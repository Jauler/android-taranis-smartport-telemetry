package crazydude.com.telemetry.terrain

import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Terrain queries on top of a [TerrainPack]:
 *  - "dtm": bare ground elevation above sea level,
 *  - "obj": height of trees/buildings above the ground (highest point inside each cell).
 *
 * All methods take WGS84 degrees and return meters. Unknown values are NaN.
 */
class TerrainModel(private val pack: TerrainPack) {

    private val projection = pack.projection
    private val ground: RasterLayer = pack.layer(TerrainPack.LAYER_GROUND)
        ?: throw IllegalArgumentException("Terrain pack has no ground layer")
    private val objects: RasterLayer? = pack.layer(TerrainPack.LAYER_OBJECTS)

    val description: String
        get() = pack.description

    val hasObjects: Boolean
        get() = objects != null

    fun close() {
        pack.close()
    }

    /** Ground elevation above sea level, NaN outside of the covered area. */
    fun groundAt(lat: Double, lon: Double): Float {
        val p = DoubleArray(2)
        projection.forward(lat, lon, p)
        return ground.bilinear(p[0], p[1])
    }

    /** Elevation of the top of whatever stands at the point (ground + trees/buildings). */
    fun surfaceAt(lat: Double, lon: Double): Float {
        val p = DoubleArray(2)
        projection.forward(lat, lon, p)
        return surfaceAtGrid(p[0], p[1])
    }

    private fun surfaceAtGrid(x: Double, y: Double): Float {
        val g = ground.bilinear(x, y)
        if (g.isNaN()) return Float.NaN
        val o = objects?.nearest(x, y) ?: 0f
        return if (o.isNaN()) g else g + o
    }

    /**
     * Lowest altitude (above sea level) at which an aircraft at the given position still has an
     * unobstructed straight line to the receiving antenna.
     *
     * The line is walked from the antenna to the aircraft; for every obstacle on the way the
     * altitude needed to look over it is extrapolated to the aircraft position. Earth curvature
     * is taken into account with the usual 4/3 effective radius for radio waves.
     *
     * A radio link needs more than the bare line: most of its energy travels inside the first
     * Fresnel zone around it, and the link is as good as unobstructed only while 60 % of the
     * radius of that zone stays clear. With a [wavelength] every obstacle is raised by that
     * much: sqrt(wavelength * d1 * d2 / (d1 + d2)) for the distances to the two ends, so nothing
     * at the antenna and at the aircraft and the most half way.
     *
     * @param antennaHeight height of the antenna above the ground at its position
     * @param ignoreNear obstacles closer than this to the antenna are ignored (trees right
     * next to the pilot would otherwise shade the whole sky)
     * @param margin extra clearance required above every obstacle
     * @param wavelength wavelength of the radio link in meters, 0 for the bare straight line
     * @return null when the terrain under the antenna or the aircraft is unknown
     */
    fun minLineOfSightAltitude(
        antennaLat: Double, antennaLon: Double, antennaHeight: Double,
        aircraftLat: Double, aircraftLon: Double,
        ignoreNear: Double, margin: Double, wavelength: Double = 0.0
    ): LineOfSight? {
        val a = DoubleArray(2)
        val b = DoubleArray(2)
        projection.forward(antennaLat, antennaLon, a)
        projection.forward(aircraftLat, aircraftLon, b)

        val antennaGround = ground.bilinear(a[0], a[1])
        if (antennaGround.isNaN()) return null
        val surfaceBelow = surfaceAtGrid(b[0], b[1])
        if (surfaceBelow.isNaN()) return null

        val antennaAltitude = antennaGround + antennaHeight
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val distance = sqrt(dx * dx + dy * dy)

        // The aircraft has to be above whatever is right below it in any case
        var required = surfaceBelow + margin
        var obstacleX = b[0]
        var obstacleY = b[1]
        var obstacleDistance = distance
        var obstacleTop = surfaceBelow.toDouble()
        var complete = true

        val step = (objects?.cellSize ?: ground.cellSize) * 0.5
        val samples = min(ceil(distance / step).toInt(), MAX_SAMPLES)
        for (i in 1 until samples) {
            val t = i.toDouble() / samples
            val d = distance * t
            if (d < ignoreNear) continue
            val x = a[0] + dx * t
            val y = a[1] + dy * t
            val top = surfaceAtGrid(x, y)
            if (top.isNaN()) {
                complete = false
                continue
            }
            val bulge = d * (distance - d) / (2.0 * EFFECTIVE_EARTH_RADIUS)
            val fresnel = if (wavelength > 0.0) {
                FRESNEL_CLEARANCE * sqrt(wavelength * d * (distance - d) / distance)
            } else {
                0.0
            }
            val needed = antennaAltitude + (top + margin + fresnel + bulge - antennaAltitude) / t
            if (needed > required) {
                required = needed
                obstacleX = x
                obstacleY = y
                obstacleDistance = d
                obstacleTop = top.toDouble()
            }
        }

        val geo = DoubleArray(2)
        projection.inverse(obstacleX, obstacleY, geo)
        return LineOfSight(required, distance, geo[0], geo[1], obstacleDistance, obstacleTop, complete)
    }

    class LineOfSight(
        /** Lowest altitude above sea level with a free line to the antenna. */
        val minAltitude: Double,
        /** Ground distance between antenna and aircraft. */
        val distance: Double,
        /** Position of the obstacle which defines [minAltitude]. */
        val obstacleLat: Double,
        val obstacleLon: Double,
        /** Distance from the antenna to that obstacle. */
        val obstacleDistance: Double,
        /** Elevation of the top of that obstacle above sea level. */
        val obstacleTop: Double,
        /** False when part of the line crosses an area without terrain data. */
        val complete: Boolean
    )

    companion object {
        /** 4/3 of the mean Earth radius: standard atmosphere bends radio waves slightly. */
        const val EFFECTIVE_EARTH_RADIUS = 6371000.0 * 4.0 / 3.0

        /** Part of the radius of the first Fresnel zone which has to stay clear. */
        const val FRESNEL_CLEARANCE = 0.6

        /** Wavelength in meters of a radio frequency in MHz. */
        fun wavelengthOf(megahertz: Double): Double {
            return if (megahertz > 0.0) 299.792458 / megahertz else 0.0
        }

        private const val MAX_SAMPLES = 40000
    }
}
