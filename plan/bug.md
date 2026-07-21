# Bugfix Report — `recreate2` (DJI MSDK V5 Android)

**Repository:** `C:\Users\avare\Documents\recreate2`  
**Reference SDK samples:** `C:\Users\avare\Documents\Mobile-SDK-Android-V5-dev-sdk-main`  
**Review type:** Bugbot (logic/correctness)  
**Review date:** 2026-07-17  
**Prepared for:** downstream fix agent  

---

## Executive summary

This Android app integrates DJI Mobile SDK V5 for drone control, MQTT C2, KMZ waypoint missions, virtual-stick tactical missions, and WebODM photo upload. The core SDK wiring (SDK init, KMZ push/start API usage, media download pattern) is generally aligned with the official V5 sample code. However, **12 bugs** were found — **4 high/critical** affecting flight safety or mission correctness.

**Recommended fix order:** Issues #1 → #2 → #3 → #4 first (safety), then #5–#8 (mission correctness), then #9–#12 (robustness/UX).

---

## Repository map (relevant files)

| File | Role | ~Lines |
|------|------|--------|
| `app/src/main/java/com/dji/recreate2/MainActivity.kt` | Main flight UI, missions, MQTT handler, virtual stick | ~4610 |
| `app/src/main/java/com/dji/recreate2/KmzGenerator.kt` | WPML/KMZ generation for mapping | ~159 |
| `app/src/main/java/com/dji/recreate2/WebODMAutoUpload.kt` | Drone → phone → WebODM sync | ~358 |
| `app/src/main/java/com/dji/recreate2/MqttService.kt` | MQTT connect/publish/subscribe | ~146 |
| `app/src/main/java/com/dji/recreate2/PayloadDetectionManager.kt` | Lens/LRF capability detection | ~108 |
| `app/src/main/java/com/dji/recreate2/HomeActivity.kt` | SDK init, permissions | ~182 |

**DJI sample references for correct patterns:**
- `SampleCode-V5/android-sdk-v5-sample/.../models/WayPointV3VM.kt` — KMZ lifecycle
- `SampleCode-V5/android-sdk-v5-sample/.../pages/VirtualStickFragment.kt` — virtual stick
- `SampleCode-V5/android-sdk-v5-sample/.../pages/MediaFileDetailsFragment.kt` — media download

---

## Findings (fix tickets)

### BUG-001 — Critical: Signal-loss recovery triggers wrong mission path

**Severity:** Critical  
**File:** `MainActivity.kt`  
**Lines:** 2849–2866  

**Current behavior:**
When air-link signal drops below 15% during an active mission, the code:
1. Clears waypoints and stops virtual stick output
2. Adds home as a single waypoint
3. Calls `executeTacticalMission()`
4. Shows toast: `"SIGNAL LOST! RTH TO HOVER INITIATED"`

**Problem:**
- `executeTacticalMission()` in `PROFESSIONAL` mode generates and **pushes a new KMZ** (line 1606–1615), not RTH.
- In other modes it starts a **new virtual-stick mission**, not native RTH.
- Toast is misleading; `KeyStartGoHome` is never called.

**Expected behavior:**
On critical signal loss during mission:
1. Stop/cancel current mission (virtual stick + KMZ if running)
2. Call `executeReturnToHome()` (uses `FlightControllerKey.KeyStartGoHome` at line 2304)
3. Do **not** call `executeTacticalMission()`

**Suggested fix:**
```kotlin
// Replace lines 2849-2866 with something like:
if (it < 15 && isMissionExecuting) {
    cancelActiveMission() // new helper — see BUG-003
    executeReturnToHome()
    showToast("SIGNAL LOST! Return to Home initiated")
}
```

**Acceptance criteria:**
- [ ] Signal loss during virtual-stick mission triggers RTH, not new waypoint mission
- [ ] Signal loss during KMZ mission stops KMZ first, then RTH
- [ ] No KMZ push occurs on signal loss

---

