package com.dji.recreate2.flight

import com.dji.recreate2.flight.ReturnHomeBudget.Sample
import com.dji.recreate2.flight.ReturnHomeBudget.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the aircraft can still get home.
 *
 * The app used to decide this from a fixed `droneBattery in 1..24`, which says nothing about
 * distance. These tests pin the energy model and, more importantly, pin the cases where the answer
 * must be "I do not know" - an alarm raised from missing data trains the operator to ignore it.
 */
class ReturnHomeBudgetTest {

    // ------------------------------------------------------------------ time to home

    @Test
    fun `time to home covers the leg, the climb and the descent`() {
        // 1000 m at 8 m/s = 125 s, no climb (already at RTH height), 100 m down at 2 m/s = 50 s.
        val b = evaluate(distance = 1000.0, altitude = 100.0)
        assertEquals(175.0, b.timeToHomeSec, 1e-9)
    }

    @Test
    fun `climbing to the return height is counted only when below it`() {
        // Below RTH height: 50 m of climb at 3 m/s adds 16.667 s.
        assertEquals(166.6667, evaluate(distance = 1000.0, altitude = 50.0).timeToHomeSec, 0.001)

        // Above it: no climb term. 2000/8 + 0 + 120/2 = 310.
        assertEquals(310.0, evaluate(distance = 2000.0, altitude = 120.0).timeToHomeSec, 1e-9)
    }

    @Test
    fun `directly overhead still costs the descent`() {
        assertEquals(50.0, evaluate(distance = 0.0, altitude = 100.0).timeToHomeSec, 1e-9)
    }

    @Test
    fun `distance drives the requirement`() {
        val near = evaluate(distance = 1000.0, altitude = 100.0)
        val far = evaluate(distance = 3000.0, altitude = 100.0)

        assertEquals(425.0, far.timeToHomeSec, 1e-9)
        assertTrue(far.requiredPercent > near.requiredPercent)
    }

    // ------------------------------------------------------------------ states

    @Test
    fun `the same charge means different things at different ranges`() {
        // THE DEFECT this replaces. The old rule read `droneBattery in 1..24`, so 30 percent was
        // "fine" whether the aircraft was 100 m away or 3 km away. Same charge, same altitude,
        // three different answers:
        fun at(distance: Double) =
            evaluate(distance = distance, altitude = 50.0, remaining = 30, burn = 0.15).state

        assertEquals(State.AMPLE, at(100.0))       // ratio 1.53
        assertEquals(State.COMMITTED, at(400.0))   // ratio 1.12
        assertEquals(State.CRITICAL, at(3000.0))   // ratio 0.33
    }

    @Test
    fun `the state boundaries are exact`() {
        // At distance 1000 / altitude 100 the requirement is 43.125 percent.
        // Ratio 1.0 at 43.125, ratio 1.5 at 64.6875.
        assertEquals(43.125, evaluate(remaining = 50, burn = 0.15).requiredPercent, 1e-9)

        assertEquals(State.CRITICAL, evaluate(remaining = 43, burn = 0.15).state)
        assertEquals(State.COMMITTED, evaluate(remaining = 44, burn = 0.15).state)
        assertEquals(State.COMMITTED, evaluate(remaining = 64, burn = 0.15).state)
        assertEquals(State.AMPLE, evaluate(remaining = 65, burn = 0.15).state)
    }

    @Test
    fun `the margin goes negative once the reserve is spent`() {
        assertTrue(evaluate(remaining = 80, burn = 0.15).marginSec > 0.0)
        assertTrue(evaluate(remaining = 20, burn = 0.15).marginSec < 0.0)
    }

    // ------------------------------------------------------------------ refusing to guess

    @Test
    fun `an unmeasured burn rate gives UNKNOWN, never CRITICAL`() {
        // The fallback rate is deliberately pessimistic. Alarming on it would cry wolf on every
        // take-off, and an alarm the operator learns to ignore is worse than none.
        val b = evaluate(remaining = 20, burn = null)

        assertEquals(State.UNKNOWN, b.state)
        assertFalse(b.burnRateMeasured)
    }

    @Test
    fun `a measured rate is marked as measured`() {
        assertTrue(evaluate(remaining = 50, burn = 0.15).burnRateMeasured)
    }

    @Test
    fun `on the ground there is no budget`() {
        assertEquals(State.UNKNOWN, evaluate(remaining = 5, burn = 0.15, flying = false).state)
    }

