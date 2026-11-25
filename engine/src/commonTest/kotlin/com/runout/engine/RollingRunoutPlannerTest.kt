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
import com.runout.engine.physics.DisplacedBallPath
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.MultiShotPlanner
import com.runout.engine.planner.PlanCache
import com.runout.engine.planner.PlannedShotStep
import com.runout.engine.planner.RollingPlanState
import com.runout.engine.planner.RollingRunoutPlanner
import com.runout.engine.planner.RunoutPlan
import com.runout.engine.planner.StepOverride
import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

// -------------------------------------------------------------------------------------------------
// Fixtures
//
// The planner's stroke choices shift whenever the physics model is refined, so no test here hard-codes a tip,
// speed or pocket the planner "should" pick. Layouts come from short fixed lists; the first one that supports the
// scenario is used, and strokes that a scenario needs are found by searching the planner at test time.
// -------------------------------------------------------------------------------------------------

private val fixtureTable = TableSpec.nineFootPool()
private val fixtureProfile = PlayerProfile()
private val nineBallRules = GameRulesState(GameType.NINE_BALL)

private fun layoutFrom(numbers: List<Int>, xy: DoubleArray): TableLayout = TableLayout(
    fixtureTable,
    numbers.mapIndexed { i, n ->
        Ball(number = n, position = Vec2(xy[2 * i], xy[2 * i + 1]), radiusMeters = fixtureTable.ballRadiusMeters)
    },
)

private val nineBallNumbers = (0..9).toList()
private val lastThreeNumbers = listOf(0, 7, 8, 9)
private val eightBallNumbers = listOf(0, 2, 3, 8, 9, 10)

/** Cue ball and balls 1-9 spread over the table with open pots, listed in order of preference. */
private val nineBallCandidates: List<TableLayout> = listOf(
    doubleArrayOf(1.28, 1.02, 1.59, 1.02, 1.13, 0.77, 1.44, 0.38, 2.28, 0.99, 2.14, 0.35, 2.27, 0.60, 0.78, 0.76, 0.41, 0.30, 1.76, 0.39),
    doubleArrayOf(0.71, 0.99, 0.62, 0.72, 0.85, 0.32, 1.46, 0.27, 1.55, 0.90, 0.99, 0.59, 2.20, 0.59, 1.29, 0.70, 1.89, 0.72, 2.27, 1.07),
    doubleArrayOf(1.53, 0.59, 1.81, 0.46, 1.95, 1.01, 2.14, 0.44, 0.86, 0.77, 0.55, 0.85, 0.48, 0.26, 2.25, 0.78, 1.25, 0.55, 0.25, 0.99),
    doubleArrayOf(0.65, 0.38, 0.35, 1.04, 1.90, 0.48, 1.66, 0.65, 1.45, 0.29, 1.82, 0.95, 1.13, 0.25, 0.66, 0.68, 2.24, 0.60, 1.30, 0.97),
    doubleArrayOf(0.62, 0.43, 0.57, 0.85, 1.61, 0.62, 2.04, 0.93, 2.29, 0.57, 1.46, 0.88, 0.33, 0.50, 0.29, 0.88, 1.02, 0.97, 1.18, 0.67),
    doubleArrayOf(1.69, 0.91, 2.03, 0.71, 1.97, 0.99, 2.28, 0.53, 1.11, 0.29, 0.56, 0.53, 0.77, 0.34, 1.16, 0.61, 0.61, 0.89, 1.35, 0.95),
    doubleArrayOf(1.43, 0.30, 2.05, 0.96, 1.47, 1.03, 0.97, 0.70, 0.27, 0.77, 0.79, 0.35, 1.17, 0.49, 0.75, 1.02, 2.12, 0.64, 1.53, 0.74),
).map { layoutFrom(nineBallNumbers, it) }

/** Cue ball with only the 7, 8 and 9 left: a three-shot rack. */
private val lastThreeCandidates: List<TableLayout> = listOf(
    doubleArrayOf(1.28, 1.02, 1.44, 0.38, 2.28, 0.99, 2.14, 0.35),
    doubleArrayOf(0.86, 0.74, 0.63, 1.04, 1.05, 0.24, 1.32, 0.83),
    doubleArrayOf(1.78, 0.76, 2.27, 0.21, 1.45, 1.05, 1.15, 0.81),
    doubleArrayOf(1.69, 0.91, 2.03, 0.71, 1.11, 0.29, 0.56, 0.53),
    doubleArrayOf(1.53, 0.59, 1.95, 1.01, 2.14, 0.44, 0.86, 0.77),
).map { layoutFrom(lastThreeNumbers, it) }

/** Open 8-ball tables with solids 2 and 3, the 8, and stripes 9 and 10. */
private val eightBallCandidates: List<TableLayout> = listOf(
    doubleArrayOf(1.28, 1.02, 1.44, 0.38, 2.28, 0.99, 2.14, 0.35, 0.78, 0.76, 0.41, 0.30),
    doubleArrayOf(1.03, 0.41, 0.84, 0.85, 2.10, 0.98, 2.11, 0.48, 0.38, 0.35, 1.42, 1.01),
    doubleArrayOf(1.12, 0.45, 0.41, 0.85, 0.54, 0.30, 2.01, 1.04, 1.54, 0.45, 1.07, 0.81),
    doubleArrayOf(1.33, 0.45, 2.11, 0.39, 1.10, 0.97, 0.69, 0.53, 0.91, 0.21, 1.87, 0.85),
    doubleArrayOf(1.29, 1.02, 0.69, 0.96, 1.93, 1.01, 1.11, 0.59, 1.50, 0.61, 0.33, 0.24),
).map { layoutFrom(eightBallNumbers, it) }

private fun nineBallStart(layout: TableLayout, horizon: Int = 3): RollingPlanState =
    RollingRunoutPlanner.start(layout, nineBallRules, fixtureProfile, horizon)

/** True when a 3-shot window can be walked from [layout] for three accepted shots without a refusal. */
private fun nineBallWalksCleanly(layout: TableLayout): Boolean {
    var state = nineBallStart(layout)
    repeat(3) {
        val window = state.window ?: return false
        if (window.steps.size != 3 || !window.steps.all { it.uncertainty.targetBallPotted }) return false
        state = RollingRunoutPlanner.acceptAndAdvance(state)
        if (state.refusalReason != null) return false
    }
    return state.window?.steps?.size == 3
}

/** The default 9-ball fixture: the first candidate that walks cleanly. */
private val nineBallLayout: TableLayout by lazy {
    nineBallCandidates.firstOrNull { nineBallWalksCleanly(it) }
        ?: error("None of the ${nineBallCandidates.size} candidate 9-ball layouts walks cleanly; add or refresh candidates")
}

private fun lastThreeWalksCleanly(layout: TableLayout): Boolean {
    var state = nineBallStart(layout)
    for (remaining in 3 downTo 1) {
        if (state.window?.steps?.size != remaining) return false
        state = RollingRunoutPlanner.acceptAndAdvance(state)
        if (state.refusalReason != null) return false
    }
    val deep = RollingRunoutPlanner.start(layout, nineBallRules, fixtureProfile, horizon = 1, deepAnalysis = true)
    return state.isRackComplete && deep.window?.steps?.size == 3
}

/** The default three-ball fixture: the first candidate that walks cleanly, in normal and deep mode. */
private val lastThreeLayout: TableLayout by lazy {
    lastThreeCandidates.firstOrNull { lastThreeWalksCleanly(it) }
        ?: error("None of the ${lastThreeCandidates.size} candidate three-ball layouts walks cleanly; add or refresh candidates")
}

private fun sameChoice(a: PlannedShotStep, b: PlannedShotStep): Boolean =
    a.targetBall.number == b.targetBall.number && a.pocketId == b.pocketId &&
        a.recommendedTip == b.recommendedTip && a.recommendedSpeedUnits == b.recommendedSpeedUnits

private val searchTips = listOf(
    TipOffset(0.0, 0.0), TipOffset(0.0, 0.18), TipOffset(0.0, 0.35), TipOffset(0.0, -0.14), TipOffset(0.0, -0.28),
    TipOffset(-0.16, 0.20), TipOffset(0.16, 0.20), TipOffset(0.0, -0.40), TipOffset(0.40, 0.0),
)
private val searchSpeeds = listOf(2.5, 3.5, 4.0, 5.5, 7.0)

class RollingRunoutPlannerTest {

    private val profile = fixtureProfile

    private fun start(horizon: Int = 3, layout: TableLayout = nineBallLayout): RollingPlanState =
        nineBallStart(layout, horizon)

    private fun targets(plan: RunoutPlan?): List<Int> = plan?.steps?.map { it.targetBall.number }.orEmpty()

    private fun landing(step: PlannedShotStep): Vec2 =
        assertNotNull(step.resultingLayout?.cueBall()).position

    private fun movedBall8(layout: TableLayout): TableLayout {
        val ball8 = assertNotNull(layout.ballByNumber(8))
        return layout.withBallPosition(8, Vec2(ball8.position.x + 0.03, ball8.position.y + 0.03))
    }

    /**
     * Finds a user edit on the focused step of [state] that still pots, uses a different tip from the planner's
     * choice, keeps the window length and makes the next shot start more than 2 cm away from where the unedited
     * plan starts it.
     */
    private fun findEdit(state: RollingPlanState): StepOverride {
        val natural = assertNotNull(state.focusStep)
        val naturalWindow = assertNotNull(state.window)
        for (speed in listOf(4.5, 3.5, 5.5, 2.5)) for (tip in searchTips) {
            if (tip == natural.recommendedTip) continue
            val override = StepOverride(tip = tip, speedUnits = speed)
            val edited = RollingRunoutPlanner.setOverride(state, override)
            val window = edited.window ?: continue
            val first = window.steps.first()
            if (!first.uncertainty.targetBallPotted || !edited.canAdvance) continue
            if (first.recommendedTip != tip || first.recommendedSpeedUnits != speed) continue
            if (window.steps.size != naturalWindow.steps.size || window.steps.size < 2) continue
            val moved = window.steps[1].compensatedAim.geometricAim.cueBallPos
                .distanceTo(naturalWindow.steps[1].compensatedAim.geometricAim.cueBallPos)
            if (moved > 0.02) return override
        }
        fail("No tip/speed edit on step ${state.focusIndex + 1} pots and moves the next start; refresh the fixture")
    }

    private val editOverride: StepOverride by lazy { findEdit(start()) }

    // ---------------------------------------------------------------------------------------------
    // Sliding window
    // ---------------------------------------------------------------------------------------------

    @Test
    fun startPlansTheHorizonFromTheBaseTableLikeThePlanner() {
        val state = start()

        val direct = MultiShotPlanner.planRunout(nineBallLayout, nineBallRules, profile, 3).first()
        assertEquals(listOf(1, 2, 3), targets(state.window))
        assertEquals(direct.steps.map { it.pocketId }, state.window?.steps?.map { it.pocketId })
        assertEquals(direct.expectedValueScore, assertNotNull(state.window).expectedValueScore, 1e-12)
        assertEquals(0, state.focusIndex)
        assertTrue(state.acceptedSteps.isEmpty())
        assertNull(state.focusOverride)
        assertFalse(state.isRackComplete)
        assertFalse(state.canGoBack)
        assertTrue(state.canAdvance)
        assertTrue(state.updatedStepNumbers.isEmpty())
        assertEquals(listOf(1, 2, 3), state.allSteps.map { it.stepNumber })
    }

