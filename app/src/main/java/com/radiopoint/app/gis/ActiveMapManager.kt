// File: gis/ActiveMapManager.kt
package com.radiopoint.app.gis

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.linearref.LengthIndexedLine
import java.io.File

data class LivePullout(
    val id: String,
    val roadName: String,
    val kmMarker: Double,
    val utmCoord: Coordinate,
    val lat: Double,
    val lon: Double,
    val isUserCreated: Boolean = false
)

class ActiveMapManager(private val context: Context) {

    private val geomFactory = GeometryFactory()
    private val localSaveFile = File(context.filesDir, "active_user_pullouts.json")
    private val gson = Gson()

    val activePullouts = mutableListOf<LivePullout>()
    val loadedRoadLines = mutableMapOf<String, LineString>()

    init {
        loadPersistedPullouts()
    }

    fun loadRoadCatalog(roads: List<RoadDefinition>) {
        synchronized(loadedRoadLines) {
            for (road in roads) {
                loadedRoadLines[road.name] = road.geometry
                // Load default pullouts if not already present
                for (po in road.initialPullouts) {
                    if (activePullouts.none { it.id == po.id }) {
                        activePullouts.add(po)
                    }
                }
            }
        }
    }

    fun addPulloutToLoadedMap(rawUtm: Coordinate, latLng: Pair<Double, Double>, selectedRoad: String): LivePullout? {
        val pt = geomFactory.createPoint(rawUtm)
        var matchedRoad: String? = null
        var matchedLine: LineString? = null
        var minDistance = 150.0 // 150 meters corridor buffer for GPS inaccuracies

        synchronized(loadedRoadLines) {
            for ((name, line) in loadedRoadLines) {
                if (name != selectedRoad) continue
                val d = line.distance(pt)
                if (d < minDistance) {
                    minDistance = d
                    matchedRoad = name
                    matchedLine = line
                }
            }
        }

        val road = matchedRoad ?: return null
        val line = matchedLine ?: return null

        val indexedLine = LengthIndexedLine(line)
        val measureMeters = indexedLine.project(rawUtm)
        val snappedUtm = indexedLine.extractPoint(measureMeters)
        val kmPost = Math.round((measureMeters / 1000.0) * 100.0) / 100.0

        val newPullout = LivePullout(
            id = "user_${System.currentTimeMillis()}",
            roadName = road,
            kmMarker = kmPost,
            utmCoord = snappedUtm,
            lat = latLng.first,
            lon = latLng.second,
            isUserCreated = true
        )

        synchronized(activePullouts) {
            activePullouts.add(newPullout)
        }

        savePulloutsToDisk()
        return newPullout
    }

    private fun savePulloutsToDisk() {
        Thread {
            try {
                synchronized(activePullouts) {
                    val userOnly = activePullouts.filter { it.isUserCreated }
                    localSaveFile.writeText(gson.toJson(userOnly))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun loadPersistedPullouts() {
        try {
            if (localSaveFile.exists()) {
                val json = localSaveFile.readText()
                val type = object : TypeToken<List<LivePullout>>() {}.type
                val saved: List<LivePullout>? = gson.fromJson(json, type)
                if (saved != null) {
                    synchronized(activePullouts) {
                        activePullouts.addAll(saved)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
