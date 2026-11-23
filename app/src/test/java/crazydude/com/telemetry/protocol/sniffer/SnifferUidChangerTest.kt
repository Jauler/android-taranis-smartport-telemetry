package crazydude.com.telemetry.protocol.sniffer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnifferUidChangerTest {

    /**
     * Behaves like the sniffer firmware: answers valid GET_UID and SET_UID frames with a report,
     * on another thread, in the middle of telemetry.
     */
    private class FakeSniffer(var uid: ByteArray) : SerialLink {
        @Volatile
        override var rawListener: ((ByteArray) -> Unit)? = null
        var refuse = false
        /** Requests to ignore before answering, like lost frames. */
        var dropRequests = 0
        /** Applies a SET_UID but loses its reply, once. */
        var loseSetReply = false
        val written = ArrayList<ByteArray>()
        var flashWrites = 0

        override fun write(data: ByteArray) {
            written.add(data)
            val isGet = data.contentEquals(SnifferUid.getUidFrame())
            val isSet = data.size == 15 && data.copyOfRange(0, 7)
                .contentEquals(SnifferUid.setUidFrame(ByteArray(6)).copyOfRange(0, 7)) &&
                    data.contentEquals(SnifferUid.setUidFrame(data.copyOfRange(7, 13)))
            if (!isGet && !isSet) return
            if (dropRequests > 0) {
                dropRequests--
                return
            }
            var flags = 0
            if (isSet) {
                val requested = data.copyOfRange(7, 13)
                if (!refuse && SnifferUid.isUsable(requested) && !requested.contentEquals(uid)) {
                    uid = requested
                    flashWrites++
                    flags = flags or SnifferUid.FLAG_CHANGED
                }
                if (loseSetReply) {
                    loseSetReply = false
                    return
                }
            }
            if (SnifferUid.isUsable(uid)) flags = flags or SnifferUid.FLAG_BOUND
            val reply = report(uid, flags)
            val telemetry = byteArrayOf(0xC8.toByte(), 0x04, 0x14, 0x01, 0x02, 0x00)
            Thread {
                Thread.sleep(20)
                rawListener?.invoke(telemetry + reply.copyOfRange(0, 6))
                rawListener?.invoke(reply.copyOfRange(6, reply.size) + telemetry)
            }.start()
        }

        private fun report(uid: ByteArray, flags: Int): ByteArray {
            val frame = byteArrayOf(0xC8.toByte(), 0x0E, 0x32, 0xC8.toByte(), 0xEC.toByte(), 0x10, 0xE2.toByte()) +
                    uid + byteArrayOf(flags.toByte(), 0, 0)
            frame[14] = SnifferUid.crc8(frame, 2, 14, SnifferUid.POLY_COMMAND).toByte()
            frame[15] = SnifferUid.crc8(frame, 2, 15, SnifferUid.POLY_FRAME).toByte()
            return frame
        }
    }

    private val alice = SnifferUid.fromPhrase("alice phrase")
    private val bob = SnifferUid.fromPhrase("bob phrase")

    private fun changer(link: SerialLink) = SnifferUidChanger(link, replyTimeoutMs = 200, commandIntervalMs = 10)

    @Test
    fun switchesToAnotherUid() {
        val sniffer = FakeSniffer(alice)
        val result = changer(sniffer).apply(bob)
        assertTrue(result is SnifferUidChanger.Result.Done)
        assertTrue((result as SnifferUidChanger.Result.Done).changed)
        assertTrue(result.uid.contentEquals(bob))
        assertTrue(sniffer.uid.contentEquals(bob))
        assertEquals(2, sniffer.written.size)  // GET_UID, SET_UID
        assertEquals(null, sniffer.rawListener)
    }

    @Test
    fun sameUidIsNotWrittenAgain() {
        val sniffer = FakeSniffer(bob)
        val result = changer(sniffer).apply(bob)
        assertTrue(result is SnifferUidChanger.Result.Done)
        assertFalse((result as SnifferUidChanger.Result.Done).changed)
        assertEquals(1, sniffer.written.size)  // GET_UID only
        assertEquals(0, sniffer.flashWrites)
    }

    @Test
    fun unboundSnifferIsSet() {
        val sniffer = FakeSniffer(ByteArray(6))
        assertTrue(changer(sniffer).apply(alice) is SnifferUidChanger.Result.Done)
        assertTrue(sniffer.uid.contentEquals(alice))
    }

    @Test
    fun refusedInWifiMode() {
        val sniffer = FakeSniffer(alice)
        sniffer.refuse = true
        val result = changer(sniffer).apply(bob)
        assertTrue(result is SnifferUidChanger.Result.Refused)
        assertTrue((result as SnifferUidChanger.Result.Refused).uid.contentEquals(alice))
    }

    @Test
    fun noReply() {
        val sniffer = FakeSniffer(alice)
        sniffer.dropRequests = 100
        assertTrue(changer(sniffer).apply(bob) is SnifferUidChanger.Result.NoReply)
        assertEquals(3, sniffer.written.size)  // GET_UID 3 times, no SET_UID without knowing
    }

    @Test
    fun retriesLostRequests() {
        val sniffer = FakeSniffer(alice)
        sniffer.dropRequests = 2
        assertTrue(changer(sniffer).apply(bob) is SnifferUidChanger.Result.Done)
        assertEquals(4, sniffer.written.size)  // GET_UID 3 times, SET_UID
    }

    @Test
    fun lostReplyOfASuccessfulSet() {
        // the retry finds the UID set already (CHANGED 0), which is still a success
        val sniffer = FakeSniffer(alice)
        sniffer.loseSetReply = true
        val result = changer(sniffer).apply(bob)
        assertTrue(result is SnifferUidChanger.Result.Done)
        assertTrue((result as SnifferUidChanger.Result.Done).changed)
        assertEquals(1, sniffer.flashWrites)
    }

    @Test
    fun allZeroUidIsNotSent() {
        val sniffer = FakeSniffer(alice)
        assertTrue(changer(sniffer).apply(SnifferUid.fromPhrase("0,0,0,0")) is SnifferUidChanger.Result.Failed)
        assertEquals(0, sniffer.written.size)
    }
}
