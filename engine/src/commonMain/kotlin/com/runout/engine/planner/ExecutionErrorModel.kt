package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.CueBallTrajectory
import com.runout.engine.physics.GhostBallAim
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.PostShotTableSimulation
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Deterministic uncertainty evaluation for a candidate shot under a [PlayerProfile].
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
)

/**
 * Models pocket capture acceptance windows and propagates player execution errors
 * (aim angle, speed fraction, and 2D tip offset) through a deterministic 9-point
 * sigma-point ensemble to compute make probability, scratch probability, and cue-ball
 * landing spread.
 */
object ExecutionErrorModel {
    private const val SQRT_2: Double = 1.4142135623730951
    private const val MAX_PERTURBED_CUT_RAD: Double = 1.45

    /** Weights aligned 1:1 with the 9-point sigma ensemble in [ShotUncertaintyResult.landingCloud]. */
    val SIGMA_WEIGHTS: List<Double> = listOf(0.28, 0.12, 0.12, 0.12, 0.12, 0.06, 0.06, 0.06, 0.06)

    private data class SigmaOffset(
        val dAimRad: Double,
        val dSpeedFrac: Double,
        val dSideRatio: Double,
        val dVertRatio: Double,
        val weight: Double,
    )

    /**
     * Computes the effective half-angle acceptance window (in radians) of [pocket] as seen
     * from [objectBallPos], narrowing with distance, [arrivalSpeedMps], and shallow
     * pocket entry angle.
     */
    fun pocketAcceptanceHalfAngleRadians(
        objectBallPos: Vec2,
        pocket: PocketSpec,
        objectBallRadiusMeters: Double,
        arrivalSpeedMps: Double,
    ): Double {
        val d = objectBallPos.distanceTo(pocket.center).coerceAtLeast(objectBallRadiusMeters * 1.5)
        val mouthRadiusMeters = pocket.mouthWidthMeters * 0.5
        val clearance = (mouthRadiusMeters - objectBallRadiusMeters * 0.72).coerceAtLeast(0.012)
        val speedFactor = (1.15 - 0.09 * arrivalSpeedMps.coerceIn(0.0, 4.5)).coerceIn(0.62, 1.10)
        val toPocket = (pocket.center - objectBallPos).normalized()
        val approachFactor = when (pocket.id) {
            PocketId.SIDE_LEFT,
            PocketId.SIDE_RIGHT,
            -> {
                val ny = abs(toPocket.y)
                if (ny < 0.58) {
                    (ny * ny * 1.48).coerceIn(0.20, 0.58)
                } else {
                    ny.coerceIn(0.58, 1.0)
                }
            }
            else -> ((abs(toPocket.x) + abs(toPocket.y)) / SQRT_2).coerceIn(0.70, 1.0)
        }
        return atan2(clearance * speedFactor * approachFactor, d).coerceIn(0.008, 0.35)
    }