### BUG-002 — High: KMZ interval photo only fires at waypoint 0

**Severity:** High  
**File:** `KmzGenerator.kt`  
**Lines:** 111–130  

**Current behavior:**
`shootPhotoTimeInterval` action group is only added when `index == 0`, with:
```xml
<wpml:actionGroupStartIndex>0</wpml:actionGroupStartIndex>
<wpml:actionGroupEndIndex>0</wpml:actionGroupEndIndex>
```

**Problem:**
Mapping grid missions will capture photos only at the first waypoint. The interval action ends immediately.

**Expected behavior:**
Action group should span the **entire wayline**:
```xml
<wpml:actionGroupStartIndex>0</wpml:actionGroupStartIndex>
<wpml:actionGroupEndIndex>{lastWaypointIndex}</wpml:actionGroupEndIndex>
```

**Suggested fix:**
1. Move action group generation **outside** the per-waypoint loop (or compute `lastIndex = waypoints.lastIndex` once).
2. Set `actionGroupEndIndex` to `waypoints.lastIndex`.
3. Consider `actionTriggerType` = `betweenAdjacentPoints` if WPML version supports it for interval shooting (verify against DJI WPML docs / sample KMZ in SDK bundle).

**Acceptance criteria:**
- [ ] Generated KMZ produces photos at interval across full grid route
- [ ] Test on aircraft or DJI simulator with mapping mission

---

### BUG-003 — High: Cancel mission does not stop native KMZ on aircraft

**Severity:** High  
**File:** `MainActivity.kt`  
**Lines:** 1105–1120  

**Current behavior:**
`btnCancelMission` click handler:
- Sets `isMissionExecuting = false`
- Clears local waypoints
- Zeroes virtual stick positions
- Does **not** call `WaypointMissionManager.stopMission()`
- Does **not** call `vs.disableVirtualStick()`

**Problem:**
If a native KMZ mission is running on the drone, the aircraft continues the mission while the app UI shows "Mission Cancelled".

**Suggested fix:**
Create a shared helper `cancelActiveMission()` used by cancel button, signal-loss handler (BUG-001), and `onDestroy()` (BUG-008):

```kotlin
private fun cancelActiveMission() {
    isMissionExecuting = false
    tacticalWaypoints.clear()

    // Stop native KMZ if loaded
    lastLoadedKmzFileName?.let { fileName ->
        WaypointMissionManager.getInstance().stopMission(fileName, null)
    }

    // Stop virtual stick
    VirtualStickManager.getInstance().apply {
        leftStick.horizontalPosition = 0
        leftStick.verticalPosition = 0
        rightStick.verticalPosition = 0
        rightStick.horizontalPosition = 0
        disableVirtualStick(null)
    }

    // Update UI...
}
```

**Reference:** `WayPointV3VM.kt` in DJI sample — `stopMission(missionID, callback)`.

**Acceptance criteria:**
- [ ] Cancel button stops KMZ mission on aircraft
- [ ] Cancel button disables virtual stick
- [ ] Same helper reused in signal-loss and lifecycle cleanup

---

### BUG-004 — High: Unauthenticated remote MQTT command execution

**Severity:** High (operational safety)  
**File:** `MainActivity.kt` (`handleMqttCommand`), `MqttService.kt`  
**Lines:** 3647+ (handler), 40–74 (subscribe)  

**Current behavior:**
Any message on topics:
- `avarell/fleet/{droneId}/command`
- `avarell/fleet/broadcast/command`
- `avarell/fleet/config`

…is parsed and executed. Dangerous commands include:
- `TAKE_OFF` / `AUTO_TAKEOFF` / `START_ENGINE`
- `UPLOAD_KMZ` (base64 payload)
- `DOWNLOAD_KMZ` (arbitrary URL fetch)
- `EXECUTE_MISSION`, `START_KMZ`, etc.

**Problem:**
No authentication, signing, nonce, or command source validation. Any broker subscriber can control the drone.

