package com.radiopoint.app.dsp

import com.radiopoint.app.data.TelemetryPacket
import kotlin.math.cos
import kotlin.math.sin

/** Streaming quadrature correlators preserve history across AudioRecord buffer boundaries.
 * Eight symbol phases search independently; CRC and payload validation gate delivery. */
class AcousticAfskDemodulator(private val onPacketReceived: (ByteArray) -> Unit) {
    private val window = RadioCodec.SAMPLES_PER_BIT
    private val ring = DoubleArray(window)
    private val sinMark = DoubleArray(480) { sin(2 * Math.PI * 1400 * it / 48000) }
    private val cosMark = DoubleArray(480) { cos(2 * Math.PI * 1400 * it / 48000) }
    private val sinSpace = DoubleArray(480) { sin(2 * Math.PI * 2300 * it / 48000) }
    private val cosSpace = DoubleArray(480) { cos(2 * Math.PI * 2300 * it / 48000) }
    private var count = 0L
    private var ms = 0.0; private var mc = 0.0; private var ss = 0.0; private var sc = 0.0
    private var lastPayload: ByteArray? = null
    private var lastDelivery = -48000L
    private val parsers = Array(8) { Parser() }
    @Volatile var isHandheldMode = false

    @Synchronized fun processAudioSamples(samples: ShortArray, length: Int) {
        require(length in 0..samples.size)
        for (i in 0 until length) {
            val n = count
            val r = (n % window).toInt(); val phase = (n % 480).toInt()
            val oldPhase = ((n - window + 480) % 480).toInt()
            val old = ring[r]; val x = samples[i].toDouble()
            ms += x*sinMark[phase] - old*sinMark[oldPhase]
            mc += x*cosMark[phase] - old*cosMark[oldPhase]
            ss += x*sinSpace[phase] - old*sinSpace[oldPhase]
            sc += x*cosSpace[phase] - old*cosSpace[oldPhase]
            ring[r] = x; count++
            if (count >= window && count % 10L == 0L) {
                val mark = ms*ms + mc*mc; val space = ss*ss + sc*sc
                parsers[((count % window) / 10).toInt()].push(mark > space)
            }
        }
    }
    private inner class Parser {
        var sync = 0; var remaining = 0; var current = 0; var bits = 0
        val data = ArrayList<Byte>()
        fun push(bit: Boolean) {
            val v = if(bit) 1 else 0
            if(remaining == 0) {
                sync = ((sync shl 1) or v) and 65535
                if(sync == RadioCodec.SYNC) { remaining = RadioCodec.FRAME_BYTES; bits=0; current=0; data.clear() }
            } else {
                current = (current shl 1) or v; bits++
                if(bits == 8) {
                    data.add(current.toByte()); bits=0; current=0; remaining--
                    if(remaining == 0) {
                        val all = data.toByteArray(); val payload = all.copyOfRange(0,TelemetryPacket.PAYLOAD_SIZE)
                        val crc = ((all[all.size-2].toInt() and 255) shl 8) or (all.last().toInt() and 255)
                        if(RadioCodec.crc(payload) == crc && TelemetryPacket.fromPayloadBytes(payload) != null &&
                            (lastPayload?.contentEquals(payload) != true || count-lastDelivery > 48000)) {
                            lastPayload = payload; lastDelivery = count; onPacketReceived(payload)
                        }
                        sync=0
                    }
                }
            }
        }
        fun reset() { sync=0; remaining=0; bits=0; current=0; data.clear() }
    }
    @Synchronized fun reset() {
        ring.fill(0.0); count=0; ms=0.0; mc=0.0; ss=0.0; sc=0.0
        lastPayload=null; lastDelivery = -48000; parsers.forEach { it.reset() }
    }
}
