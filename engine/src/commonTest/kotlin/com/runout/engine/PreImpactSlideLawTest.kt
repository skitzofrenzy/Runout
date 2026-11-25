package com.runout.engine

import com.runout.engine.model.AssumedSurfacePreset
import com.runout.engine.model.ClothParams
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.SpinPhysics.ImpactSpinState
import com.runout.engine.physics.SpinPhysics.SpinRegimeChange
import com.runout.engine.physics.TipOffset
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cue ball's motion from the tip strike to the object ball: one sliding-then-rolling law for every tip.
 */
class PreImpactSlideLawTest {

    private val g = MotionPhysics.GRAVITY_M_S2
    private val defaultTable = TableSpec.nineFootPool()
    private val tables: List<TableSpec> = AssumedSurfacePreset.entries.map {
        TableSpec.nineFootPool(ClothParams.fromPreset(it))
    }
    private val speeds = listOf(0.5, 0.8, 1.2, 2.0, 2.8, 4.0)

    private fun tip(vertical: Double, side: Double = 0.0) = TipOffset(sideRatio = side, verticalRatio = vertical)

    private fun pre(speed: Double, vertical: Double, distance: Double, table: TableSpec = defaultTable) =
        SpinPhysics.computePreImpactCueState(speed, tip(vertical), distance, table)

    // ---------------------------------------------------------------------------------------------
    // Strike
    // ---------------------------------------------------------------------------------------------

    @Test
    fun strikeSpinIsFiveHalvesOfTheTipHeightAndNaturalRollSitsAtTwoFifthsOfTheRadius() {
        for (b in listOf(-0.5, -0.3, -0.1, 0.0, 0.1, 0.25, 0.4, 0.5)) {
            assertEquals(2.5 * b, SpinPhysics.initialSpinFactorForTip(tip(b)), 1e-12, "b = $b")
        }
        assertEquals(1.0, SpinPhysics.initialSpinFactorForTip(tip(0.4)), 1e-12)
        assertEquals(-1.25, SpinPhysics.initialSpinFactorForTip(tip(-0.5)), 1e-12)
        // A tip beyond the miscue limit is clamped onto it.
        assertEquals(-1.25, SpinPhysics.initialSpinFactorForTip(tip(-0.9)), 1e-12)
    }

    // ---------------------------------------------------------------------------------------------
    // Analytic checks
    // ---------------------------------------------------------------------------------------------

    @Test
    fun centreBallSlideEndsAtFiveSeventhsOfTheStrokeSpeedAfterTwelveOverFortyNineLength() {
        for (table in tables) {
            val muS = table.cloth.slidingFrictionMuS
            for (v0 in speeds) {
                val ends = SpinPhysics.preImpactRegimeBoundaries(v0, tip(0.0), table)
                    .single { it.change == SpinRegimeChange.SLIDE_ENDS }
                assertEquals(5.0 / 7.0 * v0, ends.speedMps, 1e-9, "v_roll at $v0 m/s")
                assertEquals(12.0 * v0 * v0 / (49.0 * muS * g), ends.distanceMeters, 1e-9, "d_slide at $v0 m/s")
                assertEquals(1.0, ends.spinFactorK, 1e-12)
            }
        }
    }

    @Test
    fun slideEndsAtFivePlusTwoK0OverSevenForEveryTip() {
        for (table in tables) {
            val muS = table.cloth.slidingFrictionMuS
            for (v0 in speeds) {
                for (b in listOf(-0.5, -0.38, -0.2, -0.05, 0.05, 0.2, 0.3)) {
                    val k0 = 2.5 * b
                    val vRoll = v0 * (5.0 + 2.0 * k0) / 7.0
                    val ends = SpinPhysics.preImpactRegimeBoundaries(v0, tip(b), table)
                        .single { it.change == SpinRegimeChange.SLIDE_ENDS }
                    assertEquals(vRoll, ends.speedMps, 1e-9, "v_roll (b = $b, $v0 m/s)")
                    assertEquals(abs(v0 * v0 - vRoll * vRoll) / (2.0 * muS * g), ends.distanceMeters, 1e-9)
                }
            }
        }
    }

