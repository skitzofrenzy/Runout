package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.planner.MultiShotPlanner
import com.runout.engine.planner.OneShotPlanner
import com.runout.engine.planner.PlanningPauseReason
import com.runout.engine.planner.PlanningReadinessValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanningReadinessValidatorTest {

    private val table = TableSpec.nineFootPool()

    private fun layoutWith(vararg numbers: Int): TableLayout {
        val balls = numbers.mapIndexed { idx, num ->
            val col = idx % 5
            val row = idx / 5
            Ball(
                number = num,
                position = Vec2(0.45 + col * 0.35, 0.30 + row * 0.30),
            )
        }
        return TableLayout(table = table, balls = balls)
    }

    @Test
    fun eightBallMissingEightBallPausesAndAsksToConfirm() {
        val layout = layoutWith(0, 1, 3, 11, 14)
        val rules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS)

        val result = PlanningReadinessValidator.evaluate(layout, rules)

        assertFalse(result.isReady)
        assertEquals(listOf(PlanningPauseReason.MISSING_END_BALL), result.pauseReasons)
        assertEquals(8, result.expectedEndBallNumber)
        assertEquals(listOf(8), result.missingBallNumbers)
        assertTrue(result.headline.contains("8-ball"))
        assertTrue(result.guidance.contains("#8"))
    }

    @Test
    fun nineBallMissingNineBallPausesAndAsksToConfirm() {
        val layout = layoutWith(0, 1, 2, 3, 5)
        val rules = GameRulesState(GameType.NINE_BALL)

        val result = PlanningReadinessValidator.evaluate(layout, rules)

        assertFalse(result.isReady)
        assertEquals(listOf(PlanningPauseReason.MISSING_END_BALL), result.pauseReasons)
        assertEquals(9, result.expectedEndBallNumber)
        assertEquals(listOf(9), result.missingBallNumbers)
        assertTrue(result.headline.contains("9-ball"))
    }

    @Test
    fun tenBallMissingTenBallPausesAndAsksToConfirm() {
        val layout = layoutWith(0, 1, 4, 6, 9)
        val rules = GameRulesState(GameType.TEN_BALL)

        val result = PlanningReadinessValidator.evaluate(layout, rules)

        assertFalse(result.isReady)
        assertEquals(listOf(PlanningPauseReason.MISSING_END_BALL), result.pauseReasons)
        assertEquals(10, result.expectedEndBallNumber)
        assertEquals(listOf(10), result.missingBallNumbers)
        assertTrue(result.headline.contains("10-ball"))
    }

    @Test
    fun missingCueBallPausesPlanning() {
        val layout = layoutWith(1, 2, 9)
        val rules = GameRulesState(GameType.NINE_BALL)

        val result = PlanningReadinessValidator.evaluate(layout, rules)

        assertFalse(result.isReady)
        assertEquals(listOf(PlanningPauseReason.MISSING_CUE_BALL), result.pauseReasons)
        assertEquals(listOf(0), result.missingBallNumbers)
    }

    @Test
    fun eightBallUnsetGroupPausesAndBlocksSilentPlanning() {
        val layout = layoutWith(0, 2, 5, 8, 10, 14)
        val rules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.UNSET)

        val result = PlanningReadinessValidator.evaluate(layout, rules)

        assertFalse(result.isReady)
        assertTrue(result.needsEightBallGroupSelection)
        assertEquals(listOf(PlanningPauseReason.EIGHT_BALL_GROUP_UNSET), result.pauseReasons)
        assertTrue(result.guidance.contains("Solids, Stripes, or Open Table"))
        assertTrue(rules.legalTargetBalls(layout).isEmpty())
        assertTrue(OneShotPlanner.rankShots(layout, rules).isEmpty())
        assertTrue(MultiShotPlanner.planRunout(layout, rules).isEmpty())
    }

    @Test
    fun ukBlackballUnsetGroupPromptsYellowsOrReds() {
        val layout = layoutWith(0, 1, 2, 8, 9, 10)
        val rules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.UNSET)
        val ukSet = BallSetProfile.ukBlackballPool()

        val result = PlanningReadinessValidator.evaluate(layout, rules, ukSet)

        assertFalse(result.isReady)
        assertTrue(result.needsEightBallGroupSelection)
        assertTrue(result.headline.contains("Yellows"))
        assertTrue(result.guidance.contains("Yellows, Reds, or Open Table"))
        assertEquals("Yellows (1–7)", EightBallGroup.SOLIDS.displayNameFor(ukSet))
        assertEquals("Reds (9–15)", EightBallGroup.STRIPES.displayNameFor(ukSet))
    }

    @Test
    fun missingEndBallAndUnsetGroupReportBothReasons() {
        val layout = layoutWith(0, 1, 4, 11, 13)
        val rules = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.UNSET)

        val result = PlanningReadinessValidator.evaluate(layout, rules)

        assertFalse(result.isReady)
        assertEquals(
            listOf(PlanningPauseReason.MISSING_END_BALL, PlanningPauseReason.EIGHT_BALL_GROUP_UNSET),
            result.pauseReasons,
        )
        assertEquals(listOf(8), result.missingBallNumbers)
        assertTrue(result.needsEightBallGroupSelection)
    }

    @Test
    fun validLayoutsForEightNineAndTenBallAreReady() {
        val eightReady = PlanningReadinessValidator.evaluate(
            layout = layoutWith(0, 1, 8, 12),
            rulesState = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS),
        )
        val nineReady = PlanningReadinessValidator.evaluate(
            layout = layoutWith(0, 1, 4, 9),
            rulesState = GameRulesState(GameType.NINE_BALL),
        )
        val tenReady = PlanningReadinessValidator.evaluate(
            layout = layoutWith(0, 1, 7, 10),
            rulesState = GameRulesState(GameType.TEN_BALL),
        )

        assertTrue(eightReady.isReady)
        assertTrue(nineReady.isReady)
        assertTrue(tenReady.isReady)
    }
}
