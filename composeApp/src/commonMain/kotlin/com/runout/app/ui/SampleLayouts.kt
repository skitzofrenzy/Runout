package com.runout.app.ui

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A pre-configured practice layout and rule state for demonstrating shot geometry and planning.
 */
data class SampleScenario(
    val title: String,
    val description: String,
    val layout: TableLayout,
    val rulesState: GameRulesState,
)

/**
 * Sample practice scenarios and pure layout-editing operations in SI metres.
 */
object SampleLayouts {
    private const val MIN_CLEARANCE_TOLERANCE_METERS: Double = 1e-4

    /**
     * 9-Ball scenario with cue ball `0`, lowest object ball `1`, next ball `2`, and the `9` ball
     * on the foot spot, offering clear cuts on the `1` into the foot-right and side-right pockets.
     */
    fun nineBallThreeBallRunoutStart(): SampleScenario {
        val table = TableSpec.nineFootPool()
        val layout = TableLayout(
            table = table,
            balls = listOf(
                Ball(number = 0, position = Vec2(x = 0.75, y = 0.55)),
                Ball(number = 1, position = Vec2(x = 1.18, y = 0.90)),
                Ball(number = 2, position = Vec2(x = 1.98, y = 0.34)),
                Ball(number = 9, position = table.footSpot()),
            ),
        )
        return SampleScenario(
            title = "9-Ball: 3-Ball Runout Start",
            description = "Open angle on the 1-ball with natural cue-ball paths toward the 2-ball and 9-ball.",
            layout = layout,
            rulesState = GameRulesState(gameType = GameType.NINE_BALL),
        )
    }

    /**
     * 8-Ball solids scenario where solid `3` is the key ball to the `8`, while stripe `11` blocks
     * the side-left pocket corridor and leaves the foot-left corner cut open.
     */
    fun eightBallKeyBallToEight(): SampleScenario {
        val table = TableSpec.nineFootPool()
        val layout = TableLayout(
            table = table,
            balls = listOf(
                Ball(number = 0, position = Vec2(x = 1.15, y = 0.48)),
                Ball(number = 3, position = Vec2(x = 1.75, y = 0.36)),
                Ball(number = 11, position = Vec2(x = 1.51, y = 0.18)),
                Ball(number = 8, position = Vec2(x = 1.88, y = 0.92)),
            ),
        )
        return SampleScenario(
            title = "8-Ball: Key Ball to the 8",
            description = "Solids key ball (3) into the foot-left corner while stripe 11 blocks the side pocket.",
            layout = layout,
            rulesState = GameRulesState(
                gameType = GameType.EIGHT_BALL,
                eightBallGroup = EightBallGroup.SOLIDS,
            ),
        )
    }

    /**
     * 10-Ball scenario where cutting the `1` ball into the foot-left corner puts the stun
     * tangent line directly into the foot-right corner pocket, requiring follow or draw to stay safe. A real stun shot
     * leaves a few degrees inside the tangent line, so the object ball sits where that slightly inside line still
     * reaches the pocket (it also does for about 2.5 cm either side along the table length).
     */
    fun tenBallCutAndScratchAvoidance(): SampleScenario {
        val table = TableSpec.nineFootPool()
        val layout = TableLayout(
            table = table,
            balls = listOf(
                Ball(number = 0, position = Vec2(x = 1.10, y = 0.635)),
                Ball(number = 1, position = Vec2(x = 1.88, y = 0.57)),
                Ball(number = 10, position = Vec2(x = 0.72, y = 0.32)),
            ),
        )
        return SampleScenario(
            title = "10-Ball: Cut & Scratch Avoidance",
            description = "Cutting the 1-ball into Foot Left sends the stun line toward a Foot Right scratch; follow or draw avoids the pocket.",
            layout = layout,
            rulesState = GameRulesState(gameType = GameType.TEN_BALL),
        )
    }

    val all: List<SampleScenario> = listOf(
        nineBallThreeBallRunoutStart(),
        eightBallKeyBallToEight(),
        tenBallCutAndScratchAvoidance(),
    )

