package app.trip

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSBundle
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestUseProtocolCachePolicy
import platform.Foundation.NSURLSession
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.timeIntervalSince1970
import platform.PassKit.PKPassLibrary
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UIPasteboard
import platform.UIKit.UISelectionFeedbackGenerator
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** The app group shared with the widget extension (Info.plist's TripAppGroup); the app's own defaults without one. */
internal val defaults: NSUserDefaults by lazy {
    (NSBundle.mainBundle.objectForInfoDictionaryKey("TripAppGroup") as? String)?.takeIf { it.isNotBlank() }
        ?.let { NSUserDefaults(suiteName = it) } ?: NSUserDefaults.standardUserDefaults
}

actual fun store(name: String): Store = object : Store {
    private fun k(key: String) = "$name.$key"
    override fun string(key: String) = defaults.stringForKey(k(key))
    override fun long(key: String, default: Long) = if (defaults.objectForKey(k(key)) == null) default else defaults.integerForKey(k(key))
    override fun int(key: String, default: Int) = long(key, default.toLong()).toInt()
    override fun bool(key: String, default: Boolean) = if (defaults.objectForKey(k(key)) == null) default else defaults.boolForKey(k(key))
    override fun put(vararg pairs: Pair<String, Any?>) = pairs.forEach { (key, v) ->
        when (v) {
            null -> defaults.removeObjectForKey(k(key))
            is String -> defaults.setObject(v, k(key))
            is Long -> defaults.setInteger(v, k(key))
            is Int -> defaults.setInteger(v.toLong(), k(key))
            is Boolean -> defaults.setBool(v, k(key))
            else -> error("Can't store ${v::class.simpleName} for $key")
        }
    }
}

@OptIn(BetaInteropApi::class)
internal actual suspend fun get(url: String): Pair<Int, String> = suspendCancellableCoroutine { cont ->
    val req = NSURLRequest.requestWithURL(NSURL.URLWithString(url)!!, NSURLRequestUseProtocolCachePolicy, 15.0)
    val task = NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, error ->
        if (error != null) {
            cont.resumeWithException(NetError(error.localizedDescription))
        } else {
            val code = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
            val body = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }.orEmpty()
            cont.resume(code to body)
        }
    }
    cont.invokeOnCancellation { task.cancel() }
    task.resume()
}

private val SPEC = Regex("""%([,0]*)(\d*)(?:\.(\d+))?([dfs%])""")

/** The printf subset the app uses: %d, %s and %f with the `,` (grouping) and `0` (padding) flags, width and precision. */
actual fun String.format(vararg args: Any?): String {
    var n = 0
    return SPEC.replace(this) { m ->
        val (flags, width, prec, conv) = m.destructured
        if (conv == "%") return@replace "%"
        val a = args[n++]
        val grouped = ',' in flags
        val text = when (conv) {
            "d" -> (a as Number).toLong().let { (if (it < 0) "-" else "") + group(abs(it).toString(), grouped) }
            "f" -> fixed((a as Number).toDouble(), prec.ifEmpty { "6" }.toInt(), grouped)
            else -> a.toString()
        }
        val w = width.toIntOrNull() ?: 0
        when {
            text.length >= w -> text
            '0' in flags && text.startsWith("-") -> "-" + text.drop(1).padStart(w - 1, '0')
            '0' in flags -> text.padStart(w, '0')
            else -> text.padStart(w)
        }
    }
}

private fun group(digits: String, on: Boolean) = if (!on) digits else digits.reversed().chunked(3).joinToString(",").reversed()

/** Rounds half up, as java.util.Formatter does. */
private fun fixed(v: Double, places: Int, grouped: Boolean): String {
    val scale = 10.0.pow(places)
    val r = floor(abs(v) * scale + 0.5)
    val whole = floor(r / scale).toLong()
    val frac = (r - whole * scale).toLong().toString().padStart(places, '0')
    val sign = if (v < 0 && r > 0) "-" else ""
    return sign + group(whole.toString(), grouped) + if (places > 0) ".$frac" else ""
}

private fun pad2(n: Int) = n.toString().padStart(2, '0')
private fun DayOfWeek.short() = name.take(3).lowercase().replaceFirstChar { it.uppercase() }
private fun Month.short() = name.take(3).lowercase().replaceFirstChar { it.uppercase() }

