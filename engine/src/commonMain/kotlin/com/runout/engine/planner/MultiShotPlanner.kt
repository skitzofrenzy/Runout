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
 * the position probability (`null` on the final winning ball and on a shot that does not pot), and the score used
 * for ranking.
 *
 * [positionSuccessProbability] is the probability that the cue ball does not scratch and that the next shot, played
 * from where this shot leaves the cue ball (averaged over the landing cloud), is made.
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
    val recommendedSpeedUnits: Double = recommendedSpeedScale.toDouble(),
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
 * Every ply enumerates legal target balls, unobstructed pockets, and a coarse grid of speeds and cue-tip offsets; the
 * best strokes of each target-pocket pair are then refined in half speed steps and one vertical tip step while the
 * plan improves. Each candidate is simulated with the spin-compensated aim and the execution-error model and moves
 * the cue ball to its simulated resting place. A plan is scored as
 *
 * > value = product of P(make and no scratch) x product of (control x hold) x leave
 *
 * - **P(make)** comes from [ExecutionErrorModel.makeProbability]: a real probability for this player.
 * - **control** ([controlFactor]) is how hard the stroke is to play as asked, each cause counted once: cue-ball
 *   routes of two or more cushions, tip offsets toward the miscue limit, and very hard or very soft speeds. Side
 *   spin, cut and distance are not in it; they are in P(make).
 * - **hold** is the share of the next shot's make probability the player keeps over the landing cloud (speed and aim
 *   error) compared with the nominal landing.
 * - **leave** is the value of the last landing. Inside the horizon it is the next shot's best make probability; at the
 *   end of the horizon it is a one-shot look ahead ([leafValue]): the best next pot times the leave it gives on the
 *   ball after (rotation games: the next ball in order; 8-ball: the best remaining ball). In 8-ball the leave also
 *   carries a rack term for problem balls still tied up on the table the shot leaves ([rackFactor]).
 *
 * Only the best [beamWidth] states survive each ply. Shots that do not pot, or whose nominal cue ball scratches, are
 * only searched when the caller allows them for the first shot, and then always rank below plans that pot.
 */
object MultiShotPlanner {
    private const val MAX_CUT_ANGLE_RAD: Double = HeatZoneGenerator.MAX_HEAT_CUT_ANGLE_RAD
    private const val MAX_SCRATCH_PROBABILITY: Double = 0.45
    private const val LEAVE_FLOOR: Double = 0.05
    private const val MAX_RETURNED_PLANS: Int = 5
    private const val SELF_PLAY_MAX_SHOTS: Int = 4
    private const val SELF_PLAY_HORIZON: Int = 3
    private const val GREEDY_SPEED_SCALE: Int = 6
    private const val LANDING_CLEARANCE_FACTOR: Double = 2.0
    private const val DISTINCT_LANDING_METERS: Double = 0.25

    /** Other cue-ball routes kept per target-pocket pair, besides the best, on the first ply and on later plies before the last. */
    private const val PLY1_ALTERNATIVE_ROUTES: Int = 2
    private const val DEEP_PLY_ALTERNATIVE_ROUTES: Int = 1
    private const val VALUE_EPSILON: Double = 1e-12

    /** Plans within this fraction of each other count as equally good when choosing a stroke. */
    private const val NEAR_TIE_FRACTION: Double = 0.002

    /** A medium pace: among equally good strokes the one closest to it is preferred. */
    private const val COMFORTABLE_SPEED_UNITS: Double = 4.0

    /** Cells of the lazily filled next-shot and leaf caches (about 5 cm on a 9 ft table). */
    private const val CACHE_COLS: Int = 48
    private const val CACHE_ROWS: Int = 24

    private val PLY1_SPEED_UNITS: List<Double> = listOf(2.0, 2.5, 3.5, 4.5, 5.5, 6.5, 8.0)
    private val DEEP_PLY_SPEED_UNITS: List<Double> = listOf(2.5, 4.5, 6.5)

    /** First-ply tips: the graded planner set. Deeper plies use its centre-line tips and its two mild follow-side tips. */
    private val PLY1_TIPS: List<TipOffset> = TipOffset.gradedPlannerCandidates()
    private val DEEP_PLY_TIPS: List<TipOffset> = PLY1_TIPS.filter {
        it.sideRatio == 0.0 || (abs(it.sideRatio) == 0.16 && it.verticalRatio == 0.20)
    }

    private const val REFINE_SPEED_STEP: Double = 0.5
    private const val REFINE_TIP_STEP: Double = 0.09
    private const val REFINE_MAX_SPEED_STEPS: Int = 6
    private const val REFINE_MIN_SPEED: Double = 1.0
    private const val REFINE_MAX_SPEED: Double = 9.5

    /** Strokes tried from a leaf for the leave on the ball after the next one: centre, follow and draw, medium and firm. */
    private val LEAF_SPEED_UNITS: List<Double> = listOf(3.0, 5.0)
    private val LEAF_TIPS: List<TipOffset> = listOf(
        TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
        TipOffset(sideRatio = 0.0, verticalRatio = 0.30),
        TipOffset(sideRatio = 0.0, verticalRatio = -0.30),
    )
    private const val LEAF_SHOTS_EXPANDED: Int = 2
    private const val LEAF_MAX_CUSHIONS: Int = 2

