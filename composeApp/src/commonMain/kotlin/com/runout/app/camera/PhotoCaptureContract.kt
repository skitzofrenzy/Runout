package com.runout.app.camera

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.vision.ArgbImageFrame
import com.runout.engine.vision.BallIdentificationAndAssignment
import com.runout.engine.vision.CameraIntrinsicsSpec
import com.runout.engine.vision.ClothHueCategory
import com.runout.engine.vision.DetectedBallCandidate
import com.runout.engine.vision.PerspectiveHomography
import com.runout.engine.vision.RawFrameDetectionSummary
import com.runout.engine.vision.RawImageTableDetector
import com.runout.engine.vision.Vec3
import com.runout.engine.vision.VisionRectificationResult
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Result of pre-rectification capture quality checks (sharpness, specular glare, and framing).
 */
data class CaptureQualityReport(
    val sharpnessScore: Double,
    val glareFraction: Double,
    val allFourCornersVisible: Boolean,
    val isAcceptable: Boolean,
    val warnings: List<String>,
)

/**
 * In-memory snapshot of a captured (or simulated) table photograph, free of persisted EXIF/GPS tags.
 *
 * [detectedQuadCornersPx] are the cushion-nose corners `[headLeft, footLeft, footRight, headRight]`.
 * [cornersNeedingReview] has one flag per corner, in the same order: true where a neighbouring
 * cushion edge could not be seen and the corner was estimated from the rail (empty when unknown).
 */
data class CapturedTablePhoto(
    val id: String,
    val title: String,
    val subtitle: String,
    val tableSpec: TableSpec,
    val intrinsics: CameraIntrinsicsSpec,
    val trueCameraPoseMeters: Vec3,
    val detectedQuadCornersPx: List<Vec2>,
    val rawDetections: List<DetectedBallCandidate>,
    val groundTruthBallPositionsMeters: Map<Int, Vec2>,
    val sharpnessScore: Double,
    val glareFraction: Double,
    val cornersNeedingReview: List<Boolean> = emptyList(),
)

data class RawPhotoAnalysisBundle(
    val photo: CapturedTablePhoto,
    val summary: RawFrameDetectionSummary,
    val inferredBallSet: BallSetProfile,
)

/**
 * Capture quality gating, planar homography + 3D ball-height rectification, and accuracy evaluation.
 */
object PhotoCaptureEvaluator {
    const val MIN_SHARPNESS_SCORE: Double = 0.60
    const val MAX_GLARE_FRACTION: Double = 0.08

    /**
     * Evaluates capture sharpness, glare fraction, and whether all four cushion-nose corners lie
     * inside `[0..imageWidthPx] x [0..imageHeightPx]`.
     */
    fun evaluateQuality(
        sharpnessScore: Double,
        glareFraction: Double,
        quadCornersPx: List<Vec2>,
        intrinsics: CameraIntrinsicsSpec,
    ): CaptureQualityReport {
        val maxX = intrinsics.imageWidthPx.toDouble()
        val maxY = intrinsics.imageHeightPx.toDouble()
        val sharpnessOk = sharpnessScore.isFinite() && sharpnessScore >= MIN_SHARPNESS_SCORE
        val glareOk = glareFraction.isFinite() && glareFraction >= 0.0 && glareFraction <= MAX_GLARE_FRACTION
        val allFourCornersVisible = quadCornersPx.size == 4 && quadCornersPx.all { corner ->
            corner.x.isFinite() &&
                corner.y.isFinite() &&
                corner.x in 0.0..maxX &&
                corner.y in 0.0..maxY
        }

        val warnings = buildList {
            if (!sharpnessOk) {
                add(
                    "Low sharpness (${formatTwoDecimals(sharpnessScore)} < " +
                        "${formatTwoDecimals(MIN_SHARPNESS_SCORE)}): hold the camera steady or refocus.",
                )
            }
            if (!glareOk) {
                add(
                    "High glare (${formatPercent(glareFraction)} > " +
                        "${formatPercent(MAX_GLARE_FRACTION)}): overhead light reflection may obscure ball colours.",
                )
            }
            if (!allFourCornersVisible) {
                if (quadCornersPx.size != 4) {
                    add(
                        "Expected 4 table corners (detected ${quadCornersPx.size}); " +
                            "re-frame the full playing surface.",
                    )
                } else {
                    add(
                        "One or more table corners are clipped outside the " +
                            "${intrinsics.imageWidthPx}x${intrinsics.imageHeightPx} frame; step back slightly.",
                    )
                }
            }
        }

        return CaptureQualityReport(
            sharpnessScore = sharpnessScore,
            glareFraction = glareFraction,
            allFourCornersVisible = allFourCornersVisible,
            isAcceptable = sharpnessOk && glareOk && allFourCornersVisible,
            warnings = warnings,
        )
    }

