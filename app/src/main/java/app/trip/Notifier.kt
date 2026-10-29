package app.trip

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import java.time.Duration
import java.time.Instant

/**
 * Lock-screen status plus an alert at each airport step. Driven by one alarm at a time:
 * each firing redraws the status, posts the step alert it was set for, then sets the next alarm
 * (the next step, or a 15-minute refresh, whichever is sooner). The chronometer in the status
 * notification counts down on its own between alarms.
 */
object TripNotifier {
    // Pixel hides silent (low-importance) notifications on the lock screen, so the status card uses a
    // default-importance channel with sound and vibration turned off. Channel importance can't be raised
    // after creation, hence the new id; the old "status" channel is deleted.
    private const val CH_STATUS = "live"
    private const val CH_STEPS = "steps"
    private const val CH_CHECKIN = "checkin"
    private const val CH_FLIGHT = "flight_alerts"
    private const val ID_STATUS = 1
    private const val ID_CHECKIN = 50
    private const val EXTRA_AT = "at"
    const val ACTION_CHECKED_IN = "dev.jch.sydneytrip.CHECKED_IN"
    const val ACTION_COPY_MEMBER = "dev.jch.sydneytrip.COPY_MEMBER"
    const val EXTRA_TODO = "todo"
    private val END: Instant = RET.plusMin(1555)

    fun enabled(ctx: Context) = ctx.getSharedPreferences("notify", Context.MODE_PRIVATE).getBoolean("on", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("notify", Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
        refresh(ctx)
    }

    fun refresh(ctx: Context, firedAt: Long = -1) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        createChannels(nm)
        val now = Instant.now()
        if (!enabled(ctx) || now.isAfter(END)) {
            nm.cancel(ID_STATUS)
            return
        }
        if (firedAt > 0) {
            steps.indexOfFirst { it.at.toEpochMilli() == firedAt }.takeIf { it >= 0 }?.let { postStep(ctx, nm, it) }
            checkIns.firstOrNull { (it.opens.toEpochMilli() == firedAt || it.remindAt.toEpochMilli() == firedAt) && !isDone(ctx, it.todo) }
                ?.let { postCheckIn(ctx, nm, it, reminder = it.remindAt.toEpochMilli() == firedAt) }
        }
        nm.notify(ID_STATUS, status(ctx, statusAt(now, isDone(ctx, "checkin")), now, FlightRepo(ctx.applicationContext)))
        scheduleNext(ctx, now)
    }

