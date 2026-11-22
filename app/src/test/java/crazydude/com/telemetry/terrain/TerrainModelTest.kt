package crazydude.com.telemetry.terrain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
