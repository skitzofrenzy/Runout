package com.runout.engine.model

/**
 * Supported cue-sports game types for legal target resolution and shot planning.
 */
enum class GameType(
    val displayName: String,
    val requiredEndBallNumber: Int,
    val endBallLabel: String,
) {
    EIGHT_BALL("8-Ball", requiredEndBallNumber = 8, endBallLabel = "8-ball"),
    NINE_BALL("9-Ball", requiredEndBallNumber = 9, endBallLabel = "9-ball"),
    TEN_BALL("10-Ball", requiredEndBallNumber = 10, endBallLabel = "10-ball"),
}

/**
 * Group assignment state in an 8-ball rack.
 */
enum class EightBallGroup(val displayName: String) {
    UNSET("Choose Group"),
    OPEN("Open Table"),
    SOLIDS("Solids (1–7)"),
    STRIPES("Stripes (9–15)"),
    EIGHT_BALL("8-Ball"),
    ;

    /**
     * Human-readable label adapted for UK Blackball (Yellows/Reds) vs standard US 8-ball (Solids/Stripes).
     */
    fun displayNameFor(ballSet: BallSetProfile): String = when (this) {
        UNSET -> "Choose Group"
        OPEN -> "Open Table"
        SOLIDS -> if (ballSet.isUkBlackball) "Yellows (1–7)" else "Solids (1–7)"
        STRIPES -> if (ballSet.isUkBlackball) "Reds (9–15)" else "Stripes (9–15)"
        EIGHT_BALL -> "8-Ball"
    }
}

/**
 * Current game type and group state used to determine which object balls are legal first-contact targets.
 */
data class GameRulesState(
    val gameType: GameType = GameType.NINE_BALL,
    val eightBallGroup: EightBallGroup = EightBallGroup.OPEN,
) {
    /**
     * Returns the object balls on [layout] that are legal targets for the current shot,
     * sorted by ball number ascending.
     */
    fun legalTargetBalls(layout: TableLayout): List<Ball> {
        val objectBalls = layout.objectBalls().sortedBy { it.number }
        if (objectBalls.isEmpty()) return emptyList()

        return when (gameType) {
            GameType.NINE_BALL,
            GameType.TEN_BALL,
            -> listOfNotNull(objectBalls.minByOrNull { it.number })

            GameType.EIGHT_BALL -> when (eightBallGroup) {
                EightBallGroup.UNSET -> emptyList()

                EightBallGroup.OPEN -> {
                    val groupBalls = objectBalls.filter { it.isSolid || it.isStripe }
                    groupBalls.ifEmpty { objectBalls.filter { it.isEightBall } }
                }

                EightBallGroup.SOLIDS -> {
                    val solids = objectBalls.filter { it.isSolid }
                    solids.ifEmpty { objectBalls.filter { it.isEightBall } }
                }

                EightBallGroup.STRIPES -> {
                    val stripes = objectBalls.filter { it.isStripe }
                    stripes.ifEmpty { objectBalls.filter { it.isEightBall } }
                }

                EightBallGroup.EIGHT_BALL -> objectBalls.filter { it.isEightBall }
            }
        }
    }
}
