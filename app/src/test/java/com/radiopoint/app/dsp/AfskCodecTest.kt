package com.radiopoint.app.dsp
import com.radiopoint.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class AfskCodecTest {
    private fun payload(id:Int=22)=TelemetryPacket(id,VehicleStatus.LOADED,Direction.DOWN,0x12786543,53.218734,-123.498721,47.0,245.0,8,1,42).toPayloadBytes()
    private fun decode(audio:ShortArray,chunk:Int):List<ByteArray> {
        val out=mutableListOf<ByteArray>();val d=AcousticAfskDemodulator {out.add(it)}
        var i=0
        while(i<audio.size){val n=minOf(chunk,audio.size-i);val b=ShortArray(chunk){12345};audio.copyInto(b,0,i,i+n);d.processAudioSamples(b,n);i+=n}
        return out
    }
    @Test fun knownCrcVector(){assertEquals(0x29b1,RadioCodec.crc("123456789".toByteArray()))}
    @Test fun actualWaveformDecodesAtEverySymbolOffset() {
        val p=payload();val wav=RadioCodec.synthesize(p,1)
        for(offset in 0 until 80){val audio=ShortArray(offset)+wav+ShortArray(480);val decoded=decode(audio,1024);assertTrue("Offset $offset",decoded.any {it.contentEquals(p)})}
    }
    @Test fun arbitraryChunkBoundariesPreserveHistory(){
        val p=payload();val wav=ShortArray(137)+RadioCodec.synthesize(p)+ShortArray(1000)
        for(chunk in listOf(1,13,79,80,127,1024,4096)){val out=decode(wav,chunk);assertEquals("chunk=$chunk",1,out.size);assertArrayEquals(p,out.first())}
    }
    @Test fun attenuatedNoisyBandLimitedAudio(){
        val random=Random(8);val p=payload();val wav=RadioCodec.synthesize(p)
        var low=0.0
        val distorted=ShortArray(wav.size){i->low+=0.32*(wav[i]-low);(low*0.35+random.nextGaussian()*450+300).toInt().coerceIn(-32768,32767).toShort()}
        assertTrue(decode(ShortArray(139)+distorted,333).any {it.contentEquals(p)})
    }
    @Test fun randomPayloadCorpus(){
        val rng=Random(27)
        repeat(50){val p=TelemetryPacket(it+1,VehicleStatus.entries[it%4],Direction.entries[it%3],rng.nextInt(),48+rng.nextDouble()*10,-126+rng.nextDouble()*6,rng.nextInt(100).toDouble(),rng.nextDouble()*360,15,0,it).toPayloadBytes()
            assertTrue(decode(RadioCodec.synthesize(p,1),777).any {it.contentEquals(p)})}
    }
    @Test fun silenceAndNoiseDoNotCreatePositions(){
        assertTrue(decode(ShortArray(96000),1024).isEmpty());val r=Random(9)
        assertTrue(decode(ShortArray(96000){(r.nextGaussian()*3000).toInt().toShort()},1024).isEmpty())
    }
    @Test fun allSingleBitCorruptionsFailChecksum(){
        val p=payload();val crc=RadioCodec.crc(p)
        for(bit in 0 until p.size*8){val bad=p.copyOf();bad[bit/8]=(bad[bit/8].toInt() xor (1 shl(bit%8))).toByte();assertNotEquals(crc,RadioCodec.crc(bad))}
    }
}
