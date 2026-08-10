package com.dji.recreate2

import android.util.Log
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.camera.LaserMeasureInformation
import dji.sdk.keyvalue.value.camera.LaserMeasureState
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.manager.KeyManager

data class DronePayloadState(
    var productType: ProductType = ProductType.UNKNOWN,
    var isEnterprise: Boolean = false,
    var availableLenses: List<CameraLensType> = listOf(),
    /** The video sources the payload reports. This is what the lens buttons switch between. */
    var availableSources: List<CameraVideoStreamSourceType> = listOf(),
    var isLrfSupported: Boolean = false
)

object PayloadDetectionManager {
    
    private const val TAG = "PayloadDetectMgr"
    val currentState = DronePayloadState()

    // Callbacks for UI updates
    var onPayloadDetected: ((DronePayloadState) -> Unit)? = null

    /**
     * Fires only for a reading the laser reports as NORMAL with a usable coordinate.
     * Distance and altitude are metres. The SDK types are `Double`, not `Float` - the old
     * signature was wrong about both.
     */
    var onLrfDataUpdated: ((targetDistance: Double, targetLat: Double, targetLon: Double, targetAlt: Double) -> Unit)? = null

    /** Reasons the last laser reading was refused, for the log. */
    var lastLrfRejectReason: String? = null
        private set

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
        //
        // The aircraft reports its own lenses. KeyCameraVideoStreamSourceRange is the list the
        // payload actually has, and it is the same enum the lens SWITCH takes, so detection and
        // switching cannot disagree.
        //
        // This used to probe KeyCameraType per lens and fall back to matching the product name
        // against "M30", "ENTERPRISE" and "MATRICE". That missed M350_RTK and the M200_V2 series
        // entirely - both enterprise airframes whose names contain none of those three - so an
        // M350 lost its zoom button, its thermal button and its rangefinder.
        // A camera-level key, not a lens-level one, so createKey rather than createCameraKey.
        val sourceRange = keyManager.getValue(
            KeyTools.createKey(CameraKey.KeyCameraVideoStreamSourceRange, ComponentIndexType.LEFT_OR_MAIN)
        ) ?: emptyList()
        currentState.availableSources = sourceRange

        val supportedLenses = mutableListOf<CameraLensType>()
        supportedLenses.add(CameraLensType.CAMERA_LENS_WIDE) // Every supported payload has one.
        if (sourceRange.contains(CameraVideoStreamSourceType.ZOOM_CAMERA)) {
            supportedLenses.add(CameraLensType.CAMERA_LENS_ZOOM)
        }
        if (sourceRange.contains(CameraVideoStreamSourceType.INFRARED_CAMERA)) {
            supportedLenses.add(CameraLensType.CAMERA_LENS_THERMAL)
        }
        currentState.availableLenses = supportedLenses

        // 3. Detect Laser Range Finder (LRF)
        //
        // Ask the aircraft whether the key exists rather than guessing from the product name.
        // The name test stays as a fallback for the case where the key has not been populated
        // yet, but it no longer decides on its own.
        val lrfProbe = keyManager.getValue(
            KeyTools.createCameraKey(
                CameraKey.KeyLaserMeasureInformation, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_DEFAULT
            )
        )
        val isLrfSupported = lrfProbe != null ||
                name.contains("M30") || name.contains("M350") || name.contains("M200") ||
                name.contains("MATRICE") || name.contains("ENTERPRISE")
        currentState.isLrfSupported = isLrfSupported

        Log.d(TAG, "Payload: $name lenses=$supportedLenses sources=$sourceRange lrf=$isLrfSupported")
        
        // Trigger UI Callback
        onPayloadDetected?.invoke(currentState)
        
