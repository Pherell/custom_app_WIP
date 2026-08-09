package com.dji.recreate2.geo

/**
 * Works out where the camera is looking, on the ground.
 *
 * Without a laser rangefinder the app had no way to say where a target was: it could only record
 * where the AIRCRAFT was. This intersects the camera's line of sight with an assumed ground plane,
 * which is how a ground station geolocates from a picture when no rangefinder is fitted.
 *
 * **CAUTION: this is an estimate, not a measurement.** It assumes flat ground at a known
 * elevation. A rangefinder measures the real slant range and is always the better source when the
 * payload has one. Every result carries [GeoFix.estimatedErrorM] so a camera fix and a laser fix
 * are never confused on a target list.
 *
 * Pure geometry: no Context, no views, no SDK types.
 */
object CameraGeolocator {

    /** Below this depression the intersection is too sensitive to be useful. */
    const val DEFAULT_MIN_DEPRESSION_DEG = 15.0

    /** Assumed combined uncertainty in gimbal attitude and aircraft heading, in degrees. */
    const val DEFAULT_ATTITUDE_UNCERTAINTY_DEG = 1.0

    /** A geolocated point and how much to trust it. */
    data class GeoFix(
        val lat: Double,
        val lon: Double,
        /** Horizontal distance from the aircraft to the target, in metres. */
        val groundRangeM: Double,
        /** Straight-line distance from the aircraft to the target, in metres. */
        val slantRangeM: Double,
        /** How far below horizontal the camera is looking, in degrees. Always positive. */
        val depressionDeg: Double,
        /** Radius of the likely error, in metres. Grows quickly as the camera flattens. */
        val estimatedErrorM: Double
    )

    sealed class GeoResult {
        data class Fix(val fix: GeoFix) : GeoResult()

        /** No usable intersection. [reason] is written for the operator, not the log. */
        data class Refused(val reason: String) : GeoResult()
    }

    /**
     * Intersects the line of sight through a pixel with the ground plane.
     *
     * @param droneAltM aircraft height above the TAKEOFF point, which is what
     *        `FlightControllerKey.KeyAltitude` reports. Not height above sea level and not height
     *        above the ground under the aircraft.
     * @param gimbalYawDeg gimbal pan relative to the airframe. Added to [droneYawDeg] to give the
     *        true bearing, the same convention the AR marker and the targeting pod use.
     * @param normX horizontal position in the VISIBLE image, 0 at the left edge, 1 at the right.
     * @param normY vertical position in the VISIBLE image, 0 at the top, 1 at the bottom.
     * @param halfFovHDeg,halfFovVDeg visible half-angles from
     *        `CameraProjection.effectiveHalfFovDeg`, so the CENTER_CROP is already accounted for.
     * @param targetElevationOffsetM target elevation relative to the takeoff point. Negative when
     *        the target is below the launch site.
     */
    fun locate(
        droneLat: Double,
        droneLon: Double,
        droneAltM: Double,
        droneYawDeg: Double,
        gimbalPitchDeg: Double,
        gimbalYawDeg: Double,
        normX: Float,
        normY: Float,
        halfFovHDeg: Double,
        halfFovVDeg: Double,
        targetElevationOffsetM: Double = 0.0,
        minDepressionDeg: Double = DEFAULT_MIN_DEPRESSION_DEG,
        attitudeUncertaintyDeg: Double = DEFAULT_ATTITUDE_UNCERTAINTY_DEG
    ): GeoResult {

        if (!droneLat.isFinite() || !droneLon.isFinite() || (droneLat == 0.0 && droneLon == 0.0)) {
            return GeoResult.Refused("No aircraft position.")
        }
        if (!droneAltM.isFinite() || !droneYawDeg.isFinite() ||
            !gimbalPitchDeg.isFinite() || !gimbalYawDeg.isFinite()
        ) {
            return GeoResult.Refused("Aircraft altitude or attitude is not available.")
        }

        val heightAboveTarget = droneAltM - targetElevationOffsetM
        if (heightAboveTarget <= 1.0) {
            return GeoResult.Refused("The aircraft is not above the target height.")
        }

        // Pixel to angle off the boresight. Screen Y grows downward, so a pixel below centre is a
        // steeper look-down and therefore a MORE negative pitch.
        val deltaYaw = pixelOffsetDeg(normX, halfFovHDeg)
        val deltaPitch = -pixelOffsetDeg(normY, halfFovVDeg)

        val losYaw = GeoMathAngles.normalize360(droneYawDeg + gimbalYawDeg + deltaYaw)
        val losPitch = gimbalPitchDeg + deltaPitch

        val depression = -losPitch
        if (depression < minDepressionDeg) {
            return if (depression <= 0.0) {
                GeoResult.Refused("The camera is looking at or above the horizon.")
            } else {
                GeoResult.Refused(
                    "The camera is too shallow: ${"%.0f".format(depression)}°, " +
                            "minimum ${"%.0f".format(minDepressionDeg)}°."
                )
            }
        }

        val depressionRad = Math.toRadians(depression)
        val groundRange = heightAboveTarget / Math.tan(depressionRad)
        val slantRange = heightAboveTarget / Math.sin(depressionRad)
        if (!groundRange.isFinite() || groundRange < 0.0) {
            return GeoResult.Refused("The geometry does not give a target point.")
        }

        val losYawRad = Math.toRadians(losYaw)
        val (lat, lon) = GeoMath.offset(
            droneLat, droneLon,
            northMeters = groundRange * Math.cos(losYawRad),
            eastMeters = groundRange * Math.sin(losYawRad)
        )

        return GeoResult.Fix(
            GeoFix(
                lat = lat,
                lon = lon,
                groundRangeM = groundRange,
                slantRangeM = slantRange,
                depressionDeg = depression,
                estimatedErrorM = estimatedErrorM(heightAboveTarget, depression, attitudeUncertaintyDeg)
            )
        )
    }

