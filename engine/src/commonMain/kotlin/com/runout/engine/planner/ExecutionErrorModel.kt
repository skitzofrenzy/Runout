package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.CueBallTrajectory
import com.runout.engine.physics.GhostBallAim
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.physics.PostShotTableSimulation
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Deterministic uncertainty evaluation for a candidate shot under a [PlayerProfile].
 *
 * [landingCloudScratched] says, point by point, whether the cue ball of that [landingCloud] point drops into a pocket
 * (empty when the cloud was not built).
 */
data class ShotUncertaintyResult(
    val makeProbability: Double,
    val scratchProbability: Double,
    val nominalTrajectory: CueBallTrajectory,
    val landingCloud: List<Vec2>,
    val meanLandingPosition: Vec2,
    val landingSpreadRadiusMeters: Double,
    val postShotSimulation: PostShotTableSimulation? = null,
    val nominalLandingPosition: Vec2 = meanLandingPosition,
    val targetBallPotted: Boolean = true,
    val landingCloudScratched: List<Boolean> = emptyList(),
)

/**
 * The player's execution error and what it does to a shot.
 *
 * **Make probability.** The player's aim error is one number, [PlayerProfile.aimErrorStdRad]: the standard deviation
 * of the angle between the cue line the player meant and the one he played, normally distributed. Each cue line meets
 * the object ball at its own contact point, and the line of centres at contact is the direction the object ball leaves
 * in ([objectBallDirectionForCueLine]); near the intended line a cue-line error `delta` turns it by
 * `delta * d / (2R cos(cut))` (`d` cue ball to ghost ball). The ball drops when that direction lies inside the pocket's
 * [PocketGeometry.entryWindow] at the jaw tolerance of its arrival speed. Each edge of the window is mapped back
 * exactly to the cue-line angle that sends the ball along it, so the make probability is the normal probability mass
 * between those two angles ([directionalMakeProbability]); the window need not be centred on the aimed line. Speed
 * error changes whether the ball reaches the pocket and how fast it arrives (and so the jaw tolerance); it is
 * integrated with a three-point Gauss-Hermite rule over the speed fraction.
 *
 * Side spin adds error: the player has to allow for squirt, swerve and spin-induced throw, and misjudges that
 * allowance by [SIDE_SPIN_JUDGEMENT_ERROR_FRACTION] of its size (one standard deviation), added in quadrature to the
 * aim error ([cueLineErrorStdRad]).
 *
 * **Landing cloud.** The cue ball's resting place, scratch chance and spread come from a deterministic 9-point
 * sigma-point stencil over (cue-line angle, speed fraction, side tip, vertical tip); each point is simulated with the
 * full table simulation from its own contact geometry.
 */
object ExecutionErrorModel {
    private const val MAX_PERTURBED_CUT_RAD: Double = 1.45

    /** Weights aligned 1:1 with the 9-point sigma ensemble in [ShotUncertaintyResult.landingCloud]. */
    val SIGMA_WEIGHTS: List<Double> = listOf(0.28, 0.12, 0.12, 0.12, 0.12, 0.06, 0.06, 0.06, 0.06)

    /**
     * Standard deviation of the player's misjudgement of the side-spin allowance (squirt, swerve and spin throw) as a
     * fraction of that allowance. A modelling value: no measurement of it is known.
     */
    const val SIDE_SPIN_JUDGEMENT_ERROR_FRACTION: Double = 0.25

    /** Largest cut, in radians, at which a cue line still counts as hitting the object ball. */
    private const val GRAZING_CUT_RAD: Double = 89.5 * PI / 180.0

    /** Gauss-Hermite nodes (in standard deviations) and weights for the speed-error integral. */
    private val SPEED_NODES: DoubleArray = doubleArrayOf(0.0, sqrt(3.0), -sqrt(3.0))
    private val SPEED_WEIGHTS: DoubleArray = doubleArrayOf(2.0 / 3.0, 1.0 / 6.0, 1.0 / 6.0)

    private data class SigmaOffset(
        val dAimRad: Double,
        val dSpeedFrac: Double,
        val dSideRatio: Double,
        val dVertRatio: Double,
        val weight: Double,
    )

