package com.dji.recreate2.flight

import com.dji.recreate2.flight.LoiterController.LoiterState
import com.dji.recreate2.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control law that holds a circle around a target.
 *
 * This flies the aircraft, so the tests pin the geometry AND the refusals. A loiter that spirals
 * instead of circling, or that keeps commanding with no usable target, is a control-law failure
 * with an airframe on the end of it.
 */
class LoiterControllerTest {

    // ------------------------------------------------------------------ direction of travel

    @Test
    fun `on radius the aircraft flies the tangent`() {
        // 100 m north of the target, orbiting clockwise: it should be heading EAST.
        val cmd = step(northOfTargetM = 100.0, radius = 100.0, clockwise = true)!!

        assertEquals(90.0, cmd.groundBearingDeg, 0.01)
        assertEquals(0.0, cmd.radiusErrorM, 0.01)
        assertEquals(TANGENTIAL, cmd.horizontalSpeedMps, 0.01)
    }

    @Test
    fun `the orbit direction reverses`() {
        val cw = step(northOfTargetM = 100.0, radius = 100.0, clockwise = true)!!
        val ccw = step(northOfTargetM = 100.0, radius = 100.0, clockwise = false)!!

        assertEquals(90.0, cw.groundBearingDeg, 0.01)   // east
        assertEquals(270.0, ccw.groundBearingDeg, 0.01) // west
    }

    @Test
    fun `the tangent follows the aircraft round the circle`() {
        // East of the target, clockwise: heading south.
        val cmd = step(eastOfTargetM = 100.0, radius = 100.0, clockwise = true)!!
        assertEquals(180.0, cmd.groundBearingDeg, 0.01)
    }

    // ------------------------------------------------------------------ holding the radius

    @Test
    fun `too far out it turns inward while still going round`() {
        // THE PIN for a circle rather than a polygon. The radial correction is summed with the
        // tangential as vectors; switching between "correct the radius" and "go round" would give
        // a track made of straight segments.
        val cmd = step(northOfTargetM = 150.0, radius = 100.0, clockwise = true)!!

        assertEquals(50.0, cmd.radiusErrorM, 0.01)
        // Tangential 8 east, radial 4 south (toward the target) -> 116.57.
        assertEquals(116.57, cmd.groundBearingDeg, 0.01)
        assertEquals(8.944, cmd.horizontalSpeedMps, 0.001)
    }

    @Test
    fun `too close it turns outward`() {
        val cmd = step(northOfTargetM = 50.0, radius = 100.0, clockwise = true)!!

        assertEquals(-50.0, cmd.radiusErrorM, 0.01)
        // Radial now points NORTH, away from the target, so the track bends north of east.
        assertTrue("expected a northward bend, got ${cmd.groundBearingDeg}", cmd.groundBearingDeg < 90.0)
    }

    @Test
    fun `the radial correction is capped`() {
        // A huge error must not fly the aircraft straight at the target.
        val cmd = step(northOfTargetM = 1000.0, radius = 100.0, clockwise = true)!!
        val radialComponent = Math.hypot(
            cmd.horizontalSpeedMps * Math.cos(Math.toRadians(cmd.groundBearingDeg)),
            0.0
        )
        assertTrue("radial ran away: $radialComponent", radialComponent <= 4.01)
    }

    // ------------------------------------------------------------------ the gimbal, every tick

    @Test
    fun `gimbal pitch is recomputed from live altitude and range`() {
        // THE DEFECT this fixes. The planned orbit bakes the pitch in at generation, so an
        // altitude change mid-orbit leaves the camera at the wrong depression for the rest of it.
        val at100 = step(northOfTargetM = 100.0, radius = 100.0, altitude = 100.0)!!
        val at50 = step(northOfTargetM = 100.0, radius = 100.0, altitude = 50.0)!!

        assertEquals(-45.0, at100.gimbalPitchDeg, 0.01)
        assertEquals(-26.565, at50.gimbalPitchDeg, 0.01)
    }

    @Test
    fun `gimbal pitch shallows as the orbit widens`() {
        val near = step(northOfTargetM = 100.0, radius = 100.0, altitude = 100.0)!!
        val far = step(northOfTargetM = 200.0, radius = 200.0, altitude = 100.0)!!

        assertEquals(-45.0, near.gimbalPitchDeg, 0.01)
        assertEquals(-26.565, far.gimbalPitchDeg, 0.01)
    }

    @Test
    fun `gimbal pitch passes through the mechanical limiter`() {
        // Directly overhead the raw pitch approaches -90, past most gimbals' travel.
        val cmd = LoiterController.step(
            state = LoiterState(TARGET_LAT, TARGET_LON, 0.0, 100.0, 100.0, true),
            droneLat = TARGET_LAT + GeoMath.degreesLat(12.0, TARGET_LAT),
            droneLon = TARGET_LON,
            droneAltM = 300.0,
            droneYawDeg = 0.0,
            tangentialSpeedMps = TANGENTIAL,
            clampPitch = { it.coerceIn(-85.0, 25.0) }
        )!!
        assertTrue("pitch escaped the limiter: ${cmd.gimbalPitchDeg}", cmd.gimbalPitchDeg >= -85.0)
    }

