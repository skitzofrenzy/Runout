package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import kotlin.math.PI

/**
 * A wide, square-on pocket whose mouth is centred on [point] and faces [axisIntoPocket], for tests that only need the
 * object ball sent at a given point: from any ball on the axis its aim point is [point] itself.
 */
internal fun targetPocket(
    point: Vec2,
    axisIntoPocket: Vec2 = Vec2(1.0, 0.0),
    mouthWidthMeters: Double = 0.20,
    id: PocketId = PocketId.FOOT_RIGHT,
): PocketSpec {
    val axis = axisIntoPocket.normalized()
    val across = axis.rotate(PI / 2.0) * (mouthWidthMeters * 0.5)
    return PocketSpec(
        id = id,
        jawTipA = point - across,
        jawTipB = point + across,
        axisIntoPocket = axis,
        facingAngleRad = PI * 0.75,
        shelfDepthMeters = 0.0,
        holeRadiusMeters = 0.06,
        jawToleranceSlow = 1.0,
        jawToleranceFast = 1.0,
    )
}
