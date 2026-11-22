package crazydude.com.telemetry.manager

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.terrain.TerrainLoader
import crazydude.com.telemetry.terrain.TerrainModel

/**
 * Keeps the terrain related readouts up to date while the aircraft moves:
 *  - elevation above sea level of the ground right below the aircraft,
 *  - height above the ground right below the aircraft,
 *  - the altitude at which the aircraft reaches the legal height above ground limit.
 *
 * Inputs are fed from the UI thread, the terrain lookups run on a worker thread and the
 * resulting [State] is delivered back on the UI thread.
 *
 * Altitudes reported by the flight controller are normally relative to the takeoff point, so
 * the monitor tracks that point itself: it follows the aircraft while it is disarmed and is
 * frozen once it is armed. If the armed state is not known, the first position received is
 * used.
 *
 * Some setups (e.g. Betaflight over CRSF) report the altitude above sea level instead. Unless
 * the user has picked one of the two, this is detected from the first altitude of the flight:
 * an aircraft sitting at the takeoff point reads about zero in the first case and about the
 * elevation of the terrain in the second.
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
        val aboveLimit: Boolean,
        /** Elevation above sea level of the ground below the aircraft, NaN if unknown. */
        val groundElevation: Float
    )

    companion object {
        private const val TAG = "Terrain"
        private const val MIN_UPDATE_INTERVAL_MS = 400L
        const val REFERENCE_AUTO = "auto"
        const val REFERENCE_TAKEOFF = "takeoff"
        const val REFERENCE_MSL = "msl"

        private const val AUTO_UNDECIDED = 0
        private const val AUTO_TAKEOFF = 1
        private const val AUTO_MSL = 2
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

    // Automatic altitude reference detection; only touched by the worker thread
    private var autoReference = AUTO_UNDECIDED
    private var autoReferenceGeneration = -1

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
        aircraft = null
        altitude = Float.NaN
        takeoff = null
        takeoffLocked = false
        disarmed = false
        generation++
        if (model != null) {
            listener.onTerrainState(State(Float.NaN, Float.NaN, false, Float.NaN))
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
        thread?.quitSafely()
        thread = null
        worker = null
    }

    private class Input(
        val generation: Int,
        val aircraft: Position,
        val altitude: Float,
        val takeoff: Position?,
        val altitudeReference: String,
        val heightLimit: Float
    )

    private fun snapshot(): Input? {
        val position = aircraft ?: return null
        return Input(
            generation,
            position,
            altitude,
            takeoff,
            preferenceManager.getTerrainAltitudeReference(),
            preferenceManager.getTerrainHeightLimit()
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
        mainHandler.post {
            if (input.generation == generation) {
                listener.onTerrainState(state)
            }
        }
    }

    /**
     * Guesses whether the altitude sensor reports altitude above sea level. The first altitude
     * of a flight decides; the guess is dropped if it later puts the aircraft deep underground.
     */
    private fun isAltitudeAboveSeaLevel(input: Input, takeoffGround: Float, ground: Float): Boolean {
        if (autoReferenceGeneration != input.generation) {
            autoReferenceGeneration = input.generation
            autoReference = AUTO_UNDECIDED
        }
        if (autoReference == AUTO_UNDECIDED) {
            if (input.altitude.isNaN() || takeoffGround.isNaN()) return false
            autoReference = if (input.altitude > Math.max(30f, takeoffGround / 2)) AUTO_MSL else AUTO_TAKEOFF
        }
        if (autoReference == AUTO_MSL && !ground.isNaN() && input.altitude - ground < -20f) {
            autoReference = AUTO_TAKEOFF
        }
        return autoReference == AUTO_MSL
    }

    private fun evaluate(terrain: TerrainModel, input: Input): State {
        val ground = terrain.groundAt(input.aircraft.lat, input.aircraft.lon)

        val takeoffGround = input.takeoff?.let { terrain.groundAt(it.lat, it.lon) } ?: Float.NaN
        val relativeToTakeoff = when (input.altitudeReference) {
            REFERENCE_TAKEOFF -> true
            REFERENCE_MSL -> false
            else -> !isAltitudeAboveSeaLevel(input, takeoffGround, ground)
        }
        // Elevation which the altitude sensor reads as zero
        val reference = if (relativeToTakeoff) takeoffGround else 0f

        val heightAboveGround = input.altitude + reference - ground
        val altitudeLimit = input.heightLimit + ground - reference

        return State(
            heightAboveGround,
            altitudeLimit,
            !heightAboveGround.isNaN() && heightAboveGround > input.heightLimit,
            ground
        )
    }
}
