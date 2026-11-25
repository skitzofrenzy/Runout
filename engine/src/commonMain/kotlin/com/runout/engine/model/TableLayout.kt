package com.runout.engine.model

import com.runout.engine.math.Vec2

/**
 * Snapshot of a table and the active balls on its playing surface.
 */
data class TableLayout(
    val table: TableSpec,
    val balls: List<Ball>,
) {
    fun cueBall(): Ball? = balls.firstOrNull { it.isCueBall }

    fun objectBalls(): List<Ball> = balls.filter { !it.isCueBall }

    fun ballByNumber(n: Int): Ball? = balls.firstOrNull { it.number == n }

    /**
     * Returns true if any pair of balls overlap on the table surface.
     */
    fun hasOverlappingBalls(toleranceMeters: Double = 1e-6): Boolean {
        for (i in balls.indices) {
            for (j in i + 1 until balls.size) {
                val a = balls[i]
                val b = balls[j]
                val minCenterDist = a.radiusMeters + b.radiusMeters - toleranceMeters
                if (a.position.distanceTo(b.position) < minCenterDist) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Returns a copy of this layout with ball [number] moved to [newPosition].
     */
    fun withBallPosition(number: Int, newPosition: Vec2): TableLayout = copy(
        balls = balls.map { ball ->
            if (ball.number == number) ball.copy(position = newPosition) else ball
        },
    )
}
