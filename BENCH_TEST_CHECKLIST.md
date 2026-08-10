# Recreate2 — Bench Test Procedure

> **Language standard:** ASD-STE100 Simplified Technical English.
> **Applies to:** branch `fix/critical-flight-safety-and-c2-defects`, commit `fa9d2f6`.
> **Last change:** 2026-08-09.

---

## 1. Purpose

No part of this branch has operated against an aircraft. All the behaviour of the DJI SDK in this
code comes from the type signatures in the SDK library. The 148 unit tests examine calculations
only. They do not examine the aircraft.

This procedure finds the faults that only an aircraft can show. Do the tests in the given order:
the first tests are the fastest and they can make the later tests unnecessary.

**WARNING: REMOVE THE PROPELLERS before Test 8. Tests 1 to 7 do not turn the motors, but an
incorrect command can start them. Remove the propellers before you start.**

---

## 2. Necessary equipment

| Item | Note |
|---|---|
| Aircraft | With the propellers removed |
| Remote controller | Linked to the aircraft |
| Tablet | With the debug application from commit `fa9d2f6` |
| Open area | For the satellite lock. A window position is not sufficient. |
| Two known coordinates | For Test 6. Use a survey mark or a satellite image position. |
| Measuring tape | For Test 6, 30 m or more |

---

## 3. How to read the log

Most messages go to the application log. You do not need a computer:

**SYS → LOG tab.**

For the messages from the other components, connect a computer and use this command:

```bash
adb logcat -s PayloadDetectMgr:V GimbalLimits:V CameraProjection:V ISRModeManager:V PostFlightS3Sync:V S3UploadManager:V
```

---

## 4. Before you start

1. **Change the S3 keys and the stream password.** The old keys are in the Git history and they
   are still valid. Refer to `WORKSPACE_AUDIT.md` Section 5.
2. **Put the new keys in the application.** SYS → CFG → S3 Access Key and S3 Secret Key.

   **CAUTION: The application has no keys in it. The uploads give a 403 error until you put the
   keys in. This is correct behaviour. An empty key gives a clear error and not an incorrect
   signature.**
3. Install the debug application.
4. Start the aircraft and the remote controller. Wait for the satellite lock.

---

## 5. The tests

### Test 1 — Connection and capabilities

**Time:** 5 minutes. **Propellers:** not necessary.

Connect the aircraft. Open SYS → LOG. Find these lines:

| Message | Meaning |
|---|---|
| `Payload: <name> lenses=[...] sources=[...] lrf=<true/false>` | The aircraft type and its lenses |
| `Gimbal limits: pitch -85.0..25.0, yaw -25.0..25.0 (from aircraft)` | The mechanical limits |
| `Camera projection: video 1920x1080 (from aircraft)` | The video image size |
| `Precision landing set to true.` | Precision landing operates |
| `Aircraft object detection enabled.` | Object detection operates |

**PASS:** Each line shows `(from aircraft)` and not `(default)`.

**RECORD:** Write the full `Payload:` line. It gives which of the tests 4, 5 and 11 you can do.

**NOTE: A message of "not available on this aircraft" or "not supported" is not a fault. It shows
that your aircraft does not have that function. The application then keeps that function off and
gives a message. Make sure that this occurs. A function that appears to operate but does nothing
is the fault that this branch corrects.**

---

### Test 2 — The gimbal yaw rule ⚠ DO THIS FIRST

**Time:** 3 minutes. **Propellers:** not necessary.

**WARNING: Three functions use the same rule: `cameraYaw = droneYaw + gimbalYaw`. These are the AR
home marker, the targeting pod lock and the camera target calculation. No engineer has examined
this rule. If the rule is not correct, all three functions give a position that is wrong by the
aircraft heading. Do this test before you use any of them.**

1. Put the aircraft on the ground with the nose to the NORTH.
2. Turn the gimbal 45 degrees to the RIGHT.
3. Read `droneYaw` and `gimbalYaw` in the telemetry.