    @Test
    fun `missing telemetry gives UNKNOWN`() {
        assertEquals(State.UNKNOWN, evaluate(distance = Double.NaN, burn = 0.15).state)
        assertEquals(State.UNKNOWN, evaluate(altitude = Double.NaN, burn = 0.15).state)
        assertEquals(State.UNKNOWN, evaluate(remaining = 0, burn = 0.15).state)
    }

    @Test
    fun `a zero or negative speed is refused instead of dividing by zero`() {
        val zero = ReturnHomeBudget.evaluate(
            remainingPercent = 50, groundDistanceHomeM = 1000.0, altitudeM = 100.0,
            rthAltitudeM = 100.0, cruiseSpeedMps = 0.0, burnRate = 0.15
        )
        assertEquals(State.UNKNOWN, zero.state)
        assertTrue(zero.timeToHomeSec.isFinite())

        val negative = ReturnHomeBudget.evaluate(
            remainingPercent = 50, groundDistanceHomeM = 1000.0, altitudeM = 100.0,
            rthAltitudeM = 100.0, cruiseSpeedMps = -5.0, burnRate = 0.15
        )
        assertEquals(State.UNKNOWN, negative.state)
    }

    // ------------------------------------------------------------------ burn rate

    @Test
    fun `burn rate is measured from the discharge series`() {
        // 100 percent to 90 percent over 100 seconds.
        val samples = (0..10).map { Sample(atMs = it * 10_000L, percent = 100 - it) }
        assertEquals(0.1, ReturnHomeBudget.burnRatePercentPerSec(samples)!!, 1e-9)
    }

    @Test
    fun `too few samples gives no rate`() {
        val samples = (0..2).map { Sample(atMs = it * 10_000L, percent = 100 - it) }
        assertNull(ReturnHomeBudget.burnRatePercentPerSec(samples))
    }

    @Test
    fun `too short a window gives no rate`() {
        // Six samples but only 5 seconds apart in total - noise, not a trend.
        val samples = (0..5).map { Sample(atMs = it * 1_000L, percent = 100 - it) }
        assertNull(ReturnHomeBudget.burnRatePercentPerSec(samples))
    }

    @Test
    fun `a flat or rising charge gives no rate`() {
        // A held charge would divide into an infinite endurance. Refuse it.
        val flat = (0..10).map { Sample(atMs = it * 10_000L, percent = 80) }
        assertNull(ReturnHomeBudget.burnRatePercentPerSec(flat))

        val rising = (0..10).map { Sample(atMs = it * 10_000L, percent = 80 + it) }
        assertNull(ReturnHomeBudget.burnRatePercentPerSec(rising))
    }

    @Test
    fun `a measured rate feeds straight back into the budget`() {
        val samples = (0..10).map { Sample(atMs = it * 10_000L, percent = 100 - it) }
        val rate = ReturnHomeBudget.burnRatePercentPerSec(samples)!!

        // At 0.1 %/s with 50 percent left, total endurance is 500 s.
        val b = evaluate(remaining = 50, burn = rate)
        assertEquals(500.0 - (175.0 * 1.3 + 60.0), b.marginSec, 1e-6)
    }

    // ------------------------------------------------------------------ display

    @Test
    fun `the readout shows dashes when the state is unknown`() {
        assertEquals("RTH --", ReturnHomeBudget.describeMargin(evaluate(burn = null)))
    }

    @Test
    fun `the readout shows minutes when known`() {
        val text = ReturnHomeBudget.describeMargin(evaluate(remaining = 80, burn = 0.15))
        assertTrue("got: $text", text.startsWith("RTH ") && text.endsWith("min"))
    }

    // ------------------------------------------------------------------ helper

    private fun evaluate(
        distance: Double = 1000.0,
        altitude: Double = 100.0,
        remaining: Int = 50,
        burn: Double? = 0.15,
        flying: Boolean = true
    ) = ReturnHomeBudget.evaluate(
        remainingPercent = remaining,
        groundDistanceHomeM = distance,
        altitudeM = altitude,
        rthAltitudeM = 100.0,
        cruiseSpeedMps = 8.0,
        climbRateMps = 3.0,
        descentRateMps = 2.0,
        safetyFactor = 1.3,
        fixedReserveSeconds = 60.0,
        burnRate = burn,
        isFlying = flying
    )
}
