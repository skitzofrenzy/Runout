package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.ClothParams
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.BallBallContact
import com.runout.engine.physics.BallMotion
import com.runout.engine.physics.BallState
import com.runout.engine.physics.CushionContact
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cushion impulse model, the slide on the cloth after it, and spin about the vertical axis on the cloth and at
 * contacts.
 */
class CushionContactTest {

    private val table = TableSpec.nineFootPool()
    private val cloth = table.cloth
    private val motion = BallMotion(cloth)
    private val g = MotionPhysics.GRAVITY_M_S2

    /** Cushion along y = R, inward normal +y; the player facing it has +x on the right. */
    private val inward = Vec2(0.0, 1.0)
    private val along = Vec2(1.0, 0.0)

    private fun degrees(rad: Double) = rad * 180.0 / PI

    /**
     * A ball arriving at [speed], [angleDeg] from the normal moving to +x, with vertical spin factor [k] along its
     * travel and side spin given as a tip offset [side] (positive right, which is running english here).
     */
    private fun arriving(speed: Double, angleDeg: Double, k: Double, side: Double = 0.0): BallState {
        val a = angleDeg * PI / 180.0
        val v = along * (speed * sin(a)) - inward * (speed * cos(a))
        return BallState(Vec2(1.0, table.ballRadiusMeters), v, v * k, -2.5 * side * speed)
    }

    /** Angle from the normal of the velocity [v] of a ball moving away from the cushion, positive toward +x. */
    private fun angleFromNormal(v: Vec2): Double = degrees(atan2(v.dot(along), v.dot(inward)))

    private fun finalRollingAngle(s: BallState): Double {
        val out = CushionContact.rebound(s, inward, cloth)
        val rolling = if (motion.timeToRoll(out) > 0.0) motion.endOfPhase(out) else out
        return angleFromNormal(rolling.velocity)
    }

    @Test
    fun headOnBallWithoutSpinComesStraightBackAtTheRestitutionTheTiltAllows() {
        val sinT = 2.0 * cloth.cushionNoseHeightRatio - 1.0
        val cosSq = 1.0 - sinT * sinT
        for (speed in listOf(0.5, 1.0, 2.5)) {
            val out = CushionContact.rebound(arriving(speed, 0.0, 0.0), inward, cloth)
            assertEquals(0.0, out.velocity.x, 1e-12, "straight back")
            assertTrue(out.velocity.y > 0.0)
            // The contact point stops slipping during the impact, so the friction impulse is 2/7 of its slip:
            // v_out / v_in = (1 + e) cos^2(theta) + 2/7 sin^2(theta) - 1.
            val e = cloth.cushionRestitutionEc
            assertEquals((1.0 + e) * cosSq + 2.0 / 7.0 * sinT * sinT - 1.0, out.velocity.y / speed, 1e-12)
        }
        // Livelier rubber, livelier rebound.
        val ratios = listOf(0.6, 0.7, 0.8, 0.9).map { e ->
            CushionContact.rebound(arriving(1.0, 0.0, 0.0), inward, cloth.copy(cushionRestitutionEc = e)).velocity.length()
        }
        assertEquals(ratios.sorted(), ratios)
    }

    @Test
    fun followComesOffLongAndDrawShortAfterTheClothSlide() {
        for (speed in listOf(1.0, 2.0)) {
            for (angle in listOf(30.0, 45.0)) {
                val byK = listOf(-0.5, 0.0, 0.5, 1.0).map { k -> finalRollingAngle(arriving(speed, angle, k)) }
                for (i in 1 until byK.size) {
                    assertTrue(byK[i] > byK[i - 1], "rebound must lengthen with follow at $angle°: $byK")
                }
                assertTrue(byK.last() > angle + 10.0, "rolling ball comes off longer than the mirror angle: $byK")
                assertTrue(byK.first() < angle, "drawing ball comes off shorter than the mirror angle: $byK")
            }
        }
        // The literature reference: a rolling ball at 45° comes off near 65° once the cloth has turned its spin.
        assertEquals(65.0, finalRollingAngle(arriving(1.0, 45.0, 1.0)), 3.0)
    }

