# Recreate2 — Tactical Drone Interface

Recreate2 is an Android ground station for DJI aircraft. It uses the DJI Mobile SDK (MSDK) v5.
The application flies waypoint missions, controls the camera and gimbal, and sends telemetry to a
private command and control (C2) server. The application does not use the DJI cloud.

---

## About this document

The writers of this document use ASD-STE100 Simplified Technical English. The rules below apply:

- Sentences in procedures have a maximum of 20 words.
- Sentences in descriptions have a maximum of 25 words.
- Each instruction gives one action.
- The writers use the active voice.
- The writers use one word for one meaning.

Keep to these rules when you change this document.

---

## Safety

**WARNING: A mission can start the motors and move the aircraft. Keep all persons away from the
rotors before you send a mission command. Injury can occur.**

**WARNING: The tests of this software on an aircraft are not complete. Do a bench test with the
propellers removed before you fly a new build.**

**CAUTION: The application can disable obstacle avoidance. The follow function sets the avoidance
mode to BYPASS. The application puts the initial mode back when the follow function stops.**

---

## 1. System description

The system has four parts:

| Part | Function |
|---|---|
| Aircraft | DJI aircraft with an MSDK v5 radio link |
| Tablet | Android device that runs Recreate2 |
| Edge server | MQTT broker, Node.js API, PostgreSQL database, web interface |
| Storage | Ceph S3 endpoint and an optional WebODM server |

The tablet connects to the remote controller with a USB cable. The tablet connects to the edge
server with TCP/IP. All server components run in Docker containers.

---

## 2. Equipment and software

You must have this equipment and software:

- A DJI developer account and an application key.
- A compatible DJI aircraft. The mission generator sets the aircraft type to Mavic 3 Enterprise.
- An Android device. The minimum version is Android 7.0 (API 24). The target version is API 35.
- An MQTT broker that the tablet can reach.

---

## 3. Installation

1. Get a copy of the repository.
2. Put your DJI application key in `AndroidManifest.xml`.
3. Build the project with Android Studio.
4. Connect the Android device to the DJI remote controller with the USB cable.
5. Accept the USB debug message on the tablet.
6. Wait for the message `SDK REGISTERED. READY FOR FLIGHT.`

**NOTE: The application does not fly until the SDK registration is complete.**

---

## 4. Start-up

The application starts at the Home screen. The Home screen does these steps in sequence:

1. The application asks for the Android permissions.
2. The application starts the DJI SDK.
3. The application registers the application key.
4. The application shows the `FLY` button.

You can also set the C2 server address and start the remote controller link from the Home screen.

---

## 5. Flight controls

### 5.1 Control modes

The application has three control modes. Use the buttons at the bottom right to select a mode.

| Mode | Screen layout |
|---|---|
| `CAM` | Full video. Camera controls only. |
| `FLY` | Full video with a small map. All flight controls. |
| `MAP` | Full map with a small video image. Mission controls. |

### 5.2 Left control column

| Button | Function |
|---|---|
| `TAK` | Start the automatic take-off |
| `ENG` | Start the motors and show the arm status |
| `LND` | Start the automatic landing |
| `RTH` | Start the return to home |
| `PAU` | Stop the return to home |
| `SET` | Set the home point to the aircraft position |

**CAUTION: The `ENG` button is adjacent to `TAK` and `LND`. The buttons are small. Look at the
label before you touch a button.**

### 5.3 Manual flight

Touch `STK` to show the two joysticks. The left joystick controls the height and the heading. The
right joystick controls the movement. In `CAM` mode the right joystick moves the gimbal.

If you move a joystick during a mission, the application stops the mission.

---

## 6. Missions

### 6.1 How to make a mission

1. Select `MAP` mode.
2. Touch the aircraft symbol one time. The application shows `UNIT SELECTED: COMMAND MODE ON`.
3. Touch the map to add a waypoint.
4. Touch a waypoint symbol to open the waypoint menu.
5. Set the height, the speed, the movement type and the action.
6. Touch `SAVE`.

### 6.2 Waypoint movement types

| Type | Path |
|---|---|
| `LINEAR` | Straight line to the point |
| `SPLINE` | Smooth curve through the points |
| `ORBIT` | Circle around the point |

### 6.3 Waypoint actions

| Action | Result at the waypoint |
|---|---|
| `FLY` | No action |
| `PHOTO` | Aim the gimbal at the target and take one photo |
| `START_RECORD` | Start the video record |
| `STOP_RECORD` | Stop the video record |
| `LOCK_POI` | Aim the gimbal at a ground target |
| `UNLOCK_POI` | Put the gimbal to the forward position |
| `SET_GIMBAL` | Move the gimbal to a set angle |

The application asks you to touch the map when an action needs a target.

### 6.4 How to fly a mission

1. Touch `EXEC MISSION`.
2. The application does the pre-flight checks.
3. The application makes a KMZ mission file.
4. The application sends the file to the aircraft.
5. The aircraft flies the mission.

The pre-flight checks stop the mission if one of these conditions is true:

- The battery charge is less than 20 percent.
- The satellite count is less than 10, and the GPS-denied mode is off.
- A waypoint height is less than 5 m or more than 120 m.
- A waypoint speed is less than 0.5 m/s or more than 12 m/s.
- A waypoint or a flight path is in a no-fly area.

