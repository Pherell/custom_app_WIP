package com.dji.recreate2.tracking

import android.util.Log
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
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
     * Obstacle avoidance mode that was in force before this engine switched to BYPASS.
     * Null means there is nothing to restore (never switched, or already restored).
     */
    @Volatile
    private var savedObstacleAvoidanceType: ObstacleAvoidanceType? = null

    /**
     * Configures APAS 5.0 obstacle bypass for uninterrupted high-speed follow.
     *
     * The previous avoidance mode is captured first so [stopHybridFollow] can put it back.
     * Without that, a single tap on FOLLOW left the aircraft in BYPASS for the remainder of
     * the session — including during subsequent waypoint missions.
     *
     * NOTE: [maxSpeedMps] and [standoffDistM] are advisory only; MSDK v5 exposes no API here
     * to set an ActiveTrack speed ceiling or standoff distance, so they are logged, not applied.
     */
    fun configureOptimalActiveTrack(maxSpeedMps: Double = 15.0, standoffDistM: Double = 8.0) {
        try {
            val perceptionManager = PerceptionManager.getInstance()

            if (savedObstacleAvoidanceType != null) {
                // Already captured on a previous start; just re-apply BYPASS.
                applyBypass(maxSpeedMps, standoffDistM)
                return
            }

            perceptionManager.getObstacleAvoidanceType(object : CommonCallbacks.CompletionCallbackWithParam<ObstacleAvoidanceType> {
                override fun onSuccess(type: ObstacleAvoidanceType?) {
                    // Never memorise BYPASS as the "previous" mode, or we would restore to it.
                    savedObstacleAvoidanceType =
                        if (type != null && type != ObstacleAvoidanceType.BYPASS) type else ObstacleAvoidanceType.BRAKE
                    applyBypass(maxSpeedMps, standoffDistM)
                }

                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "Could not read current obstacle avoidance type (${error.description()}); will restore to BRAKE on stop.")
                    savedObstacleAvoidanceType = ObstacleAvoidanceType.BRAKE
                    applyBypass(maxSpeedMps, standoffDistM)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not configure perception obstacle bypass: ${e.message}")
        }
    }

    private fun applyBypass(maxSpeedMps: Double, standoffDistM: Double) {
        try {
            PerceptionManager.getInstance().setObstacleAvoidanceType(ObstacleAvoidanceType.BYPASS, null)
            Log.d(TAG, "Configured APAS 5.0 Obstacle Bypass for High-Speed Follow " +
                    "(requested ${maxSpeedMps}m/s, standoff ${standoffDistM}m; previous mode=$savedObstacleAvoidanceType)")
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply obstacle bypass: ${e.message}")
        }
    }

    /**
     * Puts the obstacle avoidance mode back to whatever was active before the follow engine
     * forced BYPASS. Safe to call repeatedly — it is a no-op once restored.
     */
    private fun restoreObstacleAvoidance() {
        val previous = savedObstacleAvoidanceType ?: return
        savedObstacleAvoidanceType = null
        try {
            PerceptionManager.getInstance().setObstacleAvoidanceType(previous, object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "Restored obstacle avoidance type to $previous")
                }
                override fun onFailure(error: IDJIError) {
                    Log.w(TAG, "Failed to restore obstacle avoidance type to $previous: ${error.description()}")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Could not restore obstacle avoidance: ${e.message}")
        }
    }

    /**
     * Updates target observation coordinates and computes velocity vector with EMA smoothing.
     */
    fun updateTargetObservation(normX: Float, normY: Float) {
        val now = System.currentTimeMillis()
        if (lastObservedTime > 0) {
            val dt = (now - lastObservedTime) / 1000.0f
            if (dt > 0.01f) {
                val instVelX = (normX - targetLastNormX) / dt
                val instVelY = (normY - targetLastNormY) / dt
                
                // Fix: Apply Exponential Moving Average (EMA) smoothing to prevent zero-velocity drops on duplicate frames
                if (targetVelX == 0.0f && targetVelY == 0.0f) {
                    targetVelX = instVelX
                    targetVelY = instVelY
                } else if (instVelX != 0.0f || instVelY != 0.0f) {
                    targetVelX = 0.6f * targetVelX + 0.4f * instVelX
                    targetVelY = 0.6f * targetVelY + 0.4f * instVelY
                }
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
        restoreObstacleAvoidance()
        Log.d(TAG, "Hybrid Unlimited Target Follow Engine STOPPED")
    }
}