    private fun createChannels(nm: NotificationManager) {
        nm.deleteNotificationChannel("status")
        nm.deleteNotificationChannel("flight")
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Trip status", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Where you are in the trip, always on the lock screen"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_CHECKIN, "Check-in", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When online check-in opens, and a reminder the morning of the flight"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_FLIGHT, "Gate and delay alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When a gate is announced or changes, delays, and your baggage belt"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_STEPS, "Step alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "An alert as each airport step comes up"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun status(ctx: Context, s: Status, now: Instant, flights: FlightRepo? = null): Notification {
        val leg = s.leg
        val w = leg.watch
        val seg = w?.let { flights?.seg(it.flight, it.from) }
        val where = if (w?.arriving == true) seg?.belt?.let { "Belt $it" } else seg?.gate?.let { "Gate $it" }
        val next = s.next
        val chip = when (leg.kind) {
            Kind.PRE -> "T" + clock(Duration.between(OUT, now).toMinutes())
            Kind.STAY -> "Day ${Duration.between(leg.from, now).toDays() + 1}"
            Kind.POST -> "Home"
            else -> "T" + clock(s.elapsedMin ?: 0)
        }
        val b = Notification.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setLargeIcon(Icon.createWithResource(ctx, R.drawable.ic_badge))
            .setContentTitle(s.doTitle)
            .setContentText(listOfNotNull(where, chip, leg.name, next?.let { "Next ${it.time} ${it.title}" }).joinToString(" · "))
            .setSubText(leg.name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp(ctx))
            .setColor(0xFFFF453C.toInt())
        if (next != null && leg.kind != Kind.STAY) {
            b.setWhen(next.at.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        val day = journeyDay(now)
        if (Build.VERSION.SDK_INT >= 36 && day != null) {
            b.setStyle(journeyProgress(ctx, day, now))
        } else {
            b.setStyle(Notification.BigTextStyle().bigText(listOfNotNull(where, s.doDetail.ifBlank { null }).joinToString(" · ").ifBlank { null }))
            if (leg.kind == Kind.AIR || leg.kind == Kind.GROUND) b.setProgress(1000, (s.progress * 1000).toInt(), false)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            // Android 16 Live Update: a status-bar chip and a promoted spot on the lock screen.
            b.setShortCriticalText(chip)
            b.extras.putBoolean("android.requestPromotedOngoing", true)
        }
        return b.build()
    }

    /** The flight day's legs (airport, flight, stop, flight, arrival) if [now] falls inside one. */
    private fun journeyDay(now: Instant): List<Leg>? = flightDays
        .firstOrNull { d -> !now.isBefore(d.first().from) && now.isBefore(d.last().to) }

    /**
     * The whole travel day as one bar: red segments for flights, grey for the ground, a dot at each
     * take-off and landing, and a plane riding along. Units are minutes. Android draws at most a
     * handful of points, so only the four flight edges get one.
     */
    private fun journeyProgress(ctx: Context, day: List<Leg>, now: Instant): Notification.Style {
        val start = day.first().from!!
        fun min(t: Instant) = Duration.between(start, t).toMinutes().toInt()
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressTrackerIcon(Icon.createWithResource(ctx, R.drawable.ic_tracker))
            .setProgress(min(now).coerceIn(0, min(day.last().to!!)))
        day.forEach { l ->
            style.addProgressSegment(Notification.ProgressStyle.Segment(min(l.to!!) - min(l.from!!))
                .setColor(if (l.kind == Kind.AIR) 0xFFFF453C.toInt() else 0xFFB4B4BA.toInt()))
        }
        day.filter { it.kind == Kind.AIR }.flatMap { listOf(it.from!!, it.to!!) }.forEach {
            style.addProgressPoint(Notification.ProgressStyle.Point(min(it)).setColor(0xFFF6F6F3.toInt()))
        }
        return style
    }

    /** Opens the airline's app if installed, else its online check-in page. */
    fun checkInIntent(ctx: Context, preferApp: Boolean): Intent =
        (if (preferApp) ctx.packageManager.let { pm -> airline.app?.let(pm::getLaunchIntentForPackage) } else null)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse(airline.checkInUrl))

    private fun postCheckIn(ctx: Context, nm: NotificationManager, c: CheckIn, reminder: Boolean) {
        val text = if (reminder) "You haven't checked in for ${c.flight} yet. It leaves ${c.when_}. Booking ${airline.bookingRef}."
            else "Check in now for ${c.when_}. Booking ${airline.bookingRef}, last name ${airline.surname}." + (loyalty?.let { " ${it.memberName} ${it.number}." } ?: "")
        fun activity(code: Int, i: Intent) = PendingIntent.getActivity(ctx, code, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val done = PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, TripAlarm::class.java).setAction(ACTION_CHECKED_IN).putExtra(EXTRA_TODO, c.todo),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val copy = PendingIntent.getBroadcast(
            ctx, 6, Intent(ctx, TripAlarm::class.java).setAction(ACTION_COPY_MEMBER), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(ctx, CH_CHECKIN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (reminder) "Still need to check in: ${c.flight}" else "${c.flight} check-in is open")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(activity(3, checkInIntent(ctx, preferApp = false)))
            .addAction(Notification.Action.Builder(null, "Open ${airline.name} app", activity(4, checkInIntent(ctx, preferApp = true))).build())
        loyalty?.let { n.addAction(Notification.Action.Builder(null, "Copy ${it.short} no.", copy).build()) }
        nm.notify(ID_CHECKIN, n
            .addAction(Notification.Action.Builder(null, "Done", done).build())
            // Pinned until Done so it can't be swiped away and forgotten.
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setColor(0xFFFF453C.toInt())
            .build())
    }

    /** Shows the status card as it will look partway through the first flight; the next refresh restores the real one. */
    fun previewFlightDay(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        createChannels(nm)
        val first = legs.first { it.kind == Kind.AIR }
        val at = OUT.plusMin(minOf(400L, Duration.between(first.from, first.to).toMinutes() / 2))
        nm.notify(ID_STATUS, status(ctx, statusAt(at), at))
    }

    /** Shows the first check-in alert now, whatever the date, so the buttons can be tried out. */
    fun testCheckIn(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        createChannels(nm)
        postCheckIn(ctx, nm, checkIns[0], reminder = false)
    }

    fun markCheckedIn(ctx: Context, todo: String) {
        setDone(ctx, todo, true)
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_CHECKIN)
        refresh(ctx)
    }

    private fun postStep(ctx: Context, nm: NotificationManager, i: Int) {
        val st = steps[i]
        nm.notify(100 + i, Notification.Builder(ctx, CH_STEPS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(st.title)
            .setContentText(st.detail.ifBlank { "${st.time} local" })
            .setStyle(Notification.BigTextStyle().bigText(st.detail.ifBlank { "${st.time} local" }))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp(ctx))
            .setColor(0xFFFF453C.toInt())
            .setTimeoutAfter(Duration.ofHours(2).toMillis())
            .build())
    }

    private fun scheduleNext(ctx: Context, now: Instant) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val tick = now.plusSeconds(15 * 60)
        val event = (steps.map { it.at } + checkIns.flatMap { listOf(it.opens, it.remindAt) })
            .filter { it.isAfter(now) }.minOrNull()
        val (at, firedAt) = if (event != null && !event.isAfter(tick)) event to event.toEpochMilli() else tick to -1L
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, TripAlarm::class.java).putExtra(EXTRA_AT, firedAt),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
    }

    fun firedAt(intent: Intent) = intent.getLongExtra(EXTRA_AT, -1)

