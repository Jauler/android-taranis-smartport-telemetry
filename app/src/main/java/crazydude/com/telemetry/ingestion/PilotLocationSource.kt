package crazydude.com.telemetry.ingestion

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Sample

/**
 * The pilot's position: the phone's GPS (network position until the first fix), as
 * [Sample.PilotPosition]. Used for the antenna of the line of sight and the FlightRadar24 search.
 */
class PilotLocationSource(context: Context, private val sink: (Sample) -> Unit) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            sink(Sample.PilotPosition(Position(location.latitude, location.longitude)))
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** Needs the location permission; does nothing without it. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        try {
            val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { if (locationManager.isProviderEnabled(it)) locationManager.getLastKnownLocation(it) else null }
                .maxBy { it.time }
            last?.let { listener.onLocationChanged(it) }
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, listener, Looper.getMainLooper())
            started = true
        } catch (e: SecurityException) {
            Log.w(TAG, "No location permission yet")
        } catch (e: Exception) {
            Log.w(TAG, "Cannot follow the phone's location", e)
        }
    }

    fun stop() {
        if (!started) return
        locationManager.removeUpdates(listener)
        started = false
    }

    companion object {
        private const val TAG = "PilotLocation"
    }
}
