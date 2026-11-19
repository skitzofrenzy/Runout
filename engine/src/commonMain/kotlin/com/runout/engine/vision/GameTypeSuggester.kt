package com.runout.engine.vision

import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameType
import com.runout.engine.model.TableLayout

/** Game variants the photo pipeline can suggest. */
enum class DetectedGameVariant {
    EIGHT_BALL,
    NINE_BALL,
    TEN_BALL,
    SNOOKER,
}

/**
 * One ranked game-type hypothesis for a scanned layout.
 *
 * [engineGameType] is the closest game the planner supports (snooker falls back to 8-ball).
 */
data class GameTypeSuggestion(
    val variant: DetectedGameVariant,
    val engineGameType: GameType,
    val suggestedEightBallGroup: EightBallGroup = EightBallGroup.OPEN,
    val confidence: Double,
    val reasons: List<String>,
)

/**
 * Ranks the likely game for a scanned layout from several signals: table and ball size, the
 * highest-numbered object ball on the table, and the previously played game.
 */
object GameTypeSuggester {
    private const val SNOOKER_BALL_RADIUS_LIMIT_METERS = 0.027
    private const val SNOOKER_TABLE_LENGTH_LIMIT_METERS = 3.0
    private const val STRONG_SIGNAL = 2.0
    private const val SNOOKER_SIGNAL = 3.0
    private const val PRIOR_BOOST = 0.08

    private const val NO_EVIDENCE_REASON = "No distinguishing evidence for this variant"

    fun suggestGameTypes(
        layout: TableLayout,
        lastPlayedVariant: DetectedGameVariant? = null,
    ): List<GameTypeSuggestion> {
        val scores = mutableMapOf(
            DetectedGameVariant.EIGHT_BALL to 0.30,
            DetectedGameVariant.NINE_BALL to 0.25,
            DetectedGameVariant.TEN_BALL to 0.20,
            DetectedGameVariant.SNOOKER to 0.05,
        )
        val reasons = DetectedGameVariant.entries.associateWith { mutableListOf<String>() }

        fun addEvidence(variant: DetectedGameVariant, score: Double, reason: String) {
            scores[variant] = scores.getValue(variant) + score
            reasons.getValue(variant).add(reason)
        }

        val objectNumbers = layout.objectBalls().map { it.number }
        val maxNumber = objectNumbers.maxOrNull()
        val isSnooker = layout.table.name.contains("Snooker", ignoreCase = true) ||
            layout.table.lengthMeters > SNOOKER_TABLE_LENGTH_LIMIT_METERS ||
            layout.balls.any { it.radiusMeters <= SNOOKER_BALL_RADIUS_LIMIT_METERS }

        when {
            isSnooker -> addEvidence(
                DetectedGameVariant.SNOOKER,
                SNOOKER_SIGNAL,
                "12-ft snooker dimensions / 52.5 mm ball radius detected",
            )

            maxNumber == null -> Unit

            maxNumber in 11..15 -> addEvidence(
                DetectedGameVariant.EIGHT_BALL,
                STRONG_SIGNAL,
                "Balls numbered 11–15 present on table",
            )

            maxNumber == 10 -> addEvidence(
                DetectedGameVariant.TEN_BALL,
                STRONG_SIGNAL,
                "Highest numbered ball is 10 (no 11–15 balls)",
            )

            else -> {
                addEvidence(
                    DetectedGameVariant.NINE_BALL,
                    STRONG_SIGNAL,
                    "All object balls are numbered 1–9",
                )
                if (8 in objectNumbers) {
                    addEvidence(
                        DetectedGameVariant.EIGHT_BALL,
                        0.5,
                        "Eight-ball present, so a late 8-ball game is possible",
                    )
                }
            }
        }

        val total = scores.values.sum()
        val suggestedGroup = if (isSnooker) EightBallGroup.OPEN else suggestEightBallGroup(objectNumbers)

        return DetectedGameVariant.entries.map { variant ->
            val share = scores.getValue(variant) / total
            val boost = if (variant == lastPlayedVariant) PRIOR_BOOST else 0.0
            val reasonList = reasons.getValue(variant).toMutableList()
            if (boost > 0.0) reasonList.add("Matches recently played game type")
            if (reasonList.isEmpty()) reasonList.add(NO_EVIDENCE_REASON)
            GameTypeSuggestion(
                variant = variant,
                engineGameType = engineTypeFor(variant),
                suggestedEightBallGroup = if (variant == DetectedGameVariant.EIGHT_BALL) {
                    suggestedGroup
                } else {
                    EightBallGroup.OPEN
                },
                confidence = (share + boost).coerceIn(0.05, 0.98),
                reasons = reasonList,
            )
        }.sortedByDescending { it.confidence }
    }

    private fun engineTypeFor(variant: DetectedGameVariant): GameType = when (variant) {
        DetectedGameVariant.EIGHT_BALL -> GameType.EIGHT_BALL
        DetectedGameVariant.NINE_BALL -> GameType.NINE_BALL
        DetectedGameVariant.TEN_BALL -> GameType.TEN_BALL
        DetectedGameVariant.SNOOKER -> GameType.EIGHT_BALL
    }

    /**
     * Guesses the shooter's 8-ball group from what is left: only solids left suggests solids, only stripes
     * suggests stripes, only the 8 suggests the 8-ball, and a mix is an open table.
     */
    private fun suggestEightBallGroup(objectNumbers: List<Int>): EightBallGroup {
        val solids = objectNumbers.count { it in 1..7 }
        val stripes = objectNumbers.count { it in 9..15 }
        return when {
            solids > 0 && stripes == 0 -> EightBallGroup.SOLIDS
            stripes > 0 && solids == 0 -> EightBallGroup.STRIPES
            solids == 0 && stripes == 0 && 8 in objectNumbers -> EightBallGroup.EIGHT_BALL
            else -> EightBallGroup.OPEN
        }
    }
}
