package crazydude.com.telemetry.operation

import crazydude.com.telemetry.manager.FlightPlanManager
import crazydude.com.telemetry.maps.Position

/**
 * The world the flights happen in: lives as long as the service, not stored in logs. Plain data:
 * only the [FlightOperation] changes it, under its lock.
 */
class Environment {
    /** Where the pilot (the phone) is; null: not known. */
    var pilot: Position? = null
    /** Aircraft around the pilot, from the latest FlightRadar24 fetch. */
    var traffic: List<Aircraft> = emptyList()
    /** The flight plans, as loaded from the configuration. */
    var flightPlans: List<FlightPlanManager.FlightPlan> = emptyList()

    fun snapshot() = EnvironmentSnapshot(pilot, traffic, flightPlans)
}

class EnvironmentSnapshot(
    val pilot: Position?,
    val traffic: List<Aircraft>,
    val flightPlans: List<FlightPlanManager.FlightPlan>
)