    /**
     * Half-angle, in radians, of the directions from [objectBallPos] that carry the object ball into [pocket] when it
     * arrives at [arrivalSpeedMps]: the [PocketGeometry.entryWindow] at the [PocketGeometry.jawTolerance] for that
     * speed. It narrows with distance, with a shallower approach to the mouth and with speed, and is (nearly) zero
     * when the ball cannot pass between the points at all.
     */
    fun pocketAcceptanceHalfAngleRadians(
        objectBallPos: Vec2,
        pocket: PocketSpec,
        objectBallRadiusMeters: Double,
        arrivalSpeedMps: Double,
    ): Double {
        val window = PocketGeometry.entryWindow(
            objectBallPos,
            pocket,
            objectBallRadiusMeters,
            PocketGeometry.jawTolerance(pocket, arrivalSpeedMps),
        )
        return window.halfAngleRadians.coerceAtLeast(MIN_HALF_ANGLE_RAD)
    }

    /** Smallest half-angle reported for a closed window, rad. */
    private const val MIN_HALF_ANGLE_RAD: Double = 1e-6

    /**
     * Standard deviation of the speed the player actually plays, as a fraction of the intended speed. Tied to the aim
     * error (a steadier stroke is steadier in both): about 6 % for an advanced, 7 % for an intermediate and 10 % for a
     * beginner profile. Like the aim error, these are estimates, not measurements.
     */
    fun speedErrorFraction(profile: PlayerProfile): Double =
        (0.045 + 9.2 * profile.aimErrorStdRad).coerceIn(0.04, 0.18)

    /**
     * Standard deviation, in radians, of the cue line for a stroke with [tip] at [cueSpeedMps] along [aim]: the
     * player's [PlayerProfile.aimErrorStdRad] and, with side spin, [SIDE_SPIN_JUDGEMENT_ERROR_FRACTION] of the
     * allowance he has to make for it (squirt and swerve, which turn the cue ball's path, and the spin part of the
     * throw, which turns the object ball's and is converted to the cue-line angle that has the same effect).
     */
    fun cueLineErrorStdRad(
        profile: PlayerProfile,
        aim: AimGeometry,
        cueSpeedMps: Double,
        tip: TipOffset,
        table: TableSpec,
    ): Double {
        val base = profile.aimErrorStdRad.coerceAtLeast(MIN_AIM_ERROR_STD_RAD)
        if (abs(tip.clampedSideRatio) < 1e-9) return base
        val squirt = SpinPhysics.computeSquirtAngleRadians(tip, profile.shaftType)
        val swerve = SpinPhysics.computeSwerveAngleRadians(tip, cueSpeedMps, aim.cueToGhostDistanceMeters)
        val pre = SpinPhysics.computePreImpactCueState(cueSpeedMps, tip, aim.cueToGhostDistanceMeters, table)
        val sideSpin = SpinPhysics.sideSpinSurfaceSpeedAtImpactMps(tip, cueSpeedMps, aim.cueToGhostDistanceMeters, table)
        val spinThrow = SpinPhysics.computeThrowAnglesRadians(
            aim.cutAngleRadians, pre.impactSpeedMps, pre.impactSpinFactorK, sideSpin,
        ).spinRadians
        val contact = aim.objectBallPos.distanceTo(aim.ghostBallCenter)
        val throwAsCueLine = abs(spinThrow) * contact * cos(aim.cutAngleRadians) /
            aim.cueToGhostDistanceMeters.coerceAtLeast(contact)
        val allowance = abs(squirt) + abs(swerve) + throwAsCueLine
        val judgement = SIDE_SPIN_JUDGEMENT_ERROR_FRACTION * allowance
        return sqrt(base * base + judgement * judgement)
    }

    /** Smallest aim error used, so that a zero-error profile still gives finite numbers, rad. */
    private const val MIN_AIM_ERROR_STD_RAD: Double = 1e-4

