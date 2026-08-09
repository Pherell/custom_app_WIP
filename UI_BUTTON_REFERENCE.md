# Recreate2 — Button Reference

> **Language standard:** ASD-STE100 Simplified Technical English.
> **Last change:** 2026-08-09.
> **Applies to:** branch `fix/critical-flight-safety-and-c2-defects`.

This document gives each button in the application and its function. It also gives the status of
each button.

---

## 1. How to read the status column

| Status | Meaning |
|---|---|
| **OK** | The button operates fully on the tablet. No aircraft is necessary. |
| **TEST** | The button sends a command to the aircraft. The connection is correct in the code, but no engineer has done a test against an aircraft. |
| **NET** | The button needs a server (MQTT, S3, WebODM or a stream server). It fails if the server is not available. |

**WARNING: A status of TEST means that the code sends the correct command. It does not mean that
the aircraft does the correct thing. Do a bench test with the propellers removed. Refer to
`PROJECT_CONTEXT_SUMMARY.md` Section 6.**

**NOTE: No button is dead now. The first version of this document gave four dead buttons and three
buttons with a fault. Section 8 keeps that record.**

---

## 2. Main screen — mode selection

These buttons select what the screen shows and what a touch on the map does.

| Label | Id | Function | Status |
|---|---|---|---|
| CAM | `btnModeCam` | Shows the camera controls. | OK |
| FLY | `btnModeFly` | Shows the flight controls and the joysticks. | OK |
| MAP | `btnModeMap` | Shows the map. A touch moves the map. | OK |
| WAYPOINT | `btnModeWaypoint` | A touch on the map adds a waypoint. | OK |
| POI | `btnModePOI` | A touch on the map sets the point of interest for a waypoint. | OK |
| MAPPING | `btnModeShape` | A touch on the map adds a corner to the survey area. | OK |
| SYS | `btnSystem` | Opens the system settings window. | OK |

**NOTE: The joystick values become zero when you leave FLY mode.**

---

## 3. Main screen — flight controls

**WARNING: These buttons move the aircraft. Remove the propellers before the first test.**

| Label | Id | Function | Status |
|---|---|---|---|
| TAK | `btnTakeoff` | Starts an automatic take-off. | TEST |
| ENG | `btnManualStart` | Starts the motors (`KeyTurnOnTheMotor`). The receipt says COMPLETED only after `KeyAreMotorsOn` shows the new state. | TEST |
| LND | `btnLand` | Starts an automatic landing. | TEST |
| RTH | `btnRth` | Starts a return to the home point. | TEST |
| PAU | `btnPau` | Stops the aircraft. Sends a zero virtual stick command. | TEST |
| X | `btnCancelMission` | Stops the mission that operates now. | TEST |
| SET | `btnSetHome` | Makes the position of the aircraft the new home point. | TEST |
| — | `btnToggleJoysticks` | Shows or hides the touch joysticks. | OK |

---

## 4. Main screen — camera and targeting

| Label | Id | Function | Status |
|---|---|---|---|
| IMG | `btnCapture` | Makes one photograph with the aircraft camera. | TEST |
| REC | `btnRecord` | Starts or stops the video recording. | TEST |
| RES | `btnResetZoom` | Makes the zoom ratio 1.0 again. | TEST |
| GMB | `btnCenterGimbal` | Moves the gimbal to the centre position. | TEST |
| LRF | `btnLrfToggle` | Starts or stops the laser rangefinder. The distance and the target coordinate show on the screen. | TEST |
| LCK | `btnTgpLock` | Starts or stops the targeting pod lock. The gimbal holds a geographic coordinate. The aircraft turns if the coordinate is outside the gimbal pan range. | TEST |
| FOL | `btnFollowObject` | Makes the gimbal follow the object that the aircraft detects. | TEST |
| DEL | `btnDeleteSelection` | Stops the object lock. | OK |
| — | `btnToggleObjectTouch` | Permits a touch on the image to select an object. | OK |
| PIP | `btnTogglePip` | Shows or hides the second camera image. | OK |
| WIDE | `btnLensWide` | Selects the wide lens. The message shows only after the aircraft accepts the command. | TEST |
| ZOOM | `btnLensZoom` | Selects the zoom lens. | TEST |
| IR | `btnLensIr` | Selects the infrared lens. | TEST |

