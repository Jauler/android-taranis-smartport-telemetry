package crazydude.com.telemetry.operation

import crazydude.com.telemetry.manager.TerrainMonitor
import crazydude.com.telemetry.maps.Position

/**
 * One flight: from connecting to a manual disconnect (or app kill), or from starting a replay to
 * closing it. A lost link does not end it. Plain data: only the [FlightOperation] changes it,
 * under its lock.
 */
class Flight(val kind: Kind, val startedAtMs: Long) {

    enum class Kind { LIVE, REPLAY }

    /** The latest value of everything the telemetry reports; null: none received yet. */
    var values = FlightValues()

    /** Every position with a GPS fix, in order. */
    val track = ArrayList<Position>()
    /** Counts the times the track started anew (replay played again). */
    var trackResets = 0
    /** Along the track, meters. */
    var traveledDistance = 0.0

    /** Replay: where the flight started (its first position with a fix), the antenna's place. */
    var home: Position? = null

    var terrain: TerrainMonitor.State? = null

    var link: LinkStatus? = null
    var protocol: String? = null

    /** When each sensor's value last arrived ([Sensor]), ms; Long.MIN_VALUE: never. */
    val lastArrival = LongArray(Sensor.values().size) { Long.MIN_VALUE }
    /** Sensors whose value has not arrived for a while. */
    var stale: Set<Sensor> = emptySet()
    /** Telemetry bytes a second. */
    var telemetryRate = 0

    /** Everything the telemetry delivered is forgotten (a replay played again); the kind stays. */
    fun restart() {
        values = FlightValues()
        track.clear()
        trackResets++
        traveledDistance = 0.0
        home = null
        terrain = null
        lastArrival.fill(Long.MIN_VALUE)
        stale = emptySet()
    }

    fun snapshot(knownResets: Int, knownPoints: Int): FlightSnapshot {
        val sameTrack = knownResets == trackResets && knownPoints <= track.size
        val from = if (sameTrack) knownPoints else 0
        return FlightSnapshot(
            kind, values.copy(), trackResets, track.size, ArrayList(track.subList(from, track.size)), from,
            traveledDistance, home, terrain, link, protocol, stale, telemetryRate
        )
    }
}

/** A copy of a [Flight] for the screen; [newPoints] = the track from [newPointsFrom] on. */
class FlightSnapshot(
    val kind: Flight.Kind,
    val values: FlightValues,
    val trackResets: Int,
    val trackSize: Int,
    val newPoints: List<Position>,
    val newPointsFrom: Int,
    val traveledDistance: Double,
    val home: Position?,
    val terrain: TerrainMonitor.State?,
    val link: LinkStatus?,
    val protocol: String?,
    val stale: Set<Sensor>,
    val telemetryRate: Int
)

/** Readouts whose values can grow old. */
enum class Sensor {
    GPS, DISTANCE, ALTITUDE, GPS_ALTITUDE, RSSI, UP_LQ, DN_LQ, ELRS_MODE, VOLTAGE, CELL_VOLTAGE, CURRENT,
    SPEED, AIRSPEED, VSPEED, THROTTLE, FUEL, RC_CHANNELS, STATUSTEXT, DN_SNR, UP_SNR, ANT, POWER,
    RSSI_DBM_1, RSSI_DBM_2, RSSI_DBM_D
}

/** The latest telemetry values; null: not received yet. */
data class FlightValues(
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
    var vbatOrCell: Float? = null,
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
    var position: Position? = null,
    var heading: Float? = null,
    var roll: Float? = null,
    var pitch: Float? = null,
    var rcChannels: IntArray? = null,
    var statusText: String? = null,
    var flyMode: FlyMode? = null
) {
    /** Takes every value the frame carries. */
    fun merge(f: TelemetryFrame) {
        f.rssi?.let { rssi = it }
        f.upLq?.let { upLq = it }
        f.dnLq?.let { dnLq = it }
        f.elrsMode?.let { elrsMode = it }
        f.upSnr?.let { upSnr = it }
        f.dnSnr?.let { dnSnr = it }
        f.antenna?.let { antenna = it }
        f.power?.let { power = it }
        f.rssiDbm1?.let { rssiDbm1 = it }
        f.rssiDbm2?.let { rssiDbm2 = it }
        f.rssiDbmd?.let { rssiDbmd = it }
        f.voltage?.let { voltage = it }
        f.cellVoltage?.let { cellVoltage = it }
        f.vbatOrCell?.let { vbatOrCell = it }
        f.current?.let { current = it }
        f.fuel?.let { fuel = it }
        f.speed?.let { speed = it }
        f.airSpeed?.let { airSpeed = it }
        f.vSpeed?.let { vSpeed = it }
        f.altitude?.let { altitude = it }
        f.gpsAltitude?.let { gpsAltitude = it }
        f.distance?.let { distance = it }
        f.throttle?.let { throttle = it }
        f.satellites?.let { satellites = it }
        f.gpsFix?.let { gpsFix = it }
        f.heading?.let { heading = it }
        f.roll?.let { roll = it }
        f.pitch?.let { pitch = it }
        f.rcChannels?.let { rcChannels = it.copyOf() }
        f.statusText?.let { statusText = it }
        f.flyMode?.let { flyMode = it }
        // the position is taken by the track processor, which needs the previous one
    }
}
