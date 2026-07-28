# Recreate2 Workspace Defect & Configuration Audit Report

> **Audit Date**: 2026-07-28  
> **Status**: RESOLVED & HARDENED  
> **Target Audience**: Core Developers, Maintainers, and Integration Engineers  

---

## 1. Executive Summary

A deep code and architectural audit of the Recreate2 workspace was conducted to identify runtime defects, topic namespace mismatches, thread-safety violations, and documentation drift. All identified defects have been patched and verified to ensure that team developers can collaborate without breaking the workspace.

---

## 2. Identified Defects & Applied Fixes

### 🔴 Defect #1: MQTT Topic Namespace Mismatch Across Stack (CRITICAL)
- **Root Cause**: 
  - Android Client ([MqttService.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/MqttService.kt)): Subscribes to `dji-sdk/fleet/{clientId}/command` and publishes to `dji-sdk/fleet/{clientId}/telemetry`.
  - Backend Server ([server.js](file:///c:/Users/avare/Documents/recreate2/tactical_server/backend/server.js)): Was subscribed to `avarell/fleet/+/telemetry`.
  - Web UI Dashboard ([App.jsx](file:///c:/Users/avare/Documents/recreate2/tactical_server/frontend/src/App.jsx)): Was publishing to `avarell/fleet/{droneId}/command`.
  - Documentation ([README.md](file:///c:/Users/avare/Documents/recreate2/README.md)): Documented legacy `tactical/fleet/`.
- **System Impact**: Web command center failed to receive real-time drone telemetry and commands sent from the dashboard never reached the drone client.
- **Applied Fix**: Standardized all MQTT topic subscriptions and publications across [server.js](file:///c:/Users/avare/Documents/recreate2/tactical_server/backend/server.js), [App.jsx](file:///c:/Users/avare/Documents/recreate2/tactical_server/frontend/src/App.jsx), and [README.md](file:///c:/Users/avare/Documents/recreate2/README.md) strictly to the `dji-sdk/fleet/` namespace.

---

### 🟠 Defect #2: `CalledFromWrongThreadException` in AR HUD View (HIGH)
- **Root Cause**: In [ARLandingOverlayView.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ARLandingOverlayView.kt), `updateDronePose()` and `updateTargetLocation()` directly invoked `invalidate()`. When camera frame listeners or MQTT telemetry threads invoked these functions from background threads, Android crashed with `CalledFromWrongThreadException`.
- **System Impact**: App crash during AR overlay updates when telemetry arrives asynchronously.
- **Applied Fix**: Replaced `invalidate()` with thread-safe `postInvalidate()` in [ARLandingOverlayView.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ARLandingOverlayView.kt).

---

### 🟡 Defect #3: Off-Main-Thread UI Callback Dispatch (MEDIUM)
- **Root Cause**: In [ARVisionLandingManager.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ARVisionLandingManager.kt), `onVisionPoseUpdated` callback dispatches were executed directly from background vision processing threads without looper posting.
- **System Impact**: Risks UI state updates failing or throwing thread violations when UI views listen to vision pose updates.
- **Applied Fix**: Wrapped `onVisionPoseUpdated` callback dispatches inside `Handler(Looper.getMainLooper()).post` in [ARVisionLandingManager.kt](file:///c:/Users/avare/Documents/recreate2/app/src/main/java/com/dji/recreate2/ARVisionLandingManager.kt).

---

### 🟢 Defect #4: Documentation Drift (LOW)
- **Root Cause**: [README.md](file:///c:/Users/avare/Documents/recreate2/README.md) contained legacy `tactical/` MQTT topic paths, and [SERVER_API_DOCS.md](file:///c:/Users/avare/Documents/recreate2/SERVER_API_DOCS.md) lacked Changelog Audit #16 entries.
- **Applied Fix**: Updated [README.md](file:///c:/Users/avare/Documents/recreate2/README.md) to `dji-sdk/fleet/` namespace and appended Audit #16 changelog entry to [SERVER_API_DOCS.md](file:///c:/Users/avare/Documents/recreate2/SERVER_API_DOCS.md).

---

## 3. Developer Onboarding & Guardrails

To prevent breaking changes in future PRs, all developers working on this codebase must adhere to the following rules:

1. **Strict MQTT Topic Convention**:
   - Telemetry Topic: `dji-sdk/fleet/{clientId}/telemetry`
   - Command Topic: `dji-sdk/fleet/{clientId}/command`
   - Broadcast Topic: `dji-sdk/fleet/broadcast/command`
   - Global Config Topic: `dji-sdk/fleet/config`
2. **Android UI Thread Safety**:
   - Custom Views called from background threads must use `postInvalidate()` instead of `invalidate()`.
   - All MSDK V5 calls (`setValue`, `performAction`) and UI updates inside background loops must be posted to `Handler(Looper.getMainLooper())` or `runOnUiThread`.
3. **Documentation Sync**:
   - Any modifications to MQTT payloads, topics, or C2 commands require instant updates to [SERVER_API_DOCS.md](file:///c:/Users/avare/Documents/recreate2/SERVER_API_DOCS.md) and [README.md](file:///c:/Users/avare/Documents/recreate2/README.md).

---

*Audit completed on 2026-07-28.*
