package com.dji.recreate2.mapping

import com.dji.recreate2.geo.GeoMath
import com.dji.recreate2.gimbal.CameraProjection

/**
 * Survey grid, orbit ring and velocity-frame maths.
 *
 * These calculations used to live inside `MainActivity`, mixed with EditText reads and map overlay
 * updates, so nothing could check them. That is where the `vFov = hFov * 9/16` error survived a
 * correction elsewhere and shipped a second time, and where the front and side overlap were both
 * derived from the horizontal swath.
 *
 * Everything here is pure: no Context, no views, no SDK calls. The caller reads the parameters
 * from the interface, calls these functions and wraps the result into waypoints.
 */
object SurveyGrid {

    /** Distance each survey line runs past the polygon edge, so the turn happens outside the area. */
    const val OVERSHOOT_METERS = 15.0

    /** Points used to approximate one orbit revolution. */
    const val ORBIT_POINTS_PER_LOOP = 12

    /** A plain latitude/longitude pair. Keeps this file independent of the map library. */
    data class GridPoint(val lat: Double, val lon: Double)

    /**
     * @param altitudeM       flight altitude above the survey area.
     * @param overlapPercent  requested image overlap, 0..100.
     * @param cameraHFovDeg   the camera's HORIZONTAL field of view.
     * @param frameAspectHOverW frame height divided by frame width, for example 1080/1920.
     * @param crosshatch      true to add a second pass at right angles to the first.
     */
    data class GridParams(
        val altitudeM: Double,
        val overlapPercent: Double,
        val cameraHFovDeg: Double,
        val frameAspectHOverW: Double,
        val crosshatch: Boolean
    )

    // ------------------------------------------------------------------ swath

    /**
     * Ground width the camera covers ACROSS the direction of travel, in metres.
     *
     * Across-track uses the HORIZONTAL field of view, because the aircraft flies with the wide
     * axis of the frame perpendicular to the line.
     */
    fun sideSwathMeters(params: GridParams): Double =
        2.0 * params.altitudeM * Math.tan(Math.toRadians(params.cameraHFovDeg.coerceIn(1.0, 179.0) / 2.0))

    /**
     * Ground length the camera covers ALONG the direction of travel, in metres.
     *
     * Along-track uses the VERTICAL field of view. Both used to be derived from the horizontal
     * swath, so on a non-square sensor the real forward overlap did not match the request.
     */
    fun frontSwathMeters(params: GridParams): Double {
        val vFov = CameraProjection.verticalFovDeg(params.cameraHFovDeg, params.frameAspectHOverW)
        return 2.0 * params.altitudeM * Math.tan(Math.toRadians(vFov / 2.0))
    }

    /** The part of a swath that is NEW ground. 70 percent overlap leaves 0.30. */
    fun advanceFraction(overlapPercent: Double): Double =
        (1.0 - (overlapPercent / 100.0)).coerceIn(0.05, 1.0)

    /** Distance between adjacent survey lines, in metres. */
    fun lineSpacingMeters(params: GridParams): Double =
        sideSwathMeters(params) * advanceFraction(params.overlapPercent)

    /** Distance between photo positions along a line, in metres. */
    fun photoIntervalMeters(params: GridParams): Double =
        (frontSwathMeters(params) * advanceFraction(params.overlapPercent)).coerceAtLeast(1.0)

    // ------------------------------------------------------------------ grid

    /**
     * Photo positions covering [polygon], in FLIGHT ORDER.
     *
     * The order is the flight path: each line runs opposite to the one before it, so the aircraft
     * turns at the end of a line instead of returning to the same side. A caller that sorts or
     * de-duplicates this list destroys the path.
     *
     * Returns an empty list for a polygon with fewer than three vertices or a spacing that would
     * not converge. The caller decides what to tell the operator.
     */
    fun generate(polygon: List<GridPoint>, params: GridParams): List<GridPoint> {
        if (polygon.size < 3) return emptyList()

        val lineSpacing = lineSpacingMeters(params)
        val photoInterval = photoIntervalMeters(params)
        if (lineSpacing < 1.0) return emptyList()

        val minLat = polygon.minOf { it.lat }
        val maxLat = polygon.maxOf { it.lat }
        val minLon = polygon.minOf { it.lon }
        val maxLon = polygon.maxOf { it.lon }
        val midLat = (minLat + maxLat) / 2.0

        val latStep = GeoMath.degreesLat(lineSpacing, midLat)
        val lonStep = GeoMath.degreesLon(lineSpacing, midLat)
        if (latStep <= 0.0 || lonStep <= 0.0) return emptyList()

        val out = mutableListOf<GridPoint>()

        // Pass 1: lines of constant latitude, flown east-west.
        var leftToRight = true
        var lat = minLat + latStep / 2.0
        while (lat <= maxLat) {
            val crossings = horizontalCrossings(polygon, lat)
            for (i in 0 until crossings.size - 1 step 2) {
                val overshoot = GeoMath.degreesLon(OVERSHOOT_METERS, lat)
                val from = crossings[i] - overshoot
                val to = crossings[i + 1] + overshoot
                val spanMeters = Math.abs(GeoMath.metersFromLon(to - from, lat))

                for (p in samplePositions(spanMeters, photoInterval, from, to, leftToRight)) {
                    out.add(GridPoint(lat, p))
                }
            }
            if (crossings.size >= 2) leftToRight = !leftToRight
            lat += latStep
        }

        // Pass 2: lines of constant longitude, flown north-south.
        if (params.crosshatch) {
            var topToBottom = true
            var lon = minLon + lonStep / 2.0
            while (lon <= maxLon) {
                val crossings = verticalCrossings(polygon, lon)
                for (i in 0 until crossings.size - 1 step 2) {
                    val overshoot = GeoMath.degreesLat(OVERSHOOT_METERS, midLat)
                    val from = crossings[i] - overshoot
                    val to = crossings[i + 1] + overshoot
                    val spanMeters = Math.abs(GeoMath.metersFromLat(to - from, midLat))

                    for (p in samplePositions(spanMeters, photoInterval, from, to, !topToBottom)) {
                        out.add(GridPoint(p, lon))
                    }
                }
                if (crossings.size >= 2) topToBottom = !topToBottom
                lon += lonStep
            }
        }

        return out
    }

