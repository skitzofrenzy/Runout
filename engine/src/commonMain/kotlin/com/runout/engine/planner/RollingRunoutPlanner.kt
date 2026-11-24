package com.runout.engine.planner

import com.runout.engine.model.GameRulesState
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.TipOffset

/**
 * What the user changed on one planned shot. A `null` field leaves that choice to the planner.
 *
 * @property tip Cue-tip contact point.
 * @property speedUnits Stroke speed on the 20-point `0.5..10.0` scale.
 * @property pocketId Pocket the object ball is aimed at.
 * @property targetBallNumber Object ball to pot (only meaningful where the rules allow a choice).
 */
data class StepOverride(
    val tip: TipOffset? = null,
    val speedUnits: Double? = null,
    val pocketId: PocketId? = null,
    val targetBallNumber: Int? = null,
) {
    /** True when no choice is overridden. */
    val isEmpty: Boolean
        get() = tip == null && speedUnits == null && pocketId == null && targetBallNumber == null

    companion object {
        /** An override that pins every choice of [step], so re-planning reproduces that shot. */
        fun of(step: PlannedShotStep): StepOverride = StepOverride(
            tip = step.recommendedTip,
            speedUnits = step.recommendedSpeedUnits,
            pocketId = step.pocketId,
            targetBallNumber = step.targetBall.number,
        )
    }
}

/**
 * What the user sees at one focus: the planned window, the other first shots on offer and the already-shown steps
 * that changed on the last re-plan ([updatedStepNumbers] for a different target or pocket, [refinedStepNumbers]
 * for the same target and pocket with a different tip or speed).
 */
data class RollingView(
    val window: RunoutPlan?,
    val alternatives: List<RunoutPlan>,
    val updatedStepNumbers: Set<Int>,
    val refinedStepNumbers: Set<Int> = emptySet(),
)

/**
 * Memo of planner results for the rolling plan, keyed by table, rules, horizon and override.
 *
 * The cache is immutable: adding a result returns a new cache. It never takes part in state equality, so two
 * [RollingPlanState]s that differ only in what they remember are equal. At most [MAX_ENTRIES] results are kept;
 * the oldest are dropped first.
 */
class PlanCache private constructor(private val entries: Map<PlanKey, List<RunoutPlan>>) {

    internal operator fun get(key: PlanKey): List<RunoutPlan>? = entries[key]

    internal fun with(key: PlanKey, plans: List<RunoutPlan>): PlanCache {
        val next = LinkedHashMap(entries)
        next.remove(key)
        next[key] = plans
        while (next.size > MAX_ENTRIES) next.remove(next.keys.first())
        return PlanCache(next)
    }

    override fun equals(other: Any?): Boolean = other is PlanCache

    override fun hashCode(): Int = 0

    override fun toString(): String = "PlanCache(${entries.size} entries)"

    companion object {
        const val MAX_ENTRIES: Int = 16

        /** A cache with no results. */
        val EMPTY: PlanCache = PlanCache(emptyMap())
    }
}

internal data class PlanKey(
    val layout: TableLayout,
    val rules: GameRulesState,
    val profile: PlayerProfile,
    val horizon: Int,
    val deepAnalysis: Boolean,
    val override: StepOverride?,
    val allowNonPotting: Boolean,
)

