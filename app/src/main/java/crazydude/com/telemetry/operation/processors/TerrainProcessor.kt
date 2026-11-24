package crazydude.com.telemetry.operation.processors

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.Sample
import java.util.concurrent.Executor

/**
 * Feeds the terrain calculation (height above ground, line of sight): the aircraft's position,
 * altitude and armed state, and the antenna. The antenna is the pilot's position, or for a replay
 * the flight's first position with a fix (the pilot's position is not in the log).
 *
 * The calculation is heavy and runs on its own worker; it is told about changes on [executor]
 * (the main thread), in order, and sends its results back as [Sample.Terrain].
 */
class TerrainProcessor(private val target: Target, private val executor: Executor) : FlightOperation.Processor {

    /** What the terrain calculation needs to hear about ([crazydude.com.telemetry.manager.TerrainMonitor]). */
    interface Target {
        fun onAircraftPosition(lat: Double, lon: Double)
        fun onAltitude(altitude: Float)
        fun onArmedState(armed: Boolean)
        fun onAntennaPosition(position: Position?)
        fun reset()
    }

    private var antenna: Position? = null
    private var antennaSent = false

    override fun onFlightChanged() {
        antennaSent = false
        executor.execute { target.reset() }
    }

    override fun process(pass: FlightOperation.Pass) {
        val flight = pass.flight ?: return
        when (val sample = pass.sample) {
            is Sample.Telemetry -> {
                val f = sample.frame
                if (f.position == null && f.altitude == null && f.flyMode == null) return
                f.position?.let { p -> executor.execute { target.onAircraftPosition(p.lat, p.lon) } }
                f.altitude?.let { a -> executor.execute { target.onAltitude(a) } }
                f.flyMode?.let { m -> executor.execute { target.onArmedState(m.armed) } }
            }
            is Sample.Track -> {
                if (!flight.values.gpsFix || sample.positions.isEmpty()) return
                // the first point of a replayed track is where the flight started
                val first = sample.positions.first()
                val last = sample.positions.last()
                executor.execute {
                    target.onAircraftPosition(first.lat, first.lon)
                    target.onAircraftPosition(last.lat, last.lon)
                }
            }
            is Sample.Restart -> {
                antennaSent = false
                executor.execute { target.reset() }
            }
            is Sample.PilotPosition -> {}
            else -> return
        }
        val newAntenna = if (flight.kind == Flight.Kind.REPLAY) flight.home else pass.environment.pilot
        if (!antennaSent || newAntenna != antenna) {
            antenna = newAntenna
            antennaSent = true
            executor.execute { target.onAntennaPosition(newAntenna) }
        }
    }
}
