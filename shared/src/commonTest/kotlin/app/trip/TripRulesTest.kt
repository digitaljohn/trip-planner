package app.trip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Checks a trip against the rules in SKILL.md. Runs on whichever trip the build points at:
 * `./gradlew testReleaseUnitTest -PtripDir=<folder>` (`iosSimulatorArm64Test` runs the same checks on iOS);
 * without -PtripDir it checks the example.
 */
class TripRulesTest {

    @Test
    fun legsJoinUpEndToEnd() {
        assertTrue(legs.first().kind == Kind.PRE && legs.first().from == null, "the first leg must be PRE, open at the start")
        assertTrue(legs.last().kind == Kind.POST && legs.last().to == null, "the last leg must be POST, open at the end")
        for (i in 1 until legs.size) {
            val prev = legs[i - 1]
            val leg = legs[i]
            assertEquals(prev.to, leg.from, "leg $i (${leg.short}) must start where leg ${i - 1} (${prev.short}) ends")
            if (leg.to != null) assertTrue(leg.from!!.isBefore(leg.to), "leg $i (${leg.short}) ends before it starts")
        }
    }

    @Test
    fun airportsPointAtTheirGroundLegs() {
        airports.forEach { a ->
            val leg = legs.getOrNull(a.leg)
            assertNotNull(leg, "airport '${a.title}' points at leg ${a.leg}, which doesn't exist")
            assertEquals(Kind.GROUND, leg!!.kind, "airport '${a.title}' must point at a GROUND leg")
            assertEquals(a.leg, leg.airport, "leg ${a.leg} (${leg.short}) must name airport ${a.leg} back")
            assertEquals(a.steps.sortedBy { it.at }, a.steps, "airport '${a.title}' steps must be in time order")
        }
        legs.withIndex().filter { it.value.kind == Kind.GROUND }.forEach { (i, leg) ->
            assertTrue(airports.any { it.leg == i }, "GROUND leg $i (${leg.short}) has no airport entry")
        }
    }

    @Test
    fun flightsAreInOrder() {
        assertTrue(segments.isNotEmpty(), "there must be at least one flight")
        segments.forEach { s -> assertTrue(s.departs.isBefore(s.arrives), "${s.key} lands before it takes off") }
        segments.zipWithNext().forEach { (a, b) ->
            assertTrue(!b.departs.isBefore(a.arrives), "${b.key} takes off before ${a.key} lands; segments must be in flight order")
        }
        assertEquals(segments.first().departs, OUT, "OUT must be the first flight's take-off")
        assertTrue(segments.any { it.departs == RET }, "RET must be a flight's take-off")
    }

    @Test
    fun watchesNameRealFlights() {
        val keys = segments.map { it.key }.toSet()
        (legs.mapNotNull { it.watch } + stops.map { it.watch }).forEach { w ->
            assertTrue("${w.flight}:${w.from}" in keys, "watch ${w.flight}:${w.from} doesn't match any segment (${keys.joinToString()})")
        }
    }

    @Test
    fun stopsAreDepartureAirportsWithRoomForFreeTime() {
        stops.forEach { s ->
            val leg = legs.getOrNull(s.airport)
            assertTrue(leg?.kind == Kind.GROUND, "stop at leg ${s.airport} must be a GROUND leg")
            assertTrue(!s.watch.arriving, "stop at leg ${s.airport} must watch a departing flight")
            assertTrue(segments.any { it.key == "${s.watch.flight}:${s.watch.from}" && it.departs == s.takeOff }, "stop at leg ${s.airport} must take off at its flight's time")
            val phases = s.phases(null, 0)
            phases.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from, "stop ${s.airport}: '${a.label}' must end where '${b.label}' starts") }
            val free = phases.first { it.free }
            assertTrue(free.from < free.to, "stop ${s.airport}: no free time left after the walk to the gate")
        }
    }

    @Test
    fun checkInsAreConsistent() {
        val ids = todos.map { it.id }.toSet()
        checkIns.forEach { c ->
            assertTrue(c.todo in ids, "check-in ${c.todo} needs a todo with that id")
            assertTrue(c.opens.isBefore(c.departs), "check-in ${c.todo} must open before take-off")
            assertTrue(!c.remindAt.isBefore(c.opens) && c.remindAt.isBefore(c.departs), "check-in ${c.todo} reminder must fall while it's open")
            assertTrue(segments.any { it.flight == c.flight && it.departs == c.departs }, "check-in ${c.todo} must be for a real flight")
        }
    }

    /** Walks the whole trip every 15 minutes, as the app does, and checks every moment has something to say. */
    @Test
    fun everyMomentHasSomethingToShow() {
        val start = OUT.minus(Duration.ofDays(2))
        val end = segments.last().arrives.plus(Duration.ofDays(1))
        var t: Instant = start
        while (t.isBefore(end)) {
            for (checkedIn in listOf(false, true)) {
                val s = try { statusAt(t, checkedIn) } catch (e: Exception) { fail("statusAt($t) threw $e") }
                assertTrue(s.doTitle.isNotBlank(), "blank title at $t (${s.leg.short})")
                // Airport steps may be title-only ("Land"); everything else needs a line of guidance.
                assertTrue(s.doDetail.isNotBlank() || s.leg.kind == Kind.GROUND, "blank detail at $t (${s.leg.short})")
            }
            t = t.plus(Duration.ofMinutes(15))
        }
    }

}
