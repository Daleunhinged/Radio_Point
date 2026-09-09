package com.radiopoint.app.data

import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.roundToInt

/** v2 is intentionally incompatible with the truncated-coordinate v1 prototype. */
data class TelemetryPacket(
    val unitId: Int,
    val status: VehicleStatus,
    val direction: Direction,
    val roadCode: Int,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double,
    val headingDeg: Double,
    val accuracyMeters: Int,
    val fixAgeSeconds: Int = 0,
    val sequence: Int = 0
) {
    fun toPayloadBytes(): ByteArray {
        require(unitId in 1..255)
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(speedKmh.isFinite() && speedKmh in 0.0..254.0)
        require(headingDeg.isFinite())
        require(accuracyMeters in 0..65535 && fixAgeSeconds in 0..255 && sequence in 0..255)
        return ByteBuffer.allocate(PAYLOAD_SIZE).apply {
            put(2.toByte()); put(unitId.toByte()); put(((status.code shl 4) or direction.code).toByte())
            putInt(roadCode)
            putInt((latitude * 1e6).roundToInt()); putInt((longitude * 1e6).roundToInt())
            put(speedKmh.roundToInt().toByte())
            put((((headingDeg % 360 + 360) % 360) / 360 * 256).roundToInt().and(255).toByte())
            putShort(accuracyMeters.toShort()); put(fixAgeSeconds.toByte()); put(sequence.toByte())
        }.array()
    }
    companion object {
        const val PAYLOAD_SIZE = 21
        fun fromPayloadBytes(payload: ByteArray): TelemetryPacket? {
            if (payload.size != PAYLOAD_SIZE) return null
            val b = ByteBuffer.wrap(payload)
            if (b.get().toInt() != 2) return null
            val id = b.get().toInt() and 255
            val flags = b.get().toInt() and 255
            val status = VehicleStatus.entries.find { it.code == flags shr 4 } ?: return null
            val direction = Direction.entries.find { it.code == flags and 15 } ?: return null
            val road = b.int
            val lat = b.int / 1e6; val lon = b.int / 1e6
            val speed = b.get().toInt() and 255
            val heading = (b.get().toInt() and 255) * 360.0 / 256
            val accuracy = b.short.toInt() and 65535
            val age = b.get().toInt() and 255
            val seq = b.get().toInt() and 255
            if (id == 0 || lat !in -90.0..90.0 || lon !in -180.0..180.0 || speed == 255) return null
            return TelemetryPacket(id,status,direction,road,lat,lon,speed.toDouble(),heading,accuracy,age,seq)
        }
        // Canonical route geometry is included by the importer; name alone is insufficient.
        fun calculateRoadCode(roadName: String): Int = CRC32().apply {
            update(roadName.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        }.value.toInt()
    }
}
