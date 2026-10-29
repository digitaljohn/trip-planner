package app.trip

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

data class Step(val base: Instant, val m: Long, val time: String, val title: String, val detail: String = "") {
    val at: Instant get() = base.plusMin(m)
}

data class Airport(val leg: Int, val title: String, val span: String, val steps: List<Step>)

val steps: List<Step> by lazy { airports.flatMap { it.steps }.sortedBy { it.at } }

enum class Kind { PRE, GROUND, AIR, STAY, POST }

/** Where live flight data applies during a ground leg: which segment, and whether you're departing or arriving on it. */
data class Watch(val flight: String, val from: String, val arriving: Boolean)

data class Leg(
    val short: String, val name: String, val from: Instant?, val to: Instant?, val kind: Kind,
    val base: Instant? = null, val airport: Int? = null, val watch: Watch? = null,
    val tipTitle: String = "", val tip: String = "",
)

data class Status(
    val index: Int, val leg: Leg, val elapsedMin: Long?, val progress: Float,
    val doTitle: String, val doDetail: String, val current: Step?, val next: Step?,
)

fun statusAt(now: Instant, checkedIn: Boolean = false): Status {
    val i = legs.indexOfFirst { (it.from == null || !now.isBefore(it.from)) && (it.to == null || now.isBefore(it.to)) }
    val leg = legs[i]
    val progress = when {
        leg.from != null && leg.to != null ->
            (Duration.between(leg.from, now).toMillis().toFloat() / Duration.between(leg.from, leg.to).toMillis()).coerceIn(0f, 1f)
        leg.kind == Kind.POST -> 1f
        else -> 0f
    }
    val cur = steps.lastOrNull { !it.at.isAfter(now) }
    val next = steps.firstOrNull { it.at.isAfter(now) }
    var title = leg.tipTitle
    var detail = leg.tip
    when (leg.kind) {
        Kind.GROUND -> if (cur != null) { title = cur.title; detail = cur.detail }
        Kind.STAY -> {
            title = AWAY_GREETING
            detail = "Day ${Duration.between(leg.from, now).toDays() + 1} of ${Duration.between(leg.from, leg.to).toDays()}. Flight home in ${dur(Duration.between(now, RET))}."
        }
        Kind.AIR -> detail = "$detail Lands in ${dur(Duration.between(now, leg.to))}."
        Kind.PRE -> {
            if (checkedIn) { title = "Checked in"; detail = CHECKED_IN_NOTE }
            detail = "${segments.first().flight} leaves in ${dur(Duration.between(now, OUT))}. $detail"
        }
        Kind.POST -> Unit
    }
    return Status(i, leg, leg.base?.let { Duration.between(it, now).toMinutes() }, progress, title, detail, cur, next)
}

fun dur(d: Duration): String {
    val m = d.toMinutes().coerceAtLeast(0)
    val days = m / 1440
    val h = (m % 1440) / 60
    return when {
        days > 0 -> "${days}d ${h}h"
        h > 0 -> "${h}h ${m % 60}m"
        else -> "${m % 60}m"
    }
}

fun clock(min: Long): String = "${if (min < 0) "−" else "+"}${abs(min) / 60}:${"%02d".format(abs(min) % 60)}"

fun stepTag(m: Long): String = if (m == 0L) "take-off" else "${if (m < 0) "−" else "+"}${abs(m) / 60}h${"%02d".format(abs(m) % 60)}"

// Preview slider: each leg gets an equal share, so the short stops are as easy to reach as the 13-day stay.
private fun legStart(l: Leg) = l.from ?: OUT.plusMin(-360)
private fun legEnd(l: Leg) = l.to ?: RET.plusMin(1620)

fun sliderToTime(v: Float): Instant {
    val k = v.toInt().coerceIn(0, legs.size - 1)
    val a = legStart(legs[k])
    val span = Duration.between(a, legEnd(legs[k])).toMillis()
    return a.plusMillis((span * (v - k).coerceIn(0f, 1f)).toLong())
}

fun timeToSlider(t: Instant): Float {
    val k = legs.indexOfFirst { t.isBefore(legEnd(it)) }
    if (k < 0) return legs.size.toFloat()
    val a = legStart(legs[k])
    val f = Duration.between(a, t).toMillis().toFloat() / Duration.between(a, legEnd(legs[k])).toMillis()
    return k + f.coerceIn(0f, 1f)
}

fun isDone(ctx: android.content.Context, todo: String) =
    ctx.getSharedPreferences("todo", android.content.Context.MODE_PRIVATE).getBoolean(todo, false)

fun setDone(ctx: android.content.Context, todo: String, done: Boolean) =
    ctx.getSharedPreferences("todo", android.content.Context.MODE_PRIVATE).edit().putBoolean(todo, done).apply()

/** The check-in whose window is open now and not yet marked done. */
fun openCheckIn(ctx: android.content.Context, now: Instant) =
    checkIns.firstOrNull { !now.isBefore(it.opens) && now.isBefore(it.departs) && !isDone(ctx, it.todo) }
