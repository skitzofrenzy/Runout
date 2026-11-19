package com.runout.engine.vision

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * A ball found by the detector, described by its image position and dominant colours.
 *
 * [whiteFraction] is the fraction (`0..1`) of the ball's pixels that are white; it separates
 * solids from stripes and finds the cue ball.
 */
data class DetectedBallCandidate(
    val id: Int,
    val imageCenterPx: Vec2,
    val dominantColor: BallPaletteColor,
    val secondaryColor: BallPaletteColor? = null,
    val whiteFraction: Double,
    val detectorConfidence: Double = 0.92,
)

/** Contact state of a ball: touching a cushion nose and/or other balls. */
data class FrozenBallStatus(
    val ballNumber: Int,
    val frozenToCushion: Boolean,
    val frozenToBallNumbers: List<Int>,
)

/** Result of identifying one detected candidate as a numbered ball on the table. */
data class IdentifiedBallResult(
    val candidateId: Int,
    val ball: Ball,
    val rawClothPositionMeters: Vec2,
    val identificationConfidence: Double,
    val needsUserReview: Boolean,
)

/** Full output of rectifying and identifying the balls of one photo. */
data class VisionRectificationResult(
    val layout: TableLayout,
    val identifiedBalls: List<IdentifiedBallResult>,
    val frozenBalls: List<FrozenBallStatus>,
    val cameraPositionMeters: Vec3,
)

/**
 * Ball identification via globally optimal (Hungarian) assignment of detected candidates to ball
 * numbers, followed by table-coordinate placement, overlap resolution and frozen-ball detection.
 */
object BallIdentificationAndAssignment {
    const val LOW_CONFIDENCE_THRESHOLD: Double = 0.72
    const val FROZEN_TOLERANCE_METERS: Double = 0.002

    private const val MISMATCH_COST = 2.5
    private const val SECONDARY_MATCH_COST = 0.35
    private const val CONFUSION_COST = 0.55
    private const val WHITE_FRACTION_SLOPE = 4.0
    private const val PUSH_APART_MAX_ITERATIONS = 200
    private const val PUSH_APART_EPSILON = 1e-9

    private val CONFUSION_PAIRS: Set<Set<BallPaletteColor>> = setOf(
        setOf(BallPaletteColor.RED, BallPaletteColor.MAROON),
        setOf(BallPaletteColor.RED, BallPaletteColor.ORANGE),
        setOf(BallPaletteColor.BLUE, BallPaletteColor.PURPLE),
        setOf(BallPaletteColor.PINK, BallPaletteColor.RED),
        setOf(BallPaletteColor.YELLOW, BallPaletteColor.ORANGE),
        setOf(BallPaletteColor.MAROON, BallPaletteColor.BROWN),
    )

