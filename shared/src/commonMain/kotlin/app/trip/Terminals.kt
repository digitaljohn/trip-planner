package app.trip

// Airport terminals the app can map, by IATA code. A trip uses them from its stops list; adding an
// airport is the add-terminal-map skill. Outlines and gates are in TerminalData.kt (generated from
// OpenStreetMap by tools/terminals_from_osm.py); places are placed by lat/lon from official floor plans.

val terminals: Map<String, Terminal> by lazy { mapOf("LHR" to heathrowT5, "SIN" to changiT1, "SYD" to sydneyT1) }

private fun ll(lat: Double, lon: Double) = lat to lon
private fun w(lat: Double, lon: Double, train: Boolean = false) = Waypoint(lat, lon, train)

val changiT1 = Terminal(
    "Changi T1", ChangiT1.LAT0, ChangiT1.LON0, ChangiT1.outlines, ChangiT1.dim, ChangiT1.rails, ChangiT1.gates,
    levels = listOf("Level 2 · gates", "Level 3"),
    hub = ll(1.36231, 103.99057),
    spots = listOf(
        Spot(SpotKind.FOOD, "Paul, Pezzo, Tip Tap", "Transit hall, by the C pier", 1.36282, 103.98926),
        Spot(SpotKind.FOOD, "Starbucks", "Transit hall, by the D pier", 1.36226, 103.99163),
        Spot(SpotKind.SMOKE, "Smoking area", "C pier, near gate C16", 1.36468, 103.99017),
        Spot(SpotKind.SMOKE, "Smoking area", "D pier, near gate D36", 1.36427, 103.99339),
        Spot(SpotKind.DESK, "Transfer desk C", "By gate C1", 1.36269, 103.98891),
        Spot(SpotKind.DESK, "Transfer desk D", "By gate D41", 1.36243, 103.99202),
        Spot(SpotKind.TRAIN, "Skytrain to T3", "Airside, free, every few minutes", 1.36180, 103.98880),
        Spot(SpotKind.TRAIN, "Skytrain to T2", "Airside, free, every few minutes", 1.36090, 103.99140),
        Spot(SpotKind.GARDEN, "Cactus Garden", "Rooftop, with a smoking area · outdoors, hot", 1.36309, 103.98878, level = 1),
        Spot(SpotKind.FOOD, "Food Gallery", "Local food; smoking area nearby", 1.36212, 103.99050, level = 1),
        Spot(SpotKind.SHOWER, "Aerotel", "Showers and pool, paid", 1.36168, 103.99173, level = 1),
        Spot(SpotKind.LOUNGE, "BA lounge", "Needs Club status or a Club fare", 1.36245, 103.98931, level = 1),
    ),
    note = "You stay on level 2: gates, screening and the Skytrain are all on it. Level 3 is optional; allow 10 minutes to get back down.",
    searchName = "Singapore Changi Airport Terminal 1",
) { g -> if (g.startsWith("C")) listOf(w(1.36291, 103.98914)) else listOf(w(1.36166, 103.99212)) }

