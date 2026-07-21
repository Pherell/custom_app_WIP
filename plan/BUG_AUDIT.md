# Bug & Misconfiguration Audit — recreate2

**Date:** 2026-07-17  
**Source:** Static analysis + comparison against DJI Mobile SDK V5 official sample code  
**Files analyzed:** `MainActivity.kt`, `MqttService.kt`, `KmzGenerator.kt`, `PayloadDetectionManager.kt`, `HomeActivity.kt`

> Status legend: `[ ]` = not fixed, `[x]` = fixed

---

## CRITICAL — Causes Silent Failure or Wrong Behavior in Flight

---

### [x] C1. GIMBAL MQTT command missing `duration` field
**File:** `MainActivity.kt:3947`  
**Severity:** CRITICAL  
**Status:** FIXED (2026-07-17)

The `"GIMBAL"` MQTT command handler builds a `GimbalAngleRotation` without setting `duration`. Every other gimbal call in the codebase sets duration (`centerGimbal()` uses 2.0, UNLOCK_POI uses 1.0, POI lock loop uses 0.3). Without duration, the SDK may silently reject or snap the gimbal instantly.

**Fix applied:** Added `rotation.duration = json.optDouble("duration", 2.0)` so callers can optionally override.

**MQTT change:** `GIMBAL` command now accepts optional `duration` field (default 2.0 seconds).

---

### [ ] C2. New MQTT command `GIMBAL_SPEED` not yet implemented
**File:** `MainActivity.kt` — `handleMqttCommand`  
**Severity:** CRITICAL  
**Status:** NOT FIXED

The SDK V5 sample (`FPVInteractionWidgetModel`) exclusively demonstrates `GimbalKey.KeyRotateBySpeed` with `GimbalSpeedRotation(pitch, yaw, 0.0, CtrlInfo())` for continuous gimbal movement. `KeyRotateByAngle` still exists in V5 and is valid for absolute positioning, but no `GIMBAL_SPEED` command is exposed to the C2 server. Server operators have no way to do continuous gimbal slewing remotely.

**Fix needed:** Add `"GIMBAL_SPEED"` command handler that calls `KeyRotateBySpeed` + `GimbalSpeedRotation`.

**MQTT change needed:** New command `GIMBAL_SPEED` with `pitch` and `yaw` as speed values (deg/s).

---

### [ ] C3. VirtualStick Advanced Mode not enabled before `sendVirtualStickAdvancedParam`
**File:** `MainActivity.kt:1638` (`startVirtualStickLoop`)  
**Severity:** CRITICAL  
**Status:** NOT FIXED (edit was started but interrupted)

SDK V5 requires `vs.setVirtualStickAdvancedModeEnabled(true)` to be called before `sendVirtualStickAdvancedParam()` works. The call at line 1638 inside `onSuccess` sends `sendVirtualStickAdvancedParam(param)` immediately without enabling advanced mode first. Advanced mode is only enabled later inside the flight loop at lines 1748 and 1760.

**Fix needed:** Add `vs.setVirtualStickAdvancedModeEnabled(true)` immediately after `enableVirtualStick` `onSuccess`, before `sendVirtualStickAdvancedParam`.

---

### [ ] C4. KmzGenerator `waylines.wpml` uses non-standard WPML root structure
**File:** `KmzGenerator.kt:91-192`  
**Severity:** CRITICAL  
**Status:** NOT FIXED

The generated `waylines.wpml` is wrapped in:
```xml
<wpml:waylines>
  <wpml:missionData>
    ...
  </wpml:missionData>
</wpml:waylines>
```
This is **not a valid DJI WPML structure**. The correct root for `waylines.wpml` is:
```xml
<kml>
  <Document>
    <Folder>
      <wpml:templateId>0</wpml:templateId>
      <wpml:waylineId>0</wpml:waylineId>
      <Placemark>...</Placemark>
    </Folder>
  </Document>
</kml>
```
Because of this, `WaypointMissionManager.getAvailableWaylineIDs()` returns an empty list for every auto-generated KMZ. **All grid mapping missions are unlaunchable.**

