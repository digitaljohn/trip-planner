@file:OptIn(ExperimentalLayoutApi::class)

package app.trip

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

private val tabs = listOf(
    "Now" to Ic.Now,
    "Flights" to Ic.Flight,
    "Stay" to Ic.Hotel,
    "Tools" to Ic.Tools,
    "Info" to Ic.Info,
)

@Composable
fun App() {
    val ctx = LocalContext.current
    val flights = remember { FlightRepo(ctx.applicationContext) }
    val rates = remember { RatesRepo(ctx.applicationContext) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var live by remember { mutableStateOf(Instant.now()) }
    var preview by remember { mutableStateOf<Instant?>(null) }
    LaunchedEffect(Unit) {
        while (true) { live = Instant.now(); delay(15_000) }
    }
    LaunchedEffect(tab, live) { flights.reload() }
    LaunchedEffect(Unit) { rates.refresh() }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { TripNotifier.refresh(ctx) }
    LaunchedEffect(Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TripNotifier.refresh(ctx)
        }
    }
    val now = preview ?: live
    val status = statusAt(now, isDone(ctx, "checkin"))

    val haptics = rememberHaptics()
    var intro by rememberSaveable { mutableStateOf(true) }
    Box {
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        // The scrubber belongs to Now, so leaving Now also drops back to the live time.
                        selected = tab == i, onClick = { if (tab != i) { haptics.tap(); tab = i; preview = null } },
                        icon = { Icon(icon, contentDescription = null) }, label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(180)) { -it / 8 * dir } + fadeOut(tween(140)))
            },
            modifier = Modifier.padding(pad).fillMaxSize(),
            label = "tab",
        ) { t ->
            when (t) {
                0 -> NowScreen(status, now, preview, flights) { preview = it }
                1 -> FlightsScreen(status, flights)
                2 -> StayScreen(now)
                3 -> ToolsScreen(now, rates)
                else -> InfoScreen(flights)
            }
        }
    }
    AnimatedVisibility(intro, exit = fadeOut(tween(320)) + scaleOut(targetScale = 1.06f, animationSpec = tween(320))) { Intro { intro = false } }
    }
}

// ---- shared pieces ----

@Composable
fun Page(bottom: Dp = 28.dp, content: RiseScope.() -> Unit) = LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, bottom),
    verticalArrangement = Arrangement.spacedBy(12.dp),
) { RiseScope(this).content() }

@Composable
fun Label(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier)

@Composable
fun Heading(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 8.dp))

@Composable
fun SubHeading(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))

@Composable
fun Panel(
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    padding: Dp = 16.dp,
    spacing: Dp = 6.dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = Card(colors = CardDefaults.cardColors(containerColor = container), modifier = modifier.fillMaxWidth()) {
    Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
}

@Composable
fun Muted(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun PulseDot(color: Color = MaterialTheme.colorScheme.primary) {
    val a by rememberInfiniteTransition(label = "pulse").animateFloat(
        1f, 0.25f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a",
    )
    Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(color))
}

/** Glyph-style progress: a row of dots, lit up to [progress], with the leading dot in red. */
@Composable
fun DotBar(target: Float, modifier: Modifier = Modifier, count: Int = 30) {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) { shown.animateTo(target, tween(if (shown.value == 0f) 900 else 500, easing = FastOutSlowInEasing)) }
    val progress = shown.value
    val blink by rememberInfiniteTransition(label = "lead").animateFloat(1f, 0.3f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "b")
    val on = MaterialTheme.colorScheme.onSurface
    val off = MaterialTheme.colorScheme.outlineVariant
    val red = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val gap = size.width / count
        val r = minOf(gap * 0.3f, size.height / 2)
        val lit = (progress * count).toInt()
        for (k in 0 until count) {
            val c = when {
                k < lit -> on
                k == lit && progress < 1f && progress > 0f -> red.copy(alpha = blink)
                else -> off
            }
            drawCircle(c, r, Offset(gap * k + gap / 2, size.height / 2))
        }
    }
}

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DHM: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
fun hm(t: Instant, zone: java.time.ZoneId): String = HM.format(t.atZone(zone))
fun dhm(t: Instant, zone: java.time.ZoneId): String = DHM.format(t.atZone(zone))

