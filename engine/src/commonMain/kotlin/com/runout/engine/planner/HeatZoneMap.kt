package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.GhostBallAiming
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp

/**
 * One cell of a [HeatZoneMap]: grid index, cell centre in table metres and a position value in `0.0..1.0`
 * (higher means that leaving the cue ball here gives an easier next shot).
 */
data class HeatZoneCell(
    val col: Int,
    val row: Int,
    val centerMeters: Vec2,
    val value: Double,
)

/**
 * Spatial position-value grid over the playing surface for the next one to three target balls.
 *
 * The grid spans `cols x rows` equal cells over `0..lengthMeters` by `0..widthMeters`; [cells] are stored
 * row-major (`index = row * cols + col`).
 */
class HeatZoneMap(
    val table: TableSpec,
    val targetBallNumbers: List<Int>,
    val cols: Int,
    val rows: Int,
    val cells: List<HeatZoneCell>,
) {
    /**
     * Constant-time lookup of the value of the cell containing [positionMeters]. Positions outside the table
     * are clamped to the nearest edge cell; an empty or inconsistent grid returns `0.0`.
     */
    fun valueAt(positionMeters: Vec2): Double {
        if (cols <= 0 || rows <= 0) return 0.0
        val colFraction = positionMeters.x / table.lengthMeters.coerceAtLeast(1e-9)
        val rowFraction = positionMeters.y / table.widthMeters.coerceAtLeast(1e-9)
        val col = (colFraction * cols).toInt().coerceIn(0, cols - 1)
        val row = (rowFraction * rows).toInt().coerceIn(0, rows - 1)
        return cells.getOrNull(row * cols + col)?.value ?: 0.0
    }

    /** The cell with the highest position value, or `null` for an empty grid. */
    fun bestLeaveCell(): HeatZoneCell? = cells.maxByOrNull { it.value }
}

/**
 * Builds [HeatZoneMap]s: for every grid cell, how good is the best direct shot at the upcoming target ball(s)
 * if the cue ball rests at that cell.
 */
object HeatZoneGenerator {
    private const val OVERLAP_FACTOR: Double = 2.05
    private const val SECONDARY_TARGET_WEIGHT: Double = 0.25
    private const val POCKET_WINDOW_FRACTION: Double = 0.8
    private const val MIN_CUT_COSINE: Double = 0.22
    private const val ERROR_DISTANCE_GAIN: Double = 0.18

    fun computeForNextBalls(
        layout: TableLayout,
        targetBallNumbers: List<Int>,
        playerProfile: PlayerProfile = PlayerProfile(),
        cols: Int = 16,
        rows: Int = 8,
    ): HeatZoneMap {
        val table = layout.table
        val safeCols = cols.coerceAtLeast(1)
        val safeRows = rows.coerceAtLeast(1)
        val cueRadius = layout.cueBall()?.radiusMeters ?: table.ballRadiusMeters
        val objectBalls = layout.balls.filter { !it.isCueBall }
        val primary = targetBallNumbers.firstOrNull()?.let { layout.ballByNumber(it) }
        val secondary = targetBallNumbers.getOrNull(1)?.let { layout.ballByNumber(it) }

        val cellWidth = table.lengthMeters / safeCols
        val cellHeight = table.widthMeters / safeRows
        val cells = ArrayList<HeatZoneCell>(safeCols * safeRows)

        for (row in 0 until safeRows) {
            for (col in 0 until safeCols) {
                val center = Vec2((col + 0.5) * cellWidth, (row + 0.5) * cellHeight)
                val value = cellValue(
                    center = center,
                    table = table,
                    cueRadius = cueRadius,
                    objectBalls = objectBalls,
                    primary = primary,
                    secondary = secondary,
                    aimErrorStdRad = playerProfile.aimErrorStdRad,
                )
                cells += HeatZoneCell(col = col, row = row, centerMeters = center, value = value)
            }
        }

        return HeatZoneMap(
            table = table,
            targetBallNumbers = targetBallNumbers,
            cols = safeCols,
            rows = safeRows,
            cells = cells,
        )
    }

    private fun cellValue(
        center: Vec2,
        table: TableSpec,
        cueRadius: Double,
        objectBalls: List<Ball>,
        primary: Ball?,
        secondary: Ball?,
        aimErrorStdRad: Double,
    ): Double {
        if (primary == null) return 0.0
        if (!table.isWithinPlayableBounds(center, cueRadius)) return 0.0
        val overlaps = objectBalls.any {
            it.position.distanceTo(center) < OVERLAP_FACTOR * maxOf(it.radiusMeters, cueRadius)
        }
        if (overlaps) return 0.0

        val primaryValue = bestShotValue(center, cueRadius, primary, table, objectBalls, aimErrorStdRad)
        if (secondary == null) return primaryValue.coerceIn(0.0, 1.0)
        val secondaryValue = bestShotValue(center, cueRadius, secondary, table, objectBalls, aimErrorStdRad)
        return ((1.0 - SECONDARY_TARGET_WEIGHT) * primaryValue + SECONDARY_TARGET_WEIGHT * secondaryValue)
            .coerceIn(0.0, 1.0)
    }

    /** Best make-quality over all unobstructed pockets for a cue ball at [cuePosition] shooting [target]. */
    private fun bestShotValue(
        cuePosition: Vec2,
        cueRadius: Double,
        target: Ball,
        table: TableSpec,
        objectBalls: List<Ball>,
        aimErrorStdRad: Double,
    ): Double {
        val cue = Ball(number = 0, position = cuePosition, radiusMeters = cueRadius)
        var best = 0.0
        for (pocket in table.pockets) {
            val aim = GhostBallAiming.computeAimGeometry(
                cueBall = cue,
                objectBall = target,
                pocket = pocket,
                otherBalls = objectBalls,
                table = table,
            )
            if (!aim.isShotPlayable) continue
            val quality = shotQuality(aim, pocket, aimErrorStdRad)
            if (quality > best) best = quality
        }
        return best
    }

    /**
     * Estimated probability in `0.0..1.0` that a shot with geometry [aim] pots into [pocket] for a player with
     * aim-error standard deviation [aimErrorStdRad]: the object-ball deflection caused by aim error (growing
     * with cue distance and sharper cuts) is compared with the angular size of the pocket window.
     */
    internal fun shotQuality(aim: AimGeometry, pocket: PocketSpec, aimErrorStdRad: Double): Double {
        val cutCosine = cos(abs(aim.cutAngleRadians)).coerceAtLeast(MIN_CUT_COSINE)
        val halfWindow = atan2(
            pocket.acceptanceRadiusMeters * POCKET_WINDOW_FRACTION,
            aim.objectToPocketDistanceMeters.coerceAtLeast(0.05),
        ).coerceAtLeast(0.005)
        val lever = 1.0 + ERROR_DISTANCE_GAIN * aim.cueToGhostDistanceMeters / 0.0572
        val sigmaObject = aimErrorStdRad.coerceAtLeast(0.003) * lever / cutCosine
        val ratio = sigmaObject / halfWindow
        return exp(-0.5 * ratio * ratio).coerceIn(0.0, 1.0)
    }
}