| `gimbalYaw` shows | Meaning | Action |
|---|---|---|
| About 45 | The value is relative to the airframe. The rule is correct. | Continue. |
| About 0, or 45 plus the heading | The value is absolute. **The rule is not correct.** | **STOP.** Do not do Test 6 or Test 7. Report this result. The three functions need a correction. |

4. Turn the aircraft 90 degrees to the right (nose to the EAST). Keep the gimbal at 45 degrees
   right of the nose. Read the two values again. If `gimbalYaw` stays at about 45, the rule is
   correct.

**PASS:** `gimbalYaw` does not change when the aircraft turns and the gimbal stays with the nose.

---

### Test 3 — The gimbal limits

**Time:** 5 minutes. **Propellers:** not necessary.

The gimbal motors hold against the end stop when the application commands an angle outside the
mechanical range. This is what makes the motors too hot.

1. Use the touch control to move the gimbal fully down. Listen to the motors.
2. Make a waypoint. Give it a point of interest at the position of the aircraft. Start the
   mission preview. The old code calculated −90 degrees for this condition.
3. Start the targeting pod lock on a tag that is very near to the aircraft.

**PASS:** The gimbal stops before the end stop in all three tests. There is no motor noise that
continues after the movement stops.

**FAIL:** The gimbal touches the end stop, or the motors make a noise that continues.

---

### Test 4 — Lens selection

**Time:** 3 minutes. **Propellers:** not necessary.
**Do this test only if the `Payload:` line in Test 1 gives more than one lens.**

1. Touch WIDE. Touch ZOOM. Touch IR.
2. Look at the video image after each touch.
3. Look for `Video stream source is now ZOOM_CAMERA` in the log.

**PASS:** The image changes, AND the message shows. The message must show after the image changes.

**FAIL:** The message shows but the image does not change. This is the old fault: the button gave a
message that was not true.

**NOTE: A button for a lens that the payload does not have is not on the screen. If the ZOOM
button is not there but the payload has a zoom lens, the `sources=` list in Test 1 is not correct.**

---

### Test 5 — The laser rangefinder

**Time:** 5 minutes. **Propellers:** not necessary.
**Do this test only if the `Payload:` line gives `lrf=true`.**

1. Touch LRF to start the laser.
2. Point the camera at a building or the ground, 20 m to 200 m away.
3. Look at the LRF area of the screen.

**PASS:** A distance and a coordinate show. The distance agrees with the true distance.

4. Point the camera at the sky.

**PASS:** The values do NOT change. Look for `Laser reading refused: NO_SIGNAL` in the logcat.

**FAIL:** The screen area stays empty in step 3. This was the old fault: the application used
reflection to call methods that do not exist, and no measurement ever came through.

5. With a good measurement on the screen, touch TAG. Open the tag window.

**PASS:** The new tag has the name `LRF_TARGET`, not `DRONE_POS`.

---

### Test 6 — The camera target calculation

**Time:** 20 minutes. **Propellers:** necessary (the aircraft must be in the air).
**Do Test 2 first. Do not do this test if Test 2 failed.**

**WARNING: This test needs a flight. Do it after all the other tests, and only in a safe area.**

This function calculates the target position from the camera angle. It assumes flat ground.

1. Set the target height: SYS → CFG → "Target elev. vs takeoff" = 0.
2. Hold a position above a known coordinate at 100 m height.
3. Point the reticle at a second known coordinate. Touch TAG.
4. Compare the tag with the true coordinate.
5. Do steps 3 and 4 again with the camera at 45, 30 and 20 degrees below horizontal.

**PASS:** The position error is near to the error radius on the screen:

| Camera angle | Expected error radius at 100 m |
|---|---|
| 45 degrees | About 4 m |
| 25 degrees | About 10 m |
| 10 degrees | About 59 m |

**FAIL:** The error is much more than the radius on the screen. **An error radius that is not
correct is worse than no error radius.** Report the measured values.

6. Point the camera near to the horizon.

**PASS:** The application refuses and gives a reason. Look for `Camera geolocation refused:` in the
log. The application must not put a marker on the map.

7. Set "Target elev. vs takeoff" to the true height difference. Do step 3 again.

**PASS:** The second tag is nearer to the true coordinate.

