package com.runout.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.runout.app.calibration.SampleCalibrationSessions
import com.runout.app.ui.AssumedSurfaceControlCard
import com.runout.app.ui.CalibrationScreen
import com.runout.app.ui.CueTipSelectorCanvas
import com.runout.app.ui.ExtrasAndToolsScreen
import com.runout.app.ui.PhotoCaptureAndConfirmScreen
import com.runout.app.ui.PlayerProfileScreen
import com.runout.app.ui.PoolTableCanvas
import com.runout.app.ui.RulesAndFaqScreen
import com.runout.app.ui.SampleLayouts
import com.runout.app.ui.SampleScenario
import com.runout.app.ui.ballBaseColor
import com.runout.app.ui.spinColor
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
import com.runout.engine.planner.MultiShotPlanner
import com.runout.engine.planner.OneShotPlanner
import com.runout.engine.planner.PlannedShotStep
import com.runout.engine.planner.PlanningPauseReason
import com.runout.engine.planner.PlanningReadinessResult
import com.runout.engine.planner.PlanningReadinessValidator
import com.runout.engine.planner.ProblemBallReason
import com.runout.engine.planner.RunoutPlan
import com.runout.engine.planner.ShotRecommendation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

private const val METERS_TO_CM: Double = 100.0
private const val METERS_TO_FEET: Double = 3.28084

private data class TableSizeOption(
    val label: String,
    val specName: String,
    val createSpec: () -> TableSpec,
)

private data class GameOption(
    val label: String,
    val rulesState: GameRulesState,
)

private val TABLE_SIZE_OPTIONS = listOf(
    TableSizeOption("9-ft Pool", "9 ft Pool") { TableSpec.nineFootPool() },
    TableSizeOption("8-ft Pool", "8 ft Pool") { TableSpec.eightFootPool() },
    TableSizeOption("7-ft Bar Box", "7 ft Pool") { TableSpec.sevenFootPool() },
    TableSizeOption("12-ft Snooker", "12 ft Snooker") { TableSpec.fullSizeSnooker() },
)

private val PLANNER_SURFACE_PRESET_LABELS: Map<AssumedSurfacePreset, String> = mapOf(
    AssumedSurfacePreset.PRO_BROADCAST_FAST to "Pro Fast",
    AssumedSurfacePreset.STANDARD_POOL_HALL to "Standard",
    AssumedSurfacePreset.SLOW_BAR_BOX_HEAVY to "Slow Bar",
    AssumedSurfacePreset.SNOOKER_DIRECTIONAL to "Snooker",
)

private val GAME_OPTIONS = listOf(
    GameOption("8-Ball Solids", GameRulesState(GameType.EIGHT_BALL, EightBallGroup.SOLIDS)),
    GameOption("8-Ball Stripes", GameRulesState(GameType.EIGHT_BALL, EightBallGroup.STRIPES)),
    GameOption("8-Ball Open", GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN)),
    GameOption("9-Ball", GameRulesState(GameType.NINE_BALL)),
    GameOption("10-Ball", GameRulesState(GameType.TEN_BALL)),
)

private enum class AppTab(val title: String) {
    TABLE_AND_PLANNER("Table & Planner"),
    PHOTO_SCAN("Photo Scan"),
    CALIBRATION("Calibration"),
    TOOLS_AND_COACH("Tools & Coach"),
    PLAYER_AND_BALL_SET("Player & Ball Set"),
    RULES_AND_FAQ("Rules & FAQ"),
}

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

private data class TipQuickPreset(
    val label: String,
    val tip: TipOffset?,
)

private val TIP_QUICK_PRESETS = listOf(
    TipQuickPreset("Auto Spin", null),
    TipQuickPreset("Center Stun", TipOffset(sideRatio = 0.0, verticalRatio = 0.0)),
    TipQuickPreset("Top Follow", TipOffset.fromPreset(CueBallSpinPreset.FOLLOW)),
    TipQuickPreset("Bottom Draw", TipOffset.fromPreset(CueBallSpinPreset.DRAW)),
    TipQuickPreset("Left English", TipOffset(sideRatio = -0.30, verticalRatio = 0.0)),
    TipQuickPreset("Right English", TipOffset(sideRatio = 0.30, verticalRatio = 0.0)),
)

private val PracticeDarkColorScheme = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    secondary = Color(0xFF4ADE80),
    onSecondary = Color(0xFF052E16),
    tertiary = Color(0xFFFFD54F),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
)