**NOTE: The aircraft flies the KMZ mission with its own navigation computer. The mission continues
if the tablet link stops.**

If the application cannot make the KMZ file, it flies the mission from the tablet with the virtual
stick function. This path stops if the tablet link stops.

### 6.5 Survey missions

1. Select `SHAPE` mode.
2. Touch the map to make the corners of an area.
3. Touch the first corner to close the area.
4. Set the height, the speed, the overlap and the gimbal angle.
5. Touch `GEN GRID`.
6. Touch `EXEC MISSION`.

The application calculates the line distance from the camera field of view and the height.

---

## 7. ISR media

The application has two ISR modes. Set the mode in the system menu or with a C2 command.

### 7.1 Mode 1 — capture

Mode 1 operates when you take a photo or a video. The application does these actions:

- The application sends a shutter command to the camera.
- The application gets the full-size file from the camera storage.
- The application sends the file to the S3 endpoint.
- The application keeps a copy in the local storage.

Mode 1 also records the video image on the tablet. The tablet record function makes an MP4 file and
an SRT subtitle file. The SRT file has one telemetry line for each second.

**CAUTION: The tablet record function and the video stream cannot operate together. Each function
stops the other function to protect the processor.**

### 7.2 Mode 2 — post-flight sync

Mode 2 operates when the motors stop. The application gets all new photo and video files from the
aircraft storage. The application sends the files to the S3 endpoint and to the local storage.

The application keeps a list of the files that it sent. It does not send the same file two times.

---

## 8. Video stream

The application sends the video with the DJI hardware encoder. Two protocols are available:

| Protocol | Function |
|---|---|
| RTMP | The aircraft sends the video to a server |
| RTSP | The application makes a server. Clients connect to it. |

If you give an HTTP or a WHIP address, the application changes the address to an RTMP address. The
application keeps the host name from the address that you give.

**NOTE: The application has no WebRTC function. A relay server such as go2rtc must change the RTMP
video to WebRTC for the web browsers.**

---

## 9. C2 protocol

The application uses these MQTT topics. `{id}` is `drone_` and the aircraft serial number.

| Direction | Topic | Data |
|---|---|---|
| Subscribe | `dji-sdk/fleet/{id}/command` | Commands for one aircraft |
| Subscribe | `dji-sdk/fleet/broadcast/command` | Commands for all aircraft |
| Subscribe | `dji-sdk/fleet/config` | Configuration data |
| Publish | `dji-sdk/fleet/{id}/telemetry` | Aircraft state, about 10 Hz, QoS 0 |
| Publish | `dji-sdk/fleet/{id}/mission` | Events and command receipts, QoS 1 |

Each command can have a `transaction_id`. The application answers with a receipt. The receipt
status is `ACCEPTED`, `EXECUTING`, `COMPLETED` or `FAILED`.

### 9.1 Command examples

Add a waypoint:

```json
{ "command": "ADD_WAYPOINT", "lat": 34.0531, "lon": -118.2450, "alt": 50.0 }
```

Start the return to home:

```json
{ "command": "RTH" }
```

Test the link:

```json
{ "command": "PING", "timestamp": 1715432109000 }
```

The application answers a `PING` command with a `PONG` event.

For all commands and all data fields, refer to `SERVER_API_DOCS.md`.

---

## 10. Configuration

The application keeps the settings in Android SharedPreferences. Two stores are in use:

| Store | Contents |
|---|---|
| `TacticalHUDConfig` | MQTT address, S3 address and keys, stream host, flight settings |
| `WebODMConfig` | WebODM address, user name, password, project number |

**CAUTION: The application does not encrypt the settings. Do not put an operational password on a
tablet that other persons can get.**

Useful keys:

| Key | Function |
|---|---|
| `streamHost` | Host name for the stream addresses |
| `streamName` | Stream name for the stream addresses |
| `rtmpPathPrefix` | Path before the stream name. Empty by default. Set to `live` for an nginx-rtmp server. |
| `cameraFovDeg` | Camera field of view in degrees. The survey calculation uses this value. |

---

## 11. Known limits

Read these limits before you use the application for an operational task:

1. **The tests on an aircraft are not complete.** The motor start, the WPML mission file and the
   virtual stick control values are not tested against hardware.
2. **The object follow function is off.** The application has no object detector. Use the targeting
   pod geo-lock for a true target lock.
3. **The GCS link failsafe uses the broker connection.** The application answers a `PING` command,
   but the server does not send `PING`. Refer to `SERVER_API_DOCS.md`.
4. **The application does not encrypt the stored settings.**
5. **The web interface uses the deprecated `avarell/` topic for two publications.** The
   configuration still reaches the aircraft through the backend. The backend does not receive the
   simulated aircraft. Refer to `SERVER_API_DOCS.md` Section 0.1.

---

## 12. Technical data

| Item | Value |
|---|---|
| Language | Kotlin |
| SDK | DJI MSDK v5.18.0 |
| Map | OSMDroid with ArcGIS satellite images |
| Network | Eclipse Paho MQTT v3, OkHttp 4 |
| Minimum Android | 7.0 (API 24) |
| Target Android | 15 (API 35) |
