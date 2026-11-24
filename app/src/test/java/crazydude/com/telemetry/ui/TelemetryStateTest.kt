package crazydude.com.telemetry.ui

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.utils.GeoUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryStateTest {

    private val a = Position(54.9226, 24.4711)
    private val b = Position(54.9236, 24.4711)
    private val c = Position(54.9246, 24.4721)
    private val ab = GeoUtils.computeDistanceBetween(a.lat, a.lon, b.lat, b.lon)
    private val bc = GeoUtils.computeDistanceBetween(b.lat, b.lon, c.lat, c.lon)

    private fun withFix() = TelemetryState().also { s -> s.update { it.gpsFix = true } }

    @Test
    fun valuesAreUnknownUntilReceived() {
        val s = TelemetryState().snapshot().values
        assertNull(s.rssi)
        assertNull(s.voltage)
        assertNull(s.armed)
        assertNull(s.position)
        assertEquals(0.0, s.traveledDistance, 0.0)
    }

    @Test
    fun latestValueWins() {
        val state = TelemetryState()
        state.update { it.rssi = 50 }
        state.update { it.rssi = 70 }
        assertEquals(70, state.snapshot().values.rssi)
    }

    @Test
    fun snapshotIsACopy() {
        val state = TelemetryState()
        state.update { it.voltage = 4.1f }
        val snapshot = state.snapshot()
        state.update { it.voltage = 3.5f }
        assertEquals(4.1f, snapshot.values.voltage)
    }

    @Test
    fun rcChannelsAreCopied() {
        val state = TelemetryState()
        val channels = IntArray(16) { 1500 }
        state.setRcChannels(channels)
        channels[0] = 2000  // the decoder reuses its array
        assertEquals(1500, state.snapshot().values.rcChannels!![0])
    }

    @Test
    fun trackAndDistanceWithFix() {
        val state = withFix()
        assertTrue(state.onPosition(a))
        assertTrue(state.onPosition(b))
        assertFalse(state.onPosition(b))
        assertTrue(state.onPosition(c))
        val snapshot = state.snapshot()
        assertEquals(listOf(a, b, c), snapshot.newTrackPoints)
        assertEquals(ab + bc, snapshot.values.traveledDistance, 1e-6)
        assertEquals(c, snapshot.values.position)
        // taken: the next snapshot has only what came after
        state.onPosition(a)
        assertEquals(listOf(a), state.snapshot().newTrackPoints)
    }

    @Test
    fun noTrackWithoutFix() {
        val state = TelemetryState()
        state.onPosition(a)
        state.onPosition(b)
        val snapshot = state.snapshot()
        assertTrue(snapshot.newTrackPoints.isEmpty())
        assertEquals(0.0, snapshot.values.traveledDistance, 0.0)
        assertEquals(b, snapshot.values.position)
    }

    @Test
    fun positionsAppendOrReplace() {
        val state = withFix()
        state.onPosition(a)
        val before = state.snapshot()
        state.onPositions(listOf(b, c), addToEnd = true)
        var snapshot = state.snapshot()
        assertEquals(before.trackResets, snapshot.trackResets)
        assertEquals(listOf(b, c), snapshot.newTrackPoints)
        assertEquals(ab + bc, snapshot.values.traveledDistance, 1e-6)

        state.onPositions(listOf(b, c), addToEnd = false)
        snapshot = state.snapshot()
        assertEquals(before.trackResets + 1, snapshot.trackResets)
        assertEquals(listOf(b, c), snapshot.newTrackPoints)
        assertEquals(bc, snapshot.values.traveledDistance, 1e-6)
    }

    @Test
    fun restartForgetsEverythingButTheConnectionStatus() {
        val state = withFix()
        state.update {
            it.connectionStatus = "Connected"
            it.rssi = 80
            it.armed = true
        }
        state.onPosition(a)
        state.onPosition(b)
        val before = state.snapshot().trackResets
        state.onPosition(c)
        state.restart()
        val snapshot = state.snapshot()
        assertEquals(before + 1, snapshot.trackResets)
        assertTrue(snapshot.newTrackPoints.isEmpty())  // c came before the restart
        assertNull(snapshot.values.rssi)
        assertNull(snapshot.values.armed)
        assertNull(snapshot.values.position)
        assertFalse(snapshot.values.gpsFix)
        assertEquals(0.0, snapshot.values.traveledDistance, 0.0)
        assertEquals("Connected", snapshot.values.connectionStatus)
    }

    @Test
    fun severalRestartsBetweenSnapshotsKeepOnlyTheLastTrack() {
        val state = withFix()
        val first = state.snapshot().trackResets
        state.onPosition(a)
        state.restart()
        state.update { it.gpsFix = true }
        state.onPosition(b)
        state.restart()
        state.update { it.gpsFix = true }
        state.onPosition(c)
        val snapshot = state.snapshot()
        assertTrue(snapshot.trackResets > first)
        assertEquals(listOf(c), snapshot.newTrackPoints)
    }

    @Test
    fun concurrentWritesGiveConsistentSnapshots() {
        val state = withFix()
        val writer = Thread {
            for (i in 1..20000) {
                state.update {
                    it.voltage = i.toFloat()
                    it.cellVoltage = i.toFloat()  // written together, must be read together
                }
                state.onPosition(Position(54.0 + i * 1e-5, 24.0))
                state.setRcChannels(IntArray(16) { i })
            }
        }
        writer.start()
        var points = 0
        while (writer.isAlive) {
            val s = state.snapshot()
            assertEquals(s.values.voltage, s.values.cellVoltage)
            s.values.rcChannels?.let { rc -> assertArrayEquals(IntArray(16) { rc[0] }, rc) }
            points += s.newTrackPoints.size
        }
        writer.join()
        points += state.snapshot().newTrackPoints.size
        assertEquals(20000, points)  // none lost, none twice
    }
}