    /**
     * Direction the object ball at [objectBallPos] leaves in when the cue ball, starting at [cueBallPos], travels along
     * [cueLine] and meets it with centres [contactDistance] apart, or `null` if that line misses the object ball (a cut
     * beyond [GRAZING_CUT_RAD]). Also returns the cue ball's centre at contact.
     */
    internal fun objectBallDirectionForCueLine(
        cueBallPos: Vec2,
        objectBallPos: Vec2,
        cueLine: Vec2,
        contactDistance: Double,
    ): Pair<Vec2, Vec2>? {
        val u = cueLine.normalized()
        val toObject = objectBallPos - cueBallPos
        val along = toObject.dot(u)
        val lateralSq = toObject.lengthSquared() - along * along
        val maxLateral = contactDistance * kotlin.math.sin(GRAZING_CUT_RAD)
        if (along <= 0.0 || lateralSq > maxLateral * maxLateral) return null
        val t = along - sqrt((contactDistance * contactDistance - lateralSq).coerceAtLeast(0.0))
        val contactCentre = cueBallPos + u * t
        return (objectBallPos - contactCentre).normalized() to contactCentre
    }

    /**
     * Cue-line angle, relative to [aim]'s aim direction, that sends the object ball along [direction]; when no cue line
     * does (the cut would pass [GRAZING_CUT_RAD]) the angle of the grazing line on that side, beyond which the cue ball
     * misses the object ball.
     */
    private fun cueLineOffsetFor(aim: AimGeometry, direction: Vec2, contactDistance: Double): Double {
        val ghost = aim.objectBallPos - direction * contactDistance
        val toGhost = ghost - aim.cueBallPos
        val cut = toGhost.normalized().angleTo(direction)
        if (abs(cut) <= GRAZING_CUT_RAD && toGhost.dot(aim.objectBallPos - aim.cueBallPos) > 0.0) {
            return aim.aimDirection.angleTo(toGhost.normalized())
        }
        // The cut falls as the cue line turns counter-clockwise: a cut toward +90° lies at the clockwise graze.
        val toObject = aim.objectBallPos - aim.cueBallPos
        val centre = aim.aimDirection.angleTo(toObject.normalized())
        val half = asin((contactDistance * kotlin.math.sin(GRAZING_CUT_RAD) / toObject.length()).coerceIn(0.0, 1.0))
        return if (cut > 0.0) centre - half else centre + half
    }

    /**
     * Probability that the object ball of [aim] drops into [pocket] when the cue line is off by a normal error with
     * standard deviation [cueLineErrorStdRad]: the probability mass of the cue-line angles whose object-ball direction
     * lies inside [pocket]'s entry window at [jawTolerance]. Each window edge is mapped exactly through the ghost-ball
     * geometry to the cue-line angle that produces it, so an off-centre aim or a lopsided window is integrated as it is.
     * `0` when the window is closed.
     */
    fun directionalMakeProbability(
        aim: AimGeometry,
        pocket: PocketSpec,
        objectBallRadiusMeters: Double,
        cueBallRadiusMeters: Double,
        jawTolerance: Double,
        cueLineErrorStdRad: Double,
    ): Double {
        if (aim.cueToGhostDistanceMeters <= 1e-6) return 0.0
        val window = PocketGeometry.entryWindow(aim.objectBallPos, pocket, objectBallRadiusMeters, jawTolerance)
        if (!window.isOpen) return 0.0
        val contact = cueBallRadiusMeters + objectBallRadiusMeters
        val a = cueLineOffsetFor(aim, window.limitA, contact)
        val b = cueLineOffsetFor(aim, window.limitB, contact)
        val sigma = cueLineErrorStdRad.coerceAtLeast(MIN_AIM_ERROR_STD_RAD)
        return abs(normalCdf(b / sigma) - normalCdf(a / sigma)).coerceIn(0.0, 1.0)
    }

    /** Standard normal cumulative distribution (Abramowitz and Stegun 7.1.26 for erf, absolute error below 1.5e-7). */
    internal fun normalCdf(x: Double): Double {
        val z = abs(x) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * z)
        val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
        val erf = 1.0 - poly * exp(-z * z)
        return if (x >= 0.0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
    }

