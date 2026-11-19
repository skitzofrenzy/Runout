package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.computeAimGeometry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GhostBallAimingTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters

    @Test
    fun straightInZeroDegreeCutComputesExactGhostBallAndZeroCutAngle() {
        val pocket = table.pocketById(PocketId.FOOT_RIGHT) // (2.54, 1.27)
        val pocketDir = Vec2(1.0, 0.0)
        // Place pocket along +x axis for a clean horizontal straight-in test
        val customPocket = pocket.copy(center = Vec2(2.50, 0.635))
        val objectBall = Ball(number = 1, position = Vec2(1.50, 0.635), radiusMeters = r)
        val cueBall = Ball(number = 0, position = Vec2(0.50, 0.635), radiusMeters = r)

        val aim = computeAimGeometry(
            cueBall = cueBall,
            objectBall = objectBall,
            pocket = customPocket,
            otherBalls = emptyList(),
        )

        // Ghost ball center must sit 2*r directly behind the object ball along -x.
        assertEquals(1.50 - 2.0 * r, aim.ghostBallCenter.x, 1e-12)
        assertEquals(0.635, aim.ghostBallCenter.y, 1e-12)
        assertEquals(pocketDir.x, aim.objectBallDirection.x, 1e-12)
        assertEquals(pocketDir.y, aim.objectBallDirection.y, 1e-12)
        assertEquals(0.0, aim.cutAngleRadians, 1e-12)
        assertTrue(aim.isCutPossible)
        assertTrue(aim.isCueToGhostClear)
        assertTrue(aim.isObjectToPocketClear)
    }

    @Test
    fun thirtyAndFortyFiveDegreeCutsMatchAnalyticAngles() {
        val customPocket = table.pocketById(PocketId.FOOT_RIGHT).copy(center = Vec2(2.50, 0.635))
        val objectBall = Ball(number = 2, position = Vec2(1.50, 0.635), radiusMeters = r)
        val expectedGhost = Vec2(1.50 - 2.0 * r, 0.635)

        // 1. 30-degree cut: place cue ball 0.8 m behind ghost ball along an angle of -30 deg from +x
        val aimAngle30 = PI / 6.0
        val aimDir30 = Vec2(1.0, 0.0).rotate(-aimAngle30)
        val cueBall30 = Ball(number = 0, position = expectedGhost - aimDir30 * 0.8, radiusMeters = r)

        val aim30 = computeAimGeometry(cueBall30, objectBall, customPocket)
        assertEquals(expectedGhost.x, aim30.ghostBallCenter.x, 1e-12)
        assertEquals(expectedGhost.y, aim30.ghostBallCenter.y, 1e-12)
        assertEquals(aimAngle30, abs(aim30.cutAngleRadians), 1e-12)
        assertTrue(aim30.isCutPossible)

        // 2. 45-degree cut: place cue ball 0.8 m behind ghost ball along +45 deg from +x
        val aimAngle45 = PI / 4.0
        val aimDir45 = Vec2(1.0, 0.0).rotate(aimAngle45)
        val cueBall45 = Ball(number = 0, position = expectedGhost - aimDir45 * 0.8, radiusMeters = r)

        val aim45 = computeAimGeometry(cueBall45, objectBall, customPocket)
        assertEquals(aimAngle45, abs(aim45.cutAngleRadians), 1e-12)
        assertTrue(aim45.isCutPossible)
    }

    @Test
    fun backCutBeyondEightyDegreesIsRejected() {
        val customPocket = table.pocketById(PocketId.FOOT_RIGHT).copy(center = Vec2(2.50, 0.635))
        val objectBall = Ball(number = 3, position = Vec2(1.50, 0.635), radiusMeters = r)
        val expectedGhost = Vec2(1.50 - 2.0 * r, 0.635)

        // 85-degree cut -> exceeds 80-degree maximum cut limit
        val aimAngle85 = PI * 85.0 / 180.0
        val aimDir85 = Vec2(1.0, 0.0).rotate(aimAngle85)
        val cueBall85 = Ball(number = 0, position = expectedGhost - aimDir85 * 0.6, radiusMeters = r)

        val aim85 = computeAimGeometry(cueBall85, objectBall, customPocket)
        assertEquals(aimAngle85, abs(aim85.cutAngleRadians), 1e-12)
        assertFalse(aim85.isCutPossible)

        // Cue ball on the pocket side of the object ball (120-degree back-cut)
        val cueBallBack = Ball(number = 0, position = Vec2(2.00, 0.80), radiusMeters = r)
        val aimBack = computeAimGeometry(cueBallBack, objectBall, customPocket)
        assertTrue(abs(aimBack.cutAngleRadians) > PI / 2.0)
        assertFalse(aimBack.isCutPossible)
    }

    @Test
    fun obstructingBallsAreDetectedOnCueToGhostAndObjectToPocketCorridors() {
        val customPocket = table.pocketById(PocketId.FOOT_RIGHT).copy(center = Vec2(2.50, 0.635))
        val objectBall = Ball(number = 1, position = Vec2(1.50, 0.635), radiusMeters = r)
        val cueBall = Ball(number = 0, position = Vec2(0.50, 0.635), radiusMeters = r)

        // 1. Blocker on the cue -> ghost line at x = 1.00, offset laterally by 1.5*r (< 2*r)
        val cueBlocker = Ball(number = 4, position = Vec2(1.00, 0.635 + 1.5 * r), radiusMeters = r)
        val blockedCueToGhost = computeAimGeometry(
            cueBall = cueBall,
            objectBall = objectBall,
            pocket = customPocket,
            otherBalls = listOf(cueBlocker),
        )
        assertFalse(blockedCueToGhost.isCueToGhostClear)
        assertTrue(blockedCueToGhost.isObjectToPocketClear)

        // 2. Blocker on the object -> pocket line at x = 2.00, offset laterally by 1.0*r (< 2*r)
        val pocketBlocker = Ball(number = 5, position = Vec2(2.00, 0.635 - 1.0 * r), radiusMeters = r)
        val blockedObjectToPocket = computeAimGeometry(
            cueBall = cueBall,
            objectBall = objectBall,
            pocket = customPocket,
            otherBalls = listOf(pocketBlocker),
        )
        assertTrue(blockedObjectToPocket.isCueToGhostClear)
        assertFalse(blockedObjectToPocket.isObjectToPocketClear)

        // 3. Ball safely outside the corridor (offset laterally by 2.5*r > 2*r)
        val safeBall = Ball(number = 6, position = Vec2(1.00, 0.635 + 2.5 * r), radiusMeters = r)
        val clearAim = computeAimGeometry(
            cueBall = cueBall,
            objectBall = objectBall,
            pocket = customPocket,
            otherBalls = listOf(safeBall),
        )
        assertTrue(clearAim.isCueToGhostClear)
        assertTrue(clearAim.isObjectToPocketClear)
    }

    @Test
    fun ghostBallOutsidePlayableTableBoundsIsMarkedImpossible() {
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)
        val cueBall = Ball(number = 0, position = Vec2(0.5, 0.1), radiusMeters = r)
        val railFrozenBall = Ball(number = 1, position = Vec2(0.9, r), radiusMeters = r)

        val aimWithTable = computeAimGeometry(
            cueBall = cueBall,
            objectBall = railFrozenBall,
            pocket = footRight,
            table = table,
        )
        assertTrue(aimWithTable.ghostBallCenter.y < r)
        assertFalse(table.isWithinPlayableBounds(aimWithTable.ghostBallCenter, cueBall.radiusMeters))
        assertFalse(aimWithTable.isCutPossible)
        assertFalse(aimWithTable.isShotPlayable)
    }
}