    @Test
    fun speedAndSpinFollowTheSlidingLawWhileSlippingAndTheRollingLawAfterwards() {
        for (table in tables) {
            val muS = table.cloth.slidingFrictionMuS
            val muR = table.cloth.rollingResistanceMuR
            for (v0 in speeds) {
                for (b in listOf(-0.5, -0.3, 0.0, 0.2)) {
                    val k0 = 2.5 * b
                    val vRoll = v0 * (5.0 + 2.0 * k0) / 7.0
                    val dSlide = (v0 * v0 - vRoll * vRoll) / (2.0 * muS * g)
                    // While slipping: v^2 = v0^2 - 2 muS g d and R*omega = k0 v0 + 5/2 (v0 - v).
                    for (fraction in listOf(0.1, 0.5, 0.9)) {
                        val d = fraction * dSlide
                        val state = pre(v0, b, d, table)
                        val v = sqrt(v0 * v0 - 2.0 * muS * g * d)
                        assertEquals(v, state.impactSpeedMps, 1e-9, "v at ${fraction * 100}% of the slide")
                        assertEquals((k0 * v0 + 2.5 * (v0 - v)) / v, state.impactSpinFactorK, 1e-9)
                        assertEquals(d, state.travelledDistanceMeters, 1e-12)
                    }
                    // Afterwards: pure rolling, v^2 = v_roll^2 - 2 muR g (d - d_slide).
                    val rollingDistance = MotionPhysics.rollingStoppingDistanceMeters(vRoll, muR, g) * 0.5
                    val state = pre(v0, b, dSlide + rollingDistance, table)
                    assertEquals(sqrt(vRoll * vRoll - 2.0 * muR * g * rollingDistance), state.impactSpeedMps, 1e-9)
                    assertEquals(1.0, state.impactSpinFactorK, 1e-12)
                    assertEquals(ImpactSpinState.NATURAL_FORWARD_ROLL, state.state)
                }
            }
        }
    }

    @Test
    fun aBallThatRollsOutBeforeContactReportsWhereItStopped() {
        val v0 = 1.0
        val state = pre(v0, 0.0, 5.0)
        val vRoll = 5.0 / 7.0 * v0
        val expected = 12.0 * v0 * v0 / (49.0 * defaultTable.cloth.slidingFrictionMuS * g) +
            MotionPhysics.rollingStoppingDistanceMeters(vRoll, defaultTable.cloth.rollingResistanceMuR, g)
        assertEquals(ImpactSpinState.STOPPED_SHORT, state.state)
        assertEquals(0.0, state.impactSpeedMps, 0.0)
        assertEquals(expected, state.travelledDistanceMeters, 1e-9)
    }

    // ---------------------------------------------------------------------------------------------
    // Continuity and energy
    // ---------------------------------------------------------------------------------------------

    @Test
    fun impactSpeedAndSpinVaryContinuouslyWithTipHeightAcrossCentre() {
        // Samples straddle the former stun band (|k0| <= 0.08, i.e. |b| <= 0.032) with no jump anywhere.
        val step = 0.001
        for (v0 in listOf(1.2, 2.0, 3.0)) {
            for (distance in listOf(0.3, 0.6, 1.0, 1.5)) {
                var previous = pre(v0, -0.06, distance)
                var b = -0.06 + step
                while (b <= 0.06 + 1e-9) {
                    val current = pre(v0, b, distance)
                    // dk0/db = 5/2, so one step moves k0 by 0.0025; k and v respond at most about that much.
                    assertTrue(
                        abs(current.impactSpinFactorK - previous.impactSpinFactorK) <= 0.01,
                        "k jumps at b = $b ($v0 m/s, $distance m): ${previous.impactSpinFactorK} -> ${current.impactSpinFactorK}",
                    )
                    assertTrue(
                        abs(current.impactSpeedMps - previous.impactSpeedMps) <= 0.005 * v0,
                        "speed jumps at b = $b ($v0 m/s, $distance m): ${previous.impactSpeedMps} -> ${current.impactSpeedMps}",
                    )
                    previous = current
                    b += step
                }
            }
        }
    }

    @Test
    fun impactSpinFactorIsMonotoneInTipHeight() {
        for (distance in listOf(0.2, 0.6, 1.2)) {
            var previousK = -Double.MAX_VALUE
            var b = -0.5
            while (b <= 0.5 + 1e-9) {
                val k = pre(2.4, b, distance).impactSpinFactorK
                assertTrue(k >= previousK - 1e-12, "k decreases at b = $b over $distance m")
                previousK = k
                b += 0.02
            }
        }
    }

    @Test
    fun speedNeverExceedsTheStrokeSpeedUnlessTheBallHasOverspinAndNeverIncreasesWithDistance() {
        for (table in tables) {
            for (v0 in speeds) {
                for (b in listOf(-0.5, -0.3, -0.1, 0.0, 0.1, 0.3, 0.4)) {
                    var previous = v0
                    var d = 0.0
                    while (d <= 4.0) {
                        val v = pre(v0, b, d, table).impactSpeedMps
                        assertTrue(v <= v0 + 1e-9, "speed $v exceeds $v0 (b = $b, d = $d)")
                        assertTrue(v <= previous + 1e-9, "speed rises with distance (b = $b, d = $d)")
                        assertTrue(v >= 0.0)
                        previous = v
                        d += 0.05
                    }
                }
            }
        }
    }

