package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.SpinCompensatedAim
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One planned shot inside a [RunoutPlan]: the target and pocket, the spin-compensated aim, the recommended speed
 * (player scale `1..10` and SI m/s), the recommended tip contact point, the execution-error evaluation and the
 * score used for ranking.
 */
data class PlannedShotStep(
    val stepNumber: Int,
    val targetBall: Ball,
    val pocketId: PocketId,
    val pocketCenter: Vec2,
    val compensatedAim: SpinCompensatedAim,
    val recommendedSpeedScale: Int,
    val cueSpeedMps: Double,
    val recommendedTip: TipOffset,
    val uncertainty: ShotUncertaintyResult,
    val breakoutOpportunity: BreakoutOpportunity? = null,
    val stepScore: Double,
)

/**
 * A multi-shot position-play plan: the ordered shots, the probability of executing all of them, the ranking
 * score, the heat zone for the balls after the first shot, the problem balls on the initial layout and
 * plain-English explanations of the advice.
 */
data class RunoutPlan(
    val steps: List<PlannedShotStep>,
    val cumulativeRunoutProbability: Double,
    val expectedValueScore: Double,
    val nextBallHeatZone: HeatZoneMap?,
    val problemBalls: List<ProblemBallReport>,
    val explanationReasons: List<String>,
)

/**
 * Self-play comparison of the beam-search planner with a greedy one-shot baseline over a set of racks.
 */
data class SelfPlayBenchmarkResult(
    val rackCount: Int,
    val beamPlannerRunoutRate: Double,
    val greedyBaselineRunoutRate: Double,
    val relativeImprovementFraction: Double,
)

/**
 * Expectimax beam-search run-out planner.
 *
 * Every ply enumerates legal target balls, unobstructed pockets, player speed scales and cue-tip offsets, applies
 * the spin-compensated aim and the execution-error model, moves the cue ball to the mean landing position and
 * scores the result by the accumulated make probability times the heat-zone value of the leave for the next ball
 * (plus a bonus for cue-ball paths that break out a problem ball). Only the best [beamWidth] states survive each
 * ply.
 */
object MultiShotPlanner {
    private const val MAX_CUT_ANGLE_RAD: Double = 1.38
    private const val MAX_SCRATCH_PROBABILITY: Double = 0.45
    private const val LEAVE_FLOOR: Double = 0.05
    private const val BREAKOUT_BONUS_WITH_INSURANCE: Double = 0.15
    private const val BREAKOUT_BONUS_WITHOUT_INSURANCE: Double = 0.04
    private const val HEAT_COLS: Int = 24
    private const val HEAT_ROWS: Int = 12
    private const val MAX_RETURNED_PLANS: Int = 5
    private const val SELF_PLAY_MAX_SHOTS: Int = 4
    private const val SELF_PLAY_HORIZON: Int = 3
    private const val GREEDY_SPEED_SCALE: Int = 6
    private const val LANDING_CLEARANCE_FACTOR: Double = 2.0

    private val SPEED_SCALES: List<Int> = listOf(3, 5, 7, 8)

    private val DEEP_PLY_TIPS: List<TipOffset> = listOf(
        TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
        TipOffset(sideRatio = 0.0, verticalRatio = 0.35),
        TipOffset(sideRatio = 0.0, verticalRatio = -0.38),
        TipOffset(sideRatio = -0.30, verticalRatio = 0.35),
        TipOffset(sideRatio = 0.30, verticalRatio = 0.35),
    )