    /**
     * Angle from the boresight to a normalised image position, in degrees.
     *
     * A camera is a tangent projection, so this is NOT `(2n - 1) * halfFov`. It is the exact
     * inverse of `CameraProjection.angleToScreen`, and using the linear form instead is the same
     * error that put the AR home marker in the wrong place.
     */
    fun pixelOffsetDeg(norm: Float, halfFovDeg: Double): Double {
        val half = halfFovDeg.coerceIn(0.1, 88.0)
        val fromCentre = (2.0 * norm - 1.0).coerceIn(-1.0, 1.0)
        return Math.toDegrees(Math.atan(fromCentre * Math.tan(Math.toRadians(half))))
    }

    /**
     * Radius of the likely horizontal error, in metres.
     *
     * The ground range is `h / tan(d)`, so its sensitivity to a pointing error is
     * `dR/dd = h / sin^2(d)`. That is why a shallow look is so much worse than a steep one: at
     * 100 m height a one-degree error is about 3.5 m at 45 degrees, 13 m at 25 degrees and over
     * 55 m at 10 degrees.
     *
     * The bearing contributes a second, smaller term across the line of sight.
     */
    fun estimatedErrorM(
        heightM: Double,
        depressionDeg: Double,
        attitudeUncertaintyDeg: Double = DEFAULT_ATTITUDE_UNCERTAINTY_DEG
    ): Double {
        val d = Math.toRadians(depressionDeg.coerceIn(0.5, 90.0))
        val sigmaRad = Math.toRadians(Math.abs(attitudeUncertaintyDeg))
        val sinD = Math.sin(d)

        // Along the line of sight: the range error from a pitch error.
        val alongTrack = heightM / (sinD * sinD) * sigmaRad
        // Across it: the arc a bearing error sweeps at that range.
        val groundRange = heightM / Math.tan(d)
        val acrossTrack = groundRange * sigmaRad

        return Math.hypot(alongTrack, acrossTrack)
    }

    /** Angle helpers kept private to this file so [GeoMath] stays about distance. */
    private object GeoMathAngles {
        fun normalize360(deg: Double): Double = ((deg % 360) + 360) % 360
    }
}
