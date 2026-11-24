package crazydude.com.telemetry.operation

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * The operation the app follows, for the service's whole life: the world ([Environment]), the
 * current flight ([Flight], at most one at a time, live or replay), and the rules about both.
 *
 * Every sample is applied, then every processor and every monitor runs on it, in one pass under
 * one lock: samples come from several threads (link reader, FlightRadar24, phone GPS, replay).
 * Processors derive values; monitors raise events and commands, which are handed out after the
 * pass, on the subscriber's own executor, never inside the lock.
 */
class FlightOperation(
    private val processors: List<Processor>,
    private val monitors: List<Monitor>,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /** What processors and monitors see and use during a pass. */
    inner class Pass(val sample: Sample, val nowMs: Long) {
        val environment: Environment get() = this@FlightOperation.environment
        val flight: Flight? get() = this@FlightOperation.flight

        fun emit(event: Event) {
            pendingEvents.add(event)
        }

        fun command(command: Command) {
            pendingCommands.add(command)
        }
    }

    interface Processor {
        /** Derives values from the sample; returns at once for samples it ignores. */
        fun process(pass: FlightOperation.Pass)
        /** A flight was opened or dropped: forget what was derived for the old one. */
        fun onFlightChanged() {}
    }

    interface Monitor {
        /** Raises events (edge-triggered: never the same one again for the same cause). */
        fun check(pass: FlightOperation.Pass)
        fun onFlightChanged() {}
    }

    private class Subscriber<T>(val executor: Executor, val listener: (T) -> Unit)

    private val lock = Any()
    private val environment = Environment()
    private var flight: Flight? = null
    private var rebuilding = false
    private val pendingEvents = ArrayList<Event>()
    private val pendingCommands = ArrayList<Command>()
    private val eventSubscribers = CopyOnWriteArrayList<Subscriber<Event>>()
    private val commandSubscribers = CopyOnWriteArrayList<Subscriber<Command>>()

    /** Starts a new flight; the previous one, if any, ends. */
    fun openFlight(kind: Flight.Kind) {
        synchronized(lock) {
            flight = Flight(kind, clock())
            processors.forEach { it.onFlightChanged() }
            monitors.forEach { it.onFlightChanged() }
        }
    }

    /** Ends the flight (manual disconnect, replay closed). */
    fun closeFlight() {
        synchronized(lock) {
            flight = null
            processors.forEach { it.onFlightChanged() }
            monitors.forEach { it.onFlightChanged() }
        }
    }

    /**
     * A replay is rebuilt from its start (seek back): samples go through processors as usual, but
     * monitors stay quiet, so that nothing is announced twice.
     */
    fun setRebuilding(rebuilding: Boolean) {
        synchronized(lock) { this.rebuilding = rebuilding }
    }

    fun submit(sample: Sample) {
        val events: List<Event>
        val commands: List<Command>
        synchronized(lock) {
            val pass = Pass(sample, clock())
            apply(sample)
            processors.forEach { it.process(pass) }
            if (!rebuilding) monitors.forEach { it.check(pass) }
            events = ArrayList(pendingEvents)
            commands = ArrayList(pendingCommands)
            pendingEvents.clear()
            pendingCommands.clear()
        }
        events.forEach { event -> eventSubscribers.forEach { s -> s.executor.execute { s.listener(event) } } }
        commands.forEach { command -> commandSubscribers.forEach { s -> s.executor.execute { s.listener(command) } } }
    }

    /** The plain data: written here, everything derived is up to the processors. */
    private fun apply(sample: Sample) {
        val flight = flight
        when (sample) {
            is Sample.Telemetry -> flight?.values?.merge(sample.frame)
            is Sample.Restart -> flight?.restart()
            is Sample.Link -> flight?.link = sample.status
            is Sample.Protocol -> flight?.protocol = sample.name
            is Sample.Terrain -> flight?.terrain = sample.state
            is Sample.Traffic -> environment.traffic = sample.aircraft
            is Sample.PilotPosition -> environment.pilot = sample.position
            is Sample.FlightPlans -> environment.flightPlans = sample.plans
            is Sample.Track, is Sample.Heartbeat -> {}
        }
    }

    /** Reads the flight and the environment consistently. */
    fun <T> read(block: (Flight?, Environment) -> T): T {
        synchronized(lock) { return block(flight, environment) }
    }

    fun subscribeEvents(executor: Executor, listener: (Event) -> Unit): AutoCloseable {
        val subscriber = Subscriber(executor, listener)
        eventSubscribers.add(subscriber)
        return AutoCloseable { eventSubscribers.remove(subscriber) }
    }

    fun subscribeCommands(executor: Executor, listener: (Command) -> Unit): AutoCloseable {
        val subscriber = Subscriber(executor, listener)
        commandSubscribers.add(subscriber)
        return AutoCloseable { commandSubscribers.remove(subscriber) }
    }
}
