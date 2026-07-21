---
title: MAVLink UAV Packet and Command Audit for DJI MQTT Interoperability
status: research
date: 2026-07-20
scope: repository-static-audit
related_task: TASK-S006-127
---

# MAVLink UAV Packet and Command Audit for DJI MQTT Interoperability

## Decision Summary

ARGUS Swarm already has a useful normalized product layer for vehicle state,
mission planning, queued GUIDED orders, safety intent, and durable execution
evidence. Its live flight implementation is nevertheless strongly MAVLink and
ArduPilot shaped below that layer. The primary exposure is not the dashboard:
it is the GCS Link's direct use of `pymavlink` packet objects, MAVLink
system/component routing, command-response matching, ArduCopter mode numbers,
native mission protocol, parameter readback, and exact-token `TIMESYNC`
liveness.

The suitable direction is therefore a **protocol-neutral vehicle-adapter
boundary with protocol-specific bridges**, not an MQTT-to-MAVLink packet
translator and not a DJI-specific branch through the mission/UI code. A DJI
MQTT bridge must satisfy the same *semantic* contracts where DJI supports
them, explicitly report a capability gap where it does not, and retain its own
raw protocol details in adapter evidence. It must not pretend that an MQTT
publish is a MAVLink `COMMAND_ACK`, that a DJI telemetry sample is an
ArduPilot local-NED sample, or that a DJI route API is a native MAVLink mission
upload.

This document is an evidence-based inventory of the current checkout. It does
not assert any DJI MQTT topic, payload, quality-of-service, command,
acknowledgement, or safety capability; those have not been provided to this
repository audit.

## Scope and Method

The audit used repository searches for `pymavlink`, receive calls, outgoing
MAVLink encoders, MAVLink constants, mission compiler command IDs, and the
backend contracts that persist or consume their results. It distinguishes the
surfaces below so a future implementation does not accidentally retain a
superseded control owner.

| Surface | Status in this audit | Why it matters for DJI work |
|---|---|---|
| `tools/sentinel_gcs_link/` | Primary live radio/GCS owner | Current physical-UAV command, mission, liveness, telemetry, and packet-observation path. This is the migration priority. |
| `tools/sentinel_edge_link/` | Monitor-only MAVLink client | A small reference implementation for normalized discovery and telemetry; it deliberately sends no vehicle commands. |
| `swarm_ws/src/swarm_control/` | Simulator and rehearsal bridge | Still opens direct MAVLink connections for ArduPilot SITL/Gazebo. It is a second implementation of some flight semantics and must be brought behind the same bridge eventually. |
| `swarm_backend/live_command_adapter.py` and `mission_upload_adapter.py` | Explicit-endpoint compatibility path | Older direct backend `pymavlink` adapters, disabled unless `SENTINEL_LIVE_COMMAND_ENDPOINT` is configured. They must not become the DJI integration route. |
| `tools/field_test_harness/`, proof scripts, and tests | Verification-only producers/consumers | They intentionally emulate or inject MAVLink frames. They are excluded from the operational inventory except where they prove the same primary protocol contract. |

“Received” below means an incoming UAV-originated message that ARGUS Swarm
either reduces into state, waits for as command/protocol evidence, or records
as a decoded packet observation. “Sent” means code in a non-test runtime path
calls a MAVLink encoder. The packet observation service sees arbitrary inbound
message names, but only the listed message types have explicit semantic
handling; all other types are retained as bounded `countOnly` observations.

## Current Ownership and Data Flow

The primary physical path is intentionally GCS-owned. The server authorizes,
queues, and persists work; the GCS Link owns one receive context and is the
only component that writes MAVLink frames to a radio master. Packet evidence is
sent back to the server as bounded normalized data, not raw frames.

| Step | Current owner | MAVLink-specific work | Product-facing output |
|---|---|---|---|
| Discover | GCS Link or monitor-only Edge Link | ArduPilot `HEARTBEAT` admits a `(system_id, component_id)` candidate. | Registered live candidate and identity evidence. |
| Receive | One `PerRadioReceiveContext` per radio | Unfiltered `recv_match()` stamps receive epoch, sequence, time, and frame boundaries before reducers run. | Candidate state, response evidence, calibration/failsafe observers, packet observations. |
| Telemetry | `MavlinkState` plus projection helpers | Reads MAVLink pose, health, battery, mode, GPS, mission progress, radio, and status packets. | Frame-explicit live-ingest and GCS telemetry payloads. |
| Authorize and route | Backend + GCS Link | Selects MAVLink system/component target and source identity. | Durable command/downlink state and route snapshot. |
| Actuate | GCS Link | Emits `COMMAND_LONG`, position-target, mission, parameter, heartbeat, stream, and `TIMESYNC` frames. | Command phase/evidence; never a dashboard-specific MAVLink object. |
| Reconcile | GCS Link message router | Matches post-write source, command ID, response target, signature state, receive boundary, and state evidence. | Accepted, rejected, unknown, timeout, or state-confirmed result. |
| Persist/observe | Backend | Stores normalized response lanes, run state, telemetry, and bounded decoded observations. | API/SSE/dashboard state and the MAVLink Logs UI. |

The primary requirement to preserve is **single receive/write ownership per
physical link**. A future MQTT bridge needs an equivalent serialized session
owner and correlation boundary even though it will not use `recv_match()`.

## Inbound UAV Packet Inventory

### Discovery, identity, and health state

