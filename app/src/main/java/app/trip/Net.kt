package app.trip

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.YearMonth

private fun get(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 15_000
    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
    return try {
        val code = c.responseCode
        code to (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    } finally {
        c.disconnect()
    }
}

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
        private fun JSONObject.s(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }
        private fun JSONObject.mins(k: String) = if (isNull(k)) null else optLong(k)
        private fun hm(s: String?) = s?.let { Regex("""T(\d{2}:\d{2})""").find(it)?.groupValues?.get(1) }

        /** Aviationstack's times are local despite their "+00:00" suffix, so only the digits are used. */
        fun parse(o: JSONObject): Seg {
            val d = o.optJSONObject("departure") ?: JSONObject()
            val a = o.optJSONObject("arrival") ?: JSONObject()
            val depSched = hm(d.s("scheduled"))
            val arrSched = hm(a.s("scheduled"))
            return Seg(
                number = o.optJSONObject("flight")?.s("iata").orEmpty(),
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
                aircraft = o.optJSONObject("aircraft")?.s("iata"),
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
class FlightRepo(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("flights", Context.MODE_PRIVATE)
    var updatedAt by mutableStateOf(prefs.getLong("at", 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli)); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private val segments = mutableStateMapOf<String, Seg>()
    // Fetches outlive any one screen; tying them to composition cancelled them on every tick or tab change.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        prefs.getString("cache", null)?.let { runCatching { absorb(JSONObject(it)) } }
    }

    fun seg(flight: String, from: String): Seg? = segments["$flight:$from"]

    /** Lookups used this calendar month. */
    fun used(): Int = if (prefs.getString("month", "") == YearMonth.now().toString()) prefs.getInt("used", 0) else 0

    fun left(): Int = (LIMIT - used()).coerceAtLeast(0)

    /** The flight worth a background lookup now, or null to save quota. */
    fun due(now: Instant = Instant.now()): Watch? {
        val w = TripNotifier.airportWatch(now) ?: return null
        if (left() <= RESERVE) return null
        val last = Instant.ofEpochMilli(prefs.getLong("at", 0))
        if (left() >= PLENTY) return w.takeIf { Duration.between(last, now).toMinutes() >= STALE_MIN - 1 }
        val slot = TripNotifier.checkSlots(w).filter { !it.isAfter(now) }.maxOrNull() ?: return null
        return w.takeIf { last.isBefore(slot) }
    }

    /** Picks up a fetch made by another instance (the background check) since this one last looked. */
    fun reload() {
        val at = prefs.getLong("at", 0)
        if (at > (updatedAt?.toEpochMilli() ?: 0)) {
            prefs.getString("cache", null)?.let { runCatching { absorb(JSONObject(it)) } }
            updatedAt = Instant.ofEpochMilli(at)
        }
    }

    /** Looks up [w]'s flight (both legs come back) and calls [done] with whether it worked. */
    fun refreshNow(w: Watch, done: (Boolean) -> Unit = {}) {
        scope.launch { refresh(w.flight); done(error == null) }
    }

    private suspend fun refresh(flight: String) {
        if (loading) return
        if (BuildConfig.AVIATIONSTACK_KEY.isBlank()) { error = "No flight data key in this build."; return }
        if (left() == 0) { error = "This month's 100 lookups are used up. Check the airport screens."; return }
        loading = true
        error = null
        try {
            prefs.edit().putString("month", YearMonth.now().toString()).putInt("used", used() + 1).apply()
            val (code, body) = withContext(Dispatchers.IO) {
                get("https://api.aviationstack.com/v1/flights?access_key=${BuildConfig.AVIATIONSTACK_KEY}&flight_iata=$flight")
            }
            val o = runCatching { JSONObject(body) }.getOrNull()
            o?.optJSONObject("error")?.let { e ->
                if (e.optString("code") == "usage_limit_reached") prefs.edit().putInt("used", LIMIT).apply()
                throw IOException(if (e.optString("code") == "usage_limit_reached") "This month's lookups are used up." else e.optString("message", "Flight data service error."))
            }
            if (code != 200 || o == null) throw IOException("Flight data service returned $code.")
            store(flight, o.optJSONArray("data") ?: JSONArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't reach the flight data service."
        } finally {
            loading = false
        }
    }

    /** Saves [data] as the newest entry for [flight], then redraws and alerts from the whole cache. */
    private fun store(flight: String, data: JSONArray) {
        val all = prefs.getString("cache", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        all.remove(flight)
        all.put(flight, data)
        absorb(all)
        TripNotifier.flightAlerts(ctx, segments)
        val at = Instant.now()
        updatedAt = at
        prefs.edit().putString("cache", all.toString()).putLong("at", at.toEpochMilli()).apply()
    }

    /**
     * Keeps only this trip's segments (Aviationstack also returns other days and codeshares), newest
     * entry last so it wins. Entries cached by the earlier AeroDataBox build are skipped.
     */
    private fun absorb(all: JSONObject) {
        for (k in all.keys()) {
            if (k !in FLIGHTS) continue
            val arr = all.getJSONArray(k)
            for (i in 0 until arr.length()) {
                val s = runCatching { Seg.parse(arr.getJSONObject(i)) }.getOrNull() ?: continue
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
class RatesRepo(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("rates", Context.MODE_PRIVATE)
    var rates by mutableStateOf(load()); private set
    var date by mutableStateOf(prefs.getString("date", null)); private set
    var error by mutableStateOf<String?>(null); private set

    private fun load(): Map<String, Double> =
        prefs.getString("json", null)?.let { j -> JSONObject(j).let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } } }
            ?: money.others.associate { it.code to it.estimate }

    suspend fun refresh() {
        try {
            val (code, body) = withContext(Dispatchers.IO) { get("https://open.er-api.com/v6/latest/${money.code}") }
            if (code != 200) throw IOException("Rates service returned $code.")
            val o = JSONObject(body)
            val all = o.getJSONObject("rates")
            val r = JSONObject()
            for (c in CURRENCIES) r.put(c, all.getDouble(c))
            rates = CURRENCIES.associateWith { r.getDouble(it) }
            date = o.optString("time_last_update_utc").take(16).ifBlank { null }
            prefs.edit().putString("json", r.toString()).putString("date", date).apply()
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
