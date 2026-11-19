package com.runout.app.calibration

import com.runout.engine.calibration.*
import com.runout.engine.math.Vec2
import com.runout.engine.model.ClothParams
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.MotionPhysics
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Complete table calibration session preset combining 60 fps overhead video observations,
 * five physical calibration drills, held-out verification shots, paired 0/180-degree
 * tilt readings on a 3x3 grid, and a slow-roll lateral drift measurement.
 */
data class CalibrationSessionPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val baseTableSpec: TableSpec,
    val calibratedDateIso: String,
    val videoFrames: List<VideoFrameBallObservation>,
    val lagTrials: List<LagDrillTrial>,
    val stunTrials: List<StunDrillTrial>,
    val cushionTrials: List<CushionSpeedDrillTrial>,
    val bankSpinTrials: List<BankSpinDrillTrial>,
    val cutThrowTrials: List<CutThrowDrillTrial>,
    val heldOutShots: List<HeldOutShotSample>,
    val pairedTiltReadings: List<PairedTiltReading>,
    val slowRollDistanceMeters: Double,
    val slowRollLateralDriftMeters: Double,
)

/**
 * Platform MEMS tilt-sensor bridge providing paired 0/180-degree readings across a 3x3 table grid.
 */
expect class PlatformTiltSensorController() {
    val sensorBackendName: String
    val resolutionDegrees: Double
    fun readPairedGridPreset(sessionId: String): List<PairedTiltReading>
}

/**
 * Physically consistent preset calibration sessions across tournament, bar-box, and unlevel tables.
 */
object SampleCalibrationSessions {
    private const val CAMERA_BUMP_OFFSET_RAD: Double = 0.015
    private const val VIDEO_FPS: Double = 60.0

    fun fastTournamentNineFootSession(): CalibrationSessionPreset {
        val levelGrid = List(9) { idx ->
            val col = idx % 3
            val row = idx / 3
            val sx = (col - 1) * 0.00008
            val sy = (row - 1) * 0.00010 + 0.00005
            sx to sy
        }
        return buildPresetSession(
            id = "fast_tournament_9ft",
            title = "Fast Tournament 9-ft",
            subtitle = "9-ft worsted cloth • fast roll, lively rails, low throw & level slate",
            baseTableSpec = TableSpec.nineFootPool(),
            calibratedDateIso = "2026-11-17",
            trueMuR = 0.0068,
            trueMuS = 0.165,
            trueEc = 0.82,
            trueMuB = 0.042,
            trueKSpin = 0.62,
            gridSlopes = levelGrid,
            slowRollDistanceMeters = 1.80,
            slowRollLateralDriftMeters = 0.003,
            noisePhase = 0.4,
        )
    }

    fun slowBarBoxSevenFootSession(): CalibrationSessionPreset {
        val levelGrid = List(9) { idx ->
            val col = idx % 3
            val row = idx / 3
            val sx = (col - 1) * 0.00012 - 0.00006
            val sy = (row - 1) * 0.00015 + 0.00008
            sx to sy
        }
        return buildPresetSession(
            id = "slow_barbox_7ft",
            title = "Slow Bar-Box 7-ft",
            subtitle = "7-ft napped bar cloth • heavy drag, dead cushions & high cut throw",
            baseTableSpec = TableSpec.sevenFootPool(),
            calibratedDateIso = "2026-11-17",
            trueMuR = 0.0148,
            trueMuS = 0.245,
            trueEc = 0.66,
            trueMuB = 0.076,
            trueKSpin = 0.44,
            gridSlopes = levelGrid,
            slowRollDistanceMeters = 1.50,
            slowRollLateralDriftMeters = 0.004,
            noisePhase = 1.3,
        )
    }

    fun unlevelHomeEightFootSession(): CalibrationSessionPreset {
        // Right-side sag reaches 0.0032 rad (~0.18 deg, 3.2 mm/m) toward the right rail (+y),
        // while the center lane averages ~0.0010 rad and the full 3x3 grid averages ~0.0014 rad.
        val unlevelGrid = buildList {
            val rowSlopesY = listOf(
                listOf(0.0005, 0.0006, 0.0007),
                listOf(0.0009, 0.0010, 0.0011),
                listOf(0.0020, 0.0026, 0.0032),
            )
            for (row in 0..2) {
                for (col in 0..2) {
                    val sx = (col - 1) * 0.00010
                    val sy = rowSlopesY[row][col]
                    add(sx to sy)
                }
            }
        }
        return buildPresetSession(
            id = "unlevel_home_8ft",
            title = "Unlevel Home 8-ft",
            subtitle = "8-ft home table • 0.18° (3.2 mm/m) right-rail roll-off & 85 mm slow-roll drift",
            baseTableSpec = TableSpec.eightFootPool(),
            calibratedDateIso = "2026-11-17",
            trueMuR = 0.0115,
            trueMuS = 0.205,
            trueEc = 0.74,
            trueMuB = 0.058,
            trueKSpin = 0.54,
            gridSlopes = unlevelGrid,
            slowRollDistanceMeters = 1.80,
            slowRollLateralDriftMeters = 0.085,
            noisePhase = 2.1,
        )
    }

