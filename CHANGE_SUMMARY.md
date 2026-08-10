# Recreate2 — Change Record

> **Language standard:** ASD-STE100 Simplified Technical English.
> **Branch:** `fix/critical-flight-safety-and-c2-defects`
> **Base:** `main` after Pull Request 1.
> **Changes:** 27 commits. 12 new units. 236 unit tests.
> **Last change:** 2026-08-10.

---

## 1. How to read this document

This document gives all the changes on this branch. It has three types of item:

| Type | Meaning |
|---|---|
| **CORRECTION** | A defect that was in the application. The behaviour was not correct. |
| **NEW** | A function that the application did not have. |
| **RECORD** | A document or a test. The application behaviour does not change. |

**WARNING: No part of this branch has operated against an aircraft. All the DJI SDK behaviour in
this code comes from the type signatures in the SDK library. The 236 tests examine calculations
only. Do the procedure in `BENCH_TEST_CHECKLIST.md` before a flight.**

---

## 2. Flight safety

### 2.1 CORRECTION — The mission engine received an angle when it sent a speed

`createVirtualStickParam()` made the roll and pitch mode ANGLE when FPV Acro mode was on. The ANGLE
mode reads roll and pitch as an attitude in DEGREES. The function `applyVelocitySetpoint` writes
METRES PER SECOND into the same fields.

Only two functions read that setting: the mission engine and the targeting pod yaw assist. Both are
automatic. The manual joystick uses the classic mode and never saw it.

**Effect:** A speed of 12 m/s went to the aircraft as 12 degrees of tilt. The ANGLE mode has no
speed control, so the aircraft continued to increase speed and went past the waypoint.

**Correction:** The roll and pitch mode is always VELOCITY. The gimbal FPV mode that the button
also controlled is a true function and stays, with a new name that gives its true effect.

### 2.2 NEW — Return-home margin

The battery drove one decision only: `droneBattery in 1..24 -> "LOW_BATTERY_WARNING"`. A percentage
does not say if the aircraft can get home. 25 percent is sufficient at 100 m and too late at 3 km.

`flight/ReturnHomeBudget.kt` calculates the return as TIME (the leg, the climb and the descent) and
changes it to charge with a burn rate MEASURED on this flight. The window becomes empty at take-off.

| Distance | 30 percent charge |
|---|---|
| 100 m | AMPLE |
| 400 m | COMMITTED |
| 3 km | CRITICAL |

**WARNING: This function does not command the aircraft.** The link-loss failsafe has that
authority. Two automatic functions that can fly the aircraft is worse than the problem. CRITICAL is
a message for the operator.

A burn rate that the application has not measured gives UNKNOWN, not CRITICAL. The default rate is
pessimistic and would give an alarm at each take-off. An alarm that the operator learns to ignore is
worse than no alarm.

### 2.3 CORRECTION — Gimbal mechanical limits

Some gimbal commands had no limit. The mission point-of-interest lock calculated
`atan2(-droneAlt, poiDist)`, which goes to −90 degrees when the horizontal distance becomes small.
The gimbal motors then hold against the end stop.

`gimbal/GimbalLimits.kt` reads the true range from the aircraft with careful default values. All
eight command positions now use it.

---

## 3. Functions that did not operate

| Function | Before | Now |
|---|---|---|
| **WIDE, ZOOM, IR** | `showToast("Wide Lens Selected")` and nothing more. The application had no lens change code. | `CameraKey.KeyCameraVideoStreamSource`. The message shows after the aircraft accepts. |
| **Laser rangefinder** | Reflection called `getLatitude()` on `LaserMeasureInformation`, which does not have it. Each measurement made an error that only went to the log. | The typed SDK class. A measurement that is not `NORMAL` is refused. |
| **Lens detection** | The product name was compared with "M30", "ENTERPRISE" and "MATRICE". The M350_RTK and the M200_V2 series have none of these. | `KeyCameraVideoStreamSourceRange`. The aircraft gives its own list. |
| **Object detection** | Listened to `PerceptionManager` and sent an empty list of boxes. | `KeyMLTrackingBox`. |
| **Object follow** | The loop used the gimbal rate that it had just commanded, so the box moved to the centre without a true target. | The detection listener gives the true box. |
| **Precision landing** | `ARVisionLandingManager` had no instance. | `KeyPrecisionLandingEnabled`, with a telemetry field. |

---

