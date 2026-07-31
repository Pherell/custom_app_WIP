# Recreate2: Project Context & Knowledge Base Memory

> **Document Purpose**: Comprehensive memory snapshot capturing workspace architecture, codebase structure, local SDK references, safety rules, and technical concepts discussed in this session.

---

## 1. 🏗️ Workspace Architecture & Technical Stack

### Core Android Application (`app/`)
* **Language & SDK**: Kotlin, **DJI Mobile SDK V5 (MSDK v5.18.0)**, Minimum API Level 29 (Android 10+).
* **Mapping**: OSMDroid (OpenStreetMap) with offline raster/vector tile caching fallback.
* **Networking & Protocols**: Eclipse Paho MQTT v3 for low-latency command and telemetry transport; OkHttp3 for WebODM photogrammetry synchronization.
* **Credential Security**: Local configuration encrypted via `EncryptedSharedPreferences` (AES256-GCM / MasterKey).

#### Key Source Files
- **[MainActivity.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/MainActivity.kt)**: Core Activity (~6,000 LOC). Manages tactical HUD overlay (AR Home Point, obstacle warnings, telemetry metrics), RTS-style single/double-tap drone unit selection, flight mode switching (`CAM`, `FLY`, `MAP`), and the Virtual Stick flight loop (`executeTacticalMission` at 10 Hz) with dynamic POI camera tracking math.
- **[ARLandingOverlayView.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ARLandingOverlayView.kt)**: Custom AR HUD overlay component. Computes 3D camera projection math to render target bounding boxes, targeting crosshairs, altitude/distance telemetry, and rotating heading alignment indicators over the live FPV video feed.
- **[ARVisionLandingManager.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ARVisionLandingManager.kt)**: Vision-guided landing controller. Connects OpenCV / ArUco marker pose estimation ($\Delta X, \Delta Y, \Delta Z, \Delta \psi$) to closed-loop Virtual Stick PID velocity commands ($V_x, V_y, V_z, \text{Yaw Rate}$) for autonomous touchdown.
- **[MqttService.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/MqttService.kt)**: Thread-safe Paho MQTT client managing connection lifecycle, 10 Hz telemetry publishing (`dji-sdk/fleet/{clientId}/telemetry`), mission log updates, and command subscriptions (`dji-sdk/fleet/{clientId}/command`, broadcast, config). Offloads incoming messages to an asynchronous single-thread executor.
- **[PayloadDetectionManager.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/PayloadDetectionManager.kt)**: Auto-detects connected aircraft models (Mavic 3 Enterprise, Matrice 30 series), available camera lenses (Wide, Zoom, Thermal IR), and Laser Range Finder (LRF) capabilities using reflection caching for zero frame-drop overhead.
- **[KmzGenerator.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/KmzGenerator.kt)**: Generates and packages native DJI Waypoint Markup Language (`template.kml` + `waylines.wpml` zipped as `.kmz`) for execution via DJI `WaypointMissionManager`.
- **[WebODMAutoUpload.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/WebODMAutoUpload.kt)**: Fetches mission photos directly from aircraft SD card storage via `MediaDataCenter` and uploads compressed frames to a WebODM server for automated 2D/3D map processing.
- **Custom Views**:
  - **[ObstacleRadarView.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ObstacleRadarView.kt)**: Renders a 360-degree polar radar displaying proximity sensor distance buffers from `PerceptionManager`.
  - **[CompassView.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/CompassView.kt)**: Custom compass widget displaying true north aircraft orientation.
  - **[OnScreenJoystick.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/virtualstick/OnScreenJoystick.kt)**: Touch joystick widget for manual Virtual Stick control overrides.

---

