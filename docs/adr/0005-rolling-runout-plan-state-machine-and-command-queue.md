# ADR 0005: Rolling run-out plan as a pure state machine driven by a command queue

- **Status:** Accepted
- **Date:** 2026-11-25

## Context

On the Plan and fullscreen table screens, a player walks a multi-shot run-out one shot at a time: accepting the focused shot to advance the look-ahead window, stepping back to reopen an earlier shot, overriding tip, speed, target ball, or pocket on the focused step, picking an alternative opening shot, changing the horizon, or dragging a ball on the table.

Each horizon search (`MultiShotPlanner.planRunoutWithStatus`) takes roughly 80–160 ms on the JVM and cannot run on the UI thread. Allowing UI events to launch concurrent searches or mutate shared plan state directly invites race conditions: a double-tap on Next could accept the same shot twice, a slow earlier search could overwrite a newer drag or edit, and an interrupted search could pollute memoized results with half-finished plans.

## Options Considered

- **Option A — Mutable planner session with internal coroutines inside `:engine`:** Couples `:engine` to coroutine scopes and UI cancellation lifecycles, complicates deterministic JVM testing, and mixes search logic with debouncing.
- **Option B — Pure `state -> state` state machine in `:engine` (`RollingRunoutPlanner`) driven by a single-consumer command queue in `:composeApp` (`RollingPlanSession`) (Selected):** Keeps all planning transitions pure, synchronous, and unit-testable in `:engine`, while `:composeApp` owns sequencing, debouncing, coalescing, cancellation, and error containment.

## Decision

### 1. Pure state machine in `:engine` (`RollingRunoutPlanner`, `RollingPlanState`)

- Every operation in `RollingRunoutPlanner` is a synchronous `RollingPlanState -> RollingPlanState` transition with no coroutines or hidden mutable state: `start`, `acceptAndAdvance`, `back`, `setOverride`, `clearOverride`, `withHorizon`, `chooseAlternative`, `withDefaultFocusStep`, and `rebase`.
- Accepted steps stay frozen in `RollingPlanState.acceptedSteps`; `window` holds the next `horizon` shots (`1..MAX_HORIZON`, where `MAX_HORIZON = 5`, or up to six object balls under `deepAnalysis`), planned from `focusLayout` and `focusRules`.
- **Stability and change reporting:** When `acceptAndAdvance` slides the window forward, previously shown steps are kept unless the fresh plan's `RunoutPlan.expectedValueScore` improves by at least `UPDATE_THRESHOLD_FRACTION` (`0.05`). Steps that still change are split into `updatedStepNumbers` (different target ball or pocket) and `refinedStepNumbers` (same target and pocket, different tip or speed). `earlierViews` and `laterViews` (`RollingView`) preserve exact views across `back` and `acceptAndAdvance`; `userEditedStepNumbers` separates explicit user edits (`StepOverride`) from pinned choices loaded by `back` or `chooseAlternative`.
- **Advance refusal guard (`advanceRefusal`):** `acceptAndAdvance` refuses any focused step that cannot legally continue (rack already complete, cue ball stops short, target not potted, target drops in another pocket, cue ball scratches, illegal extra ball drop in 8-ball, or `!PlannedShotStep.legalAsPlanned`) and records `refusalReason` without advancing.
- **Cooperative stop hook and no cached partial results:** Every searching operation accepts `shouldStop: () -> Boolean`, forwarded to `MultiShotPlanner.planRunoutWithStatus`. When `RunoutSearchResult.stopped` is true, the operation abandons the transition (returning the incoming state unchanged, or an empty-window state from `start`) and never writes partial results into `PlanCache` (an immutable LRU memo capped at `PlanCache.MAX_ENTRIES = 16` that is excluded from `RollingPlanState` equality).

### 2. Single-consumer command queue in `:composeApp` (`RollingPlanSession`, `PlanCommand`)

- UI callbacks enqueue `PlanCommand` values (`Restart`, `Horizon`, `Edit`, `ChooseAlternative`, `Advance`, `Back`) via `RollingPlanSession.submit` onto an unbounded `Channel`. One coroutine (`RollingPlanSession.run`) consumes commands sequentially and runs `applyPlanCommand` on `computeDispatcher` (`Dispatchers.Default`), so no two planner calls overlap and a shot cannot be accepted twice.
- **Debouncing and coalescing:** `Restart` debounces for `150 ms` and `Edit` for `60 ms`; `coalescePlanCommands` discards commands before the last `Restart` and collapses consecutive `Edit` or consecutive `Horizon` commands while never dropping `Advance`, `Back`, or `ChooseAlternative`.
- **Obsolescence cancellation:** `@Volatile` `generation`, `restartEpoch`, `editEpoch`, and `horizonEpoch` counters drive the `shouldStop` hook passed to `RollingRunoutPlanner`. `clear()`, a newer `Restart`, or a superseding batch of `Edit` or `Horizon` commands trips `shouldStop` and discards the interrupted batch's output.
- **Error containment and busy state:** Non-cancellation exceptions from the planner are caught; the previous `state` stays visible, `hasFailed` is set (shown with `RollingPlanSession.PLAN_FAILED_MESSAGE`), `pendingEdit` is cleared, and the session remains usable. `isBusy` stays true while commands are outstanding so Next and Prev stay disabled. After each completed batch, the consumer refreshes `rankedShots` (`OneShotPlanner.rankShots`), `fallbackAdvice` (`fallbackAdviceFor` / `FallbackShotAdvisor.advise`), and `RollingRunoutPlanner.withDefaultFocusStep`.

## Consequences

- **Positive:** Every rolling-plan invariant (stability threshold, edit marks, view restoration, refusal rules, and never caching stopped searches) is tested deterministically on the JVM without UI or clocks; command coalescing and interruption are tested independently in `:composeApp`.
- **Trade-offs:** Interruption is cooperative via `shouldStop` polling inside `MultiShotPlanner.planRunoutWithStatus` (there is no wall-clock timeout or iterative deepening), and an interrupted search discards its work rather than surfacing a partial plan.

## Code References

- `engine/src/commonMain/kotlin/com/runout/engine/planner/RollingRunoutPlanner.kt` (`RollingRunoutPlanner`, `RollingPlanState`, `StepOverride`, `RollingView`, `PlanCache`)
- `engine/src/commonMain/kotlin/com/runout/engine/planner/MultiShotPlanner.kt` (`MultiShotPlanner.planRunoutWithStatus`, `RunoutSearchResult`, `PlannedShotStep.legalAsPlanned`)
- `composeApp/src/commonMain/kotlin/com/runout/app/planning/RollingPlanSession.kt` (`RollingPlanSession`, `PlanCommand`, `applyPlanCommand`, `coalescePlanCommands`, `fallbackAdviceFor`, `PendingEdit`)
- `composeApp/src/commonTest/kotlin/com/runout/app/planning/RollingPlanSessionTest.kt`, `composeApp/src/androidUnitTest/kotlin/com/runout/app/planning/RollingPlanSessionRunTest.kt`
