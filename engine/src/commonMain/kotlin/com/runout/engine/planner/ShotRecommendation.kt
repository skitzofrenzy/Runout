package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.PocketSpec
import com.runout.engine.physics.AimGeometry
import com.runout.engine.physics.CueBallSpinPreset
import com.runout.engine.physics.TrajectoryPath

/**
 * A single evaluated and ranked pot attempt produced by [OneShotPlanner].
 *
 * [score], in `(0, 1]`, ranks the pot: its make probability times factors for the leave (see [OneShotPlanner]); it is
 * not a probability. [makeProbability] is the make probability of the recommended stroke
 * ([ExecutionErrorModel.makeProbability]); it defaults to [score] for callers that build a recommendation themselves.
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
    val recommendedSpeedUnits: Double = recommendedSpeedScale.toDouble(),
    val nextTargetBallNumber: Int? = null,
    val nextTargetPositionLine: Pair<Vec2, Vec2>? = null,
    val makeProbability: Double = score,
)
