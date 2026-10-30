package app.trip

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// =====================================================================================
//  The trip. Everything specific to one journey lives in this file; the rest of the app
//  reads it. This copy is the worked example; a real trip keeps its own copy in its trip
//  folder, with personal numbers in that folder's trip.properties via secret(). See SKILL.md.
// =====================================================================================

// ---- who and where ----

/** Shown on the launch screen and in the Now header. */
const val TRIP_TITLE = "Example · Sydney"

val home = City("London", "LDN", ZoneId.of("Europe/London"))
val away = City("Sydney", "SYD", ZoneId.of("Australia/Sydney"))

/** Now-screen title while staying away; the day count is added after it. */
const val AWAY_GREETING = "Enjoy Sydney"

/** The ticked line at the top of the Info checklist: entry permission already sorted. */
const val ENTRY_NOTE = "eVisitor granted · passport valid"

/** Said in the alert if a flight is cancelled. */
const val DISRUPTION_HELP = "Call your travel agent (24h) or BA. Numbers are in Info."

// ---- flights ----

/** Scheduled take-off of the outbound (BA15, Heathrow) and return (BA16, Sydney) journeys. Steps and legs are minute offsets from these. */
val OUT: Instant = Instant.parse("2026-10-30T21:00:00Z")
val RET: Instant = Instant.parse("2026-11-14T05:20:00Z")

/** Every flight leg, in order. Live data, gate alerts and check timings all key off these. */
val segments = listOf(
    Segment("BA15", "LHR", "SIN", "Singapore", OUT, OUT.plusMin(805), ZoneId.of("Europe/London")),
    Segment("BA15", "SIN", "SYD", "Sydney", OUT.plusMin(905), OUT.plusMin(1375), ZoneId.of("Asia/Singapore")),
    Segment("BA16", "SYD", "SIN", "Singapore", RET, RET.plusMin(485), ZoneId.of("Australia/Sydney")),
    Segment("BA16", "SIN", "LHR", "London", RET.plusMin(585), RET.plusMin(1445), ZoneId.of("Asia/Singapore")),
)

val airline = Airline(
    name = "BA", app = "com.ba.mobile",
    checkInUrl = "https://www.britishairways.com/travel/olcilandingpageauthreq/public/en_gb",
    bookingRef = secret("booking", "ABC123"), surname = secret("surname", "Traveller"),
)

/** Seat on the first flight, shown once you're checked in. */
const val SEAT_OUT = "42K"

val checkIns = listOf(
    CheckIn("checkin", "BA15", OUT.minusSeconds(86_400), Instant.parse("2026-10-30T08:00:00Z"), OUT, "Fri 21:00 from Heathrow T5"),
    CheckIn("checkin_home", "BA16", RET.minusSeconds(86_400), Instant.parse("2026-11-13T21:00:00Z"), RET, "Sat 16:20 from Sydney T1"),
)

/** The Flights tab's summary cards. */
val journeys = listOf(
    Journey(
        "Out · BA15", "Economy", "BA15",
        listOf(
            JourneyNode("LHR", "21:00 Fri 30 Oct", "Heathrow T5 · be there by 18:00"),
            JourneyHop("13h 25m"),
            JourneyStop("SIN", "1H40", "Singapore Changi T1 · 18:25–20:05 local", "Stay airside. No visa needed."),
            JourneyHop("7h 50m"),
            JourneyNode("SYD", "06:55 Sun 1 Nov", "Sydney T1 International"),
        ),
        "22h 55m in total · same plane, same flight number",
    ),
    Journey(
        "Home · BA16", "Premium Economy", "BA16",
        listOf(
            JourneyNode("SYD", "16:20 Sat 14 Nov", "Sydney T1 International · be there by 13:20"),
            JourneyHop("8h 05m"),
            JourneyStop("SIN", "1H40", "Singapore Changi T1 · 21:25–23:05 local", "Stay airside. No visa needed."),
            JourneyHop("14h 20m"),
            JourneyNode("LHR", "05:25 Sun 15 Nov", "Heathrow T5"),
        ),
        "24h 05m in total",
    ),
)

