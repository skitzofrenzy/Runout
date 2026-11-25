package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.BallBallContact
import com.runout.engine.physics.BallState
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.CueBallEventKind
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.PathWaypoint
import com.runout.engine.physics.PathWaypointKind
import com.runout.engine.physics.PostShotTableSimulation
import com.runout.engine.physics.ShotPathWaypoints
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.SpinPhysics.ImpactSpinState
import com.runout.engine.physics.SpinPhysics.SpinRegimeChange
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShotPathWaypointsTest {

    private val table = TableSpec.nineFootPool()

    private class Shot(
        val aim: AimGeometry,
        val simulation: PostShotTableSimulation,
        val waypoints: List<PathWaypoint>,
    )

    private fun shoot(
        cue: Vec2,
        target: Vec2,
        pocket: PocketId,
        speedMps: Double,
        tip: TipOffset,
        others: List<Ball> = emptyList(),
    ): Shot {
        val cueBall = Ball(number = 0, position = cue)
        val targetBall = Ball(number = 1, position = target)
        val layout = TableLayout(table = table, balls = listOf(cueBall, targetBall) + others)
        val aim = GhostBallAiming.computeAimGeometry(
            cueBall = cueBall,
            objectBall = targetBall,
            pocket = table.pockets.first { it.id == pocket },
            otherBalls = layout.balls,
            table = table,
        )
        val simulation = TableCollisionSimulator.simulatePostImpact(
            layout = layout,
            targetBall = targetBall,
            aim = aim,
            cueSpeedMps = speedMps,
            tip = tip,
        )
        val waypoints = ShotPathWaypoints.build(
            aim = aim,
            cueSpeedMps = speedMps,
            tip = tip,
            table = table,
            simulation = simulation,
            targetBallNumber = targetBall.number,
        )
        return Shot(aim, simulation, waypoints)
    }

    /** k0 for a tip with the given vertical offset (fraction of ball radius). */
    private fun tipFor(vertical: Double, side: Double = 0.0) = TipOffset(sideRatio = side, verticalRatio = vertical)

    private fun stateAt(tip: TipOffset, speed: Double, distance: Double): ImpactSpinState =
        SpinPhysics.computePreImpactCueState(speed, tip, distance, table).state

    private val longCut = Triple(Vec2(0.30, 0.40), Vec2(1.80, 0.80), PocketId.FOOT_RIGHT)

    @Test
    fun waypointsAreInPathOrderFromStrikeToRestOrScratch() {
        val shot = shoot(longCut.first, longCut.second, longCut.third, 2.6, tipFor(0.18))
        val wps = shot.waypoints
        assertEquals(PathWaypointKind.CUE_START, wps.first().kind)
        assertTrue(wps.last().kind == PathWaypointKind.CUE_REST || wps.last().kind == PathWaypointKind.SCRATCH)
        for (i in 1 until wps.size) {
            assertTrue(
                wps[i].distanceAlongPathMeters >= wps[i - 1].distanceAlongPathMeters - 1e-9,
                "waypoint $i (${wps[i].kind}) precedes waypoint ${i - 1} (${wps[i - 1].kind})",
            )
        }
        assertEquals(1, wps.count { it.kind == PathWaypointKind.OBJECT_CONTACT })
        val contact = wps.first { it.kind == PathWaypointKind.OBJECT_CONTACT }
        assertEquals(shot.aim.cueToGhostDistanceMeters, contact.distanceAlongPathMeters, 1e-9)
        assertTrue(wps.last().distanceAlongPathMeters >= contact.distanceAlongPathMeters)
    }

    @Test
    fun softLongDrawShotReportsDrawToStunAndStunToRollBeforeContact() {
        val tip = tipFor(-0.38)
        val speed = 1.5
        val shot = shoot(longCut.first, longCut.second, longCut.third, speed, tip)
        val contactDistance = shot.aim.cueToGhostDistanceMeters
        val transitions = shot.waypoints.filter { it.kind == PathWaypointKind.SPIN_TRANSITION }

        val drawToStun = transitions.firstOrNull { it.transition == SpinRegimeChange.DRAW_TO_STUN }
        val stunToRoll = transitions.firstOrNull { it.transition == SpinRegimeChange.STUN_TO_ROLL }
        assertNotNull(drawToStun, "expected a draw to stun transition")
        assertNotNull(stunToRoll, "expected a stun to roll transition")
        assertTrue(drawToStun.distanceAlongPathMeters < stunToRoll.distanceAlongPathMeters)
        assertTrue(stunToRoll.distanceAlongPathMeters < contactDistance)
        assertTrue(drawToStun.speedOutMps!! > stunToRoll.speedOutMps!!, "slide only slows the ball")

        // The regime reported on each side of a transition is what the pre-impact model says there.
        for (t in transitions) {
            val d = t.distanceAlongPathMeters
            assertEquals(t.regimeBefore, stateAt(tip, speed, d - 1e-3), "before ${t.transition}")
            assertEquals(t.regimeAfter, stateAt(tip, speed, d + 1e-3), "after ${t.transition}")
        }
        assertEquals(ImpactSpinState.BACKSPIN_DRAW, drawToStun.regimeBefore)
        assertEquals(ImpactSpinState.STUN_SLIDE, drawToStun.regimeAfter)
        assertEquals(ImpactSpinState.STUN_SLIDE, stunToRoll.regimeBefore)
        assertEquals(ImpactSpinState.PARTIAL_FORWARD_ROLL, stunToRoll.regimeAfter)

        // The ball arrives in natural roll: the contact point reports the draw is gone.
        val contact = shot.waypoints.first { it.kind == PathWaypointKind.OBJECT_CONTACT }
        assertEquals(ImpactSpinState.NATURAL_FORWARD_ROLL, contact.regimeBefore)
        assertEquals(1.0, contact.verticalSpinBefore!!, 1e-9)
    }

    @Test
    fun regimeBoundariesAgreeWithPreImpactStateAcrossTipsAndSpeeds() {
        val verticals = listOf(-0.38, -0.25, -0.12, -0.03, 0.0, 0.03, 0.08, 0.15, 0.25, 0.35, 0.45)
        val speeds = listOf(0.8, 1.5, 2.5, 4.0)
        var checked = 0
        for (vertical in verticals) {
            val tip = tipFor(vertical)
            for (speed in speeds) {
                for (b in SpinPhysics.preImpactRegimeBoundaries(speed, tip, table)) {
                    if (b.distanceMeters < 0.01) continue
                    assertEquals(
                        b.before, stateAt(tip, speed, b.distanceMeters - 1e-3),
                        "before ${b.change} (tip $vertical, ${speed} m/s)",
                    )
                    assertEquals(
                        b.after, stateAt(tip, speed, b.distanceMeters + 1e-3),
                        "after ${b.change} (tip $vertical, ${speed} m/s)",
                    )
                    val at = SpinPhysics.computePreImpactCueState(speed, tip, b.distanceMeters, table)
                    assertEquals(b.speedMps, at.impactSpeedMps, 2e-3, "speed at ${b.change}")
                    checked++
                }
            }
        }
        assertTrue(checked > 40, "expected many boundaries to be exercised, got $checked")
    }

    @Test
    fun slideEndsAtFiveSeventhsLawForSpinningStrikes() {
        val speed = 2.0
        // The slide ends at (5 + 2 k0) / 7 of the stroke speed, here for a light draw strike.
        val tip = tipFor(-0.10 / 2.5)
        val k0 = SpinPhysics.initialSpinFactorForTip(tip)
        assertEquals(-0.10, k0, 1e-9)
        val ends = SpinPhysics.preImpactRegimeBoundaries(speed, tip, table)
            .single { it.change == SpinRegimeChange.SLIDE_ENDS }
        assertEquals((5.0 + 2.0 * k0) / 7.0 * speed, ends.speedMps, 1e-9)
        // and it is within the spin correction (2 k0 / 7) of the stun law of MotionPhysics
        assertEquals(MotionPhysics.stunRollingSpeed(speed), ends.speedMps, 0.03 * speed)
        // distance: v0^2 - v^2 = 2 * muS * g * d with the cloth's full sliding friction
        val muS = table.cloth.slidingFrictionMuS
        val expectedDistance = (speed * speed - ends.speedMps * ends.speedMps) / (2.0 * muS * MotionPhysics.GRAVITY_M_S2)
        assertEquals(expectedDistance, ends.distanceMeters, 1e-9)
    }

    @Test
    fun centreBallStrikeSlidesFiveSeventhsOfItsSpeedAndThenRolls() {
        val speed = 2.0
        val muS = table.cloth.slidingFrictionMuS
        val g = MotionPhysics.GRAVITY_M_S2
        val boundaries = SpinPhysics.preImpactRegimeBoundaries(speed, tipFor(0.0), table)
        val ends = boundaries.single { it.change == SpinRegimeChange.SLIDE_ENDS }
        assertEquals(MotionPhysics.stunRollingSpeed(speed), ends.speedMps, 1e-9)
        assertEquals(12.0 * speed * speed / (49.0 * muS * g), ends.distanceMeters, 1e-9)
        assertEquals(1.0, ends.spinFactorK, 1e-12)
        // The slide passes the "starts to pick up roll" threshold (k = 0.18) first: R*omega = 5/2 (v0 - v).
        val stunToRoll = boundaries.single { it.change == SpinRegimeChange.STUN_TO_ROLL }
        val vAtThreshold = speed * 2.5 / (0.18 + 2.5)
        assertEquals(vAtThreshold, stunToRoll.speedMps, 1e-9)
        assertEquals((speed * speed - vAtThreshold * vAtThreshold) / (2.0 * muS * g), stunToRoll.distanceMeters, 1e-9)
        assertEquals(0.18, stunToRoll.spinFactorK, 1e-12)
        assertTrue(stunToRoll.distanceMeters < ends.distanceMeters)
    }

    @Test
    fun firmDrawAtShortDistanceKeepsDrawAtContact() {
        val tip = tipFor(-0.38)
        val shot = shoot(Vec2(1.25, 0.55), Vec2(1.80, 0.80), PocketId.FOOT_RIGHT, 3.0, tip)
        assertTrue(shot.waypoints.none { it.kind == PathWaypointKind.SPIN_TRANSITION })
        val contact = shot.waypoints.first { it.kind == PathWaypointKind.OBJECT_CONTACT }
        assertEquals(ImpactSpinState.BACKSPIN_DRAW, contact.regimeBefore)
        assertTrue(contact.verticalSpinBefore!! < -0.08)
        // Backspin is kept through the collision.
        assertEquals(contact.verticalSpinBefore!!, contact.verticalSpinAfter!!, 1e-12)
    }

    @Test
    fun objectContactReportsTransferredSpeedAndCueBallLeaveSpeed() {
        val speed = 2.2
        val tip = tipFor(0.0)
        val shot = shoot(longCut.first, longCut.second, longCut.third, speed, tip)
        val pre = SpinPhysics.computePreImpactCueState(speed, tip, shot.aim.cueToGhostDistanceMeters, table)
        val contact = shot.waypoints.first { it.kind == PathWaypointKind.OBJECT_CONTACT }
        // The object ball takes (1 + e)/2 of the approach speed along the line of centres; the throw adds a small
        // sideways part so that it leaves along the pot line, which makes it a little faster than the normal part alone.
        val v = pre.impactSpeedMps
        val e = MotionPhysics.BALL_BALL_RESTITUTION
        val arriving = BallState(
            shot.aim.ghostBallCenter,
            shot.aim.aimDirection * v,
            shot.aim.aimDirection * (pre.impactSpinFactorK * v),
        )
        val lineOfCentres = BallBallContact.lineOfCentresForThrownDirection(arriving, shot.aim.objectBallDirection)
        val throwRad = shot.aim.objectBallDirection.angleTo(lineOfCentres)
        val normalPart = 0.5 * (1.0 + e) * v * kotlin.math.cos(shot.aim.aimDirection.angleTo(lineOfCentres))
        // The line of centres comes from a fixed-point iteration, converged to well under 1e-4 here.
        assertEquals(normalPart / kotlin.math.cos(throwRad), contact.objectBallSpeedMps!!, 1e-4)
        assertEquals(
            MotionPhysics.transferredObjectBallInitialSpeedMps(v, shot.aim.cutAngleRadians),
            contact.objectBallSpeedMps!!,
            0.02 * contact.objectBallSpeedMps!!,
        )
        assertEquals(pre.impactSpeedMps, contact.speedInMps!!, 1e-12)
        assertEquals(abs(shot.aim.cutAngleRadians), contact.cutAngleRadians!!, 1e-12)
        assertEquals(1, contact.otherBallNumber)
        // Equal masses: the cue ball keeps what the object ball did not take.
        val cueOut = shot.aim.aimDirection * v - shot.aim.objectBallDirection * contact.objectBallSpeedMps!!
        assertEquals(cueOut.length(), contact.speedOutMps!!, 1e-4)
        assertTrue(contact.speedOutMps!! < contact.speedInMps!!)
    }

    @Test
    fun oneCushionPositionShotHasOneCushionWaypointWithLossOfSpeedAndSpin() {
        val tip = tipFor(0.0, side = 0.30)
        val shot = shoot(Vec2(0.60, 0.30), Vec2(1.10, 0.45), PocketId.SIDE_RIGHT, 1.5, tip)
        val cushions = shot.waypoints.filter { it.kind == PathWaypointKind.CUSHION_CONTACT }
        assertEquals(1, cushions.size, "kinds: ${shot.waypoints.map { it.kind }}")
        val c = cushions.single()
        assertEquals(1, c.cushionIndex)
        assertTrue(c.speedOutMps!! < c.speedInMps!!)
        // The english is still there when the ball arrives (worn down a little on the way), rolling.
        assertTrue(c.sideSpinBefore!! * tip.clampedSideRatio > 0.0)
        assertTrue(abs(c.sideSpinBefore!!) > 0.5 * abs(tip.clampedSideRatio))
        assertEquals(1.0, c.verticalSpinBefore!!, 1e-9)
        // The cushion impulse turns part of the roll and changes the english, and the ball leaves sliding: the spin
        // reported after the cushion is the real one, not natural roll.
        assertTrue(abs(c.verticalSpinAfter!! - 1.0) > 0.05, "after the cushion: ")
        assertTrue(c.sideSpinAfter!! != c.sideSpinBefore!!)
        val rest = shot.waypoints.last()
        assertEquals(PathWaypointKind.CUE_REST, rest.kind)
        assertEquals(1, rest.cushionCount)
        assertTrue(c.distanceAlongPathMeters < rest.distanceAlongPathMeters)
    }

    @Test
    fun objectBallThatRunsOutOfSpeedGetsARestWaypointShortOfThePocket() {
        val shot = shoot(Vec2(0.60, 0.30), Vec2(1.10, 0.45), PocketId.SIDE_RIGHT, 1.1, tipFor(0.0, side = 0.30))
        assertTrue(!shot.simulation.targetBallPotted)
        val rest = shot.waypoints.single { it.kind == PathWaypointKind.OBJECT_BALL_REST }
        assertTrue(rest.shortfallMeters!! > 0.0)
        assertTrue(rest.travelMeters!! > 0.0)
        assertEquals(1, rest.otherBallNumber)
        // It belongs to the contact: same position along the cue ball's path, listed right after it.
        val contactIndex = shot.waypoints.indexOfFirst { it.kind == PathWaypointKind.OBJECT_CONTACT }
        assertEquals(PathWaypointKind.OBJECT_BALL_REST, shot.waypoints[contactIndex + 1].kind)
    }

    @Test
    fun postContactSlideEndsWhereTheSlideLawPutsItAtTheRealRollingSpeed() {
        val speed = 2.0
        // A slide after contact needs a cue ball that arrives without spin, which over this distance takes a low tip.
        val distance = shoot(Vec2(0.60, 0.30), Vec2(1.10, 0.45), PocketId.SIDE_RIGHT, speed, tipFor(0.0))
            .aim.cueToGhostDistanceMeters
        val tip = assertNotNull(
            SpinPhysics.tipForImpactState(CueBallSpinPreset.STUN, speed, distance, table, sideRatio = 0.30),
        )
        val shot = shoot(Vec2(0.60, 0.30), Vec2(1.10, 0.45), PocketId.SIDE_RIGHT, speed, tip)
        val slide = shot.waypoints.single { it.kind == PathWaypointKind.POST_IMPACT_TRANSITION }
        val contact = shot.waypoints.first { it.kind == PathWaypointKind.OBJECT_CONTACT }
        val post = SpinPhysics.computePostImpactCueState(shot.aim, speed, tip, table)

        // The contact reports the physical launch speed, and the slide ends where and as fast as the sliding law says:
        // v_f = (5 v0 + 2 w0) / 7 after 2 |u0| / (7 muS g).
        assertEquals(post.launchSpeedMps, contact.speedOutMps!!, 1e-12)
        val v0 = post.cue.velocity
        val w0 = post.cue.spinVelocity
        val vf = (v0 * 5.0 + w0 * 2.0) / 7.0
        assertEquals(vf.length(), slide.speedOutMps!!, 1e-9)
        assertEquals(vf.length(), slide.speedInMps!!, 1e-9)
        val muSg = table.cloth.slidingFrictionMuS * MotionPhysics.GRAVITY_M_S2
        val tSlide = 2.0 * (v0 - w0).length() / (7.0 * muSg)
        val expectedEnd = post.cue.position + v0 * tSlide - (v0 - w0).normalized() * (0.5 * muSg * tSlide * tSlide)
        assertEquals(0.0, slide.position.distanceTo(expectedEnd), 1e-6)
        // A cue ball that arrives without spin slides straight (its slip is along its velocity), so the distance
        // along the path is the straight distance to the slide end.
        assertEquals(
            contact.distanceAlongPathMeters + post.cue.position.distanceTo(expectedEnd),
            slide.distanceAlongPathMeters,
            1e-6,
        )
        val first = shot.simulation.cueBallTrajectory.points
        val onFirstSegment = first[0].distanceTo(slide.position) + slide.position.distanceTo(first[1])
        assertEquals(first[0].distanceTo(first[1]), onFirstSegment, 1e-6)
    }

    @Test
    fun softStrokeOverLongDistanceStopsShortWithoutContactWaypoint() {
        val shot = shoot(longCut.first, longCut.second, longCut.third, 0.6, tipFor(0.0))
        assertTrue(shot.simulation.cueBallStoppedBeforeImpact)
        val last = shot.waypoints.last()
        assertEquals(PathWaypointKind.CUE_STOPS_SHORT, last.kind)
        assertTrue(shot.waypoints.none { it.kind == PathWaypointKind.OBJECT_CONTACT })
        assertTrue(shot.waypoints.none { it.kind == PathWaypointKind.CUE_REST })
        assertTrue(last.shortfallMeters!! > 0.0)
        assertEquals(
            shot.aim.cueToGhostDistanceMeters,
            last.travelMeters!! + last.shortfallMeters!!,
            1e-9,
        )
        assertEquals(0.0, last.speedOutMps!!, 1e-12)
        // Every earlier point lies before the stop.
        assertTrue(shot.waypoints.dropLast(1).all { it.distanceAlongPathMeters <= last.distanceAlongPathMeters })
    }

    @Test
    fun scratchEndsWithScratchWaypointAndNoRestWaypoint() {
        val shot = shoot(Vec2(1.20, 0.80), Vec2(2.20, 1.15), PocketId.FOOT_RIGHT, 3.5, tipFor(0.35))
        assertNotNull(shot.simulation.cueBallTrajectory.hitsScratchPocket, "scenario should scratch")
        assertEquals(PathWaypointKind.SCRATCH, shot.waypoints.last().kind)
        assertTrue(shot.waypoints.none { it.kind == PathWaypointKind.CUE_REST })
        assertEquals(1, shot.waypoints.count { it.kind == PathWaypointKind.SCRATCH })
        assertTrue(shot.simulation.cueBallEvents.any { it.kind == CueBallEventKind.SCRATCH })
    }

    @Test
    fun cueBallEventsNeverAddSpeed() {
        val blocker = Ball(number = 2, position = Vec2(0.30, 0.95))
        val shot = shoot(Vec2(0.90, 0.35), Vec2(1.60, 0.40), PocketId.FOOT_RIGHT, 2.5, tipFor(-0.20), listOf(blocker))
        for (ev in shot.simulation.cueBallEvents) {
            if (ev.kind != CueBallEventKind.SCRATCH) {
                assertTrue(ev.speedOutMps <= ev.speedInMps + 1e-9, "${ev.kind} must not add speed")
            }
        }
        assertNull(shot.waypoints.firstOrNull { it.kind == PathWaypointKind.CUE_STOPS_SHORT })
    }
}
