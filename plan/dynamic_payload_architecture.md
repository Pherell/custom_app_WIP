# Dynamic Payload Architecture Plan
**Module:** `DynamicPayloadManager` & Adaptive UI
**Objective:** Create a unified Android C2 interface that gracefully scales from standard consumer drones (single camera) to advanced enterprise platforms (Multi-lens, Thermal, Laser Range Finder) without requiring hardcoded logic.

---

## Phase 1: Hardware Interrogation Engine
We will build a `PayloadDetectionService` that queries the DJI MSDK V5 `KeyManager` the moment a drone connects to determine its capabilities.

### 1. Product & Lens Detection
*   **Determine Drone Tier**: Listen to `ProductKey.KeyProductType`. (e.g., If it returns `DJI_MAVIC_3_ENTERPRISE_SERIES`, activate the Enterprise workflow).
*   **Query Camera Lenses**: Ask the `KeyManager` for `CameraKey.KeyCameraLensType` or the available `CameraVideoStreamType` list.
    *   *Output*: An array of available lenses `[WIDE, ZOOM, INFRARED_THERMAL]` or just `[WIDE]` for consumer drones.
*   **Determine Stream Capabilities**: Check if the drone supports dual-channel decoding (Primary & Secondary video streams).

### 2. Laser Range Finder (LRF) Detection
*   Query if the payload supports LRF by checking if `CameraKey.KeyLaserMeasureEnable` or `CameraKey.KeyLaserMeasureInformation` is a supported key.
*   If `isSupported == true`, we flip a global state `isLrfAvailable = true`.

---

## Phase 2: Adaptive Glassmorphism UI
The `MainActivity` layout will dynamically inflate or hide UI components based on the Hardware Interrogation results.

### 1. Consumer Drone Layout (Base Mode)
*   **Video**: The `fpvSurface` takes up 100% of the screen.
*   **Hidden Elements**: PiP window, Lens Toggle switches, and LRF Reticle are set to `View.GONE`.

### 2. Enterprise Drone Layout (Tactical Mode)
*   **Lens Switcher**: Inflate a Glassmorphism floating toggle bar (e.g., `[ WIDE | ZOOM | IR ]`). Tapping a button instantly calls `VideoStreamManager.putCameraStreamSurface()` to swap the active stream on `fpvSurface`.
*   **Picture-in-Picture (PiP)**: If the user wants to see Thermal and Zoom at the same time, they can toggle PiP mode. We will overlay a secondary `SurfaceView` and route the IR stream to the PiP and WIDE to the background.

---

## Phase 3: Laser Designator Implementation (LRF)
For enterprise drones (like the M30T or M3T) equipped with a Laser Range Finder, we will turn the drone into an aerial target designator.

### 1. UI Integration
*   Display a tactical crosshair/reticle in the absolute center of the FPV view.
*   Add a "LASER ON" toggle button.

### 2. Data Extraction
*   When activated, send the `KeyLaserMeasureEnable` command to turn on the laser.
*   Listen to `CameraKey.KeyLaserMeasureInformation`. This returns real-time data:
    *   Distance to target (meters)
    *   Target absolute Latitude & Longitude
    *   Target Altitude

### 3. Tactical MQTT Broadcast
*   Extract the LRF Target Coordinates.
*   Publish them via `MqttService` to `avarell/fleet/{drone_id}/telemetry/lrf`.
*   **Result**: If the drone points its crosshair at an enemy position or a vehicle, the exact GPS coordinates of that vehicle instantly appear on the Node.js Web Dashboard for ground troops to see in real-time.

---

## Execution Steps
1.  **Create `PayloadDetectionManager.kt`**: A singleton class to house all the hardware polling logic.
2.  **Update `activity_main.xml`**: Add the hidden UI elements (PiP SurfaceView, Lens Switcher RadioGroup, LRF Reticle).
3.  **Refactor `MainActivity.kt`**: Wire the `PayloadDetectionManager` to update the UI visibility states upon connection.
4.  **Implement LRF MQTT Broadcast**: Add the payload schema to `SERVER_API_DOCS.md` and implement the publisher in Android.
