package crazydude.com.telemetry.integration

import crazydude.com.telemetry.logger.RcFrameThrottle
import java.nio.ByteBuffer

/** Builds CRSF frames as a flight controller sends them. */
object CrsfFrames {

    fun frame(type: Int, payload: ByteArray): ByteArray {
        val f = byteArrayOf(0xC8.toByte(), (payload.size + 2).toByte(), type.toByte()) + payload + byteArrayOf(0)
        f[f.size - 1] = RcFrameThrottle.crc8(f, 2, f.size - 1).toByte()
        return f
    }

    fun gps(lat: Double, lon: Double, altitudeM: Int, satellites: Int, speedKmh: Double = 0.0, headingDeg: Double = 0.0): ByteArray {
        val b = ByteBuffer.allocate(15)
        b.putInt(Math.round(lat * 1e7).toInt())
        b.putInt(Math.round(lon * 1e7).toInt())
        b.putShort(Math.round(speedKmh * 10).toShort())
        b.putShort(Math.round(headingDeg * 100).toShort())
        b.putShort((altitudeM + 1000).toShort())
        b.put(satellites.toByte())
        return frame(0x02, b.array())
    }

    fun battery(volts: Double, amps: Double, mah: Int): ByteArray {
        val b = ByteBuffer.allocate(8)
        b.putShort(Math.round(volts * 10).toShort())
        b.putShort(Math.round(amps * 10).toShort())
        b.put((mah shr 16).toByte()).put((mah shr 8).toByte()).put(mah.toByte())
        b.put(50)
        return frame(0x08, b.array())
    }

    fun linkStats(rssiDbm: Int, lq: Int, rfMode: Int = 2): ByteArray =
        frame(0x14, byteArrayOf((-rssiDbm).toByte(), (-rssiDbm).toByte(), lq.toByte(), 5, 0, rfMode.toByte(), 3,
            (-rssiDbm).toByte(), lq.toByte(), 4))

    fun flightMode(mode: String): ByteArray = frame(0x21, (mode + "\u0000").toByteArray())

    fun rcChannels(): ByteArray = frame(0x16, ByteArray(22) { 0x55 })
}
