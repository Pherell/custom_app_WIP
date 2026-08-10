package com.dji.recreate2.tracking

import com.dji.recreate2.tracking.TouchSelectionGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that stops a stray thumb designating a target.
 *
 * Touch designation used to be armed from launch and triggered on a bare tap. A designation locks
 * the target, drives the gimbal, moves the targeting-pod lock and publishes a `camera_target` to
 * the C2 server, so an accidental touch told the ground station about a target that was never
 * chosen.
 */
class TouchSelectionGateTest {

    private val longPress = 500L

    @Test
    fun `nothing is accepted while the designator is off`() {
        // THE DEFECT. Every one of these used to designate a target.
        assertRejected(evaluate(dx = 0f, dy = 0f, held = 50, armed = false))
        assertRejected(evaluate(dx = 0f, dy = 0f, held = 5000, armed = false))
        assertRejected(evaluate(dx = 200f, dy = 200f, held = 5000, armed = false))
    }

    @Test
    fun `the off reason names the control to turn on`() {
        val decision = evaluate(dx = 0f, dy = 0f, held = 50, armed = false)
        assertTrue(
            "the operator must be told what to do, got: $decision",
            (decision as Decision.Rejected).reason.contains("OBJ")
        )
    }

    @Test
    fun `a quick tap is refused even when armed`() {
        // The accidental case: a thumb brushing the video.
        assertRejected(evaluate(dx = 0f, dy = 0f, held = 50, armed = true))
        assertRejected(evaluate(dx = 5f, dy = 5f, held = 200, armed = true))
        assertRejected(evaluate(dx = 29f, dy = 29f, held = 499, armed = true))
    }

    @Test
    fun `a held tap designates`() {
        assertEquals(Decision.HeldTap, evaluate(dx = 0f, dy = 0f, held = 500, armed = true))
        assertEquals(Decision.HeldTap, evaluate(dx = 10f, dy = 10f, held = 900, armed = true))
    }

    @Test
    fun `the long press timeout is the boundary`() {
        assertRejected(evaluate(dx = 0f, dy = 0f, held = 499, armed = true))
        assertEquals(Decision.HeldTap, evaluate(dx = 0f, dy = 0f, held = 500, armed = true))
    }

    @Test
    fun `a drag designates at once without being held`() {
        // Drawing a box is deliberate: it does not happen by accident, so it needs no hold.
        assertEquals(Decision.Drag, evaluate(dx = 30f, dy = 0f, held = 10, armed = true))
        assertEquals(Decision.Drag, evaluate(dx = 0f, dy = 30f, held = 10, armed = true))
        assertEquals(Decision.Drag, evaluate(dx = 400f, dy = 300f, held = 60, armed = true))
    }

    @Test
    fun `the drag threshold is on either axis`() {
        // A horizontal swipe is a drag even with no vertical movement, and the reverse.
        assertEquals(Decision.Drag, evaluate(dx = 31f, dy = 0f, held = 10, armed = true))
        assertEquals(Decision.Drag, evaluate(dx = 0f, dy = 31f, held = 10, armed = true))
        assertRejected(evaluate(dx = 29f, dy = 29f, held = 10, armed = true))
    }

    @Test
    fun `a rejected hold tells the operator to hold`() {
        val decision = evaluate(dx = 0f, dy = 0f, held = 100, armed = true)
        assertTrue(
            "expected an instruction, got: $decision",
            (decision as Decision.Rejected).reason.contains("Hold", ignoreCase = true)
        )
    }

    @Test
    fun `a drag beats the hold requirement regardless of order`() {
        // A fast flick that covers distance is still a drag - it must not fall through to the
        // tap branch and be refused for being short.
        val decision = evaluate(dx = 500f, dy = 500f, held = 1, armed = true)
        assertEquals(Decision.Drag, decision)
    }

    private fun evaluate(dx: Float, dy: Float, held: Long, armed: Boolean): Decision =
        TouchSelectionGate.evaluate(dx, dy, held, longPress, armed)

    private fun assertRejected(decision: Decision) {
        assertTrue("expected a refusal, got $decision", decision is Decision.Rejected)
    }
}
