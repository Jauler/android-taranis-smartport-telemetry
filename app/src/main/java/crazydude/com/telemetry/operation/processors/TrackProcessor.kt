package crazydude.com.telemetry.operation.processors

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.utils.GeoUtils

/**
 * The aircraft's position, the track and the travelled distance. Every position with a GPS fix
 * extends the track; the distance is measured from the previous position, fix or not. A replay
 * flight's home is its first position with a fix.
 */
class TrackProcessor : FlightOperation.Processor {

    override fun process(pass: FlightOperation.Pass) {
        val flight = pass.flight ?: return
        when (val sample = pass.sample) {
            is Sample.Telemetry -> sample.frame.position?.let { add(flight, it) }
            is Sample.Track -> {
                if (sample.replace) {
                    flight.track.clear()
                    flight.trackResets++
                    flight.traveledDistance = 0.0
                    flight.values.position = null
                }
                if (flight.values.gpsFix && sample.positions.isNotEmpty()) {
                    if (flight.kind == Flight.Kind.REPLAY && flight.home == null) flight.home = sample.positions[0]
                    sample.positions.forEach { add(flight, it) }
                }
            }
            else -> return
        }
    }

    private fun add(flight: Flight, position: Position) {
        val previous = flight.values.position
        if (position == previous) return
        if (flight.values.gpsFix) {
            if (previous != null) {
                flight.traveledDistance += GeoUtils.computeDistanceBetween(previous.lat, previous.lon, position.lat, position.lon)
            }
            flight.track.add(position)
        }
        flight.values.position = position
    }
}
