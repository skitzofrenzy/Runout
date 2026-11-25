package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableSpec
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

typealias GhostBallAim = AimGeometry
typealias CueBallTrajectory = TrajectoryPath

val TrajectoryPath.scratchPocketId: PocketId?
    get() = hitsScratchPocket

/**
 * Normalized 2D cue-tip offset on the cue ball face in SI/dimensionless ratios relative to ball radius `R`.
 *
 * - [sideRatio]: negative = left english, positive = right english (normalized by `R`).
 * - [verticalRatio]: negative = draw (below centre), `0.0` = centre ball, positive = follow (above centre),
 *   normalized by `R`. This is where the tip meets the ball; the spin the cue ball has when it reaches the object
 *   ball also depends on speed and distance, see [SpinPhysics.computePreImpactCueState].
 * - [elevationRadians]: cue butt elevation in radians (default ~3 degrees).
 */
data class TipOffset(
    val sideRatio: Double = 0.0,
    val verticalRatio: Double = 0.0,
    val elevationRadians: Double = 0.05235987755982989,
) {
    val magnitudeRatio: Double = hypot(sideRatio, verticalRatio)

    val isWithinMiscueLimit: Boolean = magnitudeRatio <= MAX_SAFE_OFFSET_RATIO + 1e-9

    val clampedSideRatio: Double = if (magnitudeRatio <= MAX_SAFE_OFFSET_RATIO || magnitudeRatio < 1e-12) {
        sideRatio
    } else {
        sideRatio * (MAX_SAFE_OFFSET_RATIO / magnitudeRatio)
    }

    val clampedVerticalRatio: Double = if (magnitudeRatio <= MAX_SAFE_OFFSET_RATIO || magnitudeRatio < 1e-12) {
        verticalRatio
    } else {
        verticalRatio * (MAX_SAFE_OFFSET_RATIO / magnitudeRatio)
    }

    /**
     * Classifies the strike by tip height alone. A ball hit at centre only arrives at the object ball without
     * spin on a short shot; use [SpinPhysics.computePreImpactCueState] for the state at contact.
     */
    fun toSpinPreset(): CueBallSpinPreset = when {
        clampedVerticalRatio > 0.12 -> CueBallSpinPreset.FOLLOW
        clampedVerticalRatio < -0.12 -> CueBallSpinPreset.DRAW
        else -> CueBallSpinPreset.STUN
    }

    /**
     * Multi-level human-readable description of where the tip meets the ball: vertical offset, side english and mixes
     * (e.g. `"Center Ball"`, `"Touch Low + Touch of Left"`, `"Controlled Follow + 1/2-Tip Right"`, `"Max Power Draw"`).
     * It names the tip position only: whether the cue ball arrives with stun, follow or draw also depends on speed
     * and distance ([SpinPhysics.computePreImpactCueState]).
     */
    fun describeSpinLabel(short: Boolean = false): String {
        val side = clampedSideRatio
        val vert = clampedVerticalRatio
        val absSide = abs(side)
        val absVert = abs(vert)
        if (hypot(side, vert) < 0.06) {
            return if (short) "Center" else "Center Ball"
        }

        val vertText = when {
            absVert < 0.06 -> null
            absVert < 0.16 -> if (vert > 0.0) {
                if (short) "Touch Follow" else "Touch of Follow"
            } else {
                if (short) "Touch Low" else "Touch of Low"
            }
            absVert < 0.28 -> if (vert > 0.0) {
                if (short) "Ctrl Follow" else "Controlled Follow"
            } else {
                if (short) "Ctrl Draw" else "Controlled Draw"
            }
            absVert < 0.40 -> if (vert > 0.0) "Firm Follow" else "Firm Draw"
            else -> if (vert > 0.0) {
                if (short) "Max Follow" else "Max Top Follow"
            } else {
                if (short) "Max Draw" else "Max Power Draw"
            }
        }

        val sideDir = if (side < 0.0) "Left" else "Right"
        val sideText = when {
            absSide < 0.06 -> null
            absSide < 0.16 -> if (short) "Touch $sideDir" else "Touch of $sideDir"
            absSide < 0.28 -> "1/2-Tip $sideDir"
            absSide < 0.40 -> if (short) "Firm $sideDir" else "Firm $sideDir English"
            else -> if (short) "Max $sideDir" else "Max $sideDir English"
        }

        return when {
            vertText != null && sideText != null -> {
                val compactVert = when {
                    absVert in 0.06..<0.16 && vert < 0.0 -> "Touch Low"
                    else -> vertText
                }
                val compactSide = sideText.removeSuffix(" English")
                "$compactVert + $compactSide"
            }
            vertText != null -> vertText
            sideText != null -> "Center + $sideText"
            else -> if (short) "Center" else "Center Ball"
        }
    }

    companion object {
        const val MAX_SAFE_OFFSET_RATIO: Double = 0.50

        /**
         * Fixed nominal tip for [preset]: centre, firm follow (`+0.35`) or firm draw (`-0.38`). The preset names
         * the spin state at the object ball, which this tip produces only on a short shot; to get that state at a
         * given speed and distance use [SpinPhysics.tipForImpactState].
         */
        fun fromPreset(preset: CueBallSpinPreset, sideRatio: Double = 0.0): TipOffset {
            val verticalRatio = when (preset) {
                CueBallSpinPreset.STUN -> 0.0
                CueBallSpinPreset.FOLLOW -> 0.35
                CueBallSpinPreset.DRAW -> -0.38
            }
            return TipOffset(
                sideRatio = sideRatio,
                verticalRatio = verticalRatio,
            )
        }

        fun standardCandidates(): List<TipOffset> {
            val verticals = listOf(-0.38, 0.0, 0.35)
            val sides = listOf(-0.30, 0.0, 0.30)
            val candidates = ArrayList<TipOffset>(verticals.size * sides.size)
            for (vertical in verticals) {
                for (side in sides) {
                    candidates += TipOffset(sideRatio = side, verticalRatio = vertical)
                }
            }
            return candidates
        }

        /**
         * Graded tip candidates for realistic run-out planning, favoring center, drag-stun,
         * controlled follow/draw, and mild half-tip english mixes before resorting to firm english.
         */
        fun gradedPlannerCandidates(): List<TipOffset> = listOf(
            TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
            TipOffset(sideRatio = 0.0, verticalRatio = 0.18),
            TipOffset(sideRatio = 0.0, verticalRatio = 0.35),
            TipOffset(sideRatio = 0.0, verticalRatio = -0.14),
            TipOffset(sideRatio = 0.0, verticalRatio = -0.26),
            TipOffset(sideRatio = 0.0, verticalRatio = -0.40),
            TipOffset(sideRatio = -0.16, verticalRatio = 0.0),
            TipOffset(sideRatio = 0.16, verticalRatio = 0.0),
            TipOffset(sideRatio = -0.16, verticalRatio = 0.20),
            TipOffset(sideRatio = 0.16, verticalRatio = 0.20),
            TipOffset(sideRatio = -0.16, verticalRatio = -0.22),
            TipOffset(sideRatio = 0.16, verticalRatio = -0.22),
            TipOffset(sideRatio = -0.30, verticalRatio = 0.28),
            TipOffset(sideRatio = 0.30, verticalRatio = 0.28),
            TipOffset(sideRatio = -0.28, verticalRatio = -0.34),
            TipOffset(sideRatio = 0.28, verticalRatio = -0.34),
        )
    }
}

