package crazydude.com.telemetry.operation.processors

import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.operation.Sensor
import crazydude.com.telemetry.operation.TelemetryFrame

/**
 * When each value last arrived, which ones have grown old ([STALE_AFTER_MS] without news), and
 * the telemetry rate. Old values are greyed on the screen; an old status text is not shown.
 */
class FreshnessProcessor : FlightOperation.Processor {

    private var bytes = 0
    private var lastHeartbeatMs = Long.MIN_VALUE

    override fun onFlightChanged() {
        bytes = 0
        lastHeartbeatMs = Long.MIN_VALUE
    }

    override fun process(pass: FlightOperation.Pass) {
        val flight = pass.flight ?: return
        when (val sample = pass.sample) {
            is Sample.Telemetry -> {
                bytes += sample.frame.bytes
                sensorsIn(sample.frame).forEach { flight.lastArrival[it.ordinal] = pass.nowMs }
            }
            is Sample.Track -> if (sample.positions.isNotEmpty()) flight.lastArrival[Sensor.GPS.ordinal] = pass.nowMs
            is Sample.Heartbeat -> {
                val now = sample.nowMs
                flight.stale = Sensor.values().filter {
                    val last = flight.lastArrival[it.ordinal]
                    // a value never received is not "old": it shows "-" anyway
                    last != Long.MIN_VALUE && now - last >= STALE_AFTER_MS
                }.toSet()
                if (lastHeartbeatMs != Long.MIN_VALUE && now > lastHeartbeatMs) {
                    flight.telemetryRate = (bytes * 1000L / (now - lastHeartbeatMs)).toInt()
                }
                bytes = 0
                lastHeartbeatMs = now
            }
            else -> return
        }
    }

    companion object {
        const val STALE_AFTER_MS = 10_000L

        fun sensorsIn(f: TelemetryFrame): List<Sensor> {
            val list = ArrayList<Sensor>()
            if (f.position != null || f.satellites != null) list.add(Sensor.GPS)
            if (f.distance != null) list.add(Sensor.DISTANCE)
            if (f.altitude != null) list.add(Sensor.ALTITUDE)
            if (f.gpsAltitude != null) list.add(Sensor.GPS_ALTITUDE)
            if (f.rssi != null) list.add(Sensor.RSSI)
            if (f.upLq != null) list.add(Sensor.UP_LQ)
            if (f.dnLq != null) list.add(Sensor.DN_LQ)
            if (f.elrsMode != null) list.add(Sensor.ELRS_MODE)
            if (f.voltage != null || f.vbatOrCell != null) list.add(Sensor.VOLTAGE)
            if (f.cellVoltage != null || f.vbatOrCell != null) list.add(Sensor.CELL_VOLTAGE)
            if (f.current != null) list.add(Sensor.CURRENT)
            if (f.speed != null) list.add(Sensor.SPEED)
            if (f.airSpeed != null) list.add(Sensor.AIRSPEED)
            if (f.vSpeed != null) list.add(Sensor.VSPEED)
            if (f.throttle != null) list.add(Sensor.THROTTLE)
            if (f.fuel != null) list.add(Sensor.FUEL)
            if (f.rcChannels != null) list.add(Sensor.RC_CHANNELS)
            if (f.statusText != null) list.add(Sensor.STATUSTEXT)
            if (f.dnSnr != null) list.add(Sensor.DN_SNR)
            if (f.upSnr != null) list.add(Sensor.UP_SNR)
            if (f.antenna != null) list.add(Sensor.ANT)
            if (f.power != null) list.add(Sensor.POWER)
            if (f.rssiDbm1 != null) list.add(Sensor.RSSI_DBM_1)
            if (f.rssiDbm2 != null) list.add(Sensor.RSSI_DBM_2)
            if (f.rssiDbmd != null) list.add(Sensor.RSSI_DBM_D)
            return list
        }
    }
}
