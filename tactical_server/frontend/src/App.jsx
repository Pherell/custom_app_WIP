import React, { useState, useEffect, useRef, useMemo } from 'react';
import { Viewer, Entity, PointGraphics, PolylineGraphics, ModelGraphics, PolygonGraphics, CameraFlyTo, useCesium, ScreenSpaceEventHandler, ScreenSpaceEvent, CylinderGraphics } from 'resium';
import { Cartesian3, Color, Math as CesiumMath, Transforms, HeadingPitchRoll, ScreenSpaceEventType, Cartographic, UrlTemplateImageryProvider, Terrain } from 'cesium';
import 'cesium/Build/Cesium/Widgets/widgets.css';
import { io } from 'socket.io-client';
import mqtt from 'mqtt';
import * as turf from '@turf/turf';
import './index.css';



// ACTION TYPES available for waypoints (per API docs, comma-separated)
const ACTION_TYPES = [
  { value: 'PHOTO', label: 'TAKE PHOTO', icon: '📷' },
  { value: 'START_RECORD', label: 'START RECORD', icon: '🎬' },
  { value: 'STOP_RECORD', label: 'STOP RECORD', icon: '⏹️' },
  { value: 'SET_GIMBAL', label: 'SET GIMBAL', icon: '📐' },
  { value: 'LOCK_POI', label: 'LOCK POI', icon: '🎯' },
  { value: 'HOVER', label: 'HOVER (5s)', icon: '⏸️' },
];

// Cesium Map Click Handler for Mission Plotting
const CesiumMapClickComponent = ({ isPlotting, mappingMode, missionAlt, missionSpeed, setMissionWaypoints, setPolygonPoints, triggerPhoto, onWaypointAdded }) => {
  const { viewer } = useCesium();

  const handleLeftClick = (e) => {
    if (!isPlotting || !viewer) return;
    
    // Convert screen pixel click to 3D Cartesian
    const cartesian = viewer.camera.pickEllipsoid(e.position, viewer.scene.globe.ellipsoid);
    if (cartesian) {
      const cartographic = Cartographic.fromCartesian(cartesian);
      const lat = CesiumMath.toDegrees(cartographic.latitude);
      const lng = CesiumMath.toDegrees(cartographic.longitude);

      if (mappingMode === 'GRID') {
        setPolygonPoints(prev => [...prev, { lat, lng }]);
      } else {
        const newWp = { 
          lat, lng, alt: missionAlt, speed: missionSpeed, actionType: triggerPhoto ? 'PHOTO' : 'NONE' 
        };
        setMissionWaypoints(prev => {
          const updated = [...prev, newWp];
          setTimeout(() => onWaypointAdded(updated.length - 1), 50);
          return updated;
        });
      }
    }
  };

  return (
    <ScreenSpaceEventHandler>
      <ScreenSpaceEvent action={handleLeftClick} type={ScreenSpaceEventType.LEFT_CLICK} />
    </ScreenSpaceEventHandler>
  );
};



// Custom Hook for persistent browser storage
function useLocalStorage(key, initialValue) {
  const [storedValue, setStoredValue] = useState(() => {
    try {
      const item = window.localStorage.getItem(key);
      return item ? JSON.parse(item) : initialValue;
    } catch (error) {
      console.warn("Error reading localStorage", error);
      return initialValue;
    }
  });

  const setValue = (value) => {
    try {
      const valueToStore = value instanceof Function ? value(storedValue) : value;
      setStoredValue(valueToStore);
      window.localStorage.setItem(key, JSON.stringify(valueToStore));
    } catch (error) {
      console.warn("Error setting localStorage", error);
    }
  };
  return [storedValue, setValue];
}

