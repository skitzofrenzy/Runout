package com.runout.app.camera

import com.runout.engine.math.Vec2
import com.runout.engine.vision.ClothHueCategory
import com.runout.engine.vision.DetectedGameVariant
import com.runout.engine.vision.GameTypeSuggester
import com.runout.engine.vision.PerspectiveHomography
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhotoCaptureBenchmarksTest {
    @Test
    fun angledPresetsAchieveSubCentimetreBallHeightAccuracyWhileClothHomographyFails() {
        val angledPresets = listOf(
            SampleCapturedPhotos.angledNineFootNineBall(),
            SampleCapturedPhotos.angledEightFootEightBallWithFrozenAndWarmLight(),
        )

        for (photo in angledPresets) {
            val quality = PhotoCaptureEvaluator.evaluateQuality(
                sharpnessScore = photo.sharpnessScore,
                glareFraction = photo.glareFraction,
                quadCornersPx = photo.detectedQuadCornersPx,
                intrinsics = photo.intrinsics,
            )
            assertTrue(quality.allFourCornersVisible, "${photo.id} corners must fit inside frame")
            assertTrue(quality.isAcceptable, "${photo.id} capture quality should be acceptable")
            assertTrue(quality.warnings.isEmpty(), "${photo.id} should not trigger quality warnings")

            val rectified = PhotoCaptureEvaluator.processCapturedPhoto(photo)
            val correctedMedianError = PhotoCaptureEvaluator.computeMedianPositionErrorMeters(
                rectified = rectified,
                groundTruth = photo.groundTruthBallPositionsMeters,
            )
            assertTrue(
                correctedMedianError < 0.01,
                "${photo.id} corrected median error ($correctedMedianError m) must be < 0.01 m",
            )

            val clothErrors = rectified.identifiedBalls.map { identified ->
                val expected = photo.groundTruthBallPositionsMeters.getValue(identified.ball.number)
                identified.rawClothPositionMeters.distanceTo(expected)
            }.sorted()
            val uncorrectedMedianError = medianOfSorted(clothErrors)
            assertTrue(
                uncorrectedMedianError > 0.025,
                "${photo.id} uncorrected cloth median error ($uncorrectedMedianError m) should exceed 0.025 m",
            )
        }
    }

    @Test
    fun overheadPresetMaintainsSubCentimetreAccuracyAndSuggestsTenBall() {
        val photo = SampleCapturedPhotos.overheadNineFootTenBall()
        val quality = PhotoCaptureEvaluator.evaluateQuality(
            sharpnessScore = photo.sharpnessScore,
            glareFraction = photo.glareFraction,
            quadCornersPx = photo.detectedQuadCornersPx,
            intrinsics = photo.intrinsics,
        )
        assertTrue(quality.isAcceptable)
        assertTrue(quality.warnings.isEmpty())

        val rectified = PhotoCaptureEvaluator.processCapturedPhoto(photo)
        val medianError = PhotoCaptureEvaluator.computeMedianPositionErrorMeters(
            rectified = rectified,
            groundTruth = photo.groundTruthBallPositionsMeters,
        )
        assertTrue(medianError < 0.01, "Overhead median error ($medianError m) must be < 0.01 m")

        val topSuggestion = GameTypeSuggester.suggestGameTypes(rectified.layout).first()
        assertEquals(DetectedGameVariant.TEN_BALL, topSuggestion.variant)
    }

    @Test
    fun ballIdentityAccuracyIsOneHundredPercentWithAtMostOneReviewPerPhoto() {
        val benchmarkPresets = listOf(
            SampleCapturedPhotos.angledNineFootNineBall(),
            SampleCapturedPhotos.angledEightFootEightBallWithFrozenAndWarmLight(),
            SampleCapturedPhotos.overheadNineFootTenBall(),
        )

        var totalBalls = 0
        var correctBalls = 0
        var totalLowConfidenceReviews = 0

        for (photo in benchmarkPresets) {
            val rectified = PhotoCaptureEvaluator.processCapturedPhoto(photo)
            assertEquals(
                photo.groundTruthBallPositionsMeters.size,
                rectified.layout.balls.size,
                "${photo.id} should recover every ball",
            )
            assertEquals(
                photo.groundTruthBallPositionsMeters.keys,
                rectified.layout.balls.map { it.number }.toSet(),
                "${photo.id} should match the exact set of ground-truth ball numbers",
            )

            for (identified in rectified.identifiedBalls) {
                totalBalls++
                val expectedPos = photo.groundTruthBallPositionsMeters[identified.ball.number]
                if (expectedPos != null && identified.ball.position.distanceTo(expectedPos) < 0.02) {
                    correctBalls++
                }
                if (identified.needsUserReview) {
                    totalLowConfidenceReviews++
                }
            }
        }

        val accuracy = correctBalls.toDouble() / totalBalls.toDouble()
        val avgReviewsPerPhoto = totalLowConfidenceReviews.toDouble() / benchmarkPresets.size.toDouble()
        assertEquals(1.0, accuracy, "Expected 100% ball identity accuracy across presets")
        assertTrue(avgReviewsPerPhoto <= 1.0, "Expected <= 1.0 review per photo, got $avgReviewsPerPhoto")
        assertTrue(totalLowConfidenceReviews >= 1, "Warm-light confused ball should request review")
    }

    @Test
    fun angledEightBallPresetDetectsCushionAndBallFrozenContactsAndWarmLightReview() {
        val photo = SampleCapturedPhotos.angledEightFootEightBallWithFrozenAndWarmLight()
        val rectified = PhotoCaptureEvaluator.processCapturedPhoto(photo)

        assertFalse(rectified.layout.hasOverlappingBalls(), "Rectified layout must resolve overlaps")
        assertTrue(rectified.frozenBalls.isNotEmpty(), "Expected frozen ball detections")

        val ball14Frozen = rectified.frozenBalls.firstOrNull { it.ballNumber == 14 }
        assertNotNull(ball14Frozen, "Ball 14 near the cushion nose must be flagged as frozen")
        assertTrue(ball14Frozen.frozenToCushion, "Ball 14 must be flagged frozenToCushion")

        val ball4Frozen = rectified.frozenBalls.firstOrNull { it.ballNumber == 4 }
        assertNotNull(ball4Frozen, "Ball 4 touching Ball 11 must be flagged as frozen")
        assertTrue(11 in ball4Frozen.frozenToBallNumbers, "Ball 4 must list Ball 11 as touching")

        val ball7Result = rectified.identifiedBalls.firstOrNull { it.ball.number == 7 }
        assertNotNull(ball7Result, "Warm-light confused Ball 7 must still be identified via Hungarian matching")
        assertTrue(ball7Result.needsUserReview, "Warm-light Ball 7 should be flagged for user review")
    }

    @Test
    fun glareAndBlurSampleRejectsCaptureAndReportsBothWarnings() {
        val warningSample = SampleCapturedPhotos.glareAndBlurWarningSample()
        val report = PhotoCaptureEvaluator.evaluateQuality(
            sharpnessScore = warningSample.sharpnessScore,
            glareFraction = warningSample.glareFraction,
            quadCornersPx = warningSample.detectedQuadCornersPx,
            intrinsics = warningSample.intrinsics,
        )

        assertTrue(report.allFourCornersVisible)
        assertFalse(report.isAcceptable)
        assertTrue(
            report.warnings.any { it.contains("sharpness", ignoreCase = true) },
            "Expected a sharpness warning, got ${report.warnings}",
        )
        assertTrue(
            report.warnings.any { it.contains("glare", ignoreCase = true) },
            "Expected a glare warning, got ${report.warnings}",
        )

        val clippedCorners = warningSample.detectedQuadCornersPx.mapIndexed { idx, corner ->
            if (idx == 0) Vec2(-24.0, corner.y) else corner
        }
        val clippedReport = PhotoCaptureEvaluator.evaluateQuality(
            sharpnessScore = 0.90,
            glareFraction = 0.01,
            quadCornersPx = clippedCorners,
            intrinsics = warningSample.intrinsics,
        )
        assertFalse(clippedReport.allFourCornersVisible)
        assertFalse(clippedReport.isAcceptable)
        assertTrue(clippedReport.warnings.any { it.contains("corner", ignoreCase = true) })
    }

    @Test
    fun adjustedCornerOverrideEstimatesCameraPoseAndPlatformControllerEnforcesInMemoryContract() {
        val controller = PlatformCameraController()
        assertTrue(controller.platformName.isNotBlank())
        assertTrue(controller.stripsExifAndGpsInMemory)
        assertEquals(1920, controller.defaultIntrinsics().imageWidthPx)
        assertEquals(1080, controller.defaultIntrinsics().imageHeightPx)

        val presets = SampleCapturedPhotos.allPresets()
        assertEquals(4, presets.size)
        for (preset in presets) {
            assertEquals(preset.id, controller.capturePresetPhoto(preset.id).id)
        }

        val photo = controller.capturePresetPhoto("angled_9ft_9ball")
        val adjustedCorners = photo.detectedQuadCornersPx.mapIndexed { idx, corner ->
            if (idx == 0) Vec2(corner.x + 1.5, corner.y + 1.0) else corner
        }
        val homography = PerspectiveHomography.fromTableQuad(adjustedCorners, photo.tableSpec)
        val estimatedPose = homography.estimateCameraPositionMeters(photo.tableSpec, photo.intrinsics)
        assertTrue(estimatedPose.z > 0.3)

        val rectified = PhotoCaptureEvaluator.processCapturedPhoto(
            photo = photo,
            overriddenQuadCornersPx = adjustedCorners,
        )
        assertEquals(photo.groundTruthBallPositionsMeters.size, rectified.layout.balls.size)
        assertFalse(rectified.layout.hasOverlappingBalls())
    }

    @Test
    fun rawArgbFrameDecodingOnTournamentBlueRecoversClothAndSubFifteenMmAccuracy() {
        assertRawPixelRoundTrip(
            preset = SampleCapturedPhotos.angledNineFootNineBall(),
            expectedCloth = ClothHueCategory.TOURNAMENT_BLUE,
        )
    }

    @Test
    fun rawArgbFrameDecodingOnSimonisGreenRecoversClothAndSubFifteenMmAccuracy() {
        assertRawPixelRoundTrip(
            preset = SampleCapturedPhotos.overheadNineFootTenBall(),
            expectedCloth = ClothHueCategory.SIMONIS_GREEN,
        )
    }

    private fun assertRawPixelRoundTrip(
        preset: CapturedTablePhoto,
        expectedCloth: ClothHueCategory,
    ) {
        val frame = SampleCapturedPhotos.renderPresetAsRawArgbFrame(
            preset = preset,
            clothCategory = expectedCloth,
        )
        assertEquals(640, frame.width)
        assertEquals(360, frame.height)

        val (summary, rectified) = PhotoCaptureEvaluator.processRawArgbFrame(
            frame = frame,
            tableSpec = preset.tableSpec,
        )

        assertEquals(expectedCloth, summary.clothSurface.category)
        assertTrue(
            summary.clothSurface.clothCoverageFraction > 0.35,
            "Expected substantial cloth coverage on $expectedCloth, got ${summary.clothSurface.clothCoverageFraction}",
        )
        assertEquals(4, summary.detectedQuadCornersPx.size)
        assertEquals(
            preset.groundTruthBallPositionsMeters.size,
            summary.detectedCandidates.size,
            "${preset.id} on $expectedCloth should detect every rendered ball blob",
        )
        assertEquals(
            preset.groundTruthBallPositionsMeters.keys,
            rectified.layout.balls.map { it.number }.toSet(),
            "${preset.id} on $expectedCloth should identify the exact ground-truth ball numbers",
        )
        assertFalse(rectified.layout.hasOverlappingBalls())

        val medianErrorMeters = PhotoCaptureEvaluator.computeMedianPositionErrorMeters(
            rectified = rectified,
            groundTruth = preset.groundTruthBallPositionsMeters,
        )
        assertTrue(
            medianErrorMeters < 0.015,
            "${preset.id} on $expectedCloth raw pixel median error ($medianErrorMeters m) must be < 0.015 m",
        )
    }

    private fun medianOfSorted(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            0.5 * (sorted[mid - 1] + sorted[mid])
        }
    }
}