### Tactical Server Stack (`tactical_server/`)
- **[docker-compose.yml](file:///c:/Users/avare/Documents/recreate2/tactical_server/docker-compose.yml)**: Containerized backend infrastructure:
  - **Mosquitto MQTT Broker**: Central communication bus (ports 1883 / 9001).
  - **Map Server (`tileserver-gl`)**: Serves offline map tiles for air-gapped tactical networks.
  - **PostgreSQL Database**: Time-series telemetry storage with `(drone_id, timestamp)` indexing.
  - **Node.js Express Backend (`server.js`)**: Bridges MQTT telemetry feeds into PostgreSQL and broadcasts updates over WebSockets (Socket.io).
  - **Python KMZ Hub (`kmz_hub/kmz_hub.py`)**: FastAPI service for KMZ payload generation and validation.
  - **React Command Center (`frontend/`)**: Web dashboard for multi-drone tracking and mission control.

---

## 2. 📚 Local SDK & Documentation References

- **Local MSDK V5 HTML Documentation**:
  - Path: [Components](file:///C:/Users/avare/Documents/Mobile-SDK-Android-V5-dev-sdk-main/Docs/Android_API/en/Components)
- **Local MSDK V5 Official Sample Code**:
  - Path: [aircraft](file:///C:/Users/avare/Documents/Mobile-SDK-Android-V5-dev-sdk-main/SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft)
- **Agent Skill & Rule Files**:
  - MSDK V5 Technical Skill: [SKILL.md](file:///c:/Users/avare/Documents/recreate2/.agents/skills/dji_msdk_v5_docs/SKILL.md)
  - Android Build Skill: [SKILL.md](file:///c:/Users/avare/Documents/recreate2/.agents/skills/android_build_verify/SKILL.md)
  - Core Documentation Specs: [SERVER_API_DOCS.md](file:///c:/Users/avare/Documents/recreate2/SERVER_API_DOCS.md) and [README.md](file:///c:/Users/avare/Documents/recreate2/README.md)

---

## 3. 🎯 Precision Landing Targets & Encoded QR Payloads

### A. Physical Target Marker Design
![High-Contrast AR Landing Pad Target](C:\Users\avare\.gemini\antigravity-ide\brain\2efbe6c7-fffe-4d5c-8a82-734fbfc9d1e5\landing_pad_ar_marker_1785215014206.png)

### B. Precision Landing Encoded Data QR Code
![QR Code Encoding Precision Landing Payload Data]  

### C. Live FPV AR HUD Overlay
![Tactical FPV AR HUD Overlay](C:\Users\avare\.gemini\antigravity-ide\brain\2efbe6c7-fffe-4d5c-8a82-734fbfc9d1e5\ar_hud_landing_overlay_1785220283979.png)

### Data Schema Encoded Inside Landing QR Code
```json
{
  "type": "PRECISION_LANDING_PAD",
  "pad_id": "DOCK_ALPHA_01",
  "location": {
    "lat": -6.208800,
    "lon": 106.845600,
    "alt_msl": 15.2
  },
  "physical_specs": {
    "width_m": 0.80,
    "height_m": 0.80,
    "heading_deg": 45.0
  },
  "approach_rules": {
    "max_descent_mps": 0.5,
    "safety_radius_m": 2.5
  }
}
```

---

## 4. 🧠 Key Technical Topics Discussed

### A. Object Detection & Following (Vision Intelligence)
- **Built-in SDK Manager**: **[IntelligentFlightManager](file:///C:/Users/avare/Documents/Mobile-SDK-Android-V5-dev-sdk-main/Docs/Android_API/en/Components/IIntelligentFlightManager/IIntelligentFlightManager.html)**
- **Object Detection (Auto-Sensing)**: `addAutoSensingInfoListener` streams detected target bounding boxes (`DoubleRect`), target indexes, and types (`TargetType.RECT`, `TargetType.INDEX`).
- **ActiveTrack / SmartTrack**: `ISmartTrackMissionManager` locks aircraft flight and camera tracking onto a target.
- **Spotlight Mode**: `ISpotLightManager` locks gimbal orientation onto a moving target while preserving manual pilot flight control.
- **Sample Code Reference**: [IntelligentFlightVM.kt](file:///C:/Users/avare/Documents/Mobile-SDK-Android-V5-dev-sdk-main/SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/models/IntelligentFlightVM.kt)

### B. 360° Sensors vs. Video Feeds
- **360° Radar Data**: **[IPerceptionManager](file:///C:/Users/avare/Documents/Mobile-SDK-Android-V5-dev-sdk-main/Docs/Android_API/en/Components/IPerceptionManager/IPerceptionManager.html)** provides numerical 360-degree obstacle proximity vectors (Front, Back, Left, Right, Up, Down) for rendering HUD radar widgets.
- **Raw Sensor Video Feeds**: DJI does **not** expose raw optical video feeds of 360° obstacle-avoidance fisheye sensors to third-party SDK apps.
- **Supported Camera Streams**: **[ICameraStreamManager](file:///C:/Users/avare/Documents/Mobile-SDK-Android-V5-dev-sdk-main/Docs/Android_API/en/Components/IMediaDataCenter/ICameraStreamManager.html)** provides streams for primary payload cameras (Wide/Zoom/Thermal) and the dedicated pilot FPV camera.

### C. Precision Landing & AR Landing Targets
- **Native Precision Landing**: `PerceptionManager.getInstance().setPrecisionLandingEnabled(true, callback)` uses downward optical feature matching against takeoff ground snapshots for centimeter-level landing accuracy.
- **HUD AR Overlay**: Projects landing pad coordinates $(Lat, Lon, Alt)$ onto the FPV camera view by computing bearing, gimbal pitch, and camera FOV.
- **Moving Target Landing (Dynamic Pads)**:
  1. Live telemetry streaming (MQTT/UDP) updates target position & velocity vectors ($\vec{V}_{target}$).
  2. `SpotLightManager` visually locks the camera onto the moving pad.
  3. `IVirtualStickManager` matches horizontal velocity ($\vec{V}_{drone} = \vec{V}_{target}$) before initiating vertical descent.

### D. Safety Guardrails & Principles
- **No Kinetic Collisions or Crashes**: Commands or instructions to intentionally force aircraft crashes, kinetic collisions, or mid-air motor shutoffs are strictly prohibited.
- **Recommended Parachute & Safety Testing**:
  - Independent parachute hardware with dedicated IMU/barometers.
  - Testing within **DJI Assistant 2 Flight Simulator**.
  - Ground/bench verification of servo actuation and deployment mechanisms.

---

*Last Updated: 2026-07-31*
