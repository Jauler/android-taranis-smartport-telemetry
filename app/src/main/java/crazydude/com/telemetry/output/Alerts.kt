package crazydude.com.telemetry.output

import android.content.Context
import android.media.AudioManager
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import crazydude.com.telemetry.R
import crazydude.com.telemetry.manager.PreferenceManager
import crazydude.com.telemetry.operation.Event

/**
 * Says and sounds what the flight operation raises, with or without a screen: connection sounds
 * (if voice messages are on in the settings) and spoken traffic warnings.
 */
class Alerts(context: Context, private val preferenceManager: PreferenceManager) {

    private val soundPool = SoundPool(5, AudioManager.STREAM_NOTIFICATION, 0)
    private val connected = soundPool.load(context, R.raw.connected, 1)
    private val disconnected = soundPool.load(context, R.raw.disconnected, 1)
    private val failed = soundPool.load(context, R.raw.connection_failed, 1)
    private val reconnecting = soundPool.load(context, R.raw.reconnecting, 1)

    @Volatile
    private var ttsReady = false
    private val tts = TextToSpeech(context.applicationContext) { status -> ttsReady = status == TextToSpeech.SUCCESS }

    fun onEvent(event: Event) {
        when (event) {
            is Event.Connected -> sound(connected)
            is Event.LinkLost, is Event.Disconnected -> sound(disconnected)
            is Event.ConnectionFailed -> sound(failed)
            is Event.Reconnecting -> sound(reconnecting)
            is Event.TrafficWarning -> if (ttsReady) {
                tts.speak(spokenWarning(event), TextToSpeech.QUEUE_ADD, null, "fr24_warning_${event.aircraft.flightId}")
            }
            is Event.ProtocolDetected -> {}
        }
    }

    private fun sound(id: Int) {
        if (preferenceManager.getConnectionVoiceMessagesEnabled()) soundPool.play(id, 1f, 1f, 0, 0, 1f)
    }

    fun release() {
        tts.shutdown()
        soundPool.release()
    }

    companion object {
        fun spokenWarning(w: Event.TrafficWarning): String =
            "Traffic, ${direction(w.bearingDeg, SPOKEN)}, ${"%.1f".format(w.distanceMeters / 1000.0)} kilometers, " +
                    "altitude ${w.aircraft.altMeters} meters"

        fun shortWarning(w: Event.TrafficWarning): String =
            "TRAFFIC: ${w.aircraft.displayName} ${"%.1f".format(w.distanceMeters / 1000.0)}km " +
                    "${direction(w.bearingDeg, CARDINAL)}, alt ${w.aircraft.altMeters}m"

        private val CARDINAL = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        private val SPOKEN = arrayOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

        private fun direction(deg: Double, names: Array<String>): String = names[((deg + 22.5) / 45.0).toInt() % 8]
    }
}
