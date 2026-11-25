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
     * Default ball-ball restitution of phenolic balls; a table carries its own as
     * [com.runout.engine.model.BallContactParams.restitution].
     */
    const val BALL_BALL_RESTITUTION: Double = 0.93

    /**
     * Initial translational speed (m/s) imparted to a stationary equal-mass object ball struck at
     * [cueImpactSpeedMps] and cut angle [cutAngleRad] under restitution [restitution].
     */
    fun transferredObjectBallInitialSpeedMps(
        cueImpactSpeedMps: Double,
        cutAngleRad: Double,
        restitution: Double = BALL_BALL_RESTITUTION,
    ): Double {
        if (cueImpactSpeedMps <= 0.0) return 0.0
        val cosCut = kotlin.math.cos(abs(cutAngleRad)).coerceAtLeast(0.0)
        return 0.5 * (1.0 + restitution.coerceIn(0.0, 1.0)) * cueImpactSpeedMps * cosCut
    }

    /**
     * Total slide-plus-roll stopping distance in metres for a stationary object ball that receives
     * initial post-collision translational speed [initialObjectSpeedMps] with zero initial spin
     * (skidding down to `5/7` speed under [muS] before rolling to rest under [muR]).
     */
    fun objectBallStoppingDistanceMeters(
        initialObjectSpeedMps: Double,
        muS: Double,
        muR: Double,
        g: Double = GRAVITY_M_S2,
    ): Double {
        val v = abs(initialObjectSpeedMps)
        if (v <= 1e-9) return 0.0
        val safeMuS = muS.coerceAtLeast(1e-4)
        val safeMuR = muR.coerceAtLeast(1e-4)
        val slideDistance = (12.0 * v * v) / (49.0 * safeMuS * g)
        val rollingSpeed = stunRollingSpeed(v)
        val rollDistance = rollingStoppingDistanceMeters(rollingSpeed, safeMuR, g)
        return slideDistance + rollDistance
    }

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
     * Speed in m/s of a ball that starts rolling at [rollingSpeedMps] and has rolled [distanceMeters] under
     * rolling resistance [muR]: `v = sqrt(v0^2 - 2 * mu_r * g * d)`, or `0.0` once it has come to rest.
     */
    fun rollingSpeedAfterDistanceMps(
        rollingSpeedMps: Double,
        distanceMeters: Double,
        muR: Double,
        g: Double = GRAVITY_M_S2,
    ): Double {
        val remaining = rollingStoppingDistanceMeters(rollingSpeedMps, muR, g) - distanceMeters.coerceAtLeast(0.0)
        return if (remaining <= 0.0) 0.0 else initialRollingSpeedForDistance(remaining, muR, g)
    }

    /**
     * Centre speed in m/s at which a ball struck at [initialSpeedMps] with spin factor
     * [initialSpinFactorK] (`k = R * omega / v`: `+1` natural roll, `0` no spin, negative backspin)
     * stops slipping and starts to roll: `v_roll = v0 * (5 + 2 * k0) / 7`.
     *
     * For `k0 = 0` this is the `5/7` stun rule of [stunRollingSpeed]; it is valid while `k0 > -2.5`
     * (the cue-tip offset limit keeps `k0` above `-1.25`), so the ball never reverses on the line of travel.
     */
    fun slidingEndSpeedMps(initialSpeedMps: Double, initialSpinFactorK: Double): Double =
        (initialSpeedMps * (5.0 + 2.0 * initialSpinFactorK) / 7.0).coerceAtLeast(0.0)

    /**
     * Distance in metres a sliding ball needs to change its centre speed from [fromSpeedMps] to [toSpeedMps]
     * under sliding friction [muS]: `d = |v_a^2 - v_b^2| / (2 * mu_s * g)`.
     */
    fun slidingDistanceBetweenSpeedsMeters(
        fromSpeedMps: Double,
        toSpeedMps: Double,
        muS: Double,
        g: Double = GRAVITY_M_S2,
    ): Double {
        require(muS > 0.0) { "Sliding friction coefficient muS must be positive." }
        require(g > 0.0) { "Gravity g must be positive." }
        return abs(fromSpeedMps * fromSpeedMps - toSpeedMps * toSpeedMps) / (2.0 * muS * g)
    }

    /**
     * Centre speed in m/s of a ball that is struck at [initialSpeedMps] with spin factor [initialSpinFactorK] and
     * has slid [distanceMeters] under [muS]. Friction opposes the slip, so a ball with less spin than natural roll
     * (`k0 < 1`) slows down, `v^2 = v0^2 - 2 * mu_s * g * d`, and one with overspin (`k0 > 1`) speeds up,
     * `v^2 = v0^2 + 2 * mu_s * g * d`. Only valid until slipping stops, see [slidingEndSpeedMps].
     */
    fun slidingSpeedAfterDistanceMps(
        initialSpeedMps: Double,
        initialSpinFactorK: Double,
        distanceMeters: Double,
        muS: Double,
        g: Double = GRAVITY_M_S2,
    ): Double {
        require(muS > 0.0) { "Sliding friction coefficient muS must be positive." }
        require(g > 0.0) { "Gravity g must be positive." }
        val delta = 2.0 * muS * g * distanceMeters.coerceAtLeast(0.0)
        val vSq = if (initialSpinFactorK > 1.0) {
            initialSpeedMps * initialSpeedMps + delta
        } else {
            initialSpeedMps * initialSpeedMps - delta
        }
        return sqrt(vSq.coerceAtLeast(0.0))
    }

    /**
     * Speed in m/s of a ball moving in a straight line that starts at [initialSpeedMps] with spin factor
     * [initialSpinFactorK] along its travel (`0` for a ball just struck by another ball) and has covered
     * [distanceMeters]: it slides under [muS] until it rolls at `v0 * (5 + 2 * k0) / 7`
     * ([slidingEndSpeedMps]), then rolls under [muR]; `0.0` once it has stopped. The object ball after a contact
     * slides this way, so this is its speed on the way to the pocket.
     */
    fun slideThenRollSpeedAfterDistanceMps(
        initialSpeedMps: Double,
        distanceMeters: Double,
        muS: Double,
        muR: Double,
        initialSpinFactorK: Double = 0.0,
        g: Double = GRAVITY_M_S2,
    ): Double {
        val v0 = abs(initialSpeedMps)
        if (v0 <= 1e-9) return 0.0
        val d = distanceMeters.coerceAtLeast(0.0)
        val vRoll = slidingEndSpeedMps(v0, initialSpinFactorK)
        val dSlide = slidingDistanceBetweenSpeedsMeters(v0, vRoll, muS, g)
        return if (d < dSlide) {
            slidingSpeedAfterDistanceMps(v0, initialSpinFactorK, d, muS, g)
        } else {
            rollingSpeedAfterDistanceMps(vRoll, d - dSlide, muR, g)
        }
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
