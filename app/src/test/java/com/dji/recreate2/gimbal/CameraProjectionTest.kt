package com.dji.recreate2.gimbal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Camera-frame to screen geometry.
 *
 * Two bugs live here historically: `vFov = hFov * 9/16`, which is not how field of view works,
 * and a LINEAR angle-to-pixel map, which a camera is not. Both are pinned below. The 9/16
 * formula was fixed once in the AR marker and found still present in the survey grid days
 * later, so it gets an explicit assertion rather than only a correct-value check.
 */
class CameraProjectionTest {

    @Before
    fun setUp() {
        CameraProjection.resetForTest()
    }

    @After
    fun tearDown() {
        // This is an object, not an instance. Leaving a frame size behind changes the next test.
        CameraProjection.resetForTest()
    }

    // ---------------------------------------------------------------- vertical FOV

    @Test
    fun `vertical FOV is the tangent relation not a ratio of the horizontal`() {
        val vFov = CameraProjection.verticalFovDeg(84.0, 9.0 / 16.0)
        assertEquals(53.72, vFov, 0.01)
    }

    @Test
    fun `vertical FOV is not the old nine sixteenths approximation`() {
        // THE BUG, twice over. 84 * 9/16 = 47.25, which under-reports the real 53.72 by more
        // than six degrees. On the survey grid that made the along-track photo spacing too
        // tight, so every mapping run shot more images than the requested overlap needed.
        val wrong = 84.0 * 9.0 / 16.0
        assertEquals(47.25, wrong, 0.001)
        assertNotEquals(wrong, CameraProjection.verticalFovDeg(84.0, 9.0 / 16.0), 1.0)
    }

    @Test
    fun `a square frame has equal horizontal and vertical FOV`() {
        assertEquals(84.0, CameraProjection.verticalFovDeg(84.0, 1.0), 1e-9)
        assertEquals(60.0, CameraProjection.verticalFovDeg(60.0, 1.0), 1e-9)
    }

    @Test
    fun `vertical FOV grows with the horizontal FOV`() {
        var previous = 0.0
        for (h in 20..170 step 10) {
            val v = CameraProjection.verticalFovDeg(h.toDouble(), 9.0 / 16.0)
            assertTrue("hFov=$h produced $v after $previous", v > previous)
            previous = v
        }
    }

    @Test
    fun `an out of range horizontal FOV is coerced rather than producing a non-finite result`() {
        assertTrue(CameraProjection.verticalFovDeg(0.0, 0.5625).isFinite())
        assertTrue(CameraProjection.verticalFovDeg(180.0, 0.5625).isFinite())
        assertTrue(CameraProjection.verticalFovDeg(-45.0, 0.5625).isFinite())
    }

    // ---------------------------------------------------------------- CENTER_CROP

    @Test
    fun `a surface matching the frame aspect crops nothing`() {
        CameraProjection.setVideoSize(1920, 1080, fromAircraft = false)
        val (w, h) = CameraProjection.visibleFraction(1920, 1080)
        assertEquals(1.0, w, 1e-9)
        assertEquals(1.0, h, 1e-9)
    }

    @Test
    fun `a narrower surface crops the horizontal axis`() {
        CameraProjection.setVideoSize(1920, 1080, fromAircraft = false)

        // A square view of a 16:9 frame keeps 9/16 of the width.
        val (squareW, squareH) = CameraProjection.visibleFraction(1080, 1080)
        assertEquals(0.5625, squareW, 1e-9)
        assertEquals(1.0, squareH, 1e-9)

        // A 4:3 view keeps three quarters of the width.
        val (fourThreeW, fourThreeH) = CameraProjection.visibleFraction(1920, 1440)
        assertEquals(0.75, fourThreeW, 1e-9)
        assertEquals(1.0, fourThreeH, 1e-9)
    }

    @Test
    fun `CENTER_CROP always fills one axis completely`() {
        CameraProjection.setVideoSize(1920, 1080, fromAircraft = false)
        val surfaces = listOf(
            1920 to 1080, 1080 to 1080, 1920 to 1440, 800 to 1000, 2560 to 1080, 400 to 2000
        )
        for ((sw, sh) in surfaces) {
            val (w, h) = CameraProjection.visibleFraction(sw, sh)
            assertTrue("${sw}x$sh gave $w / $h", w == 1.0 || h == 1.0)
            assertTrue("${sw}x$sh gave $w", w in 0.0..1.0)
            assertTrue("${sw}x$sh gave $h", h in 0.0..1.0)
        }
    }

    @Test
    fun `an unmeasured surface crops nothing`() {
        // Called before layout, so the whole frame is assumed visible rather than dividing by
        // a zero-sized view.
        assertEquals(1.0 to 1.0, CameraProjection.visibleFraction(0, 0))
        assertEquals(1.0 to 1.0, CameraProjection.visibleFraction(-100, 500))
    }

