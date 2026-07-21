import streamlit as st
import paho.mqtt.client as mqtt
import os
import json
import time
import boto3
from botocore.client import Config
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()

# --- S3 CONFIGURATION (BT-Prod Cluster) ---
S3_ENDPOINT = "http://192.168.180.99:8000"
S3_PUBLIC_DOMAIN = "https://asura-s3.stellardata.ai"
S3_BUCKET = "data-primary"
S3_PREFIX = "drone/waypoint/"
S3_ACCESS_KEY = os.getenv("S3_ACCESS_KEY", "dji-sdk-access-key")  # Nanti Anda atur lewat env var
S3_SECRET_KEY = os.getenv("S3_SECRET_KEY", "dji-sdk-secret-key")

# Init S3 Client (Cocok untuk MinIO / AWS S3 Compatible)
s3 = boto3.client('s3',
    endpoint_url=S3_ENDPOINT,
    aws_access_key_id=S3_ACCESS_KEY,
    aws_secret_access_key=S3_SECRET_KEY,
    config=Config(signature_version='s3v4'),
    region_name='us-east-1'
)

# MQTT Default
MQTT_BROKER = os.getenv("MQTT_BROKER", "127.0.0.1")
MQTT_PORT = int(os.getenv("MQTT_PORT", 1883))
MQTT_USER = os.getenv("MQTT_USERNAME", "admin")
MQTT_PASS = os.getenv("MQTT_PASSWORD", "password")

# --- UI STREAMLIT ---
st.set_page_config(page_title="Tactical KMZ Hub", layout="wide", page_icon="🛰️")

st.title("🛰️ Tactical KMZ Hub (Cloud S3 C2)")
st.markdown("Dashboard cerdas untuk mengunggah dan mendistribusikan misi pemetaan KMZ menggunakan **S3 Bucket (BT-Prod Cluster)**.")
st.caption(f"☁️ **S3 Endpoint:** `{S3_ENDPOINT}` | **Bucket:** `{S3_BUCKET}`")
st.divider()

col1, col2 = st.columns([1, 1])

# FUNGSI UNTUK MENDAPATKAN LIST KMZ DARI S3
def get_s3_kmz_files():
    try:
        response = s3.list_objects_v2(Bucket=S3_BUCKET, Prefix=S3_PREFIX)
        if 'Contents' in response:
            return [obj['Key'] for obj in response['Contents'] if obj['Key'].endswith('.kmz')]
        return []
    except Exception as e:
        st.error(f"Gagal membaca S3 Bucket: {e}")
        return []

def send_command_and_wait(broker_ip, broker_port, topic, payload, expected_events=None, timeout=5.0):
    response_data = {"event": None, "error": None}
    
    def on_message(client, userdata, msg):
        try:
            data = json.loads(msg.payload.decode())
            if "event" in data and (expected_events is None or data["event"] in expected_events):
                response_data["event"] = data["event"]
                response_data["error"] = data.get("error")
        except:
            pass
            
    client = mqtt.Client()
    if MQTT_USER and MQTT_PASS:
        client.username_pw_set(MQTT_USER, MQTT_PASS)
        
    try:
        client.connect(broker_ip, int(broker_port), 5)
        if expected_events:
            client.on_message = on_message
            client.subscribe("avarell/fleet/+/mission")
            client.loop_start()
            
        client.publish(topic, json.dumps(payload))
        
        if expected_events:
            start_time = time.time()
            while time.time() - start_time < timeout:
                if response_data["event"]:
                    break
                time.sleep(0.2)
            client.loop_stop()
            
        client.disconnect()
        return response_data
    except Exception as e:
        return {"error": str(e)}


with col1:
    st.header("1. Upload Misi KMZ (ke S3)")
    uploaded_file = st.file_uploader("Seret file KMZ ke sini untuk diunggah ke S3 Server", type=["kmz"])
    
    if uploaded_file is not None:
        with st.spinner(f"Mengunggah {uploaded_file.name} ke bucket {S3_BUCKET}/{S3_PREFIX}..."):
            try:
                object_key = f"{S3_PREFIX}{uploaded_file.name}"
                s3.upload_fileobj(uploaded_file, S3_BUCKET, object_key)
                st.success(f"Berhasil menyimpan: {uploaded_file.name} ke dalam S3!")
            except Exception as e:
                st.error(f"Gagal mengunggah ke S3: {e}")

