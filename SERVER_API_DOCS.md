# Tactical Drone C2 Server Specification

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

**Example event payload:**
```json
{
  "event": "KMZ_PUSH_FAILED",
  "error": "Storage full on drone"
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
- Fixed stream cleanup logic in media downloader to close file streams and clean up temporary downloads on failure.
- Prevented map route overlays from accidentally clearing the drone's real-time heading marker line.