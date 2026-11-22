package crazydude.com.telemetry.terrain

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Terrain queries on top of a [TerrainPack]:
 *  - "dtm": bare ground elevation above sea level,
 *  - "obj": height of trees/buildings above the ground (highest point inside each cell),
 *  - "pop": populated area classes: 0 none, [POPULATED_CLASS_ISOLATED] isolated buildings
 *    (farmsteads), [POPULATED_CLASS_SETTLEMENT] villages and towns.
 *
 * All methods take WGS84 degrees and return meters. Unknown values are NaN.
 */
class TerrainModel(private val pack: TerrainPack) {

    private val projection = pack.projection
    private val ground: RasterLayer = pack.layer(TerrainPack.LAYER_GROUND)
        ?: throw IllegalArgumentException("Terrain pack has no ground layer")
    private val objects: RasterLayer? = pack.layer(TerrainPack.LAYER_OBJECTS)
    private val populated: RasterLayer? = pack.layer(TerrainPack.LAYER_POPULATED)

    val description: String
        get() = pack.description

    val hasObjects: Boolean
        get() = objects != null

    val hasPopulatedAreas: Boolean
        get() = populated != null

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
     * Populated area check around a point. Only areas of at least [minClass] count.
     * @return [POPULATED_INSIDE] when the point itself is in a populated area, [POPULATED_NEAR]
     * when one is closer than [radius] meters, [POPULATED_NONE] otherwise and
     * [POPULATED_UNKNOWN] when there is no data for the point.
     */
    fun populatedState(lat: Double, lon: Double, radius: Double, minClass: Int = POPULATED_CLASS_ISOLATED): Int {
        val layer = populated ?: return POPULATED_UNKNOWN
        val p = DoubleArray(2)
        projection.forward(lat, lon, p)
        val col = layer.colOf(p[0])
        val row = layer.rowOf(p[1])
        val here = layer.rawAt(col, row)
        if (here == layer.nodata) return POPULATED_UNKNOWN
        if (here >= minClass) return POPULATED_INSIDE
        if (radius <= 0.0) return POPULATED_NONE

        val cell = layer.cellSize
        val reach = ceil(radius / cell).toInt()
        val r2 = radius * radius
        for (dr in -reach..reach) {
            for (dc in -reach..reach) {
                if (dr == 0 && dc == 0) continue
                val v = layer.rawAt(col + dc, row + dr)
                if (v < minClass || v == layer.nodata) continue
                // distance from the point to the closest edge of that cell
                val cx0 = layer.originX + (col + dc) * cell
                val cy0 = layer.originY + (row + dr) * cell
                val dx = max(max(cx0 - p[0], p[0] - (cx0 + cell)), 0.0)
                val dy = max(max(cy0 - p[1], p[1] - (cy0 + cell)), 0.0)
                if (dx * dx + dy * dy <= r2) return POPULATED_NEAR
            }
        }
        return POPULATED_NONE
    }

    /**
     * Lowest altitude (above sea level) at which an aircraft at the given position still has an
     * unobstructed straight line to the receiving antenna.
     *
     * The line is walked from the antenna to the aircraft; for every obstacle on the way the
     * altitude needed to look over it is extrapolated to the aircraft position. Earth curvature
     * is taken into account with the usual 4/3 effective radius for radio waves.
     *
     * @param antennaHeight height of the antenna above the ground at its position
     * @param ignoreNear obstacles closer than this to the antenna are ignored (trees right
     * next to the pilot would otherwise shade the whole sky)
     * @param margin extra clearance required above every obstacle
     * @return null when the terrain under the antenna or the aircraft is unknown
     */
    fun minLineOfSightAltitude(
        antennaLat: Double, antennaLon: Double, antennaHeight: Double,
        aircraftLat: Double, aircraftLon: Double,
        ignoreNear: Double, margin: Double
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
            val needed = antennaAltitude + (top + margin + bulge - antennaAltitude) / t
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
        const val POPULATED_UNKNOWN = -1
        const val POPULATED_NONE = 0
        const val POPULATED_NEAR = 1
        const val POPULATED_INSIDE = 2

        const val POPULATED_CLASS_ISOLATED = 1
        const val POPULATED_CLASS_SETTLEMENT = 2

        /** 4/3 of the mean Earth radius: standard atmosphere bends radio waves slightly. */
        const val EFFECTIVE_EARTH_RADIUS = 6371000.0 * 4.0 / 3.0

        private const val MAX_SAMPLES = 40000
    }
}