    fun allSessions(): List<CalibrationSessionPreset> = listOf(
        fastTournamentNineFootSession(),
        slowBarBoxSevenFootSession(),
        unlevelHomeEightFootSession(),
    )

    fun runFullSessionCalibration(
        preset: CalibrationSessionPreset,
    ): Triple<SavedTableProfile, CalibrationValidationReport, TrackedBallTrajectory> {
        val fittedParams = TableCalibrationFitter.fitAllDrills(
            lagTrials = preset.lagTrials,
            stunTrials = preset.stunTrials,
            cushionTrials = preset.cushionTrials,
            bankSpinTrials = preset.bankSpinTrials,
            cutThrowTrials = preset.cutThrowTrials,
        )
        val validationReport = TableCalibrationFitter.evaluateHeldOutPredictionAccuracy(
            fittedParams = fittedParams,
            heldOutSamples = preset.heldOutShots,
        )
        val slopeMap = TableLevelCalibration.buildSlopeMapFromPairedReadings(
            readings = preset.pairedTiltReadings,
            table = preset.baseTableSpec,
        )
        val trackedTrajectory = VideoBallTracker.trackBallTrajectory(
            observations = preset.videoFrames,
            table = preset.baseTableSpec,
        )
        val savedProfile = SavedTableProfile(
            id = preset.id,
            profileName = preset.title,
            baseTableSpec = preset.baseTableSpec,
            calibratedParams = fittedParams,
            slopeMap = slopeMap,
            calibratedDateIso = preset.calibratedDateIso,
        )
        return Triple(savedProfile, validationReport, trackedTrajectory)
    }

    fun defaultSavedProfiles(): List<SavedTableProfile> =
        allSessions().map { preset -> runFullSessionCalibration(preset).first }

