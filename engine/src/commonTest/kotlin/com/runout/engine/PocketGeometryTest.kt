package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketKind
import com.runout.engine.model.PocketPreset
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.BallState
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.ExecutionErrorModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PocketGeometryTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters
    private val sideLeft = table.pocketById(PocketId.SIDE_LEFT)
    private val headLeft = table.pocketById(PocketId.HEAD_LEFT)

    private fun rad(deg: Double) = deg * PI / 180.0

    /** Ball [distance] from the middle of the side-left mouth, [deg] from the rail (90 = straight on). */
    private fun nearSide(distance: Double, deg: Double): Vec2 =
        sideLeft.mouthCenter + Vec2(cos(rad(deg)), sin(rad(deg))) * distance

    @Test
    fun pocketsAreCutIntoTheCushionsWithTheHoleBehindTheNoseLine() {
        for (spec in listOf(TableSpec.nineFootPool(), TableSpec.sevenFootPool(), TableSpec.fullSizeSnooker())) {
            for (pocket in spec.pockets) {
                val hole = pocket.holeCenter
                val outside = hole.x < 0.0 || hole.x > spec.lengthMeters || hole.y < 0.0 || hole.y > spec.widthMeters
                assertTrue(outside, "${spec.name} ${pocket.id}: hole $hole must lie behind the nose line")
                when (pocket.kind) {
                    PocketKind.CORNER -> {
                        val m = pocket.mouthWidthMeters
                        val corner = pocket.mouthCenter + pocket.axisIntoPocket * (m * 0.5)
                        assertEquals(m * 0.5, corner.distanceTo(pocket.mouthCenter), 1e-12)
                        assertTrue(spec.isWithinPlayableBounds(pocket.mouthCenter, 0.0))
                        assertEquals(m / sqrt(2.0), corner.distanceTo(pocket.jawTipA), 1e-12)
                        assertEquals(m / sqrt(2.0), corner.distanceTo(pocket.jawTipB), 1e-12)
                    }
                    PocketKind.SIDE -> assertEquals(0.0, abs(pocket.mouthCenter.y - (if (pocket.mouthCenter.y > 0.5) spec.widthMeters else 0.0)), 1e-12)
                }
            }
        }
        // WPA tournament cut on the 9 ft table: 4.5 in corners, 5 in sides.
        assertEquals(0.1143, headLeft.mouthWidthMeters, 1e-9)
        assertEquals(0.1270, sideLeft.mouthWidthMeters, 1e-9)
        assertEquals(rad(142.0), headLeft.facingAngleRad, 1e-12)
        assertEquals(rad(104.0), sideLeft.facingAngleRad, 1e-12)
        // A tighter cut is tighter.
        val proCut = TableSpec.defaultPockets(2.54, 1.27, r, PocketPreset.PRO_CUT)
        assertTrue(proCut.first { it.id == PocketId.HEAD_LEFT }.mouthWidthMeters < headLeft.mouthWidthMeters)
    }

    @Test
    fun sidePocketWindowStraightOnMatchesTheTangentFormula() {
        val m = sideLeft.mouthWidthMeters
        for (d in listOf(0.2, 0.5, 1.0, 2.0)) {
            val window = PocketGeometry.entryWindow(nearSide(d, 90.0), sideLeft, r)
            assertTrue(window.isOpen)
            val expected = atan((m / 2.0) / d) - asin(r / sqrt(d * d + m * m / 4.0))
            assertEquals(expected, window.halfAngleRadians, 1e-12)
            assertEquals(0.0, window.bisector.distanceTo(Vec2(0.0, -1.0)), 1e-12)
        }
    }

    @Test
    fun sidePocketNarrowsTowardTheRailAndClosesBeforeParallel() {
        val angles = (90 downTo 1).map { it.toDouble() }
        val halves = angles.map { PocketGeometry.entryWindow(nearSide(1.0, it), sideLeft, r).halfAngleRadians }
        for (i in 1 until halves.size) assertTrue(halves[i] <= halves[i - 1] + 1e-15, "at ${angles[i]}°")
        val closesAt = angles.first { !PocketGeometry.entryWindow(nearSide(1.0, it), sideLeft, r).isOpen }
        assertTrue(closesAt > 10.0, "a side pocket must close well before the rail, closed at $closesAt°")
        // From 1 m, a clean pot (clearance one ball radius) needs more than about 25° to the rail.
        assertTrue(PocketGeometry.entryWindow(nearSide(1.0, 30.0), sideLeft, r).isOpen)
        assertFalse(PocketGeometry.entryWindow(nearSide(1.0, 20.0), sideLeft, r).isOpen)
    }

    @Test
    fun cornerPocketIsSymmetricAndOpenAlongTheRail() {
        val diagonal = PocketGeometry.entryWindow(Vec2(0.7, 0.7), headLeft, r)
        assertTrue(diagonal.isOpen)
        assertEquals(0.0, diagonal.bisector.distanceTo(Vec2(-1.0, -1.0).normalized()), 1e-12)
        // Mirror positions about the diagonal give mirror windows.
        val a = PocketGeometry.entryWindow(Vec2(0.9, 0.4), headLeft, r)
        val b = PocketGeometry.entryWindow(Vec2(0.4, 0.9), headLeft, r)
        assertEquals(a.halfAngleRadians, b.halfAngleRadians, 1e-12)
        assertEquals(a.bisector.x, b.bisector.y, 1e-12)
        // Along the rail (centre at y = R) the corner is open, but narrower than straight down the diagonal.
        val alongRail = PocketGeometry.entryWindow(Vec2(1.0, r), headLeft, r)
        val at45 = PocketGeometry.entryWindow(Vec2(1.0, 0.0).rotate(rad(45.0)), headLeft, r)
        assertTrue(alongRail.isOpen)
        assertTrue(alongRail.halfAngleRadians < at45.halfAngleRadians)
    }

    @Test
    fun windowNeverWidensWithSpeedAndCornersForgiveSlowBallsMore() {
        for (pocket in table.pockets) {
            val from = pocket.mouthCenter - pocket.axisIntoPocket * 0.8
            var last = Double.MAX_VALUE
            for (speed in listOf(0.3, 1.0, 1.5, 2.5, 3.5, 4.0, 6.0)) {
                val half = ExecutionErrorModel.pocketAcceptanceHalfAngleRadians(from, pocket, r, speed)
                assertTrue(half <= last + 1e-15, "${pocket.id} at $speed m/s")
                last = half
            }
        }
        val slowCorner = PocketGeometry.jawTolerance(headLeft, 0.5)
        val slowSide = PocketGeometry.jawTolerance(sideLeft, 0.5)
        assertTrue(slowCorner < slowSide)
        assertTrue(slowCorner < 1.0 && PocketGeometry.jawTolerance(headLeft, 5.0) > 1.0)
        assertTrue(slowSide < 1.0 && PocketGeometry.jawTolerance(sideLeft, 5.0) > 1.0)
        // At a medium arrival speed the effective size of a straight-on pocket is near the mouth less about two
        // inches, the usual rule of thumb.
        for (pocket in listOf(headLeft, sideLeft)) {
            val effective = pocket.mouthWidthMeters - 2.0 * PocketGeometry.jawTolerance(pocket, 2.5) * r
            assertEquals(pocket.mouthWidthMeters - 0.0508, effective, 0.012)
        }
    }

    @Test
    fun aimPointSplitsTheAngularMarginAndStaysNearTheMiddleOfTheMouth() {
        assertEquals(0.0, PocketGeometry.aimPoint(nearSide(1.0, 90.0), sideLeft, r).distanceTo(sideLeft.mouthCenter), 1e-12)
        for (deg in listOf(80.0, 65.0, 50.0, 40.0, 30.0)) {
            val from = nearSide(1.0, deg)
            val window = PocketGeometry.entryWindow(from, sideLeft, r)
            val aim = PocketGeometry.aimPoint(from, sideLeft, r)
            assertEquals(0.0, aim.y, 1e-12, "the aim point lies on the mouth line")
            // Equal angular room on both sides of the line to the aim point, which is what aim error eats into.
            val toAim = (aim - from).normalized()
            assertEquals(abs(toAim.angleTo(window.limitA)), abs(toAim.angleTo(window.limitB)), 1e-9)
            assertEquals(window.halfAngleRadians, abs(toAim.angleTo(window.limitA)), 1e-9)
            // The near point is closer, so it needs a larger angle of clearance; seen obliquely the angular middle of the
            // mouth falls toward the near point. The two nearly cancel: the aim point stays within 1.5 mm of the middle.
            assertTrue(abs(aim.x - sideLeft.mouthCenter.x) < 0.0015, "at $deg degrees: ${aim.x}")
        }
        val diagonal = PocketGeometry.aimPoint(Vec2(0.7, 0.7), headLeft, r)
        assertEquals(0.0, diagonal.distanceTo(headLeft.mouthCenter), 1e-12)
    }

    @Test
    fun aShallowSidePocketPotIsNoLongerPlayable() {
        // A ball 3° off the long rail and 0.5 m from the side pocket: the old capture circle let it in.
        val objectBall = Ball(1, nearSide(0.5, 3.0) + Vec2(0.0, r), r)
        val cueBall = Ball(0, objectBall.position + Vec2(0.4, 0.05), r)
        val aim = GhostBallAiming.computeAimGeometry(cueBall, objectBall, sideLeft, table = table)
        assertFalse(aim.isCutPossible)
        assertFalse(PocketGeometry.entryWindow(objectBall.position, sideLeft, r, sideLeft.jawToleranceSlow).isOpen)
        // Straight on, the same pocket is a pot.
        val straight = Ball(1, nearSide(0.5, 90.0), r)
        val cue = Ball(0, straight.position + Vec2(0.0, 0.4), r)
        assertTrue(GhostBallAiming.computeAimGeometry(cue, straight, sideLeft, table = table).isShotPlayable)
    }

    @Test
    fun aRailRunnerHitsTheSidePocketJawWhileACentredBallDrops() {
        // Object ball struck straight along the long rail, drifting 1.5° into it, at speed: it reaches the cushion
        // line inside the side-pocket mouth without clearing the far point, and comes off the jaw.
        val start = Vec2(0.80, r + 0.012)
        val dir = Vec2(cos(rad(-1.5)), sin(rad(-1.5)))
        val ghost = start - dir * (2.0 * r)
        val aim = GhostBallAiming.computeAimGeometryToPoint(
            Ball(0, ghost - dir * 0.3, r), Ball(1, start, r), start + dir * 1.0,
        )
        val layout = TableLayout(table, listOf(Ball(0, ghost - dir * 0.3, r), Ball(1, start, r)))
        val sim = TableCollisionSimulator.simulatePostImpact(layout, layout.balls[1], aim, 4.0, TipOffset())
        assertNull(sim.targetBallTrajectory?.pocketedIn, "a rail runner at speed must not drop cleanly")
        val obPath = assertNotNull(sim.targetBallTrajectory).points
        val nearMouth = obPath.any { abs(it.x - sideLeft.mouthCenter.x) < sideLeft.mouthWidthMeters && it.y < 2.0 * r }
        assertTrue(nearMouth, "it reached the mouth: $obPath")

        // The same ball sent square into the middle of the mouth drops.
        val centred = Ball(1, nearSide(0.5, 90.0), r)
        val cue = Ball(0, centred.position + Vec2(0.0, 0.4), r)
        val square = GhostBallAiming.computeAimGeometry(cue, centred, sideLeft, table = table)
        val dropped = TableCollisionSimulator.simulatePostImpact(TableLayout(table, listOf(cue, centred)), centred, square, 2.5, TipOffset())
        assertEquals(PocketId.SIDE_LEFT, dropped.targetBallTrajectory?.pocketedIn)
        assertEquals(0.0, dropped.targetBallTrajectory!!.points.last().distanceTo(sideLeft.holeCenter), 1e-12)
    }

    @Test
    fun noCushionBounceInsideAMouthAndJawsTurnBallsBack() {
        // A ball rolling square into the side-pocket mouth meets no cushion: it drops.
        val pocketed = PocketGeometry.captureOnSegment(
            start = sideLeft.mouthCenter + Vec2(0.0, 0.3),
            end = sideLeft.mouthCenter - Vec2(0.0, 0.05),
            pockets = table.pockets,
            ballRadius = r,
            arrivalSpeedMps = 2.0,
        )
        assertEquals(PocketId.SIDE_LEFT, assertNotNull(pocketed).pocket.id)
        assertTrue(pocketed.potted)
        assertEquals(r, pocketed.point.y, 1e-12)
        // Sent at the point of the jaw it does not.
        val jaw = PocketGeometry.captureOnSegment(
            start = sideLeft.jawTipB + Vec2(-0.02, 0.3),
            end = sideLeft.jawTipB + Vec2(-0.02, -0.05),
            pockets = table.pockets,
            ballRadius = r,
            arrivalSpeedMps = 2.0,
        )
        assertFalse(assertNotNull(jaw).potted)
        // The jaw facing turns the ball back out toward the table and the mouth.
        val normal = PocketGeometry.facingNormal(sideLeft, sideLeft.jawTipB)
        assertTrue(normal.x < 0.0 && normal.y > 0.0)
        assertEquals(1.0, normal.length(), 1e-12)
    }

    @Test
    fun ballsAlreadyInAPocketAreFound() {
        assertTrue(PocketGeometry.containsRestingBall(headLeft.holeCenter, headLeft, r))
        assertTrue(PocketGeometry.containsRestingBall(sideLeft.mouthCenter, sideLeft, r))
        assertFalse(PocketGeometry.containsRestingBall(sideLeft.mouthCenter + Vec2(0.0, r), sideLeft, r))
        assertFalse(PocketGeometry.containsRestingBall(Vec2(1.0, 0.6), sideLeft, r))
        val stillOnCloth = BallState(Vec2(0.5, 0.5))
        assertTrue(table.pockets.none { PocketGeometry.containsRestingBall(stillOnCloth.position, it, r) })
    }
}
