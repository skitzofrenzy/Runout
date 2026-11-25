# ADR 0007: Single table-state owner (TableSession) and pure Back navigation (BackNavigation)

- **Status:** Accepted
- **Date:** 2026-11-25

## Context

In `:composeApp`, many user flows modify the active table, balls, rules, or calibration profile: resizing the table, picking a pocket cut, editing cloth parameters, dragging balls, loading a sample scenario or blank table, opening a tutorial or walkthrough layout, applying a calibration profile, or confirming a photo scan. When each screen mutated `TableLayout` and `GameRulesState` directly, cross-cutting rules drifted (for instance, a photo scan or calibration profile could silently overwrite the chosen pocket cut or mix snooker and pool ball sizes).

Similarly, system Back on Android must close local UI overlays before popping pushed routes or switching tabs. Scattering `BackHandler` callbacks across screens without a central rule made precedence fragile and untestable without a device or emulator.

## Options Considered

- **Option A — Per-screen mutable state and platform navigation/ViewModel stacks:** Adds platform-bound ViewModel/back-stack machinery and duplicates table-update invariants across callbacks.
- **Option B — One immutable table-state owner (`TableSession`) and one pure Back decision function (`backActionFor` in `BackNavigation.kt`), wired from `App.kt` (Selected):** Centralizes all state-transition invariants and Back precedence in pure `commonMain` code testable on the JVM without a device.

## Decision

### 1. Single table-state owner (`TableSession`)

- `TableSession(layout, rules, activeProfileId, presetIndex)` in `composeApp/src/commonMain/kotlin/com/runout/app/planning/TableSession.kt` is the sole owner of the active `TableLayout`, `GameRulesState`, active calibration profile ID, and sample preset index. Every change goes through its named transitions: `withRules`, `editBalls`, `withCloth`, `resizeTable`, `choosePocketPreset`, `canApply`, `applyProfile`, `clearProfile`, `loadPreset`, `reset`, `loadLayout`, `confirmScan`, `blankTable`, `moveBall`, `startSetup`, and `TableSession.initial`.
- **Invariants enforced uniformly across all entry points:**
  - `TableSpec.kind` and ball radius change only via `resizeTable` to a table of the other kind or `loadLayout` (drills, tutorials, walkthroughs, or break setups that bring their own table). `applyProfile` refuses a `SavedTableProfile` of the other kind (`!canApply(profile)` returns `this`); `blankTable` places balls using `table.ballRadiusMeters`.
  - `TableSpec.pocketPreset` survives `applyProfile` (via `SavedTableProfile.toEffectiveTableSpec(table)`), `resizeTable` within the same kind, `loadPreset`, `reset`, and `confirmScan`. `choosePocketPreset` accepts only pool cuts on pool tables and `PocketPreset.SNOOKER` on snooker tables.
  - `confirmScan(scanned, rules, chosenCloth)` replaces the balls while keeping the table's cloth, `ballContact`, `pocketPreset`, and `activeProfileId` when the scanned table matches the current kind and size and `chosenCloth` was left unchanged; an explicit `chosenCloth` updates the cloth and clears `activeProfileId`; a different size of the same kind resizes the table and clears `activeProfileId`.
  - `withCloth`, `resizeTable`, and `loadLayout` clear `activeProfileId` because the table no longer matches the calibrated profile.
- `TableSession` is held in memory for the session (nothing in it is persisted across restarts).

### 2. Pure Back navigation (`BackNavigation.kt` and `PlatformBackHandler`)

- `backActionFor(context: BackContext): BackAction` decides system Back from one immutable snapshot (`route: ActiveRoute`, `lastPrimaryTab: PrimaryTab`, `isFullscreenTableShowing`, `isDrawerOpen`, `isSheetOpen`) in strict local-to-global order:
  1. `isSheetOpen` → `BackAction.CloseSheet`
  2. `isDrawerOpen` → `BackAction.CloseDrawer`
  3. `isFullscreenTableShowing` → `BackAction.ExitFullscreen`
  4. `ActiveRoute.LegalDocumentView` → `BackAction.Navigate(ActiveRoute.AboutLegal)`
  5. Any other pushed full-screen route (`PhotoScan`, `LearnWalkthroughPlayer`, `LearnTutorialPlayer`, `Calibration`, `SnookerCoach`, `BreakSpeedMeter`, `ScorekeeperAndClock`, `PlayerProfileAndBallSet`, `AboutLegal`) → `BackAction.Navigate(ActiveRoute.MainTab(context.lastPrimaryTab))`
  6. Non-Home `ActiveRoute.MainTab` → `BackAction.Navigate(ActiveRoute.MainTab(PrimaryTab.HOME))`
  7. `ActiveRoute.MainTab(PrimaryTab.HOME)` → `BackAction.LeaveApp`
- `expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)` delegates to `androidx.activity.compose.BackHandler` on Android (`PlatformBackHandler.android.kt`) and is a no-op on iOS (`PlatformBackHandler.ios.kt`). Composable last-handler-wins precedence allows purely local UI states (the table canvas path-inspector card and an open legal document inside `TermsAcknowledgementScreen`) to close themselves before the root `backActionFor` handler runs.

## Consequences

- **Positive:** Table invariants and Back behaviour are guaranteed consistent across every screen and verified by fast unit tests (`TableSessionTest`, `BackNavigationTest`) with no Android device required; adding a new route to `sealed interface ActiveRoute` forces an exhaustive compile-time update in `backActionFor`.
- **Trade-offs:** Keeping hoisted state in `App.kt` avoids `ViewModel` boilerplate, but requires discipline so future screens continue mutating table state exclusively through `TableSession` operations.

## Code References

- `composeApp/src/commonMain/kotlin/com/runout/app/planning/TableSession.kt` (`TableSession`)
- `composeApp/src/commonMain/kotlin/com/runout/app/navigation/BackNavigation.kt` (`BackAction`, `BackContext`, `backActionFor`)
- `composeApp/src/commonMain/kotlin/com/runout/app/navigation/AppRoutes.kt` (`PrimaryTab`, `ActiveRoute`)
- `composeApp/src/commonMain/kotlin/com/runout/app/navigation/PlatformBackHandler.kt`, `composeApp/src/androidMain/kotlin/com/runout/app/navigation/PlatformBackHandler.android.kt`, `composeApp/src/iosMain/kotlin/com/runout/app/navigation/PlatformBackHandler.ios.kt` (`PlatformBackHandler`)
- `composeApp/src/commonMain/kotlin/com/runout/app/App.kt`
- `composeApp/src/commonTest/kotlin/com/runout/app/planning/TableSessionTest.kt`, `composeApp/src/commonTest/kotlin/com/runout/app/navigation/BackNavigationTest.kt`
