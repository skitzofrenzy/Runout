package com.runout.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runout.app.camera.CaptureQualityReport
import com.runout.app.camera.CapturedTablePhoto
import com.runout.app.camera.ImportedPhotoFrame
import com.runout.app.camera.PhotoCaptureEvaluator
import com.runout.app.camera.PlatformCameraController
import com.runout.app.camera.SampleCapturedPhotos
import com.runout.app.camera.rememberPlatformPhotoPicker
import com.runout.app.camera.toComposeImageBitmap
import com.runout.engine.math.Vec2
import com.runout.engine.model.AssumedSurfacePreset
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.ClothParams
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.planner.PlanningReadinessResult
import com.runout.engine.planner.PlanningReadinessValidator
import com.runout.engine.vision.ArgbImageFrame
import com.runout.engine.vision.BallIdentificationAndAssignment
import com.runout.engine.vision.CameraIntrinsicsSpec
import com.runout.engine.vision.ClothHueCategory
import com.runout.engine.vision.DetectedBallCandidate
import com.runout.engine.vision.DetectedGameVariant
import com.runout.engine.vision.FrozenBallStatus
import com.runout.engine.vision.GameTypeSuggester
import com.runout.engine.vision.GameTypeSuggestion
import com.runout.engine.vision.IdentifiedBallResult
import com.runout.engine.vision.LocalTapRescanResult
import com.runout.engine.vision.PerspectiveHomography
import com.runout.engine.vision.RawFrameDetectionSummary
import com.runout.engine.vision.RawImageTableDetector
import com.runout.engine.vision.VisionRectificationResult
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val METERS_TO_MM: Double = 1000.0
private const val TARGET_MEDIAN_ERROR_MM: Double = 10.0
private const val MIN_ROLLING_RESISTANCE_MU_R: Double = 0.004
private const val MAX_ROLLING_RESISTANCE_MU_R: Double = 0.025
private const val ROLLING_RESISTANCE_STEP_MU_R: Double = 0.001
private const val MIN_CUSHION_RESTITUTION_EC: Double = 0.50
private const val MAX_CUSHION_RESTITUTION_EC: Double = 0.92
private const val CUSHION_RESTITUTION_STEP_EC: Double = 0.02

private val PHOTO_SURFACE_PRESET_LABELS: Map<AssumedSurfacePreset, String> = mapOf(
    AssumedSurfacePreset.PRO_BROADCAST_FAST to "Pro Broadcast (Fast Roll / Lively Rails)",
    AssumedSurfacePreset.STANDARD_POOL_HALL to "Standard Hall",
    AssumedSurfacePreset.SLOW_BAR_BOX_HEAVY to "Slow Bar-Box (Heavy Roll / Soft Rails)",
    AssumedSurfacePreset.SNOOKER_DIRECTIONAL to "Snooker Directional",
)

private val CORNER_LABELS = listOf("HL", "FL", "FR", "HR")
private val EIGHT_BALL_CONFIRM_GROUPS = listOf(
    EightBallGroup.SOLIDS,
    EightBallGroup.STRIPES,
    EightBallGroup.OPEN,
    EightBallGroup.EIGHT_BALL,
)

private val SuccessGreenColor = Color(0xFF4ADE80)
private val WarningAmberColor = Color(0xFFFBBF24)
private val ErrorRedColor = Color(0xFFF87171)
private val ViewfinderBackdropColor = Color(0xFF070D17)
private val GuideCyanColor = Color(0xFF38BDF8)

private const val TARGET_RAW_PIXEL_MEDIAN_ERROR_MM: Double = 15.0

private enum class PhotoWizardStep(val stepNumber: Int, val headline: String) {
    SOURCE_PICKER(1, "Choose a Table Photo"),
    REVIEW_TABLE(2, "Check Corners & Balls"),
    CONFIRM_GAME(3, "Confirm Game & Group"),
}

private data class RawPixelDecodeState(
    val sourcePreset: CapturedTablePhoto,
    val requestedClothCategory: ClothHueCategory,
    val frame: ArgbImageFrame,
    val summary: RawFrameDetectionSummary,
    val rectification: VisionRectificationResult,
    val medianErrorMm: Double,
)

/**
 * Interactive 3-step photo table capture / import wizard:
 * 1. Take Photo or Import from Gallery (or try a built-in sample)
 * 2. Check & drag the 4 table cushion corners and tap any ball to fix its number
 * 3. Confirm Game Type & 8-Ball Group (guarded by [PlanningReadinessValidator])
 */
