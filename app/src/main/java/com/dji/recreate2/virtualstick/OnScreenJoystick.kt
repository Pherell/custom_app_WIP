package com.dji.recreate2.virtualstick

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.View.OnTouchListener
import com.dji.recreate2.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class OnScreenJoystick(context: Context?, attrs: AttributeSet) : SurfaceView(context, attrs),
    SurfaceHolder.Callback, OnTouchListener {
    private var mJoystick: Bitmap? = null
    private lateinit var mHolder: SurfaceHolder
    private var mKnobBounds: Rect? = null
    private var mThread: JoystickThread? = null
    private var mKnobX = 0
    private var mKnobY = 0
    private var mKnobSize = 0
    private var mBackgroundSize = 0
    private var mRadius = 0f
    private var mJoystickListener: OnScreenJoystickListener? = null
    private var isAutoCentering = true

    private fun initGraphics() {
        val res = context.resources
        // Fallback drawing if bitmap is not available
        try {
            mJoystick = BitmapFactory.decodeResource(res, R.mipmap.ic_launcher_round)
        } catch (e: Exception) {
            // we will draw a circle instead if no image is present
        }
    }

    private fun initBounds(pCanvas: Canvas?) {
        mBackgroundSize = pCanvas!!.height
        mKnobSize = (mBackgroundSize * 0.4f).roundToInt()
        mKnobBounds = Rect()
        mRadius = mBackgroundSize * 0.5f
        mKnobX = ((mBackgroundSize - mKnobSize) * 0.5f).roundToInt()
        mKnobY = ((mBackgroundSize - mKnobSize) * 0.5f).roundToInt()
    }

    private fun init() {
        mHolder = holder
        mHolder.addCallback(this)
        setZOrderOnTop(true)
        mHolder.setFormat(PixelFormat.TRANSPARENT)
        setOnTouchListener(this)
        isEnabled = true
        isAutoCentering = true
    }

    fun setJoystickListener(
        pJoystickListener: OnScreenJoystickListener?
    ) {
        mJoystickListener = pJoystickListener
    }

    override fun surfaceCreated(arg0: SurfaceHolder) {
        mThread?.setRunning(false)
        mThread = JoystickThread()
        mThread?.setRunning(true)
        mThread?.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(arg0: SurfaceHolder) {
        mThread?.setRunning(false)
        var retry = true
        while (retry) {
            try {
                mThread?.join()
                retry = false
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mThread?.setRunning(false)
    }

    fun doDraw(pCanvas: Canvas?) {
        if (pCanvas == null) {
            return
        }
        // reset canvas
        pCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if (mKnobBounds == null) {
            initBounds(pCanvas)
        }
        mKnobBounds!![mKnobX, mKnobY, mKnobX + mKnobSize] = mKnobY + mKnobSize
        
        if (mJoystick != null) {
            pCanvas.drawBitmap(mJoystick!!, null, mKnobBounds!!, null)
        } else {
            val paint = Paint()
            paint.color = Color.WHITE
            paint.alpha = 150
            paint.style = Paint.Style.FILL
            pCanvas.drawCircle((mKnobX + mKnobSize/2).toFloat(), (mKnobY + mKnobSize/2).toFloat(), (mKnobSize/2).toFloat(), paint)
        }
    }

    override fun onTouch(arg0: View, pEvent: MotionEvent): Boolean {
        val x = pEvent.x
        val y = pEvent.y
        when (pEvent.action) {
            MotionEvent.ACTION_UP -> if (isAutoCentering) {
                mKnobX = ((mBackgroundSize - mKnobSize) * 0.5f).roundToInt()
                mKnobY = ((mBackgroundSize - mKnobSize) * 0.5f).roundToInt()
            }
            else ->
                if (checkBounds(x, y)) {
                    mKnobX = (x - mKnobSize * 0.5f).roundToInt()
                    mKnobY = (y - mKnobSize * 0.5f).roundToInt()
                } else {
                    val angle = Math.atan2((y - mRadius).toDouble(), (x - mRadius).toDouble())
                    mKnobX =
                        ((mRadius + (mRadius - mKnobSize * 0.5f) * cos(angle)).roundToInt() - mKnobSize * 0.5f).toInt()
                    mKnobY =
                        ((mRadius + (mRadius - mKnobSize * 0.5f) * sin(angle)).roundToInt() - mKnobSize * 0.5f).toInt()
                }
        }
        pushTouchEvent()
        return true
    }

    private fun checkBounds(pX: Float, pY: Float): Boolean {
        return (mRadius - pX).toDouble().pow(2.0) + (mRadius - pY).toDouble().pow(2.0) <= (mRadius - mKnobSize * 0.5f).toDouble().pow(2.0)
    }

    private fun pushTouchEvent() {
        if (mJoystickListener != null) {
            mJoystickListener!!.onTouch(
                this,
                (0.5f - mKnobX.toFloat() / (mBackgroundSize - mKnobSize)) * -2,
                (0.5f - mKnobY.toFloat() / (mBackgroundSize - mKnobSize)) * 2
            )
        }
    }

    private inner class JoystickThread : Thread() {
        private val running = AtomicBoolean(false)

        fun isRunning(): Boolean {
            return running.get()
        }

        fun setRunning(pRunning: Boolean) {
            running.set(pRunning)
        }

        override fun run() {
            while (isRunning()) {
                var canvas: Canvas? = null
                try {
                    canvas = mHolder.lockCanvas(null)
                    synchronized(mHolder) {
                        doDraw(canvas)
                    }
                } catch (e: Exception) {
                    // Ignore
                } finally {
                    if (canvas != null) {
                        mHolder.unlockCanvasAndPost(canvas)
                    }
                    try {
                        Thread.sleep(16)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }

    init {
        initGraphics()
        init()
    }
}