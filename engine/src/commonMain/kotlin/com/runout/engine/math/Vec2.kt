package com.runout.engine.math

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Immutable 2D vector in SI metres.
 *
 * Table coordinate convention:
 * - Origin `(0, 0)` is at the head-end left cushion nose.
 * - `x` runs along the long side towards the foot end (`0..lengthMeters`).
 * - `y` runs across the width of the table (`0..widthMeters`).
 */
data class Vec2(
    val x: Double,
    val y: Double,
) {
    operator fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)

    operator fun minus(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)

    operator fun times(scalar: Double): Vec2 = Vec2(x * scalar, y * scalar)

    operator fun div(scalar: Double): Vec2 = Vec2(x / scalar, y / scalar)

    operator fun unaryMinus(): Vec2 = Vec2(-x, -y)

    fun length(): Double = hypot(x, y)

    fun lengthSquared(): Double = x * x + y * y

    fun distanceTo(other: Vec2): Double = hypot(x - other.x, y - other.y)

    fun distanceSquaredTo(other: Vec2): Double {
        val dx = x - other.x
        val dy = y - other.y
        return dx * dx + dy * dy
    }

    fun normalized(): Vec2 {
        val len = length()
        return if (len < 1e-12) ZERO else Vec2(x / len, y / len)
    }

    fun dot(other: Vec2): Double = x * other.x + y * other.y

    /**
     * 2D scalar cross product (`x * other.y - y * other.x`).
     */
    fun cross(other: Vec2): Double = x * other.y - y * other.x

    /**
     * Rotates this vector by [angleRad] radians in the standard `(x, y)` frame.
     */
    fun rotate(angleRad: Double): Vec2 {
        val c = cos(angleRad)
        val s = sin(angleRad)
        return Vec2(
            x = x * c - y * s,
            y = x * s + y * c,
        )
    }

    /**
     * Signed angle in radians in `[-PI, PI]` from this vector to [other].
     */
    fun angleTo(other: Vec2): Double {
        if (lengthSquared() < 1e-24 || other.lengthSquared() < 1e-24) return 0.0
        return atan2(cross(other), dot(other))
    }

    fun lerp(other: Vec2, t: Double): Vec2 = Vec2(
        x = x + (other.x - x) * t,
        y = y + (other.y - y) * t,
    )

    companion object {
        val ZERO = Vec2(0.0, 0.0)
        val UNIT_X = Vec2(1.0, 0.0)
        val UNIT_Y = Vec2(0.0, 1.0)
    }
}

operator fun Double.times(v: Vec2): Vec2 = v * this
