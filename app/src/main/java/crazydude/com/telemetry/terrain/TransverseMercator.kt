package crazydude.com.telemetry.terrain

import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/**
 * Transverse Mercator projection (Krueger series, accurate to well below 1 mm inside the zone).
 *
 * Terrain packs store their rasters on a metric grid; the projection parameters come from the
 * pack header, so nothing here is specific to one country. For Lithuania the grid is LKS-94
 * (EPSG:3346): GRS80, central meridian 24E, scale 0.9998, false easting 500000.
 */
class TransverseMercator(
    semiMajorAxis: Double,
    inverseFlattening: Double,
    centralMeridianDeg: Double,
    latitudeOfOriginDeg: Double,
    private val scaleFactor: Double,
    private val falseEasting: Double,
    falseNorthing: Double
) {
    private val lon0 = Math.toRadians(centralMeridianDeg)
    private val n: Double
    private val e2c: Double // 2*sqrt(n)/(1+n)
    private val bigA: Double
    private val a1: Double
    private val a2: Double
    private val a3: Double
    private val b1: Double
    private val b2: Double
    private val b3: Double
    private val d1: Double
    private val d2: Double
    private val d3: Double
    private val northingOffset: Double

    init {
        val f = 1.0 / inverseFlattening
        n = f / (2.0 - f)
        val n2 = n * n
        val n3 = n2 * n
        val n4 = n2 * n2
        e2c = 2.0 * sqrt(n) / (1.0 + n)
        bigA = semiMajorAxis / (1.0 + n) * (1.0 + n2 / 4.0 + n4 / 64.0)
        a1 = n / 2.0 - 2.0 * n2 / 3.0 + 5.0 * n3 / 16.0
        a2 = 13.0 * n2 / 48.0 - 3.0 * n3 / 5.0
        a3 = 61.0 * n3 / 240.0
        b1 = n / 2.0 - 2.0 * n2 / 3.0 + 37.0 * n3 / 96.0
        b2 = n2 / 48.0 + n3 / 15.0
        b3 = 17.0 * n3 / 480.0
        d1 = 2.0 * n - 2.0 * n2 / 3.0 - 2.0 * n3
        d2 = 7.0 * n2 / 3.0 - 8.0 * n3 / 5.0
        d3 = 56.0 * n3 / 15.0
        // northing of the latitude of origin on the central meridian
        val xi0 = conformalXi(Math.toRadians(latitudeOfOriginDeg), 0.0)
        val m0 = xi0 + a1 * sin(2 * xi0) + a2 * sin(4 * xi0) + a3 * sin(6 * xi0)
        northingOffset = falseNorthing - scaleFactor * bigA * m0
    }

    private fun atanh(x: Double): Double = 0.5 * ln((1.0 + x) / (1.0 - x))

    private fun conformalTan(lat: Double): Double {
        val s = sin(lat)
        return sinh(atanh(s) - e2c * atanh(e2c * s))
    }

    private fun conformalXi(lat: Double, dLon: Double): Double {
        return atan2(conformalTan(lat), cos(dLon))
    }

    /** Geographic (degrees) to grid (meters). Writes easting to out[0], northing to out[1]. */
    fun forward(latDeg: Double, lonDeg: Double, out: DoubleArray) {
        val lat = Math.toRadians(latDeg)
        val dLon = Math.toRadians(lonDeg) - lon0
        val t = conformalTan(lat)
        val xi = atan2(t, cos(dLon))
        val eta = atanh(sin(dLon) / sqrt(1.0 + t * t))
        val e = eta +
                a1 * cos(2 * xi) * sinh(2 * eta) +
                a2 * cos(4 * xi) * sinh(4 * eta) +
                a3 * cos(6 * xi) * sinh(6 * eta)
        val nn = xi +
                a1 * sin(2 * xi) * cosh(2 * eta) +
                a2 * sin(4 * xi) * cosh(4 * eta) +
                a3 * sin(6 * xi) * cosh(6 * eta)
        out[0] = falseEasting + scaleFactor * bigA * e
        out[1] = northingOffset + scaleFactor * bigA * nn
    }

    /** Grid (meters) to geographic (degrees). Writes latitude to out[0], longitude to out[1]. */
    fun inverse(easting: Double, northing: Double, out: DoubleArray) {
        val xi = (northing - northingOffset) / (scaleFactor * bigA)
        val eta = (easting - falseEasting) / (scaleFactor * bigA)
        val xiP = xi -
                b1 * sin(2 * xi) * cosh(2 * eta) -
                b2 * sin(4 * xi) * cosh(4 * eta) -
                b3 * sin(6 * xi) * cosh(6 * eta)
        val etaP = eta -
                b1 * cos(2 * xi) * sinh(2 * eta) -
                b2 * cos(4 * xi) * sinh(4 * eta) -
                b3 * cos(6 * xi) * sinh(6 * eta)
        val chi = asin(sin(xiP) / cosh(etaP))
        val lat = chi + d1 * sin(2 * chi) + d2 * sin(4 * chi) + d3 * sin(6 * chi)
        val lon = lon0 + atan(sinh(etaP) / cos(xiP))
        out[0] = Math.toDegrees(lat)
        out[1] = Math.toDegrees(lon)
    }
}
