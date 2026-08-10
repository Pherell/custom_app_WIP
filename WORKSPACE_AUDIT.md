# Recreate2 — Defect Audit Record

> **Language standard:** ASD-STE100 Simplified Technical English.
> **Last change:** 2026-08-09.
> **Status:** Audit 2 is complete. The corrections are on the branch
> `fix/critical-flight-safety-and-c2-defects`. Section 2A gives four more defects that the unit
> test work found.

---

## 1. Purpose

This document records the defect audits of the Recreate2 workspace. It gives the cause of each
defect and the correction. Read this document before you change the flight control code or the C2
command code.

**NOTE: This document is a record of past work. It is not a description of the current behaviour.
For the current behaviour, refer to `README.md` and `SERVER_API_DOCS.md`.**

---

## 2. Audit 2 — 2026-08-09

The engineer read all of the application source code. The engineer found 37 defects. This section
gives the six flight-safety defects. Section 3 gives the other defects in a short form.

### 2.1 Link-loss failsafe operated on a good link

**Severity:** Critical.

**Cause:** The monitor tested `mqttService.isConnected` before the timeout test. The monitor
therefore made a Go-Home command when the broker was available but the operator sent no command for
15 seconds. The monitor did no test when the broker was not available.

**Effect:** The aircraft flew home during a normal flight.

**Correction:** The monitor now makes a Go-Home command only when the broker connection stops for
more than 15 seconds. A new flag stops the failsafe if the C2 was never connected.

**Open item:** The application answers a `PING` command with a `PONG` event, but no server sends
`PING`. A server that sends `PING` gives a true liveness test. Refer to Section 4.

### 2.2 The virtual stick loop sent zero velocity

**Severity:** Critical.

**Cause:** `createVirtualStickParam()` selects VELOCITY control for roll and pitch. The loop wrote
RC units to the stick objects and sent a parameter object with all values at zero. The parameter
object has control of the aircraft in advanced mode.

**Effect:** The loop commanded a stop at each cycle.

**Correction:** The new function `applyVelocitySetpoint()` writes velocity values in m/s and deg/s.
The function calculates the axis values for the BODY frame and the GROUND frame.

### 2.3 Long delays in the control loop

**Severity:** Critical.

**Cause:** The photo action used `Thread.sleep(3000)` and `Thread.sleep(1500)`. The MSDK needs a
virtual stick command at 5 Hz or more.

**Effect:** The aircraft could stop the virtual stick control during a mission.

**Correction:** The photo action is now a state machine with three steps. The loop continues to send
commands during the delay.

### 2.4 ARM and DISARM gave a false result

**Severity:** Critical.

**Cause:** The arm function made the lights flash and then sent a `COMPLETED` receipt. The function
did not start the motors. The disarm function changed a screen label only.

**Effect:** The C2 server received a report that the aircraft was armed when the motors were off.

**Correction:** The arm function now sends `KeyTurnOnTheMotor`. The disarm function now does a CSC
stick command. Both functions send `COMPLETED` only after `KeyAreMotorsOn` shows the new state. The
disarm function refuses to operate when the aircraft is in the air.

### 2.5 C2 commands operated on the wrong thread

**Severity:** Critical.

**Cause:** `handleMqttCommand` operated on the MQTT executor thread. The function read the map
overlay list and sent MSDK actions. The MSDK needs the main thread for actions.

**Effect:** The application could stop with a concurrent modification error. Some SDK actions did
not operate.

**Correction:** The application now sends the command dispatch to the main thread. The KMZ file
functions operate on a background thread.

### 2.6 Obstacle avoidance stayed off

**Severity:** Critical.

**Cause:** The follow engine set the avoidance mode to BYPASS. The stop function did not put the
initial mode back.

**Effect:** The aircraft had no obstacle avoidance after the operator used the follow function one
time.

**Correction:** The engine reads the initial mode and puts it back when the follow function stops.
`onDestroy` now stops the tracking threads.

---

## 2A. Defects that the unit tests found — 2026-08-09

The engineer added unit tests for the calculations that do not need an aircraft. The first
operation of the tests found two more defects. Refer to `PROJECT_CONTEXT_SUMMARY.md` Section 4.

### 2A.1 The AWS signature did not encode a non-ASCII file name

**Severity:** Medium.

**Cause:** `uriEncode` used `Char.isLetterOrDigit()`. That function accepts all Unicode letters.
The AWS signature specification permits only `A-Z`, `a-z`, `0-9`, `-`, `_`, `.` and `~` without
an encoding.

**Effect:** A file name with an accented letter or a non-Latin letter made a signature that the
server refused. The upload gave a 403 error that looks the same as a credentials error.

**Correction:** The function now permits ASCII characters only.

### 2A.2 Two action groups in a mission file could have the same identification number

**Severity:** Medium.

**Cause:** The camera action groups used the number `100 + index`. The hover action groups used
the number `index`. The interval photo group used `999`. On a route with 100 waypoints or more,
camera group 0 and hover group 100 both had the number 100.