    /**
     * Scales all ball positions proportionally from `layout.table` to [newSpec], updates ball radii
     * for snooker vs pool tables, clamps every ball inside playable cushion bounds, and resolves any
     * resulting overlaps.
     */
    fun resizeTableLayout(layout: TableLayout, newSpec: TableSpec): TableLayout {
        val oldLength = layout.table.lengthMeters.coerceAtLeast(1e-6)
        val oldWidth = layout.table.widthMeters.coerceAtLeast(1e-6)
        val targetRadius = if (newSpec.name.contains("Snooker", ignoreCase = true)) {
            TableSpec.SNOOKER_BALL_RADIUS_METERS
        } else {
            TableSpec.POOL_BALL_RADIUS_METERS
        }
        val effectiveSpec = if (abs(newSpec.ballRadiusMeters - targetRadius) > 1e-9) {
            newSpec.copy(
                ballRadiusMeters = targetRadius,
                pockets = TableSpec.defaultPockets(
                    lengthMeters = newSpec.lengthMeters,
                    widthMeters = newSpec.widthMeters,
                    ballRadiusMeters = targetRadius,
                ),
            )
        } else {
            newSpec
        }

        val scaled = layout.balls.map { ball ->
            val scaledPos = Vec2(
                x = (ball.position.x / oldLength) * effectiveSpec.lengthMeters,
                y = (ball.position.y / oldWidth) * effectiveSpec.widthMeters,
            )
            ball.copy(
                position = clampToPlayableBounds(scaledPos, effectiveSpec, targetRadius),
                radiusMeters = targetRadius,
            )
        }.toMutableList()

        val minSeparation = 2.0 * targetRadius + MIN_CLEARANCE_TOLERANCE_METERS
        repeat(16) {
            var anyOverlap = false
            for (i in scaled.indices) {
                for (j in i + 1 until scaled.size) {
                    val a = scaled[i]
                    val b = scaled[j]
                    val delta = b.position - a.position
                    val dist = delta.length()
                    if (dist < minSeparation) {
                        anyOverlap = true
                        val dir = if (dist > 1e-9) delta / dist else Vec2.UNIT_X
                        val push = (minSeparation - dist) * 0.5
                        scaled[i] = a.copy(
                            position = clampToPlayableBounds(a.position - dir * push, effectiveSpec, targetRadius),
                        )
                        scaled[j] = b.copy(
                            position = clampToPlayableBounds(b.position + dir * push, effectiveSpec, targetRadius),
                        )
                    }
                }
            }
            if (!anyOverlap) return@repeat
        }

        val placed = mutableListOf<Ball>()
        for (ball in scaled) {
            val overlapsPlaced = placed.any { other ->
                ball.position.distanceTo(other.position) < 2.0 * targetRadius - 1e-6
            }
            val finalPos = if (overlapsPlaced) {
                findFreeSpotNear(ball.position, placed, effectiveSpec, targetRadius)
            } else {
                ball.position
            }
            placed += ball.copy(position = finalPos)
        }

        return TableLayout(table = effectiveSpec, balls = placed)
    }

    /**
     * Clamps [desiredPosition] to the playable surface `[r, length - r] x [r, width - r]` and
     * moves [ballNumber] only if the clamped position does not overlap any other ball
     * (`distance >= 2 * r - 1e-4`).
     */
    fun moveBallSafely(
        layout: TableLayout,
        ballNumber: Int,
        desiredPosition: Vec2,
    ): TableLayout {
        val targetBall = layout.ballByNumber(ballNumber) ?: return layout
        val r = targetBall.radiusMeters
        val clamped = clampToPlayableBounds(desiredPosition, layout.table, r)
        val overlapsOther = layout.balls.any { other ->
            other.number != ballNumber &&
                clamped.distanceTo(other.position) < (r + other.radiusMeters - MIN_CLEARANCE_TOLERANCE_METERS)
        }
        if (overlapsOther) return layout
        return layout.withBallPosition(ballNumber, clamped)
    }

