package crazydude.com.telemetry.operation.monitors

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Event
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.operation.TelemetryFrame
import crazydude.com.telemetry.operation.TestData
import crazydude.com.telemetry.operation.processors.TrackProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class AircraftMonitorTest {

    private val drone = Position(54.9226, 24.4711)
    private val warnings = ArrayList<Event.TrafficWarning>()

    private fun operation(kind: Flight.Kind = Flight.Kind.LIVE, withDrone: Boolean = true): FlightOperation {
        val op = FlightOperation(listOf(TrackProcessor()), listOf(AircraftMonitor({ 5000 }, { 1500 }))) { 0 }
        op.subscribeEvents(Executor { it.run() }) { if (it is Event.TrafficWarning) warnings.add(it) }
        op.openFlight(kind)
        if (withDrone) op.submit(Sample.Telemetry(TelemetryFrame(position = drone)))
        return op
    }

    // about 1.1 km north of the drone per 0.01 degree of latitude
    private fun near(id: Int, northDeg: Double, alt: Int = 600, onGround: Boolean = false) =
        TestData.aircraft(id, drone.lat + northDeg, drone.lon, alt, onGround)

    @Test
    fun warnsOfTheClosestAircraftInRangeWithItsBearing() {
        val op = operation()
        op.submit(Sample.Traffic(listOf(near(1, 0.03), near(2, 0.02), near(3, 0.2))))
        assertEquals(1, warnings.size)
        assertEquals(2, warnings[0].aircraft.flightId)
        assertEquals(2224.0, warnings[0].distanceMeters, 5.0)
        // north: 0 or just under 360, as the aircraft position is a Float
        assertEquals(0.0, Math.min(warnings[0].bearingDeg, 360 - warnings[0].bearingDeg), 0.5)
    }

    @Test
    fun ignoresAircraftOnTheGroundOrAboveTheCeiling() {
        val op = operation()
        op.submit(Sample.Traffic(listOf(near(1, 0.01, onGround = true), near(2, 0.01, alt = 1600))))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun warnsAgainOnEachUpdateWhileInRange() {
        val op = operation()
        op.submit(Sample.Traffic(listOf(near(1, 0.02))))
        op.submit(Sample.Traffic(listOf(near(1, 0.015))))
        assertEquals(2, warnings.size)
    }

    @Test
    fun noWarningWithoutADronePositionOrForAReplay() {
        operation(withDrone = false).submit(Sample.Traffic(listOf(near(1, 0.01))))
        operation(Flight.Kind.REPLAY).submit(Sample.Traffic(listOf(near(1, 0.01))))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun bearingPointsTheRightWay() {
        assertEquals(90.0, AircraftMonitor.bearing(0.0, 0.0, 0.0, 1.0), 0.01)
        assertEquals(180.0, AircraftMonitor.bearing(1.0, 0.0, 0.0, 0.0), 0.01)
        assertEquals(270.0, AircraftMonitor.bearing(0.0, 1.0, 0.0, 0.0), 0.01)
    }
}