    /**
     * Builds the DLT planar homography from [overriddenQuadCornersPx], resolves the 3D camera pose
     * (using [CapturedTablePhoto.trueCameraPoseMeters] when corners match the original detection, or
     * decomposing the adjusted homography against intrinsics when corners were dragged), and runs
     * Hungarian ball identification with ball-height parallax correction.
     */
    fun processCapturedPhoto(
        photo: CapturedTablePhoto,
        overriddenQuadCornersPx: List<Vec2> = photo.detectedQuadCornersPx,
        ballSet: BallSetProfile = BallSetProfile.standardPool(),
    ): VisionRectificationResult {
        val homography = PerspectiveHomography.fromTableQuad(overriddenQuadCornersPx, photo.tableSpec)
        val cameraPose = if (overriddenQuadCornersPx == photo.detectedQuadCornersPx) {
            photo.trueCameraPoseMeters
        } else {
            homography.estimateCameraPositionMeters(photo.tableSpec, photo.intrinsics)
        }
        return BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = photo.rawDetections,
            homography = homography,
            table = photo.tableSpec,
            ballSet = ballSet,
            cameraPositionMeters = cameraPose,
        )
    }

    /**
     * Runs [RawImageTableDetector.analyzeArgbFrame] on a raw [ArgbImageFrame] pixel buffer, builds
     * the planar homography from [overriddenQuadCornersPx] or the auto-detected cushion-nose corners,
     * and performs 3D ball-height-corrected Hungarian ball identification.
     */
    fun processRawArgbFrame(
        frame: ArgbImageFrame,
        tableSpec: TableSpec = TableSpec.nineFootPool(),
        ballSet: BallSetProfile = BallSetProfile.standardPool(),
        cameraPoseMeters: Vec3? = null,
        overriddenQuadCornersPx: List<Vec2>? = null,
    ): Pair<RawFrameDetectionSummary, VisionRectificationResult> {
        val summary = RawImageTableDetector.analyzeArgbFrame(frame)
        val (autoCorners, autoPose) = RawImageTableDetector.orientQuadForHomography(
            summary.detectedQuadCornersPx,
            tableSpec,
        )
        val corners = overriddenQuadCornersPx ?: autoCorners
        val resolvedPose = cameraPoseMeters ?: autoPose
        val resolvedBallSet = BallSetProfile.inferFromDetectedColors(
            summary.detectedCandidates.map { it.dominantColor },
            ballSet,
        )
        val homography = PerspectiveHomography.fromTableQuad(corners, tableSpec)
        val rectified = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = summary.detectedCandidates,
            homography = homography,
            table = tableSpec,
            ballSet = resolvedBallSet,
            cameraPositionMeters = resolvedPose,
        )
        return summary to rectified
    }

    /**
     * Converts a raw [ArgbImageFrame] (from camera capture or gallery import) into a rectified
     * [CapturedTablePhoto] plus auto-detected corners, ball candidates, and inferred [BallSetProfile].
     */
    fun createCapturedPhotoFromRawFrame(
        id: String,
        title: String,
        subtitle: String,
        frame: ArgbImageFrame,
        tableSpec: TableSpec = TableSpec.nineFootPool(),
        baseBallSet: BallSetProfile = BallSetProfile.standardPool(),
    ): RawPhotoAnalysisBundle {
        val summary = RawImageTableDetector.analyzeArgbFrame(frame)
        val (orientedCorners, autoPose) = RawImageTableDetector.orientQuadForHomography(
            summary.detectedQuadCornersPx,
            tableSpec,
        )
        val inferredBallSet = BallSetProfile.inferFromDetectedColors(
            summary.detectedCandidates.map { it.dominantColor },
            baseBallSet,
        )
        val intrinsics = CameraIntrinsicsSpec(
            focalLengthPx = frame.width.toDouble() * 0.9,
            principalPointPx = Vec2(frame.width * 0.5, frame.height * 0.5),
            imageWidthPx = frame.width,
            imageHeightPx = frame.height,
        )
        val photo = CapturedTablePhoto(
            id = id,
            title = title,
            subtitle = subtitle,
            tableSpec = tableSpec,
            intrinsics = intrinsics,
            trueCameraPoseMeters = autoPose,
            detectedQuadCornersPx = orientedCorners,
            rawDetections = summary.detectedCandidates,
            groundTruthBallPositionsMeters = emptyMap(),
            sharpnessScore = summary.sharpnessScore,
            glareFraction = summary.glareFraction,
            cornersNeedingReview = cornerReviewFlags(summary, orientedCorners),
        )
        return RawPhotoAnalysisBundle(
            photo = photo,
            summary = summary,
            inferredBallSet = inferredBallSet,
        )
    }

    /**
     * One flag per corner of [orientedCorners]: true when either cushion side meeting at that
     * corner used the cushion-width fallback. [RawFrameDetectionSummary.quadEdges] is indexed by the
     * unoriented quad, whose side `i` joins corners `i` and `i + 1`.
     */
    private fun cornerReviewFlags(summary: RawFrameDetectionSummary, orientedCorners: List<Vec2>): List<Boolean> {
        val raw = summary.detectedQuadCornersPx
        val edges = summary.quadEdges
        if (raw.size != 4 || edges.size != 4 || orientedCorners.size != 4) return emptyList()
        return orientedCorners.map { corner ->
            val k = raw.indices.minByOrNull { raw[it].distanceTo(corner) } ?: return emptyList()
            edges[k].usedFallback || edges[(k + 3) % 4].usedFallback
        }
    }

    /**
     * Computes the median Euclidean position error in SI metres across all rectified balls whose
     * number is present in [groundTruth]. Returns `0.0` if no balls match.
     */
    fun computeMedianPositionErrorMeters(
        rectified: VisionRectificationResult,
        groundTruth: Map<Int, Vec2>,
    ): Double {
        val errors = rectified.layout.balls.mapNotNull { ball ->
            val expected = groundTruth[ball.number] ?: return@mapNotNull null
            ball.position.distanceTo(expected)
        }.sorted()

        if (errors.isEmpty()) return 0.0
        val mid = errors.size / 2
        return if (errors.size % 2 == 1) {
            errors[mid]
        } else {
            0.5 * (errors[mid - 1] + errors[mid])
        }
    }

    private fun formatTwoDecimals(value: Double): String {
        if (!value.isFinite()) return "0.00"
        val hundredths = (value * 100.0).roundToInt()
        val sign = if (hundredths < 0) "-" else ""
        val absVal = abs(hundredths)
        val whole = absVal / 100
        val frac = absVal % 100
        val fracText = if (frac < 10) "0$frac" else "$frac"
        return "$sign$whole.$fracText"
    }

    private fun formatPercent(fraction: Double): String {
        if (!fraction.isFinite()) return "0%"
        return "${(fraction * 100.0).roundToInt()}%"
    }
}