// ---- the timeline: airport steps and the legs between them ----

/** What to do at each airport. [Airport.leg] is the index of its GROUND leg in [legs]. */
val airports = listOf(
    Airport(1, "Heathrow T5 · Fri 30 Oct", "18:00–21:00", listOf(
        Step(OUT, -180, "18:00", "Arrive, T5 departures", "Checked in online? Go straight to BA Bag Drop. It closes at 20:00."),
        Step(OUT, -150, "18:30", "Security", "Allow 20–30 min on a Friday evening."),
        Step(OUT, -60, "~20:00", "Gate shows on screens", "B and C gates are a train ride away. Allow 15 min."),
        Step(OUT, -40, "~20:20", "Boarding", "By group number on your boarding pass. The gate closes at 20:40."),
        Step(OUT, 0, "21:00", "Take-off", "Set your watch to Sydney time."),
    )),
    Airport(3, "Singapore stop · Sat 31 Oct", "1h 40m", listOf(
        Step(OUT, 805, "18:25", "Land at Changi T1", "You'll usually be asked off the plane with your cabin bags."),
        Step(OUT, 815, "~18:35", "Follow \"Transfer\" signs", "No immigration. Your checked bag stays on the plane."),
        Step(OUT, 865, "~19:25", "Back to the gate", "Bags are screened at the gate, so the liquids rules apply again."),
        Step(OUT, 905, "20:05", "Take-off for Sydney", "Sleep on this leg."),
    )),
    Airport(5, "Sydney arrival · Sun 1 Nov", "06:55–08:30", listOf(
        Step(OUT, 1320, "06:00", "Fill in the passenger card", "Handed out on board. Tick \"yes\" for any food or medicines."),
        Step(OUT, 1375, "06:55", "Land"),
        Step(OUT, 1390, "~07:10", "SmartGate", "Scan your passport. The eVisitor is already linked to it."),
        Step(OUT, 1410, "~07:30", "Bags and biosecurity", "Hand over the card on the way out."),
        Step(OUT, 1430, "~07:50", "Train or taxi", "Follow \"Trains\" signs to the Airport Link, then ride to Town Hall (15 min). Taxis are outside."),
        Step(OUT, 1470, "~08:30", "Hotel", "Ten-minute walk from Town Hall. Drop your bags."),
    )),
    Airport(7, "Sydney departure · Sat 14 Nov", "12:30–16:20", listOf(
        Step(RET, -230, "12:30", "Leave the hotel", "Town Hall to the airport, 15 min, or a cab."),
        Step(RET, -180, "13:20", "Check in, T1 departures", "Premium Economy counter. Bag drop closes at 15:20."),
        Step(RET, -155, "13:45", "SmartGate and security", "The GST refund (TRS) counter is after this, if you need it."),
        Step(RET, -50, "~15:30", "Gate shows on screens", "Boarding from about 15:40. The gate closes at 16:00."),
        Step(RET, 0, "16:20", "Take-off", "Stay awake on this leg."),
    )),
    Airport(9, "Singapore stop · Sat 14 Nov", "1h 40m", listOf(
        Step(RET, 485, "21:25", "Land at Changi T1", "You'll usually be asked off the plane with your cabin bags."),
        Step(RET, 495, "~21:35", "Follow \"Transfer\" signs", "No immigration. Your checked bag stays on the plane."),
        Step(RET, 545, "~22:25", "Back to the gate", "Bags are screened at the gate, so the liquids rules apply again."),
        Step(RET, 585, "23:05", "Take-off for London", "Sleep as much as you can."),
    )),
    Airport(11, "Heathrow arrival · Sun 15 Nov", "05:25", listOf(
        Step(RET, 1445, "05:25", "Land at T5"),
        Step(RET, 1465, "~05:45", "ePassport gates"),
        Step(RET, 1495, "~06:15", "Bags, then home"),
    )),
)

/**
 * The whole trip as back-to-back legs: PRE, then for each journey GROUND (airport) / AIR / GROUND
 * (stop) / AIR / GROUND (arrival), a STAY between journeys, and POST at the end. Each leg starts
 * where the previous one ends. [Leg.watch] says which flight's live data a leg shows.
 */
