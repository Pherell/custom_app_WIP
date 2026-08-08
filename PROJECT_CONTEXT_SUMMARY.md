# Recreate2 — Project Context

> **Language standard:** ASD-STE100 Simplified Technical English.
> **Last change:** 2026-08-09.

This document gives the structure of the workspace. Use it to find the correct file for a change.
For operation instructions, refer to `README.md`. For the C2 interface, refer to
`SERVER_API_DOCS.md`.

---

## 1. Workspace structure

| Directory | Contents |
|---|---|
| `app/` | The Android application |
| `tactical_server/` | The server stack in Docker containers |
| `plan/`, `scratch/` | Work notes. Not part of a build. |
| `AegisNet_clone/`, `GhostRider_clone/`, `dji-firmware-tools_clone/` | Reference code from other projects |

---

## 2. Android application

### 2.1 Technical data

| Item | Value |
|---|---|
| Language | Kotlin |
| SDK | DJI MSDK v5.18.0 |
| Map | OSMDroid with ArcGIS satellite images |
| Network | Eclipse Paho MQTT v3, OkHttp 4 |
| Minimum Android | 7.0 (API 24) |
| Target Android | 15 (API 35) |
| Size | 14,017 lines in 23 Kotlin files |

**CAUTION: The application keeps the settings in plain SharedPreferences. It does not encrypt them.
An earlier version of this document gave `EncryptedSharedPreferences`. That statement was not
correct.**

### 2.2 Main source files

| File | Lines | Function |
|---|---|---|
| `MainActivity.kt` | 8,929 | The HUD, the map, the mission engine, the C2 dispatch, the camera controls and all dialogs |
| `aws/S3UploadManager.kt` | 543 | HTTP upload to the S3 endpoint with an AWS SigV4 signature |
| `sync/FpvStreamRecorder.kt` | 473 | Records the video image on the tablet. Makes an MP4 file and an SRT file. |
| `WebODMAutoUpload.kt` | 449 | Sends photos to a WebODM server |
| `KmzGenerator.kt` | 415 | Makes a DJI WPML mission file |
| `sync/ISRModeManager.kt` | 410 | ISR Mode 1. Gets one photo and sends it to S3. |
| `sync/PostFlightS3Sync.kt` | 370 | ISR Mode 2. Sends all new files after the motors stop. |
| `ObjectTrackingOverlayView.kt` | 301 | Draws the target box on the video image |
| `HomeActivity.kt` | 251 | Permissions, SDK start, registration, remote controller link |
| `MqttService.kt` | 220 | The Paho MQTT client |
| `virtualstick/OnScreenJoystick.kt` | 193 | The touch joystick |
| `task/DroneTaskManager.kt` | 193 | The C2 task queue |
| `sync/WaypointRouteManager.kt` | 144 | The named route store |
| `flight/ConfinedSpaceFlightManager.kt` | 137 | The indoor and GPS-denied modes |
| `PayloadDetectionManager.kt` | 128 | Finds the aircraft type, the lenses and the laser rangefinder |
| `GpsTaggingManager.kt` | 101 | Keeps the target coordinate tags |
| `ObstacleRadarView.kt` | 98 | Draws the obstacle radar |
| `CompassView.kt` | 88 | Draws the compass |

**NOTE: `MainActivity.kt` has 64 percent of the code. Divide this file before you add a large
function to it.**

### 2.3 Files that do not operate

| File | Condition |
|---|---|
| `ARLandingOverlayView.kt` | Only `res/layout/ui_v2_concept.xml` uses this view. The application does not use that layout. |
| `tracking/CustomUnlimitedFollowEngine.kt` | The follow function is off. The application has no object detector. |

`ARVisionLandingManager.kt` is deleted. No code made an instance of that class. An earlier version
of this document gave an OpenCV and ArUco landing function. That function did not exist.

### 2.4 Layout files

`res/layout/activity_main.xml` is the layout that the application uses. Two other layouts are in the
directory. Do not use them:

- `ui_v2_concept.xml` has 40 of the 63 necessary view names missing.
- `activity_main_v1_backup.xml` has 11 of the 63 necessary view names missing.

**CAUTION: The application has 198 view lookups that cannot accept a null result. If you remove or
rename a view name in `activity_main.xml`, the application stops. Four positions also change the
layout parameters to `ConstraintLayout.LayoutParams`. Do not put `mapView` or `fpvSurface` in
another container.**

---

## 3. Server stack

`tactical_server/docker-compose.yml` starts these containers:

| Container | Function |
|---|---|
| `mqtt-broker` | Mosquitto. Ports 1883 and 9001. |
| `backend-api` | Node.js Express and Socket.io. Writes telemetry to the database. |
| `db` | PostgreSQL. Keeps the telemetry history. |
| `c2-frontend` | React and Cesium web interface |
| `map-server` | `tileserver-gl`. Serves map tiles for an air-gapped network. |

`tactical_server/kmz_hub/kmz_hub.py` is a Python FastAPI service for KMZ files. The Docker Compose
file does not start this service.

### 3.1 Server interfaces

| Path | Function |
|---|---|
| `GET /api/history/:droneId` | The telemetry history of one aircraft |
| `GET /api/trail/:droneId` | The flight path of one aircraft |
| Socket.io | Live telemetry to the web interface |

---

## 4. Rules for a change

1. Send all MSDK `setValue` and `performAction` calls from the main thread.
2. Use `postInvalidate()` in a custom view when a background thread calls it.
3. Use the topic names in `SERVER_API_DOCS.md`.
4. Do not put a password or a key in the source code or in a layout file.
5. Do not make a `COMPLETED` receipt until the aircraft shows the new state.
6. Change `README.md` and `SERVER_API_DOCS.md` when you change an interface.
7. Use ASD-STE100 Simplified Technical English in all documents.

---

## 5. Open items

Refer to `WORKSPACE_AUDIT.md` Section 5 for the full list. The most important items are:

1. Change the S3 keys and the stream password. Both are in the Git history.
2. Correct the configuration topic. The application and the server do not agree.
3. Do a bench test of the motor commands with the propellers removed.
4. The project has no test dependencies. The three files in `app/src/test` cannot operate.
