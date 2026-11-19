package com.runout.engine.snooker

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.CueBallTrajectory
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.SpinCompensatedAim
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.ExecutionErrorModel
import com.runout.engine.planner.HeatZoneGenerator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Standard snooker ball categories and their WPBSA point values.
 */
enum class SnookerBallType(val pointValue: Int) {
    RED(1),
    YELLOW(2),
    GREEN(3),
    BROWN(4),
    BLUE(5),
    PINK(6),
    BLACK(7),
}

/**
 * Current frame score, break count, and table-state flags for WPBSA snooker scoring and tactical mode selection.
 */
data class SnookerScoreState(
    val playerScore: Int = 0,
    val opponentScore: Int = 0,
    val currentBreakPoints: Int = 0,
    val redsRemaining: Int = 15,
    val onColourAfterRed: Boolean = false,
    val lowestEndgameColourValue: Int = 2,
) {
    val pointsRemainingOnTable: Int = if (redsRemaining > 0) {
        redsRemaining * 8 + (if (onColourAfterRed) 7 else 0) + 27
    } else {
        (if (onColourAfterRed) 7 else 0) + (lowestEndgameColourValue.coerceIn(2, 8)..7).sum()
    }

    val scoreDifferential: Int = playerScore - opponentScore

    val pointsAhead: Int = (playerScore - opponentScore).coerceAtLeast(0)

    val deficitPoints: Int = (opponentScore - playerScore).coerceAtLeast(0)

    val needsSnookers: Boolean = deficitPoints > pointsRemainingOnTable

    val snookersRequiredCount: Int = if (!needsSnookers) {
        0
    } else {
        ((deficitPoints - pointsRemainingOnTable + 3) / 4).coerceAtLeast(1)
    }

    val isFrameBallAvailable: Boolean =
        (playerScore + 7 >= opponentScore + pointsRemainingOnTable) && !needsSnookers
}

/**
 * Single shot inside a planned snooker break sequence, including colour re-spotting when reds remain.
 */
data class SnookerBreakStep(
    val stepNumber: Int,
    val ballType: SnookerBallType,
    val ballNumber: Int,
    val pointValue: Int,
    val pocketId: PocketId,
    val compensatedAim: SpinCompensatedAim,
    val recommendedSpeedScale: Int,
    val recommendedTip: TipOffset,
    val makeProbability: Double,
    val respottedToSpotMeters: Vec2?,
    val cueBallStopMeters: Vec2,
)

/**
 * Candidate snooker safety shot aiming to return the cue ball toward baulk and minimize opponent pot odds.
 */
data class SnookerSafetyRecommendation(
    val targetBallNumber: Int,
    val contactPointMeters: Vec2,
    val recommendedSpeedScale: Int,
    val recommendedTip: TipOffset,
    val cueBallTrajectory: CueBallTrajectory,
    val cueBallFinalMeters: Vec2,
    val distanceToBaulkLineMeters: Double,
    val opponentEstimatedPotProbability: Double,
    val safetyQualityScore: Double,
)

/**
 * Top-level snooker tactical posture derived from pot probability and frame score state.
 */
enum class SnookerTacticalMode {
    ATTACK_BREAK_BUILD,
    CONTAINMENT_SAFETY,
    PLAY_FOR_SNOOKERS,
}

/**
 * Combined tactical recommendation containing break-building plan, best baulk-return safety, and summary codes.
 */
data class SnookerTacticalAdvice(
    val recommendedMode: SnookerTacticalMode,
    val scoreState: SnookerScoreState,
    val breakSequence: List<SnookerBreakStep>,
    val expectedBreakPoints: Double,
    val bestSafety: SnookerSafetyRecommendation?,
    val tacticalSummaryCodes: List<String>,
)

/**
 * WPBSA snooker table spot coordinates and ball-number mappings in SI metres.
 */
object SnookerSpots {
    const val BAULK_LINE_X_METERS: Double = 0.737
    const val D_RADIUS_METERS: Double = 0.292

    private const val FULL_SNOOKER_LENGTH_METERS: Double = 3.569
    private const val FULL_SNOOKER_WIDTH_METERS: Double = 1.778
    private const val BLACK_SPOT_FROM_FOOT_CUSHION_METERS: Double = 0.324
    private const val RED_APEX_GAP_METERS: Double = 0.002

    /**
     * Returns the WPBSA spot coordinates in SI metres for [colour] on [table], scaling proportionally
     * when [table] differs from a standard `3.569 x 1.778 m` full-size snooker surface.
     */
    fun spotForColour(
        colour: SnookerBallType,
        table: TableSpec = TableSpec.fullSizeSnooker(),
    ): Vec2 {
        val scaleX = if (table.lengthMeters == FULL_SNOOKER_LENGTH_METERS) {
            1.0
        } else {
            table.lengthMeters / FULL_SNOOKER_LENGTH_METERS
        }
        val scaleY = if (table.widthMeters == FULL_SNOOKER_WIDTH_METERS) {
            1.0
        } else {
            table.widthMeters / FULL_SNOOKER_WIDTH_METERS
        }
        val baulkX = BAULK_LINE_X_METERS * scaleX
        val dRadius = D_RADIUS_METERS * scaleY
        val halfWidth = table.widthMeters * 0.5

        return when (colour) {
            SnookerBallType.BROWN -> Vec2(baulkX, halfWidth)
            SnookerBallType.YELLOW -> Vec2(baulkX, halfWidth + dRadius)
            SnookerBallType.GREEN -> Vec2(baulkX, halfWidth - dRadius)
            SnookerBallType.BLUE -> Vec2(table.lengthMeters * 0.5, halfWidth)
            SnookerBallType.PINK -> Vec2(table.lengthMeters * 0.75, halfWidth)
            SnookerBallType.BLACK -> Vec2(
                table.lengthMeters - BLACK_SPOT_FROM_FOOT_CUSHION_METERS * scaleX,
                halfWidth,
            )
            SnookerBallType.RED -> Vec2(
                table.lengthMeters * 0.75 + 2.0 * table.ballRadiusMeters + RED_APEX_GAP_METERS,
                halfWidth,
            )
        }
    }

