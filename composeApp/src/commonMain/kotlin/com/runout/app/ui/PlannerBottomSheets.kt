package com.runout.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.runout.engine.model.AssumedSurfacePreset
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.ClothParams
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.TipOffset
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PlanningHorizonOption(
    val label: String,
    val horizonShots: Int,
    val deepAnalysis: Boolean,
) {
    ONE_SHOT("1-Shot", 1, false),
    TWO_BALL("2-Ball", 2, false),
    THREE_BALL("3-Ball", 3, false),
    FOUR_BALL("4-Ball", 4, false),
    FIVE_BALL("5-Ball", 5, false),
    DEEP_RUNOUT("Deep Run-Out", 5, true),
}

private data class SheetTableSizeOption(
    val label: String,
    val specName: String,
    val createSpec: () -> TableSpec,
)

private val SHEET_TABLE_SIZE_OPTIONS = listOf(
    SheetTableSizeOption("7-ft Bar Box", "7 ft Pool") { TableSpec.sevenFootPool() },
    SheetTableSizeOption("8-ft Pool", "8 ft Pool") { TableSpec.eightFootPool() },
    SheetTableSizeOption("9-ft Pro", "9 ft Pool") { TableSpec.nineFootPool() },
    SheetTableSizeOption("12-ft Snooker", "12 ft Snooker") { TableSpec.fullSizeSnooker() },
)

private data class SheetGameOption(
    val label: String,
    val rulesState: GameRulesState,
)

private val SHEET_GAME_OPTIONS = listOf(
    SheetGameOption("8-Ball Solids", GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS)),
    SheetGameOption("8-Ball Stripes", GameRulesState(GameType.EIGHT_BALL, EightBallGroup.STRIPES)),
    SheetGameOption("8-Ball Open", GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN)),
    SheetGameOption("9-Ball", GameRulesState(GameType.NINE_BALL)),
    SheetGameOption("10-Ball", GameRulesState(GameType.TEN_BALL)),
)

