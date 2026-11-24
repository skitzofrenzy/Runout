package com.runout.app.planning

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.TipOffset
import com.runout.engine.planner.FallbackAdvice
import com.runout.engine.planner.FallbackShotAdvisor
import com.runout.engine.planner.OneShotPlanner
import com.runout.engine.planner.PlannedShotStep
import com.runout.engine.planner.RollingPlanState
import com.runout.engine.planner.RollingRunoutPlanner
import com.runout.engine.planner.RunoutPlan
import com.runout.engine.planner.ShotRecommendation
import com.runout.engine.planner.StepOverride
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One request to the rolling planner. Every command maps to exactly one [RollingRunoutPlanner] call.
 */
internal sealed interface PlanCommand {
    /** Plan again from [layout] and [rules]: a new table, game, horizon, player or a moved ball. */
    data class Restart(
        val layout: TableLayout,
        val rules: GameRulesState,
        val profile: PlayerProfile,
        val horizon: Int,
        val deepAnalysis: Boolean,
    ) : PlanCommand

    /** Override the focused step (`null` returns it to the planner's own choice). */
    data class Edit(val override: StepOverride?) : PlanCommand

    /** Accept the focused step and move on. */
    data object Advance : PlanCommand

    /** Reopen the previously accepted step. */
    data object Back : PlanCommand
}

/**
 * Runs [command] against [state]. A [PlanCommand.Restart] rebases the running session when only the table or the
 * rules changed (keeping its memo of planner results), and starts a fresh session when the player, horizon or
 * analysis depth changed or no session exists. Blocking: call from a background dispatcher.
 */
internal fun applyPlanCommand(state: RollingPlanState?, command: PlanCommand): RollingPlanState? = when (command) {
    is PlanCommand.Restart -> {
        val horizon = command.horizon.coerceIn(1, RollingRunoutPlanner.MAX_HORIZON)
        if (state != null &&
            state.profile == command.profile &&
            state.horizon == horizon &&
            state.deepAnalysis == command.deepAnalysis
        ) {
            RollingRunoutPlanner.rebase(state, command.layout, command.rules)
        } else {
            RollingRunoutPlanner.start(
                layout = command.layout,
                rules = command.rules,
                profile = command.profile,
                horizon = command.horizon,
                deepAnalysis = command.deepAnalysis,
            )
        }
    }
    is PlanCommand.Edit -> state?.let { RollingRunoutPlanner.setOverride(it, command.override) }
    PlanCommand.Advance -> state?.let(RollingRunoutPlanner::acceptAndAdvance)
    PlanCommand.Back -> state?.let(RollingRunoutPlanner::back)
}

/**
 * Drops commands that a later one makes pointless, keeping the order of the rest: everything before the last
 * [PlanCommand.Restart] (a restart replaces the whole session) and all but the last of consecutive
 * [PlanCommand.Edit]s (each edit carries the complete override). Advance and Back are never dropped.
 */
/**
 * The fallback advice for [state]: a combination, kick or safety from [FallbackShotAdvisor] when the planner has no shot
 * at the focus, the rack is not complete and the user has not edited the focused step; `null` otherwise. A separate
 * read, not a planner command. Blocking: call from a background dispatcher.
 */
internal fun fallbackAdviceFor(state: RollingPlanState?): FallbackAdvice? {
    if (state == null || state.window != null || state.isRackComplete || state.isFocusEdited) return null
    return FallbackShotAdvisor.advise(state.focusLayout, state.focusRules, state.profile)
}

internal fun coalescePlanCommands(commands: List<PlanCommand>): List<PlanCommand> {
    val lastRestart = commands.indexOfLast { it is PlanCommand.Restart }
    val live = if (lastRestart > 0) commands.subList(lastRestart, commands.size) else commands
    val out = ArrayList<PlanCommand>(live.size)
    for (command in live) {
        if (command is PlanCommand.Edit && out.lastOrNull() is PlanCommand.Edit) {
            out[out.lastIndex] = command
        } else {
            out += command
        }
    }
    return out
}