Also: `template.kml` contains `<wpml:actionOnFinish>goHome</wpml:actionOnFinish>` inside `<Folder>` which is not a valid WPML field (the valid field is `<wpml:finishAction>` inside `<wpml:missionConfig>`).

**Fix needed:** Rewrite `buildWaylinesWpml()` to use the correct KML/Folder/Placemark structure.

---

## HIGH — Logic Bugs & Safety Issues

---

### [ ] H1. RTH and Landing do not disable VirtualStick first
**File:** `MainActivity.kt:2309` (`executeReturnToHome`), `MainActivity.kt:2325` (`executeLanding`)  
**Severity:** HIGH  
**Status:** PARTIALLY FIXED (edit interrupted)

Both functions set `isMissionExecuting = false` to stop the VS loop, but do not call `vs.disableVirtualStick()`. If VS is still enabled at the SDK level when an RTH/Land command is issued, the flight controller may fight between the VS authority and the RTH/Land command, potentially causing unpredictable behavior.

**Fix needed:** Add `VirtualStickManager.getInstance().disableVirtualStick(null)` at the top of both `executeReturnToHome()` and `executeLanding()`.

---

### [ ] H2. `autoStart` KMZ path hardcodes wayline ID `listOf(0)`
**File:** `MainActivity.kt:4476`  
**Severity:** HIGH  
**Status:** NOT FIXED

```kotlin
startKmzWithAutoTakeoff(wpm, lastLoadedKmzFileName!!, listOf(0), "Local")
```

The UI button path (line 1083) and MQTT command path (line 3786) correctly call `wpm.getAvailableWaylineIDs(filePath)`. The `autoStart = true` path (called from `executeTacticalMission`) does not — it unconditionally passes `listOf(0)`. If the KMZ has a different wayline ID or multiple waylines, the mission fails silently.

**Fix needed:** Replace `listOf(0)` with `wpm.getAvailableWaylineIDs(lastLoadedKmzFilePath ?: return)` and add null/empty check.

---

### [ ] H3. PayloadDetectionManager always reports ZOOM and THERMAL lens as available
**File:** `PayloadDetectionManager.kt:44-54`  
**Severity:** HIGH  
**Status:** NOT FIXED

The capability-check `if` blocks for ZOOM and THERMAL are commented out, but `supportedLenses.add(...)` calls remain active outside the commented blocks:

```kotlin
/* val zoomKey = ...
if (keyManager.getValue(zoomKey) != null) { */
    supportedLenses.add(CameraLensType.CAMERA_LENS_ZOOM)  // always runs!
// }
```

Every drone — including non-Enterprise consumer models — will show the lens switcher buttons. Tapping ZOOM or IR on an unsupported drone causes a failed SDK key operation with no user feedback.

**Fix needed:** Restore the capability check. If the SDK key `KeyCameraType` is not available, use a model-name check (same approach used for LRF detection on line 62).

---

### [ ] H4. LRF feature is dead code — `listenToLrfData()` fully commented out
**File:** `PayloadDetectionManager.kt:83-101`  
**Severity:** HIGH  
**Status:** NOT FIXED

The LRF toggle button is shown, the `onLrfDataUpdated` callback is hooked up in `MainActivity`, but `listenToLrfData()` contains only commented-out code. No LRF data ever arrives. The LRF HUD display and MQTT telemetry for `lrf_target` will never trigger from real hardware.

The comments suggest the SDK key name `KeyLaserMeasureInfo` was uncertain. The correct V5 key is `CameraKey.KeyLaserMeasureInformation`.

**Fix needed:** Uncomment and restore `listenToLrfData()` using `CameraKey.KeyLaserMeasureInformation`. Also restore `setLaserMeasureEnabled()` using `CameraKey.KeyLaserMeasureEnable`.

---

### [ ] H5. `isMissionExecuting` lacks `@Volatile` — data race between threads
**File:** `MainActivity.kt:200`  
**Severity:** HIGH  
**Status:** NOT FIXED (edit interrupted)

`private var isMissionExecuting = false` is a plain Kotlin `var`. It is:
- **Written** from: MQTT callback (posted to main thread), UI button clicks (main thread), VirtualStick background thread (line 1856)
- **Read** from: VirtualStick background thread (flight loop condition check)

