@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.trip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

// ---- terminal model ----

enum class SpotKind(val glyph: String) { FOOD("F"), SMOKE("S"), SHOWER("W"), DESK("T"), TRAIN("⇄"), LOUNGE("L"), GARDEN("G") }

class Spot(val kind: SpotKind, val name: String, val note: String, val lat: Double, val lon: Double, val level: Int = 0)

/** A leg of the way to a gate; [train] legs are ridden, not walked. */
class Waypoint(val lat: Double, val lon: Double, val train: Boolean = false)

class Route(val points: List<Offset>, val train: List<Boolean>, val minutes: Int)

/**
 * A departure terminal drawn north-up in metres from its OSM origin, so a GPS fix and the gate
 * positions land on the same map without any fitting. [via] gives the pier roots between the
 * hub (where you start: after security, or the transfer hall) and a gate.
 */
class Terminal(
    val name: String,
    private val lat0: Double, private val lon0: Double,
    val outlines: List<FloatArray>, val dim: List<FloatArray>, val rails: List<FloatArray>,
    gates: Map<String, FloatArray>,
    val levels: List<String>,
    hub: Pair<Double, Double>,
    val spots: List<Spot>,
    val note: String,
    corridors: List<List<Pair<Double, Double>>> = emptyList(),
    private val trainMin: Int = 0,
    /** What Google Maps should search for, before "Gate X". */
    val searchName: String = name,
    /** Walking guidance for a gate, where the terminal needs more than "follow the signs". */
    val hint: (String) -> String? = { null },
    private val via: (String) -> List<Waypoint>,
) {
    private val c = cos(Math.toRadians(lat0))
    fun at(lat: Double, lon: Double) = Offset(((lon - lon0) * 111320 * c).toFloat(), (-(lat - lat0) * 110540).toFloat())

    val gates: Map<String, Offset> = gates.mapValues { Offset(it.value[0], it.value[1]) }
    val hub = at(hub.first, hub.second)
    val corridors: List<List<Offset>> = corridors.map { l -> l.map { at(it.first, it.second) } }

    val bounds: Rect = run {
        val pts = outlines.flatMap { a -> a.indices.step(2).map { Offset(a[it], a[it + 1]) } } +
            this.gates.values + this.corridors.flatten() + spots.map { at(it.lat, it.lon) } + this.hub
        Rect(pts.minOf { it.x } - 50f, pts.minOf { it.y } - 50f, pts.maxOf { it.x } + 50f, pts.maxOf { it.y } + 50f)
    }

    /** Matches a live gate ("D36", "55A", "A10") to a mapped one. */
    fun gate(code: String?): String? {
        val g = code?.uppercase()?.replace(" ", "") ?: return null
        return g.takeIf { it in gates } ?: g.dropLast(1).takeIf { g.last().isLetter() && it in gates }
    }

    fun route(code: String?): Route? {
        val g = gate(code) ?: return null
        val ws = via(g)
        val pts = listOf(hub) + ws.map { at(it.lat, it.lon) } + gates.getValue(g)
        val train = listOf(false) + ws.map { it.train } + false
        var walk = 0f
        for (i in 1 until pts.size) if (!train[i]) walk += (pts[i] - pts[i - 1]).getDistance()
        return Route(pts, train, (walk / WALK_M_PER_MIN).roundToInt() + if (train.any { it }) trainMin else 0)
    }

    /** Metres from [p] to the nearest mapped part of the terminal. */
    fun distanceTo(p: Offset): Float =
        (outlines.flatMap { a -> a.indices.step(2).map { Offset(a[it], a[it + 1]) } } + gates.values + corridors.flatten() + hub)
            .minOf { (it - p).getDistance() }

    companion object { const val WALK_M_PER_MIN = 75f }
}

// ---- the plan for each departure ----

class Phase(val label: String, val from: Long, val to: Long, val free: Boolean = false)

