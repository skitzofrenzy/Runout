package com.runout.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.runout.app.tools.WalkthroughVisualStage
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.Camera3DOrbitSpec
import com.runout.engine.physics.Projected3DPoint
import com.runout.engine.physics.ShotAnimationPhase
import com.runout.engine.physics.ShotAnimationTimeline
import com.runout.engine.physics.ShotTimelineAnimator
import com.runout.engine.physics.ShotTimelineFrame
import com.runout.engine.physics.TipOffset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

private val DiagramRailWoodOuter = Color(0xFF3E2415)
private val DiagramRailWoodInner = Color(0xFF54321E)
private val DiagramRailWoodHighlight = Color(0xFF6E432A)
private val DiagramRailSkirt = Color(0xFF28160C)
private val DiagramCushionCloth = Color(0xFF0D5C3A)
private val DiagramFeltBed = Color(0xFF137547)
private val DiagramMarkingLine = Color(0x66E2E8F0)
private val DiagramDiamondSight = Color(0xFFD6C7B2)
private val DiagramPocketRim = Color(0xFF1E293B)
private val DiagramPocketHole = Color(0xFF080D14)
private val DiagramCueAccent = Color(0xFF38BDF8)
private val DiagramPotGold = Color(0xFFFFD54F)
private val DiagramGhostCyan = Color(0xFF7DD3FC)
private val DiagramImpactCoral = Color(0xFFFF6B4A)
private val DiagramBackdropDark = Color(0xFF070D1A)

private const val OUTER_RAIL_MARGIN_METERS: Double = 0.095

private enum class CameraPreset3D(val label: String) {
    BEHIND_CUE("Behind Cue 3D"),
    CORNER("Corner 3D"),
    SIDE_BROADCAST("Side Broadcast 3D"),
    OVERHEAD("Overhead"),
}

/**
 * Presentation phases inside interactive tutorials and 8-Ball walkthrough cards.
 */
enum class TutorialPresentationPhase(val label: String) {
    PHASE_1_TEXT_AND_DIAGRAMS("Phase 1: Text & Visual Diagrams"),
    PHASE_2_INTERACTIVE_3D_SLIDER("Phase 2: 3D Table & Frame Slider"),
}

/**
 * Combined two-phase tutorial viewer with an interactive toggle bar switching between
 * [WalkthroughTextAndDiagramViewer] (Phase 1) and [Interactive3DTableWithFrameSlider] (Phase 2).
 */
