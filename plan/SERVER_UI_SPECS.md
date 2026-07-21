# Tactical Command Center (C2) UI Specifications

This document outlines the required design, layout, and logic for the frontend application (Web or Desktop) that will run on the C2 server. This UI is what the base commander will use to monitor and control the drone fleet.

## 1. Overall Design Aesthetics
The UI should be built for tactical efficiency, prioritizing dark modes, high contrast for outdoor visibility, and immediate access to critical data.

*   **Theme:** Dark mode (e.g., `#0F172A` backgrounds with `#10B981` accents).
*   **Typography:** Monospace fonts for telemetry data to ensure numbers don't jump around as they update (e.g., `Roboto Mono`, `JetBrains Mono`).
*   **Layout:** A massive, edge-to-edge tactical map taking up the majority of the screen, with floating or collapsible side panels.

---

## 2. Core UI Components

### A. The Fleet Map (AWACS View)
This is the primary interface.
*   **Logic:** The frontend subscribes to the MQTT wildcard `tactical/fleet/+/telemetry`.
*   **Display:** Every time a telemetry packet arrives, check if the `drone_id` exists on the map. If not, spawn a new NATO drone icon. If it does, animate the icon to the new `latitude`/`longitude`.
*   **Interaction:** Clicking a drone icon on the map should "select" that drone, updating the Telemetry Panel and arming the Mission Planner.

### B. Telemetry & Hardware Panel
A sidebar that populates when a drone is selected on the map.
*   **Status Indicator:** Green (Flying), Yellow (Hovering), Red (RTH/Warning).
*   **Flight Data:** Altitude (`altitude_m`), Speed (`speed_mps`), Heading (`heading_deg`).
*   **Hardware Data:** Battery (`battery_percent`), GPS Lock (`gps_satellites`), Radio Link (`rc_signal_strength`).

### C. The Mission Planner (Waypoint Editor)
When a drone is selected, the commander should be able to click on the map to plot a course.
*   **Logic:** Clicking the map generates an array of waypoints.
*   **Sending Data:** When the commander hits "Upload Course", the UI iterates through the waypoints and publishes a sequence of `ADD_WAYPOINT` JSON payloads to `tactical/fleet/{selected_drone_id}/command`.

### D. Quick Action Bar (C2 Controls)
A persistent ribbon of highly visible, color-coded buttons for immediate command execution.
*   **[ EXECUTE MISSION ]** (Green): Publishes the `EXECUTE_MISSION` command.
*   **[ RETURN TO HOME ]** (Orange): Publishes the `RTH` command.
*   **[ EMERGENCY LAND ]** (Red): Publishes the `LAND` command. Must include a confirmation dialog to prevent accidental clicks.

### E. Live Video Feed (Future Implementation)
A resizable picture-in-picture (PiP) window.
*   **Logic:** Connects to the WebRTC Signaling Server (`ws://[SERVER_IP]:8000`), exchanges SDP offers, and plays the incoming H.264 video stream in a standard HTML5 `<video>` tag.

---

## 3. Frontend Technology Recommendations
To build this UI rapidly and robustly, the following stack is recommended:

*   **Framework:** React (Next.js) or Vue (Nuxt).
*   **Map Engine:** Mapbox GL JS or Leaflet (configured to pull standard OpenStreetMap tiles via public internet).
*   **MQTT Client:** MQTT.js (connects via WebSockets to the Mosquitto broker on port `9001`).
*   **Video Player:** WebRTC native API.