/**
 * One departure airport visit. Minutes are relative to take-off. Free time runs from [freeStart] until
 * you need to set off for the gate: boarding ([boardAt]) less the walk and any [queue] at the gate.
 * A live delay pushes the gate and boarding times back, so free time grows with it.
 */
class Stop(
    val airport: Int, val terminal: Terminal, val takeOff: Instant, val watch: Watch, val zone: java.time.ZoneId,
    private val before: List<Phase>, private val freeStart: Long, private val boardAt: Long,
    private val walkLabel: String, private val defaultWalk: Int, private val queue: Int = 0,
) {
    fun phases(gate: String?, delayMin: Long): List<Phase> {
        val walk = (terminal.route(gate)?.minutes ?: defaultWalk) + queue
        val board = boardAt + delayMin
        return before + Phase("Free", freeStart, board - walk, free = true) + Phase(walkLabel, board - walk, board) + Phase("Board", board, delayMin)
    }
}

fun stopFor(airport: Int?) = stops.firstOrNull { it.airport == airport }

/** A Google Maps search for [gate]; Maps' indoor maps resolve "Gate X" in most large terminals. */
fun gateQuery(airport: String, gate: String) = "${terminals[airport]?.searchName ?: "$airport airport"} Gate $gate"

fun gateHint(airport: String, gate: String): String = terminals[airport]?.hint?.invoke(gate) ?: "Follow the gate signs; allow 10–15 min."

// ---- location ----

/** Live location while composed; null until permission is granted and a fix arrives. */
@Composable
fun rememberLocation(): Pair<Location?, Boolean> {
    val ctx = LocalContext.current
    fun granted() = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    var fix by remember { mutableStateOf<Location?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = granted() }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    DisposableEffect(allowed) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { lm.hasProvider(it) }
        val listener = LocationListener { fix = it }
        if (allowed && provider != null) {
            runCatching {
                fix = lm.getLastKnownLocation(provider)
                lm.requestLocationUpdates(provider, LocationRequest.Builder(3_000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build(), ctx.mainExecutor, listener)
            }
        }
        onDispose { lm.removeUpdates(listener) }
    }
    return fix to allowed
}

/** Where [fix] puts you on [t]: the map point, or how far away you are when that's outside the terminal. */
class Whereabouts(val at: Offset?, val awayM: Float?, val accuracyM: Float)

fun whereabouts(t: Terminal, fix: Location?): Whereabouts? {
    fix ?: return null
    if (Duration.between(Instant.ofEpochMilli(fix.time), Instant.now()).toMinutes() > 10) return null
    val p = t.at(fix.latitude, fix.longitude)
    val d = t.distanceTo(p)
    return if (d > IN_TERMINAL_M) Whereabouts(null, d, fix.accuracy) else Whereabouts(p, null, fix.accuracy)
}

private const val IN_TERMINAL_M = 250f

fun distanceText(m: Float) = when {
    m >= 100_000 -> "%,d km".format((m / 1000).roundToInt())
    m >= 1000 -> "%.1f km".format(m / 1000)
    else -> "${(m / 10).roundToInt() * 10} m"
}

// ---- drawing ----

