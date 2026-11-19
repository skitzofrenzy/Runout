package com.runout.engine.model

import com.runout.engine.math.Vec2

/**
 * Standard 6-pocket identifiers following the table coordinate convention:
 * - Head end (`x = 0`) is the break end.
 * - Foot end (`x = lengthMeters`) is the rack end.
 * - Left rail is `y = 0`; right rail is `y = widthMeters`.
 */
enum class PocketId(val displayName: String) {
    HEAD_LEFT("Head Left"),
    HEAD_RIGHT("Head Right"),
    SIDE_LEFT("Side Left"),
    SIDE_RIGHT("Side Right"),
    FOOT_LEFT("Foot Left"),
    FOOT_RIGHT("Foot Right"),
}

/**
 * Geometry of a single pocket in SI metres.
 */
data class PocketSpec(
    val id: PocketId,
    val center: Vec2,
    val mouthWidthMeters: Double,
    val acceptanceRadiusMeters: Double,
)

/**
 * Assumed cloth roll and rail density presets for uncalibrated tables or online photo/video scans.
 */
enum class AssumedSurfacePreset(
    val rollingResistanceMuR: Double,
    val slidingFrictionMuS: Double,
    val cushionRestitutionEc: Double,
) {
    /** Fast tournament worsted cloth & lively K55 rails (ideal assumed preset for online broadcast photos/videos). */
    PRO_BROADCAST_FAST(0.0068, 0.165, 0.82),

    /** Balanced commercial cloth & standard rails. */
    STANDARD_POOL_HALL(0.0100, 0.200, 0.75),

    /** Slow napped bar cloth & softer/dead rails. */
    SLOW_BAR_BOX_HEAVY(0.0145, 0.240, 0.66),

    /** Fine directional snooker cloth & steel-block rails. */
    SNOOKER_DIRECTIONAL(0.0082, 0.180, 0.78),
}

/**
 * Cloth and cushion physical parameters (dimensionless coefficients).
 */
data class ClothParams(
    val rollingResistanceMuR: Double = 0.010,
    val slidingFrictionMuS: Double = 0.20,
    val cushionRestitutionEc: Double = 0.75,
) {
    /**
     * Returns an updated [ClothParams] with clamped cloth roll ([rollingResistanceMuR] in `0.004..0.025`)
     * and rail bounce ([cushionRestitutionEc] in `0.50..0.92`), scaling sliding friction coherently.
     */
    fun withAdjustedRollAndRail(
        rollingResistanceMuR: Double = this.rollingResistanceMuR,
        cushionRestitutionEc: Double = this.cushionRestitutionEc,
    ): ClothParams {
        val safeMuR = if (rollingResistanceMuR.isNaN()) {
            if (this.rollingResistanceMuR.isNaN()) 0.010 else this.rollingResistanceMuR
        } else {
            rollingResistanceMuR
        }
        val safeEc = if (cushionRestitutionEc.isNaN()) {
            if (this.cushionRestitutionEc.isNaN()) 0.75 else this.cushionRestitutionEc
        } else {
            cushionRestitutionEc
        }
        val clampedMuR = safeMuR.coerceIn(0.004, 0.025)
        val clampedEc = safeEc.coerceIn(0.50, 0.92)
        val derivedMuS = (0.12 + clampedMuR * 8.0).coerceIn(0.10, 0.35)
        return copy(
            rollingResistanceMuR = clampedMuR,
            slidingFrictionMuS = derivedMuS,
            cushionRestitutionEc = clampedEc,
        )
    }

    companion object {
        fun fromPreset(preset: AssumedSurfacePreset): ClothParams = ClothParams(
            rollingResistanceMuR = preset.rollingResistanceMuR,
            slidingFrictionMuS = preset.slidingFrictionMuS,
            cushionRestitutionEc = preset.cushionRestitutionEc,
        )
    }
}

/**
 * Table specification in SI metres.
 *
 * Coordinate convention:
 * - Origin `(0, 0)` is at the head-end left cushion nose.
 * - `x` runs along the long side towards the foot end (`0..lengthMeters`).
 * - `y` runs across the table (`0..widthMeters`).
 */
