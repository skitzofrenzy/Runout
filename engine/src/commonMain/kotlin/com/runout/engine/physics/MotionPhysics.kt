package com.runout.engine.physics

import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analytic ball motion formulas on cloth in SI units.
 *
 * Reference: `docs/research/physics.md` § Ball motion states:
 * - Standard gravitational acceleration: `g = 9.81 m/s^2`.
 * - Stun (centre-ball) hit: slides first, then transitions to pure rolling at `5/7` of its initial speed.
 * - Sliding transition time: `t = 2 * u0 / (7 * mu_s * g)`.
 * - Rolling stopping distance: `d = v^2 / (2 * mu_r * g)`.
 */
object MotionPhysics {
    const val GRAVITY_M_S2: Double = 9.81

    /**
     * The 5/7 stun rule: a ball sliding without topspin/backspin at [initialSpeedMps]
     * reaches pure rolling at `(5/7) * initialSpeedMps`.
     */
    fun stunRollingSpeed(initialSpeedMps: Double): Double =
        (5.0 / 7.0) * initialSpeedMps

    /**
     * Time in seconds for a sliding ball with initial contact-point slip speed [initialSlipSpeedMps]
     * to transition to pure rolling under sliding friction coefficient [muS].
     *
     * Formula: `t = 2 * u0 / (7 * mu_s * g)`.
     */
    fun slideToRollTimeSeconds(
        initialSlipSpeedMps: Double,
        muS: Double,
        g: Double = GRAVITY_M_S2,
    ): Double {
        require(muS > 0.0) { "Sliding friction coefficient muS must be positive." }
        require(g > 0.0) { "Gravity g must be positive." }
        return (2.0 * abs(initialSlipSpeedMps)) / (7.0 * muS * g)
    }

    /**
     * Distance in metres travelled by a ball rolling at [rollingSpeedMps] before coming to rest
     * under rolling resistance coefficient [muR].
     *
     * Formula: `d = v^2 / (2 * mu_r * g)`.
     */
    fun rollingStoppingDistanceMeters(
        rollingSpeedMps: Double,
        muR: Double,
        g: Double = GRAVITY_M_S2,
    ): Double {
        require(muR > 0.0) { "Rolling resistance coefficient muR must be positive." }
        require(g > 0.0) { "Gravity g must be positive." }
        return (rollingSpeedMps * rollingSpeedMps) / (2.0 * muR * g)
    }

    /**
     * Inverse of [rollingStoppingDistanceMeters]: the initial rolling speed in m/s required
     * to roll [distanceMeters] before stopping.
     *
     * Formula: `v = sqrt(2 * mu_r * g * d)`.
     */
    fun initialRollingSpeedForDistance(
        distanceMeters: Double,
        muR: Double,
        g: Double = GRAVITY_M_S2,
    ): Double {
        require(distanceMeters >= 0.0) { "Distance must be non-negative." }
        require(muR > 0.0) { "Rolling resistance coefficient muR must be positive." }
        require(g > 0.0) { "Gravity g must be positive." }
        return sqrt(2.0 * muR * g * distanceMeters)
    }

    /**
     * Total post-impact stopping distance in metres for a stun shot struck with impact speed
     * [initialCueSpeedMps] at cut angle [cutAngleRad].
     *
     * After impact, the cue ball leaves along the tangent line at speed `v_c0 = v0 * sin|phi|`
     * with zero spin, slides over distance `12 * v_c0^2 / (49 * mu_s * g)` while slowing to
     * `v_r = (5/7) * v_c0`, and then rolls over `v_r^2 / (2 * mu_r * g)` until coming to rest.
     */
    fun stunStoppingDistanceMeters(
        initialCueSpeedMps: Double,
        cutAngleRad: Double,
        muS: Double,
        muR: Double,
        g: Double = GRAVITY_M_S2,
    ): Double {
        require(muS > 0.0) { "Sliding friction coefficient muS must be positive." }
        require(muR > 0.0) { "Rolling resistance coefficient muR must be positive." }
        require(g > 0.0) { "Gravity g must be positive." }
        val postImpactSlideSpeed = abs(initialCueSpeedMps) * abs(sin(cutAngleRad))
        val slideDistance = (12.0 * postImpactSlideSpeed * postImpactSlideSpeed) / (49.0 * muS * g)
        val rollingSpeed = stunRollingSpeed(postImpactSlideSpeed)
        val rollDistance = rollingStoppingDistanceMeters(rollingSpeed, muR, g)
        return slideDistance + rollDistance
    }
}

const val GRAVITY_M_S2: Double = MotionPhysics.GRAVITY_M_S2

fun stunRollingSpeed(initialSpeedMps: Double): Double =
    MotionPhysics.stunRollingSpeed(initialSpeedMps)

fun slideToRollTimeSeconds(
    initialSlipSpeedMps: Double,
    muS: Double,
    g: Double = GRAVITY_M_S2,
): Double = MotionPhysics.slideToRollTimeSeconds(initialSlipSpeedMps, muS, g)

fun rollingStoppingDistanceMeters(
    rollingSpeedMps: Double,
    muR: Double,
    g: Double = GRAVITY_M_S2,
): Double = MotionPhysics.rollingStoppingDistanceMeters(rollingSpeedMps, muR, g)

fun initialRollingSpeedForDistance(
    distanceMeters: Double,
    muR: Double,
    g: Double = GRAVITY_M_S2,
): Double = MotionPhysics.initialRollingSpeedForDistance(distanceMeters, muR, g)

fun stunStoppingDistanceMeters(
    initialCueSpeedMps: Double,
    cutAngleRad: Double,
    muS: Double,
    muR: Double,
    g: Double = GRAVITY_M_S2,
): Double = MotionPhysics.stunStoppingDistanceMeters(initialCueSpeedMps, cutAngleRad, muS, muR, g)