/**
 * Throw of the object ball at ball-ball contact, in radians.
 *
 * **Sign convention (defined here, used everywhere).** The cut angle is signed: it is positive when the line of
 * centres is the cue ball's direction of travel turned by a positive angle (`aim.angleTo(lineOfCentres) > 0`,
 * [Vec2.rotate] with a positive angle). A throw has the same sign as the cut it belongs to: a positive
 * throw moves the object ball off the line of centres *toward the cue ball's line of travel*, so its thrown
 * direction is `lineOfCentres.rotate(-throw)` and the ghost ball that produces a wanted direction `d` lies on
 * the line of centres `d.rotate(+throw)`, a larger cut (a thinner hit). Side spin is signed the same way: a
 * positive `R * omegaZ` turns the cue-ball surface at the contact in the direction of positive rotation (clockwise
 * seen from above, which is left english); right english is negative.
 *
 * [cutOnlyRadians] is the throw with no side spin (`R * omegaZ = 0`), the part that comes from the cut and the
 * follow/draw alone; [totalRadians] includes the side spin; [spinRadians] is the remainder `total - cutOnly`.
 */
data class ThrowAngles(
    val cutOnlyRadians: Double,
    val totalRadians: Double,
) {
    val spinRadians: Double get() = totalRadians - cutOnlyRadians

    companion object {
        val NONE = ThrowAngles(0.0, 0.0)
    }
}

/**
 * Spin-aware aiming compensation breakdown around a geometric ghost-ball solution.
 *
 * Throw angles follow the convention documented on [ThrowAngles]: positive means the object ball is pushed off
 * the line of centres toward the cue ball's line of travel, the same sign as the signed cut angle.
 * [totalThrowAngleRadians] is the throw of the contact that is actually played (cut angle, impact speed and
 * impact spin of [compensatedGhostBallCenter]); [cutThrowAngleRadians] is the part that remains with no side spin
 * and [spinThrowAngleRadians] is the remainder (`total - cut`) that side spin adds.
 *
 * The object ball leaves along [geometricAim]'s pot line because [compensatedGhostBallCenter] is the ghost ball
 * whose thrown direction is that line, see [SpinPhysics.solveCompensatedAim].
 */
data class SpinCompensatedAim(
    val geometricAim: GhostBallAim,
    val squirtAngleRadians: Double,
    val swerveAngleRadians: Double,
    val cutThrowAngleRadians: Double,
    val spinThrowAngleRadians: Double,
    val totalThrowAngleRadians: Double,
    val netCueAimAdjustmentRadians: Double,
    val compensatedGhostBallCenter: Vec2,
)

/**
 * Closed-form cue-strike spin physics in SI units: squirt, swerve, the cue ball's slide before contact, cut- and
 * spin-induced throw, and the cue ball's state and path after it meets the object ball.
 */
object SpinPhysics {
    /** Constant term `a` of the ball-ball friction curve `mu(v_rel) = a + b * exp(-c * v_rel)`. */
    private const val BALL_BALL_FRICTION_A: Double = 0.009951

    /** Speed-dependent amplitude `b` of the ball-ball friction curve. */
    private const val BALL_BALL_FRICTION_B: Double = 0.108

    /** Decay rate `c`, in s/m, of the ball-ball friction curve. */
    private const val BALL_BALL_FRICTION_C: Double = 1.088

    /** Relative sliding speed at which the friction curve is quoted and calibrated, in m/s. */
    const val BALL_BALL_FRICTION_REFERENCE_SPEED_MPS: Double = 1.0

    /**
     * Ball-ball friction of typical phenolic balls at [BALL_BALL_FRICTION_REFERENCE_SPEED_MPS] of relative sliding
     * speed, about `0.046`. A table's own ball-ball friction is expressed the same way (see
     * [computeThrowAnglesRadians]) so that one number describes the whole speed-dependent curve.
     */
    val DEFAULT_BALL_BALL_FRICTION_MU: Double =
        BALL_BALL_FRICTION_A + BALL_BALL_FRICTION_B * exp(-BALL_BALL_FRICTION_C * BALL_BALL_FRICTION_REFERENCE_SPEED_MPS)

    /**
     * Largest tangential-to-normal impulse ratio the contact can transfer before the surfaces stop slipping:
     * the no-slip (gearing) limit `1/7` of two equal solid spheres.
     */
    private const val NO_SLIP_IMPULSE_RATIO: Double = 1.0 / 7.0

    /** Fixed-point iterations that [solveCompensatedAim] allows; the map contracts by roughly a factor of ten. */
    private const val COMPENSATION_MAX_ITERATIONS: Int = 12

    /** Change in the throw, in radians, below which [solveCompensatedAim] stops iterating. */
    private const val COMPENSATION_TOLERANCE_RAD: Double = 1e-10

    /**
     * Computes cue-ball deflection (squirt) angle in radians when striking with [tip] and [shaftType].
     * Side spin pushes the cue ball away from the tip side.
     */
    fun computeSquirtAngleRadians(tip: TipOffset, shaftType: ShaftType): Double {
        val kShaft = if (shaftType == ShaftType.LOW_DEFLECTION) 0.044 else 0.076
        return -kShaft * tip.clampedSideRatio
    }

    /**
     * Computes cue-ball swerve angle in radians over [cueToGhostDistanceMeters] for an elevated cue.
     * Side spin curves the cue ball toward the tip side.
     */
    fun computeSwerveAngleRadians(
        tip: TipOffset,
        cueSpeedMps: Double,
        cueToGhostDistanceMeters: Double,
    ): Double {
        val safeSpeed = cueSpeedMps.coerceAtLeast(0.35)
        val distFactor = (cueToGhostDistanceMeters.coerceAtLeast(0.0) / (safeSpeed * 0.85)).coerceIn(0.15, 1.4)
        return 0.18 * tip.clampedSideRatio * sin(tip.elevationRadians.coerceIn(0.0, 0.55)) * distFactor
    }

    /**
     * Ball-ball friction coefficient at a relative sliding speed of [relativeSlidingSpeedMps] between the two
     * ball surfaces: `mu = a + b * exp(-c * v_rel)` with `a = 0.009951`, `b = 0.108`, `c = 1.088 s/m`
     * (Alciatore, technical proof A.14). Friction falls as the surfaces slide faster.
     *
     * [ballBallFrictionMu] is the table's friction at [BALL_BALL_FRICTION_REFERENCE_SPEED_MPS]; the whole curve is
     * scaled by `ballBallFrictionMu / DEFAULT_BALL_BALL_FRICTION_MU`, which is the single parameter the
     * calibration drill fits.
     */
    fun ballBallFrictionAtSlidingSpeed(
        relativeSlidingSpeedMps: Double,
        ballBallFrictionMu: Double = DEFAULT_BALL_BALL_FRICTION_MU,
    ): Double {
        val scale = ballBallFrictionMu / DEFAULT_BALL_BALL_FRICTION_MU
        return scale * (
            BALL_BALL_FRICTION_A +
                BALL_BALL_FRICTION_B * exp(-BALL_BALL_FRICTION_C * relativeSlidingSpeedMps.coerceAtLeast(0.0))
            )
    }