function App() {
  const [fleet, setFleet] = useState({});
  const [selectedDrone, setSelectedDrone] = useState(null);
  const [time, setTime] = useState("");
  const [isPlotting, setIsPlotting] = useState(false);
  const [mapStyle, setMapStyle] = useLocalStorage('tac_mapStyle', 'SATELLITE');
  const [mappingMode, setMappingMode] = useState('MANUAL'); // 'MANUAL' or 'GRID'
  const [missionWaypoints, setMissionWaypoints] = useState([]);
  const [polygonPoints, setPolygonPoints] = useState([]);
  const [missionAlt, setMissionAlt] = useLocalStorage('tac_missionAlt', 50);
  const [missionSpeed, setMissionSpeed] = useLocalStorage('tac_missionSpeed', 10);
  const [gridOverlap, setGridOverlap] = useLocalStorage('tac_gridOverlap', 70);
  const [triggerPhoto, setTriggerPhoto] = useState(true);
  const [actionDialogIdx, setActionDialogIdx] = useState(null); // index of WP being edited, null = closed
  
  const [videoConnected, setVideoConnected] = useState(false);
  const [flightTrails, setFlightTrails] = useState({});
  const [showConfigPanel, setShowConfigPanel] = useState(false);
  const [rthAlt, setRthAlt] = useLocalStorage('tac_rthAlt', 100);
  const [obstacleAction, setObstacleAction] = useLocalStorage('tac_obstacleAction', 'BRAKE');
  const [sysLogs, setSysLogs] = useState([]);  
  
  // RTMP Streaming State
  const [rtmpUrl, setRtmpUrl] = useLocalStorage('tac_rtmpUrl', 'rtmp://rtc.blackeye.id:1936/dji-sdk-view-asli');
  const [isRtmpActive, setIsRtmpActive] = useState(false);
  // Multi-MQTT State
  const defaultWsProtocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const [brokerList, setBrokerList] = useLocalStorage('tac_brokerList', [`${defaultWsProtocol}://${window.location.hostname}:9001`]);
  const [newBrokerUrl, setNewBrokerUrl] = useState('mqtt://');
  const [brokerStatuses, setBrokerStatuses] = useState({});
  
  const socketRef = useRef(null);
  const hasAutocentered = useRef(false);
  const clientsRef = useRef({});
  const droneBrokerMap = useRef({});
  const simTimerRef = useRef(null);

  // Update clock
  useEffect(() => {
    const timer = setInterval(() => {
      const d = new Date();
      setTime(`Zulu: ${d.toISOString().substring(11, 19)} - Local: ${d.toTimeString().substring(0, 8)}`);
    }, 1000);
    return () => clearInterval(timer);
  }, []);

  // Connect to WebRTC Signaling Server
  useEffect(() => {
    const webrtcPort = import.meta.env.VITE_WEBRTC_PORT || 9005;
    const httpProtocol = window.location.protocol === 'https:' ? 'https' : 'http';
    const socket = io(`${httpProtocol}://${window.location.hostname}:${webrtcPort}`);
    socketRef.current = socket;
    socket.on('connect', () => {
      console.log('Connected to WebRTC Signaling Server');
      setVideoConnected(true);
    });
    socket.on('disconnect', () => setVideoConnected(false));
    
    socket.on('config_update', (configData) => {
      if (configData.rthAlt !== undefined) setRthAlt(configData.rthAlt);
      if (configData.obstacleAction !== undefined) setObstacleAction(configData.obstacleAction);
    });
    
    return () => socket.disconnect();
  }, []);

  // Multi-MQTT Connection Manager
  useEffect(() => {
    brokerList.forEach(url => {
      if (!clientsRef.current[url]) {
        const mqttClient = mqtt.connect(url);
        clientsRef.current[url] = mqttClient;
        
        mqttClient.on('connect', () => {
          console.log(`Connected to MQTT Broker: ${url}`);
          mqttClient.subscribe('avarell/fleet/+/telemetry');
          mqttClient.subscribe('avarell/fleet/+/mission');
          setBrokerStatuses(prev => ({ ...prev, [url]: true }));
        });

        mqttClient.on('close', () => setBrokerStatuses(prev => ({ ...prev, [url]: false })));
        mqttClient.on('offline', () => setBrokerStatuses(prev => ({ ...prev, [url]: false })));
        mqttClient.on('reconnect', () => setBrokerStatuses(prev => ({ ...prev, [url]: false })));

        mqttClient.on('message', (topic, message) => {
          try {
            const data = JSON.parse(message.toString());
            
            if (topic.endsWith('/mission')) {
              if (data.type === 'grid_mission' && data.waypoints) {
                // Sinergi: WebApp menerima Grid Lawnmower dari Android (Phase 2)
                const wpParsed = data.waypoints.map(wp => ({
                  lat: wp.lat,
                  lng: wp.lon,
                  alt: wp.alt || 50,
                  speed: wp.speed || 10,
                  actionType: wp.action || ''
                }));
                setMissionWaypoints(wpParsed);
                alert(`HQ Received Tactical Grid Mission from Edge Unit!`);
              }
              return;
            }

            droneBrokerMap.current[data.drone_id] = url; // Map drone to this broker
            
            setFleet(prev => ({ ...prev, [data.drone_id]: data }));

            // Phase 2: Live Footprints Trail Fix
            if (data.location?.latitude && data.location?.longitude) {
              setFlightTrails(prev => {
                const trail = prev[data.drone_id] || [];
                const newPoint = {
                  lat: data.location.latitude,
                  lng: data.location.longitude,
                  alt: data.location.altitude_m || 50
                };
                const updated = [...trail, newPoint].slice(-500); // 500 points for longer footprints
                return { ...prev, [data.drone_id]: updated };
              });
            }
          } catch (err) {
            console.error("Failed to parse telemetry", err);
          }
        });
      }
    });
    
    // Cleanup removed brokers
    Object.keys(clientsRef.current).forEach(url => {
      if (!brokerList.includes(url)) {
        clientsRef.current[url].end();
        delete clientsRef.current[url];
        setBrokerStatuses(prev => {
          const newStatus = { ...prev };
          delete newStatus[url];
          return newStatus;
        });
      }
    });
  }, [brokerList]);

  const addBroker = () => {
    if (newBrokerUrl) {
      let finalUrl = newBrokerUrl;
      // Auto-convert standard MQTT TCP to WebSockets for browser compatibility
      if (finalUrl.startsWith('mqtt://')) {
        finalUrl = finalUrl.replace('mqtt://', 'ws://');
      }
      // Auto-fix default ports if user accidentally provided 1883 instead of 9001
      if (finalUrl.endsWith(':1883')) {
        finalUrl = finalUrl.replace(':1883', ':9001');
      } else if (!finalUrl.includes(':', 6)) {
        // No port provided, append default 9001
        finalUrl += ':9001';
      }
      
      if (!brokerList.includes(finalUrl)) {
        setBrokerList(prev => [...prev, finalUrl]);
      }
      setNewBrokerUrl('');
    }
  };

  const removeBroker = (url) => {
    setBrokerList(prev => prev.filter(b => b !== url));
  };

  const sendCommand = async (droneId, cmdType, extraPayload = {}) => {
    if (droneId) {
      const broker = droneBrokerMap.current[droneId];
      if (broker && clientsRef.current[broker] && brokerStatuses[broker]) {
        const payload = { 
          command: cmdType,
          drone_id: droneId,
          timestamp: Date.now(),
          ...extraPayload 
        };
        clientsRef.current[broker].publish(`avarell/fleet/${droneId}/command`, JSON.stringify(payload), { qos: 1 });
        alert(`Sent ${cmdType} to ${droneId}`);
      } else {
        alert(`Failed to send ${cmdType}. Drone is offline or broker disconnected.`);
      }
    }
  };

  const uploadMission = async () => {
    if (selectedDrone && missionWaypoints.length > 0) {
      const broker = droneBrokerMap.current[selectedDrone];
      if (broker && clientsRef.current[broker]) {
        const payload = {
          command: 'UPLOAD_MISSION',
          waypoints: missionWaypoints
        };
        clientsRef.current[broker].publish(`avarell/fleet/${selectedDrone}/command`, JSON.stringify(payload), { qos: 1 });
        alert(`Mission uploaded with ${missionWaypoints.length} waypoints via MQTT!`);
        setMissionWaypoints([]);
        setIsPlotting(false);
      } else {
        alert("Failed to upload mission. Broker not connected.");
      }
    } else {
      alert("Please select a drone and plot waypoints first.");
    }
  };

  const generateGrid = () => {
    if (polygonPoints.length < 3) return alert('Need at least 3 points for a polygon');
    const points = polygonPoints.map(p => [p.lng, p.lat]);
    if (points[0][0] !== points[points.length-1][0] || points[0][1] !== points[points.length-1][1]) {
      points.push([...points[0]]);
    }
    const poly = turf.polygon([points]);
    const bbox = turf.bbox(poly);

    // FOV width at alt H (assume ~75m width at 50m height)
    const fovWidth = missionAlt * 1.5;
    const spacingMeters = fovWidth * (1 - gridOverlap / 100);
    const spacingDeg = spacingMeters / 111111;

    let gridWps = [];
    let sweepLat = bbox[1];
    let leftToRight = true;

    while (sweepLat <= bbox[3]) {
      const line = turf.lineString([[bbox[0] - 0.1, sweepLat], [bbox[2] + 0.1, sweepLat]]);
      let intersects;
      try {
        intersects = turf.lineIntersect(line, poly);
      } catch(e) {
        break; // invalid geometry fallback
      }
      
      if (intersects.features.length >= 2) {
        intersects.features.sort((a, b) => a.geometry.coordinates[0] - b.geometry.coordinates[0]);
        const p1 = intersects.features[0].geometry.coordinates;
        const p2 = intersects.features[intersects.features.length-1].geometry.coordinates;
        
        const wp1 = { lat: p1[1], lng: p1[0], alt: missionAlt, speed: missionSpeed, actionType: triggerPhoto ? 'PHOTO' : undefined };
        const wp2 = { lat: p2[1], lng: p2[0], alt: missionAlt, speed: missionSpeed, actionType: triggerPhoto ? 'PHOTO' : undefined };
        
        if (leftToRight) {
          gridWps.push(wp1, wp2);
        } else {
          gridWps.push(wp2, wp1);
        }
        leftToRight = !leftToRight;
      }
      sweepLat += spacingDeg;
    }
    
    setMissionWaypoints(gridWps);
    setPolygonPoints([]); // clear polygon after grid generation
    setMappingMode('MANUAL'); // switch back to manual so user can tweak or upload
  };

  const clearWaypoints = () => {
    setMissionWaypoints([]);
    setPolygonPoints([]);
    setActionDialogIdx(null);
    if (selectedDrone) sendCommand(selectedDrone, 'CLEAR_MISSION');
  };

  // Waypoint drag handler
  const handleWpDragEnd = (index, lat, lng) => {
    setMissionWaypoints(prev => {
      const updated = [...prev];
      updated[index] = { ...updated[index], lat, lng };
      return updated;
    });
  };

  // Open action dialog for waypoint
  const openActionDialog = (index) => {
    setActionDialogIdx(index);
  };

  // Toggle action on waypoint (multi-select, comma-separated per API docs)
  const toggleWaypointAction = (actionValue) => {
    if (actionDialogIdx === null) return;
    setMissionWaypoints(prev => {
      const updated = [...prev];
      const wp = updated[actionDialogIdx];
      const currentActions = wp.actionType ? wp.actionType.split(',').filter(a => a) : [];
      const idx = currentActions.indexOf(actionValue);
      if (idx >= 0) {
        currentActions.splice(idx, 1); // remove
      } else {
        currentActions.push(actionValue); // add
      }
      updated[actionDialogIdx] = { ...wp, actionType: currentActions.join(',') || '' };
      return updated;
    });
  };

  // Update individual waypoint field
  const updateWaypointField = (index, field, value) => {
    setMissionWaypoints(prev => {
      const updated = [...prev];
      updated[index] = { ...updated[index], [field]: value };
      return updated;
    });
  };

  // Delete individual waypoint
  const deleteWaypoint = (index) => {
    setMissionWaypoints(prev => prev.filter((_, i) => i !== index));
    if (actionDialogIdx === index) setActionDialogIdx(null);
  };

  const pushConfig = () => {
    const configPayload = { command: 'SYNC_CONFIG', rthAlt, obstacleAction, timestamp: Date.now() };
    if (socketRef.current) socketRef.current.emit('push_config', configPayload);
    
    brokerList.forEach(url => {
      if (clientsRef.current[url]) {
        clientsRef.current[url].publish(`avarell/fleet/config`, JSON.stringify(configPayload), { qos: 1 });
      }
    });
    alert(`Config synced to all brokers: RTH ${rthAlt}m, Obstacle: ${obstacleAction}`);
  };

  const fleetCount = Object.keys(fleet).length;
  const anyMqttOnline = Object.values(brokerStatuses).some(v => v);

  const getTileUrl = () => {
    switch(mapStyle) {
      case 'SATELLITE': return 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}';
      case 'TERRAIN': return 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}';
      case 'STREET': default: return 'https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png';
    }
  };

  const startSimulation = () => {
    const firstBroker = brokerList[0];
    const client = clientsRef.current[firstBroker];

    let angle = 0;
    const centerLat = 34.0522;
    const centerLng = -118.2437;
    const radius = 0.005;

    if (simTimerRef.current) clearInterval(simTimerRef.current);

    simTimerRef.current = setInterval(() => {
      angle += 0.02;
      const lat = centerLat + (Math.sin(angle) * radius);
      const lng = centerLng + (Math.cos(angle) * radius);
      const heading = (Math.atan2(Math.cos(angle), -Math.sin(angle)) * (180 / Math.PI) + 360) % 360;
      const altitude = 60 + Math.sin(angle * 2) * 20;

      const payload = {
        drone_id: "drone_sim_01",
        timestamp: Date.now(),
        location: {
          latitude: lat,
          longitude: lng,
          altitude_m: altitude
        },
        flight_status: {
          speed_mps: 12.4,
          heading_deg: heading,
          is_flying: true,
          is_mission_executing: true
        },
        hardware: { battery_percent: 85, gps_satellites: 14, signal_quality_percent: 95 }
      };

      const logMsg = `[SIM] drone_sim_01 Pos: ${lat.toFixed(4)}, ${lng.toFixed(4)} Alt: ${altitude.toFixed(1)}m`;
      setSysLogs(prev => [logMsg, ...prev].slice(0, 50));
      
      if (client) {
        client.publish(`avarell/fleet/drone_sim_01/telemetry`, JSON.stringify(payload));
      } else {
        // Fallback: Just update local UI state if no MQTT broker is connected yet
        setFleet(prev => ({ ...prev, [payload.drone_id]: payload }));
        setFlightTrails(prev => {
          const trail = prev[payload.drone_id] || [];
          const newPoint = { lat: payload.location.latitude, lng: payload.location.longitude, alt: payload.location.altitude_m };
          return { ...prev, [payload.drone_id]: [...trail, newPoint].slice(-500) };
        });
      }
    }, 500); // 2 Hz updates for smooth 3D tracking
    
    alert("Virtual Drone Simulation started! Watch the 3D globe over Los Angeles.");
  };

  // Remove the old startSimulation block below to prevent duplicates:
  // (We replace the existing block starting with 'const startSimulation = () => {' down to 'alert("Simulation started! Watch the 3D globe.");' )

  return (
    <div className="c2-container">
      {/* CMO TOP RIBBON */}
      <div className="top-ribbon">
        <div className="ribbon-data">
          <span style={{ color: '#00FFFF', fontWeight: 'bold', marginRight: '16px', fontSize: '14px', letterSpacing: '2px' }}>DASHBOARD</span>
          Time: {time} - Selected: {selectedDrone || "NONE"}
          <span style={{ marginLeft: '16px', color: anyMqttOnline ? '#00FF00' : '#FF0000', fontWeight: 'bold' }}>
            {anyMqttOnline ? '● MQTT ONLINE' : '● MQTT OFFLINE'}
          </span>
          <span style={{ marginLeft: '8px', color: videoConnected ? '#00FF00' : '#FF0000' }}>
            {videoConnected ? '● WEBRTC' : '○ WEBRTC'}
          </span>
          <span style={{ marginLeft: '8px', color: '#888' }}>| Fleet: {fleetCount}</span>
        </div>
        <div className="ribbon-actions" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          
          <button 
            onClick={startSimulation}
            style={{ 
              background: 'rgba(255, 0, 0, 0.7)', color: 'white', 
              padding: '4px 10px', border: '1px solid red', 
              borderRadius: '4px', fontWeight: 'bold', cursor: 'pointer',
              fontSize: '10px'
            }}
          >
            START 3D SIMULATION
          </button>
          
          {/* MAP STYLE SELECTOR */}
          <div style={{ display: 'flex', gap: '4px', marginRight: '16px', background: '#333', padding: '4px 8px', borderRadius: '4px', alignItems: 'center' }}>
            <span style={{ fontSize: '10px', color: '#FFF' }}>MAP:</span>
            <select value={mapStyle} onChange={e => setMapStyle(e.target.value)} style={{ background: '#111', color: '#0F0', border: '1px solid #555', fontSize: '10px' }}>
              <option value="SATELLITE">SATELLITE</option>
              <option value="TERRAIN">TERRAIN (TOPO)</option>
              <option value="STREET">STREET (OSM)</option>
            </select>
          </div>

          {isPlotting && (
            <div style={{ display: 'flex', gap: '8px', marginRight: '16px', background: '#333', padding: '4px 8px', borderRadius: '4px', alignItems: 'center' }}>
              <select value={mappingMode} onChange={e => setMappingMode(e.target.value)} style={{ background: '#111', color: '#0F0', border: '1px solid #555', fontSize: '10px' }}>
                <option value="MANUAL">MANUAL (WAYPOINT)</option>
                <option value="GRID">GRID (POLYGON)</option>
              </select>
              
              <label style={{ fontSize: '10px', color: '#FFF' }}>
                ALT: <input type="number" value={missionAlt} onChange={e => setMissionAlt(Number(e.target.value))} style={{ width: '40px', background: '#111', color: '#0F0', border: '1px solid #555' }} />
              </label>
              <label style={{ fontSize: '10px', color: '#FFF' }}>
                SPD: <input type="number" value={missionSpeed} onChange={e => setMissionSpeed(Number(e.target.value))} style={{ width: '40px', background: '#111', color: '#0F0', border: '1px solid #555' }} />
              </label>
              
              {mappingMode === 'GRID' && (
                <>
                  <label style={{ fontSize: '10px', color: '#FFF' }}>
                    OVERLAP %: <input type="number" value={gridOverlap} onChange={e => setGridOverlap(Number(e.target.value))} style={{ width: '40px', background: '#111', color: '#0F0', border: '1px solid #555' }} />
                  </label>
                  <button className="tactical-btn blue" style={{ padding: '2px 6px', fontSize: '10px' }} onClick={generateGrid}>GENERATE GRID</button>
                </>
              )}
              
              <label style={{ fontSize: '10px', color: '#FFF', display: 'flex', alignItems: 'center', gap: '4px' }}>
                <input type="checkbox" checked={triggerPhoto} onChange={e => setTriggerPhoto(e.target.checked)} /> PHOTO
              </label>

              <span style={{ fontSize: '10px', color: '#888' }}>WP: {missionWaypoints.length}</span>
              {(missionWaypoints.length > 0 || polygonPoints.length > 0) && (
                <button className="tactical-btn red" style={{ padding: '2px 6px', fontSize: '10px' }} onClick={clearWaypoints}>CLEAR</button>
              )}
            </div>
          )}
          
          <button className={`tactical-btn ${isPlotting ? 'green' : ''}`} onClick={() => setIsPlotting(!isPlotting)}>
            {isPlotting ? 'FINISH PLOTTING' : 'PLOT COURSE'}
          </button>
          {isPlotting && <button className="tactical-btn orange" onClick={uploadMission}>UPLOAD MISSION</button>}
          
          {!isPlotting && (
            <>
              <button className="tactical-btn green" onClick={() => sendCommand(selectedDrone, 'TAKEOFF')}>TAKEOFF</button>
              <button className="tactical-btn green" onClick={() => sendCommand(selectedDrone, 'EXECUTE_MISSION')}>EXECUTE MISSION</button>
              <button className="tactical-btn orange" onClick={() => sendCommand(selectedDrone, 'RTH')}>RETURN TO HOME</button>
              <button className="tactical-btn red" onClick={() => sendCommand(selectedDrone, 'LAND')}>AUTO LAND</button>
            </>
          )}
        </div>
      </div>

      {/* MAIN AREA */}
      <div className="main-area">
        {/* TACTICAL MAP */}
        <div className="map-container">
          <Viewer 
            full 
            animation={false} 
            timeline={false}
            baseLayerPicker={false}
            geocoder={false}
            homeButton={false}
            sceneModePicker={false}
            navigationHelpButton={false}
            style={{ width: '100%', height: '100vh', margin: 0, padding: 0 }}
            imageryProvider={new UrlTemplateImageryProvider({ url: getTileUrl() })}
          >
            {/* Drone Entities */}
            {Object.values(fleet).map(drone => {
              const position = Cartesian3.fromDegrees(
                drone.location.longitude, 
                drone.location.latitude, 
                drone.location.altitude_m || 50
              );
              const heading = CesiumMath.toRadians(drone.flight_status.heading_deg || 0);
              const hpr = new HeadingPitchRoll(heading, 0, 0);
              const orientation = Transforms.headingPitchRollQuaternion(position, hpr);

              const altitude = drone.location.altitude_m || 50;
              const fovConePosition = Cartesian3.fromDegrees(
                drone.location.longitude,
                drone.location.latitude,
                altitude / 2
              );

              return (
                <React.Fragment key={drone.drone_id}>
                  <Entity 
                    position={position}
                    orientation={orientation}
                    name={drone.drone_id}
                    description={`Broker: ${droneBrokerMap.current[drone.drone_id]}`}
                    onClick={() => setSelectedDrone(drone.drone_id)}
                  >
                    <ModelGraphics uri="/drone.glb" minimumPixelSize={64} maximumScale={20000} />
                    <PointGraphics pixelSize={10} color={Color.LIME} outlineColor={Color.BLACK} outlineWidth={2} />
                  </Entity>
                  
                  {/* Drone Camera FOV Cone */}
                  <Entity position={fovConePosition}>
                    <CylinderGraphics 
                      length={altitude}
                      topRadius={0.1}
                      bottomRadius={altitude * Math.tan(CesiumMath.toRadians(35))}
                      material={Color.fromCssColorString('rgba(0, 255, 255, 0.25)')}
                      outline={true}
                      outlineColor={Color.fromCssColorString('rgba(0, 255, 255, 0.8)')}
                      outlineWidth={2}
                    />
                  </Entity>
                </React.Fragment>
              );
            })}

            {/* Flight Trails */}
            {Object.entries(flightTrails).map(([droneId, trail]) => {
              if (trail.length < 2) return null;
              const positions = trail.map(pt => Cartesian3.fromDegrees(pt.lng, pt.lat, pt.alt || 50));
              return (
                <Entity key={`trail-${droneId}`}>
                  <PolylineGraphics positions={positions} width={3} material={Color.CYAN} />
                </Entity>
              );
            })}

            {/* 3D Mission Waypoints */}
            {missionWaypoints.map((wp, i) => (
              <Entity 
                key={`wp-${i}`} 
                position={Cartesian3.fromDegrees(wp.lng, wp.lat, wp.alt)}
                description={`Waypoint ${i + 1}`}
                onClick={() => setActionDialogIdx(i)}
              >
                <PointGraphics pixelSize={12} color={Color.YELLOW} outlineColor={Color.BLACK} outlineWidth={2} />
                <PolylineGraphics positions={[Cartesian3.fromDegrees(wp.lng, wp.lat, 0), Cartesian3.fromDegrees(wp.lng, wp.lat, wp.alt)]} width={2} material={Color.YELLOW} />
              </Entity>
            ))}

            {/* 3D Polygon Area */}
            {polygonPoints.length > 2 && (
              <Entity>
                <PolygonGraphics 
                  hierarchy={Cartesian3.fromDegreesArray(polygonPoints.flatMap(p => [p.lng, p.lat]))}
                  material={Color.BLUE.withAlpha(0.3)}
                  extrudedHeight={missionAlt}
                  height={0}
                />
              </Entity>
            )}

            {/* Cesium Click Listener */}
            <CesiumMapClickComponent 
              isPlotting={isPlotting}
              mappingMode={mappingMode}
              missionAlt={missionAlt} 
              missionSpeed={missionSpeed} 
              setMissionWaypoints={setMissionWaypoints} 
              setPolygonPoints={setPolygonPoints}
              triggerPhoto={triggerPhoto}
              onWaypointAdded={setActionDialogIdx}
            />
          </Viewer>
        </div>

        {/* CMO RIGHT SIDEBAR */}
        <div className="sidebar">
          {/* Multi-MQTT Broker Manager */}
          <div className="sidebar-section">
            <div className="section-title">BROKER CONNECTIONS</div>
            {brokerList.map(url => (
              <div key={url} style={{ fontSize: '10px', display: 'flex', justifyContent: 'space-between', marginBottom: '4px', alignItems: 'center' }}>
                <span style={{ color: brokerStatuses[url] ? '#00FF00' : '#FF0000' }}>
                  {brokerStatuses[url] ? '● ' : '○ '} MQTT {url.replace('ws://', '').replace('wss://', '')}
                </span>
                <button onClick={() => removeBroker(url)} style={{ background: '#500', color: '#FFF', border: 'none', padding: '2px 4px', cursor: 'pointer', fontSize: '9px' }}>X</button>
              </div>
            ))}
            <div style={{ display: 'flex', gap: '4px', marginTop: '8px' }}>
              <input type="text" value={newBrokerUrl} onChange={e => setNewBrokerUrl(e.target.value)} placeholder="mqtt://ip:port" style={{ flex: 1, background: '#111', color: '#0F0', border: '1px solid #555', fontSize: '10px' }} />
              <button onClick={addBroker} style={{ background: '#333', color: '#FFF', border: '1px solid #555', cursor: 'pointer', fontSize: '10px' }}>ADD</button>
            </div>
          </div>

          <div className="sidebar-video" style={{ border: videoConnected ? '2px solid lime' : '2px solid red', position: 'relative', marginTop: '10px', padding: '10px' }}>
            {videoConnected ? (
              <span style={{ color: 'lime', fontSize: '10px', fontWeight: 'bold' }}>[ LIVE WEBRTC FEED CONNECTED ]</span>
            ) : (
              <span style={{ color: 'red', fontSize: '10px', fontWeight: 'bold' }}>[ NO VIDEO SIGNAL - WEBRTC DISCONNECTED ]</span>
            )}
            
            {/* RTMP STREAMING CONTROL */}
            <div style={{ marginTop: '15px', borderTop: '1px solid #444', paddingTop: '10px' }}>
              <div style={{ fontSize: '10px', color: '#0FF', marginBottom: '5px' }}>RTMP STREAMING (DRONE-TO-SERVER)</div>
              <input 
                type="text" 
                value={rtmpUrl} 
                onChange={e => setRtmpUrl(e.target.value)} 
                style={{ width: '100%', background: '#111', color: '#FFF', border: '1px solid #555', fontSize: '9px', marginBottom: '8px', padding: '4px' }} 
              />
              <div style={{ display: 'flex', gap: '8px' }}>
                <button 
                  onClick={() => {
                    sendCommand(selectedDrone, 'START_RTMP', { url: rtmpUrl });
                    setIsRtmpActive(true);
                  }} 
                  style={{ flex: 1, background: isRtmpActive ? '#222' : '#080', color: '#FFF', border: '1px solid #0F0', cursor: 'pointer', fontSize: '9px', padding: '4px' }}
                >
                  START RTMP
                </button>
                <button 
                  onClick={() => {
                    sendCommand(selectedDrone, 'STOP_RTMP');
                    setIsRtmpActive(false);
                  }} 
                  style={{ flex: 1, background: !isRtmpActive ? '#222' : '#800', color: '#FFF', border: '1px solid #F00', cursor: 'pointer', fontSize: '9px', padding: '4px' }}
                >
                  STOP RTMP
                </button>
              </div>
            </div>
          </div>

          {/* SYSTEM LOGS */}
          <div className="sidebar-section" style={{ flex: 1, overflowY: 'auto', minHeight: '120px', maxHeight: '200px' }}>
            <div className="section-title">SYSTEM LOGS</div>
            {sysLogs.length === 0 && <div style={{ fontSize: '9px', color: '#555' }}>No logs...</div>}
            {sysLogs.map((log, idx) => (
              <div key={idx} style={{
                fontSize: '9px',
                color: log.includes('ERROR') ? '#F44' : '#0F0',
                marginBottom: '2px',
                fontFamily: 'Roboto Mono, monospace'
              }}>{log}</div>
            ))}
          </div>

          {/* Unit Status */}
          <div className="sidebar-section">
            <div className="section-title">UNIT STATUS</div>
            {fleet[selectedDrone] ? (
              <>
                <div style={{fontSize:'14px', fontWeight:'bold', color: '#FFF'}}>{fleet[selectedDrone].drone_id}</div>
                <div style={{fontSize:'9px', color:'#777', marginBottom:'12px'}}>
                  {fleet[selectedDrone].hardware?.drone_type || 'UAV'} | Broker: {droneBrokerMap.current[selectedDrone]}
                </div>
                
                <div className="telemetry-row">
                  <span>ALTITUDE</span>
                  <span className="telemetry-val">{fleet[selectedDrone].location?.altitude_m?.toFixed(1) || '0.0'} m</span>
                </div>
                <div className="telemetry-row">
                  <span>BATTERY</span>
                  <span className="telemetry-val">{fleet[selectedDrone].hardware?.battery_percent || 0}%</span>
                </div>
              </>
            ) : (
              <div style={{fontSize:'11px', color:'#777'}}>No unit selected.</div>
            )}
          </div>

          {/* WAYPOINT LIST */}
          {missionWaypoints.length > 0 && (
            <div className="sidebar-section" style={{ flex: 1, overflowY: 'auto', maxHeight: '280px' }}>
              <div className="section-title">MISSION WAYPOINTS ({missionWaypoints.length})</div>
              {missionWaypoints.map((wp, idx) => (
                <div key={`wplist-${idx}`} style={{
                  fontSize: '10px', padding: '6px 8px', borderBottom: '1px solid #222',
                  backgroundColor: actionDialogIdx === idx ? '#1a3a1a' : 'transparent',
                  display: 'flex', flexDirection: 'column', gap: '3px'
                }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ color: '#00FFFF', fontWeight: 'bold', cursor: 'pointer' }} onClick={() => openActionDialog(idx)}>
                      WP {idx + 1} {wp.actionType ? wp.actionType.split(',').map(a => ACTION_TYPES.find(t => t.value === a)?.icon || '').join('') : ''}
                    </span>
                    <button onClick={() => deleteWaypoint(idx)} style={{ background: '#500', color: '#F88', border: 'none', padding: '1px 5px', cursor: 'pointer', fontSize: '9px', borderRadius: '2px' }}>DEL</button>
                  </div>
                  <div style={{ display: 'flex', gap: '6px', color: '#888' }}>
                    <span>LAT: {wp.lat.toFixed(6)}</span>
                    <span>LNG: {wp.lng.toFixed(6)}</span>
                  </div>
                  <div style={{ display: 'flex', gap: '4px', alignItems: 'center' }}>
                    <label style={{ color: '#888' }}>ALT:</label>
                    <input type="number" value={wp.alt} onChange={e => updateWaypointField(idx, 'alt', Number(e.target.value))} style={{ width: '40px', background: '#111', color: '#0F0', border: '1px solid #333', fontSize: '10px' }} />
                    <label style={{ color: '#888' }}>SPD:</label>
                    <input type="number" value={wp.speed} onChange={e => updateWaypointField(idx, 'speed', Number(e.target.value))} style={{ width: '40px', background: '#111', color: '#0F0', border: '1px solid #333', fontSize: '10px' }} />
                    <span style={{ color: '#0FF', fontSize: '9px', cursor: 'pointer', marginLeft: 'auto' }} onClick={() => openActionDialog(idx)}>[ {wp.actionType || 'NONE'} ]</span>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* Active Units */}
          <div className="sidebar-section" style={{ flex: 1, overflowY: 'auto' }}>
            <div className="section-title">ACTIVE UNITS ({fleetCount})</div>
            {Object.values(fleet).map(drone => (
              <div 
                key={drone.drone_id} 
                style={{
                  fontSize:'12px', cursor: 'pointer', padding: '8px', borderBottom: '1px solid #222',
                  backgroundColor: selectedDrone === drone.drone_id ? '#2a2a2a' : 'transparent',
                  borderLeft: selectedDrone === drone.drone_id ? '3px solid #00FF00' : '3px solid transparent'
                }}
                onClick={() => setSelectedDrone(drone.drone_id)}
              >
                <div style={{ color: selectedDrone === drone.drone_id ? '#00FF00' : '#FFF', fontWeight: 'bold' }}>{drone.drone_id}</div>
                <div style={{ color: '#888', fontSize: '9px' }}>Broker: {droneBrokerMap.current[drone.drone_id]}</div>
              </div>
            ))}
          </div>

        </div>
      </div>

      {/* ACTION DIALOG MODAL — Multi-Select (comma-separated per API docs) */}
      {actionDialogIdx !== null && missionWaypoints[actionDialogIdx] && (
        <div style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.7)', display: 'flex', alignItems: 'center', justifyContent: 'center',
          zIndex: 10000
        }} onClick={() => setActionDialogIdx(null)}>
          <div style={{
            background: '#1a1a1a', border: '2px solid #00FFFF', borderRadius: '8px',
            padding: '20px', minWidth: '320px', maxWidth: '400px'
          }} onClick={e => e.stopPropagation()}>
            <div style={{ color: '#00FFFF', fontWeight: 'bold', fontSize: '14px', marginBottom: '4px', fontFamily: 'Roboto Mono, monospace' }}>
              WAYPOINT {actionDialogIdx + 1} — SET ACTIONS
            </div>
            <div style={{ color: '#888', fontSize: '10px', marginBottom: '6px', fontFamily: 'Roboto Mono, monospace' }}>
              LAT: {missionWaypoints[actionDialogIdx].lat.toFixed(6)} | LNG: {missionWaypoints[actionDialogIdx].lng.toFixed(6)}
            </div>
            <div style={{ color: '#555', fontSize: '9px', marginBottom: '12px', fontFamily: 'Roboto Mono, monospace' }}>
              Select multiple actions (comma-separated)
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
              {ACTION_TYPES.map(action => {
                const currentActions = missionWaypoints[actionDialogIdx].actionType ? missionWaypoints[actionDialogIdx].actionType.split(',') : [];
                const isActive = currentActions.includes(action.value);
                return (
                  <button
                    key={action.value}
                    onClick={() => toggleWaypointAction(action.value)}
                    style={{
                      display: 'flex', alignItems: 'center', gap: '10px',
                      background: isActive ? 'rgba(0,255,255,0.15)' : '#222',
                      border: isActive ? '1px solid #00FFFF' : '1px solid #444',
                      color: isActive ? '#00FFFF' : '#CCC',
                      padding: '10px 14px', cursor: 'pointer', borderRadius: '4px',
                      fontFamily: 'Roboto Mono, monospace', fontSize: '12px', fontWeight: 'bold',
                      transition: 'all 0.15s'
                    }}
                  >
                    <span style={{ width: '20px', textAlign: 'center', fontSize: '14px' }}>{isActive ? '☑' : '☐'}</span>
                    <span style={{ fontSize: '18px' }}>{action.icon}</span>
                    <span>{action.label}</span>
                  </button>
                );
              })}
            </div>
            <div style={{ marginTop: '10px', padding: '6px 10px', background: '#111', borderRadius: '4px', fontFamily: 'Roboto Mono, monospace', fontSize: '10px', color: '#0FF' }}>
              PAYLOAD: "{missionWaypoints[actionDialogIdx].actionType || 'NONE'}"
            </div>
            <button
              onClick={() => setActionDialogIdx(null)}
              style={{
                marginTop: '10px', width: '100%', background: '#333', color: '#FFF',
                border: '1px solid #555', padding: '8px', cursor: 'pointer',
                fontFamily: 'Roboto Mono, monospace', fontSize: '11px', borderRadius: '4px'
              }}
            >DONE</button>
          </div>
        </div>
      )}
    </div>
  );
}

export default App;
