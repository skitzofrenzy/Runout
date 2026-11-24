package com.runout.engine.model

import com.runout.engine.math.Vec2
import kotlin.math.PI
import kotlin.math.sqrt

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

/** Corner or side (middle) pocket. */
enum class PocketKind { CORNER, SIDE }

/**
 * Geometry of a single pocket in SI metres: an opening cut into the cushions, with the hole behind the nose line.
 *
 * - [jawTipA], [jawTipB]: the two points where the cushion noses end and turn into the pocket (the points of the
 *   pocket). The mouth is the segment between them; for a corner pocket it cuts across the corner of the nose
 *   rectangle, for a side pocket it lies on the nose line.
 * - [axisIntoPocket]: unit vector from the mouth into the pocket, away from the playing surface.
 * - [facingAngleRad]: angle at each jaw tip between the cushion nose (pointing away from the pocket) and the facing
 *   (pointing into the pocket), measured through the cushion. `135°` for a corner and `90°` for a side pocket would
 *   make the facings parallel to the pocket axis; larger angles make them close in toward the back of the pocket.
 * - [shelfDepthMeters]: from the middle of the mouth to the edge of the hole, along [axisIntoPocket].
 * - [holeRadiusMeters]: radius of the hole the ball drops into.
 * - [jawToleranceSlow], [jawToleranceFast]: the clearance a ball's centre must keep from each jaw tip to drop, as a
 *   fraction of the ball radius, at or below [PocketPreset.SLOW_ARRIVAL_SPEED_MPS] and at or above
 *   [PocketPreset.FAST_ARRIVAL_SPEED_MPS] (linear in between). Below `1` a slow ball that brushes a facing still
 *   drops; above `1` a fast one that only just clears the point rattles out. See
 *   [com.runout.engine.physics.PocketGeometry.jawTolerance].
 */
data class PocketSpec(
    val id: PocketId,
    val jawTipA: Vec2,
    val jawTipB: Vec2,
    val axisIntoPocket: Vec2,
    val facingAngleRad: Double,
    val shelfDepthMeters: Double,
    val holeRadiusMeters: Double,
    val jawToleranceSlow: Double,
    val jawToleranceFast: Double,
) {
    val kind: PocketKind
        get() = if (id == PocketId.SIDE_LEFT || id == PocketId.SIDE_RIGHT) PocketKind.SIDE else PocketKind.CORNER

    /** Tip-to-tip width of the mouth. */
    val mouthWidthMeters: Double get() = jawTipA.distanceTo(jawTipB)

    /** Middle of the mouth: inside the nose rectangle for a corner pocket, on the nose line for a side pocket. */
    val mouthCenter: Vec2 get() = (jawTipA + jawTipB) * 0.5

    /** Centre of the hole, behind the nose line and outside the playing rectangle. */
    val holeCenter: Vec2 get() = mouthCenter + axisIntoPocket * (shelfDepthMeters + holeRadiusMeters)
}

/**
 * Pocket cuts. Dimensions follow the World Pool-Billiard Association tournament equipment specification where it
 * gives them (corner mouth 4.5-4.625 in, side mouth 5-5.125 in, facing angle 142° ± 1° corner and 104° ± 1° side,
 * shelf 1-2.25 in corner and 0-0.375 in side); the hole radius is not specified there and is an approximation.
 *
 * The jaw tolerances are modelling values, not measurements. At a medium arrival speed they put the effective size of
 * a pocket near the tip-to-tip mouth less about two inches, the usual rule of thumb; slow balls that brush a facing
 * still drop; the pocket tightens with speed (published measurements show close to two-to-one tightening between
 * soft and firm shots at shallow angles); corner pockets forgive slow balls more than side pockets. Calibrating them
 * against measured pocket sizes is owed.
 */
