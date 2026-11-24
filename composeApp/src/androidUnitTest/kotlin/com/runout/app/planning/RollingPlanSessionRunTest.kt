package com.runout.app.planning

import com.runout.app.ui.SampleLayouts
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.planner.RollingPlanState
import com.runout.engine.planner.StepOverride
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Drives [RollingPlanSession] against the real planner. The test body is the session's "main thread" (it runs the
 * consumer loop and calls every member); planning happens on [Dispatchers.Default].
 */
class RollingPlanSessionRunTest {
    private val scenario = SampleLayouts.all.first()
    private val profile = PlayerProfile()

    private fun restart(
        layout: TableLayout = scenario.layout,
        rules: GameRulesState = scenario.rulesState,
        horizon: Int = 2,
        deep: Boolean = false,
    ) = PlanCommand.Restart(layout, rules, profile, horizon, deep)

    private suspend fun RollingPlanSession.awaitIdle() = withTimeout(TIMEOUT_MILLIS) {
        while (isBusy) delay(10)
    }

    private fun speedEdit(state: RollingPlanState): StepOverride {
        val natural = assertNotNull(state.focusStep).recommendedSpeedUnits
        return StepOverride(speedUnits = if (natural > 5.0) 3.0 else 7.0)
    }

    @Test
    fun advanceWhileAnEditIsPendingActsOnTheEditedShotNeverOnTheOldOne() = runBlocking {
        val session = RollingPlanSession()
        val consumer = launch { session.run() }
        session.submit(restart())
        session.awaitIdle()
        val override = speedEdit(assertNotNull(session.state))

        session.submit(PlanCommand.Edit(override))
        session.submit(PlanCommand.Advance)
        session.awaitIdle()

        val state = assertNotNull(session.state)
        if (state.acceptedSteps.isNotEmpty()) {
            assertEquals(override.speedUnits, state.acceptedSteps.single().recommendedSpeedUnits, "the edited shot was accepted")
        } else {
            assertNotNull(state.refusalReason, "refused with a reason")
            assertTrue(state.isFocusEdited, "the edit is still on the step")
        }
        assertFalse(session.isBusy)
        consumer.cancel()
    }

    @Test
    fun clearWhileAPlanIsBeingComputedStopsItAndLeavesTheCounterAtZero() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val tripped = AtomicBoolean(false)
        val session = RollingPlanSession(Dispatchers.Default) { state, command, stop ->
            started.complete(Unit)
            release.await()
            try {
                applyPlanCommand(state, command) { stop().also { if (it) tripped.set(true) } }
            } finally {
                finished.complete(Unit)
            }
        }
        val consumer = launch { session.run() }
        session.submit(restart())
        withTimeout(TIMEOUT_MILLIS) { started.await() }
        assertTrue(session.isBusy)

        session.clear()
        assertFalse(session.isBusy, "clear returns the counter to zero at once")
        release.countDown()
        withTimeout(TIMEOUT_MILLIS) { finished.await() }
        delay(100)