with col2:
    st.header("2. Dispatch Center (MQTT)")
    kmz_files = get_s3_kmz_files()
    
    if not kmz_files:
        st.info("Belum ada file KMZ di dalam S3 Bucket.")
    else:
        selected_kmz = st.selectbox("Pilih Misi untuk Dikirim:", kmz_files)
        
        col_mqtt1, col_mqtt2 = st.columns([3, 1])
        with col_mqtt1:
            broker_ip = st.text_input("MQTT Broker IP", value=MQTT_BROKER)
        with col_mqtt2:
            broker_port = st.number_input("Port", value=MQTT_PORT, step=1)
            
        public_s3_endpoint = st.text_input("S3 Public Endpoint (IP/Domain yang bisa diakses Drone):", value=S3_PUBLIC_DOMAIN, help="Gunakan IP VPN (misal 10.x.x.x) jika Drone menggunakan Tailscale/ZeroTier.")
            
        target_drone = "+" # Default to broadcast
            
        if "is_airborne" not in st.session_state:
            st.session_state.is_airborne = False

        st.markdown("### 🛫 Kontrol Misi (Broadcast)")
        col_btn1, col_btn_takeoff, col_btn_exec, col_btn2 = st.columns([1, 1, 1, 1])
        
        with col_btn1:
            if st.button("📡 KIRIM MISI (KMZ)", use_container_width=True, type="primary"):
                # URL untuk diunduh oleh drone (menggunakan IP publik/VPN)
                download_url = f"{public_s3_endpoint.rstrip('/')}/{S3_BUCKET}/{selected_kmz}"
                
                with st.spinner("Memancarkan sinyal radio MQTT..."):
                    try:
                        # Publish via MQTT
                        client = mqtt.Client()
                        if MQTT_USER and MQTT_PASS:
                            client.username_pw_set(MQTT_USER, MQTT_PASS)
                        client.connect(broker_ip, int(broker_port), 60)
                        
                        topic = "avarell/fleet/broadcast/command"
                    
                        payload = {
                            "command": "DOWNLOAD_KMZ",
                            "url": download_url
                        }
                        
                        client.publish(topic, json.dumps(payload))
                        client.disconnect()
                        
                        st.success(f"Terkirim! {selected_kmz} -> Semua Drone (Broadcast).")
                        st.code(f"Topic: {topic}\nPayload: {json.dumps(payload, indent=2)}")
                    except Exception as e:
                        st.error(f"Gagal konek ke MQTT: {e}. Pastikan broker menyala!")

        with col_btn_takeoff:
            if st.button("🛫 AUTO TAKE-OFF", use_container_width=True):
                with st.spinner("Menunggu konfirmasi Take-Off dari Drone... (Max 6s)"):
                    payload = {"command": "TAKE_OFF"}
                    topic = "avarell/fleet/broadcast/command"
                    
                    res = send_command_and_wait(broker_ip, broker_port, topic, payload, ["TAKEOFF_SUCCESS", "TAKEOFF_FAILED"], timeout=6.0)
                    
                    if res.get("error") and res.get("event") != "TAKEOFF_SUCCESS":
                        st.error(f"Gagal/Ditolak: {res['error']}")
                    elif res.get("event") == "TAKEOFF_SUCCESS":
                        st.session_state.is_airborne = True
                        st.success(f"Berhasil Terbang! Drone mengudara di 1.2m.")
                    else:
                        st.warning("Perintah terkirim, tapi tidak ada jawaban dari drone (Timeout/Sinyal Lemah).")

        with col_btn_exec:
            if st.button("🟢 EKSEKUSI KMZ", use_container_width=True, type="primary"):
                if not st.session_state.get("is_airborne", False):
                    st.warning("⚠️ Drone harus di atas! Silakan klik 'AUTO TAKE-OFF' terlebih dahulu.")
                else:
                    with st.spinner("Memulai Misi KMZ... (Max 16s)"):
                        payload = {"command": "START_KMZ"}
                        topic = "avarell/fleet/broadcast/command"
                        
                        res = send_command_and_wait(broker_ip, broker_port, topic, payload, ["KMZ_STARTED", "KMZ_START_FAILED"], timeout=16.0)
                        
                        if res.get("error") and res.get("event") != "KMZ_STARTED":
                            st.error(f"Gagal Eksekusi: {res['error']}")
                        elif res.get("event") == "KMZ_STARTED":
                            st.success(f"Misi KMZ Berjalan Sempurna!")
                        else:
                            st.warning("Perintah terkirim, tapi tidak ada laporan status KMZ dari drone.")

        with col_btn2:
            if st.button("🗑️ HAPUS MISI", use_container_width=True):
                with st.spinner(f"Menghapus {selected_kmz} dari S3..."):
                    try:
                        s3.delete_object(Bucket=S3_BUCKET, Key=selected_kmz)
                        st.success(f"{selected_kmz} berhasil dihapus dari server!")
                        st.rerun() # Refresh the list
                    except Exception as e:
                        st.error(f"Gagal menghapus misi: {e}")

st.divider()
st.caption("Pusat Komando Recreate2 | Engine: Streamlit + Boto3 S3 + Paho MQTT")
