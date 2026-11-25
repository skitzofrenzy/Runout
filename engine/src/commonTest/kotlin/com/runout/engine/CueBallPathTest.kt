package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.CueBallEventKind
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import com.runout.engine.physics.computeAimGeometry
import com.runout.engine.physics.computePostImpactPaths
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CueBallPathTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters
    private val e = MotionPhysics.BALL_BALL_RESTITUTION
    private val muSg = table.cloth.slidingFrictionMuS * MotionPhysics.GRAVITY_M_S2
    private val muRg = table.cloth.rollingResistanceMuR * MotionPhysics.GRAVITY_M_S2

    private fun degrees(rad: Double) = rad * 180.0 / PI

    private fun cutAim(cutRad: Double, distance: Double = 0.7, objectAt: Vec2 = Vec2(1.50, 0.635)): AimGeometry {
        val customPocket = targetPocket(Vec2(2.50, objectAt.y))
        val objectBall = Ball(number = 1, position = objectAt, radiusMeters = r)
        val ghost = Vec2(objectAt.x - 2.0 * r, objectAt.y)
        val aimDir = Vec2(1.0, 0.0).rotate(-cutRad)
        val cueBall = Ball(number = 0, position = ghost - aimDir * distance, radiusMeters = r)
        return computeAimGeometry(cueBall, objectBall, customPocket)
    }

    private fun direction(from: Vec2, to: Vec2): Vec2 = (to - from).normalized()

    @Test
    fun stunPathLeavesStraightJustInsideTheTangentLine() {
        for (cutAngleDeg in listOf(30.0, -40.0)) {
            val aim = cutAim(cutAngleDeg * PI / 180.0)
            val speed = 1.5
            val stunPath = computePostImpactPaths(aim, table, initialCueSpeedMps = speed).getValue(CueBallSpinPreset.STUN)
            assertTrue(stunPath.points.size >= 2)

            // With no spin the slip is along the velocity, so slide and roll lie on one straight line.
            val stunDir = direction(stunPath.points[0], stunPath.points[1])
            val firstContact = stunPath.sampledPoints.indexOfFirst { it.distanceTo(stunPath.points[1]) < 1e-12 }
            for (p in stunPath.sampledPoints.take(firstContact + 1)) {
                assertEquals(0.0, abs((p - stunPath.points[0]).cross(stunDir)), 1e-9)
            }
            assertEquals(stunDir.angleTo(aim.aimDirection), -stunPath.initialDeflectionAngleRad, 1e-9)
            assertEquals(stunPath.initialDeflectionAngleRad, stunPath.rollingDeflectionAngleRad, 1e-12)

            // Along the line of centres of the contact the cue ball keeps (1 - e)/2 of its approach speed; across it,
            // all of it less the throw impulse. That puts the stun line a few degrees inside the tangent line.
            val post = SpinPhysics.postImpactStateFor(aim, speed, 0.0, 0.0, table)
            val n = (post.objectBall.velocity.rotate(post.throwRadians)).normalized()
            val approachAlong = aim.aimDirection.dot(n) * speed
            assertEquals(0.5 * (1.0 - e) * approachAlong, post.cue.velocity.dot(n), 1e-9)
            val insideTangent = 90.0 - degrees(abs(stunDir.angleTo(n)))
            assertTrue(insideTangent in 2.0..8.0, "Stun line should be a few degrees inside the tangent, was $insideTangent°")
            // It still leaves forward of the aim line's perpendicular, away from the object ball's path.
            assertTrue(stunDir.dot(aim.aimDirection) > 0.0)
            assertTrue(stunDir.dot(aim.objectBallDirection) > 0.0)
        }
    }

    @Test
    fun naturalRollFollowsTheThirtyDegreeRuleAfterItsSlide() {
        val aim = cutAim(PI / 6.0)
        val followPath = computePostImpactPaths(aim, table, initialCueSpeedMps = 1.5).getValue(CueBallSpinPreset.FOLLOW)

        // The ball leaves near the tangent line and bends forward while the follow takes.
        assertTrue(abs(followPath.initialDeflectionAngleRad) > abs(followPath.rollingDeflectionAngleRad) + 0.3)
        // Rolling, it runs along (5 v' + 2 w) / 7, about 31.5° off the aim line at half-ball (33.7° with e = 1 and no
        // throw); the stretch after the slide lies on that line.
        val rolling = abs(degrees(followPath.rollingDeflectionAngleRad))
        assertTrue(rolling in 30.0..33.0, "Half-ball natural roll should deflect about 31.5°, got $rolling°")
        val post = SpinPhysics.postImpactStateFor(aim, 1.5, 1.0, 0.0, table)
        val pts = followPath.sampledPoints
        val slideEndIndex = pts.indexOfFirst { it.distanceTo(post.slideEnd.position) < 1e-9 }
        assertTrue(slideEndIndex > 0)
        val lastDir = direction(pts[slideEndIndex], pts[slideEndIndex + 1])
        assertEquals(followPath.rollingDeflectionAngleRad, aim.aimDirection.angleTo(lastDir), 1e-6)
        assertTrue(lastDir.dot(aim.objectBallDirection) > 0.0)

        // Quarter-ball (~14.48°) and three-quarter-ball (~48.59°) hits: the same law gives about 25° and 26°.
        for (sinCut in listOf(0.25, 0.75)) {
            val path = computePostImpactPaths(cutAim(asin(sinCut)), table, initialCueSpeedMps = 1.5)
                .getValue(CueBallSpinPreset.FOLLOW)
            val deflection = abs(degrees(path.rollingDeflectionAngleRad))
            assertTrue(deflection in 23.0..30.0, "Expected roughly the 30° rule for sin(cut)=$sinCut, got $deflection°")
        }
    }

    @Test
    fun straightInStunCreepsForwardFollowRunsOnAndDrawComesBack() {
        val customPocket = targetPocket(Vec2(2.50, 0.635))
        val objectBall = Ball(number = 1, position = Vec2(1.50, 0.635), radiusMeters = r)
        val cueBall = Ball(number = 0, position = Vec2(0.70, 0.635), radiusMeters = r)
        val aim = computeAimGeometry(cueBall, objectBall, customPocket)
        val v = 1.5
        val paths = computePostImpactPaths(aim, table, initialCueSpeedMps = v)
        val kept = 0.5 * (1.0 - e) * v

        // Stun: the cue ball keeps (1 - e)/2 of its speed and no spin, so it slides and rolls a few millimetres on.
        val stunPath = paths.getValue(CueBallSpinPreset.STUN)
        assertEquals(2, stunPath.points.size)
        val stunTravel = (stunPath.points.last() - aim.ghostBallCenter).dot(aim.objectBallDirection)
        assertEquals(MotionPhysics.objectBallStoppingDistanceMeters(kept, table.cloth.slidingFrictionMuS, table.cloth.rollingResistanceMuR), stunTravel, 1e-9)
        assertTrue(stunTravel in 0.002..0.02)

        // Follow: straight on along the object ball's line.
        val followPath = paths.getValue(CueBallSpinPreset.FOLLOW)
        val followDir = direction(followPath.points[0], followPath.points[1])
        assertEquals(1.0, followDir.dot(aim.objectBallDirection), 1e-9)
        assertEquals(0.0, followPath.initialDeflectionAngleRad, 1e-9)

        // Draw: back along the aim line once the backspin has taken.
        val drawPath = paths.getValue(CueBallSpinPreset.DRAW)
        val drawDir = direction(drawPath.points[0], drawPath.points.last())
        assertEquals(-1.0, drawDir.dot(aim.aimDirection), 1e-9)
        assertEquals(PI, abs(drawPath.rollingDeflectionAngleRad), 1e-9)
    }

    @Test
    fun drawOnThirtyDegreeCutEndsOnTheSlideLawLineBehindTheTangent() {
        val speed = 2.0
        val positive = cutAim(PI / 6.0)
        val drawPos = computePostImpactPaths(positive, table, initialCueSpeedMps = speed).getValue(CueBallSpinPreset.DRAW)

        // After the contact the slide ends at (5 v' + 2 w) / 7, with w the backspin the ball arrived with.
        val post = SpinPhysics.postImpactStateFor(positive, speed, SpinPhysics.DRAW_PRESET_IMPACT_K, 0.0, table)
        val vf = (post.cue.velocity * 5.0 + post.cue.spinVelocity * 2.0) / 7.0
        assertEquals(positive.aimDirection.angleTo(vf), drawPos.rollingDeflectionAngleRad, 1e-9)
        // Behind the tangent line: it comes back against the object ball's direction.
        assertTrue(vf.dot(positive.objectBallDirection) < 0.0)
        assertTrue(degrees(drawPos.rollingDeflectionAngleRad) in -85.0..-70.0)
        // The ball leaves close to the tangent line first; the draw then bends it back.
        assertTrue(abs(drawPos.initialDeflectionAngleRad) < abs(drawPos.rollingDeflectionAngleRad))

        // Mirror shot: mirror result.
        val negative = cutAim(-PI / 6.0)
        val drawNeg = computePostImpactPaths(negative, table, initialCueSpeedMps = speed).getValue(CueBallSpinPreset.DRAW)
        assertEquals(-drawPos.rollingDeflectionAngleRad, drawNeg.rollingDeflectionAngleRad, 1e-9)
        assertEquals(-drawPos.initialDeflectionAngleRad, drawNeg.initialDeflectionAngleRad, 1e-9)
    }

    @Test
    fun drawOnCutShotEndsBehindTangentLineAndFollowStraightInScratches() {
        val footLeft = table.pocketById(PocketId.FOOT_LEFT)
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)

        val objectBall = Ball(number = 8, position = Vec2(1.90, 0.635), radiusMeters = r)
        val toFootLeft = (footLeft.mouthCenter - objectBall.position).normalized()
        val ghost = objectBall.position - toFootLeft * (2.0 * r)
        val tangentToFootRight = toFootLeft.rotate(PI / 2.0)
        val aimDir = (toFootLeft + tangentToFootRight).normalized() // 45-degree cut
        val cueBall = Ball(number = 0, position = ghost - aimDir * 0.6, radiusMeters = r)

        val aim = computeAimGeometry(cueBall, objectBall, footLeft)
        val paths = computePostImpactPaths(aim, table, initialCueSpeedMps = 2.5)

        // Once the draw has taken, the path runs behind the tangent line.
        val drawPath = paths.getValue(CueBallSpinPreset.DRAW)
        val rollingDir = aim.aimDirection.rotate(drawPath.rollingDeflectionAngleRad)
        assertTrue(rollingDir.dot(aim.objectBallDirection) < 0.0)

        // Straight-in follow shot into FOOT_RIGHT scratches into FOOT_RIGHT
        val scratchObj = Ball(number = 9, position = Vec2(2.10, 1.27 - (2.54 - 2.10) * 0.5), radiusMeters = r)
        val dirToFootRight = (footRight.mouthCenter - scratchObj.position).normalized()
        val scratchCue = Ball(number = 0, position = scratchObj.position - dirToFootRight * 0.5, radiusMeters = r)
        val scratchAim = computeAimGeometry(scratchCue, scratchObj, footRight)
        val scratchPaths = computePostImpactPaths(scratchAim, table, initialCueSpeedMps = 3.0)
        val scratchFollow = scratchPaths.getValue(CueBallSpinPreset.FOLLOW)
        assertNotNull(scratchFollow.hitsScratchPocket)
        assertEquals(PocketId.FOOT_RIGHT, scratchFollow.hitsScratchPocket)
    }

    @Test
    fun stunPathOffOneCushionLeavesSlidingAndRollsOffLongerThanTheMirrorAngle() {
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)
        val objectBall = Ball(number = 1, position = Vec2(1.80, 0.95), radiusMeters = r)
        val obDir = (com.runout.engine.physics.PocketGeometry.aimPoint(objectBall.position, footRight, r) - objectBall.position)
            .normalized()
        val ghost = objectBall.position - obDir * (2.0 * r)

        // -30° cut sends the stun line up into the right rail (y = widthMeters - r), away from pockets.
        val aimDir = obDir.rotate(PI / 6.0)
        val cueBall = Ball(number = 0, position = ghost - aimDir * 0.45, radiusMeters = r)
        val aim = computeAimGeometry(cueBall, objectBall, footRight, table = table)
        assertTrue(aim.isShotPlayable)
        val stunTip = assertNotNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.STUN, 2.0, aim.cueToGhostDistanceMeters, table))
        val layout = TableLayout(table, listOf(cueBall, objectBall))
        val sim = TableCollisionSimulator.simulatePostImpact(layout, objectBall, aim, 2.0, stunTip)
        val path = sim.cueBallTrajectory

        assertNull(path.hitsScratchPocket)
        assertEquals(3, path.points.size)
        assertEquals(1, path.cushionContacts)
        assertEquals(ghost.x, path.points[0].x, 1e-12)
        assertEquals(ghost.y, path.points[0].y, 1e-12)
        assertEquals(table.widthMeters - r, path.points[1].y, 1e-9)
        assertTrue(table.isWithinPlayableBounds(path.points[2], r))

        // By the rail the stun slide is long over: the ball arrives rolling. The cushion impulse leaves it sliding with
        // part of its roll, so the cloth bends it on along its old direction: a rolling ball comes off longer than the
        // mirror angle.
        val cushion = sim.cueBallEvents.single { it.kind == CueBallEventKind.CUSHION }
        assertEquals(1.0, cushion.verticalSpinBefore, 1e-9)
        assertTrue(abs(cushion.verticalSpinAfter - 1.0) > 0.05)
        assertTrue(cushion.speedOutMps < cushion.speedInMps)
        val incomingDir = direction(path.points[0], path.points[1])
        val sampled = path.sampledPoints
        val restDir = direction(sampled[sampled.size - 2], sampled.last())
        val angleIn = atan2(abs(incomingDir.x), abs(incomingDir.y))
        val angleOut = atan2(abs(restDir.x), abs(restDir.y))
        assertTrue(restDir.y < 0.0 && restDir.x * incomingDir.x > 0.0)
        assertTrue(angleOut > angleIn + 0.05, "rolling rebound $angleOut should be longer than the mirror angle $angleIn")
        // The curve after the cushion is sampled, so the drawn path has more points than its contact vertices.
        assertTrue(sampled.size > path.points.size)
    }

    @Test
    fun stunPathWithoutRailContactMatchesSlidePlusRollStoppingDistance() {
        val customPocket = targetPocket(Vec2(2.50, 0.25))
        val objectBall = Ball(number = 1, position = Vec2(1.50, 0.25), radiusMeters = r)
        val ghost = Vec2(1.50 - 2.0 * r, 0.25)

        val cutRad = PI / 6.0
        val aimDir = Vec2(1.0, 0.0).rotate(cutRad) // -30° cut -> tangent points +y into open cloth
        val cueBall = Ball(number = 0, position = ghost - aimDir * 0.40, radiusMeters = r)
        val aim = computeAimGeometry(cueBall, objectBall, customPocket)

        val stunPath = computePostImpactPaths(aim, table, initialCueSpeedMps = 1.0).getValue(CueBallSpinPreset.STUN)

        // A ball with no spin slides 12 v^2 / (49 muS g) to 5/7 of its speed and then rolls to rest.
        assertEquals(2, stunPath.points.size)
        val launch = SpinPhysics.postImpactStateFor(aim, 1.0, 0.0, 0.0, table).launchSpeedMps
        val cosCut = cos(cutRad)
        val sinCut = sin(cutRad)
        // Launch speed: v sin(cut) across the line of centres and (1 - e)/2 v cos(cut) along it, give or take the throw.
        assertEquals(kotlin.math.hypot(sinCut, 0.5 * (1.0 - e) * cosCut), launch, 0.03)
        val expectedDistance = 12.0 * launch * launch / (49.0 * muSg) + (5.0 / 7.0 * launch).let { it * it } / (2.0 * muRg)
        assertEquals(expectedDistance, stunPath.points[0].distanceTo(stunPath.points[1]), 1e-9)
    }

    @Test
    fun strokeBasedPathsUseTheSpeedAndSpinAtContact() {
        val aim = cutAim(PI / 6.0, distance = 0.5)
        val stroke = 2.0
        val paths = com.runout.engine.physics.CueBallPath.computePostImpactPathsForStroke(aim, table, stroke)
        for (preset in CueBallSpinPreset.entries) {
            val tip = SpinPhysics.tipForImpactState(preset, stroke, aim.cueToGhostDistanceMeters, table) ?: TipOffset.fromPreset(preset)
            val traced = SpinPhysics.traceCueBallPathWithSpin(aim, stroke, tip, table, r, maxBounces = Int.MAX_VALUE)
            assertEquals(traced.sampledPoints, paths.getValue(preset).sampledPoints)
            assertEquals(preset, paths.getValue(preset).spin)
        }
        // The draw preset arrives with the firm-draw spin, not the stroke's: the impact speed is below the stroke speed.
        val pre = SpinPhysics.computePreImpactCueState(
            stroke,
            assertNotNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.DRAW, stroke, aim.cueToGhostDistanceMeters, table)),
            aim.cueToGhostDistanceMeters,
            table,
        )
        assertEquals(SpinPhysics.DRAW_PRESET_IMPACT_K, pre.impactSpinFactorK, 1e-9)
        assertTrue(pre.impactSpeedMps < stroke)
    }
}
