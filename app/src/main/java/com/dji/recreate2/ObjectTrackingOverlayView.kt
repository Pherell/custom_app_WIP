package com.dji.recreate2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

data class DetectedObjectBox(
    val normLeft: Float,
    val normTop: Float,
    val normRight: Float,
    val normBottom: Float,
    val label: String = "OBJECT",
    val confidence: Float = 0.95f
)

/**
 * Custom View Overlay for Tactical Optical Object Locking & Tracking (Human, Vehicle, Intruder).
 * Supports Touch-and-Drag Bounding Box Selection, Single-Tap Quick Target Lock, and Native AI Object Box Detection.
 */
class ObjectTrackingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val detectedBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Tactical Cyan for Auto-Detected Objects
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val detectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1500E5FF")
        style = Paint.Style.FILL
    }

    private val detectedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        textSize = 18f
        isFakeBoldText = true
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66") // Tactical Neon Green for Active Lock
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2200FF66")
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66")
        textSize = 20f
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00FF66")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    var isTrackingActive = false
        private set

    var isTouchSelectionEnabled = true
    var isAiDetectionBoxesVisible = true

    val targetBoundingBox = RectF()
    private val dragStartBox = RectF()
    private var isDragging = false
    private var startX = 0f
    private var startY = 0f

    private val detectedObjectsList = java.util.concurrent.CopyOnWriteArrayList<DetectedObjectBox>()

    fun updateDetectedObjects(boxes: List<DetectedObjectBox>) {
        detectedObjectsList.clear()
        detectedObjectsList.addAll(boxes)
        postInvalidate()
    }

    fun clearDetectedObjects() {
        detectedObjectsList.clear()
        postInvalidate()
    }

    var onTargetLockedListener: ((normX: Float, normY: Float, normWidth: Float, normHeight: Float) -> Unit)? = null
    var onTargetUnlockedListener: (() -> Unit)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE || !isTouchSelectionEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                isDragging = true
                dragStartBox.set(startX, startY, startX, startY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val left = Math.min(startX, event.x)
                    val top = Math.min(startY, event.y)
                    val right = Math.max(startX, event.x)
                    val bottom = Math.max(startY, event.y)
                    dragStartBox.set(left, top, right, bottom)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    val dx = Math.abs(event.x - startX)
                    val dy = Math.abs(event.y - startY)

                    if (dx < 30f && dy < 30f) {
                        // Check if tap hit a detected AI object box
                        val w = width.toFloat()
                        val h = height.toFloat()
                        val hitBox = detectedObjectsList.firstOrNull { item ->
                            val r = RectF(item.normLeft * w, item.normTop * h, item.normRight * w, item.normBottom * h)
                            r.contains(event.x, event.y)
                        }

                        if (hitBox != null && w > 0 && h > 0) {
                            targetBoundingBox.set(
                                hitBox.normLeft * w,
                                hitBox.normTop * h,
                                hitBox.normRight * w,
                                hitBox.normBottom * h
                            )
                        } else {
                            // Single Tap Quick Lock (120px x 120px bounding box)
                            val boxSize = 120f
                            targetBoundingBox.set(
                                event.x - boxSize / 2f,
                                event.y - boxSize / 2f,
                                event.x + boxSize / 2f,
                                event.y + boxSize / 2f
                            )
                        }
                    } else {
                        // Dragged Bounding Box Lock
                        targetBoundingBox.set(dragStartBox)
                    }

                    clampToViewBounds(targetBoundingBox)
                    isTrackingActive = true
                    invalidate()

                    val w = width.toFloat()
                    val h = height.toFloat()
                    if (w > 0 && h > 0) {
                        val normX = targetBoundingBox.centerX() / w
                        val normY = targetBoundingBox.centerY() / h
                        val normW = targetBoundingBox.width() / w
                        val normH = targetBoundingBox.height() / h
                        onTargetLockedListener?.invoke(normX, normY, normW, normH)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun clampToViewBounds(rect: RectF) {
        rect.left = Math.max(0f, Math.min(rect.left, width.toFloat()))
        rect.top = Math.max(0f, Math.min(rect.top, height.toFloat()))
        rect.right = Math.max(0f, Math.min(rect.right, width.toFloat()))
        rect.bottom = Math.max(0f, Math.min(rect.bottom, height.toFloat()))
    }

    fun lockTargetNormalized(normX: Float, normY: Float, normW: Float = 0.2f, normH: Float = 0.2f) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val cx = normX * w
        val cy = normY * h
        val bw = normW * w
        val bh = normH * h

        targetBoundingBox.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
        clampToViewBounds(targetBoundingBox)
        isTrackingActive = true
        postInvalidate()

        onTargetLockedListener?.invoke(normX, normY, normW, normH)
    }

    fun unlockTarget() {
        isTrackingActive = false
        targetBoundingBox.setEmpty()
        dragStartBox.setEmpty()
        invalidate()
        onTargetUnlockedListener?.invoke()
    }

    fun updateTargetPosition(normCenterX: Float, normCenterY: Float) {
        if (!isTrackingActive) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val halfW = targetBoundingBox.width() / 2f
        val halfH = targetBoundingBox.height() / 2f
        val cx = normCenterX * w
        val cy = normCenterY * h

        targetBoundingBox.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        clampToViewBounds(targetBoundingBox)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw screen center reticle
        canvas.drawLine(w / 2f - 30f, h / 2f, w / 2f + 30f, h / 2f, crosshairPaint)
        canvas.drawLine(w / 2f, h / 2f - 30f, w / 2f, h / 2f + 30f, crosshairPaint)

        // 2. Draw auto-detected AI objects (Cyan Bounding Boxes) if enabled in settings
        if (isAiDetectionBoxesVisible) {
            for (item in detectedObjectsList) {
                val rect = RectF(item.normLeft * w, item.normTop * h, item.normRight * w, item.normBottom * h)
                canvas.drawRect(rect, detectedFillPaint)
                canvas.drawRect(rect, detectedBoxPaint)
                val labelText = "[${item.label} ${(item.confidence * 100).toInt()}%]"
                canvas.drawText(labelText, rect.left, Math.max(20f, rect.top - 6f), detectedTextPaint)
            }
        }

        // 3. Draw drag box if dragging
        if (isDragging) {
            canvas.drawRect(dragStartBox, fillPaint)
            canvas.drawRect(dragStartBox, boxPaint)
            return
        }

        // 3. Draw active target tracking box
        if (isTrackingActive && !targetBoundingBox.isEmpty) {
            canvas.drawRect(targetBoundingBox, fillPaint)
            canvas.drawRect(targetBoundingBox, boxPaint)

            // Corner Reticles
            val cornerLen = Math.min(targetBoundingBox.width(), targetBoundingBox.height()) * 0.25f
            val r = targetBoundingBox

            // Top-Left Corner
            canvas.drawLine(r.left, r.top, r.left + cornerLen, r.top, cornerPaint)
            canvas.drawLine(r.left, r.top, r.left, r.top + cornerLen, cornerPaint)

            // Top-Right Corner
            canvas.drawLine(r.right - cornerLen, r.top, r.right, r.top, cornerPaint)
            canvas.drawLine(r.right, r.top, r.right, r.top + cornerLen, cornerPaint)

            // Bottom-Left Corner
            canvas.drawLine(r.left, r.bottom, r.left + cornerLen, r.bottom, cornerPaint)
            canvas.drawLine(r.left, r.bottom - cornerLen, r.left, r.bottom, cornerPaint)

            // Bottom-Right Corner
            canvas.drawLine(r.right - cornerLen, r.bottom, r.right, r.bottom, cornerPaint)
            canvas.drawLine(r.right, r.bottom - cornerLen, r.right, r.bottom, cornerPaint)

            // Tactical CCTV AI Label Overlay
            val cx = r.centerX() / w
            val cy = r.centerY() / h
            val distFromCenter = Math.hypot((cx - 0.5).toDouble(), (cy - 0.5).toDouble())
            val text = if (distFromCenter < 0.03) "🎯 LOCKED: DEAD CENTER [AR TRACKING]" else "🔒 AR LOCK: CENTERING GIMBAL..."
            canvas.drawText(text, r.left, Math.max(25f, r.top - 8f), textPaint)
        }
    }
}