| UAV → ARGUS MAVLink message | Fields ARGUS consumes | Current behavior and downstream semantic | Important constraints for a DJI bridge |
|---|---|---|---|
| `HEARTBEAT` | source system/component; `type`; `autopilot`; `base_mode`; `custom_mode`; `system_status`; `mavlink_version` | Creates a candidate only when `autopilot == MAV_AUTOPILOT_ARDUPILOTMEGA`; projects vehicle type, flight stack, armed state, mode, system state, and link freshness. It also confirms mode and arm/disarm after a command. | Split generic vehicle presence, armed state, and flight mode from the ArduPilot admission predicate and numeric custom modes. A DJI identity must not be forced into a MAVLink system ID. |
| `AUTOPILOT_VERSION` | `uid`/`uid2`; `flight_sw_version`; `flight_custom_version`; `board_version` | Supplies hardware/firmware identity and supports the admitted exact-token liveness profile and GCS-failsafe evidence. | DJI needs an immutable device/firmware identity with an explicit provenance and refresh timestamp, not this packet shape. |
| `SYS_STATUS` | `battery_remaining`; `voltage_battery`; control-sensor present/enabled/health bitmaps | Projects battery percentage/voltage and prearm health. Fresh status or diagnostic evidence is required to reconcile `prearm_check`. | Define a vendor-neutral preflight/health result with individual indicators and an `unknown` state; do not map undocumented DJI health fields to MAVLink bitmaps. |
| `BATTERY_STATUS` | battery ID; cell voltages; `battery_remaining` | Provides detailed battery presentation and packet-log evidence. | Define optional pack/cell detail separately from minimum battery percentage. |
| `GPS_RAW_INT` | `fix_type`; satellites visible; EPH | Determines whether a global coordinate is usable; supplies GPS-quality display/evidence. | Keep position validity and source quality separate. DJI may use different accuracy/fix vocabulary. |
| `RADIO_STATUS` | RSSI; remote RSSI; TX buffer; RX/fixed errors | Supports radio-link diagnostics and packet observation. | Treat link metrics as optional adapter telemetry; MQTT broker/network health is not radio RSSI. |
| `EXTENDED_SYS_STATE` | `landed_state`; vehicle state | Used during GUIDED takeoff/land safe-state reconciliation. The GCS requests this stream explicitly. | A DJI bridge needs equivalent grounded/airborne/landing evidence or must declare that safe-terminal proof is unavailable. |
| `STATUSTEXT` | severity; bounded text | Stores the latest ten normalized notices, contributes health events, and supports prearm/calibration diagnostics. | Preserve vendor diagnostic code/message/severity as bounded evidence. Do not parse free text as the only safety authority. |

### Position, motion, and mission-progress state

| UAV → ARGUS MAVLink message | Fields ARGUS consumes | Current behavior and downstream semantic | Important constraints for a DJI bridge |
|---|---|---|---|
| `GLOBAL_POSITION_INT` | `lat`; `lon`; `relative_alt`; `vx`; `vy`; `vz`; `hdg` | The only source promoted to the shared WGS84 map position. It generates stable sample identity from GCS session, radio, MAVLink identity, and receive sequence; it also supports global GUIDED target arrival. | Require a WGS84 position, altitude reference, velocity units, heading convention, source timestamp, receive timestamp, and monotonic sample identity. Do not synthesize WGS84 from an undocumented local origin. |
| `LOCAL_POSITION_NED` | `x`; `y`; `z`; `vx`; `vy`; `vz` | Projects `local_xyz` with NED `z` negated for ARGUS positive-up coordinates. Supports local GUIDED target arrival and local rehearsal state. It is supporting evidence only for map truth. | Require a declared local-frame origin and axis convention. A DJI local coordinate without durable origin provenance cannot be made mission/map truth. |
| `VFR_HUD` | airspeed; groundspeed; heading; altitude; climb | Decoded and shown in packet observations; it is not retained in the primary candidate reducer. | Optional motion-detail telemetry only. |
| `MISSION_CURRENT` | `seq` | Records current native mission sequence for AUTO-start evidence. | Model progress as an optional adapter capability; DJI may expose route waypoint index or no resident mission at all. |
| `MISSION_ITEM_REACHED` | `seq` | Records reached native mission sequence for progress evidence. | Same semantic capability as above; do not require MAVLink sequence numbering in a DJI route executor. |

### Response, protocol, configuration, and service packets