/** An edit that has been requested but is not yet reflected in [RollingPlanSession.state]. */
internal data class PendingEdit(val override: StepOverride?)

/** What the planner itself picked at one focus, before any edit; used to show the default and to detect no-op edits. */
internal data class PlannerChoice(
    val focusIndex: Int,
    val targetBallNumber: Int,
    val pocketId: PocketId,
    val tip: TipOffset,
    val speedUnits: Double,
) {
    companion object {
        fun of(focusIndex: Int, step: PlannedShotStep): PlannerChoice = PlannerChoice(
            focusIndex = focusIndex,
            targetBallNumber = step.targetBall.number,
            pocketId = step.pocketId,
            tip = step.recommendedTip,
            speedUnits = step.recommendedSpeedUnits,
        )
    }
}

/**
 * Holds the rolling plan for the Plan and fullscreen screens.
 *
 * Handlers call [submit]; a single consumer ([run]) applies the commands in order on [computeDispatcher], so no two
 * planner calls overlap and an accepted step is never accepted twice. Edits and restarts wait a short moment and
 * are merged (see [coalescePlanCommands]) so dragging a slider or a ball plans once, not on every frame. While any
 * command is outstanding [isBusy] is true and the screens keep Next and Prev disabled.
 *
 * All members must be used from the main thread.
 */
