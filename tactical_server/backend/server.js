const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const mqtt = require('mqtt');
const { Pool } = require('pg');

const cors = require('cors');

const app = express();
app.use(cors());
app.use(express.json());
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' } });

// 1. PostgreSQL Persistence Setup
const pool = new Pool({
  user: process.env.POSTGRES_USER || 'tactical',
  host: process.env.POSTGRES_HOST || 'db',
  database: process.env.POSTGRES_DB || 'tactical_c2',
  password: process.env.POSTGRES_PASSWORD || 'tactical123',
  port: 5432,
});

// Init DB Table
pool.query(`
  CREATE TABLE IF NOT EXISTS telemetry (
    id SERIAL PRIMARY KEY,
    drone_id VARCHAR(50),
    timestamp BIGINT,
    lat DOUBLE PRECISION,
    lon DOUBLE PRECISION,
    alt DOUBLE PRECISION,
    battery INT,
    signal INT
  );
`).then(() => {
  return pool.query('CREATE INDEX IF NOT EXISTS idx_telemetry_drone_time ON telemetry (drone_id, timestamp);');
}).then(() => {
  console.log('Database tables and indexes initialized.');
}).catch(err => console.error("DB Init Error:", err));

// 2. MQTT to DB Bridge
const mqttOptions = {};
if (process.env.MQTT_USERNAME && process.env.MQTT_PASSWORD) {
  mqttOptions.username = process.env.MQTT_USERNAME;
  mqttOptions.password = process.env.MQTT_PASSWORD;
}
const mqttClient = mqtt.connect(process.env.MQTT_BROKER || 'mqtt://mqtt-broker:1883', mqttOptions);
mqttClient.on('connect', () => {
  console.log('Backend connected to MQTT Broker for Data Persistence');
  mqttClient.subscribe('avarell/fleet/+/telemetry');
});

mqttClient.on('message', async (topic, message) => {
  if (topic === 'avarell/fleet/config') {
    try {
      const configData = JSON.parse(message.toString());
      io.emit('config_update', configData);
    } catch (e) { /* ignore non-config messages */ }
  } else if (topic.startsWith('avarell/fleet/') && topic.endsWith('/telemetry')) {
    try {
      const data = JSON.parse(message.toString());
      const droneId = data.drone_id;
      const lat = data.location?.latitude;
      const lon = data.location?.longitude;
      const alt = data.location?.altitude_m;
      const battery = data.hardware?.battery_percent;
      const signal = data.hardware?.rc_signal_strength;
      
      // Save to DB
      await pool.query(
        'INSERT INTO telemetry (drone_id, timestamp, lat, lon, alt, battery, signal) VALUES ($1, $2, $3, $4, $5, $6, $7)',
        [droneId, data.timestamp, lat, lon, alt, battery, signal]
      );
    } catch (e) {
      console.error('Failed to parse and save telemetry:', e.message);
    }
  }
});

// API Route for Historical Data
app.get('/api/history/:droneId', async (req, res) => {
  try {
    const limit = parseInt(req.query.limit) || 100;
    const result = await pool.query('SELECT * FROM telemetry WHERE drone_id = $1 ORDER BY timestamp DESC LIMIT $2', [req.params.droneId, limit]);
    res.json(result.rows);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Flight trail endpoint (for drawing historical paths on the map)
app.get('/api/trail/:droneId', async (req, res) => {
  try {
    const minutes = parseInt(req.query.minutes) || 30;
    const cutoff = Date.now() - (minutes * 60 * 1000);
    const result = await pool.query(
      'SELECT lat, lon, alt, timestamp FROM telemetry WHERE drone_id = $1 AND timestamp > $2 ORDER BY timestamp ASC',
      [req.params.droneId, cutoff]
    );
    res.json(result.rows);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Wait for the MQTT bridge to connect before subscribing to config changes
setTimeout(() => {
  if(mqttClient.connected) {
    mqttClient.subscribe('avarell/fleet/config');
  }
}, 3000);

// 3. WebRTC Signaling Server
io.on('connection', (socket) => {
  console.log('Client connected via WebSocket:', socket.id);
  
  // WebRTC Signaling for Camera Stream
  socket.on('webrtc_offer', (data) => {
    console.log(`[WebRTC] Offer from ${socket.id} to ${data.target}`);
    socket.broadcast.emit('webrtc_offer', data);
  });
  
  socket.on('webrtc_answer', (data) => {
    console.log(`[WebRTC] Answer from ${socket.id} to ${data.target}`);
    socket.broadcast.emit('webrtc_answer', data);
  });
  
  socket.on('webrtc_ice_candidate', (data) => {
    socket.broadcast.emit('webrtc_ice_candidate', data);
  });
  
  socket.on('disconnect', () => {
    console.log('Client disconnected:', socket.id);
  });
  
  // Drone joins as a broadcaster
  socket.on('join_broadcast', (droneId) => {
    socket.join(droneId);
    console.log(`Drone ${droneId} started broadcasting`);
  });
  
  // Web UI joins as a viewer
  socket.on('join_viewer', (droneId) => {
    socket.join(droneId);
    console.log(`Viewer joined feed for ${droneId}`);
  });

  socket.on('offer', (data) => {
    socket.to(data.target).emit('offer', data.offer);
  });
  
  socket.on('answer', (data) => {
    socket.to(data.target).emit('answer', data.answer);
  });
  
  socket.on('ice_candidate', (data) => {
    socket.to(data.target).emit('ice_candidate', data.candidate);
  });

  // Config sync from Web UI -> MQTT -> Android
  socket.on('push_config', (configData) => {
    // Publish to MQTT to sync the entire fleet
    if (mqttClient.connected) {
      mqttClient.publish('avarell/fleet/config', JSON.stringify(configData), { qos: 1 });
    }
    console.log('Config pushed to fleet:', configData);
  });
});

const PORT = process.env.PORT || 8000;
server.listen(PORT, () => console.log(`Backend Server running on port ${PORT}`));


