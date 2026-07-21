package com.dji.recreate2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class CompassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var heading = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    
    init {
        paint.color = Color.parseColor("#00FF00")
        paint.strokeWidth = 3f
        paint.style = Paint.Style.FILL

        textPaint.color = Color.parseColor("#00FF00")
        textPaint.textSize = 18f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun setHeading(newHeading: Float) {
        heading = newHeading
        invalidate()
    }

    private val indicatorPath = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        val centerX = width / 2f
        
        // Draw the center fixed indicator at the bottom (triangle pointing up)
        indicatorPath.reset()
        val indicatorBottom = height
        val indicatorTop = height - 12f
        indicatorPath.moveTo(centerX, indicatorTop)
        indicatorPath.lineTo(centerX - 8f, indicatorBottom)
        indicatorPath.lineTo(centerX + 8f, indicatorBottom)
        indicatorPath.close()
        canvas.drawPath(indicatorPath, paint)
        
        // Draw a small tick above the triangle
        canvas.drawLine(centerX, indicatorTop - 2f, centerX, indicatorTop - 10f, paint)
        
        // The visible range of degrees (30 degrees total to match spacing in image)
        val visibleDegrees = 30f
        val pixelsPerDegree = width / visibleDegrees
        
        val startDegree = (heading - visibleDegrees / 2).toInt() - 1
        val endDegree = (heading + visibleDegrees / 2).toInt() + 1
        
        val tickBaseY = height / 2f + 6f
        
        for (i in startDegree..endDegree) {
            var displayDegree = i
            while (displayDegree < 0) displayDegree += 360
            while (displayDegree >= 360) displayDegree -= 360
            
            val x = centerX + (i - heading) * pixelsPerDegree
            
            if (i % 5 == 0) {
                // Major tick
                canvas.drawLine(x, tickBaseY - 12f, x, tickBaseY, paint)
                
                val text = when (displayDegree) {
                    0 -> "N"
                    90 -> "E"
                    180 -> "S"
                    270 -> "W"
                    else -> displayDegree.toString()
                }
                canvas.drawText(text, x, tickBaseY - 18f, textPaint)
            } else {
                // Minor tick
                canvas.drawLine(x, tickBaseY - 6f, x, tickBaseY, paint)
            }
        }
    }
}