/**
 * Held-out synthetic camera photo presets with exact 3D-projected ball centres and realistic
 * sub-pixel detector jitter (`±0.6 px` to `±1.2 px`) for offline evaluation and demo workflows.
 */
object SampleCapturedPhotos {
    private const val DEFAULT_FOCAL_LENGTH_PX: Double = 1150.0
    private const val IMAGE_WIDTH_PX: Int = 1920
    private const val IMAGE_HEIGHT_PX: Int = 1080
    private const val RAW_FRAME_WIDTH_PX: Int = 640
    private const val RAW_FRAME_HEIGHT_PX: Int = 360

    private val LANDSCAPE_RAW_QUAD_PX: List<Vec2> = listOf(
        Vec2(76.0, 56.0),
        Vec2(564.0, 56.0),
        Vec2(596.0, 312.0),
        Vec2(44.0, 312.0),
    )

    fun defaultIntrinsics(): CameraIntrinsicsSpec = CameraIntrinsicsSpec(
        focalLengthPx = DEFAULT_FOCAL_LENGTH_PX,
        principalPointPx = Vec2(IMAGE_WIDTH_PX * 0.5, IMAGE_HEIGHT_PX * 0.5),
        imageWidthPx = IMAGE_WIDTH_PX,
        imageHeightPx = IMAGE_HEIGHT_PX,
    )

