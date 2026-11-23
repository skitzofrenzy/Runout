package com.runout.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.runout.engine.math.Vec2
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.PlanningReadinessResult
import com.runout.engine.planner.RunoutPlan
import com.runout.engine.planner.ShotRecommendation
import kotlin.math.roundToInt

/**
 * Computes the post-shot table layout visible when stepping to [focusedStepIndex] within [plan],
 * removing previously potted target balls and updating cue/scattered object ball positions
 * without mutating the user's starting [baseLayout].
 */
fun resolveDisplayedLayoutForStep(
    baseLayout: TableLayout,
    plan: RunoutPlan?,
    focusedStepIndex: Int,
): TableLayout {
    if (plan == null || plan.steps.isEmpty() || focusedStepIndex <= 0) return baseLayout
    val clampedStepIdx = focusedStepIndex.coerceIn(0, plan.steps.lastIndex)
    val priorStep = plan.steps[clampedStepIdx - 1]
    priorStep.resultingLayout?.let { return it }

    // Fallback reconstruction when a plan step has no stored simulation snapshot
    val pottedNumbers = plan.steps.take(clampedStepIdx).map { it.targetBall.number }.toSet()
    val updatedBalls = baseLayout.balls
        .filter { it.number !in pottedNumbers }
        .map { ball ->
            if (ball.number == 0) ball.copy(position = priorStep.uncertainty.meanLandingPosition) else ball
        }
    return baseLayout.copy(balls = updatedBalls)
}

/**
 * Dedicated edge-to-edge landscape planner workspace showing the full pool table alongside
 * floating Prev/Next step playback controls and a right-hand quick action rail.
 */
