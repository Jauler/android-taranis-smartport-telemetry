package crazydude.com.telemetry.maps.osm

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import crazydude.com.telemetry.maps.MapLabel
import crazydude.com.telemetry.maps.Position
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** Outlined text drawn just above a map point; stays upright when the map is rotated. */
class OsmLabel(private val text: String, position: Position, private val mapView: MapView) : Overlay(), MapLabel {

    private val geoPoint = position.toGeoPoint()
    private val pixel = Point()
    private val density = mapView.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * mapView.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val outline = Paint(fill).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
    }

    init {
        mapView.overlayManager.add(this)
    }

    override fun draw(canvas: Canvas, osmv: MapView, shadow: Boolean) {
        if (shadow) return
        osmv.projection.toPixels(geoPoint, pixel)
        val x = pixel.x.toFloat()
        val y = pixel.y.toFloat()
        canvas.save()
        canvas.rotate(-osmv.mapOrientation, x, y)
        canvas.drawText(text, x, y - 8f * density, outline)
        canvas.drawText(text, x, y - 8f * density, fill)
        canvas.restore()
    }

    override fun remove() {
        mapView.overlayManager.remove(this)
        mapView.invalidate()
    }
}
