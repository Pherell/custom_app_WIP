package com.dji.recreate2.mapping

import com.dji.recreate2.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Survey grid, orbit ring and the velocity frame.
 *
 * This is the code with the worst defect history in the app. `vFov = hFov * 9/16` was corrected in
 * the AR marker and survived here undetected, shipping a second time; before that, the front and
 * side overlap were both derived from the horizontal swath, so the forward overlap never matched
 * what the operator asked for. Both are pinned below.
 */
class SurveyGridTest {

    // ---------------------------------------------------------------- swath

    @Test
    fun `across track swath uses the horizontal field of view`() {
        // 2 * 100 m * tan(42 deg)
        assertEquals(180.08, SurveyGrid.sideSwathMeters(params()), 0.01)
    }

    @Test
    fun `along track swath uses the vertical field of view`() {
        // The vertical FOV at 84 deg on a 16:9 frame is 53.72 deg, not 84.
        assertEquals(101.30, SurveyGrid.frontSwathMeters(params()), 0.01)
    }

    @Test
    fun `the two swaths are different on a non square sensor`() {
        // THE BUG. Both used to come from the horizontal swath, so a 70 percent forward overlap
        // request produced something else entirely.
        val p = params()
        assertTrue(SurveyGrid.frontSwathMeters(p) < SurveyGrid.sideSwathMeters(p))
        assertEquals(SurveyGrid.sideSwathMeters(p), SurveyGrid.frontSwathMeters(p.copy(frameAspectHOverW = 1.0)), 0.01)
    }

    @Test
    fun `along track swath is not the old nine sixteenths approximation`() {
        // THE OTHER BUG, and this is the site that was missed the first time round. hFov * 9/16
        // gives 47.25 deg instead of 53.72, an 87.48 m swath instead of 101.30 - so the photo
        // spacing came out 14 percent tighter than the requested overlap needed and every
        // mapping run shot more images than it had to.
        val wrongSwath = 2.0 * 100.0 * Math.tan(Math.toRadians(84.0 * 9.0 / 16.0 / 2.0))
        assertEquals(87.48, wrongSwath, 0.01)
        assertNotEquals(wrongSwath, SurveyGrid.frontSwathMeters(params()), 5.0)
    }

    @Test
    fun `spacing at seventy percent overlap`() {
        assertEquals(54.02, SurveyGrid.lineSpacingMeters(params()), 0.01)
        assertEquals(30.39, SurveyGrid.photoIntervalMeters(params()), 0.01)
    }

    @Test
    fun `photo interval is always tighter than the line spacing`() {
        // Follows from the sensor being wider than it is tall. If this inverts, the two swaths
        // have been crossed over again.
        for (overlap in listOf(0.0, 30.0, 60.0, 70.0, 85.0)) {
            val p = params(overlap = overlap)
            assertTrue(
                "overlap=$overlap",
                SurveyGrid.photoIntervalMeters(p) < SurveyGrid.lineSpacingMeters(p)
            )
        }
    }

    // ---------------------------------------------------------------- overlap

    @Test
    fun `no overlap advances a full swath`() {
        val p = params(overlap = 0.0)
        assertEquals(SurveyGrid.sideSwathMeters(p), SurveyGrid.lineSpacingMeters(p), 1e-9)
    }

    @Test
    fun `ninety percent overlap advances a tenth of a swath`() {
        val p = params(overlap = 90.0)
        assertEquals(SurveyGrid.sideSwathMeters(p) * 0.10, SurveyGrid.lineSpacingMeters(p), 1e-9)
        assertEquals(18.01, SurveyGrid.lineSpacingMeters(p), 0.01)
    }

    @Test
    fun `an impossible overlap cannot collapse the spacing to zero`() {
        // 100 percent overlap means the aircraft never advances. The clamp keeps a floor of five
        // percent so the generator terminates instead of running forever.
        for (overlap in listOf(100.0, 150.0, Double.MAX_VALUE)) {
            assertEquals(0.05, SurveyGrid.advanceFraction(overlap), 1e-9)
            assertTrue(SurveyGrid.lineSpacingMeters(params(overlap = overlap)) > 0.0)
        }
    }

    @Test
    fun `a negative overlap does not advance more than a full swath`() {
        assertEquals(1.0, SurveyGrid.advanceFraction(-50.0), 1e-9)
    }

    // ---------------------------------------------------------------- scanline

