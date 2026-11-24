package com.runout.engine

import com.runout.engine.PlannerScenarios.f2
import com.runout.engine.PlannerScenarios.layout
import com.runout.engine.PlannerScenarios.nineBall
import com.runout.engine.PlannerScenarios.r
import com.runout.engine.PlannerScenarios.solids
import com.runout.engine.PlannerScenarios.table
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.planner.FallbackAdvice
import com.runout.engine.planner.FallbackKind
import com.runout.engine.planner.FallbackShotAdvisor
import com.runout.engine.planner.MultiShotPlanner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class FallbackShotAdvisorTest {
    private val profile = PlayerProfile()

    private fun describe(advice: FallbackAdvice?): String = advice?.let {
        "${it.kind} balls=${it.targetBallNumbers} pocket=${it.pocketId} speed=${it.speedUnits} " +
            "P=${f2(it.outcomeProbability)} cushion=${it.cushionPoint} \"${it.reason}\""
    } ?: "no advice"

    /** Nine-ball table typed from a photo of a real table: the 2 sits behind the 3, which hangs in the foot-right corner. */
    private val photoNoPot: TableLayout = layout(
        0 to (1.9110 to 0.0905),
        2 to (2.3144 to 1.1407),
        3 to (2.4775 to 1.2216),
        4 to (2.0251 to 0.2145),
        5 to (2.4734 to 0.3367),
        6 to (2.3695 to 0.3696),
        7 to (1.7781 to 0.8171),
        8 to (0.5870 to 1.0030),
        9 to (1.6620 to 0.5606),
    )

    @Test
    fun photoTableWithNoDirectPotGetsTheTwoThreeCombination() {
        assertTrue(MultiShotPlanner.planRunout(photoNoPot, nineBall, profile, horizonShots = 3).isEmpty(), "Precondition: no direct pot")
        val mark = TimeSource.Monotonic.markNow()
        val advice = FallbackShotAdvisor.advise(photoNoPot, nineBall, profile)
        println("photo table: ${describe(advice)} in ${mark.elapsedNow().inWholeMilliseconds} ms")
        assertNotNull(advice)
        // The 3 hangs in the jaws straight beyond the 2: the combination is close to certain, far above any safety.
        assertEquals(FallbackKind.COMBINATION, advice.kind, describe(advice))
        assertEquals(listOf(2, 3), advice.targetBallNumbers)
        assertEquals(PocketId.FOOT_RIGHT, advice.pocketId)
        assertTrue(advice.outcomeProbability >= FallbackShotAdvisor.COMBINATION_MAKE_THRESHOLD, describe(advice))
        assertEquals(3, advice.lines.size)
    }

    @Test
    fun collinearCombinationAimsThroughTheFirstBallsCentreLine() {
        val pocket = table.pocketById(PocketId.FOOT_LEFT)
        val axis = Vec2(1.0, -1.0).normalized()
        val second = Ball(3, pocket.mouthCenter - axis * 0.30, r)
        val aimPoint = PocketGeometry.aimPoint(second.position, pocket, r)
        val line = (aimPoint - second.position).normalized()
        val first = Ball(1, second.position - line * 0.40, r)
        val cue = first.position - line * 0.50
        val geometry = assertNotNull(
            FallbackShotAdvisor.combinationGeometry(layout(0 to (cue.x to cue.y)), cue, r, first, second, pocket),
        )
        assertEquals(0.0, geometry.firstDirection.cross(line), 1e-9)
        assertEquals(0.0, geometry.secondCutRadians, 1e-9)
        assertEquals(0.0, geometry.firstCutRadians, 1e-9)
        // The cue ball aims along the line through the first ball's centre.
        assertEquals(0.0, (geometry.cueGhost - cue).normalized().cross(line), 1e-9)
        assertEquals(0.0, (first.position - cue).normalized().cross(line), 1e-9)
    }

    @Test
    fun offsetCombinationGhostPositionsMatchTheGhostBallConstruction() {
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val second = Ball(5, Vec2(2.20, 1.00), r)
        val first = Ball(2, Vec2(1.85, 0.95), r)
        val cue = Vec2(1.40, 0.70)
        val geometry = assertNotNull(
            FallbackShotAdvisor.combinationGeometry(layout(0 to (cue.x to cue.y)), cue, r, first, second, pocket),
        )
        val aim = PocketGeometry.aimPoint(second.position, pocket, r)
        val secondDir = (aim - second.position).normalized()
        val firstGhost = second.position - secondDir * (2.0 * r)
        val firstDir = (firstGhost - first.position).normalized()
        val cueGhost = first.position - firstDir * (2.0 * r)
        assertEquals(firstGhost.x, geometry.firstGhost.x, 1e-12)
        assertEquals(firstGhost.y, geometry.firstGhost.y, 1e-12)
        assertEquals(cueGhost.x, geometry.cueGhost.x, 1e-12)
        assertEquals(cueGhost.y, geometry.cueGhost.y, 1e-12)
        assertEquals(firstDir.angleTo(secondDir), geometry.secondCutRadians, 1e-12)
        assertTrue(abs(geometry.secondCutRadians) > 0.05, "An offset case: the second contact is a cut")
    }

    @Test
    fun eightBallNeverPlaysACombinationOntoAnOpponentsBall() {
        // The photo table as an 8-ball position: the shooter has solids and only the 2 is left; the ball in the jaws
        // beyond it is a stripe.
        fun eightBallTable(jawBall: Int) = layout(
            0 to (1.9110 to 0.0905),
            2 to (2.3144 to 1.1407),
            jawBall to (2.4775 to 1.2216),
            12 to (2.0251 to 0.2145),
            13 to (2.4734 to 0.3367),
            14 to (2.3695 to 0.3696),
            15 to (1.7781 to 0.8171),
            8 to (0.5870 to 1.0030),
            10 to (1.6620 to 0.5606),
        )
        val opponents = FallbackShotAdvisor.advise(eightBallTable(jawBall = 11), solids, profile)
        println("8-ball, stripe in the jaws: ${describe(opponents)}")
        assertTrue(opponents == null || 11 !in opponents.targetBallNumbers, describe(opponents))
        // The same table with the jaw ball one of the shooter's own (a solid) offers the combination.
        val own = assertNotNull(FallbackShotAdvisor.advise(eightBallTable(jawBall = 3), solids, profile))
        assertEquals(FallbackKind.COMBINATION, own.kind, describe(own))
        assertEquals(listOf(2, 3), own.targetBallNumbers)
    }

    @Test
    fun kickGoesAroundAWallOfBallsToMakeALegalHit() {
        val wall = listOf(2, 3, 4, 5, 6).mapIndexed { i, n -> n to (1.75 to 0.635 + (i - 2) * 0.06) }
        val kickTable = layout(*(listOf(0 to (0.60 to 0.635), 1 to (2.00 to 0.635), 9 to (0.40 to 1.10)) + wall).toTypedArray())
        val advice = assertNotNull(FallbackShotAdvisor.advise(kickTable, nineBall, profile), "A kick is offered")
        println("kick: ${describe(advice)}")
        assertEquals(FallbackKind.KICK, advice.kind, describe(advice))
        assertEquals(listOf(1), advice.targetBallNumbers)
        val cushion = assertNotNull(advice.cushionPoint)
        val onACushion = cushion.x <= r + 0.003 || cushion.x >= table.lengthMeters - r - 0.003 ||
            cushion.y <= r + 0.003 || cushion.y >= table.widthMeters - r - 0.003
        assertTrue(onACushion, "The cushion point lies on a cushion line: $cushion")
        // Both legs of the drawn path clear the wall.
        val blockers = kickTable.objectBalls().filter { it.number != 1 }
        for (line in advice.lines) {
            assertTrue(GhostBallAiming.isSegmentClear(line.from, line.to, r, blockers), "Leg ${line.from} -> ${line.to} clears the wall")
        }
        val contact = advice.lines.last().to
        assertEquals(2.0 * r, contact.distanceTo(Vec2(2.00, 0.635)), 2e-3)
        assertTrue(advice.outcomeProbability > 0.0)
    }

    @Test
    fun safetyLeavesTheNextBallHiddenFromEveryPocket() {
        // Ball 1 lies on the left long cushion with no pot from the cue ball; ball 9 is a single blocker in the middle.
        val safetyTable = layout(
            0 to (0.95 to 1.00),
            1 to (1.00 to r + 0.001),
            9 to (1.10 to 0.45),
        )
        assertTrue(MultiShotPlanner.planRunout(safetyTable, nineBall, profile, horizonShots = 1).isEmpty(), "Precondition: no direct pot")
        val advice = assertNotNull(FallbackShotAdvisor.advise(safetyTable, nineBall, profile))
        println("safety: ${describe(advice)} rest=${advice.cueBallRest}")
        assertEquals(FallbackKind.SAFETY, advice.kind, describe(advice))
        val after = assertNotNull(advice.resultingLayout)
        assertEquals(1.0, FallbackShotAdvisor.hiddenShare(after, nineBall), 1e-12, "Ball 1 is hidden for every pocket: ${describe(advice)}")
    }

    @Test
    fun nothingIsReturnedWhenADirectPotExists() {
        val open = layout(0 to (0.60 to 0.635), 1 to (1.20 to 0.40), 2 to (1.60 to 0.90), 9 to (2.00 to 0.50))
        assertTrue(MultiShotPlanner.planRunout(open, nineBall, profile).isNotEmpty())
        assertNull(FallbackShotAdvisor.advise(open, nineBall, profile))
    }

    @Test
    fun adviceIsDeterministic() {
        assertEquals(FallbackShotAdvisor.advise(photoNoPot, nineBall, profile), FallbackShotAdvisor.advise(photoNoPot, nineBall, profile))
    }
}
