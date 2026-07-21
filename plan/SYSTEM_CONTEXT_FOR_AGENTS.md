# TACTICAL C2 & MAPPING PLATFORM - AGENT CONTEXT
> **Purpose**: This document serves as the ground truth for any AI agent interacting with this codebase. It details the capabilities, algorithms, architectural flows, and specific class responsibilities to prevent agents from breaking established patterns.

## 1. System Architecture & Project Locations

The system is an air-gapped Tactical Command & Control (C2) and Photogrammetry mapping platform designed for DJI Enterprise drones (using MSDK V5).

### Core Project Paths
*   **Absolute Project Root**: `c:\Users\avare\Documents\recreate2\`
*   **Documentation & Plans**: `c:\Users\avare\Documents\recreate2\plan\` (Contains all architecture plans, UI specs, API docs, and this context file).

### Key Files & Sample Code Locations
*   **Android App (DJI MSDK V5)**: 
    *   *Main Flight Engine & UI*: `app\src\main\java\com\dji\recreate2\MainActivity.kt`
    *   *MQTT/Kafka Telemetry Bridge*: `app\src\main\java\com\dji\recreate2\MqttService.kt`
    *   *Main UI Layout*: `app\src\main\res\layout\activity_main.xml`
*   **Tactical Server (Node.js/Kafka)**: 
    *   *Backend API & Messaging*: `tactical_server\backend\server.js`
    *   *Docker Infrastructure*: `tactical_server\docker-compose.yml`
*   **Web Dashboard (React/Leaflet)**: 
    *   *Frontend Core*: `tactical_server\frontend\src\App.jsx`

---

## 2. Core Capabilities & Feature Sets

### A. Flight Engines
We employ two distinct flight engines in Android (`MainActivity.kt`) depending on the use-case:

1.  **Virtual Stick Engine (`executeTacticalMission`)**:
    *   *Purpose*: Dynamic, tactical, and cinematic flights (waypoints, orbits, tracking).
    *   *Mechanism*: Runs a 10Hz loop sending `VirtualStickFlightControlParam` (Pitch, Roll, Yaw, Vertical Throttle) calculated via Haversine distance and bearing math.
    *   *Agent Rule*: Never block the main thread. Virtual stick commands must be sent continuously or the drone will hover in place.
2.  **Native WaypointMission API (KMZ) [Planned]**:
    *   *Purpose*: High-precision photogrammetry (DroneDeploy style grid mapping).
    *   *Mechanism*: Generates WPML/KMZ files and uploads them to the hardware flight controller for exact camera triggering and perfect line spacing.

### B. Map Interaction (`MapInteractionType`)
The OSMDroid map in Android has stateful interaction modes:
*   `MOVE`: Tapping sets a single waypoint and drone flies to it immediately.
*   `WAYPOINT`: Tapping drops sequential waypoints for a multi-point route.
*   `POI`: Tapping locks the gimbal to a specific coordinate.
*   `SHAPE`: Tapping drops polygon vertices for mapping area definition.

### C. Backend Messaging Pipeline
*   **Telemetry**: Drone -> `MqttService.kt` -> Mosquitto Broker -> Node.js (`server.js`) -> PostgreSQL + Web UI (Socket.io).
*   **Commands**: Web UI (React `fetch`) -> Node.js API -> Kafka Topic -> Kafka Consumer Bridge -> MQTT Broker -> Android `MainActivity.kt` JSON Parser.
*   *Agent Rule*: Always format commands as JSON with `{ "command": "...", "url": "..." }` or `{ "action": "..." }` to maintain compatibility with `App.jsx` and `MainActivity.kt`.

### D. RTMP Livestreaming
*   Triggered via C2 `START_LIVESTREAM` command.
*   Uses DJI `LiveStreamManager` (requires `dji-sdk-v5-networkImp` in Gradle).
*   Bypasses WebRTC on Android; pumps hardware-encoded H.264 directly to the remote RTMP server (e.g., `rtmp://rtc.blackeye.id:1936/...`).

---

## 3. Algorithms & Mathematical Functions

### A. Grid Generation (Lawnmower Algorithm) [WIP / Planned]
The application transforms a user-drawn `Polygon` into a flight path `Polyline`.
*   **Inputs**: `shapePoints` (List of GeoPoints), Altitude (m), Front Overlap (%), Side Overlap (%), Lens FOV (Horizontal/Vertical degrees).
*   **GSD (Ground Sample Distance) Math**: 
    *   `GSD = (Altitude * SensorPixelSize) / FocalLength`
*   **Overlap Math**: 
    *   `Distance Between Lines (m) = (1 - SideOverlap) * SwathWidth`
    *   `Trigger Interval (m) = (1 - FrontOverlap) * ImageHeightFootprint`
*   **Raycasting**: Finding the intersection of generated parallel lines against the polygon edges to clip the flight path strictly inside the user's boundary.

### B. Navigation Math (`VirtualStick`)
*   `calculateDistance(lat1, lon1, lat2, lon2)`: Haversine formula to get meters to target.
*   `calculateBearing(lat1, lon1, lat2, lon2)`: To rotate the drone's nose towards the target coordinate.
*   `calculatePitchAngle(distance, speed)`: Maps desired m/s speed to drone pitch angle (max 15 degrees) using `FlightCoordinateSystem.BODY`.

---

## 4. Advanced Future Architectures (DroneLink/Digital Twin)

If implementing advanced features, adhere to these paradigms:
1.  **Component-Based Missions**: Do not hardcode a single mission type. Missions should be an array of `MissionComponent` interfaces (`GridComponent`, `OrbitComponent`, `PanoComponent`) executed sequentially.
2.  **Two-Pass Digital Twin**: 
    *   Pass 1: High altitude coarse scan -> Server generates DSM.
    *   Pass 2: Server calculates 3D bounding boxes and generates precise `Orbit` facade scans -> sends back to drone.
3.  **Resumable State Machine**: The Android app must write `lastCompletedWaypointIndex` to SharedPreferences so battery swaps can auto-resume missions safely.

---
**End of Context Document.**
