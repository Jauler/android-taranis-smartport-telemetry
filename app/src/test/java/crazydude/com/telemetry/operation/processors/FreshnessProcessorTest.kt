package crazydude.com.telemetry.operation.processors

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.operation.Sensor
import crazydude.com.telemetry.operation.TelemetryFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreshnessProcessorTest {

    private var now = 0L
    private val op = FlightOperation(listOf(FreshnessProcessor()), emptyList()) { now }.also {
        it.openFlight(Flight.Kind.LIVE)
    }

    private fun beat(at: Long) {
        now = at
        op.submit(Sample.Heartbeat(at))
    }

    private fun stale() = op.read { flight, _ -> flight!!.stale }

    @Test
    fun valuesGrowOldAfterTenSecondsWithoutNews() {
        now = 1000
        op.submit(Sample.Telemetry(TelemetryFrame(rssi = 90, upLq = 100)))
        beat(10_999)
        assertTrue(stale().isEmpty())
        now = 5000
        op.submit(Sample.Telemetry(TelemetryFrame(rssi = 91)))
        beat(11_000)
        assertEquals(setOf(Sensor.UP_LQ), stale())
    }

    @Test
    fun neverReceivedIsNotOld() {
        beat(100_000)
        assertTrue(stale().isEmpty())
    }

    @Test
    fun frameFieldsMapToTheirSensors() {
        val sensors = FreshnessProcessor.sensorsIn(
            TelemetryFrame(position = Position(1.0, 2.0), vbatOrCell = 4.0f, rcChannels = IntArray(16), statusText = "x")
        )
        assertEquals(setOf(Sensor.GPS, Sensor.VOLTAGE, Sensor.CELL_VOLTAGE, Sensor.RC_CHANNELS, Sensor.STATUSTEXT), sensors.toSet())
    }

    @Test
    fun replayPositionsCountAsGps() {
        now = 0
        op.submit(Sample.Track(listOf(Position(1.0, 2.0)), replace = false))
        beat(10_000)
        assertEquals(setOf(Sensor.GPS), stale())
    }

    @Test
    fun telemetryRateIsBytesPerSecondBetweenHeartbeats() {
        beat(1000)
        op.submit(Sample.Telemetry(TelemetryFrame(bytes = 600)))
        op.submit(Sample.Telemetry(TelemetryFrame(bytes = 700)))
        beat(2000)
        assertEquals(1300, op.read { flight, _ -> flight!!.telemetryRate })
        op.submit(Sample.Telemetry(TelemetryFrame(bytes = 1000)))
        beat(4000)
        assertEquals(500, op.read { flight, _ -> flight!!.telemetryRate })
    }
}
