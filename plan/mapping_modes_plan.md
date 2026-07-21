# Dual-Mode Mapping & MQTT Integration Plan

## Overview
The goal is to implement a Dual-Mode mapping system for WebODM, controllable via MQTT, and support KMZ waypoint uploading over MQTT.

## 1. Mapping Modes

### Mode A: Professional Mapping (Existing)
*   **Mechanism:** Uses the drone's physical camera to capture high-resolution native photos. The photos are saved to the drone's SD card.
*   **EXIF Data:** The drone automatically writes accurate GPS EXIF data into the JPEG files.
*   **WebODM Sync:** `WebODMAutoUpload` pulls the files from the drone via `MediaManager` and pushes them to the WebODM API.
*   **Pros:** Highest quality, accurate RTK/GPS EXIF.
*   **Cons:** Slower transfer rate due to pulling large files from the drone over the controller link.

### Mode B: Quick Mapping (New)
*   **Mechanism:** Uses Android's `TextureView` (FPV Surface) to capture a screenshot of the live video feed directly on the Android device.
*   **EXIF Injection:** At the exact moment the screenshot is taken, the app fetches the drone's current coordinates (Lat, Lon, Alt) and writes them into the EXIF headers of the screenshot JPEG using Android's `ExifInterface`.
*   **WebODM Sync:** The screenshots are already on the Android device, so they can be immediately uploaded to WebODM without waiting for a drone-to-controller transfer.
*   **Pros:** Extremely fast (useful for rapid tactical intelligence).
*   **Cons:** Lower resolution (limited to video feed quality, e.g., 720p/1080p), slightly less precise GPS timing.

## 2. MQTT Integration

We will extend `MqttService` to handle new command payloads.

### Mapping Mode Selection
*   **Topic:** `recreate2/cmd/mapping_mode`
*   **Payload Example:** `{"mode": "QUICK"}` or `{"mode": "PROFESSIONAL"}`
*   **Action:** Updates a global variable `activeMappingMode`. When a mapping grid is executed, the drone will either trigger the hardware camera (Professional) or trigger Android screenshots (Quick).

### KMZ Upload via MQTT
*   **Topic:** `recreate2/cmd/upload_kmz`
*   **Payload Example:** 
    ```json
    {
      "filename": "mission1.kmz",
      "data_base64": "UEsDBBQAAAAIA..." 
    }
    ```
*   **Action:** 
    1. The app decodes the base64 payload and saves it as a `.kmz` file in the device's local storage.
    2. The app uses `WPMZParser` (or OSMDroid KML parser) to extract the waypoints.
    3. The waypoints are loaded into `tacticalWaypoints` and drawn on the map.
    4. The mission can then be executed via `btnExecuteMission`.

## 3. Implementation Steps

1.  **ExifInterface Integration:** Add the logic in `MainActivity.kt` to capture FPV frames to bitmaps, save as JPEG, and write `ExifInterface` data (Lat, Lon, Alt).
2.  **Grid Execution Modification:** Modify `commitGridMission()` so it adapts the waypoint actions based on `activeMappingMode`.
3.  **MQTT Parser Update:** Update `MqttService.kt` to listen to the new topics and parse Base64 KMZ files.
4.  **UI Updates:** Add a toggle in the Mapping Settings panel (XML) to manually switch between QUICK and PRO modes, in addition to MQTT.