enum class PocketPreset(
    val cornerMouthMeters: Double,
    val sideMouthMeters: Double,
    val cornerFacingAngleDeg: Double,
    val sideFacingAngleDeg: Double,
    val cornerShelfMeters: Double,
    val sideShelfMeters: Double,
    val cornerHoleRadiusMeters: Double,
    val sideHoleRadiusMeters: Double,
    val cornerJawToleranceSlow: Double,
    val cornerJawToleranceFast: Double,
    val sideJawToleranceSlow: Double,
    val sideJawToleranceFast: Double,
) {
    /** WPA tournament cut: 4.5 in corners, 5 in sides, 142° / 104° facings, 1.75 in / 0.1875 in shelves. */
    TOURNAMENT(0.1143, 0.1270, 142.0, 104.0, 0.0445, 0.0048, 0.058, 0.062, 0.60, 1.05, 0.85, 1.10),

    /** Tighter "pro-cut" pockets: 4.25 in corners, 4.875 in sides, the deepest allowed shelves. */
    PRO_CUT(0.1080, 0.1238, 143.0, 105.0, 0.0572, 0.0095, 0.056, 0.060, 0.60, 1.05, 0.85, 1.10),

    /** Looser bar-box cut: 4.875 in corners, 5.375 in sides, shallow shelves. */
    BAR_BOX(0.1238, 0.1365, 141.0, 103.0, 0.0254, 0.0, 0.060, 0.064, 0.60, 1.05, 0.85, 1.10),

    /**
     * Snooker, approximate: about 86 mm corners and 105 mm middles at the nose line, with the same straight-facing
     * jaw model. Real snooker pockets have rounded cushion falls, which this model does not describe.
     */
    SNOOKER(0.0860, 0.1050, 140.0, 100.0, 0.0300, 0.0100, 0.050, 0.055, 0.60, 1.05, 0.85, 1.10),
    ;

    companion object {
        /** At or below this arrival speed a pocket uses its slow jaw tolerance, m/s. */
        const val SLOW_ARRIVAL_SPEED_MPS: Double = 1.0

        /** At or above this arrival speed a pocket uses its fast jaw tolerance, m/s. */
        const val FAST_ARRIVAL_SPEED_MPS: Double = 4.0

        /** Default cut for a table with balls of [ballRadiusMeters]: snooker for snooker balls, else tournament pool. */
        fun forBallRadius(ballRadiusMeters: Double): PocketPreset =
            if (ballRadiusMeters < 0.0275) SNOOKER else TOURNAMENT
    }
}

/**
 * Assumed cloth roll and rail density presets for uncalibrated tables or online photo/video scans.
 *
 * Spinning friction scales with sliding friction (the same cloth under the same contact patch, see
 * [ClothParams.spinningFrictionMuSp]); cushion friction and nose height are not known to differ between these table
 * types, so every preset uses the defaults.
 */
enum class AssumedSurfacePreset(
    val rollingResistanceMuR: Double,
    val slidingFrictionMuS: Double,
    val cushionRestitutionEc: Double,
    val cushionFrictionFc: Double = ClothParams.DEFAULT_CUSHION_FRICTION_FC,
    val cushionNoseHeightRatio: Double = ClothParams.DEFAULT_CUSHION_NOSE_HEIGHT_RATIO,
) {
    /** Fast tournament worsted cloth & lively K55 rails (ideal assumed preset for online broadcast photos/videos). */
    PRO_BROADCAST_FAST(0.0068, 0.165, 0.82),

    /** Balanced commercial cloth & standard rails. */
    STANDARD_POOL_HALL(0.0100, 0.200, 0.75),

    /** Slow napped bar cloth & softer/dead rails. */
    SLOW_BAR_BOX_HEAVY(0.0145, 0.240, 0.66),

    /** Fine directional snooker cloth & steel-block rails. */
    SNOOKER_DIRECTIONAL(0.0082, 0.180, 0.78),
    ;

    /** Spinning friction for this preset's cloth, [ClothParams.spinningFrictionForSliding] of its sliding friction. */
    val spinningFrictionMuSp: Double get() = ClothParams.spinningFrictionForSliding(slidingFrictionMuS)
}

