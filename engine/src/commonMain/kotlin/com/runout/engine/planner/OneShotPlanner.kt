package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.CueBallPath
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import com.runout.engine.physics.TrajectoryPath
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Shot planner that enumerates legal target-ball and pocket combinations, filters blocked or
 * impossible cuts (`> 80°`) and off-table ghost balls, evaluates spin presets across candidate
 * player speed scales (`3, 5, 7` or an explicit override), scores 2-ball position play against the
 * next legal target ball, and ranks candidates with scores normalized in `1e-6..1.0`.
 */
object OneShotPlanner {
    private val SPIN_CANDIDATES: List<CueBallSpinPreset> = listOf(
        CueBallSpinPreset.STUN,
        CueBallSpinPreset.FOLLOW,
        CueBallSpinPreset.DRAW,
    )

    private val CANDIDATE_SPEED_UNITS: List<Double> = listOf(2.5, 3.5, 4.5, 5.5, 7.0)

    private const val DEFAULT_CUE_SPEED_MPS: Double = 2.0
    private const val CUE_DISTANCE_WEIGHT: Double = 0.45
    private const val OBJECT_DISTANCE_WEIGHT: Double = 0.65
    private const val AIM_ERROR_DISTANCE_GAIN: Double = 15.0
    private const val POCKET_WIDTH_NORMALIZATION: Double = 1.15
    private const val ALL_SCRATCH_PENALTY_FACTOR: Double = 0.15
    private const val POSITION_BONUS_WEIGHT: Double = 0.25
    private const val FINAL_BALL_POSITION_BONUS_WEIGHT: Double = 0.42
    private const val LOW_DRAW_RATIO_THRESHOLD: Double = 0.8
    private const val LOW_DRAW_SCORE_PENALTY: Double = 0.15
    private const val PREFERRED_DEFAULT_SPEED_UNITS: Double = 4.5

    fun rankShots(
        layout: TableLayout,
        rulesState: GameRulesState = GameRulesState(),
        defaultCueSpeedMps: Double = DEFAULT_CUE_SPEED_MPS,
        playerProfile: PlayerProfile = PlayerProfile(),
        cueSpeedScaleOverride: Int? = null,
        cueSpeedUnitsOverride: Double? = null,
    ): List<ShotRecommendation> {
        val cueBall = layout.cueBall() ?: return emptyList()
        val legalTargets = rulesState.legalTargetBalls(layout)
        if (legalTargets.isEmpty()) return emptyList()

        val table = layout.table
        val tableCenter = Vec2(table.lengthMeters * 0.5, table.widthMeters * 0.5)
        val halfDiagonal = hypot(table.lengthMeters * 0.5, table.widthMeters * 0.5).coerceAtLeast(1e-6)
        val candidateScalesAndSpeeds = resolveCandidateSpeeds(
            defaultCueSpeedMps = defaultCueSpeedMps,
            playerProfile = playerProfile,
            cueSpeedScaleOverride = cueSpeedScaleOverride,
            cueSpeedUnitsOverride = cueSpeedUnitsOverride,
        )

        val recommendations = mutableListOf<ShotRecommendation>()

        for (targetBall in legalTargets) {
            val remainingBalls = layout.balls.filter { it.number != targetBall.number }
            val remainingLayout = TableLayout(table, remainingBalls)
            val nextTargets = nextLegalTargetsAfterPotting(
                remainingLayout = remainingLayout,
                pottedBall = targetBall,
                rulesState = rulesState,
            )

            for (pocket in table.pockets) {
                val aim = GhostBallAiming.computeAimGeometry(
                    cueBall = cueBall,
                    objectBall = targetBall,
                    pocket = pocket,
                    otherBalls = layout.balls,
                    table = table,
                )
                if (!aim.isShotPlayable) continue

                val baseScore = computeBaseShotScore(
                    aim = aim,
                    pocket = pocket,
                    table = table,
                    aimErrorStdRad = playerProfile.aimErrorStdRad,
                )

                val isSettingUpFinalBall = nextTargets.size == 1 &&
                    nextTargets.first().number == rulesState.gameType.requiredEndBallNumber
                val bestCandidate = selectBestSpinAndSpeed(
                    cueBall = cueBall,
                    aim = aim,
                    baseScore = baseScore,
                    table = table,
                    tableCenter = tableCenter,
                    halfDiagonal = halfDiagonal,
                    remainingLayout = remainingLayout,
                    nextTargets = nextTargets,
                    isSettingUpFinalBall = isSettingUpFinalBall,
                    playerProfile = playerProfile,
                    candidateScalesAndSpeeds = candidateScalesAndSpeeds,
                ) ?: continue

                recommendations += ShotRecommendation(
                    targetBall = targetBall,
                    pocket = pocket,
                    aim = aim,
                    cueBallPaths = bestCandidate.pathsAtSpeed,
                    recommendedSpin = bestCandidate.spin,
                    recommendedCueSpeedMps = bestCandidate.speedMps,
                    score = bestCandidate.score,
                    recommendedSpeedScale = bestCandidate.speedScale,
                    recommendedSpeedUnits = bestCandidate.speedUnits,
                    nextTargetBallNumber = bestCandidate.nextTargetBallNumber,
                    nextTargetPositionLine = bestCandidate.nextTargetPositionLine,
                )
            }
        }

        return recommendations.sortedWith(
            compareByDescending<ShotRecommendation> { it.score }
                .thenBy { it.targetBall.number }
                .thenBy { it.pocket.id.ordinal },
        )
    }