data class TableSpec(
    val lengthMeters: Double,
    val widthMeters: Double,
    val ballRadiusMeters: Double = POOL_BALL_RADIUS_METERS,
    val pockets: List<PocketSpec> = defaultPockets(lengthMeters, widthMeters, ballRadiusMeters),
    val cloth: ClothParams = ClothParams(),
    val name: String = "9 ft Pool",
) {
    /**
     * Returns true if a ball of [radius] centred at [pos] lies strictly on the playing surface
     * without overlapping the cushion noses.
     */
    fun isWithinPlayableBounds(pos: Vec2, radius: Double = ballRadiusMeters): Boolean {
        return pos.x >= radius &&
            pos.x <= lengthMeters - radius &&
            pos.y >= radius &&
            pos.y <= widthMeters - radius
    }

    /**
     * Head spot at 1/4 of table length, centred across the width.
     */
    fun headSpot(): Vec2 = Vec2(lengthMeters * 0.25, widthMeters * 0.5)

    /**
     * Foot spot at 3/4 of table length, centred across the width.
     */
    fun footSpot(): Vec2 = Vec2(lengthMeters * 0.75, widthMeters * 0.5)

    fun pocketById(id: PocketId): PocketSpec = pockets.first { it.id == id }

    /**
     * Returns a copy of this table specification with updated [ClothParams].
     */
    fun withClothParams(newCloth: ClothParams): TableSpec = copy(cloth = newCloth)

    companion object {
        const val POOL_BALL_RADIUS_METERS: Double = 0.028575
        const val SNOOKER_BALL_RADIUS_METERS: Double = 0.02625

        fun defaultPockets(
            lengthMeters: Double,
            widthMeters: Double,
            ballRadiusMeters: Double = POOL_BALL_RADIUS_METERS,
        ): List<PocketSpec> {
            val cornerMouth = ballRadiusMeters * 4.0
            val sideMouth = ballRadiusMeters * 4.4
            val cornerAcceptance = ballRadiusMeters * 2.2
            val sideAcceptance = ballRadiusMeters * 2.3
            return listOf(
                PocketSpec(
                    id = PocketId.HEAD_LEFT,
                    center = Vec2(0.0, 0.0),
                    mouthWidthMeters = cornerMouth,
                    acceptanceRadiusMeters = cornerAcceptance,
                ),
                PocketSpec(
                    id = PocketId.HEAD_RIGHT,
                    center = Vec2(0.0, widthMeters),
                    mouthWidthMeters = cornerMouth,
                    acceptanceRadiusMeters = cornerAcceptance,
                ),
                PocketSpec(
                    id = PocketId.SIDE_LEFT,
                    center = Vec2(lengthMeters * 0.5, 0.0),
                    mouthWidthMeters = sideMouth,
                    acceptanceRadiusMeters = sideAcceptance,
                ),
                PocketSpec(
                    id = PocketId.SIDE_RIGHT,
                    center = Vec2(lengthMeters * 0.5, widthMeters),
                    mouthWidthMeters = sideMouth,
                    acceptanceRadiusMeters = sideAcceptance,
                ),
                PocketSpec(
                    id = PocketId.FOOT_LEFT,
                    center = Vec2(lengthMeters, 0.0),
                    mouthWidthMeters = cornerMouth,
                    acceptanceRadiusMeters = cornerAcceptance,
                ),
                PocketSpec(
                    id = PocketId.FOOT_RIGHT,
                    center = Vec2(lengthMeters, widthMeters),
                    mouthWidthMeters = cornerMouth,
                    acceptanceRadiusMeters = cornerAcceptance,
                ),
            )
        }

        /** 9 ft regulation pool table: `2.54 x 1.27 m`, 57.15 mm balls (`R = 0.028575 m`). */
        fun nineFootPool(cloth: ClothParams = ClothParams()): TableSpec = TableSpec(
            lengthMeters = 2.54,
            widthMeters = 1.27,
            ballRadiusMeters = POOL_BALL_RADIUS_METERS,
            cloth = cloth,
            name = "9 ft Pool",
        )

        /** 8 ft pool table: `2.24 x 1.12 m`, 57.15 mm balls (`R = 0.028575 m`). */
        fun eightFootPool(cloth: ClothParams = ClothParams()): TableSpec = TableSpec(
            lengthMeters = 2.24,
            widthMeters = 1.12,
            ballRadiusMeters = POOL_BALL_RADIUS_METERS,
            cloth = cloth,
            name = "8 ft Pool",
        )

        /** 7 ft bar-box pool table: `1.98 x 0.99 m`, 57.15 mm balls (`R = 0.028575 m`). */
        fun sevenFootPool(cloth: ClothParams = ClothParams()): TableSpec = TableSpec(
            lengthMeters = 1.98,
            widthMeters = 0.99,
            ballRadiusMeters = POOL_BALL_RADIUS_METERS,
            cloth = cloth,
            name = "7 ft Pool",
        )

        /** Full-size 12 ft snooker table: `3.569 x 1.778 m`, 52.5 mm balls (`R = 0.02625 m`). */
        fun fullSizeSnooker(cloth: ClothParams = ClothParams()): TableSpec = TableSpec(
            lengthMeters = 3.569,
            widthMeters = 1.778,
            ballRadiusMeters = SNOOKER_BALL_RADIUS_METERS,
            cloth = cloth,
            name = "12 ft Snooker",
        )
    }
}
