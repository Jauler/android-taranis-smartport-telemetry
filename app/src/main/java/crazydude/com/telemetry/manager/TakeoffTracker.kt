package crazydude.com.telemetry.manager

import crazydude.com.telemetry.maps.Position

/**
 * Where the aircraft took off, which is where an altitude relative to the takeoff point is
 * measured from.
 *
 *  - While the aircraft is disarmed the takeoff point follows it, and it stays where the
 *    aircraft was armed.
 *  - If the armed state is not known, the first position seen on the ground is taken.
 *  - [setTakeoffHere] pins it to the current position until the next [reset].
 *
 * "On the ground": with an altitude relative to the takeoff point, a position is only taken
 * while that altitude is close to zero. A takeoff point picked up in the air (after the
 * application or the connection was started in flight) would shift every height above ground
 * by the height of the aircraft at that moment; no takeoff point at all is better than that.
 *
 * Plain state, no threads: the caller serializes the calls.
 */
class TakeoffTracker {

    companion object {
        /** An altitude relative to the takeoff point within this many meters of zero is on the ground. */
        const val GROUND_ALTITUDE = 5f
    }

    var takeoff: Position? = null
        private set
    var aircraft: Position? = null
        private set
    var altitude = Float.NaN
        private set

    /** Set by [setTakeoffHere]. */
    var locked = false
        private set

    /** null while the armed state is not known. */
    private var armed: Boolean? = null

    /**
     * @param altitudeRelative true if the altitude is relative to the takeoff point, false if it
     * is above sea level (then it tells nothing about being on the ground)
     */
    fun onAircraftPosition(position: Position, altitudeRelative: Boolean) {
        aircraft = position
        if (locked) return
        if (armed == false || (takeoff == null && onGround(altitudeRelative))) {
            takeoff = position
        }
    }

    fun onAltitude(altitude: Float) {
        this.altitude = altitude
    }

    fun onArmedState(armed: Boolean) {
        this.armed = armed
        if (!armed && !locked) {
            aircraft?.let { takeoff = it }
        }
    }

    /** Pins the takeoff point to the current aircraft position; false if that is not known. */
    fun setTakeoffHere(): Boolean {
        val position = aircraft ?: return false
        takeoff = position
        locked = true
        return true
    }

    /** Forgets everything: a new flight session starts. */
    fun reset() {
        takeoff = null
        aircraft = null
        altitude = Float.NaN
        locked = false
        armed = null
    }

    private fun onGround(altitudeRelative: Boolean): Boolean {
        return !altitudeRelative || (!altitude.isNaN() && Math.abs(altitude) <= GROUND_ALTITUDE)
    }
}