fun Context.dial(number: String) = runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
fun Context.open(url: String) = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
fun Context.openApp(pkg: String) = packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { startActivity(it) } }
    ?: open("https://play.google.com/store/apps/details?id=$pkg")

/** The SIMs page under Network & internet; falls back to the network page where the direct one isn't exported. */
fun Context.openSimSettings() {
    listOf(android.provider.Settings.ACTION_MANAGE_ALL_SIM_PROFILES_SETTINGS, android.provider.Settings.ACTION_WIRELESS_SETTINGS)
        .firstOrNull { runCatching { startActivity(Intent(it)) }.isSuccess }
}
fun Context.maps(query: String) = open("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(query))
fun Context.copy(label: String, value: String) =
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))

// ---- Now ----

@Composable
fun NowScreen(status: Status, now: Instant, preview: Instant?, flights: FlightRepo, setPreview: (Instant?) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val leg = status.leg
    val big = when (leg.kind) {
        Kind.PRE -> "T" + clock(Duration.between(OUT, now).toMinutes())
        Kind.STAY -> "DAY %02d".format(Duration.between(leg.from, now).toDays() + 1)
        Kind.POST -> "HOME"
        else -> "T" + clock(status.elapsedMin ?: 0)
    }
    val ctx = LocalContext.current
    var checkedTick by remember { mutableIntStateOf(0) }
    checkedTick
    val watch = leg.watch ?: if (leg.kind == Kind.STAY && Duration.between(now, RET).toHours() < 24) Watch(segments.first { it.departs == RET }.flight, segments.first { it.departs == RET }.from, false) else null
    var scrubbing by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val stop = if (leg.kind == Kind.GROUND || leg.kind == Kind.PRE) stopFor(leg.airport) else null
    val stopSeg = stop?.let { flights.seg(it.watch.flight, it.watch.from) }
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    if (mapOpen && stop != null) TerminalSheet(stop, stopSeg?.gate) { mapOpen = false }

    Box(Modifier.fillMaxSize()) {
    Page(bottom = if (scrubbing) 220.dp else 28.dp) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OMark(Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Label(TRIP_TITLE, cs.onSurface)
                Spacer(Modifier.weight(1f))
                PulseDot(if (preview != null) Color(0xFFE0A030) else cs.primary)
                Spacer(Modifier.width(8.dp))
                Label(if (preview != null) "Preview" else "Live", cs.onSurface)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Label("${away.code} ${hm(now, away.zone)} · ${home.code} ${hm(now, home.zone)}")
                AnimatedContent(leg.name, transitionSpec = {
                    (fadeIn(tween(260)) + slideInVertically { it / 3 }) togetherWith (fadeOut(tween(160)) + slideOutVertically { -it / 3 })
                }, label = "leg") { name -> Text(name.uppercase(), style = MaterialTheme.typography.headlineLarge) }
                RollingText(big, MaterialTheme.typography.displayLarge, cs.primary)
                DotBar(status.progress)
            }
        }
        item {
            Panel(container = cs.primaryContainer) {
                Label("Do now", cs.onPrimaryContainer)
                Text(status.doTitle, style = MaterialTheme.typography.titleLarge, color = cs.onPrimaryContainer)
                if (status.doDetail.isNotBlank()) Text(status.doDetail, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
            }
        }
        openCheckIn(ctx, now)?.let { ci -> item(key = "checkin") { CheckInCard(ci, onDone = { checkedTick++ }) } }
        if (stop != null) item(key = "stop") { StopCard(stop, now, stopSeg) { haptics.tap(); mapOpen = true } }
        if (watch != null) item(key = "live") { LiveCard(flights, watch, onMap = if (stop != null) ({ haptics.tap(); mapOpen = true }) else null) }
        status.next?.let { nx ->
            item(key = "next") {
                Panel {
                    Label("Next")
                    Text(nx.title, style = MaterialTheme.typography.titleMedium)
                    Muted("${nx.time} local · in ${dur(Duration.between(now, nx.at))}")
                }
            }
        }
        item(key = "clocks") {
            Panel {
                Row {
                    listOf(away.name to away.zone, home.name to home.zone).forEach { (name, zone) ->
                        Column(Modifier.weight(1f)) {
                            Label(name)
                            Text(hm(now, zone), style = MaterialTheme.typography.displaySmall)
                            Muted(now.atZone(zone).dayOfWeek.toString().lowercase().replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
        }
        item(key = "preview") {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Preview another moment", style = MaterialTheme.typography.titleSmall)
                        Muted("Scrub through the trip to see what this screen and your lock screen will show.")
                    }
                    Switch(checked = scrubbing, onCheckedChange = { haptics.toggle(it); scrubbing = it; if (!it) setPreview(null) })
                }
            }
        }
    }
    // Docked outside the list so it never moves under your finger while cards above come and go.
    AnimatedVisibility(
        scrubbing, modifier = Modifier.align(Alignment.BottomCenter),
        enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(160)),
    ) { Scrubber(status, now, preview, setPreview) { haptics.toggle(false); scrubbing = false; setPreview(null) } }
    }
}