    private fun buildPresetSession(
        id: String,
        title: String,
        subtitle: String,
        baseTableSpec: TableSpec,
        calibratedDateIso: String,
        trueMuR: Double,
        trueMuS: Double,
        trueEc: Double,
        trueMuB: Double,
        trueKSpin: Double,
        gridSlopes: List<Pair<Double, Double>>,
        slowRollDistanceMeters: Double,
        slowRollLateralDriftMeters: Double,
        noisePhase: Double,
    ): CalibrationSessionPreset {
        val g = MotionPhysics.GRAVITY_M_S2
        val relNoise = doubleArrayOf(-0.014, 0.016, -0.009, 0.012, -0.015, 0.008, 0.002)

        val lagSpeeds = listOf(0.52, 0.62, 0.72, 0.82, 0.92, 1.02, 1.12)
        val lagTrials = lagSpeeds.mapIndexed { i, v0 ->
            val dTrue = MotionPhysics.rollingStoppingDistanceMeters(v0, trueMuR, g)
            val noise = relNoise[(i + 0) % relNoise.size] * cos(noisePhase * 0.3 + i * 0.1)
            LagDrillTrial(
                initialRollingSpeedMps = v0,
                measuredStoppingDistanceMeters = dTrue * (1.0 + noise),
            )
        }

        val stunSpeeds = listOf(1.15, 1.40, 1.65, 1.90, 2.15, 2.40, 2.65)
        val stunTrials = stunSpeeds.mapIndexed { i, v0 ->
            val tTrue = MotionPhysics.slideToRollTimeSeconds(v0, trueMuS, g)
            val dTrue = (12.0 * v0 * v0) / (49.0 * trueMuS * g)
            val tNoise = relNoise[(i + 2) % relNoise.size]
            val dNoise = relNoise[(i + 4) % relNoise.size]
            StunDrillTrial(
                initialCueSpeedMps = v0,
                measuredSlideDurationSeconds = tTrue * (1.0 + tNoise),
                measuredSlideDistanceMeters = dTrue * (1.0 + dNoise),
            )
        }

        val cushionSpeeds = listOf(0.85, 1.15, 1.45, 1.75, 2.05, 2.40, 2.75)
        val cushionTrials = cushionSpeeds.mapIndexed { i, vIn ->
            val noise = relNoise[(i + 3) % relNoise.size] * 0.85
            CushionSpeedDrillTrial(
                incomingNormalSpeedMps = vIn,
                reboundNormalSpeedMps = vIn * trueEc * (1.0 + noise),
            )
        }

        val bankSpeeds = listOf(1.40, 1.65, 1.85, 2.05, 1.55, 1.95, 2.25)
        val bankAngles = listOf(0.28, 0.36, 0.44, 0.52, 0.32, 0.48, 0.40)
        val bankSpins = listOf(-0.35, 0.30, -0.22, 0.40, 0.25, -0.42, 0.35)
        val bankSpinTrials = bankSpeeds.indices.map { i ->
            val v = bankSpeeds[i]
            val thetaIn = bankAngles[i]
            val spin = bankSpins[i]
            val vN = v * cos(thetaIn)
            val vT = v * sin(thetaIn)
            val vNOut = trueEc * vN
            val vTOut = 0.82 * vT + trueKSpin * spin * vN
            val thetaOut = atan2(vTOut, vNOut)
            val angleJitter = 0.004 * sin(noisePhase + i * 1.9)
            BankSpinDrillTrial(
                incomingSpeedMps = v,
                incomingAngleRad = thetaIn,
                sideSpinRatio = spin,
                measuredReboundAngleRad = thetaOut + angleJitter,
            )
        }

        val cutAngles = listOf(0.45, -0.50, 0.52, -0.58, 0.48, -0.42, 0.60)
        val cutSpeeds = listOf(0.75, 0.90, 1.05, 1.20, 0.85, 1.35, 1.10)
        val cutThrowTrials = cutAngles.indices.map { i ->
            val phi = cutAngles[i]
            val speed = cutSpeeds[i]
            val trueThrow = SpinPhysics.computeThrowAnglesRadians(
                cutAngleSignedRadians = phi,
                cueSpeedMps = speed,
                tip = TipOffset(),
                ballBallFrictionMu = trueMuB,
            ).first
            val throwJitter = 0.0005 * cos(noisePhase + i * 2.1)
            CutThrowDrillTrial(
                cutAngleSignedRad = phi,
                cueSpeedMps = speed,
                measuredThrowAngleRad = trueThrow + throwJitter,
            )
        }

        val heldOutSpeeds = listOf(0.56, 0.70, 0.84, 0.98, 1.10, 1.24)
        val heldOutCushionSpeeds = listOf(1.00, 1.32, 1.64, 1.96, 2.28, 2.60)
        val heldOutCutAngles = listOf(0.41, -0.47, 0.54, -0.61, 0.46, -0.51)
        val heldOutShots = heldOutSpeeds.indices.map { i ->
            val v = heldOutSpeeds[i]
            val vCushion = heldOutCushionSpeeds[i]
            val phi = heldOutCutAngles[i]
            val distNoise = 0.008 * sin(noisePhase + i * 2.4)
            val reboundNoise = 0.008 * cos(noisePhase + i * 1.7)
            val throwNoise = 0.0004 * sin(noisePhase + i * 2.9)
            val trueDist = MotionPhysics.rollingStoppingDistanceMeters(v, trueMuR, g) * (1.0 + distNoise)
            val trueRebound = vCushion * trueEc * (1.0 + reboundNoise)
            val trueThrow = SpinPhysics.computeThrowAnglesRadians(
                cutAngleSignedRadians = phi,
                cueSpeedMps = v,
                tip = TipOffset(),
                ballBallFrictionMu = trueMuB,
            ).first + throwNoise
            HeldOutShotSample(
                initialRollingSpeedMps = v,
                trueStoppingDistanceMeters = trueDist,
                incomingCushionSpeedMps = vCushion,
                trueReboundSpeedMps = trueRebound,
                cutAngleRad = phi,
                trueThrowAngleRad = trueThrow,
            )
        }

        val pairedTiltReadings = buildPairedTiltGrid(gridSlopes)
        val videoFrames = generateSixtyFpsVideoFrames(
            table = baseTableSpec,
            trueMuR = trueMuR,
            trueMuS = trueMuS,
            trueEc = trueEc,
            noisePhase = noisePhase,
        )

        return CalibrationSessionPreset(
            id = id,
            title = title,
            subtitle = subtitle,
            baseTableSpec = baseTableSpec,
            calibratedDateIso = calibratedDateIso,
            videoFrames = videoFrames,
            lagTrials = lagTrials,
            stunTrials = stunTrials,
            cushionTrials = cushionTrials,
            bankSpinTrials = bankSpinTrials,
            cutThrowTrials = cutThrowTrials,
            heldOutShots = heldOutShots,
            pairedTiltReadings = pairedTiltReadings,
            slowRollDistanceMeters = slowRollDistanceMeters,
            slowRollLateralDriftMeters = slowRollLateralDriftMeters,
        )
    }