| UAV → ARGUS MAVLink message | Fields ARGUS consumes | Current behavior and downstream semantic | Important constraints for a DJI bridge |
|---|---|---|---|
| `COMMAND_ACK` | source/target IDs; `command`; `result`; MAVLink signature state; post-write receive boundary | The router correlates a response to the command ID, expected UAV, GCS source identity, signing policy, and active response lane. It rejects stale/wrong-source/wrong-command acknowledgements. | Define a protocol-neutral command receipt with request/correlation ID when available, accepted/rejected/in-progress state, vendor reason, and time. MQTT cannot rely on a topic delivery acknowledgement as vehicle acceptance. |
| `TIMESYNC` | `tc1`; `ts1`; source; response target; MAVLink version/signing | Exact-token active liveness probe: ARGUS sends token in `ts1` and requires a post-write response with nonzero `tc1`, exact echo, expected route, frame version, and signing state. | This is MAVLink/firmware-profile-specific. DJI needs a separately designed liveness proof, not a fake `TIMESYNC` result. |
| `PARAM_VALUE` | `param_id`; `param_value`; source | A matching echo acknowledges `PARAM_SET`; named parameter readbacks discover RTL-speed aliases and validate GCS-failsafe doctrine. | Model configuration capabilities as named adapter settings with read/write/readback semantics. DJI settings may be immutable, asynchronous, or unavailable. |
| `MAG_CAL_PROGRESS` | mask; status; attempt; completion; direction | Streams compass-calibration progress/events after a start command. | Expose calibration as an optional service capability. DJI calibration workflows may be mobile-app-only or differ substantially. |
| `MAG_CAL_REPORT` | mask; status; autosaved; fitness; offsets/diagonals | Marks calibration success pending explicit accept or a terminal failure. | Same optional capability rule. |
| `MISSION_REQUEST` and `MISSION_REQUEST_INT` | requested `seq` | During upload, the UAV pulls each item. The GCS sends the requested `MISSION_ITEM_INT`. | Native mission transfer is a capability, not a universal definition of “execute route.” |
| `MISSION_ACK` | result/type | Acknowledges clear/upload/readback protocol. `MAV_MISSION_*` result becomes upload/readback evidence. | A DJI route upload must supply its own durable upload acceptance and readback/verification semantics. |
| `MISSION_COUNT` | `count`; optional `opaque_id` | Starts resident-mission readback. | Optional resident-route capability. |
| `MISSION_ITEM_INT` | sequence; frame; command; current; autocontinue; parameters 1–4; x/y/z | Returned item for resident mission readback and exact artifact comparison. | Do not force a DJI waypoint object into raw MAVLink fields. Compare normalized route semantics through an adapter-specific projection. |
| `MISSION_ITEM` | decoded fields only | Appears in packet logs but is not the GCS Link readback path, which requires `MISSION_ITEM_INT`. | Observation-only compatibility information. |
| `COMMAND_LONG`, `COMMAND_INT`, `SET_POSITION_TARGET_LOCAL_NED`, `SET_POSITION_TARGET_GLOBAL_INT` | bounded command/target fields | Captured/decoded if observed inbound (for example, a shared-link echo or another station); they are not accepted as instructions to ARGUS. | Preserve this as passive observability, never as a remote-control ingress. |
| Any other message name | message name and bounded counter | Packet-observation service records it as `countOnly`; it does not persist raw MAVLink frames. Candidate state ignores it. | The bridge should retain bounded protocol observations without leaking credentials, raw frames, payloads, or MQTT topics containing secrets. |

### Additional simulator-only receive set

The ROS 2 simulator bridge has a smaller, separate reducer:
`HEARTBEAT`, `LOCAL_POSITION_NED`, `SYS_STATUS`, `EXTENDED_SYS_STATE`,
`SERVO_OUTPUT_RAW`, and `COMMAND_ACK` in
`swarm_ws/src/swarm_control/swarm_control/ardupilot_fleet_bridge.py`.
The legacy single-vehicle `ardupilot_square.py` consumes `HEARTBEAT`,
`LOCAL_POSITION_NED`, `SYS_STATUS`, `STATUSTEXT`, and `COMMAND_ACK`.
`SERVO_OUTPUT_RAW` is only simulator/rehearsal diagnostic state; it is not
part of the physical GCS Link contract.

## Outbound UAV Packet Inventory

Every active GCS Link write is addressed to the registered MAVLink
system/component pair. Before authoritative writes, it configures the GCS
source identity, normally `MAV_TYPE_GCS` with a configurable source system ID
(default 255) and component ID 190. MAVLink v2 signing is optional at the
transport level but command-response matching applies the configured signing
policy.

### Presence, telemetry-stream, liveness, and configuration traffic

| ARGUS → UAV MAVLink message | Parameters actually used | Trigger and expected evidence |
|---|---|---|
| `HEARTBEAT` | type `MAV_TYPE_GCS` (6); autopilot invalid (8); zero base/custom/system status; MAVLink v2 marker (3) | Sent at one-second cadence only while authoritative GCS presence is healthy. It is intentionally coupled to cloud lease, local supervisor, radio worker, source identity, and failsafe doctrine. No reply is treated as this packet’s acknowledgement. |
| `REQUEST_DATA_STREAM` | Normal: stream IDs `POSITION` 6, `EXTENDED_STATUS` 2, `EXTRA2` 11 at configured normal rate (2 Hz default). Suppressed: `RAW_SENSORS` 1, `RC_CHANNELS` 3, `RAW_CONTROLLER` 4, `EXTRA1` 10, `EXTRA3` 12 at zero. | Normal/quiet radio profile. This is ArduPilot/MAVLink stream control; it must become a preferred telemetry-profile request, not a DJI packet type. |
| `PARAM_SET` | `SR1_POSITION`, `SR1_EXTRA1`, `SR1_EXTRA2`, `SR1_EXTRA3`, `SR1_RAW_SENS`, `SR1_RC_CHAN`, `SR1_EXT_STAT`, `MAV_PARAM_TYPE_REAL32` (9) | Best-effort ArduPilot `SR1_*` stream-rate reinforcement. No generic code should depend on these names. |
| `COMMAND_LONG`: `MAV_CMD_SET_MESSAGE_INTERVAL` (511) | `param1 = MAVLINK_MSG_ID_EXTENDED_SYS_STATE` (245); `param2 = interval microseconds`, or `-1` to disable; remaining parameters zero. | Requests landed-state telemetry required by GUIDED reconciliation. It expects subsequent `EXTENDED_SYS_STATE`, not merely an ACK. |
| `COMMAND_LONG`: `MAV_CMD_REQUEST_MESSAGE` (512) | `param1 = MAVLINK_MSG_ID_AUTOPILOT_VERSION` (148); remaining parameters zero. | Failsafe collector refreshes identity/firmware at startup and periodically. Expected reply: `AUTOPILOT_VERSION`. |
| `PARAM_REQUEST_READ` | Target system/component; named parameter; index `-1`. Reads `FS_GCS_ENABLE`, `FS_GCS_TIMEOUT`, `FS_OPTIONS`, `MAV_GCS_SYSID`, `MAV_GCS_SYSID_HI`, `SYSID_MYGCS`, `SYSID_MYGCS_HI`, and selected RTL-speed aliases. | Read-only GCS-failsafe and RTL-speed discovery. Expected reply: matching `PARAM_VALUE`. |
| `PARAM_SET` | Target; selected `RTL_SPEED_MS`/fallback or legacy `RTL_SPEED`/fallback alias; real32 value. | Mission-scoped RTL configuration write. Expected evidence is a matching `PARAM_VALUE`, then an independent readback; never assume an MQTT configuration publish has this property. |
| `TIMESYNC` | `tc1 = 0`; server-issued exact probe token in `ts1`. | Active liveness probe only for admitted ArduPilot firmware/profile. Expected reply is the exact matching `TIMESYNC` described above. |

