package crazydude.com.telemetry.ui

import crazydude.com.telemetry.ingestion.ReplaySession
import crazydude.com.telemetry.operation.Aircraft
import crazydude.com.telemetry.operation.Event
import crazydude.com.telemetry.operation.Flight
import crazydude.com.telemetry.operation.FlightSnapshot
import crazydude.com.telemetry.operation.FlightValues
import crazydude.com.telemetry.operation.LinkStatus
import crazydude.com.telemetry.operation.Sensor
import crazydude.com.telemetry.output.Alerts
import java.util.concurrent.Executor
import android.app.Activity
import android.app.PendingIntent
import android.app.ProgressDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.*
import android.content.pm.ActivityInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.net.Uri
import android.os.*
import android.text.Html
import android.text.InputFilter
import android.text.InputType
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.TouchDelegate
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.nex3z.flowlayout.FlowLayout
import crazydude.com.telemetry.BuildConfig
import crazydude.com.telemetry.R
import crazydude.com.telemetry.converter.Converter
import crazydude.com.telemetry.manager.FlightPlanManager
import crazydude.com.telemetry.manager.PreferenceManager
import crazydude.com.telemetry.manager.TerrainMonitor
import crazydude.com.telemetry.maps.MapLabel
import crazydude.com.telemetry.maps.MapLine
import crazydude.com.telemetry.maps.MapMarker
import crazydude.com.telemetry.maps.MapWrapper
import crazydude.com.telemetry.maps.Position
import crazydude.com.telemetry.maps.osm.OsmMapWrapper
import crazydude.com.telemetry.utils.GeoUtils
import crazydude.com.telemetry.protocol.decoder.DataDecoder
import crazydude.com.telemetry.protocol.pollers.LogPlayer
import crazydude.com.telemetry.service.DataService
import kotlinx.android.synthetic.main.top_layout.*
import kotlinx.android.synthetic.main.view_map.*
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import uk.co.deanwild.materialshowcaseview.IShowcaseListener
import uk.co.deanwild.materialshowcaseview.MaterialShowcaseView
import java.io.File
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.ceil
import kotlin.math.roundToInt

//class MapsActivity : AppCompatActivity(), DataDecoder.Listener {
class MapsActivity : androidx.appcompat.app.AppCompatActivity() {

    companion object {
        private const val REQUEST_ENABLE_BT: Int = 0
        private const val REQUEST_LOCATION_PERMISSION: Int = 1
        private const val REQUEST_WRITE_PERMISSION: Int = 2
        private const val REQUEST_READ_PERMISSION: Int = 3
        private const val ACTION_USB_DEVICE = "action_usb_device"
        private val MAP_TYPE_ITEMS = arrayOf(
            "OpenStreetMap (can be cached)",
            "OpenTopoMap (can be cached)",
            "Satellite - ESRI (can be cached)",
            "Satellite + Streets - ESRI (can be cached)"
        )

        private val ESRI_SATELLITE_TILE_SOURCE = object : OnlineTileSourceBase(
            "ESRISatellite", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
                    "/" + MapTileIndex.getY(pMapTileIndex) +
                    "/" + MapTileIndex.getX(pMapTileIndex)
            }
        }