val legs = listOf(
    Leg("Before", "Before you fly", null, OUT.plusMin(-180), Kind.PRE, watch = Watch("BA15", "LHR", false), tipTitle = "Get ready", tip = "Check in online from 21:00 Thu. Be at Heathrow T5 by 18:00 Fri."),
    Leg("Heathrow", "Heathrow T5", OUT.plusMin(-180), OUT, Kind.GROUND, OUT, 1, Watch("BA15", "LHR", false)),
    Leg("→ Singapore", "Flying to Singapore", OUT, OUT.plusMin(805), Kind.AIR, OUT, watch = Watch("BA15", "LHR", true), tipTitle = "Settle in", tip = "Have dinner, stay up a few hours, then rest. Drink water."),
    Leg("Singapore", "Singapore stop", OUT.plusMin(805), OUT.plusMin(905), Kind.GROUND, OUT, 3, Watch("BA15", "SIN", false)),
    Leg("→ Sydney", "Flying to Sydney", OUT.plusMin(905), OUT.plusMin(1375), Kind.AIR, OUT, watch = Watch("BA15", "SIN", true), tipTitle = "Sleep", tip = "It is night in Sydney. Wake for breakfast and fill in the passenger card."),
    Leg("Arrive SYD", "Arriving in Sydney", OUT.plusMin(1375), OUT.plusMin(1470), Kind.GROUND, OUT, 5, Watch("BA15", "SIN", true)),
    Leg("In Sydney", "In Sydney", OUT.plusMin(1470), RET.plusMin(-230), Kind.STAY),
    Leg("Leave SYD", "Leaving Sydney", RET.plusMin(-230), RET, Kind.GROUND, RET, 7, Watch("BA16", "SYD", false)),
    Leg("→ Singapore", "Flying to Singapore", RET, RET.plusMin(485), Kind.AIR, RET, watch = Watch("BA16", "SYD", true), tipTitle = "Stay awake", tip = "Keep the blind up so you sleep on the long leg."),
    Leg("Singapore", "Singapore stop", RET.plusMin(485), RET.plusMin(585), Kind.GROUND, RET, 9, Watch("BA16", "SIN", false)),
    Leg("→ London", "Flying to London", RET.plusMin(585), RET.plusMin(1445), Kind.AIR, RET, watch = Watch("BA16", "SIN", true), tipTitle = "Sleep", tip = "You land at 05:25 Sunday, London time."),
    Leg("Heathrow", "Arriving at Heathrow", RET.plusMin(1445), RET.plusMin(1495), Kind.GROUND, RET, 11, Watch("BA16", "SIN", true)),
    Leg("Home", "Home", RET.plusMin(1495), null, Kind.POST, tipTitle = "Trip complete", tip = "Get some daylight to reset to London time."),
)

/** Said on the Now screen once you've ticked off the first check-in, before leaving home. */
const val CHECKED_IN_NOTE = "Seat $SEAT_OUT. Be at Heathrow T5 by 18:00 Fri."

/**
 * The free-time card and terminal map at each departure airport. Terminals come from Terminals.kt;
 * minutes are relative to take-off. See [Stop] for what each number means.
 */
val stops = listOf(
    Stop(1, heathrowT5, OUT, Watch("BA15", "LHR", false), home.zone, listOf(Phase("Bag drop", -180, -150), Phase("Security", -150, -120)), -120, -40, "Walk", 15),
    Stop(3, changiT1, OUT.plusMin(905), Watch("BA15", "SIN", false), ZoneId.of("Asia/Singapore"), listOf(Phase("Off", -100, -90)), -90, -25, "Gate", 5, queue = 15),
    Stop(7, sydneyT1, RET, Watch("BA16", "SYD", false), away.zone, listOf(Phase("Check-in", -180, -155), Phase("Security", -155, -120)), -120, -40, "Walk", 10),
    Stop(9, changiT1, RET.plusMin(585), Watch("BA16", "SIN", false), ZoneId.of("Asia/Singapore"), listOf(Phase("Off", -100, -90)), -90, -25, "Gate", 5, queue = 15),
)

