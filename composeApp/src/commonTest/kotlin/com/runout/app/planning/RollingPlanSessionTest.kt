package com.runout.app.planning

import com.runout.app.ui.SampleLayouts
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.planner.RollingPlanState
import com.runout.engine.planner.StepOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RollingPlanSessionTest {
    private val scenario = SampleLayouts.all.first()
    private val profile = PlayerProfile()

    private fun restart(
        layout: TableLayout = scenario.layout,
        rules: GameRulesState = scenario.rulesState,
        horizon: Int = 2,
        deep: Boolean = false,
    ) = PlanCommand.Restart(layout, rules, profile, horizon, deep)

    @Test
    fun consecutiveEditsKeepOnlyTheLast() {
        val first = PlanCommand.Edit(StepOverride(speedUnits = 3.0))
        val second = PlanCommand.Edit(StepOverride(speedUnits = 6.0))
        assertEquals(listOf<PlanCommand>(second), coalescePlanCommands(listOf(first, second)))
    }

    @Test
    fun advanceAndBackAreNeverDropped() {
        val commands = listOf(
            PlanCommand.Edit(StepOverride(speedUnits = 3.0)),
            PlanCommand.Advance,
            PlanCommand.Edit(StepOverride(speedUnits = 6.0)),
            PlanCommand.Back,
            PlanCommand.Back,
        )
        assertEquals(commands, coalescePlanCommands(commands))
    }

    @Test
    fun aRestartDropsEverythingBeforeIt() {
        val commands = listOf(
            PlanCommand.Advance,
            PlanCommand.Edit(StepOverride(speedUnits = 3.0)),
            restart(),
            PlanCommand.Edit(StepOverride(speedUnits = 5.0)),
            PlanCommand.Edit(StepOverride(speedUnits = 6.0)),
        )
        assertEquals(
            listOf(restart(), PlanCommand.Edit(StepOverride(speedUnits = 6.0))),
            coalescePlanCommands(commands),
        )
    }

    @Test
    fun commandsWithoutASessionDoNothing() {
        assertNull(applyPlanCommand(null, PlanCommand.Advance))
        assertNull(applyPlanCommand(null, PlanCommand.Back))
        assertNull(applyPlanCommand(null, PlanCommand.Edit(null)))
    }

    @Test
    fun restartStartsASessionAndAdvanceAcceptsTheFocusedShot() {
        val started = assertNotNull(applyPlanCommand(null, restart(horizon = 2)))
        assertEquals(0, started.focusIndex)
        assertEquals(scenario.layout, started.baseLayout)
        val first = assertNotNull(started.focusStep, "the sample table has a shot to plan")
        assertTrue(started.canAdvance, "the first sample shot can be accepted")

        val advanced = assertNotNull(applyPlanCommand(started, PlanCommand.Advance))
        assertEquals(1, advanced.acceptedSteps.size)
        assertEquals(first.targetBall.number, advanced.acceptedSteps.single().targetBall.number)
        assertEquals(first.stepNumber + 1, advanced.focusIndex + 1)

        val back = assertNotNull(applyPlanCommand(advanced, PlanCommand.Back))
        assertEquals(0, back.acceptedSteps.size)
        assertEquals(first.targetBall.number, back.focusStep?.targetBall?.number)
    }

    @Test
    fun restartWithTheSameSettingsRebasesAndDropsAcceptedShots() {
        val started = assertNotNull(applyPlanCommand(null, restart(horizon = 2)))
        assertTrue(started.canAdvance, "the first sample shot can be accepted")
        val advanced = assertNotNull(applyPlanCommand(started, PlanCommand.Advance))
        assertTrue(advanced.acceptedSteps.isNotEmpty())

        val again = assertNotNull(applyPlanCommand(advanced, restart(horizon = 2)))
        assertTrue(again.acceptedSteps.isEmpty())
        assertEquals(0, again.focusIndex)
        assertEquals(scenario.layout, again.baseLayout)
    }

    @Test
    fun restartWithAnotherHorizonStartsAFreshSession() {
        val started = assertNotNull(applyPlanCommand(null, restart(horizon = 2)))
        val wider = assertNotNull(applyPlanCommand(started, restart(horizon = 3)))
        assertEquals(3, wider.horizon)
        assertTrue(wider.acceptedSteps.isEmpty())
    }

    @Test
    fun editThenResetReturnsToThePlannersChoice() {
        val started = assertNotNull(applyPlanCommand(null, restart(horizon = 1)))
        val step = assertNotNull(started.focusStep)
        val override = StepOverride(speedUnits = if (step.recommendedSpeedUnits > 5.0) 3.0 else 7.0)

        val edited: RollingPlanState = assertNotNull(applyPlanCommand(started, PlanCommand.Edit(override)))
        assertTrue(edited.isFocusEdited)

        val reset = assertNotNull(applyPlanCommand(edited, PlanCommand.Edit(null)))
        assertTrue(!reset.isFocusEdited)
        assertNull(reset.focusOverride)
        assertSame(null, reset.focusOverride)
    }

    @Test
    fun consecutiveHorizonChangesKeepOnlyTheLastAndABeforeARestartIsDropped() {
        val a = PlanCommand.Horizon(2, false)
        val b = PlanCommand.Horizon(4, false)
        assertEquals(listOf<PlanCommand>(b), coalescePlanCommands(listOf(a, b)))
        assertEquals(listOf(restart(), b), coalescePlanCommands(listOf(a, restart(), b)))
        assertEquals(listOf<PlanCommand>(a, PlanCommand.Advance, b), coalescePlanCommands(listOf(a, PlanCommand.Advance, b)))
    }

    @Test
    fun aHorizonChangeKeepsTheAcceptedShots() {
        val started = assertNotNull(applyPlanCommand(null, restart(horizon = 2)))
        assertTrue(started.canAdvance)
        val advanced = assertNotNull(applyPlanCommand(started, PlanCommand.Advance))
        assertEquals(1, advanced.acceptedSteps.size)

        val wider = assertNotNull(applyPlanCommand(advanced, PlanCommand.Horizon(3, false)))
        assertEquals(3, wider.horizon)
        assertEquals(advanced.acceptedSteps, wider.acceptedSteps)
        assertEquals(1, wider.focusIndex)
    }

    @Test
    fun aRestartTakesTheHorizonOfTheCommandAndDropsAcceptedShots() {
        val started = assertNotNull(applyPlanCommand(null, restart(horizon = 2)))
        val advanced = assertNotNull(applyPlanCommand(started, PlanCommand.Advance))
        val restarted = assertNotNull(applyPlanCommand(advanced, restart(horizon = 3)))
        assertEquals(3, restarted.horizon)
        assertTrue(restarted.acceptedSteps.isEmpty())
    }

    @Test
    fun choosingAnAlternativeRouteIsNotAnEdit() {
        val started = assertNotNull(applyPlanCommand(null, restart(horizon = 2)))
        val other = started.alternatives.firstOrNull() ?: return // a table with one route has nothing to choose
        val chosen = assertNotNull(applyPlanCommand(started, PlanCommand.ChooseAlternative(other)))
        assertTrue(!chosen.isFocusEdited)
        assertEquals(other.steps.first().targetBall.number, chosen.focusStep?.targetBall?.number)
        assertTrue(!chosen.showsFocusEdit)
    }

    @Test
    fun theDefaultFocusStepIsTheFocusUntilAnEditAndTheEditShowsInTheStateAlone() {
        val started = assertNotNull(applyPlanCommand(null, restart(horizon = 1)))
        assertSame(started.focusStep, started.defaultFocusStep)
        assertTrue(!started.showsFocusEdit)

        val natural = assertNotNull(started.focusStep)
        val override = StepOverride(speedUnits = if (natural.recommendedSpeedUnits > 5.0) 3.0 else 7.0)
        val edited = assertNotNull(applyPlanCommand(started, PlanCommand.Edit(override)))
        assertTrue(edited.showsFocusEdit)
        val pick = assertNotNull(edited.defaultFocusStep)
        assertEquals(natural.recommendedSpeedUnits, pick.recommendedSpeedUnits)

        val reset = assertNotNull(applyPlanCommand(edited, PlanCommand.Edit(null)))
        assertTrue(!reset.showsFocusEdit)
    }

    @Test
    fun aStoppedCommandReturnsTheStateItWasGiven() {
        val started = assertNotNull(applyPlanCommand(null, restart(horizon = 2)))
        val stopped = applyPlanCommand(started, restart(layout = moved(), horizon = 2), shouldStop = { true })
        assertSame(started, stopped)
    }

    private fun moved(): TableLayout {
        val ball = scenario.layout.balls.first { it.number == 2 }
        return scenario.layout.withBallPosition(2, ball.position.copy(y = ball.position.y + 0.05))
    }
}
