package com.runout.engine.planner

import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.TableLayout

/**
 * Specific reasons why run-out planning must pause and ask the user for confirmation.
 */
enum class PlanningPauseReason {
    /** The white cue ball (#0) is missing from the table layout. */
    MISSING_CUE_BALL,

    /** The required game-winning end ball (#8, #9, or #10) is missing from the table layout. */
    MISSING_END_BALL,

    /** An 8-ball layout has been detected or selected, but the player has not chosen a group yet. */
    EIGHT_BALL_GROUP_UNSET,
}

/**
 * Result of checking whether a [TableLayout] and [GameRulesState] have everything required
 * to safely generate shot and run-out plans.
 */
data class PlanningReadinessResult(
    val isReady: Boolean,
    val pauseReasons: List<PlanningPauseReason>,
    val expectedEndBallNumber: Int,
    val missingBallNumbers: List<Int>,
    val needsEightBallGroupSelection: Boolean,
    val headline: String,
    val guidance: String,
)

/**
 * Pure-KMP readiness guardrail that pauses shot planning whenever:
 * 1. The cue ball (#0) is missing.
 * 2. The game's required end ball (#8 for 8-Ball, #9 for 9-Ball, #10 for 10-Ball) is missing.
 * 3. The game is 8-Ball and the user has not explicitly selected Solids/Stripes/Open
 *    (or Yellows/Reds/Open for UK Blackball).
 */
object PlanningReadinessValidator {

    fun evaluate(
        layout: TableLayout,
        rulesState: GameRulesState,
        ballSet: BallSetProfile = BallSetProfile.standardPool(),
    ): PlanningReadinessResult {
        val reasons = mutableListOf<PlanningPauseReason>()
        val missingBalls = mutableListOf<Int>()

        val hasCueBall = layout.cueBall() != null
        if (!hasCueBall) {
            reasons += PlanningPauseReason.MISSING_CUE_BALL
            missingBalls += 0
        }

        val expectedEndBall = rulesState.gameType.requiredEndBallNumber
        val hasEndBall = layout.ballByNumber(expectedEndBall) != null
        if (!hasEndBall) {
            reasons += PlanningPauseReason.MISSING_END_BALL
            missingBalls += expectedEndBall
        }

        val needsGroupChoice = rulesState.gameType == GameType.EIGHT_BALL &&
            rulesState.eightBallGroup == EightBallGroup.UNSET
        if (needsGroupChoice) {
            reasons += PlanningPauseReason.EIGHT_BALL_GROUP_UNSET
        }

        if (reasons.isEmpty()) {
            return PlanningReadinessResult(
                isReady = true,
                pauseReasons = emptyList(),
                expectedEndBallNumber = expectedEndBall,
                missingBallNumbers = emptyList(),
                needsEightBallGroupSelection = false,
                headline = "Ready to plan",
                guidance = "",
            )
        }

        val groupOptionsLabel = if (ballSet.isUkBlackball) {
            "Yellows, Reds, or Open Table"
        } else {
            "Solids, Stripes, or Open Table"
        }
        val onlyEightRemains = layout.objectBalls().isNotEmpty() &&
            layout.objectBalls().all { it.isEightBall }

        val headline = when {
            PlanningPauseReason.MISSING_CUE_BALL in reasons && PlanningPauseReason.MISSING_END_BALL in reasons ->
                "Missing cue ball and ${rulesState.gameType.endBallLabel} (#$expectedEndBall)"

            PlanningPauseReason.MISSING_END_BALL in reasons ->
                "Missing ${rulesState.gameType.endBallLabel} (#$expectedEndBall)"

            PlanningPauseReason.MISSING_CUE_BALL in reasons ->
                "Missing cue ball (#0)"

            onlyEightRemains ->
                "Confirm shooting the 8-ball"

            else ->
                if (ballSet.isUkBlackball) "Choose Yellows, Reds, or Open Table" else "Choose Solids, Stripes, or Open Table"
        }

        val guidanceParts = mutableListOf<String>()
        if (PlanningPauseReason.MISSING_END_BALL in reasons) {
            guidanceParts += "Planning is paused because the ${rulesState.gameType.endBallLabel} (#$expectedEndBall) " +
                "is missing for ${rulesState.gameType.displayName}. Please confirm or add ball #$expectedEndBall, " +
                "or switch the game type."
        }
        if (PlanningPauseReason.MISSING_CUE_BALL in reasons) {
            guidanceParts += "The white cue ball (#0) wasn't found on the table — please add or confirm its position."
        }
        if (needsGroupChoice) {
            guidanceParts += if (onlyEightRemains) {
                "Only the 8-ball is left on the table — confirm that you're shooting the 8-ball to resume planning."
            } else {
                "Select your 8-ball group ($groupOptionsLabel) before planning."
            }
        }

        return PlanningReadinessResult(
            isReady = false,
            pauseReasons = reasons,
            expectedEndBallNumber = expectedEndBall,
            missingBallNumbers = missingBalls,
            needsEightBallGroupSelection = needsGroupChoice,
            headline = headline,
            guidance = guidanceParts.joinToString(" "),
        )
    }
}