internal class RollingPlanSession(
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private class Queued(val generation: Int, val command: PlanCommand)

    private val queue = Channel<Queued>(Channel.UNLIMITED)
    private var generation = 0
    private var outstanding by mutableIntStateOf(0)

    /** The last planned state, or `null` before the first plan and while planning is not possible. */
    var state: RollingPlanState? by mutableStateOf(null)
        private set

    /** Direct pots from the focused table, for the single-shot views. */
    var rankedShots: List<ShotRecommendation> by mutableStateOf(emptyList())
        private set

    /** Advice when the focused table has no direct pot (see [fallbackAdviceFor]), or `null`. */
    var fallbackAdvice: FallbackAdvice? by mutableStateOf(null)
        private set

    private var pendingEdit: PendingEdit? by mutableStateOf(null)

    private var plannerChoice: PlannerChoice? by mutableStateOf(null)

    /** True from [submit] until the planner has caught up with every command. */
    val isBusy: Boolean
        get() = outstanding > 0

    /** The planner's own pick at the current focus, or `null` when it is unknown (for example after Prev). */
    val defaultChoice: PlannerChoice?
        get() {
            val current = state ?: return null
            return plannerChoice?.takeIf { it.focusIndex == current.focusIndex }
        }

    /**
     * The override the user has asked for on the focused step, including one still being planned, or `null` when the
     * step is as the planner chose it. An override that only holds the choices a reopened step carried is not an
     * edit.
     */
    val editedOverride: StepOverride?
        get() {
            val pending = pendingEdit
            if (pending != null) return pending.override
            val current = state ?: return null
            return if (current.isFocusEdited) current.focusOverride else null
        }

    /** Queues [command]. */
    fun submit(command: PlanCommand) {
        when (command) {
            is PlanCommand.Restart -> pendingEdit = null
            is PlanCommand.Edit -> pendingEdit = PendingEdit(command.override)
            PlanCommand.Advance, PlanCommand.Back -> Unit
        }
        outstanding += 1
        queue.trySend(Queued(generation, command))
    }

    /**
     * Changes the focused step: [transform] receives what the step holds so far (the user's last request, the choices
     * of a step reopened with Prev, or else the planner's target and pocket, so that a tip or speed test keeps the
     * shot) and returns the new complete override.
     */
    fun edit(transform: (StepOverride) -> StepOverride) {
        val current = state ?: return
        val pinned = {
            val choice = defaultChoice
            val step = current.focusStep
            StepOverride(
                targetBallNumber = choice?.targetBallNumber ?: step?.targetBall?.number,
                pocketId = choice?.pocketId ?: step?.pocketId,
            )
        }
        val pending = pendingEdit
        val base = if (pending != null) pending.override ?: pinned() else current.focusOverride ?: pinned()
        submit(PlanCommand.Edit(canonical(transform(base))))
    }

    /** Returns the focused step to the planner's own choice. */
    fun resetFocusedStep() = submit(PlanCommand.Edit(null))

    /** Takes [plan]'s first shot (an alternative route) as the choice for the focused step. */
    fun chooseAlternative(plan: RunoutPlan) {
        val first = plan.steps.firstOrNull() ?: return
        submit(
            PlanCommand.Edit(
                StepOverride(targetBallNumber = first.targetBall.number, pocketId = first.pocketId),
            ),
        )
    }

    /** Forgets the plan and everything queued; a result still being computed is discarded. */
    fun clear() {
        generation += 1
        while (queue.tryReceive().isSuccess) {
            // Drained: these commands belong to the discarded plan.
        }
        outstanding = 0
        state = null
        rankedShots = emptyList()
        fallbackAdvice = null
        pendingEdit = null
        plannerChoice = null
    }

    /** A target-only pin, or a pin equal to the planner's pick, is not an edit. */
    private fun canonical(override: StepOverride): StepOverride? {
        if (override.tip != null || override.speedUnits != null) return override
        val pocket = override.pocketId ?: return null
        val choice = defaultChoice ?: return override
        val sameTarget = override.targetBallNumber == null || override.targetBallNumber == choice.targetBallNumber
        return if (pocket == choice.pocketId && sameTarget) null else override
    }

    private class Landed(
        val state: RollingPlanState?,
        val rankedShots: List<ShotRecommendation>,
        val choice: PlannerChoice?,
        val advice: FallbackAdvice?,
    )

    /** The consumer loop; run it from one `LaunchedEffect` for as long as the session lives. */
    suspend fun run() {
        for (first in queue) {
            val debounce = when (first.command) {
                is PlanCommand.Restart -> RESTART_DEBOUNCE_MILLIS
                is PlanCommand.Edit -> EDIT_DEBOUNCE_MILLIS
                PlanCommand.Advance, PlanCommand.Back -> 0L
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
            val startChoice = plannerChoice
            val landed = withContext(computeDispatcher) {
                var current = startState
                var choice = startChoice
                for (command in commands) {
                    current = applyPlanCommand(current, command)
                    choice = nextChoice(choice, current, command)
                }
                Landed(current, current?.let { rank(it) }.orEmpty(), choice, fallbackAdviceFor(current))
            }
            if (batchGeneration != generation) continue

            state = landed.state
            rankedShots = landed.rankedShots
            fallbackAdvice = landed.advice
            plannerChoice = landed.choice
            outstanding = (outstanding - live.size).coerceAtLeast(0)
            if (outstanding == 0) pendingEdit = null
        }
    }

    private fun nextChoice(
        previous: PlannerChoice?,
        current: RollingPlanState?,
        command: PlanCommand,
    ): PlannerChoice? = when {
        current == null -> null
        current.focusOverride == null -> current.focusStep?.let { PlannerChoice.of(current.focusIndex, it) }
        command is PlanCommand.Edit && previous?.focusIndex == current.focusIndex -> previous
        else -> null
    }

    private fun rank(current: RollingPlanState): List<ShotRecommendation> = OneShotPlanner.rankShots(
        layout = current.focusLayout,
        rulesState = current.focusRules,
        playerProfile = current.profile,
        cueSpeedUnitsOverride = if (current.isFocusEdited) current.focusOverride?.speedUnits else null,
    )

    private companion object {
        const val RESTART_DEBOUNCE_MILLIS = 150L
        const val EDIT_DEBOUNCE_MILLIS = 60L
    }
}