    /**
     * Evenly spaced values from [from] to [to], close enough together to hold [photoInterval].
     *
     * [forward] false walks the line from [to] back to [from], which is what makes consecutive
     * lines alternate direction.
     */
    private fun samplePositions(
        spanMeters: Double,
        photoInterval: Double,
        from: Double,
        to: Double,
        forward: Boolean
    ): List<Double> {
        val count = Math.max(2, Math.ceil(spanMeters / photoInterval).toInt() + 1)
        val step = (to - from) / (count - 1)
        return (0 until count).map { j ->
            if (forward) from + j * step else to - j * step
        }
    }

    /** Longitudes where the polygon edges cross the parallel at [lat], sorted west to east. */
    private fun horizontalCrossings(polygon: List<GridPoint>, lat: Double): List<Double> {
        val out = mutableListOf<Double>()
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            // Half-open test: a vertex exactly on the line counts once, so the crossings stay
            // paired and a concave polygon fills only the inside of the notch.
            if ((a.lat <= lat && b.lat > lat) || (b.lat <= lat && a.lat > lat)) {
                val f = (lat - a.lat) / (b.lat - a.lat)
                out.add(a.lon + f * (b.lon - a.lon))
            }
        }
        out.sort()
        return out
    }

    /** Latitudes where the polygon edges cross the meridian at [lon], sorted south to north. */
    private fun verticalCrossings(polygon: List<GridPoint>, lon: Double): List<Double> {
        val out = mutableListOf<Double>()
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if ((a.lon <= lon && b.lon > lon) || (b.lon <= lon && a.lon > lon)) {
                val f = (lon - a.lon) / (b.lon - a.lon)
                out.add(a.lat + f * (b.lat - a.lat))
            }
        }
        out.sort()
        return out
    }

    // ------------------------------------------------------------------ orbit

    /**
     * Positions around a circle of [radiusM] about the centre, in flight order.
     *
     * The first point of each loop is due north of the centre and the ring turns clockwise.
     *
     * **NOTE: this is an inscribed polygon, not a circle.** With the default
     * [ORBIT_POINTS_PER_LOOP] the path passes about `r * (1 - cos(180/n))` inside the requested
     * radius at the middle of each side - roughly 1 m at 30 m. Raise [points] if the standoff
     * distance matters.
     */
    fun orbitRing(
        centerLat: Double,
        centerLon: Double,
        radiusM: Double,
        points: Int = ORBIT_POINTS_PER_LOOP,
        loops: Int = 1
    ): List<GridPoint> {
        if (radiusM <= 0.0 || points < 3 || loops < 1) return emptyList()

        val out = mutableListOf<GridPoint>()
        for (loop in 0 until loops) {
            for (i in 0 until points) {
                val angle = Math.toRadians(i * 360.0 / points)
                val (lat, lon) = GeoMath.offset(
                    centerLat, centerLon,
                    northMeters = radiusM * Math.cos(angle),
                    eastMeters = radiusM * Math.sin(angle)
                )
                out.add(GridPoint(lat, lon))
            }
        }
        return out
    }

    // ------------------------------------------------------------------ velocity frame

    /**
     * Resolves a ground bearing and speed into the roll and pitch axes of a Virtual Stick param.
     *
     * Takes a plain flag rather than the SDK's `FlightCoordinateSystem` so this file stays free of
     * DJI types and can run on the host JVM.
     *
     * @param bodyFrame true puts pitch on the nose axis and roll on the right axis, so the bearing
     *        is taken relative to [droneYawDeg]. False (GROUND) puts pitch on North, roll on East.
     * @return (pitch, roll) in metres per second.
     */
    fun velocityComponents(
        groundBearingDeg: Double,
        droneYawDeg: Double,
        horizontalSpeed: Double,
        bodyFrame: Boolean
    ): Pair<Double, Double> {
        val axisRad = if (bodyFrame) {
            Math.toRadians(normalizeDeg(groundBearingDeg - droneYawDeg))
        } else {
            Math.toRadians(groundBearingDeg)
        }
        return (horizontalSpeed * Math.cos(axisRad)) to (horizontalSpeed * Math.sin(axisRad))
    }

    /** Folds an angle into -180..180. */
    fun normalizeDeg(deg: Double): Double = ((deg % 360) + 540) % 360 - 180
}
