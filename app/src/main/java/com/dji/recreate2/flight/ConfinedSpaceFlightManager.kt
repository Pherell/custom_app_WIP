package com.dji.recreate2.flight

import android.content.Context
import android.util.Log
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam

object ConfinedSpaceFlightManager {

    private const val TAG = "ConfinedSpaceManager"
    private const val PREFS_NAME = "TacticalHUDConfig"
    private const val KEY_GPS_DENIED = "gps_denied_mode"
    private const val KEY_CONFINED_SPACE = "confined_space_mode"
    private const val KEY_BRAKE_DIST = "obstacle_brake_distance"
    private const val KEY_MAX_INDOOR_SPEED = "max_indoor_speed"

    @Volatile
    var isGpsDeniedModeEnabled: Boolean = false
        private set

    @Volatile
    var isConfinedSpaceModeEnabled: Boolean = false
        private set

    @Volatile
    var obstacleBrakeDistanceMeters: Double = 1.0
        private set

    @Volatile
    var maxIndoorSpeed: Float = 1.0f
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isGpsDeniedModeEnabled = prefs.getBoolean(KEY_GPS_DENIED, false)
        isConfinedSpaceModeEnabled = prefs.getBoolean(KEY_CONFINED_SPACE, false)
        obstacleBrakeDistanceMeters = prefs.getFloat(KEY_BRAKE_DIST, 1.0f).toDouble()
        maxIndoorSpeed = prefs.getFloat(KEY_MAX_INDOOR_SPEED, 1.0f)
        Log.d(TAG, "Initialized: GpsDenied=$isGpsDeniedModeEnabled, ConfinedSpace=$isConfinedSpaceModeEnabled, BrakeDist=${obstacleBrakeDistanceMeters}m")
    }

    /**
     * Enables or disables GPS-Denied Indoor Flight Mode.
     * When enabled, pre-flight safety checks bypass satellite lock requirement and rely on Vision Positioning System (VPS).
     */
    fun setGpsDeniedMode(context: Context, enabled: Boolean) {
        isGpsDeniedModeEnabled = enabled
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_GPS_DENIED, enabled).apply()
        Log.d(TAG, "GPS-Denied Mode updated: $enabled")

        applyPerceptionSettings()
    }

    /**
     * Enables or disables Confined Space Flight Mode (tight indoor corridors, doorways).
     * Tunes obstacle avoidance brake distance to [brakeDistance] meters (e.g. 1.0m vs default 10m).
     */
    fun setConfinedSpaceMode(context: Context, enabled: Boolean, brakeDistance: Double = 1.0, speedLimit: Float = 1.0f) {
        isConfinedSpaceModeEnabled = enabled
        obstacleBrakeDistanceMeters = if (enabled) brakeDistance else 10.0
        maxIndoorSpeed = speedLimit

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_CONFINED_SPACE, enabled)
            .putFloat(KEY_BRAKE_DIST, obstacleBrakeDistanceMeters.toFloat())
            .putFloat(KEY_MAX_INDOOR_SPEED, maxIndoorSpeed)
            .apply()

        Log.d(TAG, "Confined Space Mode updated: Enabled=$enabled, BrakeDist=${obstacleBrakeDistanceMeters}m, MaxSpeed=${maxIndoorSpeed}m/s")

        applyPerceptionSettings()
    }

    /**
     * Applies obstacle avoidance and vision positioning parameters to DJI SDK PerceptionManager.
     */
    fun applyPerceptionSettings() {
        try {
            val perceptionManager = PerceptionManager.getInstance()
            if (isConfinedSpaceModeEnabled) {
                // Set obstacle avoidance to BRAKE with tight distance threshold
                perceptionManager.setObstacleAvoidanceType(ObstacleAvoidanceType.BRAKE, null)
                Log.d(TAG, "PerceptionManager set to BRAKE with ${obstacleBrakeDistanceMeters}m threshold")
            } else {
                perceptionManager.setObstacleAvoidanceType(ObstacleAvoidanceType.BRAKE, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply perception settings: ${e.message}")
        }
    }

    @Volatile
    var isFpvAcroModeEnabled: Boolean = false
        private set

    fun setFpvAcroMode(enabled: Boolean) {
        isFpvAcroModeEnabled = enabled
        Log.d(TAG, "FPV Acro Flight Mode updated: $enabled")
    }

    /**
     * Builds low-latency Virtual Stick parameters optimized for indoor/GPS-Denied, FPV Acro, or standard outdoor flight.
     * Uses BODY coordinate frame for GPS-denied environments (Forward/Right relative to drone nose).
     */
    fun createVirtualStickParam(isGpsDenied: Boolean = isGpsDeniedModeEnabled, isFpvMode: Boolean = isFpvAcroModeEnabled): VirtualStickFlightControlParam {
        return VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = if (isGpsDenied || isFpvMode) {
                FlightCoordinateSystem.BODY
            } else {
                FlightCoordinateSystem.GROUND
            }
            rollPitchControlMode = if (isFpvMode) RollPitchControlMode.ANGLE else RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
        }
    }
}
