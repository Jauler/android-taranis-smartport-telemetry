package crazydude.com.telemetry.manager

import android.content.Context
import crazydude.com.telemetry.R
import crazydude.com.telemetry.maps.osm.OsmMapWrapper

class PreferenceManager(context: Context) {

    private val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val defaultHeadlineColor = context.resources.getColor(R.color.colorHeadline)
    private val defaultPlaneColor = context.resources.getColor(R.color.colorPlane)
    private val defaultRouteColor = context.resources.getColor(R.color.colorRoute)
    private val defaultFlightPlanColor = context.resources.getColor(R.color.colorFlightPlan)
    private val defaultHomeLineColor = context.resources.getColor(R.color.colorHomeLine)

    companion object {
        const val SENSOR_HEIGHT_ABOVE_GROUND = "Height above ground"
        const val SENSOR_ALTITUDE_LIMIT = "Altitude limit"
        const val SENSOR_MIN_LOS_ALTITUDE = "Min line of sight altitude"
        const val SENSOR_GROUND_ELEVATION = "Ground elevation"
        const val SENSOR_SURFACE_ELEVATION = "Elevation with trees and buildings"

        val sensors = setOf(
            // The index is the place within the bar; the order of the entries must not change
            SensorSetting("Satellites", 1),
            SensorSetting("Battery", 4),
            SensorSetting("Voltage", 5),
            SensorSetting("Amperage", 6),
            SensorSetting("Speed", 2),
            SensorSetting("Distance", 0, "bottom"),
            SensorSetting("TraveledDistance", 3),
            SensorSetting("Altitude", 1, "bottom"),
            SensorSetting("Phone Battery", 7, "top", false),
            SensorSetting("RC Channels", 2, "bottom", false),
            SensorSetting("Rssi", 0),
            SensorSetting("Uplink SNR", 8, "top", false),
            SensorSetting("Downlink SNR", 9, "top", false),
            SensorSetting("Uplink LQ", 10, "top", false),
            SensorSetting("Downlink LQ", 11, "top", false),
            SensorSetting("ELRS Rate", 12, "top", false),
            SensorSetting("Active Antena", 13, "top", false),
            SensorSetting("Uplink Power", 14, "top", false),
            SensorSetting("Uplink Antena 1 RSSI, dbm", 15, "top", false),
            SensorSetting("Uplink Antena 2 RSSI, dbm", 16, "top", false),
            SensorSetting("Downlink Antena RSSI, dbm", 17, "top", false),
            SensorSetting("AirSpeed", 3, "bottom", false),
            SensorSetting("Vertical Speed", 4, "bottom", false),
            SensorSetting("Cell Voltage", 18, "top", false),
            SensorSetting("Altitude above MSL", 5, "bottom", false),
            SensorSetting("Throttle", 6, "bottom", false),
            SensorSetting("Telemetry rate", 19, "top", false),
            SensorSetting(SENSOR_HEIGHT_ABOVE_GROUND, 7, "bottom"),
            SensorSetting(SENSOR_ALTITUDE_LIMIT, 8, "bottom"),
            SensorSetting(SENSOR_MIN_LOS_ALTITUDE, 9, "bottom"),
            SensorSetting(SENSOR_GROUND_ELEVATION, 10, "bottom"),
            SensorSetting(SENSOR_SURFACE_ELEVATION, 11, "bottom")
        )

        /** Sensors which only work with terrain data; hidden when there is none. */
        val terrainSensors = setOf(
            SENSOR_HEIGHT_ABOVE_GROUND, SENSOR_ALTITUDE_LIMIT, SENSOR_MIN_LOS_ALTITUDE, SENSOR_GROUND_ELEVATION,
            SENSOR_SURFACE_ELEVATION
        )
    }

    fun isLoggingEnabled(): Boolean {
        return sharedPreferences.getBoolean("logging_enabled", true)
    }

    fun isCSVLoggingEnabled(): Boolean {
        return sharedPreferences.getBoolean("csv_logging_enabled", true)
    }