    @Test
    fun englishWidensOrNarrowsTheReboundAndMirrorsExactly() {
        // Off the cushion itself running english leaves wider and reverse english narrower. For a ball that arrives
        // rolling this holds only at shallow angles: from about 45° on, a rolling ball slides on the cushion
        // throughout the impact and the friction it can take is shared with its large vertical slip, so english barely
        // changes the rebound in this model (see docs/research/physics.md, cushion).
        val arrivals = listOf(30.0 to 1.0, 30.0 to 0.0, 45.0 to 0.0, 30.0 to -0.5, 45.0 to -0.5, 60.0 to -0.5)
        for ((angle, k) in arrivals) {
            val (run, none, rev) = listOf(0.3, 0.0, -0.3).map {
                angleFromNormal(CushionContact.rebound(arriving(1.0, angle, k, it), inward, cloth).velocity)
            }
            assertTrue(run > none && none > rev, "departure at $angle°, k $k: $run, $none, $rev")
        }
        // After the slide on the cloth the difference survives for a ball that arrives with little or no roll.
        for ((angle, k) in listOf(30.0 to 0.0, 45.0 to 0.0, 30.0 to -0.5, 45.0 to -0.5, 60.0 to -0.5)) {
            val (run, none, rev) = listOf(0.3, 0.0, -0.3).map { finalRollingAngle(arriving(1.0, angle, k, it)) }
            assertTrue(run > none && none > rev, "after the slide at $angle°, k $k: $run, $none, $rev")
        }
        // Mirrored approach with mirrored english: mirrored result.
        val s = arriving(1.3, 40.0, 0.6, 0.25)
        val mirrored = BallState(
            Vec2(-s.position.x, s.position.y),
            Vec2(-s.velocity.x, s.velocity.y),
            Vec2(-s.spinVelocity.x, s.spinVelocity.y),
            -s.sideSpinSurfaceSpeedMps,
        )
        val a = CushionContact.rebound(s, inward, cloth)
        val b = CushionContact.rebound(mirrored, inward, cloth)
        assertEquals(-a.velocity.x, b.velocity.x, 1e-12)
        assertEquals(a.velocity.y, b.velocity.y, 1e-12)
        assertEquals(-a.spinVelocity.x, b.spinVelocity.x, 1e-12)
        assertEquals(a.spinVelocity.y, b.spinVelocity.y, 1e-12)
        assertEquals(-a.sideSpinSurfaceSpeedMps, b.sideSpinSurfaceSpeedMps, 1e-12)
    }

    @Test
    fun reverseEnglishIsTakenOffMuchMoreThanRunningEnglish() {
        for (angle in listOf(30.0, 45.0, 60.0)) {
            for (k in listOf(1.0, 0.0)) {
                val runIn = arriving(1.0, angle, k, 0.3)
                val revIn = arriving(1.0, angle, k, -0.3)
                val runKept = CushionContact.rebound(runIn, inward, cloth).sideSpinSurfaceSpeedMps / runIn.sideSpinSurfaceSpeedMps
                val revKept = CushionContact.rebound(revIn, inward, cloth).sideSpinSurfaceSpeedMps / revIn.sideSpinSurfaceSpeedMps
                assertTrue(runKept > 0.7, "running english keeps most of its spin at $angle°: $runKept")
                assertTrue(revKept < 0.5 * runKept, "reverse english loses much more at $angle°: $revKept vs $runKept")
            }
        }
    }

    @Test
    fun sideSpinDecaysLinearlyOnTheClothAndNeverReverses() {
        val rate = 2.5 * cloth.spinningFrictionMuSp * g
        assertEquals(rate, motion.spinningDeceleration, 1e-12)
        val r = table.ballRadiusMeters
        // In angular terms that is 5 muSp g / (2 R), about 11 rad/s^2 on the default cloth.
        assertEquals(5.0 * cloth.spinningFrictionMuSp * g / (2.0 * r), rate / r, 1e-9)
        assertTrue(rate / r in 8.0..14.0)
        for (s0 in listOf(0.6, -0.6)) {
            val ball = BallState(Vec2(1.0, 0.6), Vec2(0.8, 0.1), Vec2(0.8, 0.1), s0)
            for (t in listOf(0.0, 0.1, 0.5, 1.0)) {
                assertEquals(s0 - kotlin.math.sign(s0) * rate * t, motion.sideSpinAt(ball, t), 1e-12)
            }
            val tDown = motion.timeToSpinDown(ball)
            assertEquals(abs(s0) / rate, tDown, 1e-12)
            assertEquals(0.0, motion.sideSpinAt(ball, tDown + 0.5), 0.0)
            // Through the roll and the stop alike, it keeps wearing down at the same rate.
            val later = motion.advance(ball, 1.0)
            assertEquals(motion.sideSpinAt(ball, 1.0), later.sideSpinSurfaceSpeedMps, 1e-12)
            assertEquals(0.0, motion.advance(ball, 100.0).sideSpinSurfaceSpeedMps, 0.0)
        }
    }

    @Test
    fun sideSpinAtImpactHasWornOffOverTheApproach() {
        val tip = TipOffset(sideRatio = 0.3)
        val speed = 2.0
        val atStrike = SpinPhysics.sideSpinSurfaceSpeedAtImpactMps(tip, speed)
        assertEquals(-2.5 * 0.3 * speed, atStrike, 1e-12)
        var previous = abs(atStrike)
        for (d in listOf(0.3, 0.8, 1.5)) {
            val atImpact = SpinPhysics.sideSpinSurfaceSpeedAtImpactMps(tip, speed, d, table)
            val travel = SpinPhysics.preImpactTravelTimeSeconds(speed, tip, d, table)
            assertEquals(atStrike + 2.5 * cloth.spinningFrictionMuSp * g * travel, atImpact, 1e-12)
            assertTrue(abs(atImpact) < previous && atImpact * atStrike > 0.0)
            previous = abs(atImpact)
        }
    }

