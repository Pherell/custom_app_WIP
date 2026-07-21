package com.dji.recreate2

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import dji.v5.manager.KeyManager
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.PairingState
import dji.v5.common.callback.CommonCallbacks
import dji.sdk.keyvalue.value.common.EmptyMsg

class HomeActivity : AppCompatActivity() {

    private val REQUIRED_PERMISSION_LIST: Array<String>
        get() {
            val list = mutableListOf(
                Manifest.permission.VIBRATE,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.WAKE_LOCK,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.READ_PHONE_STATE
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                list.add(Manifest.permission.BLUETOOTH_SCAN)
                list.add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                list.add(Manifest.permission.BLUETOOTH)
                list.add(Manifest.permission.BLUETOOTH_ADMIN)
            }
            return list.toTypedArray()
        }
    private val REQUEST_PERMISSION_CODE = 12345

    private lateinit var tvStatus: TextView
    private lateinit var btnFlyMode: TextView
    private lateinit var btnPair: TextView
    private lateinit var btnServerSettings: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        tvStatus = findViewById(R.id.tvStatus)
        btnFlyMode = findViewById(R.id.btnFlyMode)
        btnPair = findViewById(R.id.btnPair)
        btnServerSettings = findViewById(R.id.btnServerSettings)

        btnFlyMode.visibility = View.GONE

        btnFlyMode.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        btnPair.setOnClickListener {
            togglePairing()
        }
        
        btnServerSettings.setOnClickListener {
            val sharedPrefs = getSharedPreferences("TacticalHUDConfig", android.content.Context.MODE_PRIVATE)
            val currentIp = sharedPrefs.getString("mqttServerIp", "127.0.0.1")
            val currentPort = sharedPrefs.getString("mqttServerPort", "1883")
            
            val layout = android.widget.LinearLayout(this)
            layout.orientation = android.widget.LinearLayout.VERTICAL
            layout.setPadding(50, 40, 50, 10)
            
            val ipInput = android.widget.EditText(this)
            ipInput.hint = "Server IP Address"
            ipInput.setText(currentIp)
            layout.addView(ipInput)
            
            val portInput = android.widget.EditText(this)
            portInput.hint = "Port (e.g. 1883)"
            portInput.setText(currentPort)
            portInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layout.addView(portInput)
            
            android.app.AlertDialog.Builder(this)
                .setTitle("C2 Server Config")
                .setView(layout)
                .setPositiveButton("SAVE") { _, _ ->
                    val newIp = ipInput.text.toString()
                    val newPort = portInput.text.toString()
                    val fullAddress = "tcp://$newIp:$newPort"
                    
                    sharedPrefs.edit()
                        .putString("mqttServerIp", newIp)
                        .putString("mqttServerPort", newPort)
                        .putString("mqttServerAddress", fullAddress)
                        .apply()
                        
                    android.widget.Toast.makeText(this, "Saved: $fullAddress", android.widget.Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("CANCEL", null)
                .show()
        }

        tvStatus.text = "STATUS: CHECKING PERMISSIONS..."
        
        // Check permissions before SDK Initialization
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val missingPermissions = mutableListOf<String>()
        for (eachPermission in REQUIRED_PERMISSION_LIST) {
            if (ContextCompat.checkSelfPermission(this, eachPermission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(eachPermission)
            }
        }
        
        if (missingPermissions.isEmpty()) {
            tvStatus.text = "STATUS: INITIALIZING SDK..."
            initDJISDK()
        } else {
            tvStatus.text = "STATUS: WAITING FOR PERMISSIONS..."
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_CODE) {
            // Android 13+ will silently deny WRITE_EXTERNAL_STORAGE, so we only strictly require LOCATION
            val locationGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            
            if (locationGranted) {
                tvStatus.text = "STATUS: INITIALIZING SDK..."
                initDJISDK()
            } else {
                tvStatus.text = "STATUS: PERMISSIONS DENIED. APP CANNOT RUN."
                android.widget.Toast.makeText(this, "Please grant Location permission in Settings to fly.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initDJISDK() {
        SDKManager.getInstance().init(this, object : SDKManagerCallback {
            override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
                runOnUiThread { tvStatus.text = "STATUS: SDK INIT (${event.name})" }
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                    runOnUiThread { tvStatus.text = "STATUS: REGISTERING APP..." }
                    SDKManager.getInstance().registerApp()
                }
            }

            override fun onRegisterSuccess() {
                runOnUiThread {
                    tvStatus.text = "STATUS: SDK REGISTERED. READY FOR FLIGHT."
                    btnFlyMode.visibility = View.VISIBLE
                }
            }

            override fun onRegisterFailure(error: IDJIError) {
                runOnUiThread { tvStatus.text = "STATUS: REGISTRATION FAILED (${error.errorCode()})" }
            }

            override fun onProductDisconnect(productId: Int) {
                Log.d("HomeActivity", "Product Disconnected: $productId")
            }
            
            override fun onProductConnect(productId: Int) {
                Log.d("HomeActivity", "Product Connected: $productId")
            }
            
            override fun onProductChanged(productId: Int) { }
            override fun onDatabaseDownloadProgress(current: Long, total: Long) { }
        })
    }

    private fun togglePairing() {
        val pairingStatusKey = KeyTools.createKey(RemoteControllerKey.KeyPairingStatus)
        KeyManager.getInstance().getValue(pairingStatusKey, object : CommonCallbacks.CompletionCallbackWithParam<PairingState> {
            override fun onSuccess(state: PairingState?) {
                if (state == PairingState.PAIRING) {
                    stopPairing()
                } else {
                    startPairing()
                }
            }
            override fun onFailure(error: IDJIError) {
                runOnUiThread { tvStatus.text = "STATUS: PAIRING CHECK FAILED (${error.errorCode()})" }
                startPairing()
            }
        })
    }

    private fun startPairing() {
        val pairKey = KeyTools.createKey(RemoteControllerKey.KeyRequestPairing)
        KeyManager.getInstance().performAction(pairKey, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(p0: EmptyMsg?) {
                runOnUiThread {
                    tvStatus.text = "STATUS: RC LINKING ACTIVE...\n(Press Link button on RC)"
                    btnPair.text = "[ STOP LINKING ]"
                }
            }
            override fun onFailure(error: IDJIError) {
                runOnUiThread {
                    tvStatus.text = "STATUS: LINKING INITIATION FAILED (${error.errorCode()})"
                }
            }
        })
    }

    private fun stopPairing() {
        val stopKey = KeyTools.createKey(RemoteControllerKey.KeyStopPairing)
        KeyManager.getInstance().performAction(stopKey, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(p0: EmptyMsg?) {
                runOnUiThread {
                    tvStatus.text = "STATUS: RC LINKING STOPPED."
                    btnPair.text = "[ INITIATE RC LINKING ]"
                }
            }
            override fun onFailure(error: IDJIError) {
                runOnUiThread {
                    tvStatus.text = "STATUS: FAILED TO STOP LINKING (${error.errorCode()})"
                }
            }
        })
    }
}
