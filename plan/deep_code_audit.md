# Deep Code Audit — Recreate2 Tactical Drone Platform
**Audit Date:** 2026-07-15  
**Auditor:** Antigravity AI  
**Scope:** Full-stack analysis of Android App, Tactical Server (Backend + Frontend), MQTT Broker

---

## FORCE CLOSE (CRASH) ROOT CAUSES

These are the defects most likely causing the app to crash immediately upon launch or during operation.

### CRASH-01: `lateinit` Views Accessed Before `setContentView`  
**File:** `MainActivity.kt` lines 234–270  
**Severity:** 🔴 CRITICAL (Force Close)  
**Description:** `PayloadDetectionManager.onPayloadDetected` is set up at line 234, which references `lensSwitcher`, `btnLensZoom`, `btnLensIr`, and `btnLrfToggle`. However, `fpvSurface` and other `lateinit` views are only bound starting at line 459 (`fpvSurface = findViewById(...)`). If the `ProductKey.KeyProductType` listener fires before `onCreate` finishes binding ALL views, a `UninitializedPropertyAccessException` is thrown → Force Close.  
**Fix:** Move PayloadDetectionManager callback registration AFTER all `findViewById` calls (after line 492).

### CRASH-02: `KeyManager` Called Without SDK Readiness Guard  
**File:** `MainActivity.kt` lines 2312–2605 (`monitorTelemetry()`, `setupVideoFeed()`)  
**Severity:** 🔴 CRITICAL (Force Close)  
**Description:** Multiple `KeyManager.getInstance().listen(...)` calls are made directly in `onCreate` flow without verifying that `SDKManager.getInstance().registerApp()` has completed. If the user navigates to `MainActivity` before SDK registration finishes in `HomeActivity`, `KeyManager.getInstance()` returns an uninitialized singleton → `NullPointerException` → Force Close.  
**Fix:** Add a guard: `if (SDKManager.getInstance().isRegistered()) { ... }` before all KeyManager calls, or pass a registration-complete flag via Intent extras from HomeActivity.

### CRASH-03: `MediaDataCenter.getInstance().cameraStreamManager` NPE  
**File:** `MainActivity.kt` line 2672  
**Severity:** 🔴 CRITICAL (Force Close)  
**Description:** `MediaDataCenter.getInstance().cameraStreamManager` can return `null` if no drone is connected. `enableStream()` is then called on a null reference. The `try/catch` only catches generic Exception but a Kotlin-level NPE from calling a method on a null `val` can bypass this.  
**Fix:** Add explicit null check: `val streamManager = MediaDataCenter.getInstance().cameraStreamManager ?: return`

### CRASH-04: `ObstacleRadarView` Infinite Invalidation Loop  
**File:** `ObstacleRadarView.kt` line 66  
**Severity:** 🟡 HIGH (ANR / UI Freeze leading to system kill)  
**Description:** `postInvalidateDelayed(33)` is called unconditionally in `onDraw()`. This creates a 30 FPS animation loop that runs **forever**, even when the radar view is invisible or the app is in the background. This prevents the GPU from sleeping and continuously consumes CPU. Combined with the 10Hz telemetry loop and VirtualStick loop, it can trigger an ANR (Application Not Responding) → System Force Close.  
**Fix:** Only call `postInvalidateDelayed` when the view is visible AND at least one distance is within range.

### CRASH-05: Unbounded Thread Spawning in `MqttService.kt`  
**File:** `MqttService.kt` lines 21, 133  
**Severity:** 🟡 HIGH (OOM → Force Close)  
**Description:** `Thread { ... }.start()` is used for connect and disconnect operations. If MQTT connection fails and `isAutomaticReconnect = true` triggers repeatedly, new raw threads are spawned each time. On a resource-constrained Android device (especially DJI RC with limited RAM), this can lead to `OutOfMemoryError` → Force Close.  
**Fix:** Replace raw `Thread` with a single-thread `ExecutorService` or coroutine scope.

---

## LOGIC DEFECTS

