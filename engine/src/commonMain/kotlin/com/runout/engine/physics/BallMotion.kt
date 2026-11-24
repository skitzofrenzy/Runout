package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.ClothParams
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Motion phase of a ball on the cloth, see `docs/research/physics.md` § Ball motion states.
 */
enum class BallMotionPhase {
    /** The contact point slips on the cloth; friction `muS * g` acts against the slip. */
    SLIDING,

    /** No slip: the ball slows at `muR * g` along a straight line. */
    ROLLING,

    /** Not moving across the cloth; any spin about the vertical axis keeps decaying ([BallMotion.sideSpinAt]). */
    STATIONARY,
}

/**
 * Full motion state of one ball on the bed of the table, SI units.
 *
 * - [position], [velocity]: of the ball's centre, in table coordinates.
 * - [spinVelocity] `w`: the spin about the two horizontal axes written as the surface velocity it gives the ball's
 *   bottom point relative to the centre, reversed: `w = R * (omegaY, -omegaX)`. The point touching the cloth then
 *   moves at the slip velocity `u = v - w`, so a ball rolls without slipping exactly when `w = v`; a stun ball has
 *   `w = 0`, a drawing ball has `w` pointing backwards. For a ball moving along `d` with spin factor
 *   `k = R * omega / v` (see [SpinPhysics.PreImpactCueState]) `w = k * v * d`.
 * - [sideSpinSurfaceSpeedMps]: `R * omegaZ`, the spin about the vertical axis, signed as on [ThrowAngles] (positive
 *   rotation of the table frame is positive). It decays on the cloth at `5/2 * muSp * g` whatever the ball is doing
 *   ([BallMotion.sideSpinAt]), is changed by cushion and ball-ball contacts, and does not bend the path of a ball on
 *   a level table.
 */
data class BallState(
    val position: Vec2,
    val velocity: Vec2 = Vec2.ZERO,
    val spinVelocity: Vec2 = Vec2.ZERO,
    val sideSpinSurfaceSpeedMps: Double = 0.0,
) {
    /** Velocity of the point touching the cloth: `u = v - w`. */
    val slipVelocity: Vec2 get() = velocity - spinVelocity

    /** Angular velocity about the table's x axis for a ball of radius [radiusMeters], rad/s. */
    fun omegaX(radiusMeters: Double): Double = -spinVelocity.y / radiusMeters

    /** Angular velocity about the table's y axis for a ball of radius [radiusMeters], rad/s. */
    fun omegaY(radiusMeters: Double): Double = spinVelocity.x / radiusMeters

    /** Angular velocity about the vertical axis for a ball of radius [radiusMeters], rad/s. */
    fun omegaZ(radiusMeters: Double): Double = sideSpinSurfaceSpeedMps / radiusMeters

    /**
     * Vertical spin factor along the direction of travel, `k = (w . v) / |v|^2`: `+1` natural roll, `0` stun,
     * negative backspin; `0.0` for a ball at rest.
     */
    fun spinFactorAlongTravel(): Double {
        val speedSq = velocity.lengthSquared()
        return if (speedSq <= 1e-18) 0.0 else spinVelocity.dot(velocity) / speedSq
    }

    /**
     * The spin about the vertical axis as the english a player would name: the side offset of a level tip (fraction of
     * the ball radius, negative left, as [TipOffset.sideRatio]) that gives a ball moving at this ball's speed this much
     * side spin, `-(R * omegaZ) / (5/2 * |v|)` ([SpinPhysics.sideSpinSurfaceSpeedAtImpactMps] inverted). It can pass
     * the miscue limit on a slow ball that still spins. `0.0` for a ball at rest.
     */
    fun sideSpinAsTipOffset(): Double {
        val speed = velocity.length()
        return if (speed <= 1e-6) 0.0 else -sideSpinSurfaceSpeedMps / (2.5 * speed)
    }

    /**
     * Kinetic energy per unit mass, J/kg, of a solid ball: `(v^2 + 2/5 * (|w|^2 + (R * omegaZ)^2)) / 2`.
     */
    fun kineticEnergyPerKg(): Double =
        0.5 * (velocity.lengthSquared() + 0.4 * (spinVelocity.lengthSquared() + sideSpinSurfaceSpeedMps * sideSpinSurfaceSpeedMps))

    companion object {
        /** A ball rolling without slip at [velocity]. */
        fun rolling(position: Vec2, velocity: Vec2, sideSpinSurfaceSpeedMps: Double = 0.0): BallState =
            BallState(position, velocity, velocity, sideSpinSurfaceSpeedMps)
    }
}

