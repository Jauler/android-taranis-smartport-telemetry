package crazydude.com.telemetry.operation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class FlightOperationTest {

    private val direct = Executor { it.run() }
    private var now = 1000L

    /** Records what it saw, and emits a given event on every pass. */
    private class Recorder(val log: MutableList<String>, val name: String, val event: Event? = null) :
        FlightOperation.Processor, FlightOperation.Monitor {
        var changed = 0
        override fun process(pass: FlightOperation.Pass) {
            log.add("$name:${pass.sample.javaClass.simpleName}")
        }
        override fun check(pass: FlightOperation.Pass) {
            log.add("$name:${pass.sample.javaClass.simpleName}")
            event?.let { pass.emit(it) }
        }
        override fun onFlightChanged() {
            changed++
        }
    }

    @Test
    fun processorsRunBeforeMonitorsInListOrder() {
        val log = ArrayList<String>()
        val op = FlightOperation(
            listOf(Recorder(log, "p1"), Recorder(log, "p2")),
            listOf(Recorder(log, "m1"), Recorder(log, "m2"))
        ) { now }
        op.submit(Sample.Heartbeat(now))
        assertEquals(listOf("p1:Heartbeat", "p2:Heartbeat", "m1:Heartbeat", "m2:Heartbeat"), log)
    }

    @Test
    fun telemetryIsAppliedBeforeProcessorsRun() {
        var seen: Int? = null
        val processor = object : FlightOperation.Processor {
            override fun process(pass: FlightOperation.Pass) {
                seen = pass.flight?.values?.rssi
            }
        }
        val op = FlightOperation(listOf(processor), emptyList()) { now }
        op.openFlight(Flight.Kind.LIVE)
        op.submit(Sample.Telemetry(TelemetryFrame(rssi = 77)))
        assertEquals(77, seen)
    }

    @Test
    fun withoutAFlightTelemetryIsIgnoredButTheEnvironmentIsKept() {
        val op = FlightOperation(emptyList(), emptyList()) { now }
        op.submit(Sample.Telemetry(TelemetryFrame(rssi = 77)))
        op.submit(Sample.PilotPosition(crazydude.com.telemetry.maps.Position(54.0, 24.0)))
        op.read { flight, environment ->
            assertNull(flight)
            assertEquals(54.0, environment.pilot!!.lat, 0.0)
        }
    }

    @Test
    fun eventsAreDeliveredAfterThePassOutsideTheLock() {
        val log = ArrayList<String>()
        val op = FlightOperation(emptyList(), listOf(Recorder(log, "m", Event.Connected))) { now }
        var lockFree = false
        op.subscribeEvents(direct) {
            log.add("event:$it")
            // another thread must get the lock now: it would block if the pass still held it
            val other = Thread { op.read { _, _ -> lockFree = true } }
            other.start()
            other.join(2000)
        }
        op.submit(Sample.Heartbeat(now))
        assertEquals(listOf("m:Heartbeat", "event:${Event.Connected}"), log)
        assertTrue(lockFree)
    }

    @Test
    fun eventsGoToEverySubscriberOnItsExecutor() {
        val op = FlightOperation(emptyList(), listOf(Recorder(ArrayList(), "m", Event.LinkLost))) { now }
        val queued = ArrayList<Runnable>()
        val a = ArrayList<Event>()
        val b = ArrayList<Event>()
        op.subscribeEvents(Executor { queued.add(it) }) { a.add(it) }
        val subscription = op.subscribeEvents(direct) { b.add(it) }
        op.submit(Sample.Heartbeat(now))
        assertTrue(a.isEmpty())  // not yet: its executor has not run
        queued.forEach { it.run() }
        assertEquals(listOf<Event>(Event.LinkLost), a)
        assertEquals(listOf<Event>(Event.LinkLost), b)
        subscription.close()
        op.submit(Sample.Heartbeat(now))
        assertEquals(1, b.size)
    }

    @Test
    fun monitorsStayQuietWhileRebuilding() {
        val log = ArrayList<String>()
        val op = FlightOperation(listOf(Recorder(log, "p")), listOf(Recorder(log, "m", Event.Connected))) { now }
        val events = ArrayList<Event>()
        op.subscribeEvents(direct) { events.add(it) }
        op.setRebuilding(true)
        op.submit(Sample.Heartbeat(now))
        op.setRebuilding(false)
        op.submit(Sample.Heartbeat(now))
        assertEquals(listOf("p:Heartbeat", "p:Heartbeat", "m:Heartbeat"), log)
        assertEquals(1, events.size)
    }

    @Test
    fun openingAndClosingAFlightTellsProcessorsAndMonitors() {
        val p = Recorder(ArrayList(), "p")
        val m = Recorder(ArrayList(), "m")
        val op = FlightOperation(listOf(p), listOf(m)) { now }
        op.openFlight(Flight.Kind.REPLAY)
        assertEquals(Flight.Kind.REPLAY, op.read { flight, _ -> flight?.kind })
        op.closeFlight()
        assertNull(op.read { flight, _ -> flight })
        assertEquals(2, p.changed)
        assertEquals(2, m.changed)
    }

    @Test
    fun restartKeepsTheFlightButForgetsItsValues() {
        val op = FlightOperation(emptyList(), emptyList()) { now }
        op.openFlight(Flight.Kind.REPLAY)
        op.submit(Sample.Telemetry(TelemetryFrame(rssi = 50)))
        op.submit(Sample.Restart)
        op.read { flight, _ ->
            assertEquals(Flight.Kind.REPLAY, flight!!.kind)
            assertNull(flight.values.rssi)
            assertEquals(1, flight.trackResets)
        }
    }

    @Test
    fun linkProtocolTerrainTrafficAndPlansAreApplied() {
        val op = FlightOperation(emptyList(), emptyList()) { now }
        op.openFlight(Flight.Kind.LIVE)
        op.submit(Sample.Link(LinkStatus.CONNECTED))
        op.submit(Sample.Protocol("CRSF"))
        op.submit(Sample.Traffic(listOf(TestData.aircraft(1))))
        op.read { flight, environment ->
            assertEquals(LinkStatus.CONNECTED, flight!!.link)
            assertEquals("CRSF", flight.protocol)
            assertEquals(1, environment.traffic.size)
        }
    }

    @Test
    fun concurrentSubmitsAreSerialised() {
        var inside = 0
        var overlaps = 0
        val processor = object : FlightOperation.Processor {
            override fun process(pass: FlightOperation.Pass) {
                inside++
                if (inside > 1) overlaps++
                Thread.yield()
                inside--
            }
        }
        val op = FlightOperation(listOf(processor), emptyList()) { now }
        val threads = (1..4).map { Thread { repeat(2000) { op.submit(Sample.Heartbeat(0)) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(0, overlaps)
    }
}
