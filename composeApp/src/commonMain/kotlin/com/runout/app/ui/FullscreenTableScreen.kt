package com.runout.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.runout.app.fallbackChanceLabel
import com.runout.app.fallbackTitle
import com.runout.app.planning.showsFocusEdit
import com.runout.engine.math.Vec2
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.FallbackAdvice
import com.runout.engine.planner.PlanningReadinessResult
import com.runout.engine.planner.PlannedShotStep
import com.runout.engine.planner.RollingPlanState
import com.runout.engine.planner.RollingRunoutPlanner
import com.runout.engine.planner.ShotRecommendation
import kotlin.math.roundToInt

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
    rollingPlan: RollingPlanState?,
    selectedShot: ShotRecommendation?,
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
    onResetStep: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onOpenGameSetupSheet: () -> Unit,
    onOpenEditBallsSheet: () -> Unit,
    onOpenQuickSpinSheet: () -> Unit,
    onOpenShotOptionsSheet: () -> Unit,
    onOpenPhotoScan: () -> Unit,
    customTipOffset: TipOffset? = null,
    defaultTipForShot: TipOffset? = null,
    speedUnitsOverride: Double? = null,
    defaultSpeedUnitsForShot: Double? = null,
    playerProfile: PlayerProfile = PlayerProfile(),
    sandboxTargetBallNumber: Int? = null,
    showCueSandboxPanel: Boolean = false,
    hasSandboxOverrides: Boolean = false,
    onToggleCueSandbox: () -> Unit = onOpenQuickSpinSheet,
    onCloseCueSandbox: () -> Unit = {},
    onUpdateCustomTip: (TipOffset?) -> Unit = {},
    onSelectSpeedUnits: (Double?) -> Unit = {},
    onResetSandboxDefaults: () -> Unit = {},
    fallbackAdvice: FallbackAdvice? = null,
    planError: String? = null,
    modifier: Modifier = Modifier,
) {
    val activePlan = rollingPlan?.window
    val activeStep = rollingPlan?.focusStep
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

                    if (rollingPlan != null && (playablePockets.size > 1 || customPocketOverride != null)) {
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
                    if (hasSandboxOverrides) {
                        FilterChip(
                            selected = true,
                            onClick = onResetSandboxDefaults,
                            modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                            label = {
                                Text(
                                    text = "Reset",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                        )
                    }
                }

                if (planError != null) {
                    Text(
                        text = planError,
                        style = MaterialTheme.typography.labelMedium,
                        color = RunoutColors.DangerCoral,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                    // The window starts at the focused shot: one line set in Step mode, all of it in All mode.
                    focusedStepIndex = if (showAllStepsOverlay) null else 0,
                    isDraggingBall = isDraggingBall,
                    availablePockets = playablePockets,
                    onSelectPocket = { pocketId ->
                        onSelectPocketOverride(if (customPocketOverride == pocketId) null else pocketId)
                    },
                    onTapCueBall = if (planningReadiness.isReady) onToggleCueSandbox else null,
                    highlightCueBall = showCueSandboxPanel || hasSandboxOverrides,
                    showHeatZone = displayMode == AppDisplayMode.PRO && showHeatZone,
                    showLandingCloud = if (displayMode == AppDisplayMode.SIMPLE) true else showLandingCloud,
                    displayMode = displayMode,
                    fitContainer = true,
                    isFullscreen = true,
                    onToggleFullscreen = onToggleFullscreen,
                    fallbackAdvice = fallbackAdvice,
                )

                // Floating Prev / Next Step Playback Bar
                StepPlaybackControlBar(
                    rollingPlan = rollingPlan,
                    selectedShot = selectedShot,
                    ballSetProfile = ballSetProfile,
                    tableSpec = displayedLayout.table,
                    showAllStepsOverlay = showAllStepsOverlay,
                    isPlanning = isPlanning,
                    onPrevStep = onPrevStep,
                    onNextStep = onNextStep,
                    onToggleAllSteps = onToggleAllStepsOverlay,
                    onResetStep = onResetStep,
                    onTapCueHitGuide = onToggleCueSandbox,
                    fallbackAdvice = fallbackAdvice,
                    compactLandscape = true,
                )
            }

            // Collapsible Live Cue-Ball Sandbox Panel side-by-side with table
            AnimatedVisibility(
                visible = showCueSandboxPanel && planningReadiness.isReady,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .width(228.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    val resolvedTip = customTipOffset
                        ?: activeStep?.recommendedTip
                        ?: defaultTipForShot
                        ?: TipOffset()
                    val resolvedSpeed = speedUnitsOverride
                        ?: activeStep?.recommendedSpeedUnits
                        ?: defaultSpeedUnitsForShot
                        ?: 4.5
                    val cueToGhostMeters = activeStep?.compensatedAim?.geometricAim?.cueToGhostDistanceMeters
                        ?: selectedShot?.aim?.cueToGhostDistanceMeters
                    val resolvedAim = activeStep?.compensatedAim?.geometricAim
                        ?: selectedShot?.aim
                    val resolvedPocket = activeStep?.pocketId?.let { displayedLayout.table.pocketById(it) }
                        ?: selectedShot?.pocket
                    val resolvedTargetNumber = sandboxTargetBallNumber
                        ?: activeStep?.targetBall?.number
                        ?: selectedShot?.targetBall?.number
                    CueBallSandboxPanel(
                        activeTip = resolvedTip,
                        defaultTip = defaultTipForShot ?: activeStep?.recommendedTip,
                        customTipOffset = customTipOffset,
                        activeSpeedUnits = resolvedSpeed,
                        speedUnitsOverride = speedUnitsOverride,
                        lockedTargetBallNumber = sandboxTargetBallNumber,
                        hasOverrides = hasSandboxOverrides,
                        onUpdateTip = onUpdateCustomTip,
                        onUpdateSpeedUnits = onSelectSpeedUnits,
                        onResetDefaults = onResetSandboxDefaults,
                        onClose = onCloseCueSandbox,
                        compactVertical = true,
                        cueToObjectDistanceMeters = cueToGhostMeters,
                        activeAim = resolvedAim,
                        activePocket = resolvedPocket,
                        activeTargetBallNumber = resolvedTargetNumber,
                        playerProfile = playerProfile,
                        tableSpec = displayedLayout.table,
                    )
                }
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
                        highlighted = showCueSandboxPanel || hasSandboxOverrides,
                        onClick = onToggleCueSandbox,
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
 * Shared `[< Prev] Shot k · balls a–b [Next >]` playback bar for the portrait and fullscreen planner views.
 *
 * Next accepts the focused shot and slides the plan forward; Prev reopens the previous one. Both stay disabled
 * while the planner is working, and Next also while the focused shot cannot be accepted (the reason is shown under
 * the bar). Below the controls a strip lists the accepted shots (locked), the focused shot and the ones planned after
 * it, marking the shots a re-plan changed.
 */
@Composable
fun StepPlaybackControlBar(
    rollingPlan: RollingPlanState?,
    selectedShot: ShotRecommendation?,
    ballSetProfile: BallSetProfile,
    tableSpec: TableSpec,
    showAllStepsOverlay: Boolean,
    isPlanning: Boolean,
    onPrevStep: () -> Unit,
    onNextStep: () -> Unit,
    onToggleAllSteps: () -> Unit,
    onResetStep: () -> Unit,
    onTapCueHitGuide: (() -> Unit)? = null,
    compactLandscape: Boolean = false,
    fallbackAdvice: FallbackAdvice? = null,
    modifier: Modifier = Modifier,
) {
    val steps = rollingPlan?.window?.steps.orEmpty()
    val currentStep = rollingPlan?.focusStep
    val nextStep = steps.getOrNull(1)
    val isRackComplete = rollingPlan?.isRackComplete == true
    val canGoBack = rollingPlan?.canGoBack == true && !isPlanning
    val canAdvance = rollingPlan?.canAdvance == true && !isPlanning
    // Why Next is unavailable for a shot that is on screen (a miss, a scratch, a cue ball that stops short).
    val refusal = if (rollingPlan != null && currentStep != null && !isRackComplete && !isPlanning) {
        RollingRunoutPlanner.advanceRefusal(rollingPlan)
    } else {
        null
    }
    // The same word as the "Reset Defaults" chip beside the table: the planner's own answer.
    val isEdited = rollingPlan?.showsFocusEdit == true
    val resetInMainRow = isEdited && compactLandscape

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = RunoutColors.SurfaceMid,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = if (compactLandscape) 4.dp else 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilledTonalIconButton(
                    onClick = onPrevStep,
                    enabled = canGoBack,
                    modifier = Modifier
                        .defaultMinSize(
                            minWidth = RunoutSpacing.MinTouchTarget,
                            minHeight = RunoutSpacing.MinTouchTarget,
                        )
                        .semantics { contentDescription = "Previous shot" },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = RunoutColors.SurfaceLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    RunoutVectorIcon(
                        icon = RunoutIcon.PREV,
                        tint = if (canGoBack) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }

                if (currentStep != null || selectedShot != null) {
                    val guideTip = currentStep?.recommendedTip
                        ?: selectedShot?.solvedTip(tableSpec)
                        ?: TipOffset()
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
                    if (isRackComplete) {
                        Text(
                            text = "Rack complete",
                            style = if (compactLandscape) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = RunoutColors.SuccessGreen,
                            maxLines = 1,
                        )
                        Text(
                            text = "Press Prev to revisit a shot",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (currentStep != null) {
                        val makePct = (currentStep.uncertainty.makeProbability * 100.0).roundToInt()
                        val makeTier = currentStep.makeDifficulty
                        val posProb = currentStep.positionSuccessProbability
                        val posTier = currentStep.positionDifficulty
                        val mutedColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                                text = "Shot ${currentStep.stepNumber} · ${formatBallRange(steps.map { it.targetBall.number })}",
                                style = if (compactLandscape) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val subtitle = buildAnnotatedString {
                            withStyle(SpanStyle(color = shotDifficultyColor(makeTier), fontWeight = FontWeight.SemiBold)) {
                                append("Make $makePct% ${makeTier.label}")
                            }
                            withStyle(SpanStyle(color = mutedColor)) {
                                append(" • ")
                            }
                            if (posProb != null && posTier != null) {
                                val posPct = (posProb.coerceIn(0.0, 1.0) * 100.0).roundToInt()
                                withStyle(SpanStyle(color = shotDifficultyColor(posTier), fontWeight = FontWeight.SemiBold)) {
                                    append("Position $posPct% ${posTier.label}")
                                }
                            } else {
                                withStyle(SpanStyle(color = RunoutColors.PrimaryCyan, fontWeight = FontWeight.SemiBold)) {
                                    append("Final ball")
                                }
                            }
                            when {
                                isPlanning -> withStyle(SpanStyle(color = RunoutColors.WarningAmber)) {
                                    append(" • Updating…")
                                }
                                currentStep.disturbedBalls.isNotEmpty() -> {
                                    val bumped = currentStep.disturbedBalls.joinToString(", ") { "#${it.ballNumber}" }
                                    withStyle(SpanStyle(color = RunoutColors.WarningAmber)) {
                                        append(" • Bumps $bumped")
                                    }
                                }
                            }
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (selectedShot != null) {
                        val makePct = (selectedShot.makeProbability.coerceIn(0.0, 1.0) * 100.0).roundToInt()
                        Text(
                            text = "Single shot: #${selectedShot.targetBall.number} -> ${selectedShot.pocket.id.displayName} (Make $makePct%)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (fallbackAdvice != null && !isPlanning) {
                        Text(
                            text = fallbackTitle(fallbackAdvice),
                            style = if (compactLandscape) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${fallbackChanceLabel(fallbackAdvice.kind)} ${(fallbackAdvice.outcomeProbability * 100.0).roundToInt()}% • " +
                                "speed ${PlayerProfile.formatSpeedUnits(fallbackAdvice.speedUnits)}/10",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                if (resetInMainRow) {
                    ResetStepButton(onClick = onResetStep)
                }

                if (steps.size > 1) {
                    FilterChip(
                        selected = showAllStepsOverlay,
                        onClick = onToggleAllSteps,
                        modifier = Modifier.defaultMinSize(minHeight = 40.dp),
                        label = {
                            Text(
                                text = if (showAllStepsOverlay) "All" else "Step",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                    )
                }

                FilledTonalIconButton(
                    onClick = onNextStep,
                    enabled = canAdvance,
                    modifier = Modifier
                        .defaultMinSize(
                            minWidth = RunoutSpacing.MinTouchTarget,
                            minHeight = RunoutSpacing.MinTouchTarget,
                        )
                        .semantics { contentDescription = "Next shot" },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = RunoutColors.SurfaceLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    RunoutVectorIcon(
                        icon = RunoutIcon.NEXT,
                        tint = if (canAdvance) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (rollingPlan != null) {
                RollingStepStrip(rollingPlan = rollingPlan, ballSetProfile = ballSetProfile)
            }

            if (refusal != null || (isEdited && !resetInMainRow)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (refusal != null) {
                        Text(
                            text = refusal,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = RunoutColors.WarningAmber,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 6.dp),
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (isEdited && !resetInMainRow) {
                        ResetStepButton(onClick = onResetStep)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResetStepButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
    ) {
        Text(
            text = "Reset step",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The balls of the plan in order: accepted shots first (locked), then the focused shot and the shots planned after
 * it. A shot whose ball or pocket changed on the last re-plan is marked "updated"; one that kept its ball and pocket
 * but got a different stroke is marked "refined"; the focused shot reads "edited" when the player changed it.
 */
@Composable
private fun RollingStepStrip(
    rollingPlan: RollingPlanState,
    ballSetProfile: BallSetProfile,
) {
    val windowSteps = rollingPlan.window?.steps.orEmpty()
    if (rollingPlan.acceptedSteps.isEmpty() && windowSteps.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rollingPlan.acceptedSteps.forEach { step ->
            StepMarkChip(
                step = step,
                ballSetProfile = ballSetProfile,
                state = if (step.stepNumber in rollingPlan.userEditedStepNumbers) StepMark.LOCKED_EDITED else StepMark.LOCKED,
            )
        }
        windowSteps.forEachIndexed { index, step ->
            val mark = when {
                step.stepNumber in rollingPlan.updatedStepNumbers -> StepMark.UPDATED
                step.stepNumber in rollingPlan.refinedStepNumbers -> StepMark.REFINED
                index == 0 && rollingPlan.isFocusEdited -> StepMark.EDITED
                index == 0 -> StepMark.FOCUS
                else -> StepMark.PLANNED
            }
            StepMarkChip(
                step = step,
                ballSetProfile = ballSetProfile,
                state = mark,
                isFocus = index == 0,
            )
        }
        if (rollingPlan.isRackComplete) {
            Text(
                text = "✓ rack complete",
                modifier = Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = RunoutColors.SuccessGreen,
                maxLines = 1,
            )
        }
    }
}

private enum class StepMark(val suffix: String?) {
    LOCKED(null),
    LOCKED_EDITED("edited"),
    FOCUS(null),
    EDITED("edited"),
    UPDATED("updated"),
    REFINED("refined"),
    PLANNED(null),
}

@Composable
private fun StepMarkChip(
    step: PlannedShotStep,
    ballSetProfile: BallSetProfile,
    state: StepMark,
    isFocus: Boolean = false,
) {
    val isLocked = state == StepMark.LOCKED || state == StepMark.LOCKED_EDITED
    val accent = when {
        state == StepMark.UPDATED -> RunoutColors.WarningAmber
        isFocus -> RunoutColors.PrimaryCyan
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val description = buildString {
        append("Shot ${step.stepNumber}, ball ${step.targetBall.number}")
        if (isLocked) append(", accepted")
        if (state.suffix != null) append(", ${state.suffix}")
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = when {
            state == StepMark.UPDATED -> RunoutColors.WarningAmber.copy(alpha = 0.22f)
            isFocus -> RunoutColors.PrimaryCyan.copy(alpha = 0.14f)
            else -> RunoutColors.SurfaceLow
        },
        border = BorderStroke(if (state == StepMark.UPDATED) 1.5.dp else 1.dp, accent),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(ballBaseColor(step.targetBall.number, ballSetProfile)),
            )
            Text(
                text = "#${step.targetBall.number}",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isFocus) FontWeight.Bold else FontWeight.Medium,
                color = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (isLocked) {
                LockMark(tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.suffix?.let { suffix ->
                Text(
                    text = suffix,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (state == StepMark.UPDATED) FontWeight.Bold else FontWeight.Normal,
                    color = when (state) {
                        StepMark.UPDATED -> RunoutColors.WarningAmber
                        StepMark.EDITED, StepMark.LOCKED_EDITED -> RunoutColors.PrimaryCyan
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** A small padlock: an accepted shot is frozen and the plan no longer changes it. */
@Composable
private fun LockMark(tint: Color, modifier: Modifier = Modifier.size(10.dp)) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.24f, h * 0.04f),
            size = Size(w * 0.52f, h * 0.52f),
            style = Stroke(width = w * 0.16f, cap = StrokeCap.Round),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.44f),
            size = Size(w * 0.80f, h * 0.52f),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
        )
    }
}

/**
 * Names the balls of a plan window: `ball 3`, `balls 3–5` for a run of consecutive numbers, otherwise the numbers
 * in shooting order (`balls 1, 5, 3`).
 */
internal fun formatBallRange(ballNumbers: List<Int>): String = when {
    ballNumbers.isEmpty() -> ""
    ballNumbers.size == 1 -> "ball ${ballNumbers.first()}"
    ballNumbers.zipWithNext().all { (a, b) -> b == a + 1 } -> "balls ${ballNumbers.first()}–${ballNumbers.last()}"
    else -> "balls " + ballNumbers.joinToString(", ")
}

/**
 * Interactive pocket chooser strip allowing the player to switch the target pocket of the focused shot
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
