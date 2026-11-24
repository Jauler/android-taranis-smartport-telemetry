package crazydude.com.telemetry.protocol.pollers

import crazydude.com.telemetry.protocol.*
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import java.io.File
import java.io.RandomAccessFile

/**
 * A live connection played from a recorded telemetry log (*.tlm), for testing without a radio:
 * the bytes go through protocol detection and decoding exactly like those of a real link, at a
 * steady pace which plays the whole file in [durationMs].
 *
 * [disconnect] behaves like a lost link; [position] tells where to continue on a reconnect.
 * Only used by the debug commands of the data service.
 */
class FileDataPoller(
    private val listener: DataDecoder.Listener,
    private val file: File,
    startPosition: Long,
    durationMs: Long
) : DataPoller {

    @Volatile
    var position: Long = startPosition
        private set

    @Volatile
    private var running = true
    private var selectedProtocol: Protocol? = null

    private val thread = Thread({ run(durationMs) }, "file-poller")

    init {
        thread.start()
    }

    private fun run(durationMs: Long) {
        val input = try {
            RandomAccessFile(file, "r")
        } catch (e: Exception) {
            listener.onConnectionFailed()
            return
        }
        listener.onConnected()
        val detector = ProtocolDetector(object : ProtocolDetector.Callback {
            override fun onProtocolDetected(protocol: Protocol?) {
                selectedProtocol = when (protocol) {
                    is CrsfProtocol -> CrsfProtocol(listener)
                    is MAVLinkProtocol -> MAVLinkProtocol(listener)
                    is MAVLink2Protocol -> MAVLink2Protocol(listener)
                    else -> null
                }
                selectedProtocol?.let { listener.onProtocolDetected(it.javaClass.simpleName) }
            }
        })
        val length = file.length()
        val bytesPerTick = Math.max(1L, length * TICK_MS / Math.max(1L, durationMs)).toInt()
        val buffer = ByteArray(bytesPerTick)
        input.use {
            it.seek(position)
            while (running) {
                val n = it.read(buffer)
                if (n <= 0) break  // end of the log: stays connected, like an aircraft on the ground
                for (i in 0 until n) {
                    val b = buffer[i].toUByte().toInt()
                    listener.onTelemetryByte()
                    val protocol = selectedProtocol
                    if (protocol != null) protocol.process(b) else detector.feedData(b)
                }
                listener.commit()
                position += n
                Thread.sleep(TICK_MS)
            }
        }
    }

    override fun disconnect() {
        if (!running) return
        running = false
        thread.join(1000)
        listener.onDisconnected()
    }

    companion object {
        private const val TICK_MS = 20L
    }
}