@Composable
fun TerminalMap(t: Terminal, level: Int, gate: String?, here: Whereabouts?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "you").animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    val b = t.bounds
    val g = t.gate(gate)
    val route = t.route(g)
    Canvas(modifier.fillMaxWidth().aspectRatio((b.width / b.height).coerceIn(0.75f, 1.8f)).clip(RoundedCornerShape(10.dp)).background(cs.surfaceVariant)) {
        val k = minOf(size.width / b.width, size.height / b.height)
        val ox = (size.width - b.width * k) / 2 - b.left * k
        val oy = (size.height - b.height * k) / 2 - b.top * k
        fun p(o: Offset) = Offset(o.x * k + ox, o.y * k + oy)
        fun poly(a: FloatArray) = Path().apply {
            moveTo(a[0] * k + ox, a[1] * k + oy)
            for (i in 2 until a.size step 2) lineTo(a[i] * k + ox, a[i + 1] * k + oy)
            close()
        }
        val step = 7.dp.toPx()
        var y = step / 2
        while (y < size.height) { var x = step / 2; while (x < size.width) { drawCircle(cs.outlineVariant, 1.1.dp.toPx(), Offset(x, y)); x += step }; y += step }

        val floor = cs.surfaceContainerLowest
        t.dim.forEach { drawPath(poly(it), floor.copy(alpha = 0.45f)) }
        t.outlines.forEach { drawPath(poly(it), floor); drawPath(poly(it), cs.outline.copy(alpha = 0.5f), style = Stroke(1.dp.toPx())) }
        t.corridors.forEachIndexed { i, l ->
            val path = Path().apply { l.forEachIndexed { j, o -> p(o).let { if (j == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
            drawPath(path, if (i == 0) floor.copy(alpha = 0.45f) else floor, style = Stroke(34f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
        t.rails.forEach { a ->
            val path = Path().apply { moveTo(a[0] * k + ox, a[1] * k + oy); for (i in 2 until a.size step 2) lineTo(a[i] * k + ox, a[i + 1] * k + oy) }
            drawPath(path, cs.onSurfaceVariant.copy(alpha = 0.6f), style = Stroke(1.5.dp.toPx(), pathEffect = dash))
        }

        t.gates.values.forEach { drawCircle(cs.onSurfaceVariant.copy(alpha = 0.55f), 2.dp.toPx(), p(it)) }
        val small = TextStyle(fontFamily = PlexMono, fontSize = 8.sp, color = cs.onSurfaceVariant)
        groupEnds(t.gates.keys).forEach { code -> if (code != g) label(tm, code, p(t.gates.getValue(code)) + Offset(0f, -9.dp.toPx()), small) }

        if (level == 0 && route != null) {
            val dot = PathEffect.dashPathEffect(floatArrayOf(0.1f, 6.dp.toPx()))
            for (i in 1 until route.points.size) {
                drawLine(cs.primary, p(route.points[i - 1]), p(route.points[i]), 3.dp.toPx(), StrokeCap.Round,
                    pathEffect = if (route.train[i]) PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 4.dp.toPx())) else dot)
            }
        }
        drawCircle(cs.onSurface, 5.dp.toPx(), p(t.hub), style = Stroke(2.dp.toPx()))

        t.spots.filter { it.level == level }.forEach { s ->
            val c = p(t.at(s.lat, s.lon))
            val bg = if (s.kind == SpotKind.SMOKE) cs.primary else cs.onSurface
            drawRoundRect(bg, c - Offset(8.dp.toPx(), 8.dp.toPx()), Size(16.dp.toPx(), 16.dp.toPx()), CornerRadius(5.dp.toPx()))
            label(tm, s.kind.glyph, c, TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 9.sp, color = if (s.kind == SpotKind.SMOKE) cs.onPrimary else cs.surface))
        }

        if (g != null) {
            val c = p(t.gates.getValue(g))
            drawCircle(cs.primary, 13.dp.toPx(), c)
            label(tm, g, c, TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = if (g.length > 3) 7.5.sp else 9.sp, color = cs.onPrimary))
        }
        here?.at?.let { at ->
            val c = p(at)
            drawCircle(cs.primary.copy(alpha = 0.15f), (here.accuracyM * k).coerceAtLeast(10.dp.toPx()), c)
            drawCircle(cs.primary.copy(alpha = pulse), 11.dp.toPx(), c)
            drawCircle(cs.surface, 7.dp.toPx(), c)
            drawCircle(cs.onSurface, 5.dp.toPx(), c)
        }
    }
}

private fun DrawScope.label(tm: TextMeasurer, text: String, center: Offset, style: TextStyle) {
    val r = tm.measure(text, style)
    drawText(r, topLeft = center - Offset(r.size.width / 2f, r.size.height / 2f))
}

/** First and last gate of each run (C1…C26, 50…63), so the map stays readable. */
private fun groupEnds(codes: Set<String>): List<String> = codes
    .groupBy { c -> c.takeWhile(Char::isLetter).ifEmpty { ((c.filter(Char::isDigit).toIntOrNull() ?: 0) / 10).toString() } }
    .values.flatMap { l -> l.sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.let { listOf(it.first(), it.last()).distinct() } }

// ---- composed pieces ----

@Composable
fun SpotRow(t: Terminal, s: Spot, from: Offset) {
    val cs = MaterialTheme.colorScheme
    val min = ((t.at(s.lat, s.lon) - from).getDistance() * 1.3f / Terminal.WALK_M_PER_MIN).roundToInt().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(if (s.kind == SpotKind.SMOKE) cs.primary else cs.onSurface),
            contentAlignment = Alignment.Center,
        ) { Text(s.kind.glyph, style = MaterialTheme.typography.labelMedium, color = if (s.kind == SpotKind.SMOKE) cs.onPrimary else cs.surface) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Muted(s.note)
        }
        Label("$min min")
    }
}