    private data class BallSpec(
        val number: Int,
        val positionMeters: Vec2,
        val dominantColor: BallPaletteColor,
        val secondaryColor: BallPaletteColor? = null,
        val whiteFraction: Double,
        val detectorConfidence: Double,
        val jitterPx: Vec2,
    )

    /**
     * Preset 1: 9-ft 9-ball rack photographed from a raised foot-end camera angle.
     */
    fun angledNineFootNineBall(): CapturedTablePhoto {
        val table = TableSpec.nineFootPool()
        val cameraPose = Vec3(x = 3.15, y = 0.635, z = 1.55)
        val lookAt = Vec3(x = 1.72, y = 0.635, z = 0.0)
        val rightHint = Vec3(x = 0.0, y = 1.0, z = 0.0)
        val specs = listOf(
            BallSpec(
                number = 0,
                positionMeters = Vec2(0.58, 0.48),
                dominantColor = BallPaletteColor.WHITE,
                whiteFraction = 0.94,
                detectorConfidence = 0.96,
                jitterPx = Vec2(0.7, -0.8),
            ),
            BallSpec(
                number = 1,
                positionMeters = Vec2(1.12, 0.88),
                dominantColor = BallPaletteColor.YELLOW,
                whiteFraction = 0.14,
                detectorConfidence = 0.95,
                jitterPx = Vec2(-0.9, 0.7),
            ),
            BallSpec(
                number = 2,
                positionMeters = Vec2(1.48, 0.34),
                dominantColor = BallPaletteColor.BLUE,
                whiteFraction = 0.15,
                detectorConfidence = 0.94,
                jitterPx = Vec2(0.8, 0.8),
            ),
            BallSpec(
                number = 3,
                positionMeters = Vec2(0.86, 0.28),
                dominantColor = BallPaletteColor.RED,
                whiteFraction = 0.14,
                detectorConfidence = 0.93,
                jitterPx = Vec2(-0.7, -0.9),
            ),
            BallSpec(
                number = 5,
                positionMeters = Vec2(1.52, 0.96),
                dominantColor = BallPaletteColor.ORANGE,
                whiteFraction = 0.16,
                detectorConfidence = 0.92,
                jitterPx = Vec2(1.0, -0.6),
            ),
            BallSpec(
                number = 7,
                positionMeters = Vec2(1.32, 0.60),
                dominantColor = BallPaletteColor.MAROON,
                whiteFraction = 0.16,
                detectorConfidence = 0.93,
                jitterPx = Vec2(-0.8, 0.9),
            ),
            BallSpec(
                number = 9,
                positionMeters = Vec2(1.88, 0.64),
                dominantColor = BallPaletteColor.YELLOW,
                whiteFraction = 0.54,
                detectorConfidence = 0.94,
                jitterPx = Vec2(0.7, 0.6),
            ),
        )
        return buildPresetPhoto(
            id = "angled_9ft_9ball",
            title = "9-ft 9-Ball (Foot-End Angled)",
            subtitle = "Raised foot-end phone angle • strong 3D parallax correction",
            tableSpec = table,
            cameraPose = cameraPose,
            lookAt = lookAt,
            rightHint = rightHint,
            ballSpecs = specs,
            sharpnessScore = 0.88,
            glareFraction = 0.02,
        )
    }

