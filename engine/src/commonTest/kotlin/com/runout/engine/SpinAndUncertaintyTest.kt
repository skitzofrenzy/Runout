package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.ExperienceLevel
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import com.runout.engine.physics.scratchPocketId
import com.runout.engine.planner.ExecutionErrorModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpinAndUncertaintyTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters

    @Test
    fun tipOffsetClampsToMiscueLimitAndMapsSpinPresets() {
        val center = TipOffset()
        assertTrue(center.isWithinMiscueLimit)
        assertEquals(0.0, center.clampedSideRatio, 1e-12)
        assertEquals(0.0, center.clampedVerticalRatio, 1e-12)
        assertEquals(CueBallSpinPreset.STUN, center.toSpinPreset())

        val extreme = TipOffset(sideRatio = 0.50, verticalRatio = 0.50)
        assertFalse(extreme.isWithinMiscueLimit)
        val clampedMag = hypot(extreme.clampedSideRatio, extreme.clampedVerticalRatio)
        assertEquals(TipOffset.MAX_SAFE_OFFSET_RATIO, clampedMag, 1e-9)
        assertEquals(extreme.clampedSideRatio, extreme.clampedVerticalRatio, 1e-9)
        assertEquals(CueBallSpinPreset.FOLLOW, extreme.toSpinPreset())

        val followPreset = TipOffset.fromPreset(CueBallSpinPreset.FOLLOW, sideRatio = 0.25)
        val drawPreset = TipOffset.fromPreset(CueBallSpinPreset.DRAW, sideRatio = -0.20)
        val stunPreset = TipOffset.fromPreset(CueBallSpinPreset.STUN, sideRatio = 0.10)
        assertEquals(CueBallSpinPreset.FOLLOW, followPreset.toSpinPreset())
        assertEquals(CueBallSpinPreset.DRAW, drawPreset.toSpinPreset())
        assertEquals(CueBallSpinPreset.STUN, stunPreset.toSpinPreset())

        val candidates = TipOffset.standardCandidates()
        assertEquals(9, candidates.size)
        assertEquals(9, candidates.toSet().size)
        assertTrue(candidates.all { it.isWithinMiscueLimit })
    }

    @Test
    fun lowDeflectionShaftProducesLessSquirtThanStandardAndOpposesTipSide() {
        val maxRight = TipOffset(sideRatio = 0.50, verticalRatio = 0.0)
        val maxLeft = TipOffset(sideRatio = -0.50, verticalRatio = 0.0)
        val center = TipOffset(sideRatio = 0.0, verticalRatio = 0.0)

        val squirtLowRight = SpinPhysics.computeSquirtAngleRadians(maxRight, ShaftType.LOW_DEFLECTION)
        val squirtStdRight = SpinPhysics.computeSquirtAngleRadians(maxRight, ShaftType.STANDARD)
        val squirtLowLeft = SpinPhysics.computeSquirtAngleRadians(maxLeft, ShaftType.LOW_DEFLECTION)

        assertEquals(-0.022, squirtLowRight, 1e-9)
        assertEquals(-0.038, squirtStdRight, 1e-9)
        assertEquals(0.022, squirtLowLeft, 1e-9)
        assertEquals(0.0, SpinPhysics.computeSquirtAngleRadians(center, ShaftType.STANDARD), 1e-12)
        assertTrue(abs(squirtLowRight) < abs(squirtStdRight))
    }

    @Test
    fun swerveCurvesTowardTipSideAndScalesWithElevation() {
        val lowElevRight = TipOffset(sideRatio = 0.40, verticalRatio = 0.0, elevationRadians = 0.05)
        val highElevRight = TipOffset(sideRatio = 0.40, verticalRatio = 0.0, elevationRadians = 0.25)
        val flatElevRight = TipOffset(sideRatio = 0.40, verticalRatio = 0.0, elevationRadians = 0.0)
        val lowElevLeft = TipOffset(sideRatio = -0.40, verticalRatio = 0.0, elevationRadians = 0.05)

        val swerveLow = SpinPhysics.computeSwerveAngleRadians(lowElevRight, cueSpeedMps = 1.6, cueToGhostDistanceMeters = 1.2)
        val swerveHigh = SpinPhysics.computeSwerveAngleRadians(highElevRight, cueSpeedMps = 1.6, cueToGhostDistanceMeters = 1.2)
        val swerveFlat = SpinPhysics.computeSwerveAngleRadians(flatElevRight, cueSpeedMps = 1.6, cueToGhostDistanceMeters = 1.2)
        val swerveLeft = SpinPhysics.computeSwerveAngleRadians(lowElevLeft, cueSpeedMps = 1.6, cueToGhostDistanceMeters = 1.2)

        assertTrue(swerveLow > 0.0, "Right english must swerve right (positive)")
        assertTrue(swerveLeft < 0.0, "Left english must swerve left (negative)")
        assertTrue(abs(swerveHigh) > abs(swerveLow), "Higher cue elevation must increase swerve")
        assertEquals(0.0, swerveFlat, 1e-12)
    }

    @Test
    fun cutAndSpinThrowHaveExpectedSignsAndSpeedDependence() {
        val center = TipOffset(sideRatio = 0.0, verticalRatio = 0.0)
        val rightEnglish = TipOffset(sideRatio = 0.40, verticalRatio = 0.0)
        val leftEnglish = TipOffset(sideRatio = -0.40, verticalRatio = 0.0)

        val (posCutSlow, zeroSpinThrow) = SpinPhysics.computeThrowAnglesRadians(PI / 6.0, cueSpeedMps = 0.8, tip = center)
        val (negCutSlow, _) = SpinPhysics.computeThrowAnglesRadians(-PI / 6.0, cueSpeedMps = 0.8, tip = center)
        val (posCutFast, _) = SpinPhysics.computeThrowAnglesRadians(PI / 6.0, cueSpeedMps = 3.5, tip = center)

        assertTrue(posCutSlow > 0.0)
        assertTrue(negCutSlow < 0.0)
        assertEquals(0.0, zeroSpinThrow, 1e-12)
        assertTrue(abs(posCutSlow) > abs(posCutFast), "Slower speed must produce larger cut throw")

        val (_, rightSpinThrow) = SpinPhysics.computeThrowAnglesRadians(0.0, cueSpeedMps = 1.2, tip = rightEnglish)
        val (_, leftSpinThrow) = SpinPhysics.computeThrowAnglesRadians(0.0, cueSpeedMps = 1.2, tip = leftEnglish)
        assertTrue(rightSpinThrow < 0.0, "Right english must impart negative (left) spin throw")
        assertTrue(leftSpinThrow > 0.0, "Left english must impart positive (right) spin throw")
    }

    @Test
    fun solveCompensatedAimShiftsGhostBallAndComputesNetCueAdjustment() {
        val pocketPos = table.pocketById(PocketId.FOOT_RIGHT).center
        val objectBallPos = Vec2(1.80, 0.85)
        val cueBallPos = Vec2(0.80, 0.85)
        val tip = TipOffset(sideRatio = 0.35, verticalRatio = 0.0, elevationRadians = 0.06)

        val compensated = SpinPhysics.solveCompensatedAim(
            cueBallPos = cueBallPos,
            objectBallPos = objectBallPos,
            pocketPos = pocketPos,
            cueBallRadiusMeters = r,
            objectBallRadiusMeters = r,
            cueSpeedMps = 1.6,
            tip = tip,
            shaftType = ShaftType.STANDARD,
            table = table,
        )
        assertNotNull(compensated)
        assertEquals(
            compensated.cutThrowAngleRadians + compensated.spinThrowAngleRadians,
            compensated.totalThrowAngleRadians,
            1e-12,
        )
        assertEquals(
            -(compensated.squirtAngleRadians + compensated.swerveAngleRadians),
            compensated.netCueAimAdjustmentRadians,
            1e-12,
        )
        assertTrue(table.isWithinPlayableBounds(compensated.compensatedGhostBallCenter, r))
        assertTrue(
            compensated.compensatedGhostBallCenter.distanceTo(compensated.geometricAim.ghostBallCenter) > 1e-5,
            "Throw compensation must shift ghost-ball aim when totalThrow is non-zero",
        )

        // Impossible > 80 deg back-cut must return null
        val impossibleBackCut = SpinPhysics.solveCompensatedAim(
            cueBallPos = Vec2(2.40, 1.15),
            objectBallPos = Vec2(1.50, 0.635),
            pocketPos = pocketPos,
            cueBallRadiusMeters = r,
            objectBallRadiusMeters = r,
            cueSpeedMps = 1.5,
            tip = tip,
            shaftType = ShaftType.STANDARD,
            table = table,
        )
        assertNull(impossibleBackCut)
    }

    @Test
    fun runningVsReverseEnglishReboundsWiderOrSteeperOffFootAndRightRails() {
        // 1. Foot rail rebound (x = lengthMeters - r), approaching with (+vx, +vy):
        // Right english (+sideRatio) is running and kicks farther toward +y (FOOT_RIGHT).
        val sideRight = table.pocketById(PocketId.SIDE_RIGHT)
        val objFootTest = Ball(number = 1, position = Vec2(1.27, 0.60), radiusMeters = r)
        val cueFootTest = Ball(number = 0, position = Vec2(0.95, 0.25), radiusMeters = r)
        val aimFootTest = GhostBallAiming.computeAimGeometry(cueFootTest, objFootTest, sideRight, table = table)
        assertTrue(aimFootTest.isShotPlayable)

        val footRunRight = SpinPhysics.traceCueBallPathWithSpin(
            aim = aimFootTest,
            cueSpeedMps = 1.0,
            tip = TipOffset(sideRatio = 0.32, verticalRatio = 0.35),
            table = table,
            ballRadiusMeters = r,
        )
        val footCenter = SpinPhysics.traceCueBallPathWithSpin(
            aim = aimFootTest,
            cueSpeedMps = 1.0,
            tip = TipOffset(sideRatio = 0.0, verticalRatio = 0.35),
            table = table,
            ballRadiusMeters = r,
        )
        val footRevLeft = SpinPhysics.traceCueBallPathWithSpin(
            aim = aimFootTest,
            cueSpeedMps = 1.0,
            tip = TipOffset(sideRatio = -0.32, verticalRatio = 0.35),
            table = table,
            ballRadiusMeters = r,
        )

        assertNull(footCenter.scratchPocketId)
        assertEquals(3, footCenter.points.size, "Expected cue ball to rebound once off foot rail")
        assertTrue(
            footRunRight.points.last().y > footCenter.points.last().y &&
                footCenter.points.last().y > footRevLeft.points.last().y,
            "Running right english on foot rail must finish farther +y than neutral and reverse left english",
        )

        // 2. Right rail rebound (y = widthMeters - r), approaching with (+vx, +vy):
        // Left english (-sideRatio) is running and kicks farther toward +x (FOOT_RIGHT).
        val footLeft = table.pocketById(PocketId.FOOT_LEFT)
        val objRightTest = Ball(number = 2, position = Vec2(1.60, 0.55), radiusMeters = r)
        val cueRightTest = Ball(number = 0, position = Vec2(0.95, 0.58), radiusMeters = r)
        val aimRightTest = GhostBallAiming.computeAimGeometry(cueRightTest, objRightTest, footLeft, table = table)
        assertTrue(aimRightTest.isShotPlayable)

        val rightRunLeft = SpinPhysics.traceCueBallPathWithSpin(
            aim = aimRightTest,
            cueSpeedMps = 1.4,
            tip = TipOffset(sideRatio = -0.32, verticalRatio = 0.0),
            table = table,
            ballRadiusMeters = r,
        )
        val rightCenter = SpinPhysics.traceCueBallPathWithSpin(
            aim = aimRightTest,
            cueSpeedMps = 1.4,
            tip = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
            table = table,
            ballRadiusMeters = r,
        )
        val rightRevRight = SpinPhysics.traceCueBallPathWithSpin(
            aim = aimRightTest,
            cueSpeedMps = 1.4,
            tip = TipOffset(sideRatio = 0.32, verticalRatio = 0.0),
            table = table,
            ballRadiusMeters = r,
        )

        assertNull(rightCenter.scratchPocketId)
        assertEquals(3, rightCenter.points.size, "Expected cue ball to rebound once off right rail")
        assertTrue(
            rightRunLeft.points.last().x > rightCenter.points.last().x &&
                rightCenter.points.last().x > rightRevRight.points.last().x,
            "Running left english on right rail must finish farther +x than neutral and reverse right english",
        )
    }

    @Test
    fun traceCueBallPathWithSpinDetectsScratchAndMultiBounceTrajectories() {
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)
        val scratchObj = Ball(number = 9, position = Vec2(2.10, 1.27 - (2.54 - 2.10) * 0.5), radiusMeters = r)
        val toFootRight = (footRight.center - scratchObj.position).normalized()
        val scratchCue = Ball(number = 0, position = scratchObj.position - toFootRight * 0.5, radiusMeters = r)
        val scratchAim = GhostBallAiming.computeAimGeometry(scratchCue, scratchObj, footRight, table = table)

        val scratchPath = SpinPhysics.traceCueBallPathWithSpin(
            aim = scratchAim,
            cueSpeedMps = 3.0,
            tip = TipOffset.fromPreset(CueBallSpinPreset.FOLLOW),
            table = table,
            ballRadiusMeters = r,
        )
        assertEquals(PocketId.FOOT_RIGHT, scratchPath.hitsScratchPocket)
        assertEquals(PocketId.FOOT_RIGHT, scratchPath.scratchPocketId)

        // High-speed follow cut crossing multiple cushions with maxBounces = 3
        val sideRight = table.pocketById(PocketId.SIDE_RIGHT)
        val multiObj = Ball(number = 1, position = Vec2(1.27, 0.65), radiusMeters = r)
        val multiCue = Ball(number = 0, position = Vec2(0.95, 0.40), radiusMeters = r)
        val multiAim = GhostBallAiming.computeAimGeometry(multiCue, multiObj, sideRight, table = table)

        val multiBouncePath = SpinPhysics.traceCueBallPathWithSpin(
            aim = multiAim,
            cueSpeedMps = 4.5,
            tip = TipOffset.fromPreset(CueBallSpinPreset.FOLLOW),
            table = table,
            ballRadiusMeters = r,
            maxBounces = 3,
        )
        val singleBouncePath = SpinPhysics.traceCueBallPathWithSpin(
            aim = multiAim,
            cueSpeedMps = 4.5,
            tip = TipOffset.fromPreset(CueBallSpinPreset.FOLLOW),
            table = table,
            ballRadiusMeters = r,
            maxBounces = 1,
        )
        assertTrue(multiBouncePath.points.size >= 4, "Expected at least 2 cushion bounces with maxBounces=3")
        assertTrue(singleBouncePath.points.size <= 3, "maxBounces=1 must stop after at most 1 bounce")
        assertTrue(table.isWithinPlayableBounds(multiBouncePath.points.last(), r))
    }

    @Test
    fun pocketAcceptanceHalfAngleNarrowsWithSpeedDistanceAndShallowSideApproach() {
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)
        val closeBallPos = Vec2(2.10, 0.83)
        val farBallPos = Vec2(1.10, 0.55)

        val slowClose = ExecutionErrorModel.pocketAcceptanceHalfAngleRadians(closeBallPos, footRight, r, arrivalSpeedMps = 1.0)
        val fastClose = ExecutionErrorModel.pocketAcceptanceHalfAngleRadians(closeBallPos, footRight, r, arrivalSpeedMps = 4.0)
        val slowFar = ExecutionErrorModel.pocketAcceptanceHalfAngleRadians(farBallPos, footRight, r, arrivalSpeedMps = 1.0)

        assertTrue(slowClose > fastClose, "Fast shots rattle jaws more and must have narrower acceptance half-angle")
        assertTrue(slowClose > slowFar, "Longer distance must narrow acceptance half-angle")

        val sideRight = table.pocketById(PocketId.SIDE_RIGHT)
        val straightSidePos = Vec2(1.27, 0.70)
        val shallowSidePos = Vec2(0.75, 1.04) // nearly same distance (~0.57m), shallow approach angle
        val straightAngle = ExecutionErrorModel.pocketAcceptanceHalfAngleRadians(straightSidePos, sideRight, r, arrivalSpeedMps = 1.5)
        val shallowAngle = ExecutionErrorModel.pocketAcceptanceHalfAngleRadians(shallowSidePos, sideRight, r, arrivalSpeedMps = 1.5)
        assertTrue(straightAngle > shallowAngle, "Shallow side-pocket approach must narrow acceptance half-angle")
    }

    @Test
    fun advancedProfileHasHigherMakeProbabilityAndTighterSpreadThanBeginner() {
        val pocket = table.pocketById(PocketId.FOOT_RIGHT)
        val objectBall = Ball(number = 1, position = Vec2(1.85, 0.85), radiusMeters = r)
        val cueBall = Ball(number = 0, position = Vec2(0.95, 0.65), radiusMeters = r)
        val layout = TableLayout(table, listOf(cueBall, objectBall))
        val aim = GhostBallAiming.computeAimGeometry(cueBall, objectBall, pocket, layout.balls, table = table)
        assertTrue(aim.isShotPlayable)

        val advanced = PlayerProfile.fromQuestionnaire(
            experience = ExperienceLevel.ADVANCED,
            shaft = ShaftType.LOW_DEFLECTION,
            speedControlRating = 5,
            spinRating = 5,
        )
        val beginner = PlayerProfile.fromQuestionnaire(
            experience = ExperienceLevel.BEGINNER,
            shaft = ShaftType.STANDARD,
            speedControlRating = 1,
            spinRating = 1,
        )
        val tip = TipOffset(sideRatio = 0.15, verticalRatio = 0.25)

        val advResult1 = ExecutionErrorModel.evaluateShotUncertainty(layout, objectBall, pocket, aim, 2.2, tip, advanced)
        val advResult2 = ExecutionErrorModel.evaluateShotUncertainty(layout, objectBall, pocket, aim, 2.2, tip, advanced)
        val begResult = ExecutionErrorModel.evaluateShotUncertainty(layout, objectBall, pocket, aim, 2.2, tip, beginner)

        assertEquals(advResult1, advResult2, "Sigma-point uncertainty evaluation must be strictly deterministic")
        assertEquals(9, advResult1.landingCloud.size)
        assertEquals(9, begResult.landingCloud.size)
        assertTrue(advResult1.makeProbability in 0.02..0.99)
        assertTrue(begResult.makeProbability in 0.02..0.99)
        assertTrue(
            advResult1.makeProbability > begResult.makeProbability,
            "Advanced profile (${advResult1.makeProbability}) must outperform beginner (${begResult.makeProbability})",
        )
        assertTrue(
            advResult1.landingSpreadRadiusMeters < begResult.landingSpreadRadiusMeters,
            "Advanced profile spread (${advResult1.landingSpreadRadiusMeters}) must be tighter than beginner (${begResult.landingSpreadRadiusMeters})",
        )
    }

    @Test
    fun uncertaintyDetectsScratchRiskAndInsufficientPocketSpeed() {
        val footLeft = table.pocketById(PocketId.FOOT_LEFT)
        val ghost = Vec2(2.54 - 0.635, 0.635)
        val toFootLeft = (footLeft.center - ghost).normalized()
        val objectBall = Ball(number = 1, position = ghost + toFootLeft * (2.0 * r), radiusMeters = r)
        val tangentToFootRight = toFootLeft.rotate(PI / 2.0)
        val aimDir = (toFootLeft + tangentToFootRight).normalized()
        val cueBall = Ball(number = 0, position = ghost - aimDir * 0.60, radiusMeters = r)
        val layout = TableLayout(table, listOf(cueBall, objectBall))
        val aim = GhostBallAiming.computeAimGeometry(cueBall, objectBall, footLeft, layout.balls, table = table)

        val scratchRiskResult = ExecutionErrorModel.evaluateShotUncertainty(
            layout = layout,
            targetBall = objectBall,
            pocket = footLeft,
            aim = aim,
            cueSpeedMps = 2.5,
            tip = TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
        )
        assertTrue(
            scratchRiskResult.scratchProbability >= 0.28,
            "Scratching nominal stun trajectory must yield high scratchProbability, got ${scratchRiskResult.scratchProbability}",
        )

        // Long straight shot with insufficient speed (0.35 m/s over ~1.4 m) cannot reach the pocket
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)
        val longObj = Ball(number = 3, position = Vec2(1.40, 0.635), radiusMeters = r)
        val dirToPocket = (footRight.center - longObj.position).normalized()
        val longCue = Ball(number = 0, position = longObj.position - dirToPocket * 0.50, radiusMeters = r)
        val longLayout = TableLayout(table, listOf(longCue, longObj))
        val longAim = GhostBallAiming.computeAimGeometry(longCue, longObj, footRight, longLayout.balls, table = table)

        val tooSoft = ExecutionErrorModel.evaluateShotUncertainty(longLayout, longObj, footRight, longAim, cueSpeedMps = 0.35, tip = TipOffset())
        val goodPace = ExecutionErrorModel.evaluateShotUncertainty(longLayout, longObj, footRight, longAim, cueSpeedMps = 2.0, tip = TipOffset())
        assertTrue(tooSoft.makeProbability < 0.15, "Too-soft shot unable to reach pocket must fail (${tooSoft.makeProbability})")
        assertTrue(goodPace.makeProbability > tooSoft.makeProbability + 0.30)
    }
}
