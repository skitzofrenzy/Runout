package com.runout.engine.model

/**
 * Supported cue-sports game types for legal target resolution and shot planning.
 */
enum class GameType(val displayName: String) {
    EIGHT_BALL("8-Ball"),
    NINE_BALL("9-Ball"),
    TEN_BALL("10-Ball"),
}

/**
 * Group assignment state in an 8-ball rack.
 */
enum class EightBallGroup(val displayName: String) {
    OPEN("Open Table"),
    SOLIDS("Solids (1–7)"),
    STRIPES("Stripes (9–15)"),
    EIGHT_BALL("8-Ball"),
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
