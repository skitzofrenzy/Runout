package com.runout.engine

import com.runout.engine.PlannerScenarios.advanced
import com.runout.engine.PlannerScenarios.beginner
import com.runout.engine.PlannerScenarios.cushions
import com.runout.engine.PlannerScenarios.describe
import com.runout.engine.PlannerScenarios.intermediate
import com.runout.engine.PlannerScenarios.landing
import com.runout.engine.PlannerScenarios.nineBall
import com.runout.engine.PlannerScenarios.sidePocketApproachDegrees
import com.runout.engine.PlannerScenarios.solids
import com.runout.engine.PlannerScenarios.usesDrawWithSide
import com.runout.engine.PlannerScenarios.usesSide
import com.runout.engine.math.Vec2
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketKind
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.HeatZoneGenerator
import com.runout.engine.planner.MultiShotPlanner
import com.runout.engine.planner.ProblemBallAnalyzer
import com.runout.engine.planner.RunoutPlan
import com.runout.engine.planner.ShotDifficultyTier
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a good player would play, position by position. Each scenario is a typed-in layout and an assertion about the
 * recommended plan that a pool player would agree with; failure messages print the plan.
 */
class PlannerScenarioTest {

    private val profile = PlayerProfile()

    private fun best(
        layout: TableLayout,
        rules: GameRulesState = nineBall,
        player: PlayerProfile = profile,
        horizon: Int = 3,
    ): RunoutPlan {
        val plan = MultiShotPlanner.planRunout(layout, rules, player, horizonShots = horizon).firstOrNull()
        println("plan: ${describe(plan)}")
        return assertNotNull(plan, "Expected a plan")
    }

    @Test
    fun s01StraightInWithTheNextBallCloseIsAStopShot() {
        val plan = best(PlannerScenarios.stopShot)
        val first = plan.steps.first()
        val ghost = first.compensatedAim.geometricAim.ghostBallCenter
        val roll = landing(first).distanceTo(ghost)
        val why = describe(plan)
        assertEquals(1, first.targetBall.number, why)
        assertEquals(PocketId.FOOT_LEFT, first.pocketId, why)
        assertTrue(abs(first.recommendedTip.clampedSideRatio) < 0.06, "No side on a straight stop shot: $why")
        assertTrue(first.recommendedSpeedUnits <= 5.0, "Soft-to-medium speed: $why")
        assertTrue(roll <= 0.20, "The cue ball stays near the contact point (rolled ${PlannerScenarios.f2(roll)} m): $why")
    }

    @Test
    fun s02ShotOneLeavesTheRightSideOfBallTwoForBallThree() {
        val layout = PlannerScenarios.rightSide
        val two = assertNotNull(layout.ballByNumber(2))
        val potLine = (
            PocketGeometry.aimPoint(two.position, PlannerScenarios.table.pocketById(PocketId.FOOT_RIGHT), two.radiusMeters) -
                two.position
            ).normalized()
        for (horizon in listOf(1, 2, 3)) {
            val plan = best(layout, horizon = horizon)
            val why = "horizon $horizon: ${describe(plan)}"
            val leave = landing(plan.steps.first())
            // Below and right of ball 2's pot line (negative side) the cue ball comes off one cushion toward the 3.
            assertTrue(potLine.cross(leave - two.position) < 0.0, "Shot 1 must leave the right side of ball 2: $why")
            val angle = abs(potLine.angleTo((two.position - leave).normalized()))
            assertTrue(angle >= 0.17, "An angle on ball 2, not straight in: $why")
            if (horizon == 1) {
                // The one-shot look ahead says what it saw.
                assertTrue(plan.explanationReasons.any { "is then a" in it && "#3" in it }, "Leaf explanation: ${plan.explanationReasons}")
            }
            if (plan.steps.size >= 2) {
                val second = plan.steps[1]
                assertEquals(PocketId.FOOT_RIGHT, second.pocketId, "Ball 2 goes in the near corner: $why")
                assertTrue(cushions(second) <= 1, "Shape from ball 2 to ball 3 takes at most one cushion: $why")
            }
        }
    }

