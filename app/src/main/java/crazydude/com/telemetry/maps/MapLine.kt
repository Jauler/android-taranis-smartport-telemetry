package crazydude.com.telemetry.maps

import crazydude.com.telemetry.utils.GeoUtils

abstract class MapLine {
    abstract fun remove()
    abstract fun addPoints(points: List<Position>)
    abstract fun setPoint(index: Int, position: Position)
    abstract fun clear()
    abstract fun removeAt(index: Int)

    abstract val size: Int
    abstract var color: Int

    private var lastLat = 0.0
    private var lastLon = 0.0

    var spoints: MutableList<Position> = mutableListOf()

    fun submitPoints(points: List<Position>) {
        spoints.addAll(points)
    }

    private fun simplifySPoints(limit: Int) {
        if (size == 0) {
            lastLat = 0.0
            lastLon = 0.0
        }

        var threshold = 5
        if (limit > 1500) {
            if ((size + spoints.size) > 1500) {
                threshold = 10
            } else if ((size + spoints.size) > 3000) {
                threshold = 20
            } else if ((size + spoints.size) > 5000) {
                threshold = 30
            } else if ((size + spoints.size) > 7000) {
                threshold = 100
            }
        }

        spoints = spoints.filter { i ->
            val d = GeoUtils.computeDistanceBetween(lastLat, lastLon, i.lat, i.lon)
            if (d >= threshold) {
                lastLat = i.lat
                lastLon = i.lon
                true
            } else {
                false
            }
        }.toMutableList()
    }

    fun commitPoints(limit: Int) {
        simplifySPoints(limit)
        var toRemove = (size + spoints.size) - limit
        if (toRemove >= size) {
            var fi = spoints.size - limit
            if (fi < 0) fi = 0
            val subList = spoints.subList(fi, spoints.size).toList()
            clear()
            addPoints(subList)
        } else {
            for (i in 1..toRemove) {
                removeAt(0)
            }
            addPoints(spoints)
            spoints.clear()
        }
    }
}
