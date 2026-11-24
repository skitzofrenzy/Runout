package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.BallMotion
import com.runout.engine.physics.BallState
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.CueBallTrajectory
import com.runout.engine.physics.CushionContact
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Balls whose centre lies on a cushion line (frozen to a cushion, or pushed against one by a contact): they strike the
 * cushion, or drop into a pocket mouth, the moment they move into it, they are not found striking it again, and a
 * ball whose slide presses it into the cushion runs along the cushion.
 */
class CushionLineContactTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters
    private val g = MotionPhysics.GRAVITY_M_S2
    private val motion = BallMotion(table.cloth)

    private fun trace(state: BallState, onTable: TableSpec = table, maxEvents: Int? = null, obstacles: List<Ball> = emptyList()): CueBallTrajectory =
        if (maxEvents == null) {
            TableCollisionSimulator.traceCueBall(onTable, state, r, CueBallSpinPreset.STUN, 0.0, 0.0, obstacles)
        } else {
            TableCollisionSimulator.traceCueBall(onTable, state, r, CueBallSpinPreset.STUN, 0.0, 0.0, obstacles, maxEvents = maxEvents)
        }

    /** Aim that sends [objectBall] off along [direction], struck from [cueDistance] behind its ghost ball, straight on. */
    private fun straightAim(objectBall: Ball, direction: Vec2, cueDistance: Double = 0.5): Pair<Ball, AimGeometry> {
        val d = direction.normalized()
        val cue = Ball(0, objectBall.position - d * (2.0 * r + cueDistance))
        val aim = GhostBallAiming.computeAimGeometryToPoint(cue, objectBall, objectBall.position + d, table = table)
        return cue to aim
    }

    @Test
    fun ballOnTheHeadCushionLineMovingIntoItBouncesOffIt() {
        // Before the fix this ball slid up the cushion and stopped pinned at (0.028575, 1.168).
        val start = BallState.rolling(Vec2(r, 0.6), Vec2(-1.0, 0.2).normalized())
        val path = trace(start, maxEvents = 12)
        assertFalse(path.truncated)
        assertEquals(1, path.cushionContacts)
        val end = path.points.last()
        assertTrue(end.x > 0.5, "the ball leaves the cushion, ends at $end")
        assertTrue(path.sampledPoints.drop(1).all { it.x > r }, "it is never pinned against the cushion")
    }

    @Test
    fun ballOnTheLeftCushionLineMovingIntoItBouncesBeforeTheSidePocket() {
        // Before the fix this ball ran along the cushion past the side pocket's mouth to (2.49, 0.028575).
        val path = trace(BallState.rolling(Vec2(0.8, r), Vec2(0.5, -0.5)), maxEvents = 12)
        assertFalse(path.truncated)
        assertEquals(1, path.cushionContacts)
        assertNull(path.hitsScratchPocket)
        assertTrue(path.points.last().y > 0.2, "the ball comes off the cushion, ends at ${path.points.last()}")
    }

    @Test
    fun railFrozenObjectBallSentAtTheSidePocketDropsThereOrComesOffTheCushion() {
        val side = table.pocketById(PocketId.SIDE_LEFT)
        for (x0 in listOf(0.4, 0.7, 1.0, 1.15, 1.20, 1.24, 1.30)) {
            val results = listOf(0.0, 1e-8).map { gap ->
                val ob = Ball(3, Vec2(x0, r + gap))
                val aimPoint = PocketGeometry.aimPoint(ob.position, side, r)
                val (cue, aim) = straightAim(ob, aimPoint - ob.position)
                TableCollisionSimulator.simulatePostImpact(TableLayout(table, listOf(cue, ob)), ob, aim, 2.0, TipOffset())
            }
            for (sim in results) {
                assertFalse(sim.truncated)
                val path = assertNotNull(sim.targetBallTrajectory)
                if (path.pocketedIn != PocketId.SIDE_LEFT) {
                    // Struck into its cushion it bounces off at once: never slides along it into another pocket.
                    val leftRail = path.points[1]
                    assertTrue(leftRail.y > r + 1e-3, "x0=$x0: the ball came off the cushion (second vertex $leftRail)")
                }
            }
            val (a, b) = results.map { assertNotNull(it.targetBallTrajectory) }
            assertEquals(a.pocketedIn, b.pocketedIn, "x0=$x0: same outcome for a frozen ball and one 1e-8 m off")
            assertTrue(a.points.last().distanceTo(b.points.last()) < 1e-3, "x0=$x0")
        }
        // On the capture line within the mouth, moving in: it drops.
        val inMouth = Ball(3, Vec2(1.24, r))
        val (cue, aim) = straightAim(inMouth, PocketGeometry.aimPoint(inMouth.position, side, r) - inMouth.position)
        val sim = TableCollisionSimulator.simulatePostImpact(TableLayout(table, listOf(cue, inMouth)), inMouth, aim, 2.0, TipOffset())
        assertEquals(PocketId.SIDE_LEFT, sim.targetBallTrajectory?.pocketedIn)
    }

    @Test
    fun frozenObjectBallStruckIntoItsCushionReboundsOffIt() {
        val ob = Ball(5, Vec2(1.6, r))
        val (cue, aim) = straightAim(ob, Vec2(1.0, 0.0).rotate(-PI / 6.0))
        val sim = TableCollisionSimulator.simulatePostImpact(TableLayout(table, listOf(cue, ob)), ob, aim, 2.0, TipOffset())
        assertFalse(sim.truncated)
        val path = assertNotNull(sim.targetBallTrajectory)
        val highest = path.sampledPoints.maxOf { it.y }
        assertTrue(highest > r + 0.05, "the struck ball rebounds off its cushion (highest y $highest)")
    }

    @Test
    fun smallSpeedsIntoACushionSettleInAFewEvents() {
        for (inward in listOf(5e-5, 2e-4)) {
            for (along in listOf(0.0, 0.5)) {
                val path = trace(BallState.rolling(Vec2(0.8, r), Vec2(along, -inward)), maxEvents = 6)
                assertFalse(path.truncated, "inward $inward along $along")
                val end = path.points.last()
                assertEquals(r, end.y, 1e-12, "it stays on the cushion line")
                // Along the cushion it rolls out its stopping distance v^2 / (2 muR g).
                val expected = 0.8 + MotionPhysics.rollingStoppingDistanceMeters(along, table.cloth.rollingResistanceMuR, g)
                assertEquals(expected, end.x, 1e-3, "inward $inward along $along")
                assertNull(path.hitsScratchPocket)
            }
        }
    }

    @Test
    fun aContactLeavesNoVelocityIntoTheCushion() {
        val inwardNormal = Vec2(0.0, 1.0)
        val random = Random(11)
        repeat(500) {
            val speed = random.nextDouble(1e-6, 3.0)
            val angle = random.nextDouble(-PI, 0.0)
            val v = Vec2(1.0, 0.0).rotate(angle) * speed
            val w = Vec2(random.nextDouble(-2.0, 2.0), random.nextDouble(-2.0, 2.0))
            val arriving = BallState(Vec2(1.0, r), v, w, random.nextDouble(-1.0, 1.0))
            val out = CushionContact.rebound(arriving, inwardNormal, table.cloth)
            assertTrue(out.velocity.y >= 0.0, "no velocity into the cushion after a contact: ${out.velocity}")
            assertTrue(out.kineticEnergyPerKg() <= arriving.kineticEnergyPerKg() + 1e-12)
        }
    }

    @Test
    fun ballRestingOnACushionLineAndMovingAlongOrAwayGetsNoContact() {
        val along = trace(BallState.rolling(Vec2(0.8, r), Vec2(0.5, 0.0)), maxEvents = 6)
        assertEquals(0, along.cushionContacts)
        assertEquals(0.8 + MotionPhysics.rollingStoppingDistanceMeters(0.5, table.cloth.rollingResistanceMuR, g), along.points.last().x, 1e-9)
        val away = trace(BallState.rolling(Vec2(0.8, r), Vec2(0.3, 0.4)), maxEvents = 6)
        assertEquals(0, away.cushionContacts)
        assertEquals(2, away.points.size, "a straight roll away from the cushion")
    }

    @Test
    fun ballPressedIntoACushionByItsSlideRunsAlongIt() {
        // Moving along the left cushion with spin that rolls it toward the cushion: the cloth pushes it into the cushion.
        val start = BallState(Vec2(0.3, r), Vec2(0.6, 0.0), Vec2(0.0, -0.5))
        val path = trace(start, maxEvents = 6)
        assertFalse(path.truncated, "no jitter against the cushion")
        assertEquals(0, path.cushionContacts)
        assertTrue(path.sampledPoints.all { abs(it.y - r) < 1e-12 }, "it runs along the cushion line")
        // The cushion takes the spin that drives it in; what is left is a stun slide along the cushion.
        val expected = 0.3 + MotionPhysics.objectBallStoppingDistanceMeters(0.6, table.cloth.slidingFrictionMuS, table.cloth.rollingResistanceMuR)
        assertEquals(expected, path.points.last().x, 1e-3)

        val held = CushionContact.pressedAgainst(start, Vec2(0.0, 1.0))
        assertEquals(0.0, held.slipVelocity.y, 0.0)
        assertEquals(0.0, motion.acceleration(held).y, 0.0)
        assertTrue(held.kineticEnergyPerKg() <= start.kineticEnergyPerKg())
        // Spin rolling it away from the cushion does not press it.
        val leaving = BallState(Vec2(0.3, r), Vec2(0.6, 0.0), Vec2(0.0, 0.5))
        assertEquals(leaving, CushionContact.pressedAgainst(leaving, Vec2(0.0, 1.0)))
        assertTrue(trace(leaving, maxEvents = 6).points.last().y > r + 0.01, "it curves off the cushion")
    }

    @Test
    fun ballFrozenInACornerOfTwoCushionsReboundsOffBoth() {
        val noPockets = table.copy(pockets = emptyList())
        val corner = BallState.rolling(Vec2(r, r), Vec2(-0.6, -0.8))
        val path = trace(corner, onTable = noPockets, maxEvents = 8)
        assertFalse(path.truncated)
        assertEquals(1, path.cushionContacts, "one contact with both cushions")
        val end = path.points.last()
        assertTrue(end.x > r + 0.05 && end.y > r + 0.05, "it comes out of the corner, ends at $end")

        // Struck into the corner by another ball.
        val ob = Ball(4, Vec2(r, r))
        val cueStart = BallState.rolling(Vec2(0.4, 0.4), Vec2(-1.0, -1.0).normalized() * 1.5)
        val struck = TableCollisionSimulator.traceCueBall(
            noPockets, cueStart, r, CueBallSpinPreset.STUN, 0.0, 0.0, listOf(ob), maxEvents = 40,
        )
        assertFalse(struck.truncated)
        assertTrue(struck.ballContacts >= 1)
    }

    @Test
    fun frozenPairOnACushionStruckFromBehind() {
        val outcomes = listOf(0.0, 1e-8).map { gap ->
            val a = Ball(1, Vec2(1.0, r + gap))
            val b = Ball(2, Vec2(1.0 + 2.0 * r, r + gap))
            val (cue, aim) = straightAim(a, Vec2(1.0, 0.0).rotate(-0.15), cueDistance = 0.4)
            val sim = TableCollisionSimulator.simulatePostImpact(TableLayout(table, listOf(cue, a, b)), a, aim, 2.5, TipOffset())
            assertFalse(sim.truncated)
            sim
        }
        for (sim in outcomes) {
            val b = assertNotNull(sim.disturbedBalls.firstOrNull { it.ballNumber == 2 }, "the front ball is driven on")
            assertTrue(b.sampledPoints.last().x > 1.0 + 2.0 * r + 0.1)
            val a = assertNotNull(sim.targetBallTrajectory)
            assertTrue(a.sampledPoints.maxOf { it.y } > r + 1e-3, "the struck ball comes off its cushion")
        }
        val (zero, offset) = outcomes
        for (ball in zero.resultingLayout.balls) {
            val other = assertNotNull(offset.resultingLayout.ballByNumber(ball.number))
            assertTrue(ball.position.distanceTo(other.position) < 1e-3, "ball ${ball.number}: gap 0 and 1e-8 agree")
        }
    }

    @Test
    fun railShotsAreMirrorSymmetricAndDoNotDependOnAMicronGap() {
        val random = Random(2026)
        val l = table.lengthMeters
        val w = table.widthMeters
        fun mirrorX(p: Vec2) = Vec2(l - p.x, p.y)
        fun mirrorY(p: Vec2) = Vec2(p.x, w - p.y)
        fun flipX(v: Vec2) = Vec2(-v.x, v.y)
        fun flipY(v: Vec2) = Vec2(v.x, -v.y)
        val mirrorPocketX = mapOf(
            PocketId.HEAD_LEFT to PocketId.FOOT_LEFT, PocketId.FOOT_LEFT to PocketId.HEAD_LEFT,
            PocketId.HEAD_RIGHT to PocketId.FOOT_RIGHT, PocketId.FOOT_RIGHT to PocketId.HEAD_RIGHT,
            PocketId.SIDE_LEFT to PocketId.SIDE_LEFT, PocketId.SIDE_RIGHT to PocketId.SIDE_RIGHT,
        )
        val mirrorPocketY = mapOf(
            PocketId.HEAD_LEFT to PocketId.HEAD_RIGHT, PocketId.HEAD_RIGHT to PocketId.HEAD_LEFT,
            PocketId.FOOT_LEFT to PocketId.FOOT_RIGHT, PocketId.FOOT_RIGHT to PocketId.FOOT_LEFT,
            PocketId.SIDE_LEFT to PocketId.SIDE_RIGHT, PocketId.SIDE_RIGHT to PocketId.SIDE_LEFT,
        )
        repeat(200) { shot ->
            val position = when (random.nextInt(4)) {
                0 -> Vec2(r, random.nextDouble(0.15, w - 0.15))
                1 -> Vec2(l - r, random.nextDouble(0.15, w - 0.15))
                2 -> Vec2(random.nextDouble(0.15, l - 0.15), r)
                else -> Vec2(random.nextDouble(0.15, l - 0.15), w - r)
            }
            val v = Vec2(1.0, 0.0).rotate(random.nextDouble(-PI, PI)) * random.nextDouble(0.2, 3.0)
            val spin = v * random.nextDouble(-0.6, 1.0)
            val side = random.nextDouble(-1.0, 1.0)
            val start = BallState(position, v, spin, side)
            val base = trace(start)
            assertFalse(base.truncated, "shot $shot")
            assertTrue(base.points.size < 30, "shot $shot: a handful of contacts")

            val x = trace(BallState(mirrorX(position), flipX(v), flipX(spin), -side))
            assertTrue(mirrorX(base.points.last()).distanceTo(x.points.last()) < 1e-6, "shot $shot mirrored in x")
            assertEquals(base.hitsScratchPocket?.let { mirrorPocketX.getValue(it) }, x.hitsScratchPocket, "shot $shot")
            val y = trace(BallState(mirrorY(position), flipY(v), flipY(spin), -side))
            assertTrue(mirrorY(base.points.last()).distanceTo(y.points.last()) < 1e-6, "shot $shot mirrored in y")
            assertEquals(base.hitsScratchPocket?.let { mirrorPocketY.getValue(it) }, y.hitsScratchPocket, "shot $shot")

            // A ball a hundredth of a micron off the cushion line behaves as one on it.
            val inward = Vec2(
                if (position.x == r) 1.0 else if (position.x == l - r) -1.0 else 0.0,
                if (position.y == r) 1.0 else if (position.y == w - r) -1.0 else 0.0,
            )
            val off = trace(start.copy(position = position + inward * 1e-8))
            assertEquals(base.hitsScratchPocket, off.hitsScratchPocket, "shot $shot")
            assertTrue(base.points.last().distanceTo(off.points.last()) < 1e-3, "shot $shot: gap 0 and 1e-8")
        }
    }
}
