package com.runout.engine.model

import com.runout.engine.math.Vec2

/**
 * Role of a ball in pool/snooker layouts.
 */
enum class BallRole {
    CUE,
    SOLID,
    EIGHT_BALL,
    STRIPE,
    OTHER,
}

/**
 * A ball on the playing surface in SI units (metres, kilograms).
 *
 * Pool numbering convention:
 * - `0`: Cue ball
 * - `1..7`: Solids
 * - `8`: Eight-ball
 * - `9..15`: Stripes
 */
data class Ball(
    val number: Int,
    val position: Vec2,
    val radiusMeters: Double = DEFAULT_POOL_BALL_RADIUS_METERS,
    val massKg: Double = DEFAULT_POOL_BALL_MASS_KG,
) {
    val isCueBall: Boolean
        get() = number == 0

    val isSolid: Boolean
        get() = number in 1..7

    val isEightBall: Boolean
        get() = number == 8

    val isStripe: Boolean
        get() = number in 9..15

    val role: BallRole
        get() = when {
            isCueBall -> BallRole.CUE
            isSolid -> BallRole.SOLID
            isEightBall -> BallRole.EIGHT_BALL
            isStripe -> BallRole.STRIPE
            else -> BallRole.OTHER
        }

    companion object {
        const val DEFAULT_POOL_BALL_RADIUS_METERS: Double = TableSpec.POOL_BALL_RADIUS_METERS
        const val DEFAULT_SNOOKER_BALL_RADIUS_METERS: Double = TableSpec.SNOOKER_BALL_RADIUS_METERS
        const val DEFAULT_POOL_BALL_MASS_KG: Double = 0.165
    }
}