    /**
     * Make probability of [aim] into [pocket] for a stroke at [cueSpeedMps] with [tip]: [directionalMakeProbability]
     * at the cue-line error of [cueLineErrorStdRad], averaged over the player's speed error (three-point Gauss-Hermite
     * rule) with each speed's reach (the object ball must get to the pocket's capture line) and its arrival speed's jaw
     * tolerance.
     */
    fun makeProbability(
        table: TableSpec,
        targetBall: Ball,
        cueBallRadiusMeters: Double,
        pocket: PocketSpec,
        aim: AimGeometry,
        cueSpeedMps: Double,
        tip: TipOffset,
        playerProfile: PlayerProfile,
    ): Double {
        if (!aim.isShotPlayable) return 0.0
        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val muS = table.cloth.slidingFrictionMuS.coerceAtLeast(1e-4)
        val g = MotionPhysics.GRAVITY_M_S2
        val dropDist = (
            PocketGeometry.distanceToCaptureLine(aim.objectBallPos, aim.objectBallDirection, pocket, targetBall.radiusMeters)
                ?: aim.objectToPocketDistanceMeters
            ).coerceAtLeast(0.02)
        val sigmaCue = cueLineErrorStdRad(playerProfile, aim, cueSpeedMps, tip, table)
        val sigmaSpeed = speedErrorFraction(playerProfile)
        var total = 0.0
        for (i in SPEED_NODES.indices) {
            val speed = (cueSpeedMps * (1.0 + SPEED_NODES[i] * sigmaSpeed)).coerceAtLeast(0.05)
            val pre = SpinPhysics.computePreImpactCueState(speed, tip, aim.cueToGhostDistanceMeters, table)
            if (pre.state == SpinPhysics.ImpactSpinState.STOPPED_SHORT || pre.impactSpeedMps <= 0.03) continue
            val vObject = MotionPhysics.transferredObjectBallInitialSpeedMps(pre.impactSpeedMps, aim.cutAngleRadians)
            if (MotionPhysics.objectBallStoppingDistanceMeters(vObject, muS, muR, g) < dropDist) continue
            val arrival = MotionPhysics.slideThenRollSpeedAfterDistanceMps(vObject, dropDist, muS, muR, g = g)
            total += SPEED_WEIGHTS[i] * directionalMakeProbability(
                aim = aim,
                pocket = pocket,
                objectBallRadiusMeters = targetBall.radiusMeters,
                cueBallRadiusMeters = cueBallRadiusMeters,
                jawTolerance = PocketGeometry.jawTolerance(pocket, arrival),
                cueLineErrorStdRad = sigmaCue,
            )
        }
        return total.coerceIn(0.0, 1.0)
    }