    @Test
    fun drawNeverReversesTheBallBeforeContact() {
        // Strongest draw, soft and firm: the speed along the line of travel stays positive until the ball stops.
        for (v0 in listOf(0.6, 1.0, 2.0, 4.0)) {
            var d = 0.01
            while (d < 3.0) {
                val state = pre(v0, -0.5, d)
                if (state.state != ImpactSpinState.STOPPED_SHORT) {
                    assertTrue(state.impactSpeedMps > 0.0, "ball reversed at $d m ($v0 m/s)")
                }
                d += 0.05
            }
        }
    }

    @Test
    fun overspinGainsSpeedUpToTheRollingSpeedThenRollsDown() {
        val v0 = 2.0
        val b = 0.5
        val k0 = 2.5 * b
        val vRoll = v0 * (5.0 + 2.0 * k0) / 7.0
        assertTrue(k0 > 1.0 && vRoll > v0)
        val dSlide = (vRoll * vRoll - v0 * v0) / (2.0 * defaultTable.cloth.slidingFrictionMuS * g)
        var previous = v0
        for (fraction in listOf(0.1, 0.3, 0.6, 0.9, 0.999)) {
            val state = pre(v0, b, fraction * dSlide)
            assertTrue(state.impactSpeedMps > previous, "overspin must keep speeding up (${fraction * 100}%)")
            assertTrue(state.impactSpeedMps < vRoll)
            assertTrue(state.impactSpinFactorK in 1.0..k0, "k = ${state.impactSpinFactorK}")
            assertEquals(ImpactSpinState.NATURAL_FORWARD_ROLL, state.state)
            previous = state.impactSpeedMps
        }
        val atEnd = pre(v0, b, dSlide)
        assertEquals(vRoll, atEnd.impactSpeedMps, 1e-9)
        assertEquals(1.0, atEnd.impactSpinFactorK, 1e-12)
        val beyond = pre(v0, b, dSlide + 0.5)
        assertTrue(beyond.impactSpeedMps < vRoll)
    }

    // ---------------------------------------------------------------------------------------------
    // Stop shots
    // ---------------------------------------------------------------------------------------------

    @Test
    fun centreBallOverLongerDistancesArrivesRollingNotStun() {
        val atOnePointFive = pre(2.0, 0.0, 1.5)
        assertEquals(ImpactSpinState.NATURAL_FORWARD_ROLL, atOnePointFive.state)
        assertEquals(1.0, atOnePointFive.impactSpinFactorK, 1e-12)
        // 0.25 m in, a centre-ball hit at 2 m/s has not finished slipping: it carries part of a roll.
        val early = pre(2.0, 0.0, 0.25)
        assertTrue(early.impactSpinFactorK in 0.18..0.92, "k = ${early.impactSpinFactorK}")
        assertEquals(ImpactSpinState.PARTIAL_FORWARD_ROLL, early.state)
        // Stun at contact is only a short-range thing: a 10 cm shot.
        assertEquals(ImpactSpinState.STUN_SLIDE, pre(2.0, 0.0, 0.10).state)
    }

    @Test
    fun theTipForAStopShotIsBelowCentreGetsLowerWithDistanceAndHigherWithSpeed() {
        fun stopTip(speed: Double, distance: Double): Double = assertNotNull(
            SpinPhysics.verticalTipRatioForImpactSpin(0.0, speed, distance, defaultTable),
            "stop shot at $speed m/s over $distance m",
        )
        val distances = listOf(0.2, 0.35, 0.5, 0.65, 0.75)
        val atTwo = distances.map { stopTip(2.0, it) }
        assertTrue(atTwo.all { it < 0.0 }, "below centre: $atTwo")
        assertTrue(atTwo.zipWithNext().all { (a, b) -> b < a }, "lower with distance: $atTwo")

        val speedsForOnePointTwo = listOf(2.6, 3.0, 3.5, 4.0)
        val overSpeed = speedsForOnePointTwo.map { stopTip(it, 1.2) }
        assertTrue(overSpeed.all { it < 0.0 }, "below centre: $overSpeed")
        assertTrue(overSpeed.zipWithNext().all { (a, b) -> b > a }, "higher with speed: $overSpeed")

        // The tip really gives no spin at contact.
        for (distance in distances) {
            val state = pre(2.0, stopTip(2.0, distance), distance)
            assertEquals(0.0, state.impactSpinFactorK, 1e-9, "k at $distance m")
            assertEquals(ImpactSpinState.STUN_SLIDE, state.state)
        }
    }

