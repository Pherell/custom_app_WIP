package com.dji.recreate2.gimbal

import android.util.Log
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.KeyManager

/**
 * Mechanical travel limits for the payload gimbal.
 *
 * Every gimbal command in the app must pass through [clampPitch] / [clampYaw]. Commanding an angle
 * outside the mechanical range makes the gimbal drive against its end stop, which the motors hold
 * against until the command changes — that is what overheats and damages them.
 *
 * The limits are read from the aircraft ([refresh]) so each airframe gets its own range. Until that
 * read succeeds the conservative defaults below apply. They are deliberately tighter than any DJI
 * gimbal so an unknown airframe cannot be driven into a stop.
 */
object GimbalLimits {

    private const val TAG = "GimbalLimits"

    // Conservative defaults. Narrower than the Mavic 3E and M30 ranges on purpose.
    private const val DEFAULT_PITCH_MIN = -85.0
    private const val DEFAULT_PITCH_MAX = 25.0
    private const val DEFAULT_YAW_MIN = -25.0
    private const val DEFAULT_YAW_MAX = 25.0

    /** Keeps commands clear of the physical end stop. */
    private const val SAFETY_MARGIN_DEG = 2.0

    @Volatile var pitchMin: Double = DEFAULT_PITCH_MIN
        private set
    @Volatile var pitchMax: Double = DEFAULT_PITCH_MAX
        private set
    @Volatile var yawMin: Double = DEFAULT_YAW_MIN
        private set
    @Volatile var yawMax: Double = DEFAULT_YAW_MAX
        private set

    /** True once the real range was read from the aircraft. */
    @Volatile var isFromAircraft: Boolean = false
        private set

    /** Largest yaw magnitude the gimbal can reach. The aircraft must turn beyond this. */
    val yawAuthorityDeg: Double
        get() = minOf(Math.abs(yawMin), Math.abs(yawMax))

    /**
     * Reads the mechanical range from the aircraft. Call after the aircraft connects.
     * Keeps the current values if the read fails.
     */
    fun refresh() {
        try {
            val key = KeyTools.createKey(GimbalKey.KeyGimbalAttitudeRange, ComponentIndexType.LEFT_OR_MAIN)
            val range = KeyManager.getInstance().getValue(key)
            if (range == null) {
                Log.w(TAG, "Gimbal attitude range unavailable; keeping ${describe()}")
                return
            }

            val p = range.pitch
            val y = range.yaw
            var changed = false

            val pMin = p?.min
            val pMax = p?.max
            if (pMin != null && pMax != null && pMax > pMin) {
                pitchMin = pMin + SAFETY_MARGIN_DEG
                pitchMax = pMax - SAFETY_MARGIN_DEG
                changed = true
            }

            val yMin = y?.min
            val yMax = y?.max
            if (yMin != null && yMax != null && yMax > yMin) {
                yawMin = yMin + SAFETY_MARGIN_DEG
                yawMax = yMax - SAFETY_MARGIN_DEG
                changed = true
            }

            if (changed) {
                isFromAircraft = true
                Log.d(TAG, "Gimbal range read from aircraft: ${describe()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the gimbal attitude range: ${e.message}")
        }
    }

    /** Limits a pitch command to the mechanical range. Returns 0.0 for a non-finite input. */
    fun clampPitch(pitchDeg: Double): Double {
        if (pitchDeg.isNaN() || pitchDeg.isInfinite()) return 0.0
        return pitchDeg.coerceIn(pitchMin, pitchMax)
    }

    /** Limits a yaw command to the mechanical range. Returns 0.0 for a non-finite input. */
    fun clampYaw(yawDeg: Double): Double {
        if (yawDeg.isNaN() || yawDeg.isInfinite()) return 0.0
        return yawDeg.coerceIn(yawMin, yawMax)
    }

    /**
     * Tells you how much aircraft rotation a yaw command needs.
     *
     * @param desiredYawDeg the yaw angle to the target, relative to the aircraft nose.
     * @return the part of the angle the gimbal cannot reach, in degrees. Zero when the gimbal can
     *         reach the target without help.
     */
    fun aircraftYawShortfall(desiredYawDeg: Double): Double {
        if (desiredYawDeg.isNaN() || desiredYawDeg.isInfinite()) return 0.0
        return desiredYawDeg - clampYaw(desiredYawDeg)
    }

    fun describe(): String =
        "pitch ${"%.1f".format(pitchMin)}..${"%.1f".format(pitchMax)}, " +
        "yaw ${"%.1f".format(yawMin)}..${"%.1f".format(yawMax)}" +
        if (isFromAircraft) " (from aircraft)" else " (default)"
}