@Composable
fun App() {
    val scenarios = remember { SampleLayouts.all }
    var activeTab by remember { mutableStateOf(AppTab.TABLE_AND_PLANNER) }
    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    var currentLayout by remember { mutableStateOf(scenarios[0].layout) }
    var rulesState by remember { mutableStateOf(scenarios[0].rulesState) }
    var playerProfile by remember { mutableStateOf(PlayerProfile()) }
    var ballSetProfile by remember { mutableStateOf(BallSetProfile.standardPool()) }
    var savedTableProfiles by remember { mutableStateOf(SampleCalibrationSessions.defaultSavedProfiles()) }
    var activeTableProfileId by remember { mutableStateOf<String?>(null) }
    var speedScaleOverride by remember { mutableStateOf<Int?>(null) }
    var selectedHorizon by remember { mutableStateOf(PlanningHorizonOption.THREE_BALL) }
    var showHeatZone by remember { mutableStateOf(false) }
    var showLandingCloud by remember { mutableStateOf(true) }
    var customTipOffset by remember { mutableStateOf<TipOffset?>(null) }

    val planningReadiness = remember(currentLayout, rulesState, ballSetProfile) {
        PlanningReadinessValidator.evaluate(
            layout = currentLayout,
            rulesState = rulesState,
            ballSet = ballSetProfile,
        )
    }

    val runoutPlans = remember(
        currentLayout,
        rulesState,
        playerProfile,
        selectedHorizon,
        customTipOffset,
        speedScaleOverride,
        planningReadiness.isReady,
    ) {
        if (!planningReadiness.isReady) return@remember emptyList()
        MultiShotPlanner.planRunout(
            layout = currentLayout,
            rulesState = rulesState,
            playerProfile = playerProfile,
            horizonShots = selectedHorizon.horizonShots,
            deepAnalysis = selectedHorizon.deepAnalysis,
            customFirstShotTip = customTipOffset,
            customFirstShotSpeedScale = speedScaleOverride,
        )
    }

    val rankedShots = remember(
        currentLayout,
        rulesState,
        playerProfile,
        speedScaleOverride,
        planningReadiness.isReady,
    ) {
        if (!planningReadiness.isReady) return@remember emptyList()
        OneShotPlanner.rankShots(
            layout = currentLayout,
            rulesState = rulesState,
            playerProfile = playerProfile,
            cueSpeedScaleOverride = speedScaleOverride,
        )
    }

    var selectedPlanIndex by remember { mutableIntStateOf(0) }
    var selectedShotIndex by remember { mutableIntStateOf(0) }
    var visibleSpins by remember {
        mutableStateOf(
            setOf(
                CueBallSpinPreset.STUN,
                CueBallSpinPreset.FOLLOW,
                CueBallSpinPreset.DRAW,
            ),
        )
    }

    val safePlanIndex = selectedPlanIndex.coerceIn(0, (runoutPlans.size - 1).coerceAtLeast(0))
    val activePlan = runoutPlans.getOrNull(safePlanIndex)
    val safeShotIndex = selectedShotIndex.coerceIn(0, (rankedShots.size - 1).coerceAtLeast(0))
    val selectedShot = rankedShots.getOrNull(safeShotIndex)

    MaterialTheme(colorScheme = PracticeDarkColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScrollableTabRow(
                    selectedTabIndex = activeTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    edgePadding = 8.dp,
                ) {
                    AppTab.entries.forEach { tab ->
                        val isSelected = activeTab == tab
                        Tab(
                            selected = isSelected,
                            onClick = { activeTab = tab },
                            text = {
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (activeTab) {
                        AppTab.TABLE_AND_PLANNER -> RunoutPracticeScreen(
                            scenarios = scenarios,
                            selectedPresetIndex = selectedPresetIndex,
                            onSelectPreset = { index ->
                                val chosen = scenarios[index]
                                val activeProfile = savedTableProfiles.firstOrNull { it.id == activeTableProfileId }
                                selectedPresetIndex = index
                                currentLayout = if (activeProfile != null) {
                                    SampleLayouts.resizeTableLayout(chosen.layout, activeProfile.toEffectiveTableSpec())
                                } else {
                                    chosen.layout
                                }
                                rulesState = chosen.rulesState
                                customTipOffset = null
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                            },
                            currentLayout = currentLayout,
                            onResizeTable = { newSpec ->
                                activeTableProfileId = null
                                currentLayout = SampleLayouts.resizeTableLayout(
                                    layout = currentLayout,
                                    newSpec = newSpec.withClothParams(currentLayout.table.cloth),
                                )
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                            },
                            rulesState = rulesState,
                            onSelectRules = { nextRules ->
                                rulesState = nextRules
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                            },
                            playerProfile = playerProfile,
                            ballSetProfile = ballSetProfile,
                            speedScaleOverride = speedScaleOverride,
                            onSelectSpeedScale = { nextScale ->
                                speedScaleOverride = nextScale
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                            },
                            onToggleBall = { ballNumber ->
                                currentLayout = SampleLayouts.toggleObjectBall(currentLayout, ballNumber)
                            },
                            onAddMissingBall = { ballNumber ->
                                currentLayout = SampleLayouts.addMissingBall(currentLayout, ballNumber)
                            },
                            onMoveBall = { ballNumber, newPosition ->
                                currentLayout = SampleLayouts.moveBallSafely(currentLayout, ballNumber, newPosition)
                            },
                            planningReadiness = planningReadiness,
                            rankedShots = rankedShots,
                            selectedShotIndex = safeShotIndex,
                            onSelectShot = { index -> selectedShotIndex = index },
                            selectedShot = selectedShot,
                            visibleSpins = visibleSpins,
                            onToggleSpin = { spin ->
                                visibleSpins = if (spin in visibleSpins) {
                                    visibleSpins - spin
                                } else {
                                    visibleSpins + spin
                                }
                            },
                            selectedHorizon = selectedHorizon,
                            onSelectHorizon = { nextHorizon ->
                                selectedHorizon = nextHorizon
                                selectedPlanIndex = 0
                            },
                            showHeatZone = showHeatZone,
                            onToggleHeatZone = { showHeatZone = !showHeatZone },
                            showLandingCloud = showLandingCloud,
                            onToggleLandingCloud = { showLandingCloud = !showLandingCloud },
                            customTipOffset = customTipOffset,
                            onUpdateCustomTip = { nextTip ->
                                customTipOffset = nextTip
                                selectedPlanIndex = 0
                            },
                            runoutPlans = runoutPlans,
                            selectedPlanIndex = safePlanIndex,
                            onSelectPlan = { index -> selectedPlanIndex = index },
                            activePlan = activePlan,
                            onUpdateClothParams = { updatedCloth ->
                                activeTableProfileId = null
                                currentLayout = currentLayout.copy(
                                    table = currentLayout.table.withClothParams(updatedCloth),
                                )
                            },
                        )

                        AppTab.PHOTO_SCAN -> PhotoCaptureAndConfirmScreen(
                            ballSet = ballSetProfile,
                            lastPlayedGameType = rulesState.gameType,
                            onConfirmScannedLayout = { scannedLayout, scannedRules ->
                                activeTableProfileId = null
                                currentLayout = scannedLayout
                                rulesState = scannedRules
                                selectedPresetIndex = -1
                                customTipOffset = null
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                                activeTab = AppTab.TABLE_AND_PLANNER
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        AppTab.CALIBRATION -> CalibrationScreen(
                            savedProfiles = savedTableProfiles,
                            activeProfileId = activeTableProfileId,
                            onSelectOrSaveProfile = { profile ->
                                val existingIndex = savedTableProfiles.indexOfFirst { it.id == profile.id }
                                savedTableProfiles = if (existingIndex >= 0) {
                                    savedTableProfiles.toMutableList().apply { this[existingIndex] = profile }
                                } else {
                                    savedTableProfiles + profile
                                }
                                activeTableProfileId = profile.id
                                currentLayout = SampleLayouts.resizeTableLayout(
                                    layout = currentLayout,
                                    newSpec = profile.toEffectiveTableSpec(),
                                )
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                                activeTab = AppTab.TABLE_AND_PLANNER
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        AppTab.TOOLS_AND_COACH -> ExtrasAndToolsScreen(
                            playerProfile = playerProfile,
                            onLoadLayoutIntoPlanner = { layout, rules ->
                                activeTableProfileId = null
                                currentLayout = layout
                                rulesState = rules
                                selectedPresetIndex = -1
                                customTipOffset = null
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                                activeTab = AppTab.TABLE_AND_PLANNER
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                        AppTab.PLAYER_AND_BALL_SET -> PlayerProfileScreen(
                            profile = playerProfile,
                            ballSet = ballSetProfile,
                            table = currentLayout.table,
                            onUpdateProfile = { playerProfile = it },
                            onUpdateBallSet = { ballSetProfile = it },
                            modifier = Modifier.fillMaxSize(),
                        )

                        AppTab.RULES_AND_FAQ -> RulesAndFaqScreen(
                            modifier = Modifier.fillMaxSize(),
                            onLoadExampleLayout = { layout, rules ->
                                activeTableProfileId = null
                                currentLayout = layout
                                rulesState = rules
                                selectedPresetIndex = -1
                                customTipOffset = null
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                                activeTab = AppTab.TABLE_AND_PLANNER
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RunoutPracticeScreen(
    scenarios: List<SampleScenario>,
    selectedPresetIndex: Int,
    onSelectPreset: (Int) -> Unit,
    currentLayout: TableLayout,
    onResizeTable: (TableSpec) -> Unit,
    rulesState: GameRulesState,
    onSelectRules: (GameRulesState) -> Unit,
    playerProfile: PlayerProfile,
    ballSetProfile: BallSetProfile,
    speedScaleOverride: Int?,
    onSelectSpeedScale: (Int?) -> Unit,
    onToggleBall: (Int) -> Unit,
    onMoveBall: (ballNumber: Int, newPosition: com.runout.engine.math.Vec2) -> Unit,
    rankedShots: List<ShotRecommendation>,
    selectedShotIndex: Int,
    onSelectShot: (Int) -> Unit,
    selectedShot: ShotRecommendation?,
    visibleSpins: Set<CueBallSpinPreset>,
    onToggleSpin: (CueBallSpinPreset) -> Unit,
    modifier: Modifier = Modifier,
    planningReadiness: PlanningReadinessResult = PlanningReadinessValidator.evaluate(
        layout = currentLayout,
        rulesState = rulesState,
        ballSet = ballSetProfile,
    ),
    onAddMissingBall: (Int) -> Unit = onToggleBall,
    selectedHorizon: PlanningHorizonOption = PlanningHorizonOption.THREE_BALL,
    onSelectHorizon: (PlanningHorizonOption) -> Unit = {},
    showHeatZone: Boolean = false,
    onToggleHeatZone: () -> Unit = {},
    showLandingCloud: Boolean = true,
    onToggleLandingCloud: () -> Unit = {},
    customTipOffset: TipOffset? = null,
    onUpdateCustomTip: (TipOffset?) -> Unit = {},
    runoutPlans: List<RunoutPlan> = emptyList(),
    selectedPlanIndex: Int = 0,
    onSelectPlan: (Int) -> Unit = {},
    activePlan: RunoutPlan? = runoutPlans.getOrNull(selectedPlanIndex),
    onUpdateClothParams: (ClothParams) -> Unit = {},
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Runout",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Interactive Table Editor & Position Planner",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RulesBadge(
                rulesState = rulesState,
                ballSet = ballSetProfile,
                tableName = currentLayout.table.name,
            )
        }

        if (!planningReadiness.isReady) {
            PlanningPausedCard(
                readiness = planningReadiness,
                rulesState = rulesState,
                ballSet = ballSetProfile,
                onSelectRules = onSelectRules,
                onAddMissingBall = onAddMissingBall,
            )
        }

        // Preset layout chips
        SectionLabel("Presets")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            scenarios.forEachIndexed { index, item ->
                val isSelected = index == selectedPresetIndex
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectPreset(index) },
                    label = {
                        Text(
                            text = item.title,
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

        // Table size selector
        SectionLabel("Table Size")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TABLE_SIZE_OPTIONS.forEach { option ->
                val isSelected = currentLayout.table.name == option.specName
                FilterChip(
                    selected = isSelected,
                    onClick = { onResizeTable(option.createSpec()) },
                    label = {
                        Text(
                            text = option.label,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                )
            }
        }

        // Game & group selector
        SectionLabel("Game & Group")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GAME_OPTIONS.forEach { option ->
                val isSelected = rulesState == option.rulesState
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectRules(option.rulesState) },
                    label = {
                        Text(
                            text = option.label,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                )
            }
        }

        // Planning horizon bar (1-Shot..5-Ball, Deep Run-Out)
        SectionLabel("Planning Horizon")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlanningHorizonOption.entries.forEach { option ->
                val isSelected = option == selectedHorizon
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectHorizon(option) },
                    label = {
                        Text(
                            text = option.label,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.22f),
                        selectedLabelColor = MaterialTheme.colorScheme.secondary,
                    ),
                )
            }
        }

        // Strategy overlay filter chips (Heat Zone, Landing Cloud)
        SectionLabel("Overlays")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = showHeatZone,
                onClick = onToggleHeatZone,
                label = {
                    Text(
                        text = "Heat Zone",
                        fontWeight = if (showHeatZone) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF10B981).copy(alpha = 0.22f),
                    selectedLabelColor = Color(0xFF4ADE80),
                ),
            )
            FilterChip(
                selected = showLandingCloud,
                onClick = onToggleLandingCloud,
                label = {
                    Text(
                        text = "Landing Cloud",
                        fontWeight = if (showLandingCloud) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }

        // Speed scale selector (Auto or 1..10)
        val speedSummary = if (speedScaleOverride == null) {
            "Auto (${playerProfile.name})"
        } else {
            "$speedScaleOverride/10 (${formatOneDecimal(playerProfile.speedForScale(speedScaleOverride))} m/s)"
        }
        SectionLabel("Speed Scale: $speedSummary")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = speedScaleOverride == null,
                onClick = { onSelectSpeedScale(null) },
                label = { Text("Auto") },
            )
            for (scale in 1..10) {
                FilterChip(
                    selected = speedScaleOverride == scale,
                    onClick = { onSelectSpeedScale(scale) },
                    label = { Text("$scale") },
                )
            }
        }

        // Interactive ball toggle chips (+ / - balls 1..15)
        SectionLabel("Object Balls (+ / -)")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (ballNumber in 1..15) {
                val isPresent = currentLayout.ballByNumber(ballNumber) != null
                val dotColor = ballBaseColor(ballNumber, ballSetProfile)
                FilterChip(
                    selected = isPresent,
                    onClick = { onToggleBall(ballNumber) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                    },
                    label = {
                        Text(if (isPresent) "- $ballNumber" else "+ $ballNumber")
                    },
                )
            }
        }

        // Pool table canvas card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PoolTableCanvas(
                    layout = currentLayout,
                    selectedShot = selectedShot,
                    modifier = Modifier.fillMaxWidth(),
                    ballSet = ballSetProfile,
                    onMoveBall = onMoveBall,
                    visibleSpins = visibleSpins,
                    activeRunoutPlan = activePlan,
                    showHeatZone = showHeatZone,
                    showLandingCloud = showLandingCloud,
                )

                Text(
                    text = "Drag any ball on the table to edit positions live",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val heatMap = activePlan?.nextBallHeatZone
                if (showHeatZone && heatMap != null && heatMap.targetBallNumbers.isNotEmpty()) {
                    val targetsText = heatMap.targetBallNumbers.joinToString(", ") { "#$it" }
                    Text(
                        text = "Heat zone for $targetsText: green >= 60%, amber 30–60%, red 5–30%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }

                if (activePlan != null && activePlan.steps.isNotEmpty()) {
                    ActivePlanReadoutBar(plan = activePlan)
                } else if (selectedShot != null) {
                    SelectedShotReadoutBar(shot = selectedShot)
                }
            }
        }

        // Assumed cloth roll & rail density live surface presets + fine-tuning
        AssumedSurfaceControlCard(
            title = "Surface: Assumed Cloth Roll & Rail Density",
            clothParams = currentLayout.table.cloth,
            presetLabels = PLANNER_SURFACE_PRESET_LABELS,
            rollStepperTitle = "Cloth Roll (μr)",
            railStepperTitle = "Rail Bounce (ec)",
            onUpdateClothParams = onUpdateClothParams,
        )

        // Interactive 2D Cue-Tip Spin & English selector + compensation readout
        CueTipEnglishCard(
            customTipOffset = customTipOffset,
            recommendedStep = activePlan?.steps?.firstOrNull(),
            onUpdateCustomTip = onUpdateCustomTip,
        )

        // Problem Balls & Breakouts card
        if (activePlan != null && activePlan.problemBalls.isNotEmpty()) {
            ProblemBallsCard(
                plan = activePlan,
                ballSet = ballSetProfile,
            )
        }

        // Multi-Shot Run-Out Plans list
        Text(
            text = "Multi-Shot Run-Out Plans (${runoutPlans.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (runoutPlans.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    text = if (!planningReadiness.isReady) {
                        "Planning is paused — ${planningReadiness.headline}. Confirm above to generate a run-out plan."
                    } else {
                        "No multi-shot run-out plan found for this layout."
                    },
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            runoutPlans.forEachIndexed { index, plan ->
                RunoutPlanCard(
                    rank = index + 1,
                    plan = plan,
                    ballSet = ballSetProfile,
                    isSelected = index == selectedPlanIndex,
                    onClick = { onSelectPlan(index) },
                )
            }
        }

        // Spin trajectory toggles (90° stun, 30° follow, draw)
        SpinPathToggleRow(
            visibleSpins = visibleSpins,
            recommendedSpin = selectedShot?.recommendedSpin,
            onToggleSpin = onToggleSpin,
        )

        // Ranked One-Shot Geometric Reference list
        Text(
            text = "Ranked Legal Shots (${rankedShots.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        if (rankedShots.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    text = if (!planningReadiness.isReady) {
                        "Planning is paused — ${planningReadiness.headline}."
                    } else {
                        "No direct legal pots available for this layout."
                    },
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            rankedShots.forEachIndexed { index, shot ->
                ShotRecommendationCard(
                    rank = index + 1,
                    shot = shot,
                    ballSet = ballSetProfile,
                    isSelected = index == selectedShotIndex,
                    onClick = { onSelectShot(index) },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PlanningPausedCard(
    readiness: PlanningReadinessResult,
    rulesState: GameRulesState,
    ballSet: BallSetProfile,
    onSelectRules: (GameRulesState) -> Unit,
    onAddMissingBall: (Int) -> Unit,
) {
    val amber = Color(0xFFFBBF24)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = amber.copy(alpha = 0.12f)),
        border = BorderStroke(1.2.dp, amber.copy(alpha = 0.70f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Planning Paused — ${readiness.headline}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = amber,
            )
            Text(
                text = readiness.guidance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (readiness.needsEightBallGroupSelection) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        EightBallGroup.SOLIDS,
                        EightBallGroup.STRIPES,
                        EightBallGroup.OPEN,
                        EightBallGroup.EIGHT_BALL,
                    ).forEach { group ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                onSelectRules(rulesState.copy(eightBallGroup = group))
                            },
                            label = {
                                Text(
                                    text = group.displayNameFor(ballSet),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                        )
                    }
                }
            }

            if (readiness.missingBallNumbers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    readiness.missingBallNumbers.forEach { missingNum ->
                        val label = if (missingNum == 0) "Add Cue Ball (#0)" else "Add Ball #$missingNum"
                        FilterChip(
                            selected = false,
                            onClick = { onAddMissingBall(missingNum) },
                            label = {
                                Text(
                                    text = label,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                        )
                    }
                    if (PlanningPauseReason.MISSING_END_BALL in readiness.pauseReasons) {
                        GameType.entries
                            .filter { it != rulesState.gameType }
                            .forEach { altGame ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        val nextGroup = if (altGame == GameType.EIGHT_BALL) {
                                            EightBallGroup.UNSET
                                        } else {
                                            EightBallGroup.OPEN
                                        }
                                        onSelectRules(GameRulesState(altGame, nextGroup))
                                    },
                                    label = { Text("Switch to ${altGame.displayName}") },
                                )
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesBadge(
    rulesState: GameRulesState,
    ballSet: BallSetProfile,
    tableName: String,
) {
    val groupSuffix = if (rulesState.gameType == GameType.EIGHT_BALL) {
        " • ${rulesState.eightBallGroup.displayNameFor(ballSet)}"
    } else {
        ""
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "${rulesState.gameType.displayName}$groupSuffix • $tableName",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SelectedShotReadoutBar(shot: ShotRecommendation) {
    val cutDeg = formatOneDecimal(abs(radiansToDegrees(shot.aim.cutAngleRadians)))
    val cueDistText = formatDistanceMeters(shot.aim.cueToGhostDistanceMeters)
    val obDistText = formatDistanceMeters(shot.aim.objectToPocketDistanceMeters)
    val scratchSpins = shot.cueBallPaths.values.filter { it.hitsScratchPocket != null }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatShotSummary(shot),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Cut $cutDeg° • Speed ${shot.recommendedSpeedScale}/10",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            text = "Cue → Ghost: $cueDistText   •   Object → Pocket: $obDistText",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (shot.nextTargetBallNumber != null) {
            Text(
                text = "Next shape: Ball #${shot.nextTargetBallNumber}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (scratchSpins.isNotEmpty()) {
            val warningText = scratchSpins.joinToString(", ") { path ->
                "${path.spin.name} scratches (${path.hitsScratchPocket?.displayName})"
            }
            Text(
                text = "Scratch risk: $warningText",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFF87171),
            )
        }
    }
}

@Composable
private fun SpinPathToggleRow(
    visibleSpins: Set<CueBallSpinPreset>,
    recommendedSpin: CueBallSpinPreset?,
    onToggleSpin: (CueBallSpinPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Cue-Ball Post-Impact Paths",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CueBallSpinPreset.entries.forEach { spin ->
                val isVisible = spin in visibleSpins
                val color = spinColor(spin)
                val label = when (spin) {
                    CueBallSpinPreset.STUN -> "STUN (90° rule)"
                    CueBallSpinPreset.FOLLOW -> "FOLLOW (30° rule)"
                    CueBallSpinPreset.DRAW -> "DRAW (Backspin)"
                }
                val suffix = if (spin == recommendedSpin) " ★" else ""
                FilterChip(
                    selected = isVisible,
                    onClick = { onToggleSpin(spin) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                    },
                    label = {
                        Text(
                            text = "$label$suffix",
                            fontWeight = if (spin == recommendedSpin) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = color.copy(alpha = 0.18f),
                        selectedLabelColor = color,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ShotRecommendationCard(
    rank: Int,
    shot: ShotRecommendation,
    ballSet: BallSetProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    val cutAngleDeg = formatOneDecimal(abs(radiansToDegrees(shot.aim.cutAngleRadians)))
    val cueToGhostText = formatDistanceMeters(shot.aim.cueToGhostDistanceMeters)
    val objectToPocketText = formatDistanceMeters(shot.aim.objectToPocketDistanceMeters)
    val ratingText = formatOneDecimal(shot.score * 10.0)
    val recSpinColor = spinColor(shot.recommendedSpin)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 1.8.dp else 1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(ballBaseColor(shot.targetBall.number, ballSet)),
                    )
                    Text(
                        text = "#$rank: Ball ${shot.targetBall.number} -> ${shot.pocket.id.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ) {
                    Text(
                        text = "Rating $ratingText / 10",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = "Cut: $cutAngleDeg°  •  Speed: ${shot.recommendedSpeedScale}/10  •  Cue→Ghost: $cueToGhostText  •  OB→Pocket: $objectToPocketText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(recSpinColor),
                )
                Text(
                    text = "Spin: ${shot.recommendedSpin.name}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = recSpinColor,
                )
                if (shot.nextTargetBallNumber != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• Next shape: Ball #${shot.nextTargetBallNumber}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                val scratchRisks = shot.cueBallPaths.values.filter { it.hitsScratchPocket != null }
                if (scratchRisks.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "• Avoid ${scratchRisks.joinToString("/") { it.spin.name }} (scratch)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFF87171),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivePlanReadoutBar(plan: RunoutPlan) {
    val firstStep = plan.steps.firstOrNull() ?: return
    val clearPct = formatPercentOneDecimal(plan.cumulativeRunoutProbability)
    val makePct = formatPercentInt(firstStep.uncertainty.makeProbability)
    val spreadCm = (firstStep.uncertainty.landingSpreadRadiusMeters * METERS_TO_CM).roundToInt()
    val cutDeg = formatOneDecimal(abs(radiansToDegrees(firstStep.compensatedAim.geometricAim.cutAngleRadians)))

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Step 1: Ball #${firstStep.targetBall.number} -> ${firstStep.pocketId.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Run-Out P(clear): $clearPct%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Text(
            text = "Cut $cutDeg° • P(make) $makePct% • Speed ${firstStep.recommendedSpeedScale}/10 • 95% spread ±$spreadCm cm",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (plan.steps.size >= 2) {
            val sequenceLabel = plan.steps.joinToString(" -> ") { "#${it.targetBall.number}" }
            Text(
                text = "Sequence: $sequenceLabel",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CueTipEnglishCard(
    customTipOffset: TipOffset?,
    recommendedStep: PlannedShotStep?,
    onUpdateCustomTip: (TipOffset?) -> Unit,
) {
    val displayedTip = customTipOffset ?: recommendedStep?.recommendedTip ?: TipOffset()
    val compAim = recommendedStep?.compensatedAim

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
                Column {
                    Text(
                        text = "2D Cue-Tip Spin & English",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (customTipOffset == null) {
                            "Auto Spin active (tap or drag cue face to override)"
                        } else {
                            "Manual Tip Override (${formatTipDescription(displayedTip)})"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ) {
                    Text(
                        text = if (customTipOffset == null) "AUTO" else "MANUAL",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CueTipSelectorCanvas(
                    tipOffset = displayedTip,
                    onUpdateTip = { newTip -> onUpdateCustomTip(newTip) },
                    modifier = Modifier.size(144.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Offset: ${formatSignedTwoDecimals(displayedTip.clampedSideRatio)}R side, ${formatSignedTwoDecimals(displayedTip.clampedVerticalRatio)}R vert",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (compAim != null) {
                        CompensationMetricRow(
                            label = "Squirt",
                            valueText = "${formatSignedDegrees(compAim.squirtAngleRadians)}°",
                        )
                        CompensationMetricRow(
                            label = "Swerve",
                            valueText = "${formatSignedDegrees(compAim.swerveAngleRadians)}°",
                        )
                        CompensationMetricRow(
                            label = "Cut / Spin Throw",
                            valueText = "${formatSignedDegrees(compAim.cutThrowAngleRadians)}° / ${formatSignedDegrees(compAim.spinThrowAngleRadians)}°",
                        )
                        CompensationMetricRow(
                            label = "Total Throw",
                            valueText = "${formatSignedDegrees(compAim.totalThrowAngleRadians)}°",
                        )
                        CompensationMetricRow(
                            label = "Net Cue Adjust",
                            valueText = "${formatSignedDegrees(compAim.netCueAimAdjustmentRadians)}°",
                            highlight = true,
                        )
                    } else {
                        Text(
                            text = "Select a playable layout to inspect squirt, swerve, and throw compensation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Quick spin & english presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TIP_QUICK_PRESETS.forEach { preset ->
                    val isSelected = if (preset.tip == null) {
                        customTipOffset == null
                    } else {
                        customTipOffset != null &&
                            abs(customTipOffset.clampedSideRatio - preset.tip.clampedSideRatio) < 0.04 &&
                            abs(customTipOffset.clampedVerticalRatio - preset.tip.clampedVerticalRatio) < 0.04
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { onUpdateCustomTip(preset.tip) },
                        label = {
                            Text(
                                text = preset.label,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompensationMetricRow(
    label: String,
    valueText: String,
    highlight: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
            color = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ProblemBallsCard(
    plan: RunoutPlan,
    ballSet: BallSetProfile,
) {
    val breakoutSteps = plan.steps.mapNotNull { step ->
        step.breakoutOpportunity?.let { step to it }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1618)),
        border = BorderStroke(1.dp, Color(0xFFFF6B4A).copy(alpha = 0.65f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Problem Balls & Breakouts (${plan.problemBalls.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFFF8A65),
            )

            plan.problemBalls.forEach { report ->
                val reasonText = report.reasons.joinToString(", ") { formatProblemReason(it) }
                val insuranceText = if (report.recommendedInsuranceBallNumber != null) {
                    "Insurance ball: #${report.recommendedInsuranceBallNumber}"
                } else {
                    "No open insurance ball"
                }
                val neighbourSuffix = if (report.blockingBallNumbers.isNotEmpty()) {
                    " • Neighbours: ${report.blockingBallNumbers.joinToString(", ") { "#$it" }}"
                } else {
                    ""
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(ballBaseColor(report.ballNumber, ballSet)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Ball #${report.ballNumber}: $reasonText",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "$insuranceText$neighbourSuffix",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (breakoutSteps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                breakoutSteps.forEach { (step, breakout) ->
                    val insNote = if (breakout.insuranceBallNumber != null) {
                        " (with #${breakout.insuranceBallNumber} insurance)"
                    } else {
                        ""
                    }
                    Text(
                        text = "Breakout route on Step ${step.stepNumber}: pot #${step.targetBall.number} -> ${step.pocketId.displayName} to open #${breakout.problemBallNumber}$insNote",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunoutPlanCard(
    rank: Int,
    plan: RunoutPlan,
    ballSet: BallSetProfile,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val clearProbText = formatPercentOneDecimal(plan.cumulativeRunoutProbability)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 1.8.dp else 1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Plan #$rank (${plan.steps.size}-Ball Sequence)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                ) {
                    Text(
                        text = "Run-Out P(clear): $clearProbText%",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            plan.steps.forEach { step ->
                val makePct = formatPercentInt(step.uncertainty.makeProbability)
                val tipSummary = formatTipDescription(step.recommendedTip)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(ballBaseColor(step.targetBall.number, ballSet)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Step ${step.stepNumber}: Ball #${step.targetBall.number} -> ${step.pocketId.displayName} | P(make): $makePct% | Speed: ${step.recommendedSpeedScale}/10",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val breakout = step.breakoutOpportunity
                        val breakoutSuffix = if (breakout != null) {
                            " • Breaks out #${breakout.problemBallNumber}"
                        } else {
                            ""
                        }
                        Text(
                            text = "Tip: $tipSummary$breakoutSuffix",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (plan.explanationReasons.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    plan.explanationReasons.forEach { reason ->
                        Text(
                            text = "• $reason",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Converts an SI angle in radians to degrees at the UI boundary.
 */
private fun radiansToDegrees(rad: Double): Double = rad * 180.0 / PI

/**
 * Formats a player-facing shot summary string from SI [ShotRecommendation] data at the UI boundary.
 */
private fun formatShotSummary(shot: ShotRecommendation): String {
    val cutDegreesRounded = round(abs(radiansToDegrees(shot.aim.cutAngleRadians))).toInt()
    return "Ball ${shot.targetBall.number} -> ${shot.pocket.id.displayName} " +
        "($cutDegreesRounded° cut, ${shot.recommendedSpin.name.lowercase()})"
}

/**
 * Converts an SI distance in metres into a player-friendly `cm (ft)` string at the UI boundary.
 */
private fun formatDistanceMeters(meters: Double): String {
    val cm = (meters * METERS_TO_CM).roundToInt()
    val feet = formatOneDecimal(meters * METERS_TO_FEET)
    return "$cm cm ($feet ft)"
}

private fun formatOneDecimal(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    val sign = if (tenths < 0) "-" else ""
    val absTenths = abs(tenths)
    val whole = absTenths / 10
    val frac = absTenths % 10
    return "$sign$whole.$frac"
}

private fun formatSignedTwoDecimals(value: Double): String {
    val hundredths = (value * 100.0).roundToInt()
    val sign = if (hundredths >= 0) "+" else "-"
    val absVal = abs(hundredths)
    val whole = absVal / 100
    val frac = absVal % 100
    val fracStr = if (frac < 10) "0$frac" else "$frac"
    return "$sign$whole.$fracStr"
}

private fun formatSignedDegrees(rad: Double): String {
    val deg = radiansToDegrees(rad)
    val tenths = (deg * 10.0).roundToInt()
    val sign = if (tenths >= 0) "+" else "-"
    val absTenths = abs(tenths)
    val whole = absTenths / 10
    val frac = absTenths % 10
    return "$sign$whole.$frac"
}

private fun formatPercentOneDecimal(probability: Double): String {
    val clamped = (probability.coerceIn(0.0, 1.0)) * 100.0
    return formatOneDecimal(clamped)
}

private fun formatPercentInt(probability: Double): Int =
    (probability.coerceIn(0.0, 1.0) * 100.0).roundToInt()

private fun formatProblemReason(reason: ProblemBallReason): String = when (reason) {
    ProblemBallReason.NO_CLEAR_POCKET -> "No clear pocket"
    ProblemBallReason.CLUSTERED_NEIGHBOURS -> "Clustered neighbours"
    ProblemBallReason.FROZEN_TO_RAIL -> "Frozen to rail"
}

private fun formatTipDescription(tip: TipOffset): String {
    val vertLabel = when {
        tip.clampedVerticalRatio > 0.12 -> "Follow"
        tip.clampedVerticalRatio < -0.12 -> "Draw"
        else -> "Stun"
    }
    val sideLabel = when {
        tip.clampedSideRatio > 0.08 -> "Right"
        tip.clampedSideRatio < -0.08 -> "Left"
        else -> "Center"
    }
    return if (sideLabel == "Center") vertLabel else "$vertLabel + $sideLabel"
}