## 4. Targeting

### 4.1 CORRECTION — The application stopped when you made a tag

The tag window tested `droneLat == 0.0`. Telemetry starts at NaN, and a NaN is not equal to 0.0, so
the test permitted the value. `JSONObject` refuses a NaN and makes an error that no `catch` block
received.

### 4.2 CORRECTION — The map and the tag list did not agree

`updateGpsTagsOnMap()` operated in two positions only. A tag that you removed kept its marker, and
that marker started a pod lock on a tag that did not exist.

### 4.3 CORRECTION — A tag could give the wrong position

`+ TAG TGP TARGET` kept the aircraft position with the name `TGP_TARGET` when there was no lock.

### 4.4 NEW — Camera target calculation

An aircraft with no rangefinder could record only its own position. `geo/CameraGeolocator.kt`
calculates where the camera line of sight meets the ground.

The tag sources are now: laser → camera → pod → aircraft. Each tag keeps its true source.

**WARNING: A camera fix is an ESTIMATE from an assumed flat ground. It is not a measurement.** At
100 m height with one degree of angle error the error is 3.9 m at 45 degrees and 58.7 m at 10
degrees. The application refuses a fix below a minimum angle, and each fix carries its error radius
to the tag and to the C2 server.

### 4.5 CORRECTION — A nadir view gave no position

Past 90 degrees of depression the line of sight goes across the nadir and `tan()` becomes negative,
so the calculation gave no point.

This is not a rare condition: with the camera straight down, EVERY position below the centre of the
image is past 90 degrees. A nadir view — the most usual ISR camera position — gave no footprint, and
a touch on the lower half of the image was refused.

---

## 5. ISR

### 5.1 NEW — Sensor footprint

The map gave the position of the aircraft and nothing about what the camera covered. The application
now draws the ground that the camera covers on the satellite image.

A footprint with a far edge that goes past the usable angle is amber with a broken line, not solid.
A solid shape would give a report of confirmed coverage of ground that the camera did not usefully
see.

An optional coverage trail keeps a record of where the sensor has been.

### 5.2 NEW — Tactical loiter

The application could orbit a point, but only as a PLANNED route. `flight/LoiterController.kt` makes
it a live function: designate a target, touch LTR, and the aircraft turns around it with the camera
on it.

It is a control law that holds a circle, not a movement between waypoints. The radius error gives a
radial speed that adds to the tangential speed as vectors. This keeps the path a circle and permits
a change of the radius during the flight.

It also corrects a defect in the planned orbit: that orbit calculates the gimbal angle one time,
when it makes the route. `towardPOI` keeps the NOSE on the target but nothing corrects the angle, so
a change of height leaves the camera at the wrong angle. The loiter calculates the angle at each
step.

**WARNING: The loiter flies the aircraft. These conditions are necessary:**

- The application must have the joystick channel. It cannot take control from the pilot or a
  mission.
- A movement of a joystick stops the loiter immediately.
- It refuses with no target, below 10 m radius, below 15 m altitude, or on the ground.
- It stops at a disconnection and at a landing, and lets the link-loss failsafe operate.
- The return-home margin continues to operate. A loiter is where an aircraft uses its reserve.

---

## 6. Field operation

### 6.1 CORRECTION — The map could not operate in an air-gapped network

`docker-compose.yml` starts a `tileserver-gl` container for an air-gapped network. The application
never used it: the ArcGIS address was in the source code.

**CAUTION: This was more than a change of address.** The old code made `{z}/{y}/{x}`, which is the
ArcGIS order. A `tileserver-gl` and OSM use `{z}/{x}/{y}`. A change of the address only would give a
map with the tiles in the wrong positions — a map that continues to look correct, which is worse
than no map. `map/TileTemplate.kt` carries the order.

### 6.2 CORRECTION — Telemetry was lost in a link outage

The application sent telemetry at QoS 0 with no queue. `telemetry/TelemetryBuffer.kt` keeps the
frames, and sends them oldest first at QoS 1 with their true times when the link returns.

### 6.3 NEW — A crash and a flight leave a record

The application had no uncaught-exception handler. The log was 5000 characters in memory that
deleted its oldest half without a message and stopped with the process.

`diag/CrashReporter.kt` and `diag/FlightLog.kt` give a crash file and a rotating flight log.

**NOTE: A crash file has the positions and the target coordinates of the flight. It is operational
data. The application does not send it without permission.**

