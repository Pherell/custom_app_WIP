# Tactical Drone Platform - Code Defect Audit Report

**Date:** 2026-07-15
**Target:** `recreate2` tactical drone interface and backend C2 server.
**Type:** Defect Analysis & System Impact Assessment

---

## 1. Client-Side Browser Memory & CPU Leak (React Frontend)
**Location:** `tactical_server/frontend/src/App.jsx`
**Defect:** The `startSimulation` function initiates a `setInterval` that runs at 2Hz (every 500ms) to mock telemetry data. However, the interval ID is never stored, and there is no mechanism to clear it. 
**System Effect:** If a user clicks the "START 3D SIMULATION" button multiple times, it spawns orphaned, overlapping simulation loops. This will quickly overwhelm the browser's JavaScript engine, leading to 100% CPU utilization on a single core, UI unresponsiveness, browser tab freezing, and potentially crashing the user's computer if left running. Additionally, high-frequency React state updates (`setFleet`, `setFlightTrails`) at 10Hz per drone without debouncing/throttling will cause severe React render thrashing.
**Remediation:** 
*   Store the interval ID in a `useRef` and clear it before starting a new one.
*   Implement state batching or throttle the UI render frequency for high-frequency telemetry.

## 2. Database Connection Pool Exhaustion (Node.js Backend)
**Location:** `tactical_server/backend/server.js`
**Defect:** The MQTT `message` event handler processes incoming telemetry at 10Hz (10 times per second) per drone. For each message, it instantly executes an unbatched `pool.query('INSERT INTO telemetry...')`.
**System Effect:** When scaling to multiple drones (e.g., 5 drones = 50 queries/sec), this unthrottled asynchronous database insertion pattern will rapidly exhaust the default PostgreSQL connection pool. This causes high CPU load, disk I/O bottlenecks, database locking, and eventual `TimeoutError` exceptions that can crash the Node.js backend container.
**Remediation:** 
*   Implement a caching layer or batching mechanism. Store telemetry in an array and use a bulk insert (e.g., `INSERT ... VALUES (), (), ()`) every 1-2 seconds.
*   Alternatively, use Redis for real-time telemetry and Postgres only for periodic historical persistence.

## 3. Thread Spawning Race Condition (Android Kotlin)
**Location:** `app/src/main/java/com/dji/recreate2/MqttService.kt`
**Defect:** The `connect()` method creates an unmanaged `Thread { ... }.start()`. While it attempts to disconnect an existing client (`if (mqttClient != null && mqttClient!!.isConnected)`), doing this across unmanaged threads introduces a race condition. 
**System Effect:** If the Android system suffers a network drop and attempts to reconnect rapidly, or if the user toggles settings quickly, the app could spawn multiple zombie MQTT connection threads. This causes memory leaks on the Android device, battery drain, and redundant MQTT connections bouncing on the Mosquitto broker (which force-disconnects overlapping client IDs).
**Remediation:**
*   Replace raw `Thread` usage with Kotlin Coroutines (`Dispatchers.IO`) or a managed `ExecutorService`.
*   Add a locking mechanism (e.g., `Mutex` or `synchronized`) to ensure only one connection routine executes at a time.

## 4. Unclosed Threaded Network Operations (Android Kotlin)
**Location:** `app/src/main/java/com/dji/recreate2/WebODMAutoUpload.kt`
**Defect:** When downloading media from the drone, the process waits via `downloadLatch.await(90, TimeUnit.SECONDS)`. If a timeout occurs, the code breaks out of the loop and sets `isDownloading = false`. However, it proceeds to call `createWebOdmTask(context, downloadedFiles)` even if the operation partially failed or timed out.
**System Effect:** The WebODM server may receive an incomplete task, triggering server-side photogrammetry errors. Furthermore, the drone SDK might still have pending media tasks in its hardware queue that aren't properly flushed, potentially locking up the DJI camera hardware until rebooted.
**Remediation:**
*   Halt the upload process entirely if critical failures occur during download, or add strict validation to only upload intact, verified files.
*   Explicitly cancel the `MediaManager` task queue if a timeout is encountered.

## 5. Security & Authentication Vulnerabilities
**Location:** `tactical_server/config/mosquitto.conf` & `server.js`
**Defect:** `mosquitto.conf` explicitly allows anonymous connections (`allow_anonymous true`). Furthermore, the Node.js Express server does not validate the Origin or enforce authentication on the API routes or the Socket.io WebSocket connections.
**System Effect:** Any machine on the local network (or internet, if exposed) can connect to the MQTT broker and WebSocket server. A malicious actor could easily intercept live drone video feeds, track GPS telemetry, or inject `EXECUTE_MISSION` / `RTH` payloads into the `tactical/fleet/+/command` topic to hijack the drone fleet.
**Remediation:**
*   Set `allow_anonymous false` in Mosquitto and enforce ACLs (Access Control Lists).
*   Add JWT/Token authentication to the Express API and Socket.io handshakes.

---
**Audit Status:** Review Completed. Critical issues found in backend DB scaling and frontend memory management.
