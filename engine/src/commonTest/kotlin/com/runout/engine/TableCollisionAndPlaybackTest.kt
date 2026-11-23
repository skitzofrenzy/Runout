package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.MultiShotPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableCollisionAndPlaybackTest {

    private val table = TableSpec.nineFootPool()
    private val r = TableSpec.POOL_BALL_RADIUS_METERS

    @Test
    fun cueBallDeflectsOffStationaryClusterAndScattersClusterBalls() {
        val targetBall = Ball(number = 1, position = Vec2(1.40, 0.635))
        // Place a 2-ball cluster directly along the 90-degree stun tangent from target -> FOOT_RIGHT.
        val clusterA = Ball(number = 2, position = Vec2(1.50, 0.35))
        val clusterB = Ball(number = 3, position = Vec2(1.50 + 2.05 * r, 0.31))
        val cue = Ball(number = 0, position = Vec2(0.85, 0.635))
        val layout = TableLayout(
            table = table,
            balls = listOf(cue, targetBall, clusterA, clusterB),
        )
        val pocket = table.pockets.first { it.id == PocketId.FOOT_RIGHT }
        val aim = GhostBallAiming.computeAimGeometry(
            cueBall = cue,
            objectBall = targetBall,
            pocket = pocket,
            otherBalls = layout.balls,
            table = table,
        )
        assertTrue(aim.isShotPlayable)

        val sim = TableCollisionSimulator.simulatePostImpact(
            layout = layout,
            targetBall = targetBall,
            aim = aim,
            cueSpeedMps = 2.6,
            tip = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
        )

        assertNull(sim.resultingLayout.ballByNumber(1), "Potted target ball should leave the evolved layout")
        assertFalse(sim.resultingLayout.hasOverlappingBalls(), "Evolved layout must have zero overlaps")
        assertTrue(sim.disturbedBalls.isNotEmpty(), "Cluster balls struck along the cue path should record scatter paths")
        val movedA = sim.resultingLayout.ballByNumber(2)?.position
        assertNotNull(movedA)
        assertTrue(movedA.distanceTo(clusterA.position) > 0.01, "Struck cluster ball #2 should move from its starting spot")
    }

    @Test
    fun multiShotPlannerEvolvesTableLayoutAcrossShotsAfterClusterImpact() {
        val target1 = Ball(number = 1, position = Vec2(1.20, 0.72))
        val blocker2 = Ball(number = 2, position = Vec2(1.42, 0.42))
        val key3 = Ball(number = 3, position = Vec2(1.85, 0.96))
        val nine = Ball(number = 9, position = Vec2(2.10, 0.28))
        val cue = Ball(number = 0, position = Vec2(0.75, 0.56))
        val layout = TableLayout(table = table, balls = listOf(cue, target1, blocker2, key3, nine))

        val plans = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.NINE_BALL),
            playerProfile = PlayerProfile(),
            horizonShots = 3,
        )
        assertTrue(plans.isNotEmpty(), "Expected valid multi-shot plan")
        val step1 = plans.first().steps.first()
        val afterStep1 = assertNotNull(step1.resultingLayout, "Step 1 must carry an evolved post-shot layout")
        assertNull(afterStep1.ballByNumber(step1.targetBall.number), "Evolved layout after Step 1 must remove potted ball")
        assertNotNull(afterStep1.cueBall(), "Evolved layout must retain the cue ball at its landing position")
        assertFalse(afterStep1.hasOverlappingBalls())
    }

    @Test
    fun multiShotPlannerRespectsCustomFirstShotPocketOverride() {
        val layout = TableLayout(
            table = table,
            balls = listOf(
                Ball(number = 0, position = Vec2(0.75, 0.55)),
                Ball(number = 1, position = Vec2(1.18, 0.90)),
                Ball(number = 2, position = Vec2(1.98, 0.34)),
                Ball(number = 9, position = table.footSpot()),
            ),
        )
        val ball1 = layout.ballByNumber(1)!!
        val playable = MultiShotPlanner.playablePocketsForTarget(layout, ball1)
        assertTrue(PocketId.FOOT_RIGHT in playable)
        assertTrue(PocketId.SIDE_RIGHT in playable)

        val forcedSideRight = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.NINE_BALL),
            horizonShots = 3,
            customFirstShotPocketId = PocketId.SIDE_RIGHT,
        )
        assertTrue(forcedSideRight.isNotEmpty())
        assertEquals(PocketId.SIDE_RIGHT, forcedSideRight.first().steps.first().pocketId)

        val forcedFootRight = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.NINE_BALL),
            horizonShots = 3,
            customFirstShotPocketId = PocketId.FOOT_RIGHT,
        )
        assertTrue(forcedFootRight.isNotEmpty())
        assertEquals(PocketId.FOOT_RIGHT, forcedFootRight.first().steps.first().pocketId)
    }
}