    /**
     * Preset 2: 8-ft 8-ball layout from a head-end angled view featuring a cushion-frozen ball,
     * a touching pair of balls, and a warm-light confused maroon/red detection needing review.
     */
    fun angledEightFootEightBallWithFrozenAndWarmLight(): CapturedTablePhoto {
        val table = TableSpec.eightFootPool()
        val r = table.ballRadiusMeters
        val cameraPose = Vec3(x = -0.55, y = 0.56, z = 1.48)
        val lookAt = Vec3(x = 0.75, y = 0.56, z = 0.0)
        val rightHint = Vec3(x = 0.0, y = -1.0, z = 0.0)
        val specs = listOf(
            BallSpec(
                number = 0,
                positionMeters = Vec2(0.76, 0.42),
                dominantColor = BallPaletteColor.WHITE,
                whiteFraction = 0.94,
                detectorConfidence = 0.95,
                jitterPx = Vec2(-0.7, 0.8),
            ),
            BallSpec(
                number = 2,
                positionMeters = Vec2(1.08, 0.84),
                dominantColor = BallPaletteColor.BLUE,
                whiteFraction = 0.15,
                detectorConfidence = 0.93,
                jitterPx = Vec2(0.8, -0.7),
            ),
            BallSpec(
                number = 3,
                positionMeters = Vec2(1.26, 0.28),
                dominantColor = BallPaletteColor.RED,
                whiteFraction = 0.14,
                detectorConfidence = 0.93,
                jitterPx = Vec2(-0.8, -0.7),
            ),
            BallSpec(
                number = 4,
                positionMeters = Vec2(1.52, 0.72),
                dominantColor = BallPaletteColor.PURPLE,
                whiteFraction = 0.16,
                detectorConfidence = 0.94,
                // Slight -v jitter nudges Ball 4 toward Ball 11 (+X) so contact stays within 2 mm after push-apart
                jitterPx = Vec2(-0.7, -0.6),
            ),
            BallSpec(
                number = 7,
                positionMeters = Vec2(1.84, 0.44),
                dominantColor = BallPaletteColor.RED,
                secondaryColor = BallPaletteColor.MAROON,
                whiteFraction = 0.16,
                detectorConfidence = 0.74,
                jitterPx = Vec2(0.8, 0.7),
            ),
            BallSpec(
                number = 8,
                positionMeters = Vec2(1.94, 0.76),
                dominantColor = BallPaletteColor.BLACK,
                whiteFraction = 0.08,
                detectorConfidence = 0.95,
                jitterPx = Vec2(-0.9, 0.6),
            ),
            BallSpec(
                number = 11,
                positionMeters = Vec2(1.52 + 2.0 * r + 0.0008, 0.72),
                dominantColor = BallPaletteColor.RED,
                whiteFraction = 0.52,
                detectorConfidence = 0.92,
                // Slight +v jitter nudges Ball 11 toward Ball 4 (-X) so pair contact stays frozen
                jitterPx = Vec2(0.7, 0.6),
            ),
            BallSpec(
                number = 14,
                positionMeters = Vec2(1.42, r + 0.0005),
                dominantColor = BallPaletteColor.GREEN,
                whiteFraction = 0.53,
                detectorConfidence = 0.93,
                // +u corresponds to -Y (toward the left cushion nose) so cushion contact remains intact
                jitterPx = Vec2(0.6, 0.7),
            ),
        )
        return buildPresetPhoto(
            id = "angled_8ft_8ball_frozen",
            title = "8-ft 8-Ball (Frozen & Warm Light)",
            subtitle = "Head-end angled view • cushion/ball contacts & warm-light review",
            tableSpec = table,
            cameraPose = cameraPose,
            lookAt = lookAt,
            rightHint = rightHint,
            ballSpecs = specs,
            sharpnessScore = 0.82,
            glareFraction = 0.04,
        )
    }