    @Test
    fun `a polygon with fewer than three vertices produces nothing`() {
        assertTrue(SurveyGrid.generate(emptyList(), params()).isEmpty())
        assertTrue(SurveyGrid.generate(listOf(SQUARE[0]), params()).isEmpty())
        assertTrue(SurveyGrid.generate(listOf(SQUARE[0], SQUARE[1]), params()).isEmpty())
    }

    @Test
    fun `a grid covers the polygon`() {
        val points = SurveyGrid.generate(SQUARE, params())
        assertTrue("expected a covering grid, got ${points.size} points", points.size > 20)
    }

    @Test
    fun `every point sits on the polygon or inside the overshoot`() {
        // Lines run past the edge so the aircraft turns outside the area, but only by the
        // overshoot - a point further out than that means the scanline ran away.
        val slack = GeoMath.degreesLon(SurveyGrid.OVERSHOOT_METERS + 1.0, SOUTH)

        for (p in SurveyGrid.generate(SQUARE, params())) {
            assertTrue("lat ${p.lat} outside the polygon", p.lat in SOUTH..NORTH)
            assertTrue("lon ${p.lon} beyond the overshoot", p.lon in (WEST - slack)..(EAST + slack))
        }
    }

    @Test
    fun `line count follows the polygon height and the line spacing`() {
        val points = SurveyGrid.generate(SQUARE, params())
        val lines = points.map { it.lat }.distinct()

        val heightMeters = GeoMath.metersFromLat(NORTH - SOUTH, (NORTH + SOUTH) / 2.0)
        val expected = Math.ceil(heightMeters / SurveyGrid.lineSpacingMeters(params())).toInt()

        assertEquals("height ${heightMeters}m", expected.toDouble(), lines.size.toDouble(), 1.0)
    }

    @Test
    fun `consecutive lines are flown in opposite directions`() {
        // THE PATH, not the point set. A grid whose lines all run the same way makes the aircraft
        // fly back across the whole area between every line instead of turning at the end.
        val points = SurveyGrid.generate(SQUARE, params())

        val directions = points.groupBy { it.lat }
            .toSortedMap()
            .map { (_, line) -> Math.signum(line.last().lon - line.first().lon) }

        assertTrue("expected several lines, got ${directions.size}", directions.size >= 3)
        for (i in 1 until directions.size) {
            assertEquals("line $i runs the same way as line ${i - 1}", -directions[i - 1], directions[i], 0.0)
        }
    }

    @Test
    fun `points along a line are no further apart than the photo interval`() {
        val interval = SurveyGrid.photoIntervalMeters(params())

        for ((lat, line) in SurveyGrid.generate(SQUARE, params()).groupBy { it.lat }) {
            for (i in 1 until line.size) {
                val gap = Math.abs(GeoMath.metersFromLon(line[i].lon - line[i - 1].lon, lat))
                assertTrue("gap of ${gap}m exceeds the ${interval}m interval", gap <= interval + 0.5)
            }
        }
    }

    @Test
    fun `a concave polygon is not filled across the notch`() {
        // A C-shape open to the east. A scanline crossing the mouth must produce two spans, not
        // one bridging the gap - otherwise the aircraft flies over ground it was told to skip.
        val c = listOf(
            SurveyGrid.GridPoint(SOUTH, WEST),
            SurveyGrid.GridPoint(SOUTH, EAST),
            SurveyGrid.GridPoint(SOUTH + (NORTH - SOUTH) * 0.4, EAST),
            SurveyGrid.GridPoint(SOUTH + (NORTH - SOUTH) * 0.4, WEST + (EAST - WEST) * 0.3),
            SurveyGrid.GridPoint(SOUTH + (NORTH - SOUTH) * 0.6, WEST + (EAST - WEST) * 0.3),
            SurveyGrid.GridPoint(SOUTH + (NORTH - SOUTH) * 0.6, EAST),
            SurveyGrid.GridPoint(NORTH, EAST),
            SurveyGrid.GridPoint(NORTH, WEST)
        )
        val points = SurveyGrid.generate(c, params())
        assertTrue(points.isNotEmpty())

        // In the notch band the polygon only extends to 30 percent of the width, so no point
        // there may sit deep in the eastern part of the bounding box.
        val notchLo = SOUTH + (NORTH - SOUTH) * 0.42
        val notchHi = SOUTH + (NORTH - SOUTH) * 0.58
        val notchLimit = WEST + (EAST - WEST) * 0.3 + GeoMath.degreesLon(SurveyGrid.OVERSHOOT_METERS + 1.0, SOUTH)

        val strays = points.filter { it.lat in notchLo..notchHi && it.lon > notchLimit }
        assertTrue("${strays.size} points flown across the notch", strays.isEmpty())
    }