---

### Test 7 — The coordinate tags

**Time:** 5 minutes. **Propellers:** not necessary.

1. **Before the satellite lock**, open SYS → the tag window. Touch `+ TAG DRONE POS`.

   **PASS:** A message says that the application is waiting for the satellite lock.
   **FAIL: The application stops.** This was the old fault.

2. After the satellite lock, make three tags.
3. Remove one tag.

   **PASS:** The marker goes off the map immediately.

4. Touch CLEAR ALL.

   **PASS:** All the markers go off the map.

5. Stop the application and start it again.

   **PASS:** The tags that you kept show on the map.

6. With no laser measurement and no pod lock, touch `+ TAG TGP TARGET`.

   **PASS:** A message says that there is no target. The application does NOT make a tag with the
   aircraft position.

---

### Test 8 — The motors ⚠ PROPELLERS OFF

**Time:** 5 minutes.

**WARNING: REMOVE THE PROPELLERS. This test turns the motors.**

1. Touch ENG. Look at the motors and the receipt.

   **PASS:** The motors turn AND the receipt says `COMPLETED`. The receipt must come after the
   motors turn, not before.

2. Do the CSC stick command to stop the motors.

   **PASS:** The motors stop and the receipt says `COMPLETED`.

**FAIL:** The receipt says `COMPLETED` but the motors do not turn. This is the fault that Audit 2
corrected. Refer to `WORKSPACE_AUDIT.md` Section 2.4.

---

### Test 9 — The mission file

**Time:** 10 minutes. **Propellers:** not necessary.

1. Draw a survey area on the map. Touch GENERATE GRID.
2. Compare the preview lines with the earlier version if you have it.

   **PASS:** The lines make a pattern that turns at the end of each line. The lines do not go back
   across the area between each line.

3. Touch START KMZ.

   **PASS:** The aircraft accepts the file. The message "No waylines found in KMZ. Invalid WPML
   format." does NOT show.

4. Make a route with more than 100 waypoints. Give an action to waypoint 1 and a hover time to
   waypoint 101. Send it.

   **PASS:** The aircraft accepts the file. This examines the action group number correction.

---

### Test 10 — ISR Mode 2 and the media transfer

**Time:** 10 minutes. **Propellers:** not necessary.

1. Make sure that the motors are off.
2. SYS → ISR → touch `🔄 SYNC NOW`.

   **PASS:** The log shows the file list from the SD card. The files go to S3.
   **PASS:** The live video stops during the transfer and starts again after it.

3. Start the motors (propellers off). Touch `SYNC NOW`.

   **PASS:** The application refuses. The camera must not go to playback mode when the motors turn.

**NOTE: Step 2 examines an assumption. The application makes the camera enter playback mode with
`mediaManager.enable()`. The engineer did not examine if this stops the live video. All of ISR
Mode 1 and Mode 2 is built on this.**

---

### Test 11 — Object detection and follow

**Time:** 10 minutes. **Propellers:** not necessary.
**Do this test only if Test 1 gives `Aircraft object detection enabled.`**

1. Put a person in the camera image.

   **PASS:** A box shows on the person with the name `PERSON`. The box stays on the person when
   the person moves.
   **PASS:** The box is ON the person and not to one side. A box to one side shows that the
   CENTER_CROP correction is not correct.

2. Touch the person in the image.

   **PASS:** The gimbal follows the person.
   **PASS:** A camera target position shows (Test 6 gives the details).

3. Move the person out of the image for 2 seconds.

   **PASS:** The gimbal stops. It does not continue to move.

4. Stop the follow function.

   **PASS:** The obstacle avoidance mode returns to its earlier value.

---

### Test 12 — The video stream

**Time:** 5 minutes. **Propellers:** not necessary.

1. SYS → select RTMP. Touch START STREAM.

   **PASS:** The go2rtc server receives the video.

2. Select RTSP. Touch START STREAM.

   **PASS:** The aircraft makes an RTSP server. A client can get the video from it.

**NOTE: DJI RTSP is a SERVER mode. The aircraft makes an address. The application does not push to
an RTSP address.**

---

