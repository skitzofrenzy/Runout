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
import com.runout.engine.physics.DisplacedBallPath
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.PostShotTableSimulation
import com.runout.engine.physics.SpinCompensatedAim
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Plain-language difficulty bucket paired with a shot's make or position probability.
 */
enum class ShotDifficultyTier(val label: String) {
    EASY("Easy"),
    MEDIUM("Medium"),
    HARD("Hard"),
    ;

    companion object {
        fun fromProbability(probability: Double): ShotDifficultyTier = when {
            probability >= 0.65 -> EASY
            probability >= 0.35 -> MEDIUM
            else -> HARD
        }
    }
}

/**
 * One planned shot inside a [RunoutPlan]: the target and pocket, the spin-compensated aim, the recommended speed
 * (player scale `1..10` and SI m/s), the recommended tip contact point, the execution-error evaluation,
 * next-ball position difficulty probability (`null` on the final winning ball), and the score used for ranking.
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
    val resultingLayout: TableLayout? = null,
    val disturbedBalls: List<DisplacedBallPath> = emptyList(),
    val positionSuccessProbability: Double? = null,
) {
    val makeDifficulty: ShotDifficultyTier
        get() = ShotDifficultyTier.fromProbability(uncertainty.makeProbability)

    val positionDifficulty: ShotDifficultyTier?
        get() = positionSuccessProbability?.let { ShotDifficultyTier.fromProbability(it) }
}

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
    private const val MAX_CUT_ANGLE_RAD: Double = HeatZoneGenerator.MAX_HEAT_CUT_ANGLE_RAD
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
        customFirstShotTargetBallNumber: Int? = null,
        customFirstShotPocketId: PocketId? = null,
        allowHighScratchFirstShot: Boolean = false,
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
            customTargetBallNumber = customFirstShotTargetBallNumber,
            customPocketId = customFirstShotPocketId,
            allowHighScratchFirstShot = allowHighScratchFirstShot,
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

    /**
     * Returns all pockets on [layout] into which [targetBall] currently has an unobstructed playable cut.
     */
    fun playablePocketsForTarget(layout: TableLayout, targetBall: Ball): List<PocketId> {
        val cue = layout.cueBall() ?: return emptyList()
        return layout.table.pockets
            .filter { pocket -> playableAim(layout, cue, targetBall, pocket) != null }
            .map { it.id }
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
        val customTargetBallNumber: Int? = null,
        val customPocketId: PocketId? = null,
        val allowHighScratchFirstShot: Boolean = false,
    ) {
        private val problemCache = HashMap<String, List<ProblemBallReport>>()
        private val heatCache = HashMap<String, HeatZoneMap>()

        private fun layoutSignature(layout: TableLayout): String =
            layout.balls
                .filter { !it.isCueBall }
                .sortedBy { it.number }
                .joinToString(",") { b ->
                    val gx = (b.position.x * 20.0).roundToInt()
                    val gy = (b.position.y * 20.0).roundToInt()
                    "${b.number}@${gx}_$gy"
                }

        fun problemBalls(layout: TableLayout, rules: GameRulesState): List<ProblemBallReport> {
            val key = layoutSignature(layout) + "|" + rules.gameType + "|" + rules.eightBallGroup
            return problemCache.getOrPut(key) { ProblemBallAnalyzer.analyzeProblemBalls(layout, rules) }
        }

        fun heatMap(layout: TableLayout, targetNumbers: List<Int>): HeatZoneMap {
            val key = layoutSignature(layout) + "|" + targetNumbers.joinToString(",")
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
        val targets = node.rules.legalTargetBalls(node.layout).let { legal ->
            if (ply == 1 && context.customTargetBallNumber != null) {
                legal.filter { it.number == context.customTargetBallNumber }
            } else {
                legal
            }
        }
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
        val pockets = if (ply == 1 && context.customPocketId != null) {
            table.pockets.filter { it.id == context.customPocketId }
        } else {
            table.pockets
        }

        val children = mutableListOf<SearchNode>()
        for (target in targets) {
            for (pocket in pockets) {
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
        val evaluation = evaluateShot(
            layout = node.layout,
            cue = cue,
            target = target,
            pocket = pocket,
            aim = aim,
            scale = scale,
            tip = tip,
            profile = context.profile,
            allowHighScratch = ply == 1 && context.allowHighScratchFirstShot,
        ) ?: return null
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

        val advanced = advance(
            layout = node.layout,
            rules = node.rules,
            cue = cue,
            target = target,
            landingPosition = uncertainty.nominalLandingPosition,
            simulation = uncertainty.postShotSimulation,
        )
        val landing = advanced.layout.cueBall()?.position ?: uncertainty.nominalLandingPosition
        val terminal = advanced.nextTargets.isEmpty()
        val virtualCueAtLanding = Ball(number = 0, position = landing, radiusMeters = cue.radiusMeters)
        val (leaveFactor, positionSuccessProbability) = when {
            terminal -> computeExecutionSimplicityFactor(
                scale = scale,
                tip = tip,
                bounceCount = 0,
                isTerminalShot = true,
            ) to null
            isFinalTargetState(advanced.rules, advanced.nextTargets) -> {
                val finalBall = advanced.nextTargets.first()
                val heat = context.heatMap(advanced.layout, listOf(finalBall.number))
                val overlapsBall = advanced.layout.balls.any {
                    !it.isCueBall && it.position.distanceTo(landing) < LANDING_CLEARANCE_FACTOR * cue.radiusMeters
                }
                val hasClearNextShot = !overlapsBall &&
                    table.isWithinPlayableBounds(landing, cue.radiusMeters) &&
                    table.pockets.any { nextPocket ->
                        playableAim(
                            layout = advanced.layout,
                            cue = virtualCueAtLanding,
                            target = finalBall,
                            pocket = nextPocket,
                        ) != null && !HeatZoneGenerator.isPointOccludedForPocket(
                            landing,
                            HeatZoneGenerator.computePocketVisibility(advanced.layout, finalBall, nextPocket, cue.radiusMeters),
                        )
                    }
                val heatValue = if (!hasClearNextShot) 0.0 else heat.valueAt(landing)
                val cloudEase = evaluateFinalBallLandingCloudEase(
                    landingCloud = uncertainty.landingCloud,
                    finalBall = finalBall,
                    nextLayout = advanced.layout,
                    cueRadius = cue.radiusMeters,
                    profile = context.profile,
                )
                val simplicity = computeExecutionSimplicityFactor(
                    scale = scale,
                    tip = tip,
                    bounceCount = (uncertainty.nominalTrajectory.points.size - 2).coerceAtLeast(0),
                    isTerminalShot = false,
                )
                val combinedEase = (0.75 * cloudEase + 0.25 * heatValue).coerceIn(0.0, 1.0) * simplicity
                val factor = 0.20 + 0.80 * combinedEase
                val posProb = evaluatePositionSuccessProbability(
                    uncertainty = uncertainty,
                    heat = heat,
                    nextLayout = advanced.layout,
                    landing = landing,
                    hasClearNextShot = hasClearNextShot,
                    cueRadius = cue.radiusMeters,
                    scale = scale,
                    tip = tip,
                )
                factor to posProb
            }
            else -> {
                val heat = context.heatMap(advanced.layout, advanced.nextTargets.take(2).map { it.number })
                val overlapsBall = advanced.layout.balls.any {
                    !it.isCueBall && it.position.distanceTo(landing) < LANDING_CLEARANCE_FACTOR * cue.radiusMeters
                }
                val hasClearNextShot = !overlapsBall &&
                    table.isWithinPlayableBounds(landing, cue.radiusMeters) &&
                    advanced.nextTargets.any { nextTarget ->
                        table.pockets.any { nextPocket ->
                            playableAim(
                                layout = advanced.layout,
                                cue = virtualCueAtLanding,
                                target = nextTarget,
                                pocket = nextPocket,
                            ) != null && !HeatZoneGenerator.isPointOccludedForPocket(
                                landing,
                                HeatZoneGenerator.computePocketVisibility(advanced.layout, nextTarget, nextPocket, cue.radiusMeters),
                            )
                        }
                    }
                val heatValue = if (!hasClearNextShot) 0.0 else heat.valueAt(landing)
                val factor = LEAVE_FLOOR + (1.0 - LEAVE_FLOOR) * heatValue
                val posProb = evaluatePositionSuccessProbability(
                    uncertainty = uncertainty,
                    heat = heat,
                    nextLayout = advanced.layout,
                    landing = landing,
                    hasClearNextShot = hasClearNextShot,
                    cueRadius = cue.radiusMeters,
                    scale = scale,
                    tip = tip,
                )
                factor to posProb
            }
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
            resultingLayout = advanced.layout,
            disturbedBalls = uncertainty.postShotSimulation?.disturbedBalls.orEmpty(),
            positionSuccessProbability = positionSuccessProbability,
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
        allowHighScratch: Boolean = false,
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
        if (!allowHighScratch && uncertainty.scratchProbability >= MAX_SCRATCH_PROBABILITY) return null
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

    private fun isFinalTargetState(rules: GameRulesState, nextTargets: List<Ball>): Boolean =
        nextTargets.size == 1 && isGameOver(rules, nextTargets.first())

    private fun evaluateFinalBallLandingCloudEase(
        landingCloud: List<Vec2>,
        finalBall: Ball,
        nextLayout: TableLayout,
        cueRadius: Double,
        profile: PlayerProfile,
    ): Double {
        val samplePoints = landingCloud.ifEmpty { listOfNotNull(nextLayout.cueBall()?.position) }
        if (samplePoints.isEmpty()) return 0.0
        val table = nextLayout.table
        var totalEase = 0.0
        for (pt in samplePoints) {
            if (!table.isWithinPlayableBounds(pt, cueRadius)) continue
            if (pt.distanceTo(finalBall.position) < LANDING_CLEARANCE_FACTOR * cueRadius) continue
            val virtualCue = Ball(number = 0, position = pt, radiusMeters = cueRadius)
            var bestPointScore = 0.0
            for (pocket in table.pockets) {
                val aim = playableAim(nextLayout, virtualCue, finalBall, pocket) ?: continue
                val quality = HeatZoneGenerator.shotQuality(aim, pocket, profile.aimErrorStdRad)
                val cutEase = 0.72 + 0.28 * cos(abs(aim.cutAngleRadians)).coerceAtLeast(0.0)
                val minRailDist = minOf(
                    pt.x,
                    table.lengthMeters - pt.x,
                    pt.y,
                    table.widthMeters - pt.y,
                )
                val comfortThreshold = 2.2 * cueRadius
                val comfort = if (minRailDist >= comfortThreshold) {
                    1.0
                } else {
                    0.78 + 0.22 * (minRailDist / comfortThreshold).coerceIn(0.0, 1.0)
                }
                val score = (quality * cutEase * comfort).coerceIn(0.0, 1.0)
                if (score > bestPointScore) bestPointScore = score
            }
            totalEase += bestPointScore
        }
        return (totalEase / samplePoints.size.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun evaluatePositionSuccessProbability(
        uncertainty: ShotUncertaintyResult,
        heat: HeatZoneMap,
        nextLayout: TableLayout,
        landing: Vec2,
        hasClearNextShot: Boolean,
        cueRadius: Double,
        scale: Int,
        tip: TipOffset,
    ): Double {
        val cloud = uncertainty.landingCloud.ifEmpty { listOf(landing) }
        val weights = ExecutionErrorModel.SIGMA_WEIGHTS
        val table = nextLayout.table
        val objectBalls = nextLayout.balls.filter { !it.isCueBall }
        val nominalHeat = if (hasClearNextShot) heat.valueAt(landing) else 0.0

        var weightedSum = 0.0
        var totalWeight = 0.0
        for (i in cloud.indices) {
            val pt = cloud[i]
            val w = weights.getOrElse(i) { 1.0 / cloud.size }
            totalWeight += w
            val inBounds = table.isWithinPlayableBounds(pt, cueRadius)
            val hitsBall = objectBalls.any {
                it.position.distanceTo(pt) < LANDING_CLEARANCE_FACTOR * cueRadius
            }
            if (!inBounds || hitsBall) continue
            val v = heat.valueAt(pt)
            if (v <= 0.02) continue
            val rel = if (nominalHeat > 0.05) (v / nominalHeat).coerceIn(0.0, 1.0) else 0.0
            val absQuality = (v / 0.72).coerceIn(0.0, 1.0)
            weightedSum += w * (0.55 * rel + 0.45 * absQuality)
        }

        val rawZoneAccuracy = (weightedSum / totalWeight.coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        val zoneAccuracy = if (hasClearNextShot) {
            rawZoneAccuracy
        } else {
            (rawZoneAccuracy * 0.25).coerceAtMost(0.18)
        }

        val pts = uncertainty.nominalTrajectory.points
        val bounceCount = (pts.size - 2).coerceAtLeast(0)
        var travelMeters = 0.0
        for (i in 1 until pts.size) {
            travelMeters += pts[i - 1].distanceTo(pts[i])
        }
        val sideMag = abs(tip.clampedSideRatio)
        val drawMag = (-tip.clampedVerticalRatio).coerceAtLeast(0.0)
        val followMag = tip.clampedVerticalRatio.coerceAtLeast(0.0)
        val spinPenalty = 0.36 * sideMag + 0.32 * drawMag + 0.10 * followMag
        val railPenalty = (0.11 * bounceCount).coerceAtMost(0.38)
        val travelPenalty = (0.09 * (travelMeters - 0.35).coerceAtLeast(0.0)).coerceAtMost(0.24)
        val spreadPenalty = 0.28 * (uncertainty.landingSpreadRadiusMeters / 0.55).coerceIn(0.0, 1.0)
        val speedStress = when {
            scale <= 2 -> 0.10
            scale >= 9 -> 0.22 + 0.06 * (scale - 9)
            scale >= 7 -> 0.08 * (scale - 6)
            else -> 0.0
        }
        val strokeControl = (1.0 - spinPenalty - railPenalty - travelPenalty - spreadPenalty - speedStress)
            .coerceIn(0.18, 1.0)
        val scratchSurvival = (1.0 - uncertainty.scratchProbability).coerceIn(0.0, 1.0)
        return (zoneAccuracy * strokeControl * scratchSurvival).coerceIn(0.02, 0.98)
    }

    private fun computeExecutionSimplicityFactor(
        scale: Int,
        tip: TipOffset,
        bounceCount: Int,
        isTerminalShot: Boolean,
    ): Double = if (isTerminalShot) {
        (1.0 - 0.04 * tip.magnitudeRatio - 0.015 * abs(scale - 4)).coerceIn(0.84, 1.0)
    } else {
        (1.0 - 0.08 * tip.magnitudeRatio - 0.022 * abs(scale - 4) - 0.045 * (bounceCount - 1).coerceAtLeast(0))
            .coerceIn(0.70, 1.0)
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
        simulation: PostShotTableSimulation? = null,
    ): AdvancedState {
        val baseLayout = simulation?.resultingLayout ?: TableLayout(
            table = layout.table,
            balls = layout.balls.filter { it.number != target.number },
        )
        val table = baseLayout.table
        val clampedMean = Vec2(
            landingPosition.x.coerceIn(cue.radiusMeters, (table.lengthMeters - cue.radiusMeters).coerceAtLeast(cue.radiusMeters)),
            landingPosition.y.coerceIn(cue.radiusMeters, (table.widthMeters - cue.radiusMeters).coerceAtLeast(cue.radiusMeters)),
        )
        val meanOverlapsObject = baseLayout.balls.any {
            !it.isCueBall && it.position.distanceTo(clampedMean) < LANDING_CLEARANCE_FACTOR * cue.radiusMeters
        }
        val finalCuePos = if (!meanOverlapsObject) {
            clampedMean
        } else {
            baseLayout.cueBall()?.position ?: clampedMean
        }
        val nextBalls = baseLayout.balls.map { if (it.isCueBall) it.copy(position = finalCuePos) else it }
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
        val afterFirst = first.resultingLayout ?: TableLayout(
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
            cols = 32,
            rows = 16,
            preferredPocketId = steps.getOrNull(1)?.pocketId,
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
            val posSummary = step.positionSuccessProbability?.let { posProb ->
                ", ${percent(posProb)}% position (${step.positionDifficulty!!.label})"
            } ?: ", final ball"
            reasons += "Step ${step.stepNumber}: pot ball #$ballNumber in the ${step.pocketId.displayName} pocket " +
                "at speed ${step.recommendedSpeedScale}/10, ${percent(step.uncertainty.makeProbability)}% to make (${step.makeDifficulty.label})$posSummary."

            val nextStep = steps.getOrNull(index + 1)
            val spinText = describeTip(step.recommendedTip)
            reasons += if (nextStep != null && isGameOver(rules, nextStep.targetBall)) {
                "Use $spinText to set up a clean, easy final shot on ball #${nextStep.targetBall.number}."
            } else if (nextStep != null) {
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
            val advanced = advance(
                layout,
                rules,
                cue,
                step.targetBall,
                step.uncertainty.nominalLandingPosition,
                step.uncertainty.postShotSimulation,
            )
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
            val advanced = advance(
                layout,
                rules,
                cue,
                chosenTarget,
                chosen.uncertainty.nominalLandingPosition,
                chosen.uncertainty.postShotSimulation,
            )
            layout = advanced.layout
            rules = advanced.rules
            if (advanced.nextTargets.isEmpty()) break
        }
        return probability
    }
}
