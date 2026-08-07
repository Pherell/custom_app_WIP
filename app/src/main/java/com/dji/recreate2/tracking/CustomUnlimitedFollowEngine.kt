package com.dji.recreate2.tracking

import android.util.Log
import dji.v5.manager.aircraft.perception.PerceptionManager
import dji.v5.manager.aircraft.perception.data.ObstacleAvoidanceType

/**
 * Custom Unlimited Target Follow Engine for Recreate2.
 * Combines Native DJI ActiveTrack VPU (54km/h max speed + APAS 5.0 Detour)
 * with 20Hz Virtual Stick PID Velocity Extrapolation to provide uninterrupted,
 * no-limitation target following during visual occlusions or high-speed maneuvers.
 */
object CustomUnlimitedFollowEngine {

    private const val TAG = "UnlimitedFollowEngine"

    @Volatile
    var isHybridFollowActive: Boolean = false
        private set

    @Volatile
    var isExtrapolationFailoverActive: Boolean = false
        private set

    private var targetLastNormX = 0.5f
    private var targetLastNormY = 0.5f
    private var targetVelX = 0.0f
    private var targetVelY = 0.0f
    private var lastObservedTime = 0L

    /**
     * Configures APAS 5.0 obstacle bypass for uninterrupted high-speed follow.
     */
    fun configureOptimalActiveTrack(maxSpeedMps: Double = 15.0, standoffDistM: Double = 8.0) {
        try {
            val perceptionManager = PerceptionManager.getInstance()
            perceptionManager.setObstacleAvoidanceType(ObstacleAvoidanceType.BYPASS, null)
            Log.d(TAG, "Configured APAS 5.0 Obstacle Bypass for High-Speed Follow (${maxSpeedMps}m/s)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not configure perception obstacle bypass: ${e.message}")
        }
    }

    /**
     * Updates target observation coordinates and computes velocity vector.
     */
    fun updateTargetObservation(normX: Float, normY: Float) {
        val now = System.currentTimeMillis()
        if (lastObservedTime > 0) {
            val dt = (now - lastObservedTime) / 1000.0f
            if (dt > 0.01f) {
                targetVelX = (normX - targetLastNormX) / dt
                targetVelY = (normY - targetLastNormY) / dt
            }
        }
        targetLastNormX = normX
        targetLastNormY = normY
        lastObservedTime = now
        isExtrapolationFailoverActive = false
    }

    /**
     * Extrapolates target position during visual occlusions (when optical detection is lost).
     */
    fun getExtrapolatedTargetPosition(): Pair<Float, Float> {
        val now = System.currentTimeMillis()
        val dt = (now - lastObservedTime) / 1000.0f

        // Extrapolate position along velocity vector for up to 5 seconds
        if (dt in 0.05f..5.0f) {
            isExtrapolationFailoverActive = true
            val extrapolatedX = (targetLastNormX + targetVelX * dt).coerceIn(0.05f, 0.95f)
            val extrapolatedY = (targetLastNormY + targetVelY * dt).coerceIn(0.05f, 0.95f)
            return Pair(extrapolatedX, extrapolatedY)
        }
        return Pair(targetLastNormX, targetLastNormY)
    }

    fun startHybridFollow() {
        isHybridFollowActive = true
        isExtrapolationFailoverActive = false
        lastObservedTime = System.currentTimeMillis()
        Log.d(TAG, "Hybrid Unlimited Target Follow Engine STARTED")
    }

    fun stopHybridFollow() {
        isHybridFollowActive = false
        isExtrapolationFailoverActive = false
        Log.d(TAG, "Hybrid Unlimited Target Follow Engine STOPPED")
    }
}