**Suggested fix (minimum viable):**
1. Require `authToken` field in JSON; validate against SharedPreferences secret configured in UI.
2. Reject commands missing/invalid token before `when (command)`.
3. Use MQTT over TLS (`ssl://`) instead of `tcp://`.
4. For `DOWNLOAD_KMZ`: allowlist hostnames, enforce max file size, validate `.kmz` extension.

**Suggested fix (production):**
- Broker-side ACLs per drone ID
- Signed commands (HMAC-SHA256 with shared secret + timestamp window)

**Acceptance criteria:**
- [ ] Commands without valid token are rejected and logged
- [ ] `DOWNLOAD_KMZ` cannot fetch from arbitrary URLs
- [ ] Document required MQTT message schema

---

### BUG-005 — High: Pre-flight checks fail open on parse errors

**Severity:** High  
**File:** `MainActivity.kt`  
**Lines:** 1580–1603, 4413–4445  

**Current behavior:**
```kotlin
} catch (e: Exception) {
    log("Pre-flight check parse error, overriding...")
}
// mission continues
```

Defaults when parsing fails:
- Battery → `100`
- GPS satellites → `15`

**Problem:**
Mission can proceed with unknown battery/GPS state.

**Suggested fix:**
Fail closed — abort mission on parse error:
```kotlin
} catch (e: Exception) {
    log("Pre-flight check failed: ${e.message}")
    showToast("PRE-FLIGHT FAILED: Cannot read telemetry")
    return
}
```

Also read telemetry from `KeyManager` directly (battery/GPS keys already listened at lines 2742+) instead of parsing UI TextView strings.

**Acceptance criteria:**
- [ ] Parse error aborts mission
- [ ] Pre-flight uses SDK key values, not UI text parsing

---

### BUG-006 — Medium: WebODM saved project ID is ignored

**Severity:** Medium  
**File:** `WebODMAutoUpload.kt`  
**Lines:** 221–290  

**Current behavior:**
- `getProjectId(context)` is read and validated (`projectId < 0` fails)
- Code then **always creates a new project** (lines 262–290)
- Upload goes to `newProjectId`, not the user-selected `projectId`

**Problem:**
User selects project in config dialog (`MainActivity.kt` 1545–1558 saves `pId`), but sync always creates `"Recreate2_Mission_{timestamp}"`.

**Suggested fix:**
```kotlin
val targetProjectId = if (projectId >= 0) {
    projectId
} else {
    // create new project only when no project selected
    createNewProject(...)
}
// POST to /api/projects/$targetProjectId/tasks/
```

**Acceptance criteria:**
- [ ] When user selects existing project, task is created under that project
- [ ] New project creation only when `projectId == -1` or explicit "create new" option

---

### BUG-007 — Medium: Professional "Execute" only pushes KMZ, does not start

**Severity:** Medium (UX / operator confusion)  
**File:** `MainActivity.kt`  
**Lines:** 1606–1618, 1068–1086  

**Current behavior:**
In `PROFESSIONAL` mapping mode, `executeTacticalMission()`:
1. Generates KMZ via `KmzGenerator.generateMappingKmz()`
2. Calls `executeNativeKMZ()` (push only)
3. Returns early — mission does **not** auto-start

Starting requires separate `btnStartKmz` click or MQTT `START_KMZ`.

**Problem:**
Operator clicks "Execute Mission" expecting full run; drone receives KMZ but does not fly until second action.

**Suggested fix (choose one):**
- **Option A:** After successful push in `executeNativeKMZ` onSuccess, auto-call `startKmzWithAutoTakeoff()` (may need flag to distinguish push-only C2 workflow)
- **Option B:** Rename UI button to "Push KMZ" and show dialog: "KMZ pushed. Tap START KMZ to fly."
- **Option C:** Add config toggle: "Auto-start after push"

**Acceptance criteria:**
- [ ] Operator intent is clear in UI
- [ ] Local Execute either auto-starts or explicitly prompts for Start