### LOGIC-01: `heartbeatTimer` Sends Hardcoded `drone_alpha_01`  
**File:** `MainActivity.kt` lines 326–339  
**Severity:** 🟠 MEDIUM  
**Description:** The heartbeat timer always sends telemetry under `drone_id: "drone_alpha_01"` regardless of the actual connected drone's serial number. This pollutes the C2 dashboard with phantom drone data and conflicts with real telemetry from the actual drone identified dynamically at line 2338.  
**Fix:** Either remove this dummy heartbeat entirely, or replace the hardcoded ID with `cachedDroneSn`.

### LOGIC-02: `setInterval` Memory Leak in Frontend Simulation  
**File:** `App.jsx` lines 396–434  
**Severity:** 🟠 MEDIUM  
**Description:** `startSimulation()` uses `setInterval()` but never stores the interval ID. Pressing "START 3D SIMULATION" multiple times stacks multiple intervals that can never be cleared, consuming increasing amounts of CPU and memory.  
**Fix:** Store the interval ID and check if simulation is already running before creating a new one.

### LOGIC-03: Flight Trail Data Uses Wrong Coordinate Keys  
**File:** `App.jsx` line 598  
**Severity:** 🟠 MEDIUM  
**Description:** Trail positions are accessed as `pt.lng` and `pt.lat`, but trail data is stored at line 170 as `[data.location.latitude, data.location.longitude]` (an array of `[lat, lon]`, not an object with `.lat/.lng`). This means all flight trails render at `0,0` (null island).  
**Fix:** Change line 598 to: `const positions = trail.map(pt => Cartesian3.fromDegrees(pt[1], pt[0], 50));`

### LOGIC-04: WebODM Timestamp Parsing Uses Reflection Unsafely  
**File:** `WebODMAutoUpload.kt` lines 91–97  
**Severity:** 🟠 MEDIUM  
**Description:** `mediaFile.javaClass.getMethod("getCreateTime")` uses Java reflection to extract timestamps. If the MSDK V5 version doesn't expose `getCreateTime()` (some versions use `getDate()` or `getTimeTaken()`), the `NoSuchMethodException` is caught silently and the file passes through the filter unfiltered (fallback at line 107: `true`). This defeats the purpose of time-bounding.  
**Fix:** Access the known `MediaFile` API method directly (e.g., `mediaFile.date`) instead of reflection.

### LOGIC-05: Download Stream Writes with Wrong Offset  
**File:** `WebODMAutoUpload.kt` lines 148–153  
**Severity:** 🟠 MEDIUM  
**Description:** `onRealtimeDataUpdate(data, position)` provides a `position` parameter indicating where in the file the data belongs, but `fos.write(data)` just appends sequentially. If the DJI SDK delivers chunks out of order (which can happen during retransmission), the resulting file will be corrupted.  
**Fix:** Use `RandomAccessFile` and seek to `position` before writing, or verify sequential delivery.

### LOGIC-06: `downloadFileToDedicatedFolder` Stream Close Order  
**File:** `MainActivity.kt` lines 1934–1936  
**Severity:** 🟡 LOW  
**Description:** `outputStream.close()` is called before `bos.close()`. The `BufferedOutputStream` wraps `outputStream`, so closing the inner stream first can cause data loss (unflushed buffer). Standard practice is to close only the outermost wrapper.  
**Fix:** Remove `outputStream.close()` and only call `bos.close()`.

---

## SECURITY DEFECTS

### SEC-01: MQTT Credentials Hardcoded in Source  
**File:** `MqttService.kt` line 35–36  
**Severity:** 🔴 CRITICAL  
**Description:** Default MQTT username `"avarell"` and password `"avAREl1z02B"` are hardcoded as fallback values. Anyone who decompiles the APK gets these credentials.  
**Fix:** Remove hardcoded defaults. Require the user to configure credentials via `HomeActivity` settings dialog before connecting.

### SEC-02: DJI API Key Exposed in Manifest  
**File:** `AndroidManifest.xml` line 46  
**Severity:** 🟠 MEDIUM  
**Description:** The DJI developer API key `c453f7fe6b8aea663c5b3a55` is committed to source in plain text. If this repository is ever public, the key can be abused.  
**Fix:** Move to `local.properties` or a build config variable excluded from version control.