    /**
     * Surface speed `R * omegaZ`, in m/s, of the cue ball's side spin when it reaches the object ball, signed as
     * documented on [ThrowAngles] (right english is negative).
     *
     * A level cue striking [TipOffset.clampedSideRatio] `* R` to the side of centre gives `R * omegaZ = 5/2 *
     * (a / R) * v0` ([STRIKE_SPIN_PER_TIP_RATIO]) for a stroke of speed [cueSpeedMps]. Spinning friction then wears
     * it down linearly at `5/2 * muSp * g` ([BallMotion.sideSpinAt]) for as long as the ball takes to cover
     * [cueToGhostDistanceMeters] under the sliding-then-rolling law of [computePreImpactCueState]. With no distance
     * (the default) this is the strike value. [table] supplies the cloth; a default 9 ft table's when it is `null`.
     *
     * This is the one place that says what side spin is left at contact.
     */
    fun sideSpinSurfaceSpeedAtImpactMps(
        tip: TipOffset,
        cueSpeedMps: Double,
        cueToGhostDistanceMeters: Double = 0.0,
        table: TableSpec? = null,
    ): Double {
        val atStrike = -STRIKE_SPIN_PER_TIP_RATIO * tip.clampedSideRatio * cueSpeedMps
        if (atStrike == 0.0 || cueToGhostDistanceMeters <= 0.0) return atStrike
        val cloth = (table ?: TableSpec.nineFootPool()).cloth
        val travelTime = preImpactTravelTimeSeconds(cueSpeedMps, tip, cueToGhostDistanceMeters, table ?: TableSpec.nineFootPool())
        return BallMotion(cloth).sideSpinAt(BallState(Vec2.ZERO, sideSpinSurfaceSpeedMps = atStrike), travelTime)
    }

    /**
     * Time the cue ball takes to cover [distanceMeters] (or to stop, if it stops short) after a strike at
     * [cueSpeedMps] with [tip], under the same sliding-then-rolling law as [computePreImpactCueState]: the speed
     * changes linearly in time at `muS * g` while it slides and at `muR * g` while it rolls.
     */
    internal fun preImpactTravelTimeSeconds(
        cueSpeedMps: Double,
        tip: TipOffset,
        distanceMeters: Double,
        table: TableSpec,
    ): Double {
        val v0 = cueSpeedMps
        if (v0 <= 1e-6 || distanceMeters <= 0.0) return 0.0
        val g = MotionPhysics.GRAVITY_M_S2
        val muS = table.cloth.slidingFrictionMuS.coerceAtLeast(1e-4)
        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val k0 = initialSpinFactorForTip(tip)
        val vRoll = MotionPhysics.slidingEndSpeedMps(v0, k0)
        val dSlide = MotionPhysics.slidingDistanceBetweenSpeedsMeters(v0, vRoll, muS, g)
        if (distanceMeters < dSlide) {
            val v = MotionPhysics.slidingSpeedAfterDistanceMps(v0, k0, distanceMeters, muS, g)
            return abs(v0 - v) / (muS * g)
        }
        val slideTime = abs(v0 - vRoll) / (muS * g)
        val v = MotionPhysics.rollingSpeedAfterDistanceMps(vRoll, distanceMeters - dSlide, muR, g)
        return slideTime + (vRoll - v) / (muR * g)
    }

    /**
     * Throw of the object ball at ball-ball contact (Alciatore, technical proof A.14).
     *
     * The cue ball arrives with speed [impactSpeedMps], cut angle [cutAngleSignedRadians], follow/draw spin
     * `R * omegaX = impactSpinFactorK * v` (positive follow, negative draw) and side spin
     * [sideSpinSurfaceSpeedMps] `= R * omegaZ`. At the contact point the surfaces slide against each other at
     *
     * - `v_slide = v * sin(phi) - R * omegaZ` along the contact tangent, and
     * - `R * omegaX * cos(phi)` perpendicular to the table,
     *
     * so `v_rel = sqrt(v_slide^2 + (R * omegaX * cos(phi))^2)`. Friction `mu(v_rel)`
     * ([ballBallFrictionAtSlidingSpeed]) acts against the sliding direction with the normal impulse that carries
     * `v * cos(phi)` into the object ball, so the object ball gains a tangential speed
     * `min(mu * v * cos(phi) / v_rel, 1/7) * v_slide` along the contact tangent. The `1/7` limit is the no-slip
     * (gearing) bound. The throw is the angle of that tangential speed against the normal speed `v * cos(phi)`.
     *
     * What follows from this, rather than being special-cased: the throw is larger for slow shots (friction is
     * higher at low sliding speed); it peaks for stun near half-ball and falls toward both straight-in and thin
     * cuts; follow or draw lowers it because the extra vertical sliding takes friction away from the lateral
     * direction; english that makes the contact surfaces slide less (left english for a cut with positive sign)
     * cancels it and, past that, reverses it; the opposite english adds to it where the no-slip limit holds the
     * plain throw down (slow shots, thinner cuts), while a half-ball stun already throws close to the most the
     * friction curve allows; a straight-in shot with side spin drags the object ball
     * the way the cue ball's contact surface moves, which is away from the side of the english (left english
     * throws it to the right).
     *
     * The result carries the sign convention of [ThrowAngles]. [ThrowAngles.cutOnlyRadians] is the same
     * calculation with `R * omegaZ = 0`.
     *
     * [ballBallFrictionMu] is the table's ball-ball friction at 1 m/s of sliding speed (default about 0.046).
     * Returns [ThrowAngles.NONE] when there is no impact speed or the contact is grazing or behind (`cos(phi) <= 0`).
     */
    fun computeThrowAnglesRadians(
        cutAngleSignedRadians: Double,
        impactSpeedMps: Double,
        impactSpinFactorK: Double = 0.0,
        sideSpinSurfaceSpeedMps: Double = 0.0,
        ballBallFrictionMu: Double = DEFAULT_BALL_BALL_FRICTION_MU,
    ): ThrowAngles {
        val cosPhi = cos(cutAngleSignedRadians)
        if (impactSpeedMps <= 1e-9 || cosPhi <= 1e-9) return ThrowAngles.NONE
        val totalThrow = throwFor(
            cutAngleSignedRadians, impactSpeedMps, impactSpinFactorK, sideSpinSurfaceSpeedMps, ballBallFrictionMu,
        )
        val cutOnlyThrow = if (sideSpinSurfaceSpeedMps == 0.0) {
            totalThrow
        } else {
            throwFor(cutAngleSignedRadians, impactSpeedMps, impactSpinFactorK, 0.0, ballBallFrictionMu)
        }
        return ThrowAngles(cutOnlyRadians = cutOnlyThrow, totalRadians = totalThrow)
    }

    private fun throwFor(
        cutAngleSignedRadians: Double,
        speed: Double,
        spinFactorK: Double,
        sideSpinSurfaceSpeed: Double,
        ballBallFrictionMu: Double,
    ): Double {
        val sinPhi = sin(cutAngleSignedRadians)
        val cosPhi = cos(cutAngleSignedRadians)
        val lateralSlide = speed * sinPhi - sideSpinSurfaceSpeed
        val verticalSlide = spinFactorK * speed * cosPhi
        val relativeSpeed = hypot(lateralSlide, verticalSlide)
        if (relativeSpeed <= 1e-12) return 0.0
        val normalSpeed = speed * cosPhi
        val mu = ballBallFrictionAtSlidingSpeed(relativeSpeed, ballBallFrictionMu)
        val frictionShare = min(mu * normalSpeed / relativeSpeed, NO_SLIP_IMPULSE_RATIO)
        return atan2(frictionShare * lateralSlide, normalSpeed)
    }

