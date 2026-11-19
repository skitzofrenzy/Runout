package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.planner.OneShotPlanner
import com.runout.engine.planner.rankShots
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneShotPlannerTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters

    @Test
    fun nineBallAndTenBallEnforceLowestNumberedObjectBallTarget() {
        // Ball 9 sits hanging in the jaws of FOOT_RIGHT, while Ball 2 is near the middle of the table.
        val cueBall = Ball(number = 0, position = Vec2(0.80, 0.635), radiusMeters = r)
        val ball2 = Ball(number = 2, position = Vec2(1.40, 0.635), radiusMeters = r)
        val ball9InJaws = Ball(number = 9, position = Vec2(2.42, 1.18), radiusMeters = r)
        val layout = TableLayout(table = table, balls = listOf(cueBall, ball9InJaws, ball2))

        val nineBallRules = GameRulesState(gameType = GameType.NINE_BALL)
        val tenBallRules = GameRulesState(gameType = GameType.TEN_BALL)

        assertEquals(listOf(ball2), nineBallRules.legalTargetBalls(layout))
        assertEquals(listOf(ball2), tenBallRules.legalTargetBalls(layout))

        val nineBallShots = rankShots(layout = layout, rulesState = nineBallRules)
        assertTrue(nineBallShots.isNotEmpty(), "Expected playable shots on the lowest ball (ball 2)")
        assertTrue(
            nineBallShots.all { it.targetBall.number == 2 },
            "All 9-ball recommendations must target ball 2, even with ball 9 hanging in the jaws",
        )

        val tenBallShots = rankShots(layout = layout, rulesState = tenBallRules)
        assertTrue(tenBallShots.isNotEmpty())
        assertTrue(tenBallShots.all { it.targetBall.number == 2 })
    }

    @Test
    fun eightBallFiltersGroupsAndTransitionsToEightBallWhenGroupCleared() {
        val cueBall = Ball(number = 0, position = Vec2(0.60, 0.635), radiusMeters = r)
        val solid3 = Ball(number = 3, position = Vec2(1.40, 0.45), radiusMeters = r)
        val eight = Ball(number = 8, position = Vec2(1.50, 0.635), radiusMeters = r)
        val stripe11 = Ball(number = 11, position = Vec2(1.40, 0.85), radiusMeters = r)

        val mixedLayout = TableLayout(
            table = table,
            balls = listOf(cueBall, solid3, eight, stripe11),
        )

        // 1. Open table: solids and stripes are legal, 8-ball is not
        val openRules = GameRulesState(gameType = GameType.EIGHT_BALL, eightBallGroup = EightBallGroup.OPEN)
        assertEquals(listOf(3, 11), openRules.legalTargetBalls(mixedLayout).map { it.number })
        val openShots = OneShotPlanner.rankShots(mixedLayout, openRules)
        assertTrue(openShots.isNotEmpty())
        assertTrue(openShots.none { it.targetBall.number == 8 })
        assertTrue(openShots.any { it.targetBall.number == 3 })
        assertTrue(openShots.any { it.targetBall.number == 11 })

        // 2. Solids group: only solid 3 is legal while on the table
        val solidsRules = GameRulesState(gameType = GameType.EIGHT_BALL, eightBallGroup = EightBallGroup.SOLIDS)
        assertEquals(listOf(3), solidsRules.legalTargetBalls(mixedLayout).map { it.number })
        val solidsShots = OneShotPlanner.rankShots(mixedLayout, solidsRules)
        assertTrue(solidsShots.isNotEmpty())
        assertTrue(solidsShots.all { it.targetBall.number == 3 })

        // 3. Stripes group: only stripe 11 is legal while on the table
        val stripesRules = GameRulesState(gameType = GameType.EIGHT_BALL, eightBallGroup = EightBallGroup.STRIPES)
        assertEquals(listOf(11), stripesRules.legalTargetBalls(mixedLayout).map { it.number })
        val stripesShots = OneShotPlanner.rankShots(mixedLayout, stripesRules)
        assertTrue(stripesShots.isNotEmpty())
        assertTrue(stripesShots.all { it.targetBall.number == 11 })

        // 4. Automatic transition to 8-ball when all solids are cleared in SOLIDS state
        val solidsClearedLayout = TableLayout(
            table = table,
            balls = listOf(cueBall, eight, stripe11),
        )
        assertEquals(listOf(8), solidsRules.legalTargetBalls(solidsClearedLayout).map { it.number })
        val eightBallShotsAfterSolids = OneShotPlanner.rankShots(solidsClearedLayout, solidsRules)
        assertTrue(eightBallShotsAfterSolids.isNotEmpty())
        assertTrue(eightBallShotsAfterSolids.all { it.targetBall.number == 8 })

        // 5. Automatic transition to 8-ball when all stripes are cleared in STRIPES state
        val stripesClearedLayout = TableLayout(
            table = table,
            balls = listOf(cueBall, solid3, eight),
        )
        assertEquals(listOf(8), stripesRules.legalTargetBalls(stripesClearedLayout).map { it.number })

        // 6. Explicit EIGHT_BALL group state targets only the 8-ball
        val explicitEightRules = GameRulesState(
            gameType = GameType.EIGHT_BALL,
            eightBallGroup = EightBallGroup.EIGHT_BALL,
        )
        assertEquals(listOf(8), explicitEightRules.legalTargetBalls(mixedLayout).map { it.number })
    }

    @Test
    fun blockedShotsAndImpossibleBackCutsAreExcludedFromRankShots() {
        // Place ball 1 near FOOT_RIGHT so FOOT_RIGHT is a straight-in shot,
        // then block FOOT_RIGHT with ball 9 directly in the object-to-pocket corridor,
        // and place the cue ball near the foot rail so all head-end pockets require > 80° back-cuts.
        val footRight = table.pocketById(PocketId.FOOT_RIGHT) // (2.54, 1.27)
        val dirToFootRight = Vec2(1.0, 1.0).normalized()

        val ball1 = Ball(
            number = 1,
            position = footRight.center - dirToFootRight * 0.45,
            radiusMeters = r,
        )
        val cueBall = Ball(
            number = 0,
            position = footRight.center - dirToFootRight * 0.90,
            radiusMeters = r,
        )
        // Blocker sits right on the line between ball 1 and FOOT_RIGHT
        val blocker9 = Ball(
            number = 9,
            position = footRight.center - dirToFootRight * 0.22,
            radiusMeters = r,
        )

        val layout = TableLayout(
            table = table,
            balls = listOf(cueBall, ball1, blocker9),
        )
        val shots = rankShots(
            layout = layout,
            rulesState = GameRulesState(gameType = GameType.NINE_BALL),
        )

        // FOOT_RIGHT is blocked by ball 9, and HEAD_LEFT / HEAD_RIGHT require > 80° back-cuts
        assertFalse(
            shots.any { it.pocket.id == PocketId.FOOT_RIGHT },
            "Blocked shot into FOOT_RIGHT must be excluded",
        )
        assertFalse(
            shots.any { it.pocket.id == PocketId.HEAD_LEFT || it.pocket.id == PocketId.HEAD_RIGHT },
            "Back-cuts > 80° into head pockets must be excluded",
        )
        assertTrue(
            shots.all {
                it.aim.isShotPlayable &&
                    abs(it.aim.cutAngleRadians) <= GhostBallAiming.DEFAULT_MAX_CUT_ANGLE_RAD + 1e-9
            },
            "Every returned recommendation must be playable and within the 80° cut limit",
        )
    }

    @Test
    fun straightInShortPotRanksHigherThanLongThinCut() {
        val cueBall = Ball(number = 0, position = Vec2(1.27, 0.635), radiusMeters = r)

        // Ball 1: short, straight-in pot into SIDE_RIGHT (1.27, 1.27)
        val ball1ShortStraight = Ball(number = 1, position = Vec2(1.27, 0.95), radiusMeters = r)

        // Ball 2: long, thin ~55-degree cut into FOOT_LEFT (2.54, 0.0)
        val footLeft = table.pocketById(PocketId.FOOT_LEFT)
        val ball2Pos = Vec2(2.05, 0.55)
        val ball2LongCut = Ball(number = 2, position = ball2Pos, radiusMeters = r)

        val layout = TableLayout(
            table = table,
            balls = listOf(cueBall, ball1ShortStraight, ball2LongCut),
        )
        val openEightBall = GameRulesState(
            gameType = GameType.EIGHT_BALL,
            eightBallGroup = EightBallGroup.OPEN,
        )

        val ranked = rankShots(layout = layout, rulesState = openEightBall)
        assertTrue(ranked.isNotEmpty())

        val bestShot = ranked.first()
        assertEquals(1, bestShot.targetBall.number)
        assertEquals(PocketId.SIDE_RIGHT, bestShot.pocket.id)
        assertTrue(bestShot.score in 0.0..1.0)

        val longThinCutShot = ranked.firstOrNull {
            it.targetBall.number == 2 && it.pocket.id == footLeft.id
        }
        assertNotNull(longThinCutShot, "Expected the playable cut on ball 2 into FOOT_LEFT to be present")
        assertTrue(
            bestShot.score > longThinCutShot.score,
            "Short straight-in pot (${bestShot.score}) must rank higher than long thin cut (${longThinCutShot.score})",
        )
        // Verify descending score order across the entire ranked list
        for (i in 0 until ranked.size - 1) {
            assertTrue(ranked[i].score >= ranked[i + 1].score)
        }
    }

    @Test
    fun spinSelectionAvoidsScratchPocketWhenSafeSpinPresetExists() {
        // By Thales's theorem, placing the ghost ball at (2.54 - 0.635, 0.635) = (1.905, 0.635)
        // makes the line to FOOT_LEFT (2.54, 0.0) and the line to FOOT_RIGHT (2.54, 1.27)
        // strictly orthogonal, so the 90-degree STUN tangent heads straight into FOOT_RIGHT.
        val footLeft = table.pocketById(PocketId.FOOT_LEFT)
        val ghost = Vec2(2.54 - 0.635, 0.635)
        val toFootLeft = (footLeft.center - ghost).normalized()
        val ball1 = Ball(number = 1, position = ghost + toFootLeft * (2.0 * r), radiusMeters = r)

        val tangentToFootRight = toFootLeft.rotate(PI / 2.0)
        val aimDir = (toFootLeft + tangentToFootRight).normalized()
        val cueBall = Ball(number = 0, position = ghost - aimDir * 0.60, radiusMeters = r)

        val layout = TableLayout(table = table, balls = listOf(cueBall, ball1))
        val ranked = rankShots(
            layout = layout,
            rulesState = GameRulesState(gameType = GameType.NINE_BALL),
            defaultCueSpeedMps = 2.5,
        )

        val footLeftShot = ranked.firstOrNull { it.pocket.id == PocketId.FOOT_LEFT }
        assertNotNull(footLeftShot, "Expected Ball 1 into FOOT_LEFT to be evaluated")

        // Verify that STUN indeed scratches into FOOT_RIGHT at this speed
        val stunPath = footLeftShot.cueBallPaths.getValue(CueBallSpinPreset.STUN)
        assertEquals(PocketId.FOOT_RIGHT, stunPath.hitsScratchPocket)

        // Verify that the planner avoided STUN and chose a safe spin preset that does not scratch
        assertNotEquals(CueBallSpinPreset.STUN, footLeftShot.recommendedSpin)
        val chosenPath = footLeftShot.cueBallPaths.getValue(footLeftShot.recommendedSpin)
        assertNull(
            chosenPath.hitsScratchPocket,
            "Recommended spin (${footLeftShot.recommendedSpin}) must avoid scratching when a safe path exists",
        )
        assertEquals(1, footLeftShot.targetBall.number)
        assertEquals(PocketId.FOOT_LEFT, footLeftShot.pocket.id)
    }

    @Test
    fun railFrozenBallRequiringOffTableGhostBallIsExcludedByRankShots() {
        val cueBall = Ball(number = 0, position = Vec2(0.5, 0.1), radiusMeters = r)
        val railFrozenBall1 = Ball(number = 1, position = Vec2(0.9, r), radiusMeters = r)
        val layout = TableLayout(table = table, balls = listOf(cueBall, railFrozenBall1))

        val ranked = OneShotPlanner.rankShots(
            layout = layout,
            rulesState = GameRulesState(gameType = GameType.NINE_BALL),
        )

        // Shots into FOOT_RIGHT / SIDE_RIGHT / HEAD_RIGHT require ghostBallCenter.y < r (off the table).
        assertFalse(
            ranked.any {
                it.pocket.id == PocketId.FOOT_RIGHT ||
                    it.pocket.id == PocketId.SIDE_RIGHT ||
                    it.pocket.id == PocketId.HEAD_RIGHT
            },
            "Shots whose ghost ball lies outside playable table bounds must be excluded",
        )
        assertTrue(
            ranked.all { table.isWithinPlayableBounds(it.aim.ghostBallCenter, cueBall.radiusMeters) },
            "Every recommended shot must keep the ghost ball inside playable bounds",
        )
    }

    @Test
    fun rankShotsReturnsEmptyWhenCueBallOrLegalTargetsMissing() {
        val ball1 = Ball(number = 1, position = Vec2(1.5, 0.635), radiusMeters = r)
        val noCueLayout = TableLayout(table = table, balls = listOf(ball1))
        assertTrue(OneShotPlanner.rankShots(noCueLayout).isEmpty())

        val cueOnlyLayout = TableLayout(
            table = table,
            balls = listOf(Ball(number = 0, position = Vec2(0.7, 0.635), radiusMeters = r)),
        )
        assertTrue(OneShotPlanner.rankShots(cueOnlyLayout).isEmpty())
    }
}