/**
 * Session state of a rolling run-out plan.
 *
 * The user walks a plan shot by shot. Shots already accepted are frozen in [acceptedSteps]; [window] always holds
 * the next [horizon] shots from the current focus (fewer near the end of the rack), planned from the table the
 * last accepted shot leaves. Step numbers count from the base table, so accepted steps and the window form one
 * gap-free sequence ([allSteps]).
 *
 * Use [RollingRunoutPlanner] to move between states; every operation returns a new state.
 *
 * @property baseLayout Table the session started from (or was last rebased onto).
 * @property baseRules Rules state that goes with [baseLayout].
 * @property profile Player model used for every search.
 * @property horizon Number of shots shown from the focus (`1..5`; ignored when [deepAnalysis] is set).
 * @property deepAnalysis When set, the window covers the rack and advancing only moves the focus.
 * @property acceptedSteps Frozen shots from the base to the focus. Never re-planned.
 * @property focusOverride Edits on the focused step, or `null` when the planner chooses.
 * @property window Planned steps from the focus onward, or `null` when the rack is complete or no shot exists.
 * @property alternatives Plans that start with a different target or pocket at the focus.
 * @property updatedStepNumbers Numbers of already-shown steps whose target ball or pocket changed on the last
 * re-plan (a route change).
 * @property refinedStepNumbers Numbers of already-shown steps that kept their target and pocket but got a different
 * tip or speed on the last re-plan (a stroke refinement).
 * @property userEditedStepNumbers Numbers (counted from the base) of steps the user explicitly overrode. Unlike
 * [focusOverride], which [RollingRunoutPlanner.back] also fills with the reopened step's own choices, this only
 * changes through [RollingRunoutPlanner.setOverride] and [RollingRunoutPlanner.clearOverride]; accepted steps keep
 * their mark.
 * @property refusalReason Why the last [RollingRunoutPlanner.acceptAndAdvance] was refused, or `null`.
 * @property earlierViews One view per accepted step: what was shown at that focus before it was accepted.
 * @property laterViews Views of later foci, kept after stepping back so that accepting again restores them.
 * @property cache Memo of planner results.
 */
data class RollingPlanState(
    val baseLayout: TableLayout,
    val baseRules: GameRulesState,
    val profile: PlayerProfile,
    val horizon: Int,
    val deepAnalysis: Boolean = false,
    val acceptedSteps: List<PlannedShotStep> = emptyList(),
    val focusOverride: StepOverride? = null,
    val window: RunoutPlan? = null,
    val alternatives: List<RunoutPlan> = emptyList(),
    val updatedStepNumbers: Set<Int> = emptySet(),
    val refinedStepNumbers: Set<Int> = emptySet(),
    val userEditedStepNumbers: Set<Int> = emptySet(),
    val refusalReason: String? = null,
    val earlierViews: List<RollingView> = emptyList(),
    val laterViews: List<RollingView> = emptyList(),
    val cache: PlanCache = PlanCache.EMPTY,
) {
    /** True when the user explicitly overrode the focused step (not merely reopened it with [back]). */
    val isFocusEdited: Boolean
        get() = (focusIndex + 1) in userEditedStepNumbers

    /** Zero-based index of the focused step, equal to the number of accepted steps. */
    val focusIndex: Int
        get() = acceptedSteps.size

    /** Table at the focus: the one the last accepted shot leaves, or [baseLayout]. */
    val focusLayout: TableLayout
        get() = acceptedSteps.lastOrNull()?.resultingLayout ?: baseLayout

    /** Rules at the focus, with every accepted target applied (an accepted solid assigns the 8-ball group). */
    val focusRules: GameRulesState
        get() = acceptedSteps.fold(baseRules) { rules, step ->
            MultiShotPlanner.rulesAfterPotting(rules, step.targetBall)
        }

    /** True once the game ball (or the last legal ball) has been accepted. */
    val isRackComplete: Boolean
        get() {
            val last = acceptedSteps.lastOrNull() ?: return false
            return last.targetBall.number == baseRules.gameType.requiredEndBallNumber ||
                focusRules.legalTargetBalls(focusLayout).isEmpty()
        }

    /** The step the user is looking at, or `null` when there is none. */
    val focusStep: PlannedShotStep?
        get() = window?.steps?.firstOrNull()

    /** Accepted steps followed by the window steps, numbered `1..n` from the base. */
    val allSteps: List<PlannedShotStep>
        get() = acceptedSteps + window?.steps.orEmpty()

    /** True when the focus can step back to the previously accepted shot. */
    val canGoBack: Boolean
        get() = acceptedSteps.isNotEmpty()

    /** True when [RollingRunoutPlanner.acceptAndAdvance] would be accepted. */
    val canAdvance: Boolean
        get() = RollingRunoutPlanner.advanceRefusal(this) == null
}