    /**
     * Preset 3: 9-ft 10-ball layout captured from a high overhead position.
     */
    fun overheadNineFootTenBall(): CapturedTablePhoto {
        val table = TableSpec.nineFootPool()
        val cameraPose = Vec3(x = 1.27, y = 0.635, z = 2.25)
        val lookAt = Vec3(x = 1.27, y = 0.635, z = 0.0)
        val rightHint = Vec3(x = 1.0, y = 0.0, z = 0.0)
        val specs = listOf(
            BallSpec(
                number = 0,
                positionMeters = Vec2(0.62, 0.54),
                dominantColor = BallPaletteColor.WHITE,
                whiteFraction = 0.95,
                detectorConfidence = 0.97,
                jitterPx = Vec2(0.6, -0.6),
            ),
            BallSpec(
                number = 1,
                positionMeters = Vec2(1.18, 0.36),
                dominantColor = BallPaletteColor.YELLOW,
                whiteFraction = 0.14,
                detectorConfidence = 0.96,
                jitterPx = Vec2(-0.7, 0.6),
            ),
            BallSpec(
                number = 4,
                positionMeters = Vec2(1.48, 0.88),
                dominantColor = BallPaletteColor.PURPLE,
                whiteFraction = 0.15,
                detectorConfidence = 0.95,
                jitterPx = Vec2(0.7, 0.7),
            ),
            BallSpec(
                number = 6,
                positionMeters = Vec2(1.86, 0.42),
                dominantColor = BallPaletteColor.GREEN,
                whiteFraction = 0.15,
                detectorConfidence = 0.96,
                jitterPx = Vec2(-0.6, -0.7),
            ),
            BallSpec(
                number = 8,
                positionMeters = Vec2(2.04, 0.78),
                dominantColor = BallPaletteColor.BLACK,
                whiteFraction = 0.08,
                detectorConfidence = 0.97,
                jitterPx = Vec2(0.8, -0.6),
            ),
            BallSpec(
                number = 10,
                positionMeters = table.footSpot(),
                dominantColor = BallPaletteColor.BLUE,
                whiteFraction = 0.52,
                detectorConfidence = 0.95,
                jitterPx = Vec2(-0.7, 0.8),
            ),
        )
        return buildPresetPhoto(
            id = "overhead_9ft_10ball",
            title = "9-ft 10-Ball (Near-Overhead)",
            subtitle = "Overhead camera mount • minimal perspective foreshortening",
            tableSpec = table,
            cameraPose = cameraPose,
            lookAt = lookAt,
            rightHint = rightHint,
            ballSpecs = specs,
            sharpnessScore = 0.94,
            glareFraction = 0.01,
        )
    }

