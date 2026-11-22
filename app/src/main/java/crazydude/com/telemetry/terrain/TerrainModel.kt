package crazydude.com.telemetry.terrain

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
}
