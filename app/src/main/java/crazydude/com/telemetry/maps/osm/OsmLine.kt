package crazydude.com.telemetry.maps.osm

import crazydude.com.telemetry.maps.MapLine
import crazydude.com.telemetry.maps.Position
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

class OsmLine(private val mapView: MapView) : MapLine() {

    private val line = Polyline(mapView)

    init {
        mapView.overlayManager.add(line)
    }

    /** Makes the line heavier than the ordinary ones, with round ends. */
    fun emphasize() {
        line.outlinePaint.strokeWidth = line.outlinePaint.strokeWidth * 1.8f
        line.outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
    }

    override fun remove() {
        mapView.overlayManager.remove(line)
        mapView.invalidate()
    }

    override fun addPoints(points: List<Position>) {
        points.forEach { line.addPoint(it.toGeoPoint()) }
    }

    override fun setPoint(index: Int, position: Position) {
        val actualPoints = ArrayList(line.actualPoints)
        actualPoints[index] = position.toGeoPoint()
        line.setPoints(actualPoints)
    }

    override fun clear() {
        spoints.clear()
        line.actualPoints.clear()
        mapView.invalidate()
    }

    override fun removeAt(index: Int) {
        line.actualPoints.removeAt(index)
        mapView.invalidate()
    }

    override val size: Int
        get() = line.actualPoints.size
    override var color: Int
        get() = line.color
        set(value) {line.color = value}
}