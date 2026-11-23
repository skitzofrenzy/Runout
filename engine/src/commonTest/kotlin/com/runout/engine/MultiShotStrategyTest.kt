package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.TipOffset
import com.runout.engine.physics.TrajectoryPath
import com.runout.engine.planner.HeatZoneGenerator
import com.runout.engine.planner.MultiShotPlanner
import com.runout.engine.planner.ProblemBallAnalyzer
import com.runout.engine.planner.ProblemBallReason
import com.runout.engine.planner.ShotDifficultyTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiShotStrategyTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters

    private fun ball(number: Int, x: Double, y: Double): Ball =
        Ball(number = number, position = Vec2(x, y), radiusMeters = r)

    private fun layoutOf(vararg balls: Ball): TableLayout = TableLayout(table = table, balls = balls.toList())

    /** Ball 3 frozen to the bottom rail and boxed in by balls 4, 5 and 6; ball 7 is open in the middle. */
    private fun boxedRailLayout(): TableLayout = layoutOf(
        ball(0, 1.80, 0.90),
        ball(3, 0.90, r + 0.001),
        ball(4, 0.90 - 0.0585, r + 0.001),
        ball(5, 0.90 + 0.0585, r + 0.001),
        ball(6, 0.90, r + 0.001 + 0.0585),
        ball(7, 1.80, 0.50),
    )

    // ---------------------------------------------------------------------------------------------
    // HeatZoneGenerator
    // ---------------------------------------------------------------------------------------------

    @Test
    fun heatZoneGridHasExpectedShapeRangeAndBallOverlapCells() {
        // Cell (col 8, row 3) of a 16 x 8 grid is centred at (8.5, 3.5) * 0.15875 m.
        val blockedCenter = Vec2(8.5 * 0.15875, 3.5 * 0.15875)
        val layout = layoutOf(
            ball(0, 0.60, 0.635),
            ball(1, blockedCenter.x, blockedCenter.y),
            ball(2, 2.10, 0.90),
        )

        val map = HeatZoneGenerator.computeForNextBalls(layout, listOf(1, 2))

        assertEquals(16, map.cols)
        assertEquals(8, map.rows)
        assertEquals(128, map.cells.size)
        assertEquals(listOf(1, 2), map.targetBallNumbers)
        assertTrue(map.cells.all { it.value in 0.0..1.0 }, "Heat values must stay in 0..1")
        assertEquals(0.0, map.valueAt(blockedCenter), 1e-12, "Cell under an existing ball must be zero")

        val best = assertNotNull(map.bestLeaveCell())
        assertTrue(best.value > 0.05, "Some open cell should offer a workable shot, was ${best.value}")
        assertEquals(map.cells.maxOf { it.value }, best.value, 1e-12)
        assertEquals(best.value, map.valueAt(best.centerMeters), 1e-12)
    }

    @Test
    fun heatZoneValueAtClampsOutsidePositionsAndEmptyTargetsAreFlat() {
        val layout = layoutOf(ball(0, 0.60, 0.635), ball(1, 1.50, 0.635))
        val map = HeatZoneGenerator.computeForNextBalls(layout, listOf(1), cols = 8, rows = 4)

        assertEquals(map.cells.first().value, map.valueAt(Vec2(-3.0, -3.0)), 1e-12)
        assertEquals(map.cells.last().value, map.valueAt(Vec2(50.0, 50.0)), 1e-12)

        val flat = HeatZoneGenerator.computeForNextBalls(layout, emptyList(), cols = 8, rows = 4)
        assertTrue(flat.cells.all { it.value == 0.0 })
        assertEquals(0.0, assertNotNull(flat.bestLeaveCell()).value, 1e-12)
    }

    // ---------------------------------------------------------------------------------------------
    // ProblemBallAnalyzer
    // ---------------------------------------------------------------------------------------------

    @Test
    fun ballSurroundedByRingHasNoClearPocketAndIsClustered() {
        val centre = Vec2(1.27, 0.635)
        val ringDistance = 2.02 * r
        val ring = (0 until 6).map { i ->
            val angle = i * PI_OVER_3
            ball(
                number = 10 + i,
                x = centre.x + ringDistance * kotlin.math.cos(angle),
                y = centre.y + ringDistance * kotlin.math.sin(angle),
            )
        }
        val layout = TableLayout(table, listOf(ball(0, 0.5, 0.635), ball(5, centre.x, centre.y)) + ring)

        val report = assertNotNull(ProblemBallAnalyzer.analyzeProblemBalls(layout).firstOrNull { it.ballNumber == 5 })

        assertEquals(0, report.availablePocketCount)
        assertTrue(ProblemBallReason.NO_CLEAR_POCKET in report.reasons)
        assertTrue(ProblemBallReason.CLUSTERED_NEIGHBOURS in report.reasons)
        assertEquals(listOf(10, 11, 12, 13, 14, 15), report.blockingBallNumbers)
    }

    @Test
    fun touchingPairIsClusteredButStillHasClearPockets() {
        val layout = layoutOf(
            ball(0, 1.80, 0.90),
            ball(1, 1.00, 0.60),
            ball(2, 1.00, 0.60 + 2.05 * r),
        )

        val reports = ProblemBallAnalyzer.analyzeProblemBalls(layout)
        val first = assertNotNull(reports.firstOrNull { it.ballNumber == 1 })

        assertTrue(ProblemBallReason.CLUSTERED_NEIGHBOURS in first.reasons)
        assertFalse(ProblemBallReason.NO_CLEAR_POCKET in first.reasons)
        assertFalse(ProblemBallReason.FROZEN_TO_RAIL in first.reasons)
        assertEquals(listOf(2), first.blockingBallNumbers)
        assertTrue(first.availablePocketCount > 0)
        assertEquals(reports.map { it.ballNumber }, reports.map { it.ballNumber }.sorted())
    }

    @Test
    fun railFrozenBoxedBallIsFlaggedAndOpenBallBecomesInsurance() {
        val layout = boxedRailLayout()

        val reports = ProblemBallAnalyzer.analyzeProblemBalls(layout)
        val frozen = assertNotNull(reports.firstOrNull { it.ballNumber == 3 })

        assertTrue(ProblemBallReason.FROZEN_TO_RAIL in frozen.reasons)
        assertTrue(ProblemBallReason.NO_CLEAR_POCKET in frozen.reasons)
        assertTrue(ProblemBallReason.CLUSTERED_NEIGHBOURS in frozen.reasons)
        assertEquals(7, frozen.recommendedInsuranceBallNumber)
        assertTrue(reports.none { it.ballNumber == 7 }, "The open insurance ball is not a problem ball")
    }

    @Test
    fun eightBallGroupLimitsInspectionToOwnGroupAndEightBall() {
        // Same boxed cluster as above, but the ball on top of ball 3 is a stripe (9).
        val layout = layoutOf(
            ball(0, 1.80, 0.90),
            ball(3, 0.90, r + 0.001),
            ball(4, 0.90 - 0.0585, r + 0.001),
            ball(5, 0.90 + 0.0585, r + 0.001),
            ball(9, 0.90, r + 0.001 + 0.0585),
            ball(7, 1.80, 0.50),
        )
        val solidsRules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS)
        val stripesRules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.STRIPES)

        val solidReports = ProblemBallAnalyzer.analyzeProblemBalls(layout, solidsRules)
        val stripeReports = ProblemBallAnalyzer.analyzeProblemBalls(layout, stripesRules)

        assertTrue(solidReports.none { it.ballNumber == 9 }, "Stripe must not be inspected for a solids player")
        assertTrue(solidReports.any { it.ballNumber == 3 })
        assertEquals(listOf(9), stripeReports.map { it.ballNumber })
    }

    @Test
    fun trajectoryBreakoutDetectsProblemBallPassByAndSkipsShotTarget() {
        val layout = boxedRailLayout()
        val problems = ProblemBallAnalyzer.analyzeProblemBalls(layout)
        val nearBall6 = TrajectoryPath(
            spin = CueBallSpinPreset.STUN,
            points = listOf(Vec2(0.50, 0.50), Vec2(0.90, 0.12)),
            initialDeflectionAngleRad = 0.0,
            hitsScratchPocket = null,
        )

        val breakout = assertNotNull(
            ProblemBallAnalyzer.checkTrajectoryBreakout(
                trajectory = nearBall6,
                shotTargetBallNumber = 7,
                shotPocketId = PocketId.FOOT_LEFT,
                problemBalls = problems,
                ballRadiusMeters = r,
            ),
        )
        assertEquals(6, breakout.problemBallNumber)
        assertEquals(7, breakout.triggerShotTargetBallNumber)
        assertEquals(PocketId.FOOT_LEFT, breakout.triggerPocketId)
        assertEquals(7, breakout.insuranceBallNumber)
        assertTrue(breakout.closestApproachMeters <= 2.3 * r)

        assertNull(
            ProblemBallAnalyzer.checkTrajectoryBreakout(nearBall6, 6, PocketId.FOOT_LEFT, problems, r),
            "Path next to the shot's own target ball is not a breakout",
        )

        val farAway = nearBall6.copy(points = listOf(Vec2(1.5, 1.0), Vec2(2.0, 1.1)))
        assertNull(ProblemBallAnalyzer.checkTrajectoryBreakout(farAway, 7, PocketId.FOOT_LEFT, problems, r))
    }

    // ---------------------------------------------------------------------------------------------
    // MultiShotPlanner.planRunout
    // ---------------------------------------------------------------------------------------------

    private fun openNineBallLayout(): TableLayout = layoutOf(
        ball(0, 0.60, 0.635),
        ball(1, 1.20, 0.40),
        ball(2, 1.60, 0.90),
        ball(3, 2.00, 0.50),
    )

    @Test
    fun planRunoutLooksThreeShotsAheadWithConsistentSteps() {
        val plans = MultiShotPlanner.planRunout(openNineBallLayout(), horizonShots = 3)

        assertTrue(plans.isNotEmpty(), "Expected at least one plan on an open table")
        val best = plans.first()
        assertEquals(3, plans.maxOf { it.steps.size })
        assertTrue(best.steps.size in 1..3)
        assertEquals((1..best.steps.size).toList(), best.steps.map { it.stepNumber })
        assertEquals(listOf(1, 2, 3).take(best.steps.size), best.steps.map { it.targetBall.number })
        assertTrue(best.cumulativeRunoutProbability in 0.0..1.0)
        assertTrue(best.expectedValueScore >= 0.0)
        assertTrue(best.steps.all { it.recommendedSpeedScale in 1..10 && it.cueSpeedMps > 0.0 })
        assertTrue(plans.zipWithNext().all { (a, b) -> a.expectedValueScore >= b.expectedValueScore - 1e-12 })
        assertTrue(best.explanationReasons.isNotEmpty())
        assertTrue(best.explanationReasons.first().startsWith("Step 1"))

        if (best.steps.size >= 2) {
            val heat = assertNotNull(best.nextBallHeatZone)
            assertEquals(best.steps.drop(1).take(2).map { it.targetBall.number }, heat.targetBallNumbers)
        }
    }

    @Test
    fun planRunoutHonoursShortHorizonAndCapsLongHorizon() {
        val short = MultiShotPlanner.planRunout(openNineBallLayout(), horizonShots = 2)
        assertTrue(short.isNotEmpty())
        assertTrue(short.all { it.steps.size <= 2 })
        assertEquals(2, short.maxOf { it.steps.size })

        val sixBalls = layoutOf(
            ball(0, 0.50, 0.635),
            ball(1, 0.95, 0.35),
            ball(2, 1.25, 0.95),
            ball(3, 1.60, 0.40),
            ball(4, 1.95, 0.85),
            ball(5, 2.20, 0.35),
            ball(6, 2.25, 1.00),
        )
        val capped = MultiShotPlanner.planRunout(sixBalls, horizonShots = 9)
        assertTrue(capped.isNotEmpty())
        assertTrue(capped.all { it.steps.size <= 5 }, "Non-deep horizon is capped at five shots")
    }

    @Test
    fun deepAnalysisPlansTheWholeShortRack() {
        val layout = layoutOf(
            ball(0, 0.60, 0.635),
            ball(1, 1.10, 0.40),
            ball(2, 1.50, 0.90),
            ball(3, 1.90, 0.45),
            ball(4, 2.20, 0.85),
        )

        val plans = MultiShotPlanner.planRunout(layout, horizonShots = 1, deepAnalysis = true)

        assertTrue(plans.isNotEmpty())
        assertEquals(4, plans.maxOf { it.steps.size }, "Deep mode ignores horizonShots and covers every object ball")
        assertTrue(plans.all { it.cumulativeRunoutProbability in 0.0..1.0 })
    }

    @Test
    fun customFirstShotTipAndSpeedAreRespected() {
        val tip = TipOffset(sideRatio = 0.0, verticalRatio = 0.35)

        val plans = MultiShotPlanner.planRunout(
            layout = openNineBallLayout(),
            horizonShots = 2,
            customFirstShotTip = tip,
            customFirstShotSpeedScale = 4,
        )

        assertTrue(plans.isNotEmpty())
        for (plan in plans) {
            val first = plan.steps.first()
            assertEquals(4, first.recommendedSpeedScale)
            assertEquals(tip, first.recommendedTip)
        }
    }

    @Test
    fun eightBallPlanTargetsTheEightBallOnceGroupIsCleared() {
        val layout = layoutOf(
            ball(0, 0.70, 0.635),
            ball(2, 1.30, 0.50),
            ball(8, 1.90, 0.80),
            ball(9, 1.70, 0.25),
        )
        val rules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS)

        val plans = MultiShotPlanner.planRunout(layout, rules, horizonShots = 2)

        assertTrue(plans.isNotEmpty())
        assertTrue(plans.all { plan -> plan.steps.none { it.targetBall.number == 9 } })
        assertTrue(
            plans.any { plan -> plan.steps.map { it.targetBall.number } == listOf(2, 8) },
            "Expected a plan that pots the last solid and then the 8-ball",
        )
        val withEight = plans.first { plan -> plan.steps.map { it.targetBall.number } == listOf(2, 8) }
        assertTrue(withEight.explanationReasons.any { "#8" in it })
    }

    @Test
    fun planReportsProblemBallsAndInsuranceInExplanations() {
        val layout = boxedRailLayout()
        val rules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN)

        val plans = MultiShotPlanner.planRunout(layout, rules, horizonShots = 2)

        assertTrue(plans.isNotEmpty())
        val plan = plans.first()
        assertTrue(plan.problemBalls.any { it.ballNumber == 3 })
        assertTrue(plan.explanationReasons.any { it.startsWith("Problem ball #3") })
        assertTrue(plan.explanationReasons.any { "insurance" in it })
    }

    @Test
    fun planRunoutHandlesDegenerateLayouts() {
        assertTrue(MultiShotPlanner.planRunout(layoutOf(ball(1, 1.0, 0.6))).isEmpty(), "No cue ball")
        assertTrue(MultiShotPlanner.planRunout(layoutOf(ball(0, 1.0, 0.6))).isEmpty(), "No object balls")
    }

    // ---------------------------------------------------------------------------------------------
    // MultiShotPlanner.evaluateAgainstGreedyBaseline
    // ---------------------------------------------------------------------------------------------

    @Test
    fun beamPlannerOutperformsGreedyBaselineOverSelfPlayRacks() {
        val rules = GameRulesState(GameType.NINE_BALL)
        val racks = listOf(
            openNineBallLayout() to rules,
            layoutOf(
                ball(0, 0.55, 0.30),
                ball(1, 1.00, 0.75),
                ball(2, 1.45, 0.35),
                ball(3, 1.95, 0.95),
            ) to rules,
            layoutOf(
                ball(0, 2.00, 0.30),
                ball(1, 1.55, 0.60),
                ball(2, 1.05, 0.95),
                ball(3, 0.60, 0.45),
            ) to rules,
            layoutOf(
                ball(0, 0.70, 1.00),
                ball(1, 1.30, 0.80),
                ball(2, 1.75, 0.45),
                ball(3, 2.15, 0.85),
            ) to rules,
        )

        val result = MultiShotPlanner.evaluateAgainstGreedyBaseline(racks)

        assertEquals(racks.size, result.rackCount)
        assertTrue(result.beamPlannerRunoutRate in 0.0..1.0)
        assertTrue(result.greedyBaselineRunoutRate in 0.0..1.0)
        assertTrue(result.beamPlannerRunoutRate > 0.0)
        assertTrue(
            result.beamPlannerRunoutRate > result.greedyBaselineRunoutRate,
            "Beam ${result.beamPlannerRunoutRate} must beat greedy ${result.greedyBaselineRunoutRate}",
        )
        assertTrue(result.relativeImprovementFraction > 0.0)
        assertEquals(
            (result.beamPlannerRunoutRate - result.greedyBaselineRunoutRate) /
                result.greedyBaselineRunoutRate.coerceAtLeast(1e-6),
            result.relativeImprovementFraction,
            1e-9,
        )
    }

    @Test
    fun selfPlayWithNoRacksReturnsZeroedResult() {
        val result = MultiShotPlanner.evaluateAgainstGreedyBaseline(emptyList())

        assertEquals(0, result.rackCount)
        assertEquals(0.0, result.beamPlannerRunoutRate)
        assertEquals(0.0, result.greedyBaselineRunoutRate)
        assertEquals(0.0, result.relativeImprovementFraction)
    }

    // ---------------------------------------------------------------------------------------------
    // Position difficulty (independent from Make %)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun shotDifficultyTierThresholdsMapCorrectly() {
        assertEquals(ShotDifficultyTier.EASY, ShotDifficultyTier.fromProbability(0.65))
        assertEquals(ShotDifficultyTier.EASY, ShotDifficultyTier.fromProbability(0.92))
        assertEquals(ShotDifficultyTier.MEDIUM, ShotDifficultyTier.fromProbability(0.649))
        assertEquals(ShotDifficultyTier.MEDIUM, ShotDifficultyTier.fromProbability(0.35))
        assertEquals(ShotDifficultyTier.HARD, ShotDifficultyTier.fromProbability(0.349))
        assertEquals(ShotDifficultyTier.HARD, ShotDifficultyTier.fromProbability(0.05))
    }

    @Test
    fun terminalShotsHaveNullPositionDifficultyWhileNonTerminalShotsPopulateIt() {
        val nineBallTerminal = layoutOf(
            ball(0, 1.85, 0.55),
            ball(9, 2.28, 0.24),
        )
        val nineTerminalPlan = MultiShotPlanner.planRunout(
            layout = nineBallTerminal,
            rulesState = GameRulesState(GameType.NINE_BALL),
        ).firstOrNull()
        val nineTerminalStep = assertNotNull(nineTerminalPlan).steps.single()
        assertNull(nineTerminalStep.positionSuccessProbability)
        assertNull(nineTerminalStep.positionDifficulty)
        assertTrue(nineTerminalPlan.explanationReasons.first().endsWith("final ball."))

        val eightBallTerminal = layoutOf(
            ball(0, 1.85, 0.55),
            ball(8, 2.28, 0.24),
        )
        val eightTerminalPlan = MultiShotPlanner.planRunout(
            layout = eightBallTerminal,
            rulesState = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.EIGHT_BALL),
        ).firstOrNull()
        val eightTerminalStep = assertNotNull(eightTerminalPlan).steps.single()
        assertNull(eightTerminalStep.positionSuccessProbability)
        assertNull(eightTerminalStep.positionDifficulty)

        val multiPlan = MultiShotPlanner.planRunout(openNineBallLayout(), horizonShots = 3).first()
        assertTrue(multiPlan.steps.size >= 2)
        val step1 = multiPlan.steps.first()
        val step1Pos = assertNotNull(step1.positionSuccessProbability)
        assertTrue(step1Pos in 0.02..0.98)
        assertNotNull(step1.positionDifficulty)
        assertTrue(multiPlan.explanationReasons.first().contains("position (${step1.positionDifficulty?.label})"))
    }

    @Test
    fun easyOpenLeaveScoresSignificantlyHigherThanDemandingMultiRailDrawLeave() {
        val easyLayout = layoutOf(
            ball(0, 1.92, 0.60),
            ball(1, 2.28, 0.24),
            ball(9, 2.02, 0.90),
        )
        val easyPlan = assertNotNull(
            MultiShotPlanner.planRunout(
                layout = easyLayout,
                rulesState = GameRulesState(GameType.NINE_BALL),
                horizonShots = 2,
            ).firstOrNull(),
        )
        val easyStep = easyPlan.steps.first()
        val easyPos = assertNotNull(easyStep.positionSuccessProbability)
        assertTrue(easyPos >= 0.65, "Open short stun leave should be Easy (>= 0.65), was $easyPos")
        assertEquals(ShotDifficultyTier.EASY, easyStep.positionDifficulty)

        // Same easy pot, but forced max speed + full side+draw english leaving long across-table shape behind blockers
        val demandingLayout = layoutOf(
            ball(0, 1.92, 0.60),
            ball(1, 2.28, 0.24),
            ball(5, 1.15, 0.55),
            ball(9, 0.38, 0.95),
        )
        val hardPlan = assertNotNull(
            MultiShotPlanner.planRunout(
                layout = demandingLayout,
                rulesState = GameRulesState(GameType.NINE_BALL),
                horizonShots = 2,
                customFirstShotTip = TipOffset(sideRatio = 0.45, verticalRatio = -0.45),
                customFirstShotSpeedScale = 9,
                customFirstShotTargetBallNumber = 1,
            ).firstOrNull(),
        )
        val hardStep = hardPlan.steps.first()
        val hardPos = assertNotNull(hardStep.positionSuccessProbability)
        assertTrue(
            easyPos > hardPos + 0.12,
            "Easy position ($easyPos) must materially exceed demanding draw/side position ($hardPos)",
        )
    }

    @Test
    fun makeProbabilityAndPositionProbabilityCanDivergeIndependently() {
        // Ball 1 is a short hanger makeable > 80%, while ball 9 is tucked cleanly only from across the table
        // and draw + english sends the cue ball awkwardly away from clean position.
        val layout = layoutOf(
            ball(0, 1.92, 0.60),
            ball(1, 2.34, 0.18),
            ball(8, 1.95, 0.68),
            ball(9, 1.25, 0.88),
        )
        val plan = assertNotNull(
            MultiShotPlanner.planRunout(
                layout = layout,
                rulesState = GameRulesState(GameType.NINE_BALL),
                horizonShots = 2,
                customFirstShotTip = TipOffset(sideRatio = -0.45, verticalRatio = -0.45),
                customFirstShotSpeedScale = 10,
                customFirstShotTargetBallNumber = 1,
            ).firstOrNull(),
        )
        val step = plan.steps.first()
        val makeProb = step.uncertainty.makeProbability
        val posProb = assertNotNull(step.positionSuccessProbability)
        assertTrue(makeProb >= 0.70, "Short hanger make % should remain high even when position is tough, was $makeProb")
        assertTrue(
            makeProb - posProb >= 0.20,
            "Position % ($posProb) should diverge cleanly below easy hanger Make % ($makeProb)",
        )
    }

    @Test
    fun lowerAimErrorPlayerProfileEarnsHigherPositionProbabilityOnNonTrivialShot() {
        val layout = layoutOf(
            ball(0, 0.80, 0.95),
            ball(1, 1.50, 0.55),
            ball(9, 1.95, 0.95),
        )
        val proStep = assertNotNull(
            MultiShotPlanner.planRunout(
                layout = layout,
                rulesState = GameRulesState(GameType.NINE_BALL),
                playerProfile = PlayerProfile(aimErrorStdRad = 0.008),
                horizonShots = 2,
                customFirstShotTargetBallNumber = 1,
            ).firstOrNull(),
        ).steps.first()
        val beginnerStep = assertNotNull(
            MultiShotPlanner.planRunout(
                layout = layout,
                rulesState = GameRulesState(GameType.NINE_BALL),
                playerProfile = PlayerProfile(aimErrorStdRad = 0.032),
                horizonShots = 2,
                customFirstShotTargetBallNumber = 1,
            ).firstOrNull(),
        ).steps.first()

        val proPos = assertNotNull(proStep.positionSuccessProbability)
        val begPos = assertNotNull(beginnerStep.positionSuccessProbability)
        assertTrue(
            proPos > begPos,
            "Lower aim-error profile ($proPos) must achieve higher position probability than high-error profile ($begPos)",
        )
    }

    private companion object {
        const val PI_OVER_3: Double = 1.0471975511965976
    }
}