Without `@Volatile`, the JVM may cache the value in a register and the background thread may never see the updated value, causing the mission loop to run past the stop signal.

**Fix needed:** Change to `@Volatile private var isMissionExecuting = false`.

---

### [ ] H6. Telemetry fields lack `@Volatile` — data race with VirtualStick loop
**File:** `MainActivity.kt:143-150`  
**Severity:** HIGH  
**Status:** NOT FIXED (edit interrupted)

`droneLat`, `droneLon`, `droneAlt`, `droneYaw`, `homeLat`, `homeLon`, `gimbalPitch`, `gimbalYaw` are plain `var`. They are written from SDK key listener callbacks (dispatched to main thread) and read from the VirtualStick background thread for navigation math. Without `@Volatile`, the flight loop may use stale coordinates.

**Fix needed:** Add `@Volatile` to all 9 fields.

---

### [ ] H7. Two listeners on `KeyConnection` with the same lifecycle owner — second overwrites first
**File:** `MainActivity.kt:2785` (`monitorTelemetry`), `MainActivity.kt:3049` (`monitorConnectionStatus`)  
**Severity:** HIGH  
**Status:** NOT FIXED

Both `monitorTelemetry()` and `monitorConnectionStatus()` call:
```kotlin
KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyConnection), this) { ... }
```
In MSDK V5, calling `listen()` twice on the same `(key, lifecycleOwner)` pair replaces the first listener. One of the following will never fire:
- `alertLoss` signal-loss banner (registered in `monitorTelemetry`)
- Drone-status text update `DRN: ONLINE/OFFLINE` (registered in `monitorConnectionStatus`)

**Fix needed:** Merge the two listener blocks into a single `KeyManager.listen` call for `KeyConnection`, handling both alert banner and status text inside one callback.

---

## MEDIUM — State Bugs & Memory Leaks

---

### [ ] M1. RTK location listener not removed in `onDestroy` — activity leak
**File:** `MainActivity.kt:2712`  
**Severity:** MEDIUM  
**Status:** NOT FIXED

`RTKCenter.getInstance().addRTKLocationInfoListener { ... }` captures `this` (MainActivity) via lambda. No corresponding `removeRTKLocationInfoListener` exists in `onDestroy()`. If `RTKCenter` holds a strong reference, the activity cannot be garbage collected.

**Fix needed:** Store the listener reference and call `RTKCenter.getInstance().removeRTKLocationInfoListener(listener)` in `onDestroy()`.

---

### [ ] M2. `PerceptionManager` obstacle data listener not removed — activity leak
**File:** `MainActivity.kt:1966`  
**Severity:** MEDIUM  
**Status:** NOT FIXED

`PerceptionManager.getInstance().addObstacleDataListener { ... }` captures Views owned by the activity. No `removeObstacleDataListener` in `onDestroy()`.

**Fix needed:** Store the listener reference and call `pm.removeObstacleDataListener(listener)` in `onDestroy()`.

---

### [ ] M3. System dialog — stale View references if dismissed via Back button
**File:** `MainActivity.kt:3342-3616`  
**Severity:** MEDIUM  
**Status:** NOT FIXED

When the system dialog opens, `dTvDroneModel`, `dTvRCStatus`, `tvDroneStatus`, `tvRCStatus`, `dTvSimulatorStatus` are set to Views inside the dialog. These are only null-ed out inside `dBtnClose.setOnClickListener`. If the user presses Back (Android default dismiss), the cleanup block never runs. The class-level fields keep alive references to destroyed dialog Views, and SDK listener callbacks continue writing into those stale Views.

**Fix needed:** Add `dialog.setOnDismissListener { ... }` that null-ifies all dialog View references, replacing the close-button-only cleanup.

---

### [ ] M4. `RECORD_STOP` MQTT command does not update `isRecording` flag
**File:** `MainActivity.kt:3937`  
**Severity:** MEDIUM  
**Status:** NOT FIXED