**Effect:** The aircraft identifies an action by the group number. Two groups with the same
number is not defined behaviour. A survey grid on a moderate area has more than 100 waypoints.

**Correction:** The camera groups use `index * 2`. The hover groups use `index * 2 + 1`. The
interval group uses `waypoint count * 2`. These numbers cannot be the same at any route length.

### 2A.3 The FPV Acro setting made the mission engine use the wrong units

**Severity:** Critical.

**Cause:** `createVirtualStickParam()` set `rollPitchControlMode` to ANGLE when the FPV Acro
setting was on. ANGLE reads the roll and pitch fields as an attitude in degrees.
`applyVelocitySetpoint` writes a speed in metres per second into the same fields.

The setting had three users only: the mission engine and the targeting pod yaw. All three are
automatic flight. The manual sticks use the basic mode and did not read this setting.

**Effect:** With the setting on, a waypoint speed of 12 m/s went to the aircraft as 12 degrees of
tilt. ANGLE mode has no speed control, so the aircraft continued to accelerate until it went past
the waypoint. The setting made automatic flight worse and did nothing for manual flight.

**Correction:** The roll and pitch mode is now always VELOCITY. The gimbal FPV mode part of the
button is kept, because it is a true function: it locks the gimbal roll to the airframe. The
button now has the name `btnGimbalFpvMode` and the text "GIMBAL FPV MODE (ROLL LOCK)".

**NOTE: Do not put an ANGLE mode in `createVirtualStickParam()` again. There is a WARNING in the
function and a CAUTION in `applyVelocitySetpoint`.**

### 2A.4 Calculations that no test could reach

The survey grid and the orbit ring were inside `MainActivity`, mixed with interface reads and map
overlay changes. The `hFov * 9/16` error stayed in the survey grid after the engineer corrected it
in the AR marker, and it went to the branch a second time.

**Correction:** `mapping/SurveyGrid.kt` holds the grid calculation, the orbit ring and the velocity
frame calculation. `MainActivity` keeps the interface reads, the waypoint construction and the map
preview. 31 tests examine the new file.

---

## 2B. New function — camera target geolocation, 2026-08-09

**Type:** New function. This is not a defect correction.

**Cause of the work:** All accurate target tags needed the laser rangefinder. The application
reports a rangefinder on the M30, M300, M350, M200, Matrice and Enterprise aircraft only. On an
aircraft with no rangefinder, a tag gave the position of the AIRCRAFT. That aircraft can do the ISR
mission and point its camera at a target, but it could not record where the target was.

**Function:** `geo/CameraGeolocator.kt` calculates where the camera line of sight touches the
ground. It uses the aircraft position, the aircraft height above the take-off point, the aircraft
heading, the gimbal angles and the camera field of view. The operator can touch a position in the
image, or use the centre of the image.

The tag source order is now:

1. The laser rangefinder measurement.
2. The camera calculation (`CAM_TARGET`, source `CAMERA_GEO`).
3. The targeting pod lock.
4. The aircraft position.

**CAUTION: A camera fix is an estimate. It is not a measurement. It assumes flat ground at a known
height. A rangefinder measures the true distance and is always the better source.**

Each camera fix has an error radius. The error increases quickly when the camera comes near to
horizontal: at 100 m height with one degree of angle error, the error is 3.9 m at 45 degrees, 10.5 m
at 25 degrees and 58.7 m at 10 degrees. The application refuses a fix below a minimum angle
(15 degrees is the default value).

Two new settings are on the CFG page:

| Setting | Default | Function |
|---|---|---|
| Target elevation vs takeoff | 0.0 m | The target height in relation to the TAKE-OFF POINT. Use a negative value when the target is below the launch position. |
| Minimum depression | 15 degrees | The application refuses a camera fix below this angle. |

**WARNING: The target height is in relation to the take-off point. It is not a height above sea
level. `KeyAltitude` gives the height above the take-off point. A confusion of the two makes a
large error.**

**NOTE: This function uses the same `cameraYaw = droneYaw + gimbalYaw` rule as the AR home marker
and the targeting pod. No engineer has tested this rule against an aircraft. If the rule is not
correct, all three functions are wrong by the aircraft heading. Refer to
`PROJECT_CONTEXT_SUMMARY.md` Section 6, item 4.**

---

## 3. Audit 2 — other corrections

