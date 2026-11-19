package com.runout.engine.calibration

import com.runout.engine.math.Vec2
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.MotionPhysics
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Paired 0-degree and 180-degree rotate-in-place phone tilt readings at cell ([gridCol], [gridRow])
 * in radians. Cancelling `(reading0 - reading180) * 0.5` eliminates sensor zero-bias and camera-bump tilt.
 */
data class PairedTiltReading(
    val gridCol: Int,
    val gridRow: Int,
    val readingZeroDegPitchRad: Double,
    val readingZeroDegRollRad: Double,
    val reading180DegPitchRad: Double,
    val reading180DegRollRad: Double,
)

/**
 * Constant-slope cell across the table grid in SI units (metres, radians).
 */
data class SlopeCell(
    val col: Int,
    val row: Int,
    val centerMeters: Vec2,
    val slopeXRad: Double,
    val slopeYRad: Double,
)

/**
 * Coarse 2D grid of table slope measurements (`slopeXRad` along length, `slopeYRad` along width).
 */
data class TableSlopeMap(
    val cols: Int = 3,
    val rows: Int = 3,
    val cells: List<SlopeCell>,
) {
    val maxSlopeMagnitudeRad: Double =
        cells.maxOfOrNull { hypot(it.slopeXRad, it.slopeYRad) } ?: 0.0

    val isNoticeablyUnlevel: Boolean =
        maxSlopeMagnitudeRad >= TableLevelCalibration.UNLEVEL_WARNING_THRESHOLD_RAD

    /**
     * Looks up `(slopeXRad, slopeYRad)` at [positionMeters] on [table].
     */
    fun slopeAt(positionMeters: Vec2, table: TableSpec): Pair<Double, Double> {
        if (cells.isEmpty()) return 0.0 to 0.0
        val safeCols = cols.coerceAtLeast(1)
        val safeRows = rows.coerceAtLeast(1)
        val targetCol = ((positionMeters.x / table.lengthMeters.coerceAtLeast(1e-6)) * safeCols)
            .toInt()
            .coerceIn(0, safeCols - 1)
        val targetRow = ((positionMeters.y / table.widthMeters.coerceAtLeast(1e-6)) * safeRows)
            .toInt()
            .coerceIn(0, safeRows - 1)

        val matched = cells.firstOrNull { it.col == targetCol && it.row == targetRow }
            ?: cells.minByOrNull { it.centerMeters.distanceSquaredTo(positionMeters) }
        return if (matched != null) {
            matched.slopeXRad to matched.slopeYRad
        } else {
            0.0 to 0.0
        }
    }
}

/**
 * Persisted calibrated table profile combining geometry, fitted cloth/cushion/throw parameters,
 * and level map.
 */
data class SavedTableProfile(
    val id: String,
    val profileName: String,
    val baseTableSpec: TableSpec,
    val calibratedParams: CalibratedTableParams,
    val slopeMap: TableSlopeMap,
    val calibratedDateIso: String,
    val applySlopeInPhysics: Boolean = false,
) {
    fun toEffectiveTableSpec(): TableSpec =
        baseTableSpec.copy(cloth = calibratedParams.cloth, name = profileName)
}

/**
 * 180-degree paired tilt bias cancellation, slow-roll lateral drift slope estimation,
 * and slope-adjusted rolling stopping prediction in SI units.
 */
object TableLevelCalibration {
    /** 0.1 degrees in radians (`~1.745 mm/m`). */
    const val UNLEVEL_WARNING_THRESHOLD_RAD: Double = 0.0017453292519943296

    /**
     * Cancels symmetric chassis/camera-bump and sensor zero-offset bias by taking
     * half the difference between 0-degree and 180-degree rotate-in-place readings.
     */
    fun cancelSensorBias180(reading0Rad: Double, reading180Rad: Double): Double =
        (reading0Rad - reading180Rad) * 0.5

