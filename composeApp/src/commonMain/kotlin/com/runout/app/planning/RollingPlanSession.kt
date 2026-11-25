package com.runout.app.planning

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.planner.FallbackAdvice
import com.runout.engine.planner.FallbackShotAdvisor
import com.runout.engine.planner.OneShotPlanner
import com.runout.engine.planner.RollingPlanState
import com.runout.engine.planner.RollingRunoutPlanner
import com.runout.engine.planner.RunoutPlan
import com.runout.engine.planner.ShotRecommendation
import com.runout.engine.planner.StepOverride
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One request to the rolling planner. Every command maps to one [RollingRunoutPlanner] call.
 */
internal sealed interface PlanCommand {
    /** Plan again from [layout] and [rules]: a new table, game, player or a moved ball. */
    data class Restart(
        val layout: TableLayout,
        val rules: GameRulesState,
        val profile: PlayerProfile,
        val horizon: Int,
        val deepAnalysis: Boolean,
    ) : PlanCommand

    /** Show [horizon] shots, or the deep analysis, from the current focus; accepted shots are kept. */
    data class Horizon(val horizon: Int, val deepAnalysis: Boolean) : PlanCommand

    /** Override the focused step (`null` returns it to the planner's own choice). */
    data class Edit(val override: StepOverride?) : PlanCommand

    /** Take the first shot of [plan], one of the other routes on offer, as the focused step. Not an edit. */
    data class ChooseAlternative(val plan: RunoutPlan) : PlanCommand

    /** Accept the focused step and move on. */
    data object Advance : PlanCommand

    /** Reopen the previously accepted step. */
    data object Back : PlanCommand
}

/** Runs one [PlanCommand]; see [applyPlanCommand]. */
internal typealias PlanCommandRunner = (RollingPlanState?, PlanCommand, () -> Boolean) -> RollingPlanState?

/**
 * Runs [command] against [state]. A [PlanCommand.Restart] rebases the running session onto the new table and rules
 * (keeping its memo of planner results) when the player is the same, and starts a fresh session otherwise or when
 * none exists; the horizon of the command is used either way. [shouldStop] is polled by the planner: when it returns
 * true the search is abandoned and the state comes back unchanged (see [RollingRunoutPlanner]). Blocking: call from a
 * background dispatcher.
 */
internal fun applyPlanCommand(
    state: RollingPlanState?,
    command: PlanCommand,
    shouldStop: () -> Boolean = { false },
): RollingPlanState? = when (command) {
    is PlanCommand.Restart -> {
        val horizon = command.horizon.coerceIn(1, RollingRunoutPlanner.MAX_HORIZON)
        if (state != null && state.profile == command.profile) {
            val resized = if (state.horizon != horizon || state.deepAnalysis != command.deepAnalysis) {
                state.copy(horizon = horizon, deepAnalysis = command.deepAnalysis)
            } else {
                state
            }
            val rebased = RollingRunoutPlanner.rebase(resized, command.layout, command.rules, shouldStop)
            // A stopped search gives back what it was given: hand on the state the caller passed in.
            if (rebased === resized) state else rebased
        } else {
            RollingRunoutPlanner.start(
                layout = command.layout,
                rules = command.rules,
                profile = command.profile,
                horizon = horizon,
                deepAnalysis = command.deepAnalysis,
                shouldStop = shouldStop,
            )
        }
    }
    is PlanCommand.Horizon -> state?.let {
        RollingRunoutPlanner.withHorizon(it, command.horizon, command.deepAnalysis, shouldStop)
    }
    is PlanCommand.Edit -> state?.let { RollingRunoutPlanner.setOverride(it, command.override, shouldStop) }
    is PlanCommand.ChooseAlternative -> state?.let { RollingRunoutPlanner.chooseAlternative(it, command.plan) }
    PlanCommand.Advance -> state?.let { RollingRunoutPlanner.acceptAndAdvance(it, shouldStop) }
    PlanCommand.Back -> state?.let { RollingRunoutPlanner.back(it, shouldStop) }
}