// ---- the stay ----

// An example trip: the flights are real scheduled services; the traveller, plans and phone numbers are made up.
val hotel = Hotel(
    "Hyatt Regency Sydney", "161 Sussex Street · King room · Sun 1 – Sat 14 Nov",
    confirmation = secret("hotel.confirmation", "00000000"), phone = "+61200000000", mapsQuery = "Hyatt Regency Sydney 161 Sussex Street",
)

val stayCalendar = StayCalendar(
    start = LocalDate.of(2026, 10, 26), weeks = 3, tripStart = LocalDate.of(2026, 10, 30),
    tags = mapOf(
        LocalDate.of(2026, 10, 30) to "Fly", LocalDate.of(2026, 10, 31) to "Air", LocalDate.of(2026, 11, 1) to "Land",
        LocalDate.of(2026, 11, 14) to "Fly", LocalDate.of(2026, 11, 15) to "Home",
    ),
)

val stayTips = listOf(
    Tip("Sun 1 Nov", "Stay outside in daylight and go to bed after 9 pm. Walk Darling Harbour to Circular Quay."),
    Tip("Tue 3 Nov", "Melbourne Cup. The city stops to watch around 15:00."),
    Tip("Sat 7 – Sun 8", "Bondi to Coogee coastal walk, the Manly ferry, or a Blue Mountains day trip."),
    Tip("Calls home", "Before 9 am Sydney time (10 pm London) or after 6 pm (7 am London)."),
)

val places = listOf(
    Place("Hyatt Regency, 161 Sussex St", "10 min walk to Town Hall or Wynyard station.", "Hyatt Regency Sydney 161 Sussex Street"),
    Place("Airport, T1 International", "15 min by train to Town Hall. 20–35 min by taxi.", "Sydney Airport Terminal 1 International"),
    Place("ICC Sydney, Darling Harbour", "The conference. 10 min walk from the hotel.", "ICC Sydney Darling Harbour"),
    Place("Sydney Olympic Park", "Client visit. 35 min by train from Central, or 30–40 min by Uber.", "Sydney Olympic Park NSW"),
)

/**
 * Hand-drawn Sydney: a 1000×790 frame projected from lat/lon (lon 151.02–151.31, lat −33.79 to
 * −33.98). Set to null to drop the map and keep just the place list.
 */
val cityMap: CityMap? = CityMap(
    1000, 790,
    water = listOf(
        intArrayOf(940, 0, 1000, 0, 1000, 790, 780, 790, 817, 665, 824, 545, 855, 474, 886, 420, 905, 300, 914, 183, 945, 168, 955, 125, 930, 60, 924, 29),
        intArrayOf(430, 790, 466, 707, 552, 690, 600, 705, 672, 728, 707, 749, 745, 790),
    ),
    rivers = listOf(
        26 to intArrayOf(931, 175, 862, 208, 776, 262, 707, 270, 659, 262, 603, 241, 524, 237, 441, 216),
        10 to intArrayOf(441, 216, 362, 187, 293, 158, 224, 175, 155, 166, 103, 170, 0, 125),
        12 to intArrayOf(834, 158, 776, 104, 741, 42, 707, 0),
    ),
    routes = listOf(
        MapRoute(MapPoint(629, 335), MapPoint(503, 609), bend = MapPoint(520, 470)),
        MapRoute(MapPoint(629, 335), MapPoint(171, 239), bend = MapPoint(400, 320)),
        MapRoute(MapPoint(629, 335), MapPoint(619, 353)),
    ),
    routeLabels = listOf(MapText("10 km", MapPoint(430, 470)), MapText("15 km", MapPoint(380, 262))),
    waterLabels = listOf(MapText("Parramatta River", MapPoint(200, 125)), MapText("Harbour", MapPoint(735, 215)), MapText("Botany Bay", MapPoint(560, 762))),
    landmarks = listOf(
        MapPin("Opera House", MapPoint(673, 278)), MapPin("Bondi", MapPoint(877, 419)),
        MapPin("Coogee", MapPoint(820, 543), left = true), MapPin("Manly", MapPoint(923, 29), left = true),
    ),
    pins = listOf(
        MapPin("Hotel", MapPoint(629, 335), dy = -18), MapPin("ICC Sydney", MapPoint(619, 353), dy = 18),
        MapPin("Airport", MapPoint(503, 609), left = true), MapPin("Olympic Park", MapPoint(171, 239), dx = -40, dy = -30),
    ),
)

