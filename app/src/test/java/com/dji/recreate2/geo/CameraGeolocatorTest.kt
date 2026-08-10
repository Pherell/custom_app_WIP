package com.dji.recreate2.geo

import com.dji.recreate2.geo.CameraGeolocator.GeoResult
import com.dji.recreate2.gimbal.CameraProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Camera-only target geolocation.
 *
 * This is what lets an aircraft with no laser rangefinder record where a TARGET is rather than
 * only where the aircraft was. It is an estimate from an assumed ground plane, so the tests pin
 * the error model as tightly as the geometry - a fix that looks confident and is not is worse
 * than no fix at all.
 */
class CameraGeolocatorTest {

    // ---------------------------------------------------------------- geometry

    @Test
    fun `looking straight down puts the target under the aircraft`() {
        val fix = fixOrFail(gimbalPitch = -90.0)

        assertEquals(0.0, fix.groundRangeM, 1e-6)
        assertEquals(100.0, fix.slantRangeM, 1e-6)
        assertEquals(90.0, fix.depressionDeg, 1e-9)
        assertEquals(LAT, fix.lat, 1e-12)
        assertEquals(LON, fix.lon, 1e-12)
    }

    @Test
    fun `a forty five degree look reaches one height away`() {
        val fix = fixOrFail(gimbalPitch = -45.0)

        assertEquals(100.0, fix.groundRangeM, 1e-6)
        assertEquals(141.42, fix.slantRangeM, 0.01)

        // Measured back in the same tangent plane that produced it.
        val (north, east) = GeoMath.toNorthEast(LAT, LON, fix.lat, fix.lon)
        assertEquals(100.0, north, 0.01)
        assertEquals(0.0, east, 0.01)
    }

    @Test
    fun `the range follows the depression angle`() {
        assertEquals(57.735, fixOrFail(gimbalPitch = -60.0).groundRangeM, 0.001)
        assertEquals(173.205, fixOrFail(gimbalPitch = -30.0).groundRangeM, 0.001)
        assertEquals(373.205, fixOrFail(gimbalPitch = -15.0).groundRangeM, 0.001)
    }

    @Test
    fun `the aircraft heading sets the direction`() {
        val cases = mapOf(
            0.0 to Pair(100.0, 0.0),
            90.0 to Pair(0.0, 100.0),
            180.0 to Pair(-100.0, 0.0),
            270.0 to Pair(0.0, -100.0)
        )
        for ((heading, expected) in cases) {
            val fix = fixOrFail(gimbalPitch = -45.0, droneYaw = heading)
            val (north, east) = GeoMath.toNorthEast(LAT, LON, fix.lat, fix.lon)
            assertEquals("heading $heading north", expected.first, north, 0.01)
            assertEquals("heading $heading east", expected.second, east, 0.01)
        }
    }

    @Test
    fun `gimbal pan adds to the aircraft heading`() {
        // A gimbal panned 30 degrees right of a north-facing aircraft must point where a
        // 30-degree-heading aircraft with a centred gimbal points.
        val panned = fixOrFail(gimbalPitch = -45.0, droneYaw = 0.0, gimbalYaw = 30.0)
        val turned = fixOrFail(gimbalPitch = -45.0, droneYaw = 30.0, gimbalYaw = 0.0)

        assertEquals(turned.lat, panned.lat, 1e-9)
        assertEquals(turned.lon, panned.lon, 1e-9)
    }

    @Test
    fun `a heading that wraps past north still points the right way`() {
        val fix = fixOrFail(gimbalPitch = -45.0, droneYaw = 350.0, gimbalYaw = 20.0)
        val (north, east) = GeoMath.toNorthEast(LAT, LON, fix.lat, fix.lon)

        // 350 + 20 = 370, which is 10 degrees east of north.
        assertEquals(100.0 * Math.cos(Math.toRadians(10.0)), north, 0.01)
        assertEquals(100.0 * Math.sin(Math.toRadians(10.0)), east, 0.01)
    }

    // ---------------------------------------------------------------- pixel offset