**NOTE: FOL needs aircraft object detection. If the aircraft does not have this function, the
button stays off and gives a message. Refer to the connection log.**

---

## 4A. Where a tag coordinate comes from

The TAG button and the tag window use the best source that is available:

| Order | Source | Tag name | Condition |
|---|---|---|---|
| 1 | Laser rangefinder | `LRF_TARGET` | A measurement less than 5 seconds old. The gimbal moves, so an older measurement is not the position that the camera sees now. |
| 2 | Camera calculation | `CAM_TARGET` | The camera looks down more than the minimum angle. Refer to `WORKSPACE_AUDIT.md` Section 2B. |
| 3 | Targeting pod lock | `TGP_TARGET` | A lock is active. |
| 4 | Aircraft position | `DRONE_POS` | No other source is available. |

Each tag keeps its true source. A tag from the camera and a tag from the laser are not the same
quality, and the tag list shows the difference.

**WARNING: A camera tag is an estimate. The application assumes flat ground at the height that you
give in the CFG page. A laser tag is a measurement. Do not use a camera tag when a laser tag is
available.**

**NOTE: A touch on the image starts the object tracking, moves the camera focus and makes a target
position. One touch does all three.**

---

## 5. Main screen — map and missions

| Label | Id | Function | Status |
|---|---|---|---|
| LOC | `btnCenterMap` | Moves the map to the position of the aircraft. | OK |
| TAG | `btnTagLocation` | Makes a coordinate tag. Refer to Section 4A for the source order. | TEST |
| ACK | `btnDismissAlerts` | Removes the alert message from the screen. | OK |
| ORBIT | `btnOrbit` | Makes a circular route around the last waypoint. | OK |
| GENERATE GRID | `btnGenerateGrid` | Makes the survey grid in the area that you drew. | OK |
| ROUTES | `btnRouteManager` | Opens the route window. | OK |
| EXEC | `btnExecuteMission` | Starts the mission with the waypoints on the map. | TEST |
| CLEAR MAP | `btnDeleteWaypoint` | Removes all waypoints and all routes. | OK |
| CLEAR MAP/KMZ | `btnClearKmz` | Removes the mission file lines from the map. The no-fly areas stay. | OK |
| SHOW SAVED MISSIONS | `btnShowSavedKmz` | Gives a list of the mission files on the tablet. | OK |
| FROM STORAGE | `btnImportKmzLocal` | Reads a mission file from the tablet. | OK |
| FROM URL | `btnImportKmz` | Reads a mission file from a server. | NET |
| START KMZ | `btnStartKmz` | Sends the mission file to the aircraft and starts it. | TEST |
| SYNC | `btnSyncWebOdm` | Sends the photographs to the WebODM server. | NET |

**NOTE: START KMZ gives the message "No waylines found in KMZ" if the aircraft refuses the file.
This is the test of the WPML format.**

---

## 6. Windows

### 6.1 Waypoint window (`dialog_waypoint.xml`)

| Label | Id | Function | Status |
|---|---|---|---|
| SAVE | `btnWpSave` | Keeps the altitude, the speed, the movement method and the action of the waypoint. | OK |
| DELETE | `btnWpDelete` | Removes the waypoint. | OK |

**NOTE: The `spWpAction` list sets the waypoint action: FLY, PHOTO, START_RECORD, STOP_RECORD,
LOCK_POI, UNLOCK_POI or SET_GIMBAL. The mission file carries these actions.**

### 6.2 Route window (`dialog_route_manager.xml`)

| Id | Function | Status |
|---|---|---|
| `btnCreateNewRoute` | Makes a new empty route. | OK |
| `btnChainExecuteRoutes` | Operates all the routes one after the other. | TEST |
| `btnMasterClearMap` | Removes all routes. | OK |
| `btnCloseRouteManager` | Closes the window. | OK |
| (one for each route) | Selects or removes that route. | OK |

