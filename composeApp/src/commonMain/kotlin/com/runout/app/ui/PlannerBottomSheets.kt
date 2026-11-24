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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runout.engine.model.AssumedSurfacePreset
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.ClothParams
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.SpinPhysics
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

            val centeredChipText = remember {
                LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                )
            }
            (1..15).chunked(5).forEach { rowNumbers ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    rowNumbers.forEach { ballNumber ->
                        val isPresent = currentLayout.ballByNumber(ballNumber) != null
                        FilterChip(
                            selected = isPresent,
                            onClick = { onToggleBall(ballNumber) },
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                            leadingIcon = {
                                BallNumberBadge(
                                    ballNumber = ballNumber,
                                    ballSet = ballSet,
                                    sizeDp = 18.dp,
                                )
                            },
                            label = {
                                Text(
                                    text = "$ballNumber",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        lineHeight = 14.sp,
                                        lineHeightStyle = centeredChipText,
                                    ),
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
    speedUnitsOverride: Double?,
    onSelectSpeedUnits: (Double?) -> Unit,
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

            // 20-Point Speed Scale (0.5 .. 10.0)
            Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                val speedSummary = if (speedUnitsOverride == null) {
                    "Auto (${playerProfile.name})"
                } else {
                    "${PlayerProfile.formatSpeedUnits(speedUnitsOverride)}/10 (${sandboxSpeedLabel(speedUnitsOverride)})"
                }
                Text(
                    text = "Cue Speed (20-Point Scale): $speedSummary",
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
                        selected = speedUnitsOverride == null,
                        onClick = { onSelectSpeedUnits(null) },
                        modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                        label = { Text("Auto") },
                    )
                    PlayerProfile.TWENTY_POINT_SPEED_UNITS.forEach { units ->
                        val isSelected = speedUnitsOverride != null && abs(speedUnitsOverride - units) < 0.01
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectSpeedUnits(units) },
                            modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                            label = { Text(PlayerProfile.formatSpeedUnits(units)) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Element),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Cue Spin & English Mix",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                GlossaryInfoButton(
                    term = "Spin Levels & Mixing",
                    explanation = "Mild offsets (Drag Stun, Touch of Follow/Draw, 1/2-Tip English) hold cleaner control and less squirt/throw than max spin. On long shots, soft draw wears off into stun or forward roll.",
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
                Triple("Auto Recommended", "Let Runout pick the cleanest natural spin & speed", null),
                Triple("Center Ball (Pure Stun)", "Slides along 90° tangent on close/medium shots; rolls forward on long soft shots", TipOffset(0.0, 0.0)),
                Triple("Touch of Low (Drag Stun)", "Holds the 90° stun line longer across medium-to-long distance", TipOffset(0.0, -0.14)),
                Triple("Controlled Follow", "Smooth half-tip topspin rolling naturally forward (~30°)", TipOffset(0.0, 0.20)),
                Triple("Firm Top Follow", "Full 1-tip topspin driving through cushions", TipOffset.fromPreset(CueBallSpinPreset.FOLLOW)),
                Triple("Controlled Draw", "Balanced half-tip backspin for short pullback without wild squirt", TipOffset(0.0, -0.25)),
                Triple("Firm / Max Draw", "Deep backspin — requires enough speed over distance so draw doesn't fade to roll", TipOffset(0.0, -0.42)),
                Triple("Controlled Follow + 1/2-Tip Left", "Mixed high-left english to widen/check rail rebound", TipOffset(-0.18, 0.22)),
                Triple("Controlled Follow + 1/2-Tip Right", "Mixed high-right english to widen/check rail rebound", TipOffset(0.18, 0.22)),
                Triple("Controlled Draw + 1/2-Tip Left", "Mixed low-left english for angled pullback off cushions", TipOffset(-0.18, -0.24)),
                Triple("Controlled Draw + 1/2-Tip Right", "Mixed low-right english for angled pullback off cushions", TipOffset(0.18, -0.24)),
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
    activeSpeedUnits: Double,
    speedUnitsOverride: Double?,
    lockedTargetBallNumber: Int?,
    hasOverrides: Boolean,
    onUpdateTip: (TipOffset?) -> Unit,
    onUpdateSpeedUnits: (Double?) -> Unit,
    onResetDefaults: () -> Unit,
    onClose: () -> Unit,
    compactVertical: Boolean = false,
    cueToObjectDistanceMeters: Double? = null,
    activeAim: AimGeometry? = null,
    activePocket: PocketSpec? = null,
    activeTargetBallNumber: Int? = lockedTargetBallNumber,
    playerProfile: PlayerProfile = PlayerProfile(),
    tableSpec: TableSpec = TableSpec.nineFootPool(),
    modifier: Modifier = Modifier,
) {
    val snappedSpeed = PlayerProfile.snapSpeedUnits(activeSpeedUnits)
    val formattedSpeed = PlayerProfile.formatSpeedUnits(snappedSpeed)
    val speedWord = sandboxSpeedLabel(snappedSpeed)
    val spinMixSummary = activeTip.describeSpinLabel(short = compactVertical)
    val cueSpeedMps = playerProfile.speedForUnits(snappedSpeed)
    val shotCoachingNote = remember(
        activeTip,
        snappedSpeed,
        cueToObjectDistanceMeters,
        activeAim,
        activePocket,
        activeTargetBallNumber,
        playerProfile,
        tableSpec,
    ) {
        when {
            activeAim != null && activePocket != null -> SpinPhysics.describeShotPowerAndSpin(
                tip = activeTip,
                cueSpeedMps = cueSpeedMps,
                aim = activeAim,
                pocket = activePocket,
                table = tableSpec,
                targetBallNumber = activeTargetBallNumber,
            )
            cueToObjectDistanceMeters != null -> SpinPhysics.describeImpactTransition(
                tip = activeTip,
                cueSpeedMps = cueSpeedMps,
                cueToGhostDistanceMeters = cueToObjectDistanceMeters,
                table = tableSpec,
            )
            else -> null
        }
    }
    val isUnderpoweredShot = shotCoachingNote?.contains("stops short", ignoreCase = true) == true

    val spinPresets = listOf(
        "Auto" to null,
        "Center" to TipOffset(sideRatio = 0.0, verticalRatio = 0.0),
        "Drag Stun" to TipOffset(sideRatio = 0.0, verticalRatio = -0.14),
        "Ctrl Follow" to TipOffset(sideRatio = 0.0, verticalRatio = 0.20),
        "Firm Follow" to TipOffset.fromPreset(CueBallSpinPreset.FOLLOW),
        "Ctrl Draw" to TipOffset(sideRatio = 0.0, verticalRatio = -0.25),
        "Max Draw" to TipOffset(sideRatio = 0.0, verticalRatio = -0.44),
        "1/2 Left" to TipOffset(sideRatio = -0.18, verticalRatio = 0.0),
        "1/2 Right" to TipOffset(sideRatio = 0.18, verticalRatio = 0.0),
        "Hi-Left" to TipOffset(sideRatio = -0.18, verticalRatio = 0.22),
        "Hi-Right" to TipOffset(sideRatio = 0.18, verticalRatio = 0.22),
        "Lo-Left" to TipOffset(sideRatio = -0.18, verticalRatio = -0.24),
        "Lo-Right" to TipOffset(sideRatio = 0.18, verticalRatio = -0.24),
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
            // Fixed-height header so mode/ball transitions never shift the cue ball canvas
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Cue Ball Sandbox",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when {
                            lockedTargetBallNumber != null -> "Testing Ball #$lockedTargetBallNumber live"
                            activeTargetBallNumber != null -> "Ball #$activeTargetBallNumber • drag tip or speed"
                            else -> "Drag tip or speed to test live"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (lockedTargetBallNumber != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                            maxLines = 1,
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(78.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "Spin: $spinMixSummary",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = shotCoachingNote ?: "Drag tip or speed to preview live cue & ball travel",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUnderpoweredShot) {
                                RunoutColors.WarningAmber
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    SpeedHalfStepControlBlock(
                        snappedSpeed = snappedSpeed,
                        formattedSpeed = formattedSpeed,
                        speedWord = speedWord,
                        isAuto = speedUnitsOverride == null,
                        onUpdateSpeedUnits = onUpdateSpeedUnits,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(156.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CueTipSelectorCanvas(
                            tipOffset = activeTip,
                            defaultTipOffset = if (customTipOffset != null) defaultTip else null,
                            onUpdateTip = { onUpdateTip(it) },
                            modifier = Modifier.size(128.dp),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(84.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = "Spin: $spinMixSummary",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = shotCoachingNote ?: "Drag tip or speed to preview live cue & ball travel",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isUnderpoweredShot) {
                                    RunoutColors.WarningAmber
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SpeedHalfStepControlBlock(
                            snappedSpeed = snappedSpeed,
                            formattedSpeed = formattedSpeed,
                            speedWord = speedWord,
                            isAuto = speedUnitsOverride == null,
                            onUpdateSpeedUnits = onUpdateSpeedUnits,
                        )
                    }
                }
            }

            // Quick spin presets (fixed height so chip selection never alters card size)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compactVertical) 38.dp else 42.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
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
                        modifier = Modifier.height(if (compactVertical) 36.dp else 40.dp),
                        label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                    )
                }
            }

            // Auto speed + Reset to Plan Defaults (fixed height)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compactVertical) 40.dp else 44.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = speedUnitsOverride == null,
                    onClick = { onUpdateSpeedUnits(null) },
                    modifier = Modifier.height(if (compactVertical) 38.dp else 44.dp),
                    label = { Text("Auto Speed", style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                )
                OutlinedButton(
                    onClick = onResetDefaults,
                    enabled = hasOverrides,
                    modifier = Modifier
                        .weight(1f)
                        .height(if (compactVertical) 38.dp else 44.dp),
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

@Composable
private fun SpeedHalfStepControlBlock(
    snappedSpeed: Double,
    formattedSpeed: String,
    speedWord: String,
    isAuto: Boolean,
    onUpdateSpeedUnits: (Double?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Speed: $formattedSpeed/10 ($speedWord)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = RunoutColors.SurfaceLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.clickable(enabled = snappedSpeed > PlayerProfile.MIN_SPEED_UNITS + 0.01) {
                        onUpdateSpeedUnits(PlayerProfile.snapSpeedUnits(snappedSpeed - 0.5))
                    },
                ) {
                    Text(
                        text = "−.5",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = RunoutColors.SurfaceLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.clickable(enabled = snappedSpeed < PlayerProfile.MAX_SPEED_UNITS - 0.01) {
                        onUpdateSpeedUnits(PlayerProfile.snapSpeedUnits(snappedSpeed + 0.5))
                    },
                ) {
                    Text(
                        text = "+.5",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
                Text(
                    text = if (isAuto) "Auto" else "Manual",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAuto) MaterialTheme.colorScheme.secondary else RunoutColors.WarningAmber,
                    maxLines = 1,
                )
            }
        }
        Slider(
            value = snappedSpeed.toFloat(),
            onValueChange = { raw ->
                onUpdateSpeedUnits(PlayerProfile.snapSpeedUnits(raw.toDouble()))
            },
            valueRange = 0.5f..10.0f,
            steps = 18,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
        )
    }
}

private fun sandboxSpeedLabel(units: Double): String = when {
    units <= 1.5 -> "Touch"
    units <= 3.0 -> "Gentle"
    units <= 5.5 -> "Medium"
    units <= 7.5 -> "Firm"
    else -> "Power"
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
