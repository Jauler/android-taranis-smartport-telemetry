package crazydude.com.telemetry.integration

import crazydude.com.telemetry.ingestion.SampleAssembler
import crazydude.com.telemetry.logger.RcFrameThrottle
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Command
import crazydude.com.telemetry.operation.Event
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.LinkStatus
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.operation.TestData
import crazydude.com.telemetry.operation.monitors.AircraftMonitor
import crazydude.com.telemetry.operation.monitors.LinkMonitor
import crazydude.com.telemetry.operation.processors.FreshnessProcessor
import crazydude.com.telemetry.operation.processors.TrackProcessor
import crazydude.com.telemetry.output.CsvRecorder
import crazydude.com.telemetry.protocol.CrsfProtocol
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.utils.GeoUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.concurrent.Executor

/**
 * Ingestion and the flight operation together: CRSF bytes as the link delivers them, decoded by
 * the real protocol, assembled into samples, run through the real processors and monitors.
 */
class FlightOperationIntegrationTest {

    private var now = 0L
    private val events = ArrayList<Event>()
    private val commands = ArrayList<Command>()
    private val operation = FlightOperation(
        listOf(TrackProcessor(), FreshnessProcessor()),
        listOf(LinkMonitor { true }, AircraftMonitor({ 5000 }, { 1500 }))
    ) { now }.also {
        it.subscribeEvents(Executor { r -> r.run() }) { e -> events.add(e) }
        it.subscribeCommands(Executor { r -> r.run() }) { c -> commands.add(c) }
    }

    /** What the data service does with the link's callbacks. */
    private val linkListener = object : SampleAssembler.LinkListener {
        override fun onConnected() = operation.submit(Sample.Link(LinkStatus.CONNECTED))
        override fun onDisconnected() = operation.submit(Sample.Link(LinkStatus.LOST))
        override fun onConnectionFailed() = operation.submit(Sample.Link(LinkStatus.FAILED))
        override fun onProtocolDetected(name: String) = operation.submit(Sample.Protocol(name))
    }
    private val assembler = SampleAssembler({ operation.submit(it) }, linkListener, restarts = false)
    private var protocol = CrsfProtocol(assembler)

    /** One batch of bytes from the link, then the commit the poller makes after each batch. */
    private fun receive(vararg frames: ByteArray) {
        frames.forEach { f -> f.forEach { assembler.onTelemetryByte(); protocol.process(it.toInt() and 0xFF) } }
        assembler.commit()
    }

    private fun flight() = operation.read { flight, _ -> flight!! }

    // CRSF decodes a frame only once 20 bytes are waiting: a link-statistics frame after each
    // frame of interest makes sure it is out, as the steady stream of a real link does
    private val pad = CrsfFrames.linkStats(-60, 100)

    @Test
    fun decodedFramesBecomeTheFlight() {
        operation.openFlight(Flight.Kind.LIVE)
        receive(
            CrsfFrames.gps(54.9226, 24.4711, altitudeM = 120, satellites = 12), pad,
            CrsfFrames.battery(16.4, 3.5, 450), pad,
            CrsfFrames.flightMode("ACRO"), pad, pad
        )
        val v = flight().values
        assertEquals(54.9226, v.position!!.lat, 1e-6)
        assertEquals(24.4711, v.position!!.lon, 1e-6)
        assertEquals(12, v.satellites)
        assertTrue(v.gpsFix)
        assertEquals(120f, v.altitude!!, 0.01f)
        assertEquals(16.4f, v.vbatOrCell!!, 0.01f)
        assertEquals(3.5f, v.current!!, 0.01f)
        assertEquals(450, v.fuel)
        assertEquals(-60, v.rssiDbm1)  // v.rssi is the decoder's percentage
        assertEquals(100, v.upLq)
        assertTrue(v.flyMode!!.armed)
        assertEquals(DataDecoder.Companion.FlyMode.ACRO, v.flyMode!!.mode1)
        assertEquals(1, flight().track.size)
    }

