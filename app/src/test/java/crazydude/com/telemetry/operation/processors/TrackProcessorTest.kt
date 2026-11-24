package crazydude.com.telemetry.operation.processors

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.operation.TelemetryFrame
import crazydude.com.telemetry.utils.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackProcessorTest {

    private val a = Position(54.9226, 24.4711)
    private val b = Position(54.9236, 24.4711)
    private val c = Position(54.9246, 24.4721)
    private val ab = GeoUtils.computeDistanceBetween(a.lat, a.lon, b.lat, b.lon)
    private val bc = GeoUtils.computeDistanceBetween(b.lat, b.lon, c.lat, c.lon)

    private fun operation(kind: Flight.Kind = Flight.Kind.LIVE) =
        FlightOperation(listOf(TrackProcessor()), emptyList()) { 0 }.also { it.openFlight(kind) }

    private fun FlightOperation.position(p: Position, fix: Boolean? = null) =
        submit(Sample.Telemetry(TelemetryFrame(position = p, gpsFix = fix)))

    private fun FlightOperation.flight() = read { flight, _ -> flight!! }

    @Test
    fun positionsWithAFixMakeTheTrackAndTheDistance() {
        val op = operation()
        op.position(a, fix = true)
        op.position(b)
        op.position(b)
        op.position(c)
        val flight = op.flight()
        assertEquals(listOf(a, b, c), flight.track)
        assertEquals(ab + bc, flight.traveledDistance, 1e-6)
        assertEquals(c, flight.values.position)
    }

    @Test
    fun withoutAFixOnlyThePositionMoves() {
        val op = operation()
        op.position(a, fix = false)
        op.position(b)
        val flight = op.flight()
        assertEquals(0, flight.track.size)
        assertEquals(0.0, flight.traveledDistance, 0.0)
        assertEquals(b, flight.values.position)
    }

    @Test
    fun fixAndPositionInTheSameFrameCount() {
        val op = operation()
        op.position(a, fix = true)
        assertEquals(listOf(a), op.flight().track)
    }

    @Test
    fun trackSamplesAppendOrReplace() {
        val op = operation()
        op.submit(Sample.Telemetry(TelemetryFrame(gpsFix = true)))
        op.submit(Sample.Track(listOf(a, b), replace = false))
        assertEquals(listOf(a, b), op.flight().track)
        op.submit(Sample.Track(listOf(b, c), replace = true))
        val flight = op.flight()
        assertEquals(listOf(b, c), flight.track)
        assertEquals(bc, flight.traveledDistance, 1e-6)
        assertEquals(1, flight.trackResets)
    }

    @Test
    fun replayHomeIsTheFirstPositionWithAFix() {
        val op = operation(Flight.Kind.REPLAY)
        op.submit(Sample.Track(listOf(a), replace = false))  // no fix yet: ignored
        assertNull(op.flight().home)
        op.submit(Sample.Telemetry(TelemetryFrame(gpsFix = true)))
        op.submit(Sample.Track(listOf(b, c), replace = false))
        assertEquals(b, op.flight().home)
    }

    @Test
    fun liveFlightsHaveNoHome() {
        val op = operation()
        op.submit(Sample.Telemetry(TelemetryFrame(gpsFix = true)))
        op.submit(Sample.Track(listOf(a), replace = false))
        assertNull(op.flight().home)
    }

    @Test
    fun noFlightNothingHappens() {
        val op = FlightOperation(listOf(TrackProcessor()), emptyList()) { 0 }
        op.position(a, fix = true)
        assertNull(op.read { flight, _ -> flight })
    }
}
