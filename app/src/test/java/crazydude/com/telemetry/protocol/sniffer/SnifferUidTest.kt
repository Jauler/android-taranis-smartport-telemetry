package crazydude.com.telemetry.protocol.sniffer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test vectors of the sniffer's UART specification (changing-binding-phrase-over-uart.md). */
class SnifferUidTest {

    private fun hex(text: String): ByteArray =
        text.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun uid(vararg bytes: Int): ByteArray = bytes.map { it.toByte() }.toByteArray()

    @Test
    fun crcCheckValues() {
        val data = "123456789".toByteArray()
        assertEquals(0x20, SnifferUid.crc8(data, 0, data.size, SnifferUid.POLY_COMMAND))
        assertEquals(0xBC, SnifferUid.crc8(data, 0, data.size, SnifferUid.POLY_FRAME))
    }

    @Test
    fun phrasesAreHashed() {
        assertEquals("133,75,181,162,104,215", SnifferUid.format(SnifferUid.fromPhrase("test phrase")))
        assertEquals("67,127,47,177,211,57", SnifferUid.format(SnifferUid.fromPhrase("ExpressLRS")))
        // UTF-8, checked against src/python/sniffer_uid.py
        assertEquals("222,43,119,46,14,116", SnifferUid.format(SnifferUid.fromPhrase("\u0104\u017euolas pilot")))
        // surrounding whitespace does not count
        assertEquals("67,127,47,177,211,57", SnifferUid.format(SnifferUid.fromPhrase("  ExpressLRS \n")))
    }

    @Test
    fun literalUids() {
        assertArrayEquals(uid(0, 0, 1, 2, 3, 4), SnifferUid.fromPhrase("1,2,3,4"))
        assertArrayEquals(uid(0, 1, 2, 3, 4, 5), SnifferUid.fromPhrase("1, 2, 3, 4, 5"))
        assertArrayEquals(uid(10, 20, 30, 40, 50, 255), SnifferUid.fromPhrase("10,20,30,40,50,255"))
        // not literal: hashed like any phrase
        assertEquals(SnifferUid.fromPhrase("x").size, 6)
        assertFalse(SnifferUid.fromPhrase("1,2,3").contentEquals(uid(0, 0, 0, 1, 2, 3)))
        assertFalse(SnifferUid.fromPhrase("1,2,3,256").contentEquals(uid(0, 0, 1, 2, 3, 0)))
        assertFalse(SnifferUid.fromPhrase("1,2,3,4,5,6,7").contentEquals(uid(2, 3, 4, 5, 6, 7)))
    }

    @Test
    fun allZeroUidIsNotUsable() {
        assertFalse(SnifferUid.isUsable(SnifferUid.fromPhrase("0,0,0,0")))
        assertFalse(SnifferUid.isUsable(uid(7, 7, 0, 0, 0, 0)))
        assertTrue(SnifferUid.isUsable(SnifferUid.fromPhrase("0,0,0,1")))
        assertTrue(SnifferUid.isUsable(SnifferUid.fromPhrase("test phrase")))
    }

    @Test
    fun frames() {
        assertArrayEquals(
            hex("C8 0D 32 EC C8 10 E0 85 4B B5 A2 68 D7 A2 C9"),
            SnifferUid.setUidFrame(SnifferUid.fromPhrase("test phrase"))
        )
        assertArrayEquals(
            hex("C8 0D 32 EC C8 10 E0 43 7F 2F B1 D3 39 8C B2"),
            SnifferUid.setUidFrame(SnifferUid.fromPhrase("ExpressLRS"))
        )
        assertArrayEquals(
            hex("C8 0D 32 EC C8 10 E0 00 00 01 02 03 04 84 6A"),
            SnifferUid.setUidFrame(SnifferUid.fromPhrase("1,2,3,4"))
        )
        assertArrayEquals(hex("C8 07 32 EC C8 10 E1 5C 44"), SnifferUid.getUidFrame())
    }

    private val report = hex("C8 0E 32 C8 EC 10 E2 85 4B B5 A2 68 D7 03 7E 12")

    private fun parse(stream: ByteArray): List<SnifferUid.Report> {
        val reports = ArrayList<SnifferUid.Report>()
        SnifferUid.ReportParser { reports.add(it) }.feed(stream)
        return reports
    }

    @Test
    fun parsesTheReport() {
        val reports = parse(report)
        assertEquals(1, reports.size)
        assertEquals("133,75,181,162,104,215", SnifferUid.format(reports[0].uid))
        assertTrue(reports[0].changed)
        assertTrue(reports[0].bound)
    }

    @Test
    fun findsTheReportAmongTelemetry() {
        val linkStats = hex("C8 0C 14 10 0A 64 05 00 01 02 00 00 00 00 00")
        // the telemetry frame's checksum is wrong on purpose, and C8 bytes inside it must not confuse
        val noise = hex("00 C8 C8 05 C8 01 02 03 04 FF")
        val stream = noise + linkStats + report.copyOfRange(0, 5) + report.copyOfRange(5, report.size) + noise
        val reports = parse(stream)
        assertEquals(1, reports.size)
        assertEquals("133,75,181,162,104,215", SnifferUid.format(reports[0].uid))
    }

    @Test
    fun ignoresLookalikes() {
        // the SET_UID request itself (heard over the air, or echoed)
        assertEquals(0, parse(SnifferUid.setUidFrame(SnifferUid.fromPhrase("test phrase"))).size)
        // a wrong frame checksum
        val badFrame = report.copyOf().also { it[15] = (it[15] + 1).toByte() }
        assertEquals(0, parse(badFrame).size)
        // a right frame checksum, but a wrong command checksum
        val badCommand = report.copyOf()
        badCommand[14] = (badCommand[14] + 1).toByte()
        badCommand[15] = SnifferUid.crc8(badCommand, 2, 15, SnifferUid.POLY_FRAME).toByte()
        assertEquals(0, parse(badCommand).size)
        // another origin than the receiver
        val otherOrigin = report.copyOf()
        otherOrigin[4] = 0xEE.toByte()
        otherOrigin[14] = SnifferUid.crc8(otherOrigin, 2, 14, SnifferUid.POLY_COMMAND).toByte()
        otherOrigin[15] = SnifferUid.crc8(otherOrigin, 2, 15, SnifferUid.POLY_FRAME).toByte()
        assertEquals(0, parse(otherOrigin).size)
        // a damaged frame does not hide the report right after it
        assertEquals(1, parse(badFrame + report).size)
    }
}
