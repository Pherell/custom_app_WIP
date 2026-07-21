    # Enterprise Command & Control (C2) Server Architecture & UI Plan

This document outlines the architecture and UI design for the tactical C2 server, designed to handle multi-drone fleet management, high-throughput telemetry via Apache Kafka, and a professional military-grade user interface inspired by CMO (Command: Modern Operations).

---

## 1. Backend Architecture (MQTT + Kafka + WebRTC)

To support a large fleet of drones broadcasting telemetry at 10Hz, the backend relies on an event-streaming architecture.

*   **Ingestion (MQTT Broker):** Eclipse Mosquitto or EMQX handles the raw connections from the Android drone clients on port `1883`.
*   **Streaming & Storage (Apache Kafka):** 
    *   An MQTT-to-Kafka Bridge consumes the `tactical/fleet/+/telemetry` topics.
    *   Kafka persists the telemetry stream, ensuring zero data loss and enabling "flight replay" capabilities.
    *   Kafka acts as the central nervous system, feeding data to the Web UI backend, AI analytics engines, and external command systems.
*   **Video Delivery (WebRTC):** A dedicated Node.js Signaling Server (port `8000`) establishes peer-to-peer or server-relayed H.264 video streams directly from the Android devices to the Command Center browsers.

---

## 2. Web UI Layout (CMO-Style Tactical Interface)

The web frontend (built with React/Next.js) will be a 1-to-1 recreation of the CMO interface, optimized for real-time fleet control.

### A. The Global Tactical Map (Center)
*   **Visuals:** OpenStreetMap (OSM) base layer via public internet overlaid with NATO APP-6 unit markers. 
*   **Dynamic Scaling:** Similar to the Android tablet UI, all Unit Markers and Home Markers will scale proportionally with the map. As the commander zooms out, the markers shrink to avoid cluttering the view; as they zoom in, the markers grow to maintain geographical scale.
*   **RTS Interaction:** Just like the Android app, clicking a drone marker "Selects" it. Left-clicking the map while selected drops tactical waypoints, POIs, and draws the projected flight route directly on the screen.
*   **Threat Rings:** The UI will draw dynamic circles around drones representing sensor range, camera FOV, or AI detection radiuses.

### B. Top Action Ribbon
*   **Data:** Current Zulu Time, Local Time, Global Threat Status.
*   **Controls:** Global filters (Toggle Sensors, Toggle Waypoints, Map Layers).
*   **Action Buttons:** When a drone is selected, context-aware command buttons appear: `[ TAKEOFF ]`, `[ SET HOME ]`, `[ PLOT COURSE ]`, `[ EXECUTE MISSION ]`, `[ RTH (RETURN TO HOME) ]`, `[ AUTO LAND ]`.

### C. The Right Sidebar (Unit Status & Video)
Based on `activity_map_concept.xml` and the CMO layout, this sidebar populates when a drone is selected.

*   **Live Video PiP (Replacing the "Ship" Image):**
    *   At the very top of the sidebar, the static unit image is replaced by a live, low-latency **WebRTC HTML5 `<video>` feed** straight from the selected drone's camera.
*   **Unit Details:**
    *   Unit ID, Class (e.g., DJI M300), Status (Hovering, Cruise).
*   **Hardware Gauges (Progress Bars):**
    *   Battery Life (Green > 50%, Yellow > 20%, Red < 20%).
    *   RC Signal Strength (Link Quality).
*   **Sensor Status:**
    *   Edge AI Target Detection statuses.

---

## 3. Multi-Cam Fleet Dashboard

To accommodate commanders managing multiple assets, the UI will feature a dedicated "Multi-Cam" view.

*   **Access:** A button in the Top Ribbon: `[ FLEET CAMERAS ]`.
*   **Layout:** Opens a full-screen CSS Grid overlay. 
*   **Functionality:** If there are 4 drones connected, the screen divides into a 2x2 grid. Each quadrant displays the live WebRTC feed of a different drone, overlaid with its ID and current altitude.
*   **Interaction:** Clicking any video feed instantly closes the grid, selects that drone, and zooms the tactical map to its GPS location.

---

## 4. C2 RTS Control Flow

The server can control *all* drones simultaneously through the exact same RTS workflow established on the tablet.

1. **Select Unit:** Commander clicks `drone_bravo` on the Web UI map.
2. **Plot Course:** Commander clicks 3 points on the map. The Web UI draws the projected flight path.
3. **Assign Action:** Commander sets the final waypoint to "LOCK POI" (Surveillance mode).
4. **Execute:** Commander clicks `[ EXECUTE ]` on the Top Ribbon.
5. **Backend Push:** The React frontend pushes the JSON payload to the backend API -> Backend publishes to Kafka -> Kafka bridge publishes to MQTT `tactical/fleet/drone_bravo/command` -> Drone flies the mission.

---

## 5. Deployment Strategy (Docker Compose)

To ensure this complex, multi-service backend is rapidly deployable in austere or tactical environments, the entire stack must be orchestrated using **Docker Compose**. 

By keeping all services containerized, a unit can deploy the entire Command Center onto a single server (or edge compute device) by running a single command: `docker compose up -d`.

**The `docker-compose.yml` stack will include:**
1.  **`mqtt-broker`**: Eclipse Mosquitto or EMQX (Port `1883` & `9001`).
2.  **`kafka-cluster`**: Apache Kafka & Zookeeper (or KRaft) for event streaming.
3.  **`mqtt-kafka-bridge`**: A microservice or connector syncing Mosquitto to Kafka.
4.  **`map-server`**: GeoServer or MapTiler serving offline `.mbtiles`.
5.  **`webrtc-signaler`**: Node.js WebSocket server (Port `8000`).
6.  **`c2-frontend`**: Nginx serving the compiled React/Next.js UI.

All configuration files (like `mosquitto.conf`), map data, and database volumes will be securely mounted into the containers via a centralized `tactical_server/` directory, keeping the host system completely clean.