        // If LRF is supported, start listening to it
        if (isLrfSupported) {
            listenToLrfData()
        }
    }
    
    /**
     * Switches the camera to [source].
     *
     * The WIDE, ZOOM and IR buttons used to call `showToast("Wide Lens Selected")` and nothing
     * else. No lens-switch code existed anywhere in the app, so the message was never true.
     *
     * [onResult] reports what the AIRCRAFT did, not what was asked. Do not tell the operator the
     * lens changed before this reports success - that is the rule the old ARM/DISARM defect broke.
     */
    fun setVideoStreamSource(
        source: CameraVideoStreamSourceType,
        onResult: (success: Boolean, message: String) -> Unit
    ) {
        if (currentState.availableSources.isNotEmpty() && !currentState.availableSources.contains(source)) {
            onResult(false, "This payload has no ${lensLabel(source)} lens.")
            return
        }
        val key = KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, ComponentIndexType.LEFT_OR_MAIN)
        KeyManager.getInstance().setValue(key, source, object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.d(TAG, "Video stream source is now $source")
                onResult(true, "${lensLabel(source)} lens selected")
            }
            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                Log.e(TAG, "Could not select $source: ${error.description()}")
                onResult(false, "Lens change refused: ${error.description()}")
            }
        })
    }

    private fun lensLabel(source: CameraVideoStreamSourceType): String = when (source) {
        CameraVideoStreamSourceType.WIDE_CAMERA -> "Wide"
        CameraVideoStreamSourceType.ZOOM_CAMERA -> "Zoom"
        CameraVideoStreamSourceType.INFRARED_CAMERA -> "IR"
        else -> source.name
    }

    fun setLaserMeasureEnabled(enabled: Boolean) {
        if (!currentState.isLrfSupported) return
        val lrfEnableKey = KeyTools.createCameraKey(CameraKey.KeyLaserMeasureEnabled, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_DEFAULT)
        KeyManager.getInstance().setValue(lrfEnableKey, enabled, object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
            override fun onSuccess() {}
            override fun onFailure(error: dji.v5.common.error.IDJIError) {}
        })
    }

    /**
     * Listens to the laser rangefinder.
     *
     * **This used to read the value by reflection, calling `getLatitude()` and `getLongitude()` on
     * [LaserMeasureInformation]. That class has neither.** The coordinate lives on its
     * `location3D` member. Every reading therefore threw `NoSuchMethodException` into a catch
     * block that only wrote to the log, so [onLrfDataUpdated] never fired once: no distance on
     * screen, no coordinate, and no `lrf_target` message to the C2 server.
     *
     * The type is in the SDK library at compile time. Reflection was never needed.
     */
    private fun listenToLrfData() {
        val lrfInfoKey = KeyTools.createCameraKey(
            CameraKey.KeyLaserMeasureInformation, ComponentIndexType.LEFT_OR_MAIN, CameraLensType.CAMERA_LENS_DEFAULT
        )
        KeyManager.getInstance().listen(lrfInfoKey, this) { _: LaserMeasureInformation?, info: LaserMeasureInformation? ->
            if (info == null) return@listen

            // The laser reports why it could not range: TOO_CLOSE, TOO_FAR, NO_SIGNAL,
            // OUT_OF_RANGE. Acting on a reading in any of those states puts a target marker
            // somewhere the laser never reached.
            val state = info.laserMeasureState
            if (state != null && state != LaserMeasureState.NORMAL) {
                if (lastLrfRejectReason != state.name) {
                    lastLrfRejectReason = state.name
                    Log.d(TAG, "Laser reading refused: $state")
                }
                return@listen
            }

            val location = info.location3D
            val lat = location?.latitude ?: return@listen
            val lon = location?.longitude ?: return@listen
            val alt = location.altitude ?: 0.0
            val distance = info.distance ?: 0.0

            // 0,0 is the null island, which the payload reports before it has a fix.
            if (!lat.isFinite() || !lon.isFinite() || (lat == 0.0 && lon == 0.0)) {
                lastLrfRejectReason = "NO_FIX"
                return@listen
            }

            lastLrfRejectReason = null
            onLrfDataUpdated?.invoke(distance, lat, lon, if (alt.isFinite()) alt else 0.0)
        }
    }

    fun cleanup() {
        KeyManager.getInstance().cancelListen(this)
        lastLrfRejectReason = null
        onPayloadDetected = null
        onLrfDataUpdated = null
    }
}
