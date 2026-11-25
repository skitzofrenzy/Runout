package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.ExperienceLevel
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallEventKind
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.SpinCompensatedAim
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TableCollisionSimulator
import com.runout.engine.physics.TipOffset
import com.runout.engine.physics.scratchPocketId
import com.runout.engine.planner.ExecutionErrorModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.sin
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

    private fun deg(radians: Double): Double = radians * 180.0 / PI

    private fun rad(degrees: Double): Double = degrees * PI / 180.0

    /** Total throw in degrees for a contact with the given impact speed, follow spin factor and side-english tip. */
    private fun throwDeg(
        cutDeg: Double,
        speed: Double,
        k: Double = 0.0,
        sideRatio: Double = 0.0,
        strokeSpeed: Double = speed,
    ): Double = deg(
        SpinPhysics.computeThrowAnglesRadians(
            cutAngleSignedRadians = rad(cutDeg),
            impactSpeedMps = speed,
            impactSpinFactorK = k,
            sideSpinSurfaceSpeedMps = SpinPhysics.sideSpinSurfaceSpeedAtImpactMps(
                TipOffset(sideRatio = sideRatio), strokeSpeed,
            ),
        ).totalRadians,
    )

    @Test
    fun stunCutThrowHasTheSignOfTheCutAndTheRightSizeAtHalfBall() {
        // Half-ball (30 deg) stun at 1 m/s: v_rel = 0.5 m/s, throw = atan(mu(0.5)) ~ 4.2 deg.
        val right = throwDeg(30.0, 1.0)
        val left = throwDeg(-30.0, 1.0)
        assertEquals(4.2, right, 0.3)
        assertEquals(-right, left, 1e-9)

        // Faster shots throw clearly less because friction falls with sliding speed.
        val slow = throwDeg(30.0, 1.0)
        val medium = throwDeg(30.0, 2.0)
        val fast = throwDeg(30.0, 3.0)
        assertTrue(medium < 0.75 * slow && fast < 0.75 * medium, "throw must fall with speed: $slow $medium $fast")
        assertEquals(1.8, fast, 0.3)
    }

    @Test
    fun rollingCueBallThrowsMuchLessThanStun() {
        for (speed in listOf(1.0, 2.0, 3.0)) {
            val stun = throwDeg(30.0, speed)
            val rolling = throwDeg(30.0, speed, k = 1.0)
            assertTrue(rolling > 0.0)
            assertTrue(rolling < 0.5 * stun, "rolling $rolling must be well below stun $stun at $speed m/s")
        }
        // Draw reduces it as well, the extra vertical sliding takes friction away from the lateral direction.
        assertTrue(throwDeg(30.0, 1.0, k = -0.5) < 0.7 * throwDeg(30.0, 1.0))
    }

    @Test
    fun stunThrowPeaksNearHalfBallAndFallsOffTowardThinCuts() {
        for (speed in listOf(1.0, 2.0, 3.0)) {
            assertTrue(
                throwDeg(60.0, speed) < throwDeg(30.0, speed),
                "stun throw at 60 deg must be smaller than at 30 deg at $speed m/s",
            )
        }
        val byCut = (10..75 step 5).map { it to throwDeg(it.toDouble(), 1.0) }
        val peakCut = byCut.maxByOrNull { it.second }!!.first
        assertTrue(peakCut in 25..45, "stun throw must peak between 25 and 45 deg, peaked at $peakCut")
        assertTrue(throwDeg(75.0, 1.0) < throwDeg(peakCut.toDouble(), 1.0))
    }

    @Test
    fun outsideEnglishGearsAwayCutThrow() {
        // A cut with positive sign: the contact surfaces slide apart at v*sin(phi) - R*omegaZ. Left english
        // (negative side ratio) gives positive omegaZ and closes that gap: R*omegaZ = 5/2 * a/R * v.
        val speed = 1.0
        fun throwAt(sideRatio: Double) = throwDeg(30.0, speed, sideRatio = sideRatio)
        assertTrue(throwAt(0.0) > 0.0)

        // Gearing: the surfaces stop sliding when 5/2 * a/R = sin(30 deg) = 0.5, i.e. at 0.2 of the radius left.
        val ratios = (-50..50).map { it / 100.0 }.reversed()
        val gearing = ratios.zipWithNext().first { (a, b) -> throwAt(a) > 0.0 && throwAt(b) <= 0.0 }
        assertEquals(-0.2, 0.5 * (gearing.first + gearing.second), 0.011)
        assertEquals(0.0, throwAt(-0.2), 1e-9)
        assertTrue(throwAt(-0.19) > 0.0 && throwAt(-0.21) < 0.0, "throw must change sign through the gearing value")
        assertTrue(throwAt(-0.3) < 0.0, "over-geared contact must throw to the other side")

        // Mirror image for a cut of the other sign.
        assertEquals(0.0, throwDeg(-30.0, speed, sideRatio = 0.2), 1e-9)
        assertTrue(throwDeg(-30.0, speed, sideRatio = 0.19) < 0.0)
        assertTrue(throwDeg(-30.0, speed, sideRatio = 0.21) > 0.0)
    }

    @Test
    fun insideEnglishAddsThrowWhereTheNoSlipLimitHoldsPlainThrowDown() {
        // A 15 degree stun cut at 1 m/s slides at only 0.26 m/s, where friction is high enough to reach the 1/7
        // limit, so the plain throw (atan(tan(phi) / 7)) is held down; the extra sliding of inside english raises it.
        val plain = throwDeg(15.0, 1.0)
        val inside = throwDeg(15.0, 1.0, sideRatio = 0.10)
        assertEquals(2.2, plain, 0.1)
        assertTrue(inside > plain + 1.5, "inside english must add throw on a thin-ish cut: $plain -> $inside")
        // The gearing english is the same fraction of the radius at any speed: R*omegaZ = v*sin(phi).
        assertEquals(0.0, throwDeg(15.0, 1.0, sideRatio = -sin(rad(15.0)) / 2.5), 1e-9)
        assertEquals(0.0, throwDeg(15.0, 2.7, sideRatio = -sin(rad(15.0)) / 2.5), 1e-9)
    }

    @Test
    fun straightInSideSpinThrowsTheObjectBallAwayFromTheEnglishAndIsBounded() {
        // Straight-in, left english: the cue ball's contact surface moves toward positive (right), dragging the
        // object ball to the right: thrown direction = line.rotate(-theta) with theta < 0.
        val left = throwDeg(0.0, 1.0, sideRatio = -0.2)
        val right = throwDeg(0.0, 1.0, sideRatio = 0.2)
        assertTrue(right > 0.0 && left < 0.0)
        assertEquals(-left, right, 1e-9)
        assertEquals(4.2, right, 0.3)

        // However much side spin there is and however slow the shot, throw stays within the no-slip limit.
        val limit = atan(1.0 / 7.0)
        for (speed in listOf(0.05, 0.1, 0.3, 1.0, 3.0)) {
            for (strokeSpeed in listOf(speed, 3.0 * speed, 6.0)) {
                for (side in (-50..50 step 5).map { it / 100.0 }) {
                    for (k in listOf(-1.0, 0.0, 1.0)) {
                        for (cut in listOf(0.0, 15.0, 30.0, 45.0, 70.0)) {
                            val theta = rad(throwDeg(cut, speed, k, side, strokeSpeed))
                            assertTrue(abs(theta) <= limit + 1e-12, "throw $theta exceeds the no-slip limit")
                        }
                    }
                }
            }
        }
        // A slow straight-in shot with heavy side spin is in the capped regime and still throws a lot.
        assertTrue(abs(throwDeg(0.0, 0.3, sideRatio = 0.5)) > 3.0)
    }

    @Test
    fun throwEdgeCasesAndBreakdownAddUp() {
        assertEquals(0.0, SpinPhysics.computeThrowAnglesRadians(0.5, 0.0).totalRadians, 0.0)
        assertEquals(0.0, SpinPhysics.computeThrowAnglesRadians(0.0, 1.0).totalRadians, 0.0)
        assertEquals(0.0, SpinPhysics.computeThrowAnglesRadians(PI / 2.0, 1.0).totalRadians, 0.0)
        val parts = SpinPhysics.computeThrowAnglesRadians(
            cutAngleSignedRadians = rad(15.0),
            impactSpeedMps = 1.0,
            sideSpinSurfaceSpeedMps = -0.25,
        )
        assertEquals(parts.cutOnlyRadians + parts.spinRadians, parts.totalRadians, 1e-15)
        assertEquals(
            SpinPhysics.computeThrowAnglesRadians(rad(15.0), 1.0).totalRadians,
            parts.cutOnlyRadians,
            1e-15,
        )
        assertTrue(parts.spinRadians > 0.0, "right english adds to a positive cut's throw at this speed")
    }

    /**
     * Solves the compensated aim of a shot whose geometric cut is exactly [cutDeg] (signed): the object ball sits
     * at the middle of the table on a pot line along +x, and the cue ball [distance] metres behind the ghost ball.
     */
    private fun solveCut(
        cutDeg: Double,
        speed: Double,
        distance: Double,
        tip: (Double) -> TipOffset,
    ): SpinCompensatedAim {
        val objectPos = Vec2(1.50, 0.635)
        val potLine = Vec2(1.0, 0.0)
        val ghost = objectPos - potLine * (2.0 * r)
        val aimDirection = potLine.rotate(-rad(cutDeg))
        val cuePos = ghost - aimDirection * distance
        val pocket = targetPocket(objectPos + potLine * 0.9, potLine)
        val geometric = GhostBallAiming.computeAimGeometry(Ball(0, cuePos, r), Ball(1, objectPos, r), pocket)
        assertEquals(rad(cutDeg), geometric.cutAngleRadians, 1e-9)
        return assertNotNull(
            SpinPhysics.solveCompensatedAim(geometric, r, r, speed, tip(distance), ShaftType.STANDARD, table),
        )
    }

    private fun stunTip(speed: Double): (Double) -> TipOffset = { distance ->
        assertNotNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.STUN, speed, distance, table))
    }

    /** Signed cut angle of the shot that aims the cue ball at [ghost]. */
    private fun cutAt(cue: Vec2, ghost: Vec2, objectPos: Vec2): Double =
        (ghost - cue).normalized().angleTo((objectPos - ghost).normalized())

    @Test
    fun compensatedGhostBallIsThinnerThanGeometricAndSymmetricForLeftAndRightCuts() {
        // Half-ball stun arriving at about 1 m/s: stroke 1.2 m/s, 0.1 m of cloth.
        val right = solveCut(30.0, 1.2, 0.1, stunTip(1.2))
        val left = solveCut(-30.0, 1.2, 0.1, stunTip(1.2))

        for ((aim, sign) in listOf(right to 1.0, left to -1.0)) {
            val geometric = aim.geometricAim
            val geometricCut = geometric.cutAngleRadians
            val compensatedCut = cutAt(geometric.cueBallPos, aim.compensatedGhostBallCenter, geometric.objectBallPos)
            assertTrue(geometricCut * sign > 0.0)
            assertTrue(
                abs(compensatedCut) > abs(geometricCut) + rad(1.0),
                "compensated hit must be thinner: ${deg(compensatedCut)} vs ${deg(geometricCut)} deg",
            )
            assertTrue(compensatedCut * sign > 0.0, "the thinner hit stays on the same side")
            assertTrue(aim.totalThrowAngleRadians * sign > 0.0)

            // The compensated aim line passes farther from the object ball's centre than the geometric one.
            val toObject = geometric.objectBallPos - geometric.cueBallPos
            fun offset(ghost: Vec2): Double = abs((ghost - geometric.cueBallPos).normalized().cross(toObject))
            assertTrue(offset(aim.compensatedGhostBallCenter) > offset(geometric.ghostBallCenter))
        }
        // Mirror symmetry about the table's long axis (y = 0.635).
        assertEquals(right.compensatedGhostBallCenter.x, left.compensatedGhostBallCenter.x, 1e-9)
        assertEquals(
            2.0 * 0.635 - right.compensatedGhostBallCenter.y,
            left.compensatedGhostBallCenter.y,
            1e-9,
        )
        assertEquals(right.totalThrowAngleRadians, -left.totalThrowAngleRadians, 1e-9)
    }

    @Test
    fun throwingTheCompensatedContactSendsTheObjectBallDownThePotLine() {
        class Case(val cutDeg: Double, val speed: Double, val distance: Double, val tip: TipOffset?)
        val cases = listOf(
            Case(30.0, 1.2, 0.1, null),
            Case(-30.0, 1.2, 0.1, null),
            Case(15.0, 2.0, 0.4, null),
            Case(-45.0, 2.0, 0.5, null),
            Case(60.0, 2.5, 0.5, null),
            Case(30.0, 1.2, 0.1, TipOffset(sideRatio = 0.15)),
            Case(30.0, 1.2, 0.1, TipOffset(sideRatio = -0.25)),
            Case(-25.0, 2.5, 0.6, TipOffset(sideRatio = 0.30, verticalRatio = 0.35)),
            Case(35.0, 2.0, 0.4, TipOffset(sideRatio = -0.20, verticalRatio = -0.30)),
            Case(0.0, 1.0, 0.3, TipOffset(sideRatio = 0.30)),
        )
        for (case in cases) {
            val tipFor = { distance: Double -> case.tip ?: stunTip(case.speed)(distance) }
            val aim = solveCut(case.cutDeg, case.speed, case.distance, tipFor)
            val g = aim.geometricAim
            val ghost = aim.compensatedGhostBallCenter
            val aimDirection = (ghost - g.cueBallPos).normalized()
            val lineOfCentres = (g.objectBallPos - ghost).normalized()
            val tip = tipFor(case.distance)
            val pre = SpinPhysics.computePreImpactCueState(
                case.speed, tip, (ghost - g.cueBallPos).length(), table,
            )
            val thrown = SpinPhysics.computeThrowAnglesRadians(
                cutAngleSignedRadians = aimDirection.angleTo(lineOfCentres),
                impactSpeedMps = pre.impactSpeedMps,
                impactSpinFactorK = pre.impactSpinFactorK,
                sideSpinSurfaceSpeedMps = SpinPhysics.sideSpinSurfaceSpeedAtImpactMps(
                    tip, case.speed, (ghost - g.cueBallPos).length(), table,
                ),
            )
            val thrownDirection = lineOfCentres.rotate(-thrown.totalRadians)
            val error = abs(thrownDirection.angleTo(g.objectBallDirection))
            assertTrue(
                error < rad(0.05),
                "object ball must leave along the pot line within 0.05 deg, off by ${deg(error)} deg " +
                    "for cut ${case.cutDeg}",
            )
            assertEquals(thrown.totalRadians, aim.totalThrowAngleRadians, 1e-8)
            assertEquals(aim.cutThrowAngleRadians + aim.spinThrowAngleRadians, aim.totalThrowAngleRadians, 1e-15)
        }
    }

    @Test
    fun compensationSignIsPinnedByTheThrownDirection() {
        // The one convention (see ThrowAngles): a contact's thrown direction is lineOfCentres.rotate(-throw), so the
        // compensated line of centres is potLine.rotate(+throw), a larger cut for a positive throw.
        val aim = solveCut(30.0, 1.2, 0.1, stunTip(1.2))
        val g = aim.geometricAim
        val lineOfCentres = (g.objectBallPos - aim.compensatedGhostBallCenter).normalized()
        assertEquals(0.0, lineOfCentres.angleTo(g.objectBallDirection.rotate(aim.totalThrowAngleRadians)), 1e-9)
        assertTrue(g.aimDirection.angleTo(g.objectBallDirection) > 0.0, "positive cut: line of centres is aim turned by +phi")
        assertTrue(aim.totalThrowAngleRadians > 0.0)
        assertTrue(
            (aim.compensatedGhostBallCenter - g.cueBallPos).normalized().angleTo(lineOfCentres) > g.cutAngleRadians,
            "a positive throw is compensated by a larger positive cut",
        )
    }

    @Test
    fun throwCompensationIsAbsentWithoutImpactSpeed() {
        // A stroke too soft to reach the object ball has no contact, so nothing is thrown and nothing compensated.
        val aim = solveCut(30.0, 0.3, 0.6) { TipOffset() }
        assertEquals(0.0, aim.totalThrowAngleRadians, 0.0)
        assertEquals(0.0, aim.compensatedGhostBallCenter.distanceTo(aim.geometricAim.ghostBallCenter), 1e-12)
    }

    @Test
    fun solveCompensatedAimShiftsGhostBallAndComputesNetCueAdjustment() {
        val pocketPos = table.pocketById(PocketId.FOOT_RIGHT).mouthCenter
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
    fun runningAndReverseEnglishActAtTheFootAndRightRails() {
        // 1. Foot rail (x = lengthMeters - r), approached moving +x and +y: right english is running. The stroke is firm
        // enough for the english to outlast the long way to the rail (it wears off on the cloth).
        val footSpeed = 2.0
        val sideRight = table.pocketById(PocketId.SIDE_RIGHT)
        val objFootTest = Ball(number = 1, position = Vec2(1.27, 0.60), radiusMeters = r)
        val cueFootTest = Ball(number = 0, position = Vec2(0.95, 0.25), radiusMeters = r)
        val aimFootTest = GhostBallAiming.computeAimGeometry(cueFootTest, objFootTest, sideRight, table = table)
        assertTrue(aimFootTest.isShotPlayable)
        val footCenter = SpinPhysics.traceCueBallPathWithSpin(
            aim = aimFootTest,
            cueSpeedMps = footSpeed,
            tip = TipOffset(sideRatio = 0.0, verticalRatio = 0.35),
            table = table,
            ballRadiusMeters = r,
        )
        assertNull(footCenter.scratchPocketId)
        assertTrue(footCenter.cushionContacts >= 1, "Expected cue ball to rebound off the foot rail")
        assertEnglishActsAtFirstCushion(
            layout = TableLayout(table, listOf(cueFootTest, objFootTest)),
            target = objFootTest,
            aim = aimFootTest,
            speed = footSpeed,
            running = TipOffset(sideRatio = 0.32, verticalRatio = 0.35),
            plain = TipOffset(sideRatio = 0.0, verticalRatio = 0.35),
            reverse = TipOffset(sideRatio = -0.32, verticalRatio = 0.35),
        )

        // 2. Right rail (y = widthMeters - r), approached moving +x and +y: left english is running.
        val footLeft = table.pocketById(PocketId.FOOT_LEFT)
        val objRightTest = Ball(number = 2, position = Vec2(1.60, 0.55), radiusMeters = r)
        val cueRightTest = Ball(number = 0, position = Vec2(0.95, 0.58), radiusMeters = r)
        val aimRightTest = GhostBallAiming.computeAimGeometry(cueRightTest, objRightTest, footLeft, table = table)
        assertTrue(aimRightTest.isShotPlayable)

        // A stun hit leaves close to the tangent into the right rail; over this 0.6 m that takes a tip below centre
        // and enough speed for the backspin to survive.
        val rightSpeed = 2.2
        fun stunTipWithSide(side: Double): TipOffset = assertNotNull(
            SpinPhysics.tipForImpactState(
                CueBallSpinPreset.STUN, rightSpeed, aimRightTest.cueToGhostDistanceMeters, table, sideRatio = side,
            ),
        )
        val rightCenter = SpinPhysics.traceCueBallPathWithSpin(
            aim = aimRightTest,
            cueSpeedMps = rightSpeed,
            tip = stunTipWithSide(0.0),
            table = table,
            ballRadiusMeters = r,
        )
        assertNull(rightCenter.scratchPocketId)
        assertTrue(rightCenter.cushionContacts >= 1, "Expected cue ball to rebound off the right rail")
        assertEnglishActsAtFirstCushion(
            layout = TableLayout(table, listOf(cueRightTest, objRightTest)),
            target = objRightTest,
            aim = aimRightTest,
            speed = rightSpeed,
            running = stunTipWithSide(-0.32),
            plain = stunTipWithSide(0.0),
            reverse = stunTipWithSide(0.32),
        )
    }

    /**
     * At the first cushion: the english that survived the cloth keeps the sign of the tip, friction along the rail puts
     * running english on every ball, and the ball that brought running english leaves with the most, the reverse ball
     * with the least.
     */
    private fun assertEnglishActsAtFirstCushion(
        layout: TableLayout,
        target: Ball,
        aim: com.runout.engine.physics.AimGeometry,
        speed: Double,
        running: TipOffset,
        plain: TipOffset,
        reverse: TipOffset,
    ) {
        val (run, none, rev) = listOf(running, plain, reverse).map { tip ->
            TableCollisionSimulator.simulatePostImpact(layout, target, aim, speed, tip)
                .cueBallEvents.first { it.kind == CueBallEventKind.CUSHION }
        }
        val report = "running $run, plain $none, reverse $rev"
        val runningSign = kotlin.math.sign(running.sideRatio)
        assertTrue(run.sideSpinBefore * runningSign > 0.0 && rev.sideSpinBefore * runningSign < 0.0, report)
        assertTrue(none.sideSpinAfter * runningSign > 0.0, report)
        assertTrue(run.sideSpinAfter * runningSign > none.sideSpinAfter * runningSign, report)
        assertTrue(none.sideSpinAfter * runningSign > rev.sideSpinAfter * runningSign, report)
    }

    @Test
    fun traceCueBallPathWithSpinDetectsScratchAndMultiBounceTrajectories() {
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)
        val scratchObj = Ball(number = 9, position = Vec2(2.10, 1.27 - (2.54 - 2.10) * 0.5), radiusMeters = r)
        val toFootRight = (footRight.mouthCenter - scratchObj.position).normalized()
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
        // A stun hit sends the cue ball close to the tangent line, here placed to run into FOOT_RIGHT; over 0.6 m that
        // takes a tip below centre. The stroke is firm (3.0 m/s) so that stroke and tip errors leave most of the
        // sampled shots still sliding at contact rather than drawing or rolling.
        val scratchSpeed = 3.0
        val setup = StunIntoCornerFixture.build(table, objectBallNumber = 1, cueSpeedMps = scratchSpeed)
        val objectBall = setup.objectBall
        val cueBall = setup.cueBall
        val layout = TableLayout(table, listOf(cueBall, objectBall))
        val aim = GhostBallAiming.computeAimGeometry(cueBall, objectBall, footLeft, layout.balls, table = table)

        val stunTip = assertNotNull(
            SpinPhysics.tipForImpactState(CueBallSpinPreset.STUN, scratchSpeed, aim.cueToGhostDistanceMeters, table),
        )
        val scratchRiskResult = ExecutionErrorModel.evaluateShotUncertainty(
            layout = layout,
            targetBall = objectBall,
            pocket = footLeft,
            aim = aim,
            cueSpeedMps = scratchSpeed,
            tip = stunTip,
        )
        assertTrue(
            scratchRiskResult.scratchProbability >= 0.28,
            "Scratching nominal stun trajectory must yield high scratchProbability, got ${scratchRiskResult.scratchProbability}",
        )

        // Long straight shot with insufficient speed (0.35 m/s over ~1.4 m) cannot reach the pocket
        val footRight = table.pocketById(PocketId.FOOT_RIGHT)
        val longObj = Ball(number = 3, position = Vec2(1.40, 0.635), radiusMeters = r)
        val dirToPocket = (footRight.mouthCenter - longObj.position).normalized()
        val longCue = Ball(number = 0, position = longObj.position - dirToPocket * 0.50, radiusMeters = r)
        val longLayout = TableLayout(table, listOf(longCue, longObj))
        val longAim = GhostBallAiming.computeAimGeometry(longCue, longObj, footRight, longLayout.balls, table = table)

        val tooSoft = ExecutionErrorModel.evaluateShotUncertainty(longLayout, longObj, footRight, longAim, cueSpeedMps = 0.35, tip = TipOffset())
        val goodPace = ExecutionErrorModel.evaluateShotUncertainty(longLayout, longObj, footRight, longAim, cueSpeedMps = 2.0, tip = TipOffset())
        assertTrue(tooSoft.makeProbability < 0.15, "Too-soft shot unable to reach pocket must fail (${tooSoft.makeProbability})")
        assertTrue(goodPace.makeProbability > tooSoft.makeProbability + 0.30)
    }
}
