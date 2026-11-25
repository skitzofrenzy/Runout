package com.runout.engine.planner

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PocketId
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.CueBallTrajectory
import com.runout.engine.physics.GhostBallAiming
import com.runout.engine.physics.PocketGeometry

/** Why an object ball is hard to run out. */
enum class ProblemBallReason {
    NO_CLEAR_POCKET,
    CLUSTERED_NEIGHBOURS,
    FROZEN_TO_RAIL,
}

/**
 * A problem ball: where it is, why it is a problem, which balls are in the way, how many pockets still have a
 * clear path to it and which open ball could be shot first as insurance.
 */
data class ProblemBallReport(
    val ballNumber: Int,
    val positionMeters: Vec2,
    val reasons: List<ProblemBallReason>,
    val blockingBallNumbers: List<Int>,
    val availablePocketCount: Int,
    val recommendedInsuranceBallNumber: Int?,
)

/**
 * A cue-ball path that passes close enough to a problem ball to free it while a different ball is potted.
 */
data class BreakoutOpportunity(
    val problemBallNumber: Int,
    val triggerShotTargetBallNumber: Int,
    val triggerPocketId: PocketId,
    val closestApproachMeters: Double,
    val insuranceBallNumber: Int?,
)

/**
 * Detects problem balls (no clear pocket, clustered neighbours, frozen to a rail), picks an open insurance ball
 * and finds cue-ball paths that break a problem ball out.
 */
object ProblemBallAnalyzer {
    private const val NEIGHBOUR_RADIUS_FACTOR: Double = 2.8
    private const val RAIL_TOLERANCE_METERS: Double = 0.003
    private const val BREAKOUT_RADIUS_FACTOR: Double = 2.3
    private const val INSURANCE_MIN_POCKETS: Int = 2

    fun analyzeProblemBalls(
        layout: TableLayout,
        rulesState: GameRulesState = GameRulesState(),
    ): List<ProblemBallReport> {
        val table = layout.table
        val objectBalls = layout.objectBalls().sortedBy { it.number }
        val inspected = relevantBalls(objectBalls, rulesState)
        if (inspected.isEmpty()) return emptyList()

        data class Assessment(
            val ball: Ball,
            val pocketCount: Int,
            val neighbours: List<Int>,
        )

        val assessments = inspected.map { ball ->
            val obstacles = objectBalls.filter { it.number != ball.number }
            // A pocket counts when the ball can pass between its points (at the slow-ball clearance) and its line to the
            // pocket's aim point is free of other balls.
            val pocketCount = table.pockets.count { pocket ->
                PocketGeometry.entryWindow(ball.position, pocket, ball.radiusMeters, pocket.jawToleranceSlow).isOpen &&
                    GhostBallAiming.isSegmentClear(
                        start = ball.position,
                        end = PocketGeometry.aimPoint(ball.position, pocket, ball.radiusMeters),
                        movingRadius = ball.radiusMeters,
                        obstacles = obstacles,
                    )
            }
            val neighbourLimit = NEIGHBOUR_RADIUS_FACTOR * ball.radiusMeters
            val neighbours = obstacles
                .filter { it.position.distanceTo(ball.position) <= neighbourLimit }
                .map { it.number }
            Assessment(ball, pocketCount, neighbours)
        }

        val reports = mutableListOf<ProblemBallReport>()
        for (assessment in assessments) {
            val ball = assessment.ball
            val reasons = mutableListOf<ProblemBallReason>()
            if (assessment.pocketCount == 0) reasons += ProblemBallReason.NO_CLEAR_POCKET
            if (assessment.neighbours.isNotEmpty()) reasons += ProblemBallReason.CLUSTERED_NEIGHBOURS

            val r = ball.radiusMeters
            val nearRail = ball.position.x <= r + RAIL_TOLERANCE_METERS ||
                ball.position.x >= table.lengthMeters - r - RAIL_TOLERANCE_METERS ||
                ball.position.y <= r + RAIL_TOLERANCE_METERS ||
                ball.position.y >= table.widthMeters - r - RAIL_TOLERANCE_METERS
            if (nearRail && assessment.pocketCount <= 1) reasons += ProblemBallReason.FROZEN_TO_RAIL

            if (reasons.isEmpty()) continue

            val insurance = assessments
                .filter {
                    it.ball.number != ball.number &&
                        it.pocketCount >= INSURANCE_MIN_POCKETS &&
                        it.neighbours.isEmpty()
                }
                .sortedWith(
                    compareByDescending<Assessment> { it.pocketCount }
                        .thenBy { it.ball.position.distanceTo(ball.position) }
                        .thenBy { it.ball.number },
                )
                .firstOrNull()
                ?.ball
                ?.number

            reports += ProblemBallReport(
                ballNumber = ball.number,
                positionMeters = ball.position,
                reasons = reasons,
                blockingBallNumbers = assessment.neighbours.sorted(),
                availablePocketCount = assessment.pocketCount,
                recommendedInsuranceBallNumber = insurance,
            )
        }
        return reports.sortedBy { it.ballNumber }
    }

