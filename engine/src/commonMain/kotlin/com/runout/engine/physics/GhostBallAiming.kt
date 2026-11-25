package com.runout.engine.physics

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableSpec
import kotlin.math.PI
import kotlin.math.abs

/**
 * Ghost-ball aiming geometry and corridor clearance results for a direct pot attempt.
 */
data class AimGeometry(
    val cueBallPos: Vec2,
    val objectBallPos: Vec2,
    val pocketAimPos: Vec2,
    val ghostBallCenter: Vec2,
    val aimDirection: Vec2,
    val objectBallDirection: Vec2,
    val cueToGhostDistanceMeters: Double,
    val objectToPocketDistanceMeters: Double,
    val cutAngleRadians: Double,
    val isCutPossible: Boolean,
    val isCueToGhostClear: Boolean,
    val isObjectToPocketClear: Boolean,
) {
    val isShotPlayable: Boolean
        get() = isCutPossible && isCueToGhostClear && isObjectToPocketClear
}

object GhostBallAiming {
    const val DEFAULT_MAX_CUT_ANGLE_RAD: Double = PI * 80.0 / 180.0
    const val DEFAULT_CLEARANCE_MARGIN_METERS: Double = 0.0005

    /**
     * Computes ghost-ball aiming geometry for potting [objectBall] into [pocket] using [cueBall],
     * checking whether any ball in [otherBalls] obstructs the cue-to-ghost or object-to-pocket path.
     *
     * The object ball is sent at [PocketGeometry.aimPoint], the middle of its entry window into the pocket at a clean
     * clearance of one ball radius from each jaw tip (or, if that window is closed, at the pocket's slow-ball
     * clearance). [AimGeometry.isCutPossible] needs that window to be open at the slow-ball clearance: a ball that
     * cannot pass between the points even when it arrives slowly is not a pot.
     *
     * When [table] is provided, [AimGeometry.isCutPossible] (and therefore [AimGeometry.isShotPlayable])
     * also requires [AimGeometry.ghostBallCenter] to lie within the playable cushion bounds for
     * [cueBall]'s radius so rail-frozen shots requiring an off-table cue ball are rejected.
     */
    fun computeAimGeometry(
        cueBall: Ball,
        objectBall: Ball,
        pocket: PocketSpec,
        otherBalls: List<Ball> = emptyList(),
        maxCutAngleRad: Double = DEFAULT_MAX_CUT_ANGLE_RAD,
        table: TableSpec? = null,
    ): AimGeometry {
        val pocketAimPos = PocketGeometry.aimPoint(objectBall.position, pocket, objectBall.radiusMeters)
        val windowOpen = PocketGeometry.entryWindow(
            objectBall.position, pocket, objectBall.radiusMeters, pocket.jawToleranceSlow,
        ).isOpen
        return computeAimGeometryToPoint(cueBall, objectBall, pocketAimPos, otherBalls, maxCutAngleRad, table, windowOpen)
    }

