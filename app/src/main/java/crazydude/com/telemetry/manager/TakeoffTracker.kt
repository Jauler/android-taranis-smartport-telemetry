package crazydude.com.telemetry.manager

import crazydude.com.telemetry.maps.Position

/**
 * Where the aircraft took off, which is where an altitude relative to the takeoff point is
 * measured from.
 *
 *  - While the aircraft is disarmed the takeoff point follows it, and it stays where the
 *    aircraft was armed.
 *  - If the armed state is not known, the first position received is taken.
 *  - [setTakeoffHere] pins it to the current position until the next [reset].
 *
 * Plain state, no threads: the caller serializes the calls.
 */
class TakeoffTracker {

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

    fun onAircraftPosition(position: Position) {
        aircraft = position
        if (locked) return
        if (armed == false || takeoff == null) {
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
}