    @Test
    fun `the centre pixel is on the boresight`() {
        assertEquals(0.0, CameraGeolocator.pixelOffsetDeg(0.5f, 42.0), 1e-12)
    }

    @Test
    fun `the frame edges are at the half angle`() {
        assertEquals(-42.0, CameraGeolocator.pixelOffsetDeg(0.0f, 42.0), 1e-9)
        assertEquals(42.0, CameraGeolocator.pixelOffsetDeg(1.0f, 42.0), 1e-9)
    }

    @Test
    fun `the pixel map is a tangent projection not a linear one`() {
        // THE BUG this shares with the AR home marker. Quarter of the way across the frame is
        // NOT half the half-angle: a real lens puts it further out.
        val actual = CameraGeolocator.pixelOffsetDeg(0.25f, 42.0)
        val linear = -21.0

        assertEquals(-24.2374, actual, 0.0001)
        assertNotEquals(linear, actual, 1.0)
    }

    @Test
    fun `the pixel map inverts the screen projection exactly`() {
        // Round trip against the projection the AR marker and the detection boxes already use,
        // so the two can never drift apart.
        CameraProjection.resetForTest()
        val half = 42.0
        for (norm in listOf(0.05f, 0.25f, 0.5f, 0.75f, 0.95f)) {
            val angle = CameraGeolocator.pixelOffsetDeg(norm, half)
            val backToPixels = CameraProjection.angleToScreen(angle, half, 1000)
            assertEquals("norm=$norm", (norm * 1000).toDouble(), backToPixels, 0.01)
        }
    }

    @Test
    fun `an off centre pixel moves the target`() {
        val centre = fixOrFail(gimbalPitch = -45.0)
        val right = fixOrFail(gimbalPitch = -45.0, normX = 0.75f)
        val low = fixOrFail(gimbalPitch = -45.0, normY = 0.75f)

        // Right of centre swings the bearing east.
        val (_, rightEast) = GeoMath.toNorthEast(LAT, LON, right.lat, right.lon)
        assertTrue("expected an easterly swing, got $rightEast", rightEast > 1.0)

        // Below centre is a steeper look, so the target is CLOSER.
        assertTrue(
            "low pixel ${low.groundRangeM} should be nearer than centre ${centre.groundRangeM}",
            low.groundRangeM < centre.groundRangeM
        )
    }

    // ---------------------------------------------------------------- terrain

    @Test
    fun `a target below the takeoff point is further away`() {
        // The aircraft is 100 m above launch; the target sits 50 m below launch, so the camera
        // is 150 m above it and a 45-degree look reaches 150 m, not 100 m.
        val fix = fixOrFail(gimbalPitch = -45.0, elevationOffset = -50.0)
        assertEquals(150.0, fix.groundRangeM, 1e-6)
    }

    @Test
    fun `a target above the takeoff point is nearer`() {
        val fix = fixOrFail(gimbalPitch = -45.0, elevationOffset = 40.0)
        assertEquals(60.0, fix.groundRangeM, 1e-6)
    }

    @Test
    fun `a target at or above the aircraft is refused`() {
        assertRefused(gimbalPitch = -45.0, elevationOffset = 100.0)
        assertRefused(gimbalPitch = -45.0, elevationOffset = 150.0)
    }

    // ---------------------------------------------------------------- refusals

    @Test
    fun `a shallow look is refused`() {
        // At 10 degrees a one-degree error is nearly 60 m on the ground. The app must say so
        // rather than put an authoritative marker in the wrong field.
        assertRefused(gimbalPitch = -10.0)
        assertRefused(gimbalPitch = -14.9)
    }

    @Test
    fun `the minimum depression is the boundary`() {
        assertRefused(gimbalPitch = -14.999)
        assertTrue(locate(gimbalPitch = -15.001) is GeoResult.Fix)
    }

