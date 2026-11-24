package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
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
 * - [verticalRatio]: negative = draw, `0.0` = stun, positive = follow (normalized by `R`).
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

    fun toSpinPreset(): CueBallSpinPreset = when {
        clampedVerticalRatio > 0.12 -> CueBallSpinPreset.FOLLOW
        clampedVerticalRatio < -0.12 -> CueBallSpinPreset.DRAW
        else -> CueBallSpinPreset.STUN
    }

    /**
     * Multi-level human-readable description of vertical spin, side english, and mixed combinations
     * (e.g. `"Center Ball (Stun)"`, `"Drag Stun + Touch of Left"`, `"Controlled Follow + Half-Tip Right"`,
     * `"Max Power Draw"`).
     */
    fun describeSpinLabel(short: Boolean = false): String {
        val side = clampedSideRatio
        val vert = clampedVerticalRatio
        val absSide = abs(side)
        val absVert = abs(vert)
        if (hypot(side, vert) < 0.06) {
            return if (short) "Center Stun" else "Center Ball (Stun)"
        }

        val vertText = when {
            absVert < 0.06 -> null
            absVert < 0.16 -> if (vert > 0.0) {
                if (short) "Touch Follow" else "Touch of Follow"
            } else {
                if (short) "Drag Stun" else "Touch of Low (Drag Stun)"
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
                    absVert in 0.06..<0.16 && vert < 0.0 -> "Drag Stun"
                    else -> vertText
                }
                val compactSide = sideText.removeSuffix(" English")
                "$compactVert + $compactSide"
            }
            vertText != null -> vertText
            sideText != null -> if (short) "Stun + $sideText" else "Center + $sideText"
            else -> if (short) "Center Stun" else "Center Ball (Stun)"
        }
    }

    companion object {
        const val MAX_SAFE_OFFSET_RATIO: Double = 0.50

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
 * Spin-aware aiming compensation breakdown around a geometric ghost-ball solution.
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
 * Closed-form cue-strike spin physics (squirt, swerve, cut/spin-induced throw) and
 * Han (2005)-inspired cushion rebound trajectory tracer in SI units.
 */
object SpinPhysics {
    const val DEFAULT_BALL_BALL_FRICTION_MU: Double = 0.055
    private const val STRAIGHT_IN_ANGLE_EPSILON_RAD: Double = 1e-4

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
     * Computes `(cutThrowRadians, spinThrowRadians)` imparted to the object ball at ball-ball contact.
     */
    fun computeThrowAnglesRadians(
        cutAngleSignedRadians: Double,
        cueSpeedMps: Double,
        tip: TipOffset,
        ballBallFrictionMu: Double = DEFAULT_BALL_BALL_FRICTION_MU,
    ): Pair<Double, Double> {
        val safeSpeed = cueSpeedMps.coerceAtLeast(0.35)
        val speedFactor = (1.6 / (safeSpeed + 0.8)).coerceIn(0.35, 1.35)
        val sinPhi = sin(cutAngleSignedRadians)
        val cosPhi = cos(cutAngleSignedRadians).coerceAtLeast(0.15)
        val cutThrow = atan2(ballBallFrictionMu * sinPhi, cosPhi + ballBallFrictionMu * abs(sinPhi)) * speedFactor
        val spinThrow = -0.85 * ballBallFrictionMu * tip.clampedSideRatio * speedFactor
        return cutThrow to spinThrow
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
        val pocket = PocketSpec(
            id = PocketId.FOOT_RIGHT,
            center = pocketPos,
            mouthWidthMeters = cueBallRadiusMeters * 4.0,
            acceptanceRadiusMeters = cueBallRadiusMeters * 2.2,
        )
        val geom = GhostBallAiming.computeAimGeometry(
            cueBall = cueBall,
            objectBall = objectBall,
            pocket = pocket,
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

        val cross = geometricAim.aimDirection.x * geometricAim.objectBallDirection.y -
            geometricAim.aimDirection.y * geometricAim.objectBallDirection.x
        val absCut = abs(geometricAim.cutAngleRadians)
        val signedCut = if (cross >= 0.0) absCut else -absCut

        val squirt = computeSquirtAngleRadians(tip, shaftType)
        val swerve = computeSwerveAngleRadians(tip, cueSpeedMps, geometricAim.cueToGhostDistanceMeters)
        val (cutThrow, spinThrow) = computeThrowAnglesRadians(signedCut, cueSpeedMps, tip)
        val totalThrow = cutThrow + spinThrow

        val compImpactDir = geometricAim.objectBallDirection.rotate(-totalThrow).normalized()
        val rawCompensatedGhost = geometricAim.objectBallPos -
            compImpactDir * (cueBallRadiusMeters + objectBallRadiusMeters)
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
    )

    private const val PRE_IMPACT_SKID_FRICTION_FACTOR: Double = 0.50

    /**
     * Maps normalized vertical tip offset to initial strike spin ratio `k0 = (R * omega0) / v0`.
     */
    fun initialSpinFactorForTip(tip: TipOffset): Double {
        val vertical = tip.clampedVerticalRatio
        return if (vertical >= 0.0) {
            (vertical / 0.35).coerceIn(0.0, 1.30)
        } else {
            (vertical / 0.38 * 1.22).coerceIn(-1.38, 0.0)
        }
    }

    /**
     * Evolves initial cue-ball stroke speed [cueSpeedMps] and [tip] vertical spin over
     * [cueToGhostDistanceMeters] of cloth (`muS`, `muR`), capturing how low-speed draw wears off
     * into stun/sliding and then transitions into natural forward roll on longer shots.
     */
    fun computePreImpactCueState(
        cueSpeedMps: Double,
        tip: TipOffset,
        cueToGhostDistanceMeters: Double,
        table: TableSpec,
    ): PreImpactCueState {
        val k0 = initialSpinFactorForTip(tip)
        if (cueSpeedMps <= 1e-6) {
            return PreImpactCueState(0.0, 0.0, k0, ImpactSpinState.STOPPED_SHORT)
        }
        val dist = cueToGhostDistanceMeters.coerceAtLeast(0.0)
        if (dist <= 1e-6) {
            val startState = when {
                k0 < -0.08 -> ImpactSpinState.BACKSPIN_DRAW
                k0 <= 0.15 -> ImpactSpinState.STUN_SLIDE
                k0 < 0.92 -> ImpactSpinState.PARTIAL_FORWARD_ROLL
                else -> ImpactSpinState.NATURAL_FORWARD_ROLL
            }
            return PreImpactCueState(cueSpeedMps, k0, k0, startState)
        }

        val g = MotionPhysics.GRAVITY_M_S2
        val muPre = (table.cloth.slidingFrictionMuS * PRE_IMPACT_SKID_FRICTION_FACTOR).coerceAtLeast(0.05)
        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val v0 = cueSpeedMps
        val vRoll = (((5.0 + 2.0 * k0) / 7.0) * v0).coerceAtLeast(0.0)

        return when {
            abs(k0 - 1.0) <= 1e-4 -> {
                val vImpactSq = v0 * v0 - 2.0 * muR * g * dist
                if (vImpactSq <= 1e-5) {
                    PreImpactCueState(0.0, 0.0, k0, ImpactSpinState.STOPPED_SHORT)
                } else {
                    PreImpactCueState(sqrt(vImpactSq), 1.0, k0, ImpactSpinState.NATURAL_FORWARD_ROLL)
                }
            }

            abs(k0) <= 0.08 -> {
                // Intentional Stun strike: holds pure 90-degree stun within its stroke-speed stun envelope,
                // gradually picking up forward roll only when stroked too softly across long table distances.
                val stunEnvelopeMeters = 0.68 * v0 * v0
                val vImpactSq = v0 * v0 - 3.0 * muR * g * dist
                if (vImpactSq <= 1e-5) {
                    PreImpactCueState(0.0, 0.0, k0, ImpactSpinState.STOPPED_SHORT)
                } else {
                    val vImpact = sqrt(vImpactSq)
                    if (dist <= stunEnvelopeMeters) {
                        PreImpactCueState(vImpact, 0.0, k0, ImpactSpinState.STUN_SLIDE)
                    } else {
                        val rollProgress = ((dist - stunEnvelopeMeters) / (0.65 * v0 * v0).coerceAtLeast(0.15))
                            .coerceIn(0.0, 1.0)
                        val state = if (rollProgress >= 0.92) {
                            ImpactSpinState.NATURAL_FORWARD_ROLL
                        } else {
                            ImpactSpinState.PARTIAL_FORWARD_ROLL
                        }
                        PreImpactCueState(vImpact, rollProgress, k0, state)
                    }
                }
            }

            k0 < 1.0 -> {
                val dRoll = ((v0 * v0 - vRoll * vRoll) / (2.0 * muPre * g)).coerceAtLeast(0.0)
                if (dist < dRoll) {
                    val vImpactSq = (v0 * v0 - 2.0 * muPre * g * dist).coerceAtLeast(1e-8)
                    val vImpact = sqrt(vImpactSq)
                    val rwImpact = k0 * v0 + 2.5 * (v0 - vImpact)
                    val kImpact = (rwImpact / vImpact).coerceIn(-1.50, 1.0)
                    val state = when {
                        kImpact < -0.08 -> ImpactSpinState.BACKSPIN_DRAW
                        kImpact <= 0.18 -> ImpactSpinState.STUN_SLIDE
                        kImpact < 0.92 -> ImpactSpinState.PARTIAL_FORWARD_ROLL
                        else -> ImpactSpinState.NATURAL_FORWARD_ROLL
                    }
                    PreImpactCueState(vImpact, kImpact, k0, state)
                } else {
                    val remDist = dist - dRoll
                    val vImpactSq = vRoll * vRoll - 2.0 * muR * g * remDist
                    if (vImpactSq <= 1e-5) {
                        PreImpactCueState(0.0, 0.0, k0, ImpactSpinState.STOPPED_SHORT)
                    } else {
                        PreImpactCueState(sqrt(vImpactSq), 1.0, k0, ImpactSpinState.NATURAL_FORWARD_ROLL)
                    }
                }
            }

            else -> {
                // Overspin follow (k0 > 1.0): backward bottom slip accelerates CM forward until natural roll
                val dRoll = ((vRoll * vRoll - v0 * v0) / (2.0 * muPre * g)).coerceAtLeast(0.0)
                if (dist < dRoll) {
                    val vImpact = sqrt(v0 * v0 + 2.0 * muPre * g * dist)
                    val rwImpact = k0 * v0 - 2.5 * (vImpact - v0)
                    val kImpact = (rwImpact / vImpact).coerceIn(1.0, 1.35)
                    PreImpactCueState(vImpact, kImpact, k0, ImpactSpinState.NATURAL_FORWARD_ROLL)
                } else {
                    val remDist = dist - dRoll
                    val vImpactSq = vRoll * vRoll - 2.0 * muR * g * remDist
                    if (vImpactSq <= 1e-5) {
                        PreImpactCueState(0.0, 0.0, k0, ImpactSpinState.STOPPED_SHORT)
                    } else {
                        PreImpactCueState(sqrt(vImpactSq), 1.0, k0, ImpactSpinState.NATURAL_FORWARD_ROLL)
                    }
                }
            }
        }
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
            ImpactSpinState.STOPPED_SHORT ->
                "Too soft for ${distTenths}m — cue ball stops short before object ball"
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
     * Immediate post-impact cue-ball velocity (m/s) and initial deflection from the aim line (rad).
     */
    internal data class PostImpactCueState(
        val initialVelocity: Vec2,
        val extraSlideDistanceMeters: Double,
        val effectiveInitialSpeedMps: Double,
        val initialDeflectionAngleRad: Double,
    )

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
        val impactSpeed = preImpact.impactSpeedMps
        if (impactSpeed <= 1e-6) {
            return PostImpactCueState(Vec2.ZERO, 0.0, 0.0, 0.0)
        }
        val absPhi = abs(aim.cutAngleRadians)
        val sinPhi = sin(absPhi)
        val cosPhi = cos(absPhi)
        val tangentDir = if (aim.cutAngleRadians >= 0.0) {
            aim.objectBallDirection.rotate(-PI / 2.0)
        } else {
            aim.objectBallDirection.rotate(PI / 2.0)
        }
        val spinFactorK = preImpact.impactSpinFactorK

        val postSlideVelocity = if (absPhi <= STRAIGHT_IN_ANGLE_EPSILON_RAD) {
            if (abs(spinFactorK) <= 1e-4) {
                Vec2.ZERO
            } else {
                aim.objectBallDirection * ((2.0 / 7.0) * spinFactorK * impactSpeed)
            }
        } else {
            (
                aim.objectBallDirection * ((2.0 / 7.0) * spinFactorK * cosPhi) +
                    tangentDir * ((5.0 / 7.0 + (2.0 / 7.0) * spinFactorK) * sinPhi)
                ) * impactSpeed
        }

        val baseSpeed = postSlideVelocity.length()
        if (baseSpeed <= 1e-6) {
            return PostImpactCueState(Vec2.ZERO, 0.0, 0.0, 0.0)
        }

        val initialDir = postSlideVelocity / baseSpeed
        val initialDeflectionRad = aim.aimDirection.angleTo(initialDir)

        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val muS = table.cloth.slidingFrictionMuS.coerceAtLeast(1e-4)
        val g = MotionPhysics.GRAVITY_M_S2

        // Blend in the pure-stun post-impact slide distance when impact spin factor is near stun (0.0).
        val stunWeight = (1.0 - abs(spinFactorK)).coerceIn(0.0, 1.0)
        val extraSlideDist = if (absPhi > STRAIGHT_IN_ANGLE_EPSILON_RAD && stunWeight > 0.0) {
            val vSlide0 = impactSpeed * sinPhi
            val slideDist = (12.0 * vSlide0 * vSlide0) / (49.0 * muS * g)
            slideDist * stunWeight
        } else {
            0.0
        }

        val initialRollingDist = MotionPhysics.rollingStoppingDistanceMeters(baseSpeed, muR, g) + extraSlideDist
        val effectiveInitialSpeed = MotionPhysics.initialRollingSpeedForDistance(initialRollingDist, muR, g)

        return PostImpactCueState(
            initialVelocity = initialDir * effectiveInitialSpeed,
            extraSlideDistanceMeters = extraSlideDist,
            effectiveInitialSpeedMps = effectiveInitialSpeed,
            initialDeflectionAngleRad = initialDeflectionRad,
        )
    }

    /**
     * Traces post-impact cue-ball motion across cloth and up to [maxBounces] cushion rebounds using
     * continuous vertical spin (`TipOffset.clampedVerticalRatio`) and a Han (2005)-inspired
     * side-spin cushion rebound model, optionally deflecting off [obstacleBalls].
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
        val start = aim.ghostBallCenter
        val postState = computePostImpactCueState(aim, cueSpeedMps, tip, table)

        if (postState.effectiveInitialSpeedMps <= 1e-6) {
            return CueBallTrajectory(
                spin = preset,
                points = listOf(start),
                initialDeflectionAngleRad = 0.0,
                hitsScratchPocket = detectScratchOnSegment(start, start, table.pockets)?.id,
            )
        }

        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val g = MotionPhysics.GRAVITY_M_S2
        val points = mutableListOf(start)
        var currentPos = start
        var currentVel = postState.initialVelocity
        val safeMaxBounces = maxBounces.coerceAtLeast(0)
        val maxIterations = safeMaxBounces + 6
        var railBounces = 0
        var lastHitBallNumber: Int? = null

        for (iter in 0..maxIterations) {
            val speed = currentVel.length()
            if (speed <= 1e-6) break

            val dir = currentVel / speed
            val stoppingDist = MotionPhysics.rollingStoppingDistanceMeters(speed, muR, g)
            if (stoppingDist <= 1e-6) break

            val railHit = intersectRails(currentPos, dir, table, ballRadiusMeters)
            val ballHit = if (obstacleBalls.isNotEmpty()) {
                findFirstObstacleHit(
                    origin = currentPos,
                    dir = dir,
                    maxDistance = min(stoppingDist, railHit.distance),
                    cueRadius = ballRadiusMeters,
                    obstacles = obstacleBalls,
                    ignoreBallNumber = lastHitBallNumber,
                )
            } else {
                null
            }

            if (ballHit != null) {
                val segEnd = currentPos + dir * ballHit.distance
                val scratch = detectScratchOnSegment(currentPos, segEnd, table.pockets)
                if (scratch != null) {
                    points += scratch.center
                    return CueBallTrajectory(
                        spin = preset,
                        points = points,
                        initialDeflectionAngleRad = postState.initialDeflectionAngleRad,
                        hitsScratchPocket = scratch.id,
                    )
                }
                points += segEnd
                val remainingDist = (stoppingDist - ballHit.distance).coerceAtLeast(0.0)
                if (remainingDist <= 1e-6) break
                val preHitSpeed = MotionPhysics.initialRollingSpeedForDistance(remainingDist, muR, g)
                val preHitVel = dir * preHitSpeed
                val normal = (ballHit.ball.position - segEnd).let { delta ->
                    if (delta.lengthSquared() < 1e-12) dir else delta.normalized()
                }
                val vn = preHitVel.dot(normal).coerceAtLeast(0.0)
                currentVel = preHitVel - normal * (vn * 0.965)
                currentPos = segEnd
                lastHitBallNumber = ballHit.ball.number
                continue
            }

            lastHitBallNumber = null
            val travel = min(stoppingDist, railHit.distance)
            val segEnd = currentPos + dir * travel
            val extendedEnd = if (stoppingDist >= railHit.distance) {
                currentPos + dir * (railHit.distance + ballRadiusMeters)
            } else {
                segEnd
            }

            val scratch = detectScratchOnSegment(currentPos, extendedEnd, table.pockets)
            if (scratch != null) {
                points += scratch.center
                return CueBallTrajectory(
                    spin = preset,
                    points = points,
                    initialDeflectionAngleRad = postState.initialDeflectionAngleRad,
                    hitsScratchPocket = scratch.id,
                )
            }

            points += segEnd
            if (stoppingDist <= railHit.distance + 1e-6 || railBounces >= safeMaxBounces) {
                break
            }

            val remainingDist = (stoppingDist - railHit.distance).coerceAtLeast(0.0)
            val impactSpeed = MotionPhysics.initialRollingSpeedForDistance(remainingDist, muR, g)
            val impactVel = dir * impactSpeed
            val normal = inwardNormalForHit(railHit, table, segEnd)
            val vNormalMag = abs(impactVel.dot(normal))
            if (vNormalMag <= 1e-6) break

            // Table coordinates have +x Head->Foot and +y Left->Right, so (-normal).rotate(+PI/2) = Vec2(n.y, -n.x)
            // points to the player's right when facing the cushion; right english (+sideRatio) kicks right (+t).
            val tangent = Vec2(normal.y, -normal.x)
            val ecEffective = (table.cloth.cushionRestitutionEc - 0.035 * vNormalMag).coerceIn(0.55, 0.90)
            val vNormalOut = vNormalMag * ecEffective

            val sideSpinRatio = tip.clampedSideRatio * (0.72.pow(railBounces))
            val vTanIn = impactVel.dot(tangent)
            val vTanOut = vTanIn * 0.82 + sideSpinRatio * vNormalMag * 0.55

            currentVel = normal * vNormalOut + tangent * vTanOut
            currentPos = Vec2(
                x = segEnd.x.coerceIn(ballRadiusMeters, (table.lengthMeters - ballRadiusMeters).coerceAtLeast(ballRadiusMeters)),
                y = segEnd.y.coerceIn(ballRadiusMeters, (table.widthMeters - ballRadiusMeters).coerceAtLeast(ballRadiusMeters)),
            )
            railBounces++
        }

        return CueBallTrajectory(
            spin = preset,
            points = points,
            initialDeflectionAngleRad = postState.initialDeflectionAngleRad,
            hitsScratchPocket = null,
        )
    }

    private data class ObstacleHit(
        val ball: Ball,
        val distance: Double,
    )

    private fun findFirstObstacleHit(
        origin: Vec2,
        dir: Vec2,
        maxDistance: Double,
        cueRadius: Double,
        obstacles: List<Ball>,
        ignoreBallNumber: Int?,
    ): ObstacleHit? {
        if (maxDistance <= 1e-5) return null
        var best: ObstacleHit? = null
        var bestDist = maxDistance
        for (ball in obstacles) {
            if (ball.number == ignoreBallNumber) continue
            val sumR = cueRadius + ball.radiusMeters - 1e-4
            if (sumR <= 0.0) continue
            val toObs = ball.position - origin
            val proj = toObs.dot(dir)
            if (proj <= 0.0) continue
            val perpSq = toObs.lengthSquared() - proj * proj
            val sumRSq = sumR * sumR
            if (perpSq >= sumRSq) continue
            val backDist = sqrt((sumRSq - perpSq).coerceAtLeast(0.0))
            val hitDist = (proj - backDist).coerceAtLeast(0.0)
            if (hitDist in 1e-4..bestDist) {
                bestDist = hitDist
                best = ObstacleHit(ball, hitDist)
            }
        }
        return best
    }

    private data class RailHit(
        val distance: Double,
        val hitVerticalRail: Boolean,
        val hitHorizontalRail: Boolean,
    )

    private fun intersectRails(
        origin: Vec2,
        dir: Vec2,
        table: TableSpec,
        ballRadiusMeters: Double,
    ): RailHit {
        val minX = ballRadiusMeters
        val maxX = (table.lengthMeters - ballRadiusMeters).coerceAtLeast(ballRadiusMeters)
        val minY = ballRadiusMeters
        val maxY = (table.widthMeters - ballRadiusMeters).coerceAtLeast(ballRadiusMeters)

        val tx = when {
            dir.x > 1e-9 -> ((maxX - origin.x) / dir.x).coerceAtLeast(0.0)
            dir.x < -1e-9 -> ((minX - origin.x) / dir.x).coerceAtLeast(0.0)
            else -> Double.POSITIVE_INFINITY
        }
        val ty = when {
            dir.y > 1e-9 -> ((maxY - origin.y) / dir.y).coerceAtLeast(0.0)
            dir.y < -1e-9 -> ((minY - origin.y) / dir.y).coerceAtLeast(0.0)
            else -> Double.POSITIVE_INFINITY
        }

        val minDist = min(tx, ty)
        val hitVertical = abs(tx - minDist) <= 1e-6
        val hitHorizontal = abs(ty - minDist) <= 1e-6
        return RailHit(
            distance = minDist,
            hitVerticalRail = hitVertical,
            hitHorizontalRail = hitHorizontal,
        )
    }

    private fun inwardNormalForHit(
        hit: RailHit,
        table: TableSpec,
        hitPos: Vec2,
    ): Vec2 {
        val nx = if (hit.hitVerticalRail) {
            if (hitPos.x <= table.lengthMeters * 0.5) 1.0 else -1.0
        } else {
            0.0
        }
        val ny = if (hit.hitHorizontalRail) {
            if (hitPos.y <= table.widthMeters * 0.5) 1.0 else -1.0
        } else {
            0.0
        }
        val raw = Vec2(nx, ny)
        return if (raw.lengthSquared() < 1e-12) Vec2(1.0, 0.0) else raw.normalized()
    }

    private fun detectScratchOnSegment(
        start: Vec2,
        end: Vec2,
        pockets: List<PocketSpec>,
    ): PocketSpec? {
        val seg = end - start
        val segLenSq = seg.lengthSquared()
        var bestPocket: PocketSpec? = null
        var bestT = Double.POSITIVE_INFINITY

        for (pocket in pockets) {
            val t = if (segLenSq < 1e-12) {
                0.0
            } else {
                ((pocket.center - start).dot(seg) / segLenSq).coerceIn(0.0, 1.0)
            }
            val closest = start + seg * t
            if (pocket.center.distanceTo(closest) <= pocket.acceptanceRadiusMeters) {
                if (t < bestT) {
                    bestT = t
                    bestPocket = pocket
                }
            }
        }
        return bestPocket
    }
}
