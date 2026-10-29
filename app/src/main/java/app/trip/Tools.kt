@file:OptIn(ExperimentalLayoutApi::class)

package app.trip

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.rotate
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private fun money(cur: String, v: Double) = money.symbol(cur) + "%,.2f".format(v)

@Composable
fun ToolsScreen(now: Instant, rates: RatesRepo) {
    Page {
        item { Heading("Tools") }
        item { Converter(rates) }
        item { TimeTool(now) }
        taxRefund?.let { t -> item { TaxTool(t, rates) } }
    }
}

@Composable
private fun Converter(rates: RatesRepo) {
    val cs = MaterialTheme.colorScheme
    var amount by rememberSaveable { mutableStateOf("20") }
    var other by rememberSaveable { mutableStateOf(money.home.code) }
    var fromAud by rememberSaveable { mutableStateOf(true) }
    val rate = rates.rates[other] ?: 0.0
    val value = amount.replace(",", "").toDoubleOrNull() ?: 0.0
    val (inCur, outCur) = if (fromAud) money.code to other else other to money.code
    val out = if (fromAud) value * rate else if (rate > 0) value / rate else 0.0

    Panel(spacing = 10.dp) {
        Label("Currency")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                prefix = { Text(money.symbol(inCur)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            val haptics = rememberHaptics()
            val spin by animateFloatAsState(if (fromAud) 0f else 180f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "swap")
            IconButton(onClick = { haptics.tap(); fromAud = !fromAud }) { Icon(Ic.Swap, "Swap direction", Modifier.rotate(spin)) }
        }
        RollingText(money(outCur, out), MaterialTheme.typography.displayMedium, cs.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val haptics = rememberHaptics()
            RatesRepo.CURRENCIES.forEach { c -> FilterChip(selected = other == c, onClick = { haptics.tap(); other = c }, label = { Text(c) }) }
        }
        HorizontalDivider(color = cs.outlineVariant)
        listOf(5, 10, 20, 50, 100).chunked(3).forEach { row ->
            Row {
                row.forEach { a ->
                    Column(Modifier.weight(1f)) {
                        Mono(money(money.code, a.toDouble()).removeSuffix(".00"))
                        Muted(money(other, a * rate))
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Label(
            "1 ${money.code} = ${"%.4f".format(rate)} $other" + (rates.date?.let { " · $it" } ?: "") + (rates.error?.let { " · $it" } ?: ""),
        )
        Text("Rates by Exchange Rate API", style = MaterialTheme.typography.labelSmall, color = cs.outline)
    }
}

@Composable
private fun TimeTool(now: Instant) {
    val cs = MaterialTheme.colorScheme
    val nowSyd = now.atZone(away.zone).toLocalTime()
    var minutes by rememberSaveable { mutableFloatStateOf((nowSyd.hour * 60 + nowSyd.minute).toFloat()) }
    val syd = LocalTime.of(0, 0).plusMinutes((minutes.roundToInt() / 15 * 15).toLong())
    val ahead = (away.zone.rules.getOffset(now).totalSeconds - home.zone.rules.getOffset(now).totalSeconds) / 60
    val lonMin = syd.hour * 60 + syd.minute - ahead
    val lon = LocalTime.of(0, 0).plusMinutes(((lonMin % 1440 + 1440) % 1440).toLong())
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val awake = lon.hour in 8..21

    Panel(spacing = 10.dp) {
        Label("${away.name} ↔ ${home.name}")
        Row {
            Column(Modifier.weight(1f)) {
                Label(away.name)
                RollingText(fmt.format(syd), MaterialTheme.typography.displayMedium, cs.onSurface)
            }
            Column(Modifier.weight(1f)) {
                Label(home.name + if (lonMin < 0) " · day before" else if (lonMin >= 1440) " · day after" else "")
                RollingText(fmt.format(lon), MaterialTheme.typography.displayMedium, cs.primary)
            }
        }
        val haptics = rememberHaptics()
        Slider(value = minutes, onValueChange = { if (it.roundToInt() / 60 != minutes.roundToInt() / 60) haptics.tick(); minutes = it }, valueRange = 0f..1425f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (awake) "Good time to call home" else "${home.name} is asleep", style = MaterialTheme.typography.titleSmall,
                color = if (awake) cs.onSurface else cs.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = { minutes = (nowSyd.hour * 60 + nowSyd.minute).toFloat() }) { Text("Now") }
        }
    }
}

@Composable
private fun TaxTool(t: TaxRefund, rates: RatesRepo) {
    val cs = MaterialTheme.colorScheme
    var price by rememberSaveable { mutableStateOf("") }
    val v = price.replace(",", "").toDoubleOrNull() ?: 0.0
    val gst = v * t.fraction
    Panel(spacing = 10.dp) {
        Label(t.name)
        OutlinedTextField(
            value = price, onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
            label = { Text("Price including ${t.taxName}") }, prefix = { Text(money.symbol) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Column(Modifier.weight(1f)) {
                Label("Refund")
                Text(money(money.code, gst), style = MaterialTheme.typography.displaySmall, color = if (v >= t.minimum) cs.primary else cs.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Label("In ${money.homeWord}")
                Text(money(money.home.code, gst * (rates.rates[money.home.code] ?: 0.0)), style = MaterialTheme.typography.displaySmall, color = cs.onSurfaceVariant)
            }
        }
        Muted(
            if (v > 0 && v < t.minimum) t.belowNote else t.claimNote,
        )
    }
}

// ---- Info ----

@Composable
fun InfoScreen(flights: FlightRepo) {
    val ctx = LocalContext.current
    val copy = rememberCopy()
    val haptics = rememberHaptics()
    val cs = MaterialTheme.colorScheme
    val prefs = remember { ctx.getSharedPreferences("todo", Context.MODE_PRIVATE) }
    val done = remember { mutableStateMapOf<String, Boolean>().apply { todos.forEach { put(it.id, prefs.getBoolean(it.id, false)) } } }

    Page {
        item { Heading("Checklist") }
        item {
            Panel(spacing = 2.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Ic.Check, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ENTRY_NOTE, style = MaterialTheme.typography.bodyMedium)
                }
                todos.forEach { t ->
                    val checked = done[t.id] == true
                    val toggle = { v: Boolean -> haptics.toggle(v); done[t.id] = v; setDone(ctx, t.id, v); TripNotifier.refresh(ctx) }
                    Row(Modifier.fillMaxWidth().clickable { toggle(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = toggle)
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge,
                                textDecoration = if (checked) TextDecoration.LineThrough else null,
                                color = if (checked) cs.onSurfaceVariant else cs.onSurface)
                            Muted(t.detail)
                        }
                        if (t.id.startsWith("checkin") && !checked) {
                            TextButton(onClick = { haptics.tap(); ctx.startActivity(TripNotifier.checkInIntent(ctx, preferApp = true)) }) { Text("Check in") }
                        }
                    }
                }
            }
        }
        loyalty?.let { l ->
            item { SubHeading(l.program) }
            item { LoyaltyCard(l) }
        }
        item { SubHeading("References") }
        item {
            Panel(padding = 0.dp, spacing = 0.dp) {
                references.forEach { (label, value) ->
                    ListItem(
                        overlineContent = { Text(label) },
                        headlineContent = { Mono(value) },
                        trailingContent = { IconButton(onClick = { copy(label, value) }) { Icon(Ic.Copy, "Copy $label") } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        esim?.let { e ->
            item { SubHeading("eSIM") }
            item {
                Panel(spacing = 10.dp) {
                    Column {
                        Text(e.title, style = MaterialTheme.typography.titleSmall)
                        Muted(e.note)
                    }
                    e.refs.forEach { (label, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Label(label); Mono(value) }
                            IconButton(onClick = { copy(label, value) }) { Icon(Ic.Copy, "Copy $label") }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { haptics.tap(); ctx.openSimSettings() }) { Text("SIM settings") }
                        e.app?.let { app -> OutlinedButton(onClick = { haptics.tap(); ctx.openApp(app) }) { Text("${e.appName} app") } }
                    }
                    Muted(e.support)
                }
            }
        }
        item { SubHeading("Contacts") }
        item {
            Panel(padding = 0.dp, spacing = 0.dp) {
                contacts.forEach { c ->
                    val (label, shown, number) = Triple(c.name, c.shown, c.dial)
                    ListItem(
                        headlineContent = { Text(label) },
                        supportingContent = { Mono(shown, color = cs.onSurfaceVariant, weight = androidx.compose.ui.text.font.FontWeight.Normal) },
                        trailingContent = { IconButton(onClick = { ctx.dial(number) }) { Icon(Ic.Call, "Call $label", tint = cs.primary) } },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
        item { Muted(CONTACTS_NOTE) }
        item { SubHeading("Notifications") }
        item {
            var on by remember { mutableStateOf(TripNotifier.enabled(ctx)) }
            val am = remember { ctx.getSystemService(android.app.AlarmManager::class.java) }
            Panel(spacing = 10.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock-screen status", style = MaterialTheme.typography.titleSmall)
                        Muted("Current leg and countdown to the next step, plus an alert at each airport step.")
                    }
                    Switch(checked = on, onCheckedChange = { haptics.toggle(it); on = it; TripNotifier.setEnabled(ctx, it) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { haptics.tap(); TripNotifier.testCheckIn(ctx) }) { Text("Test check-in alert") }
                    OutlinedButton(onClick = { haptics.tap(); TripNotifier.previewFlightDay(ctx) }) { Text("Preview flight-day card") }
                }
                if (!am.canScheduleExactAlarms()) {
                    Muted("Alerts may arrive a few minutes late until you allow alarms for this app.")
                    OutlinedButton(onClick = {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:" + ctx.packageName)))
                    }) { Text("Allow on-time alerts") }
                }
            }
        }
        item { SubHeading("Live flight data") }
        item {
            Panel(spacing = 10.dp) {
                Muted(
                    "Gates, delays and baggage belts come from Aviationstack's free plan: ${FlightRepo.LIMIT} lookups a month. " +
                        "The app checks by itself only around the airports, every ${FlightRepo.STALE_MIN} minutes while lookups last and then just before each take-off and after landing. The refresh button checks straight away.",
                )
                RefreshButton(flights, TripNotifier.nextWatch(java.time.Instant.now()))
            }
        }
    }
}

@Composable
private fun LoyaltyCard(l: Loyalty) {
    val ctx = LocalContext.current
    val copy = rememberCopy()
    val haptics = rememberHaptics()
    val prefs = remember { ctx.getSharedPreferences("club", Context.MODE_PRIVATE) }
    var avios by remember { mutableIntStateOf(prefs.getInt("avios", l.balance)) }
    var tp by remember { mutableIntStateOf(prefs.getInt("tp", l.tierBalance)) }
    var asOf by remember { mutableStateOf(prefs.getString("at", l.asOf)!!) }
    var editing by remember { mutableStateOf(false) }
    val credited = Instant.now().isAfter(RET.plus(java.time.Duration.ofDays(5)))
    val afterTp = tp + if (credited) 0 else l.tripTierPoints
    fun n(v: Int) = "%,d".format(v)
    fun k(v: Int) = if (v >= 10_000) "${(v + 500) / 1000}k" else "%.1fk".format(v / 1000f)
    fun tierAt(points: Int) = l.tiers.last { points >= it.second }.first
    // The bar runs from the tier this trip should reach to the one after it (or the top tier, once reached).
    val target = l.tiers.firstOrNull { it.second > afterTp } ?: l.tiers.last()
    val below = l.tiers.getOrNull(l.tiers.indexOf(target) - 1) ?: target

    Panel(spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(tierAt(tp).let { if (it == l.tiers.first().first) "$it member" else it })
                Mono(l.number)
            }
            IconButton(onClick = { copy("${l.program} number", l.number) }) { Icon(Ic.Copy, "Copy ${l.program} number") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(Triple(l.points, avios, avios + l.tripPoints), Triple(l.tierPoints, tp, afterTp)).forEach { (label, now, after) ->
                Column(Modifier.weight(1f)) {
                    Label(label)
                    RollingText(n(now), MaterialTheme.typography.displaySmall, MaterialTheme.colorScheme.onSurface)
                    if (!credited) Muted("≈ ${k(after)} after this trip")
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DotBar((afterTp / target.second.toFloat()).coerceAtMost(1f))
            Row {
                Label("${below.first} ${k(below.second)}", modifier = Modifier.weight(1f))
                Label("${target.first} ${k(target.second)}")
            }
            Muted(
                when {
                    afterTp >= target.second -> "${target.first} reached."
                    credited -> "${n(target.second - tp)} ${l.tierPoints.lowercase()} to ${target.first} by ${l.yearEnds}."
                    else -> "This trip should earn about ${n(l.tripPoints)} ${l.points} and ${n(l.tripTierPoints)} ${l.tierPoints.lowercase()}: ${tierAt(afterTp)}, and roughly ${n((target.second - afterTp + 50) / 100 * 100)} short of ${target.first}. Collection year ends ${l.yearEnds}."
                },
            )
        }
        Muted(listOfNotNull(l.household?.let { "Household ${l.points} ${n(prefs.getInt("household", it))}" }, "balance from $asOf.").joinToString(" · ").replaceFirstChar { it.uppercase() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            l.app?.let { app -> Button(onClick = { haptics.tap(); ctx.openApp(app) }) { Text("Open ${l.short} app") } }
            OutlinedButton(onClick = { haptics.tap(); editing = true }) { Text("Update balance") }
        }
    }

    if (editing) {
        var a by remember { mutableStateOf(avios.toString()) }
        var t by remember { mutableStateOf(tp.toString()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Update balance") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Triple(l.points, a) { v: String -> a = v }, Triple(l.tierPoints, t) { v: String -> t = v }).forEach { (label, v, set) ->
                        OutlinedTextField(v, { set(it.filter(Char::isDigit)) }, label = { Text(label) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    avios = a.toIntOrNull() ?: avios
                    tp = t.toIntOrNull() ?: tp
                    asOf = DateTimeFormatter.ofPattern("d MMM").format(java.time.LocalDate.now())
                    prefs.edit().putInt("avios", avios).putInt("tp", tp).putString("at", asOf).apply()
                    haptics.confirm(); editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}