/**
 * Closed-form motion of a ball on cloth with sliding friction [slidingFrictionMuS] and rolling resistance
 * [rollingResistanceMuR], and the event-time helpers an event-based simulation needs.
 *
 * **Sliding.** With slip `u = v - w`, friction `muS * g` acts along `-u_hat`. Its torque turns `w` the other way at
 * `5/2` of that rate (solid sphere, `I = 2/5 m R^2`), so the slip shrinks along a fixed direction at `7/2 * muS * g`
 * and `u_hat` stays constant for the whole slide:
 *
 * - `v(t) = v0 - muS * g * t * u_hat`, `w(t) = w0 + 5/2 * muS * g * t * u_hat`,
 * - the path is a parabola, `x(t) = x0 + v0 * t - 1/2 * muS * g * t^2 * u_hat`,
 * - the slide ends at `t = 2 * |u0| / (7 * muS * g)` with `v_f = w_f = (5 * v0 + 2 * w0) / 7`.
 *
 * **Rolling.** `w = v`, the speed falls at `muR * g` along a straight line until the ball stops after
 * `|v| / (muR * g)`.
 *
 * **Spin about the vertical axis.** Spinning friction [spinningFrictionMuSp] brakes it at a constant
 * `5 * muSp * g / (2 R)`, so its surface speed `R * omegaZ` falls linearly at `5/2 * muSp * g` to zero, independent
 * of the ball's travel.
 *
 * Within one phase every ball moves with a constant acceleration ([acceleration]), so positions are quadratic in
 * time: a cushion line is reached at the root of a quadratic ([timeToReachCoordinate]) and two balls touch at the
 * first root of a quartic ([contactTime]).
 */
