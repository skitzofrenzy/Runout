package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.BallBallContact
import com.runout.engine.physics.BallMotion
import com.runout.engine.physics.BallMotionPhase
import com.runout.engine.physics.BallState
import com.runout.engine.physics.CueBallEventKind
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ball-state model after contact: the vector sliding law, the contact law, and the simulator that carries every
 * ball's velocity and spin through slides, rolls and collisions.
 */
class BallMotionAndContactTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters
    private val g = MotionPhysics.GRAVITY_M_S2
    private val muS = table.cloth.slidingFrictionMuS
    private val muR = table.cloth.rollingResistanceMuR
    private val motion = BallMotion(table.cloth)
    private val e = MotionPhysics.BALL_BALL_RESTITUTION

    private fun degrees(rad: Double) = rad * 180.0 / PI

    /** Aim geometry of a cut of [cutDeg] at a ball on open cloth, pot line +x, ghost [distance] from the cue ball. */
    private fun openAim(cutDeg: Double, distance: Double = 0.4, objectAt: Vec2 = Vec2(1.30, 0.635)): AimGeometry {
        val d = Vec2(1.0, 0.0)
        val ghost = objectAt - d * (2.0 * r)
        val aimDir = d.rotate(-cutDeg * PI / 180.0)
        val cue = ghost - aimDir * distance
        return AimGeometry(
            cueBallPos = cue,
            objectBallPos = objectAt,
            pocketAimPos = objectAt + d * 0.8,
            ghostBallCenter = ghost,
            aimDirection = aimDir,
            objectBallDirection = d,
            cueToGhostDistanceMeters = distance,
            objectToPocketDistanceMeters = 0.8,
            cutAngleRadians = aimDir.angleTo(d),
            isCutPossible = true,
            isCueToGhostClear = true,
            isObjectToPocketClear = true,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Sliding and rolling in closed form
    // ---------------------------------------------------------------------------------------------

    @Test
    fun slidingBallFollowsTheVectorSlideLawThenRolls() {
        val v0 = Vec2(1.1, 0.3)
        val w0 = Vec2(-0.5, 0.4)
        val s = BallState(Vec2(1.0, 0.6), v0, w0)
        val u0 = v0 - w0
        val uHat = u0.normalized()
        assertEquals(BallMotionPhase.SLIDING, motion.phaseOf(s))

        val tSlide = 2.0 * u0.length() / (7.0 * muS * g)
        assertEquals(tSlide, motion.timeToRoll(s), 1e-12)
        assertEquals(tSlide, MotionPhysics.slideToRollTimeSeconds(u0.length(), muS), 1e-12)
        for (f in listOf(0.0, 0.2, 0.5, 0.9)) {
            val t = f * tSlide
            assertEquals(0.0, motion.velocityAt(s, t).distanceTo(v0 - uHat * (muS * g * t)), 1e-12)
            assertEquals(0.0, motion.spinAt(s, t).distanceTo(w0 + uHat * (2.5 * muS * g * t)), 1e-12)
            assertEquals(0.0, motion.positionAt(s, t).distanceTo(s.position + v0 * t - uHat * (0.5 * muS * g * t * t)), 1e-12)
            // The slip shrinks along a fixed direction.
            val u = motion.stateAt(s, t).slipVelocity
            if (u.length() > 1e-9) assertEquals(0.0, u.normalized().distanceTo(uHat), 1e-9)
        }
        val end = motion.endOfPhase(s)
        val vf = (v0 * 5.0 + w0 * 2.0) / 7.0
        assertEquals(0.0, end.velocity.distanceTo(vf), 1e-12)
        assertEquals(0.0, end.spinVelocity.distanceTo(vf), 1e-12)
        assertEquals(BallMotionPhase.ROLLING, motion.phaseOf(end))

        // Then it rolls straight at muR * g and stops after |vf| / (muR g), v^2 / (2 muR g) farther on.
        val tStop = vf.length() / (muR * g)
        assertEquals(tSlide + tStop, motion.timeToStop(s), 1e-9)
        val rest = motion.advance(s, tSlide + tStop + 1.0)
        assertEquals(BallMotionPhase.STATIONARY, motion.phaseOf(rest))
        val rollDistance = MotionPhysics.rollingStoppingDistanceMeters(vf.length(), muR)
        assertEquals(0.0, rest.position.distanceTo(end.position + vf.normalized() * rollDistance), 1e-9)
    }

    @Test
    fun stunBallReachesFiveSeventhsAndSlidesTwelveFortyNinths() {
        val v = 1.7
        val s = BallState(Vec2(0.5, 0.5), Vec2(v, 0.0))
        val end = motion.endOfPhase(s)
        assertEquals(5.0 / 7.0 * v, end.velocity.length(), 1e-12)
        assertEquals(12.0 * v * v / (49.0 * muS * g), end.position.distanceTo(s.position), 1e-12)
        assertEquals(1, motion.chordPieces(s, motion.phaseDuration(s)), "A slide without spin is straight")
    }

    @Test
    fun timeToReachCoordinateSolvesTheQuadraticInTheRightDirection() {
        // x(t) = 0.2 + 1.0 t - 0.5 t^2 reaches 0.5 at t = 1 - sqrt(0.4) going up, never comes back up to 0.9.
        val t = assertNotNull(BallMotion.timeToReachCoordinate(0.2, 1.0, -1.0, 0.5, +1, 5.0))
        assertEquals(1.0 - kotlin.math.sqrt(0.4), t, 1e-12)
        assertNull(BallMotion.timeToReachCoordinate(0.2, 1.0, -1.0, 0.9, +1, 5.0))
        // Crossing the same line on the way back is a crossing in the other direction.
        val back = assertNotNull(BallMotion.timeToReachCoordinate(0.2, 1.0, -1.0, 0.5, -1, 5.0))
        assertEquals(1.0 + kotlin.math.sqrt(0.4), back, 1e-12)
        assertNull(BallMotion.timeToReachCoordinate(0.2, 1.0, -1.0, 0.5, +1, 0.1), "beyond the horizon")
    }

    @Test
    fun contactTimeMatchesBruteForceIncludingGrazingPasses() {
        val d = 2.0 * r
        val horizon = 0.8
        // A sliding ball curving past or into a ball at rest, and two moving balls.
        val cases = listOf(
            Pair(BallState(Vec2(0.5, 0.5), Vec2(1.2, 0.0), Vec2(-0.6, 0.5)), BallState(Vec2(0.9, 0.56))),
            Pair(BallState(Vec2(0.5, 0.5), Vec2(1.2, 0.0), Vec2(-0.6, 0.5)), BallState(Vec2(0.9, 0.62))),
            Pair(BallState(Vec2(0.5, 0.5), Vec2(1.0, 0.2)), BallState(Vec2(1.0, 0.5), Vec2(-0.4, 0.3))),
            Pair(BallState(Vec2(0.5, 0.5), Vec2(1.0, 0.0), Vec2(1.0, 0.0)), BallState(Vec2(1.0, 0.5 + d - 1e-5))),
            Pair(BallState(Vec2(0.5, 0.5), Vec2(1.0, 0.0), Vec2(1.0, 0.0)), BallState(Vec2(1.0, 0.5 + d + 1e-5))),
        )
        for ((a, b) in cases) {
            val accA = motion.acceleration(a)
            val accB = motion.acceleration(b)
            val hA = motion.phaseDuration(a)
            val hB = motion.phaseDuration(b)
            val h = minOf(horizon, hA, hB)
            val found = BallMotion.contactTime(a.position, a.velocity, accA, b.position, b.velocity, accB, d, h)
            // Brute force on a fine time grid.
            var brute: Double? = null
            val steps = 200_000
            for (i in 0..steps) {
                val t = h * i / steps
                val pa = a.position + a.velocity * t + accA * (0.5 * t * t)
                val pb = b.position + b.velocity * t + accB * (0.5 * t * t)
                if (pa.distanceTo(pb) <= d) {
                    brute = t
                    break
                }
            }
            if (brute == null) {
                assertNull(found, "no contact expected")
            } else {
                assertNotNull(found, "contact expected near $brute")
                assertEquals(brute, found, h / steps + 1e-8)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Contact law
    // ---------------------------------------------------------------------------------------------

    @Test
    fun stunCueBallLeavesAlongTheTangentWithPerfectRestitutionAndInsideItOtherwise() {
        val v = 1.5
        for ((cutDeg, insideDeg) in listOf(30.0 to 3.47, 10.0 to 11.23)) {
            val phi = cutDeg * PI / 180.0
            val n = Vec2(1.0, 0.0)
            val incoming = BallState(Vec2.ZERO, n.rotate(-phi) * v)
            val struck = BallState(Vec2(2.0 * r, 0.0))

            val elastic = BallBallContact.resolve(incoming, struck, n, restitution = 1.0, ballBallFrictionMu = 0.0)
            assertEquals(0.0, elastic.striking.velocity.dot(n), 1e-12, "e = 1: exactly along the tangent line")
            assertEquals(v * sin(phi), elastic.striking.velocity.length(), 1e-12)
            assertEquals(v * cos(phi), elastic.struck.velocity.dot(n), 1e-12)

            val real = BallBallContact.resolve(incoming, struck, n, restitution = e, ballBallFrictionMu = 0.0)
            assertEquals(0.5 * (1.0 + e) * v * cos(phi), real.struck.velocity.dot(n), 1e-12)
            assertEquals(0.5 * (1.0 - e) * v * cos(phi), real.striking.velocity.dot(n), 1e-12)
            val inside = 90.0 - degrees(abs(real.striking.velocity.angleTo(n)))
            assertEquals(degrees(atan(0.5 * (1.0 - e) * cos(phi) / sin(phi))), inside, 1e-9)
            assertEquals(insideDeg, inside, 0.01)
            // The contact does not touch the spin.
            assertEquals(incoming.spinVelocity, real.striking.spinVelocity)
        }
    }

    @Test
    fun rollingCueBallDeflectionFollowsFromTheLawNotACase() {
        fun rollingDeflectionDeg(cutDeg: Double, restitution: Double): Double {
            val phi = cutDeg * PI / 180.0
            val n = Vec2(1.0, 0.0)
            val aim = n.rotate(-phi)
            val incoming = BallState.rolling(Vec2.ZERO, aim)
            val out = BallBallContact.resolve(incoming, BallState(Vec2(2.0 * r, 0.0)), n, restitution, 0.0).striking
            val vf = motion.endOfPhase(out).velocity
            return abs(degrees(aim.angleTo(vf)))
        }
        // The 30° rule: 33.7° at half-ball with e = 1, and between quarter- and three-quarter-ball not far from 30°.
        assertEquals(33.67, rollingDeflectionDeg(30.0, 1.0), 0.01)
        for (sinCut in listOf(0.25, 0.75)) {
            assertTrue(rollingDeflectionDeg(degrees(asin(sinCut)), 1.0) in 27.0..34.0)
        }
        // With real restitution the cue ball keeps a little speed along the line of centres and deflects less.
        val real = rollingDeflectionDeg(30.0, e)
        assertTrue(real in 31.0..32.5, "half-ball with e = $e: $real°")
    }

    @Test
    fun drawOnACutFollowsTheTangentLineThenCurvesBack() {
        val aim = openAim(30.0)
        val layout = TableLayout(table, listOf(Ball(0, aim.cueBallPos, r), Ball(1, aim.objectBallPos, r)))
        val speed = 2.0
        val tip = assertNotNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.DRAW, speed, aim.cueToGhostDistanceMeters, table))
        val sim = TableCollisionSimulator.simulatePostImpact(layout, layout.balls[1], aim, speed, tip)
        val post = SpinPhysics.computePostImpactCueState(aim, speed, tip, table)
        val v0 = post.cue.velocity
        val w0 = post.cue.spinVelocity
        val u0 = v0 - w0

        // Leaves close to the tangent line of the contact.
        val n = post.objectBall.velocity.rotate(post.throwRadians).normalized()
        assertTrue(90.0 - degrees(abs(v0.angleTo(n))) < 6.0)
        val pts = sim.cueBallTrajectory.sampledPoints
        assertEquals(aim.aimDirection.angleTo(v0), sim.cueBallTrajectory.initialDeflectionAngleRad, 1e-12)
        assertTrue(abs((pts[1] - pts[0]).normalized().cross(v0.normalized())) < 0.15, "the first chord starts along the launch line")

        // The slide ends 2|u0| / (7 muS g) later at (5 v0 + 2 w0) / 7, on the parabola x0 + v0 t - muS g t^2 u_hat / 2.
        val tSlide = 2.0 * u0.length() / (7.0 * muS * g)
        val slideEnd = post.cue.position + v0 * tSlide - u0.normalized() * (0.5 * muS * g * tSlide * tSlide)
        val event = sim.cueBallEvents.first()
        assertEquals(CueBallEventKind.SLIDE_ENDS, event.kind)
        assertEquals(0.0, event.position.distanceTo(slideEnd), 1e-9)
        val vf = (v0 * 5.0 + w0 * 2.0) / 7.0
        assertEquals(vf.length(), event.speedOutMps, 1e-9)
        assertTrue(vf.dot(aim.objectBallDirection) < 0.0, "Draw finishes behind the tangent line")

        // It bends one way only: every turn between successive chords up to the slide end has the same sign.
        val endIndex = pts.indexOfFirst { it.distanceTo(slideEnd) < 1e-9 }
        assertTrue(endIndex >= 3)
        var turnSign = 0.0
        for (i in 1 until endIndex) {
            val turn = (pts[i] - pts[i - 1]).cross(pts[i + 1] - pts[i])
            if (abs(turn) < 1e-14) continue
            if (turnSign == 0.0) turnSign = turn
            assertTrue(turn * turnSign > 0.0)
        }
        // After that it rolls straight along vf to its next contact or to rest.
        val lastDir = (pts[endIndex + 1] - slideEnd).normalized()
        assertEquals(0.0, lastDir.distanceTo(vf.normalized()), 1e-9)
    }

    @Test
    fun straightFollowAndDrawTravelTheClosedFormDistanceIncludingRestitution() {
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val ob = Vec2(1.70, 0.85)
        val potDir = (com.runout.engine.physics.PocketGeometry.aimPoint(ob, pocket, r) - ob).normalized()
        val ghost = ob - potDir * (2.0 * r)
        for ((speed, preset) in listOf(1.0 to CueBallSpinPreset.FOLLOW, 2.0 to CueBallSpinPreset.DRAW)) {
            val cue = ghost - potDir * 0.3
            val aim = com.runout.engine.physics.GhostBallAiming.computeAimGeometry(Ball(0, cue, r), Ball(1, ob, r), pocket)
            val tip = assertNotNull(SpinPhysics.tipForImpactState(preset, speed, aim.cueToGhostDistanceMeters, table))
            val pre = SpinPhysics.computePreImpactCueState(speed, tip, aim.cueToGhostDistanceMeters, table)
            val layout = TableLayout(table, listOf(Ball(0, cue, r), Ball(1, ob, r)))
            val sim = TableCollisionSimulator.simulatePostImpact(layout, layout.balls[1], aim, speed, tip)
            assertTrue(sim.targetBallPotted)

            // Along the line: the cue ball keeps v' = (1 - e)/2 v and its spin w = k v; it slides to (5 v' + 2 w)/7.
            val v = pre.impactSpeedMps
            val vKept = 0.5 * (1.0 - e) * v
            val w = pre.impactSpinFactorK * v
            val slip = vKept - w
            val tSlide = 2.0 * abs(slip) / (7.0 * muS * g)
            val slideTravel = vKept * tSlide - kotlin.math.sign(slip) * 0.5 * muS * g * tSlide * tSlide
            val vRoll = (5.0 * vKept + 2.0 * w) / 7.0
            val expected = slideTravel + kotlin.math.sign(vRoll) * vRoll * vRoll / (2.0 * muR * g)
            val travelled = (sim.cueBallTrajectory.points.last() - ghost).dot(potDir)
            assertEquals(expected, travelled, 1e-9, "$preset")

            // Without restitution (the cue ball keeping nothing along the line) the closed form differs noticeably:
            // follow runs about a fifth farther with it, draw comes back shorter.
            val vRollNoE = 2.0 * w / 7.0
            val noRestitution = kotlin.math.sign(w) * 0.5 * muS * g * (2.0 * abs(w) / (7.0 * muS * g)).let { it * it } +
                kotlin.math.sign(vRollNoE) * vRollNoE * vRollNoE / (2.0 * muR * g)
            if (preset == CueBallSpinPreset.FOLLOW) {
                assertTrue(travelled > 1.15 * noRestitution)
            } else {
                assertTrue(travelled < 0.0 && abs(travelled) < 0.95 * abs(noRestitution))
            }
        }
    }

    @Test
    fun objectBallSlidesThenRollsAtTheTransferredSpeed() {
        val aim = openAim(25.0, distance = 0.5, objectAt = Vec2(0.9, 0.5))
        val layout = TableLayout(table, listOf(Ball(0, aim.cueBallPos, r), Ball(1, aim.objectBallPos, r)))
        val speed = 0.8
        val tip = TipOffset()
        val sim = TableCollisionSimulator.simulatePostImpact(layout, layout.balls[1], aim, speed, tip)
        val post = SpinPhysics.computePostImpactCueState(aim, speed, tip, table)
        val v = post.impactSpeedMps
        val n = post.objectBall.velocity.rotate(post.throwRadians).normalized()

        // Launch: (1 + e)/2 v cos(phi) along the line of centres; the throw turns it onto the pot line.
        val normalPart = 0.5 * (1.0 + e) * v * aim.aimDirection.dot(n)
        assertEquals(normalPart, post.objectBall.velocity.dot(n), 1e-12)
        assertEquals(0.0, post.objectBall.velocity.normalized().distanceTo(aim.objectBallDirection), 1e-5)
        assertEquals(Vec2.ZERO, post.objectBall.spinVelocity)

        // It slides straight to 5/7 of its launch speed, then rolls: its whole path is a straight line of the classic
        // slide-plus-roll length, within a couple of percent of the throw-free transferred-speed value.
        val launch = post.objectBallSpeedMps
        val obState = BallState(aim.objectBallPos, post.objectBall.velocity)
        assertEquals(5.0 / 7.0 * launch, motion.endOfPhase(obState).velocity.length(), 1e-12)
        val path = assertNotNull(sim.targetBallTrajectory)
        assertNull(path.pocketedIn)
        val travel = path.points.first().distanceTo(path.points.last())
        assertEquals(MotionPhysics.objectBallStoppingDistanceMeters(launch, muS, muR), travel, 1e-9)
        val before = MotionPhysics.objectBallStoppingDistanceMeters(
            MotionPhysics.transferredObjectBallInitialSpeedMps(v, aim.cutAngleRadians), muS, muR,
        )
        assertEquals(before, travel, 0.05 * before)
        // Speed on the way: while it slides it is faster than a ball rolling the same total distance would be.
        val halfSlide = 0.5 * 12.0 * launch * launch / (49.0 * muS * g)
        val real = MotionPhysics.slideThenRollSpeedAfterDistanceMps(launch, halfSlide, muS, muR)
        assertEquals(kotlin.math.sqrt(launch * launch - 2.0 * muS * g * halfSlide), real, 1e-12)
        val equivalentRolling = MotionPhysics.rollingSpeedAfterDistanceMps(
            MotionPhysics.initialRollingSpeedForDistance(travel, muR), halfSlide, muR,
        )
        assertTrue(real > equivalentRolling * 1.1)
    }

    @Test
    fun secondaryCollisionCarriesTheCueBallsRollThroughTheContact() {
        // Pure contact law: a rolling ball hitting a ball at rest full follows through at 2/7 of its speed (e = 1),
        // and deflects 33.7° at half-ball, not the 60° of a frictionless velocity-only impulse.
        val v = 1.0
        val rolling = BallState.rolling(Vec2.ZERO, Vec2(v, 0.0))
        val full = BallBallContact.resolve(rolling, BallState(Vec2(2.0 * r, 0.0)), Vec2(1.0, 0.0), 1.0, 0.0)
        assertEquals(0.0, full.striking.velocity.length(), 1e-12)
        assertEquals(2.0 / 7.0 * v, motion.endOfPhase(full.striking).velocity.x, 1e-12)

        // Through the simulator: a stun shot whose cue ball, rolling by then, runs into a third ball.
        val aim = openAim(30.0, distance = 0.3, objectAt = Vec2(0.9, 0.9))
        val speed = 2.0
        val tip = assertNotNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.STUN, speed, aim.cueToGhostDistanceMeters, table))
        val post = SpinPhysics.computePostImpactCueState(aim, speed, tip, table)
        val line = post.cue.velocity.normalized()
        for (offsetRatio in listOf(0.0, 1.0)) {
            // Full hit (offset 0) or half-ball (offset R) on a ball 0.35 m down the stun line.
            val side = Vec2(-line.y, line.x)
            val blocker = Ball(2, aim.ghostBallCenter + line * 0.35 + side * (offsetRatio * r), r)
            val layout = TableLayout(table, listOf(Ball(0, aim.cueBallPos, r), Ball(1, aim.objectBallPos, r), blocker))
            val sim = TableCollisionSimulator.simulatePostImpact(layout, layout.balls[1], aim, speed, tip)
            val contactIndex = sim.cueBallEvents.indexOfFirst { it.kind == CueBallEventKind.BALL_CONTACT }
            assertTrue(contactIndex >= 0)
            val contact = sim.cueBallEvents[contactIndex]
            assertEquals(2, contact.otherBallNumber)
            assertEquals(1.0, contact.verticalSpinBefore, 1e-9, "the cue ball arrives rolling")
            val slideEnd = sim.cueBallEvents.drop(contactIndex + 1).first { it.kind == CueBallEventKind.SLIDE_ENDS }
            val cuePts = sim.cueBallTrajectory.sampledPoints
            val atContact = cuePts.indexOfFirst { it.distanceTo(contact.position) < 1e-9 }
            val incoming = (cuePts[atContact] - cuePts[atContact - 1]).normalized()
            val endIdx = cuePts.indexOfFirst { it.distanceTo(slideEnd.position) < 1e-9 }
            val outgoing = (cuePts[endIdx + 1] - cuePts[endIdx]).normalized()
            if (offsetRatio == 0.0) {
                // Follows through at (5 (1 - e)/2 + 2)/7 of its speed, give or take the throw: never stops dead.
                assertEquals((5.0 * 0.5 * (1.0 - e) + 2.0) / 7.0 * contact.speedInMps, slideEnd.speedOutMps, 0.01 * contact.speedInMps)
                assertTrue(outgoing.dot(incoming) > 0.99)
            } else {
                val deflection = abs(degrees(incoming.angleTo(outgoing)))
                assertTrue(deflection in 28.0..34.0, "Rolling half-ball deflection should be about 31-34°, was $deflection°")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Whole-table properties
    // ---------------------------------------------------------------------------------------------

    @Test
    fun energyNeverIncreasesOnClothOrInContacts() {
        // Along a slide and a roll.
        val s = BallState(Vec2(1.0, 0.6), Vec2(1.4, -0.2), Vec2(-0.6, 0.3))
        var last = s.kineticEnergyPerKg()
        val total = motion.timeToStop(s)
        for (i in 1..200) {
            val energy = motion.advance(s, total * i / 200).kineticEnergyPerKg()
            assertTrue(energy <= last + 1e-12)
            last = energy
        }
        // Across contacts with spin, throw and restitution (no side spin).
        val states = listOf(
            BallState(Vec2.ZERO, Vec2(1.2, 0.1), Vec2(-0.5, 0.0)),
            BallState(Vec2.ZERO, Vec2(0.8, -0.3), Vec2(0.8, -0.3)),
            BallState(Vec2.ZERO, Vec2(2.0, 0.5), Vec2(0.0, 0.0)),
        )
        for (a in states) {
            for (cutDeg in listOf(0.0, 15.0, 30.0, 50.0, 70.0)) {
                val n = a.velocity.normalized().rotate(cutDeg * PI / 180.0)
                val b = BallState(n * (2.0 * r), Vec2(0.1, 0.05), Vec2(0.1, 0.05))
                val out = BallBallContact.resolve(a, b, n)
                val before = a.kineticEnergyPerKg() + b.kineticEnergyPerKg()
                val after = out.striking.kineticEnergyPerKg() + out.struck.kineticEnergyPerKg()
                assertTrue(after <= before + 1e-12, "cut $cutDeg: $after > $before")
                // Momentum is conserved (equal masses).
                assertEquals(0.0, (out.striking.velocity + out.struck.velocity).distanceTo(a.velocity + b.velocity), 1e-12)
            }
        }
    }

    @Test
    fun clusterBreakKeepsEveryBallOnTheTableAndIsDeterministic() {
        val aim = openAim(20.0, distance = 0.5, objectAt = Vec2(1.0, 0.6))
        val cluster = listOf(
            Ball(2, Vec2(1.30, 0.42), r), Ball(3, Vec2(1.36, 0.47), r), Ball(4, Vec2(1.30, 0.53), r),
            Ball(5, Vec2(1.42, 0.43), r), Ball(6, Vec2(1.25, 0.30), r),
        )
        val layout = TableLayout(table, listOf(Ball(0, aim.cueBallPos, r), Ball(1, aim.objectBallPos, r)) + cluster)
        for (preset in CueBallSpinPreset.entries) {
            val tip = SpinPhysics.tipForImpactState(preset, 3.5, aim.cueToGhostDistanceMeters, table) ?: TipOffset.fromPreset(preset)
            val first = TableCollisionSimulator.simulatePostImpact(layout, layout.balls[1], aim, 3.5, tip)
            val second = TableCollisionSimulator.simulatePostImpact(layout, layout.balls[1], aim, 3.5, tip)
            assertEquals(first, second)
            for (ball in first.resultingLayout.balls) {
                assertTrue(table.isWithinPlayableBounds(ball.position, r), "ball ${ball.number} left the table: ${ball.position}")
            }
            // Every point of every path is on the table, except the last point of a pocketed ball: the hole.
            val cuePath = first.cueBallTrajectory.sampledPoints.let { if (first.cueBallTrajectory.hitsScratchPocket != null) it.dropLast(1) else it }
            val everyPath = listOf(cuePath) +
                (listOfNotNull(first.targetBallTrajectory) + first.disturbedBalls)
                    .map { if (it.pocketedIn != null) it.sampledPoints.dropLast(1) else it.sampledPoints }
            for (path in everyPath) {
                for (p in path) {
                    assertTrue(p.x in -1e-9..table.lengthMeters + 1e-9 && p.y in -1e-9..table.widthMeters + 1e-9)
                }
            }
        }
    }

    @Test
    fun drawnPathsFollowTheCurveWithinAMillimetre() {
        val aim = openAim(35.0, distance = 0.4)
        val speed = 3.0
        val tip = assertNotNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.DRAW, speed, aim.cueToGhostDistanceMeters, table))
        val path = SpinPhysics.traceCueBallPathWithSpin(aim, speed, tip, table, r, maxBounces = 0)
        val post = SpinPhysics.computePostImpactCueState(aim, speed, tip, table)
        // Every point of the true slide lies within 1 mm of the sampled polyline.
        val tSlide = motion.timeToRoll(post.cue)
        val pts = path.sampledPoints
        for (i in 0..400) {
            val p = motion.positionAt(post.cue, tSlide * i / 400)
            var best = Double.MAX_VALUE
            for (j in 1 until pts.size) {
                val a = pts[j - 1]
                val ab = pts[j] - a
                val lenSq = ab.lengthSquared()
                val t = if (lenSq < 1e-24) 0.0 else ((p - a).dot(ab) / lenSq).coerceIn(0.0, 1.0)
                best = minOf(best, (a + ab * t).distanceTo(p))
            }
            assertTrue(best <= BallMotion.PATH_SAMPLE_TOLERANCE_METERS + 1e-9, "curve point $i is $best m off the drawn line")
        }
        // The contact-vertex polyline keeps only the start, contacts and end.
        assertEquals(path.cushionContacts + path.ballContacts + 2, path.points.size)
    }
}