    // Control factor.
    private const val TWO_CUSHION_FACTOR: Double = 0.90
    private const val THREE_CUSHION_FACTOR: Double = 0.80
    private const val EXTRA_CUSHION_STEP: Double = 0.05
    private const val MIN_CUSHION_FACTOR: Double = 0.60
    private const val COMFORTABLE_TIP_RATIO: Double = 0.25
    private const val TIP_AT_LIMIT_PENALTY: Double = 0.20
    private const val POWER_SPEED_UNITS: Double = 6.5
    private const val POWER_PENALTY_PER_UNIT: Double = 0.05
    private const val SOFT_SPEED_UNITS: Double = 2.0
    private const val SOFT_PENALTY_PER_UNIT: Double = 0.04

    // Eight-ball rack term.
    private const val UNRESOLVED_PROBLEM_FACTOR: Double = 0.85

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
        customFirstShotSpeedUnits: Double? = null,
        firstStepNumber: Int = 1,
    ): List<RunoutPlan> {
        if (layout.cueBall() == null) return emptyList()
        val objectBallCount = layout.balls.count { it.number != 0 }
        if (objectBallCount == 0) return emptyList()

        val maxDepth = if (deepAnalysis) objectBallCount.coerceIn(1, 6) else horizonShots.coerceIn(1, 5)
        val beamWidth = if (deepAnalysis) 9 else 6

        val resolvedCustomSpeedUnits = when {
            customFirstShotSpeedUnits != null -> PlayerProfile.snapSpeedUnits(customFirstShotSpeedUnits)
            customFirstShotSpeedScale != null -> customFirstShotSpeedScale.coerceIn(
                PlayerProfile.MIN_SPEED_SCALE,
                PlayerProfile.MAX_SPEED_SCALE,
            ).toDouble()
            else -> null
        }

        val initialProblemBalls = ProblemBallAnalyzer.analyzeProblemBalls(layout, rulesState)
        val context = SearchContext(
            profile = playerProfile,
            customTip = customFirstShotTip,
            customSpeedUnits = resolvedCustomSpeedUnits,
            customTargetBallNumber = customFirstShotTargetBallNumber,
            customPocketId = customFirstShotPocketId,
            allowHighScratchFirstShot = allowHighScratchFirstShot,
            firstStepNumber = firstStepNumber,
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
                val expanded = expand(node, ply, ply == maxDepth, context)
                if (expanded.isEmpty()) {
                    if (node.steps.isNotEmpty()) finished += node.copy(leaveFactor = LEAVE_FLOOR, notes = emptyList())
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

    /**
     * A partial plan. [legal] is false once a step does not pot or scratches (only possible when the caller allows
     * such a first shot); [notes] are explanations that belong to the plan ending at this node.
     */
    private data class SearchNode(
        val layout: TableLayout,
        val rules: GameRulesState,
        val steps: List<PlannedShotStep>,
        val cumulativeProbability: Double,
        val bonusFactor: Double,
        val leaveFactor: Double,
        val terminal: Boolean,
        val legal: Boolean = true,
        val notes: List<String> = emptyList(),
    ) {
        val value: Double
            get() = cumulativeProbability * bonusFactor * leaveFactor
    }

    private val NODE_ORDER: Comparator<SearchNode> = compareByDescending<SearchNode> { it.legal }
        .thenByDescending { it.value }
        .thenBy { it.steps.firstOrNull()?.targetBall?.number ?: 0 }
        .thenBy { it.steps.firstOrNull()?.pocketId?.ordinal ?: 0 }

    /** A leaf's value with the shot behind it, for the explanation of the plan's last step. */
    private class LeafResult(val value: Double, val note: String?)

    private class SearchContext(
        val profile: PlayerProfile,
        val customTip: TipOffset?,
        val customSpeedUnits: Double?,
        val customTargetBallNumber: Int?,
        val customPocketId: PocketId?,
        val allowHighScratchFirstShot: Boolean,
        val firstStepNumber: Int,
    ) {
        private val problemCache = HashMap<String, List<ProblemBallReport>>()
        private val nextShotCache = HashMap<String, DoubleArray>()
        private val leafCache = HashMap<String, Array<LeafResult?>>()

        fun problemBalls(layout: TableLayout, rules: GameRulesState, signature: String): List<ProblemBallReport> {
            val key = signature + "|" + rules.gameType + "|" + rules.eightBallGroup
            return problemCache.getOrPut(key) { ProblemBallAnalyzer.analyzeProblemBalls(layout, rules) }
        }

        private fun cellOf(layout: TableLayout, point: Vec2): Int {
            val table = layout.table
            val col = ((point.x / table.lengthMeters) * CACHE_COLS).toInt().coerceIn(0, CACHE_COLS - 1)
            val row = ((point.y / table.widthMeters) * CACHE_ROWS).toInt().coerceIn(0, CACHE_ROWS - 1)
            return row * CACHE_COLS + col
        }

        private fun cellCentre(layout: TableLayout, cell: Int): Vec2 {
            val table = layout.table
            val col = cell % CACHE_COLS
            val row = cell / CACHE_COLS
            return Vec2((col + 0.5) * table.lengthMeters / CACHE_COLS, (row + 0.5) * table.widthMeters / CACHE_ROWS)
        }

        /**
         * Where to evaluate a cue ball resting at [point]: the centre of its cache cell when a cue ball can rest there
         * (so the value can be shared), else [point] itself (not cached).
         */
        private fun evaluationPoint(layout: TableLayout, point: Vec2, cueRadius: Double): Pair<Vec2, Int?> {
            val cell = cellOf(layout, point)
            val centre = cellCentre(layout, cell)
            return if (canRestAt(layout, centre, cueRadius)) centre to cell else point to null
        }

        /**
         * Best make probability, over [targets] and pockets, of the next shot from a cue ball resting at [point] on
         * [layout] (whose own cue ball is ignored); `0` where a cue ball cannot rest.
         */
        fun nextShotProbability(
            layout: TableLayout,
            signature: String,
            targets: List<Ball>,
            point: Vec2,
            cueRadius: Double,
        ): Double {
            if (targets.isEmpty() || !canRestAt(layout, point, cueRadius)) return 0.0
            val (at, cell) = evaluationPoint(layout, point, cueRadius)
            val key = signature + "|" + targets.joinToString(",") { it.number.toString() }
            val grid = nextShotCache.getOrPut(key) { DoubleArray(CACHE_COLS * CACHE_ROWS) { Double.NaN } }
            if (cell != null && !grid[cell].isNaN()) return grid[cell]
            val value = bestPotProbability(layout, targets, at, cueRadius)
            if (cell != null) grid[cell] = value
            return value
        }

        private fun bestPotProbability(layout: TableLayout, targets: List<Ball>, at: Vec2, cueRadius: Double): Double {
            val cue = Ball(number = 0, position = at, radiusMeters = cueRadius)
            var best = 0.0
            for (target in targets) {
                for (pocket in layout.table.pockets) {
                    val aim = playableAim(layout, cue, target, pocket) ?: continue
                    val p = HeatZoneGenerator.shotQuality(aim, pocket, profile.aimErrorStdRad)
                    if (p > best) best = p
                }
            }
            return best
        }

        /** [leafValue], cached per table signature and cell. */
        fun leaf(layout: TableLayout, rules: GameRulesState, signature: String, point: Vec2, cueRadius: Double): LeafResult {
            if (!canRestAt(layout, point, cueRadius)) return LeafResult(0.0, null)
            val (at, cell) = evaluationPoint(layout, point, cueRadius)
            val key = signature + "|" + rules.gameType + "|" + rules.eightBallGroup
            val grid = leafCache.getOrPut(key) { arrayOfNulls(CACHE_COLS * CACHE_ROWS) }
            if (cell != null) grid[cell]?.let { return it }
            val value = leafValue(layout, rules, at, cueRadius, this)
            if (cell != null) grid[cell] = value
            return value
        }
    }

    private fun canRestAt(layout: TableLayout, point: Vec2, cueRadius: Double): Boolean =
        layout.table.isWithinPlayableBounds(point, cueRadius) &&
            layout.balls.none { !it.isCueBall && it.position.distanceTo(point) < LANDING_CLEARANCE_FACTOR * cueRadius }

    private fun layoutSignature(layout: TableLayout): String =
        layout.balls
            .filter { !it.isCueBall }
            .sortedBy { it.number }
            .joinToString(",") { b ->
                val gx = (b.position.x * 200.0).roundToInt()
                val gy = (b.position.y * 200.0).roundToInt()
                "${b.number}@${gx}_$gy"
            }

    /**
     * One-shot look ahead from a cue ball at [at] on [layout]: for the best [LEAF_SHOTS_EXPANDED] next pots, the make
     * probability times the leave it gives on the ball after it (the best of a small set of centre, follow and draw
     * strokes, each traced among the other balls and charged for cushions like [controlFactor]). When the next ball
     * is the last one, its make probability alone.
     */
    private fun leafValue(
        layout: TableLayout,
        rules: GameRulesState,
        at: Vec2,
        cueRadius: Double,
        context: SearchContext,
    ): LeafResult {
        val table = layout.table
        val profile = context.profile
        val cue = Ball(number = 0, position = at, radiusMeters = cueRadius)
        class NextPot(val target: Ball, val pocket: PocketSpec, val aim: AimGeometry, val probability: Double)

        val pots = ArrayList<NextPot>()
        for (target in rules.legalTargetBalls(layout)) {
            for (pocket in table.pockets) {
                val aim = playableAim(layout, cue, target, pocket) ?: continue
                val p = HeatZoneGenerator.shotQuality(aim, pocket, profile.aimErrorStdRad)
                if (p > 0.0) pots += NextPot(target, pocket, aim, p)
            }
        }
        if (pots.isEmpty()) return LeafResult(0.0, null)
        pots.sortWith(
            compareByDescending<NextPot> { it.probability }
                .thenBy { it.target.number }
                .thenBy { it.pocket.id.ordinal },
        )

        var best = LeafResult(0.0, null)
        for (pot in pots.take(LEAF_SHOTS_EXPANDED)) {
            val after = TableLayout(table, layout.balls.filter { it.number != pot.target.number && !it.isCueBall })
            val ballsAfter = if (isGameOver(rules, pot.target)) {
                emptyList()
            } else {
                rulesAfterPotting(rules, pot.target).legalTargetBalls(after)
            }
            if (ballsAfter.isEmpty()) {
                if (pot.probability > best.value) best = LeafResult(pot.probability, null)
                continue
            }
            val afterSignature = layoutSignature(after)
            var bestLeave = 0.0
            var bestCushions = 0
            for (speedUnits in LEAF_SPEED_UNITS) {
                val speed = profile.speedForUnits(speedUnits)
                for (tip in LEAF_TIPS) {
                    val pre = SpinPhysics.computePreImpactCueState(speed, tip, pot.aim.cueToGhostDistanceMeters, table)
                    if (pre.state == SpinPhysics.ImpactSpinState.STOPPED_SHORT) continue
                    val path = SpinPhysics.traceCueBallPathWithSpin(
                        aim = pot.aim,
                        cueSpeedMps = speed,
                        tip = tip,
                        table = table,
                        ballRadiusMeters = cueRadius,
                        maxBounces = LEAF_MAX_CUSHIONS,
                        obstacleBalls = after.balls,
                    )
                    if (path.hitsScratchPocket != null) continue
                    val stop = path.points.lastOrNull() ?: continue
                    val next = context.nextShotProbability(after, afterSignature, ballsAfter, stop, cueRadius)
                    val leave = (LEAVE_FLOOR + (1.0 - LEAVE_FLOOR) * next) * cushionFactor(path.cushionContacts)
                    if (leave > bestLeave) {
                        bestLeave = leave
                        bestCushions = path.cushionContacts
                    }
                }
            }
            val value = pot.probability * bestLeave.coerceAtLeast(LEAVE_FLOOR)
            if (value > best.value) {
                val after1 = ballsAfter.joinToString(" or ") { "#${it.number}" }
                val route = when (bestCushions) {
                    0 -> "with no cushion"
                    1 -> "off one cushion"
                    else -> "off $bestCushions cushions"
                }
                best = LeafResult(
                    value,
                    "Ball #${pot.target.number} is then a ${percent(pot.probability)}% pot into the " +
                        "${pot.pocket.id.displayName} pocket, with a route $route to $after1.",
                )
            }
        }
        return best
    }

    private class ShotEvaluation(
        val compensatedAim: SpinCompensatedAim,
        val uncertainty: ShotUncertaintyResult,
        val cueSpeedMps: Double,
        val preImpact: SpinPhysics.PreImpactCueState,
        val legal: Boolean,
    ) {
        val stepProbability: Double
            get() = (uncertainty.makeProbability * (1.0 - uncertainty.scratchProbability)).coerceIn(0.0, 1.0)
    }

    private fun expand(node: SearchNode, ply: Int, isLastPly: Boolean, context: SearchContext): List<SearchNode> {
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

        val signature = layoutSignature(node.layout)
        val problemBalls = context.problemBalls(node.layout, node.rules, signature)
        val speedPinned = ply == 1 && context.customSpeedUnits != null
        val tipPinned = ply == 1 && context.customTip != null
        val speeds = when {
            speedPinned -> listOfNotNull(context.customSpeedUnits)
            ply == 1 -> PLY1_SPEED_UNITS
            else -> DEEP_PLY_SPEED_UNITS
        }
        val tips = when {
            tipPinned -> listOfNotNull(context.customTip)
            ply == 1 -> PLY1_TIPS
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
                val tried = HashMap<Pair<Double, TipOffset>, SearchNode?>()
                fun stroke(speedUnits: Double, tip: TipOffset): SearchNode? {
                    val key = speedUnits to tip
                    if (key in tried) return tried[key]
                    val child = buildChild(node, ply, isLastPly, cue, target, pocket, aim, speedUnits, tip, problemBalls, context)
                    tried[key] = child
                    return child
                }
                for (speedUnits in speeds) for (tip in tips) stroke(speedUnits, tip)
                val coarse = tried.values.filterNotNull().sortedWith(NODE_ORDER)
                if (coarse.isEmpty()) continue
                val primary = refine(simplestNearBest(coarse), speedPinned, tipPinned, ::stroke)
                children += primary
                // Before the last ply, also keep other cue-ball routes (for example holding short versus rolling off a rail):
                // when the next pot is easy from all of them, only the plies after can tell them apart.
                val alternatives = when {
                    isLastPly || (ply == 1 && (speedPinned || tipPinned)) -> 0
                    ply == 1 -> PLY1_ALTERNATIVE_ROUTES
                    else -> DEEP_PLY_ALTERNATIVE_ROUTES
                }
                val landings = mutableListOf(landingOf(primary))
                for (candidate in coarse) {
                    if (landings.size > alternatives) break
                    if (landings.any { landingOf(candidate).distanceTo(it) < DISTINCT_LANDING_METERS }) continue
                    val refined = refine(candidate, speedPinned = false, tipPinned = false, stroke = ::stroke)
                    val route = if (landings.none { landingOf(refined).distanceTo(it) < DISTINCT_LANDING_METERS }) refined else candidate
                    children += route
                    landings += landingOf(route)
                }
            }
        }
        return children
    }

    private fun landingOf(node: SearchNode): Vec2 =
        node.layout.cueBall()?.position ?: node.steps.last().uncertainty.nominalLandingPosition

    /** A pace closer to [COMFORTABLE_SPEED_UNITS] first, then a tip closer to the centre: the simpler of two strokes. */
    private val SIMPLER_STROKE: Comparator<SearchNode> =
        compareBy<SearchNode> { abs(it.steps.last().recommendedSpeedUnits - COMFORTABLE_SPEED_UNITS) }
        .thenBy { it.steps.last().recommendedTip.magnitudeRatio }

    /**
     * Whether [candidate] should replace [current] as the stroke of a step: it is clearly better (by more than
     * [NEAR_TIE_FRACTION]), or it is a simpler stroke ([SIMPLER_STROKE]) whose plan is within [NEAR_TIE_FRACTION] of
     * [bestValue], the best value seen for this shot. When two strokes do equally well a player plays the one at a more
     * comfortable pace and closer to centre ball.
     */
    private fun preferable(candidate: SearchNode, current: SearchNode, bestValue: Double): Boolean {
        if (candidate.legal != current.legal) return candidate.legal
        if (candidate.value > current.value * (1.0 + NEAR_TIE_FRACTION) + VALUE_EPSILON) return true
        return candidate.value >= bestValue * (1.0 - NEAR_TIE_FRACTION) && SIMPLER_STROKE.compare(candidate, current) < 0
    }

    /** The simplest stroke among [ranked] (best first) whose plan is within [NEAR_TIE_FRACTION] of the best. */
    private fun simplestNearBest(ranked: List<SearchNode>): SearchNode {
        val first = ranked.first()
        return ranked
            .filter { it.legal == first.legal && it.value >= first.value * (1.0 - NEAR_TIE_FRACTION) }
            .minWith(SIMPLER_STROKE.thenByDescending { it.value })
    }

    /**
     * Hill-climbs the stroke of [seed]'s last step: speed in [REFINE_SPEED_STEP] steps in each direction while the plan
     * improves ([preferable]), then one [REFINE_TIP_STEP] of the vertical tip either way. [stroke] evaluates (and
     * remembers) a stroke.
     */
    private fun refine(
        seed: SearchNode,
        speedPinned: Boolean,
        tipPinned: Boolean,
        stroke: (Double, TipOffset) -> SearchNode?,
    ): SearchNode {
        if (!seed.legal) return seed
        var best = seed
        var bestValue = seed.value
        if (!speedPinned) {
            for (direction in intArrayOf(1, -1)) {
                var current = best
                var steps = 0
                while (steps < REFINE_MAX_SPEED_STEPS) {
                    val last = current.steps.last()
                    val next = last.recommendedSpeedUnits + direction * REFINE_SPEED_STEP
                    if (next < REFINE_MIN_SPEED || next > REFINE_MAX_SPEED) break
                    val candidate = stroke(next, last.recommendedTip) ?: break
                    bestValue = maxOf(bestValue, candidate.value)
                    if (!preferable(candidate, current, bestValue)) break
                    current = candidate
                    steps++
                }
                if (preferable(current, best, bestValue)) best = current
            }
        }
        if (!tipPinned) {
            val last = best.steps.last()
            for (delta in doubleArrayOf(REFINE_TIP_STEP, -REFINE_TIP_STEP)) {
                val vertical = ((last.recommendedTip.verticalRatio + delta) * 100.0).roundToInt() / 100.0
                val tip = last.recommendedTip.copy(verticalRatio = vertical)
                if (!tip.isWithinMiscueLimit) continue
                val candidate = stroke(last.recommendedSpeedUnits, tip) ?: continue
                bestValue = maxOf(bestValue, candidate.value)
                if (preferable(candidate, best, bestValue)) best = candidate
            }
        }
        return best
    }

    private fun buildChild(
        node: SearchNode,
        ply: Int,
        isLastPly: Boolean,
        cue: Ball,
        target: Ball,
        pocket: PocketSpec,
        aim: AimGeometry,
        speedUnits: Double,
        tip: TipOffset,
        problemBalls: List<ProblemBallReport>,
        context: SearchContext,
    ): SearchNode? {
        val evaluation = evaluateShot(
            layout = node.layout,
            cue = cue,
            target = target,
            pocket = pocket,
            aim = aim,
            speedUnits = speedUnits,
            tip = tip,
            profile = context.profile,
            allowHighScratch = ply == 1 && context.allowHighScratchFirstShot,
        ) ?: return null
        val uncertainty = evaluation.uncertainty
        val stepProbability = evaluation.stepProbability
        val legal = evaluation.legal

        val breakout = truthfulBreakout(uncertainty, target, pocket, problemBalls, cue.radiusMeters)
        val advanced = advance(
            layout = node.layout,
            rules = node.rules,
            cue = cue,
            target = target,
            landingPosition = uncertainty.nominalLandingPosition,
            simulation = uncertainty.postShotSimulation,
        )
        val landing = advanced.layout.cueBall()?.position ?: uncertainty.nominalLandingPosition
        val gameWon = legal && advanced.nextTargets.isEmpty()
        val terminal = !legal || gameWon
        val stepsSoFar = node.steps

        var leaveFactor: Double
        var positionProbability: Double? = null
        var hold = 1.0
        val notes = ArrayList<String>()
        when {
            !legal -> leaveFactor = LEAVE_FLOOR
            gameWon -> leaveFactor = 1.0
            else -> {
                val afterSignature = layoutSignature(advanced.layout)
                val nominalNext = context.nextShotProbability(
                    advanced.layout, afterSignature, advanced.nextTargets, landing, cue.radiusMeters,
                )
                val (expectedNext, keptShare) = positionHold(
                    uncertainty, advanced, afterSignature, nominalNext, cue.radiusMeters, context,
                )
                positionProbability = (expectedNext * (1.0 - uncertainty.scratchProbability)).coerceIn(0.0, 1.0)
                hold = keptShare
                val leave = if (isLastPly) {
                    val leaf = context.leaf(advanced.layout, advanced.rules, afterSignature, landing, cue.radiusMeters)
                    leaf.note?.let { notes += it }
                    leaf.value
                } else {
                    nominalNext
                }
                val (rack, rackNotes) = rackFactor(advanced, afterSignature, context)
                notes += rackNotes
                leaveFactor = (LEAVE_FLOOR + (1.0 - LEAVE_FLOOR) * leave) * rack
            }
        }

        val control = controlFactor(
            cushions = if (gameWon) 0 else uncertainty.nominalTrajectory.cushionContacts,
            tip = tip,
            speedUnits = speedUnits,
        )
        val scaleInt = speedUnits.roundToInt().coerceIn(
            PlayerProfile.MIN_SPEED_SCALE,
            PlayerProfile.MAX_SPEED_SCALE,
        )

        val step = PlannedShotStep(
            stepNumber = context.firstStepNumber + ply - 1,
            targetBall = target,
            pocketId = pocket.id,
            pocketCenter = aim.pocketAimPos,
            compensatedAim = evaluation.compensatedAim,
            recommendedSpeedScale = scaleInt,
            cueSpeedMps = evaluation.cueSpeedMps,
            recommendedTip = tip,
            uncertainty = uncertainty,
            breakoutOpportunity = breakout,
            stepScore = stepProbability * control * hold,
            resultingLayout = advanced.layout,
            disturbedBalls = uncertainty.postShotSimulation?.disturbedBalls.orEmpty(),
            positionSuccessProbability = positionProbability,
            recommendedSpeedUnits = speedUnits,
        )

        return SearchNode(
            layout = advanced.layout,
            rules = advanced.rules,
            steps = stepsSoFar + step,
            cumulativeProbability = node.cumulativeProbability * stepProbability,
            bonusFactor = node.bonusFactor * control * hold,
            leaveFactor = leaveFactor,
            terminal = terminal,
            legal = node.legal && legal,
            notes = notes,
        )
    }

    /**
     * The next shot's make probability averaged over the non-scratching points of the landing cloud, and the share of
     * the nominal landing's value it keeps (at most 1; 1 when the nominal landing has no shot at all).
     */
    private fun positionHold(
        uncertainty: ShotUncertaintyResult,
        advanced: AdvancedState,
        signature: String,
        nominalNext: Double,
        cueRadius: Double,
        context: SearchContext,
    ): Pair<Double, Double> {
        val cloud = uncertainty.landingCloud
        if (cloud.isEmpty()) return nominalNext to 1.0
        val weights = ExecutionErrorModel.SIGMA_WEIGHTS
        var weightSum = 0.0
        var valueSum = 0.0
        for (i in cloud.indices) {
            if (uncertainty.landingCloudScratched.getOrElse(i) { false }) continue
            val w = weights.getOrElse(i) { 1.0 / cloud.size }
            weightSum += w
            valueSum += w * context.nextShotProbability(advanced.layout, signature, advanced.nextTargets, cloud[i], cueRadius)
        }
        val expected = if (weightSum > 1e-12) valueSum / weightSum else 0.0
        val kept = if (nominalNext > 1e-6) (expected / nominalNext).coerceIn(0.0, 1.0) else 1.0
        return expected to kept
    }

    /**
     * How hard the stroke is to play as asked, each cause once: the cue ball's cushions on the way to position
     * ([cushionFactor]; the game-winning shot needs no position and passes 0), a tip offset beyond
     * [COMFORTABLE_TIP_RATIO] toward the miscue limit, and speeds above [POWER_SPEED_UNITS] or below
     * [SOFT_SPEED_UNITS].
     */
    private fun controlFactor(cushions: Int, tip: TipOffset, speedUnits: Double): Double {
        val tipShare = ((tip.magnitudeRatio - COMFORTABLE_TIP_RATIO) / (TipOffset.MAX_SAFE_OFFSET_RATIO - COMFORTABLE_TIP_RATIO))
            .coerceIn(0.0, 1.0)
        val tipFactor = 1.0 - TIP_AT_LIMIT_PENALTY * tipShare
        val speedFactor = when {
            speedUnits > POWER_SPEED_UNITS -> 1.0 - POWER_PENALTY_PER_UNIT * (speedUnits - POWER_SPEED_UNITS)
            speedUnits < SOFT_SPEED_UNITS -> 1.0 - SOFT_PENALTY_PER_UNIT * (SOFT_SPEED_UNITS - speedUnits)
            else -> 1.0
        }.coerceAtLeast(0.70)
        return cushionFactor(cushions) * tipFactor * speedFactor
    }

    /** Route complexity: none or one cushion is natural; two cost 10 %, three 20 %, then 5 % per cushion. */
    private fun cushionFactor(cushions: Int): Double = when {
        cushions <= 1 -> 1.0
        cushions == 2 -> TWO_CUSHION_FACTOR
        else -> (THREE_CUSHION_FACTOR - EXTRA_CUSHION_STEP * (cushions - 3)).coerceAtLeast(MIN_CUSHION_FACTOR)
    }

    /**
     * Eight-ball rack term on the leave, with its explanations: [UNRESOLVED_PROBLEM_FACTOR] for each ball of the
     * player's group (or the 8) that is still a problem ball on the table this shot leaves, so that a break-out played
     * now, while the rest of the group is still there to play after it, is worth more than one put off past the
     * horizon. `1` in rotation games and on an open table.
     */
    private fun rackFactor(
        advanced: AdvancedState,
        signature: String,
        context: SearchContext,
    ): Pair<Double, List<String>> {
        val rules = advanced.rules
        if (rules.gameType != GameType.EIGHT_BALL) return 1.0 to emptyList()
        if (rules.eightBallGroup != EightBallGroup.SOLIDS && rules.eightBallGroup != EightBallGroup.STRIPES) {
            return 1.0 to emptyList()
        }
        var factor = 1.0
        val notes = ArrayList<String>()
        for (problem in context.problemBalls(advanced.layout, rules, signature)) {
            factor *= UNRESOLVED_PROBLEM_FACTOR
            notes += "Ball #${problem.ballNumber} is still tied up when the plan ends; breaking it out earlier, while " +
                "other balls of the group are left to play, scores higher."
        }
        return factor to notes
    }

    /**
     * The break-out a shot really makes: a cue-ball path that passes a problem ball ([ProblemBallAnalyzer.checkTrajectoryBreakout])
     * and moves that ball or one of the balls tying it up in the simulation.
     */
    private fun truthfulBreakout(
        uncertainty: ShotUncertaintyResult,
        target: Ball,
        pocket: PocketSpec,
        problemBalls: List<ProblemBallReport>,
        cueRadius: Double,
    ): BreakoutOpportunity? {
        val breakout = ProblemBallAnalyzer.checkTrajectoryBreakout(
            trajectory = uncertainty.nominalTrajectory,
            shotTargetBallNumber = target.number,
            shotPocketId = pocket.id,
            problemBalls = problemBalls,
            ballRadiusMeters = cueRadius,
        ) ?: return null
        val report = problemBalls.firstOrNull { it.ballNumber == breakout.problemBallNumber } ?: return null
        val involved = report.blockingBallNumbers.toSet() + report.ballNumber
        val moved = uncertainty.postShotSimulation?.disturbedBalls.orEmpty().any { it.ballNumber in involved }
        return breakout.takeIf { moved }
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

    /**
     * Simulates one candidate stroke. Unless [allowHighScratch] is set, a shot is only kept when the nominal
     * simulation pots the target in the intended pocket, drops no other ball (no plan counts on a carom, and in 8-ball
     * an early 8 loses), the nominal cue ball does not scratch, and the scratch probability stays below
     * [MAX_SCRATCH_PROBABILITY].
     */
    private fun evaluateShot(
        layout: TableLayout,
        cue: Ball,
        target: Ball,
        pocket: PocketSpec,
        aim: AimGeometry,
        speedUnits: Double,
        tip: TipOffset,
        profile: PlayerProfile,
        allowHighScratch: Boolean = false,
    ): ShotEvaluation? {
        val cueSpeed = profile.speedForUnits(speedUnits)
        val preImpact = SpinPhysics.computePreImpactCueState(
            cueSpeedMps = cueSpeed,
            tip = tip,
            cueToGhostDistanceMeters = aim.cueToGhostDistanceMeters,
            table = layout.table,
        )
        if (!allowHighScratch && preImpact.state == SpinPhysics.ImpactSpinState.STOPPED_SHORT) return null
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
        val simulation = uncertainty.postShotSimulation
        val pottedAsCalled = uncertainty.targetBallPotted &&
            (simulation?.targetBallTrajectory?.pocketedIn ?: pocket.id) == pocket.id
        val otherBallDropped = simulation?.disturbedBalls?.any { it.pocketedIn != null } == true
        val nominalScratch = uncertainty.nominalTrajectory.hitsScratchPocket != null
        val legal = pottedAsCalled && !nominalScratch && !otherBallDropped
        if (!allowHighScratch && !legal) return null
        if (!allowHighScratch && uncertainty.scratchProbability >= MAX_SCRATCH_PROBABILITY) return null
        return ShotEvaluation(compensated, uncertainty, cueSpeed, preImpact, legal)
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

    /**
     * Rules state after legally potting [target] (assigns the 8-ball group on an open table).
     */
    fun rulesAfterPotting(rules: GameRulesState, target: Ball): GameRulesState {
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
        val targetPotted = simulation?.targetBallPotted != false && simulation?.cueBallStoppedBeforeImpact != true
        val nextRules = if (targetPotted) rulesAfterPotting(rules, target) else rules
        val nextTargets = when {
            !targetPotted -> emptyList()
            isGameOver(rules, target) -> emptyList()
            else -> nextRules.legalTargetBalls(nextLayout)
        }
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
        val hypotheticalAfterFirst = if (first.uncertainty.targetBallPotted) {
            first.resultingLayout ?: TableLayout(
                initialLayout.table,
                initialLayout.balls.filter { it.number != first.targetBall.number },
            )
        } else {
            val base = first.resultingLayout ?: initialLayout
            TableLayout(base.table, base.balls.filter { it.number != first.targetBall.number })
        }
        val targetNumbers = if (steps.size >= 2) {
            steps.drop(1).take(2).map { it.targetBall.number }
        } else if (isGameOver(initialRules, first.targetBall)) {
            emptyList()
        } else {
            rulesAfterPotting(initialRules, first.targetBall)
                .legalTargetBalls(hypotheticalAfterFirst)
                .take(2)
                .map { it.number }
        }
        if (targetNumbers.isEmpty()) return null
        return HeatZoneGenerator.computeForNextBalls(
            layout = hypotheticalAfterFirst,
            targetBallNumbers = targetNumbers,
            playerProfile = playerProfile,
            cols = 32,
            rows = 16,
            preferredPocketId = steps.getOrNull(1)?.pocketId,
        )
    }

    private fun percent(value: Double): Int = (value * 100.0).roundToInt().coerceIn(0, 100)

    private fun describeTip(tip: TipOffset): String = tip.describeSpinLabel(short = false).lowercase()

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
            val pots = step.uncertainty.targetBallPotted && step.uncertainty.postShotSimulation?.cueBallStoppedBeforeImpact != true
            val posSummary = step.positionSuccessProbability?.let { posProb ->
                ", ${percent(posProb)}% position (${ShotDifficultyTier.fromProbability(posProb).label})"
            } ?: if (pots) ", final ball" else ""
            val speedLabel = PlayerProfile.formatSpeedUnits(step.recommendedSpeedUnits)
            reasons += "Step ${step.stepNumber}: pot ball #$ballNumber in the ${step.pocketId.displayName} pocket " +
                "at speed $speedLabel/10, ${percent(step.uncertainty.makeProbability)}% to make (${step.makeDifficulty.label})$posSummary."

            val nextStep = steps.getOrNull(index + 1)
            val spinText = describeTip(step.recommendedTip)
            val postSim = step.uncertainty.postShotSimulation
            reasons += when {
                postSim?.cueBallStoppedBeforeImpact == true ->
                    "Stroke is too soft (${speedLabel}/10): the cue ball stops short before reaching ball #$ballNumber."
                !step.uncertainty.targetBallPotted ->
                    "Collision transfer at speed ${speedLabel}/10 leaves ball #$ballNumber short of the ${step.pocketId.displayName} pocket."
                nextStep != null && isGameOver(rules, nextStep.targetBall) ->
                    "Use $spinText to set up a clean, easy final shot on ball #${nextStep.targetBall.number}."
                nextStep != null ->
                    "Use $spinText so the cue ball lands in the high-percentage zone for ball #${nextStep.targetBall.number}."
                node.terminal ->
                    "Use $spinText; this is the last ball, so only a safe finish matters."
                else ->
                    "Use $spinText to keep the cue ball in a good zone for the next ball."
            }

            if (rules.gameType == GameType.EIGHT_BALL && step.targetBall.isEightBall) {
                reasons += "Your group is cleared at this point, so ball #8 becomes the legal target."
            }

            val breakout = step.breakoutOpportunity
            if (breakout != null) {
                val insurance = breakout.insuranceBallNumber
                reasons += if (insurance != null) {
                    "Step ${step.stepNumber} sends the cue ball into problem ball #${breakout.problemBallNumber}'s cluster " +
                        "to free it; ball #$insurance is the open insurance ball if it stays tied up."
                } else {
                    "Step ${step.stepNumber} sends the cue ball into problem ball #${breakout.problemBallNumber}'s cluster to free it."
                }
            }

            rules = rulesAfterPotting(rules, step.targetBall)
        }
        reasons += node.notes

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
                        layout, cue, target, pocket, aim, GREEDY_SPEED_SCALE.toDouble(), centerTip, profile,
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