/**
 * Rolling run-out planning: accept the focused shot and the window slides forward with a full horizon planned from
 * the table that shot leaves.
 *
 * With a 3-shot horizon on a 9-ball rack the first window is balls 1-3; accepting shot 1 plans balls 2-4, then
 * 3-5. The focused step can be overridden and everything after it is re-fitted. Stepping back reopens the previous
 * step, and moving a ball rebases the session.
 *
 * Every function is `state -> state`: no coroutines, no hidden state. One or two [MultiShotPlanner.planRunout]
 * calls run per action, so call from a background thread.
 *
 * ### Stability
 * When a shot is accepted, the steps that were already shown are kept unless the fresh plan is clearly better.
 * The kept alternative is the best plan from the new table whose first shot is the previously shown next shot
 * (same target, pocket, tip and speed). The fresh plan replaces it only if its
 * [RunoutPlan.expectedValueScore] is at least [UPDATE_THRESHOLD_FRACTION] higher.
 *
 * The steps that then differ from what was shown are reported in two sets: [RollingPlanState.updatedStepNumbers]
 * for a different target ball or pocket (a route change) and [RollingPlanState.refinedStepNumbers] for the same
 * target and pocket with a different tip or speed (a stroke refinement). The same split applies when an edit
 * re-fits the later steps.
 *
 * ### User edits
 * [RollingPlanState.userEditedStepNumbers] records which steps the user overrode, so a UI can mark them. The
 * override that [back] loads is not an edit.
 */
object RollingRunoutPlanner {
    /** Relative score gain a fresh plan needs before it replaces steps the user has already seen. */
    const val UPDATE_THRESHOLD_FRACTION: Double = 0.05

    /** Largest horizon the underlying search honours outside deep analysis. */
    const val MAX_HORIZON: Int = 5

    /**
     * Starts a session on [layout]: the window is the planner's plan of [horizon] shots from the base table.
     */
    fun start(
        layout: TableLayout,
        rules: GameRulesState = GameRulesState(),
        profile: PlayerProfile = PlayerProfile(),
        horizon: Int = 3,
        deepAnalysis: Boolean = false,
    ): RollingPlanState {
        val state = RollingPlanState(
            baseLayout = layout,
            baseRules = rules,
            profile = profile,
            horizon = horizon.coerceIn(1, MAX_HORIZON),
            deepAnalysis = deepAnalysis,
        )
        return replanFocus(state, override = null, previousSteps = emptyList(), compareFrom = 0)
    }

    /**
     * Why the focused step cannot be accepted, or `null` when it can: the rack is complete, there is no planned
     * shot, the simulation does not pot the ball, or the cue ball scratches.
     */
    fun advanceRefusal(state: RollingPlanState): String? {
        if (state.isRackComplete) return "The rack is already complete."
        val step = state.focusStep ?: return "There is no planned shot at this step."
        val ball = step.targetBall.number
        val simulation = step.uncertainty.postShotSimulation
        return when {
            simulation?.cueBallStoppedBeforeImpact == true ->
                "The cue ball stops short of ball #$ball at this speed. Change the shot before moving on."
            !step.uncertainty.targetBallPotted ->
                "This shot does not pot ball #$ball. Change the shot before moving on."
            step.uncertainty.nominalTrajectory.hitsScratchPocket != null ||
                simulation?.cueBallTrajectory?.hitsScratchPocket != null ->
                "The cue ball scratches on this shot. Change the shot before moving on."
            step.resultingLayout == null -> "This shot has no resulting table to continue from."
            else -> null
        }
    }

