package app.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

// org.json's lenient readers, which the parsing below was written against.
internal fun parseJson(s: String): JsonElement = Json.parseToJsonElement(s)
internal fun JsonObject.obj(k: String) = this[k] as? JsonObject
internal fun JsonObject.arr(k: String) = this[k] as? JsonArray
/** A value as text: null when missing, JSON null, blank or the text "null". */
internal fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() && it != "null" }
internal fun JsonElement?.isNull() = this == null || this is JsonNull

/** One flight segment as reported by Aviationstack. Times are local "HH:mm" strings at each airport. */
data class Seg(
    val number: String, val from: String, val to: String, val status: String, val depDate: String?,
    val depSched: String?, val depRevised: String?, val depTerminal: String?, val gate: String?, val depDelay: Long?,
    val arrSched: String?, val arrRevised: String?, val arrTerminal: String?, val belt: String?, val arrDelay: Long?,
    val aircraft: String?,
) {
    val statusLabel: String get() = status.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
    val troubled: Boolean get() = status.startsWith("Cancel") || status == "Diverted" || (depDelay ?: 0) >= 15

    companion object {
        private fun JsonObject.s(k: String) = str(k)
        private fun JsonObject.mins(k: String) = this[k].let { if (it.isNull()) null else (it as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.doubleOrNull?.toLong() } ?: 0L }
        private fun hm(s: String?) = s?.let { Regex("""T(\d{2}:\d{2})""").find(it)?.groupValues?.get(1) }

        /** Aviationstack's times are local despite their "+00:00" suffix, so only the digits are used. */
        fun parse(o: JsonObject): Seg {
            val d = o.obj("departure") ?: JsonObject(emptyMap())
            val a = o.obj("arrival") ?: JsonObject(emptyMap())
            val depSched = hm(d.s("scheduled"))
            val arrSched = hm(a.s("scheduled"))
            return Seg(
                number = o.obj("flight")?.s("iata").orEmpty(),
                from = d.s("iata") ?: "?", to = a.s("iata") ?: "?",
                status = when (o.s("flight_status")) {
                    "scheduled" -> "Expected"; "active" -> "EnRoute"; "landed" -> "Arrived"
                    "cancelled" -> "Canceled"; "diverted" -> "Diverted"; "incident" -> "Incident"; else -> "Unknown"
                },
                depDate = d.s("scheduled")?.take(10),
                depSched = depSched, depRevised = hm(d.s("actual") ?: d.s("estimated"))?.takeIf { it != depSched },
                depTerminal = d.s("terminal"), gate = d.s("gate"), depDelay = d.mins("delay"),
                arrSched = arrSched, arrRevised = hm(a.s("actual") ?: a.s("estimated"))?.takeIf { it != arrSched },
                arrTerminal = a.s("terminal"), belt = a.s("baggage"), arrDelay = a.mins("delay"),
                aircraft = o.obj("aircraft")?.s("iata"),
            )
        }
    }
}

/**
 * Live flight status from Aviationstack, cached for offline use. The free plan is 100 lookups a
 * month, counted here because the API doesn't report what's left. [due] spends them only around the
 * airports: every [STALE_MIN] minutes while plenty remain, then only at a few set moments. A tap on the
 * refresh button always goes through, down to [RESERVE] lookups kept back for the background checks.
 */
