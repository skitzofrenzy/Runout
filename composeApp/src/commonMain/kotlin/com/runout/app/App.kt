package com.runout.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.runout.app.calibration.SampleCalibrationSessions
import com.runout.app.storage.SavedSetupPreferences
import com.runout.app.storage.TableSizePreset
import com.runout.app.storage.rememberSetupPreferencesStore
import com.runout.app.tools.EightBallWalkthrough
import com.runout.app.tools.InteractiveTutorial
import com.runout.app.ui.AppDisplayMode
import com.runout.app.ui.AssumedSurfaceControlCard
import com.runout.app.ui.CalibrationScreen
import com.runout.app.ui.CollapsibleSectionCard
import com.runout.app.ui.CueBallHitGuide
import com.runout.app.ui.CueBallSandboxPanel
import com.runout.app.ui.CueTipSelectorCanvas
import com.runout.app.ui.EditBallsSheet
import com.runout.app.ui.ExtrasAndToolsScreen
import com.runout.app.ui.FullscreenTableScreen
import com.runout.app.ui.GameAndTableSetupSheet
import com.runout.app.ui.GlossaryInfoButton
import com.runout.app.ui.HomeLandingScreen
import com.runout.app.ui.LearnHubScreen
import com.runout.app.ui.LearnSubTab
import com.runout.app.ui.LockLandscapeEffect
import com.runout.app.ui.OnboardingFlowScreen
import com.runout.app.ui.PhotoCaptureAndConfirmScreen
import com.runout.app.ui.PlayerProfileScreen
import com.runout.app.ui.PlanningHorizonOption
import com.runout.app.ui.PocketSelectorStrip
import com.runout.app.ui.PoolTableCanvas
import com.runout.app.ui.QuickSpinSheet
import com.runout.app.ui.RulesAndFaqScreen
import com.runout.app.ui.RunoutColors
import com.runout.app.ui.RunoutDarkColorScheme
import com.runout.app.ui.RunoutIcon
import com.runout.app.ui.RunoutSectionHeader
import com.runout.app.ui.RunoutSpacing
import com.runout.app.ui.RunoutVectorIcon
import com.runout.app.ui.SampleLayouts
import com.runout.app.ui.SampleScenario
import com.runout.app.ui.ShotOptionsSheet
import com.runout.app.ui.ShotDifficultyPairRow
import com.runout.app.ui.SimpleProTogglePill
import com.runout.app.ui.StepPlaybackControlBar
import com.runout.app.ui.ToolFocusDestination
import com.runout.app.ui.TutorialDetailPlayerScreen
import com.runout.app.ui.WalkthroughDetailPlayerScreen
import com.runout.app.ui.ballBaseColor
import com.runout.app.ui.computeShotTightness
import com.runout.app.ui.resolveDisplayedLayoutForStep
import com.runout.app.ui.shotTightnessLabel
import com.runout.app.ui.spinColor
import com.runout.engine.math.Vec2
import com.runout.engine.model.AssumedSurfacePreset
import com.runout.engine.model.Ball
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.ClothParams
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.ExperienceLevel
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val METERS_TO_CM: Double = 100.0
private const val METERS_TO_FEET: Double = 3.28084

private val PLANNER_SURFACE_PRESET_LABELS: Map<AssumedSurfacePreset, String> = mapOf(
    AssumedSurfacePreset.PRO_BROADCAST_FAST to "Pro Fast",
    AssumedSurfacePreset.STANDARD_POOL_HALL to "Standard",
    AssumedSurfacePreset.SLOW_BAR_BOX_HEAVY to "Slow Bar",
    AssumedSurfacePreset.SNOOKER_DIRECTIONAL to "Snooker",
)

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

private enum class PrimaryTab(val label: String, val icon: RunoutIcon) {
    HOME("Home", RunoutIcon.HOME),
    PLAN("Plan", RunoutIcon.PLAN),
    LEARN("Learn", RunoutIcon.LEARN),
    RULES("Rules", RunoutIcon.RULES),
}

private sealed interface ActiveRoute {
    data class MainTab(val tab: PrimaryTab) : ActiveRoute
    data object PhotoScan : ActiveRoute
    data class LearnWalkthroughPlayer(val walkthrough: EightBallWalkthrough) : ActiveRoute
    data class LearnTutorialPlayer(val tutorial: InteractiveTutorial) : ActiveRoute
    data object Calibration : ActiveRoute
    data object SnookerCoach : ActiveRoute
    data object BreakSpeedMeter : ActiveRoute
    data object ScorekeeperAndClock : ActiveRoute
    data object PlayerProfileAndBallSet : ActiveRoute
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val scenarios = remember { SampleLayouts.all }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val setupStore = rememberSetupPreferencesStore()
    val initialSavedSetup = remember(setupStore) { setupStore.load() }
    val initialRulesState = initialSavedSetup?.toRulesState() ?: scenarios[0].rulesState
    val initialPresetIdx = initialSavedSetup?.let { saved ->
        scenarios.indexOfFirst { it.rulesState.gameType == saved.gameType }.takeIf { it >= 0 }
    } ?: 0
    val initialBaseScenario = scenarios[initialPresetIdx]
    val initialLayout = if (initialSavedSetup != null) {
        SampleLayouts.resizeTableLayout(
            initialBaseScenario.layout,
            initialSavedSetup.tableSizePreset.toTableSpec(),
        )
    } else {
        initialBaseScenario.layout
    }

    var showOnboarding by remember { mutableStateOf(initialSavedSetup?.hasCompletedSetup != true) }
    var lastPrimaryTab by remember { mutableStateOf(PrimaryTab.HOME) }
    var activeRoute by remember { mutableStateOf<ActiveRoute>(ActiveRoute.MainTab(PrimaryTab.HOME)) }
    var learnSubTab by remember { mutableStateOf(LearnSubTab.WALKTHROUGHS) }
    var displayMode by remember { mutableStateOf(initialSavedSetup?.displayMode ?: AppDisplayMode.SIMPLE) }
    var hasActiveSession by remember { mutableStateOf(false) }

    var selectedPresetIndex by remember { mutableIntStateOf(initialPresetIdx) }
    var currentLayout by remember { mutableStateOf(initialLayout) }
    var rulesState by remember { mutableStateOf(initialRulesState) }
    var playerProfile by remember {
        mutableStateOf(initialSavedSetup?.toPlayerProfile() ?: PlayerProfile())
    }
    var ballSetProfile by remember {
        mutableStateOf(initialSavedSetup?.toBallSetProfile() ?: BallSetProfile.standardPool())
    }
    var savedTableProfiles by remember { mutableStateOf(SampleCalibrationSessions.defaultSavedProfiles()) }
    var activeTableProfileId by remember { mutableStateOf<String?>(null) }

    var speedScaleOverride by remember { mutableStateOf<Int?>(null) }
    var selectedHorizon by remember {
        mutableStateOf(initialSavedSetup?.toPlanningHorizon() ?: PlanningHorizonOption.THREE_BALL)
    }
    var showHeatZone by remember { mutableStateOf(false) }
    var showLandingCloud by remember { mutableStateOf(true) }
    var customTipOffset by remember { mutableStateOf<TipOffset?>(null) }
    var visibleSpins by remember {
        mutableStateOf(
            setOf(
                CueBallSpinPreset.STUN,
                CueBallSpinPreset.FOLLOW,
                CueBallSpinPreset.DRAW,
            ),
        )
    }

    var showGameSetupSheet by remember { mutableStateOf(false) }
    var showEditBallsSheet by remember { mutableStateOf(false) }
    var showShotOptionsSheet by remember { mutableStateOf(false) }
    var showQuickSpinSheet by remember { mutableStateOf(false) }
    var showPlanOverflowMenu by remember { mutableStateOf(false) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }

    var selectedPlanIndex by remember { mutableIntStateOf(0) }
    var selectedShotIndex by remember { mutableIntStateOf(0) }

    var isDraggingBall by remember { mutableStateOf(false) }
    var isPlanning by remember { mutableStateOf(false) }
    var customPocketOverride by remember { mutableStateOf<PocketId?>(null) }
    var showCueSandboxPanel by remember { mutableStateOf(false) }
    var sandboxTargetBallNumber by remember { mutableStateOf<Int?>(null) }
    var defaultTipForActiveShot by remember { mutableStateOf<TipOffset?>(null) }
    var defaultSpeedForActiveShot by remember { mutableStateOf<Int?>(null) }
    var focusedStepIndex by remember { mutableIntStateOf(0) }
    var showAllStepsOverlay by remember { mutableStateOf(false) }
    var isFullscreenTable by remember { mutableStateOf(false) }
    var dismissAutoLandscape by remember { mutableStateOf(false) }
    var runoutPlans by remember { mutableStateOf<List<RunoutPlan>>(emptyList()) }
    var rankedShots by remember { mutableStateOf<List<ShotRecommendation>>(emptyList()) }

    val hasSandboxOverrides = customTipOffset != null ||
        speedScaleOverride != null ||
        customPocketOverride != null ||
        sandboxTargetBallNumber != null

    val planningReadiness = remember(currentLayout, rulesState, ballSetProfile) {
        PlanningReadinessValidator.evaluate(
            layout = currentLayout,
            rulesState = rulesState,
            ballSet = ballSetProfile,
        )
    }

    val playablePockets = remember(currentLayout, rulesState, planningReadiness.isReady) {
        if (!planningReadiness.isReady) {
            emptyList()
        } else {
            val legalTargets = rulesState.legalTargetBalls(currentLayout)
            val validSet = legalTargets
                .flatMap { ball -> MultiShotPlanner.playablePocketsForTarget(currentLayout, ball) }
                .toSet()
            PocketId.entries.filter { it in validSet }
        }
    }

