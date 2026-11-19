package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.AssumedSurfacePreset
import com.runout.engine.model.Ball
import com.runout.engine.model.ClothParams
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.GRAVITY_M_S2
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.initialRollingSpeedForDistance
import com.runout.engine.physics.rollingStoppingDistanceMeters
import com.runout.engine.physics.slideToRollTimeSeconds
import com.runout.engine.physics.stunRollingSpeed
import com.runout.engine.physics.stunStoppingDistanceMeters
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotionPhysicsTest {

    @Test
    fun stunRollingSpeedFollowsFiveSeventhsRule() {
        val v0 = 3.5 // m/s
        val expected = 2.5 // (5/7) * 3.5 = 2.5 m/s
        assertEquals(expected, stunRollingSpeed(v0), 1e-12)
        assertEquals(0.0, stunRollingSpeed(0.0), 1e-12)
    }

    @Test
    fun slideToRollTimeMatchesAnalyticFormula() {
        val u0 = 2.8 // m/s
        val muS = 0.20
        // t = 2 * u0 / (7 * mu_s * g) = 5.6 / (1.4 * 9.81) = 4.0 / 9.81
        val expected = (2.0 * u0) / (7.0 * muS * GRAVITY_M_S2)
        val actual = slideToRollTimeSeconds(u0, muS)
        assertEquals(expected, actual, 1e-12)
    }

    @Test
    fun rollingStoppingDistanceMatchesAnalyticFormula() {
        val v = 1.4 // m/s
        val muR = 0.010
        // d = v^2 / (2 * mu_r * g)
        val expected = (v * v) / (2.0 * muR * GRAVITY_M_S2)
        val actual = rollingStoppingDistanceMeters(v, muR)
        assertEquals(expected, actual, 1e-12)
    }

    @Test
    fun initialRollingSpeedForDistanceIsExactInverseOfStoppingDistance() {
        val muR = 0.012
        val targetDistance = 2.25 // metres
        val speed = initialRollingSpeedForDistance(targetDistance, muR)
        val expectedSpeed = sqrt(2.0 * muR * GRAVITY_M_S2 * targetDistance)
        assertEquals(expectedSpeed, speed, 1e-12)

        val recoveredDistance = rollingStoppingDistanceMeters(speed, muR)
        assertEquals(targetDistance, recoveredDistance, 1e-12)
    }

    @Test
    fun stunStoppingDistanceIsZeroForStraightInAndPositiveForCut() {
        val muS = 0.20
        val muR = 0.010
        val v0 = 2.0

        // Straight-in stun shot transfers all velocity to object ball -> 0 post-impact travel.
        assertEquals(0.0, stunStoppingDistanceMeters(v0, 0.0, muS, muR), 1e-12)

        // Half-ball 30-degree cut: post-impact slide speed = v0 * sin(30°) = 1.0 m/s
        val cutAngle = PI / 6.0
        val vc0 = 1.0
        val expectedSlide = (12.0 * vc0 * vc0) / (49.0 * muS * GRAVITY_M_S2)
        val vr = (5.0 / 7.0) * vc0
        val expectedRoll = (vr * vr) / (2.0 * muR * GRAVITY_M_S2)
        val actual = stunStoppingDistanceMeters(v0, cutAngle, muS, muR)
        assertEquals(expectedSlide + expectedRoll, actual, 1e-12)
        assertTrue(actual > expectedRoll)
    }

    @Test
    fun tablePresetsAndLayoutHelpersMatchSpecification() {
        val nineFoot = TableSpec.nineFootPool()
        assertEquals(2.54, nineFoot.lengthMeters, 1e-9)
        assertEquals(1.27, nineFoot.widthMeters, 1e-9)
        assertEquals(0.028575, nineFoot.ballRadiusMeters, 1e-9)
        assertEquals(6, nineFoot.pockets.size)
        assertEquals(Vec2(0.0, 0.0), nineFoot.pocketById(PocketId.HEAD_LEFT).center)
        assertEquals(Vec2(2.54, 1.27), nineFoot.pocketById(PocketId.FOOT_RIGHT).center)
        assertEquals(Vec2(2.54 * 0.25, 1.27 * 0.5), nineFoot.headSpot())
        assertEquals(Vec2(2.54 * 0.75, 1.27 * 0.5), nineFoot.footSpot())

        val eightFoot = TableSpec.eightFootPool()
        assertEquals(2.24, eightFoot.lengthMeters, 1e-9)
        assertEquals(1.12, eightFoot.widthMeters, 1e-9)

        val sevenFoot = TableSpec.sevenFootPool()
        assertEquals(1.98, sevenFoot.lengthMeters, 1e-9)
        assertEquals(0.99, sevenFoot.widthMeters, 1e-9)

        val snooker = TableSpec.fullSizeSnooker()
        assertEquals(3.569, snooker.lengthMeters, 1e-9)
        assertEquals(1.778, snooker.widthMeters, 1e-9)
        assertEquals(0.02625, snooker.ballRadiusMeters, 1e-9)

        assertTrue(nineFoot.isWithinPlayableBounds(Vec2(1.0, 0.5)))
        assertFalse(nineFoot.isWithinPlayableBounds(Vec2(0.01, 0.5)))

        val cue = Ball(0, Vec2(0.6, 0.6))
        val one = Ball(1, Vec2(1.2, 0.6))
        val eight = Ball(8, Vec2(1.5, 0.6))
        val nine = Ball(9, Vec2(1.8, 0.6))
        assertTrue(cue.isCueBall)
        assertTrue(one.isSolid)
        assertTrue(eight.isEightBall)
        assertTrue(nine.isStripe)

        val layout = TableLayout(nineFoot, listOf(cue, one, eight, nine))
        assertNotNull(layout.cueBall())
        assertEquals(3, layout.objectBalls().size)
        assertFalse(layout.hasOverlappingBalls())

        val overlapping = layout.withBallPosition(1, Vec2(0.62, 0.6))
        assertTrue(overlapping.hasOverlappingBalls())
    }

    @Test
    fun vec2OperationsAreAccurate() {
        val a = Vec2(3.0, 4.0)
        assertEquals(5.0, a.length(), 1e-12)
        assertEquals(25.0, a.lengthSquared(), 1e-12)
        val n = a.normalized()
        assertEquals(1.0, n.length(), 1e-12)

        val x = Vec2(1.0, 0.0)
        val y = x.rotate(PI / 2.0)
        assertTrue(abs(y.x) < 1e-12)
        assertEquals(1.0, y.y, 1e-12)
        assertEquals(PI / 2.0, x.angleTo(y), 1e-12)
        assertEquals(-PI / 2.0, y.angleTo(x), 1e-12)
    }

    @Test
    fun assumedSurfacePresetsProduceExpectedClothParametersAndStoppingDistances() {
        val proFast = ClothParams.fromPreset(AssumedSurfacePreset.PRO_BROADCAST_FAST)
        val standard = ClothParams.fromPreset(AssumedSurfacePreset.STANDARD_POOL_HALL)
        val slowBar = ClothParams.fromPreset(AssumedSurfacePreset.SLOW_BAR_BOX_HEAVY)
        val snooker = ClothParams.fromPreset(AssumedSurfacePreset.SNOOKER_DIRECTIONAL)

        assertEquals(0.0068, proFast.rollingResistanceMuR, 1e-12)
        assertEquals(0.165, proFast.slidingFrictionMuS, 1e-12)
        assertEquals(0.82, proFast.cushionRestitutionEc, 1e-12)

        assertEquals(0.0100, standard.rollingResistanceMuR, 1e-12)
        assertEquals(0.200, standard.slidingFrictionMuS, 1e-12)
        assertEquals(0.75, standard.cushionRestitutionEc, 1e-12)

        assertEquals(0.0145, slowBar.rollingResistanceMuR, 1e-12)
        assertEquals(0.240, slowBar.slidingFrictionMuS, 1e-12)
        assertEquals(0.66, slowBar.cushionRestitutionEc, 1e-12)

        assertEquals(0.0082, snooker.rollingResistanceMuR, 1e-12)
        assertEquals(0.180, snooker.slidingFrictionMuS, 1e-12)
        assertEquals(0.78, snooker.cushionRestitutionEc, 1e-12)

        val updatedTable = TableSpec.nineFootPool().withClothParams(proFast)
        assertEquals(proFast, updatedTable.cloth)
        assertEquals(2.54, updatedTable.lengthMeters, 1e-9)

        val speedMps = 1.6
        val fastRollDist = rollingStoppingDistanceMeters(speedMps, proFast.rollingResistanceMuR)
        val standardRollDist = rollingStoppingDistanceMeters(speedMps, standard.rollingResistanceMuR)
        val slowRollDist = rollingStoppingDistanceMeters(speedMps, slowBar.rollingResistanceMuR)

        assertTrue(fastRollDist > standardRollDist)
        assertTrue(standardRollDist > slowRollDist)
        assertTrue(fastRollDist > slowRollDist * 2.0)

        val fastStunDist = stunStoppingDistanceMeters(
            initialCueSpeedMps = 2.2,
            cutAngleRad = PI / 6.0,
            muS = proFast.slidingFrictionMuS,
            muR = proFast.rollingResistanceMuR,
        )
        val slowStunDist = stunStoppingDistanceMeters(
            initialCueSpeedMps = 2.2,
            cutAngleRad = PI / 6.0,
            muS = slowBar.slidingFrictionMuS,
            muR = slowBar.rollingResistanceMuR,
        )
        assertTrue(fastStunDist > slowStunDist)
    }

    @Test
    fun withAdjustedRollAndRailClampsOutOfRangeInputsAndScalesSlidingFriction() {
        val clampedLow = ClothParams().withAdjustedRollAndRail(
            rollingResistanceMuR = 0.001,
            cushionRestitutionEc = 0.20,
        )
        assertEquals(0.004, clampedLow.rollingResistanceMuR, 1e-12)
        assertEquals(0.50, clampedLow.cushionRestitutionEc, 1e-12)
        assertEquals(0.12 + 0.004 * 8.0, clampedLow.slidingFrictionMuS, 1e-12)

        val clampedHigh = ClothParams().withAdjustedRollAndRail(
            rollingResistanceMuR = 0.080,
            cushionRestitutionEc = 1.15,
        )
        assertEquals(0.025, clampedHigh.rollingResistanceMuR, 1e-12)
        assertEquals(0.92, clampedHigh.cushionRestitutionEc, 1e-12)
        assertEquals(0.12 + 0.025 * 8.0, clampedHigh.slidingFrictionMuS, 1e-12)

        val proBase = ClothParams.fromPreset(AssumedSurfacePreset.PRO_BROADCAST_FAST)
        val rollOnlyAdjusted = proBase.withAdjustedRollAndRail(rollingResistanceMuR = 0.0115)
        assertEquals(0.0115, rollOnlyAdjusted.rollingResistanceMuR, 1e-12)
        assertEquals(0.82, rollOnlyAdjusted.cushionRestitutionEc, 1e-12)
        assertEquals(0.12 + 0.0115 * 8.0, rollOnlyAdjusted.slidingFrictionMuS, 1e-12)

        val railOnlyAdjusted = proBase.withAdjustedRollAndRail(cushionRestitutionEc = 0.70)
        assertEquals(0.0068, railOnlyAdjusted.rollingResistanceMuR, 1e-12)
        assertEquals(0.70, railOnlyAdjusted.cushionRestitutionEc, 1e-12)
        assertEquals(0.12 + 0.0068 * 8.0, railOnlyAdjusted.slidingFrictionMuS, 1e-12)

        val nanAdjusted = proBase.withAdjustedRollAndRail(
            rollingResistanceMuR = Double.NaN,
            cushionRestitutionEc = Double.NaN,
        )
        assertEquals(0.0068, nanAdjusted.rollingResistanceMuR, 1e-12)
        assertEquals(0.82, nanAdjusted.cushionRestitutionEc, 1e-12)
    }
}
