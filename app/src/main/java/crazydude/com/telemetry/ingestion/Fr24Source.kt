package crazydude.com.telemetry.ingestion

import android.util.Log
import crazydude.com.telemetry.api.Fr24Client
import crazydude.com.telemetry.manager.PreferenceManager
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Aircraft
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.proto.fr24.Flight
import crazydude.com.telemetry.proto.fr24.LocationBoundaries
import crazydude.com.telemetry.utils.GeoUtils
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Fetches the aircraft around the pilot from FlightRadar24, every poll interval, and hands them
 * over as [Sample.Traffic]. Runs from app start, with or without a link or a screen.
 */
class Fr24Source(
    private val preferenceManager: PreferenceManager,
    private val pilotPosition: () -> Position?,
    private val sink: (Sample) -> Unit,
    private val client: Fr24Client = Fr24Client()
) {

    private var executor: ScheduledExecutorService? = null
    private var next: ScheduledFuture<*>? = null

    val running: Boolean
        get() = executor != null

    fun start() {
        if (executor != null) return
        executor = Executors.newSingleThreadScheduledExecutor()
        schedule(preferenceManager.getFr24PollIntervalSec() * 1000L)
    }

    fun stop() {
        next?.cancel(false)
        executor?.shutdownNow()
        executor = null
        sink(Sample.Traffic(emptyList()))
    }

    private fun schedule(delayMs: Long) {
        val executor = executor ?: return
        next = executor.schedule({
            fetch()
            schedule(preferenceManager.getFr24PollIntervalSec() * 1000L)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun fetch() {
        val pilot = pilotPosition()
        if (pilot == null) {
            Log.w(TAG, "Skipping fetch: phone location not available")
            return
        }
        try {
            val radiusMeters = preferenceManager.getFr24RadiusKm() * 1000.0
            val ceiling = preferenceManager.getFr24DisplayAltCeilingM()
            val flights = client.fetchLiveFeed(boundingBox(pilot, radiusMeters), ceiling)
            val aircraft = flights.mapNotNull { toAircraft(it, ceiling) }
            Log.d(TAG, "Fetched ${aircraft.size} aircraft (from ${flights.size} raw)")
            if (executor != null) sink(Sample.Traffic(aircraft))
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed", e)
        }
    }

    companion object {
        private const val TAG = "FR24"
        private const val FEET_TO_METERS = 0.3048
        private const val KNOTS_TO_KMH = 1.852
        private const val FPM_TO_MS = 0.00508

        /** null for aircraft on the ground or above [ceilingM]. */
        fun toAircraft(flight: Flight, ceilingM: Int): Aircraft? {
            if (flight.onGround) return null
            val altMeters = (flight.alt * FEET_TO_METERS).roundToInt()
            if (altMeters > ceilingM) return null
            val extra = flight.extraInfo
            return Aircraft(
                flightId = flight.flightid,
                lat = flight.lat,
                lon = flight.lon,
                altMeters = altMeters,
                speedKmh = (flight.speed * KNOTS_TO_KMH).roundToInt(),
                track = flight.track,
                callsign = flight.callsign,
                flightNumber = extra?.flight ?: "",
                registration = extra?.reg ?: "",
                aircraftType = extra?.type ?: "",
                route = buildString {
                    val r = extra?.route
                    if (r != null) {
                        if (r.from.isNotEmpty()) append(r.from)
                        if (r.to.isNotEmpty()) {
                            if (isNotEmpty()) append(" → ")
                            append(r.to)
                        }
                    }
                },
                onGround = flight.onGround,
                vspeedMs = (extra?.vspeed ?: 0) * FPM_TO_MS.toFloat()
            )
        }

        fun boundingBox(center: Position, radiusMeters: Double): LocationBoundaries {
            val north = GeoUtils.computeOffset(center.lat, center.lon, radiusMeters, 0.0)
            val south = GeoUtils.computeOffset(center.lat, center.lon, radiusMeters, 180.0)
            val east = GeoUtils.computeOffset(center.lat, center.lon, radiusMeters, 90.0)
            val west = GeoUtils.computeOffset(center.lat, center.lon, radiusMeters, 270.0)
            return LocationBoundaries.newBuilder()
                .setNorth(north.first.toFloat())
                .setSouth(south.first.toFloat())
                .setEast(east.second.toFloat())
                .setWest(west.second.toFloat())
                .build()
        }
    }
}
