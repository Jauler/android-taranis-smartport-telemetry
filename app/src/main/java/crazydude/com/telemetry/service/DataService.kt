package crazydude.com.telemetry.service

import android.Manifest
import android.annotation.TargetApi
import android.app.*
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbDeviceConnection
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import crazydude.com.telemetry.BuildConfig
import crazydude.com.telemetry.R
import crazydude.com.telemetry.ingestion.Fr24Source
import crazydude.com.telemetry.ingestion.PilotLocationSource
import crazydude.com.telemetry.ingestion.ReplaySession
import crazydude.com.telemetry.ingestion.SampleAssembler
import crazydude.com.telemetry.logger.RcFrameThrottle
import crazydude.com.telemetry.manager.FlightPlanManager
import crazydude.com.telemetry.manager.PreferenceManager
import crazydude.com.telemetry.manager.TerrainMonitor
import crazydude.com.telemetry.operation.Command
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightOperation
import crazydude.com.telemetry.operation.LinkStatus
import crazydude.com.telemetry.operation.Sample
import crazydude.com.telemetry.operation.monitors.AircraftMonitor
import crazydude.com.telemetry.operation.monitors.LinkMonitor
import crazydude.com.telemetry.operation.processors.FreshnessProcessor
import crazydude.com.telemetry.operation.processors.TerrainProcessor
import crazydude.com.telemetry.operation.processors.TrackProcessor
import crazydude.com.telemetry.output.Alerts
import crazydude.com.telemetry.output.CsvRecorder
import crazydude.com.telemetry.protocol.pollers.BluetoothDataPoller
import crazydude.com.telemetry.protocol.pollers.BluetoothLeDataPoller
import crazydude.com.telemetry.protocol.pollers.DataPoller
import crazydude.com.telemetry.protocol.pollers.FileDataPoller
import crazydude.com.telemetry.protocol.pollers.UsbDataPoller
import crazydude.com.telemetry.protocol.sniffer.SerialLink
import crazydude.com.telemetry.ui.MapsActivity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Hosts everything but the screens, from app start until stopped from its notification:
 * Ingestion (the link, replay, FlightRadar24, the phone's position), the [FlightOperation], and
 * the outputs that work without a screen (alerts, CSV recording). Runs in the foreground, so it
 * goes on with the app closed: traffic warnings keep coming.
 */
class DataService : Service() {

    private enum class LinkKind { NONE, BT, BLE, USB, FILE }

    private lateinit var preferenceManager: PreferenceManager
    lateinit var terrainMonitor: TerrainMonitor
        private set
    lateinit var operation: FlightOperation
        private set
    private lateinit var alerts: Alerts
    private lateinit var fr24: Fr24Source
    private lateinit var pilot: PilotLocationSource

    private val mainHandler = Handler(Looper.getMainLooper())
    private val uiExecutor = Executor { mainHandler.post(it) }
    private val alertsExecutor = Executors.newSingleThreadExecutor()
    private val dataBinder = DataBinder()

    @Volatile
    private var dataPoller: DataPoller? = null
    private var linkKind = LinkKind.NONE
    private var lastDevice: BluetoothDevice? = null
    @Volatile
    private var userDisconnect = false
    @Volatile
    private var connectedOnce = false
    private var csv: CsvRecorder? = null

    /** The replay being played, null when none. */
    var replay: ReplaySession? = null
        private set

    private var notification: Notification? = null
    private var debugReceiver: BroadcastReceiver? = null
    private var debugFile: File? = null
    private var debugPosition = 0L
    private var debugDurationMs = 0L

    private val assembler = SampleAssembler({ operation.submit(it) }, object : SampleAssembler.LinkListener {
        override fun onConnected() {
            connectedOnce = true
            operation.submit(Sample.Link(LinkStatus.CONNECTED))
        }

        override fun onDisconnected() {
            dataPoller = null
            if (userDisconnect) {
                operation.submit(Sample.Link(LinkStatus.DISCONNECTED))
                endLiveFlight()
            } else {
                // the flight goes on: only the user ends it
                operation.submit(Sample.Link(LinkStatus.LOST))
            }
        }

        override fun onConnectionFailed() {
            dataPoller = null
            operation.submit(Sample.Link(LinkStatus.FAILED))
            // a first connection that never came up is no flight; a failed reconnection is
            if (!connectedOnce) endLiveFlight()
        }

        override fun onProtocolDetected(name: String) = operation.submit(Sample.Protocol(name))
    }, restarts = false)

    private val terrainListener = object : TerrainMonitor.Listener {
        override fun onTerrainState(state: TerrainMonitor.State) = operation.submit(Sample.Terrain(state))
    }

    private val heartbeat = object : Runnable {
        override fun run() {
            operation.submit(Sample.Heartbeat(System.currentTimeMillis()))
            mainHandler.postDelayed(this, 1000)
        }
    }

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "fr24_enabled") applyFr24Setting()
    }

    override fun onCreate() {
        super.onCreate()
        preferenceManager = PreferenceManager(this)
        terrainMonitor = TerrainMonitor(applicationContext, preferenceManager)
        operation = FlightOperation(
            listOf(TrackProcessor(), FreshnessProcessor(), TerrainProcessor(terrainMonitor, uiExecutor)),
            listOf(
                LinkMonitor { canReconnect() },
                AircraftMonitor({ preferenceManager.getFr24WarningDistanceM() }, { preferenceManager.getFr24WarningAltCeilingM() })
            )
        )
        alerts = Alerts(this, preferenceManager)
        operation.subscribeEvents(alertsExecutor) { alerts.onEvent(it) }
        operation.subscribeCommands(uiExecutor) { onCommand(it) }

        pilot = PilotLocationSource(this) { operation.submit(it) }
        pilot.start()
        fr24 = Fr24Source(preferenceManager, { operation.read { _, environment -> environment.pilot } }, { operation.submit(it) })
        applyFr24Setting()
        preferenceManager.registerListener(settingsListener)
        reloadFlightPlans()

        startInForeground()
        mainHandler.post(heartbeat)

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

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("bt_channel", "Telemetry", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, DataService::class.java).setAction(ACTION_STOP), 0)
        notification = NotificationCompat.Builder(this, "bt_channel")
            .setContentTitle("Telemetry is running")
            .setContentText("Traffic warnings continue with the app closed")
            .setContentIntent(PendingIntent.getActivity(this, -1, Intent(this, MapsActivity::class.java), 0))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .addAction(0, "Stop", stop)
            .build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** The notification's Stop: no link, no replay, no traffic; the service ends once no screen is bound. */
    private fun stopEverything() {
        disconnect()
        closeReplay()
        fr24.stop()
        pilot.stop()
        stopForeground(true)
        stopSelf()
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
    }

    private fun applyFr24Setting() {
        if (preferenceManager.isFr24Enabled()) fr24.start() else if (fr24.running) fr24.stop()
    }

    /** Settings may have changed (the settings screen was closed). */
    fun reloadSettings() {
        applyFr24Setting()
        reloadFlightPlans()
        pilot.start()  // the location permission may have been granted since
    }

    fun reloadFlightPlans() {
        operation.submit(Sample.FlightPlans(FlightPlanManager(this).getPlans()))
    }

    /** The terrain readouts are calculated only while a screen shows them. */
    fun setTerrainActive(active: Boolean) {
        terrainMonitor.listener = if (active) terrainListener else null
    }

    private fun canReconnect(): Boolean =
        preferenceManager.getReconnectionEnabled() && (linkKind == LinkKind.BT || linkKind == LinkKind.BLE)

    private fun onCommand(command: Command) {
        when (command) {
            is Command.Reconnect -> {
                val device = lastDevice ?: return
                if (dataPoller != null || !isConnected()) return
                connect(device, linkKind == LinkKind.BLE, newSession = false)
            }
        }
    }

    // --- Link ---

    /** A live flight starts: the replay, if any, ends. */
    private fun startLiveFlight() {
        closeReplay()
        operation.openFlight(Flight.Kind.LIVE)
        connectedOnce = false
        userDisconnect = false
        startCsv()
    }

    private fun endLiveFlight() {
        csv?.close()
        csv = null
        operation.closeFlight()
    }

    /**
     * @param newSession false when the link is found again after it was lost (automatic
     * reconnection): the flight goes on
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    fun connect(device: BluetoothDevice, isBle: Boolean, newSession: Boolean = true) {
        if (newSession) startLiveFlight()
        userDisconnect = false
        lastDevice = device
        linkKind = if (isBle) LinkKind.BLE else LinkKind.BT
        try {
            dataPoller?.disconnect()
            val logFile = createLogFile()
            dataPoller = if (!isBle) {
                val socket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
                BluetoothDataPoller(socket, assembler, logFile)
            } else {
                BluetoothLeDataPoller(this, device, assembler, logFile)
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Failed to connect to bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    fun connect(serialPort: UsbSerialPort, connection: UsbDeviceConnection) {
        startLiveFlight()
        linkKind = LinkKind.USB
        dataPoller = UsbDataPoller(assembler, serialPort, preferenceManager.getUsbSerialBaudrate(), connection, createLogFile())
    }

    /** The user ends the flight. */
    fun disconnect() {
        if (!isConnected()) return
        userDisconnect = true
        val poller = dataPoller
        if (poller != null) {
            poller.disconnect()  // its onDisconnected ends the flight
        } else {
            // the link was lost already
            operation.submit(Sample.Link(LinkStatus.DISCONNECTED))
            endLiveFlight()
        }
    }

    /** Whether a live flight is open (its link may be lost for now). */
    fun isConnected(): Boolean = operation.read { flight, _ -> flight?.kind == Flight.Kind.LIVE }

    private fun createLogFile(): OutputStream? {
        if (!preferenceManager.isLoggingEnabled() || !canWriteLogs()) return null
        val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
        dir.mkdirs()
        val file = File(dir, "${SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(Date())}.tlm")
        // RC channels frames come at the packet rate and would fill most of the log
        return RcFrameThrottle(FileOutputStream(file), preferenceManager.getLogRcChannelsRate())
    }

    private fun startCsv() {
        csv?.close()
        csv = null
        if (!preferenceManager.isCSVLoggingEnabled() || !canWriteLogs()) return
        val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
        dir.mkdirs()
        val file = File(dir, "${SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(Date())}.csv")
        try {
            csv = CsvRecorder.open(file) { operation.read { flight, _ -> flight?.values?.copy() } }
        } catch (e: IOException) {
        }
    }

    private fun canWriteLogs() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** The USB serial port of the current connection, to send commands to the receiver; null when not on USB. */
    fun usbSerialLink(): SerialLink? = dataPoller as? UsbDataPoller

    /**
     * The sniffer receiver now follows another pilot's link: what comes next is another
     * aircraft, nothing known about the last one applies to it.
     */
    fun onSnifferLinkChanged() {
        operation.submit(Sample.Restart)
    }

    // --- Replay ---

    fun startReplay(file: File) {
        closeReplay()
        replay = ReplaySession(file, operation, preferenceManager, mainHandler).also { it.start() }
    }

    fun closeReplay() {
        replay?.close()
        replay = null
    }

    // --- Debug ---

    /**
     * Debug builds only: a live connection played from a recorded log, to test everything behind
     * the connection without a radio.
     *
     *     adb shell am broadcast -a jauler.telemetry.debug.LINK --es cmd connect \
     *         --es file "2026-11-02 15-57-26.tlm" --ei duration 120
     *     adb shell am broadcast -a jauler.telemetry.debug.LINK --es cmd drop
     *     adb shell am broadcast -a jauler.telemetry.debug.LINK --es cmd reconnect
     *     adb shell am broadcast -a jauler.telemetry.debug.LINK --es cmd disconnect
     *
     * connect starts the log from its beginning, or from --ei from <percent>; drop loses the link
     * like a radio going out of range (the flight goes on), reconnect continues where drop left
     * off, disconnect is the user's disconnect. duration is the time to play the whole file in, s.
     */
    private fun onDebugCommand(intent: Intent) {
        when (intent.getStringExtra("cmd")) {
            "connect" -> {
                val name = intent.getStringExtra("file") ?: return
                debugFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), name)
                debugDurationMs = intent.getIntExtra("duration", 120) * 1000L
                dataPoller?.disconnect()
                startLiveFlight()
                linkKind = LinkKind.FILE
                val from = debugFile!!.length() * intent.getIntExtra("from", 0) / 100
                dataPoller = FileDataPoller(assembler, debugFile!!, from, debugDurationMs)
            }
            "drop" -> {
                val poller = dataPoller as? FileDataPoller ?: return
                debugPosition = poller.position
                poller.disconnect()
            }
            "reconnect" -> {
                val file = debugFile ?: return
                if (dataPoller != null || !isConnected()) return
                dataPoller = FileDataPoller(assembler, file, debugPosition, debugDurationMs)
            }
            "disconnect" -> disconnect()
        }
    }

    inner class DataBinder : Binder() {
        fun getService(): DataService = this@DataService
    }

    override fun onBind(intent: Intent): IBinder? = dataBinder

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(heartbeat)
        preferenceManager.unregisterListener(settingsListener)
        debugReceiver?.let { unregisterReceiver(it) }
        debugReceiver = null
        userDisconnect = true
        dataPoller?.disconnect()
        dataPoller = null
        csv?.close()
        replay?.close()
        fr24.stop()
        pilot.stop()
        terrainMonitor.destroy()
        alerts.release()
        alertsExecutor.shutdown()
    }

    companion object {
        const val DEBUG_ACTION = "jauler.telemetry.debug.LINK"
        const val ACTION_STOP = "jauler.telemetry.STOP"
        /** Sent when the user stopped everything from the notification. */
        const val ACTION_STOPPED = "jauler.telemetry.STOPPED"
    }
}
