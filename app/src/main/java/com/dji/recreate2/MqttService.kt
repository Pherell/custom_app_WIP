package com.dji.recreate2

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

class MqttService(context: Context) {

    private val appContext = context.applicationContext // Fix BUG-25: Prevent Context Memory Leak
    @Volatile private var mqttClient: MqttClient? = null
    private val tag = "MqttService"
    
    @Volatile
    var isConnected = false
    private var currentDroneId = "drone_alpha_01"
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val clientLock = Any() // Fix BUG-16: Thread Safety lock for client operations
    // M-08: track latest connect future to cancel if connect() called again before finishing
    @Volatile private var connectFuture: java.util.concurrent.Future<*>? = null
    
    // Callbacks to communicate back to MainActivity
    var onCommandReceived: ((JSONObject) -> Unit)? = null
    var onConnectionStatusChanged: ((Boolean) -> Unit)? = null
    var onErrorOccurred: ((String) -> Unit)? = null

    fun connect(serverUri: String, clientId: String = "drone_" + java.util.UUID.randomUUID().toString().substring(0,8)) {
        // M-08: cancel any pending connection attempt before starting a new one
        connectFuture?.cancel(false)
        connectFuture = executor.submit {
            synchronized(clientLock) {
                var cleanUri = serverUri.trim()
                if (!cleanUri.startsWith("tcp://") && !cleanUri.startsWith("ssl://") && 
                    !cleanUri.startsWith("ws://") && !cleanUri.startsWith("wss://")) {
                    cleanUri = "tcp://$cleanUri"
                }
                
                try {
                    if (mqttClient != null && mqttClient!!.isConnected) {
                        try { mqttClient!!.disconnect() } catch (e: Exception) {}
                    }

                    mqttClient = MqttClient(cleanUri, clientId, MemoryPersistence())
                
                    val sharedPrefs = appContext.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
                    val passStr = sharedPrefs.getString("mqttPass", "password") ?: "password"
                    val userStr = sharedPrefs.getString("mqttUser", "admin") ?: "admin"
                    val passChars = passStr.toCharArray()

                    val options = MqttConnectOptions().apply {
                        isCleanSession = true
                        connectionTimeout = 30 // Fix BUG-17: Brittle MQTT connection timeout (increased from 10s)
                        keepAliveInterval = 60 // Fix BUG-17: Brittle MQTT keep-alive (increased from 20s)
                        isAutomaticReconnect = true
                        if (userStr.isNotEmpty()) userName = userStr
                        if (passStr.isNotEmpty()) password = passChars
                    }

                    mqttClient?.setCallback(object : MqttCallbackExtended {
                        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                            Log.d(tag, "Connected to: $serverURI")
                            isConnected = true
                            onConnectionStatusChanged?.invoke(true)
                            
                            // Subscribe to command topic immediately after connecting.
                            // Deliberately NOT under clientLock: this runs on Paho's callback
                            // thread while the executor thread may still be inside the blocking
                            // connect() holding that lock, which would stall Paho's own
                            // callback dispatch. mqttClient is @Volatile for safe publication.
                            try {
                                mqttClient?.subscribe("dji-sdk/fleet/$currentDroneId/command", 1)
                                mqttClient?.subscribe("dji-sdk/fleet/broadcast/command", 1)
                                mqttClient?.subscribe("dji-sdk/fleet/config", 1)
                                Log.d(tag, "Subscribed to command, broadcast, and config topics")
                            } catch (e: Exception) {
                                Log.e(tag, "Failed to subscribe", e)
                            }

                            // Close the gap the outage left in the C2 track.
                            try {
                                replayBufferedTelemetry(currentDroneId)
                            } catch (e: Exception) {
                                Log.e(tag, "Failed to start telemetry replay", e)
                            }
                        }

                        override fun connectionLost(cause: Throwable?) {
                            val causeMsg = cause?.localizedMessage ?: cause?.message ?: "Unknown connection loss"
                            Log.w(tag, "Connection lost: $causeMsg", cause)
                            isConnected = false
                            onConnectionStatusChanged?.invoke(false)
                            onErrorOccurred?.invoke("Connection Lost: $causeMsg")
                        }

                        override fun messageArrived(topic: String?, message: MqttMessage?) {
                            message?.let {
                                val payload = String(it.payload)
                                Log.d(tag, "Message received on $topic: $payload")
                                try {
                                    val json = JSONObject(payload)
                                    // H-03: offload to executor so Paho internal thread is not blocked
                                    // by runOnUiThread calls in MainActivity's handleMqttCommand
                                    executor.submit { onCommandReceived?.invoke(json) }
                                } catch (e: Exception) {
                                    Log.e(tag, "Failed to parse incoming command", e)
                                }
                            }
                        }

                        override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                    })

                    Log.d(tag, "Connecting to MQTT broker: $cleanUri")
                    mqttClient?.connect(options)

                    // NOTE: do NOT zero passChars here. MqttConnectOptions.setPassword() stores
                    // the char[] BY REFERENCE, and isAutomaticReconnect = true makes Paho reuse
                    // this same options object for every reconnect. Wiping the array (the old
                    // "Fix BUG-18") made every reconnect authenticate with "0000..." and be
                    // rejected, permanently killing the link until app restart. The security
                    // value was nil anyway - the password is already stored in plaintext in
                    // SharedPreferences ("mqttPass", read above).

                } catch (e: Exception) {
                    val errorMsg = e.localizedMessage ?: e.message ?: e.toString()
                    Log.e(tag, "Failed to connect to MQTT broker ($cleanUri): $errorMsg", e)
                    isConnected = false
                    onConnectionStatusChanged?.invoke(false)
                    onErrorOccurred?.invoke(errorMsg)
                }
            }
        }
    }
    
    fun updateDroneId(newDroneId: String) {
        executor.submit {
            synchronized(clientLock) {
                if (currentDroneId != newDroneId) {
                    val oldTopic = "dji-sdk/fleet/$currentDroneId/command"
                    val newTopic = "dji-sdk/fleet/$newDroneId/command"
                    currentDroneId = newDroneId
                    if (isConnected) {
                        try {
                            mqttClient?.unsubscribe(oldTopic)
                            mqttClient?.subscribe(newTopic, 1)
                            Log.d(tag, "Switched command subscription to: $newTopic")
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to update drone ID command subscription to: $newTopic", e)
                            onConnectionStatusChanged?.invoke(false)
                        }
                    }
                }
            }
        }
    }

    /**
     * Holds telemetry while the broker is unreachable, so a reconnect can backfill the track.
     * Refer to [com.dji.recreate2.telemetry.TelemetryBuffer].
     */
    private val telemetryBuffer = com.dji.recreate2.telemetry.TelemetryBuffer()

    /** Frames released per drain pass, so catching up does not starve live telemetry. */
    private val REPLAY_BATCH = 50

    /** Frames held while the link is down. */
    val bufferedTelemetryCount: Int get() = telemetryBuffer.size

    /** Frames lost to a full buffer. A gap the operator should know about. */
    val droppedTelemetryCount: Long get() = telemetryBuffer.droppedCount

    fun publishTelemetry(clientId: String = currentDroneId, jsonPayload: String) {
        // Previously this returned here and the frame was gone for good. Hold it instead.
        if (!isConnected) {
            telemetryBuffer.add(jsonPayload)
            return
        }
        executor.submit {
            synchronized(clientLock) {
                if (mqttClient?.isConnected == true) {
                    try {
                        val topic = "dji-sdk/fleet/$clientId/telemetry"
                        val message = MqttMessage(jsonPayload.toByteArray())
                        message.qos = 0 // QoS 0 for high-frequency telemetry (fire and forget)
                        mqttClient?.publish(topic, message)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to publish telemetry", e)
                        telemetryBuffer.add(jsonPayload)
                    }
                } else {
                    telemetryBuffer.add(jsonPayload)
                }
            }
        }
    }

    /**
     * Sends held frames after a reconnect, oldest first.
     *
     * Replayed frames go at **QoS 1**: the whole point is to close a gap, so the catch-up must not
     * be lossy the way live telemetry deliberately is. They keep their ORIGINAL timestamps, and
     * the server stores `data.timestamp` rather than arrival time, so the track is drawn where the
     * aircraft actually was.
     *
     * **A held frame must not carry a `type` field.** The server routes position frames on its
     * absence; a frame with one would be treated as a target report and never stored.
     */
    private fun replayBufferedTelemetry(clientId: String) {
        if (telemetryBuffer.isEmpty) return
        val held = telemetryBuffer.size
        val dropped = telemetryBuffer.droppedCount
        Log.d(tag, "Replaying $held buffered telemetry frames (dropped $dropped while offline)")

        executor.submit {
            while (true) {
                val batch = telemetryBuffer.drain(REPLAY_BATCH)
                if (batch.isEmpty()) break

                val failed = mutableListOf<com.dji.recreate2.telemetry.TelemetryBuffer.Frame>()
                synchronized(clientLock) {
                    if (mqttClient?.isConnected != true) {
                        failed.addAll(batch)
                    } else {
                        val topic = "dji-sdk/fleet/$clientId/telemetry"
                        for (frame in batch) {
                            try {
                                val message = MqttMessage(frame.payload.toByteArray())
                                message.qos = 1
                                mqttClient?.publish(topic, message)
                            } catch (e: Exception) {
                                Log.w(tag, "Replay failed, requeueing the rest", e)
                                failed.add(frame)
                            }
                        }
                    }
                }
                if (failed.isNotEmpty()) {
                    telemetryBuffer.requeueFront(failed)
                    break
                }
                // Let live telemetry through between batches.
                try { Thread.sleep(50) } catch (e: InterruptedException) { break }
            }
        }
    }
    
    fun publishMission(clientId: String = currentDroneId, jsonPayload: String) {
        executor.submit {
            synchronized(clientLock) {
                if (mqttClient?.isConnected == true) {
                    try {
                        val topic = "dji-sdk/fleet/$clientId/mission"
                        val message = MqttMessage(jsonPayload.toByteArray())
                        message.qos = 1 // QoS 1 for mission data to ensure delivery
                        mqttClient?.publish(topic, message)
                        Log.d(tag, "Published mission payload to $topic")
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to publish mission", e)
                    }
                }
            }
        }
    }

    fun disconnect() {
        executor.submit {
            synchronized(clientLock) {
                try {
                    if (mqttClient?.isConnected == true) {
                        mqttClient?.disconnect()
                    }
                    isConnected = false
                    onConnectionStatusChanged?.invoke(false)
                } catch (e: Exception) {
                    Log.e(tag, "Failed to disconnect", e)
                }
            }
        }
    }

    fun destroy() {
        executor.submit {
            synchronized(clientLock) {
                try {
                    if (mqttClient?.isConnected == true) {
                        mqttClient?.disconnect()
                    }
                    isConnected = false
                    onConnectionStatusChanged?.invoke(false)
                } catch (e: Exception) {
                    Log.e(tag, "Failed to disconnect during destroy", e)
                }
            }
        }
        executor.shutdown()
    }
}

