# Panduan Pemetaan Drone (Drone Mapping Guide) & Dokumentasi MQTT

Dokumen ini menjelaskan alur kerja (*workflow*) lengkap bagaimana sistem melakukan pemetaan (fotogrametri) dari Aplikasi Android maupun Server C2 (Web App), serta struktur komunikasi MQTT di belakangnya.

---

## Bagian 1: Bagaimana Cara Drone Melakukan Mapping?

Pemetaan dengan drone (*Photogrammetry*) adalah proses menerbangkan drone secara otomatis di atas suatu area untuk mengambil puluhan hingga ratusan foto tegak lurus (nadir -90°) yang saling tumpang tindih (*overlap*). Foto-foto ini nantinya akan dijahit oleh software seperti WebODM menjadi peta 2D (Orthophoto) atau model 3D.

### Alur Kerja (Workflow) Pemetaan Otomatis
1. **Perencanaan Area (Grid Generation)**
   Pengguna menentukan area lahan yang akan dipetakan dengan menggambar poligon (kotak/bidang) di atas peta.
2. **Kalkulasi Jalur Zig-zag**
   Sistem secara otomatis menghitung rute zig-zag di dalam poligon tersebut berdasarkan:
   - **Ketinggian (Altitude)**: Semakin tinggi, cakupan kamera makin luas, garis zig-zag makin renggang.
   - **Side Overlap & Front Overlap**: Umumnya 70%-80%. Memastikan setiap foto memiliki kesamaan objek dengan foto di sebelahnya agar software WebODM bisa menjahitnya.
3. **Pengaturan Kamera (Gimbal Pitch)**
   Kamera drone ditundukkan menghadap lurus ke bawah (`Gimbal Pitch = -90°`).
4. **Eksekusi & Pengambilan Foto Beruntun**
   Drone lepas landas secara otomatis menuju titik pertama (WP1). Selama terbang menyusuri garis zig-zag, drone akan **berhenti sejenak atau terus melaju sambil memicu jepretan foto (`actionType: "PHOTO"`)** setiap sekian meter sesuai jarak Overlap.
5. **Kembali (RTH) & Upload**
   Setelah titik terakhir selesai difoto, drone otomatis pulang (Return To Home). Gambar yang tersimpan di SD Card kemudian diunggah ke *server* WebODM untuk diproses.

---

## Bagian 2: Dokumentasi Komunikasi MQTT (C2 Server ↔ Android)

Untuk mengendalikan pemetaan ini dari jarak jauh (Web App C2), kita menggunakan protokol MQTT. 

### Topik MQTT (Topics)
Setiap drone memiliki ID unik (contoh: `drone_alpha_01`).
- **Command (C2 ➡️ Drone):** `dji-sdk/fleet/{drone_id}/command` (QoS 1)
- **Telemetry (Drone ➡️ C2):** `dji-sdk/fleet/{drone_id}/telemetry` (QoS 0)

### Struktur Payload Perintah Pemetaan (Commands)

**1. UPLOAD_MISSION (Kirim Rute Mapping Sekaligus)**
Menggantikan seluruh rute yang ada di drone dengan rute pemetaan baru (Grid/Manual) hasil kalkulasi C2.

```json
{
  "command": "UPLOAD_MISSION",
  "waypoints": [
    {
      "lat": -6.200000,
      "lng": 106.800000,
      "alt": 50.0,
      "speed": 10.0,
      "actionType": "PHOTO" 
    },
    {
      "lat": -6.200500,
      "lng": 106.800000,
      "alt": 50.0,
      "speed": 10.0,
      "actionType": "PHOTO"
    }
  ]
}
```
*Catatan: `actionType: "PHOTO"` sangat penting untuk mapping, karena tanpa ini drone hanya akan lewat tanpa menjepret gambar.*

**2. ADD_WAYPOINT (Tambah 1 Titik Secara Langsung)**
```json
{
  "command": "ADD_WAYPOINT",
  "lat": -6.200000,
  "lon": 106.800000, 
  "alt": 50.0,
  "actionType": "START_RECORD"
}
```

**3. Kontrol Misi Eksekusi**
```json
// Untuk memulai terbang otomatis ke titik-titik mapping
{ "command": "EXECUTE_MISSION" }

// Untuk menghapus semua titik dari memori drone
{ "command": "CLEAR_MISSION" }
```

### Struktur Payload Telemetri (Feedback dari Drone)

C2 Server harus mendengarkan *Telemetry* untuk mengetahui posisi *real-time* drone dan memastikan misi (titik-titik mapping) sudah benar-benar tertanam di memori drone (`active_mission`).

```json
{
  "drone_id": "drone_alpha_01",
  "timestamp": 1690000000000,
  "location": {
    "latitude": -6.2001,
    "longitude": 106.8002,
    "altitude_m": 50.5
  },
  "flight_status": {
    "heading_deg": 90.0,
    "speed_mps": 5.2
  },
  "active_mission": [
    {
      "lat": -6.200000,
      "lng": 106.800000,
      "alt": 50.0,
      "speed": 10.0
    }
  ],
  "hardware": {
    "battery_percent": 85,
    "rc_signal_strength": 99,
    "drone_type": "Mavic 3 Enterprise"
  }
}
```
