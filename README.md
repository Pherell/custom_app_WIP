# Recreate2: Tactical Drone Interface

**Recreate2** is a professional-grade, Android-based tactical mission control application built on the DJI Mobile SDK (v5). It transforms standard commercial DJI drones (e.g., Mavic 3 Enterprise, Mini series) into tactical edge nodes capable of advanced waypoint mission execution, dynamic gimbal tracking, and real-time MQTT-based command and control (C2) without relying on DJI's proprietary cloud infrastructure.

---

## 🎯 Core Features

### 1. Advanced Waypoint Engine (Virtual Stick)
Bypasses standard DJI waypoint limitations by executing missions dynamically via the Virtual Stick engine.
*   **Dynamic Gimbal Locking (POI):** Set a Point of Interest (POI) on the map. The drone calculates relative bearing and altitude differences in real-time, locking the gimbal onto the target while flying completely unrelated trajectories.
*   **Camera Actions:** Waypoints can be assigned specific tasks: `Just Fly`, `Capture Photo` (stops, aims at POI, shoots, resumes), `Start Record`, `Stop Record`, `Lock Gimbal (Surveillance)`, and `Unlock Gimbal`.
*   **Smooth Navigation:** Proportional yaw and pitch math prevent jerky movements during trajectory changes.

### 2. Tactical Head-Up Display (HUD)
A dark-mode, military-style FPV overlay designed for maximum situational awareness.
*   **AR Home Point:** Projects the drone's home coordinate dynamically onto the live video feed (FPV) taking camera FOV, drone altitude, and gimbal pitch into account.
*   **Obstacle Radar:** An on-screen UI widget mapping proximity sensor data in 360 degrees.
*   **Critical Alerts:** Blinking red warnings for Thermal, Battery, Signal Loss, and Obstacle Proximity.

### 3. Edge Server Integration (MQTT)
Built for air-gapped or private enterprise networks, the app acts as a local bridge between the drone and a secure open-source backend.
*   **Live Telemetry Broadcast:** Broadcasts high-frequency GPS, battery, and kinematics data via Eclipse Paho MQTT.
*   **Remote C2:** Subscribes to command topics, allowing remote commanders to inject waypoints, trigger Return-to-Home (RTH), or execute missions remotely.

---

## 🏗️ Technical Architecture

*   **Language:** Kotlin
*   **SDK:** DJI Mobile SDK (MSDK) v5
*   **Mapping:** OSMDroid (OpenStreetMap)
*   **Networking:** Eclipse Paho MQTT v3
*   **Minimum OS:** Android 10 (API 29)

---

## 📡 MQTT Protocol Specification

The app communicates with an external MQTT broker. The server address can be configured manually via the **CFG Tab** in the Advanced System Menu (Double-click the Drone Logo to access).

### 1. Telemetry Broadcast
The app publishes live data (approx 10Hz) to the server.
*   **Topic:** `tactical/fleet/{drone_id}/telemetry`
*   **Payload (JSON):**
```json
{
  "id": "drone_alpha_01",
  "timestamp": 1715432109000,
  "location": {
    "lat": 34.0522,
    "lon": -118.2437,
    "alt": 120.5
  },
  "kinematics": {
    "heading": 275.4,
    "speed": 12.4
  }
}
```

### 2. Command & Control (C2)
The app listens for incoming commands from the server to alter its mission state.
*   **Topic:** `tactical/fleet/{drone_id}/command`
*   **Payload (JSON):**

**Add Waypoint:**
```json
{
  "command": "ADD_WAYPOINT",
  "lat": 34.0531,
  "lon": -118.2450,
  "alt": 50.0
}
```

**Execute Mission:**
```json
{
  "command": "EXECUTE_MISSION"
}
```

**Trigger RTH:**
```json
{
  "command": "RTH"
}
```

---

## 🎮 User Interface Controls

### Flight Modes
Located in the bottom right corner (Green Outline Buttons).
*   **CAM:** Disables map interaction, optimizes screen for pure FPV video.
*   **FLY:** Hybrid mode. Map shrinks to a minimap in the bottom left. Tapping the minimap expands it.
*   **MAP:** Full-screen tactical map for mission planning.

### Map Editing (RTS Style Unit Selection)
You interact with the drone exactly like a unit in a Real-Time Strategy game (e.g., Starcraft). Tap the actual drone marker on the 2D map to issue orders.
*   **Single-Click (Drone Marker):** Toggles `UNIT SELECTED: COMMAND MODE`. When ON, tapping anywhere on the map drops standard waypoints for the drone to fly to. Tapping the drone again deselects it.
*   **Double-Click (Drone Marker):** Opens the **Advanced System Menu** (Logs, Settings, MQTT config, Radar Config).
*   **POI Targeting:** If a waypoint action requires a target (like Capture Photo or Surveillance), the app will prompt you to tap the map to set the camera target coordinate.

### Waypoint Action Menu
Tap any placed waypoint marker on the map to open the Action Menu.
*   **JUST FLY:** No action.
*   **LOCK GIMBAL TO POI (SURVEILLANCE):** Locks the gimbal to a specific coordinate on the ground without triggering a recording. Excellent for silent overwatch. App prompts you to tap the map to set a POI target.
*   **UNLOCK GIMBAL:** Releases the POI lock and resets the camera forward.
*   **CAPTURE PHOTO:** Assigns a photo action. App prompts you to tap the map to set a POI target.
*   **START RECORD:** Starts video recording upon arrival. App prompts you to tap the map for persistent gimbal POI lock.
*   **STOP RECORD:** Stops recording and releases gimbal lock.

---

## 🚀 Getting Started

1. **Prerequisites:** 
   * A DJI Developer Account and App Key.
   * A compatible DJI drone (e.g., Mavic 3 Enterprise).
2. **Build:** 
   * Clone the repository.
   * Insert your DJI App Key into `AndroidManifest.xml`.
   * Build via Android Studio.
3. **Run:**
   * Connect the Android device to the DJI Remote Controller via USB.
   * Accept the USB debugging prompt.
   * Wait for the "DJI SDK Registration Success" toast message before flying.
