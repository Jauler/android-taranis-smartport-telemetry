package crazydude.com.telemetry.operation

import crazydude.com.telemetry.maps.Position
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlightTest {

    @Test
    fun mergeTakesOnlyWhatTheFrameCarries() {
        val values = FlightValues(rssi = 50, voltage = 16.0f)
        values.merge(TelemetryFrame(rssi = 70, current = 3.5f))
        assertEquals(70, values.rssi)
        assertEquals(16.0f, values.voltage)
        assertEquals(3.5f, values.current)
    }

    @Test
    fun mergeCopiesRcChannelsAndLeavesThePositionToTheTrack() {
        val channels = IntArray(16) { 1500 }
        val values = FlightValues()
        values.merge(TelemetryFrame(rcChannels = channels, position = Position(54.0, 24.0)))
        channels[0] = 2000
        assertEquals(1500, values.rcChannels!![0])
        assertNull(values.position)
    }

    @Test
    fun restartForgetsTheTelemetryAndCountsATrackReset() {
        val flight = Flight(Flight.Kind.REPLAY, 0)
        flight.values.rssi = 80
        flight.track.add(TestData.home)
        flight.traveledDistance = 100.0
        flight.home = TestData.home
        flight.lastArrival[Sensor.RSSI.ordinal] = 5
        flight.restart()
        assertNull(flight.values.rssi)
        assertEquals(0, flight.track.size)
        assertEquals(1, flight.trackResets)
        assertEquals(0.0, flight.traveledDistance, 0.0)
        assertNull(flight.home)
        assertEquals(Long.MIN_VALUE, flight.lastArrival[Sensor.RSSI.ordinal])
    }

    @Test
    fun snapshotGivesOnlyTheNewPointsOfTheSameTrack() {
        val flight = Flight(Flight.Kind.LIVE, 0)
        val a = Position(54.0, 24.0)
        val b = Position(54.1, 24.0)
        val c = Position(54.2, 24.0)
        flight.track.addAll(listOf(a, b))
        val first = flight.snapshot(0, 0)
        assertEquals(listOf(a, b), first.newPoints)
        flight.track.add(c)
        val second = flight.snapshot(first.trackResets, first.trackSize)
        assertEquals(listOf(c), second.newPoints)
        assertEquals(2, second.newPointsFrom)
    }

    @Test
    fun snapshotAfterARestartGivesTheWholeTrack() {
        val flight = Flight(Flight.Kind.REPLAY, 0)
        flight.track.addAll(listOf(Position(54.0, 24.0), Position(54.1, 24.0)))
        val known = flight.snapshot(0, 0)
        flight.restart()
        flight.track.add(Position(55.0, 25.0))
        val after = flight.snapshot(known.trackResets, known.trackSize)
        assertEquals(0, after.newPointsFrom)
        assertEquals(1, after.newPoints.size)
    }

    @Test
    fun snapshotIsACopy() {
        val flight = Flight(Flight.Kind.LIVE, 0)
        flight.values.rcChannels = intArrayOf(1, 2, 3)
        val snapshot = flight.snapshot(0, 0)
        flight.values.rssi = 10
        assertNull(snapshot.values.rssi)
        assertArrayEquals(intArrayOf(1, 2, 3), snapshot.values.rcChannels)
    }
}
