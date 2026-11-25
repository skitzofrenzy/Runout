package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.ExecutionErrorModel
import com.runout.engine.planner.HeatZoneGenerator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The make probability is the normal probability mass of the cue-line error that keeps the object ball inside the
 * pocket's entry window. These tests pin its shape (monotonic in everything that should make a pot harder) and check
 * that the percentages for the three skill profiles look like pool.
 */
class MakeProbabilityTest {
    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters
    private val footLeft: PocketSpec = table.pocketById(PocketId.FOOT_LEFT)
    private val sideLeft: PocketSpec = table.pocketById(PocketId.SIDE_LEFT)

    private val beginner = PlannerScenarios.beginner
    private val intermediate = PlannerScenarios.intermediate
    private val advanced = PlannerScenarios.advanced

    /**
     * A pot into [pocket] with the object ball [objectToMouth] metres from the mouth along [approach] (a unit vector
     * pointing into the pocket), the cue ball [cueToGhost] metres from the ghost ball and the cut [cutDeg] degrees.
     */
    private fun shot(
        pocket: PocketSpec,
        approach: Vec2,
        objectToMouth: Double,
        cueToGhost: Double,
        cutDeg: Double,
    ): Pair<AimGeometry, Ball> {
        val objectPos = pocket.mouthCenter - approach * objectToMouth
        val objectBall = Ball(1, objectPos, r)
        val aimPoint = PocketGeometry.aimPoint(objectPos, pocket, r)
        val objectDir = (aimPoint - objectPos).normalized()
        val ghost = objectPos - objectDir * (2.0 * r)
        val cuePos = ghost - objectDir.rotate(cutDeg * PI / 180.0) * cueToGhost
        val aim = GhostBallAiming.computeAimGeometry(Ball(0, cuePos, r), objectBall, pocket, table = table)
        return aim to objectBall
    }

    private val cornerAxis = Vec2(1.0, -1.0).normalized()

    private fun cornerShot(objectToMouth: Double, cueToGhost: Double, cutDeg: Double) =
        shot(footLeft, cornerAxis, objectToMouth, cueToGhost, cutDeg)

    private fun p(shot: Pair<AimGeometry, Ball>, pocket: PocketSpec, profile: PlayerProfile, speed: Double = 2.0): Double =
        ExecutionErrorModel.makeProbability(table, shot.second, r, pocket, shot.first, speed, TipOffset(), profile)

    private fun sideApproach(degreesOffRail: Double): Vec2 {
        val a = degreesOffRail * PI / 180.0
        return Vec2(kotlin.math.cos(a), -kotlin.math.sin(a))
    }

    @Test
    fun printsTheAnchorTable() {
        for ((cue, obj) in listOf(1.0 to 1.0, 1.2 to 1.2, 0.5 to 0.5)) {
            println(
                "anchor: drill straight-in $cue/$obj (slow tolerance): beginner ${PlannerScenarios.f2(drill(cue, obj, beginner))}, " +
                    "intermediate ${PlannerScenarios.f2(drill(cue, obj, intermediate))}, advanced ${PlannerScenarios.f2(drill(cue, obj, advanced))}",
            )
        }
        val shots = listOf(
            "corner straight 0.5/0.5" to (cornerShot(0.5, 0.5, 0.0) to footLeft),
            "corner straight 1.0/1.0" to (cornerShot(1.0, 1.0, 0.0) to footLeft),
            "corner 30° cut 1.0/1.0" to (cornerShot(1.0, 1.0, 30.0) to footLeft),
            "corner 60° cut 1.0/1.5" to (cornerShot(1.0, 1.5, 60.0) to footLeft),
            "side straight 0.5/0.5" to (shot(sideLeft, sideApproach(90.0), 0.5, 0.5, 0.0) to sideLeft),
            "side 30° off rail 0.4/0.4" to (shot(sideLeft, sideApproach(30.0), 0.4, 0.4, 10.0) to sideLeft),
        )
        for ((name, s) in shots) {
            val (geometry, pocket) = s
            println(
                "anchor: $name: beginner ${PlannerScenarios.f2(p(geometry, pocket, beginner))}, " +
                    "intermediate ${PlannerScenarios.f2(p(geometry, pocket, intermediate))}, " +
                    "advanced ${PlannerScenarios.f2(p(geometry, pocket, advanced))}",
            )
        }
    }