/** "You're here", "about 2.3 km away" or nothing, under a map. */
@Composable
fun WhereLine(here: Whereabouts?, allowed: Boolean, t: Terminal) {
    val text = when {
        !allowed -> "Allow location to see yourself on the map."
        here == null -> "Finding you…"
        here.awayM != null -> "Looks like you're not in the terminal: ${distanceText(here.awayM)} from ${t.name}."
        here.accuracyM > 80 -> "You're roughly here (±${here.accuracyM.roundToInt()} m). Indoor location is rough and can't tell floors apart."
        else -> "You're here (±${here.accuracyM.roundToInt()} m)."
    }
    Muted(text)
}

@Composable
fun TerminalSheet(stop: Stop, gate: String?, onDismiss: () -> Unit) {
    val t = stop.terminal
    val cs = MaterialTheme.colorScheme
    var level by remember { mutableIntStateOf(0) }
    val (fix, allowed) = rememberLocation()
    val here = whereabouts(t, fix)
    val g = t.gate(gate)
    val route = t.route(g)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = cs.surfaceContainerHighest) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(t.name.uppercase(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Label("North up")
            }
            if (t.levels.size > 1) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    t.levels.forEachIndexed { i, l ->
                        SegmentedButton(selected = level == i, onClick = { level = i }, shape = SegmentedButtonDefaults.itemShape(i, t.levels.size)) { Text(l) }
                    }
                }
            }
            TerminalMap(t, level, g, here)
            WhereLine(here, allowed, t)
            when {
                g != null && route != null -> Text(
                    "Gate $g · about ${route.minutes} min from ${if (here?.at != null) "the hall" else "the hall after ${if (stop.airport == 3 || stop.airport == 9) "transfer" else "security"}"}" +
                        (if (route.train.any { it }) ", including the train" else "") +
                        (here?.at?.let { " · ${distanceText((t.gates.getValue(g) - it).getDistance())} from you" } ?: ""),
                    style = MaterialTheme.typography.titleSmall,
                )
                gate != null -> Muted("Gate $gate isn't on this map yet. Follow the screens.")
                else -> Muted("Your gate appears here once it's announced, usually about an hour before boarding.")
            }
            val from = here?.at ?: t.hub
            Column {
                t.spots.filter { it.level == level }.sortedBy { (t.at(it.lat, it.lon) - from).getDistance() }.forEach { s ->
                    HorizontalDivider(color = cs.outlineVariant)
                    SpotRow(t, s, from)
                }
            }
            Muted(t.note)
            Label("Map data © OpenStreetMap contributors · gates can change, so check the screens")
        }
    }
}

