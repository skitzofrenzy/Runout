package com.runout.engine

import com.runout.engine.PlannerScenarios.assertPlanInvariants
import com.runout.engine.PlannerScenarios.describe
import com.runout.engine.PlannerScenarios.nineBall
import com.runout.engine.PlannerScenarios.randomLayout
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.planner.MultiShotPlanner
import com.runout.engine.planner.RunoutPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Properties every run-out plan must have, over random tables of the three games, and the definition of the reported
 * probability: each shot's make and scratch, times the share of the next shot's chance the shot's landing error keeps.
 */
class PlannerInvariantsTest {
    private val profile = PlayerProfile()
    private val tenBall = GameRulesState(GameType.TEN_BALL)
    private val open = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN)
    private val solids = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS)

    /** Sixty fixed random tables: twenty each of nine-ball, ten-ball and 8-ball (open and with solids). */
    private val tables: List<Pair<TableLayout, GameRulesState>> =
        (1..20).map { randomLayout(3000 + it, (0..9).toList()) to nineBall } +
            (1..20).map { randomLayout(4000 + it, (0..10).toList()) to tenBall } +
            (1..20).map {
                randomLayout(5000 + it, listOf(0, 1, 2, 3, 4, 8, 9, 10, 11, 12)) to (if (it % 2 == 0) open else solids)
            }

    @Test
    fun everyPlanOnSixtyRandomTablesKeepsTheInvariants() {
        var plans = 0
        var withPlan = 0
        var belowProduct = 0
        for ((index, entry) in tables.withIndex()) {
            val (table, rules) = entry
            val found = MultiShotPlanner.planRunout(table, rules, profile, horizonShots = 3)
            if (found.isNotEmpty()) withPlan++
            for (plan in found) {
                assertPlanInvariants(plan, table, rules, "table $index (${rules.gameType} ${rules.eightBallGroup}): ${describe(plan)}")
                assertFalse(plan.searchStopped)
                val product = plan.steps.fold(1.0) { acc, s -> acc * s.uncertainty.makeProbability * (1.0 - s.uncertainty.scratchProbability) }
                if (plan.cumulativeRunoutProbability < product - 1e-6) belowProduct++
                plans++
            }
            // Each plan opens with a different shot.
            assertEquals(found.size, found.map { it.steps.first().targetBall.number to it.steps.first().pocketId }.toSet().size)
            // Ranked best first.
            assertTrue(found.zipWithNext().all { (a, b) -> a.expectedValueScore >= b.expectedValueScore - 1e-12 }, "table $index ranking")
        }
        println("invariants: ${tables.size} tables, $withPlan with a plan, $plans plans, $belowProduct below the product of makes")
        assertTrue(withPlan >= 45, "Most random tables have a direct pot ($withPlan of ${tables.size})")
        assertTrue(belowProduct > 0, "Some plans lose probability to position error")
    }

    @Test
    fun aOneShotPlanReportsTheMakeAndScratchOfItsShotOnly() {
        for ((table, rules) in tables.take(10)) {
            val plan = MultiShotPlanner.planRunout(table, rules, profile, horizonShots = 1).firstOrNull() ?: continue
            val step = plan.steps.single()
            assertEquals(
                step.uncertainty.makeProbability * (1.0 - step.uncertainty.scratchProbability),
                plan.cumulativeRunoutProbability,
                1e-12,
            )
        }
    }

    /**
     * Shot 1 has a long route to a landing whose cloud straddles a blocker for ball 2: some of its points leave ball 2
     * with a much worse shot than the nominal landing. The reported probability is below the product of makes by that
     * share.
     */
    @Test
    fun aWideLandingCloudAcrossABlockerLowersTheReportedProbability() {
        // Cue ball near the left cushion; ball 1 is a cut into the foot-right corner, ball 2 waits up-table.
        val table = randomLayout(511, (0..5).toList())
        val plan = assertNotNull(MultiShotPlanner.planRunout(table, nineBall, profile, horizonShots = 2).firstOrNull())
        val why = describe(plan)
        assertPlanInvariants(plan, table, nineBall, why)
        assertEquals(2, plan.steps.size, why)
        val first = plan.steps.first()
        val product = plan.steps.fold(1.0) { acc, s -> acc * s.uncertainty.makeProbability * (1.0 - s.uncertainty.scratchProbability) }
        println("hold: ${PlannerScenarios.f2(first.positionHoldProbability)}, product ${PlannerScenarios.f2(product)}, reported ${PlannerScenarios.f2(plan.cumulativeRunoutProbability)}: $why")
        assertTrue(first.positionHoldProbability < 0.8, "Precondition: the landing cloud loses position on ball 2: $why")
        assertTrue(plan.cumulativeRunoutProbability < 0.8 * product, "Reported below the product of makes: $why")
    }

    @Test
    fun aStopHookThatNeverFiresChangesNothing() {
        for ((table, rules) in listOf(tables[0], tables[25], tables[45])) {
            var polls = 0
            val plain = MultiShotPlanner.planRunout(table, rules, profile, horizonShots = 3)
            val hooked = MultiShotPlanner.planRunoutWithStatus(table, rules, profile, horizonShots = 3, shouldStop = { polls++; false })
            assertFalse(hooked.stopped)
            assertTrue(polls > 0, "The hook is polled")
            assertEquals(plain.map { summary(it) }, hooked.plans.map { summary(it) })
        }
    }

    @Test
    fun aStopRequestReturnsCompletePlansFoundSoFar() {
        val (table, rules) = tables[2]
        for (pollsBeforeStop in listOf(1, 40, 400, 4000)) {
            var polls = 0
            val result = MultiShotPlanner.planRunoutWithStatus(
                table, rules, profile, horizonShots = 3, shouldStop = { ++polls > pollsBeforeStop },
            )
            if (!result.stopped) continue
            assertTrue(result.plans.all { it.searchStopped && it.nextBallHeatZone == null })
            for (plan in result.plans) assertPlanInvariants(plan, table, rules, "stopped after $pollsBeforeStop polls: ${describe(plan)}")
            assertEquals(pollsBeforeStop + 1, polls, "Nothing is polled after the stop")
        }
        val immediately = MultiShotPlanner.planRunoutWithStatus(table, rules, profile, shouldStop = { true })
        assertTrue(immediately.stopped && immediately.plans.isEmpty())
    }

    private fun summary(plan: RunoutPlan): String = describe(plan) + " v=" + plan.expectedValueScore
}
