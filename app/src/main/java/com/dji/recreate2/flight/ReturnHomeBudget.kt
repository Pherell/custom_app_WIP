package com.dji.recreate2.flight

/**
 * Whether the aircraft can still get home.
 *
 * The app read the battery, published it to the C2 server, and used it for exactly one decision:
 * a fixed `droneBattery in 1..24 -> "LOW_BATTERY_WARNING"`. A percentage on its own says nothing
 * about whether the aircraft can reach the home point. 25% is generous at 100 m and far too late
 * at 3 km, and flying past the point of no return is the ordinary way a multirotor is lost.
 *
 * This models the return as TIME, because time is what discharge tracks, and converts it to charge
 * with a burn rate OBSERVED on this flight rather than an assumed constant. That adapts to the
 * payload, the wind and the age of the battery without needing another SDK key.
 *
 * **WARNING: nothing here commands the aircraft.** The link-loss failsafe already holds the
 * authority to fly home on its own. A second autonomous trigger would mean two independent things
 * can take the aircraft, which is worse than the problem it solves. [State.CRITICAL] is a prompt
 * for the operator, not an action.
 *
 * Pure arithmetic: no Context, no views, no SDK types.
 */
object ReturnHomeBudget {

    /** Above this ratio of available to required charge, there is no concern. */
    const val AMPLE_RATIO = 1.5

    /** Below this ratio the reserve is gone. */
    const val CRITICAL_RATIO = 1.0

    /** Used until the flight has produced enough samples to measure the real rate. */
    const val FALLBACK_BURN_PERCENT_PER_SEC = 0.15 // ~11 minutes from full. Deliberately pessimistic.

    /** Samples needed before a measured rate is trusted. */
    const val MIN_BURN_SAMPLES = 5

    /** Below this the rate is noise, not a discharge trend. */
    private const val MIN_BURN_WINDOW_SEC = 20.0

    enum class State {
        /** Comfortably more charge than the trip home needs. */
        AMPLE,

        /** Enough to get home, but the reserve is being spent. Turn back. */
        COMMITTED,

        /** Not enough for the trip home plus reserve. The point of no return has passed. */
        CRITICAL,

        /** Not enough information to judge. Never inferred from missing data. */
        UNKNOWN
    }

    /** One battery reading. */
    data class Sample(val atMs: Long, val percent: Int)

    data class Budget(
        val state: State,
        /** Seconds to fly home, climb and descend included. */
        val timeToHomeSec: Double,
        /** Charge that trip needs, including the safety factor and the fixed reserve. */
        val requiredPercent: Double,
        val remainingPercent: Double,
        /** Seconds of flying left AFTER setting aside the trip home. Negative once overdrawn. */
        val marginSec: Double,
        /** The burn rate used, percent per second. */
        val burnRatePercentPerSec: Double,
        /** True when the rate was measured on this flight rather than assumed. */
        val burnRateMeasured: Boolean
    )

    /**
     * Measures discharge over the sample window.
     *
     * @return percent per second, or null when there is not enough of a window to be meaningful.
     *         A null must produce [State.UNKNOWN], never a guess.
     */
    fun burnRatePercentPerSec(samples: List<Sample>): Double? {
        if (samples.size < MIN_BURN_SAMPLES) return null

        val first = samples.first()
        val last = samples.last()
        val elapsedSec = (last.atMs - first.atMs) / 1000.0
        if (elapsedSec < MIN_BURN_WINDOW_SEC) return null

        val used = (first.percent - last.percent).toDouble()
        val rate = used / elapsedSec

        // A charge that rose or held is not a discharge trend, and a zero rate would divide into
        // an infinite endurance. elapsedSec is already at least MIN_BURN_WINDOW_SEC, so this one
        // test covers both - an earlier `used <= 0.0` check above it was the same condition
        // written twice, and a mutation of it changed nothing.
        return if (rate.isFinite() && rate > 0.0) rate else null
    }

    /**
     * @param groundDistanceHomeM horizontal distance to the home point.
     * @param altitudeM height above the take-off point.
     * @param rthAltitudeM the height the aircraft climbs to before returning.
     * @param burnRate from [burnRatePercentPerSec], or null to use the pessimistic fallback.
     * @param isFlying when false the result is [State.UNKNOWN] - a budget means nothing on the
     *        ground, and showing CRITICAL to an operator holding the aircraft is noise.
     */
    fun evaluate(
        remainingPercent: Int,
        groundDistanceHomeM: Double,
        altitudeM: Double,
        rthAltitudeM: Double,
        cruiseSpeedMps: Double,
        climbRateMps: Double = 3.0,
        descentRateMps: Double = 2.0,
        safetyFactor: Double = 1.3,
        fixedReserveSeconds: Double = 60.0,
        burnRate: Double? = null,
        isFlying: Boolean = true
    ): Budget {
        val effectiveBurn = burnRate ?: FALLBACK_BURN_PERCENT_PER_SEC

        if (!isFlying ||
            remainingPercent <= 0 ||
            !groundDistanceHomeM.isFinite() ||
            !altitudeM.isFinite() ||
            cruiseSpeedMps <= 0.0 ||
            climbRateMps <= 0.0 ||
            descentRateMps <= 0.0
        ) {
            return Budget(
                state = State.UNKNOWN,
                timeToHomeSec = 0.0,
                requiredPercent = 0.0,
                remainingPercent = remainingPercent.toDouble(),
                marginSec = 0.0,
                burnRatePercentPerSec = effectiveBurn,
                burnRateMeasured = burnRate != null
            )
        }

        // Climb only counts when the aircraft is BELOW the return height. Above it, the aircraft
        // already has the height it needs and adding a climb term would overstate the trip.
        val climbM = (rthAltitudeM - altitudeM).coerceAtLeast(0.0)

        val timeToHome = groundDistanceHomeM / cruiseSpeedMps +
                climbM / climbRateMps +
                altitudeM.coerceAtLeast(0.0) / descentRateMps

        val requiredSeconds = timeToHome * safetyFactor + fixedReserveSeconds
        val requiredPercent = requiredSeconds * effectiveBurn

        val totalEnduranceSec = remainingPercent / effectiveBurn
        val marginSec = totalEnduranceSec - requiredSeconds

        // A burn rate that has not been measured yet is a guess, so do not raise an alarm on it.
        // The fallback is deliberately pessimistic and would cry wolf on every take-off.
        val state = if (burnRate == null) {
            State.UNKNOWN
        } else {
            val ratio = if (requiredPercent > 0.0) remainingPercent / requiredPercent else Double.MAX_VALUE
            when {
                ratio >= AMPLE_RATIO -> State.AMPLE
                ratio >= CRITICAL_RATIO -> State.COMMITTED
                else -> State.CRITICAL
            }
        }

        return Budget(
            state = state,
            timeToHomeSec = timeToHome,
            requiredPercent = requiredPercent,
            remainingPercent = remainingPercent.toDouble(),
            marginSec = marginSec,
            burnRatePercentPerSec = effectiveBurn,
            burnRateMeasured = burnRate != null
        )
    }

    /** Short operator text, e.g. "RTH 4.2min" or "RTH -0.8min". */
    fun describeMargin(budget: Budget): String = when (budget.state) {
        State.UNKNOWN -> "RTH --"
        else -> "RTH ${String.format(java.util.Locale.US, "%.1f", budget.marginSec / 60.0)}min"
    }
}
