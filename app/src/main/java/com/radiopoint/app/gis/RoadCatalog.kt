package com.radiopoint.app.gis

import com.radiopoint.app.data.TelemetryPacket
import org.locationtech.jts.geom.*

data class RoadDefinition(
    val roadCode:Int,val name:String,val defaultChannel:String,val frequencyMhz:String,
    val geometry:LineString,val totalLengthKm:Double,val originUtm:Coordinate,
    val initialPullouts:List<LivePullout> = emptyList(),val isDemo:Boolean = false
)
object RoadCatalog {
    val defaultRoads:List<RoadDefinition> by lazy {
        val a=UtmConverter.latLonToUtm(53.0,-123.0)
        val line=GeometryFactory().createLineString(arrayOf(a,Coordinate(a.x,a.y+10000)))
        listOf(RoadDefinition(TelemetryPacket.calculateRoadCode("demo-only"),"Sample corridor","UNVERIFIED","Check posted channel",
            line,10.0,a,isDemo=true))
    }
}
