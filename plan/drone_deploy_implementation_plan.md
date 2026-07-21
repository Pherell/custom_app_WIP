# Transformation Plan: Enterprise Mapping & Photogrammetry Platform

This plan analyzes the current state of our codebase and outlines the architectural changes needed to achieve the capabilities of platforms like DroneDeploy, Dronelink, and WebODM.

## Goal Description
The objective is to evolve the current Tactical C2 application into a full-fledged enterprise mapping solution. Based on your feedback, **the operator must be able to plan and execute photogrammetry grid missions directly from the Android phone/tablet**, just like the DroneDeploy app, without relying entirely on the web dashboard.

## Proposed Architecture

To match DroneDeploy & WebODM, we must implement three major pillars:

### Pillar 1: Native Android Grid Flight Planning (The "DroneDeploy" App Feature)
We need to give the Android app the ability to generate photogrammetry-ready flight paths with specific front/side overlaps natively on the device.
*   **Android UI (`MainActivity.kt` / XML):** 
    *   Add a new mapping mode to the UI: "MAPPING MODE".
    *   Allow the user to tap on the map to draw a Polygon (mapping boundary) using `osmdroid` polygon tools.
    *   Add a parameter panel to adjust Altitude, Front Overlap (%), and Side Overlap (%).
*   **Dynamic Lens Compatibility (Auto-Detect & Profiles):**
    *   *Method A (Auto-Detect):* Use MSDK V5 `KeyCameraLensType` to read the attached hardware lens on the fly.
    *   *Method B (Offline Profiles):* Create a dropdown menu with a built-in database of popular lenses (Mavic 3E Wide, Zenmuse P1, Zenmuse H20T, etc.) for offline planning.
    *   The FOV and Sensor Size parameters from either method will mathematically dictate the required distance between flight lines (Side Overlap) and photo trigger intervals (Front Overlap).
*   **Android Logic (Kotlin Grid Algorithm):**
    *   Implement an algorithm inside Android to calculate the bounding box of the drawn polygon.
    *   Generate parallel flight lines (Lawnmower pattern) across the bounding box, spaced out perfectly based on the camera's FOV (Field of View) and overlap percentage.
    *   Filter the lines so the drone only flies inside the polygon.
*   **DJI Native Mission API:**
    *   Convert the generated grid into a native DJI MSDK V5 **WaypointMission** (KMZ). 
    *   Add automatic camera trigger commands to the mission (e.g., shoot a photo every X meters).
*   **S3 Cloud Integration (Cloud-to-Drone KMZ Execution):**
    *   The app can receive an MQTT command containing an AWS S3 (or any HTTPS) URL pointing to a pre-generated `.kmz` file.
    *   Android utilizes `OkHttp` to download the KMZ file from S3 and saves it to the local device storage (`getExternalFilesDir`).
    *   The app then feeds this downloaded local KMZ file directly into DJI's `WaypointMissionManager` for hardware execution.

### Pillar 2: Automated Media Syncing
After the mission, the images must be transferred to the server for processing.
*   **Android (`MainActivity.kt`):**
    *   Use MSDK V5 `MediaDataCenter` to list files generated during the mission.
    *   Download the original high-resolution JPGs/DNGs from the drone to the Android tablet.
    *   Upload these files via HTTP POST to our Node.js backend.

### Pillar 3: WebODM Processing Integration (The "WebODM" Feature)
Sistem ini dirancang untuk memanfaatkan *server* WebODM yang sudah Anda miliki (tidak perlu install WebODM lokal di aplikasi).
*   **Custom API Configuration**:
    *   Tersedia pengaturan di *Android App* dan *Web Dashboard* untuk memasukkan **WebODM Server IP/URL** beserta **API Key**.
    *   Pengaturan ini disinkronkan secara otomatis antar *platform*.