    private fun resolveCandidateSpeeds(
        defaultCueSpeedMps: Double,
        playerProfile: PlayerProfile,
        cueSpeedScaleOverride: Int?,
        cueSpeedUnitsOverride: Double?,
    ): List<Pair<Double, Double>> = when {
        cueSpeedUnitsOverride != null -> {
            val snapped = PlayerProfile.snapSpeedUnits(cueSpeedUnitsOverride)
            listOf(snapped to playerProfile.speedForUnits(snapped))
        }
        cueSpeedScaleOverride != null -> {
            val clampedScale = cueSpeedScaleOverride.coerceIn(
                PlayerProfile.MIN_SPEED_SCALE,
                PlayerProfile.MAX_SPEED_SCALE,
            )
            listOf(clampedScale.toDouble() to playerProfile.speedForScale(clampedScale))
        }
        defaultCueSpeedMps != DEFAULT_CUE_SPEED_MPS -> {
            listOf(playerProfile.scaleForSpeed(defaultCueSpeedMps).toDouble() to defaultCueSpeedMps)
        }
        else -> CANDIDATE_SPEED_UNITS.map { units ->
            units to playerProfile.speedForUnits(units)
        }
    }

    private fun computeBaseShotScore(
        aim: AimGeometry,
        pocket: PocketSpec,
        table: TableSpec,
        aimErrorStdRad: Double,
    ): Double {
        val cutFactor = cos(abs(aim.cutAngleRadians)).coerceAtLeast(0.0)
        val extraAimPenalty = (aimErrorStdRad - PlayerProfile.DEFAULT_AIM_ERROR_STD_RAD)
            .coerceAtLeast(0.0) * AIM_ERROR_DISTANCE_GAIN
        val effectiveObjectWeight = OBJECT_DISTANCE_WEIGHT + extraAimPenalty
        val distanceFactor = 1.0 / (
            1.0 +
                CUE_DISTANCE_WEIGHT * aim.cueToGhostDistanceMeters +
                effectiveObjectWeight * aim.objectToPocketDistanceMeters
            )
        val pocketWidthBonus = pocket.mouthWidthMeters / (table.ballRadiusMeters * 4.0)
        val sideApproachPenalty = if (pocket.id == PocketId.SIDE_LEFT || pocket.id == PocketId.SIDE_RIGHT) {
            val ny = abs(aim.objectBallDirection.y)
            if (ny < 0.58) (ny * ny * 1.48).coerceIn(0.25, 0.85) else 1.0
        } else {
            1.0
        }
        val normalizedPocketFactor = ((pocketWidthBonus / POCKET_WIDTH_NORMALIZATION) * sideApproachPenalty)
            .coerceIn(0.25, 1.0)
        return (cutFactor * distanceFactor * normalizedPocketFactor).coerceIn(1e-6, 1.0)
    }

    private fun nextLegalTargetsAfterPotting(
        remainingLayout: TableLayout,
        pottedBall: Ball,
        rulesState: GameRulesState,
    ): List<Ball> = when (rulesState.gameType) {
        GameType.NINE_BALL -> {
            if (pottedBall.number == 9) emptyList() else rulesState.legalTargetBalls(remainingLayout)
        }
        GameType.TEN_BALL -> {
            if (pottedBall.number == 10) emptyList() else rulesState.legalTargetBalls(remainingLayout)
        }
        GameType.EIGHT_BALL -> {
            if (pottedBall.number == 8) emptyList() else rulesState.legalTargetBalls(remainingLayout)
        }
    }

