package app.trip

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// What each platform supplies: storage, network, formatting, the phone's own apps, haptics, location,
// and the system alerts (lock screen, notifications, widget). Android's are in androidMain, iOS's in iosMain.

/**
 * Small persistent settings, one [name]d area each ("flights", "todo", …). On Android these are the
 * SharedPreferences files the app has always used, so updating an installed app keeps its data.
 */
interface Store {
    fun string(key: String): String?
    fun long(key: String, default: Long = 0): Long
    fun int(key: String, default: Int = 0): Int
    fun bool(key: String, default: Boolean = false): Boolean
    /** Saves every pair at once; a null value removes the key. Values are String, Long, Int or Boolean. */
    fun put(vararg pairs: Pair<String, Any?>)
}

expect fun store(name: String): Store

class NetError(message: String) : Exception(message)

/** An HTTP GET: the status code and body (the error body for a failed status). Throws when unreachable. */
internal expect suspend fun get(url: String): Pair<Int, String>

/** java.lang.String.format on Android (so the device locale applies, as before); the same patterns on iOS. */
expect fun String.format(vararg args: Any?): String

/** "18:25" and "Sat 18:25" at [zone]. */
expect fun hm(t: Instant, zone: ZoneId): String
expect fun dhm(t: Instant, zone: ZoneId): String

/** "29 Oct". */
expect fun dayMonth(d: LocalDate): String

/** The phone's own apps and services. */
expect object Phone {
    fun dial(number: String)
    fun open(url: String)
    /** Opens an app: [android] is its package, [ios] a URL that opens it. Android falls back to the Play Store page. */
    fun openApp(android: String, ios: String?)
    /** Whether [openApp] would open the app itself on this phone. */
    fun hasApp(android: String?, ios: String?): Boolean
    /** Whether an app button is worth showing: Android always has the Play Store to fall back on; iOS needs the trip's [ios] link. */
    fun offersApp(ios: String?): Boolean
    /** The airline's app when [preferApp] and it's on the phone, else its online check-in. */
    fun openCheckIn(preferApp: Boolean)
    fun openSimSettings()
    /** Public-transport directions from here to [query]. */
    fun directions(query: String)
    fun maps(query: String)
    fun copy(label: String, value: String)
    /** The phone's wallet app ("Google Wallet"), or null when there isn't one. */
    val wallet: String?
    fun openWallet()
}

interface Haptics {
    fun tap()
    fun tick()
    fun confirm()
    fun reject()
    fun toggle(on: Boolean)
}

@Composable
expect fun rememberHaptics(): Haptics

/** A location fix: degrees, when it was taken (epoch ms), and its accuracy in metres. */
class Fix(val lat: Double, val lon: Double, val timeMs: Long, val accuracyM: Float)

/** Live location while composed (null until permission is granted and a fix arrives), and whether it's allowed. */
@Composable
expect fun rememberLocation(): Pair<Fix?, Boolean>

/** Keeps a horizontal drag near the screen edge from being taken as the system back gesture. */
expect fun Modifier.noBackGesture(): Modifier

/**
 * The system's side of the trip: the lock-screen status, step and check-in alerts, gate alerts and the
 * home-screen widget. [refresh] redraws them all for the current moment.
 */
expect object Alerts {
    fun refresh()
    fun enabled(): Boolean
    fun setEnabled(on: Boolean)
    fun markCheckedIn(todo: String)
    fun testCheckIn()
    fun previewFlightDay()
    /** Alerts for what changed in freshly fetched flight data. */
    fun flightAlerts(segs: Map<String, Seg>)
    /** Asks the home screen to add the widget; false where the person has to add it themselves. */
    fun addWidget(): Boolean
    /** How to add the widget by hand, where [addWidget] can't. */
    val widgetHint: String?
}

/** Asks for permission to alert, once, then refreshes the alerts. */
@Composable
expect fun StartAlerts()

/** Anything the platform needs said or asked under the notification settings (Android: exact alarms). */
@Composable
expect fun AlertSettingsExtra()
