package crazydude.com.telemetry.logger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class RcFrameThrottleTest {

    private var now = 0L

    private fun frame(type: Int, payload: ByteArray, sync: Int = 0xC8): ByteArray {
        val f = byteArrayOf(sync.toByte(), (payload.size + 2).toByte(), type.toByte()) + payload + byteArrayOf(0)
        f[f.size - 1] = RcFrameThrottle.crc8(f, 2, f.size - 1).toByte()
        return f
    }

    private fun rc(seed: Int) = frame(0x16, ByteArray(22) { (seed + it).toByte() })
    private val linkStats = frame(0x14, byteArrayOf(-50, -60, 100, 5, 0, 2, 3, -70, 99, 4))
    private val battery = frame(0x08, byteArrayOf(0, -88, 0, 12, 0, 1, 44, 75))

    private fun throttle(rate: Int): Pair<RcFrameThrottle, ByteArrayOutputStream> {
        val out = ByteArrayOutputStream()
        return RcFrameThrottle(out, rate) { now } to out
    }

    @Test
    fun keepsAtMostTheRatePerSecond() {
        val (t, out) = throttle(5)
        val expected = ByteArrayOutputStream()
        // 50 frames a second for 2 s, with link statistics after every 5th
        for (i in 0 until 100) {
            now = i * 20L
            t.write(rc(i))
            if (now % 200 == 0L) expected.write(rc(i))
            if (i % 5 == 0) {
                t.write(linkStats)
                expected.write(linkStats)
            }
        }
        t.close()
        assertArrayEquals(expected.toByteArray(), out.toByteArray())
    }

    @Test
    fun unthrottledKeepsEverything() {
        val (t, out) = throttle(0)
        val all = ByteArrayOutputStream()
        for (i in 0 until 50) {
            now = i * 2L
            all.write(rc(i))
            all.write(battery)
        }
        t.write(all.toByteArray())
        t.close()
        assertArrayEquals(all.toByteArray(), out.toByteArray())
    }

    @Test
    fun framesSplitAcrossWrites() {
        val (t, out) = throttle(1)
        val stream = rc(1) + battery + rc(2) + linkStats + rc(3)
        // one byte at a time, all within the same second: only the first RC frame is kept
        stream.forEach { t.write(it.toInt()) }
        t.close()
        assertArrayEquals(rc(1) + battery + linkStats, out.toByteArray())
    }

    @Test
    fun otherBytesGoThroughUnchanged() {
        val (t, out) = throttle(1)
        val noise = ByteArray(5000) { (it * 37 + it / 7).toByte() }  // includes C8 bytes and lengths
        val damaged = rc(9).also { it[10] = (it[10] + 1).toByte() }  // checksum no longer valid
        val notRc = frame(0x16, ByteArray(10))  // RC type, but not the RC frame length
        val otherSync = rc(4).also { it[0] = 0x55 }
        val input = noise + damaged + notRc + otherSync + noise
        t.write(input)
        t.close()
        assertArrayEquals(input, out.toByteArray())
    }

    @Test
    fun aFrameStartingInsideAFalseStartIsFound() {
        val (t, out) = throttle(1)
        // C8 18 16 then garbage and a real frame starting inside the first 26 bytes
        val prefix = byteArrayOf(0xC8.toByte(), 0x18, 0x16, 1, 2, 3)
        t.write(prefix + rc(1) + rc(2))
        t.close()
        assertArrayEquals(prefix + rc(1), out.toByteArray())
    }

    @Test
    fun otherSyncBytesAreRecognized() {
        val (t, out) = throttle(1)
        val ea = frame(0x16, ByteArray(22) { it.toByte() }, sync = 0xEA)
        t.write(ea + ea + ea)
        t.close()
        assertArrayEquals(ea, out.toByteArray())
    }

    @Test
    fun anUnfinishedFrameAtTheEndIsKept() {
        val (t, out) = throttle(5)
        val partial = rc(1).copyOf(12)
        t.write(battery + partial)
        assertEquals(battery.size, out.size())  // held back until it is known
        t.close()
        assertArrayEquals(battery + partial, out.toByteArray())
    }
}