    /**
     * Computes geometric ghost-ball aim plus squirt, swerve, and throw compensation.
     */
    fun solveCompensatedAim(
        cueBallPos: Vec2,
        objectBallPos: Vec2,
        pocketPos: Vec2,
        cueBallRadiusMeters: Double,
        objectBallRadiusMeters: Double,
        cueSpeedMps: Double,
        tip: TipOffset,
        shaftType: ShaftType,
        table: TableSpec? = null,
    ): SpinCompensatedAim? {
        val cueBall = Ball(number = 0, position = cueBallPos, radiusMeters = cueBallRadiusMeters)
        val objectBall = Ball(number = 1, position = objectBallPos, radiusMeters = objectBallRadiusMeters)
        // [pocketPos] is the point the object ball is sent at (for a pocket, its aim point).
        val geom = GhostBallAiming.computeAimGeometryToPoint(
            cueBall = cueBall,
            objectBall = objectBall,
            targetPoint = pocketPos,
            otherBalls = emptyList(),
            table = table,
        )
        if (!geom.isCutPossible) return null
        return solveCompensatedAim(
            geometricAim = geom,
            cueBallRadiusMeters = cueBallRadiusMeters,
            objectBallRadiusMeters = objectBallRadiusMeters,
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            shaftType = shaftType,
            table = table,
        )
    }

    /**
     * Overload solving squirt, swerve, and throw compensation directly from a precomputed [geometricAim].
     *
     * **Throw compensation.** The object ball must leave along the geometric pot line
     * `n_g = geometricAim.objectBallDirection`. A real contact throws it off the line of centres `n` by the throw
     * `theta` ([computeThrowAnglesRadians], sign convention on [ThrowAngles]), so the line of centres has to be
     * the one that satisfies `n.rotate(-theta(n)) = n_g`, that is `n = n_g.rotate(+theta)`: for a cut the ghost
     * ball moves to a thinner hit, away from the aim line. `theta` itself depends on `n` through the cut angle
     * (the cue ball's path to the ghost ball changes with it), through the distance the cue ball travels and through
     * the speed and spin it has left, so the equation is solved by fixed-point iteration, re-evaluating the
     * pre-impact state ([computePreImpactCueState]) and the cut angle at each step. The map contracts quickly
     * (the throw changes by about a tenth of the change in `n`), and a handful of steps converge to rounding.
     *
     * The throw uses the cue ball's speed and follow/draw spin at impact, not at the strike, and the side spin of
     * [sideSpinSurfaceSpeedAtImpactMps]. [table] supplies the cloth for the pre-impact state and the ball-ball friction
     * ([TableSpec.ballContact]); a default 9 ft table's are used when it is `null`.
     *
     * **Why the planners keep the geometric direction.** The planners and the uncertainty model
     * ([com.runout.engine.planner.ExecutionErrorModel]) launch the object ball along [geometricAim]'s
     * `objectBallDirection`. That is correct when the shooter aims at [SpinCompensatedAim.compensatedGhostBallCenter]:
     * the throw then turns the compensated line of centres back onto the pot line. A shooter who aims at the
     * geometric ghost ball instead sends the object ball off the pot line by the throw.
     */
    fun solveCompensatedAim(
        geometricAim: AimGeometry,
        cueBallRadiusMeters: Double,
        objectBallRadiusMeters: Double,
        cueSpeedMps: Double,
        tip: TipOffset,
        shaftType: ShaftType,
        table: TableSpec? = null,
    ): SpinCompensatedAim? {
        if (!geometricAim.isCutPossible) return null

        val squirt = computeSquirtAngleRadians(tip, shaftType)
        val swerve = computeSwerveAngleRadians(tip, cueSpeedMps, geometricAim.cueToGhostDistanceMeters)

        val clothTable = table ?: TableSpec.nineFootPool()
        val contactDistance = cueBallRadiusMeters + objectBallRadiusMeters
        val potLine = geometricAim.objectBallDirection

        var lineOfCentres = potLine
        var throwAngles = ThrowAngles.NONE
        for (iteration in 0 until COMPENSATION_MAX_ITERATIONS) {
            val ghost = geometricAim.objectBallPos - lineOfCentres * contactDistance
            val toGhost = ghost - geometricAim.cueBallPos
            val distance = toGhost.length()
            if (distance <= 1e-6) break
            val signedCut = (toGhost / distance).angleTo(lineOfCentres)
            val pre = computePreImpactCueState(cueSpeedMps, tip, distance, clothTable)
            val sideSpin = sideSpinSurfaceSpeedAtImpactMps(tip, cueSpeedMps, distance, clothTable)
            val next = computeThrowAnglesRadians(
                cutAngleSignedRadians = signedCut,
                impactSpeedMps = pre.impactSpeedMps,
                impactSpinFactorK = pre.impactSpinFactorK,
                sideSpinSurfaceSpeedMps = sideSpin,
                ballBallFrictionMu = clothTable.ballContact.frictionMu,
            )
            val change = abs(next.totalRadians - throwAngles.totalRadians)
            throwAngles = next
            lineOfCentres = potLine.rotate(next.totalRadians)
            if (change < COMPENSATION_TOLERANCE_RAD) break
        }
        val cutThrow = throwAngles.cutOnlyRadians
        val spinThrow = throwAngles.spinRadians
        val totalThrow = throwAngles.totalRadians

        val rawCompensatedGhost = geometricAim.objectBallPos - lineOfCentres * contactDistance
        val compensatedGhost = if (
            table != null && !table.isWithinPlayableBounds(rawCompensatedGhost, cueBallRadiusMeters)
        ) {
            geometricAim.ghostBallCenter
        } else {
            rawCompensatedGhost
        }
        val netCueAimAdjustment = -(squirt + swerve)

        return SpinCompensatedAim(
            geometricAim = geometricAim,
            squirtAngleRadians = squirt,
            swerveAngleRadians = swerve,
            cutThrowAngleRadians = cutThrow,
            spinThrowAngleRadians = spinThrow,
            totalThrowAngleRadians = totalThrow,
            netCueAimAdjustmentRadians = netCueAimAdjustment,
            compensatedGhostBallCenter = compensatedGhost,
        )
    }

    /**
     * Vertical roll/spin regime of the cue ball at the moment it reaches the object ball after
     * sliding/rolling across [AimGeometry.cueToGhostDistanceMeters] of cloth.
     */
    enum class ImpactSpinState {
        BACKSPIN_DRAW,
        STUN_SLIDE,
        PARTIAL_FORWARD_ROLL,
        NATURAL_FORWARD_ROLL,
        STOPPED_SHORT,
    }

    /**
     * Cue-ball translational speed and spin factor `k = (R * omega) / v` remaining upon contact
     * with the object ball after cloth friction acts over `cueToGhostDistanceMeters`.
     */
    data class PreImpactCueState(
        val impactSpeedMps: Double,
        val impactSpinFactorK: Double,
        val initialSpinFactorK: Double,
        val state: ImpactSpinState,
        val travelledDistanceMeters: Double = 0.0,
    )

    /** Spin factor `k` below which a backspin cue ball counts as drawing. */
    private const val DRAW_STUN_THRESHOLD_K: Double = -0.08

    /** Spin factor `k` above which a sliding cue ball counts as picking up forward roll. */
    private const val STUN_ROLL_THRESHOLD_K: Double = 0.18

    /** Spin factor `k` at and above which the cue ball counts as being in natural forward roll. */
    private const val ROLL_SETTLED_THRESHOLD_K: Double = 0.92

