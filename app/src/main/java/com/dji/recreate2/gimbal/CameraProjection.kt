package com.dji.recreate2.gimbal

import android.util.Log
import androidx.annotation.VisibleForTesting
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter

/**
 * Maps between camera-frame angles/ratios and on-screen pixels.
 *
 * Two callers need this and both used to get it wrong in the same way:
 *
 *  - The AR home marker projected an angle onto the screen with a LINEAR ratio and derived the
 *    vertical field of view as `hFov * 9/16`. Neither is correct. A camera is a tangent
 *    projection, and vertical FOV is `2*atan(tan(hFov/2) * aspect)` — at hFov 84 degrees on a
 *    16:9 sensor the true vertical FOV is about 54 degrees, not the 47 the old formula gave.
 *
 *  - Detection boxes arrive as ratios of the FULL camera frame, but the video is bound with
 *    [dji.v5.manager.interfaces.ICameraStreamManager.ScaleType.CENTER_CROP]. That scales the
 *    frame to FILL the view, so part of one axis is cropped away and never reaches the screen.
 *    A ratio applied straight to the surface therefore lands in the wrong place, and the visible
 *    field of view is narrower than the camera's.
 *
 * Everything here is pure geometry apart from [refreshVideoSize], which asks the SDK for the real
 * stream dimensions.
 */
object CameraProjection {

    private const val TAG = "CameraProjection"

    /** Fallback stream size until the SDK reports the real one. */
    private const val DEFAULT_VIDEO_W = 1920
    private const val DEFAULT_VIDEO_H = 1080

    /** tan() runs away near 90 degrees; keep every angle a safe distance from it. */
    private const val MAX_ANGLE_DEG = 88.0

    @Volatile var videoWidth: Int = DEFAULT_VIDEO_W
        private set
    @Volatile var videoHeight: Int = DEFAULT_VIDEO_H
        private set
    @Volatile var isVideoSizeKnown: Boolean = false
        private set