    @Test
    fun `a horizontal or upward camera is refused with a clear reason`() {
        val level = locate(gimbalPitch = 0.0)
        val up = locate(gimbalPitch = 20.0)

        assertTrue(level is GeoResult.Refused)
        assertTrue(up is GeoResult.Refused)
        assertTrue((up as GeoResult.Refused).reason.contains("horizon"))
    }

    @Test
    fun `missing telemetry is refused rather than producing a coordinate`() {
        assertTrue(locate(droneLat = Double.NaN) is GeoResult.Refused)
        assertTrue(locate(droneAlt = Double.NaN) is GeoResult.Refused)
        assertTrue(locate(droneYaw = Double.NaN) is GeoResult.Refused)
        assertTrue(locate(gimbalPitch = Double.NaN) is GeoResult.Refused)
        assertTrue(locate(droneLat = 0.0, droneLon = 0.0) is GeoResult.Refused)
    }

    @Test
    fun `an aircraft on the ground is refused`() {
        assertTrue(locate(droneAlt = 0.0) is GeoResult.Refused)
        assertTrue(locate(droneAlt = 0.5) is GeoResult.Refused)
    }

    // ---------------------------------------------------------------- error model

    @Test
    fun `the error estimate is pinned at the angles that matter`() {
        // These are the figures the operator reads off the screen. At 100 m height with one
        // degree of attitude uncertainty.
        assertEquals(1.745, CameraGeolocator.estimatedErrorM(100.0, 90.0, 1.0), 0.001)
        assertEquals(3.903, CameraGeolocator.estimatedErrorM(100.0, 45.0, 1.0), 0.001)
        assertEquals(10.464, CameraGeolocator.estimatedErrorM(100.0, 25.0, 1.0), 0.001)
        assertEquals(58.721, CameraGeolocator.estimatedErrorM(100.0, 10.0, 1.0), 0.001)
    }

    @Test
    fun `the error grows as the camera flattens`() {
        var previous = 0.0
        for (depression in listOf(90.0, 60.0, 45.0, 30.0, 20.0, 10.0, 5.0)) {
            val error = CameraGeolocator.estimatedErrorM(100.0, depression, 1.0)
            assertTrue("depression $depression gave $error after $previous", error > previous)
            previous = error
        }
    }

    @Test
    fun `the error scales with height and with attitude uncertainty`() {
        val base = CameraGeolocator.estimatedErrorM(100.0, 45.0, 1.0)
        assertEquals(base * 2.0, CameraGeolocator.estimatedErrorM(200.0, 45.0, 1.0), 1e-9)
        assertEquals(base * 3.0, CameraGeolocator.estimatedErrorM(100.0, 45.0, 3.0), 1e-9)
    }