---

### BUG-008 — Medium: `onDestroy()` incomplete teardown

**Severity:** Medium  
**File:** `MainActivity.kt`  
**Lines:** 4481–4493  

**Current behavior:**
`onDestroy()` cancels timers/handlers, detaches map, cancels KeyManager listeners, disconnects MQTT.  
**Missing:**
- Stop active KMZ mission
- Disable virtual stick
- `PayloadDetectionManager.cleanup()`
- Cancel virtual-stick background thread (mission loop at line 1645)

**Suggested fix:**
Call `cancelActiveMission()` (BUG-003) from `onDestroy()` before SDK cleanup.

**Acceptance criteria:**
- [ ] Leaving activity stops drone mission control paths
- [ ] No virtual stick commands sent after destroy

---

### BUG-009 — Medium: `MqttService.isConnected` race condition

**Severity:** Medium  
**File:** `MqttService.kt`  
**Lines:** 13, 43–44, 59–60, 108, 121  

**Current behavior:**
`isConnected` is a plain `var` updated on Paho callback thread, read from main thread in `publishTelemetry()` / `publishMission()`.

**Suggested fix:**
```kotlin
@Volatile var isConnected = false
```
Or check `mqttClient?.isConnected == true` exclusively (already partially done at line 108).

**Acceptance criteria:**
- [ ] No stale `isConnected` reads across threads

---

### BUG-010 — Medium: Payload lens detection always reports ZOOM/THERMAL

**Severity:** Medium  
**File:** `PayloadDetectionManager.kt`  
**Lines:** 39–56  

**Current behavior:**
Zoom and thermal lenses are unconditionally added; real SDK key checks are commented out.

**Problem:**
Non-enterprise drones show lens buttons that fail at runtime.

**Suggested fix:**
Uncomment and fix SDK key lookups for lens availability, or gate UI buttons on `currentState.availableLenses` in MainActivity lens switcher.

**Acceptance criteria:**
- [ ] Only supported lenses shown per connected aircraft type

---

### BUG-011 — Medium: Fixed 6s takeoff delay before KMZ start

**Severity:** Medium  
**File:** `MainActivity.kt`  
**Lines:** 4521–4527  

**Current behavior:**
If `droneAlt < 1.0`, calls `executeTakeoff()` then `postDelayed(startMissionLogic, 6000)`.

**Problem:**
Fixed timer may start mission before aircraft reaches safe altitude.

**Suggested fix:**
Listen to `FlightControllerKey.KeyAltitude` (or takeoff state key) and start mission when altitude > threshold (e.g. 3–5 m), with timeout fallback.

**Acceptance criteria:**
- [ ] KMZ starts after confirmed altitude, not fixed delay
- [ ] Timeout aborts if takeoff fails

---

### BUG-012 — Low: Log auto-scroll operator precedence bug

**Severity:** Low  
**File:** `MainActivity.kt`  
**Line:** 3641  

**Current code:**
```kotlin
val scrollAmount = logText!!.layout?.getLineTop(logText!!.lineCount) ?: 0 - logText!!.height
```

**Problem:**
Parses as `layout?.getLineTop(...) ?: (0 - height)`. When layout exists, `height` is never subtracted; log may not scroll to bottom.

**Suggested fix:**
```kotlin
val scrollAmount = (logText!!.layout?.getLineTop(logText!!.lineCount) ?: 0) - logText!!.height
```

**Acceptance criteria:**
- [ ] Log view scrolls to latest entry

---

## Additional notes for fix agent (non-blocking)

These were observed but not filed as primary bugs:

| Item | Location | Note |
|------|----------|------|
| Dummy MQTT heartbeat | `MainActivity.kt:286–302` | Sends fake telemetry every 3s; remove or gate behind debug flag for production |
| Hardcoded MQTT password default | `MqttService.kt:37` | Default password in source; move to required config |
| `KmzGenerator` hardcoded drone enum | `KmzGenerator.kt:75` | `droneEnumValue=67` (M3E); wrong drone may reject KMZ |
| `TestRtmp.kt` | unused stub | Safe to delete or implement |
| `SERVER_API_DOCS.md` | repo root | Appears empty/corrupt (`]]` only); may need regeneration |

