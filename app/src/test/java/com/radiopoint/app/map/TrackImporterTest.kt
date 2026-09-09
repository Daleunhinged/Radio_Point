package com.radiopoint.app.map
import org.junit.Assert.*
import org.junit.Test
class TrackImporterTest {
    private val points="<trkpt lat=\"53.25\" lon=\"-123.3\"/><trkpt lat=\"53.28\" lon=\"-123.33\"/>"
    @Test fun realGpxParsing(){val xml="<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><name>Branch 400</name><trkseg>$points</trkseg></trk></gpx>";val r=TrackImporter.parseGpx(xml.byteInputStream())!!;assertEquals("Branch 400",r.road.name);assertTrue(r.road.totalLengthKm>3);assertEquals("UNVERIFIED",r.road.defaultChannel)}
    @Test fun realKmlParsing(){val xml="<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Placemark><name>Branch 400</name><LineString><coordinates>-123.3,53.25,0 -123.33,53.28,0</coordinates></LineString></Placemark></kml>";assertEquals("Branch 400",TrackImporter.parseKml(xml.byteInputStream())!!.road.name)}
    @Test(expected=IllegalArgumentException::class) fun disjointSegmentsRejected(){TrackImporter.parseGpx("<gpx><trk><trkseg>$points</trkseg><trkseg>$points</trkseg></trk></gpx>".byteInputStream())}
    @Test fun sameNameDifferentGeometryHasDifferentRouteCode(){val a="<gpx><trk><trkseg>$points</trkseg></trk></gpx>";val b=a.replace("53.28","53.29");assertNotEquals(TrackImporter.parseGpx(a.byteInputStream())!!.road.roadCode,TrackImporter.parseGpx(b.byteInputStream())!!.road.roadCode)}
    @Test(expected=IllegalArgumentException::class) fun unsupportedRegionRejected(){TrackImporter.parseGpx("<gpx><trk><trkseg>${points.replace("-123.3","-113.3")}</trkseg></trk></gpx>".byteInputStream())}
}