    LaunchedEffect(playablePockets, isDraggingBall) {
        if (!isDraggingBall && customPocketOverride != null && customPocketOverride !in playablePockets) {
            customPocketOverride = null
        }
    }

    LaunchedEffect(
        currentLayout,
        rulesState,
        playerProfile,
        selectedHorizon,
        customTipOffset,
        speedScaleOverride,
        customPocketOverride,
        sandboxTargetBallNumber,
        planningReadiness.isReady,
        isDraggingBall,
    ) {
        if (!planningReadiness.isReady) {
            runoutPlans = emptyList()
            rankedShots = emptyList()
            isPlanning = false
            return@LaunchedEffect
        }
        if (isDraggingBall) {
            // Keep finger dragging at 60 fps and defer heavy multi-shot tree search until release.
            return@LaunchedEffect
        }
        isPlanning = true
        delay(if (showCueSandboxPanel || hasSandboxOverrides) 60L else 150L)
        val layoutSnapshot = currentLayout
        val rulesSnapshot = rulesState
        val profileSnapshot = playerProfile
        val horizonSnapshot = selectedHorizon
        val tipSnapshot = customTipOffset
        val speedSnapshot = speedScaleOverride
        val targetSnapshot = sandboxTargetBallNumber
        val pocketSnapshot = customPocketOverride
        val allowScratchSandbox = tipSnapshot != null || speedSnapshot != null
        val (plans, shots) = withContext(Dispatchers.Default) {
            val computedPlans = MultiShotPlanner.planRunout(
                layout = layoutSnapshot,
                rulesState = rulesSnapshot,
                playerProfile = profileSnapshot,
                horizonShots = horizonSnapshot.horizonShots,
                deepAnalysis = horizonSnapshot.deepAnalysis,
                customFirstShotTip = tipSnapshot,
                customFirstShotSpeedScale = speedSnapshot,
                customFirstShotTargetBallNumber = targetSnapshot,
                customFirstShotPocketId = pocketSnapshot,
                allowHighScratchFirstShot = allowScratchSandbox,
            )
            val computedShots = OneShotPlanner.rankShots(
                layout = layoutSnapshot,
                rulesState = rulesSnapshot,
                playerProfile = profileSnapshot,
                cueSpeedScaleOverride = speedSnapshot,
            )
            computedPlans to computedShots
        }
        runoutPlans = plans
        rankedShots = shots
        if (tipSnapshot == null && speedSnapshot == null && targetSnapshot == null && pocketSnapshot == null) {
            val defaultFirst = plans.firstOrNull()?.steps?.firstOrNull()
            defaultTipForActiveShot = defaultFirst?.recommendedTip
            defaultSpeedForActiveShot = defaultFirst?.recommendedSpeedScale
        }
        isPlanning = false
    }

    val safePlanIndex = selectedPlanIndex.coerceIn(0, (runoutPlans.size - 1).coerceAtLeast(0))
    val activePlan = runoutPlans.getOrNull(safePlanIndex)
    val safeShotIndex = selectedShotIndex.coerceIn(0, (rankedShots.size - 1).coerceAtLeast(0))
    val selectedShot = rankedShots.getOrNull(safeShotIndex)
    val safeFocusedStepIndex = focusedStepIndex.coerceIn(
        0,
        ((activePlan?.steps?.size ?: 1) - 1).coerceAtLeast(0),
    )
    val displayedLayout = remember(currentLayout, activePlan, safeFocusedStepIndex) {
        resolveDisplayedLayoutForStep(
            baseLayout = currentLayout,
            plan = activePlan,
            focusedStepIndex = safeFocusedStepIndex,
        )
    }

    fun navigateToTab(tab: PrimaryTab) {
        lastPrimaryTab = tab
        if (tab != PrimaryTab.PLAN) {
            isFullscreenTable = false
        }
        activeRoute = ActiveRoute.MainTab(tab)
    }

    fun navigateToSubRoute(route: ActiveRoute) {
        val current = activeRoute
        if (current is ActiveRoute.MainTab) {
            lastPrimaryTab = current.tab
        }
        isFullscreenTable = false
        activeRoute = route
    }

    fun handleMoveBall(ballNumber: Int, newPosition: Vec2) {
        val baseLayout = if (safeFocusedStepIndex > 0) displayedLayout else currentLayout
        currentLayout = SampleLayouts.moveBallSafely(baseLayout, ballNumber, newPosition)
        if (safeFocusedStepIndex > 0) focusedStepIndex = 0
        if (customPocketOverride != null) customPocketOverride = null
        sandboxTargetBallNumber = null
        hasActiveSession = true
    }

    fun resetSandboxToPlanDefaults() {
        customTipOffset = null
        speedScaleOverride = null
        customPocketOverride = null
        sandboxTargetBallNumber = null
        focusedStepIndex = 0
        selectedPlanIndex = 0
        selectedShotIndex = 0
    }

    fun handleUpdateSandboxTip(nextTip: TipOffset?) {
        if (nextTip != null && sandboxTargetBallNumber == null) {
            sandboxTargetBallNumber = activePlan?.steps?.firstOrNull()?.targetBall?.number
        }
        customTipOffset = nextTip
        if (nextTip == null && speedScaleOverride == null && customPocketOverride == null) {
            sandboxTargetBallNumber = null
        }
        focusedStepIndex = 0
        selectedPlanIndex = 0
    }

    fun handleUpdateSandboxSpeed(nextScale: Int?) {
        if (nextScale != null && sandboxTargetBallNumber == null) {
            sandboxTargetBallNumber = activePlan?.steps?.firstOrNull()?.targetBall?.number
        }
        speedScaleOverride = nextScale
        if (nextScale == null && customTipOffset == null && customPocketOverride == null) {
            sandboxTargetBallNumber = null
        }
        focusedStepIndex = 0
        selectedPlanIndex = 0
        selectedShotIndex = 0
    }

    fun handleToggleCueSandbox() {
        if (focusedStepIndex > 0) {
            focusedStepIndex = 0
        }
        showCueSandboxPanel = !showCueSandboxPanel
    }

    fun handlePrevStep() {
        focusedStepIndex = (safeFocusedStepIndex - 1).coerceAtLeast(0)
    }

    fun handleNextStep() {
        val maxIdx = ((activePlan?.steps?.size ?: 1) - 1).coerceAtLeast(0)
        focusedStepIndex = (safeFocusedStepIndex + 1).coerceAtMost(maxIdx)
        showAllStepsOverlay = false
    }

    fun handleToggleAllStepsOverlay() {
        if (safeFocusedStepIndex > 0) {
            focusedStepIndex = 0
            showAllStepsOverlay = true
        } else {
            showAllStepsOverlay = !showAllStepsOverlay
        }
    }

    fun handleApplyCurrentStepToTable() {
        if (safeFocusedStepIndex > 0) {
            currentLayout = displayedLayout
            focusedStepIndex = 0
            customPocketOverride = null
            sandboxTargetBallNumber = null
            selectedPlanIndex = 0
            selectedShotIndex = 0
            hasActiveSession = true
        }
    }

