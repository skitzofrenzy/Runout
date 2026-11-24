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
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.MultiShotPlanner
import com.runout.engine.planner.PlannedShotStep
import com.runout.engine.planner.RollingPlanState
import com.runout.engine.planner.RollingRunoutPlanner
import com.runout.engine.planner.RunoutPlan
import com.runout.engine.planner.StepOverride
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
