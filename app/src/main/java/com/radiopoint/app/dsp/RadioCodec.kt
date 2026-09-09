package com.radiopoint.app.dsp

import com.radiopoint.app.data.TelemetryPacket
import kotlin.math.sin

object RadioCodec {
    const val SAMPLE_RATE = 48000
    const val BAUD = 600
    const val SAMPLES_PER_BIT = SAMPLE_RATE / BAUD
    const val SYNC = 0x2DD4
    const val FRAME_BYTES = TelemetryPacket.PAYLOAD_SIZE + 2
    fun crc(bytes: ByteArray): Int {
        var crc = 0xffff
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 255) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 65535
        }
        return crc
    }
    fun frame(payload: ByteArray): ByteArray {
        require(payload.size == TelemetryPacket.PAYLOAD_SIZE)
        val crc = crc(payload)
        return byteArrayOf(0xaa.toByte(),0xaa.toByte(),0xaa.toByte(),0xaa.toByte(),0x2d,0xd4.toByte()) + payload + byteArrayOf((crc shr 8).toByte(),crc.toByte())
    }
    fun synthesize(payload: ByteArray, repeats: Int = 2): ShortArray {
        require(repeats in 1..3)
        val frame = frame(payload)
        val gap = SAMPLE_RATE / 25
        val out = ShortArray(repeats * (frame.size * 8 * SAMPLES_PER_BIT + gap))
        var pos = 0; var phase = 0.0
        repeat(repeats) {
            for (b in frame) for (bit in 7 downTo 0) {
                val f = if ((b.toInt() shr bit) and 1 == 1) 1400.0 else 2300.0
                repeat(SAMPLES_PER_BIT) {
                    out[pos++] = (sin(phase) * 24000).toInt().toShort()
                    phase = (phase + 2 * Math.PI * f / SAMPLE_RATE) % (2 * Math.PI)
                }
            }
            pos += gap
        }
        return out
    }
}