@Composable
private fun Scrubber(status: Status, now: Instant, preview: Instant?, setPreview: (Instant?) -> Unit, close: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val chips = rememberLazyListState()
    LaunchedEffect(status.index) {
        if (preview != null) haptics.tick()
        chips.animateScrollToItem((status.index - 1).coerceAtLeast(0))
    }
    Surface(
        color = cs.surfaceContainerHighest, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(if (preview != null) "Preview" else "Live", if (preview != null) cs.primary else cs.onSurfaceVariant)
                    Text("${dhm(now, away.zone)} ${away.name} · ${hm(now, home.zone)} ${home.name}", style = MaterialTheme.typography.titleSmall)
                }
                if (preview != null) TextButton(onClick = { haptics.confirm(); setPreview(null) }) { Text("Live") }
                IconButton(onClick = close) { Icon(Ic.Remove, "Close preview") }
            }
            Slider(
                value = timeToSlider(now), onValueChange = { setPreview(sliderToTime(it)) },
                // Excluded from the edge back-gesture, or a drag starting near the screen edge exits the app.
                valueRange = 0f..legs.size.toFloat(), modifier = Modifier.padding(start = 12.dp, end = 20.dp).systemGestureExclusion(),
            )
            LazyRow(
                state = chips, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 8.dp),
                modifier = Modifier.systemGestureExclusion(),
            ) {
                itemsIndexed(legs) { k, l ->
                    FilterChip(
                        selected = status.index == k,
                        onClick = { haptics.tap(); setPreview(sliderToTime(k + 0.35f)) },
                        label = { Text(l.short) },
                    )
                }
            }
        }
    }
}

