package crazydude.com.telemetry.ui

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.utils.GeoUtils

/**
 * What is known about the flight right now, as the telemetry delivers it. The map screen draws
 * it at a fixed rate ([snapshot]), instead of on every value received: telemetry comes much
 * faster than it is worth redrawing the screen (each RC channels frame, each link statistics
 * frame...).
 *
 * Written from the thread of the telemetry (live) or the main thread (replay), read on the
 * main thread: every access goes through one lock.
 */
class TelemetryState {

    /** null: no value received yet. */
    data class Values(
        var rssi: Int? = null,
        var upLq: Int? = null,
        var dnLq: Int? = null,
        var elrsMode: Int? = null,
        var upSnr: Int? = null,
        var dnSnr: Int? = null,
        var antenna: Int? = null,
        var power: Int? = null,
        var rssiDbm1: Int? = null,
        var rssiDbm2: Int? = null,
        var rssiDbmd: Int? = null,
        var voltage: Float? = null,
        var cellVoltage: Float? = null,
        var current: Float? = null,
        var fuel: Int? = null,
        var speed: Float? = null,
        var airSpeed: Float? = null,
        var vSpeed: Float? = null,
        var altitude: Float? = null,
        var gpsAltitude: Float? = null,
        var distance: Int? = null,
        var throttle: Int? = null,
        var satellites: Int? = null,
        var gpsFix: Boolean = false,
        var heading: Float? = null,
        var roll: Float? = null,
        var pitch: Float? = null,
        var rcChannels: IntArray? = null,
        var statusText: String? = null,
        var telemetryRate: Int? = null,
        /** null until the flight mode is received; then with [headingMode], [flyMode1], [flyMode2] */
        var armed: Boolean? = null,
        var headingMode: Boolean = false,
        var flyMode1: DataDecoder.Companion.FlyMode? = null,
        var flyMode2: DataDecoder.Companion.FlyMode? = null,
        /** The latest position, with or without a GPS fix. */
        var position: Position? = null,
        /** Along the track, meters. */
        var traveledDistance: Double = 0.0,
        /** "Connected", "Disconnected"...: shown until the flight mode is known. Kept by [restart]. */
        var connectionStatus: String = "Disconnected"
    )

    /**
     * @param newTrackPoints the points added to the track since the previous snapshot
     * @param trackResets counts the times the track has been started anew: when it changes, the
     * track drawn so far is to be cleared before [newTrackPoints] are added
     */
    class Snapshot(val values: Values, val newTrackPoints: List<Position>, val trackResets: Int)

    private val lock = Any()
    private var values = Values()
    private var newTrackPoints = ArrayList<Position>()
    private var trackResets = 0

    fun update(block: (Values) -> Unit) {
        synchronized(lock) { block(values) }
    }

    fun setRcChannels(channels: IntArray) {
        // the decoder keeps filling the same array
        val copy = channels.copyOf()
        synchronized(lock) { values.rcChannels = copy }
    }

    val gpsFix: Boolean
        get() = synchronized(lock) { values.gpsFix }

    val position: Position?
        get() = synchronized(lock) { values.position }

    val heading: Float?
        get() = synchronized(lock) { values.heading }

    /**
     * A new position of the aircraft. With a GPS fix it extends the track.
     * @return false if it is the same as the last one (nothing changes)
     */
    fun onPosition(position: Position): Boolean {
        synchronized(lock) {
            return addPosition(position)
        }
    }

    private fun addPosition(position: Position): Boolean {
        val last = values.position
        if (position == last) return false
        if (values.gpsFix) {
            if (last != null) {
                values.traveledDistance += GeoUtils.computeDistanceBetween(last.lat, last.lon, position.lat, position.lon)
            }
            newTrackPoints.add(position)
        }
        values.position = position
        return true
    }

    /**
     * Several positions at once (replay).
     * @param addToEnd false: they replace the track so far
     */
    fun onPositions(positions: List<Position>, addToEnd: Boolean) {
        synchronized(lock) {
            if (!addToEnd) clearTrack()
            if (!values.gpsFix) return
            positions.forEach { addPosition(it) }
        }
    }

    private fun clearTrack() {
        values.position = null
        values.traveledDistance = 0.0
        newTrackPoints = ArrayList()
        trackResets++
    }

    /** Everything received so far is forgotten: a new connection, or a replay played again. */
    fun restart() {
        synchronized(lock) {
            val status = values.connectionStatus
            values = Values(connectionStatus = status)
            clearTrack()
        }
    }

    /** A copy of the values, and the track points added since the previous snapshot. */
    fun snapshot(): Snapshot {
        synchronized(lock) {
            val points = newTrackPoints
            newTrackPoints = ArrayList()
            return Snapshot(values.copy(), points, trackResets)
        }
    }
}
