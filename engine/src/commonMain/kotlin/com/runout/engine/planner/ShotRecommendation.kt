package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.TrajectoryPath

/**
 * A single evaluated and ranked pot attempt produced by [OneShotPlanner].
 */
data class ShotRecommendation(
    val targetBall: Ball,
    val pocket: PocketSpec,
    val aim: AimGeometry,
    val cueBallPaths: Map<CueBallSpinPreset, TrajectoryPath>,
    val recommendedSpin: CueBallSpinPreset,
    val recommendedCueSpeedMps: Double,
    val score: Double,
    val recommendedSpeedScale: Int = 5,
    val nextTargetBallNumber: Int? = null,
    val nextTargetPositionLine: Pair<Vec2, Vec2>? = null,
)
