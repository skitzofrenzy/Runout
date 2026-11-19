package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.snooker.SnookerBallType
import com.runout.engine.snooker.SnookerScoreState
import com.runout.engine.snooker.SnookerSpots
import com.runout.engine.snooker.SnookerStrategyPlanner
import com.runout.engine.snooker.SnookerTacticalMode
import com.runout.engine.tools.BreakSpeedEstimator
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnookerAndBreakSpeedTest {

    private val snookerTable = TableSpec.fullSizeSnooker()
    private val sr = snookerTable.ballRadiusMeters

    private fun sBall(number: Int, pos: Vec2): Ball =
        Ball(number = number, position = pos, radiusMeters = sr)

    private fun sBall(number: Int, x: Double, y: Double): Ball =
        sBall(number = number, pos = Vec2(x, y))

    @Test
    fun scoreStateCalculates147MaxAnd27EndgamePoints() {
        val fullStart = SnookerScoreState()
        assertEquals(147, fullStart.pointsRemainingOnTable)
        assertEquals(0, fullStart.scoreDifferential)
        assertEquals(0, fullStart.pointsAhead)
        assertEquals(0, fullStart.deficitPoints)
        assertFalse(fullStart.needsSnookers)
        assertEquals(0, fullStart.snookersRequiredCount)
        assertFalse(fullStart.isFrameBallAvailable)

        val oneRedOnColour = SnookerScoreState(redsRemaining = 1, onColourAfterRed = true)
        assertEquals(42, oneRedOnColour.pointsRemainingOnTable)

        val lastRedColourPending = SnookerScoreState(
            redsRemaining = 0,
            onColourAfterRed = true,
            lowestEndgameColourValue = 2,
        )
        assertEquals(34, lastRedColourPending.pointsRemainingOnTable)

        val endgameStart = SnookerScoreState(
            redsRemaining = 0,
            onColourAfterRed = false,
            lowestEndgameColourValue = 2,
        )
        assertEquals(27, endgameStart.pointsRemainingOnTable)

        val pinkAndBlackOnly = SnookerScoreState(
            redsRemaining = 0,
            onColourAfterRed = false,
            lowestEndgameColourValue = 6,
        )
        assertEquals(13, pinkAndBlackOnly.pointsRemainingOnTable)

        val cleared = SnookerScoreState(
            redsRemaining = 0,
            onColourAfterRed = false,
            lowestEndgameColourValue = 8,
        )
        assertEquals(0, cleared.pointsRemainingOnTable)
    }

    @Test
    fun scoreStateDetectsSnookersRequiredAndFrameBall() {
        val trailingBy35With27Left = SnookerScoreState(
            playerScore = 20,
            opponentScore = 55,
            redsRemaining = 0,
            onColourAfterRed = false,
            lowestEndgameColourValue = 2,
        )
        assertEquals(27, trailingBy35With27Left.pointsRemainingOnTable)
        assertEquals(-35, trailingBy35With27Left.scoreDifferential)
        assertEquals(0, trailingBy35With27Left.pointsAhead)
        assertEquals(35, trailingBy35With27Left.deficitPoints)
        assertTrue(trailingBy35With27Left.needsSnookers)
        assertEquals(2, trailingBy35With27Left.snookersRequiredCount)
        assertFalse(trailingBy35With27Left.isFrameBallAvailable)

        val tiedOnRemaining = SnookerScoreState(
            playerScore = 28,
            opponentScore = 55,
            redsRemaining = 0,
            lowestEndgameColourValue = 2,
        )
        assertFalse(tiedOnRemaining.needsSnookers)
        assertEquals(0, tiedOnRemaining.snookersRequiredCount)

        val oneSnookerNeeded = SnookerScoreState(
            playerScore = 27,
            opponentScore = 55,
            redsRemaining = 0,
            lowestEndgameColourValue = 2,
        )
        assertTrue(oneSnookerNeeded.needsSnookers)
        assertEquals(1, oneSnookerNeeded.snookersRequiredCount)

        val frameBallReady = SnookerScoreState(
            playerScore = 50,
            opponentScore = 20,
            redsRemaining = 1,
            onColourAfterRed = false,
        )
        assertEquals(35, frameBallReady.pointsRemainingOnTable)
        assertTrue(frameBallReady.isFrameBallAvailable)

        val notFrameBallYet = frameBallReady.copy(playerScore = 47)
        assertFalse(notFrameBallYet.isFrameBallAvailable)
    }

    @Test
    fun snookerSpotsMatchWpbsaFullSizeScalingAndBallNumbers() {
        val brown = SnookerSpots.spotForColour(SnookerBallType.BROWN, snookerTable)
        val yellow = SnookerSpots.spotForColour(SnookerBallType.YELLOW, snookerTable)
        val green = SnookerSpots.spotForColour(SnookerBallType.GREEN, snookerTable)
        val blue = SnookerSpots.spotForColour(SnookerBallType.BLUE, snookerTable)
        val pink = SnookerSpots.spotForColour(SnookerBallType.PINK, snookerTable)
        val black = SnookerSpots.spotForColour(SnookerBallType.BLACK, snookerTable)
        val redApex = SnookerSpots.spotForColour(SnookerBallType.RED, snookerTable)

        assertEquals(Vec2(0.737, snookerTable.widthMeters * 0.5), brown)
        assertEquals(Vec2(0.737, snookerTable.widthMeters * 0.5 + 0.292), yellow)
        assertEquals(Vec2(0.737, snookerTable.widthMeters * 0.5 - 0.292), green)
        assertEquals(Vec2(snookerTable.lengthMeters * 0.5, snookerTable.widthMeters * 0.5), blue)
        assertEquals(Vec2(snookerTable.lengthMeters * 0.75, snookerTable.widthMeters * 0.5), pink)
        assertEquals(Vec2(snookerTable.lengthMeters - 0.324, snookerTable.widthMeters * 0.5), black)
        assertEquals(
            Vec2(snookerTable.lengthMeters * 0.75 + 2.0 * sr + 0.002, snookerTable.widthMeters * 0.5),
            redApex,
        )

        listOf(brown, yellow, green, blue, pink, black, redApex).forEach { spot ->
            assertTrue(snookerTable.isWithinPlayableBounds(spot, sr))
        }

        val scaledTable = TableSpec.nineFootPool()
        val scaledBlack = SnookerSpots.spotForColour(SnookerBallType.BLACK, scaledTable)
        val scaledBrown = SnookerSpots.spotForColour(SnookerBallType.BROWN, scaledTable)
        val scaledBlue = SnookerSpots.spotForColour(SnookerBallType.BLUE, scaledTable)
        assertTrue(scaledBrown.x < scaledBlue.x && scaledBlue.x < scaledBlack.x)
        assertTrue(scaledTable.isWithinPlayableBounds(scaledBlack, scaledTable.ballRadiusMeters))
        assertTrue(scaledTable.isWithinPlayableBounds(scaledBrown, scaledTable.ballRadiusMeters))

        assertEquals(SnookerBallType.RED, SnookerSpots.ballTypeForNumber(1))
        assertEquals(SnookerBallType.RED, SnookerSpots.ballTypeForNumber(8))
        assertEquals(SnookerBallType.RED, SnookerSpots.ballTypeForNumber(22))
        assertEquals(SnookerBallType.YELLOW, SnookerSpots.ballTypeForNumber(2))
        assertEquals(SnookerBallType.GREEN, SnookerSpots.ballTypeForNumber(3))
        assertEquals(SnookerBallType.BROWN, SnookerSpots.ballTypeForNumber(4))
        assertEquals(SnookerBallType.BLUE, SnookerSpots.ballTypeForNumber(5))
        assertEquals(SnookerBallType.PINK, SnookerSpots.ballTypeForNumber(6))
        assertEquals(SnookerBallType.BLACK, SnookerSpots.ballTypeForNumber(7))
        assertNull(SnookerSpots.ballTypeForNumber(0))
        assertNull(SnookerSpots.ballTypeForNumber(23))
    }

    @Test
    fun plannerBuildsRedColourRedBreakWithRespottedColour() {
        val layout = TableLayout(
            table = snookerTable,
            balls = listOf(
                sBall(0, 2.55, 0.45),
                sBall(1, 3.02, 0.25),
                sBall(8, 3.00, 1.48),
                sBall(2, SnookerSpots.spotForColour(SnookerBallType.YELLOW, snookerTable)),
                sBall(3, SnookerSpots.spotForColour(SnookerBallType.GREEN, snookerTable)),
                sBall(4, SnookerSpots.spotForColour(SnookerBallType.BROWN, snookerTable)),
                sBall(5, SnookerSpots.spotForColour(SnookerBallType.BLUE, snookerTable)),
                sBall(6, SnookerSpots.spotForColour(SnookerBallType.PINK, snookerTable)),
                sBall(7, SnookerSpots.spotForColour(SnookerBallType.BLACK, snookerTable)),
            ),
        )
        assertFalse(layout.hasOverlappingBalls())

        val advice = SnookerStrategyPlanner.evaluateSnookerPosition(
            layout = layout,
            scoreState = SnookerScoreState(redsRemaining = 2, onColourAfterRed = false),
            playerProfile = PlayerProfile(),
            horizonShots = 3,
        )

        assertEquals(SnookerTacticalMode.ATTACK_BREAK_BUILD, advice.recommendedMode)
        assertEquals(3, advice.breakSequence.size)
        assertEquals(SnookerBallType.RED, advice.breakSequence[0].ballType)
        assertNull(advice.breakSequence[0].respottedToSpotMeters)
        assertTrue(advice.breakSequence[0].makeProbability >= 0.52)

        val colourStep = advice.breakSequence[1]
        assertTrue(colourStep.ballType == SnookerBallType.BLACK || colourStep.ballType == SnookerBallType.PINK)
        val respot = assertNotNull(colourStep.respottedToSpotMeters)
        assertTrue(snookerTable.isWithinPlayableBounds(respot, sr))

        assertEquals(SnookerBallType.RED, advice.breakSequence[2].ballType)
        assertNull(advice.breakSequence[2].respottedToSpotMeters)
        assertTrue(advice.expectedBreakPoints > 2.0)
        assertNotNull(advice.bestSafety)
    }

    @Test
    fun plannerRespotFallbackAvoidsOverlappingOccupyingBall() {
        val blackSpot = SnookerSpots.spotForColour(SnookerBallType.BLACK, snookerTable)
        val layout = TableLayout(
            table = snookerTable,
            balls = listOf(
                sBall(0, 2.85, 0.35),
                sBall(7, 3.12, 0.80),
                sBall(8, blackSpot), // Red sits directly on Black's spot
            ),
        )

        val advice = SnookerStrategyPlanner.evaluateSnookerPosition(
            layout = layout,
            scoreState = SnookerScoreState(redsRemaining = 1, onColourAfterRed = true),
            playerProfile = PlayerProfile(),
            horizonShots = 1,
        )

        assertEquals(1, advice.breakSequence.size)
        val step = advice.breakSequence.first()
        assertEquals(SnookerBallType.BLACK, step.ballType)
        val respot = assertNotNull(step.respottedToSpotMeters)
        assertEquals(snookerTable.widthMeters * 0.5, respot.y, 1e-6)
        assertTrue(respot.distanceTo(blackSpot) >= 2.0 * sr)
        assertTrue(snookerTable.isWithinPlayableBounds(respot, sr))
    }

    @Test
    fun plannerClearsEndgameColoursInAscendingOrder() {
        val layout = TableLayout(
            table = snookerTable,
            balls = listOf(
                sBall(0, 0.85, 1.32),
                sBall(2, 0.48, 1.50),
                sBall(3, 0.50, 0.60),
                sBall(4, SnookerSpots.spotForColour(SnookerBallType.BROWN, snookerTable)),
                sBall(5, SnookerSpots.spotForColour(SnookerBallType.BLUE, snookerTable)),
                sBall(6, SnookerSpots.spotForColour(SnookerBallType.PINK, snookerTable)),
                sBall(7, SnookerSpots.spotForColour(SnookerBallType.BLACK, snookerTable)),
            ),
        )

        val advice = SnookerStrategyPlanner.evaluateSnookerPosition(
            layout = layout,
            scoreState = SnookerScoreState(
                redsRemaining = 0,
                onColourAfterRed = false,
                lowestEndgameColourValue = 2,
            ),
            playerProfile = PlayerProfile(),
            horizonShots = 2,
        )

        assertEquals(SnookerTacticalMode.ATTACK_BREAK_BUILD, advice.recommendedMode)
        assertEquals(2, advice.breakSequence.size)
        assertEquals(SnookerBallType.YELLOW, advice.breakSequence[0].ballType)
        assertEquals(2, advice.breakSequence[0].pointValue)
        assertNull(advice.breakSequence[0].respottedToSpotMeters)

        assertEquals(SnookerBallType.GREEN, advice.breakSequence[1].ballType)
        assertEquals(3, advice.breakSequence[1].pointValue)
        assertNull(advice.breakSequence[1].respottedToSpotMeters)
    }

    @Test
    fun plannerRecommendsContainmentSafetyWhenRedPotIsLowPercentage() {
        val layout = TableLayout(
            table = snookerTable,
            balls = listOf(
                sBall(0, 0.55, 0.65),
                sBall(1, 2.75, 0.72),
                sBall(2, SnookerSpots.spotForColour(SnookerBallType.YELLOW, snookerTable)),
                sBall(3, SnookerSpots.spotForColour(SnookerBallType.GREEN, snookerTable)),
                sBall(4, SnookerSpots.spotForColour(SnookerBallType.BROWN, snookerTable)),
                sBall(5, SnookerSpots.spotForColour(SnookerBallType.BLUE, snookerTable)),
                sBall(6, SnookerSpots.spotForColour(SnookerBallType.PINK, snookerTable)),
                sBall(7, SnookerSpots.spotForColour(SnookerBallType.BLACK, snookerTable)),
            ),
        )

        val advice = SnookerStrategyPlanner.evaluateSnookerPosition(
            layout = layout,
            scoreState = SnookerScoreState(redsRemaining = 1, onColourAfterRed = false),
            playerProfile = PlayerProfile(),
            horizonShots = 3,
        )

        assertEquals(SnookerTacticalMode.CONTAINMENT_SAFETY, advice.recommendedMode)
        val safety = assertNotNull(advice.bestSafety)
        assertEquals(1, safety.targetBallNumber)
        assertTrue(safety.cueBallFinalMeters.x <= SnookerSpots.BAULK_LINE_X_METERS + 1e-6)
        assertTrue(safety.opponentEstimatedPotProbability < 0.45)
        assertTrue(safety.safetyQualityScore > 0.45)
        assertNull(safety.cueBallTrajectory.hitsScratchPocket)
    }

    @Test
    fun plannerRecommendsPlayForSnookersWhenDeficitExceedsRemainingPoints() {
        val layout = TableLayout(
            table = snookerTable,
            balls = listOf(
                sBall(0, 2.55, 0.45),
                sBall(1, 3.02, 0.25),
                sBall(8, 3.00, 1.48),
                sBall(4, SnookerSpots.spotForColour(SnookerBallType.BROWN, snookerTable)),
                sBall(6, SnookerSpots.spotForColour(SnookerBallType.PINK, snookerTable)),
                sBall(7, SnookerSpots.spotForColour(SnookerBallType.BLACK, snookerTable)),
            ),
        )

        val advice = SnookerStrategyPlanner.evaluateSnookerPosition(
            layout = layout,
            scoreState = SnookerScoreState(
                playerScore = 10,
                opponentScore = 90,
                redsRemaining = 2,
                onColourAfterRed = false,
            ),
            playerProfile = PlayerProfile(),
            horizonShots = 3,
        )

        assertEquals(43, advice.scoreState.pointsRemainingOnTable)
        assertTrue(advice.scoreState.needsSnookers)
        assertEquals(10, advice.scoreState.snookersRequiredCount)
        assertEquals(SnookerTacticalMode.PLAY_FOR_SNOOKERS, advice.recommendedMode)
        assertNotNull(advice.bestSafety)
    }

    @Test
    fun plannerHandlesDirectSnookerWithSafetyEscape() {
        val layout = TableLayout(
            table = snookerTable,
            balls = listOf(
                sBall(0, 0.40, 0.889),
                sBall(4, 0.737, 0.889), // Brown directly blocks cue ball to Red
                sBall(1, 1.50, 0.889),
            ),
        )

        val advice = SnookerStrategyPlanner.evaluateSnookerPosition(
            layout = layout,
            scoreState = SnookerScoreState(redsRemaining = 1, onColourAfterRed = false),
            playerProfile = PlayerProfile(),
            horizonShots = 2,
        )

        assertTrue(advice.breakSequence.isEmpty())
        assertEquals(SnookerTacticalMode.CONTAINMENT_SAFETY, advice.recommendedMode)
        val safety = assertNotNull(advice.bestSafety)
        assertEquals(1, safety.targetBallNumber)
        assertTrue(safety.cueBallTrajectory.points.size >= 2)
    }

    @Test
    fun detectBreakImpactsFindsCueStrikeAndRackImpactInSyntheticEnvelope() {
        val sampleRate = 2000
        val envelope = DoubleArray(1200) { 0.02 }
        envelope[159] = 0.35
        envelope[160] = 0.72 // t = 0.080 s
        envelope[161] = 0.31
        envelope[429] = 0.55
        envelope[430] = 0.96 // t = 0.215 s
        envelope[431] = 0.52

        val detected = assertNotNull(
            BreakSpeedEstimator.detectBreakImpactsFromEnvelope(
                envelopeAmplitudes = envelope,
                envelopeSampleRateHz = sampleRate,
            ),
        )

        assertEquals(0.080, detected.cueStrikeTimestampSeconds, 1e-6)
        assertEquals(0.215, detected.rackImpactTimestampSeconds, 1e-6)
        assertEquals(0.135, detected.rawDeltaTimeSeconds, 1e-6)
        assertEquals(0.72, detected.peakCueStrikeAmplitude, 1e-9)
        assertEquals(0.96, detected.peakRackImpactAmplitude, 1e-9)

        assertNull(BreakSpeedEstimator.detectBreakImpactsFromEnvelope(DoubleArray(400) { 0.05 }, sampleRate))
        val tooClose = DoubleArray(400) { 0.01 }
        tooClose[50] = 0.80
        tooClose[80] = 0.90 // 0.015 s < 0.035 s
        assertNull(BreakSpeedEstimator.detectBreakImpactsFromEnvelope(tooClose, sampleRate))

        val tooFar = DoubleArray(1400) { 0.01 }
        tooFar[100] = 0.80
        tooFar[1300] = 0.90 // 0.60 s > 0.45 s
        assertNull(BreakSpeedEstimator.detectBreakImpactsFromEnvelope(tooFar, sampleRate))
    }

    @Test
    fun estimateBreakSpeedRecoversSyntheticLaunchSpeedWithinOneHundredthMps() {
        val breakDistance = 1.27
        val muS = 0.20
        val micToCue = 0.60
        val micToRack = 1.65
        val acousticDelay = (micToRack - micToCue) / BreakSpeedEstimator.SPEED_OF_SOUND_MPS
        val decel = muS * MotionPhysics.GRAVITY_M_S2

        for (trueLaunchSpeed in listOf(6.5, 9.5, 12.0)) {
            val flightTime = (trueLaunchSpeed - sqrt(trueLaunchSpeed * trueLaunchSpeed - 2.0 * decel * breakDistance)) / decel
            val rawDelta = flightTime + acousticDelay
            val expectedImpactSpeed = trueLaunchSpeed - decel * flightTime

            val estimate = BreakSpeedEstimator.estimateBreakSpeed(
                rawDeltaTimeSeconds = rawDelta,
                breakDistanceMeters = breakDistance,
                micToCueBallDistanceMeters = micToCue,
                micToRackDistanceMeters = micToRack,
                slidingFrictionMuS = muS,
            )

            assertTrue(
                abs(estimate.launchSpeedMps - trueLaunchSpeed) < 0.01,
                "Expected launch speed $trueLaunchSpeed within 0.01 m/s, got ${estimate.launchSpeedMps}",
            )
            assertTrue(
                abs(estimate.impactSpeedMps - expectedImpactSpeed) < 0.01,
                "Expected impact speed $expectedImpactSpeed within 0.01 m/s, got ${estimate.impactSpeedMps}",
            )
            assertEquals(flightTime, estimate.correctedFlightTimeSeconds, 1e-9)
            assertEquals(acousticDelay, estimate.acousticDelayCorrectionSeconds, 1e-9)
            assertTrue(estimate.launchSpeedMps > estimate.averageFlightSpeedMps)
            assertTrue(estimate.averageFlightSpeedMps > estimate.impactSpeedMps)
        }
    }

    @Test
    fun breakSpeedEstimatorRejectsInvalidPositiveConstraints() {
        assertFailsWith<IllegalArgumentException> {
            BreakSpeedEstimator.detectBreakImpactsFromEnvelope(doubleArrayOf(0.8, 0.0, 0.9), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            BreakSpeedEstimator.estimateBreakSpeed(rawDeltaTimeSeconds = 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            BreakSpeedEstimator.estimateBreakSpeed(rawDeltaTimeSeconds = 0.15, breakDistanceMeters = -1.0)
        }
    }
}
