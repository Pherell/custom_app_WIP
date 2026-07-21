# Mapping System Finalization & UI Overhaul Plan

Sesuai permintaan Anda, dokumen ini adalah **Rencana Eksekusi Terpisah** yang difokuskan sepenuhnya untuk membuat sistem pemetaan di HP (*Android App*) benar-benar berfungsi sempurna hari ini, bisa mengunggah data ke WebODM, dan memiliki tampilan semewah Web GUI.

## Tujuan Utama
1. **Core Mapping Fixes**: Menyelesaikan cacat logika navigasi agar drone terbang presisi dan gimbal bekerja sempurna.
2. **Android UI Rework**: Merombak tampilan HP agar *full-screen*, transparan, dan menggunakan peta satelit.
3. **WebODM Data Pipeline**: Membangun sistem yang menyedot foto dari drone ke HP, lalu melemparnya ke server WebODM Anda.

---

## Rincian Eksekusi

### 1. Fix Core Navigation & Mapping Bugs (The "Deep Audit" Fixes)
Kita harus menambal kelemahan matematika yang sebelumnya saya temukan agar drone bisa terbang otonom layaknya *enterprise mapping drone*.
*   **Fix "Drunken Spiral" (POI Lock Bug)**: 
    *   *Action*: Mengubah arsitektur *Virtual Stick* di `MainActivity.kt` dari koordinat `BODY` menjadi `GROUND`.
    *   *Result*: Drone kini bisa terbang menyamping/diagonal menuju *Waypoint* sambil memutar hidungnya (Yaw) untuk menatap dan mengunci target objek (POI) secara presisi tanpa membuat gimbal patah/error.
*   **Fix Longitude Shrinkage**: 
    *   *Action*: Mengalikan jarak bujur dengan `cos(Latitude)` di fungsi `generateGridFromPolygon()`.
    *   *Result*: Kotak grid pemetaan (*Lawnmower*) akan presisi dan tidak melar/menyusut di manapun drone diterbangkan di seluruh dunia.
*   **Add Camera Turnaround Overshoot**: 
    *   *Action*: Melebihkan jarak garis grid sekitar 15 meter keluar dari area *polygon*.
    *   *Result*: Drone akan terbang melewati batas lahan, berputar balik di luar lahan, sehingga saat masuk kembali ke lahan drone sudah terbang lurus stabil untuk mulai memotret (mencegah foto nge-blur di ujung lahan).

### 2. Android UI/UX Overhaul (Web GUI Parity)
Tampilan `activity_main.xml` yang kaku dan penuh dengan tombol *solid* akan dibongkar habis.
*   **Full-Screen Satellite Map**: 
    *   *Action*: Menerapkan *Tile Source* satelit (Esri World Imagery) pada OSMDroid dan membuat *MapView* menutupi 100% layar HP.
    *   *Result*: Tampilan dasar aplikasi akan berupa hamparan bumi nyata.
*   **Glassmorphism Floating Controls**: 
    *   *Action*: Mengubah semua tombol navigasi (`btnTakeoff`, `btnGenerateGrid`, `btnModeShape`, dll) menjadi *Floating Action Buttons* atau panel transparan (hitam tembus pandang) yang melayang di pinggiran layar, meniru desain estetis dari *Web Dashboard*.

### 3. WebODM Auto-Upload Pipeline (Android to Server)
Membangun jembatan transfer foto agar *user* tidak perlu lagi repot mencabut SD Card dari drone.
*   **Tahap A: Drone -> HP**: 
    *   Menggunakan MSDK V5 `MediaDataCenter` di Android untuk men-*download* seluruh foto hasil misi (JPG) dari memori drone ke penyimpanan internal HP secara nirkabel (via WiFi *remote*).
*   **Tahap B: HP -> Server (WebODM)**: 
    *   Membuat *HTTP Multipart Request* (memakai library `OkHttp` di Kotlin) untuk mengunggah kumpulan foto tersebut ke *Node.js backend* Anda, yang kemudian akan meneruskannya ke WebODM *Server* Anda via API.

---

## User Review Required

> [!IMPORTANT]
> **Keputusan UI:**
> Dalam desain *Web GUI*, panel kontrol ada di sisi layar. Di layar HP yang kecil, apakah Anda setuju jika panel pengaturan pemetaan (seperti input Altitude & Overlap) dimunculkan sebagai **Jendela Transparan di Tengah Layar (Dialog)**, sementara tombol-tombol eksekusi (Takeoff, Mode) berderet **melayang di bawah layar**?

## Verification Plan
1. **Navigasi**: Menyimulasikan mode POI; drone harus bisa terbang ke Waypoint B sambil hidungnya berputar menatap titik A.
2. **UI**: Layar utama *Android App* harus menampilkan peta satelit *full-screen* dengan tombol melayang.
3. **WebODM Flow**: Menjalankan fungsi uji coba `uploadPhotosToWebODM()` di Kotlin; aplikasi harus mencoba mengirim paket *byte* foto ke titik akhir URL server Anda.
