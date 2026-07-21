package com.dji.recreate2

import android.util.Log
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.manager.KeyManager

data class DronePayloadState(
    var productType: ProductType = ProductType.UNKNOWN,
    var isEnterprise: Boolean = false,
    var availableLenses: List<CameraLensType> = listOf(),
    var isLrfSupported: Boolean = false
)

object PayloadDetectionManager {
    
    private const val TAG = "PayloadDetectMgr"
    val currentState = DronePayloadState()

    // Callbacks for UI updates
    var onPayloadDetected: ((DronePayloadState) -> Unit)? = null
    var onLrfDataUpdated: ((targetDistance: Float, targetLat: Double, targetLon: Double, targetAlt: Float) -> Unit)? = null

    // Reflection Caching to fix BUG-15 (High CPU Overhead)
    private var distanceMethod: java.lang.reflect.Method? = null
    private var latitudeMethod: java.lang.reflect.Method? = null
    private var longitudeMethod: java.lang.reflect.Method? = null

    fun detectCapabilities() {
        val keyManager = KeyManager.getInstance()
        
        // 1. Detect Product Type
        val productKey = KeyTools.createKey(ProductKey.KeyProductType)
        val type = keyManager.getValue(productKey)
        val name = type?.name ?: ""
        if (type != null) {
            currentState.productType = type
            // Fix BUG-13: Robust ProductType name matching for capability detection
            currentState.isEnterprise = name.contains("ENTERPRISE") || 
                                        name.contains("MATRICE") || 
                                        name.contains("M30")
        }

        // 2. Detect Available Lenses
        val supportedLenses = mutableListOf<CameraLensType>()
        supportedLenses.add(CameraLensType.CAMERA_LENS_WIDE) // Almost all drones have a wide lens
        
        // Check if ZOOM is supported
        val zoomKey = KeyTools.createCameraKey(CameraKey.KeyCameraType, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_ZOOM)
        val zoomVal = keyManager.getValue(zoomKey)
        val hasZoom = zoomVal != null || name.contains("M30") || name.contains("ENTERPRISE") || name.contains("MATRICE")
        if (hasZoom) {
            supportedLenses.add(CameraLensType.CAMERA_LENS_ZOOM)
        }
        
        // Check if THERMAL is supported
        val thermalKey = KeyTools.createCameraKey(CameraKey.KeyCameraType, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_THERMAL)
        val thermalVal = keyManager.getValue(thermalKey)
        val hasThermal = thermalVal != null || name.contains("M30") || name.contains("ENTERPRISE")
        if (hasThermal) {
            supportedLenses.add(CameraLensType.CAMERA_LENS_THERMAL)
        }
        
        currentState.availableLenses = supportedLenses

        // 3. Detect Laser Range Finder (LRF)
        val isLrfSupported = name.contains("M30") || name.contains("MATRICE") || name.contains("ENTERPRISE")
        currentState.isLrfSupported = isLrfSupported
        
        // Trigger UI Callback
        onPayloadDetected?.invoke(currentState)
        
        // If LRF is supported, start listening to it
        if (isLrfSupported) {
            listenToLrfData()
        }
    }
    
    fun setLaserMeasureEnabled(enabled: Boolean) {
        if (!currentState.isLrfSupported) return
        val lrfEnableKey = KeyTools.createCameraKey(CameraKey.KeyLaserMeasureEnabled, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_DEFAULT)
        KeyManager.getInstance().setValue(lrfEnableKey, enabled, object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
            override fun onSuccess() {}
            override fun onFailure(error: dji.v5.common.error.IDJIError) {}
        })
    }

    private fun listenToLrfData() {
        val lrfInfoKey = KeyTools.createCameraKey(CameraKey.KeyLaserMeasureInformation, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_DEFAULT)
        KeyManager.getInstance().listen(lrfInfoKey, this) { oldData: Any?, newData: Any? ->
            if (newData != null) {
                try {
                    // Fix BUG-15: Cached reflection calls to prevent UI frame drops
                    val cls = newData.javaClass
                    if (distanceMethod == null) distanceMethod = cls.getMethod("getDistance")
                    if (latitudeMethod == null) latitudeMethod = cls.getMethod("getLatitude")
                    if (longitudeMethod == null) longitudeMethod = cls.getMethod("getLongitude")
                    
                    val distance = distanceMethod?.invoke(newData) as? Float ?: 0f
                    val lat = latitudeMethod?.invoke(newData) as? Double ?: 0.0
                    val lon = longitudeMethod?.invoke(newData) as? Double ?: 0.0
                    val alt = 0f
                    
                    // Only dispatch if coordinates are non-zero (valid lock)
                    if (lat != 0.0 && lon != 0.0) {
                        onLrfDataUpdated?.invoke(distance, lat, lon, alt)
                    }
                } catch(e: Exception) {
                    // Fix BUG-14: Removed dangerous hardcoded Los Angeles coordinates fallback on failure
                    Log.e(TAG, "Gagal parse data LRF secara dinamis: ${e.message}")
                }
            }
        }
    }

    fun cleanup() {
        KeyManager.getInstance().cancelListen(this)
        distanceMethod = null
        latitudeMethod = null
        longitudeMethod = null
        onPayloadDetected = null
        onLrfDataUpdated = null
    }
}
