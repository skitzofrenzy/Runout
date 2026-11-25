package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.PostShotTableSimulation
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The simulator's event budget, and what it does with balls that start in a pocket or carry their own mass. */
class SimulationBudgetTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters

    /** A fifteen-ball triangle with its apex on the foot spot, balls [gap] apart. */
    private fun rack(gap: Double): List<Ball> {
        val d = 2.0 * r + gap
        val apex = table.footSpot()
        val balls = ArrayList<Ball>()
        var number = 1
        for (row in 0 until 5) {
            for (k in 0..row) {
                balls += Ball(number++, Vec2(apex.x + row * d * sqrt(3.0) / 2.0, apex.y + (k - row / 2.0) * d))
            }
        }
        return balls
    }

    private fun breakShot(cueAt: Vec2, speed: Double, tip: TipOffset, gap: Double, maxEvents: Int? = null): PostShotTableSimulation {
        val balls = rack(gap)
        val cue = Ball(0, cueAt)
        val apex = balls.first()
        val aim = GhostBallAiming.computeAimGeometryToPoint(cue, apex, apex.position + Vec2(1.0, 0.0), table = table)
        val layout = TableLayout(table, listOf(cue) + balls)
        return if (maxEvents == null) {
            TableCollisionSimulator.simulatePostImpact(layout, apex, aim, speed, tip)
        } else {
            TableCollisionSimulator.simulatePostImpact(layout, apex, aim, speed, tip, maxEvents)
        }
    }

    @Test
    fun defaultBudgetGrowsWithTheBallCount() {
        assertEquals(TableCollisionSimulator.DEFAULT_MAX_EVENTS + 16 * TableCollisionSimulator.MAX_EVENTS_PER_BALL, TableCollisionSimulator.defaultMaxEvents(16))
        assertTrue(TableCollisionSimulator.defaultMaxEvents(16) > TableCollisionSimulator.defaultMaxEvents(2))
    }

    @Test
    fun rackBreakWithTheDefaultBudgetMatchesAnUnlimitedRunOrSaysItWasCut() {
        val shots = listOf(
            Triple(Vec2(0.635, 0.635), 8.0, TipOffset()),
            Triple(Vec2(0.60, 0.50), 6.0, TipOffset(verticalRatio = -0.2)),
            Triple(Vec2(0.70, 0.80), 4.0, TipOffset(verticalRatio = 0.2)),
            Triple(Vec2(0.635, 0.30), 9.5, TipOffset()),
        )
        var completeRuns = 0
        for ((cueAt, speed, tip) in shots) {
            for (gap in listOf(0.0, 1e-4)) {
                val default = breakShot(cueAt, speed, tip, gap)
                val unlimited = breakShot(cueAt, speed, tip, gap, maxEvents = 100_000)
                assertFalse(unlimited.truncated)
                if (default.truncated) continue
                completeRuns++
                assertEquals(unlimited.resultingLayout.balls.map { it.number }, default.resultingLayout.balls.map { it.number })
                for (ball in unlimited.resultingLayout.balls) {
                    val same = assertNotNull(default.resultingLayout.ballByNumber(ball.number))
                    assertTrue(ball.position.distanceTo(same.position) < 1e-3, "ball ${ball.number} at speed $speed")
                }
            }
        }
        assertTrue(completeRuns >= 6, "the default budget completes ordinary breaks ($completeRuns of 8)")
    }

    @Test
    fun aCutShortRunIsFlaggedAndEndsWithBallsAtRest() {
        val cut = breakShot(Vec2(0.635, 0.635), 8.0, TipOffset(), gap = 0.0, maxEvents = 12)
        assertTrue(cut.truncated)
        assertTrue(cut.cueBallTrajectory.truncated)
        // Balls are run on to rest, against cushions and pockets, rather than left where the budget ran out.
        for (ball in cut.resultingLayout.balls) {
            assertTrue(table.isWithinPlayableBounds(ball.position, ball.radiusMeters), "ball ${ball.number} at ${ball.position}")
        }
        val full = breakShot(Vec2(0.635, 0.635), 8.0, TipOffset(), gap = 0.0)
        assertFalse(full.truncated)
    }

    @Test
    fun objectBallThatStartsInAPocketDropsAtOnce() {
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val inPocket = Ball(7, pocket.holeCenter)
        val target = Ball(1, Vec2(1.2, 0.5))
        val cue = Ball(0, Vec2(0.7, 0.5))
        val aim = GhostBallAiming.computeAimGeometryToPoint(cue, target, Vec2(2.0, 0.5), table = table)
        val sim = TableCollisionSimulator.simulatePostImpact(TableLayout(table, listOf(cue, target, inPocket)), target, aim, 1.0, TipOffset())
        assertEquals(PocketId.FOOT_RIGHT, sim.disturbedBalls.firstOrNull { it.ballNumber == 7 }?.pocketedIn)
        assertNull(sim.resultingLayout.ballByNumber(7))
    }

    @Test
    fun resultingLayoutKeepsEachBallsMass() {
        val cue = Ball(0, Vec2(0.7, 0.5), massKg = 0.17)
        val target = Ball(1, Vec2(1.2, 0.5), massKg = 0.16)
        val other = Ball(2, Vec2(1.2, 1.0), massKg = 0.155)
        val aim = GhostBallAiming.computeAimGeometryToPoint(cue, target, Vec2(2.0, 0.5), table = table)
        val sim = TableCollisionSimulator.simulatePostImpact(TableLayout(table, listOf(cue, target, other)), target, aim, 0.8, TipOffset())
        assertEquals(0.17, assertNotNull(sim.resultingLayout.ballByNumber(0)).massKg)
        assertEquals(0.155, assertNotNull(sim.resultingLayout.ballByNumber(2)).massKg)
        sim.resultingLayout.ballByNumber(1)?.let { assertEquals(0.16, it.massKg) }
    }
}