    /** Speed below which the squared-speed bookkeeping treats the cue ball as stopped, in (m/s)^2. */
    private const val STOPPED_SPEED_SQUARED_EPSILON: Double = 1e-5

    /**
     * Spin factor of a firm draw at the object ball, the state [CueBallSpinPreset.DRAW] asks for in
     * [tipForImpactState].
     */
    const val DRAW_PRESET_IMPACT_K: Double = -0.5

    /** Spin-factor lever of a tip strike: `k0 = (5/2) * (b / R)` for a level cue hitting `b` above centre. */
    private const val STRIKE_SPIN_PER_TIP_RATIO: Double = 2.5

    /**
     * Maps normalized vertical tip offset to the strike spin factor `k0 = R * omega0 / v0`.
     *
     * A level cue striking the ball [TipOffset.verticalRatio] `* R` above its centre gives the ball
     * `v0 = F * dt / m` and `omega0 = (5/2) * (b / R) * v0 / R`, so `k0 = (5/2) * (b / R)`: natural roll
     * (`k0 = 1`) at `b = 0.4 R`, and `k0 = +-1.25` at the miscue limit [TipOffset.MAX_SAFE_OFFSET_RATIO].
     * The ideal law is used for every offset.
     */
    fun initialSpinFactorForTip(tip: TipOffset): Double =
        STRIKE_SPIN_PER_TIP_RATIO * tip.clampedVerticalRatio

    private fun impactStateForSpinFactor(k: Double): ImpactSpinState = when {
        k < DRAW_STUN_THRESHOLD_K -> ImpactSpinState.BACKSPIN_DRAW
        k <= STUN_ROLL_THRESHOLD_K -> ImpactSpinState.STUN_SLIDE
        k < ROLL_SETTLED_THRESHOLD_K -> ImpactSpinState.PARTIAL_FORWARD_ROLL
        else -> ImpactSpinState.NATURAL_FORWARD_ROLL
    }

    /**
     * Evolves the strike speed [cueSpeedMps] and the spin of [tip] over [cueToGhostDistanceMeters] of cloth
     * (`muS`, `muR` of [table]) and returns the cue ball's state when it reaches the object ball.
     *
     * One law applies to every tip, with the full sliding friction `muS`:
     *
     * 1. The strike gives `k0 = 5/2 * b/R` ([initialSpinFactorForTip]).
     * 2. While the ball slips (`k != 1`) friction `muS * g` acts against the slip. For `k0 < 1`
     *    `v^2 = v0^2 - 2 * muS * g * d` and `R * omega = k0 * v0 + 5/2 * (v0 - v)`; for overspin (`k0 > 1`) the
     *    signs flip and the ball speeds up.
     * 3. Slipping stops at `v_roll = v0 * (5 + 2 * k0) / 7` after `|v0^2 - v_roll^2| / (2 * muS * g)`
     *    ([MotionPhysics.slidingEndSpeedMps]); a centre-ball strike is the `k0 = 0` case, `5/7 v0`.
     * 4. After that the ball rolls and loses speed to rolling resistance only
     *    ([MotionPhysics.rollingSpeedAfterDistanceMps]).
     *
     * The speed along the aim line stays positive while `k0 > -2.5`, which the tip-offset limit
     * (`k0 >= -1.25`) guarantees, so a draw shot never reverses before contact; it only loses its backspin.
     * The result is continuous in the tip height and in the distance. Speed never exceeds [cueSpeedMps] unless
     * the ball has overspin (`k0 > 1`).
     */
    fun computePreImpactCueState(
        cueSpeedMps: Double,
        tip: TipOffset,
        cueToGhostDistanceMeters: Double,
        table: TableSpec,
    ): PreImpactCueState {
        val k0 = initialSpinFactorForTip(tip)
        if (cueSpeedMps <= 1e-6) {
            return PreImpactCueState(0.0, 0.0, k0, ImpactSpinState.STOPPED_SHORT, 0.0)
        }
        val dist = cueToGhostDistanceMeters.coerceAtLeast(0.0)
        if (dist <= 1e-6) {
            return PreImpactCueState(cueSpeedMps, k0, k0, impactStateForSpinFactor(k0), 0.0)
        }

        val g = MotionPhysics.GRAVITY_M_S2
        val muS = table.cloth.slidingFrictionMuS.coerceAtLeast(1e-4)
        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val v0 = cueSpeedMps
        val vRoll = MotionPhysics.slidingEndSpeedMps(v0, k0)
        val dSlide = MotionPhysics.slidingDistanceBetweenSpeedsMeters(v0, vRoll, muS, g)

        if (dist < dSlide) {
            val vImpact = MotionPhysics.slidingSpeedAfterDistanceMps(v0, k0, dist, muS, g)
            // R*omega = k0*v0 + 5/2*(v0 - v) holds on both sides of k = 1 (overspin: v > v0).
            val kImpact = (k0 * v0 + STRIKE_SPIN_PER_TIP_RATIO * (v0 - vImpact)) / vImpact
            return PreImpactCueState(vImpact, kImpact, k0, impactStateForSpinFactor(kImpact), dist)
        }

        val vImpact = MotionPhysics.rollingSpeedAfterDistanceMps(vRoll, dist - dSlide, muR, g)
        return if (vImpact * vImpact <= STOPPED_SPEED_SQUARED_EPSILON) {
            val stopDist = (dSlide + MotionPhysics.rollingStoppingDistanceMeters(vRoll, muR, g)).coerceIn(0.0, dist)
            PreImpactCueState(0.0, 0.0, k0, ImpactSpinState.STOPPED_SHORT, stopDist)
        } else {
            PreImpactCueState(vImpact, 1.0, k0, ImpactSpinState.NATURAL_FORWARD_ROLL, dist)
        }
    }

    /**
     * Vertical tip offset `b / R` (negative = below centre, no english) that gives the cue ball the spin factor
     * [targetImpactK] when it reaches the object ball after [cueToGhostDistanceMeters], struck at
     * [cueSpeedMps] on [table]; `null` when no tip within the miscue limit does.
     *
     * Inverts [computePreImpactCueState] for a ball that is still slipping at contact (`targetImpactK < 1`):
     * `k0 = (k * v - 5/2 * (v0 - v)) / v0` with `v^2 = v0^2 - 2 * muS * g * d`, then `b / R = k0 / (5/2)`.
     * `null` also when `v0^2 <= 2 * muS * g * d`, because then slipping has stopped before contact for every tip.
     * A [targetImpactK] of `1` or more asks for natural roll: the tip closest to centre, but never below it,
     * whose slide has ended by contact (`0.0` when the centre ball already rolls).
     * That is a pure kinematic answer: a stroke too soft to cover the distance still stops short, which
     * [computePreImpactCueState] reports.
     *
     * [sideRatio] is the english the caller will add; it narrows the vertical range allowed by the miscue limit.
     */
    fun verticalTipRatioForImpactSpin(
        targetImpactK: Double,
        cueSpeedMps: Double,
        cueToGhostDistanceMeters: Double,
        table: TableSpec,
        sideRatio: Double = 0.0,
    ): Double? {
        if (cueSpeedMps <= 1e-6) return null
        val dist = cueToGhostDistanceMeters.coerceAtLeast(0.0)
        val g = MotionPhysics.GRAVITY_M_S2
        val muS = table.cloth.slidingFrictionMuS.coerceAtLeast(1e-4)
        val v0 = cueSpeedMps
        val maxVertical = sqrt(
            (TipOffset.MAX_SAFE_OFFSET_RATIO * TipOffset.MAX_SAFE_OFFSET_RATIO - sideRatio * sideRatio)
                .coerceAtLeast(0.0),
        )
        val speedSqAtContact = v0 * v0 - 2.0 * muS * g * dist

        val k0 = if (targetImpactK >= 1.0) {
            // Slide ends exactly at contact: v_roll = v0 * (5 + 2 k0) / 7 = sqrt(v0^2 - 2 muS g d).
            if (speedSqAtContact <= 0.0) return 0.0
            ((7.0 * sqrt(speedSqAtContact) / v0 - 5.0) / 2.0).coerceAtLeast(0.0)
        } else {
            if (speedSqAtContact <= 0.0) return null
            val v = sqrt(speedSqAtContact)
            (targetImpactK * v - STRIKE_SPIN_PER_TIP_RATIO * (v0 - v)) / v0
        }
        val vertical = k0 / STRIKE_SPIN_PER_TIP_RATIO
        return if (abs(vertical) <= maxVertical + 1e-12) vertical else null
    }

