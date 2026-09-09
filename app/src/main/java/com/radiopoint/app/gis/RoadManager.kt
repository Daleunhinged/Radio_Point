// File: gis/RoadManager.kt
package com.radiopoint.app.gis

import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.linearref.LengthIndexedLine

class RoadManager(initialRoads: List<RoadDefinition>) {

    val availableRoads = initialRoads.toMutableList()

    @Volatile
    var selectedRoad: RoadDefinition = availableRoads.first()
        private set

    fun addRoad(road: RoadDefinition) {
        synchronized(availableRoads) {
            val existingIndex = availableRoads.indexOfFirst { it.name.equals(road.name, ignoreCase = true) }
            if (existingIndex >= 0) {
                availableRoads[existingIndex] = road
            } else {
                availableRoads.add(0, road)
            }
        }
    }

    fun selectRoad(roadName: String): RoadDefinition? {
        val matched = availableRoads.find { it.name.equals(roadName, ignoreCase = true) }
        if (matched != null) {
            selectedRoad = matched
        }
        return matched
    }

    fun selectRoadByCode(roadCode: Int): RoadDefinition? {
        val matched = availableRoads.find { it.roadCode == roadCode }
        if (matched != null) {
            selectedRoad = matched
        }
        return matched
    }

    fun snapToSelectedRoad(currentUtm: Coordinate): Pair<Coordinate, Double> {
        val indexedLine = LengthIndexedLine(selectedRoad.geometry)
        val measureMeters = indexedLine.project(currentUtm)
        val snappedUtm = indexedLine.extractPoint(measureMeters)
        val kmMarker = Math.round((measureMeters / 1000.0) * 100.0) / 100.0
        return Pair(snappedUtm, kmMarker)
    }

    fun isPointNearRoad(currentUtm: Coordinate, maxDistanceMeters: Double = 500.0): Boolean {
        val pt = org.locationtech.jts.geom.GeometryFactory().createPoint(currentUtm)
        return selectedRoad.geometry.distance(pt) <= maxDistanceMeters
    }
}