The `"RECORD_STOP"` MQTT command handler calls `KeyStopRecord` directly but never sets `isRecording = false` on success. The local `toggleRecord()` function correctly updates the flag (line 2146). After a C2 `RECORD_STOP`, the app still thinks recording is active. The next `RECORD_START` command or UI button press will call `KeyStopRecord` again instead of `KeyStartRecord`.

**Fix needed:** Add `isRecording = false` inside the `onSuccess` callback of the `RECORD_STOP` handler.

---

### [ ] M5. `updateModeUI()` overrides coordinate system while mission is executing
**File:** `MainActivity.kt:859-862`  
**Severity:** MEDIUM  
**Status:** NOT FIXED

When the user taps the "FLY" mode button — even during an active mission — this runs unconditionally:
```kotlin
val param = VirtualStickFlightControlParam()
param.rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
```
If a waypoint mission is executing in `GROUND` coordinate system, this overrides it to `BODY` mid-flight. Navigation vectors calculated for GROUND frame are then applied in BODY frame → drone flies in the wrong direction.

**Fix needed:** Guard this block with `if (!isMissionExecuting)`.

---

### [ ] M6. `startCscSequence()` sets stick positions but never transmits them to drone
**File:** `MainActivity.kt:2356-2382`  
**Severity:** MEDIUM  
**Status:** NOT FIXED

`startCscSequence()` sets `leftStick.verticalPosition`, `rightStick.horizontalPosition`, etc., but never calls `vs.sendVirtualStickAdvancedParam()` to flush them to the flight controller. Compare with `startEngineUsingVirtualStick()` at line 4587 which does call `sendVirtualStickAdvancedParam()` inside a Thread loop. The CSC sequence triggered from `executeManualStart()` is likely a no-op on the drone.

**Fix needed:** Add `vs.sendVirtualStickAdvancedParam(VirtualStickFlightControlParam())` calls after setting stick positions, and after the reset-to-zero block.

---

### [ ] M7. `enableVirtualStick(null)` race in gimbal yaw-limit handler
**File:** `MainActivity.kt:3249-3259`  
**Severity:** MEDIUM  
**Status:** NOT FIXED

```kotlin
vs.enableVirtualStick(null)              // async, callback is null — no way to know when done
isVirtualStickEnabledLocally = true
vs.leftStick.horizontalPosition = ...
vs.sendVirtualStickAdvancedParam(...)    // may execute before enable completes
```
`enableVirtualStick` is async. Passing `null` as callback means there is no signal for when the enable is complete. The immediately following `sendVirtualStickAdvancedParam` call may silently fail because VS is not yet active.

**Fix needed:** Move the stick position setting and `sendVirtualStickAdvancedParam` call into a proper `CompletionCallback.onSuccess` block.

---

## LOW — Error Handling & Configuration

---

### [ ] L1. LRF MQTT publish — completely empty catch block
**File:** `MainActivity.kt:518`  
**Severity:** LOW  

```kotlin
} catch (e: Exception) {}
```
MQTT publish failure for LRF telemetry is silently dropped with no log or user feedback.

**Fix needed:** Add `log("LRF publish failed: ${e.message}")`.

---

### [ ] L2. `RECORD_STOP` MQTT command — `onFailure` body is completely empty
**File:** `MainActivity.kt:3942`  
**Severity:** LOW  

```kotlin
override fun onFailure(error: IDJIError) {
    // empty
}
```
A failure to stop recording via MQTT command is invisible to operator and logs.

**Fix needed:** Add `runOnUiThread { showToast("C2 Record Stop Error: ${error.description()}") }` and `log(...)`.

---

### [ ] L3. `capturePhoto()` `onFailure` has no user toast
**File:** `MainActivity.kt:2030`  
**Severity:** LOW  

```kotlin
override fun onFailure(error: IDJIError) {
    log("Photo Capture Failed: ${error.errorCode()}")
}
```
During autonomous missions a missed capture is invisible to the operator. All other SDK action failures in the file call `showToast()`.

**Fix needed:** Add `showToast("Photo Failed: ${error.errorCode()}")`.

---

### [ ] L4. `MqttService.updateDroneId()` subscription failure — only `e.printStackTrace()`
**File:** `MqttService.kt:100`  
**Severity:** LOW  