    /**
     * The tip that gives the cue ball the spin state [preset] names at the object ball, for a strike at
     * [cueSpeedMps] over [cueToGhostDistanceMeters] of [table] cloth; `null` when no tip within the miscue limit
     * does (for example a stun or draw shot that is too long or too soft: the backspin wears off first).
     *
     * - [CueBallSpinPreset.STUN]: no spin at contact (`k = 0`); at any distance beyond the centre-ball slide
     *   this needs a tip below centre.
     * - [CueBallSpinPreset.FOLLOW]: natural roll at contact, see [verticalTipRatioForImpactSpin].
     * - [CueBallSpinPreset.DRAW]: a firm draw ([DRAW_PRESET_IMPACT_K]) or, if the firm draw is out of reach, the
     *   strongest tip as long as it still arrives with backspin.
     */
    fun tipForImpactState(
        preset: CueBallSpinPreset,
        cueSpeedMps: Double,
        cueToGhostDistanceMeters: Double,
        table: TableSpec,
        sideRatio: Double = 0.0,
    ): TipOffset? {
        val targetK = when (preset) {
            CueBallSpinPreset.STUN -> 0.0
            CueBallSpinPreset.FOLLOW -> 1.0
            CueBallSpinPreset.DRAW -> DRAW_PRESET_IMPACT_K
        }
        val vertical = verticalTipRatioForImpactSpin(targetK, cueSpeedMps, cueToGhostDistanceMeters, table, sideRatio)
        if (vertical != null) return TipOffset(sideRatio = sideRatio, verticalRatio = vertical)
        if (preset != CueBallSpinPreset.DRAW) return null

        val strongest = TipOffset(
            sideRatio = sideRatio,
            verticalRatio = -sqrt(
                (TipOffset.MAX_SAFE_OFFSET_RATIO * TipOffset.MAX_SAFE_OFFSET_RATIO - sideRatio * sideRatio)
                    .coerceAtLeast(0.0),
            ),
        )
        val state = computePreImpactCueState(cueSpeedMps, strongest, cueToGhostDistanceMeters, table).state
        return if (state == ImpactSpinState.BACKSPIN_DRAW) strongest else null
    }

    /**
     * Named change of vertical-spin regime on the way from the cue tip to the object ball.
     */
    enum class SpinRegimeChange {
        /** Backspin has worn off; the ball is sliding with no spin. */
        DRAW_TO_STUN,

        /** The slide has started to turn into forward roll. */
        STUN_TO_ROLL,

        /** Forward roll is established (reported as natural roll by [computePreImpactCueState]). */
        ROLL_SETTLES,

        /** Slipping has stopped; the ball is in pure rolling (`k = 1`). For overspin this is where the grip ends. */
        SLIDE_ENDS,
    }

    /**
     * Point on the aim line where [computePreImpactCueState] switches regime.
     *
     * [before] and [after] are the [ImpactSpinState] values that function reports on either side of
     * [distanceMeters]; [speedMps] and [spinFactorK] are the cue-ball speed and spin factor at that point.
     */
    data class SpinRegimeBoundary(
        val change: SpinRegimeChange,
        val distanceMeters: Double,
        val speedMps: Double,
        val spinFactorK: Double,
        val before: ImpactSpinState,
        val after: ImpactSpinState,
    )

    /**
     * Distances along the aim line, from the tip strike, at which the cue ball changes spin regime
     * (draw to stun, stun to roll, roll settled, slide ends), derived from the same thresholds and
     * closed-form sliding law as [computePreImpactCueState] so the two always agree. Ordered by distance.
     *
     * Boundaries are listed for the unobstructed line regardless of the cue-to-ghost distance; callers
     * compare them with [PreImpactCueState.travelledDistanceMeters] and the contact distance.
     */
    fun preImpactRegimeBoundaries(
        cueSpeedMps: Double,
        tip: TipOffset,
        table: TableSpec,
    ): List<SpinRegimeBoundary> {
        val v0 = cueSpeedMps
        if (v0 <= 1e-6) return emptyList()
        val k0 = initialSpinFactorForTip(tip)
        // A strike at natural roll never slips, so there is nothing to report.
        if (abs(k0 - 1.0) <= 1e-4) return emptyList()
        val g = MotionPhysics.GRAVITY_M_S2
        val muS = table.cloth.slidingFrictionMuS.coerceAtLeast(1e-4)
        val out = ArrayList<SpinRegimeBoundary>(4)

        val vRoll = MotionPhysics.slidingEndSpeedMps(v0, k0)
        fun slideDistance(v: Double): Double = MotionPhysics.slidingDistanceBetweenSpeedsMeters(v0, v, muS, g)
        val slideEnds = SpinRegimeBoundary(
            SpinRegimeChange.SLIDE_ENDS, slideDistance(vRoll), vRoll, 1.0,
            ImpactSpinState.NATURAL_FORWARD_ROLL, ImpactSpinState.NATURAL_FORWARD_ROLL,
        )
        if (k0 > 1.0) {
            // Overspin: the grip ends once friction has sped the ball up to natural roll.
            out += slideEnds
        } else {
            // Rolling-without-slip is reached when R*omega = v, i.e. k = 1; for a threshold kt the speed
            // follows from R*omega = k0*v0 + 2.5*(v0 - v) = kt*v.
            fun speedForK(kt: Double): Double =
                v0 * (k0 + STRIKE_SPIN_PER_TIP_RATIO) / (kt + STRIKE_SPIN_PER_TIP_RATIO)
            fun add(change: SpinRegimeChange, kt: Double, before: ImpactSpinState, after: ImpactSpinState) {
                val v = speedForK(kt)
                out += SpinRegimeBoundary(change, slideDistance(v), v, kt, before, after)
            }
            if (k0 < DRAW_STUN_THRESHOLD_K) {
                add(
                    SpinRegimeChange.DRAW_TO_STUN, DRAW_STUN_THRESHOLD_K,
                    ImpactSpinState.BACKSPIN_DRAW, ImpactSpinState.STUN_SLIDE,
                )
            }
            if (k0 <= STUN_ROLL_THRESHOLD_K) {
                add(
                    SpinRegimeChange.STUN_TO_ROLL, STUN_ROLL_THRESHOLD_K,
                    ImpactSpinState.STUN_SLIDE, ImpactSpinState.PARTIAL_FORWARD_ROLL,
                )
            }
            if (k0 < ROLL_SETTLED_THRESHOLD_K) {
                add(
                    SpinRegimeChange.ROLL_SETTLES, ROLL_SETTLED_THRESHOLD_K,
                    ImpactSpinState.PARTIAL_FORWARD_ROLL, ImpactSpinState.NATURAL_FORWARD_ROLL,
                )
            }
            out += slideEnds
        }
        return out.sortedBy { it.distanceMeters }
    }

