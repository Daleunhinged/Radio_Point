package com.radiopoint.app.gis
import com.radiopoint.app.data.Direction
import org.junit.Assert.*
import org.junit.Test
import org.locationtech.jts.geom.*
class RoadPredictorEngineTest {
    private val road=RoadDefinition(1,"Test","","",GeometryFactory().createLineString(arrayOf(Coordinate(0.0,0.0),Coordinate(10000.0,0.0))),10.0,Coordinate(0.0,0.0))
    private fun predict(a:Direction,b:Direction,sa:Double=36.0,sb:Double=36.0)=RoadPredictorEngine().predictMeet(Coordinate(1000.0,0.0),Coordinate(9000.0,0.0),sa,sb,road,emptyList(),a,b)
    @Test fun approachingTraffic(){val p=predict(Direction.UP,Direction.DOWN)!!;assertEquals(5.0,p.passKm,1e-8);assertEquals(400.0/60,p.timeToMeetMinutes,1e-8)}
    @Test fun divergingTrafficHasNoEncounter(){assertNull(predict(Direction.DOWN,Direction.UP))}
    @Test fun parallelTrafficHasNoEncounter(){assertNull(predict(Direction.UP,Direction.UP))}
    @Test fun stoppedTrafficIsNotGivenInventedSpeed(){assertNull(predict(Direction.PARKED,Direction.DOWN));assertNull(predict(Direction.UP,Direction.DOWN,0.0))}
    @Test fun nearbyPulloutDoesNotMoveTheCalculatedEncounter(){
        val po=LivePullout("1","Test",5.5,Coordinate(5500.0,0.0),53.0,-123.0)
        val p=RoadPredictorEngine().predictMeet(Coordinate(1000.0,0.0),Coordinate(9000.0,0.0),36.0,36.0,road,listOf(po),Direction.UP,Direction.DOWN)!!
        assertEquals(5.0,p.passKm,1e-8);assertEquals(5.5,p.nearbyPulloutKm!!,1e-8);assertFalse(p.isDesignatedPullout)
    }
    @Test fun utmRoundTrip(){val u=UtmConverter.latLonToUtm(53.25,-123.3);val p=UtmConverter.utmToLatLon(u);assertEquals(53.25,p.first,0.0001);assertEquals(-123.3,p.second,0.0001)}
}
