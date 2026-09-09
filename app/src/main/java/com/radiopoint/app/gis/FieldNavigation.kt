package com.radiopoint.app.gis

import kotlin.math.*

/** Straight-line spherical guidance. Not a walking route or device compass. */
object FieldNavigation {
    data class Guidance(val meters: Double, val bearingTrue: Double?)

    fun between(lat: Double, lon: Double, targetLat: Double, targetLon: Double,
                accuracy: Double = 0.0, targetAccuracy: Double = 0.0): Guidance {
        require(listOf(lat, lon, targetLat, targetLon, accuracy, targetAccuracy).all { it.isFinite() })
        require(lat in -90.0..90.0 && targetLat in -90.0..90.0)
        require(lon in -180.0..180.0 && targetLon in -180.0..180.0)
        require(accuracy >= 0 && targetAccuracy >= 0)
        val p = Math.toRadians(lat); val q = Math.toRadians(targetLat)
        val dl = Math.toRadians(targetLon - lon)
        val a = (sin((q-p)/2).pow(2) + cos(p)*cos(q)*sin(dl/2).pow(2)).coerceIn(0.0,1.0)
        val meters = 6371008.8 * 2 * atan2(sqrt(a), sqrt(1-a))
        val bearing = (Math.toDegrees(atan2(sin(dl)*cos(q), cos(p)*sin(q)-sin(p)*cos(q)*cos(dl)))+360)%360
        // Do not display a directional instruction when uncertainty dominates.
        return Guidance(meters, if(meters <= max(1.0, accuracy+targetAccuracy) || a >= 1-1e-12) null else bearing)
    }

    /** A future receipt time (clock change) must not appear freshly received. */
    fun reportAgeSeconds(now: Long, receivedAt: Long, fixAgeSeconds: Int): Long? =
        if(receivedAt > now || receivedAt <= 0 || fixAgeSeconds < 0) null
        else (now-receivedAt)/1000 + fixAgeSeconds
}
