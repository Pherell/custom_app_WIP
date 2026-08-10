package com.dji.recreate2.flight

import android.content.Context
import android.util.Log
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType
import dji.v5.manager.aircraft.perception.data.PerceptionDirection
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
            perceptionManager.setObstacleAvoidanceType(ObstacleAvoidanceType.BRAKE, null)

            // Previously both branches did only the line above, so enabling Confined Space
            // Mode changed nothing on the aircraft: obstacleBrakeDistanceMeters was persisted
            // to SharedPreferences and never pushed to the SDK. Actually apply it now.
            val brakeDist = obstacleBrakeDistanceMeters
            val warnDist = (brakeDist * 2.0).coerceAtLeast(brakeDist + 0.5)

            for (direction in listOf(
                PerceptionDirection.HORIZONTAL,
                PerceptionDirection.UPWARD,
                PerceptionDirection.DOWNWARD
            )) {
                perceptionManager.setObstacleAvoidanceBrakingDistance(brakeDist, direction, null)
                perceptionManager.setObstacleAvoidanceWarningDistance(warnDist, direction, null)
            }

            Log.d(TAG, "PerceptionManager set to BRAKE (confinedSpace=$isConfinedSpaceModeEnabled, " +
                    "brake=${brakeDist}m, warn=${warnDist}m)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply perception settings: ${e.message}")
        }
    }

    /**
     * Builds the Virtual Stick parameters for AUTONOMOUS flight - the mission engine and the
     * targeting-pod yaw assist. Both write metres per second and degrees per second, so roll and
     * pitch are always [RollPitchControlMode.VELOCITY].
     *
     * **WARNING: Do not add an [RollPitchControlMode.ANGLE] branch here.** An earlier "FPV Acro"
     * setting did exactly that. ANGLE reads roll and pitch as an attitude in DEGREES, while every
     * caller of this function writes a SPEED in metres per second into the same fields. A 12 m/s
     * waypoint speed reached the aircraft as 12 degrees of tilt, and ANGLE mode holds that tilt
     * with no speed regulation, so the aircraft accelerated until it passed the waypoint. The
     * setting never reached the manual sticks, which use classic (non-advanced) mode, so its only
     * effect was to break autonomous flight.
     *
     * GPS-denied flight selects the BODY frame, where pitch is the nose axis and roll is the right
     * axis. `MainActivity.applyVelocitySetpoint` resolves a ground bearing into either frame.
     */
    fun createVirtualStickParam(isGpsDenied: Boolean = isGpsDeniedModeEnabled): VirtualStickFlightControlParam {
        return VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = if (isGpsDenied) {
                FlightCoordinateSystem.BODY
            } else {
                FlightCoordinateSystem.GROUND
            }
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
        }
    }
}
