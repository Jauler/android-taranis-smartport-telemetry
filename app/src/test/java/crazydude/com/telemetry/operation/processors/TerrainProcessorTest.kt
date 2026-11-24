package crazydude.com.telemetry.operation.processors

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.FlyMode
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.operation.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class TerrainProcessorTest {

    private val calls = ArrayList<String>()
    private val queued = ArrayList<Runnable>()
    private val target = object : TerrainProcessor.Target {
        override fun onAircraftPosition(lat: Double, lon: Double) { calls.add("aircraft $lat,$lon") }
        override fun onAltitude(altitude: Float) { calls.add("altitude $altitude") }
        override fun onArmedState(armed: Boolean) { calls.add("armed $armed") }
        override fun onAntennaPosition(position: Position?) { calls.add("antenna ${position?.lat}") }
        override fun reset() { calls.add("reset") }
    }
    private val op = FlightOperation(listOf(TrackProcessor(), TerrainProcessor(target, Executor { queued.add(it) })), emptyList()) { 0 }

    private fun run(): List<String> {
        queued.forEach { it.run() }
        queued.clear()
        val result = ArrayList(calls)
        calls.clear()
        return result
    }

    @Test
    fun liveValuesAndThePilotAsAntenna() {
        op.openFlight(Flight.Kind.LIVE)
        op.submit(Sample.PilotPosition(Position(54.0, 24.0)))
        op.submit(Sample.Telemetry(TelemetryFrame(position = Position(54.1, 24.1), altitude = 50f, flyMode = FlyMode(true, false, null, null))))
        assertEquals(listOf("reset", "antenna 54.0", "aircraft 54.1,24.1", "altitude 50.0", "armed true"), run())
    }

    @Test
    fun nothingRelevantNothingSent() {
        op.openFlight(Flight.Kind.LIVE)
        run()
        op.submit(Sample.Telemetry(TelemetryFrame(rssi = 90)))
        op.submit(Sample.Heartbeat(0))
        assertTrue(run().isEmpty())
    }

    @Test
    fun theAntennaIsSentOnlyWhenItChanges() {
        op.openFlight(Flight.Kind.LIVE)
        op.submit(Sample.PilotPosition(Position(54.0, 24.0)))
        op.submit(Sample.PilotPosition(Position(54.0, 24.0)))
        op.submit(Sample.PilotPosition(Position(55.0, 24.0)))
        assertEquals(listOf("reset", "antenna 54.0", "antenna 55.0"), run())
    }

    @Test
    fun replayUsesTheFlightsHomeAsAntennaAndSendsTheTracksEnds() {
        op.openFlight(Flight.Kind.REPLAY)
        op.submit(Sample.PilotPosition(Position(10.0, 10.0)))  // the live pilot does not count
        op.submit(Sample.Telemetry(TelemetryFrame(gpsFix = true)))
        op.submit(Sample.Track(listOf(Position(54.1, 24.0), Position(54.2, 24.0), Position(54.3, 24.0)), replace = false))
        assertEquals(listOf("reset", "antenna null", "aircraft 54.1,24.0", "aircraft 54.3,24.0", "antenna 54.1"), run())
    }

    @Test
    fun aRestartResetsTheCalculation() {
        op.openFlight(Flight.Kind.REPLAY)
        run()
        op.submit(Sample.Restart)
        assertEquals(listOf("reset", "antenna null"), run())
    }
}
