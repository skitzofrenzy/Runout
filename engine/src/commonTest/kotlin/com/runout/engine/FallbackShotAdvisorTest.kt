package com.runout.engine

import com.runout.engine.PlannerScenarios.f2
import com.runout.engine.PlannerScenarios.layout
import com.runout.engine.PlannerScenarios.nineBall
import com.runout.engine.PlannerScenarios.r
import com.runout.engine.PlannerScenarios.solids
import com.runout.engine.PlannerScenarios.table
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.BallBallContact
import com.runout.engine.physics.BallState
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.PocketGeometry
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.FallbackAdvice
import com.runout.engine.planner.FallbackKind
import com.runout.engine.planner.FallbackShotAdvisor
import com.runout.engine.planner.MultiShotPlanner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class FallbackShotAdvisorTest {
    private val profile = PlayerProfile()

    private fun describe(advice: FallbackAdvice?): String = advice?.let {
        "${it.kind} balls=${it.targetBallNumbers} pocket=${it.pocketId} speed=${it.speedUnits} " +
            "P=${f2(it.outcomeProbability)} cushion=${it.cushionPoint} \"${it.reason}\""
    } ?: "no advice"

    /** Nine-ball table typed from a photo of a real table: the 2 sits behind the 3, which hangs in the foot-right corner. */
    private val photoNoPot: TableLayout = layout(
        0 to (1.9110 to 0.0905),
        2 to (2.3144 to 1.1407),
        3 to (2.4775 to 1.2216),
        4 to (2.0251 to 0.2145),
        5 to (2.4734 to 0.3367),
        6 to (2.3695 to 0.3696),
        7 to (1.7781 to 0.8171),
        8 to (0.5870 to 1.0030),
        9 to (1.6620 to 0.5606),
    )

    @Test
    fun photoTableWithNoDirectPotGetsTheTwoThreeCombination() {
        assertTrue(MultiShotPlanner.planRunout(photoNoPot, nineBall, profile, horizonShots = 3).isEmpty(), "Precondition: no direct pot")
        val mark = TimeSource.Monotonic.markNow()
        val advice = FallbackShotAdvisor.advise(photoNoPot, nineBall, profile)
        println("photo table: ${describe(advice)} in ${mark.elapsedNow().inWholeMilliseconds} ms")
        assertNotNull(advice)
        // The 3 hangs in the jaws straight beyond the 2: the combination is close to certain, far above any safety.
        assertEquals(FallbackKind.COMBINATION, advice.kind, describe(advice))
        assertEquals(listOf(2, 3), advice.targetBallNumbers)
        assertEquals(PocketId.FOOT_RIGHT, advice.pocketId)
        assertTrue(advice.outcomeProbability >= FallbackShotAdvisor.COMBINATION_MAKE_THRESHOLD, describe(advice))
        assertEquals(3, advice.lines.size)
    }

    @Test
    fun collinearCombinationAimsThroughTheFirstBallsCentreLine() {
        val pocket = table.pocketById(PocketId.FOOT_LEFT)
        val axis = Vec2(1.0, -1.0).normalized()
        val second = Ball(3, pocket.mouthCenter - axis * 0.30, r)
        val aimPoint = PocketGeometry.aimPoint(second.position, pocket, r)
        val line = (aimPoint - second.position).normalized()
        val first = Ball(1, second.position - line * 0.40, r)
        val cue = first.position - line * 0.50
        val geometry = assertNotNull(
            FallbackShotAdvisor.combinationGeometry(layout(0 to (cue.x to cue.y)), cue, r, first, second, pocket),
        )
        assertEquals(0.0, geometry.firstDirection.cross(line), 1e-9)
        assertEquals(0.0, geometry.secondCutRadians, 1e-9)
        assertEquals(0.0, geometry.firstCutRadians, 1e-9)
        // The cue ball aims along the line through the first ball's centre.
        assertEquals(0.0, (geometry.cueGhost - cue).normalized().cross(line), 1e-9)
        assertEquals(0.0, (first.position - cue).normalized().cross(line), 1e-9)
    }

    @Test
    fun offsetCombinationGhostPositionsMatchTheGhostBallConstruction() {
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val second = Ball(5, Vec2(2.20, 1.00), r)
        val first = Ball(2, Vec2(1.85, 0.95), r)
        val cue = Vec2(1.40, 0.70)
        val geometry = assertNotNull(
            FallbackShotAdvisor.combinationGeometry(layout(0 to (cue.x to cue.y)), cue, r, first, second, pocket),
        )
        val aim = PocketGeometry.aimPoint(second.position, pocket, r)
        val secondDir = (aim - second.position).normalized()
        val firstGhost = second.position - secondDir * (2.0 * r)
        val firstDir = (firstGhost - first.position).normalized()
        val cueGhost = first.position - firstDir * (2.0 * r)
        assertEquals(firstGhost.x, geometry.firstGhost.x, 1e-12)
        assertEquals(firstGhost.y, geometry.firstGhost.y, 1e-12)
        assertEquals(cueGhost.x, geometry.cueGhost.x, 1e-12)
        assertEquals(cueGhost.y, geometry.cueGhost.y, 1e-12)
        assertEquals(firstDir.angleTo(secondDir), geometry.secondCutRadians, 1e-12)
        assertTrue(abs(geometry.secondCutRadians) > 0.05, "An offset case: the second contact is a cut")
    }

    @Test
    fun eightBallNeverPlaysACombinationOntoAnOpponentsBall() {
        // The photo table as an 8-ball position: the shooter has solids and only the 2 is left; the ball in the jaws
        // beyond it is a stripe.
        fun eightBallTable(jawBall: Int) = layout(
            0 to (1.9110 to 0.0905),
            2 to (2.3144 to 1.1407),
            jawBall to (2.4775 to 1.2216),
            12 to (2.0251 to 0.2145),
            13 to (2.4734 to 0.3367),
            14 to (2.3695 to 0.3696),
            15 to (1.7781 to 0.8171),
            8 to (0.5870 to 1.0030),
            10 to (1.6620 to 0.5606),
        )
        val opponents = FallbackShotAdvisor.advise(eightBallTable(jawBall = 11), solids, profile)
        println("8-ball, stripe in the jaws: ${describe(opponents)}")
        assertTrue(opponents == null || 11 !in opponents.targetBallNumbers, describe(opponents))
        // The same table with the jaw ball one of the shooter's own (a solid) offers the combination.
        val own = assertNotNull(FallbackShotAdvisor.advise(eightBallTable(jawBall = 3), solids, profile))
        assertEquals(FallbackKind.COMBINATION, own.kind, describe(own))
        assertEquals(listOf(2, 3), own.targetBallNumbers)
    }

    @Test
    fun kickGoesAroundAWallOfBallsToMakeALegalHit() {
        val wall = listOf(2, 3, 4, 5, 6).mapIndexed { i, n -> n to (1.75 to 0.635 + (i - 2) * 0.06) }
        val kickTable = layout(*(listOf(0 to (0.60 to 0.635), 1 to (2.00 to 0.635), 9 to (0.40 to 1.10)) + wall).toTypedArray())
        val advice = assertNotNull(FallbackShotAdvisor.advise(kickTable, nineBall, profile), "A kick is offered")
        println("kick: ${describe(advice)}")
        assertEquals(FallbackKind.KICK, advice.kind, describe(advice))
        assertEquals(listOf(1), advice.targetBallNumbers)
        val cushion = assertNotNull(advice.cushionPoint)
        val onACushion = cushion.x <= r + 0.003 || cushion.x >= table.lengthMeters - r - 0.003 ||
            cushion.y <= r + 0.003 || cushion.y >= table.widthMeters - r - 0.003
        assertTrue(onACushion, "The cushion point lies on a cushion line: $cushion")
        // Both legs of the drawn path clear the wall.
        val blockers = kickTable.objectBalls().filter { it.number != 1 }
        for (line in advice.lines) {
            assertTrue(GhostBallAiming.isSegmentClear(line.from, line.to, r, blockers), "Leg ${line.from} -> ${line.to} clears the wall")
        }
        val contact = advice.lines.last().to
        assertEquals(2.0 * r, contact.distanceTo(Vec2(2.00, 0.635)), 2e-3)
        assertTrue(advice.outcomeProbability > 0.0)
    }

    @Test
    fun safetyLeavesTheNextBallHiddenFromEveryPocket() {
        // Ball 1 lies on the left long cushion with no pot from the cue ball; ball 9 is a single blocker in the middle.
        val safetyTable = layout(
            0 to (0.95 to 1.00),
            1 to (1.00 to r + 0.001),
            9 to (1.10 to 0.45),
        )
        assertTrue(MultiShotPlanner.planRunout(safetyTable, nineBall, profile, horizonShots = 1).isEmpty(), "Precondition: no direct pot")
        val advice = assertNotNull(FallbackShotAdvisor.advise(safetyTable, nineBall, profile))
        println("safety: ${describe(advice)} rest=${advice.cueBallRest}")
        assertEquals(FallbackKind.SAFETY, advice.kind, describe(advice))
        val after = assertNotNull(advice.resultingLayout)
        assertEquals(1.0, FallbackShotAdvisor.hiddenShare(after, nineBall), 1e-12, "Ball 1 is hidden for every pocket: ${describe(advice)}")
    }

    @Test
    fun nothingIsReturnedWhenADirectPotExists() {
        val open = layout(0 to (0.60 to 0.635), 1 to (1.20 to 0.40), 2 to (1.60 to 0.90), 9 to (2.00 to 0.50))
        assertTrue(MultiShotPlanner.planRunout(open, nineBall, profile).isNotEmpty())
        assertNull(FallbackShotAdvisor.advise(open, nineBall, profile))
    }

    // ---------------------------------------------------------------------------------------------
    // Combination probability model
    // ---------------------------------------------------------------------------------------------

    /** Cue ball, the 2 and the 3 for a 2-into-3 combination to the foot-right pocket; the cut at the first contact is large. */
    private val comboLayout: TableLayout = layout(
        0 to (1.40 to 0.40),
        2 to (1.85 to 0.75),
        3 to (2.25 to 0.95),
    )

    /** More tables, with larger cuts at one or both contacts, so that the throw is not small. */
    private val throwHeavyLayouts: List<TableLayout> = listOf(
        comboLayout,
        layout(0 to (0.89 to 0.94), 2 to (1.69 to 0.55), 3 to (2.06 to 0.61)),
        layout(0 to (1.53 to 0.85), 2 to (1.82 to 1.07), 3 to (2.25 to 1.05)),
        layout(0 to (1.32 to 0.41), 2 to (1.47 to 0.35), 3 to (2.04 to 0.52)),
    )

    private fun ballsOf(layout: TableLayout): Triple<Ball, Ball, Ball> = Triple(
        assertNotNull(layout.cueBall()),
        assertNotNull(layout.ballByNumber(2)),
        assertNotNull(layout.ballByNumber(3)),
    )

    @Test
    fun combinationProbabilityIsCentredOnTheIntendedSecondBallLineAtEverySpeed() {
        val (_, first, second) = ballsOf(comboLayout)
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val at = { speed: Double -> FallbackShotAdvisor.combinationProbabilityAt(comboLayout, profile, first, second, pocket, speed) }
        println("combination P by speed: 3.0 ${f2(at(3.0))}, 4.5 ${f2(at(4.5))}, 6.0 ${f2(at(6.0))}")

        // The error is measured around the line the player is told to play, so the window is centred on the intended
        // second-ball line at every speed and the mass is about 0.447 at the medium speed.
        assertEquals(0.447, at(4.5), 0.01)
        assertTrue(at(3.0) > 0.05, "Soft speed keeps its chance: ${at(3.0)}")
        assertTrue(at(6.0) > 0.05, "Firm speed keeps its chance: ${at(6.0)}")
    }

    @Test
    fun noCueLineErrorSendsTheSecondBallAlongTheIntendedLine() {
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        for ((index, layout) in throwHeavyLayouts.withIndex()) {
            val (cue, first, second) = ballsOf(layout)
            val geometry = assertNotNull(
                FallbackShotAdvisor.combinationGeometry(layout, cue.position, cue.radiusMeters, first, second, pocket),
                "layout $index has a combination",
            )
            val modelled = assertNotNull(FallbackShotAdvisor.modelledSecondDirection(cue, first, second, geometry, 0.0))
            val errorDegrees = abs(modelled.angleTo(geometry.secondDirection)) * 180.0 / PI
            println("layout $index: cuts ${f2(geometry.firstCutRadians * 180.0 / PI)} and ${f2(geometry.secondCutRadians * 180.0 / PI)} deg, offset-0 error $errorDegrees deg")
            assertTrue(errorDegrees < 0.1, "layout $index: modelled second-ball direction is $errorDegrees deg off the intended line")
        }
        assertTrue(
            throwHeavyLayouts.any { layout ->
                val (cue, first, second) = ballsOf(layout)
                val g = FallbackShotAdvisor.combinationGeometry(layout, cue.position, cue.radiusMeters, first, second, pocket)
                g != null && abs(g.firstCutRadians) > 0.35 && abs(g.secondCutRadians) > 0.35
            },
            "At least one fixture cuts both balls by more than 20 degrees",
        )
    }

    @Test
    fun aTinyAimErrorMakesTheCombinationNearlyCertain() {
        val steady = PlayerProfile(aimErrorStdRad = 1e-5)
        val (_, first, second) = ballsOf(comboLayout)
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val p = FallbackShotAdvisor.combinationProbabilityAt(comboLayout, steady, first, second, pocket, 4.5)
        println("combination P with a 0.00001 rad aim error: ${f2(p)}")
        assertTrue(p > 0.95, "A steady aim along the centred line pots: $p")
    }

    @Test
    fun theAdvisedCombinationLinesPotTheSecondBallWhenReplayed() {
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        for ((index, layout) in throwHeavyLayouts.withIndex()) {
            val (cue, first, second) = ballsOf(layout)
            val advice = FallbackShotAdvisor.combinationAdvice(layout, profile, first, second, pocket) ?: continue
            val cueGhost = advice.lines[0].to
            val firstGhost = advice.lines[1].to
            val speed = profile.speedForUnits(advice.speedUnits)
            val leg = cueGhost - cue.position
            val intended = (firstGhost - first.position).normalized()

            // The cue ball's contact with the first ball throws it along the line to the first ghost position.
            val pre = SpinPhysics.computePreImpactCueState(speed, TipOffset(), leg.length(), table)
            val velocity = leg.normalized() * pre.impactSpeedMps
            val arriving = BallState(cueGhost, velocity, velocity * pre.impactSpinFactorK)
            val struck = BallBallContact.resolve(arriving, BallState(first.position), (first.position - cueGhost).normalized()).struck
            val launchErrorDegrees = abs(struck.velocity.normalized().angleTo(intended)) * 180.0 / PI
            assertTrue(launchErrorDegrees < 0.2, "layout $index: first ball leaves $launchErrorDegrees deg off the advised line")

            // And the whole table simulation drops the second ball.
            val aim = AimGeometry(
                cueBallPos = cue.position,
                objectBallPos = first.position,
                pocketAimPos = firstGhost,
                ghostBallCenter = cueGhost,
                aimDirection = leg.normalized(),
                objectBallDirection = intended,
                cueToGhostDistanceMeters = leg.length(),
                objectToPocketDistanceMeters = first.position.distanceTo(firstGhost),
                cutAngleRadians = leg.normalized().angleTo(intended),
                isCutPossible = true,
                isCueToGhostClear = true,
                isObjectToPocketClear = true,
            )
            val simulation = TableCollisionSimulator.simulatePostImpact(layout, first, aim, speed, TipOffset())
            val dropped = simulation.disturbedBalls.firstOrNull { it.ballNumber == 3 }?.pocketedIn
            println("layout $index: advised speed ${advice.speedUnits}, P=${f2(advice.outcomeProbability)}, 3 drops in $dropped")
            assertEquals(pocket.id, dropped, "layout $index: the replayed combination pots the second ball")
        }
    }

    @Test
    fun aCombinationAlwaysNamesTwoBallsAndALoneBallGetsNone() {
        val lone = layout(0 to (0.60 to 0.635), 1 to (2.20 to 0.95))
        val advice = FallbackShotAdvisor.advise(lone, nineBall, profile)
        assertTrue(advice == null || (advice.kind != FallbackKind.COMBINATION && advice.targetBallNumbers.size == 1), describe(advice))

        val random = Random(5)
        var combinations = 0
        repeat(40) {
            val tableLayout = randomNineBallTable(random, wall = true)
            val a = FallbackShotAdvisor.advise(tableLayout, nineBall, profile) ?: return@repeat
            when (a.kind) {
                FallbackKind.COMBINATION -> {
                    combinations++
                    assertEquals(2, a.targetBallNumbers.size, describe(a))
                    assertTrue(a.reason.contains("#${a.targetBallNumbers[1]}") && !a.reason.contains("null"), a.reason)
                }
                else -> assertEquals(1, a.targetBallNumbers.size, describe(a))
            }
        }
        println("random tables with a wall: $combinations combinations")

        val photo = assertNotNull(FallbackShotAdvisor.advise(photoNoPot, nineBall, profile))
        assertEquals(FallbackKind.COMBINATION, photo.kind)
        assertEquals(2, photo.targetBallNumbers.size)
        assertTrue("#${photo.targetBallNumbers[1]}" in photo.reason && "null" !in photo.reason, photo.reason)
    }

    // ---------------------------------------------------------------------------------------------
    // Kick, direct-pot floor
    // ---------------------------------------------------------------------------------------------

    /** Cue ball on the left, the 1 on the right, a wall of five balls between them with some jitter, and the 9. */
    private fun randomNineBallTable(random: Random, wall: Boolean): TableLayout {
        val cue = Vec2(0.40 + 0.40 * random.nextDouble(), 0.30 + 0.70 * random.nextDouble())
        val one = Vec2(1.70 + 0.60 * random.nextDouble(), 0.30 + 0.70 * random.nextDouble())
        val balls = mutableListOf(0 to (cue.x to cue.y), 1 to (one.x to one.y), 9 to (0.35 + 0.1 * random.nextDouble() to 1.10))
        if (wall) {
            val wallX = 1.2 + 0.2 * random.nextDouble()
            val t = (wallX - cue.x) / (one.x - cue.x)
            val centreY = cue.y + t * (one.y - cue.y)
            for (i in 0 until 5) balls += (2 + i) to (wallX + 0.01 * random.nextDouble() to (centreY + (i - 2) * 0.06))
        }
        return layout(*balls.toTypedArray())
    }

    @Test
    fun anAdvisedKickReachesACushionAfterTheContact() {
        val random = Random(2026)
        var kicks = 0
        repeat(60) {
            val kickTable = randomNineBallTable(random, wall = true)
            val advice = FallbackShotAdvisor.advise(kickTable, nineBall, profile) ?: return@repeat
            if (advice.kind != FallbackKind.KICK) return@repeat
            kicks++
            val cue = assertNotNull(kickTable.cueBall())
            val target = assertNotNull(kickTable.ballByNumber(advice.targetBallNumbers.single()))
            val direction = (advice.lines[0].to - advice.lines[0].from).normalized()
            val path = TableCollisionSimulator.traceCueBall(
                table = table,
                cueState = BallState(cue.position, direction * profile.speedForUnits(advice.speedUnits)),
                cueRadius = cue.radiusMeters,
                spin = CueBallSpinPreset.STUN,
                initialDeflectionAngleRad = 0.0,
                rollingDeflectionAngleRad = 0.0,
                obstacleBalls = listOf(target),
                maxCushions = 1,
            )
            val contact = path.points.indexOfFirst { it.distanceTo(target.position) <= cue.radiusMeters + target.radiusMeters + 1e-3 }
            assertTrue(contact > 0, "The replayed kick meets ball #${target.number}")
            val onRail = { p: Vec2 ->
                p.x <= r + 2e-3 || p.x >= table.lengthMeters - r - 2e-3 || p.y <= r + 2e-3 || p.y >= table.widthMeters - r - 2e-3
            }
            assertTrue(path.points.drop(contact + 1).any(onRail), "After the contact the cue ball reaches a cushion: ${describe(advice)}")
        }
        println("random walled tables: $kicks kicks checked")
        assertTrue(kicks > 0, "The random tables produced no kick; the test proves nothing")
    }

    @Test
    fun aKickIsStillAdvisedWhenTheLegalBallIsInLineOfSightButNoSafetyIsFound() {
        // The 1 can be reached in a straight line, yet no safety stroke is legal; the advisor falls through to a kick.
        val tables = listOf(
            layout(0 to (0.67 to 0.97), 1 to (1.82 to 0.62), 2 to (2.19 to 0.94), 3 to (2.47 to 0.57), 4 to (2.55 to 1.26), 9 to (1.52 to 0.59)),
            layout(0 to (1.39 to 0.48), 1 to (2.52 to 0.83), 2 to (2.05 to 0.52), 3 to (1.32 to 1.26), 4 to (0.16 to 0.29), 9 to (0.75 to 0.66)),
        )
        for ((index, tableLayout) in tables.withIndex()) {
            val cue = assertNotNull(tableLayout.cueBall())
            val one = assertNotNull(tableLayout.ballByNumber(1))
            val blockers = tableLayout.objectBalls().filter { it.number != 1 }
            assertTrue(GhostBallAiming.isSegmentClear(cue.position, one.position, r, blockers), "table $index: precondition, a straight line to the 1")
            val advice = assertNotNull(FallbackShotAdvisor.advise(tableLayout, nineBall, profile), "table $index")
            assertEquals(FallbackKind.KICK, advice.kind, describe(advice))
        }
    }

    private fun bestDirectMake(tableLayout: TableLayout): Double? =
        MultiShotPlanner.planRunout(tableLayout, nineBall, profile, horizonShots = 1)
            .maxOfOrNull { it.steps.first().uncertainty.makeProbability }

    @Test
    fun aWeakDirectPotDoesNotSuppressAdviceAndTheReasonSaysSo() {
        // Long or thin shots at the 1 that the model gives well under the floor, a 9 elsewhere.
        val weakTables = listOf(
            layout(0 to (0.60 to 1.05), 1 to (1.88 to 0.47), 9 to (1.61 to 0.64)),
            layout(0 to (2.09 to 0.97), 1 to (1.85 to 0.21), 9 to (1.10 to 0.77)),
            layout(0 to (1.83 to 0.96), 1 to (1.71 to 0.20), 9 to (1.13 to 1.03)),
        )
        for ((index, weakTable) in weakTables.withIndex()) {
            val make = assertNotNull(bestDirectMake(weakTable), "table $index has a direct pot")
            assertTrue(make > 0.0 && make < FallbackShotAdvisor.DIRECT_POT_FLOOR, "table $index: make ${f2(make)} is below the floor")
            val advice = assertNotNull(FallbackShotAdvisor.advise(weakTable, nineBall, profile), "table $index: advice despite the weak pot")
            println("weak pot ${f2(make)}: ${describe(advice)}")
            assertTrue("low-percentage" in advice.reason, advice.reason)
            assertTrue("${(make * 100.0 + 0.5).toInt()}%" in advice.reason, "${advice.reason} (make ${f2(make)})")
            assertTrue("No direct pot" !in advice.reason, advice.reason)
        }
    }

    @Test
    fun aDirectPotAtOrAboveTheFloorKeepsTheAdviceQuiet() {
        val random = Random(99)
        var strong = 0
        repeat(80) {
            val tableLayout = randomNineBallTable(random, wall = false)
            val best = bestDirectMake(tableLayout) ?: return@repeat
            if (best >= FallbackShotAdvisor.DIRECT_POT_FLOOR) {
                strong++
                assertNull(FallbackShotAdvisor.advise(tableLayout, nineBall, profile), "A direct pot at ${f2(best)} keeps the advice quiet")
            }
        }
        assertTrue(strong > 0, "The random tables offered no direct pot above the floor")
    }

    @Test
    fun adviceForATableWithNoDirectPotDoesNotMentionALowPercentagePot() {
        val advice = assertNotNull(FallbackShotAdvisor.advise(photoNoPot, nineBall, profile))
        assertTrue("low-percentage" !in advice.reason, advice.reason)
        assertTrue(advice.reason.startsWith("No direct pot"), advice.reason)
    }

    @Test
    fun adviceIsDeterministic() {
        assertEquals(FallbackShotAdvisor.advise(photoNoPot, nineBall, profile), FallbackShotAdvisor.advise(photoNoPot, nineBall, profile))
    }
}
