package com.radiopoint.app.gis

import com.radiopoint.app.data.Direction
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.linearref.LengthIndexedLine
import kotlin.math.*

data class PassPrediction(
    val passCoordinate:Coordinate,
    val passKm:Double,
    val isDesignatedPullout:Boolean = false,
    val timeToMeetMinutes:Double,
    val roadName:String,
    val pulloutId:String? = null,
    val nearbyPulloutKm:Double? = null
)
class RoadPredictorEngine {
    fun predictMeet(localUtm:Coordinate,remoteUtm:Coordinate,localSpeedKmh:Double,remoteSpeedKmh:Double,
        activeRoad:RoadDefinition,pullouts:List<LivePullout>,localDirection:Direction,remoteDirection:Direction):PassPrediction? {
        if(!localSpeedKmh.isFinite() || !remoteSpeedKmh.isFinite() || localSpeedKmh<0 || remoteSpeedKmh<0)return null
        val line=LengthIndexedLine(activeRoad.geometry)
        val a=line.project(localUtm);val b=line.project(remoteUtm)
        if(abs(a-b)<10)return null
        // Only opposing, moving traffic. No guessed minimum speeds, overtaking, or parked extrapolation.
        if(localDirection==Direction.PARKED || remoteDirection==Direction.PARKED || localDirection==remoteDirection)return null
        if(localSpeedKmh<3 || remoteSpeedKmh<3)return null
        val va=localSpeedKmh/3.6 * if(localDirection==Direction.UP)1 else -1
        val vb=remoteSpeedKmh/3.6 * if(remoteDirection==Direction.UP)1 else -1
        val time=(b-a)/(va-vb)
        if(time<=0 || time>1800)return null
        val idx=a+va*time
        if(idx !in 0.0..activeRoad.geometry.length)return null
        val near=pullouts.filter { it.roadName==activeRoad.name && it.kmMarker*1000 in min(a,b)..max(a,b) }
            .minByOrNull { abs(it.kmMarker*1000-idx) }?.takeIf { abs(it.kmMarker*1000-idx)<=1500 }
        // Meeting estimate remains at the calculated point; a turnout is only a separate suggestion.
        return PassPrediction(line.extractPoint(idx),idx/1000,timeToMeetMinutes=time/60,roadName=activeRoad.name,nearbyPulloutKm=near?.kmMarker)
    }
}