    /**
     * Maps a layout ball number (`1` or `8..22` for reds, `2..7` for colours) to its [SnookerBallType].
     */
    fun ballTypeForNumber(ballNumber: Int): SnookerBallType? = when (ballNumber) {
        1, in 8..22 -> SnookerBallType.RED
        2 -> SnookerBallType.YELLOW
        3 -> SnookerBallType.GREEN
        4 -> SnookerBallType.BROWN
        5 -> SnookerBallType.BLUE
        6 -> SnookerBallType.PINK
        7 -> SnookerBallType.BLACK
        else -> null
    }

    internal fun baulkLineX(table: TableSpec): Double =
        if (table.lengthMeters == FULL_SNOOKER_LENGTH_METERS) {
            BAULK_LINE_X_METERS
        } else {
            BAULK_LINE_X_METERS * (table.lengthMeters / FULL_SNOOKER_LENGTH_METERS)
        }
}

/**
 * Evaluates snooker break-building lines (red-colour alternation with colour re-spotting and endgame clearance)
 * alongside baulk-return safety shots to advise attacking, safety containment, or playing for snookers.
 */
object SnookerStrategyPlanner {
    private const val ATTACK_PROBABILITY_THRESHOLD: Double = 0.52
    private const val MAX_POT_CUT_ANGLE_RAD: Double = 1.38
    private const val MAX_SAFETY_CUT_ANGLE_RAD: Double = 1.43
    private const val MAX_SCRATCH_PROBABILITY: Double = 0.45
    private const val BEAM_WIDTH: Int = 8
    private const val RESPOT_CLEARANCE_MARGIN_METERS: Double = 0.001

    private val POT_SPEED_SCALES: List<Int> = listOf(3, 5, 7)
    private val POT_SPIN_PRESETS: List<CueBallSpinPreset> = listOf(
        CueBallSpinPreset.STUN,
        CueBallSpinPreset.FOLLOW,
        CueBallSpinPreset.DRAW,
    )

    private val SAFETY_CUT_ANGLES_RAD: List<Double> = listOf(
        -72.0, -56.0, -40.0, -24.0, -12.0, 0.0, 12.0, 24.0, 40.0, 56.0, 72.0,
    ).map { it * PI / 180.0 }
    private val SAFETY_SPEED_SCALES: List<Int> = listOf(2, 3, 4, 5, 7)
    private val SAFETY_TIPS: List<TipOffset> = listOf(
        TipOffset.fromPreset(CueBallSpinPreset.STUN),
        TipOffset.fromPreset(CueBallSpinPreset.FOLLOW),
        TipOffset.fromPreset(CueBallSpinPreset.DRAW),
    )

