package com.runout.engine.model

import kotlin.math.roundToInt

/**
 * Player skill tier used to initialize default aim-error and cue-control parameters.
 */
enum class ExperienceLevel {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED,
}

/**
 * Cue shaft deflection class used to adjust baseline aim error.
 */
enum class ShaftType {
    STANDARD,
    LOW_DEFLECTION,
}

/**
 * Calibrated player stroke profile in SI units (metres/second, radians).
 */
data class PlayerProfile(
    val name: String = "Default Player",
    val experienceLevel: ExperienceLevel = ExperienceLevel.INTERMEDIATE,
    val shaftType: ShaftType = ShaftType.STANDARD,
    val speedScaleMinMps: Double = 0.6,
    val speedScaleMaxMps: Double = 4.2,
    val maxEffectiveDrawRatio: Double = 1.5,
    val aimErrorStdRad: Double = DEFAULT_AIM_ERROR_STD_RAD,
) {
    init {
        require(speedScaleMinMps > 0.0) { "speedScaleMinMps must be positive." }
        require(speedScaleMaxMps > speedScaleMinMps) {
            "speedScaleMaxMps ($speedScaleMaxMps) must exceed speedScaleMinMps ($speedScaleMinMps)."
        }
        require(maxEffectiveDrawRatio >= 0.0) { "maxEffectiveDrawRatio must be non-negative." }
        require(aimErrorStdRad >= 0.0) { "aimErrorStdRad must be non-negative." }
    }

    /**
     * Maps a player-facing speed scale (`1..10`) linearly to SI speed in m/s.
     */
    fun speedForScale(scale: Int): Double {
        val clamped = scale.coerceIn(MIN_SPEED_SCALE, MAX_SPEED_SCALE)
        val fraction = (clamped - MIN_SPEED_SCALE) / (MAX_SPEED_SCALE - MIN_SPEED_SCALE).toDouble()
        return speedScaleMinMps + fraction * (speedScaleMaxMps - speedScaleMinMps)
    }

    /**
     * Maps a 20-point half-step speed unit (`0.5, 1.0, 1.5, ..., 10.0`) to SI speed in m/s.
     * Values in `1.0..10.0` match [speedForScale] identically at integer marks; `0.5` provides a
     * delicate touch/lag stroke smoothly below `1.0`.
     */
    fun speedForUnits(units: Double): Double {
        val clamped = units.coerceIn(MIN_SPEED_UNITS, MAX_SPEED_UNITS)
        val stepMps = (speedScaleMaxMps - speedScaleMinMps) / (MAX_SPEED_SCALE - MIN_SPEED_SCALE).toDouble()
        return if (clamped >= 1.0) {
            speedScaleMinMps + (clamped - 1.0) * stepMps
        } else {
            (speedScaleMinMps - (1.0 - clamped) * stepMps).coerceAtLeast(speedScaleMinMps * 0.60)
        }
    }

    /**
     * Maps an SI speed in m/s to the nearest integer player speed scale (`1..10`).
     */
    fun scaleForSpeed(speedMps: Double): Int {
        val fraction = (speedMps - speedScaleMinMps) / (speedScaleMaxMps - speedScaleMinMps)
        return (MIN_SPEED_SCALE + fraction * (MAX_SPEED_SCALE - MIN_SPEED_SCALE))
            .roundToInt()
            .coerceIn(MIN_SPEED_SCALE, MAX_SPEED_SCALE)
    }

    /**
     * Maps an SI speed in m/s to the nearest 20-point speed unit (`0.5..10.0` in `0.5` steps).
     */
    fun unitsForSpeed(speedMps: Double): Double {
        val stepMps = (speedScaleMaxMps - speedScaleMinMps) / (MAX_SPEED_SCALE - MIN_SPEED_SCALE).toDouble()
        val rawUnits = 1.0 + (speedMps - speedScaleMinMps) / stepMps.coerceAtLeast(1e-6)
        return snapSpeedUnits(rawUnits)
    }

    companion object {
        const val MIN_SPEED_SCALE: Int = 1
        const val MAX_SPEED_SCALE: Int = 10
        const val MIN_SPEED_UNITS: Double = 0.5
        const val MAX_SPEED_UNITS: Double = 10.0
        const val SPEED_UNIT_STEP: Double = 0.5
        const val DEFAULT_AIM_ERROR_STD_RAD: Double = 0.015

        /** All 20 player-selectable speed points (`0.5, 1.0, 1.5, ..., 10.0`). */
        val TWENTY_POINT_SPEED_UNITS: List<Double> = (1..20).map { it * SPEED_UNIT_STEP }

        /** Snaps [units] to the nearest `0.5` increment inside `0.5..10.0`. */
        fun snapSpeedUnits(units: Double): Double {
            if (!units.isFinite()) return 5.0
            val halfSteps = (units / SPEED_UNIT_STEP).roundToInt().coerceIn(1, 20)
            return halfSteps * SPEED_UNIT_STEP
        }

        /** Formats speed units cleanly without trailing `.0` on whole numbers (e.g. `2.5` -> `"2.5"`, `5.0` -> `"5"`). */
        fun formatSpeedUnits(units: Double): String {
            val snapped = snapSpeedUnits(units)
            val tenths = (snapped * 10.0).roundToInt()
            return if (tenths % 10 == 0) {
                "${tenths / 10}"
            } else {
                "${tenths / 10}.${tenths % 10}"
            }
        }

        /**
         * Derives calibrated stroke and execution-error parameters from the quick-start questionnaire.
         */
        fun fromQuestionnaire(
            experience: ExperienceLevel,
            shaft: ShaftType,
            speedControlRating: Int,
            spinRating: Int,
        ): PlayerProfile {
            val clampedSpeed = speedControlRating.coerceIn(1, 5)
            val clampedSpin = spinRating.coerceIn(1, 5)

            val (minBase, maxBase, aimBase, drawOffset) = when (experience) {
                ExperienceLevel.BEGINNER -> Quad(0.80, 3.60, 0.028, -0.10)
                ExperienceLevel.INTERMEDIATE -> Quad(0.65, 4.10, 0.015, 0.0)
                ExperienceLevel.ADVANCED -> Quad(0.50, 4.60, 0.008, 0.15)
            }

            val speedMin = (minBase - (clampedSpeed - 3) * 0.05).coerceAtLeast(0.35)
            val speedMax = (maxBase + (clampedSpeed - 3) * 0.20).coerceAtLeast(speedMin + 1.0)

            val baseDrawByRating = when (clampedSpin) {
                1 -> 0.50
                2 -> 0.75
                3 -> 1.15
                4 -> 1.50
                else -> 1.80
            }
            val maxDrawRatio = (baseDrawByRating + drawOffset).coerceAtLeast(0.20)

            val shaftFactor = if (shaft == ShaftType.LOW_DEFLECTION) 0.88 else 1.0
            val aimError = aimBase * shaftFactor

            return PlayerProfile(
                experienceLevel = experience,
                shaftType = shaft,
                speedScaleMinMps = speedMin,
                speedScaleMaxMps = speedMax,
                maxEffectiveDrawRatio = maxDrawRatio,
                aimErrorStdRad = aimError,
            )
        }
    }

    private data class Quad(
        val minBase: Double,
        val maxBase: Double,
        val aimBase: Double,
        val drawOffset: Double,
    )
}
