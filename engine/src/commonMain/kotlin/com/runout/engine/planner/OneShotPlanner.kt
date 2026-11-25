package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.CueBallPath
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TrajectoryPath
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Shot planner that enumerates legal target-ball and pocket combinations, filters blocked or impossible cuts (`> 80°`)
 * and off-table ghost balls, evaluates the stun, follow and draw presets across candidate speeds (or an explicit
 * override), and ranks each pot by the best of its strokes.
 *
 * A stroke's score, in `(0, 1]`, is a product of factors that are each at most 1, so it needs no clipping and two
 * different shots seldom tie:
 *
 * > score = P(make) x centre x position x scratch x draw x reach
 *
 * - **P(make)** is [ExecutionErrorModel.makeProbability] of the stroke (its speed and the preset's tip, with the other
 *   balls narrowing the window), floored at `1e-6` so that every playable pot keeps a rank. It is reported as
 *   [ShotRecommendation.makeProbability].
 * - **centre** is `0.90 + 0.10 x c`, `c` in `0..1` how close to the middle of the table the cue ball stops.
 * - **position** is `(1 - w) + w x v`: `v` in `0..1` the best stroke-free make probability
 *   ([HeatZoneGenerator.shotQuality]) of the next legal ball from where the cue ball stops, times a control factor
 *   for cushions and draw (and, when it sets up the game ball, for a rail-tight leave and the pace); `w` is
 *   [POSITION_BONUS_WEIGHT], or [FINAL_BALL_POSITION_BONUS_WEIGHT] when the next ball is the game ball. With no next
 *   ball (the game ball itself) it is a small ease factor for pace and draw instead.
 * - **scratch** is [ALL_SCRATCH_PENALTY_FACTOR] when the cue ball's path scratches; **draw** is
 *   `1 - `[LOW_DRAW_SCORE_PENALTY] for a draw stroke by a player with little draw; **reach** is `1 - 0.35` when the
 *   cue ball would stop short of the object ball.
 */
object OneShotPlanner {
    private val SPIN_CANDIDATES: List<CueBallSpinPreset> = listOf(
        CueBallSpinPreset.STUN,
        CueBallSpinPreset.FOLLOW,
        CueBallSpinPreset.DRAW,
    )

    private val CANDIDATE_SPEED_UNITS: List<Double> = listOf(2.5, 3.5, 4.5, 5.5, 7.0)

    private const val DEFAULT_CUE_SPEED_MPS: Double = 2.0
    private const val ALL_SCRATCH_PENALTY_FACTOR: Double = 0.15
    private const val POSITION_BONUS_WEIGHT: Double = 0.25
    private const val FINAL_BALL_POSITION_BONUS_WEIGHT: Double = 0.42
    private const val LOW_DRAW_RATIO_THRESHOLD: Double = 0.8
    private const val LOW_DRAW_SCORE_PENALTY: Double = 0.15
    private const val STOPPED_SHORT_PENALTY: Double = 0.35
    private const val PREFERRED_DEFAULT_SPEED_UNITS: Double = 4.5
    private const val MIN_MAKE_PROBABILITY: Double = 1e-6

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

