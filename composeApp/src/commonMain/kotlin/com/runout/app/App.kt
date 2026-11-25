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
import com.runout.app.legal.AboutLegalScreen
import com.runout.app.legal.LegalConfig
import com.runout.app.legal.LegalDocument
import com.runout.app.legal.LegalDocumentScreen
import com.runout.app.legal.TermsAcknowledgementScreen
import com.runout.app.navigation.ActiveRoute
import com.runout.app.navigation.BackAction
import com.runout.app.navigation.BackContext
import com.runout.app.navigation.PlatformBackHandler
import com.runout.app.navigation.PrimaryTab
import com.runout.app.navigation.backActionFor
import com.runout.app.planning.PlanCommand
import com.runout.app.planning.RollingPlanSession
import com.runout.app.planning.TableSession
import com.runout.app.planning.showsFocusEdit
import com.runout.app.storage.SavedSetupPreferences
import com.runout.app.storage.TableSizePreset
import com.runout.app.storage.isTermsAcceptanceCurrent
import com.runout.app.storage.rememberSetupPreferencesStore
import com.runout.app.tools.EightBallWalkthrough
import com.runout.app.tools.InteractiveTutorial
import com.runout.app.ui.AppDisplayMode
import com.runout.app.ui.AssumedSurfaceControlCard
import com.runout.app.ui.BallNumberBadge
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
import com.runout.app.ui.shotTightnessLabel
import com.runout.app.ui.solvedTip
import com.runout.app.ui.spinColor
import com.runout.app.version.AppVersion
import com.runout.app.version.WhatsNewScreen
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
import com.runout.engine.model.PocketPreset
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.SpinPhysics
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.FallbackAdvice
import com.runout.engine.planner.FallbackKind
import com.runout.engine.planner.MultiShotPlanner
import com.runout.engine.planner.PlannedShotStep
import com.runout.engine.planner.PlanningPauseReason
import com.runout.engine.planner.PlanningReadinessResult
import com.runout.engine.planner.PlanningReadinessValidator
import com.runout.engine.planner.ProblemBallReason
import com.runout.engine.planner.RollingPlanState
import com.runout.engine.planner.RunoutPlan
import com.runout.engine.planner.ShotRecommendation
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val METERS_TO_CM: Double = 100.0
private const val METERS_TO_FEET: Double = 3.28084

private val PLANNER_SURFACE_PRESET_LABELS: Map<AssumedSurfacePreset, String> = mapOf(
    AssumedSurfacePreset.PRO_BROADCAST_FAST to "Pro Fast",
    AssumedSurfacePreset.STANDARD_POOL_HALL to "Standard",
    AssumedSurfacePreset.SLOW_BAR_BOX_HEAVY to "Slow Bar",
    AssumedSurfacePreset.SNOOKER_DIRECTIONAL to "Snooker",
)

internal const val MAKE_POSITION_RUNOUT_TERM: String = "Make, Position & Run-Out"

/** The glossary text for the three percentages. The last sentence says what Run-Out % and Next N shots % include. */
internal const val MAKE_POSITION_RUNOUT_EXPLANATION: String =
    "Make % is your chance to pot this ball. Position % is how easily the cue ball lands cleanly in shape for the " +
        "next shot. Run-Out % and Next N shots % are the chance of making every shot shown and holding position " +
        "between them."

private const val SAFER_OPTION_TITLE: String = "A Safer Option"

private data class TipQuickPreset(
    val label: String,
    val tip: TipOffset?,
)

