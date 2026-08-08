# Tactical Drone C2 Server Specification

> **Language standard:** ASD-STE100 Simplified Technical English.
> **Applies to:** branch `fix/critical-flight-safety-and-c2-defects`.
> **Last change:** 2026-08-09.

This document gives the interface between the Recreate2 Android application and the C2 server. It
gives the MQTT topics, the data schemas and the commands.

---

## 0. Status and open items

Read this section before you write server code. The items below are not correct in the current
build.

### 0.1 The `avarell/` topic namespace is deprecated

Do not use the `avarell/` namespace in new code. Use `dji-sdk/fleet/`.

The correction of 2026-08-09 changed the Android application, the backend and the KMZ hub:

| File | Position | Old topic | New topic |
|---|---|---|---|
| `backend/server.js` | 111 | `avarell/fleet/config` | `dji-sdk/fleet/config` |
| `backend/server.js` | 166 | `avarell/fleet/config` | `dji-sdk/fleet/config` |
| `kmz_hub/kmz_hub.py` | 77 | `avarell/fleet/+/mission` | `dji-sdk/fleet/+/mission` |
| `kmz_hub/kmz_hub.py` | 147, 166, 185 | `avarell/fleet/broadcast/command` | `dji-sdk/fleet/broadcast/command` |

Before this correction, the configuration push did not reach the aircraft. The KMZ hub did not
receive mission events and its commands did not reach the aircraft.

#### The web interface keeps the old namespace

The owner of the project holds the web interface (`frontend/`) outside this work. Two positions in
`frontend/src/App.jsx` still use the `avarell/` namespace:

| Position | Topic | Effect |
|---|---|---|
| 400 | `avarell/fleet/config` | No component subscribes. The publication has no effect. |
| 456 | `avarell/fleet/drone_sim_01/telemetry` | The backend does not receive the simulated aircraft. |

The configuration push still operates. `App.jsx` sends the same data two times: one time to the
broker on the old topic, and one time to the backend with Socket.io (`push_config`). The backend
then sends it to `dji-sdk/fleet/config`. The aircraft receives the data on the second path.

The simulated aircraft does not reach the backend or the database.

**NOTE: `MQTT_USERNAME=avarell` in the `.env` files is a broker user name. It is not a topic. Do not
change it.**

### 0.2 No server sends PING

The application answers a `PING` command with a `PONG` event. This function is complete. No
component of the server sends `PING`.

The link-loss failsafe therefore uses the broker connection only. It makes a Go-Home command when
the broker connection stops for more than 15 seconds. It does not detect a server that is connected
but not operational.

**Correction:** the server must send a `PING` command at a regular interval. Then the failsafe can
also use command inactivity.

### 0.3 The application has no WebRTC function

Earlier versions of this document gave a native WebRTC WHIP engine
(`NativeWebRtcStreamManager.kt`). That code never sent video data. The frame listener was empty and
the SDP offer had a fixed example fingerprint. The file is deleted.

The application sends video with the DJI hardware encoder only. Use RTMP or RTSP. A relay server
such as go2rtc must change the video to WebRTC for a web browser.

### 0.4 The application does not encrypt the settings

Earlier versions of this document gave `EncryptedSharedPreferences` with AES256-GCM. The
application does not use it. The application keeps all settings in plain SharedPreferences.

### 0.5 The tests against an aircraft are not complete

The motor start commands, the WPML mission file and the virtual stick control values are not tested
against hardware. Do a bench test with the propellers removed.

---

## 0.6 Revision record

| Version | Date | Changes |
|---|---|---|
| v1.2.0 | 2026-08-09 | Corrected 37 defects. Refer to the list below. |
| v1.1.4 | 2026-08-07 | Stopped background threads at activity destruction. Removed listener memory leaks. |
| v1.1.3 | 2026-08-06 | Added `PING`/`PONG`. Added command receipts with `transaction_id`. Added compass calibration commands. Added cell voltages and link quality to the telemetry. |
| v1.1.2 | 2026-08-05 | The map draws a planned mission when the aircraft has no GPS position. |
| v1.1.1 | 2026-08-04 | Read the aircraft serial number at connection. Added `is_flying` and `is_mission_executing`. |

### Changes in v1.2.0

Flight safety:

- The link-loss failsafe uses the broker connection. It no longer uses command inactivity.
- The virtual stick loop sends velocity values. It no longer sends zero values.
- The control loop has no long delays. The photo action is a state machine.
- `ARM` and `DISARM` operate the motors. The receipt shows the true motor state.
- The C2 command dispatch operates on the main thread.
- The follow function puts back the initial obstacle avoidance mode.

Interface:

- The telemetry publish operates at 10 Hz. Earlier builds sent no telemetry.
- Non-finite numbers become `null` in the telemetry payload. Earlier builds sent no payload.
- The MQTT client reconnects. Earlier builds could not reconnect after the first connection.
- The task queue continues after a mission is complete.

Missions:

- `waylines.wpml` has all necessary WPML elements.
- The KMZ file keeps the waypoint camera actions and gimbal actions.
- The one-second photo action operates on survey missions only.

Storage and video:

- The S3 keys are removed from the source code. Set them with `SET_S3_CONFIG`.
- The S3 signature uses one clock read and an encoded path.
- ISR Mode 2 does not send the same file two times.
- An `rtsp://` address makes an RTSP server. Earlier builds sent it as an RTMP address.
- The stream address keeps the host name that the operator gives.

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

## 7. Security and Deployment

**CAUTION: The application keeps all settings in plain SharedPreferences. It does not encrypt them.
Do not put an operational password on a tablet that other persons can get.**

1. **No internet connection is necessary.** The application does not use an external service such as
   Google Maps. The application uses ArcGIS tile addresses by default. For an air-gapped network, set
   OSMDroid to use a local tile server.
2. **Reconnection.** The application connects to the broker again after a disconnection. The server
   must accept a reconnection from the same aircraft. The server must not make a new aircraft record.
3. **Two mission commands.** Do not send `EXECUTE_MISSION` more than one time. The application
   ignores the command when a mission operates.
4. **Mission state.** Send `CLEAR_MISSION` before `UPLOAD_MISSION`. `UPLOAD_MISSION` clears the
   waypoint list, but `CLEAR_MISSION` makes the state sure.
5. **KMZ file size.** The broker parameter `max_packet_size` limits `UPLOAD_KMZ`. For a file of more
   than about 100 KB, use `DOWNLOAD_KMZ` with an address.
6. **Pre-flight check.** `START_KMZ` does a pre-flight check. The application rejects the mission
   when the battery charge is less than 20 percent or the satellite count is less than 10. The
   application then sends a `KMZ_PREFLIGHT_FAILED` event. The server must wait for this event before
   it reports that the mission operates.
7. **Authentication.** Set the user name and the password in the system menu. The application keeps
   them as `mqttUser` and `mqttPass`. The default values are `admin` and `password`. Change these
   values before you use the system outside a private network.
8. **Storage keys.** The application has no S3 keys in the source code. Send the keys with the
   `SET_S3_CONFIG` command, or set them in the system menu. An upload fails with HTTP 401 or 403
   when the keys are not set.

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

## 10. Revision Record

The revision record is in Section 0.6 at the start of this document. Section 0.6 also gives the
open items that a server engineer must know.

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