    @Test
    fun s03NaturalAngleBeatsDrawWithSide() {
        val plan = best(PlannerScenarios.naturalAngle)
        val why = describe(plan)
        assertTrue(plan.steps.none { usesDrawWithSide(it) }, "No draw with side when a natural stroke gets there: $why")
    }

    @Test
    fun s04ShallowSidePocketIsTheCornerOrRatedHard() {
        val plan = best(PlannerScenarios.shallowSide)
        val first = plan.steps.first()
        val why = describe(plan)
        val pocketKind = PocketId.entries.first { it == first.pocketId }.let {
            if (it == PocketId.SIDE_LEFT || it == PocketId.SIDE_RIGHT) PocketKind.SIDE else PocketKind.CORNER
        }
        if (pocketKind == PocketKind.SIDE) {
            assertEquals(ShotDifficultyTier.HARD, first.makeDifficulty, "A side pocket 28° off the rail is Hard: $why")
        }
    }

    @Test
    fun s05DownTheRailIsHarderHardThanAtPocketSpeed() {
        fun pot(speed: Double): Double {
            val plan = MultiShotPlanner.planRunout(
                PlannerScenarios.downTheRail, nineBall, profile, horizonShots = 1,
                customFirstShotTip = TipOffset(), customFirstShotSpeedUnits = speed,
                customFirstShotTargetBallNumber = 1, customFirstShotPocketId = PocketId.FOOT_LEFT,
            ).firstOrNull()
            println("down the rail at $speed: ${describe(plan)}")
            return assertNotNull(plan).steps.first().uncertainty.makeProbability
        }
        val soft = pot(3.0)
        val hard = pot(8.5)
        assertTrue(hard <= soft - 0.05, "Down the rail rates lower at high speed ($hard) than at pocket speed ($soft)")
    }

    @Test
    fun s06HangerIsNearCertainAndPlayedForPosition() {
        val plan = best(PlannerScenarios.hanger)
        val first = plan.steps.first()
        val why = describe(plan)
        assertEquals(1, first.targetBall.number, why)
        assertTrue(first.uncertainty.makeProbability >= 0.95, "A hanger is near certain: $why")
        assertTrue(plan.steps.size >= 2 && plan.steps[1].uncertainty.makeProbability >= 0.5, "Speed chosen for a makeable next shot: $why")
    }

    @Test
    fun s07BreakOutWhileTheInsuranceBallIsStillThere() {
        val layout = PlannerScenarios.clusterWithInsurance
        val problems = ProblemBallAnalyzer.analyzeProblemBalls(layout, solids)
        val problem = assertNotNull(problems.firstOrNull { it.ballNumber == 2 }, "Ball 2 is a problem ball: $problems")
        val insurance = assertNotNull(problem.recommendedInsuranceBallNumber, "An insurance ball exists")
        assertEquals(1, insurance, "Ball 1 is the insurance ball")

        for (horizon in listOf(1, 2, 3)) {
            assertBreakOutBeforeInsurance(best(layout, solids, horizon = horizon), insurance, horizon)
        }
        // Spending the insurance ball now leaves ball 2 tied up, and the plan says so.
        val spent = assertNotNull(
            MultiShotPlanner.planRunout(layout, solids, profile, horizonShots = 1, customFirstShotTargetBallNumber = insurance).firstOrNull(),
        )
        assertTrue(spent.explanationReasons.any { "#2 is still tied up" in it }, "Rack explanation: ${spent.explanationReasons}")
    }

    private fun assertBreakOutBeforeInsurance(plan: RunoutPlan, insurance: Int, horizon: Int) {
        val why = "horizon $horizon: ${describe(plan)}"
        // Ball 2 is dealt with once it is potted or no other ball is within 2.8 radii of it (the clustering distance).
        val breakoutIndex = plan.steps.indexOfFirst { step ->
            val after = step.resultingLayout ?: return@indexOfFirst false
            val two = after.ballByNumber(2) ?: return@indexOfFirst true
            after.objectBalls().none { it.number != 2 && it.position.distanceTo(two.position) <= 2.8 * two.radiusMeters }
        }
        val insuranceIndex = plan.steps.indexOfFirst { it.targetBall.number == insurance }
        assertTrue(breakoutIndex >= 0, "The plan frees or pots ball 2: $why")
        assertTrue(insuranceIndex < 0 || insuranceIndex > breakoutIndex, "Insurance ball still on the table at the break-out: $why")
    }