| Item | Defect | Correction |
|---|---|---|
| Telemetry | The payload builder was outside a timer. It operated one time. `put(NaN)` made an error. | The builder is a method. A timer calls it at 10 Hz. Non-finite values become null. |
| MQTT | The code made the password array zero after connect. Paho keeps the array by reference. | The code does not change the array. The automatic reconnect operates. |
| Security | Two S3 keys were in the source code. | The keys are removed. Refer to Section 5. |
| Missions | `waylines.wpml` had no `missionConfig`, `executeHeightMode`, `distance` or `duration`. | The generator writes all necessary elements. |
| Missions | The generator wrote a photo action for each second on all missions. | The photo action operates on survey missions only. |
| Missions | The KMZ path removed all waypoint actions. | The generator writes gimbal and camera actions. |
| Missions | The route manager had no connection to the waypoint data. | The application keeps the routes and the map in agreement. |
| ISR | Mode 2 sent all files after each landing. | The application keeps a list of the files that it sent. |
| Recorder | The stop function could stop the application. The muxer had a memory leak. | The drain function has a time limit. The muxer always releases. |
| Storage | The upload function copied large files on the calling thread. | The copy operates on a worker thread. |
| Storage | The signature used two clock reads and an unencoded path. | The signature uses one clock read and an encoded path. |
| Telemetry | Two listeners on the same key wrote the same field with different formulas. | One listener for each key. |
| Map | A mission cancel removed the no-fly areas. | The cancel keeps the no-fly areas. |
| Survey | `cameraFov` had no value. The front and side overlap used the same swath. | `cameraFov` is a setting. The front overlap uses the vertical field of view. |
| Controls | The joystick values stayed active when the joystick was not visible. | The application makes the values zero. |
| Interface | The HUD toggle button stopped the application. | The button uses the correct view name. |

---

## 4. Correction to Audit 1 (2026-07-28)

Audit 1 gave four defects and a status of RESOLVED. Two of the four defects were in code that does
not operate:

- **Defect 2 (`ARLandingOverlayView.kt`):** Only `res/layout/ui_v2_concept.xml` used this view, and
  the application does not use that layout. The correction was real but no code path reached it.
  The view and that layout are now deleted together.
- **Defect 3 (`ARVisionLandingManager.kt`):** No code made an instance of this class. The file is
  now deleted.

Precision landing does not need either file. It is an aircraft function that the application turns
on with `FlightAssistantKey.KeyPrecisionLandingEnabled`. Refer to `README.md` Section 6A.

Audit 1 also gave a status of RESOLVED to the MQTT topic defect. The correction was not complete.
The telemetry topic and the command topic were correct. Four other positions kept the deprecated
`avarell/` namespace.

The correction of 2026-08-09 changed the backend and the KMZ hub. The `avarell/` namespace is
deprecated.

| Topic | Application | Server | Web interface | KMZ hub |
|---|---|---|---|---|
| Telemetry | `dji-sdk/fleet/` | `dji-sdk/fleet/` | `dji-sdk/fleet/` | — |
| Command | `dji-sdk/fleet/` | `dji-sdk/fleet/` | `dji-sdk/fleet/` | `dji-sdk/fleet/` |
| Config | `dji-sdk/fleet/` | `dji-sdk/fleet/` | **`avarell/fleet/`** | — |
| Mission | `dji-sdk/fleet/` | — | `dji-sdk/fleet/` | `dji-sdk/fleet/` |
| Simulator | — | `dji-sdk/fleet/` | **`avarell/fleet/`** | — |

Before the correction, the configuration push did not reach the aircraft, and the KMZ hub could not
send a command or receive a mission event. Both faults are corrected.

The owner of the project holds the web interface (`frontend/`) outside this work. The two positions
in bold keep the old namespace. The effects are:

- The direct configuration publication has no effect. No component subscribes to that topic. The
  configuration still reaches the aircraft on the Socket.io path through the backend.
- The backend does not receive the simulated aircraft.

**NOTE: `MQTT_USERNAME=avarell` in the `.env` files is a broker user name. It is not a topic.**

---

## 5. Open items

These items need work outside the application source code:

1. **Change the S3 keys.** The keys `0RUUD1YOR1DLRQN2WF7H` and
   `hfGxYhmhBjNL41NUecqyGev5a77H29JfO0DAEkBs` are in the Git history. Removal from the source code
   does not make them safe.
2. **Change the stream password.** The password `streamer:Rahas!@2025` was in the source code and in
   `dialog_system.xml`. It is also in the Git history.
3. **Build the web interface again.** The output in `frontend/dist/` has the deprecated topic name.
   Use `npm run build`.
4. **Do a bench test.** The motor start, the WPML mission file and the virtual stick control values
   are not tested against an aircraft. Remove the propellers for this test.

---

## 6. Rules for engineers

Obey these rules when you change this workspace:

1. Use the topic names `dji-sdk/fleet/{clientId}/telemetry`, `dji-sdk/fleet/{clientId}/command`,
   `dji-sdk/fleet/broadcast/command` and `dji-sdk/fleet/config`.
2. Send all MSDK `setValue` and `performAction` calls from the main thread.
3. Use `postInvalidate()` in a custom view when a background thread calls it.
4. Do not put a password or a key in the source code or in a layout file.
5. Do not make a `COMPLETED` receipt until the aircraft shows the new state.
6. Change `SERVER_API_DOCS.md` and `README.md` when you change a topic, a payload or a command.
7. Use ASD-STE100 Simplified Technical English in all documents.
