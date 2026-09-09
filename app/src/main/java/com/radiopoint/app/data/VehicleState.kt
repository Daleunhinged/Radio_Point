package com.radiopoint.app.data

import org.locationtech.jts.geom.Coordinate

enum class VehicleStatus(val code: Int, val label: String) {
    EMPTY(0, "EMPTY"),
    LOADED(1, "LOADED"),
    PICKUP(2, "PICKUP"),
    HAZARD(3, "HAZARD");

    companion object {
        fun fromCode(code: Int): VehicleStatus = entries.find { it.code == code } ?: EMPTY
    }
}

enum class Direction(val code: Int, val label: String) {
    UP(0, "UP"),
    DOWN(1, "DOWN"),
    PARKED(2, "PARKED");

    companion object {
        fun fromCode(code: Int): Direction = entries.find { it.code == code } ?: PARKED
    }
}

data class RemoteTruckState(
    val unitId: Int,
    val status: VehicleStatus,
    val direction: Direction,
    val roadCode: Int,
    val utmCoord: Coordinate,
    val speedKmh: Double,
    val headingDeg: Double,
    val lat: Double,
    val lon: Double,
    val kmMarker: Double,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)