    /**
     * Reads the live stream dimensions from the SDK. Call once the video is bound. Keeps the
     * previous values if the read fails.
     */
    fun refreshVideoSize(index: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN) {
        try {
            val info = MediaDataCenter.getInstance().cameraStreamManager?.getAircraftStreamFrameInfo(index)
            if (setVideoSize(info?.width ?: 0, info?.height ?: 0, fromAircraft = true)) {
                Log.d(TAG, "Stream size from aircraft: ${describe()}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the stream size: ${e.message}")
        }
    }

    /**
     * The only writer of the frame size. Rejects a non-positive dimension so a failed SDK read
     * cannot replace a good size with zeros and make every ratio infinite.
     *
     * @return true when the size was accepted.
     */
    @VisibleForTesting
    internal fun setVideoSize(w: Int, h: Int, fromAircraft: Boolean): Boolean {
        if (w <= 0 || h <= 0) return false
        videoWidth = w
        videoHeight = h
        if (fromAircraft) isVideoSizeKnown = true
        return true
    }

    /** Puts the fallback frame size back. Tests only — this object outlives a single test. */
    @VisibleForTesting
    internal fun resetForTest() {
        videoWidth = DEFAULT_VIDEO_W
        videoHeight = DEFAULT_VIDEO_H
        isVideoSizeKnown = false
    }

    /** Vertical field of view for a given horizontal field of view and frame aspect (h/w). */
    fun verticalFovDeg(horizontalFovDeg: Double, aspectHOverW: Double): Double {
        val halfH = Math.toRadians(horizontalFovDeg.coerceIn(1.0, 179.0) / 2.0)
        return Math.toDegrees(2.0 * Math.atan(Math.tan(halfH) * aspectHOverW))
    }

    /**
     * Fraction of the camera frame that survives CENTER_CROP on each axis.
     * One value is always 1.0 (the axis that fills the view); the other is the cropped one.
     */
    fun visibleFraction(surfaceW: Int, surfaceH: Int): Pair<Double, Double> {
        if (surfaceW <= 0 || surfaceH <= 0) return 1.0 to 1.0
        val vw = videoWidth.toDouble()
        val vh = videoHeight.toDouble()
        // CENTER_CROP scales so the frame covers the view on both axes.
        val scale = maxOf(surfaceW / vw, surfaceH / vh)
        if (scale <= 0.0) return 1.0 to 1.0
        val fracW = (surfaceW / (scale * vw)).coerceIn(0.05, 1.0)
        val fracH = (surfaceH / (scale * vh)).coerceIn(0.05, 1.0)
        return fracW to fracH
    }

    /**
     * Half-angles actually visible on screen, in degrees, after CENTER_CROP.
     *
     * @param horizontalFovDeg the camera's full horizontal field of view.
     * @return (horizontal half-angle, vertical half-angle)
     */
    fun effectiveHalfFovDeg(horizontalFovDeg: Double, surfaceW: Int, surfaceH: Int): Pair<Double, Double> {
        val (fracW, fracH) = visibleFraction(surfaceW, surfaceH)

        val fullHalfH = Math.toRadians(horizontalFovDeg.coerceIn(1.0, 179.0) / 2.0)
        val fullVertFov = verticalFovDeg(horizontalFovDeg, videoHeight.toDouble() / videoWidth.toDouble())
        val fullHalfV = Math.toRadians(fullVertFov / 2.0)

        // Cropping keeps the centre, so the visible half-angle shrinks with the visible fraction.
        val effHalfH = Math.toDegrees(Math.atan(Math.tan(fullHalfH) * fracW))
        val effHalfV = Math.toDegrees(Math.atan(Math.tan(fullHalfV) * fracH))
        return effHalfH to effHalfV
    }

    /**
     * Projects an off-axis angle onto a screen axis with the correct tangent mapping.
     *
     * @param deltaDeg angle from the centre of the view.
     * @param halfFovDeg visible half-angle of that axis (from [effectiveHalfFovDeg]).
     * @param screenPx length of that axis in pixels.
     * @return pixel position along the axis.
     */
    fun angleToScreen(deltaDeg: Double, halfFovDeg: Double, screenPx: Int): Double {
        val half = halfFovDeg.coerceIn(1.0, MAX_ANGLE_DEG)
        val delta = deltaDeg.coerceIn(-MAX_ANGLE_DEG, MAX_ANGLE_DEG)
        val denom = Math.tan(Math.toRadians(half))
        if (denom <= 0.0) return screenPx / 2.0
        val ratio = Math.tan(Math.toRadians(delta)) / denom
        return screenPx / 2.0 + ratio * (screenPx / 2.0)
    }

    /** True when the angle pair falls outside the visible cone and the marker must be hidden. */
    fun isOffScreen(deltaYawDeg: Double, deltaPitchDeg: Double, halfFovH: Double, halfFovV: Double): Boolean =
        Math.abs(deltaYawDeg) >= halfFovH || Math.abs(deltaPitchDeg) >= halfFovV

    /**
     * Converts a ratio of the FULL camera frame (0..1, as an MLTrackingBox reports) into a ratio
     * of the VISIBLE view, undoing the CENTER_CROP.
     *
     * A value outside 0..1 after conversion means that part of the frame is cropped off screen.
     */
    fun cameraRatioToViewRatio(normX: Float, normY: Float, surfaceW: Int, surfaceH: Int): Pair<Float, Float> {
        val (fracW, fracH) = visibleFraction(surfaceW, surfaceH)
        val vx = ((normX - 0.5f) / fracW.toFloat()) + 0.5f
        val vy = ((normY - 0.5f) / fracH.toFloat()) + 0.5f
        return vx to vy
    }

    fun describe(): String =
        "video ${videoWidth}x$videoHeight" + if (isVideoSizeKnown) " (from aircraft)" else " (default)"
}
