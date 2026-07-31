# AR Vision-Guided Precision Landing Implementation Plan

> **Document Status**: READY FOR IMPLEMENTATION  
> **Target SDK**: DJI Mobile SDK V5 (MSDK v5.18.0)  
> **Architecture Layer**: Android Native (Kotlin + OpenCV Android SDK)  

---

## 1. Executive Summary

This document outlines the end-to-end technical implementation plan for adding **optical AR vision-guided precision landing** to the Recreate2 tactical drone application. The system captures raw video frames from the aircraft camera, detects ArUco / AprilTag fiducial landing markers using OpenCV, computes 3D spatial pose offsets ($\Delta X, \Delta Y, \Delta Z, \Delta \psi$), and drives closed-loop Virtual Stick flight controls ([ARVisionLandingManager.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ARVisionLandingManager.kt)) alongside real-time FPV HUD overlays ([ARLandingOverlayView.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ARLandingOverlayView.kt)).

---

## 2. Technical Architecture & Data Flow

```
┌────────────────────────────────────────────────────────────────────────┐
│                        ANDROID APP (Recreate2)                         │
│                                                                        │
│  ┌──────────────────────────┐      ┌────────────────────────────────┐  │
│  │ ICameraStreamManager     │      │ ArUcoDetectorHelper            │  │
│  │ (YUV420 Frame Listener)  ├─────►│ (OpenCV solvePnP 3D Pose)      │  │
│  └──────────────────────────┘      └───────────────┬────────────────┘  │
│                                                    │                   │
│                                 ┌──────────────────┴───────────────┐   │
│                                 │ 3D Offsets                       │   │
│                                 │ (ΔX, ΔY, ΔZ, Δψ, isAligned)      │   │
│                                 └─────────┬─────────────────┬──────┘   │
│                                           │                 │          │
│                                           ▼                 ▼          │
│  ┌──────────────────────────┐   ┌──────────────────┐  ┌─────────────┐ │
│  │ ARLandingOverlayView     │   │ ARVisionLanding  │  │ HUD Overlay │ │
│  │ (postInvalidate Render)  │   │ Manager          │  │ (Reticle &  │ │
│  └──────────────────────────┘   └─────────┬────────┘  │  Metrics)   │ │
│                                           │           └─────────────┘ │
│                                           ▼                           │
│                                 ┌──────────────────┐                  │
│                                 │ VirtualStick     │                  │
│                                 │ Manager (10 Hz)  │                  │
│                                 └─────────┬────────┘                  │
└───────────────────────────────────────────┼───────────────────────────┘
                                            ▼
                               ┌───────────────────────────┐
                               │   DJI Flight Controller   │
                               │   (Velocity Control Vx,Vy)│
                               └───────────────────────────┘
```

---

## 3. Step-by-Step Implementation Roadmap

### Phase 1: OpenCV Android SDK Dependency (`build.gradle.kts`)
- Add OpenCV Android SDK dependency (`org.opencv:opencv-android:4.8.0`) to `app/build.gradle.kts`.
- Initialize OpenCV native binaries inside `MainApplication.kt` / `MainActivity.kt`.

### Phase 2: Computer Vision Detector Helper (`ArUcoDetectorHelper.kt`)
- Create `ArUcoDetectorHelper.kt` to handle:
  - **YUV to Mat Conversion**: Converts YUV420_888 byte buffers into grayscale OpenCV `Mat` images.
  - **Marker Detection**: Executes `Aruco.detectMarkers()` to locate marker corners.
  - **Perspective-n-Point 3D Pose**: Runs `Calib3d.solvePnP()` with camera intrinsic matrix $K$ and distortion coefficients $D$ to output translation vector $T = [\Delta X, \Delta Y, \Delta Z]^T$ and rotation vector $R$.

### Phase 3: Closed-Loop Flight Control Integration (`ARVisionLandingManager.kt`)
- Connect detected pose offsets $(\Delta X, \Delta Y, \Delta Z, \Delta \psi)$ to [ARVisionLandingManager.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ARVisionLandingManager.kt).
- Send 10 Hz Virtual Stick velocity commands ($V_x, V_y, V_z, \text{Yaw Rate}$) via `VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(...)` until touchdown ($Alt \le 0.25\text{ m}$) is complete.

### Phase 4: FPV HUD AR Overlay Binding (`ARLandingOverlayView.kt` & `MainActivity.kt`)
- Register `ICameraStreamManager.addFrameListener` in `MainActivity.kt`.
- Feed pose updates to [ARLandingOverlayView.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ARLandingOverlayView.kt) using `postInvalidate()` to render 3D target bounding boxes, central crosshairs, altitude badge, and `ALIGN: PERFECT` status.

---

## 4. Verification & Testing Protocol

1. **Compilation Verification**:
   ```powershell
   .\gradlew.bat assembleDebug
   ```
   Ensure Gradle exit code `0` and `app-debug.apk` generation.
2. **Flight Simulator Verification**:
   - Connect aircraft to **DJI Assistant 2 Simulator**.
   - Test ArUco marker detection under simulated camera feeds and verify Virtual Stick velocity response curves.
