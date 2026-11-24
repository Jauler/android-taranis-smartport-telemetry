package crazydude.com.telemetry.operation

import crazydude.com.telemetry.manager.FlightPlanManager
import crazydude.com.telemetry.manager.TerrainMonitor
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.protocol.decoder.DataDecoder

/**
 * What Ingestion hands to the [FlightOperation]: typed, and whole. A telemetry frame arrives as
 * one sample with all of its values, so that nothing sees a new latitude with an old longitude.
 */
sealed class Sample {

    /** The values of one telemetry frame (or of one replay step); null: not in this frame. */
    data class Telemetry(val frame: TelemetryFrame) : Sample()

    /** Positions of a replay step, in order; [replace]: they start the track anew. */
    data class Track(val positions: List<Position>, val replace: Boolean) : Sample()

    /** The decoder started over: a replay is played again from its start. */
    object Restart : Sample()

    /** The state of the link, as Ingestion sees it. */
    data class Link(val status: LinkStatus) : Sample()

    /** The protocol found in the link's bytes. */
    data class Protocol(val name: String) : Sample()

    /** Aircraft around the pilot (FlightRadar24). */
    data class Traffic(val aircraft: List<Aircraft>) : Sample()

    /** Where the pilot (the phone) is; null: not known. */
    data class PilotPosition(val position: Position?) : Sample()

    /** Flight plans loaded from the configuration. */
    data class FlightPlans(val plans: List<FlightPlanManager.FlightPlan>) : Sample()

    /** The terrain readouts calculated for the flight. */
    data class Terrain(val state: TerrainMonitor.State) : Sample()

    /** Once a second, so that monitors notice what does not arrive. */
    data class Heartbeat(val nowMs: Long) : Sample()
}

enum class LinkStatus {
    /** A connection was made. */
    CONNECTED,
    /** A connection could not be made. */
    FAILED,
    /** The link was lost (not by the user). */
    LOST,
    /** The user disconnected. */
    DISCONNECTED
}

/** Flight mode as the telemetry reports it. */
data class FlyMode(
    val armed: Boolean,
    val heading: Boolean,
    val mode1: DataDecoder.Companion.FlyMode?,
    val mode2: DataDecoder.Companion.FlyMode?
)

/** The values of one frame. Every value is optional: a frame carries only some of them. */
data class TelemetryFrame(
    val rssi: Int? = null,
    val upLq: Int? = null,
    val dnLq: Int? = null,
    val elrsMode: Int? = null,
    val upSnr: Int? = null,
    val dnSnr: Int? = null,
    val antenna: Int? = null,
    val power: Int? = null,
    val rssiDbm1: Int? = null,
    val rssiDbm2: Int? = null,
    val rssiDbmd: Int? = null,
    val voltage: Float? = null,
    val cellVoltage: Float? = null,
    /** Battery or cell voltage, which one depends on the flight controller's settings. */
    val vbatOrCell: Float? = null,
    val current: Float? = null,
    val fuel: Int? = null,
    val speed: Float? = null,
    val airSpeed: Float? = null,
    val vSpeed: Float? = null,
    val altitude: Float? = null,
    val gpsAltitude: Float? = null,
    val distance: Int? = null,
    val throttle: Int? = null,
    val satellites: Int? = null,
    val gpsFix: Boolean? = null,
    val position: Position? = null,
    val heading: Float? = null,
    val roll: Float? = null,
    val pitch: Float? = null,
    val rcChannels: IntArray? = null,
    val statusText: String? = null,
    val flyMode: FlyMode? = null,
    /** Bytes received for this frame (telemetry rate). */
    val bytes: Int = 0
)

/** An aircraft around the pilot, as FlightRadar24 reports it. */
data class Aircraft(
    val flightId: Int,
    val lat: Float,
    val lon: Float,
    val altMeters: Int,
    val speedKmh: Int,
    val track: Int,
    val callsign: String,
    val flightNumber: String,
    val registration: String,
    val aircraftType: String,
    val route: String,
    val onGround: Boolean,
    val vspeedMs: Float
) {
    val displayName: String
        get() = callsign.ifEmpty { flightNumber.ifEmpty { registration.ifEmpty { "Unknown" } } }
}