actual fun hm(t: Instant, zone: ZoneId): String = t.atZone(zone).let { "${pad2(it.hour)}:${pad2(it.minute)}" }
actual fun dhm(t: Instant, zone: ZoneId): String = "${t.atZone(zone).dayOfWeek.short()} ${hm(t, zone)}"
actual fun dayMonth(d: LocalDate): String = "${d.dayOfMonth} ${d.month.short()}"

/** Percent-encodes everything but RFC 3986's unreserved characters. */
private fun enc(s: String) = buildString {
    for (b in s.encodeToByteArray()) {
        val c = (b.toInt() and 0xFF).toChar()
        if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-._~") append(c)
        else append('%').append((b.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
    }
}

actual object Phone {
    private fun go(url: String, fallback: (() -> Unit)? = null) {
        val u = NSURL.URLWithString(url) ?: return fallback?.invoke() ?: Unit
        UIApplication.sharedApplication.openURL(u, emptyMap<Any?, Any>()) { ok -> if (!ok) fallback?.invoke() }
    }

    actual fun dial(number: String) = go("tel:" + number.filter { it.isDigit() || it == '+' })
    actual fun open(url: String) = go(url)
    actual fun openApp(android: String, ios: String?) { ios?.let { go(it) } }
    actual fun hasApp(android: String?, ios: String?) = ios != null
    actual fun offersApp(ios: String?) = ios != null
    actual fun openCheckIn(preferApp: Boolean) = go(airline.iosApp?.takeIf { preferApp } ?: airline.checkInUrl)

    /** Mobile data settings where iOS still honours the link, else this app's own page in Settings. */
    actual fun openSimSettings() = go("App-prefs:MOBILE_DATA_SETTINGS_ID") { go(UIApplicationOpenSettingsURLString) }
    actual fun directions(query: String) = go("https://maps.apple.com/?daddr=${enc(query)}&dirflg=r")
    actual fun maps(query: String) = go("https://maps.apple.com/?q=${enc(query)}")
    actual fun copy(label: String, value: String) { UIPasteboard.generalPasteboard.string = value }
    actual val wallet: String? get() = if (PKPassLibrary.isPassLibraryAvailable()) "Apple Wallet" else null
    /** Wallet's own URL scheme; it opens on the pass list, nearest pass first. */
    actual fun openWallet() = go("shoebox://")
}

private object IosHaptics : Haptics {
    private fun impact(style: UIImpactFeedbackStyle) = UIImpactFeedbackGenerator(style).impactOccurred()
    override fun tap() = impact(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    override fun tick() = UISelectionFeedbackGenerator().selectionChanged()
    override fun confirm() = UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
    override fun reject() = UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
    override fun toggle(on: Boolean) = impact(if (on) UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium else UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
}

@Composable
actual fun rememberHaptics(): Haptics = IosHaptics

private class LocationDelegate(val onFix: (Fix) -> Unit, val onAllowed: (Boolean) -> Unit) : NSObject(), CLLocationManagerDelegateProtocol {
    @OptIn(ExperimentalForeignApi::class)
    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val l = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        l.coordinate.useContents { onFix(Fix(latitude, longitude, (l.timestamp.timeIntervalSince1970 * 1000).toLong(), l.horizontalAccuracy.toFloat())) }
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        val s = manager.authorizationStatus
        onAllowed(s == kCLAuthorizationStatusAuthorizedWhenInUse || s == kCLAuthorizationStatusAuthorizedAlways)
    }
}

@Composable
actual fun rememberLocation(): Pair<Fix?, Boolean> {
    var fix by remember { mutableStateOf<Fix?>(null) }
    var allowed by remember { mutableStateOf(false) }
    // CLLocationManager holds its delegate weakly, so both are remembered here.
    val delegate = remember { LocationDelegate({ fix = it }, { allowed = it }) }
    val manager = remember { CLLocationManager() }
    DisposableEffect(Unit) {
        manager.delegate = delegate
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
        onDispose {
            manager.stopUpdatingLocation()
            manager.delegate = null
        }
    }
    return fix to allowed
}

actual fun Modifier.noBackGesture(): Modifier = this

@Composable
actual fun StartAlerts() {
    LaunchedEffect(Unit) { IosAlerts.ask() }
}

@Composable
actual fun AlertSettingsExtra() = Unit
