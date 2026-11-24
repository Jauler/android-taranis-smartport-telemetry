package crazydude.com.telemetry.logger

import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream

/**
 * Writes the raw telemetry log (*.tlm) with fewer CRSF RC channels frames: an ELRS receiver
 * listening to a link sends them at the packet rate (50-1000 a second), and they would make up
 * most of the log.
 *
 * Only complete CRSF RC channels frames with a valid checksum are dropped, so that at most
 * [maxPerSecond] of them are kept. Every other byte goes through as it is: the other frames,
 * other protocols, anything not recognized. The log stays a valid stream for replay.
 *
 * Not thread safe: written by the one thread which reads the link.
 *
 * @param maxPerSecond 0: every frame is kept
 */
class RcFrameThrottle(
    out: OutputStream,
    maxPerSecond: Int,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) : FilterOutputStream(out) {

    private val intervalMs = if (maxPerSecond > 0) 1000L / maxPerSecond else 0L
    private var lastKeptMs = Long.MIN_VALUE

    // the start of what may be an RC channels frame, held back until it is complete
    private val pending = ByteArray(FRAME_SIZE)
    private var pendingSize = 0
    private val output = ByteArrayOutputStream(1024)

    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (intervalMs == 0L) {
            out.write(b, off, len)
            return
        }
        for (i in off until off + len) feed(b[i])
        if (output.size() > 0) {
            output.writeTo(out)
            output.reset()
        }
    }

    private fun feed(byte: Byte) {
        val value = byte.toInt() and 0xFF
        when (pendingSize) {
            0 -> if (value in SYNC_BYTES) hold(byte) else output.write(value)
            1 -> if (value == LENGTH) hold(byte) else resync(byte)
            2 -> if (value == TYPE_RC_CHANNELS) hold(byte) else resync(byte)
            else -> {
                hold(byte)
                if (pendingSize == FRAME_SIZE) onFrame()
            }
        }
    }

    private fun hold(byte: Byte) {
        pending[pendingSize++] = byte
    }

    /** Not a frame after all: the first byte goes out, the rest is looked at again. */
    private fun resync(next: Byte) {
        val rest = pending.copyOfRange(1, pendingSize)
        output.write(pending[0].toInt() and 0xFF)
        pendingSize = 0
        rest.forEach { feed(it) }
        feed(next)
    }

    private fun onFrame() {
        if (crc8(pending, 2, FRAME_SIZE - 1) != (pending[FRAME_SIZE - 1].toInt() and 0xFF)) {
            // looked like one, but is not: maybe a frame starts inside it
            val rest = pending.copyOfRange(1, FRAME_SIZE)
            output.write(pending[0].toInt() and 0xFF)
            pendingSize = 0
            rest.forEach { feed(it) }
            return
        }
        val now = clockMs()
        if (lastKeptMs == Long.MIN_VALUE || now - lastKeptMs >= intervalMs) {
            lastKeptMs = now
            output.write(pending, 0, FRAME_SIZE)
        }
        pendingSize = 0
    }

    override fun flush() {
        out.flush()
    }

    override fun close() {
        // an unfinished frame at the end is kept as it is
        if (pendingSize > 0) {
            out.write(pending, 0, pendingSize)
            pendingSize = 0
        }
        out.close()
    }

    companion object {
        /** The start bytes the CRSF decoder accepts (CrsfProtocol). */
        private val SYNC_BYTES = setOf(0xC8, 0xEA, 0xEC, 0xEE)
        private const val TYPE_RC_CHANNELS = 0x16
        /** type + 22 bytes of channels + crc */
        private const val LENGTH = 24
        private const val FRAME_SIZE = LENGTH + 2

        /** CRSF CRC-8 (polynomial 0xD5) over [from, to). */
        fun crc8(data: ByteArray, from: Int, to: Int): Int {
            var crc = 0
            for (i in from until to) {
                crc = crc xor (data[i].toInt() and 0xFF)
                repeat(8) {
                    crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0xD5) and 0xFF else (crc shl 1) and 0xFF
                }
            }
            return crc
        }
    }
}
