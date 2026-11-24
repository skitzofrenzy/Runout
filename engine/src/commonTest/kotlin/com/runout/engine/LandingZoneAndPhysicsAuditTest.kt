package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.HeatZoneGenerator
import com.runout.engine.planner.MultiShotPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LandingZoneAndPhysicsAuditTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters

    private fun polylineLength(points: List<Vec2>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += points[i - 1].distanceTo(points[i])
        }
        return total
    }

    @Test
    fun nominalLandingPositionIsInsideZoneAndSyncedWithNextStepAcrossLayouts() {
        val layouts = listOf(
            // 9-ball 3-ball runout
            TableLayout(
                table = table,
                balls = listOf(
                    Ball(0, Vec2(0.75, 0.55)),
                    Ball(1, Vec2(1.18, 0.90)),
                    Ball(2, Vec2(1.98, 0.34)),
                    Ball(9, table.footSpot()),
                ),
            ) to GameRulesState(GameType.NINE_BALL),
            // 8-ball key ball to 8 with stripe blocker
            TableLayout(
                table = table,
                balls = listOf(
                    Ball(0, Vec2(1.15, 0.48)),
                    Ball(3, Vec2(1.75, 0.36)),
                    Ball(11, Vec2(1.51, 0.18)),
                    Ball(8, Vec2(1.88, 0.92)),
                ),
            ) to GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS),
            // 10-ball cut & scratch avoidance
            TableLayout(
                table = table,
                balls = listOf(
                    Ball(0, Vec2(1.10, 0.635)),
                    Ball(1, Vec2(1.9454, 0.5946)),
                    Ball(10, Vec2(0.72, 0.32)),
                ),
            ) to GameRulesState(GameType.TEN_BALL),
            // Clustered rack with neighbor scatter
            TableLayout(
                table = table,
                balls = listOf(
                    Ball(0, Vec2(0.75, 0.56)),
                    Ball(1, Vec2(1.20, 0.72)),
                    Ball(2, Vec2(1.42, 0.42)),
                    Ball(3, Vec2(1.85, 0.96)),
                    Ball(9, Vec2(2.10, 0.28)),
                ),
            ) to GameRulesState(GameType.NINE_BALL),
        )

        for ((index, pair) in layouts.withIndex()) {
            val (layout, rules) = pair
            val plans = MultiShotPlanner.planRunout(
                layout = layout,
                rulesState = rules,
                playerProfile = PlayerProfile(),
                horizonShots = 3,
            )
            assertTrue(plans.isNotEmpty(), "Layout $index should produce a plan")
            val plan = plans.first()
            for (stepIdx in plan.steps.indices) {
                val step = plan.steps[stepIdx]
                val uncertainty = step.uncertainty
                val nom = uncertainty.nominalLandingPosition
                val mean = uncertainty.meanLandingPosition

                assertTrue(
                    table.isWithinPlayableBounds(nom, r),
                    "Layout $index step ${step.stepNumber} nominal landing $nom must lie in bounds",
                )
                assertTrue(
                    table.isWithinPlayableBounds(mean, r),
                    "Layout $index step ${step.stepNumber} mean landing $mean must lie in bounds",
                )
                assertTrue(
                    nom.distanceTo(mean) <= uncertainty.landingSpreadRadiusMeters + 1e-4,
                    "Layout $index step ${step.stepNumber} nominal landing must lie inside spread radius",
                )
                if (uncertainty.nominalTrajectory.hitsScratchPocket == null) {
                    val trajEnd = uncertainty.nominalTrajectory.points.last()
                    assertEquals(
                        0.0,
                        trajEnd.distanceTo(nom),
                        1e-3,
                        "Layout $index step ${step.stepNumber} trajectory endpoint must match nominalLandingPosition",
                    )
                }

                val nextStep = plan.steps.getOrNull(stepIdx + 1)
                if (nextStep != null) {
                    val evolved = assertNotNull(step.resultingLayout)
                    val evolvedCue = assertNotNull(evolved.cueBall())
                    assertEquals(
                        0.0,
                        evolvedCue.position.distanceTo(nom),
                        1e-3,
                        "Evolved cue ball must sit at step nominalLandingPosition",
                    )
                    assertEquals(
                        0.0,
                        nextStep.compensatedAim.geometricAim.cueBallPos.distanceTo(nom),
                        1e-3,
                        "Next step aim must start from prior nominalLandingPosition",
                    )
                    val nextTarget = evolved.ballByNumber(nextStep.targetBall.number) ?: nextStep.targetBall
                    val nextPocket = evolved.table.pocketById(nextStep.pocketId)
                    val region = HeatZoneGenerator.computePocketVisibility(evolved, nextTarget, nextPocket, r)
                    assertFalse(
                        HeatZoneGenerator.isPointOccludedForPocket(nom, region),
                        "Layout $index step ${step.stepNumber} recommended leave must not be occluded for step ${nextStep.stepNumber}",
                    )
                }
            }
        }
    }

    @Test
    fun scratchingSigmaOutlierDoesNotDragMeanLandingIntoCornerPocketWhenNominalIsSafe() {
        // Near-45° cut into FOOT_LEFT where slight stun pulls near FOOT_RIGHT scratch line, while mild follow clears safely.
        val footLeft = table.pocketById(PocketId.FOOT_LEFT)
        val obj = Ball(1, Vec2(1.9454, 0.5946), r)
        val cue = Ball(0, Vec2(1.10, 0.635), r)
        val layout = TableLayout(table, listOf(cue, obj))
        val aim = GhostBallAiming.computeAimGeometry(cue, obj, footLeft, layout.balls, table = table)
        assertTrue(aim.isShotPlayable)

        val evaluation = com.runout.engine.planner.ExecutionErrorModel.evaluateShotUncertainty(
            layout = layout,
            targetBall = obj,
            pocket = footLeft,
            aim = aim,
            cueSpeedMps = 2.6,
            tip = TipOffset(sideRatio = 0.0, verticalRatio = 0.14),
            playerProfile = PlayerProfile(aimErrorStdRad = 0.025),
        )
        assertNull(evaluation.nominalTrajectory.hitsScratchPocket, "Nominal trajectory should clear FOOT_RIGHT")
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)
        assertTrue(
            evaluation.meanLandingPosition.distanceTo(footRight.center) > footRight.acceptanceRadiusMeters,
            "Safe nominal shot should keep meanLandingPosition out of scratch pocket",
        )
        assertTrue(
            evaluation.nominalLandingPosition.distanceTo(evaluation.meanLandingPosition) <=
                evaluation.landingSpreadRadiusMeters + 1e-6,
            "Nominal landing must stay within spread radius of meanLandingPosition",
        )
    }

    @Test
    fun clusterOverlapSeparationSyncsCueTrajectoryEndWithResultingLayout() {
        val target = Ball(1, Vec2(1.40, 0.635), r)
        val clusterA = Ball(2, Vec2(1.48, 0.42), r)
        val clusterB = Ball(3, Vec2(1.48 + 2.02 * r, 0.40), r)
        val cue = Ball(0, Vec2(0.85, 0.635), r)
        val layout = TableLayout(table, listOf(cue, target, clusterA, clusterB))
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val aim = GhostBallAiming.computeAimGeometry(cue, target, pocket, layout.balls, table = table)

        val sim = TableCollisionSimulator.simulatePostImpact(
            layout = layout,
            targetBall = target,
            aim = aim,
            cueSpeedMps = 2.4,
            tip = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
        )

        val evolvedCue = assertNotNull(sim.resultingLayout.cueBall())
        assertFalse(sim.resultingLayout.hasOverlappingBalls())
        assertTrue(sim.resultingLayout.balls.all { table.isWithinPlayableBounds(it.position, it.radiusMeters) })
        if (sim.cueBallTrajectory.hitsScratchPocket == null) {
            assertEquals(
                0.0,
                sim.cueBallTrajectory.points.last().distanceTo(evolvedCue.position),
                1e-6,
                "Cue trajectory endpoint must stay synced with resultingLayout after overlap separation",
            )
        }
    }

    @Test
    fun speedScalesProduceStrictlyIncreasingTravelDistances() {
        val profile = PlayerProfile()
        val obj = Ball(1, Vec2(1.40, 0.635), r)
        val cue = Ball(0, Vec2(0.85, 0.72), r)
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val aim = GhostBallAiming.computeAimGeometry(cue, obj, pocket, listOf(cue, obj), table = table)
        assertTrue(aim.isShotPlayable)

        val followTip = TipOffset.fromPreset(CueBallSpinPreset.FOLLOW)
        val distScale3 = polylineLength(
            SpinPhysics.traceCueBallPathWithSpin(aim, profile.speedForScale(3), followTip, table, r).points,
        )
        val distScale5 = polylineLength(
            SpinPhysics.traceCueBallPathWithSpin(aim, profile.speedForScale(5), followTip, table, r).points,
        )
        val distScale8 = polylineLength(
            SpinPhysics.traceCueBallPathWithSpin(aim, profile.speedForScale(8), followTip, table, r).points,
        )

        assertTrue(distScale3 < distScale5, "Speed 5 ($distScale5) must travel farther than Speed 3 ($distScale3)")
        assertTrue(distScale5 < distScale8, "Speed 8 ($distScale8) must travel farther than Speed 5 ($distScale5)")
    }

    @Test
    fun verticalSpinFollowStunDrawProducesMonotonicForwardProjectionAlongObjectLine() {
        val obj = Ball(1, Vec2(1.50, 0.635), r)
        val pocket = table.pocketById(PocketId.FOOT_RIGHT).copy(center = Vec2(2.50, 0.635))
        val aimDir = Vec2(1.0, 0.0).rotate(-kotlin.math.PI / 8.0)
        val ghost = Vec2(1.50 - 2.0 * r, 0.635)
        val cue = Ball(0, ghost - aimDir * 0.65, r)
        val aim = GhostBallAiming.computeAimGeometry(cue, obj, pocket, listOf(cue, obj), table = table)
        assertTrue(aim.isShotPlayable)

        val speed = 2.2
        val followEnd = SpinPhysics.traceCueBallPathWithSpin(
            aim, speed, TipOffset.fromPreset(CueBallSpinPreset.FOLLOW), table, r,
        ).points.last()
        val stunEnd = SpinPhysics.traceCueBallPathWithSpin(
            aim, speed, TipOffset.fromPreset(CueBallSpinPreset.STUN), table, r,
        ).points.last()
        val drawEnd = SpinPhysics.traceCueBallPathWithSpin(
            aim, speed, TipOffset.fromPreset(CueBallSpinPreset.DRAW), table, r,
        ).points.last()

        val followProj = (followEnd - aim.ghostBallCenter).dot(aim.objectBallDirection)
        val stunProj = (stunEnd - aim.ghostBallCenter).dot(aim.objectBallDirection)
        val drawProj = (drawEnd - aim.ghostBallCenter).dot(aim.objectBallDirection)

        assertTrue(followProj > stunProj + 0.05, "Follow ($followProj) must progress further forward than Stun ($stunProj)")
        assertTrue(stunProj > drawProj + 0.05, "Stun ($stunProj) must stay ahead of Draw ($drawProj)")
    }

    @Test
    fun leftAndRightSideEnglishDeflectSymmetricallyOffFootRail() {
        val sideRight = table.pocketById(PocketId.SIDE_RIGHT)
        val obj = Ball(1, Vec2(1.27, 0.60), r)
        val cue = Ball(0, Vec2(0.95, 0.25), r)
        val aim = GhostBallAiming.computeAimGeometry(cue, obj, sideRight, listOf(cue, obj), table = table)
        assertTrue(aim.isShotPlayable)

        val speed = 1.0
        val leftPath = SpinPhysics.traceCueBallPathWithSpin(
            aim, speed, TipOffset(sideRatio = -0.35, verticalRatio = 0.35), table, r, maxBounces = 1,
        )
        val centerPath = SpinPhysics.traceCueBallPathWithSpin(
            aim, speed, TipOffset(sideRatio = 0.0, verticalRatio = 0.35), table, r, maxBounces = 1,
        )
        val rightPath = SpinPhysics.traceCueBallPathWithSpin(
            aim, speed, TipOffset(sideRatio = 0.35, verticalRatio = 0.35), table, r, maxBounces = 1,
        )
        assertNull(leftPath.hitsScratchPocket)
        assertNull(centerPath.hitsScratchPocket)
        assertNull(rightPath.hitsScratchPocket)
        assertEquals(3, centerPath.points.size, "Center english should rebound once off foot rail")

        val leftY = leftPath.points.last().y
        val centerY = centerPath.points.last().y
        val rightY = rightPath.points.last().y

        assertEquals(3, leftPath.points.size)
        assertEquals(3, rightPath.points.size)
        assertTrue(leftY < centerY - 0.015, "Left english ($leftY) must kick inward relative to center ($centerY)")
        assertTrue(rightY > centerY + 0.015, "Right english ($rightY) must kick wider relative to center ($centerY)")

        val leftHeading = (leftPath.points[2] - leftPath.points[1]).normalized()
        val centerHeading = (centerPath.points[2] - centerPath.points[1]).normalized()
        val rightHeading = (rightPath.points[2] - centerPath.points[1]).normalized()
        val leftDelta = centerHeading.angleTo(leftHeading)
        val rightDelta = centerHeading.angleTo(rightHeading)
        assertTrue(
            leftDelta * rightDelta < 0.0,
            "Left ($leftDelta rad) and right ($rightDelta rad) english must deflect to opposite sides of neutral rebound",
        )
    }

    @Test
    fun sandboxAllowsHighScratchFirstShotPreviewWhileNormalPlannerFiltersIt() {
        // Straight-in shot directly into FOOT_RIGHT with forced FOLLOW intentionally scratches.
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)
        val obj = Ball(9, Vec2(2.05, 0.88), r)
        val dirToPocket = (footRight.center - obj.position).normalized()
        val cue = Ball(0, obj.position - dirToPocket * 0.55, r)
        val layout = TableLayout(table, listOf(cue, obj))
        val followTip = TipOffset.fromPreset(CueBallSpinPreset.FOLLOW)

        val normalFiltered = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.NINE_BALL),
            customFirstShotTip = followTip,
            customFirstShotSpeedScale = 8,
            customFirstShotPocketId = PocketId.FOOT_RIGHT,
            allowHighScratchFirstShot = false,
        )
        assertTrue(normalFiltered.isEmpty(), "Default planner should filter out high-probability scratch forced shot")

        val sandboxAllowed = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.NINE_BALL),
            customFirstShotTip = followTip,
            customFirstShotSpeedScale = 8,
            customFirstShotPocketId = PocketId.FOOT_RIGHT,
            allowHighScratchFirstShot = true,
        )
        assertTrue(sandboxAllowed.isNotEmpty(), "Sandbox mode must keep the high-scratch preview visible")
        val step = sandboxAllowed.first().steps.single()
        assertNotNull(step.uncertainty.nominalTrajectory.hitsScratchPocket)
        assertTrue(step.uncertainty.scratchProbability >= 0.45)
    }

    @Test
    fun everyPositiveHeatZoneCellIsInBoundsClearOfBallsAndUnoccluded() {
        val target = Ball(7, Vec2(1.90, 0.84), r)
        val blocker = Ball(12, Vec2(1.30, 0.56), r)
        val layout = TableLayout(table, listOf(Ball(0, Vec2(0.55, 0.45)), target, blocker))

        val heatMap = HeatZoneGenerator.computeForNextBalls(
            layout = layout,
            targetBallNumbers = listOf(7),
            cols = 24,
            rows = 12,
        )
        val visibilities = table.pockets.map { pocket ->
            HeatZoneGenerator.computePocketVisibility(layout, target, pocket, r)
        }
        val objectBalls = layout.balls.filter { !it.isCueBall }

        var positiveCount = 0
        for (cell in heatMap.cells) {
            if (cell.value <= 0.0) continue
            positiveCount++
            assertTrue(
                table.isWithinPlayableBounds(cell.centerMeters, r),
                "Positive cell ${cell.centerMeters} must stay inside playable bounds",
            )
            assertTrue(
                objectBalls.none { it.position.distanceTo(cell.centerMeters) < 2.0 * r },
                "Positive cell ${cell.centerMeters} must not overlap any object ball",
            )
            assertTrue(
                visibilities.any { !HeatZoneGenerator.isPointOccludedForPocket(cell.centerMeters, it) },
                "Positive cell ${cell.centerMeters} must be unoccluded for at least one pocket",
            )
        }
        assertTrue(positiveCount > 10, "Open table should contain viable leave cells")
    }

    @Test
    fun halfTableMaxDrawAtSpeedTwoPointFiveTransitionsFromDrawToForwardRoll() {
        val profile = PlayerProfile()
        val pocket = table.pocketById(PocketId.FOOT_RIGHT).copy(center = Vec2(2.50, 0.635))
        val obj = Ball(1, Vec2(1.80, 0.635), r)
        val ghost = Vec2(1.80 - 2.0 * r, 0.635)
        // Cue ball half a 9-ft table away (1.27 m cue-to-ghost)
        val halfTableCue = Ball(0, ghost - Vec2(1.27, 0.0), r)
        val halfTableAim = GhostBallAiming.computeAimGeometry(
            halfTableCue,
            obj,
            pocket,
            listOf(halfTableCue, obj),
            table = table,
        )
        assertTrue(halfTableAim.isShotPlayable)

        val maxDrawTip = TipOffset(sideRatio = 0.0, verticalRatio = -0.45)
        val speed25Mps = profile.speedForUnits(2.5)
        val preAt25 = SpinPhysics.computePreImpactCueState(
            cueSpeedMps = speed25Mps,
            tip = maxDrawTip,
            cueToGhostDistanceMeters = halfTableAim.cueToGhostDistanceMeters,
            table = table,
        )
        assertTrue(preAt25.initialSpinFactorK < -1.0, "Strike starts with max backspin")
        assertEquals(
            SpinPhysics.ImpactSpinState.NATURAL_FORWARD_ROLL,
            preAt25.state,
            "At speed 2.5 over half-table (1.27m), max draw must wear off through stun into forward roll",
        )
        assertTrue(preAt25.impactSpeedMps > 0.0)

        val pathAt25 = SpinPhysics.traceCueBallPathWithSpin(halfTableAim, speed25Mps, maxDrawTip, table, r)
        val projAt25 = (pathAt25.points.last() - halfTableAim.ghostBallCenter).dot(halfTableAim.objectBallDirection)
        assertTrue(projAt25 > 0.0, "Post-impact cue ball at speed 2.5 must roll forward (+x), got $projAt25")

        // At firm speed 6.5 over the same 1.27m distance, backspin survives and draws backward
        val speed65Mps = profile.speedForUnits(6.5)
        val preAt65 = SpinPhysics.computePreImpactCueState(
            cueSpeedMps = speed65Mps,
            tip = maxDrawTip,
            cueToGhostDistanceMeters = halfTableAim.cueToGhostDistanceMeters,
            table = table,
        )
        assertEquals(SpinPhysics.ImpactSpinState.BACKSPIN_DRAW, preAt65.state)
        val pathAt65 = SpinPhysics.traceCueBallPathWithSpin(halfTableAim, speed65Mps, maxDrawTip, table, r)
        val projAt65 = (pathAt65.points.last() - halfTableAim.ghostBallCenter).dot(halfTableAim.objectBallDirection)
        assertTrue(projAt65 < -0.05, "At speed 6.5 over 1.27m, max draw must pull backward, got $projAt65")

        // At close range (0.22 m), even speed 2.5 holds backspin and draws backward
        val closeCue = Ball(0, ghost - Vec2(0.22, 0.0), r)
        val closeAim = GhostBallAiming.computeAimGeometry(closeCue, obj, pocket, listOf(closeCue, obj), table = table)
        val closePath25 = SpinPhysics.traceCueBallPathWithSpin(closeAim, speed25Mps, maxDrawTip, table, r)
        val closeProj25 = (closePath25.points.last() - closeAim.ghostBallCenter).dot(closeAim.objectBallDirection)
        assertTrue(closeProj25 < -0.02, "Close-range speed 2.5 max draw must still draw back, got $closeProj25")
    }

    @Test
    fun twentyPointSpeedScaleAndMultiLevelSpinDescriptionsWorkAccurately() {
        val profile = PlayerProfile()
        val units = PlayerProfile.TWENTY_POINT_SPEED_UNITS
        assertEquals(20, units.size)
        assertEquals(0.5, units.first(), 1e-9)
        assertEquals(10.0, units.last(), 1e-9)
        assertEquals("2.5", PlayerProfile.formatSpeedUnits(2.5))
        assertEquals("5", PlayerProfile.formatSpeedUnits(5.0))

        for (i in 1 until units.size) {
            assertTrue(
                profile.speedForUnits(units[i]) > profile.speedForUnits(units[i - 1]),
                "20-point speed units must map strictly monotonically",
            )
        }
        for (intScale in 1..10) {
            assertEquals(profile.speedForScale(intScale), profile.speedForUnits(intScale.toDouble()), 1e-12)
        }

        assertEquals("Center Ball (Stun)", TipOffset(0.0, 0.0).describeSpinLabel())
        assertEquals("Touch of Low (Drag Stun)", TipOffset(0.0, -0.12).describeSpinLabel())
        assertEquals("Controlled Follow + 1/2-Tip Left", TipOffset(-0.20, 0.22).describeSpinLabel())
        assertEquals("Firm Draw + Touch of Right", TipOffset(0.11, -0.34).describeSpinLabel())
        assertEquals("Max Power Draw", TipOffset(0.0, -0.45).describeSpinLabel())
        assertEquals("Center + Firm Left English", TipOffset(-0.32, 0.0).describeSpinLabel())

        val layout = TableLayout(
            table,
            listOf(
                Ball(0, Vec2(0.85, 0.635), r),
                Ball(1, Vec2(1.45, 0.50), r),
                Ball(9, Vec2(2.00, 0.85), r),
            ),
        )
        val plans = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.NINE_BALL),
            customFirstShotSpeedUnits = 3.5,
        )
        assertTrue(plans.isNotEmpty())
        assertEquals(3.5, plans.first().steps.first().recommendedSpeedUnits, 1e-9)
    }

    @Test
    fun softStrokeCueBallStopsShortBeforeReachingObjectBall() {
        val profile = PlayerProfile()
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val obj = Ball(1, Vec2(2.15, 0.98), r)
        val cue = Ball(0, Vec2(0.30, 0.30), r)
        val layout = TableLayout(table, listOf(cue, obj))
        val aim = GhostBallAiming.computeAimGeometry(cue, obj, pocket, layout.balls, table = table)
        assertTrue(aim.isShotPlayable)

        // Speed 0.5 with max draw across ~2.0m table distance loses all kinetic energy before contact
        val ultraSoftSpeed = profile.speedForUnits(0.5)
        val maxDraw = TipOffset(sideRatio = 0.0, verticalRatio = -0.45)
        val sim = TableCollisionSimulator.simulatePostImpact(
            layout = layout,
            targetBall = obj,
            aim = aim,
            cueSpeedMps = ultraSoftSpeed,
            tip = maxDraw,
        )

        assertTrue(sim.cueBallStoppedBeforeImpact, "Cue ball must stop short before reaching ghost ball")
        assertFalse(sim.targetBallPotted, "Target ball cannot pot if cue ball never reached it")
        val targetAfter = sim.resultingLayout.ballByNumber(1)
        assertNotNull(targetAfter, "Target ball must remain on table unmoved")
        assertEquals(obj.position.x, targetAfter.position.x, 1e-6)
        assertEquals(obj.position.y, targetAfter.position.y, 1e-6)
        val cueStop = sim.cueBallTrajectory.points.last()
        assertTrue(
            cueStop.distanceTo(cue.position) < aim.cueToGhostDistanceMeters - 0.10,
            "Cue ball stopping point $cueStop must be strictly short of ghost center ${aim.ghostBallCenter}",
        )
    }

    @Test
    fun collisionPowerTransferLeavesObjectBallShortOnSoftCutWhileFirmStrokePotsIt() {
        val profile = PlayerProfile()
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        // Object ball 1.65 m from Foot Right corner with a thin ~50-degree cut from nearby cue ball
        val obj = Ball(9, Vec2(1.08, 0.48), r)
        val dirToPocket = (pocket.center - obj.position).normalized()
        val ghost = obj.position - dirToPocket * (2.0 * r)
        val cutAimDir = dirToPocket.rotate(0.92) // ~52.7 deg cut so cos|cut| ~ 0.606 transfers partial speed
        val cue = Ball(0, ghost - cutAimDir * 0.28, r)
        val layout = TableLayout(table, listOf(cue, obj))
        val aim = GhostBallAiming.computeAimGeometry(cue, obj, pocket, layout.balls, table = table)
        assertTrue(aim.isShotPlayable, "Thin cut should be geometrically playable")

        // Soft speed 1.0 reaches the nearby object ball (0.28m away), but transferred speed (cos 53°)
        // plus initial 5/7 skid friction leaves ball #9 well short of the 1.65m distant pocket!
        val softSandboxPlans = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.NINE_BALL),
            playerProfile = profile,
            customFirstShotTargetBallNumber = 9,
            customFirstShotPocketId = PocketId.FOOT_RIGHT,
            customFirstShotSpeedUnits = 1.0,
            customFirstShotTip = TipOffset(0.0, 0.0),
            allowHighScratchFirstShot = true,
        )
        assertTrue(softSandboxPlans.isNotEmpty(), "Sandbox must preview soft underpowered shot")
        val softStep = softSandboxPlans.first().steps.single()
        val softSim = assertNotNull(softStep.uncertainty.postShotSimulation)
        assertFalse(softSim.cueBallStoppedBeforeImpact, "Cue ball at 0.28m reaches ball #9")
        assertFalse(softSim.targetBallPotted, "Transferred speed on soft 53° cut must NOT reach 1.65m pocket")
        assertFalse(softStep.uncertainty.targetBallPotted)
        assertTrue(softStep.uncertainty.makeProbability <= 0.10, "Underpowered shot make% must drop near zero")

        val stoppedNine = assertNotNull(
            softSim.resultingLayout.ballByNumber(9),
            "Stopped-short object ball #9 must remain on resultingLayout",
        )
        val obTravelled = stoppedNine.position.distanceTo(obj.position)
        assertTrue(obTravelled > 0.10, "Ball #9 should move forward after impact, moved $obTravelled m")
        assertTrue(
            stoppedNine.position.distanceTo(pocket.center) > pocket.acceptanceRadiusMeters * 2.0,
            "Ball #9 should stop well short of the pocket jaws",
        )
        val targetPathEnd = assertNotNull(softSim.targetBallTrajectory).points.last()
        assertEquals(stoppedNine.position.x, targetPathEnd.x, 1e-4)
        assertEquals(stoppedNine.position.y, targetPathEnd.y, 1e-4)

        val note = SpinPhysics.describeShotPowerAndSpin(
            tip = TipOffset(0.0, 0.0),
            cueSpeedMps = profile.speedForUnits(1.0),
            aim = aim,
            pocket = pocket,
            table = table,
            targetBallNumber = 9,
        )
        assertTrue(note.contains("stops short", ignoreCase = true), "Coaching note should flag short roll: $note")

        // Default planner (allowHighScratchFirstShot = false) must reject the underpowered 1.0 speed
        val filteredAuto = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.NINE_BALL),
            playerProfile = profile,
            customFirstShotPocketId = PocketId.FOOT_RIGHT,
            customFirstShotSpeedUnits = 1.0,
            allowHighScratchFirstShot = false,
        )
        assertTrue(filteredAuto.isEmpty(), "Auto planner must never recommend a shot that stops short of the pocket")

        // Firm speed 5.5 on the exact same cut transfers enough momentum to pot ball #9 cleanly
        val firmPlans = MultiShotPlanner.planRunout(
            layout = layout,
            rulesState = GameRulesState(GameType.NINE_BALL),
            playerProfile = profile,
            customFirstShotTargetBallNumber = 9,
            customFirstShotPocketId = PocketId.FOOT_RIGHT,
            customFirstShotSpeedUnits = 5.5,
            customFirstShotTip = TipOffset(0.0, 0.0),
            allowHighScratchFirstShot = true,
        )
        assertTrue(firmPlans.isNotEmpty())
        val firmStep = firmPlans.first().steps.single()
        val firmSim = assertNotNull(firmStep.uncertainty.postShotSimulation)
        assertTrue(firmSim.targetBallPotted, "Firm speed 5.5 must transfer enough speed to pot ball #9")
        assertNull(firmSim.resultingLayout.ballByNumber(9), "Potted ball #9 must be removed from resultingLayout")
    }
}
