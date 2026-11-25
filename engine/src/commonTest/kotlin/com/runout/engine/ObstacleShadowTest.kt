package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.planner.ExecutionErrorModel
import com.runout.engine.planner.HeatZoneGenerator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A ball beside the object ball's path to the pocket, or beside the cue ball's path to the object ball, takes away the
 * cue lines that would make the two balls touch. The make probability is checked against a direct numerical
 * integration of the same forward map: for each cue-line angle, the object ball's direction from the ghost-ball
 * contact, whether it lies in the entry window, and whether the two swept paths clear every other ball.
 */
class ObstacleShadowTest {
    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters
    private val footLeft: PocketSpec = table.pocketById(PocketId.FOOT_LEFT)
    private val profile = PlayerProfile()
    private val nominalTolerance = PocketGeometry.jawTolerance(footLeft, PocketGeometry.NOMINAL_ARRIVAL_SPEED_MPS)

    /**
     * Probability mass, under a normal cue-line error of [sigma], of the cue lines that pot the object ball of [aim] into
     * [pocket] at [tolerance] without either ball touching one of [obstacles]; integrated on 16,001 points over ±8 sigma.
     */
    private fun forwardMap(
        aim: AimGeometry,
        pocket: PocketSpec,
        tolerance: Double,
        sigma: Double,
        obstacles: List<Ball>,
        radius: Double = r,
    ): Double {
        val window = PocketGeometry.entryWindow(aim.objectBallPos, pocket, radius, tolerance)
        if (!window.isOpen) return 0.0
        val turn = window.limitA.cross(window.limitB)
        var made = 0.0
        var all = 0.0
        val n = 16_001
        for (i in 0 until n) {
            val z = -8.0 + 16.0 * i / (n - 1)
            val w = exp(-0.5 * z * z)
            all += w
            val line = aim.aimDirection.rotate(z * sigma)
            val (direction, contact) =
                ExecutionErrorModel.objectBallDirectionForCueLine(aim.cueBallPos, aim.objectBallPos, line, 2.0 * radius) ?: continue
            if (window.limitA.cross(direction) * turn < 0.0 || direction.cross(window.limitB) * turn < 0.0) continue
            val run = PocketGeometry.distanceToCaptureLine(aim.objectBallPos, direction, pocket, radius) ?: continue
            if (!GhostBallAiming.isSegmentClear(aim.objectBallPos, aim.objectBallPos + direction * run, radius, obstacles, 0.0)) continue
            if (!GhostBallAiming.isSegmentClear(aim.cueBallPos, contact, radius, obstacles, 0.0)) continue
            made += w
        }
        return made / all
    }

    /**
     * A pot into the foot-left corner along its axis: object ball 1.17 m from the mouth, cue ball [cueToGhost] metres
     * behind the ghost ball, straight in.
     */
    private fun longStraightPot(cueToGhost: Double = 0.5): Triple<Ball, Ball, Vec2> {
        val axis = Vec2(1.0, -1.0).normalized()
        val objectPos = footLeft.mouthCenter - axis * 1.17
        val direction = (PocketGeometry.aimPoint(objectPos, footLeft, r) - objectPos).normalized()
        val cuePos = objectPos - direction * (2.0 * r + cueToGhost)
        return Triple(Ball(0, cuePos, r), Ball(1, objectPos, r), direction)
    }

    private fun quality(cue: Ball, objectBall: Ball, others: List<Ball>): Pair<Double, AimGeometry> {
        val balls = listOf(cue, objectBall) + others
        val aim = GhostBallAiming.computeAimGeometry(cue, objectBall, footLeft, balls, table = table)
        return HeatZoneGenerator.shotQuality(aim, footLeft, profile.aimErrorStdRad, balls) to aim
    }

    @Test
    fun aBallBesideTheLongPotLineTakesAwayTheCueLinesThatWouldClipIt() {
        val (cue, objectBall, direction) = longStraightPot()
        val side = Vec2(-direction.y, direction.x)
        val (clear, clearAim) = quality(cue, objectBall, emptyList())
        assertTrue(abs(clear - 0.86) <= 0.02, "Precondition: the unobstructed pot is about 0.86 ($clear)")
        assertTrue(abs(clear - forwardMap(clearAim, footLeft, nominalTolerance, profile.aimErrorStdRad, emptyList())) <= 0.01)
        // Reference values of a numerical simulation of the cue-line distribution for this layout.
        val reference = mapOf(0.001 to 0.47, 0.005 to 0.59, 0.015 to 0.81, 0.030 to 0.86)
        for ((gap, expected) in reference) {
            val neighbour = Ball(5, objectBall.position + direction * 0.55 + side * (2.0 * r + gap), r)
            val (value, aim) = quality(cue, objectBall, listOf(neighbour))
            assertTrue(aim.isShotPlayable, "Precondition: the centre line is clear at a gap of ${gap * 1000} mm")
            val simulated = forwardMap(aim, footLeft, nominalTolerance, profile.aimErrorStdRad, listOf(neighbour))
            println("shadow: gap ${gap * 1000} mm: make probability ${PlannerScenarios.f2(value)}, simulated ${PlannerScenarios.f2(simulated)}")
            assertTrue(abs(value - expected) <= 0.08, "gap ${gap * 1000} mm: $value, reference $expected")
            assertTrue(abs(value - simulated) <= 0.01, "gap ${gap * 1000} mm: $value, simulated $simulated")
        }
    }