    @Test
    fun aFlightAlongAPathMakesItsTrackAndDistance() {
        operation.openFlight(Flight.Kind.LIVE)
        val path = (0 until 10).map { Position(54.9226 + it * 0.001, 24.4711) }
        path.forEach { receive(CrsfFrames.gps(it.lat, it.lon, 100, 10), pad) }
        val expected = path.zipWithNext { a, b -> GeoUtils.computeDistanceBetween(a.lat, a.lon, b.lat, b.lon) }.sum()
        assertEquals(10, flight().track.size)
        assertEquals(expected, flight().traveledDistance, 1.0)
    }

    @Test
    fun withoutAFixThereIsNoTrack() {
        operation.openFlight(Flight.Kind.LIVE)
        receive(CrsfFrames.gps(54.9226, 24.4711, 100, satellites = 4), pad)
        receive(CrsfFrames.gps(54.9236, 24.4711, 100, satellites = 4), pad)
        assertEquals(0, flight().track.size)
        assertEquals(54.9236, flight().values.position!!.lat, 1e-6)
    }

    @Test
    fun trafficNearTheDecodedDroneIsWarnedOf() {
        operation.openFlight(Flight.Kind.LIVE)
        receive(CrsfFrames.gps(54.9226, 24.4711, 100, 10), pad)
        operation.submit(Sample.Traffic(listOf(TestData.aircraft(42, 54.9426, 24.4711, altMeters = 800))))
        val warning = events.filterIsInstance<Event.TrafficWarning>().single()
        assertEquals(42, warning.aircraft.flightId)
        assertEquals(2224.0, warning.distanceMeters, 5.0)
    }

    @Test
    fun aLostLinkIsReconnectedAndTheFlightGoesOn() {
        operation.openFlight(Flight.Kind.LIVE)
        assembler.onConnected()
        receive(CrsfFrames.gps(54.9226, 24.4711, 100, 10), pad)
        receive(CrsfFrames.gps(54.9236, 24.4711, 100, 10), pad)
        val before = flight().traveledDistance

        now = 1_000
        assembler.onDisconnected()
        now = 6_000
        operation.submit(Sample.Heartbeat(now))
        assertEquals(listOf<Command>(Command.Reconnect), commands)

        // the reconnection: a new link, a new protocol and decoder (whose start is no restart)
        protocol = CrsfProtocol(assembler)
        assembler.onConnected()
        receive(CrsfFrames.gps(54.9246, 24.4711, 100, 10), pad)
        assertEquals(3, flight().track.size)
        assertTrue(flight().traveledDistance > before)
        assertEquals(listOf(Event.Connected, Event.LinkLost, Event.Reconnecting, Event.Connected), events)
    }

    @Test
    fun theThrottledRawLogStillDecodes() {
        // what the link sends: 50 RC frames a second with a GPS frame among them
        val log = ByteArrayOutputStream()
        val throttle = RcFrameThrottle(log, 5) { now }
        for (i in 0 until 50) {
            now = i * 20L
            throttle.write(CrsfFrames.rcChannels())
            if (i % 10 == 0) throttle.write(CrsfFrames.gps(54.9226 + i * 0.0001, 24.4711, 100, 10))
        }
        throttle.write(pad + pad)
        throttle.close()

        operation.openFlight(Flight.Kind.REPLAY)
        val replayed = SampleAssembler({ operation.submit(it) }, linkListener)
        val replayProtocol = CrsfProtocol(replayed)
        log.toByteArray().forEach { replayProtocol.process(it.toInt() and 0xFF) }
        replayed.commit()
        assertEquals(54.9226 + 40 * 0.0001, flight().values.position!!.lat, 1e-6)
        assertTrue(log.size() < 50 * CrsfFrames.rcChannels().size)
    }

    @Test
    fun theCsvRowShowsTheDecodedFlight() {
        operation.openFlight(Flight.Kind.LIVE)
        receive(CrsfFrames.gps(54.9226, 24.4711, 93, 26), pad, CrsfFrames.battery(16.4, 0.0, 0), pad, pad)
        val row = CsvRecorder.row(flight().values, Date(0))
        assertEquals("54.9226 24.4711", row[17])
        assertEquals("93.0", row[20])
        assertEquals("26", row[21])
        assertEquals("16.4", row[23])
    }
}