---

## Suggested implementation plan

### Phase 1 — Safety (same PR or first)
1. Implement `cancelActiveMission()` helper
2. Fix BUG-001 (signal loss → RTH)
3. Fix BUG-003 (cancel stops KMZ + virtual stick)
4. Fix BUG-005 (fail-closed preflight)
5. Fix BUG-008 (`onDestroy` teardown)

### Phase 2 — Mission correctness
6. Fix BUG-002 (KMZ interval photo span)
7. Fix BUG-007 (Execute vs Start UX)
8. Fix BUG-011 (altitude-gated takeoff before KMZ start)

### Phase 3 — Infrastructure
9. Fix BUG-004 (MQTT auth) — may need server-side coordination
10. Fix BUG-006 (WebODM project ID)
11. Fix BUG-009, BUG-010, BUG-012

---

## Test plan (for fix agent)

| Test | Steps | Expected |
|------|-------|----------|
| Cancel KMZ | Start KMZ mission → tap Cancel | Aircraft stops mission |
| Signal loss | Simulate low signal during mission | RTH invoked, no new KMZ push |
| Mapping photos | Run PROFESSIONAL grid mission | Photos captured along full route |
| WebODM sync | Configure existing project ID → sync | Task created under selected project, not new project |
| MQTT auth | Send command without token | Command rejected |
| Preflight fail | Disconnect telemetry / empty battery UI | Mission blocked |
| Destroy cleanup | Start mission → back/destroy activity | Virtual stick disabled, mission stopped |

---

## Key code references

**Signal loss bug:**
```2849:2866:app/src/main/java/com/dji/recreate2/MainActivity.kt
                    if (it < 15 && isMissionExecuting) {
                        isMissionExecuting = false
                        tacticalWaypoints.clear()
                        // ... zeros sticks ...
                        if (!homeLat.isNaN() && !homeLon.isNaN()) {
                            tacticalWaypoints.add(TacticalWaypoint(GeoPoint(homeLat, homeLon), 50.0))
                            isMissionExecuting = true
                            executeTacticalMission()  // BUG: should be executeReturnToHome()
                            showToast("SIGNAL LOST! RTH TO HOVER INITIATED")
```

**KMZ photo interval bug:**
```111:116:app/src/main/java/com/dji/recreate2/KmzGenerator.kt
                  ${if (index == 0) """
                  <wpml:actionGroup>
                    <wpml:actionGroupStartIndex>0</wpml:actionGroupStartIndex>
                    <wpml:actionGroupEndIndex>0</wpml:actionGroupEndIndex>
```

**Cancel mission gap:**
```1105:1118:app/src/main/java/com/dji/recreate2/MainActivity.kt
        findViewById<TextView>(R.id.btnCancelMission).setOnClickListener { 
            isMissionExecuting = false
            tacticalWaypoints.clear()
            // Missing: WaypointMissionManager.stopMission()
            // Missing: disableVirtualStick()
```

---

## Handoff instruction for fix agent

1. Read this report fully before editing.
2. Start with **`cancelActiveMission()`** — it unblocks BUG-001, BUG-003, BUG-008.
3. Keep changes minimal; match existing Kotlin/style in `MainActivity.kt`.
4. Do not refactor the 4600-line activity unless necessary for the fix.
5. Compare KMZ/WPML changes against DJI sample `WayPointV3VM` and any bundled WPML examples in the SDK repo.
6. Run `./gradlew assembleDebug` and unit tests after changes.
7. Manual test on RC + aircraft for mission cancel, RTH, and KMZ photo interval.

---

*To persist this report in the repo, switch to Agent mode and save as e.g. `plan/BUGFIX_REPORT.md`.*