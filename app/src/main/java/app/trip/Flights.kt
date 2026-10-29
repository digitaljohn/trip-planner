package app.trip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun FlightsScreen(status: Status, flights: FlightRepo) {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    Page {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading("Flights")
                Spacer(Modifier.weight(1f))
            }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { RefreshButton(flights, TripNotifier.nextWatch(java.time.Instant.now())) } }
        items(journeys) { j -> JourneyCard(j, flights) }
        item { Muted("Times are ${airline.name}'s schedule via Aviationstack, updated live as the day approaches. Gates appear about an hour before boarding.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                journeys.forEachIndexed { i, j ->
                    OutlinedButton(onClick = { ctx.open("https://www.flightradar24.com/data/flights/${j.flight.lowercase()}") }) {
                        Text(if (i == 0) "${j.flight} on Flightradar" else j.flight); Spacer(Modifier.width(6.dp)); Icon(Ic.OpenInNew, null, Modifier.size(16.dp))
                    }
                }
            }
        }
        item { SubHeading("At the airport") }
        item { Muted("Tap an airport for each step. Small tags count from take-off.") }
        items(airports) { ap -> AirportCard(ap, status, flights) }
    }
}

@Composable
private fun JourneyCard(j: Journey, flights: FlightRepo) {
    val cs = MaterialTheme.colorScheme
    Panel(spacing = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(j.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Label(j.cabin, cs.primary)
        }
        j.parts.forEach { p ->
            when (p) {
                is JourneyNode -> Row(verticalAlignment = Alignment.Top) {
                    Text(p.code, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.width(68.dp))
                    Column {
                        Mono(p.time)
                        Muted(p.where)
                    }
                }
                is JourneyHop -> Row(Modifier.height(46.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(68.dp).fillMaxHeight(), contentAlignment = Alignment.Center) { DottedLine() }
                    Mono("${p.flight} flight", color = cs.onSurfaceVariant, weight = FontWeight.Normal)
                }
                is JourneyStop -> Row(
                    Modifier.fillMaxWidth().background(cs.primaryContainer, RoundedCornerShape(12.dp)).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(p.code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                        color = cs.onPrimaryContainer, modifier = Modifier.width(68.dp))
                    Column(Modifier.padding(end = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.length, style = MaterialTheme.typography.displaySmall, color = cs.primary)
                            Spacer(Modifier.width(8.dp))
                            Label("stop", cs.onPrimaryContainer)
                        }
                        Text(p.window, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer)
                        Text(p.note, style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer)
                    }
                }
            }
        }
        Text(j.total, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        segments.filter { it.flight == j.flight }.mapNotNull { flights.seg(it.flight, it.from) }.forEach { s ->
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = cs.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label("Live · ${s.from} → ${s.to}")
                    Text(s.statusLabel + (s.depDelay?.takeIf { it >= 5 }?.let { " · ${it}m late" } ?: ""),
                        style = MaterialTheme.typography.titleSmall, color = if (s.troubled) cs.primary else cs.onSurface)
                    Muted(listOfNotNull(
                        (s.depRevised ?: s.depSched)?.let { "Dep $it" }, s.depTerminal?.let { "T$it" },
                        (s.arrRevised ?: s.arrSched)?.let { "Arr $it" }, s.aircraft,
                    ).joinToString(" · "))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Label("Gate")
                    Text(s.gate ?: "—", style = MaterialTheme.typography.displaySmall)
                }
            }
        }
    }
}

@Composable
private fun DottedLine() {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.width(4.dp).fillMaxHeight()) {
        val step = 6.dp.toPx()
        var y = step / 2
        while (y < size.height) { drawCircle(c, 1.4.dp.toPx(), Offset(size.width / 2, y)); y += step }
    }
}

@Composable
private fun AirportCard(ap: Airport, status: Status, flights: FlightRepo) {
    val cs = MaterialTheme.colorScheme
    val isCurrent = status.leg.airport == ap.leg
    var open by remember(isCurrent) { mutableStateOf(isCurrent) }
    val haptics = rememberHaptics()
    val turn by animateFloatAsState(if (open) 45f else 0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "turn")
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHighest), modifier = Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
        Row(Modifier.fillMaxWidth().clickable { haptics.tap(); open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ap.title, style = MaterialTheme.typography.titleSmall)
                Muted(ap.span)
            }
            if (isCurrent) { Label("Now", cs.primary); Spacer(Modifier.width(8.dp)) }
            Icon(Ic.Add, contentDescription = if (open) "Collapse" else "Expand", modifier = Modifier.rotate(turn))
        }
        AnimatedVisibility(open, enter = fadeIn(tween(220)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)), exit = fadeOut(tween(120)) + shrinkVertically(tween(200))) {
            Column {
                val stop = stopFor(ap.leg)
                val gate = stop?.let { flights.seg(it.watch.flight, it.watch.from)?.gate }
                var mapOpen by remember { mutableStateOf(false) }
                if (mapOpen && stop != null) TerminalSheet(stop, gate) { mapOpen = false }
                if (stop != null) {
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).clickable { haptics.tap(); mapOpen = true }) {
                        TerminalMap(stop.terminal, 0, gate, null)
                    }
                }
                ap.steps.forEach { s ->
                    val here = isCurrent && status.current == s
                    HorizontalDivider(color = cs.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (here) cs.primaryContainer else Color.Transparent)
                            .drawBehind { if (here) drawRect(cs.primary, size = Size(3.dp.toPx(), size.height)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Column(Modifier.width(76.dp)) {
                            Mono(s.time)
                            Label(stepTag(s.m))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(s.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            if (s.detail.isNotEmpty()) Muted(s.detail)
                        }
                    }
                }
                if (stop != null) {
                    HorizontalDivider(color = cs.outlineVariant)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Label("Within reach")
                        val t = stop.terminal
                        t.spots.filter { it.level == 0 && it.kind != SpotKind.TRAIN }.sortedBy { (t.at(it.lat, it.lon) - t.hub).getDistance() }.take(4)
                            .forEach { SpotRow(t, it, t.hub) }
                        TextButton(onClick = { haptics.tap(); mapOpen = true }) { Text("Open the terminal map") }
                    }
                }
            }
        }
    }
}
