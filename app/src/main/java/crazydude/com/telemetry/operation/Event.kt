package crazydude.com.telemetry.operation

/** Something that happened, raised by a monitor and delivered once to each subscriber. */
sealed class Event {
    object Connected : Event()
    data class ProtocolDetected(val name: String) : Event()
    object ConnectionFailed : Event()
    /** The link was lost, not by the user. The flight goes on. */
    object LinkLost : Event()
    /** An automatic reconnection is being tried. */
    object Reconnecting : Event()
    /** The user disconnected: the flight is over. */
    object Disconnected : Event()
    /** An aircraft is close to the drone. */
    data class TrafficWarning(val aircraft: Aircraft, val distanceMeters: Double, val bearingDeg: Double) : Event()
}

/** What a monitor asks Ingestion to do. */
sealed class Command {
    /** Connect again to the link that was lost. */
    object Reconnect : Command()
}
