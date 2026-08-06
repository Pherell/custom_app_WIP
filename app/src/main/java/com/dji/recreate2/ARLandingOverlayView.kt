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

    // --- Data Update API ---
    fun updateDronePose(lat: Double, lon: Double, alt: Double, heading: Double, pitch: Double) {
        this.droneLat = lat
        this.droneLon = lon
        this.droneAlt = alt
        this.droneHeading = (heading % 360 + 360) % 360
        this.gimbalPitch = pitch
        postInvalidate()
    }

    fun updateTargetLocation(lat: Double, lon: Double, alt: Double = 0.0, heading: Double? = null) {
        this.targetLat = lat
        this.targetLon = lon
        this.targetAlt = alt
        this.targetHeading = heading
        postInvalidate()
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
            return
        }

        // 3. Distance and Bearing Math
        val distance = calculateHaversineDistance(droneLat, droneLon, targetLat, targetLon)
        val bearing = calculateBearing(droneLat, droneLon, targetLat, targetLon)
        val relativeAlt = droneAlt - targetAlt

        // Relative Azimuth & Elevation Offset
        var relAzimuth = bearing - droneHeading
        while (relAzimuth > 180) relAzimuth -= 360
        while (relAzimuth < -180) relAzimuth += 360

        // Elevation angle relative to horizon
        val elevAngleDeg = Math.toDegrees(atan2(-relativeAlt, max(distance, 0.1)))
        val relPitch = elevAngleDeg - gimbalPitch // Offset from current camera pitch center

        // Normalize to Screen Coordinates
        val screenX = centerX + (relAzimuth / (cameraHFOV / 2.0) * centerX).toFloat()
        val screenY = centerY - (relPitch / (cameraVFOV / 2.0) * centerY).toFloat()

        val isTargetVisible = (screenX in 50f..(width - 50f)) && (screenY in 50f..(height - 50f))

        if (isTargetVisible) {
            // Draw Projected 3D Bounding Target Box
            val boxSize = max(40f, min(250f, (300f / max(distance, 1.0)).toFloat()))
            val rect = RectF(
                screenX - boxSize,
                screenY - boxSize,
                screenX + boxSize,
                screenY + boxSize
            )
            canvas.drawRoundRect(rect, 16f, 16f, fillPaint)
            canvas.drawRoundRect(rect, 16f, 16f, boxPaint)

            // Corner Accents
            drawCornerAccents(canvas, rect)

            // Target Label
            canvas.drawText("LANDING PAD", screenX - 80f, screenY - boxSize - 15f, textPaint)

            // Heading Adjustment Arrow if targetHeading specified
            targetHeading?.let { padHeading ->
                val yawOffset = ((padHeading - droneHeading) % 360 + 360) % 360
                drawHeadingArrow(canvas, screenX, screenY + boxSize + 35f, yawOffset.toFloat())
            }
        } else {
            // Target is off-screen -> Draw Edge Pointer Indicator
            drawEdgePointer(canvas, centerX, centerY, relAzimuth.toFloat(), relPitch.toFloat(), width, height)
        }

        // 4. Render Telemetry HUD Cards
        drawTelemetryHUD(canvas, distance, relativeAlt, relAzimuth, isTargetVisible)
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
