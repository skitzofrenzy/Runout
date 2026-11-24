package com.runout.engine.planner

import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.PostShotTableSimulation
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
 * for the same target and pocket with a different tip or speed). [focusOverride] is the override the window's first step
 * was planned with: the user's edit, or the pinned choices of a reopened step in a view kept for stepping forward. A view
 * that comes back brings it back with it.
 */
data class RollingView(
    val window: RunoutPlan?,
    val alternatives: List<RunoutPlan>,
    val updatedStepNumbers: Set<Int>,
    val refinedStepNumbers: Set<Int> = emptySet(),
    val focusOverride: StepOverride? = null,
)

/**
 * Memo of planner results for the rolling plan, keyed by table, rules, horizon and override.
 *
 * The cache is immutable: adding a result returns a new cache. It never takes part in state equality, so two
 * [RollingPlanState]s that differ only in what they remember are equal. At most [MAX_ENTRIES] results are kept;
 * the oldest are dropped first.
 */
class PlanCache private constructor(private val entries: Map<PlanKey, List<RunoutPlan>>) {

    /** Number of remembered results. */
    val size: Int
        get() = entries.size

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
 * @property deepAnalysis When set, the window holds as many shots as the table has object balls, at most six (a
 * longer rack is not planned to its end), and advancing only moves the focus until those run out.
 * @property acceptedSteps Frozen shots from the base to the focus. Never re-planned.
 * @property focusOverride The override that produced the focused step, or `null` when the planner chose it: the
 * user's edit, or the pinned choices of a step reopened with [RollingRunoutPlanner.back] or taken with
 * [RollingRunoutPlanner.chooseAlternative]. A step marked as edited always has its override here.
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

    /**
     * True when the edit state is self-consistent: a focused step marked as edited has the override that produced it
     * in [focusOverride], so that resetting it can re-plan to the planner's own choice. Every function of
     * [RollingRunoutPlanner] preserves this.
     */
    val isEditStateConsistent: Boolean
        get() = !isFocusEdited || focusOverride != null