### Direct live command traffic

The current GCS command vocabulary is `arm`, `disarm`, `takeoff`, `goto`,
`goto_local`, `goto_global`, `enable_guided`, `set_mode`, `prearm_check`,
`bench_arm_test`, `set_speed`, `start_auto`, `hold`, `land`, `rtl`, and
`abort`. Safety commands are `hold`, `land`, `rtl`, `abort`, and `disarm`.

| Operator/domain intent | ARGUS → UAV MAVLink representation | Parameters and state confirmation |
|---|---|---|
| Enter `GUIDED`, `AUTO`, `STABILIZE`, `RTL`, `LAND`, `BRAKE`; `hold`; `abort` | `COMMAND_LONG` `MAV_CMD_DO_SET_MODE` (176) | `param1 = MAV_MODE_FLAG_CUSTOM_MODE_ENABLED` (1); `param2` uses ArduCopter custom modes: STABILIZE 0, AUTO 3, GUIDED 4, RTL 6, LAND 9, BRAKE 17. Current GCS behavior maps `hold` and `abort` to BRAKE, `rtl` to RTL. Normal mode changes wait for matched `COMMAND_ACK` plus fresh matching `HEARTBEAT`; safety mode changes reconcile from fresh heartbeat state and do not wait for an ACK. |
| Prearm check | `COMMAND_LONG` `MAV_CMD_RUN_PREARM_CHECKS` (401) | All command parameters zero. A matched `COMMAND_ACK` is collected, then fresh `SYS_STATUS` or diagnostic state must prove health. |
| Arm/disarm | `COMMAND_LONG` `MAV_CMD_COMPONENT_ARM_DISARM` (400) | `param1 = 1` arm or `0` disarm; `param2 = 21196` only for explicit force arm/disarm. Normal arm waits for matched ACK plus fresh armed heartbeat; safety disarm prioritizes state reconciliation. |
| Takeoff | `COMMAND_LONG` `MAV_CMD_NAV_TAKEOFF` (22) | `param7 = altitudeM`; parameters 1–6 zero. Expected evidence: matched ACK plus fresh armed/altitude/landed-state evidence. |
| Land | `COMMAND_LONG` `MAV_CMD_NAV_LAND` (21) | Parameters zero. It is a safety command: immediate local write evidence followed by LAND-mode/disarmed state and, when available, safe-terminal evidence. |
| Guided speed | `COMMAND_LONG` `MAV_CMD_DO_CHANGE_SPEED` (178) | Direct GUIDED command uses `param1 = 0`, `param2 = requested speed m/s`, `param3 = -1` (no throttle change), others zero. A queued travel order requires an eligible accepted ACK before it emits its target; a fresh observed speed can separately prove application. |
| Global guided target | `SET_POSITION_TARGET_GLOBAL_INT` | Frame `MAV_FRAME_GLOBAL_RELATIVE_ALT_INT` (6); mask `0b0000111111111000` (position only); latitude/longitude scaled by `1e7`; relative altitude in metres; velocity/acceleration/yaw fields zero. No MAVLink ACK is expected; two fresh `GLOBAL_POSITION_INT` samples within horizontal/vertical tolerance prove arrival. |
| Local guided target | `SET_POSITION_TARGET_LOCAL_NED` | Frame `MAV_FRAME_LOCAL_NED` (1); the same position-only mask; N/E values from `xM`/`yM`; ARGUS positive-up `zM` is negated to NED down. No ACK is expected; fresh `LOCAL_POSITION_NED` evidence proves arrival. |
| Start AUTO after verified upload | Ordered `COMMAND_LONG` sequence | `DO_SET_MODE` GUIDED; `COMPONENT_ARM_DISARM`; optional `NAV_TAKEOFF` if mission has no leading takeoff; `DO_SET_MODE` AUTO; then `MAV_CMD_MISSION_START` (300) with zero parameters. It requires mission readback match, post-write state, and progress evidence (`MISSION_CURRENT` or `MISSION_ITEM_REACHED`), not an ACK alone. |
| Bench arm test | Ordered `COMMAND_LONG` sequence | STABILIZE mode; force arm (400, `param1=1`, `param2=21196`); timed wait; force disarm (400, `param1=0`, `param2=21196`). This is a service/test operation, not a general mission primitive. |
| Compass calibration | `COMMAND_LONG` commands 42424, 42425, 42426 | `MAV_CMD_DO_START_MAG_CAL` (42424): mask, retry-on-failure, autosave, delay, autoreboot. `MAV_CMD_DO_ACCEPT_MAG_CAL` (42425) and `MAV_CMD_DO_CANCEL_MAG_CAL` (42426): mask only. Matched ACK plus relevant `MAG_CAL_PROGRESS`/`MAG_CAL_REPORT` state is required. |

### Native MAVLink mission transfer