    @Test
    fun s08KeyBallForTheEightIsPlayedLast() {
        val plan = best(PlannerScenarios.keyBall, solids)
        val why = describe(plan)
        assertEquals(3, plan.steps.size, why)
        assertTrue(plan.steps.none { it.targetBall.number == 5 }, "Ball 5 is the key ball for the 8 and stays for last: $why")
    }

    @Test
    fun s09EasyFirstTrapIsAvoided() {
        val layout = PlannerScenarios.easyFirstTrap
        val four = assertNotNull(layout.ballByNumber(4))
        // Precondition: from the start and from the foot end, where a stop or stun on 2 and 3 leaves the cue ball, ball 4
        // has no realistic pot.
        for (spot in listOf(assertNotNull(layout.cueBall()).position, Vec2(2.0, 0.6), Vec2(2.3, 0.5), Vec2(2.2, 0.95), Vec2(1.9, 0.3))) {
            val from = layout.withBallPosition(0, spot)
            val best = PlannerScenarios.table.pockets.maxOf { pocket ->
                val aim = GhostBallAiming.computeAimGeometry(assertNotNull(from.cueBall()), four, pocket, from.balls, table = from.table)
                if (aim.isShotPlayable) HeatZoneGenerator.shotQuality(aim, pocket, profile.aimErrorStdRad) else 0.0
            }
            assertTrue(best < 0.10, "Precondition: ball 4 is no realistic pot from $spot ($best)")
        }
        val plan = best(layout, solids)
        val why = describe(plan)
        assertEquals(3, plan.steps.size, "All three solids are planned: $why")
        assertEquals(setOf(2, 3, 4), plan.steps.map { it.targetBall.number }.toSet(), why)
        val shotOnFour = plan.steps.first { it.targetBall.number == 4 }
        assertTrue(shotOnFour.uncertainty.makeProbability >= 0.5, "The cue ball is brought up-table for a real shot on 4: $why")
    }

    @Test
    fun s10OpenThreeBallsRankBySkill() {
        val adv = best(PlannerScenarios.openThree, player = advanced)
        val mid = best(PlannerScenarios.openThree, player = intermediate)
        val beg = best(PlannerScenarios.openThree, player = beginner)
        val why = "adv ${describe(adv)}; int ${describe(mid)}; beg ${describe(beg)}"
        assertEquals(3, adv.steps.size, why)
        assertTrue(adv.cumulativeRunoutProbability >= 0.85, "An advanced player runs three open balls with short routes nearly every time: $why")
        assertTrue(beg.cumulativeRunoutProbability < mid.cumulativeRunoutProbability, why)
        assertTrue(mid.cumulativeRunoutProbability < adv.cumulativeRunoutProbability, why)
    }

    private fun assertPhotoPlanIsSound(layout: TableLayout) {
        val plan = best(layout)
        val why = describe(plan)
        for (step in plan.steps) {
            val side = step.pocketId == PocketId.SIDE_LEFT || step.pocketId == PocketId.SIDE_RIGHT
            if (side && sidePocketApproachDegrees(step) < 40.0) {
                assertTrue(
                    step.makeDifficulty != ShotDifficultyTier.EASY,
                    "Side pocket from ${PlannerScenarios.f2(sidePocketApproachDegrees(step))}° off the rail is not Easy: $why",
                )
            }
        }
        assertTrue(plan.steps.count { usesSide(it) } <= 1, "At most one side-spin shot: $why")
    }

    @Test
    fun s11PhotoLayoutOne() = assertPhotoPlanIsSound(PlannerScenarios.photoOne)

    @Test
    fun s12PhotoLayoutTwo() = assertPhotoPlanIsSound(PlannerScenarios.photoTwo)

    @Test
    fun s13PhotoLayoutThree() = assertPhotoPlanIsSound(PlannerScenarios.photoThree)
}