    @Test
    fun `crosshatch adds a second pass at right angles`() {
        val single = SurveyGrid.generate(SQUARE, params())
        val crossed = SurveyGrid.generate(SQUARE, params(crosshatch = true))

        assertTrue("crosshatch must add points", crossed.size > single.size)

        // The second pass flies constant longitude, so its points share longitudes rather than
        // latitudes. The first pass alone has far more distinct latitudes than longitudes.
        assertTrue(single.map { it.lat }.distinct().size < single.map { it.lon }.distinct().size)
        assertTrue(crossed.map { it.lon }.distinct().size > single.map { it.lon }.distinct().size)
    }

    @Test
    fun `a spacing that cannot converge produces nothing rather than hanging`() {
        // One metre of altitude gives a swath under a metre, which the caller must reject.
        assertTrue(SurveyGrid.generate(SQUARE, params(altitude = 0.5)).isEmpty())
    }

    // ---------------------------------------------------------------- orbit

    @Test
    fun `every orbit point is at the requested radius`() {
        val ring = SurveyGrid.orbitRing(CENTER_LAT, CENTER_LON, radiusM = 30.0)

        for (p in ring) {
            val (north, east) = GeoMath.toNorthEast(CENTER_LAT, CENTER_LON, p.lat, p.lon)
            assertEquals(30.0, Math.hypot(north, east), 0.05)
        }
    }

    @Test
    fun `an orbit repeats the ring once per loop`() {
        assertEquals(12, SurveyGrid.orbitRing(CENTER_LAT, CENTER_LON, 30.0).size)
        assertEquals(36, SurveyGrid.orbitRing(CENTER_LAT, CENTER_LON, 30.0, loops = 3).size)
        assertEquals(72, SurveyGrid.orbitRing(CENTER_LAT, CENTER_LON, 30.0, points = 24, loops = 3).size)
    }

    @Test
    fun `an orbit starts due north and turns clockwise`() {
        val ring = SurveyGrid.orbitRing(CENTER_LAT, CENTER_LON, 30.0, points = 4)

        val (n0, e0) = GeoMath.toNorthEast(CENTER_LAT, CENTER_LON, ring[0].lat, ring[0].lon)
        assertEquals(30.0, n0, 0.05)
        assertEquals(0.0, e0, 0.05)

        // Quarter turn clockwise from north is due east.
        val (n1, e1) = GeoMath.toNorthEast(CENTER_LAT, CENTER_LON, ring[1].lat, ring[1].lon)
        assertEquals(0.0, n1, 0.05)
        assertEquals(30.0, e1, 0.05)
    }

    @Test
    fun `the twelve point ring is an inscribed polygon not a circle`() {
        // Recorded rather than assumed: the flown path passes about a metre inside the requested
        // 30 m standoff at the middle of each side. Raise the point count if that matters.
        val inscribedError = 30.0 * (1.0 - Math.cos(Math.PI / SurveyGrid.ORBIT_POINTS_PER_LOOP))
        assertEquals(1.02, inscribedError, 0.01)

        val finer = 30.0 * (1.0 - Math.cos(Math.PI / 36))
        assertTrue("more points must tighten the path", finer < inscribedError / 5.0)
    }

    @Test
    fun `a degenerate orbit produces nothing`() {
        assertTrue(SurveyGrid.orbitRing(CENTER_LAT, CENTER_LON, radiusM = 0.0).isEmpty())
        assertTrue(SurveyGrid.orbitRing(CENTER_LAT, CENTER_LON, radiusM = -5.0).isEmpty())
        assertTrue(SurveyGrid.orbitRing(CENTER_LAT, CENTER_LON, 30.0, points = 2).isEmpty())
        assertTrue(SurveyGrid.orbitRing(CENTER_LAT, CENTER_LON, 30.0, loops = 0).isEmpty())
    }

    // ---------------------------------------------------------------- velocity frame

    @Test
    fun `ground frame puts pitch on north and roll on east`() {
        val (northPitch, northRoll) = ground(bearing = 0.0)
        assertEquals(5.0, northPitch, 1e-9)
        assertEquals(0.0, northRoll, 1e-9)

        val (eastPitch, eastRoll) = ground(bearing = 90.0)
        assertEquals(0.0, eastPitch, 1e-9)
        assertEquals(5.0, eastRoll, 1e-9)

        val (southPitch, _) = ground(bearing = 180.0)
        assertEquals(-5.0, southPitch, 1e-9)

        val (_, westRoll) = ground(bearing = 270.0)
        assertEquals(-5.0, westRoll, 1e-9)
    }

