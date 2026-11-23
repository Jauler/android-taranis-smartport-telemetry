package crazydude.com.telemetry.service

import android.Manifest
import android.annotation.TargetApi
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDeviceConnection
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import crazydude.com.telemetry.BuildConfig
import crazydude.com.telemetry.R
import crazydude.com.telemetry.logger.OtxCsvLogger
import crazydude.com.telemetry.manager.PreferenceManager
import crazydude.com.telemetry.manager.TerrainMonitor
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.protocol.pollers.BluetoothDataPoller
import crazydude.com.telemetry.protocol.pollers.BluetoothLeDataPoller
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.protocol.pollers.DataPoller
import crazydude.com.telemetry.protocol.pollers.FileDataPoller
import crazydude.com.telemetry.protocol.pollers.UsbDataPoller
import crazydude.com.telemetry.protocol.sniffer.SerialLink
import crazydude.com.telemetry.ui.MapsActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.NullPointerException
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

class DataService : Service(), DataDecoder.Listener {

    private var dataPoller: DataPoller? = null
    private var dataListener: DataDecoder.Listener? = null
    private var logListener: OtxCsvLogger? = null
    private val dataBinder = DataBinder()
    private var hasGPSFix = false
    private var isArmed = false
    private var satellites = 0
    private var lastLatitude: Double = 0.0
    private var lastLongitude: Double = 0.0
    private var lastAltitude: Float = 0.0f
    private var lastSpeed: Float = 0.0f
    private var lastHeading: Float = 0.0f
    private lateinit var preferenceManager: PreferenceManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Terrain readouts of the live flight, as long as the service lives (not just the map
     * screen). Called on the main thread only. The map screen also feeds it the antenna
     * position, and replayed flights (replay does not go through the service).
     */
    lateinit var terrainMonitor: TerrainMonitor
        private set
    val points: ArrayList<Position> = ArrayList()
    private var notification: Notification? = null
    private var debugReceiver: BroadcastReceiver? = null
    private var debugFile: File? = null
    private var debugPosition = 0L
    private var debugDurationMs = 0L

    override fun onCreate() {
        super.onCreate()

        preferenceManager = PreferenceManager(this)
        terrainMonitor = TerrainMonitor(applicationContext, preferenceManager)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel =
                NotificationChannel("bt_channel", "Bluetooth", importance)
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }


