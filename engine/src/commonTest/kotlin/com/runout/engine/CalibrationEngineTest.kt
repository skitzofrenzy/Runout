package com.runout.engine

import com.runout.engine.calibration.BankSpinDrillTrial
import com.runout.engine.calibration.CushionSpeedDrillTrial
import com.runout.engine.calibration.CutThrowDrillTrial
import com.runout.engine.calibration.HeldOutShotSample
import com.runout.engine.calibration.LagDrillTrial
import com.runout.engine.calibration.PairedTiltReading
import com.runout.engine.calibration.SavedTableProfile
import com.runout.engine.calibration.StunDrillTrial
import com.runout.engine.calibration.TableCalibrationFitter
import com.runout.engine.calibration.TableLevelCalibration
import com.runout.engine.calibration.TrajectoryEventType
import com.runout.engine.calibration.VideoBallTracker
import com.runout.engine.calibration.VideoFrameBallObservation
import com.runout.engine.math.Vec2
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CalibrationEngineTest {

    private val table9ft = TableSpec.nineFootPool()
    private val table8ft = TableSpec.eightFootPool()

    @Test
    fun normalizeFrameBrightnessRemovesTwoAndThreeFrameFlicker() {
        val n = 90
        val rawTwoAndThreeFrame = DoubleArray(n) { i ->
            val alt = if (i % 2 == 0) 1.0 else -1.0
            114.0 + 18.0 * alt + 11.0 * sin(2.0 * PI * i / 3.0 + 0.4)
        }

        val normalized = VideoBallTracker.normalizeFrameBrightness(rawTwoAndThreeFrame, targetMeanLuma = 128.0)
        assertEquals(n, normalized.size)
        assertEquals(128.0, normalized.average(), 1e-6)

        val rawDiffVar = frameDiffVariance(rawTwoAndThreeFrame)
        val normDiffVar = frameDiffVariance(normalized)
        assertTrue(rawDiffVar > 100.0)
        assertTrue(
            normDiffVar < 1e-6,
            "Expected near-zero frame-to-frame flicker variance after normalization, got $normDiffVar",
        )

        // Slow linear exposure drift + flicker preserves trend while keeping target mean
        val drifting = DoubleArray(n) { i ->
            val alt = if (i % 2 == 0) 1.0 else -1.0
            100.0 + 0.25 * i + 15.0 * alt + 8.0 * cos(2.0 * PI * i / 3.0)
        }
        val normDrifting = VideoBallTracker.normalizeFrameBrightness(drifting, targetMeanLuma = 135.0)
        assertEquals(135.0, normDrifting.average(), 1e-6)
        assertTrue(frameDiffVariance(normDrifting) < 0.05 * frameDiffVariance(drifting))

        assertEquals(0, VideoBallTracker.normalizeFrameBrightness(DoubleArray(0)).size)
        val single = VideoBallTracker.normalizeFrameBrightness(doubleArrayOf(92.0), targetMeanLuma = 120.0)
        assertEquals(1, single.size)
        assertEquals(120.0, single[0], 1e-9)
    }

    @Test
    fun trackBallTrajectorySmoothsNoisyPositionsAndDetectsSlideToRollBounceAndStop() {
        val fps = 60.0
        val dt = 1.0 / fps
        val g = MotionPhysics.GRAVITY_M_S2
        val muS = 0.20
        val muR = 0.020
        val ec = 0.75

        val start = Vec2(0.80, 0.40)
        val dir = Vec2(0.98, 0.199).normalized()
        val v0 = 1.75

        val aSlide = muS * g
        val tSlide = (2.0 * v0) / (7.0 * muS * g)
        val vRoll0 = (5.0 / 7.0) * v0
        val sSlide = 0.5 * (v0 + vRoll0) * tSlide

        val footX = table9ft.lengthMeters - table9ft.ballRadiusMeters
        val sToFoot = (footX - start.x) / dir.x
        val sRollBeforeBounce = sToFoot - sSlide
        val aRoll = muR * g
        val vImpact = sqrt(vRoll0 * vRoll0 - 2.0 * aRoll * sRollBeforeBounce)
        val dtRoll1 = (vRoll0 - vImpact) / aRoll
        val tBounce = tSlide + dtRoll1
        val bouncePos = start + dir * sToFoot

        val vInVec = dir * vImpact
        val vOutVec = Vec2(-ec * vInVec.x, 0.82 * vInVec.y)
        val speedAfterBounce = vOutVec.length()
        val outDir = vOutVec / speedAfterBounce
        val dtRoll2 = speedAfterBounce / aRoll
        val tStop = tBounce + dtRoll2

        val totalDuration = tStop + 0.25
        val frameCount = (totalDuration * fps).toInt()
        val truePositions = ArrayList<Vec2>(frameCount)
        val observations = ArrayList<VideoFrameBallObservation>(frameCount)

        var seed = 424242L
        fun nextGaussian(std: Double): Double {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val u1 = (((seed ushr 11) and 0x1FFFFFL).toDouble() / 0x200000L.toDouble()).coerceIn(1e-6, 1.0 - 1e-6)
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val u2 = ((seed ushr 11) and 0x1FFFFFL).toDouble() / 0x200000L.toDouble()
            return std * sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
        }

        val noiseStd = 0.0018
        for (i in 0 until frameCount) {
            val t = i * dt
            val truePos = when {
                t <= tSlide -> {
                    val s = v0 * t - 0.5 * aSlide * t * t
                    start + dir * s
                }
                t <= tBounce -> {
                    val tr = t - tSlide
                    val s = sSlide + vRoll0 * tr - 0.5 * aRoll * tr * tr
                    start + dir * s
                }
                t <= tStop -> {
                    val tb = t - tBounce
                    val s = speedAfterBounce * tb - 0.5 * aRoll * tb * tb
                    bouncePos + outDir * s
                }
                else -> {
                    val sMax = 0.5 * speedAfterBounce * dtRoll2
                    bouncePos + outDir * sMax
                }
            }
            truePositions += truePos
            val noisy = Vec2(truePos.x + nextGaussian(noiseStd), truePos.y + nextGaussian(noiseStd))
            val flickerLuma = 128.0 + 16.0 * (if (i % 2 == 0) 1.0 else -1.0)
            observations += VideoFrameBallObservation(
                frameIndex = i,
                timestampSeconds = t,
                rawPositionMeters = noisy,
                frameMeanLuma = flickerLuma,
            )
        }

        val tracked = VideoBallTracker.trackBallTrajectory(
            observations = observations,
            table = table9ft,
            processAccelNoiseStdMps2 = 0.85,
            measurementPositionNoiseStdMeters = noiseStd,
        )

        assertEquals(frameCount, tracked.points.size)
        assertEquals(128.0, tracked.normalizedLumaSamples.average(), 1e-6)

        var rawSq = 0.0
        var filtSq = 0.0
        for (i in observations.indices) {
            rawSq += observations[i].rawPositionMeters.distanceSquaredTo(truePositions[i])
            filtSq += tracked.points[i].positionMeters.distanceSquaredTo(truePositions[i])
        }
        val rawRms = sqrt(rawSq / observations.size)
        val filtRms = sqrt(filtSq / observations.size)
        assertTrue(
            filtRms < rawRms * 0.75,
            "Kalman tracker must reduce RMS position error significantly: raw=$rawRms, filtered=$filtRms",
        )

        val slideToRoll = tracked.events.firstOrNull { it.type == TrajectoryEventType.SLIDE_TO_ROLL_TRANSITION }
        val bounce = tracked.events.firstOrNull { it.type == TrajectoryEventType.CUSHION_BOUNCE }
        val stop = tracked.events.firstOrNull { it.type == TrajectoryEventType.BALL_STOP }

        assertNotNull(slideToRoll, "Expected SLIDE_TO_ROLL_TRANSITION event in ${tracked.events}")
        assertNotNull(bounce, "Expected CUSHION_BOUNCE event in ${tracked.events}")
        assertNotNull(stop, "Expected BALL_STOP event in ${tracked.events}")

        assertTrue(abs(slideToRoll.timestampSeconds - tSlide) < 0.12)
        assertTrue(abs(bounce.timestampSeconds - tBounce) < 0.08)
        assertTrue(bounce.speedBeforeMps > bounce.speedAfterMps)
        assertTrue(abs(table9ft.lengthMeters - bounce.positionMeters.x) <= 2.5 * table9ft.ballRadiusMeters)
        assertTrue(stop.speedAfterMps <= 0.025)
        assertTrue(slideToRoll.timestampSeconds < bounce.timestampSeconds)
        assertTrue(bounce.timestampSeconds < stop.timestampSeconds)
    }

    @Test
    fun trackBallTrajectoryHandlesEmptySingleAndUnsortedObservations() {
        val empty = VideoBallTracker.trackBallTrajectory(emptyList(), table9ft)
        assertTrue(empty.points.isEmpty())
        assertTrue(empty.events.isEmpty())
        assertEquals(0, empty.normalizedLumaSamples.size)

        val singleObs = listOf(VideoFrameBallObservation(0, 0.0, Vec2(1.0, 0.5), 110.0))
        val single = VideoBallTracker.trackBallTrajectory(singleObs, table9ft)
        assertEquals(1, single.points.size)
        assertTrue(single.events.isEmpty())
        assertEquals(128.0, single.normalizedLumaSamples[0], 1e-6)

        val ordered = (0 until 25).map { idx ->
            val t = idx / 60.0
            VideoFrameBallObservation(idx, t, Vec2(0.5 + 1.2 * t, 0.6))
        }
        val shuffled = ordered.reversed()
        val resOrdered = VideoBallTracker.trackBallTrajectory(ordered, table9ft)
        val resShuffled = VideoBallTracker.trackBallTrajectory(shuffled, table9ft)
        assertEquals(resOrdered.points.size, resShuffled.points.size)
        for (i in resOrdered.points.indices) {
            assertEquals(resOrdered.points[i].timestampSeconds, resShuffled.points[i].timestampSeconds, 1e-12)
            assertEquals(resOrdered.points[i].positionMeters.x, resShuffled.points[i].positionMeters.x, 1e-12)
            assertEquals(resOrdered.points[i].velocityMps.x, resShuffled.points[i].velocityMps.x, 1e-12)
        }
    }

    @Test
    fun fitRollingResistanceMuRRecoversClothSpeedAndTrimsOutliers() {
        assertEquals(0.010, TableCalibrationFitter.fitRollingResistanceMuR(emptyList()), 1e-9)

        val trueMuR = 0.0068
        val speeds = listOf(0.8, 1.0, 1.2, 1.4, 1.6, 1.8)
        val noiseOffsets = listOf(0.008, -0.010, 0.006, -0.007, 0.005, -0.006)
        val trials = speeds.indices.map { i ->
            val d = MotionPhysics.rollingStoppingDistanceMeters(speeds[i], trueMuR) + noiseOffsets[i]
            LagDrillTrial(speeds[i], d)
        } + LagDrillTrial(initialRollingSpeedMps = 1.4, measuredStoppingDistanceMeters = 1.2) // extreme outlier

        val fitted = TableCalibrationFitter.fitRollingResistanceMuR(trials)
        assertEquals(trueMuR, fitted, 0.0004)
    }

    @Test
    fun fitSlidingFrictionMuSRecoversFrictionFromTimeAndDistanceWithOutliers() {
        assertEquals(0.20, TableCalibrationFitter.fitSlidingFrictionMuS(emptyList()), 1e-9)

        val trueMuS = 0.165
        val g = MotionPhysics.GRAVITY_M_S2
        val speeds = listOf(1.8, 2.2, 2.5, 2.8, 3.1, 3.4)
        val trials = speeds.indices.map { i ->
            val v0 = speeds[i]
            val tSlide = (2.0 * v0) / (7.0 * trueMuS * g) * (1.0 + if (i % 2 == 0) 0.015 else -0.012)
            val dSlide = (12.0 * v0 * v0) / (49.0 * trueMuS * g) * (1.0 + if (i % 2 == 0) -0.01 else 0.01)
            StunDrillTrial(v0, tSlide, dSlide)
        } + StunDrillTrial(2.5, 0.08, 0.12) // outlier

        val fitted = TableCalibrationFitter.fitSlidingFrictionMuS(trials)
        assertEquals(trueMuS, fitted, 0.006)
    }

    @Test
    fun fitCushionRestitutionAndSpinCouplingRecoverBounceParameters() {
        assertEquals(0.75, TableCalibrationFitter.fitCushionRestitutionEc(emptyList()), 1e-9)
        assertEquals(0.55, TableCalibrationFitter.fitCushionSpinCoupling(emptyList(), 0.75), 1e-9)

        val trueEc = 0.83
        val cushionTrials = listOf(0.8, 1.2, 1.6, 2.0, 2.4, 2.8).mapIndexed { i, vIn ->
            val factor = if (i % 2 == 0) 1.006 else 0.994
            CushionSpeedDrillTrial(vIn, vIn * trueEc * factor)
        } + CushionSpeedDrillTrial(2.0, 0.70) // outlier

        val fittedEc = TableCalibrationFitter.fitCushionRestitutionEc(cushionTrials)
        assertEquals(trueEc, fittedEc, 0.01)

        val trueKSpin = 0.68
        val bankConfigs = listOf(
            Triple(1.5, 0.35, 0.30),
            Triple(1.8, 0.45, -0.35),
            Triple(2.1, 0.25, 0.40),
            Triple(1.6, 0.50, -0.25),
            Triple(1.9, 0.30, 0.0), // zero side-spin must be ignored cleanly
        )
        val bankTrials = bankConfigs.map { (v, thetaIn, side) ->
            val vn = v * cos(thetaIn)
            val vt = v * sin(thetaIn)
            val vnOut = fittedEc * vn
            val vtOut = 0.82 * vt + trueKSpin * side * vn
            BankSpinDrillTrial(v, thetaIn, side, atan2(vtOut, vnOut))
        }

        val fittedSpin = TableCalibrationFitter.fitCushionSpinCoupling(bankTrials, fittedEc)
        assertEquals(trueKSpin, fittedSpin, 0.015)

        // Physical clamping checks
        val maxClamped = TableCalibrationFitter.fitCushionSpinCoupling(
            listOf(BankSpinDrillTrial(2.0, 0.2, 0.25, atan2(2.0, 0.4))),
            fittedEc = 0.75,
        )
        assertEquals(1.00, maxClamped, 1e-9)
    }

    @Test
    fun fitBallBallFrictionMuBInvertsThrowModelAccurately() {
        assertEquals(0.055, TableCalibrationFitter.fitBallBallFrictionMuB(emptyList()), 1e-9)

        val trueMuB = 0.082
        val centerTip = TipOffset()
        val cuts = listOf(0.35, -0.45, 0.52, -0.60, 0.28, -0.50)
        val speeds = listOf(0.7, 1.0, 1.3, 0.9, 1.5, 1.1)
        val trials = cuts.indices.map { i ->
            val exactThrow = SpinPhysics.computeThrowAnglesRadians(cuts[i], speeds[i], centerTip, trueMuB).first
            val noisyThrow = exactThrow + (if (i % 2 == 0) 0.0004 else -0.0004)
            CutThrowDrillTrial(cuts[i], speeds[i], noisyThrow)
        }

        val fittedMuB = TableCalibrationFitter.fitBallBallFrictionMuB(trials)
        assertEquals(trueMuB, fittedMuB, 0.003)
    }

    @Test
    fun evaluateHeldOutPredictionAccuracyProvesSubstantialErrorReduction() {
        val trueMuR = 0.0062
        val trueMuS = 0.155
        val trueEc = 0.84
        val trueMuB = 0.040
        val g = MotionPhysics.GRAVITY_M_S2
        val centerTip = TipOffset()

        val lagTrials = listOf(0.9, 1.1, 1.3, 1.5, 1.7, 1.9).mapIndexed { i, v ->
            val d = MotionPhysics.rollingStoppingDistanceMeters(v, trueMuR) * (1.0 + (i % 2 - 0.5) * 0.015)
            LagDrillTrial(v, d)
        }
        val stunTrials = listOf(1.8, 2.2, 2.5, 2.9, 3.2).map { v ->
            StunDrillTrial(
                initialCueSpeedMps = v,
                measuredSlideDurationSeconds = (2.0 * v) / (7.0 * trueMuS * g),
                measuredSlideDistanceMeters = (12.0 * v * v) / (49.0 * trueMuS * g),
            )
        }
        val cushionTrials = listOf(1.0, 1.4, 1.8, 2.2, 2.6).map { v ->
            CushionSpeedDrillTrial(v, v * trueEc)
        }
        val bankSpinTrials = listOf(0.30, -0.35, 0.40, -0.25).map { side ->
            val v = 1.8
            val thetaIn = 0.40
            val vn = v * cos(thetaIn)
            val vt = v * sin(thetaIn)
            val thetaOut = atan2(0.82 * vt + 0.62 * side * vn, trueEc * vn)
            BankSpinDrillTrial(v, thetaIn, side, thetaOut)
        }
        val cutThrowTrials = listOf(0.30, -0.45, 0.55, -0.35, 0.40).mapIndexed { i, cut ->
            val speed = 0.8 + i * 0.25
            val thrown = SpinPhysics.computeThrowAnglesRadians(cut, speed, centerTip, trueMuB).first
            CutThrowDrillTrial(cut, speed, thrown)
        }

        val fitted = TableCalibrationFitter.fitAllDrills(
            lagTrials = lagTrials,
            stunTrials = stunTrials,
            cushionTrials = cushionTrials,
            bankSpinTrials = bankSpinTrials,
            cutThrowTrials = cutThrowTrials,
        )

        val heldOut = (0 until 8).map { i ->
            val vRoll = 0.85 + i * 0.16
            val vCush = 0.90 + i * 0.22
            val cut = if (i % 2 == 0) 0.32 + i * 0.03 else -(0.34 + i * 0.03)
            HeldOutShotSample(
                initialRollingSpeedMps = vRoll,
                trueStoppingDistanceMeters = MotionPhysics.rollingStoppingDistanceMeters(vRoll, trueMuR),
                incomingCushionSpeedMps = vCush,
                trueReboundSpeedMps = vCush * trueEc,
                cutAngleRad = cut,
                trueThrowAngleRad = SpinPhysics.computeThrowAnglesRadians(cut, vRoll, centerTip, trueMuB).first,
            )
        }

        val report = TableCalibrationFitter.evaluateHeldOutPredictionAccuracy(fitted, heldOut)
        assertTrue(
            report.relativeStoppingErrorReductionFraction > 0.70,
            "Expected >70% stopping error reduction, got ${report.relativeStoppingErrorReductionFraction}",
        )
        assertTrue(report.calibratedStoppingRmsErrorMeters < report.defaultStoppingRmsErrorMeters)
        assertTrue(report.calibratedReboundSpeedRmsErrorMps < report.defaultReboundSpeedRmsErrorMps)
        assertTrue(report.calibratedThrowRmsErrorRad < report.defaultThrowRmsErrorRad)
    }

    @Test
    fun cancelSensorBias180AndBuildSlopeMapRemoveCameraBumpOffset() {
        val trueTilt = 0.0032
        val bumpBias = 0.025
        val r0 = trueTilt + bumpBias
        val r180 = -trueTilt + bumpBias
        assertEquals(trueTilt, TableLevelCalibration.cancelSensorBias180(r0, r180), 1e-12)

        val pitchBias = -0.018
        val rollBias = 0.022
        val trueSlopeX = 0.0005
        val trueSlopeY = 0.0032
        val readings = ArrayList<PairedTiltReading>(9)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                readings += PairedTiltReading(
                    gridCol = col,
                    gridRow = row,
                    readingZeroDegPitchRad = trueSlopeX + pitchBias,
                    readingZeroDegRollRad = trueSlopeY + rollBias,
                    reading180DegPitchRad = -trueSlopeX + pitchBias,
                    reading180DegRollRad = -trueSlopeY + rollBias,
                )
            }
        }

        val slopeMap = TableLevelCalibration.buildSlopeMapFromPairedReadings(readings, table8ft)
        assertEquals(3, slopeMap.cols)
        assertEquals(3, slopeMap.rows)
        assertEquals(9, slopeMap.cells.size)
        assertTrue(slopeMap.isNoticeablyUnlevel)

        val (sx, sy) = slopeMap.slopeAt(Vec2(1.5, 0.8), table8ft)
        assertEquals(trueSlopeX, sx, 1e-12)
        assertEquals(trueSlopeY, sy, 1e-12)

        val flatReadings = readings.map {
            PairedTiltReading(it.gridCol, it.gridRow, 0.0002 + pitchBias, 0.0003 + rollBias, -0.0002 + pitchBias, -0.0003 + rollBias)
        }
        val flatMap = TableLevelCalibration.buildSlopeMapFromPairedReadings(flatReadings, table8ft)
        assertFalse(flatMap.isNoticeablyUnlevel)
        assertEquals(0.0 to 0.0, slopeMap.copy(cells = emptyList()).slopeAt(Vec2(1.0, 0.5), table8ft))
        assertTrue(TableLevelCalibration.buildSlopeMapFromPairedReadings(emptyList(), table8ft).cells.isEmpty())

        val fitted = TableCalibrationFitter.fitAllDrills(
            lagTrials = listOf(LagDrillTrial(1.2, MotionPhysics.rollingStoppingDistanceMeters(1.2, 0.008))),
            stunTrials = emptyList(),
            cushionTrials = emptyList(),
        )
        val profile = SavedTableProfile(
            id = "club_8ft",
            profileName = "Club 8 ft",
            baseTableSpec = table8ft,
            calibratedParams = fitted,
            slopeMap = slopeMap,
            calibratedDateIso = "2026-11-17",
        )
        val effective = profile.toEffectiveTableSpec()
        assertEquals("Club 8 ft", effective.name)
        assertEquals(0.008, effective.cloth.rollingResistanceMuR, 1e-4)
    }

    @Test
    fun estimateSlopeFromSlowRollRecoversSubSensorTilt() {
        val muR = 0.0115
        val rollDistance = 1.80
        val trueTheta = 0.00072 // ~0.041 deg, below typical 0.1 deg phone sensor resolution
        val lateralDrift = (5.0 * rollDistance * sin(trueTheta)) / (7.0 * muR)

        val estimated = TableLevelCalibration.estimateSlopeFromSlowRoll(
            rollDistanceMeters = rollDistance,
            lateralDriftMeters = lateralDrift,
            muR = muR,
        )
        assertEquals(trueTheta, estimated, 1e-9)

        val steeperTheta = 0.0032
        val steeperDrift = (5.0 * rollDistance * sin(steeperTheta)) / (7.0 * muR)
        assertEquals(
            steeperTheta,
            TableLevelCalibration.estimateSlopeFromSlowRoll(rollDistance, steeperDrift, muR),
            1e-9,
        )
    }

    @Test
    fun predictSlowRollWithSlopeDriftsDownslopeAndClampsToPlayableBounds() {
        val start = Vec2(0.40, 0.56)
        val muR = 0.0115
        val v0 = MotionPhysics.initialRollingSpeedForDistance(1.50, muR)
        val slopeY = 0.0032

        val levelStop = TableLevelCalibration.predictSlowRollWithSlope(
            startMeters = start,
            direction = Vec2.UNIT_X,
            initialRollingSpeedMps = v0,
            muR = muR,
            slopeXRad = 0.0,
            slopeYRad = 0.0,
            table = table8ft,
        )
        val slopedStop = TableLevelCalibration.predictSlowRollWithSlope(
            startMeters = start,
            direction = Vec2.UNIT_X,
            initialRollingSpeedMps = v0,
            muR = muR,
            slopeXRad = 0.0,
            slopeYRad = slopeY,
            table = table8ft,
        )

        assertEquals(start.x + 1.50, levelStop.x, 1e-9)
        assertEquals(start.y, levelStop.y, 1e-9)

        val drift = slopedStop.y - levelStop.y
        assertTrue(drift > 0.25, "Expected significant downslope lateral drift, got $drift")
        val recoveredSlope = TableLevelCalibration.estimateSlopeFromSlowRoll(
            rollDistanceMeters = 1.50,
            lateralDriftMeters = drift,
            muR = muR,
        )
        assertEquals(slopeY, recoveredSlope, 1e-9)

        // Fast shot exceeding table bounds clamps cleanly inside playable bounds
        val clamped = TableLevelCalibration.predictSlowRollWithSlope(
            startMeters = Vec2(2.0, 1.0),
            direction = Vec2(1.0, 1.0),
            initialRollingSpeedMps = 4.0,
            muR = muR,
            slopeXRad = 0.005,
            slopeYRad = 0.005,
            table = table8ft,
        )
        assertTrue(table8ft.isWithinPlayableBounds(clamped))
    }

    private fun frameDiffVariance(samples: DoubleArray): Double {
        if (samples.size < 2) return 0.0
        val diffs = DoubleArray(samples.size - 1) { i -> samples[i + 1] - samples[i] }
        val mean = diffs.average()
        var sumSq = 0.0
        for (d in diffs) {
            val e = d - mean
            sumSq += e * e
        }
        return sumSq / diffs.size
    }
}
