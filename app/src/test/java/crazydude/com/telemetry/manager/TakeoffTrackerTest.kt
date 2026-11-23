package crazydude.com.telemetry.manager

import crazydude.com.telemetry.maps.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TakeoffTrackerTest {

    private val field = Position(54.9226, 24.4711)
    private val runway = Position(54.9230, 24.4720)
    private val inFlight = Position(54.9300, 24.5000)
    private val farAway = Position(54.9500, 24.6000)

    @Test
    fun followsWhileDisarmedAndStaysWhereArmed() {
        val t = TakeoffTracker()
        t.onArmedState(false)
        t.onAircraftPosition(field)
        t.onAircraftPosition(runway)
        assertEquals(runway, t.takeoff)
        t.onArmedState(true)
        t.onAircraftPosition(inFlight)
        assertEquals(runway, t.takeoff)
        assertEquals(inFlight, t.aircraft)
    }

    @Test
    fun disarmingMovesItToTheLandingPoint() {
        val t = TakeoffTracker()
        t.onArmedState(true)
        t.onAircraftPosition(field)
        t.onAircraftPosition(farAway)
        assertEquals(field, t.takeoff)
        t.onArmedState(false)
        assertEquals(farAway, t.takeoff)
    }

    @Test
    fun unknownArmedStateTakesTheFirstPosition() {
        val t = TakeoffTracker()
        t.onAircraftPosition(field)
        t.onAircraftPosition(inFlight)
        assertEquals(field, t.takeoff)
    }

    @Test
    fun altitudeIsKept() {
        val t = TakeoffTracker()
        assertTrue(t.altitude.isNaN())
        t.onAltitude(42f)
        assertEquals(42f, t.altitude, 0f)
    }

    @Test
    fun setTakeoffHereStaysUntilReset() {
        val t = TakeoffTracker()
        assertFalse(t.setTakeoffHere())
        t.onArmedState(false)
        t.onAircraftPosition(runway)
        assertTrue(t.setTakeoffHere())
        t.onAircraftPosition(farAway)
        t.onArmedState(true)
        t.onArmedState(false)
        assertEquals(runway, t.takeoff)
        assertTrue(t.locked)
        t.reset()
        assertNull(t.takeoff)
        assertNull(t.aircraft)
        assertFalse(t.locked)
        assertTrue(t.altitude.isNaN())
    }

    @Test
    fun resetForgetsTheArmedState() {
        val t = TakeoffTracker()
        t.onArmedState(false)
        t.reset()
        // armed state unknown again: the first position is kept, not followed
        t.onAircraftPosition(field)
        t.onAircraftPosition(inFlight)
        assertEquals(field, t.takeoff)
    }
}