/**
 * Cloth and cushion physical parameters (dimensionless coefficients).
 *
 * - [rollingResistanceMuR], [slidingFrictionMuS]: ball on cloth, rolling and sliding.
 * - [cushionRestitutionEc]: restitution of the ball-cushion contact along its contact normal (the line from the
 *   contact point on the cushion nose to the ball's centre), in `0.50..0.92`. A head-on rebound leaves at a little
 *   less than this, because the contact normal is tilted (see [cushionNoseHeightRatio]) and friction acts too.
 * - [cushionFrictionFc]: ball-cushion sliding friction, in `0.05..0.40`.
 * - [cushionNoseHeightRatio]: height of the cushion nose above the bed as a fraction of the ball diameter, in
 *   `0.60..0.70`; the cushion meets the ball `(2 * ratio - 1) * R` above its centre.
 * - [spinningFrictionMuSp]: friction that brakes spin about the vertical axis, in `0.003..0.05`; that spin decays at
 *   `5 * muSp * g / (2 R)` whatever the ball is doing.
 *
 * Defaults and sources: `muR`, `muS` and `e_c` are the starting values of `docs/research/physics.md`;
 * `f_c = 0.20` is the ball-cushion friction commonly used with the Han (2005) cushion model; the nose height is the
 * middle of the 62.5-64.5 % of ball diameter that the WPA equipment specifications allow; `muSp = 0.0127` is the
 * friction disc of a contact patch about 2.7 mm in radius under uniform pressure, `(2/3) * muS * a / R`, which spins
 * a ball down at about 11 rad/s^2. All of them are meant to be replaced by calibration.
 */