    /**
     * "Next": accepts the focused step and plans a full horizon from the table it leaves.
     *
     * If the step cannot be accepted (see [advanceRefusal]) the state is returned unchanged except for
     * [RollingPlanState.refusalReason]. After the game ball the window is `null` and
     * [RollingPlanState.isRackComplete] is true. In deep analysis the window already covers the rack, so only the
     * focus moves: the remaining steps are kept as they are (the plan-level probability, score and explanations
     * still describe the whole original plan) and the rack is searched again only once they run out. Stepping back and accepting again, without an edit in between, restores exactly what was shown.
     */
    fun acceptAndAdvance(state: RollingPlanState): RollingPlanState {
        val refusal = advanceRefusal(state)
        if (refusal != null) return state.copy(refusalReason = refusal)
        val window = state.window ?: return state.copy(refusalReason = "There is no planned shot at this step.")
        val accepted = window.steps.first()
        // advanceRefusal guarantees a resulting layout.
        val newLayout = accepted.resultingLayout ?: return state
        val currentView = RollingView(state.window, state.alternatives, state.updatedStepNumbers, state.refinedStepNumbers)

        val advanced = state.copy(
            acceptedSteps = state.acceptedSteps + accepted,
            focusOverride = null,
            window = null,
            alternatives = emptyList(),
            updatedStepNumbers = emptySet(),
            refinedStepNumbers = emptySet(),
            refusalReason = null,
            earlierViews = state.earlierViews + currentView,
            laterViews = state.laterViews.dropLast(1),
        )
        if (advanced.isRackComplete) return advanced

        val restored = state.laterViews.lastOrNull()
        if (restored != null) {
            return advanced.copy(
                window = restored.window,
                alternatives = restored.alternatives,
                updatedStepNumbers = restored.updatedStepNumbers,
                refinedStepNumbers = restored.refinedStepNumbers,
            )
        }

        val tail = window.steps.drop(1)
        if (state.deepAnalysis && tail.isNotEmpty()) {
            return advanced.copy(window = window.copy(steps = tail))
        }
        return slideWindow(advanced, newLayout, previousTail = tail)
    }

    /**
     * "Prev": reopens the last accepted step. The view shown before it was accepted comes back and the step's own
     * choices become the focus override, so nothing changes until the user edits. Does nothing at the first step.
     */
    fun back(state: RollingPlanState): RollingPlanState {
        val reopened = state.acceptedSteps.lastOrNull() ?: return state
        val earlier = state.earlierViews.lastOrNull()
        val stepped = state.copy(
            acceptedSteps = state.acceptedSteps.dropLast(1),
            focusOverride = StepOverride.of(reopened),
            refusalReason = null,
            earlierViews = state.earlierViews.dropLast(1),
            laterViews = state.laterViews + RollingView(
                state.window,
                state.alternatives,
                state.updatedStepNumbers,
                state.refinedStepNumbers,
            ),
        )
        if (earlier == null) {
            return replanFocus(
                stepped.copy(laterViews = emptyList()),
                override = stepped.focusOverride,
                previousSteps = emptyList(),
                compareFrom = 1,
            )
        }
        return stepped.copy(
            window = earlier.window,
            alternatives = earlier.alternatives,
            updatedStepNumbers = earlier.updatedStepNumbers,
            refinedStepNumbers = earlier.refinedStepNumbers,
        )
    }

    /**
     * Re-plans from the focus with [override] as the first shot; later steps re-fit around it. An override that
     * pots nothing is still shown (so the user can see why), but cannot be accepted. An empty override clears it.
     */
    fun setOverride(state: RollingPlanState, override: StepOverride?): RollingPlanState {
        val normalised = override?.takeUnless { it.isEmpty }
        if (state.isRackComplete) return state
        val number = state.focusIndex + 1
        if (normalised == state.focusOverride && state.window != null) {
            val unmarked = if (normalised == null) state.userEditedStepNumbers - number else state.userEditedStepNumbers
            return state.copy(refusalReason = null, userEditedStepNumbers = unmarked)
        }
        val marked = if (normalised == null) {
            state.userEditedStepNumbers - number
        } else {
            state.userEditedStepNumbers + number
        }
        return replanFocus(
            state.copy(
                laterViews = emptyList(),
                refusalReason = null,
                // Steps after the focus are discarded with the restored future, and so are their marks.
                userEditedStepNumbers = marked.filterTo(HashSet()) { it <= number },
            ),
            override = normalised,
            previousSteps = state.window?.steps.orEmpty(),
            compareFrom = 1,
        )
    }