    @Test
    fun aStopShotThatNeedsMoreBackspinThanATipCanGiveIsNotReachable() {
        // At the miscue limit (k0 = -1.25) the ball has no spin when v = v0 / 2, after 3/8 * v0^2 / (muS * g) of slide.
        val v0 = 2.0
        val limit = 0.375 * v0 * v0 / (defaultTable.cloth.slidingFrictionMuS * g)
        assertNotNull(SpinPhysics.verticalTipRatioForImpactSpin(0.0, v0, limit - 0.01, defaultTable))
        assertNull(SpinPhysics.verticalTipRatioForImpactSpin(0.0, v0, limit + 0.01, defaultTable))
        assertNull(SpinPhysics.verticalTipRatioForImpactSpin(0.0, v0, 1.5, defaultTable), "1.5 m at 2 m/s")
        assertNull(SpinPhysics.verticalTipRatioForImpactSpin(0.0, v0, 2.0, defaultTable), "2.0 m at 2 m/s")
        // A firmer stroke reaches them.
        val firm = assertNotNull(SpinPhysics.verticalTipRatioForImpactSpin(0.0, 3.5, 1.5, defaultTable))
        assertTrue(firm in -0.5..0.0)
        assertEquals(0.0, pre(3.5, firm, 1.5).impactSpinFactorK, 1e-9)
        // No stroke, no shot.
        assertNull(SpinPhysics.verticalTipRatioForImpactSpin(0.0, 0.0, 0.3, defaultTable))
    }

    @Test
    fun solvedTipsRoundTripThroughThePreImpactModel() {
        for (table in tables) {
            for (targetK in listOf(-0.5, -0.2, 0.0, 0.18, 0.5, 0.9)) {
                for (v0 in listOf(1.5, 2.5, 3.5)) {
                    for (d in listOf(0.15, 0.4, 0.8)) {
                        val b = SpinPhysics.verticalTipRatioForImpactSpin(targetK, v0, d, table) ?: continue
                        assertTrue(abs(b) <= TipOffset.MAX_SAFE_OFFSET_RATIO + 1e-9)
                        val state = pre(v0, b, d, table)
                        assertEquals(targetK, state.impactSpinFactorK, 1e-9, "k (target $targetK, $v0 m/s, $d m)")
                    }
                }
            }
        }
    }

    @Test
    fun englishNarrowsTheVerticalRangeOfTheSolver() {
        // With 0.3 of side english only |b| <= 0.4 is inside the miscue limit.
        val withoutSide = assertNotNull(SpinPhysics.verticalTipRatioForImpactSpin(0.0, 2.0, 0.72, defaultTable))
        assertTrue(withoutSide < -0.4)
        assertNull(SpinPhysics.verticalTipRatioForImpactSpin(0.0, 2.0, 0.72, defaultTable, sideRatio = 0.3))
        assertNotNull(SpinPhysics.verticalTipRatioForImpactSpin(0.0, 2.0, 0.5, defaultTable, sideRatio = 0.3))
    }

    @Test
    fun naturalRollTipIsTheCentreMostTipWhoseSlideHasEnded() {
        // Very close: the strike itself must be natural roll, 0.4 R.
        assertEquals(0.4, assertNotNull(SpinPhysics.verticalTipRatioForImpactSpin(1.0, 2.0, 1e-6, defaultTable)), 1e-5)
        // Farther: a lower tip suffices, and a centre-ball hit is enough once its own slide is over.
        val mid = assertNotNull(SpinPhysics.verticalTipRatioForImpactSpin(1.0, 2.0, 0.3, defaultTable))
        assertTrue(mid in 0.0..0.4)
        assertEquals(1.0, pre(2.0, mid, 0.3 + 1e-9).impactSpinFactorK, 1e-6)
        assertEquals(0.0, assertNotNull(SpinPhysics.verticalTipRatioForImpactSpin(1.0, 2.0, 1.2, defaultTable)), 0.0)
        // Slightly closer than the slide end is not yet rolling.
        assertTrue(pre(2.0, mid, 0.25).impactSpinFactorK < 1.0)
    }

    // ---------------------------------------------------------------------------------------------
    // Presets as impact states
    // ---------------------------------------------------------------------------------------------

