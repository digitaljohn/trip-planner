package app.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Checks a trip against the rules in SKILL.md. Runs on whichever trip the build points at:
 * `./gradlew testReleaseUnitTest -PtripDir=<folder>`; without -PtripDir it checks the example.
 */
class TripRulesTest {

    @Test
    fun legsJoinUpEndToEnd() {
        assertTrue("the first leg must be PRE, open at the start", legs.first().kind == Kind.PRE && legs.first().from == null)
        assertTrue("the last leg must be POST, open at the end", legs.last().kind == Kind.POST && legs.last().to == null)
        for (i in 1 until legs.size) {
            val prev = legs[i - 1]
            val leg = legs[i]
            assertEquals("leg $i (${leg.short}) must start where leg ${i - 1} (${prev.short}) ends", prev.to, leg.from)
            if (leg.to != null) assertTrue("leg $i (${leg.short}) ends before it starts", leg.from!!.isBefore(leg.to))
        }
    }

    @Test
    fun airportsPointAtTheirGroundLegs() {
        airports.forEach { a ->
            val leg = legs.getOrNull(a.leg)
            assertNotNull("airport '${a.title}' points at leg ${a.leg}, which doesn't exist", leg)
            assertEquals("airport '${a.title}' must point at a GROUND leg", Kind.GROUND, leg!!.kind)
            assertEquals("leg ${a.leg} (${leg.short}) must name airport ${a.leg} back", a.leg, leg.airport)
            assertEquals("airport '${a.title}' steps must be in time order", a.steps.sortedBy { it.at }, a.steps)
        }
        legs.withIndex().filter { it.value.kind == Kind.GROUND }.forEach { (i, leg) ->
            assertTrue("GROUND leg $i (${leg.short}) has no airport entry", airports.any { it.leg == i })
        }
    }

    @Test
    fun flightsAreInOrder() {
        assertTrue("there must be at least one flight", segments.isNotEmpty())
        segments.forEach { s -> assertTrue("${s.key} lands before it takes off", s.departs.isBefore(s.arrives)) }
        segments.zipWithNext().forEach { (a, b) ->
            assertTrue("${b.key} takes off before ${a.key} lands; segments must be in flight order", !b.departs.isBefore(a.arrives))
        }
        assertEquals("OUT must be the first flight's take-off", segments.first().departs, OUT)
        assertTrue("RET must be a flight's take-off", segments.any { it.departs == RET })
    }

    @Test
    fun watchesNameRealFlights() {
        val keys = segments.map { it.key }.toSet()
        (legs.mapNotNull { it.watch } + stops.map { it.watch }).forEach { w ->
            assertTrue("watch ${w.flight}:${w.from} doesn't match any segment (${keys.joinToString()})", "${w.flight}:${w.from}" in keys)
        }
    }

    @Test
    fun stopsAreDepartureAirportsWithRoomForFreeTime() {
        stops.forEach { s ->
            val leg = legs.getOrNull(s.airport)
            assertTrue("stop at leg ${s.airport} must be a GROUND leg", leg?.kind == Kind.GROUND)
            assertTrue("stop at leg ${s.airport} must watch a departing flight", !s.watch.arriving)
            assertTrue("stop at leg ${s.airport} must take off at its flight's time", segments.any { it.key == "${s.watch.flight}:${s.watch.from}" && it.departs == s.takeOff })
            val phases = s.phases(null, 0)
            phases.zipWithNext().forEach { (a, b) -> assertEquals("stop ${s.airport}: '${a.label}' must end where '${b.label}' starts", a.to, b.from) }
            val free = phases.first { it.free }
            assertTrue("stop ${s.airport}: no free time left after the walk to the gate", free.from < free.to)
        }
    }

    @Test
    fun checkInsAreConsistent() {
        val ids = todos.map { it.id }.toSet()
        checkIns.forEach { c ->
            assertTrue("check-in ${c.todo} needs a todo with that id", c.todo in ids)
            assertTrue("check-in ${c.todo} must open before take-off", c.opens.isBefore(c.departs))
            assertTrue("check-in ${c.todo} reminder must fall while it's open", !c.remindAt.isBefore(c.opens) && c.remindAt.isBefore(c.departs))
            assertTrue("check-in ${c.todo} must be for a real flight", segments.any { it.flight == c.flight && it.departs == c.departs })
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
                val s = try { statusAt(t, checkedIn) } catch (e: Exception) { fail("statusAt($t) threw $e"); return }
                assertTrue("blank title at $t (${s.leg.short})", s.doTitle.isNotBlank())
                // Airport steps may be title-only ("Land"); everything else needs a line of guidance.
                assertTrue("blank detail at $t (${s.leg.short})", s.doDetail.isNotBlank() || s.leg.kind == Kind.GROUND)
            }
            t = t.plus(Duration.ofMinutes(15))
        }
    }

}
