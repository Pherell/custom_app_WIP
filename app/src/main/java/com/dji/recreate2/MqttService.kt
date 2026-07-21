package com.dji.recreate2

import android.content.Context
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.Arrays

class MqttService(context: Context) {

    private val appContext = context.applicationContext // Fix BUG-25: Prevent Context Memory Leak
    private var mqttClient: MqttClient? = null
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

    fun connect(serverUri: String, clientId: String = "drone_" + java.util.UUID.randomUUID().toString().substring(0,8)) {
        // M-08: cancel any pending connection attempt before starting a new one
        connectFuture?.cancel(false)
        connectFuture = executor.submit {
            synchronized(clientLock) {
                try {
                    if (mqttClient != null && mqttClient!!.isConnected) {
                        mqttClient!!.disconnect()
                    }

                    mqttClient = MqttClient(serverUri, clientId, MemoryPersistence())
                
                    // L-08: use EncryptedSharedPreferences so credentials are not stored in plaintext
                    val sharedPrefs = try {
                        val masterKey = androidx.security.crypto.MasterKey.Builder(appContext)
                            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                            .build()
                        androidx.security.crypto.EncryptedSharedPreferences.create(
                            appContext,
                            "TacticalHUDConfig",
                            masterKey,
                            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        )
                    } catch (e: Exception) {
                        Log.w(tag, "EncryptedSharedPreferences unavailable, falling back to plaintext: ${e.message}")
                        appContext.getSharedPreferences("TacticalHUDConfig", Context.MODE_PRIVATE)
                    }
                    val passStr = sharedPrefs.getString("mqttPass", "hgDnj1SPDKXZo2b") ?: "hgDnj1SPDKXZo2b"
                    val passChars = passStr.toCharArray()

                    val options = MqttConnectOptions().apply {
                        isCleanSession = true
                        connectionTimeout = 30 // Fix BUG-17: Brittle MQTT connection timeout (increased from 10s)
                        keepAliveInterval = 60 // Fix BUG-17: Brittle MQTT keep-alive (increased from 20s)
                        isAutomaticReconnect = true
                        userName = sharedPrefs.getString("mqttUser", "dji-sdk")
                        password = passChars
                    }

                    mqttClient?.setCallback(object : MqttCallbackExtended {
                        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                            Log.d(tag, "Connected to: $serverURI")
                            isConnected = true
                            onConnectionStatusChanged?.invoke(true)
                            
                            // Subscribe to command topic immediately after connecting
                            try {
                                synchronized(clientLock) {
                                    mqttClient?.subscribe("dji-sdk/fleet/$currentDroneId/command", 1)
                                    mqttClient?.subscribe("dji-sdk/fleet/broadcast/command", 1)
                                    mqttClient?.subscribe("dji-sdk/fleet/config", 1)
                                }
                                Log.d(tag, "Subscribed to command, broadcast, and config topics")
                            } catch (e: Exception) {
                                Log.e(tag, "Failed to subscribe", e)
                            }
                        }

                        override fun connectionLost(cause: Throwable?) {
                            Log.w(tag, "Connection lost", cause)
                            isConnected = false
                            onConnectionStatusChanged?.invoke(false)
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

                    Log.d(tag, "Connecting to MQTT broker: $serverUri")
                    mqttClient?.connect(options)
                    
                    // Fix BUG-18: Securely zero out raw password array in memory
                    Arrays.fill(passChars, '0')

                } catch (e: Exception) {
                    Log.e(tag, "Failed to connect to MQTT broker", e)
                    isConnected = false
                    onConnectionStatusChanged?.invoke(false)
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

    fun publishTelemetry(clientId: String = currentDroneId, jsonPayload: String) {
        if (!isConnected) return
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
                    }
                }
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

