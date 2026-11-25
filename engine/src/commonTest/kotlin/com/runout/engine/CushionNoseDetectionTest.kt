package com.runout.engine

import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.vision.ArgbImageFrame
import com.runout.engine.vision.BallIdentificationAndAssignment
import com.runout.engine.vision.ClothHueCategory
import com.runout.engine.vision.DetectedBallCandidate
import com.runout.engine.vision.PerspectiveHomography
import com.runout.engine.vision.RawFrameDetectionSummary
import com.runout.engine.vision.RawImageTableDetector
import com.runout.engine.vision.RawImageTableDetector.SyntheticCushionStyle
import com.runout.engine.vision.TableQuadDetection
import com.runout.engine.vision.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cushion-nose detection on synthetic renders: where balls frozen to the rails are found, which
 * cushion edges are believed, and how the quad falls back when they are not.
 */
class CushionNoseDetectionTest {

    private val table = TableSpec.nineFootPool()
    private val r = table.ballRadiusMeters
    private val l = table.lengthMeters
    private val w = table.widthMeters

    /** A camera view: the quad in image order `[TL, TR, BR, BL]` and the pose it is rendered from. */
    private class View(
        val name: String,
        val rawQuad: List<Vec2>,
        val pose: Vec3,
        val ballRadiusPx: Int,
        val endOn: Boolean,
    )

    private val endOnView = View(
        "end-on",
        listOf(Vec2(210.0, 40.0), Vec2(470.0, 40.0), Vec2(560.0, 330.0), Vec2(110.0, 330.0)),
        Vec3(l + 0.65, 0.5 * w, 1.55),
        ballRadiusPx = 7,
        endOn = true,
    )

    /** Long rails top and bottom, taken from beyond the lower one. */
    private val landscapeView = View(
        "landscape",
        listOf(Vec2(90.0, 70.0), Vec2(550.0, 70.0), Vec2(590.0, 300.0), Vec2(50.0, 300.0)),
        Vec3(0.5 * l, w + 0.9, 1.5),
        ballRadiusPx = 6,
        endOn = false,
    )

    private fun ball(number: Int, x: Double, y: Double) = Ball(number, Vec2(x, y), r)

    /** The quad in the order [PerspectiveHomography.fromTableQuad] wants it. */
    private fun View.homographyQuad(): List<Vec2> =
        RawImageTableDetector.orientQuadForHomography(rawQuad, table, endOn).first