class BallMotion(
    slidingFrictionMuS: Double,
    rollingResistanceMuR: Double,
    val gravity: Double = MotionPhysics.GRAVITY_M_S2,
    spinningFrictionMuSp: Double = ClothParams.spinningFrictionForSliding(slidingFrictionMuS),
) {
    constructor(cloth: ClothParams) : this(
        cloth.slidingFrictionMuS,
        cloth.rollingResistanceMuR,
        MotionPhysics.GRAVITY_M_S2,
        cloth.spinningFrictionMuSp,
    )

    /** Magnitude of the sliding deceleration `muS * g`, m/s^2. */
    val slidingDeceleration: Double = slidingFrictionMuS.coerceAtLeast(1e-4) * gravity

    /** Magnitude of the rolling deceleration `muR * g`, m/s^2. */
    val rollingDeceleration: Double = rollingResistanceMuR.coerceAtLeast(1e-4) * gravity

    /** Rate at which the side-spin surface speed `R * omegaZ` falls, `5/2 * muSp * g`, m/s^2. */
    val spinningDeceleration: Double = 2.5 * spinningFrictionMuSp.coerceAtLeast(0.0) * gravity

    /** Side-spin surface speed `R * omegaZ` after [t] seconds: linear decay to zero, never past it. */
    fun sideSpinAt(state: BallState, t: Double): Double {
        val s = state.sideSpinSurfaceSpeedMps
        val left = abs(s) - spinningDeceleration * t.coerceAtLeast(0.0)
        return if (left <= 0.0) 0.0 else if (s > 0.0) left else -left
    }

    /** Time until the spin about the vertical axis has worn off. */
    fun timeToSpinDown(state: BallState): Double =
        if (spinningDeceleration <= 0.0) Double.POSITIVE_INFINITY else abs(state.sideSpinSurfaceSpeedMps) / spinningDeceleration

    fun phaseOf(state: BallState): BallMotionPhase = when {
        state.slipVelocity.length() > SLIP_EPSILON_MPS -> BallMotionPhase.SLIDING
        state.velocity.length() > SPEED_EPSILON_MPS -> BallMotionPhase.ROLLING
        else -> BallMotionPhase.STATIONARY
    }

    /** Constant acceleration of the ball's centre during its current phase. */
    fun acceleration(state: BallState): Vec2 = when (phaseOf(state)) {
        BallMotionPhase.SLIDING -> state.slipVelocity.normalized() * -slidingDeceleration
        BallMotionPhase.ROLLING -> state.velocity.normalized() * -rollingDeceleration
        BallMotionPhase.STATIONARY -> Vec2.ZERO
    }

    /**
     * Time until the current phase ends: the end of the slide `2|u| / (7 muS g)`, the stop of a rolling ball
     * `|v| / (muR g)`, or infinity at rest.
     */
    fun phaseDuration(state: BallState): Double = when (phaseOf(state)) {
        BallMotionPhase.SLIDING -> 2.0 * state.slipVelocity.length() / (7.0 * slidingDeceleration)
        BallMotionPhase.ROLLING -> state.velocity.length() / rollingDeceleration
        BallMotionPhase.STATIONARY -> Double.POSITIVE_INFINITY
    }

    /** Time until the ball stops slipping, `2|u0| / (7 muS g)`; `0.0` if it is not sliding. */
    fun timeToRoll(state: BallState): Double =
        if (phaseOf(state) == BallMotionPhase.SLIDING) phaseDuration(state) else 0.0

    /** Time until the ball comes to rest, slide and roll together; `0.0` at rest. */
    fun timeToStop(state: BallState): Double = when (phaseOf(state)) {
        BallMotionPhase.SLIDING -> {
            val tSlide = phaseDuration(state)
            tSlide + endOfPhase(state).velocity.length() / rollingDeceleration
        }
        BallMotionPhase.ROLLING -> phaseDuration(state)
        BallMotionPhase.STATIONARY -> 0.0
    }

    /** Centre position after [t] seconds of the current phase (`t` is clamped to the phase). */
    fun positionAt(state: BallState, t: Double): Vec2 {
        val tt = t.coerceIn(0.0, phaseDuration(state).coerceAtLeast(0.0))
        val a = acceleration(state)
        return state.position + state.velocity * tt + a * (0.5 * tt * tt)
    }

    /** Centre velocity after [t] seconds of the current phase (`t` is clamped to the phase). */
    fun velocityAt(state: BallState, t: Double): Vec2 {
        val tt = t.coerceIn(0.0, phaseDuration(state).coerceAtLeast(0.0))
        return state.velocity + acceleration(state) * tt
    }

    /** Spin surface velocity `w` after [t] seconds of the current phase (`t` is clamped to the phase). */
    fun spinAt(state: BallState, t: Double): Vec2 = when (phaseOf(state)) {
        BallMotionPhase.SLIDING -> {
            val tt = t.coerceIn(0.0, phaseDuration(state))
            state.spinVelocity + state.slipVelocity.normalized() * (2.5 * slidingDeceleration * tt)
        }
        BallMotionPhase.ROLLING -> velocityAt(state, t)
        BallMotionPhase.STATIONARY -> state.spinVelocity
    }

    /**
     * State after [t] seconds, which must not pass the end of the current phase; at the end of the phase use
     * [endOfPhase], which snaps the ball exactly into the next phase.
     */
    fun stateAt(state: BallState, t: Double): BallState {
        if (t >= phaseDuration(state)) return endOfPhase(state)
        return BallState(
            position = positionAt(state, t),
            velocity = velocityAt(state, t),
            spinVelocity = spinAt(state, t),
            sideSpinSurfaceSpeedMps = sideSpinAt(state, t),
        )
    }

    /**
     * State at the end of the current phase: a sliding ball becomes a rolling ball at `(5 * v0 + 2 * w0) / 7`
     * (or comes to rest if that is zero), a rolling ball comes to rest.
     */
    fun endOfPhase(state: BallState): BallState = when (phaseOf(state)) {
        BallMotionPhase.SLIDING -> {
            val vf = (state.velocity * 5.0 + state.spinVelocity * 2.0) / 7.0
            val tEnd = phaseDuration(state)
            val end = positionAt(state, tEnd)
            val side = sideSpinAt(state, tEnd)
            if (vf.length() <= SPEED_EPSILON_MPS) {
                BallState(end, Vec2.ZERO, Vec2.ZERO, side)
            } else {
                BallState.rolling(end, vf, side)
            }
        }
        BallMotionPhase.ROLLING -> BallState(
            position = positionAt(state, phaseDuration(state)),
            sideSpinSurfaceSpeedMps = sideSpinAt(state, phaseDuration(state)),
        )
        BallMotionPhase.STATIONARY -> state.copy(velocity = Vec2.ZERO, spinVelocity = Vec2.ZERO)
    }

    /** State after [t] seconds on open cloth, through as many phase changes as happen. */
    fun advance(state: BallState, t: Double): BallState {
        var s = state
        var remaining = t.coerceAtLeast(0.0)
        repeat(3) {
            if (phaseOf(s) == BallMotionPhase.STATIONARY) return s.copy(sideSpinSurfaceSpeedMps = sideSpinAt(s, remaining))
            val d = phaseDuration(s)
            if (remaining < d) return stateAt(s, remaining)
            remaining -= d
            s = endOfPhase(s)
        }
        return s
    }

    /**
     * Number of equal time pieces that keep every chord of the path over the next [duration] seconds of the current
     * phase within [toleranceMeters] of the true path: a parabola deviates from its chord by at most
     * `|a| * h^2 / 8` over a piece of length `h`. A straight path (rolling, or a slide whose slip is parallel to
     * its velocity) needs one piece.
     */
    fun chordPieces(state: BallState, duration: Double, toleranceMeters: Double = PATH_SAMPLE_TOLERANCE_METERS): Int {
        if (duration <= 0.0 || phaseOf(state) != BallMotionPhase.SLIDING) return 1
        val a = acceleration(state)
        val v = state.velocity
        val bend = abs(v.cross(a))
        if (bend <= 1e-9 * (v.length() * a.length()).coerceAtLeast(1e-12)) return 1
        val n = ceil(duration * sqrt(a.length() / (8.0 * toleranceMeters))).toInt()
        return n.coerceIn(1, MAX_CHORD_PIECES)
    }

    /**
     * Time within the current phase at which a ball moving straight has covered [distanceMeters], or `null` if it
     * does not get that far before the phase ends. Only meaningful for straight motion (rolling, or sliding with the
     * slip along the velocity).
     */
    fun timeToTravelStraight(state: BallState, distanceMeters: Double): Double? {
        if (distanceMeters <= 0.0) return 0.0
        val speed = state.velocity.length()
        if (speed <= SPEED_EPSILON_MPS) return null
        val decel = -acceleration(state).dot(state.velocity / speed)
        val tEnd = phaseDuration(state)
        val t = if (abs(decel) < 1e-12) {
            distanceMeters / speed
        } else {
            val disc = speed * speed - 2.0 * decel * distanceMeters
            if (disc < 0.0) return null
            (speed - sqrt(disc)) / decel
        }
        return if (t in 0.0..tEnd) t else null
    }

    companion object {
        /** Slip speed below which a ball counts as rolling, m/s. */
        const val SLIP_EPSILON_MPS: Double = 1e-4

        /** Speed below which a ball counts as at rest, m/s. */
        const val SPEED_EPSILON_MPS: Double = 1e-4

        /** Largest deviation of a path chord from the true curve when paths are sampled, metres. */
        const val PATH_SAMPLE_TOLERANCE_METERS: Double = 0.001

        private const val MAX_CHORD_PIECES: Int = 64

        /**
         * Distance within which a ball's centre counts as being on a bound it can strike (a cushion line, a pocket's
         * mouth line), metres. Balls come to rest exactly on a cushion line (frozen balls, contacts that push a ball
         * against a cushion), and rounding can leave one a fraction of a micron off it either way; within this
         * distance the ball is treated as touching.
         */
        const val BOUND_CONTACT_TOLERANCE_METERS: Double = 1e-7

        /**
         * Smallest time in `(tMin, tMax]` at which a coordinate `p(t) = p0 + v0 t + a t^2 / 2` reaches [bound]
         * while moving towards it from the inside (`direction` `+1`: increasing through the bound, `-1`:
         * decreasing), or `null`. A coordinate that starts on the bound is not found here; see
         * [timeToContactBound].
         */
        fun timeToReachCoordinate(
            p0: Double,
            v0: Double,
            a: Double,
            bound: Double,
            direction: Int,
            tMax: Double,
            tMin: Double = 1e-9,
        ): Double? {
            val c = p0 - bound
            val roots = quadraticRoots(0.5 * a, v0, c)
            var best: Double? = null
            for (t in roots) {
                if (t <= tMin || t > tMax) continue
                val slope = v0 + a * t
                if (slope * direction <= 0.0) continue
                if (best == null || t < best) best = t
            }
            return best
        }

        /**
         * Time in `[0, tMax]` at which a coordinate `p(t) = p0 + v0 t + a t^2 / 2` that must stay on the inside of
         * [bound] (`direction` as in [timeToReachCoordinate]) first meets it moving outward, or `null`.
         *
         * A coordinate already within [tolerance] of the bound, or past it, meets it at once (`0.0`) when it is moving
         * outward (`v0 * direction > 0`); one on the bound that is not moving outward has no contact now, and its
         * next one, if any, is the first later root. Otherwise this is the first root in `(0, tMax]`. A contact found
         * here must remove the outward velocity, or it is found again at once.
         */
        fun timeToContactBound(
            p0: Double,
            v0: Double,
            a: Double,
            bound: Double,
            direction: Int,
            tMax: Double,
            tolerance: Double = BOUND_CONTACT_TOLERANCE_METERS,
        ): Double? {
            val gap = (bound - p0) * direction
            if (gap <= tolerance && v0 * direction > 0.0) return 0.0
            return timeToReachCoordinate(p0, v0, a, bound, direction, tMax, tMin = 0.0)
        }

        /** Real roots of `a t^2 + b t + c = 0`, in no particular order. */
        internal fun quadraticRoots(a: Double, b: Double, c: Double): DoubleArray {
            if (abs(a) < 1e-14) {
                return if (abs(b) < 1e-14) DoubleArray(0) else doubleArrayOf(-c / b)
            }
            val disc = b * b - 4.0 * a * c
            if (disc < 0.0) return DoubleArray(0)
            val sq = sqrt(disc)
            // Numerically stable pair.
            val q = -0.5 * (b + if (b >= 0.0) sq else -sq)
            return if (abs(q) < 1e-300) doubleArrayOf(0.0) else doubleArrayOf(q / a, c / q)
        }

        /**
         * First time in `(0, horizon]` at which two balls moving with constant accelerations come within
         * [contactDistance] of each other while approaching, or `null`.
         *
         * The separation `d(t) = (pB - pA) + (vB - vA) t + (aB - aA) t^2 / 2` is a quadratic Bezier curve over any
         * time interval, so it lies inside the triangle of its three control points. The search discards an
         * interval when that triangle stays farther than [contactDistance] from the origin and otherwise halves it,
         * earlier half first, until the interval is shorter than a nanosecond or its chord is below `1e-7 m`. That
         * finds the first root of the quartic `|d(t)|^2 = D^2`, including grazing contacts, without a polynomial
         * solver; the contact time is accurate to the stopping size. A pair that starts closer than
         * [contactDistance] collides at once (`0.0`) when it is approaching and is ignored when it is separating.
         */
        fun contactTime(
            positionA: Vec2,
            velocityA: Vec2,
            accelerationA: Vec2,
            positionB: Vec2,
            velocityB: Vec2,
            accelerationB: Vec2,
            contactDistance: Double,
            horizon: Double,
        ): Double? {
            if (!(horizon > 0.0) || contactDistance <= 0.0) return null
            val p = positionB - positionA
            val v = velocityB - velocityA
            val c = (accelerationB - accelerationA) * 0.5
            val d2 = contactDistance * contactDistance
            if (p.lengthSquared() <= d2) {
                return if (p.dot(v) < 0.0) 0.0 else null
            }
            val tEnd = if (horizon.isFinite()) horizon else return null
            return earliestEntry(p, v, c, d2, 0.0, tEnd, 0)
        }

        private fun earliestEntry(p: Vec2, v: Vec2, c: Vec2, d2: Double, t0: Double, t1: Double, depth: Int): Double? {
            val h = t1 - t0
            val p0 = p + v * t0 + c * (t0 * t0)
            val v0 = v + c * (2.0 * t0)
            val p2 = p + v * t1 + c * (t1 * t1)
            val p1 = p0 + v0 * (0.5 * h)
            if (triangleDistanceSquaredToOrigin(p0, p1, p2) > d2) return null
            if (p0.lengthSquared() <= d2) return t0
            val tiny = h < 1e-9 || (p1 - p0).lengthSquared() + (p2 - p1).lengthSquared() < 1e-14
            if (tiny || depth >= MAX_CONTACT_SEARCH_DEPTH) {
                return if (p2.lengthSquared() <= d2) t1 else null
            }
            val mid = 0.5 * (t0 + t1)
            return earliestEntry(p, v, c, d2, t0, mid, depth + 1) ?: earliestEntry(p, v, c, d2, mid, t1, depth + 1)
        }

        private const val MAX_CONTACT_SEARCH_DEPTH: Int = 60

        private fun triangleDistanceSquaredToOrigin(a: Vec2, b: Vec2, c: Vec2): Double {
            val area = (b - a).cross(c - a)
            if (abs(area) > 1e-18) {
                val s1 = (b - a).cross(-a)
                val s2 = (c - b).cross(-b)
                val s3 = (a - c).cross(-c)
                val inside = if (area > 0.0) s1 >= 0.0 && s2 >= 0.0 && s3 >= 0.0 else s1 <= 0.0 && s2 <= 0.0 && s3 <= 0.0
                if (inside) return 0.0
            }
            return min(segmentDistanceSquaredToOrigin(a, b), min(segmentDistanceSquaredToOrigin(b, c), segmentDistanceSquaredToOrigin(a, c)))
        }

        private fun segmentDistanceSquaredToOrigin(a: Vec2, b: Vec2): Double {
            val ab = b - a
            val lenSq = ab.lengthSquared()
            val t = if (lenSq < 1e-24) 0.0 else (-a.dot(ab) / lenSq).coerceIn(0.0, 1.0)
            return (a + ab * t).lengthSquared()
        }
    }
}