    /**
     * Evaluates make probability ([makeProbability]), scratch probability and the cue-ball landing cloud for [aim]
     * executed at [cueSpeedMps] with [tip] under [playerProfile]'s errors.
     */
    fun evaluateShotUncertainty(
        layout: TableLayout,
        targetBall: Ball,
        pocket: PocketSpec,
        aim: GhostBallAim,
        cueSpeedMps: Double,
        tip: TipOffset,
        playerProfile: PlayerProfile = PlayerProfile(),
    ): ShotUncertaintyResult {
        val table = layout.table
        val cueBallRadius = layout.cueBall()?.radiusMeters ?: table.ballRadiusMeters
        val sigmaAim = cueLineErrorStdRad(playerProfile, aim, cueSpeedMps, tip, table)
        val sigmaSpeedFrac = speedErrorFraction(playerProfile)

        val stencil = listOf(
            SigmaOffset(0.0, 0.0, 0.0, 0.0, SIGMA_WEIGHTS[0]),
            SigmaOffset(1.35 * sigmaAim, 0.0, 0.0, 0.0, SIGMA_WEIGHTS[1]),
            SigmaOffset(-1.35 * sigmaAim, 0.0, 0.0, 0.0, SIGMA_WEIGHTS[2]),
            SigmaOffset(0.0, 1.35 * sigmaSpeedFrac, 0.0, 0.0, SIGMA_WEIGHTS[3]),
            SigmaOffset(0.0, -1.35 * sigmaSpeedFrac, 0.0, 0.0, SIGMA_WEIGHTS[4]),
            SigmaOffset(0.85 * sigmaAim, 0.85 * sigmaSpeedFrac, 0.05, 0.0, SIGMA_WEIGHTS[5]),
            SigmaOffset(-0.85 * sigmaAim, -0.85 * sigmaSpeedFrac, -0.05, 0.0, SIGMA_WEIGHTS[6]),
            SigmaOffset(-0.85 * sigmaAim, 0.85 * sigmaSpeedFrac, 0.0, 0.05, SIGMA_WEIGHTS[7]),
            SigmaOffset(0.85 * sigmaAim, -0.85 * sigmaSpeedFrac, 0.0, -0.05, SIGMA_WEIGHTS[8]),
        )

        val contactDist = cueBallRadius + targetBall.radiusMeters
        val nominalSquirt = SpinPhysics.computeSquirtAngleRadians(tip, playerProfile.shaftType)
        val nominalSimulation = TableCollisionSimulator.simulatePostImpact(
            layout = layout,
            targetBall = targetBall,
            aim = aim,
            cueSpeedMps = cueSpeedMps,
            tip = tip,
        )

        val landingCloud = ArrayList<Vec2>(stencil.size)
        val scratchedAtPoint = ArrayList<Boolean>(stencil.size)
        var nominalTrajectory: CueBallTrajectory? = null
        var nominalStop = aim.ghostBallCenter
        var weightedScratchProb = 0.0
        var meanX = 0.0
        var meanY = 0.0
        var nonScratchWeightSum = 0.0
        var nonScratchX = 0.0
        var nonScratchY = 0.0
        val maxStopX = (table.lengthMeters - cueBallRadius).coerceAtLeast(cueBallRadius)
        val maxStopY = (table.widthMeters - cueBallRadius).coerceAtLeast(cueBallRadius)

        for ((index, point) in stencil.withIndex()) {
            val perturbedSpeed = (cueSpeedMps * (1.0 + point.dSpeedFrac)).coerceAtLeast(0.30)
            val perturbedTip = TipOffset(
                sideRatio = tip.sideRatio + point.dSideRatio,
                verticalRatio = tip.verticalRatio + point.dVertRatio,
                elevationRadians = tip.elevationRadians,
            )
            val perturbedAim = if (index == 0) {
                aim
            } else {
                // A different side tip squirts the cue ball by a different amount: that, too, turns the cue line.
                val squirtChange = SpinPhysics.computeSquirtAngleRadians(perturbedTip, playerProfile.shaftType) - nominalSquirt
                perturbedAimFor(aim, point.dAimRad + squirtChange, contactDist)
            }

            val trajectory = when {
                index == 0 -> nominalSimulation.cueBallTrajectory
                else -> TableCollisionSimulator.simulatePostImpact(
                    layout = layout,
                    targetBall = targetBall,
                    aim = perturbedAim,
                    cueSpeedMps = perturbedSpeed,
                    tip = perturbedTip,
                ).cueBallTrajectory
            }
            if (index == 0) {
                nominalTrajectory = trajectory
            }

            val rawStop = if (index == 0) {
                nominalSimulation.resultingLayout.cueBall()?.position
                    ?: trajectory.points.lastOrNull()
                    ?: perturbedAim.ghostBallCenter
            } else {
                trajectory.points.lastOrNull() ?: perturbedAim.ghostBallCenter
            }
            val stopPos = Vec2(
                x = rawStop.x.coerceIn(cueBallRadius, maxStopX),
                y = rawStop.y.coerceIn(cueBallRadius, maxStopY),
            )
            if (index == 0) {
                nominalStop = stopPos
            }
            landingCloud += stopPos
            meanX += point.weight * stopPos.x
            meanY += point.weight * stopPos.y

            val didScratch = trajectory.hitsScratchPocket != null
            scratchedAtPoint += didScratch
            if (didScratch) {
                weightedScratchProb += point.weight
            } else {
                nonScratchWeightSum += point.weight
                nonScratchX += point.weight * stopPos.x
                nonScratchY += point.weight * stopPos.y
            }
        }

        val useNonScratchOnly = nonScratchWeightSum > 1e-6
        val resolvedMeanX = if (useNonScratchOnly) nonScratchX / nonScratchWeightSum else meanX
        val resolvedMeanY = if (useNonScratchOnly) nonScratchY / nonScratchWeightSum else meanY
        val meanLanding = Vec2(
            x = resolvedMeanX.coerceIn(cueBallRadius, maxStopX),
            y = resolvedMeanY.coerceIn(cueBallRadius, maxStopY),
        )
        var meanSqDist = 0.0
        var spreadWeightSum = 0.0
        for (i in stencil.indices) {
            if (useNonScratchOnly && scratchedAtPoint[i]) continue
            val w = stencil[i].weight
            meanSqDist += w * landingCloud[i].distanceSquaredTo(meanLanding)
            spreadWeightSum += w
        }
        val rmsSq = if (spreadWeightSum > 1e-6) meanSqDist / spreadWeightSum else meanSqDist
        val spreadRadius = max(1.96 * sqrt(rmsSq), meanLanding.distanceTo(nominalStop))

        val targetPottedNominally = nominalSimulation.targetBallPotted &&
            !nominalSimulation.cueBallStoppedBeforeImpact

        val makeProbability = makeProbability(
            table = table,
            targetBall = targetBall,
            cueBallRadiusMeters = cueBallRadius,
            pocket = pocket,
            aim = aim,
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            playerProfile = playerProfile,
        )

        val resolvedNominal = nominalTrajectory ?: SpinPhysics.traceCueBallPathWithSpin(
            aim = aim,
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            table = table,
            ballRadiusMeters = cueBallRadius,
        )

        return ShotUncertaintyResult(
            makeProbability = makeProbability,
            scratchProbability = weightedScratchProb.coerceIn(0.0, 1.0),
            nominalTrajectory = resolvedNominal,
            landingCloud = landingCloud,
            meanLandingPosition = meanLanding,
            landingSpreadRadiusMeters = spreadRadius,
            postShotSimulation = nominalSimulation,
            nominalLandingPosition = nominalStop,
            targetBallPotted = targetPottedNominally,
            landingCloudScratched = scratchedAtPoint,
        )
    }