    /**
     * Exact minimum-cost bipartite matching (Hungarian / Kuhn-Munkres, `O(N^2 M)`).
     *
     * Rows are assigned to distinct columns. If there are more rows than columns the matrix is padded
     * with zero-cost dummy columns and the rows that end up on a dummy column get `-1`.
     *
     * @return `result[row] = assignedCol`, or `-1` for a row left without a real column.
     */
    fun solveHungarianAssignment(costMatrix: Array<DoubleArray>): IntArray {
        val rows = costMatrix.size
        if (rows == 0) return IntArray(0)
        val realCols = costMatrix[0].size
        require(costMatrix.all { it.size == realCols }) { "Cost matrix must be rectangular." }
        require(costMatrix.all { row -> row.all { it.isFinite() } }) { "Costs must be finite." }
        if (realCols == 0) return IntArray(rows) { -1 }

        val cols = max(rows, realCols)
        fun cost(r: Int, c: Int): Double = if (c < realCols) costMatrix[r][c] else 0.0

        val inf = Double.MAX_VALUE / 4.0
        val u = DoubleArray(rows + 1)
        val v = DoubleArray(cols + 1)
        val p = IntArray(cols + 1)
        val way = IntArray(cols + 1)

        for (i in 1..rows) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(cols + 1) { inf }
            val used = BooleanArray(cols + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = inf
                var j1 = 0
                for (j in 1..cols) {
                    if (used[j]) continue
                    val cur = cost(i0 - 1, j - 1) - u[i0] - v[j]
                    if (cur < minv[j]) {
                        minv[j] = cur
                        way[j] = j0
                    }
                    if (minv[j] < delta) {
                        delta = minv[j]
                        j1 = j
                    }
                }
                for (j in 0..cols) {
                    if (used[j]) {
                        u[p[j]] += delta
                        v[j] -= delta
                    } else {
                        minv[j] -= delta
                    }
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val result = IntArray(rows) { -1 }
        for (j in 1..cols) {
            val row = p[j]
            if (row != 0 && j - 1 < realCols) {
                result[row - 1] = j - 1
            }
        }
        return result
    }

    /**
     * Identifies [candidates] as numbered balls, places them in table metres and resolves contacts.
     *
     * Candidates that cannot be matched to any allowed identity (more candidates than identities)
     * are omitted from the result.
     */
    fun rectifyAndIdentifyBalls(
        candidates: List<DetectedBallCandidate>,
        homography: PerspectiveHomography,
        table: TableSpec,
        ballSet: BallSetProfile = BallSetProfile.standardPool(),
        cameraPositionMeters: Vec3 = Vec3(-0.50, table.widthMeters * 0.5, 1.65),
        allowedObjectBallNumbers: Set<Int> = (1..15).toSet(),
    ): VisionRectificationResult {
        val radius = ballSet.ballRadiusMeters
        val pool = listOf(0) + allowedObjectBallNumbers.filter { it != 0 }.sorted()

        val costMatrix = Array(candidates.size) { i ->
            DoubleArray(pool.size) { j -> assignmentCost(candidates[i], pool[j], ballSet) }
        }
        val assignment = solveHungarianAssignment(costMatrix)

        class Placed(
            val candidate: DetectedBallCandidate,
            val number: Int,
            val cost: Double,
            val rawCloth: Vec2,
            var position: Vec2,
        )

        val placed = ArrayList<Placed>()
        for (i in candidates.indices) {
            val col = assignment[i]
            if (col < 0) continue
            val candidate = candidates[i]
            val centre = homography.imageToBallCenterMeters(
                candidate.imageCenterPx,
                radius,
                cameraPositionMeters,
            )
            placed.add(
                Placed(
                    candidate = candidate,
                    number = pool[col],
                    cost = costMatrix[i][col],
                    rawCloth = homography.imageToTableCloth(candidate.imageCenterPx),
                    position = clampToPlayable(centre, radius, table),
                ),
            )
        }

        val positions = placed.map { it.position }.toTypedArray()
        resolveOverlaps(positions, radius, table)
        positions.forEachIndexed { index, pos -> placed[index].position = pos }

        val identified = placed.map { item ->
            val confidence = (item.candidate.detectorConfidence * exp(-0.45 * item.cost))
                .coerceIn(0.05, 0.99)
            IdentifiedBallResult(
                candidateId = item.candidate.id,
                ball = Ball(number = item.number, position = item.position, radiusMeters = radius),
                rawClothPositionMeters = item.rawCloth,
                identificationConfidence = confidence,
                needsUserReview = confidence < LOW_CONFIDENCE_THRESHOLD,
            )
        }
        val layout = TableLayout(table = table, balls = identified.map { it.ball })
        return VisionRectificationResult(
            layout = layout,
            identifiedBalls = identified,
            frozenBalls = detectFrozenBalls(layout),
            cameraPositionMeters = cameraPositionMeters,
        )
    }

    /**
     * Finds balls resting on a cushion nose or touching other balls within [toleranceMeters].
     * Only balls with at least one contact are returned, sorted by ball number.
     */
    fun detectFrozenBalls(
        layout: TableLayout,
        toleranceMeters: Double = FROZEN_TOLERANCE_METERS,
    ): List<FrozenBallStatus> {
        val table = layout.table
        return layout.balls.mapNotNull { ball ->
            val r = ball.radiusMeters
            val x = ball.position.x
            val y = ball.position.y
            val onCushion = x - r <= toleranceMeters ||
                (table.lengthMeters - r) - x <= toleranceMeters ||
                y - r <= toleranceMeters ||
                (table.widthMeters - r) - y <= toleranceMeters
            val touching = layout.balls
                .filter { other ->
                    other !== ball &&
                        other.number != ball.number &&
                        ball.position.distanceTo(other.position) <=
                        (r + other.radiusMeters) + toleranceMeters
                }
                .map { it.number }
                .sorted()
            if (onCushion || touching.isNotEmpty()) {
                FrozenBallStatus(ball.number, onCushion, touching)
            } else {
                null
            }
        }.sortedBy { it.ballNumber }
    }

    private fun assignmentCost(
        candidate: DetectedBallCandidate,
        number: Int,
        ballSet: BallSetProfile,
    ): Double = colourCost(candidate, ballSet.colorForBall(number)) +
        whiteFractionCost(candidate.whiteFraction, number)

    private fun colourCost(candidate: DetectedBallCandidate, expected: BallPaletteColor): Double {
        val dominant = candidate.dominantColor
        return when {
            dominant == expected -> 0.0
            candidate.secondaryColor == expected -> SECONDARY_MATCH_COST
            setOf(dominant, expected) in CONFUSION_PAIRS -> CONFUSION_COST
            else -> MISMATCH_COST
        }
    }

    private fun whiteFractionCost(whiteFraction: Double, number: Int): Double = when (number) {
        0 -> max(0.0, 0.80 - whiteFraction) * WHITE_FRACTION_SLOPE
        8 -> max(0.0, whiteFraction - 0.28) * WHITE_FRACTION_SLOPE
        in 1..7 -> max(0.0, whiteFraction - 0.25) * WHITE_FRACTION_SLOPE
        in 9..15 -> max(0.0, 0.35 - whiteFraction) * WHITE_FRACTION_SLOPE
        else -> 0.0
    }

    private fun clampToPlayable(p: Vec2, r: Double, table: TableSpec): Vec2 = Vec2(
        min(max(p.x, r), table.lengthMeters - r),
        min(max(p.y, r), table.widthMeters - r),
    )

    /** Symmetrically pushes apart overlapping pairs in-place, re-clamping to the playable area. */
    private fun resolveOverlaps(positions: Array<Vec2>, r: Double, table: TableSpec) {
        val minDistance = 2.0 * r
        repeat(PUSH_APART_MAX_ITERATIONS) {
            var moved = false
            for (i in positions.indices) {
                for (j in i + 1 until positions.size) {
                    val a = positions[i]
                    val b = positions[j]
                    val distance = a.distanceTo(b)
                    if (distance >= minDistance - PUSH_APART_EPSILON) continue
                    val direction = if (distance < PUSH_APART_EPSILON) Vec2.UNIT_X else (b - a) / distance
                    val push = (minDistance - distance) / 2.0 + PUSH_APART_EPSILON
                    positions[i] = clampToPlayable(a - direction * push, r, table)
                    positions[j] = clampToPlayable(b + direction * push, r, table)
                    moved = true
                }
            }
            if (!moved) return
        }
    }
}
