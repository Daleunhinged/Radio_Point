package com.radiopoint.app.gis

import org.locationtech.jts.geom.Coordinate
import kotlin.math.*

/**
 * High-performance WGS84 <-> UTM Zone 10N Coordinate Conversion.
 * British Columbia Forest Service Roads predominantly operate within UTM Zone 10N.
 */
object UtmConverter {

    private const val a = 6378137.0 // WGS84 semi-major axis
    private const val f = 1.0 / 298.257223563 // WGS84 flattening
    private const val b = a * (1.0 - f)
    private const val e2 = (a * a - b * b) / (a * a)
    private const val ePrime2 = (a * a - b * b) / (b * b)
    private const val k0 = 0.9996 // UTM scale factor
    private const val ZONE_10_CENTRAL_MERIDIAN = -123.0

    /**
     * Converts WGS84 (Lat/Lon in degrees) to UTM Zone 10N (Easting, Northing in meters).
     */
    fun latLonToUtm(latDeg: Double, lonDeg: Double, zoneCentralMeridian: Double = ZONE_10_CENTRAL_MERIDIAN): Coordinate {
        val latRad = Math.toRadians(latDeg)
        val lonRad = Math.toRadians(lonDeg)
        val lonOriginRad = Math.toRadians(zoneCentralMeridian)

        val n = (a - b) / (a + b)
        val nu = a / sqrt(1.0 - e2 * sin(latRad).pow(2))

        val p = lonRad - lonOriginRad

        // Meridional arc length calculation
        val A0 = 1.0 + (n.pow(2) / 4.0) + (n.pow(4) / 64.0)
        val B0 = (3.0 / 2.0) * (n - (n.pow(3) / 8.0))
        val C0 = (15.0 / 16.0) * (n.pow(2) - (n.pow(4) / 4.0))
        val D0 = (35.0 / 48.0) * n.pow(3)
        val E0 = (315.0 / 512.0) * n.pow(4)

        val meridionalArc = a / (1.0 + n) * (
            A0 * latRad -
            B0 * sin(2.0 * latRad) +
            C0 * sin(4.0 * latRad) -
            D0 * sin(6.0 * latRad) +
            E0 * sin(8.0 * latRad)
        )

        val k1 = meridionalArc * k0
        val k2 = nu * sin(latRad) * cos(latRad) * k0 / 2.0
        val k3 = (nu * sin(latRad) * cos(latRad).pow(3) * k0 / 24.0) * (5.0 - tan(latRad).pow(2) + 9.0 * ePrime2 * cos(latRad).pow(2) + 4.0 * ePrime2.pow(2) * cos(latRad).pow(4))
        
        val k4 = nu * cos(latRad) * k0
        val k5 = (nu * cos(latRad).pow(3) * k0 / 6.0) * (1.0 - tan(latRad).pow(2) + ePrime2 * cos(latRad).pow(2))

        val easting = 500000.0 + (k4 * p + k5 * p.pow(3))
        val northing = k1 + k2 * p.pow(2) + k3 * p.pow(4)

        return Coordinate(easting, northing)
    }

    /**
     * Converts UTM Zone 10N (Easting, Northing) back to WGS84 (Lat, Lon in degrees).
     */
    fun utmToLatLon(utm: Coordinate, zoneCentralMeridian: Double = ZONE_10_CENTRAL_MERIDIAN): Pair<Double, Double> {
        val x = utm.x - 500000.0
        val y = utm.y

        val m = y / k0
        val mu = m / (a * (1.0 - e2 / 4.0 - 3.0 * e2.pow(2) / 64.0 - 5.0 * e2.pow(3) / 256.0))

        val e1 = (1.0 - sqrt(1.0 - e2)) / (1.0 + sqrt(1.0 - e2))
        val j1 = (3.0 * e1 / 2.0 - 27.0 * e1.pow(3) / 32.0)
        val j2 = (21.0 * e1.pow(2) / 16.0 - 55.0 * e1.pow(4) / 32.0)
        val j3 = (151.0 * e1.pow(3) / 96.0)

        val footprintLat = mu + j1 * sin(2.0 * mu) + j2 * sin(4.0 * mu) + j3 * sin(6.0 * mu)

        val c1 = ePrime2 * cos(footprintLat).pow(2)
        val t1 = tan(footprintLat).pow(2)
        val n1 = a / sqrt(1.0 - e2 * sin(footprintLat).pow(2))
        val r1 = a * (1.0 - e2) / (1.0 - e2 * sin(footprintLat).pow(2)).pow(1.5)
        val d = x / (n1 * k0)

        val lat = footprintLat - (n1 * tan(footprintLat) / r1) * (d.pow(2) / 2.0 - (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1.pow(2) - 9.0 * ePrime2) * d.pow(4) / 24.0)
        val lon = (d - (1.0 + 2.0 * t1 + c1) * d.pow(3) / 6.0) / cos(footprintLat)

        val latDeg = Math.toDegrees(lat)
        val lonDeg = zoneCentralMeridian + Math.toDegrees(lon)

        return Pair(latDeg, lonDeg)
    }
}