    /**
     * Evaluates make probability, scratch probability, and cue-ball landing cloud for [aim]
     * executed at [cueSpeedMps] with [tip] under [playerProfile]'s error standard deviations.
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
        val sigmaAim = playerProfile.aimErrorStdRad.coerceAtLeast(0.003)
        val sigmaSpeedFrac = (0.04 + playerProfile.aimErrorStdRad * 2.5).coerceIn(0.04, 0.18)

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

        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val muS = table.cloth.slidingFrictionMuS.coerceAtLeast(1e-4)
        val g = MotionPhysics.GRAVITY_M_S2
        val dropDist = (aim.objectToPocketDistanceMeters - pocket.acceptanceRadiusMeters * 0.75)
            .coerceAtLeast(0.02)
        val contactDist = cueBallRadius + targetBall.radiusMeters
        val contactGain = (1.0 + aim.cueToGhostDistanceMeters / (2.0 * targetBall.radiusMeters).coerceAtLeast(0.05)) * 0.18
        val cosCut = cos(aim.cutAngleRadians).coerceAtLeast(0.22)
        val obstacleBalls = layout.balls.filter { !it.isCueBall && it.number != targetBall.number }
        val nominalSimulation = TableCollisionSimulator.simulatePostImpact(
            layout = layout,
            targetBall = targetBall,
            aim = aim,
            cueSpeedMps = cueSpeedMps,
            tip = tip,
        )

        val landingCloud = ArrayList<Vec2>(stencil.size)
        val scratchedAtPoint = BooleanArray(stencil.size)
        var nominalTrajectory: CueBallTrajectory? = null
        var nominalStop = aim.ghostBallCenter
        var weightedScratchProb = 0.0
        var weightedEnsembleMake = 0.0
        var weightedReachFrac = 0.0
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

            val spinAimPerturb = -0.04 * point.dSideRatio
            val deltaThetaObj = (point.dAimRad + spinAimPerturb) * contactGain / cosCut
            val perturbedObjDir = aim.objectBallDirection.rotate(-deltaThetaObj).normalized()
            val perturbedAimDir = aim.aimDirection.rotate(point.dAimRad).normalized()
            val perturbedCut = perturbedAimDir.angleTo(perturbedObjDir)
                .coerceIn(-MAX_PERTURBED_CUT_RAD, MAX_PERTURBED_CUT_RAD)
            val perturbedGhost = aim.objectBallPos - perturbedObjDir * contactDist

            val perturbedAim = if (index == 0) {
                aim
            } else {
                aim.copy(
                    ghostBallCenter = perturbedGhost,
                    aimDirection = perturbedAimDir,
                    objectBallDirection = perturbedObjDir,
                    cutAngleRadians = perturbedCut,
                )
            }

            val trajectory = when {
                index == 0 -> nominalSimulation.cueBallTrajectory
                nominalSimulation.disturbedBalls.isNotEmpty() -> TableCollisionSimulator.simulatePostImpact(
                    layout = layout,
                    targetBall = targetBall,
                    aim = perturbedAim,
                    cueSpeedMps = perturbedSpeed,
                    tip = perturbedTip,
                ).cueBallTrajectory
                else -> SpinPhysics.traceCueBallPathWithSpin(
                    aim = perturbedAim,
                    cueSpeedMps = perturbedSpeed,
                    tip = perturbedTip,
                    table = table,
                    ballRadiusMeters = cueBallRadius,
                    maxBounces = 6,
                    obstacleBalls = obstacleBalls,
                )
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
            scratchedAtPoint[index] = didScratch
            if (didScratch) {
                weightedScratchProb += point.weight
            } else {
                nonScratchWeightSum += point.weight
                nonScratchX += point.weight * stopPos.x
                nonScratchY += point.weight * stopPos.y
            }

            val preImpact = SpinPhysics.computePreImpactCueState(
                cueSpeedMps = perturbedSpeed,
                tip = perturbedTip,
                cueToGhostDistanceMeters = perturbedAim.cueToGhostDistanceMeters,
                table = table,
            )
            val vObjInitial = MotionPhysics.transferredObjectBallInitialSpeedMps(
                cueImpactSpeedMps = preImpact.impactSpeedMps,
                cutAngleRad = perturbedCut,
            )
            val objMaxDist = MotionPhysics.objectBallStoppingDistanceMeters(vObjInitial, muS, muR, g)
            val reachesPocket = preImpact.state != SpinPhysics.ImpactSpinState.STOPPED_SHORT &&
                preImpact.impactSpeedMps > 0.03 &&
                objMaxDist >= dropDist
            if (reachesPocket) {
                weightedReachFrac += point.weight
                val arrivalSpeed = MotionPhysics.initialRollingSpeedForDistance(
                    distanceMeters = (objMaxDist - dropDist).coerceAtLeast(0.0),
                    muR = muR,
                    g = g,
                )
                val pointHalfAngle = pocketAcceptanceHalfAngleRadians(
                    objectBallPos = targetBall.position,
                    pocket = pocket,
                    objectBallRadiusMeters = targetBall.radiusMeters,
                    arrivalSpeedMps = arrivalSpeed,
                )
                val survival = exp(-0.5 * (deltaThetaObj / pointHalfAngle).pow(2))
                weightedEnsembleMake += point.weight * survival
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

        val nomPreImpact = SpinPhysics.computePreImpactCueState(
            cueSpeedMps = cueSpeedMps,
            tip = tip,
            cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
            table = table,
        )
        val nomVObj = MotionPhysics.transferredObjectBallInitialSpeedMps(
            cueImpactSpeedMps = nomPreImpact.impactSpeedMps,
            cutAngleRad = aim.cutAngleRadians,
        )
        val nomObjMaxDist = MotionPhysics.objectBallStoppingDistanceMeters(nomVObj, muS, muR, g)
        val nomArrivalSpeed = MotionPhysics.initialRollingSpeedForDistance(
            distanceMeters = (nomObjMaxDist - dropDist).coerceAtLeast(0.0),
            muR = muR,
            g = g,
        )
        val nomHalfAngle = pocketAcceptanceHalfAngleRadians(
            objectBallPos = targetBall.position,
            pocket = pocket,
            objectBallRadiusMeters = targetBall.radiusMeters,
            arrivalSpeedMps = nomArrivalSpeed,
        )
        val sigmaThetaObj = sigmaAim * contactGain / cosCut
        val gaussianMakeProb = exp(-0.5 * (sigmaThetaObj / nomHalfAngle).pow(2))
        val targetPottedNominally = nominalSimulation.targetBallPotted &&
            !nominalSimulation.cueBallStoppedBeforeImpact

        val makeProbability = if (!aim.isShotPlayable || weightedReachFrac <= 1e-6) {
            0.02
        } else {
            val rawMake = gaussianMakeProb * weightedReachFrac * (0.55 + 0.45 * weightedEnsembleMake)
            rawMake.coerceIn(0.02, 0.99)
        }

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
        )
    }
}