data class ClothParams(
    val rollingResistanceMuR: Double = 0.010,
    val slidingFrictionMuS: Double = 0.20,
    val cushionRestitutionEc: Double = 0.75,
    val cushionFrictionFc: Double = DEFAULT_CUSHION_FRICTION_FC,
    val cushionNoseHeightRatio: Double = DEFAULT_CUSHION_NOSE_HEIGHT_RATIO,
    val spinningFrictionMuSp: Double = spinningFrictionForSliding(slidingFrictionMuS),
) {
    /**
     * Returns an updated [ClothParams] with clamped cloth roll ([rollingResistanceMuR] in `0.004..0.025`)
     * and rail bounce ([cushionRestitutionEc] in `0.50..0.92`), scaling sliding and spinning friction coherently.
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
        val clampedEc = safeEc.coerceIn(MIN_CUSHION_RESTITUTION_EC, MAX_CUSHION_RESTITUTION_EC)
        val derivedMuS = (0.12 + clampedMuR * 8.0).coerceIn(0.10, 0.35)
        return copy(
            rollingResistanceMuR = clampedMuR,
            slidingFrictionMuS = derivedMuS,
            cushionRestitutionEc = clampedEc,
            spinningFrictionMuSp = spinningFrictionForSliding(derivedMuS),
        )
    }

    companion object {
        const val DEFAULT_CUSHION_FRICTION_FC: Double = 0.20
        const val DEFAULT_CUSHION_NOSE_HEIGHT_RATIO: Double = 0.635

        const val MIN_CUSHION_RESTITUTION_EC: Double = 0.50
        const val MAX_CUSHION_RESTITUTION_EC: Double = 0.92
        const val MIN_CUSHION_FRICTION_FC: Double = 0.05
        const val MAX_CUSHION_FRICTION_FC: Double = 0.40
        const val MIN_CUSHION_NOSE_HEIGHT_RATIO: Double = 0.60
        const val MAX_CUSHION_NOSE_HEIGHT_RATIO: Double = 0.70
        const val MIN_SPINNING_FRICTION_MU_SP: Double = 0.003
        const val MAX_SPINNING_FRICTION_MU_SP: Double = 0.05

        /** `(2/3) * a / R` for a uniform-pressure contact patch of radius `a`, about 2.7 mm on a 57.15 mm ball. */
        private const val SPINNING_TO_SLIDING_FRICTION_RATIO: Double = 0.0635

        /** Spinning friction of a cloth with sliding friction [slidingFrictionMuS]: `0.0635 * muS`. */
        fun spinningFrictionForSliding(slidingFrictionMuS: Double): Double =
            (SPINNING_TO_SLIDING_FRICTION_RATIO * slidingFrictionMuS)
                .coerceIn(MIN_SPINNING_FRICTION_MU_SP, MAX_SPINNING_FRICTION_MU_SP)

        fun fromPreset(preset: AssumedSurfacePreset): ClothParams = ClothParams(
            rollingResistanceMuR = preset.rollingResistanceMuR,
            slidingFrictionMuS = preset.slidingFrictionMuS,
            cushionRestitutionEc = preset.cushionRestitutionEc,
            cushionFrictionFc = preset.cushionFrictionFc,
            cushionNoseHeightRatio = preset.cushionNoseHeightRatio,
            spinningFrictionMuSp = preset.spinningFrictionMuSp,
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

        /**
         * The six pockets of an [lengthMeters] x [widthMeters] nose rectangle cut as [preset]. Corner jaw tips sit
         * `m / sqrt(2)` along each rail from the corner, side tips `m / 2` either side of the middle of the long rail.
         */
        fun defaultPockets(
            lengthMeters: Double,
            widthMeters: Double,
            ballRadiusMeters: Double = POOL_BALL_RADIUS_METERS,
            preset: PocketPreset = PocketPreset.forBallRadius(ballRadiusMeters),
        ): List<PocketSpec> {
            val l = lengthMeters
            val w = widthMeters
            val c = preset.cornerMouthMeters / sqrt(2.0)
            val s = preset.sideMouthMeters * 0.5
            fun corner(id: PocketId, cornerPoint: Vec2, alongX: Double, alongY: Double): PocketSpec = PocketSpec(
                id = id,
                jawTipA = Vec2(cornerPoint.x + alongX * c, cornerPoint.y),
                jawTipB = Vec2(cornerPoint.x, cornerPoint.y + alongY * c),
                axisIntoPocket = Vec2(-alongX, -alongY).normalized(),
                facingAngleRad = preset.cornerFacingAngleDeg * PI / 180.0,
                shelfDepthMeters = preset.cornerShelfMeters,
                holeRadiusMeters = preset.cornerHoleRadiusMeters,
                jawToleranceSlow = preset.cornerJawToleranceSlow,
                jawToleranceFast = preset.cornerJawToleranceFast,
            )
            fun side(id: PocketId, railY: Double, inwardY: Double): PocketSpec = PocketSpec(
                id = id,
                jawTipA = Vec2(l * 0.5 - s, railY),
                jawTipB = Vec2(l * 0.5 + s, railY),
                axisIntoPocket = Vec2(0.0, -inwardY),
                facingAngleRad = preset.sideFacingAngleDeg * PI / 180.0,
                shelfDepthMeters = preset.sideShelfMeters,
                holeRadiusMeters = preset.sideHoleRadiusMeters,
                jawToleranceSlow = preset.sideJawToleranceSlow,
                jawToleranceFast = preset.sideJawToleranceFast,
            )
            return listOf(
                corner(PocketId.HEAD_LEFT, Vec2(0.0, 0.0), 1.0, 1.0),
                corner(PocketId.HEAD_RIGHT, Vec2(0.0, w), 1.0, -1.0),
                side(PocketId.SIDE_LEFT, 0.0, 1.0),
                side(PocketId.SIDE_RIGHT, w, -1.0),
                corner(PocketId.FOOT_LEFT, Vec2(l, 0.0), -1.0, 1.0),
                corner(PocketId.FOOT_RIGHT, Vec2(l, w), -1.0, -1.0),
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
