package app.trip

/** trip.private.* entries from the trip's trip.properties (or local.properties), compiled in so they never sit in the repo. */
private val private: Map<String, String> by lazy {
    BuildConfig.TRIP_PRIVATE.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1) } }.toMap()
}

/**
 * A personal value (booking reference, membership number, confirmation code) from
 * `trip.private.<key>` in trip.properties or local.properties, or [example] when it isn't set, so a fresh clone builds
 * and shows plausible placeholders.
 */
fun secret(key: String, example: String): String = private[key] ?: example

fun secretInt(key: String, example: Int): Int = private[key]?.toIntOrNull() ?: example
