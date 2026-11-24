package crazydude.com.telemetry.output

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.FlightValues
import crazydude.com.telemetry.operation.FlyMode
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter
import java.text.SimpleDateFormat

class CsvRecorderTest {

    private val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").parse("2026-11-24 14:05:09.250")!!

    @Test
    fun headerIsTodays() {
        assertEquals(25, CsvRecorder.HEADER.size)
        assertEquals("Date,Time,RSSI(dB),1RSS(dB),2RSS(dB),RQly(%),RSNR(dB),ANT,RFMD,TPWR(mW),TRSS(dB),TQly(%),TSNR(dB)," +
                "Ptch(rad),Roll(rad),FM,VSpd(m/s),GPS,GSpd(kmh),Hdg(°),Alt(m),Sats,Curr(A),VFAS(V),Dist(m)",
            CsvRecorder.HEADER.joinToString(","))
    }

    @Test
    fun nothingReceivedIsZero() {
        val row = CsvRecorder.row(FlightValues(), at)
        assertEquals("2026-11-24", row[0])
        assertEquals("14:05:09.250", row[1])
        assertEquals("0", row[2])
        assertEquals("0.0", row[13])
        assertEquals("", row[15])
        assertEquals("0.0 0.0", row[17])
        assertEquals(CsvRecorder.HEADER.size, row.size)
    }

    @Test
    fun valuesGoToTheirColumns() {
        val v = FlightValues(
            rssi = 99, rssiDbm1 = -40, upLq = 100, elrsMode = 2, dnLq = 98, pitch = 0.5f,
            flyMode = FlyMode(true, false, DataDecoder.Companion.FlyMode.ACRO, null),
            position = Position(54.9, 24.4), altitude = 93f, satellites = 26, vbatOrCell = 16.4f, current = 3.5f, distance = 13
        )
        val row = CsvRecorder.row(v, at)
        assertEquals("99", row[2])
        assertEquals("-40", row[3])
        assertEquals("100", row[5])
        assertEquals("2", row[8])
        assertEquals("98", row[11])
        assertEquals("0.5", row[13])
        assertEquals("ACRO", row[15])
        assertEquals("54.9 24.4", row[17])
        assertEquals("93.0", row[20])
        assertEquals("26", row[21])
        assertEquals("3.5", row[22])
        assertEquals("16.4", row[23])
        assertEquals("13", row[24])
    }

    @Test
    fun theBatteryVoltageFallsBackToTheVbatReading() {
        assertEquals("15.2", CsvRecorder.row(FlightValues(voltage = 15.2f), at)[23])
    }

    @Test
    fun writesTheHeaderThenRowsUntilClosed() {
        val out = StringWriter()
        val recorder = CsvRecorder(out) { FlightValues(rssi = 77) }
        Thread.sleep(1500)
        recorder.close()
        val lines = out.toString().trim().lines()
        assertEquals(CsvRecorder.HEADER.joinToString(","), lines[0])
        assertTrue("rows: ${lines.size - 1}", lines.size - 1 in 2..4)
        assertEquals("77", lines[1].split(",")[2])
        val written = out.toString().length
        Thread.sleep(400)
        assertEquals(written, out.toString().length)
    }
}