### SEC-03: Anonymous MQTT Access Enabled  
**File:** `mosquitto.conf` line 17  
**Severity:** 🔴 CRITICAL  
**Description:** `allow_anonymous true` means anyone on the network can subscribe to all drone telemetry and publish flight commands (takeoff, land, RTH) without authentication. A malicious actor could hijack a drone in flight.  
**Fix:** Set `allow_anonymous false` and configure a `password_file` with `mosquitto_passwd`.

### SEC-04: No Authentication on Backend API  
**File:** `server.js` lines 82–105  
**Severity:** 🟠 MEDIUM  
**Description:** `/api/history/:droneId` and `/api/trail/:droneId` endpoints have zero authentication. Anyone who can reach the server can pull all historical flight data.  
**Fix:** Add JWT or API key middleware to Express routes.

### SEC-05: `usesCleartextTraffic` Enabled  
**File:** `AndroidManifest.xml` line 37  
**Severity:** 🟡 LOW  
**Description:** `android:usesCleartextTraffic="true"` allows all HTTP (non-encrypted) network traffic. MQTT telemetry and WebODM credentials are sent in plaintext.  
**Fix:** Use TLS for MQTT (`mqtts://`) and HTTPS for WebODM in production.

---

## PERFORMANCE DEFECTS

### PERF-01: Unbatched PostgreSQL Telemetry Writes  
**File:** `server.js` lines 71–74  
**Severity:** 🟡 HIGH  
**Description:** Every single MQTT telemetry message (arriving at 2–10 Hz per drone) triggers an individual `INSERT INTO telemetry` query. With 5 drones at 10 Hz, that's 50 INSERT queries/second. PostgreSQL will exhaust the connection pool (default 10 connections) and subsequent queries will timeout.  
**Fix:** Buffer telemetry messages in memory and batch-insert every 5 seconds using a single multi-row INSERT.

### PERF-02: Telemetry Publishes on Every GPS Update  
**File:** `MainActivity.kt` lines 2325–2377  
**Severity:** 🟡 HIGH  
**Description:** A full JSON telemetry payload (including `cachedDroneSn` key lookups and `tacticalWaypoints` serialization) is constructed and published via MQTT on **every single** GPS location update (up to 10 Hz). This is extremely wasteful.  
**Fix:** Add a time-based throttle (e.g., publish at most once per 500ms) and skip publishing if position hasn't changed significantly.

### PERF-03: React State Thrashing in Telemetry Handler  
**File:** `App.jsx` lines 160–178  
**Severity:** 🟠 MEDIUM  
**Description:** `setFleet()` and `setFlightTrails()` are called for every MQTT message. Each call triggers a full React re-render of the Cesium 3D globe, 3D drone models, and all UI components. At 10 Hz per drone, this causes severe frame drops on the dashboard.  
**Fix:** Use `useRef` for high-frequency data and batch state updates using `requestAnimationFrame`.

### PERF-04: New `UrlTemplateImageryProvider` on Every Render  
**File:** `App.jsx` line 545  
**Severity:** 🟠 MEDIUM  
**Description:** `imageryProvider={new UrlTemplateImageryProvider({ url: getTileUrl() })}` creates a brand new provider object on every React render. Cesium treats each new provider as a tile source change, causing map tiles to reload and flicker.  
**Fix:** Memoize with `useMemo`: `const imageryProvider = useMemo(() => new UrlTemplateImageryProvider({ url: getTileUrl() }), [mapStyle]);`

---

## RESOURCE LEAK DEFECTS

### LEAK-01: `heartbeatTimer` Continues After Activity Pause  
**File:** `MainActivity.kt` lines 326–339  
**Severity:** 🟠 MEDIUM  
**Description:** `heartbeatTimer` is only cancelled in `onDestroy()`. If the user presses Home or switches apps, the timer continues firing MQTT publishes in the background, draining battery and wasting bandwidth.  
**Fix:** Cancel in `onPause()` and restart in `onResume()`.

