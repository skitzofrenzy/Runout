package com.runout.app.ui

import com.runout.engine.math.Vec2
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.planner.OneShotPlanner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SampleLayoutsTest {
    @Test
    fun allSampleLayoutsHaveValidNonOverlappingBallsAndPlayableShots() {
        assertEquals(3, SampleLayouts.all.size)
        for (scenario in SampleLayouts.all) {
            val layout = scenario.layout
            val table = layout.table
            assertNotNull(layout.cueBall(), "${scenario.title} must include a cue ball")

            val balls = layout.balls
            assertEquals(
                balls.size,
                balls.map { it.number }.distinct().size,
                "${scenario.title} balls must have distinct numbers",
            )

            for (i in balls.indices) {
                val a = balls[i]
                assertTrue(
                    table.isWithinPlayableBounds(a.position, a.radiusMeters),
                    "${scenario.title} ball ${a.number} must be within playable bounds",
                )
                for (j in i + 1 until balls.size) {
                    val b = balls[j]
                    val minDist = a.radiusMeters + b.radiusMeters - 1e-6
                    assertTrue(
                        a.position.distanceTo(b.position) >= minDist,
                        "${scenario.title} balls ${a.number} and ${b.number} must not overlap",
                    )
                }
            }

            val ranked = OneShotPlanner.rankShots(layout, scenario.rulesState)
            assertTrue(ranked.isNotEmpty(), "${scenario.title} must produce at least one legal shot")
        }
    }

    @Test
    fun nineBallLayoutOffersClearCutsIntoFootRightAndSideRight() {
        val scenario = SampleLayouts.nineBallThreeBallRunoutStart()
        val ranked = OneShotPlanner.rankShots(scenario.layout, scenario.rulesState)

        assertTrue(ranked.all { it.targetBall.number == 1 })
        val pockets = ranked.map { it.pocket.id }.toSet()
        assertTrue(PocketId.FOOT_RIGHT in pockets, "1-ball should have an open cut into Foot Right")
        assertTrue(PocketId.SIDE_RIGHT in pockets, "1-ball should have an open cut into Side Right")
        assertFalse(PocketId.FOOT_LEFT in pockets, "1-ball into Foot Left should be blocked by the 2-ball")

        for (shot in ranked) {
            assertTrue(abs(shot.aim.cutAngleRadians) <= GhostBallAiming.DEFAULT_MAX_CUT_ANGLE_RAD)
            assertTrue(
                scenario.layout.table.isWithinPlayableBounds(
                    shot.aim.ghostBallCenter,
                    scenario.layout.table.ballRadiusMeters,
                ),
            )
        }
    }

    @Test
    fun eightBallLayoutLeavesFootLeftOpenWhileSideLeftIsBlocked() {
        val scenario = SampleLayouts.eightBallKeyBallToEight()
        val ranked = OneShotPlanner.rankShots(scenario.layout, scenario.rulesState)

        val ball3Pockets = ranked.filter { it.targetBall.number == 3 }.map { it.pocket.id }.toSet()
        assertTrue(PocketId.FOOT_LEFT in ball3Pockets, "3-ball should have an open cut into Foot Left")
        assertFalse(PocketId.SIDE_LEFT in ball3Pockets, "3-ball into Side Left should not be playable")
    }

    @Test
    fun tenBallLayoutFlagsStunScratchAndRecommendsFollowOrDraw() {
        val scenario = SampleLayouts.tenBallCutAndScratchAvoidance()
        val ranked = OneShotPlanner.rankShots(scenario.layout, scenario.rulesState)

        val footLeftShot = ranked.firstOrNull {
            it.targetBall.number == 1 && it.pocket.id == PocketId.FOOT_LEFT
        }
        assertNotNull(footLeftShot, "1-ball into Foot Left should be playable")

        val stunPath = footLeftShot.cueBallPaths.getValue(CueBallSpinPreset.STUN)
        assertEquals(PocketId.FOOT_RIGHT, stunPath.hitsScratchPocket)
        assertNotEquals(
            CueBallSpinPreset.STUN,
            footLeftShot.recommendedSpin,
            "Planner should avoid STUN when it scratches into Foot Right",
        )
    }

    @Test
    fun moveBallSafelyClampsToBoundsAndRejectsOverlappingPositions() {
        val layout = SampleLayouts.nineBallThreeBallRunoutStart().layout
        val r = layout.table.ballRadiusMeters
        val ball1 = requireNotNull(layout.ballByNumber(1))

        // Out-of-bounds drag gets clamped cleanly to playable surface
        val clampedLayout = SampleLayouts.moveBallSafely(
            layout = layout,
            ballNumber = 0,
            desiredPosition = Vec2(-0.5, 5.0),
        )
        val movedCue = requireNotNull(clampedLayout.ballByNumber(0))
        assertEquals(r, movedCue.position.x, 1e-6)
        assertEquals(layout.table.widthMeters - r, movedCue.position.y, 1e-6)

        // Overlapping drag onto Ball 1 leaves Cue Ball unchanged
        val blockedLayout = SampleLayouts.moveBallSafely(
            layout = layout,
            ballNumber = 0,
            desiredPosition = ball1.position + Vec2(r * 0.5, 0.0),
        )
        assertEquals(layout.cueBall()?.position, blockedLayout.cueBall()?.position)
    }

    @Test
    fun resizeTableLayoutScalesPositionsUpdatesSnookerRadiusAndAvoidsOverlap() {
        val nineFoot = SampleLayouts.nineBallThreeBallRunoutStart().layout
        val snookerSpec = TableSpec.fullSizeSnooker()
        val snookerLayout = SampleLayouts.resizeTableLayout(nineFoot, snookerSpec)

        assertEquals(snookerSpec.name, snookerLayout.table.name)
        assertEquals(nineFoot.balls.size, snookerLayout.balls.size)
        for (ball in snookerLayout.balls) {
            assertEquals(TableSpec.SNOOKER_BALL_RADIUS_METERS, ball.radiusMeters, 1e-9)
            assertTrue(snookerLayout.table.isWithinPlayableBounds(ball.position, ball.radiusMeters))
        }

        val sevenFootSpec = TableSpec.sevenFootPool()
        val sevenFootLayout = SampleLayouts.resizeTableLayout(snookerLayout, sevenFootSpec)
        for (i in sevenFootLayout.balls.indices) {
            val a = sevenFootLayout.balls[i]
            assertEquals(TableSpec.POOL_BALL_RADIUS_METERS, a.radiusMeters, 1e-9)
            assertTrue(sevenFootLayout.table.isWithinPlayableBounds(a.position, a.radiusMeters))
            for (j in i + 1 until sevenFootLayout.balls.size) {
                val b = sevenFootLayout.balls[j]
                assertTrue(a.position.distanceTo(b.position) >= a.radiusMeters + b.radiusMeters - 1e-6)
            }
        }
    }

    @Test
    fun toggleObjectBallAddsAtFreeSpotAndRemovesExistingBallWithoutRemovingCueBall() {
        val base = SampleLayouts.nineBallThreeBallRunoutStart().layout
        assertNotNull(base.ballByNumber(2))

        // Removing existing object ball 2
        val withoutTwo = SampleLayouts.toggleObjectBall(base, 2)
        assertNull(withoutTwo.ballByNumber(2))
        assertEquals(base.balls.size - 1, withoutTwo.balls.size)

        // Adding ball 5 places it inside bounds without overlapping existing balls
        val withFive = SampleLayouts.toggleObjectBall(withoutTwo, 5)
        val ball5 = assertNotNull(withFive.ballByNumber(5))
        assertTrue(withFive.table.isWithinPlayableBounds(ball5.position, ball5.radiusMeters))
        for (other in withFive.balls.filter { it.number != 5 }) {
            assertTrue(ball5.position.distanceTo(other.position) >= ball5.radiusMeters + other.radiusMeters - 1e-6)
        }

        // Toggling ball 0 (cue ball) is a no-op
        val unchanged = SampleLayouts.toggleObjectBall(base, 0)
        assertEquals(base, unchanged)
    }

    @Test
    fun ballSetProfileColorsAreAvailableForStandardAndTvSets() {
        val standard = BallSetProfile.standardPool()
        val tv = BallSetProfile.tvTournamentPool()
        assertEquals(BallPaletteColor.WHITE, standard.colorForBall(0))
        assertEquals(BallPaletteColor.PURPLE, standard.colorForBall(4))
        assertEquals(BallPaletteColor.PINK, tv.colorForBall(4))
    }
}