    /**
     * Removes object ball [ballNumber] (`1..15`) if present on [layout], or adds it at a free
     * non-overlapping spot near the foot spot / table centre if absent.
     */
    fun toggleObjectBall(
        layout: TableLayout,
        ballNumber: Int,
    ): TableLayout {
        if (ballNumber !in 1..15) return layout
        if (layout.ballByNumber(ballNumber) != null) {
            return layout.copy(balls = layout.balls.filterNot { it.number == ballNumber })
        }
        return addMissingBall(layout, ballNumber)
    }

    /**
     * Adds ball [ballNumber] (`0..15`, including the cue ball `0`) at a free non-overlapping spot
     * if it is not already on [layout]. Places cue ball near the head spot and object balls near
     * the foot spot.
     */
    fun addMissingBall(
        layout: TableLayout,
        ballNumber: Int,
    ): TableLayout {
        if (ballNumber !in 0..15) return layout
        if (layout.ballByNumber(ballNumber) != null) return layout

        val table = layout.table
        val ballRadius = layout.balls.firstOrNull { !it.isCueBall }?.radiusMeters ?: table.ballRadiusMeters
        val preferred = if (ballNumber == 0) {
            Vec2(table.lengthMeters * 0.25, table.widthMeters * 0.5)
        } else {
            table.footSpot()
        }
        val freePosition = findFreeSpotNear(preferred, layout.balls, table, ballRadius)
        return layout.copy(
            balls = layout.balls + Ball(
                number = ballNumber,
                position = freePosition,
                radiusMeters = ballRadius,
            ),
        )
    }

    private fun clampToPlayableBounds(pos: Vec2, table: TableSpec, radius: Double): Vec2 {
        val minX = radius.coerceAtMost(table.lengthMeters * 0.5)
        val maxX = (table.lengthMeters - radius).coerceAtLeast(minX)
        val minY = radius.coerceAtMost(table.widthMeters * 0.5)
        val maxY = (table.widthMeters - radius).coerceAtLeast(minY)
        return Vec2(
            x = pos.x.coerceIn(minX, maxX),
            y = pos.y.coerceIn(minY, maxY),
        )
    }

    private fun findFreeSpotNear(
        preferred: Vec2,
        existingBalls: List<Ball>,
        table: TableSpec,
        radius: Double,
    ): Vec2 {
        fun isFree(candidate: Vec2): Boolean {
            if (!table.isWithinPlayableBounds(candidate, radius)) return false
            return existingBalls.none { other ->
                candidate.distanceTo(other.position) < (radius + other.radiusMeters + MIN_CLEARANCE_TOLERANCE_METERS)
            }
        }

        val anchors = listOf(
            clampToPlayableBounds(preferred, table, radius),
            clampToPlayableBounds(Vec2(table.lengthMeters * 0.5, table.widthMeters * 0.5), table, radius),
        )
        for (anchor in anchors) {
            if (isFree(anchor)) return anchor
        }

        val ringStep = radius * 2.2
        val maxRing = ((table.lengthMeters / ringStep).toInt()).coerceAtLeast(10)
        for (ring in 1..maxRing) {
            val dist = ring * ringStep
            for (angleIdx in 0 until 16) {
                val angle = (2.0 * PI * angleIdx) / 16.0
                val offset = Vec2(cos(angle) * dist, sin(angle) * dist)
                for (anchor in anchors) {
                    val candidate = clampToPlayableBounds(anchor + offset, table, radius)
                    if (isFree(candidate)) return candidate
                }
            }
        }

        // Deterministic grid fallback across the playable area
        val step = radius * 2.1
        var x = radius
        while (x <= table.lengthMeters - radius) {
            var y = radius
            while (y <= table.widthMeters - radius) {
                val candidate = Vec2(x, y)
                if (isFree(candidate)) return candidate
                y += step
            }
            x += step
        }

        return anchors.first()
    }
}
