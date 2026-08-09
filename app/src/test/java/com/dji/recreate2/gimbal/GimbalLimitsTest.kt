package com.dji.recreate2.gimbal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mechanical travel limits.
 *
 * Commanding an angle past the end stop makes the gimbal motors hold against the stop until the
 * command changes, which is what overheats and damages them. Several command sites had no clamp
 * at all; the mission POI lock computed `atan2(-droneAlt, poiDist)`, which reaches -90 degrees
 * as the horizontal distance approaches zero.
 */
class GimbalLimitsTest {

    @Before
    fun setUp() {
        GimbalLimits.resetToDefaults()
    }

    @After
    fun tearDown() {
        // This is an object, not an instance. A range left behind changes the next test.
        GimbalLimits.resetToDefaults()
    }

    // ---------------------------------------------------------------- defaults

    @Test
    fun `the conservative defaults apply before the aircraft is read`() {
        assertFalse(GimbalLimits.isFromAircraft)
        assertEquals(-85.0, GimbalLimits.pitchMin, 0.0)
        assertEquals(25.0, GimbalLimits.pitchMax, 0.0)
        assertEquals(-25.0, GimbalLimits.yawMin, 0.0)
        assertEquals(25.0, GimbalLimits.yawMax, 0.0)
    }

    @Test
    fun `a command inside the range passes through untouched`() {
        assertEquals(0.0, GimbalLimits.clampPitch(0.0), 0.0)
        assertEquals(-45.0, GimbalLimits.clampPitch(-45.0), 0.0)
        assertEquals(20.0, GimbalLimits.clampPitch(20.0), 0.0)
        assertEquals(-10.0, GimbalLimits.clampYaw(-10.0), 0.0)
    }

    @Test
    fun `a command past the end stop comes back at the limit`() {
        assertEquals(-85.0, GimbalLimits.clampPitch(-90.0), 0.0)
        assertEquals(-85.0, GimbalLimits.clampPitch(-180.0), 0.0)
        assertEquals(25.0, GimbalLimits.clampPitch(40.0), 0.0)
        assertEquals(-25.0, GimbalLimits.clampYaw(-90.0), 0.0)
        assertEquals(25.0, GimbalLimits.clampYaw(90.0), 0.0)
    }

    // ---------------------------------------------------------------- regression pin

    @Test
    fun `the POI lock expression at short range cannot reach the end stop`() {
        // THE BUG. This is the mission POI lock's own expression: the aircraft 40 m above a
        // target it is almost on top of. It yields -89.28 degrees, past every DJI gimbal's
        // travel, and it was sent to the gimbal unclamped.
        val poiPitch = Math.toDegrees(Math.atan2(-40.0, 0.5))
        assertEquals(-89.28, poiPitch, 0.01)

        assertEquals(-85.0, GimbalLimits.clampPitch(poiPitch), 0.0)
        assertTrue(GimbalLimits.clampPitch(poiPitch) >= GimbalLimits.pitchMin)
    }

    @Test
    fun `a target directly beneath the aircraft cannot reach the end stop`() {
        val straightDown = Math.toDegrees(Math.atan2(-50.0, 0.0))
        assertEquals(-90.0, straightDown, 1e-9)
        assertEquals(-85.0, GimbalLimits.clampPitch(straightDown), 0.0)
    }

    // ---------------------------------------------------------------- non-finite input