@Composable
fun StopCard(stop: Stop, now: Instant, seg: Seg?, onMap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val t = stop.terminal
    val delay = (seg?.depDelay ?: 0).coerceAtLeast(0).let { if (it < 5) 0 else it }
    val phases = stop.phases(seg?.gate, delay)
    val free = phases.first { it.free }
    fun at(m: Long) = stop.takeOff.plusMin(m)
    val zone = stop.zone
    val freeFrom = at(free.from)
    val freeTo = at(free.to)
    val left = Duration.between(now, freeTo).toMinutes()
    Panel(spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Free time", modifier = Modifier.weight(1f))
            Text(
                "LEAVE FOR GATE ${hm(freeTo, zone)}", style = MaterialTheme.typography.labelSmall, color = cs.primary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(cs.primaryContainer).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        when {
            now.isBefore(freeFrom) -> {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${Duration.between(freeFrom, freeTo).toMinutes()}", style = MaterialTheme.typography.displayMedium)
                    Text(" MIN", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
                }
                Muted("Free from ${hm(freeFrom, zone)}, once you're through ${phases.first().label.lowercase().let { if (it == "off") "transfer" else "security" }}.")
            }
            left > 0 -> Row(verticalAlignment = Alignment.Bottom) {
                RollingText("$left", MaterialTheme.typography.displayMedium, cs.onSurface)
                Text(" MIN LEFT", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 10.dp))
            }
            else -> Text(seg?.gate?.let { "Head to gate $it" } ?: "Head to the gate", style = MaterialTheme.typography.headlineSmall, color = cs.primary)
        }
        Budget(phases, Duration.between(stop.takeOff, now).toMinutes())
        Row {
            Label(hm(at(phases.first().from), zone), modifier = Modifier.weight(1f))
            Label(hm(freeTo, zone), modifier = Modifier.weight(1f), )
            Label("${hm(at(delay), zone)} off")
        }
        if (delay > 0) Text("Running ${delay}m late, so you have longer.", style = MaterialTheme.typography.bodySmall, color = cs.primary)
        val near = t.spots.filter { it.level == 0 && it.kind != SpotKind.TRAIN }.sortedBy { (t.at(it.lat, it.lon) - t.hub).getDistance() }.take(3)
        Column {
            near.forEach { s -> HorizontalDivider(color = cs.outlineVariant); SpotRow(t, s, t.hub) }
        }
        FilledTonalButton(onClick = onMap) { Icon(Ic.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Terminal map") }
    }
}

/** The visit as proportional blocks, free time in red, with a tick at the current minute. */
@Composable
private fun Budget(phases: List<Phase>, nowMin: Long) {
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val start = phases.first().from
    val end = phases.last().to
    Canvas(Modifier.fillMaxWidth().height(30.dp)) {
        val span = (end - start).toFloat()
        val gap = 3.dp.toPx()
        phases.forEach { ph ->
            val x0 = (ph.from - start) / span * size.width
            val x1 = (ph.to - start) / span * size.width - gap
            if (x1 <= x0) return@forEach
            val bg = if (ph.free) cs.primary else cs.surfaceVariant
            drawRoundRect(bg, Offset(x0, 0f), Size(x1 - x0, size.height), CornerRadius(4.dp.toPx()))
            val r = tm.measure(ph.label.uppercase(), TextStyle(fontFamily = PlexMono, fontSize = 9.sp, letterSpacing = 0.5.sp, color = if (ph.free) cs.onPrimary else cs.onSurfaceVariant))
            if (r.size.width < x1 - x0 - 4) drawText(r, topLeft = Offset(x0 + (x1 - x0 - r.size.width) / 2, (size.height - r.size.height) / 2))
        }
        if (nowMin in start..end) {
            val x = (nowMin - start) / span * size.width
            drawLine(cs.onSurface, Offset(x, -2f), Offset(x, size.height + 2f), 2.dp.toPx())
        }
    }
}