/**
 * Collision of two balls of equal mass at the moment they touch.
 *
 * Along the line of centres `n` the struck ball gains `(1 + e)/2` of the approach speed and the striking ball keeps
 * `(1 - e)/2` of it; everything across the line of centres stays where it was, apart from the throw: friction at the
 * contact moves the struck ball off the line of centres by the throw angle of [SpinPhysics.computeThrowAnglesRadians]
 * and gives the striking ball the equal and opposite tangential impulse. That sideways impulse acts at the balls'
 * equators, so it also turns both balls about the vertical axis the same way, by `Δ(R * omegaZ) = 5/2 * j_t` each
 * (`j_t` the tangential impulse per unit mass): the gearing that hands some english to the object ball. The spin
 * about the horizontal axes is unchanged by the contact.
 *
 * The throw law is written for a cue ball running into a ball at rest; for two moving balls it is evaluated in the
 * struck ball's frame (relative velocity, the sum of both balls' spins sliding at the contact point), which is the
 * same contact seen by a moving observer.
 */
object BallBallContact {
    /** Result of [resolve]: the two balls' states just after the contact and the throw that was applied. */
    data class Outcome(
        val striking: BallState,
        val struck: BallState,
        val throwRadians: Double,
    )

    /**
     * Applies the contact between [striking] and [struck] along the unit line of centres [lineOfCentres]
     * (pointing from the striking ball to the struck ball). Nothing changes when the balls are not approaching
     * along that line.
     */
    fun resolve(
        striking: BallState,
        struck: BallState,
        lineOfCentres: Vec2,
        restitution: Double = MotionPhysics.BALL_BALL_RESTITUTION,
        ballBallFrictionMu: Double = SpinPhysics.DEFAULT_BALL_BALL_FRICTION_MU,
    ): Outcome {
        val n = lineOfCentres.normalized()
        val relative = striking.velocity - struck.velocity
        val vn = relative.dot(n)
        if (vn <= 0.0) return Outcome(striking, struck, 0.0)
        val throwRad = throwAngle(striking, struck, n, ballBallFrictionMu)
        val jn = 0.5 * (1.0 + restitution.coerceIn(0.0, 1.0)) * vn
        val jt = jn * tan(throwRad)
        val impulse = n * jn + n.rotate(-PI / 2.0) * jt
        // The tangential impulse on the struck ball points along n.rotate(-90°); about the vertical axis it turns
        // both balls the same way, R * omegaZ changing by (R^2 m / I) * jt = 5/2 * jt.
        val sideSpinChange = 2.5 * jt
        return Outcome(
            striking = striking.copy(
                velocity = striking.velocity - impulse,
                sideSpinSurfaceSpeedMps = striking.sideSpinSurfaceSpeedMps + sideSpinChange,
            ),
            struck = struck.copy(
                velocity = struck.velocity + impulse,
                sideSpinSurfaceSpeedMps = struck.sideSpinSurfaceSpeedMps + sideSpinChange,
            ),
            throwRadians = throwRad,
        )
    }

