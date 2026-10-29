package app.trip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate

@Composable
fun StayScreen(now: Instant) {
    val ctx = LocalContext.current
    val copy = rememberCopy()
    val haptics = rememberHaptics()
    val cs = MaterialTheme.colorScheme
    Page {
        item { Heading("Your stay") }
        item {
            Panel {
                Text(hotel.name, style = MaterialTheme.typography.titleLarge)
                Muted(hotel.details)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Confirmation")
                    Spacer(Modifier.width(8.dp))
                    Mono(hotel.confirmation, Modifier.weight(1f))
                    IconButton(onClick = { copy("Hotel confirmation", hotel.confirmation) }) { Icon(Ic.Copy, "Copy confirmation") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { ctx.dial(hotel.phone) }) {
                        Icon(Ic.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Call")
                    }
                    OutlinedButton(onClick = { ctx.maps(hotel.mapsQuery) }) {
                        Icon(Ic.Directions, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Directions")
                    }
                }
            }
        }
        item { Calendar(stayCalendar, now.atZone(away.zone).toLocalDate()) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                stayTips.forEachIndexed { i, t -> TipRow(i + 1, t.head, t.body) }
            }
        }
        cityMap?.let { m ->
            item { SubHeading("Map") }
            item { SketchMap(m) }
        }
        items(places) { p ->
            Panel(padding = 0.dp) {
                ListItem(
                    headlineContent = { Text(p.name) },
                    supportingContent = { Text(p.note) },
                    trailingContent = { IconButton(onClick = { ctx.maps(p.query) }) { Icon(Ic.Directions, "Open in Maps", tint = cs.primary) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        item { Muted("Sketch map, positions approximate.") }
    }
}

@Composable
private fun TipRow(n: Int, head: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(thickness = 2.dp, color = cs.onSurface)
        Row(Modifier.padding(top = 10.dp)) {
            Text("%02d".format(n), style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.width(36.dp))
            Column {
                Text(head, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Calendar(c: StayCalendar, today: LocalDate) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row { "MTWTFSS".forEach { Label(it.toString(), modifier = Modifier.weight(1f).wrapContentWidth()) } }
        for (w in 0 until c.weeks) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (d in 0 until 7) {
                val date = c.start.plusDays((w * 7 + d).toLong())
                val before = date.isBefore(c.tripStart)
                val tag = if (before) null else c.tags[date]
                val weekend = !before && tag == null && d >= 5
                val bg = when { tag != null -> cs.primaryContainer; weekend -> cs.secondaryContainer; before -> Color.Transparent; else -> cs.surfaceContainerHighest }
                Column(
                    Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                        .border(if (date == today) 2.dp else 0.dp, if (date == today) cs.primary else Color.Transparent, RoundedCornerShape(10.dp))
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Mono(date.dayOfMonth.toString(), color = if (before) cs.outline else cs.onSurface)
                    if (tag != null) Text(tag, style = MaterialTheme.typography.labelSmall, color = cs.primary)
                }
            }
        }
    }
}

/** The trip's [CityMap], scaled to the canvas width: dotted water, rivers, dashed routes from the hotel, and pins. */
@Composable
private fun SketchMap(m: CityMap) {
    val cs = MaterialTheme.colorScheme
    val tm = rememberTextMeasurer()
    val water = cs.onSurfaceVariant.copy(alpha = 0.45f)
    val river = cs.onSurfaceVariant.copy(alpha = 0.28f)
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(m.width.toFloat() / m.height).clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHighest),
    ) {
        val s = size.width / m.width
        fun p(at: MapPoint) = Offset(at.x * s, at.y * s)
        fun poly(pts: IntArray, close: Boolean) = Path().apply {
            moveTo(pts[0] * s, pts[1] * s)
            for (k in 2 until pts.size step 2) lineTo(pts[k] * s, pts[k + 1] * s)
            if (close) close()
        }

        val step = 7.dp.toPx()
        m.water.forEach { area ->
            clipPath(poly(area, close = true)) {
                var y = step / 2
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) { drawCircle(water, 1.3.dp.toPx(), Offset(x, y)); x += step }
                    y += step
                }
            }
        }
        m.rivers.forEach { (w, pts) -> drawPath(poly(pts, close = false), river, style = Stroke(w * s, cap = StrokeCap.Round, join = StrokeJoin.Round)) }

        val dash = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
        m.routes.forEach { r ->
            drawPath(Path().apply {
                moveTo(r.from.x * s, r.from.y * s)
                r.bend?.let { b -> quadraticTo(b.x * s, b.y * s, r.to.x * s, r.to.y * s) } ?: lineTo(r.to.x * s, r.to.y * s)
            }, cs.primary, style = dash)
        }

        val small = TextStyle(color = cs.onSurfaceVariant, fontSize = 10.sp)
        val italic = small.copy(fontStyle = FontStyle.Italic)
        val route = TextStyle(color = cs.primary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        val key = TextStyle(color = cs.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Bold)

        m.waterLabels.forEach { label(tm, it.text, it.at.x, it.at.y, italic, s) }
        m.routeLabels.forEach { label(tm, it.text, it.at.x, it.at.y, route, s) }
        m.landmarks.forEach { pin ->
            drawCircle(cs.onSurfaceVariant, 3.dp.toPx(), p(pin.at))
            label(tm, pin.name, (if (pin.left) pin.at.x - 14 else pin.at.x + 14) + pin.dx, pin.at.y + pin.dy, small, s, endAnchor = pin.left)
        }
        m.pins.forEach { pin ->
            drawCircle(cs.primary.copy(alpha = 0.22f), 9.dp.toPx(), p(pin.at))
            drawCircle(cs.primary, 5.dp.toPx(), p(pin.at))
            label(tm, pin.name, (if (pin.left) pin.at.x - 20 else pin.at.x + 20) + pin.dx, pin.at.y + pin.dy, key, s, endAnchor = pin.left)
        }
    }
}

private fun DrawScope.label(tm: TextMeasurer, text: String, x: Int, y: Int, style: TextStyle, s: Float, endAnchor: Boolean = false) {
    val r = tm.measure(text, style)
    val left = if (endAnchor) x * s - r.size.width else x * s
    drawText(r, topLeft = Offset(left.coerceIn(4f, size.width - r.size.width - 4f), y * s - r.size.height / 2f))
}