        notification = NotificationCompat.Builder(this, "bt_channel")
            .setContentText("Telemetry service is running. To stop - disconnect and close the app")
            .setContentTitle("Telemetry service is running")
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    -1,
                    Intent(this, MapsActivity::class.java),
                    0
                )
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        if (BuildConfig.DEBUG) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    onDebugCommand(intent)
                }
            }
            registerReceiver(receiver, IntentFilter(DEBUG_ACTION))
            debugReceiver = receiver
        }
    }

    /**
     * Debug builds only: a live connection played from a recorded log, to test everything behind
     * the connection without a radio.
     *
     *     adb shell am broadcast -a jauler.telemetry.debug.LINK --es cmd connect \
     *         --es file "2026-11-02 15-57-26.tlm" --ei duration 120
     *     adb shell am broadcast -a jauler.telemetry.debug.LINK --es cmd drop
     *     adb shell am broadcast -a jauler.telemetry.debug.LINK --es cmd reconnect
     *
     * connect starts the log from its beginning, or from --ei from <percent> (like connecting
     * from the app, to an aircraft on the ground or in flight), drop loses the
     * link like a radio going out of range, reconnect continues where drop left off (like the
     * automatic reconnection). duration is the time to play the whole file in, in seconds.
     */
    private fun onDebugCommand(intent: Intent) {
        when (intent.getStringExtra("cmd")) {
            "connect" -> {
                val name = intent.getStringExtra("file") ?: return
                debugFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), name)
                debugDurationMs = intent.getIntExtra("duration", 120) * 1000L
                dataPoller?.disconnect()
                logListener = null
                terrainMonitor.reset()
                // "from": percent of the file to start at, like connecting to an aircraft in flight
                val from = debugFile!!.length() * intent.getIntExtra("from", 0) / 100
                dataPoller = FileDataPoller(this, debugFile!!, from, debugDurationMs)
            }
            "drop" -> {
                val poller = dataPoller as? FileDataPoller ?: return
                debugPosition = poller.position
                poller.disconnect()
            }
            "reconnect" -> {
                val file = debugFile ?: return
                if (dataPoller != null) return
                dataPoller = FileDataPoller(this, file, debugPosition, debugDurationMs)
            }
        }
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        return Service.START_REDELIVER_INTENT
    }

    inner class DataBinder : Binder() {
        fun getService(): DataService = this@DataService
    }

    /**
     * @param newSession false when the link is found again after it was lost (automatic
     * reconnection): the flight goes on, what is known about it (the takeoff point) is kept
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    fun connect(device: BluetoothDevice, isBle: Boolean, newSession: Boolean = true) {
        if (newSession) terrainMonitor.reset()
        try {
            dataPoller?.disconnect()

            val logFile = createLogFile()

            createLogger()

            if (!isBle) {
                val socket =
                    device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                dataPoller =
                    BluetoothDataPoller(
                        socket,
                        this,
                        logFile
                    )
            } else {
                dataPoller =
                    BluetoothLeDataPoller(
                        this,
                        device,
                        this,
                        logFile
                    )
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to connect to bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    private fun createLogFile(): FileOutputStream? {
        var fileOutputStream: FileOutputStream? = null
        if (preferenceManager.isLoggingEnabled()
            && ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val name = SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(Date())
            val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
            dir.mkdirs()
            val file = File(dir, "$name.tlm")
            fileOutputStream = FileOutputStream(file)
        }

        return fileOutputStream
    }

    fun connect(serialPort: UsbSerialPort, connection: UsbDeviceConnection) {
        terrainMonitor.reset()
        val logFile = createLogFile()
        createLogger()
        dataPoller = UsbDataPoller(
            this,
            serialPort,
            preferenceManager.getUsbSerialBaudrate(),
            connection,
            logFile
        )
    }

    private fun createLogger() {
        if (preferenceManager.isCSVLoggingEnabled()
            && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            logListener = OtxCsvLogger()
        }
    }

    fun setDataListener(dataListener: DataDecoder.Listener?) {
        this.dataListener = dataListener
        if (dataListener != null) {
            dataListener.onGPSState(satellites, hasGPSFix)
        } else {
            if (!isConnected()) {
                stopSelf()
            }
        }
    }

    /**
     * Stops sending to this listener if it is the current one, and nothing else: for a screen
     * which is replaced by a new one (rotation), which sets itself as the listener when it binds.
     */
    fun removeDataListener(dataListener: DataDecoder.Listener) {
        if (this.dataListener === dataListener) {
            this.dataListener = null
        }
    }

    fun isConnected(): Boolean {
        return dataPoller != null
    }

    /** The USB serial port of the current connection, to send commands to the receiver; null when not on USB. */
    fun usbSerialLink(): SerialLink? {
        return dataPoller as? UsbDataPoller
    }

    /**
     * The sniffer receiver now follows another pilot's link: what comes next is another
     * aircraft, the takeoff point of the last one does not apply to it.
     */
    fun onSnifferLinkChanged() {
        mainHandler.post { terrainMonitor.reset() }
    }

    override fun onBind(intent: Intent): IBinder? {
        return dataBinder
    }

    override fun onDestroy() {
        super.onDestroy()
        terrainMonitor.destroy()
        debugReceiver?.let { unregisterReceiver(it) }
        debugReceiver = null
        dataPoller?.disconnect()
        dataPoller = null
    }

    override fun onConnectionFailed() {
        dataListener?.onConnectionFailed()
        logListener?.onConnectionFailed()
        dataPoller = null
    }

    override fun onFuelData(fuel: Int) {
        dataListener?.onFuelData(fuel)
        logListener?.onFuelData(fuel)
    }

    override fun onConnected() {
        dataListener?.onConnected()
        logListener?.onConnected()

        startForeground(1, notification)
    }

    override fun onGPSData(latitude: Double, longitude: Double) {
        if (hasGPSFix) {
            points.add(Position(latitude, longitude))
        }
        lastLatitude = latitude
        lastLongitude = longitude
        mainHandler.post { terrainMonitor.onAircraftPosition(latitude, longitude) }
        dataListener?.onGPSData(latitude, longitude)
        logListener?.onGPSData(latitude, longitude)
    }

    override fun onGPSData(list: List<Position>, addToEnd: Boolean) {

    }

    override fun onVBATData(voltage: Float) {
        dataListener?.onVBATData(voltage)
        logListener?.onVBATData(voltage)
    }

    override fun onCellVoltageData(voltage: Float) {
        dataListener?.onCellVoltageData(voltage)
        logListener?.onCellVoltageData(voltage)
    }

    override fun onVBATOrCellData(voltage: Float) {
        dataListener?.onVBATOrCellData(voltage)
        logListener?.onVBATOrCellData(voltage)
    }

    override fun onCurrentData(current: Float) {
        dataListener?.onCurrentData(current)
        logListener?.onCurrentData(current)
    }

    override fun onHeadingData(heading: Float) {
        lastHeading = heading
        dataListener?.onHeadingData(heading)
        logListener?.onHeadingData(heading)
    }

    override fun onAirSpeedData(speed: Float) {
        dataListener?.onAirSpeedData(speed)
        logListener?.onAirSpeedData(speed)
    }

    override fun onTelemetryByte() {
        dataListener?.onTelemetryByte()
        logListener?.onTelemetryByte()
    }

    override fun onSuccessDecode() {
        dataListener?.onSuccessDecode()
        logListener?.onSuccessDecode()
    }

    override fun onDecoderRestart() {
        dataListener?.onDecoderRestart()
        logListener?.onDecoderRestart()
    }

    override fun onProtocolDetected( protocolName: String) {
        dataListener?.onProtocolDetected(protocolName)
    }

    override fun commit() {
        dataListener?.commit()
    }

    override fun onRSSIData(rssi: Int) {
        dataListener?.onRSSIData(rssi)
        logListener?.onRSSIData(rssi)
    }

    override fun onUpLqData(lq: Int) {
        dataListener?.onUpLqData(lq)
        logListener?.onUpLqData(lq)
    }

    override fun onDnLqData(lq: Int) {
        dataListener?.onDnLqData(lq)
        logListener?.onDnLqData(lq)
    }

    override fun onElrsModeModeData(mode: Int) {
        dataListener?.onElrsModeModeData(mode)
        logListener?.onElrsModeModeData(mode)
    }

    override fun onDisconnected() {
        points.clear()
        dataListener?.onDisconnected()
        logListener?.onDisconnected()
        dataPoller = null
        satellites = 0
        hasGPSFix = false
        stopForeground(true)
    }

    override fun onGPSState(satellites: Int, gpsFix: Boolean) {
        hasGPSFix = gpsFix
        dataListener?.onGPSState(satellites, gpsFix)
        logListener?.onGPSState(satellites, gpsFix)
    }

    override fun onVSpeedData(vspeed: Float) {
        dataListener?.onVSpeedData(vspeed)
        logListener?.onVSpeedData(vspeed)
    }

    override fun onThrottleData(throttle: Int) {
        dataListener?.onThrottleData(throttle)
        logListener?.onThrottleData(throttle)
    }

    override fun onAltitudeData(altitude: Float) {
        lastAltitude = altitude
        mainHandler.post { terrainMonitor.onAltitude(altitude) }
        dataListener?.onAltitudeData(altitude)
        logListener?.onAltitudeData(altitude)
    }

    override fun onGPSAltitudeData(altitude: Float) {
        dataListener?.onGPSAltitudeData(altitude)
        logListener?.onGPSAltitudeData(altitude)
    }

    override fun onDistanceData(distance: Int) {
        dataListener?.onDistanceData(distance)
        logListener?.onDistanceData(distance)
    }

    override fun onRollData(rollAngle: Float) {
        dataListener?.onRollData(rollAngle)
        logListener?.onRollData(rollAngle)
    }

    override fun onPitchData(pitchAngle: Float) {
        dataListener?.onPitchData(pitchAngle)
        logListener?.onPitchData(pitchAngle)
    }

    override fun onGSpeedData(speed: Float) {
        lastSpeed = speed
        dataListener?.onGSpeedData(speed)
        logListener?.onGSpeedData(speed)
    }

    override fun onFlyModeData(
        armed: Boolean,
        heading: Boolean,
        firstFlightMode: DataDecoder.Companion.FlyMode?,
        secondFlightMode: DataDecoder.Companion.FlyMode?
    ) {
        isArmed = armed
        mainHandler.post { terrainMonitor.onArmedState(armed) }
        dataListener?.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
        logListener?.onFlyModeData(armed, heading, firstFlightMode, secondFlightMode)
    }

    override fun onRCChannels(rcChannels: IntArray) {
        dataListener?.onRCChannels(rcChannels)
        logListener?.onRCChannels(rcChannels)
    }

    override fun onStatusText(message: String) {
        dataListener?.onStatusText(message)
        logListener?.onStatusText(message)
    }

    override fun onDNSNRData(snr: Int) {
        dataListener?.onDNSNRData(snr)
        logListener?.onDNSNRData(snr)
    }

    override fun onUPSNRData(snr: Int) {
        dataListener?.onUPSNRData(snr)
        logListener?.onUPSNRData(snr)
    }

    override fun onAntData(activeAntena: Int) {
        dataListener?.onAntData(activeAntena)
        logListener?.onAntData(activeAntena)
    }

    override fun onPowerData(power: Int) {
        dataListener?.onPowerData(power)
        logListener?.onPowerData(power)
    }

    override fun onRssiDbm1Data(rssi: Int) {
        dataListener?.onRssiDbm1Data(rssi)
        logListener?.onRssiDbm1Data(rssi)
    }

    override fun onRssiDbm2Data(rssi: Int) {
        dataListener?.onRssiDbm2Data(rssi)
        logListener?.onRssiDbm2Data(rssi)
    }

    override fun onRssiDbmdData(rssi: Int) {
        dataListener?.onRssiDbmdData(rssi)
        logListener?.onRssiDbmdData(rssi)
    }

    fun disconnect() {
        points.clear()
        dataPoller?.disconnect()
        dataPoller = null
        satellites = 0
        hasGPSFix = false
    }

    companion object {
        const val DEBUG_ACTION = "jauler.telemetry.debug.LINK"
    }
}
