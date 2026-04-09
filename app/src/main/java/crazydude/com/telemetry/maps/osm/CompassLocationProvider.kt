package crazydude.com.telemetry.maps.osm

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

class CompassLocationProvider(private val context: Context) : IMyLocationProvider, SensorEventListener {

    private val gpsProvider = GpsMyLocationProvider(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var compassBearing: Float = 0f
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var hasGravity = false
    private var hasGeomagnetic = false
    private var consumer: IMyLocationConsumer? = null

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        return gpsProvider.startLocationProvider { location, source ->
            myLocationConsumer?.onLocationChanged(injectBearing(location), source)
        }
    }

    override fun stopLocationProvider() {
        sensorManager.unregisterListener(this)
        gpsProvider.stopLocationProvider()
        consumer = null
    }

    override fun getLastKnownLocation(): Location? {
        return gpsProvider.lastKnownLocation?.let { injectBearing(it) }
    }

    override fun destroy() {
        stopLocationProvider()
        gpsProvider.destroy()
    }

    private fun injectBearing(location: Location?): Location? {
        if (location == null) return null
        location.bearing = compassBearing
        return location
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                hasGravity = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                hasGeomagnetic = true
            }
        }
        if (hasGravity && hasGeomagnetic) {
            val r = FloatArray(9)
            if (SensorManager.getRotationMatrix(r, null, gravity, geomagnetic)) {
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                compassBearing = Math.toDegrees(orientation[0].toDouble()).toFloat()
                if (compassBearing < 0) compassBearing += 360f
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