/**
 * The fallback advice for [state]: a combination, kick or safety from [FallbackShotAdvisor] when the planner has no
 * shot at the focus, or only one whose make chance is below [FallbackShotAdvisor.DIRECT_POT_FLOOR]; `null` when the
 * focused shot is a reasonable pot, the rack is complete or the user has edited the focused step. The advisor makes the
 * final call. A separate read, not a planner command. Blocking: call from a background dispatcher.
 */
internal fun fallbackAdviceFor(state: RollingPlanState?): FallbackAdvice? {
    if (state == null || state.isRackComplete || state.isFocusEdited) return null
    val makeChance = state.focusStep?.uncertainty?.makeProbability
    if (makeChance != null && makeChance >= FallbackShotAdvisor.DIRECT_POT_FLOOR) return null
    return FallbackShotAdvisor.advise(state.focusLayout, state.focusRules, state.profile)
}

/**
 * True when the focused step is the user's edit and the controls that undo it ("Reset step", "Reset Defaults") should
 * be on offer; the planner's own answer, so every screen agrees.
 */
internal val RollingPlanState.showsFocusEdit: Boolean
    get() = isFocusEdited && !isRackComplete

/**
 * Drops commands that a later one makes pointless, keeping the order of the rest: everything before the last
 * [PlanCommand.Restart] (a restart replaces the whole session) and all but the last of consecutive
 * [PlanCommand.Edit]s or consecutive [PlanCommand.Horizon]s (each carries the complete setting). Advance, Back and
 * ChooseAlternative are never dropped.
 */
internal fun coalescePlanCommands(commands: List<PlanCommand>): List<PlanCommand> {
    val lastRestart = commands.indexOfLast { it is PlanCommand.Restart }
    val live = if (lastRestart > 0) commands.subList(lastRestart, commands.size) else commands
    val out = ArrayList<PlanCommand>(live.size)
    for (command in live) {
        val previous = out.lastOrNull()
        val replacesPrevious = (command is PlanCommand.Edit && previous is PlanCommand.Edit) ||
            (command is PlanCommand.Horizon && previous is PlanCommand.Horizon)
        if (replacesPrevious) {
            out[out.lastIndex] = command
        } else {
            out += command
        }
    }
    return out
}

/** An edit that has been requested but is not yet reflected in [RollingPlanSession.state]. */
internal data class PendingEdit(val override: StepOverride?)

/**
 * Holds the rolling plan for the Plan and fullscreen screens.
 *
 * Handlers call [submit]; a single consumer ([run]) applies the commands in order on [computeDispatcher], so no two
 * planner calls overlap and an accepted step is never accepted twice. Edits and restarts wait a short moment and
 * are merged (see [coalescePlanCommands]) so dragging a slider or a ball plans once, not on every frame. While any
 * command is outstanding [isBusy] is true and the screens keep Next and Prev disabled.
 *
 * A search never outlives the request that started it: [clear], a newer restart and, for a batch of edits (or of
 * horizon changes) alone, a newer edit (or horizon change) trip the stop hook of the search in progress, which gives up
 * at its next check; its result is thrown away. A failure in the planner is caught: the previous plan stays on screen,
 * [hasFailed] is set until the next plan succeeds, and the session keeps working.
 *
 * All members must be used from the main thread.
 */