        assertTrue(tripped.get(), "the search in progress was told to stop")
        assertFalse(session.isBusy)
        assertNull(session.state, "the abandoned plan did not land")
        assertTrue(session.rankedShots.isEmpty())
        consumer.cancel()
    }

    @Test
    fun anExceptionInsideTheComputationKeepsThePlanClearsTheEditAndTheCounterAndShowsTheError() = runBlocking {
        val failing = AtomicBoolean(false)
        val session = RollingPlanSession(Dispatchers.Default) { state, command, stop ->
            if (failing.get() && command is PlanCommand.Edit) throw IllegalStateException("planner failed")
            applyPlanCommand(state, command, stop)
        }
        val consumer = launch { session.run() }
        session.submit(restart())
        session.awaitIdle()
        val before = assertNotNull(session.state)
        assertFalse(session.hasFailed)

        failing.set(true)
        session.edit { it.copy(speedUnits = 3.0) }
        assertNotNull(session.editedOverride, "the edit shows at once while it is planned")
        session.awaitIdle()

        assertSame(before, session.state, "the previous plan stays")
        assertTrue(session.hasFailed)
        assertFalse(session.isBusy)
        assertNull(session.editedOverride, "the failed edit is forgotten")
        assertTrue(RollingPlanSession.PLAN_FAILED_MESSAGE.startsWith("Couldn't plan this table."))

        failing.set(false)
        session.submit(restart())
        session.awaitIdle()
        assertFalse(session.hasFailed, "the next plan that succeeds clears the error")
        assertNotNull(session.state)
        consumer.cancel()
    }

    @Test
    fun aCancellationInsideTheComputationEndsTheLoopAndStillReturnsTheCounter() = runBlocking {
        val session = RollingPlanSession(Dispatchers.Default) { _, _, _ -> throw CancellationException("cancelled") }
        val consumer = launch { session.run() }
        session.submit(restart())
        withTimeout(TIMEOUT_MILLIS) { consumer.join() }
        assertTrue(consumer.isCancelled, "a cancellation is not swallowed")
        assertFalse(session.isBusy)
        assertFalse(session.hasFailed)
    }

    @Test
    fun editThenPrevThenNextThenResetStepRestoresThePlannersPick() = runBlocking {
        val session = RollingPlanSession()
        val consumer = launch { session.run() }
        session.submit(restart(horizon = 2))
        session.awaitIdle()
        session.submit(PlanCommand.Advance)
        session.awaitIdle()
        val atSecond = assertNotNull(session.state)
        assertEquals(1, atSecond.focusIndex)
        val natural = assertNotNull(atSecond.focusStep)

        session.submit(PlanCommand.Edit(speedEdit(atSecond)))
        session.awaitIdle()
        assertTrue(assertNotNull(session.state).showsFocusEdit)
        assertNotNull(session.editedOverride)

        session.submit(PlanCommand.Back)
        session.awaitIdle()
        session.submit(PlanCommand.Advance)
        session.awaitIdle()
        val back = assertNotNull(session.state)
        assertEquals(1, back.focusIndex, "back at the second shot")
        assertTrue(back.isEditStateConsistent)
        assertEquals(back.showsFocusEdit, session.editedOverride != null, "the controls agree with the planner")

        session.resetFocusedStep()
        session.awaitIdle()
        val reset = assertNotNull(session.state)
        assertFalse(reset.isFocusEdited)
        assertNull(session.editedOverride)
        val pick = assertNotNull(reset.focusStep)
        assertEquals(natural.targetBall.number, pick.targetBall.number)
        assertEquals(natural.pocketId, pick.pocketId)
        assertEquals(natural.recommendedTip, pick.recommendedTip)
        assertEquals(natural.recommendedSpeedUnits, pick.recommendedSpeedUnits)
        assertSame(pick, reset.defaultFocusStep)
        consumer.cancel()
    }

    @Test
    fun aRestartStopsTheSearchInProgressAndTheNewTableIsPlanned() = runBlocking {
        val heavy = openEightBallTable()
        val heavyRules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN)
        val started = CompletableDeferred<Unit>()
        val tripAt = AtomicLong(0L)
        val returnedAt = AtomicLong(0L)
        val session = RollingPlanSession(Dispatchers.Default) { state, command, stop ->
            if (command is PlanCommand.Restart && command.layout == heavy) {
                started.complete(Unit)
                Thread.sleep(100) // time for the newer restart below to be submitted
                val result = applyPlanCommand(state, command) {
                    stop().also { if (it) tripAt.compareAndSet(0L, System.nanoTime()) }
                }
                returnedAt.set(System.nanoTime())
                result
            } else {
                applyPlanCommand(state, command, stop)
            }
        }
        val consumer = launch { session.run() }
        session.submit(restart(layout = heavy, rules = heavyRules, horizon = 5, deep = true))
        withTimeout(TIMEOUT_MILLIS) { started.await() }

        session.submit(restart())
        session.awaitIdle()

        assertTrue(tripAt.get() != 0L, "the stop hook of the first search turned true")
        val millisToStop = (returnedAt.get() - tripAt.get()) / 1_000_000
        assertTrue(millisToStop < 1500, "the first search gave up $millisToStop ms after the newer restart")
        assertEquals(scenario.layout, assertNotNull(session.state).baseLayout)
        assertFalse(session.hasFailed)
        consumer.cancel()
    }

    /** Fifteen balls and the cue ball spread over a 9 ft table, nothing touching: plenty of pots to search. */
    private fun openEightBallTable(): TableLayout {
        val table = TableSpec.nineFootPool()
        val xs = listOf(0.8, 1.2, 1.6, 2.0, 2.3)
        val ys = listOf(0.30, 0.635, 0.97)
        val balls = mutableListOf(Ball(0, Vec2(0.35, 0.635), table.ballRadiusMeters))
        var number = 1
        for (y in ys) for (x in xs) {
            balls += Ball(number++, Vec2(x, y), table.ballRadiusMeters)
        }
        return TableLayout(table, balls)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 120_000L
    }
}