---

## 7. Interface

### 7.1 CORRECTION — The HUD button stopped the application

`R.id.rightActionControls` did not exist: the container had a new name and the code was not changed.
`findViewById<View>` gives a platform type, so the error occurred at the first use.

### 7.2 CORRECTION — A touch on the image made a target

The overlay starts visible and the touch flag started as true, so the function was on from the
application start. A touch also sends a `camera_target` message to the C2 server, so a finger on the
image gave a report of a target that nobody selected.

The function is now off at the start, amber when it is on, and needs a long touch or a movement.

---

## 8. Server

### 8.1 CORRECTION — Target reports went into the position table

`lrf_target` and `camera_target` use the telemetry topic but they are not position frames. The
handler read the position fields without a test and, because `drone_id` permits a null value, made a
row of null values for each laser measurement.

---

## 9. Tests

The project had no test dependencies. There are now 236 tests.

| Test class | Tests |
|---|---|
| `CameraGeolocatorTest` | 35 |
| `SurveyGridTest` | 31 |
| `KmzGeneratorWpmlTest` | 22 |
| `SigV4Test` | 21 |
| `CameraProjectionTest` | 20 |
| `LoiterControllerTest` | 19 |
| `ReturnHomeBudgetTest` | 19 |
| `GimbalLimitsTest` | 15 |
| `GeoMathTest` | 11 |
| `TelemetryBufferTest` | 11 |
| `FlightLogTest` | 9 |
| `TileTemplateTest` | 9 |
| `TouchSelectionGateTest` | 9 |
| `GpsTaggingManagerTest` | 5 |

**NOTE: Each test that guards a defect was examined. The engineer put the defect into the code again
and made sure that the test failed. A test that is successful with and without the defect is not a
test.**

Two defects came from the tests at the first operation:

- `uriEncode` used `Char.isLetterOrDigit()`, which knows Unicode. A file name with an accent went
  into the S3 signature without a change, and the server gave a 403 error that looks the same as a
  credentials error.
- Action group numbers were the same for two groups past 100 waypoints. The aircraft uses the group
  number to identify an action.

```bash
JAVA_HOME="<Android Studio>/jbr" ./gradlew :app:testDebugUnitTest
```

Gradle 9.4.1 needs JVM 17 or higher.

---

## 10. New units

| Unit | Function |
|---|---|
| `geo/GeoMath.kt` | Metres and degrees, one correct conversion |
| `geo/CameraGeolocator.kt` | Camera line of sight to ground position, and the footprint |
| `gimbal/CameraProjection.kt` | Camera image to screen, with the CENTER_CROP correction |
| `gimbal/GimbalLimits.kt` | Mechanical limits |
| `mapping/SurveyGrid.kt` | Survey grid, orbit ring, velocity frames |
| `flight/ReturnHomeBudget.kt` | The return-home margin |
| `flight/LoiterController.kt` | The tactical loiter control law |
| `map/TileTemplate.kt` | Map tile address with the axis order |
| `telemetry/TelemetryBuffer.kt` | Telemetry store and forward |
| `tracking/TouchSelectionGate.kt` | The test for a true target selection |
| `diag/CrashReporter.kt` | The crash file |
| `diag/FlightLog.kt` | The rotating flight log |

---

## 11. Open items

**These items are not corrected on this branch:**

1. **Change the S3 keys and the stream password.** They are in the Git history and they are still
   correct keys. Removal from the source code does not make them safe. **The application has no keys
   in it, so the uploads give a 403 error until you put the new keys in SYS → CFG.**
2. **The gimbal yaw rule.** `cameraYaw = droneYaw + gimbalYaw` is not examined. The AR home marker,
   the targeting pod, the camera target calculation and the loiter all use it. Test 2 of the bench
   procedure examines it in three minutes. **Do that test first.**
3. **The web interface.** `tactical_server/frontend/` is not changed and continues to use the
   deprecated `avarell/` topic for the configuration and the simulator.
4. **Automatic ActiveTrack.** The keys are in a comment but not used. It needs a decision procedure
   against the mission loop and the failsafe first.
5. **`MainActivity.kt` is 9,205 lines.** A plan to divide it is in
   `.claude/plans/break-up-mainactivity.md`.
6. **The bench test.** Refer to `BENCH_TEST_CHECKLIST.md`. 15 tests. Most need no propellers.
