package com.dji.recreate2

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.remotecontroller.PairingState
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.KeyManager
import dji.v5.manager.SDKManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.manager.interfaces.SDKManagerCallback
import android.os.Environment
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.view.GestureDetector
import android.view.MotionEvent
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.value.gimbal.GimbalSpeedRotation
import android.view.ScaleGestureDetector
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.gimbal.CtrlInfo
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import android.content.Context
import android.content.SharedPreferences
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.SDCardLoadState
import dji.sdk.keyvalue.value.camera.CameraStorageInfos
import org.osmdroid.views.MapView
import org.osmdroid.util.GeoPoint
import androidx.constraintlayout.widget.ConstraintLayout
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.v5.manager.aircraft.rtk.RTKCenter
import dji.sdk.keyvalue.key.RtkMobileStationKey
import dji.sdk.keyvalue.value.rtkmobilestation.RTKPositioningSolution
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.value.flightcontroller.CompassCalibrationState

class MainActivity : AppCompatActivity() {
    
    private var lastLoadedKmzFileName: String? = null
    private var lastLoadedKmzFilePath: String? = null

    private val pickKmzLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: android.net.Uri? ->
        uri?.let {
            try {
                val inputStream = contentResolver.openInputStream(it)
                val file = java.io.File(getExternalFilesDir(null), "local_mission.kmz")
                java.io.FileOutputStream(file).use { output ->
                    inputStream?.copyTo(output)
                }
                showToast("Local KMZ loaded. Pushing to Drone...")
                    executeNativeKMZ(file.absolutePath)
            } catch(e: Exception) {
                e.printStackTrace()
                showToast("Failed to load KMZ: ${e.message}")
            }
        }
    }

    data class TacticalWaypoint(val geoPoint: GeoPoint, var altitude: Double = 50.0, var speed: Double = 5.0, var action: Int = 0, var actionType: String = "FLY", var poiTarget: GeoPoint? = null, var gimbalPitch: Double? = null, var osmdroidMarker: org.osmdroid.views.overlay.Marker? = null, var preflightExecuted: Boolean = false, var heading: Double? = null, var dwellTime: Double? = null, var movementMethod: String = "default", var orbitRadius: Double = 30.0, var orbitLoops: Int = 1, var name: String = "")

    private lateinit var mqttService: MqttService
    private lateinit var fpvSurface: SurfaceView
    private lateinit var pipSurface: SurfaceView
    private lateinit var lrfReticle: android.widget.ImageView
    private lateinit var tvLrfData: TextView
    private lateinit var lensSwitcher: android.widget.LinearLayout
    private lateinit var btnLensWide: TextView
    private lateinit var btnLensZoom: TextView
    private lateinit var btnLensIr: TextView
    private lateinit var btnTogglePip: TextView
    private lateinit var btnLrfToggle: TextView
    private lateinit var mapView: MapView
    private val tacticalWaypoints = java.util.concurrent.CopyOnWriteArrayList<TacticalWaypoint>()
    private lateinit var tvAltitude: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var compassView: CompassView
    private lateinit var tvGps: TextView
    private lateinit var tvCoords: TextView
    private lateinit var tvServerStatus: TextView
    private var tvDroneStatus: TextView? = null
    private var tvRCStatus: TextView? = null
    private var tvEngineStatus: TextView? = null
    private var tvGimbalStatus: TextView? = null
    
    // Dialog TextViews for System Status
    private var dTvDroneModel: TextView? = null
    private var dTvDroneStatus: TextView? = null
    private var dTvRCStatus: TextView? = null
    private var dTvEngineStatus: TextView? = null
    private var dTvSimulatorStatus: TextView? = null
    
    // Status State Strings
    private var currentDroneModelStr = "MOD: UNKNOWN"
    private var currentDroneStatusStr = "DRN: OFFLINE"
    private var currentRcStatusStr = "LNK: OFFLINE"
    private var currentEngineStatusStr = "ENG: INACTIVE"
    private var currentDroneStatusColor = android.graphics.Color.RED
    private var currentRcStatusColor = android.graphics.Color.RED
    private var currentEngineStatusColor = android.graphics.Color.GREEN
    private lateinit var alertThermal: TextView
    private lateinit var alertLow: TextView
    private lateinit var alertStorage: TextView
    private lateinit var alertObstacle: TextView
    private lateinit var alertBlock: TextView
    private lateinit var alertLoss: TextView

    private lateinit var btnDismissAlerts: TextView
    private lateinit var btnSetHome: TextView
    private lateinit var btnPau: TextView
    private lateinit var arHomePoint: TextView
    private lateinit var obstacleRadar: ObstacleRadarView
    
    // AR Home Point State — @Volatile: written from main thread, read from VS background thread
    @Volatile private var homeLat: Double = Double.NaN
    @Volatile private var homeLon: Double = Double.NaN
    @Volatile private var droneLat: Double = Double.NaN
    @Volatile private var droneLon: Double = Double.NaN
    @Volatile private var droneAlt: Double = 0.0
    @Volatile private var droneSpeed: Double = 0.0
    @Volatile private var droneYaw: Double = 0.0
    @Volatile private var gimbalPitch: Double = 0.0
    @Volatile private var gimbalYaw: Double = 0.0
    
    // Config
    private var radarEnabled: Boolean = true
    private var radarMaxDistance: Double = 10.0
    
    private var btnPair: TextView? = null
    private var logText: TextView? = null
    private val logHistory = StringBuffer() // L-04: StringBuffer is thread-safe

    // SAF local folder picker
    private val REQUEST_CODE_PICK_LOCAL_FOLDER = 7241
    private var pendingLocalFolderEditText: android.widget.EditText? = null
    
    
    private var swipeSensitivity = 0.2f
    private var flightSensitivity = 1.0f
    private var invertVertical = false
    private var invertHorizontal = false
    private var rthAltitude = 100
    private var obstacleAction = 0 // 0: BRAKE, 1: BYPASS, 2: OFF
    private var signalLossAction = 0 // 0: GOHOME, 1: LANDING, 2: HOVER
    
    private var currentZoomRatio = 1.0
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var droneConnected = false
    private var rcConnected = false
    private var usingRTK = false
    @Volatile private var droneVx = 0.0
    @Volatile private var droneVy = 0.0
    @Volatile private var droneVz = 0.0
    @Volatile private var isDeadReckoningActive = false
    private var lastGpsFixTime = 0L
    private var deadReckoningLastTime = 0L
    private var curVx = 0.0
    private var curVy = 0.0
    private var curVz = 0.0
    private var satelliteCount = 0
    @Volatile private var rtkSupported = false
    @Volatile private var droneGpsFixType = "NO_GPS"
    private var healthChangeListener: dji.v5.manager.diagnostic.DJIDeviceHealthInfoChangeListener? = null
    private var hasCenteredMap = false
    private var droneBattery = 0
    private var rcBattery = 0
    private var droneSignal = 0
    @Volatile private var isFlying = false
    @Volatile private var isMappingMissionRunning = false
    @Volatile private var droneSatellites = 0
    @Volatile private var upLinkQuality = 0
    @Volatile private var downLinkQuality = 0
    @Volatile private var cellVoltagesList: List<Int> = emptyList()
    @Volatile private var droneFlightMode = "UNKNOWN"
    @Volatile private var isEngineOn = false
    private var heartbeatTimer: java.util.Timer? = null
    private var kmzExecuteStateListener: dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener? = null
    private var kmzWaylineExecutingInfoListener: dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener? = null
    @Volatile private var lastGcsHeartbeatTime = System.currentTimeMillis()
    private val LINK_LOSS_TIMEOUT_MS = 15000L // 15 seconds
    @Volatile private var isLinkLossFailsafeTriggered = false
    @Volatile private var failsafeThread: Thread? = null
    @Volatile private var gimbalRoll: Double = 0.0
    
    private var cachedDroneSn = "UNKNOWN"
    private var cachedDroneType = "UNKNOWN"
    private var cameraFov = 84.0 // Default FOV for standard drones
    
    enum class TouchAction { GIMBAL, FOCUS }
    var currentTouchAction = TouchAction.GIMBAL
    var autoSensitivity = true

    enum class ControlMode { CAM, FLY, MAP }
    var currentControlMode = ControlMode.FLY
    
    private val uiHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var uiHideRunnable: Runnable? = null
    
    private val flightModeHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var flightModeHideRunnable: Runnable? = null
    
    private val hudToggleHideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hudToggleHideRunnable: Runnable? = null

    @Volatile private var isMissionExecuting = false
    private var rtkLocationListener: dji.v5.manager.aircraft.rtk.RTKLocationInfoListener? = null
    private var obstacleDataListener: dji.v5.manager.aircraft.perception.listener.ObstacleDataListener? = null
    private var droneMarker: org.osmdroid.views.overlay.Marker? = null
    private var homeMapMarker: org.osmdroid.views.overlay.Marker? = null
    private var isMapEditMode = false
    private var isSettingPOI = false
    private var selectedWaypointForPOI: TacticalWaypoint? = null
    private var rthMarker: org.osmdroid.views.overlay.Marker? = null
    private var flightPathPolyline: org.osmdroid.views.overlay.Polyline? = null
    private val orbitCircleOverlays = java.util.concurrent.CopyOnWriteArrayList<org.osmdroid.views.overlay.Polyline>() // H-05: thread-safe
    private var headingLine: org.osmdroid.views.overlay.Polyline? = null
    
    enum class MapInteractionType { MOVE, WAYPOINT, SHAPE, POI }
    private var currentMapInteraction = MapInteractionType.MOVE
    
    enum class MappingMode { PROFESSIONAL, QUICK }
    private var activeMappingMode = MappingMode.PROFESSIONAL
    
    private var missionAltitude = 50.0
    private var missionSpeed = 3.0
    
    private val shapePoints = mutableListOf<GeoPoint>()
    private var shapePolygon: org.osmdroid.views.overlay.Polygon? = null
    private var shapePolyline: org.osmdroid.views.overlay.Polyline? = null
    private val shapeVertexMarkers = mutableListOf<org.osmdroid.views.overlay.Marker>()
    
    // Dynamic Grid Preview
    private val previewWaypoints = java.util.concurrent.CopyOnWriteArrayList<TacticalWaypoint>()
    private var previewGridPolyline: org.osmdroid.views.overlay.Polyline? = null
    
    private var isSimulatorActive = false

    // H-04: debounce map redraws to avoid 10Hz updateFlightPathLine calls
    private val mapRedrawHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var mapRedrawRunnable: Runnable? = null

    // M-07: track takeoff wait thread so we can interrupt it on destroy
    @Volatile private var takeoffWaitThread: Thread? = null
    @Volatile private var ledBlinkThread: Thread? = null

    // M-09: debounce grid preview recalculation
    private val gridPreviewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var gridPreviewRunnable: Runnable? = null

    // Persistent Configuration
    private lateinit var sharedPrefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fix CRASH-02: Ensure SDK is registered before proceeding
        if (!dji.v5.manager.SDKManager.getInstance().isRegistered) {
            android.widget.Toast.makeText(this, "SDK not registered, please restart.", android.widget.Toast.LENGTH_LONG).show()
            finish()
            startActivity(android.content.Intent(this, HomeActivity::class.java))
            return
        }

        // Keep screen on (Wakelock)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Load persistent settings
        sharedPrefs = getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        loadConfig()
        
        // Initialize Map
        org.osmdroid.config.Configuration.getInstance().userAgentValue = applicationContext.packageName
        org.osmdroid.config.Configuration.getInstance().load(
            applicationContext, 
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        
        setContentView(R.layout.activity_main)
        
        // Init MQTT Service
        mqttService = MqttService(this)
        startFailsafeMonitor()

        // NOTE: PayloadDetectionManager callbacks are registered AFTER all views are bound (see below line ~500)

        mqttService.onConnectionStatusChanged = { connected ->
            if (connected) {
                lastGcsHeartbeatTime = System.currentTimeMillis()
                isLinkLossFailsafeTriggered = false
            }
            runOnUiThread {
                if (connected) {
                    tvServerStatus.text = "C2: CONNECTED"
                    tvServerStatus.setTextColor(android.graphics.Color.GREEN)
                    android.widget.Toast.makeText(this@MainActivity, "MQTT Connected to Server!", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    tvServerStatus.text = "C2: OFFLINE"
                    tvServerStatus.setTextColor(android.graphics.Color.RED)
                    android.widget.Toast.makeText(this@MainActivity, "MQTT Disconnected!", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        mqttService.onCommandReceived = { json ->
            runOnUiThread {
                android.widget.Toast.makeText(this@MainActivity, "MQTT COMMAND RECEIVED: $json", android.widget.Toast.LENGTH_LONG).show()
            }
            handleMqttCommand(json)
        }
        mqttService.onErrorOccurred = { errorMsg ->
            runOnUiThread {
                android.widget.Toast.makeText(this@MainActivity, "MQTT Connection Error: $errorMsg", android.widget.Toast.LENGTH_LONG).show()
                log("MQTT ERROR: $errorMsg")
            }
        }

        // --- S3 UPLOAD STATUS & ALERT LISTENERS ---
        com.dji.recreate2.aws.S3UploadManager.onUploadStartListener = { filename, targetUrl, size ->
            val activeTask = com.dji.recreate2.task.DroneTaskManager.activeTaskId
            if (::mqttService.isInitialized && mqttService.isConnected) {
                val payload = org.json.JSONObject().apply {
                    put("event", "UPLOAD_START")
                    put("filename", filename)
                    put("targetUrl", targetUrl)
                    put("fileSize", size)
                    if (activeTask != null) put("taskId", activeTask)
                }
                mqttService.publishMission(jsonPayload = payload.toString())
            }
        }

        com.dji.recreate2.aws.S3UploadManager.onUploadSuccessListener = { filename, targetUrl, durationMs ->
            val activeTask = com.dji.recreate2.task.DroneTaskManager.activeTaskId
            if (::mqttService.isInitialized && mqttService.isConnected) {
                val payload = org.json.JSONObject().apply {
                    put("event", "UPLOAD_SUCCESS")
                    put("filename", filename)
                    put("targetUrl", targetUrl)
                    put("durationMs", durationMs)
                    if (activeTask != null) put("taskId", activeTask)
                }
                mqttService.publishMission(jsonPayload = payload.toString())
            }
            runOnUiThread {
                showToast("S3 Upload Complete: $filename")
            }
        }

        com.dji.recreate2.aws.S3UploadManager.onUploadFailedListener = { filename, targetUrl, code, error ->
            val activeTask = com.dji.recreate2.task.DroneTaskManager.activeTaskId
            if (::mqttService.isInitialized && mqttService.isConnected) {
                val payload = org.json.JSONObject().apply {
                    put("event", "UPLOAD_FAILED")
                    put("filename", filename)
                    put("targetUrl", targetUrl)
                    put("httpCode", code)
                    put("error", error)
                    if (activeTask != null) put("taskId", activeTask)
                }
                mqttService.publishMission(jsonPayload = payload.toString())
            }
            runOnUiThread {
                val alertText = if (code == 401 || code == 403) {
                    "⚠️ ALERT: S3 Authorization Failed (HTTP $code) - Check Ceph credentials!"
                } else {
                    "⚠️ ALERT: S3 Upload Failed ($filename): $error"
                }
                android.widget.Toast.makeText(this@MainActivity, alertText, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        // --- DRONE TASK MANAGER CALLBACKS ---
        com.dji.recreate2.task.DroneTaskManager.onTaskStateChanged = { task ->
            if (::mqttService.isInitialized && mqttService.isConnected) {
                val payload = org.json.JSONObject().apply {
                    put("event", "TASK_STATE_CHANGED")
                    put("taskId", task.taskId)
                    put("taskName", task.taskName)
                    put("taskType", task.taskType)
                    put("status", task.status)
                    put("queuedCount", com.dji.recreate2.task.DroneTaskManager.queuedCount)
                }
                mqttService.publishMission(jsonPayload = payload.toString())
            }
            runOnUiThread {
                showToast("Task Engine: Task '${task.taskId}' -> ${task.status}")
            }
        }

        com.dji.recreate2.task.DroneTaskManager.onExecuteTaskCallback = { task ->
            runOnUiThread {
                showToast("Executing Task '${task.taskId}'...")
            }
            if (task.waypointsJson != null && task.waypointsJson.isNotEmpty()) {
                try {
                    val json = org.json.JSONObject(task.waypointsJson)
                    handleMqttCommand(json)
                } catch (e: Exception) {
                    android.util.Log.e("TaskEngine", "Failed to parse task waypoints json: ${e.message}")
                }
            }
        }

        com.dji.recreate2.task.DroneTaskManager.onAbortTaskCallback = { task ->
            runOnUiThread {
                showToast("⚠️ ALERT: Task '${task.taskId}' CANCELLED / ABORTED!")
            }
            cancelActiveMission()
        }
        
        // --- DUMMY HEARTBEAT REMOVED ---
        // ------------------------------------
        
        // Auto-connect if server address is saved from Home Activity
        val savedMqttServer = sharedPrefs.getString("mqttServerAddress", "")
        if (!savedMqttServer.isNullOrEmpty()) {
            mqttService.connect(savedMqttServer)
        }

        // Setup Strategy Map
        mapView = findViewById(R.id.mapView)
        val satelliteSource = object : org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase(
            "ArcGIS", 0, 20, 256, ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return baseUrl + org.osmdroid.util.MapTileIndex.getZoom(pMapTileIndex) + "/" +
                        org.osmdroid.util.MapTileIndex.getY(pMapTileIndex) + "/" +
                        org.osmdroid.util.MapTileIndex.getX(pMapTileIndex)
            }
        }
        mapView.setTileSource(satelliteSource)
        mapView.zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.ALWAYS) // L-06: non-deprecated API
        mapView.setMultiTouchControls(true)
        mapView.controller.setZoom(18.0)
        mapView.controller.setCenter(GeoPoint(34.0522, -118.2437)) // Default to LA until GPS lock
        
        val mapEventsReceiver = object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (currentControlMode == ControlMode.FLY) {
                    // Tapped the minimap! Swap to full MAP mode
                    runOnUiThread { findViewById<View>(R.id.btnModeMap).performClick() }
                    return true
                }
                
                if (p != null) {
                    if (!isMapEditMode) {
                        return false // Map is in view-only mode, ignore taps
                    }
                    if (isMissionExecuting) {
                        runOnUiThread { showToast("Map editing is disabled while a mission is executing.") }
                        return false
                    }
                    
                    if (isSettingPOI) {
                        selectedWaypointForPOI?.poiTarget = p
                        showToast("POI SET FOR WP. Action: ${selectedWaypointForPOI?.actionType}")
                        isSettingPOI = false
                        currentMapInteraction = MapInteractionType.WAYPOINT
                        updateFlightPathLine()
                        return true
                    }
                    
                    when (currentMapInteraction) {
                        MapInteractionType.WAYPOINT -> addWaypointMarker(p)
                        MapInteractionType.SHAPE -> addShapePoint(p)
                        MapInteractionType.MOVE -> {
                            tacticalWaypoints.clear()
                            addWaypointMarker(p)
                            executeTacticalMission()
                        }
                        MapInteractionType.POI -> {
                            val poiMarker = org.osmdroid.views.overlay.Marker(mapView)
                            poiMarker.position = p
                            poiMarker.icon = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_poi_flag)
                            poiMarker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                            poiMarker.title = "POI TARGET"
                            poiMarker.setOnMarkerClickListener { m, _ ->
                                android.app.AlertDialog.Builder(this@MainActivity)
                                    .setTitle("POI Target")
                                    .setMessage("Delete this POI?")
                                    .setPositiveButton("DELETE") { _, _ ->
                                        mapView.overlays.remove(m)
                                        mapView.invalidate()
                                    }
                                    .setNegativeButton("CANCEL", null)
                                    .show()
                                true
                            }
                            mapView.overlays.add(poiMarker)
                            mapView.invalidate()
                            showToast("POI Added")
                        }
                    } // End of when
                    return true
                }
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        val mapEventsOverlay = org.osmdroid.views.overlay.MapEventsOverlay(mapEventsReceiver)
        mapView.overlays.add(0, mapEventsOverlay)
        
        // Show user phone location on map
        val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), mapView)
        locationOverlay.enableMyLocation()
        mapView.overlays.add(locationOverlay)
        
        // Setup Drone Marker
        droneMarker = org.osmdroid.views.overlay.Marker(mapView)
        droneMarker?.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_nato_drone)
        droneMarker?.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
        droneMarker?.title = "AEROSTAT / DRONE"
        
        homeMapMarker = org.osmdroid.views.overlay.Marker(mapView)
        homeMapMarker?.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_home_tactical)
        homeMapMarker?.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
        homeMapMarker?.title = "HOME POINT"
        
        rthMarker = org.osmdroid.views.overlay.Marker(mapView)
        rthMarker?.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_nato_waypoint) // Reusing waypoint icon for now
        rthMarker?.title = "H (HOME)"
        rthMarker?.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
        
        headingLine = org.osmdroid.views.overlay.Polyline(mapView)
        headingLine?.outlinePaint?.color = android.graphics.Color.CYAN
        headingLine?.outlinePaint?.strokeWidth = 0.5f
        mapView.overlays.add(headingLine)
        
        // Dynamic Drone Marker Scaling based on Zoom
        mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean = false
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                event?.let {
                    // Zoom level 18 is close, zoom level 10 is far
                    val zoomLevel = it.zoomLevel
                    // At zoom 18, we want scale 1.0. At zoom 12, we want smaller scale.
                    val scale = Math.max(0.3, Math.min(2.5, (zoomLevel - 12) / 6.0)).toFloat()
                    
                    val size = (32 * resources.displayMetrics.density * scale).toInt()
                    
                    droneMarker?.icon?.setBounds(0, 0, size, size)
                    homeMapMarker?.icon?.setBounds(0, 0, size, size)
                    rthMarker?.icon?.setBounds(0, 0, size, size)
                    
                    mapView.invalidate()
                }
                return false
            }
        })

        // Auto hide Android navigation and status bar
        setupImmersiveMode()

        // Bind UI elements
        fpvSurface = findViewById(R.id.fpvSurface)
        pipSurface = findViewById(R.id.pipSurface)
        lrfReticle = findViewById(R.id.lrfReticle)
        tvLrfData = findViewById(R.id.tvLrfData)
        lensSwitcher = findViewById(R.id.lensSwitcher)
        btnLensWide = findViewById(R.id.btnLensWide)
        btnLensZoom = findViewById(R.id.btnLensZoom)
        btnLensIr = findViewById(R.id.btnLensIr)
        btnTogglePip = findViewById(R.id.btnTogglePip)
        btnLrfToggle = findViewById(R.id.btnLrfToggle)
        tvAltitude = findViewById(R.id.tvAltitude)
        tvBattery = findViewById(R.id.tvBattery)
        tvSpeed = findViewById(R.id.tvSpeed)
        compassView = findViewById(R.id.compassView)
        tvGps = findViewById(R.id.tvGps)
        tvCoords = findViewById(R.id.tvCoords)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        // Status TextViews (These will be assigned when the System Dialog is opened)
        tvDroneStatus = null
        tvRCStatus = null
        tvEngineStatus = null
        tvGimbalStatus = null
        
        btnSetHome = findViewById(R.id.btnSetHome)
        btnPau = findViewById(R.id.btnPau)
        arHomePoint = findViewById(R.id.arHomePoint)
        obstacleRadar = findViewById(R.id.obstacleRadar)
        alertThermal = findViewById(R.id.alertThermal)
        alertLow = findViewById(R.id.alertLow)
        alertStorage = findViewById(R.id.alertStorage)
        alertObstacle = findViewById(R.id.alertObstacle)
        alertBlock = findViewById(R.id.alertBlock)
        alertLoss = findViewById(R.id.alertLoss)
        btnDismissAlerts = findViewById(R.id.btnDismissAlerts)
        setupObjectTracking()
        
        // ============================================================
        // PAYLOAD DETECTION CALLBACKS (MUST be after all findViewById)
        // Moving this here fixes CRASH-01: lateinit access before bind
        // ============================================================
        PayloadDetectionManager.onPayloadDetected = { state ->
            runOnUiThread {
                if (!::lensSwitcher.isInitialized) return@runOnUiThread
                if (state.isEnterprise) {
                    lensSwitcher.visibility = View.VISIBLE
                    btnLensZoom.visibility = if (state.availableLenses.contains(dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_ZOOM)) View.VISIBLE else View.GONE
                    btnLensIr.visibility = if (state.availableLenses.contains(dji.sdk.keyvalue.value.common.CameraLensType.CAMERA_LENS_THERMAL)) View.VISIBLE else View.GONE
                } else {
                    lensSwitcher.visibility = View.GONE
                }
                
                if (state.isLrfSupported && ::btnLrfToggle.isInitialized) {
                    btnLrfToggle.visibility = View.VISIBLE
                }
            }
        }
        
        PayloadDetectionManager.onLrfDataUpdated = { distance, lat, lon, alt ->
            runOnUiThread {
                if (!::tvLrfData.isInitialized) return@runOnUiThread
                tvLrfData.text = "LRF: ${String.format("%.1f", distance)}m\nLat: $lat\nLon: $lon"
                
                try {
                    val payload = org.json.JSONObject()
                    payload.put("type", "lrf_target")
                    payload.put("timestamp", System.currentTimeMillis())
                    payload.put("distance", distance)
                    payload.put("lat", lat)
                    payload.put("lon", lon)
                    payload.put("alt", alt)
                    if (::mqttService.isInitialized) {
                        mqttService.publishTelemetry(jsonPayload = payload.toString())
                    }
                } catch (e: Exception) {
                    android.util.Log.e("LRF_Telemetry", "Error packaging/publishing LRF telemetry: ${e.message}", e)
                }
            }
        }
        
        btnLrfToggle.setOnClickListener {
            val isEnabled = tvLrfData.visibility == View.VISIBLE
            if (isEnabled) {
                tvLrfData.visibility = View.GONE
                lrfReticle.visibility = View.GONE
                PayloadDetectionManager.setLaserMeasureEnabled(false)
            } else {
                tvLrfData.visibility = View.VISIBLE
                lrfReticle.visibility = View.VISIBLE
                PayloadDetectionManager.setLaserMeasureEnabled(true)
            }
        }
        
        btnLensWide.setOnClickListener { showToast("Wide Lens Selected") }
        btnLensZoom.setOnClickListener { showToast("Zoom Lens Selected") }
        btnLensIr.setOnClickListener { showToast("IR Lens Selected") }
        
        btnTogglePip.setOnClickListener {
            if (pipSurface.visibility == View.VISIBLE) {
                pipSurface.visibility = View.GONE
            } else {
                pipSurface.visibility = View.VISIBLE
            }
        }
        // ============================================================

        // Setup Delete Waypoint button (visible only in MAP mode)
        val btnDeleteWaypoint = findViewById<TextView>(R.id.btnDeleteWaypoint)
        btnDeleteWaypoint.setOnClickListener {
            // Master Clear: erase ALL mission overlays, polygons, and waypoint queues (preserving drone/home markers)
            mapView.overlays.removeAll { 
                it is org.osmdroid.views.overlay.Marker && it != droneMarker && it != homeMapMarker || 
                it is org.osmdroid.views.overlay.Polygon || 
                (it is org.osmdroid.views.overlay.Polyline && it != headingLine) 
            }
            tacticalWaypoints.clear()
            com.dji.recreate2.sync.WaypointRouteManager.clearAllRoutes()
            
            shapePoints.clear()
            shapeVertexMarkers.clear()
            shapePolygon = null
            shapePolyline = null
            
            previewWaypoints.clear()
            previewGridPolyline = null
            
            findViewById<TextView>(R.id.btnGenerateGrid).visibility = View.GONE
            findViewById<TextView>(R.id.btnExecuteMission).visibility = View.GONE
            findViewById<android.view.View>(R.id.layoutMappingSettings).visibility = View.GONE
            
            currentMapInteraction = MapInteractionType.MOVE
            isMapEditMode = false
            
            updateFlightPathLine()
            mapView.invalidate()
            showToast("Master Map Cleared")
        }

        findViewById<TextView>(R.id.btnOrbit).setOnClickListener {
            if (droneLat.isNaN() || droneLon.isNaN()) {
                showToast("Drone location unknown")
                return@setOnClickListener
            }
            
            tacticalWaypoints.clear()
            mapView.overlays.removeAll { it is org.osmdroid.views.overlay.Marker && it.title?.startsWith("WP") == true }
            
            val radius = 30.0 // 30 meters
            addWaypointMarker(GeoPoint(droneLat, droneLon))
            
            // Fix #2: The UI button now properly sets the single center waypoint to "orbit"
            // rather than generating 16 fake waypoints and marking them all as orbit centers.
            tacticalWaypoints.firstOrNull()?.let { wp ->
                wp.movementMethod = "orbit"
                wp.orbitRadius = radius
                wp.osmdroidMarker?.title = "WP 1 (Orbit Center)"
            }

            updateFlightPathLine()
            showToast("Orbit Route Generated (30m Radius)")
        }

        // Setup Dismiss Alerts button on HUD
        btnDismissAlerts.setOnClickListener {
            alertThermal.visibility = View.GONE
            alertThermal.clearAnimation()
            
            alertLow.visibility = View.GONE
            alertLow.clearAnimation()
            
            alertStorage.visibility = View.GONE
            alertStorage.clearAnimation()
            
            alertObstacle.visibility = View.GONE
            alertObstacle.clearAnimation()
            
            alertBlock.visibility = View.GONE
            alertBlock.clearAnimation()
            
            alertLoss.visibility = View.GONE
            alertLoss.clearAnimation()
            
            // Clear radar test
            obstacleRadar.updateDistances(100.0, 100.0, 100.0, 100.0)
            
            showToast("Alerts Dismissed")
            log("Alerts Dismissed by User")
        }
        
        // Setup UI Hide/Show Toggle
        val btnToggleUI = findViewById<TextView>(R.id.btnToggleUI)
        val leftFlightControls = findViewById<View>(R.id.leftFlightControls)
        val rightActionControls = findViewById<View>(R.id.rightActionControls)
        
        hudToggleHideRunnable = Runnable {
            btnToggleUI.animate().alpha(0f).setDuration(500).start()
        }
        hudToggleHideHandler.postDelayed(hudToggleHideRunnable!!, 5000)

        btnToggleUI.setOnClickListener {
            btnToggleUI.animate().alpha(1f).setDuration(200).start()
            hudToggleHideRunnable?.let {
                hudToggleHideHandler.removeCallbacks(it)
                hudToggleHideHandler.postDelayed(it, 5000)
            }
            
            if (leftFlightControls.visibility == View.VISIBLE) {
                leftFlightControls.visibility = View.GONE
                rightActionControls.visibility = View.GONE
                btnToggleUI.setTextColor(android.graphics.Color.RED)
            } else {
                leftFlightControls.visibility = View.VISIBLE
                rightActionControls.visibility = View.VISIBLE
                btnToggleUI.setTextColor(android.graphics.Color.GREEN)
            }
        }
        
        val modeSwitcher = findViewById<View>(R.id.modeSwitcher)
        val btnModeCam = findViewById<TextView>(R.id.btnModeCam)
        val btnModeFly = findViewById<TextView>(R.id.btnModeFly)
        val btnModeMap = findViewById<TextView>(R.id.btnModeMap)
        val btnModeShape = findViewById<TextView>(R.id.btnModeShape)
        val btnModeWaypoint = findViewById<TextView>(R.id.btnModeWaypoint)
        val btnGenerateGrid = findViewById<TextView>(R.id.btnGenerateGrid)
        val btnSyncWebOdm = findViewById<TextView>(R.id.btnSyncWebOdm)
        val btnStartKmz = findViewById<TextView>(R.id.btnStartKmz)
        val btnExecuteMission = findViewById<TextView>(R.id.btnExecuteMission)
        val tvControlStatus = findViewById<TextView>(R.id.tvControlStatus)
        
        val execListener = View.OnClickListener {
            executeTacticalMission()
        }
        btnExecuteMission.setOnClickListener(execListener)
        
        findViewById<View>(R.id.btnCenterMap).setOnClickListener {
            if (!droneLat.isNaN() && !droneLon.isNaN()) {
                mapView.controller.animateTo(GeoPoint(droneLat, droneLon))
                mapView.controller.setZoom(18.0)
            } else {
                showToast("Drone location unknown")
            }
        }
        
        fun showAltitudeSpeedDialog() {
            val dialogView = layoutInflater.inflate(R.layout.dialog_waypoint, null)
            val dialog = android.app.AlertDialog.Builder(this).setView(dialogView).create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            
            val tvTitle = dialogView.findViewById<TextView>(R.id.tvWpTitle)
            val etAlt = dialogView.findViewById<android.widget.EditText>(R.id.etWpAltitude)
            val etSpeed = dialogView.findViewById<android.widget.EditText>(R.id.etWpSpeed)
            val btnSave = dialogView.findViewById<TextView>(R.id.btnWpSave)
            dialogView.findViewById<View>(R.id.btnWpDelete).visibility = View.GONE
            
            tvTitle.text = "> MISSION_PARAMS"
            etAlt.setText(missionAltitude.toString())
            etSpeed.setText(missionSpeed.toString())
            
            btnSave.setOnClickListener {
                missionAltitude = etAlt.text.toString().toDoubleOrNull() ?: 50.0
                missionSpeed = etSpeed.text.toString().toDoubleOrNull() ?: 3.0
                showToast("Params Saved: ${missionAltitude}m @ ${missionSpeed}m/s")
                dialog.dismiss()
            }
            dialog.show()
        }

        var droneClickCount = 0
        val droneHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        droneMarker?.setOnMarkerClickListener { _, _ ->
            droneClickCount++
            if (droneClickCount == 1) {
                droneHandler.postDelayed({
                    if (droneClickCount == 1) {
                        // Single Click: Toggle Edit Mode (Select Unit)
                        isMapEditMode = !isMapEditMode
                        isSettingPOI = false
                        currentMapInteraction = MapInteractionType.WAYPOINT
                        showToast(if (isMapEditMode) "UNIT SELECTED: COMMAND MODE ON" else "UNIT DESELECTED: COMMAND MODE OFF")
                    } else if (droneClickCount >= 2) {
                        // Double Click: Open System/Tactical Menu
                        showSystemDialog()
                    }
                    droneClickCount = 0 // L-05: always reset regardless of branch
                }, 300)
            }
            true
        }
        
        val btnTakeoff = findViewById<View>(R.id.btnTakeoff)
        val btnManualStart = findViewById<View>(R.id.btnManualStart)
        val btnLand = findViewById<View>(R.id.btnLand)
        val btnRth = findViewById<View>(R.id.btnRth)
        val btnPau = findViewById<View>(R.id.btnPau)
        val btnSetHome = findViewById<View>(R.id.btnSetHome)
        val btnCapture = findViewById<View>(R.id.btnCapture)
        val btnRecord = findViewById<View>(R.id.btnRecord)
        val btnCenterGimbal = findViewById<View>(R.id.btnCenterGimbal)
        val btnToggleJoysticks = findViewById<View>(R.id.btnToggleJoysticks)
        
        fun updateModeUI() {
            btnModeCam.setBackgroundResource(if (currentControlMode == ControlMode.CAM) R.drawable.bg_glass_btn_active else R.drawable.bg_glass_btn)
            btnModeFly.setBackgroundResource(if (currentControlMode == ControlMode.FLY) R.drawable.bg_glass_btn_active else R.drawable.bg_glass_btn)
            btnModeMap.setBackgroundResource(if (currentControlMode == ControlMode.MAP && currentMapInteraction == MapInteractionType.MOVE) R.drawable.bg_glass_btn_active else R.drawable.bg_glass_btn)
            btnModeShape.setBackgroundResource(if (currentControlMode == ControlMode.MAP && currentMapInteraction == MapInteractionType.SHAPE) R.drawable.bg_glass_btn_active else R.drawable.bg_glass_btn)
            btnModeWaypoint.setBackgroundResource(if (currentControlMode == ControlMode.MAP && currentMapInteraction == MapInteractionType.WAYPOINT) R.drawable.bg_glass_btn_active else R.drawable.bg_glass_btn)
            val btnModePOI = findViewById<TextView>(R.id.btnModePOI)
            btnModePOI.setBackgroundResource(if (currentControlMode == ControlMode.MAP && currentMapInteraction == MapInteractionType.POI) R.drawable.bg_glass_btn_active else R.drawable.bg_glass_btn)
            
            if (currentControlMode == ControlMode.MAP) {
                tvControlStatus.visibility = View.VISIBLE
                tvControlStatus.text = "MAP"
                startBlinkingAnimation(tvControlStatus)
                btnToggleUI.visibility = View.GONE
                
                findViewById<View>(R.id.reticle).visibility = View.GONE
                findViewById<View>(R.id.tvGimbalStatus).visibility = View.GONE
                findViewById<View>(R.id.btnFlightMode).visibility = View.GONE
                findViewById<View>(R.id.compassView).visibility = View.GONE
                
                btnTakeoff.visibility = View.VISIBLE
                btnManualStart.visibility = View.GONE
                btnLand.visibility = View.GONE
                btnRth.visibility = View.VISIBLE
                btnPau.visibility = View.GONE
                btnSetHome.visibility = View.VISIBLE
                btnToggleJoysticks.visibility = View.GONE
                btnCapture.visibility = View.GONE
                btnRecord.visibility = View.GONE
                btnCenterGimbal.visibility = View.GONE
                findViewById<View>(R.id.btnCenterMap).visibility = View.VISIBLE
                findViewById<View>(R.id.btnCancelMission).visibility = View.VISIBLE
                findViewById<View>(R.id.btnDeleteWaypoint).visibility = View.VISIBLE
                findViewById<View>(R.id.btnOrbit).visibility = View.VISIBLE
                btnModeWaypoint.visibility = View.VISIBLE
                btnModePOI.visibility = View.VISIBLE
                btnModeShape.visibility = View.VISIBLE
                btnGenerateGrid.visibility = if (currentMapInteraction == MapInteractionType.SHAPE && shapePoints.size >= 3) View.VISIBLE else View.GONE
                btnSyncWebOdm.visibility = View.VISIBLE
                btnStartKmz.visibility = View.VISIBLE
                
                // Show full map, shrink FPV to PiP
                mapView.visibility = View.VISIBLE
                mapView.z = 0f
                val mapParams = mapView.layoutParams as ConstraintLayout.LayoutParams
                mapParams.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                mapParams.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                mapParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                mapParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                mapParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                mapParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                mapParams.setMargins(0, 0, 0, 0)
                mapView.layoutParams = mapParams
                
                fpvSurface.setZOrderMediaOverlay(true)
                val params = fpvSurface.layoutParams as ConstraintLayout.LayoutParams
                params.width = (250 * resources.displayMetrics.density).toInt()
                params.height = (140 * resources.displayMetrics.density).toInt()
                params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                params.endToEnd = ConstraintLayout.LayoutParams.UNSET
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                params.setMargins(16, 16, 0, 0)
                fpvSurface.layoutParams = params
                
            } else {
                if (isMissionExecuting) {
                    tvControlStatus.visibility = View.VISIBLE
                    tvControlStatus.text = "MAP"
                    startBlinkingAnimation(tvControlStatus)
                } else {
                    tvControlStatus.visibility = View.GONE
                    tvControlStatus.clearAnimation()
                }
                btnToggleUI.visibility = View.VISIBLE
                findViewById<View>(R.id.btnFlightMode).visibility = View.VISIBLE
                
                findViewById<View>(R.id.reticle).visibility = View.VISIBLE
                findViewById<View>(R.id.tvGimbalStatus).visibility = View.VISIBLE
                findViewById<View>(R.id.btnCenterMap).visibility = View.GONE
                findViewById<View>(R.id.btnCancelMission).visibility = View.GONE
                findViewById<View>(R.id.btnDeleteWaypoint).visibility = View.GONE
                findViewById<View>(R.id.btnOrbit).visibility = View.GONE
                findViewById<View>(R.id.compassView).visibility = View.VISIBLE
                
                if (currentControlMode == ControlMode.CAM) {
                    btnTakeoff.visibility = View.GONE
                    btnManualStart.visibility = View.GONE
                    btnLand.visibility = View.GONE
                    btnRth.visibility = View.GONE
                    btnPau.visibility = View.GONE
                    btnSetHome.visibility = View.GONE
                    btnToggleJoysticks.visibility = View.VISIBLE // Allow toggle in CAM mode
                    
                    btnCapture.visibility = View.VISIBLE
                    btnRecord.visibility = View.VISIBLE
                    btnCenterGimbal.visibility = View.VISIBLE
                    
                    findViewById<View>(R.id.btnCancelMission).visibility = if (isMissionExecuting) View.VISIBLE else View.GONE
                } else if (currentControlMode == ControlMode.FLY) {
                    btnTakeoff.visibility = View.VISIBLE
                    btnManualStart.visibility = View.VISIBLE
                    btnLand.visibility = View.VISIBLE
                    btnRth.visibility = View.VISIBLE
                    btnPau.visibility = View.VISIBLE
                    btnSetHome.visibility = View.VISIBLE
                    btnToggleJoysticks.visibility = View.VISIBLE
                    
                    btnCapture.visibility = View.VISIBLE
                    btnRecord.visibility = View.VISIBLE
                    btnCenterGimbal.visibility = View.VISIBLE
                    
                    // Reset to BODY coordinate for manual joystick flight
                    if (!isMissionExecuting) {
                        val param = dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam()
                        param.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                        dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
                    }
                    
                    findViewById<View>(R.id.btnCancelMission).visibility = if (isMissionExecuting) View.VISIBLE else View.GONE
                }
                
                updateJoysticksUI()
                
                // Always show Mini-map in CAM and FLY modes
                mapView.visibility = View.VISIBLE
                val mapParams = mapView.layoutParams as ConstraintLayout.LayoutParams
                mapParams.width = (200 * resources.displayMetrics.density).toInt()
                mapParams.height = (140 * resources.displayMetrics.density).toInt()
                mapParams.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                mapParams.endToEnd = ConstraintLayout.LayoutParams.UNSET
                mapParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                mapParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                mapParams.setMargins(16, 16, 0, 0)
                mapView.layoutParams = mapParams
                
                // Keep map on top of FPV
                mapView.z = 10f
                
                // Restore FPV to fullscreen
                fpvSurface.setZOrderMediaOverlay(false)
                val params = fpvSurface.layoutParams as ConstraintLayout.LayoutParams
                params.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                params.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                params.setMargins(0, 0, 0, 0)
                fpvSurface.layoutParams = params
            }
        }
        
        btnModeCam.setOnClickListener { currentControlMode = ControlMode.CAM; updateModeUI() }
        btnModeFly.setOnClickListener { currentControlMode = ControlMode.FLY; updateModeUI() }
        btnModeMap.setOnClickListener { currentControlMode = ControlMode.MAP; currentMapInteraction = MapInteractionType.MOVE; updateModeUI() }
        
        btnModeWaypoint.setOnClickListener {
            currentMapInteraction = MapInteractionType.WAYPOINT
            isMapEditMode = true
            currentControlMode = ControlMode.MAP
            
            // Hide grid settings side panel
            findViewById<android.view.View>(R.id.layoutMappingSettings).visibility = android.view.View.GONE
            
            updateModeUI()
            showToast("TAP MAP TO ADD WAYPOINTS")
        }
        
        val btnRouteManager = findViewById<View>(R.id.btnRouteManager)
        btnRouteManager?.setOnClickListener {
            showRouteManagerDialog()
        }
        
        val btnModePOI = findViewById<TextView>(R.id.btnModePOI)
        btnModePOI.setOnClickListener {
            currentMapInteraction = MapInteractionType.POI
            isSettingPOI = false
            isMapEditMode = true
            currentControlMode = ControlMode.MAP
            
            findViewById<android.view.View>(R.id.layoutMappingSettings).visibility = android.view.View.GONE
            
            updateModeUI()
            showToast("TAP MAP TO PLACE POI FLAG")
        }
        
        btnModeShape.setOnClickListener {
            currentMapInteraction = MapInteractionType.SHAPE
            isMapEditMode = true
            currentControlMode = ControlMode.MAP
            
            // Show side panel
            findViewById<android.view.View>(R.id.layoutMappingSettings).visibility = android.view.View.VISIBLE
            
            updateModeUI()
            showToast("TAP MAP TO DRAW POLYGON VERTICES")
        }
        
        // Setup Mapping Settings Panel
        val spnMapPattern = findViewById<android.widget.Spinner>(R.id.spnMapPattern)
        val patterns = arrayOf("Grid (Single)", "Crosshatch (Double)")
        spnMapPattern.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, patterns)
        
        val spnMapMode = findViewById<android.widget.Spinner>(R.id.spnMapMode)
        val modes = arrayOf("Professional (Native)", "Quick (Screenshots)")
        spnMapMode.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        spnMapMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                activeMappingMode = if (position == 0) MappingMode.PROFESSIONAL else MappingMode.QUICK
                showToast("Mapping Mode: $activeMappingMode")
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // M-09: debounce grid preview to avoid expensive recalculation on every keystroke
        val mapWatcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                gridPreviewRunnable?.let { gridPreviewHandler.removeCallbacks(it) }
                val r = Runnable { previewGridMission() }
                gridPreviewRunnable = r
                gridPreviewHandler.postDelayed(r, 300)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        findViewById<android.widget.EditText>(R.id.etMapAlt).addTextChangedListener(mapWatcher)
        findViewById<android.widget.EditText>(R.id.etMapSpeed).addTextChangedListener(mapWatcher)
        findViewById<android.widget.EditText>(R.id.etMapOverlap).addTextChangedListener(mapWatcher)
        findViewById<android.widget.EditText>(R.id.etMapPitch).addTextChangedListener(mapWatcher)
        
        spnMapPattern.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                previewGridMission()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        btnGenerateGrid.setOnClickListener {
            commitGridMission()
        }
        
        findViewById<android.widget.Button>(R.id.btnShowSavedKmz).setOnClickListener {
            val dir = getExternalFilesDir(null)
            val kmzFiles = dir?.listFiles { file -> file.name.endsWith(".kmz") }
            if (kmzFiles.isNullOrEmpty()) {
                showToast("No downloaded missions found.")
                return@setOnClickListener
            }
            
            val fileNames = kmzFiles.map { it.name }.toTypedArray()
            
            android.app.AlertDialog.Builder(this)
                .setTitle("Select Mission")
                .setItems(fileNames) { dialog, which ->
                    val selectedFile = kmzFiles[which]
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Action for ${selectedFile.name}")
                        .setPositiveButton("EXECUTE") { _, _ ->
                            showToast("Loading ${selectedFile.name}...")
                            executeNativeKMZ(selectedFile.absolutePath)
                        }
                        .setNegativeButton("DELETE") { _, _ ->
                            if (selectedFile.delete()) {
                                showToast("Deleted ${selectedFile.name}")
                            } else {
                                showToast("Failed to delete")
                            }
                        }
                        .setNeutralButton("CANCEL", null)
                        .show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        
        findViewById<android.widget.Button>(R.id.btnImportKmzLocal).setOnClickListener {
            pickKmzLauncher.launch("*/*")
        }
        
        findViewById<android.widget.Button>(R.id.btnClearKmz).setOnClickListener {
            runOnUiThread {
                // Isolated KMZ Clear: remove imported KMZ polylines and KMZ markers only
                val kmzOverlays = mapView.overlays.filter { overlay ->
                    (overlay is org.osmdroid.views.overlay.Polyline && overlay != flightPathPolyline && overlay != previewGridPolyline) ||
                    (overlay is org.osmdroid.views.overlay.Marker && (overlay.title?.contains("KMZ", ignoreCase = true) == true || overlay.title?.contains("Wayline", ignoreCase = true) == true))
                }
                mapView.overlays.removeAll(kmzOverlays)
                mapView.invalidate()
                showToast("KMZ Route Cleared (User Waypoints Preserved)")
            }
        }
        
        findViewById<android.widget.Button>(R.id.btnImportKmz).setOnClickListener {
            val url = findViewById<android.widget.EditText>(R.id.etKmzUrl).text.toString()
            if (url.isEmpty()) {
                showToast("Please enter a valid URL")
                return@setOnClickListener
            }
            
            showToast("Downloading KMZ...")
            Thread {
                try {
                    val client = okhttp3.OkHttpClient()
                    val request = okhttp3.Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val file = java.io.File(getExternalFilesDir(null), "imported_mission.kmz")
                        java.io.FileOutputStream(file).use { output ->
                            response.body?.byteStream()?.copyTo(output)
                        }
                        
                        runOnUiThread {
                            showToast("Download complete. Pushing to Drone...")
                            executeNativeKMZ(file.absolutePath)
                        }
                    } else {
                        runOnUiThread { showToast("Download failed: ${response.code}") }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread { showToast("Error downloading KMZ: ${e.message}") }
                }
            }.start()
        }
        
        btnSyncWebOdm.setOnClickListener {
            showToast("Initiating WebODM Sync...")
            WebODMAutoUpload.startSyncProcess(this, activeMappingMode == MappingMode.QUICK)
        }
        
        btnSyncWebOdm.setOnLongClickListener {
            showWebOdmConfigDialog()
            true
        }
        
        btnStartKmz.setOnClickListener {
            val wpm = dji.v5.manager.aircraft.waypoint3.WaypointMissionManager.getInstance()
            val fileName = lastLoadedKmzFileName
            val filePath = lastLoadedKmzFilePath
            if (fileName == null || filePath == null) {
                showToast("No KMZ loaded to start.")
            } else {
                val waylineIDs = wpm.getAvailableWaylineIDs(filePath)
                if (waylineIDs.isNullOrEmpty()) {
                    showToast("No waylines found in KMZ. Invalid WPML format.")
                } else {
                    val missionId = if (fileName.endsWith(".kmz", ignoreCase = true)) {
                        fileName.substring(0, fileName.length - 4)
                    } else fileName

                    startKmzWithAutoTakeoff(wpm, missionId, waylineIDs, "UI")
                }
            }
        }
        
        
        val btnFlightMode = findViewById<TextView>(R.id.btnFlightMode)
        flightModeHideRunnable = Runnable {
            btnFlightMode.animate().alpha(0f).setDuration(500).start()
        }
        flightModeHideHandler.postDelayed(flightModeHideRunnable!!, 5000)

        // Camera controls
        findViewById<TextView>(R.id.btnCapture).setOnClickListener { capturePhoto() }
        findViewById<TextView>(R.id.btnRecord).setOnClickListener { toggleRecord() }

        // Flight controls
        findViewById<TextView>(R.id.btnTakeoff).setOnClickListener { executeTakeoff() }
        findViewById<TextView>(R.id.btnManualStart).setOnClickListener { executeManualStart() }
        findViewById<TextView>(R.id.btnRth).setOnClickListener { executeReturnToHome() }
        findViewById<TextView>(R.id.btnLand).setOnClickListener { executeLanding() }
        
        findViewById<TextView>(R.id.btnCancelMission).setOnClickListener { 
            cancelActiveMission()
            showToast("Mission Cancelled")
            updateModeUI()
        }
        
        btnSetHome.setOnClickListener {
            val setHomeKey = KeyTools.createKey(FlightControllerKey.KeyHomeLocationUsingCurrentAircraftLocation)
            KeyManager.getInstance().performAction(setHomeKey, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    showToast("Home Point Set to Current Location")
                    log("Home Point Updated")
                }
                override fun onFailure(error: IDJIError) {
                    showToast("Failed to set Home Point")
                    log("Set Home Error: ${error.errorCode()}")
                }
            })
        }
        
        btnPau.setOnClickListener {
            // Pause VirtualStick Mission
            if (isMissionExecuting) {
                isMissionExecuting = false
                showToast("Tactical Mission PAUSED")
                // Clear any active movement
                // sendVirtualStickData(0.0, 0.0, 0.0, 0.0)
            }
            
            val pauseKey = KeyTools.createKey(FlightControllerKey.KeyStopGoHome)
            KeyManager.getInstance().performAction(pauseKey, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    showToast("RTH Paused")
                    log("RTH Paused")
                }
                override fun onFailure(error: IDJIError) {
                    showToast("Failed to Pause RTH")
                    log("Pause RTH Error: ${error.errorCode()}")
                }
            })
        }
        
        findViewById<TextView>(R.id.btnCenterGimbal).setOnClickListener { centerGimbal() }
        findViewById<TextView>(R.id.btnResetZoom)?.setOnClickListener { resetZoom() }
        findViewById<TextView>(R.id.btnDeleteSelection)?.setOnClickListener {
            objectTrackingOverlay?.unlockTarget()
            showToast("LOCK: SELECTION CLEARED (DEL)")
        }
        findViewById<TextView>(R.id.btnFollowObject)?.setOnClickListener {
            if (isObjectTrackingActive) {
                showToast("LOCK: OBJECT FOLLOW ACTIVE (FOL)")
            } else {
                objectTrackingOverlay?.lockTargetNormalized(0.5f, 0.5f, 0.2f, 0.2f)
                showToast("LOCK: OBJECT FOLLOW ACTIVATED (FOL)")
            }
        }
        findViewById<TextView>(R.id.btnTgpLock)?.setOnClickListener {
            toggleTargetingPodLock()
        }
        findViewById<TextView>(R.id.btnTagLocation)?.setOnClickListener {
            val tLat = if (isTgpGeoLockActive) tgpTargetLat else droneLat
            val tLon = if (isTgpGeoLockActive) tgpTargetLon else droneLon
            val tAlt = if (isTgpGeoLockActive) tgpTargetAlt else droneAlt

            if (tLat == 0.0 && tLon == 0.0) {
                showToast("⚠️ Cannot tag: Waiting for GPS fix...")
            } else {
                val item = GpsTaggingManager.addTag(
                    this,
                    if (isTgpGeoLockActive) "TGP_TARGET" else "DRONE_POS",
                    tLat,
                    tLon,
                    tAlt,
                    if (isTgpGeoLockActive) "TGP_GEO_LOCK" else "DRONE_GPS"
                )
                updateGpsTagsOnMap()
                showToast("📍 TAGGED LOCATION [${item.id}]: ${String.format("%.5f", tLat)}, ${String.format("%.5f", tLon)}")
            }
        }
        val btnToggleObjectTouch = findViewById<TextView>(R.id.btnToggleObjectTouch)
        btnToggleObjectTouch?.setOnClickListener {
            val newState = !(objectTrackingOverlay?.isTouchSelectionEnabled ?: false)
            objectTrackingOverlay?.isTouchSelectionEnabled = newState
            btnToggleObjectTouch.setTextColor(if (newState) android.graphics.Color.parseColor("#00FFFF") else android.graphics.Color.parseColor("#AAAAAA"))
            showToast(if (newState) "🎯 Touch Object Selection: ENABLED (Tap/Drag Feed)" else "✋ Touch Object Selection: DISABLED (Gestures Active)")
        }
        findViewById<TextView>(R.id.btnToggleJoysticks).setOnClickListener { toggleJoysticks() }
        findViewById<TextView>(R.id.btnSystem).setOnClickListener { showSystemDialog() }

        // Joysticks & Gimbal setup
        setupJoysticks()
        setupGimbalInteraction()

        log("App started. SDK should already be initialized.")
        // Setup features immediately assuming HomeActivity registered SDK
        setupVideoFeed()
        configureCameraStorage()
        monitorCameraStorage()
        setupObstacleRadar()
        monitorTelemetry()
        monitorConnectionStatus()
        
        // Initialize UI State
        
        WebODMAutoUpload.statusListener = { statusMsg, isError ->
            if (::mqttService.isInitialized) {
                val logObj = org.json.JSONObject()
                logObj.put("event", "WEBODM_SYNC_STATUS")
                logObj.put("status", statusMsg)
                logObj.put("isError", isError)
                mqttService.publishMission(jsonPayload = logObj.toString())
            }
        }
        
        // Listen to Camera Shooting Photo state to log photo captures
        try {
            val isShootingPhotoKey = KeyTools.createCameraKey(CameraKey.KeyIsShootingPhoto, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_DEFAULT)
            KeyManager.getInstance().listen(isShootingPhotoKey, this) { oldValue, newValue ->
                if (newValue == true && ::mqttService.isInitialized) {
                    val logObj = org.json.JSONObject()
                    logObj.put("event", "PHOTO_CAPTURED")
                    logObj.put("isMissionExecuting", isMissionExecuting)
                    mqttService.publishMission(jsonPayload = logObj.toString())
                }
            }
        } catch (e: Exception) {
            log("Error setting up photo listener: ${e.message}")
        }
        
        updateModeUI()
    }

    private fun addWaypointMarker(p: GeoPoint) {
        val wp = TacticalWaypoint(p)
        wp.name = "waypoint_${tacticalWaypoints.size + 1}"
        tacticalWaypoints.add(wp)
        // Update Map Marker
        val marker = org.osmdroid.views.overlay.Marker(mapView)
        marker.position = p
        marker.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_waypoint_dot)
        marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
        marker.title = wp.name
        marker.setOnMarkerClickListener { m, _ ->
            showWaypointActionDialog(wp, m as org.osmdroid.views.overlay.Marker)
            true
        }
        wp.osmdroidMarker = marker
        mapView.overlays.add(marker)
        updateFlightPathLine()
        mapView.invalidate()
        publishWaypointsUpdate()
        
        // Show action dialog immediately upon placing the waypoint
        showWaypointActionDialog(wp, marker)
    }

    private fun showWaypointActionDialog(wp: TacticalWaypoint, marker: org.osmdroid.views.overlay.Marker) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_waypoint, null)
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvWpTitle)
        val etName = dialogView.findViewById<android.widget.EditText>(R.id.etWpName)
        val etAlt = dialogView.findViewById<android.widget.EditText>(R.id.etWpAltitude)
        val etSpeed = dialogView.findViewById<android.widget.EditText>(R.id.etWpSpeed)
        val spMovementMethod = dialogView.findViewById<android.widget.Spinner>(R.id.spWpMovementMethod)
        val llOrbitParams = dialogView.findViewById<android.widget.LinearLayout>(R.id.llOrbitParams)
        val etOrbitRadius = dialogView.findViewById<android.widget.EditText>(R.id.etWpOrbitRadius)
        val etOrbitLoops = dialogView.findViewById<android.widget.EditText>(R.id.etWpOrbitLoops)
        val spAction = dialogView.findViewById<android.widget.Spinner>(R.id.spWpAction)
        val btnDelete = dialogView.findViewById<TextView>(R.id.btnWpDelete)
        val btnSave = dialogView.findViewById<TextView>(R.id.btnWpSave)
        
        val index = tacticalWaypoints.indexOf(wp) + 1
        tvTitle.text = "> WP $index CONFIG"
        
        etName?.setText(wp.name)
        etAlt.setText(wp.altitude.toString())
        etSpeed.setText(wp.speed.toString())
        etOrbitRadius.setText(wp.orbitRadius.toString())
        etOrbitLoops.setText(wp.orbitLoops.toString())
        
        val movementMethods = arrayOf("LINEAR", "SPLINE", "ORBIT")
        val methodAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, movementMethods)
        methodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spMovementMethod.adapter = methodAdapter
        
        val currentMethod = wp.movementMethod.uppercase()
        val selectedMethodIndex = movementMethods.indexOf(currentMethod).coerceAtLeast(0)
        spMovementMethod.setSelection(selectedMethodIndex)
        
        val actions = arrayOf("FLY", "LOCK_POI", "UNLOCK_POI", "PHOTO", "START_RECORD", "STOP_RECORD")
        val actionAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, actions)
        actionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spAction.adapter = actionAdapter
        
        val currentAction = wp.actionType.uppercase()
        val selectedActionIndex = actions.indexOf(currentAction).coerceAtLeast(0)
        spAction.setSelection(selectedActionIndex)
        
        // Show/hide orbit params AND draw a live preview circle on the map
        var liveOrbitPreview: org.osmdroid.views.overlay.Polyline? = null
        spMovementMethod.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isOrbit = movementMethods[position] == "ORBIT"
                llOrbitParams.visibility = if (isOrbit) View.VISIBLE else View.GONE
                // Live preview: show orbit circle while dialog is open
                liveOrbitPreview?.let { mapView.overlays.remove(it) }
                liveOrbitPreview = null
                if (isOrbit) {
                    val previewRadius = etOrbitRadius.text.toString().toDoubleOrNull() ?: wp.orbitRadius
                    liveOrbitPreview = buildOrbitCircleOverlay(wp.geoPoint, previewRadius)
                    mapView.overlays.add(liveOrbitPreview)
                    mapView.invalidate()
                } else {
                    mapView.invalidate()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        btnDelete.setOnClickListener {
            tacticalWaypoints.remove(wp)
            mapView.overlays.remove(marker)
            tacticalWaypoints.forEachIndexed { i, twp ->
                val methodSuffix = if (twp.movementMethod != "linear") " (${twp.movementMethod.uppercase()})" else ""
                val actionSuffix = if (twp.actionType != "FLY") " [${twp.actionType}]" else ""
                if (twp.name.startsWith("waypoint_")) {
                    twp.name = "waypoint_${i + 1}"
                }
                twp.osmdroidMarker?.title = "${twp.name}$methodSuffix$actionSuffix"
            }
            showToast("Waypoint $index deleted")
            updateFlightPathLine()
            mapView.invalidate()
            publishWaypointsUpdate()
            dialog.dismiss()
        }
        
        // Remove live preview when dialog is dismissed
        dialog.setOnDismissListener {
            liveOrbitPreview?.let { mapView.overlays.remove(it) }
            liveOrbitPreview = null
            mapView.invalidate()
        }

        btnSave.setOnClickListener {
            val alt = etAlt.text.toString().toDoubleOrNull() ?: wp.altitude
            val spd = etSpeed.text.toString().toDoubleOrNull() ?: wp.speed
            val method = spMovementMethod.selectedItem.toString().lowercase()
            val action = spAction.selectedItem.toString()
            val newName = etName?.text?.toString()?.trim() ?: wp.name
            
            if (newName.isNotEmpty()) {
                wp.name = newName
            }
            wp.altitude = alt
            wp.speed = spd
            wp.movementMethod = method
            wp.actionType = action
            
            if (method == "orbit") {
                wp.orbitRadius = etOrbitRadius.text.toString().toDoubleOrNull() ?: wp.orbitRadius
                wp.orbitLoops = etOrbitLoops.text.toString().toIntOrNull() ?: wp.orbitLoops
            }
            
            if (action == "LOCK_POI" || action == "PHOTO" || action == "START_RECORD") {
                if (wp.poiTarget == null) {
                    selectedWaypointForPOI = wp
                    isSettingPOI = true
                    currentMapInteraction = MapInteractionType.POI
                    showToast("TAP MAP TO SELECT TARGET POI")
                }
            } else {
                wp.poiTarget = null
            }
            
            val methodSuffix = if (wp.movementMethod != "linear") " (${wp.movementMethod.uppercase()})" else ""
            val actionSuffix = if (wp.actionType != "FLY") " [${wp.actionType}]" else ""
            marker.title = "${wp.name}$methodSuffix$actionSuffix"
            
            showToast("Waypoint '${wp.name}' Config Saved")
            updateFlightPathLine()
            mapView.invalidate()
            publishWaypointsUpdate()
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun updateShapePolyline() {
        if (shapePolygon != null) return
        if (shapePolyline == null) {
            shapePolyline = org.osmdroid.views.overlay.Polyline(mapView)
            shapePolyline?.outlinePaint?.color = android.graphics.Color.YELLOW
            shapePolyline?.outlinePaint?.strokeWidth = 5f
            mapView.overlays.add(0, shapePolyline) // Add below markers
        }
        shapePolyline?.setPoints(shapePoints)
    }

    private fun addShapePoint(p: GeoPoint) {
        shapePoints.add(p)
        
        val vertexMarker = org.osmdroid.views.overlay.Marker(mapView)
        vertexMarker.position = p
        vertexMarker.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_waypoint_dot)
        vertexMarker.isDraggable = true
        vertexMarker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
        
        vertexMarker.setOnMarkerDragListener(object : org.osmdroid.views.overlay.Marker.OnMarkerDragListener {
            override fun onMarkerDragStart(marker: org.osmdroid.views.overlay.Marker) {}
            override fun onMarkerDrag(marker: org.osmdroid.views.overlay.Marker) {
                val index = shapeVertexMarkers.indexOf(marker)
                if (index != -1) {
                    shapePoints[index] = marker.position
                    shapePolygon?.points = shapePoints
                    updateShapePolyline()
                    previewGridMission()
                    mapView.invalidate()
                }
            }
            override fun onMarkerDragEnd(marker: org.osmdroid.views.overlay.Marker) {}
        })
        
        vertexMarker.setOnMarkerClickListener { m, _ ->
            val index = shapeVertexMarkers.indexOf(m as org.osmdroid.views.overlay.Marker)
            if (index != -1) {
                if (index == 0 && shapePoints.size >= 3 && shapePolygon == null) {
                    // Close the polygon
                    shapePolygon = org.osmdroid.views.overlay.Polygon(mapView)
                    shapePolygon?.fillPaint?.color = android.graphics.Color.argb(75, 255, 255, 0)
                    shapePolygon?.outlinePaint?.color = android.graphics.Color.YELLOW
                    shapePolygon?.outlinePaint?.strokeWidth = 3f
                    shapePolygon?.points = shapePoints
                    shapePolygon?.setOnClickListener { polygon, _, _ ->
                        showShapeDialog(polygon as org.osmdroid.views.overlay.Polygon)
                        true
                    }
                    mapView.overlays.add(0, shapePolygon)
                    
                    shapePolyline?.let { mapView.overlays.remove(it) }
                    shapePolyline = null
                    
                    findViewById<TextView>(R.id.btnGenerateGrid).visibility = View.VISIBLE
                    previewGridMission()
                    showToast("Polygon Closed!")
                } else {
                    shapePoints.removeAt(index)
                    shapeVertexMarkers.remove(m)
                    mapView.overlays.remove(m)
                    if (shapePolygon != null) {
                        if (shapePoints.size < 3) {
                            shapePolygon?.let { mapView.overlays.remove(it) }
                            shapePolygon = null
                            findViewById<TextView>(R.id.btnGenerateGrid).visibility = View.GONE
                        } else {
                            shapePolygon?.points = shapePoints
                        }
                    }
                    updateShapePolyline()
                    previewGridMission()
                }
                mapView.invalidate()
            }
            true
        }
        
        shapeVertexMarkers.add(vertexMarker)
        mapView.overlays.add(vertexMarker)

        updateShapePolyline()
        mapView.invalidate()
    }

    private fun showShapeDialog(polygon: org.osmdroid.views.overlay.Polygon) {
        val colors = arrayOf("Yellow (Caution)", "Red (No Fly)", "Green (Safe)")
        android.app.AlertDialog.Builder(this)
            .setTitle("Designate Area")
            .setItems(colors) { _, which ->
                when (which) {
                    0 -> {
                        polygon.fillPaint.color = android.graphics.Color.argb(75, 255, 255, 0)
                        polygon.outlinePaint.color = android.graphics.Color.YELLOW
                    }
                    1 -> {
                        polygon.fillPaint.color = android.graphics.Color.argb(75, 255, 0, 0)
                        polygon.outlinePaint.color = android.graphics.Color.RED
                    }
                    2 -> {
                        polygon.fillPaint.color = android.graphics.Color.argb(75, 0, 255, 0)
                        polygon.outlinePaint.color = android.graphics.Color.GREEN
                    }
                }
                mapView.invalidate()
            }
            .setPositiveButton("DELETE") { _, _ ->
                mapView.overlays.remove(polygon)
                shapeVertexMarkers.forEach { mapView.overlays.remove(it) }
                shapeVertexMarkers.clear()
                if (polygon == shapePolygon) {
                    shapePoints.clear()
                    shapePolygon = null
                }
                shapePolyline?.let { mapView.overlays.remove(it) }
                shapePolyline = null
                findViewById<TextView>(R.id.btnGenerateGrid).visibility = View.GONE
                mapView.invalidate()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun showRouteManagerDialog() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_route_manager)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val layoutRoutesContainer = dialog.findViewById<android.widget.LinearLayout>(R.id.layoutRoutesContainer)
        val btnCreateNewRoute = dialog.findViewById<Button>(R.id.btnCreateNewRoute)
        val btnChainExecuteRoutes = dialog.findViewById<Button>(R.id.btnChainExecuteRoutes)
        val btnMasterClearMap = dialog.findViewById<Button>(R.id.btnMasterClearMap)
        val btnCloseRouteManager = dialog.findViewById<TextView>(R.id.btnCloseRouteManager)

        fun refreshRouteList() {
            layoutRoutesContainer?.removeAllViews()
            val allRoutes = com.dji.recreate2.sync.WaypointRouteManager.getAllRoutes()
            val activeId = com.dji.recreate2.sync.WaypointRouteManager.activeRouteId

            for (route in allRoutes) {
                val row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(12, 10, 12, 10)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 8) }
                    background = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_telemetry_capsule)
                }

                // Checkbox for map visibility
                val cbVisible = android.widget.CheckBox(this).apply {
                    isChecked = route.isVisibleOnMap
                    setOnCheckedChangeListener { _, isChecked ->
                        com.dji.recreate2.sync.WaypointRouteManager.setRouteVisibility(route.id, isChecked)
                        updateFlightPathLine()
                        mapView.invalidate()
                    }
                }

                // Title & WP Count
                val tvTitle = TextView(this).apply {
                    text = "${route.name} (${route.waypoints.size} WPs)"
                    setTextColor(if (route.id == activeId) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.WHITE)
                    textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        com.dji.recreate2.sync.WaypointRouteManager.setActiveRoute(route.id)
                        showToast("Active Route: ${route.name}")
                        refreshRouteList()
                    }
                }

                // Delete button
                val btnDel = TextView(this).apply {
                    text = "🗑"
                    setTextColor(android.graphics.Color.parseColor("#FF3333"))
                    textSize = 14f
                    setPadding(12, 6, 12, 6)
                    setOnClickListener {
                        com.dji.recreate2.sync.WaypointRouteManager.deleteRoute(route.id)
                        updateFlightPathLine()
                        mapView.invalidate()
                        refreshRouteList()
                        showToast("Deleted ${route.name}")
                    }
                }

                row.addView(cbVisible)
                row.addView(tvTitle)
                row.addView(btnDel)
                layoutRoutesContainer?.addView(row)
            }
        }

        refreshRouteList()

        btnCreateNewRoute?.setOnClickListener {
            val newRoute = com.dji.recreate2.sync.WaypointRouteManager.createNewRoute()
            showToast("Created ${newRoute.name}")
            refreshRouteList()
        }

        btnChainExecuteRoutes?.setOnClickListener {
            val chainedWps = com.dji.recreate2.sync.WaypointRouteManager.getChainedExecutionWaypoints()
            if (chainedWps.isEmpty()) {
                showToast("No waypoints to execute.")
                return@setOnClickListener
            }
            dialog.dismiss()
            executeTacticalMission()
        }

        btnMasterClearMap?.setOnClickListener {
            dialog.dismiss()
            findViewById<TextView>(R.id.btnDeleteWaypoint)?.performClick()
        }

        btnCloseRouteManager?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showFetchMediaFolderSelectionDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundResource(R.drawable.bg_atak_panel)
        }

        val titleTv = TextView(this).apply {
            text = "MEDIA FETCH & CEPH S3 SYNC DESTINATION"
            setTextColor(android.graphics.Color.parseColor("#00FF66"))
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        layout.addView(titleTv)

        val localLabel = TextView(this).apply {
            text = "Local Device Download Folder:"
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            textSize = 11f
        }
        layout.addView(localLabel)

        val etLocal = EditText(this).apply {
            setText(com.dji.recreate2.sync.PostFlightS3Sync.getLocalFetchFolderName(this@MainActivity))
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_telemetry_capsule)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            hint = "e.g. ISR_Mode2_Sync or Custom_Folder"
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            textSize = 12f
        }
        layout.addView(etLocal)

        val space1 = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) }
        layout.addView(space1)

        val s3Label = TextView(this).apply {
            text = "Ceph S3 Target Subfolder:"
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            textSize = 11f
        }
        layout.addView(s3Label)

        val currentCustom = com.dji.recreate2.aws.S3UploadManager.getCustomFolderName(this@MainActivity)
        val etS3Folder = EditText(this).apply {
            setText(if (currentCustom.isNotEmpty()) currentCustom else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_telemetry_capsule)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            hint = "e.g. recon_alpha_01 or YYYY-MM-DD"
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            textSize = 12f
        }
        layout.addView(etS3Folder)

        val space2 = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(16)) }
        layout.addView(space2)

        val dialog = android.app.AlertDialog.Builder(this)
            .setView(layout)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
        }

        val btnCreateRemote = Button(this).apply {
            text = "📁 CREATE S3 FOLDER"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_btn)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener {
                val s3Folder = etS3Folder.text.toString().trim()
                if (s3Folder.isEmpty()) {
                    showToast("Please enter an S3 folder name")
                    return@setOnClickListener
                }
                showToast("Creating remote S3 folder ($s3Folder)...")
                com.dji.recreate2.aws.S3UploadManager.createRemoteFolder(this@MainActivity, s3Folder,
                    onSuccess = { showToast("Successfully created S3 folder: $s3Folder") },
                    onError = { err -> showToast("S3 folder error: $err") }
                )
            }
        }
        btnRow1.addView(btnCreateRemote)

        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val btnSyncS3 = Button(this).apply {
            text = "FETCH & SYNC S3"
            setTextColor(android.graphics.Color.parseColor("#00FF66"))
            setBackgroundResource(R.drawable.bg_btn_outline_green)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                val localFolder = etLocal.text.toString().trim()
                val s3Folder = etS3Folder.text.toString().trim()
                if (localFolder.isNotEmpty()) {
                    com.dji.recreate2.sync.PostFlightS3Sync.saveLocalFetchFolderName(this@MainActivity, localFolder)
                }
                if (s3Folder.isNotEmpty()) {
                    com.dji.recreate2.aws.S3UploadManager.saveFolderConfig(this@MainActivity, com.dji.recreate2.aws.S3UploadManager.FOLDER_MODE_CUSTOM, s3Folder)
                }
                dialog.dismiss()
                showToast("Fetching Media & Syncing to Ceph S3...")
                com.dji.recreate2.sync.PostFlightS3Sync.startSync(this@MainActivity)
            }
        }

        val btnLocalOnly = Button(this).apply {
            text = "LOCAL ONLY"
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.bg_glass_btn)
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener {
                val localFolder = etLocal.text.toString().trim()
                if (localFolder.isNotEmpty()) {
                    com.dji.recreate2.sync.PostFlightS3Sync.saveLocalFetchFolderName(this@MainActivity, localFolder)
                }
                dialog.dismiss()
                showToast("Fetching Media to Local Folder ($localFolder)...")
                com.dji.recreate2.sync.PostFlightS3Sync.startSync(this@MainActivity)
            }
        }

        btnRow2.addView(btnSyncS3)
        btnRow2.addView(btnLocalOnly)

        btnContainer.addView(btnRow1)
        btnContainer.addView(btnRow2)
        layout.addView(btnContainer)

        dialog.show()
    }

    private fun showWebOdmConfigDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_webodm_config, null)
        val dialog = android.app.AlertDialog.Builder(this).setView(dialogView).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        val etUrl = dialogView.findViewById<android.widget.EditText>(R.id.etServerUrl)
        val etUser = dialogView.findViewById<android.widget.EditText>(R.id.etUsername)
        val etPass = dialogView.findViewById<android.widget.EditText>(R.id.etPassword)
        val btnFetch = dialogView.findViewById<TextView>(R.id.btnFetchProjects)
        val spnProjects = dialogView.findViewById<android.widget.Spinner>(R.id.spnProjects)
        
        etUrl.setText(WebODMAutoUpload.getServerUrl(this))
        etUser.setText(WebODMAutoUpload.getUsername(this))
        etPass.setText(WebODMAutoUpload.getPassword(this))
        
        var fetchedProjects = mutableMapOf<String, Int>()
        var selectedProjectId = WebODMAutoUpload.getProjectId(this)
        
        btnFetch.setOnClickListener {
            val url = etUrl.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString().trim()
            
            if (url.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                showToast("Please fill URL, Username, and Password")
                return@setOnClickListener
            }
            
            btnFetch.text = "FETCHING..."
            Thread {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                        
                    // 1. Authenticate
                    val authBody = okhttp3.FormBody.Builder()
                        .add("username", user)
                        .add("password", pass)
                        .build()
                    val authReq = okhttp3.Request.Builder()
                        .url("$url/api/token-auth/")
                        .post(authBody)
                        .build()
                        
                    val authRes = client.newCall(authReq).execute()
                    val authResStr = authRes.body?.string() ?: ""
                    
                    if (!authRes.isSuccessful) {
                        runOnUiThread { 
                            btnFetch.text = "FETCH FAILED"
                            showToast("Auth failed: ${authRes.code}") 
                        }
                        return@Thread
                    }
                    
                    val tokenObj = org.json.JSONObject(authResStr)
                    val token = tokenObj.optString("token")
                    if (token.isEmpty()) {
                        runOnUiThread { btnFetch.text = "FETCH FAILED" }
                        return@Thread
                    }
                    
                    // 2. Fetch Projects
                    val projReq = okhttp3.Request.Builder()
                        .url("$url/api/projects/")
                        .header("Authorization", "JWT $token")
                        .build()
                        
                    val projRes = client.newCall(projReq).execute()
                    val projResStr = projRes.body?.string() ?: ""
                    
                    if (projRes.isSuccessful) {
                        fetchedProjects.clear()
                        
                        // WebODM returns a direct JSON Array, not a paginated object
                        val projArray = try {
                            org.json.JSONArray(projResStr)
                        } catch (e: Exception) {
                            // Fallback: some versions may return paginated {"results": [...]}
                            val obj = org.json.JSONObject(projResStr)
                            obj.optJSONArray("results")
                        }
                        
                        if (projArray != null) {
                            for (i in 0 until projArray.length()) {
                                val p = projArray.getJSONObject(i)
                                fetchedProjects[p.getString("name")] = p.getInt("id")
                            }
                        }
                        
                        runOnUiThread {
                            btnFetch.text = "FETCHED ${fetchedProjects.size} PROJECTS"
                            val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fetchedProjects.keys.toList())
                            spnProjects.adapter = adapter
                            
                            // Try to pre-select previously saved project
                            val keysList = fetchedProjects.keys.toList()
                            val idx = keysList.indexOfFirst { fetchedProjects[it] == selectedProjectId }
                            if (idx >= 0) spnProjects.setSelection(idx)
                        }
                    } else {
                        runOnUiThread { 
                            btnFetch.text = "FETCH FAILED"
                            showToast("Failed to fetch projects: ${projRes.code}") 
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { 
                        btnFetch.text = "FETCH FAILED"
                        showToast("Error: ${e.message}") 
                    }
                }
            }.start()
        }
        
        dialogView.findViewById<TextView>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<TextView>(R.id.btnSave).setOnClickListener {
            val url = etUrl.text.toString().trim()
            val user = etUser.text.toString().trim()
            val pass = etPass.text.toString().trim()
            
            val selectedName = spnProjects.selectedItem as? String
            val pId = if (selectedName != null && fetchedProjects.containsKey(selectedName)) {
                fetchedProjects[selectedName]!!
            } else {
                -1
            }
            
            if (url.isNotEmpty() && url.startsWith("http")) {
                WebODMAutoUpload.saveServerConfig(this, url, user, pass, pId)
                showToast("WebODM Configured! (Proj ID: $pId)")
                dialog.dismiss()
            } else {
                showToast("Invalid URL. Must start with http or https.")
            }
        }
        
        dialog.show()
    }

    private fun expandOrbitWaypoints(wps: List<TacticalWaypoint>): List<TacticalWaypoint> {
        val result = mutableListOf<TacticalWaypoint>()
        for (wp in wps) {
            if (wp.movementMethod.equals("orbit", ignoreCase = true)) { // C-03: was checking wrong field actionType
                val radius = wp.orbitRadius
                val loops = wp.orbitLoops
                val points = 12
                for (l in 0 until loops) {
                    for (i in 0 until points) {
                        val angle = (i * 360.0 / points) * Math.PI / 180.0
                        val lat = wp.geoPoint.latitude + (radius / 111320.0) * Math.cos(angle)
                        val lon = wp.geoPoint.longitude + (radius / (111320.0 * Math.cos(wp.geoPoint.latitude * Math.PI / 180.0))) * Math.sin(angle)
                        
                        val results = FloatArray(3)
                        android.location.Location.distanceBetween(lat, lon, wp.geoPoint.latitude, wp.geoPoint.longitude, results)
                        val bearing = ((results[1] % 360) + 360) % 360.0
                        
                        val circleWp = TacticalWaypoint(
                            org.osmdroid.util.GeoPoint(lat, lon),
                            altitude = wp.altitude,
                            speed = wp.speed,
                            actionType = "LOCK_POI",
                            poiTarget = wp.geoPoint,
                            movementMethod = "spline",
                            heading = bearing
                        )
                        result.add(circleWp)
                    }
                }
            } else {
                result.add(wp)
            }
        }
        return result
    }

    private fun checkMissionSafety(wps: List<TacticalWaypoint>): String? {
        if (wps.isEmpty()) return "No waypoints to execute."
        
        for (i in wps.indices) {
            val wp = wps[i]
            
            // 1. Altitude Check: Spline or Orbit waypoints must have safe altitude
            if (wp.movementMethod.equals("spline", ignoreCase = true) || wp.movementMethod.equals("orbit", ignoreCase = true)) {
                if (wp.altitude < 15.0) {
                    return "Waypoint ${i+1} has unsafe low altitude (${wp.altitude}m) for advanced trajectory modes. Must be >= 15m."
                }
            }
            if (wp.altitude < 5.0) {
                return "Waypoint ${i+1} altitude is too low (${wp.altitude}m). Must be >= 5m."
            }
            if (wp.altitude > 120.0) {
                return "Waypoint ${i+1} altitude exceeds maximum legal limit of 120m."
            }
            
            // 2. Speed Check
            if (wp.speed <= 0.5) {
                return "Waypoint ${i+1} speed too slow (${wp.speed} m/s). Must be >= 0.5 m/s."
            }
            if (wp.speed > 12.0) {
                return "Waypoint ${i+1} speed exceeds safe limit of 12.0 m/s."
            }
            
            // 3. Orbit Parameters validation
            if (wp.movementMethod.equals("orbit", ignoreCase = true)) {
                if (wp.orbitRadius < 5.0 || wp.orbitRadius > 150.0) {
                    return "Waypoint ${i+1} orbit radius (${wp.orbitRadius}m) is out of safe range (5m to 150m)."
                }
                if (wp.orbitLoops < 1 || wp.orbitLoops > 10) {
                    return "Waypoint ${i+1} orbit loops (${wp.orbitLoops}) must be between 1 and 10."
                }
            }
            
            // 4. No-Fly Zone (Red Polygon) Intersection Check
            mapView.overlays.forEach { overlay ->
                if (overlay is org.osmdroid.views.overlay.Polygon) {
                    val color = overlay.fillPaint.color
                    val isRed = color == android.graphics.Color.argb(75, 255, 0, 0)
                    if (isRed && isPointInPolygon(wp.geoPoint, overlay)) {
                        return "Waypoint ${i+1} coordinate falls inside a designated NO-FLY ZONE!"
                    }
                }
            }
        }
        
        // 5. Check transition collision hazards
        var prev = if (!droneLat.isNaN() && !droneLon.isNaN()) GeoPoint(droneLat, droneLon) else wps.first().geoPoint
        wps.forEachIndexed { idx, wp ->
            val nPoints = 10
            for (step in 1..nPoints) {
                val fraction = step.toDouble() / nPoints
                val sampleLat = prev.latitude + (wp.geoPoint.latitude - prev.latitude) * fraction
                val sampleLon = prev.longitude + (wp.geoPoint.longitude - prev.longitude) * fraction
                val samplePt = GeoPoint(sampleLat, sampleLon)
                
                mapView.overlays.forEach { overlay ->
                    if (overlay is org.osmdroid.views.overlay.Polygon) {
                        val color = overlay.fillPaint.color
                        val isRed = color == android.graphics.Color.argb(75, 255, 0, 0)
                        if (isRed && isPointInPolygon(samplePt, overlay)) {
                            return "Flight path segment between waypoint/drone and waypoint ${idx+1} intersects a designated NO-FLY ZONE!"
                        }
                    }
                }
            }
            prev = wp.geoPoint
        }
        
        return null
    }

    private fun isPointInPolygon(point: GeoPoint, polygon: org.osmdroid.views.overlay.Polygon): Boolean {
        // M-06 Fixed: High-precision Point-In-Polygon using native Spherical Mercator Math
        // This eliminates polar distortion and supports complex concave shapes safely.
        val points = polygon.actualPoints
        if (points.isEmpty()) return false
        
        fun latToY(lat: Double): Double = Math.log(Math.tan(Math.PI / 4.0 + Math.toRadians(lat) / 2.0))
        fun lonToX(lon: Double): Double = Math.toRadians(lon)

        val targetX = lonToX(point.longitude)
        val targetY = latToY(point.latitude)
        
        var inPoly = false
        var j = points.size - 1
        for (i in 0 until points.size) {
            val iY = latToY(points[i].latitude)
            val jY = latToY(points[j].latitude)
            val iX = lonToX(points[i].longitude)
            val jX = lonToX(points[j].longitude)
            
            if (((iY > targetY) != (jY > targetY)) &&
                (targetX < (jX - iX) * (targetY - iY) / (jY - iY) + iX)) {
                inPoly = !inPoly
            }
            j = i
        }
        return inPoly
    }

    private fun executeTacticalMission(transactionId: String? = null) {
        if (isMissionExecuting) {
            showToast("Mission already executing!")
            publishCommandReceipt(transactionId, "EXECUTE_MISSION", "FAILED", errorCode = -10, errorMessage = "Mission already executing")
            return
        }
        
        if (tacticalWaypoints.isEmpty()) {
            showToast("No waypoints to execute!")
            publishCommandReceipt(transactionId, "EXECUTE_MISSION", "FAILED", errorCode = -11, errorMessage = "No waypoints to execute")
            return
        }
        
        val expanded = expandOrbitWaypoints(tacticalWaypoints)
        
        // --- PRE-FLIGHT/CONFLICT CHECKS ---
        val safetyError = checkMissionSafety(expanded)
        if (safetyError != null) {
            showToast("PRE-FLIGHT REJECTED: $safetyError")
            publishCommandReceipt(transactionId, "EXECUTE_MISSION", "FAILED", errorCode = -12, errorMessage = "Safety Violation: $safetyError")
            if (::mqttService.isInitialized) {
                try {
                    val logObj = org.json.JSONObject()
                    logObj.put("event", "MISSION_SAFETY_REJECTED")
                    logObj.put("reason", safetyError)
                    mqttService.publishMission(jsonPayload = logObj.toString())
                } catch (e: Exception) { e.printStackTrace() }
            }
            return
        }
        
        // C-02: do NOT mutate tacticalWaypoints (the UI list). Use a dedicated execution list.
        // This prevents concurrent modification when the GPS listener calls updateFlightPathLine().
        val executionWaypoints = java.util.concurrent.CopyOnWriteArrayList<TacticalWaypoint>(expanded)
        
        // --- PRE-FLIGHT CHECKS ---
        try {
            val batt = droneBattery
            val gps = KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)) ?: 0
            
            if (batt < 20) {
                showToast("PRE-FLIGHT FAILED: Battery Too Low (< 20%)")
                publishCommandReceipt(transactionId, "EXECUTE_MISSION", "FAILED", errorCode = -13, errorMessage = "Battery Too Low (< 20%)")
                return
            }

            val isGpsDeniedMode = com.dji.recreate2.flight.ConfinedSpaceFlightManager.isGpsDeniedModeEnabled
            if (!isGpsDeniedMode) {
                if (gps < 10) {
                    showToast("PRE-FLIGHT FAILED: Weak GPS Signal (< 10 Sats)")
                    publishCommandReceipt(transactionId, "EXECUTE_MISSION", "FAILED", errorCode = -14, errorMessage = "Weak GPS Signal (< 10 Sats)")
                    return
                }
                if (droneLat.isNaN() || droneLon.isNaN()) {
                    showToast("PRE-FLIGHT FAILED: No Home Point / GPS Lock")
                    publishCommandReceipt(transactionId, "EXECUTE_MISSION", "FAILED", errorCode = -15, errorMessage = "No Home Point / GPS Lock")
                    return
                }
            } else {
                log("PRE-FLIGHT: GPS check bypassed (GPS-Denied Indoor VPS Mode Active)")
                showToast("PRE-FLIGHT: GPS Bypassed (Indoor VPS Mode Active)")
            }

            log("Pre-flight checks passed. Battery: $batt%, Satellites: $gps (GPS-Denied: $isGpsDeniedMode)")
            publishCommandReceipt(transactionId, "EXECUTE_MISSION", "EXECUTING")
        } catch (e: Exception) {
            log("Pre-flight check failed: ${e.message}")
            showToast("PRE-FLIGHT FAILED: System Error")
            publishCommandReceipt(transactionId, "EXECUTE_MISSION", "FAILED", errorCode = -16, errorMessage = "Pre-flight exception: ${e.message}")
            return
        }
        // -------------------------
        
        val kmzWaypoints = executionWaypoints.map { 
            KmzGenerator.KmzWaypoint(it.geoPoint, it.altitude, it.speed, it.heading, it.dwellTime, it.movementMethod)
        }
        val spd = executionWaypoints.firstOrNull()?.speed ?: 5.0
        
        showToast("Generating Native KMZ Mission...")
        val kmzFile = KmzGenerator.generateMappingKmz(this, kmzWaypoints, spd, signalLossAction)
        if (kmzFile != null) {
            executeNativeKMZ(kmzFile.absolutePath, autoStart = true)
            return
        } else {
            showToast("Failed to generate KMZ. Falling back to Virtual Stick.")
        }
        
        if (!droneAlt.isNaN() && droneAlt < 1.0) {
            showToast("Auto Takeoff Initiated...")
            executeTakeoff()
            // M-07: store thread ref so onDestroy can interrupt it
            val t = Thread({
                val deadline = System.currentTimeMillis() + 15000L
                while (!Thread.currentThread().isInterrupted && System.currentTimeMillis() < deadline) {
                    if (!droneAlt.isNaN() && droneAlt > 1.0) break
                    try { Thread.sleep(500) } catch (ie: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
                if (Thread.currentThread().isInterrupted || droneAlt <= 1.0) {
                    runOnUiThread { showToast("Takeoff timed out. Aborting Tactical Mission.") }
                } else {
                    runOnUiThread { startVirtualStickLoop(expanded) }
                }
            }, "TakeoffWait")
            takeoffWaitThread = t
            t.start()
        } else {
            startVirtualStickLoop(expanded)
        }
    }

    private fun startVirtualStickLoop(executionList: List<TacticalWaypoint>) {
        showToast("Executing Tactical Mission via Virtual Stick Engine...")
        log("Executing ${executionList.size} waypoints")
        
        val vs = dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance()
        vs.enableVirtualStick(object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                vs.setVirtualStickAdvancedModeEnabled(true)
                val param = com.dji.recreate2.flight.ConfinedSpaceFlightManager.createVirtualStickParam()
                vs.sendVirtualStickAdvancedParam(param)
                
                isMissionExecuting = true
                
                Thread({ // M-02: named thread for crash log readability
                    val threadParam = com.dji.recreate2.flight.ConfinedSpaceFlightManager.createVirtualStickParam()
                    var activePoiTarget: GeoPoint? = null
                    var cameraLockActive = false
                    var lastGimbalUpdate = 0L
                    var gpsLossCounter = 0
            
                    val wps = java.util.concurrent.CopyOnWriteArrayList<TacticalWaypoint>(executionList)
                    
                    while (isMissionExecuting && wps.isNotEmpty()) {
                        if (isFinishing) break
                        
                        if (!droneConnected) {
                            runOnUiThread { showToast("CRITICAL: Drone Disconnected. Aborting Mission!") }
                            isMissionExecuting = false
                            break
                        }
                        
                        if (droneLat.isNaN() || droneLon.isNaN() || droneAlt.isNaN()) {
                            gpsLossCounter++
                            if (gpsLossCounter > 100) {
                                runOnUiThread { showToast("CRITICAL: GPS Signal Lost. Aborting Mission!") }
                                isMissionExecuting = false
                                break
                            }
                            Thread.sleep(50)
                            continue
                        }
                        gpsLossCounter = 0
                        
                        if (wps.isEmpty()) break
                        val currentWp = wps.firstOrNull() ?: break
                
                if (!currentWp.preflightExecuted) {
                    val actions = currentWp.actionType.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                    for (action in actions) {
                        when (action) {
                            "START_RECORD" -> {
                                runOnUiThread { showToast("ACTION: STARTING RECORD") }
                                if (!isRecording) {
                                    runOnUiThread { toggleRecord() }
                                }
                            }
                            "STOP_RECORD" -> {
                                runOnUiThread { showToast("ACTION: STOPPING RECORD") }
                                if (isRecording) {
                                    runOnUiThread { toggleRecord() }
                                }
                            }
                            "LOCK_POI" -> {
                                runOnUiThread { showToast("ACTION: LOCKING GIMBAL TO POI") }
                                activePoiTarget = currentWp.poiTarget
                                cameraLockActive = true
                            }
                            "UNLOCK_POI" -> {
                                runOnUiThread { showToast("ACTION: UNLOCKING GIMBAL") }
                                activePoiTarget = null
                                cameraLockActive = false
                                // C-01: SDK performAction must be posted to main thread
                                runOnUiThread {
                                    val gimbalParam = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation()
                                    gimbalParam.mode = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode.ABSOLUTE_ANGLE
                                    gimbalParam.pitch = 0.0
                                    gimbalParam.roll = 0.0
                                    gimbalParam.yaw = 0.0
                                    gimbalParam.duration = 1.0
                                    KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN), gimbalParam, null)
                                }
                            }
                            "SET_GIMBAL" -> {
                                runOnUiThread { showToast("ACTION: SETTING GIMBAL TO ${currentWp.gimbalPitch}") }
                                if (currentWp.gimbalPitch != null) {
                                    // C-01: SDK performAction must run on main thread
                                    runOnUiThread {
                                        val gimbalParam = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation()
                                        gimbalParam.mode = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode.ABSOLUTE_ANGLE
                                        gimbalParam.pitch = currentWp.gimbalPitch!!
                                        gimbalParam.roll = 0.0
                                        gimbalParam.yaw = 0.0
                                        gimbalParam.duration = 1.0
                                        KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN), gimbalParam, null)
                                    }
                                }
                            }
                        }
                    }
                    currentWp.preflightExecuted = true
                }
                
                val results = FloatArray(3)
                android.location.Location.distanceBetween(droneLat, droneLon, currentWp.geoPoint.latitude, currentWp.geoPoint.longitude, results)
                val distance = results[0]
                val bearing = results[1]
                
                // --- POI GIMBAL LOCK LOGIC ---
                if (cameraLockActive && activePoiTarget != null) {
                    val poiRes = FloatArray(3)
                    android.location.Location.distanceBetween(droneLat, droneLon, activePoiTarget!!.latitude, activePoiTarget!!.longitude, poiRes)
                    val poiDist = poiRes[0]
                    val poiBearing = poiRes[1]
                    
                    val poiRelativeBearing = poiBearing - droneYaw
                    val poiNormalizedYaw = ((poiRelativeBearing % 360) + 540) % 360 - 180
                    val poiTargetPitch = Math.toDegrees(Math.atan2(-droneAlt, poiDist.toDouble()))
                    
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastGimbalUpdate > 250) {
                        val gimbalParam = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation()
                        gimbalParam.mode = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode.ABSOLUTE_ANGLE
                        gimbalParam.pitch = poiTargetPitch
                        gimbalParam.roll = 0.0
                        gimbalParam.yaw = poiNormalizedYaw
                        gimbalParam.duration = 0.3
                        // C-01: SDK performAction must run on main thread
                        runOnUiThread {
                            dji.v5.manager.KeyManager.getInstance().performAction(
                                dji.sdk.keyvalue.key.KeyTools.createKey(dji.sdk.keyvalue.key.GimbalKey.KeyRotateByAngle, dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN),
                                gimbalParam, null)
                        }
                        lastGimbalUpdate = currentTime
                    }
                }
                val altDiff = currentWp.altitude - droneAlt
                
                if (Math.abs(altDiff) > 1.0) {
                    // Altitude adjustment phase
                    vs.setVirtualStickAdvancedModeEnabled(true)
                    
                    // Proportional climb/descent rate (max 300 RC out of 660)
                    val vertSpeed = (altDiff * 100.0).coerceIn(-300.0, 300.0).toInt()
                    
                    vs.leftStick.verticalPosition = vertSpeed
                    vs.leftStick.horizontalPosition = 0
                    vs.rightStick.verticalPosition = 0
                    vs.rightStick.horizontalPosition = 0
                    vs.sendVirtualStickAdvancedParam(threadParam)
                } else if (distance > 5.0) {
                    // Horizontal flight phase
                    vs.setVirtualStickAdvancedModeEnabled(true) // Use Advanced GROUND mode
                    
                    val maxRc = 660.0
                    val speedFactor = Math.min(currentWp.speed / 15.0, 1.0) // Normalize speed to 15m/s max
                    
                    // Convert target bearing to absolute velocities for GROUND coordinate system
                    val pitch = maxRc * Math.cos(Math.toRadians(bearing.toDouble())) * speedFactor
                    val roll = maxRc * Math.sin(Math.toRadians(bearing.toDouble())) * speedFactor
                    
                    // Determine which way the drone's nose (Yaw) should point
                    val targetYawBearing = if (cameraLockActive && activePoiTarget != null) {
                        val poiRes = FloatArray(3)
                        android.location.Location.distanceBetween(droneLat, droneLon, activePoiTarget!!.latitude, activePoiTarget!!.longitude, poiRes)
                        poiRes[1] // Face the POI
                    } else {
                        bearing // Face the Waypoint
                    }
                    
                    val relativeYawError = targetYawBearing - droneYaw
                    val normalizedYaw = ((relativeYawError % 360) + 540) % 360 - 180
                    
                    // Proportional yaw control (maxing out at 300 so it doesn't spin violently)
                    val yawSpeed = (normalizedYaw * 4.0).coerceIn(-300.0, 300.0).toInt()
                    
                    // Yaw Lock Gate (Fix for Drunken Spiral)
                    // If the drone is facing more than 5 degrees away from the target heading,
                    // pause horizontal movement and prioritize rotation.
                    val actualPitch = if (Math.abs(normalizedYaw) > 5.0) 0 else pitch.toInt()
                    val actualRoll = if (Math.abs(normalizedYaw) > 5.0) 0 else roll.toInt()
                    
                    vs.leftStick.verticalPosition = 0
                    vs.leftStick.horizontalPosition = yawSpeed
                    vs.rightStick.verticalPosition = actualPitch
                    vs.rightStick.horizontalPosition = actualRoll
                    
                    vs.sendVirtualStickAdvancedParam(threadParam)
                } else {
                    // Reached the waypoint!
                    
                    // Stop movement
                    vs.leftStick.verticalPosition = 0
                    vs.leftStick.horizontalPosition = 0
                    vs.rightStick.verticalPosition = 0
                    vs.rightStick.horizontalPosition = 0
                    vs.sendVirtualStickAdvancedParam(threadParam)
                    
                    // --- WAYPOINT ACTION EXECUTION ---
                    val postActions = currentWp.actionType.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                    for (action in postActions) {
                        when (action) {
                            "PHOTO" -> {
                                runOnUiThread { showToast("ACTION: CAPTURING PHOTO") }
                                if (currentWp.poiTarget != null) {
                                    val poiRes = FloatArray(3)
                                    android.location.Location.distanceBetween(droneLat, droneLon, currentWp.poiTarget!!.latitude, currentWp.poiTarget!!.longitude, poiRes)
                                    val distanceToPoi = poiRes[0]
                                    if (distanceToPoi > 0) {
                                        val poiBearing = poiRes[1]
                                        val relativeBearing = poiBearing - droneYaw
                                        val normalizedYaw = ((relativeBearing % 360) + 540) % 360 - 180
                                        val targetPitch = Math.toDegrees(Math.atan2(-droneAlt, distanceToPoi.toDouble()))
                                        
                                        val gimbalParam = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation()
                                        gimbalParam.mode = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode.ABSOLUTE_ANGLE
                                        gimbalParam.pitch = targetPitch
                                        gimbalParam.roll = 0.0
                                        gimbalParam.yaw = normalizedYaw
                                        gimbalParam.duration = 1.0
                                        KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN), gimbalParam, null)
                                        Thread.sleep(3000)
                                    }
                                }
                                if (activeMappingMode == MappingMode.QUICK) {
                                    runOnUiThread { showToast("ACTION: CAPTURING QUICK SCREENSHOT") }
                                    captureQuickScreenshot()
                                } else {
                                    runOnUiThread { showToast("ACTION: CAPTURING NATIVE PHOTO") }
                                    capturePhoto()
                                }
                                Thread.sleep(1500)
                            }
                        }
                    }
                    
                    if (wps.isNotEmpty()) {
                        wps.removeAt(0)
                    } else {
                        break
                    }
                    runOnUiThread {
                        currentWp.osmdroidMarker?.let { mapView.overlays.remove(it) }
                        mapView.invalidate()
                    }
                    
                    if (wps.isEmpty()) {
                        isMissionExecuting = false
                        runOnUiThread { showToast("Waypoint Mission Completed") }
                    } else {
                        runOnUiThread { showToast("Waypoint Reached. Proceeding to next.") }
                    }
                }
                Thread.sleep(50)
            }
            
            vs.disableVirtualStick(null)
            
            runOnUiThread {
                tacticalWaypoints.clear()
                mapView.overlays.removeAll { it is org.osmdroid.views.overlay.Marker && it != droneMarker && it != homeMapMarker || it is org.osmdroid.views.overlay.Polygon || it is org.osmdroid.views.overlay.Polyline && it != headingLine }
                updateFlightPathLine()
                mapView.invalidate()
            }
        }, "VS-MissionLoop").start() // M-02: named for crash log readability
            }
            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                runOnUiThread { showToast("C2 Error: Failed to enable Virtual Stick: ${error.description()}") }
            }
        })
        
        val tvControlStatus = findViewById<TextView>(R.id.tvControlStatus)
        if (currentControlMode != ControlMode.MAP) {
            runOnUiThread {
                tvControlStatus.visibility = View.VISIBLE
                tvControlStatus.text = "MAP"
                startBlinkingAnimation(tvControlStatus)
            }
        }
    }



    private fun updateHeadingLine() {
        val line = headingLine ?: return
        if (droneLat.isNaN() || droneLon.isNaN()) return

        val startPoint = GeoPoint(droneLat, droneLon)
        
        val distanceMeters = 50.0
        val earthRadius = 6378137.0
        val latRad = Math.toRadians(droneLat)
        val lonRad = Math.toRadians(droneLon)
        val bearingRad = Math.toRadians(droneYaw)
        
        val angularDist = distanceMeters / earthRadius
        
        val endLatRad = Math.asin(Math.sin(latRad) * Math.cos(angularDist) + 
                        Math.cos(latRad) * Math.sin(angularDist) * Math.cos(bearingRad))
        
        val endLonRad = lonRad + Math.atan2(Math.sin(bearingRad) * Math.sin(angularDist) * Math.cos(latRad), 
                                            Math.cos(angularDist) - Math.sin(latRad) * Math.sin(endLatRad))
                                            
        val endLat = Math.toDegrees(endLatRad)
        val endLon = Math.toDegrees(endLonRad)
        
        val endPoint = GeoPoint(endLat, endLon)

        line.setPoints(listOf(startPoint, endPoint))
        mapView.invalidate()
    }

    private fun setupImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }
    }
    
    private fun monitorCameraStorage() {
        val storageKey = KeyTools.createKey(CameraKey.KeyCameraStorageInfos, ComponentIndexType.LEFT_OR_MAIN)
        KeyManager.getInstance().listen(storageKey, this) { _, newValue ->
            newValue?.let {
                val sdCardInfo = it.getCameraStorageInfoByLocation(CameraStorageLocation.SDCARD)
                val sdState = sdCardInfo?.storageState
                
                if (sdState?.name == "ERROR" || sdState?.name == "FULL" || sdState?.name == "INVALID" || sdState?.name == "FORMAT_RECOMMENDED") {
                    runOnUiThread {
                        alertStorage.visibility = View.VISIBLE
                        startBlinkingAnimation(alertStorage)
                    }
                } else {
                    runOnUiThread {
                        alertStorage.visibility = View.GONE
                        alertStorage.clearAnimation()
                    }
                }
            }
        }
    }
    
    private fun setupObstacleRadar() {
        val pm = PerceptionManager.getInstance()
        
        pm.setObstacleAvoidanceEnabled(true, dji.v5.manager.aircraft.perception.data.PerceptionDirection.HORIZONTAL, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { log("Horizontal Obstacle Detection Enabled") }
            override fun onFailure(error: IDJIError) { log("Failed to enable Obstacle Detection: ${error.errorCode()}") }
        })
        
        obstacleDataListener = dji.v5.manager.aircraft.perception.listener.ObstacleDataListener { data ->
            val horiz = data.horizontalObstacleDistance
            if (horiz != null && horiz.isNotEmpty()) {
                val frontIdx = 0
                val rightIdx = horiz.size / 4
                val backIdx = horiz.size / 2
                val leftIdx = horiz.size * 3 / 4
                
                val frontM = horiz[frontIdx] / 1000.0
                val rightM = horiz[rightIdx] / 1000.0
                val backM = horiz[backIdx] / 1000.0
                val leftM = horiz[leftIdx] / 1000.0
                
                val nearestDist = minOf(frontM, rightM, backM, leftM)
                
                runOnUiThread {
                    obstacleRadar.maxRadarDistance = radarMaxDistance
                    obstacleRadar.updateDistances(frontM, backM, leftM, rightM)
                    
                    if (nearestDist < radarMaxDistance && radarEnabled) {
                        alertObstacle.visibility = View.VISIBLE
                        alertObstacle.text = String.format("OBSTACLE: %.1fm", nearestDist)
                        if (alertObstacle.animation == null) {
                            startBlinkingAnimation(alertObstacle)
                        }
                    } else {
                        alertObstacle.visibility = View.GONE
                        alertObstacle.clearAnimation()
                    }
                }
            }
        }
        pm.addObstacleDataListener(obstacleDataListener!!)
    }

    private var isRecording = false

    private fun setCameraMode(mode: dji.sdk.keyvalue.value.camera.CameraMode, callback: () -> Unit) {
        val modeKey = KeyTools.createKey(CameraKey.KeyCameraMode, ComponentIndexType.LEFT_OR_MAIN)
        KeyManager.getInstance().setValue(modeKey, mode, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                callback()
            }
            override fun onFailure(error: IDJIError) {
                log("Failed to switch camera mode: ${error.errorCode()}")
                showToast("Switch Mode Failed")
            }
        })
    }

    private fun capturePhoto() {
        // ISR Mode 1: PixelCopy FPV frame → S3 + also shoot full-res photo to drone SD card
        if (com.dji.recreate2.sync.ISRModeManager.currentMode == "MODE1") {
            log("ISR Mode 1 — IMG pressed: FPV PixelCopy → S3 + drone shoot → SD")
            showToast("ISR: Capturing → S3…")

            // Mark position on map
            if (!droneLat.isNaN() && !droneLon.isNaN()) {
                runOnUiThread { addImageCaptureMarker(org.osmdroid.util.GeoPoint(droneLat, droneLon)) }
            }

            // Also trigger drone's built-in camera → full-res saved to SD card
            setCameraMode(dji.sdk.keyvalue.value.camera.CameraMode.PHOTO_NORMAL) {
                val shootKey = KeyTools.createKey(CameraKey.KeyStartShootPhoto, ComponentIndexType.LEFT_OR_MAIN)
                KeyManager.getInstance().performAction(shootKey, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) { log("ISR: full-res photo saved to drone SD") }
                    override fun onFailure(error: IDJIError) { log("ISR: drone shoot failed (SD only): ${error.errorCode()}") }
                })
            }

            // PixelCopy FPV surface → JPEG → S3 (immediate, no MediaFile pipeline)
            val w = fpvSurface.width
            val h = fpvSurface.height
            if (w <= 0 || h <= 0) {
                showToast("ISR: No FPV feed — is drone streaming?")
                return
            }
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(fpvSurface, bmp, { result ->
                if (result == android.view.PixelCopy.SUCCESS) {
                    val dateFolder = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    val fileName   = "isr_cam_${System.currentTimeMillis()}.jpg"
                    val cacheFile  = java.io.File(cacheDir, fileName)
                    Thread {
                        try {
                            cacheFile.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                            injectExifMetadata(cacheFile)
                            com.dji.recreate2.aws.S3UploadManager.uploadFile(
                                context        = this,
                                localFile      = cacheFile,
                                remoteFolder   = dateFolder,
                                remoteFileName = fileName,
                                onSuccess = {
                                    runOnUiThread { showToast("ISR ✔ $fileName → S3") }
                                    log("ISR upload OK → $dateFolder/$fileName")
                                    cacheFile.delete()
                                },
                                onError = { err ->
                                    runOnUiThread { showToast("ISR ✗ Upload failed: $err") }
                                    log("ISR upload error: $err")
                                    cacheFile.delete()
                                }
                            )
                        } catch (e: Exception) { log("ISR save error: ${e.message}") }
                    }.start()
                } else {
                    runOnUiThread { showToast("ISR ✗ Capture failed (code $result)") }
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            return
        }

        // Normal capture (ISR off)
        log("Switching to Photo Mode...")
        setCameraMode(dji.sdk.keyvalue.value.camera.CameraMode.PHOTO_NORMAL) {
            val action = KeyTools.createKey(CameraKey.KeyStartShootPhoto, ComponentIndexType.LEFT_OR_MAIN)
            KeyManager.getInstance().performAction(action, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    log("Photo Captured!")
                    showToast("Photo Captured")
                    if (!droneLat.isNaN() && !droneLon.isNaN()) {
                        runOnUiThread { addImageCaptureMarker(org.osmdroid.util.GeoPoint(droneLat, droneLon)) }
                    }
                }
                override fun onFailure(error: IDJIError) {
                    log("Photo Capture Failed: ${error.description() ?: error.errorCode()}")
                    showToast("Photo Capture Failed: ${error.description() ?: error.errorCode()}")
                }
            })
        }
    }

    
    private fun captureQuickScreenshot() {
        if (droneLat.isNaN() || droneLon.isNaN()) {
            runOnUiThread { showToast("Cannot Quick Capture: No GPS Lock") }
            return
        }
        
        val lat = droneLat
        val lon = droneLon
        val alt = droneAlt
        
        runOnUiThread {
            try {
                val bitmap = android.graphics.Bitmap.createBitmap(fpvSurface.width, fpvSurface.height, android.graphics.Bitmap.Config.ARGB_8888)
                android.view.PixelCopy.request(fpvSurface, bitmap, { result ->
                    if (result == android.view.PixelCopy.SUCCESS) {
                        Thread {
                            try {
                                val dir = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Recreate2_QuickMap")
                                if (!dir.exists()) dir.mkdirs()
                                
                                val file = java.io.File(dir, "QuickMap_${System.currentTimeMillis()}.jpg")
                                val fos = java.io.FileOutputStream(file)
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos)
                                fos.flush()
                                fos.close()
                                
                                // Inject EXIF
                                val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)
                                
                                fun convertToDegreeMinuteSeconds(latLong: Double): String {
                                    val absLatLong = Math.abs(latLong)
                                    val degree = absLatLong.toInt()
                                    val minute = ((absLatLong - degree) * 60).toInt()
                                    val second = (((absLatLong - degree) * 60) - minute) * 60
                                    return "$degree/1,$minute/1,${(second * 1000).toInt()}/1000"
                                }
                                
                                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE, convertToDegreeMinuteSeconds(lat))
                                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE_REF, if (lat > 0) "N" else "S")
                                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE, convertToDegreeMinuteSeconds(lon))
                                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE_REF, if (lon > 0) "E" else "W")
                                
                                val altAbs = Math.abs(alt)
                                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE, "${(altAbs * 1000).toInt()}/1000")
                                exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE_REF, if (alt < 0) "1" else "0")
                                
                                exif.saveAttributes()
                                
                                runOnUiThread {
                                    addImageCaptureMarker(org.osmdroid.util.GeoPoint(lat, lon))
                                    log("QuickMap Screenshot saved: ${file.name}")
                                    
                                    if (::mqttService.isInitialized) {
                                        val logObj = org.json.JSONObject()
                                        logObj.put("event", "PHOTO_CAPTURED")
                                        logObj.put("isMissionExecuting", isMissionExecuting)
                                        logObj.put("quickScan", true)
                                        mqttService.publishMission(jsonPayload = logObj.toString())
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }.start()
                    } else {
                        showToast("PixelCopy Failed")
                    }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Screenshot Failed: ${e.message}")
            }
        }
    }

    /**
     * Injects standard GPS, Altitude, Timestamp, and Drone Telemetry EXIF tags into JPEG files
     * for photogrammetry, mapping software (WebODM, Pix4D, QGIS), and GIS pipelines.
     */
    private fun injectExifMetadata(file: File) {
        if (!file.exists() || droneLat.isNaN() || droneLon.isNaN()) return
        try {
            val exif = androidx.exifinterface.media.ExifInterface(file.absolutePath)

            // Latitude N/S
            val latRef = if (droneLat < 0) "S" else "N"
            val latAbs = Math.abs(droneLat)
            val latDeg = latAbs.toInt()
            val latMin = ((latAbs - latDeg) * 60).toInt()
            val latSec = (latAbs - latDeg - latMin / 60.0) * 3600.0
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE,
                "$latDeg/1,$latMin/1,${(latSec * 1000).toInt()}/1000"
            )
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE_REF, latRef)

            // Longitude E/W
            val lonRef = if (droneLon < 0) "W" else "E"
            val lonAbs = Math.abs(droneLon)
            val lonDeg = lonAbs.toInt()
            val lonMin = ((lonAbs - lonDeg) * 60).toInt()
            val lonSec = (lonAbs - lonDeg - lonMin / 60.0) * 3600.0
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE,
                "$lonDeg/1,$lonMin/1,${(lonSec * 1000).toInt()}/1000"
            )
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE_REF, lonRef)

            // Altitude
            val altAbs = Math.abs(droneAlt)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE,
                "${(altAbs * 1000).toInt()}/1000"
            )
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
                if (droneAlt < 0) "1" else "0"
            )

            // Timestamp
            val nowStr = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME, nowStr)
            exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL, nowStr)

            // Mapping & Telemetry tags (WebODM, Pix4D, ArcGIS compatible)
            exif.setAttribute(
                androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT,
                "DroneLat=$droneLat,DroneLon=$droneLon,DroneAlt=$droneAlt,Yaw=$droneYaw,GimbalPitch=$gimbalPitch"
            )

            exif.saveAttributes()
            log("EXIF Mapping Metadata injected into ${file.name}: Lat=$droneLat, Lon=$droneLon, Alt=$droneAlt, Yaw=$droneYaw")
        } catch (e: Exception) {
            log("Failed to inject EXIF metadata: ${e.message}")
        }
    }
    
    private fun addImageCaptureMarker(geoPoint: org.osmdroid.util.GeoPoint) {
        val marker = org.osmdroid.views.overlay.Marker(mapView)
        marker.position = geoPoint
        marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
        
        // Draw a small yellow circle for the photo point
        val bitmap = android.graphics.Bitmap.createBitmap(30, 30, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        paint.color = android.graphics.Color.YELLOW
        paint.style = android.graphics.Paint.Style.FILL
        paint.isAntiAlias = true
        canvas.drawCircle(15f, 15f, 10f, paint)
        
        val borderPaint = android.graphics.Paint()
        borderPaint.color = android.graphics.Color.BLACK
        borderPaint.style = android.graphics.Paint.Style.STROKE
        borderPaint.strokeWidth = 2f
        borderPaint.isAntiAlias = true
        canvas.drawCircle(15f, 15f, 10f, borderPaint)
        
        marker.icon = android.graphics.drawable.BitmapDrawable(resources, bitmap)
        marker.title = "Photo Captured"
        
        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    private fun toggleRecord() {
        val btnRecord = findViewById<TextView>(R.id.btnRecord)
        if (isRecording) {
            // Stop recording
            val action = KeyTools.createKey(CameraKey.KeyStopRecord, ComponentIndexType.LEFT_OR_MAIN)
            KeyManager.getInstance().performAction(action, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                    isRecording = false
                    runOnUiThread {
                        btnRecord.text = "REC"
                        btnRecord.setTextColor(android.graphics.Color.GREEN)
                    }
                    log("Recording Stopped")
                    showToast("Recording Stopped")

                    // ISR Mode 1: Stop raw FPV video recorder and upload clean MP4 + companion SRT to S3
                    if (com.dji.recreate2.sync.ISRModeManager.currentMode == "MODE1") {
                        val (recordedMp4, recordedSrt) = com.dji.recreate2.sync.FpvStreamRecorder.stopRecording()
                        if (recordedMp4 != null && recordedMp4.exists() && recordedMp4.length() > 0) {
                            val dateFolder = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                            val videoName = recordedMp4.name
                            log("ISR Mode 1: Raw FPV video recorded (${recordedMp4.length()} bytes) -> uploading MP4 + SRT to S3...")
                            showToast("ISR Mode 1: Uploading Video & SRT → S3…")

                            // Upload MP4 Video
                            com.dji.recreate2.aws.S3UploadManager.uploadFile(
                                context = this@MainActivity,
                                localFile = recordedMp4,
                                remoteFolder = dateFolder,
                                remoteFileName = videoName,
                                onSuccess = {
                                    runOnUiThread { showToast("ISR ✔ Video Uploaded: $videoName") }
                                    log("ISR Mode 1 raw video upload OK -> $dateFolder/$videoName")
                                    recordedMp4.delete()
                                },
                                onError = { err ->
                                    runOnUiThread { showToast("ISR ✗ Video upload failed: $err") }
                                    log("ISR Mode 1 raw video upload error: $err")
                                }
                            )

                            // Upload companion .SRT Subtitle File
                            if (recordedSrt != null && recordedSrt.exists() && recordedSrt.length() > 0) {
                                val srtName = recordedSrt.name
                                com.dji.recreate2.aws.S3UploadManager.uploadFile(
                                    context = this@MainActivity,
                                    localFile = recordedSrt,
                                    remoteFolder = dateFolder,
                                    remoteFileName = srtName,
                                    onSuccess = {
                                        log("ISR Mode 1 companion SRT upload OK -> $dateFolder/$srtName")
                                        recordedSrt.delete()
                                    },
                                    onError = { err ->
                                        log("ISR Mode 1 SRT upload error: $err")
                                    }
                                )
                            }
                        } else {
                            // Fallback to camera SD card video fetch if tablet recorder produced no file
                            log("ISR Mode 1: Tablet FPV recorder empty, falling back to drone storage fetch...")
                            com.dji.recreate2.sync.ISRModeManager.triggerMode1VideoUpload(this@MainActivity)
                        }
                    }
                }
                override fun onFailure(error: IDJIError) {
                    log("Stop Record Failed")
                }
            })
        } else {
            log("Switching to Video Mode...")
            setCameraMode(dji.sdk.keyvalue.value.camera.CameraMode.VIDEO_NORMAL) {
                val action = KeyTools.createKey(CameraKey.KeyStartRecord, ComponentIndexType.LEFT_OR_MAIN)
                KeyManager.getInstance().performAction(action, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                        isRecording = true
                        runOnUiThread {
                            btnRecord.text = "REC"
                            btnRecord.setTextColor(android.graphics.Color.RED)
                        }
                        log("Recording Started")
                        showToast("Recording Started")

                        // ISR Mode 1: Start raw FPV video recorder on tablet with 1s telemetry logging for .SRT
                        if (com.dji.recreate2.sync.ISRModeManager.currentMode == "MODE1") {
                            log("ISR Mode 1: Starting raw FPV stream video recorder + SRT telemetry logger...")
                            com.dji.recreate2.sync.FpvStreamRecorder.startRecording(
                                surfaceView = fpvSurface,
                                context = this@MainActivity,
                                fps = 20,
                                telemetryProvider = {
                                    com.dji.recreate2.sync.FpvStreamRecorder.TelemetrySample(
                                        timestampMs = System.currentTimeMillis(),
                                        lat = droneLat,
                                        lon = droneLon,
                                        alt = droneAlt,
                                        speed = droneSpeed,
                                        yaw = droneYaw,
                                        gimbalPitch = gimbalPitch,
                                        sats = droneSatellites,
                                        battery = droneBattery
                                    )
                                }
                            )
                        }
                    }
                    override fun onFailure(error: IDJIError) {
                        log("Start Record Failed: ${error.errorCode()}")
                    }
                })
            }
        }
    }

    private fun configureCameraStorage() {
        val storageKey = KeyTools.createKey(CameraKey.KeyCameraStorageLocation, ComponentIndexType.LEFT_OR_MAIN)
        KeyManager.getInstance().setValue(
            storageKey,
            dji.sdk.keyvalue.value.camera.CameraStorageLocation.SDCARD,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    log("Camera configured to save media to SD Card.")
                }
                override fun onFailure(error: IDJIError) {
                    log("Failed to configure SD card storage: ${error.errorCode()}")
                }
            }
        )
    }

    private fun downloadLatestMedia() {
        val mediaManager = MediaDataCenter.getInstance().mediaManager
        log("Fetching media list...")
        
        mediaManager.pullMediaFileListFromCamera(
            PullMediaFileListParam.Builder().mediaFileIndex(-1).count(-1).build(),
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    val mediaList = mediaManager.mediaFileListData.data
                    log("Scanned SD Card: Found ${mediaList.size} files.")
                    
                    if (mediaList.isNotEmpty()) {
                        val latestFile = mediaList.maxByOrNull { it.fileIndex }
                        latestFile?.let { downloadFileToDedicatedFolder(it) }
                    } else {
                        log("No media files found on drone.")
                        showToast("No media found.")
                    }
                }

                override fun onFailure(error: IDJIError) {
                    log("Failed to pull media list: ${error.errorCode()}")
                    showToast("Failed to fetch media list.")
                }
            }
        )
    }

    private fun downloadFileToDedicatedFolder(mediaFile: MediaFile) {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DJI_SDK_Media")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        val file = File(folder, mediaFile.fileName)
        
        log("Downloading ${mediaFile.fileName}...")
        showToast("Downloading ${mediaFile.fileName}...")
        
        try {
            val outputStream = FileOutputStream(file, false)
            val bos = BufferedOutputStream(outputStream)

            mediaFile.pullOriginalMediaFileFromCamera(0L, object : MediaFileDownloadListener {
                override fun onStart() { }
                override fun onProgress(total: Long, current: Long) { }
                override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                    try {
                        bos.write(data)
                        bos.flush()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
                override fun onFinish() {
                    try {
                        outputStream.close()
                        bos.close()
                    } catch (e: IOException) {}
                    
                    runOnUiThread {
                        showToast("Saved: Download/DJI_SDK_Media/${mediaFile.fileName}")
                        log("Download Complete: ${mediaFile.fileName}")
                    }
                }
                override fun onFailure(error: IDJIError?) {
                    try {
                        bos.close()
                        outputStream.close()
                    } catch (e: Exception) {}
                    try {
                        if (file.exists()) file.delete()
                    } catch (e: Exception) {}
                    runOnUiThread {
                        log("Download failed: ${error?.errorCode()}")
                        showToast("Download failed")
                    }
                }
            })
        } catch (e: Exception) {
            log("File setup error: ${e.message}")
        }
    }

    private fun executeTakeoff(transactionId: String? = null) {
        log("Executing Auto Takeoff...")
        publishCommandReceipt(transactionId, "TAKEOFF", "EXECUTING")
        val action = KeyTools.createKey(FlightControllerKey.KeyStartTakeoff)
        KeyManager.getInstance().performAction(action, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
            override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                log("Takeoff initiated!")
                showToast("Takeoff Started")
                publishCommandReceipt(transactionId, "TAKEOFF", "COMPLETED")
                if (::mqttService.isInitialized) {
                    val logObj = org.json.JSONObject()
                    logObj.put("event", "TAKEOFF_SUCCESS")
                    mqttService.publishMission(jsonPayload = logObj.toString())
                }
            }
            override fun onFailure(error: IDJIError) {
                val errorCode = error.errorCode()
                val errorStr = error.toString()
                
                val reason = when {
                    errorStr.contains("GPS", ignoreCase = true) -> "NO GPS LOCK"
                    errorStr.contains("NFZ", ignoreCase = true) || errorStr.contains("NoFly", ignoreCase = true) -> "NFZ BLOCKED"
                    errorStr.contains("battery", ignoreCase = true) -> "LOW BATTERY"
                    errorStr.contains("motor", ignoreCase = true) -> "MOTORS LOCKED"
                    else -> "ERR: $errorCode"
                }
                
                log("Takeoff failed: $errorStr")
                showToast("Takeoff Failed: $reason")
                publishCommandReceipt(transactionId, "TAKEOFF", "FAILED", errorCode = errorCode, errorMessage = errorStr)
                if (::mqttService.isInitialized) {
                    val logObj = org.json.JSONObject()
                    logObj.put("event", "TAKEOFF_FAILED")
                    logObj.put("error", reason)
                    mqttService.publishMission(jsonPayload = logObj.toString())
                }
            }
        })
    }

    private fun executeReturnToHome(transactionId: String? = null) {
        log("Executing Return to Home (RTH)...")
        publishCommandReceipt(transactionId, "RTH", "EXECUTING")
        isMissionExecuting = false
        dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance().disableVirtualStick(null)
        val action = KeyTools.createKey(FlightControllerKey.KeyStartGoHome)
        KeyManager.getInstance().performAction(action, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
            override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                log("Return to Home initiated!")
                showToast("Returning Home")
                publishCommandReceipt(transactionId, "RTH", "COMPLETED")
            }
            override fun onFailure(error: IDJIError) {
                log("RTH failed: ${error.errorCode()}")
                showToast("RTH Error")
                publishCommandReceipt(transactionId, "RTH", "FAILED", errorCode = error.errorCode(), errorMessage = error.description())
            }
        })
    }

    private fun executeLanding(transactionId: String? = null) {
        log("Executing Auto Landing...")
        publishCommandReceipt(transactionId, "LAND", "EXECUTING")
        isMissionExecuting = false
        dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance().disableVirtualStick(null)
        val action = KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding)
        KeyManager.getInstance().performAction(action, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
            override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                log("Auto Landing initiated!")
                showToast("Landing Started")
                publishCommandReceipt(transactionId, "LAND", "COMPLETED")
            }
            override fun onFailure(error: IDJIError) {
                log("Landing failed: ${error.errorCode()}")
                showToast("Landing Error")
                publishCommandReceipt(transactionId, "LAND", "FAILED", errorCode = error.errorCode(), errorMessage = error.description())
            }
        })
    }
    
    private fun executeManualStart() {
        val virtualStickManager = dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance()
        
        log("Enabling Virtual Sticks for Manual Start...")
        virtualStickManager.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                startCscSequence()
            }
            override fun onFailure(error: IDJIError) {
                log("Failed to enable Virtual Sticks: ${error.errorCode()}")
                showToast("Failed to initiate Manual Start")
            }
        })
    }

    private fun startCscSequence() {
        val virtualStickManager = dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance()
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            log("Initiating Virtual CSC Engine Start...")
            showToast("Starting Engines (CSC)...")
            
            virtualStickManager.setVirtualStickAdvancedModeEnabled(false)
            
            virtualStickManager.leftStick.verticalPosition = -660
            virtualStickManager.leftStick.horizontalPosition = 660
            virtualStickManager.rightStick.verticalPosition = -660
            virtualStickManager.rightStick.horizontalPosition = -660
            virtualStickManager.sendVirtualStickAdvancedParam(dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam())
            
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                log("Virtual CSC Complete. Engines should be idling.") 
                virtualStickManager.leftStick.verticalPosition = 0
                virtualStickManager.leftStick.horizontalPosition = 0
                virtualStickManager.rightStick.verticalPosition = 0
                virtualStickManager.rightStick.horizontalPosition = 0
                virtualStickManager.sendVirtualStickAdvancedParam(dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam())
                
                if (!joysticksVisible) {
                    virtualStickManager.disableVirtualStick(null)
                }
            }, 2500)
            
        }, 500)
    }

    private var joysticksVisible = false
    
    private fun updateJoysticksUI() {
        val container = findViewById<View>(R.id.joystickContainer)
        val leftJoystick = findViewById<View>(R.id.leftJoystick)
        val rightJoystick = findViewById<View>(R.id.rightJoystick)
        
        if (!joysticksVisible || currentControlMode == ControlMode.MAP) {
            container.visibility = View.GONE
        } else if (currentControlMode == ControlMode.CAM) {
            container.visibility = View.VISIBLE
            leftJoystick.visibility = View.GONE
            rightJoystick.visibility = View.VISIBLE
        } else if (currentControlMode == ControlMode.FLY) {
            container.visibility = View.VISIBLE
            leftJoystick.visibility = View.VISIBLE
            rightJoystick.visibility = View.VISIBLE
        }
    }
    
    private fun toggleJoysticks() {
        val btnToggle = findViewById<TextView>(R.id.btnToggleJoysticks)
        val virtualStickManager = dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance()
        
        if (joysticksVisible) {
            joysticksVisible = false
            btnToggle.text = "STK"
            updateJoysticksUI()
            virtualStickManager.disableVirtualStick(null)
        } else {
            joysticksVisible = true
            btnToggle.text = "STK"
            updateJoysticksUI()
            virtualStickManager.enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    virtualStickManager.setVirtualStickAdvancedModeEnabled(false)
                    log("Virtual Sticks Enabled")
                }
                override fun onFailure(error: IDJIError) {
                    log("Failed to enable virtual stick")
                }
            })
        }
    }
    
    private fun handleJoystickCancelMission(pX: Float, pY: Float) {
        if (isMissionExecuting && (pX != 0f || pY != 0f)) {
            isMissionExecuting = false
            val vs = dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance()
            vs.leftStick.horizontalPosition = 0
            vs.leftStick.verticalPosition = 0
            vs.rightStick.horizontalPosition = 0
            vs.rightStick.verticalPosition = 0
            val param = dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam()
            param.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            vs.sendVirtualStickAdvancedParam(param)
            showToast("Tactical Mission Cancelled by Joystick Override")
            
            runOnUiThread { 
                findViewById<View>(R.id.btnModeFly).performClick()
            }
        }
    }
    
    private fun setupJoysticks() {
        val leftJoystick = findViewById<com.dji.recreate2.virtualstick.OnScreenJoystick>(R.id.leftJoystick)
        val rightJoystick = findViewById<com.dji.recreate2.virtualstick.OnScreenJoystick>(R.id.rightJoystick)
        val virtualStickManager = dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance()

        leftJoystick.setJoystickListener(object : com.dji.recreate2.virtualstick.OnScreenJoystickListener {
            override fun onTouch(joystick: com.dji.recreate2.virtualstick.OnScreenJoystick?, pX: Float, pY: Float) {
                handleJoystickCancelMission(pX, pY)
                if (joysticksVisible && currentControlMode == ControlMode.FLY) {
                    virtualStickManager.leftStick.horizontalPosition = (pX * 660.0f * flightSensitivity).toInt()
                    virtualStickManager.leftStick.verticalPosition = (pY * 660.0f * flightSensitivity).toInt()
                }
            }
        })

        rightJoystick.setJoystickListener(object : com.dji.recreate2.virtualstick.OnScreenJoystickListener {
            override fun onTouch(joystick: com.dji.recreate2.virtualstick.OnScreenJoystick?, pX: Float, pY: Float) {
                handleJoystickCancelMission(pX, pY)
                if (joysticksVisible && currentControlMode == ControlMode.FLY) {
                    virtualStickManager.rightStick.horizontalPosition = (pX * 660.0f * flightSensitivity).toInt()
                    virtualStickManager.rightStick.verticalPosition = (pY * 660.0f * flightSensitivity).toInt()
                } else if (joysticksVisible && currentControlMode == ControlMode.CAM) {
                    val invertY = if (invertVertical) -1.0 else 1.0
                    val invertX = if (invertHorizontal) -1.0 else 1.0
                    val pitchSpeed = -pY * swipeSensitivity * invertY * 25.0
                    val yawSpeed = pX * swipeSensitivity * invertX * 25.0
                    
                    val rotateKey = KeyTools.createKey(GimbalKey.KeyRotateBySpeed, ComponentIndexType.LEFT_OR_MAIN)
                    val rotation = GimbalSpeedRotation(pitchSpeed.toDouble(), yawSpeed.toDouble(), 0.0, CtrlInfo())
                    KeyManager.getInstance().performAction(rotateKey, rotation, null)
                }
            }
        })
    }

    private fun togglePairing() {
        if (!SDKManager.getInstance().isRegistered) {
            showToast("SDK not registered yet!")
            return
        }

        val pairingStatusKey = KeyTools.createKey(RemoteControllerKey.KeyPairingStatus)
        KeyManager.getInstance().getValue(pairingStatusKey, object : CommonCallbacks.CompletionCallbackWithParam<PairingState> {
            override fun onSuccess(state: PairingState?) {
                if (state == PairingState.PAIRING) {
                    stopPairing()
                } else {
                    startPairing()
                }
            }
            override fun onFailure(error: IDJIError) {
                log("Failed to get pairing status: ${error.errorCode()}")
                startPairing()
            }
        })
    }

    private fun startPairing() {
        val pairKey = KeyTools.createKey(RemoteControllerKey.KeyRequestPairing)
        KeyManager.getInstance().performAction(pairKey, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
            override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                log("Pairing started")
                showToast("Pairing Started")
                runOnUiThread { btnPair?.text = "[ STOP LINKING ]" }
            }
            override fun onFailure(error: IDJIError) {
                log("Pairing failed: ${error.errorCode()}")
                showToast("Pairing Failed")
            }
        })
    }

    private fun stopPairing() {
        val stopKey = KeyTools.createKey(RemoteControllerKey.KeyStopPairing)
        KeyManager.getInstance().performAction(stopKey, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
            override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                log("Pairing stopped")
                showToast("Pairing Stopped")
                runOnUiThread { btnPair?.text = "[ INITIATE RC LINKING ]" }
            }
            override fun onFailure(error: IDJIError) {
                log("Stop pairing failed")
            }
        })
    }
    
    private fun loadConfig() {
        swipeSensitivity = sharedPrefs.getFloat("swipeSensitivity", 0.2f)
        flightSensitivity = sharedPrefs.getFloat("flightSensitivity", 1.0f)
        invertVertical = sharedPrefs.getBoolean("invertVertical", false)
        invertHorizontal = sharedPrefs.getBoolean("invertHorizontal", false)
        rthAltitude = sharedPrefs.getInt("rthAltitude", 100)
        obstacleAction = sharedPrefs.getInt("obstacleAction", 0)
        signalLossAction = sharedPrefs.getInt("signalLossAction", 0)
        radarEnabled = sharedPrefs.getBoolean("radarEnabled", true)
        radarMaxDistance = sharedPrefs.getFloat("radarMaxDistance", 10.0f).toDouble()
    }

    private fun saveConfig() {
        sharedPrefs.edit().apply {
            putFloat("swipeSensitivity", swipeSensitivity)
            putFloat("flightSensitivity", flightSensitivity)
            putBoolean("invertVertical", invertVertical)
            putBoolean("invertHorizontal", invertHorizontal)
            putInt("rthAltitude", rthAltitude)
            putInt("obstacleAction", obstacleAction)
            putInt("signalLossAction", signalLossAction)
            putBoolean("radarEnabled", radarEnabled)
            putFloat("radarMaxDistance", radarMaxDistance.toFloat())
            apply()
        }
    }

    private fun updateDroneLocationOnMap(lat: Double, lon: Double) {
        droneMarker?.let {
            it.position = GeoPoint(lat, lon)
            if (it !in mapView.overlays) {
                mapView.overlays.add(it)
            }
            updateHeadingLine()
            
            if (!hasCenteredMap) {
                hasCenteredMap = true
                mapView.controller.animateTo(GeoPoint(lat, lon))
                mapView.controller.setZoom(17.0)
            }
            
            // NOTE: Waypoint arrival detection is handled exclusively by the Virtual Stick Loop
            // to prevent race conditions from dual removal systems (BUG-03 fix)
            
            // H-04: debounce to max 2Hz - avoids 10Hz full overlay rebuild on every GPS update
            mapRedrawRunnable?.let { mapRedrawHandler.removeCallbacks(it) }
            val r = Runnable { updateFlightPathLine(); mapView.invalidate() }
            mapRedrawRunnable = r
            mapRedrawHandler.postDelayed(r, 500)
            
            // H-10: Real-time Geofencing (NFZ) warning
            if (::alertBlock.isInitialized) {
                var insideNfz = false
                val droneGeoPoint = GeoPoint(lat, lon)
                mapView.overlays.forEach { overlay ->
                    if (overlay is org.osmdroid.views.overlay.Polygon) {
                        val color = overlay.fillPaint.color
                        val isRed = color == android.graphics.Color.argb(75, 255, 0, 0)
                        if (isRed && isPointInPolygon(droneGeoPoint, overlay)) {
                            insideNfz = true
                        }
                    }
                }
                if (insideNfz) {
                    if (alertBlock.visibility != View.VISIBLE) {
                        alertBlock.visibility = View.VISIBLE
                        startBlinkingAnimation(alertBlock)
                    }
                } else {
                    if (alertBlock.visibility == View.VISIBLE && alertBlock.animation != null) {
                        alertBlock.visibility = View.GONE
                        alertBlock.clearAnimation()
                    }
                }
            }
        }
    }
    
    private fun updateHomeMarkerOnMap(lat: Double, lon: Double) {
        homeMapMarker?.let {
            it.position = org.osmdroid.util.GeoPoint(lat, lon)
            if (it !in mapView.overlays) {
                mapView.overlays.add(it)
            }
            mapView.invalidate()
        }
    }
    
    private fun interpolateCatmullRom(
        p0: GeoPoint,
        p1: GeoPoint,
        p2: GeoPoint,
        p3: GeoPoint,
        steps: Int = 15
    ): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val t2 = t * t
            val t3 = t2 * t
            
            val f1 = -0.5 * t3 + t2 - 0.5 * t
            val f2 = 1.5 * t3 - 2.5 * t2 + 1.0
            val f3 = -1.5 * t3 + 2.0 * t2 + 0.5 * t
            val f4 = 0.5 * t3 - 0.5 * t2
            
            val lat = p0.latitude * f1 + p1.latitude * f2 + p2.latitude * f3 + p3.latitude * f4
            val lon = p0.longitude * f1 + p1.longitude * f2 + p2.longitude * f3 + p3.longitude * f4
            
            points.add(GeoPoint(lat, lon))
        }
        return points
    }

    private fun getDetailedPathPoints(startPoint: GeoPoint, wps: List<TacticalWaypoint>): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        points.add(startPoint)
        
        var prev = startPoint
        wps.forEachIndexed { index, wp ->
            val method = wp.movementMethod.uppercase()
            if (method == "ORBIT") {
                val radius = wp.orbitRadius
                val loops = wp.orbitLoops
                
                // Orbit starts at angle 0 (North offset)
                val startLat = wp.geoPoint.latitude + (radius / 111320.0)
                val startLon = wp.geoPoint.longitude
                val startOrbitPt = GeoPoint(startLat, startLon)
                
                // Line from prev to start of orbit
                points.add(startOrbitPt)
                
                // Circular loops
                val stepsPerLoop = 36
                for (l in 0 until loops) {
                    for (i in 1..stepsPerLoop) {
                        val angle = (i * 360.0 / stepsPerLoop) * Math.PI / 180.0
                        val lat = wp.geoPoint.latitude + (radius / 111320.0) * Math.cos(angle)
                        val lon = wp.geoPoint.longitude + (radius / (111320.0 * Math.cos(wp.geoPoint.latitude * Math.PI / 180.0))) * Math.sin(angle)
                        points.add(GeoPoint(lat, lon))
                    }
                }
                prev = startOrbitPt
            } else if (method == "SPLINE") {
                // Get control points P0, P1, P2, P3
                val p1 = prev
                val p2 = wp.geoPoint
                
                // P0: previous point's predecessor
                val p0 = if (index > 0) {
                    val prevWp = wps[index - 1]
                    if (prevWp.movementMethod.uppercase() == "ORBIT") {
                        prevWp.geoPoint
                    } else {
                        if (index > 1) wps[index - 2].geoPoint else startPoint
                    }
                } else {
                    startPoint
                }
                
                // P3: next point
                val p3 = if (index < wps.size - 1) {
                    wps[index + 1].geoPoint
                } else {
                    GeoPoint(p2.latitude + (p2.latitude - p1.latitude), p2.longitude + (p2.longitude - p1.longitude))
                }
                
                val interpolated = interpolateCatmullRom(p0, p1, p2, p3, steps = 15)
                points.addAll(interpolated)
                prev = p2
            } else {
                // LINEAR or DEFAULT
                points.add(wp.geoPoint)
                prev = wp.geoPoint
            }
        }
        return points
    }

    private fun updateFlightPathLine() {
        flightPathPolyline?.let { mapView.overlays.remove(it) }
        // Remove any existing orbit circle overlays
        orbitCircleOverlays.forEach { mapView.overlays.remove(it) }
        orbitCircleOverlays.clear()

        if (tacticalWaypoints.isEmpty()) {
            mapView.invalidate()
            return
        }

        val startPt = if (!droneLat.isNaN() && !droneLon.isNaN()) {
            GeoPoint(droneLat, droneLon)
        } else {
            tacticalWaypoints.first().geoPoint
        }

        val points = getDetailedPathPoints(startPt, tacticalWaypoints)

        flightPathPolyline = org.osmdroid.views.overlay.Polyline(mapView)
        flightPathPolyline?.setPoints(points)
        flightPathPolyline?.outlinePaint?.color = android.graphics.Color.WHITE
        flightPathPolyline?.outlinePaint?.strokeWidth = 5.0f
        flightPathPolyline?.outlinePaint?.pathEffect = android.graphics.DashPathEffect(floatArrayOf(15f, 15f), 0f)
        mapView.overlays.add(0, flightPathPolyline)

        // Draw a distinct yellow circle for every ORBIT waypoint
        tacticalWaypoints.filter { it.movementMethod.equals("orbit", ignoreCase = true) }.forEach { wp ->
            val circle = buildOrbitCircleOverlay(wp.geoPoint, wp.orbitRadius)
            orbitCircleOverlays.add(circle)
            mapView.overlays.add(circle)
        }

        mapView.invalidate()
    }

    /** Builds a closed yellow Polyline circle around [center] with [radiusMeters]. */
    private fun buildOrbitCircleOverlay(center: GeoPoint, radiusMeters: Double): org.osmdroid.views.overlay.Polyline {
        val steps = 72
        val circlePts = ArrayList<GeoPoint>(steps + 1)
        val latOffset = radiusMeters / 111320.0
        val cosLat = Math.max(0.0001, Math.abs(Math.cos(Math.toRadians(center.latitude))))
        val lonOffset = radiusMeters / (111320.0 * cosLat)
        for (i in 0..steps) {
            val angle = Math.toRadians((i * 360.0 / steps))
            circlePts.add(GeoPoint(
                center.latitude  + latOffset * Math.cos(angle),
                center.longitude + lonOffset * Math.sin(angle)
            ))
        }
        val poly = org.osmdroid.views.overlay.Polyline(mapView)
        poly.setPoints(circlePts)
        poly.outlinePaint.color = android.graphics.Color.YELLOW
        poly.outlinePaint.strokeWidth = 3.5f
        poly.outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
        return poly
    }

    private fun startBlinkingAnimation(view: View) {
        val anim = android.view.animation.AlphaAnimation(0.0f, 1.0f)
        anim.duration = 500
        anim.startOffset = 20
        anim.repeatMode = android.view.animation.Animation.REVERSE
        anim.repeatCount = android.view.animation.Animation.INFINITE
        view.startAnimation(anim)
    }

    private fun monitorTelemetry() {
        val tempKey = KeyTools.createKey(dji.sdk.keyvalue.key.BatteryKey.KeyBatteryTemperature)
        KeyManager.getInstance().listen(tempKey, this) { _, temp: Double? ->
            temp?.let {
                runOnUiThread {
                    if (it >= 55.0) { 
                        if (alertThermal.visibility != View.VISIBLE) {
                            alertThermal.visibility = View.VISIBLE
                            startBlinkingAnimation(alertThermal)
                        }
                    } else {
                        alertThermal.visibility = View.GONE
                        alertThermal.clearAnimation()
                    }
                }
            }
        }

        // Satellite count listener
        val satsKey = KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)
        KeyManager.getInstance().listen(satsKey, this) { _, count: Int? ->
            if (count != null) {
                satelliteCount = count
            }
        }

        // 3D Velocity Vector Listener (IMU / Downward VPS Optical Flow / Barometer)
        val velKey = KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity)
        KeyManager.getInstance().listen(velKey, this) { _, vel ->
            if (vel != null) {
                curVx = (vel.x ?: 0).toDouble() // North m/s
                curVy = (vel.y ?: 0).toDouble() // East m/s
                curVz = (vel.z ?: 0).toDouble() // Up m/s
                droneVx = curVx
                droneVy = curVy
                droneVz = curVz
                droneSpeed = Math.sqrt(curVx * curVx + curVy * curVy)

                val now = System.currentTimeMillis()
                val dtSec = if (deadReckoningLastTime > 0) (now - deadReckoningLastTime) / 1000.0 else 0.0
                deadReckoningLastTime = now

                // If GPS is lost or sat count < 6 mid-flight -> IMU + VPS Dead Reckoning Navigation Active
                val timeSinceLastGps = now - lastGpsFixTime
                val gpsLost = (satelliteCount < 6 || timeSinceLastGps > 1500) && isFlying

                if (gpsLost && dtSec > 0.005 && dtSec < 1.0) {
                    if (!droneLat.isNaN() && !droneLon.isNaN()) {
                        isDeadReckoningActive = true

                        // WGS-84 Geodesic Dead Reckoning displacement math
                        val deltaNorthMeters = curVx * dtSec
                        val deltaEastMeters = curVy * dtSec
                        val deltaUpMeters = curVz * dtSec

                        val latRad = Math.toRadians(droneLat)
                        val deltaLatDeg = deltaNorthMeters / 111132.92
                        val deltaLonDeg = deltaEastMeters / (111412.84 * Math.cos(latRad))

                        droneLat += deltaLatDeg
                        droneLon += deltaLonDeg
                        droneAlt += deltaUpMeters

                        runOnUiThread {
                            tvCoords.text = String.format(java.util.Locale.US, "NAV: %.5f, %.5f [DR IMU+VPS]", droneLat, droneLon)
                            tvCoords.setTextColor(android.graphics.Color.parseColor("#FFB300")) // Amber warning
                            updateDroneLocationOnMap(droneLat, droneLon)
                        }
                        updateARHomePoint()
                    }
                } else if (!gpsLost) {
                    isDeadReckoningActive = false
                }
            }
        }

        val locationKey = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation)
        KeyManager.getInstance().listen(locationKey, this) { _, newValue ->
            newValue?.let {
                if (!it.latitude.isNaN() && !it.longitude.isNaN() && it.latitude != 0.0 && it.longitude != 0.0) {
                    lastGpsFixTime = System.currentTimeMillis()
                    if (!usingRTK) {
                        droneLat = it.latitude
                        droneLon = it.longitude
                        isDeadReckoningActive = false
                        runOnUiThread { 
                            tvCoords.text = String.format(java.util.Locale.US, "NAV: %.5f, %.5f [GPS %dS]", it.latitude, it.longitude, satelliteCount) 
                            tvCoords.setTextColor(android.graphics.Color.WHITE)
                            updateDroneLocationOnMap(it.latitude, it.longitude)
                        }
                        updateARHomePoint()
                    }
                }
            }
        }
                    
                    try {
                        val payload = org.json.JSONObject()
                        
                        if (cachedDroneSn == "UNKNOWN") {
                            val snKey = KeyTools.createKey(dji.sdk.keyvalue.key.FlightControllerKey.KeySerialNumber)
                            cachedDroneSn = dji.v5.manager.KeyManager.getInstance().getValue(snKey) ?: "UNKNOWN"
                        }
                        
                        if (cachedDroneType == "UNKNOWN") {
                            val typeKey = KeyTools.createKey(dji.sdk.keyvalue.key.ProductKey.KeyProductType)
                            cachedDroneType = dji.v5.manager.KeyManager.getInstance().getValue(typeKey)?.toString() ?: "UNKNOWN"
                        }
                        
                        val currentDroneId = if (cachedDroneSn != "UNKNOWN") "drone_$cachedDroneSn" else "drone_alpha_01"

                        payload.put("drone_id", currentDroneId)
                        payload.put("timestamp", System.currentTimeMillis())
                        
                        val loc = org.json.JSONObject()
                        loc.put("latitude", droneLat)
                        loc.put("longitude", droneLon)
                        loc.put("altitude_m", droneAlt)
                        payload.put("location", loc)
                        
                        val flightStatus = org.json.JSONObject()
                        flightStatus.put("heading_deg", droneYaw)
                        flightStatus.put("speed_mps", droneSpeed)
                        flightStatus.put("velocity_x", droneVx)
                        flightStatus.put("velocity_y", droneVy)
                        flightStatus.put("velocity_z", droneVz)
                        flightStatus.put("is_flying", isFlying)
                        flightStatus.put("is_mission_executing", isMissionExecuting)
                        val groundState = when {
                            !isFlying -> "LANDED"
                            droneFlightMode.contains("LAND", ignoreCase = true) -> "LANDING_IN_PROGRESS"
                            droneFlightMode.contains("TAKEOFF", ignoreCase = true) -> "TAKEOFF_IN_PROGRESS"
                            else -> "IN_FLIGHT"
                        }
                        flightStatus.put("ground_state", groundState)
                        val extendedState = when {
                            !isFlying -> "ON_GROUND"
                            droneFlightMode.contains("TAKEOFF", ignoreCase = true) -> "TAKING_OFF"
                            droneFlightMode.contains("LAND", ignoreCase = true) -> "LANDING"
                            else -> "IN_AIR"
                        }
                        flightStatus.put("extended_state", extendedState)
                        payload.put("flight_status", flightStatus)
                        
                        val wpArray = org.json.JSONArray()
                        tacticalWaypoints.forEach { wp ->
                            val wpObj = org.json.JSONObject()
                            wpObj.put("lat", wp.geoPoint.latitude)
                            wpObj.put("lng", wp.geoPoint.longitude)
                            wpObj.put("alt", wp.altitude)
                            wpObj.put("speed", wp.speed)
                            wpArray.put(wpObj)
                        }
                        payload.put("active_mission", wpArray)
                        
                        val hw = org.json.JSONObject()
                        hw.put("battery_percent", droneBattery)
                        val cellVoltArray = org.json.JSONArray()
                        cellVoltagesList.forEach { cellVoltArray.put(it) }
                        hw.put("cell_voltages", cellVoltArray)
                        hw.put("rc_battery_percent", rcBattery)
                        hw.put("rc_signal_strength", droneSignal)
                        hw.put("gps_satellites", droneSatellites)
                        if (!usingRTK) {
                            droneGpsFixType = when {
                                droneSatellites >= 6 -> "GPS_3D"
                                droneSatellites > 0 -> "GPS_2D"
                                else -> "NO_GPS"
                            }
                        }
                        hw.put("gps_fix_type", droneGpsFixType)
                        hw.put("rtk_supported", rtkSupported)
                        
                        val healthInfos = dji.v5.manager.diagnostic.DeviceHealthManager.getInstance().currentDJIDeviceHealthInfos
                        val healthWarnings = org.json.JSONArray()
                        for (info in healthInfos) {
                            if (info.warningLevel() == dji.v5.manager.diagnostic.WarningLevel.WARNING || 
                                info.warningLevel() == dji.v5.manager.diagnostic.WarningLevel.SERIOUS_WARNING) {
                                val healthObj = org.json.JSONObject()
                                healthObj.put("title", info.title())
                                healthObj.put("description", info.description())
                                healthObj.put("warning_level", info.warningLevel().name)
                                healthWarnings.put(healthObj)
                            }
                        }
                        hw.put("health_warnings", healthWarnings)
                        
                        hw.put("signal_quality_percent", droneSignal)
                        hw.put("uplink_signal_quality_percent", upLinkQuality)
                        hw.put("uplink_quality_percent", upLinkQuality)
                        hw.put("downlink_signal_quality_percent", downLinkQuality)
                        hw.put("downlink_quality_percent", downLinkQuality)
                        hw.put("drone_type", cachedDroneType)
                        hw.put("drone_sn", cachedDroneSn)
                        payload.put("hardware", hw)

                        val batteryObj = org.json.JSONObject()
                        batteryObj.put("percentage", droneBattery)
                        val cellsArray = org.json.JSONArray()
                        cellVoltagesList.forEach { cellsArray.put(it) }
                        batteryObj.put("cells", cellsArray)
                        payload.put("battery", batteryObj)

                        val gimbalObj = org.json.JSONObject()
                        gimbalObj.put("pitch", gimbalPitch)
                        gimbalObj.put("roll", gimbalRoll)
                        gimbalObj.put("yaw", gimbalYaw)
                        payload.put("gimbal", gimbalObj)

                        val activeTask = com.dji.recreate2.task.DroneTaskManager.activeTask
                        if (activeTask != null) {
                            payload.put("taskId", activeTask.taskId)
                            payload.put("taskName", activeTask.taskName)
                            payload.put("taskStatus", activeTask.status)
                            payload.put("queuedTasksCount", com.dji.recreate2.task.DroneTaskManager.queuedCount)
                        } else {
                            payload.put("taskId", org.json.JSONObject.NULL)
                            payload.put("queuedTasksCount", com.dji.recreate2.task.DroneTaskManager.queuedCount)
                        }

                        payload.put("gpsDeniedMode", com.dji.recreate2.flight.ConfinedSpaceFlightManager.isGpsDeniedModeEnabled)
                        payload.put("confinedSpaceMode", com.dji.recreate2.flight.ConfinedSpaceFlightManager.isConfinedSpaceModeEnabled)
                        payload.put("obstacleBrakeDistanceMeters", com.dji.recreate2.flight.ConfinedSpaceFlightManager.obstacleBrakeDistanceMeters)
                        
                        mqttService.updateDroneId(currentDroneId)
                        mqttService.publishTelemetry(jsonPayload = payload.toString())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

            KeyManager.getInstance().setValue(KeyTools.createKey(RtkMobileStationKey.KeyRTKEnable), true, null)
            rtkLocationListener = dji.v5.manager.aircraft.rtk.RTKLocationInfoListener { rtkInfo ->
                val solution = rtkInfo.rtkLocation?.positioningSolution
            if (solution != null && solution != RTKPositioningSolution.NONE) {
                rtkSupported = true
                droneGpsFixType = when (solution) {
                    RTKPositioningSolution.FIXED_POINT -> "RTK_FIXED"
                    RTKPositioningSolution.FLOAT -> "RTK_FLOAT"
                    RTKPositioningSolution.SINGLE_POINT -> "GPS_3D"
                    else -> "GPS_3D"
                }
            }
            if (solution == RTKPositioningSolution.FIXED_POINT) {
                val lat = rtkInfo.rtkLocation?.mobileStationLocation?.latitude
                val lon = rtkInfo.rtkLocation?.mobileStationLocation?.longitude
                if (lat != null && lon != null && !lat.isNaN() && !lon.isNaN()) {
                    usingRTK = true
                    droneLat = lat
                    droneLon = lon
                    runOnUiThread { 
                        tvCoords.text = String.format("NAV(RTK): %.5f, %.5f", lat, lon)
                        tvCoords.setTextColor(android.graphics.Color.CYAN)
                        updateDroneLocationOnMap(lat, lon)
                    }
                    updateARHomePoint()
                }
            } else {
                usingRTK = false
            }
        }
        RTKCenter.getInstance().addRTKLocationInfoListener(rtkLocationListener!!)
        
        val homeLocationKey = KeyTools.createKey(FlightControllerKey.KeyHomeLocation)
        KeyManager.getInstance().listen(homeLocationKey, this) { _, newValue ->
            newValue?.let {
                homeLat = it.latitude
                homeLon = it.longitude
                runOnUiThread { 
                    updateHomeMarkerOnMap(it.latitude, it.longitude)
                    rthMarker?.position = GeoPoint(it.latitude, it.longitude)
                    if (rthMarker !in mapView.overlays) {
                        mapView.overlays.add(rthMarker)
                    }
                    mapView.invalidate()
                }
                updateARHomePoint()
            }
        }
        
        val altitudeKey = KeyTools.createKey(FlightControllerKey.KeyAltitude)
        KeyManager.getInstance().listen(altitudeKey, this) { _, newValue: Double? ->
            newValue?.let { 
                droneAlt = it
                runOnUiThread { tvAltitude.text = String.format("%05.1fM", it) } 
                updateARHomePoint()
            }
        }

        val batteryKey = KeyTools.createKey(dji.sdk.keyvalue.key.BatteryKey.KeyChargeRemainingInPercent)
        KeyManager.getInstance().listen(batteryKey, this) { _, newValue: Int? ->
            newValue?.let { 
                droneBattery = it
                updateSituationLighting()
                runOnUiThread { 
                    tvBattery.text = String.format("%02d%%", it) 
                    
                    if (it <= 30) {
                        tvBattery.setTextColor(android.graphics.Color.RED)
                    } else {
                        tvBattery.setTextColor(android.graphics.Color.GREEN)
                    }
                    
                    if (it <= 30) {
                        if (alertLow.visibility != View.VISIBLE) {
                            alertLow.visibility = View.VISIBLE
                            startBlinkingAnimation(alertLow)
                        }
                    } else {
                        alertLow.visibility = View.GONE
                        alertLow.clearAnimation()
                    }
                } 
            }
        }
        
        val productTypeKey = KeyTools.createKey(dji.sdk.keyvalue.key.ProductKey.KeyProductType)
        KeyManager.getInstance().listen(productTypeKey, this) { _, newValue ->
            newValue?.let { productType ->
                PayloadDetectionManager.detectCapabilities()
                val name = productType.name.replace("_", " ").replace("DJI", "").trim()
                runOnUiThread {
                    val tvDroneModel = findViewById<TextView?>(R.id.tvDroneModel)
                    if (productType.name == "UNKNOWN") {
                        currentDroneModelStr = "MOD: UNKNOWN"
                    } else {
                        currentDroneModelStr = "MOD: $name"
                    }
                    tvDroneModel?.text = currentDroneModelStr
                    dTvDroneModel?.text = currentDroneModelStr
                }
            }
        }

        val velocityKey = KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity)
        KeyManager.getInstance().listen(velocityKey, this) { _, newValue ->
            newValue?.let {
                droneVx = it.x
                droneVy = it.y
                droneVz = it.z
                val speed = Math.sqrt(it.x * it.x + it.y * it.y + it.z * it.z)
                droneSpeed = speed
                runOnUiThread { tvSpeed.text = String.format("%04.1fM/S", speed) }
            }
        }

        val attitudeKey = KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude)
        val tvHeading = findViewById<TextView>(R.id.tvHeading)
        KeyManager.getInstance().listen(attitudeKey, this) { _, newValue ->
            newValue?.let { 
                droneYaw = it.yaw
                val yaw = if (it.yaw < 0) it.yaw + 360 else it.yaw
                runOnUiThread { 
                    compassView.setHeading(yaw.toFloat()) 
                    tvHeading.text = String.format("HDG: %03d°", yaw.toInt())
                    droneMarker?.rotation = yaw.toFloat()
                    updateHeadingLine()
                } 
                updateARHomePoint()
            }
        }

        val gpsKey = KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)
        KeyManager.getInstance().listen(gpsKey, this) { _, newValue: Int? ->
            newValue?.let { 
                droneSatellites = it
                runOnUiThread { tvGps.text = String.format("SAT: %02d", it) } 
            }
        }

        val isFlyingKey = KeyTools.createKey(FlightControllerKey.KeyIsFlying)
        KeyManager.getInstance().listen(isFlyingKey, this) { _, newValue: Boolean? ->
            newValue?.let { isFlying = it }
        }

        healthChangeListener = dji.v5.manager.diagnostic.DJIDeviceHealthInfoChangeListener { healthInfos ->
            for (info in healthInfos) {
                if (info.warningLevel() == dji.v5.manager.diagnostic.WarningLevel.WARNING || 
                    info.warningLevel() == dji.v5.manager.diagnostic.WarningLevel.SERIOUS_WARNING) {
                    val msg = "⚠️ Health Alert: ${info.title()} - ${info.description()}"
                    log(msg)
                    if (::mqttService.isInitialized) {
                        try {
                            val logObj = org.json.JSONObject()
                            logObj.put("event", "DIAGNOSTIC_WARNING")
                            logObj.put("title", info.title())
                            logObj.put("description", info.description())
                            logObj.put("level", info.warningLevel().name)
                            mqttService.publishMission(jsonPayload = logObj.toString())
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
        dji.v5.manager.diagnostic.DeviceHealthManager.getInstance().addDJIDeviceHealthInfoChangeListener(healthChangeListener!!)

        val signalKey = KeyTools.createKey(dji.sdk.keyvalue.key.AirLinkKey.KeySignalQuality)
        KeyManager.getInstance().listen(signalKey, this) { _, newValue: Int? ->
            newValue?.let { 
                droneSignal = it
                runOnUiThread { 
                    val tvSignal = findViewById<TextView>(R.id.tvSignal)
                    tvSignal.text = String.format("%02d%%", it) 
                    
                    if (it < 15 && isMissionExecuting && isFlying) { // C-04: guard against RTH when drone is on the ground
                        cancelActiveMission()
                        
                        if (!homeLat.isNaN() && !homeLon.isNaN()) {
                            executeReturnToHome()
                            showToast("SIGNAL LOST! RTH INITIATED")
                        } else {
                            showToast("SIGNAL LOST! NO HOME POINT FOR RTH")
                        }
                    }

                    if (it < 30) {
                        tvSignal.setTextColor(android.graphics.Color.RED)
                    } else if (it < 60) {
                        tvSignal.setTextColor(android.graphics.Color.YELLOW)
                    } else {
                        tvSignal.setTextColor(android.graphics.Color.parseColor("#00FF00"))
                    }
                } 
            }
        }

        val flightModeKey = KeyTools.createKey(FlightControllerKey.KeyFCFlightMode)
        KeyManager.getInstance().listen(flightModeKey, this) { _, newValue ->
            newValue?.let {
                droneFlightMode = it.name
                val modeName = it.name.uppercase(java.util.Locale.US)
                
                if (autoSensitivity) {
                    when (modeName) {
                        "CINE" -> flightSensitivity = 0.3f
                        "NORMAL" -> flightSensitivity = 1.0f
                        "SPORT" -> flightSensitivity = 2.0f
                    }
                }

                val displayChar = when {
                    modeName.contains("SPORT") -> "S"
                    modeName.contains("TRIPOD") || modeName.contains("CINE") -> "C"
                    else -> "N"
                }
                
                runOnUiThread { 
                    val btnFlightMode = findViewById<TextView>(R.id.btnFlightMode)
                    btnFlightMode.text = displayChar
                    if (displayChar == "S") {
                        btnFlightMode.setTextColor(android.graphics.Color.RED)
                    } else {
                        btnFlightMode.setTextColor(android.graphics.Color.GREEN)
                    }
                    
                    btnFlightMode.animate().alpha(1f).setDuration(200).start()
                    flightModeHideRunnable?.let {
                        flightModeHideHandler.removeCallbacks(it)
                        flightModeHideHandler.postDelayed(it, 5000)
                    }
                }
                
                if (displayChar == "S") {
                    radarEnabled = false
                    PerceptionManager.getInstance().setObstacleAvoidanceEnabled(false, dji.v5.manager.aircraft.perception.data.PerceptionDirection.HORIZONTAL, null)
                } else {
                    radarEnabled = true
                    PerceptionManager.getInstance().setObstacleAvoidanceEnabled(true, dji.v5.manager.aircraft.perception.data.PerceptionDirection.HORIZONTAL, null)
                }
            }
        }

        val gimbalAttitudeKey = KeyTools.createKey(GimbalKey.KeyGimbalAttitude, ComponentIndexType.LEFT_OR_MAIN)
        KeyManager.getInstance().listen(gimbalAttitudeKey, this) { _, newValue ->
            newValue?.let {
                gimbalPitch = it.pitch
                gimbalYaw = it.yaw
                gimbalRoll = it.roll
                runOnUiThread { 
                    tvGimbalStatus?.text = String.format("%02.0f", it.pitch) 
                }
                updateARHomePoint()
            }
        }
    }

    private fun updateARHomePoint() {
        if (homeLat.isNaN() || homeLon.isNaN() || droneLat.isNaN() || droneLon.isNaN() || droneAlt.isNaN()) {
            runOnUiThread { arHomePoint.visibility = View.GONE }
            return
        }
        
        val results = FloatArray(3)
        android.location.Location.distanceBetween(droneLat, droneLon, homeLat, homeLon, results)
        val distance = results[0]
        val bearing = results[1]
        
        if (distance < 5.0) {
            runOnUiThread { arHomePoint.visibility = View.GONE }
            return
        }

        var cameraYaw = droneYaw + gimbalYaw
        while (cameraYaw < -180) cameraYaw += 360
        while (cameraYaw > 180) cameraYaw -= 360
        
        var deltaYaw = bearing - cameraYaw
        while (deltaYaw < -180) deltaYaw += 360
        while (deltaYaw > 180) deltaYaw -= 360

        val targetPitch = Math.toDegrees(Math.atan2(-droneAlt, distance.toDouble()))
        val deltaPitch = targetPitch - gimbalPitch

        val hFov = cameraFov // L-03: use actual camera FOV instead of hardcoded value
        val vFov = hFov * (9.0 / 16.0) // L-03: derive vFov from aspect ratio

        runOnUiThread {
            val screenW = fpvSurface.width
            val screenH = fpvSurface.height
            if (screenW == 0 || screenH == 0) return@runOnUiThread

            if (Math.abs(deltaYaw) > hFov / 2 + 15 || Math.abs(deltaPitch) > vFov / 2 + 15) {
                arHomePoint.visibility = View.GONE
                return@runOnUiThread
            }

            val x = (screenW / 2) + (deltaYaw / (hFov / 2)) * (screenW / 2)
            val y = (screenH / 2) - (deltaPitch / (vFov / 2)) * (screenH / 2)

            arHomePoint.translationX = x.toFloat() - (arHomePoint.width / 2)
            arHomePoint.translationY = y.toFloat() - (arHomePoint.height / 2)
            arHomePoint.visibility = View.VISIBLE
        }
    }

    private var videoFeedBound = false

    private fun setupVideoFeed() {
        val streamManager = try {
            MediaDataCenter.getInstance().cameraStreamManager
        } catch (e: Exception) {
            log("CameraStreamManager unavailable: ${e.message}")
            return
        }
        
        if (streamManager == null) {
            log("CameraStreamManager is null - drone not connected")
            return
        }

        try {
            streamManager.enableStream(ComponentIndexType.LEFT_OR_MAIN, true)
            log("Camera stream enabled")
        } catch (e: Exception) {
            log("Camera stream enable error: ${e.message}")
        }

        fpvSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                log("Surface created. Binding video stream...")
                bindVideoStream(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                log("Surface changed: ${width}x${height}")
                if (videoFeedBound) {
                    bindVideoStream(holder.surface)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                log("Surface destroyed. Releasing video stream...")
                videoFeedBound = false
                streamManager.removeCameraStreamSurface(holder.surface)
            }
        })

        if (fpvSurface.holder.surface != null) {
            bindVideoStream(fpvSurface.holder.surface)
        }
    }

    private fun bindVideoStream(surface: android.view.Surface) {
        val streamManager = MediaDataCenter.getInstance().cameraStreamManager
        val w = if (fpvSurface.width > 0) fpvSurface.width else 1920
        val h = if (fpvSurface.height > 0) fpvSurface.height else 1080

        try {
            fpvSurface.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        } catch (e: Exception) {
            // Format set fallback
        }

        streamManager.putCameraStreamSurface(
            ComponentIndexType.LEFT_OR_MAIN,
            surface,
            w,
            h,
            ICameraStreamManager.ScaleType.CENTER_CROP
        )
        videoFeedBound = true
        log("Low-latency hardware FPV video stream bound (${w}x${h})")
    }

    private fun monitorConnectionStatus() {
        val aircraftConnectionKey = KeyTools.createKey(FlightControllerKey.KeyConnection)
        val tvDroneStatus = findViewById<TextView?>(R.id.tvDroneStatus)
        KeyManager.getInstance().listen(aircraftConnectionKey, this) { _, isConnected: Boolean? ->
            runOnUiThread {
                val conn = isConnected ?: false
                if (!conn) {
                    if (alertLoss.visibility != View.VISIBLE) {
                        alertLoss.visibility = View.VISIBLE
                        startBlinkingAnimation(alertLoss)
                    }
                } else {
                    alertLoss.visibility = View.GONE
                    alertLoss.clearAnimation()
                }

                if (conn && !droneConnected) {
                    droneConnected = true
                    currentDroneStatusStr = "DRN: ONLINE"
                    currentDroneStatusColor = android.graphics.Color.GREEN
                    tvDroneStatus?.text = currentDroneStatusStr
                    tvDroneStatus?.setTextColor(currentDroneStatusColor)
                    dTvDroneStatus?.text = currentDroneStatusStr
                    dTvDroneStatus?.setTextColor(currentDroneStatusColor)
                    log("DRONE CONNECTED")
                    
                    // Resolve drone serial number and product type immediately on connection (Fix for no-GPS/indoor C2 registration)
                    Thread {
                        try {
                            Thread.sleep(500)
                            val snKey = KeyTools.createKey(dji.sdk.keyvalue.key.FlightControllerKey.KeySerialNumber)
                            val serial = dji.v5.manager.KeyManager.getInstance().getValue(snKey)
                            if (serial != null) {
                                cachedDroneSn = serial
                                val currentDroneId = "drone_$cachedDroneSn"
                                if (::mqttService.isInitialized) {
                                    mqttService.updateDroneId(currentDroneId)
                                    log("Drone SN resolved on connection: $cachedDroneSn")
                                }
                            }
                            val typeKey = KeyTools.createKey(dji.sdk.keyvalue.key.ProductKey.KeyProductType)
                            val type = dji.v5.manager.KeyManager.getInstance().getValue(typeKey)
                            if (type != null) {
                                cachedDroneType = type.toString()
                                log("Drone Type resolved on connection: $cachedDroneType")
                            }
                            val rtkKey = KeyTools.createKey(RtkMobileStationKey.KeyRTKEnable)
                            rtkSupported = dji.v5.manager.KeyManager.getInstance().getValue(rtkKey) != null
                            log("Drone RTK Support detected: $rtkSupported")
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to resolve drone Sn/Type on connection: ${e.message}")
                        }
                    }.start()
                } else if (!conn && droneConnected) {
                    droneConnected = false
                    currentDroneStatusStr = "DRN: OFFLINE"
                    currentDroneStatusColor = android.graphics.Color.RED
                    tvDroneStatus?.text = currentDroneStatusStr
                    tvDroneStatus?.setTextColor(currentDroneStatusColor)
                    dTvDroneStatus?.text = currentDroneStatusStr
                    dTvDroneStatus?.setTextColor(currentDroneStatusColor)
                    log("DRONE DISCONNECTED")
                }
            }
        }

        // --- AUTO-CONFIRM LANDING ---
        val confirmLandingNeededKey = KeyTools.createKey(FlightControllerKey.KeyIsLandingConfirmationNeeded)
        KeyManager.getInstance().listen(confirmLandingNeededKey, this) { _, isNeeded: Boolean? ->
            if (isNeeded == true) {
                runOnUiThread { 
                    log("⚠️ Landing Protection: Auto-Confirming Landing!") 
                    showToast("Bypassing Landing Protection. Landing!")
                }
                val confirmLandingKey = KeyTools.createKey(FlightControllerKey.KeyConfirmLanding)
                KeyManager.getInstance().performAction(confirmLandingKey, object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(p0: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                        runOnUiThread { log("✅ Auto-Landing Confirmed!") }
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        runOnUiThread { log("❌ Auto-Landing Failed: ${error.description()}") }
                    }
                })
            }
        }

        val rcConnectionKey = KeyTools.createKey(RemoteControllerKey.KeyConnection)
        val tvRCStatus = findViewById<TextView?>(R.id.tvRCStatus)
        KeyManager.getInstance().listen(rcConnectionKey, this) { _, isConnected: Boolean? ->
            runOnUiThread {
                if (isConnected == true && !rcConnected) {
                    rcConnected = true
                    currentRcStatusStr = "LNK: ONLINE"
                    currentRcStatusColor = android.graphics.Color.GREEN
                    tvRCStatus?.text = currentRcStatusStr
                    tvRCStatus?.setTextColor(currentRcStatusColor)
                    dTvRCStatus?.text = currentRcStatusStr
                    dTvRCStatus?.setTextColor(currentRcStatusColor)
                    log("RC CONNECTED")
                } else if (isConnected == false && rcConnected) {
                    rcConnected = false
                    currentRcStatusStr = "LNK: OFFLINE"
                    currentRcStatusColor = android.graphics.Color.RED
                    tvRCStatus?.text = currentRcStatusStr
                    tvRCStatus?.setTextColor(currentRcStatusColor)
                    dTvRCStatus?.text = currentRcStatusStr
                    dTvRCStatus?.setTextColor(currentRcStatusColor)
                    log("RC DISCONNECTED")
                }
            }
        }

        val motorsKey = KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn)
        val tvEngineStatus = findViewById<TextView?>(R.id.tvEngineStatus)
        KeyManager.getInstance().listen(motorsKey, this) { _, areMotorsOn: Boolean? ->
            isEngineOn = areMotorsOn == true
            runOnUiThread {
                if (areMotorsOn == true) {
                    currentEngineStatusStr = "ENG: ACTIVE"
                    currentEngineStatusColor = android.graphics.Color.GREEN
                    tvEngineStatus?.text = currentEngineStatusStr
                    tvEngineStatus?.setTextColor(currentEngineStatusColor)
                    dTvEngineStatus?.text = currentEngineStatusStr
                    dTvEngineStatus?.setTextColor(currentEngineStatusColor)
                } else {
                    currentEngineStatusStr = "ENG: INACTIVE"
                    currentEngineStatusColor = android.graphics.Color.parseColor("#00FF00")
                    tvEngineStatus?.text = currentEngineStatusStr
                    tvEngineStatus?.setTextColor(currentEngineStatusColor)
                    dTvEngineStatus?.text = currentEngineStatusStr
                    dTvEngineStatus?.setTextColor(currentEngineStatusColor)
                }
            }
        }

        val cellVoltagesKey = KeyTools.createKey(BatteryKey.KeyCellVoltages, 0)
        KeyManager.getInstance().listen(cellVoltagesKey, this) { _, newValue: List<Int>? ->
            newValue?.let { cellVoltagesList = it }
        }

        val upLinkQualityKey = KeyTools.createKey(AirLinkKey.KeyUpLinkQualityRaw)
        KeyManager.getInstance().listen(upLinkQualityKey, this) { _, newValue: Int? ->
            newValue?.let { upLinkQuality = it }
        }

        val downLinkQualityKey = KeyTools.createKey(AirLinkKey.KeyDownLinkQualityRaw)
        KeyManager.getInstance().listen(downLinkQualityKey, this) { _, newValue: Int? ->
            newValue?.let { downLinkQuality = it }
        }

        val compassCalKey = KeyTools.createKey(FlightControllerKey.KeyCompassCalibrationStatus)
        KeyManager.getInstance().listen(compassCalKey, this) { _, newValue: CompassCalibrationState? ->
            newValue?.let {
                if (::mqttService.isInitialized) {
                    try {
                        val logObj = org.json.JSONObject()
                        logObj.put("event", "COMPASS_CALIBRATION_STATUS")
                        logObj.put("state", it.name)
                        mqttService.publishMission(jsonPayload = logObj.toString())
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
        }
    }
    
    private fun centerGimbal() {
        val rotateKey = KeyTools.createKey(GimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN)
        val rotation = GimbalAngleRotation()
        rotation.mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
        rotation.pitch = 0.0
        rotation.roll = 0.0
        rotation.yaw = 0.0
        rotation.duration = 2.0
        KeyManager.getInstance().performAction(rotateKey, rotation, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
            override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                log("Gimbal Centered")
            }
            override fun onFailure(error: IDJIError) {
                log("Gimbal Center Failed: ${error.errorCode()}")
            }
        })
    }

    private fun resetZoom() {
        log("Resetting camera zoom ratio to 1.0x...")
        currentZoomRatio = 1.0
        val zoomKey = KeyTools.createCameraKey(CameraKey.KeyCameraZoomRatios, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_ZOOM)
        KeyManager.getInstance().setValue(zoomKey, 1.0, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                runOnUiThread { showToast("Camera Zoom Reset to 1.0x") }
                log("Camera Zoom Reset to 1.0x successfully.")
            }
            override fun onFailure(error: IDJIError) {
                // Fallback attempt without specifying lens type
                val zoomKey2 = KeyTools.createKey(CameraKey.KeyCameraZoomRatios)
                KeyManager.getInstance().setValue(zoomKey2, 1.0, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        runOnUiThread { showToast("Camera Zoom Reset to 1.0x") }
                        log("Camera Zoom Reset to 1.0x successfully.")
                    }
                    override fun onFailure(err2: IDJIError) {
                        runOnUiThread { showToast("Reset Zoom Failed: ${error.errorCode()}") }
                        log("Reset Zoom Failed: ${error.errorCode()} / ${err2.errorCode()}")
                    }
                })
            }
        })
    }

    private var objectTrackingOverlay: ObjectTrackingOverlayView? = null
    private var isObjectTrackingActive = false
    private var trackingLoopThread: Thread? = null
    private var lastTargetNormX = 0.5f
    private var lastTargetNormY = 0.5f
    private var lastTargetNormW = 0.2f
    private var lastTargetNormH = 0.2f

    private fun setupObjectTracking() {
        objectTrackingOverlay = findViewById(R.id.objectTrackingOverlay)
        objectTrackingOverlay?.isTouchSelectionEnabled = true

        objectTrackingOverlay?.onTargetLockedListener = { normX, normY, normW, normH ->
            lastTargetNormX = normX
            lastTargetNormY = normY
            lastTargetNormW = normW
            lastTargetNormH = normH
            triggerTapToFocus(normX.toDouble(), normY.toDouble())
            startOpticalObjectTracking(normX, normY, normW, normH)
        }

        objectTrackingOverlay?.onTargetUnlockedListener = {
            stopOpticalObjectTracking()
        }

        try {
            val perceptionManager = dji.v5.manager.aircraft.perception.PerceptionManager.getInstance()
            perceptionManager.addPerceptionInformationListener { info ->
                runOnUiThread {
                    if (info != null && !isObjectTrackingActive) {
                        val boxes = mutableListOf<DetectedObjectBox>()
                        objectTrackingOverlay?.updateDetectedObjects(boxes)
                    }
                }
            }
        } catch (e: Exception) {
            log("PerceptionManager listener setup: ${e.message}")
        }
    }

    private fun stopAllCameraTracking() {
        if (isObjectTrackingActive) {
            isObjectTrackingActive = false
            trackingLoopThread?.interrupt()
            trackingLoopThread = null
            com.dji.recreate2.tracking.CustomUnlimitedFollowEngine.stopHybridFollow()
        }
        if (isTgpGeoLockActive) {
            isTgpGeoLockActive = false
            tgpLockThread?.interrupt()
            tgpLockThread = null
            runOnUiThread {
                findViewById<TextView>(R.id.btnTgpLock)?.setTextColor(android.graphics.Color.YELLOW)
            }
        }
        val gimbalSpeedKey = KeyTools.createKey(GimbalKey.KeyRotateBySpeed, ComponentIndexType.LEFT_OR_MAIN)
        runOnUiThread {
            KeyManager.getInstance().performAction(gimbalSpeedKey, GimbalSpeedRotation(0.0, 0.0, 0.0, CtrlInfo()), null)
        }
    }

    private fun startOpticalObjectTracking(normX: Float, normY: Float, normW: Float, normH: Float) {
        if (isTgpGeoLockActive) {
            isTgpGeoLockActive = false
            tgpLockThread?.interrupt()
            tgpLockThread = null
            runOnUiThread {
                findViewById<TextView>(R.id.btnTgpLock)?.setTextColor(android.graphics.Color.YELLOW)
            }
        }

        isObjectTrackingActive = true
        com.dji.recreate2.tracking.CustomUnlimitedFollowEngine.startHybridFollow()
        com.dji.recreate2.tracking.CustomUnlimitedFollowEngine.configureOptimalActiveTrack(15.0, 8.0)
        com.dji.recreate2.tracking.CustomUnlimitedFollowEngine.updateTargetObservation(normX, normY)

        log("Started Optical Object Tracking & Hybrid Unlimited Follow on Target Box: center=($normX, $normY)")
        showToast("HYBRID UNLIMITED FOLLOW ACTIVE [APAS 54km/h]")

        trackingLoopThread?.interrupt()
        trackingLoopThread = Thread {
            try {
                val gimbalSpeedKey = KeyTools.createKey(GimbalKey.KeyRotateBySpeed, ComponentIndexType.LEFT_OR_MAIN)

                while (!Thread.currentThread().isInterrupted && isObjectTrackingActive) {
                    com.dji.recreate2.tracking.CustomUnlimitedFollowEngine.updateTargetObservation(lastTargetNormX, lastTargetNormY)

                    val errorX = lastTargetNormX - 0.5f
                    val errorY = 0.5f - lastTargetNormY

                    val maxDegPerSec = 24.0
                    val yawSpeed = (errorX * maxDegPerSec).coerceIn(-maxDegPerSec, maxDegPerSec)
                    val pitchSpeed = (errorY * maxDegPerSec).coerceIn(-maxDegPerSec, maxDegPerSec)

                    if (Math.abs(errorX) > 0.015f || Math.abs(errorY) > 0.015f) {
                        runOnUiThread {
                            KeyManager.getInstance().performAction(
                                gimbalSpeedKey,
                                GimbalSpeedRotation(pitchSpeed.toDouble(), yawSpeed.toDouble(), 0.0, CtrlInfo()),
                                null
                            )
                        }

                        // Physical Camera Optical Shift: Moves AR bounding box lock-step with camera rotation
                        val fovH = 60.0
                        val fovV = 45.0
                        val dt = 0.05
                        lastTargetNormX -= ((yawSpeed * dt) / fovH).toFloat()
                        lastTargetNormY += ((pitchSpeed * dt) / fovV).toFloat()
                        lastTargetNormX = lastTargetNormX.coerceIn(0.05f, 0.95f)
                        lastTargetNormY = lastTargetNormY.coerceIn(0.05f, 0.95f)

                        runOnUiThread {
                            objectTrackingOverlay?.updateTargetPosition(lastTargetNormX, lastTargetNormY)
                        }
                    } else {
                        runOnUiThread {
                            KeyManager.getInstance().performAction(
                                gimbalSpeedKey,
                                GimbalSpeedRotation(0.0, 0.0, 0.0, CtrlInfo()),
                                null
                            )
                        }
                    }

                    Thread.sleep(50)
                }
            } catch (e: InterruptedException) {
                // Interrupted
            }
        }.apply { start() }
    }

    private fun stopOpticalObjectTracking() {
        if (!isObjectTrackingActive) return
        isObjectTrackingActive = false
        trackingLoopThread?.interrupt()
        trackingLoopThread = null
        com.dji.recreate2.tracking.CustomUnlimitedFollowEngine.stopHybridFollow()

        val gimbalSpeedKey = KeyTools.createKey(GimbalKey.KeyRotateBySpeed, ComponentIndexType.LEFT_OR_MAIN)
        runOnUiThread {
            KeyManager.getInstance().performAction(gimbalSpeedKey, GimbalSpeedRotation(0.0, 0.0, 0.0, CtrlInfo()), null)
        }

        log("Stopped Optical Object Tracking & Hybrid Follow Engine.")
        showToast("LOCK: OBJECT UNLOCKED")
    }

    private var isTgpGeoLockActive = false
    private var tgpTargetLat = 0.0
    private var tgpTargetLon = 0.0
    private var tgpTargetAlt = 0.0
    private var tgpLockThread: Thread? = null

    private fun toggleTargetingPodLock(targetLat: Double? = null, targetLon: Double? = null, targetAlt: Double? = null) {
        if (isTgpGeoLockActive && targetLat == null) {
            stopAllCameraTracking()
            log("Targeting Pod Geo-Lock UNLOCKED")
            showToast("TGP GEO-LOCK: UNLOCKED")
            return
        }

        if (isObjectTrackingActive) {
            isObjectTrackingActive = false
            trackingLoopThread?.interrupt()
            trackingLoopThread = null
            objectTrackingOverlay?.unlockTarget()
        }

        val dLat = if (targetLat != null && targetLat != 0.0) targetLat else droneLat
        val dLon = if (targetLon != null && targetLon != 0.0) targetLon else droneLon
        val dAlt = if (targetAlt != null && targetAlt > 0.0) targetAlt else (droneAlt - 15.0).coerceAtLeast(0.0)

        if (dLat == 0.0 && dLon == 0.0) {
            showToast("⚠️ TGP Lock Error: Waiting for Drone GPS fix...")
            return
        }

        tgpTargetLat = dLat
        tgpTargetLon = dLon
        tgpTargetAlt = dAlt
        isTgpGeoLockActive = true

        log("Targeting Pod Geo-Lock ENGAGED on Ground Coordinate: ($tgpTargetLat, $tgpTargetLon, Alt: ${tgpTargetAlt}m)")
        showToast("TGP GEO-LOCK: ENGAGED [${String.format("%.5f", tgpTargetLat)}, ${String.format("%.5f", tgpTargetLon)}]")

        runOnUiThread {
            findViewById<TextView>(R.id.btnTgpLock)?.setTextColor(android.graphics.Color.parseColor("#00FF66"))
        }

        tgpLockThread?.interrupt()
        tgpLockThread = Thread {
            try {
                val gimbalAngleKey = KeyTools.createKey(GimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN)
                var lastSentPitch = -999.0
                var lastSentYaw = -999.0

                while (!Thread.currentThread().isInterrupted && isTgpGeoLockActive) {
                    val curLat = droneLat
                    val curLon = droneLon
                    val curAlt = droneAlt

                    if (curLat != 0.0 && curLon != 0.0) {
                        val dN = (tgpTargetLat - curLat) * 111132.92
                        val dE = (tgpTargetLon - curLon) * 111412.84 * Math.cos(Math.toRadians(curLat))
                        val dU = tgpTargetAlt - curAlt

                        val distanceHorizontal = Math.sqrt(dN * dN + dE * dE)
                        val targetBearingDeg = (Math.toDegrees(Math.atan2(dE, dN)) + 360.0) % 360.0
                        val targetPitchDeg = Math.toDegrees(Math.atan2(dU, distanceHorizontal))

                        val desiredPitch = targetPitchDeg.coerceIn(-88.0, 25.0)
                        val finalYaw = if (distanceHorizontal < 3.0) {
                            gimbalYaw
                        } else {
                            val relativeYaw = (targetBearingDeg - droneYaw + 360.0) % 360.0
                            if (relativeYaw > 180.0) relativeYaw - 360.0 else relativeYaw
                        }

                        // Optimization: Throttled execution only when pitch or yaw shifts by > 0.1°
                        if (Math.abs(desiredPitch - lastSentPitch) > 0.1 || Math.abs(finalYaw - lastSentYaw) > 0.1) {
                            lastSentPitch = desiredPitch
                            lastSentYaw = finalYaw

                            val rotation = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation()
                            rotation.mode = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode.ABSOLUTE_ANGLE
                            rotation.pitch = desiredPitch
                            rotation.yaw = finalYaw
                            rotation.roll = 0.0
                            rotation.duration = 0.1

                            runOnUiThread {
                                KeyManager.getInstance().performAction(gimbalAngleKey, rotation, null)
                            }
                        }
                    }

                    Thread.sleep(100)
                }
            } catch (e: InterruptedException) {
                // Interrupted
            }
        }.apply { start() }
    }

    private val gpsTagMapMarkers = mutableListOf<org.osmdroid.views.overlay.Marker>()

    private fun updateGpsTagsOnMap() {
        runOnUiThread {
            if (!::mapView.isInitialized) return@runOnUiThread

            for (m in gpsTagMapMarkers) {
                mapView.overlays.remove(m)
            }
            gpsTagMapMarkers.clear()

            val tags = GpsTaggingManager.getTags(this)
            for (tag in tags) {
                val marker = org.osmdroid.views.overlay.Marker(mapView).apply {
                    position = GeoPoint(tag.latitude, tag.longitude)
                    title = "${tag.id}: ${tag.name}"
                    snippet = "Lat: ${String.format("%.5f", tag.latitude)}, Lon: ${String.format("%.5f", tag.longitude)}\nAlt: ${String.format("%.1f", tag.altitude)}m [TGP/POI]"
                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)

                    val yellowDrawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(android.graphics.Color.parseColor("#FFFF00"))
                        setStroke(4, android.graphics.Color.BLACK)
                        setSize(36, 36)
                    }
                    icon = yellowDrawable

                    setOnMarkerClickListener { _, _ ->
                        showInfoWindow()
                        toggleTargetingPodLock(tag.latitude, tag.longitude, tag.altitude)
                        showToast("🎯 TGP Geo-Lock Engaged on Saved Tag: ${tag.id}")
                        true
                    }
                }
                mapView.overlays.add(marker)
                gpsTagMapMarkers.add(marker)
            }

            mapView.invalidate()
        }
    }

    private fun showGpsTaggingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_gps_tagging, null)
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))

        val containerGpsTags = dialogView.findViewById<android.widget.LinearLayout>(R.id.containerGpsTags)
        val btnTagCurrentPos = dialogView.findViewById<android.widget.Button>(R.id.btnTagCurrentPos)
        val btnTagTgpTarget = dialogView.findViewById<android.widget.Button>(R.id.btnTagTgpTarget)
        val btnClearAllTags = dialogView.findViewById<android.widget.Button>(R.id.btnClearAllTags)
        val btnCloseTagsDialog = dialogView.findViewById<android.widget.Button>(R.id.btnCloseTagsDialog)

        fun refreshTagList() {
            containerGpsTags.removeAllViews()
            val tags = GpsTaggingManager.getTags(this)
            updateGpsTagsOnMap()

            if (tags.isEmpty()) {
                val emptyTv = android.widget.TextView(this).apply {
                    text = "No GPS Target Coordinates Tagged Yet."
                    setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                    textSize = 12f
                    setPadding(16, 24, 16, 24)
                    gravity = android.view.Gravity.CENTER
                }
                containerGpsTags.addView(emptyTv)
                return
            }

            val timeFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

            for (tag in tags) {
                val itemView = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(16, 12, 16, 12)
                    setBackgroundResource(R.drawable.bg_glass_btn)
                    val params = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 0, 0, 12)
                    layoutParams = params
                }

                val titleTv = android.widget.TextView(this).apply {
                    text = "${tag.id}: ${String.format("%.5f", tag.latitude)}, ${String.format("%.5f", tag.longitude)} (Alt: ${String.format("%.1f", tag.altitude)}m)"
                    setTextColor(android.graphics.Color.parseColor("#00FF66"))
                    textSize = 13f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                val metaTv = android.widget.TextView(this).apply {
                    text = "Src: ${tag.source} | Time: ${timeFormat.format(java.util.Date(tag.timestamp))}"
                    setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                }

                val actionRow = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    val params = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    params.setMargins(0, 8, 0, 0)
                    layoutParams = params
                }

                val btnLock = android.widget.Button(this).apply {
                    text = "🎯 LOCK POI/TGP"
                    setTextColor(android.graphics.Color.BLACK)
                    textSize = 10f
                    setBackgroundColor(android.graphics.Color.parseColor("#00FF66"))
                    val p = android.widget.LinearLayout.LayoutParams(0, 72, 1.2f)
                    p.setMargins(0, 0, 8, 0)
                    layoutParams = p
                    setOnClickListener {
                        toggleTargetingPodLock(tag.latitude, tag.longitude, tag.altitude)
                        dialog.dismiss()
                    }
                }

                val btnDelete = android.widget.Button(this).apply {
                    text = "🗑️ DELETE"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 10f
                    setBackgroundColor(android.graphics.Color.parseColor("#FF3333"))
                    val p = android.widget.LinearLayout.LayoutParams(0, 72, 1.0f)
                    layoutParams = p
                    setOnClickListener {
                        GpsTaggingManager.deleteTag(this@MainActivity, tag.id)
                        refreshTagList()
                    }
                }

                actionRow.addView(btnLock)
                actionRow.addView(btnDelete)

                itemView.addView(titleTv)
                itemView.addView(metaTv)
                itemView.addView(actionRow)

                containerGpsTags.addView(itemView)
            }
        }

        refreshTagList()

        btnTagCurrentPos?.setOnClickListener {
            if (droneLat == 0.0 && droneLon == 0.0) {
                showToast("⚠️ Waiting for Drone GPS fix...")
            } else {
                GpsTaggingManager.addTag(this, "DRONE_POS", droneLat, droneLon, droneAlt, "DRONE_GPS")
                refreshTagList()
                showToast("📍 Tagged Current Drone Position")
            }
        }

        btnTagTgpTarget?.setOnClickListener {
            val tLat = if (isTgpGeoLockActive) tgpTargetLat else droneLat
            val tLon = if (isTgpGeoLockActive) tgpTargetLon else droneLon
            val tAlt = if (isTgpGeoLockActive) tgpTargetAlt else droneAlt

            if (tLat == 0.0 && tLon == 0.0) {
                showToast("⚠️ Waiting for GPS fix...")
            } else {
                GpsTaggingManager.addTag(this, "TGP_TARGET", tLat, tLon, tAlt, "TGP_GEO_LOCK")
                refreshTagList()
                showToast("📍 Tagged TGP Target Location")
            }
        }

        btnClearAllTags?.setOnClickListener {
            GpsTaggingManager.clearAllTags(this)
            refreshTagList()
            showToast("🗑️ All GPS Tags Cleared")
        }

        btnCloseTagsDialog?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private var isDistanceLimitEnabled = false
    private var maxFlightDistanceMeters = 50000

    private fun setDistanceLimitation(enabled: Boolean, distanceMeters: Int = 50000) {
        isDistanceLimitEnabled = enabled
        maxFlightDistanceMeters = distanceMeters
        log("Setting Flight Distance Limitation: enabled=$enabled, distance=${distanceMeters}m")

        val limitEnabledKey = KeyTools.createKey(FlightControllerKey.KeyDistanceLimitEnabled)
        val distanceLimitKey = KeyTools.createKey(FlightControllerKey.KeyDistanceLimit)

        KeyManager.getInstance().setValue(limitEnabledKey, enabled, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                log("Distance limit enabled set to: $enabled")
            }

            override fun onFailure(error: IDJIError) {
                log("Distance limit enabled switch error: ${error.errorCode()}")
            }
        })

        if (!enabled || distanceMeters > 0) {
            KeyManager.getInstance().setValue(distanceLimitKey, distanceMeters, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    log("Max flight distance limit set to: ${distanceMeters}m")
                }

                override fun onFailure(error: IDJIError) {
                    log("Max flight distance value set error: ${error.errorCode()}")
                }
            })
        }

        val prefs = getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("distance_limit_enabled", enabled)
            .putInt("max_flight_distance", distanceMeters)
            .apply()

        runOnUiThread {
            if (!enabled) {
                showToast("🌐 MAX FLIGHT DISTANCE: UNLIMITED (NO LIMITATION)")
            } else {
                showToast("🌐 MAX FLIGHT DISTANCE: LIMITED (${distanceMeters}m)")
            }
        }
    }

    private var isStealthModeEnabled = false
    private var isBellyLampOn = false
    private var currentLightingSituation = "NORMAL"
    private var blinkingThread: Thread? = null

    private fun setBellyLamp(on: Boolean) {
        isBellyLampOn = on
        log("Setting Belly Lamp / Bottom Auxiliary Light: $on")

        val ledKey = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)
        val ledSettings = if (isStealthModeEnabled) {
            dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(false, false, false, false)
        } else {
            dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(true, true, true, on)
        }
        KeyManager.getInstance().setValue(ledKey, ledSettings, null)

        runOnUiThread {
            showToast(if (on) "💡 Belly Landing Lamp: TURNED ON" else "💡 Belly Landing Lamp: TURNED OFF")
        }
    }

    private fun updateSituationLighting() {
        if (isStealthModeEnabled) return

        val newSituation = when {
            droneBattery in 1..24 -> "LOW_BATTERY_WARNING"
            droneFlightMode.contains("LAND", ignoreCase = true) || droneFlightMode.contains("TAKEOFF", ignoreCase = true) -> "TAKEOFF_LANDING"
            isMissionExecuting -> "MISSION_EXECUTING"
            else -> "NORMAL"
        }

        if (newSituation == currentLightingSituation) return
        currentLightingSituation = newSituation
        log("Tactical Situation Lighting changed to: $newSituation")

        blinkingThread?.interrupt()
        blinkingThread = null

        when (newSituation) {
            "LOW_BATTERY_WARNING" -> {
                blinkingThread = Thread {
                    try {
                        val key = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)
                        val offSettings = dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(false, false, false, false)
                        val warnSettings = dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(true, true, true, isBellyLampOn)

                        while (!Thread.currentThread().isInterrupted && currentLightingSituation == "LOW_BATTERY_WARNING") {
                            KeyManager.getInstance().setValue(key, offSettings, null)
                            Thread.sleep(350)
                            if (Thread.currentThread().isInterrupted) break
                            KeyManager.getInstance().setValue(key, warnSettings, null)
                            Thread.sleep(350)
                        }
                    } catch (e: InterruptedException) {
                        // Thread interrupted
                    }
                }.apply { start() }
            }
            "TAKEOFF_LANDING" -> {
                blinkingThread = Thread {
                    try {
                        val key = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)
                        val offSettings = dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(false, false, true, false)
                        val flashSettings = dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(true, true, true, true)

                        while (!Thread.currentThread().isInterrupted && currentLightingSituation == "TAKEOFF_LANDING") {
                            KeyManager.getInstance().setValue(key, offSettings, null)
                            Thread.sleep(500)
                            if (Thread.currentThread().isInterrupted) break
                            KeyManager.getInstance().setValue(key, flashSettings, null)
                            Thread.sleep(500)
                        }
                    } catch (e: InterruptedException) {
                        // Thread interrupted
                    }
                }.apply { start() }
            }
            "NORMAL" -> {
                val key = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)
                val normalSettings = dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(true, true, true, isBellyLampOn)
                KeyManager.getInstance().setValue(key, normalSettings, null)
            }
        }
    }

    private fun toggleStealthMode(enable: Boolean? = null) {
        val newState = enable ?: !isStealthModeEnabled
        isStealthModeEnabled = newState

        log("Setting Stealth Mode: $newState")

        if (newState) {
            blinkingThread?.interrupt()
            blinkingThread = null
        }

        // 1. Front LEDs, Rear LEDs, Status Indicators, and Navigation Lights -> OFF in Stealth, ON in Normal
        val ledKey = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)
        val ledSettings = if (newState) {
            dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(false, false, false, false)
        } else {
            dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(true, true, true, isBellyLampOn)
        }
        KeyManager.getInstance().setValue(ledKey, ledSettings, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() { log("Stealth Mode LEDsSettings set to $ledSettings") }
            override fun onFailure(error: IDJIError) { log("LEDsSettings control failed: ${error.errorCode()}") }
        })

        runOnUiThread {
            showToast(if (newState) "🥷 Stealth Mode ENABLED: All LEDs & Navigation Lights OFF" else "🥷 Stealth Mode DISABLED: Navigation LEDs Restored")
        }
    }

    private fun triggerTapToFocus(normX: Double, normY: Double, screenX: Float = -1f, screenY: Float = -1f) {
        val targetPoint = dji.sdk.keyvalue.value.common.DoublePoint2D(normX, normY)

        // Set AF Mode across all camera lenses
        val focusModeZoom = KeyTools.createCameraKey(CameraKey.KeyCameraFocusMode, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_ZOOM)
        KeyManager.getInstance().setValue(focusModeZoom, dji.sdk.keyvalue.value.camera.CameraFocusMode.AF, null)

        val focusModeWide = KeyTools.createCameraKey(CameraKey.KeyCameraFocusMode, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_WIDE)
        KeyManager.getInstance().setValue(focusModeWide, dji.sdk.keyvalue.value.camera.CameraFocusMode.AF, null)

        // Set Focus Target Point (Zoom, Wide, & General)
        val targetZoom = KeyTools.createCameraKey(CameraKey.KeyCameraFocusTarget, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_ZOOM)
        KeyManager.getInstance().setValue(targetZoom, targetPoint, null)

        val targetWide = KeyTools.createCameraKey(CameraKey.KeyCameraFocusTarget, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_WIDE)
        KeyManager.getInstance().setValue(targetWide, targetPoint, null)

        val targetGeneral = KeyTools.createKey(CameraKey.KeyCameraFocusTarget)
        KeyManager.getInstance().setValue(targetGeneral, targetPoint, null)

        runOnUiThread {
            val focusReticle = findViewById<android.widget.ImageView>(R.id.focusReticle)
            if (focusReticle != null && fpvSurface.width > 0) {
                val posX = if (screenX >= 0) screenX else (normX * fpvSurface.width).toFloat()
                val posY = if (screenY >= 0) screenY else (normY * fpvSurface.height).toFloat()

                focusReticle.visibility = View.VISIBLE
                focusReticle.alpha = 1f
                focusReticle.translationX = posX - (focusReticle.width / 2f)
                focusReticle.translationY = posY - (focusReticle.height / 2f)
                focusReticle.scaleX = 1.6f
                focusReticle.scaleY = 1.6f
                focusReticle.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(250)
                    .withEndAction {
                        focusReticle.postDelayed({
                            focusReticle.animate().alpha(0f).setDuration(250).withEndAction {
                                focusReticle.visibility = View.GONE
                                focusReticle.alpha = 1f
                            }.start()
                        }, 1800)
                    }
                    .start()
            }
            showToast("🎯 Touch Focus Target: ${String.format(java.util.Locale.US, "%.2f", normX)}, ${String.format(java.util.Locale.US, "%.2f", normY)}")
        }
    }

    private fun triggerContinuousAutoFocus() {
        val focusModeKey = KeyTools.createCameraKey(CameraKey.KeyCameraFocusMode, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_ZOOM)
        KeyManager.getInstance().setValue(focusModeKey, dji.sdk.keyvalue.value.camera.CameraFocusMode.AF, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                runOnUiThread { showToast("Continuous Auto Focus Enabled") }
                log("Continuous Auto Focus Enabled")
            }
            override fun onFailure(error: IDJIError) {
                runOnUiThread { showToast("Auto Focus Failed: ${error.errorCode()}") }
                log("Auto Focus Failed: ${error.errorCode()}")
            }
        })
    }

    private fun setupGimbalInteraction() {
        var isVirtualStickEnabledLocally = false
        val zoomKey = KeyTools.createCameraKey(CameraKey.KeyCameraZoomRatios, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_ZOOM)
        
        KeyManager.getInstance().listen(zoomKey, this) { _, newValue: Double? ->
            if (newValue != null) {
                currentZoomRatio = newValue
            }
        }

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentZoomRatio *= detector.scaleFactor
                if (currentZoomRatio < 1.0) currentZoomRatio = 1.0
                
                KeyManager.getInstance().setValue(zoomKey, currentZoomRatio, null)
                return true
            }
        })

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (currentControlMode == ControlMode.MAP) {
                    runOnUiThread { findViewById<View>(R.id.btnModeFly).performClick() }
                    return true
                }
                // Tap anywhere on FPV stream feed -> Tap-to-Focus & Auto Focus
                val normX = (e.x / fpvSurface.width.toDouble()).coerceIn(0.0, 1.0)
                val normY = (e.y / fpvSurface.height.toDouble()).coerceIn(0.0, 1.0)
                
                triggerTapToFocus(normX, normY, e.x, e.y)
                return true
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (e2.pointerCount > 1) {
                    val zoomDelta = distanceY * 0.03
                    currentZoomRatio += zoomDelta
                    if (currentZoomRatio < 1.0) currentZoomRatio = 1.0
                    KeyManager.getInstance().setValue(zoomKey, currentZoomRatio, null)
                    return true
                }
                
                val invertY = if (invertVertical) -1.0 else 1.0
                val invertX = if (invertHorizontal) -1.0 else 1.0
                
                val pitchSpeed = -distanceY * swipeSensitivity * invertY
                val yawSpeed = -distanceX * swipeSensitivity * invertX

                val hasInfiniteYaw = false 
                val yawLimit = 25.0

                if (!hasInfiniteYaw && ((gimbalYaw >= yawLimit && yawSpeed > 0) || (gimbalYaw <= -yawLimit && yawSpeed < 0))) {
                    val vs = dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance()
                    if (!isVirtualStickEnabledLocally) {
                        vs.enableVirtualStick(object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                isVirtualStickEnabledLocally = true
                                vs.leftStick.horizontalPosition = (yawSpeed * 2).toInt()
                                vs.leftStick.verticalPosition = 0
                                vs.rightStick.horizontalPosition = 0
                                vs.rightStick.verticalPosition = 0
                                vs.sendVirtualStickAdvancedParam(dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam())
                            }
                            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                                log("Failed to enable VS for yaw limit: ${error.description()}")
                            }
                        })
                    } else {
                        vs.leftStick.horizontalPosition = (yawSpeed * 2).toInt()
                        vs.leftStick.verticalPosition = 0
                        vs.rightStick.horizontalPosition = 0
                        vs.rightStick.verticalPosition = 0
                        vs.sendVirtualStickAdvancedParam(dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam())
                    }
                    
                    val rotateKey = KeyTools.createKey(GimbalKey.KeyRotateBySpeed, ComponentIndexType.LEFT_OR_MAIN)
                    KeyManager.getInstance().performAction(rotateKey, GimbalSpeedRotation(pitchSpeed.toDouble(), 0.0, 0.0, CtrlInfo()), null)
                } else {
                    val rotateKey = KeyTools.createKey(GimbalKey.KeyRotateBySpeed, ComponentIndexType.LEFT_OR_MAIN)
                    val rotation = GimbalSpeedRotation(pitchSpeed.toDouble(), yawSpeed.toDouble(), 0.0, CtrlInfo())
                    KeyManager.getInstance().performAction(rotateKey, rotation, null)
                }
                
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })

        fpvSurface.setOnTouchListener { _, event ->
            val modeSwitcher = findViewById<View>(R.id.modeSwitcher)
            modeSwitcher.animate().alpha(1f).setDuration(200).start()
            
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                val rotateKey = KeyTools.createKey(GimbalKey.KeyRotateBySpeed, ComponentIndexType.LEFT_OR_MAIN)
                val rotation = GimbalSpeedRotation(0.0, 0.0, 0.0, CtrlInfo())
                KeyManager.getInstance().performAction(rotateKey, rotation, null)
                
                val vs = dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance()
                if (isVirtualStickEnabledLocally) {
                    vs.leftStick.horizontalPosition = 0
                    vs.leftStick.verticalPosition = 0
                    vs.rightStick.horizontalPosition = 0
                    vs.rightStick.verticalPosition = 0
                    vs.sendVirtualStickAdvancedParam(dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam())
                    
                    vs.disableVirtualStick(null)
                    isVirtualStickEnabledLocally = false
                }
            }
            
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun showSystemDialog() {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_system)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        val width = (resources.displayMetrics.widthPixels * 0.45).toInt()
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        
        val tabInfo = dialog.findViewById<TextView>(R.id.tabInfo)
        val tabCfg = dialog.findViewById<TextView>(R.id.tabCfg)
        val tabIsr = dialog.findViewById<TextView>(R.id.tabIsr)
        val tabLog = dialog.findViewById<TextView>(R.id.tabLog)

        val layoutInfo = dialog.findViewById<View>(R.id.layoutInfo)
        val layoutCfg = dialog.findViewById<View>(R.id.layoutCfg)
        val layoutIsr = dialog.findViewById<View>(R.id.layoutIsr)
        val layoutLog = dialog.findViewById<View>(R.id.layoutLog)

        val GREEN_ACTIVE = android.graphics.Color.parseColor("#00FF66")
        val GREEN_DIM    = android.graphics.Color.parseColor("#33FFFFFF")
        val TRANSPARENT  = android.graphics.Color.TRANSPARENT

        fun updateTabs(active: Int) {
            // Reset all tabs to inactive style
            for ((tab, idx) in listOf(tabInfo to 0, tabCfg to 1, tabIsr to 2, tabLog to 3)) {
                if (active == idx) {
                    tab.setBackgroundResource(R.drawable.bg_glass_btn_active)
                    tab.setTextColor(android.graphics.Color.WHITE)
                } else {
                    tab.setBackgroundColor(TRANSPARENT)
                    tab.setTextColor(GREEN_DIM)
                }
            }
            layoutInfo.visibility = if (active == 0) View.VISIBLE else View.GONE
            layoutCfg.visibility  = if (active == 1) View.VISIBLE else View.GONE
            layoutIsr.visibility  = if (active == 2) View.VISIBLE else View.GONE
            layoutLog.visibility  = if (active == 3) View.VISIBLE else View.GONE
        }

        tabInfo.setOnClickListener { updateTabs(0) }
        tabCfg.setOnClickListener  { updateTabs(1) }
        tabIsr.setOnClickListener  { updateTabs(2) }
        tabLog.setOnClickListener  { updateTabs(3) }

        updateTabs(0)
        
        dTvDroneModel = dialog.findViewById(R.id.tvDroneModel)
        dTvDroneStatus = dialog.findViewById(R.id.tvDroneStatus)
        dTvRCStatus = dialog.findViewById(R.id.tvRCStatus)
        dTvEngineStatus = dialog.findViewById(R.id.tvEngineStatus)
        dTvSimulatorStatus = dialog.findViewById(R.id.tvSimulatorStatus)
        
        dTvDroneModel?.text = currentDroneModelStr
        dTvDroneStatus?.text = currentDroneStatusStr
        dTvDroneStatus?.setTextColor(currentDroneStatusColor)
        dTvRCStatus?.text = currentRcStatusStr
        dTvRCStatus?.setTextColor(currentRcStatusColor)
        dTvEngineStatus?.text = currentEngineStatusStr
        dTvEngineStatus?.setTextColor(currentEngineStatusColor)
        
        if (isSimulatorActive) {
            dTvSimulatorStatus?.visibility = View.VISIBLE
            startBlinkingAnimation(dTvSimulatorStatus!!)
        } else {
            dTvSimulatorStatus?.visibility = View.GONE
            dTvSimulatorStatus?.clearAnimation()
        }
        
        val dBtnTestAlerts = dialog.findViewById<TextView>(R.id.btnTestAlerts)
        dBtnTestAlerts?.setOnClickListener {
            if (alertThermal.visibility != View.VISIBLE) {
                alertThermal.visibility = View.VISIBLE
                startBlinkingAnimation(alertThermal)
                alertLow.visibility = View.VISIBLE
                startBlinkingAnimation(alertLow)
                alertStorage.visibility = View.VISIBLE
                startBlinkingAnimation(alertStorage)
                alertObstacle.visibility = View.VISIBLE
                startBlinkingAnimation(alertObstacle)
                alertBlock.visibility = View.VISIBLE
                startBlinkingAnimation(alertBlock)
                alertLoss.visibility = View.VISIBLE
                startBlinkingAnimation(alertLoss)
                obstacleRadar.updateDistances(2.0, 2.0, 2.0, 2.0)
                showToast("Test: All Alerts & Radar ON")
                log("Test: All Alerts Triggered")
            }
        }
        
        val dBtnPair = dialog.findViewById<TextView>(R.id.btnPair)
        val dBtnGimbalSens = dialog.findViewById<TextView>(R.id.btnGimbalSens)
        val dBtnFlightSens = dialog.findViewById<TextView>(R.id.btnFlightSens)
        val dBtnInvertVertical = dialog.findViewById<TextView>(R.id.btnInvertVertical)
        val dBtnInvertHorizontal = dialog.findViewById<TextView>(R.id.btnInvertHorizontal)
        val dBtnRthAltitude = dialog.findViewById<TextView>(R.id.btnRthAltitude)
        val dBtnObstacleAction = dialog.findViewById<TextView>(R.id.btnObstacleAction)
        val dBtnToggleRadar = dialog.findViewById<TextView>(R.id.btnToggleRadar)
        val dBtnRadarDistance = dialog.findViewById<TextView>(R.id.btnRadarDistance)
        val dBtnSimulator = dialog.findViewById<TextView>(R.id.btnSimulator)
        val dBtnSignalLossAction = dialog.findViewById<TextView>(R.id.btnSignalLossAction)
        
        val etServerIp = dialog.findViewById<android.widget.EditText>(R.id.etServerIp)
        val etServerPort = dialog.findViewById<android.widget.EditText>(R.id.etServerPort)
        val etMqttUser = dialog.findViewById<android.widget.EditText>(R.id.etMqttUser)
        val etMqttPass = dialog.findViewById<android.widget.EditText>(R.id.etMqttPass)
        val btnConnectServer = dialog.findViewById<android.widget.Button>(R.id.btnConnectServer)
        val btnWebOdmConfig = dialog.findViewById<android.widget.Button>(R.id.btnWebOdmConfig)
        
        val sharedPrefs = getSharedPreferences("TacticalHUDConfig", android.content.Context.MODE_PRIVATE)
        etServerIp?.setText(sharedPrefs.getString("mqttServerIp", "127.0.0.1"))
        etServerPort?.setText(sharedPrefs.getString("mqttServerPort", "1883"))
        etMqttUser?.setText(sharedPrefs.getString("mqttUser", "admin"))
        etMqttPass?.setText(sharedPrefs.getString("mqttPass", "password"))
        
        // FPV STREAMING PROTOCOL SELECTOR BINDS
        val btnStreamModeWhip = dialog.findViewById<android.widget.Button>(R.id.btnStreamModeWhip)
        val btnStreamModeRtsp = dialog.findViewById<android.widget.Button>(R.id.btnStreamModeRtsp)
        val btnStreamModeRtmp = dialog.findViewById<android.widget.Button>(R.id.btnStreamModeRtmp)
        val dBtnStartRtmp = dialog.findViewById<android.widget.Button>(R.id.btnStartRtmp)
        val dBtnStopRtmp = dialog.findViewById<android.widget.Button>(R.id.btnStopRtmp)
        val dEtRtmpUrl = dialog.findViewById<android.widget.EditText>(R.id.etRtmpUrl)

        val DEFAULT_WHIP_URL = "https://streamer:Rahas!%402025@rtc.blackeye.id/api/webrtc?dst=dji-sdk-view-asli"
        val DEFAULT_RTSP_URL = "rtsp://streamer:Rahas!%402025@rtc.blackeye.id:8554/dji-sdk-view-asli"
        val DEFAULT_RTMP_URL = "rtmp://rtc.blackeye.id:1936/dji-sdk-view-asli"

        dEtRtmpUrl?.setText(sharedPrefs.getString("rtmpUrl", DEFAULT_WHIP_URL))

        fun updateStreamModeButtons(activeMode: String) {
            btnStreamModeWhip?.setTextColor(if (activeMode == "WHIP") android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.parseColor("#AAAAAA"))
            btnStreamModeWhip?.setBackgroundResource(if (activeMode == "WHIP") R.drawable.bg_btn_outline_green else R.drawable.bg_telemetry_capsule)

            btnStreamModeRtsp?.setTextColor(if (activeMode == "RTSP") android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.parseColor("#AAAAAA"))
            btnStreamModeRtsp?.setBackgroundResource(if (activeMode == "RTSP") R.drawable.bg_btn_outline_green else R.drawable.bg_telemetry_capsule)

            btnStreamModeRtmp?.setTextColor(if (activeMode == "RTMP") android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.parseColor("#AAAAAA"))
            btnStreamModeRtmp?.setBackgroundResource(if (activeMode == "RTMP") R.drawable.bg_btn_outline_green else R.drawable.bg_telemetry_capsule)
        }

        btnStreamModeWhip?.setOnClickListener {
            dEtRtmpUrl?.setText(DEFAULT_WHIP_URL)
            updateStreamModeButtons("WHIP")
            showToast("Mode Selected: ⚡ WEBRTC WHIP (Sub-80ms)")
        }

        btnStreamModeRtsp?.setOnClickListener {
            dEtRtmpUrl?.setText(DEFAULT_RTSP_URL)
            updateStreamModeButtons("RTSP")
            showToast("Mode Selected: 📡 RTSP UDP (<150ms)")
        }

        btnStreamModeRtmp?.setOnClickListener {
            dEtRtmpUrl?.setText(DEFAULT_RTMP_URL)
            updateStreamModeButtons("RTMP")
            showToast("Mode Selected: 🌐 RTMP (Standard Push)")
        }
        
        dBtnStartRtmp?.setOnClickListener {
            val url = dEtRtmpUrl?.text.toString()
            sharedPrefs.edit().putString("rtmpUrl", url).apply()
            startRtmpStream(url)
        }
        dBtnStopRtmp?.setOnClickListener {
            stopRtmpStream()
        }

        // ══════════════════════════════════════════════════════════
        // ISR MODE TAB — S3 / CEPH STORAGE BINDS
        // ══════════════════════════════════════════════════════════
        val btnIsrMode1Toggle    = dialog.findViewById<android.widget.Button>(R.id.btnIsrMode1Toggle)
        val btnIsrMode2Toggle    = dialog.findViewById<android.widget.Button>(R.id.btnIsrMode2Toggle)
        val btnIsrMode1CaptureNow = dialog.findViewById<android.widget.Button>(R.id.btnIsrMode1CaptureNow)
        val tvIsrStatus          = dialog.findViewById<TextView>(R.id.tvIsrStatus)
        val tvMode1LastCapture   = dialog.findViewById<TextView>(R.id.tvMode1LastCapture)
        val tvIsrUploadStatus    = dialog.findViewById<TextView>(R.id.tvIsrUploadStatus)
        val pbIsrUpload          = dialog.findViewById<android.widget.ProgressBar>(R.id.pbIsrUpload)

        val etS3ServerUrl     = dialog.findViewById<android.widget.EditText>(R.id.etS3ServerUrl)
        val etS3AccessKey     = dialog.findViewById<android.widget.EditText>(R.id.etS3AccessKey)
        val etS3SecretKey     = dialog.findViewById<android.widget.EditText>(R.id.etS3SecretKey)
        val etS3Region        = dialog.findViewById<android.widget.EditText>(R.id.etS3Region)
        val btnS3FolderModeToggle = dialog.findViewById<android.widget.Button>(R.id.btnS3FolderModeToggle)
        val etS3CustomFolder  = dialog.findViewById<android.widget.EditText>(R.id.etS3CustomFolder)
        val etLocalFetchFolder = dialog.findViewById<android.widget.EditText>(R.id.etLocalFetchFolder)
        val btnSaveS3Config   = dialog.findViewById<android.widget.Button>(R.id.btnSaveS3Config)
        val btnCreateS3Folder = dialog.findViewById<android.widget.Button>(R.id.btnCreateS3Folder)
        val btnConnectCeph    = dialog.findViewById<android.widget.Button>(R.id.btnConnectCeph)
        val etLocalFolderPath = dialog.findViewById<android.widget.EditText>(R.id.etLocalFolderPath)
        val btnPickLocalFolder = dialog.findViewById<android.widget.Button>(R.id.btnPickLocalFolder)

        // Populate fields from saved config
        etS3ServerUrl?.setText(com.dji.recreate2.aws.S3UploadManager.getS3ServerUrl(this))
        etS3AccessKey?.setText(com.dji.recreate2.aws.S3UploadManager.getAccessKey(this))
        etS3SecretKey?.setText(com.dji.recreate2.aws.S3UploadManager.getSecretKey(this))
        etS3Region?.setText(com.dji.recreate2.aws.S3UploadManager.getRegion(this))
        etS3CustomFolder?.setText(com.dji.recreate2.aws.S3UploadManager.getCustomFolderName(this))
        etLocalFetchFolder?.setText(com.dji.recreate2.sync.PostFlightS3Sync.getLocalFetchFolderName(this))
        // Restore saved local folder path
        val prefs = getSharedPreferences("TacticalHUDConfig", android.content.Context.MODE_PRIVATE)
        etLocalFolderPath?.setText(prefs.getString("isrLocalFolderPath", ""))

        // --- ISR Mode 1 & 2 Toggle + Status Banner ---
        fun refreshIsrStatusBanner() {
            val m1On = com.dji.recreate2.sync.ISRModeManager.currentMode == "MODE1"
            val m2On = com.dji.recreate2.sync.ISRModeManager.currentMode == "MODE2"
            val m1Dot = if (m1On) "\u25CF" else "\u25CB"
            val m2Dot = if (m2On) "\u25CF" else "\u25CB"
            val m1Color = if (m1On) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.parseColor("#AAAAAA")
            val m2Color = if (m2On) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.parseColor("#AAAAAA")
            tvIsrStatus?.text = "$m1Dot Mode 1: ${if (m1On) "ENABLED" else "DISABLED"}   $m2Dot Mode 2: ${if (m2On) "ENABLED" else "DISABLED"}"

            // Mode 1 button
            btnIsrMode1Toggle?.text = if (m1On) "MODE 1 (HUD CAPTURE): ENABLED ✓" else "MODE 1 (HUD CAPTURE): DISABLED"
            btnIsrMode1Toggle?.setTextColor(if (m1On) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.parseColor("#AAAAAA"))
            btnIsrMode1CaptureNow?.isEnabled = m1On
            btnIsrMode1CaptureNow?.alpha = if (m1On) 1.0f else 0.4f

            // Mode 2 button
            btnIsrMode2Toggle?.text = if (m2On) "MODE 2 (AUTO SYNC ON LAND): ENABLED ✓" else "MODE 2 (AUTO SYNC ON LAND): DISABLED"
            btnIsrMode2Toggle?.setTextColor(if (m2On) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.parseColor("#AAAAAA"))
        }
        refreshIsrStatusBanner()

        // Hook Mode 2 SD Card live progress updates to system dialog UI
        com.dji.recreate2.sync.PostFlightS3Sync.onSyncProgressUpdate = { statusStr, currItem, totalItems, pct ->
            runOnUiThread {
                tvIsrUploadStatus?.text = statusStr
                if (pbIsrUpload != null) {
                    pbIsrUpload.visibility = View.VISIBLE
                    pbIsrUpload.progress = pct
                    if (pct >= 100) {
                        pbIsrUpload.postDelayed({ pbIsrUpload.visibility = View.GONE }, 3000)
                    }
                }
            }
        }

        btnIsrMode1Toggle?.setOnClickListener {
            val nextMode = if (com.dji.recreate2.sync.ISRModeManager.currentMode == "MODE1") "NONE" else "MODE1"
            com.dji.recreate2.sync.ISRModeManager.setMode(nextMode, this)
            refreshIsrStatusBanner()
            showToast(if (nextMode == "MODE1") "ISR Mode 1 (HUD Capture): ENABLED" else "ISR Mode 1: DISABLED")
        }

        btnIsrMode2Toggle?.setOnClickListener {
            val nextMode = if (com.dji.recreate2.sync.ISRModeManager.currentMode == "MODE2") "NONE" else "MODE2"
            com.dji.recreate2.sync.ISRModeManager.setMode(nextMode, this)
            refreshIsrStatusBanner()
            showToast(if (nextMode == "MODE2") "ISR Mode 2 (Auto-Sync on Landing): ENABLED" else "ISR Mode 2: DISABLED")
        }

        val btnIsrMode2SyncNow = dialog.findViewById<android.widget.Button>(R.id.btnIsrMode2SyncNow)
        btnIsrMode2SyncNow?.setOnClickListener {
            log("Manual Mode 2 Sync Triggered")
            showToast("ISR Mode 2: Starting manual sync from drone SD card...")
            com.dji.recreate2.sync.PostFlightS3Sync.startSync(this)
        }

        // --- Reticle Target Box & Compass Tape Overlay Toggles for Mode 1 Record & Stream ---
        val btnToggleReticleOverlay = dialog.findViewById<android.widget.Button>(R.id.btnToggleReticleOverlay)
        val btnToggleCompassOverlay = dialog.findViewById<android.widget.Button>(R.id.btnToggleCompassOverlay)

        fun refreshOverlayButtonUI() {
            val rOn = com.dji.recreate2.sync.FpvStreamRecorder.isReticleOverlayEnabled
            val cOn = com.dji.recreate2.sync.FpvStreamRecorder.isCompassOverlayEnabled
            btnToggleReticleOverlay?.text = if (rOn) "RETICLE: ON ✓" else "RETICLE: OFF"
            btnToggleReticleOverlay?.setTextColor(if (rOn) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.parseColor("#AAAAAA"))
            btnToggleCompassOverlay?.text = if (cOn) "COMPASS: ON ✓" else "COMPASS: OFF"
            btnToggleCompassOverlay?.setTextColor(if (cOn) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.parseColor("#AAAAAA"))
        }
        refreshOverlayButtonUI()

        btnToggleReticleOverlay?.setOnClickListener {
            val newState = !com.dji.recreate2.sync.FpvStreamRecorder.isReticleOverlayEnabled
            com.dji.recreate2.sync.FpvStreamRecorder.isReticleOverlayEnabled = newState
            refreshOverlayButtonUI()
            showToast(if (newState) "Reticle Target Box Overlay: ENABLED" else "Reticle Target Box Overlay: DISABLED")
        }

        btnToggleCompassOverlay?.setOnClickListener {
            val newState = !com.dji.recreate2.sync.FpvStreamRecorder.isCompassOverlayEnabled
            com.dji.recreate2.sync.FpvStreamRecorder.isCompassOverlayEnabled = newState
            refreshOverlayButtonUI()
            showToast(if (newState) "Compass Tape Overlay: ENABLED" else "Compass Tape Overlay: DISABLED")
        }

        val btnFpvAcroMode = dialog.findViewById<android.widget.Button>(R.id.btnFpvAcroMode)
        fun refreshFpvAcroButtonUI() {
            val enabled = com.dji.recreate2.flight.ConfinedSpaceFlightManager.isFpvAcroModeEnabled
            btnFpvAcroMode?.text = if (enabled) "⚡ FPV ACRO FLIGHT MODE: ON ✓" else "⚡ FPV ACRO FLIGHT MODE: OFF"
            btnFpvAcroMode?.setTextColor(if (enabled) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#888888"))
        }
        refreshFpvAcroButtonUI()

        btnFpvAcroMode?.setOnClickListener {
            val newState = !com.dji.recreate2.flight.ConfinedSpaceFlightManager.isFpvAcroModeEnabled
            com.dji.recreate2.flight.ConfinedSpaceFlightManager.setFpvAcroMode(newState)

            val gimbalModeKey = KeyTools.createKey(dji.sdk.keyvalue.key.GimbalKey.KeyGimbalMode)
            val mode = if (newState) dji.sdk.keyvalue.value.gimbal.GimbalMode.FPV else dji.sdk.keyvalue.value.gimbal.GimbalMode.YAW_FOLLOW
            KeyManager.getInstance().setValue(gimbalModeKey, mode, null)

            refreshFpvAcroButtonUI()
            showToast(if (newState) "⚡ FPV Acro Mode: ENABLED (Gimbal Roll Lock Active)" else "⚡ FPV Acro Mode: DISABLED (Standard POS Mode)")
        }

        val btnToggleAiDetectionBoxes = dialog.findViewById<android.widget.Button>(R.id.btnToggleAiDetectionBoxes)
        fun refreshAiBoxButtonUI() {
            val visible = objectTrackingOverlay?.isAiDetectionBoxesVisible ?: true
            btnToggleAiDetectionBoxes?.text = if (visible) "🤖 AI OBJECT DETECTION BOXES: ON ✓" else "🤖 AI OBJECT DETECTION BOXES: OFF (HIDDEN)"
            btnToggleAiDetectionBoxes?.setTextColor(if (visible) android.graphics.Color.parseColor("#00E5FF") else android.graphics.Color.parseColor("#888888"))
        }
        refreshAiBoxButtonUI()

        btnToggleAiDetectionBoxes?.setOnClickListener {
            val newState = !(objectTrackingOverlay?.isAiDetectionBoxesVisible ?: true)
            objectTrackingOverlay?.isAiDetectionBoxesVisible = newState
            refreshAiBoxButtonUI()
            showToast(if (newState) "🤖 AI Object Detection Boxes: VISIBLE" else "🤖 AI Object Detection Boxes: HIDDEN")
        }

        // Mode 1: Capture drone camera feed only (no HUD) and upload to S3
        btnIsrMode1CaptureNow?.setOnClickListener {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            tvMode1LastCapture?.text = "Last: $ts"
            tvIsrUploadStatus?.text = "Capturing drone camera feed…"
            pbIsrUpload?.visibility = View.VISIBLE
            pbIsrUpload?.progress = 10

            val w = fpvSurface.width
            val h = fpvSurface.height
            if (w <= 0 || h <= 0) {
                tvIsrUploadStatus?.text = "✗ No camera feed available"
                pbIsrUpload?.visibility = View.GONE
                showToast("ISR: No camera feed — connect drone first")
                return@setOnClickListener
            }

            val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)

            // PixelCopy captures the raw SurfaceView texture — drone cam only, no HUD
            android.view.PixelCopy.request(fpvSurface, bitmap, { result ->
                if (result == android.view.PixelCopy.SUCCESS) {
                    pbIsrUpload?.progress = 40

                    val dateFolder  = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
                    val fileName    = "isr_cam_${System.currentTimeMillis()}.jpg"
                    val cacheFile   = java.io.File(cacheDir, fileName)

                    Thread {
                        try {
                            cacheFile.outputStream().use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                            }
                            injectExifMetadata(cacheFile)
                            runOnUiThread {
                                tvIsrUploadStatus?.text = "Uploading to S3…"
                                pbIsrUpload?.progress = 50
                            }

                            com.dji.recreate2.aws.S3UploadManager.uploadFile(
                                context        = this,
                                localFile      = cacheFile,
                                remoteFolder   = dateFolder,
                                remoteFileName = fileName,
                                onProgress     = { pct ->
                                    runOnUiThread {
                                        pbIsrUpload?.progress = 50 + (pct * 0.5).toInt()
                                        tvIsrUploadStatus?.text = "Uploading: $pct%"
                                    }
                                },
                                onSuccess = {
                                    runOnUiThread {
                                        pbIsrUpload?.progress = 100
                                        tvIsrUploadStatus?.text = "✔ Uploaded: $fileName"
                                        pbIsrUpload?.postDelayed({ pbIsrUpload?.visibility = View.GONE }, 3000)
                                        log("ISR cam capture uploaded: $fileName → $dateFolder/")
                                    }
                                    cacheFile.delete()
                                },
                                onError = { err ->
                                    runOnUiThread {
                                        pbIsrUpload?.visibility = View.GONE
                                        tvIsrUploadStatus?.text = "✗ Upload failed: $err"
                                    }
                                    cacheFile.delete()
                                }
                            )
                        } catch (e: Exception) {
                            runOnUiThread {
                                pbIsrUpload?.visibility = View.GONE
                                tvIsrUploadStatus?.text = "✗ Save error: ${e.message}"
                            }
                        }
                    }.start()
                } else {
                    runOnUiThread {
                        pbIsrUpload?.visibility = View.GONE
                        tvIsrUploadStatus?.text = "✗ PixelCopy failed (code $result)"
                        showToast("ISR: Camera capture failed — is FPV active?")
                    }
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }

        // S3 Folder Mode Toggle
        var currentS3FolderMode = com.dji.recreate2.aws.S3UploadManager.getFolderMode(this)
        fun updateFolderModeUi() {
            if (currentS3FolderMode == com.dji.recreate2.aws.S3UploadManager.FOLDER_MODE_CUSTOM) {
                btnS3FolderModeToggle?.text = "FOLDER: CUSTOM"
                etS3CustomFolder?.visibility = android.view.View.VISIBLE
            } else {
                btnS3FolderModeToggle?.text = "FOLDER: AUTO (DATE)"
                etS3CustomFolder?.visibility = android.view.View.GONE
            }
        }
        updateFolderModeUi()

        btnS3FolderModeToggle?.setOnClickListener {
            currentS3FolderMode = if (currentS3FolderMode == com.dji.recreate2.aws.S3UploadManager.FOLDER_MODE_AUTO) {
                com.dji.recreate2.aws.S3UploadManager.FOLDER_MODE_CUSTOM
            } else {
                com.dji.recreate2.aws.S3UploadManager.FOLDER_MODE_AUTO
            }
            updateFolderModeUi()
        }

        btnSaveS3Config?.setOnClickListener {
            val url          = etS3ServerUrl?.text.toString().trim()
            val ak           = etS3AccessKey?.text.toString().trim()
            val sk           = etS3SecretKey?.text.toString().trim()
            val reg          = etS3Region?.text.toString().trim()
            val customFolder = etS3CustomFolder?.text.toString().trim()
            val localFolder  = etLocalFetchFolder?.text.toString().trim()

            if (url.isNotEmpty()) com.dji.recreate2.aws.S3UploadManager.saveS3ServerUrl(this, url)
            if (ak.isNotEmpty() && sk.isNotEmpty()) com.dji.recreate2.aws.S3UploadManager.saveCredentials(this, ak, sk, if (reg.isEmpty()) "BT" else reg)
            com.dji.recreate2.aws.S3UploadManager.saveFolderConfig(this, currentS3FolderMode, customFolder)
            if (localFolder.isNotEmpty()) com.dji.recreate2.sync.PostFlightS3Sync.saveLocalFetchFolderName(this, localFolder)

            showToast("Ceph S3 & Local Storage Config Saved!")
            log("ISR/S3 config saved. URL=$url folder_mode=$currentS3FolderMode")
        }

        btnCreateS3Folder?.setOnClickListener {
            val customFolder = etS3CustomFolder?.text.toString().trim()
            val folderToCreate = if (customFolder.isNotEmpty()) customFolder
                else java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            tvIsrUploadStatus?.text = "Creating folder: $folderToCreate..."
            showToast("Creating remote S3 folder ($folderToCreate)...")
            com.dji.recreate2.aws.S3UploadManager.createRemoteFolder(this, folderToCreate,
                onSuccess = {
                    runOnUiThread {
                        tvIsrUploadStatus?.text = "\u2714 S3 folder created: $folderToCreate"
                        showToast("Successfully created S3 folder: $folderToCreate")
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        tvIsrUploadStatus?.text = "\u2717 Folder create failed: $err"
                        showToast("Failed to create S3 folder: $err")
                    }
                }
            )
        }

        // ── S3 REMOTE FOLDER DROPDOWN (SPINNER) ──────────────────────────
        val spinnerS3Folder         = dialog.findViewById<android.widget.Spinner>(R.id.spinnerS3Folder)
        val btnRefreshS3Folders     = dialog.findViewById<android.widget.Button>(R.id.btnRefreshS3Folders)
        val btnUseSelectedS3Folder  = dialog.findViewById<android.widget.Button>(R.id.btnUseSelectedS3Folder)

        val folderList = mutableListOf<String>("\u23F3 Loading\u2026")
        val folderAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            folderList
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerS3Folder?.adapter = folderAdapter

        fun loadS3Folders() {
            runOnUiThread {
                folderList.clear()
                folderList.add("\u23F3 Fetching folders\u2026")
                folderAdapter.notifyDataSetChanged()
                btnRefreshS3Folders?.isEnabled = false
                tvIsrUploadStatus?.text = "Fetching S3 folder list\u2026"
            }
            com.dji.recreate2.aws.S3UploadManager.listRemoteFolders(
                context   = this,
                onSuccess = { folders ->
                    runOnUiThread {
                        folderList.clear()
                        btnRefreshS3Folders?.isEnabled = true
                        if (folders.isEmpty()) {
                            folderList.add("(no folders found)")
                            tvIsrUploadStatus?.text = "No remote folders found. Create one first."
                        } else {
                            folderList.addAll(folders)
                            tvIsrUploadStatus?.text = "\u2714 ${folders.size} folder(s) found"
                        }
                        folderAdapter.notifyDataSetChanged()
                        val savedFolder = com.dji.recreate2.aws.S3UploadManager.getCustomFolderName(this)
                        val savedIdx = folderList.indexOf(savedFolder)
                        if (savedIdx >= 0) spinnerS3Folder?.setSelection(savedIdx)
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        folderList.clear()
                        folderList.add("\u26A0 Error \u2014 tap \u21BB REFRESH")
                        folderAdapter.notifyDataSetChanged()
                        btnRefreshS3Folders?.isEnabled = true
                        tvIsrUploadStatus?.text = "Folder fetch failed: $err"
                        log("S3 folder list error: $err")
                    }
                }
            )
        }

        loadS3Folders()

        btnRefreshS3Folders?.setOnClickListener { loadS3Folders() }

        btnUseSelectedS3Folder?.setOnClickListener {
            val selected = spinnerS3Folder?.selectedItem?.toString()?.trim() ?: ""
            if (selected.isEmpty() || selected.startsWith("\u23F3") || selected.startsWith("\u26A0") || selected.startsWith("(")) {
                showToast("No valid folder selected")
                return@setOnClickListener
            }
            etS3CustomFolder?.setText(selected)
            etS3CustomFolder?.visibility = View.VISIBLE
            currentS3FolderMode = com.dji.recreate2.aws.S3UploadManager.FOLDER_MODE_CUSTOM
            updateFolderModeUi()
            tvIsrUploadStatus?.text = "\u2714 Active folder: $selected"
            showToast("Target folder set: $selected")
            log("ISR: Active S3 folder selected from dropdown \u2192 $selected")
        }
        // ─────────────────────────────────────────────────────────────────

        // ── CONNECT TO CEPH / S3 ─────────────────────────────────────────
        btnConnectCeph?.setOnClickListener {
            val url = etS3ServerUrl?.text.toString().trim()
            val ak  = etS3AccessKey?.text.toString().trim()
            val sk  = etS3SecretKey?.text.toString().trim()
            val reg = etS3Region?.text.toString().trim()

            if (url.isEmpty() || ak.isEmpty() || sk.isEmpty()) {
                showToast("Fill in S3 URL, Access Key, and Secret Key first")
                return@setOnClickListener
            }

            // Save credentials first, then test connection by listing folders
            com.dji.recreate2.aws.S3UploadManager.saveS3ServerUrl(this, url)
            com.dji.recreate2.aws.S3UploadManager.saveCredentials(this, ak, sk, if (reg.isEmpty()) "BT" else reg)

            tvIsrUploadStatus?.text = "Connecting to Ceph S3…"
            pbIsrUpload?.visibility = View.VISIBLE
            pbIsrUpload?.progress   = 30
            btnConnectCeph?.isEnabled = false
            btnConnectCeph?.text = "🔗 CONNECTING…"

            com.dji.recreate2.aws.S3UploadManager.listRemoteFolders(
                context   = this,
                onSuccess = { folders ->
                    runOnUiThread {
                        pbIsrUpload?.progress   = 100
                        pbIsrUpload?.postDelayed({ pbIsrUpload?.visibility = View.GONE }, 2000)
                        btnConnectCeph?.isEnabled = true
                        btnConnectCeph?.text = "🔗 CONNECT TO CEPH / S3"
                        tvIsrUploadStatus?.text = "✔ Connected! ${folders.size} folder(s) found"
                        showToast("Ceph S3 connected! ${folders.size} folders found")
                        log("Ceph S3 connection OK — URL=$url, folders: $folders")
                        // Refresh the spinner too
                        folderList.clear()
                        if (folders.isEmpty()) folderList.add("(no folders found)") else folderList.addAll(folders)
                        folderAdapter.notifyDataSetChanged()
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        pbIsrUpload?.visibility = View.GONE
                        btnConnectCeph?.isEnabled = true
                        btnConnectCeph?.text = "🔗 CONNECT TO CEPH / S3"
                        tvIsrUploadStatus?.text = "✗ Connection failed: $err"
                        showToast("Ceph S3 connection failed: $err")
                        log("Ceph S3 connection error: $err")
                    }
                }
            )
        }

        // ── SELECT LOCAL FOLDER ──────────────────────────────────────────
        btnPickLocalFolder?.setOnClickListener {
            // Use Android Storage Access Framework to pick a directory
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
                // Store reference to dialog field so onActivityResult can populate it
                pendingLocalFolderEditText = etLocalFolderPath
                startActivityForResult(intent, REQUEST_CODE_PICK_LOCAL_FOLDER)
            } catch (e: Exception) {
                showToast("Folder picker not available: ${e.message}")
                log("SAF folder picker error: ${e.message}")
            }
        }

        // Save path when user edits it manually
        etLocalFolderPath?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val path = etLocalFolderPath.text.toString().trim()
                if (path.isNotEmpty()) {
                    prefs.edit().putString("isrLocalFolderPath", path).apply()
                    tvIsrUploadStatus?.text = "✔ Local folder: $path"
                    log("ISR local folder path saved: $path")
                }
            }
        }

        
        btnWebOdmConfig?.setOnClickListener {
            dialog.dismiss()
            showWebOdmConfigDialog()
        }

        val btnGpsTagsConfig = dialog.findViewById<android.widget.Button>(R.id.btnGpsTagsConfig)
        btnGpsTagsConfig?.setOnClickListener {
            dialog.dismiss()
            showGpsTaggingDialog()
        }

        // CONFINED SPACE & GPS-DENIED FLIGHT BINDS
        val btnGpsDeniedMode = dialog.findViewById<android.widget.Button>(R.id.btnGpsDeniedMode)
        val btnConfinedSpaceMode = dialog.findViewById<android.widget.Button>(R.id.btnConfinedSpaceMode)
        val btnStealthMode = dialog.findViewById<android.widget.Button>(R.id.btnStealthMode)

        fun updateGpsDeniedUi() {
            val enabled = com.dji.recreate2.flight.ConfinedSpaceFlightManager.isGpsDeniedModeEnabled
            btnGpsDeniedMode?.text = if (enabled) "GPS-DENIED MODE: ON (INDOOR VPS)" else "GPS-DENIED MODE: OFF (OUTDOOR GPS)"
            btnGpsDeniedMode?.setTextColor(if (enabled) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.WHITE)
        }

        fun updateConfinedSpaceUi() {
            val enabled = com.dji.recreate2.flight.ConfinedSpaceFlightManager.isConfinedSpaceModeEnabled
            val dist = com.dji.recreate2.flight.ConfinedSpaceFlightManager.obstacleBrakeDistanceMeters
            btnConfinedSpaceMode?.text = if (enabled) "CONFINED SPACE MODE: TIGHT (${dist}m BRAKE)" else "CONFINED SPACE MODE: NORMAL (10m BRAKE)"
            btnConfinedSpaceMode?.setTextColor(if (enabled) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.WHITE)
        }

        val btnBellyLamp = dialog.findViewById<android.widget.Button>(R.id.btnBellyLamp)

        fun updateStealthUi() {
            btnStealthMode?.text = if (isStealthModeEnabled) "🥷 STEALTH MODE: ENABLED (LEDS OFF)" else "STEALTH MODE (BELLY LAMP & LEDS): OFF"
            btnStealthMode?.setTextColor(if (isStealthModeEnabled) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.WHITE)
        }

        fun updateBellyLampUi() {
            btnBellyLamp?.text = if (isBellyLampOn) "💡 BELLY LANDING LAMP: ON" else "💡 BELLY LANDING LAMP: OFF"
            btnBellyLamp?.setTextColor(if (isBellyLampOn) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.WHITE)
        }

        val btnDistanceLimit = dialog.findViewById<android.widget.Button>(R.id.btnDistanceLimit)

        fun updateDistanceLimitUi() {
            btnDistanceLimit?.text = if (!isDistanceLimitEnabled) {
                "🌐 DISTANCE LIMIT: UNLIMITED (NO LIMIT)"
            } else {
                "🌐 DISTANCE LIMIT: LIMITED (${maxFlightDistanceMeters}m)"
            }
            btnDistanceLimit?.setTextColor(if (!isDistanceLimitEnabled) android.graphics.Color.parseColor("#00FF66") else android.graphics.Color.WHITE)
        }

        updateGpsDeniedUi()
        updateConfinedSpaceUi()
        updateStealthUi()
        updateBellyLampUi()
        updateDistanceLimitUi()

        btnGpsDeniedMode?.setOnClickListener {
            val next = !com.dji.recreate2.flight.ConfinedSpaceFlightManager.isGpsDeniedModeEnabled
            com.dji.recreate2.flight.ConfinedSpaceFlightManager.setGpsDeniedMode(this, next)
            updateGpsDeniedUi()
            showToast("GPS-Denied Indoor Mode: ${if (next) "ENABLED (VPS ACTIVE)" else "DISABLED"}")
        }

        btnConfinedSpaceMode?.setOnClickListener {
            val next = !com.dji.recreate2.flight.ConfinedSpaceFlightManager.isConfinedSpaceModeEnabled
            com.dji.recreate2.flight.ConfinedSpaceFlightManager.setConfinedSpaceMode(this, next, brakeDistance = 1.0)
            updateConfinedSpaceUi()
            showToast("Confined Space Mode: ${if (next) "ENABLED (1.0m BRAKE)" else "NORMAL (10m BRAKE)"}")
        }

        btnStealthMode?.setOnClickListener {
            toggleStealthMode()
            updateStealthUi()
            updateBellyLampUi()
        }

        btnBellyLamp?.setOnClickListener {
            setBellyLamp(!isBellyLampOn)
            updateBellyLampUi()
        }

        btnDistanceLimit?.setOnClickListener {
            val nextEnabled = !isDistanceLimitEnabled
            setDistanceLimitation(enabled = nextEnabled, distanceMeters = if (nextEnabled) 5000 else 50000)
            updateDistanceLimitUi()
        }
        
        btnConnectServer?.setOnClickListener {
            val rawIp = etServerIp?.text.toString().trim()
            val port = etServerPort?.text.toString().trim()
            val user = etMqttUser?.text.toString().trim()
            val pass = etMqttPass?.text.toString()
            if (rawIp.isNotEmpty() && port.isNotEmpty()) {
                val cleanIp = rawIp.removePrefix("tcp://").removePrefix("ssl://").removePrefix("ws://").removePrefix("wss://").trim()
                val fullAddress = if (rawIp.startsWith("ssl://") || rawIp.startsWith("ws://") || rawIp.startsWith("wss://")) {
                    if (rawIp.contains(":")) rawIp else "$rawIp:$port"
                } else {
                    "tcp://$cleanIp:$port"
                }
                
                sharedPrefs.edit()
                    .putString("mqttServerIp", rawIp)
                    .putString("mqttServerPort", port)
                    .putString("mqttServerAddress", fullAddress)
                    .putString("mqttUser", user)
                    .putString("mqttPass", pass)
                    .apply()
                    
                showToast("Connecting to $fullAddress...")
                log("Attempting MQTT connection to: $fullAddress (User: $user)")
                mqttService.connect(fullAddress)
            }
        }
        
        val dLogText = dialog.findViewById<TextView>(R.id.logText)
        val dBtnClose = dialog.findViewById<TextView>(R.id.btnCloseSystem)
        
        val dBtnTouchAction = dialog.findViewById<TextView>(R.id.btnTouchAction)
        val dBtnAutoSens = dialog.findViewById<TextView>(R.id.btnAutoSens)
        
        val tvSNInfo = dialog.findViewById<TextView>(R.id.tvSNInfo)
        
        val fcSnKey = KeyTools.createKey(FlightControllerKey.KeySerialNumber)
        val rcSnKey = KeyTools.createKey(RemoteControllerKey.KeySerialNumber)
        val camSnKey = KeyTools.createKey(CameraKey.KeySerialNumber)
        
        var snText = "[ SYSTEM SERIAL NUMBERS ]\n"
        snText += "DRN SN: ${KeyManager.getInstance().getValue(fcSnKey) ?: "UNKNOWN"}\n"
        snText += " RC SN: ${KeyManager.getInstance().getValue(rcSnKey) ?: "UNKNOWN"}\n"
        snText += "CAM SN: ${KeyManager.getInstance().getValue(camSnKey) ?: "UNKNOWN"}"
        
        tvSNInfo.text = snText
        
        btnPair = dBtnPair
        logText = dLogText
        dLogText.text = logHistory.toString()
        
        dBtnGimbalSens.text = String.format("[ GIMBAL SENSITIVITY: %.1f ]", swipeSensitivity)
        dBtnFlightSens.text = String.format("[ FLIGHT SENSITIVITY: %.1f ]", flightSensitivity)
        dBtnInvertVertical.text = if (invertVertical) "[ INVERT Y-AXIS: ON ]" else "[ INVERT Y-AXIS: OFF ]"
        dBtnInvertHorizontal.text = if (invertHorizontal) "[ INVERT X-AXIS: ON ]" else "[ INVERT X-AXIS: OFF ]"
        dBtnRthAltitude.text = "[ RTH ALTITUDE: ${rthAltitude}m ]"
        dBtnObstacleAction.text = "[ OBS ACTION: " + when(obstacleAction) {
            0 -> "BRAKE ]"
            1 -> "BYPASS ]"
            else -> "OFF ]"
        }
        dBtnSignalLossAction?.text = "[ SIGNAL LOSS ACTION: " + when(signalLossAction) {
            0 -> "GO HOME ]"
            1 -> "LANDING ]"
            2 -> "HOVER ]"
            else -> "GO HOME ]"
        }
        dBtnToggleRadar.text = if (radarEnabled) "[ OBSTACLE RADAR: ON ]" else "[ OBSTACLE RADAR: OFF ]"
        dBtnRadarDistance.text = "[ RADAR DISTANCE: ${radarMaxDistance.toInt()}m ]"
        
        dBtnSimulator.text = if (isSimulatorActive) "[ HARDWARE SIMULATOR: ON ]" else "[ HARDWARE SIMULATOR: OFF ]"
        dBtnSimulator.setOnClickListener {
            if (isSimulatorActive) {
                dji.v5.manager.aircraft.simulator.SimulatorManager.getInstance().disableSimulator(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        runOnUiThread {
                            isSimulatorActive = false
                            dBtnSimulator.text = "[ HARDWARE SIMULATOR: OFF ]"
                            dTvSimulatorStatus?.visibility = View.GONE
                            dTvSimulatorStatus?.clearAnimation()
                            showToast("Simulator Disabled")
                        }
                    }
                    override fun onFailure(error: IDJIError) {
                        showToast("Failed to Disable Simulator: ${error.errorCode()}")
                    }
                })
            } else {
                val initData = dji.v5.manager.aircraft.simulator.InitializationSettings.createInstance(
                    dji.sdk.keyvalue.value.common.LocationCoordinate2D(if (droneLat.isNaN()) 34.0522 else droneLat, if (droneLon.isNaN()) -118.2437 else droneLon),
                    15
                )
                dji.v5.manager.aircraft.simulator.SimulatorManager.getInstance().enableSimulator(initData, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        runOnUiThread {
                            isSimulatorActive = true
                            dBtnSimulator.text = "[ HARDWARE SIMULATOR: ON ]"
                            dTvSimulatorStatus?.visibility = View.VISIBLE
                            dTvSimulatorStatus?.let { startBlinkingAnimation(it) }
                            showToast("Simulator Enabled")
                        }
                    }
                    override fun onFailure(error: IDJIError) {
                        showToast("Failed to Enable Simulator: ${error.errorCode()}")
                    }
                })
            }
        }
        
        dBtnTouchAction.text = "[ TOUCH ACTION: ${currentTouchAction.name} ]"
        dBtnTouchAction.setOnClickListener {
            currentTouchAction = if (currentTouchAction == TouchAction.GIMBAL) TouchAction.FOCUS else TouchAction.GIMBAL
            dBtnTouchAction.text = "[ TOUCH ACTION: ${currentTouchAction.name} ]"
            saveConfig()
        }
        
        dBtnAutoSens.text = "[ AUTO SENSITIVITY: ${if (autoSensitivity) "ON" else "OFF"} ]"
        dBtnAutoSens.setOnClickListener {
            autoSensitivity = !autoSensitivity
            dBtnAutoSens.text = "[ AUTO SENSITIVITY: ${if (autoSensitivity) "ON" else "OFF"} ]"
            saveConfig()
        }

        dBtnPair.setOnClickListener { togglePairing() }
        
        dBtnGimbalSens.setOnClickListener {
            swipeSensitivity += 0.1f
            if (swipeSensitivity > 0.55f) swipeSensitivity = 0.1f
            dBtnGimbalSens.text = String.format("[ GIMBAL SENSITIVITY: %.1f ]", swipeSensitivity)
            saveConfig()
        }
        
        dBtnFlightSens.setOnClickListener {
            flightSensitivity += 0.2f
            if (flightSensitivity > 1.05f) flightSensitivity = 0.2f
            dBtnFlightSens.text = String.format("[ FLIGHT SENSITIVITY: %.1f ]", flightSensitivity)
            saveConfig()
        }
        
        dBtnInvertVertical.setOnClickListener {
            invertVertical = !invertVertical
            dBtnInvertVertical.text = if (invertVertical) "[ INVERT Y-AXIS: ON ]" else "[ INVERT Y-AXIS: OFF ]"
            saveConfig()
        }
        
        dBtnInvertHorizontal.setOnClickListener {
            invertHorizontal = !invertHorizontal
            dBtnInvertHorizontal.text = if (invertHorizontal) "[ INVERT X-AXIS: ON ]" else "[ INVERT X-AXIS: OFF ]"
            saveConfig()
        }
        
        dBtnRthAltitude.setOnClickListener {
            rthAltitude += 50
            if (rthAltitude > 200) rthAltitude = 50
            dBtnRthAltitude.text = "[ RTH ALTITUDE: ${rthAltitude}m ]"
            saveConfig()
            val rthHeightKey = KeyTools.createKey(FlightControllerKey.KeyGoHomeHeight)
            KeyManager.getInstance().setValue(rthHeightKey, rthAltitude, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { log("RTH Altitude set to $rthAltitude m") }
                override fun onFailure(error: IDJIError) { log("Failed to set RTH Alt: ${error.errorCode()}") }
            })
        }
        
        dBtnObstacleAction.setOnClickListener {
            obstacleAction = (obstacleAction + 1) % 3
            val type = when (obstacleAction) {
                0 -> ObstacleAvoidanceType.BRAKE
                1 -> ObstacleAvoidanceType.BYPASS
                else -> ObstacleAvoidanceType.CLOSE
            }
            dBtnObstacleAction.text = "[ OBS ACTION: " + when(obstacleAction) {
                0 -> "BRAKE ]"
                1 -> "BYPASS ]"
                else -> "OFF ]"
            }
            saveConfig()
            PerceptionManager.getInstance().setObstacleAvoidanceType(type, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { log("Obstacle Action set to $type") }
                override fun onFailure(error: IDJIError) { log("Failed to set Obstacle Action: ${error.errorCode()}") }
            })
        }

        dBtnSignalLossAction?.setOnClickListener {
            signalLossAction = (signalLossAction + 1) % 3
            dBtnSignalLossAction.text = "[ SIGNAL LOSS ACTION: " + when(signalLossAction) {
                0 -> "GO HOME ]"
                1 -> "LANDING ]"
                2 -> "HOVER ]"
                else -> "GO HOME ]"
            }
            saveConfig()
            val failsafeKey = KeyTools.createKey(FlightControllerKey.KeyFailsafeAction)
            val failsafeValue = when (signalLossAction) {
                0 -> dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.GOHOME
                1 -> dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.LANDING
                2 -> dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.HOVER
                else -> dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.GOHOME
            }
            KeyManager.getInstance().setValue(failsafeKey, failsafeValue, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() { log("Signal Loss Action set to $failsafeValue") }
                override fun onFailure(error: IDJIError) { log("Failed to set Signal Loss Action: ${error.errorCode()}") }
            })
        }
        
        dBtnToggleRadar.setOnClickListener {
            radarEnabled = !radarEnabled
            dBtnToggleRadar.text = if (radarEnabled) "[ OBSTACLE RADAR: ON ]" else "[ OBSTACLE RADAR: OFF ]"
            obstacleRadar.setRadarEnabled(radarEnabled)
            saveConfig()
        }
        
        dBtnRadarDistance.setOnClickListener {
            radarMaxDistance += 5.0
            if (radarMaxDistance > 30.0) radarMaxDistance = 5.0
            dBtnRadarDistance.text = "[ RADAR DISTANCE: ${radarMaxDistance.toInt()}m ]"
            obstacleRadar.maxRadarDistance = radarMaxDistance
            saveConfig()
        }
        
        dialog.setOnDismissListener {
            logText = null
            btnPair = null
            dTvDroneModel = null
            dTvDroneStatus = null
            dTvRCStatus = null
            dTvEngineStatus = null
            dTvSimulatorStatus = null
        }

        dBtnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    private fun showToast(msg: String) {
        runOnUiThread {
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logEntry = "[$timestamp] $message\n"
        logHistory.append(logEntry)
        
        if (logHistory.length > 5000) {
            logHistory.delete(0, logHistory.length - 5000)
        }
        
        runOnUiThread {
            logText?.append(logEntry)
            if (logText != null) {
                val scrollAmount = (logText!!.layout?.getLineTop(logText!!.lineCount) ?: 0) - logText!!.height // H-06: fix operator precedence bug
                if (scrollAmount > 0) logText!!.scrollTo(0, scrollAmount)
            }
        }
    }

    private fun publishCommandReceipt(
        transactionId: String?,
        commandName: String,
        status: String,
        errorCode: Any? = null,
        errorMessage: String? = null
    ) {
        if (!::mqttService.isInitialized) return
        try {
            val receipt = org.json.JSONObject()
            receipt.put("event", "COMMAND_RECEIPT")
            receipt.put("timestamp", System.currentTimeMillis())
            receipt.put("command", commandName)
            receipt.put("status", status)
            if (transactionId != null) {
                receipt.put("transaction_id", transactionId)
            }
            if (errorCode != null) {
                receipt.put("error_code", errorCode)
            }
            if (errorMessage != null) {
                receipt.put("error_message", errorMessage)
            }
            mqttService.publishMission(jsonPayload = receipt.toString())
        } catch (e: Exception) {
            android.util.Log.e("CommandReceipt", "Failed to publish receipt: ${e.message}", e)
        }
    }

    private fun publishWaypointsUpdate() {
        if (!::mqttService.isInitialized) return
        try {
            val json = org.json.JSONObject()
            json.put("event", "WAYPOINTS_UPDATED")
            val wpsArray = org.json.JSONArray()
            for (wp in tacticalWaypoints) {
                val wpObj = org.json.JSONObject()
                wpObj.put("name", wp.name)
                wpObj.put("lat", wp.geoPoint.latitude)
                wpObj.put("lng", wp.geoPoint.longitude)
                wpObj.put("alt", wp.altitude)
                wpObj.put("speed", wp.speed)
                wpObj.put("movementMethod", wp.movementMethod)
                wpObj.put("actionType", wp.actionType)
                if (wp.poiTarget != null) {
                    wpObj.put("poiLat", wp.poiTarget?.latitude)
                    wpObj.put("poiLng", wp.poiTarget?.longitude)
                }
                wpsArray.put(wpObj)
            }
            json.put("waypoints", wpsArray)
            mqttService.publishMission(jsonPayload = json.toString())
        } catch (e: Exception) {
            log("Error publishing waypoints update: ${e.message}")
        }
    }

    private fun handleMqttCommand(json: org.json.JSONObject) {
        lastGcsHeartbeatTime = System.currentTimeMillis()
        var command = "UNKNOWN"
        var transactionId: String? = null
        try {
            if (json.has("transaction_id")) {
                transactionId = json.getString("transaction_id")
            }

            // Check for top-level isr_mode setting: 1, 2, or NONE/0
            if (json.has("isr_mode")) {
                val modeVal = json.opt("isr_mode")?.toString()?.uppercase() ?: ""
                val targetMode = when (modeVal) {
                    "1", "MODE1" -> "MODE1"
                    "2", "MODE2" -> "MODE2"
                    "0", "NONE", "OFF", "DISABLE" -> "NONE"
                    else -> modeVal
                }
                if (targetMode.isNotEmpty()) {
                    com.dji.recreate2.sync.ISRModeManager.setMode(targetMode, this)
                    runOnUiThread { showToast("C2: ISR Mode set to $targetMode") }
                }
            }

            if (json.has("command")) {
                command = json.getString("command").trim().uppercase()
            } else if (json.has("isr_mode")) {
                publishCommandReceipt(transactionId, "SET_ISR_MODE", "COMPLETED")
                return
            }

            log("C2 Received: $command")
            publishCommandReceipt(transactionId, command, "ACCEPTED")
            when (command) {
                "ADD_WAYPOINT" -> {
                        val lat = json.getDouble("lat")
                        val lon = json.getDouble("lon")
                        val alt = json.optDouble("alt", 50.0)
                        val wpName = if (json.has("name")) json.getString("name") else "waypoint_${tacticalWaypoints.size + 1}"
                        val wp = TacticalWaypoint(org.osmdroid.util.GeoPoint(lat, lon), name = wpName, altitude = alt)
                        
                        if (json.has("heading")) wp.heading = json.getDouble("heading")
                        if (json.has("dwellTime")) wp.dwellTime = json.getDouble("dwellTime")
                        if (json.has("movementMethod")) wp.movementMethod = json.getString("movementMethod")
                        if (json.has("orbitRadius")) wp.orbitRadius = json.getDouble("orbitRadius")
                        if (json.has("orbitLoops")) wp.orbitLoops = json.getInt("orbitLoops")
                        
                        if (json.has("actionType")) {
                            wp.actionType = json.getString("actionType")
                            if (json.has("poiLat") && json.has("poiLng")) {
                                wp.poiTarget = org.osmdroid.util.GeoPoint(json.getDouble("poiLat"), json.getDouble("poiLng"))
                            }
                            if (json.has("gimbalPitch")) {
                                wp.gimbalPitch = json.getDouble("gimbalPitch")
                            }
                        }
                        
                        runOnUiThread {
                            tacticalWaypoints.add(wp)
                            
                            val marker = org.osmdroid.views.overlay.Marker(mapView)
                            marker.position = wp.geoPoint
                            marker.icon = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_nato_waypoint)
                            marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                            val methodSuffix = if (wp.movementMethod != "linear") " (${wp.movementMethod.uppercase()})" else ""
                            val actionSuffix = if (wp.actionType != "FLY") " [${wp.actionType}]" else ""
                            marker.title = "${wp.name}$methodSuffix$actionSuffix"
                            marker.setOnMarkerClickListener { m, _ ->
                                showWaypointActionDialog(wp, m as org.osmdroid.views.overlay.Marker)
                                true
                            }
                            wp.osmdroidMarker = marker
                            mapView.overlays.add(marker)
                            updateFlightPathLine()
                            mapView.invalidate()
                            publishWaypointsUpdate()
                            showToast("C2: Added Waypoint")
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "UPLOAD_MISSION" -> {
                        runOnUiThread {
                            tacticalWaypoints.forEach { wp ->
                                wp.osmdroidMarker?.let { mapView.overlays.remove(it) }
                            }
                            tacticalWaypoints.clear()
                            
                            val wps = json.getJSONArray("waypoints")
                            for (i in 0 until wps.length()) {
                                val wpData = wps.getJSONObject(i)
                                val lat = wpData.getDouble("lat")
                                val lon = wpData.getDouble("lng")
                                val alt = wpData.optDouble("alt", 50.0)
                                val speed = wpData.optDouble("speed", 10.0)
                                val wpName = if (wpData.has("name")) wpData.getString("name") else "waypoint_${tacticalWaypoints.size + 1}"
                                
                                val wp = TacticalWaypoint(org.osmdroid.util.GeoPoint(lat, lon), name = wpName, altitude = alt, speed = speed)
                                
                                if (wpData.has("heading")) wp.heading = wpData.getDouble("heading")
                                if (wpData.has("dwellTime")) wp.dwellTime = wpData.getDouble("dwellTime")
                                if (wpData.has("movementMethod")) wp.movementMethod = wpData.getString("movementMethod")
                                if (wpData.has("orbitRadius")) wp.orbitRadius = wpData.getDouble("orbitRadius")
                                if (wpData.has("orbitLoops")) wp.orbitLoops = wpData.getInt("orbitLoops")
                                
                                if (wpData.has("actionType")) {
                                    wp.actionType = wpData.getString("actionType")
                                    if (wpData.has("poiLat") && wpData.has("poiLng")) {
                                        wp.poiTarget = org.osmdroid.util.GeoPoint(wpData.getDouble("poiLat"), wpData.getDouble("poiLng"))
                                    }
                                    if (wpData.has("gimbalPitch")) {
                                        wp.gimbalPitch = wpData.getDouble("gimbalPitch")
                                    }
                                }
                                
                                tacticalWaypoints.add(wp)
                                
                                val marker = org.osmdroid.views.overlay.Marker(mapView)
                                marker.position = wp.geoPoint
                                marker.icon = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_nato_waypoint)
                                marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
                                val methodSuffix = if (wp.movementMethod != "linear") " (${wp.movementMethod.uppercase()})" else ""
                                val actionSuffix = if (wp.actionType != "FLY") " [${wp.actionType}]" else ""
                                marker.title = "${wp.name}$methodSuffix$actionSuffix"
                                marker.setOnMarkerClickListener { m, _ ->
                                    showWaypointActionDialog(wp, m as org.osmdroid.views.overlay.Marker)
                                    true
                                }
                                wp.osmdroidMarker = marker
                                mapView.overlays.add(marker)
                            }
                            updateFlightPathLine()
                            mapView.invalidate()
                            publishWaypointsUpdate()
                            showToast("C2: Uploaded Mission with ${tacticalWaypoints.size} WPs")
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "EXECUTE_MISSION" -> {
                        executeTacticalMission(transactionId)
                        showToast("C2: Executing Mission")
                    }
                    "TAKE_OFF", "AUTO_TAKEOFF", "TAKEOFF" -> {
                        executeTakeoff(transactionId)
                        showToast("C2: Executing Auto Take-Off")
                    }
                    "LAND" -> {
                        executeLanding(transactionId)
                        showToast("C2: Landing Initiated")
                    }
                    "RTH" -> {
                        executeReturnToHome(transactionId)
                        showToast("C2: Return to Home Initiated")
                    }
                    "START_ENGINE", "ARM" -> {
                        val cmdAlias = if (command == "ARM") "ARM" else "START_ENGINE"
                        publishCommandReceipt(transactionId, cmdAlias, "EXECUTING")
                        startEngineUsingVirtualStick(transactionId, cmdAlias)
                        showToast("C2: Executing Start Engine / ARM")
                    }
                    "DISARM" -> {
                        publishCommandReceipt(transactionId, "DISARM", "EXECUTING")
                        stopEngineUsingVirtualStick(transactionId)
                        showToast("C2: Executing Stop Engine / DISARM")
                    }
                    "TIMESYNC" -> {
                        val ts1 = json.optLong("ts1", 0L)
                        val reply = org.json.JSONObject()
                        reply.put("event", "TIMESYNC")
                        reply.put("ts1", ts1)
                        reply.put("tc1", System.currentTimeMillis() * 1000L) // microseconds approx
                        mqttService.publishMission(jsonPayload = reply.toString())
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "SET_MAPPING_MODE" -> {
                        val modeStr = json.getString("mode")
                        activeMappingMode = if (modeStr == "QUICK") MappingMode.QUICK else MappingMode.PROFESSIONAL
                        
                        runOnUiThread {
                            val spnMapMode = findViewById<android.widget.Spinner>(R.id.spnMapMode)
                            spnMapMode.setSelection(if (activeMappingMode == MappingMode.QUICK) 1 else 0)
                            showToast("C2: Mapping Mode Set to $activeMappingMode")
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "SET_S3_CONFIG", "UPDATE_S3_CONFIG", "SET_STORAGE_CONFIG" -> {
                        val serverUrl = json.optString("serverUrl", json.optString("s3Url"))
                        val accessKey = json.optString("accessKey", json.optString("ak"))
                        val secretKey = json.optString("secretKey", json.optString("sk"))
                        val region = json.optString("region", "BT")
                        val folderMode = json.optString("folderMode", json.optString("mode"))
                        val customFolder = json.optString("customFolder", json.optString("folder"))
                        val localFolder = json.optString("localFolder")
                        val isrMode = json.optString("isrMode")

                        if (serverUrl.isNotEmpty()) {
                            com.dji.recreate2.aws.S3UploadManager.saveS3ServerUrl(this, serverUrl)
                        }
                        if (accessKey.isNotEmpty() && secretKey.isNotEmpty()) {
                            com.dji.recreate2.aws.S3UploadManager.saveCredentials(this, accessKey, secretKey, if (region.isEmpty()) "BT" else region)
                        }
                        if (folderMode.isNotEmpty() || customFolder.isNotEmpty()) {
                            val mode = if (folderMode.equals("CUSTOM", ignoreCase = true)) com.dji.recreate2.aws.S3UploadManager.FOLDER_MODE_CUSTOM else com.dji.recreate2.aws.S3UploadManager.FOLDER_MODE_AUTO
                            com.dji.recreate2.aws.S3UploadManager.saveFolderConfig(this, mode, customFolder)
                        }
                        if (localFolder.isNotEmpty()) {
                            com.dji.recreate2.sync.PostFlightS3Sync.saveLocalFetchFolderName(this, localFolder)
                        }
                        if (isrMode.isNotEmpty()) {
                            com.dji.recreate2.sync.ISRModeManager.setMode(isrMode.uppercase(), this)
                        }

                        runOnUiThread {
                            showToast("C2: S3 & Storage Config Updated")
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "CREATE_FOLDER", "CREATE_S3_FOLDER", "MKDIR" -> {
                        val folderName = json.optString("folderName", json.optString("folder", json.optString("name")))
                        val target = json.optString("target", "ALL").uppercase()

                        if (folderName.isEmpty()) {
                            runOnUiThread { showToast("C2 Error: Folder name missing") }
                            publishCommandReceipt(transactionId, command, "FAILED", errorCode = -1, errorMessage = "Folder name missing")
                        } else {
                            publishCommandReceipt(transactionId, command, "EXECUTING")

                            if (target == "LOCAL" || target == "ALL") {
                                com.dji.recreate2.sync.PostFlightS3Sync.saveLocalFetchFolderName(this, folderName)
                                com.dji.recreate2.sync.PostFlightS3Sync.getLocalFetchDirectory(this)
                            }

                            if (target == "S3" || target == "ALL") {
                                com.dji.recreate2.aws.S3UploadManager.createRemoteFolder(
                                    this,
                                    folderName,
                                    onSuccess = {
                                        runOnUiThread { showToast("C2: Created Folder '$folderName'") }
                                        publishCommandReceipt(transactionId, command, "COMPLETED")
                                    },
                                    onError = { err ->
                                        runOnUiThread { showToast("C2 Folder Error: $err") }
                                        publishCommandReceipt(transactionId, command, "FAILED", errorCode = -2, errorMessage = err)
                                    }
                                )
                            } else {
                                runOnUiThread { showToast("C2: Created Local Folder '$folderName'") }
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                        }
                    }
                    "START_TASK", "ASSIGN_TASK" -> {
                        val taskId = json.optString("taskId", json.optString("id", "task_" + System.currentTimeMillis()))
                        val taskName = json.optString("taskName", taskId)
                        val taskType = json.optString("taskType", "ISR")
                        val waypoints = if (json.has("waypoints")) json.toString() else null

                        val task = com.dji.recreate2.task.DroneTask(
                            taskId = taskId,
                            taskName = taskName,
                            taskType = taskType,
                            waypointsJson = waypoints
                        )

                        com.dji.recreate2.task.DroneTaskManager.startTask(this, task, executeNow = true)
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "QUEUE_TASK" -> {
                        val taskId = json.optString("taskId", json.optString("id", "task_" + System.currentTimeMillis()))
                        val taskName = json.optString("taskName", taskId)
                        val taskType = json.optString("taskType", "ISR")
                        val waypoints = if (json.has("waypoints")) json.toString() else null

                        val task = com.dji.recreate2.task.DroneTask(
                            taskId = taskId,
                            taskName = taskName,
                            taskType = taskType,
                            waypointsJson = waypoints
                        )

                        com.dji.recreate2.task.DroneTaskManager.enqueueTask(this, task)
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "CANCEL_TASK" -> {
                        val taskId = json.optString("taskId", json.optString("id"))
                        if (taskId.isNotEmpty()) {
                            val cancelled = com.dji.recreate2.task.DroneTaskManager.cancelTask(this, taskId)
                            if (cancelled) {
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            } else {
                                publishCommandReceipt(transactionId, command, "FAILED", errorCode = -404, errorMessage = "Task ID not found")
                            }
                        } else {
                            publishCommandReceipt(transactionId, command, "FAILED", errorCode = -1, errorMessage = "Missing taskId parameter")
                        }
                    }
                    "CANCEL_ALL_TASKS" -> {
                        com.dji.recreate2.task.DroneTaskManager.cancelAllTasks(this)
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "CLEAR_TASK_QUEUE" -> {
                        com.dji.recreate2.task.DroneTaskManager.clearQueue()
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "SET_GPS_DENIED_MODE" -> {
                        val enabled = json.optBoolean("enabled", true)
                        com.dji.recreate2.flight.ConfinedSpaceFlightManager.setGpsDeniedMode(this, enabled)
                        runOnUiThread {
                            showToast("C2: GPS-Denied Mode -> $enabled")
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "SET_CONFINED_SPACE_MODE" -> {
                        val enabled = json.optBoolean("enabled", true)
                        val dist = json.optDouble("brakeDistance", 1.0)
                        val maxSpeed = json.optDouble("maxSpeed", 1.0).toFloat()
                        com.dji.recreate2.flight.ConfinedSpaceFlightManager.setConfinedSpaceMode(this, enabled, dist, maxSpeed)
                        runOnUiThread {
                            showToast("C2: Confined Space Mode -> $enabled (${dist}m brake)")
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "UPLOAD_KMZ" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val base64Data = json.getString("data")
                        val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                        val file = java.io.File(getExternalFilesDir(null), "c2_imported_mission.kmz")
                        java.io.FileOutputStream(file).use { it.write(bytes) }
                        
                        if (::mqttService.isInitialized) {
                            val logObj = org.json.JSONObject()
                            logObj.put("event", "KMZ_UPLOAD_RECEIVED")
                            mqttService.publishMission(jsonPayload = logObj.toString())
                        }
                        
                        runOnUiThread { showToast("C2: KMZ Received, Pushing to Drone...") }
                        executeNativeKMZ(file.absolutePath)
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "START_KMZ" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val wpm = dji.v5.manager.aircraft.waypoint3.WaypointMissionManager.getInstance()
                        val fileName = lastLoadedKmzFileName
                        val filePath = lastLoadedKmzFilePath
                        if (fileName == null || filePath == null) {
                            runOnUiThread { showToast("C2 Error: No KMZ loaded to start.") }
                            publishCommandReceipt(transactionId, command, "FAILED", errorCode = -20, errorMessage = "No KMZ loaded")
                        } else {
                            val waylineIDs = wpm.getAvailableWaylineIDs(filePath)
                            if (waylineIDs.isNullOrEmpty()) {
                                runOnUiThread { showToast("C2 Error: No waylines found in KMZ. Invalid WPML format.") }
                                publishCommandReceipt(transactionId, command, "FAILED", errorCode = -21, errorMessage = "No waylines found in KMZ")
                                if (::mqttService.isInitialized) {
                                    val logObj = org.json.JSONObject()
                                    logObj.put("event", "KMZ_START_FAILED")
                                    logObj.put("error", "No waylines found in KMZ. Ensure it's a valid DJI WPML file.")
                                    mqttService.publishMission(jsonPayload = logObj.toString())
                                    android.util.Log.e("KMZ_SysLog", "KMZ_START_FAILED: No waylines found in $filePath")
                                }
                            } else {
                                val missionId = if (fileName.endsWith(".kmz", ignoreCase = true)) {
                                    fileName.substring(0, fileName.length - 4)
                                } else fileName

                                startKmzWithAutoTakeoff(wpm, missionId, waylineIDs, "C2")
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                        }
                    }
                    "PAUSE_KMZ" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        dji.v5.manager.aircraft.waypoint3.WaypointMissionManager.getInstance().pauseMission(object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                            override fun onSuccess() { 
                                runOnUiThread { showToast("C2: KMZ Paused") } 
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                            override fun onFailure(e: dji.v5.common.error.IDJIError) { 
                                runOnUiThread { showToast("C2: Pause failed: ${e.description()}") } 
                                publishCommandReceipt(transactionId, command, "FAILED", errorCode = e.errorCode(), errorMessage = e.description())
                            }
                        })
                    }
                    "RESUME_KMZ" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        dji.v5.manager.aircraft.waypoint3.WaypointMissionManager.getInstance().resumeMission(object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                            override fun onSuccess() { 
                                runOnUiThread { showToast("C2: KMZ Resumed") } 
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                            override fun onFailure(e: dji.v5.common.error.IDJIError) { 
                                runOnUiThread { showToast("C2: Resume failed: ${e.description()}") } 
                                publishCommandReceipt(transactionId, command, "FAILED", errorCode = e.errorCode(), errorMessage = e.description())
                            }
                        })
                    }
                    "STOP_KMZ" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val fileName = lastLoadedKmzFileName ?: ""
                        dji.v5.manager.aircraft.waypoint3.WaypointMissionManager.getInstance().stopMission(fileName, object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                            override fun onSuccess() { 
                                runOnUiThread { showToast("C2: KMZ Stopped") } 
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                            override fun onFailure(e: dji.v5.common.error.IDJIError) { 
                                runOnUiThread { showToast("C2: Stop failed: ${e.description()}") } 
                                publishCommandReceipt(transactionId, command, "FAILED", errorCode = e.errorCode(), errorMessage = e.description())
                            }
                        })
                    }
                    "DOWNLOAD_KMZ" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val url = json.getString("url")
                        runOnUiThread { showToast("C2: Downloading KMZ from Server...") }
                        
                        if (::mqttService.isInitialized) {
                            val logObj = org.json.JSONObject()
                            logObj.put("event", "KMZ_DOWNLOAD_STARTED")
                            logObj.put("url", url)
                            mqttService.publishMission(jsonPayload = logObj.toString())
                            android.util.Log.d("KMZ_SysLog", "KMZ_DOWNLOAD_STARTED: $url")
                        }
                        
                        Thread {
                            try {
                                val client = okhttp3.OkHttpClient()
                                val request = okhttp3.Request.Builder().url(url).build()
                                val response = client.newCall(request).execute()
                                
                                if (response.isSuccessful) {
                                    val fileNameFromUrl = android.net.Uri.parse(url).lastPathSegment ?: "c2_downloaded_mission.kmz"
                                    val validFileName = if (fileNameFromUrl.endsWith(".kmz", ignoreCase = true)) fileNameFromUrl else "$fileNameFromUrl.kmz"
                                    val file = java.io.File(getExternalFilesDir(null), validFileName)
                                    java.io.FileOutputStream(file).use { output ->
                                        response.body?.byteStream()?.copyTo(output)
                                    }
                                    
                                    if (::mqttService.isInitialized) {
                                        val logObj = org.json.JSONObject()
                                        logObj.put("event", "KMZ_DOWNLOAD_SUCCESS")
                                        mqttService.publishMission(jsonPayload = logObj.toString())
                                        android.util.Log.d("KMZ_SysLog", "KMZ_DOWNLOAD_SUCCESS")
                                    }
                                    
                                    runOnUiThread {
                                        showToast("C2: KMZ Downloaded. Pushing to Drone...")
                                        executeNativeKMZ(file.absolutePath)
                                    }
                                    publishCommandReceipt(transactionId, command, "COMPLETED")
                                } else {
                                    if (::mqttService.isInitialized) {
                                        val logObj = org.json.JSONObject()
                                        logObj.put("event", "KMZ_DOWNLOAD_FAILED")
                                        logObj.put("error", "HTTP ${response.code}")
                                        mqttService.publishMission(jsonPayload = logObj.toString())
                                        android.util.Log.e("KMZ_SysLog", "KMZ_DOWNLOAD_FAILED: HTTP ${response.code}")
                                    }
                                    runOnUiThread { showToast("C2: KMZ Download failed: ${response.code}") }
                                    publishCommandReceipt(transactionId, command, "FAILED", errorCode = response.code, errorMessage = "HTTP ${response.code}")
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                if (::mqttService.isInitialized) {
                                    val logObj = org.json.JSONObject()
                                    logObj.put("event", "KMZ_DOWNLOAD_FAILED")
                                    logObj.put("error", e.message)
                                    mqttService.publishMission(jsonPayload = logObj.toString())
                                    android.util.Log.e("KMZ_SysLog", "KMZ_DOWNLOAD_FAILED: ${e.message}")
                                }
                                runOnUiThread { showToast("C2: KMZ Download error: ${e.message}") }
                                publishCommandReceipt(transactionId, command, "FAILED", errorCode = -22, errorMessage = e.message)
                            }
                        }.start()
                    }
                    "CLEAR_MISSION" -> {
                        runOnUiThread {
                            tacticalWaypoints.clear()
                            val toRemove = mapView.overlays.filter { 
                                it is org.osmdroid.views.overlay.Polyline || 
                                (it is org.osmdroid.views.overlay.Marker && it.title?.startsWith("WP") == true) || 
                                (it is org.osmdroid.views.overlay.Polygon) 
                            }
                            mapView.overlays.removeAll(toRemove)
                            updateFlightPathLine()
                            mapView.invalidate()
                            showToast("C2: Mission Cleared")
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "START_RTMP", "START_STREAM", "SET_STREAM", "STREAM_START", "START_WHIP", "WHIP_START" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val rtmpUrl = json.optString("url", json.optString("rtmp_url", json.optString("whip_url", "")))
                        runOnUiThread { startRtmpStream(rtmpUrl) }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "STOP_RTMP", "STOP_STREAM", "STREAM_STOP", "STOP_WHIP", "WHIP_STOP" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        runOnUiThread { stopRtmpStream() }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "SET_HOME" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val setHomeKey = dji.sdk.keyvalue.key.KeyTools.createKey(dji.sdk.keyvalue.key.FlightControllerKey.KeyHomeLocationUsingCurrentAircraftLocation)
                        dji.v5.manager.KeyManager.getInstance().performAction(setHomeKey, object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                            override fun onSuccess(p0: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                                runOnUiThread { showToast("C2: Home Point Updated") }
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                                runOnUiThread { showToast("C2 Home Error: ${error.description()}") }
                                publishCommandReceipt(transactionId, command, "FAILED", errorCode = error.errorCode(), errorMessage = error.description())
                            }
                        })
                    }
                    "PHOTO", "PHOTO_CAPTURE", "TAKE_PHOTO" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        setCameraMode(dji.sdk.keyvalue.value.camera.CameraMode.PHOTO_NORMAL) {  
                            val key = dji.sdk.keyvalue.key.KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyStartShootPhoto, dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN)
                            dji.v5.manager.KeyManager.getInstance().performAction(key, object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                                override fun onSuccess(p0: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                                    runOnUiThread { showToast("C2: Photo Captured") }
                                    publishCommandReceipt(transactionId, command, "COMPLETED")
                                }
                                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                                    runOnUiThread { showToast("C2 Photo Error: ${error.description() ?: error.errorCode()}") }
                                    publishCommandReceipt(transactionId, command, "FAILED", errorCode = error.errorCode(), errorMessage = error.description())
                                }
                            })
                        }
                    }
                    "RECORD_START", "START_RECORD", "START_RECORDING", "START" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        setCameraMode(dji.sdk.keyvalue.value.camera.CameraMode.VIDEO_NORMAL) {
                            val key = dji.sdk.keyvalue.key.KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyStartRecord, dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN)
                            dji.v5.manager.KeyManager.getInstance().performAction(key, object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                                override fun onSuccess(p0: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                                    isRecording = true
                                    runOnUiThread { 
                                        findViewById<android.widget.TextView>(R.id.btnRecord)?.text = "REC"
                                        findViewById<android.widget.TextView>(R.id.btnRecord)?.setTextColor(android.graphics.Color.RED)
                                        showToast("C2: Video Recording Started") 
                                    }
                                    log("Recording Started")
                                    publishCommandReceipt(transactionId, command, "COMPLETED")
                                }
                                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                                    runOnUiThread { showToast("C2 Record Error: ${error.description() ?: error.errorCode()}") }
                                    publishCommandReceipt(transactionId, command, "FAILED", errorCode = error.errorCode(), errorMessage = error.description())
                                }
                            })
                        }
                    }
                    "RECORD_STOP", "STOP_RECORD", "STOP_RECORDING", "STOP" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val key = dji.sdk.keyvalue.key.KeyTools.createKey(dji.sdk.keyvalue.key.CameraKey.KeyStopRecord, dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN)
                        dji.v5.manager.KeyManager.getInstance().performAction(key, object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                            override fun onSuccess(p0: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                                isRecording = false
                                runOnUiThread { 
                                    findViewById<android.widget.TextView>(R.id.btnRecord)?.text = "REC"
                                    findViewById<android.widget.TextView>(R.id.btnRecord)?.setTextColor(android.graphics.Color.GREEN)
                                    showToast("C2: Video Recording Stopped") 
                                }
                                log("Recording Stopped")
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                                runOnUiThread { showToast("C2 Stop Record Error: ${error.description() ?: error.errorCode()}") }
                                publishCommandReceipt(transactionId, command, "FAILED", errorCode = error.errorCode(), errorMessage = error.description())
                            }
                        })
                    }
                    "GIMBAL" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val pitch = json.optDouble("pitch", 0.0)
                        val yaw = json.optDouble("yaw", 0.0)
                        val duration = json.optDouble("duration", 2.0)
                        val rotation = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation()
                        rotation.mode = dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode.ABSOLUTE_ANGLE
                        rotation.pitch = pitch
                        rotation.yaw = yaw
                        rotation.roll = 0.0
                        rotation.duration = duration

                        val key = dji.sdk.keyvalue.key.KeyTools.createKey(dji.sdk.keyvalue.key.GimbalKey.KeyRotateByAngle, dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN)
                        dji.v5.manager.KeyManager.getInstance().performAction(key, rotation, object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                            override fun onSuccess(p0: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                                runOnUiThread { showToast("C2: Gimbal Angle Adjusted") }
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                                runOnUiThread { showToast("C2 Gimbal Error: ${error.description() ?: error.errorCode()}") }
                                publishCommandReceipt(transactionId, command, "FAILED", errorMessage = "[${error.errorCode()}] ${error.description()}")
                            }
                        })
                    }
                    "SET_BELLY_LAMP", "BELLY_LAMP_ON", "BELLY_LAMP_OFF", "LANDING_LAMP" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val on = when (command) {
                            "BELLY_LAMP_ON" -> true
                            "BELLY_LAMP_OFF" -> false
                            else -> json.optBoolean("on", json.optBoolean("enabled", !isBellyLampOn))
                        }
                        setBellyLamp(on)
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "SET_DISTANCE_LIMIT", "UNLIMITED_DISTANCE", "REMOVE_DISTANCE_LIMIT", "DISABLE_DISTANCE_LIMIT" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val enabled = when (command) {
                            "UNLIMITED_DISTANCE", "REMOVE_DISTANCE_LIMIT", "DISABLE_DISTANCE_LIMIT" -> false
                            else -> json.optBoolean("enabled", false)
                        }
                        val distanceMeters = json.optInt("distance_m", json.optInt("max_distance", 50000))
                        setDistanceLimitation(enabled, distanceMeters)
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "TGP_LOCK", "TARGET_POD_LOCK", "TAG_GEO_POINT" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val lat = json.optDouble("latitude", json.optDouble("lat", 0.0))
                        val lon = json.optDouble("longitude", json.optDouble("lon", 0.0))
                        val alt = json.optDouble("altitude", json.optDouble("alt", 0.0))

                        runOnUiThread {
                            toggleTargetingPodLock(
                                if (lat != 0.0) lat else null,
                                if (lon != 0.0) lon else null,
                                if (alt != 0.0) alt else null
                            )
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "UNLOCK_TGP", "UNLOCK_POD" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        runOnUiThread {
                            if (isTgpGeoLockActive) {
                                toggleTargetingPodLock()
                            }
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "TRACK_OBJECT", "LOCK_TARGET", "LOCK_OBJECT", "AI_TRACK", "OBJECT_FOLLOW", "AUTO_FOLLOW" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val xMin = json.optDouble("x_min", json.optDouble("normX", json.optDouble("norm_x", 0.5) - 0.1)).toFloat()
                        val yMin = json.optDouble("y_min", json.optDouble("normY", json.optDouble("norm_y", 0.5) - 0.1)).toFloat()
                        val xMax = json.optDouble("x_max", json.optDouble("normX", json.optDouble("norm_x", 0.5) + 0.1)).toFloat()
                        val yMax = json.optDouble("y_max", json.optDouble("normY", json.optDouble("norm_y", 0.5) + 0.1)).toFloat()
                        val normX = (xMin + xMax) / 2f
                        val normY = (yMin + yMax) / 2f
                        val normW = Math.abs(xMax - xMin)
                        val normH = Math.abs(yMax - yMin)

                        runOnUiThread {
                            // 1. Lock optical target tracking & gimbal follow loop
                            objectTrackingOverlay?.lockTargetNormalized(normX, normY, normW, normH)
                            // 2. Trigger hardware camera focus directly on the AI-detected target
                            triggerTapToFocus(normX.toDouble(), normY.toDouble())
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "FOCUS_OBJECT", "TAP_TO_FOCUS", "FOCUS_TARGET", "AUTO_FOCUS" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val normX = json.optDouble("normX", json.optDouble("norm_x", json.optDouble("x", 0.5)))
                        val normY = json.optDouble("normY", json.optDouble("norm_y", json.optDouble("y", 0.5)))

                        runOnUiThread {
                            triggerTapToFocus(normX, normY)
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "STOP_TRACKING", "UNLOCK_TARGET", "UNLOCK_OBJECT", "CANCEL_FOLLOW" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        runOnUiThread {
                            objectTrackingOverlay?.unlockTarget()
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "GIMBAL_SPEED" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val pitch = json.optDouble("pitch", 0.0)
                        val yaw = json.optDouble("yaw", 0.0)
                        val key = dji.sdk.keyvalue.key.KeyTools.createKey(dji.sdk.keyvalue.key.GimbalKey.KeyRotateBySpeed, dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN)
                        dji.v5.manager.KeyManager.getInstance().performAction(key, GimbalSpeedRotation(pitch, yaw, 0.0, CtrlInfo()), object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                            override fun onSuccess(p0: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                                runOnUiThread { showToast("C2: Gimbal Speed Command Sent") }
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                                runOnUiThread { showToast("C2 Gimbal Speed Error: ${error.description() ?: error.errorCode()}") }
                                publishCommandReceipt(transactionId, command, "FAILED", errorMessage = "[${error.errorCode()}] ${error.description()}")
                            }
                        })
                    }
                    "SYNC_CONFIG" -> {
                        // Apply RTH Altitude from web dashboard
                        val newRthAlt = json.optInt("rthAlt", rthAltitude)
                        if (newRthAlt != rthAltitude) {
                            rthAltitude = newRthAlt
                            val rthKey = dji.sdk.keyvalue.key.KeyTools.createKey(dji.sdk.keyvalue.key.FlightControllerKey.KeyGoHomeHeight)
                            dji.v5.manager.KeyManager.getInstance().setValue(rthKey, newRthAlt, object : CommonCallbacks.CompletionCallback {
                                override fun onSuccess() { log("C2 Config: RTH Altitude set to ${newRthAlt}m") }
                                override fun onFailure(error: IDJIError) { log("Failed to set RTH Alt: ${error.errorCode()}") }
                            })
                        }
                        
                        // Apply Obstacle Action from web dashboard
                        val newObstacle = json.optString("obstacleAction", "BRAKE")
                        obstacleAction = when (newObstacle) {
                            "BRAKE" -> 0
                            "BYPASS" -> 1
                            "OFF" -> 2
                            else -> 0
                        }
                        val obsType = when (obstacleAction) {
                            0 -> ObstacleAvoidanceType.BRAKE
                            1 -> ObstacleAvoidanceType.BYPASS
                            else -> ObstacleAvoidanceType.CLOSE
                        }
                        PerceptionManager.getInstance().setObstacleAvoidanceType(obsType, object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() { log("C2 Config: Obstacle Action set to $obsType") }
                            override fun onFailure(error: IDJIError) { log("Failed to set Obstacle Action: ${error.errorCode()}") }
                        })
                        
                        // Apply Signal Loss Action from web dashboard
                        val newSignalLoss = json.optString("signalLossAction", "GOHOME")
                        signalLossAction = when (newSignalLoss) {
                            "GOHOME", "GO_HOME" -> 0
                            "LANDING" -> 1
                            "HOVER" -> 2
                            else -> 0
                        }
                        val failsafeKey = KeyTools.createKey(FlightControllerKey.KeyFailsafeAction)
                        val failsafeValue = when (signalLossAction) {
                            0 -> dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.GOHOME
                            1 -> dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.LANDING
                            2 -> dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.HOVER
                            else -> dji.sdk.keyvalue.value.flightcontroller.FailsafeAction.GOHOME
                        }
                        dji.v5.manager.KeyManager.getInstance().setValue(failsafeKey, failsafeValue, object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() { log("C2 Config: Signal Loss Action set to $failsafeValue") }
                            override fun onFailure(error: IDJIError) { log("Failed to set Signal Loss Action: ${error.errorCode()}") }
                        })
                        
                        saveConfig()
                        showToast("C2: Config Synced (RTH: ${newRthAlt}m, OBS: $newObstacle, LOSS: $newSignalLoss)")
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "PING" -> {
                        val pingTimestamp = json.optLong("timestamp", System.currentTimeMillis())
                        val pong = org.json.JSONObject()
                        pong.put("event", "PONG")
                        pong.put("timestamp", pingTimestamp)
                        if (transactionId != null) {
                            pong.put("transaction_id", transactionId)
                        }
                        mqttService.publishMission(jsonPayload = pong.toString())
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "STEALTH_MODE", "SET_STEALTH_MODE", "STEALTH", "STEALTH_ON", "STEALTH_OFF", "LIGHTS_OFF", "LIGHTS_ON", "BELLY_LAMP" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val enable = when (command) {
                            "STEALTH_ON", "LIGHTS_OFF" -> true
                            "STEALTH_OFF", "LIGHTS_ON" -> false
                            else -> json.optBoolean("enabled", !isStealthModeEnabled)
                        }
                        toggleStealthMode(enable)
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "START_COMPASS_CALIBRATION" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val startCalKey = KeyTools.createKey(FlightControllerKey.KeyStartCompassCalibration)
                        KeyManager.getInstance().performAction(startCalKey, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                            override fun onSuccess(p0: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                                runOnUiThread { showToast("C2: Compass Calibration Started") }
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                            override fun onFailure(error: IDJIError) {
                                runOnUiThread { showToast("C2 Calibration Error: ${error.description()}") }
                                publishCommandReceipt(transactionId, command, "FAILED", errorMessage = "[${error.errorCode()}] ${error.description()}")
                            }
                        })
                    }
                    "STOP_COMPASS_CALIBRATION" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val stopCalKey = KeyTools.createKey(FlightControllerKey.KeyStopCompassCalibration)
                        KeyManager.getInstance().performAction(stopCalKey, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                            override fun onSuccess(p0: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                                runOnUiThread { showToast("C2: Compass Calibration Stopped") }
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                            override fun onFailure(error: IDJIError) {
                                runOnUiThread { showToast("C2 Calibration Error: ${error.description()}") }
                                publishCommandReceipt(transactionId, command, "FAILED", errorMessage = "[${error.errorCode()}] ${error.description()}")
                            }
                        })
                    }
                    "RENAME_WAYPOINT" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val newName = json.getString("new_name")
                        var found = false
                        
                        if (json.has("index")) {
                            val idx = json.getInt("index")
                            if (idx >= 0 && idx < tacticalWaypoints.size) {
                                val wp = tacticalWaypoints[idx]
                                wp.name = newName
                                runOnUiThread {
                                    val methodSuffix = if (wp.movementMethod != "linear") " (${wp.movementMethod.uppercase()})" else ""
                                    val actionSuffix = if (wp.actionType != "FLY") " [${wp.actionType}]" else ""
                                    wp.osmdroidMarker?.title = "${wp.name}$methodSuffix$actionSuffix"
                                    mapView.invalidate()
                                }
                                found = true
                            }
                        } else if (json.has("name")) {
                            val nameToFind = json.getString("name")
                            val wp = tacticalWaypoints.firstOrNull { it.name.equals(nameToFind, ignoreCase = true) }
                            if (wp != null) {
                                wp.name = newName
                                runOnUiThread {
                                    val methodSuffix = if (wp.movementMethod != "linear") " (${wp.movementMethod.uppercase()})" else ""
                                    val actionSuffix = if (wp.actionType != "FLY") " [${wp.actionType}]" else ""
                                    wp.osmdroidMarker?.title = "${wp.name}$methodSuffix$actionSuffix"
                                    mapView.invalidate()
                                }
                                found = true
                            }
                        }
                        
                        if (found) {
                            runOnUiThread { showToast("C2: Waypoint renamed to $newName") }
                            publishWaypointsUpdate()
                            publishCommandReceipt(transactionId, command, "COMPLETED")
                        } else {
                            publishCommandReceipt(transactionId, command, "FAILED", errorCode = -30, errorMessage = "Waypoint not found by index/name")
                        }
                    }
                    "UPDATE_WAYPOINT" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        var foundWp: TacticalWaypoint? = null
                        
                        if (json.has("index")) {
                            val idx = json.getInt("index")
                            if (idx >= 0 && idx < tacticalWaypoints.size) {
                                foundWp = tacticalWaypoints[idx]
                            }
                        } else if (json.has("name")) {
                            val nameToFind = json.getString("name")
                            foundWp = tacticalWaypoints.firstOrNull { it.name.equals(nameToFind, ignoreCase = true) }
                        }
                        
                        if (foundWp != null) {
                            if (json.has("altitude")) foundWp.altitude = json.getDouble("altitude")
                            if (json.has("speed")) foundWp.speed = json.getDouble("speed")
                            if (json.has("movementMethod")) foundWp.movementMethod = json.getString("movementMethod")
                            if (json.has("orbitRadius")) foundWp.orbitRadius = json.getDouble("orbitRadius")
                            if (json.has("orbitLoops")) foundWp.orbitLoops = json.getInt("orbitLoops")
                            
                            if (json.has("actionType")) {
                                foundWp.actionType = json.getString("actionType")
                                if (json.has("poiLat") && json.has("poiLng")) {
                                    foundWp.poiTarget = org.osmdroid.util.GeoPoint(json.getDouble("poiLat"), json.getDouble("poiLng"))
                                } else if (foundWp.actionType != "LOCK_POI" && foundWp.actionType != "PHOTO" && foundWp.actionType != "START_RECORD") {
                                    foundWp.poiTarget = null
                                }
                                if (json.has("gimbalPitch")) {
                                    foundWp.gimbalPitch = json.getDouble("gimbalPitch")
                                }
                            }
                            
                            val wp = foundWp // Smart-cast
                            runOnUiThread {
                                val methodSuffix = if (wp.movementMethod != "linear") " (${wp.movementMethod.uppercase()})" else ""
                                val actionSuffix = if (wp.actionType != "FLY") " [${wp.actionType}]" else ""
                                wp.osmdroidMarker?.title = "${wp.name}$methodSuffix$actionSuffix"
                                updateFlightPathLine()
                                mapView.invalidate()
                            }
                            publishWaypointsUpdate()
                            publishCommandReceipt(transactionId, command, "COMPLETED")
                        } else {
                            publishCommandReceipt(transactionId, command, "FAILED", errorCode = -30, errorMessage = "Waypoint not found by index/name")
                        }
                    }
                    "RESET_ZOOM" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        resetZoom()
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "SET_CAMERA_ZOOM" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val ratio = json.optDouble("zoom", 1.0)
                        currentZoomRatio = ratio
                        val zoomKey = KeyTools.createCameraKey(CameraKey.KeyCameraZoomRatios, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_ZOOM)
                        KeyManager.getInstance().setValue(zoomKey, ratio, object : CommonCallbacks.CompletionCallback {
                            override fun onSuccess() {
                                runOnUiThread { showToast("C2: Zoom set to ${ratio}x") }
                                publishCommandReceipt(transactionId, command, "COMPLETED")
                            }
                            override fun onFailure(error: IDJIError) {
                                publishCommandReceipt(transactionId, command, "FAILED", errorCode = error.errorCode(), errorMessage = error.description())
                            }
                        })
                    }
                    "TAP_TO_FOCUS" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val normX = json.optDouble("x", 0.5).coerceIn(0.0, 1.0)
                        val normY = json.optDouble("y", 0.5).coerceIn(0.0, 1.0)
                        triggerTapToFocus(normX, normY)
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "AUTO_FOCUS" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        triggerContinuousAutoFocus()
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "SET_ISR_MODE" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        val modeStr = json.optString("mode", "NONE").uppercase()
                        com.dji.recreate2.sync.ISRModeManager.setMode(modeStr, this)
                        runOnUiThread { showToast("C2: ISR Mode set to $modeStr") }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "SET_ISR_OVERLAYS" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        if (json.has("reticle")) {
                            com.dji.recreate2.sync.FpvStreamRecorder.isReticleOverlayEnabled = json.getBoolean("reticle")
                        }
                        if (json.has("compass")) {
                            com.dji.recreate2.sync.FpvStreamRecorder.isCompassOverlayEnabled = json.getBoolean("compass")
                        }
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "TRIGGER_MODE1_CAPTURE", "ISR_CAPTURE", "CAPTURE_ISR", "TAKE_PHOTO", "PHOTO_CAPTURE", "CAPTURE", "MODE1_CAPTURE" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        runOnUiThread { showToast("C2: Triggering ISR Photo Capture...") }
                        capturePhoto()
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    "TRIGGER_MODE2_SYNC", "ISR_SYNC", "SYNC_MODE2", "MODE2_SYNC" -> {
                        publishCommandReceipt(transactionId, command, "EXECUTING")
                        runOnUiThread { showToast("C2: Triggering ISR Mode 2 SD Sync...") }
                        com.dji.recreate2.sync.PostFlightS3Sync.startSync(this)
                        publishCommandReceipt(transactionId, command, "COMPLETED")
                    }
                    else -> {
                        publishCommandReceipt(transactionId, command, "FAILED", errorCode = -404, errorMessage = "Unknown command: $command")
                    }
                }
        } catch (e: Exception) {
            log("Error parsing C2 command: ${e.message}")
            publishCommandReceipt(transactionId, command, "REJECTED", errorCode = -1, errorMessage = e.message)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_LOCAL_FOLDER && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data ?: return
            // Persist access across reboots
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }

            // Resolve to a display path string
            val path = try {
                // Decode the document tree URI to a readable path
                val docId = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.uri?.lastPathSegment ?: uri.toString()
                val decoded = java.net.URLDecoder.decode(docId, "UTF-8")
                // docId is often "primary:DCIM/ISR_Sync" — convert to /sdcard/ path
                if (decoded.contains(":")) {
                    val parts = decoded.split(":", limit = 2)
                    if (parts[0] == "primary") "/sdcard/${parts[1]}" else "/storage/${parts[0]}/${parts[1]}"
                } else {
                    decoded
                }
            } catch (_: Exception) { uri.toString() }

            pendingLocalFolderEditText?.setText(path)
            getSharedPreferences("TacticalHUDConfig", android.content.Context.MODE_PRIVATE)
                .edit().putString("isrLocalFolderPath", path).apply()
            showToast("Local folder set: $path")
            log("ISR local folder picked via SAF: $path")
            pendingLocalFolderEditText = null
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    private fun previewGridMission() {
        previewWaypoints.clear()
        if (previewGridPolyline != null) {
            mapView.overlays.remove(previewGridPolyline)
            previewGridPolyline = null
        }
        
        if (shapePoints.size < 3) {
            mapView.invalidate()
            return
        }

        // Get Parameters
        val altStr = findViewById<android.widget.EditText>(R.id.etMapAlt)?.text.toString()
        val speedStr = findViewById<android.widget.EditText>(R.id.etMapSpeed)?.text.toString()
        val overlapStr = findViewById<android.widget.EditText>(R.id.etMapOverlap)?.text.toString()
        val pitchStr = findViewById<android.widget.EditText>(R.id.etMapPitch)?.text.toString()
        val spnPattern = findViewById<android.widget.Spinner>(R.id.spnMapPattern)
        
        val previewAlt = altStr.toDoubleOrNull() ?: 50.0
        val previewSpeed = speedStr.toDoubleOrNull() ?: 5.0
        val overlapPct = overlapStr.toDoubleOrNull() ?: 70.0
        val pitch = pitchStr.toDoubleOrNull() ?: -90.0
        val isCrosshatch = spnPattern?.selectedItemPosition == 1
        
        // 1. Calculate Bounding Box
        var minLat = 90.0
        var maxLat = -90.0
        var minLon = 180.0
        var maxLon = -180.0
        
        for (p in shapePoints) {
            if (p.latitude < minLat) minLat = p.latitude
            if (p.latitude > maxLat) maxLat = p.latitude
            if (p.longitude < minLon) minLon = p.longitude
            if (p.longitude > maxLon) maxLon = p.longitude
        }
        
        // 2. Calculate line spacing based on Altitude and Side Overlap
        val fovTan = Math.tan(Math.toRadians(cameraFov / 2.0))
        val swathWidth = 2 * previewAlt * fovTan
        val distanceBetweenLinesMeters = swathWidth * (1.0 - (overlapPct / 100.0))
        
        if (distanceBetweenLinesMeters < 1.0) return
        
        val latStep = distanceBetweenLinesMeters / 111320.0
        val lonStepBase = distanceBetweenLinesMeters / (111320.0 * Math.cos(Math.toRadians((minLat + maxLat) / 2.0)))
        
        // Generate Horizontal Lines (Latitude Slices)
        var isLeftToRight = true
        var currentLat = minLat + (latStep / 2)
        
        while (currentLat <= maxLat) {
            val intersections = mutableListOf<Double>()
            for (i in shapePoints.indices) {
                val p1 = shapePoints[i]
                val p2 = shapePoints[(i + 1) % shapePoints.size]
                if ((p1.latitude <= currentLat && p2.latitude > currentLat) || (p2.latitude <= currentLat && p1.latitude > currentLat)) {
                    val fraction = (currentLat - p1.latitude) / (p2.latitude - p1.latitude)
                    val intersectLon = p1.longitude + fraction * (p2.longitude - p1.longitude)
                    intersections.add(intersectLon)
                }
            }
            
            intersections.sort()
            
            if (intersections.size >= 2) {
                for (i in 0 until intersections.size - 1 step 2) {
                    val lon1 = intersections[i]
                    val lon2 = intersections[i+1]
                    val overshootMeters = 15.0
                    val lonOvershoot = overshootMeters / (111320.0 * Math.cos(Math.toRadians(currentLat)))
                    val extendedLon1 = lon1 - lonOvershoot
                    val extendedLon2 = lon2 + lonOvershoot
                    val lonDistance = extendedLon2 - extendedLon1
                    val metersLon = lonDistance * (111320.0 * Math.cos(Math.toRadians(currentLat)))
                    val frontOverlapDistance = swathWidth * (1.0 - (overlapPct / 100.0))
                    val numPhotos = Math.max(2, Math.ceil(Math.abs(metersLon) / frontOverlapDistance).toInt() + 1)
                    val lonInterval = lonDistance / (numPhotos - 1)
                    
                    if (isLeftToRight) {
                        for (j in 0 until numPhotos) {
                            val targetLon = extendedLon1 + (j * lonInterval)
                            previewWaypoints.add(TacticalWaypoint(GeoPoint(currentLat, targetLon), previewAlt, previewSpeed, actionType = "SET_GIMBAL,PHOTO", gimbalPitch = pitch))
                        }
                    } else {
                        for (j in 0 until numPhotos) {
                            val targetLon = extendedLon2 - (j * lonInterval)
                            previewWaypoints.add(TacticalWaypoint(GeoPoint(currentLat, targetLon), previewAlt, previewSpeed, actionType = "SET_GIMBAL,PHOTO", gimbalPitch = pitch))
                        }
                    }
                }
                isLeftToRight = !isLeftToRight
            }
            currentLat += latStep
        }
        
        // Generate Vertical Lines (Longitude Slices) if Crosshatch
        if (isCrosshatch) {
            var isTopToBottom = true
            var currentLon = minLon + (lonStepBase / 2)
            
            while (currentLon <= maxLon) {
                val intersections = mutableListOf<Double>()
                for (i in shapePoints.indices) {
                    val p1 = shapePoints[i]
                    val p2 = shapePoints[(i + 1) % shapePoints.size]
                    if ((p1.longitude <= currentLon && p2.longitude > currentLon) || (p2.longitude <= currentLon && p1.longitude > currentLon)) {
                        val fraction = (currentLon - p1.longitude) / (p2.longitude - p1.longitude)
                        val intersectLat = p1.latitude + fraction * (p2.latitude - p1.latitude)
                        intersections.add(intersectLat)
                    }
                }
                
                intersections.sort()
                
                if (intersections.size >= 2) {
                    for (i in 0 until intersections.size - 1 step 2) {
                        val lat1 = intersections[i]
                        val lat2 = intersections[i+1]
                        val overshootMeters = 15.0
                        val latOvershoot = overshootMeters / 111320.0
                        val extendedLat1 = lat1 - latOvershoot
                        val extendedLat2 = lat2 + latOvershoot
                        val latDistance = extendedLat2 - extendedLat1
                        val metersLat = latDistance * 111320.0
                        val frontOverlapDistance = swathWidth * (1.0 - (overlapPct / 100.0))
                        val numPhotos = Math.max(2, Math.ceil(Math.abs(metersLat) / frontOverlapDistance).toInt() + 1)
                        val latInterval = latDistance / (numPhotos - 1)
                        
                        if (isTopToBottom) {
                            for (j in 0 until numPhotos) {
                                val targetLat = extendedLat2 - (j * latInterval)
                                previewWaypoints.add(TacticalWaypoint(GeoPoint(targetLat, currentLon), previewAlt, previewSpeed, actionType = "SET_GIMBAL,PHOTO", gimbalPitch = pitch))
                            }
                        } else {
                            for (j in 0 until numPhotos) {
                                val targetLat = extendedLat1 + (j * latInterval)
                                previewWaypoints.add(TacticalWaypoint(GeoPoint(targetLat, currentLon), previewAlt, previewSpeed, actionType = "SET_GIMBAL,PHOTO", gimbalPitch = pitch))
                        }
                    }
                    }
                    isTopToBottom = !isTopToBottom
                }
                currentLon += lonStepBase
            }
        }
        
        // Render Polyline Preview
        if (previewWaypoints.isNotEmpty()) {
            val points = previewWaypoints.map { it.geoPoint }
            previewGridPolyline = org.osmdroid.views.overlay.Polyline(mapView)
            previewGridPolyline?.setPoints(points)
            previewGridPolyline?.outlinePaint?.color = android.graphics.Color.CYAN
            previewGridPolyline?.outlinePaint?.strokeWidth = 3.0f
            mapView.overlays.add(0, previewGridPolyline)
        }
        
        mapView.invalidate()
    }
    
    private var liveStreamStatusListener: dji.v5.manager.datacenter.livestream.LiveStreamStatusListener? = null

    private fun startRtmpStream(url: String) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        try {
            val isWhip = cleanUrl.startsWith("http://", ignoreCase = true) || 
                         cleanUrl.startsWith("https://", ignoreCase = true) || 
                         cleanUrl.startsWith("whip://", ignoreCase = true) ||
                         cleanUrl.contains("/whip", ignoreCase = true)

            // --- RTMP / RTSP EXECUTION PATH ---
            // As DJI's LiveStreamManager is the only way to utilize the hardware encoder without overloading the phone's CPU,
            // we must use RTMP (TCP) push or RTSP Server. Native WHIP WebRTC requires libwebrtc and software encoding, which overloads the Redmi 15C.

            val targetStreamUrl = if (isWhip) {
                // If user clicks WHIP, we map it to RTMP push since pure WHIP cannot be hardware-encoded on this device.
                val streamName = cleanUrl.substringAfter("dst=", "").ifEmpty { cleanUrl.substringAfter("src=", "").ifEmpty { "dji-sdk-view-asli" } }
                val host = if (cleanUrl.contains("rtc.blackeye.id")) "rtc.blackeye.id" else "10.12.0.15"
                // STRIP AUTH for RTMP Push: go2rtc RTMP server does not require URL auth by default, and DJI encoder might crash parsing it!
                "rtmp://$host:1936/live/$streamName"
            } else {
                cleanUrl
            }

            showToast("🚀 Starting Hardware RTMP Stream (TCP): $targetStreamUrl")
            android.util.Log.i("KMZ_SysLog", "Optimized Hardware Stream Command Received: $targetStreamUrl")
            
            val liveStreamManager = dji.v5.manager.datacenter.MediaDataCenter.getInstance().liveStreamManager
            
            if (liveStreamManager != null) {
                val isRtspServerOnly = targetStreamUrl.equals("rtsp://", ignoreCase = true) || 
                                       targetStreamUrl.startsWith("rtsp://:", ignoreCase = true) ||
                                       (targetStreamUrl.startsWith("rtsp://", ignoreCase = true) && !targetStreamUrl.substring(7).contains("/"))

                val liveStreamConfig = if (isRtspServerOnly) {
                    val portStr = targetStreamUrl.substringAfterLast(":").takeWhile { it.isDigit() }
                    val port = portStr.toIntOrNull() ?: 8554
                    val rtspSettings = dji.v5.manager.datacenter.livestream.settings.RtspSettings.Builder()
                        .setPort(port)
                        .build()
                    dji.v5.manager.datacenter.livestream.LiveStreamSettings.Builder()
                        .setLiveStreamType(dji.v5.manager.datacenter.livestream.LiveStreamType.RTSP)
                        .setRtspSettings(rtspSettings)
                        .build()
                } else {
                    val rtmpSettings = dji.v5.manager.datacenter.livestream.settings.RtmpSettings.Builder()
                        .setUrl(targetStreamUrl)
                        .build()
                    dji.v5.manager.datacenter.livestream.LiveStreamSettings.Builder()
                        .setLiveStreamType(dji.v5.manager.datacenter.livestream.LiveStreamType.RTMP)
                        .setRtmpSettings(rtmpSettings)
                        .build()
                }
                    
                // 1. Set main camera as primary stream source
                liveStreamManager.cameraIndex = dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN

                // 2. Set quality & bitrate mode before assigning liveStreamSettings so settings take full effect
                // FIX: Use StreamQuality.HD (720p) with AUTO bitrate
                try {
                    liveStreamManager.liveStreamQuality = dji.v5.manager.datacenter.livestream.StreamQuality.HD
                    liveStreamManager.liveVideoBitrateMode = dji.v5.manager.datacenter.livestream.LiveVideoBitrateMode.AUTO
                } catch (e: Exception) {
                    android.util.Log.w("KMZ_SysLog", "Could not set stream quality/bitrate mode: ${e.message}")
                }

                // 3. Assign liveStreamSettings
                liveStreamManager.liveStreamSettings = liveStreamConfig

                // Attach LiveStreamStatusListener to track telemetry & error events
                if (liveStreamStatusListener == null) {
                    liveStreamStatusListener = object : dji.v5.manager.datacenter.livestream.LiveStreamStatusListener {
                        override fun onLiveStreamStatusUpdate(status: dji.v5.manager.datacenter.livestream.LiveStreamStatus?) {
                            status?.let {
                                android.util.Log.d("KMZ_SysLog", "LiveStream Telemetry Update: $it")
                            }
                        }

                        override fun onError(error: dji.v5.common.error.IDJIError?) {
                            error?.let {
                                android.util.Log.e("KMZ_SysLog", "LiveStream Error Event: ${it.description()}")
                            }
                        }
                    }
                }
                liveStreamStatusListener?.let { liveStreamManager.addLiveStreamStatusListener(it) }
                
                // Set main camera as primary stream source
                liveStreamManager.cameraIndex = dji.sdk.keyvalue.value.common.ComponentIndexType.LEFT_OR_MAIN
                
                liveStreamManager.startStream(object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        runOnUiThread {
                            val protoLabel = if (cleanUrl.startsWith("rtsp://", ignoreCase = true)) "RTSP" else "RTMP"
                            showToast("✔ Ultra-Fast $protoLabel Stream ACTIVE: $cleanUrl")
                            log("Optimized Low-Latency $protoLabel Stream started successfully to: $cleanUrl")
                        }
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        runOnUiThread {
                            showToast("✗ Stream Failed: ${error.description()}")
                            log("Stream Error: ${error.errorCode()} / ${error.description()}")
                        }
                    }
                })
                
            } else {
                showToast("LiveStreamManager unavailable on this aircraft.")
            }
        } catch (e: Exception) {
            runOnUiThread {
                showToast("LiveStream error: ${e.message}")
            }
            android.util.Log.e("KMZ_SysLog", "LiveStream failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun stopRtmpStream() {
        try {
            showToast("Stopping FPV Stream")
            android.util.Log.i("KMZ_SysLog", "FPV Stream Stop Command Received")

            if (com.dji.recreate2.sync.WhipWebRtcManager.isStreaming) {
                com.dji.recreate2.sync.WhipWebRtcManager.stopWhipStream(
                    onSuccess = { runOnUiThread { showToast("WHIP WebRTC Stream Stopped!") } },
                    onError = { err -> runOnUiThread { showToast("WHIP Stop Error: $err") } }
                )
            }

            val liveStreamManager = dji.v5.manager.datacenter.MediaDataCenter.getInstance().liveStreamManager
            liveStreamStatusListener?.let {
                liveStreamManager?.removeLiveStreamStatusListener(it)
            }
            liveStreamManager?.stopStream(object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    runOnUiThread {
                        showToast("FPV Stream Stopped!")
                    }
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    runOnUiThread {
                        showToast("Failed to stop FPV Stream: ${error.description()}")
                    }
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun commitGridMission() {
        if (previewWaypoints.isEmpty()) {
            showToast("No valid grid path generated.")
            return
        }
        
        tacticalWaypoints.clear()
        tacticalWaypoints.addAll(previewWaypoints)
        
        // Create map markers directly without using addWaypointMarker() to avoid
        // duplicating waypoints with default values (BUG-06 fix)
        mapView.overlays.removeAll { it is org.osmdroid.views.overlay.Marker && shapeVertexMarkers.indexOf(it) == -1 && it != droneMarker && it != homeMapMarker }
        for ((index, wp) in tacticalWaypoints.withIndex()) {
            wp.name = "waypoint_${index + 1}"
            val marker = org.osmdroid.views.overlay.Marker(mapView)
            marker.position = wp.geoPoint
            marker.icon = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_nato_waypoint)
            marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_CENTER)
            marker.title = wp.name
            marker.setOnMarkerClickListener { m, _ ->
                showWaypointActionDialog(wp, m as org.osmdroid.views.overlay.Marker)
                true
            }
            wp.osmdroidMarker = marker
            mapView.overlays.add(marker)
        }
        publishWaypointsUpdate()
        
        updateFlightPathLine()
        
        // Hide settings panel
        findViewById<android.view.View>(R.id.layoutMappingSettings).visibility = android.view.View.GONE
        
        // Publish to Edge Server
        try {
            val jsonWaypoints = org.json.JSONArray()
            for (wp in tacticalWaypoints) {
                val wpObj = org.json.JSONObject()
                wpObj.put("lat", wp.geoPoint.latitude)
                wpObj.put("lon", wp.geoPoint.longitude)
                wpObj.put("alt", wp.altitude)
                wpObj.put("speed", wp.speed)
                wpObj.put("action", wp.actionType)
                jsonWaypoints.put(wpObj)
            }
            
            val payload = org.json.JSONObject()
            payload.put("type", "grid_mission")
            payload.put("timestamp", System.currentTimeMillis())
            payload.put("waypoints", jsonWaypoints)
            
            if (::mqttService.isInitialized) {
                mqttService.publishMission(jsonPayload = payload.toString())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        currentMapInteraction = MapInteractionType.MOVE
        findViewById<android.view.View>(R.id.btnGenerateGrid).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.btnExecuteMission).visibility = android.view.View.VISIBLE
        
        showToast("Grid Generated: ${tacticalWaypoints.size} Waypoints. Press EXEC MISSION to start.")
    }

    private fun parseKmzCoordinates(kmzFilePath: String): List<org.osmdroid.util.GeoPoint> {
        val points = mutableListOf<org.osmdroid.util.GeoPoint>()
        var waylinesContent = ""
        var templateContent = ""
        try {
            val fis = java.io.FileInputStream(kmzFilePath)
            val zis = java.util.zip.ZipInputStream(fis)
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith("waylines.wpml")) {
                    waylinesContent = zis.readBytes().toString(Charsets.UTF_8)
                } else if (entry.name.endsWith("template.kml")) {
                    templateContent = zis.readBytes().toString(Charsets.UTF_8)
                } else if (entry.name.endsWith(".kml") || entry.name.endsWith(".wpml")) {
                    if (templateContent.isEmpty()) {
                        templateContent = zis.readBytes().toString(Charsets.UTF_8)
                    }
                }
                entry = zis.nextEntry
            }
            zis.close()
            fis.close()
            
            val targetXml = if (waylinesContent.isNotEmpty()) waylinesContent else templateContent
            
            if (targetXml.isNotEmpty()) {
                val regex = Regex("<(?:[a-zA-Z0-9]+:)?coordinates>([\\d\\.\\-\\s,]+)</(?:[a-zA-Z0-9]+:)?coordinates>")
                val matches = regex.findAll(targetXml)
                for (match in matches) {
                    val coordsStr = match.groupValues[1].trim()
                    
                    // A single <coordinates> block might contain multiple space-separated coordinates (e.g. in LineString)
                    val coordinatePairs = coordsStr.split(Regex("\\s+"))
                    for (pair in coordinatePairs) {
                        if (pair.isBlank()) continue
                        
                        val parts = pair.split(",")
                        if (parts.size >= 2) {
                            val lng = parts[0].trim().toDoubleOrNull()
                            val lat = parts[1].trim().toDoubleOrNull()
                            if (lat != null && lng != null && lat != 0.0 && lng != 0.0) {
                                val newPoint = org.osmdroid.util.GeoPoint(lat, lng)
                                if (points.isEmpty() || points.last().latitude != lat || points.last().longitude != lng) {
                                    points.add(newPoint)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("KMZ_SysLog", "Failed to parse KMZ for visualization: ${e.message}")
        }
        return points
    }

    private fun drawKmzRouteOnMap(waypoints: List<org.osmdroid.util.GeoPoint>) {
        if (waypoints.isEmpty()) return
        
        runOnUiThread {
            // M-05: exclude flightPathPolyline and orbitCircleOverlays to prevent dangling references
            val toRemove = mapView.overlays.filter {
                (it is org.osmdroid.views.overlay.Polyline && it != headingLine && it != flightPathPolyline && !orbitCircleOverlays.contains(it))
                || (it is org.osmdroid.views.overlay.Marker && it.title?.startsWith("WP") == true)
            }
            mapView.overlays.removeAll(toRemove)
            
            val line = org.osmdroid.views.overlay.Polyline()
            line.setPoints(waypoints)
            line.outlinePaint.color = android.graphics.Color.BLUE
            line.outlinePaint.strokeWidth = 5.0f
            mapView.overlays.add(line)
            
            waypoints.forEachIndexed { index, point ->
                val marker = org.osmdroid.views.overlay.Marker(mapView)
                marker.position = point
                marker.title = "WP ${index + 1}"
                marker.setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                mapView.overlays.add(marker)
            }
            
            mapView.invalidate()
            
            if (waypoints.isNotEmpty()) {
                if (waypoints.size == 1) {
                    mapView.controller.animateTo(waypoints[0])
                    mapView.controller.setZoom(18.0)
                } else {
                    val boundingBox = org.osmdroid.util.BoundingBox.fromGeoPoints(waypoints)
                    if (boundingBox.latNorth == boundingBox.latSouth || boundingBox.lonEast == boundingBox.lonWest) {
                        mapView.controller.animateTo(waypoints[0])
                        mapView.controller.setZoom(18.0)
                    } else {
                        mapView.zoomToBoundingBox(boundingBox, true, 100)
                    }
                }
            }
        }
    }

    private fun executeNativeKMZ(kmzPath: String, autoStart: Boolean = false) {
        if (isMissionExecuting) {
            runOnUiThread { showToast("A mission is already executing!") }
            return
        }

        // --- VISUALIZE KMZ ROUTE ---
        Thread {
            val kmzPoints = parseKmzCoordinates(kmzPath)
            if (kmzPoints.isNotEmpty()) {
                drawKmzRouteOnMap(kmzPoints)
                // Publish KMZ route to Web App (Dashboard HQ)
                if (::mqttService.isInitialized) {
                    try {
                        val jsonWaypoints = org.json.JSONArray()
                        kmzPoints.forEach { pt ->
                            val wp = org.json.JSONObject()
                            wp.put("lat", pt.latitude)
                            wp.put("lon", pt.longitude)
                            wp.put("alt", pt.altitude)
                            jsonWaypoints.put(wp)
                        }
                        val payload = org.json.JSONObject()
                        payload.put("type", "grid_mission") // Web App listens for this
                        payload.put("timestamp", System.currentTimeMillis())
                        payload.put("waypoints", jsonWaypoints)
                        mqttService.publishMission(jsonPayload = payload.toString())
                        android.util.Log.i("KMZ_SysLog", "Published KMZ points to HQ: ${kmzPoints.size}")
                    } catch (e: Exception) { e.printStackTrace() }
                }
            } else {
                android.util.Log.w("KMZ_SysLog", "No coordinates found in KMZ for visualization.")
            }
        }.start()

        // --- PRE-FLIGHT CHECKS ---
        try {
            // H-07: fallback to 0 so safety gate FAILS when SDK cannot read values (drone disconnected)
            val batt = if (droneBattery > 0) droneBattery else (KeyManager.getInstance().getValue(KeyTools.createKey(dji.sdk.keyvalue.key.BatteryKey.KeyChargeRemainingInPercent)) ?: 0)
            val gps = if (droneSatellites > 0) droneSatellites else (KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount)) ?: 0)
            
            if (batt < 20) {
                runOnUiThread { showToast("PRE-FLIGHT FAILED: Battery Too Low (< 20%)") }
                if (::mqttService.isInitialized) {
                    val logObj = org.json.JSONObject()
                    logObj.put("event", "KMZ_PREFLIGHT_FAILED")
                    logObj.put("error", "Battery Too Low (< 20%)")
                    mqttService.publishMission(jsonPayload = logObj.toString())
                }
                return
            }
            val isGpsDeniedMode = com.dji.recreate2.flight.ConfinedSpaceFlightManager.isGpsDeniedModeEnabled
            if (!isGpsDeniedMode && gps < 10) {
                runOnUiThread { showToast("PRE-FLIGHT FAILED: Weak GPS Signal (< 10 Sats)") }
                if (::mqttService.isInitialized) {
                    val logObj = org.json.JSONObject()
                    logObj.put("event", "KMZ_PREFLIGHT_FAILED")
                    logObj.put("error", "Weak GPS Signal (< 10 Sats)")
                    mqttService.publishMission(jsonPayload = logObj.toString())
                }
                return
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (::mqttService.isInitialized) {
            val logObj = org.json.JSONObject()
            logObj.put("event", "KMZ_PUSH_STARTED")
            mqttService.publishMission(jsonPayload = logObj.toString())
            android.util.Log.d("KMZ_SysLog", "KMZ_PUSH_STARTED: $kmzPath")
        }

        val wpm = dji.v5.manager.aircraft.waypoint3.WaypointMissionManager.getInstance()
        wpm.pushKMZFileToAircraft(kmzPath, object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithProgress<Double> {
            override fun onProgressUpdate(progress: Double?) {}
            override fun onSuccess() {
                runOnUiThread { showToast("Native KMZ Pushed.") }
                if (::mqttService.isInitialized) {
                    val logObj = org.json.JSONObject()
                    logObj.put("event", "KMZ_PUSH_SUCCESS")
                    mqttService.publishMission(jsonPayload = logObj.toString())
                    android.util.Log.d("KMZ_SysLog", "KMZ_PUSH_SUCCESS")
                }
                lastLoadedKmzFileName = java.io.File(kmzPath).name
                lastLoadedKmzFilePath = kmzPath
                
                if (autoStart) {
                    val waylineIDs = wpm.getAvailableWaylineIDs(lastLoadedKmzFilePath ?: return)
                    if (waylineIDs.isNullOrEmpty()) {
                        runOnUiThread { showToast("Error: No waylines found in KMZ. Invalid WPML format.") }
                        if (::mqttService.isInitialized) {
                            val logObj = org.json.JSONObject()
                            logObj.put("event", "KMZ_START_FAILED")
                            logObj.put("error", "No waylines found in KMZ. Ensure it's a valid DJI WPML file.")
                            mqttService.publishMission(jsonPayload = logObj.toString())
                        }
                    } else {
                        startKmzWithAutoTakeoff(wpm, lastLoadedKmzFileName!!, waylineIDs, "Local")
                    }
                } else {
                    runOnUiThread { showToast("Waiting for START_KMZ command...") }
                }
            }
            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                runOnUiThread { showToast("Failed to push KMZ: ${error.description()}") }
                if (::mqttService.isInitialized) {
                    val logObj = org.json.JSONObject()
                    logObj.put("event", "KMZ_PUSH_FAILED")
                    logObj.put("error", error.description())
                    mqttService.publishMission(jsonPayload = logObj.toString())
                    android.util.Log.e("KMZ_SysLog", "KMZ_PUSH_FAILED: ${error.description()}")
                }
            }
        })
    }

    override fun onDestroy() {
        cancelActiveMission()
        takeoffWaitThread?.interrupt() // M-07: stop any pending takeoff wait
        takeoffWaitThread = null
        ledBlinkThread?.interrupt()
        ledBlinkThread = null
        healthChangeListener?.let {
            dji.v5.manager.diagnostic.DeviceHealthManager.getInstance().removeDJIDeviceHealthInfoChangeListener(it)
            healthChangeListener = null
        }
        mapRedrawHandler.removeCallbacksAndMessages(null)  // H-04
        gridPreviewHandler.removeCallbacksAndMessages(null) // M-09
        val rtkLocListener = rtkLocationListener
        if (rtkLocListener != null) {
            dji.v5.manager.aircraft.rtk.RTKCenter.getInstance().removeRTKLocationInfoListener(rtkLocListener)
        }
        val obsListener = obstacleDataListener
        if (obsListener != null) {
            dji.v5.manager.aircraft.perception.PerceptionManager.getInstance().removeObstacleDataListener(obsListener)
        }
        mapView.onDetach()
        isMissionExecuting = false
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        flightModeHideHandler.removeCallbacksAndMessages(null)
        hudToggleHideHandler.removeCallbacksAndMessages(null)
        uiHideHandler.removeCallbacksAndMessages(null)
        KeyManager.getInstance().cancelListen(this)
        PayloadDetectionManager.cleanup()
        WebODMAutoUpload.cleanup()
        failsafeThread?.interrupt()
        failsafeThread = null
        kmzWaylineExecutingInfoListener?.let {
            dji.v5.manager.aircraft.waypoint3.WaypointMissionManager.getInstance().removeWaylineExecutingInfoListener(it)
            kmzWaylineExecutingInfoListener = null
        }
        kmzExecuteStateListener?.let {
            dji.v5.manager.aircraft.waypoint3.WaypointMissionManager.getInstance().removeWaypointMissionExecuteStateListener(it)
            kmzExecuteStateListener = null
        }
        if (::mqttService.isInitialized) {
            mqttService.onCommandReceived = null
            mqttService.onConnectionStatusChanged = null
            mqttService.destroy()
        }
        super.onDestroy()
    }
    
    private fun startKmzWithAutoTakeoff(wpm: dji.v5.manager.aircraft.waypoint3.WaypointMissionManager, missionId: String, waylineIDs: List<Int>, source: String) {
        setupAutoRthForKmzMission(wpm)
        val startMissionLogic = {
            wpm.startMission(missionId, waylineIDs, object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    runOnUiThread { showToast("$source: KMZ Mission Started!") }
                    if (::mqttService.isInitialized) {
                        val logObj = org.json.JSONObject()
                        logObj.put("event", "KMZ_STARTED")
                        mqttService.publishMission(jsonPayload = logObj.toString())
                        android.util.Log.d("KMZ_SysLog", "KMZ_STARTED (from $source)")
                    }
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    runOnUiThread { showToast("$source: Start failed: ${error.description()}") }
                    if (::mqttService.isInitialized) {
                        val logObj = org.json.JSONObject()
                        logObj.put("event", "KMZ_START_FAILED")
                        logObj.put("error", error.description())
                        mqttService.publishMission(jsonPayload = logObj.toString())
                        android.util.Log.e("KMZ_SysLog", "KMZ_START_FAILED: ${error.description()}")
                    }
                }
            })
        }
        
        if (!droneAlt.isNaN() && droneAlt < 1.0) {
            runOnUiThread { showToast("$source: Drone is on the ground. Initiating Auto Takeoff...") }
            executeTakeoff()
            
            val t = Thread({
                var waited = 0
                while (waited < 15000) {
                    if (Thread.currentThread().isInterrupted) break
                    if (!droneAlt.isNaN() && droneAlt > 1.0) break
                    try {
                        Thread.sleep(500)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    waited += 500
                }
                if (!Thread.currentThread().isInterrupted && !isFinishing && !isDestroyed) {
                    if (waited >= 15000) {
                        runOnUiThread { showToast("$source: Takeoff timed out. Aborting KMZ.") }
                    } else {
                        startMissionLogic()
                    }
                }
            }, "TakeoffWaitKMZ")
            takeoffWaitThread = t
            t.start()
        } else {
            startMissionLogic()
        }
    }
    
    private fun setupAutoRthForKmzMission(wpm: dji.v5.manager.aircraft.waypoint3.WaypointMissionManager) {
        kmzExecuteStateListener?.let {
            wpm.removeWaypointMissionExecuteStateListener(it)
        }
        kmzWaylineExecutingInfoListener?.let {
            wpm.removeWaylineExecutingInfoListener(it)
        }
        
        val progressListener = object : dji.v5.manager.aircraft.waypoint3.WaylineExecutingInfoListener {
            override fun onWaylineExecutingInfoUpdate(info: dji.v5.manager.aircraft.waypoint3.model.WaylineExecutingInfo) {
                if (::mqttService.isInitialized) {
                    try {
                        val progressObj = org.json.JSONObject()
                        progressObj.put("event", "KMZ_PROGRESS")
                        progressObj.put("waypoint_index", info.currentWaypointIndex)
                        progressObj.put("wayline_id", info.waylineID)
                        progressObj.put("mission_file", info.missionFileName ?: "")
                        mqttService.publishMission(jsonPayload = progressObj.toString())
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
            override fun onWaylineExecutingInterruptReasonUpdate(error: dji.v5.common.error.IDJIError?) {
                error?.let {
                    if (::mqttService.isInitialized) {
                        try {
                            val errObj = org.json.JSONObject()
                            errObj.put("event", "KMZ_INTERRUPTED")
                            errObj.put("error_code", it.errorCode())
                            errObj.put("description", it.description())
                            mqttService.publishMission(jsonPayload = errObj.toString())
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
        }
        kmzWaylineExecutingInfoListener = progressListener
        wpm.addWaylineExecutingInfoListener(progressListener)

        val listener = dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener { state ->
            when (state) {
                dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState.EXECUTING,
                dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState.ENTER_WAYLINE,
                dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState.PREPARING,
                dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState.UPLOADING,
                dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState.RECOVERING -> {
                    isMissionExecuting = true
                }
                else -> {
                    isMissionExecuting = false
                }
            }

            if (state == dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState.FINISHED) {
                runOnUiThread { showToast("KMZ Mission Finished. Triggering Auto RTH...") }
                
                if (::mqttService.isInitialized) {
                    val logObj = org.json.JSONObject()
                    logObj.put("event", "KMZ_FINISHED")
                    mqttService.publishMission(jsonPayload = logObj.toString())
                }
                
                val rthKey = dji.sdk.keyvalue.key.KeyTools.createKey(dji.sdk.keyvalue.key.FlightControllerKey.KeyStartGoHome)
                dji.v5.manager.KeyManager.getInstance().performAction(rthKey, object : dji.v5.common.callback.CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(t: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                        runOnUiThread { showToast("Auto RTH Started Successfully!") }
                        if (::mqttService.isInitialized) {
                            val logObj = org.json.JSONObject()
                            logObj.put("event", "AUTO_RTH_STARTED")
                            mqttService.publishMission(jsonPayload = logObj.toString())
                        }
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        runOnUiThread { showToast("Auto RTH Failed: ${error.description()}") }
                    }
                })
            }
        }
        kmzExecuteStateListener = listener
        wpm.addWaypointMissionExecuteStateListener(listener)
    }

    // H-02: accepts transactionId so COMPLETED receipt is published only after CSC sequence finishes
    // H-02: accepts transactionId so COMPLETED receipt is published only after checks and blink sequence finish
    private fun startEngineUsingVirtualStick(transactionId: String? = null, cmdAlias: String = "START_ENGINE") {
        if (!droneConnected) {
            runOnUiThread { log("❌ Arming failed: Drone is not connected / paired.") }
            publishCommandReceipt(transactionId, cmdAlias, "FAILED", errorMessage = "Drone is not connected / paired.")
            return
        }

        if (droneBattery < 20) {
            runOnUiThread { log("❌ Arming failed: Battery too low (${droneBattery}%). Min 20% required.") }
            publishCommandReceipt(transactionId, cmdAlias, "FAILED", errorMessage = "Battery too low (${droneBattery}%). Min 20% required.")
            return
        }

        val compassHasError = KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyCompassHasError)) ?: false
        if (compassHasError) {
            runOnUiThread { log("❌ Arming failed: Compass error detected.") }
            publishCommandReceipt(transactionId, cmdAlias, "FAILED", errorMessage = "Compass has error. Calibration required.")
            return
        }

        // Check Device Health Infos for WARNING or SERIOUS_WARNING (e.g. propeller/motor issues, etc.)
        val healthInfos = dji.v5.manager.diagnostic.DeviceHealthManager.getInstance().currentDJIDeviceHealthInfos
        var healthErrorStr: String? = null
        for (info in healthInfos) {
            if (info.warningLevel() == dji.v5.manager.diagnostic.WarningLevel.WARNING || 
                info.warningLevel() == dji.v5.manager.diagnostic.WarningLevel.SERIOUS_WARNING) {
                healthErrorStr = "${info.title()}: ${info.description()}"
                break
            }
        }
        if (healthErrorStr != null) {
            runOnUiThread { log("❌ Arming failed: Drone health check reported errors: $healthErrorStr") }
            publishCommandReceipt(transactionId, cmdAlias, "FAILED", errorMessage = "Drone health check failed: $healthErrorStr")
            return
        }

        runOnUiThread { log("🔥 Arming checks passed. Blinking LEDs twice...") }

        val t = Thread({
            try {
                val key = KeyTools.createKey(FlightControllerKey.KeyLEDsSettings)
                val defaultSettings = dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(true, true, true, true)
                val originalSettings = KeyManager.getInstance().getValue(key) ?: defaultSettings
                
                val offSettings = dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(false, false, false, false)
                val onSettings = dji.sdk.keyvalue.value.flightcontroller.LEDsSettings(true, true, true, true)
                
                // Blink 1
                if (Thread.currentThread().isInterrupted) return@Thread
                KeyManager.getInstance().setValue(key, offSettings, null)
                Thread.sleep(500)
                if (Thread.currentThread().isInterrupted) return@Thread
                KeyManager.getInstance().setValue(key, onSettings, null)
                Thread.sleep(500)
                
                // Blink 2
                if (Thread.currentThread().isInterrupted) return@Thread
                KeyManager.getInstance().setValue(key, offSettings, null)
                Thread.sleep(500)
                if (Thread.currentThread().isInterrupted) return@Thread
                KeyManager.getInstance().setValue(key, onSettings, null)
                Thread.sleep(500)
                
                // Restore original settings
                if (Thread.currentThread().isInterrupted) return@Thread
                KeyManager.getInstance().setValue(key, originalSettings, null)
                Thread.sleep(500)
                
                if (!Thread.currentThread().isInterrupted && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            log("🔥 Arming completed and lights blinked twice successfully.")
                            // Set local engine state
                            isEngineOn = true
                            // Update UI
                            val tvEngineStatus = findViewById<TextView?>(R.id.tvEngineStatus)
                            tvEngineStatus?.text = "ENG: ACTIVE"
                            tvEngineStatus?.setTextColor(android.graphics.Color.GREEN)
                            dTvEngineStatus?.text = "ENG: ACTIVE"
                            dTvEngineStatus?.setTextColor(android.graphics.Color.GREEN)
                            
                            publishCommandReceipt(transactionId, cmdAlias, "COMPLETED")
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted && !isFinishing && !isDestroyed) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            log("❌ Error during LED arming blink: ${e.message}")
                            publishCommandReceipt(transactionId, cmdAlias, "COMPLETED")
                        }
                    }
                }
            }
        }, "Arming-LED-Blink")
        ledBlinkThread = t
        t.start()
    }

    private fun stopEngineUsingVirtualStick(transactionId: String? = null) {
        runOnUiThread {
            log("🔥 Disarming drone: setting engine state to inactive.")
            isEngineOn = false
            val tvEngineStatus = findViewById<TextView?>(R.id.tvEngineStatus)
            tvEngineStatus?.text = "ENG: INACTIVE"
            tvEngineStatus?.setTextColor(android.graphics.Color.parseColor("#00FF00"))
            dTvEngineStatus?.text = "ENG: INACTIVE"
            dTvEngineStatus?.setTextColor(android.graphics.Color.parseColor("#00FF00"))
            publishCommandReceipt(transactionId, "DISARM", "COMPLETED")
        }
    }

    private fun cancelActiveMission() {
        isMissionExecuting = false
        tacticalWaypoints.clear()

        // Stop KMZ mission if loaded
        lastLoadedKmzFileName?.let {
            dji.v5.manager.aircraft.waypoint3.WaypointMissionManager.getInstance().stopMission(it, null)
        }

        // Disable virtual stick
        val vs = dji.v5.manager.aircraft.virtualstick.VirtualStickManager.getInstance()
        vs.leftStick.horizontalPosition = 0
        vs.leftStick.verticalPosition = 0
        vs.rightStick.horizontalPosition = 0
        vs.rightStick.verticalPosition = 0
        vs.disableVirtualStick(null)

        val clearUI = {
            orbitCircleOverlays.forEach { mapView.overlays.remove(it) }
            orbitCircleOverlays.clear()
            mapView.overlays.removeAll { it is org.osmdroid.views.overlay.Polygon }
            mapView.invalidate()
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            clearUI()
        } else {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    clearUI()
                }
            }
        }
    }

    private fun startFailsafeMonitor() {
        failsafeThread?.interrupt()
        val t = Thread({
            while (!Thread.currentThread().isInterrupted && !isFinishing && !isDestroyed) {
                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                
                if (::mqttService.isInitialized && mqttService.isConnected) {
                    val now = System.currentTimeMillis()
                    if (now - lastGcsHeartbeatTime > LINK_LOSS_TIMEOUT_MS) {
                        if (!isLinkLossFailsafeTriggered) {
                            isLinkLossFailsafeTriggered = true
                            triggerLinkLossFailsafe()
                        }
                    } else {
                        isLinkLossFailsafeTriggered = false
                    }
                }
            }
        }, "LinkLossFailsafeMonitor")
        failsafeThread = t
        t.start()
    }

    private fun triggerLinkLossFailsafe() {
        // Signal Loss Action during Mapping Missions: Continue Autonomous Mapping Grid!
        if (isMappingMissionRunning) {
            runOnUiThread {
                log("⚠️ Signal Lost during Mapping! Continuing autonomous mapping grid on-board...")
                showToast("⚠️ Signal Lost! Continuing Mapping Grid autonomously...")
            }
            return
        }

        runOnUiThread {
            log("⚠️ GCS LINK LOST! Triggering failsafe Go-Home...")
            showToast("GCS LINK LOST! Triggering failsafe Go-Home...")
        }
        
        if (::mqttService.isInitialized) {
            try {
                val payload = org.json.JSONObject()
                payload.put("event", "LINK_LOSS_FAILSAFE")
                payload.put("timestamp", System.currentTimeMillis())
                mqttService.publishMission(jsonPayload = payload.toString())
            } catch (e: Exception) { e.printStackTrace() }
        }

        if (isFlying) {
            runOnUiThread {
                val rthKey = KeyTools.createKey(FlightControllerKey.KeyStartGoHome)
                KeyManager.getInstance().performAction(rthKey, object : CommonCallbacks.CompletionCallbackWithParam<dji.sdk.keyvalue.value.common.EmptyMsg> {
                    override fun onSuccess(p0: dji.sdk.keyvalue.value.common.EmptyMsg?) {
                        runOnUiThread { log("✔️ Failsafe RTH started successfully.") }
                    }
                    override fun onFailure(error: IDJIError) {
                        runOnUiThread { log("❌ Failsafe RTH failed: ${error.description()}") }
                        // Fallback to landing if Go-Home fails
                        val landKey = KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding)
                        KeyManager.getInstance().performAction(landKey, null)
                    }
                })
            }
        }
    }
}