    /**
     * Preset 4: 7-ft bar-box capture exhibiting low sharpness and high glare that trigger both warnings.
     */
    fun glareAndBlurWarningSample(): CapturedTablePhoto {
        val table = TableSpec.sevenFootPool()
        val cameraPose = Vec3(x = 2.48, y = 0.495, z = 1.38)
        val lookAt = Vec3(x = 1.30, y = 0.495, z = 0.0)
        val rightHint = Vec3(x = 0.0, y = 1.0, z = 0.0)
        val specs = listOf(
            BallSpec(
                number = 0,
                positionMeters = Vec2(0.54, 0.44),
                dominantColor = BallPaletteColor.WHITE,
                whiteFraction = 0.92,
                detectorConfidence = 0.86,
                jitterPx = Vec2(0.8, -0.7),
            ),
            BallSpec(
                number = 1,
                positionMeters = Vec2(0.92, 0.68),
                dominantColor = BallPaletteColor.YELLOW,
                whiteFraction = 0.15,
                detectorConfidence = 0.85,
                jitterPx = Vec2(-0.9, 0.8),
            ),
            BallSpec(
                number = 2,
                positionMeters = Vec2(1.18, 0.30),
                dominantColor = BallPaletteColor.BLUE,
                whiteFraction = 0.16,
                detectorConfidence = 0.84,
                jitterPx = Vec2(0.7, 0.9),
            ),
            BallSpec(
                number = 8,
                positionMeters = Vec2(1.42, 0.56),
                dominantColor = BallPaletteColor.BLACK,
                whiteFraction = 0.09,
                detectorConfidence = 0.88,
                jitterPx = Vec2(-0.8, -0.8),
            ),
            BallSpec(
                number = 12,
                positionMeters = Vec2(1.56, 0.34),
                dominantColor = BallPaletteColor.PURPLE,
                whiteFraction = 0.50,
                detectorConfidence = 0.84,
                jitterPx = Vec2(0.9, -0.7),
            ),
        )
        return buildPresetPhoto(
            id = "warning_glare_blur",
            title = "7-ft Bar Box (Glare & Blur)",
            subtitle = "Low-sharpness & specular glare warning sample",
            tableSpec = table,
            cameraPose = cameraPose,
            lookAt = lookAt,
            rightHint = rightHint,
            ballSpecs = specs,
            sharpnessScore = 0.45,
            glareFraction = 0.14,
        )
    }

    fun allPresets(): List<CapturedTablePhoto> = listOf(
        angledNineFootNineBall(),
        angledEightFootEightBallWithFrozenAndWarmLight(),
        overheadNineFootTenBall(),
        glareAndBlurWarningSample(),
    )

    /**
     * Renders [preset]'s ground-truth ball layout onto a synthetic 640x360 ARGB pixel frame using
     * the standard landscape table quad `[TL, TR, BR, BL]` at the cushion noses, cloth-covered
     * cushions and pockets around it, and a head-end camera pose so
     * [PhotoCaptureEvaluator.processRawArgbFrame] can decode it directly without corner overrides.
     */
    fun renderPresetAsRawArgbFrame(
        preset: CapturedTablePhoto = angledNineFootNineBall(),
        clothCategory: ClothHueCategory = ClothHueCategory.TOURNAMENT_BLUE,
        ballSet: BallSetProfile = BallSetProfile.standardPool(),
    ): ArgbImageFrame {
        val groundTruthBalls = preset.groundTruthBallPositionsMeters.entries
            .sortedBy { it.key }
            .map { (number, positionMeters) ->
                Ball(
                    number = number,
                    position = positionMeters,
                    radiusMeters = ballSet.ballRadiusMeters,
                )
            }
        val layout = TableLayout(
            table = preset.tableSpec,
            balls = groundTruthBalls,
        )
        val cameraPoseMeters = Vec3(-0.55, preset.tableSpec.widthMeters * 0.5, 1.55)
        return RawImageTableDetector.renderSyntheticTablePhotoArgb(
            layout = layout,
            quadCornersPx = LANDSCAPE_RAW_QUAD_PX,
            cameraPoseMeters = cameraPoseMeters,
            width = RAW_FRAME_WIDTH_PX,
            height = RAW_FRAME_HEIGHT_PX,
            clothCategory = clothCategory,
            ballSet = ballSet,
            cushions = RawImageTableDetector.SyntheticCushionStyle.SHADED,
        )
    }