                val isSettingUpFinalBall = nextTargets.size == 1 &&
                    nextTargets.first().number == rulesState.gameType.requiredEndBallNumber
                val bestCandidate = selectBestSpinAndSpeed(
                    cueBall = cueBall,
                    targetBall = targetBall,
                    pocket = pocket,
                    aim = aim,
                    layout = layout,
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
                    makeProbability = bestCandidate.makeProbability,
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

    /**
     * The legal balls once [pottedBall] has been potted from [remainingLayout]: none after the game ball; otherwise the
     * legal targets under the rules that follow the pot ([MultiShotPlanner.rulesAfterPotting]), so that on an open
     * 8-ball table the group of the potted ball is assigned and the 8 follows the last ball of that group.
     */
    internal fun nextLegalTargetsAfterPotting(
        remainingLayout: TableLayout,
        pottedBall: Ball,
        rulesState: GameRulesState,
    ): List<Ball> {
        if (pottedBall.number == rulesState.gameType.requiredEndBallNumber) return emptyList()
        return MultiShotPlanner.rulesAfterPotting(rulesState, pottedBall).legalTargetBalls(remainingLayout)
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

                val nextScore = HeatZoneGenerator.shotQuality(nextAim, nextPocket, aimErrorStdRad, remainingLayout.balls)
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
        targetBall: Ball,
        pocket: PocketSpec,
        aim: AimGeometry,
        layout: TableLayout,
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
            // Paths from the stroke: each preset's tip, the speed and spin it leaves the cue ball with at contact, and the
            // contact and slide that follow.
            val paths = CueBallPath.computePostImpactPathsForStroke(
                aim = aim,
                table = table,
                cueSpeedMps = speedMps,
            )

            for (spin in SPIN_CANDIDATES) {
                // The preset names the spin at the object ball. Only offer it
                // when some tip gives that state after this distance at this speed (a stop shot over a long
                // distance at a soft speed needs more backspin than a tip can give).
                val tip = SpinPhysics.tipForImpactState(
                    preset = spin,
                    cueSpeedMps = speedMps,
                    cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
                    table = table,
                ) ?: continue
                val chosenPath = paths.getValue(spin)
                val scratches = chosenPath.hitsScratchPocket != null
                val cueStop = chosenPath.points.lastOrNull() ?: aim.ghostBallCenter
                val centerCloseness = 1.0 - (cueStop.distanceTo(tableCenter) / halfDiagonal).coerceIn(0.0, 1.0)
                val centerDistance = cueStop.distanceTo(tableCenter)
                val preImpact = SpinPhysics.computePreImpactCueState(
                    cueSpeedMps = speedMps,
                    tip = tip,
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

                val make = ExecutionErrorModel.makeProbability(
                    table = table,
                    targetBall = targetBall,
                    cueBallRadiusMeters = cueBall.radiusMeters,
                    pocket = pocket,
                    aim = aim,
                    cueSpeedMps = speedMps,
                    tip = tip,
                    playerProfile = playerProfile,
                    otherBalls = layout.balls,
                ).coerceAtLeast(MIN_MAKE_PROBABILITY)
                val centreFactor = 0.90 + 0.10 * centerCloseness
                val positionValue = if (bestNext != null) {
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
                                0.05 * (chosenPath.cushionContacts - 1).coerceAtLeast(0) -
                                0.02 * abs(speedUnits - 4.0) -
                                (if (spin == CueBallSpinPreset.DRAW) 0.04 else 0.0)
                            ).coerceIn(0.70, 1.0)
                        bestNext.score * comfort * ease
                    } else {
                        val controlEase = (
                            1.0 -
                                0.04 * (chosenPath.cushionContacts - 1).coerceAtLeast(0) -
                                (if (spin == CueBallSpinPreset.DRAW) 0.025 else 0.0)
                            ).coerceIn(0.78, 1.0)
                        bestNext.score * controlEase
                    }
                } else {
                    0.0
                }
                val positionFactor = if (nextTargets.isEmpty()) {
                    // The game ball: no position to play for, only an easy pace and no draw.
                    (
                        1.0 -
                            0.015 * abs(speedUnits - 4.0) -
                            (if (spin == CueBallSpinPreset.DRAW) 0.02 else 0.0)
                        ).coerceIn(0.85, 1.0)
                } else {
                    (1.0 - positionWeight) + positionWeight * positionValue
                }
                val scratchFactor = if (scratches) ALL_SCRATCH_PENALTY_FACTOR else 1.0
                val drawFactor = if (
                    spin == CueBallSpinPreset.DRAW &&
                    playerProfile.maxEffectiveDrawRatio < LOW_DRAW_RATIO_THRESHOLD
                ) {
                    1.0 - LOW_DRAW_SCORE_PENALTY
                } else {
                    1.0
                }
                val reachFactor = if (preImpact.state == SpinPhysics.ImpactSpinState.STOPPED_SHORT) {
                    1.0 - STOPPED_SHORT_PENALTY
                } else {
                    1.0
                }
                val candidateScore = make * centreFactor * positionFactor * scratchFactor * drawFactor * reachFactor

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
                    makeProbability = make,
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
        val makeProbability: Double,
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
