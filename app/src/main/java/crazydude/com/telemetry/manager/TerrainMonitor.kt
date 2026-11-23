package crazydude.com.telemetry.manager

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import crazydude.com.telemetry.BuildConfig
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.terrain.TerrainLoader
import crazydude.com.telemetry.terrain.TerrainModel
import java.util.Locale

/**
 * Keeps the terrain related readouts up to date while the aircraft moves:
 *  - elevation above sea level of the ground right below the aircraft, and of the top of the
 *    trees or buildings standing there,
 *  - height above the ground right below the aircraft,
 *  - the altitude at which the aircraft reaches the legal height above ground limit,
 *  - the lowest altitude with a free line of sight to the receiving antenna.
 *
 * Inputs are fed from the UI thread, the terrain lookups run on a worker thread and the
 * resulting [State] is delivered back on the UI thread.
 *
 * Altitudes reported by the flight controller are normally relative to the takeoff point, so
 * the monitor tracks that point itself: it follows the aircraft while it is disarmed and is
 * frozen once it is armed. If the armed state is not known, the first position received is
 * used.
 *
 * Some setups (e.g. Betaflight over CRSF) report the altitude above sea level instead; the user
 * picks which one in the settings (height above the takeoff point by default).
 */
class TerrainMonitor(
    context: Context,
    private val preferenceManager: PreferenceManager,
    private val listener: Listener
) {

    interface Listener {
        fun onTerrainState(state: State)
    }

    class State(
        /** Height of the aircraft above the ground below it, NaN if unknown. */
        val heightAboveGround: Float,
        /** Altitude (as shown by the altitude sensor) of the height above ground limit, NaN if unknown. */
        val altitudeLimit: Float,
        /** Lowest altitude (as shown by the altitude sensor) with a free line to the antenna, NaN if unknown. */
        val minLosAltitude: Float,
        val aboveLimit: Boolean,
        val belowLos: Boolean,
        /** Where the obstacle defining [minLosAltitude] is, null if unknown. */
        val losObstacle: Position?,
        /**
         * Pieces of the line from the antenna (as given to [onAntennaPosition]) to the aircraft
         * which are obstructed now; empty unless [belowLos], and when the line had to be taken
         * from the takeoff point instead.
         */
        val losBlocked: List<Segment>,
        /** Elevation above sea level of the ground below the aircraft, NaN if unknown. */
        val groundElevation: Float,
        /** Elevation above sea level of the top of the trees or buildings below the aircraft, NaN if unknown. */
        val surfaceElevation: Float
    )

    /** A piece of a line on the map. */
    data class Segment(val from: Position, val to: Position)

    companion object {
        private const val TAG = "Terrain"
        /** Debug builds log every computed state under this tag, see [describe]. */
        private const val STATE_TAG = "TerrainState"
        private const val MIN_UPDATE_INTERVAL_MS = 400L
        const val REFERENCE_TAKEOFF = "takeoff"
        const val REFERENCE_MSL = "msl"
    }

    private val model: TerrainModel? = try {
        TerrainLoader.open(context)
    } catch (e: Exception) {
        Log.w(TAG, "Terrain data is not usable", e)
        null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var updateScheduled = false
    private var lastUpdateTime = 0L
    private var generation = 0

    private var aircraft: Position? = null
    private var altitude = Float.NaN
    private var takeoff: Position? = null
    private var takeoffLocked = false
    private var disarmed = false
    private var antenna: Position? = null

    val isAvailable: Boolean
        get() = model != null

    /** Description of the loaded terrain pack, null when there is none. */
    val description: String?
        get() = model?.description

    val takeoffPosition: Position?
        get() = takeoff

    init {
        if (model != null) {
            val t = HandlerThread("terrain")
            t.start()
            thread = t
            worker = Handler(t.looper)
        }
    }

    fun onAircraftPosition(lat: Double, lon: Double) {
        if (lat == 0.0 && lon == 0.0) return
        val position = Position(lat, lon)
        aircraft = position
        if (!takeoffLocked && (takeoff == null || disarmed)) {
            takeoff = position
        }
        requestUpdate()
    }

    fun onAltitude(altitude: Float) {
        this.altitude = altitude
        requestUpdate()
    }

    fun onArmedState(armed: Boolean) {
        disarmed = !armed
        if (disarmed && !takeoffLocked) {
            aircraft?.let { takeoff = it }
        }
    }

    /** Position of the receiving antenna (the phone); null if not known. */
    fun onAntennaPosition(position: Position?) {
        antenna = position
    }

    /** Declares the current aircraft position to be the takeoff point until the next [reset]. */
    fun setTakeoffHere(): Boolean {
        val position = aircraft ?: return false
        takeoff = position
        takeoffLocked = true
        requestUpdate()
        return true
    }

    /** Forgets everything known about the flight (new connection, replay restart). */
    fun reset() {
        if (BuildConfig.DEBUG) Log.d(STATE_TAG, "reset")
        aircraft = null
        altitude = Float.NaN
        takeoff = null
        takeoffLocked = false
        disarmed = false
        generation++
        if (model != null) {
            listener.onTerrainState(State(Float.NaN, Float.NaN, Float.NaN, false, false, null, emptyList(), Float.NaN, Float.NaN))
        }
    }

    /** Recomputes the state, e.g. after a change of settings. */
    fun requestUpdate() {
        val handler = worker ?: return
        if (updateScheduled) return
        updateScheduled = true
        val delay = lastUpdateTime + MIN_UPDATE_INTERVAL_MS - System.currentTimeMillis()
        mainHandler.postDelayed({
            updateScheduled = false
            lastUpdateTime = System.currentTimeMillis()
            val input = snapshot()
            if (input != null) {
                handler.post { compute(input) }
            }
        }, if (delay > 0L) delay else 0L)
    }

    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        generation++
        // Closed on the worker thread, after whatever it is still computing
        val terrain = model
        worker?.post {
            try {
                terrain?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Cannot close the terrain data", e)
            }
        }
        thread?.quitSafely()
        thread = null
        worker = null
    }

    private class Input(
        val generation: Int,
        val aircraft: Position,
        val altitude: Float,
        val takeoff: Position?,
        val antenna: Position?,
        val altitudeReference: String,
        val heightLimit: Float,
        val antennaHeight: Double,
        val ignoreNear: Double,
        val margin: Double,
        val wavelength: Double,
        val losWarnMinDistance: Float
    )

    private fun snapshot(): Input? {
        val position = aircraft ?: return null
        return Input(
            generation,
            position,
            altitude,
            takeoff,
            antenna,
            preferenceManager.getTerrainAltitudeReference(),
            preferenceManager.getTerrainHeightLimit(),
            preferenceManager.getTerrainAntennaHeight().toDouble(),
            preferenceManager.getTerrainLosIgnoreNear().toDouble(),
            preferenceManager.getTerrainLosMargin().toDouble(),
            TerrainModel.wavelengthOf(preferenceManager.getTerrainLosFrequency().toDouble()),
            preferenceManager.getTerrainLosWarnMinDistance()
        )
    }

    private fun compute(input: Input) {
        val terrain = model ?: return
        val state = try {
            evaluate(terrain, input)
        } catch (e: Exception) {
            Log.w(TAG, "Terrain evaluation failed", e)
            return
        }
        if (BuildConfig.DEBUG) Log.d(STATE_TAG, describe(input, state))
        mainHandler.post {
            if (input.generation == generation) {
                listener.onTerrainState(state)
            }
        }
    }

    private fun evaluate(terrain: TerrainModel, input: Input): State {
        val lat = input.aircraft.lat
        val lon = input.aircraft.lon
        val ground = terrain.groundAt(lat, lon)

        // Elevation which the altitude sensor reads as zero
        val reference = if (input.altitudeReference == REFERENCE_MSL) {
            0f
        } else {
            input.takeoff?.let { terrain.groundAt(it.lat, it.lon) } ?: Float.NaN
        }

        val heightAboveGround = input.altitude + reference - ground
        val altitudeLimit = input.heightLimit + ground - reference

        var minLosAltitude = Float.NaN
        var obstacle: Position? = null
        var blocked: List<Segment> = emptyList()
        val altitudeAboveSeaLevel = (input.altitude + reference).toDouble()  // NaN if either is unknown
        if (terrain.hasObjects) {
            // The antenna is where the phone is. Without a usable phone position (no fix, or a
            // position outside of the terrain data) it is assumed to be at the takeoff point.
            var los: TerrainModel.LineOfSight? = null
            var fromAntenna = false
            for (antenna in listOf(input.antenna, input.takeoff)) {
                if (antenna == null || los != null) continue
                los = terrain.minLineOfSightAltitude(
                    antenna.lat, antenna.lon, input.antennaHeight,
                    lat, lon, input.ignoreNear, input.margin, input.wavelength, altitudeAboveSeaLevel
                )
                fromAntenna = los != null && antenna === input.antenna
            }
            if (los != null) {
                minLosAltitude = (los.minAltitude - reference).toFloat()
                if (los.obstacleDistance < los.distance - 1.0) {
                    obstacle = Position(los.obstacleLat, los.obstacleLon)
                }
                // Shown on the map along the line from the antenna; a line from the takeoff
                // point instead would not be on anything drawn there
                if (fromAntenna) {
                    blocked = los.blocked.map {
                        Segment(Position(it.fromLat, it.fromLon), Position(it.toLat, it.toLon))
                    }
                }
            }
        }

        // Not below the line of sight close to home: on the ground before takeoff and after landing,
        // and while climbing out, trees and buildings around the pilot would trigger it all the time
        val home = input.takeoff ?: input.antenna
        val homeDistance = if (home != null) {
            val result = FloatArray(1)
            Location.distanceBetween(home.lat, home.lon, lat, lon, result)
            result[0]
        } else {
            Float.NaN
        }
        val belowLos = !minLosAltitude.isNaN() && !input.altitude.isNaN() && input.altitude < minLosAltitude &&
                !homeDistance.isNaN() && homeDistance >= input.losWarnMinDistance

        return State(
            heightAboveGround,
            altitudeLimit,
            minLosAltitude,
            !heightAboveGround.isNaN() && heightAboveGround > input.heightLimit,
            belowLos,
            obstacle,
            if (belowLos) blocked else emptyList(),
            ground,
            if (terrain.hasObjects) terrain.surfaceAt(lat, lon) else Float.NaN
        )
    }

    /**
     * One line with the inputs and the outputs of a computation, for comparing runs (debug
     * builds only): the same inputs have to give the same outputs.
     */
    private fun describe(input: Input, state: State): String {
        fun p(position: Position?) =
            if (position == null) "-" else String.format(Locale.US, "%.6f,%.6f", position.lat, position.lon)
        fun m(v: Float) = if (v.isNaN()) "-" else String.format(Locale.US, "%.2f", v)
        return "in aircraft=${p(input.aircraft)} alt=${m(input.altitude)} ref=${input.altitudeReference}" +
                " takeoff=${p(input.takeoff)} antenna=${p(input.antenna)}" +
                " | out agl=${m(state.heightAboveGround)} limit=${m(state.altitudeLimit)}" +
                " los=${m(state.minLosAltitude)} above=${state.aboveLimit} below=${state.belowLos}" +
                " obstacle=${p(state.losObstacle)} blocked=${state.losBlocked.size}" +
                " ground=${m(state.groundElevation)} surface=${m(state.surfaceElevation)}"
    }
}
