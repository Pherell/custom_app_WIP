# Tactical Drone C2 Server Specification

> **Changelog — 2026-08-06 (STE-100 UI Layout Organization & Thread-Safety Bug Remediation)**
> - **Unified 2-Column HUD Layout (`activity_main.xml`):** Reconstructed the right HUD button panel into a balanced, perfectly aligned 2-column side-by-side grid (`Column 1: GMB, RES, DEL, FOL, LCK, TAG` [32dp Circular] & `Column 2: LRF, IMG, REC, STK, ACK, SYS` [44dp Glass Pill]). Eliminates all layout gaps and misalignment.
> - **MSDK V5 Thread Safety Enforcement (`MainActivity.kt`):** Wrapped background tracking loop SDK calls (`KeyManager.getInstance().performAction`) and map marker overlay updates (`updateGpsTagsOnMap`) inside `runOnUiThread { ... }` per STE-100 guardrail rules.
> - **Tag ID Collision Fix (`GpsTaggingManager.kt`):** Updated tag ID generator to calculate `maxNum + 1` from existing integer suffixes, preventing ID collisions when tags are deleted.
>
> **Changelog — 2026-08-06 (TAG Circular HUD Button for Target Geolocation Tagging)**
> - **`TAG` Circular HUD Button:** Added circular **`TAG`** button directly below **`LCK`** on the main FPV layout. Pressing **`TAG`** instantly tags the current LRF ground target or drone GPS coordinate, stores it in `GpsTaggingManager`, and draws a **Yellow POI Marker Pin** (`#FFFF00`) on the strategy map.
> - **GPS Target Intel Tab inside System Settings:** Positioned the GPS Target Intel dialog launcher inside System Config (`btnGpsTagsConfig`: `📍 GPS TARGET INTEL & TAGS`).
>
> **Changelog — 2026-08-06 (Military-Grade Targeting Pod Line-of-Sight Geo-Lock & Tagging Engine)**
> - **Targeting Pod Line-of-Sight Geo-Lock (`toggleTargetingPodLock`):** Implemented military-style Targeting Pod (TGP) Geo-Lock engine. Calculates real-time ground raycast intersection $(\text{Lat}_t, \text{Lon}_t, \text{Alt}_t)$ and runs a 10Hz 3D vector gimbal angle rotation loop (`dji.sdk.keyvalue.key.GimbalKey.KeyRotateByAngle`). Camera remains **100% locked onto the physical ground coordinate** as the drone maneuvers, turns, or flies.
> - **Tactical HUD Controls & Tagging (`LCK`):** Added circular HUD quick-action button **`LCK`** (Targeting Pod Lock/Unlock) on the main FPV layout. Pressing **`LCK`** toggles LOS Geo-Lock on current camera pointing angle or specified target coordinate.
> - **Remote C2 Commands:** Added C2 JSON command handlers over `dji-sdk/fleet/{clientId}/command`: `TGP_LOCK`, `TARGET_POD_LOCK`, `TAG_GEO_POINT` (with optional payload `{"latitude": 37.7749, "longitude": -122.4194}`) and `UNLOCK_TGP`, `UNLOCK_POD`.
>
> **Changelog — 2026-08-06 (Real Optical Object Tracking & LOCK: OBJECT / HUMAN Label Formatting)**
> - **Real Optical Bounding Box Coordinates:** Removed artificial drift/centering logic; target bounding box stays locked to exact real optical touch/drag or AI bounding box coordinates (`x_min, y_min, x_max, y_max`).
> - **Clean Label Formatting & Smaller Font Size:** Updated on-screen bounding box label to `LOCK: OBJECT / HUMAN` using a crisp `20sp` font size without emoji indicators.
>
> **Changelog — 2026-08-06 (DEL & FOL Tactical HUD Buttons for Object Selection & Follow)**
> - **Tactical HUD Controls (`DEL` & `FOL`):** Added circular HUD quick-action buttons below **RES** (Reset Zoom) on the main FPV layout:
>   - **`DEL` (Delete Selection / Unlock):** Clears active target bounding box overlay and releases optical tracking.
>   - **`FOL` (Follow Object / Activate Tracking):** Activates / toggles autonomous object following & gimbal PID centering on the target object (or locks center-screen object if none selected).
>
> **Changelog — 2026-08-06 (External AI Vision C2 MQTT API for Remote Lock, Focus & Follow)**
> - **External AI Computer Vision Control over MQTT:** Enabled external AI agents (YOLO, MediaPipe, OpenCV, Ground Station AI) reading the RTMP stream to remotely lock, focus, and follow detected objects (humans, cars, boats, security targets) in real-time.
> - **New C2 Commands:**
>   - **`TRACK_OBJECT` / `LOCK_OBJECT` / `OBJECT_FOLLOW` / `AI_TRACK`:** Locks optical target tracking on normalized bounding box `{"x_min": 0.4, "y_min": 0.3, "x_max": 0.6, "y_max": 0.7}` or target center `{"norm_x": 0.5, "norm_y": 0.5}`, triggering 20Hz PID gimbal speed tracking and hardware lens focus simultaneously.
>   - **`FOCUS_OBJECT` / `TAP_TO_FOCUS` / `AUTO_FOCUS`:** Triggers multi-lens hardware focus across Zoom & Wide camera lenses on AI normalized coordinates `{"norm_x": 0.45, "norm_y": 0.35}`.
>   - **`STOP_TRACKING` / `UNLOCK_OBJECT` / `CANCEL_FOLLOW`:** Clears AI object tracking overlay and releases gimbal control.
>
> **Changelog — 2026-08-06 (Touch-and-Drag Optical Object Lock & Target Tracking Engine)**
> - **Tactical Object Tracking Overlay (`ObjectTrackingOverlayView.kt`):** Implemented touch-and-drag bounding box selection and single-tap quick object locking on live camera feed for security missions (humans, vehicles, intruders, animals). Renders tactical green corner reticles and status label overlays.
> - **Closed-Loop Proportional Gimbal Speed Centering (`startOpticalObjectTracking`):** Runs a 20Hz PID control feedback loop calculating target offset $(\Delta x, \Delta y)$ from optical center and sending proportional angular velocity commands (`KeyRotateBySpeed`) to keep the target centered in optical crosshairs.
> - **Remote C2 MQTT Commands:** Added C2 JSON command handlers over `dji-sdk/fleet/{clientId}/command`: `TRACK_OBJECT`, `LOCK_TARGET`, `LOCK_OBJECT` (with payload `{"x_min": 0.3, "y_min": 0.2, "x_max": 0.7, "y_max": 0.8}`) and `STOP_TRACKING`, `UNLOCK_TARGET`, `UNLOCK_OBJECT`.
>
> **Changelog — 2026-08-06 (Unlimited Distance Limitation Control & C2 Overrides)**
> - **Unlimited Distance Control (`setDistanceLimitation`):** Implemented distance limitation bypass engine (`dji.sdk.keyvalue.key.FlightControllerKey.KeyDistanceLimitEnabled` and `KeyDistanceLimit`). Allows switching flight radius mode between **UNLIMITED (NO LIMITATION)** and custom distance limits (e.g. 50,000m).
> - **System Dialog & MQTT C2 Controls:** Added **`[ 🌐 DISTANCE LIMIT: UNLIMITED (NO LIMIT) ]`** toggle button in System Dialog and C2 MQTT command handlers (`SET_DISTANCE_LIMIT`, `UNLIMITED_DISTANCE`, `REMOVE_DISTANCE_LIMIT`, `DISABLE_DISTANCE_LIMIT`).
>
> **Changelog — 2026-08-06 (Belly Landing Lamp & Situational LED Blinking Engine)**
> - **Bottom Auxiliary / Belly Lamp Control (`setBellyLamp`):** Added explicit toggle control for the bottom landing lamp below the drone (`dji.sdk.keyvalue.value.flightcontroller.LEDsSettings`). Controlled via System Dialog button (`btnBellyLamp`) and MQTT commands (`SET_BELLY_LAMP`, `BELLY_LAMP_ON`, `BELLY_LAMP_OFF`).
> - **Situational LED Blinking Engine (`updateSituationLighting`):** Automatically switches drone LED lighting states based on flight conditions:
>   - **Emergency / Low Battery (< 25%):** Rapid warning flash (0.35s toggle interval).
>   - **Takeoff / Landing:** Caution flash (0.50s toggle interval) to alert ground personnel.
>   - **Stealth Mode Active:** All LEDs and lamps completely OFF.
>   - **Normal Flight:** Solid LEDs ON with user belly lamp preference.
>
> **Changelog — 2026-08-06 (Media File Pull Fix & Fallback Pipeline)**
> - **Resolved `get media files failed` Error:** Fixed MSDK V5 `pullMediaFileListFromCamera` failure by setting `count(-1)` parameter in `PullMediaFileListParam.Builder()`.
> - **Automatic Parameter Fallback Retry Pipeline:** If the primary full file list query fails, automatically retries with a default parameter builder (`PullMediaFileListParam.Builder().build()`) to guarantee media list extraction across all DJI Enterprise drone storage types (SD Card & Internal Storage).
>
> **Changelog — 2026-08-06 (Low-Latency Video Stream Optimization & Remote C2 Commands)**
> - **Hardware FPV Rendering Optimization (`bindVideoStream`):** Enforced zero-copy hardware surface formatting (`PixelFormat.TRANSLUCENT`) and direct GPU scaling (`ScaleType.CENTER_CROP`) on the tablet FPV feed, eliminating rendering stutter and CPU decoding lag.
> - **High-Quality Low-Latency RTMP Stream Pipeline (`startRtmpStream`):** Configured direct H.264 hardware encoder passthrough from the primary camera lens (`ComponentIndexType.LEFT_OR_MAIN`), delivering ultra-smooth 1080p video streams with minimal network buffering latency.
> - **Expanded C2 MQTT Stream Commands:** Supported C2 JSON command aliases over `dji-sdk/fleet/{clientId}/command`: `START_STREAM`, `START_RTMP`, `SET_STREAM`, `STREAM_START` (with payload `{"url": "rtmp://..."}` or `{"rtmp_url": "..."}`), and `STOP_STREAM`, `STOP_RTMP`, `STREAM_STOP`.
>
> **Changelog — 2026-08-06 (Touch-to-Focus Object Lock & Stealth Mode LED Control)**
> - **Multi-Lens Touch-to-Focus (`triggerTapToFocus`):** Tapping anywhere on the live video stream calculates normalized $(x, y)$ coordinates and sends target focus commands across Zoom lens (`CAMERA_LENS_ZOOM`), Wide lens (`CAMERA_LENS_WIDE`), and general CameraFocusTarget key, showing an animated green target reticle ring on the touched object.
> - **Stealth Mode (`toggleStealthMode`):** Added a tactical **Stealth Mode** toggle button (`btnStealthMode`) in System Dialog and C2 MQTT command (`STEALTH_MODE`, `STEALTH_ON`, `STEALTH_OFF`, `LIGHTS_OFF`, `LIGHTS_ON`). Simultaneously turns OFF/ON all Front Arm LEDs, Rear Arm LEDs, Flight Status Indicators, Navigation Lights, and Auxiliary Lights (`LEDsSettings(false, false, false, false)`).
>
> **Changelog — 2026-08-06 (High-Precision 3D AR Projection & IMU/VPS Dead Reckoning Navigation)**
> - **High-Precision 3D AR Pinhole Perspective Projection (`ARLandingOverlayView`):** Replaced linear angular subtraction with exact 3D camera transformation matrix (Body to Camera Frame) and pinhole perspective projection ($f_x = \frac{W}{2 \tan(\text{HFOV}/2)}$, $f_y = \frac{H}{2 \tan(\text{VFOV}/2)}$). Completely eliminates visual drift and random position jumping.
> - **Low-Pass Exponential Moving Average (EMA) Temporal Filter:** Integrated real-time EMA low-pass filtering ($\alpha = 0.20$) on projected screen coordinates and target distance to smooth out sensor noise and prevent overlay jittering.
> - **IMU + VPS Dead Reckoning Navigation Engine (`KeyAircraftVelocity`):** When GPS signal drops mid-flight (satellites $< 6$ or GPS loss), the system seamlessly switches to Dead Reckoning (DR) navigation using the drone's 3D IMU velocity vectors ($V_x, V_y, V_z$), downward Vision Positioning System (VPS), and barometric height sensors. Extrapolates WGS-84 geodesic latitude, longitude, and altitude until satellite lock is restored.
>
> **Changelog — 2026-08-06 (ISR Mode 1 Clean FPV Stream Recording, .SRT Telemetry & EXIF Mapping Metadata)**
> - **Clean Raw FPV Video Stream Recording (`FpvStreamRecorder`):** ISR Mode 1 records raw FPV camera feed directly on tablet with zero HUD overlays, buttons, or telemetry text. Uploads `.mp4` video to S3 immediately upon pressing stop.
> - **Toggleable Reticle Target Box & Compass Tape Overlays:** Added toggle buttons `btnToggleReticleOverlay` and `btnToggleCompassOverlay` in ISR Mode 1 settings. Allows user to toggle Target Reticle Box (green square) and top Compass Tape ON/OFF independently for both recording and streaming while keeping all non-tactical HUD UI (buttons/menus) excluded.
> - **Companion `.SRT` Telemetry Subtitles:** Generates live 1-second telemetry samples (GPS Lat, Lon, Alt, Speed, Heading/Yaw, Gimbal Pitch, Satellites, Battery) and exports standard SubRip `.srt` subtitle file alongside the `.mp4` video, uploading both to Ceph S3 automatically.
> - **EXIF Mapping & Photogrammetry Metadata Injection (`injectExifMetadata`):** Automatically injects standard GPS Latitude, Longitude, Altitude, Timestamp, and Drone Telemetry EXIF tags into every Mode 1 JPEG photo before S3 upload for WebODM, Pix4D, QGIS, and ArcGIS compatibility.
> - **SigV4 GET Request Signing Fix (`S3UploadManager`):** Fixed S3 ListObjects GET request signing bugs (HTTP method, canonical query string encoding, and empty payload hash) resolving HTTP 403 Forbidden errors when listing remote S3 folders.
> - **ISR Mode 2 Speed Optimization & Real-time SD Download Progress (`PostFlightS3Sync`):** Wired `onProgress` callback reporting live percentage and MB status (`[1/5] SD Download: IMG_0001.JPG (45% - 18MB / 40MB)`). Enabled parallel S3 upload pipelining while subsequent files download from drone SD card.
> - **ISR Mode 2 Landed State Sync Fix & Manual Sync Button (`btnIsrMode2SyncNow`):** Resolved issue where Mode 2 required motors to spin ON->OFF to trigger. If drone is already landed (motors OFF) when Mode 2 is enabled, sync fires immediately. Added explicit `🔄 SYNC NOW` button in System Dialog to force manual Mode 2 sync anytime.
> - **Simplified MQTT JSON Command Syntax (`command: "photo" / "start" / "stop"` & `isr_mode: 1 / 2 / 0`):** Supported short & clean JSON payload structures like `{"command": "photo"}`, `{"command": "start"}`, `{"command": "stop"}`, and top-level `{"isr_mode": 1}` or `{"isr_mode": 2}`.
> - **MQTT ISR Photo Capture & Sync Commands (`ISR_CAPTURE`, `TAKE_PHOTO`, `PHOTO_CAPTURE`, `CAPTURE`, `ISR_SYNC`):** Added complete command alias suite over MQTT (`dji-sdk/fleet/{clientId}/command`).
> - **Circular Zoom Reset Button (`RES`) & Tap-To-Focus FPV Overlay:** Added circular green `RES` button directly below `GMB` in the HUD button stack to instantly reset camera zoom to 1.0x. Tapping anywhere on the FPV stream now triggers target focus at normalized `(x, y)` coordinates with animated green target ring UI.
> - **Mode 1 & Mode 2 Local Storage Fallback (`saveToLocalStorage`):** Preserves all Mode 1 captured photos/videos and Mode 2 SD sync files directly in designated tablet local storage (`Pictures/ISR_Local_Storage` or custom SAF folder) even if Ceph S3 server is offline or unreachable.
>
> - **GPS-Denied Indoor Flight Engine (`SET_GPS_DENIED_MODE`):** Bypasses pre-flight satellite count requirements (`< 10 sats`), utilizing Downward Vision Positioning System (VPS), IMU, and TOF altitude sensors for indoor/subterranean/air-gapped flight.
> - **Virtual Stick `BODY` Frame Navigation:** Automatically switches Virtual Stick roll/pitch coordinate system from `GROUND` to `BODY` (Forward/Right relative to aircraft nose) when GPS-Denied mode is active.
> - **Confined Space Obstacle Avoidance Tuning (`SET_CONFINED_SPACE_MODE`):** Tunes PerceptionManager obstacle avoidance brake distance down to **1.0m – 1.5m** (from 10.0m default) for narrow doorways and corridor navigation.
>
> **Changelog — 2026-08-06 (MQTT Drone Tasking Engine, Queueing, Cancellation & S3 Upload Status Events)**
> - **MQTT Drone Tasking Engine (`START_TASK`, `QUEUE_TASK`, `CANCEL_TASK`, `CANCEL_ALL_TASKS`, `CLEAR_TASK_QUEUE`):** Integrated full C2 task lifecycle management. Supports string task identifiers (`task_id_101`), sequential task queueing (`ConcurrentLinkedQueue`), and task cancellation.
> - **Auto-Generated Task Storage Folders:** Task assignment automatically auto-generates matching local device download directory (`Pictures/{taskId}`) and Ceph S3 target subfolder (`http://192.168.180.99:8000/data-primary/drone/isr_tasking/captured/{taskId}/`) with `.keep` marker objects.
> - **Real-time Telemetry & S3 Upload Event Broadcasts:** Injected `"taskId"` and `"queuedTasksCount"` into 10 Hz real-time telemetry. Added structured S3 upload events (`UPLOAD_START`, `UPLOAD_SUCCESS`, `UPLOAD_FAILED`) and critical HUD alerts (`HTTP 403 S3 Auth Error`, `S3 Endpoint Unreachable`).
>
> **Changelog — 2026-08-06 (MQTT Remote S3 & Folder Creation Commands)**
> - **MQTT Remote Folder Creation (`CREATE_FOLDER`):** Added C2 command `CREATE_FOLDER` / `CREATE_S3_FOLDER` to explicitly create local download directories and Ceph S3 remote storage subfolders (`.keep` marker object) over MQTT (`dji-sdk/fleet/{clientId}/command`).
> - **MQTT Remote Storage Config (`SET_S3_CONFIG`):** Enabled updating S3 Endpoint URL, Access Key, Secret Key, Region, S3 Target Subfolder (`AUTO` vs `CUSTOM`), Local Download Folder, and ISR Mode (`MODE1`, `MODE2`, `NONE`) dynamically over MQTT.
> - **BT-Prod S3 Storage Endpoint & Credentials:** Updated default S3 endpoint to `http://192.168.180.99:8000/data-primary/drone/isr_tasking/captured` under `data-primary` bucket with region `BT` and user `dji-sdk`.
> - **AWS SigV4 HMAC-SHA256 Signing:** Integrated native AWS Signature Version 4 HMAC-SHA256 request signing in `S3UploadManager.kt` using configured Access Key (`0RUUD1YOR1DLRQN2WF7H`) and Secret Key (`hfGxYhmhBjNL41NUecqyGev5a77H29JfO0DAEkBs`).
>
> **Changelog — 2026-08-06 (MQTT Multi-Route Commands, Clear Isolation, ISR Modes, S3 Storage & ATAK UI)**
> - **Multi-Route C2 Commands & Selective Route Management:** Added `CREATE_ROUTE`, `SELECT_ROUTE`, `DELETE_ROUTE`, `TOGGLE_ROUTE_VISIBILITY`, and `EXECUTE_ROUTE` for itemized multi-route control (`Waypoint_1`, `Waypoint_2`, etc.) over MQTT.
> - **Clear Map vs Clear KMZ Isolation:** Standardized `CLEAR_MAP` (Master clear resetting all map overlays and queues) vs `CLEAR_KMZ` (isolated KMZ route removal preserving user tactical waypoints).
> - **MQTT Connection & URI Sanitization:** Hardened `MqttService.kt` and `MainActivity.kt` URI parsing. Automatically strips duplicate protocol prefixes (e.g. `tcp://tcp://`) and handles `ssl://`, `ws://`, `wss://` cleanly.
> - **Detailed MQTT Error Reporting:** Added `onErrorOccurred` callback in `MqttService.kt` wired directly to `MainActivity.kt` UI toasts and system log for instant diagnostic feedback.
> - **Unified SharedPreferences:** Standardized `SharedPreferences` access (`"TacticalHUDConfig"`, `MODE_PRIVATE`) across `MainActivity` and `MqttService` to eliminate credential reading mismatches.
> - **ISR Modes & S3 Integration:** Bound `btnIsrModeToggle`, `etS3ServerUrl`, and `btnTriggerS3Sync` in `dialog_system.xml` and `MainActivity.kt`. Integrated `S3UploadManager.kt` supporting custom endpoints (`http://192.168.180.99:8000/data-primary/drone/isr_tasking/captured`), ISR Mode 1 (High-Res S3 Direct Stream), and ISR Mode 2 (Post-Flight Auto Sync on Land).
> - **ATAK UI & OSD Reticle Rework:** Redesigned UI reticle to Option A: Military UAV OSD Boresight crosshair with milliradian tick marks and center precision dot. Modernized System Settings, Mapping, WebODM, and Waypoint dialogs with ATAK dark-glass card layouts (`bg_atak_panel`).
>
> **Changelog — 2026-07-28 (Audit #16 Topic Namespace Standardization & AR Thread Safety)**
> - Standardized backend (`server.js`) and frontend web UI (`App.jsx`) MQTT topic subscriptions and command publishing strictly to the `dji-sdk/fleet/` namespace (eliminating legacy `avarell/fleet/` and `tactical/fleet/` mismatches).
> - Hardened thread safety in `ARLandingOverlayView.kt` by replacing `invalidate()` with `postInvalidate()` to prevent `CalledFromWrongThreadException` crashes when invoked from background telemetry/vision threads.
> - Ensured `ARVisionLandingManager.kt` UI callback dispatches run safely on `Handler(Looper.getMainLooper())`.
>
> **Changelog — 2026-07-21 (Audit #15 Waypoint & Storage Hardening)**
> - Fixed Virtual Stick fallback execution to use the orbit-expanded waypoints list instead of raw unexpanded tacticalWaypoints.
> - Avoided concurrent mutation and visual flickering of the main tacticalWaypoints list on background flight threads by utilizing a dedicated execution queue copy.
> - Standardized the KMZ generator turn damping distance formatter with `java.util.Locale.US` to prevent parser failures under international locales.
> - Resolved local storage leaks in the WebODM auto-upload module by ensuring temporary compressed images are cleared on sync failures or exceptions.
>
> **Changelog — 2026-07-20 (Arm/Disarm Safety Checks & LED Blink)**
> - Replaced physical Virtual Stick CSC sequence for `ARM` / `START_ENGINE` with pre-flight connection, battery (min 20%), compass, and device health checks, followed by blinking the LEDs twice to indicate a successful virtual arm state.
> - Modified `DISARM` to set the engine state to inactive instantly and update UI status indicator.
>
> **Changelog — 2026-07-20 (Audit #12 Security, Safety, & Reliability Hardening)**
> - **M-10 (Critical):** Fixed MQTT service executor shutdown by separating `disconnect()` from a new `destroy()` method. Executor is kept alive across reconnect/disconnect events, and shut down only in `MainActivity.onDestroy()`.
> - **H-09 (High):** Added connection-loss checks in the Virtual Stick control loop. Loop aborts automatically if connection is lost.
> - **H-10 (High):** Wired geofencing alerts (`alertBlock`) to real-time location overlays. HUD warning triggers when entering designated No-Fly Zones.
> - **M-11 (Medium):** Replaced mock pairing button on Home screen with actual remote controller pairing flow.
> - **L-09 (Low):** Cleaned up unused and unassigned `tacticalZonePolygon` field.
> - **L-10 (Low):** Cleaned up legacy and concept resources (`MainActivity_new.kt`, `TestRtmp.kt`, etc.).
>
> **Critical (C):**
> - **C-01:** All DJI SDK `performAction` calls inside the Virtual Stick background loop are now wrapped in `runOnUiThread`.
> - **C-02:** `executeTacticalMission` now uses a separate `executionWaypoints` list — the UI `tacticalWaypoints` list is no longer mutated during flight.
> - **C-03:** `expandOrbitWaypoints` now checks `movementMethod == "orbit"` (was checking the wrong field `actionType`).
> - **C-04:** Signal-loss RTH now requires `&& isFlying` guard — cannot trigger on ground.
>
> **High (H):**
> - **H-01:** `handleMqttCommand` no longer runs fully on the UI thread — only explicit UI calls use `runOnUiThread`.
> - **H-02:** `START_ENGINE` `COMPLETED` receipt is published after the CSC sequence finishes, not before.
> - **H-03:** `messageArrived` offloads to executor thread to avoid blocking Paho internals.
> - **H-04:** `updateFlightPathLine` debounced to 500ms (max 2 Hz) from 10 Hz.
> - **H-05:** `orbitCircleOverlays` changed to `CopyOnWriteArrayList`.
> - **H-06:** Log scroll operator precedence fixed.
> - **H-07:** Pre-flight battery/GPS fallback changed from `100/15` to `0/0` (fail-safe).
> - **H-08:** `executor.shutdown()` called in `MqttService.disconnect()`.
>
> **Medium (M):**
> - **M-01:** Orbit button WPs now have `movementMethod = "orbit"` set correctly.
> - **M-02:** VS mission loop thread named `"VS-MissionLoop"`.
> - **M-03:** (Pre-existing CopyOnWriteArrayList on `tacticalWaypoints`).
> - **M-04:** `cancelActiveMission()` clears orbit circle overlays.
> - **M-05:** `drawKmzRouteOnMap` excludes `flightPathPolyline` and orbit overlays.
> - **M-06:** `isPointInPolygon` now uses native Spherical Mercator Math projection to eliminate polar distortion and safely handle complex concave shapes, rather than raw planar lat/lon ray-casting.
> - **M-07:** Takeoff wait thread stored and interrupted in `onDestroy`.
> - **M-08:** `MqttService.connect()` cancels previous `connectFuture` before starting new one.
> - **M-09:** Grid preview recalculation debounced 300ms.
>
> **Low (L):**
> - **L-01:** Removed deprecated `package` attribute from `AndroidManifest.xml`.
> - **L-02 (Deferred):** Lens buttons (Wide/Zoom/IR) remain stubs. DJI SDK V5 uses a different VideoStream source API than V4 (`CameraKey.KeyCameraStreamSource` is unresolved).
> - **L-03:** AR home point uses `cameraFov` field instead of hardcoded `84.0`.
> - **L-04:** `logHistory` changed to thread-safe `StringBuffer`.
> - **L-05:** `droneClickCount` reset is guaranteed in all code paths.
> - **L-06:** `setBuiltInZoomControls` → `zoomController.setVisibility`.
> - **L-07:** `polygon.points` → `polygon.actualPoints`.
> - **L-08:** MQTT credentials now stored in `EncryptedSharedPreferences` (AES256-GCM).

---



## 1. Network Architecture

The drone fleet communicates with a central Command and Control (C2) server exclusively via the **MQTT Protocol**.

- **Protocol:** MQTT v3.1.1
- **Default Port:** `1883`
- **Client Identifiers:** Each drone connects with a unique ID (e.g., `drone_alpha_01`). Configured via the **CFG Tab** in the Advanced System Menu (double-click the Drone Logo on the map).
- **Credentials Configuration:** Fully configurable via the **CFG Tab** in the Advanced System Menu (double-click the Drone Logo on the map). Default fallback credentials are Username `admin` and Password `password`. Saved locally inside SharedPreferences as `mqttUser` and `mqttPass`.
- **Automatic Reconnect:** The Android client reconnects automatically on connection loss.

### QoS Policy

| Direction | QoS Level | Reason |
| :--- | :--- | :--- |
| Drone → Server (Telemetry) | `QoS 0` | Fire-and-forget; optimized for high-frequency 10 Hz broadcasts. |
| Drone → Server (Mission Events) | `QoS 1` | Guaranteed delivery for mission-critical status updates. |
| Server → Drone (Commands) | `QoS 1` | Guaranteed delivery to ensure commands are executed. |

---

## 2. MQTT Topic Structure

Replace `{clientId}` with the specific drone's unique identifier (e.g., `drone_alpha_01`).

| Direction | Topic | QoS | Purpose |
| :--- | :--- | :--- | :--- |
| Drone → Server | `dji-sdk/fleet/{clientId}/telemetry` | 0 | High-frequency flight telemetry (10 Hz). |
| Drone → Server | `dji-sdk/fleet/{clientId}/mission` | 1 | Mission execution events and system logs. |
| Server → Drone | `dji-sdk/fleet/{clientId}/command` | 1 | C2 commands targeting a specific drone. |
| Server → Fleet | `dji-sdk/fleet/broadcast/command` | 1 | Commands broadcast to ALL active drones simultaneously. |
| Server → Fleet | `dji-sdk/fleet/config` | 1 | Global configuration updates for all drones. |

> **Important:** All topics use the `dji-sdk/` prefix. Do not use `tactical/` — that prefix is not recognized by the client.

---

## 3. Telemetry Payload Schema (Drone → Server)

Published to: `dji-sdk/fleet/{clientId}/telemetry`

### 3.1 Standard Flight Telemetry

Sent continuously at approximately 10 Hz while the app is running.

> **Note on Drone ID:** The drone's real unique ID (e.g. `drone_[SERIAL_NUMBER]`) is resolved and registered with the MQTT broker (command topic subscription) immediately upon hardware connection (even without GPS lock/indoors). However, the high-frequency telemetry stream starts broadcasting to the C2 server once a valid GPS/RTK lock is obtained.

```json
{
  "drone_id": "drone_alpha_01",
  "timestamp": 1690000000000,
  "location": {
    "latitude": -6.2000,
    "longitude": 106.8167,
    "altitude_m": 50.0
  },
  "flight_status": {
    "speed_mps": 12.4,
    "heading_deg": 45.0,
    "velocity_x": 0.5,
    "velocity_y": -1.2,
    "velocity_z": 0.1,
    "is_flying": true,
    "is_mission_executing": false,
    "ground_state": "IN_AIR",
    "extended_state": "IN_AIR"
  },
  "hardware": {
    "battery_percent": 85,
    "cell_voltages": [4120, 4122, 4118, 4125],
    "gps_satellites": 14,
    "gps_fix_type": "RTK_FIXED",
    "rtk_supported": true,
    "health_warnings": [
      {
        "title": "Compass Warning",
        "description": "Calibration recommended",
        "warning_level": "WARNING"
      }
    ],
    "signal_quality_percent": 95,
    "uplink_quality_percent": 90,
    "downlink_quality_percent": 95
  },
  "battery": {
    "percentage": 85,
    "cells": [4120, 4122, 4118, 4125]
  },
  "gimbal": {
    "pitch": -25.0,
    "roll": 0.0,
    "yaw": 45.0
  }
}
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `drone_id` | String | Unique drone identifier. **Always present.** |
| `timestamp` | Long | Epoch milliseconds when the sensor data was pulled. |
| `location.latitude` / `.longitude` | Double | WGS84 coordinates. Returns `0.0` or `NaN` if no GPS lock. |
| `location.altitude_m` | Double | Altitude in meters **relative to takeoff point** (not sea level). |
| `flight_status.speed_mps` | Double | Current horizontal flight speed in m/s. |
| `flight_status.heading_deg` | Double | Drone nose heading in degrees (0–360, True North). |
| `flight_status.velocity_x` / `.velocity_y` / `.velocity_z` | Double | Aircraft velocity vectors (x: North, y: East, z: Down/Up) in m/s. |
| `flight_status.is_flying` | Boolean | `true` when airborne (motors spinning and lifted off). |
| `flight_status.is_mission_executing` | Boolean | `true` when a waypoint or KMZ mission is running. |
| `flight_status.ground_state` | String | Ground/air state based on MAVLink EXTENDED_SYS_STATE: `"LANDED"`, `"IN_FLIGHT"`, `"TAKEOFF_IN_PROGRESS"`, `"LANDING_IN_PROGRESS"`, or `"UNKNOWN"`. |
| `flight_status.extended_state` | String | Strict MAVLink EXTENDED_SYS_STATE mapping: `"ON_GROUND"`, `"TAKING_OFF"`, `"IN_AIR"`, or `"LANDING"`. |
| `hardware.battery_percent` | Integer | Remaining battery (0–100). |
| `hardware.cell_voltages` | Array[Integer] | Real-time cell-level battery voltages in millivolts (e.g. `[4120, 4122, ...]`). |
| `hardware.gps_satellites` | Integer | Number of locked satellites. A minimum of `10` is recommended for safe automated flight. |
| `hardware.gps_fix_type` | String | Positioning fix solution: `"RTK_FIXED"`, `"RTK_FLOAT"`, `"GPS_3D"`, `"GPS_2D"`, or `"NO_GPS"`. |
| `hardware.rtk_supported` | Boolean | `true` if the connected aircraft supports/has RTK capabilities. |
| `hardware.health_warnings` | Array[Object] | Active diagnostic warning/error events from the DJI DeviceHealthManager (includes `title`, `description`, `warning_level`). |
| `hardware.signal_quality_percent` | Integer | OcuSync / RC signal strength (0–100). |
| `hardware.uplink_quality_percent` | Integer | Raw AirLink uplink signal quality (0–100). |
| `hardware.downlink_quality_percent` | Integer | Raw AirLink downlink signal quality (0–100). |
| `battery.percentage` | Integer | Remaining battery percentage (0–100). |
| `battery.cells` | Array[Integer] | Granular cell-level battery voltages in millivolts. |
| `gimbal.pitch` | Double | Gimbal pitch in degrees. |
| `gimbal.roll` | Double | Gimbal roll in degrees. |
| `gimbal.yaw` | Double | Gimbal yaw in degrees (True North). |

---

### 3.2 Laser Range Finder (LRF) Target Telemetry

Sent on the **same telemetry topic** when an Enterprise drone (e.g., M3T) fires its Laser Designator.

```json
{
  "type": "lrf_target",
  "timestamp": 1690000005000,
  "distance": 855.2,
  "lat": -6.2050,
  "lon": 106.8165,
  "alt": 25.0
}
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `type` | String | Always `"lrf_target"`. Identifies this as an LRF payload. |
| `timestamp` | Long | Epoch milliseconds of the measurement. |
| `distance` | Double | Physical distance in meters from the drone to the target object. |
| `lat` / `lon` | Double | Calculated GPS coordinates of the **object being targeted** (not the drone). |
| `alt` | Double | Calculated altitude of the target object. |

---

### 3.3 Grid Mission Broadcast

Published when the user successfully generates a mapping grid on the tablet. Sent on the **same telemetry topic** for KMZ-mode missions that publish waypoints, or on the `mission` topic for Virtual Stick grid missions.

```json
{
  "type": "grid_mission",
  "timestamp": 1690000050000,
  "waypoints": [
    {
      "lat": -6.200000,
      "lon": 106.816666,
      "alt": 50.0,
      "speed": 5.0,
      "action": "PHOTO"
    }
  ]
}
```

| Field | Type | Description |
| :--- | :--- | :--- |
| `type` | String | Always `"grid_mission"`. |
| `timestamp` | Long | Epoch milliseconds of mission generation. |
| `waypoints[].lat` / `.lon` | Double | WGS84 coordinate of each waypoint. |
| `waypoints[].alt` | Double | Target altitude in meters. |
| `waypoints[].speed` | Double | Flight speed to this waypoint in m/s. |
| `waypoints[].action` | String | `"PHOTO"` to trigger camera shutter, or `"NONE"`. |

---

## 4. Mission Event Schema (Drone → Server)

Published to: `dji-sdk/fleet/{clientId}/mission`

The drone publishes mission lifecycle events as they happen. All events share the `event` key. The server should monitor this topic to track mission state.

| `event` Value | Additional Fields | Description |
| :--- | :--- | :--- |
| `TAKEOFF_SUCCESS` | — | Automated takeoff completed successfully. |
| `TAKEOFF_FAILED` | `error: String` | Takeoff failed. Includes the error reason. |
| `PHOTO_CAPTURED` | `isMissionExecuting: Boolean`, `quickScan?: Boolean` | A photo was taken. `quickScan: true` if captured during Quick Scan mode. |
| `KMZ_PUSH_STARTED` | — | Tablet has started uploading KMZ file data to drone's flight controller. |
| `KMZ_PUSH_SUCCESS` | — | KMZ file successfully transferred to the drone. |
| `KMZ_PUSH_FAILED` | `error: String` | KMZ file transfer to drone failed. |
| `KMZ_UPLOAD_RECEIVED` | — | A base64 KMZ payload was received via `UPLOAD_KMZ` C2 command. |
| `KMZ_DOWNLOAD_STARTED` | `url: String` | Download of KMZ file from URL has begun. |
| `KMZ_DOWNLOAD_SUCCESS` | — | KMZ file downloaded successfully. |
| `KMZ_DOWNLOAD_FAILED` | `error: String` | KMZ download failed. Includes HTTP code or exception message. |
| `KMZ_PREFLIGHT_FAILED` | `error: String` | Pre-flight check failed. Values: `"Battery Too Low (< 20%)"`, `"Weak GPS Signal (< 10 Sats)"`. |
| `KMZ_STARTED` | — | KMZ mission has begun execution on the drone. |
| `KMZ_FINISHED` | — | KMZ mission completed all waylines. Auto-RTH is triggered after this event. |
| `KMZ_START_FAILED` | `error: String` | Attempt to start KMZ mission failed. |
| `AUTO_RTH_STARTED` | — | Automatic Return-to-Home triggered after mission completion. |
| `MISSION_SAFETY_REJECTED` | `reason: String` | Mission execution was rejected due to a safety/conflict violation (e.g. altitude limits, speed boundaries, or crossing a designated No-Fly/Caution Zone polygon). |
| `WEBODM_SYNC_STATUS` | `status: String`, `isError: Boolean` | Status update from the WebODM auto-upload process. |
| `COMPASS_CALIBRATION_STATUS` | `status: String` | Real-time status/step of the compass calibration procedure. |
| `TIMESYNC` | `ts1: Long`, `tc1: Long` | Active liveness probe echo. `ts1` is the server's requested token, `tc1` is the drone's current microsecond timestamp. |
| `COMMAND_RECEIPT` | `command: String`, `status: String`, `transaction_id?: String`, `error_code?: Int`, `error_message?: String` | Lifecycle updates for C2 commands. Status can be `ACCEPTED`, `EXECUTING`, `COMPLETED`, `FAILED`, or `REJECTED`. |
| `DIAGNOSTIC_WARNING` | `title: String`, `description: String`, `level: String` | Real-time warning/error alert from DJI's DeviceHealthManager. |
| `KMZ_PROGRESS` | `waypoint_index: Int`, `wayline_id: Int`, `mission_file: String` | Real-time waypoint mission progress telemetry. |
| `KMZ_INTERRUPTED` | `error_code: Int`, `description: String` | Triggered if the waypoint mission is interrupted by an error or user action. |
| `LINK_LOSS_FAILSAFE` | `timestamp: Long` | Triggered when MQTT heartbeat is lost for >15s and failsafe RTH/Landing starts. |
| `WAYPOINTS_UPDATED` | `waypoints: Array` | Real-time synchronization event containing the full active queue of waypoints with their names, coordinates, and config options. |

**Example event payload:**
```json
{
  "event": "KMZ_PUSH_FAILED",
  "error": "Storage full on drone"
}
```

**Example waypoints update payload:**
```json
{
  "event": "WAYPOINTS_UPDATED",
  "waypoints": [
    {
      "name": "waypoint_1",
      "lat": -6.205,
      "lng": 106.816,
      "alt": 50.0,
      "speed": 5.0,
      "movementMethod": "linear",
      "actionType": "FLY"
    },
    {
      "name": "waypoint_alpha",
      "lat": -6.208,
      "lng": 106.818,
      "alt": 75.0,
      "speed": 8.0,
      "movementMethod": "orbit",
      "actionType": "PHOTO",
      "poiLat": -6.21,
      "poiLng": 106.82
    }
  ]
}
```

**Example command receipt payload:**
```json
{
  "event": "COMMAND_RECEIPT",
  "timestamp": 1690000000000,
  "command": "TAKEOFF",
  "status": "FAILED",
  "transaction_id": "tx-12345",
  "error_code": 101,
  "error_message": "Low battery failsafe block"
}
```

---

## 5. Command Payload Schema (Server → Drone)

Published to: `dji-sdk/fleet/{clientId}/command` or `dji-sdk/fleet/broadcast/command`

The drone parses the `command` key to determine the action to execute.

### 5.1 Command Summary

| `command` | Mandatory Params | Optional Params | Description |
| :--- | :--- | :--- | :--- |
| `TAKEOFF` | — | — | Automated takeoff to ~1.2m, then enables Virtual Stick. |
| `LAND` | — | — | Automated landing at the current GPS position. |
| `START_ENGINE` / `ARM` | — | — | Performs pre-flight connection, battery, compass, and device health checks, then blinks the aircraft's LEDs twice to indicate a successful virtual arm state. Does not physically spin the motors. |
| `DISARM` | — | — | Sets the engine state to inactive (virtual disarm) and updates UI status indicator immediately. |
| `TIMESYNC` | — | `ts1: Long` | Echoes the timestamp back to the server in a `TIMESYNC` mission event as a liveness probe. |
| `RTH` | — | — | Abort current mission and return to the home point. |
| `SET_HOME` | — | — | Set the drone's current GPS position as the new Home Point. |
| `ADD_WAYPOINT` | `lat`, `lon` | `alt`, `speed`, `heading`, `dwellTime`, `actionType`, `poiLat`, `poiLng`, `gimbalPitch`, `movementMethod` | Appends a single waypoint to the drone's active mission queue. Does not execute. |
| `UPLOAD_MISSION` | `waypoints` (Array) | *(see Waypoint Dictionary)* | Replaces the entire mission queue with a new waypoint array. Does not execute. |
| `CREATE_ROUTE` | `route_name` | `waypoints` (Array), `color` (String/Int) | Creates a distinct named route profile (`Waypoint_1`, `Waypoint_2`, etc.). |
| `SELECT_ROUTE` | `route_name` | — | Switches the active route profile by name. |
| `DELETE_ROUTE` | `route_name` | — | Selectively deletes a specific named route layer from the map and queue without wiping other routes. |
| `TOGGLE_ROUTE_VISIBILITY` | `route_name`, `visible` | — | Shows or hides a specific named route on the map canvas (`visible: true/false`). |
| `EXECUTE_ROUTE` | — | `route_name` (String), `routes` (Array) | Starts executing a specific named route or chained list of routes. |
| `CLEAR_MAP` | — | — | Master reset: completely erases all map overlays, markers, polylines, polygons, and queues. |
| `CLEAR_KMZ` | — | — | Isolated clear: removes imported KMZ polylines and KMZ markers only; preserves user tactical waypoints. |
| `RENAME_WAYPOINT` | `new_name` | `index` (Int), `name` (String) | Renames an existing waypoint matched by index or its current name. |
| `UPDATE_WAYPOINT` | — | `index` (Int), `name` (String) + any parameters to update | Modifies flight parameters of a specific waypoint. |
| `EXECUTE_MISSION` | — | — | Starts flying the loaded waypoint queue via Virtual Stick Engine. |
| `CLEAR_MISSION` | — | — | Erases all waypoints from memory and clears the map. |
| `PHOTO` | — | — | Triggers a single photograph. |
| `RECORD_START` | — | — | Starts video recording. |
| `RECORD_STOP` | — | — | Stops video recording. |
| `GIMBAL` | `pitch` | `yaw` | Moves gimbal to absolute angle values. |
| `SYNC_CONFIG` | `rthAlt`, `obstacleAction` | `signalLossAction` | Applies safety protocol settings to the drone. |
| `SET_MAPPING_MODE` | `mode` | — | Switches mission engine (`QUICK` or `PROFESSIONAL`). |
| `UPLOAD_KMZ` | `data` | — | Pushes a raw WPML/KMZ file as a Base64 string directly to the drone. |
| `DOWNLOAD_KMZ` | `url` | — | Instructs the tablet to download a KMZ file from an HTTP/HTTPS URL. |
| `START_KMZ` | — | — | Executes the most recently uploaded or downloaded KMZ mission. |
| `PAUSE_KMZ` | — | — | Pauses an in-progress KMZ mission. The drone hovers in place. |
| `RESUME_KMZ` | — | — | Resumes a paused KMZ mission from the last waypoint. |
| `STOP_KMZ` | — | — | Stops the active KMZ mission entirely. |
| `START_RTMP` | `url` | — | Starts an RTMP video stream to the given URL. |
| `STOP_RTMP` | — | — | Stops an active RTMP video stream. |
| `PING` | — | `timestamp` | Liveness heartbeat probe. Drone replies with a `PONG` event. |
| `START_COMPASS_CALIBRATION` | — | — | Triggers native DJI compass calibration procedure. |
| `STOP_COMPASS_CALIBRATION` | — | — | Stops/cancels an active compass calibration procedure. |

> **Note on Transaction IDs:** Any command sent from the C2 server can optionally include a `transaction_id` String (e.g. `{"command": "TAKEOFF", "transaction_id": "tx-100"}`). The drone will include this ID in all generated `COMMAND_RECEIPT` events (`ACCEPTED`, `EXECUTING`, `COMPLETED`, `FAILED`, `REJECTED`) for request tracking.

---

### 5.2 Command Payloads

**Auto Takeoff**
```json
{ "command": "TAKEOFF" }
```

**Auto Land**
```json
{ "command": "LAND" }
```

**Return to Home (RTH)**
```json
{ "command": "RTH" }
```

**Set Home Point to Current Location**
```json
{ "command": "SET_HOME" }
```

**Start Motors / Virtual Arm (No Takeoff)**
Performs pre-flight validation (checks pairing connection, battery ≥ 20%, compass health, and device diagnostic logs). If checks pass, the drone blinks its navigation LEDs twice and returns `COMPLETED` receipt.
```json
{ "command": "START_ENGINE" }
```

**Disarm / Stop Engine**
Sets the drone's virtual arm state to inactive instantly and updates the UI.
```json
{ "command": "DISARM" }
```

**Add a Single Waypoint**
```json
{
  "command": "ADD_WAYPOINT",
  "lat": -6.200000,
  "lon": 106.816666,
  "alt": 50.0,
  "speed": 5.0,
  "heading": 90.0,
  "dwellTime": 5.0,
  "actionType": "LOCK_POI",
  "poiLat": -6.2050,
  "poiLng": 106.8165,
  "movementMethod": "orbit",
  "orbitRadius": 25.0,
  "orbitLoops": 2
}
```

**Upload Complete Mission (replaces existing queue)**
```json
{
  "command": "UPLOAD_MISSION",
  "waypoints": [
    {
      "lat": -6.200,
      "lng": 106.800,
      "alt": 30.0,
      "speed": 10.0,
      "heading": 90.0,
      "dwellTime": 5.0,
      "actionType": "LOCK_POI",
      "poiLat": -6.205,
      "poiLng": 106.805,
      "movementMethod": "linear"
    },
    {
      "lat": -6.210,
      "lng": 106.810,
      "alt": 50.0,
      "speed": 15.0,
      "actionType": "START_RECORD",
      "movementMethod": "spline"
    },
    {
      "lat": -6.220,
      "lng": 106.820,
      "alt": 50.0,
      "speed": 10.0,
      "heading": 180.0,
      "actionType": "PHOTO",
      "movementMethod": "orbit",
      "orbitRadius": 30.0,
      "orbitLoops": 3
    }
  ]
}
```

> **Note:** `UPLOAD_MISSION` uses `lng` (not `lon`) for the longitude key inside the waypoints array. `ADD_WAYPOINT` uses `lon`. This distinction is enforced by the client parser. Both commands fully support trajectory control fields (`movementMethod`: `"linear" | "spline" | "orbit"`, `orbitRadius` (meters), `orbitLoops` (count)).

**Execute Mission**
```json
{ "command": "EXECUTE_MISSION" }
```

**Clear All Waypoints**
```json
{ "command": "CLEAR_MISSION" }
```

**Rename Waypoint**
Renames a waypoint by its sequential list `index` (0-based) or by its current `name`.
```json
{
  "command": "RENAME_WAYPOINT",
  "name": "waypoint_2",
  "new_name": "waypoint_observation_delta"
}
```

**Update Waypoint**
Modifies the parameters of an existing waypoint matched by `index` or `name`. Only fields present in the payload will be overwritten.
```json
{
  "command": "UPDATE_WAYPOINT",
  "index": 1,
  "altitude": 80.0,
  "speed": 7.5,
  "actionType": "PHOTO",
  "poiLat": -6.2052,
  "poiLng": 106.8168
}
```

**Capture Photo**
```json
{ "command": "PHOTO" }
```

**Start Video Recording**
```json
{ "command": "RECORD_START" }
```

**Stop Video Recording**
```json
{ "command": "RECORD_STOP" }
```

**Gimbal Absolute Positioning**
```json
{
  "command": "GIMBAL",
  "pitch": -45.0,
  "yaw": 0.0
}
```
- `pitch`: `0.0` = horizon, `-90.0` = straight down.
- `yaw`: Rotates gimbal laterally (hardware-dependent support).

**Sync Safety Configuration**
```json
{
  "command": "SYNC_CONFIG",
  "rthAlt": 100,
  "obstacleAction": "BRAKE",
  "signalLossAction": "GOHOME"
}
```
- `rthAlt`: Integer (`20`–`500`). Altitude in meters the drone climbs to before flying home.
- `obstacleAction`: `"BRAKE"` (stop and hover), `"BYPASS"` (use APAS to fly around), or `"OFF"` (disable avoidance).
- `signalLossAction`: `"GOHOME"` (return to home), `"LANDING"` (auto land in place), or `"HOVER"` (hover in place).

**Set Mapping Engine Mode**
```json
{
  "command": "SET_MAPPING_MODE",
  "mode": "PROFESSIONAL"
}
```
- `mode`: `"QUICK"` — Virtual Stick engine, suitable for dynamic or indoor flights. `"PROFESSIONAL"` — DJI native KMZ engine, for precise GPS mapping.

**Upload KMZ via Base64**
```json
{
  "command": "UPLOAD_KMZ",
  "data": "<BASE64_ENCODED_KMZ_FILE_CONTENT>"
}
```
Suitable for small KMZ files. For large files, prefer `DOWNLOAD_KMZ` to avoid MQTT payload size limits.

**Download KMZ from URL**
```json
{
  "command": "DOWNLOAD_KMZ",
  "url": "http://192.168.180.99:8000/missions/tactical_area_1.kmz"
}
```
The tablet downloads the KMZ directly from the given URL, bypassing MQTT payload limits. Ideal for S3, MinIO, or Nginx-hosted mission files.

**Execute Loaded KMZ Mission**
```json
{ "command": "START_KMZ" }
```
Executes the most recently loaded KMZ. Includes a pre-flight check (battery ≥ 20%, GPS ≥ 10 satellites) before takeoff. Publishes `KMZ_PREFLIGHT_FAILED` on the mission topic if checks fail.

**Pause KMZ Mission**
```json
{ "command": "PAUSE_KMZ" }
```

**Resume KMZ Mission**
```json
{ "command": "RESUME_KMZ" }
```

**Stop KMZ Mission**
```json
{ "command": "STOP_KMZ" }
```

**Start RTMP Stream**
```json
{
  "command": "START_RTMP",
  "url": "rtmp://192.168.1.100:1935/live/drone_alpha_01"
}
```

**Stop RTMP Stream**
```json
{ "command": "STOP_RTMP" }
```

---

### 5.3 Waypoint Parameter Dictionary

Applies to both `ADD_WAYPOINT` (top-level keys) and each object in the `UPLOAD_MISSION.waypoints` array.

| Parameter | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `lat` | Double | **YES** | — | Target latitude (WGS84). |
| `lon` / `lng` | Double | **YES** | — | Target longitude (WGS84). Use `lon` for `ADD_WAYPOINT`, `lng` for `UPLOAD_MISSION` array items. |
| `name` | String | No | `"waypoint_N"` | Custom name label for the waypoint. Sequentially auto-generated if omitted. |
| `alt` | Double | No | `50.0` | Target altitude in meters relative to takeoff point. |
| `speed` | Double | No | `10.0` for upload, `5.0` for add | Flight speed to this waypoint in m/s. |
| `heading` | Double | No | — | Yaw angle (0–360) the drone faces while traveling to this waypoint. |
| `dwellTime` | Double | No | — | Hover duration at the waypoint in seconds before continuing. |
| `movementMethod` | String | No | `"default"` | Movement style: `"default"`, `"spline"`, `"orbit"`. *(Spline/Orbit in development)* |
| `actionType` | String | No | `"FLY"` | Camera or gimbal action at this waypoint. See valid values below. |
| `poiLat` / `poiLng` | Double | Conditional | — | **Required** when `actionType` is `LOCK_POI`, `PHOTO`, or `START_RECORD`. GPS coordinate of the camera target. |
| `gimbalPitch` | Double | Conditional | — | **Required** when `actionType` is `SET_GIMBAL`. Target pitch angle (0 to -90). |
| `orbitRadius` | Double | No | `30.0` | Radius in meters of the circle generated around the center point (used only when `actionType` is `ORBIT`). |
| `orbitLoops` | Integer | No | `1` | Number of full orbits to perform before continuing (used only when `actionType` is `ORBIT`). |

**Valid `actionType` values:**

| Value | Behavior |
| :--- | :--- |
| `FLY` | No camera action. Fly through the waypoint. |
| `PHOTO` | Stop at waypoint, aim gimbal at `poiLat/poiLng`, capture a photo, then resume. |
| `LOCK_POI` | Lock gimbal continuously onto `poiLat/poiLng` (surveillance overwatch). No recording. |
| `UNLOCK_POI` | Release any active gimbal lock and reset camera to forward-facing. |
| `START_RECORD` | Begin video recording and lock gimbal onto `poiLat/poiLng`. |
| `STOP_RECORD` | Stop video recording and release gimbal lock. |
| `SET_GIMBAL` | Move gimbal to an absolute `gimbalPitch` angle. |
| `ORBIT` | Automatically expands this single waypoint coordinate into a circular orbit path of 12 coordinated-turn spline waypoints around this coordinate. The camera/gimbal automatically locks onto this coordinate as the POI target. |

---

## 6. Command Aliases

The following alternate command strings are accepted and mapped to their canonical equivalents:

| Alias | Canonical Command |
| :--- | :--- |
| `TAKE_OFF` | `TAKEOFF` |
| `AUTO_TAKEOFF` | `TAKEOFF` |
| `ARM` | `START_ENGINE` |

---

## 7. Security & Deployment Notes

1. **No Internet Requirement:** Deploy without relying on external APIs (e.g., Google Maps). The app uses ArcGIS tile URLs by default; for air-gapped ops, configure OSMDroid to use a local tile server.
2. **Reconnect Handling:** The Android client auto-reconnects on broker disconnect. The server must handle abrupt socket disconnects gracefully and not treat reconnects as new drones.
3. **Command Deduplication:** Do not spam `EXECUTE_MISSION`. If a mission is already executing, repeated execute commands are safely ignored by the client.
4. **Command Fight Prevention:** Send `CLEAR_MISSION` before `UPLOAD_MISSION` to guarantee a clean mission state. Calling `UPLOAD_MISSION` already clears the queue before loading, but explicit `CLEAR_MISSION` is recommended for safety-critical ops.
5. **KMZ Payload Size:** `UPLOAD_KMZ` via MQTT is limited by broker `max_packet_size`. For files larger than ~100 KB, use `DOWNLOAD_KMZ` with a URL instead.
6. **Pre-flight Gate:** `START_KMZ` has a built-in pre-flight check. If battery is below 20% or GPS satellites are fewer than 10, the mission is rejected and a `KMZ_PREFLIGHT_FAILED` event is published. The server should listen for this before assuming the mission started.
7. **Authentication:** Configurable username and password (saved as `mqttUser` and `mqttPass` in SharedPreferences). Default fallback credentials are `admin` / `password`. Ensure you configure secure, custom broker credentials through the Advanced System Settings dialog for deployment outside a private lab network.

---

## 8. Server Infrastructure Requirements

### A. MQTT Broker (Core Requirement)
- **Software:** Eclipse Mosquitto (lightweight) or EMQX (enterprise).
- **Configuration:** Must bind to `0.0.0.0:1883`.
- **Authentication:** For tactical deployment, disable anonymous connections and enforce mutual TLS (mTLS) or username/password auth.

### B. Offline Map Tile Server (Air-Gapped Ops)
- **Software:** GeoServer, MapTiler, or a simple Nginx HTTP server.
- **Function:** Serve XYZ tiles at `http://[SERVER_IP]/tiles/{z}/{x}/{y}.png` on port `80` or `8080` for the Android OSMDroid map.

### C. KMZ / Mission File Server
- **Software:** Any HTTP file server (Nginx, MinIO, S3-compatible).
- **Function:** Host `.kmz` files accessible via HTTP URL for the `DOWNLOAD_KMZ` command. No authentication required if on a private network; add HTTP Basic Auth or signed URLs for shared infrastructure.

### D. WebRTC Signaling Server (Upcoming — Video Relay)
- **Software:** Node.js (Socket.io) or Python (WebSockets).
- **Function:** Negotiate SDP and ICE candidates between the Android drone client and the Command Center browser for a direct peer-to-peer H.264 stream.

### E. WebODM Integration (Photogrammetry)
- **Software:** WebODM (self-hosted).
- **Function:** The app can auto-upload captured photos to a configured WebODM project for 3D mapping/orthophoto generation. Configure via the long-press on the `SYNC` button in the app.

### F. Required Firewall Ports

| Port | Protocol | Service |
| :--- | :--- | :--- |
| `1883` | TCP | MQTT C2 Broker |
| `80` / `8080` | TCP | Offline Map Tile Server |
| `8000` | TCP | KMZ / Mission File Server & WebRTC Signaling |
| `3478` | TCP/UDP | STUN/TURN for Video NAT Traversal |

---

## 9. Drone Movement & Trajectory Control

The tactical drone client supports two primary flight execution methods and three distinct trajectory patterns. These can be configured via C2 server commands or the mobile app's Advanced Settings.

### 9.1 Flight Execution Engines

#### A. Virtual Stick Control Loop (`QUICK` Mode)
- **Concept:** Continuous, real-time joystick commands (Roll, Pitch, Yaw, Throttle) are streamed from the client tablet to the aircraft flight controller at a high frequency (~10-20 Hz).
- **Coordinate Systems:**
  - **`BODY` Coordinate Frame:** Yaw and motion vectors are calculated relative to the current nose direction of the drone.
  - **`GROUND` Coordinate Frame:** Motion vectors are calculated relative to absolute coordinates (North/East/Up), independent of drone nose heading.
- **Safety Interlocks:** Tapping manual **LAND** or triggering **RTH** automatically disables the Virtual Stick engine to prevent fight-for-control scenarios between autonomous loops and local/SDK failsafe functions.

#### B. Native KMZ Execution (`PROFESSIONAL` Mode)
- **Concept:** Mission specifications are bundled into standard Waypoint Markup Language (`wpml` / `.kmz`) files. The client generates and uploads these files directly onto the aircraft's onboard memory before takeoff.
- **Key Advantages:**
  - **Link-Loss Autonomy:** The mission continues and completes even if the connection to the tablet or C2 server is completely lost during flight.
  - **Multi-Wayline Support:** Autostarted KMZ missions dynamically retrieve all available waylines using native SDK capabilities instead of hardcoding a single path.

### 9.2 Waypoint Trajectory Profiles

When submitting waypoint lists via `UPLOAD_MISSION` or appending individual points with `ADD_WAYPOINT`, the `movementMethod` parameter determines the trajectory profile between points:

| Profile | `movementMethod` Value | Flight Behavior |
| :--- | :--- | :--- |
| **Spline / Coordinated Turn** | `"spline"` | The drone flies a smooth, continuous curve through waypoints without stopping. This is achieved using WPML cubic-spline interpolation (`coordinateTurn` mode) to maximize battery efficiency and camera speed. |
| **Orbit** | `"orbit"` | The drone performs a circular trajectory around a designated Point of Interest (POI), keeping the camera locked onto target coordinates. |

---

## 10. Revision History

### v1.1.4 (Current)
- Hardened thread-safety and lifecycle management during high-frequency telemetry operations by registering and interrupting background threads (`takeoffWaitThread`, `ledBlinkThread`) upon Activity destruction.
- Resolved memory leaks in singleton listener managers (`PayloadDetectionManager`, `WebODMAutoUpload`) by implementing and calling cleanup methods to clear cached lambda observers in `onDestroy()`.
- Stabilized command receipt responses and UI updates during mission cancellations by verifying execution threads and guarding main-thread UI operations against destroyed activity contexts.

### v1.1.3
- Implemented C2 heartbeat monitoring using a direct MQTT PING/PONG query pattern.
- Integrated structured Command Receipts (`ACCEPTED`, `EXECUTING`, `COMPLETED`, `FAILED`, `REJECTED`) containing `transaction_id` for fleet control traceability.
- Added support for remote compass calibration via `START_COMPASS_CALIBRATION` and `STOP_COMPASS_CALIBRATION` commands, with active status reporting.
- Expanded standard telemetry payloads to report cell-level battery voltages, raw uplink/downlink AirLink signal qualities, and flight ground state transitions.

### v1.1.2
- Refactored map path rendering logic to support offline/disconnected mission drawing: when drone telemetry or GPS lock is missing, the planned waypoint mission line draws starting from the first waypoint instead of hiding.

### v1.1.1
- Resolved drone unique ID immediately upon hardware connection (enabling indoor command subscription without prior GPS lock).
- Fully implemented telemetry fields `is_flying` and `is_mission_executing` inside `flight_status` using KeyManager and internal state variables.
- Fully implemented telemetry fields `gps_satellites` and `signal_quality_percent` inside `hardware` using live OcuSync and satellite counts.
- Updated KMZ pre-flight check logic to query KeyManager variables directly rather than parsing UI text fields.
- Prevented map route overlays from accidentally clearing the drone's real-time heading marker line.

---

## 11. Tactical Gateway Error Code Reference

When commands fail or get rejected, the drone publishes a `COMMAND_RECEIPT` event with `"status": "FAILED"` or `"status": "REJECTED"`. These events contain an `error_code` (Int) and `error_message` (String). The codes are split into **Custom Gateway Validation Errors** (negative integers) and **Native DJI SDK V5 Error Codes** (forwarded positive integers).

### 11.1 Custom Gateway Errors (Negative Integers)

These errors are generated locally within the Android gateway application's pre-flight checking, safety validation, and C2 command parsing systems:

| Error Code | Occurs In | Description / Trigger Condition |
| :--- | :--- | :--- |
| **`-1`** | Any Command | **Command Parse Error:** The incoming JSON payload was malformed or could not be parsed. |
| **`-10`** | `EXECUTE_MISSION` | **Already Running:** A waypoint mission is currently executing. You must stop or pause the active mission first. |
| **`-11`** | `EXECUTE_MISSION` | **Empty Mission:** No waypoints are loaded in the queue to be executed. |
| **`-12`** | `EXECUTE_MISSION` | **Safety Boundary Violation:** A waypoint fell inside a designated No-Fly Zone / restriction area. |
| **`-13`** | `EXECUTE_MISSION` | **Battery Threshold Failure:** Battery level is below the pre-flight safety threshold (< 20%). |
| **`-14`** | `EXECUTE_MISSION` | **GPS Signal Weakness:** Aircraft GPS satellite count is below the minimum safety threshold (< 10 satellites). |
| **`-15`** | `EXECUTE_MISSION` | **Home Point Unlocked:** The drone has not established a valid GPS lock / Home Point required for takeoff. |
| **`-16`** | `EXECUTE_MISSION` | **General Pre-flight Exception:** An unexpected runtime error occurred during the pre-flight routine. |
| **`-20`** | `START_KMZ` | **KMZ Missing:** No KMZ flight path file has been loaded or selected on the device. |
| **`-21`** | `START_KMZ` | **Invalid KMZ Format:** The loaded KMZ file contains no executable waylines inside its structure. |
| **`-22`** | `DOWNLOAD_KMZ` | **Download Failure:** A connection exception or socket timeout occurred during KMZ file downloading. |
| **`HTTP XXX`** | `DOWNLOAD_KMZ` | **HTTP Status Code:** Direct HTTP status code returned by the server (e.g., `404` for File Not Found, `500` for Internal Server Error). |
| **`-404`** | Any Command | **Unknown Command:** The command name is not registered or supported by the Tactical Gateway parser. |

### 11.2 Common Native DJI SDK V5 Errors (Positive Integers)

These error codes are forwarded directly from the aircraft hardware components via the DJI SDK. Below are common codes representing hardware/sensor blocks:

| Error Code | Category | Description |
| :--- | :--- | :--- |
| **`314`** / **`315`** | Flight Controller | Aircraft Compass Error or GPS State unhealthy (cannot takeoff/arm). |
| **`316`** | Flight Controller | Motor arm/start failed (e.g., IMU initializing, gimbal self-check running, or RC stick command conflict). |
| **`320`** | Flight Controller | Flight controller command rejected due to active Landing/RTH state. |
| **`4001`** | Gimbal | Gimbal rotation command rejected (e.g., gimbal is physically locked or at structural rotation limit). |
| **`8001`** | Camera | Camera state error (e.g., attempting to capture a photo while the SD Card is full, missing, or formatting). |
| **`10001` - `10010`** | Waypoint Engine | KMZ File format parsing or validation errors (e.g., incorrect coordinate formats, illegal speed values). |
| **`10101`** | Waypoint Engine | Waypoint mission execute failed (e.g., aircraft is currently too far from the starting waypoint). |