    private fun buildPresetPhoto(
        id: String,
        title: String,
        subtitle: String,
        tableSpec: TableSpec,
        cameraPose: Vec3,
        lookAt: Vec3,
        rightHint: Vec3,
        ballSpecs: List<BallSpec>,
        sharpnessScore: Double,
        glareFraction: Double,
    ): CapturedTablePhoto {
        val intrinsics = defaultIntrinsics()
        val quadCornersPx = projectTableCorners(
            tableSpec = tableSpec,
            cameraPose = cameraPose,
            lookAt = lookAt,
            rightHint = rightHint,
            intrinsics = intrinsics,
        )
        val homography = PerspectiveHomography.fromTableQuad(quadCornersPx, tableSpec)
        val candidates = ballSpecs.mapIndexed { index, spec ->
            val idealPx = homography.ballCenterMetersToImage(
                ballCenterMeters = spec.positionMeters,
                ballRadiusMeters = tableSpec.ballRadiusMeters,
                cameraPositionMeters = cameraPose,
            )
            DetectedBallCandidate(
                id = index + 1,
                imageCenterPx = idealPx + spec.jitterPx,
                dominantColor = spec.dominantColor,
                secondaryColor = spec.secondaryColor,
                whiteFraction = spec.whiteFraction,
                detectorConfidence = spec.detectorConfidence,
            )
        }
        val groundTruth = ballSpecs.associate { it.number to it.positionMeters }
        return CapturedTablePhoto(
            id = id,
            title = title,
            subtitle = subtitle,
            tableSpec = tableSpec,
            intrinsics = intrinsics,
            trueCameraPoseMeters = cameraPose,
            detectedQuadCornersPx = quadCornersPx,
            rawDetections = candidates,
            groundTruthBallPositionsMeters = groundTruth,
            sharpnessScore = sharpnessScore,
            glareFraction = glareFraction,
        )
    }

    /**
     * Projects `[headLeft, footLeft, footRight, headRight]` cloth corners through an ideal
     * pinhole camera at [cameraPose] looking at [lookAt] with image-right axis [rightHint].
     */
    private fun projectTableCorners(
        tableSpec: TableSpec,
        cameraPose: Vec3,
        lookAt: Vec3,
        rightHint: Vec3,
        intrinsics: CameraIntrinsicsSpec,
    ): List<Vec2> {
        val forward = normalize3(sub3(lookAt, cameraPose))
        val rightRaw = sub3(rightHint, scale3(forward, dot3(rightHint, forward)))
        val right = normalize3(rightRaw)
        val down = cross3(forward, right)

        fun projectPoint(world: Vec3): Vec2 {
            val delta = sub3(world, cameraPose)
            val xCam = dot3(right, delta)
            val yCam = dot3(down, delta)
            val zCam = dot3(forward, delta)
            return Vec2(
                x = intrinsics.principalPointPx.x + intrinsics.focalLengthPx * (xCam / zCam),
                y = intrinsics.principalPointPx.y + intrinsics.focalLengthPx * (yCam / zCam),
            )
        }

        return listOf(
            projectPoint(Vec3(0.0, 0.0, 0.0)),
            projectPoint(Vec3(tableSpec.lengthMeters, 0.0, 0.0)),
            projectPoint(Vec3(tableSpec.lengthMeters, tableSpec.widthMeters, 0.0)),
            projectPoint(Vec3(0.0, tableSpec.widthMeters, 0.0)),
        )
    }

    private fun sub3(a: Vec3, b: Vec3): Vec3 = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)

    private fun scale3(v: Vec3, s: Double): Vec3 = Vec3(v.x * s, v.y * s, v.z * s)

    private fun dot3(a: Vec3, b: Vec3): Double = a.x * b.x + a.y * b.y + a.z * b.z

    private fun cross3(a: Vec3, b: Vec3): Vec3 = Vec3(
        x = a.y * b.z - a.z * b.y,
        y = a.z * b.x - a.x * b.z,
        z = a.x * b.y - a.y * b.x,
    )

    private fun normalize3(v: Vec3): Vec3 {
        val len = sqrt(dot3(v, v))
        require(len > 1e-12) { "Camera axis vector must be non-zero." }
        return Vec3(v.x / len, v.y / len, v.z / len)
    }
}

/**
 * Platform camera abstraction keeping frame capture and EXIF/GPS stripping strictly in memory.
 */
expect class PlatformCameraController() {
    val platformName: String
    val stripsExifAndGpsInMemory: Boolean
    fun defaultIntrinsics(): CameraIntrinsicsSpec
    fun capturePresetPhoto(presetId: String): CapturedTablePhoto
}