    @Test
    fun eachPresetGivesItsNamedStateAtContactWhereAnyTipCan() {
        for (v0 in listOf(2.0, 2.6, 3.4)) {
            for (d in listOf(0.2, 0.4, 0.6)) {
                val stun = SpinPhysics.tipForImpactState(CueBallSpinPreset.STUN, v0, d, defaultTable)
                val follow = SpinPhysics.tipForImpactState(CueBallSpinPreset.FOLLOW, v0, d, defaultTable)
                val draw = SpinPhysics.tipForImpactState(CueBallSpinPreset.DRAW, v0, d, defaultTable)
                val stunState = assertNotNull(stun, "stun at $v0 m/s over $d m")
                assertEquals(ImpactSpinState.STUN_SLIDE, SpinPhysics.computePreImpactCueState(v0, stunState, d, defaultTable).state)
                val followState = assertNotNull(follow)
                assertEquals(
                    ImpactSpinState.NATURAL_FORWARD_ROLL,
                    SpinPhysics.computePreImpactCueState(v0, followState, d, defaultTable).state,
                )
                assertTrue(followState.verticalRatio >= 0.0)
                val drawState = assertNotNull(draw, "draw at $v0 m/s over $d m")
                assertEquals(ImpactSpinState.BACKSPIN_DRAW, SpinPhysics.computePreImpactCueState(v0, drawState, d, defaultTable).state)
                assertTrue(drawState.verticalRatio < stunState.verticalRatio, "draw tip is lower than stun tip")
            }
        }
    }

    @Test
    fun drawPresetFallsBackToTheStrongestTipThenToNotReachable() {
        val v0 = 2.0
        // 0.6 m: the firm draw (k = -0.5) is within reach, 0.62 m is not but the strongest tip still draws.
        val firm = assertNotNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.DRAW, v0, 0.55, defaultTable))
        assertEquals(SpinPhysics.DRAW_PRESET_IMPACT_K, pre(v0, firm.verticalRatio, 0.55).impactSpinFactorK, 1e-9)
        val strongest = assertNotNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.DRAW, v0, 0.68, defaultTable))
        assertEquals(-TipOffset.MAX_SAFE_OFFSET_RATIO, strongest.verticalRatio, 1e-12)
        assertEquals(ImpactSpinState.BACKSPIN_DRAW, pre(v0, strongest.verticalRatio, 0.68).state)
        assertNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.DRAW, v0, 1.5, defaultTable))
        assertNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.STUN, v0, 1.5, defaultTable))
        // Natural roll is always within reach.
        assertNotNull(SpinPhysics.tipForImpactState(CueBallSpinPreset.FOLLOW, v0, 1.5, defaultTable))
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    @Test
    fun motionHelpersAgreeWithTheClosedFormLaws() {
        val muR = 0.01
        assertEquals(
            sqrt(2.0 * 2.0 - 2.0 * muR * g * 0.7),
            MotionPhysics.rollingSpeedAfterDistanceMps(2.0, 0.7, muR, g),
            1e-12,
        )
        assertEquals(0.0, MotionPhysics.rollingSpeedAfterDistanceMps(1.0, 100.0, muR, g), 0.0)
        assertEquals(2.0, MotionPhysics.rollingSpeedAfterDistanceMps(2.0, 0.0, muR, g), 1e-12)

        assertEquals(MotionPhysics.stunRollingSpeed(3.0), MotionPhysics.slidingEndSpeedMps(3.0, 0.0), 1e-12)
        assertEquals(3.0, MotionPhysics.slidingEndSpeedMps(3.0, 1.0), 1e-12)
        assertEquals(3.0 * 2.5 / 7.0, MotionPhysics.slidingEndSpeedMps(3.0, -1.25), 1e-12)

        assertEquals(
            12.0 * 2.0 * 2.0 / (49.0 * 0.2 * g),
            MotionPhysics.slidingDistanceBetweenSpeedsMeters(2.0, 5.0 / 7.0 * 2.0, 0.2, g),
            1e-12,
        )
        assertEquals(
            MotionPhysics.slidingDistanceBetweenSpeedsMeters(2.0, 1.0, 0.2, g),
            MotionPhysics.slidingDistanceBetweenSpeedsMeters(1.0, 2.0, 0.2, g),
            0.0,
        )
        assertEquals(sqrt(4.0 - 2.0 * 0.2 * g * 0.3), MotionPhysics.slidingSpeedAfterDistanceMps(2.0, 0.0, 0.3, 0.2, g), 1e-12)
        assertEquals(sqrt(4.0 + 2.0 * 0.2 * g * 0.3), MotionPhysics.slidingSpeedAfterDistanceMps(2.0, 1.2, 0.3, 0.2, g), 1e-12)
    }
}