        private val ESRI_TRANSPORTATION_OVERLAY_TILE_SOURCE = object : OnlineTileSourceBase(
            "ESRITransportation", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
                    "/" + MapTileIndex.getY(pMapTileIndex) +
                    "/" + MapTileIndex.getX(pMapTileIndex)
            }
        }

        private val ESRI_BOUNDARIES_PLACES_OVERLAY_TILE_SOURCE = object : OnlineTileSourceBase(
            "ESRIBoundariesPlaces", 0, 19, 256, "",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + MapTileIndex.getZoom(pMapTileIndex) +
                    "/" + MapTileIndex.getY(pMapTileIndex) +
                    "/" + MapTileIndex.getX(pMapTileIndex)
            }
        }

        private const val CONNTYPE_NONE = 0
        private const val CONNTYPE_BT = 1
        private const val CONNTYPE_BLE = 2
        private const val CONNTYPE_USB = 3

        private const val COLOR_SENSOR_NORMAL = Color.WHITE
        private const val COLOR_SENSOR_ALERT = 0xFFFF5252.toInt()
        private const val COLOR_LOS_BLOCKED = 0xFFFF0000.toInt()
    }

    enum class RequestWritePermissionSequenceType {
        NONE, CONNECT, CONNECT_USB, RENAME, DELETE, EXPORT_GPX, EXPORT_KML
    }

    private var map: MapWrapper? = null

    private var marker: MapMarker? = null
    private var polyLine: MapLine? = null
    private val airplaneMarkers = mutableMapOf<Int, MapMarker>()
    // the traffic drawn last, to redraw only when a new list comes
    private var trafficShown: List<Aircraft>? = null
    private var headingPolyline: MapLine? = null
    private var flightPlanLines: MutableList<MapLine> = mutableListOf()
    private var flightPlanLabels: MutableList<MapLabel> = mutableListOf()
    private var homeLine: MapLine? = null
    // red marks on the home line where the line of sight to the aircraft is obstructed
    private val losBlockedLines = ArrayList<MapLine>()
    private var losBlockedShown: List<TerrainMonitor.Segment> = emptyList()
    // the last obstructed stretches reported by the terrain monitor, drawn or not
    private var losBlocked: List<TerrainMonitor.Segment> = emptyList()
    /** Lives in the data service, null until it is bound. */
    private val terrainMonitor: TerrainMonitor?
        get() = dataService?.terrainMonitor
    private var started = false
    private val phoneLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            runOnUiThread {
                updateHomeLine()
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private lateinit var connectButton: Button
    private lateinit var replayButton: ImageView
    private lateinit var seekBar: SeekBar
    private lateinit var playButton: FloatingActionButton
    private lateinit var fuel: TextView
    private lateinit var rssi: TextView
    private lateinit var satellites: TextView
    private lateinit var current: TextView
    private lateinit var voltage: TextView
    private lateinit var phoneBattery: TextView
    private lateinit var speed: TextView
    private lateinit var airspeed: TextView
    private lateinit var vspeed: TextView
    private lateinit var distance: TextView
    private lateinit var traveled_distance: TextView
    private lateinit var altitude: TextView
    private lateinit var altitude_msl: TextView
    private lateinit var mode: TextView
    private lateinit var statustext: TextView
    private lateinit var followButton: FloatingActionButton
    private lateinit var mapTypeButton: FloatingActionButton
    private lateinit var northUpButton: FloatingActionButton
    private lateinit var myLocationButton: FloatingActionButton
    private lateinit var fullscreenButton: ImageView
    private lateinit var menuButton: FloatingActionButton
    private lateinit var settingsButton: ImageView
    private lateinit var topLayout: RelativeLayout
    private lateinit var bottomLayout: RelativeLayout
    private lateinit var horizonView: HorizonView
    private lateinit var topList: FlowLayout
    private lateinit var bottomList: FlowLayout
    private lateinit var rootLayout: CoordinatorLayout
    private lateinit var compassHeading: TextViewOutline
    private lateinit var mapHolder: FrameLayout
    private lateinit var mapViewHolder: FrameLayout
    private lateinit var rc_widget: RCWidget
    private lateinit var dnSnr: TextView
    private lateinit var upSnr: TextView
    private lateinit var upLq: TextView
    private lateinit var dnLq: TextView
    private lateinit var elrsRate: TextView
    private lateinit var ant: TextView
    private lateinit var power: TextView
    private lateinit var rssiDbm1: TextView
    private lateinit var rssiDbm2: TextView
    private lateinit var rssiDbmd: TextView
    private lateinit var cell_voltage: TextView
    private lateinit var throttle: TextView
    private lateinit var tlmRate: TextView
    private lateinit var heightAboveGround: TextView
    private lateinit var altitudeLimit: TextView
    private lateinit var groundElevation: TextView
    private lateinit var surfaceElevation: TextView
    private lateinit var minLosAltitude: TextView

    private lateinit var sensorViewMap: HashMap<String, View>
    private lateinit var sensorsConverters: HashMap<String, Converter>

    private lateinit var preferenceManager: PreferenceManager

    private var mapType = OsmMapWrapper.MAP_TYPE_DEFAULT

    private var lastGPS = Position(0.0, 0.0)
    // During replay the home line goes to where the flight started, not to the phone's current location.
    private var replayHome: Position? = null
    private var lastHeading = 0f
    private var followMode = true
    private var hasGPSFix = false
    private var replayFileString: String? = null
    private var dataService: DataService? = null
    private var lastPhoneBattery = 0

    /** Draws the flight operation of the data service at a fixed rate ([renderIntervalMs]). */
    private val renderHandler = Handler(Looper.getMainLooper())
    private val renderTask = object : Runnable {
        override fun run() {
            render()
            renderHandler.postDelayed(this, renderIntervalMs())
        }
    }
    // the track drawn so far: that many points of the flight's track number trackResets; -1: none
    private var renderedTrackResets = -1
    private var renderedTrackSize = 0
    private var events: AutoCloseable? = null
    // debug builds: a replay to start once the data service is bound
    private var pendingReplayFile: String? = null
    private var seekBarDragging = false

    private var fullscreenWindow = false

    private var requestWritePermissionSequence = RequestWritePermissionSequenceType.NONE;

    private var lastFileDialogSelectionIndex = -1;
    private var lastFileDialogSelection = "";

    private var lastSelectedDataPooler = "";
    private var lastSelectedBluetoothDeviceAddress = "";
    private var lastSelectedBLEDeviceAddress = "";

    private var lastConnectionType = CONNTYPE_NONE;

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(p0: ComponentName?) {
            events?.close()
            events = null
            dataService = null
            switchToIdleState()
        }

        override fun onServiceConnected(p0: ComponentName?, p1: IBinder?) {
            val service = (p1 as DataService.DataBinder).getService()
            dataService = service
            events?.close()
            events = service.operation.subscribeEvents(Executor { renderHandler.post(it) }) { onEvent(it) }
            // a screen made again (rotation) takes up what the service is doing
            when {
                service.isConnected() -> switchToConnectedState()
                service.replay != null -> enterReplayUi()
                else -> pendingReplayFile?.let {
                    pendingReplayFile = null
                    startReplay(File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), it))
                }
            }
            attachTerrain()
            drawFlightPlans()
        }
    }

    /** Stopped from the notification: nothing is left to show. */
    private val stoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        preferenceManager = PreferenceManager(this)

        mapType = preferenceManager.getMapType()
        followMode = savedInstanceState?.getBoolean("follow_mode", true) ?: true
        if (BuildConfig.DEBUG && savedInstanceState == null) {
            // debug builds: adb shell am start -n <package>/crazydude.com.telemetry.ui.MapsActivity --es debug_replay "<name>.tlm"
            pendingReplayFile = intent?.getStringExtra("debug_replay")
        }
        fullscreenWindow = preferenceManager.isFullscreenWindow()

        lastSelectedDataPooler = preferenceManager.getLastSelectedDataPooler()
        lastSelectedBluetoothDeviceAddress = preferenceManager.getLastSelectedBluetoothDeviceAddress()
        lastSelectedBLEDeviceAddress = preferenceManager.getLastSelectedBLEDeviceAddress()

        rootLayout = findViewById(R.id.rootLayout)
        fuel = findViewById(R.id.fuel)
        rssi = findViewById(R.id.rssi)
        satellites = findViewById(R.id.satellites)
        topLayout = findViewById(R.id.top_layout)
        bottomLayout = findViewById(R.id.bottom_layout)
        connectButton = findViewById(R.id.connect_button)
        current = findViewById(R.id.current)
        voltage = findViewById(R.id.voltage)
        phoneBattery = findViewById(R.id.phone_battery)
        speed = findViewById(R.id.speed)
        airspeed = findViewById(R.id.airspeed)
        vspeed = findViewById(R.id.vspeed)
        distance = findViewById(R.id.distance)
        traveled_distance = findViewById(R.id.traveled_distance)
        altitude = findViewById(R.id.altitude)
        altitude_msl = findViewById(R.id.altitude_msl)
        mode = findViewById(R.id.mode)
        statustext = findViewById(R.id.statustext)
        followButton = findViewById(R.id.follow_button)
        mapTypeButton = findViewById(R.id.map_type_button)
        northUpButton = findViewById(R.id.north_up_button)
        compassHeading = findViewById(R.id.compass_heading)
        myLocationButton = findViewById(R.id.my_location_button)
        settingsButton = findViewById(R.id.settings_button)
        replayButton = findViewById(R.id.replay_button)
        seekBar = findViewById(R.id.seekbar)
        playButton = findViewById(R.id.play_button)
        // The mini button is only 40 dp: let touches a bit around it count too, so it is not
        // missed and the map panned instead. Rebuilt on every layout, as the button starts hidden.
        playButton.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val extra = (16 * resources.displayMetrics.density).toInt()
            val area = Rect()
            v.getHitRect(area)
            area.inset(-extra, -extra)
            (v.parent as View).touchDelegate = TouchDelegate(area, v)
        }
        horizonView = findViewById(R.id.horizon_view)
        fullscreenButton = findViewById(R.id.fullscreen_button)
        menuButton = findViewById(R.id.replay_menu_button)
        topList = findViewById(R.id.top_list)
        bottomList = findViewById(R.id.bottom_list)
        mapHolder = findViewById(R.id.map_holder)
        mapViewHolder = findViewById(R.id.mapViewHolder)
        rc_widget = findViewById(R.id.rc_widget)
        dnSnr = findViewById(R.id.dn_snr)
        upSnr = findViewById(R.id.up_snr)
        upLq = findViewById(R.id.up_lq)
        dnLq = findViewById(R.id.dn_lq)
        elrsRate = findViewById(R.id.elrs_rate)
        ant = findViewById(R.id.ant)
        power = findViewById(R.id.power)
        rssiDbm1 = findViewById(R.id.up_rssi_dbm1)
        rssiDbm2 = findViewById(R.id.up_rssi_dbm2)
        rssiDbmd = findViewById(R.id.dn_rssi_dbm)
        cell_voltage = findViewById(R.id.cell_voltage)
        throttle = findViewById(R.id.throttle)
        tlmRate = findViewById(R.id.tlm_rate)
        heightAboveGround = findViewById(R.id.height_above_ground)
        altitudeLimit = findViewById(R.id.altitude_limit)
        groundElevation = findViewById(R.id.ground_elevation)
        surfaceElevation = findViewById(R.id.surface_elevation)
        minLosAltitude = findViewById(R.id.min_los_altitude)

        sensorViewMap = hashMapOf(
            Pair(PreferenceManager.sensors.elementAt(0).name, satellites),
            Pair(PreferenceManager.sensors.elementAt(1).name, fuel),
            Pair(PreferenceManager.sensors.elementAt(2).name, voltage),
            Pair(PreferenceManager.sensors.elementAt(3).name, current),
            Pair(PreferenceManager.sensors.elementAt(4).name, speed),
            Pair(PreferenceManager.sensors.elementAt(5).name, distance),
            Pair(PreferenceManager.sensors.elementAt(6).name, traveled_distance),
            Pair(PreferenceManager.sensors.elementAt(7).name, altitude),
            Pair(PreferenceManager.sensors.elementAt(8).name, phoneBattery),
            Pair(PreferenceManager.sensors.elementAt(9).name, rc_widget),
            Pair(PreferenceManager.sensors.elementAt(10).name, rssi),
            Pair(PreferenceManager.sensors.elementAt(11).name, dnSnr),
            Pair(PreferenceManager.sensors.elementAt(12).name, upSnr),
            Pair(PreferenceManager.sensors.elementAt(13).name, upLq),
            Pair(PreferenceManager.sensors.elementAt(14).name, dnLq),
            Pair(PreferenceManager.sensors.elementAt(15).name, elrsRate),
            Pair(PreferenceManager.sensors.elementAt(16).name, ant),
            Pair(PreferenceManager.sensors.elementAt(17).name, power),
            Pair(PreferenceManager.sensors.elementAt(18).name, rssiDbm1),
            Pair(PreferenceManager.sensors.elementAt(19).name, rssiDbm2),
            Pair(PreferenceManager.sensors.elementAt(20).name, rssiDbmd),
            Pair(PreferenceManager.sensors.elementAt(21).name, airspeed),
            Pair(PreferenceManager.sensors.elementAt(22).name, vspeed),
            Pair(PreferenceManager.sensors.elementAt(23).name, cell_voltage),
            Pair(PreferenceManager.sensors.elementAt(24).name, altitude_msl),
            Pair(PreferenceManager.sensors.elementAt(25).name, throttle),
            Pair(PreferenceManager.sensors.elementAt(26).name, tlmRate),
            Pair(PreferenceManager.SENSOR_HEIGHT_ABOVE_GROUND, heightAboveGround),
            Pair(PreferenceManager.SENSOR_ALTITUDE_LIMIT, altitudeLimit),
            Pair(PreferenceManager.SENSOR_GROUND_ELEVATION, groundElevation),
            Pair(PreferenceManager.SENSOR_SURFACE_ELEVATION, surfaceElevation),
            Pair(PreferenceManager.SENSOR_MIN_LOS_ALTITUDE, minLosAltitude)
        )

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        fullscreenButton.setOnClickListener {
            updateFullscreenState()
            this.fullscreenWindow = !this.fullscreenWindow
            preferenceManager.setFullscreenWindow(fullscreenWindow)
            updateWindowFullscreenDecoration()
        }

        /*
        rootLayout.setOnClickListener {
            var layout = preferenceManager.getMainLayout()
            if (layout == 2) setNextLayout()
        }
        */

        followButton.setOnClickListener {
            setFollowMode(!followMode);
            if (followMode) {
                marker?.let {
                    if (map?.initialized() ?: false) {
                        map?.moveCamera(it.position)
                    }
                }
            }
        }

        mapTypeButton.setOnClickListener {
            showMapTypeSelectorDialog()
        }

        northUpButton.setOnClickListener {
            map?.resetMapOrientation()
        }

        myLocationButton.setOnClickListener {
            val pos = map?.getMyLocation()
            if (pos != null) {
                setFollowMode(false)
                map?.moveCamera(pos, 15f)
            } else {
                Toast.makeText(this, "Phone location not available", Toast.LENGTH_SHORT).show()
            }
        }

        menuButton.setOnClickListener {
            val option0 = "Copy UAV location to clipboard";
            val option1 = "Show route to UAV";
            val option2 = "Rename Log";
            val option3 = "Delete Log";
            val option4 = "Export GPX file...";
            val option5 = "Export KML file...";
            val option6 = "Set playback duration..."
            val option7 = "Set takeoff point at UAV location"

            var options = arrayOf(option0, option1, option2, option3, option4, option5, option6)
            if (!isInReplayMode()) {
                options = arrayOf(option0, option1)
            }
            if (dataService?.terrainMonitor?.isAvailable == true) {
                options += option7
            }

            this.showDialog( AlertDialog.Builder(this)
            .setTitle("Select an action")
            .setItems(options) { dialog: DialogInterface, which: Int ->
                val selectedOption = options[which]
                when (selectedOption) {
                    option0 -> {
                        showAndCopyCurrentGPSLocation()
                    }
                    option1 -> {
                        showDirectionsToCurrentLocation()
                    }
                    option2 -> {
                        showRenameLogDialog()
                    }
                    option3 -> {
                        showDeleteLogDialog()
                    }
                    option4 -> {
                        showExportGPXDialog()
                    }
                    option5 -> {
                        showExportKMLDialog1()
                    }
                    option6 -> {
                        showSetPlaybackDurationDialog()
                    }
                    option7 -> {
                        if (dataService?.terrainMonitor?.setTakeoffHere() == true) {
                            Toast.makeText(this, "Altitudes are now relative to the terrain at this point", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Location is unknown", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                dialog.dismiss()
            }.create())
        }

        playButton.setOnClickListener {
            dataService?.replay?.let { if (it.playing) it.pause() else it.play() }
        }

        switchToIdleState()
        registerReceiver(stoppedReceiver, IntentFilter(DataService.ACTION_STOPPED))

        startDataService()

        initMap(false)
        map?.onCreate(savedInstanceState)

        updateWindowFullscreenDecoration()

        updateScreenOrientation()

        this.registerReceiver(this.batInfoReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        renderHandler.post(renderTask)
    }

    private fun updateWindowFullscreenDecoration() {
        if (!this.fullscreenWindow) {
            window.decorView.systemUiVisibility = 0
        } else {
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE)
        }
    }

    private fun updateFullscreenState() {
        //user may have brought system ui with a swipe. Update state
        this.fullscreenWindow = window.decorView.systemUiVisibility ==
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE)
    }


    private fun initMap(simulateLifecycle: Boolean) {

        headingPolyline?.remove();
        headingPolyline = null;
        polyLine?.remove();
        polyLine = null;
        flightPlanLines.forEach { it.remove() }
        flightPlanLines.clear()
        flightPlanLabels.forEach { it.remove() }
        flightPlanLabels.clear()
        homeLine?.remove()
        homeLine = null
        losBlockedLines.forEach { it.remove() }
        losBlockedLines.clear()
        losBlockedShown = emptyList()
        marker?.remove();
        marker = null;
        airplaneMarkers.values.forEach { it.remove() }
        airplaneMarkers.clear()

        if (mapType == OsmMapWrapper.MAP_TYPE_DEFAULT) {
            initOSMMap(TileSourceFactory.DEFAULT_TILE_SOURCE)
        } else if (mapType == OsmMapWrapper.MAP_TYPE_SATELLITE) {
            initOSMMap(ESRI_SATELLITE_TILE_SOURCE)
        } else if (mapType == OsmMapWrapper.MAP_TYPE_SATELLITE_HYBRID) {
            initOSMMap(ESRI_SATELLITE_TILE_SOURCE, listOf(ESRI_TRANSPORTATION_OVERLAY_TILE_SOURCE, ESRI_BOUNDARIES_PLACES_OVERLAY_TILE_SOURCE))
        } else {
            initOSMMap(TileSourceFactory.OpenTopo)
        }
    }

    private fun initOSMMap(tileSource: OnlineTileSourceBase, overlayTileSources: List<OnlineTileSourceBase> = emptyList()) {
        val mapView = org.osmdroid.views.MapView(this)
        mapHolder.addView(mapView)
        val osmMap = OsmMapWrapper(applicationContext, mapView, tileSource, { initHeadingLine() }, overlayTileSources)
        map = osmMap
        map?.setOnCameraMoveStartedListener {
            setFollowMode(false);
        }
        osmMap.setOnOrientationChangedListener { orientation ->
            updateCompassHeading(orientation)
        }
        polyLine = map?.addPolyline(preferenceManager.getRouteColor())
        // the next render draws the flight's whole track on it
        renderedTrackResets = -1
        homeLine = map?.addPolyline(2f, preferenceManager.getHomeLineColor())
        drawFlightPlans()
        showMyLocation()
        // the home line and its marks went with the old map
        updateHomeLine()
    }

    private fun updateCompassHeading(orientation: Float) {
        val heading = (((-orientation % 360f) + 360f) % 360f).roundToInt() % 360
        compassHeading.text = "↑ %03d°".format(heading)
        compassHeading.visibility = View.VISIBLE
    }

    private fun showMyLocation() {
        if (checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            map?.isMyLocationEnabled = true
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ),
                REQUEST_LOCATION_PERMISSION
            )
            map?.isMyLocationEnabled = false
        }
    }


    private fun initHeadingLine() {
        polyLine?.let { it.color = preferenceManager.getRouteColor() }
        if (!isIdle()) {
            if (preferenceManager.isHeadingLineEnabled() && headingPolyline == null) {
                headingPolyline = createHeadingPolyline()
                updateHeading()
            } else if (!preferenceManager.isHeadingLineEnabled() && headingPolyline != null) {
                headingPolyline?.remove()
                headingPolyline = null
            }
            headingPolyline?.let { it.color = preferenceManager.getHeadLineColor() }
            marker?.setIcon(R.drawable.ic_plane, preferenceManager.getPlaneColor())
        }
        drawFlightPlans()
    }

    private fun updateHomeLine() {
        updateHomeLinePoints()
        // the marks on it follow the line: hidden with it, back with it
        showLosBlocked()
    }

    private fun updateHomeLinePoints() {
        val line = homeLine ?: return
        line.color = preferenceManager.getHomeLineColor()
        if (!preferenceManager.isHomeLineEnabled()) {
            line.clear()
            map?.invalidate()
            return
        }
        val drone = if (lastGPS.lat != 0.0 || lastGPS.lon != 0.0) lastGPS else return
        val home = if (isInReplayMode()) replayHome else map?.getMyLocation()
        if (home == null) {
            if (line.size > 0) {
                line.clear()
                map?.invalidate()
            }
            return
        }
        if (line.size == 2) {
            line.setPoint(0, drone)
            line.setPoint(1, home)
        } else {
            line.clear()
            line.addPoints(listOf(drone, home))
        }
        map?.invalidate()
    }

    private fun drawFlightPlans() {
        flightPlanLines.forEach { it.remove() }
        flightPlanLines.clear()
        flightPlanLabels.forEach { it.remove() }
        flightPlanLabels.clear()
        if (!preferenceManager.isFlightPlansEnabled()) return
        val showNames = preferenceManager.isFlightPlanNamesEnabled()
        // the plans the data service has loaded into the environment
        val plans = dataService?.operation?.read { _, environment -> environment.flightPlans } ?: FlightPlanManager(this).getPlans()
        for (plan in plans) {
            if (!plan.visible || plan.waypoints.size < 2) continue
            val line = map?.addPolyline(4f, preferenceManager.getFlightPlanColor(), *plan.waypoints.toTypedArray())
            if (line != null) {
                flightPlanLines.add(line)
            }
            if (showNames && plan.name.isNotEmpty()) {
                map?.addLabel(plan.name, plan.waypoints[0])?.let { flightPlanLabels.add(it) }
            }
        }
    }

    private fun showAndCopyCurrentGPSLocation() {
        marker?.let {
            val posString = "${it.position.lat},${it.position.lon}"
            val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.primaryClip = ClipData.newPlainText("Location", posString)
            Toast.makeText(
                this,
                "Current plane location copied to clipboard ($posString)",
                Toast.LENGTH_LONG
            ).show()
        }
        if ( marker == null ) {
            Toast.makeText(this, "Location is unknown", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDirectionsToCurrentLocation() {
        marker?.let {
            val posString = "${it.position.lat},${it.position.lon}"
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("http://maps.google.com/maps?daddr=$posString")
            )
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "Cannot build directions", Toast.LENGTH_LONG).show()
            }
        }
        if ( marker == null ) {
            Toast.makeText(this, "Location is unknown", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isInReplayMode(): Boolean {
        return dataService?.replay != null
    }

    private fun isIdle(): Boolean {
        return !isInReplayMode() && !(dataService?.isConnected() ?: false)
    }

    private fun replay() {
        if (dataService?.isConnected() != true) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_DENIED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQUEST_READ_PERMISSION
                )
            } else {
                val dir = Environment.getExternalStoragePublicDirectory("TelemetryLogs")
                if (dir.exists()) {
                    val files =
                        dir.listFiles { file -> ((file.extension == "log") || (file.extension == "tlm")) && (file.length() > 0) }
                            .sorted()
                            .reversed()

                    if ( lastFileDialogSelectionIndex >= files.size) {
                        lastFileDialogSelectionIndex = files.size-1;
                    }

                    val dialog = AlertDialog.Builder(this)
                        .setAdapter(
                            ArrayAdapter(
                                this,
                                android.R.layout.simple_list_item_1,
                                files.map { i ->
                                    if ( i.name == lastFileDialogSelection ) {
                                        val b = "${i.nameWithoutExtension} (${ceil(i.length() / 102.4) / 10} Kb)"
                                        val boldOption = SpannableString(b)
                                        boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, b.length, 0)
                                        boldOption
                                    } else {
                                        "${i.nameWithoutExtension} (${ceil(i.length() / 102.4) / 10} Kb)"
                                    }
                                })
                        ) { _, i ->
                            updateWindowFullscreenDecoration()
                            lastFileDialogSelectionIndex = i;
                            lastFileDialogSelection = files[i].name
                            startReplay(files[i])
                        }
                        .create();

                    dialog.setOnShowListener {
                        val alertDialog = it as AlertDialog
                        if ( lastFileDialogSelectionIndex != -1) {
                            val centerY = alertDialog.listView.height / 2 // Calculate the center position vertically
                            alertDialog.listView.smoothScrollToPositionFromTop(lastFileDialogSelectionIndex, centerY)
                        }
                    }

                    this.showDialog(dialog);
                }
            }
        } else {
            Toast.makeText(this, "You need to disconnect first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startReplay(file: File?) {
        file ?: return
        val service = dataService
        if (service == null) {
            pendingReplayFile = file.name
            return
        }
        service.startReplay(file)
        enterReplayUi()
    }

    /** Shows the replay the data service plays: its progress while it loads, then its controls. */
    private fun enterReplayUi() {
        val session = dataService?.replay ?: return
        replayFileString = session.file.name
        switchToReplayMode()
        if (session.size >= 0) {
            showReplayControls(session)
            return
        }
        val progressDialog = ProgressDialog(this)
        progressDialog.setCancelable(false)
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
        progressDialog.max = 100
        progressDialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        progressDialog.show()
        progressDialog.window?.decorView?.systemUiVisibility = if (!this.fullscreenWindow) 0 else
            (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE)
        progressDialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val poll = object : Runnable {
            override fun run() {
                val current = dataService?.replay
                if (current !== session || isDestroyed) {
                    progressDialog.dismiss()
                    return
                }
                progressDialog.progress = session.loadPercent
                if (session.size >= 0) {
                    progressDialog.dismiss()
                    showReplayControls(session)
                } else {
                    renderHandler.postDelayed(this, 100)
                }
            }
        }
        poll.run()
    }

    private fun showReplayControls(session: ReplaySession) {
        seekBar.max = session.size
        seekBar.progress = session.position
        seekBar.visibility = View.VISIBLE
        playButton.visibility = View.VISIBLE
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekbar: SeekBar, position: Int, fromUser: Boolean) {
                // the playback moves the bar too: only the user's moves are seeks
                if (fromUser) dataService?.replay?.seek(position)
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {
                seekBarDragging = true
            }

            override fun onStopTrackingTouch(p0: SeekBar?) {
                seekBarDragging = false
            }
        })
        // the map goes to where the aircraft first has a fix
        session.focus?.let { map?.moveCamera(it, 15f) }
    }


    /** "Armed | Acro"..., or the state of the connection until the flight mode is known. */
    private fun modeText(flight: FlightSnapshot?): String {
        val mode = flight?.values?.flyMode ?: return when {
            flight == null -> "Disconnected"
            flight.kind == Flight.Kind.REPLAY -> "Replay"
            flight.link == LinkStatus.CONNECTED -> "Connected"
            flight.link == LinkStatus.LOST -> "Link lost"
            flight.link == LinkStatus.FAILED -> "Link lost"
            else -> "Connecting"
        }
        val text = StringBuilder(if (mode.armed) "Armed" else "Disarmed")
        if (mode.heading) text.append(" | Heading")
        decodeMode(text, mode.mode1)
        decodeMode(text, mode.mode2)
        if (flight.kind == Flight.Kind.LIVE && flight.link == LinkStatus.LOST) text.append(" | Link lost")
        return text.toString()
    }

    private fun decodeMode(text: StringBuilder, flyMode: DataDecoder.Companion.FlyMode?) {
        when (flyMode) {
            DataDecoder.Companion.FlyMode.ACRO -> {
                text.append(" | Acro")
            }
            DataDecoder.Companion.FlyMode.HORIZON -> {
                text.append(" | Horizon")
            }
            DataDecoder.Companion.FlyMode.ANGLE -> {
                text.append(" | Angle")
            }
            DataDecoder.Companion.FlyMode.FAILSAFE -> {
                text.append(" | Failsafe")
            }
            DataDecoder.Companion.FlyMode.RTH -> {
                text.append(" | RTH")
            }
            DataDecoder.Companion.FlyMode.WAYPOINT -> {
                text.append(" | Waypoint")
            }
            DataDecoder.Companion.FlyMode.MANUAL -> {
                text.append(" | Manual")
            }
            DataDecoder.Companion.FlyMode.CRUISE -> {
                text.append(" | Cruise")
            }
            DataDecoder.Companion.FlyMode.HOLD -> {
                text.append(" | Hold")
            }
            DataDecoder.Companion.FlyMode.HOME_RESET -> {
                text.append(" | Home reset")
            }
            DataDecoder.Companion.FlyMode.CRUISE3D -> {
                text.append(" | 3D Cruise")
            }
            DataDecoder.Companion.FlyMode.ALTHOLD -> {
                text.append(" | Alt hold")
            }
            DataDecoder.Companion.FlyMode.ERROR -> {
                text.append(" | !ERROR!")
            }
            DataDecoder.Companion.FlyMode.WAIT -> {
                text.append(" | GPS wait")
            }
            DataDecoder.Companion.FlyMode.CIRCLE -> {
                text.append(" | Circle")
            }
            DataDecoder.Companion.FlyMode.STABILIZE -> {
                text.append(" | Stabilize")
            }
            DataDecoder.Companion.FlyMode.TRAINING -> {
                text.append(" | Training")
            }
            DataDecoder.Companion.FlyMode.FBWA -> {
                text.append(" | FBWA")
            }
            DataDecoder.Companion.FlyMode.FBWB -> {
                text.append(" | FBWB")
            }
            DataDecoder.Companion.FlyMode.AUTOTUNE -> {
                text.append(" | Autotune")
            }
            DataDecoder.Companion.FlyMode.LOITER -> {
                text.append(" | Loiter")
            }
            DataDecoder.Companion.FlyMode.TAKEOFF -> {
                text.append(" | Takeoff")
            }
            DataDecoder.Companion.FlyMode.AVOID_ADSB -> {
                text.append(" | AVOID_ADSB")
            }
            DataDecoder.Companion.FlyMode.GUIDED -> {
                text.append(" | Guided")
            }
            DataDecoder.Companion.FlyMode.INITIALISING -> {
                text.append(" | Initializing")
            }
            DataDecoder.Companion.FlyMode.LANDING -> {
                text.append(" | Landing")
            }
            DataDecoder.Companion.FlyMode.MISSION -> {
                text.append(" | Mission")
            }
            DataDecoder.Companion.FlyMode.QSTABILIZE -> {
                text.append(" | QSTABILIZE")
            }
            DataDecoder.Companion.FlyMode.QHOVER -> {
                text.append(" | QHOVER")
            }
            DataDecoder.Companion.FlyMode.QLOITER -> {
                text.append(" | QLOITER")
            }
            DataDecoder.Companion.FlyMode.QLAND -> {
                text.append(" | QLAND")
            }
            DataDecoder.Companion.FlyMode.QRTL -> {
                text.append(" | QRTL")
            }
            DataDecoder.Companion.FlyMode.QAUTOTUNE -> {
                text.append(" | QAUTOTUNE")
            }
            DataDecoder.Companion.FlyMode.QACRO -> {
                text.append(" | QACRO")
            }
            DataDecoder.Companion.FlyMode.AUTONOMOUS -> {
                text.append(" | Autonomous")
            }
            DataDecoder.Companion.FlyMode.RATE -> {
                text.append(" | Rate")
            }
            null -> {
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        map?.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        map?.onSaveInstanceState(outState)
        outState?.putBoolean("follow_mode", followMode)
        preferenceManager.setFullscreenWindow(fullscreenWindow)
    }

    override fun onStart() {
        super.onStart()
        started = true
        attachTerrain()
        map?.onStart()
        if (preferenceManager.showArtificialHorizonView()) {
            horizonView.visibility = View.VISIBLE
        } else {
            horizonView.visibility = View.GONE
        }

        updateHorizonViewSize()

        updateSensorsPlacement()
    }

    private fun updateSensorsPlacement() {
        val sensorsSettings = preferenceManager.getSensorsSettings().sortedBy { it.index }
        topList.removeAllViews()
        bottomList.removeAllViews()
        sensorsSettings.forEach {
            val sensorView = sensorViewMap[it.name]
            val usable = dataService?.terrainMonitor?.isAvailable == true || it.name !in PreferenceManager.terrainSensors
            sensorView?.visibility = if (it.shown && usable) View.VISIBLE else View.GONE
            if (it.position == "top") {
                topList.addView(sensorView)
            } else {
                bottomList.addView(sensorView)
            }
        }
    }

    private fun connect() {
        lastConnectionType = CONNTYPE_NONE;
        val showcaseView = MaterialShowcaseView.Builder(this)
            .renderOverNavigationBar()
            .setTarget(replayButton)
            .setMaskColour(Color.argb(230, 0, 0, 0))
            .setDismissText("GOT IT")
            .setContentText("You can replay your logged flights by clicking this button")
            .setListener(
                object : IShowcaseListener {
                    override fun onShowcaseDismissed(showcaseView: MaterialShowcaseView?) {
                        connect();
                    }
                    override fun onShowcaseDisplayed(showcaseView: MaterialShowcaseView?) {
                    }
                })
            .singleUse("replay_guide").build()

        var items = arrayOf(
            "Bluetooth",
            "Bluetooth LE",
            "USB Serial"
        )

        if (showcaseView.hasFired()) {
            this.showDialog(AlertDialog.Builder(this)
                .setAdapter(
                    ArrayAdapter(
                        this,
                        android.R.layout.simple_list_item_1,
                        items.map { i ->
                            if (i == lastSelectedDataPooler) {
                                val boldOption = SpannableString(i)
                                boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, i.length, 0)
                                boldOption
                            } else {
                                i
                            }
                        })
                ) { dialogInterface, i ->
                    lastSelectedDataPooler = items[i]
                    preferenceManager.setLastSelectedDataPooler(lastSelectedDataPooler)
                    when (i) {
                        0 -> connectBluetooth()
                        1 -> connectBluetoothLE()
                        2 -> connectUSB()
                    }
                }
                .setTitle("Choose connection method")
                .create())
        } else {
            showcaseView.show(this)
        }
    }

    /** The raw (.tlm) and CSV logs both need the write permission. */
    private fun isAnyLoggingEnabled(): Boolean {
        return preferenceManager.isLoggingEnabled() || preferenceManager.isCSVLoggingEnabled()
    }

    private fun connectUSB() {
        // without the permission the logs are skipped silently: ask first, like Bluetooth does
        if (isAnyLoggingEnabled()) {
            if (!requestWritePermission(RequestWritePermissionSequenceType.CONNECT_USB)) return
        }
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull()
        if (driver == null) {
            Toast.makeText(this, "No valid usb driver has been found", Toast.LENGTH_SHORT).show()
        } else {
            val connection = usbManager.openDevice(driver.device)
            if (connection != null) {
                val port = driver.ports.firstOrNull()
                if (port == null) {
                    Toast.makeText(this, "No valid usb port has been found", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    connectToUSBDevice(port, connection)
                }
            } else {
                val pendingIntent =
                    PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_DEVICE), 0)
                registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (ACTION_USB_DEVICE == intent?.action) {
                            synchronized(this) {
                                val device: UsbDevice? =
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                                if (intent.getBooleanExtra(
                                        UsbManager.EXTRA_PERMISSION_GRANTED,
                                        false
                                    )
                                ) {
                                    device?.apply {
                                        connectUSB()
                                    }
                                } else {
                                    Toast.makeText(
                                        this@MapsActivity,
                                        "You need to allow permission in order to connect with a usb",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }

                        unregisterReceiver(this)
                    }
                }, IntentFilter(ACTION_USB_DEVICE))
                usbManager.requestPermission(driver.device, pendingIntent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // settings (traffic, flight plans...) may have changed while away
        dataService?.reloadSettings()
        map?.onResume()
        updateWindowFullscreenDecoration()
        updateScreenOrientation()
        drawFlightPlans()
        updateHomeLine()
        applySensorScale()
        // Settings (height limit, altitude reference, antenna) may have changed while away
        reloadTerrainIfChanged()
        dataService?.terrainMonitor?.requestUpdate()
        if (checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1f, phoneLocationListener)
        }
    }

    override fun onPause() {
        super.onPause()
        map?.onPause()
        dataService?.replay?.pause()
        updateFullscreenState()//check if user has brought system ui with swipe
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(phoneLocationListener)
    }

    override fun onStop() {
        super.onStop()
        started = false
        detachTerrain()
        map?.onStop()
        dataService?.replay?.pause()
    }

    private fun connectBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            this.showDialog(
                AlertDialog.Builder(this)
                    .setMessage("It seems like your phone does not have bluetooth, or it does not supported")
                    .setPositiveButton("OK", null)
                    .create()
            )
            return
        }

        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }
        if (isAnyLoggingEnabled()) {
            if (!requestWritePermission(RequestWritePermissionSequenceType.CONNECT)) return;
        }

        val devices = ArrayList<BluetoothDevice>(adapter.bondedDevices)
        var deviceNames = ArrayList<String>(devices.map {
            var result = it.name
            if (result == null) {
                result = it.address
            }
            if (result == null) {
                result = "*noname*"
            }
            result
        })

        deviceNames = augmentNonUniqueDiviceNames(deviceNames, devices.map { i-> i.address })

        var deviceNames1 = deviceNames.mapIndexed { index, i ->
            if ( devices[index].address == lastSelectedBluetoothDeviceAddress ) {
                val boldOption = SpannableString(i)
                boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, i.length, 0)
                boldOption
            } else {
                i
            }
        }.toMutableList()

        val deviceAdapter = ArrayAdapter( this, android.R.layout.simple_list_item_1, deviceNames1)

        var dialog = AlertDialog.Builder(this).setOnDismissListener {
        } .setNeutralButton(R.string.pair_new_device) { dialog, which ->
            showPairDeviceDialog()
        }.setAdapter(deviceAdapter) { _, i ->
            lastSelectedBluetoothDeviceAddress = devices[i].address;
            preferenceManager.setLastSelectedBluetoothDeviceAddress(lastSelectedBluetoothDeviceAddress)
            runOnUiThread {
                connectToBluetoothDevice(devices[i], false)
            }
        }.create()

        dialog.setOnShowListener {
            val alertDialog = it as AlertDialog
            var index = devices.indexOfFirst {i -> i.address == lastSelectedBluetoothDeviceAddress}
            if ( index != -1) {
                val centerY = alertDialog.listView.height / 2 // Calculate the center position vertically
                alertDialog.listView.smoothScrollToPositionFromTop(index, centerY)
            }
        }

        this.showDialog(dialog)
    }

    private fun augmentNonUniqueDiviceNames(deviceNames : ArrayList<String>, deviceAddr : List<String>) : ArrayList<String>
    {
        return ArrayList(deviceNames.mapIndexed { index, i ->
            var i1 = deviceNames.indexOf(i)
            var i2 = deviceNames.lastIndexOf(i)
            if (i1 != i2) {
                "${deviceNames[index]} (${deviceAddr[index]})"
            } else {
                i
            }
        })
    }

    private fun showPairDeviceDialog() {
        val devices = ArrayList<BluetoothDevice>()
        val deviceNames = ArrayList<String>()
        val deviceAdapter =
            ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, deviceNames)
        AlertDialog.Builder(this)
            .setAdapter(deviceAdapter) { _, i ->
                BluetoothAdapter.getDefaultAdapter().cancelDiscovery()
                pairDevice(devices[i])
            }.show()
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        unregisterReceiver(this)
                    }
                    BluetoothDevice.ACTION_FOUND -> {
                        val device =
                            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)!!
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                            ?: device.address
                        if (!deviceNames.contains(name) && device.bondState == BluetoothDevice.BOND_NONE) {
                            devices.add(device)
                            deviceNames.add(name)
                            deviceAdapter.notifyDataSetChanged()
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {

                    }
                }
            }
        }
        registerReceiver(listener, IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
        })
        BluetoothAdapter.getDefaultAdapter().startDiscovery()
    }

    private fun pairDevice(bluetoothDevice: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (!bluetoothDevice.createBond()) {
                Toast.makeText(this, "Failed to pair bluetooth device", Toast.LENGTH_LONG).show()
            } else {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        if (intent?.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                            val device =
                                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            val newBondState: Int =
                                intent.getIntExtra(
                                    BluetoothDevice.EXTRA_BOND_STATE,
                                    BluetoothDevice.BOND_NONE
                                )
                            if (newBondState == BluetoothDevice.BOND_BONDED) {
                                device?.let { connectToBluetoothDevice(it, false) }
                                unregisterReceiver(this)
                            } else if (newBondState == BluetoothDevice.BOND_NONE) {
                                Toast.makeText(
                                    this@MapsActivity,
                                    "Failed to pair new device",
                                    Toast.LENGTH_LONG
                                ).show()
                                unregisterReceiver(this)
                            }
                        }
                    }
                }

                registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            }
        } else {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.pair_not_supported_message))
                .show()
        }
    }

    private fun connectBluetoothLE() {
        if (!bleCheck()) {
            Toast.makeText(
                this,
                "Bluetooth LE is not supported or application does not have needed permissions",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            this.showDialog(
                AlertDialog.Builder(this)
                    .setMessage("It seems like your phone does not have bluetooth, or it does not supported")
                    .setPositiveButton("OK", null)
                    .create()
            )
            return
        }

        if (!adapter.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }
        if (isAnyLoggingEnabled()) {
            if (!requestWritePermission(RequestWritePermissionSequenceType.CONNECT)) return;
        }

        val devices = ArrayList<BluetoothDevice>(adapter.bondedDevices)
        var deviceNames = ArrayList<String>(devices.map {
            var result = it.name
            if (result == null) {
                result = it.address
            }
            if (result == null) {
                result = "*noname*"
            }
            result
        })

        deviceNames = augmentNonUniqueDiviceNames(deviceNames, devices.map {i -> i.address})

        var deviceNames1 = deviceNames.mapIndexed { index, i ->
            if ( devices[index].address == lastSelectedBLEDeviceAddress ) {
                val boldOption = SpannableString(i)
                boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, i.length, 0)
                boldOption
            } else {
                i
            }
        }.toMutableList()

        val deviceAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames1)

        var scrolled = false;
        var dialog: AlertDialog? = null;

        val callback = BluetoothAdapter.LeScanCallback { bluetoothDevice, i, bytes ->
            if (!devices.contains(bluetoothDevice) && bluetoothDevice.name != null) {
                devices.add(bluetoothDevice)
                var name1 = bluetoothDevice.name
                if ( deviceNames.indexOf( name1) >= 0 ) {
                    name1 = "${bluetoothDevice.name} (${bluetoothDevice.address})"
                }
                if ( lastSelectedBLEDeviceAddress == bluetoothDevice.address) {
                    val boldOption = SpannableString(name1)
                    boldOption.setSpan(StyleSpan(Typeface.BOLD), 0, name1.length, 0)
                    deviceNames1.add(boldOption)

                    if ( dialog is AlertDialog && scrolled) {
                        runOnUiThread {
                            var index = devices.indexOfFirst {i -> i.address == lastSelectedBLEDeviceAddress}
                            if ( index != -1) {
                                val alertDialog = dialog as AlertDialog
                                if ( alertDialog != null ) {
                                    val centerY =
                                        alertDialog.listView.height / 2 // Calculate the center position vertically
                                    alertDialog.listView.smoothScrollToPositionFromTop(
                                        index,
                                        centerY
                                    )
                                }
                            }
                        }
                    }
                }
                else {
                    deviceNames1.add(name1)
                }
                deviceAdapter.notifyDataSetChanged()
            }
        }

        if (bleCheck()) {
            adapter.startLeScan(callback)
        }

        dialog = AlertDialog.Builder(this).setOnDismissListener {
            if (bleCheck()) {
                adapter.stopLeScan(callback)
            }
        }.setAdapter(deviceAdapter) { _, i ->
            lastSelectedBLEDeviceAddress = devices[i].address;
            preferenceManager.setLastSelectedBLEDeviceAddress(lastSelectedBLEDeviceAddress)
            if (bleCheck()) {
                adapter.stopLeScan(callback)
            }
            runOnUiThread {
                connectToBluetoothDevice(devices[i], true)
            }
        }.create()

        dialog.setOnShowListener {
            val alertDialog = it as AlertDialog
            var index = devices.indexOfFirst {i -> i.address == lastSelectedBLEDeviceAddress}
            if ( index != -1) {
                val centerY = alertDialog.listView.height / 2 // Calculate the center position vertically
                alertDialog.listView.smoothScrollToPositionFromTop(index, centerY)
            }
            scrolled = true;
        }

        this.showDialog(dialog)
    }

    private fun resetUI() {
        // the phone's battery is known whatever the link does
        if (lastPhoneBattery > 0) updatePhoneBattery() else phoneBattery.text = "-"
        render()
    }

    private fun bleCheck() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 && ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED


    private fun connectToBluetoothDevice(device: BluetoothDevice, isBLE: Boolean) {
        if ( isBLE ) {
            lastConnectionType = CONNTYPE_BLE;
        }
        else {
            lastConnectionType = CONNTYPE_BT;
        }
        startDataService()
        dataService?.let {
            connectButton.text = getString(R.string.connecting)
            connectButton.isEnabled = false
            it.connect(device, isBLE)
        }
    }


    private fun connectToUSBDevice(
        port: UsbSerialPort,
        connection: UsbDeviceConnection
    ) {
        lastConnectionType = CONNTYPE_USB;
        startDataService()
        dataService?.let {
            connectButton.text = getString(R.string.connecting)
            connectButton.isEnabled = false
            it.connect(port, connection)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderHandler.removeCallbacks(renderTask)
        events?.close()
        events = null
        unregisterReceiver(stoppedReceiver)
        headingPolyline = null;
        polyLine = null;
        homeLine = null
        losBlockedLines.clear()
        flightPlanLines.clear()
        flightPlanLabels.clear()
        detachTerrain()
        map?.onDestroy()
        // the data service goes on without a screen (traffic warnings, the flight); it ends
        // from its notification
        map = null;
        this.unregisterReceiver(this.batInfoReceiver)
        unbindService(serviceConnection)
    }

    private fun startDataService() {
        val intent = Intent(this, DataService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        startService(intent)
        bindService(intent, serviceConnection, 0)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty()) {
            if (requestCode == REQUEST_LOCATION_PERMISSION) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    map?.isMyLocationEnabled = true
                } else {
                    this.showDialog(AlertDialog.Builder(this)
                        .setMessage("Location permission is needed in order to discover BLE devices and show your location on map")
                        .setPositiveButton("OK", null)
                        .create())
                }
            } else if (requestCode == REQUEST_WRITE_PERMISSION) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    when (requestWritePermissionSequence) {
                        RequestWritePermissionSequenceType.CONNECT -> connect()
                        RequestWritePermissionSequenceType.CONNECT_USB -> connectUSB()
                        RequestWritePermissionSequenceType.DELETE -> showDeleteLogDialog()
                        RequestWritePermissionSequenceType.RENAME -> showRenameLogDialog()
                        RequestWritePermissionSequenceType.EXPORT_GPX -> showExportGPXDialog()
                        RequestWritePermissionSequenceType.EXPORT_KML -> showExportKMLDialog1()
                    }
                    requestWritePermissionSequence = RequestWritePermissionSequenceType.NONE;
                } else {
                    this.showDialog(
                        AlertDialog.Builder(this)
                            .setMessage("Write permission is required in order to log telemetry data. Disable logging or grant permission to continue")
                            .setPositiveButton("OK", null)
                            .create()
                    )
                }
            } else if (requestCode == REQUEST_READ_PERMISSION) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    replay()
                } else {
                    this.showDialog(
                        AlertDialog.Builder(this)
                            .setMessage("Read permission is required in order to read and replay telemetry data")
                            .setPositiveButton("OK", null)
                            .create()
                    )
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT && resultCode == Activity.RESULT_OK) {
            connectBluetooth()
        }
    }



    /**
     * The render rate: the telemetry comes much faster than it is worth redrawing the screen.
     * A replay being played or scrubbed is drawn at the rate the player steps.
     */
    private fun renderIntervalMs(): Long {
        val replayMoving = dataService?.replay?.let { it.playing || seekBarDragging } ?: false
        return if (replayMoving) 50L else 333L
    }

    /** Draws the telemetry state: the readouts, the aircraft and its track. */
    // what render() showed last: a view set to the same again would be laid out and drawn again
    private var shownRoll = Float.NaN
    private var shownPitch = Float.NaN
    private var shownRcChannels: IntArray? = null

    /**
     * Sets the text only when it changes: setting a readout's text, even the same one, lays out
     * the whole screen and redraws it, about 30 ms on a slow tablet.
     */
    private fun TextView.setTextIfChanged(value: CharSequence) {
        if (text.toString() != value.toString()) text = value
    }

    /** Draws the flight operation: the readouts, the aircraft and its track, the traffic, the terrain. */
    private fun render() {
        val operation = dataService?.operation
        val flight = operation?.read { f, _ -> f?.snapshot(renderedTrackResets, renderedTrackSize) }
        val environment = operation?.read { _, e -> e.snapshot() }
        val v = flight?.values ?: FlightValues()

        satellites.setTextIfChanged(v.satellites?.let { if (it == 99) "ES" else it.toString() } ?: "0")
        rssi.setTextIfChanged(v.rssi?.let { if (it == -1) "-" else it.toString() } ?: "-")
        setRSSIIcon(v.rssi ?: 100)
        upLq.setTextIfChanged(v.upLq?.let { if (it == -1) "-" else it.toString() } ?: "-")
        setUPLQIcon(v.upLq ?: 100)
        dnLq.setTextIfChanged(v.dnLq?.let { if (it == -1) "-" else it.toString() } ?: "-")
        setDNLQIcon(v.dnLq ?: 100)
        rssiDbm1.setTextIfChanged(v.rssiDbm1?.let { if (it == 0) "-" else it.toString() } ?: "-")
        setRssiDbm1Icon(v.rssiDbm1 ?: 0)
        rssiDbm2.setTextIfChanged(v.rssiDbm2?.let { if (it == 0) "-" else it.toString() } ?: "-")
        setRssiDbm2Icon(v.rssiDbm2 ?: 0)
        rssiDbmd.setTextIfChanged(v.rssiDbmd?.let { if (it == 0) "-" else it.toString() } ?: "-")
        setRssiDbmdIcon(v.rssiDbmd ?: 0)
        elrsRate.setTextIfChanged(v.elrsMode?.let { elrsModeText(it) } ?: "-")
        upSnr.setTextIfChanged(v.upSnr?.toString() ?: "-")
        dnSnr.setTextIfChanged(v.dnSnr?.toString() ?: "-")
        ant.setTextIfChanged(v.antenna?.let { (it + 1).toString() } ?: "-")
        power.setTextIfChanged(v.power?.let { powerText(it) } ?: "-")

        // "Voltage reported by telemetry": the battery's or a cell's
        val reportsBattery = preferenceManager.getReportVoltage() == "Battery"
        val batteryVoltage = if (reportsBattery) v.vbatOrCell ?: v.voltage else v.voltage
        val cellVoltage = if (reportsBattery) v.cellVoltage else v.vbatOrCell ?: v.cellVoltage
        voltage.setTextIfChanged(batteryVoltage?.let { "${"%.2f".format(it)} V" } ?: "-")
        cell_voltage.setTextIfChanged(cellVoltage?.let { "${"%.2f".format(it)} V" } ?: "-")
        current.setTextIfChanged(v.current?.let { "${"%.2f".format(it)} A" } ?: "-")
        renderFuel(v.fuel, cellVoltage)

        speed.setTextIfChanged(v.speed?.let { "${it.roundToInt()} km/h" } ?: "-")
        airspeed.setTextIfChanged(v.airSpeed?.let { "${it.roundToInt()} km/h" } ?: "-")
        vspeed.setTextIfChanged(v.vSpeed?.let { "${"%.1f".format(it)} m/s" } ?: "-")
        altitude.setTextIfChanged(v.altitude?.let { formatHeight(it) } ?: "-")
        altitude_msl.setTextIfChanged(v.gpsAltitude?.let { formatHeight(it) } ?: "-")
        distance.setTextIfChanged(v.distance?.let { formatDistance(it.toFloat()) } ?: "-")
        traveled_distance.setTextIfChanged(formatDistance((flight?.traveledDistance ?: 0.0).toFloat()))
        throttle.setTextIfChanged(v.throttle?.toString() ?: "-")

        val rate = flight?.telemetryRate ?: 0
        tlmRate.setTextIfChanged(if (rate < 1000) "$rate b/s" else "${"%.1f".format(rate / 1000f)} kb/s")
        val stale = if (flight?.kind == Flight.Kind.LIVE) flight.stale else emptySet()
        statustext.setTextIfChanged(if (Sensor.STATUSTEXT in stale) "" else v.statusText ?: "")
        mode.setTextIfChanged(modeText(flight))
        val roll = v.roll ?: 0f
        val pitch = v.pitch ?: 0f
        if (roll != shownRoll) horizonView.setRoll(roll)
        if (pitch != shownPitch) horizonView.setPitch(pitch)
        shownRoll = roll
        shownPitch = pitch
        v.rcChannels?.let { channels -> if (shownRcChannels?.contentEquals(channels) != true) rc_widget.setChannels(channels) }
        shownRcChannels = v.rcChannels
        renderStale(stale)
        val rateAlpha = if (flight?.kind == Flight.Kind.REPLAY) 0.5f else 1f
        if (tlmRate.alpha != rateAlpha) tlmRate.alpha = rateAlpha

        renderAircraft(flight)
        renderTerrain(flight?.terrain ?: TerrainMonitor.EMPTY_STATE)
        renderTraffic(environment?.traffic ?: emptyList())
        renderReplay()
    }

    /** Readouts whose values have not come for a while are greyed. */
    private fun renderStale(stale: Set<Sensor>) {
        sensorViews.forEach { (sensor, views) ->
            val alpha = if (sensor in stale) 0.5f else 1f
            views.forEach { if (it.alpha != alpha) it.alpha = alpha }
        }
    }

    private val sensorViews: Map<Sensor, List<View>> by lazy {
        mapOf(
            Sensor.GPS to listOf(satellites, traveled_distance),
            Sensor.DISTANCE to listOf<View>(distance),
            Sensor.ALTITUDE to listOf<View>(altitude),
            Sensor.GPS_ALTITUDE to listOf<View>(altitude_msl),
            Sensor.RSSI to listOf<View>(rssi),
            Sensor.UP_LQ to listOf<View>(upLq),
            Sensor.DN_LQ to listOf<View>(dnLq),
            Sensor.ELRS_MODE to listOf<View>(elrsRate),
            Sensor.VOLTAGE to listOf<View>(voltage),
            Sensor.CELL_VOLTAGE to listOf<View>(cell_voltage),
            Sensor.CURRENT to listOf<View>(current),
            Sensor.SPEED to listOf<View>(speed),
            Sensor.AIRSPEED to listOf<View>(airspeed),
            Sensor.VSPEED to listOf<View>(vspeed),
            Sensor.THROTTLE to listOf<View>(throttle),
            Sensor.FUEL to listOf<View>(fuel),
            Sensor.RC_CHANNELS to listOf<View>(rc_widget),
            Sensor.DN_SNR to listOf<View>(dnSnr),
            Sensor.UP_SNR to listOf<View>(upSnr),
            Sensor.ANT to listOf<View>(ant),
            Sensor.POWER to listOf<View>(power),
            Sensor.RSSI_DBM_1 to listOf<View>(rssiDbm1),
            Sensor.RSSI_DBM_2 to listOf<View>(rssiDbm2),
            Sensor.RSSI_DBM_D to listOf<View>(rssiDbmd)
        )
    }

    /** The replay's seek bar and play button follow the playback. */
    private fun renderReplay() {
        val session = dataService?.replay ?: return
        if (session.size < 0) return
        if (!seekBarDragging && seekBar.progress != session.position) seekBar.progress = session.position
        val icon = if (session.playing) R.drawable.ic_pause else R.drawable.ic_play
        if (playButton.tag != icon) {
            playButton.tag = icon
            playButton.setImageDrawable(ContextCompat.getDrawable(this, icon))
        }
    }

    private fun onEvent(event: Event) {
        when (event) {
            is Event.Connected -> {
                Toast.makeText(this, "Connected!", Toast.LENGTH_SHORT).show()
                switchToConnectedState()
            }
            is Event.Disconnected -> {
                Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
                switchToIdleState()
            }
            is Event.LinkLost -> Toast.makeText(this, "Link lost", Toast.LENGTH_SHORT).show()
            is Event.Reconnecting -> Toast.makeText(this, "Reconnecting", Toast.LENGTH_SHORT).show()
            is Event.ConnectionFailed -> {
                Toast.makeText(this, "Connection failed", Toast.LENGTH_SHORT).show()
                // a first connection that never came up: nothing to show
                if (dataService?.isConnected() != true) switchToIdleState()
            }
            is Event.ProtocolDetected -> Toast.makeText(this, "Protocol: ${event.name}", Toast.LENGTH_SHORT).show()
            is Event.TrafficWarning -> Toast.makeText(this, Alerts.shortWarning(event), Toast.LENGTH_LONG).show()
        }
    }

    private fun renderAircraft(flight: FlightSnapshot?) {
        val v = flight?.values
        hasGPSFix = v?.gpsFix ?: false
        replayHome = flight?.home
        if (flight == null) {
            if (renderedTrackResets != -1) polyLine?.clear()
            renderedTrackResets = -1
            renderedTrackSize = 0
        } else {
            // a new track, or a screen that has not drawn this one yet: from its start
            if (flight.trackResets != renderedTrackResets || flight.newPointsFrom == 0) polyLine?.clear()
            if (flight.newPoints.isNotEmpty()) polyLine?.submitPoints(flight.newPoints)
            renderedTrackResets = flight.trackResets
            renderedTrackSize = flight.trackSize
        }
        commitRouteLinePoints()

        val position = v?.position
        if (position == null) {
            lastGPS = Position(0.0, 0.0)
        } else if (position != lastGPS) {
            lastGPS = position
            marker?.let { it.position = lastGPS }
            updateHomeLine()
            updateHeading()
            if (followMode) {
                if (map?.initialized() ?: false) {
                    map?.moveCamera(lastGPS)
                }
            } else {
                this.map?.invalidate()
            }
        }
        val heading = v?.heading
        if (heading != null && heading != lastHeading) {
            lastHeading = heading
            marker?.rotation = heading
            updateHeading()
        }
        tryCreateMarker()
    }

    private fun renderFuel(fuel: Int?, cellVoltage: Float?) {
        if (fuel == null) {
            this.fuel.setTextIfChanged("-")
            setFuelIcon(-1)
            return
        }
        val batteryUnits = preferenceManager.getBatteryUnits()
        var percentage = fuel
        when (batteryUnits) {
            "mAh", "mWh" -> {
                this.fuel.setTextIfChanged(this.formatPower(fuel, batteryUnits))
                //for icon, calculate percentage from cell voltage if available
                val cell = cellVoltage ?: 0f
                percentage = if ((cell > 0) && (cell <= 4.4)) {
                    ((1 - (4.2f - cell)).coerceIn(0f, 1f) * 100).toInt()
                } else {
                    -1  //unknown icon
                }
            }
            "Percentage" -> {
                this.fuel.setTextIfChanged("$fuel%")
            }
        }
        setFuelIcon(percentage)
    }

    private fun elrsModeText(mode: Int): String {
        return when (mode) {
            13 -> "F1000"
            12 -> "F500"
            11 -> "D500"
            10 -> "D250"
            9 -> "L500"
            8 -> "L333c" //8ch
            7 -> "L250"
            6 -> "L200"
            5 -> "L150"
            4 -> "L100"
            3 -> "L100c"  //8ch
            2 -> "L50"
            1 -> "L25"
            0 -> "L4"
            else -> mode.toString()
        }
    }

    private fun powerText(power: Int): String {
        return when (power) {
            1 -> "10mW"
            2 -> "25mW"
            3 -> "100mW"
            4 -> "500mW"
            5 -> "1W"
            6 -> "2W"
            7 -> "250mW"
            8 -> "50mW"
            else -> power.toString()
        }
    }

    private fun formatDistance(v: Float): String {
        if (v < 1000) {
            return "${"%.0f".format(v)} m"
        } else {
            return "${"%.2f".format(v / 1000)} km"
        }
    }

    private fun formatHeight(v: Float): String {
        if (v < 10) {
            return "${"%.2f".format(v)} m"
        } else if (v < 100) {
            return "${"%.1f".format(v)} m"
        } else if (v < 1000) {
            return "${"%.0f".format(v)} m"
        } else {
            return "${"%.2f".format(v / 1000)} km"
        }
    }











    //should be called on ui thread
    fun tryCreateMarker() {
        if (this.hasGPSFix && marker == null && (map?.initialized() ?: false) && lastGPS.lat != 0.0 && lastGPS.lon != 0.0) {
            if (headingPolyline == null && preferenceManager.isHeadingLineEnabled()) {
                headingPolyline = createHeadingPolyline()
                updateHeading()
            }
            marker =
                map?.addMarker(R.drawable.ic_plane, preferenceManager.getPlaneColor(), lastGPS)
            marker?.rotation = lastHeading;
            map?.moveCamera(lastGPS, 15f)
        }
    }

    private fun createHeadingPolyline(): MapLine? {
        return map?.addPolyline(3f, preferenceManager.getHeadLineColor(), lastGPS, lastGPS)
    }

    private fun setRSSIIcon(rssi: Int) {
        when (rssi) {
            in 81..100 -> R.drawable.ic_rssi_5
            in 61..80 -> R.drawable.ic_rssi_4
            in 41..69 -> R.drawable.ic_rssi_3
            in 21..40 -> R.drawable.ic_rssi_2
            in 1..20 -> R.drawable.ic_rssi_1
            0 -> R.drawable.ic_rssi_0
            else -> R.drawable.ic_rssi_5
        }.let {
            // a new icon lays the screen out again: only when it changes
            if (this.rssi.tag == it) return@let
            this.rssi.tag = it
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.rssi.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.rssi.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun setUPLQIcon(lq: Int) {
        when (lq) {
            in 81..100 -> R.drawable.ic_up_lq_5
            in 61..80 -> R.drawable.ic_up_lq_4
            in 41..69 -> R.drawable.ic_up_lq_3
            in 21..40 -> R.drawable.ic_up_lq_2
            in 1..20 -> R.drawable.ic_up_lq_1
            0 -> R.drawable.ic_up_lq_0
            else -> R.drawable.ic_up_lq_5
        }.let {
            // a new icon lays the screen out again: only when it changes
            if (this.upLq.tag == it) return@let
            this.upLq.tag = it
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.upLq.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.upLq.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun setDNLQIcon(lq: Int) {
        when (lq) {
            in 81..100 -> R.drawable.ic_dn_lq_5
            in 61..80 -> R.drawable.ic_dn_lq_4
            in 41..69 -> R.drawable.ic_dn_lq_3
            in 21..40 -> R.drawable.ic_dn_lq_2
            in 1..20 -> R.drawable.ic_dn_lq_1
            0 -> R.drawable.ic_dn_lq_0
            else -> R.drawable.ic_dn_lq_5
        }.let {
            // a new icon lays the screen out again: only when it changes
            if (this.dnLq.tag == it) return@let
            this.dnLq.tag = it
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.dnLq.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.dnLq.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun setRssiDbm1Icon(rssi: Int) {
        when (rssi) {
            in -31..0 -> R.drawable.ic_rssi_dbm1_5
            in -51..-30 -> R.drawable.ic_rssi_dbm1_4
            in -71..-59 -> R.drawable.ic_rssi_dbm1_3
            in -91..-70 -> R.drawable.ic_rssi_dbm1_2
            in -120..-90 -> R.drawable.ic_rssi_dbm1_1
            0 -> R.drawable.ic_rssi_dbm1_0
            else -> R.drawable.ic_rssi_dbm1_5
        }.let {
            // a new icon lays the screen out again: only when it changes
            if (this.rssiDbm1.tag == it) return@let
            this.rssiDbm1.tag = it
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.rssiDbm1.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.rssiDbm1.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun setRssiDbm2Icon(rssi: Int) {
        when (rssi) {
            in -31..0 -> R.drawable.ic_rssi_dbm2_5
            in -51..-30 -> R.drawable.ic_rssi_dbm2_4
            in -71..-50 -> R.drawable.ic_rssi_dbm2_3
            in -91..-70 -> R.drawable.ic_rssi_dbm2_2
            in -121..-90 -> R.drawable.ic_rssi_dbm2_1
            0 -> R.drawable.ic_rssi_dbm2_0
            else -> R.drawable.ic_rssi_dbm2_5
        }.let {
            // a new icon lays the screen out again: only when it changes
            if (this.rssiDbm2.tag == it) return@let
            this.rssiDbm2.tag = it
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.rssiDbm2.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.rssiDbm2.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun setRssiDbmdIcon(rssi: Int) {
        when (rssi) {
            in -31..0 -> R.drawable.ic_rssi_dbmd_5
            in -51..-30 -> R.drawable.ic_rssi_dbmd_4
            in -71..50 -> R.drawable.ic_rssi_dbmd_3
            in -91..-70 -> R.drawable.ic_rssi_dbmd_2
            in -120..-90 -> R.drawable.ic_rssi_dbmd_1
            0 -> R.drawable.ic_rssi_dbmd_0
            else -> R.drawable.ic_rssi_dbmd_5
        }.let {
            // a new icon lays the screen out again: only when it changes
            if (this.rssiDbmd.tag == it) return@let
            this.rssiDbmd.tag = it
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.rssiDbmd.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(
                        this,
                        it
                    ), null, null, null
                )
            } else {
                this.rssiDbmd.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }









    private fun showMapTypeSelectorDialog() {
        val fDialogTitle = "Select Map Type"
        val builder = AlertDialog.Builder(this)
        builder.setTitle(fDialogTitle)

        val checkItem = preferenceManager.getMapType() - OsmMapWrapper.MAP_TYPE_DEFAULT

        builder.setSingleChoiceItems(
            MAP_TYPE_ITEMS,
            checkItem
        ) { dialog, item ->
            mapHolder.removeAllViews()
            map = null
            mapType = item + OsmMapWrapper.MAP_TYPE_DEFAULT
            preferenceManager.setMapType(mapType)
            initMap(true)
            dialog.dismiss()
        }

        val fMapTypeDialog = builder.create()
        fMapTypeDialog.setCanceledOnTouchOutside(true)
        this.showDialog(fMapTypeDialog);
    }










    private fun updateHeading() {
        if (lastGPS.lat != 0.0 && lastGPS.lon != 0.0) {
            headingPolyline?.let { headingLine ->
                headingLine.setPoint(0, lastGPS)
                val (offsetLat, offsetLon) = GeoUtils.computeOffset(lastGPS.lat, lastGPS.lon, 1000.0, lastHeading.toDouble())
                headingLine.setPoint(1, Position(offsetLat, offsetLon))
            }
        }
    }



    private fun switchToReplayMode() {
        setFollowMode(true);
        seekBar.setOnSeekBarChangeListener(null)
        seekBar.progress = 0
        menuButton.show()
        connectButton.visibility = View.GONE
        replayButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_close))
        replayButton.setOnClickListener {
            lastConnectionType = CONNTYPE_NONE; //reset last connection type to skip reconnection
            dataService?.closeReplay()
            switchToIdleState()
            replayFileString = null
        }
        lastGPS = Position(0.0, 0.0);
        hasGPSFix = false;
        marker?.remove()
        marker = null
        headingPolyline?.remove()
        headingPolyline = null
    }

    private fun switchToIdleState() {
        resetUI()
        menuButton.hide()
        seekBar.visibility = View.GONE
        playButton.visibility = View.GONE
        connectButton.visibility = View.VISIBLE
        connectButton.text = getString(R.string.connect)
        replayButton.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_replay))
        replayButton.visibility = View.VISIBLE
        replayButton.setOnClickListener {
            replay()
        }
        connectButton.isEnabled = true
        connectButton.setOnClickListener {
            connect()
        }
        marker?.remove()
        marker = null
        polyLine?.clear()
        headingPolyline?.remove()
        headingPolyline = null;
        lastGPS = Position(0.0, 0.0)
        replayHome = null
        homeLine?.clear()
        showLosBlocked()
        map?.invalidate()
    }

    private fun switchToConnectedState() {
        replayButton.visibility = View.GONE
        menuButton.show()
        connectButton.text = getString(R.string.disconnect)
        connectButton.isEnabled = true
        connectButton.setOnClickListener {
            connectButton.isEnabled = false
            connectButton.text = getString(R.string.disconnecting)
            lastConnectionType = CONNTYPE_NONE; //reset last connection type to skip reconnection
            dataService?.disconnect()
        }
    }


    private fun setFuelIcon(percentage: Int) {
        when (percentage) {
            in 91..100 -> R.drawable.ic_battery_full
            in 81..90 -> R.drawable.ic_battery_90
            in 61..80 -> R.drawable.ic_battery_80
            in 51..60 -> R.drawable.ic_battery_60
            in 31..50 -> R.drawable.ic_battery_50
            in 21..30 -> R.drawable.ic_battery_30
            in 0..20 -> R.drawable.ic_battery_alert
            else -> R.drawable.ic_battery_unknown
        }.let {
            // a new icon lays the screen out again: only when it changes
            if (this.fuel.tag == it) return@let
            this.fuel.tag = it
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                this.fuel.setCompoundDrawablesWithIntrinsicBounds(
                    ContextCompat.getDrawable(this, it),
                    null,
                    null,
                    null
                )
            } else {
                this.fuel.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    ContextCompat.getDrawable(this, it),
                    null,
                    null
                )
            }
        }
    }

    private fun formatPower(v: Int, suffix: String): String {
        if (v < 1000) {
            return "$v $suffix"
        } else {
            if (suffix == "mAh") {
                return "${"%.2f".format(v / 1000f)} Ah"
            } else {
                return "${"%.2f".format(v / 1000f)} Wh"
            }
        }
    }











    private fun updateHorizonViewSize() {
        var size = 96.0f;
        var sizeInt = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            size,
            getResources().getDisplayMetrics()
        )
            .toInt();

        var lp = horizonView.getLayoutParams()
        lp.width = sizeInt;
        lp.height = sizeInt;
        horizonView.setLayoutParams(lp);
    }

    private val batInfoReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context?, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            lastPhoneBattery = level
            runOnUiThread {
                updatePhoneBattery()
            }
        }
    }

    private fun updatePhoneBattery() {
        this.phoneBattery.text = "$lastPhoneBattery%"
    }

    private fun showDialog(dialog: AlertDialog) {
        dialog.getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        );
        dialog.show();
        if (!this.fullscreenWindow) {
            dialog.getWindow().decorView.systemUiVisibility = 0
        } else {
            dialog.getWindow().decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE)
        }
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
    }

    protected fun updateScreenOrientation() {
        val screenRotation: String = preferenceManager.getScreenOrientationLock()
        try {
            requestedOrientation = when (screenRotation) {
                "Portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "Landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "Reverse Landscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } catch (e: Exception) {
        }
    }

    //SensorTimeoutListener

    //SensorTimeoutListener

    //SensorTimeoutListener


    fun setFollowMode(mode: Boolean) {
        followMode = mode;
        if (mode) {
            this.followButton.imageAlpha = 255
        } else {
            this.followButton.imageAlpha = 128
        }
    }

    fun commitRouteLinePoints() {
        var maxCount = preferenceManager.getMaxRoutePoints()
        if ( maxCount < 0) {
            maxCount = 10000
        }
        polyLine?.commitPoints(maxCount)
    }

    fun showRenameLogDialog() {
        if (!requestWritePermission(RequestWritePermissionSequenceType.RENAME)) return;

        val currentFileName = replayFileString ?: "";
        val editText = EditText(this)
        editText.setText(currentFileName)

        this.showDialog( AlertDialog.Builder(this)
        .setTitle("Rename Log")
        .setView(editText)
        .setPositiveButton("Rename") { dialog: DialogInterface, which: Int ->
            val newFileName = editText.text.toString()
            renameLog(currentFileName, newFileName)
            dialog.dismiss()
        }
        .setNegativeButton("Cancel") { dialog: DialogInterface, which: Int ->
            dialog.dismiss()
        }.create())
    }

    private fun renameLog(currentFileName: String, newFileName: String) {
        val currentFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), currentFileName)
        val newFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), newFileName)

        if (currentFile.renameTo(newFile)) {
            Toast.makeText(this, "Log renamed successfully.", Toast.LENGTH_SHORT).show()

            val csvCurrentFileName = replaceExtension( currentFileName, ".csv")
            val csvNewFileName = replaceExtension( newFileName, ".csv")
            val csvCurrentFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), csvCurrentFileName)
            val csvNewFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), csvNewFileName)
            csvCurrentFile.renameTo(csvNewFile)

            replayFileString = newFileName;
        } else {
            Toast.makeText(this, "Failed to rename log.", Toast.LENGTH_SHORT).show()
        }
    }


    fun showDeleteLogDialog() {
        if (!requestWritePermission(RequestWritePermissionSequenceType.DELETE)) return;

        this.showDialog( AlertDialog.Builder(this)
        .setTitle("Delete Log")
        .setMessage("Are you sure you want to delete this log?")
        .setPositiveButton("Delete") { dialog: DialogInterface, which: Int ->
            deleteLog(replayFileString ?: "")
            dialog.dismiss()
        }
        .setNegativeButton("Cancel") { dialog: DialogInterface, which: Int ->
            dialog.dismiss()
        }.create())
    }

    fun deleteLog(fileName: String)
    {
        val currentFile = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), fileName)

        if (currentFile.delete()) {
            Toast.makeText(this, "Log deleted successfully.", Toast.LENGTH_SHORT).show()

            val csvFileName = replaceExtension( fileName, ".csv")
            val currentFileCSV = File(Environment.getExternalStoragePublicDirectory("TelemetryLogs"), csvFileName)
            currentFileCSV.delete();

            dataService?.closeReplay()
            switchToIdleState()
            replayFileString = null
        } else {
            Toast.makeText(this, "Failed to delete log.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Exports run off the main thread: a long flight takes a while. */
    private fun exportInBackground(fileName: String, export: (LogPlayer) -> Unit) {
        val player = dataService?.replay?.player ?: return
        val appContext = applicationContext
        Thread {
            val message = try {
                export(player)
                "$fileName saved"
            } catch (e: Exception) {
                "Cannot save $fileName: ${e.message}"
            }
            renderHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
        }.start()
    }

    fun replaceExtension(fileName: String, newExtension : String): String {
        val extensionSeparatorIndex = fileName.lastIndexOf(".")
        if (extensionSeparatorIndex != -1) {
            val nameWithoutExtension = fileName.substring(0, extensionSeparatorIndex)
            return nameWithoutExtension + newExtension
        }
        return fileName
    }

    fun showExportGPXDialog() {
        dataService?.replay?.pause()
        if (!requestWritePermission(RequestWritePermissionSequenceType.EXPORT_GPX)) return;

        val editText = EditText(this)
        editText.setText(dataService?.replay?.player?.launchPointMSLAltitude.toString())
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        editText.filters = arrayOf(InputFilter.LengthFilter(10)) // Set maximum input length, if needed
        editText.setSelection(editText.text.length)

        this.showDialog(AlertDialog.Builder(this)
        .setTitle("Enter launch point MSL altitude, m:")
        .setView(editText)
        .setPositiveButton("OK") { dialog: DialogInterface, which: Int ->
            val enteredNumber = editText.text.toString().toFloatOrNull()
            if (enteredNumber != null) {
                val fileName = replaceExtension( replayFileString?:"", ".gpx")
                exportInBackground(fileName) { player -> player.exportGPX(fileName, enteredNumber) }
            }
            dialog.dismiss()
        }
        .setNegativeButton("Cancel") { dialog: DialogInterface, which: Int ->
            dialog.dismiss()
        }.create())
    }

    fun showExportKMLDialog1() {
        dataService?.replay?.pause()
        if (!requestWritePermission(RequestWritePermissionSequenceType.EXPORT_KML)) return;

        val option1 = "Clamp to ground";
        val option2 = "Relative to ground";
        val option3 = "MSL";
        val options = arrayOf(option1, option2, option3)

        val fileName = replaceExtension( replayFileString?:"", ".kml")

        this.showDialog( AlertDialog.Builder(this)
        .setTitle("Select altitude mode:")
        .setItems(options) { dialog: DialogInterface, which: Int ->
            val selectedOption = options[which]
            when (selectedOption) {
                option1 -> {
                    exportInBackground(fileName) { player -> player.exportKML(fileName, 0.0f, "clampToGround" ) }
                }
                option2 -> {
                    this.showExportKMLDialog2(fileName, "relativeToGround","Adjust track altitude, m:", 0)
                }
                option3 -> {
                    this.showExportKMLDialog2(fileName, "absolute","Enter launch point MSL altitude, m:", dataService?.replay?.player?.launchPointMSLAltitude?:0)
                }
            }
            dialog.dismiss()
        }.create())
    }

    fun showExportKMLDialog2(fileName: String, altitudeMode: String, requestText: String, defaultValue: Int) {
        val editText = EditText(this)
        editText.setText(defaultValue.toString())
        editText.inputType = InputType.TYPE_CLASS_NUMBER
        editText.filters = arrayOf(InputFilter.LengthFilter(10)) // Set maximum input length, if needed
        editText.setSelection(editText.text.length)

        this.showDialog( AlertDialog.Builder(this)
        .setTitle(requestText)
        .setView(editText)
        .setPositiveButton("OK") { dialog: DialogInterface, which: Int ->
            val enteredNumber = editText.text.toString().toFloatOrNull()
            if (enteredNumber != null) {
                exportInBackground(fileName) { player -> player.exportKML(fileName, enteredNumber,altitudeMode) }
            }
            dialog.dismiss()
        }
        .setNegativeButton("Cancel") { dialog: DialogInterface, which: Int ->
            dialog.dismiss()
        }.create())
    }


    fun showSetPlaybackDurationDialog() {
        val options = resources.getStringArray(R.array.playback_durations)
        val options_values = resources.getStringArray(R.array.playback_durations_values)

        this.showDialog( AlertDialog.Builder(this)
        .setTitle("Set playback duration:")
        .setItems(options) { dialog: DialogInterface, which: Int ->
            val v = options_values[which].toInt();
            preferenceManager.setPlaybackDuration(v)
            dataService?.replay?.let {
                if (it.playing) {
                    it.pause()
                    it.play()
                }
            }
            Toast.makeText(this, "Duration changed", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }.create())
    }

    fun requestWritePermission(seq: RequestWritePermissionSequenceType): Boolean {
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_DENIED
        ) {
            requestWritePermissionSequence = seq;
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_PERMISSION
            )
            return false;
        }
        return true;
    }

    // --- FlightRadar24 nearby aircraft ---



    /** The aircraft around the pilot, redrawn when FlightRadar24 brings a new list. */
    private fun renderTraffic(airplanes: List<Aircraft>) {
        if (airplanes === trafficShown) return
        trafficShown = airplanes
        val currentIds = airplanes.map { it.flightId }.toSet()

        // Remove stale markers
        val staleIds = airplaneMarkers.keys.filter { it !in currentIds }
        staleIds.forEach { id ->
            airplaneMarkers.remove(id)?.remove()
        }

        // Update or create markers
        for (airplane in airplanes) {
            val title = airplane.displayName
            val snippet = buildString {
                if (airplane.aircraftType.isNotEmpty()) append(airplane.aircraftType)
                if (airplane.registration.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append(airplane.registration)
                }
                append("\nAlt: ${airplane.altMeters}m | Spd: ${airplane.speedKmh}km/h")
                val route = airplane.route
                if (route.isNotEmpty()) append("\n$route")
            }

            val existing = airplaneMarkers[airplane.flightId]
            if (existing != null) {
                existing.position = Position(airplane.lat.toDouble(), airplane.lon.toDouble())
                existing.rotation = airplane.track.toFloat()
                existing.title = title
                existing.snippet = snippet
            } else {
                val m = map?.addMarker(
                    R.drawable.ic_airplane_fr24,
                    Position(airplane.lat.toDouble(), airplane.lon.toDouble())
                )
                if (m != null) {
                    m.rotation = airplane.track.toFloat()
                    m.title = title
                    m.snippet = snippet
                    airplaneMarkers[airplane.flightId] = m
                }
            }
        }
        map?.invalidate()
    }




    // --- Terrain: height above ground, altitude limit, line of sight ---

    /**
     * Where the receiving antenna is: at the phone, the home end of the home line. A replayed log
     * does not tell where the phone was during the flight and the phone may be anywhere now, so
     * the home of the replay (where the flight started) is used then, again the end of the home
     * line. Without it the terrain monitor falls back to its takeoff point.
     */

    /** "Sensor size" of the settings: the text and icons of both sensor bars. */
    private fun applySensorScale() {
        val scale = preferenceManager.getSensorScale()
        sensorViewMap.values.forEach { (it as? TextViewStableSize)?.setScale(scale) }
    }

    /** Terrain data may have been installed or removed in the settings. */
    private fun reloadTerrainIfChanged() {
        // the monitor sends an empty state when it reloads: no readouts or marks of the old data stay
        if (dataService?.terrainMonitor?.reloadIfChanged() == true) {
            updateSensorsPlacement()
        }
    }

    /**
     * The terrain readouts are calculated by the data service while this screen is visible (and
     * the service is bound). Without a screen nothing is computed that nobody sees.
     */
    private fun attachTerrain() {
        val service = dataService ?: return
        reloadTerrainIfChanged()
        updateSensorsPlacement()
        service.setTerrainActive(started)
    }

    private fun detachTerrain() {
        dataService?.setTerrainActive(false)
    }

    private fun formatTerrainHeight(v: Float): String {
        return when {
            v.isNaN() -> "-"
            v > 2000f -> ">2 km"
            else -> "${v.roundToInt()} m"
        }
    }

    private fun renderTerrain(state: TerrainMonitor.State) {
        heightAboveGround.setTextIfChanged(formatTerrainHeight(state.heightAboveGround))
        heightAboveGround.setTextColor(if (state.aboveLimit) COLOR_SENSOR_ALERT else COLOR_SENSOR_NORMAL)
        altitudeLimit.setTextIfChanged(formatTerrainHeight(state.altitudeLimit))
        altitudeLimit.setTextColor(if (state.aboveLimit) COLOR_SENSOR_ALERT else COLOR_SENSOR_NORMAL)
        groundElevation.setTextIfChanged(formatTerrainHeight(state.groundElevation))
        surfaceElevation.setTextIfChanged(formatTerrainHeight(state.surfaceElevation))
        minLosAltitude.setTextIfChanged(formatTerrainHeight(state.minLosAltitude))
        minLosAltitude.setTextColor(if (state.belowLos) COLOR_SENSOR_ALERT else COLOR_SENSOR_NORMAL)
        losBlocked = state.losBlocked
        showLosBlocked()
    }

    /**
     * Marks in red the stretches of the home line (the straight line from the pilot to the
     * aircraft) where the line of sight is obstructed. Nothing is drawn while the home line is
     * not: switched off, or without a home position.
     */
    private fun showLosBlocked() {
        val homeShown = preferenceManager.isHomeLineEnabled() && (homeLine?.size ?: 0) > 0
        val wanted = if (homeShown) losBlocked else emptyList()
        if (wanted == losBlockedShown) return
        losBlockedShown = wanted
        losBlockedLines.forEach { it.remove() }
        losBlockedLines.clear()
        wanted.forEach { segment ->
            map?.addMarkLine(COLOR_LOS_BLOCKED, segment.from, segment.to)?.let { losBlockedLines.add(it) }
        }
        map?.invalidate()
    }

}