If re-subscribing to the new drone's command topic fails (e.g., brief disconnect), the app silently continues without the correct subscription. The UI will not reflect the failure.

**Fix needed:** Invoke `onConnectionStatusChanged?.invoke(false)` or add a dedicated error callback.

---

### [ ] L5. MQTT credentials hardcoded — cannot be changed from UI
**File:** `MqttService.kt:36-37`  
**Severity:** LOW  

Default credentials `avarell` / `avAREl1z02B` are hardcoded as fallback values:
```kotlin
userName = sharedPrefs.getString("mqttUser", "avarell")
password = sharedPrefs.getString("mqttPass", "avAREl1z02B")?.toCharArray()
```
The keys `mqttUser` and `mqttPass` exist in SharedPreferences but are never exposed in the settings dialog (only `mqttServerAddress` is editable in the CFG tab). Credentials can only be changed by recompiling.

**Fix needed:** Add `mqttUser` and `mqttPass` fields to the CFG tab in `showSystemDialog()`.

---

## Summary Table

| ID | Severity | File | Status | Description |
|---|---|---|---|---|
| C1 | CRITICAL | MainActivity | **FIXED** | GIMBAL missing `duration` field |
| C2 | CRITICAL | MainActivity | NOT FIXED | New `GIMBAL_SPEED` command missing |
| C3 | CRITICAL | MainActivity | NOT FIXED | VS Advanced Mode not enabled before param send |
| C4 | CRITICAL | KmzGenerator | NOT FIXED | WPML structure invalid — all grid missions broken |
| H1 | HIGH | MainActivity | NOT FIXED | RTH/Land don't disable VirtualStick |
| H2 | HIGH | MainActivity | NOT FIXED | autoStart KMZ hardcodes wayline `listOf(0)` |
| H3 | HIGH | PayloadDetection | NOT FIXED | Zoom/IR always shown on all drones |
| H4 | HIGH | PayloadDetection | NOT FIXED | LRF listener is dead code |
| H5 | HIGH | MainActivity | NOT FIXED | `isMissionExecuting` not `@Volatile` |
| H6 | HIGH | MainActivity | NOT FIXED | Telemetry nav fields not `@Volatile` |
| H7 | HIGH | MainActivity | NOT FIXED | Duplicate `KeyConnection` listener — one never fires |
| M1 | MEDIUM | MainActivity | NOT FIXED | RTK listener leak in `onDestroy` |
| M2 | MEDIUM | MainActivity | NOT FIXED | PerceptionManager listener leak in `onDestroy` |
| M3 | MEDIUM | MainActivity | NOT FIXED | Dialog Back-dismiss leaves stale View refs |
| M4 | MEDIUM | MainActivity | NOT FIXED | `RECORD_STOP` MQTT doesn't update `isRecording` |
| M5 | MEDIUM | MainActivity | NOT FIXED | `updateModeUI` overrides VS coordinate mid-mission |
| M6 | MEDIUM | MainActivity | NOT FIXED | `startCscSequence` never transmits stick data |
| M7 | MEDIUM | MainActivity | NOT FIXED | `enableVirtualStick(null)` race in gimbal handler |
| L1 | LOW | MainActivity | NOT FIXED | LRF publish — empty catch |
| L2 | LOW | MainActivity | NOT FIXED | `RECORD_STOP` `onFailure` is empty |
| L3 | LOW | MainActivity | NOT FIXED | `capturePhoto` failure has no user toast |
| L4 | LOW | MqttService | NOT FIXED | `updateDroneId` failure silent |
| L5 | LOW | MqttService | NOT FIXED | MQTT credentials not configurable from UI |

---

## MQTT Command Changes Required (when bugs are fixed)

| Change | Type | Reason |
|---|---|---|
| `GIMBAL` — add optional `duration` field | Enhancement | C1 fix: controls rotation speed |
| `GIMBAL_SPEED` — new command | New | C2: continuous gimbal slew via `KeyRotateBySpeed` |
| `RECORD_START` / `RECORD_STOP` — server must track `isRecording` state | Doc update | M4: client state may desync via C2 |
| MQTT credentials — new config fields in CFG tab | UX | L5: `mqttUser` and `mqttPass` fields |