For AUTO missions, ARGUS compiles an ArduPilot artifact and uses the MAVLink
mission protocol. The normal upload/readback sequence is intentionally
separate from GUIDED live-plan orders; GUIDED never uploads a native mission.

| Direction | MAVLink message | Fields / sequence |
|---|---|---|
| GCS → UAV | `MISSION_CLEAR_ALL` | Target system/component; mission type 0. A short `MISSION_ACK` wait is best-effort before the next phase. |
| GCS → UAV | `MISSION_COUNT` | Count of all wire items; mission type 0. ARGUS retries a missing initial request sequence up to three times. |
| UAV → GCS | `MISSION_REQUEST_INT` or `MISSION_REQUEST` | Requests a sequence number. Values out of range are ignored. |
| GCS → UAV | `MISSION_ITEM_INT` | Target; sequence; frame; command; current; autocontinue; `param1` through `param4`; integer latitude/longitude or command-specific x/y; altitude z; mission type 0. |
| UAV → GCS | `MISSION_ACK` | Completes upload; returned result is retained as protocol evidence. |
| GCS → UAV | `MISSION_REQUEST_LIST` | Starts resident-mission readback, mission type 0. |
| UAV → GCS | `MISSION_COUNT` | Declares resident item count and may carry opaque ID. |
| GCS → UAV | `MISSION_REQUEST_INT` | Sent per missing resident sequence, with two passes. |
| UAV → GCS | `MISSION_ITEM_INT` | Readback item is normalized, projection-version checked, and compared field by field against the compiled artifact. |
| GCS → UAV | `MISSION_ACK` | Final acknowledgement with accepted type 0 after completed readback. |

The mission compiler currently emits these payload command IDs inside
`MISSION_ITEM_INT`:

| Normalized ARGUS mission command | MAVLink command ID | Current item semantics |
|---|---:|---|
| `takeoff` | `MAV_CMD_NAV_TAKEOFF` 22 | Global-relative-altitude frame; generated when fly rules need a leading takeoff; parameters 1–4 are zero. |
| `waypoint` | `MAV_CMD_NAV_WAYPOINT` 16 | Global-relative-altitude frame; `param1 = holdTimeS`; ArduCopter item parameters 2–4 are intentionally zero. |
| `spline_waypoint` | `MAV_CMD_NAV_SPLINE_WAYPOINT` 82 | Global-relative-altitude frame; `param1 = holdTimeS`; parameters 2–4 zero. |
| `circle` | `MAV_CMD_NAV_LOITER_TURNS` 18 | `param1 = turns`; `param2 = 0`; `param3 = signed radius`; `param4` selects centerline (1) or tangent (0) exit; position is circle center. |
| `hold` / `loiter` | `MAV_CMD_NAV_LOITER_TIME` 19 | `param1 = holdTimeS`; `loiter` may put `loiterRadiusM` in `param3`. These are timed loiters, not a DJI-orbit abstraction. |
| `land` | `MAV_CMD_NAV_LAND` 21 | Parameters 1–3 zero; `param4 = 1` is the admitted neutral yaw direction. |
| `rtl` | `MAV_CMD_NAV_RETURN_TO_LAUNCH` 20 | Generated by fly rules when no terminal route action exists. Native RTL speed is configured separately through parameter readback. |
| incoming-leg speed change | `MAV_CMD_DO_CHANGE_SPEED` 178 | Frame `MAV_FRAME_MISSION` (2); `param1 = 1` ground speed; `param2 = effective m/s`; `param3 = -1`; injected immediately before the destination NAV item it controls. |

Navigation items use `MAV_FRAME_GLOBAL_RELATIVE_ALT_INT` (6), with local
mission x/y converted by the artifact compiler to integer WGS84 coordinates.
That coordinate conversion, ArduCopter parameter canonicalization, and the
command-aware resident readback projection are adapter responsibilities—not
mission API fields.

### Simulator and compatibility-path writes

The primary GCS Link table is the current physical-UAV control path. The
following non-test paths also write MAVLink and therefore belong in a full
inventory. They are not an endorsement to retain multiple flight-control
owners; they are exactly why the adapter extraction needs an explicit simulator
implementation.

| Surface | Additional MAVLink writes | Related inbound evidence | Migration treatment |
|---|---|---|---|
| `ardupilot_fleet_bridge.py` | MAVLink `SET_MODE` for mode changes; `COMMAND_LONG` 511 to request `LOCAL_POSITION_NED`, `SYS_STATUS`, `EXTENDED_SYS_STATE`, and `SERVO_OUTPUT_RAW` at 5 Hz; `COMMAND_LONG` arm/disarm 400, takeoff 22, land 21, RTL 20; local-NED position targets. | `HEARTBEAT`, local position, system status, extended state, servo outputs, and command ACK. | A simulator/rehearsal MAVLink adapter. Its normalized ROS topics should consume the same domain state/result contracts as the GCS Link, not expose a second command model. |
| `ardupilot_square.py` | ArduPilot `SET_MODE`; `COMMAND_LONG` 511 for heartbeat, local position, global position, and system-status intervals; `COMMAND_LONG` arm/disarm 400, takeoff 22, land 21, RTL 20; local-NED position targets. | `HEARTBEAT`, local position, system status, status text, and command ACK. | Legacy single-vehicle simulator path. Keep it out of a DJI physical bridge and either adapt or formally retire it after simulator parity is preserved. |
| `MavlinkCommandEmitter` | A direct, explicit-endpoint path: ArduPilot mode helpers or command 176, prearm 401, arm/disarm 400, takeoff 22, land 21, start-auto 300, and local-NED targets. | Direct `wait_heartbeat()` and unscoped `COMMAND_ACK` waits. | Compatibility-only. It lacks the production per-radio receive owner and durable response lanes, so it must not be selected for new DJI work. |
| `MavlinkMissionUploader` | Native mission clear/count/item/readback/ack sequence from the main mission table. | Direct mission protocol reads. | Compatibility-only upload/readback. Its AUTO start is intentionally disabled; use the GCS Link for field AUTO execution. |