    @Test
    fun `a fix always carries its error`() {
        val steep = fixOrFail(gimbalPitch = -75.0)
        val shallow = fixOrFail(gimbalPitch = -20.0)

        assertTrue(steep.estimatedErrorM > 0.0)
        assertTrue(
            "a shallow fix must report a larger error than a steep one",
            shallow.estimatedErrorM > steep.estimatedErrorM * 3.0
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun locate(
        droneLat: Double = LAT,
        droneLon: Double = LON,
        droneAlt: Double = 100.0,
        droneYaw: Double = 0.0,
        gimbalPitch: Double = -45.0,
        gimbalYaw: Double = 0.0,
        normX: Float = 0.5f,
        normY: Float = 0.5f,
        elevationOffset: Double = 0.0
    ): GeoResult = CameraGeolocator.locate(
        droneLat = droneLat,
        droneLon = droneLon,
        droneAltM = droneAlt,
        droneYawDeg = droneYaw,
        gimbalPitchDeg = gimbalPitch,
        gimbalYawDeg = gimbalYaw,
        normX = normX,
        normY = normY,
        halfFovHDeg = 42.0,
        halfFovVDeg = 26.86,
        targetElevationOffsetM = elevationOffset
    )

    private fun fixOrFail(
        droneYaw: Double = 0.0,
        gimbalPitch: Double = -45.0,
        gimbalYaw: Double = 0.0,
        normX: Float = 0.5f,
        normY: Float = 0.5f,
        elevationOffset: Double = 0.0
    ): CameraGeolocator.GeoFix {
        val result = locate(
            droneYaw = droneYaw, gimbalPitch = gimbalPitch, gimbalYaw = gimbalYaw,
            normX = normX, normY = normY, elevationOffset = elevationOffset
        )
        assertTrue("expected a fix, got $result", result is GeoResult.Fix)
        return (result as GeoResult.Fix).fix
    }

    private fun assertRefused(gimbalPitch: Double, elevationOffset: Double = 0.0) {
        val result = locate(gimbalPitch = gimbalPitch, elevationOffset = elevationOffset)
        assertTrue("expected a refusal, got $result", result is GeoResult.Refused)
    }


    // ---------------------------------------------------------------- footprint

    @Test
    fun `a nadir view produces a footprint centred on the aircraft`() {
        // THE BUG this exposed. Looking straight down, every pixel below centre is past 90
        // degrees of depression - the ray has crossed nadir. Without mirroring it, tan() went
        // negative and the most common ISR camera position produced no footprint at all.
        val fp = CameraGeolocator.footprint(
            LAT, LON, 100.0, 0.0, -90.0, 0.0, 42.0, 26.86
        )!!

        assertEquals(4, fp.corners.size)
        assertFalse("a nadir view is not truncated", fp.truncated)

        val (north, east) = GeoMath.toNorthEast(LAT, LON, fp.centre.first, fp.centre.second)
        assertEquals("the centre sits under the aircraft", 0.0, north, 0.5)
        assertEquals(0.0, east, 0.5)
    }

    @Test
    fun `a nadir footprint is symmetric about the aircraft`() {
        val fp = CameraGeolocator.footprint(LAT, LON, 100.0, 0.0, -90.0, 0.0, 42.0, 26.86)!!
        val offsets = fp.corners.map { GeoMath.toNorthEast(LAT, LON, it.first, it.second) }

        // Every corner the same distance out, and they sum to roughly zero.
        val ranges = offsets.map { Math.hypot(it.first, it.second) }
        assertEquals(ranges.first(), ranges.max(), 0.5)
        assertEquals(0.0, offsets.sumOf { it.first }, 0.5)
        assertEquals(0.0, offsets.sumOf { it.second }, 0.5)
    }

    @Test
    fun `an oblique view is a trapezoid with the far edge further out`() {
        val fp = CameraGeolocator.footprint(LAT, LON, 100.0, 0.0, -45.0, 0.0, 42.0, 26.86)!!

        // Near corner depression 71.86 -> 32.76 m; far corner 18.14 -> 305.23 m.
        assertEquals(32.76, fp.nearEdgeM, 0.01)
        assertEquals(305.23, fp.farEdgeM, 0.01)
        assertTrue(fp.farEdgeM > fp.nearEdgeM * 5)
    }

    @Test
    fun `the footprint centre equals the centre pixel fix`() {
        // The pin tying the coverage overlay to tap-to-designate. If these ever disagree the
        // operator has two answers for the same pixel and no way to tell which is right.
        val fp = CameraGeolocator.footprint(LAT, LON, 100.0, 30.0, -50.0, 15.0, 42.0, 26.86)!!
        val fix = fixOrFail(droneYaw = 30.0, gimbalPitch = -50.0, gimbalYaw = 15.0)

        assertEquals(fix.lat, fp.centre.first, 1e-9)
        assertEquals(fix.lon, fp.centre.second, 1e-9)
    }

    @Test
    fun `a shallow far edge is clamped and reported as truncated`() {
        // At gimbal -20 the far corner is 6.86 degrees ABOVE the horizon. Drawing to it would
        // claim coverage of ground the camera never saw.
        val fp = CameraGeolocator.footprint(LAT, LON, 100.0, 0.0, -20.0, 0.0, 42.0, 26.86)!!

        assertTrue("the far edge ran past the usable angle", fp.truncated)
        // Clamped to the 15 degree minimum, not stretched to the horizon.
        assertEquals(100.0 / Math.tan(Math.toRadians(15.0)), fp.farEdgeM, 0.01)
    }

    @Test
    fun `a view past the usable angle has no footprint`() {
        // Null only when the NEAR corner - the steepest look, the bottom of the image - is itself
        // past the usable angle. Then there is nothing worth drawing, and inventing a polygon
        // would be worse than drawing none.
        //
        // The boundary is a gimbal pitch of about +11.9 degrees: above that even the bottom of
        // the frame is within 15 degrees of the horizon.
        assertNull(CameraGeolocator.footprint(LAT, LON, 100.0, 0.0, 12.0, 0.0, 42.0, 26.86))
        assertNull(CameraGeolocator.footprint(LAT, LON, 100.0, 0.0, 30.0, 0.0, 42.0, 26.86))
    }

    @Test
    fun `a camera just below horizontal still sees ground near the aircraft`() {
        // At a pitch of -5 degrees the far edge is above the horizon but the BOTTOM of the frame
        // still looks 31.9 degrees down. Refusing the whole footprint here would throw away the
        // ground the camera genuinely covers, close in.
        val fp = CameraGeolocator.footprint(LAT, LON, 100.0, 0.0, -5.0, 0.0, 42.0, 26.86)!!

        assertTrue("the far edge is past the horizon", fp.truncated)
        assertEquals(100.0 / Math.tan(Math.toRadians(31.86)), fp.nearEdgeM, 0.1)
    }

    @Test
    fun `an oblique view well above the minimum is not truncated`() {
        val fp = CameraGeolocator.footprint(LAT, LON, 100.0, 0.0, -60.0, 0.0, 42.0, 26.86)!!
        assertFalse(fp.truncated)
    }

    @Test
    fun `the footprint grows with altitude`() {
        val low = CameraGeolocator.footprint(LAT, LON, 50.0, 0.0, -60.0, 0.0, 42.0, 26.86)!!
        val high = CameraGeolocator.footprint(LAT, LON, 200.0, 0.0, -60.0, 0.0, 42.0, 26.86)!!

        assertTrue(high.farEdgeM > low.farEdgeM * 3.5)
        assertEquals(4.0, high.nearEdgeM / low.nearEdgeM, 0.01)
    }

    @Test
    fun `the terrain offset moves the footprint like it moves a fix`() {
        val flat = CameraGeolocator.footprint(LAT, LON, 100.0, 0.0, -60.0, 0.0, 42.0, 26.86)!!
        val below = CameraGeolocator.footprint(
            LAT, LON, 100.0, 0.0, -60.0, 0.0, 42.0, 26.86, targetElevationOffsetM = -50.0
        )!!

        assertEquals(1.5, below.nearEdgeM / flat.nearEdgeM, 0.001)
    }

    @Test
    fun `missing telemetry produces no footprint`() {
        assertNull(CameraGeolocator.footprint(Double.NaN, LON, 100.0, 0.0, -60.0, 0.0, 42.0, 26.86))
        assertNull(CameraGeolocator.footprint(LAT, LON, Double.NaN, 0.0, -60.0, 0.0, 42.0, 26.86))
        assertNull(CameraGeolocator.footprint(LAT, LON, 0.5, 0.0, -60.0, 0.0, 42.0, 26.86))
        assertNull(CameraGeolocator.footprint(0.0, 0.0, 100.0, 0.0, -60.0, 0.0, 42.0, 26.86))
    }

    @Test
    fun `a nadir tap below centre resolves instead of failing`() {
        // The same crossed-nadir case through the tap path. This used to be refused with
        // "the geometry does not give a target point".
        val result = CameraGeolocator.locate(
            LAT, LON, 100.0, 0.0, -90.0, 0.0,
            normX = 0.5f, normY = 0.9f,
            halfFovHDeg = 42.0, halfFovVDeg = 26.86
        )
        assertTrue("got: $result", result is GeoResult.Fix)
    }

    private companion object {
        const val LAT = -6.2000
        const val LON = 106.8000
    }
}