### 6.3 Coordinate tag window (`dialog_gps_tagging.xml`)

| Id | Function | Status |
|---|---|---|
| `btnTagCurrentPos` | Makes a tag at the position of the aircraft. Gives a message when there is no satellite lock. | OK |
| `btnTagTgpTarget` | Makes a tag at the laser target or the targeting pod target. Gives a message when there is no target. It does not give the aircraft position. | TEST |
| `btnClearAllTags` | Removes all tags from the list and from the map. | OK |
| `btnCloseTagsDialog` | Closes the window. | OK |

### 6.4 WebODM window (`dialog_webodm_config.xml`)

| Id | Function | Status |
|---|---|---|
| `btnFetchProjects` | Gets the project list from the WebODM server. | NET |
| `btnSave` | Keeps the server address and the project. | OK |
| `btnCancel` | Closes the window. | OK |

---

## 7. System settings window (`dialog_system.xml`)

### 7.1 Tabs

`tabInfo`, `tabCfg`, `tabIsr`, `tabLog` change the page. Status: OK.
`btnCloseSystem` closes the window. Status: OK.

### 7.2 Flight settings

| Id | Function | Status |
|---|---|---|
| `btnRthAltitude` | Sets the return-to-home altitude on the aircraft. | TEST |
| `btnSignalLossAction` | Sets what the aircraft does when the control link stops. | TEST |
| `btnObstacleAction` | Sets the obstacle avoidance type. | TEST |
| `btnDistanceLimit` | Sets the maximum distance from the home point. | TEST |
| `btnPrecisionLanding` | Turns on the aircraft precision landing function. | TEST |
| `btnAutoConfirmLanding` | Permits the application to answer the DJI landing question without the operator. **The default is OFF.** | TEST |
| `btnGpsDeniedMode` | Indoor flight. The pre-flight test does not need a satellite lock. | TEST |
| `btnConfinedSpaceMode` | Small spaces. Makes the obstacle brake distance smaller. | TEST |
| `btnGimbalFpvMode` | Locks the gimbal roll to the airframe. | TEST |

**WARNING: `btnAutoConfirmLanding` removes a safety test. The aircraft lands without a test that
the ground is safe. The button is amber when it is on, not green.**

### 7.3 Control settings

`btnFlightSens`, `btnGimbalSens`, `btnAutoSens`, `btnInvertVertical`, `btnInvertHorizontal`,
`btnTouchAction`, `btnToggleRadar`, `btnRadarDistance` keep a value on the tablet. Status: OK.

### 7.4 Screen overlays

`btnToggleCompassOverlay`, `btnToggleReticleOverlay`, `btnToggleAiDetectionBoxes` show or hide an
overlay. Status: OK.

### 7.5 Aircraft functions

| Id | Function | Status |
|---|---|---|
| `btnBellyLamp` | Turns the bottom lamp on or off. | TEST |
| `btnStealthMode` | Turns off the lamps and the beacon. | TEST |
| `btnSimulator` | Turns the aircraft simulator on or off. | TEST |
| `btnTestAlerts` | Makes a test alert on the screen. | OK |

### 7.6 Video stream

| Id | Function | Status |
|---|---|---|
| `btnStreamModeRtmp` | Selects an RTMP push. | OK |
| `btnStreamModeRtsp` | Selects the RTSP server mode. | OK |
| `btnStreamModeWhip` | Selects the WHIP address. The application sends it as an RTMP push. | OK |
| `btnStartRtmp` | Starts the video stream. | NET |
| `btnStopRtmp` | Stops the video stream. | TEST |

**NOTE: DJI RTSP is a SERVER mode. The aircraft makes an RTSP address and a client gets the video
from it. The application does not push to an RTSP address.**

### 7.7 C2 server

| Id | Function | Status |
|---|---|---|
| `btnConnectServer` | Connects to the MQTT broker. | NET |

### 7.8 ISR and storage