    /**
     * Plain-English coaching summary of how the cue ball's vertical spin evolves across
     * [cueToGhostDistanceMeters] before hitting the object ball.
     */
    fun describeImpactTransition(
        tip: TipOffset,
        cueSpeedMps: Double,
        cueToGhostDistanceMeters: Double,
        table: TableSpec,
    ): String {
        val pre = computePreImpactCueState(cueSpeedMps, tip, cueToGhostDistanceMeters, table)
        val distTenths = ((cueToGhostDistanceMeters * 10.0).toInt() / 10.0)
        return when (pre.state) {
            ImpactSpinState.STOPPED_SHORT -> {
                val stopTenths = ((pre.travelledDistanceMeters * 10.0).toInt() / 10.0)
                "White ball stops short at ${stopTenths}m (needs ${distTenths}m to contact)"
            }
            ImpactSpinState.BACKSPIN_DRAW ->
                "Holds backspin across ${distTenths}m → draws back off contact"
            ImpactSpinState.STUN_SLIDE -> if (pre.initialSpinFactorK < -0.25) {
                "Backspin delays into a 90° stun at ${distTenths}m contact"
            } else {
                "Arrives sliding → crisp 90° tangent stun at contact"
            }
            ImpactSpinState.PARTIAL_FORWARD_ROLL -> if (pre.initialSpinFactorK < -0.15) {
                "Draw fades over ${distTenths}m → gentle stun-forward roll at contact"
            } else {
                "Picks up partial forward roll over ${distTenths}m"
            }
            ImpactSpinState.NATURAL_FORWARD_ROLL -> when {
                pre.initialSpinFactorK < -0.20 ->
                    "Draw wears off over ${distTenths}m → transitions to forward roll!"
                abs(pre.initialSpinFactorK) <= 0.20 ->
                    "Center strike transitions to natural forward roll over ${distTenths}m"
                else ->
                    "Arrives in smooth natural forward roll (~30° line)"
            }
        }
    }

    /**
     * Full Sandbox coaching telemetry combining pre-impact spin evolution AND collision power
     * transfer to [targetBallNumber] toward [pocket] (detecting when thin cuts or soft strokes
     * leave the object ball short of the pocket).
     */
    fun describeShotPowerAndSpin(
        tip: TipOffset,
        cueSpeedMps: Double,
        aim: AimGeometry,
        pocket: PocketSpec,
        table: TableSpec,
        targetBallNumber: Int? = null,
    ): String {
        val pre = computePreImpactCueState(cueSpeedMps, tip, aim.cueToGhostDistanceMeters, table)
        if (pre.state == ImpactSpinState.STOPPED_SHORT) {
            val stopTenths = ((pre.travelledDistanceMeters * 10.0).toInt() / 10.0)
            val needTenths = ((aim.cueToGhostDistanceMeters * 10.0).toInt() / 10.0)
            return "White ball stops short (${stopTenths}m of ${needTenths}m) — add speed"
        }
        val vOb0 = MotionPhysics.transferredObjectBallInitialSpeedMps(
            cueImpactSpeedMps = pre.impactSpeedMps,
            cutAngleRad = aim.cutAngleRadians,
            restitution = table.ballContact.restitution,
        )
        val obMaxDist = MotionPhysics.objectBallStoppingDistanceMeters(
            initialObjectSpeedMps = vOb0,
            muS = table.cloth.slidingFrictionMuS,
            muR = table.cloth.rollingResistanceMuR,
        )
        val dropDist = (
            PocketGeometry.distanceToCaptureLine(aim.objectBallPos, aim.objectBallDirection, pocket, table.ballRadiusMeters)
                ?: aim.objectToPocketDistanceMeters
            ).coerceAtLeast(0.02)
        if (obMaxDist < dropDist) {
            val reachPct = ((obMaxDist / dropDist) * 100.0).toInt().coerceIn(1, 99)
            val ballTag = targetBallNumber?.let { "Ball #$it" } ?: "Object ball"
            return "$ballTag stops short ($reachPct% to pocket) — transfer needs more speed"
        }
        return describeImpactTransition(tip, cueSpeedMps, aim.cueToGhostDistanceMeters, table)
    }

    /**
     * The two balls just after the cue ball meets the object ball, and the end of the cue ball's slide that follows.
     *
     * - [cue] and [objectBall] are the states at the moment of contact (cue ball at the ghost-ball centre, object
     *   ball where it stands), from [BallBallContact]: the object ball takes `(1 + e)/2 * v * cos(phi)` along the line
     *   of centres, the cue ball keeps `(1 - e)/2 * v * cos(phi)` along it and all of `v * sin(phi)` across it, the
     *   throw moves the object ball onto the aimed line, and both spins are unchanged.
     * - [slideEnd] is where and how the cue ball rolls once the slip is gone on open cloth
     *   (`(5 * v0 + 2 * w0) / 7`, after [slideDurationSeconds]); equal to [cue] when it leaves without slip.
     * - [impactSpinFactorK] is the vertical spin factor on arrival, relative to [impactSpeedMps]. The contact leaves
     *   the spin unchanged, so it also measures the spin the cue ball keeps.
     * - [initialDeflectionAngleRad] and [rollingDeflectionAngleRad] are signed angles from the aim line to the cue
     *   ball's velocity just after contact and once it rolls.
     */
    internal data class PostImpactCueState(
        val cue: BallState,
        val objectBall: BallState,
        val impactSpeedMps: Double,
        val impactSpinFactorK: Double,
        val initialDeflectionAngleRad: Double,
        val rollingDeflectionAngleRad: Double,
        val throwRadians: Double,
        val slideEnd: BallState,
        val slideDurationSeconds: Double,
    ) {
        /** Cue-ball speed just after contact. */
        val launchSpeedMps: Double get() = cue.velocity.length()

        /** Object-ball speed just after contact. */
        val objectBallSpeedMps: Double get() = objectBall.velocity.length()

        /** Cue-ball speed when the slide after contact ends. */
        val rollingSpeedAfterSlideMps: Double get() = slideEnd.velocity.length()
    }

    /**
     * Contact of a cue ball struck at [cueSpeedMps] with [tip] along [aim]: its speed and spin on arrival
     * ([computePreImpactCueState]), its side spin ([sideSpinSurfaceSpeedAtImpactMps]) and the contact law of
     * [postImpactStateFor].
     */
    internal fun computePostImpactCueState(
        aim: GhostBallAim,
        cueSpeedMps: Double,
        tip: TipOffset,
        table: TableSpec,
    ): PostImpactCueState {
        val preImpact = computePreImpactCueState(
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
            table = table,
        )
        return postImpactStateFor(
            aim = aim,
            impactSpeedMps = preImpact.impactSpeedMps,
            impactSpinFactorK = preImpact.impactSpinFactorK,
            sideSpinSurfaceSpeedMps = sideSpinSurfaceSpeedAtImpactMps(tip, cueSpeedMps, aim.cueToGhostDistanceMeters, table),
            table = table,
        )
    }

