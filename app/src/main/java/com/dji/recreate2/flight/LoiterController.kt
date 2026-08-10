package com.dji.recreate2.flight

import com.dji.recreate2.geo.GeoMath

/**
 * Holds the aircraft on a circle around a target with the camera slaved to it.
 *
 * The app could already orbit a point, but only as a PLANNED route: place a waypoint, set a radius
 * and a loop count, and it becomes waypoints. Two things that makes impossible, and this fixes:
 *
 *  - **Loitering on a target you just found.** A laser fix, a camera fix or a live pod lock could
 *    not become an orbit without going back to the map and planning one.
 *  - **Correct gimbal pitch.** The planned orbit bakes `atan2(-alt, groundDist)` into each
 *    waypoint at generation time. `towardPOI` keeps the NOSE on the target in flight, but nothing
 *    corrects the pitch, so any altitude change leaves the camera at the wrong depression for the
 *    rest of the orbit. [step] recomputes it every tick from live altitude and range.
 *
 * This is a control law holding a circle, not a chase of the next waypoint. That is what lets the
 * standoff be changed while flying, and it is why the radius error feeds a velocity rather than a
 * new set of waypoints.
 *
 * **WARNING: this decides setpoints. It does not send them, and it holds no authority.** Every
 * interlock - stick authority, pilot override, link loss, refusing to start - lives at the call
 * site, where the aircraft state is known. Refer to the loiter thread in MainActivity.
 *
 * Pure arithmetic: no Context, no views, no SDK types.
 */
object LoiterController {

    /** Radial correction gain, m/s per metre of radius error. */
    private const val RADIAL_GAIN = 0.5

    /** Ceiling on the radial correction so a big error does not fly straight at the target. */
    private const val MAX_RADIAL_SPEED_MPS = 4.0

    /** Vertical gain, m/s per metre of altitude error. */
    private const val VERTICAL_GAIN = 0.5
    private const val MAX_VERTICAL_SPEED_MPS = 2.0

    /** Yaw gain, deg/s per degree of heading error, and its ceiling. */
    private const val YAW_GAIN = 1.5
    private const val MAX_YAW_RATE_DPS = 45.0

    /** Inside this range the bearing to the target is noise and the geometry degenerates. */
    const val MIN_USABLE_RANGE_M = 3.0

    /** Smallest orbit worth flying. Below this the aircraft cannot hold the circle. */
    const val MIN_RADIUS_M = 10.0

    /** Below this height a loiter is refused outright. */
    const val MIN_ALTITUDE_M = 15.0

    data class LoiterState(
        val targetLat: Double,
        val targetLon: Double,
        /** Target height above the take-off point. Used for the gimbal depression. */
        val targetAltM: Double,
        val radiusM: Double,
        /** Height above the take-off point to hold. */
        val altitudeM: Double,
        val clockwise: Boolean = true
    )

    data class LoiterCommand(
        /** Ground track to fly, degrees true. */
        val groundBearingDeg: Double,
        val horizontalSpeedMps: Double,
        val verticalSpeedMps: Double,
        val yawRateDegPerSec: Double,
        /** Recomputed every tick, already inside the mechanical range the caller passed. */
        val gimbalPitchDeg: Double,
        /** Gimbal pan relative to the nose. */
        val gimbalYawDeg: Double,
        /** Current distance to the target. */
        val rangeM: Double,
        /** Signed: positive means further out than the wanted radius. */
        val radiusErrorM: Double
    )

