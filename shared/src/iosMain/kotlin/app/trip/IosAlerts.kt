package app.trip

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSBundle
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.time.Clock

// iOS can't run code at a chosen moment the way Android's alarms do. Instead, every step and check-in
// alert is handed to the system in advance as a scheduled notification, the widget gets a precomputed
// timeline, and the Live Activity counts down on its own between the app's refreshes (on opening, and
// in background refreshes, which iOS runs when it chooses).

/**
 * What the Swift side does for Kotlin: ActivityKit and WidgetKit have no Objective-C API.
 * The app passes one to [IosApp.start] at launch.
 */
interface IosHost {
    /** Starts or updates the Live Activity with [state] (JSON for TripActivity.ContentState); null ends it. */
    fun live(state: String?)
    fun reloadWidgets()
}

/** Entry points for the Swift app. */
object IosApp {
    internal var host: IosHost? = null
    // The notification centre holds its delegate weakly.
    private val notifications = NotificationDelegate()

    fun start(host: IosHost) {
        this.host = host
        UNUserNotificationCenter.currentNotificationCenter().delegate = notifications
        IosAlerts.registerCategories()
    }

    /** The app came to the foreground. */
    fun foreground() = Alerts.refresh()

    /** A background refresh: redraws everything and, when a lookup is due, fetches the flight first. [done] runs once finished. */
    fun background(done: () -> Unit) {
        Alerts.refresh()
        val w = Schedule.airportWatch(Clock.System.now())
        val flights = FlightRepo()
        if (w == null || flights.due() != w) return done()
        flights.refreshNow(w) { Alerts.refresh(); done() }
    }
}

private const val CHECK_IN = "checkin"
private const val TODO = "todo"

internal object IosAlerts {
    val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()
    private val appName get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleDisplayName") as? String ?: "Trip"

    fun ask() = center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge) { _, _ ->
        dispatch_async(dispatch_get_main_queue()) { Alerts.refresh() }
    }

    /** The check-in alert's buttons, as on Android: the airline, the membership number, and Done. */
    fun registerCategories() {
        val actions = listOfNotNull(
            UNNotificationAction.actionWithIdentifier("open", if (airline.iosApp != null) "Open ${airline.name} app" else "Open ${airline.name} online", UNNotificationActionOptionForeground),
            loyalty?.let { UNNotificationAction.actionWithIdentifier("copy", "Copy ${it.short} no.", UNNotificationActionOptionNone) },
            UNNotificationAction.actionWithIdentifier("done", "Done", UNNotificationActionOptionNone),
        )
        center.setNotificationCategories(setOf(
            UNNotificationCategory.categoryWithIdentifier(CHECK_IN, actions, emptyList<String>(), UNNotificationCategoryOptionNone),
        ))
    }

    fun notify(id: String, title: String, body: String, at: Instant? = null, category: String? = null, todo: String? = null) {
        val c = UNMutableNotificationContent()
        c.setTitle(title)
        c.setBody(body)
        c.setSound(UNNotificationSound.defaultSound)
        category?.let { c.setCategoryIdentifier(it) }
        todo?.let { c.setUserInfo(mapOf(TODO to it)) }
        val wait = at?.let { (it - Clock.System.now()).inWholeMilliseconds / 1000.0 }
        if (wait != null && wait < 1) return
        val trigger = wait?.let { UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(it, repeats = false) }
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(id, c, trigger), null)
    }

    fun postCheckIn(c: CheckIn, reminder: Boolean, at: Instant? = null) {
        val (title, text) = Schedule.checkInText(c, reminder)
        notify("checkin-${c.todo}-${if (reminder) "remind" else "open"}", title, text, at, CHECK_IN, c.todo)
    }

    private fun stepId(i: Int) = "step-$i"

    /**
     * Replaces every pending step and check-in alert with the ones still to come. iOS keeps at most 64
     * pending, which a trip's steps and check-ins stay well under; the soonest are kept if not.
     */
    fun schedule(now: Instant) {
        center.removeAllPendingNotificationRequests()
        val pending = mutableListOf<Pair<Instant, () -> Unit>>()
        steps.forEachIndexed { i, st ->
            if (st.at.isAfter(now)) pending += st.at to { notify(stepId(i), st.title, st.detail.ifBlank { "${st.time} local" }, st.at) }
        }
        checkIns.filter { !isDone(it.todo) }.forEach { c ->
            if (c.opens.isAfter(now)) pending += c.opens to { postCheckIn(c, reminder = false, at = c.opens) }
            if (c.remindAt.isAfter(now)) pending += c.remindAt to { postCheckIn(c, reminder = true, at = c.remindAt) }
        }
        pending.sortedBy { it.first }.take(60).forEach { it.second() }
        // Android lets step alerts time out after two hours; here they're cleared on the next refresh after that.
        center.removeDeliveredNotificationsWithIdentifiers(steps.indices.filter { steps[it].at.isBefore(now.plusSeconds(-7200)) }.map(::stepId))
    }

    fun clearCheckIn(todo: String) {
        val ids = listOf("checkin-$todo-open", "checkin-$todo-remind")
        center.removeDeliveredNotificationsWithIdentifiers(ids)
        center.removePendingNotificationRequestsWithIdentifiers(ids)
    }

    private fun secs(t: Instant?): JsonElement = t?.let { JsonPrimitive(it.toEpochMilliseconds() / 1000.0) } ?: JsonNull
    private fun text(s: String?): JsonElement = s?.let(::JsonPrimitive) ?: JsonNull

    /** The Live Activity's content at [now] (later than the real now for the preview): the same facts as Android's status card, for TripActivity.swift. */
    fun liveState(now: Instant, s: Status, flights: FlightRepo?): String {
        val leg = s.leg
        val w = leg.watch
        val seg = w?.let { flights?.seg(it.flight, it.from) }
        val where = if (w?.arriving == true) seg?.belt?.let { "Belt $it" } else seg?.gate?.let { "Gate $it" }
        val next = s.next?.takeIf { leg.kind != Kind.STAY }
        val day = flightDays.firstOrNull { d -> !now.isBefore(d.first().from!!) && now.isBefore(d.last().to!!) }
        val moving = leg.kind == Kind.AIR || leg.kind == Kind.GROUND
        return JsonObject(mapOf(
            "at" to secs(now),
            "title" to text(s.doTitle),
            "detail" to text(s.doDetail),
            "leg" to text(leg.name),
            "chip" to text(s.chip(now)),
            "place" to text(where),
            "next" to text(next?.let { "Next ${it.time} ${it.title}" }),
            "nextAt" to secs(next?.at),
            "legFrom" to secs(leg.from.takeIf { moving }),
            "legTo" to secs(leg.to.takeIf { moving }),
            "day" to JsonArray(day.orEmpty().map { l ->
                JsonObject(mapOf("from" to secs(l.from), "to" to secs(l.to), "air" to JsonPrimitive(l.kind == Kind.AIR)))
            }),
        )).toString()
    }

    /**
     * The home-screen widget's timeline: what it should show every 15 minutes for the next day and a
     * half, and at each step, as the Android widget would after each alarm. WidgetKit swaps entries on time.
     */
    fun widgetTimeline(now: Instant) {
        val end = now.plusSeconds(36 * 3600L)
        val times = (generateSequence(now) { it.plusSeconds(15 * 60L) }.takeWhile { it.isBefore(end) } +
            steps.map { it.at }.filter { it.isAfter(now) && it.isBefore(end) }).sorted().distinct().toList()
        val checked = isDone("checkin")
        val entries = times.map { t ->
            val s = statusAt(t, checked)
            val next = s.next?.takeIf { s.leg.kind != Kind.STAY }
            JsonObject(mapOf(
                "at" to secs(t),
                "leg" to text("${s.leg.name} · ${s.chip(t)}".uppercase()),
                "title" to text(s.doTitle),
                "detail" to text(s.doDetail),
                "next" to text(next?.let { "Next ${it.time} · ${it.title}" }),
                "nextAt" to secs(next?.at),
            ))
        }
        store("widget").put("entries" to JsonArray(entries).toString())
        IosApp.host?.reloadWidgets()
    }

    val widgetHint get() = "To add the widget, touch and hold the home screen, tap Edit, then Add Widget, and pick $appName."
}

