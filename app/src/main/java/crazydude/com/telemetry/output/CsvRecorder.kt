package crazydude.com.telemetry.output

import crazydude.com.telemetry.operation.FlightValues
import java.io.File
import java.io.FileWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Timer
import java.util.TimerTask

/**
 * Writes the flight to a CSV file in the OpenTX format, one row every 200 ms (after a first
 * second), as long as the flight lasts. The format is the one the app has always written.
 *
 * @param values the flight's latest values, read for each row
 */
class CsvRecorder(private val writer: Writer, private val values: () -> FlightValues?) {

    private val timer = Timer("csv")
    // made once: building them for every row costs more than the row itself
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd")
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS")
    @Volatile
    private var closed = false

    init {
        writeLine(HEADER)
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                values()?.let { writeLine(row(it, Date(), dateFormat, timeFormat)) }
            }
        }, 1000, 200)
    }

    @Synchronized
    private fun writeLine(line: List<String>) {
        if (closed) return
        try {
            writer.append(line.joinToString(","))
            writer.append("\n")
        } catch (e: Exception) {
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        timer.cancel()
        try {
            writer.close()
        } catch (e: Exception) {
        }
    }

    companion object {
        val HEADER = listOf(
            "Date", "Time", "RSSI(dB)", "1RSS(dB)", "2RSS(dB)", "RQly(%)", "RSNR(dB)", "ANT", "RFMD", "TPWR(mW)",
            "TRSS(dB)", "TQly(%)", "TSNR(dB)", "Ptch(rad)", "Roll(rad)", "FM", "VSpd(m/s)", "GPS", "GSpd(kmh)",
            "Hdg(°)", "Alt(m)", "Sats", "Curr(A)", "VFAS(V)", "Dist(m)"
        )

        fun open(file: File, values: () -> FlightValues?) = CsvRecorder(FileWriter(file), values)

        /** One row, with 0 for what was not received, as always. */
        fun row(
            v: FlightValues,
            at: Date,
            dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd"),
            timeFormat: SimpleDateFormat = SimpleDateFormat("HH:mm:ss.SSS")
        ): List<String> {
            val position = v.position
            return listOf(
                dateFormat.format(at),
                timeFormat.format(at),
                (v.rssi ?: 0).toString(),
                (v.rssiDbm1 ?: 0).toString(),
                (v.rssiDbm2 ?: 0).toString(),
                (v.upLq ?: 0).toString(),
                (v.upSnr ?: 0).toString(),
                (v.antenna ?: 0).toString(),
                (v.elrsMode ?: 0).toString(),
                (v.power ?: 0).toString(),
                (v.rssiDbmd ?: 0).toString(),
                (v.dnLq ?: 0).toString(),
                (v.dnSnr ?: 0).toString(),
                (v.pitch ?: 0f).toString(),
                (v.roll ?: 0f).toString(),
                v.flyMode?.mode1?.toString() ?: (if (v.flyMode != null) "null" else ""),
                (v.vSpeed ?: 0f).toString(),
                "${position?.lat ?: 0.0} ${position?.lon ?: 0.0}",
                (v.speed ?: 0f).toString(),
                (v.heading ?: 0f).toString(),
                (v.altitude ?: 0f).toString(),
                (v.satellites ?: 0).toString(),
                (v.current ?: 0f).toString(),
                (v.vbatOrCell ?: v.voltage ?: 0f).toString(),
                (v.distance ?: 0).toString()
            )
        }
    }
}