private val TIP_QUICK_PRESETS = listOf(
    TipQuickPreset("Auto Spin", null),
    TipQuickPreset("Center", TipOffset(sideRatio = 0.0, verticalRatio = 0.0)),
    TipQuickPreset("Touch Low", TipOffset(sideRatio = 0.0, verticalRatio = -0.14)),
    TipQuickPreset("Ctrl Follow", TipOffset(sideRatio = 0.0, verticalRatio = 0.20)),
    TipQuickPreset("Firm Follow", TipOffset(sideRatio = 0.0, verticalRatio = 0.35)),
    TipQuickPreset("Ctrl Draw", TipOffset(sideRatio = 0.0, verticalRatio = -0.25)),
    TipQuickPreset("Max Draw", TipOffset(sideRatio = 0.0, verticalRatio = -0.44)),
    TipQuickPreset("1/2 Left", TipOffset(sideRatio = -0.18, verticalRatio = 0.0)),
    TipQuickPreset("1/2 Right", TipOffset(sideRatio = 0.18, verticalRatio = 0.0)),
    TipQuickPreset("Follow + Left", TipOffset(sideRatio = -0.18, verticalRatio = 0.22)),
    TipQuickPreset("Follow + Right", TipOffset(sideRatio = 0.18, verticalRatio = 0.22)),
    TipQuickPreset("Draw + Left", TipOffset(sideRatio = -0.18, verticalRatio = -0.24)),
    TipQuickPreset("Draw + Right", TipOffset(sideRatio = 0.18, verticalRatio = -0.24)),
)

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

    var termsAccepted by remember(setupStore) {
        mutableStateOf(isTermsAcceptanceCurrent(setupStore.loadAcceptedTermsVersion(), LegalConfig.TERMS_VERSION))
    }
    var showOnboarding by remember { mutableStateOf(initialSavedSetup?.hasCompletedSetup != true) }
    var lastPrimaryTab by remember { mutableStateOf(PrimaryTab.HOME) }
    var activeRoute by remember { mutableStateOf<ActiveRoute>(ActiveRoute.MainTab(PrimaryTab.HOME)) }
    var learnSubTab by remember { mutableStateOf(LearnSubTab.WALKTHROUGHS) }
    var displayMode by remember { mutableStateOf(initialSavedSetup?.displayMode ?: AppDisplayMode.SIMPLE) }
    var hasActiveSession by remember { mutableStateOf(false) }

    // The balls, rules, table and active calibration profile live in one place; every change to them is one of the
    // named operations of TableSession. The values below are for drawing; handlers read and write `tableSession`.
    var tableSession by remember {
        mutableStateOf(
            TableSession.initial(
                index = initialPresetIdx,
                scenario = initialBaseScenario,
                rules = initialRulesState,
                spec = initialSavedSetup?.tableSizePreset?.toTableSpec(),
            ),
        )
    }
    val currentLayout = tableSession.layout
    val rulesState = tableSession.rules
    val activeTableProfileId = tableSession.activeProfileId
    val selectedPresetIndex = tableSession.presetIndex
    var playerProfile by remember {
        mutableStateOf(initialSavedSetup?.toPlayerProfile() ?: PlayerProfile())
    }
    var ballSetProfile by remember {
        mutableStateOf(initialSavedSetup?.toBallSetProfile() ?: BallSetProfile.standardPool())
    }
    var savedTableProfiles by remember { mutableStateOf(SampleCalibrationSessions.defaultSavedProfiles()) }

    var selectedHorizon by remember {
        mutableStateOf(initialSavedSetup?.toPlanningHorizon() ?: PlanningHorizonOption.THREE_BALL)
    }
    var showHeatZone by remember { mutableStateOf(false) }
    var showLandingCloud by remember { mutableStateOf(true) }
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

    var selectedShotIndex by remember { mutableIntStateOf(0) }

    var isDraggingBall by remember { mutableStateOf(false) }
    var showCueSandboxPanel by remember { mutableStateOf(false) }
    var showAllStepsOverlay by remember { mutableStateOf(false) }
    var isFullscreenTable by remember { mutableStateOf(false) }
    var dismissAutoLandscape by remember { mutableStateOf(false) }

    // The rolling plan: accepted shots, the window from the focus and the edit on the focused shot. Changing the
    // token plans again from the current table even when nothing else changed (a preset or photo loaded twice).
    val planSession = remember { RollingPlanSession() }
    var planRestartToken by remember { mutableIntStateOf(0) }

    val planningReadiness = remember(currentLayout, rulesState, ballSetProfile) {
        PlanningReadinessValidator.evaluate(
            layout = currentLayout,
            rulesState = rulesState,
            ballSet = ballSetProfile,
        )
    }

    LaunchedEffect(planSession) { planSession.run() }

    // A new table, rules, player or restart token plans again from the table; the horizon is not a key here, so a
    // change of horizon (below) keeps the accepted shots.
    LaunchedEffect(
        currentLayout,
        rulesState,
        playerProfile,
        planRestartToken,
        planningReadiness.isReady,
        isDraggingBall,
    ) {
        if (!planningReadiness.isReady) {
            planSession.clear()
            return@LaunchedEffect
        }
        if (isDraggingBall) {
            // Keep finger dragging at 60 fps and defer heavy multi-shot tree search until release.
            return@LaunchedEffect
        }
        planSession.submit(
            PlanCommand.Restart(
                layout = currentLayout,
                rules = rulesState,
                profile = playerProfile,
                horizon = selectedHorizon.horizonShots,
                deepAnalysis = selectedHorizon.deepAnalysis,
            ),
        )
    }

    LaunchedEffect(selectedHorizon) {
        planSession.setHorizon(selectedHorizon.horizonShots, selectedHorizon.deepAnalysis)
    }

    // The plan on screen. After the table or the rules change it is hidden until the planner has caught up, so
    // lines never sit on a table they were not planned for.
    val rollingState = planSession.state
    val activeRolling: RollingPlanState? = rollingState?.takeIf {
        planningReadiness.isReady && it.baseLayout == currentLayout && it.baseRules == rulesState
    }
    val isPlanning = planningReadiness.isReady && !isDraggingBall &&
        (planSession.isBusy || (activeRolling == null && !planSession.hasFailed))
    val planError = if (planningReadiness.isReady && planSession.hasFailed && !planSession.isBusy) {
        RollingPlanSession.PLAN_FAILED_MESSAGE
    } else {
        null
    }
    val displayedLayout = activeRolling?.focusLayout ?: currentLayout
    val rankedShots = if (activeRolling != null) planSession.rankedShots else emptyList()
    // Advice beside a weak pot, or for a table with no direct pot; hidden while the planner is catching up, like the plan.
    val fallbackAdvice = if (activeRolling != null && !planSession.isBusy) planSession.fallbackAdvice else null

    // What the user has chosen on the focused shot, as the controls show it. "Reset step" and "Reset Defaults" both
    // follow the planner's own word on whether the focused step is an edit.
    val editedOverride = if (activeRolling != null) planSession.editedOverride else null
    val editedTip = editedOverride?.tip
    val editedSpeedUnits = editedOverride?.speedUnits
    val editedPocket = editedOverride?.pocketId
    val editedTargetBall = editedOverride?.targetBallNumber
    val hasSandboxOverrides = activeRolling?.showsFocusEdit == true
    // What the planner picks at the focus when nothing is forced, for the default markers.
    val plannerPick = activeRolling?.defaultFocusStep

    val playablePockets = remember(displayedLayout, activeRolling?.focusRules, rulesState, planningReadiness.isReady) {
        if (!planningReadiness.isReady) {
            emptyList()
        } else {
            val focusRules = activeRolling?.focusRules ?: rulesState
            val legalTargets = focusRules.legalTargetBalls(displayedLayout)
            val validSet = legalTargets
                .flatMap { ball -> MultiShotPlanner.playablePocketsForTarget(displayedLayout, ball) }
                .toSet()
            PocketId.entries.filter { it in validSet }
        }
    }

    val safeShotIndex = selectedShotIndex.coerceIn(0, (rankedShots.size - 1).coerceAtLeast(0))
    val selectedShot = rankedShots.getOrNull(safeShotIndex)

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

    /** Back to [route]: a tab through the tab bar's own path, anything else as it is. */
    fun goToRoute(route: ActiveRoute) {
        if (route is ActiveRoute.MainTab) navigateToTab(route.tab) else activeRoute = route
    }

    fun handleMoveBall(ballNumber: Int, newPosition: Vec2) {
        // Moving a ball while a later shot is shown makes that table the new start of the plan; the group an
        // accepted 8-ball shot assigned stays with it.
        val focus = activeRolling
        tableSession = if (focus != null) {
            tableSession.moveBall(ballNumber, newPosition, from = focus.focusLayout, rulesAtFrom = focus.focusRules)
        } else {
            tableSession.moveBall(ballNumber, newPosition)
        }
        hasActiveSession = true
    }

    fun resetSandboxToPlanDefaults() {
        planSession.resetFocusedStep()
        selectedShotIndex = 0
    }

    fun handleUpdateSandboxTip(nextTip: TipOffset?) {
        if (activeRolling == null) return
        planSession.edit { it.copy(tip = nextTip) }
    }

    fun handleUpdateSandboxSpeed(nextUnits: Double?) {
        if (activeRolling == null) return
        val snapped = nextUnits?.let(PlayerProfile::snapSpeedUnits)
        planSession.edit { it.copy(speedUnits = snapped) }
        selectedShotIndex = 0
    }

    fun handleSelectPocket(pocket: PocketId?) {
        if (activeRolling == null) return
        planSession.edit { it.copy(pocketId = pocket) }
        selectedShotIndex = 0
    }

    fun handleToggleCueSandbox() {
        showCueSandboxPanel = !showCueSandboxPanel
    }

    fun handlePrevStep() {
        if (activeRolling?.canGoBack == true && !planSession.isBusy) {
            planSession.submit(PlanCommand.Back)
        }
    }

    fun handleNextStep() {
        // Busy covers the moment between a tap and the planner returning, so a quick second tap cannot accept twice.
        if (activeRolling?.canAdvance == true && !planSession.isBusy) {
            planSession.submit(PlanCommand.Advance)
        }
    }

    fun handleToggleAllStepsOverlay() {
        showAllStepsOverlay = !showAllStepsOverlay
    }

    fun performResetWithUndo() {
        val previousSession = tableSession
        val previousHasSession = hasActiveSession

        tableSession = previousSession.reset(scenarios[0])
        selectedShotIndex = 0
        planRestartToken += 1
        hasActiveSession = false

        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Table layout reset",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                tableSession = previousSession
                hasActiveSession = previousHasSession
                planRestartToken += 1
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
        if (!termsAccepted) {
            TermsAcknowledgementScreen(
                onAgree = {
                    setupStore.saveAcceptedTermsVersion(LegalConfig.TERMS_VERSION)
                    termsAccepted = true
                },
            )
            return@MaterialTheme
        }
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
                    tableSession = tableSession.startSetup(
                        index = matchingPresetIdx,
                        scenario = scenarios[matchingPresetIdx],
                        spec = tableSpec,
                        rules = nextRules,
                    )
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

            // System Back. Registered before the screens below, so anything a screen handles itself (the path
            // inspector card on the table, a dialog) is dealt with first.
            val backAction = backActionFor(
                BackContext(
                    route = activeRoute,
                    lastPrimaryTab = lastPrimaryTab,
                    isFullscreenTableShowing = showFullscreenLandscape,
                    isDrawerOpen = drawerState.isOpen,
                    isSheetOpen = showGameSetupSheet || showEditBallsSheet || showShotOptionsSheet || showQuickSpinSheet,
                ),
            )
            PlatformBackHandler(enabled = backAction != BackAction.LeaveApp) {
                when (backAction) {
                    BackAction.CloseSheet -> {
                        showGameSetupSheet = false
                        showEditBallsSheet = false
                        showShotOptionsSheet = false
                        showQuickSpinSheet = false
                    }
                    BackAction.CloseDrawer -> scope.launch { drawerState.close() }
                    BackAction.ExitFullscreen -> {
                        if (isFullscreenTable) {
                            isFullscreenTable = false
                        } else {
                            dismissAutoLandscape = true
                        }
                    }
                    is BackAction.Navigate -> goToRoute(backAction.route)
                    BackAction.LeaveApp -> Unit
                }
            }

            if (showFullscreenLandscape) {
                FullscreenTableScreen(
                    displayMode = displayMode,
                    fallbackAdvice = fallbackAdvice,
                    planError = planError,
                    onDisplayModeChange = { nextMode ->
                        displayMode = nextMode
                        persistSetupPreferences(mode = nextMode)
                    },
                    displayedLayout = displayedLayout,
                    rulesState = rulesState,
                    ballSetProfile = ballSetProfile,
                    planningReadiness = planningReadiness,
                    rollingPlan = activeRolling,
                    selectedShot = selectedShot,
                    showAllStepsOverlay = showAllStepsOverlay,
                    isPlanning = isPlanning,
                    isDraggingBall = isDraggingBall,
                    visibleSpins = visibleSpins,
                    showHeatZone = showHeatZone,
                    showLandingCloud = showLandingCloud,
                    playablePockets = playablePockets,
                    customPocketOverride = editedPocket,
                    onSelectPocketOverride = ::handleSelectPocket,
                    onMoveBall = ::handleMoveBall,
                    onDragStateChanged = { isDraggingBall = it },
                    onPrevStep = ::handlePrevStep,
                    onNextStep = ::handleNextStep,
                    onToggleAllStepsOverlay = ::handleToggleAllStepsOverlay,
                    onResetStep = ::resetSandboxToPlanDefaults,
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
                    customTipOffset = editedTip,
                    defaultTipForShot = plannerPick?.recommendedTip,
                    speedUnitsOverride = editedSpeedUnits,
                    defaultSpeedUnitsForShot = plannerPick?.recommendedSpeedUnits,
                    playerProfile = playerProfile,
                    sandboxTargetBallNumber = editedTargetBall,
                    showCueSandboxPanel = showCueSandboxPanel,
                    hasSandboxOverrides = hasSandboxOverrides,
                    onToggleCueSandbox = ::handleToggleCueSandbox,
                    onCloseCueSandbox = { showCueSandboxPanel = false },
                    onUpdateCustomTip = ::handleUpdateSandboxTip,
                    onSelectSpeedUnits = ::handleUpdateSandboxSpeed,
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
                        DrawerNavItem(
                            label = "What's New",
                            icon = RunoutIcon.INFO,
                            selected = activeRoute is ActiveRoute.WhatsNew,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigateToSubRoute(ActiveRoute.WhatsNew(fromAbout = false))
                            },
                        )
                        DrawerNavItem(
                            label = "About & Legal",
                            icon = RunoutIcon.INFO,
                            selected = activeRoute == ActiveRoute.AboutLegal,
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigateToSubRoute(ActiveRoute.AboutLegal)
                            },
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = RunoutSpacing.Element),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
                        )
                        TextButton(
                            onClick = {
                                scope.launch { drawerState.close() }
                                navigateToSubRoute(ActiveRoute.WhatsNew(fromAbout = false))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = RunoutSpacing.Element)
                                .defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                        ) {
                            Text(
                                text = "Runout ${AppVersion.fullLabel} • View changelog",
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                        ActiveRoute.AboutLegal -> "About & Legal"
                        is ActiveRoute.LegalDocumentView -> r.document.title
                        is ActiveRoute.WhatsNew -> "What's New"
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
                                        // The same destination as the system Back button.
                                        val action = backActionFor(BackContext(activeRoute, lastPrimaryTab))
                                        if (action is BackAction.Navigate) goToRoute(action.route)
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
                                    contentDescription = if (isTopLevel) "Open menu" else "Back",
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
                                            contentDescription = "Enter fullscreen table",
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
                                            contentDescription = "Scan or import a table photo",
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
                                                contentDescription = "More options",
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
                                    tableSession = tableSession.withRules(nextRules)
                                    hasActiveSession = true
                                    selectedShotIndex = 0
                                    planRestartToken += 1
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
                                    tableSession = tableSession.editBalls { SampleLayouts.addMissingBall(it, ballNumber) }
                                    hasActiveSession = true
                                },
                                onMoveBall = ::handleMoveBall,
                                isDraggingBall = isDraggingBall,
                                onDragStateChanged = { isDraggingBall = it },
                                isPlanning = isPlanning,
                                planningReadiness = planningReadiness,
                                rankedShots = rankedShots,
                                selectedShotIndex = safeShotIndex,
                                fallbackAdvice = fallbackAdvice,
                                planError = planError,
                                onSelectShot = { selectedShotIndex = it },
                                selectedShot = selectedShot,
                                visibleSpins = visibleSpins,
                                onToggleSpin = { spin ->
                                    visibleSpins = if (spin in visibleSpins) visibleSpins - spin else visibleSpins + spin
                                },
                                showHeatZone = showHeatZone,
                                showLandingCloud = showLandingCloud,
                                customTipOffset = editedTip,
                                onUpdateCustomTip = ::handleUpdateSandboxTip,
                                rollingPlan = activeRolling,
                                onSelectAlternative = planSession::chooseAlternative,
                                showAllStepsOverlay = showAllStepsOverlay,
                                onPrevStep = ::handlePrevStep,
                                onNextStep = ::handleNextStep,
                                onToggleAllStepsOverlay = ::handleToggleAllStepsOverlay,
                                onResetStep = ::resetSandboxToPlanDefaults,
                                playablePockets = playablePockets,
                                customPocketOverride = editedPocket,
                                onSelectPocketOverride = ::handleSelectPocket,
                                defaultTipForShot = plannerPick?.recommendedTip,
                                defaultSpeedUnitsForShot = plannerPick?.recommendedSpeedUnits,
                                speedUnitsOverride = editedSpeedUnits,
                                sandboxTargetBallNumber = editedTargetBall,
                                showCueSandboxPanel = showCueSandboxPanel,
                                hasSandboxOverrides = hasSandboxOverrides,
                                onToggleCueSandbox = ::handleToggleCueSandbox,
                                onCloseCueSandbox = { showCueSandboxPanel = false },
                                onUpdateSandboxTip = ::handleUpdateSandboxTip,
                                onSelectSpeedUnits = ::handleUpdateSandboxSpeed,
                                onResetSandboxDefaults = ::resetSandboxToPlanDefaults,
                                onUpdateClothParams = { updatedCloth ->
                                    tableSession = tableSession.withCloth(updatedCloth)
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
                                    tableSession = tableSession.loadLayout(layout, rules)
                                    hasActiveSession = true
                                    planRestartToken += 1
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
                            onConfirmScannedLayout = { scannedLayout, scannedRules, scannedBallSet, chosenCloth ->
                                // The scan replaces the balls; the table keeps its calibration, cloth and pockets
                                // unless another cloth was set on the scan screen.
                                tableSession = tableSession.confirmScan(scannedLayout, scannedRules, chosenCloth)
                                ballSetProfile = scannedBallSet
                                hasActiveSession = true
                                planRestartToken += 1
                                selectedShotIndex = 0
                                navigateToTab(PrimaryTab.PLAN)
                            },
                        )

                        is ActiveRoute.LearnWalkthroughPlayer -> WalkthroughDetailPlayerScreen(
                            walkthrough = r.walkthrough,
                            ballSet = ballSetProfile,
                            onTryOnTablePlanner = { layout, rules ->
                                tableSession = tableSession.loadLayout(layout, rules)
                                hasActiveSession = true
                                planRestartToken += 1
                                selectedShotIndex = 0
                                navigateToTab(PrimaryTab.PLAN)
                            },
                        )

                        is ActiveRoute.LearnTutorialPlayer -> TutorialDetailPlayerScreen(
                            tutorial = r.tutorial,
                            ballSet = ballSetProfile,
                            onTryOnTablePlanner = { layout, rules ->
                                tableSession = tableSession.loadLayout(layout, rules)
                                hasActiveSession = true
                                planRestartToken += 1
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
                                if (tableSession.canApply(profile)) {
                                    tableSession = tableSession.applyProfile(profile)
                                    hasActiveSession = true
                                    planRestartToken += 1
                                    selectedShotIndex = 0
                                    navigateToTab(PrimaryTab.PLAN)
                                } else {
                                    val profileKind = profile.baseTableSpec.kind.name.lowercase()
                                    val tableKind = currentLayout.table.kind.name.lowercase()
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "That profile is for a $profileKind table, so it can't be used on the " +
                                                "$tableKind table in use. Choose a matching table size first.",
                                        )
                                    }
                                }
                            },
                        )

                        ActiveRoute.SnookerCoach -> ExtrasAndToolsScreen(
                            playerProfile = playerProfile,
                            focusedDestination = ToolFocusDestination.SNOOKER_COACH,
                            onLoadLayoutIntoPlanner = { layout, rules ->
                                tableSession = tableSession.loadLayout(layout, rules)
                                planRestartToken += 1
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

                        ActiveRoute.AboutLegal -> AboutLegalScreen(
                            onOpenDocument = { activeRoute = ActiveRoute.LegalDocumentView(it) },
                            onOpenWhatsNew = { activeRoute = ActiveRoute.WhatsNew(fromAbout = true) },
                        )

                        is ActiveRoute.LegalDocumentView -> LegalDocumentScreen(
                            document = r.document,
                            onOpenDocument = { activeRoute = ActiveRoute.LegalDocumentView(it) },
                        )

                        is ActiveRoute.WhatsNew -> WhatsNewScreen()

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
                    tableSession = tableSession.loadPreset(index, scenarios[index])
                    hasActiveSession = true
                    planRestartToken += 1
                    selectedShotIndex = 0
                },
                onStartBlankTable = {
                    tableSession = tableSession.blankTable()
                    planRestartToken += 1
                    hasActiveSession = true
                },
                onSelectPocketPreset = { preset ->
                    // Session only: the pocket cut is not part of the saved setup.
                    tableSession = tableSession.choosePocketPreset(preset)
                    hasActiveSession = true
                    planRestartToken += 1
                    selectedShotIndex = 0
                },
                onResizeTable = { newSpec ->
                    val next = tableSession.resizeTable(newSpec)
                    tableSession = next
                    hasActiveSession = true
                    persistSetupPreferences(tableSpec = next.table)
                },
                onSelectRules = { nextRules ->
                    tableSession = tableSession.withRules(nextRules)
                    hasActiveSession = true
                    planRestartToken += 1
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
                    tableSession = tableSession.editBalls { SampleLayouts.toggleObjectBall(it, num) }
                    hasActiveSession = true
                },
                onAddMissingCueBall = {
                    tableSession = tableSession.editBalls { SampleLayouts.addMissingBall(it, 0) }
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
                onSelectHorizon = { selectedHorizon = it },
                speedUnitsOverride = editedSpeedUnits,
                onSelectSpeedUnits = ::handleUpdateSandboxSpeed,
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
                    tableSession = tableSession.withCloth(updatedCloth)
                    hasActiveSession = true
                },
                onDismiss = { showShotOptionsSheet = false },
            )
        }

        if (showQuickSpinSheet) {
            QuickSpinSheet(
                customTipOffset = editedTip,
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
    fallbackAdvice: FallbackAdvice?,
    planError: String?,
    selectedShotIndex: Int,
    onSelectShot: (Int) -> Unit,
    selectedShot: ShotRecommendation?,
    visibleSpins: Set<CueBallSpinPreset>,
    onToggleSpin: (CueBallSpinPreset) -> Unit,
    showHeatZone: Boolean,
    showLandingCloud: Boolean,
    customTipOffset: TipOffset?,
    onUpdateCustomTip: (TipOffset?) -> Unit,
    rollingPlan: RollingPlanState?,
    onSelectAlternative: (RunoutPlan) -> Unit,
    showAllStepsOverlay: Boolean,
    onPrevStep: () -> Unit,
    onNextStep: () -> Unit,
    onToggleAllStepsOverlay: () -> Unit,
    onResetStep: () -> Unit,
    playablePockets: List<PocketId>,
    customPocketOverride: PocketId?,
    onSelectPocketOverride: (PocketId?) -> Unit,
    defaultTipForShot: TipOffset? = null,
    defaultSpeedUnitsForShot: Double? = null,
    speedUnitsOverride: Double? = null,
    sandboxTargetBallNumber: Int? = null,
    showCueSandboxPanel: Boolean = false,
    hasSandboxOverrides: Boolean = false,
    onToggleCueSandbox: () -> Unit = onOpenQuickSpinSheet,
    onCloseCueSandbox: () -> Unit = {},
    onUpdateSandboxTip: (TipOffset?) -> Unit = onUpdateCustomTip,
    onSelectSpeedUnits: (Double?) -> Unit = {},
    onResetSandboxDefaults: () -> Unit = {},
    onUpdateClothParams: (ClothParams) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activePlan = rollingPlan?.window
    val activeStep = rollingPlan?.focusStep
    val activeTargetPocketId = activeStep?.pocketId ?: selectedShot?.pocket?.id
    // The plan from the focused shot, then the other routes the planner found for that shot.
    val routePlans = remember(rollingPlan) {
        listOfNotNull(rollingPlan?.window) + rollingPlan?.alternatives.orEmpty()
    }

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
                        // The window starts at the focused shot: one line set in Step mode, all of it in All mode.
                        focusedStepIndex = if (showAllStepsOverlay) null else 0,
                        isDraggingBall = isDraggingBall,
                        availablePockets = if (planningReadiness.isReady) playablePockets else emptyList(),
                        onSelectPocket = if (planningReadiness.isReady) { pocketId ->
                            onSelectPocketOverride(if (customPocketOverride == pocketId) null else pocketId)
                        } else null,
                        onTapCueBall = if (planningReadiness.isReady) onToggleCueSandbox else null,
                        highlightCueBall = showCueSandboxPanel || hasSandboxOverrides,
                        showHeatZone = displayMode == AppDisplayMode.PRO && showHeatZone,
                        showLandingCloud = if (displayMode == AppDisplayMode.SIMPLE) true else showLandingCloud,
                        displayMode = displayMode,
                        onToggleFullscreen = onEnterFullscreen,
                        fallbackAdvice = fallbackAdvice,
                    )

                    if (planningReadiness.isReady) {
                        StepPlaybackControlBar(
                            rollingPlan = rollingPlan,
                            selectedShot = selectedShot,
                            ballSetProfile = ballSetProfile,
                            tableSpec = currentLayout.table,
                            showAllStepsOverlay = showAllStepsOverlay,
                            isPlanning = isPlanning,
                            onPrevStep = onPrevStep,
                            onNextStep = onNextStep,
                            onToggleAllSteps = onToggleAllStepsOverlay,
                            onResetStep = onResetStep,
                            onTapCueHitGuide = onToggleCueSandbox,
                            fallbackAdvice = fallbackAdvice,
                        )

                        if (rollingPlan != null && (playablePockets.size > 1 || customPocketOverride != null)) {
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
                            val resolvedSpeed = speedUnitsOverride
                                ?: activeStep?.recommendedSpeedUnits
                                ?: defaultSpeedUnitsForShot
                                ?: 4.5
                            val cueToGhostMeters = activeStep?.compensatedAim?.geometricAim?.cueToGhostDistanceMeters
                                ?: selectedShot?.aim?.cueToGhostDistanceMeters
                            val resolvedAim = activeStep?.compensatedAim?.geometricAim
                                ?: selectedShot?.aim
                            val resolvedPocket = activeStep?.pocketId?.let { currentLayout.table.pocketById(it) }
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
                                onUpdateTip = onUpdateSandboxTip,
                                onUpdateSpeedUnits = onSelectSpeedUnits,
                                onResetDefaults = onResetSandboxDefaults,
                                onClose = onCloseCueSandbox,
                                compactVertical = false,
                                cueToObjectDistanceMeters = cueToGhostMeters,
                                activeAim = resolvedAim,
                                activePocket = resolvedPocket,
                                activeTargetBallNumber = resolvedTargetNumber,
                                playerProfile = playerProfile,
                                tableSpec = currentLayout.table,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when {
                                isDraggingBall -> "Release ball to re-plan"
                                isPlanning -> "Updating runout plan…"
                                playablePockets.size > 1 && rollingPlan != null ->
                                    "Drag balls • Tap white ball for spin & speed • Tap pocket to switch"
                                else -> "Drag balls • Tap white ball for spin & speed"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isPlanning || isDraggingBall) {
                                RunoutColors.WarningAmber
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (hasSandboxOverrides) {
                            FilterChip(
                                selected = true,
                                onClick = onResetSandboxDefaults,
                                modifier = Modifier.height(34.dp),
                                label = {
                                    Text(
                                        text = "Reset Defaults",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                    )
                                },
                            )
                        } else if (displayMode == AppDisplayMode.PRO && showHeatZone) {
                            Text(
                                text = "Heat Zone ON",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
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

        if (planError != null) {
            Text(
                text = planError,
                style = MaterialTheme.typography.bodyMedium,
                color = RunoutColors.DangerCoral,
            )
        }

        // 3. Hero Recommended Shot Section (Simple vs. Pro). A table with no direct pot gets the fallback card instead;
        // a table whose only pot is a long shot gets the card beside the shot, after the hero card.
        if (fallbackAdvice != null && rollingPlan?.window == null) {
            FallbackAdviceCard(
                advice = fallbackAdvice,
                displayMode = displayMode,
                ballSet = ballSetProfile,
                title = "No Direct Pot",
            )
        } else if (displayMode == AppDisplayMode.SIMPLE) {
            SimpleModeShotSection(
                rollingPlan = rollingPlan,
                fallbackShot = selectedShot,
                allPlans = routePlans,
                onSelectRoute = { index -> if (index > 0) routePlans.getOrNull(index)?.let(onSelectAlternative) },
                ballSet = ballSetProfile,
                tableSpec = currentLayout.table,
                onOpenQuickSpinSheet = onOpenQuickSpinSheet,
                onToggleCueSandbox = onToggleCueSandbox,
                saferOption = fallbackAdvice,
            )
        } else {
            ProModeStrategySection(
                rollingPlan = rollingPlan,
                selectedShot = selectedShot,
                saferOption = fallbackAdvice,
                runoutPlans = routePlans,
                onSelectRoute = { index -> if (index > 0) routePlans.getOrNull(index)?.let(onSelectAlternative) },
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
                tableSpec = currentLayout.table,
                onUpdateClothParams = onUpdateClothParams,
            )
        }
    }
}

/** Title of the fallback card: the kind of shot and the balls it involves. */
internal fun fallbackTitle(advice: FallbackAdvice): String {
    val balls = advice.targetBallNumbers
    return when (advice.kind) {
        FallbackKind.COMBINATION ->
            "Combination: #${balls.first()} into #${balls.getOrNull(1)} -> ${advice.pocketId?.displayName ?: "pocket"}"
        FallbackKind.KICK -> "Kick: one cushion to #${balls.first()}"
        FallbackKind.SAFETY -> "Safety: hit #${balls.first()}, leave no pot"
    }
}

/** What the fallback card's chance figure measures, in a few words. */
internal fun fallbackChanceLabel(kind: FallbackKind): String = when (kind) {
    FallbackKind.COMBINATION -> "Make"
    FallbackKind.KICK -> "Legal hit"
    FallbackKind.SAFETY -> "No pot left"
}

/**
 * Shown in place of the shot card for an edited shot that does not play as set (it misses, scratches or drops another
 * ball). The step bar says why Next is unavailable; this card only says there is no recommendation to follow.
 */
@Composable
private fun NotPlayableAsSetCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = RunoutColors.WarningAmber.copy(alpha = 0.12f)),
        border = BorderStroke(1.2.dp, RunoutColors.WarningAmber.copy(alpha = 0.70f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunoutSpacing.Margin),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
        ) {
            Text(
                text = "This shot doesn't work as set",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = RunoutColors.WarningAmber,
            )
            Text(
                text = "Change the spin, speed or pocket, or tap Reset step to go back to the planned shot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * The advice card: in place of the recommended shot when the legal ball has no direct pot ([title] "No Direct Pot"),
 * or beside a shot that is a long shot ("A Safer Option"). The kind of shot, one sentence of reasoning and three data
 * points (speed, spin, chance); Pro mode adds the numbers behind them.
 */
@Composable
private fun FallbackAdviceCard(
    advice: FallbackAdvice,
    displayMode: AppDisplayMode,
    ballSet: BallSetProfile,
    title: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
        RunoutSectionHeader(title = title)
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
                    BallNumberBadge(
                        ballNumber = advice.targetBallNumbers.first(),
                        ballSet = ballSet,
                        sizeDp = 24.dp,
                    )
                    Text(
                        text = fallbackTitle(advice),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = advice.reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Speed: ${plainSpeedLabel(advice.speedUnits)} (${PlayerProfile.formatSpeedUnits(advice.speedUnits)}/10)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = "Spin: ${plainTipLabel(advice.tip)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${fallbackChanceLabel(advice.kind)}: ${formatPercentInt(advice.outcomeProbability)}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (displayMode == AppDisplayMode.PRO) {
                    Text(
                        text = fallbackProDetails(advice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The numbers behind the fallback card, for Pro mode. */
internal fun fallbackProDetails(advice: FallbackAdvice): String {
    val parts = mutableListOf(
        "${fallbackChanceLabel(advice.kind)} ${formatOneDecimal(advice.outcomeProbability * 100.0)}%",
        "speed ${PlayerProfile.formatSpeedUnits(advice.speedUnits)}/10",
        "tip side ${formatTwoDecimals(advice.tip.sideRatio)} R, height ${formatTwoDecimals(advice.tip.verticalRatio)} R",
    )
    advice.cushionPoint?.let { parts += "cushion point x ${formatTwoDecimals(it.x)} m, y ${formatTwoDecimals(it.y)} m" }
    advice.cueBallRest?.let { parts += "cue ball stops at x ${formatTwoDecimals(it.x)} m, y ${formatTwoDecimals(it.y)} m" }
    return parts.joinToString(" · ")
}

private fun formatTwoDecimals(value: Double): String {
    val hundredths = (value * 100.0).roundToInt()
    val sign = if (hundredths < 0) "-" else ""
    val magnitude = abs(hundredths)
    return "$sign${magnitude / 100}.${(magnitude % 100).toString().padStart(2, '0')}"
}

@Composable
private fun SimpleModeShotSection(
    rollingPlan: RollingPlanState?,
    fallbackShot: ShotRecommendation?,
    allPlans: List<RunoutPlan>,
    onSelectRoute: (Int) -> Unit,
    ballSet: BallSetProfile,
    tableSpec: TableSpec,
    onOpenQuickSpinSheet: () -> Unit,
    onToggleCueSandbox: () -> Unit = onOpenQuickSpinSheet,
    saferOption: FallbackAdvice? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
        val activePlan = rollingPlan?.window
        val steps = activePlan?.steps.orEmpty()
        val activeStep = steps.firstOrNull()
        val nextStep = steps.getOrNull(1)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RunoutSectionHeader(
                title = if (activeStep != null) "Shot ${activeStep.stepNumber}" else "Recommended Shot",
                modifier = Modifier.weight(1f),
            )
            GlossaryInfoButton(
                term = MAKE_POSITION_RUNOUT_TERM,
                explanation = MAKE_POSITION_RUNOUT_EXPLANATION,
            )
        }

        if (activePlan != null && activeStep != null) {
            val speedWord = plainSpeedLabel(activeStep.recommendedSpeedUnits)
            val speedText = PlayerProfile.formatSpeedUnits(activeStep.recommendedSpeedUnits)
            val tipWord = plainTipLabel(activeStep.recommendedTip)
            val tightness = computeShotTightness(activeStep, nextStep)

            if (!activeStep.legalAsPlanned) NotPlayableAsSetCard() else Card(
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
                        BallNumberBadge(
                            ballNumber = activeStep.targetBall.number,
                            ballSet = ballSet,
                            sizeDp = 24.dp,
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
                                text = "Speed: $speedWord ($speedText/10)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = "Spin: $tipWord",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Zone: ${shotTightnessLabel(tightness)} sweet spot (pinch table to zoom • tap white ball for spin)",
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

            if (saferOption != null) {
                FallbackAdviceCard(
                    advice = saferOption,
                    displayMode = AppDisplayMode.SIMPLE,
                    ballSet = ballSet,
                    title = SAFER_OPTION_TITLE,
                )
            }

            if (activeStep.legalAsPlanned) {
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
                            isSelected = index == 0,
                            onClick = { onSelectRoute(index) },
                        )
                    }
                }
            }
        } else if (fallbackShot != null) {
            // The tip that gives the recommended spin state at the object ball for this distance and speed.
            val solvedTip = fallbackShot.solvedTip(tableSpec)
            val fallbackTip = solvedTip ?: TipOffset()
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
                        val fallbackSpeedText = PlayerProfile.formatSpeedUnits(fallbackShot.recommendedSpeedUnits)
                        Text(
                            text = "Speed: ${plainSpeedLabel(fallbackShot.recommendedSpeedUnits)} ($fallbackSpeedText/10) • Spin: " +
                                if (solvedTip != null) {
                                    plainTipLabel(solvedTip)
                                } else {
                                    "${impactStateWord(fallbackShot.recommendedSpin)} is out of reach at this speed"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Make ${formatPercentInt(fallbackShot.makeProbability)}% • " +
                                "${shotTightnessLabel(fallbackTightness)} sweet spot",
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
                    text = if (rollingPlan?.isRackComplete == true) {
                        "Rack complete. Every ball in the plan has been accepted. Press Prev to revisit a shot."
                    } else {
                        "No open legal pots found from this cue ball position. Try dragging balls or playing a safety."
                    },
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
    rollingPlan: RollingPlanState?,
    selectedShot: ShotRecommendation?,
    saferOption: FallbackAdvice?,
    runoutPlans: List<RunoutPlan>,
    onSelectRoute: (Int) -> Unit,
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
    tableSpec: TableSpec,
    onUpdateClothParams: (ClothParams) -> Unit,
) {
    val activePlan = rollingPlan?.window
    val clothParams = tableSpec.cloth
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
                    term = MAKE_POSITION_RUNOUT_TERM,
                    explanation = MAKE_POSITION_RUNOUT_EXPLANATION,
                )
            }
            if (activePlan?.steps?.firstOrNull()?.legalAsPlanned == false) NotPlayableAsSetCard() else Card(
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
                            onTapCueHitGuide = onToggleCueSandbox,
                        )
                    } else if (selectedShot != null) {
                        SelectedShotReadoutBar(
                            shot = selectedShot,
                            tableSpec = tableSpec,
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

        if (saferOption != null) {
            FallbackAdviceCard(
                advice = saferOption,
                displayMode = AppDisplayMode.PRO,
                ballSet = ballSetProfile,
                title = SAFER_OPTION_TITLE,
            )
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
                recommendedStep = rollingPlan?.focusStep,
                tableSpec = tableSpec,
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
                        isSelected = index == 0,
                        onClick = { onSelectRoute(index) },
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
    tableSpec: TableSpec,
    onTapCueHitGuide: (() -> Unit)? = null,
) {
    val cutDeg = formatOneDecimal(abs(radiansToDegrees(shot.aim.cutAngleRadians)))
    val cueDistText = formatDistanceMeters(shot.aim.cueToGhostDistanceMeters)
    val obDistText = formatDistanceMeters(shot.aim.objectToPocketDistanceMeters)
    val scratchSpins = shot.cueBallPaths.values.filter { it.hitsScratchPocket != null }
    // The tip that gives the recommended spin state at the object ball for this distance and speed, if one does.
    val solvedTip = shot.solvedTip(tableSpec)
    val recommendedTip = solvedTip ?: TipOffset()
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
                    text = "Cut $cutDeg° • Speed ${PlayerProfile.formatSpeedUnits(shot.recommendedSpeedUnits)}/10",
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
                text = if (solvedTip != null) {
                    "Spin: ${plainTipLabel(solvedTip)} • ${shotTightnessLabel(tightness)} sweet spot"
                } else {
                    "Spin: ${impactStateWord(shot.recommendedSpin)} at the ball is out of reach at this speed"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (solvedTip != null) MaterialTheme.colorScheme.primary else RunoutColors.WarningAmber,
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
            text = "Cue-Ball Paths by Spin at the Object Ball",
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
                    CueBallSpinPreset.STUN -> "STUN at ball (90°)"
                    CueBallSpinPreset.FOLLOW -> "ROLL at ball (30°)"
                    CueBallSpinPreset.DRAW -> "DRAW at ball"
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
    // The list is ordered by the shot's score, which also weighs position; the figure shown is the chance to pot.
    val makeText = "Make ${formatPercentInt(shot.makeProbability)}%"
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
                    BallNumberBadge(
                        ballNumber = shot.targetBall.number,
                        ballSet = ballSet,
                        sizeDp = 22.dp,
                    )
                    Text(
                        text = "#$rank: Ball ${shot.targetBall.number} -> ${shot.pocket.id.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = makeText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "Cut: $cutAngleDeg°  •  Speed: ${PlayerProfile.formatSpeedUnits(shot.recommendedSpeedUnits)}/10  •  Spin: ${shot.recommendedSpin.name}",
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
    onTapCueHitGuide: (() -> Unit)? = null,
) {
    val steps = plan.steps
    if (steps.isEmpty()) return
    // The window starts at the focused shot.
    val activeStep = steps.first()
    val nextStep = steps.getOrNull(1)
    val clearPct = formatPercentOneDecimal(plan.cumulativeRunoutProbability)
    val windowLabel = if (steps.size == 1) "This shot" else "Next ${steps.size} shots"
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
                text = "$windowLabel: $clearPct%",
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
                    text = "Cut $cutDeg° • Speed ${PlayerProfile.formatSpeedUnits(activeStep.recommendedSpeedUnits)}/10 • ±$spreadCm cm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Spin: ${plainTipLabel(activeStep.recommendedTip)} • ${shotTightnessLabel(tightness)} sweet spot",
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
    tableSpec: TableSpec,
    onUpdateCustomTip: (TipOffset?) -> Unit,
) {
    val displayedTip = customTipOffset ?: recommendedStep?.recommendedTip ?: TipOffset()
    val compAim = recommendedStep?.compensatedAim

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 166.dp),
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
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = displayedTip.describeSpinLabel(short = false),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "Offset: ${formatSignedTwoDecimals(displayedTip.clampedSideRatio)}R side, ${formatSignedTwoDecimals(displayedTip.clampedVerticalRatio)}R vert",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (recommendedStep != null) {
                        val pocketSpec = tableSpec.pocketById(recommendedStep.pocketId)
                        val transitionText = SpinPhysics.describeShotPowerAndSpin(
                            tip = displayedTip,
                            cueSpeedMps = recommendedStep.cueSpeedMps,
                            aim = recommendedStep.compensatedAim.geometricAim,
                            pocket = pocketSpec,
                            table = tableSpec,
                            targetBallNumber = recommendedStep.targetBall.number,
                        )
                        Text(
                            text = transitionText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (transitionText.contains("stops short", ignoreCase = true)) {
                                RunoutColors.WarningAmber
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (compAim != null) {
                    CompensationMetricRow("Squirt", "${formatSignedDegrees(compAim.squirtAngleRadians)}°")
                    CompensationMetricRow("Swerve", "${formatSignedDegrees(compAim.swerveAngleRadians)}°")
                    CompensationMetricRow("Total Throw", "${formatSignedDegrees(compAim.totalThrowAngleRadians)}°")
                    CompensationMetricRow("Net Adjust", "${formatSignedDegrees(compAim.netCueAimAdjustmentRadians)}°", highlight = true)
                }
            }
        }
        if (recommendedStep != null) {
            // Tips that give the spin state at the object ball for this shot's distance and speed. A state that no
            // tip can give here (a stop shot that is too long or too soft) is shown but cannot be chosen.
            val cueToGhost = recommendedStep.compensatedAim.geometricAim.cueToGhostDistanceMeters
            val impactStates = listOf(
                CueBallSpinPreset.STUN to "Stun at ball",
                CueBallSpinPreset.FOLLOW to "Roll at ball",
                CueBallSpinPreset.DRAW to "Draw at ball",
            ).map { (state, label) ->
                label to SpinPhysics.tipForImpactState(state, recommendedStep.cueSpeedMps, cueToGhost, tableSpec)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                impactStates.forEach { (label, solved) ->
                    val isSelected = solved != null && customTipOffset != null &&
                        abs(customTipOffset.clampedSideRatio - solved.clampedSideRatio) < 0.02 &&
                        abs(customTipOffset.clampedVerticalRatio - solved.clampedVerticalRatio) < 0.02
                    FilterChip(
                        selected = isSelected,
                        enabled = solved != null,
                        onClick = { if (solved != null) onUpdateCustomTip(solved) },
                        modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                        label = { Text(if (solved != null) label else "$label (out of reach)") },
                    )
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
                    "Position ${formatPercentInt(it)}% (${step.positionDifficulty?.label})"
                } ?: "Final ball"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BallNumberBadge(
                        ballNumber = step.targetBall.number,
                        ballSet = ballSet,
                        sizeDp = 18.dp,
                    )
                    Text(
                        text = "${step.stepNumber}. #${step.targetBall.number} -> ${step.pocketId.displayName} • Make $makePct% • $posText • Speed ${PlayerProfile.formatSpeedUnits(step.recommendedSpeedUnits)}/10",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun plainSpeedLabel(units: Double): String = when {
    units <= 1.5 -> "Touch / Lag"
    units <= 3.0 -> "Gentle"
    units <= 5.5 -> "Medium"
    units <= 7.5 -> "Firm"
    else -> "Power"
}

private fun plainTipLabel(tip: TipOffset): String = tip.describeSpinLabel(short = false)

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

private fun formatTipDescription(tip: TipOffset): String = tip.describeSpinLabel(short = false)

private fun impactStateWord(spin: CueBallSpinPreset): String = when (spin) {
    CueBallSpinPreset.STUN -> "A stun hit"
    CueBallSpinPreset.FOLLOW -> "A rolling hit"
    CueBallSpinPreset.DRAW -> "A draw hit"
}