    /**
     * Contact of a cue ball arriving along [aim]'s aim line at [impactSpeedMps] with vertical spin factor
     * [impactSpinFactorK] and side spin [sideSpinSurfaceSpeedMps]. The line of centres is the one whose throw sends the
     * object ball along [AimGeometry.objectBallDirection] (the contact of a player who aims at the compensated ghost
     * ball, see [solveCompensatedAim]); the cue ball takes the equal and opposite impulse. The contact uses [table]'s
     * ball-ball friction and restitution ([TableSpec.ballContact]).
     */
    internal fun postImpactStateFor(
        aim: GhostBallAim,
        impactSpeedMps: Double,
        impactSpinFactorK: Double,
        sideSpinSurfaceSpeedMps: Double,
        table: TableSpec,
    ): PostImpactCueState {
        val ghost = aim.ghostBallCenter
        if (impactSpeedMps <= 1e-6) {
            val rest = BallState(ghost)
            return PostImpactCueState(
                cue = rest,
                objectBall = BallState(aim.objectBallPos),
                impactSpeedMps = 0.0,
                impactSpinFactorK = impactSpinFactorK,
                initialDeflectionAngleRad = 0.0,
                rollingDeflectionAngleRad = 0.0,
                throwRadians = 0.0,
                slideEnd = rest,
                slideDurationSeconds = 0.0,
            )
        }
        val direction = aim.aimDirection.normalized()
        val arriving = BallState(
            position = ghost,
            velocity = direction * impactSpeedMps,
            spinVelocity = direction * (impactSpinFactorK * impactSpeedMps),
            sideSpinSurfaceSpeedMps = sideSpinSurfaceSpeedMps,
        )
        val contact = table.ballContact
        val lineOfCentres = BallBallContact.lineOfCentresForThrownDirection(
            striking = arriving,
            thrownDirection = aim.objectBallDirection,
            ballBallFrictionMu = contact.frictionMu,
        )
        val outcome = BallBallContact.resolve(
            striking = arriving,
            struck = BallState(aim.objectBallPos),
            lineOfCentres = lineOfCentres,
            restitution = contact.restitution,
            ballBallFrictionMu = contact.frictionMu,
        )
        val cue = outcome.striking
        val motion = BallMotion(table.cloth)
        val slideTime = motion.timeToRoll(cue)
        val slideEnd = if (slideTime > 0.0) motion.endOfPhase(cue) else cue
        val initialDeflection = if (cue.velocity.length() > 1e-9) direction.angleTo(cue.velocity) else 0.0
        val rollingDeflection = if (slideEnd.velocity.length() > 1e-9) {
            direction.angleTo(slideEnd.velocity)
        } else {
            initialDeflection
        }
        return PostImpactCueState(
            cue = cue,
            objectBall = outcome.struck,
            impactSpeedMps = impactSpeedMps,
            impactSpinFactorK = impactSpinFactorK,
            initialDeflectionAngleRad = initialDeflection,
            rollingDeflectionAngleRad = rollingDeflection,
            throwRadians = outcome.throwRadians,
            slideEnd = slideEnd,
            slideDurationSeconds = slideTime,
        )
    }

    /**
     * Path of the cue ball after it meets the object ball along [aim], struck at [cueSpeedMps] with [tip]: the contact
     * of [computePostImpactCueState], then the slide (a curve when the ball carries follow or draw) and the roll of
     * [BallMotion], up to [maxBounces] cushions ([CushionContact]; the ball is stopped at the next one), and contacts
     * with [obstacleBalls], which move when struck. A cue ball that stops short of the object ball ends where it stops.
     */
    fun traceCueBallPathWithSpin(
        aim: GhostBallAim,
        cueSpeedMps: Double,
        tip: TipOffset,
        table: TableSpec,
        ballRadiusMeters: Double = TableSpec.POOL_BALL_RADIUS_METERS,
        maxBounces: Int = 3,
        obstacleBalls: List<Ball> = emptyList(),
    ): CueBallTrajectory {
        val preset = tip.toSpinPreset()
        val preImpact = computePreImpactCueState(
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
            table = table,
        )
        if (preImpact.state == ImpactSpinState.STOPPED_SHORT) {
            val cueStop = clampCenterToBounds(
                pos = aim.cueBallPos + aim.aimDirection * preImpact.travelledDistanceMeters,
                table = table,
                radius = ballRadiusMeters,
            )
            return CueBallTrajectory(
                spin = preset,
                points = listOf(aim.cueBallPos, cueStop),
                initialDeflectionAngleRad = 0.0,
                hitsScratchPocket = detectScratchOnSegment(aim.cueBallPos, cueStop, table.pockets, ballRadiusMeters)?.id,
            )
        }
        return tracePathFromContact(
            aim = aim,
            impactSpeedMps = preImpact.impactSpeedMps,
            impactSpinFactorK = preImpact.impactSpinFactorK,
            sideSpinSurfaceSpeedMps = sideSpinSurfaceSpeedAtImpactMps(tip, cueSpeedMps, aim.cueToGhostDistanceMeters, table),
            spin = preset,
            table = table,
            ballRadiusMeters = ballRadiusMeters,
            maxBounces = maxBounces,
            obstacleBalls = obstacleBalls,
        )
    }

    /** Cue-ball path from the contact on, for a ball arriving with the given speed and spin; see [traceCueBallPathWithSpin]. */
    internal fun tracePathFromContact(
        aim: GhostBallAim,
        impactSpeedMps: Double,
        impactSpinFactorK: Double,
        sideSpinSurfaceSpeedMps: Double,
        spin: CueBallSpinPreset,
        table: TableSpec,
        ballRadiusMeters: Double,
        maxBounces: Int = Int.MAX_VALUE,
        obstacleBalls: List<Ball> = emptyList(),
    ): CueBallTrajectory {
        val post = postImpactStateFor(aim, impactSpeedMps, impactSpinFactorK, sideSpinSurfaceSpeedMps, table)
        return TableCollisionSimulator.traceCueBall(
            table = table,
            cueState = post.cue,
            cueRadius = ballRadiusMeters,
            spin = spin,
            initialDeflectionAngleRad = post.initialDeflectionAngleRad,
            rollingDeflectionAngleRad = post.rollingDeflectionAngleRad,
            obstacleBalls = obstacleBalls,
            maxCushions = maxBounces,
        )
    }

    /** Pocket a ball rolling straight from [start] to [end] drops into, if any ([PocketGeometry.captureOnSegment]). */
    private fun detectScratchOnSegment(
        start: Vec2,
        end: Vec2,
        pockets: List<PocketSpec>,
        ballRadiusMeters: Double,
    ): PocketSpec? = PocketGeometry.captureOnSegment(start, end, pockets, ballRadiusMeters)?.takeIf { it.potted }?.pocket

    private fun clampCenterToBounds(pos: Vec2, table: TableSpec, radius: Double): Vec2 {
        val minX = radius.coerceAtMost(table.lengthMeters * 0.5)
        val maxX = (table.lengthMeters - radius).coerceAtLeast(minX)
        val minY = radius.coerceAtMost(table.widthMeters * 0.5)
        val maxY = (table.widthMeters - radius).coerceAtLeast(minY)
        return Vec2(
            x = pos.x.coerceIn(minX, maxX),
            y = pos.y.coerceIn(minY, maxY),
        )
    }
}
