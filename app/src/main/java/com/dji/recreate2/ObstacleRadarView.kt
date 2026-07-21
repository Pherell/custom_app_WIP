package com.dji.recreate2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ObstacleRadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var isRadarEnabled = true
    private val arcRect = RectF()
    
    // Default distances (clear)
    var frontDist = 15.0
    var backDist = 15.0
    var leftDist = 15.0
    var rightDist = 15.0
    
    var maxRadarDistance = 10.0

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 15f
    }

    fun setRadarEnabled(enabled: Boolean) {
        isRadarEnabled = enabled
        invalidate()
    }

    fun updateDistances(front: Double, back: Double, left: Double, right: Double) {
        frontDist = front
        backDist = back
        leftDist = left
        rightDist = right
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isRadarEnabled) return

        val w = width.toFloat()
        val h = height.toFloat()
        
        // 3D Perspective Ellipse
        // Fit within the fullscreen view to place arcs on the extreme left and right sides
        arcRect.set(w * 0.05f, h * 0.15f, w * 0.95f, h * 0.85f)
        
        // Draw 4 arcs around the perimeter
        // Front (Top) - sweep from 225 to 315
        drawArc(canvas, arcRect, 225f, 90f, frontDist)
        // Back (Bottom) - sweep from 45 to 135
        drawArc(canvas, arcRect, 45f, 90f, backDist)
        // Left - sweep from 135 to 225
        drawArc(canvas, arcRect, 135f, 90f, leftDist)
        // Right - sweep from 315 to 45
        drawArc(canvas, arcRect, 315f, 90f, rightDist)
        
        // Continuous animation loop for blinking only when an obstacle is close
        val hasObstacle = frontDist <= maxRadarDistance || backDist <= maxRadarDistance || 
                          leftDist <= maxRadarDistance || rightDist <= maxRadarDistance
                          
        if (hasObstacle && visibility == View.VISIBLE) {
            postInvalidateDelayed(33)
        }
    }
    
    private fun drawArc(canvas: Canvas, rect: RectF, startAngle: Float, sweepAngle: Float, distance: Double) {
        if (distance > maxRadarDistance) return // Transparent if far

        val intensity = 1.0 - (distance / maxRadarDistance) // 0m = 1.0 (nearest), max = 0.0 (farthest)
        
        // Blinking period: 100ms (fast) at 0m, to 1000ms (slow) at max distance
        val period = 100.0 + (distance / maxRadarDistance) * 900.0
        val time = System.currentTimeMillis()
        
        // Blinking on/off (square wave)
        val isBlinkOn = (time % period.toLong()) < (period / 2.0)
        
        if (isBlinkOn) {
            val alpha = (255 * intensity).toInt().coerceIn(0, 255)
            val r = 255
            val g = (255 * (1.0 - intensity)).toInt().coerceIn(0, 255)
            val b = 0
            
            paint.color = Color.argb(alpha, r, g, b)
            canvas.drawArc(rect, startAngle, sweepAngle, false, paint)
        }
        // Removed faint green base circle to make it fully transparent when inactive
    }
}