    /**
     * [aim] with the cue line turned by [cueLineOffsetRad]: the cue ball meets the object ball where that line first
     * comes within a contact distance of it, and the object ball leaves along the line of centres there. A line that
     * would miss is held at the grazing cut.
     */
    private fun perturbedAimFor(aim: GhostBallAim, cueLineOffsetRad: Double, contactDist: Double): GhostBallAim {
        val line = aim.aimDirection.rotate(cueLineOffsetRad)
        val hit = objectBallDirectionForCueLine(aim.cueBallPos, aim.objectBallPos, line, contactDist)
        val (objectDir, ghost) = hit ?: run {
            // Missed: keep the grazing contact on the side the line turned to.
            val toObject = (aim.objectBallPos - aim.cueBallPos)
            val half = asin((contactDist * kotlin.math.sin(GRAZING_CUT_RAD) / toObject.length()).coerceIn(0.0, 1.0))
            val centre = aim.aimDirection.angleTo(toObject.normalized())
            val clamped = if (cueLineOffsetRad > centre) centre + half * 0.999 else centre - half * 0.999
            objectBallDirectionForCueLine(aim.cueBallPos, aim.objectBallPos, aim.aimDirection.rotate(clamped), contactDist)
                ?: (aim.objectBallDirection to aim.ghostBallCenter)
        }
        val toGhost = ghost - aim.cueBallPos
        val aimDir = toGhost.normalized()
        return aim.copy(
            ghostBallCenter = ghost,
            aimDirection = aimDir,
            objectBallDirection = objectDir,
            cutAngleRadians = aimDir.angleTo(objectDir).coerceIn(-MAX_PERTURBED_CUT_RAD, MAX_PERTURBED_CUT_RAD),
            cueToGhostDistanceMeters = toGhost.length(),
        )
    }
}
