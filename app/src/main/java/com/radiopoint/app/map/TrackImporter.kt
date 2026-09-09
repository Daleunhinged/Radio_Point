package com.radiopoint.app.map

import com.radiopoint.app.data.TelemetryPacket
import com.radiopoint.app.gis.*
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.linearref.LengthIndexedLine
import org.w3c.dom.Element
import java.io.InputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class ImportedRoadResult(val road:RoadDefinition,val pullouts:List<LivePullout>)
object TrackImporter {
    private fun document(input:InputStream):Element {
        val factory=DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware=true
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
        val builder=factory.newDocumentBuilder()
        builder.setEntityResolver { _,_->org.xml.sax.InputSource(java.io.StringReader("")) }
        val doc=builder.parse(input)
        require(doc.doctype==null) { "Documents with DOCTYPE are not supported" }
        return doc.documentElement
    }
    private fun Element.all(tag:String):List<Element> {
        val list=getElementsByTagNameNS("*",tag)
        return (0 until list.length).map { list.item(it) as Element }
    }
    private fun Element.label(fallback:String)=all("name").firstOrNull()?.textContent?.trim()?.take(100)?.ifEmpty { fallback } ?: fallback
    fun parseGpx(inputStream:InputStream,defaultName:String="Imported track"):ImportedRoadResult? {
        val root=document(inputStream)
        val segments=root.all("trkseg").filter { it.all("trkpt").size>=2 }
        val routes=root.all("rte").filter { it.all("rtept").size>=2 }
        require(segments.size+routes.size<=1) { "Import one continuous track segment or route at a time" }
        val segment=(segments+routes).firstOrNull()?:return null
        val pts=segment.all(if(segments.isEmpty())"rtept" else "trkpt").map { it.getAttribute("lat").toDouble() to it.getAttribute("lon").toDouble() }
        val name=if(segments.isEmpty())segment.label(defaultName) else (segment.parentNode as? Element)?.label(defaultName) ?: defaultName
        val wpts=root.all("wpt").filter { Regex("pullout|turnout|passing",RegexOption.IGNORE_CASE).containsMatchIn(it.label("")) }
            .map { it.getAttribute("lat").toDouble() to it.getAttribute("lon").toDouble() }
        return build(name,pts,wpts)
    }
    fun parseKml(inputStream:InputStream,defaultName:String="Imported road"):ImportedRoadResult? {
        val root=document(inputStream);val lines=root.all("LineString")
        require(lines.size<=1) { "Import one LineString at a time" }
        val line=lines.firstOrNull()?:return null
        fun coords(e:Element)=e.all("coordinates").firstOrNull()?.textContent?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.map {
            val v=it.split(',');require(v.size>=2);v[1].toDouble() to v[0].toDouble()
        } ?: emptyList()
        val points=coords(line);if(points.size<2)return null
        var parent=line.parentNode
        while(parent is Element && parent.localName!="Placemark")parent=parent.parentNode
        val name=(parent as? Element)?.label(defaultName)?:defaultName
        val wpts=root.all("Placemark").filter { Regex("pullout|turnout|passing",RegexOption.IGNORE_CASE).containsMatchIn(it.label("")) }
            .flatMap { it.all("Point").flatMap(::coords) }
        return build(name,points,wpts)
    }
    private fun build(name:String,pts:List<Pair<Double,Double>>,wpts:List<Pair<Double,Double>>):ImportedRoadResult {
        require(pts.size in 2..100000) { "Track must contain 2–100,000 points" }
        fun valid(p:Pair<Double,Double>)=p.first.isFinite() && p.second.isFinite() && p.first in 48.0..61.0 && p.second in -126.0..-120.0
        require(pts.all(::valid)) { "Road calculations currently support BC UTM zone 10 (-126° to -120° longitude)" }
        val coords=pts.map { UtmConverter.latLonToUtm(it.first,it.second) }.toTypedArray()
        val geometry=GeometryFactory().createLineString(coords)
        require(geometry.length>1) { "Track has no usable length" }
        val fingerprint=name.trim()+pts.joinToString("") { String.format(Locale.US,"|%.6f,%.6f",it.first,it.second) }
        val code=TelemetryPacket.calculateRoadCode(fingerprint)
        val indexed=LengthIndexedLine(geometry)
        val pullouts=wpts.filter(::valid).mapIndexedNotNull { i,p ->
            val raw=UtmConverter.latLonToUtm(p.first,p.second);val index=indexed.project(raw);val snap=indexed.extractPoint(index)
            if(raw.distance(snap)>150)null else LivePullout("${code}_$i",name,index/1000,snap,p.first,p.second)
        }
        val road=RoadDefinition(code,name,"UNVERIFIED","Check posted channel",geometry,geometry.length/1000,coords.first(),pullouts)
        return ImportedRoadResult(road,pullouts)
    }
}
