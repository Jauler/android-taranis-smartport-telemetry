package crazydude.com.telemetry.ingestion

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleAssemblerTest {

    private val samples = ArrayList<Sample>()
    private val link = ArrayList<String>()
    private val assembler = SampleAssembler({ samples.add(it) }, object : SampleAssembler.LinkListener {
        override fun onConnected() { link.add("connected") }
        override fun onDisconnected() { link.add("disconnected") }
        override fun onConnectionFailed() { link.add("failed") }
        override fun onProtocolDetected(name: String) { link.add("protocol $name") }
    })

    private fun frames() = samples.filterIsInstance<Sample.Telemetry>().map { it.frame }

    @Test
    fun valuesUntilACommitMakeOneSample() {
        assembler.onGPSState(9, true)
        assembler.onGPSData(54.1, 24.2)
        assembler.onAltitudeData(120f)
        assertTrue(samples.isEmpty())
        assembler.commit()
        val f = frames().single()
        assertEquals(Position(54.1, 24.2), f.position)
        assertEquals(9, f.satellites)
        assertEquals(true, f.gpsFix)
        assertEquals(120f, f.altitude)
        assertNull(f.rssi)
    }

    @Test
    fun nothingNewNothingSent() {
        assembler.commit()
        assertTrue(samples.isEmpty())
    }

    @Test
    fun bytesAloneAreSentForTheRate() {
        repeat(5) { assembler.onTelemetryByte() }
        assembler.commit()
        assertEquals(5, frames().single().bytes)
    }

    @Test
    fun theLatestValueOfABatchWins() {
        assembler.onRSSIData(50)
        assembler.onRSSIData(60)
        assembler.commit()
        assertEquals(60, frames().single().rssi)
    }

    @Test
    fun rcChannelsAreCopied() {
        val channels = IntArray(16) { 1500 }
        assembler.onRCChannels(channels)
        channels[0] = 2000
        assembler.commit()
        assertEquals(1500, frames().single().rcChannels!![0])
    }

    @Test
    fun flightModeIsOneValue() {
        assembler.onFlyModeData(true, false, DataDecoder.Companion.FlyMode.ACRO, null)
        assembler.commit()
        val mode = frames().single().flyMode!!
        assertTrue(mode.armed)
        assertEquals(DataDecoder.Companion.FlyMode.ACRO, mode.mode1)
    }

    @Test
    fun aTrackFlushesWhatCameBeforeIt() {
        assembler.onGPSState(8, true)
        assembler.onGPSData(listOf(Position(1.0, 2.0)), addToEnd = false)
        assertEquals(2, samples.size)
        assertTrue(samples[0] is Sample.Telemetry)
        val track = samples[1] as Sample.Track
        assertTrue(track.replace)
    }

    @Test
    fun restartDropsWhatWasGathered() {
        assembler.onRSSIData(50)
        assembler.onDecoderRestart()
        assembler.commit()
        assertEquals(listOf<Sample>(Sample.Restart), samples)
    }

    @Test
    fun aLiveLinksDecoderRestartForgetsNothing() {
        val live = SampleAssembler({ samples.add(it) }, object : SampleAssembler.LinkListener {
            override fun onConnected() {}
            override fun onDisconnected() {}
            override fun onConnectionFailed() {}
            override fun onProtocolDetected(name: String) {}
        }, restarts = false)
        live.onRSSIData(50)
        live.onDecoderRestart()
        live.commit()
        assertTrue(samples.isEmpty())
    }

    @Test
    fun connectionCallbacksGoToTheLinkListenerAfterAFlush() {
        assembler.onRSSIData(50)
        assembler.onDisconnected()
        assertEquals(1, frames().size)
        assembler.onConnected()
        assembler.onConnectionFailed()
        assembler.onProtocolDetected("CRSF")
        assertEquals(listOf("disconnected", "connected", "failed", "protocol CRSF"), link)
    }
}