    /**
     * Ghost-ball aiming geometry for sending [objectBall] at [targetPoint] (a pocket's aim point, or any point when
     * [targetReachable] is true), with the same cut-angle, on-table and clearance checks as [computeAimGeometry].
     */
    fun computeAimGeometryToPoint(
        cueBall: Ball,
        objectBall: Ball,
        targetPoint: Vec2,
        otherBalls: List<Ball> = emptyList(),
        maxCutAngleRad: Double = DEFAULT_MAX_CUT_ANGLE_RAD,
        table: TableSpec? = null,
        targetReachable: Boolean = true,
    ): AimGeometry {
        val pocketAimPos = targetPoint
        val objectToPocketVec = pocketAimPos - objectBall.position
        val objectToPocketDistance = objectToPocketVec.length()
        val objectBallDirection = objectToPocketVec.normalized()

        val contactDistance = cueBall.radiusMeters + objectBall.radiusMeters
        val ghostBallCenter = objectBall.position - objectBallDirection * contactDistance

        val cueToGhostVec = ghostBallCenter - cueBall.position
        val cueToGhostDistance = cueToGhostVec.length()
        val aimDirection = cueToGhostVec.normalized()

        val cutAngleRadians = aimDirection.angleTo(objectBallDirection)
        val isGhostOnTable = table?.isWithinPlayableBounds(ghostBallCenter, cueBall.radiusMeters) ?: true
        val isCutPossible = abs(cutAngleRadians) <= maxCutAngleRad &&
            cueToGhostDistance > 1e-6 &&
            objectToPocketDistance > 1e-6 &&
            isGhostOnTable &&
            targetReachable

        val obstacles = otherBalls.filter {
            it.number != cueBall.number && it.number != objectBall.number
        }

        val isCueToGhostClear = isSegmentClear(
            start = cueBall.position,
            end = ghostBallCenter,
            movingRadius = cueBall.radiusMeters,
            obstacles = obstacles,
        )

        val isObjectToPocketClear = isSegmentClear(
            start = objectBall.position,
            end = pocketAimPos,
            movingRadius = objectBall.radiusMeters,
            obstacles = obstacles,
        )

        return AimGeometry(
            cueBallPos = cueBall.position,
            objectBallPos = objectBall.position,
            pocketAimPos = pocketAimPos,
            ghostBallCenter = ghostBallCenter,
            aimDirection = aimDirection,
            objectBallDirection = objectBallDirection,
            cueToGhostDistanceMeters = cueToGhostDistance,
            objectToPocketDistanceMeters = objectToPocketDistance,
            cutAngleRadians = cutAngleRadians,
            isCutPossible = isCutPossible,
            isCueToGhostClear = isCueToGhostClear,
            isObjectToPocketClear = isObjectToPocketClear,
        )
    }

    /**
     * Checks whether a ball of radius [movingRadius] travelling along the straight segment from
     * [start] to [end] avoids overlapping any ball in [obstacles].
     */
    fun isSegmentClear(
        start: Vec2,
        end: Vec2,
        movingRadius: Double,
        obstacles: List<Ball>,
        clearanceMarginMeters: Double = DEFAULT_CLEARANCE_MARGIN_METERS,
    ): Boolean {
        val seg = end - start
        val segLenSq = seg.lengthSquared()

        for (obstacle in obstacles) {
            val t = if (segLenSq < 1e-12) {
                0.0
            } else {
                ((obstacle.position - start).dot(seg) / segLenSq).coerceIn(0.0, 1.0)
            }
            val closestPoint = start + seg * t
            val distance = obstacle.position.distanceTo(closestPoint)
            val minAllowedDistance = movingRadius + obstacle.radiusMeters - clearanceMarginMeters
            if (distance < minAllowedDistance) {
                return false
            }
        }
        return true
    }
}

fun computeAimGeometry(
    cueBall: Ball,
    objectBall: Ball,
    pocket: PocketSpec,
    otherBalls: List<Ball> = emptyList(),
    maxCutAngleRad: Double = GhostBallAiming.DEFAULT_MAX_CUT_ANGLE_RAD,
    table: TableSpec? = null,
): AimGeometry = GhostBallAiming.computeAimGeometry(
    cueBall = cueBall,
    objectBall = objectBall,
    pocket = pocket,
    otherBalls = otherBalls,
    maxCutAngleRad = maxCutAngleRad,
    table = table,
)

fun isSegmentClear(
    start: Vec2,
    end: Vec2,
    movingRadius: Double,
    obstacles: List<Ball>,
    clearanceMarginMeters: Double = GhostBallAiming.DEFAULT_CLEARANCE_MARGIN_METERS,
): Boolean = GhostBallAiming.isSegmentClear(
    start = start,
    end = end,
    movingRadius = movingRadius,
    obstacles = obstacles,
    clearanceMarginMeters = clearanceMarginMeters,
)