    @Test
    fun theSameBallOnTheOtherSideCastsTheSameShadow() {
        val (cue, objectBall, direction) = longStraightPot()
        val side = Vec2(-direction.y, direction.x)
        val (clear, _) = quality(cue, objectBall, emptyList())
        for (gap in listOf(0.001, 0.005)) {
            val left = quality(cue, objectBall, listOf(Ball(5, objectBall.position + direction * 0.55 + side * (2 * r + gap), r)))
            val right = quality(cue, objectBall, listOf(Ball(5, objectBall.position + direction * 0.55 - side * (2 * r + gap), r)))
            val simulatedRight = forwardMap(right.second, footLeft, nominalTolerance, profile.aimErrorStdRad, listOf(Ball(5, objectBall.position + direction * 0.55 - side * (2 * r + gap), r)))
            assertTrue(right.first < clear - 0.1, "right side, gap $gap: ${right.first} vs clear $clear")
            assertTrue(abs(right.first - simulatedRight) <= 0.01, "right side, gap $gap: ${right.first} vs simulated $simulatedRight")
            assertEquals(left.first, right.first, 0.02, "A straight pot on the pocket's axis is symmetric")
        }
    }

    @Test
    fun aBallPastTheCaptureLineOrBehindTheObjectBallChangesNothing() {
        val (cue, objectBall, direction) = longStraightPot()
        val (clear, _) = quality(cue, objectBall, emptyList())
        // Deep in the pocket, more than two radii past the mouth line: the object ball has dropped before it could touch it.
        val inPocket = Ball(5, footLeft.mouthCenter + footLeft.axisIntoPocket * (2.0 * r + 0.002), r)
        assertEquals(clear, quality(cue, objectBall, listOf(inPocket)).first, 1e-12)
        // Just behind the cue ball (it moves away from it), and far up the table.
        val behind = Ball(6, cue.position - direction * (2.0 * r + 0.001), r)
        val far = Ball(7, Vec2(0.3, 1.0), r)
        assertEquals(clear, quality(cue, objectBall, listOf(behind, far)).first, 1e-12)
    }

    @Test
    fun aBallAcrossTheWholeWindowLeavesNothing() {
        val (cue, objectBall, direction) = longStraightPot()
        val blocker = Ball(5, objectBall.position + direction * 0.40, r)
        val aim = GhostBallAiming.computeAimGeometry(cue, objectBall, footLeft, table = table)
        val value = ExecutionErrorModel.directionalMakeProbability(
            aim, footLeft, r, r, nominalTolerance, profile.aimErrorStdRad, listOf(cue, objectBall, blocker),
        )
        assertEquals(0.0, value)
    }

    @Test
    fun aBallBesideTheCueBallsPathTakesAwayTheLinesOnWhichTheCueBallClipsIt() {
        // The cue lines that pot the ball lie within about a milliradian of the aim line, so only a ball very close to the
        // cue ball's path, far from the cue ball, can be clipped by one of them: 0.3 mm outside the corridor, 0.9 m out.
        val (cue, objectBall, direction) = longStraightPot(cueToGhost = 1.0)
        val side = Vec2(-direction.y, direction.x)
        val (clear, _) = quality(cue, objectBall, emptyList())
        val neighbour = Ball(5, cue.position + direction * 0.9 + side * (2.0 * r + 0.0003), r)
        val (value, aim) = quality(cue, objectBall, listOf(neighbour))
        val simulated = forwardMap(aim, footLeft, nominalTolerance, profile.aimErrorStdRad, listOf(neighbour))
        println("shadow: cue-ball side: clear ${PlannerScenarios.f2(clear)}, with neighbour ${PlannerScenarios.f2(value)}, simulated ${PlannerScenarios.f2(simulated)}")
        assertTrue(value < clear - 0.05, "The neighbour costs cue lines: $value vs $clear")
        assertTrue(abs(value - simulated) <= 0.01, "$value vs simulated $simulated")
    }