Root proof utilities share MAVLink helper functions and can request all streams
or individual message intervals, but their calls exist only to qualify SITL or
hardware evidence. They are not normal product control paths.

## Packet Observation Inventory

`MavlinkObservationProducer` is a bounded observability sidecar, not the
control protocol. It creates packet rows with source/route identity, direction,
family, receive time, decoded summary, and counters. It decodes the following
families: heartbeat, radio, health (`SYS_STATUS`, `BATTERY_STATUS`,
`GPS_RAW_INT`), pose (`GLOBAL_POSITION_INT`, `LOCAL_POSITION_NED`, `VFR_HUD`),
command (`COMMAND_LONG`, `COMMAND_INT`, `COMMAND_ACK`, both position-target
messages), mission protocol, status text, and autopilot version. Pose/health
telemetry is sampled/coalesced; command/protocol/health evidence receives
higher retention priority. Raw MAVLink bytes are deliberately not posted or
persisted.

For a transport-neutral design, retain the useful concept as a generic
`ProtocolObservation` with `protocol`, `adapterSessionId`, `vehicleIdentity`,
`direction`, `messageKind`, bounded decoded fields, and redacted diagnostics.
The operator UI can call the tab “Link logs” and display the protocol without
requiring DJI data to masquerade as MAVLink.

## MAVLink-Specific Coupling to Remove or Encapsulate

| Coupling | Current location/effect | Required future boundary |
|---|---|---|
| ArduPilot-only discovery gate | `MavlinkState.ingest()` admits only ArduPilot heartbeat/autopilot 3. | `VehicleIdentityObservation` and adapter identity policy. |
| `(system_id, component_id)` is the vehicle route | GCS registration, candidate mapping, command route validation, response lanes. | `VehicleRoute` with opaque adapter route ID; MAVLink IDs become one adapter implementation. |
| ArduCopter numeric modes | `COPTER_CUSTOM_MODE_BY_NAME` and state checks. | Domain `FlightMode` plus adapter mapping/capability; preserve unsupported/ambiguous modes. |
| MAVLink `COMMAND_ACK` generations | `RadioMessageRouter`, response lanes, command sender. | `CommandReceipt` and `CommandStateEvidence` with protocol-specific correlation internals. |
| Native mission protocol/frames/IDs | Artifact compiler, mission upload/readback senders. | `MissionExecutor` capability that can implement native upload, a DJI route API, or no upload capability. |
| NED and global-relative-altitude assumptions | Position target sends, local telemetry, mission item compiler. | Frame-explicit `Position`/`Target` contract with frame-origin and altitude-reference provenance. |
| ArduPilot parameters and stream setup | stream profile, failsafe collector, RTL-speed parameter IO. | Optional `VehicleConfiguration` and `TelemetryProfile` capabilities; vendor settings must be explicitly admitted. |
| Exact-token `TIMESYNC` liveness | Link probe, backend profile admission. | Pluggable `LivenessProbe` capability with protocol-specific proof rules. |
| Compass calibration packets | Calibration downlink service. | Optional `CalibrationService` capability. |
| MAVLink terminology in persistence and UI | `mavlink_*` candidate fields, liveness schema names, MAVLink Logs tab, adapter labels. | Preserve historical fields for MAVLink but add generic protocol/adapter fields before a DJI asset can be registered. |
| Multiple direct `pymavlink` owners | GCS Link, Edge Link, ROS bridges, explicit-endpoint backend adapters. | One adapter abstraction, with GCS Link as the sole physical control owner for live vehicles and simulator adapters clearly separated. |

The dashboard and most mission APIs are already closer to the desired boundary
than these lower layers: waypoints use x/y/z and normalized command names,
Fleet/Mission position views consume normalized telemetry, and the GUIDED
queue persists producer-neutral plans. The following details must remain below
the adapter: MAVLink command IDs, type masks, frame IDs, parameter names,
system/component addressing, message-rate requests, signature bits, and raw
packet decoding.

## DJI MQTT Comparison Matrix

This is the required evidence matrix for the existing DJI SDK. “Required”
means required to claim the current ARGUS behavior, not required merely to
register a read-only DJI vehicle. Leave unsupported cells explicit; do not
approximate them with MAVLink words.