    fun isLoggingSet(): Boolean {
        return sharedPreferences.contains("logging_enabled")
    }

    fun setLoggingEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean("logging_enabled", enabled)
            .apply()
    }

    fun isHeadingLineEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_heading_line", true)
    }

    fun getHeadLineColor(): Int {
        return sharedPreferences.getInt("headline_color", defaultHeadlineColor)
    }

    fun getPlaneColor(): Int {
        return sharedPreferences.getInt("drone_color", defaultPlaneColor)
    }

    fun isYoutubeChannelShown(): Boolean {
        return sharedPreferences.getBoolean("youtube_shown", false)
    }

    fun setYoutubeShown() {
        sharedPreferences.edit().putBoolean("youtube_shown", true).apply()
    }

    fun getBatteryUnits(): String {
        return sharedPreferences.getString("battery_units", "mAh") ?: "mAh"
    }

    fun getReportVoltage(): String {
        return sharedPreferences.getString("report_voltage", "Battery") ?: "Battery"
    }

    fun showArtificialHorizonView(): Boolean {
        return sharedPreferences.getBoolean("show_artificial_horizon", true)
    }

    fun getMapType(): Int {
        return sharedPreferences.getInt("map_type", OsmMapWrapper.MAP_TYPE_DEFAULT)
    }

    fun setMapType(mapType: Int) {
        sharedPreferences.edit().putInt("map_type", mapType).apply()
    }

    fun getRouteColor(): Int {
        return sharedPreferences.getInt("route_color", defaultRouteColor)
    }

    fun getSensorsSettings(): List<SensorSetting> {
        return sensors.map {
            SensorSetting(
                it.name,
                sharedPreferences.getInt(it.name + "_index", it.index),
                sharedPreferences.getString(it.name + "_position", it.position),
                sharedPreferences.getBoolean(it.name + "_shown", it.shown)
            )
        }
    }

    fun setSensorsSettings(data: List<SensorSetting>) {
        val commit = sharedPreferences.edit()
        data.forEach {
            commit.putInt(it.name + "_index", it.index)
            commit.putString(it.name + "_position", it.position)
            commit.putBoolean(it.name + "_shown", it.shown)
        }
        commit.apply()
    }

    fun getUsbSerialBaudrate() : Int {
       return sharedPreferences.getString("usb_serial_baudrate", "57600")?.toInt() ?: 57600
    }

	data class SensorSetting(
        val name: String,
        val index: Int,
        val position: String = "top",
        val shown: Boolean = true
    )

    fun isFullscreenWindow(): Boolean {
        return sharedPreferences.getBoolean("fullscreen_window", false)
    }

    fun setFullscreenWindow( state: Boolean ) {
        sharedPreferences.edit().putBoolean("fullscreen_window", state).apply()
    }

    fun getScreenOrientationLock() : String {
        return sharedPreferences.getString("screen_orientation_lock", "No lock") ?: "No lock"
    }

    fun getReconnectionEnabled(): Boolean {
        return sharedPreferences.getBoolean("connection_reconnect", true)
    }

    fun getConnectionVoiceMessagesEnabled(): Boolean {
        return sharedPreferences.getBoolean("connection_voice_messages", true)
    }

    fun getMaxRoutePoints() : Int {
        return sharedPreferences.getString("route_max_points", "-1")?.toInt() ?: -1
    }

    fun getPlaybackAutostart() : Boolean {
        return sharedPreferences.getBoolean("playback_autostart", true)
    }

    fun getPlaybackDuration() : Int {
        return sharedPreferences.getString("playback_duration", "30")?.toInt() ?: 30
    }

    fun setPlaybackDuration( v : Int)  {
        sharedPreferences.edit().putString("playback_duration", v.toString()).apply();
    }

    fun getLastSelectedDataPooler() : String {
        return sharedPreferences.getString("last_data_pooler", "") ?: ""
    }

    fun setLastSelectedDataPooler(pooler: String) {
        sharedPreferences.edit().putString("last_data_pooler", pooler).apply()
    }

    fun getLastSelectedBluetoothDeviceAddress() : String {
        return sharedPreferences.getString("last_bt_address", "") ?: ""
    }

    fun setLastSelectedBluetoothDeviceAddress(pooler: String) {
        sharedPreferences.edit().putString("last_bt_address", pooler).apply()
    }

    fun getLastSelectedBLEDeviceAddress() : String {
        return sharedPreferences.getString("last_ble_address", "") ?: ""
    }

    fun setLastSelectedBLEDeviceAddress(pooler: String) {
        sharedPreferences.edit().putString("last_ble_address", pooler).apply()
    }

    fun isFlightPlansEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_flight_plans", true)
    }

    fun getFlightPlanColor(): Int {
        return sharedPreferences.getInt("flight_plan_color", defaultFlightPlanColor)
    }

    fun isHomeLineEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_home_line", true)
    }

    fun getHomeLineColor(): Int {
        return sharedPreferences.getInt("home_line_color", defaultHomeLineColor)
    }

    fun isFr24Enabled(): Boolean {
        return sharedPreferences.getBoolean("fr24_enabled", false)
    }

    fun getFr24RadiusKm(): Int {
        return sharedPreferences.getString("fr24_radius_km", "50")?.toIntOrNull() ?: 50
    }

    fun getFr24DisplayAltCeilingM(): Int {
        return sharedPreferences.getString("fr24_display_alt_ceiling", "3000")?.toIntOrNull() ?: 3000
    }

    fun getFr24WarningAltCeilingM(): Int {
        return sharedPreferences.getString("fr24_warning_alt_ceiling", "1500")?.toIntOrNull() ?: 1500
    }

    fun getFr24WarningDistanceM(): Int {
        return sharedPreferences.getString("fr24_warning_distance", "5000")?.toIntOrNull() ?: 5000
    }

    fun getFr24PollIntervalSec(): Int {
        return sharedPreferences.getString("fr24_poll_interval", "10")?.toIntOrNull() ?: 10
    }

    fun isFlightPlanNamesEnabled(): Boolean {
        return sharedPreferences.getBoolean("show_flight_plan_names", true)
    }

    // --- Terrain ---

    private fun getFloat(key: String, default: Float): Float {
        return sharedPreferences.getString(key, null)?.replace(',', '.')?.toFloatOrNull() ?: default
    }

    /**
     * "takeoff": the altitude sensor is relative to the takeoff point; "msl": above sea level;
     * "auto": detect which of the two it is.
     */
    fun getTerrainAltitudeReference(): String {
        return sharedPreferences.getString("terrain_alt_reference", "auto") ?: "auto"
    }

    /** Maximum allowed height above ground, meters. */
    fun getTerrainHeightLimit(): Float {
        return getFloat("terrain_height_limit", 120f)
    }

    fun isTerrainRepeaterEnabled(): Boolean {
        return sharedPreferences.getBoolean("terrain_repeater_enabled", false)
    }

    /** Height above ground of the antenna the aircraft talks to: the repeater when enabled. */
    fun getTerrainAntennaHeight(): Float {
        return if (isTerrainRepeaterEnabled()) {
            getFloat("terrain_repeater_height", 100f)
        } else {
            getFloat("terrain_antenna_height", 2f)
        }
    }

    fun getTerrainLosIgnoreNear(): Float {
        return getFloat("terrain_los_ignore_near", 50f)
    }

    fun getTerrainLosMargin(): Float {
        return getFloat("terrain_los_margin", 0f)
    }

    /** Distance at which a populated area triggers the warning; negative: warning disabled. */
    fun getTerrainPopulatedRadius(): Float {
        return getFloat("terrain_populated_warning", 0f)
    }

    /** Whether single farmsteads count as populated areas (settlements always do). */
    fun isTerrainFarmsteadWarningEnabled(): Boolean {
        return sharedPreferences.getBoolean("terrain_populated_farmsteads", false)
    }

    fun isTerrainVoiceWarningsEnabled(): Boolean {
        return sharedPreferences.getBoolean("terrain_voice_warnings", true)
    }

}