    @Test
    fun theFirstOrderTurnOfTheObjectBallIsDistanceOverTwoRadiiTimesCosCut() {
        val pocket = footLeft
        for (cutDeg in listOf(0.0, 30.0, 60.0)) {
            val objectPos = Vec2(1.6, 0.5)
            val toPocket = (pocket.mouthCenter - objectPos).normalized()
            val ghost = objectPos - toPocket * (2.0 * r)
            val d = 0.8
            val cuePos = ghost - toPocket.rotate(cutDeg * PI / 180.0) * d
            val aim = GhostBallAiming.computeAimGeometryToPoint(Ball(0, cuePos, r), Ball(1, objectPos, r), pocket.mouthCenter)
            val delta = 1e-6
            val turned = assertNotNull(
                ExecutionErrorModel.objectBallDirectionForCueLine(cuePos, objectPos, aim.aimDirection.rotate(delta), 2.0 * r),
            ).first
            val slope = aim.objectBallDirection.angleTo(turned) / delta
            val expected = -d / (2.0 * r * cos(cutDeg * PI / 180.0))
            assertEquals(expected, slope, abs(expected) * 0.01, "cut $cutDeg°")
        }
    }

    /**
     * Random pots with up to four balls placed near the two paths, on every pocket of the table, at random aim errors and
     * jaw tolerances: the closed form agrees with the numerical integration of the same forward map to 0.01.
     */
    @Test
    fun matchesNumericalIntegrationOfTheForwardMapOnRandomLayouts() {
        val random = Random(20261124)
        var compared = 0
        var shadowed = 0
        var worst = 0.0
        while (compared < 160) {
            val pocket = table.pockets[random.nextInt(table.pockets.size)]
            val objectPos = Vec2(random.nextDouble(0.15, table.lengthMeters - 0.15), random.nextDouble(0.12, table.widthMeters - 0.12))
            val cuePos = Vec2(random.nextDouble(0.1, table.lengthMeters - 0.1), random.nextDouble(0.1, table.widthMeters - 0.1))
            if (cuePos.distanceTo(objectPos) < 0.2) continue
            val cue = Ball(0, cuePos, r)
            val objectBall = Ball(1, objectPos, r)
            val aim = GhostBallAiming.computeAimGeometry(cue, objectBall, pocket, table = table)
            if (!aim.isCutPossible) continue
            val others = ArrayList<Ball>()
            repeat(random.nextInt(5)) { k ->
                // Near the object ball's path or the cue ball's path, a few millimetres to a few centimetres off it.
                val onObjectPath = random.nextBoolean()
                val from = if (onObjectPath) objectPos else cuePos
                val to = if (onObjectPath) aim.pocketAimPos else aim.ghostBallCenter
                val along = from + (to - from) * random.nextDouble(0.15, 0.9)
                val normal = (to - from).normalized().let { Vec2(-it.y, it.x) }
                val offset = (2.0 * r + random.nextDouble(0.0005, 0.04)) * (if (random.nextBoolean()) 1.0 else -1.0)
                val position = along + normal * offset
                if ((others + cue + objectBall).none { it.position.distanceTo(position) < 2.0 * r + 1e-4 }) {
                    others += Ball(2 + k, position, r)
                }
            }
            val balls = listOf(cue, objectBall) + others
            val placed = GhostBallAiming.computeAimGeometry(cue, objectBall, pocket, balls, table = table)
            if (!placed.isShotPlayable) continue
            val sigma = random.nextDouble(0.0015, 0.006)
            val lenient = minOf(pocket.jawToleranceSlow, pocket.jawToleranceFast)
            val strict = maxOf(pocket.jawToleranceSlow, pocket.jawToleranceFast)
            val tolerance = random.nextDouble(lenient, strict + 1e-6)
            val closed = ExecutionErrorModel.directionalMakeProbability(placed, pocket, r, r, tolerance, sigma, balls)
            val numeric = forwardMap(placed, pocket, tolerance, sigma, others)
            val free = ExecutionErrorModel.directionalMakeProbability(placed, pocket, r, r, tolerance, sigma)
            if (free - closed > 0.01) shadowed++
            worst = maxOf(worst, abs(closed - numeric))
            assertTrue(abs(closed - numeric) <= 0.01, "case $compared, $pocket: closed form $closed, numerical $numeric, without balls $free")
            compared++
        }
        println("shadow: $compared random pots, $shadowed with a shadow, worst difference ${worst}")
        assertTrue(shadowed >= 20, "Enough of the random pots have a ball that matters ($shadowed)")
    }

    @Test
    fun theOtherBallsDoNotChangeAPotWhenNoneIsNear() {
        val (cue, objectBall, _) = longStraightPot()
        val layout = TableLayout(table, listOf(cue, objectBall, Ball(9, Vec2(0.4, 0.3), r), Ball(10, Vec2(0.6, 1.1), r)))
        val aim = GhostBallAiming.computeAimGeometry(cue, objectBall, footLeft, layout.balls, table = table)
        val without = ExecutionErrorModel.directionalMakeProbability(aim, footLeft, r, r, nominalTolerance, profile.aimErrorStdRad)
        val with = ExecutionErrorModel.directionalMakeProbability(aim, footLeft, r, r, nominalTolerance, profile.aimErrorStdRad, layout.balls)
        assertEquals(without, with, 0.0)
    }
}