    @Test
    fun `cropping narrows the visible half angle`() {
        CameraProjection.setVideoSize(1920, 1080, fromAircraft = false)

        val (fullH, fullV) = CameraProjection.effectiveHalfFovDeg(84.0, 1920, 1080)
        assertEquals(42.0, fullH, 0.01)
        assertEquals(53.72 / 2.0, fullV, 0.01)

        val (cropH, cropV) = CameraProjection.effectiveHalfFovDeg(84.0, 1080, 1080)
        assertTrue("cropped $cropH must be under the full $fullH", cropH < fullH)
        assertEquals(fullV, cropV, 1e-9)
    }

    // ---------------------------------------------------------------- angle to pixel

    @Test
    fun `the optical axis lands at the centre of the view`() {
        assertEquals(500.0, CameraProjection.angleToScreen(0.0, 30.0, 1000), 1e-9)
    }

    @Test
    fun `the field edge lands at the view edge`() {
        assertEquals(1000.0, CameraProjection.angleToScreen(30.0, 30.0, 1000), 1e-6)
        assertEquals(0.0, CameraProjection.angleToScreen(-30.0, 30.0, 1000), 1e-6)
    }

    @Test
    fun `the projection is monotonic across the field`() {
        var previous = -1.0
        var deg = -29.0
        while (deg <= 29.0) {
            val px = CameraProjection.angleToScreen(deg, 30.0, 1000)
            assertTrue("$deg produced $px after $previous", px > previous)
            previous = px
            deg += 1.0
        }
    }

    @Test
    fun `the projection is a tangent map and not a linear one`() {
        // THE BUG. The AR home marker mapped angle to pixel with a plain ratio. Half of the
        // half-angle is NOT half the distance to the edge: a real lens puts it closer in.
        val half = 26.8612
        val actual = CameraProjection.angleToScreen(half / 2.0, half, 1000)
        val linear = 500.0 + 0.5 * 500.0

        assertEquals(735.7, actual, 0.5)
        assertTrue("tangent $actual should sit inside the linear $linear", actual < linear)
        assertTrue("difference of ${linear - actual}px must be visible on screen", linear - actual > 5.0)
    }

    @Test
    fun `a marker outside the visible cone is reported off screen`() {
        assertTrue(CameraProjection.isOffScreen(50.0, 0.0, 42.0, 26.9))
        assertTrue(CameraProjection.isOffScreen(0.0, -30.0, 42.0, 26.9))
        assertFalse(CameraProjection.isOffScreen(41.0, 26.0, 42.0, 26.9))
        assertFalse(CameraProjection.isOffScreen(0.0, 0.0, 42.0, 26.9))
    }

    // ---------------------------------------------------------------- detection box ratios

    @Test
    fun `the frame centre is the view centre under every crop`() {
        CameraProjection.setVideoSize(1920, 1080, fromAircraft = false)
        for ((sw, sh) in listOf(1920 to 1080, 1080 to 1080, 800 to 1000)) {
            val (x, y) = CameraProjection.cameraRatioToViewRatio(0.5f, 0.5f, sw, sh)
            assertEquals(0.5f, x, 1e-6f)
            assertEquals(0.5f, y, 1e-6f)
        }
    }

    @Test
    fun `an off centre detection moves outward when its axis is cropped`() {
        CameraProjection.setVideoSize(1920, 1080, fromAircraft = false)

        // Square view: 9/16 of the width is visible, so a box at 0.75 of the frame sits at
        // (0.25 / 0.5625) + 0.5 = 0.944 of the view. Applying the raw ratio would have drawn
        // it at 0.75 - visibly off the subject.
        val (x, y) = CameraProjection.cameraRatioToViewRatio(0.75f, 0.75f, 1080, 1080)
        assertEquals(0.9444f, x, 1e-4f)
        assertEquals(0.75f, y, 1e-6f)
    }

    @Test
    fun `a detection in the cropped margin lands outside the view`() {
        CameraProjection.setVideoSize(1920, 1080, fromAircraft = false)
        // 0.98 across a 16:9 frame is beyond the right edge of a square view.
        val (x, _) = CameraProjection.cameraRatioToViewRatio(0.98f, 0.5f, 1080, 1080)
        assertTrue("expected past 1.0, got $x", x > 1.0f)
    }

    // ---------------------------------------------------------------- frame size

    @Test
    fun `a failed size read cannot replace a good frame size`() {
        CameraProjection.setVideoSize(3840, 2160, fromAircraft = true)

        assertFalse(CameraProjection.setVideoSize(0, 0, fromAircraft = true))
        assertFalse(CameraProjection.setVideoSize(1920, -1, fromAircraft = true))

        assertEquals(3840, CameraProjection.videoWidth)
        assertEquals(2160, CameraProjection.videoHeight)
    }

    @Test
    fun `the default frame size applies until the aircraft reports one`() {
        assertFalse(CameraProjection.isVideoSizeKnown)
        assertEquals(1920, CameraProjection.videoWidth)
        assertEquals(1080, CameraProjection.videoHeight)

        CameraProjection.setVideoSize(3840, 2160, fromAircraft = true)
        assertTrue(CameraProjection.isVideoSizeKnown)
    }
}