    @Test
    fun aCushionNeverAddsEnergyOrNormalSpeed() {
        for (speed in listOf(0.5, 2.0)) {
            for (angle in listOf(0.0, 20.0, 45.0, 70.0, 85.0)) {
                for (k in listOf(-1.0, -0.5, 0.0, 0.5, 1.0, 1.3)) {
                    for (side in listOf(-0.4, 0.0, 0.4)) {
                        val s = arriving(speed, angle, k, side)
                        val out = CushionContact.rebound(s, inward, cloth)
                        assertTrue(
                            out.kineticEnergyPerKg() <= s.kineticEnergyPerKg() + 1e-12,
                            "energy rose at $angle°, k $k, side $side",
                        )
                        assertTrue(out.velocity.dot(inward) <= speed * cos(angle * PI / 180.0) + 1e-12)
                        assertTrue(out.velocity.dot(inward) >= 0.0)
                    }
                }
            }
        }
    }

    @Test
    fun threeRailPathStaysFiniteAndOnTheTable() {
        val cueBall = Ball(0, Vec2(0.70, 0.40))
        val objectBall = Ball(1, Vec2(1.20, 0.55))
        val aim = GhostBallAiming.computeAimGeometry(cueBall, objectBall, table.pocketById(PocketId.SIDE_RIGHT), table = table)
        for (tip in listOf(TipOffset(0.3, 0.3), TipOffset(-0.3, 0.3), TipOffset(0.3, -0.3), TipOffset(0.0, 0.0))) {
            val path = SpinPhysics.traceCueBallPathWithSpin(aim, 5.0, tip, table, maxBounces = 3)
            // A pocketed ball's path ends in the hole, behind the cushion line.
            val onTable = if (path.hitsScratchPocket != null) path.sampledPoints.dropLast(1) else path.sampledPoints
            for (p in onTable) {
                assertTrue(p.x.isFinite() && p.y.isFinite())
                assertTrue(p.x in -1e-9..table.lengthMeters + 1e-9 && p.y in -1e-9..table.widthMeters + 1e-9)
            }
            assertTrue(path.cushionContacts <= 3)
            if (path.hitsScratchPocket == null) assertTrue(table.isWithinPlayableBounds(path.points.last()))
        }
    }

    @Test
    fun ballBallThrowGearsSideSpinOntoBothBalls() {
        val r = table.ballRadiusMeters
        val n = Vec2(1.0, 0.0)
        val v = 1.0
        val incoming = BallState(Vec2.ZERO, n.rotate(-PI / 6.0) * v)
        val struck = BallState(Vec2(2.0 * r, 0.0))
        val out = BallBallContact.resolve(incoming, struck, n)
        val jt = -out.struck.velocity.dot(n.rotate(PI / 2.0))
        assertTrue(abs(jt) > 1e-4, "a half-ball stun throws")
        // Both balls turn the same way about the vertical axis, by 5/2 of the sideways impulse per unit mass.
        assertEquals(2.5 * jt, out.struck.sideSpinSurfaceSpeedMps, 1e-12)
        assertEquals(2.5 * jt, out.striking.sideSpinSurfaceSpeedMps, 1e-12)
        // That reduces the surfaces' sliding at the contact by 7 jt and takes energy, never adds it.
        val slideBefore = (incoming.velocity - struck.velocity).dot(n.rotate(PI / 2.0))
        val slideAfter = (out.striking.velocity - out.struck.velocity).dot(n.rotate(PI / 2.0)) +
            out.striking.sideSpinSurfaceSpeedMps + out.struck.sideSpinSurfaceSpeedMps
        assertEquals(slideBefore + 7.0 * jt, slideAfter, 1e-12)
        assertTrue(abs(slideAfter) < abs(slideBefore))
        val before = incoming.kineticEnergyPerKg() + struck.kineticEnergyPerKg()
        val after = out.striking.kineticEnergyPerKg() + out.struck.kineticEnergyPerKg()
        assertTrue(after <= before)
        // A cushion nose height outside the allowed range is held to it.
        val low = CushionContact.contactTiltSin(ClothParams(cushionNoseHeightRatio = 0.4))
        assertEquals(2.0 * ClothParams.MIN_CUSHION_NOSE_HEIGHT_RATIO - 1.0, low, 1e-12)
        assertTrue(sqrt(1.0 - low * low) > 0.9)
    }
}
