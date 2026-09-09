package com.radiopoint.app

import com.radiopoint.app.data.*
import com.radiopoint.app.dsp.*
import org.junit.Assert.*
import org.junit.Test

class OnFootRadioTest {
    @Test fun onFootRemovesRoadParticipationWithoutChangingPosition() {
        for(status in VehicleStatus.entries) for(direction in Direction.entries) {
            val original=TelemetryPacket(12,status,direction,12345,53.21,-123.49,4.0,90.0,8,2,17)
            val walking=original.forOnFoot()
            assertEquals(0,walking.roadCode)
            assertEquals(Direction.PARKED,walking.direction)
            assertEquals(original.copy(direction=Direction.PARKED,roadCode=0),walking)
            assertEquals(walking,TelemetryPacket.fromPayloadBytes(walking.toPayloadBytes()))
            assertEquals(12345,original.roadCode)
        }
    }
    @Test fun onFootWaveformDecodesThroughUnchangedV2Receiver() {
        val walking=TelemetryPacket(12,VehicleStatus.PICKUP,Direction.UP,12345,53.21,-123.49,4.0,90.0,8,2,17).forOnFoot()
        val received=mutableListOf<ByteArray>()
        val decoder=AcousticAfskDemodulator { received.add(it) }
        val audio=ShortArray(137)+RadioCodec.synthesize(walking.toPayloadBytes())+ShortArray(1000)
        var pos=0
        while(pos<audio.size) {
            val chunk=audio.copyOfRange(pos,minOf(pos+333,audio.size))
            decoder.processAudioSamples(chunk,chunk.size);pos+=chunk.size
        }
        assertTrue(received.any { TelemetryPacket.fromPayloadBytes(it)==walking })
    }
}