    /**
     * Evaluates [layout] under [scoreState] and [playerProfile], planning up to [horizonShots] (`1..5`)
     * alternating break-building shots and finding the strongest baulk-return safety option.
     */
    fun evaluateSnookerPosition(
        layout: TableLayout,
        scoreState: SnookerScoreState = SnookerScoreState(),
        playerProfile: PlayerProfile = PlayerProfile(),
        horizonShots: Int = 3,
    ): SnookerTacticalAdvice {
        val cueBall = layout.cueBall()
        if (cueBall == null || layout.objectBalls().isEmpty()) {
            val mode = if (scoreState.needsSnookers) {
                SnookerTacticalMode.PLAY_FOR_SNOOKERS
            } else {
                SnookerTacticalMode.CONTAINMENT_SAFETY
            }
            return SnookerTacticalAdvice(
                recommendedMode = mode,
                scoreState = scoreState,
                breakSequence = emptyList(),
                expectedBreakPoints = 0.0,
                bestSafety = null,
                tacticalSummaryCodes = buildSummaryCodes(
                    mode = mode,
                    scoreState = scoreState,
                    breakSequence = emptyList(),
                    bestSafety = null,
                    table = layout.table,
                ),
            )
        }

        val safeHorizon = horizonShots.coerceIn(1, 5)
        val breakSequence = planBreakSequence(
            initialLayout = layout,
            initialScoreState = scoreState,
            playerProfile = playerProfile,
            horizonShots = safeHorizon,
        )
        val expectedBreakPoints = computeExpectedBreakPoints(breakSequence)
        val bestSafety = findBestSafety(
            layout = layout,
            scoreState = scoreState,
            playerProfile = playerProfile,
        )

        val firstMakeProb = breakSequence.firstOrNull()?.makeProbability ?: 0.0
        val recommendedMode = when {
            scoreState.needsSnookers -> SnookerTacticalMode.PLAY_FOR_SNOOKERS
            breakSequence.isNotEmpty() && firstMakeProb >= ATTACK_PROBABILITY_THRESHOLD ->
                SnookerTacticalMode.ATTACK_BREAK_BUILD
            else -> SnookerTacticalMode.CONTAINMENT_SAFETY
        }

        return SnookerTacticalAdvice(
            recommendedMode = recommendedMode,
            scoreState = scoreState,
            breakSequence = breakSequence,
            expectedBreakPoints = expectedBreakPoints,
            bestSafety = bestSafety,
            tacticalSummaryCodes = buildSummaryCodes(
                mode = recommendedMode,
                scoreState = scoreState,
                breakSequence = breakSequence,
                bestSafety = bestSafety,
                table = layout.table,
            ),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Break-building beam search
    // ---------------------------------------------------------------------------------------------

    private data class BreakSearchNode(
        val layout: TableLayout,
        val scoreState: SnookerScoreState,
        val steps: List<SnookerBreakStep>,
        val cumulativeMakeProbability: Double,
        val expectedPointsSoFar: Double,
        val leaveBonus: Double,
        val terminal: Boolean,
    ) {
        val firstStepAttackReady: Boolean
            get() = (steps.firstOrNull()?.makeProbability ?: 0.0) >= ATTACK_PROBABILITY_THRESHOLD

        val rankingScore: Double
            get() = expectedPointsSoFar + leaveBonus
    }

    private fun planBreakSequence(
        initialLayout: TableLayout,
        initialScoreState: SnookerScoreState,
        playerProfile: PlayerProfile,
        horizonShots: Int,
    ): List<SnookerBreakStep> {
        val root = BreakSearchNode(
            layout = initialLayout,
            scoreState = initialScoreState,
            steps = emptyList(),
            cumulativeMakeProbability = 1.0,
            expectedPointsSoFar = 0.0,
            leaveBonus = 0.0,
            terminal = false,
        )

        val completed = mutableListOf<BreakSearchNode>()
        var beam = listOf(root)

        for (ply in 1..horizonShots) {
            val candidates = mutableListOf<BreakSearchNode>()
            for (node in beam) {
                val expanded = expandBreakNode(node, ply, playerProfile)
                if (expanded.isEmpty()) {
                    if (node.steps.isNotEmpty()) completed += node
                } else {
                    candidates += expanded
                }
            }
            if (candidates.isEmpty()) {
                beam = emptyList()
                break
            }
            val sorted = candidates.sortedWith(
                compareByDescending<BreakSearchNode> { it.firstStepAttackReady }
                    .thenByDescending { it.rankingScore }
                    .thenByDescending { it.cumulativeMakeProbability }
                    .thenBy { it.steps.firstOrNull()?.ballNumber ?: 0 },
            )
            val survivors = sorted.take(BEAM_WIDTH)
            completed += survivors.filter { it.terminal }
            beam = survivors.filter { !it.terminal }
        }
        completed += beam

        val bestNode = completed
            .filter { it.steps.isNotEmpty() }
            .maxWithOrNull(
                compareBy<BreakSearchNode> { it.firstStepAttackReady }
                    .thenBy { it.rankingScore }
                    .thenBy { it.cumulativeMakeProbability }
                    .thenByDescending { it.steps.firstOrNull()?.ballNumber ?: 0 },
            )
        return bestNode?.steps ?: emptyList()
    }

    private fun expandBreakNode(
        node: BreakSearchNode,
        ply: Int,
        playerProfile: PlayerProfile,
    ): List<BreakSearchNode> {
        val cue = node.layout.cueBall() ?: return emptyList()
        val table = node.layout.table
        val targets = legalTargetsForState(node.layout, node.scoreState)
        if (targets.isEmpty()) return emptyList()

        val children = mutableListOf<BreakSearchNode>()
        for (target in targets) {
            val ballType = SnookerSpots.ballTypeForNumber(target.number) ?: continue
            for (pocket in table.pockets) {
                val aim = GhostBallAiming.computeAimGeometry(
                    cueBall = cue,
                    objectBall = target,
                    pocket = pocket,
                    otherBalls = node.layout.balls,
                    table = table,
                )
                if (!aim.isShotPlayable || abs(aim.cutAngleRadians) > MAX_POT_CUT_ANGLE_RAD) continue

                var bestForPair: BreakSearchNode? = null
                for (scale in POT_SPEED_SCALES) {
                    val cueSpeed = playerProfile.speedForScale(scale)
                    for (preset in POT_SPIN_PRESETS) {
                        val tip = TipOffset.fromPreset(preset)
                        val candidate = evaluatePotCandidate(
                            node = node,
                            ply = ply,
                            cue = cue,
                            target = target,
                            ballType = ballType,
                            pocket = pocket,
                            aim = aim,
                            scale = scale,
                            cueSpeed = cueSpeed,
                            tip = tip,
                            playerProfile = playerProfile,
                        ) ?: continue

                        val current = bestForPair
                        if (
                            current == null ||
                            candidate.rankingScore > current.rankingScore
                        ) {
                            bestForPair = candidate
                        }
                    }
                }
                if (bestForPair != null) {
                    children += bestForPair
                }
            }
        }
        return children
    }

    private fun evaluatePotCandidate(
        node: BreakSearchNode,
        ply: Int,
        cue: Ball,
        target: Ball,
        ballType: SnookerBallType,
        pocket: PocketSpec,
        aim: AimGeometry,
        scale: Int,
        cueSpeed: Double,
        tip: TipOffset,
        playerProfile: PlayerProfile,
    ): BreakSearchNode? {
        val table = node.layout.table
        val compensatedAim = SpinPhysics.solveCompensatedAim(
            geometricAim = aim,
            cueBallRadiusMeters = cue.radiusMeters,
            objectBallRadiusMeters = target.radiusMeters,
            cueSpeedMps = cueSpeed,
            tip = tip,
            shaftType = playerProfile.shaftType,
            table = table,
        ) ?: return null

        val uncertainty = ExecutionErrorModel.evaluateShotUncertainty(
            layout = node.layout,
            targetBall = target,
            pocket = pocket,
            aim = compensatedAim.geometricAim,
            cueSpeedMps = cueSpeed,
            tip = tip,
            playerProfile = playerProfile,
        )
        if (uncertainty.scratchProbability >= MAX_SCRATCH_PROBABILITY) return null

        val cueStop = clampToPlayableBounds(uncertainty.meanLandingPosition, table, cue.radiusMeters)
        val newMakeCumulative = node.cumulativeMakeProbability * uncertainty.makeProbability
        val stepExpectedPoints = newMakeCumulative * ballType.pointValue

        val advanced = advanceBreakState(
            layout = node.layout,
            scoreState = node.scoreState,
            pottedBall = target,
            ballType = ballType,
            cueStop = cueStop,
        )

        val nextTargets = legalTargetsForState(advanced.layout, advanced.scoreState)
        val terminal = nextTargets.isEmpty()
        val leaveBonus = if (terminal) {
            0.0
        } else {
            val nextExpectedValue = bestNextLeaveExpectedPoints(
                layout = advanced.layout,
                cuePosition = cueStop,
                cueRadius = cue.radiusMeters,
                nextTargets = nextTargets,
                playerProfile = playerProfile,
            )
            newMakeCumulative * 0.65 * nextExpectedValue
        }

        val step = SnookerBreakStep(
            stepNumber = ply,
            ballType = ballType,
            ballNumber = target.number,
            pointValue = ballType.pointValue,
            pocketId = pocket.id,
            compensatedAim = compensatedAim,
            recommendedSpeedScale = scale,
            recommendedTip = tip,
            makeProbability = uncertainty.makeProbability,
            respottedToSpotMeters = advanced.respottedSpot,
            cueBallStopMeters = cueStop,
        )

        return BreakSearchNode(
            layout = advanced.layout,
            scoreState = advanced.scoreState,
            steps = node.steps + step,
            cumulativeMakeProbability = newMakeCumulative,
            expectedPointsSoFar = node.expectedPointsSoFar + stepExpectedPoints,
            leaveBonus = leaveBonus,
            terminal = terminal,
        )
    }

    private data class AdvancedBreakState(
        val layout: TableLayout,
        val scoreState: SnookerScoreState,
        val respottedSpot: Vec2?,
    )

    private fun advanceBreakState(
        layout: TableLayout,
        scoreState: SnookerScoreState,
        pottedBall: Ball,
        ballType: SnookerBallType,
        cueStop: Vec2,
    ): AdvancedBreakState {
        val table = layout.table
        val remainingWithMovedCue = layout.balls
            .filter { it.number != pottedBall.number }
            .map { ball -> if (ball.isCueBall) ball.copy(position = cueStop) else ball }

        return when {
            scoreState.onColourAfterRed -> {
                val respot = findRespotPosition(
                    colour = ballType,
                    table = table,
                    occupiedBalls = remainingWithMovedCue,
                    ballRadius = pottedBall.radiusMeters,
                )
                val updatedBalls = remainingWithMovedCue + pottedBall.copy(position = respot)
                AdvancedBreakState(
                    layout = TableLayout(table, updatedBalls),
                    scoreState = scoreState.copy(
                        playerScore = scoreState.playerScore + ballType.pointValue,
                        currentBreakPoints = scoreState.currentBreakPoints + ballType.pointValue,
                        onColourAfterRed = false,
                    ),
                    respottedSpot = respot,
                )
            }

            scoreState.redsRemaining > 0 -> {
                AdvancedBreakState(
                    layout = TableLayout(table, remainingWithMovedCue),
                    scoreState = scoreState.copy(
                        playerScore = scoreState.playerScore + ballType.pointValue,
                        currentBreakPoints = scoreState.currentBreakPoints + ballType.pointValue,
                        redsRemaining = (scoreState.redsRemaining - 1).coerceAtLeast(0),
                        onColourAfterRed = true,
                    ),
                    respottedSpot = null,
                )
            }

            else -> {
                AdvancedBreakState(
                    layout = TableLayout(table, remainingWithMovedCue),
                    scoreState = scoreState.copy(
                        playerScore = scoreState.playerScore + ballType.pointValue,
                        currentBreakPoints = scoreState.currentBreakPoints + ballType.pointValue,
                        lowestEndgameColourValue = scoreState.lowestEndgameColourValue + 1,
                    ),
                    respottedSpot = null,
                )
            }
        }
    }

    private fun bestNextLeaveExpectedPoints(
        layout: TableLayout,
        cuePosition: Vec2,
        cueRadius: Double,
        nextTargets: List<Ball>,
        playerProfile: PlayerProfile,
    ): Double {
        val overlapsAny = layout.balls.any {
            !it.isCueBall && it.position.distanceTo(cuePosition) < it.radiusMeters + cueRadius + 0.001
        }
        if (overlapsAny) return 0.0

        val virtualCue = Ball(number = 0, position = cuePosition, radiusMeters = cueRadius)
        val table = layout.table
        var bestValue = 0.0
        for (target in nextTargets) {
            val type = SnookerSpots.ballTypeForNumber(target.number) ?: continue
            for (pocket in table.pockets) {
                val aim = GhostBallAiming.computeAimGeometry(
                    cueBall = virtualCue,
                    objectBall = target,
                    pocket = pocket,
                    otherBalls = layout.balls,
                    table = table,
                )
                if (!aim.isShotPlayable || abs(aim.cutAngleRadians) > MAX_POT_CUT_ANGLE_RAD) continue
                val quality = HeatZoneGenerator.shotQuality(aim, pocket, playerProfile.aimErrorStdRad)
                val value = quality * type.pointValue
                if (value > bestValue) bestValue = value
            }
        }
        return bestValue
    }

    private fun computeExpectedBreakPoints(steps: List<SnookerBreakStep>): Double {
        var survival = 1.0
        var total = 0.0
        for (step in steps) {
            survival *= step.makeProbability
            total += survival * step.pointValue
        }
        return total
    }

    // ---------------------------------------------------------------------------------------------
    // Legal targets & re-spotting
    // ---------------------------------------------------------------------------------------------

    private fun legalTargetsForState(
        layout: TableLayout,
        scoreState: SnookerScoreState,
    ): List<Ball> {
        val objectBalls = layout.objectBalls().sortedBy { it.number }
        if (objectBalls.isEmpty()) return emptyList()

        return when {
            scoreState.onColourAfterRed -> objectBalls.filter { ball ->
                val type = SnookerSpots.ballTypeForNumber(ball.number)
                type != null && type != SnookerBallType.RED
            }

            scoreState.redsRemaining > 0 -> objectBalls.filter { ball ->
                SnookerSpots.ballTypeForNumber(ball.number) == SnookerBallType.RED
            }

            else -> {
                val targetValue = scoreState.lowestEndgameColourValue
                if (targetValue !in 2..7) {
                    emptyList()
                } else {
                    objectBalls.filter { ball ->
                        SnookerSpots.ballTypeForNumber(ball.number)?.pointValue == targetValue
                    }
                }
            }
        }
    }

    private fun findRespotPosition(
        colour: SnookerBallType,
        table: TableSpec,
        occupiedBalls: List<Ball>,
        ballRadius: Double,
    ): Vec2 {
        val ownSpot = SnookerSpots.spotForColour(colour, table)
        if (isPositionClear(ownSpot, table, occupiedBalls, ballRadius)) return ownSpot

        val centerY = table.widthMeters * 0.5
        val minX = ballRadius
        val maxX = (table.lengthMeters - ballRadius).coerceAtLeast(minX)
        val step = (ballRadius * 0.25).coerceAtLeast(0.004)
        val maxOffset = table.lengthMeters
        var offset = step
        while (offset <= maxOffset) {
            val forwardX = ownSpot.x + offset
            if (forwardX <= maxX) {
                val candidate = Vec2(forwardX, centerY)
                if (isPositionClear(candidate, table, occupiedBalls, ballRadius)) return candidate
            }
            val backwardX = ownSpot.x - offset
            if (backwardX >= minX) {
                val candidate = Vec2(backwardX, centerY)
                if (isPositionClear(candidate, table, occupiedBalls, ballRadius)) return candidate
            }
            offset += step
        }

        val yStep = (ballRadius * 0.5).coerceAtLeast(0.008)
        var dy = yStep
        while (dy < table.widthMeters * 0.5) {
            for (sign in listOf(1.0, -1.0)) {
                val candidate = Vec2(ownSpot.x.coerceIn(minX, maxX), centerY + sign * dy)
                if (isPositionClear(candidate, table, occupiedBalls, ballRadius)) return candidate
            }
            dy += yStep
        }

        return clampToPlayableBounds(ownSpot, table, ballRadius)
    }

    private fun isPositionClear(
        pos: Vec2,
        table: TableSpec,
        occupiedBalls: List<Ball>,
        ballRadius: Double,
    ): Boolean {
        if (!table.isWithinPlayableBounds(pos, ballRadius)) return false
        for (ball in occupiedBalls) {
            val minDist = ball.radiusMeters + ballRadius + RESPOT_CLEARANCE_MARGIN_METERS
            if (pos.distanceTo(ball.position) < minDist) return false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Baulk-return safety evaluator
    // ---------------------------------------------------------------------------------------------

    private fun findBestSafety(
        layout: TableLayout,
        scoreState: SnookerScoreState,
        playerProfile: PlayerProfile,
    ): SnookerSafetyRecommendation? {
        val cue = layout.cueBall() ?: return null
        val legalTargets = legalTargetsForState(layout, scoreState)
        if (legalTargets.isEmpty()) return null

        val table = layout.table
        val baulkX = SnookerSpots.baulkLineX(table)
        val opponentAfterSafetyState = opponentStateAfterSafety(scoreState)
        val opponentAfterTargetsBase = legalTargetsForState(layout, opponentAfterSafetyState)

        var bestDirect: SnookerSafetyRecommendation? = null
        var bestFallback: SnookerSafetyRecommendation? = null

        for (target in legalTargets) {
            val toTarget = (target.position - cue.position)
            if (toTarget.lengthSquared() < 1e-12) continue
            val baseDir = toTarget.normalized()
            val obstacles = layout.balls.filter { it.number != cue.number && it.number != target.number }

            for (angleOffset in SAFETY_CUT_ANGLES_RAD) {
                val obDir = baseDir.rotate(angleOffset).normalized()
                val contactDist = cue.radiusMeters + target.radiusMeters
                val ghostCenter = target.position - obDir * contactDist
                if (!table.isWithinPlayableBounds(ghostCenter, cue.radiusMeters)) continue

                val cueToGhost = ghostCenter - cue.position
                val cueToGhostDist = cueToGhost.length()
                if (cueToGhostDist < 1e-6) continue
                val aimDir = cueToGhost / cueToGhostDist
                val cutAngle = aimDir.angleTo(obDir)
                if (abs(cutAngle) > MAX_SAFETY_CUT_ANGLE_RAD) continue

                val isApproachClear = GhostBallAiming.isSegmentClear(
                    start = cue.position,
                    end = ghostCenter,
                    movingRadius = cue.radiusMeters,
                    obstacles = obstacles,
                )
                if (!isApproachClear) continue

                val contactPoint = ghostCenter + obDir * cue.radiusMeters
                val syntheticAim = AimGeometry(
                    cueBallPos = cue.position,
                    objectBallPos = target.position,
                    pocketAimPos = target.position + obDir * 0.5,
                    ghostBallCenter = ghostCenter,
                    aimDirection = aimDir,
                    objectBallDirection = obDir,
                    cueToGhostDistanceMeters = cueToGhostDist,
                    objectToPocketDistanceMeters = 0.5,
                    cutAngleRadians = cutAngle,
                    isCutPossible = true,
                    isCueToGhostClear = true,
                    isObjectToPocketClear = true,
                )

                for (scale in SAFETY_SPEED_SCALES) {
                    val cueSpeed = playerProfile.speedForScale(scale)
                    val targetMotion = estimateTargetBallPostContact(
                        target = target,
                        direction = obDir,
                        cueSpeedMps = cueSpeed,
                        cutAngleRad = cutAngle,
                        table = table,
                        obstacles = obstacles,
                    )
                    val layoutAfterTarget = updateTargetPosition(layout, target, targetMotion.finalPosition)
                    val opponentTargets = if (opponentAfterTargetsBase.any { it.number == target.number }) {
                        legalTargetsForState(layoutAfterTarget, opponentAfterSafetyState)
                    } else {
                        opponentAfterTargetsBase
                    }

                    for (tip in SAFETY_TIPS) {
                        val trajectory = SpinPhysics.traceCueBallPathWithSpin(
                            aim = syntheticAim,
                            cueSpeedMps = cueSpeed,
                            tip = tip,
                            table = table,
                            ballRadiusMeters = cue.radiusMeters,
                            maxBounces = 3,
                        )
                        val scratches = trajectory.hitsScratchPocket != null
                        val cueFinal = clampToPlayableBounds(
                            trajectory.points.lastOrNull() ?: ghostCenter,
                            table,
                            cue.radiusMeters,
                        )
                        val returnClear = isTrajectoryClearOfBalls(
                            points = trajectory.points,
                            cueRadius = cue.radiusMeters,
                            obstacles = layoutAfterTarget.balls.filter { !it.isCueBall && it.number != target.number },
                        )
                        val oppPotProb = estimateOpponentBestPotProbability(
                            cueBallPos = cueFinal,
                            cueRadius = cue.radiusMeters,
                            layoutAfterShot = layoutAfterTarget,
                            opponentTargets = opponentTargets,
                            opponentAimErrorRad = playerProfile.aimErrorStdRad,
                        )

                        val score = scoreSafetyCandidate(
                            cueFinal = cueFinal,
                            targetFinal = targetMotion.finalPosition,
                            targetPocketed = targetMotion.pocketed,
                            oppPotProb = oppPotProb,
                            returnClear = returnClear,
                            scratches = scratches,
                            baulkX = baulkX,
                            table = table,
                        )

                        val recommendation = SnookerSafetyRecommendation(
                            targetBallNumber = target.number,
                            contactPointMeters = contactPoint,
                            recommendedSpeedScale = scale,
                            recommendedTip = tip,
                            cueBallTrajectory = trajectory,
                            cueBallFinalMeters = cueFinal,
                            distanceToBaulkLineMeters = abs(cueFinal.x - baulkX),
                            opponentEstimatedPotProbability = oppPotProb,
                            safetyQualityScore = score,
                        )

                        if (!scratches && returnClear) {
                            if (bestDirect == null || recommendation.safetyQualityScore > bestDirect.safetyQualityScore) {
                                bestDirect = recommendation
                            }
                        } else if (!scratches) {
                            if (bestFallback == null || recommendation.safetyQualityScore > bestFallback.safetyQualityScore) {
                                bestFallback = recommendation
                            }
                        }
                    }
                }
            }
        }

        if (bestDirect != null) return bestDirect
        if (bestFallback != null) return bestFallback
        return findCushionEscapeSafety(layout, legalTargets, opponentAfterSafetyState, playerProfile, baulkX)
    }

    private fun findCushionEscapeSafety(
        layout: TableLayout,
        legalTargets: List<Ball>,
        opponentAfterSafetyState: SnookerScoreState,
        playerProfile: PlayerProfile,
        baulkX: Double,
    ): SnookerSafetyRecommendation? {
        val cue = layout.cueBall() ?: return null
        val table = layout.table
        val r = cue.radiusMeters
        val minX = r
        val maxX = (table.lengthMeters - r).coerceAtLeast(r)
        val minY = r
        val maxY = (table.widthMeters - r).coerceAtLeast(r)

        var best: SnookerSafetyRecommendation? = null
        val tip = TipOffset.fromPreset(CueBallSpinPreset.STUN)

        for (target in legalTargets) {
            val obstacles = layout.balls.filter { it.number != cue.number && it.number != target.number }
            val railMirrors = listOf(
                Vec2(2.0 * minX - target.position.x, target.position.y),
                Vec2(2.0 * maxX - target.position.x, target.position.y),
                Vec2(target.position.x, 2.0 * minY - target.position.y),
                Vec2(target.position.x, 2.0 * maxY - target.position.y),
            )
            for ((railIndex, mirrorTarget) in railMirrors.withIndex()) {
                val bouncePoint = intersectMirrorLineWithRail(cue.position, mirrorTarget, railIndex, minX, maxX, minY, maxY)
                    ?: continue
                if (!table.isWithinPlayableBounds(bouncePoint, r)) continue
                val approachDir = (target.position - bouncePoint).normalized()
                if (approachDir.lengthSquared() < 1e-12) continue
                val ghostCenter = target.position - approachDir * (cue.radiusMeters + target.radiusMeters)
                if (!table.isWithinPlayableBounds(ghostCenter, r)) continue

                val leg1Clear = GhostBallAiming.isSegmentClear(cue.position, bouncePoint, r, layout.objectBalls())
                val leg2Clear = GhostBallAiming.isSegmentClear(bouncePoint, ghostCenter, r, obstacles)
                if (!leg1Clear || !leg2Clear) continue

                val cueFinal = clampToPlayableBounds(
                    ghostCenter + approachDir.rotate(PI * 0.5) * 0.35,
                    table,
                    r,
                )
                val trajectory = CueBallTrajectory(
                    spin = CueBallSpinPreset.STUN,
                    points = listOf(cue.position, bouncePoint, ghostCenter, cueFinal),
                    initialDeflectionAngleRad = 0.0,
                    hitsScratchPocket = null,
                )
                val opponentTargets = legalTargetsForState(layout, opponentAfterSafetyState)
                val oppPotProb = estimateOpponentBestPotProbability(
                    cueBallPos = cueFinal,
                    cueRadius = r,
                    layoutAfterShot = layout,
                    opponentTargets = opponentTargets,
                    opponentAimErrorRad = playerProfile.aimErrorStdRad,
                )
                val score = scoreSafetyCandidate(
                    cueFinal = cueFinal,
                    targetFinal = target.position,
                    targetPocketed = false,
                    oppPotProb = oppPotProb,
                    returnClear = true,
                    scratches = false,
                    baulkX = baulkX,
                    table = table,
                )
                val rec = SnookerSafetyRecommendation(
                    targetBallNumber = target.number,
                    contactPointMeters = ghostCenter + approachDir * cue.radiusMeters,
                    recommendedSpeedScale = 4,
                    recommendedTip = tip,
                    cueBallTrajectory = trajectory,
                    cueBallFinalMeters = cueFinal,
                    distanceToBaulkLineMeters = abs(cueFinal.x - baulkX),
                    opponentEstimatedPotProbability = oppPotProb,
                    safetyQualityScore = score,
                )
                if (best == null || rec.safetyQualityScore > best.safetyQualityScore) {
                    best = rec
                }
            }
        }
        return best
    }

    private fun intersectMirrorLineWithRail(
        start: Vec2,
        end: Vec2,
        railIndex: Int,
        minX: Double,
        maxX: Double,
        minY: Double,
        maxY: Double,
    ): Vec2? {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val t = when (railIndex) {
            0 -> if (abs(dx) > 1e-9) (minX - start.x) / dx else -1.0
            1 -> if (abs(dx) > 1e-9) (maxX - start.x) / dx else -1.0
            2 -> if (abs(dy) > 1e-9) (minY - start.y) / dy else -1.0
            else -> if (abs(dy) > 1e-9) (maxY - start.y) / dy else -1.0
        }
        if (t <= 0.01 || t >= 0.99) return null
        return Vec2(start.x + t * dx, start.y + t * dy)
    }

    private fun opponentStateAfterSafety(scoreState: SnookerScoreState): SnookerScoreState =
        scoreState.copy(onColourAfterRed = false)

    private data class TargetPostContact(
        val finalPosition: Vec2,
        val pocketed: Boolean,
    )

    private fun estimateTargetBallPostContact(
        target: Ball,
        direction: Vec2,
        cueSpeedMps: Double,
        cutAngleRad: Double,
        table: TableSpec,
        obstacles: List<Ball>,
    ): TargetPostContact {
        val vObj = (cueSpeedMps * cos(abs(cutAngleRad)).coerceAtLeast(0.05) * 0.96).coerceAtLeast(0.0)
        val muR = table.cloth.rollingResistanceMuR.coerceAtLeast(1e-4)
        val muS = table.cloth.slidingFrictionMuS.coerceAtLeast(1e-4)
        val g = MotionPhysics.GRAVITY_M_S2
        val slideDist = (12.0 * vObj * vObj) / (49.0 * muS * g)
        val rollDist = MotionPhysics.rollingStoppingDistanceMeters(MotionPhysics.stunRollingSpeed(vObj), muR, g)
        val totalDist = slideDist + rollDist

        val r = target.radiusMeters
        val minX = r
        val maxX = (table.lengthMeters - r).coerceAtLeast(r)
        val minY = r
        val maxY = (table.widthMeters - r).coerceAtLeast(r)

        val tx = when {
            direction.x > 1e-9 -> ((maxX - target.position.x) / direction.x).coerceAtLeast(0.0)
            direction.x < -1e-9 -> ((minX - target.position.x) / direction.x).coerceAtLeast(0.0)
            else -> Double.POSITIVE_INFINITY
        }
        val ty = when {
            direction.y > 1e-9 -> ((maxY - target.position.y) / direction.y).coerceAtLeast(0.0)
            direction.y < -1e-9 -> ((minY - target.position.y) / direction.y).coerceAtLeast(0.0)
            else -> Double.POSITIVE_INFINITY
        }
        val railDist = min(tx, ty)

        var maxFreeDist = totalDist
        for (obs in obstacles) {
            val rel = obs.position - target.position
            val proj = rel.dot(direction)
            if (proj <= 0.0 || proj >= maxFreeDist) continue
            val perpSq = rel.lengthSquared() - proj * proj
            val minSep = r + obs.radiusMeters
            if (perpSq < minSep * minSep) {
                val hitDist = (proj - sqrt(max(0.0, minSep * minSep - perpSq))).coerceAtLeast(0.0)
                if (hitDist < maxFreeDist) maxFreeDist = hitDist
            }
        }

        val travel = min(maxFreeDist, railDist)
        val segEnd = clampToPlayableBounds(target.position + direction * travel, table, r)
        val pocketed = table.pockets.any { pocket ->
            distancePointToSegment(pocket.center, target.position, segEnd) <= pocket.acceptanceRadiusMeters
        }
        if (pocketed) {
            return TargetPostContact(finalPosition = segEnd, pocketed = true)
        }

        if (maxFreeDist <= railDist + 1e-6 || travel < maxFreeDist - 1e-6) {
            return TargetPostContact(finalPosition = segEnd, pocketed = false)
        }

        val ec = table.cloth.cushionRestitutionEc
        val remaining = (maxFreeDist - railDist) * ec * ec
        val hitVert = abs(tx - railDist) <= 1e-6
        val hitHoriz = abs(ty - railDist) <= 1e-6
        val bouncedDir = Vec2(
            x = if (hitVert) -direction.x else direction.x,
            y = if (hitHoriz) -direction.y else direction.y,
        ).normalized()
        val afterBounce = clampToPlayableBounds(segEnd + bouncedDir * min(remaining, 0.8), table, r)
        return TargetPostContact(finalPosition = afterBounce, pocketed = false)
    }

    private fun updateTargetPosition(layout: TableLayout, target: Ball, newPos: Vec2): TableLayout =
        TableLayout(
            table = layout.table,
            balls = layout.balls.map { ball ->
                if (ball.number == target.number) ball.copy(position = newPos) else ball
            },
        )

    private fun isTrajectoryClearOfBalls(
        points: List<Vec2>,
        cueRadius: Double,
        obstacles: List<Ball>,
    ): Boolean {
        if (points.size < 2 || obstacles.isEmpty()) return true
        for (i in 0 until points.lastIndex) {
            if (!GhostBallAiming.isSegmentClear(points[i], points[i + 1], cueRadius, obstacles)) {
                return false
            }
        }
        return true
    }

    private fun estimateOpponentBestPotProbability(
        cueBallPos: Vec2,
        cueRadius: Double,
        layoutAfterShot: TableLayout,
        opponentTargets: List<Ball>,
        opponentAimErrorRad: Double,
    ): Double {
        if (opponentTargets.isEmpty()) return 0.0
        val table = layoutAfterShot.table
        val virtualCue = Ball(number = 0, position = cueBallPos, radiusMeters = cueRadius)
        val effectiveError = opponentAimErrorRad.coerceIn(0.005, 0.015)

        var bestPotProb = 0.0
        for (target in opponentTargets) {
            if (cueBallPos.distanceTo(target.position) < cueRadius + target.radiusMeters - 1e-4) continue
            for (pocket in table.pockets) {
                val aim = GhostBallAiming.computeAimGeometry(
                    cueBall = virtualCue,
                    objectBall = target,
                    pocket = pocket,
                    otherBalls = layoutAfterShot.balls,
                    table = table,
                )
                if (!aim.isShotPlayable || abs(aim.cutAngleRadians) > MAX_POT_CUT_ANGLE_RAD) continue
                val quality = HeatZoneGenerator.shotQuality(aim, pocket, effectiveError)
                if (quality > bestPotProb) bestPotProb = quality
            }
        }
        return bestPotProb.coerceIn(0.0, 1.0)
    }

    private fun scoreSafetyCandidate(
        cueFinal: Vec2,
        targetFinal: Vec2,
        targetPocketed: Boolean,
        oppPotProb: Double,
        returnClear: Boolean,
        scratches: Boolean,
        baulkX: Double,
        table: TableSpec,
    ): Double {
        val r = table.ballRadiusMeters
        val baulkSpan = (baulkX - r).coerceAtLeast(1e-4)
        val baulkProximity = if (cueFinal.x <= baulkX + 1e-6) {
            val depthFrac = (1.0 - (cueFinal.x - r) / baulkSpan).coerceIn(0.0, 1.0)
            0.70 + 0.30 * depthFrac
        } else {
            val outsideDist = cueFinal.x - baulkX
            0.45 * exp(-outsideDist / (table.lengthMeters * 0.35))
        }

        val containment = (1.0 - oppPotProb).coerceIn(0.0, 1.0)
        val targetStayBack = ((targetFinal.x - baulkX) / (table.lengthMeters - baulkX).coerceAtLeast(1e-4))
            .coerceIn(0.0, 1.0)
        val separation = (cueFinal.distanceTo(targetFinal) / table.lengthMeters).coerceIn(0.0, 1.0)

        val nearestPocketDist = table.pockets.minOfOrNull { it.center.distanceTo(targetFinal) } ?: 1.0
        val pocketDanger = ((r * 5.0 - nearestPocketDist) / (r * 5.0)).coerceIn(0.0, 1.0) * 0.18
        val unintendedPotPenalty = if (targetPocketed) 0.25 else 0.0
        val collisionPenalty = if (returnClear) 0.0 else 0.40
        val scratchPenalty = if (scratches) 0.50 else 0.0

        val raw = 0.42 * containment +
            0.34 * baulkProximity +
            0.14 * targetStayBack +
            0.10 * separation -
            pocketDanger -
            unintendedPotPenalty -
            collisionPenalty -
            scratchPenalty
        return raw.coerceIn(0.0, 1.0)
    }

    // ---------------------------------------------------------------------------------------------
    // Utilities
    // ---------------------------------------------------------------------------------------------

    private fun clampToPlayableBounds(pos: Vec2, table: TableSpec, radius: Double): Vec2 {
        val minX = radius
        val maxX = (table.lengthMeters - radius).coerceAtLeast(minX)
        val minY = radius
        val maxY = (table.widthMeters - radius).coerceAtLeast(minY)
        return Vec2(
            x = pos.x.coerceIn(minX, maxX),
            y = pos.y.coerceIn(minY, maxY),
        )
    }

    private fun distancePointToSegment(p: Vec2, a: Vec2, b: Vec2): Double {
        val ab = b - a
        val lenSq = ab.lengthSquared()
        if (lenSq < 1e-12) return p.distanceTo(a)
        val t = ((p - a).dot(ab) / lenSq).coerceIn(0.0, 1.0)
        val proj = a + ab * t
        return p.distanceTo(proj)
    }

    private fun buildSummaryCodes(
        mode: SnookerTacticalMode,
        scoreState: SnookerScoreState,
        breakSequence: List<SnookerBreakStep>,
        bestSafety: SnookerSafetyRecommendation?,
        table: TableSpec,
    ): List<String> {
        val codes = mutableListOf<String>()
        codes += when (mode) {
            SnookerTacticalMode.ATTACK_BREAK_BUILD -> "MODE_ATTACK_BREAK_BUILD"
            SnookerTacticalMode.CONTAINMENT_SAFETY -> "MODE_CONTAINMENT_SAFETY"
            SnookerTacticalMode.PLAY_FOR_SNOOKERS -> "MODE_PLAY_FOR_SNOOKERS"
        }

        if (scoreState.needsSnookers) {
            codes += "SNOOKERS_REQUIRED_${scoreState.snookersRequiredCount}"
        }
        if (scoreState.isFrameBallAvailable) {
            codes += "FRAME_BALL_AVAILABLE"
        }

        codes += when {
            scoreState.onColourAfterRed -> "ON_COLOUR_AFTER_RED"
            scoreState.redsRemaining > 0 -> "RED_COLOUR_ALTERNATION"
            else -> "ENDGAME_COLOURS"
        }

        if (breakSequence.any { it.respottedToSpotMeters != null }) {
            codes += "RESPOTTED_COLOUR"
        }
        if (breakSequence.isEmpty() || breakSequence.first().makeProbability < ATTACK_PROBABILITY_THRESHOLD) {
            codes += "NO_HIGH_PERCENTAGE_POT"
        }

        val baulkX = SnookerSpots.baulkLineX(table)
        if (bestSafety != null && bestSafety.cueBallFinalMeters.x <= baulkX + 1e-6) {
            codes += "BAULK_RETURN_SAFETY_AVAILABLE"
        }
        return codes
    }
}
