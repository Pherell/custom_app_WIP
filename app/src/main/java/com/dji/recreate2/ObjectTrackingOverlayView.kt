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

/**
 * Custom View Overlay for Tactical Optical Object Locking & Tracking (Human, Vehicle, Intruder).
 * Supports Touch-and-Drag Bounding Box Selection and Single-Tap Quick Target Lock on FPV stream.
 */
class ObjectTrackingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF66") // Tactical Neon Green
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

    val targetBoundingBox = RectF()
    private val dragStartBox = RectF()
    private var isDragging = false
    private var startX = 0f
    private var startY = 0f

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
                        // Single Tap Quick Lock (120px x 120px bounding box)
                        val boxSize = 120f
                        targetBoundingBox.set(
                            event.x - boxSize / 2f,
                            event.y - boxSize / 2f,
                            event.x + boxSize / 2f,
                            event.y + boxSize / 2f
                        )
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

        // 2. Draw drag box if dragging
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

            // Tactical Label Overlay
            val text = "LOCK: OBJECT / HUMAN"
            canvas.drawText(text, r.left, Math.max(25f, r.top - 8f), textPaint)
        }
    }
}
