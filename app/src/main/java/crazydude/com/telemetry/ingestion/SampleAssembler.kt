package crazydude.com.telemetry.ingestion

import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.operation.FlyMode
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.operation.TelemetryFrame
import crazydude.com.telemetry.protocol.decoder.DataDecoder

/**
 * Turns the decoders' value-by-value callbacks into whole samples for the flight operation.
 *
 * The values are gathered until [commit]: the pollers commit after each batch of bytes they
 * read, the replay after each step. A frame's values are decoded together, when its last byte
 * arrives, so a commit never splits a frame: no sample has a new latitude with an old longitude.
 *
 * Connection callbacks go to [linkListener], which decides what they mean (a lost link or a
 * manual disconnect).
 *
 * @param restarts whether a decoder restart is a [Sample.Restart]: for a replay, played again
 * from its start. A live link gets a new decoder on every connection, also when it is found again
 * after it was lost: the flight goes on then, nothing is forgotten.
 */
class SampleAssembler(
    private val sink: (Sample) -> Unit,
    private val linkListener: LinkListener,
    private val restarts: Boolean = true
) : DataDecoder.Listener {

    interface LinkListener {
        fun onConnected()
        fun onDisconnected()
        fun onConnectionFailed()
        fun onProtocolDetected(name: String)
    }

    private val lock = Any()
    private var frame = TelemetryFrame()
    private var changed = false
    private var bytes = 0

    private fun update(change: (TelemetryFrame) -> TelemetryFrame) {
        synchronized(lock) {
            frame = change(frame)
            changed = true
        }
    }

    /** Hands over what was gathered, as one sample. */
    override fun commit() {
        val sample: Sample.Telemetry
        synchronized(lock) {
            if (!changed && bytes == 0) return
            sample = Sample.Telemetry(frame.copy(bytes = bytes))
            frame = TelemetryFrame()
            changed = false
            bytes = 0
        }
        sink(sample)
    }

    override fun onTelemetryByte() {
        synchronized(lock) { bytes++ }
    }

    override fun onGPSData(list: List<Position>, addToEnd: Boolean) {
        commit()
        sink(Sample.Track(ArrayList(list), replace = !addToEnd))
    }

    override fun onDecoderRestart() {
        synchronized(lock) {
            frame = TelemetryFrame()
            changed = false
            bytes = 0
        }
        if (restarts) sink(Sample.Restart)
    }

    override fun onConnected() {
        commit()
        linkListener.onConnected()
    }

    override fun onDisconnected() {
        commit()
        linkListener.onDisconnected()
    }

    override fun onConnectionFailed() {
        commit()
        linkListener.onConnectionFailed()
    }

    override fun onProtocolDetected(protocolName: String) {
        linkListener.onProtocolDetected(protocolName)
    }

    override fun onGPSData(latitude: Double, longitude: Double) = update { it.copy(position = Position(latitude, longitude)) }
    override fun onGPSState(satellites: Int, gpsFix: Boolean) = update { it.copy(satellites = satellites, gpsFix = gpsFix) }
    override fun onFuelData(fuel: Int) = update { it.copy(fuel = fuel) }
    override fun onVBATData(voltage: Float) = update { it.copy(voltage = voltage) }
    override fun onCellVoltageData(voltage: Float) = update { it.copy(cellVoltage = voltage) }
    override fun onVBATOrCellData(voltage: Float) = update { it.copy(vbatOrCell = voltage) }
    override fun onCurrentData(current: Float) = update { it.copy(current = current) }
    override fun onHeadingData(heading: Float) = update { it.copy(heading = heading) }
    override fun onRSSIData(rssi: Int) = update { it.copy(rssi = rssi) }
    override fun onUpLqData(lq: Int) = update { it.copy(upLq = lq) }
    override fun onDnLqData(lq: Int) = update { it.copy(dnLq = lq) }
    override fun onElrsModeModeData(mode: Int) = update { it.copy(elrsMode = mode) }
    override fun onVSpeedData(vspeed: Float) = update { it.copy(vSpeed = vspeed) }
    override fun onThrottleData(throttle: Int) = update { it.copy(throttle = throttle) }
    override fun onAltitudeData(altitude: Float) = update { it.copy(altitude = altitude) }
    override fun onGPSAltitudeData(altitude: Float) = update { it.copy(gpsAltitude = altitude) }
    override fun onDistanceData(distance: Int) = update { it.copy(distance = distance) }
    override fun onRollData(rollAngle: Float) = update { it.copy(roll = rollAngle) }
    override fun onPitchData(pitchAngle: Float) = update { it.copy(pitch = pitchAngle) }
    override fun onGSpeedData(speed: Float) = update { it.copy(speed = speed) }
    override fun onAirSpeedData(speed: Float) = update { it.copy(airSpeed = speed) }
    override fun onRCChannels(rcChannels: IntArray) = update { it.copy(rcChannels = rcChannels.copyOf()) }
    override fun onStatusText(message: String) = update { it.copy(statusText = message) }
    override fun onDNSNRData(snr: Int) = update { it.copy(dnSnr = snr) }
    override fun onUPSNRData(snr: Int) = update { it.copy(upSnr = snr) }
    override fun onAntData(activeAntena: Int) = update { it.copy(antenna = activeAntena) }
    override fun onPowerData(power: Int) = update { it.copy(power = power) }
    override fun onRssiDbm1Data(rssi: Int) = update { it.copy(rssiDbm1 = rssi) }
    override fun onRssiDbm2Data(rssi: Int) = update { it.copy(rssiDbm2 = rssi) }
    override fun onRssiDbmdData(rssi: Int) = update { it.copy(rssiDbmd = rssi) }
    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) = update { it.copy(flyMode = FlyMode(armed, heading, firstFlightMode, secondFlightMode)) }

    override fun onSuccessDecode() {}
}