    private fun View.render(
        layout: TableLayout = TableLayout(table, emptyList()),
        cushions: SyntheticCushionStyle = SyntheticCushionStyle.SHADED,
        cushionWidth: Double = RawImageTableDetector.CUSHION_VISIBLE_BAND_METERS,
        contrast: Double = 1.0,
        cloth: ClothHueCategory = ClothHueCategory.BLUE,
        width: Int = 640,
        height: Int = 360,
    ): ArgbImageFrame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
        layout = layout,
        quadCornersPx = homographyQuad(),
        cameraPoseMeters = pose,
        width = width,
        height = height,
        clothCategory = cloth,
        ballRadiusPx = ballRadiusPx,
        cushions = cushions,
        cushionWidthMeters = cushionWidth,
        cushionContrast = contrast,
    )

    /** One ball frozen to each rail, plus two in open table. */
    private fun railLayout() = TableLayout(
        table,
        listOf(
            ball(0, 0.80, 0.50),
            ball(2, 1.30, r + 0.0005),
            ball(3, 1.00, w - r - 0.0005),
            ball(4, r + 0.0005, 0.40),
            ball(5, l - r - 0.0005, 0.85),
            ball(9, 1.50, 0.70),
        ),
    )

    /** Table positions of the detections, mapped through the nose quad of [summary]. */
    private fun detectedPositions(summary: RawFrameDetectionSummary, view: View): List<Vec2> {
        val (oriented, _) = RawImageTableDetector.orientQuadForHomography(
            summary.detectedQuadCornersPx,
            table,
            summary.endOnView,
        )
        val homography = PerspectiveHomography.fromTableQuad(oriented, table)
        return summary.detectedCandidates.map { homography.imageToBallCenterMeters(it.imageCenterPx, r, view.pose) }
    }

    /** Distance from each true ball to the nearest detection (infinite when there is none). */
    private fun errorsTo(layout: TableLayout, found: List<Vec2>): List<Double> =
        layout.balls.map { truth -> found.minOfOrNull { it.distanceTo(truth.position) } ?: Double.POSITIVE_INFINITY }

    private fun dragged(quad: List<Vec2>): List<Vec2> = quad.mapIndexed { i, c ->
        when (i) {
            0 -> Vec2(c.x + 3.0, c.y + 2.0)
            2 -> Vec2(c.x - 2.0, c.y - 1.0)
            else -> c
        }
    }

    // -----------------------------------------------------------------------------------------
    // Balls on the rails: one mask rule for first analysis and re-analysis
    // -----------------------------------------------------------------------------------------

    @Test
    fun ballsFrozenToAllFourRailsAreFoundOnFirstAnalysisAndAfterReanalysis() {
        val layout = railLayout()
        for (view in listOf(endOnView, landscapeView)) {
            val frame = view.render(layout)
            val first = RawImageTableDetector.analyzeArgbFrame(frame)
            assertEquals(layout.balls.size, first.detectedCandidates.size, "${view.name}: first analysis")
            val firstErrors = errorsTo(layout, detectedPositions(first, view))
            assertTrue(firstErrors.all { it < 0.03 }, "${view.name}: first analysis errors $firstErrors")

            // The confirm screen re-analyses with the quad it was handed back plus what it knows.
            val same = RawImageTableDetector.analyzeArgbFrame(
                frame,
                quadOverridePx = first.detectedQuadCornersPx,
                previousQuads = first.toQuadDetection(),
            )
            assertEquals(first.detectedCandidates.size, same.detectedCandidates.size, "${view.name}: same quad")
            assertEquals(first.ballMaskCornersPx, same.ballMaskCornersPx, "${view.name}: same mask")
            val firstPositions = detectedPositions(first, view)
            detectedPositions(same, view).forEachIndexed { i, p ->
                assertTrue(p.distanceTo(firstPositions[i]) < 1e-6, "${view.name}: ball $i moved on re-analysis")
            }

            // Nothing remembered about the first analysis: the mask still covers the cushion band.
            val bare = RawImageTableDetector.analyzeArgbFrame(frame, quadOverridePx = first.detectedQuadCornersPx)
            assertEquals(layout.balls.size, bare.detectedCandidates.size, "${view.name}: override only")
            val bareErrors = errorsTo(layout, detectedPositions(bare, view))
            assertTrue(bareErrors.all { it < 0.06 }, "${view.name}: override only errors $bareErrors")

            // A slightly dragged corner, with and without the first analysis to hand.
            val moved = dragged(first.detectedQuadCornersPx)
            for ((label, previous) in listOf("dragged" to first.toQuadDetection(), "dragged, nothing known" to null)) {
                val again = RawImageTableDetector.analyzeArgbFrame(
                    frame,
                    quadOverridePx = moved,
                    previousQuads = previous,
                    endOnView = first.endOnView,
                )
                // With the first analysis to hand every ball is found. With nothing known the mask only
                // reaches the nominal band, which can cut the rail ball farthest from the camera so
                // far that it no longer looks like a disc on the band: one may be lost, none is moved.
                val allowedMissing = if (previous == null) 1 else 0
                assertTrue(again.detectedCandidates.size >= layout.balls.size - allowedMissing, "${view.name}: $label found ${again.detectedCandidates.size}")
                val found = detectedPositions(again, view)
                val errors = errorsTo(layout, found).sorted().dropLast(allowedMissing)
                assertTrue(errors.all { it < 0.06 }, "${view.name}: $label errors $errors")
            }
        }
    }

    @Test
    fun theBallMaskReachesBeyondTheNoseOnEverySideButNotIntoTheRail() {
        for (view in listOf(endOnView, landscapeView)) {
            val frame = view.render()
            val first = RawImageTableDetector.analyzeArgbFrame(frame)
            val homography = PerspectiveHomography.fromTableQuad(
                RawImageTableDetector.orientQuadForHomography(first.detectedQuadCornersPx, table, first.endOnView).first,
                table,
            )
            val corners = RawImageTableDetector.orientQuadForHomography(first.ballMaskCornersPx, table, first.endOnView).first
                .map { homography.imageToTableCloth(it) }
            // Table x of the head corners and y of the left ones: how far the mask reaches past each nose.
            val reachHead = -corners.minOf { it.x }
            val reachFoot = corners.maxOf { it.x } - l
            val reachLeft = -corners.minOf { it.y }
            val reachRight = corners.maxOf { it.y } - w
            for ((side, reach) in listOf("head" to reachHead, "foot" to reachFoot, "left" to reachLeft, "right" to reachRight)) {
                assertTrue(reach >= 2.0 * r - 0.005, "${view.name}: mask reaches only ${reach * 1000} mm past the $side nose")
                assertTrue(reach <= 0.16, "${view.name}: mask reaches ${reach * 1000} mm past the $side nose")
            }
        }
    }

    @Test
    fun aBlobOnTheCushionBandBeyondABallRadiusIsNotReportedAsABall() {
        // A ball-sized mark on the near cushion (the camera stands beyond it), 45 mm beyond the
        // nose at its centre, well past one radius. Its image lies on the cushion band, inside the
        // ball mask, so only the position stage can tell it is not on the table.
        val view = landscapeView
        val homography = PerspectiveHomography.fromTableQuad(view.homographyQuad(), table)
        val open = listOf(ball(0, 0.80, 0.50), ball(9, 1.50, 0.70))
        val onBand = ball(4, 1.60, w + 0.045)
        val frame = view.render(TableLayout(table, open + onBand))

        val summary = RawImageTableDetector.analyzeArgbFrame(frame)
        assertEquals(3, summary.detectedCandidates.size, "the mask lets the blob through, the position stage must not")

        val result = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = summary.detectedCandidates,
            homography = PerspectiveHomography.fromTableQuad(
                RawImageTableDetector.orientQuadForHomography(summary.detectedQuadCornersPx, table, summary.endOnView).first,
                table,
            ),
            table = table,
            cameraPositionMeters = view.pose,
        )
        assertEquals(1, result.rejectedCandidateIds.size)
        assertEquals(2, result.layout.balls.size)
        assertTrue(result.layout.balls.none { it.number == 4 })
        val rejected = summary.detectedCandidates.first { it.id == result.rejectedCandidateIds.single() }
        val expectedPixel = homography.ballCenterMetersToImage(onBand.position, r, view.pose)
        assertTrue(rejected.imageCenterPx.distanceTo(expectedPixel) < 6.0, "rejected the wrong blob")
    }

    // -----------------------------------------------------------------------------------------
    // Physical limit on the cushion width
    // -----------------------------------------------------------------------------------------

    /** Paints every pixel whose table position satisfies [inside] with [argb] (cloth plane). */
    private fun paint(frame: ArgbImageFrame, view: View, argb: Int, inside: (Vec2) -> Boolean): ArgbImageFrame {
        val homography = PerspectiveHomography.fromTableQuad(view.homographyQuad(), table)
        val pixels = frame.argbPixels.copyOf()
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                val p = homography.imageToTableCloth(Vec2(x.toDouble(), y.toDouble()))
                if (p.x.isFinite() && p.y.isFinite() && inside(p)) pixels[y * frame.width + x] = argb
            }
        }
        return ArgbImageFrame(frame.width, frame.height, pixels)
    }

    private fun assertQuadNear(quad: List<Vec2>, truth: List<Vec2>, tolerancePx: Double, label: String) {
        for (i in 0 until 4) {
            assertTrue(quad[i].distanceTo(truth[i]) < tolerancePx, "$label corner $i off by ${quad[i].distanceTo(truth[i])} px")
        }
    }

    @Test
    fun aShadowStripParallelToARailTwelveCentimetresInsideItIsNotTakenForTheCushionEdge() {
        // 15 mm wide dark strip along the y = 0 rail, 120 mm to 135 mm in from the cushion back
        // (the back is 65 mm outside the nose), so it is 55 mm to 70 mm inside the nose.
        val strip = 0xFF1B2A38.toInt()
        for (view in listOf(endOnView, landscapeView)) {
            val clean = view.render()
            val painted = paint(clean, view, strip) { p -> p.y in 0.055..0.070 && p.x in 0.20..(l - 0.20) }
            val cloth = RawImageTableDetector.detectClothSurface(painted)
            val quads = RawImageTableDetector.detectTableQuads(painted, cloth, table)
            val reference = RawImageTableDetector.detectTableQuads(clean, RawImageTableDetector.detectClothSurface(clean), table)
            assertQuadNear(quads.noseQuadCornersPx, view.rawQuad, 3.0, "${view.name} with strip")
            assertQuadNear(reference.noseQuadCornersPx, view.rawQuad, 3.0, "${view.name} clean")
            // Either the true nose was found, or the side is flagged for review: never a silent wrong line.
            for (i in 0 until 4) {
                assertTrue(
                    !quads.edges[i].usedFallback || quads.needsCornerReview,
                    "${view.name}: side $i fallback must raise the review flag",
                )
            }
            assertTrue(quads.edges.none { it.usedFallback }, "${view.name}: the true nose was visible behind the strip: ${quads.edges}")
        }
    }

    @Test
    fun aLightLineOnTheCushionBehindTheNoseDoesNotMoveTheNose() {
        // A 10 mm light line 45 mm in from the cushion back sits between the cushion back and the
        // nose (inside the cushion): it is behind the nose, never a candidate in front of it.
        val view = endOnView
        val painted = paint(view.render(), view, 0xFFC8D8E8.toInt()) { p -> p.y in -0.030..-0.020 && p.x in 0.20..(l - 0.20) }
        val quads = RawImageTableDetector.detectTableQuads(painted, RawImageTableDetector.detectClothSurface(painted), table)
        assertQuadNear(quads.noseQuadCornersPx, view.rawQuad, 3.0, "light line on the cushion")
    }

    @Test
    fun theCushionWidthWindowIsTheDocumentedRange() {
        assertEquals(0.020, RawImageTableDetector.MIN_CUSHION_WIDTH_METERS)
        assertEquals(0.110, RawImageTableDetector.MAX_CUSHION_WIDTH_METERS)
        // 91 mm is the widest visible band measured on photos; the window keeps a margin over it.
        assertTrue(RawImageTableDetector.MAX_CUSHION_WIDTH_METERS > 0.091)
        assertTrue(RawImageTableDetector.MIN_CUSHION_WIDTH_METERS < RawImageTableDetector.CUSHION_TOP_WIDTH_METERS)
    }

    // -----------------------------------------------------------------------------------------
    // Table size
    // -----------------------------------------------------------------------------------------

    /** Signed distance in metres from the true nose to a detected nose side, outward positive. */
    private fun outwardErrorsMeters(
        detected: List<Vec2>,
        view: View,
        spec: TableSpec,
    ): List<Double> {
        val oriented = RawImageTableDetector.orientQuadForHomography(view.rawQuad, spec, view.endOn).first
        val homography = PerspectiveHomography.fromTableQuad(oriented, spec)
        val rectOrder = RawImageTableDetector.orientQuadForHomography(detected, spec, view.endOn).first
        val corners = rectOrder.map { homography.imageToTableCloth(it) }
        // Corner order is head-left, foot-left, foot-right, head-right.
        val head = -(corners[0].x + corners[3].x) / 2.0
        val foot = (corners[1].x + corners[2].x) / 2.0 - spec.lengthMeters
        val left = -(corners[0].y + corners[1].y) / 2.0
        val right = (corners[2].y + corners[3].y) / 2.0 - spec.widthMeters
        return listOf(head, foot, left, right)
    }

    @Test
    fun theFallbackBandMatchesTheRenderedCushionOnASnookerTable() {
        val snooker = TableSpec.fullSizeSnooker()
        val view = View(
            "snooker end-on",
            endOnView.rawQuad,
            Vec3(snooker.lengthMeters + 0.9, 0.5 * snooker.widthMeters, 1.9),
            ballRadiusPx = 6,
            endOn = true,
        )
        val oriented = RawImageTableDetector.orientQuadForHomography(view.rawQuad, snooker, view.endOn).first
        val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
            layout = TableLayout(snooker, emptyList()),
            quadCornersPx = oriented,
            cameraPoseMeters = view.pose,
            cushions = SyntheticCushionStyle.CLOTH_MATCHED,
        )
        val cloth = RawImageTableDetector.detectClothSurface(frame)
        val withTable = RawImageTableDetector.detectTableQuads(frame, cloth, snooker)
        assertTrue(withTable.needsCornerReview)
        val errors = outwardErrorsMeters(withTable.noseQuadCornersPx, view, snooker)
        // A fallback is placed from the nominal band; the rendered band is the same nominal 65 mm
        // (50 mm on the side the camera stands behind), so the sides the outline follows closely
        // (foot and both long rails) land within a few millimetres. Index 0 is the head side, whose
        // outline is lifted by a fixed margin in end-on views.
        assertTrue(errors.drop(1).all { abs(it) < 0.012 }, "snooker-sized fallback errors (m) $errors")

        // Taking the same render for a 9 ft table scales the band wrongly: the probe distances
        // and the fallback inset are fixed fractions of the wrong table.
        val asNineFoot = RawImageTableDetector.detectTableQuads(frame, cloth, table)
        val wrong = outwardErrorsMeters(asNineFoot.noseQuadCornersPx, view, snooker)
        assertTrue(
            wrong.drop(1).maxOf { abs(it) } > errors.drop(1).maxOf { abs(it) } + 0.005,
            "9 ft assumption ($wrong) should be worse than the snooker spec ($errors)",
        )
    }

    // -----------------------------------------------------------------------------------------
    // Orientation decided once, near side, fallback on both views
    // -----------------------------------------------------------------------------------------

    @Test
    fun aBorderlineTrapezoidKeepsTheOrientationDecidedOnTheOutline() {
        // Sweep the top edge of an end-on trapezoid across the end-on threshold (top width under
        // 85 % of the bottom). Near it the nose quad and the cloth outline, which differs from it
        // by the cushions and the pocket cut-outs, fall on different sides of the threshold; the
        // orientation must still be the one decided on the outline, in every later step.
        var disagreements = 0
        for (topLeft in 120..170 step 2) {
            val raw = listOf(Vec2(topLeft.toDouble(), 45.0), Vec2(640.0 - topLeft - 40.0, 45.0), Vec2(600.0, 330.0), Vec2(50.0, 330.0))
            val (oriented, pose) = RawImageTableDetector.orientQuadForHomography(raw, table)
            val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
                layout = TableLayout(table, emptyList()),
                quadCornersPx = oriented,
                cameraPoseMeters = pose,
                cushions = SyntheticCushionStyle.SHADED,
            )
            val summary = RawImageTableDetector.analyzeArgbFrame(frame)
            val outlineSaysEndOn = RawImageTableDetector.isEndOnPerspectiveQuad(summary.outerQuadCornersPx)
            val noseSaysEndOn = RawImageTableDetector.isEndOnPerspectiveQuad(summary.detectedQuadCornersPx)
            assertEquals(outlineSaysEndOn, summary.endOnView, "top-left $topLeft: the decision is the one made on the outline")

            val q = summary.detectedQuadCornersPx
            val kept = RawImageTableDetector.orientQuadForHomography(q, table, summary.endOnView).first
            assertEquals(if (summary.endOnView) listOf(q[0], q[3], q[2], q[1]) else q, kept, "top-left $topLeft")
            if (outlineSaysEndOn != noseSaysEndOn) {
                disagreements++
                val redecided = RawImageTableDetector.orientQuadForHomography(q, table).first
                assertTrue(kept != redecided, "top-left $topLeft: a second decision on the nose quad would reorder the corners")
            }

            // A re-analysis with the quad handed back keeps the orientation as well.
            val again = RawImageTableDetector.analyzeArgbFrame(
                frame,
                quadOverridePx = q,
                previousQuads = summary.toQuadDetection(),
            )
            assertEquals(summary.endOnView, again.endOnView, "top-left $topLeft: re-analysis")
        }
        assertTrue(disagreements > 0, "the sweep never put the outline and the nose quad on different sides of the threshold")
    }

    @Test
    fun aLandscapeRenderExercisesTheLandscapeFallbackWithTheNearSideAtTheBottom() {
        val view = landscapeView
        val frame = view.render(cushions = SyntheticCushionStyle.CLOTH_MATCHED)
        val summary = RawImageTableDetector.analyzeArgbFrame(frame)
        assertTrue(!summary.endOnView)
        assertTrue(summary.quadEdges.all { it.usedFallback }, "cloth-matched cushions leave no edge to measure: ${summary.quadEdges}")
        val errors = outwardErrorsMeters(summary.detectedQuadCornersPx, view, table)
        // errors are head, foot, left (y = 0), right (y = W). The camera stands beyond the y = W rail,
        // which shows its top only (50 mm), the other long rail 65 mm: the fallback bands match, so
        // both long rails land within a few millimetres. A wrong near side would put one 15 mm out.
        assertTrue(abs(errors[2]) < 0.010, "far long rail ${errors[2] * 1000} mm out")
        assertTrue(abs(errors[3]) < 0.010, "near long rail ${errors[3] * 1000} mm out")
        // The short sides follow the outline, which the corner pockets pull inwards; the quad must
        // still be a sane one: convex, with the table centre well inside.
        assertTrue(RawImageTableDetector.pointInQuad(0.5 * (view.rawQuad[0].x + view.rawQuad[2].x), 0.5 * (view.rawQuad[0].y + view.rawQuad[2].y), summary.detectedQuadCornersPx, 40.0))
    }

    @Test
    fun theFallbackErrorIsWhatTheDifferenceFromTheRenderedWidthPredicts() {
        // The fallback places a side `nominal` in from the outer edge whatever the cushion is. Render
        // cushions wider and narrower than that: each side's error must move by exactly the
        // difference. The error at the nominal width is the outline's own offset on that side, so
        // the prediction is made on the change, and on the absolute error where the outline is true.
        val nominal = RawImageTableDetector.CUSHION_VISIBLE_BAND_METERS
        for (view in listOf(endOnView, landscapeView)) {
            fun errorsAt(width: Double): List<Double> {
                val frame = view.render(cushions = SyntheticCushionStyle.CLOTH_MATCHED, cushionWidth = width)
                val quads = RawImageTableDetector.detectTableQuads(frame, RawImageTableDetector.detectClothSurface(frame), table)
                assertTrue(quads.edges.all { it.usedFallback })
                return outwardErrorsMeters(quads.noseQuadCornersPx, view, table)
            }
            val atNominal = errorsAt(nominal)
            for (rendered in listOf(0.085, 0.055)) {
                val predicted = rendered - nominal
                val errors = errorsAt(rendered)
                for ((side, pair) in listOf("head", "foot", "left", "right").zip(errors.zip(atNominal))) {
                    val change = pair.first - pair.second
                    assertTrue(
                        abs(change - predicted) < 0.008,
                        "${view.name}, ${rendered * 1000} mm cushions: $side error changed by ${change * 1000} mm, predicted ${predicted * 1000} mm",
                    )
                }
            }
            // Where the outline is the cloth edge (the long rails of either view, the foot of the end-on
            // view) the nominal render is placed within a few millimetres in absolute terms.
            val trueSides = if (view.endOn) listOf(1, 2, 3) else listOf(2, 3)
            for (side in trueSides) {
                assertTrue(abs(atNominal[side]) < 0.010, "${view.name}: side $side is ${atNominal[side] * 1000} mm out at the nominal width")
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Mixed outcome, outliers, low contrast, border, resolution
    // -----------------------------------------------------------------------------------------

    @Test
    fun aMixedOutcomeKeepsFittedSidesAndPlacesTheOthersByTheNominalBand() {
        val view = endOnView
        val clean = view.render()
        // Flatten the cushion behind the y = 0 rail (left in the image) into the bed: that side has
        // no edge left to measure while the other three do.
        val bed = clothBedColor(clean)
        val painted = paint(clean, view, bed) { p -> p.y in -0.066..0.0 && p.x in 0.0..l }
        val quads = RawImageTableDetector.detectTableQuads(painted, RawImageTableDetector.detectClothSurface(painted), table)
        // Image side 3 runs BL to TL, the y = 0 rail in the end-on frame.
        assertTrue(quads.edges[3].usedFallback, "flattened side must fall back: ${quads.edges}")
        for (i in 0..2) assertTrue(!quads.edges[i].usedFallback, "side $i should keep its measured line: ${quads.edges}")
        assertTrue(quads.needsCornerReview)
        // Corners 1 and 2 touch only measured sides; corners 0 and 3 meet the side placed by the band.
        for (i in listOf(1, 2)) assertTrue(quads.noseQuadCornersPx[i].distanceTo(view.rawQuad[i]) < 3.0, "measured corner $i")
        for (i in listOf(0, 3)) assertTrue(quads.noseQuadCornersPx[i].distanceTo(view.rawQuad[i]) < 14.0, "mixed corner $i")
        // A sane quad: convex, inside the frame, and each corner inside the outline.
        for (corner in quads.noseQuadCornersPx) {
            assertTrue(corner.x in 0.0..640.0 && corner.y in 0.0..360.0)
            assertTrue(RawImageTableDetector.pointInQuad(corner.x, corner.y, quads.outerQuadCornersPx, -12.0))
        }
    }

    /** A bed pixel colour (the median of the central region) for repainting cushions into the bed. */
    private fun clothBedColor(frame: ArgbImageFrame): Int = frame.pixelAt(frame.width / 2, frame.height * 2 / 3)

    @Test
    fun theLineFitSurvivesBallsAndACueLyingOnTheRail() {
        val view = landscapeView
        // Balls in front of the far (y = 0) rail, close enough that their edge is the first step in
        // the cross-sections they cover, one ball frozen to it, and a cue lying along it across the nose.
        val layout = TableLayout(
            table,
            listOf(
                ball(2, 0.60, 0.075),
                ball(3, 0.95, 0.065),
                ball(5, 1.30, 0.080),
                ball(6, 1.62, r + 0.0005),
                ball(0, 1.20, 0.70),
            ),
        )
        val clean = view.render(layout)
        val cue = 0xFFB98A4C.toInt()
        val cloth = RawImageTableDetector.detectClothSurface(clean)
        val withBalls = RawImageTableDetector.detectTableQuads(clean, cloth, table)
        val withCue = paint(clean, view, cue) { p -> p.y in -0.010..0.012 && p.x in 1.90..2.30 }
        val withBoth = RawImageTableDetector.detectTableQuads(withCue, RawImageTableDetector.detectClothSurface(withCue), table)
        for ((label, quads) in listOf("balls" to withBalls, "balls and cue" to withBoth)) {
            assertQuadNear(quads.noseQuadCornersPx, view.rawQuad, 3.0, "$label on the rail")
            // Image side 0 is the y = 0 rail in landscape.
            assertTrue(!quads.edges[0].usedFallback, "$label must not push the far rail to the fallback: ${quads.edges[0]}")
        }
        // The balls and the cue really are outliers: the fit agrees on fewer sections than on a clean rail.
        val cleanFit = RawImageTableDetector.detectTableQuads(
            view.render(),
            RawImageTableDetector.detectClothSurface(view.render()),
            table,
        )
        assertTrue(
            withBoth.edges[0].confidence < cleanFit.edges[0].confidence,
            "outliers should cost confidence: ${withBoth.edges[0].confidence} vs ${cleanFit.edges[0].confidence}",
        )
    }

    @Test
    fun lowContrastCushionsAreStillFoundUntilTheStepDisappearsIntoTheNoise() {
        val view = endOnView
        val faint = view.render(contrast = 0.6)
        val found = RawImageTableDetector.detectTableQuads(faint, RawImageTableDetector.detectClothSurface(faint), table)
        assertQuadNear(found.noseQuadCornersPx, view.rawQuad, 3.0, "contrast 0.6")
        assertTrue(!found.needsCornerReview, "contrast 0.6 should still be measured: ${found.edges}")

        val flat = view.render(contrast = 0.2)
        val fellBack = RawImageTableDetector.detectTableQuads(flat, RawImageTableDetector.detectClothSurface(flat), table)
        assertTrue(fellBack.needsCornerReview, "contrast 0.2 is below the step threshold: ${fellBack.edges}")
        assertQuadNear(fellBack.noseQuadCornersPx, view.rawQuad, 10.0, "contrast 0.2")
    }

    @Test
    fun aQuadTouchingTheImageBorderStillGivesAFiniteConvexNoseQuad() {
        // The far rail is on the first image row and the near rail on the last: the cloth outline is
        // clipped by the frame and the cushion bands run off it.
        val raw = listOf(Vec2(215.0, 2.0), Vec2(465.0, 2.0), Vec2(560.0, 357.0), Vec2(110.0, 357.0))
        val (oriented, pose) = RawImageTableDetector.orientQuadForHomography(raw, table)
        val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
            layout = TableLayout(table, listOf(ball(0, 1.0, 0.6), ball(3, l - r - 0.0005, 0.3))),
            quadCornersPx = oriented,
            cameraPoseMeters = pose,
            ballRadiusPx = 7,
            cushions = SyntheticCushionStyle.SHADED,
        )
        val summary = RawImageTableDetector.analyzeArgbFrame(frame)
        val nose = summary.detectedQuadCornersPx
        assertEquals(4, nose.size)
        assertTrue(nose.all { it.x.isFinite() && it.y.isFinite() })
        assertTrue(nose.all { it.x in -1.0..641.0 && it.y in -1.0..361.0 }, "corners left the frame: $nose")
        assertTrue(
            RawImageTableDetector.pointInQuad(320.0, 180.0, nose, 20.0),
            "the table centre must be inside the nose quad: $nose",
        )
        assertEquals(4, summary.quadEdges.size)
        // The ball on the near rail is cut by the frame and is not ball-like on the band; the open one is found.
        assertTrue(summary.detectedCandidates.isNotEmpty(), "the open ball is on the cloth")
        // Where the cushion runs off the frame the measured edge is not trusted without a cushion
        // behind it; whichever way each side went, the corners stay inside the outline's neighbourhood.
        for (i in 0 until 4) {
            assertTrue(
                nose[i].distanceTo(summary.outerQuadCornersPx[i]) < 90.0,
                "corner $i drifted ${nose[i].distanceTo(summary.outerQuadCornersPx[i])} px from the outline",
            )
        }
    }

    @Test
    fun aLargeFrameWidensTheProfileWindowAndStillFindsTheNose() {
        // Frames above 1000 px on the short side take the wider profile step and window.
        val scale = 2.9
        val view = View(
            "large landscape",
            landscapeView.rawQuad.map { Vec2(it.x * scale, it.y * scale) },
            landscapeView.pose,
            ballRadiusPx = 14,
            endOn = false,
        )
        val width = (640 * scale).toInt()
        val height = (360 * scale).toInt()
        assertTrue(minOf(width, height) > 1000)
        val frame = view.render(width = width, height = height)
        val quads = RawImageTableDetector.detectTableQuads(frame, RawImageTableDetector.detectClothSurface(frame), table)
        // The long rails are measured with the widened profile (image side 0 and 2 in landscape);
        // the short sides of this synthetic frame fall back and are flagged.
        assertTrue(!quads.edges[0].usedFallback && !quads.edges[2].usedFallback, "large frame: ${quads.edges}")
        assertTrue(quads.needsCornerReview)
        // The fallback short sides are as far off as in the small frame (about 20 px there), scaled up.
        assertQuadNear(quads.noseQuadCornersPx, view.rawQuad, 24.0 * scale, "large frame")
    }

    // -----------------------------------------------------------------------------------------
    // Corner conditioning
    // -----------------------------------------------------------------------------------------

    @Test
    fun cornerBoundsRejectNearlyParallelSidesAndImplausibleShifts() {
        assertTrue(RawImageTableDetector.isCornerWellConditioned(Vec2(1.0, 0.0), Vec2(0.0, 1.0)))
        assertTrue(RawImageTableDetector.isCornerWellConditioned(Vec2(1.0, 0.0), Vec2(0.6, 0.8)))
        // 8 degrees between the two sides: |sin| = 0.139, below the 0.2 limit.
        val eightDegrees = 8.0 * PI / 180.0
        val eight = Vec2(cos(eightDegrees), sin(eightDegrees))
        assertTrue(!RawImageTableDetector.isCornerWellConditioned(Vec2(1.0, 0.0), eight))
        assertTrue(!RawImageTableDetector.isCornerWellConditioned(Vec2(1.0, 0.0), Vec2(-1.0, 0.0)))

        assertTrue(RawImageTableDetector.isCornerShiftPlausible(Vec2(0.065, -0.065)))
        assertTrue(RawImageTableDetector.isCornerShiftPlausible(Vec2(0.20, 0.20)))
        assertTrue(!RawImageTableDetector.isCornerShiftPlausible(Vec2(0.31, 0.0)))
        assertTrue(!RawImageTableDetector.isCornerShiftPlausible(Vec2(0.0, Double.NaN)))
    }

    // -----------------------------------------------------------------------------------------
    // Position stage
    // -----------------------------------------------------------------------------------------

    private fun candidate(id: Int, color: BallPaletteColor, whiteFraction: Double, at: Vec2) = DetectedBallCandidate(
        id = id,
        imageCenterPx = at,
        dominantColor = color,
        whiteFraction = whiteFraction,
    )

    /** Top-down style mapping, so table metres map linearly to pixels (camera very high). */
    private val topDown = PerspectiveHomography.fromTableQuad(
        listOf(
            Vec2(100.0, 100.0),
            Vec2(100.0 + 500.0 * l, 100.0),
            Vec2(100.0 + 500.0 * l, 100.0 + 500.0 * w),
            Vec2(100.0, 100.0 + 500.0 * w),
        ),
        table,
    )
    private val topDownCamera = Vec3(0.5 * l, 0.5 * w, 40.0)

    private fun px(x: Double, y: Double) = Vec2(100.0 + 500.0 * x, 100.0 + 500.0 * y)

    private fun rectify(vararg at: Vec2) = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
        candidates = at.mapIndexed { i, p ->
            candidate(i + 1, listOf(BallPaletteColor.YELLOW, BallPaletteColor.BLUE, BallPaletteColor.RED, BallPaletteColor.GREEN)[i % 4], 0.05, px(p.x, p.y))
        },
        homography = topDown,
        table = table,
        cameraPositionMeters = topDownCamera,
    )

    @Test
    fun aCentreInsideTheNoseOrWithinTheNoiseMarginIsFrozenToTheRailAndOneFurtherOutIsRejected() {
        val margin = BallIdentificationAndAssignment.RAIL_NOISE_MARGIN_FRACTION * r
        val result = rectify(
            Vec2(1.0, 0.60), // open table
            Vec2(0.010, 0.40), // inside the nose but less than a radius from it: against the rail
            Vec2(-0.5 * margin, 0.90), // just outside, within the noise margin: against the rail
            Vec2(1.5, -2.0 * margin), // outside by twice the margin
            Vec2(2.0, w + 0.5 * r), // outside by half a radius, as a hand on the rail would be
        )
        assertEquals(listOf(4, 5), result.rejectedCandidateIds)
        assertEquals(listOf(1, 2, 3), result.identifiedBalls.map { it.candidateId })
        for (id in listOf(2, 3)) {
            val ball = result.identifiedBalls.first { it.candidateId == id }.ball
            assertEquals(r, ball.position.x, 1e-9, "candidate $id is moved against the head rail")
            assertNotNull(result.frozenBalls.firstOrNull { it.ballNumber == ball.number && it.frozenToCushion })
        }
        assertTrue(result.frozenBalls.none { it.ballNumber == result.identifiedBalls.first { c -> c.candidateId == 1 }.ball.number && it.frozenToCushion })
    }

    @Test
    fun pocketOpeningsAreRejectedWhileBallsHangingAtTheJawsAreKept() {
        val sideMouth = Vec2(0.5 * l, 0.010) // past the side pocket's capture line, between the jaw tips
        val cornerMouth = Vec2(-0.003, -0.003) // just outside the nose, in the corner mouth
        val overHole = Vec2(0.5 * l, -0.045) // over the side pocket's hole
        val rejected = rectify(sideMouth, cornerMouth, overHole)
        assertEquals(listOf(1, 2, 3), rejected.rejectedCandidateIds)
        assertTrue(rejected.layout.balls.isEmpty())

        val hanging = rectify(
            Vec2(0.5 * l, r), // frozen against the side pocket's mouth line
            Vec2(0.060, 0.060), // at the corner pocket's jaws, on the table side of the mouth line
            Vec2(0.5 * l + 0.09, r), // beside the side mouth, against the cushion
            Vec2(0.5 * l + 0.02, r - 0.004), // 4 mm past the side mouth line, as a ball sitting at the jaws reads
            Vec2(l - 0.033, w - 0.041), // 5 mm past the corner mouth line, at the jaws
        )
        assertTrue(hanging.rejectedCandidateIds.isEmpty(), "rejected: ${hanging.rejectedCandidateIds}")
        assertEquals(5, hanging.layout.balls.size)
    }

    // -----------------------------------------------------------------------------------------
    // Hands on the rail and pocket openings in renders
    // -----------------------------------------------------------------------------------------

    private val skin = 0xFFD9A47F.toInt()

    @Test
    fun aHandLyingOnTheRailNearACornerIsNotABallButAFrozenBallBesideItStillIs() {
        // A 260 mm x 70 mm skin-coloured strip along the y = 0 cushion, starting at the head corner,
        // and a ball frozen to the same cushion 120 mm past its end.
        val hand = RawImageTableDetector.SyntheticTableObject(Vec2(0.22, -0.025), 0.26, 0.07, 0.0, skin)
        val layout = TableLayout(table, listOf(ball(0, 1.0, 0.60), ball(2, 0.45, r + 0.0005)))
        for (view in listOf(endOnView, landscapeView)) {
            val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
                layout = layout,
                quadCornersPx = view.homographyQuad(),
                cameraPoseMeters = view.pose,
                ballRadiusPx = view.ballRadiusPx,
                cushions = SyntheticCushionStyle.SHADED,
                objects = listOf(hand),
            )
            val summary = RawImageTableDetector.analyzeArgbFrame(frame)
            val found = detectedPositions(summary, view)
            assertTrue(
                errorsTo(layout, found).all { it < 0.03 },
                "${view.name}: the frozen ball beside the hand must be found: $found",
            )
            // Seen along the table (end-on) the strip is foreshortened to a near-square blob, so only the
            // landscape view can tell it by shape; in both views its corrected position, outside the
            // nose line, rules it out.
            val handCandidates = found.filter { it.distanceTo(hand.centerMeters) < 0.16 }
            if (!view.endOn) assertTrue(handCandidates.isEmpty(), "${view.name}: the hand was found as a ball: $handCandidates")

            val result = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
                candidates = summary.detectedCandidates,
                homography = PerspectiveHomography.fromTableQuad(
                    RawImageTableDetector.orientQuadForHomography(summary.detectedQuadCornersPx, table, summary.endOnView).first,
                    table,
                ),
                table = table,
                cameraPositionMeters = view.pose,
            )
            assertEquals(layout.balls.size, result.layout.balls.size, "${view.name}: balls reported")
            assertEquals(handCandidates.size, result.rejectedCandidateIds.size, "${view.name}: the hand is rejected by its position")
        }
    }

    @Test
    fun pocketOpeningsDrawnInTheRenderAreNotBallsAndABallInTheCornerJawsIs() {
        val empty = TableLayout(table, emptyList())
        val jaws = TableLayout(table, listOf(ball(3, 0.075, 0.075), ball(0, 1.0, 0.60)))
        for (view in listOf(endOnView, landscapeView)) {
            val bare = RawImageTableDetector.analyzeArgbFrame(view.render(empty))
            assertEquals(0, bare.detectedCandidates.size, "${view.name}: a pocket opening was found as a ball")

            val summary = RawImageTableDetector.analyzeArgbFrame(view.render(jaws))
            val found = detectedPositions(summary, view)
            assertEquals(jaws.balls.size, found.size, "${view.name}: $found")
            assertTrue(errorsTo(jaws, found).all { it < 0.04 }, "${view.name}: ${errorsTo(jaws, found)}")
        }
    }
}
