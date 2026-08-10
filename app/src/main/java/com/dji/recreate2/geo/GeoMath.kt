package com.dji.recreate2.geo

/**
 * Local conversions between metres and degrees of latitude/longitude.
 *
 * The app previously did this in two different ways:
 *
 *  - Orbit generation, the orbit map overlay and the survey grid used a flat 111320.0 for BOTH
 *    axes. For longitude that constant is wrong even as an approximation.
 *  - Dead reckoning and the targeting-pod geo-lock used 111132.92 and 111412.84, which are the
 *    correct leading terms.
 *
 * Two answers to the same question, and the cruder one drove survey line spacing. This object is
 * the single source: the standard first-order series, accurate to about a centimetre per degree,
 * against roughly 1.7 m per kilometre of error from the flat 111320.
 *
 * These are LOCAL-TANGENT conversions. They are correct for the few hundred metres to few
 * kilometres a mission covers. For a true distance between two points use
 * [android.location.Location.distanceBetween], and for a destination from a bearing use a
 * great-circle formula — this object does not replace either.
 */
object GeoMath {

    /**
     * Metres per degree of latitude at [latDeg].
     *
     * Latitude degrees are not constant: about 110 574 m at the equator and 111 694 m at the
     * poles, because the Earth is an oblate spheroid.
     */
    fun metersPerDegreeLat(latDeg: Double): Double {
        val lat = Math.toRadians(safeLat(latDeg))
        return 111132.92 -
                559.82 * Math.cos(2 * lat) +
                1.175 * Math.cos(4 * lat) -
                0.0023 * Math.cos(6 * lat)
    }

    /**
     * Metres per degree of longitude at [latDeg].
     *
     * Longitude degrees shrink toward the poles. The result is floored so a caller dividing by it
     * cannot produce infinity at extreme latitudes.
     */
    fun metersPerDegreeLon(latDeg: Double): Double {
        val lat = Math.toRadians(safeLat(latDeg))
        val m = 111412.84 * Math.cos(lat) -
                93.5 * Math.cos(3 * lat) +
                0.118 * Math.cos(5 * lat)
        // Floor of 1 m/deg: past ~89.999 degrees the true value approaches zero and every caller
        // here divides by it.
        return Math.max(1.0, m)
    }

    /** Degrees of latitude that span [meters] at [latDeg]. */
    fun degreesLat(meters: Double, latDeg: Double): Double = meters / metersPerDegreeLat(latDeg)

    /** Degrees of longitude that span [meters] at [latDeg]. */
    fun degreesLon(meters: Double, latDeg: Double): Double = meters / metersPerDegreeLon(latDeg)

    /** Metres spanned by [degrees] of latitude at [latDeg]. */
    fun metersFromLat(degrees: Double, latDeg: Double): Double = degrees * metersPerDegreeLat(latDeg)

    /** Metres spanned by [degrees] of longitude at [latDeg]. */
    fun metersFromLon(degrees: Double, latDeg: Double): Double = degrees * metersPerDegreeLon(latDeg)

    /**
     * Moves a coordinate by a local north/east offset in metres.
     *
     * @return the new (latitude, longitude).
     */
    fun offset(latDeg: Double, lonDeg: Double, northMeters: Double, eastMeters: Double): Pair<Double, Double> {
        val newLat = latDeg + degreesLat(northMeters, latDeg)
        val newLon = lonDeg + degreesLon(eastMeters, latDeg)
        return newLat to newLon
    }

    /**
     * North/east offset in metres from one coordinate to another, in the local tangent plane.
     *
     * @return (north, east) in metres.
     */
    fun toNorthEast(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Pair<Double, Double> {
        val north = metersFromLat(toLat - fromLat, fromLat)
        val east = metersFromLon(toLon - fromLon, fromLat)
        return north to east
    }

    /** Keeps trigonometry away from the poles, where the longitude scale collapses. */
    private fun safeLat(latDeg: Double): Double =
        if (latDeg.isNaN()) 0.0 else latDeg.coerceIn(-89.9, 89.9)
}