internal class RollingPlanSession(
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val runCommand: PlanCommandRunner = ::applyPlanCommand,
) {
    private class Queued(val generation: Int, val command: PlanCommand)

    private val queue = Channel<Queued>(Channel.UNLIMITED)

    // Read by the stop hook on the compute thread, written on the main thread only.
    @Volatile
    private var generation = 0

    @Volatile
    private var restartEpoch = 0

    @Volatile
    private var editEpoch = 0

    @Volatile
    private var horizonEpoch = 0

    private var outstanding by mutableIntStateOf(0)

    /** The last planned state, or `null` before the first plan and while planning is not possible. */
    var state: RollingPlanState? by mutableStateOf(null)
        private set

    /** Direct pots from the focused table, for the single-shot views. */
    var rankedShots: List<ShotRecommendation> by mutableStateOf(emptyList())
        private set

    /** Advice beside or instead of the planned shot (see [fallbackAdviceFor]), or `null`. */
    var fallbackAdvice: FallbackAdvice? by mutableStateOf(null)
        private set

    /** True after the planner failed on the last request and until a request succeeds; the plan on screen is then the one before. */
    var hasFailed: Boolean by mutableStateOf(false)
        private set

    private var pendingEdit: PendingEdit? by mutableStateOf(null)

    /** True from [submit] until the planner has caught up with every command. */
    val isBusy: Boolean
        get() = outstanding > 0

    /**
     * The override the user has asked for on the focused step, including one still being planned, or `null` when the
     * step is as the planner chose it. An override that only holds the choices a reopened step carried is not an
     * edit; in a planned state this is non-null exactly when [showsFocusEdit] is.
     */
    val editedOverride: StepOverride?
        get() {
            val pending = pendingEdit
            if (pending != null) return pending.override
            val current = state ?: return null
            return if (current.showsFocusEdit) current.focusOverride else null
        }

    /** Queues [command]. */
    fun submit(command: PlanCommand) {
        when (command) {
            is PlanCommand.Restart -> {
                restartEpoch += 1
                pendingEdit = null
            }
            is PlanCommand.Horizon -> horizonEpoch += 1
            is PlanCommand.Edit -> {
                editEpoch += 1
                pendingEdit = PendingEdit(command.override)
            }
            is PlanCommand.ChooseAlternative -> pendingEdit = null
            PlanCommand.Advance, PlanCommand.Back -> Unit
        }
        outstanding += 1
        queue.trySend(Queued(generation, command))
    }

    /**
     * Changes the focused step: [transform] receives what the step holds so far (the user's last request, the choices
     * of a step reopened with Prev, or else the planner's own target and pocket, so that a tip or speed test keeps the
     * shot) and returns the new complete override. A target-and-pocket choice that is the planner's own pick is no
     * edit and resets the step.
     */
    fun edit(transform: (StepOverride) -> StepOverride) {
        val current = state ?: return
        val pick = current.defaultFocusStep ?: current.focusStep
        val pinned = StepOverride(targetBallNumber = pick?.targetBall?.number, pocketId = pick?.pocketId)
        val pending = pendingEdit
        val base = if (pending != null) pending.override ?: pinned else current.focusOverride ?: pinned
        submit(PlanCommand.Edit(asEdit(transform(base), current)))
    }

    /** Returns the focused step to the planner's own choice. */
    fun resetFocusedStep() = submit(PlanCommand.Edit(null))

    /** Takes [plan]'s first shot (an alternative route) as the focused step; it is not marked as an edit. */
    fun chooseAlternative(plan: RunoutPlan) = submit(PlanCommand.ChooseAlternative(plan))

    /** Shows [horizon] shots (or the deep analysis) from the focus, keeping the accepted shots. */
    fun setHorizon(horizon: Int, deepAnalysis: Boolean) = submit(PlanCommand.Horizon(horizon, deepAnalysis))

    /** Forgets the plan and everything queued; a search in progress is stopped and its result discarded. */
    fun clear() {
        generation += 1
        while (queue.tryReceive().isSuccess) {
            // Drained: these commands belong to the discarded plan.
        }
        outstanding = 0
        state = null
        rankedShots = emptyList()
        fallbackAdvice = null
        hasFailed = false
        pendingEdit = null
    }

    /** A target-and-pocket pin equal to the planner's own pick is not an edit. */
    private fun asEdit(override: StepOverride, current: RollingPlanState): StepOverride? {
        if (override.tip != null || override.speedUnits != null) return override
        val pocket = override.pocketId ?: return null
        val pick = current.defaultFocusStep ?: return override
        val sameTarget = override.targetBallNumber == null || override.targetBallNumber == pick.targetBall.number
        return if (pocket == pick.pocketId && sameTarget) null else override
    }

    private class Landed(
        val state: RollingPlanState?,
        val rankedShots: List<ShotRecommendation>,
        val advice: FallbackAdvice?,
    )

    /** The consumer loop; run it from one `LaunchedEffect` for as long as the session lives. */
    suspend fun run() {
        for (first in queue) {
            val debounce = when (first.command) {
                is PlanCommand.Restart -> RESTART_DEBOUNCE_MILLIS
                is PlanCommand.Edit -> EDIT_DEBOUNCE_MILLIS
                is PlanCommand.Horizon, is PlanCommand.ChooseAlternative, PlanCommand.Advance, PlanCommand.Back -> 0L
            }
            if (debounce > 0L) delay(debounce)
            val batch = arrayListOf(first)
            while (true) {
                batch += queue.tryReceive().getOrNull() ?: break
            }
            val batchGeneration = generation
            val live = batch.filter { it.generation == batchGeneration }.map { it.command }
            if (live.isEmpty()) continue

            val commands = coalescePlanCommands(live)
            val startState = state
            // What a newer request makes this batch pointless: a restart always; an edit (or a horizon change) only
            // when the batch holds nothing but edits (or horizon changes), since each carries its complete setting.
            val restartAtStart = restartEpoch
            val editAtStart = editEpoch
            val horizonAtStart = horizonEpoch
            val onlyEdits = commands.all { it is PlanCommand.Edit }
            val onlyHorizons = commands.all { it is PlanCommand.Horizon }
            var interrupted = false
            val shouldStop: () -> Boolean = {
                val stop = generation != batchGeneration ||
                    restartEpoch != restartAtStart ||
                    (onlyEdits && editEpoch != editAtStart) ||
                    (onlyHorizons && horizonEpoch != horizonAtStart)
                if (stop) interrupted = true
                stop
            }
            try {
                val landed = withContext(computeDispatcher) {
                    var current = startState
                    for (command in commands) {
                        current = runCommand(current, command, shouldStop)
                        if (interrupted) break
                    }
                    if (!interrupted && current != null) {
                        current = RollingRunoutPlanner.withDefaultFocusStep(current, shouldStop)
                    }
                    if (interrupted) {
                        Landed(null, emptyList(), null)
                    } else {
                        Landed(current, current?.let { rank(it) }.orEmpty(), fallbackAdviceFor(current))
                    }
                }
                val obsolete = interrupted || batchGeneration != generation || restartEpoch != restartAtStart
                if (!obsolete) {
                    state = landed.state
                    rankedShots = landed.rankedShots
                    fallbackAdvice = landed.advice
                    hasFailed = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Keep the plan that is on screen; the request that failed is dropped, with any edit waiting on it.
                if (batchGeneration == generation) {
                    hasFailed = true
                    pendingEdit = null
                }
            } finally {
                if (batchGeneration == generation) {
                    outstanding = (outstanding - live.size).coerceAtLeast(0)
                    if (outstanding == 0) pendingEdit = null
                }
            }
        }
    }

    private fun rank(current: RollingPlanState): List<ShotRecommendation> = OneShotPlanner.rankShots(
        layout = current.focusLayout,
        rulesState = current.focusRules,
        playerProfile = current.profile,
        cueSpeedUnitsOverride = if (current.isFocusEdited) current.focusOverride?.speedUnits else null,
    )

    companion object {
        /** What the Plan screen says when the planner failed. */
        const val PLAN_FAILED_MESSAGE: String = "Couldn't plan this table. Move a ball or reset the table."

        private const val RESTART_DEBOUNCE_MILLIS = 150L
        private const val EDIT_DEBOUNCE_MILLIS = 60L
    }
}