private class NotificationDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) = withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList or UNNotificationPresentationOptionSound)

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val content = didReceiveNotificationResponse.notification.request.content
        val todo = content.userInfo[TODO] as? String
        if (content.categoryIdentifier == CHECK_IN) {
            when (didReceiveNotificationResponse.actionIdentifier) {
                "open" -> Phone.openCheckIn(preferApp = true)
                "copy" -> loyalty?.let { Phone.copy("${it.short} ${it.memberName}", it.number) }
                "done" -> todo?.let(Alerts::markCheckedIn)
                UNNotificationDefaultActionIdentifier -> Phone.openCheckIn(preferApp = false)
            }
        }
        withCompletionHandler()
    }
}

actual object Alerts {
    actual fun enabled() = store("notify").bool("on", true)

    actual fun setEnabled(on: Boolean) {
        store("notify").put("on" to on)
        refresh()
    }

    actual fun refresh() {
        val now = Clock.System.now()
        IosAlerts.widgetTimeline(now)
        if (!enabled() || now.isAfter(Schedule.END)) {
            IosAlerts.center.removeAllPendingNotificationRequests()
            IosApp.host?.live(null)
            return
        }
        IosAlerts.schedule(now)
        IosApp.host?.live(IosAlerts.liveState(now, statusAt(now, isDone("checkin")), FlightRepo()))
    }

    actual fun markCheckedIn(todo: String) {
        setDone(todo, true)
        IosAlerts.clearCheckIn(todo)
        refresh()
    }

    actual fun testCheckIn() = IosAlerts.postCheckIn(checkIns[0], reminder = false)

    /** Shows the Live Activity as it will look partway through the first flight; the next refresh restores the real one. */
    actual fun previewFlightDay() {
        val first = legs.first { it.kind == Kind.AIR }
        val at = OUT.plusMin(minOf(400L, Duration.between(first.from!!, first.to!!).toMinutes() / 2))
        IosApp.host?.live(IosAlerts.liveState(at, statusAt(at), null))
    }

    actual fun flightAlerts(segs: Map<String, Seg>) {
        if (!enabled()) return
        Schedule.flightAlerts(segs, Clock.System.now()) { id, title, text -> IosAlerts.notify("flight-$id", title, text) }
    }

    actual fun addWidget() = false
    actual val widgetHint: String? get() = IosAlerts.widgetHint
}
