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
| Size | 14,226 lines in 24 Kotlin files |

**CAUTION: The application keeps the settings in plain SharedPreferences. It does not encrypt them.
An earlier version of this document gave `EncryptedSharedPreferences`. That statement was not
correct.**

### 2.2 Main source files

| File | Lines | Function |
|---|---|---|
| `MainActivity.kt` | 9,178 | The HUD, the map, the mission engine, the C2 dispatch, the camera controls and all dialogs |
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
| `gimbal/CameraProjection.kt` | 141 | Camera-frame to screen calculations |
| `gimbal/GimbalLimits.kt` | 120 | Gimbal mechanical limits |
| `mapping/SurveyGrid.kt` | 258 | Survey grid, orbit ring and velocity frame calculations |
| `geo/GeoMath.kt` | 91 | Metres to degrees conversions |
| `geo/CameraGeolocator.kt` | 196 | Camera line of sight to ground position, for an aircraft with no rangefinder |
| `flight/ReturnHomeBudget.kt` | 205 | Whether the charge that is left can still reach the home point |
| `map/TileTemplate.kt` | 55 | Map tile address, with the axis sequence |
| `telemetry/TelemetryBuffer.kt` | 105 | Keeps the telemetry when the C2 link stops |
| `diag/FlightLog.kt` | 130 | Flight log on the tablet, with rotation |
| `diag/CrashReporter.kt` | 120 | Writes a report when the application stops |
| `tracking/TouchSelectionGate.kt` | 60 | Which touch is a true target designation |

**NOTE: `MainActivity.kt` has 64 percent of the code. Divide this file before you add a large
function to it.**

**NOTE: `mapping/SurveyGrid.kt` holds all survey and orbit geometry. `MainActivity` reads the
interface fields and makes the waypoints. Do not put a calculation back into `MainActivity` —
a calculation there cannot have a test.**

### 2.3 Deleted files

| File | Reason |
|---|---|
| `ARVisionLandingManager.kt` | No code made an instance of it. An earlier version of this document gave an OpenCV and ArUco landing function. That function did not exist. |
| `ARLandingOverlayView.kt` | Only the unused layout `ui_v2_concept.xml` used it. Both are deleted. |
| `dialog_mapping_settings.xml`, `dialog_waypoint_action.xml` | No code made these layouts. The mapping fields are in `activity_main.xml` and the waypoint actions are in the `spWpAction` list in `dialog_waypoint.xml`. |
| `NativeWebRtcStreamManager.kt`, `WhipWebRtcManager.kt` | Never sent video data. |

Precision landing is now an aircraft function that the application turns on
(`FlightAssistantKey.KeyPrecisionLandingEnabled`). It needs no vision code on the tablet.

### 2.4 Camera geometry

`gimbal/CameraProjection.kt` holds all camera-frame to screen calculations. The AR home marker and
the object detection boxes both use it. It gives the correct vertical field of view, and it
corrects for the part of the image that `CENTER_CROP` removes.

`gimbal/GimbalLimits.kt` holds the mechanical limits. Every gimbal command goes through
`clampPitch` or `clampYaw`.

### 2.5 Layout files

`res/layout/activity_main.xml` is the layout that the application uses. One other layout is in the
directory. Do not use it:

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

## 4. Unit tests

The tests operate on the development computer. They do not need an aircraft.

```
./gradlew :app:testDebugUnitTest
```

**NOTE: Gradle 9.4.1 needs Java 17 or a later version. If the command stops with a Java version
message, set `JAVA_HOME` to the Java in Android Studio (`<Android Studio>/jbr`).**

The report is at `app/build/reports/tests/testDebugUnitTest/index.html`.

| Test file | Contents |
|---|---|
| `geo/GeoMathTest.kt` | The metres-per-degree values against the published table |
| `gimbal/CameraProjectionTest.kt` | Field of view, CENTER_CROP and the angle-to-pixel calculation |
| `gimbal/GimbalLimitsTest.kt` | The limits, the safety margin and the aircraft yaw handover |
| `KmzGeneratorWpmlTest.kt` | The WPML elements, the waypoint actions and the gimbal limits in a mission file |
| `aws/SigV4Test.kt` | The AWS signature, the character encoding and the single clock read |
| `mapping/SurveyGridTest.kt` | The grid line spacing, the photo interval, the flight path direction, the orbit ring and the velocity frame |
| `geo/CameraGeolocatorTest.kt` | The camera target position, the angle calculation, the ground height correction, the refusals and the error estimate |
| `GpsTaggingManagerTest.kt` | The coordinate test that stops the application from closing |

Some tests are regression tests for a defect. The comment in the test gives the defect. Do not
remove these tests.

**CAUTION: These tests examine calculations only. They do not examine the aircraft behaviour.
The virtual stick values, the motor commands and the mission file acceptance are not tested.
Refer to Section 6.**

---

## 5. Rules for a change

1. Send all MSDK `setValue` and `performAction` calls from the main thread.
2. Use `postInvalidate()` in a custom view when a background thread calls it.
3. Use the topic names in `SERVER_API_DOCS.md`.
4. Do not put a password or a key in the source code or in a layout file.
5. Do not make a `COMPLETED` receipt until the aircraft shows the new state.
6. Change `README.md` and `SERVER_API_DOCS.md` when you change an interface.
7. Use ASD-STE100 Simplified Technical English in all documents.
8. Operate `./gradlew :app:testDebugUnitTest` before you send a change. Add a test when you
   correct a calculation.

---

## 6. Open items

Refer to `WORKSPACE_AUDIT.md` Section 5 for the full list. The most important items are:

1. Change the S3 keys and the stream password. Both are in the Git history.
2. The web interface sends the configuration and the simulated telemetry on the deprecated
   `avarell/` topic. The server and the KMZ hub are corrected.
3. Do a bench test of the motor commands with the propellers removed.
4. **Examine the gimbal yaw convention.** `updateARHomePoint` and the targeting pod both
   calculate `cameraYaw = droneYaw + gimbalYaw`. This is correct only if the SDK gives the gimbal
   yaw relative to the airframe. Point the aircraft to the north, turn the gimbal 45 degrees to
   the right and read both values in the log. If `gimbalYaw` shows 45, the calculation is correct.
   No test can answer this question.
5. **Examine the mission speed.** The ANGLE mode defect (`WORKSPACE_AUDIT.md` Section 2A.3) is
   corrected. Make sure that a waypoint leg holds the commanded speed and does not accelerate.
