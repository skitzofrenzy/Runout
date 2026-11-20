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

        private val WNT_TOURNAMENT_BLACK_MAP: Map<Int, BallPaletteColor> = TV_TOURNAMENT_MAP + mapOf(
            5 to BallPaletteColor.PURPLE,
            13 to BallPaletteColor.PURPLE,
        )

        private val UK_BLACKBALL_MAP: Map<Int, BallPaletteColor> = mapOf(
            0 to BallPaletteColor.WHITE,
            1 to BallPaletteColor.YELLOW,
            2 to BallPaletteColor.YELLOW,
            3 to BallPaletteColor.YELLOW,
            4 to BallPaletteColor.YELLOW,
            5 to BallPaletteColor.YELLOW,
            6 to BallPaletteColor.YELLOW,
            7 to BallPaletteColor.YELLOW,
            8 to BallPaletteColor.BLACK,
            9 to BallPaletteColor.RED,
            10 to BallPaletteColor.RED,
            11 to BallPaletteColor.RED,
            12 to BallPaletteColor.RED,
            13 to BallPaletteColor.RED,
            14 to BallPaletteColor.RED,
            15 to BallPaletteColor.RED,
        )

        fun standardPool(): BallSetProfile = BallSetProfile(
            name = "Standard Pool",
            numberToColor = STANDARD_MAP,
        )

        fun tvTournamentPool(): BallSetProfile = BallSetProfile(
            name = "TV Tournament",
            numberToColor = TV_TOURNAMENT_MAP,
        )

        fun wntTournamentBlackPool(): BallSetProfile = BallSetProfile(
            name = "WNT Tournament Black",
            numberToColor = WNT_TOURNAMENT_BLACK_MAP,
        )

        fun ukBlackballPool(): BallSetProfile = BallSetProfile(
            name = "UK Blackball",
            numberToColor = UK_BLACKBALL_MAP,
        )

        /**
         * Infers whether a detected set of ball palette colours comes from a UK Blackball
         * (yellows `1..7` and reds `9..15`), WNT Tournament Black (`4 = PINK, 5 = PURPLE,
         * 7 = BROWN`), TV Tournament (`4 = PINK, 7 = BROWN`), or the caller's [defaultProfile].
         */
        fun inferFromDetectedColors(
            dominantColors: Iterable<BallPaletteColor>,
            defaultProfile: BallSetProfile = standardPool(),
        ): BallSetProfile {
            if (defaultProfile.name != "Standard Pool") return defaultProfile
            val list = dominantColors.toList()
            val yellowCount = list.count { it == BallPaletteColor.YELLOW }
            val redFamilyCount = list.count {
                it == BallPaletteColor.RED || it == BallPaletteColor.MAROON || it == BallPaletteColor.BROWN
            }
            val otherColorCount = list.count {
                it == BallPaletteColor.BLUE || it == BallPaletteColor.GREEN || it == BallPaletteColor.PURPLE
            }
            if ((yellowCount >= 3 || redFamilyCount >= 3) && otherColorCount == 0) {
                return ukBlackballPool()
            }
            val colors = list.toSet()
            val hasPink = BallPaletteColor.PINK in colors
            val hasPurple = BallPaletteColor.PURPLE in colors
            val hasBrown = BallPaletteColor.BROWN in colors
            val hasOrange = BallPaletteColor.ORANGE in colors
            return when {
                (hasPink && hasPurple) || (hasBrown && hasPurple && !hasOrange) -> wntTournamentBlackPool()
                hasPink || hasBrown -> tvTournamentPool()
                else -> defaultProfile
            }
        }

        private fun standardColorFallback(number: Int): BallPaletteColor =
            STANDARD_MAP[number] ?: BallPaletteColor.WHITE
    }
}