@Composable
fun FullscreenTableScreen(
    displayMode: AppDisplayMode,
    onDisplayModeChange: (AppDisplayMode) -> Unit,
    displayedLayout: TableLayout,
    rulesState: GameRulesState,
    ballSetProfile: BallSetProfile,
    planningReadiness: PlanningReadinessResult,
    activePlan: RunoutPlan?,
    selectedShot: ShotRecommendation?,
    focusedStepIndex: Int,
    showAllStepsOverlay: Boolean,
    isPlanning: Boolean,
    isDraggingBall: Boolean,
    visibleSpins: Set<CueBallSpinPreset>,
    showHeatZone: Boolean,
    showLandingCloud: Boolean,
    playablePockets: List<PocketId>,
    customPocketOverride: PocketId?,
    onSelectPocketOverride: (PocketId?) -> Unit,
    onMoveBall: (ballNumber: Int, newPosition: Vec2) -> Unit,
    onDragStateChanged: (Boolean) -> Unit,
    onPrevStep: () -> Unit,
    onNextStep: () -> Unit,
    onToggleAllStepsOverlay: () -> Unit,
    onApplyCurrentStepToTable: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenGameSetupSheet: () -> Unit,
    onOpenEditBallsSheet: () -> Unit,
    onOpenQuickSpinSheet: () -> Unit,
    onOpenShotOptionsSheet: () -> Unit,
    onOpenPhotoScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeStep = activePlan?.steps?.getOrNull(
        focusedStepIndex.coerceIn(0, (activePlan.steps.size - 1).coerceAtLeast(0)),
    )
    val activeTargetPocketId = activeStep?.pocketId ?: selectedShot?.pocket?.id

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(RunoutColors.Background)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Main table + top status/pocket HUD + bottom Prev/Next playback bar
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Compact Top HUD Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val gameBadge = if (rulesState.gameType == GameType.EIGHT_BALL) {
                        "${rulesState.gameType.displayName} • ${rulesState.eightBallGroup.displayNameFor(ballSetProfile)}"
                    } else {
                        rulesState.gameType.displayName
                    }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = RunoutColors.SurfaceMid,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = gameBadge,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                            if (isPlanning) {
                                Text(
                                    text = "• Planning…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RunoutColors.WarningAmber,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            } else if (!planningReadiness.isReady) {
                                Text(
                                    text = "• Paused: ${planningReadiness.headline}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = RunoutColors.DangerCoral,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (focusedStepIndex == 0 && (playablePockets.size > 1 || customPocketOverride != null)) {
                        PocketSelectorStrip(
                            playablePockets = playablePockets,
                            activePocketId = activeTargetPocketId,
                            customPocketOverride = customPocketOverride,
                            onSelectPocketOverride = onSelectPocketOverride,
                            compact = true,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                // Expanded Pool Table Canvas
                PoolTableCanvas(
                    layout = displayedLayout,
                    selectedShot = selectedShot,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    ballSet = ballSetProfile,
                    onMoveBall = onMoveBall,
                    onDragStateChanged = onDragStateChanged,
                    visibleSpins = visibleSpins,
                    activeRunoutPlan = activePlan,
                    focusedStepIndex = if (showAllStepsOverlay && focusedStepIndex == 0) null else focusedStepIndex,
                    isDraggingBall = isDraggingBall,
                    availablePockets = if (focusedStepIndex == 0) playablePockets else emptyList(),
                    onSelectPocket = if (focusedStepIndex == 0) { pocketId ->
                        onSelectPocketOverride(if (customPocketOverride == pocketId) null else pocketId)
                    } else null,
                    showHeatZone = displayMode == AppDisplayMode.PRO && showHeatZone && focusedStepIndex == 0,
                    showLandingCloud = if (displayMode == AppDisplayMode.SIMPLE) true else showLandingCloud,
                    fitContainer = true,
                )

                // Floating Prev / Next Step Playback Bar
                StepPlaybackControlBar(
                    activePlan = activePlan,
                    selectedShot = selectedShot,
                    ballSetProfile = ballSetProfile,
                    focusedStepIndex = focusedStepIndex,
                    showAllStepsOverlay = showAllStepsOverlay,
                    isPlanning = isPlanning,
                    onPrevStep = onPrevStep,
                    onNextStep = onNextStep,
                    onToggleAllSteps = onToggleAllStepsOverlay,
                    onApplyCurrentStepToTable = if (focusedStepIndex > 0) onApplyCurrentStepToTable else null,
                    onTapCueHitGuide = onOpenQuickSpinSheet,
                    compactLandscape = true,
                )
            }

            // Right Vertical Action Rail (Side Bar for Options & Buttons)
            Surface(
                modifier = Modifier
                    .width(92.dp)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(14.dp),
                color = RunoutColors.SurfaceLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FullscreenRailButton(
                        icon = RunoutIcon.EXIT_FULLSCREEN,
                        label = "Exit",
                        highlighted = true,
                        onClick = onToggleFullscreen,
                    )

                    Surface(
                        onClick = {
                            onDisplayModeChange(
                                if (displayMode == AppDisplayMode.SIMPLE) AppDisplayMode.PRO else AppDisplayMode.SIMPLE,
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (displayMode == AppDisplayMode.PRO) {
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f)
                        } else {
                            RunoutColors.SurfaceMid
                        },
                        border = BorderStroke(
                            1.dp,
                            if (displayMode == AppDisplayMode.PRO) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = displayMode.label.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (displayMode == AppDisplayMode.PRO) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                            Text(
                                text = "Mode",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    FullscreenRailButton(
                        icon = RunoutIcon.SNOOKER,
                        label = "Balls",
                        onClick = onOpenEditBallsSheet,
                    )
                    FullscreenRailButton(
                        icon = RunoutIcon.PLAN,
                        label = "Spin",
                        onClick = onOpenQuickSpinSheet,
                    )
                    FullscreenRailButton(
                        icon = RunoutIcon.TABLE,
                        label = "Game",
                        onClick = onOpenGameSetupSheet,
                    )
                    FullscreenRailButton(
                        icon = RunoutIcon.TUNE,
                        label = "Options",
                        onClick = onOpenShotOptionsSheet,
                    )
                    FullscreenRailButton(
                        icon = RunoutIcon.CAMERA,
                        label = "Scan",
                        onClick = onOpenPhotoScan,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenRailButton(
    icon: RunoutIcon,
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        } else {
            RunoutColors.SurfaceMid
        },
        border = BorderStroke(
            1.dp,
            if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            RunoutVectorIcon(
                icon = icon,
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Shared `[< Prev] Shot X of Y [Next >]` playback control bar used in both portrait and
 * fullscreen landscape planner views to step through evolved post-shot table states.
 */
@Composable
fun StepPlaybackControlBar(
    activePlan: RunoutPlan?,
    selectedShot: ShotRecommendation?,
    ballSetProfile: BallSetProfile,
    focusedStepIndex: Int,
    showAllStepsOverlay: Boolean,
    isPlanning: Boolean,
    onPrevStep: () -> Unit,
    onNextStep: () -> Unit,
    onToggleAllSteps: () -> Unit,
    onApplyCurrentStepToTable: (() -> Unit)? = null,
    onTapCueHitGuide: (() -> Unit)? = null,
    compactLandscape: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val steps = activePlan?.steps.orEmpty()
    val hasSteps = steps.isNotEmpty()
    val safeStepIdx = if (hasSteps) focusedStepIndex.coerceIn(0, steps.lastIndex) else 0
    val currentStep = steps.getOrNull(safeStepIdx)
    val nextStep = steps.getOrNull(safeStepIdx + 1)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = RunoutColors.SurfaceMid,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = if (compactLandscape) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilledTonalIconButton(
                onClick = onPrevStep,
                enabled = hasSteps && safeStepIdx > 0,
                modifier = Modifier.defaultMinSize(
                    minWidth = RunoutSpacing.MinTouchTarget,
                    minHeight = RunoutSpacing.MinTouchTarget,
                ),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = RunoutColors.SurfaceLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                RunoutVectorIcon(
                    icon = RunoutIcon.PREV,
                    tint = if (hasSteps && safeStepIdx > 0) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                    modifier = Modifier.size(18.dp),
                )
            }

            if (currentStep != null || selectedShot != null) {
                val guideTip = currentStep?.recommendedTip ?: TipOffset.fromPreset(selectedShot!!.recommendedSpin)
                val guideTightness = if (currentStep != null) {
                    computeShotTightness(currentStep, nextStep)
                } else {
                    computeShotTightness(selectedShot!!)
                }
                CueBallHitGuide(
                    tipOffset = guideTip,
                    tightness = guideTightness,
                    showAxisLabels = false,
                    onClick = onTapCueHitGuide,
                    modifier = Modifier.size(42.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (currentStep != null) {
                    val makePct = (currentStep.uncertainty.makeProbability * 100.0).roundToInt()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(ballBaseColor(currentStep.targetBall.number, ballSetProfile)),
                        )
                        Text(
                            text = "Shot ${safeStepIdx + 1}/${steps.size}: #${currentStep.targetBall.number} -> ${currentStep.pocketId.displayName} ($makePct%)",
                            style = if (compactLandscape) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val subtitle = buildString {
                        if (isPlanning) {
                            append("Updating shots…")
                        } else if (currentStep.disturbedBalls.isNotEmpty()) {
                            val bumped = currentStep.disturbedBalls.joinToString(", ") { "#${it.ballNumber}" }
                            append("Bumps $bumped • Next table updated")
                        } else if (safeStepIdx > 0) {
                            append("Showing table after potting Step $safeStepIdx")
                        } else {
                            append("Tap Next to pot #${currentStep.targetBall.number} and advance")
                        }
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (currentStep.disturbedBalls.isNotEmpty()) {
                            RunoutColors.WarningAmber
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (selectedShot != null) {
                    val scorePct = (selectedShot.score * 100.0).roundToInt()
                    Text(
                        text = "Single shot: #${selectedShot.targetBall.number} -> ${selectedShot.pocket.id.displayName} ($scorePct%)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = if (isPlanning) "Calculating runout lines…" else "Move a ball or add targets to plan shots",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (onApplyCurrentStepToTable != null && safeStepIdx > 0) {
                TextButton(
                    onClick = onApplyCurrentStepToTable,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                ) {
                    Text(
                        text = "Edit Here",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            if (steps.size > 1) {
                FilterChip(
                    selected = showAllStepsOverlay && safeStepIdx == 0,
                    onClick = onToggleAllSteps,
                    modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                    label = {
                        Text(
                            text = if (showAllStepsOverlay && safeStepIdx == 0) "All" else "Step",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                )
            }

            FilledTonalIconButton(
                onClick = onNextStep,
                enabled = hasSteps && safeStepIdx < steps.lastIndex,
                modifier = Modifier.defaultMinSize(
                    minWidth = RunoutSpacing.MinTouchTarget,
                    minHeight = RunoutSpacing.MinTouchTarget,
                ),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = RunoutColors.SurfaceLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                RunoutVectorIcon(
                    icon = RunoutIcon.NEXT,
                    tint = if (hasSteps && safeStepIdx < steps.lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * Interactive pocket chooser strip allowing the player to switch the target pocket for Shot 1
 * (either automatically picking the highest-EV pocket or pinning any playable pocket).
 */
@Composable
fun PocketSelectorStrip(
    playablePockets: List<PocketId>,
    activePocketId: PocketId?,
    customPocketOverride: PocketId?,
    onSelectPocketOverride: (PocketId?) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = customPocketOverride == null,
            onClick = { onSelectPocketOverride(null) },
            modifier = Modifier.defaultMinSize(minHeight = if (compact) 36.dp else 44.dp),
            label = {
                Text(
                    text = "Auto Pocket",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            },
        )
        for (pocketId in playablePockets) {
            val isSelected = customPocketOverride == pocketId ||
                (customPocketOverride == null && activePocketId == pocketId)
            FilterChip(
                selected = isSelected,
                onClick = {
                    onSelectPocketOverride(if (customPocketOverride == pocketId) null else pocketId)
                },
                modifier = Modifier.defaultMinSize(minHeight = if (compact) 36.dp else 44.dp),
                label = {
                    Text(
                        text = pocketId.shortLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (customPocketOverride == pocketId) FontWeight.Bold else FontWeight.Medium,
                    )
                },
            )
        }
    }
}

private fun PocketId.shortLabel(): String = when (this) {
    PocketId.HEAD_LEFT -> "Head L"
    PocketId.HEAD_RIGHT -> "Head R"
    PocketId.SIDE_LEFT -> "Side L"
    PocketId.SIDE_RIGHT -> "Side R"
    PocketId.FOOT_LEFT -> "Foot L"
    PocketId.FOOT_RIGHT -> "Foot R"
}