    /** The flight to keep checking while you're at (or an hour from) an airport. */
    fun airportWatch(now: Instant): Watch? = legs.firstOrNull { l ->
        l.airport != null && l.watch != null && l.from != null && l.to != null && !now.isBefore(l.from.plusMin(-60)) && now.isBefore(l.to)
    }?.watch

    /** The flight the refresh button looks up: the one at the airport now, else the next to leave. */
    fun nextWatch(now: Instant): Watch = airportWatch(now) ?: when {
        else -> (segments.firstOrNull { now.isBefore(it.departs) } ?: segments.last()).let { Watch(it.flight, it.from, false) }
    }

    private val DEP_AT by lazy { segments.associate { it.key to it.departs } }
    /** Landings where you collect bags: the last leg of each flight number. */
    private val ARR_AT by lazy { segments.filterIndexed { i, s -> segments.getOrNull(i + 1)?.flight != s.flight }.associate { it.key to it.arrives } }

    /** A leg that continues a flight number from an earlier stop, as opposed to the first leg out of an airport you arrived at by road. */
    private fun isConnection(key: String) = segments.indexOfFirst { it.key == key }.let { i -> i > 0 && segments[i - 1].flight == segments[i].flight }
    /** When to look up [w] while quota is low: before each take-off, and once after landing for the belt. */
    fun checkSlots(w: Watch): List<Instant> {
        val key = "${w.flight}:${w.from}"
        if (w.arriving) return listOfNotNull(ARR_AT[key]?.plusMin(15))
        val dep = DEP_AT[key] ?: return emptyList()
        return (if (isConnection(key)) listOf(-60L, -35L) else listOf(-90L, -60L, -35L)).map { dep.plusMin(it) }
    }

    private val TO by lazy { segments.associate { it.key to it.toCity } }

    /**
     * Alerts for what changed since the last fetch, from either the app or the background check:
     * a gate announced or moved, a delay crossing a 15-minute step, a cancellation, or the belt for
     * your bags. What was last alerted is kept in prefs, so two fetches never alert twice.
     */
    fun flightAlerts(ctx: Context, segs: Map<String, Seg>) {
        if (!enabled(ctx)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        createChannels(nm)
        val p = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        val e = p.edit()
        val now = Instant.now()
        segs.forEach { (key, s) ->
            val flight = key.substringBefore(':')
            val id = 300 + DEP_AT.keys.indexOf(key).coerceAtLeast(0) * 4
            if (DEP_AT[key]?.isAfter(now) == true) {
                val old = p.getString("gate:$key", null)
                if (s.gate != null && s.gate != old) {
                    post(ctx, nm, id, if (old == null) "Gate ${s.gate} for $flight" else "Gate changed: $old → ${s.gate}",
                        "$flight to ${TO[key]}${s.depTerminal?.let { " · Terminal $it" } ?: ""} · leaves ${s.depRevised ?: s.depSched}. Tap for the terminal map.")
                    e.putString("gate:$key", s.gate)
                }
                val d = s.depDelay ?: 0
                val step = if (d >= 15) d / 15 * 15 else 0
                val was = p.getLong("delay:$key", 0)
                if (step != was) {
                    if (step > 0) post(ctx, nm, id + 1, "$flight delayed about $d min", "Now leaves ${s.depRevised ?: "later"} local.")
                    else post(ctx, nm, id + 1, "$flight back on schedule", "Leaves ${s.depSched} local.")
                    e.putLong("delay:$key", step)
                }
                if (s.status.startsWith("Cancel") && !p.getBoolean("cx:$key", false)) {
                    post(ctx, nm, id + 2, "$flight is cancelled", DISRUPTION_HELP)
                    e.putBoolean("cx:$key", true)
                }
            }
            val lands = ARR_AT[key]
            if (lands != null && now.isAfter(lands.plusMin(-180)) && now.isBefore(lands.plusMin(120)) && s.belt != null && s.belt != p.getString("belt:$key", null)) {
                post(ctx, nm, id + 3, "Bags on belt ${s.belt}", "$flight${s.arrTerminal?.let { " · Terminal $it" } ?: ""}.")
                e.putString("belt:$key", s.belt)
            }
        }
        e.apply()
    }

    private fun post(ctx: Context, nm: NotificationManager, id: Int, title: String, text: String) {
        nm.notify(id, Notification.Builder(ctx, CH_FLIGHT)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(openApp(ctx))
            .setColor(0xFFFF453C.toInt())
            .build())
    }
}

class TripAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            TripNotifier.ACTION_CHECKED_IN -> TripNotifier.markCheckedIn(ctx, intent.getStringExtra(TripNotifier.EXTRA_TODO) ?: return)
            TripNotifier.ACTION_COPY_MEMBER -> loyalty?.let { ctx.copy("${it.short} ${it.memberName}", it.number) }
            else -> {
                TripNotifier.refresh(ctx, TripNotifier.firedAt(intent))
                val w = TripNotifier.airportWatch(Instant.now()) ?: return
                val flights = FlightRepo(ctx.applicationContext)
                if (flights.due() != w) return
                val pending = goAsync()
                flights.refreshNow(w) { TripNotifier.refresh(ctx); pending.finish() }
            }
        }
    }
}
