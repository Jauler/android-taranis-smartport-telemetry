package crazydude.com.telemetry.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.service.DataService
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

/** A recorded flight replayed through the data service, as from the replay button. */
@RunWith(AndroidJUnit4::class)
class ReplayE2ETest {

    @get:Rule
    val storage: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private lateinit var service: DataService

    @Before
    fun setUp() {
        service = E2E.service()
        E2E.onMain { service.disconnect() }
    }

    @After
    fun tearDown() {
        E2E.onMain { service.closeReplay() }
        E2E.unbind()
    }

    @Test
    fun aReplayedFlightGoesThroughTheFlightOperation() {
        val log = E2E.flightLog()
        assumeTrue("no .tlm on the device", log != null && log.exists())
        E2E.onMain { service.startReplay(log!!) }
        E2E.waitFor("the log to load", 60_000) { service.replay?.size ?: -1 >= 0 }
        val replay = service.replay!!
        E2E.onMain { replay.pause() }
        assertEquals(Flight.Kind.REPLAY, E2E.flight(service)?.kind)

        // to the end, as the playback would
        E2E.onMain { replay.seek(replay.size) }
        val end = E2E.flight(service)!!
        assumeTrue("the log has no GPS fix", end.track.isNotEmpty())
        assertTrue(end.traveledDistance > 0)
        assertNotNull("the replay's home is its first fix", end.home)
        assertEquals(end.track.first(), end.home)

        // the flight is live data: keep the numbers before it changes
        val endTrack = end.track.size
        val endDistance = end.traveledDistance
        val home = end.home

        // back to the middle: rebuilt from the start, shorter, same home
        E2E.onMain { replay.seek(replay.size / 2) }
        val middle = E2E.flight(service)!!
        assertTrue(middle.track.size < endTrack)
        assertTrue(middle.traveledDistance < endDistance)
        assertEquals(home, middle.home)
    }

    @Test
    fun closingTheReplayEndsItsFlight() {
        val log = E2E.flightLog()
        assumeTrue("no .tlm on the device", log != null && log.exists())
        E2E.onMain { service.startReplay(log!!) }
        E2E.waitFor("the log to load", 60_000) { service.replay?.size ?: -1 >= 0 }
        E2E.onMain { service.closeReplay() }
        assertNull(service.replay)
        assertNull(E2E.flight(service))
    }
}
