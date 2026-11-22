package crazydude.com.telemetry.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Checks the terrain pack reader, the projection and the ground/surface queries against values
 * produced by the independent Python implementation in tools/terrain/make_test_pack.py.
 */
class TerrainModelTest {

    private fun resourceFile(name: String): File {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
        assertNotNull("Missing test resource $name", stream)
        val file = File.createTempFile("terrain", ".bin")
        file.deleteOnExit()
        FileOutputStream(file).use { out -> stream!!.use { it.copyTo(out) } }
        return file
    }

    private fun vectors(type: String): List<List<String>> {
        return resourceFile("terrain/test_vectors.txt").readLines()
            .map { it.trim().split(" ") }
            .filter { it[0] == type }
    }

    private fun openModel(): TerrainModel {
        return TerrainModel(TerrainPack(ChannelTerrainSource.open(resourceFile("terrain/test.tpk")), 4))
    }

    private fun assertClose(message: String, expected: String, actual: Float, tolerance: Double) {
        if (expected == "nan") {
            assertTrue("$message: expected NaN, got $actual", actual.isNaN())
        } else {
            assertEquals(message, expected.toDouble(), actual.toDouble(), tolerance)
        }
    }

    @Test
    fun projectionMatchesProj() {
        val lks94 = TransverseMercator(6378137.0, 298.257222101, 24.0, 0.0, 0.9998, 500000.0, 0.0)
        val out = DoubleArray(2)
        val cases = vectors("T")
        assertTrue(cases.isNotEmpty())
        for (c in cases) {
            val lat = c[1].toDouble()
            val lon = c[2].toDouble()
            lks94.forward(lat, lon, out)
            assertEquals("easting of $lat,$lon", c[3].toDouble(), out[0], 0.001)
            assertEquals("northing of $lat,$lon", c[4].toDouble(), out[1], 0.001)
            lks94.inverse(c[3].toDouble(), c[4].toDouble(), out)
            assertEquals("latitude", lat, out[0], 1e-8)
            assertEquals("longitude", lon, out[1], 1e-8)
        }
    }

    @Test
    fun groundAndSurfaceMatchReference() {
        val model = openModel()
        val cases = vectors("G")
        assertTrue(cases.size > 100)
        var known = 0
        for (c in cases) {
            val lat = c[1].toDouble()
            val lon = c[2].toDouble()
            assertClose("ground at $lat,$lon", c[3], model.groundAt(lat, lon), 0.01)
            assertClose("surface at $lat,$lon", c[4], model.surfaceAt(lat, lon), 0.01)
            if (c[3] != "nan") known++
        }
        assertTrue("test data must contain both known and unknown points", known > 50 && known < cases.size)
    }

    @Test
    fun lineOfSightMatchesReference() {
        val model = openModel()
        val cases = vectors("L")
        assertTrue(cases.size > 100)
        var obstructedByTerrain = 0
        for (c in cases) {
            val los = model.minLineOfSightAltitude(
                c[1].toDouble(), c[2].toDouble(), c[3].toDouble(),
                c[4].toDouble(), c[5].toDouble(), c[6].toDouble(), c[7].toDouble()
            )
            val name = "line ${c[1]},${c[2]} -> ${c[4]},${c[5]}"
            if (c[8] == "nan") {
                assertNull(name, los)
                continue
            }
            assertNotNull(name, los)
            assertEquals("$name: min altitude", c[8].toDouble(), los!!.minAltitude, 0.05)
            assertEquals("$name: distance", c[9].toDouble(), los.distance, 0.01)
            assertEquals("$name: obstacle distance", c[10].toDouble(), los.obstacleDistance, 0.01)
            assertEquals("$name: completeness", c[11] == "1", los.complete)
            if (los.obstacleDistance < los.distance - 1.0) obstructedByTerrain++
        }
        assertTrue("test data must contain obstructed lines", obstructedByTerrain > 20)
    }

    @Test
    fun wallOnFlatGroundMatchesGeometry() {
        // Flat ground at 100 m, antenna 2 m above it, a 30 m high and 10 m thick wall starting
        // 1000 m away, aircraft 3000 m away. Looking over the far top edge of the wall needs
        // 102 + (130 - 102) * 3000 / 1000..1010 m plus a little for the curvature of the Earth.
        val c = vectors("W").single()
        val model = openModel()
        val los = model.minLineOfSightAltitude(
            c[1].toDouble(), c[2].toDouble(), 2.0, c[3].toDouble(), c[4].toDouble(), 0.0, 0.0
        )!!
        assertEquals(3000.0, los.distance, 0.5)
        assertEquals(130.0, los.obstacleTop, 0.01)
        assertTrue("obstacle at ${los.obstacleDistance}", los.obstacleDistance in 999.0..1010.0)
        assertTrue("min altitude ${los.minAltitude}", los.minAltitude in 185.5..186.5)
        assertTrue(los.complete)

        // A 100 m mast instead of a 2 m one lowers the requirement accordingly
        val mast = model.minLineOfSightAltitude(
            c[1].toDouble(), c[2].toDouble(), 100.0, c[3].toDouble(), c[4].toDouble(), 0.0, 0.0
        )!!
        assertTrue("min altitude with mast ${mast.minAltitude}", mast.minAltitude < 100.5)

        // With obstacles within 1.2 km of the antenna ignored the wall no longer counts
        val ignored = model.minLineOfSightAltitude(
            c[1].toDouble(), c[2].toDouble(), 2.0, c[3].toDouble(), c[4].toDouble(), 1200.0, 0.0
        )!!
        assertTrue("min altitude without wall ${ignored.minAltitude}", ignored.minAltitude < 101.0)
        assertFalse(ignored.obstacleDistance in 999.0..1010.0)
    }
}
