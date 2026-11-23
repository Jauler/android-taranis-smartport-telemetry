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
        t.onAltitude(0f)
        t.onAircraftPosition(field, true)
        t.onAircraftPosition(runway, true)
        assertEquals(runway, t.takeoff)
        t.onArmedState(true)
        t.onAltitude(80f)
        t.onAircraftPosition(inFlight, true)
        assertEquals(runway, t.takeoff)
        assertEquals(inFlight, t.aircraft)
    }

    @Test
    fun disarmingMovesItToTheLandingPoint() {
        val t = TakeoffTracker()
        t.onArmedState(true)
        t.onAltitude(0f)
        t.onAircraftPosition(field, true)
        t.onAltitude(50f)
        t.onAircraftPosition(farAway, true)
        assertEquals(field, t.takeoff)
        t.onArmedState(false)
        assertEquals(farAway, t.takeoff)
    }

    @Test
    fun unknownArmedStateTakesTheFirstPositionOnTheGround() {
        val t = TakeoffTracker()
        t.onAltitude(0.5f)
        t.onAircraftPosition(field, true)
        t.onAltitude(60f)
        t.onAircraftPosition(inFlight, true)
        assertEquals(field, t.takeoff)
    }

    @Test
    fun noTakeoffPointPickedUpInTheAir() {
        // the application or the connection started while the aircraft is flying
        val t = TakeoffTracker()
        t.onArmedState(true)
        t.onAltitude(120f)
        t.onAircraftPosition(inFlight, true)
        assertNull(t.takeoff)
        // ... and still nothing while the altitude is unknown
        val u = TakeoffTracker()
        u.onAircraftPosition(inFlight, true)
        assertNull(u.takeoff)
        // once it is back on the ground, it is
        t.onAltitude(1f)
        t.onAircraftPosition(field, true)
        assertEquals(field, t.takeoff)
    }

    @Test
    fun altitudeAboveSeaLevelTellsNothingAboutTheGround() {
        val t = TakeoffTracker()
        t.onArmedState(true)
        t.onAltitude(166f)
        t.onAircraftPosition(field, false)
        assertEquals(field, t.takeoff)
    }

    @Test
    fun setTakeoffHereStaysUntilReset() {
        val t = TakeoffTracker()
        assertFalse(t.setTakeoffHere())
        t.onArmedState(false)
        t.onAircraftPosition(runway, true)
        assertTrue(t.setTakeoffHere())
        t.onAircraftPosition(farAway, true)
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
        t.onArmedState(true)
        t.reset()
        // armed state unknown again: the first position on the ground is taken
        t.onAltitude(0f)
        t.onAircraftPosition(field, true)
        t.onAltitude(30f)
        t.onAircraftPosition(inFlight, true)
        assertEquals(field, t.takeoff)
    }
}