val heathrowT5 = Terminal(
    "Heathrow T5", HeathrowT5.LAT0, HeathrowT5.LON0, HeathrowT5.outlines, HeathrowT5.dim, HeathrowT5.rails, HeathrowT5.gates,
    levels = listOf("Departures"),
    hub = ll(51.47160, -0.48750),
    spots = listOf(
        Spot(SpotKind.FOOD, "Pret A Manger", "North end, by gate A7", 51.47347, -0.48680),
        Spot(SpotKind.FOOD, "Starbucks", "Main departure lounge", 51.47220, -0.48690),
        Spot(SpotKind.FOOD, "Itsu", "By gate A15", 51.47086, -0.48660),
        Spot(SpotKind.FOOD, "Pilots Bar & Kitchen", "South end, by gates A16–A17", 51.47033, -0.48690),
        Spot(SpotKind.TRAIN, "Transit to B and C gates", "Downstairs; trains every few minutes", 51.47110, -0.48570),
        Spot(SpotKind.TRAIN, "T5B station", "B gates", 51.47110, -0.48160),
        Spot(SpotKind.TRAIN, "T5C station", "C gates", 51.47110, -0.47580),
    ),
    note = "Security is on level 3; the lounge and A gates are on level 2. B and C gates are a short train ride. No smoking areas airside.",
    trainMin = 8,
    searchName = "Heathrow Terminal 5",
    hint = { g ->
        when (g.first().uppercaseChar()) {
            'A' -> "A gates are in the main building: about 5–10 min on foot."
            'B' -> "B gates are a train ride away: follow the transit signs and allow 15 min."
            'C' -> "C gates are one train stop past B: allow 15–20 min."
            else -> null
        }
    },
) { g ->
    when (g.first()) {
        'B' -> listOf(w(51.47110, -0.48570), w(51.47110, -0.48160, train = true))
        'C' -> listOf(w(51.47110, -0.48570), w(51.47110, -0.47580, train = true))
        else -> emptyList()
    }
}

private val sydDutyFree = ll(-33.93640, 151.16794)
private val sydJunctionB = ll(-33.93663, 151.16873)
private val sydRootC = ll(-33.93817, 151.16528)

val sydneyT1 = Terminal(
    "Sydney T1", SydneyT1.LAT0, SydneyT1.LON0, SydneyT1.outlines, SydneyT1.dim, SydneyT1.rails, SydneyT1.gates,
    levels = listOf("Departures"),
    hub = ll(-33.93584, 151.16688),
    spots = listOf(
        Spot(SpotKind.FOOD, "Food court and duty free", "Straight after security", sydDutyFree.first, sydDutyFree.second),
        Spot(SpotKind.FOOD, "McDonald's", "Pier B", -33.93683, 151.16913),
        Spot(SpotKind.FOOD, "McDonald's", "Pier C", -33.93768, 151.16562),
        Spot(SpotKind.SHOWER, "Showers", "Pier B, near gate 31", -33.93743, 151.16890),
        Spot(SpotKind.SHOWER, "Showers", "Pier C, near gate 51", -33.93839, 151.16559),
        Spot(SpotKind.DESK, "GST refund (TRS)", "After security, by gate 9", -33.93470, 151.16802),
        Spot(SpotKind.SMOKE, "Smoking area", "Outside only, before check-in", -33.93418, 151.16636),
        Spot(SpotKind.TRAIN, "Airport Link", "Train to the city, below arrivals", -33.93460, 151.16510),
    ),
    note = "One level after security. Smoking is only allowed outside, before check-in. There's no train between piers; Pier C is the long walk.",
    searchName = "Sydney Airport Terminal 1",
    corridors = listOf(
        listOf(ll(-33.93463, 151.16660), ll(-33.93743, 151.16487)),
        listOf(ll(-33.93584, 151.16688), sydDutyFree, sydJunctionB),
        listOf(sydDutyFree, ll(-33.93602, 151.16798), ll(-33.93501, 151.16763)),
        listOf(sydJunctionB, ll(-33.93639, 151.16991)),
        listOf(sydJunctionB, ll(-33.93692, 151.16865), ll(-33.93933, 151.16934)),
        listOf(ll(-33.93584, 151.16688), ll(-33.93795, 151.16514), sydRootC, ll(-33.94010, 151.16450)),
    ),
) { g ->
    when (g.filter(Char::isDigit).toIntOrNull() ?: 0) {
        in 8..10 -> listOf(w(sydDutyFree.first, sydDutyFree.second), w(-33.93602, 151.16798))
        in 24..26 -> listOf(w(sydDutyFree.first, sydDutyFree.second), w(sydJunctionB.first, sydJunctionB.second))
        in 30..37 -> listOf(w(sydDutyFree.first, sydDutyFree.second), w(sydJunctionB.first, sydJunctionB.second), w(-33.93692, 151.16865))
        else -> listOf(w(-33.93795, 151.16514), w(sydRootC.first, sydRootC.second))
    }
}

