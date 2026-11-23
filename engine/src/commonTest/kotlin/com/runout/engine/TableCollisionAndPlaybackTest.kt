package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.HeatZoneGenerator
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

    @Test
    fun penultimateShotOntoLastBallPrefersEasiestCleanPosition() {
        val layout = TableLayout(
            table = table,
            balls = listOf(
                Ball(number = 0, position = Vec2(0.75, 0.56)),
                Ball(number = 7, position = Vec2(1.22, 0.90)),
                Ball(number = 8, position = Vec2(1.88, 0.62)),
            ),
        )
        val plans = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS),
            horizonShots = 2,
        )
        assertTrue(plans.isNotEmpty())
        val topPlan = plans.first()
        assertEquals(2, topPlan.steps.size, "Expected 2-shot runout (#7 then #8)")
        assertEquals(7, topPlan.steps[0].targetBall.number)
        assertEquals(8, topPlan.steps[1].targetBall.number)
        assertTrue(
            topPlan.steps[1].uncertainty.makeProbability >= 0.70,
            "Penultimate shot should leave an easy, high-make final shot on #8 (was ${topPlan.steps[1].uncertainty.makeProbability})",
        )
    }

    @Test
    fun finalBallShotPrefersCleanLowSpinStroke() {
        val layout = TableLayout(
            table = table,
            balls = listOf(
                Ball(number = 0, position = Vec2(1.30, 0.64)),
                Ball(number = 8, position = Vec2(2.05, 0.96)),
            ),
        )
        val plans = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.EIGHT_BALL, EightBallGroup.EIGHT_BALL),
            horizonShots = 1,
        )
        assertTrue(plans.isNotEmpty())
        val step = plans.first().steps.single()
        assertEquals(8, step.targetBall.number)
        assertTrue(
            step.recommendedTip.magnitudeRatio <= 0.35,
            "Final shot should choose a clean low-spin stroke (was ${step.recommendedTip.magnitudeRatio})",
        )
        assertTrue(
            step.recommendedSpeedScale in 3..6,
            "Final shot should use comfortable pocket speed (was ${step.recommendedSpeedScale})",
        )
    }

    @Test
    fun landingCloudAndHeatZoneStayWithinPlayableBounds() {
        val layout = TableLayout(
            table = table,
            balls = listOf(
                Ball(number = 0, position = Vec2(0.85, 0.22)),
                Ball(number = 1, position = Vec2(1.22, 0.14)),
                Ball(number = 9, position = Vec2(2.15, 0.35)),
            ),
        )
        val plans = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.NINE_BALL),
            horizonShots = 2,
        )
        assertTrue(plans.isNotEmpty())
        val step1 = plans.first().steps.first()
        assertTrue(
            table.isWithinPlayableBounds(step1.uncertainty.meanLandingPosition, table.ballRadiusMeters),
            "Mean landing position must stay inside playable cloth bounds",
        )
        for (pt in step1.uncertainty.landingCloud) {
            assertTrue(
                table.isWithinPlayableBounds(pt, table.ballRadiusMeters),
                "Every landing cloud sigma point ($pt) must stay inside playable cloth bounds",
            )
        }
        val heatZone = HeatZoneGenerator.computeForNextBalls(layout, listOf(9))
        for (cell in heatZone.cells) {
            if (cell.value > 0.0) {
                assertTrue(
                    table.isWithinPlayableBounds(cell.centerMeters, table.ballRadiusMeters),
                    "Every non-zero heat zone cell center (${cell.centerMeters}) must stay inside playable cloth bounds",
                )
            }
        }
    }
}