### Test 13 — The mission speed ⚠ FLIGHT

**Time:** 15 minutes. **Propellers:** necessary.

**WARNING: This is a flight test. Do it last, and only after all the other tests pass.**

This examines the correction of the ANGLE and VELOCITY fault.

1. Make a route with two waypoints, 200 m apart, at a speed of 5 m/s.
2. Start the mission. Look at the ground speed.

   **PASS:** The aircraft holds about 5 m/s.

**FAIL:** The aircraft continues to increase speed and goes past the waypoint. Stop the mission
immediately with the joystick. Report this result: the correction is not sufficient.

3. Do the test again at 10 m/s.

---

### Test 14 — The return-home margin

**Time:** 10 minutes on the ground, then in flight. **Propellers:** not for the first part.

1. On the ground, set the home point. Look at the battery indicator.

   **PASS:** The state is `UNKNOWN`. The application must not give an alarm when the aircraft is
   on the ground or before it measures the discharge rate.

2. In flight, fly away from the home point and look at the battery colour and the log.

   **PASS:** The colour changes to amber at `COMMITTED` and to red at `CRITICAL`. The message
   shows one time at each change and not continuously.

3. Compare the margin at the turn with the charge that is left after the landing.

   **PASS:** The margin was not more than the true value. A margin that is too large is dangerous;
   a margin that is too small is only an inconvenience.

**WARNING: The application does NOT go home on its own for this. Make sure that it does not. If
the aircraft goes home without a command, two functions have control and this is a defect.**

---

### Test 15 — The map with no network

**Time:** 15 minutes. **Propellers:** not necessary.

1. With a network, move the map to your operation area. SYS → CFG → CACHE VISIBLE MAP AREA.

   **PASS:** The log gives the number of tiles.

2. Put the tablet in flight mode. Start the application again. Move the map to the same area.

   **PASS:** The map shows. Before this correction it was empty.

3. SYS → CFG → LOCAL SERVER → SAVE. Start the application again.

   **PASS:** The tiles come from your `tileserver-gl` container.
   **PASS: Make sure that the map is CORRECT and not mixed.** A mixed map has correct tiles in
   incorrect positions. Look at a coast or a road: it must be continuous.

**CAUTION: ArcGIS uses the sequence `{z}/{y}/{x}`. A tileserver-gl uses `{z}/{x}/{y}`. An
incorrect sequence gives a map that appears correct but is not.**

---

### Test 16 — The telemetry buffer

**Time:** 10 minutes. **Propellers:** not necessary.

1. Connect to the C2 server. Make sure that the flight path shows.
2. Stop the MQTT broker for 60 seconds. Start it again.

   **PASS:** The flight path has no space in it after the connection returns. The application
   sends the frames that it kept.
   **PASS:** The frames have their original time. The flight path must not show all the frames at
   the position where the connection returned.

---

### Test 17 — The crash report and the flight log

**Time:** 10 minutes. **Propellers:** not necessary.

1. Operate the application for 30 minutes. Open SYS → LOG.

   **PASS:** The log file on the tablet has the full session. The screen shows the last part only.

2. Cause a crash in a debug version. Start the application again.

   **PASS:** A window gives the report and the buttons SHARE, KEEP and DELETE.
   **PASS:** The report gives the aircraft type, the application version and the last log lines.

**NOTE: A crash report has positions and target coordinates. Send it only where this is
permitted.**

---

### Test 14 — The sensor footprint

**Time:** 5 minutes. **Propellers:** not necessary.

1. Connect the aircraft. Go to MAP mode. Make sure SYS - CFG - SENSOR FOOTPRINT is ON.
2. Move the gimbal down and up.

   **PASS:** A blue shape on the satellite image shows the ground that the camera covers. The
   shape becomes smaller when the camera looks more down.

3. Move the camera up to the horizon.

   **PASS:** The shape becomes amber with a broken line. This shows that the far edge goes past
   the horizon and is not confirmed coverage.

4. Move the camera above the horizon.

   **PASS:** The shape goes off the map. It does not stay in the last position.

