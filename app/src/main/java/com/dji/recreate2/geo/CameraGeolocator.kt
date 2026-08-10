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

        val ground = groundPoint(droneLat, droneLon, heightAboveTarget, losYaw, depression)
            ?: return GeoResult.Refused("The geometry does not give a target point.")

        return GeoResult.Fix(
            GeoFix(
                lat = ground.lat,
                lon = ground.lon,
                groundRangeM = ground.groundRangeM,
                slantRangeM = ground.slantRangeM,
                depressionDeg = depression,
                estimatedErrorM = estimatedErrorM(heightAboveTarget, depression, attitudeUncertaintyDeg)
            )
        )
    }

    /** Where one line of sight meets the ground plane. */
    private data class GroundHit(
        val lat: Double,
        val lon: Double,
        val groundRangeM: Double,
        val slantRangeM: Double
    )

    /**
     * Projects a single line of sight onto the ground plane.
     *
     * Shared by [locate] and [footprint] on purpose: a footprint drawn by different arithmetic
     * than the tap-to-designate path would disagree with it, and the operator would have no way
     * to tell which was right.
     *
     * @param depressionDeg how far below horizontal, positive.
     */
    private fun groundPoint(
        droneLat: Double,
        droneLon: Double,
        heightAboveTargetM: Double,
        losYawDeg: Double,
        depressionDeg: Double
    ): GroundHit? {
        if (depressionDeg <= 0.0) return null

        // Past vertical the ray has crossed nadir and is heading the other way. This is not an
        // edge case: looking straight down, every pixel below centre is past 90 degrees, so
        // without this a nadir view - the most common ISR camera position - produced no point at
        // all. Mirror the angle and turn the bearing around.
        var depression = depressionDeg
        var yaw = losYawDeg
        if (depression > 90.0) {
            depression = 180.0 - depression
            yaw = (yaw + 180.0) % 360.0
        }
        if (depression <= 0.0) return null

        val depressionRad = Math.toRadians(depression)
        val groundRange = heightAboveTargetM / Math.tan(depressionRad)
        val slantRange = heightAboveTargetM / Math.sin(depressionRad)
        if (!groundRange.isFinite() || groundRange < 0.0) return null

        val losYawRad = Math.toRadians(yaw)
        val (lat, lon) = GeoMath.offset(
            droneLat, droneLon,
            northMeters = groundRange * Math.cos(losYawRad),
            eastMeters = groundRange * Math.sin(losYawRad)
        )
        return GroundHit(lat, lon, groundRange, slantRange)
    }

    /** The ground the camera currently covers. */
    data class Footprint(
        /** Corners in draw order: near-left, near-right, far-right, far-left. */
        val corners: List<Pair<Double, Double>>,
        val centre: Pair<Double, Double>,
        /** Ground range to the nearest edge of the view. */
        val nearEdgeM: Double,
        /** Ground range to the farthest edge actually drawn. */
        val farEdgeM: Double,
        /**
         * True when the far edge ran past the usable depression and was clamped.
         *
         * **Draw a truncated edge differently.** The polygon then ends where the estimate stops
         * being trustworthy, not where the camera stops seeing. Filling it in solid would claim
         * coverage of ground the camera never usefully saw.
         */
        val truncated: Boolean
    )

    /**
     * The ground quadrilateral the camera covers.
     *
     * A footprint is not a rectangle. Looking down it is a trapezoid, and as the camera flattens
     * the far edge stretches and eventually runs to the horizon.
     *
     * @return null when the view is entirely at or above the horizon - there is no footprint to
     *         draw, and drawing one anyway would be an invention.
     */
    fun footprint(
        droneLat: Double,
        droneLon: Double,
        droneAltM: Double,
        droneYawDeg: Double,
        gimbalPitchDeg: Double,
        gimbalYawDeg: Double,
        halfFovHDeg: Double,
        halfFovVDeg: Double,
        targetElevationOffsetM: Double = 0.0,
        minDepressionDeg: Double = DEFAULT_MIN_DEPRESSION_DEG
    ): Footprint? {
        if (!droneLat.isFinite() || !droneLon.isFinite() || (droneLat == 0.0 && droneLon == 0.0)) return null
        if (!droneAltM.isFinite() || !droneYawDeg.isFinite() ||
            !gimbalPitchDeg.isFinite() || !gimbalYawDeg.isFinite()
        ) return null

        val height = droneAltM - targetElevationOffsetM
        if (height <= 1.0) return null

        // Screen Y grows downward, so y = 1 (bottom of the image) is the NEAR edge and y = 0 the
        // far edge. Draw order keeps the quadrilateral from self-intersecting.
        val imageCorners = listOf(
            0.0f to 1.0f,   // near-left
            1.0f to 1.0f,   // near-right
            1.0f to 0.0f,   // far-right
            0.0f to 0.0f    // far-left
        )

        var truncated = false
        val hits = ArrayList<GroundHit>(4)

        for ((nx, ny) in imageCorners) {
            val losYaw = GeoMathAngles.normalize360(
                droneYawDeg + gimbalYawDeg + pixelOffsetDeg(nx, halfFovHDeg)
            )
            val rawDepression = -(gimbalPitchDeg - pixelOffsetDeg(ny, halfFovVDeg))

            // Clamp a far corner that ran past the usable angle, and SAY SO. Clamping quietly
            // would draw a confident polygon over ground the camera never usefully saw.
            val depression = if (rawDepression < minDepressionDeg) {
                truncated = true
                minDepressionDeg
            } else {
                rawDepression
            }

            hits.add(groundPoint(droneLat, droneLon, height, losYaw, depression) ?: return null)
        }

        // The near edge is the BOTTOM of the image, y = 1 - the steepest look. If even that is
        // shallower than the usable angle then the whole view is beyond it and there is no
        // footprint worth drawing. (Testing the top corner here instead would refuse every
        // oblique view, because the top corner is the shallow one by definition.)
        val nearDepression = -(gimbalPitchDeg - pixelOffsetDeg(1.0f, halfFovVDeg))
        if (nearDepression < minDepressionDeg) return null

        val centreHit = run {
            val losYaw = GeoMathAngles.normalize360(droneYawDeg + gimbalYawDeg)
            val depression = -gimbalPitchDeg
            if (depression < minDepressionDeg) null
            else groundPoint(droneLat, droneLon, height, losYaw, depression)
        }

        return Footprint(
            corners = hits.map { it.lat to it.lon },
            centre = centreHit?.let { it.lat to it.lon }
                ?: (hits.map { it.lat }.average() to hits.map { it.lon }.average()),
            nearEdgeM = minOf(hits[0].groundRangeM, hits[1].groundRangeM),
            farEdgeM = maxOf(hits[2].groundRangeM, hits[3].groundRangeM),
            truncated = truncated
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