| Semantic capability | Current MAVLink evidence | DJI MQTT evidence to collect | Integration decision after comparison |
|---|---|---|---|
| Asset discovery and stable identity | `HEARTBEAT` route plus `AUTOPILOT_VERSION` UID/firmware | Product/serial/device ID, SDK gateway ID, firmware, component identity, reconnect behavior | Define canonical vehicle identity and adapter route. |
| Presence and link health | heartbeat freshness, radio status, GCS heartbeat doctrine | MQTT client/broker/session state, UAV online state, telemetry freshness, reconnect/replay semantics | Separate cloud/broker availability from UAV/C2 availability. |
| WGS84 pose | `GLOBAL_POSITION_INT` + GPS fix, source receive sequence | Latitude/longitude/altitude reference, velocity, heading, GPS/RTK quality, source timestamp/rate | Implement shared live-position contract only after frame/time semantics are proven. |
| Local pose | `LOCAL_POSITION_NED` and explicit origin limitation | Local coordinate frame/origin and axis convention, if available | Admit only with origin provenance; otherwise expose global pose only. |
| Armed, mode, airborne, landed truth | heartbeat mode bits/custom mode and `EXTENDED_SYS_STATE` | Flight status, control mode, motor/armed status, landing state transitions | Map to a conservative common state model; unsupported distinctions stay unknown. |
| Preflight/health | `SYS_STATUS`, `STATUSTEXT`, parameter/readback evidence | Health-check response codes, warnings, battery/GNSS/RTK/mission readiness state | Build vendor-neutral blockers; avoid best-effort text parsing. |
| Command initiation | `COMMAND_LONG` or position target with direct wire boundary | MQTT command topic/action, request ID, required session/control authority, QoS, payload schema | Implement idempotent adapter command invocation and durable correlation. |
| Command acceptance | matched `COMMAND_ACK` | SDK acknowledgement, status topic/event, error codes, delayed acceptance behavior | Do not regard MQTT PUBACK as UAV acceptance unless the SDK proves it. |
| State reconciliation | fresh heartbeat/position/progress after a write | Telemetry/event evidence that is post-command and route-matched | Use the existing state-first command outcome model. |
| Safety action | BRAKE/RTL/LAND/disarm MAVLink mappings and state proof | Exact supported DJI hold/pause, return-home, land, cancel, emergency-stop semantics and permission gates | Create a capability matrix; never relabel a different DJI action as ARGUS RTL/abort. |
| Guided target and speed | global/local position target packets and command 178 | DJI waypoint/virtual-stick/guided API, command cadence, speed control, target-reached event | Separate one-shot route command from streamed control; establish safety/preemption behavior first. |
| Route/mission upload | MAVLink transfer, artifact readback, `MISSION_CURRENT` progress | DJI route/wayline/mission API, upload/start/abort/readback/version/progress behavior | Implement `MissionExecutor` only if semantic verification is possible; otherwise use ordered GUIDED capabilities. |
| Vehicle configuration | named `PARAM_*` read/write/readback | DJI configurable settings, permissions, readback, rollback | Optional capability; never use it for an assumed RTL-speed policy. |
| Active liveness | exact-token signed/framed `TIMESYNC` response | A device-originated challenge/response or equivalent explicit API proof | Define a new DJI liveness profile; do not reuse the MAVLink profile name or result. |
| Calibration | MAVLink magnetic-calibration command/progress/report | DJI calibration/session API and operator requirements | Optional capability, likely separate from basic flight integration. |
| Diagnostics and audit | bounded decoded packet observations and command traces | Redacted MQTT topic/event observations, broker/client diagnostics, SDK request/response evidence | Generalize the observation model without storing raw secrets or control payloads. |

## Proposed Adapter Contract (Design Direction, Not Yet a PRD)

The first implementation should use a small server-facing adapter contract,
not ROS as a mandatory new dependency and not a transport bridge that invents
missing DJI features. The GCS/server queues work to one selected adapter; each
adapter owns its transport connection and converts it to the contracts below.

| Contract | Minimum responsibilities | MAVLink implementation today | DJI MQTT implementation target |
|---|---|---|---|
| `VehicleIdentityProvider` | Discover identity, model/firmware, route, link identity, capability revision | ArduPilot heartbeat and autopilot version, MAVLink system/component route | DJI SDK device/gateway identifiers and observed capabilities. |
| `TelemetryProvider` | Emit frame-explicit, timestamped state observations with quality/provenance | `MavlinkState` plus telemetry projections | MQTT telemetry subscription and normalized mapper. |
| `CommandExecutor` | Accept semantic intent, return emission boundary, receipt, and reconciliation evidence | `MasterCommandSender` and response router | MQTT command/action publish plus SDK acceptance/event correlation. |
| `MissionExecutor` | Validate/upload/readback/start/progress or explicitly decline capability | `MasterMissionUploadSender` and native readback | DJI route/wayline adapter only if upload and verification are supported. |
| `SafetyExecutor` | Expose only documented hold/land/return/abort/disarm meanings and safe-terminal proof | Mode and command-long pathways plus heartbeat/landed proof | DJI-specific safety mapping with explicit unsupported states. |
| `ConfigurationProvider` | Capability discovery, read/write/readback, rollback evidence | `PARAM_*` and ArduPilot-specific collectors | Optional DJI settings adapter. |
| `LivenessProbe` | Protocol-specific positive active proof and freshness rules | exact-token `TIMESYNC` | Separate DJI proof, likely based on documented SDK/device response. |
| `ProtocolObserver` | Bounded redacted traffic/diagnostic records | MAVLink packet-observation producer | MQTT event/command observation producer. |

The domain-facing command input should stay close to existing ARGUS concepts:
`ensure_mode`, `arm`, `takeoff`, `set_speed`, `goto_global`, `goto_local`,
`hold`, `land`, `return_home`, `abort`, and mission/plan actions. Every result
needs three separate states:

1. **Emitted** — the adapter crossed its local publish/write boundary.
2. **Protocol accepted/rejected** — a vendor-defined receipt was correlated to
   that request when the protocol has one.
3. **State achieved/unknown** — fresh post-boundary telemetry proves the
   requested state, or the bridge admits it cannot prove it.

This preserves the current conservative safety doctrine and avoids the common
error of turning an MQTT QoS acknowledgement into a flight-state claim.

## Recommended Incremental Work

1. Capture the DJI SDK contract and a redacted flight transcript: MQTT topics,
   publish/subscribe direction, payload schemas, correlation IDs, QoS/retain
   behavior, device/gateway identity, source timestamps, command receipts,
   telemetry samples, and failure/reconnect cases.
