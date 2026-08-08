# Recreate2 — Defect Audit Record

> **Language standard:** ASD-STE100 Simplified Technical English.
> **Last change:** 2026-08-09.
> **Status:** Audit 2 is complete. The corrections are on the branch
> `fix/critical-flight-safety-and-c2-defects`.

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

- **Defect 2 (`ARLandingOverlayView.kt`):** Only `res/layout/ui_v2_concept.xml` uses this view. The
  application does not use that layout. The correction is real but no code path reaches it.
- **Defect 3 (`ARVisionLandingManager.kt`):** No code made an instance of this class. The file is
  now deleted.

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