    /**
     * Returns the closest [BreakoutOpportunity] if the cue-ball [trajectory] passes within `2.3` ball radii of a
     * problem ball other than the ball being potted, otherwise `null`. The path is read from its sampled curve
     * ([CueBallTrajectory.sampledPoints]), so a curving cue ball is measured where it really runs.
     */
    fun checkTrajectoryBreakout(
        trajectory: CueBallTrajectory,
        shotTargetBallNumber: Int,
        shotPocketId: PocketId,
        problemBalls: List<ProblemBallReport>,
        ballRadiusMeters: Double,
    ): BreakoutOpportunity? {
        val points = trajectory.sampledPoints.ifEmpty { trajectory.points }
        if (points.isEmpty()) return null
        val threshold = BREAKOUT_RADIUS_FACTOR * ballRadiusMeters

        var best: BreakoutOpportunity? = null
        for (problem in problemBalls) {
            if (problem.ballNumber == shotTargetBallNumber) continue
            var closest = Double.MAX_VALUE
            if (points.size == 1) {
                closest = points[0].distanceTo(problem.positionMeters)
            } else {
                for (i in 0 until points.size - 1) {
                    val d = distanceToSegment(problem.positionMeters, points[i], points[i + 1])
                    if (d < closest) closest = d
                }
            }
            if (closest > threshold) continue
            val current = best
            if (current == null || closest < current.closestApproachMeters) {
                best = BreakoutOpportunity(
                    problemBallNumber = problem.ballNumber,
                    triggerShotTargetBallNumber = shotTargetBallNumber,
                    triggerPocketId = shotPocketId,
                    closestApproachMeters = closest,
                    insuranceBallNumber = problem.recommendedInsuranceBallNumber,
                )
            }
        }
        return best
    }

    private fun relevantBalls(objectBalls: List<Ball>, rulesState: GameRulesState): List<Ball> {
        if (rulesState.gameType != GameType.EIGHT_BALL) return objectBalls
        return when (rulesState.eightBallGroup) {
            EightBallGroup.UNSET -> emptyList()
            EightBallGroup.SOLIDS -> objectBalls.filter { it.isSolid || it.isEightBall }
            EightBallGroup.STRIPES -> objectBalls.filter { it.isStripe || it.isEightBall }
            EightBallGroup.EIGHT_BALL -> objectBalls.filter { it.isEightBall }
            EightBallGroup.OPEN -> objectBalls
        }
    }

    private fun distanceToSegment(point: Vec2, a: Vec2, b: Vec2): Double {
        val ab = b - a
        val lengthSquared = ab.lengthSquared()
        val t = if (lengthSquared < 1e-12) 0.0 else ((point - a).dot(ab) / lengthSquared).coerceIn(0.0, 1.0)
        return point.distanceTo(a + ab * t)
    }
}
