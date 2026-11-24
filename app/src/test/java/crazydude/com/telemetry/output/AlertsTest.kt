package crazydude.com.telemetry.output

import crazydude.com.telemetry.operation.Event
import crazydude.com.telemetry.operation.TestData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AlertsTest {

    private fun warning(bearing: Double) = Event.TrafficWarning(TestData.aircraft(7, altMeters = 900), 3240.0, bearing)

    @Test
    fun spokenWarning() {
        Locale.setDefault(Locale.US)
        assertEquals("Traffic, north-east, 3.2 kilometers, altitude 900 meters", Alerts.spokenWarning(warning(45.0)))
    }

    @Test
    fun shortWarning() {
        Locale.setDefault(Locale.US)
        assertEquals("TRAFFIC: CS7 3.2km SW, alt 900m", Alerts.shortWarning(warning(225.0)))
    }

    @Test
    fun directionsWrapAround() {
        Locale.setDefault(Locale.US)
        assertEquals("Traffic, north, 3.2 kilometers, altitude 900 meters", Alerts.spokenWarning(warning(350.0)))
        assertEquals("Traffic, north-west, 3.2 kilometers, altitude 900 meters", Alerts.spokenWarning(warning(330.0)))
    }
}