    private fun evaluateBestNextShot(
        cueBall: Ball,
        cueBallStop: Vec2,
        remainingLayout: TableLayout,
        nextTargets: List<Ball>,
        aimErrorStdRad: Double,
    ): NextShotEvaluation? {
        if (nextTargets.isEmpty()) return null
        val table = remainingLayout.table
        val virtualCue = cueBall.copy(position = cueBallStop)
        var best: NextShotEvaluation? = null

        for (nextBall in nextTargets) {
            val minSeparation = cueBall.radiusMeters + nextBall.radiusMeters - 1e-4
            if (cueBallStop.distanceTo(nextBall.position) < minSeparation) continue

            for (nextPocket in table.pockets) {
                val nextAim = GhostBallAiming.computeAimGeometry(
                    cueBall = virtualCue,
                    objectBall = nextBall,
                    pocket = nextPocket,
                    otherBalls = remainingLayout.balls,
                    table = table,
                )
                if (!nextAim.isShotPlayable) continue

                val nextScore = computeBaseShotScore(
                    aim = nextAim,
                    pocket = nextPocket,
                    table = table,
                    aimErrorStdRad = aimErrorStdRad,
                )
                if (best == null || nextScore > best.score) {
                    best = NextShotEvaluation(
                        nextBallNumber = nextBall.number,
                        nextGhostCenter = nextAim.ghostBallCenter,
                        score = nextScore,
                    )
                }
            }
        }
        return best
    }

    private fun selectBestSpinAndSpeed(
        cueBall: Ball,
        aim: AimGeometry,
        baseScore: Double,
        table: TableSpec,
        tableCenter: Vec2,
        halfDiagonal: Double,
        remainingLayout: TableLayout,
        nextTargets: List<Ball>,
        isSettingUpFinalBall: Boolean,
        playerProfile: PlayerProfile,
        candidateScalesAndSpeeds: List<Pair<Double, Double>>,
    ): CandidateEvaluation? {
        var best: CandidateEvaluation? = null
        val positionWeight = if (isSettingUpFinalBall) FINAL_BALL_POSITION_BONUS_WEIGHT else POSITION_BONUS_WEIGHT

        for ((speedUnits, speedMps) in candidateScalesAndSpeeds) {
            val scaleInt = speedUnits.roundToInt().coerceIn(
                PlayerProfile.MIN_SPEED_SCALE,
                PlayerProfile.MAX_SPEED_SCALE,
            )
            val paths = CueBallPath.computePostImpactPaths(
                aim = aim,
                table = table,
                initialCueSpeedMps = speedMps,
            )

            for (spin in SPIN_CANDIDATES) {
                val chosenPath = paths.getValue(spin)
                val scratches = chosenPath.hitsScratchPocket != null
                val cueStop = chosenPath.points.lastOrNull() ?: aim.ghostBallCenter
                val centerCloseness = 1.0 - (cueStop.distanceTo(tableCenter) / halfDiagonal).coerceIn(0.0, 1.0)
                val centerDistance = cueStop.distanceTo(tableCenter)
                val preImpact = SpinPhysics.computePreImpactCueState(
                    cueSpeedMps = speedMps,
                    tip = TipOffset.fromPreset(spin),
                    cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
                    table = table,
                )

                val bestNext = if (!scratches) {
                    evaluateBestNextShot(
                        cueBall = cueBall,
                        cueBallStop = cueStop,
                        remainingLayout = remainingLayout,
                        nextTargets = nextTargets,
                        aimErrorStdRad = playerProfile.aimErrorStdRad,
                    )
                } else {
                    null
                }

                val baseWithCenterRaw = baseScore * (0.90 + 0.10 * centerCloseness)
                val baseWithCenter = if (nextTargets.isEmpty()) {
                    val terminalEase = (
                        1.0 -
                            0.015 * abs(speedUnits - 4.0) -
                            (if (spin == CueBallSpinPreset.DRAW) 0.02 else 0.0)
                        ).coerceIn(0.85, 1.0)
                    baseWithCenterRaw * terminalEase
                } else {
                    baseWithCenterRaw
                }
                val positionBonus = if (bestNext != null) {
                    if (isSettingUpFinalBall) {
                        val minRailDist = minOf(
                            cueStop.x,
                            table.lengthMeters - cueStop.x,
                            cueStop.y,
                            table.widthMeters - cueStop.y,
                        )
                        val comfortThreshold = 2.2 * cueBall.radiusMeters
                        val comfort = if (minRailDist >= comfortThreshold) {
                            1.0
                        } else {
                            0.80 + 0.20 * (minRailDist / comfortThreshold).coerceIn(0.0, 1.0)
                        }
                        val ease = (
                            1.0 -
                                0.05 * ((chosenPath.points.size - 2).coerceAtLeast(0) - 1).coerceAtLeast(0) -
                                0.02 * abs(speedUnits - 4.0) -
                                (if (spin == CueBallSpinPreset.DRAW) 0.04 else 0.0)
                            ).coerceIn(0.70, 1.0)
                        positionWeight * bestNext.score * comfort * ease
                    } else {
                        val controlEase = (
                            1.0 -
                                0.04 * ((chosenPath.points.size - 2).coerceAtLeast(0) - 1).coerceAtLeast(0) -
                                (if (spin == CueBallSpinPreset.DRAW) 0.025 else 0.0)
                            ).coerceIn(0.78, 1.0)
                        positionWeight * bestNext.score * controlEase
                    }
                } else {
                    0.0
                }
                val scratchFactor = if (scratches) ALL_SCRATCH_PENALTY_FACTOR else 1.0
                val drawPenalty = if (
                    spin == CueBallSpinPreset.DRAW &&
                    playerProfile.maxEffectiveDrawRatio < LOW_DRAW_RATIO_THRESHOLD
                ) {
                    LOW_DRAW_SCORE_PENALTY
                } else {
                    0.0
                }
                val distanceSpinPenalty = when {
                    preImpact.state == SpinPhysics.ImpactSpinState.STOPPED_SHORT -> 0.35
                    spin == CueBallSpinPreset.DRAW && preImpact.impactSpinFactorK >= 0.0 -> 0.14
                    spin == CueBallSpinPreset.STUN && preImpact.impactSpinFactorK >= 0.70 -> 0.05
                    else -> 0.0
                }
                val candidateScore = ((baseWithCenter + positionBonus) * scratchFactor - drawPenalty - distanceSpinPenalty)
                    .coerceIn(1e-6, 1.0)

                val candidate = CandidateEvaluation(
                    spin = spin,
                    speedScale = scaleInt,
                    speedUnits = speedUnits,
                    speedMps = speedMps,
                    pathsAtSpeed = paths,
                    score = candidateScore,
                    scratches = scratches,
                    centerDistance = centerDistance,
                    nextTargetBallNumber = bestNext?.nextBallNumber,
                    nextTargetPositionLine = bestNext?.let { cueStop to it.nextGhostCenter },
                )

                if (best == null || isBetterCandidate(candidate, best)) {
                    best = candidate
                }
            }
        }

        return best
    }