/** Gate (departing) or baggage belt (arriving) for a flight segment, from live data when available. */
@Composable
fun LiveCard(flights: FlightRepo, w: Watch, onMap: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val seg = flights.seg(w.flight, w.from)
    Panel {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(if (w.arriving) "Arrival · ${w.flight}" else "Departure · ${w.flight}")
                if (seg == null) {
                    Text("No live data yet", style = MaterialTheme.typography.titleMedium)
                    Muted("Live status starts on the day of the flight. Gates usually appear about an hour before departure.")
                } else {
                    val delay = if (w.arriving) seg.arrDelay else seg.depDelay
                    val delayText = when {
                        delay == null -> ""
                        delay >= 5 -> " · ${delay}m late"
                        delay <= -5 -> " · ${-delay}m early"
                        else -> " · on time"
                    }
                    Text("${seg.from} → ${seg.to} · ${seg.statusLabel}$delayText", style = MaterialTheme.typography.titleMedium,
                        color = if (seg.troubled) cs.primary else cs.onSurface)
                    val parts = if (w.arriving) listOfNotNull(
                        (seg.arrRevised ?: seg.arrSched)?.let { "Lands $it" }, seg.arrTerminal?.let { "Terminal $it" },
                    ) else listOfNotNull(
                        (seg.depRevised ?: seg.depSched)?.let { "Leaves $it" }, seg.depTerminal?.let { "Terminal $it" },
                    )
                    if (parts.isNotEmpty()) Muted(parts.joinToString(" · "))
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Label(if (w.arriving) "Belt" else "Gate")
                Text((if (w.arriving) seg?.belt else seg?.gate) ?: "—", style = MaterialTheme.typography.displayMedium)
            }
        }
        val gate = seg?.gate
        if (!w.arriving && gate != null) {
            val ctx = LocalContext.current
            val haptics = rememberHaptics()
            Muted(gateHint(w.from, gate))
            if (onMap != null) OutlinedButton(onClick = onMap) {
                Icon(Ic.Map, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Show gate $gate on the terminal map")
            } else OutlinedButton(onClick = { haptics.tap(); ctx.maps(gateQuery(w.from, gate)) }) {
                Icon(Ic.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Find gate $gate in Maps")
            }
        }
        RefreshButton(flights, w)
    }
}

/** The one way to spend a lookup by hand: full width, with when it last ran and what's left. */
@Composable
fun RefreshButton(flights: FlightRepo, w: Watch) {
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    Button(
        onClick = { haptics.tap(); flights.refreshNow(w) { ok -> if (ok) haptics.confirm() else haptics.reject() } },
        enabled = !flights.loading, modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (flights.loading) CircularProgressIndicator(Modifier.size(18.dp), color = cs.onPrimary, strokeWidth = 2.dp)
        else Icon(Ic.Refresh, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (flights.loading) "Checking ${w.flight}…" else "Refresh ${w.flight} status", style = MaterialTheme.typography.titleSmall)
    }
    val left = "${flights.left()} of ${FlightRepo.LIMIT} lookups left this month"
    Label(listOfNotNull(flights.updatedAt?.let { "Updated ${dhm(it, java.time.ZoneId.systemDefault())}" }, left).joinToString(" · "))
    flights.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary) }
}

@Composable
fun Mono(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, weight: FontWeight = FontWeight.SemiBold) =
    Text(text, style = MaterialTheme.typography.titleSmall.copy(fontFamily = PlexMono, fontWeight = if (weight >= FontWeight.SemiBold) FontWeight.Medium else FontWeight.Normal), color = color, modifier = modifier)


@Composable
private fun CheckInCard(c: CheckIn, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val copy = rememberCopy()
    Card(
        colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHighest),
        modifier = Modifier.fillMaxWidth().drawBehind { drawRect(cs.primary, size = Size(size.width, 4.dp.toPx())) },
    ) {
        Column(Modifier.padding(16.dp).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot()
                Spacer(Modifier.width(8.dp))
                Label("Check-in open · ${c.flight}", cs.primary)
            }
            Column {
                Text("CHECK IN NOW", style = MaterialTheme.typography.headlineMedium)
                Muted("Leaves ${c.when_}. Last name ${airline.surname}.")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOfNotNull("Booking" to airline.bookingRef, loyalty?.let { "${it.short} ${it.memberShort}" to it.number }).forEach { (label, value) ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(cs.background)
                            .clickable { copy(label, value) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(label, modifier = Modifier.weight(1f))
                            Icon(Ic.Copy, "Copy $label", Modifier.size(14.dp), tint = cs.onSurfaceVariant)
                        }
                        Mono(value)
                    }
                }
            }
            Button(
                onClick = { haptics.tap(); ctx.startActivity(TripNotifier.checkInIntent(ctx, preferApp = true)) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Open ${airline.name} app", style = MaterialTheme.typography.titleMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { haptics.tap(); ctx.startActivity(TripNotifier.checkInIntent(ctx, preferApp = false)) },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Text("Web check-in") }
                OutlinedButton(
                    onClick = { haptics.confirm(); TripNotifier.markCheckedIn(ctx, c.todo); onDone() },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { Icon(Ic.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Checked in") }
            }
        }
    }
}