### LEAK-02: `KeyManager` Listeners Not Fully Cancelled  
**File:** `MainActivity.kt` line 3694  
**Severity:** 🟡 LOW  
**Description:** `KeyManager.getInstance().cancelListen(this)` cancels listeners registered with `this` as the holder. However, `PayloadDetectionManager` registers listeners with itself (`this` = the object) at line 85 in its own file. These are never cancelled because `PayloadDetectionManager.cleanup()` is never called from `onDestroy()`.  
**Fix:** Call `PayloadDetectionManager.cleanup()` in `MainActivity.onDestroy()`.

### LEAK-03: `FileOutputStream` Not Closed on Timeout  
**File:** `WebODMAutoUpload.kt` lines 169–177  
**Severity:** 🟡 LOW  
**Description:** When `downloadLatch.await(90, SECONDS)` returns `false` (timeout), `fos.close()` is called, but if the DJI SDK callback fires *after* the timeout and tries `fos.write(data)`, it will throw an `IOException` on a closed stream. The DJI SDK's internal background queue is not cancelled.  
**Fix:** Use an `AtomicBoolean` flag to tell the callback to skip writes after timeout.

---

## SUMMARY TABLE

| ID | Category | Severity | File | Description |
|----|----------|----------|------|-------------|
| CRASH-01 | Force Close | 🔴 CRITICAL | MainActivity.kt:234 | `lateinit` views accessed before `findViewById` |
| CRASH-02 | Force Close | 🔴 CRITICAL | MainActivity.kt:2312 | `KeyManager` used without SDK readiness check |
| CRASH-03 | Force Close | 🔴 CRITICAL | MainActivity.kt:2672 | `cameraStreamManager` NPE |
| CRASH-04 | ANR/Kill | 🟡 HIGH | ObstacleRadarView.kt:66 | Infinite GPU invalidation loop |
| CRASH-05 | OOM Crash | 🟡 HIGH | MqttService.kt:21 | Unbounded thread spawning |
| LOGIC-01 | Data Integrity | 🟠 MEDIUM | MainActivity.kt:331 | Hardcoded phantom drone ID |
| LOGIC-02 | Memory Leak | 🟠 MEDIUM | App.jsx:396 | Unstoppable setInterval |
| LOGIC-03 | Render Bug | 🟠 MEDIUM | App.jsx:598 | Flight trail wrong coords |
| LOGIC-04 | Filter Bypass | 🟠 MEDIUM | WebODMAutoUpload.kt:91 | Reflection-based timestamp |
| LOGIC-05 | Data Corrupt | 🟠 MEDIUM | WebODMAutoUpload.kt:148 | Out-of-order chunk writes |
| LOGIC-06 | Data Loss | 🟡 LOW | MainActivity.kt:1934 | Wrong stream close order |
| SEC-01 | Security | 🔴 CRITICAL | MqttService.kt:35 | Hardcoded MQTT credentials |
| SEC-02 | Security | 🟠 MEDIUM | AndroidManifest.xml:46 | Exposed DJI API key |
| SEC-03 | Security | 🔴 CRITICAL | mosquitto.conf:17 | Anonymous MQTT access |
| SEC-04 | Security | 🟠 MEDIUM | server.js:82 | No API authentication |
| SEC-05 | Security | 🟡 LOW | AndroidManifest.xml:37 | Cleartext traffic allowed |
| PERF-01 | Performance | 🟡 HIGH | server.js:71 | Unbatched DB writes |
| PERF-02 | Performance | 🟡 HIGH | MainActivity.kt:2325 | 10Hz full telemetry publish |
| PERF-03 | Performance | 🟠 MEDIUM | App.jsx:160 | React state thrashing |
| PERF-04 | Performance | 🟠 MEDIUM | App.jsx:545 | ImageryProvider recreated |
| LEAK-01 | Resource Leak | 🟠 MEDIUM | MainActivity.kt:326 | Timer runs in background |
| LEAK-02 | Resource Leak | 🟡 LOW | PayloadDetectionManager | Listeners not cancelled |
| LEAK-03 | Resource Leak | 🟡 LOW | WebODMAutoUpload.kt:169 | Stream write after close |

**Total Defects Found:** 23  
**Critical (likely causes Force Close):** 5  
**High:** 4  
**Medium:** 10  
**Low:** 4
