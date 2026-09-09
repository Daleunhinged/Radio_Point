package com.radiopoint.app.data
import org.junit.Assert.*
import org.junit.Test

class TelemetryPacketTest {
    private fun packet(lat:Double=53.2,lon:Double = -123.4)=TelemetryPacket(255,VehicleStatus.LOADED,Direction.DOWN,0x12345678,lat,lon,55.0,270.0,12,2,14)
    @Test fun fullCoordinatesDoNotWrapOrLoseSign() {
        listOf(53.2 to -123.4,53.8 to -124.2,-33.8 to 151.2,0.0 to 0.0,90.0 to -180.0).forEach {(lat,lon)->
            val p=packet(lat,lon);val q=TelemetryPacket.fromPayloadBytes(p.toPayloadBytes())!!
            assertEquals(lat,q.latitude,0.000001);assertEquals(lon,q.longitude,0.000001)
            assertEquals(p.unitId,q.unitId);assertEquals(p.roadCode,q.roadCode);assertEquals(p.accuracyMeters,q.accuracyMeters)
        }
    }
    @Test fun invalidPayloadsAreRejected() {
        assertNull(TelemetryPacket.fromPayloadBytes(ByteArray(20)))
        val bytes=packet().toPayloadBytes()
        for(index in listOf(0,1,2)){val bad=bytes.copyOf();bad[index]=if(index==2)0xff.toByte() else 0;assertNull(TelemetryPacket.fromPayloadBytes(bad))}
    }
    @Test(expected=IllegalArgumentException::class) fun invalidCoordinatesCannotTransmit(){packet(Double.NaN).toPayloadBytes()}
    @Test fun routeCodeStableAcrossLocale(){
        val old=java.util.Locale.getDefault()
        try {java.util.Locale.setDefault(java.util.Locale("tr","TR"));val a=TelemetryPacket.calculateRoadCode("TIBBLES");java.util.Locale.setDefault(java.util.Locale.US);assertEquals(a,TelemetryPacket.calculateRoadCode("TIBBLES"))}finally{java.util.Locale.setDefault(old)}
    }
}