    /**
     * The first step the planner picks at the focus when nothing is forced, for a "default" marker: the focused step
     * itself when no override is active, otherwise the result remembered from planning without it, or `null` when that
     * is not known (call [RollingRunoutPlanner.withDefaultFocusStep] to compute it).
     */
    val defaultFocusStep: PlannedShotStep?
        get() = if (focusOverride == null) focusStep else RollingRunoutPlanner.cachedDefaultFocusStep(this)

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
 * ### Stopping a search
 * Every operation that searches takes `shouldStop`, polled by the underlying search (see
 * [MultiShotPlanner.planRunoutWithStatus]). When it returns true the operation gives up: the incoming state is
 * returned unchanged (for [start], which has none, the session with no window), and nothing the interrupted search
 * found is remembered in the [PlanCache].
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
 * override that [back] loads for a step that was not edited is not an edit. A step that was edited keeps its mark and
 * its own override across [back] and [acceptAndAdvance], so resetting it ([clearOverride]) always re-plans to the
 * planner's own choice.
 *
 * ### Other operations
 * [withHorizon] changes the number of shots shown, [chooseAlternative] takes one of the other first shots on offer
 * without calling it an edit, and [withDefaultFocusStep] fills in the planner's own first step at an edited focus.
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
        shouldStop: () -> Boolean = { false },
    ): RollingPlanState {
        val state = RollingPlanState(
            baseLayout = layout,
            baseRules = rules,
            profile = profile,
            horizon = horizon.coerceIn(1, MAX_HORIZON),
            deepAnalysis = deepAnalysis,
        )
        return replanFocus(state, override = null, previousSteps = emptyList(), compareFrom = 0, shouldStop) ?: state
    }

    /**
     * Why the focused step cannot be accepted, or `null` when it can: the rack is complete, there is no planned
     * shot, the simulation does not pot the ball, the ball drops in a pocket other than the planned one, the cue
     * ball scratches, or another ball drops that makes the shot a foul or a loss.
     *
     * A forced shot (an override the planner found no legal plan for) is shown so that the user can see what it
     * does, but it is only accepted when the nominal simulation shows what the plan says it shows.
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
            else -> forcedShotRefusal(step, state.focusRules)
                ?: if (step.resultingLayout == null) "This shot has no resulting table to continue from." else null
        }
    }

    /**
     * Why [step], which pots its target ball and does not scratch, is still not a shot the plan can continue from, or
     * `null`. Read from the nominal simulation of the step. The reasons below are specific; a step the planner flags as
     * not [PlannedShotStep.legalAsPlanned] for any other reason (any extra ball dropping, in any game) is refused with
     * a general one.
     *
     * - The target ball drops in a pocket other than the step's own: the plan (and the table it leaves) is for another
     *   pocket.
     * - Eight-ball, any ball dropping with the target besides the target itself, other than a ball of the shooter's
     *   own group: the 8-ball loses the game when it goes down with a group ball, and an opponent's ball hands the
     *   opponent a ball (a foul under some rule sets, and a plan that leaves them one is not a run-out). The shooter's
     *   group is the target's group, or, when the 8 is the target, the assigned group; when it cannot be told, any
     *   extra ball counts against the shot. Own-group balls dropping together are given no specific reason, but a
     *   step the planner flags as not legal as planned is still refused by the general one.
     * - Nine-ball and ten-ball: no specific reason; only the general one for a step the planner flags as not legal as
     *   planned.
     */
    private fun forcedShotRefusal(step: PlannedShotStep, rules: GameRulesState): String? {
        val simulation = step.uncertainty.postShotSimulation
        val target = step.targetBall
        if (simulation != null) {
            val droppedIn = simulation.targetBallTrajectory?.pocketedIn
            if (droppedIn != null && droppedIn != step.pocketId) {
                return "Ball #${target.number} drops in the ${droppedIn.displayName} pocket, not the " +
                    "${step.pocketId.displayName} pocket of the plan. Change the shot before moving on."
            }
            if (rules.gameType == GameType.EIGHT_BALL) eightBallExtraDropRefusal(simulation, target, rules)?.let { return it }
        }
        return if (step.legalAsPlanned) {
            null
        } else {
            "This shot drops another ball or does not play as planned. Change the shot before moving on."
        }
    }

    private fun eightBallExtraDropRefusal(
        simulation: PostShotTableSimulation,
        target: Ball,
        rules: GameRulesState,
    ): String? {
        val ownIsSolid: Boolean? = when {
            target.isSolid -> true
            target.isStripe -> false
            else -> when (rules.eightBallGroup) {
                EightBallGroup.SOLIDS -> true
                EightBallGroup.STRIPES -> false
                else -> null
            }
        }
        val extra = simulation.disturbedBalls.filter { it.pocketedIn != null }.map { it.ballNumber }.sorted()
        for (number in extra) {
            when {
                number == 8 ->
                    return "This shot also drops the 8-ball, which loses the game. Change the shot before moving on."
                ownIsSolid == null || (number in 1..7) != ownIsSolid ->
                    return "This shot also drops ball #$number, one of your opponent's, which hands them a ball. " +
                        "Change the shot before moving on."
            }
        }
        return null
    }

    /**
     * "Next": accepts the focused step and plans a full horizon from the table it leaves.
     *
     * If the step cannot be accepted (see [advanceRefusal]) the state is returned unchanged except for
     * [RollingPlanState.refusalReason]. After the game ball the window is `null` and
     * [RollingPlanState.isRackComplete] is true. In deep analysis the window already holds the shots after the
     * accepted one (up to six in all), so only the focus moves: the remaining steps are kept as they are, the
     * window's probability is recomputed from them ([RunoutPlan.cumulativeRunoutProbability] has the definition), its
     * score and explanations still describe the whole original plan, and the table is searched again only once the
     * steps run out. Stepping back and accepting again, without an edit in between, restores exactly what was shown.
     */
    fun acceptAndAdvance(state: RollingPlanState, shouldStop: () -> Boolean = { false }): RollingPlanState {
        val refusal = advanceRefusal(state)
        if (refusal != null) return state.copy(refusalReason = refusal)
        val window = state.window ?: return state.copy(refusalReason = "There is no planned shot at this step.")
        val accepted = window.steps.first()
        // advanceRefusal guarantees a resulting layout.
        val newLayout = accepted.resultingLayout ?: return state
        val currentView = viewOf(state, keepPins = false)

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
                focusOverride = restored.focusOverride,
                window = restored.window,
                alternatives = restored.alternatives,
                updatedStepNumbers = restored.updatedStepNumbers,
                refinedStepNumbers = restored.refinedStepNumbers,
            )
        }

        val tail = window.steps.drop(1)
        if (state.deepAnalysis && tail.isNotEmpty()) {
            return advanced.copy(
                window = window.copy(steps = tail, cumulativeRunoutProbability = cumulativeProbability(tail)),
            )
        }
        return slideWindow(advanced, newLayout, previousTail = tail, shouldStop) ?: state
    }

    /** [RunoutPlan.cumulativeRunoutProbability] of [steps]: every step's make and no scratch, every hold but the last. */
    private fun cumulativeProbability(steps: List<PlannedShotStep>): Double {
        var product = 1.0
        steps.forEachIndexed { index, step ->
            product *= step.uncertainty.makeProbability * (1.0 - step.uncertainty.scratchProbability)
            if (index < steps.lastIndex) product *= step.positionHoldProbability
        }
        return product.coerceIn(0.0, 1.0)
    }

    /**
     * "Prev": reopens the last accepted step. The view shown before it was accepted comes back. For a step that was
     * not edited its own choices become the focus override, so nothing changes until the user edits; a step that was
     * edited comes back as the edit it was (mark and override), so resetting it works as before. The view that was
     * focused is kept, with its override, so accepting again restores it. Does nothing at the first step.
     */
    fun back(state: RollingPlanState, shouldStop: () -> Boolean = { false }): RollingPlanState {
        val reopened = state.acceptedSteps.lastOrNull() ?: return state
        val earlier = state.earlierViews.lastOrNull()
        val wasEdited = state.acceptedSteps.size in state.userEditedStepNumbers
        val override = if (wasEdited) earlier?.focusOverride ?: StepOverride.of(reopened) else StepOverride.of(reopened)
        val stepped = state.copy(
            acceptedSteps = state.acceptedSteps.dropLast(1),
            focusOverride = override,
            refusalReason = null,
            earlierViews = state.earlierViews.dropLast(1),
            laterViews = state.laterViews + viewOf(state, keepPins = true),
        )
        if (earlier == null) {
            return replanFocus(
                stepped.copy(laterViews = emptyList()),
                override = stepped.focusOverride,
                previousSteps = emptyList(),
                compareFrom = 1,
                shouldStop = shouldStop,
            ) ?: state
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
     * pots nothing is still shown (so the user can see why), but cannot be accepted. An empty override clears it:
     * the focus returns to the planner's own choice and loses its edit mark, whatever override it held, including
     * a step that is marked as edited whose override is not at hand.
     */
    fun setOverride(
        state: RollingPlanState,
        override: StepOverride?,
        shouldStop: () -> Boolean = { false },
    ): RollingPlanState {
        val normalised = override?.takeUnless { it.isEmpty }
        if (state.isRackComplete) return state
        val number = state.focusIndex + 1
        val resetsEdit = normalised == null && state.isFocusEdited
        if (normalised == state.focusOverride && state.window != null && !resetsEdit) {
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
            shouldStop = shouldStop,
        ) ?: state
    }

    /** Drops the override on the focused step and returns to the planner's own choice. */
    fun clearOverride(state: RollingPlanState, shouldStop: () -> Boolean = { false }): RollingPlanState =
        setOverride(state, null, shouldStop)

    /**
     * Changes the number of shots shown ([horizon], `1..`[MAX_HORIZON]) or switches deep analysis. The accepted steps
     * stay, the window is planned again from the current focus, and the views kept for stepping forward are dropped,
     * as are the views kept for stepping back (they were planned with the old horizon; [back] plans the reopened
     * step afresh). An override on the focus, whether the user's edit or pinned choices, is kept, so a horizon change
     * does not undo an edit. Nothing changes when both settings are already in force.
     */
    fun withHorizon(
        state: RollingPlanState,
        horizon: Int,
        deepAnalysis: Boolean = state.deepAnalysis,
        shouldStop: () -> Boolean = { false },
    ): RollingPlanState {
        val coerced = horizon.coerceIn(1, MAX_HORIZON)
        if (coerced == state.horizon && deepAnalysis == state.deepAnalysis) return state
        val number = state.focusIndex + 1
        val resized = state.copy(
            horizon = coerced,
            deepAnalysis = deepAnalysis,
            earlierViews = emptyList(),
            laterViews = emptyList(),
            refusalReason = null,
            userEditedStepNumbers = state.userEditedStepNumbers.filterTo(HashSet()) { it <= number },
        )
        if (resized.isRackComplete) return resized
        return replanFocus(
            resized,
            override = state.focusOverride,
            previousSteps = state.window?.steps.orEmpty(),
            compareFrom = 0,
            shouldStop = shouldStop,
        ) ?: state
    }

    /**
     * Makes [plan], one of [RollingPlanState.alternatives], the window. The step is not marked as edited and nothing
     * is searched: the plan is already computed. Its first shot becomes the focus override (pinned) so that later
     * operations, such as a horizon change, keep it; [clearOverride] returns to the planner's own choice. The
     * previous window joins the alternatives. The state is returned unchanged when [plan] is not on offer.
     */
    fun chooseAlternative(state: RollingPlanState, plan: RunoutPlan): RollingPlanState {
        val first = plan.steps.firstOrNull() ?: return state
        if (state.isRackComplete || plan !in state.alternatives) return state
        val number = state.focusIndex + 1
        val candidates = listOfNotNull(state.window) + state.alternatives
        val changes = changedSteps(state.window?.steps.orEmpty(), plan.steps, 1)
        return state.copy(
            focusOverride = StepOverride.of(first),
            window = plan,
            alternatives = alternativesTo(plan, candidates),
            updatedStepNumbers = changes.routeChanged,
            refinedStepNumbers = changes.strokeRefined,
            // The focus is no longer the user's edit; steps after it go with the discarded future.
            userEditedStepNumbers = state.userEditedStepNumbers.filterTo(HashSet()) { it < number },
            laterViews = emptyList(),
            refusalReason = null,
        )
    }

    /**
     * Makes sure [RollingPlanState.defaultFocusStep] is known: when the focus carries an override and the planner's own
     * plan without it is not remembered, it is planned now (one search). The returned state equals [state] apart
     * from its memo.
     */
    fun withDefaultFocusStep(state: RollingPlanState, shouldStop: () -> Boolean = { false }): RollingPlanState {
        if (state.focusOverride == null || state.isRackComplete) return state
        if (cachedDefaultFocusStep(state) != null) return state
        val searched = search(state.cache, state.focusLayout, state.focusRules, state, override = null, shouldStop)
        return if (searched.stopped) state else state.copy(cache = searched.cache)
    }

    /** The planner's own first step at the focus, if its plan without an override is in the memo. */
    internal fun cachedDefaultFocusStep(state: RollingPlanState): PlannedShotStep? {
        if (state.isRackComplete) return null
        val key = PlanKey(
            state.focusLayout, state.focusRules, state.profile, state.horizon, state.deepAnalysis,
            override = null, allowNonPotting = false,
        )
        val plan = state.cache[key]?.firstOrNull() ?: return null
        return renumber(plan, state.focusIndex).steps.firstOrNull()
    }

    /**
     * The user moved a ball: every accepted step is dropped and [newLayout] becomes the base table. [rules]
     * defaults to the rules at the current focus, so an 8-ball group assigned by an accepted shot is kept.
     */
    fun rebase(
        state: RollingPlanState,
        newLayout: TableLayout,
        rules: GameRulesState = state.focusRules,
        shouldStop: () -> Boolean = { false },
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
        shouldStop = shouldStop,
    ) ?: state

    // ---------------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------------

    /** The plans of one [search]; when [stopped] they are partial and [cache] is the cache without them. */
    private class Searched(val plans: List<RunoutPlan>, val cache: PlanCache, val stopped: Boolean = false)

    /**
     * What [state] shows at its focus. [keepPins] also keeps the pinned choices of a step that was reopened or
     * taken as an alternative; without it only the user's own edit is recorded, so that the views of accepted steps
     * do not change when a step is reopened and accepted again.
     */
    private fun viewOf(state: RollingPlanState, keepPins: Boolean): RollingView = RollingView(
        window = state.window,
        alternatives = state.alternatives,
        updatedStepNumbers = state.updatedStepNumbers,
        refinedStepNumbers = state.refinedStepNumbers,
        focusOverride = state.focusOverride.takeIf { keepPins || state.isFocusEdited },
    )

    /** Planner results for a table, from the cache when possible. */
    private fun search(
        cache: PlanCache,
        layout: TableLayout,
        rules: GameRulesState,
        state: RollingPlanState,
        override: StepOverride?,
        shouldStop: () -> Boolean,
    ): Searched {
        var memo = cache
        var stopped = false
        fun run(allowNonPotting: Boolean): List<RunoutPlan> {
            val key = PlanKey(layout, rules, state.profile, state.horizon, state.deepAnalysis, override, allowNonPotting)
            memo[key]?.let { return it }
            val result = MultiShotPlanner.planRunoutWithStatus(
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
                shouldStop = shouldStop,
            )
            // A partial result is never remembered: the next call with the same key must search properly.
            if (result.stopped) stopped = true else memo = memo.with(key, result.plans)
            return result.plans
        }
        var plans = run(allowNonPotting = false)
        // An edit that pots nothing still gets a window, so the user sees what the shot does.
        if (!stopped && plans.isEmpty() && override != null) plans = run(allowNonPotting = true)
        return Searched(plans, memo, stopped)
    }

    /**
     * Plans from the focus with [override] and replaces the window; used by start, edits, rebase and fallbacks.
     * Returns `null` when the search was stopped.
     */
    private fun replanFocus(
        state: RollingPlanState,
        override: StepOverride?,
        previousSteps: List<PlannedShotStep>,
        compareFrom: Int,
        shouldStop: () -> Boolean,
    ): RollingPlanState? {
        val searched = search(state.cache, state.focusLayout, state.focusRules, state, override, shouldStop)
        if (searched.stopped) return null
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
        shouldStop: () -> Boolean,
    ): RollingPlanState? {
        val rules = advanced.focusRules
        val fresh = search(advanced.cache, newLayout, rules, advanced, override = null, shouldStop)
        if (fresh.stopped) return null
        val offset = advanced.focusIndex
        val freshPlans = fresh.plans.map { renumber(it, offset) }
        val best = freshPlans.firstOrNull()
            ?: return advanced.copy(cache = fresh.cache)

        var chosen = best
        var cache = fresh.cache
        val shown = previousTail.firstOrNull()
        if (shown != null && !sameChoice(best.steps.first(), shown)) {
            val kept = search(cache, newLayout, rules, advanced, override = StepOverride.of(shown), shouldStop)
            if (kept.stopped) return null
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
