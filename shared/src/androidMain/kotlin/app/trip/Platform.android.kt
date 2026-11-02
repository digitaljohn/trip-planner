package app.trip

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.Uri
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaZoneId
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.time.format.DateTimeFormatter
import kotlin.time.toJavaInstant

/** Holds the application context for the platform functions below; receivers and the activity all start after it. */
class TripApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
    }

    companion object {
        lateinit var app: Context
        /** The open screen, so links opened from it stay in its task (back returns to the app). */
        var activity: WeakReference<Activity>? = null
    }
}

private val ctx get() = TripApp.app

actual fun store(name: String): Store = object : Store {
    private val p = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun string(key: String) = p.getString(key, null)
    override fun long(key: String, default: Long) = p.getLong(key, default)
    override fun int(key: String, default: Int) = p.getInt(key, default)
    override fun bool(key: String, default: Boolean) = p.getBoolean(key, default)
    override fun put(vararg pairs: Pair<String, Any?>) {
        val e = p.edit()
        pairs.forEach { (k, v) ->
            when (v) {
                null -> e.remove(k)
                is String -> e.putString(k, v)
                is Long -> e.putLong(k, v)
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                else -> error("Can't store ${v::class.simpleName} for $k")
            }
        }
        e.apply()
    }
}

internal actual suspend fun get(url: String): Pair<Int, String> = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 10_000
    c.readTimeout = 15_000
    try {
        val code = c.responseCode
        code to (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    } finally {
        c.disconnect()
    }
}

actual fun String.format(vararg args: Any?): String = java.lang.String.format(this, *args)

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DHM: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
private val DM: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
actual fun hm(t: Instant, zone: ZoneId): String = HM.format(t.toJavaInstant().atZone(zone.toJavaZoneId()))
actual fun dhm(t: Instant, zone: ZoneId): String = DHM.format(t.toJavaInstant().atZone(zone.toJavaZoneId()))
actual fun dayMonth(d: LocalDate): String = DM.format(d.toJavaLocalDate())

private const val WALLET = "com.google.android.apps.walletnfcrel"

actual object Phone {
    private fun start(i: Intent) = runCatching {
        TripApp.activity?.get()?.startActivity(i) ?: ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun launch(pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg)

    actual fun dial(number: String) { start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
    actual fun open(url: String) { start(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    actual fun openApp(android: String, ios: String?) {
        launch(android)?.let { start(it) } ?: open("https://play.google.com/store/apps/details?id=$android")
    }
    actual fun hasApp(android: String?, ios: String?) = android?.let(::launch) != null
    actual fun offersApp(ios: String?) = true
    actual fun openCheckIn(preferApp: Boolean) { start(TripNotifier.checkInIntent(ctx, preferApp)) }

    /** The SIMs page under Network & internet; falls back to the network page where the direct one isn't exported. */
    actual fun openSimSettings() {
        listOf(android.provider.Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS, android.provider.Settings.ACTION_WIRELESS_SETTINGS)
            .firstOrNull { start(Intent(it)).isSuccess }
    }
    actual fun directions(query: String) = open("https://www.google.com/maps/dir/?api=1&travelmode=transit&destination=" + Uri.encode(query))
    actual fun maps(query: String) = open("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query))
    actual fun copy(label: String, value: String) =
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
    actual val wallet: String? get() = if (launch(WALLET) != null) "Google Wallet" else null
    actual fun openWallet() = openApp(WALLET, null)
}

private class ViewHaptics(private val view: View) : Haptics {
    private fun go(c: Int) { view.performHapticFeedback(c) }
    override fun tap() = go(HapticFeedbackConstants.CONTEXT_CLICK)
    override fun tick() = go(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    override fun confirm() = go(HapticFeedbackConstants.CONFIRM)
    override fun reject() = go(HapticFeedbackConstants.REJECT)
    override fun toggle(on: Boolean) = go(
        if (Build.VERSION.SDK_INT >= 34) (if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
        else HapticFeedbackConstants.CONTEXT_CLICK,
    )
}

@Composable
actual fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { ViewHaptics(view) }
}

@Composable
actual fun rememberLocation(): Pair<Fix?, Boolean> {
    val ctx = LocalContext.current
    fun granted() = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    var fix by remember { mutableStateOf<Fix?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = granted() }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    DisposableEffect(allowed) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { lm.hasProvider(it) }
        fun android.location.Location.toFix() = Fix(latitude, longitude, time, accuracy)
        val listener = LocationListener { fix = it.toFix() }
        if (allowed && provider != null) {
            runCatching {
                fix = lm.getLastKnownLocation(provider)?.toFix()
                lm.requestLocationUpdates(provider, LocationRequest.Builder(3_000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build(), ctx.mainExecutor, listener)
            }
        }
        onDispose { lm.removeUpdates(listener) }
    }
    return fix to allowed
}

actual fun Modifier.noBackGesture(): Modifier = systemGestureExclusion()

actual object Alerts {
    actual fun refresh() = TripNotifier.refresh(ctx)
    actual fun enabled() = TripNotifier.enabled(ctx)
    actual fun setEnabled(on: Boolean) = TripNotifier.setEnabled(ctx, on)
    actual fun markCheckedIn(todo: String) = TripNotifier.markCheckedIn(ctx, todo)
    actual fun testCheckIn() = TripNotifier.testCheckIn(ctx)
    actual fun previewFlightDay() = TripNotifier.previewFlightDay(ctx)
    actual fun flightAlerts(segs: Map<String, Seg>) = TripNotifier.flightAlerts(ctx, segs)
    actual fun addWidget() = TripWidget.pin(ctx)
    actual val widgetHint: String? = null
}

@Composable
actual fun StartAlerts() {
    val ctx = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Alerts.refresh() }
    LaunchedEffect(Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Alerts.refresh()
        }
    }
}

@Composable
actual fun AlertSettingsExtra() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AlarmManager::class.java) }
    if (!am.canScheduleExactAlarms()) {
        Muted("Alerts may arrive a few minutes late until you allow alarms for this app.")
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
        }) { Text("Allow on-time alerts") }
    }
}