5. Move the camera to look straight down.

   **PASS:** The shape shows below the aircraft. **FAIL: no shape.** This condition gave no shape
   before commit 9de96be.

---

### Test 15 — Tactical loiter ⚠ FLIGHT

**Time:** 20 minutes. **Propellers:** necessary.

**WARNING: This test flies the aircraft in a circle without the operator. Do it last, in an open
area, and keep your hands on the sticks. The first flight is a test of the control law.**

**Do Test 2 first. Do not do this test if Test 2 failed.**

First, on the ground (propellers removed), make sure that the application refuses:

1. Touch LTR with no target.

   **PASS:** A message says that there is no target.

2. Make a target (laser, or touch the image). Touch LTR with the joysticks off.

   **PASS:** A message says that the loiter needs stick authority.

3. Touch LTR with the aircraft on the ground.

   **PASS:** A message says that the aircraft must be in the air.

**FAIL for any of the three: the aircraft can start a loiter when it must not. Stop the test.**

Then, in the air:

4. Set the radius to 100 m and the altitude to 80 m in SYS - CFG.
5. Designate a target. Turn the joysticks on. Touch LTR.

   **PASS:** The aircraft goes to the circle and turns around the target. The LTR button is amber.

6. Look at the flight path on the map.

   **PASS:** The path is a **circle**. **FAIL: the path is a spiral** — the radius correction is
   not correct. Stop the loiter with the joystick and report this result.

7. Look at the video during one full circle.

   **PASS:** The target stays near the centre of the image.

8. Change the altitude during the circle (use the throttle after you stop the loiter, then start
   it again at the new height).

   **PASS:** The camera angle changes and the target stays in the image. This is the correction of
   the frozen gimbal angle. In the old planned orbit the target went out of the image.

9. Move a joystick.

   **PASS:** The loiter stops immediately and the aircraft answers the sticks.

**WARNING: Step 9 is the most important test on this page. If the loiter does not stop when you
move a stick, do not use this function.**

---

## 6. Results

| Test | Result | Note |
|---|---|---|
| 1 Connection and capabilities | ☐ Pass ☐ Fail | Write the `Payload:` line: |
| 2 Gimbal yaw rule | ☐ Pass ☐ Fail | `gimbalYaw` = |
| 3 Gimbal limits | ☐ Pass ☐ Fail | |
| 4 Lens selection | ☐ Pass ☐ Fail ☐ N/A | |
| 5 Laser rangefinder | ☐ Pass ☐ Fail ☐ N/A | |
| 6 Camera target | ☐ Pass ☐ Fail | Error at 45°: |
| 7 Coordinate tags | ☐ Pass ☐ Fail | |
| 8 Motors | ☐ Pass ☐ Fail | |
| 9 Mission file | ☐ Pass ☐ Fail | |
| 10 ISR Mode 2 | ☐ Pass ☐ Fail | |
| 11 Object detection | ☐ Pass ☐ Fail ☐ N/A | |
| 12 Video stream | ☐ Pass ☐ Fail | |
| 13 Mission speed | ☐ Pass ☐ Fail | |
| 14 Sensor footprint | ☐ Pass ☐ Fail | |
| 15 Tactical loiter | ☐ Pass ☐ Fail | Path circular? |
| 14 Return-home margin | ☐ Pass ☐ Fail | Margin at the turn: |
| 15 Map with no network | ☐ Pass ☐ Fail | Mixed map? ☐ No ☐ Yes |
| 16 Telemetry buffer | ☐ Pass ☐ Fail | |
| 17 Crash report and log | ☐ Pass ☐ Fail | |

---

## 7. Stop conditions

Stop the test and report the result when one of these occurs:

1. **Test 2 fails.** Three functions give an incorrect position. Do not use them.
2. **Test 8 gives a `COMPLETED` receipt with the motors off.** The C2 server then gets a report
   that is not true.
3. **Test 13 shows that the speed increases.** The aircraft has no speed control.
4. **The application stops at any time.** Get the crash log before you start the application
   again.
5. **Test 15 step 9 fails.** A loiter that does not stop on a stick input has taken the aircraft
   from the pilot. Do not use the function.
