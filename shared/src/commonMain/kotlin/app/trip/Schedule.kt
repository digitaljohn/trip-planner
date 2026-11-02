package app.trip

/**
 * When things happen around the flights: which flight to watch, when to look it up, and which alerts
 * fresh flight data calls for. Each platform's notifier posts what this decides.
 */
object Schedule {
    /** Alerts and the status card stop a day after landing home. */
    val END: Instant by lazy { RET.plusMin(1555) }

    /** Moments that fire an alert: every airport step, each check-in opening, and each check-in reminder. */
    fun events(): List<Instant> = steps.map { it.at } + checkIns.flatMap { listOf(it.opens, it.remindAt) }

    /** The flight to keep checking while you're at (or an hour from) an airport. */
    fun airportWatch(now: Instant): Watch? = legs.firstOrNull { l ->
        l.airport != null && l.watch != null && l.from != null && l.to != null && !now.isBefore(l.from.plusMin(-60)) && now.isBefore(l.to)
    }?.watch

    /** The flight the refresh button looks up: the one at the airport now, else the next to leave. */
    fun nextWatch(now: Instant): Watch = airportWatch(now)
        ?: (segments.firstOrNull { now.isBefore(it.departs) } ?: segments.last()).let { Watch(it.flight, it.from, false) }

    private val DEP_AT by lazy { segments.associate { it.key to it.departs } }
    /** Landings where you collect bags: the last leg of each flight number. */
    private val ARR_AT by lazy { segments.filterIndexed { i, s -> segments.getOrNull(i + 1)?.flight != s.flight }.associate { it.key to it.arrives } }
    private val TO by lazy { segments.associate { it.key to it.toCity } }

    /** A leg that continues a flight number from an earlier stop, as opposed to the first leg out of an airport you arrived at by road. */
    private fun isConnection(key: String) = segments.indexOfFirst { it.key == key }.let { i -> i > 0 && segments[i - 1].flight == segments[i].flight }

    /** When to look up [w] while quota is low: before each take-off, and once after landing for the belt. */
    fun checkSlots(w: Watch): List<Instant> {
        val key = "${w.flight}:${w.from}"
        if (w.arriving) return listOfNotNull(ARR_AT[key]?.plusMin(15))
        val dep = DEP_AT[key] ?: return emptyList()
        return (if (isConnection(key)) listOf(-60L, -35L) else listOf(-90L, -60L, -35L)).map { dep.plusMin(it) }
    }

    /**
     * Calls [post] (id, title, text) for what changed since the last fetch, from either the app or the
     * background check: a gate announced or moved, a delay crossing a 15-minute step, a cancellation,
     * or the belt for your bags. What was last alerted is kept in the "alerts" store, so two fetches
     * never alert twice. Ids are 300 + 4 per segment, so each kind of alert replaces its own last one.
     */
    fun flightAlerts(segs: Map<String, Seg>, now: Instant, post: (Int, String, String) -> Unit) {
        val p = store("alerts")
        val e = mutableListOf<Pair<String, Any?>>()
        segs.forEach { (key, s) ->
            val flight = key.substringBefore(':')
            val id = 300 + DEP_AT.keys.indexOf(key).coerceAtLeast(0) * 4
            if (DEP_AT[key]?.isAfter(now) == true) {
                val old = p.string("gate:$key")
                if (s.gate != null && s.gate != old) {
                    post(id, if (old == null) "Gate ${s.gate} for $flight" else "Gate changed: $old → ${s.gate}",
                        "$flight to ${TO[key]}${s.depTerminal?.let { " · Terminal $it" } ?: ""} · leaves ${s.depRevised ?: s.depSched}. Tap for the terminal map.")
                    e += "gate:$key" to s.gate
                }
                val d = s.depDelay ?: 0
                val step = if (d >= 15) d / 15 * 15 else 0
                val was = p.long("delay:$key")
                if (step != was) {
                    if (step > 0) post(id + 1, "$flight delayed about $d min", "Now leaves ${s.depRevised ?: "later"} local.")
                    else post(id + 1, "$flight back on schedule", "Leaves ${s.depSched} local.")
                    e += "delay:$key" to step
                }
                if (s.status.startsWith("Cancel") && !p.bool("cx:$key")) {
                    post(id + 2, "$flight is cancelled", DISRUPTION_HELP)
                    e += "cx:$key" to true
                }
            }
            val lands = ARR_AT[key]
            if (lands != null && now.isAfter(lands.plusMin(-180)) && now.isBefore(lands.plusMin(120)) && s.belt != null && s.belt != p.string("belt:$key")) {
                post(id + 3, "Bags on belt ${s.belt}", "$flight${s.arrTerminal?.let { " · Terminal $it" } ?: ""}.")
                e += "belt:$key" to s.belt
            }
        }
        p.put(*e.toTypedArray())
    }

    /** The check-in alert's wording: when it opens, or the morning-of reminder. */
    fun checkInText(c: CheckIn, reminder: Boolean): Pair<String, String> =
        (if (reminder) "Still need to check in: ${c.flight}" else "${c.flight} check-in is open") to
            (if (reminder) "You haven't checked in for ${c.flight} yet. It leaves ${c.when_}. Booking ${airline.bookingRef}."
            else "Check in now for ${c.when_}. Booking ${airline.bookingRef}, last name ${airline.surname}." + (loyalty?.let { " ${it.memberName} ${it.number}." } ?: ""))
}