// ---- tools ----

val money = Money(
    "AUD", "A$", homeWord = "pounds",
    others = listOf(Currency("GBP", "£", 0.49), Currency("USD", "US$", 0.65), Currency("EUR", "€", 0.57), Currency("SGD", "S$", 0.85)),
)

/** Australia's Tourist Refund Scheme. Set to null where there's no visitor tax refund. */
val taxRefund: TaxRefund? = TaxRefund(
    "GST refund (TRS)", "GST", fraction = 1.0 / 11, minimum = 300.0,
    belowNote = "Below A$300. The refund needs A$300+ from one shop, on receipts, bought within 60 days.",
    claimNote = "Claim at the TRS counter after security at Sydney T1, with the goods and tax invoices. Allow 30 min.",
)

// ---- info ----

val todos = listOf(
    Todo("checkin", "Check in for BA15", "Opens 21:00 Thu 29 Oct, London time."),
    Todo("checkin_home", "Check in for BA16", "Opens 16:20 Fri 13 Nov, Sydney time."),
    Todo("early", "Ask the hotel for early check-in", "You'll arrive around 08:30. Only a booked Sat 31 Oct night guarantees a room."),
    Todo("plug", "Pack an Australian plug adaptor", "Type I, 230 V."),
    Todo("pay", "Contactless card or phone wallet", "Tap it on all Sydney transport."),
)

/** Copyable numbers in Info. Anything personal goes through secret(). */
val references = listOf(
    "BA booking" to airline.bookingRef,
    "BA15 seat" to SEAT_OUT,
    "BA Executive Club" to secret("loyalty.number", "00000000"),
    "E-ticket" to secret("eticket", "1250000000000"),
    "eVisitor grant" to secret("evisa", "0000000000000"),
    "Hotel confirmation" to hotel.confirmation,
    "Hotel loyalty" to secret("hotel.loyalty", "000000000000"),
    "Agency reference" to secret("agency", "XYZ123"),
)

/** Under the contacts in Info. */
const val CONTACTS_NOTE = "Flight changes go through the travel agent who issued the ticket."

val contacts = listOf(
    Contact("Emergency", "000", "000"),
    Contact("Hyatt Regency Sydney", "+61 2 0000 0000", "+61200000000"),
    Contact("Travel agent 24h", "+44 20 0000 0000", "+442000000000"),
)

/** The frequent-flyer card in Info. Set to null to hide it. Balances can also be updated in the app. */
val loyalty: Loyalty? = Loyalty(
    program = "BA Club", short = "BA", memberName = "Executive Club", memberShort = "Exec Club",
    number = secret("loyalty.number", "00000000"), app = airline.app,
    points = "Avios", tierPoints = "Tier points",
    tiers = listOf("Blue" to 0, "Bronze" to 3_500, "Silver" to 7_500), yearEnds = "31 Mar 2027",
    balance = secretInt("loyalty.points", 12_500), tierBalance = secretInt("loyalty.tierPoints", 1_200),
    household = secretInt("loyalty.household", 0).takeIf { it > 0 }, asOf = "29 Oct",
    tripPoints = 29_000, tripTierPoints = 4_800,
)

/** The eSIM card in Info. Set to null to hide it. */
val esim: Esim? = Esim(
    "Holafly Australia · unlimited data",
    "Installed. Set mobile data to the Holafly eSIM with roaming on; keep the UK SIM for calls and texts with its roaming off.",
    refs = listOf("Holafly order" to secret("esim.order", "HLY00000000"), "ICCID" to secret("esim.iccid", "8900000000000000000")),
    app = "com.holafly.holafly", appName = "Holafly", support = "Support: help@holafly.com, or chat in the app.",
)
