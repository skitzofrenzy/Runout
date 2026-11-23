package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.GhostBallAiming
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spreading occlusion shadow cone cast from a ghost-ball aim point [originMeters] past a
 * blocking ball at [blockerPosition]. Any cue-ball centre inside this cone or exclusion disk
 * cannot see the ghost ball directly without hitting the blocker.
 */
data class OcclusionShadowWedge(
    val blockerBallNumber: Int,
    val blockerPosition: Vec2,
    val exclusionRadiusMeters: Double,
    val originMeters: Vec2,
    val leftTangentMeters: Vec2,
    val rightTangentMeters: Vec2,
    val leftFarMeters: Vec2,
    val rightFarMeters: Vec2,
    val midFarMeters: Vec2,
    val centerAngleRadians: Double,
    val halfAngleRadians: Double,
    val blockerDistanceMeters: Double,
)

/**
 * Visibility geometry for potting [targetBallNumber] into [pocketId] on a table layout.
 * When [isPocketClear] is false (any ball obstructs the target-to-pocket corridor or ghost ball),
 * the direct pocket lane is blocked across the entire table.
 */
data class PocketVisibilityRegion(
    val targetBallNumber: Int,
    val targetBallPosition: Vec2,
    val targetExclusionRadiusMeters: Double,
    val pocketId: PocketId,
    val ghostBallCenter: Vec2,
    val objectToPocketDirection: Vec2,
    val isPocketClear: Boolean,
    val shadowWedges: List<OcclusionShadowWedge>,
)

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
    private const val OCCLUSION_CLEARANCE_PAD_METERS: Double = 0.002
    const val MAX_HEAT_CUT_ANGLE_RAD: Double = 78.0 * PI / 180.0

    fun computeForNextBalls(
        layout: TableLayout,
        targetBallNumbers: List<Int>,
        playerProfile: PlayerProfile = PlayerProfile(),
        cols: Int = 16,
        rows: Int = 8,
        preferredPocketId: PocketId? = null,
    ): HeatZoneMap {
        val table = layout.table
        val safeCols = cols.coerceAtLeast(1)
        val safeRows = rows.coerceAtLeast(1)
        val cueRadius = layout.cueBall()?.radiusMeters ?: table.ballRadiusMeters
        val objectBalls = layout.balls.filter { !it.isCueBall }
        val primary = targetBallNumbers.firstOrNull()?.let { layout.ballByNumber(it) }
        val secondary = targetBallNumbers.getOrNull(1)?.let { layout.ballByNumber(it) }

        val primaryVisibilities = if (primary != null) {
            val pockets = if (preferredPocketId != null) {
                table.pockets.filter { it.id == preferredPocketId }
            } else {
                table.pockets
            }
            pockets.map { pocket ->
                pocket to computePocketVisibility(layout, primary, pocket, cueRadius)
            }
        } else {
            emptyList()
        }
        val secondaryVisibilities = if (secondary != null) {
            table.pockets.map { pocket ->
                pocket to computePocketVisibility(layout, secondary, pocket, cueRadius)
            }
        } else {
            emptyList()
        }

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
                    primaryVisibilities = primaryVisibilities,
                    secondary = secondary,
                    secondaryVisibilities = secondaryVisibilities,
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

    /**
     * Computes exact pocket visibility and spreading blocking-ball shadow wedges for shooting
     * [targetBall] into [pocket]. Every other object ball on [layout] — both non-playable and
     * friendly — casts a spreading shadow wedge if it blocks the approach, or invalidates the
     * entire pocket if it obstructs [targetBall] -> [pocket] or the ghost ball.
     */
    fun computePocketVisibility(
        layout: TableLayout,
        targetBall: Ball,
        pocket: PocketSpec,
        cueRadius: Double = layout.table.ballRadiusMeters,
    ): PocketVisibilityRegion {
        val table = layout.table
        val objToPocket = (pocket.center - targetBall.position).normalized()
        val contactDist = cueRadius + targetBall.radiusMeters
        val ghostCenter = targetBall.position - objToPocket * contactDist
        val obstacles = layout.balls.filter { !it.isCueBall && it.number != targetBall.number }

        val pocketPathClear = GhostBallAiming.isSegmentClear(
            start = targetBall.position,
            end = pocket.center,
            movingRadius = targetBall.radiusMeters,
            obstacles = obstacles,
            clearanceMarginMeters = -OCCLUSION_CLEARANCE_PAD_METERS,
        )
        val ghostOnTable = table.isWithinPlayableBounds(ghostCenter, cueRadius)
        val ghostClear = obstacles.none {
            it.position.distanceTo(ghostCenter) < cueRadius + it.radiusMeters + OCCLUSION_CLEARANCE_PAD_METERS
        }
        val isPocketClear = pocketPathClear && ghostOnTable && ghostClear

        val farDist = max(table.lengthMeters, table.widthMeters) * 3.0
        val wedges = obstacles.map { blocker ->
            val toBlocker = blocker.position - ghostCenter
            val dist = toBlocker.length()
            val exclusionR = cueRadius + blocker.radiusMeters + OCCLUSION_CLEARANCE_PAD_METERS
            val centerAngle = atan2(toBlocker.y, toBlocker.x)
            val halfAngle = if (dist <= exclusionR + 1e-6) {
                PI * 0.495
            } else {
                asin((exclusionR / dist).coerceIn(0.0, 0.9999))
            }
            val tangentDist = if (dist <= exclusionR + 1e-6) {
                0.0
            } else {
                sqrt((dist * dist - exclusionR * exclusionR).coerceAtLeast(0.0))
            }
            val leftDir = Vec2(cos(centerAngle - halfAngle), sin(centerAngle - halfAngle))
            val rightDir = Vec2(cos(centerAngle + halfAngle), sin(centerAngle + halfAngle))
            val midDir = Vec2(cos(centerAngle), sin(centerAngle))
            OcclusionShadowWedge(
                blockerBallNumber = blocker.number,
                blockerPosition = blocker.position,
                exclusionRadiusMeters = exclusionR,
                originMeters = ghostCenter,
                leftTangentMeters = ghostCenter + leftDir * tangentDist,
                rightTangentMeters = ghostCenter + rightDir * tangentDist,
                leftFarMeters = ghostCenter + leftDir * farDist,
                rightFarMeters = ghostCenter + rightDir * farDist,
                midFarMeters = ghostCenter + midDir * farDist,
                centerAngleRadians = centerAngle,
                halfAngleRadians = halfAngle,
                blockerDistanceMeters = dist,
            )
        }

        return PocketVisibilityRegion(
            targetBallNumber = targetBall.number,
            targetBallPosition = targetBall.position,
            targetExclusionRadiusMeters = cueRadius + targetBall.radiusMeters + OCCLUSION_CLEARANCE_PAD_METERS,
            pocketId = pocket.id,
            ghostBallCenter = ghostCenter,
            objectToPocketDirection = objToPocket,
            isPocketClear = isPocketClear,
            shadowWedges = wedges,
        )
    }

    /**
     * Returns `true` if a cue ball centered at [point] is occluded behind a blocking ball, sits
     * inside a ball overlap exclusion disk, faces a blocked [region] pocket, or lies beyond the
     * cut-angle plane (`> [maxCutAngleRad]`).
     */
    fun isPointOccludedForPocket(
        point: Vec2,
        region: PocketVisibilityRegion,
        maxCutAngleRad: Double = MAX_HEAT_CUT_ANGLE_RAD,
    ): Boolean {
        if (!region.isPocketClear) return true
        if (point.distanceTo(region.targetBallPosition) < region.targetExclusionRadiusMeters) return true

        val toGhost = region.ghostBallCenter - point
        if (toGhost.lengthSquared() < 1e-10) return true
        val cutAngle = abs(toGhost.normalized().angleTo(region.objectToPocketDirection))
        if (cutAngle > maxCutAngleRad) return true

        for (wedge in region.shadowWedges) {
            if (point.distanceTo(wedge.blockerPosition) < wedge.exclusionRadiusMeters) return true
            val toPoint = point - wedge.originMeters
            val ptDist = toPoint.length()
            val tangentDist = sqrt(
                (wedge.blockerDistanceMeters * wedge.blockerDistanceMeters -
                    wedge.exclusionRadiusMeters * wedge.exclusionRadiusMeters).coerceAtLeast(0.0),
            )
            if (ptDist + 1e-6 >= tangentDist) {
                val ptAngle = atan2(toPoint.y, toPoint.x)
                val delta = ptAngle - wedge.centerAngleRadians
                val wrapped = abs(atan2(sin(delta), cos(delta)))
                if (wrapped <= wedge.halfAngleRadians + 1e-6) {
                    return true
                }
            }
        }
        return false
    }

    private fun cellValue(
        center: Vec2,
        table: TableSpec,
        cueRadius: Double,
        objectBalls: List<Ball>,
        primary: Ball?,
        primaryVisibilities: List<Pair<PocketSpec, PocketVisibilityRegion>>,
        secondary: Ball?,
        secondaryVisibilities: List<Pair<PocketSpec, PocketVisibilityRegion>>,
        aimErrorStdRad: Double,
    ): Double {
        if (primary == null) return 0.0
        if (!table.isWithinPlayableBounds(center, cueRadius)) return 0.0
        val overlaps = objectBalls.any {
            it.position.distanceTo(center) < OVERLAP_FACTOR * maxOf(it.radiusMeters, cueRadius)
        }
        if (overlaps) return 0.0

        val primaryValue = bestShotValue(center, cueRadius, primary, table, objectBalls, primaryVisibilities, aimErrorStdRad)
        if (secondary == null) return primaryValue.coerceIn(0.0, 1.0)
        val secondaryValue = bestShotValue(center, cueRadius, secondary, table, objectBalls, secondaryVisibilities, aimErrorStdRad)
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
        visibilities: List<Pair<PocketSpec, PocketVisibilityRegion>>,
        aimErrorStdRad: Double,
    ): Double {
        val cue = Ball(number = 0, position = cuePosition, radiusMeters = cueRadius)
        var best = 0.0
        for ((pocket, visibility) in visibilities) {
            if (isPointOccludedForPocket(cuePosition, visibility)) continue
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
