package crazydude.com.telemetry.operation

import crazydude.com.telemetry.maps.Position

object TestData {
    val home = Position(54.9226, 24.4711)

    fun aircraft(id: Int, lat: Double = 54.95, lon: Double = 24.50, altMeters: Int = 600, onGround: Boolean = false) =
        Aircraft(id, lat.toFloat(), lon.toFloat(), altMeters, 400, 90, "CS$id", "FN$id", "REG$id", "A320", "VNO → RIX", onGround, 0f)
}