    fun planRunout(
        layout: TableLayout,
        rulesState: GameRulesState = GameRulesState(),
        playerProfile: PlayerProfile = PlayerProfile(),
        horizonShots: Int = 3,
        deepAnalysis: Boolean = false,
        customFirstShotTip: TipOffset? = null,
        customFirstShotSpeedScale: Int? = null,
    ): List<RunoutPlan> {
        if (layout.cueBall() == null) return emptyList()
        val objectBallCount = layout.balls.count { it.number != 0 }
        if (objectBallCount == 0) return emptyList()

        val maxDepth = if (deepAnalysis) objectBallCount.coerceIn(1, 6) else horizonShots.coerceIn(1, 5)
        val beamWidth = if (deepAnalysis) 8 else 5

        val initialProblemBalls = ProblemBallAnalyzer.analyzeProblemBalls(layout, rulesState)
        val context = SearchContext(
            profile = playerProfile,
            customTip = customFirstShotTip,
            customSpeedScale = customFirstShotSpeedScale?.coerceIn(
                PlayerProfile.MIN_SPEED_SCALE,
                PlayerProfile.MAX_SPEED_SCALE,
            ),
        )

        val root = SearchNode(
            layout = layout,
            rules = rulesState,
            steps = emptyList(),
            cumulativeProbability = 1.0,
            bonusFactor = 1.0,
            leaveFactor = 1.0,
            terminal = false,
        )

        val finished = mutableListOf<SearchNode>()
        var beam = listOf(root)

        for (ply in 1..maxDepth) {
            val children = mutableListOf<SearchNode>()
            for (node in beam) {
                val expanded = expand(node, ply, context)
                if (expanded.isEmpty()) {
                    if (node.steps.isNotEmpty()) finished += node.copy(leaveFactor = LEAVE_FLOOR)
                } else {
                    children += expanded
                }
            }
            if (children.isEmpty()) {
                beam = emptyList()
                break
            }
            val survivors = children.sortedWith(NODE_ORDER).take(beamWidth)
            finished += survivors.filter { it.terminal }
            beam = survivors.filter { !it.terminal }
        }
        finished += beam

        val ranked = finished
            .filter { it.steps.isNotEmpty() }
            .sortedWith(NODE_ORDER)
        val seenFirstShots = HashSet<Pair<Int, PocketId>>()
        val selected = mutableListOf<SearchNode>()
        for (node in ranked) {
            val first = node.steps.first()
            if (seenFirstShots.add(first.targetBall.number to first.pocketId)) {
                selected += node
                if (selected.size >= MAX_RETURNED_PLANS) break
            }
        }

        return selected.map { node ->
            buildPlan(
                node = node,
                initialLayout = layout,
                initialRules = rulesState,
                playerProfile = playerProfile,
                problemBalls = initialProblemBalls,
            )
        }
    }