@Composable
fun PhotoCaptureAndConfirmScreen(
    ballSet: BallSetProfile,
    lastPlayedGameType: GameType,
    onConfirmScannedLayout: (TableLayout, GameRulesState, BallSetProfile) -> Unit,
    modifier: Modifier = Modifier,
    displayMode: AppDisplayMode = AppDisplayMode.SIMPLE,
    defaultTableSpec: TableSpec = TableSpec.nineFootPool(),
) {
    val cameraController = remember { PlatformCameraController() }
    val presets = remember { SampleCapturedPhotos.allPresets() }
    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    var selectedPhoto by remember {
        mutableStateOf(
            presets.firstOrNull() ?: cameraController.capturePresetPhoto("angled_9ft_9ball"),
        )
    }
    var wizardStep by remember { mutableStateOf(PhotoWizardStep.SOURCE_PICKER) }
    var importedImageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var importedRawFrame by remember { mutableStateOf<ArgbImageFrame?>(null) }
    var pendingImportedFrame by remember { mutableStateOf<ImportedPhotoFrame?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var activeBallSet by remember(ballSet) { mutableStateOf(ballSet) }
    var selectedRawClothCategory by remember { mutableStateOf(ClothHueCategory.TOURNAMENT_BLUE) }
    var rawPixelDecodeState by remember { mutableStateOf<RawPixelDecodeState?>(null) }

    val photoPicker = rememberPlatformPhotoPicker(
        onPhotoImported = { imported ->
            importError = null
            pendingImportedFrame = imported
        },
        onError = { message ->
            importError = message
        },
    )

    LaunchedEffect(pendingImportedFrame, ballSet, defaultTableSpec) {
        val imported = pendingImportedFrame ?: return@LaunchedEffect
        val outcome = runCatching {
            withContext(Dispatchers.Default) {
                val bundle = PhotoCaptureEvaluator.createCapturedPhotoFromRawFrame(
                    id = imported.id,
                    title = imported.title,
                    subtitle = imported.subtitle,
                    frame = imported.frame,
                    tableSpec = defaultTableSpec,
                    baseBallSet = ballSet,
                )
                val previewBitmap = imported.frame.toComposeImageBitmap()
                bundle to previewBitmap
            }
        }
        outcome.fold(
            onSuccess = { (bundle, previewBitmap) ->
                importedImageBitmap = previewBitmap
                importedRawFrame = imported.frame
                selectedPhoto = bundle.photo
                activeBallSet = bundle.inferredBallSet
                importError = null
                pendingImportedFrame = null
                wizardStep = PhotoWizardStep.REVIEW_TABLE
            },
            onFailure = { err ->
                importError = err.message ?: "Unable to analyze the selected table photo."
                pendingImportedFrame = null
            },
        )
    }

    var overriddenQuadCornersPx by remember(selectedPhoto.id) {
        mutableStateOf(selectedPhoto.detectedQuadCornersPx)
    }

    LaunchedEffect(importedRawFrame, overriddenQuadCornersPx, selectedPhoto.id) {
        val frame = importedRawFrame ?: return@LaunchedEffect
        if (overriddenQuadCornersPx.size != 4) return@LaunchedEffect
        if (overriddenQuadCornersPx == selectedPhoto.detectedQuadCornersPx) return@LaunchedEffect
        delay(220)
        val canonicalQuad = canonicalizeQuadTopLeftClockwise(overriddenQuadCornersPx)
        val summary = withContext(Dispatchers.Default) {
            runCatching {
                RawImageTableDetector.analyzeArgbFrame(
                    frame = frame,
                    quadOverridePx = canonicalQuad,
                )
            }.getOrNull()
        } ?: return@LaunchedEffect
        selectedPhoto = selectedPhoto.copy(rawDetections = summary.detectedCandidates)
        activeBallSet = BallSetProfile.inferFromDetectedColors(
            summary.detectedCandidates.map { it.dominantColor },
            ballSet,
        )
    }
    var candidateNumberOverrides by remember(selectedPhoto.id) {
        mutableStateOf<Map<Int, Int>>(emptyMap())
    }
    var activeReassignCandidateId by remember(selectedPhoto.id) {
        mutableStateOf<Int?>(null)
    }
    var activeCornerIndex by remember(selectedPhoto.id) {
        mutableStateOf<Int?>(null)
    }
    var draggedCandidateId by remember(selectedPhoto.id) {
        mutableStateOf<Int?>(null)
    }
    var rescanFeedbackMessage by remember(selectedPhoto.id) {
        mutableStateOf<String?>(null)
    }
    var isRescanningArea by remember(selectedPhoto.id) {
        mutableStateOf(false)
    }
    val coroutineScope = rememberCoroutineScope()
    var selectedVariantOverride by remember(selectedPhoto.id) {
        mutableStateOf<DetectedGameVariant?>(null)
    }
    var confirmedEightBallGroup by remember(selectedPhoto.id) {
        mutableStateOf<EightBallGroup?>(null)
    }
    var selectedClothParams by remember(selectedPhoto.id) {
        mutableStateOf(
            when {
                selectedPhoto.tableSpec.name.contains("Snooker", ignoreCase = true) ->
                    ClothParams.fromPreset(AssumedSurfacePreset.SNOOKER_DIRECTIONAL)
                selectedPhoto.tableSpec.name.contains("7 ft", ignoreCase = true) ->
                    ClothParams.fromPreset(AssumedSurfacePreset.SLOW_BAR_BOX_HEAVY)
                else -> ClothParams.fromPreset(AssumedSurfacePreset.STANDARD_POOL_HALL)
            },
        )
    }

    val qualityReport = remember(selectedPhoto, overriddenQuadCornersPx) {
        PhotoCaptureEvaluator.evaluateQuality(
            sharpnessScore = selectedPhoto.sharpnessScore,
            glareFraction = selectedPhoto.glareFraction,
            quadCornersPx = overriddenQuadCornersPx,
            intrinsics = selectedPhoto.intrinsics,
        )
    }

    val rectificationOutcome = remember(selectedPhoto, overriddenQuadCornersPx, activeBallSet) {
        runCatching {
            PhotoCaptureEvaluator.processCapturedPhoto(
                photo = selectedPhoto,
                overriddenQuadCornersPx = overriddenQuadCornersPx,
                ballSet = activeBallSet,
            )
        }
    }

    var fallbackRectification by remember(selectedPhoto.id) {
        mutableStateOf<VisionRectificationResult?>(null)
    }
    LaunchedEffect(rectificationOutcome) {
        rectificationOutcome.getOrNull()?.let { valid ->
            fallbackRectification = valid
        }
    }

    val activeRectification = rectificationOutcome.getOrNull() ?: fallbackRectification
    val isQuadGeometryInvalid = rectificationOutcome.isFailure

    val displayedBallsWithIdentity = remember(activeRectification, candidateNumberOverrides) {
        activeRectification?.identifiedBalls.orEmpty().map { item ->
            val effectiveNumber = candidateNumberOverrides[item.candidateId] ?: item.ball.number
            item to effectiveNumber
        }
    }

    fun handleMoveBallCandidate(candidateId: Int, newImageCenterPx: Vec2) {
        val clamped = Vec2(
            x = newImageCenterPx.x.coerceIn(0.0, selectedPhoto.intrinsics.imageWidthPx.toDouble()),
            y = newImageCenterPx.y.coerceIn(0.0, selectedPhoto.intrinsics.imageHeightPx.toDouble()),
        )
        selectedPhoto = selectedPhoto.copy(
            rawDetections = selectedPhoto.rawDetections.map { cand ->
                if (cand.id == candidateId) cand.copy(imageCenterPx = clamped) else cand
            },
        )
    }

    fun handleDeleteBallCandidate(candidateId: Int) {
        val remainingPinned = displayedBallsWithIdentity
            .filter { (item, _) -> item.candidateId != candidateId }
            .associate { (item, num) -> item.candidateId to num }
        selectedPhoto = selectedPhoto.copy(
            rawDetections = selectedPhoto.rawDetections.filterNot { it.id == candidateId },
        )
        candidateNumberOverrides = remainingPinned
        if (activeReassignCandidateId == candidateId) activeReassignCandidateId = null
        if (draggedCandidateId == candidateId) draggedCandidateId = null
    }

    fun handleReassignBallCandidateNumber(candidateId: Int, newNumber: Int) {
        val baseIdentified = activeRectification?.identifiedBalls ?: return
        candidateNumberOverrides = reassignWithUniqueSwap(
            identifiedBalls = baseIdentified,
            currentOverrides = candidateNumberOverrides,
            targetCandidateId = candidateId,
            newNumber = newNumber,
        )
        activeReassignCandidateId = null
    }

    fun handleTapEmptyPhotoArea(tapImagePx: Vec2) {
        if (isRescanningArea) return
        val pinnedMap = displayedBallsWithIdentity.associate { (item, num) ->
            item.candidateId to num
        }
        val usedNumbers = pinnedMap.values.toMutableSet()
        val existing = selectedPhoto.rawDetections
        val frame = importedRawFrame
        val canonicalQuad = canonicalizeQuadTopLeftClockwise(overriddenQuadCornersPx)
        val snapshotPhotoId = selectedPhoto.id

        isRescanningArea = true
        coroutineScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    if (frame != null) {
                        RawImageTableDetector.rescanLocalRegion(
                            frame = frame,
                            tapCenterPx = tapImagePx,
                            quadCornersPx = canonicalQuad,
                            existingCandidates = existing,
                        )
                    } else {
                        val nextId = (existing.maxOfOrNull { it.id } ?: 0) + 1
                        LocalTapRescanResult(
                            detectedCandidates = listOf(
                                DetectedBallCandidate(
                                    id = nextId,
                                    imageCenterPx = tapImagePx,
                                    dominantColor = BallPaletteColor.WHITE,
                                    whiteFraction = 0.85,
                                    detectorConfidence = 0.70,
                                ),
                            ),
                            usedColorFallback = true,
                        )
                    }
                }
                if (selectedPhoto.id != snapshotPhotoId) return@launch
                val newAssigned = LinkedHashMap<Int, Int>()
                for (cand in result.detectedCandidates) {
                    val suggested = BallIdentificationAndAssignment.suggestBallNumberForCandidate(
                        candidate = cand,
                        ballSet = activeBallSet,
                        excludedNumbers = usedNumbers,
                    )
                    newAssigned[cand.id] = suggested
                    usedNumbers += suggested
                }
                selectedPhoto = selectedPhoto.copy(
                    rawDetections = existing + result.detectedCandidates,
                )
                candidateNumberOverrides = pinnedMap + newAssigned
                val firstNew = result.detectedCandidates.firstOrNull()
                activeReassignCandidateId = firstNew?.id
                if (firstNew != null) {
                    val firstNum = newAssigned[firstNew.id] ?: 0
                    val ballTitle = if (firstNum == 0) "Cue Ball (0)" else "Ball #$firstNum"
                    rescanFeedbackMessage = if (!result.usedColorFallback) {
                        val count = result.detectedCandidates.size
                        val summary = if (count == 1) ballTitle else "$count balls (first: $ballTitle)"
                        "Smart rescan detected $summary — drag to align or tap a number below."
                    } else {
                        val colorWord = firstNew.dominantColor.name.lowercase()
                        "Added $ballTitle from detected $colorWord area — drag to center or tap a number."
                    }
                }
            } finally {
                isRescanningArea = false
            }
        }
    }

    val finalLayout = remember(activeRectification, displayedBallsWithIdentity) {
        val base = activeRectification
        if (base == null) {
            TableLayout(table = selectedPhoto.tableSpec, balls = emptyList())
        } else {
            val updatedBalls = displayedBallsWithIdentity.map { (item, effectiveNum) ->
                if (effectiveNum == item.ball.number) {
                    item.ball
                } else {
                    item.ball.copy(number = effectiveNum)
                }
            }
            base.layout.copy(balls = updatedBalls)
        }
    }

    val effectiveFrozenBalls = remember(finalLayout) {
        BallIdentificationAndAssignment.detectFrozenBalls(finalLayout)
    }

    val medianErrorMm = remember(activeRectification, finalLayout, effectiveFrozenBalls, selectedPhoto) {
        val base = activeRectification ?: return@remember 0.0
        val overriddenResult = base.copy(
            layout = finalLayout,
            frozenBalls = effectiveFrozenBalls,
        )
        PhotoCaptureEvaluator.computeMedianPositionErrorMeters(
            rectified = overriddenResult,
            groundTruth = selectedPhoto.groundTruthBallPositionsMeters,
        ) * METERS_TO_MM
    }

    val lastPlayedVariant = remember(lastPlayedGameType) {
        lastPlayedGameType.toDetectedVariant()
    }

    val gameSuggestions = remember(finalLayout, lastPlayedVariant) {
        GameTypeSuggester.suggestGameTypes(
            layout = finalLayout,
            lastPlayedVariant = lastPlayedVariant,
        )
    }

    val topSuggestion = gameSuggestions.firstOrNull()
    val activeVariant = selectedVariantOverride ?: topSuggestion?.variant ?: DetectedGameVariant.EIGHT_BALL
    val activeSuggestion = gameSuggestions.firstOrNull { it.variant == activeVariant } ?: topSuggestion
    // Never silently assume Solids, Stripes, or Open Table after detecting 8-Ball; require explicit user selection.
    val activeEightBallGroup = confirmedEightBallGroup ?: EightBallGroup.UNSET
    val candidateGameType = activeSuggestion?.engineGameType ?: lastPlayedGameType
    val candidateRulesState = GameRulesState(
        gameType = candidateGameType,
        eightBallGroup = if (
            candidateGameType == GameType.EIGHT_BALL &&
            activeVariant == DetectedGameVariant.EIGHT_BALL
        ) {
            activeEightBallGroup
        } else {
            EightBallGroup.OPEN
        },
    )
    val planningReadiness = remember(finalLayout, candidateRulesState, activeBallSet) {
        PlanningReadinessValidator.evaluate(
            layout = finalLayout,
            rulesState = candidateRulesState,
            ballSet = activeBallSet,
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Card),
        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Section),
    ) {
        // Step Progress Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Step ${wizardStep.stepNumber} of 3 • ${wizardStep.headline}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (wizardStep == PhotoWizardStep.SOURCE_PICKER) {
                        "Processed strictly in memory — location tags stripped"
                    } else {
                        selectedPhoto.title
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (wizardStep != PhotoWizardStep.SOURCE_PICKER) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (qualityReport.isAcceptable) {
                        SuccessGreenColor.copy(alpha = 0.14f)
                    } else {
                        WarningAmberColor.copy(alpha = 0.16f)
                    },
                ) {
                    Text(
                        text = if (qualityReport.isAcceptable) "Quality: Good" else "Check Angles",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (qualityReport.isAcceptable) SuccessGreenColor else WarningAmberColor,
                    )
                }
            }
        }

        when (wizardStep) {
            PhotoWizardStep.SOURCE_PICKER -> {
                Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card)) {
                    Button(
                        onClick = {
                            importError = null
                            photoPicker.launchCamera()
                        },
                        enabled = pendingImportedFrame == null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RunoutVectorIcon(
                                icon = RunoutIcon.CAMERA,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = "Take Table Photo",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            importError = null
                            photoPicker.launchGallery()
                        },
                        enabled = pendingImportedFrame == null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RunoutVectorIcon(
                                icon = RunoutIcon.GALLERY,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = "Import From Phone Gallery",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (pendingImportedFrame != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = RunoutColors.SurfaceMid),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(RunoutSpacing.Margin),
                                horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                                Text(
                                    text = "Analyzing table photo…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    if (importError != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = ErrorRedColor.copy(alpha = 0.14f),
                            border = BorderStroke(1.dp, ErrorRedColor.copy(alpha = 0.50f)),
                        ) {
                            Text(
                                text = importError.orEmpty(),
                                modifier = Modifier.padding(RunoutSpacing.Margin),
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorRedColor,
                            )
                        }
                    }
                }

                // Quick Framing Tips
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(RunoutSpacing.Margin),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Quick Photo Tips",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "• Stand slightly behind one end so all 4 rail corners show.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "• Hold steady and avoid bright overhead glare on the felt.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Collapsed Built-In Samples & Raw Pixel Tester
                CollapsibleSectionCard(
                    title = "No table nearby? Try a built-in sample photo",
                    summary = "Test detection with 4 sample pool layouts",
                    initiallyExpanded = false,
                ) {
                    PresetCaptureBar(
                        presets = presets,
                        selectedPresetIndex = selectedPresetIndex,
                        selectedPhoto = selectedPhoto,
                        onSelectPreset = { index ->
                            val chosen = presets[index]
                            selectedPresetIndex = index
                            selectedPhoto = cameraController.capturePresetPhoto(chosen.id)
                            activeBallSet = ballSet
                            importedImageBitmap = null
                            importedRawFrame = null
                            wizardStep = PhotoWizardStep.REVIEW_TABLE
                        },
                    )

                    if (displayMode == AppDisplayMode.PRO) {
                        val rawSourcePreset = presets.getOrElse(selectedPresetIndex) { selectedPhoto }
                        RawPixelFrameDecoderCard(
                            sourcePreset = rawSourcePreset,
                            selectedClothCategory = selectedRawClothCategory,
                            decodeState = rawPixelDecodeState,
                            isLoadedInPreview = selectedPhoto.id.startsWith("raw_pixel_"),
                            ballSet = activeBallSet,
                            onSelectClothCategory = { category ->
                                selectedRawClothCategory = category
                            },
                            onRunPixelDetector = {
                                val frame = SampleCapturedPhotos.renderPresetAsRawArgbFrame(
                                    preset = rawSourcePreset,
                                    clothCategory = selectedRawClothCategory,
                                    ballSet = activeBallSet,
                                )
                                val (summary, rectified) = PhotoCaptureEvaluator.processRawArgbFrame(
                                    frame = frame,
                                    tableSpec = rawSourcePreset.tableSpec,
                                    ballSet = activeBallSet,
                                )
                                val pixelMedianErrorMm = PhotoCaptureEvaluator.computeMedianPositionErrorMeters(
                                    rectified = rectified,
                                    groundTruth = rawSourcePreset.groundTruthBallPositionsMeters,
                                ) * METERS_TO_MM
                                rawPixelDecodeState = RawPixelDecodeState(
                                    sourcePreset = rawSourcePreset,
                                    requestedClothCategory = selectedRawClothCategory,
                                    frame = frame,
                                    summary = summary,
                                    rectification = rectified,
                                    medianErrorMm = pixelMedianErrorMm,
                                )
                            },
                            onUsePixelDetectedLayout = { decoded ->
                                val widthRatio = decoded.summary.width.toDouble() /
                                    decoded.sourcePreset.intrinsics.imageWidthPx.toDouble().coerceAtLeast(1.0)
                                selectedPhoto = CapturedTablePhoto(
                                    id = "raw_pixel_${decoded.sourcePreset.id}_${decoded.requestedClothCategory.name.lowercase()}",
                                    title = "${decoded.sourcePreset.title} (640×360 Pixel Decode)",
                                    subtitle = "Decoded from ARGB pixels • ${decoded.summary.clothSurface.category.displayLabel()}",
                                    tableSpec = decoded.sourcePreset.tableSpec,
                                    intrinsics = CameraIntrinsicsSpec(
                                        focalLengthPx = decoded.sourcePreset.intrinsics.focalLengthPx * widthRatio,
                                        principalPointPx = Vec2(decoded.summary.width * 0.5, decoded.summary.height * 0.5),
                                        imageWidthPx = decoded.summary.width,
                                        imageHeightPx = decoded.summary.height,
                                    ),
                                    trueCameraPoseMeters = decoded.rectification.cameraPositionMeters,
                                    detectedQuadCornersPx = decoded.summary.detectedQuadCornersPx,
                                    rawDetections = decoded.summary.detectedCandidates,
                                    groundTruthBallPositionsMeters = decoded.sourcePreset.groundTruthBallPositionsMeters,
                                    sharpnessScore = decoded.summary.sharpnessScore,
                                    glareFraction = decoded.summary.glareFraction,
                                )
                                importedImageBitmap = decoded.frame.toComposeImageBitmap()
                                importedRawFrame = decoded.frame
                                wizardStep = PhotoWizardStep.REVIEW_TABLE
                            },
                        )
                    }
                }
            }

            PhotoWizardStep.REVIEW_TABLE -> {
                InteractiveFramingGuideCard(
                    photo = selectedPhoto,
                    sourceImageBitmap = importedImageBitmap,
                    quadCornersPx = overriddenQuadCornersPx,
                    activeCornerIndex = activeCornerIndex,
                    activeCandidateId = activeReassignCandidateId,
                    draggedCandidateId = draggedCandidateId,
                    displayedBalls = displayedBallsWithIdentity,
                    ballSet = activeBallSet,
                    qualityReport = qualityReport,
                    isQuadGeometryInvalid = isQuadGeometryInvalid,
                    rescanFeedbackMessage = rescanFeedbackMessage,
                    isRescanningArea = isRescanningArea,
                    onCornersChanged = { updated -> overriddenQuadCornersPx = updated },
                    onActiveCornerChanged = { idx -> activeCornerIndex = idx },
                    onMoveBallCandidate = ::handleMoveBallCandidate,
                    onDraggedCandidateChanged = { id -> draggedCandidateId = id },
                    onSelectBallCandidate = { id ->
                        activeReassignCandidateId = if (activeReassignCandidateId == id) null else id
                    },
                    onTapEmptyPhotoArea = ::handleTapEmptyPhotoArea,
                    onReassignBallNumber = ::handleReassignBallCandidateNumber,
                    onDeleteBallCandidate = ::handleDeleteBallCandidate,
                    onDismissFeedback = { rescanFeedbackMessage = null },
                    onResetCorners = {
                        overriddenQuadCornersPx = selectedPhoto.detectedQuadCornersPx
                    },
                )

                if (activeRectification != null) {
                    RectificationAndIdentityCard(
                        rectification = activeRectification,
                        finalLayout = finalLayout,
                        medianErrorMm = medianErrorMm,
                        frozenBalls = effectiveFrozenBalls,
                        displayedBalls = displayedBallsWithIdentity,
                        candidateNumberOverrides = candidateNumberOverrides,
                        activeReassignCandidateId = activeReassignCandidateId,
                        ballSet = activeBallSet,
                        displayMode = displayMode,
                        onToggleReassignCandidate = { candidateId ->
                            activeReassignCandidateId = if (activeReassignCandidateId == candidateId) {
                                null
                            } else {
                                candidateId
                            }
                        },
                        onReassignBallNumber = ::handleReassignBallCandidateNumber,
                        onDeleteBallCandidate = ::handleDeleteBallCandidate,
                        onResetAllIdentities = {
                            candidateNumberOverrides = emptyMap()
                            activeReassignCandidateId = null
                        },
                    )
                }

                CollapsibleSectionCard(
                    title = "Table Speed & Rail Bounce",
                    summary = PHOTO_SURFACE_PRESET_LABELS[matchingAssumedSurfacePreset(selectedClothParams)] ?: "Custom Surface",
                    initiallyExpanded = false,
                ) {
                    AssumedSurfaceControlCard(
                        title = "Assumed Cloth Roll & Rail Density",
                        subtitle = "Adjust cloth roll or cushion bounce if your photo comes from an unfamiliar table.",
                        clothParams = selectedClothParams,
                        presetLabels = PHOTO_SURFACE_PRESET_LABELS,
                        rollStepperTitle = "Cloth Roll (μr)",
                        railStepperTitle = "Rail Bounce (ec)",
                        onUpdateClothParams = { updated -> selectedClothParams = updated },
                    )
                }

                if (displayMode == AppDisplayMode.PRO || !qualityReport.isAcceptable) {
                    CollapsibleSectionCard(
                        title = "Capture Quality & Telemetry",
                        summary = if (qualityReport.isAcceptable) {
                            "Sharpness ${(qualityReport.sharpnessScore * 100.0).roundToInt()}% • Glare ${formatDecimal(qualityReport.glareFraction * 100.0, 1)}%"
                        } else {
                            qualityReport.warnings.firstOrNull() ?: "Check framing"
                        },
                        initiallyExpanded = !qualityReport.isAcceptable,
                    ) {
                        CaptureQualityCard(
                            qualityReport = qualityReport,
                            photo = selectedPhoto,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
                ) {
                    OutlinedButton(
                        onClick = { wizardStep = PhotoWizardStep.SOURCE_PICKER },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Text("Back", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { wizardStep = PhotoWizardStep.CONFIRM_GAME },
                        enabled = activeRectification != null && !isQuadGeometryInvalid,
                        modifier = Modifier
                            .weight(2f)
                            .height(52.dp),
                    ) {
                        Text("Next: Confirm Game", fontWeight = FontWeight.Bold)
                    }
                }
            }

            PhotoWizardStep.CONFIRM_GAME -> {
                GameTypeConfirmationCard(
                    suggestions = gameSuggestions,
                    selectedVariant = activeVariant,
                    selectedEightBallGroup = activeEightBallGroup,
                    ballSet = activeBallSet,
                    planningReadiness = planningReadiness,
                    onSelectVariant = { variant ->
                        selectedVariantOverride = variant
                    },
                    onSelectEightBallGroup = { group ->
                        confirmedEightBallGroup = group
                    },
                    canConfirm = activeRectification != null && !isQuadGeometryInvalid &&
                        (planningReadiness.isReady || planningReadiness.missingBallNumbers.isNotEmpty()),
                    onConfirm = {
                        onConfirmScannedLayout(
                            finalLayout.copy(table = finalLayout.table.withClothParams(selectedClothParams)),
                            candidateRulesState,
                            activeBallSet,
                        )
                    },
                )

                OutlinedButton(
                    onClick = { wizardStep = PhotoWizardStep.REVIEW_TABLE },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Back to Corners & Balls", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PresetCaptureBar(
    presets: List<CapturedTablePhoto>,
    selectedPresetIndex: Int,
    selectedPhoto: CapturedTablePhoto,
    onSelectPreset: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Simulated Camera Captures",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = selectedPhoto.tableSpec.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEachIndexed { index, preset ->
                    val isSelected = index == selectedPresetIndex
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPreset(index) },
                        label = {
                            Text(
                                text = preset.title,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }

            Text(
                text = selectedPhoto.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RawPixelFrameDecoderCard(
    sourcePreset: CapturedTablePhoto,
    selectedClothCategory: ClothHueCategory,
    decodeState: RawPixelDecodeState?,
    isLoadedInPreview: Boolean,
    ballSet: BallSetProfile,
    onSelectClothCategory: (ClothHueCategory) -> Unit,
    onRunPixelDetector: () -> Unit,
    onUsePixelDetectedLayout: (RawPixelDecodeState) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isLoadedInPreview) {
                SuccessGreenColor.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Online Photo / Raw Pixel Frame Decoder (RawImageTableDetector)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isLoadedInPreview) {
                        SuccessGreenColor.copy(alpha = 0.16f)
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    },
                ) {
                    Text(
                        text = if (isLoadedInPreview) "Loaded in Preview" else "640×360 ARGB",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isLoadedInPreview) SuccessGreenColor else MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = "Render ${sourcePreset.title} as a raw 640×360 ARGB pixel frame, auto-segment the cloth in HSV, extract the 4 cushion corners, and classify ball blobs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Cloth Colour",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ClothHueCategory.entries.forEach { category ->
                        val isSelected = category == selectedClothCategory
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectClothCategory(category) },
                            label = {
                                Text(
                                    text = category.displayLabel(),
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onRunPixelDetector,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Run Pixel Detector on Raw 640×360 ARGB Frame",
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (decodeState != null) {
                val summary = decodeState.summary
                val cloth = summary.clothSurface
                val identifiedById = remember(decodeState.rectification) {
                    decodeState.rectification.identifiedBalls.associateBy { it.candidateId }
                }

                RawArgbFramePreviewCanvas(
                    frame = decodeState.frame,
                    summary = summary,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QualityMetricTile(
                        label = "Detected Cloth",
                        value = cloth.category.displayLabel(),
                        target = "Hue ${cloth.dominantHueDegrees.roundToInt()}°, Coverage ${(cloth.clothCoverageFraction * 100.0).roundToInt()}%",
                        isGood = cloth.category == decodeState.requestedClothCategory,
                        modifier = Modifier.weight(1f),
                    )
                    QualityMetricTile(
                        label = "End-to-End Pixel -> SI Median Error",
                        value = "${formatDecimal(decodeState.medianErrorMm, 1)} mm",
                        target = "Target < ${formatDecimal(TARGET_RAW_PIXEL_MEDIAN_ERROR_MM, 1)} mm",
                        isGood = decodeState.medianErrorMm < TARGET_RAW_PIXEL_MEDIAN_ERROR_MM,
                        modifier = Modifier.weight(1f),
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Auto-Fitted Quad Corners",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val cornersText = summary.detectedQuadCornersPx.mapIndexed { idx, corner ->
                            val label = CORNER_LABELS.getOrElse(idx) { "C$idx" }
                            "$label(${formatDecimal(corner.x, 1)}, ${formatDecimal(corner.y, 1)})"
                        }.joinToString("  •  ")
                        Text(
                            text = cornersText,
                            style = MaterialTheme.typography.bodySmall,
                            color = GuideCyanColor,
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Pixel Blobs Classified: ${summary.detectedCandidates.size} balls",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        summary.detectedCandidates.forEach { candidate ->
                            val matched = identifiedById[candidate.id]
                            val matchedNumber = matched?.ball?.number
                            val blobTypeLabel = blobClassificationLabel(candidate)
                            val whitePct = (candidate.whiteFraction * 100.0).roundToInt()
                            val secondaryVote = candidate.secondaryColor
                            val hsvVoteText = if (secondaryVote != null) {
                                "${candidate.dominantColor.displayLabel()} / ${secondaryVote.displayLabel()}"
                            } else {
                                candidate.dominantColor.displayLabel()
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    if (matchedNumber != null) {
                                        MiniBallSwatch(
                                            ballNumber = matchedNumber,
                                            ballSet = ballSet,
                                        )
                                    }
                                    Column {
                                        val titleText = when (matchedNumber) {
                                            null -> "Blob #${candidate.id}"
                                            0 -> "Cue Ball (0)"
                                            else -> "Ball #$matchedNumber"
                                        }
                                        Text(
                                            text = "$titleText • $blobTypeLabel",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "White $whitePct% • HSV Vote: $hsvVoteText",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    text = "(${candidate.imageCenterPx.x.roundToInt()}, ${candidate.imageCenterPx.y.roundToInt()}) px",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = { onUsePixelDetectedLayout(decodeState) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Use Pixel-Detected Layout",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun RawArgbFramePreviewCanvas(
    frame: ArgbImageFrame,
    summary: RawFrameDetectionSummary,
    modifier: Modifier = Modifier,
) {
    val aspect = frame.width.toFloat() / frame.height.toFloat().coerceAtLeast(1f)
    Canvas(
        modifier = modifier
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
    ) {
        val sampleCols = 80
        val sampleRows = 45
        val cellW = size.width / sampleCols.toFloat()
        val cellH = size.height / sampleRows.toFloat()
        val stepX = frame.width.toDouble() / sampleCols.toDouble()
        val stepY = frame.height.toDouble() / sampleRows.toDouble()

        for (row in 0 until sampleRows) {
            val py = ((row + 0.5) * stepY).toInt().coerceIn(0, frame.height - 1)
            val top = row * cellH
            for (col in 0 until sampleCols) {
                val px = ((col + 0.5) * stepX).toInt().coerceIn(0, frame.width - 1)
                val argb = frame.pixelAt(px, py)
                val pixelColor = Color(
                    red = (argb ushr 16) and 0xFF,
                    green = (argb ushr 8) and 0xFF,
                    blue = argb and 0xFF,
                )
                drawRect(
                    color = pixelColor,
                    topLeft = Offset(col * cellW, top),
                    size = Size(cellW + 0.6f, cellH + 0.6f),
                )
            }
        }

        val scaleX = size.width / frame.width.toFloat().coerceAtLeast(1f)
        val scaleY = size.height / frame.height.toFloat().coerceAtLeast(1f)
        if (summary.detectedQuadCornersPx.size == 4) {
            val pts = summary.detectedQuadCornersPx.map { corner ->
                Offset((corner.x * scaleX).toFloat(), (corner.y * scaleY).toFloat())
            }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                lineTo(pts[1].x, pts[1].y)
                lineTo(pts[2].x, pts[2].y)
                lineTo(pts[3].x, pts[3].y)
                close()
            }
            drawPath(
                path = path,
                color = GuideCyanColor,
                style = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            pts.forEach { cornerPt ->
                drawCircle(color = GuideCyanColor, radius = 4.5f, center = cornerPt)
            }
        }

        summary.detectedCandidates.forEach { candidate ->
            val center = Offset(
                x = (candidate.imageCenterPx.x * scaleX).toFloat(),
                y = (candidate.imageCenterPx.y * scaleY).toFloat(),
            )
            drawCircle(
                color = SuccessGreenColor,
                radius = max(7f, 12f * scaleX),
                center = center,
                style = Stroke(width = 1.8f),
            )
        }
    }
}

private fun ClothHueCategory.displayLabel(): String = when (this) {
    ClothHueCategory.TOURNAMENT_BLUE -> "Tournament Blue"
    ClothHueCategory.SIMONIS_GREEN -> "Simonis Green"
    ClothHueCategory.CHAMPIONSHIP_RED -> "Championship Red"
    ClothHueCategory.SLATE_GREY -> "Slate Grey"
}

private fun BallPaletteColor.displayLabel(): String = when (this) {
    BallPaletteColor.WHITE -> "White"
    BallPaletteColor.YELLOW -> "Yellow"
    BallPaletteColor.BLUE -> "Blue"
    BallPaletteColor.RED -> "Red"
    BallPaletteColor.PURPLE -> "Purple"
    BallPaletteColor.ORANGE -> "Orange"
    BallPaletteColor.GREEN -> "Green"
    BallPaletteColor.MAROON -> "Maroon"
    BallPaletteColor.BLACK -> "Black"
    BallPaletteColor.PINK -> "Pink"
    BallPaletteColor.BROWN -> "Brown"
}

private fun blobClassificationLabel(candidate: DetectedBallCandidate): String = when {
    candidate.dominantColor == BallPaletteColor.WHITE || candidate.whiteFraction >= 0.78 -> "Cue Ball"
    candidate.dominantColor == BallPaletteColor.BLACK -> "8-Ball"
    candidate.whiteFraction >= 0.32 -> "Stripe"
    else -> "Solid"
}

@Composable
private fun CaptureQualityCard(
    qualityReport: CaptureQualityReport,
    photo: CapturedTablePhoto,
) {
    val statusColor = if (qualityReport.isAcceptable) SuccessGreenColor else ErrorRedColor
    val statusLabel = if (qualityReport.isAcceptable) "Capture OK" else "Retake Advised"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (qualityReport.isAcceptable) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                ErrorRedColor.copy(alpha = 0.65f)
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Capture Quality Check",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.16f),
                ) {
                    Text(
                        text = statusLabel,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QualityMetricTile(
                    label = "Sharpness",
                    value = "${(qualityReport.sharpnessScore * 100.0).roundToInt()}%",
                    target = "Min ${(PhotoCaptureEvaluator.MIN_SHARPNESS_SCORE * 100.0).roundToInt()}%",
                    isGood = qualityReport.sharpnessScore >= PhotoCaptureEvaluator.MIN_SHARPNESS_SCORE,
                    modifier = Modifier.weight(1f),
                )
                QualityMetricTile(
                    label = "Glare Area",
                    value = "${formatDecimal(qualityReport.glareFraction * 100.0, 1)}%",
                    target = "Max ${formatDecimal(PhotoCaptureEvaluator.MAX_GLARE_FRACTION * 100.0, 1)}%",
                    isGood = qualityReport.glareFraction <= PhotoCaptureEvaluator.MAX_GLARE_FRACTION,
                    modifier = Modifier.weight(1f),
                )
                QualityMetricTile(
                    label = "Table Corners",
                    value = if (qualityReport.allFourCornersVisible) "4 / 4 Visible" else "Clipped",
                    target = "${photo.intrinsics.imageWidthPx}×${photo.intrinsics.imageHeightPx}",
                    isGood = qualityReport.allFourCornersVisible,
                    modifier = Modifier.weight(1f),
                )
            }

            if (qualityReport.warnings.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = ErrorRedColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, ErrorRedColor.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        qualityReport.warnings.forEach { warning ->
                            Text(
                                text = "• $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = ErrorRedColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityMetricTile(
    label: String,
    value: String,
    target: String,
    isGood: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = if (isGood) SuccessGreenColor else WarningAmberColor
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(
                text = target,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InteractiveFramingGuideCard(
    photo: CapturedTablePhoto,
    quadCornersPx: List<Vec2>,
    activeCornerIndex: Int?,
    activeCandidateId: Int?,
    draggedCandidateId: Int?,
    displayedBalls: List<Pair<IdentifiedBallResult, Int>>,
    ballSet: BallSetProfile,
    qualityReport: CaptureQualityReport,
    isQuadGeometryInvalid: Boolean,
    rescanFeedbackMessage: String?,
    isRescanningArea: Boolean,
    onCornersChanged: (List<Vec2>) -> Unit,
    onActiveCornerChanged: (Int?) -> Unit,
    onMoveBallCandidate: (candidateId: Int, newImageCenterPx: Vec2) -> Unit,
    onDraggedCandidateChanged: (Int?) -> Unit,
    onSelectBallCandidate: (Int?) -> Unit,
    onTapEmptyPhotoArea: (Vec2) -> Unit,
    onReassignBallNumber: (candidateId: Int, newNumber: Int) -> Unit,
    onDeleteBallCandidate: (candidateId: Int) -> Unit,
    onDismissFeedback: () -> Unit,
    onResetCorners: () -> Unit,
    sourceImageBitmap: ImageBitmap? = null,
) {
    val hasCustomCorners = quadCornersPx != photo.detectedQuadCornersPx
    val frameWidthPx = photo.intrinsics.imageWidthPx.toFloat().coerceAtLeast(1f)
    val frameHeightPx = photo.intrinsics.imageHeightPx.toFloat().coerceAtLeast(1f)
    val frameAspectRatio = frameWidthPx / frameHeightPx

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val latestCorners by rememberUpdatedState(quadCornersPx)
    val latestCandidates by rememberUpdatedState(photo.rawDetections)
    val latestFrameW by rememberUpdatedState(frameWidthPx)
    val latestFrameH by rememberUpdatedState(frameHeightPx)
    val latestOnCornersChanged by rememberUpdatedState(onCornersChanged)
    val latestOnActiveCornerChanged by rememberUpdatedState(onActiveCornerChanged)
    val latestOnMoveBallCandidate by rememberUpdatedState(onMoveBallCandidate)
    val latestOnDraggedCandidateChanged by rememberUpdatedState(onDraggedCandidateChanged)
    val latestOnSelectBallCandidate by rememberUpdatedState(onSelectBallCandidate)
    val latestOnTapEmptyPhotoArea by rememberUpdatedState(onTapEmptyPhotoArea)

    val cornerHomography = remember(quadCornersPx, photo.tableSpec) {
        runCatching {
            PerspectiveHomography.fromTableQuad(quadCornersPx, photo.tableSpec)
        }.getOrNull()
    }

    val candidateLookup = remember(displayedBalls) {
        displayedBalls.associate { (result, effectiveNum) ->
            result.candidateId to (result to effectiveNum)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Framing Guide & 4-Corner Homography",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Drag corners or balls (magnifying loupe) • Tap empty area to rescan/add ball",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = onResetCorners,
                    enabled = hasCustomCorners,
                ) {
                    Text("Reset Corners")
                }
            }

            val tapModifier = Modifier.pointerInput(photo.id) {
                detectTapGestures { tapOffset ->
                    val canvasW = size.width.toFloat().coerceAtLeast(1f)
                    val canvasH = size.height.toFloat().coerceAtLeast(1f)
                    val scaleX = canvasW / latestFrameW
                    val scaleY = canvasH / latestFrameH

                    val hitCorner = latestCorners.any { c ->
                        hypot(tapOffset.x - c.x.toFloat() * scaleX, tapOffset.y - c.y.toFloat() * scaleY) <= 34f
                    }
                    if (hitCorner) return@detectTapGestures

                    val detRadiusPx = max(8f, minOf(canvasW, canvasH) * 0.030f)
                    val ballHitRadius = max(28f, detRadiusPx * 1.9f)
                    val hitBall = latestCandidates
                        .map { cand ->
                            val bx = cand.imageCenterPx.x.toFloat() * scaleX
                            val by = cand.imageCenterPx.y.toFloat() * scaleY
                            cand to hypot(tapOffset.x - bx, tapOffset.y - by)
                        }
                        .filter { (_, dist) -> dist <= ballHitRadius }
                        .minByOrNull { (_, dist) -> dist }
                        ?.first

                    if (hitBall != null) {
                        latestOnSelectBallCandidate(hitBall.id)
                    } else {
                        val tapImg = Vec2(
                            x = (tapOffset.x.toDouble() / scaleX.toDouble()).coerceIn(0.0, latestFrameW.toDouble()),
                            y = (tapOffset.y.toDouble() / scaleY.toDouble()).coerceIn(0.0, latestFrameH.toDouble()),
                        )
                        latestOnTapEmptyPhotoArea(tapImg)
                    }
                }
            }

            val dragModifier = Modifier.pointerInput(photo.id) {
                var draggedCorner: Int? = null
                var draggedBallId: Int? = null
                detectDragGestures(
                    onDragStart = { startOffset ->
                        val canvasW = size.width.toFloat().coerceAtLeast(1f)
                        val canvasH = size.height.toFloat().coerceAtLeast(1f)
                        val scaleX = canvasW / latestFrameW
                        val scaleY = canvasH / latestFrameH
                        val hitCornerIdx = latestCorners.indices
                            .map { idx ->
                                val c = latestCorners[idx]
                                val cx = c.x.toFloat() * scaleX
                                val cy = c.y.toFloat() * scaleY
                                idx to hypot(startOffset.x - cx, startOffset.y - cy)
                            }
                            .filter { (_, dist) -> dist <= 40f }
                            .minByOrNull { (_, dist) -> dist }
                            ?.first

                        if (hitCornerIdx != null) {
                            draggedCorner = hitCornerIdx
                            draggedBallId = null
                            latestOnActiveCornerChanged(hitCornerIdx)
                            latestOnDraggedCandidateChanged(null)
                        } else {
                            val detRadiusPx = max(8f, minOf(canvasW, canvasH) * 0.030f)
                            val ballHitRadius = max(32f, detRadiusPx * 2.1f)
                            val hitBall = latestCandidates
                                .map { cand ->
                                    val bx = cand.imageCenterPx.x.toFloat() * scaleX
                                    val by = cand.imageCenterPx.y.toFloat() * scaleY
                                    cand to hypot(startOffset.x - bx, startOffset.y - by)
                                }
                                .filter { (_, dist) -> dist <= ballHitRadius }
                                .minByOrNull { (_, dist) -> dist }
                                ?.first
                            draggedCorner = null
                            draggedBallId = hitBall?.id
                            latestOnActiveCornerChanged(null)
                            latestOnDraggedCandidateChanged(hitBall?.id)
                            if (hitBall != null) {
                                latestOnSelectBallCandidate(hitBall.id)
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        val canvasW = size.width.toFloat().coerceAtLeast(1f)
                        val canvasH = size.height.toFloat().coerceAtLeast(1f)
                        val dxImg = dragAmount.x.toDouble() * (latestFrameW.toDouble() / canvasW.toDouble())
                        val dyImg = dragAmount.y.toDouble() * (latestFrameH.toDouble() / canvasH.toDouble())
                        val idx = draggedCorner
                        val ballId = draggedBallId
                        if (idx != null) {
                            change.consume()
                            val current = latestCorners
                            if (idx in current.indices) {
                                val prev = current[idx]
                                val updatedCorner = Vec2(
                                    x = (prev.x + dxImg).coerceIn(0.0, latestFrameW.toDouble()),
                                    y = (prev.y + dyImg).coerceIn(0.0, latestFrameH.toDouble()),
                                )
                                val nextList = current.toMutableList()
                                nextList[idx] = updatedCorner
                                latestOnCornersChanged(nextList)
                            }
                        } else if (ballId != null) {
                            change.consume()
                            val prev = latestCandidates.firstOrNull { it.id == ballId } ?: return@detectDragGestures
                            latestOnMoveBallCandidate(
                                ballId,
                                Vec2(
                                    x = (prev.imageCenterPx.x + dxImg).coerceIn(0.0, latestFrameW.toDouble()),
                                    y = (prev.imageCenterPx.y + dyImg).coerceIn(0.0, latestFrameH.toDouble()),
                                ),
                            )
                        }
                    },
                    onDragEnd = {
                        draggedCorner = null
                        draggedBallId = null
                        latestOnActiveCornerChanged(null)
                        latestOnDraggedCandidateChanged(null)
                    },
                    onDragCancel = {
                        draggedCorner = null
                        draggedBallId = null
                        latestOnActiveCornerChanged(null)
                        latestOnDraggedCandidateChanged(null)
                    },
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(frameAspectRatio)
                    .clip(RoundedCornerShape(10.dp))
                    .then(dragModifier)
                    .then(tapModifier),
            ) {
                val scaleX = size.width / frameWidthPx
                val scaleY = size.height / frameHeightPx
                fun toCanvas(imagePt: Vec2): Offset = Offset(
                    x = (imagePt.x * scaleX).toFloat(),
                    y = (imagePt.y * scaleY).toFloat(),
                )

                val detectionRadiusPx = max(8f, size.minDimension * 0.030f)
                val ballFontSp = with(density) { (detectionRadiusPx * 0.95f).toSp() }
                val handleLabelSp = with(density) { 10.dp.toSp() }

                fun DrawScope.drawFramingScene() {
                    // 1. Camera sensor backdrop (draw real photo if imported, else schematic reticle)
                    drawRect(color = ViewfinderBackdropColor)
                    if (sourceImageBitmap != null) {
                        drawImage(
                            image = sourceImageBitmap,
                            dstSize = IntSize(
                                width = size.width.roundToInt().coerceAtLeast(1),
                                height = size.height.roundToInt().coerceAtLeast(1),
                            ),
                        )
                    } else {
                        drawFramingGuideReticle(
                            isAcceptable = qualityReport.isAcceptable,
                            textMeasurer = textMeasurer,
                            labelFontSp = handleLabelSp,
                        )
                    }

                    // 2. Table surface quadrilateral connecting HL, FL, FR, HR
                    if (quadCornersPx.size == 4) {
                        val pts = quadCornersPx.map(::toCanvas)
                        val quadPath = Path().apply {
                            moveTo(pts[0].x, pts[0].y)
                            lineTo(pts[1].x, pts[1].y)
                            lineTo(pts[2].x, pts[2].y)
                            lineTo(pts[3].x, pts[3].y)
                            close()
                        }
                        drawPath(
                            path = quadPath,
                            color = if (sourceImageBitmap != null) Color(0x18137547) else Color(0x52137547),
                        )
                        drawPath(
                            path = quadPath,
                            color = if (isQuadGeometryInvalid) ErrorRedColor else GuideCyanColor,
                            style = Stroke(
                                width = 2.5f,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )

                        // Project table head string and centre line onto the angled view when valid
                        if (cornerHomography != null) {
                            drawProjectedTableGuides(
                                homography = cornerHomography,
                                photo = photo,
                                toCanvas = ::toCanvas,
                            )
                        }
                    }

                    // 3. Simulated glare spots indicator when glare exceeds threshold
                    if (qualityReport.glareFraction > PhotoCaptureEvaluator.MAX_GLARE_FRACTION) {
                        val glareCenter = Offset(size.width * 0.56f, size.height * 0.42f)
                        drawCircle(
                            color = Color(0x44FFF8E1),
                            radius = size.minDimension * 0.14f,
                            center = glareCenter,
                        )
                        drawCircle(
                            color = WarningAmberColor.copy(alpha = 0.65f),
                            radius = size.minDimension * 0.14f,
                            center = glareCenter,
                            style = Stroke(
                                width = 1.6f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                            ),
                        )
                    }

                    // 4. Detected balls in image space
                    for (candidate in photo.rawDetections) {
                        val center = toCanvas(candidate.imageCenterPx)
                        val match = candidateLookup[candidate.id]
                        val assignedNumber = match?.second
                        val needsReview = match?.first?.needsUserReview == true
                        drawImageSpaceBall(
                            candidate = candidate,
                            assignedNumber = assignedNumber,
                            needsReview = needsReview,
                            isSelected = activeCandidateId == candidate.id,
                            isBeingDragged = draggedCandidateId == candidate.id,
                            ballSet = ballSet,
                            center = center,
                            radiusPx = detectionRadiusPx,
                            textMeasurer = textMeasurer,
                            fontSizeSp = ballFontSp,
                        )
                    }

                    // 5. Draggable corner handles (HL, FL, FR, HR)
                    quadCornersPx.forEachIndexed { index, cornerPx ->
                        val label = CORNER_LABELS.getOrElse(index) { "C$index" }
                        val center = toCanvas(cornerPx)
                        val isActive = activeCornerIndex == index
                        val handleColor = if (isActive) WarningAmberColor else GuideCyanColor
                        drawCircle(
                            color = handleColor.copy(alpha = if (isActive) 0.34f else 0.20f),
                            radius = if (isActive) 22f else 17f,
                            center = center,
                        )
                        drawCircle(
                            color = handleColor,
                            radius = if (isActive) 10f else 8f,
                            center = center,
                        )
                        drawCircle(
                            color = ViewfinderBackdropColor,
                            radius = 3.5f,
                            center = center,
                        )
                        val tagLayout = textMeasurer.measure(
                            text = label,
                            style = TextStyle(
                                color = handleColor,
                                fontSize = handleLabelSp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        val tagX = (center.x + 12f).coerceIn(
                            4f,
                            (size.width - tagLayout.size.width - 4f).coerceAtLeast(4f),
                        )
                        val tagY = (center.y - tagLayout.size.height - 8f).coerceIn(
                            4f,
                            (size.height - tagLayout.size.height - 4f).coerceAtLeast(4f),
                        )
                        drawText(
                            textLayoutResult = tagLayout,
                            topLeft = Offset(tagX, tagY),
                        )
                    }
                }

                drawFramingScene()

                // Floating magnifying glass loupe when dragging either a table corner or a ball
                val loupeTarget: Triple<Offset, String, Color>? = when {
                    activeCornerIndex != null && activeCornerIndex in quadCornersPx.indices -> {
                        val label = CORNER_LABELS.getOrElse(activeCornerIndex) { "C$activeCornerIndex" }
                        Triple(toCanvas(quadCornersPx[activeCornerIndex]), label, WarningAmberColor)
                    }
                    draggedCandidateId != null -> {
                        val draggedCand = photo.rawDetections.firstOrNull { it.id == draggedCandidateId }
                        draggedCand?.let { cand ->
                            val num = candidateLookup[cand.id]?.second
                            val label = when (num) {
                                null -> "Ball"
                                0 -> "Cue Ball"
                                else -> "Ball #$num"
                            }
                            Triple(toCanvas(cand.imageCenterPx), label, GuideCyanColor)
                        }
                    }
                    else -> null
                }

                if (loupeTarget != null) {
                    val (focusPos, badge, accent) = loupeTarget
                    val loupeR = minOf(with(density) { 56.dp.toPx() }, size.minDimension * 0.25f)
                    val clearance = with(density) { 42.dp.toPx() }
                    val loupeCenter = computeLoupeCenter(
                        targetCanvasPos = focusPos,
                        canvasSize = size,
                        loupeRadiusPx = loupeR,
                        fingerClearancePx = clearance,
                    )
                    drawPrecisionLoupe(
                        focusCanvasPos = focusPos,
                        loupeCenter = loupeCenter,
                        loupeRadiusPx = loupeR,
                        zoom = 2.5f,
                        accentColor = accent,
                        badgeLabel = badge,
                        showCenterRedDot = true,
                        showCrosshair = true,
                        textMeasurer = textMeasurer,
                        labelFontSp = handleLabelSp,
                        drawZoomedScene = { drawFramingScene() },
                    )
                }
            }

            if (isQuadGeometryInvalid) {
                Text(
                    text = "Corner geometry is degenerate — drag corners back to the cushion noses or tap Reset Corners.",
                    style = MaterialTheme.typography.labelSmall,
                    color = ErrorRedColor,
                )
            }

            if (isRescanningArea || rescanFeedbackMessage != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isRescanningArea) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                text = "Scanning tapped area for missed balls...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        } else if (rescanFeedbackMessage != null) {
                            Text(
                                text = rescanFeedbackMessage,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismissFeedback) {
                                Text("Hide")
                            }
                        }
                    }
                }
            }

            val selectedMatch = activeCandidateId?.let { id ->
                displayedBalls.firstOrNull { (item, _) -> item.candidateId == id }
            }
            if (selectedMatch != null) {
                val (selectedItem, effectiveNum) = selectedMatch
                val rawCand = photo.rawDetections.firstOrNull { it.id == selectedItem.candidateId }
                val colorHint = rawCand?.dominantColor?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Auto"
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, GuideCyanColor.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                MiniBallSwatch(ballNumber = effectiveNum, ballSet = ballSet)
                                Column {
                                    Text(
                                        text = if (effectiveNum == 0) "Selected: Cue Ball (0)" else "Selected: Ball #$effectiveNum",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Detected color: $colorHint • Drag on photo to align",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onDeleteBallCandidate(selectedItem.candidateId) }) {
                                    Text("Remove", color = ErrorRedColor)
                                }
                                TextButton(onClick = { onSelectBallCandidate(null) }) {
                                    Text("Done")
                                }
                            }
                        }

                        BallNumberSelectionGrid(
                            selectedNumber = effectiveNum,
                            ballSet = ballSet,
                            onSelectNumber = { num -> onReassignBallNumber(selectedItem.candidateId, num) },
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawFramingGuideReticle(
    isAcceptable: Boolean,
    textMeasurer: TextMeasurer,
    labelFontSp: TextUnit,
) {
    val marginX = size.width * 0.06f
    val marginY = size.height * 0.08f
    val guideColor = if (isAcceptable) {
        Color(0x55E2E8F0)
    } else {
        WarningAmberColor.copy(alpha = 0.55f)
    }

    drawRect(
        color = guideColor,
        topLeft = Offset(marginX, marginY),
        size = Size(size.width - 2f * marginX, size.height - 2f * marginY),
        style = Stroke(
            width = 1.4f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f),
        ),
    )

    val bracketLen = minOf(size.width, size.height) * 0.055f
    val left = marginX
    val right = size.width - marginX
    val top = marginY
    val bottom = size.height - marginY
    val bracketColor = if (isAcceptable) GuideCyanColor.copy(alpha = 0.85f) else WarningAmberColor

    // Four L-brackets
    drawLine(bracketColor, Offset(left, top), Offset(left + bracketLen, top), strokeWidth = 2.5f)
    drawLine(bracketColor, Offset(left, top), Offset(left, top + bracketLen), strokeWidth = 2.5f)

    drawLine(bracketColor, Offset(right, top), Offset(right - bracketLen, top), strokeWidth = 2.5f)
    drawLine(bracketColor, Offset(right, top), Offset(right, top + bracketLen), strokeWidth = 2.5f)

    drawLine(bracketColor, Offset(left, bottom), Offset(left + bracketLen, bottom), strokeWidth = 2.5f)
    drawLine(bracketColor, Offset(left, bottom), Offset(left, bottom - bracketLen), strokeWidth = 2.5f)

    drawLine(bracketColor, Offset(right, bottom), Offset(right - bracketLen, bottom), strokeWidth = 2.5f)
    drawLine(bracketColor, Offset(right, bottom), Offset(right, bottom - bracketLen), strokeWidth = 2.5f)

    // Center crosshair
    val cx = size.width * 0.5f
    val cy = size.height * 0.5f
    val cross = 8f
    drawLine(guideColor, Offset(cx - cross, cy), Offset(cx + cross, cy), strokeWidth = 1.2f)
    drawLine(guideColor, Offset(cx, cy - cross), Offset(cx, cy + cross), strokeWidth = 1.2f)

    val guideText = textMeasurer.measure(
        text = "FRAME ALL 4 TABLE CORNERS",
        style = TextStyle(
            color = guideColor,
            fontSize = labelFontSp,
            fontWeight = FontWeight.Medium,
        ),
    )
    drawText(
        textLayoutResult = guideText,
        topLeft = Offset(
            x = (size.width - guideText.size.width) * 0.5f,
            y = (marginY - guideText.size.height) * 0.5f,
        ),
    )
}

private fun DrawScope.drawProjectedTableGuides(
    homography: PerspectiveHomography,
    photo: CapturedTablePhoto,
    toCanvas: (Vec2) -> Offset,
) {
    fun safeProject(tablePt: Vec2): Offset? {
        val img = homography.tableClothToImage(tablePt)
        if (!img.x.isFinite() || !img.y.isFinite()) return null
        if (abs(img.x) > 1e5 || abs(img.y) > 1e5) return null
        return toCanvas(img)
    }

    val table = photo.tableSpec
    val headStart = safeProject(Vec2(table.lengthMeters * 0.25, 0.0))
    val headEnd = safeProject(Vec2(table.lengthMeters * 0.25, table.widthMeters))
    if (headStart != null && headEnd != null) {
        drawLine(
            color = Color(0x66E2E8F0),
            start = headStart,
            end = headEnd,
            strokeWidth = 1.4f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
        )
    }

    val sideStart = safeProject(Vec2(table.lengthMeters * 0.5, 0.0))
    val sideEnd = safeProject(Vec2(table.lengthMeters * 0.5, table.widthMeters))
    if (sideStart != null && sideEnd != null) {
        drawLine(
            color = Color(0x44E2E8F0),
            start = sideStart,
            end = sideEnd,
            strokeWidth = 1.2f,
        )
    }

    val footSpot = safeProject(table.footSpot())
    if (footSpot != null) {
        drawCircle(
            color = Color(0x88E2E8F0),
            radius = 3.5f,
            center = footSpot,
        )
    }
}

private fun DrawScope.drawImageSpaceBall(
    candidate: DetectedBallCandidate,
    assignedNumber: Int?,
    needsReview: Boolean,
    isSelected: Boolean,
    isBeingDragged: Boolean,
    ballSet: BallSetProfile,
    center: Offset,
    radiusPx: Float,
    textMeasurer: TextMeasurer,
    fontSizeSp: TextUnit,
) {
    val baseColor = if (assignedNumber != null) {
        ballBaseColor(assignedNumber, ballSet)
    } else {
        ballPaletteToComposeColor(candidate.dominantColor)
    }
    val whiteBase = Color(0xFFF8FAFC)
    val isStripe = assignedNumber != null && assignedNumber in 9..15

    if (isBeingDragged) {
        // Ghost ball ring keeping the original underlying photo unobstructed with a red center dot
        drawCircle(
            color = GuideCyanColor.copy(alpha = 0.24f),
            radius = radiusPx * 1.48f,
            center = center,
            style = Stroke(width = 2.0f),
        )
        drawCircle(
            color = baseColor.copy(alpha = 0.80f),
            radius = radiusPx * 1.04f,
            center = center,
            style = Stroke(
                width = 2.6f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3.5f), 0f),
            ),
        )
        if (isStripe) {
            drawCircle(
                color = whiteBase.copy(alpha = 0.85f),
                radius = radiusPx * 0.82f,
                center = center,
                style = Stroke(
                    width = 1.6f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
                ),
            )
        }
        drawCircle(color = Color(0xEE0F172A), radius = 3.8f, center = center)
        drawCircle(color = whiteBase, radius = 3.0f, center = center)
        drawCircle(color = Color(0xFFEF4444), radius = 2.1f, center = center)

        if (assignedNumber != null) {
            val tagText = if (assignedNumber == 0) "Cue" else "#$assignedNumber"
            val tagLayout = textMeasurer.measure(
                text = tagText,
                style = TextStyle(
                    color = whiteBase,
                    fontSize = fontSizeSp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            val tagOffset = Offset(
                x = (center.x + radiusPx * 0.95f).coerceIn(
                    2f,
                    (size.width - tagLayout.size.width - 4f).coerceAtLeast(2f),
                ),
                y = (center.y - radiusPx * 0.95f - tagLayout.size.height).coerceIn(
                    2f,
                    (size.height - tagLayout.size.height - 2f).coerceAtLeast(2f),
                ),
            )
            drawRoundRect(
                color = Color(0xDC0F172A),
                topLeft = tagOffset - Offset(3f, 1.5f),
                size = Size(tagLayout.size.width + 6f, tagLayout.size.height + 3f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
            )
            drawText(
                textLayoutResult = tagLayout,
                topLeft = tagOffset,
            )
        }
        return
    }

    if (needsReview) {
        drawCircle(
            color = WarningAmberColor.copy(alpha = 0.28f),
            radius = radiusPx * 1.45f,
            center = center,
        )
        drawCircle(
            color = WarningAmberColor,
            radius = radiusPx * 1.40f,
            center = center,
            style = Stroke(width = 2f),
        )
    }
    if (isSelected) {
        drawCircle(
            color = GuideCyanColor,
            radius = radiusPx * 1.32f,
            center = center,
            style = Stroke(width = 2.4f),
        )
    }

    drawCircle(
        color = Color(0x66000000),
        radius = radiusPx,
        center = center + Offset(1.5f, 2f),
    )

    if (isStripe) {
        drawCircle(color = whiteBase, radius = radiusPx, center = center)
        val clipCircle = Path().apply { addOval(Rect(center = center, radius = radiusPx)) }
        clipPath(clipCircle) {
            val bandHalf = radiusPx * 0.56f
            drawRect(
                color = baseColor,
                topLeft = Offset(center.x - radiusPx, center.y - bandHalf),
                size = Size(radiusPx * 2f, bandHalf * 2f),
            )
        }
    } else {
        drawCircle(color = baseColor, radius = radiusPx, center = center)
    }

    if (assignedNumber != null && assignedNumber != 0) {
        drawCircle(color = whiteBase, radius = radiusPx * 0.52f, center = center)
        val labelLayout = textMeasurer.measure(
            text = assignedNumber.toString(),
            style = TextStyle(
                color = Color(0xFF0F172A),
                fontSize = fontSizeSp,
                fontWeight = FontWeight.Bold,
            ),
        )
        drawText(
            textLayoutResult = labelLayout,
            topLeft = Offset(
                x = center.x - labelLayout.size.width * 0.5f,
                y = center.y - labelLayout.size.height * 0.5f,
            ),
        )
    }

    drawCircle(
        color = Color(0xAA0F172A),
        radius = radiusPx,
        center = center,
        style = Stroke(width = 1.2f),
    )
}

@Composable
private fun RectificationAndIdentityCard(
    rectification: VisionRectificationResult,
    finalLayout: TableLayout,
    medianErrorMm: Double,
    frozenBalls: List<FrozenBallStatus>,
    displayedBalls: List<Pair<IdentifiedBallResult, Int>>,
    candidateNumberOverrides: Map<Int, Int>,
    activeReassignCandidateId: Int?,
    ballSet: BallSetProfile,
    onToggleReassignCandidate: (Int) -> Unit,
    onReassignBallNumber: (candidateId: Int, newNumber: Int) -> Unit,
    onDeleteBallCandidate: (candidateId: Int) -> Unit,
    onResetAllIdentities: () -> Unit,
    displayMode: AppDisplayMode = AppDisplayMode.PRO,
) {
    val errorOnTarget = medianErrorMm <= TARGET_MEDIAN_ERROR_MM
    val sortedDisplayed = remember(displayedBalls) {
        displayedBalls.sortedBy { (_, num) -> num }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (displayMode == AppDisplayMode.PRO) {
                        "3D Rectification, Frozen Balls & Identity Review"
                    } else {
                        "Detected Table & Balls (${sortedDisplayed.size})"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (candidateNumberOverrides.isNotEmpty()) {
                    TextButton(onClick = onResetAllIdentities) {
                        Text("Reset IDs")
                    }
                }
            }

            if (displayMode == AppDisplayMode.PRO) {
                // Camera height + median error metrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QualityMetricTile(
                        label = "Est. 3D Camera Height",
                        value = "Z = ${formatDecimal(rectification.cameraPositionMeters.z, 2)} m",
                        target = "X=${formatDecimal(rectification.cameraPositionMeters.x, 2)} m, Y=${formatDecimal(rectification.cameraPositionMeters.y, 2)} m",
                        isGood = true,
                        modifier = Modifier.weight(1f),
                    )
                    QualityMetricTile(
                        label = "Median Ball-Centre Error",
                        value = "${formatDecimal(medianErrorMm, 1)} mm",
                        target = "Target <= ${formatDecimal(TARGET_MEDIAN_ERROR_MM, 1)} mm",
                        isGood = errorOnTarget,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Compact top-down rectified preview
            PoolTableCanvas(
                layout = finalLayout,
                selectedShot = null,
                modifier = Modifier.fillMaxWidth(),
                ballSet = ballSet,
                onMoveBall = null,
            )

            if (frozenBalls.isNotEmpty() || displayMode == AppDisplayMode.PRO) {
                // Frozen balls indicator
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = if (frozenBalls.isEmpty()) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    } else {
                        WarningAmberColor.copy(alpha = 0.12f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (frozenBalls.isEmpty()) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            WarningAmberColor.copy(alpha = 0.45f)
                        },
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = if (frozenBalls.isEmpty()) {
                                "Frozen Balls: None detected"
                            } else {
                                "Touching / Cushion Balls (${frozenBalls.size})"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (frozenBalls.isEmpty()) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                WarningAmberColor
                            },
                        )
                        frozenBalls.forEach { status ->
                            Text(
                                text = "• ${formatFrozenBallStatus(status)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            // Identified balls review list (collapsed when nothing needs review in Simple Mode, or directly shown when few/flagged)
            val reviewCount = sortedDisplayed.count { (item, _) -> item.needsUserReview }
            Text(
                text = if (reviewCount > 0) {
                    "Tap any highlighted ball to fix its number ($reviewCount flagged):"
                } else {
                    "Tap any ball below to reassign its number:"
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (reviewCount > 0) WarningAmberColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            sortedDisplayed.forEach { (item, effectiveNumber) ->
                val isOverridden = effectiveNumber != item.ball.number
                val isExpanded = activeReassignCandidateId == item.candidateId
                val confidencePct = (item.identificationConfidence * 100.0).roundToInt()

                val cardBorder = when {
                    isExpanded -> MaterialTheme.colorScheme.primary
                    item.needsUserReview -> WarningAmberColor
                    isOverridden -> GuideCyanColor
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val cardBg = when {
                    item.needsUserReview -> WarningAmberColor.copy(alpha = 0.10f)
                    isExpanded -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onToggleReassignCandidate(item.candidateId) },
                    shape = RoundedCornerShape(10.dp),
                    color = cardBg,
                    border = BorderStroke(if (item.needsUserReview || isExpanded) 1.5.dp else 1.dp, cardBorder),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                MiniBallSwatch(
                                    ballNumber = effectiveNumber,
                                    ballSet = ballSet,
                                )
                                Column {
                                    val title = if (effectiveNumber == 0) {
                                        "Cue Ball (0)"
                                    } else {
                                        "Ball #$effectiveNumber"
                                    }
                                    val overrideNote = if (isOverridden) {
                                        " (was #${item.ball.number})"
                                    } else {
                                        ""
                                    }
                                    Text(
                                        text = "$title$overrideNote",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Pos (${formatDecimal(item.ball.position.x, 2)} m, ${formatDecimal(item.ball.position.y, 2)} m)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (item.needsUserReview) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = WarningAmberColor.copy(alpha = 0.22f),
                                    ) {
                                        Text(
                                            text = "Review",
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = WarningAmberColor,
                                        )
                                    }
                                } else if (isOverridden) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = GuideCyanColor.copy(alpha = 0.22f),
                                    ) {
                                        Text(
                                            text = "Updated",
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = GuideCyanColor,
                                        )
                                    }
                                }
                                Text(
                                    text = "$confidencePct%",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.needsUserReview) WarningAmberColor else SuccessGreenColor,
                                )
                            }
                        }

                        if (isExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Assign number (Cue or 1–15; active numbers swap):",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { onDeleteBallCandidate(item.candidateId) }) {
                                        Text("Remove", color = ErrorRedColor)
                                    }
                                }
                                BallNumberSelectionGrid(
                                    selectedNumber = effectiveNumber,
                                    ballSet = ballSet,
                                    onSelectNumber = { num -> onReassignBallNumber(item.candidateId, num) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BallNumberSelectionGrid(
    selectedNumber: Int,
    ballSet: BallSetProfile,
    onSelectNumber: (Int) -> Unit,
) {
    val allNumbers = remember { listOf(0) + (1..15).toList() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        allNumbers.chunked(4).forEach { rowNumbers ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowNumbers.forEach { number ->
                    val isCurrent = number == selectedNumber
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectNumber(number) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                        },
                        border = BorderStroke(
                            width = if (isCurrent) 1.5.dp else 1.dp,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(ballBaseColor(number, ballSet)),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (number == 0) "Cue" else "$number",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniBallSwatch(
    ballNumber: Int,
    ballSet: BallSetProfile,
) {
    val ballColor = ballBaseColor(ballNumber, ballSet)
    val isStripe = ballNumber in 9..15
    val whiteBase = Color(0xFFF8FAFC)

    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (isStripe) whiteBase else ballColor)
            .border(1.dp, Color(0xAA0F172A), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (isStripe) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(15.dp)
                    .background(ballColor),
            )
        }
        if (ballNumber != 0) {
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(whiteBase),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ballNumber.toString(),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A),
                )
            }
        }
    }
}

@Composable
private fun GameTypeConfirmationCard(
    suggestions: List<GameTypeSuggestion>,
    selectedVariant: DetectedGameVariant,
    selectedEightBallGroup: EightBallGroup,
    ballSet: BallSetProfile,
    planningReadiness: PlanningReadinessResult,
    onSelectVariant: (DetectedGameVariant) -> Unit,
    onSelectEightBallGroup: (EightBallGroup) -> Unit,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Suggested Game Type & Confirmation",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            suggestions.forEach { suggestion ->
                val isSelected = suggestion.variant == selectedVariant
                val confidencePct = (suggestion.confidence * 100.0).roundToInt()
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectVariant(suggestion.variant) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = suggestion.variant.displayLabel(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            ) {
                                Text(
                                    text = "$confidencePct% confidence",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        suggestion.reasons.forEach { reason ->
                            Text(
                                text = "• $reason",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (selectedVariant == DetectedGameVariant.EIGHT_BALL) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (selectedEightBallGroup == EightBallGroup.UNSET) {
                            "Choose Your 8-Ball Group (Required Before Planning)"
                        } else {
                            "8-Ball Group"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selectedEightBallGroup == EightBallGroup.UNSET) {
                            WarningAmberColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        EIGHT_BALL_CONFIRM_GROUPS.forEach { group ->
                            val isSelected = group == selectedEightBallGroup
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSelectEightBallGroup(group) },
                                label = {
                                    Text(
                                        text = group.displayNameFor(ballSet),
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
            }

            if (!planningReadiness.isReady) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = WarningAmberColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, WarningAmberColor.copy(alpha = 0.65f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Planning Paused: ${planningReadiness.headline}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = WarningAmberColor,
                        )
                        Text(
                            text = planningReadiness.guidance,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            val confirmLabel = when {
                planningReadiness.isReady -> "Confirm & Start Planning"
                planningReadiness.missingBallNumbers.isNotEmpty() ->
                    "Open in Table Editor (Planning Paused)"
                else -> "Select Your 8-Ball Group Above"
            }

            Button(
                onClick = onConfirm,
                enabled = canConfirm,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = confirmLabel,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun reassignWithUniqueSwap(
    identifiedBalls: List<IdentifiedBallResult>,
    currentOverrides: Map<Int, Int>,
    targetCandidateId: Int,
    newNumber: Int,
): Map<Int, Int> {
    val targetOriginal = identifiedBalls.firstOrNull { it.candidateId == targetCandidateId }
        ?: return currentOverrides
    val currentTargetNumber = currentOverrides[targetCandidateId] ?: targetOriginal.ball.number
    if (currentTargetNumber == newNumber) return currentOverrides

    val updated = currentOverrides.toMutableMap()
    val occupant = identifiedBalls.firstOrNull { item ->
        item.candidateId != targetCandidateId &&
            (updated[item.candidateId] ?: item.ball.number) == newNumber
    }

    updated[targetCandidateId] = newNumber
    if (occupant != null) {
        updated[occupant.candidateId] = currentTargetNumber
    }

    // Drop entries that match their original unedited number
    identifiedBalls.forEach { item ->
        if (updated[item.candidateId] == item.ball.number) {
            updated.remove(item.candidateId)
        }
    }
    return updated
}

private fun formatFrozenBallStatus(status: FrozenBallStatus): String {
    val parts = mutableListOf<String>()
    if (status.frozenToCushion) {
        parts += "frozen to cushion"
    }
    if (status.frozenToBallNumbers.isNotEmpty()) {
        val others = status.frozenToBallNumbers.joinToString(", ") { "Ball #$it" }
        parts += "frozen to $others"
    }
    val detail = parts.joinToString(" & ").ifEmpty { "frozen" }
    return "Ball #${status.ballNumber}: $detail"
}

private fun GameType.toDetectedVariant(): DetectedGameVariant = when (this) {
    GameType.EIGHT_BALL -> DetectedGameVariant.EIGHT_BALL
    GameType.NINE_BALL -> DetectedGameVariant.NINE_BALL
    GameType.TEN_BALL -> DetectedGameVariant.TEN_BALL
}

private fun DetectedGameVariant.displayLabel(): String = when (this) {
    DetectedGameVariant.EIGHT_BALL -> "8-Ball"
    DetectedGameVariant.NINE_BALL -> "9-Ball"
    DetectedGameVariant.TEN_BALL -> "10-Ball"
    DetectedGameVariant.SNOOKER -> "Snooker"
}

private fun formatDecimal(value: Double, decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val scaled = round(value * scale).toLong()
    val sign = if (scaled < 0L) "-" else ""
    val absScaled = abs(scaled)
    val whole = absScaled / scale
    val frac = absScaled % scale
    return if (decimals == 0) {
        "$sign$whole"
    } else {
        "$sign$whole.${frac.toString().padStart(decimals, '0')}"
    }
}

/**
 * Shared assumed cloth roll & rail density card with preset chips and live `- / +` steppers
 * used in both Photo Scan confirmation and the live Table & Planner workspace.
 */
@Composable
internal fun AssumedSurfaceControlCard(
    title: String,
    clothParams: ClothParams,
    presetLabels: Map<AssumedSurfacePreset, String>,
    onUpdateClothParams: (ClothParams) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    rollStepperTitle: String = "Cloth Roll (μr)",
    railStepperTitle: String = "Rail Bounce (ec)",
) {
    val matchedPreset = matchingAssumedSurfacePreset(clothParams)
    val statusBadge = when (matchedPreset) {
        AssumedSurfacePreset.PRO_BROADCAST_FAST -> "Pro Fast"
        AssumedSurfacePreset.STANDARD_POOL_HALL -> "Standard"
        AssumedSurfacePreset.SLOW_BAR_BOX_HEAVY -> "Slow Bar"
        AssumedSurfacePreset.SNOOKER_DIRECTIONAL -> "Snooker"
        null -> "Custom"
    }
    val rollLabel = clothRollSpeedLabel(clothParams.rollingResistanceMuR)
    val railLabel = railBounceLabel(clothParams.cushionRestitutionEc)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ) {
                    Text(
                        text = statusBadge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssumedSurfacePreset.entries.forEach { preset ->
                    val isSelected = preset == matchedPreset
                    FilterChip(
                        selected = isSelected,
                        onClick = { onUpdateClothParams(ClothParams.fromPreset(preset)) },
                        label = {
                            Text(
                                text = presetLabels[preset] ?: preset.name,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SurfaceStepperTile(
                    label = rollStepperTitle,
                    valueText = formatDecimal(clothParams.rollingResistanceMuR, 4),
                    descriptor = rollLabel,
                    canDecrement = clothParams.rollingResistanceMuR > MIN_ROLLING_RESISTANCE_MU_R + 1e-6,
                    canIncrement = clothParams.rollingResistanceMuR < MAX_ROLLING_RESISTANCE_MU_R - 1e-6,
                    onDecrement = {
                        val nextMuR = roundToDecimals(
                            clothParams.rollingResistanceMuR - ROLLING_RESISTANCE_STEP_MU_R,
                            4,
                        )
                        onUpdateClothParams(clothParams.withAdjustedRollAndRail(rollingResistanceMuR = nextMuR))
                    },
                    onIncrement = {
                        val nextMuR = roundToDecimals(
                            clothParams.rollingResistanceMuR + ROLLING_RESISTANCE_STEP_MU_R,
                            4,
                        )
                        onUpdateClothParams(clothParams.withAdjustedRollAndRail(rollingResistanceMuR = nextMuR))
                    },
                    modifier = Modifier.weight(1f),
                )

                SurfaceStepperTile(
                    label = railStepperTitle,
                    valueText = formatDecimal(clothParams.cushionRestitutionEc, 2),
                    descriptor = railLabel,
                    canDecrement = clothParams.cushionRestitutionEc > MIN_CUSHION_RESTITUTION_EC + 1e-6,
                    canIncrement = clothParams.cushionRestitutionEc < MAX_CUSHION_RESTITUTION_EC - 1e-6,
                    onDecrement = {
                        val nextEc = roundToDecimals(
                            clothParams.cushionRestitutionEc - CUSHION_RESTITUTION_STEP_EC,
                            3,
                        )
                        onUpdateClothParams(clothParams.withAdjustedRollAndRail(cushionRestitutionEc = nextEc))
                    },
                    onIncrement = {
                        val nextEc = roundToDecimals(
                            clothParams.cushionRestitutionEc + CUSHION_RESTITUTION_STEP_EC,
                            3,
                        )
                        onUpdateClothParams(clothParams.withAdjustedRollAndRail(cushionRestitutionEc = nextEc))
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = "Active coefficients: μr = ${formatDecimal(clothParams.rollingResistanceMuR, 4)} ($rollLabel)  •  " +
                    "μs = ${formatDecimal(clothParams.slidingFrictionMuS, 3)}  •  " +
                    "ec = ${formatDecimal(clothParams.cushionRestitutionEc, 2)} ($railLabel)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SurfaceStepperTile(
    label: String,
    valueText: String,
    descriptor: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepperPillButton(
                    symbol = "-",
                    enabled = canDecrement,
                    onClick = onDecrement,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = valueText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = descriptor,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                StepperPillButton(
                    symbol = "+",
                    enabled = canIncrement,
                    onClick = onIncrement,
                )
            }
        }
    }
}

@Composable
private fun StepperPillButton(
    symbol: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(width = 34.dp, height = 32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        border = BorderStroke(
            1.dp,
            if (enabled) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
            )
        }
    }
}

private fun matchingAssumedSurfacePreset(cloth: ClothParams): AssumedSurfacePreset? =
    AssumedSurfacePreset.entries.firstOrNull { preset ->
        abs(cloth.rollingResistanceMuR - preset.rollingResistanceMuR) < 1e-6 &&
            abs(cloth.cushionRestitutionEc - preset.cushionRestitutionEc) < 1e-6 &&
            abs(cloth.slidingFrictionMuS - preset.slidingFrictionMuS) < 1e-6
    }

private fun clothRollSpeedLabel(muR: Double): String = when {
    muR <= 0.0085 -> "Fast"
    muR <= 0.0125 -> "Medium"
    else -> "Slow"
}

private fun railBounceLabel(ec: Double): String = when {
    ec < 0.70 -> "Soft/Dead"
    ec <= 0.78 -> "Standard"
    else -> "Lively"
}

private fun canonicalizeQuadTopLeftClockwise(corners: List<Vec2>): List<Vec2> {
    if (corners.size != 4) return corners
    val byY = corners.sortedBy { it.y }
    val topPair = byY.take(2).sortedBy { it.x }
    val bottomPair = byY.drop(2).sortedBy { it.x }
    return listOf(topPair[0], topPair[1], bottomPair[1], bottomPair[0])
}

private fun roundToDecimals(value: Double, decimals: Int): Double {
    var scale = 1.0
    repeat(decimals) { scale *= 10.0 }
    return round(value * scale) / scale
}
