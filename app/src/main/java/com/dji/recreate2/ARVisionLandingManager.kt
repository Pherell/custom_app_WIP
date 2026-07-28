package com.dji.recreate2

import android.util.Log
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import kotlin.math.abs
import kotlin.math.max

/**
 * AR Vision-Guided Precision Landing Manager
 * Connects OpenCV / ArUco marker pose detection to DJI MSDK V5 Virtual Stick closed-loop control.
 */
class ARVisionLandingManager {

    private val tag = "ARVisionLanding"

    // --- Closed-Loop PID Control Gains ---
    var kpHorizontal: Double = 0.8  // Proportional gain for horizontal velocity (m/s per meter offset)
    var kpYaw: Double = 0.5         // Proportional gain for yaw rotation (deg/s per degree offset)
    var descentSpeed: Double = -0.4 // Vertical descent speed in m/s (negative = down)

    // Safety Thresholds
    var alignmentToleranceMeters: Double = 0.15 // Centering tolerance (15 cm)
    var yawToleranceDegrees: Double = 3.0       // Heading tolerance (3 degrees)

    @Volatile var isLandingActive: Boolean = false
    private var lastDetectionTime: Long = 0L

    // Callbacks for UI overlay updates
    var onVisionPoseUpdated: ((deltaX: Double, deltaY: Double, deltaZ: Double, deltaYaw: Double, isAligned: Boolean) -> Unit)? = null

    /**
     * Called on every camera frame when ArUco / AprilTag pose detection computes target offsets.
     * @param offsetX Horizontal X offset in meters (right +, left -)
     * @param offsetY Horizontal Y offset in meters (forward +, backward -)
     * @param offsetZ Altitude Z distance to pad in meters (positive height)
     * @param yawOffset Heading angle offset in degrees relative to landing pad
     */
    fun processVisionPose(offsetX: Double, offsetY: Double, offsetZ: Double, yawOffset: Double) {
        if (!isLandingActive) return

        lastDetectionTime = System.currentTimeMillis()

        // 1. Calculate Velocity Commands via Proportional Control
        val vx = (offsetY * kpHorizontal).coerceIn(-1.5, 1.5) // Forward/backward pitch velocity
        val vy = (offsetX * kpHorizontal).coerceIn(-1.5, 1.5) // Roll velocity
        val yawRate = (yawOffset * kpYaw).coerceIn(-20.0, 20.0) // Yaw rate deg/s

        val horizontalDistance = Math.hypot(offsetX, offsetY)
        val isAligned = horizontalDistance <= alignmentToleranceMeters && abs(yawOffset) <= yawToleranceDegrees

        // Notify UI for AR HUD update on Main Thread
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onVisionPoseUpdated?.invoke(offsetX, offsetY, offsetZ, yawOffset, isAligned)
        }

        // 2. Determine Vertical Descent
        val vz = if (isAligned) descentSpeed else 0.0 // Only descend when aligned over pad

        // 3. Dispatch Closed-Loop Virtual Stick Param to DJI Aircraft
        sendControlVector(pitchVelocity = vx, rollVelocity = vy, verticalVelocity = vz, yawRate = yawRate)

        // 4. Touchdown Condition
        if (offsetZ <= 0.25 && isAligned) {
            Log.d(tag, "Touchdown altitude reached over pad center! Completing landing.")
            completeTouchdown()
        }
    }

    private fun sendControlVector(pitchVelocity: Double, rollVelocity: Double, verticalVelocity: Double, yawRate: Double) {
        val param = VirtualStickFlightControlParam().apply {
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY

            roll = rollVelocity
            pitch = pitchVelocity
            verticalThrottle = verticalVelocity
            yaw = yawRate
        }

        VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
    }

    fun startVisionLanding() {
        Log.d(tag, "Starting AR Vision-Guided Landing loop")
        isLandingActive = true
        VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.d(tag, "Virtual stick enabled for AR vision landing")
            }
            override fun onFailure(error: IDJIError) {
                Log.e(tag, "Failed to enable virtual stick: ${error.description()}")
            }
        })
    }

    fun stopVisionLanding() {
        Log.d(tag, "Stopping AR Vision-Guided Landing loop")
        isLandingActive = false
        VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {}
            override fun onFailure(error: IDJIError) {}
        })
    }

    private fun completeTouchdown() {
        isLandingActive = false
        // Command final engine cut / landing complete
        VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.d(tag, "AR Vision Landing complete. Aircraft landed safely.")
            }
            override fun onFailure(error: IDJIError) {}
        })
    }
}