    fun evaluateAgainstGreedyBaseline(
        racks: List<Pair<TableLayout, GameRulesState>>,
        playerProfile: PlayerProfile = PlayerProfile(),
    ): SelfPlayBenchmarkResult {
        if (racks.isEmpty()) {
            return SelfPlayBenchmarkResult(
                rackCount = 0,
                beamPlannerRunoutRate = 0.0,
                greedyBaselineRunoutRate = 0.0,
                relativeImprovementFraction = 0.0,
            )
        }
        var beamTotal = 0.0
        var greedyTotal = 0.0
        for ((rackLayout, rackRules) in racks) {
            beamTotal += simulateBeamPolicy(rackLayout, rackRules, playerProfile)
            greedyTotal += simulateGreedyPolicy(rackLayout, rackRules, playerProfile)
        }
        val beamRate = beamTotal / racks.size
        val greedyRate = greedyTotal / racks.size
        return SelfPlayBenchmarkResult(
            rackCount = racks.size,
            beamPlannerRunoutRate = beamRate,
            greedyBaselineRunoutRate = greedyRate,
            relativeImprovementFraction = (beamRate - greedyRate) / greedyRate.coerceAtLeast(1e-6),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------------

    private data class SearchNode(
        val layout: TableLayout,
        val rules: GameRulesState,
        val steps: List<PlannedShotStep>,
        val cumulativeProbability: Double,
        val bonusFactor: Double,
        val leaveFactor: Double,
        val terminal: Boolean,
    ) {
        val value: Double
            get() = cumulativeProbability * bonusFactor * leaveFactor
    }

    private val NODE_ORDER: Comparator<SearchNode> = compareByDescending<SearchNode> { it.value }
        .thenBy { it.steps.firstOrNull()?.targetBall?.number ?: 0 }
        .thenBy { it.steps.firstOrNull()?.pocketId?.ordinal ?: 0 }

    private class SearchContext(
        val profile: PlayerProfile,
        val customTip: TipOffset?,
        val customSpeedScale: Int?,
    ) {
        private val problemCache = HashMap<String, List<ProblemBallReport>>()
        private val heatCache = HashMap<String, HeatZoneMap>()

        fun problemBalls(layout: TableLayout, rules: GameRulesState): List<ProblemBallReport> {
            val key = layout.balls.filter { !it.isCueBall }.map { it.number }.sorted().joinToString(",") +
                "|" + rules.gameType + "|" + rules.eightBallGroup
            return problemCache.getOrPut(key) { ProblemBallAnalyzer.analyzeProblemBalls(layout, rules) }
        }

        fun heatMap(layout: TableLayout, targetNumbers: List<Int>): HeatZoneMap {
            val key = layout.balls.filter { !it.isCueBall }.map { it.number }.sorted().joinToString(",") +
                "|" + targetNumbers.joinToString(",")
            return heatCache.getOrPut(key) {
                HeatZoneGenerator.computeForNextBalls(
                    layout = layout,
                    targetBallNumbers = targetNumbers,
                    playerProfile = profile,
                    cols = HEAT_COLS,
                    rows = HEAT_ROWS,
                )
            }
        }
    }

    private class ShotEvaluation(
        val compensatedAim: SpinCompensatedAim,
        val uncertainty: ShotUncertaintyResult,
        val cueSpeedMps: Double,
    ) {
        val stepProbability: Double
            get() = (uncertainty.makeProbability * (1.0 - uncertainty.scratchProbability)).coerceIn(0.0, 1.0)
    }

    private fun expand(node: SearchNode, ply: Int, context: SearchContext): List<SearchNode> {
        val cue = node.layout.cueBall() ?: return emptyList()
        val table = node.layout.table
        val targets = node.rules.legalTargetBalls(node.layout)
        if (targets.isEmpty()) return emptyList()

        val problemBalls = context.problemBalls(node.layout, node.rules)
        val customSpeed = context.customSpeedScale
        val customTip = context.customTip
        val speeds = if (ply == 1 && customSpeed != null) listOf(customSpeed) else SPEED_SCALES
        val tips = when {
            ply == 1 && customTip != null -> listOf(customTip)
            ply == 1 -> TipOffset.standardCandidates()
            else -> DEEP_PLY_TIPS
        }

        val children = mutableListOf<SearchNode>()
        for (target in targets) {
            for (pocket in table.pockets) {
                val aim = playableAim(node.layout, cue, target, pocket) ?: continue
                var bestChild: SearchNode? = null
                for (scale in speeds) {
                    for (tip in tips) {
                        val child = buildChild(
                            node = node,
                            ply = ply,
                            cue = cue,
                            target = target,
                            pocket = pocket,
                            aim = aim,
                            scale = scale,
                            tip = tip,
                            problemBalls = problemBalls,
                            context = context,
                        ) ?: continue
                        val currentBest = bestChild
                        if (currentBest == null || child.value > currentBest.value) bestChild = child
                    }
                }
                bestChild?.let { children += it }
            }
        }
        return children
    }

    private fun buildChild(
        node: SearchNode,
        ply: Int,
        cue: Ball,
        target: Ball,
        pocket: PocketSpec,
        aim: AimGeometry,
        scale: Int,
        tip: TipOffset,
        problemBalls: List<ProblemBallReport>,
        context: SearchContext,
    ): SearchNode? {
        val table = node.layout.table
        val evaluation = evaluateShot(node.layout, cue, target, pocket, aim, scale, tip, context.profile) ?: return null
        val uncertainty = evaluation.uncertainty
        val stepProbability = evaluation.stepProbability

        val breakout = ProblemBallAnalyzer.checkTrajectoryBreakout(
            trajectory = uncertainty.nominalTrajectory,
            shotTargetBallNumber = target.number,
            shotPocketId = pocket.id,
            problemBalls = problemBalls,
            ballRadiusMeters = cue.radiusMeters,
        )
        val breakoutBonus = when {
            breakout == null -> 0.0
            breakout.insuranceBallNumber != null -> BREAKOUT_BONUS_WITH_INSURANCE
            else -> BREAKOUT_BONUS_WITHOUT_INSURANCE
        }

        val advanced = advance(node.layout, node.rules, cue, target, uncertainty.meanLandingPosition)
        val landing = advanced.layout.cueBall()?.position ?: uncertainty.meanLandingPosition
        val terminal = advanced.nextTargets.isEmpty()
        val leaveFactor = if (terminal) {
            1.0
        } else {
            val heat = context.heatMap(advanced.layout, advanced.nextTargets.take(2).map { it.number })
            val overlapsBall = advanced.layout.balls.any {
                !it.isCueBall && it.position.distanceTo(landing) < LANDING_CLEARANCE_FACTOR * cue.radiusMeters
            }
            val heatValue = if (overlapsBall || !table.isWithinPlayableBounds(landing, cue.radiusMeters)) {
                0.0
            } else {
                heat.valueAt(landing)
            }
            LEAVE_FLOOR + (1.0 - LEAVE_FLOOR) * heatValue
        }

        val step = PlannedShotStep(
            stepNumber = ply,
            targetBall = target,
            pocketId = pocket.id,
            pocketCenter = pocket.center,
            compensatedAim = evaluation.compensatedAim,
            recommendedSpeedScale = scale,
            cueSpeedMps = evaluation.cueSpeedMps,
            recommendedTip = tip,
            uncertainty = uncertainty,
            breakoutOpportunity = breakout,
            stepScore = stepProbability * (1.0 + breakoutBonus),
        )

        return SearchNode(
            layout = advanced.layout,
            rules = advanced.rules,
            steps = node.steps + step,
            cumulativeProbability = node.cumulativeProbability * stepProbability,
            bonusFactor = node.bonusFactor * (1.0 + breakoutBonus),
            leaveFactor = leaveFactor,
            terminal = terminal,
        )
    }

    private fun playableAim(layout: TableLayout, cue: Ball, target: Ball, pocket: PocketSpec): AimGeometry? {
        val aim = GhostBallAiming.computeAimGeometry(
            cueBall = cue,
            objectBall = target,
            pocket = pocket,
            otherBalls = layout.balls,
            table = layout.table,
        )
        if (!aim.isShotPlayable) return null
        if (abs(aim.cutAngleRadians) > MAX_CUT_ANGLE_RAD) return null
        return aim
    }

    private fun evaluateShot(
        layout: TableLayout,
        cue: Ball,
        target: Ball,
        pocket: PocketSpec,
        aim: AimGeometry,
        scale: Int,
        tip: TipOffset,
        profile: PlayerProfile,
    ): ShotEvaluation? {
        val cueSpeed = profile.speedForScale(scale)
        val compensated = SpinPhysics.solveCompensatedAim(
            aim,
            cue.radiusMeters,
            target.radiusMeters,
            cueSpeed,
            tip,
            profile.shaftType,
            layout.table,
        ) ?: return null
        val uncertainty = ExecutionErrorModel.evaluateShotUncertainty(
            layout = layout,
            targetBall = target,
            pocket = pocket,
            aim = compensated.geometricAim,
            cueSpeedMps = cueSpeed,
            tip = tip,
            playerProfile = profile,
        )
        if (uncertainty.scratchProbability >= MAX_SCRATCH_PROBABILITY) return null
        return ShotEvaluation(compensated, uncertainty, cueSpeed)
    }

    // ---------------------------------------------------------------------------------------------
    // Rules and layout advance
    // ---------------------------------------------------------------------------------------------

    private class AdvancedState(
        val layout: TableLayout,
        val rules: GameRulesState,
        val nextTargets: List<Ball>,
    )

    private fun isGameOver(rules: GameRulesState, target: Ball): Boolean = when (rules.gameType) {
        GameType.EIGHT_BALL -> target.number == 8
        GameType.NINE_BALL -> target.number == 9
        GameType.TEN_BALL -> target.number == 10
    }

    private fun rulesAfterPotting(rules: GameRulesState, target: Ball): GameRulesState {
        if (rules.gameType != GameType.EIGHT_BALL || rules.eightBallGroup != EightBallGroup.OPEN) return rules
        return when {
            target.isSolid -> rules.copy(eightBallGroup = EightBallGroup.SOLIDS)
            target.isStripe -> rules.copy(eightBallGroup = EightBallGroup.STRIPES)
            else -> rules
        }
    }

    private fun advance(
        layout: TableLayout,
        rules: GameRulesState,
        cue: Ball,
        target: Ball,
        landingPosition: Vec2,
    ): AdvancedState {
        val table = layout.table
        val landing = Vec2(
            landingPosition.x.coerceIn(cue.radiusMeters, (table.lengthMeters - cue.radiusMeters).coerceAtLeast(cue.radiusMeters)),
            landingPosition.y.coerceIn(cue.radiusMeters, (table.widthMeters - cue.radiusMeters).coerceAtLeast(cue.radiusMeters)),
        )
        val nextBalls = layout.balls
            .filter { it.number != target.number }
            .map { if (it.isCueBall) it.copy(position = landing) else it }
        val nextLayout = TableLayout(table, nextBalls)
        val nextRules = rulesAfterPotting(rules, target)
        val nextTargets = if (isGameOver(rules, target)) emptyList() else nextRules.legalTargetBalls(nextLayout)
        return AdvancedState(nextLayout, nextRules, nextTargets)
    }

    // ---------------------------------------------------------------------------------------------
    // Plan assembly and explanations
    // ---------------------------------------------------------------------------------------------

    private fun buildPlan(
        node: SearchNode,
        initialLayout: TableLayout,
        initialRules: GameRulesState,
        playerProfile: PlayerProfile,
        problemBalls: List<ProblemBallReport>,
    ): RunoutPlan {
        val steps = node.steps
        val heatZone = buildHeatZone(steps, initialLayout, initialRules, playerProfile)
        val explanations = buildExplanations(steps, initialRules, problemBalls, heatZone, node)
        return RunoutPlan(
            steps = steps,
            cumulativeRunoutProbability = node.cumulativeProbability.coerceIn(0.0, 1.0),
            expectedValueScore = node.value.coerceAtLeast(0.0),
            nextBallHeatZone = heatZone,
            problemBalls = problemBalls,
            explanationReasons = explanations,
        )
    }

    private fun buildHeatZone(
        steps: List<PlannedShotStep>,
        initialLayout: TableLayout,
        initialRules: GameRulesState,
        playerProfile: PlayerProfile,
    ): HeatZoneMap? {
        val first = steps.firstOrNull() ?: return null
        val afterFirst = TableLayout(
            initialLayout.table,
            initialLayout.balls.filter { it.number != first.targetBall.number },
        )
        val targetNumbers = if (steps.size >= 2) {
            steps.drop(1).take(2).map { it.targetBall.number }
        } else if (isGameOver(initialRules, first.targetBall)) {
            emptyList()
        } else {
            rulesAfterPotting(initialRules, first.targetBall)
                .legalTargetBalls(afterFirst)
                .take(2)
                .map { it.number }
        }
        if (targetNumbers.isEmpty()) return null
        return HeatZoneGenerator.computeForNextBalls(
            layout = afterFirst,
            targetBallNumbers = targetNumbers,
            playerProfile = playerProfile,
        )
    }

    private fun percent(value: Double): Int = (value * 100.0).roundToInt().coerceIn(0, 100)

    private fun describeTip(tip: TipOffset): String {
        val vertical = when {
            tip.clampedVerticalRatio > 0.12 -> "follow (top spin)"
            tip.clampedVerticalRatio < -0.12 -> "draw (back spin)"
            else -> "stun (centre ball)"
        }
        val side = when {
            tip.clampedSideRatio > 0.12 -> " with right english"
            tip.clampedSideRatio < -0.12 -> " with left english"
            else -> ""
        }
        return vertical + side
    }

    private fun buildExplanations(
        steps: List<PlannedShotStep>,
        initialRules: GameRulesState,
        problemBalls: List<ProblemBallReport>,
        heatZone: HeatZoneMap?,
        node: SearchNode,
    ): List<String> {
        val reasons = mutableListOf<String>()
        var rules = initialRules
        for ((index, step) in steps.withIndex()) {
            val ballNumber = step.targetBall.number
            reasons += "Step ${step.stepNumber}: pot ball #$ballNumber in the ${step.pocketId.displayName} pocket " +
                "at speed ${step.recommendedSpeedScale}/10, ${percent(step.uncertainty.makeProbability)}% to make."

            val nextStep = steps.getOrNull(index + 1)
            val spinText = describeTip(step.recommendedTip)
            reasons += if (nextStep != null) {
                "Use $spinText so the cue ball lands in the high-percentage zone for ball #${nextStep.targetBall.number}."
            } else if (node.terminal) {
                "Use $spinText; this is the last ball, so only a safe finish matters."
            } else {
                "Use $spinText to keep the cue ball in a good zone for the next ball."
            }

            if (rules.gameType == GameType.EIGHT_BALL && step.targetBall.isEightBall) {
                reasons += "Your group is cleared at this point, so ball #8 becomes the legal target."
            }

            val breakout = step.breakoutOpportunity
            if (breakout != null) {
                val insurance = breakout.insuranceBallNumber
                reasons += if (insurance != null) {
                    "Step ${step.stepNumber} sends the cue ball past problem ball #${breakout.problemBallNumber} " +
                        "to free it; ball #$insurance is the open insurance ball if it stays tied up."
                } else {
                    "Step ${step.stepNumber} sends the cue ball past problem ball #${breakout.problemBallNumber} to free it."
                }
            }

            rules = rulesAfterPotting(rules, step.targetBall)
        }

        for (problem in problemBalls) {
            val causes = problem.reasons.joinToString(", ") {
                when (it) {
                    ProblemBallReason.NO_CLEAR_POCKET -> "no clear pocket"
                    ProblemBallReason.CLUSTERED_NEIGHBOURS ->
                        "clustered with ${problem.blockingBallNumbers.joinToString(", ") { n -> "#$n" }}"
                    ProblemBallReason.FROZEN_TO_RAIL -> "frozen to the rail"
                }
            }
            val insurance = problem.recommendedInsuranceBallNumber
            reasons += if (insurance != null) {
                "Problem ball #${problem.ballNumber}: $causes. Keep ball #$insurance available as insurance."
            } else {
                "Problem ball #${problem.ballNumber}: $causes. No open insurance ball is available."
            }
        }

        val best = heatZone?.bestLeaveCell()
        if (heatZone != null && best != null && best.value > 0.0) {
            reasons += "Best leave for ball #${heatZone.targetBallNumbers.first()} rates ${percent(best.value)}% " +
                "around x=${roundTo2(best.centerMeters.x)} m, y=${roundTo2(best.centerMeters.y)} m."
        }
        reasons += "Plan covers ${steps.size} shot(s) with ${percent(node.cumulativeProbability)}% combined success."
        return reasons
    }

    private fun roundTo2(value: Double): Double = (value * 100.0).roundToInt() / 100.0

    // ---------------------------------------------------------------------------------------------
    // Self-play
    // ---------------------------------------------------------------------------------------------

    private fun simulateBeamPolicy(
        initialLayout: TableLayout,
        initialRules: GameRulesState,
        profile: PlayerProfile,
    ): Double {
        var layout = initialLayout
        var rules = initialRules
        var probability = 1.0
        for (shot in 1..SELF_PLAY_MAX_SHOTS) {
            if (rules.legalTargetBalls(layout).isEmpty()) break
            val cue = layout.cueBall() ?: return 0.0
            val plan = planRunout(
                layout = layout,
                rulesState = rules,
                playerProfile = profile,
                horizonShots = SELF_PLAY_HORIZON,
            ).firstOrNull() ?: return 0.0
            val step = plan.steps.firstOrNull() ?: return 0.0
            probability *= (step.uncertainty.makeProbability * (1.0 - step.uncertainty.scratchProbability))
                .coerceIn(0.0, 1.0)
            val advanced = advance(layout, rules, cue, step.targetBall, step.uncertainty.meanLandingPosition)
            layout = advanced.layout
            rules = advanced.rules
            if (advanced.nextTargets.isEmpty()) break
        }
        return probability
    }

    private fun simulateGreedyPolicy(
        initialLayout: TableLayout,
        initialRules: GameRulesState,
        profile: PlayerProfile,
    ): Double {
        var layout = initialLayout
        var rules = initialRules
        var probability = 1.0
        val centerTip = TipOffset(sideRatio = 0.0, verticalRatio = 0.0)
        for (shot in 1..SELF_PLAY_MAX_SHOTS) {
            val targets = rules.legalTargetBalls(layout)
            if (targets.isEmpty()) break
            val cue = layout.cueBall() ?: return 0.0

            var bestTarget: Ball? = null
            var bestEvaluation: ShotEvaluation? = null
            for (target in targets) {
                for (pocket in layout.table.pockets) {
                    val aim = playableAim(layout, cue, target, pocket) ?: continue
                    val evaluation = evaluateShot(
                        layout, cue, target, pocket, aim, GREEDY_SPEED_SCALE, centerTip, profile,
                    ) ?: continue
                    val currentBest = bestEvaluation
                    if (currentBest == null || evaluation.stepProbability > currentBest.stepProbability) {
                        bestTarget = target
                        bestEvaluation = evaluation
                    }
                }
            }
            val chosenTarget = bestTarget ?: return 0.0
            val chosen = bestEvaluation ?: return 0.0
            probability *= chosen.stepProbability
            val advanced = advance(layout, rules, cue, chosenTarget, chosen.uncertainty.meanLandingPosition)
            layout = advanced.layout
            rules = advanced.rules
            if (advanced.nextTargets.isEmpty()) break
        }
        return probability
    }
}