    /**
     * Builds a bias-cancelled [TableSlopeMap] across the grid from [readings] on [table].
     */
    fun buildSlopeMapFromPairedReadings(
        readings: List<PairedTiltReading>,
        table: TableSpec,
    ): TableSlopeMap {
        if (readings.isEmpty()) {
            return TableSlopeMap(cols = 3, rows = 3, cells = emptyList())
        }
        val cols = ((readings.maxOfOrNull { it.gridCol } ?: 0) + 1).coerceAtLeast(3)
        val rows = ((readings.maxOfOrNull { it.gridRow } ?: 0) + 1).coerceAtLeast(3)

        val grouped = readings.groupBy {
            it.gridCol.coerceAtLeast(0) to it.gridRow.coerceAtLeast(0)
        }
        val cells = grouped.entries.map { (coord, group) ->
            val (col, row) = coord
            var sumX = 0.0
            var sumY = 0.0
            for (r in group) {
                sumX += cancelSensorBias180(r.readingZeroDegPitchRad, r.reading180DegPitchRad)
                sumY += cancelSensorBias180(r.readingZeroDegRollRad, r.reading180DegRollRad)
            }
            val count = group.size.toDouble()
            SlopeCell(
                col = col,
                row = row,
                centerMeters = Vec2(
                    x = ((col + 0.5) / cols) * table.lengthMeters,
                    y = ((row + 0.5) / rows) * table.widthMeters,
                ),
                slopeXRad = sumX / count,
                slopeYRad = sumY / count,
            )
        }

        return TableSlopeMap(
            cols = cols,
            rows = rows,
            cells = cells,
        )
    }

    /**
     * Recovers fine table slope angle in radians from a slow-roll test of length [rollDistanceMeters]
     * and sideways drift [lateralDriftMeters] on cloth with rolling resistance [muR].
     */
    fun estimateSlopeFromSlowRoll(
        rollDistanceMeters: Double,
        lateralDriftMeters: Double,
        muR: Double,
    ): Double {
        require(rollDistanceMeters > 0.05 && muR > 0.0) {
            "rollDistanceMeters (> 0.05) and muR (> 0.0) must be positive."
        }
        val sinTheta = ((7.0 * muR * lateralDriftMeters) / (5.0 * rollDistanceMeters))
            .coerceIn(-0.15, 0.15)
        return asin(sinTheta)
    }

    /**
     * Predicts the final resting position on [table] for a rolling ball launched from [startMeters]
     * along [direction] at [initialRollingSpeedMps] under rolling resistance [muR] and table slope
     * angles ([slopeXRad], [slopeYRad]), clamped within playable cushion bounds.
     */
    fun predictSlowRollWithSlope(
        startMeters: Vec2,
        direction: Vec2,
        initialRollingSpeedMps: Double,
        muR: Double,
        slopeXRad: Double,
        slopeYRad: Double,
        table: TableSpec,
    ): Vec2 {
        require(muR > 0.0) { "Rolling resistance muR must be positive." }
        val speed = initialRollingSpeedMps.coerceAtLeast(0.0)
        val g = MotionPhysics.GRAVITY_M_S2
        val aRoll = muR * g
        val tStop = speed / aRoll.coerceAtLeast(1e-6)
        val d = MotionPhysics.rollingStoppingDistanceMeters(speed, muR, g)

        val axSlope = (5.0 / 7.0) * g * sin(slopeXRad)
        val aySlope = (5.0 / 7.0) * g * sin(slopeYRad)
        val slopeDisplacement = Vec2(
            x = 0.5 * axSlope * tStop * tStop,
            y = 0.5 * aySlope * tStop * tStop,
        )
        val end = startMeters + direction.normalized() * d + slopeDisplacement

        val r = table.ballRadiusMeters
        val maxX = (table.lengthMeters - r).coerceAtLeast(r)
        val maxY = (table.widthMeters - r).coerceAtLeast(r)
        return Vec2(
            x = end.x.coerceIn(r, maxX),
            y = end.y.coerceIn(r, maxY),
        )
    }
}