    private fun buildPairedTiltGrid(
        gridSlopes: List<Pair<Double, Double>>,
    ): List<PairedTiltReading> {
        val readings = ArrayList<PairedTiltReading>(9)
        for (row in 0..2) {
            for (col in 0..2) {
                val (trueSlopeX, trueSlopeY) = gridSlopes[row * 3 + col]
                val pitchBias = CAMERA_BUMP_OFFSET_RAD
                val rollBias = -CAMERA_BUMP_OFFSET_RAD
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
        return readings
    }

    private fun generateSixtyFpsVideoFrames(
        table: TableSpec,
        trueMuR: Double,
        trueMuS: Double,
        trueEc: Double,
        noisePhase: Double,
    ): List<VideoFrameBallObservation> {
        val g = MotionPhysics.GRAVITY_M_S2
        val r = table.ballRadiusMeters
        val startPos = Vec2(table.lengthMeters * 0.34, table.widthMeters * 0.34)
        val aimAngle = 0.16
        val dir = Vec2(cos(aimAngle), sin(aimAngle))
        val dToRail = ((table.lengthMeters - r) - startPos.x) / dir.x

        val aSlide = trueMuS * g
        val aRoll = trueMuR * g
        val tSlide = 0.45

        val targetPostBounceDist = table.lengthMeters * 0.42
        val targetPostBounceSpeed = sqrt(2.0 * aRoll * targetPostBounceDist)
        val bounceSpeedScale = hypot(trueEc * dir.x, 0.82 * dir.y)
        val vBounceIn = targetPostBounceSpeed / bounceSpeedScale

        // Solve vRoll0^2 + 2 * aRoll * tSlide * vRoll0 - C = 0 where vRoll0 is speed at slide-to-roll transition.
        val bCoeff = 2.0 * aRoll * tSlide
        val cCoeff = vBounceIn * vBounceIn + 2.0 * aRoll * (dToRail - 0.5 * aSlide * tSlide * tSlide)
        val vRoll0 = (-bCoeff + sqrt(bCoeff * bCoeff + 4.0 * cCoeff)) * 0.5
        val v0 = vRoll0 + aSlide * tSlide
        val dSlide = vRoll0 * tSlide + 0.5 * aSlide * tSlide * tSlide

        val tRollBefore = (vRoll0 - vBounceIn) / aRoll
        val tBounce = tSlide + tRollBefore
        val vAfter = vBounceIn * bounceSpeedScale
        val tStop = tBounce + vAfter / aRoll
        val tEnd = tStop + 0.65

        val bouncePos = startPos + dir * dToRail
        val afterDir = Vec2(-trueEc * dir.x, 0.82 * dir.y).normalized()
        val dAfter = 0.5 * vAfter * (tStop - tBounce)
        val stopPos = bouncePos + afterDir * dAfter

        val frameCount = (tEnd * VIDEO_FPS).toInt() + 1
        val dt = 1.0 / VIDEO_FPS
        val observations = ArrayList<VideoFrameBallObservation>(frameCount)

        for (frameIdx in 0 until frameCount) {
            val t = frameIdx * dt
            val truePos = when {
                t <= tSlide -> {
                    val dist = v0 * t - 0.5 * aSlide * t * t
                    startPos + dir * dist
                }
                t <= tBounce -> {
                    val dtRoll = t - tSlide
                    val dist = dSlide + vRoll0 * dtRoll - 0.5 * aRoll * dtRoll * dtRoll
                    startPos + dir * dist
                }
                t <= tStop -> {
                    val dtAfter = t - tBounce
                    val dist = vAfter * dtAfter - 0.5 * aRoll * dtAfter * dtAfter
                    bouncePos + afterDir * dist
                }
                else -> stopPos
            }

            // Realistic ±0.8 mm overhead detection jitter and 120 Hz mains flicker beat
            val jitterScale = if (t <= tStop) 0.0008 else 0.0004
            val jitter = Vec2(
                x = jitterScale * sin(frameIdx * 1.7 + noisePhase),
                y = jitterScale * cos(frameIdx * 2.3 + noisePhase),
            )
            val luma = 128.0 +
                14.0 * cos(PI * frameIdx) +
                6.0 * sin(2.0 * PI * frameIdx / 3.0)

            observations += VideoFrameBallObservation(
                frameIndex = frameIdx,
                timestampSeconds = t,
                rawPositionMeters = truePos + jitter,
                frameMeanLuma = luma,
            )
        }
        return observations
    }
}
