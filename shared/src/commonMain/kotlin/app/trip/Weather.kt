package app.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import kotlin.time.Clock

/** One day of the forecast: temperatures in °C, [rain] the chance of rain in percent. */
class WeatherDay(val date: LocalDate, val code: Int, val hi: Int, val lo: Int, val rain: Int?) {
    val word get() = weatherWord(code)
}

/**
 * Daily forecast for [away] from Open-Meteo (free, no key; data CC BY 4.0). The city is found with
 * Open-Meteo's geocoder by name, picking the match in the trip's time zone, so a trip needs no
 * coordinates. Forecasts reach 16 days ahead.
 */
class WeatherRepo {
    private val prefs = store("weather")
    var days by mutableStateOf(parse(prefs.string("json"))); private set
    var error by mutableStateOf<String?>(null); private set

    fun day(date: LocalDate) = days.firstOrNull { it.date == date }

    suspend fun refresh() {
        val at = prefs.long("at")
        if (days.isNotEmpty() && Duration.between(Instant.fromEpochMilliseconds(at), Clock.System.now()).toMinutes() < FRESH_MIN) return
        try {
            val (lat, lon) = place()
            val (code, body) = get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=${enc(away.zone.id)}&forecast_days=16" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
            )
            if (code != 200) throw NetError("Forecast service returned $code.")
            days = parse(body)
            prefs.put("json" to body, "at" to Clock.System.now().toEpochMilli())
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (days.isEmpty()) "No forecast yet. It loads when you're online." else "Offline. Showing the last forecast."
        }
    }

    /** The away city's coordinates, looked up once and kept. */
    private suspend fun place(): Pair<Double, Double> {
        val key = "place:${away.name}:${away.zone.id}"
        prefs.string(key)?.split(',')?.let { return it[0].toDouble() to it[1].toDouble() }
        val (code, body) = get("https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name=${enc(away.name)}")
        if (code != 200) throw NetError("Place lookup returned $code.")
        val results = parseJson(body).jsonObject.arr("results") ?: throw NetError("No place called ${away.name}.")
        val all = results.map { it.jsonObject }
        val hit = all.firstOrNull { it.str("timezone") == away.zone.id } ?: all.first()
        val lat = hit.getValue("latitude").jsonPrimitive.double
        val lon = hit.getValue("longitude").jsonPrimitive.double
        prefs.put(key to "$lat,$lon")
        return lat to lon
    }

    companion object {
        private const val FRESH_MIN = 180L

        /** As java.net.URLEncoder: letters, digits and .-*_ kept, space as +, the rest as %XX of UTF-8. */
        private fun enc(s: String) = buildString {
            for (b in s.encodeToByteArray()) {
                val c = b.toInt().toChar()
                when {
                    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ".-*_" -> append(c)
                    c == ' ' -> append('+')
                    else -> append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
                }
            }
        }

        private fun parse(json: String?): List<WeatherDay> = runCatching {
            val d = parseJson(json!!).jsonObject.getValue("daily").jsonObject
            val time = d.arr("time")!!
            val code = d.arr("weather_code")!!
            val hi = d.arr("temperature_2m_max")!!
            val lo = d.arr("temperature_2m_min")!!
            val rain = d.arr("precipitation_probability_max")
            fun num(e: kotlinx.serialization.json.JsonElement) = e.jsonPrimitive.double
            time.indices.mapNotNull { i ->
                if (hi[i].isNull() || lo[i].isNull()) return@mapNotNull null
                WeatherDay(
                    LocalDate.parse(time[i].jsonPrimitive.content), (code.getOrNull(i) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() } ?: 0,
                    num(hi[i]).roundToInt(), num(lo[i]).roundToInt(),
                    rain?.getOrNull(i)?.takeUnless { it.isNull() }?.let { num(it).toInt() },
                )
            }
        }.getOrDefault(emptyList())
    }
}

/** WMO weather code, as Open-Meteo reports it, in one short word (it labels a narrow forecast column). */
fun weatherWord(code: Int): String = when (code) {
    0 -> "Clear"
    1 -> "Fair"
    2 -> "Clouds"
    3 -> "Overcast"
    45, 48 -> "Fog"
    in 51..57 -> "Drizzle"
    in 61..67 -> "Rain"
    in 71..77 -> "Snow"
    in 80..82 -> "Showers"
    85, 86 -> "Snow"
    in 95..99 -> "Storms"
    else -> "—"
}