    @Test
    fun `ground frame ignores the aircraft heading`() {
        for (yaw in listOf(0.0, 90.0, -170.0, 359.0)) {
            val (pitch, roll) = SurveyGrid.velocityComponents(45.0, yaw, 5.0, false)
            assertEquals(5.0 * Math.cos(Math.toRadians(45.0)), pitch, 1e-9)
            assertEquals(5.0 * Math.sin(Math.toRadians(45.0)), roll, 1e-9)
        }
    }

    @Test
    fun `body frame puts pitch on the nose and roll on the right`() {
        // Target straight ahead, whatever the aircraft is pointing at.
        for (yaw in listOf(0.0, 90.0, 217.0, -45.0)) {
            val (pitch, roll) = SurveyGrid.velocityComponents(yaw, yaw, 5.0, true)
            assertEquals("yaw=$yaw", 5.0, pitch, 1e-9)
            assertEquals("yaw=$yaw", 0.0, roll, 1e-9)
        }

        // Target 90 degrees to the right: pure roll, no pitch.
        val (rightPitch, rightRoll) = SurveyGrid.velocityComponents(90.0, 0.0, 5.0, true)
        assertEquals(0.0, rightPitch, 1e-9)
        assertEquals(5.0, rightRoll, 1e-9)

        // Target behind: full reverse pitch.
        val (backPitch, _) = SurveyGrid.velocityComponents(180.0, 0.0, 5.0, true)
        assertEquals(-5.0, backPitch, 1e-9)
    }

    @Test
    fun `body frame handles a heading that wraps through north`() {
        // Aircraft on 350, target on 10: a 20 degree turn to the right, not 340 to the left.
        val (pitch, roll) = SurveyGrid.velocityComponents(10.0, 350.0, 5.0, true)
        assertEquals(5.0 * Math.cos(Math.toRadians(20.0)), pitch, 1e-9)
        assertEquals(5.0 * Math.sin(Math.toRadians(20.0)), roll, 1e-9)
        assertTrue("must roll right, not left", roll > 0.0)
    }

    @Test
    fun `speed is preserved whatever the bearing`() {
        for (bearing in 0..359 step 7) {
            val (pitch, roll) = SurveyGrid.velocityComponents(bearing.toDouble(), 33.0, 7.5, true)
            assertEquals("bearing=$bearing", 7.5, Math.hypot(pitch, roll), 1e-9)
        }
    }

    @Test
    fun `a hold command is zero on both axes`() {
        val (pitch, roll) = SurveyGrid.velocityComponents(0.0, 0.0, 0.0, false)
        assertEquals(0.0, pitch, 0.0)
        assertEquals(0.0, roll, 0.0)
    }

    @Test
    fun `angle normalisation folds into plus or minus one eighty`() {
        assertEquals(0.0, SurveyGrid.normalizeDeg(0.0), 1e-9)
        assertEquals(-20.0, SurveyGrid.normalizeDeg(340.0), 1e-9)
        assertEquals(20.0, SurveyGrid.normalizeDeg(-340.0), 1e-9)
        assertEquals(10.0, SurveyGrid.normalizeDeg(730.0), 1e-9)
        assertEquals(-180.0, SurveyGrid.normalizeDeg(180.0), 1e-9)
    }

    // ---------------------------------------------------------------- helpers

    private fun params(
        altitude: Double = 100.0,
        overlap: Double = 70.0,
        crosshatch: Boolean = false
    ) = SurveyGrid.GridParams(
        altitudeM = altitude,
        overlapPercent = overlap,
        cameraHFovDeg = 84.0,
        frameAspectHOverW = 1080.0 / 1920.0,
        crosshatch = crosshatch
    )

    private fun ground(bearing: Double) =
        SurveyGrid.velocityComponents(bearing, 0.0, 5.0, false)

    private companion object {
        // A square about 1100 m on a side near Jakarta.
        const val SOUTH = -6.2000
        const val NORTH = -6.1900
        const val WEST = 106.8000
        const val EAST = 106.8100

        val SQUARE = listOf(
            SurveyGrid.GridPoint(SOUTH, WEST),
            SurveyGrid.GridPoint(SOUTH, EAST),
            SurveyGrid.GridPoint(NORTH, EAST),
            SurveyGrid.GridPoint(NORTH, WEST)
        )

        const val CENTER_LAT = -6.1950
        const val CENTER_LON = 106.8050
    }
}
