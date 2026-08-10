package com.dji.recreate2.tracking

/**
 * Decides whether a touch on the video image is a deliberate target designation.
 *
 * Designating used to be a bare tap, and touch selection was ON from the moment the app opened.
 * A designation now locks the target, drives the gimbal, moves the targeting-pod lock and
 * publishes a `camera_target` to the C2 server - so a thumb resting on the video reported a target
 * to the ground station.
 *
 * The rule separates the two gestures by how hard they are to do by accident:
 *
 *  - A DRAG is deliberate. Nobody draws a box across the screen without meaning to, so a drag is
 *    accepted at once.
 *  - A TAP is the accidental case, so a tap must be HELD to count.
 *
 * Pure logic, kept out of the View so it can be tested without an Android device.
 */
object TouchSelectionGate {

    /** Movement under this many pixels is a tap, not a drag. */
    const val DRAG_SLOP_PX = 30f

    sealed class Decision {
        /** A drawn box. [Drag] carries no data: the View already has the rectangle. */
        object Drag : Decision()

        /** A held tap at a point. */
        object HeldTap : Decision()

        /** Not a designation. [reason] is written for the operator. */
        data class Rejected(val reason: String) : Decision()
    }

    /**
     * @param dxPx,dyPx how far the touch moved.
     * @param heldMs how long the touch was down.
     * @param longPressMs the platform long-press timeout, from
     *        `ViewConfiguration.getLongPressTimeout()`. Passed in so a test does not need Android.
     * @param armed whether the operator has turned touch designation on.
     */
    fun evaluate(
        dxPx: Float,
        dyPx: Float,
        heldMs: Long,
        longPressMs: Long,
        armed: Boolean
    ): Decision {
        if (!armed) return Decision.Rejected("Touch designation is off. Turn on OBJ first.")

        val isDrag = dxPx >= DRAG_SLOP_PX || dyPx >= DRAG_SLOP_PX
        if (isDrag) return Decision.Drag

        if (heldMs >= longPressMs) return Decision.HeldTap

        return Decision.Rejected("Hold to designate a target.")
    }
}