    /** Drops the override on the focused step and returns to the planner's own choice. */
    fun clearOverride(state: RollingPlanState): RollingPlanState = setOverride(state, null)

    /**
     * The user moved a ball: every accepted step is dropped and [newLayout] becomes the base table. [rules]
     * defaults to the rules at the current focus, so an 8-ball group assigned by an accepted shot is kept.
     */
    fun rebase(
        state: RollingPlanState,
        newLayout: TableLayout,
        rules: GameRulesState = state.focusRules,
    ): RollingPlanState = replanFocus(
        state.copy(
            baseLayout = newLayout,
            baseRules = rules,
            acceptedSteps = emptyList(),
            focusOverride = null,
            window = null,
            alternatives = emptyList(),
            updatedStepNumbers = emptySet(),
            refinedStepNumbers = emptySet(),
            userEditedStepNumbers = emptySet(),
            refusalReason = null,
            earlierViews = emptyList(),
            laterViews = emptyList(),
        ),
        override = null,
        previousSteps = emptyList(),
        compareFrom = 0,
    )

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    private class Searched(val plans: List<RunoutPlan>, val cache: PlanCache)

    /** Planner results for a table, from the cache when possible. */
    private fun search(
        cache: PlanCache,
        layout: TableLayout,
        rules: GameRulesState,
        state: RollingPlanState,
        override: StepOverride?,
    ): Searched {
        var memo = cache
        fun run(allowNonPotting: Boolean): List<RunoutPlan> {
            val key = PlanKey(layout, rules, state.profile, state.horizon, state.deepAnalysis, override, allowNonPotting)
            memo[key]?.let { return it }
            val plans = MultiShotPlanner.planRunout(
                layout = layout,
                rulesState = rules,
                playerProfile = state.profile,
                horizonShots = state.horizon,
                deepAnalysis = state.deepAnalysis,
                customFirstShotTip = override?.tip,
                customFirstShotSpeedUnits = override?.speedUnits,
                customFirstShotTargetBallNumber = override?.targetBallNumber,
                customFirstShotPocketId = override?.pocketId,
                allowHighScratchFirstShot = allowNonPotting,
            )
            memo = memo.with(key, plans)
            return plans
        }
        var plans = run(allowNonPotting = false)
        // An edit that pots nothing still gets a window, so the user sees what the shot does.
        if (plans.isEmpty() && override != null) plans = run(allowNonPotting = true)
        return Searched(plans, memo)
    }

    /** Plans from the focus with [override] and replaces the window; used by start, edits, rebase and fallbacks. */
    private fun replanFocus(
        state: RollingPlanState,
        override: StepOverride?,
        previousSteps: List<PlannedShotStep>,
        compareFrom: Int,
    ): RollingPlanState {
        val searched = search(state.cache, state.focusLayout, state.focusRules, state, override)
        val offset = state.focusIndex
        val numbered = searched.plans.map { renumber(it, offset) }
        val best = numbered.firstOrNull()
        val changes = if (best == null) StepChanges.NONE else changedSteps(previousSteps, best.steps, compareFrom)
        return state.copy(
            focusOverride = override,
            window = best,
            alternatives = alternativesTo(best, numbered),
            updatedStepNumbers = changes.routeChanged,
            refinedStepNumbers = changes.strokeRefined,
            cache = searched.cache,
        )
    }