*   **Backend (`server.js`)**:
    *   Setelah foto diunggah dari drone ke server lokal, Node.js secara otomatis menembak REST API WebODM eksternal Anda untuk memulai *Task Processing*.
    *   Memonitor status *processing* via *webhook/polling*.
*   **Cross-Platform Rendering (SDK & Web)**:
    *   *Android SDK (OSMDroid)*: Mengunduh *Tile Map Service* (TMS) Orthophoto 2D hasil WebODM dan menampilkannya langsung sebagai *Overlay Layer* di peta Android.
    *   *Web App (Leaflet & Potree)*: Menampilkan *Orthophoto* 2D di atas Leaflet map (menggunakan `ImageOverlay` atau `L.tileLayer`), serta merender model 3D (format `.obj`/`.las`) menggunakan Potree / Three.js di halaman *dashboard* komandan.

### Pillar 4: Advanced Mission Planning (The "DroneLink" Capability)
Untuk membuat aplikasi ini benar-benar setara dengan DroneLink (yang jauh lebih kompleks dari DroneDeploy), kita harus menambahkan arsitektur *Component-Based Mission*. DroneLink tidak hanya bergantung pada *Native Waypoint*, tetapi menggunakan *Virtual Stick* untuk manuver sinematik yang rumit. Fitur ekstra yang harus kita bangun:
*   **1. Component-Based Timeline (Node Editor):** Misi tidak hanya berupa *Grid*. *User* bisa menyusun misi seperti kepingan puzzle (Contoh: Misi = *Lawnmower Grid* + *Orbit Tower* + *360 Pano* + *Return to Home*).
*   **2. Terrain Follow (Elevasi 3D):** Mengintegrasikan data DEM (*Digital Elevation Model*) dari server. Jadi saat terbang memetakan bukit, drone akan otomatis naik/turun menjaga jarak 50m dari kontur tanah.
*   **3. Resumable Missions (Battery Swap):** Logika *state-machine* di Android yang mengingat kordinat foto terakhir. Jika baterai habis di tengah misi, drone pulang, ganti baterai, lalu otomatis melanjutkan dari kordinat persis di mana ia berhenti.
*   **4. Curved & Cinematic Paths:** Mengoptimalkan *engine Virtual Stick* (yang *sudah* kita miliki di aplikasi ini) untuk melakukan kalkulasi B-Spline/Bezier curve, sehingga kamera berbelok sangat mulus saat mengambil video aset infrastruktur.

### Pillar 5: Autonomous Two-Pass Workflow (The "Digital Twin" Automation)
Menjawab visi Anda tentang otomasi pemetaan *Tower* secara cerdas, kita harus membangun arsitektur dua-tahap (Two-Pass) di mana Server dan Drone "berbicara" secara mandiri tanpa campur tangan pilot:
*   **Tahap 1: Coarse Scan (Grid Udara).** Drone otomatis melakukan *Lawnmower Grid* di ketinggian aman (misal 80m) dengan kamera menghadap lurus ke bawah (-90 derajat). Ini untuk mendata lokasi kasar objek dan rintangan sekitar. Foto langsung diunggah (*auto-sync*) ke Server saat drone mendarat/masih di udara.
*   **Tahap 2: AI / WebODM Processing.** Server secara kilat (menggunakan WebODM `fast-orthophoto` atau ekstraksi *Point Cloud*) membangun **DSM (Digital Surface Model)**. Dari data DSM ini, skrip *Python* di server mendeteksi puncak *Tower* dan rintangan di sekitarnya.
*   **Tahap 3: Auto-Generated Detail Mission.** Berdasarkan letak puncak *Tower* dan area bebas rintangan dari hasil Tahap 2, Server (Node.js) secara otomatis merakit misi **DroneLink-style Facade/Orbit** (mengelilingi tower dari bawah ke atas dengan gimbal menghadap ke tengah bangunan). Misi ini dikirim balik ke HP lewat MQTT, dan drone langsung lepas landas lagi untuk mengambil foto detail resolusi tinggi (GSD milimeter) secara presisi!

