package com.dji.recreate2

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * Tactical AR Landing Pad Overlay View
 * Projects real-time 3D target bounding box, crosshairs, distance vector,
 * and heading alignment indicators over the live FPV camera feed.
 */
class ARLandingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- State Variables ---
    private var droneLat: Double = Double.NaN
    private var droneLon: Double = Double.NaN
    private var droneAlt: Double = 0.0
    private var droneHeading: Double = 0.0 // True North (0..360)
    private var gimbalPitch: Double = -45.0 // Degrees (-90 = down, 0 = horizon)

    private var targetLat: Double = Double.NaN
    private var targetLon: Double = Double.NaN
    private var targetAlt: Double = 0.0
    private var targetHeading: Double? = null // Pad alignment orientation (0..360)

    // Camera Parameters (Mavic 3 / M3E default wide lens FOV)
    var cameraHFOV: Double = 84.0 // Degrees
    var cameraVFOV: Double = 63.0 // Degrees

    // --- Paints & Pens ---
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 255, 255)
        style = Paint.Style.FILL
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 4f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 10, 20, 30)
        style = Paint.Style.FILL
    }

    private val alignOkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        textSize = 32f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    // --- Exponential Moving Average (EMA) Low-Pass Filter State ---
    private var filteredScreenX: Float = Float.NaN
    private var filteredScreenY: Float = Float.NaN
    private var filteredDistance: Double = Double.NaN
    private val emaAlpha: Float = 0.20f // Smooths out sensor noise and prevents drift

    // --- Data Update API ---
    fun updateDronePose(lat: Double, lon: Double, alt: Double, heading: Double, pitch: Double) {
        this.droneLat = lat
        this.droneLon = lon
        this.droneAlt = alt
        this.droneHeading = (heading % 360 + 360) % 360
        this.gimbalPitch = pitch
        postInvalidateOnAnimation()
    }

    fun updateTargetLocation(lat: Double, lon: Double, alt: Double = 0.0, heading: Double? = null) {
        this.targetLat = lat
        this.targetLon = lon
        this.targetAlt = alt
        this.targetHeading = heading
        postInvalidateOnAnimation()
    }

    fun resetFilter() {
        filteredScreenX = Float.NaN
        filteredScreenY = Float.NaN
        filteredDistance = Double.NaN
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        // 1. Draw Central Targeting Crosshair
        drawCentralCrosshair(canvas, centerX, centerY)

        // 2. Validate Inputs
        if (droneLat.isNaN() || droneLon.isNaN() || targetLat.isNaN() || targetLon.isNaN()) {
            drawStatusBadge(canvas, 30f, 60f, "AR LANDING: WAITING FOR GPS")
            resetFilter()
            return
        }

        // 3. Precise Local Tangent Plane ENU (East-North-Up) Math
        val latRad = Math.toRadians(droneLat)
        val dLat = Math.toRadians(targetLat - droneLat)
        val dLon = Math.toRadians(targetLon - droneLon)

        // WGS-84 Geodesic distances in meters
        val northMeters = dLat * 6378137.0
        val eastMeters = dLon * 6378137.0 * cos(latRad)
        val upMeters = targetAlt - droneAlt

        val rawDistance = sqrt(northMeters * northMeters + eastMeters * eastMeters)
        val relativeAlt = droneAlt - targetAlt

        // Smooth distance via EMA
        if (filteredDistance.isNaN()) {
            filteredDistance = rawDistance
        } else {
            filteredDistance += emaAlpha * (rawDistance - filteredDistance)
        }

        // Bearing to target relative to True North
        val bearingRad = atan2(eastMeters, northMeters)
        val headingRad = Math.toRadians(droneHeading)
        val pitchRad = Math.toRadians(gimbalPitch) // -90 deg = down, 0 deg = horizon

        // 4. 3D Coordinate Transformation to Camera Frame (Body -> Camera)
        // Step A: Rotate ENU vector into Aircraft Body Frame (Forward, Right, Up)
        val yawRel = bearingRad - headingRad
        val xBody = rawDistance * sin(yawRel) // Right (+X)
        val yBody = rawDistance * cos(yawRel) // Forward (+Y)
        val zBody = -relativeAlt             // Up (+Z)

        // Step B: Rotate Body Frame by Gimbal Pitch into Camera Optical Frame
        // Xcam = Right, Ycam = Down, Zcam = Forward (depth)
        val xCam = xBody
        val yCam = yBody * sin(pitchRad) - zBody * cos(pitchRad)
        val zCam = yBody * cos(pitchRad) + zBody * sin(pitchRad)

        // Calculate Relative Azimuth & Pitch for Edge Pointer Fallback
        var relAzimuth = Math.toDegrees(atan2(xBody, max(0.1, yBody)))
        while (relAzimuth > 180) relAzimuth -= 360
        while (relAzimuth < -180) relAzimuth += 360

        val elevAngleDeg = Math.toDegrees(atan2(-relativeAlt, max(rawDistance, 0.1)))
        val relPitch = elevAngleDeg - gimbalPitch

        // 5. True Pinhole Tangential Camera Perspective Projection
        val isTargetInFront = zCam > 0.1
        var isTargetVisible = false
        var targetScreenX = centerX
        var targetScreenY = centerY

        if (isTargetInFront) {
            val fx = centerX / tan(Math.toRadians(cameraHFOV / 2.0)).toFloat()
            val fy = centerY / tan(Math.toRadians(cameraVFOV / 2.0)).toFloat()

            val rawX = centerX + (fx * (xCam / zCam)).toFloat()
            val rawY = centerY - (fy * (yCam / zCam)).toFloat()

            // Apply EMA Low-Pass Filter to eliminate jitter and drift
            if (filteredScreenX.isNaN() || filteredScreenY.isNaN()) {
                filteredScreenX = rawX
                filteredScreenY = rawY
            } else {
                filteredScreenX += emaAlpha * (rawX - filteredScreenX)
                filteredScreenY += emaAlpha * (rawY - filteredScreenY)
            }

            targetScreenX = filteredScreenX
            targetScreenY = filteredScreenY

            // Bounds check inside view rectangle
            isTargetVisible = (targetScreenX in 30f..(width - 30f)) && (targetScreenY in 30f..(height - 30f))
        } else {
            resetFilter()
        }

        if (isTargetVisible) {
            // Draw High-Precision 3D Bounding Target Box
            val boxSize = max(35f, min(220f, (280f / max(filteredDistance, 1.0)).toFloat()))
            val rect = RectF(
                targetScreenX - boxSize,
                targetScreenY - boxSize,
                targetScreenX + boxSize,
                targetScreenY + boxSize
            )
            canvas.drawRoundRect(rect, 14f, 14f, fillPaint)
            canvas.drawRoundRect(rect, 14f, 14f, boxPaint)

            // Corner Accents
            drawCornerAccents(canvas, rect)

            // Target Label
            canvas.drawText("LANDING PAD", targetScreenX - 75f, targetScreenY - boxSize - 12f, textPaint)

            // Heading Adjustment Arrow if targetHeading specified
            targetHeading?.let { padHeading ->
                val yawOffset = ((padHeading - droneHeading) % 360 + 360) % 360
                drawHeadingArrow(canvas, targetScreenX, targetScreenY + boxSize + 32f, yawOffset.toFloat())
            }
        } else {
            // Target is off-screen -> Draw Edge Pointer Indicator
            drawEdgePointer(canvas, centerX, centerY, relAzimuth.toFloat(), relPitch.toFloat(), width, height)
        }

        // 6. Render Telemetry HUD Cards
        drawTelemetryHUD(canvas, filteredDistance, relativeAlt, relAzimuth, isTargetVisible)
    }

    private fun drawCentralCrosshair(canvas: Canvas, cx: Float, cy: Float) {
        // Option A: Military UAV OSD Boresight
        val armLen = 40f
        val gap = 12f
        val tickLen = 6f

        // 1. Center Precision Dot
        canvas.drawCircle(cx, cy, 3f, crosshairPaint)

        // 2. Open Center Crosshair Arms
        canvas.drawLine(cx - armLen, cy, cx - gap, cy, crosshairPaint)
        canvas.drawLine(cx + gap, cy, cx + armLen, cy, crosshairPaint)
        canvas.drawLine(cx, cy - armLen, cx, cy - gap, crosshairPaint)
        canvas.drawLine(cx, cy + gap, cx, cy + armLen, crosshairPaint)

        // 3. Milliradian Ticks
        val tickOffset = 25f
        canvas.drawLine(cx - tickOffset, cy - tickLen, cx - tickOffset, cy + tickLen, crosshairPaint)
        canvas.drawLine(cx + tickOffset, cy - tickLen, cx + tickOffset, cy + tickLen, crosshairPaint)
        canvas.drawLine(cx - tickLen, cy - tickOffset, cx + tickLen, cy - tickOffset, crosshairPaint)
        canvas.drawLine(cx - tickLen, cy + tickOffset, cx + tickLen, cy + tickOffset, crosshairPaint)

        // 4. Tactical Corner Brackets
        val bSize = 50f
        val bLen = 14f
        val p = crosshairPaint
        // Top-Left
        canvas.drawLine(cx - bSize, cy - bSize, cx - bSize + bLen, cy - bSize, p)
        canvas.drawLine(cx - bSize, cy - bSize, cx - bSize, cy - bSize + bLen, p)
        // Top-Right
        canvas.drawLine(cx + bSize - bLen, cy - bSize, cx + bSize, cy - bSize, p)
        canvas.drawLine(cx + bSize, cy - bSize, cx + bSize, cy - bSize + bLen, p)
        // Bottom-Left
        canvas.drawLine(cx - bSize, cy + bSize, cx - bSize + bLen, cy + bSize, p)
        canvas.drawLine(cx - bSize, cy + bSize - bLen, cx - bSize, cy + bSize, p)
        // Bottom-Right
        canvas.drawLine(cx + bSize - bLen, cy + bSize, cx + bSize, cy + bSize, p)
        canvas.drawLine(cx + bSize, cy + bSize - bLen, cx + bSize, cy + bSize, p)
    }

    private fun drawCornerAccents(canvas: Canvas, r: RectF) {
        val len = 25f
        val p = Paint(boxPaint).apply { pathEffect = null; strokeWidth = 8f }
        // Top-Left
        canvas.drawLine(r.left, r.top, r.left + len, r.top, p)
        canvas.drawLine(r.left, r.top, r.left, r.top + len, p)
        // Top-Right
        canvas.drawLine(r.right - len, r.top, r.right, r.top, p)
        canvas.drawLine(r.right, r.top, r.right, r.top + len, p)
        // Bottom-Left
        canvas.drawLine(r.left, r.bottom - len, r.left, r.bottom, p)
        canvas.drawLine(r.left, r.bottom, r.left + len, r.bottom, p)
        // Bottom-Right
        canvas.drawLine(r.right - len, r.bottom, r.right, r.bottom, p)
        canvas.drawLine(r.right, r.bottom - len, r.right, r.bottom, p)
    }

    private fun drawHeadingArrow(canvas: Canvas, cx: Float, cy: Float, angleDeg: Float) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angleDeg)
        val path = Path().apply {
            moveTo(0f, -20f)
            lineTo(12f, 15f)
            lineTo(0f, 8f)
            lineTo(-12f, 15f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
    }

    private fun drawEdgePointer(
        canvas: Canvas,
        cx: Float, cy: Float,
        azimuth: Float, pitch: Float,
        w: Float, h: Float
    ) {
        val angleRad = atan2(pitch.toDouble(), azimuth.toDouble()).toFloat()
        val margin = 80f
        val edgeX = (cx + cos(angleRad) * (w / 2f - margin)).coerceIn(margin, w - margin)
        val edgeY = (cy - sin(angleRad) * (h / 2f - margin)).coerceIn(margin, h - margin)

        canvas.save()
        canvas.translate(edgeX, edgeY)
        canvas.rotate(Math.toDegrees(angleRad.toDouble()).toFloat())
        val path = Path().apply {
            moveTo(20f, 0f)
            lineTo(-15f, -15f)
            lineTo(-5f, 0f)
            lineTo(-15f, 15f)
            close()
        }
        canvas.drawPath(path, arrowPaint)
        canvas.restore()
        canvas.drawText("TARGET", edgeX - 40f, edgeY + 40f, textPaint)
    }

    private fun drawStatusBadge(canvas: Canvas, x: Float, y: Float, msg: String) {
        val rect = RectF(x, y, x + 520f, y + 55f)
        canvas.drawRoundRect(rect, 10f, 10f, badgeBgPaint)
        canvas.drawText(msg, x + 15f, y + 38f, textPaint)
    }

    private fun drawTelemetryHUD(
        canvas: Canvas,
        dist: Double,
        relAlt: Double,
        azimuth: Double,
        isVisible: Boolean
    ) {
        val startX = 30f
        val startY = 70f
        val bgRect = RectF(startX, startY, startX + 460f, startY + 170f)
        canvas.drawRoundRect(bgRect, 12f, 12f, badgeBgPaint)

        canvas.drawText(String.format("ALT : %.1fm", relAlt), startX + 20f, startY + 45f, textPaint)
        canvas.drawText(String.format("DIST: %.1fm", dist), startX + 20f, startY + 90f, textPaint)
        val statusStr = if (isVisible && abs(azimuth) < 5.0) "ALIGN: PERFECT" else if (isVisible) "ALIGN: OK" else "ALIGN: SEEKING"
        canvas.drawText(statusStr, startX + 20f, startY + 135f, alignOkPaint)
    }

    // --- Math Utilities ---
    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        val bearingRad = atan2(y, x)
        return (Math.toDegrees(bearingRad) + 360) % 360
    }
}