| Id | Function | Status |
|---|---|---|
| `btnIsrMode1Toggle` | Mode 1. Gets the screen image and sends it to S3 with the telemetry in the EXIF data. | NET |
| `btnIsrMode2Toggle` | Mode 2. Sends the new aircraft SD card files after the motors stop. | NET |
| `btnIsrMode1CaptureNow` | Gets one image now. | NET |
| `btnIsrMode2SyncNow` | Starts the SD card transfer now. | TEST |
| `btnSaveS3Config` | Keeps the S3 address, the keys and the folder. | OK |
| `btnConnectCeph` | Connects to the S3 server and gets the folder list. | NET |
| `btnRefreshS3Folders` | Gets the folder list again. | NET |
| `btnCreateS3Folder` | Makes a new folder on the S3 server. | NET |
| `btnUseSelectedS3Folder` | Selects the folder for the uploads. | OK |
| `btnS3FolderModeToggle` | Changes between the automatic date folder and a folder that you give. | OK |
| `btnPickLocalFolder` | Selects the folder on the tablet. | OK |
| `btnWebOdmConfig` | Opens the WebODM window. | OK |
| `btnGpsTagsConfig` | Opens the coordinate tag window. | OK |
| `btnSaveGeolocation` | Keeps the target height and the minimum camera angle for the camera target calculation. | OK |

**CAUTION: The application has no S3 keys in it. The uploads fail with a 403 error until you put
the keys in the CFG page. This is correct behaviour: an empty key gives a clear error instead of a
signature that is not correct.**

**WARNING: `btnIsrMode2SyncNow` puts the camera in playback mode. The live video stops during the
transfer. The application refuses to start if the motors turn.**

---

## 8. Faults that are corrected

All the faults that the first version of this document gave are corrected. This section keeps the
record. Refer to `WORKSPACE_AUDIT.md` Sections 2A and 2B for the full cause and correction.

| Fault | Was | Now |
|---|---|---|
| WIDE, ZOOM and IR did nothing | The buttons showed a message only. The application had no lens change code. | `CameraKey.KeyCameraVideoStreamSource`. The message shows after the aircraft accepts the command. |
| The lens list was not correct | The application matched the product name against "M30", "ENTERPRISE" and "MATRICE". The M350_RTK and the M200_V2 series have none of these, so their zoom, thermal and rangefinder controls were hidden. | `CameraKey.KeyCameraVideoStreamSourceRange`. The aircraft gives its own lens list. |
| The rangefinder gave no measurement | The code used reflection to call `getLatitude()` and `getLongitude()` on `LaserMeasureInformation`. That class has neither. Each measurement made an error that only went to the log. | The typed SDK class. The coordinate is on `getLocation3D()`. The application refuses a measurement when `laserMeasureState` is not `NORMAL`. |
| The tag buttons stopped the application | The test was `droneLat == 0.0`. A NaN value is not equal to 0.0, and `JSONObject` refuses a NaN. | `GpsTaggingManager.isTaggable()` refuses a value that is not finite. Five tests keep this correct. |
| The map kept old markers | `updateGpsTagsOnMap()` operated in two positions only. | It operates after each change and when the application starts. |
| A tag could give the wrong position | `+ TAG TGP TARGET` used the aircraft position when there was no lock, but gave the tag the name `TGP_TARGET`. | Each tag keeps its true source. Refer to Section 4A. |

---

## 9. Summary

| Status | Count |
|---|---|
| OK — operates on the tablet | 66 |
| TEST — needs a bench test | 30 |
| NET — needs a server | 14 |
| DEAD or FAULT | 0 |

The engineer did these tests of the connections:

- All 195 `R.id` names in the code have a view in a layout file. No button can stop the
  application with a null value.
- No button in a layout that the application uses is without a function.
- 110 button functions are in `MainActivity.kt`. Five more are made in code for the route list,
  the tag list and the map shapes.

**NOTE: These tests examine the CONNECTION only. They do not examine the aircraft behaviour. Refer
to the WARNING in Section 1.**
