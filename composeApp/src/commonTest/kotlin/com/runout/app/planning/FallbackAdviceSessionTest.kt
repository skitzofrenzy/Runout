package com.runout.app.planning

import com.runout.app.fallbackChanceLabel
import com.runout.app.fallbackProDetails
import com.runout.app.fallbackTitle
import com.runout.app.ui.SampleLayouts
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.FallbackAdvice
import com.runout.engine.planner.FallbackKind
import com.runout.engine.planner.FallbackShotAdvisor
import com.runout.engine.planner.RollingRunoutPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FallbackAdviceSessionTest {
    private val table = TableSpec.nineFootPool()
    private val nineBall = GameRulesState(GameType.NINE_BALL)

    /** A nine-ball table with no direct pot: the 2 sits behind the 3, which hangs in the foot-right corner. */
    private val noPot = TableLayout(
        table,
        listOf(
            0 to Vec2(1.9110, 0.0905), 2 to Vec2(2.3144, 1.1407), 3 to Vec2(2.4775, 1.2216),
            4 to Vec2(2.0251, 0.2145), 5 to Vec2(2.4734, 0.3367), 6 to Vec2(2.3695, 0.3696),
            7 to Vec2(1.7781, 0.8171), 8 to Vec2(0.5870, 1.0030), 9 to Vec2(1.6620, 0.5606),
        ).map { (n, p) -> Ball(n, p, table.ballRadiusMeters) },
    )

    @Test
    fun noWindowAtTheFocusGivesAdviceAndAPlanGivesNone() {
        val stuck = RollingRunoutPlanner.start(noPot, nineBall, PlayerProfile(), horizon = 3)
        assertNull(stuck.window, "Precondition: the planner has no shot here")
        val advice = assertNotNull(fallbackAdviceFor(stuck))
        assertEquals(FallbackKind.COMBINATION, advice.kind)

        val scenario = SampleLayouts.all.first()
        val planned = RollingRunoutPlanner.start(scenario.layout, scenario.rulesState, PlayerProfile(), horizon = 2)
        assertNotNull(planned.window)
        assertNull(fallbackAdviceFor(planned))
        assertNull(fallbackAdviceFor(null))
    }

    @Test
    fun cardTextNamesTheKindTheBallsAndTheChance() {
        fun advice(kind: FallbackKind, balls: List<Int>, pocket: PocketId? = null, cushion: Vec2? = null) = FallbackAdvice(
            kind = kind,
            targetBallNumbers = balls,
            pocketId = pocket,
            lines = emptyList(),
            cushionPoint = cushion,
            speedUnits = 4.5,
            tip = TipOffset(),
            outcomeProbability = 0.987,
            reason = "",
        )
        assertEquals("Combination: #2 into #3 -> Foot Right", fallbackTitle(advice(FallbackKind.COMBINATION, listOf(2, 3), PocketId.FOOT_RIGHT)))
        assertEquals("Kick: one cushion to #1", fallbackTitle(advice(FallbackKind.KICK, listOf(1))))
        assertEquals("Safety: hit #1, leave no pot", fallbackTitle(advice(FallbackKind.SAFETY, listOf(1))))
        assertEquals("Make", fallbackChanceLabel(FallbackKind.COMBINATION))
        assertEquals("Legal hit", fallbackChanceLabel(FallbackKind.KICK))
        assertEquals("No pot left", fallbackChanceLabel(FallbackKind.SAFETY))
        val pro = fallbackProDetails(advice(FallbackKind.KICK, listOf(1), cushion = Vec2(1.115, 1.2414)))
        assertTrue("Legal hit 98.7%" in pro && "cushion point x 1.12 m, y 1.24 m" in pro, pro)
    }

    @Test
    fun aWeakDirectPotGetsAdviceBesideItAndAnEditedStepOrAStrongPotGetsNone() {
        fun table(vararg balls: Pair<Int, Vec2>) =
            TableLayout(table, balls.map { (n, p) -> Ball(n, p, table.ballRadiusMeters) })
        // A long thin shot at the 1, well under the floor.
        val weak = table(0 to Vec2(0.60, 1.05), 1 to Vec2(1.88, 0.47), 9 to Vec2(1.61, 0.64))
        val state = RollingRunoutPlanner.start(weak, nineBall, PlayerProfile(), horizon = 1)
        val make = assertNotNull(state.focusStep).uncertainty.makeProbability
        assertTrue(make < FallbackShotAdvisor.DIRECT_POT_FLOOR, "Precondition: a weak pot, make $make")
        val advice = assertNotNull(fallbackAdviceFor(state), "advice beside the weak pot")
        assertTrue("low-percentage" in advice.reason, advice.reason)

        val edited = RollingRunoutPlanner.setOverride(state, com.runout.engine.planner.StepOverride(speedUnits = 6.0))
        if (edited.isFocusEdited) assertNull(fallbackAdviceFor(edited), "no advice on a step the user edited")

        val scenario = SampleLayouts.all.first()
        val strong = RollingRunoutPlanner.start(scenario.layout, scenario.rulesState, PlayerProfile(), horizon = 1)
        assertTrue(assertNotNull(strong.focusStep).uncertainty.makeProbability >= FallbackShotAdvisor.DIRECT_POT_FLOOR)
        assertNull(fallbackAdviceFor(strong))
    }
}