2. Create a capability-and-semantics PRD using the comparison matrix above.
   It must classify each DJI operation as supported, unsupported, degraded, or
   unknown rather than assuming MAVLink parity.
3. Introduce generic `adapter`, `protocol`, and opaque route identity fields
   beside existing MAVLink fields. Preserve current MAVLink fields for current
   registered assets and migrations; do not rename persisted records in place.
4. Extract the domain result/evidence types from `MasterCommandSender` and
   mission/telemetry projections. Keep the MAVLink response router and all
   packet constants in a MAVLink bridge implementation.
5. Implement a **read-only DJI adapter first**: identity, link status,
   WGS84 telemetry, battery, flight state, packet/event observations, and
   reconnection behavior. Verify that it leaves MAVLink asset behavior
   unchanged.
6. Add one safety-reviewed DJI command at a time, beginning with the exact
   documented safety action and state evidence. Defer route upload, virtual
   stick, or streamed GUIDED control until control authority, rate, preemption,
   and loss-of-link semantics are verified.
7. Move simulator ROS bridge and explicit-endpoint backend adapters behind the
   same semantic adapter interfaces or formally deprecate them. Multiple
   independently evolving MAVLink command implementations are a long-term
   parity and safety risk.

## Open Questions for the DJI SDK Audit

- What is the canonical physical UAV ID, and does it survive gateway restart,
  reconnect, replacement aircraft, and controller handoff?
- Which MQTT messages are device-originated versus broker/client-originated?
  Does every command have a device-level correlation ID and final result?
- Are command messages idempotent, retained, replayable, ordered, and scoped
  to one UAV? What prevents a stale retained command from flying a vehicle?
- Which telemetry has source timestamps, and are they monotonic and tied to
  vehicle clock or gateway receive time?
- What geographic and altitude frames are exposed? Is there a documented home
  reference, local frame, and positive/negative vertical convention?
- Which exact modes and safety commands are supported on the intended DJI
  models, and what state/event proves that each action took effect?
- Is route upload supported? If yes, can ARGUS read it back by immutable
  version/hash and observe waypoint progress; if not, can an ordered target
  queue be executed safely?
- Does the SDK allow flight-critical configuration (such as return-home
  behavior/speed), and can it read back and restore configuration safely?
- What is the correct positive liveness proof when an MQTT broker connection
  remains alive but the aircraft/gateway path has failed?
- What authorization, operator login, SDK token, network isolation, and audit
  controls are required? Raw credentials, topics, and payloads must remain
  outside backend observations and task documents.

## Source Map

| Subject | Primary repository evidence |
|---|---|
| Primary MAVLink reducer and telemetry projection | `tools/sentinel_edge_link/mavlink_state.py`, `tools/sentinel_edge_link/mavlink_telemetry.py`, `tools/sentinel_edge_link/live_ingest_payloads.py` |
| Receive ownership, packet routing, and acknowledgement correlation | `tools/sentinel_gcs_link/mavlink_receive_context.py`, `tools/sentinel_gcs_link/mavlink_message_router.py`, `tools/sentinel_gcs_link/command_response_lanes.py` |
| Live command/send and GUIDED state evidence | `tools/sentinel_gcs_link/command_sender.py`, `docs/prd/guided_live_control_contract.md`, `docs/prd/guided_live_plan_queue_contract.md` |
| Native mission transfer/readback and artifact projection | `tools/sentinel_gcs_link/mission_upload_downlink.py`, `tools/sentinel_gcs_link/mission_read_downlink.py`, `swarm_backend/mission_artifacts.py`, `swarm_backend/mission_upload_adapter.py`, `docs/prd/curved_route_mission_contract.md` |
| GCS presence, failsafe configuration, stream profile, and active liveness | `tools/sentinel_gcs_link/gcs_heartbeat.py`, `tools/sentinel_gcs_link/stream_rates.py`, `tools/sentinel_gcs_link/rtl_speed_parameter.py`, `tools/sentinel_gcs_link/link_probe.py` |
| Compass calibration | `tools/sentinel_gcs_link/compass_calibration_downlink.py` |
| Bounded raw-protocol observations | `tools/sentinel_gcs_link/mavlink_observations.py`, `swarm_backend/gcs_mavlink_observation_repository.py`, `docs/prd/live_mavlink_packet_logs_tab.md` |
| Primary current boundary history | `ops/tasks/archive/TASK-S005-114-gcs-link-mavlink-packet-observation-producer.md`, `ops/tasks/archive/TASK-S005-004-gcs-guided-command-downlink.md`, `docs/prd/physical_uav_live_fleet_connection.md` |
| Simulator/direct compatibility paths | `swarm_ws/src/swarm_control/swarm_control/ardupilot_fleet_bridge.py`, `swarm_ws/src/swarm_control/swarm_control/ardupilot_square.py`, `swarm_backend/live_command_adapter.py` |
| Existing long-range multi-protocol direction | `docs/plans/03_MULTI_PROTOCOL_ARCHITECTURE.md` — useful historical direction only; it is not a current executable contract. |

## Audit Limitations

- This is a static source audit plus local constant verification, not a live
  radio, SITL, ArduPilot firmware, or DJI flight capture. Firmware can emit
  additional MAVLink message types; ARGUS currently records those only as
  bounded count-only observations unless explicit code consumes them.
- The audit covers non-test MAVLink send/receive code paths. Test harnesses
  deliberately generate extra frames to prove faults and are not a claim that
  the primary runtime sends those frames during operations.
- No DJI MQTT SDK source, message schema, or flight transcript was supplied,
  so the comparison matrix intentionally records required evidence instead of
  speculative mappings.