private val SHEET_SURFACE_PRESET_LABELS: Map<AssumedSurfacePreset, String> = mapOf(
    AssumedSurfacePreset.PRO_BROADCAST_FAST to "Pro Fast",
    AssumedSurfacePreset.STANDARD_POOL_HALL to "Standard",
    AssumedSurfacePreset.SLOW_BAR_BOX_HEAVY to "Slow Bar",
    AssumedSurfacePreset.SNOOKER_DIRECTIONAL to "Snooker",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameAndTableSetupSheet(
    scenarios: List<SampleScenario>,
    selectedPresetIndex: Int,
    currentLayout: TableLayout,
    rulesState: GameRulesState,
    ballSet: BallSetProfile,
    onSelectPreset: (Int) -> Unit,
    onStartBlankTable: () -> Unit,
    onResizeTable: (TableSpec) -> Unit,
    onSelectRules: (GameRulesState) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Element),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Margin),
        ) {
            SheetHeaderRow(title = "Game, Table Size & Presets", onDismiss = onDismiss)

            // Game & Group
            Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                Text(
                    text = "Game & Group",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    SHEET_GAME_OPTIONS.forEach { option ->
                        val isSelected = rulesState == option.rulesState
                        val label = when {
                            option.rulesState.gameType == GameType.EIGHT_BALL &&
                                option.rulesState.eightBallGroup == EightBallGroup.SOLIDS &&
                                ballSet.isUkBlackball -> "UK Yellows"
                            option.rulesState.gameType == GameType.EIGHT_BALL &&
                                option.rulesState.eightBallGroup == EightBallGroup.STRIPES &&
                                ballSet.isUkBlackball -> "UK Reds"
                            else -> option.label
                        }
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectRules(option.rulesState) },
                            modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                            label = {
                                Text(
                                    text = label,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                        )
                    }
                }
            }

            // Table Size
            Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                Text(
                    text = "Table Size",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    SHEET_TABLE_SIZE_OPTIONS.forEach { option ->
                        val isSelected = currentLayout.table.name == option.specName
                        FilterChip(
                            selected = isSelected,
                            onClick = { onResizeTable(option.createSpec()) },
                            modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                            label = {
                                Text(
                                    text = option.label,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                        )
                    }
                }
            }

            // Practice Presets & Blank Rack
            Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                Text(
                    text = "Starting Layouts",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                scenarios.forEachIndexed { index, scenario ->
                    val isSelected = index == selectedPresetIndex
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            onSelectPreset(index)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                            } else {
                                RunoutColors.SurfaceMid
                            },
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(RunoutSpacing.Card),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = scenario.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = scenario.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = {
                        onStartBlankTable()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Start Blank Table (Cue Ball + End Ball Only)")
                }
            }

            Spacer(modifier = Modifier.height(RunoutSpacing.Element))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBallsSheet(
    currentLayout: TableLayout,
    ballSet: BallSetProfile,
    onToggleBall: (Int) -> Unit,
    onAddMissingCueBall: () -> Unit,
    onRequestReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Element),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Margin),
        ) {
            SheetHeaderRow(
                title = "Edit Balls (${currentLayout.objectBalls().size} object balls)",
                onDismiss = onDismiss,
            )

            Text(
                text = "Tap any number to add or remove that ball on the felt, then drag balls directly on the table.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (currentLayout.cueBall() == null) {
                Button(
                    onClick = onAddMissingCueBall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text("Add Cue Ball (#0)", fontWeight = FontWeight.SemiBold)
                }
            }

            (1..15).chunked(5).forEach { rowNumbers ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    rowNumbers.forEach { ballNumber ->
                        val isPresent = currentLayout.ballByNumber(ballNumber) != null
                        val dotColor = ballBaseColor(ballNumber, ballSet)
                        FilterChip(
                            selected = isPresent,
                            onClick = { onToggleBall(ballNumber) },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(dotColor),
                                )
                            },
                            label = {
                                Text(
                                    text = "$ballNumber",
                                    fontWeight = if (isPresent) FontWeight.Bold else FontWeight.Normal,
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
                onClick = {
                    onDismiss()
                    onRequestReset()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("Reset Table Layout", color = RunoutColors.DangerCoral)
            }

            Spacer(modifier = Modifier.height(RunoutSpacing.Element))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotOptionsSheet(
    displayMode: AppDisplayMode,
    playerProfile: PlayerProfile,
    selectedHorizon: PlanningHorizonOption,
    onSelectHorizon: (PlanningHorizonOption) -> Unit,
    speedScaleOverride: Int?,
    onSelectSpeedScale: (Int?) -> Unit,
    showHeatZone: Boolean,
    onToggleHeatZone: () -> Unit,
    showLandingCloud: Boolean,
    onToggleLandingCloud: () -> Unit,
    visibleSpins: Set<CueBallSpinPreset>,
    onToggleSpin: (CueBallSpinPreset) -> Unit,
    clothParams: ClothParams,
    onUpdateClothParams: (ClothParams) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Element),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Margin),
        ) {
            SheetHeaderRow(title = "Shot & Overlay Options", onDismiss = onDismiss)

            // Lookahead Horizon
            Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                Text(
                    text = "Plan Lookahead",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    PlanningHorizonOption.entries.forEach { option ->
                        val isSelected = option == selectedHorizon
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectHorizon(option) },
                            modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            // Overlays
            Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Table Overlays",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    GlossaryInfoButton(
                        term = "Heat Zone & Landing Cloud",
                        explanation = "Heat Zone colours the cloth green where stopping gives the best shot on your next ball. Landing Cloud draws the 95% likely resting area of the cue ball.",
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    FilterChip(
                        selected = showLandingCloud,
                        onClick = onToggleLandingCloud,
                        modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                        label = { Text("Landing Cloud") },
                    )
                    FilterChip(
                        selected = showHeatZone,
                        onClick = onToggleHeatZone,
                        modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                        label = { Text("Heat Zone") },
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    CueBallSpinPreset.entries.forEach { spin ->
                        val label = when (spin) {
                            CueBallSpinPreset.STUN -> "90° Stun Line"
                            CueBallSpinPreset.FOLLOW -> "30° Follow Line"
                            CueBallSpinPreset.DRAW -> "Draw Line"
                        }
                        FilterChip(
                            selected = spin in visibleSpins,
                            onClick = { onToggleSpin(spin) },
                            modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                            label = { Text(label) },
                        )
                    }
                }
            }

            // Speed Scale
            Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                val speedSummary = if (speedScaleOverride == null) {
                    "Auto (${playerProfile.name})"
                } else {
                    "$speedScaleOverride/10"
                }
                Text(
                    text = "Cue Speed: $speedSummary",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = speedScaleOverride == null,
                        onClick = { onSelectSpeedScale(null) },
                        modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                        label = { Text("Auto") },
                    )
                    for (scale in 1..10) {
                        FilterChip(
                            selected = speedScaleOverride == scale,
                            onClick = { onSelectSpeedScale(scale) },
                            modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                            label = { Text("$scale") },
                        )
                    }
                }
            }

            if (displayMode == AppDisplayMode.PRO) {
                AssumedSurfaceControlCard(
                    title = "Cloth Roll & Rail Bounce",
                    clothParams = clothParams,
                    presetLabels = SHEET_SURFACE_PRESET_LABELS,
                    onUpdateClothParams = onUpdateClothParams,
                )
            }

            Spacer(modifier = Modifier.height(RunoutSpacing.Element))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSpinSheet(
    customTipOffset: TipOffset?,
    onSelectTipOffset: (TipOffset?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Element),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cue Spin Selection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                GlossaryInfoButton(
                    term = "Stun, Follow & Draw",
                    explanation = "Stun sends the cue ball along the 90° tangent line after contact; Follow rolls it forward (~30°); Draw pulls it backward after hitting the object ball.",
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                ) {
                    Text("Done")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            val presets = listOf(
                Triple("Auto Recommended", "Let Runout pick the best spin for position", null),
                Triple("Center Stun (90°)", "Cue ball slides cleanly sideways after impact", TipOffset(0.0, 0.0)),
                Triple("Top Follow (30°)", "Natural forward roll toward the foot rail", TipOffset.fromPreset(CueBallSpinPreset.FOLLOW)),
                Triple("Bottom Draw", "Pulls the cue ball backward off the shot line", TipOffset.fromPreset(CueBallSpinPreset.DRAW)),
            )

            presets.forEach { (title, subtitle, tip) ->
                val isSelected = if (tip == null) {
                    customTipOffset == null
                } else {
                    customTipOffset != null &&
                        abs(customTipOffset.clampedSideRatio - tip.clampedSideRatio) < 0.04 &&
                        abs(customTipOffset.clampedVerticalRatio - tip.clampedVerticalRatio) < 0.04
                }
                Surface(
                    onClick = {
                        onSelectTipOffset(tip)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        RunoutColors.SurfaceMid
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 56.dp)
                            .padding(RunoutSpacing.Card),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(RunoutSpacing.Element))
        }
    }
}

@Composable
fun CueBallSandboxPanel(
    activeTip: TipOffset,
    defaultTip: TipOffset?,
    customTipOffset: TipOffset?,
    activeSpeedScale: Int,
    speedScaleOverride: Int?,
    lockedTargetBallNumber: Int?,
    hasOverrides: Boolean,
    onUpdateTip: (TipOffset?) -> Unit,
    onUpdateSpeedScale: (Int?) -> Unit,
    onResetDefaults: () -> Unit,
    onClose: () -> Unit,
    compactVertical: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val spinPresets = listOf(
        "Auto" to null,
        "Stun" to TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
        "Follow" to TipOffset.fromPreset(CueBallSpinPreset.FOLLOW),
        "Draw" to TipOffset.fromPreset(CueBallSpinPreset.DRAW),
        "Left" to TipOffset(sideRatio = -0.30, verticalRatio = 0.0),
        "Right" to TipOffset(sideRatio = 0.30, verticalRatio = 0.0),
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = RunoutColors.SurfaceMid,
        border = BorderStroke(
            width = 1.2.dp,
            color = if (hasOverrides) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (compactVertical) 10.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactVertical) 8.dp else 10.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cue Ball Sandbox",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (lockedTargetBallNumber != null) {
                        Text(
                            text = "Testing #${lockedTargetBallNumber} live",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (hasOverrides) {
                            RunoutColors.WarningAmber.copy(alpha = 0.16f)
                        } else {
                            RunoutColors.SuccessGreen.copy(alpha = 0.16f)
                        },
                    ) {
                        Text(
                            text = if (hasOverrides) "Custom" else "Default",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasOverrides) RunoutColors.WarningAmber else RunoutColors.SuccessGreen,
                        )
                    }
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                    ) {
                        Text("Done", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (compactVertical) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CueTipSelectorCanvas(
                        tipOffset = activeTip,
                        defaultTipOffset = if (customTipOffset != null) defaultTip else null,
                        onUpdateTip = { onUpdateTip(it) },
                        modifier = Modifier.size(116.dp),
                    )
                    Text(
                        text = formatSandboxSpinSummary(activeTip),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Speed: $activeSpeedScale/10 (${sandboxSpeedLabel(activeSpeedScale)})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (speedScaleOverride == null) {
                            Text(
                                text = "Auto",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    Slider(
                        value = activeSpeedScale.toFloat(),
                        onValueChange = { onUpdateSpeedScale(it.roundToInt().coerceIn(1, 10)) },
                        valueRange = 1f..10f,
                        steps = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CueTipSelectorCanvas(
                        tipOffset = activeTip,
                        defaultTipOffset = if (customTipOffset != null) defaultTip else null,
                        onUpdateTip = { onUpdateTip(it) },
                        modifier = Modifier.size(126.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = formatSandboxSpinSummary(activeTip),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Speed: $activeSpeedScale/10 (${sandboxSpeedLabel(activeSpeedScale)})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (speedScaleOverride == null) "Auto" else "Manual",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (speedScaleOverride == null) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    RunoutColors.WarningAmber
                                },
                            )
                        }
                        Slider(
                            value = activeSpeedScale.toFloat(),
                            onValueChange = { onUpdateSpeedScale(it.roundToInt().coerceIn(1, 10)) },
                            valueRange = 1f..10f,
                            steps = 8,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Quick spin presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                spinPresets.forEach { (label, tip) ->
                    val isSelected = if (tip == null) {
                        customTipOffset == null
                    } else {
                        customTipOffset != null &&
                            abs(customTipOffset.clampedSideRatio - tip.clampedSideRatio) < 0.04 &&
                            abs(customTipOffset.clampedVerticalRatio - tip.clampedVerticalRatio) < 0.04
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { onUpdateTip(tip) },
                        modifier = Modifier.defaultMinSize(minHeight = if (compactVertical) 36.dp else 40.dp),
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            // Auto speed + Reset to Plan Defaults
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = speedScaleOverride == null,
                    onClick = { onUpdateSpeedScale(null) },
                    modifier = Modifier.defaultMinSize(minHeight = if (compactVertical) 38.dp else 44.dp),
                    label = { Text("Auto Speed", style = MaterialTheme.typography.labelSmall) },
                )
                OutlinedButton(
                    onClick = onResetDefaults,
                    enabled = hasOverrides,
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = if (compactVertical) 38.dp else 44.dp),
                ) {
                    Text(
                        text = if (compactVertical) "Reset Defaults" else "Reset to Plan Defaults",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun sandboxSpeedLabel(scale: Int): String = when {
    scale <= 3 -> "Gentle"
    scale <= 6 -> "Medium"
    scale <= 8 -> "Firm"
    else -> "Hard"
}

private fun formatSandboxSpinSummary(tip: TipOffset): String {
    val vert = when {
        tip.clampedVerticalRatio > 0.12 -> "Follow"
        tip.clampedVerticalRatio < -0.12 -> "Draw"
        else -> "Stun"
    }
    val side = when {
        tip.clampedSideRatio > 0.08 -> "Right"
        tip.clampedSideRatio < -0.08 -> "Left"
        else -> "Center"
    }
    return if (side == "Center") "Spin: $vert" else "Spin: $vert + $side"
}

@Composable
private fun SheetHeaderRow(
    title: String,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
            ) {
                Text("Done", fontWeight = FontWeight.SemiBold)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
