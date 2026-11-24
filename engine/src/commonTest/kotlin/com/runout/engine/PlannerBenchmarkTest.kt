package com.runout.engine

import com.runout.engine.PlannerScenarios.assertPlanInvariants
import com.runout.engine.PlannerScenarios.describe
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.planner.MultiShotPlanner
import com.runout.engine.planner.RollingRunoutPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Measurements of the run-out planner, printed to the test output: wall time of a 3-shot plan on a nine-ball rack, a
 * photo layout, an 8-ball table with five solids left and a 15-ball table, and of deep analysis; how quickly a stop
 * request is honoured; how often a rolling plan changes a step it has already shown; and the self-play comparison
 * with the greedy baseline. Timings are not asserted (they depend on the machine); what is asserted holds on any
 * machine: the plans keep their invariants, and a stop request ends the search with consistent plans.
 */
class PlannerBenchmarkTest {

    private val table = PlannerScenarios.table
    private val nineBall = GameRulesState(GameType.NINE_BALL)
    private val openEight = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN)
    private val solids = PlannerScenarios.solids

    private fun layoutFrom(numbers: List<Int>, xy: DoubleArray): TableLayout = TableLayout(
        table,
        numbers.mapIndexed { i, n -> Ball(number = n, position = Vec2(xy[2 * i], xy[2 * i + 1]), radiusMeters = table.ballRadiusMeters) },
    )

    /** Cue ball and balls 1-9 spread over the table. */
    private val nineBallRacks: List<TableLayout> = listOf(
        doubleArrayOf(1.28, 1.02, 1.59, 1.02, 1.13, 0.77, 1.44, 0.38, 2.28, 0.99, 2.14, 0.35, 2.27, 0.60, 0.78, 0.76, 0.41, 0.30, 1.76, 0.39),
        doubleArrayOf(0.71, 0.99, 0.62, 0.72, 0.85, 0.32, 1.46, 0.27, 1.55, 0.90, 0.99, 0.59, 2.20, 0.59, 1.29, 0.70, 1.89, 0.72, 2.27, 1.07),
        doubleArrayOf(1.53, 0.59, 1.81, 0.46, 1.95, 1.01, 2.14, 0.44, 0.86, 0.77, 0.55, 0.85, 0.48, 0.26, 2.25, 0.78, 1.25, 0.55, 0.25, 0.99),
        doubleArrayOf(0.65, 0.38, 0.35, 1.04, 1.90, 0.48, 1.66, 0.65, 1.45, 0.29, 1.82, 0.95, 1.13, 0.25, 0.66, 0.68, 2.24, 0.60, 1.30, 0.97),
        doubleArrayOf(0.62, 0.43, 0.57, 0.85, 1.61, 0.62, 2.04, 0.93, 2.29, 0.57, 1.46, 0.88, 0.33, 0.50, 0.29, 0.88, 1.02, 0.97, 1.18, 0.67),
        doubleArrayOf(1.69, 0.91, 2.03, 0.71, 1.97, 0.99, 2.28, 0.53, 1.11, 0.29, 0.56, 0.53, 0.77, 0.34, 1.16, 0.61, 0.61, 0.89, 1.35, 0.95),
        doubleArrayOf(1.43, 0.30, 2.05, 0.96, 1.47, 1.03, 0.97, 0.70, 0.27, 0.77, 0.79, 0.35, 1.17, 0.49, 0.75, 1.02, 2.12, 0.64, 1.53, 0.74),
    ).map { layoutFrom((0..9).toList(), it) } + listOf(PlannerScenarios.photoOne, PlannerScenarios.photoTwo, PlannerScenarios.photoThree)

    private fun medianMillis(runs: Int, warmUp: Int = 3, block: () -> Unit): Long {
        repeat(warmUp) { block() }
        val times = (1..runs).map {
            val mark = TimeSource.Monotonic.markNow()
            block()
            mark.elapsedNow().inWholeMilliseconds
        }.sorted()
        return times[times.size / 2]
    }

    @Test
    fun reportsThreeShotPlanWallTime() {
        val cases = listOf(
            Triple("nine-ball rack", nineBallRacks.first(), nineBall),
            Triple("nine-ball photo layout", PlannerScenarios.photoOne, nineBall),
            Triple("eight-ball (5 solids left)", PlannerScenarios.keyBall, solids),
            Triple("open 15-ball table", PlannerScenarios.openFifteen, openEight),
            Triple("15-ball table, solids", PlannerScenarios.openFifteen, solids),
        )
        val report = StringBuilder("benchmark: 3-shot planRunout median ms:")
        for ((name, layout, rules) in cases) {
            val ms = medianMillis(5) { MultiShotPlanner.planRunout(layout, rules, PlayerProfile(), horizonShots = 3) }
            report.append(" $name = $ms;")
            val plans = MultiShotPlanner.planRunout(layout, rules, PlayerProfile(), horizonShots = 3)
            assertTrue(plans.isNotEmpty(), "$name has a plan")
            for (plan in plans) assertPlanInvariants(plan, layout, rules, "$name: ${describe(plan)}")
        }
        println(report)
    }

    @Test
    fun reportsDeepAnalysisWallTime() {
        val cases = listOf(
            Triple("nine-ball rack", nineBallRacks.first(), nineBall),
            Triple("15-ball table, solids", PlannerScenarios.openFifteen, solids),
            Triple("open 15-ball table", PlannerScenarios.openFifteen, openEight),
        )
        val report = StringBuilder("benchmark: deep analysis median ms:")
        for ((name, layout, rules) in cases) {
            val ms = medianMillis(3, warmUp = 1) { MultiShotPlanner.planRunout(layout, rules, PlayerProfile(), deepAnalysis = true) }
            val plans = MultiShotPlanner.planRunout(layout, rules, PlayerProfile(), deepAnalysis = true)
            report.append(" $name = $ms (${plans.firstOrNull()?.steps?.size ?: 0} shots);")
            assertTrue(plans.isNotEmpty(), "$name has a plan")
            for (plan in plans) assertPlanInvariants(plan, layout, rules, "$name: ${describe(plan)}")
        }
        println(report)
    }

    /**
     * A stop request raised part-way through the worst case measured here (deep analysis of the open 15-ball table) ends
     * the search quickly, with plans that are consistent from the table onward. The bound asserted (one second) is far
     * above what is measured, so that a slow machine cannot fail it; the measured latency is printed.
     */
    @Test
    fun aStopRequestIsHonouredQuickly() {
        val layout = PlannerScenarios.openFifteen
        MultiShotPlanner.planRunout(layout, openEight, PlayerProfile(), horizonShots = 1)
        val latencies = ArrayList<Long>()
        for (stopAfterMs in listOf(20L, 150L, 400L)) {
            val start = TimeSource.Monotonic.markNow()
            var requestedAt: TimeSource.Monotonic.ValueTimeMark? = null
            val result = MultiShotPlanner.planRunoutWithStatus(
                layout, openEight, PlayerProfile(), deepAnalysis = true,
                shouldStop = {
                    val due = start.elapsedNow().inWholeMilliseconds >= stopAfterMs
                    if (due && requestedAt == null) requestedAt = TimeSource.Monotonic.markNow()
                    due
                },
            )
            val requested = requestedAt ?: continue
            val latency = requested.elapsedNow().inWholeMilliseconds
            latencies += latency
            assertTrue(result.stopped, "The result says it was stopped")
            assertTrue(latency < 1000, "Stop honoured within a second (took $latency ms after $stopAfterMs ms)")
            for (plan in result.plans) {
                assertTrue(plan.searchStopped)
                assertPlanInvariants(plan, layout, openEight, "stopped after $stopAfterMs ms: ${describe(plan)}")
            }
        }
        println("benchmark: stop latency (ms from the request to the return), deep analysis of the open 15-ball table: $latencies")
        assertTrue(latencies.isNotEmpty(), "At least one stop request fell inside the search")
    }

    @Test
    fun reportsHowOftenARollingPlanChangesAShownStep() {
        var advances = 0
        var refined = 0
        var updated = 0
        var focusRefined = 0
        var focusUpdated = 0
        for (layout in nineBallRacks) {
            var state = RollingRunoutPlanner.start(layout, nineBall, PlayerProfile(), horizon = 3)
            repeat(4) {
                if (!state.canAdvance) return@repeat
                state = RollingRunoutPlanner.acceptAndAdvance(state)
                if (state.window == null) return@repeat
                advances++
                if (state.refinedStepNumbers.isNotEmpty()) refined++
                if (state.updatedStepNumbers.isNotEmpty()) updated++
                val focus = state.focusIndex + 1
                if (focus in state.refinedStepNumbers) focusRefined++
                if (focus in state.updatedStepNumbers) focusUpdated++
            }
        }
        println(
            "benchmark: rolling plan over ${nineBallRacks.size} racks: $advances advances, " +
                "$refined with a refined step, $updated with an updated step, " +
                "shot now to be played: $focusRefined with a different stroke, $focusUpdated with a different ball or pocket",
        )
        assertTrue(advances >= nineBallRacks.size, "Every rack advances at least once ($advances advances)")
        assertTrue(focusRefined <= advances && focusUpdated <= advances)
    }

    @Test
    fun reportsSelfPlayAgainstTheGreedyBaseline() {
        fun l(vararg xy: Double) = layoutFrom(listOf(0, 1, 2, 3), xy)
        val racks = listOf(
            l(0.60, 0.635, 1.20, 0.40, 1.60, 0.90, 2.00, 0.50),
            l(0.55, 0.30, 1.00, 0.75, 1.45, 0.35, 1.95, 0.95),
            l(2.00, 0.30, 1.55, 0.60, 1.05, 0.95, 0.60, 0.45),
            l(0.70, 1.00, 1.30, 0.80, 1.75, 0.45, 2.15, 0.85),
        ).map { it to nineBall }
        val result = MultiShotPlanner.evaluateAgainstGreedyBaseline(racks)
        println(
            "benchmark: self-play over ${result.rackCount} racks: beam ${result.beamPlannerRunoutRate}, " +
                "greedy ${result.greedyBaselineRunoutRate}, relative ${result.relativeImprovementFraction}",
        )
        assertEquals(racks.size, result.rackCount)
        assertTrue(result.beamPlannerRunoutRate in 0.0..1.0 && result.greedyBaselineRunoutRate in 0.0..1.0)
    }
}