class FlightRepo {
    private val prefs = store("flights")
    var updatedAt by mutableStateOf(prefs.long("at").takeIf { it > 0 }?.let(Instant::fromEpochMilliseconds)); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private val segments = mutableStateMapOf<String, Seg>()
    // Fetches outlive any one screen; tying them to composition cancelled them on every tick or tab change.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        prefs.string("cache")?.let { runCatching { absorb(parseJson(it).jsonObject) } }
    }

    fun seg(flight: String, from: String): Seg? = segments["$flight:$from"]

    /** Lookups used this calendar month. */
    fun used(): Int = if (prefs.string("month") == yearMonth()) prefs.int("used") else 0

    fun left(): Int = (LIMIT - used()).coerceAtLeast(0)

    /** The flight worth a background lookup now, or null to save quota. */
    fun due(now: Instant = Clock.System.now()): Watch? {
        val w = Schedule.airportWatch(now) ?: return null
        if (left() <= RESERVE) return null
        val last = Instant.fromEpochMilliseconds(prefs.long("at"))
        if (left() >= PLENTY) return w.takeIf { Duration.between(last, now).toMinutes() >= STALE_MIN - 1 }
        val slot = Schedule.checkSlots(w).filter { !it.isAfter(now) }.maxOrNull() ?: return null
        return w.takeIf { last.isBefore(slot) }
    }

    /** Picks up a fetch made by another instance (the background check) since this one last looked. */
    fun reload() {
        val at = prefs.long("at")
        if (at > (updatedAt?.toEpochMilli() ?: 0)) {
            prefs.string("cache")?.let { runCatching { absorb(parseJson(it).jsonObject) } }
            updatedAt = Instant.fromEpochMilliseconds(at)
        }
    }

    /** Looks up [w]'s flight (both legs come back) and calls [done] with whether it worked. */
    fun refreshNow(w: Watch, done: (Boolean) -> Unit = {}) {
        scope.launch { refresh(w.flight); done(error == null) }
    }

    private suspend fun refresh(flight: String) {
        if (loading) return
        if (TripBuild.AVIATIONSTACK_KEY.isBlank()) { error = "No flight data key in this build."; return }
        if (left() == 0) { error = "This month's 100 lookups are used up. Check the airport screens."; return }
        loading = true
        error = null
        try {
            prefs.put("month" to yearMonth(), "used" to used() + 1)
            val (code, body) = get("https://api.aviationstack.com/v1/flights?access_key=${TripBuild.AVIATIONSTACK_KEY}&flight_iata=$flight")
            val o = runCatching { parseJson(body).jsonObject }.getOrNull()
            o?.obj("error")?.let { e ->
                if (e.str("code") == "usage_limit_reached") prefs.put("used" to LIMIT)
                throw NetError(if (e.str("code") == "usage_limit_reached") "This month's lookups are used up." else e.str("message") ?: "Flight data service error.")
            }
            if (code != 200 || o == null) throw NetError("Flight data service returned $code.")
            save(flight, o.arr("data") ?: JsonArray(emptyList()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach the flight data service."
        } finally {
            loading = false
        }
    }

    /** Saves [data] as the newest entry for [flight], then redraws and alerts from the whole cache. */
    private fun save(flight: String, data: JsonArray) {
        val old = prefs.string("cache")?.let { runCatching { parseJson(it).jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
        val all = JsonObject(old - flight + (flight to data))
        absorb(all)
        Alerts.flightAlerts(segments)
        val at = Clock.System.now()
        updatedAt = at
        prefs.put("cache" to all.toString(), "at" to at.toEpochMilli())
    }

    /**
     * Keeps only this trip's segments (Aviationstack also returns other days and codeshares), newest
     * entry last so it wins. Entries cached by the earlier AeroDataBox build are skipped.
     */
    private fun absorb(all: JsonObject) {
        for (k in all.keys) {
            if (k !in FLIGHTS) continue
            val arr = all.getValue(k).jsonArray
            for (item in arr) {
                val s = runCatching { Seg.parse(item.jsonObject) }.getOrNull() ?: continue
                val key = "${s.number}:${s.from}"
                if (DEPARTS[key] == s.depDate) segments[key] = s
            }
        }
    }

    companion object {
        const val LIMIT = 100
        const val STALE_MIN = 15L
        // Below PLENTY the background switches to set check times; RESERVE is kept for those checks.
        private const val PLENTY = 40
        private const val RESERVE = 3
        private val FLIGHTS by lazy { segments.map { it.flight }.toSet() }
        // Local departure date of each leg, keyed by flight and origin.
        val DEPARTS by lazy { segments.associate { it.key to it.departDate } }
    }
}

/** Daily exchange rates for the trip's local currency from ExchangeRate-API's open endpoint (no key; attribution required). Falls back to the last saved rates. */
class RatesRepo {
    private val prefs = store("rates")
    var rates by mutableStateOf(load()); private set
    var date by mutableStateOf(prefs.string("date")); private set
    var error by mutableStateOf<String?>(null); private set

    private fun load(): Map<String, Double> =
        prefs.string("json")?.let { j -> parseJson(j).jsonObject.mapValues { it.value.jsonPrimitive.double } }
            ?: money.others.associate { it.code to it.estimate }

    suspend fun refresh() {
        try {
            val (code, body) = get("https://open.er-api.com/v6/latest/${money.code}")
            if (code != 200) throw NetError("Rates service returned $code.")
            val o = parseJson(body).jsonObject
            val all = o.getValue("rates").jsonObject
            val r = CURRENCIES.associateWith { all.getValue(it).jsonPrimitive.double }
            rates = r
            date = (o["time_last_update_utc"] as? JsonPrimitive)?.content.orEmpty().take(16).ifBlank { null }
            prefs.put("json" to JsonObject(r.mapValues { JsonPrimitive(it.value) }).toString(), "date" to date)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Offline. Using rates from ${date ?: "a built-in estimate"}."
        }
    }

    companion object {
        val CURRENCIES = money.others.map { it.code }
    }
}
