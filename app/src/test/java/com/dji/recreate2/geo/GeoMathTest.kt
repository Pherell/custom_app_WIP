package com.dji.recreate2.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app previously converted metres to degrees in two incompatible ways: a flat 111320.0 for
 * BOTH axes in the orbit and survey code, and the correct 111132.92 / 111412.84 pair in dead
 * reckoning. The cruder one drove survey line spacing.
 *
 * These tests pin the series against the published metres-per-degree table so a future edit
 * cannot quietly reintroduce a flat constant.
 */
class GeoMathTest {

    // ---------------------------------------------------------------- reference values

    @Test
    fun `latitude degree length matches the published table`() {
        assertEquals(110574.0, GeoMath.metersPerDegreeLat(0.0), TOLERANCE_M)
        assertEquals(110649.0, GeoMath.metersPerDegreeLat(15.0), TOLERANCE_M)
        assertEquals(110852.0, GeoMath.metersPerDegreeLat(30.0), TOLERANCE_M)
        assertEquals(111132.0, GeoMath.metersPerDegreeLat(45.0), TOLERANCE_M)
        assertEquals(111412.0, GeoMath.metersPerDegreeLat(60.0), TOLERANCE_M)
        assertEquals(111618.0, GeoMath.metersPerDegreeLat(75.0), TOLERANCE_M)
    }

    @Test
    fun `longitude degree length matches the published table`() {
        assertEquals(111320.0, GeoMath.metersPerDegreeLon(0.0), TOLERANCE_M)
        assertEquals(107550.0, GeoMath.metersPerDegreeLon(15.0), TOLERANCE_M)
        assertEquals(96486.0, GeoMath.metersPerDegreeLon(30.0), TOLERANCE_M)
        assertEquals(78847.0, GeoMath.metersPerDegreeLon(45.0), TOLERANCE_M)
        assertEquals(55800.0, GeoMath.metersPerDegreeLon(60.0), TOLERANCE_M)
        assertEquals(28902.0, GeoMath.metersPerDegreeLon(75.0), TOLERANCE_M)
    }

    @Test
    fun `a latitude degree is not the same length everywhere`() {
        // The oblate spheroid: about 1120 m longer at the pole than at the equator. A flat
        // constant would make this difference zero.
        val spread = GeoMath.metersPerDegreeLat(89.9) - GeoMath.metersPerDegreeLat(0.0)
        assertEquals(1120.0, spread, 5.0)
    }

    // ---------------------------------------------------------------- regression pins

    @Test
    fun `longitude degree is not the flat constant away from the equator`() {
        // THE BUG. Orbit generation, the orbit overlay and the survey grid all used 111320.0
        // for longitude at every latitude. At 45 degrees that is 41 percent too long.
        assertNotEquals(111320.0, GeoMath.metersPerDegreeLon(45.0), 100.0)
        assertNotEquals(111320.0, GeoMath.metersPerDegreeLon(60.0), 100.0)
    }

    @Test
    fun `the flat constant misplaces a one kilometre line`() {
        // How much error the old flat 111320.0 introduced over 1 km on the latitude axis.
        // Kept as a measurement, not just an inequality, so the size of the correction is on
        // the record.
        fun errorAt(lat: Double): Double = 1000.0 * (111320.0 / GeoMath.metersPerDegreeLat(lat)) - 1000.0

        assertEquals(6.74, errorAt(0.0), 0.1)
        assertEquals(4.22, errorAt(30.0), 0.1)
        assertEquals(1.69, errorAt(45.0), 0.1)
        assertEquals(-0.83, errorAt(60.0), 0.1)
    }

    // ---------------------------------------------------------------- round trips

    @Test
    fun `metres to degrees and back is lossless on both axes`() {
        for (lat in listOf(-60.0, -12.5, 0.0, 7.25, 45.0, 71.0)) {
            for (meters in listOf(0.5, 15.0, 1000.0, 25000.0)) {
                assertEquals(
                    "lat=$lat m=$meters",
                    meters,
                    GeoMath.metersFromLat(GeoMath.degreesLat(meters, lat), lat),
                    1e-9
                )
                assertEquals(
                    "lat=$lat m=$meters",
                    meters,
                    GeoMath.metersFromLon(GeoMath.degreesLon(meters, lat), lat),
                    1e-9
                )
            }
        }
    }

    @Test
    fun `offset then toNorthEast returns the original displacement`() {
        val lat = -6.2088
        val lon = 106.8456
        val north = 250.0
        val east = -180.0

        val (newLat, newLon) = GeoMath.offset(lat, lon, north, east)
        val (backNorth, backEast) = GeoMath.toNorthEast(lat, lon, newLat, newLon)

        // Both directions use the scale at the ORIGIN latitude, so the round trip is exact.
        assertEquals(north, backNorth, 1e-6)
        assertEquals(east, backEast, 1e-6)
    }

    @Test
    fun `a north offset increases latitude and an east offset increases longitude`() {
        val (latN, lonN) = GeoMath.offset(0.0, 0.0, 1000.0, 0.0)
        assertTrue(latN > 0.0)
        assertEquals(0.0, lonN, 0.0)

        val (latE, lonE) = GeoMath.offset(0.0, 0.0, 0.0, 1000.0)
        assertEquals(0.0, latE, 0.0)
        assertTrue(lonE > 0.0)
    }

    // ---------------------------------------------------------------- guards

    @Test
    fun `latitude is clamped short of the pole`() {
        // Past the clamp the value must stop changing rather than run to the pole, because
        // every caller divides by the longitude scale.
        assertEquals(GeoMath.metersPerDegreeLat(89.9), GeoMath.metersPerDegreeLat(90.0), 0.0)
        assertEquals(GeoMath.metersPerDegreeLat(89.9), GeoMath.metersPerDegreeLat(120.0), 0.0)
        assertEquals(GeoMath.metersPerDegreeLon(-89.9), GeoMath.metersPerDegreeLon(-90.0), 0.0)
    }

    @Test
    fun `longitude scale stays usable as a divisor at every latitude`() {
        for (lat in -90..90) {
            val m = GeoMath.metersPerDegreeLon(lat.toDouble())
            assertTrue("lat=$lat gave $m", m >= 1.0)
            assertTrue("lat=$lat gave $m", GeoMath.degreesLon(1000.0, lat.toDouble()).isFinite())
        }
    }

    @Test
    fun `a NaN latitude yields a finite result instead of propagating`() {
        // Telemetry drops out and hands NaN to these conversions. They must not turn a mission
        // waypoint into NaN.
        assertTrue(GeoMath.metersPerDegreeLat(Double.NaN).isFinite())
        assertTrue(GeoMath.metersPerDegreeLon(Double.NaN).isFinite())
        assertEquals(GeoMath.metersPerDegreeLat(0.0), GeoMath.metersPerDegreeLat(Double.NaN), 0.0)
    }

    private companion object {
        /** The series is a first-order fit; the published table is rounded to the metre. */
        const val TOLERANCE_M = 1.0
    }
}