    /**
     * Throw, in radians, of a contact along [lineOfCentres], signed as on [ThrowAngles] (relative to the striking
     * ball's velocity in the struck ball's frame).
     */
    fun throwAngle(
        striking: BallState,
        struck: BallState,
        lineOfCentres: Vec2,
        ballBallFrictionMu: Double = SpinPhysics.DEFAULT_BALL_BALL_FRICTION_MU,
    ): Double {
        if (ballBallFrictionMu <= 0.0) return 0.0
        val relative = striking.velocity - struck.velocity
        val vn = relative.dot(lineOfCentres)
        if (vn <= 1e-9) return 0.0
        val speed = relative.length()
        val cut = relative.angleTo(lineOfCentres)
        // Vertical sliding at the contact point is -(wA + wB) . n; the throw law takes it as k * v * cos(cut).
        val spinFactor = (striking.spinVelocity + struck.spinVelocity).dot(lineOfCentres) / vn
        val sideSpin = striking.sideSpinSurfaceSpeedMps + struck.sideSpinSurfaceSpeedMps
        return SpinPhysics.computeThrowAnglesRadians(
            cutAngleSignedRadians = cut,
            impactSpeedMps = speed,
            impactSpinFactorK = spinFactor,
            sideSpinSurfaceSpeedMps = sideSpin,
            ballBallFrictionMu = ballBallFrictionMu,
        ).totalRadians
    }

