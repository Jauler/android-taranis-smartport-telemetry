package crazydude.com.telemetry.ingestion

import android.os.Handler
import crazydude.com.telemetry.manager.PreferenceManager
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.protocol.pollers.LogPlayer
import java.io.File

/**
 * A recorded flight (*.tlm) played as a source: its samples go through the flight operation like
 * a live link's, so the terrain readouts and everything else derived work in replay too. Lives
 * from starting the replay to closing it. Called on the main thread.
 */
class ReplaySession(
    val file: File,
    private val operation: FlightOperation,
    private val preferenceManager: PreferenceManager,
    private val mainHandler: Handler
) {

    private val assembler = SampleAssembler({ operation.submit(it) }, object : SampleAssembler.LinkListener {
        override fun onConnected() {}
        override fun onDisconnected() {}
        override fun onConnectionFailed() {}
        override fun onProtocolDetected(name: String) = operation.submit(Sample.Protocol(name))
    })

    val player = LogPlayer(assembler)

    /** Percent of the file decoded while loading. */
    @Volatile
    var loadPercent = 0
        private set
    /** Records in the log; -1 while loading. */
    var size = -1
        private set
    /** Where the aircraft first is with a GPS fix and a heading, to show the map there; null: not found yet. */
    var focus: Position? = null
        private set
    val position: Int
        get() = player.currentPosition
    val playing: Boolean
        get() = player.isPlaying()

    fun start() {
        operation.openFlight(Flight.Kind.REPLAY)
        player.load(file, object : LogPlayer.DataReadyListener {
            override fun onUpdate(percent: Int) {
                loadPercent = percent
            }

            override fun onDataReady(size: Int) {
                this@ReplaySession.size = size
                findFocus()
                if (preferenceManager.getPlaybackAutostart()) player.startPlayback()
            }

            override fun onPlaybackPositionChange(prevPosition: Int, nextPosition: Int) {
                // on the player's timer thread: the seek happens on the main thread
                mainHandler.post {
                    if (player.currentPosition == prevPosition) seek(nextPosition)
                }
            }

            override fun onPlaybackStateChange(isPlaying: Boolean) {}
            override fun getTotalPlaybackDurationSec(): Int = preferenceManager.getPlaybackDuration()
            override fun getPlaybackAutostart(): Boolean = false  // started above, after the focus is found
            override fun onProtocolDetected(protocolName: String) = operation.submit(Sample.Protocol(protocolName))
        })
    }

    /** Plays the log up to its first position with a fix and a heading, then back to the start. */
    private fun findFocus() {
        operation.setRebuilding(true)
        try {
            for (i in 0 until size) {
                player.seek(i)
                val found = operation.read { flight, _ ->
                    val v = flight?.values
                    if (v != null && v.gpsFix && v.position != null && v.heading != null) v.position else null
                }
                if (found != null) {
                    focus = found
                    break
                }
            }
            player.seek(0)
        } finally {
            operation.setRebuilding(false)
        }
    }

    /** Moves to [position]; backwards, the flight is rebuilt from the start, quietly. */
    fun seek(position: Int) {
        val back = position < player.currentPosition
        if (back) operation.setRebuilding(true)
        try {
            player.seek(position)
        } finally {
            if (back) operation.setRebuilding(false)
        }
    }

    fun play() {
        if (size >= 0) player.startPlayback()
    }

    fun pause() {
        player.stop()
    }

    fun close() {
        player.stop()
        operation.closeFlight()
    }
}
