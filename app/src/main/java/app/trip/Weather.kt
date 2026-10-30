package app.trip

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** One day of the forecast: temperatures in °C, [rain] the chance of rain in percent. */
class WeatherDay(val date: LocalDate, val code: Int, val hi: Int, val lo: Int, val rain: Int?) {
    val word get() = weatherWord(code)
}

/**
 * Daily forecast for [away] from Open-Meteo (free, no key; data CC BY 4.0). The city is found with
 * Open-Meteo's geocoder by name, picking the match in the trip's time zone, so a trip needs no
 * coordinates. Forecasts reach 16 days ahead.
 */
class WeatherRepo(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
    var days by mutableStateOf(parse(prefs.getString("json", null))); private set
    var error by mutableStateOf<String?>(null); private set

    fun day(date: LocalDate) = days.firstOrNull { it.date == date }

    suspend fun refresh() {
        val at = prefs.getLong("at", 0)
        if (days.isNotEmpty() && Duration.between(Instant.ofEpochMilli(at), Instant.now()).toMinutes() < FRESH_MIN) return
        try {
            val body = withContext(Dispatchers.IO) {
                val (lat, lon) = place()
                val (code, body) = get(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=${enc(away.zone.id)}&forecast_days=16" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                )
                if (code != 200) throw IOException("Forecast service returned $code.")
                body
            }
            days = parse(body)
            prefs.edit().putString("json", body).putLong("at", Instant.now().toEpochMilli()).apply()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = if (days.isEmpty()) "No forecast yet. It loads when you're online." else "Offline. Showing the last forecast."
        }
    }

    /** The away city's coordinates, looked up once and kept. */
    private fun place(): Pair<Double, Double> {
        val key = "place:${away.name}:${away.zone.id}"
        prefs.getString(key, null)?.split(',')?.let { return it[0].toDouble() to it[1].toDouble() }
        val (code, body) = get("https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name=${enc(away.name)}")
        if (code != 200) throw IOException("Place lookup returned $code.")
        val results = JSONObject(body).optJSONArray("results") ?: throw IOException("No place called ${away.name}.")
        val all = (0 until results.length()).map { results.getJSONObject(it) }
        val hit = all.firstOrNull { it.optString("timezone") == away.zone.id } ?: all.first()
        val lat = hit.getDouble("latitude")
        val lon = hit.getDouble("longitude")
        prefs.edit().putString(key, "$lat,$lon").apply()
        return lat to lon
    }

    companion object {
        private const val FRESH_MIN = 180L

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun parse(json: String?): List<WeatherDay> = runCatching {
            val d = JSONObject(json!!).getJSONObject("daily")
            val time = d.getJSONArray("time")
            val code = d.getJSONArray("weather_code")
            val hi = d.getJSONArray("temperature_2m_max")
            val lo = d.getJSONArray("temperature_2m_min")
            val rain = d.optJSONArray("precipitation_probability_max")
            (0 until time.length()).mapNotNull { i ->
                if (hi.isNull(i) || lo.isNull(i)) return@mapNotNull null
                WeatherDay(
                    LocalDate.parse(time.getString(i)), code.optInt(i), Math.round(hi.getDouble(i)).toInt(), Math.round(lo.getDouble(i)).toInt(),
                    rain?.takeUnless { it.isNull(i) }?.getInt(i),
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