### Pillar 6: The "Deep Audit" Fixes & UI Overhaul
Menindaklanjuti audit matematika dan *bug* operasional yang ada, fase ini akan mengeksekusi perombakan pada inti penerbangan (*flight core*) dan antarmuka pemetaan Android:

1.  **Fixing POI Lock & The "Drunken Spiral" (Defect 1)**: 
    *   *Akar Masalah*: Drone saat ini memakai `BODY` coordinate. Hidung drone dipaksa menghadap *Waypoint* tujuan. Saat mode POI aktif, gimbal kamera mencoba menengok ke samping untuk melihat target POI, tetapi mentok karena batas fisik gimbal Mavic hanya ~30 derajat.
    *   *Solusi*: Memindahkan arsitektur `VirtualStick` ke `GROUND` coordinate (Utara/Selatan/Timur/Barat absolut). Jika POI aktif, **Hidung drone (Yaw)** akan diputar menghadap objek POI, sementara drone terbang menyamping/diagonal menuju *Waypoint* menggunakan vektor kecepatan X dan Y. Ini menyelesaikan *Drunken Spiral* sekaligus membuat POI *tracking* menjadi sempurna!
2.  **Fixing Grid Mathematics (Defect 2 & 3)**:
    *   Menambahkan kompensasi `cos(Latitude)` agar bentuk *Grid* tidak menyusut di bujur (*Longitude*).
    *   Menambahkan `Overshoot Margin` 15 meter pada kalkulasi *Ray-Casting* agar drone melampaui batas *polygon* sebelum berbelok, memastikan ujung lahan terfoto sempurna tanpa *motion blur*.
3.  **Map Mode UI Rework (Web GUI Parity & Satellite Layer)**:
    *   **Satellite Layer**: Mengubah dasar peta OSMDroid dari gaya *Street Map* biasa menjadi **Peta Satelit Resolusi Tinggi** (menggunakan *Tile Source* satelit seperti Google Satellite atau Esri World Imagery).
    *   Merombak `activity_main.xml`. Menyingkirkan *layout* kaku dan menggantinya dengan peta *Full-Screen* seutuhnya.
    *   Tombol-tombol kontrol (*Takeoff*, *Orbit*, *Generate Grid*, *Mapping Settings*) akan dibuat melayang (*floating*) transparan di atas peta (seperti gaya *Glassmorphism* pada *dashboard* Web Anda).

---

> [!WARNING]  
> **Major Architectural Shift Required**
> Currently, we use the `VirtualStick` engine to fly the drone. Virtual Stick is **not precise enough** for professional photogrammetry mapping. We will need to rewrite the flight engine in `MainActivity.kt` to use DJI's native **Waypoint Mission API (KMZ/WPML)** which handles exact grid flying and automatic camera triggering at the hardware level.

## Open Questions

> [!IMPORTANT]  
> Please review the following questions before we proceed:
> 1. **Which step first?** Since you want the Android app to behave like DroneDeploy, the most logical first step is **Phase 1: Native Android Grid Planning**. Do you agree we should start by building the Polygon Drawing and Lawnmower Algorithm in Android?
> 2. **Default Lens Profile:** Saat kita membuat "Database Lensa", drone tipe apa yang ingin Anda jadikan *default* awal saat aplikasi pertama kali dibuka? (Misal: Mavic 3 Enterprise Wide).

## Verification Plan
1. **Phase 1 (Android App Focus):** User can draw a polygon on the Android screen, the app generates a precise lawnmower path, and the UI displays the estimated flight time and photo count.
2. **Phase 2 (DJI Execution):** Drone successfully executes the generated KMZ mission using DJI's hardware Waypoint engine, taking photos automatically.
3. **Phase 3 (Server Sync):** Android app successfully downloads photos from drone and pushes them to WebODM via the backend.