    fun performResetWithUndo() {
        val previousLayout = currentLayout
        val previousRules = rulesState
        val previousPresetIdx = selectedPresetIndex
        val previousHasSession = hasActiveSession
        val defaultScenario = scenarios[0]

        currentLayout = SampleLayouts.resizeTableLayout(defaultScenario.layout, previousLayout.table)
        rulesState = defaultScenario.rulesState
        selectedPresetIndex = 0
        customTipOffset = null
        speedScaleOverride = null
        customPocketOverride = null
        sandboxTargetBallNumber = null
        defaultTipForActiveShot = null
        defaultSpeedForActiveShot = null
        focusedStepIndex = 0
        selectedPlanIndex = 0
        selectedShotIndex = 0
        hasActiveSession = false

        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Table layout reset",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                currentLayout = previousLayout
                rulesState = previousRules
                selectedPresetIndex = previousPresetIdx
                hasActiveSession = previousHasSession
                focusedStepIndex = 0
            }
        }
    }

    fun persistSetupPreferences(
        hasCompletedSetup: Boolean = !showOnboarding,
        profile: PlayerProfile = playerProfile,
        ballSet: BallSetProfile = ballSetProfile,
        rules: GameRulesState = rulesState,
        tableSpec: TableSpec = currentLayout.table,
        mode: AppDisplayMode = displayMode,
    ) {
        setupStore.save(
            SavedSetupPreferences(
                hasCompletedSetup = hasCompletedSetup,
                experienceLevel = profile.experienceLevel,
                shaftType = profile.shaftType,
                gameType = rules.gameType,
                useUkBlackball = ballSet.isUkBlackball,
                tableSizePreset = TableSizePreset.fromTableSpec(tableSpec),
                displayMode = mode,
            ),
        )
    }

    MaterialTheme(colorScheme = RunoutDarkColorScheme) {
        if (showOnboarding) {
            OnboardingFlowScreen(
                initialLevel = playerProfile.experienceLevel,
                initialGame = rulesState.gameType,
                initialUseUkBlackball = ballSetProfile.isUkBlackball,
                initialTableSpec = currentLayout.table,
                onComplete = { level, gameType, tableSpec, useUkBlackball, preferredMode ->
                    val nextProfile = PlayerProfile.fromQuestionnaire(
                        experience = level,
                        shaft = playerProfile.shaftType,
                        speedControlRating = if (level == ExperienceLevel.BEGINNER) 4 else 6,
                        spinRating = if (level == ExperienceLevel.ADVANCED) 8 else 5,
                    )
                    val nextBallSet = if (useUkBlackball) {
                        BallSetProfile.ukBlackballPool()
                    } else {
                        BallSetProfile.standardPool()
                    }
                    val nextRules = if (gameType == GameType.EIGHT_BALL) {
                        GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN)
                    } else {
                        GameRulesState(gameType)
                    }
                    val matchingPresetIdx = scenarios.indexOfFirst { it.rulesState.gameType == gameType }
                        .let { if (it >= 0) it else 0 }

                    playerProfile = nextProfile
                    ballSetProfile = nextBallSet
                    displayMode = preferredMode
                    selectedHorizon = if (level == ExperienceLevel.BEGINNER) {
                        PlanningHorizonOption.TWO_BALL
                    } else {
                        PlanningHorizonOption.THREE_BALL
                    }
                    selectedPresetIndex = matchingPresetIdx
                    currentLayout = SampleLayouts.resizeTableLayout(scenarios[matchingPresetIdx].layout, tableSpec)
                    rulesState = nextRules
                    hasActiveSession = false
                    showOnboarding = false
                    persistSetupPreferences(
                        hasCompletedSetup = true,
                        profile = nextProfile,
                        ballSet = nextBallSet,
                        rules = nextRules,
                        tableSpec = tableSpec,
                        mode = preferredMode,
                    )
                    navigateToTab(PrimaryTab.HOME)
                },
                onSkip = {
                    showOnboarding = false
                    persistSetupPreferences(hasCompletedSetup = true)
                    navigateToTab(PrimaryTab.HOME)
                },
            )
            return@MaterialTheme
        }

        val isTopLevel = activeRoute is ActiveRoute.MainTab
        LockLandscapeEffect(
            lockLandscape = isFullscreenTable &&
                activeRoute == ActiveRoute.MainTab(PrimaryTab.PLAN) &&
                !showOnboarding,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideLandscape = maxWidth > maxHeight && maxWidth >= 560.dp
            LaunchedEffect(isWideLandscape) {
                if (!isWideLandscape) {
                    dismissAutoLandscape = false
                }
            }
            val showFullscreenLandscape = activeRoute == ActiveRoute.MainTab(PrimaryTab.PLAN) &&
                (isFullscreenTable || (isWideLandscape && !dismissAutoLandscape))

            if (showFullscreenLandscape) {
                FullscreenTableScreen(
                    displayMode = displayMode,
                    onDisplayModeChange = { nextMode ->
                        displayMode = nextMode
                        persistSetupPreferences(mode = nextMode)
                    },
                    displayedLayout = displayedLayout,
                    rulesState = rulesState,
                    ballSetProfile = ballSetProfile,
                    planningReadiness = planningReadiness,
                    activePlan = activePlan,
                    selectedShot = selectedShot,
                    focusedStepIndex = safeFocusedStepIndex,
                    showAllStepsOverlay = showAllStepsOverlay,
                    isPlanning = isPlanning,
                    isDraggingBall = isDraggingBall,
                    visibleSpins = visibleSpins,
                    showHeatZone = showHeatZone,
                    showLandingCloud = showLandingCloud,
                    playablePockets = playablePockets,
                    customPocketOverride = customPocketOverride,
                    onSelectPocketOverride = { pocket ->
                        customPocketOverride = pocket
                        focusedStepIndex = 0
                        selectedPlanIndex = 0
                        selectedShotIndex = 0
                    },
                    onMoveBall = ::handleMoveBall,
                    onDragStateChanged = { isDraggingBall = it },
                    onPrevStep = ::handlePrevStep,
                    onNextStep = ::handleNextStep,
                    onToggleAllStepsOverlay = ::handleToggleAllStepsOverlay,
                    onApplyCurrentStepToTable = ::handleApplyCurrentStepToTable,
                    onToggleFullscreen = {
                        if (isFullscreenTable) {
                            isFullscreenTable = false
                        } else {
                            dismissAutoLandscape = true
                        }
                    },
                    onOpenGameSetupSheet = { showGameSetupSheet = true },
                    onOpenEditBallsSheet = { showEditBallsSheet = true },
                    onOpenQuickSpinSheet = { showQuickSpinSheet = true },
                    onOpenShotOptionsSheet = { showShotOptionsSheet = true },
                    onOpenPhotoScan = { navigateToSubRoute(ActiveRoute.PhotoScan) },
                    customTipOffset = customTipOffset,
                    defaultTipForShot = defaultTipForActiveShot,
                    speedScaleOverride = speedScaleOverride,
                    defaultSpeedScaleForShot = defaultSpeedForActiveShot,
                    sandboxTargetBallNumber = sandboxTargetBallNumber,
                    showCueSandboxPanel = showCueSandboxPanel,
                    hasSandboxOverrides = hasSandboxOverrides,
                    onToggleCueSandbox = ::handleToggleCueSandbox,
                    onCloseCueSandbox = { showCueSandboxPanel = false },
                    onUpdateCustomTip = ::handleUpdateSandboxTip,
                    onSelectSpeedScale = ::handleUpdateSandboxSpeed,
                    onResetSandboxDefaults = ::resetSandboxToPlanDefaults,
                )

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding(),
                )
            } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = isTopLevel,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = RunoutColors.SurfaceLow,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(max = 300.dp)
                            .safeDrawingPadding()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // Drawer Header
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(RunoutColors.SurfaceMid)
                                .padding(RunoutSpacing.Margin),
                            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                        ) {
                            Text(
                                text = playerProfile.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "${playerProfile.experienceLevel.name.lowercase().replaceFirstChar { it.uppercase() }} • ${currentLayout.table.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SimpleProTogglePill(
                                mode = displayMode,
                                onModeChange = { nextMode ->
                                    displayMode = nextMode
                                    persistSetupPreferences(mode = nextMode)
                                },
                            )
                        }

                        Spacer(modifier = Modifier.height(RunoutSpacing.Element))

                        DrawerSectionLabel("TABLE & CAPTURE")
                        DrawerNavItem(
                            label = "Scan or Import Photo",
                            icon = RunoutIcon.CAMERA,
                            selected = activeRoute == ActiveRoute.PhotoScan,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigateToSubRoute(ActiveRoute.PhotoScan)
                            },
                        )
                        DrawerNavItem(
                            label = "Table Calibration & Saved Tables",
                            icon = RunoutIcon.TUNE,
                            selected = activeRoute == ActiveRoute.Calibration,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigateToSubRoute(ActiveRoute.Calibration)
                            },
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = RunoutSpacing.Element),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
                        )

                        DrawerSectionLabel("MATCH & PRACTICE TOOLS")
                        DrawerNavItem(
                            label = "Snooker Break & Safety Coach",
                            icon = RunoutIcon.SNOOKER,
                            selected = activeRoute == ActiveRoute.SnookerCoach,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigateToSubRoute(ActiveRoute.SnookerCoach)
                            },
                        )
                        DrawerNavItem(
                            label = "Acoustic Break-Speed Meter",
                            icon = RunoutIcon.SPEED,
                            selected = activeRoute == ActiveRoute.BreakSpeedMeter,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigateToSubRoute(ActiveRoute.BreakSpeedMeter)
                            },
                        )
                        DrawerNavItem(
                            label = "Scorekeeper & Shot Clock",
                            icon = RunoutIcon.TIMER,
                            selected = activeRoute == ActiveRoute.ScorekeeperAndClock,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigateToSubRoute(ActiveRoute.ScorekeeperAndClock)
                            },
                        )
                        DrawerNavItem(
                            label = "Practice Drill Log",
                            icon = RunoutIcon.LEARN,
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                learnSubTab = LearnSubTab.DRILLS
                                navigateToTab(PrimaryTab.LEARN)
                            },
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = RunoutSpacing.Element),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
                        )

                        DrawerSectionLabel("SETTINGS & HELP")
                        DrawerNavItem(
                            label = "Player Profile & Ball Set",
                            icon = RunoutIcon.PROFILE,
                            selected = activeRoute == ActiveRoute.PlayerProfileAndBallSet,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigateToSubRoute(ActiveRoute.PlayerProfileAndBallSet)
                            },
                        )
                        DrawerNavItem(
                            label = "Replay Welcome Tour",
                            icon = RunoutIcon.RESET,
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                showOnboarding = true
                            },
                        )
                        DrawerNavItem(
                            label = "Offline & Photo Privacy",
                            icon = RunoutIcon.INFO,
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                showPrivacyDialog = true
                            },
                        )
                    }
                }
            },
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    val topTitle = when (val r = activeRoute) {
                        is ActiveRoute.MainTab -> when (r.tab) {
                            PrimaryTab.HOME -> "Runout"
                            PrimaryTab.PLAN -> "Plan"
                            PrimaryTab.LEARN -> "Learn"
                            PrimaryTab.RULES -> "Rules & FAQ"
                        }
                        ActiveRoute.PhotoScan -> "Scan Table Photo"
                        is ActiveRoute.LearnWalkthroughPlayer -> "8-Ball Walkthrough"
                        is ActiveRoute.LearnTutorialPlayer -> "Interactive Lesson"
                        ActiveRoute.Calibration -> "Table Calibration"
                        ActiveRoute.SnookerCoach -> "Snooker Coach"
                        ActiveRoute.BreakSpeedMeter -> "Break-Speed Meter"
                        ActiveRoute.ScorekeeperAndClock -> "Scorekeeper & Clock"
                        ActiveRoute.PlayerProfileAndBallSet -> "Profile & Ball Set"
                    }
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = RunoutColors.SurfaceLow,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    if (isTopLevel) {
                                        scope.launch { drawerState.open() }
                                    } else {
                                        activeRoute = ActiveRoute.MainTab(lastPrimaryTab)
                                    }
                                },
                                modifier = Modifier.defaultMinSize(
                                    minWidth = RunoutSpacing.MinTouchTarget,
                                    minHeight = RunoutSpacing.MinTouchTarget,
                                ),
                            ) {
                                RunoutVectorIcon(
                                    icon = if (isTopLevel) RunoutIcon.MENU else RunoutIcon.BACK,
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                        title = {
                            Text(
                                text = topTitle,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        actions = {
                            when (activeRoute) {
                                ActiveRoute.MainTab(PrimaryTab.HOME) -> {
                                    Surface(
                                        onClick = { navigateToSubRoute(ActiveRoute.PlayerProfileAndBallSet) },
                                        shape = RoundedCornerShape(999.dp),
                                        color = RunoutColors.SurfaceMid,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                                        modifier = Modifier
                                            .padding(end = RunoutSpacing.Element)
                                            .defaultMinSize(minHeight = 40.dp),
                                    ) {
                                        val badgeLabel = when (playerProfile.experienceLevel) {
                                            ExperienceLevel.BEGINNER -> "Beginner"
                                            ExperienceLevel.INTERMEDIATE -> "Casual"
                                            ExperienceLevel.ADVANCED -> "Pro"
                                        }
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            RunoutVectorIcon(
                                                icon = RunoutIcon.PROFILE,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Text(
                                                text = badgeLabel,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                }

                                ActiveRoute.MainTab(PrimaryTab.PLAN) -> {
                                    SimpleProTogglePill(
                                        mode = displayMode,
                                        onModeChange = { nextMode ->
                                            displayMode = nextMode
                                            persistSetupPreferences(mode = nextMode)
                                        },
                                    )
                                    IconButton(
                                        onClick = {
                                            dismissAutoLandscape = false
                                            isFullscreenTable = true
                                        },
                                        modifier = Modifier.defaultMinSize(
                                            minWidth = RunoutSpacing.MinTouchTarget,
                                            minHeight = RunoutSpacing.MinTouchTarget,
                                        ),
                                    ) {
                                        RunoutVectorIcon(
                                            icon = RunoutIcon.FULLSCREEN,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    IconButton(
                                        onClick = { navigateToSubRoute(ActiveRoute.PhotoScan) },
                                        modifier = Modifier.defaultMinSize(
                                            minWidth = RunoutSpacing.MinTouchTarget,
                                            minHeight = RunoutSpacing.MinTouchTarget,
                                        ),
                                    ) {
                                        RunoutVectorIcon(
                                            icon = RunoutIcon.CAMERA,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    Box {
                                        IconButton(
                                            onClick = { showPlanOverflowMenu = true },
                                            modifier = Modifier.defaultMinSize(
                                                minWidth = RunoutSpacing.MinTouchTarget,
                                                minHeight = RunoutSpacing.MinTouchTarget,
                                            ),
                                        ) {
                                            RunoutVectorIcon(
                                                icon = RunoutIcon.MORE,
                                                tint = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                        DropdownMenu(
                                            expanded = showPlanOverflowMenu,
                                            onDismissRequest = { showPlanOverflowMenu = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Fullscreen Landscape Table") },
                                                onClick = {
                                                    showPlanOverflowMenu = false
                                                    dismissAutoLandscape = false
                                                    isFullscreenTable = true
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Scan or Import Photo") },
                                                onClick = {
                                                    showPlanOverflowMenu = false
                                                    navigateToSubRoute(ActiveRoute.PhotoScan)
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Game, Table & Presets") },
                                                onClick = {
                                                    showPlanOverflowMenu = false
                                                    showGameSetupSheet = true
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Saved Table Profiles") },
                                                onClick = {
                                                    showPlanOverflowMenu = false
                                                    navigateToSubRoute(ActiveRoute.Calibration)
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Reset Table", color = RunoutColors.DangerCoral) },
                                                onClick = {
                                                    showPlanOverflowMenu = false
                                                    showResetConfirmDialog = true
                                                },
                                            )
                                        }
                                    }
                                }

                                else -> Unit
                            }
                        },
                    )
                },
                bottomBar = {
                    if (isTopLevel) {
                        val activeTab = (activeRoute as ActiveRoute.MainTab).tab
                        NavigationBar(
                            containerColor = RunoutColors.SurfaceLow,
                        ) {
                            PrimaryTab.entries.forEach { tab ->
                                val selected = tab == activeTab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(tab) },
                                    icon = {
                                        RunoutVectorIcon(
                                            icon = tab.icon,
                                            tint = if (selected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = tab.label,
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                ) {
                    when (val r = activeRoute) {
                        is ActiveRoute.MainTab -> when (r.tab) {
                            PrimaryTab.HOME -> HomeLandingScreen(
                                playerProfile = playerProfile,
                                ballSet = ballSetProfile,
                                currentLayout = currentLayout,
                                rulesState = rulesState,
                                hasActiveSession = hasActiveSession,
                                onContinueLastTable = { navigateToTab(PrimaryTab.PLAN) },
                                onRequestResetSession = { showResetConfirmDialog = true },
                                onOpenPhotoScan = { navigateToSubRoute(ActiveRoute.PhotoScan) },
                                onOpenNewTableSheet = {
                                    navigateToTab(PrimaryTab.PLAN)
                                    showGameSetupSheet = true
                                },
                                onOpenSavedTables = { navigateToSubRoute(ActiveRoute.Calibration) },
                                onOpenLearnWalkthroughs = {
                                    learnSubTab = LearnSubTab.WALKTHROUGHS
                                    navigateToTab(PrimaryTab.LEARN)
                                },
                                onOpenLearnDrills = {
                                    learnSubTab = LearnSubTab.DRILLS
                                    navigateToTab(PrimaryTab.LEARN)
                                },
                                onOpenRules = { navigateToTab(PrimaryTab.RULES) },
                            )

                            PrimaryTab.PLAN -> RunoutPracticeScreen(
                                displayMode = displayMode,
                                currentLayout = displayedLayout,
                                totalObjectBallsCount = currentLayout.objectBalls().size,
                                rulesState = rulesState,
                                onSelectRules = { nextRules ->
                                    rulesState = nextRules
                                    hasActiveSession = true
                                    customPocketOverride = null
                                    sandboxTargetBallNumber = null
                                    focusedStepIndex = 0
                                    selectedPlanIndex = 0
                                    selectedShotIndex = 0
                                    persistSetupPreferences(rules = nextRules)
                                },
                                onOpenGameSetupSheet = { showGameSetupSheet = true },
                                onOpenEditBallsSheet = { showEditBallsSheet = true },
                                onOpenShotOptionsSheet = { showShotOptionsSheet = true },
                                onOpenQuickSpinSheet = { showQuickSpinSheet = true },
                                onEnterFullscreen = {
                                    dismissAutoLandscape = false
                                    isFullscreenTable = true
                                },
                                playerProfile = playerProfile,
                                ballSetProfile = ballSetProfile,
                                onAddMissingBall = { ballNumber ->
                                    currentLayout = SampleLayouts.addMissingBall(currentLayout, ballNumber)
                                    customPocketOverride = null
                                    sandboxTargetBallNumber = null
                                    focusedStepIndex = 0
                                    hasActiveSession = true
                                },
                                onMoveBall = ::handleMoveBall,
                                isDraggingBall = isDraggingBall,
                                onDragStateChanged = { isDraggingBall = it },
                                isPlanning = isPlanning,
                                planningReadiness = planningReadiness,
                                rankedShots = rankedShots,
                                selectedShotIndex = safeShotIndex,
                                onSelectShot = { selectedShotIndex = it },
                                selectedShot = selectedShot,
                                visibleSpins = visibleSpins,
                                onToggleSpin = { spin ->
                                    visibleSpins = if (spin in visibleSpins) visibleSpins - spin else visibleSpins + spin
                                },
                                showHeatZone = showHeatZone,
                                showLandingCloud = showLandingCloud,
                                customTipOffset = customTipOffset,
                                onUpdateCustomTip = ::handleUpdateSandboxTip,
                                runoutPlans = runoutPlans,
                                selectedPlanIndex = safePlanIndex,
                                onSelectPlan = {
                                    selectedPlanIndex = it
                                    focusedStepIndex = 0
                                },
                                activePlan = activePlan,
                                focusedStepIndex = safeFocusedStepIndex,
                                showAllStepsOverlay = showAllStepsOverlay,
                                onPrevStep = ::handlePrevStep,
                                onNextStep = ::handleNextStep,
                                onToggleAllStepsOverlay = ::handleToggleAllStepsOverlay,
                                onApplyCurrentStepToTable = ::handleApplyCurrentStepToTable,
                                playablePockets = playablePockets,
                                customPocketOverride = customPocketOverride,
                                onSelectPocketOverride = { pocket ->
                                    customPocketOverride = pocket
                                    focusedStepIndex = 0
                                    selectedPlanIndex = 0
                                    selectedShotIndex = 0
                                },
                                defaultTipForShot = defaultTipForActiveShot,
                                defaultSpeedScaleForShot = defaultSpeedForActiveShot,
                                speedScaleOverride = speedScaleOverride,
                                sandboxTargetBallNumber = sandboxTargetBallNumber,
                                showCueSandboxPanel = showCueSandboxPanel,
                                hasSandboxOverrides = hasSandboxOverrides,
                                onToggleCueSandbox = ::handleToggleCueSandbox,
                                onCloseCueSandbox = { showCueSandboxPanel = false },
                                onUpdateSandboxTip = ::handleUpdateSandboxTip,
                                onSelectSpeedScale = ::handleUpdateSandboxSpeed,
                                onResetSandboxDefaults = ::resetSandboxToPlanDefaults,
                                onUpdateClothParams = { updatedCloth ->
                                    activeTableProfileId = null
                                    currentLayout = currentLayout.copy(
                                        table = currentLayout.table.withClothParams(updatedCloth),
                                    )
                                    focusedStepIndex = 0
                                    hasActiveSession = true
                                },
                            )

                            PrimaryTab.LEARN -> LearnHubScreen(
                                ballSet = ballSetProfile,
                                selectedSubTab = learnSubTab,
                                onSelectSubTab = { learnSubTab = it },
                                onOpenWalkthrough = { wt -> navigateToSubRoute(ActiveRoute.LearnWalkthroughPlayer(wt)) },
                                onOpenTutorial = { tut -> navigateToSubRoute(ActiveRoute.LearnTutorialPlayer(tut)) },
                                onLoadDrillIntoPlanner = { layout, rules ->
                                    activeTableProfileId = null
                                    currentLayout = layout
                                    rulesState = rules
                                    selectedPresetIndex = -1
                                    customTipOffset = null
                                    speedScaleOverride = null
                                    customPocketOverride = null
                                    sandboxTargetBallNumber = null
                                    focusedStepIndex = 0
                                    hasActiveSession = true
                                    selectedPlanIndex = 0
                                    selectedShotIndex = 0
                                    navigateToTab(PrimaryTab.PLAN)
                                },
                            )

                            PrimaryTab.RULES -> RulesAndFaqScreen(
                                onOpenWalkthroughs = {
                                    learnSubTab = LearnSubTab.WALKTHROUGHS
                                    navigateToTab(PrimaryTab.LEARN)
                                },
                            )
                        }

                        ActiveRoute.PhotoScan -> PhotoCaptureAndConfirmScreen(
                            ballSet = ballSetProfile,
                            lastPlayedGameType = rulesState.gameType,
                            displayMode = displayMode,
                            defaultTableSpec = currentLayout.table,
                            onConfirmScannedLayout = { scannedLayout, scannedRules, scannedBallSet ->
                                activeTableProfileId = null
                                currentLayout = scannedLayout
                                rulesState = scannedRules
                                ballSetProfile = scannedBallSet
                                selectedPresetIndex = -1
                                customTipOffset = null
                                speedScaleOverride = null
                                customPocketOverride = null
                                sandboxTargetBallNumber = null
                                focusedStepIndex = 0
                                hasActiveSession = true
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                                navigateToTab(PrimaryTab.PLAN)
                            },
                        )

                        is ActiveRoute.LearnWalkthroughPlayer -> WalkthroughDetailPlayerScreen(
                            walkthrough = r.walkthrough,
                            ballSet = ballSetProfile,
                            onTryOnTablePlanner = { layout, rules ->
                                activeTableProfileId = null
                                currentLayout = layout
                                rulesState = rules
                                selectedPresetIndex = -1
                                customTipOffset = null
                                speedScaleOverride = null
                                customPocketOverride = null
                                sandboxTargetBallNumber = null
                                hasActiveSession = true
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                                navigateToTab(PrimaryTab.PLAN)
                            },
                        )

                        is ActiveRoute.LearnTutorialPlayer -> TutorialDetailPlayerScreen(
                            tutorial = r.tutorial,
                            ballSet = ballSetProfile,
                            onTryOnTablePlanner = { layout, rules ->
                                activeTableProfileId = null
                                currentLayout = layout
                                rulesState = rules
                                selectedPresetIndex = -1
                                customTipOffset = null
                                speedScaleOverride = null
                                customPocketOverride = null
                                sandboxTargetBallNumber = null
                                hasActiveSession = true
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                                navigateToTab(PrimaryTab.PLAN)
                            },
                        )

                        ActiveRoute.Calibration -> CalibrationScreen(
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
                                hasActiveSession = true
                                selectedPlanIndex = 0
                                selectedShotIndex = 0
                                navigateToTab(PrimaryTab.PLAN)
                            },
                        )

                        ActiveRoute.SnookerCoach -> ExtrasAndToolsScreen(
                            playerProfile = playerProfile,
                            focusedDestination = ToolFocusDestination.SNOOKER_COACH,
                            onLoadLayoutIntoPlanner = { layout, rules ->
                                activeTableProfileId = null
                                currentLayout = layout
                                rulesState = rules
                                selectedPresetIndex = -1
                                hasActiveSession = true
                                navigateToTab(PrimaryTab.PLAN)
                            },
                        )

                        ActiveRoute.BreakSpeedMeter -> ExtrasAndToolsScreen(
                            playerProfile = playerProfile,
                            focusedDestination = ToolFocusDestination.BREAK_METER,
                            onLoadLayoutIntoPlanner = { _, _ -> navigateToTab(PrimaryTab.PLAN) },
                        )

                        ActiveRoute.ScorekeeperAndClock -> ExtrasAndToolsScreen(
                            playerProfile = playerProfile,
                            focusedDestination = ToolFocusDestination.SCOREKEEPER_AND_CLOCK,
                            onLoadLayoutIntoPlanner = { _, _ -> navigateToTab(PrimaryTab.PLAN) },
                        )

                        ActiveRoute.PlayerProfileAndBallSet -> PlayerProfileScreen(
                            profile = playerProfile,
                            ballSet = ballSetProfile,
                            table = currentLayout.table,
                            onUpdateProfile = { updated ->
                                playerProfile = updated
                                val nextMode = when (updated.experienceLevel) {
                                    ExperienceLevel.BEGINNER -> AppDisplayMode.SIMPLE
                                    ExperienceLevel.ADVANCED -> AppDisplayMode.PRO
                                    ExperienceLevel.INTERMEDIATE -> displayMode
                                }
                                displayMode = nextMode
                                persistSetupPreferences(profile = updated, mode = nextMode)
                            },
                            onUpdateBallSet = { nextBallSet ->
                                ballSetProfile = nextBallSet
                                persistSetupPreferences(ballSet = nextBallSet)
                            },
                        )
                    }
                }
            }
        }
            }

        // Bottom Sheets
        if (showGameSetupSheet) {
            GameAndTableSetupSheet(
                scenarios = scenarios,
                selectedPresetIndex = selectedPresetIndex,
                currentLayout = currentLayout,
                rulesState = rulesState,
                ballSet = ballSetProfile,
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
                    speedScaleOverride = null
                    customPocketOverride = null
                    sandboxTargetBallNumber = null
                    focusedStepIndex = 0
                    hasActiveSession = true
                    selectedPlanIndex = 0
                    selectedShotIndex = 0
                },
                onStartBlankTable = {
                    val spec = currentLayout.table
                    val endBallNum = rulesState.gameType.requiredEndBallNumber
                    currentLayout = TableLayout(
                        table = spec,
                        balls = listOf(
                            Ball(0, Vec2(spec.lengthMeters * 0.25, spec.widthMeters * 0.5)),
                            Ball(endBallNum, spec.footSpot()),
                        ),
                    )
                    selectedPresetIndex = -1
                    customTipOffset = null
                    speedScaleOverride = null
                    customPocketOverride = null
                    sandboxTargetBallNumber = null
                    focusedStepIndex = 0
                    hasActiveSession = true
                },
                onResizeTable = { newSpec ->
                    val resizedSpec = newSpec.withClothParams(currentLayout.table.cloth)
                    activeTableProfileId = null
                    currentLayout = SampleLayouts.resizeTableLayout(
                        layout = currentLayout,
                        newSpec = resizedSpec,
                    )
                    sandboxTargetBallNumber = null
                    focusedStepIndex = 0
                    hasActiveSession = true
                    persistSetupPreferences(tableSpec = resizedSpec)
                },
                onSelectRules = { nextRules ->
                    rulesState = nextRules
                    customPocketOverride = null
                    sandboxTargetBallNumber = null
                    focusedStepIndex = 0
                    hasActiveSession = true
                    selectedPlanIndex = 0
                    selectedShotIndex = 0
                    persistSetupPreferences(rules = nextRules)
                },
                onDismiss = { showGameSetupSheet = false },
            )
        }

        if (showEditBallsSheet) {
            EditBallsSheet(
                currentLayout = currentLayout,
                ballSet = ballSetProfile,
                onToggleBall = { num ->
                    currentLayout = SampleLayouts.toggleObjectBall(currentLayout, num)
                    focusedStepIndex = 0
                    customPocketOverride = null
                    sandboxTargetBallNumber = null
                    hasActiveSession = true
                },
                onAddMissingCueBall = {
                    currentLayout = SampleLayouts.addMissingBall(currentLayout, 0)
                    focusedStepIndex = 0
                    hasActiveSession = true
                },
                onRequestReset = { showResetConfirmDialog = true },
                onDismiss = { showEditBallsSheet = false },
            )
        }

        if (showShotOptionsSheet) {
            ShotOptionsSheet(
                displayMode = displayMode,
                playerProfile = playerProfile,
                selectedHorizon = selectedHorizon,
                onSelectHorizon = {
                    selectedHorizon = it
                    selectedPlanIndex = 0
                },
                speedScaleOverride = speedScaleOverride,
                onSelectSpeedScale = ::handleUpdateSandboxSpeed,
                showHeatZone = showHeatZone,
                onToggleHeatZone = { showHeatZone = !showHeatZone },
                showLandingCloud = showLandingCloud,
                onToggleLandingCloud = { showLandingCloud = !showLandingCloud },
                visibleSpins = visibleSpins,
                onToggleSpin = { spin ->
                    visibleSpins = if (spin in visibleSpins) visibleSpins - spin else visibleSpins + spin
                },
                clothParams = currentLayout.table.cloth,
                onUpdateClothParams = { updatedCloth ->
                    activeTableProfileId = null
                    currentLayout = currentLayout.copy(table = currentLayout.table.withClothParams(updatedCloth))
                    hasActiveSession = true
                },
                onDismiss = { showShotOptionsSheet = false },
            )
        }

        if (showQuickSpinSheet) {
            QuickSpinSheet(
                customTipOffset = customTipOffset,
                onSelectTipOffset = ::handleUpdateSandboxTip,
                onDismiss = { showQuickSpinSheet = false },
            )
        }

        if (showResetConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showResetConfirmDialog = false },
                title = { Text("Reset Table Layout?") },
                text = { Text("This returns the table to the starter rack and clears your current ball edits.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showResetConfirmDialog = false
                            performResetWithUndo()
                        },
                    ) {
                        Text("Reset", color = RunoutColors.DangerCoral, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirmDialog = false }) {
                        Text("Keep Table")
                    }
                },
            )
        }

        if (showPrivacyDialog) {
            AlertDialog(
                onDismissRequest = { showPrivacyDialog = false },
                title = { Text("Offline & Photo Privacy") },
                text = {
                    Text("Runout works 100% offline with zero accounts. Camera and gallery photos are decoded in memory with location tags stripped.")
                },
                confirmButton = {
                    TextButton(onClick = { showPrivacyDialog = false }) {
                        Text("OK")
                    }
                },
            )
        }
        }
    }
}

@Composable
fun RunoutPracticeScreen(
    displayMode: AppDisplayMode,
    currentLayout: TableLayout,
    totalObjectBallsCount: Int,
    rulesState: GameRulesState,
    onSelectRules: (GameRulesState) -> Unit,
    onOpenGameSetupSheet: () -> Unit,
    onOpenEditBallsSheet: () -> Unit,
    onOpenShotOptionsSheet: () -> Unit,
    onOpenQuickSpinSheet: () -> Unit,
    onEnterFullscreen: () -> Unit,
    playerProfile: PlayerProfile,
    ballSetProfile: BallSetProfile,
    onAddMissingBall: (Int) -> Unit,
    onMoveBall: (ballNumber: Int, newPosition: Vec2) -> Unit,
    isDraggingBall: Boolean,
    onDragStateChanged: (Boolean) -> Unit,
    isPlanning: Boolean,
    planningReadiness: PlanningReadinessResult,
    rankedShots: List<ShotRecommendation>,
    selectedShotIndex: Int,
    onSelectShot: (Int) -> Unit,
    selectedShot: ShotRecommendation?,
    visibleSpins: Set<CueBallSpinPreset>,
    onToggleSpin: (CueBallSpinPreset) -> Unit,
    showHeatZone: Boolean,
    showLandingCloud: Boolean,
    customTipOffset: TipOffset?,
    onUpdateCustomTip: (TipOffset?) -> Unit,
    runoutPlans: List<RunoutPlan>,
    selectedPlanIndex: Int,
    onSelectPlan: (Int) -> Unit,
    activePlan: RunoutPlan?,
    focusedStepIndex: Int,
    showAllStepsOverlay: Boolean,
    onPrevStep: () -> Unit,
    onNextStep: () -> Unit,
    onToggleAllStepsOverlay: () -> Unit,
    onApplyCurrentStepToTable: () -> Unit,
    playablePockets: List<PocketId>,
    customPocketOverride: PocketId?,
    onSelectPocketOverride: (PocketId?) -> Unit,
    defaultTipForShot: TipOffset? = null,
    defaultSpeedScaleForShot: Int? = null,
    speedScaleOverride: Int? = null,
    sandboxTargetBallNumber: Int? = null,
    showCueSandboxPanel: Boolean = false,
    hasSandboxOverrides: Boolean = false,
    onToggleCueSandbox: () -> Unit = onOpenQuickSpinSheet,
    onCloseCueSandbox: () -> Unit = {},
    onUpdateSandboxTip: (TipOffset?) -> Unit = onUpdateCustomTip,
    onSelectSpeedScale: (Int?) -> Unit = {},
    onResetSandboxDefaults: () -> Unit = {},
    onUpdateClothParams: (ClothParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeStep = activePlan?.steps?.getOrNull(
        focusedStepIndex.coerceIn(0, (activePlan.steps.size - 1).coerceAtLeast(0)),
    )
    val activeTargetPocketId = activeStep?.pocketId ?: selectedShot?.pocket?.id

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Card),
        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Section),
    ) {
        // 1. Single Action Strip + Hero Pool Table (Above the fold!)
        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
            ) {
                val gameBadge = if (rulesState.gameType == GameType.EIGHT_BALL) {
                    "${rulesState.gameType.displayName} • ${rulesState.eightBallGroup.displayNameFor(ballSetProfile)}"
                } else {
                    rulesState.gameType.displayName
                }
                FilterChip(
                    selected = false,
                    onClick = onOpenGameSetupSheet,
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                    label = { Text("$gameBadge • ${currentLayout.table.name} ▾") },
                )
                FilterChip(
                    selected = false,
                    onClick = onOpenEditBallsSheet,
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                    label = { Text("Edit Balls ($totalObjectBallsCount)") },
                )
                FilterChip(
                    selected = false,
                    onClick = onOpenShotOptionsSheet,
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                    label = { Text("Shot Options") },
                )
                FilterChip(
                    selected = false,
                    onClick = onEnterFullscreen,
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                    label = { Text("Fullscreen") },
                )
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
                        .padding(RunoutSpacing.Card),
                    verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    PoolTableCanvas(
                        layout = currentLayout,
                        selectedShot = selectedShot,
                        modifier = Modifier.fillMaxWidth(),
                        ballSet = ballSetProfile,
                        onMoveBall = onMoveBall,
                        onDragStateChanged = onDragStateChanged,
                        visibleSpins = visibleSpins,
                        activeRunoutPlan = activePlan,
                        focusedStepIndex = if (showAllStepsOverlay && focusedStepIndex == 0) null else focusedStepIndex,
                        isDraggingBall = isDraggingBall,
                        availablePockets = if (focusedStepIndex == 0 && planningReadiness.isReady) playablePockets else emptyList(),
                        onSelectPocket = if (focusedStepIndex == 0 && planningReadiness.isReady) { pocketId ->
                            onSelectPocketOverride(if (customPocketOverride == pocketId) null else pocketId)
                        } else null,
                        onTapCueBall = if (planningReadiness.isReady) onToggleCueSandbox else null,
                        highlightCueBall = showCueSandboxPanel || hasSandboxOverrides,
                        showHeatZone = displayMode == AppDisplayMode.PRO && showHeatZone && focusedStepIndex == 0,
                        showLandingCloud = if (displayMode == AppDisplayMode.SIMPLE) true else showLandingCloud,
                    )

                    if (planningReadiness.isReady) {
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
                            onTapCueHitGuide = onToggleCueSandbox,
                        )

                        if (focusedStepIndex == 0 && (playablePockets.size > 1 || customPocketOverride != null)) {
                            PocketSelectorStrip(
                                playablePockets = playablePockets,
                                activePocketId = activeTargetPocketId,
                                customPocketOverride = customPocketOverride,
                                onSelectPocketOverride = onSelectPocketOverride,
                            )
                        }

                        AnimatedVisibility(
                            visible = showCueSandboxPanel,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            val resolvedTip = customTipOffset
                                ?: activeStep?.recommendedTip
                                ?: defaultTipForShot
                                ?: TipOffset()
                            val resolvedSpeed = speedScaleOverride
                                ?: activeStep?.recommendedSpeedScale
                                ?: defaultSpeedScaleForShot
                                ?: 5
                            CueBallSandboxPanel(
                                activeTip = resolvedTip,
                                defaultTip = defaultTipForShot ?: activeStep?.recommendedTip,
                                customTipOffset = customTipOffset,
                                activeSpeedScale = resolvedSpeed,
                                speedScaleOverride = speedScaleOverride,
                                lockedTargetBallNumber = sandboxTargetBallNumber,
                                hasOverrides = hasSandboxOverrides,
                                onUpdateTip = onUpdateSandboxTip,
                                onUpdateSpeedScale = onSelectSpeedScale,
                                onResetDefaults = onResetSandboxDefaults,
                                onClose = onCloseCueSandbox,
                                compactVertical = false,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when {
                                isDraggingBall -> "Release ball to re-plan"
                                isPlanning -> "Updating runout plan…"
                                playablePockets.size > 1 && focusedStepIndex == 0 ->
                                    "Drag balls • Tap white ball for spin & speed • Tap pocket to switch"
                                else -> "Drag balls • Tap white ball for spin & speed"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPlanning || isDraggingBall) {
                                RunoutColors.WarningAmber
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (hasSandboxOverrides) {
                            FilterChip(
                                selected = true,
                                onClick = onResetSandboxDefaults,
                                modifier = Modifier.defaultMinSize(minHeight = 36.dp),
                                label = {
                                    Text(
                                        text = "Reset Defaults",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                },
                            )
                        } else if (displayMode == AppDisplayMode.PRO && showHeatZone) {
                            Text(
                                text = "Heat Zone ON",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }

        // 2. Mandatory Readiness Pause Guardrail
        if (!planningReadiness.isReady) {
            PlanningPausedCard(
                readiness = planningReadiness,
                rulesState = rulesState,
                ballSet = ballSetProfile,
                onSelectRules = onSelectRules,
                onAddMissingBall = onAddMissingBall,
            )
            return@Column
        }

        // 3. Hero Recommended Shot Section (Simple vs. Pro)
        if (displayMode == AppDisplayMode.SIMPLE) {
            SimpleModeShotSection(
                activePlan = activePlan,
                focusedStepIndex = focusedStepIndex,
                fallbackShot = selectedShot,
                allPlans = runoutPlans,
                selectedPlanIndex = selectedPlanIndex,
                onSelectPlan = onSelectPlan,
                ballSet = ballSetProfile,
                onOpenQuickSpinSheet = onOpenQuickSpinSheet,
                onToggleCueSandbox = onToggleCueSandbox,
            )
        } else {
            ProModeStrategySection(
                activePlan = activePlan,
                focusedStepIndex = focusedStepIndex,
                selectedShot = selectedShot,
                runoutPlans = runoutPlans,
                selectedPlanIndex = selectedPlanIndex,
                onSelectPlan = onSelectPlan,
                rankedShots = rankedShots,
                selectedShotIndex = selectedShotIndex,
                onSelectShot = onSelectShot,
                ballSetProfile = ballSetProfile,
                visibleSpins = visibleSpins,
                onToggleSpin = onToggleSpin,
                customTipOffset = customTipOffset,
                onUpdateCustomTip = onUpdateCustomTip,
                onOpenQuickSpinSheet = onOpenQuickSpinSheet,
                onToggleCueSandbox = onToggleCueSandbox,
                clothParams = currentLayout.table.cloth,
                onUpdateClothParams = onUpdateClothParams,
            )
        }
    }
}

@Composable
private fun SimpleModeShotSection(
    activePlan: RunoutPlan?,
    focusedStepIndex: Int,
    fallbackShot: ShotRecommendation?,
    allPlans: List<RunoutPlan>,
    selectedPlanIndex: Int,
    onSelectPlan: (Int) -> Unit,
    ballSet: BallSetProfile,
    onOpenQuickSpinSheet: () -> Unit,
    onToggleCueSandbox: () -> Unit = onOpenQuickSpinSheet,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val steps = activePlan?.steps.orEmpty()
            val safeStepIdx = focusedStepIndex.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
            RunoutSectionHeader(
                title = if (steps.size > 1) "Shot ${safeStepIdx + 1} of ${steps.size}" else "Recommended Shot",
                modifier = Modifier.weight(1f),
            )
            GlossaryInfoButton(
                term = "Make vs. Position Difficulty",
                explanation = "Make % is your chance to pot this ball. Position % is how easily the cue ball lands cleanly in shape for the next shot.",
            )
        }

        val steps = activePlan?.steps.orEmpty()
        val safeStepIdx = if (steps.isNotEmpty()) focusedStepIndex.coerceIn(0, steps.lastIndex) else 0
        val activeStep = steps.getOrNull(safeStepIdx)
        val nextStep = steps.getOrNull(safeStepIdx + 1)
        if (activePlan != null && activeStep != null) {
            val speedWord = plainSpeedLabel(activeStep.recommendedSpeedScale)
            val tipWord = plainTipLabel(activeStep.recommendedTip)
            val tightness = computeShotTightness(activeStep, nextStep)

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(RunoutSpacing.Margin),
                    verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(ballBaseColor(activeStep.targetBall.number, ballSet)),
                        )
                        Text(
                            text = "Ball #${activeStep.targetBall.number} -> ${activeStep.pocketId.displayName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    ShotDifficultyPairRow(
                        makeProbability = activeStep.uncertainty.makeProbability,
                        positionProbability = activeStep.positionSuccessProbability,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CueBallHitGuide(
                            tipOffset = activeStep.recommendedTip,
                            tightness = tightness,
                            showAxisLabels = true,
                            onClick = onToggleCueSandbox,
                            modifier = Modifier.size(68.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Speed: $speedWord (${activeStep.recommendedSpeedScale}/10)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = "Hit: $tipWord",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Zone: ${shotTightnessLabel(tightness)} sweet spot (tap cue ball to test spin)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (activeStep.uncertainty.scratchProbability >= 0.25) {
                        val scratchPct = formatPercentInt(activeStep.uncertainty.scratchProbability)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = RunoutColors.DangerCoral.copy(alpha = 0.14f),
                        ) {
                            Text(
                                text = "Scratch risk ($scratchPct%) — adjust spin or speed",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = RunoutColors.DangerCoral,
                            )
                        }
                    }

                    if (activePlan.problemBalls.isNotEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = RunoutColors.WarningAmber.copy(alpha = 0.12f),
                        ) {
                            Text(
                                text = "Tip: Ball #${activePlan.problemBalls.first().ballNumber} is tied up — look for an open breakout.",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = RunoutColors.WarningAmber,
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onToggleCueSandbox,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text("Sandbox: Spin & Speed ($tipWord)")
                    }
                }
            }

            CollapsibleSectionCard(
                title = "Why this shot?",
                summary = if (activePlan.steps.size >= 2) {
                    "Sets up ${activePlan.steps.drop(1).joinToString(" -> ") { "#${it.targetBall.number}" }}"
                } else {
                    "Highest make chance on the table"
                },
                initiallyExpanded = false,
            ) {
                if (activePlan.steps.size >= 2) {
                    Text(
                        text = "Next balls in order: ${activePlan.steps.joinToString(" -> ") { "#${it.targetBall.number} (${it.pocketId.displayName})" }}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                activePlan.explanationReasons.forEach { reason ->
                    Text(
                        text = "• $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (allPlans.size > 1) {
                CollapsibleSectionCard(
                    title = "Alternative Shot Routes (${allPlans.size - 1})",
                    summary = "Tap to compare other run-out sequences",
                    initiallyExpanded = false,
                ) {
                    allPlans.forEachIndexed { index, plan ->
                        RunoutPlanCard(
                            rank = index + 1,
                            plan = plan,
                            ballSet = ballSet,
                            isSelected = index == selectedPlanIndex,
                            onClick = { onSelectPlan(index) },
                        )
                    }
                }
            }
        } else if (fallbackShot != null) {
            val fallbackTip = TipOffset.fromPreset(fallbackShot.recommendedSpin)
            val fallbackTightness = computeShotTightness(fallbackShot)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(RunoutSpacing.Margin),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CueBallHitGuide(
                        tipOffset = fallbackTip,
                        tightness = fallbackTightness,
                        showAxisLabels = true,
                        onClick = onToggleCueSandbox,
                        modifier = Modifier.size(68.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Best Direct Pot: Ball #${fallbackShot.targetBall.number} -> ${fallbackShot.pocket.id.displayName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Speed: ${plainSpeedLabel(fallbackShot.recommendedSpeedScale)} (${fallbackShot.recommendedSpeedScale}/10) • Hit: ${plainTipLabel(fallbackTip)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Zone: ${shotTightnessLabel(fallbackTightness)} sweet spot",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Text(
                    text = "No open legal pots found from this cue ball position. Try dragging balls or playing a safety.",
                    modifier = Modifier.padding(RunoutSpacing.Margin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProModeStrategySection(
    activePlan: RunoutPlan?,
    focusedStepIndex: Int,
    selectedShot: ShotRecommendation?,
    runoutPlans: List<RunoutPlan>,
    selectedPlanIndex: Int,
    onSelectPlan: (Int) -> Unit,
    rankedShots: List<ShotRecommendation>,
    selectedShotIndex: Int,
    onSelectShot: (Int) -> Unit,
    ballSetProfile: BallSetProfile,
    visibleSpins: Set<CueBallSpinPreset>,
    onToggleSpin: (CueBallSpinPreset) -> Unit,
    customTipOffset: TipOffset?,
    onUpdateCustomTip: (TipOffset?) -> Unit,
    onOpenQuickSpinSheet: () -> Unit,
    onToggleCueSandbox: () -> Unit = onOpenQuickSpinSheet,
    clothParams: ClothParams,
    onUpdateClothParams: (ClothParams) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Section)) {
        // Hero Runout / Shot Card
        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RunoutSectionHeader(
                    title = if (activePlan != null) "Top Run-Out Plan" else "Shot Analysis",
                    subtitle = "Pro physics telemetry & multi-ball shape",
                    modifier = Modifier.weight(1f),
                )
                GlossaryInfoButton(
                    term = "Make vs. Position Difficulty",
                    explanation = "Make % is your chance to pot this ball. Position % is how easily the cue ball lands cleanly in shape for the next shot.",
                )
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
                        .padding(RunoutSpacing.Margin),
                    verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
                ) {
                    if (activePlan != null && activePlan.steps.isNotEmpty()) {
                        ActivePlanReadoutBar(
                            plan = activePlan,
                            focusedStepIndex = focusedStepIndex,
                            onTapCueHitGuide = onToggleCueSandbox,
                        )
                    } else if (selectedShot != null) {
                        SelectedShotReadoutBar(
                            shot = selectedShot,
                            onTapCueHitGuide = onToggleCueSandbox,
                        )
                    } else {
                        Text(
                            text = "No direct legal pots or multi-shot plan available for this layout.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    SpinPathToggleRow(
                        visibleSpins = visibleSpins,
                        recommendedSpin = selectedShot?.recommendedSpin,
                        onToggleSpin = onToggleSpin,
                    )
                }
            }
        }

        if (activePlan != null && activePlan.problemBalls.isNotEmpty()) {
            ProblemBallsCard(
                plan = activePlan,
                ballSet = ballSetProfile,
            )
        }

        CollapsibleSectionCard(
            title = "2D Cue-Tip Spin & English",
            summary = if (customTipOffset == null) {
                "Auto Spin • Squirt & Throw compensation active"
            } else {
                "Manual: ${formatTipDescription(customTipOffset)}"
            },
            initiallyExpanded = false,
        ) {
            CueTipEnglishCard(
                customTipOffset = customTipOffset,
                recommendedStep = activePlan?.steps?.firstOrNull(),
                onUpdateCustomTip = onUpdateCustomTip,
            )
        }

        CollapsibleSectionCard(
            title = "Table Surface (Cloth Roll & Rail Bounce)",
            summary = "μr=${formatOneDecimal(clothParams.rollingResistanceMuR * 1000.0)}×10⁻³ • ec=${formatSignedTwoDecimals(clothParams.cushionRestitutionEc).removePrefix("+")}",
            initiallyExpanded = false,
        ) {
            AssumedSurfaceControlCard(
                title = "Surface: Assumed Cloth Roll & Rail Density",
                clothParams = clothParams,
                presetLabels = PLANNER_SURFACE_PRESET_LABELS,
                rollStepperTitle = "Cloth Roll (μr)",
                railStepperTitle = "Rail Bounce (ec)",
                onUpdateClothParams = onUpdateClothParams,
            )
        }

        if (runoutPlans.size > 1) {
            CollapsibleSectionCard(
                title = "All Run-Out Plans (${runoutPlans.size})",
                summary = "Compare expected clear probabilities",
                initiallyExpanded = false,
            ) {
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
        }

        CollapsibleSectionCard(
            title = "Ranked Legal Pots (${rankedShots.size})",
            summary = "Geometric cut & 2-ball shape reference",
            initiallyExpanded = false,
        ) {
            if (rankedShots.isEmpty()) {
                Text(
                    text = "No direct legal pots available for this layout.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DrawerNavItem(
    label: String,
    icon: RunoutIcon,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
        icon = {
            RunoutVectorIcon(
                icon = icon,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
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
    val amber = RunoutColors.WarningAmber
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = amber.copy(alpha = 0.12f)),
        border = BorderStroke(1.2.dp, amber.copy(alpha = 0.70f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunoutSpacing.Margin),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
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
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    listOf(
                        EightBallGroup.SOLIDS,
                        EightBallGroup.STRIPES,
                        EightBallGroup.OPEN,
                        EightBallGroup.EIGHT_BALL,
                    ).forEach { group ->
                        FilterChip(
                            selected = false,
                            onClick = { onSelectRules(rulesState.copy(eightBallGroup = group)) },
                            modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
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
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    readiness.missingBallNumbers.forEach { missingNum ->
                        val label = if (missingNum == 0) "Add Cue Ball (#0)" else "Add Ball #$missingNum"
                        FilterChip(
                            selected = false,
                            onClick = { onAddMissingBall(missingNum) },
                            modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
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
                                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
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
private fun SelectedShotReadoutBar(
    shot: ShotRecommendation,
    onTapCueHitGuide: (() -> Unit)? = null,
) {
    val cutDeg = formatOneDecimal(abs(radiansToDegrees(shot.aim.cutAngleRadians)))
    val cueDistText = formatDistanceMeters(shot.aim.cueToGhostDistanceMeters)
    val obDistText = formatDistanceMeters(shot.aim.objectToPocketDistanceMeters)
    val scratchSpins = shot.cueBallPaths.values.filter { it.hitsScratchPocket != null }
    val recommendedTip = TipOffset.fromPreset(shot.recommendedSpin)
    val tightness = computeShotTightness(shot)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CueBallHitGuide(
            tipOffset = recommendedTip,
            tightness = tightness,
            showAxisLabels = true,
            onClick = onTapCueHitGuide,
            modifier = Modifier.size(68.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
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
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Cut $cutDeg° • Speed ${shot.recommendedSpeedScale}/10",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                text = "Cue → Ghost: $cueDistText • Object → Pocket: $obDistText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Hit: ${plainTipLabel(recommendedTip)} • ${shotTightnessLabel(tightness)} sweet spot",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
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
                    color = RunoutColors.DangerCoral,
                )
            }
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
            horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
        ) {
            CueBallSpinPreset.entries.forEach { spin ->
                val isVisible = spin in visibleSpins
                val color = spinColor(spin)
                val label = when (spin) {
                    CueBallSpinPreset.STUN -> "STUN (90°)"
                    CueBallSpinPreset.FOLLOW -> "FOLLOW (30°)"
                    CueBallSpinPreset.DRAW -> "DRAW"
                }
                val suffix = if (spin == recommendedSpin) " ★" else ""
                FilterChip(
                    selected = isVisible,
                    onClick = { onToggleSpin(spin) },
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
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
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val containerColor = if (isSelected) RunoutColors.SurfaceMid else MaterialTheme.colorScheme.surface
    val cutAngleDeg = formatOneDecimal(abs(radiansToDegrees(shot.aim.cutAngleRadians)))
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
                .padding(RunoutSpacing.Card),
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
                Text(
                    text = "$ratingText / 10",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "Cut: $cutAngleDeg°  •  Speed: ${shot.recommendedSpeedScale}/10  •  Spin: ${shot.recommendedSpin.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (shot.nextTargetBallNumber != null) {
                Text(
                    text = "Next shape: Ball #${shot.nextTargetBallNumber}",
                    style = MaterialTheme.typography.labelSmall,
                    color = recSpinColor,
                )
            }
        }
    }
}

@Composable
private fun ActivePlanReadoutBar(
    plan: RunoutPlan,
    focusedStepIndex: Int = 0,
    onTapCueHitGuide: (() -> Unit)? = null,
) {
    val steps = plan.steps
    if (steps.isEmpty()) return
    val safeIdx = focusedStepIndex.coerceIn(0, steps.lastIndex)
    val activeStep = steps[safeIdx]
    val nextStep = steps.getOrNull(safeIdx + 1)
    val clearPct = formatPercentOneDecimal(plan.cumulativeRunoutProbability)
    val spreadCm = (activeStep.uncertainty.landingSpreadRadiusMeters * METERS_TO_CM).roundToInt()
    val cutDeg = formatOneDecimal(abs(radiansToDegrees(activeStep.compensatedAim.geometricAim.cutAngleRadians)))
    val tightness = computeShotTightness(activeStep, nextStep)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Step ${activeStep.stepNumber}: Ball #${activeStep.targetBall.number} -> ${activeStep.pocketId.displayName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Run-Out: $clearPct%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        ShotDifficultyPairRow(
            makeProbability = activeStep.uncertainty.makeProbability,
            positionProbability = activeStep.positionSuccessProbability,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CueBallHitGuide(
                tipOffset = activeStep.recommendedTip,
                tightness = tightness,
                showAxisLabels = true,
                onClick = onTapCueHitGuide,
                modifier = Modifier.size(68.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Cut $cutDeg° • Speed ${activeStep.recommendedSpeedScale}/10 • ±$spreadCm cm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Hit: ${plainTipLabel(activeStep.recommendedTip)} • ${shotTightnessLabel(tightness)} sweet spot",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
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

        if (activeStep.uncertainty.scratchProbability >= 0.25) {
            val scratchPct = formatPercentInt(activeStep.uncertainty.scratchProbability)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = RunoutColors.DangerCoral.copy(alpha = 0.14f),
            ) {
                Text(
                    text = "Scratch risk ($scratchPct%) — adjust spin or speed",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = RunoutColors.DangerCoral,
                )
            }
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CueTipSelectorCanvas(
                tipOffset = displayedTip,
                onUpdateTip = onUpdateCustomTip,
                modifier = Modifier.size(140.dp),
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
                    CompensationMetricRow("Squirt", "${formatSignedDegrees(compAim.squirtAngleRadians)}°")
                    CompensationMetricRow("Swerve", "${formatSignedDegrees(compAim.swerveAngleRadians)}°")
                    CompensationMetricRow("Total Throw", "${formatSignedDegrees(compAim.totalThrowAngleRadians)}°")
                    CompensationMetricRow("Net Adjust", "${formatSignedDegrees(compAim.netCueAimAdjustmentRadians)}°", highlight = true)
                }
            }
        }
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
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                    label = { Text(preset.label) },
                )
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
                .padding(RunoutSpacing.Card),
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
                    Column {
                        Text(
                            text = "Ball #${report.ballNumber}: $reasonText",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = insuranceText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            breakoutSteps.forEach { (step, breakout) ->
                Text(
                    text = "Breakout on Step ${step.stepNumber}: pot #${step.targetBall.number} -> ${step.pocketId.displayName} to open #${breakout.problemBallNumber}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary,
                )
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
    val borderColor = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant
    val containerColor = if (isSelected) RunoutColors.SurfaceMid else MaterialTheme.colorScheme.surface
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
                .padding(RunoutSpacing.Card),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Plan #$rank (${plan.steps.size}-Ball)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Run-Out: $clearProbText%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            plan.steps.forEach { step ->
                val makePct = formatPercentInt(step.uncertainty.makeProbability)
                val posText = step.positionSuccessProbability?.let {
                    "Pos ${formatPercentInt(it)}% (${step.positionDifficulty?.label})"
                } ?: "Final ball"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(ballBaseColor(step.targetBall.number, ballSet)),
                    )
                    Text(
                        text = "${step.stepNumber}. #${step.targetBall.number} -> ${step.pocketId.displayName} • Make $makePct% • $posText • Speed ${step.recommendedSpeedScale}/10",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun plainSpeedLabel(scale: Int): String = when {
    scale <= 3 -> "Gentle"
    scale <= 6 -> "Medium"
    scale <= 8 -> "Firm"
    else -> "Hard"
}

private fun plainTipLabel(tip: TipOffset): String = when {
    tip.clampedVerticalRatio > 0.12 -> "Soft Follow"
    tip.clampedVerticalRatio < -0.12 -> "Bottom Draw"
    else -> "Center Stun"
}

private fun radiansToDegrees(rad: Double): Double = rad * 180.0 / PI

private fun formatShotSummary(shot: ShotRecommendation): String {
    val cutDegreesRounded = round(abs(radiansToDegrees(shot.aim.cutAngleRadians))).toInt()
    return "Ball ${shot.targetBall.number} -> ${shot.pocket.id.displayName} ($cutDegreesRounded° cut, ${shot.recommendedSpin.name.lowercase()})"
}

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
