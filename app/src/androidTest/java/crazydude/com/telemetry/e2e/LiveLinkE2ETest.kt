package crazydude.com.telemetry.e2e

import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hoho.android.usbserial.driver.UsbSerialProber
import crazydude.com.telemetry.operation.Event
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.LinkStatus
import crazydude.com.telemetry.service.DataService
import crazydude.com.telemetry.ui.MapsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import androidx.test.rule.GrantPermissionRule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * Live flights: a recorded flight played as a live link (the debug file link), and the real
 * receiver on USB when one is plugged in.
 */
@RunWith(AndroidJUnit4::class)
class LiveLinkE2ETest {

    @get:Rule
    val storage: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private lateinit var service: DataService
    private val events = CopyOnWriteArrayList<Event>()
    private var subscription: AutoCloseable? = null

    @Before
    fun setUp() {
        service = E2E.service()
        E2E.onMain {
            service.closeReplay()
            service.disconnect()
        }
        subscription = service.operation.subscribeEvents(Executor { it.run() }) { events.add(it) }
    }

    @After
    fun tearDown() {
        subscription?.close()
        E2E.onMain { service.disconnect() }
        E2E.unbind()
    }

    private fun connectFileLink(fromPercent: Int) {
        val log = E2E.flightLog()
        assumeTrue("no .tlm on the device", log != null && log.exists())
        E2E.link("connect") {
            putExtra("file", log!!.name)
            putExtra("duration", 120)
            putExtra("from", fromPercent)
        }
        E2E.waitFor("the link to come up") { E2E.flight(service)?.link == LinkStatus.CONNECTED }
    }

    @Test
    fun aLostLinkKeepsTheFlightAndTheReconnectionContinuesIt() {
        connectFileLink(40)
        E2E.waitFor("a track") { (E2E.flight(service)?.track?.size ?: 0) >= 5 }

        E2E.link("drop")
        E2E.waitFor("the link loss") { E2E.flight(service)?.link == LinkStatus.LOST }
        val lost = E2E.flight(service)!!
        assertEquals(Flight.Kind.LIVE, lost.kind)
        val trackAtLoss = lost.track.size
        val distanceAtLoss = lost.traveledDistance
        assertTrue(service.isConnected())

        E2E.link("reconnect")
        E2E.waitFor("the link back") { E2E.flight(service)?.link == LinkStatus.CONNECTED }
        E2E.waitFor("the track to go on") { (E2E.flight(service)?.track?.size ?: 0) > trackAtLoss + 3 }
        assertTrue("nothing forgotten", E2E.flight(service)!!.traveledDistance > distanceAtLoss)
        assertTrue(events.contains(Event.LinkLost))

        E2E.link("disconnect")
        E2E.waitFor("the flight to end") { E2E.flight(service) == null }
        assertTrue(events.contains(Event.Disconnected))
    }

    @Test
    fun theFlightGoesOnWithoutAScreen() {
        val scenario = ActivityScenario.launch<MapsActivity>(Intent(E2E.context, MapsActivity::class.java))
        connectFileLink(20)
        scenario.close()  // the map screen is gone
        val samples = E2E.flight(service)!!.track.size
        E2E.waitFor("the track to grow with no screen") { (E2E.flight(service)?.track?.size ?: 0) > samples + 3 }
        assertNotNull(E2E.flight(service)!!.values.flyMode)
    }

    @Test
    fun theScreenTakesUpARunningFlight() {
        connectFileLink(30)
        E2E.waitFor("a track") { (E2E.flight(service)?.track?.size ?: 0) >= 3 }
        val scenario = ActivityScenario.launch<MapsActivity>(Intent(E2E.context, MapsActivity::class.java))
        Thread.sleep(2000)
        scenario.onActivity { activity ->
            val button = activity.findViewById<android.widget.Button>(crazydude.com.telemetry.R.id.connect_button)
            assertEquals(activity.getString(crazydude.com.telemetry.R.string.disconnect), button.text.toString())
            val traveled = activity.findViewById<android.widget.TextView>(crazydude.com.telemetry.R.id.traveled_distance)
            assertTrue("travelled distance shown: ${traveled.text}", traveled.text.toString() != "0 m")
        }
        scenario.close()
    }

    @Test
    fun theRealReceiverOnUsb() {
        val usb = E2E.context.getSystemService(android.content.Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()
        assumeTrue("no USB serial adapter", driver != null)
        assumeTrue("no USB permission for it", usb.hasPermission(driver!!.device))
        val connection = usb.openDevice(driver.device)
        assumeTrue("cannot open the adapter", connection != null)
        E2E.onMain { service.connect(driver.ports.first(), connection) }
        E2E.waitFor("the receiver's link") { E2E.flight(service)?.link == LinkStatus.CONNECTED }
        E2E.waitFor("CRSF to be detected", 10_000) { E2E.flight(service)?.protocol != null }
        E2E.waitFor("link statistics", 10_000) { E2E.flight(service)?.values?.upLq != null }
        E2E.onMain { service.disconnect() }
        E2E.waitFor("the flight to end") { E2E.flight(service) == null }
        assertNull(E2E.flight(service))
    }
}
