# Dual-Mode Mapping & Cloud KMZ Execution Plan

This document outlines the architecture for supporting two distinct photogrammetry workflows (Quick vs. Professional) within the Recreate2 Tactical Drone system, fully controllable via MQTT and executing native DJI KMZ files.

## 1. Core Concepts

### A. Professional Mapping Mode (Hard-Capture)
*   **Purpose:** High-precision surveying, construction mapping, and measurement.
*   **Mechanism:** Drone triggers hardware camera. 20MP original images are saved to the Drone's SD Card.
*   **WebODM Flow:** Post-flight, the Android tablet connects to the drone, downloads the 20MP images via USB/WiFi (`MediaDataCenter`), and batches the upload to WebODM.
*   **Quality:** Highest (GSD ~1cm/px), but requires post-flight time to download massive files.

### B. Quick Mapping Mode (Soft-Capture / Proxy Mapping)
*   **Purpose:** Rapid situational awareness, SAR (Search & Rescue), tactical overviews.
*   **Mechanism:** The Android tablet "screenshots" the live FPV video stream (720p/1080p). It instantly injects the drone's real-time GPS coordinates (Lat/Lon/Alt) into the JPEG's EXIF metadata.
*   **WebODM Flow:** The Android tablet uploads these lightweight images to WebODM *instantly during the flight* via 4G/LTE.
*   **Quality:** Low (GSD ~10cm/px), but WebODM map generation can begin the second the drone lands. No SD Card required.

---

## 2. MQTT Command Architecture

The Tactical Server (Dashboard) will send an MQTT command to the drone to initiate a mission. The payload will now include the desired `mappingMode` and an S3 URL pointing to the KMZ file.

**Topic:** `dji-sdk/fleet/{drone_id}/command`

**Payload Example:**
```json
{
  "command": "EXECUTE_KMZ",
  "drone_id": "mavic_3e_alpha",
  "mappingMode": "QUICK", // or "PROFESSIONAL"
  "kmzUrl": "https://s3.aws.com/tactical-missions/grid_alpha_01.kmz",
  "timestamp": 1715421000
}
```

---

## 3. Android Implementation Steps

### Phase 1: MQTT & KMZ Downloader
1.  **Modify `MqttService.kt`**: Add a parser for the `EXECUTE_KMZ` command.
2.  **S3 Downloader**: Create a helper function using `OkHttp` to download the `.kmz` file from `kmzUrl` and save it to the Android device's `cacheDir`.
3.  **KMZ Execution**: Push the downloaded KMZ file directly into the DJI `WaypointMissionManager` and call `startMission()`.
4.  **State Management**: Store the current `mappingMode` in a global state so the camera listener knows which capture method to use.

### Phase 2: Building `QuickMappingEngine.kt`
1.  **Frame Interceptor**: Hook into DJI MSDK V5 `CameraStreamManager` to add a frame listener.
2.  **Action Trigger**: When the drone reaches a waypoint and fires a "Take Photo" action, intercept this event.
3.  **Bitmap Capture**: Convert the current YUV video frame into a JPEG `Bitmap`.
4.  **EXIF Injection**: Use Android's `androidx.exifinterface.media.ExifInterface` to write the drone's current `Latitude`, `Longitude`, and `Altitude` into the JPEG header.
5.  **Instant Upload**: Immediately pass this lightweight JPEG to the existing `createWebOdmTask` OkHttp function.

### Phase 3: Adapting `WebODMAutoUpload.kt`
1.  Ensure the existing `WebODMAutoUpload.kt` logic (which downloads from the SD Card) **ONLY** triggers if the mission's `mappingMode` was set to `PROFESSIONAL`.
2.  If the mode was `QUICK`, bypass the SD card download entirely, as the photos were already sent mid-flight.

---

## 4. Dashboard (Frontend) Updates
1.  Update `App.jsx` in the React frontend.
2.  In the "Upload Mission" panel, add a Dropdown/Radio Button: **Mode: [ Quick Scan ] / [ Professional Survey ]**.
3.  When clicking "Generate KMZ & Upload", the dashboard must upload the `.kmz` to an S3 bucket (or local Express server static folder) to get a public URL.
4.  Publish the `EXECUTE_KMZ` JSON payload to the MQTT broker.

## 5. Next Actions
To begin this implementation, we will start by modifying `MqttService.kt` and `MainActivity.kt` in the Android App to parse the new MQTT JSON payload and successfully download a KMZ file from a URL.
