package crazydude.com.telemetry.operation.monitors

import crazydude.com.telemetry.operation.Command
import crazydude.com.telemetry.operation.Event
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.LinkStatus
import crazydude.com.telemetry.operation.Sample

/**
 * Connection events, and the automatic reconnection of a lost link: a first attempt
 * [RETRY_AFTER_MS] after the loss, again after each failure, until [GIVE_UP_AFTER_MS].
 *
 * @param canReconnect whether the lost link can and may be reconnected (a Bluetooth link with the
 * setting on); asked when the link is lost
 */
class LinkMonitor(private val canReconnect: () -> Boolean) : FlightOperation.Monitor {

    private var lostAtMs = NONE
    private var lastAttemptMs = NONE
    private var attempting = false

    override fun onFlightChanged() = stopReconnecting()

    override fun check(pass: FlightOperation.Pass) {
        when (val sample = pass.sample) {
            is Sample.Link -> when (sample.status) {
                LinkStatus.CONNECTED -> {
                    stopReconnecting()
                    pass.emit(Event.Connected)
                }
                LinkStatus.LOST -> {
                    pass.emit(Event.LinkLost)
                    if (lostAtMs == NONE && canReconnect()) {
                        lostAtMs = pass.nowMs
                        lastAttemptMs = pass.nowMs
                    }
                }
                LinkStatus.FAILED -> {
                    pass.emit(Event.ConnectionFailed)
                    if (attempting) {
                        attempting = false
                        lastAttemptMs = pass.nowMs
                    }
                }
                LinkStatus.DISCONNECTED -> {
                    stopReconnecting()
                    pass.emit(Event.Disconnected)
                }
            }
            is Sample.Protocol -> pass.emit(Event.ProtocolDetected(sample.name))
            is Sample.Heartbeat -> {
                if (lostAtMs == NONE || attempting) return
                val now = sample.nowMs
                if (now - lostAtMs >= GIVE_UP_AFTER_MS) {
                    stopReconnecting()
                } else if (now - lastAttemptMs >= RETRY_AFTER_MS) {
                    attempting = true
                    lastAttemptMs = now
                    pass.emit(Event.Reconnecting)
                    pass.command(Command.Reconnect)
                }
            }
            else -> return
        }
    }

    private fun stopReconnecting() {
        lostAtMs = NONE
        lastAttemptMs = NONE
        attempting = false
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        const val RETRY_AFTER_MS = 5_000L
        const val GIVE_UP_AFTER_MS = 21_000L
    }
}