    /**
     * Plans a full horizon from [newLayout] after a step was accepted, keeping the previously shown next shot
     * unless the fresh plan is at least [UPDATE_THRESHOLD_FRACTION] better.
     */
    private fun slideWindow(
        advanced: RollingPlanState,
        newLayout: TableLayout,
        previousTail: List<PlannedShotStep>,
    ): RollingPlanState {
        val rules = advanced.focusRules
        val fresh = search(advanced.cache, newLayout, rules, advanced, override = null)
        val offset = advanced.focusIndex
        val freshPlans = fresh.plans.map { renumber(it, offset) }
        val best = freshPlans.firstOrNull()
            ?: return advanced.copy(cache = fresh.cache)

        var chosen = best
        var cache = fresh.cache
        val shown = previousTail.firstOrNull()
        if (shown != null && !sameChoice(best.steps.first(), shown)) {
            val kept = search(cache, newLayout, rules, advanced, override = StepOverride.of(shown))
            cache = kept.cache
            val keptPlan = kept.plans.firstOrNull()?.let { renumber(it, offset) }
            if (keptPlan != null &&
                best.expectedValueScore < keptPlan.expectedValueScore * (1.0 + UPDATE_THRESHOLD_FRACTION)
            ) {
                chosen = keptPlan
            }
        }
        val changes = changedSteps(previousTail, chosen.steps, 0)
        return advanced.copy(
            window = chosen,
            alternatives = alternativesTo(chosen, freshPlans),
            updatedStepNumbers = changes.routeChanged,
            refinedStepNumbers = changes.strokeRefined,
            cache = cache,
        )
    }

    /** Plans from [candidates] that open with a different target or pocket than [chosen]. */
    private fun alternativesTo(chosen: RunoutPlan?, candidates: List<RunoutPlan>): List<RunoutPlan> {
        val first = chosen?.steps?.firstOrNull() ?: return emptyList()
        return candidates.filter { plan ->
            val other = plan.steps.firstOrNull()
            other != null && (other.targetBall.number != first.targetBall.number || other.pocketId != first.pocketId)
        }
    }

    private fun sameChoice(a: PlannedShotStep, b: PlannedShotStep): Boolean =
        a.targetBall.number == b.targetBall.number &&
            a.pocketId == b.pocketId &&
            a.recommendedTip == b.recommendedTip &&
            a.recommendedSpeedUnits == b.recommendedSpeedUnits

    private class StepChanges(val routeChanged: Set<Int>, val strokeRefined: Set<Int>) {
        companion object {
            val NONE = StepChanges(emptySet(), emptySet())
        }
    }

    /**
     * Compares the steps in [after], from index [from], with the step at the same index in [before]. A different
     * target ball or pocket is a route change; the same target and pocket with a different tip or speed is a
     * stroke refinement.
     */
    private fun changedSteps(
        before: List<PlannedShotStep>,
        after: List<PlannedShotStep>,
        from: Int,
    ): StepChanges {
        val route = HashSet<Int>()
        val stroke = HashSet<Int>()
        for (index in from until minOf(before.size, after.size)) {
            val old = before[index]
            val new = after[index]
            when {
                old.targetBall.number != new.targetBall.number || old.pocketId != new.pocketId ->
                    route += new.stepNumber
                !sameChoice(old, new) -> stroke += new.stepNumber
            }
        }
        return StepChanges(route, stroke)
    }

    private val STEP_REFERENCE = Regex("""\bStep (\d+)\b""")

    /** Shifts step numbers (and "Step n" mentions in the explanations) so they count from the base table. */
    private fun renumber(plan: RunoutPlan, offset: Int): RunoutPlan {
        if (offset == 0) return plan
        return plan.copy(
            steps = plan.steps.map { it.copy(stepNumber = it.stepNumber + offset) },
            explanationReasons = plan.explanationReasons.map { reason ->
                STEP_REFERENCE.replace(reason) { match -> "Step ${match.groupValues[1].toInt() + offset}" }
            },
        )
    }
}
