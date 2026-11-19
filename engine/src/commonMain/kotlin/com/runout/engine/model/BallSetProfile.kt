package com.runout.engine.model

/**
 * Standard ball colour identifiers independent of UI rendering theme.
 */
enum class BallPaletteColor {
    WHITE,
    YELLOW,
    BLUE,
    RED,
    PURPLE,
    ORANGE,
    GREEN,
    MAROON,
    BLACK,
    PINK,
    BROWN,
}

/**
 * Mapping between pool ball numbers and physical ball colours, supporting preset switching
 * and number-to-colour swaps for custom or reordered ball sets.
 */
data class BallSetProfile(
    val name: String,
    val numberToColor: Map<Int, BallPaletteColor>,
    val ballRadiusMeters: Double = TableSpec.POOL_BALL_RADIUS_METERS,
) {
    init {
        require(ballRadiusMeters > 0.0) { "ballRadiusMeters must be positive." }
    }

    /**
     * Returns the configured [BallPaletteColor] for [number], falling back to the standard
     * pool palette if [number] is not explicitly mapped.
     */
    fun colorForBall(number: Int): BallPaletteColor =
        numberToColor[number] ?: standardColorFallback(number)

    /**
     * Returns a copy of this profile with the colours assigned to [numberA] and [numberB] swapped.
     */
    fun swapBallColors(numberA: Int, numberB: Int): BallSetProfile {
        require(numberA != 0 && numberB != 0) { "Cue ball (0) colour cannot be swapped." }
        if (numberA == numberB) return this
        val colorA = colorForBall(numberA)
        val colorB = colorForBall(numberB)
        val updated = numberToColor.toMutableMap()
        updated[numberA] = colorB
        updated[numberB] = colorA
        return copy(numberToColor = updated)
    }

    companion object {
        private val STANDARD_MAP: Map<Int, BallPaletteColor> = mapOf(
            0 to BallPaletteColor.WHITE,
            1 to BallPaletteColor.YELLOW,
            2 to BallPaletteColor.BLUE,
            3 to BallPaletteColor.RED,
            4 to BallPaletteColor.PURPLE,
            5 to BallPaletteColor.ORANGE,
            6 to BallPaletteColor.GREEN,
            7 to BallPaletteColor.MAROON,
            8 to BallPaletteColor.BLACK,
            9 to BallPaletteColor.YELLOW,
            10 to BallPaletteColor.BLUE,
            11 to BallPaletteColor.RED,
            12 to BallPaletteColor.PURPLE,
            13 to BallPaletteColor.ORANGE,
            14 to BallPaletteColor.GREEN,
            15 to BallPaletteColor.MAROON,
        )

        private val TV_TOURNAMENT_MAP: Map<Int, BallPaletteColor> = STANDARD_MAP + mapOf(
            4 to BallPaletteColor.PINK,
            7 to BallPaletteColor.BROWN,
            12 to BallPaletteColor.PINK,
            15 to BallPaletteColor.BROWN,
        )

        fun standardPool(): BallSetProfile = BallSetProfile(
            name = "Standard Pool",
            numberToColor = STANDARD_MAP,
        )

        fun tvTournamentPool(): BallSetProfile = BallSetProfile(
            name = "TV Tournament",
            numberToColor = TV_TOURNAMENT_MAP,
        )

        private fun standardColorFallback(number: Int): BallPaletteColor =
            STANDARD_MAP[number] ?: BallPaletteColor.WHITE
    }
}