    @Test
    fun `the nose is driven onto the target and the gimbal covers the rest`() {
        // Aircraft north of the target facing north: the target is 180 degrees behind.
        val cmd = LoiterController.step(
            state = LoiterState(TARGET_LAT, TARGET_LON, 0.0, 100.0, 100.0, true),
            droneLat = TARGET_LAT + GeoMath.degreesLat(100.0, TARGET_LAT),
            droneLon = TARGET_LON,
            droneAltM = 100.0,
            droneYawDeg = 0.0,
            tangentialSpeedMps = TANGENTIAL,
            clampYaw = { it.coerceIn(-25.0, 25.0) }
        )!!

        assertTrue("expected a yaw command, got ${cmd.yawRateDegPerSec}", Math.abs(cmd.yawRateDegPerSec) > 1.0)
        // The gimbal cannot reach 180 degrees; the limiter must have capped it.
        assertEquals(25.0, Math.abs(cmd.gimbalYawDeg), 0.01)
    }

    @Test
    fun `no yaw command when the nose already points at the target`() {
        val cmd = step(northOfTargetM = 100.0, radius = 100.0, droneYaw = 180.0)!!
        assertEquals(0.0, cmd.yawRateDegPerSec, 0.01)
    }

    // ------------------------------------------------------------------ altitude

    @Test
    fun `it climbs or descends toward the loiter altitude`() {
        val low = step(northOfTargetM = 100.0, radius = 100.0, altitude = 60.0, wantAltitude = 100.0)!!
        val high = step(northOfTargetM = 100.0, radius = 100.0, altitude = 140.0, wantAltitude = 100.0)!!

        assertTrue("should climb, got ${low.verticalSpeedMps}", low.verticalSpeedMps > 0.0)
        assertTrue("should descend, got ${high.verticalSpeedMps}", high.verticalSpeedMps < 0.0)
        assertEquals(2.0, low.verticalSpeedMps, 0.01)   // capped
        assertEquals(-2.0, high.verticalSpeedMps, 0.01)
    }

    // ------------------------------------------------------------------ refusing to command

    @Test
    fun `over the top of the target it refuses instead of spinning`() {
        // Inside the minimum range the bearing is noise. Commanding from it would spin the
        // aircraft chasing a direction that changes every tick.
        assertNull(step(northOfTargetM = 2.0, radius = 100.0))
        assertNull(step(northOfTargetM = 0.0, radius = 100.0))
    }

    @Test
    fun `missing telemetry produces no command`() {
        assertNull(step(northOfTargetM = 100.0, radius = 100.0, altitude = Double.NaN))
        assertNull(step(northOfTargetM = 100.0, radius = 100.0, droneYaw = Double.NaN))
    }

    @Test
    fun `a radius below the minimum produces no command`() {
        assertNull(step(northOfTargetM = 100.0, radius = 5.0))
        assertNotNull(step(northOfTargetM = 100.0, radius = 10.0))
    }

    @Test
    fun `a zero or negative speed produces no command`() {
        assertNull(step(northOfTargetM = 100.0, radius = 100.0, tangential = 0.0))
        assertNull(step(northOfTargetM = 100.0, radius = 100.0, tangential = -5.0))
    }

    // ------------------------------------------------------------------ start interlocks

    @Test
    fun `it refuses to start without a target`() {
        assertTrue(
            LoiterController.rejectReason(Double.NaN, TARGET_LON, 100.0, 100.0)!!.contains("target")
        )
        assertTrue(
            LoiterController.rejectReason(0.0, 0.0, 100.0, 100.0)!!.contains("target")
        )
    }

    @Test
    fun `it refuses too tight a radius or too low an altitude`() {
        assertTrue(
            LoiterController.rejectReason(TARGET_LAT, TARGET_LON, 5.0, 100.0)!!.contains("radius")
        )
        assertTrue(
            LoiterController.rejectReason(TARGET_LAT, TARGET_LON, 100.0, 5.0)!!.contains("altitude")
        )
    }

    @Test
    fun `a sound request is accepted`() {
        assertNull(LoiterController.rejectReason(TARGET_LAT, TARGET_LON, 100.0, 100.0))
    }

    // ------------------------------------------------------------------ helper

    private fun step(
        northOfTargetM: Double = 0.0,
        eastOfTargetM: Double = 0.0,
        radius: Double = 100.0,
        altitude: Double = 100.0,
        wantAltitude: Double = 100.0,
        droneYaw: Double = 0.0,
        clockwise: Boolean = true,
        tangential: Double = TANGENTIAL
    ) = LoiterController.step(
        state = LoiterState(TARGET_LAT, TARGET_LON, 0.0, radius, wantAltitude, clockwise),
        droneLat = TARGET_LAT + GeoMath.degreesLat(northOfTargetM, TARGET_LAT),
        droneLon = TARGET_LON + GeoMath.degreesLon(eastOfTargetM, TARGET_LAT),
        droneAltM = altitude,
        droneYawDeg = droneYaw,
        tangentialSpeedMps = tangential
    )

    private companion object {
        const val TARGET_LAT = -6.2000
        const val TARGET_LON = 106.8000
        const val TANGENTIAL = 8.0
    }
}