    private fun isBetterCandidate(
        candidate: CandidateEvaluation,
        currentBest: CandidateEvaluation,
    ): Boolean {
        if (candidate.scratches != currentBest.scratches) return !candidate.scratches
        if (abs(candidate.score - currentBest.score) > 1e-9) return candidate.score > currentBest.score
        if (abs(candidate.centerDistance - currentBest.centerDistance) > 1e-9) {
            return candidate.centerDistance < currentBest.centerDistance
        }
        return abs(candidate.speedUnits - PREFERRED_DEFAULT_SPEED_UNITS) <
            abs(currentBest.speedUnits - PREFERRED_DEFAULT_SPEED_UNITS)
    }

    private data class NextShotEvaluation(
        val nextBallNumber: Int,
        val nextGhostCenter: Vec2,
        val score: Double,
    )

    private data class CandidateEvaluation(
        val spin: CueBallSpinPreset,
        val speedScale: Int,
        val speedUnits: Double,
        val speedMps: Double,
        val pathsAtSpeed: Map<CueBallSpinPreset, TrajectoryPath>,
        val score: Double,
        val scratches: Boolean,
        val centerDistance: Double,
        val nextTargetBallNumber: Int?,
        val nextTargetPositionLine: Pair<Vec2, Vec2>?,
    )
}

fun rankShots(
    layout: TableLayout,
    rulesState: GameRulesState = GameRulesState(),
    defaultCueSpeedMps: Double = 2.0,
    playerProfile: PlayerProfile = PlayerProfile(),
    cueSpeedScaleOverride: Int? = null,
    cueSpeedUnitsOverride: Double? = null,
): List<ShotRecommendation> = OneShotPlanner.rankShots(
    layout = layout,
    rulesState = rulesState,
    defaultCueSpeedMps = defaultCueSpeedMps,
    playerProfile = playerProfile,
    cueSpeedScaleOverride = cueSpeedScaleOverride,
    cueSpeedUnitsOverride = cueSpeedUnitsOverride,
)
