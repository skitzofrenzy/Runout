package com.runout.app.calibration

import com.runout.engine.calibration.TableLevelCalibration
import com.runout.engine.calibration.TrajectoryEventType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalibrationSessionBenchmarksTest {
    @Test
    fun fastTournamentNineFootSessionReducesHeldOutPredictionErrorAboveThreshold() {
        val preset = SampleCalibrationSessions.fastTournamentNineFootSession()
        val (profile, report, _) = SampleCalibrationSessions.runFullSessionCalibration(preset)

        assertEquals("fast_tournament_9ft", profile.id)
        assertFalse(profile.slopeMap.isNoticeablyUnlevel, "Tournament 9-ft table should be level")
        assertTrue(
            report.relativeStoppingErrorReductionFraction > 0.65,
            "Expected > 65% stopping error reduction, got ${report.relativeStoppingErrorReductionFraction}",
        )
        assertTrue(
            report.calibratedReboundSpeedRmsErrorMps < report.defaultReboundSpeedRmsErrorMps,
            "Calibrated rebound RMS (${report.calibratedReboundSpeedRmsErrorMps}) should beat default (${report.defaultReboundSpeedRmsErrorMps})",
        )
        assertTrue(
            report.calibratedThrowRmsErrorRad < report.defaultThrowRmsErrorRad,
            "Calibrated throw RMS (${report.calibratedThrowRmsErrorRad}) should beat default (${report.defaultThrowRmsErrorRad})",
        )
        assertTrue(
            abs(report.fittedParams.cloth.rollingResistanceMuR - 0.0068) < 0.0006,
            "Fitted muR (${report.fittedParams.cloth.rollingResistanceMuR}) should recover ~0.0068",
        )
        assertTrue(
            abs(report.fittedParams.cloth.slidingFrictionMuS - 0.165) < 0.01,
            "Fitted muS (${report.fittedParams.cloth.slidingFrictionMuS}) should recover ~0.165",
        )
    }

    @Test
    fun slowBarBoxSevenFootSessionReducesHeldOutPredictionErrorAboveThreshold() {
        val preset = SampleCalibrationSessions.slowBarBoxSevenFootSession()
        val (profile, report, _) = SampleCalibrationSessions.runFullSessionCalibration(preset)

        assertEquals("slow_barbox_7ft", profile.id)
        assertFalse(profile.slopeMap.isNoticeablyUnlevel, "Bar-box 7-ft table should be level")
        assertTrue(
            report.relativeStoppingErrorReductionFraction > 0.65,
            "Expected > 65% stopping error reduction, got ${report.relativeStoppingErrorReductionFraction}",
        )
        assertTrue(
            report.calibratedReboundSpeedRmsErrorMps < report.defaultReboundSpeedRmsErrorMps,
            "Calibrated rebound RMS (${report.calibratedReboundSpeedRmsErrorMps}) should beat default (${report.defaultReboundSpeedRmsErrorMps})",
        )
        assertTrue(
            report.calibratedThrowRmsErrorRad < report.defaultThrowRmsErrorRad,
            "Calibrated throw RMS (${report.calibratedThrowRmsErrorRad}) should beat default (${report.defaultThrowRmsErrorRad})",
        )
        assertTrue(
            abs(report.fittedParams.cloth.rollingResistanceMuR - 0.0148) < 0.0008,
            "Fitted muR (${report.fittedParams.cloth.rollingResistanceMuR}) should recover ~0.0148",
        )
        assertTrue(
            abs(report.fittedParams.cloth.cushionRestitutionEc - 0.66) < 0.025,
            "Fitted ec (${report.fittedParams.cloth.cushionRestitutionEc}) should recover ~0.66",
        )
    }

    @Test
    fun unlevelHomeEightFootSessionDetectsRollOffAndMatchesSlowRollSlope() {
        val preset = SampleCalibrationSessions.unlevelHomeEightFootSession()
        val (profile, report, _) = SampleCalibrationSessions.runFullSessionCalibration(preset)

        assertEquals("unlevel_home_8ft", profile.id)
        assertTrue(
            profile.slopeMap.isNoticeablyUnlevel,
            "Unlevel home 8-ft table must trigger unlevel warning (maxSlope=${profile.slopeMap.maxSlopeMagnitudeRad})",
        )
        assertTrue(
            profile.slopeMap.maxSlopeMagnitudeRad >= 0.0030,
            "Expected max roll-off near 0.0032 rad, got ${profile.slopeMap.maxSlopeMagnitudeRad}",
        )

        val slowRollSlope = TableLevelCalibration.estimateSlopeFromSlowRoll(
            rollDistanceMeters = preset.slowRollDistanceMeters,
            lateralDriftMeters = preset.slowRollLateralDriftMeters,
            muR = report.fittedParams.cloth.rollingResistanceMuR,
        )
        val gridMeanSlopeY = profile.slopeMap.cells.map { it.slopeYRad }.average()
        val centerLaneSlopeY = profile.slopeMap.cells.filter { it.row == 1 }.map { it.slopeYRad }.average()

        assertTrue(
            abs(slowRollSlope - gridMeanSlopeY) < 0.001,
            "Slow-roll slope ($slowRollSlope) must match 3x3 grid mean slope ($gridMeanSlopeY) within 0.001 rad",
        )
        assertTrue(
            abs(slowRollSlope - centerLaneSlopeY) < 0.001,
            "Slow-roll slope ($slowRollSlope) must match center-lane grid slope ($centerLaneSlopeY) within 0.001 rad",
        )
    }

    @Test
    fun overheadVideoTrackingDetectsSlideToRollBounceAndStopAcrossAllPresets() {
        for (preset in SampleCalibrationSessions.allSessions()) {
            val (_, _, trajectory) = SampleCalibrationSessions.runFullSessionCalibration(preset)
            assertEquals(preset.videoFrames.size, trajectory.points.size)
            assertEquals(preset.videoFrames.size, trajectory.normalizedLumaSamples.size)

            val eventTypes = trajectory.events.map { it.type }.toSet()
            assertTrue(
                TrajectoryEventType.SLIDE_TO_ROLL_TRANSITION in eventTypes,
                "${preset.id} must detect SLIDE_TO_ROLL_TRANSITION, found $eventTypes",
            )
            assertTrue(
                TrajectoryEventType.CUSHION_BOUNCE in eventTypes,
                "${preset.id} must detect CUSHION_BOUNCE, found $eventTypes",
            )
            assertTrue(
                TrajectoryEventType.BALL_STOP in eventTypes,
                "${preset.id} must detect BALL_STOP, found $eventTypes",
            )

            val rawLumaMean = preset.videoFrames.map { it.frameMeanLuma }.average()
            val normLumaMean = trajectory.normalizedLumaSamples.average()
            assertTrue(abs(rawLumaMean - 128.0) < 1.0)
            assertTrue(abs(normLumaMean - 128.0) < 1e-6)
        }
    }

    @Test
    fun platformTiltSensorControllerReturnsNineGridReadingsAndCancelsCameraBumpBias() {
        val controller = PlatformTiltSensorController()
        assertTrue(controller.sensorBackendName.isNotBlank())
        assertEquals(0.08, controller.resolutionDegrees, 1e-9)

        for (preset in SampleCalibrationSessions.allSessions()) {
            val readings = controller.readPairedGridPreset(preset.id)
            assertEquals(9, readings.size, "${preset.id} must provide a 3x3 paired tilt grid")

            val gridCoords = readings.map { it.gridCol to it.gridRow }.toSet()
            assertEquals(9, gridCoords.size, "All 9 (col, row) grid cells must be distinct")

            for (reading in readings) {
                assertTrue(
                    abs(reading.readingZeroDegPitchRad) > 0.010,
                    "Raw 0-deg reading must reflect the ~0.015 rad camera-bump bias",
                )
                assertTrue(
                    abs(reading.reading180DegPitchRad) > 0.010,
                    "Raw 180-deg reading must reflect the ~0.015 rad camera-bump bias",
                )
                val unbiasedPitch = TableLevelCalibration.cancelSensorBias180(
                    reading.readingZeroDegPitchRad,
                    reading.reading180DegPitchRad,
                )
                assertTrue(
                    abs(unbiasedPitch) < 0.001,
                    "Paired 0/180 cancellation must remove the 0.015 rad camera bump, got $unbiasedPitch",
                )
                assertTrue(
                    abs(reading.readingZeroDegRollRad) > 0.010 && abs(reading.reading180DegRollRad) > 0.010,
                    "Raw 0/180 roll readings must reflect the ~0.015 rad camera-bump bias",
                )
                val unbiasedRoll = TableLevelCalibration.cancelSensorBias180(
                    reading.readingZeroDegRollRad,
                    reading.reading180DegRollRad,
                )
                if (preset.id != "unlevel_home_8ft") {
                    assertTrue(
                        abs(unbiasedRoll) < 0.001,
                        "Level preset ${preset.id} roll after cancellation should be near zero, got $unbiasedRoll",
                    )
                } else {
                    assertTrue(
                        unbiasedRoll in 0.0004..0.0033,
                        "Unlevel preset roll after cancellation should reflect 0.5..3.2 mm/m slope, got $unbiasedRoll",
                    )
                }
            }
        }
    }

    @Test
    fun defaultSavedProfilesPreserveOrderAndEffectiveTableSpecs() {
        val sessions = SampleCalibrationSessions.allSessions()
        val profiles = SampleCalibrationSessions.defaultSavedProfiles()

        assertEquals(3, sessions.size)
        assertEquals(listOf("fast_tournament_9ft", "slow_barbox_7ft", "unlevel_home_8ft"), sessions.map { it.id })
        assertEquals(sessions.map { it.id }, profiles.map { it.id })
        assertEquals(sessions.map { it.title }, profiles.map { it.profileName })

        val fastSpec = profiles[0].toEffectiveTableSpec()
        val slowSpec = profiles[1].toEffectiveTableSpec()
        assertTrue(fastSpec.cloth.rollingResistanceMuR < slowSpec.cloth.rollingResistanceMuR)
        assertTrue(fastSpec.cloth.cushionRestitutionEc > slowSpec.cloth.cushionRestitutionEc)
        assertEquals("Fast Tournament 9-ft", fastSpec.name)
    }

    @Test
    fun unknownPresetIdFallsBackSafelyWithoutCrashing() {
        val controller = PlatformTiltSensorController()
        val fallbackReadings = controller.readPairedGridPreset("non_existent_table_id")
        assertEquals(9, fallbackReadings.size)
    }
}
