package crazydude.com.telemetry.operation.monitors

import crazydude.com.telemetry.operation.Command
import crazydude.com.telemetry.operation.Event
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.LinkStatus
import crazydude.com.telemetry.operation.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class LinkMonitorTest {

    private var now = 0L
    private var reconnectable = true
    private val events = ArrayList<Event>()
    private val commands = ArrayList<Command>()
    private val op = FlightOperation(emptyList(), listOf(LinkMonitor { reconnectable })) { now }.also {
        it.subscribeEvents(Executor { r -> r.run() }) { e -> events.add(e) }
        it.subscribeCommands(Executor { r -> r.run() }) { c -> commands.add(c) }
        it.openFlight(Flight.Kind.LIVE)
    }

    private fun at(ms: Long, sample: Sample) {
        now = ms
        op.submit(sample)
    }

    private fun beat(ms: Long) = at(ms, Sample.Heartbeat(ms))

    @Test
    fun connectionEvents() {
        at(0, Sample.Link(LinkStatus.CONNECTED))
        at(1, Sample.Protocol("CRSF"))
        at(2, Sample.Link(LinkStatus.DISCONNECTED))
        at(3, Sample.Link(LinkStatus.FAILED))
        assertEquals(listOf(Event.Connected, Event.ProtocolDetected("CRSF"), Event.Disconnected, Event.ConnectionFailed), events)
    }

    @Test
    fun aLostLinkIsRetriedAfterFiveSecondsAndAfterEachFailure() {
        at(0, Sample.Link(LinkStatus.LOST))
        beat(4_999)
        assertTrue(commands.isEmpty())
        beat(5_000)
        assertEquals(listOf<Command>(Command.Reconnect), commands)
        beat(6_000)  // an attempt is under way: no second one
        assertEquals(1, commands.size)
        at(9_000, Sample.Link(LinkStatus.FAILED))
        beat(13_999)
        assertEquals(1, commands.size)
        beat(14_000)
        assertEquals(2, commands.size)
        assertEquals(listOf(Event.LinkLost, Event.Reconnecting, Event.ConnectionFailed, Event.Reconnecting), events)
    }

    @Test
    fun reconnectionGivesUpAfterTwentyOneSeconds() {
        at(0, Sample.Link(LinkStatus.LOST))
        beat(5_000)
        at(6_000, Sample.Link(LinkStatus.FAILED))
        beat(11_000)
        at(12_000, Sample.Link(LinkStatus.FAILED))
        beat(17_000)
        at(18_000, Sample.Link(LinkStatus.FAILED))
        beat(21_000)
        beat(30_000)
        assertEquals(3, commands.size)
    }

    @Test
    fun aSuccessfulReconnectionStopsTheAttempts() {
        at(0, Sample.Link(LinkStatus.LOST))
        beat(5_000)
        at(6_000, Sample.Link(LinkStatus.CONNECTED))
        beat(12_000)
        beat(18_000)
        assertEquals(1, commands.size)
    }

    @Test
    fun noReconnectionWhenNotAllowed() {
        reconnectable = false
        at(0, Sample.Link(LinkStatus.LOST))
        beat(10_000)
        assertTrue(commands.isEmpty())
        assertEquals(listOf<Event>(Event.LinkLost), events)
    }

    @Test
    fun aManualDisconnectStopsTheAttempts() {
        at(0, Sample.Link(LinkStatus.LOST))
        at(1_000, Sample.Link(LinkStatus.DISCONNECTED))
        beat(6_000)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun aNewFlightForgetsTheLostLink() {
        at(0, Sample.Link(LinkStatus.LOST))
        op.openFlight(Flight.Kind.LIVE)
        beat(6_000)
        assertTrue(commands.isEmpty())
    }
}