    @Test
    fun acceptingAdvancesTheWindowOneBallAtATimeWithGaplessNumbering() {
        val s0 = start()
        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)
        val s2 = RollingRunoutPlanner.acceptAndAdvance(s1)

        assertNull(s1.refusalReason)
        assertEquals(listOf(1), s1.acceptedSteps.map { it.targetBall.number })
        assertEquals(listOf(2, 3, 4), targets(s1.window))
        assertEquals(1, s1.focusIndex)
        assertEquals(listOf(1, 2), s2.acceptedSteps.map { it.targetBall.number })
        assertEquals(listOf(3, 4, 5), targets(s2.window))
        assertEquals(2, s2.focusIndex)

        assertEquals(listOf(1, 2, 3, 4), s1.allSteps.map { it.stepNumber })
        assertEquals(listOf(1, 2, 3, 4, 5), s2.allSteps.map { it.stepNumber })
        assertEquals(listOf(1, 2, 3, 4, 5), s2.allSteps.map { it.targetBall.number })
        assertEquals(listOf(3, 4, 5), s2.window?.steps?.map { it.stepNumber })
        assertTrue(s2.window?.steps.orEmpty().all { it.uncertainty.targetBallPotted })

        val firstReason = assertNotNull(s2.window).explanationReasons.first()
        assertTrue(firstReason.startsWith("Step 3: pot ball #3"), "Explanations must use base numbering: $firstReason")
    }

    @Test
    fun acceptedStepsStayIdenticalObjectsThroughEveryLaterAction() {
        val s0 = start()
        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)
        val frozen = s1.acceptedSteps.single()
        val s2 = RollingRunoutPlanner.acceptAndAdvance(s1)
        val edited = RollingRunoutPlanner.setOverride(s2, findEdit(s2))
        val cleared = RollingRunoutPlanner.clearOverride(edited)
        val back = RollingRunoutPlanner.back(cleared)
        val forwardAgain = RollingRunoutPlanner.acceptAndAdvance(back)
        val s3 = RollingRunoutPlanner.acceptAndAdvance(s2)

        for (later in listOf(s2, edited, cleared, back, forwardAgain, s3)) {
            assertSame(frozen, later.acceptedSteps.first())
        }
        assertSame(s2.acceptedSteps[1], edited.acceptedSteps[1])
        assertSame(s2.acceptedSteps[1], s3.acceptedSteps[1])
        assertSame(s2.acceptedSteps[1], forwardAgain.acceptedSteps[1])
        assertEquals(1, back.acceptedSteps.size)
    }

    @Test
    fun windowCueBallStartsWhereTheLastAcceptedShotLeftIt() {
        var state = start()
        repeat(3) {
            val accepted = state.acceptedSteps.lastOrNull()
            val expected = if (accepted == null) {
                assertNotNull(nineBallLayout.cueBall()).position
            } else {
                landing(accepted)
            }
            val focusStep = assertNotNull(state.focusStep)
            assertEquals(0.0, focusStep.compensatedAim.geometricAim.cueBallPos.distanceTo(expected), 1e-9)
            assertEquals(0.0, assertNotNull(state.focusLayout.cueBall()).position.distanceTo(expected), 1e-9)
            state = RollingRunoutPlanner.acceptAndAdvance(state)
        }
        // Within one window, shot n+1 starts where shot n lands.
        val window = assertNotNull(state.window)
        for ((a, b) in window.steps.zipWithNext()) {
            assertEquals(0.0, b.compensatedAim.geometricAim.cueBallPos.distanceTo(landing(a)), 1e-9)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Overrides
    // ---------------------------------------------------------------------------------------------

    @Test
    fun overrideOnTheFocusIsUsedAndRefitsTheLaterSteps() {
        val natural = start()
        val naturalFirst = assertNotNull(natural.focusStep)
        val override = editOverride

        val edited = RollingRunoutPlanner.setOverride(natural, override)

        val first = assertNotNull(edited.focusStep)
        assertEquals(override, edited.focusOverride)
        assertEquals(override.tip, first.recommendedTip)
        assertEquals(override.speedUnits, first.recommendedSpeedUnits)
        assertTrue(first.uncertainty.targetBallPotted, "The override must still pot the ball for this test to mean anything")
        assertEquals(1, first.stepNumber)
        assertEquals(listOf(1, 2, 3), edited.window?.steps?.map { it.stepNumber })
        assertNotEquals(naturalFirst.recommendedTip, first.recommendedTip)

        val naturalSecondStart = assertNotNull(natural.window).steps[1].compensatedAim.geometricAim.cueBallPos
        val editedSecondStart = assertNotNull(edited.window).steps[1].compensatedAim.geometricAim.cueBallPos
        assertTrue(
            naturalSecondStart.distanceTo(editedSecondStart) > 0.02,
            "A follow stroke must leave the cue ball elsewhere than the planned draw stroke",
        )
        assertEquals(0.0, editedSecondStart.distanceTo(landing(first)), 1e-9)
        assertFalse(1 in edited.updatedStepNumbers || 1 in edited.refinedStepNumbers, "The edited step itself is not an update")
        assertTrue(edited.updatedStepNumbers.all { it in 2..3 })
        assertTrue(edited.refinedStepNumbers.all { it in 2..3 })
        assertTrue(edited.updatedStepNumbers.intersect(edited.refinedStepNumbers).isEmpty())
    }

    @Test
    fun clearOverrideReturnsToThePlannersChoice() {
        val natural = start()
        val edited = RollingRunoutPlanner.setOverride(natural, editOverride)
        val cleared = RollingRunoutPlanner.clearOverride(edited)

        assertNull(cleared.focusOverride)
        assertEquals(natural.window, cleared.window)
        assertEquals(natural.window, RollingRunoutPlanner.setOverride(edited, StepOverride()).window)
        assertEquals(natural, RollingRunoutPlanner.clearOverride(natural))
    }

    @Test
    fun overrideAfterAcceptingShapesTheWindowFromThatFocus() {
        val s1 = RollingRunoutPlanner.acceptAndAdvance(start())
        val override = findEdit(s1)

        val edited = RollingRunoutPlanner.setOverride(s1, override)

        val first = assertNotNull(edited.focusStep)
        assertEquals(2, first.stepNumber)
        assertEquals(2, first.targetBall.number)
        assertEquals(override.speedUnits, first.recommendedSpeedUnits)
        assertTrue(first.uncertainty.targetBallPotted)
        assertEquals(s1.acceptedSteps, edited.acceptedSteps)
        assertEquals(listOf(2, 3, 4), edited.window?.steps?.map { it.stepNumber })
    }

    // ---------------------------------------------------------------------------------------------
    // Stepping back
    // ---------------------------------------------------------------------------------------------

    @Test
    fun backReopensThePreviousStepUnchangedWithItsChoicesAsTheOverride() {
        val s0 = start()
        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)

        val back = RollingRunoutPlanner.back(s1)

        assertEquals(s0.window, back.window)
        assertEquals(s0.alternatives, back.alternatives)
        assertTrue(back.acceptedSteps.isEmpty())
        assertEquals(0, back.focusIndex)
        assertEquals(StepOverride.of(assertNotNull(s0.focusStep)), back.focusOverride)
        assertSame(s0.focusStep, back.focusStep)
        assertEquals(s0, back.copy(focusOverride = null, laterViews = emptyList()))
        assertEquals(back, RollingRunoutPlanner.back(back), "Nothing to step back to at the first step")
    }

    @Test
    fun backThenAcceptWithoutAnEditRoundTripsToTheSameState() {
        val s0 = start()
        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)
        val s2 = RollingRunoutPlanner.acceptAndAdvance(s1)

        val roundTrip = RollingRunoutPlanner.acceptAndAdvance(RollingRunoutPlanner.back(s2))
        assertEquals(s2, roundTrip)

        val twoBack = RollingRunoutPlanner.back(RollingRunoutPlanner.back(s2))
        assertEquals(s0.window, twoBack.window)
        val twoForward = RollingRunoutPlanner.acceptAndAdvance(RollingRunoutPlanner.acceptAndAdvance(twoBack))
        assertEquals(s2, twoForward)
    }

    @Test
    fun editingAfterBackReplansAndAcceptingUsesTheEdit() {
        val s1 = RollingRunoutPlanner.acceptAndAdvance(start())
        val originalFirst = s1.acceptedSteps.single()
        val back = RollingRunoutPlanner.back(s1)
        val override = editOverride

        val edited = RollingRunoutPlanner.setOverride(back, override)
        assertTrue(edited.laterViews.isEmpty(), "An edit discards the restored future")
        val accepted = RollingRunoutPlanner.acceptAndAdvance(edited)

        val newFirst = accepted.acceptedSteps.single()
        assertEquals(override.tip, newFirst.recommendedTip)
        assertNotEquals(originalFirst.recommendedTip, newFirst.recommendedTip)
        assertEquals(listOf(2, 3, 4), targets(accepted.window))
        assertEquals(0.0, assertNotNull(accepted.focusStep).compensatedAim.geometricAim.cueBallPos.distanceTo(landing(newFirst)), 1e-9)
        assertNull(accepted.focusOverride)
    }

    // ---------------------------------------------------------------------------------------------
    // Stability of steps the user has already seen
    // ---------------------------------------------------------------------------------------------

    private class StabilityFixture(
        val seeded: RollingPlanState,
        val fresh: RunoutPlan,
        val shownNext: RunoutPlan,
    )

    private val kept = "kept: shown continuation within 5% of the fresh plan"
    private val refined = "refined: same target and pocket, different stroke, fresh at least 5% better"
    private val updated = "updated: different pocket, fresh at least 5% better"

    /**
     * Searches for a state whose shown window continues with a shot the planner would not pick today, as if that
     * choice had been planned earlier, such that accepting step 1 exercises [scenario].
     *
     * For each candidate base layout in turn, the table after step 1 is planned afresh and then every
     * pocket x speed x tip combination is tried as the "shown" next shot (via the planner's custom first shot on
     * the fresh plan's target ball). The first combination that pots, differs from the fresh best shot, keeps both
     * plans at three steps and satisfies [matches] (given the fresh/shown score ratio) is used. Fails naming the
     * scenario when no layout offers one.
     */
    private fun stabilityFixture(
        scenario: String,
        matches: (fresh: PlannedShotStep, shown: PlannedShotStep, ratio: Double) -> Boolean,
    ): StabilityFixture {
        for (layout in nineBallCandidates) {
            val s0 = start(layout = layout)
            val shown1 = s0.focusStep ?: continue
            val tableAfterOne = shown1.resultingLayout ?: continue
            if (!s0.canAdvance || s0.window?.steps?.size != 3) continue
            val fresh = MultiShotPlanner.planRunout(tableAfterOne, nineBallRules, profile, 3).firstOrNull() ?: continue
            if (fresh.steps.size != 3) continue
            val freshFirst = fresh.steps.first()
            for (pocket in PocketId.entries) for (speed in searchSpeeds) for (tip in searchTips) {
                val shownNext = MultiShotPlanner.planRunout(
                    layout = tableAfterOne,
                    rulesState = nineBallRules,
                    playerProfile = profile,
                    horizonShots = 3,
                    customFirstShotTip = tip,
                    customFirstShotSpeedUnits = speed,
                    customFirstShotPocketId = pocket,
                    customFirstShotTargetBallNumber = freshFirst.targetBall.number,
                ).firstOrNull() ?: continue
                val shownFirst = shownNext.steps.first()
                if (!shownFirst.uncertainty.targetBallPotted || shownNext.steps.size != 3) continue
                if (sameChoice(freshFirst, shownFirst) || shownNext.expectedValueScore <= 0.0) continue
                val ratio = fresh.expectedValueScore / shownNext.expectedValueScore
                if (!matches(freshFirst, shownFirst, ratio)) continue
                val shownWindow = assertNotNull(s0.window).copy(
                    steps = listOf(shown1) + shownNext.steps.take(2).map { it.copy(stepNumber = it.stepNumber + 1) },
                )
                return StabilityFixture(s0.copy(window = shownWindow), fresh, shownNext)
            }
        }
        fail("No candidate base layout offers a shown continuation for the scenario '$scenario'; refresh the fixtures")
    }

    private fun keptFixture() = stabilityFixture(kept) { _, _, ratio -> ratio < 1.05 }

    private fun refinedFixture() = stabilityFixture(refined) { fresh, shown, ratio ->
        ratio >= 1.05 && fresh.targetBall.number == shown.targetBall.number && fresh.pocketId == shown.pocketId
    }

    private fun updatedFixture() = stabilityFixture(updated) { fresh, shown, ratio ->
        ratio >= 1.05 && (fresh.targetBall.number != shown.targetBall.number || fresh.pocketId != shown.pocketId)
    }

    @Test
    fun withinFivePercentTheShownContinuationIsKeptAndNothingIsMarkedUpdated() {
        val fixture = keptFixture()
        assertTrue(
            fixture.fresh.expectedValueScore < fixture.shownNext.expectedValueScore * 1.05,
            "Precondition: the fresh plan is within 5% of the kept one " +
                "(${fixture.fresh.expectedValueScore} vs ${fixture.shownNext.expectedValueScore})",
        )
        val shownSecond = assertNotNull(fixture.seeded.window).steps[1]
        assertFalse(sameChoice(shownSecond, fixture.fresh.steps.first()), "Precondition: the shown shot differs from the fresh best")

        val advanced = RollingRunoutPlanner.acceptAndAdvance(fixture.seeded)

        val window = assertNotNull(advanced.window)
        assertEquals(listOf(2, 3, 4), targets(window))
        assertTrue(sameChoice(shownSecond, window.steps.first()), "Kept: target, pocket, tip and speed unchanged")
        assertEquals(shownSecond.pocketId, window.steps.first().pocketId)
        assertEquals(fixture.shownNext.expectedValueScore, window.expectedValueScore, 1e-12)
        assertTrue(advanced.updatedStepNumbers.isEmpty())
        assertTrue(advanced.refinedStepNumbers.isEmpty())
    }

    @Test
    fun aMuchBetterStrokeOnTheSameShotIsReportedAsRefinedNotUpdated() {
        val fixture = refinedFixture()
        val shownSecond = assertNotNull(fixture.seeded.window).steps[1]
        val freshFirst = fixture.fresh.steps.first()
        assertEquals(shownSecond.targetBall.number, freshFirst.targetBall.number, "Precondition: same target")
        assertEquals(shownSecond.pocketId, freshFirst.pocketId, "Precondition: same pocket")
        assertTrue(
            shownSecond.recommendedTip != freshFirst.recommendedTip ||
                shownSecond.recommendedSpeedUnits != freshFirst.recommendedSpeedUnits,
            "Precondition: tip or speed differs",
        )
        assertTrue(
            fixture.fresh.expectedValueScore >= fixture.shownNext.expectedValueScore * 1.05,
            "Precondition: the fresh plan beats the kept one by 5% or more " +
                "(${fixture.fresh.expectedValueScore} vs ${fixture.shownNext.expectedValueScore})",
        )

        val advanced = RollingRunoutPlanner.acceptAndAdvance(fixture.seeded)

        val window = assertNotNull(advanced.window)
        assertEquals(listOf(2, 3, 4), targets(window))
        assertFalse(sameChoice(shownSecond, window.steps.first()), "The shown stroke was replaced")
        assertTrue(sameChoice(freshFirst, window.steps.first()))
        assertEquals(fixture.fresh.expectedValueScore, window.expectedValueScore, 1e-12)
        assertTrue(2 in advanced.refinedStepNumbers, "Step 2 was refined: ${advanced.refinedStepNumbers}")
        assertFalse(2 in advanced.updatedStepNumbers, "Same target and pocket is not a route change")
        assertTrue(advanced.refinedStepNumbers.all { it in 2..3 }, "Only previously shown steps are listed")
    }

    @Test
    fun aBetterFreshPlanThatChangesThePocketIsReportedAsUpdated() {
        val fixture = updatedFixture()
        val shownSecond = assertNotNull(fixture.seeded.window).steps[1]
        val freshFirst = fixture.fresh.steps.first()
        assertTrue(
            shownSecond.targetBall.number != freshFirst.targetBall.number || shownSecond.pocketId != freshFirst.pocketId,
            "Precondition: a different pocket or target",
        )
        assertTrue(
            fixture.fresh.expectedValueScore >= fixture.shownNext.expectedValueScore * 1.05,
            "Precondition: the fresh plan beats the kept one by 5% or more " +
                "(${fixture.fresh.expectedValueScore} vs ${fixture.shownNext.expectedValueScore})",
        )

        val advanced = RollingRunoutPlanner.acceptAndAdvance(fixture.seeded)

        val window = assertNotNull(advanced.window)
        assertEquals(freshFirst.pocketId, window.steps.first().pocketId)
        assertTrue(2 in advanced.updatedStepNumbers, "Step 2 changed route: ${advanced.updatedStepNumbers}")
        assertFalse(2 in advanced.refinedStepNumbers)
        assertTrue(advanced.updatedStepNumbers.all { it in 2..3 })
    }

    @Test
    fun updatedAndRefinedMarksAreRestoredByBackAndForward() {
        val fixture = refinedFixture()
        val s1 = RollingRunoutPlanner.acceptAndAdvance(fixture.seeded)
        assertTrue(2 in s1.refinedStepNumbers)

        val back = RollingRunoutPlanner.back(s1)
        assertTrue(back.refinedStepNumbers.isEmpty() && back.updatedStepNumbers.isEmpty())
        val forward = RollingRunoutPlanner.acceptAndAdvance(back)

        assertEquals(s1.refinedStepNumbers, forward.refinedStepNumbers)
        assertEquals(s1.updatedStepNumbers, forward.updatedStepNumbers)
        assertEquals(s1, forward)
    }

    // ---------------------------------------------------------------------------------------------
    // User edit marks
    // ---------------------------------------------------------------------------------------------


    @Test
    fun userEditsAreMarkedAndClearingRemovesTheMark() {
        val s0 = start()
        assertTrue(s0.userEditedStepNumbers.isEmpty())
        assertFalse(s0.isFocusEdited)

        val edited = RollingRunoutPlanner.setOverride(s0, editOverride)
        assertEquals(setOf(1), edited.userEditedStepNumbers)
        assertTrue(edited.isFocusEdited)
        assertEquals(edited, RollingRunoutPlanner.setOverride(edited, editOverride), "The same edit twice changes nothing")

        val cleared = RollingRunoutPlanner.clearOverride(edited)
        assertTrue(cleared.userEditedStepNumbers.isEmpty())
        assertFalse(cleared.isFocusEdited)
        assertNull(cleared.focusOverride)

        val emptied = RollingRunoutPlanner.setOverride(edited, StepOverride())
        assertTrue(emptied.userEditedStepNumbers.isEmpty())
    }

    @Test
    fun acceptedEditedStepKeepsItsMarkWhileBackAloneNeverCreatesOne() {
        val s0 = start()
        val edited = RollingRunoutPlanner.setOverride(s0, editOverride)
        val s1 = RollingRunoutPlanner.acceptAndAdvance(edited)

        assertEquals(setOf(1), s1.userEditedStepNumbers)
        assertFalse(s1.isFocusEdited, "Step 2 has not been edited")

        val reopenedEdited = RollingRunoutPlanner.back(s1)
        assertEquals(setOf(1), reopenedEdited.userEditedStepNumbers)
        assertTrue(reopenedEdited.isFocusEdited)
        assertEquals(s1, RollingRunoutPlanner.acceptAndAdvance(reopenedEdited), "An unedited round trip leaves the marks alone")

        val unedited = RollingRunoutPlanner.back(RollingRunoutPlanner.acceptAndAdvance(s0))
        assertNotNull(unedited.focusOverride, "Back loads the reopened step's own choices")
        assertFalse(unedited.isFocusEdited)
        assertTrue(unedited.userEditedStepNumbers.isEmpty())
    }

    @Test
    fun clearingAfterBackDropsThePinnedChoicesAndTheMark() {
        val s0 = start()
        val edited = RollingRunoutPlanner.setOverride(s0, editOverride)
        val reopened = RollingRunoutPlanner.back(RollingRunoutPlanner.acceptAndAdvance(edited))
        assertTrue(reopened.isFocusEdited)

        val cleared = RollingRunoutPlanner.clearOverride(reopened)

        assertNull(cleared.focusOverride)
        assertTrue(cleared.userEditedStepNumbers.isEmpty())
        assertFalse(cleared.isFocusEdited)
        assertEquals(s0.window, cleared.window, "Back to the planner's own choice for that step")

        val uneditedReopened = RollingRunoutPlanner.back(RollingRunoutPlanner.acceptAndAdvance(s0))
        val uneditedCleared = RollingRunoutPlanner.clearOverride(uneditedReopened)
        assertNull(uneditedCleared.focusOverride)
        assertEquals(s0.window, uneditedCleared.window)
    }

    @Test
    fun editAfterBackPrunesMarksOfStepsThatNoLongerExist() {
        val s0 = start()
        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)
        val s1Edited = RollingRunoutPlanner.setOverride(s1, findEdit(s1))
        val s2 = RollingRunoutPlanner.acceptAndAdvance(s1Edited)
        assertEquals(setOf(2), s2.userEditedStepNumbers)

        val backOnce = RollingRunoutPlanner.back(s2)
        assertEquals(setOf(2), backOnce.userEditedStepNumbers, "Back keeps the marks")
        val backTwice = RollingRunoutPlanner.back(backOnce)
        assertEquals(setOf(2), backTwice.userEditedStepNumbers)

        val editedEarlier = RollingRunoutPlanner.setOverride(backTwice, editOverride)

        assertEquals(setOf(1), editedEarlier.userEditedStepNumbers, "Step 2 was discarded with the restored future")
        assertTrue(editedEarlier.laterViews.isEmpty())
        assertEquals(listOf(1), editedEarlier.window?.steps?.map { it.stepNumber }?.take(1))
    }

    @Test
    fun startAndRebaseClearTheEditMarks() {
        val edited = RollingRunoutPlanner.setOverride(start(), editOverride)
        assertTrue(edited.isFocusEdited)

        val rebased = RollingRunoutPlanner.rebase(edited, movedBall8(edited.focusLayout))

        assertTrue(rebased.userEditedStepNumbers.isEmpty())
        assertFalse(rebased.isFocusEdited)
        assertTrue(start().userEditedStepNumbers.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------
    // Ends, rules, refusal, rebase, deep mode
    // ---------------------------------------------------------------------------------------------

    @Test
    fun windowShrinksAtTheEndOfTheRackAndTheLastBallCompletesIt() {
        val s0 = RollingRunoutPlanner.start(lastThreeLayout, nineBallRules, profile, 3)
        assertEquals(listOf(7, 8, 9), targets(s0.window))

        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)
        assertEquals(listOf(8, 9), targets(s1.window))

        val s2 = RollingRunoutPlanner.acceptAndAdvance(s1)
        assertEquals(listOf(9), targets(s2.window))
        assertEquals(3, assertNotNull(s2.focusStep).stepNumber)
        assertFalse(s2.isRackComplete)
        assertTrue(s2.canAdvance)

        val s3 = RollingRunoutPlanner.acceptAndAdvance(s2)
        assertNull(s3.window)
        assertTrue(s3.alternatives.isEmpty())
        assertTrue(s3.isRackComplete)
        assertEquals(listOf(7, 8, 9), s3.acceptedSteps.map { it.targetBall.number })
        assertEquals(listOf(1, 2, 3), s3.allSteps.map { it.stepNumber })
        assertFalse(s3.canAdvance)
        assertTrue(s3.canGoBack)

        val refused = RollingRunoutPlanner.acceptAndAdvance(s3)
        assertEquals(s3.acceptedSteps, refused.acceptedSteps)
        assertNotNull(refused.refusalReason)
        assertTrue(RollingRunoutPlanner.setOverride(s3, StepOverride(speedUnits = 4.0)).isRackComplete)

        val reopened = RollingRunoutPlanner.back(s3)
        assertEquals(s2.window, reopened.window)
        assertFalse(reopened.isRackComplete)
    }
    private class EightBallScenario(
        val open: RollingPlanState,
        val forced: RollingPlanState,
        val accepted: RollingPlanState,
        val forcedBall: Int,
    )

    /**
     * Opens each candidate 8-ball table, notes which group the planner prefers, and forces the opposite group by
     * overriding the target ball. The first table where some ball of the opposite group pots and can be accepted
     * is used.
     */
    private fun eightBallScenario(): EightBallScenario {
        val rules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN)
        for (layout in eightBallCandidates) {
            val open = RollingRunoutPlanner.start(layout, rules, profile, 3)
            val naturalFirst = open.focusStep?.targetBall ?: continue
            val opposite = layout.objectBalls().filter { if (naturalFirst.isSolid) it.isStripe else it.isSolid }
            for (ball in opposite) {
                val forced = RollingRunoutPlanner.setOverride(open, StepOverride(targetBallNumber = ball.number))
                val step = forced.focusStep ?: continue
                if (step.targetBall.number != ball.number || !forced.canAdvance) continue
                val accepted = RollingRunoutPlanner.acceptAndAdvance(forced)
                if (accepted.refusalReason == null && accepted.window != null) {
                    return EightBallScenario(open, forced, accepted, ball.number)
                }
            }
        }
        fail("No candidate 8-ball table lets the opposite group be forced and accepted; refresh the fixtures")
    }

    @Test
    fun acceptingABallOnAnOpenEightBallTableAssignsItsGroupForTheRestOfTheWindow() {
        val scenario = eightBallScenario()
        val naturalFirst = assertNotNull(scenario.open.focusStep).targetBall
        val forcedFirst = assertNotNull(scenario.forced.focusStep).targetBall
        assertEquals(EightBallGroup.OPEN, scenario.open.focusRules.eightBallGroup)
        assertNotEquals(naturalFirst.number, forcedFirst.number, "Precondition: the override changed the first ball")
        assertNotEquals(naturalFirst.isSolid, forcedFirst.isSolid, "Precondition: the override switched group")

        val accepted = scenario.accepted

        val expectedGroup = if (forcedFirst.isSolid) EightBallGroup.SOLIDS else EightBallGroup.STRIPES
        assertEquals(expectedGroup, accepted.focusRules.eightBallGroup)
        val window = assertNotNull(accepted.window)
        val inGroup = { n: Int -> if (forcedFirst.isSolid) n in 1..7 else n in 9..15 }
        assertTrue(window.steps.all { inGroup(it.targetBall.number) || it.targetBall.isEightBall }, "Only own group, then the 8: ${targets(window)}")
        assertTrue(window.steps.last().targetBall.isEightBall)
        val remainingOwn = scenario.open.focusLayout.objectBalls().count { inGroup(it.number) } - 1
        assertEquals(remainingOwn + 1, window.steps.size, "Remaining own-group balls, then the 8-ball")
        assertEquals((2..window.steps.size + 1).toList(), window.steps.map { it.stepNumber })

        val rebased = RollingRunoutPlanner.rebase(accepted, accepted.focusLayout)
        assertEquals(expectedGroup, rebased.baseRules.eightBallGroup, "The assigned group survives a rebase")
        assertEquals(targets(window), targets(rebased.window))
    }

    /** Finds a stroke at the first pocket choice that leaves the ball short, so the edit shows but cannot be accepted. */
    private fun findNonPottingEdit(state: RollingPlanState): RollingPlanState {
        for (speed in listOf(0.5, 1.0, 1.5)) for (pocket in PocketId.entries) {
            val edited = RollingRunoutPlanner.setOverride(state, StepOverride(speedUnits = speed, pocketId = pocket))
            val step = edited.focusStep ?: continue
            if (!step.uncertainty.targetBallPotted) return edited
        }
        fail("No soft stroke leaves ball ${state.focusStep?.targetBall?.number} unpotted; refresh the fixture")
    }

    @Test
    fun acceptingAStepThatDoesNotPotIsRefusedWithAReason() {
        val s0 = start()

        val edited = findNonPottingEdit(s0)

        val step = assertNotNull(edited.focusStep, "A non-potting edit is still shown so the user can see it")
        assertFalse(step.uncertainty.targetBallPotted)
        assertFalse(edited.canAdvance)
        assertNotNull(RollingRunoutPlanner.advanceRefusal(edited))

        val refused = RollingRunoutPlanner.acceptAndAdvance(edited)

        assertTrue(refused.acceptedSteps.isEmpty())
        assertEquals(edited.window, refused.window)
        assertTrue(assertNotNull(refused.refusalReason).contains("#1"), refused.refusalReason)
        assertNull(RollingRunoutPlanner.acceptAndAdvance(RollingRunoutPlanner.clearOverride(refused)).refusalReason)
    }

    @Test
    fun anOverrideWithNoPlayableShotLeavesNoWindowAndRefusesToAdvance() {
        val s0 = start()

        val edited = RollingRunoutPlanner.setOverride(s0, StepOverride(targetBallNumber = 5))

        assertNull(edited.window, "Ball 5 is not a legal target in 9-ball while the 1 is on the table")
        assertFalse(edited.isRackComplete)
        assertNotNull(RollingRunoutPlanner.advanceRefusal(edited))
        assertNotNull(RollingRunoutPlanner.acceptAndAdvance(edited).refusalReason)
        assertEquals(s0.window, RollingRunoutPlanner.clearOverride(edited).window)
    }

    @Test
    fun rebaseDropsAcceptedStepsAndPlansFromTheMovedTable() {
        val s2 = RollingRunoutPlanner.acceptAndAdvance(RollingRunoutPlanner.acceptAndAdvance(start()))
        val moved = movedBall8(s2.focusLayout)

        val rebased = RollingRunoutPlanner.rebase(s2, moved)

        assertTrue(rebased.acceptedSteps.isEmpty())
        assertEquals(0, rebased.focusIndex)
        assertEquals(moved, rebased.baseLayout)
        assertNull(rebased.focusOverride)
        assertTrue(rebased.earlierViews.isEmpty() && rebased.laterViews.isEmpty())
        assertEquals(listOf(3, 4, 5), targets(rebased.window))
        assertEquals(listOf(1, 2, 3), rebased.window?.steps?.map { it.stepNumber })
        assertFalse(rebased.canGoBack)
        assertEquals(rebased, RollingRunoutPlanner.back(rebased))
    }

    @Test
    fun deepAnalysisCoversTheRackSoAcceptingOnlyMovesTheFocus() {
        val s0 = RollingRunoutPlanner.start(lastThreeLayout, nineBallRules, profile, horizon = 1, deepAnalysis = true)
        val window = assertNotNull(s0.window)
        assertEquals(listOf(7, 8, 9), targets(window))

        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)

        val next = assertNotNull(s1.window)
        assertEquals(listOf(8, 9), targets(next))
        assertSame(window.steps[1], next.steps[0])
        assertSame(window.steps[2], next.steps[1])
        assertSame(window.steps[0], s1.acceptedSteps.single())
        assertEquals(listOf(1, 2, 3), s1.allSteps.map { it.stepNumber })
        assertEquals(s0, RollingRunoutPlanner.back(s1).copy(focusOverride = null, laterViews = emptyList()))
    }

    // ---------------------------------------------------------------------------------------------
    // Edit state across Prev and Next
    // ---------------------------------------------------------------------------------------------

    /** A speed-only edit that differs from what the planner chose for the focused step. */
    private fun speedEdit(state: RollingPlanState): StepOverride {
        val natural = assertNotNull(state.focusStep).recommendedSpeedUnits
        return StepOverride(speedUnits = if (natural == 7.0) 5.0 else 7.0)
    }

    /** The invariant plus, for an edited focus, that the focus step carries the override it was planned with. */
    private fun assertEditStateConsistent(state: RollingPlanState, where: String) {
        assertTrue(state.isEditStateConsistent, "$where: edited focus without its override")
        val override = state.focusOverride
        val step = state.focusStep
        if (state.isFocusEdited && override != null && step != null) {
            override.speedUnits?.let { assertEquals(it, step.recommendedSpeedUnits, "$where: speed of the edit") }
            override.tip?.let { assertEquals(it, step.recommendedTip, "$where: tip of the edit") }
            override.pocketId?.let { assertEquals(it, step.pocketId, "$where: pocket of the edit") }
            override.targetBallNumber?.let { assertEquals(it, step.targetBall.number, "$where: target of the edit") }
        }
    }

    /** The first step the planner picks at the focus of [state] when nothing is forced. */
    private fun ownPick(state: RollingPlanState): PlannedShotStep =
        MultiShotPlanner.planRunout(state.focusLayout, state.focusRules, state.profile, state.horizon)
            .first().steps.first()

    @Test
    fun editThenPrevThenNextKeepsTheEditAndResetStepReturnsToThePlannersPick() {
        val s1 = RollingRunoutPlanner.acceptAndAdvance(start())
        val natural = assertNotNull(s1.focusStep)
        val edit = speedEdit(s1)
        val edited = RollingRunoutPlanner.setOverride(s1, edit)
        assertEquals(edit.speedUnits, assertNotNull(edited.focusStep).recommendedSpeedUnits)

        val back = RollingRunoutPlanner.back(edited)
        val forward = RollingRunoutPlanner.acceptAndAdvance(back)

        assertEditStateConsistent(forward, "after Prev and Next")
        assertTrue(forward.isFocusEdited, "The edited step keeps its mark")
        assertEquals(edit, forward.focusOverride, "The edit comes back with its view")
        assertEquals(edit.speedUnits, assertNotNull(forward.focusStep).recommendedSpeedUnits)
        assertEquals(edited, forward, "Prev then Next without an edit restores exactly what was shown")

        val reset = RollingRunoutPlanner.clearOverride(forward)

        assertEditStateConsistent(reset, "after Reset step")
        assertFalse(reset.isFocusEdited)
        assertNull(reset.focusOverride)
        val resetStep = assertNotNull(reset.focusStep)
        assertTrue(sameChoice(ownPick(reset), resetStep), "Reset returns to the planner's own first shot")
        assertEquals(natural.recommendedSpeedUnits, resetStep.recommendedSpeedUnits)
        assertNotEquals(edit.speedUnits, resetStep.recommendedSpeedUnits)
    }

    @Test
    fun resetStepOnAnEditedFocusWithoutItsOverrideStillResets() {
        // The state the earlier code produced after edit, Prev, Next: marked as edited, override lost.
        val s1 = RollingRunoutPlanner.acceptAndAdvance(start())
        val edited = RollingRunoutPlanner.setOverride(s1, speedEdit(s1))
        val broken = edited.copy(focusOverride = null)
        assertFalse(broken.isEditStateConsistent)

        val reset = RollingRunoutPlanner.clearOverride(broken)

        assertEditStateConsistent(reset, "after Reset step")
        assertFalse(reset.isFocusEdited)
        assertTrue(sameChoice(ownPick(reset), assertNotNull(reset.focusStep)))
        assertEquals(s1.focusStep?.recommendedSpeedUnits, reset.focusStep?.recommendedSpeedUnits)
    }

    @Test
    fun prevOntoAnEditedStepBringsBackTheEditAsARealEdit() {
        val s0 = start()
        val edit = editOverride
        val s1 = RollingRunoutPlanner.acceptAndAdvance(RollingRunoutPlanner.setOverride(s0, edit))

        val reopened = RollingRunoutPlanner.back(s1)

        assertEditStateConsistent(reopened, "after Prev")
        assertTrue(reopened.isFocusEdited)
        assertEquals(edit, reopened.focusOverride, "The user's own override, not the pinned choices")
        val reset = RollingRunoutPlanner.clearOverride(reopened)
        assertFalse(reset.isFocusEdited)
        assertEquals(s0.window, reset.window)
        assertEditStateConsistent(reset, "after Reset step")
    }

    @Test
    fun prevOntoAnUneditedStepIsNotAnEditAndLoadsThePinnedChoices() {
        val s0 = start()
        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)

        val reopened = RollingRunoutPlanner.back(s1)

        assertEditStateConsistent(reopened, "after Prev")
        assertFalse(reopened.isFocusEdited)
        assertEquals(StepOverride.of(assertNotNull(s0.focusStep)), reopened.focusOverride)
        assertTrue(reopened.userEditedStepNumbers.isEmpty())
    }

    @Test
    fun anEditAfterPrevDiscardsTheLaterViewsAndKeepsTheStateConsistent() {
        val s1 = RollingRunoutPlanner.acceptAndAdvance(start())
        val s1Edited = RollingRunoutPlanner.setOverride(s1, speedEdit(s1))
        val s2 = RollingRunoutPlanner.acceptAndAdvance(s1Edited)
        val back = RollingRunoutPlanner.back(RollingRunoutPlanner.back(s2))
        assertEquals(2, back.laterViews.size)

        val edited = RollingRunoutPlanner.setOverride(back, editOverride)

        assertTrue(edited.laterViews.isEmpty())
        assertEquals(setOf(1), edited.userEditedStepNumbers)
        assertEditStateConsistent(edited, "after the edit")
        val forward = RollingRunoutPlanner.acceptAndAdvance(edited)
        assertEditStateConsistent(forward, "after Next")
        assertFalse(forward.isFocusEdited)
    }

    @Test
    fun rebaseAfterEditAndPrevStartsWithoutAnyEditState() {
        val s1 = RollingRunoutPlanner.acceptAndAdvance(start())
        val edited = RollingRunoutPlanner.setOverride(s1, speedEdit(s1))
        val forward = RollingRunoutPlanner.acceptAndAdvance(RollingRunoutPlanner.back(edited))
        assertTrue(forward.isFocusEdited)

        val rebased = RollingRunoutPlanner.rebase(forward, movedBall8(forward.focusLayout))

        assertTrue(rebased.userEditedStepNumbers.isEmpty())
        assertNull(rebased.focusOverride)
        assertFalse(rebased.isFocusEdited)
        assertEditStateConsistent(rebased, "after rebase")
    }

    /**
     * Every sequence of five operations (Next, Prev, edit, Reset step) from the start keeps the edit state consistent,
     * and Reset step on an edited focus returns to the planner's own choice.
     */
    @Test
    fun everyShortSequenceOfEditsAndMovesKeepsTheEditStateConsistent() {
        var visited = 0
        var resetChecks = 0
        fun walk(state: RollingPlanState, depth: Int, path: String) {
            visited++
            assertEditStateConsistent(state, path)
            if (state.isFocusEdited && resetChecks < 6) {
                resetChecks++
                val reset = RollingRunoutPlanner.clearOverride(state)
                assertFalse(reset.isFocusEdited, "$path: reset keeps the mark")
                assertTrue(sameChoice(ownPick(reset), assertNotNull(reset.focusStep)), "$path: reset is not the planner's pick")
            } else if (state.isFocusEdited) {
                assertFalse(RollingRunoutPlanner.clearOverride(state).isFocusEdited, "$path: reset keeps the mark")
            }
            if (depth == 0) return
            walk(RollingRunoutPlanner.acceptAndAdvance(state), depth - 1, "$path>next")
            walk(RollingRunoutPlanner.back(state), depth - 1, "$path>prev")
            if (state.focusStep != null && !state.isRackComplete) {
                walk(RollingRunoutPlanner.setOverride(state, speedEdit(state)), depth - 1, "$path>edit")
            }
            walk(RollingRunoutPlanner.clearOverride(state), depth - 1, "$path>reset")
        }
        walk(start(), depth = 5, path = "start")
        println("edit-state walk: $visited states, $resetChecks full reset checks")
    }

    // ---------------------------------------------------------------------------------------------
    // Horizon, alternatives and the default marker
    // ---------------------------------------------------------------------------------------------

    @Test
    fun withHorizonKeepsAcceptedStepsAndReplansTheWindowFromTheFocus() {
        val s1 = RollingRunoutPlanner.acceptAndAdvance(start(horizon = 3))
        val before = s1.acceptedSteps.single()

        val shorter = RollingRunoutPlanner.withHorizon(s1, 2, deepAnalysis = false)

        assertSame(before, shorter.acceptedSteps.single())
        assertEquals(2, shorter.horizon)
        assertEquals(listOf(2, 3), shorter.window?.steps?.map { it.stepNumber })
        val direct = MultiShotPlanner.planRunout(s1.focusLayout, s1.focusRules, profile, 2).first()
        assertEquals(direct.steps.map { it.targetBall.number }, targets(shorter.window))
        assertEquals(direct.steps.map { it.pocketId }, shorter.window?.steps?.map { it.pocketId })
        assertTrue(shorter.laterViews.isEmpty() && shorter.earlierViews.isEmpty())
        assertEditStateConsistent(shorter, "after withHorizon")
        assertEquals(listOf(1, 2, 3), shorter.allSteps.map { it.stepNumber })

        val longer = RollingRunoutPlanner.withHorizon(shorter, 4, deepAnalysis = false)
        assertEquals(listOf(2, 3, 4, 5), longer.window?.steps?.map { it.stepNumber })
        assertEquals(4, longer.horizon)

        // Prev plans the reopened step with the new horizon; Next restores what was shown.
        val reopened = RollingRunoutPlanner.back(longer)
        assertEquals(4, reopened.window?.steps?.size)
        assertEquals(listOf(1, 2, 3, 4), reopened.window?.steps?.map { it.stepNumber })
        assertEquals(1, RollingRunoutPlanner.acceptAndAdvance(reopened).acceptedSteps.size)
    }

    @Test
    fun withHorizonClearsStepForwardViewsAndKeepsAnEdit() {
        val s1 = RollingRunoutPlanner.acceptAndAdvance(start())
        val edited = RollingRunoutPlanner.setOverride(s1, speedEdit(s1))
        val back = RollingRunoutPlanner.back(edited)
        assertTrue(back.laterViews.isNotEmpty())

        val resized = RollingRunoutPlanner.withHorizon(back, 2, deepAnalysis = false)

        assertTrue(resized.laterViews.isEmpty())
        assertEquals(StepOverride.of(assertNotNull(start().focusStep)), resized.focusOverride)

        val editedNow = RollingRunoutPlanner.setOverride(start(), editOverride)
        val resizedEdited = RollingRunoutPlanner.withHorizon(editedNow, 2, deepAnalysis = false)
        assertTrue(resizedEdited.isFocusEdited)
        assertEquals(editOverride, resizedEdited.focusOverride)
        assertEquals(editOverride.tip, resizedEdited.focusStep?.recommendedTip)
        assertEquals(2, resizedEdited.window?.steps?.size)
        assertEditStateConsistent(resizedEdited, "after withHorizon")
    }

    @Test
    fun withHorizonChangesNothingWhenTheSettingsAreAlreadyInForceAndClampsTheHorizon() {
        val s0 = start(horizon = 3)
        assertSame(s0, RollingRunoutPlanner.withHorizon(s0, 3, deepAnalysis = false))
        assertEquals(RollingRunoutPlanner.MAX_HORIZON, RollingRunoutPlanner.withHorizon(s0, 99, deepAnalysis = false).horizon)
        assertEquals(1, RollingRunoutPlanner.withHorizon(s0, 0, deepAnalysis = false).horizon)
    }

    @Test
    fun withHorizonCanSwitchDeepAnalysisOnAndOff() {
        val s0 = RollingRunoutPlanner.start(lastThreeLayout, nineBallRules, profile, horizon = 1)
        assertEquals(listOf(7), targets(s0.window))

        val deep = RollingRunoutPlanner.withHorizon(s0, 1, deepAnalysis = true)

        assertTrue(deep.deepAnalysis)
        assertEquals(listOf(7, 8, 9), targets(deep.window))
        val shallow = RollingRunoutPlanner.withHorizon(deep, 1, deepAnalysis = false)
        assertEquals(listOf(7), targets(shallow.window))
        assertEquals(s0.window, shallow.window)
    }

    /** The first base layout whose start window offers another first shot. */
    private fun stateWithAlternatives(): RollingPlanState {
        val rules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN)
        for (layout in eightBallCandidates) {
            val open = RollingRunoutPlanner.start(layout, rules, profile, 3)
            if (open.window != null && open.alternatives.isNotEmpty()) return open
        }
        fail("No candidate table offers an alternative first shot; refresh the fixtures")
    }

    @Test
    fun chooseAlternativeTakesAnotherFirstShotWithoutEditingOrSearching() {
        val open = stateWithAlternatives()
        val chosen = open.alternatives.first()
        val oldWindow = assertNotNull(open.window)

        val picked = RollingRunoutPlanner.chooseAlternative(open, chosen)

        assertSame(chosen, picked.window)
        assertEquals(open.cache.size, picked.cache.size, "No new search")
        assertFalse(picked.isFocusEdited)
        assertTrue(picked.userEditedStepNumbers.isEmpty())
        assertEquals(StepOverride.of(chosen.steps.first()), picked.focusOverride)
        assertTrue(oldWindow in picked.alternatives, "The window that was shown stays on offer")
        assertFalse(chosen in picked.alternatives)
        assertEditStateConsistent(picked, "after chooseAlternative")
        assertTrue(picked.canAdvance)
        val accepted = RollingRunoutPlanner.acceptAndAdvance(picked)
        assertTrue(sameChoice(chosen.steps.first(), accepted.acceptedSteps.single()))

        // The pin survives a horizon change; Reset step returns to the planner's own first shot.
        val resized = RollingRunoutPlanner.withHorizon(picked, 2, deepAnalysis = false)
        assertTrue(sameChoice(chosen.steps.first(), assertNotNull(resized.focusStep)))
        val reset = RollingRunoutPlanner.clearOverride(picked)
        assertEquals(open.window, reset.window)
        assertNull(reset.focusOverride)
    }

    @Test
    fun chooseAlternativeDropsTheEditMarkAndIgnoresAPlanThatIsNotOnOffer() {
        val open = stateWithAlternatives()
        val edited = RollingRunoutPlanner.setOverride(open, StepOverride(speedUnits = speedEdit(open).speedUnits))
        assertTrue(edited.isFocusEdited)
        val alternative = edited.alternatives.firstOrNull() ?: open.alternatives.first()

        val picked = RollingRunoutPlanner.chooseAlternative(edited, alternative)
        if (alternative in edited.alternatives) {
            assertFalse(picked.isFocusEdited)
            assertEditStateConsistent(picked, "after chooseAlternative")
        }

        val unrelated = assertNotNull(RollingRunoutPlanner.start(nineBallLayout, nineBallRules, profile, 1).window)
        assertSame(open, RollingRunoutPlanner.chooseAlternative(open, unrelated))
    }

    @Test
    fun theDefaultFocusStepIsTheFocusUntilAnEditAndThenComesFromTheMemo() {
        val s0 = start()
        val natural = assertNotNull(s0.focusStep)
        assertSame(natural, s0.defaultFocusStep)

        val edited = RollingRunoutPlanner.setOverride(s0, speedEdit(s0))
        assertTrue(sameChoice(natural, assertNotNull(edited.defaultFocusStep)), "Known from the memo, no search needed")

        val forgotten = edited.copy(cache = PlanCache.EMPTY)
        assertNull(forgotten.defaultFocusStep)
        val filled = RollingRunoutPlanner.withDefaultFocusStep(forgotten)
        assertTrue(sameChoice(natural, assertNotNull(filled.defaultFocusStep)))
        assertEquals(forgotten, filled, "Only the memo differs")

        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)
        val edited1 = RollingRunoutPlanner.setOverride(s1, speedEdit(s1))
        val default1 = assertNotNull(RollingRunoutPlanner.withDefaultFocusStep(edited1.copy(cache = PlanCache.EMPTY)).defaultFocusStep)
        assertEquals(2, default1.stepNumber, "Numbered from the base table like the focus step")
        assertTrue(sameChoice(ownPick(edited1), default1))
    }

    // ---------------------------------------------------------------------------------------------
    // Advancing a forced shot
    // ---------------------------------------------------------------------------------------------

    private val solidsRules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS)

    private fun droppedBalls(step: PlannedShotStep): Map<Int, PocketId> =
        step.uncertainty.postShotSimulation?.disturbedBalls.orEmpty()
            .mapNotNull { path -> path.pocketedIn?.let { path.ballNumber to it } }
            .toMap()

    /** An 8-ball table with solids 1 and 2, stripes 9 and 10 and the 8 in the foot-left corner. */
    private val eightDropLayout: TableLayout = layoutFrom(
        listOf(0, 1, 2, 9, 10, 8),
        doubleArrayOf(0.578, 0.460, 1.646, 0.334, 0.512, 0.997, 0.622, 0.591, 1.435, 0.396, 2.460, 0.100),
    )

    /** [state] with the focused step's simulation changed to drop the balls of [extra] and, if given, its target in [targetPocket]. */
    private fun simulateDrops(
        state: RollingPlanState,
        extra: Map<Int, PocketId> = emptyMap(),
        targetPocket: PocketId? = null,
    ): RollingPlanState {
        val window = assertNotNull(state.window)
        val step = window.steps.first()
        val simulation = assertNotNull(step.uncertainty.postShotSimulation)
        val changed = simulation.copy(
            disturbedBalls = simulation.disturbedBalls.filter { it.ballNumber !in extra } +
                extra.map { (number, pocket) -> DisplacedBallPath(number, listOf(Vec2(0.0, 0.0)), pocketedIn = pocket) },
            targetBallTrajectory = if (targetPocket == null) {
                simulation.targetBallTrajectory
            } else {
                simulation.targetBallTrajectory?.copy(pocketedIn = targetPocket)
            },
        )
        val newStep = step.copy(uncertainty = step.uncertainty.copy(postShotSimulation = changed))
        return state.copy(window = window.copy(steps = listOf(newStep) + window.steps.drop(1)))
    }

    private fun refusalFor(state: RollingPlanState, extra: Map<Int, PocketId> = emptyMap(), targetPocket: PocketId? = null): String? {
        assertNull(RollingRunoutPlanner.advanceRefusal(state), "Precondition: the planner's own shot can be accepted")
        return RollingRunoutPlanner.advanceRefusal(simulateDrops(state, extra, targetPocket))
    }

    @Test
    fun eightBallRefusesAnEightAnOpponentBallOrAnUnclearGroupButNotAnOwnBallDroppingWithTheTarget() {
        val solids = RollingRunoutPlanner.start(eightDropLayout, solidsRules, profile, 1)
        val target = assertNotNull(solids.focusStep).targetBall
        assertTrue(target.isSolid, "Precondition: a solid is the target")
        val otherSolid = listOf(1, 2, 3).first { it != target.number }
        val pocket = PocketId.SIDE_LEFT

        assertTrue("8-ball" in assertNotNull(refusalFor(solids, mapOf(8 to pocket))))
        assertTrue("#9" in assertNotNull(refusalFor(solids, mapOf(9 to pocket))), "An opponent's stripe")
        assertNull(refusalFor(solids, mapOf(otherSolid to pocket)), "A ball of the shooter's own group dropping with the target")
        assertTrue("8-ball" in assertNotNull(refusalFor(solids, mapOf(8 to pocket, otherSolid to pocket))))

        val open = RollingRunoutPlanner.start(eightDropLayout, GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN), profile, 1)
        val openTarget = assertNotNull(open.focusStep).targetBall
        val sameGroup = if (openTarget.isSolid) listOf(1, 2, 3).first { it != openTarget.number } else 10
        val otherGroup = if (openTarget.isSolid) 9 else 2
        assertNull(refusalFor(open, mapOf(sameGroup to pocket)), "Same group as the target on an open table")
        assertNotNull(refusalFor(open, mapOf(otherGroup to pocket)), "The other group on an open table")
        assertNotNull(refusalFor(open, mapOf(8 to pocket)))
    }

    @Test
    fun theEightAsTheTargetRefusesEveryOtherBallThatDrops() {
        val layout = layoutFrom(listOf(0, 8, 9), doubleArrayOf(0.70, 0.64, 1.60, 0.40, 1.30, 1.05))
        val state = RollingRunoutPlanner.start(layout, solidsRules, profile, 1)
        assertEquals(8, assertNotNull(state.focusStep).targetBall.number, "Precondition: the 8 is the only legal ball")

        assertNull(refusalFor(state))
        val reason = assertNotNull(refusalFor(state, mapOf(9 to PocketId.SIDE_LEFT)))
        assertTrue("#9" in reason, reason)
    }

    @Test
    fun nineBallNeverRefusesAnExtraBallThatDropsAfterTheLegalHit() {
        val state = start(horizon = 1)
        assertNull(refusalFor(state, mapOf(4 to PocketId.SIDE_LEFT)))
        assertNull(refusalFor(state, mapOf(4 to PocketId.SIDE_LEFT, 9 to PocketId.FOOT_LEFT)))
    }

    @Test
    fun aTargetDroppingInAnotherPocketIsRefusedInEveryGameNamingBothPockets() {
        for (state in listOf(
            start(horizon = 1),
            RollingRunoutPlanner.start(eightDropLayout, solidsRules, profile, 1),
        )) {
            val step = assertNotNull(state.focusStep)
            val other = PocketId.entries.first { it != step.pocketId }
            val reason = assertNotNull(refusalFor(state, targetPocket = other))
            assertTrue(other.displayName in reason && step.pocketId.displayName in reason, reason)
            assertTrue("#${step.targetBall.number}" in reason, reason)
        }
    }

    /**
     * A solids table with the 8 close to a pocket, the 1 close to the 8, and 2, 3, the stripes 9 and 10 and the cue
     * ball spread over the table: shots that send a ball into the 8 or follow through to it are common.
     */
    private fun eightNearAPocket(random: Random): TableLayout? {
        val r = fixtureTable.ballRadiusMeters
        val pocket = fixtureTable.pockets.random(random)
        val towardTable = (Vec2(fixtureTable.lengthMeters / 2, fixtureTable.widthMeters / 2) - pocket.mouthCenter).normalized()
        val eight = pocket.mouthCenter + towardTable * (0.10 + 0.2 * random.nextDouble())
        val placed = mutableListOf(8 to eight)
        for ((number, nearTheEight) in listOf(1 to true, 2 to false, 3 to false, 9 to false, 10 to false, 0 to false)) {
            var spot: Vec2? = null
            repeat(80) {
                if (spot == null) {
                    val candidate = if (nearTheEight) {
                        eight + Vec2(1.0, 0.0).rotate(random.nextDouble() * 2 * PI) * (0.10 + 0.35 * random.nextDouble())
                    } else {
                        Vec2(
                            r + 0.05 + random.nextDouble() * (fixtureTable.lengthMeters - 0.2),
                            r + 0.05 + random.nextDouble() * (fixtureTable.widthMeters - 0.2),
                        )
                    }
                    val inside = candidate.x > r + 0.02 && candidate.x < fixtureTable.lengthMeters - r - 0.02 &&
                        candidate.y > r + 0.02 && candidate.y < fixtureTable.widthMeters - r - 0.02
                    if (inside && placed.all { (_, p) -> p.distanceTo(candidate) > 2 * r + 0.015 }) spot = candidate
                }
            }
            placed += number to (spot ?: return null)
        }
        return TableLayout(fixtureTable, placed.map { (number, position) -> Ball(number, position, r) })
    }

    @Test
    fun noForcedShotThatDropsTheEightOrPutsTheTargetInAnotherPocketCanBeAdvanced() {
        val random = Random(5)
        val tips = listOf<TipOffset?>(null, TipOffset(0.0, 0.35), TipOffset(0.0, -0.30))
        var tables = 0
        var shots = 0
        var eightDrops = 0
        var otherPocket = 0
        var firstEightDrop: RollingPlanState? = null
        val mark = TimeSource.Monotonic.markNow()
        while (tables < 60) {
            val layout = eightNearAPocket(random) ?: continue
            tables++
            val start = RollingRunoutPlanner.start(layout, solidsRules, profile, 1)
            for (target in 1..3) for (pocket in PocketId.entries) for (speed in listOf(5.0, 7.0, 9.0, 10.0)) for (tip in tips) {
                val forced = RollingRunoutPlanner.setOverride(
                    start,
                    StepOverride(tip = tip, targetBallNumber = target, pocketId = pocket, speedUnits = speed),
                )
                val step = forced.focusStep ?: continue
                shots++
                if (8 in droppedBalls(step) && step.targetBall.number != 8) {
                    eightDrops++
                    assertFalse(forced.canAdvance, "The 8 drops with ball #${step.targetBall.number}: ${droppedBalls(step)}")
                    if (firstEightDrop == null) firstEightDrop = forced
                }
                val landedIn = step.uncertainty.postShotSimulation?.targetBallTrajectory?.pocketedIn
                if (landedIn != null && landedIn != step.pocketId) {
                    otherPocket++
                    assertFalse(forced.canAdvance, "Ball #${step.targetBall.number} drops in $landedIn, not ${step.pocketId}")
                }
            }
        }
        println("forced shots: $shots in $tables tables, the 8 drops in $eightDrops, another pocket in $otherPocket, ${mark.elapsedNow().inWholeSeconds} s")
        assertTrue(eightDrops > 0, "The search never dropped the 8, so it proves nothing; change the seed or the setup")

        // What Next says about one of them, and that resetting the step gives back a shot that can be accepted.
        val forced = assertNotNull(firstEightDrop)
        val reason = assertNotNull(RollingRunoutPlanner.advanceRefusal(forced))
        assertTrue("8-ball" in reason, reason)
        val refused = RollingRunoutPlanner.acceptAndAdvance(forced)
        assertTrue(refused.acceptedSteps.isEmpty())
        assertEquals(reason, refused.refusalReason)
        assertNotNull(refused.window, "The shot is still shown so that the user can see what it does")
    }

    @Test
    fun theNaturalShotOnAnEightBallTableStillAdvances() {
        val start = RollingRunoutPlanner.start(eightDropLayout, solidsRules, profile, 3)
        assertTrue(start.canAdvance, "The planner's own shot")
        val accepted = RollingRunoutPlanner.acceptAndAdvance(start)
        assertNull(accepted.refusalReason)
        assertEquals(1, accepted.acceptedSteps.size)
    }

    @Test
    fun aStepFlaggedAsNotLegalAsPlannedIsRefusedAndItsMessageIsShown() {
        val state = start(horizon = 1)
        assertNull(RollingRunoutPlanner.advanceRefusal(state), "Precondition: the planner's own shot can be accepted")
        val window = assertNotNull(state.window)
        val flagged = state.copy(window = window.copy(steps = listOf(window.steps.first().copy(legalAsPlanned = false))))

        val reason = assertNotNull(RollingRunoutPlanner.advanceRefusal(flagged))
        assertFalse(flagged.canAdvance)
        val refused = RollingRunoutPlanner.acceptAndAdvance(flagged)
        assertTrue(refused.acceptedSteps.isEmpty())
        assertEquals(reason, refused.refusalReason)
    }

    @Test
    fun aSpecificRefusalReasonIsKeptWhenTheStepIsAlsoFlaggedNotLegal() {
        val solids = RollingRunoutPlanner.start(eightDropLayout, solidsRules, profile, 1)
        val dropsTheEight = simulateDrops(solids, mapOf(8 to PocketId.SIDE_LEFT))
        val window = assertNotNull(dropsTheEight.window)
        val flagged = dropsTheEight.copy(
            window = window.copy(steps = listOf(window.steps.first().copy(legalAsPlanned = false))),
        )
        val reason = assertNotNull(RollingRunoutPlanner.advanceRefusal(flagged))
        assertTrue("8-ball" in reason, reason)
    }

    /**
     * Straight-in pot on ball #1 into [PocketId.SIDE_LEFT] with [extraBall] in front of [PocketId.SIDE_RIGHT] behind
     * the cue ball: medium draw drops #1 and [extraBall] without scratching; hard draw also scratches in
     * [PocketId.SIDE_RIGHT].
     */
    private fun extraDropDrawLayout(extraBall: Int, otherA: Int, otherB: Int): TableLayout = layoutFrom(
        listOf(0, 1, otherA, otherB, extraBall),
        doubleArrayOf(1.27, 0.55, 1.27, 0.22, 0.65, 0.35, 2.05, 0.95, 1.27, 1.08),
    )

    /**
     * Finds a draw override on ball #1 into [PocketId.SIDE_LEFT] that pots #1 as planned, drops [extraBall] in the
     * nominal simulation, and matches [expectScratch].
     */
    private fun findExtraDropEdit(
        state: RollingPlanState,
        extraBall: Int,
        expectScratch: Boolean,
    ): RollingPlanState {
        val speeds = if (expectScratch) {
            listOf(10.0, 9.5, 9.0, 8.5, 8.0, 7.5, 7.0)
        } else {
            listOf(5.0, 5.5, 6.0, 4.5, 6.5, 4.0, 7.0)
        }
        val verticals = if (expectScratch) {
            listOf(-0.50, -0.45, -0.40)
        } else {
            listOf(-0.45, -0.40, -0.35, -0.50, -0.30)
        }
        for (vertical in verticals) for (speed in speeds) {
            val edited = RollingRunoutPlanner.setOverride(
                state,
                StepOverride(
                    tip = TipOffset(sideRatio = 0.0, verticalRatio = vertical),
                    speedUnits = speed,
                    pocketId = PocketId.SIDE_LEFT,
                    targetBallNumber = 1,
                ),
            )
            val step = edited.focusStep ?: continue
            val simulation = step.uncertainty.postShotSimulation ?: continue
            val scratched = step.uncertainty.nominalTrajectory.hitsScratchPocket != null ||
                simulation.cueBallTrajectory.hitsScratchPocket != null
            if (step.targetBall.number == 1 &&
                step.uncertainty.targetBallPotted &&
                extraBall in droppedBalls(step) &&
                scratched == expectScratch
            ) {
                return edited
            }
        }
        fail("No draw override potted #1 and dropped #$extraBall (expectScratch=$expectScratch)")
    }

    @Test
    fun nineBallEditedShotDroppingAnExtraNonMoneyBallIsAcceptedAndContinues() {
        val layout = extraDropDrawLayout(extraBall = 4, otherA = 2, otherB = 9)
        val start = RollingRunoutPlanner.start(layout, nineBallRules, profile, horizon = 3)
        val edited = findExtraDropEdit(start, extraBall = 4, expectScratch = false)
        val step = assertNotNull(edited.focusStep)
        assertFalse(step.legalAsPlanned, "Precondition: the planner flags extra-drop steps as not legalAsPlanned")
        assertTrue(step.uncertainty.targetBallPotted)
        assertTrue(4 in droppedBalls(step))

        assertNull(RollingRunoutPlanner.advanceRefusal(edited))
        assertTrue(edited.canAdvance)

        val accepted = RollingRunoutPlanner.acceptAndAdvance(edited)

        assertNull(accepted.refusalReason)
        assertFalse(accepted.isRackComplete)
        assertEquals(listOf(1), accepted.acceptedSteps.map { it.targetBall.number })
        assertNull(accepted.focusLayout.ballByNumber(1), "Target ball must be absent from the continuation table")
        assertNull(accepted.focusLayout.ballByNumber(4), "Extra pocketed ball must be absent from the continuation table")
        assertNotNull(accepted.focusLayout.ballByNumber(2))
        assertNotNull(accepted.focusLayout.ballByNumber(9))
        assertEquals(listOf(2, 9), targets(accepted.window))
        assertEquals(listOf(2, 3), accepted.window?.steps?.map { it.stepNumber })
        assertTrue(accepted.canAdvance)
    }

    @Test
    fun nineBallEditedShotDroppingTheMoneyBallCompletesTheRack() {
        val layout = extraDropDrawLayout(extraBall = 9, otherA = 2, otherB = 5)
        val start = RollingRunoutPlanner.start(layout, nineBallRules, profile, horizon = 3)
        val edited = findExtraDropEdit(start, extraBall = 9, expectScratch = false)
        val step = assertNotNull(edited.focusStep)
        assertFalse(step.legalAsPlanned, "Precondition: extra-drop step is not legalAsPlanned")
        assertEquals(1, step.targetBall.number)
        assertTrue(step.uncertainty.targetBallPotted)
        assertTrue(9 in droppedBalls(step))

        assertNull(RollingRunoutPlanner.advanceRefusal(edited))
        assertTrue(edited.canAdvance)

        val accepted = RollingRunoutPlanner.acceptAndAdvance(edited)

        assertNull(accepted.refusalReason)
        assertTrue(accepted.isRackComplete, "Pocketing the 9-ball on a legal hit ends the rack")
        assertNull(accepted.window)
        assertTrue(accepted.alternatives.isEmpty())
        assertFalse(accepted.canAdvance)
        assertTrue(accepted.canGoBack)
        assertNull(accepted.focusLayout.ballByNumber(1))
        assertNull(accepted.focusLayout.ballByNumber(9))
        assertNotNull(accepted.focusLayout.ballByNumber(2), "Other object balls remain, confirming the 9-ball drop ended the rack")

        val reopened = RollingRunoutPlanner.back(accepted)
        assertFalse(reopened.isRackComplete)
        assertTrue(reopened.canAdvance)
    }

    @Test
    fun nineBallEditedShotScratchingWhileDroppingAnExtraBallIsStillRefused() {
        val layout = extraDropDrawLayout(extraBall = 4, otherA = 2, otherB = 9)
        val start = RollingRunoutPlanner.start(layout, nineBallRules, profile, horizon = 3)
        val edited = findExtraDropEdit(start, extraBall = 4, expectScratch = true)
        val step = assertNotNull(edited.focusStep)
        assertFalse(step.legalAsPlanned)
        assertTrue(step.uncertainty.targetBallPotted)
        assertTrue(4 in droppedBalls(step))

        assertFalse(edited.canAdvance)
        val reason = assertNotNull(RollingRunoutPlanner.advanceRefusal(edited))
        assertTrue("scratches" in reason, reason)

        val refused = RollingRunoutPlanner.acceptAndAdvance(edited)
        assertTrue(refused.acceptedSteps.isEmpty())
        assertEquals(reason, refused.refusalReason)
    }

    @Test
    fun eightBallEditedShotDroppingAnExtraBallRemainsRefused() {
        val dropsEightStart = RollingRunoutPlanner.start(
            extraDropDrawLayout(extraBall = 8, otherA = 2, otherB = 9),
            solidsRules,
            profile,
            horizon = 3,
        )
        val dropsEight = findExtraDropEdit(dropsEightStart, extraBall = 8, expectScratch = false)
        assertFalse(dropsEight.canAdvance)
        val eightReason = assertNotNull(RollingRunoutPlanner.advanceRefusal(dropsEight))
        assertTrue("8-ball" in eightReason, eightReason)
        assertEquals(eightReason, RollingRunoutPlanner.acceptAndAdvance(dropsEight).refusalReason)

        val dropsOpponentStart = RollingRunoutPlanner.start(
            extraDropDrawLayout(extraBall = 9, otherA = 2, otherB = 8),
            solidsRules,
            profile,
            horizon = 3,
        )
        val dropsOpponent = findExtraDropEdit(dropsOpponentStart, extraBall = 9, expectScratch = false)
        assertFalse(dropsOpponent.canAdvance)
        val opponentReason = assertNotNull(RollingRunoutPlanner.advanceRefusal(dropsOpponent))
        assertTrue("#9" in opponentReason, opponentReason)
        assertEquals(opponentReason, RollingRunoutPlanner.acceptAndAdvance(dropsOpponent).refusalReason)

        val dropsOwnStart = RollingRunoutPlanner.start(
            extraDropDrawLayout(extraBall = 2, otherA = 9, otherB = 8),
            solidsRules,
            profile,
            horizon = 3,
        )
        val dropsOwn = findExtraDropEdit(dropsOwnStart, extraBall = 2, expectScratch = false)
        assertFalse(assertNotNull(dropsOwn.focusStep).legalAsPlanned)
        assertFalse(dropsOwn.canAdvance)
        val ownReason = assertNotNull(RollingRunoutPlanner.advanceRefusal(dropsOwn))
        assertEquals(ownReason, RollingRunoutPlanner.acceptAndAdvance(dropsOwn).refusalReason)
    }

    @Test
    fun tenBallEditedShotDroppingAnExtraBallIsAcceptedUnderRotationRules() {
        val tenBallRules = GameRulesState(GameType.TEN_BALL)

        val nonMoneyLayout = extraDropDrawLayout(extraBall = 4, otherA = 2, otherB = 10)
        val start = RollingRunoutPlanner.start(nonMoneyLayout, tenBallRules, profile, horizon = 3)
        val edited = findExtraDropEdit(start, extraBall = 4, expectScratch = false)
        val step = assertNotNull(edited.focusStep)
        assertFalse(step.legalAsPlanned)
        assertTrue(4 in droppedBalls(step))

        assertNull(RollingRunoutPlanner.advanceRefusal(edited))
        assertTrue(edited.canAdvance)
        val accepted = RollingRunoutPlanner.acceptAndAdvance(edited)
        assertNull(accepted.refusalReason)
        assertFalse(accepted.isRackComplete)
        assertNull(accepted.focusLayout.ballByNumber(1))
        assertNull(accepted.focusLayout.ballByNumber(4))
        assertEquals(listOf(2, 10), targets(accepted.window))

        val moneyLayout = extraDropDrawLayout(extraBall = 10, otherA = 2, otherB = 5)
        val moneyStart = RollingRunoutPlanner.start(moneyLayout, tenBallRules, profile, horizon = 3)
        val editedMoney = findExtraDropEdit(moneyStart, extraBall = 10, expectScratch = false)
        assertNull(RollingRunoutPlanner.advanceRefusal(editedMoney))
        val completed = RollingRunoutPlanner.acceptAndAdvance(editedMoney)
        assertNull(completed.refusalReason)
        assertTrue(completed.isRackComplete, "Pocketing the 10-ball on a legal hit ends the rack")
        assertNull(completed.window)
        assertNull(completed.focusLayout.ballByNumber(1))
        assertNull(completed.focusLayout.ballByNumber(10))
        assertNotNull(completed.focusLayout.ballByNumber(2))
    }

    // ---------------------------------------------------------------------------------------------
    // Stopping a search
    // ---------------------------------------------------------------------------------------------

    private val stopNow: () -> Boolean = { true }

    /** A hook that lets [polls] polls pass and then asks to stop, so that a search is interrupted part-way. */
    private fun stopAfter(polls: Int): () -> Boolean {
        var seen = 0
        return { ++seen > polls }
    }

    @Test
    fun aStoppedStartHasNoWindowAndRemembersNothing() {
        val stopped = RollingRunoutPlanner.start(nineBallLayout, nineBallRules, profile, 3, shouldStop = stopNow)
        assertNull(stopped.window)
        assertEquals(0, stopped.cache.size)
        assertEquals(nineBallLayout, stopped.baseLayout)

        assertEquals(listOf(1, 2, 3), targets(start().window), "The same call without a stop plans as usual")
    }

    @Test
    fun everyOperationThatSearchesReturnsTheIncomingStateWhenStopped() {
        val s0 = start()
        val sizeBefore = s0.cache.size

        assertSame(s0, RollingRunoutPlanner.acceptAndAdvance(s0, stopNow))
        assertSame(s0, RollingRunoutPlanner.setOverride(s0, speedEdit(s0), stopNow))
        assertSame(s0, RollingRunoutPlanner.withHorizon(s0, 2, shouldStop = stopNow))
        assertSame(s0, RollingRunoutPlanner.rebase(s0, movedBall8(nineBallLayout), shouldStop = stopNow))

        // With an empty memo the planner has to search, so the hook is consulted.
        val edited = RollingRunoutPlanner.setOverride(s0, speedEdit(s0)).copy(cache = PlanCache.EMPTY)
        assertSame(edited, RollingRunoutPlanner.clearOverride(edited, stopNow))
        assertSame(edited, RollingRunoutPlanner.withDefaultFocusStep(edited, stopNow))

        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)
        val resized = RollingRunoutPlanner.withHorizon(s1, 2)
        assertTrue(resized.earlierViews.isEmpty(), "Precondition: Prev has to plan the reopened step afresh")
        assertSame(resized, RollingRunoutPlanner.back(resized, stopNow))

        assertEquals(sizeBefore, s0.cache.size)
    }

    @Test
    fun aSearchInterruptedPartWayLeavesNoPartialResultInTheMemo() {
        val s0 = start()
        for (polls in listOf(1, 5, 25, 120)) {
            val hook = stopAfter(polls)
            val stopped = RollingRunoutPlanner.acceptAndAdvance(s0, hook)
            assertSame(s0, stopped, "Stopped after $polls polls")
        }
        // Whatever the interrupted searches saw, a full search afterwards gives what a fresh session gives.
        assertSamePlan(RollingRunoutPlanner.acceptAndAdvance(start()).window, RollingRunoutPlanner.acceptAndAdvance(s0).window)
    }

    @Test
    fun aHookThatNeverStopsChangesNothing() {
        var polls = 0
        val counted = RollingRunoutPlanner.start(nineBallLayout, nineBallRules, profile, 3, shouldStop = { polls++; false })
        assertTrue(polls > 0, "The search polls the hook")
        assertSamePlan(start().window, counted.window)
    }

    /** Same shots, strokes and numbers (the heat zone is compared by identity, so it is left out). */
    private fun assertSamePlan(expected: RunoutPlan?, actual: RunoutPlan?) {
        val e = assertNotNull(expected)
        val a = assertNotNull(actual)
        assertEquals(e.steps.size, a.steps.size)
        e.steps.zip(a.steps).forEach { (x, y) ->
            assertTrue(sameChoice(x, y), "Step ${x.stepNumber}: ${x.targetBall.number} vs ${y.targetBall.number}")
        }
        assertEquals(e.cumulativeRunoutProbability, a.cumulativeRunoutProbability, 1e-12)
        assertEquals(e.expectedValueScore, a.expectedValueScore, 1e-12)
    }

    @Test
    fun deepAnalysisTailReportsTheProbabilityOfTheStepsThatRemain() {
        fun product(steps: List<PlannedShotStep>): Double {
            var p = 1.0
            steps.forEachIndexed { i, step ->
                p *= step.uncertainty.makeProbability * (1.0 - step.uncertainty.scratchProbability)
                if (i < steps.lastIndex) p *= step.positionHoldProbability
            }
            return p
        }
        val s0 = RollingRunoutPlanner.start(lastThreeLayout, nineBallRules, profile, horizon = 1, deepAnalysis = true)
        val whole = assertNotNull(s0.window)
        assertEquals(whole.cumulativeRunoutProbability, product(whole.steps), 1e-9, "The documented definition")

        val s1 = RollingRunoutPlanner.acceptAndAdvance(s0)
        val tail = assertNotNull(s1.window)
        assertEquals(2, tail.steps.size)
        assertEquals(product(tail.steps), tail.cumulativeRunoutProbability, 1e-12)
        assertTrue(tail.cumulativeRunoutProbability >= whole.cumulativeRunoutProbability - 1e-12, "Fewer steps never lower it")
        assertTrue(tail.cumulativeRunoutProbability <= 1.0)

        val s2 = RollingRunoutPlanner.acceptAndAdvance(s1)
        assertEquals(product(assertNotNull(s2.window).steps), assertNotNull(s2.window).cumulativeRunoutProbability, 1e-12)
    }

    // ---------------------------------------------------------------------------------------------
    // Timing (printed, not asserted)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun reportsAcceptAndAdvanceWallTimeOnTheNineBallFixture() {
        for (horizon in listOf(3, 5)) {
            var state = start(horizon)
            val millis = mutableListOf<Long>()
            repeat(4) {
                val mark = TimeSource.Monotonic.markNow()
                state = RollingRunoutPlanner.acceptAndAdvance(state)
                millis += mark.elapsedNow().inWholeMilliseconds
                assertNotNull(state.window)
            }
            println("acceptAndAdvance 9-ball fixture, horizon $horizon: per call in ms = $millis")
        }
    }
}
