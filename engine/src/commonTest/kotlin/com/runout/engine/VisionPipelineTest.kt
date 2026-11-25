package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.GameType
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.vision.BallIdentificationAndAssignment
import com.runout.engine.vision.CameraIntrinsicsSpec
import com.runout.engine.vision.DetectedBallCandidate
import com.runout.engine.vision.DetectedGameVariant
import com.runout.engine.vision.GameTypeSuggester
import com.runout.engine.vision.PerspectiveHomography
import com.runout.engine.vision.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent pinhole camera used to synthesise "photos": camera frame is x right, y down, z forward.
 */
private class PinholeCamera(
    val position: Vec3,
    target: Vec3,
    val intrinsics: CameraIntrinsicsSpec,
) {
    private val forward: Vec3 = unit(sub(target, position))
    private val right: Vec3 = unit(cross(forward, Vec3(0.0, 0.0, 1.0)))
    private val down: Vec3 = cross(forward, right)

    fun project(p: Vec3): Vec2 {
        val d = sub(p, position)
        val xc = dot(d, right)
        val yc = dot(d, down)
        val zc = dot(d, forward)
        return Vec2(
            intrinsics.principalPointPx.x + intrinsics.focalLengthPx * xc / zc,
            intrinsics.principalPointPx.y + intrinsics.focalLengthPx * yc / zc,
        )
    }

    /** Table corners in the order `[headLeft, footLeft, footRight, headRight]`. */
    fun tableCornersPx(table: TableSpec): List<Vec2> = listOf(
        project(Vec3(0.0, 0.0, 0.0)),
        project(Vec3(table.lengthMeters, 0.0, 0.0)),
        project(Vec3(table.lengthMeters, table.widthMeters, 0.0)),
        project(Vec3(0.0, table.widthMeters, 0.0)),
    )

    private companion object {
        fun sub(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)

        fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

        fun cross(a: Vec3, b: Vec3) = Vec3(
            a.y * b.z - a.z * b.y,
            a.z * b.x - a.x * b.z,
            a.x * b.y - a.y * b.x,
        )

        fun unit(a: Vec3): Vec3 {
            val n = sqrt(dot(a, a))
            return Vec3(a.x / n, a.y / n, a.z / n)
        }
    }
}

class VisionPipelineTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters
    private val intrinsics = CameraIntrinsicsSpec(
        focalLengthPx = 1000.0,
        principalPointPx = Vec2(960.0, 540.0),
    )
    private val headEndCameraPosition = Vec3(-0.50, 0.635, 1.65)
    private val headEndCamera = PinholeCamera(
        position = headEndCameraPosition,
        target = Vec3(1.27, 0.635, 0.0),
        intrinsics = intrinsics,
    )

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun candidate(
        id: Int,
        color: BallPaletteColor,
        whiteFraction: Double,
        secondary: BallPaletteColor? = null,
        centerPx: Vec2 = Vec2(500.0 + 10.0 * id, 500.0),
        confidence: Double = 0.92,
    ) = DetectedBallCandidate(
        id = id,
        imageCenterPx = centerPx,
        dominantColor = color,
        secondaryColor = secondary,
        whiteFraction = whiteFraction,
        detectorConfidence = confidence,
    )

    /** Affine top-down style homography: `u = 100 + 500 X`, `v = 100 + 500 Y`. */
    private fun topDownHomography(): PerspectiveHomography = PerspectiveHomography.fromTableQuad(
        listOf(
            Vec2(100.0, 100.0),
            Vec2(100.0 + 500.0 * table.lengthMeters, 100.0),
            Vec2(100.0 + 500.0 * table.lengthMeters, 100.0 + 500.0 * table.widthMeters),
            Vec2(100.0, 100.0 + 500.0 * table.widthMeters),
        ),
        table,
    )

    private fun layoutOf(tableSpec: TableSpec, numbers: List<Int>, radius: Double = tableSpec.ballRadiusMeters) =
        TableLayout(
            table = tableSpec,
            balls = numbers.mapIndexed { index, n ->
                Ball(number = n, position = Vec2(0.3 + 0.1 * index, 0.5), radiusMeters = radius)
            },
        )

    @Test
    fun dltHomographyRoundTripsCornersAndInteriorPoints() {
        val corners = headEndCamera.tableCornersPx(table)
        val homography = PerspectiveHomography.fromTableQuad(corners, table)

        val expectedTargets = listOf(
            Vec2(0.0, 0.0),
            Vec2(table.lengthMeters, 0.0),
            Vec2(table.lengthMeters, table.widthMeters),
            Vec2(0.0, table.widthMeters),
        )
        corners.zip(expectedTargets).forEach { (px, expected) ->
            assertTrue(homography.imageToTableCloth(px).distanceTo(expected) < 1e-6)
        }

        val interior = listOf(Vec2(1.0, 0.4), Vec2(2.2, 1.1), Vec2(0.3, 0.9), Vec2(1.27, 0.635))
        interior.forEach { tablePoint ->
            val pixel = headEndCamera.project(Vec3(tablePoint.x, tablePoint.y, 0.0))
            assertTrue(homography.imageToTableCloth(pixel).distanceTo(tablePoint) < 1e-6)
            assertTrue(homography.tableClothToImage(tablePoint).distanceTo(pixel) < 1e-4)
            val roundTrip = homography.tableClothToImage(homography.imageToTableCloth(pixel))
            assertTrue(roundTrip.distanceTo(pixel) < 1e-4)
        }
    }

    @Test
    fun homographySolverRejectsWrongPointCountsAndDegenerateInput() {
        val four = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0), Vec2(0.0, 1.0))
        assertFailsWith<IllegalArgumentException> {
            PerspectiveHomography.fromPointCorrespondences(four.take(3), four.take(3))
        }
        val collinear = listOf(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(2.0, 0.0), Vec2(3.0, 0.0))
        assertFailsWith<IllegalArgumentException> {
            PerspectiveHomography.fromPointCorrespondences(collinear, four)
        }
        assertFailsWith<IllegalArgumentException> {
            PerspectiveHomography(DoubleArray(8), DoubleArray(9))
        }
        val homography = PerspectiveHomography.fromTableQuad(headEndCamera.tableCornersPx(table), table)
        assertFailsWith<IllegalArgumentException> {
            homography.imageToBallCenterMeters(Vec2(960.0, 540.0), r, Vec3(0.0, 0.0, r))
        }
    }

    @Test
    fun ballHeightCorrectionGivesSubCentimetreMedianErrorWhereClothProjectionDoesNot() {
        val homography = PerspectiveHomography.fromTableQuad(headEndCamera.tableCornersPx(table), table)
        val groundTruth = listOf(
            Vec2(1.4, 0.3), Vec2(1.6, 0.9), Vec2(1.9, 0.5), Vec2(2.0, 1.1),
            Vec2(2.2, 0.7), Vec2(2.35, 0.35), Vec2(1.5, 0.6), Vec2(2.1, 0.2),
        )
        val jitter = listOf(
            Vec2(0.6, -0.6), Vec2(-0.9, 0.4), Vec2(0.5, 1.0), Vec2(-1.1, -0.7),
            Vec2(0.8, 0.3), Vec2(-0.4, 0.9), Vec2(1.0, -0.5), Vec2(-0.7, -1.0),
        )

        // The forward model agrees with an independent pinhole projection of the 3D ball centre.
        groundTruth.forEach { truth ->
            val viaHomography = homography.ballCenterMetersToImage(truth, r, headEndCameraPosition)
            val viaPinhole = headEndCamera.project(Vec3(truth.x, truth.y, r))
            assertTrue(viaHomography.distanceTo(viaPinhole) < 1e-3)
            val exact = homography.imageToBallCenterMeters(viaPinhole, r, headEndCameraPosition)
            assertTrue(exact.distanceTo(truth) < 1e-6)
        }

        val pixels = groundTruth.mapIndexed { i, truth ->
            homography.ballCenterMetersToImage(truth, r, headEndCameraPosition) + jitter[i]
        }
        val correctedErrors = pixels.mapIndexed { i, px ->
            homography.imageToBallCenterMeters(px, r, headEndCameraPosition).distanceTo(groundTruth[i])
        }
        val uncorrectedErrors = pixels.mapIndexed { i, px ->
            homography.imageToTableCloth(px).distanceTo(groundTruth[i])
        }
        assertTrue(median(correctedErrors) < 0.01, "corrected median ${median(correctedErrors)} m")
        assertTrue(median(uncorrectedErrors) > 0.03, "uncorrected median ${median(uncorrectedErrors)} m")
    }

    @Test
    fun cameraPositionIsRecoveredFromHomographyAndIntrinsics() {
        val cameras = listOf(
            headEndCamera,
            PinholeCamera(Vec3(3.15, 0.635, 1.55), Vec3(1.27, 0.635, 0.0), intrinsics),
            PinholeCamera(Vec3(1.0, -0.7, 1.9), Vec3(1.27, 0.635, 0.0), intrinsics),
        )
        cameras.forEach { camera ->
            val homography = PerspectiveHomography.fromTableQuad(camera.tableCornersPx(table), table)
            val estimate = homography.estimateCameraPositionMeters(table, intrinsics)
            assertEquals(camera.position.x, estimate.x, 1e-3)
            assertEquals(camera.position.y, estimate.y, 1e-3)
            assertEquals(camera.position.z, estimate.z, 1e-3)
        }
    }

    @Test
    fun hungarianSolvesSquareRectangularAndTallMatricesOptimally() {
        val square = arrayOf(
            doubleArrayOf(4.0, 1.0, 3.0),
            doubleArrayOf(2.0, 0.0, 5.0),
            doubleArrayOf(3.0, 2.0, 2.0),
        )
        assertContentEquals(intArrayOf(1, 0, 2), BallIdentificationAndAssignment.solveHungarianAssignment(square))

        val wide = arrayOf(
            doubleArrayOf(9.0, 2.0, 7.0, 8.0),
            doubleArrayOf(6.0, 4.0, 3.0, 7.0),
        )
        assertContentEquals(intArrayOf(1, 2), BallIdentificationAndAssignment.solveHungarianAssignment(wide))

        val tall = arrayOf(
            doubleArrayOf(1.0, 9.0),
            doubleArrayOf(9.0, 1.0),
            doubleArrayOf(5.0, 5.0),
            doubleArrayOf(7.0, 7.0),
        )
        assertContentEquals(intArrayOf(0, 1, -1, -1), BallIdentificationAndAssignment.solveHungarianAssignment(tall))

        assertEquals(0, BallIdentificationAndAssignment.solveHungarianAssignment(emptyArray()).size)
    }

    @Test
    fun hungarianMatchesBruteForceOnRandomMatrices() {
        val random = Random(42)
        repeat(8) {
            val n = 5
            val matrix = Array(n) { DoubleArray(n) { random.nextDouble(0.0, 10.0) } }
            val assignment = BallIdentificationAndAssignment.solveHungarianAssignment(matrix)
            assertEquals(n, assignment.toSet().size)
            val hungarianCost = assignment.indices.sumOf { matrix[it][assignment[it]] }

            fun best(row: Int, used: Int): Double {
                if (row == n) return 0.0
                var min = Double.MAX_VALUE
                for (c in 0 until n) {
                    if (used and (1 shl c) != 0) continue
                    min = minOf(min, matrix[row][c] + best(row + 1, used or (1 shl c)))
                }
                return min
            }
            assertEquals(best(0, 0), hungarianCost, 1e-9)
        }
    }

    @Test
    fun confusedColoursAndStripesAreAssignedGloballyAndLowConfidenceIsFlagged() {
        val candidates = listOf(
            candidate(0, BallPaletteColor.WHITE, 0.95),
            candidate(1, BallPaletteColor.RED, 0.08),
            candidate(2, BallPaletteColor.RED, 0.55),
            candidate(3, BallPaletteColor.RED, 0.10, secondary = BallPaletteColor.MAROON),
        )
        val result = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = candidates,
            homography = topDownHomography(),
            table = table,
        )
        val numbers = result.identifiedBalls.associate { it.candidateId to it.ball.number }
        assertEquals(mapOf(0 to 0, 1 to 3, 2 to 11, 3 to 7), numbers)
        assertTrue(result.identifiedBalls.none { it.needsUserReview })
        assertEquals(0.92, result.identifiedBalls.first { it.candidateId == 1 }.identificationConfidence, 1e-9)

        val pink = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = listOf(candidate(1, BallPaletteColor.PINK, 0.05)),
            homography = topDownHomography(),
            table = table,
        ).identifiedBalls.single()
        assertEquals(3, pink.ball.number)
        assertTrue(pink.identificationConfidence < BallIdentificationAndAssignment.LOW_CONFIDENCE_THRESHOLD)
        assertTrue(pink.needsUserReview)
    }

    @Test
    fun tvTournamentBallSetSwapsPinkAndBrownIdentities() {
        val candidates = listOf(
            candidate(1, BallPaletteColor.PINK, 0.05),
            candidate(2, BallPaletteColor.PINK, 0.60),
            candidate(3, BallPaletteColor.BROWN, 0.08),
            candidate(4, BallPaletteColor.BROWN, 0.55),
        )
        val homography = topDownHomography()

        val tv = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = candidates,
            homography = homography,
            table = table,
            ballSet = BallSetProfile.tvTournamentPool(),
        )
        assertEquals(
            mapOf(1 to 4, 2 to 12, 3 to 7, 4 to 15),
            tv.identifiedBalls.associate { it.candidateId to it.ball.number },
        )
        assertTrue(tv.identifiedBalls.none { it.needsUserReview })

        val standard = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = candidates.take(2),
            homography = homography,
            table = table,
        )
        assertEquals(
            mapOf(1 to 3, 2 to 11),
            standard.identifiedBalls.associate { it.candidateId to it.ball.number },
        )

        val restricted = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = candidates.take(2),
            homography = homography,
            table = table,
            allowedObjectBallNumbers = setOf(3, 4),
        )
        assertTrue(restricted.identifiedBalls.all { it.ball.number in setOf(0, 3, 4) })
    }

    @Test
    fun overlappingDetectionsArePushedApartClampedAndReportedFrozen() {
        val homography = topDownHomography()
        val camera = headEndCameraPosition
        fun pixelFor(x: Double, y: Double) = homography.ballCenterMetersToImage(Vec2(x, y), r, camera)

        val candidates = listOf(
            candidate(0, BallPaletteColor.WHITE, 0.95, centerPx = pixelFor(2.0, 0.3)),
            candidate(1, BallPaletteColor.YELLOW, 0.05, centerPx = pixelFor(1.00, 0.6)),
            candidate(2, BallPaletteColor.BLUE, 0.05, centerPx = pixelFor(1.04, 0.6)),
            candidate(3, BallPaletteColor.RED, 0.05, centerPx = pixelFor(0.01, 0.6)),
        )
        val result = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = candidates,
            homography = homography,
            table = table,
            cameraPositionMeters = camera,
        )
        val layout = result.layout
        assertFalse(layout.hasOverlappingBalls(toleranceMeters = 1e-6))
        layout.balls.forEach { assertTrue(table.isWithinPlayableBounds(it.position, r)) }

        val ball1 = requireNotNull(layout.ballByNumber(1))
        val ball2 = requireNotNull(layout.ballByNumber(2))
        val ball3 = requireNotNull(layout.ballByNumber(3))
        assertTrue(ball1.position.distanceTo(ball2.position) >= 2.0 * r - 1e-6)
        // Symmetric push: the midpoint of the pair is preserved.
        assertEquals(1.02, (ball1.position.x + ball2.position.x) / 2.0, 1e-6)
        assertEquals(r, ball3.position.x, 1e-9)
        // The raw cloth projection of the unmoved cue ball is displaced by the ball-height effect.
        val cue = result.identifiedBalls.first { it.ball.number == 0 }
        assertTrue(cue.rawClothPositionMeters.distanceTo(cue.ball.position) > 0.03)
        assertEquals(2.0, cue.ball.position.x, 1e-6)
        assertEquals(0.3, cue.ball.position.y, 1e-6)

        assertEquals(listOf(1, 2, 3), result.frozenBalls.map { it.ballNumber })
        val status1 = result.frozenBalls.first { it.ballNumber == 1 }
        val status3 = result.frozenBalls.first { it.ballNumber == 3 }
        assertEquals(listOf(2), status1.frozenToBallNumbers)
        assertFalse(status1.frozenToCushion)
        assertTrue(status3.frozenToCushion)
        assertTrue(status3.frozenToBallNumbers.isEmpty())
        assertEquals(camera, result.cameraPositionMeters)
    }

    @Test
    fun detectFrozenBallsHonoursToleranceForBallsAndCushions() {
        val width = table.widthMeters
        val layout = TableLayout(
            table = table,
            balls = listOf(
                Ball(1, Vec2(1.0, 0.6), r),
                Ball(2, Vec2(1.0 + 2.0 * r + 0.001, 0.6), r),
                Ball(3, Vec2(1.0, 0.6 + 2.0 * r + 0.01), r),
                Ball(4, Vec2(r + 0.001, 0.3), r),
                Ball(5, Vec2(2.0, width - r - 0.0015), r),
                Ball(6, Vec2(2.0, 0.2), r),
            ),
        )
        val frozen = BallIdentificationAndAssignment.detectFrozenBalls(layout)
        assertEquals(listOf(1, 2, 4, 5), frozen.map { it.ballNumber })
        assertEquals(listOf(2), frozen.first { it.ballNumber == 1 }.frozenToBallNumbers)
        assertEquals(listOf(1), frozen.first { it.ballNumber == 2 }.frozenToBallNumbers)
        assertTrue(frozen.first { it.ballNumber == 4 }.frozenToCushion)
        assertTrue(frozen.first { it.ballNumber == 5 }.frozenToCushion)
        assertTrue(frozen.none { it.ballNumber == 3 || it.ballNumber == 6 })

        val strict = BallIdentificationAndAssignment.detectFrozenBalls(layout, toleranceMeters = 0.0005)
        assertTrue(strict.isEmpty())
    }

    @Test
    fun gameTypeSuggesterRanksEightNineTenBallAndSnooker() {
        val eight = GameTypeSuggester.suggestGameTypes(layoutOf(table, listOf(0, 1, 3, 8, 9, 11, 15)))
        assertEquals(DetectedGameVariant.EIGHT_BALL, eight.first().variant)
        assertEquals(GameType.EIGHT_BALL, eight.first().engineGameType)
        assertEquals(EightBallGroup.OPEN, eight.first().suggestedEightBallGroup)
        assertTrue(eight.first().reasons.any { it.contains("11") && it.contains("15") })

        val stripesOnly = GameTypeSuggester.suggestGameTypes(layoutOf(table, listOf(0, 8, 11, 13)))
        assertEquals(EightBallGroup.STRIPES, stripesOnly.first().suggestedEightBallGroup)

        val nine = GameTypeSuggester.suggestGameTypes(layoutOf(table, listOf(0, 1, 2, 3, 5, 7, 9)))
        assertEquals(DetectedGameVariant.NINE_BALL, nine.first().variant)
        assertEquals(GameType.NINE_BALL, nine.first().engineGameType)

        val ten = GameTypeSuggester.suggestGameTypes(layoutOf(table, listOf(0, 1, 4, 6, 10)))
        assertEquals(DetectedGameVariant.TEN_BALL, ten.first().variant)
        assertEquals(GameType.TEN_BALL, ten.first().engineGameType)

        val snookerTable = TableSpec.fullSizeSnooker()
        val snooker = GameTypeSuggester.suggestGameTypes(layoutOf(snookerTable, listOf(0, 1, 2, 3)))
        assertEquals(DetectedGameVariant.SNOOKER, snooker.first().variant)
        assertEquals(GameType.EIGHT_BALL, snooker.first().engineGameType)

        listOf(eight, nine, ten, snooker).forEach { ranked ->
            assertEquals(4, ranked.map { it.variant }.toSet().size)
            assertEquals(ranked.sortedByDescending { it.confidence }, ranked)
            assertTrue(ranked.all { it.confidence in 0.05..0.98 && it.reasons.isNotEmpty() })
        }
    }

    @Test
    fun gameTypeSuggesterAppliesRecentlyPlayedPrior() {
        val layout = layoutOf(table, listOf(0, 1, 2, 3, 5, 7, 9))
        val without = GameTypeSuggester.suggestGameTypes(layout)
        val with = GameTypeSuggester.suggestGameTypes(layout, lastPlayedVariant = DetectedGameVariant.TEN_BALL)

        val before = without.first { it.variant == DetectedGameVariant.TEN_BALL }
        val after = with.first { it.variant == DetectedGameVariant.TEN_BALL }
        assertTrue(abs((after.confidence - before.confidence) - 0.08) < 1e-9)
        assertTrue(after.reasons.contains("Matches recently played game type"))
        assertEquals(DetectedGameVariant.NINE_BALL, with.first().variant)
    }
}