@Composable
fun PhasedInteractiveTutorialViewer(
    layout: TableLayout,
    stages: List<WalkthroughVisualStage>,
    targetBallNumber: Int,
    pocketId: PocketId,
    recommendedTipOffset: TipOffset = TipOffset(),
    recommendedSpeedScale: Int = 5,
    ballSet: BallSetProfile = BallSetProfile.standardPool(),
    modifier: Modifier = Modifier,
    initialPhase: TutorialPresentationPhase = TutorialPresentationPhase.PHASE_1_TEXT_AND_DIAGRAMS,
) {
    var activePhase by remember(layout, targetBallNumber, pocketId) {
        mutableStateOf(initialPhase)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TutorialPresentationPhase.entries.forEach { phase ->
                    val isSelected = activePhase == phase
                    FilterChip(
                        selected = isSelected,
                        onClick = { activePhase = phase },
                        label = {
                            Text(
                                text = phase.label,
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

            when (activePhase) {
                TutorialPresentationPhase.PHASE_1_TEXT_AND_DIAGRAMS -> {
                    WalkthroughTextAndDiagramViewer(
                        layout = layout,
                        stages = stages,
                        targetBallNumber = targetBallNumber,
                        pocketId = pocketId,
                        tipOffset = recommendedTipOffset,
                        speedScale = recommendedSpeedScale,
                        ballSet = ballSet,
                    )
                }

                TutorialPresentationPhase.PHASE_2_INTERACTIVE_3D_SLIDER -> {
                    Interactive3DTableWithFrameSlider(
                        layout = layout,
                        targetBallNumber = targetBallNumber,
                        pocketId = pocketId,
                        initialTipOffset = recommendedTipOffset,
                        initialSpeedScale = recommendedSpeedScale,
                        ballSet = ballSet,
                    )
                }
            }
        }
    }
}

/**
 * Phase 1 tutorial & walkthrough viewer presenting selectable 4-stage explanations paired with
 * an illustrated 2D diagram sampled from [ShotTimelineAnimator.buildTimeline].
 */
@Composable
fun WalkthroughTextAndDiagramViewer(
    layout: TableLayout,
    stages: List<WalkthroughVisualStage>,
    targetBallNumber: Int,
    pocketId: PocketId,
    tipOffset: TipOffset,
    speedScale: Int,
    ballSet: BallSetProfile = BallSetProfile.standardPool(),
    modifier: Modifier = Modifier,
) {
    val effectiveStages = remember(stages) {
        stages.ifEmpty { defaultFallbackStages() }
    }
    var selectedStageIndex by remember(effectiveStages) { mutableIntStateOf(0) }
    val safeStageIndex = selectedStageIndex.coerceIn(0, (effectiveStages.size - 1).coerceAtLeast(0))
    val selectedStage = effectiveStages[safeStageIndex]

    val cueSpeedMps = remember(speedScale) {
        PlayerProfile().speedForScale(speedScale.coerceIn(1, 10))
    }
    val timeline = remember(layout, targetBallNumber, pocketId, cueSpeedMps, tipOffset) {
        ShotTimelineAnimator.buildTimeline(
            layout = layout,
            targetBallNumber = targetBallNumber,
            pocketId = pocketId,
            cueSpeedMps = cueSpeedMps,
            tip = tipOffset,
        )
    }
    val sampledFrame = remember(timeline, selectedStage.frameProgress) {
        timeline?.sampleAtProgress(selectedStage.frameProgress)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            effectiveStages.forEachIndexed { idx, stage ->
                val isSelected = idx == safeStageIndex
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedStageIndex = idx },
                    label = {
                        Text(
                            text = "Stage ${stage.stageNumber}",
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DiagramCueAccent.copy(alpha = 0.22f),
                        selectedLabelColor = DiagramCueAccent,
                    ),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = DiagramPotGold.copy(alpha = 0.18f),
                border = BorderStroke(1.dp, DiagramPotGold.copy(alpha = 0.5f)),
            ) {
                Text(
                    text = selectedStage.calloutBadge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = DiagramPotGold,
                )
            }
            Text(
                text = "Target #$targetBallNumber -> ${pocketId.displayName}  •  ${speedScale}/10",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = selectedStage.stageTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
            text = selectedStage.explanationText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Stage2DVisualDiagramCanvas(
            layout = layout,
            timeline = timeline,
            frame = sampledFrame,
            targetBallNumber = targetBallNumber,
            pocketId = pocketId,
            stage = selectedStage,
            ballSet = ballSet,
        )

        if (sampledFrame != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Stage phase: ${sampledFrame.phase.displayLabel()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = DiagramCueAccent,
                )
                Text(
                    text = "Frame ${formatDecimal(sampledFrame.progressFraction * 100.0, 0)}%  •  ${formatDecimal(sampledFrame.timestampSeconds, 2)} / ${formatDecimal(sampledFrame.totalDurationSeconds, 2)} s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Phase 2 interactive 3D perspective table viewer with orbit drag, camera presets, live tip/speed
 * selection, quick-jump step buttons, and a continuous `0%..100%` frame scrubber slider.
 */
@Composable
fun Interactive3DTableWithFrameSlider(
    layout: TableLayout,
    targetBallNumber: Int,
    pocketId: PocketId,
    initialTipOffset: TipOffset = TipOffset(),
    initialSpeedScale: Int = 5,
    ballSet: BallSetProfile = BallSetProfile.standardPool(),
    modifier: Modifier = Modifier,
) {
    var activeTip by remember(layout, targetBallNumber, pocketId, initialTipOffset) {
        mutableStateOf(initialTipOffset)
    }
    var activeSpeedScale by remember(layout, targetBallNumber, pocketId, initialSpeedScale) {
        mutableIntStateOf(initialSpeedScale.coerceIn(1, 10))
    }
    var frameProgress by remember(layout, targetBallNumber, pocketId) {
        mutableStateOf(0.0f)
    }
    var activePreset by remember(layout, targetBallNumber, pocketId) {
        mutableStateOf<CameraPreset3D?>(CameraPreset3D.CORNER)
    }
    var cameraSpec by remember(layout, targetBallNumber, pocketId) {
        mutableStateOf(Camera3DOrbitSpec.cornerIsometricView())
    }

    val cueSpeedMps = remember(activeSpeedScale) {
        PlayerProfile().speedForScale(activeSpeedScale)
    }
    val timeline = remember(layout, targetBallNumber, pocketId, cueSpeedMps, activeTip) {
        ShotTimelineAnimator.buildTimeline(
            layout = layout,
            targetBallNumber = targetBallNumber,
            pocketId = pocketId,
            cueSpeedMps = cueSpeedMps,
            tip = activeTip,
        )
    }
    val frame = remember(timeline, frameProgress) {
        timeline?.sampleAtProgress(frameProgress.toDouble())
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CameraPreset3D.entries.forEach { preset ->
                val isSelected = activePreset == preset
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        activePreset = preset
                        cameraSpec = when (preset) {
                            CameraPreset3D.BEHIND_CUE -> {
                                val aimDir = timeline?.compensatedAim?.geometricAim?.aimDirection
                                    ?: Vec2(1.0, 0.0)
                                Camera3DOrbitSpec.behindCueView(aimDir)
                            }
                            CameraPreset3D.CORNER -> Camera3DOrbitSpec.cornerIsometricView()
                            CameraPreset3D.SIDE_BROADCAST -> Camera3DOrbitSpec.sideBroadcastView()
                            CameraPreset3D.OVERHEAD -> Camera3DOrbitSpec.overheadTopDownView()
                        }
                    },
                    label = {
                        Text(
                            text = preset.label,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DiagramCueAccent.copy(alpha = 0.22f),
                        selectedLabelColor = DiagramCueAccent,
                    ),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(DiagramBackdropDark)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
        ) {
            Table3DPerspectiveCanvas(
                layout = layout,
                timeline = timeline,
                frame = frame,
                targetBallNumber = targetBallNumber,
                pocketId = pocketId,
                cameraSpec = cameraSpec,
                ballSet = ballSet,
                onOrbitDrag = { dx, dy ->
                    activePreset = null
                    cameraSpec = cameraSpec.copy(
                        yawRadians = cameraSpec.yawRadians + dx * 0.007,
                        pitchRadians = (cameraSpec.pitchRadians + dy * 0.006).coerceIn(0.15, 1.50),
                    )
                },
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color(0xAA0F172A),
            ) {
                Text(
                    text = "Drag to orbit 3D view",
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFCBD5E1),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val totalSecs = timeline?.totalDurationSeconds ?: 0.0
            val currentSecs = frame?.timestampSeconds ?: 0.0
            val pct = (frameProgress * 100f).roundToInt().coerceIn(0, 100)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Timeline Scrubber ($pct%)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Time: ${formatDecimal(currentSecs, 2)} / ${formatDecimal(totalSecs, 2)} s",
                    style = MaterialTheme.typography.labelSmall,
                    color = DiagramCueAccent,
                )
            }

            Slider(
                value = frameProgress,
                onValueChange = { frameProgress = it.coerceIn(0.0f, 1.0f) },
                valueRange = 0.0f..1.0f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = DiagramCueAccent,
                    activeTrackColor = DiagramCueAccent,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val impactProgress = (timeline?.impactProgressFraction?.toFloat() ?: 0.35f).coerceIn(0.0f, 1.0f)
            QuickStepButton(label = "|< Strike (0%)", onClick = { frameProgress = 0.0f })
            QuickStepButton(
                label = "-5% Frame",
                onClick = { frameProgress = (frameProgress - 0.05f).coerceIn(0.0f, 1.0f) },
            )
            QuickStepButton(
                label = "Impact",
                accent = true,
                onClick = { frameProgress = impactProgress },
            )
            QuickStepButton(
                label = "+5% Frame",
                onClick = { frameProgress = (frameProgress + 0.05f).coerceIn(0.0f, 1.0f) },
            )
            QuickStepButton(label = "End (100%) >|", onClick = { frameProgress = 1.0f })
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ) {
            val phaseLabel = frame?.phase?.displayLabel() ?: "Unavailable"
            val totalSecs = frame?.totalDurationSeconds ?: 0.0
            val curSecs = frame?.timestampSeconds ?: 0.0
            val cueSpeed = frame?.cueBallSpeedMps ?: 0.0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = phaseLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = DiagramPotGold,
                )
                Text(
                    text = "Time: ${formatDecimal(curSecs, 2)} / ${formatDecimal(totalSecs, 2)} s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Cue Ball: ${formatDecimal(cueSpeed, 1)} m/s",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = DiagramCueAccent,
                )
            }
        }

        SpinAndSpeedControlsRow(
            activeTip = activeTip,
            activeSpeedScale = activeSpeedScale,
            cueSpeedMps = cueSpeedMps,
            onUpdateTip = { activeTip = it },
            onUpdateSpeedScale = { activeSpeedScale = it },
        )
    }
}

@Composable
private fun SpinAndSpeedControlsRow(
    activeTip: TipOffset,
    activeSpeedScale: Int,
    cueSpeedMps: Double,
    onUpdateTip: (TipOffset) -> Unit,
    onUpdateSpeedScale: (Int) -> Unit,
) {
    val isStun = abs(activeTip.clampedVerticalRatio) <= 0.12
    val isFollow = activeTip.clampedVerticalRatio > 0.12
    val isDraw = activeTip.clampedVerticalRatio < -0.12
    val isLeftEnglish = activeTip.clampedSideRatio < -0.08
    val isRightEnglish = activeTip.clampedSideRatio > 0.08

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpinChip3D(
                label = "Stun",
                selected = isStun,
                onClick = { onUpdateTip(activeTip.copy(verticalRatio = 0.0)) },
            )
            SpinChip3D(
                label = "Follow",
                selected = isFollow,
                onClick = { onUpdateTip(activeTip.copy(verticalRatio = 0.35)) },
            )
            SpinChip3D(
                label = "Draw",
                selected = isDraw,
                onClick = { onUpdateTip(activeTip.copy(verticalRatio = -0.38)) },
            )
            SpinChip3D(
                label = "Left English",
                selected = isLeftEnglish,
                onClick = {
                    onUpdateTip(activeTip.copy(sideRatio = if (isLeftEnglish) 0.0 else -0.30))
                },
            )
            SpinChip3D(
                label = "Right English",
                selected = isRightEnglish,
                onClick = {
                    onUpdateTip(activeTip.copy(sideRatio = if (isRightEnglish) 0.0 else 0.30))
                },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Speed:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(3, 5, 8).forEach { scale ->
                val isSelected = activeSpeedScale == scale
                FilterChip(
                    selected = isSelected,
                    onClick = { onUpdateSpeedScale(scale) },
                    label = {
                        Text(
                            text = "$scale/10",
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = DiagramPotGold.copy(alpha = 0.22f),
                        selectedLabelColor = DiagramPotGold,
                    ),
                )
            }
            Text(
                text = "(Active: $activeSpeedScale/10 = ${formatDecimal(cueSpeedMps, 2)} m/s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickStepButton(
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(
            width = 1.dp,
            color = if (accent) DiagramCueAccent else MaterialTheme.colorScheme.surfaceVariant,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (accent) DiagramCueAccent else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpinChip3D(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun Stage2DVisualDiagramCanvas(
    layout: TableLayout,
    timeline: ShotAnimationTimeline?,
    frame: ShotTimelineFrame?,
    targetBallNumber: Int,
    pocketId: PocketId,
    stage: WalkthroughVisualStage,
    ballSet: BallSetProfile,
) {
    val table = layout.table
    val aspectRatio = (table.lengthMeters / table.widthMeters).toFloat()
        .takeIf { it.isFinite() && it > 0f } ?: 2f
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        val railPad = min(size.width, size.height) * 0.075f
        val availW = (size.width - 2f * railPad).coerceAtLeast(1f)
        val availH = (size.height - 2f * railPad).coerceAtLeast(1f)
        val pxPerMeter = min(
            availW / table.lengthMeters.toFloat(),
            availH / table.widthMeters.toFloat(),
        ).coerceAtLeast(1f)
        val playW = table.lengthMeters.toFloat() * pxPerMeter
        val playH = table.widthMeters.toFloat() * pxPerMeter
        val playLeft = (size.width - playW) * 0.5f
        val playTop = (size.height - playH) * 0.5f

        fun toOffset(pos: Vec2): Offset = Offset(
            x = playLeft + (pos.x * pxPerMeter).toFloat(),
            y = playTop + (pos.y * pxPerMeter).toFloat(),
        )

        drawRoundRect(
            color = DiagramRailWoodOuter,
            topLeft = Offset.Zero,
            size = size,
            cornerRadius = CornerRadius(railPad * 0.7f, railPad * 0.7f),
        )
        val inset = railPad * 0.2f
        drawRoundRect(
            color = DiagramRailWoodInner,
            topLeft = Offset(inset, inset),
            size = Size(size.width - 2f * inset, size.height - 2f * inset),
            cornerRadius = CornerRadius(railPad * 0.5f, railPad * 0.5f),
        )
        val cushionMargin = railPad * 0.34f
        drawRoundRect(
            color = DiagramCushionCloth,
            topLeft = Offset(playLeft - cushionMargin, playTop - cushionMargin),
            size = Size(playW + 2f * cushionMargin, playH + 2f * cushionMargin),
            cornerRadius = CornerRadius(railPad * 0.2f, railPad * 0.2f),
        )
        drawRect(
            color = DiagramFeltBed,
            topLeft = Offset(playLeft, playTop),
            size = Size(playW, playH),
        )

        val headStringX = playLeft + (0.25 * table.lengthMeters * pxPerMeter).toFloat()
        drawLine(
            color = DiagramMarkingLine,
            start = Offset(headStringX, playTop),
            end = Offset(headStringX, playTop + playH),
            strokeWidth = max(1f, pxPerMeter * 0.003f),
        )
        val spotR = max(2f, pxPerMeter * 0.007f)
        drawCircle(color = DiagramMarkingLine, radius = spotR, center = toOffset(table.headSpot()))
        drawCircle(color = DiagramMarkingLine, radius = spotR, center = toOffset(table.footSpot()))

        for (pocket in table.pockets) {
            val pCenter = toOffset(pocket.center)
            val pRad = (pocket.acceptanceRadiusMeters * pxPerMeter).toFloat()
            val isTarget = pocket.id == pocketId
            drawCircle(
                color = if (isTarget) DiagramPotGold.copy(alpha = 0.45f) else DiagramPocketRim,
                radius = pRad * 1.12f,
                center = pCenter,
            )
            drawCircle(color = DiagramPocketHole, radius = pRad, center = pCenter)
            if (isTarget) {
                drawCircle(
                    color = DiagramPotGold,
                    radius = pRad * 1.18f,
                    center = pCenter,
                    style = Stroke(width = max(1.8f, pxPerMeter * 0.005f)),
                )
            }
        }

        val ballRadiusPx = (table.ballRadiusMeters * pxPerMeter).toFloat()
        if (timeline != null && frame != null) {
            val ghostOff = toOffset(timeline.compensatedAim.compensatedGhostBallCenter)
            val dash = max(5f, pxPerMeter * 0.025f)
            val gap = max(4f, pxPerMeter * 0.018f)
            val guideDash = PathEffect.dashPathEffect(floatArrayOf(dash, gap), 0f)

            drawLine(
                color = DiagramGhostCyan.copy(alpha = 0.45f),
                start = toOffset(timeline.compensatedAim.geometricAim.cueBallPos),
                end = ghostOff,
                strokeWidth = max(1.4f, pxPerMeter * 0.0045f),
                pathEffect = guideDash,
            )
            drawLine(
                color = DiagramPotGold.copy(alpha = 0.45f),
                start = toOffset(timeline.targetBall.position),
                end = toOffset(timeline.pocket.center),
                strokeWidth = max(1.4f, pxPerMeter * 0.0045f),
                pathEffect = guideDash,
            )
            drawCircle(color = DiagramGhostCyan.copy(alpha = 0.14f), radius = ballRadiusPx, center = ghostOff)
            drawCircle(
                color = DiagramGhostCyan,
                radius = ballRadiusPx,
                center = ghostOff,
                style = Stroke(width = max(1.4f, pxPerMeter * 0.0045f), pathEffect = guideDash),
            )

            draw2DPolyline(
                points = frame.traveledCuePathPoints,
                color = DiagramCueAccent,
                strokeWidth = max(2.4f, pxPerMeter * 0.0075f),
                toOffset = ::toOffset,
            )
            draw2DPolyline(
                points = frame.traveledObjectPathPoints,
                color = DiagramPotGold,
                strokeWidth = max(2.4f, pxPerMeter * 0.0075f),
                toOffset = ::toOffset,
            )

            if (stage.stageNumber == 3 || frame.phase == ShotAnimationPhase.CONTACT_AND_THROW) {
                drawCircle(
                    color = DiagramImpactCoral.copy(alpha = 0.30f),
                    radius = ballRadiusPx * 1.45f,
                    center = ghostOff,
                )
                drawCircle(
                    color = DiagramImpactCoral,
                    radius = ballRadiusPx * 1.45f,
                    center = ghostOff,
                    style = Stroke(width = max(1.6f, pxPerMeter * 0.005f)),
                )
            }

            if (frame.cueStickVisible) {
                drawTaperedCueStick(
                    buttScreen = toOffset(frame.cueStickButtMeters),
                    tipScreen = toOffset(frame.cueStickTipMeters),
                    buttHalfWidthPx = max(4.5f, ballRadiusPx * 0.70f),
                    tipHalfWidthPx = max(2.4f, ballRadiusPx * 0.36f),
                )
            }
        }

        val activeBalls = frame?.balls ?: layout.balls
        val fontSizeSp = with(density) { (ballRadiusPx * 0.80f).coerceAtLeast(6f).toSp() }
        for (ball in activeBalls) {
            drawStyledPoolBall(
                ball = ball,
                ballColor = ballBaseColor(ball.number, ballSet),
                center = toOffset(ball.position),
                radiusPx = (ball.radiusMeters * pxPerMeter).toFloat(),
                isTarget = ball.number == targetBallNumber,
                textMeasurer = textMeasurer,
                fontSizeSp = fontSizeSp,
                with3DShading = false,
            )
        }
    }
}

@Composable
private fun Table3DPerspectiveCanvas(
    layout: TableLayout,
    timeline: ShotAnimationTimeline?,
    frame: ShotTimelineFrame?,
    targetBallNumber: Int,
    pocketId: PocketId,
    cameraSpec: Camera3DOrbitSpec,
    ballSet: BallSetProfile,
    onOrbitDrag: (dx: Float, dy: Float) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val latestOnOrbitDrag by rememberUpdatedState(onOrbitDrag)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(265.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    latestOnOrbitDrag(dragAmount.x, dragAmount.y)
                }
            },
    ) {
        val table = layout.table
        val r = table.ballRadiusMeters
        val l = table.lengthMeters
        val w = table.widthMeters

        fun proj(x: Double, y: Double, z: Double, radiusM: Double = r): Projected3DPoint =
            ShotTimelineAnimator.projectTablePoint3D(
                xMeters = x,
                yMeters = y,
                zMeters = z,
                table = table,
                camera = cameraSpec,
                sphereRadiusMeters = radiusM,
            )

        val outerRail = listOf(
            proj(-OUTER_RAIL_MARGIN_METERS, -OUTER_RAIL_MARGIN_METERS, 0.038, 0.0),
            proj(l + OUTER_RAIL_MARGIN_METERS, -OUTER_RAIL_MARGIN_METERS, 0.038, 0.0),
            proj(l + OUTER_RAIL_MARGIN_METERS, w + OUTER_RAIL_MARGIN_METERS, 0.038, 0.0),
            proj(-OUTER_RAIL_MARGIN_METERS, w + OUTER_RAIL_MARGIN_METERS, 0.038, 0.0),
        )
        val outerSkirt = listOf(
            proj(-OUTER_RAIL_MARGIN_METERS, -OUTER_RAIL_MARGIN_METERS, -0.025, 0.0),
            proj(l + OUTER_RAIL_MARGIN_METERS, -OUTER_RAIL_MARGIN_METERS, -0.025, 0.0),
            proj(l + OUTER_RAIL_MARGIN_METERS, w + OUTER_RAIL_MARGIN_METERS, -0.025, 0.0),
            proj(-OUTER_RAIL_MARGIN_METERS, w + OUTER_RAIL_MARGIN_METERS, -0.025, 0.0),
        )

        val boundsPoints = outerRail + outerSkirt
        val minX = boundsPoints.minOf { it.normalizedX }
        val maxX = boundsPoints.maxOf { it.normalizedX }
        val minY = boundsPoints.minOf { it.normalizedY }
        val maxY = boundsPoints.maxOf { it.normalizedY }
        val spanX = (maxX - minX).coerceAtLeast(0.5).toFloat()
        val spanY = (maxY - minY).coerceAtLeast(0.5).toFloat()
        val scalePx = min(size.width * 0.88f / spanX, size.height * 0.86f / spanY)
        val centerNormX = ((minX + maxX) * 0.5).toFloat()
        val centerNormY = ((minY + maxY) * 0.5).toFloat()

        fun toScreen(pt: Projected3DPoint): Offset = Offset(
            x = size.width * 0.5f + (pt.normalizedX.toFloat() - centerNormX) * scalePx,
            y = size.height * 0.5f + (pt.normalizedY.toFloat() - centerNormY) * scalePx,
        )

        draw3DTableStructure(
            table = table,
            cameraSpec = cameraSpec,
            targetPocketId = pocketId,
            outerRail = outerRail,
            outerSkirt = outerSkirt,
            scalePx = scalePx,
            proj = ::proj,
            toScreen = ::toScreen,
        )

        if (timeline != null && frame != null) {
            draw3DShotPathsAndGhostRing(
                timeline = timeline,
                frame = frame,
                ballRadiusMeters = r,
                proj = { x, y, z -> toScreen(proj(x, y, z, 0.0)) },
            )
        }

        val activeBalls = frame?.balls ?: layout.balls
        val sortedBalls = activeBalls.map { ball ->
            Triple(
                ball,
                proj(ball.position.x, ball.position.y, ball.radiusMeters, ball.radiusMeters),
                proj(ball.position.x, ball.position.y, 0.0, ball.radiusMeters),
            )
        }.sortedByDescending { (_, centerProj, _) -> centerProj.depthMeters }

        for ((ball, centerProj, shadowProj) in sortedBalls) {
            val screenCenter = toScreen(centerProj)
            val shadowCenter = toScreen(shadowProj)
            val radiusPx = (centerProj.apparentRadiusNormalized.toFloat() * scalePx).coerceAtLeast(4.5f)
            val shadowRadiusPx = (shadowProj.apparentRadiusNormalized.toFloat() * scalePx).coerceAtLeast(4.0f)

            drawOval(
                color = Color(0x66000000),
                topLeft = Offset(
                    x = shadowCenter.x - shadowRadiusPx * 1.08f,
                    y = shadowCenter.y - shadowRadiusPx * 0.72f,
                ),
                size = Size(shadowRadiusPx * 2.16f, shadowRadiusPx * 1.44f),
            )

            drawStyledPoolBall(
                ball = ball,
                ballColor = ballBaseColor(ball.number, ballSet),
                center = screenCenter,
                radiusPx = radiusPx,
                isTarget = ball.number == targetBallNumber,
                textMeasurer = textMeasurer,
                fontSizeSp = with(density) { (radiusPx * 0.78f).coerceAtLeast(5f).toSp() },
                with3DShading = true,
            )
        }

        if (frame != null && frame.cueStickVisible) {
            val tipMeters = frame.cueStickTipMeters
            val buttMeters = frame.cueStickButtMeters
            val tipProj = proj(tipMeters.x, tipMeters.y, r, 0.0065)
            val buttProj = proj(buttMeters.x, buttMeters.y, r + 0.08, 0.0155)
            drawLine(
                color = Color(0x4D000000),
                start = toScreen(proj(buttMeters.x, buttMeters.y, 0.0, 0.0)),
                end = toScreen(proj(tipMeters.x, tipMeters.y, 0.0, 0.0)),
                strokeWidth = 5f,
                cap = StrokeCap.Round,
            )
            drawTaperedCueStick(
                buttScreen = toScreen(buttProj),
                tipScreen = toScreen(tipProj),
                buttHalfWidthPx = (buttProj.apparentRadiusNormalized.toFloat() * scalePx).coerceAtLeast(3.5f),
                tipHalfWidthPx = (tipProj.apparentRadiusNormalized.toFloat() * scalePx).coerceAtLeast(1.8f),
            )
        }
    }
}

private fun DrawScope.draw3DTableStructure(
    table: TableSpec,
    cameraSpec: Camera3DOrbitSpec,
    targetPocketId: PocketId,
    outerRail: List<Projected3DPoint>,
    outerSkirt: List<Projected3DPoint>,
    scalePx: Float,
    proj: (Double, Double, Double, Double) -> Projected3DPoint,
    toScreen: (Projected3DPoint) -> Offset,
) {
    val l = table.lengthMeters
    val w = table.widthMeters
    val innerCushionTop = listOf(
        proj(0.0, 0.0, 0.036, 0.0),
        proj(l, 0.0, 0.036, 0.0),
        proj(l, w, 0.036, 0.0),
        proj(0.0, w, 0.036, 0.0),
    )
    val clothBed = listOf(
        proj(0.0, 0.0, 0.0, 0.0),
        proj(l, 0.0, 0.0, 0.0),
        proj(l, w, 0.0, 0.0),
        proj(0.0, w, 0.0, 0.0),
    )

    val pitch = cameraSpec.pitchRadians.coerceIn(0.12, 1.53)
    val yaw = cameraSpec.yawRadians
    val dist = cameraSpec.distanceMeters.coerceAtLeast(0.25)
    val camCenterX = -dist * cos(pitch) * cos(yaw)
    val camCenterY = -dist * cos(pitch) * sin(yaw)

    val outerScreen = outerRail.map(toScreen)
    val skirtScreen = outerSkirt.map(toScreen)
    val cushionTopScreen = innerCushionTop.map(toScreen)
    val clothScreen = clothBed.map(toScreen)

    drawFilledQuad(points = skirtScreen, fillColor = Color(0x66000000))
    val skirtVisibility = listOf(
        camCenterY < -(w * 0.5 + OUTER_RAIL_MARGIN_METERS),
        camCenterX > (l * 0.5 + OUTER_RAIL_MARGIN_METERS),
        camCenterY > (w * 0.5 + OUTER_RAIL_MARGIN_METERS),
        camCenterX < -(l * 0.5 + OUTER_RAIL_MARGIN_METERS),
    )
    for (edgeIdx in 0..3) {
        if (!skirtVisibility[edgeIdx]) continue
        val nextIdx = (edgeIdx + 1) % 4
        drawFilledQuad(
            points = listOf(
                outerScreen[edgeIdx],
                outerScreen[nextIdx],
                skirtScreen[nextIdx],
                skirtScreen[edgeIdx],
            ),
            fillColor = DiagramRailSkirt,
            strokeColor = Color(0x44000000),
        )
    }

    // Cloth felt bed, head string, and spots (z = 0.0 m)
    drawFilledQuad(points = clothScreen, fillColor = DiagramFeltBed)
    drawLine(
        color = DiagramMarkingLine,
        start = toScreen(proj(l * 0.25, 0.0, 0.0, 0.0)),
        end = toScreen(proj(l * 0.25, w, 0.0, 0.0)),
        strokeWidth = 1.5f,
    )
    val headSpotProj = proj(l * 0.25, w * 0.5, 0.0, 0.008)
    val footSpotProj = proj(l * 0.75, w * 0.5, 0.0, 0.008)
    drawCircle(
        color = DiagramMarkingLine,
        radius = (headSpotProj.apparentRadiusNormalized.toFloat() * scalePx).coerceAtLeast(2f),
        center = toScreen(headSpotProj),
    )
    drawCircle(
        color = DiagramMarkingLine,
        radius = (footSpotProj.apparentRadiusNormalized.toFloat() * scalePx).coerceAtLeast(2f),
        center = toScreen(footSpotProj),
    )

    // Six 3D perspective pocket mouths on the cloth bed
    for (pocket in table.pockets) {
        val isTarget = pocket.id == targetPocketId
        val pocketPath = Path().apply {
            val steps = 14
            for (i in 0 until steps) {
                val angle = (2.0 * PI * i) / steps
                val px = (pocket.center.x + pocket.acceptanceRadiusMeters * cos(angle))
                    .coerceIn(-OUTER_RAIL_MARGIN_METERS * 0.45, l + OUTER_RAIL_MARGIN_METERS * 0.45)
                val py = (pocket.center.y + pocket.acceptanceRadiusMeters * sin(angle))
                    .coerceIn(-OUTER_RAIL_MARGIN_METERS * 0.45, w + OUTER_RAIL_MARGIN_METERS * 0.45)
                val pt = toScreen(proj(px, py, 0.0, 0.0))
                if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
            }
            close()
        }
        drawPath(path = pocketPath, color = DiagramPocketHole)
        drawPath(
            path = pocketPath,
            color = if (isTarget) DiagramPotGold else DiagramPocketRim,
            style = Stroke(width = if (isTarget) 2.4f else 1.3f),
        )
    }

    // Visible inner cushion walls (z = 0.036 m -> 0.0 m)
    val cushionVisibility = listOf(
        camCenterY > -w * 0.5,
        camCenterX < l * 0.5,
        camCenterY < w * 0.5,
        camCenterX > -l * 0.5,
    )
    for (edgeIdx in 0..3) {
        if (!cushionVisibility[edgeIdx]) continue
        val nextIdx = (edgeIdx + 1) % 4
        drawFilledQuad(
            points = listOf(
                cushionTopScreen[edgeIdx],
                cushionTopScreen[nextIdx],
                clothScreen[nextIdx],
                clothScreen[edgeIdx],
            ),
            fillColor = DiagramCushionCloth,
            strokeColor = Color(0x44000000),
        )
    }

    // Wooden rail aprons (outer z = 0.038 m -> inner cushion nose z = 0.036 m)
    val railOrder = (0..3).sortedByDescending { edgeIdx ->
        outerRail[edgeIdx].depthMeters + outerRail[(edgeIdx + 1) % 4].depthMeters
    }
    for (edgeIdx in railOrder) {
        val nextIdx = (edgeIdx + 1) % 4
        drawFilledQuad(
            points = listOf(
                outerScreen[edgeIdx],
                outerScreen[nextIdx],
                cushionTopScreen[nextIdx],
                cushionTopScreen[edgeIdx],
            ),
            fillColor = if (edgeIdx % 2 == 0) DiagramRailWoodOuter else DiagramRailWoodInner,
            strokeColor = DiagramRailWoodHighlight.copy(alpha = 0.55f),
        )
    }

    val sightOffset = OUTER_RAIL_MARGIN_METERS * 0.5
    for (i in listOf(1, 2, 3, 5, 6, 7)) {
        val sx = l * (i / 8.0)
        drawCircle(color = DiagramDiamondSight, radius = 2.0f, center = toScreen(proj(sx, -sightOffset, 0.038, 0.0)))
        drawCircle(color = DiagramDiamondSight, radius = 2.0f, center = toScreen(proj(sx, w + sightOffset, 0.038, 0.0)))
    }
    for (j in 1..3) {
        val sy = w * (j / 4.0)
        drawCircle(color = DiagramDiamondSight, radius = 2.0f, center = toScreen(proj(-sightOffset, sy, 0.038, 0.0)))
        drawCircle(color = DiagramDiamondSight, radius = 2.0f, center = toScreen(proj(l + sightOffset, sy, 0.038, 0.0)))
    }
}

private fun DrawScope.draw3DShotPathsAndGhostRing(
    timeline: ShotAnimationTimeline,
    frame: ShotTimelineFrame,
    ballRadiusMeters: Double,
    proj: (Double, Double, Double) -> Offset,
) {
    val ghostCenter = timeline.compensatedAim.compensatedGhostBallCenter
    val aimPathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)

    drawLine(
        color = DiagramGhostCyan.copy(alpha = 0.36f),
        start = proj(timeline.compensatedAim.geometricAim.cueBallPos.x, timeline.compensatedAim.geometricAim.cueBallPos.y, ballRadiusMeters * 0.15),
        end = proj(ghostCenter.x, ghostCenter.y, ballRadiusMeters * 0.15),
        strokeWidth = 1.6f,
        pathEffect = aimPathEffect,
    )
    drawLine(
        color = DiagramPotGold.copy(alpha = 0.36f),
        start = proj(timeline.targetBall.position.x, timeline.targetBall.position.y, ballRadiusMeters * 0.15),
        end = proj(timeline.pocket.center.x, timeline.pocket.center.y, ballRadiusMeters * 0.15),
        strokeWidth = 1.6f,
        pathEffect = aimPathEffect,
    )

    draw3DHorizontalRing(
        centerMeters = ghostCenter,
        radiusMeters = ballRadiusMeters,
        zMeters = 0.0,
        strokeColor = Color(0x33000000),
        fillColor = null,
        strokeWidth = 1.4f,
        project = proj,
    )
    draw3DHorizontalRing(
        centerMeters = ghostCenter,
        radiusMeters = ballRadiusMeters,
        zMeters = ballRadiusMeters,
        strokeColor = DiagramGhostCyan,
        fillColor = DiagramGhostCyan.copy(alpha = 0.14f),
        strokeWidth = 2.0f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f),
        project = proj,
    )

    draw3DPathRibbon(
        points = frame.traveledCuePathPoints,
        zMeters = ballRadiusMeters * 0.25,
        ribbonColor = DiagramCueAccent,
        project = proj,
    )
    draw3DPathRibbon(
        points = frame.traveledObjectPathPoints,
        zMeters = ballRadiusMeters * 0.25,
        ribbonColor = DiagramPotGold,
        project = proj,
    )
}

private fun DrawScope.drawFilledQuad(
    points: List<Offset>,
    fillColor: Color,
    strokeColor: Color? = null,
) {
    if (points.size < 3) return
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) {
            lineTo(points[i].x, points[i].y)
        }
        close()
    }
    drawPath(path = path, color = fillColor)
    if (strokeColor != null) {
        drawPath(
            path = path,
            color = strokeColor,
            style = Stroke(width = 1.2f, join = StrokeJoin.Round),
        )
    }
}

private fun DrawScope.draw3DHorizontalRing(
    centerMeters: Vec2,
    radiusMeters: Double,
    zMeters: Double,
    strokeColor: Color,
    fillColor: Color? = null,
    strokeWidth: Float,
    pathEffect: PathEffect? = null,
    project: (Double, Double, Double) -> Offset,
) {
    val segments = 24
    val ringPath = Path().apply {
        for (i in 0 until segments) {
            val angle = (2.0 * PI * i) / segments
            val pt = project(
                centerMeters.x + radiusMeters * cos(angle),
                centerMeters.y + radiusMeters * sin(angle),
                zMeters,
            )
            if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
        }
        close()
    }
    if (fillColor != null) {
        drawPath(path = ringPath, color = fillColor)
    }
    drawPath(
        path = ringPath,
        color = strokeColor,
        style = Stroke(width = strokeWidth, pathEffect = pathEffect),
    )
}

private fun DrawScope.draw3DPathRibbon(
    points: List<Vec2>,
    zMeters: Double,
    ribbonColor: Color,
    project: (Double, Double, Double) -> Offset,
) {
    if (points.size < 2) return
    val shadowPath = Path().apply {
        val first = project(points[0].x, points[0].y, 0.0)
        moveTo(first.x, first.y)
        for (i in 1 until points.size) {
            val p = project(points[i].x, points[i].y, 0.0)
            lineTo(p.x, p.y)
        }
    }
    drawPath(
        path = shadowPath,
        color = Color(0x44000000),
        style = Stroke(width = 4.2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    val ribbonPath = Path().apply {
        val first = project(points[0].x, points[0].y, zMeters)
        moveTo(first.x, first.y)
        for (i in 1 until points.size) {
            val p = project(points[i].x, points[i].y, zMeters)
            lineTo(p.x, p.y)
        }
    }
    drawPath(
        path = ribbonPath,
        color = ribbonColor.copy(alpha = 0.28f),
        style = Stroke(width = 6.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = ribbonPath,
        color = ribbonColor,
        style = Stroke(width = 2.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawStyledPoolBall(
    ball: Ball,
    ballColor: Color,
    center: Offset,
    radiusPx: Float,
    isTarget: Boolean,
    textMeasurer: TextMeasurer,
    fontSizeSp: TextUnit,
    with3DShading: Boolean,
) {
    if (isTarget) {
        drawCircle(color = DiagramPotGold.copy(alpha = 0.30f), radius = radiusPx * 1.40f, center = center)
        drawCircle(
            color = DiagramPotGold,
            radius = radiusPx * 1.34f,
            center = center,
            style = Stroke(width = max(1.4f, radiusPx * 0.14f)),
        )
    }

    if (!with3DShading) {
        drawCircle(
            color = Color(0x55000000),
            radius = radiusPx,
            center = center + Offset(radiusPx * 0.12f, radiusPx * 0.14f),
        )
    }

    val whiteBase = Color(0xFFF8FAFC)
    if (ball.isCueBall) {
        drawCircle(color = ballColor, radius = radiusPx, center = center)
        drawCircle(
            color = Color(0xFFDC2626),
            radius = max(1.2f, radiusPx * 0.17f),
            center = center + Offset(radiusPx * 0.20f, -radiusPx * 0.17f),
        )
    } else if (ball.isStripe) {
        drawCircle(color = whiteBase, radius = radiusPx, center = center)
        val circleClip = Path().apply { addOval(Rect(center = center, radius = radiusPx)) }
        clipPath(circleClip) {
            val bandHalf = radiusPx * 0.56f
            drawRect(
                color = ballColor,
                topLeft = Offset(center.x - radiusPx, center.y - bandHalf),
                size = Size(radiusPx * 2f, bandHalf * 2f),
            )
        }
    } else {
        drawCircle(color = ballColor, radius = radiusPx, center = center)
    }

    if (with3DShading) {
        val sphereClip = Path().apply { addOval(Rect(center = center, radius = radiusPx)) }
        clipPath(sphereClip) {
            drawCircle(
                color = Color(0x2E000000),
                radius = radiusPx * 1.05f,
                center = center + Offset(radiusPx * 0.28f, radiusPx * 0.30f),
            )
        }
    }

    if (!ball.isCueBall) {
        drawCircle(color = whiteBase, radius = radiusPx * 0.48f, center = center)
        if (!with3DShading || radiusPx >= 6.5f) {
            val measured = textMeasurer.measure(
                text = ball.number.toString(),
                style = TextStyle(
                    color = Color(0xFF0F172A),
                    fontSize = fontSizeSp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = center.x - measured.size.width * 0.5f,
                    y = center.y - measured.size.height * 0.5f,
                ),
            )
        }
    }

    if (with3DShading) {
        drawCircle(
            color = Color(0xCCFFFFFF),
            radius = max(1.4f, radiusPx * 0.22f),
            center = center + Offset(-radiusPx * 0.34f, -radiusPx * 0.36f),
        )
    }

    drawCircle(
        color = Color(0xAA0F172A),
        radius = radiusPx,
        center = center,
        style = Stroke(width = max(0.8f, radiusPx * 0.08f)),
    )
}

private fun DrawScope.drawTaperedCueStick(
    buttScreen: Offset,
    tipScreen: Offset,
    buttHalfWidthPx: Float,
    tipHalfWidthPx: Float,
) {
    val dx = tipScreen.x - buttScreen.x
    val dy = tipScreen.y - buttScreen.y
    val len = hypot(dx, dy)
    if (len < 2f) return
    val perp = Offset(-dy / len, dx / len)

    fun lerpOffset(t: Float): Offset = Offset(buttScreen.x + dx * t, buttScreen.y + dy * t)
    fun halfWidthAt(t: Float): Float = buttHalfWidthPx + (tipHalfWidthPx - buttHalfWidthPx) * t

    val segments = listOf(
        Triple(0.0f, 0.42f, Color(0xFF3E1F13)),
        Triple(0.42f, 0.965f, Color(0xFFE7CFA8)),
        Triple(0.965f, 0.988f, Color(0xFFF8FAFC)),
        Triple(0.988f, 1.0f, Color(0xFF0284C7)),
    )
    for ((t0, t1, color) in segments) {
        val p0 = lerpOffset(t0)
        val p1 = lerpOffset(t1)
        val w0 = halfWidthAt(t0)
        val w1 = halfWidthAt(t1)
        val quad = Path().apply {
            moveTo(p0.x + perp.x * w0, p0.y + perp.y * w0)
            lineTo(p1.x + perp.x * w1, p1.y + perp.y * w1)
            lineTo(p1.x - perp.x * w1, p1.y - perp.y * w1)
            lineTo(p0.x - perp.x * w0, p0.y - perp.y * w0)
            close()
        }
        drawPath(path = quad, color = color)
    }
}

private fun DrawScope.draw2DPolyline(
    points: List<Vec2>,
    color: Color,
    strokeWidth: Float,
    toOffset: (Vec2) -> Offset,
) {
    if (points.size < 2) return
    val path = Path().apply {
        val first = toOffset(points.first())
        moveTo(first.x, first.y)
        for (idx in 1 until points.size) {
            val pt = toOffset(points[idx])
            lineTo(pt.x, pt.y)
        }
    }
    drawPath(
        path = path,
        color = color.copy(alpha = 0.25f),
        style = Stroke(width = strokeWidth * 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun ShotAnimationPhase.displayLabel(): String = when (this) {
    ShotAnimationPhase.CUE_ADDRESS_AND_STRIKE -> "Address & Cue Strike"
    ShotAnimationPhase.CUE_BALL_APPROACH -> "Cue Ball Approach"
    ShotAnimationPhase.CONTACT_AND_THROW -> "Impact & Throw"
    ShotAnimationPhase.POST_IMPACT_ROLL -> "Post-Impact Roll"
    ShotAnimationPhase.BALLS_AT_REST -> "Balls at Rest"
}

private fun defaultFallbackStages(): List<WalkthroughVisualStage> = listOf(
    WalkthroughVisualStage(
        stageNumber = 1,
        stageTitle = "Stage 1: Table Setup & Assessment",
        explanationText = "Inspect ball positions, target pocket lane, and cue-ball clearance.",
        calloutBadge = "Table Assessment",
        frameProgress = 0.0,
    ),
    WalkthroughVisualStage(
        stageNumber = 2,
        stageTitle = "Stage 2: Aim Line & Cue Strike",
        explanationText = "Align down the ghost-ball line and deliver a smooth cue stroke.",
        calloutBadge = "Aim & Strike",
        frameProgress = 0.20,
    ),
    WalkthroughVisualStage(
        stageNumber = 3,
        stageTitle = "Stage 3: Ghost-Ball Impact & Throw",
        explanationText = "Contact the object ball at the ghost-ball position and initiate post-impact path.",
        calloutBadge = "Impact & Throw",
        frameProgress = 0.45,
    ),
    WalkthroughVisualStage(
        stageNumber = 4,
        stageTitle = "Stage 4: Cushion Rebound & Next-Ball Shape",
        explanationText = "Track the cue ball to rest and check next-ball angle.",
        calloutBadge = "Final Shape",
        frameProgress = 1.0,
    ),
)

private fun formatDecimal(value: Double, decimals: Int): String {
    if (!value.isFinite()) return "0"
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
