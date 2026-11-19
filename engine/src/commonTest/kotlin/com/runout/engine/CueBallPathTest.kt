package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.computeAimGeometry
import com.runout.engine.physics.computePostImpactPaths
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CueBallPathTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters

    @Test
    fun stunPathSatisfiesNinetyDegreeTangentRuleOrthogonality() {
        val customPocket = table.pocketById(PocketId.FOOT_RIGHT).copy(center = Vec2(2.50, 0.635))
        val objectBall = Ball(number = 1, position = Vec2(1.50, 0.635), radiusMeters = r)
        val ghost = Vec2(1.50 - 2.0 * r, 0.635)

        // Test both positive and negative cut angles (30 deg and -40 deg)
        for (cutAngleDeg in listOf(30.0, -40.0)) {
            val cutRad = cutAngleDeg * PI / 180.0
            val aimDir = Vec2(1.0, 0.0).rotate(-cutRad)
            val cueBall = Ball(number = 0, position = ghost - aimDir * 0.7, radiusMeters = r)
            val aim = computeAimGeometry(cueBall, objectBall, customPocket)

            val paths = computePostImpactPaths(aim, table, initialCueSpeedMps = 1.5)
            val stunPath = paths.getValue(CueBallSpinPreset.STUN)
            assertTrue(stunPath.points.size >= 2)

            val stunDir = (stunPath.points[1] - stunPath.points[0]).normalized()
            // 90-degree rule: stun direction must be orthogonal to objectBallDirection
            assertEquals(0.0, stunDir.dot(aim.objectBallDirection), 1e-9)
            // Must deflect to the side away from the object ball's path (positive projection along aimDirection)
            assertTrue(stunDir.dot(aim.aimDirection) > 0.0)
        }
    }

    @Test
    fun naturalRollHalfBallCutDeflectsAroundThirtyDegrees() {
        val customPocket = table.pocketById(PocketId.FOOT_RIGHT).copy(center = Vec2(2.50, 0.635))
        val objectBall = Ball(number = 1, position = Vec2(1.50, 0.635), radiusMeters = r)
        val ghost = Vec2(1.50 - 2.0 * r, 0.635)

        // Half-ball hit corresponds to a 30-degree cut angle (sin(phi) = 0.5)
        val halfBallCutRad = PI / 6.0
        val aimDir = Vec2(1.0, 0.0).rotate(-halfBallCutRad)
        val cueBall = Ball(number = 0, position = ghost - aimDir * 0.7, radiusMeters = r)
        val aim = computeAimGeometry(cueBall, objectBall, customPocket)

        val paths = computePostImpactPaths(aim, table, initialCueSpeedMps = 1.5)
        val followPath = paths.getValue(CueBallSpinPreset.FOLLOW)
        val followDir = (followPath.points[1] - followPath.points[0]).normalized()

        val deflectionDeg = abs(followPath.initialDeflectionAngleRad) * 180.0 / PI
        // Analytic post-slide natural roll deflection at a 30° half-ball cut is 33.67°.
        assertEquals(33.67, deflectionDeg, 0.15)
        assertEquals(abs(aim.aimDirection.angleTo(followDir)), abs(followPath.initialDeflectionAngleRad), 1e-9)
        // Follow path must advance forward relative to the 90-degree tangent line
        assertTrue(followDir.dot(aim.objectBallDirection) > 0.0)

        // Quarter-ball (~14.48° cut) and three-quarter-ball (~48.59° cut) deflections also obey the 30° rule band.
        for (sinCut in listOf(0.25, 0.75)) {
            val cutRad = asin(sinCut)
            val cutAimDir = Vec2(1.0, 0.0).rotate(-cutRad)
            val cutCueBall = Ball(number = 0, position = ghost - cutAimDir * 0.7, radiusMeters = r)
            val cutAim = computeAimGeometry(cutCueBall, objectBall, customPocket)
            val cutFollowPath = computePostImpactPaths(cutAim, table, initialCueSpeedMps = 1.5)
                .getValue(CueBallSpinPreset.FOLLOW)
            val cutDeflectionDeg = abs(cutFollowPath.initialDeflectionAngleRad) * 180.0 / PI
            assertTrue(
                cutDeflectionDeg in 27.0..34.0,
                "Expected natural roll deflection in 27.0°..34.0° for sin(cut)=$sinCut, got $cutDeflectionDeg°",
            )
        }
    }

    @Test
    fun straightInShotStopsOnStunFollowsForwardAndDrawsBack() {
        val customPocket = table.pocketById(PocketId.FOOT_RIGHT).copy(center = Vec2(2.50, 0.635))
        val objectBall = Ball(number = 1, position = Vec2(1.50, 0.635), radiusMeters = r)
        val cueBall = Ball(number = 0, position = Vec2(0.70, 0.635), radiusMeters = r)
        val aim = computeAimGeometry(cueBall, objectBall, customPocket)

        val paths = computePostImpactPaths(aim, table, initialCueSpeedMps = 1.5)

        // 1. STUN stops dead at ghostBallCenter
        val stunPath = paths.getValue(CueBallSpinPreset.STUN)
        assertEquals(1, stunPath.points.size)
        assertEquals(aim.ghostBallCenter, stunPath.points.first())
        assertEquals(0.0, stunPath.initialDeflectionAngleRad, 1e-12)

        // 2. FOLLOW continues straight forward along objectBallDirection (+x)
        val followPath = paths.getValue(CueBallSpinPreset.FOLLOW)
        assertTrue(followPath.points.size >= 2)
        val followDir = (followPath.points[1] - followPath.points[0]).normalized()
        assertEquals(1.0, followDir.dot(aim.objectBallDirection), 1e-9)
        assertEquals(0.0, followPath.initialDeflectionAngleRad, 1e-9)

        // 3. DRAW pulls straight back towards cueBallPos (-x)
        val drawPath = paths.getValue(CueBallSpinPreset.DRAW)
        assertTrue(drawPath.points.size >= 2)
        val drawDir = (drawPath.points[1] - drawPath.points[0]).normalized()
        assertEquals(-1.0, drawDir.dot(aim.aimDirection), 1e-9)
        assertEquals(PI, abs(drawPath.initialDeflectionAngleRad), 1e-9)
    }

    @Test
    fun drawOnThirtyDegreeCutMatchesAnalyticDeflection() {
        val customPocket = table.pocketById(PocketId.FOOT_RIGHT).copy(center = Vec2(2.50, 0.635))
        val objectBall = Ball(number = 1, position = Vec2(1.50, 0.635), radiusMeters = r)
        val ghost = Vec2(1.50 - 2.0 * r, 0.635)

        // Positive +30° cut: objectBallDirection is CCW from aimDirection, so cue ball deflects CW (-128.95°).
        val cutRad = PI / 6.0
        val aimDirPos = Vec2(1.0, 0.0).rotate(-cutRad)
        val cuePos = Ball(number = 0, position = ghost - aimDirPos * 0.7, radiusMeters = r)
        val aimPos = computeAimGeometry(cuePos, objectBall, customPocket)
        val drawPathPos = computePostImpactPaths(aimPos, table, initialCueSpeedMps = 2.0)
            .getValue(CueBallSpinPreset.DRAW)
        val drawDeflectionDegPos = drawPathPos.initialDeflectionAngleRad * 180.0 / PI
        assertEquals(-128.95, drawDeflectionDegPos, 0.15)
        val drawDirPos = (drawPathPos.points[1] - drawPathPos.points[0]).normalized()
        assertTrue(drawDirPos.dot(aimPos.objectBallDirection) < 0.0)

        // Negative -30° cut: symmetric +128.95° deflection.
        val aimDirNeg = Vec2(1.0, 0.0).rotate(cutRad)
        val cueNeg = Ball(number = 0, position = ghost - aimDirNeg * 0.7, radiusMeters = r)
        val aimNeg = computeAimGeometry(cueNeg, objectBall, customPocket)
        val drawPathNeg = computePostImpactPaths(aimNeg, table, initialCueSpeedMps = 2.0)
            .getValue(CueBallSpinPreset.DRAW)
        assertEquals(128.95, drawPathNeg.initialDeflectionAngleRad * 180.0 / PI, 0.15)
    }

    @Test
    fun drawOnCutShotDeflectsBehindTangentLineAndFollowStraightInScratches() {
        val footLeft = table.pocketById(PocketId.FOOT_LEFT)
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)

        // Place object ball at (1.90, 0.635): vector to FOOT_LEFT (2.54, 0.0) is (0.64, -0.635),
        // nearly orthogonal to vector to FOOT_RIGHT (2.54, 1.27) = (0.64, +0.635).
        val objectBall = Ball(number = 8, position = Vec2(1.90, 0.635), radiusMeters = r)
        val toFootLeft = (footLeft.center - objectBall.position).normalized()
        val ghost = objectBall.position - toFootLeft * (2.0 * r)
        val tangentToFootRight = toFootLeft.rotate(PI / 2.0)
        val aimDir = (toFootLeft + tangentToFootRight).normalized() // 45-degree cut
        val cueBall = Ball(number = 0, position = ghost - aimDir * 0.6, radiusMeters = r)

        val aim = computeAimGeometry(cueBall, objectBall, footLeft)
        val paths = computePostImpactPaths(aim, table, initialCueSpeedMps = 2.5)

        // Draw path must go behind the tangent line (negative dot product with objectBallDirection)
        val drawPath = paths.getValue(CueBallSpinPreset.DRAW)
        val drawDir = (drawPath.points[1] - drawPath.points[0]).normalized()
        assertTrue(drawDir.dot(aim.objectBallDirection) < 0.0)

        // Straight-in follow shot into FOOT_RIGHT scratches into FOOT_RIGHT
        val scratchObj = Ball(number = 9, position = Vec2(2.10, 1.27 - (2.54 - 2.10) * 0.5), radiusMeters = r)
        val dirToFootRight = (footRight.center - scratchObj.position).normalized()
        val scratchCue = Ball(number = 0, position = scratchObj.position - dirToFootRight * 0.5, radiusMeters = r)
        val scratchAim = computeAimGeometry(scratchCue, scratchObj, footRight)
        val scratchPaths = computePostImpactPaths(scratchAim, table, initialCueSpeedMps = 3.0)
        val scratchFollow = scratchPaths.getValue(CueBallSpinPreset.FOLLOW)
        assertNotNull(scratchFollow.hitsScratchPocket)
        assertEquals(PocketId.FOOT_RIGHT, scratchFollow.hitsScratchPocket)
    }

    @Test
    fun singleCushionBounceProducesThreePointMirrorReflectedTrajectory() {
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)
        val objectBall = Ball(number = 1, position = Vec2(1.80, 0.95), radiusMeters = r)
        val obDir = (footRight.center - objectBall.position).normalized()
        val ghost = objectBall.position - obDir * (2.0 * r)

        // -30° cut sends STUN tangent upward into the right rail (y = widthMeters - r) away from pockets.
        val aimDir = obDir.rotate(PI / 6.0)
        val cueBall = Ball(number = 0, position = ghost - aimDir * 0.45, radiusMeters = r)
        val aim = computeAimGeometry(cueBall, objectBall, footRight, table = table)
        assertTrue(aim.isShotPlayable)

        val stunPath = computePostImpactPaths(aim, table, initialCueSpeedMps = 1.5)
            .getValue(CueBallSpinPreset.STUN)

        assertNull(stunPath.hitsScratchPocket)
        assertEquals(3, stunPath.points.size)
        assertEquals(ghost.x, stunPath.points[0].x, 1e-12)
        assertEquals(ghost.y, stunPath.points[0].y, 1e-12)
        // Bounce occurs on the right cushion boundary y = widthMeters - r
        assertEquals(table.widthMeters - r, stunPath.points[1].y, 1e-9)
        assertTrue(table.isWithinPlayableBounds(stunPath.points[2], r))

        // Mirror reflection across the horizontal rail: x velocity sign preserved, y velocity sign inverted
        val incomingDir = (stunPath.points[1] - stunPath.points[0]).normalized()
        val outgoingDir = (stunPath.points[2] - stunPath.points[1]).normalized()
        assertEquals(incomingDir.x, outgoingDir.x, 1e-9)
        assertEquals(-incomingDir.y, outgoingDir.y, 1e-9)

        // Remaining travel distance after bounce is attenuated by ec^2
        val ec = table.cloth.cushionRestitutionEc
        val firstLeg = stunPath.points[0].distanceTo(stunPath.points[1])
        val secondLeg = stunPath.points[1].distanceTo(stunPath.points[2])
        val expectedTotal = MotionPhysics.stunStoppingDistanceMeters(
            initialCueSpeedMps = 1.5,
            cutAngleRad = abs(aim.cutAngleRadians),
            muS = table.cloth.slidingFrictionMuS,
            muR = table.cloth.rollingResistanceMuR,
        )
        assertEquals((expectedTotal - firstLeg) * (ec * ec), secondLeg, 1e-9)
    }

    @Test
    fun stunPathWithoutRailContactMatchesSlidePlusRollStoppingDistance() {
        val customPocket = table.pocketById(PocketId.FOOT_RIGHT).copy(center = Vec2(2.50, 0.25))
        val objectBall = Ball(number = 1, position = Vec2(1.50, 0.25), radiusMeters = r)
        val ghost = Vec2(1.50 - 2.0 * r, 0.25)

        val cutRad = PI / 6.0
        val aimDir = Vec2(1.0, 0.0).rotate(cutRad) // -30° cut -> tangent points +y into open cloth
        val cueBall = Ball(number = 0, position = ghost - aimDir * 0.40, radiusMeters = r)
        val aim = computeAimGeometry(cueBall, objectBall, customPocket)

        val stunPath = computePostImpactPaths(aim, table, initialCueSpeedMps = 1.0)
            .getValue(CueBallSpinPreset.STUN)

        assertEquals(2, stunPath.points.size)
        val expectedDistance = MotionPhysics.stunStoppingDistanceMeters(
            initialCueSpeedMps = 1.0,
            cutAngleRad = cutRad,
            muS = table.cloth.slidingFrictionMuS,
            muR = table.cloth.rollingResistanceMuR,
        )
        assertEquals(expectedDistance, stunPath.points[0].distanceTo(stunPath.points[1]), 1e-9)
    }
}
