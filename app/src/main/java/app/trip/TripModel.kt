package app.trip

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// The shapes a trip is described in. The trip itself is trip/MyTrip.kt; nothing here is specific to one trip.
//
// Care: MyTrip.kt's top-level values are built when the app first touches one of them, and they call
// into this file. Anything here (or in other engine files) that reads trip values must be `by lazy`,
// or it can run mid-way through MyTrip.kt's set-up and see nulls.

fun Instant.plusMin(m: Long): Instant = plusSeconds(m * 60)

/** One end of the trip: [code] is the short label shown beside clocks ("LDN", "SYD"). */
class City(val name: String, val code: String, val zone: ZoneId)

/**
 * One flight leg as the live-data service sees it: the flight number, origin and destination
 * airport codes, scheduled take-off and landing, and the take-off date in the origin's local time
 * (Aviationstack reports several days of the same flight number; this picks the right one).
 */
class Segment(
    val flight: String, val from: String, val to: String, val toCity: String,
    val departs: Instant, val arrives: Instant, val departDate: String,
) {
    val key get() = "$flight:$from"
}

class Airline(
    val name: String, val app: String?, val checkInUrl: String,
    val bookingRef: String, val surname: String,
)

/**
 * A frequent-flyer scheme, shown as the loyalty card in Info. [tiers] are (name, tier points needed),
 * lowest first. [memberName] and [memberShort] name the membership in alerts and on the check-in card.
 */
class Loyalty(
    val program: String, val short: String, val memberName: String, val memberShort: String, val number: String, val app: String?,
    val points: String, val tierPoints: String, val tiers: List<Pair<String, Int>>, val yearEnds: String,
    val balance: Int, val tierBalance: Int, val household: Int?, val asOf: String,
    val tripPoints: Int, val tripTierPoints: Int,
)

/** [app] is the provider's Android package, opened by the "[appName] app" button. */
class Esim(val title: String, val note: String, val refs: List<Pair<String, String>>, val app: String?, val appName: String, val support: String)

/** [estimate] is units per one local currency unit, used until the first rates download. */
class Currency(val code: String, val symbol: String, val estimate: Double)

/**
 * The local currency and the others the converter offers. The first of [others] is the home
 * currency, called [homeWord] in prose ("In pounds"). Rates come from open.er-api.com.
 */
class Money(val code: String, val symbol: String, val homeWord: String, val others: List<Currency>) {
    val home get() = others.first()
    fun symbol(code: String) = if (code == this.code) symbol else others.first { it.code == code }.symbol
}

/** A sales-tax refund for visitors: the tax is [fraction] of the shelf price, claimable from [minimum] up. */
class TaxRefund(val name: String, val taxName: String, val fraction: Double, val minimum: Double, val belowNote: String, val claimNote: String)

class Hotel(val name: String, val details: String, val confirmation: String, val phone: String, val mapsQuery: String)

class Tip(val head: String, val body: String)

class Place(val name: String, val note: String, val query: String)

class Todo(val id: String, val title: String, val detail: String)

/** Online check-in opens 24 h before each departure; [remindAt] repeats the nudge the morning of the flight. */
class CheckIn(val todo: String, val flight: String, val opens: Instant, val remindAt: Instant, val departs: Instant, val when_: String)

class Contact(val name: String, val shown: String, val dial: String)

/** The Stay tab's month view: [weeks] rows from the Monday [start]; days before [tripStart] are greyed. */
class StayCalendar(val start: LocalDate, val weeks: Int, val tripStart: LocalDate, val tags: Map<LocalDate, String>)

// ---- the Flights tab ----

sealed interface JourneyPart
class JourneyNode(val code: String, val time: String, val where: String) : JourneyPart
class JourneyHop(val flight: String) : JourneyPart
class JourneyStop(val code: String, val length: String, val window: String, val note: String) : JourneyPart

class Journey(val title: String, val cabin: String, val flight: String, val parts: List<JourneyPart>, val total: String)

// ---- the Stay tab's sketch map: a [width]×[height] frame, x right and y down ----

class MapPoint(val x: Int, val y: Int)

/** A label beside a point; [left] puts it on the point's left, [dx]/[dy] nudge it. */
class MapPin(val name: String, val at: MapPoint, val left: Boolean = false, val dx: Int = 0, val dy: Int = 0)

/** A dashed route from [from] to [to], curved towards [bend] when given. */
class MapRoute(val from: MapPoint, val to: MapPoint, val bend: MapPoint? = null)

class MapText(val text: String, val at: MapPoint)

class CityMap(
    val width: Int, val height: Int,
    val water: List<IntArray>, val rivers: List<Pair<Int, IntArray>>,
    val routes: List<MapRoute>, val routeLabels: List<MapText>, val waterLabels: List<MapText>,
    val landmarks: List<MapPin>, val pins: List<MapPin>,
)

// ---- derived from the trip ----

/** Segments by "flight:origin". */
val segmentByKey: Map<String, Segment> by lazy { segments.associateBy { it.key } }

/** Each travel day's unbroken run of airport and flight legs, for the flight-day progress bar. */
val flightDays: List<List<Leg>> by lazy {
    legs.withIndex().filter { it.value.kind != Kind.PRE && it.value.kind != Kind.STAY && it.value.kind != Kind.POST }
        .fold(mutableListOf<MutableList<IndexedValue<Leg>>>()) { acc, l ->
            if (acc.isEmpty() || acc.last().last().index != l.index - 1) acc.add(mutableListOf(l)) else acc.last().add(l)
            acc
        }.map { day -> day.map { it.value } }
}