    @Test
    fun `a non-finite command becomes a level command rather than reaching the gimbal`() {
        // Telemetry drops out and the tracking loops divide by distances that can be zero.
        // A NaN reaching the gimbal is undefined behaviour on the aircraft.
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(0.0, GimbalLimits.clampPitch(bad), 0.0)
            assertEquals(0.0, GimbalLimits.clampYaw(bad), 0.0)
            assertEquals(0.0, GimbalLimits.aircraftYawShortfall(bad), 0.0)
        }
    }

    // ---------------------------------------------------------------- reading the aircraft

    @Test
    fun `an aircraft range is narrowed by the safety margin`() {
        assertTrue(GimbalLimits.applyRange(-120.0, 30.0, -90.0, 90.0))

        assertEquals(-118.0, GimbalLimits.pitchMin, 0.0)
        assertEquals(28.0, GimbalLimits.pitchMax, 0.0)
        assertEquals(-88.0, GimbalLimits.yawMin, 0.0)
        assertEquals(88.0, GimbalLimits.yawMax, 0.0)
    }

    @Test
    fun `an inverted range is refused and the previous limits stay`() {
        // A gimbal that reports 0..0 or max below min must not become a gimbal that cannot
        // move. Keeping the conservative defaults is the safe answer.
        assertFalse(GimbalLimits.applyRange(30.0, -120.0, 0.0, 0.0))

        assertEquals(-85.0, GimbalLimits.pitchMin, 0.0)
        assertEquals(25.0, GimbalLimits.pitchMax, 0.0)
        assertEquals(-25.0, GimbalLimits.yawMin, 0.0)
        assertEquals(25.0, GimbalLimits.yawMax, 0.0)
    }

    @Test
    fun `a missing axis leaves that axis alone`() {
        assertTrue(GimbalLimits.applyRange(-120.0, 30.0, null, null))

        assertEquals(-118.0, GimbalLimits.pitchMin, 0.0)
        assertEquals(-25.0, GimbalLimits.yawMin, 0.0)
        assertEquals(25.0, GimbalLimits.yawMax, 0.0)
    }

    @Test
    fun `a range with no usable axis reports failure`() {
        assertFalse(GimbalLimits.applyRange(null, null, null, null))
        assertFalse(GimbalLimits.applyRange(10.0, 10.0, 5.0, 5.0))
    }

    @Test
    fun `clamping follows the range that was read from the aircraft`() {
        GimbalLimits.applyRange(-120.0, 30.0, -90.0, 90.0)

        // What the defaults would have refused is now inside the real gimbal's travel.
        assertEquals(-90.0, GimbalLimits.clampPitch(-90.0), 0.0)
        assertEquals(-118.0, GimbalLimits.clampPitch(-135.0), 0.0)
        assertEquals(60.0, GimbalLimits.clampYaw(60.0), 0.0)
    }

    // ---------------------------------------------------------------- aircraft yaw handover

    @Test
    fun `a target the gimbal can reach needs no aircraft rotation`() {
        assertEquals(0.0, GimbalLimits.aircraftYawShortfall(0.0), 0.0)
        assertEquals(0.0, GimbalLimits.aircraftYawShortfall(20.0), 0.0)
        assertEquals(0.0, GimbalLimits.aircraftYawShortfall(-25.0), 0.0)
    }

    @Test
    fun `a target beyond the gimbal hands the remainder to the aircraft`() {
        // 40 degrees demanded, 25 available: the aircraft must turn the other 15.
        assertEquals(15.0, GimbalLimits.aircraftYawShortfall(40.0), 1e-9)
        assertEquals(-15.0, GimbalLimits.aircraftYawShortfall(-40.0), 1e-9)
        assertEquals(155.0, GimbalLimits.aircraftYawShortfall(180.0), 1e-9)
    }

    @Test
    fun `the shortfall plus the clamped gimbal angle is always the demanded angle`() {
        for (demand in -180..180 step 5) {
            val d = demand.toDouble()
            assertEquals(
                "demand=$d",
                d,
                GimbalLimits.clampYaw(d) + GimbalLimits.aircraftYawShortfall(d),
                1e-9
            )
        }
    }

    @Test
    fun `yaw authority is the smaller side of an asymmetric range`() {
        GimbalLimits.applyRange(null, null, -50.0, 30.0)
        // Margin applied: -48 .. 28. The usable symmetric authority is the smaller magnitude.
        assertEquals(28.0, GimbalLimits.yawAuthorityDeg, 0.0)
    }
}