    /**
     * Line of centres at which a ball arriving with [striking]'s motion throws a ball at rest exactly along
     * [thrownDirection]: `n = thrownDirection.rotate(theta)` where `theta` solves `theta = throw(n(theta))`. This is
     * the contact a player makes who aims at the throw-compensated ghost ball.
     *
     * The equation is solved for the angle `theta`: one fixed-point step, then secant steps on
     * `h(theta) = throw(n(theta)) - theta`, each held to at most a few fixed-point steps' length, until
     * `|h| <= 1e-12` rad or [iterations] evaluations of the throw. The plain fixed-point map can contract slowly
     * (thin cuts with side spin at low speed), which the secant steps do not mind.
     */
    fun lineOfCentresForThrownDirection(
        striking: BallState,
        thrownDirection: Vec2,
        ballBallFrictionMu: Double = SpinPhysics.DEFAULT_BALL_BALL_FRICTION_MU,
        iterations: Int = MAX_THROW_SOLVE_ITERATIONS,
    ): Vec2 {
        val target = thrownDirection.normalized()
        if (ballBallFrictionMu <= 0.0) return target
        val atRest = BallState(position = striking.position + target)
        fun residual(theta: Double): Double = throwAngle(striking, atRest, target.rotate(theta), ballBallFrictionMu) - theta

        var previous = 0.0
        var previousResidual = residual(previous)
        if (abs(previousResidual) <= THROW_SOLVE_TOLERANCE_RAD) return target
        var current = previousResidual
        var currentResidual = residual(current)
        var evaluations = 2
        while (abs(currentResidual) > THROW_SOLVE_TOLERANCE_RAD && evaluations < max(2, iterations)) {
            val fixedPointStep = currentResidual
            val slope = currentResidual - previousResidual
            var step = if (slope != 0.0) -currentResidual * (current - previous) / slope else fixedPointStep
            if (!step.isFinite() || abs(step) > MAX_SECANT_TO_FIXED_POINT_STEP * abs(fixedPointStep)) step = fixedPointStep
            previous = current
            previousResidual = currentResidual
            current += step
            currentResidual = residual(current)
            evaluations++
        }
        return target.rotate(current)
    }

    /** Most evaluations of the throw that [lineOfCentresForThrownDirection] makes. */
    const val MAX_THROW_SOLVE_ITERATIONS: Int = 12

    /** Residual, in radians, at which [lineOfCentresForThrownDirection] stops. */
    private const val THROW_SOLVE_TOLERANCE_RAD: Double = 1e-12

    /** Longest secant step, in units of the fixed-point step at the same point. */
    private const val MAX_SECANT_TO_FIXED_POINT_STEP: Double = 4.0
}
