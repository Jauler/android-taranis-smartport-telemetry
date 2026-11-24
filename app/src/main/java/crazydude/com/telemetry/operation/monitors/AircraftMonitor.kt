package crazydude.com.telemetry.operation.monitors

import crazydude.com.telemetry.operation.Event
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.utils.GeoUtils
import kotlin.math.atan2

/**
 * Warns of the closest aircraft within [warningDistanceM] of the drone and below
 * [warningCeilingM], on each traffic update (as often as FlightRadar24 is polled). Only for a
 * live flight: a replayed drone and today's traffic have nothing to do with each other.
 */
class AircraftMonitor(
    private val warningDistanceM: () -> Int,
    private val warningCeilingM: () -> Int
) : FlightOperation.Monitor {

    override fun check(pass: FlightOperation.Pass) {
        val sample = pass.sample as? Sample.Traffic ?: return
        val flight = pass.flight ?: return
        if (flight.kind != Flight.Kind.LIVE) return
        val drone = flight.values.position ?: return
        val distance = warningDistanceM()
        val ceiling = warningCeilingM()
        val closest = sample.aircraft
            .filter { !it.onGround && it.altMeters <= ceiling }
            .map { it to GeoUtils.computeDistanceBetween(drone.lat, drone.lon, it.lat.toDouble(), it.lon.toDouble()) }
            .filter { it.second <= distance }
            .minBy { it.second } ?: return
        val bearing = bearing(drone.lat, drone.lon, closest.first.lat.toDouble(), closest.first.lon.toDouble())
        pass.emit(Event.TrafficWarning(closest.first, closest.second, bearing))
    }

    companion object {
        /** Initial bearing from the first point to the second, degrees 0-360. */
        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLon = Math.toRadians(lon2 - lon1)
            val lat1Rad = Math.toRadians(lat1)
            val lat2Rad = Math.toRadians(lat2)
            val y = Math.sin(dLon) * Math.cos(lat2Rad)
            val x = Math.cos(lat1Rad) * Math.sin(lat2Rad) - Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }
}