    /**
     * Straight-in pot into the foot-left corner with [cueToObject] metres between the ball centres and [objectToMouth]
     * metres from the object ball to the mouth, at the corner's slow-ball jaw tolerance (a pocket-speed stroke).
     */
    private fun drill(cueToObject: Double, objectToMouth: Double, profile: PlayerProfile): Double {
        val (aim, _) = cornerShot(objectToMouth, cueToObject - 2.0 * r, 0.0)
        return ExecutionErrorModel.directionalMakeProbability(aim, footLeft, r, r, footLeft.jawToleranceSlow, profile.aimErrorStdRad)
    }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double, what: String) =
        assertTrue(abs(actual - expected) <= tolerance, "$what: expected about $expected, was $actual")

    // The default sizes are anchored to these drill percentages (estimates, not measurements); see PlayerProfile.

    @Test
    fun halfTableStraightInMatchesTheDrillAnchors() {
        assertNear(1.0 / 3.0, drill(1.0, 1.0, beginner), 0.08, "beginner 1 m / 1 m")
        assertNear(2.0 / 3.0, drill(1.0, 1.0, intermediate), 0.08, "intermediate 1 m / 1 m")
        assertNear(0.90, drill(1.0, 1.0, advanced), 0.08, "advanced 1 m / 1 m")
    }

    @Test
    fun longStraightInDrillMatchesTheDrillAnchors() {
        assertNear(0.20, drill(1.2, 1.2, beginner), 0.08, "beginner 1.2 m / 1.2 m")
        assertNear(0.50, drill(1.2, 1.2, intermediate), 0.08, "intermediate 1.2 m / 1.2 m")
        assertNear(0.70, drill(1.2, 1.2, advanced), 0.08, "advanced 1.2 m / 1.2 m")
    }

    @Test
    fun shortStraightInFollowsFromTheAnchors() {
        assertTrue(drill(0.5, 0.5, beginner) >= 0.85, "beginner 0.5 m / 0.5 m: ${drill(0.5, 0.5, beginner)}")
        assertTrue(drill(0.5, 0.5, intermediate) >= 0.98, "intermediate 0.5 m / 0.5 m: ${drill(0.5, 0.5, intermediate)}")
        assertTrue(drill(0.5, 0.5, advanced) >= 0.995, "advanced 0.5 m / 0.5 m: ${drill(0.5, 0.5, advanced)}")
    }

    @Test
    fun aLongThinCutIsFarHarderThanAShortStraightIn() {
        val straight = cornerShot(0.5, 0.5, 0.0)
        val thinLong = cornerShot(1.0, 1.5, 60.0)
        for (profile in listOf(beginner, intermediate, advanced)) {
            assertTrue(p(thinLong, footLeft, profile) < 0.5 * p(straight, footLeft, profile))
        }
    }

    @Test
    fun makeProbabilityFallsStrictlyWithCutDistancesAndPlayerError() {
        val cuts = listOf(0.0, 15.0, 30.0, 45.0, 60.0, 70.0).map { p(cornerShot(0.8, 0.8, it), footLeft, intermediate) }
        assertTrue(cuts.zipWithNext().all { (a, b) -> b < a }, "cut angle: $cuts")
        val cueDistances = listOf(0.3, 0.6, 0.9, 1.2, 1.5).map { p(cornerShot(0.8, it, 20.0), footLeft, intermediate) }
        assertTrue(cueDistances.zipWithNext().all { (a, b) -> b < a }, "cue ball to ghost: $cueDistances")
        val objectDistances = listOf(0.3, 0.6, 0.9, 1.2, 1.5).map { p(cornerShot(it, 0.8, 20.0), footLeft, intermediate) }
        assertTrue(objectDistances.zipWithNext().all { (a, b) -> b < a }, "object ball to pocket: $objectDistances")
        val errors = listOf(0.002, 0.004, 0.006, 0.008, 0.012).map {
            p(cornerShot(0.8, 0.8, 20.0), footLeft, PlayerProfile(aimErrorStdRad = it))
        }
        assertTrue(errors.zipWithNext().all { (a, b) -> b < a }, "aim error: $errors")
    }

    @Test
    fun heatZoneOneShotAndSearchUseTheSameDirectionalProbability() {
        val s = cornerShot(0.8, 0.8, 25.0)
        val sigma = intermediate.aimErrorStdRad
        val direct = ExecutionErrorModel.directionalMakeProbability(
            s.first, footLeft, r, r, PocketGeometry.jawTolerance(footLeft, PocketGeometry.NOMINAL_ARRIVAL_SPEED_MPS), sigma,
        )
        assertEquals(direct, HeatZoneGenerator.shotQuality(s.first, footLeft, sigma), 1e-12)
    }

    @Test
    fun anOffCentreAimIsIntegratedOverTheLopsidedWindow() {
        // Aim at the near jaw side of the window: the mass falls off on one side only, so it is below the centred value.
        val (aim, objectBall) = cornerShot(0.8, 0.8, 0.0)
        val window = PocketGeometry.entryWindow(objectBall.position, footLeft, r, 0.6)
        val offAim = GhostBallAiming.computeAimGeometryToPoint(
            Ball(0, aim.cueBallPos, r), objectBall,
            objectBall.position + window.limitA.rotate(0.3 * (window.bisector.angleTo(window.limitA))) * 0.8,
        )
        val centred = ExecutionErrorModel.directionalMakeProbability(aim, footLeft, r, r, 0.6, 0.004)
        val off = ExecutionErrorModel.directionalMakeProbability(offAim, footLeft, r, r, 0.6, 0.004)
        assertTrue(off < centred, "off-centre $off must be below centred $centred")
        assertTrue(off > 0.0)
    }

    @Test
    fun sideSpinAddsAimError() {
        val (aim, _) = cornerShot(0.8, 0.8, 20.0)
        val plain = ExecutionErrorModel.cueLineErrorStdRad(intermediate, aim, 2.0, TipOffset(), table)
        val half = ExecutionErrorModel.cueLineErrorStdRad(intermediate, aim, 2.0, TipOffset(sideRatio = 0.16), table)
        val firm = ExecutionErrorModel.cueLineErrorStdRad(intermediate, aim, 2.0, TipOffset(sideRatio = 0.30), table)
        assertEquals(intermediate.aimErrorStdRad, plain, 1e-15)
        assertTrue(plain < half && half < firm, "$plain < $half < $firm")
    }
}