    /**
     * One tick of the orbit.
     *
     * A pure function of state and position: no threads, no sleeps, no accumulated controller
     * state. That is what makes a whole orbit testable without an aircraft.
     *
     * @param tangentialSpeedMps speed along the circle. The caller sets it from the mission speed.
     * @param clampPitch the mechanical pitch limiter, injected so the controller does not depend
     *        on the SDK. Pass `GimbalLimits::clampPitch`.
     * @param clampYaw as above for pan.
     * @return null when the inputs cannot produce a sane command - the caller must then hold, not
     *         guess.
     */
    fun step(
        state: LoiterState,
        droneLat: Double,
        droneLon: Double,
        droneAltM: Double,
        droneYawDeg: Double,
        tangentialSpeedMps: Double,
        clampPitch: (Double) -> Double = { it },
        clampYaw: (Double) -> Double = { it }
    ): LoiterCommand? {
        if (!droneLat.isFinite() || !droneLon.isFinite() || !droneAltM.isFinite() ||
            !droneYawDeg.isFinite() || !state.targetLat.isFinite() || !state.targetLon.isFinite()
        ) return null
        if (state.radiusM < MIN_RADIUS_M || tangentialSpeedMps <= 0.0) return null

        val (north, east) = GeoMath.toNorthEast(droneLat, droneLon, state.targetLat, state.targetLon)
        val range = Math.hypot(north, east)

        // Over the top of the target the bearing flips wildly and the orbit geometry has no
        // meaning. Report it and let the caller hold rather than command a spin.
        if (range < MIN_USABLE_RANGE_M) return null

        val bearingToTarget = normalize360(Math.toDegrees(Math.atan2(east, north)))

        // Tangential: 90 degrees off the line to the target. Clockwise seen from above means the
        // aircraft leads to the RIGHT of the target bearing.
        val tangentialHeading = normalize360(
            if (state.clockwise) bearingToTarget - 90.0 else bearingToTarget + 90.0
        )

        // Radial: positive error means too far out, so fly toward the target.
        val radiusError = range - state.radiusM
        val radialSpeed = (Math.abs(radiusError) * RADIAL_GAIN).coerceAtMost(MAX_RADIAL_SPEED_MPS)
        val radialHeading = if (radiusError > 0) bearingToTarget else normalize360(bearingToTarget + 180.0)

        // Sum the two as vectors. Doing it this way, rather than switching between "correct the
        // radius" and "go round", is what keeps the track a circle instead of a polygon.
        val tRad = Math.toRadians(tangentialHeading)
        val rRad = Math.toRadians(radialHeading)
        val vNorth = tangentialSpeedMps * Math.cos(tRad) + radialSpeed * Math.cos(rRad)
        val vEast = tangentialSpeedMps * Math.sin(tRad) + radialSpeed * Math.sin(rRad)

        val groundBearing = normalize360(Math.toDegrees(Math.atan2(vEast, vNorth)))
        val horizontalSpeed = Math.hypot(vNorth, vEast)

        val verticalSpeed = ((state.altitudeM - droneAltM) * VERTICAL_GAIN)
            .coerceIn(-MAX_VERTICAL_SPEED_MPS, MAX_VERTICAL_SPEED_MPS)

        // Gimbal, recomputed from LIVE altitude and range. This is the fix for the planned orbit's
        // frozen pitch.
        val heightAboveTarget = droneAltM - state.targetAltM
        val rawPitch = Math.toDegrees(Math.atan2(-heightAboveTarget, range))
        val gimbalPitch = clampPitch(rawPitch)

        // Keep the nose on the target; the gimbal covers what the airframe has not turned yet.
        val yawError = shortestAngle(bearingToTarget - droneYawDeg)
        val yawRate = (yawError * YAW_GAIN).coerceIn(-MAX_YAW_RATE_DPS, MAX_YAW_RATE_DPS)
        val gimbalYaw = clampYaw(yawError)

        return LoiterCommand(
            groundBearingDeg = groundBearing,
            horizontalSpeedMps = horizontalSpeed,
            verticalSpeedMps = verticalSpeed,
            yawRateDegPerSec = yawRate,
            gimbalPitchDeg = gimbalPitch,
            gimbalYawDeg = gimbalYaw,
            rangeM = range,
            radiusErrorM = radiusError
        )
    }

    /** Why a loiter cannot start, or null when it can. */
    fun rejectReason(
        targetLat: Double,
        targetLon: Double,
        radiusM: Double,
        altitudeM: Double
    ): String? = when {
        !targetLat.isFinite() || !targetLon.isFinite() ||
                (targetLat == 0.0 && targetLon == 0.0) ->
            "No target. Fire the laser, tap a target, or lock the pod first."
        radiusM < MIN_RADIUS_M ->
            "Loiter radius must be at least ${MIN_RADIUS_M.toInt()} m."
        altitudeM < MIN_ALTITUDE_M ->
            "Loiter altitude must be at least ${MIN_ALTITUDE_M.toInt()} m."
        else -> null
    }

    private fun normalize360(deg: Double): Double = ((deg % 360) + 360) % 360

    /** Signed difference in [-180, 180). */
    private fun shortestAngle(deg: Double): Double = ((deg % 360) + 540) % 360 - 180
}
