package com.runout.engine

import com.runout.engine.calibration.TrajectoryEventType
import com.runout.engine.math.Vec2
import com.runout.engine.model.Ball
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import com.runout.engine.vision.ArgbImageFrame
import com.runout.engine.vision.BallIdentificationAndAssignment
import com.runout.engine.vision.ClothHueCategory
import com.runout.engine.vision.PerspectiveHomography
import com.runout.engine.vision.RawFrameDetectionSummary
import com.runout.engine.vision.RawImageTableDetector
import com.runout.engine.vision.RawImageTableDetector.SyntheticCushionStyle
import com.runout.engine.vision.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RawImageTableDetectorTest {

    private val table = TableSpec.nineFootPool()
    private val camera = Vec3(table.lengthMeters * 0.5, table.widthMeters * 0.5, 2.20)

    /** Landscape quad `[headLeft, footLeft, footRight, headRight]` = `[TL, TR, BR, BL]`. */
    private val angledQuad = listOf(
        Vec2(150.0, 60.0),
        Vec2(500.0, 90.0),
        Vec2(600.5, 335.0),
        Vec2(40.0, 300.5),
    )

    private val trapezoidQuad = listOf(
        Vec2(90.0, 45.0),
        Vec2(545.0, 52.0),
        Vec2(610.0, 330.0),
        Vec2(30.0, 322.0),
    )

    private val greenGameQuad = listOf(
        Vec2(120.0, 60.0),
        Vec2(520.0, 48.0),
        Vec2(615.0, 315.0),
        Vec2(25.0, 335.0),
    )

    private fun ball(number: Int, x: Double, y: Double) = Ball(number, Vec2(x, y), table.ballRadiusMeters)

    private fun nineBallLayout() = TableLayout(
        table,
        listOf(
            ball(0, 0.60, 0.40),
            ball(1, 1.00, 0.30),
            ball(2, 1.00, 0.80),
            ball(3, 1.35, 0.55),
            ball(4, 1.65, 0.25),
            ball(5, 1.70, 0.95),
            ball(6, 1.95, 0.60),
            ball(7, 2.25, 0.35),
            ball(8, 2.20, 0.90),
            ball(9, 1.40, 1.05),
        ),
    )

    private fun tvEightBallLayout() = TableLayout(
        table,
        listOf(
            ball(0, 0.55, 0.35),
            ball(1, 0.95, 0.25),
            ball(3, 0.95, 0.80),
            ball(4, 1.30, 0.50),
            ball(7, 1.65, 0.25),
            ball(8, 1.65, 0.80),
            ball(9, 2.00, 0.45),
            ball(11, 2.05, 1.00),
            ball(12, 1.30, 1.00),
            ball(15, 2.25, 0.25),
        ),
    )

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else 0.5 * (sorted[n / 2 - 1] + sorted[n / 2])
    }

    private fun assertRoundTrip(
        layout: TableLayout,
        quad: List<Vec2>,
        cloth: ClothHueCategory,
        ballSet: BallSetProfile,
    ) {
        val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
            layout = layout,
            quadCornersPx = quad,
            cameraPoseMeters = camera,
            clothCategory = cloth,
            ballSet = ballSet,
            cushions = SyntheticCushionStyle.SHADED,
        )
        val summary = RawImageTableDetector.analyzeArgbFrame(frame)
        assertEquals(cloth, summary.clothSurface.category)
        assertEquals(layout.balls.size, summary.detectedCandidates.size, "Every ball must be found as one blob.")

        val homography = PerspectiveHomography.fromTableQuad(summary.detectedQuadCornersPx, table)
        val result = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = summary.detectedCandidates,
            homography = homography,
            table = table,
            ballSet = ballSet,
            cameraPositionMeters = camera,
        )

        val expected = layout.balls.associateBy { it.number }
        val recovered = result.layout.balls.associateBy { it.number }
        assertEquals(expected.keys, recovered.keys, "All ball identities must be recovered.")

        val errors = expected.map { (number, truth) ->
            val found = recovered.getValue(number)
            found.position.distanceTo(truth.position)
        }
        val medianError = median(errors)
        assertTrue(medianError < 0.01, "Median position error was $medianError m")
        assertTrue(errors.all { it < 0.03 }, "Worst position error was ${errors.maxOf { it }} m")
    }

    @Test
    fun rgbToHsvConvertsPrimariesGreysAndClothColours() {
        val red = RawImageTableDetector.rgbToHsv(0xFFFF0000.toInt())
        assertEquals(0.0, red.first, 1e-9)
        assertEquals(1.0, red.second, 1e-9)
        assertEquals(1.0, red.third, 1e-9)

        val green = RawImageTableDetector.rgbToHsv(0xFF00FF00.toInt())
        assertEquals(120.0, green.first, 1e-9)

        val blue = RawImageTableDetector.rgbToHsv(0xFF0000FF.toInt())
        assertEquals(240.0, blue.first, 1e-9)

        val magenta = RawImageTableDetector.rgbToHsv(0xFFFF00FF.toInt())
        assertEquals(300.0, magenta.first, 1e-9)

        val white = RawImageTableDetector.rgbToHsv(0xFFFFFFFF.toInt())
        assertEquals(0.0, white.second, 1e-9)
        assertEquals(1.0, white.third, 1e-9)

        val black = RawImageTableDetector.rgbToHsv(0xFF000000.toInt())
        assertEquals(0.0, black.third, 1e-9)

        val clothBlue = RawImageTableDetector.rgbToHsv(0xFF1E78B4.toInt())
        assertTrue(clothBlue.first in 195.0..215.0, "hue was ${clothBlue.first}")
        assertTrue(clothBlue.second in 0.75..0.90)

        // Hue wraps into 0..360 for reddish colours with blue above green.
        val crimson = RawImageTableDetector.rgbToHsv(0xFFDC143C.toInt())
        assertTrue(crimson.first in 340.0..350.0, "hue was ${crimson.first}")
    }

    @Test
    fun detectClothSurfaceClassifiesAllFourClothCategories() {
        val expectedHueRange = mapOf(
            ClothHueCategory.BLUE to 175.0..255.0,
            ClothHueCategory.GREEN to 75.0..175.0,
        )
        for (category in ClothHueCategory.values()) {
            val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
                layout = TableLayout(table, emptyList()),
                quadCornersPx = trapezoidQuad,
                cameraPoseMeters = camera,
                clothCategory = category,
            )
            val cloth = RawImageTableDetector.detectClothSurface(frame)
            assertEquals(category, cloth.category, "Wrong category for $category: $cloth")
            assertTrue(
                cloth.clothCoverageFraction in 0.45..0.95,
                "Coverage for $category was ${cloth.clothCoverageFraction}",
            )
            when (category) {
                ClothHueCategory.RED -> assertTrue(
                    cloth.dominantHueDegrees <= 35.0 || cloth.dominantHueDegrees >= 325.0,
                )
                ClothHueCategory.GREY -> assertTrue(cloth.meanSaturation < 0.22)
                else -> assertTrue(cloth.dominantHueDegrees in expectedHueRange.getValue(category))
            }
            if (category != ClothHueCategory.GREY) {
                assertTrue(cloth.meanSaturation > 0.5, "Saturation for $category was ${cloth.meanSaturation}")
            }
        }
    }

    @Test
    fun detectClothSurfaceIgnoresBallsAndStillFindsCloth() {
        val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
            layout = nineBallLayout(),
            quadCornersPx = angledQuad,
            cameraPoseMeters = camera,
            clothCategory = ClothHueCategory.GREEN,
        )
        val cloth = RawImageTableDetector.detectClothSurface(frame)
        assertEquals(ClothHueCategory.GREEN, cloth.category)
        assertTrue(RawImageTableDetector.isClothPixel(frame.pixelAt(300, 200), cloth))
        assertTrue(!RawImageTableDetector.isClothPixel(frame.pixelAt(2, 2), cloth), "Rail must not be cloth")
    }

    @Test
    fun detectOuterClothQuadRecoversPerspectiveCornersWithinFourPixels() {
        for (quad in listOf(angledQuad, trapezoidQuad, greenGameQuad)) {
            val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
                layout = nineBallLayout(),
                quadCornersPx = quad,
                cameraPoseMeters = camera,
            )
            val cloth = RawImageTableDetector.detectClothSurface(frame)
            val detected = RawImageTableDetector.detectOuterClothQuadCornersPx(frame, cloth)
            assertEquals(4, detected.size)
            for (i in 0 until 4) {
                val error = detected[i].distanceTo(quad[i])
                assertTrue(error < 4.0, "Corner $i error $error px: ${detected[i]} vs ${quad[i]}")
            }
        }
    }

    @Test
    fun detectTableQuadCornersRecoversNoseCornersWithinFourPixels() {
        for (quad in listOf(angledQuad, trapezoidQuad, greenGameQuad)) {
            val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
                layout = nineBallLayout(),
                quadCornersPx = quad,
                cameraPoseMeters = camera,
                cushions = SyntheticCushionStyle.SHADED,
            )
            val cloth = RawImageTableDetector.detectClothSurface(frame)
            val detected = RawImageTableDetector.detectTableQuadCornersPx(frame, cloth)
            assertEquals(4, detected.size)
            for (i in 0 until 4) {
                val error = detected[i].distanceTo(quad[i])
                assertTrue(error < 4.0, "Corner $i error $error px: ${detected[i]} vs ${quad[i]}")
            }
        }
    }

    /** End-on broadcast-style view rendered from the default pose that orientation assumes. */
    private val endOnRawQuad = listOf(
        Vec2(210.0, 40.0),
        Vec2(470.0, 40.0),
        Vec2(560.0, 330.0),
        Vec2(110.0, 330.0),
    )

    /** Landscape view from the head end, `[TL, TR, BR, BL]`. */
    private val landscapeRawQuad = listOf(
        Vec2(90.0, 70.0),
        Vec2(550.0, 70.0),
        Vec2(590.0, 300.0),
        Vec2(50.0, 300.0),
    )

    /** Ball 2 frozen to the `y = W` rail; balls 0, 3 and 9 in open table. */
    private fun frozenToRightRailLayout() = TableLayout(
        table,
        listOf(
            ball(0, 0.80, 0.50),
            ball(2, 1.40, table.widthMeters - table.ballRadiusMeters - 0.0005),
            ball(3, 1.70, 0.80),
            ball(9, 1.20, 0.70),
        ),
    )

    private fun renderAtDefaultPose(
        rawQuad: List<Vec2>,
        cloth: ClothHueCategory,
        cushions: SyntheticCushionStyle,
    ) = RawImageTableDetector.orientQuadForHomography(rawQuad, table).let { (oriented, pose) ->
        RawImageTableDetector.renderSyntheticTablePhotoArgb(
            layout = frozenToRightRailLayout(),
            quadCornersPx = oriented,
            cameraPoseMeters = pose,
            clothCategory = cloth,
            ballRadiusPx = 9,
            cushions = cushions,
        )
    }

    /** Distance from the `y = W` cushion nose to the detected ball nearest ball 2, in metres. */
    private fun frozenBallDistanceFromRightNose(summary: RawFrameDetectionSummary): Double {
        val (oriented, pose) = RawImageTableDetector.orientQuadForHomography(summary.detectedQuadCornersPx, table)
        val homography = PerspectiveHomography.fromTableQuad(oriented, table)
        val truth = frozenToRightRailLayout().balls.first { it.number == 2 }.position
        val mapped = summary.detectedCandidates
            .map { homography.imageToBallCenterMeters(it.imageCenterPx, table.ballRadiusMeters, pose) }
            .minByOrNull { it.distanceTo(truth) }
        assertNotNull(mapped, "The frozen ball must be detected")
        return table.widthMeters - mapped.y
    }

    @Test
    fun noseQuadSitsAtTheCushionNosesNotAtTheClothCoveredCushionBacks() {
        for (rawQuad in listOf(endOnRawQuad, landscapeRawQuad)) {
            for (cloth in ClothHueCategory.values()) {
                val frame = renderAtDefaultPose(rawQuad, cloth, SyntheticCushionStyle.SHADED)
                val summary = RawImageTableDetector.analyzeArgbFrame(frame)
                val label = "${if (rawQuad === endOnRawQuad) "end-on" else "landscape"} $cloth"
                for (i in 0 until 4) {
                    val noseError = summary.detectedQuadCornersPx[i].distanceTo(rawQuad[i])
                    val outerError = summary.outerQuadCornersPx[i].distanceTo(rawQuad[i])
                    assertTrue(noseError < 2.0, "$label nose corner $i off by $noseError px")
                    // The cushions pass the cloth test, so the cloth outline is the cushion backs.
                    assertTrue(outerError > 8.0, "$label outer corner $i only $outerError px out")
                }
                assertEquals(4, summary.quadEdges.size)
                assertTrue(!summary.quadNeedsCornerReview, "$label: no side should need the fallback")
                assertTrue(summary.quadEdges.all { it.confidence >= 0.8 }, "$label confidences ${summary.quadEdges}")

                val distance = frozenBallDistanceFromRightNose(summary)
                assertTrue(
                    abs(distance - table.ballRadiusMeters) < 0.005,
                    "$label frozen ball is ${distance * 1000} mm from the nose",
                )
            }
        }
    }

    @Test
    fun cushionsMatchingTheBedFallBackToTheCushionWidthAndAskForReview() {
        val frame = renderAtDefaultPose(endOnRawQuad, ClothHueCategory.BLUE, SyntheticCushionStyle.CLOTH_MATCHED)
        val summary = RawImageTableDetector.analyzeArgbFrame(frame)
        assertTrue(summary.quadNeedsCornerReview)
        assertTrue(summary.quadEdges.all { it.usedFallback && it.confidence < 0.5 }, "edges ${summary.quadEdges}")
        for (i in 0 until 4) {
            val error = summary.detectedQuadCornersPx[i].distanceTo(endOnRawQuad[i])
            assertTrue(error < 10.0, "Fallback corner $i off by $error px")
            assertTrue(summary.quadEdges[i].insetFromOuterPx > 2.0, "Side $i was not inset: ${summary.quadEdges[i]}")
        }
        val distance = frozenBallDistanceFromRightNose(summary)
        assertTrue(
            abs(distance - table.ballRadiusMeters) < 0.015,
            "Fallback frozen ball is ${distance * 1000} mm from the nose",
        )
    }

    @Test
    fun clothMeetingTheRailWithoutAVisibleCushionIsTreatedAsHiddenCushions() {
        val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
            layout = nineBallLayout(),
            quadCornersPx = trapezoidQuad,
            cameraPoseMeters = camera,
        )
        val cloth = RawImageTableDetector.detectClothSurface(frame)
        val quads = RawImageTableDetector.detectTableQuads(frame, cloth)
        assertTrue(quads.needsCornerReview)
        for (i in 0 until 4) {
            assertTrue(quads.outerQuadCornersPx[i].distanceTo(trapezoidQuad[i]) < 4.0)
            assertTrue(
                RawImageTableDetector.pointInQuad(quads.noseQuadCornersPx[i].x, quads.noseQuadCornersPx[i].y, trapezoidQuad, 2.0),
                "Fallback corner $i must lie inside the cloth outline: ${quads.noseQuadCornersPx[i]}",
            )
        }
    }

    @Test
    fun detectTableQuadCornersFallsBackToInsetDefaultWithoutCloth() {
        val rail = 0xFF2B1B12.toInt()
        val frame = ArgbImageFrame(640, 360, IntArray(640 * 360) { rail })
        val cloth = RawImageTableDetector.detectClothSurface(frame)
        val corners = RawImageTableDetector.detectTableQuadCornersPx(frame, cloth)
        assertEquals(
            listOf(
                Vec2(0.12 * 640, 0.18 * 360),
                Vec2(0.88 * 640, 0.18 * 360),
                Vec2(0.88 * 640, 0.85 * 360),
                Vec2(0.12 * 640, 0.85 * 360),
            ),
            corners,
        )
    }

    @Test
    fun analyzeArgbFrameReportsQualityMetricsAndBallBlobs() {
        val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
            layout = nineBallLayout(),
            quadCornersPx = trapezoidQuad,
            cameraPoseMeters = camera,
        )
        val summary = RawImageTableDetector.analyzeArgbFrame(frame)
        assertEquals(640, summary.width)
        assertEquals(360, summary.height)
        assertEquals(10, summary.detectedCandidates.size)
        assertTrue(summary.sharpnessScore >= 0.70, "sharpness ${summary.sharpnessScore}")
        assertTrue(summary.glareFraction < 0.02, "glare ${summary.glareFraction}")
        assertTrue(summary.meanLuma in 20.0..200.0, "luma ${summary.meanLuma}")

        val cue = summary.detectedCandidates.filter { it.whiteFraction >= 0.82 }
        assertEquals(1, cue.size)
        val stripes = summary.detectedCandidates.count { it.whiteFraction in 0.35..0.75 }
        assertEquals(1, stripes, "Only the 9-ball is a stripe")

        val flat = ArgbImageFrame(64, 48, IntArray(64 * 48) { 0xFF808080.toInt() })
        val flatSummary = RawImageTableDetector.analyzeArgbFrame(flat)
        assertTrue(flatSummary.sharpnessScore < 0.05, "flat sharpness ${flatSummary.sharpnessScore}")
    }

    @Test
    fun angledNineBallPixelRoundTripRecoversIdentitiesAndPositionsOnBlueCloth() {
        assertRoundTrip(
            layout = nineBallLayout(),
            quad = angledQuad,
            cloth = ClothHueCategory.BLUE,
            ballSet = BallSetProfile.standardPool(),
        )
    }

    @Test
    fun tvTournamentEightBallPixelRoundTripOnGreenCloth() {
        assertRoundTrip(
            layout = tvEightBallLayout(),
            quad = greenGameQuad,
            cloth = ClothHueCategory.GREEN,
            ballSet = BallSetProfile.tvTournamentPool(),
        )
    }

    @Test
    fun broadcastBlackSetAndEndOnOrientationRoundTrip() {
        assertRoundTrip(
            layout = nineBallLayout(),
            quad = trapezoidQuad,
            cloth = ClothHueCategory.GREY,
            ballSet = BallSetProfile.broadcastBlackPool(),
        )
        val endOnQuad = listOf(
            Vec2(210.0, 20.0),
            Vec2(515.0, 20.0),
            Vec2(615.0, 325.0),
            Vec2(110.0, 325.0),
        )
        assertTrue(RawImageTableDetector.isEndOnPerspectiveQuad(endOnQuad))
        assertTrue(!RawImageTableDetector.isEndOnPerspectiveQuad(trapezoidQuad))
        val (oriented, pose) = RawImageTableDetector.orientQuadForHomography(endOnQuad, table)
        assertEquals(listOf(endOnQuad[0], endOnQuad[3], endOnQuad[2], endOnQuad[1]), oriented)
        assertTrue(pose.x > table.lengthMeters)
    }

    @Test
    fun ukBlackballInferenceAndLowAngleRedClothQuadRecoverBalls() {
        val inferred = BallSetProfile.inferFromDetectedColors(
            listOf(
                com.runout.engine.model.BallPaletteColor.WHITE,
                com.runout.engine.model.BallPaletteColor.YELLOW,
                com.runout.engine.model.BallPaletteColor.YELLOW,
                com.runout.engine.model.BallPaletteColor.YELLOW,
                com.runout.engine.model.BallPaletteColor.RED,
                com.runout.engine.model.BallPaletteColor.RED,
                com.runout.engine.model.BallPaletteColor.RED,
                com.runout.engine.model.BallPaletteColor.BLACK,
            ),
        )
        assertEquals("UK Blackball", inferred.name)
        assertEquals(com.runout.engine.model.BallPaletteColor.YELLOW, inferred.colorForBall(4))
        assertEquals(com.runout.engine.model.BallPaletteColor.RED, inferred.colorForBall(12))

        val steepRedQuad = listOf(
            Vec2(140.0, 75.0),
            Vec2(430.0, 82.0),
            Vec2(610.0, 260.0),
            Vec2(45.0, 260.0),
        )
        val layout = TableLayout(
            table = table,
            balls = listOf(
                ball(0, 1.15, 0.62),
                ball(4, 1.00, 0.35),
                ball(5, 1.85, 0.78),
                ball(8, 1.55, 0.42),
            ),
        )
        val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
            layout = layout,
            quadCornersPx = steepRedQuad,
            cameraPoseMeters = camera,
            clothCategory = ClothHueCategory.RED,
            ballSet = BallSetProfile.standardPool(),
        )
        val summary = RawImageTableDetector.analyzeArgbFrame(frame)
        assertEquals(ClothHueCategory.RED, summary.clothSurface.category)
        assertEquals(4, summary.outerQuadCornersPx.size)
        for (i in 0 until 4) {
            assertTrue(
                summary.outerQuadCornersPx[i].distanceTo(steepRedQuad[i]) < 5.0,
                "Steep red corner $i mismatch: ${summary.outerQuadCornersPx[i]} vs ${steepRedQuad[i]}",
            )
        }
        assertEquals(4, summary.detectedCandidates.size)
    }

    @Test
    fun trackBallInArgbVideoFramesRecoversTrajectoryAndEventsFromPixels() {
        val fps = 60.0
        val dt = 1.0 / fps
        val g = 9.80665
        val muS = 0.20
        val muR = 0.028
        val ec = 0.70

        val start = Vec2(0.80, 0.40)
        val dir = Vec2(0.98, 0.199).normalized()
        val v0 = 1.75

        val aSlide = muS * g
        val tSlide = (2.0 * v0) / (7.0 * muS * g)
        val vRoll0 = (5.0 / 7.0) * v0
        val sSlide = 0.5 * (v0 + vRoll0) * tSlide

        val contactX = table.lengthMeters - 0.05
        val sToCushion = (contactX - start.x) / dir.x
        val sRollBeforeBounce = sToCushion - sSlide
        val aRoll = muR * g
        val vImpact = sqrt(vRoll0 * vRoll0 - 2.0 * aRoll * sRollBeforeBounce)
        val dtRoll1 = (vRoll0 - vImpact) / aRoll
        val tBounce = tSlide + dtRoll1
        val bouncePos = start + dir * sToCushion

        val vIn = dir * vImpact
        val vOut = Vec2(-ec * vIn.x, 0.82 * vIn.y)
        val speedAfterBounce = vOut.length()
        val outDir = vOut / speedAfterBounce
        val dtRoll2 = speedAfterBounce / aRoll
        val tStop = tBounce + dtRoll2

        val frameCount = ((tStop + 0.25) * fps).toInt()
        val truePositions = ArrayList<Vec2>(frameCount)
        val frames = ArrayList<Pair<Double, ArgbImageFrame>>(frameCount)
        for (i in 0 until frameCount) {
            val t = i * dt
            val pos = when {
                t <= tSlide -> start + dir * (v0 * t - 0.5 * aSlide * t * t)
                t <= tBounce -> {
                    val tr = t - tSlide
                    start + dir * (sSlide + vRoll0 * tr - 0.5 * aRoll * tr * tr)
                }
                t <= tStop -> {
                    val tb = t - tBounce
                    bouncePos + outDir * (speedAfterBounce * tb - 0.5 * aRoll * tb * tb)
                }
                else -> bouncePos + outDir * (0.5 * speedAfterBounce * dtRoll2)
            }
            truePositions += pos
            val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
                layout = TableLayout(table, listOf(Ball(0, pos, table.ballRadiusMeters))),
                quadCornersPx = trapezoidQuad,
                cameraPoseMeters = camera,
                ballRadiusPx = 7,
            )
            frames += t to frame
        }

        val tracked = RawImageTableDetector.trackBallInArgbVideoFrames(
            frames = frames,
            quadCornersPx = trapezoidQuad,
            table = table,
            cameraPoseMeters = camera,
        )

        assertEquals(frameCount, tracked.points.size, "Cue ball must be found in every frame")
        assertEquals(128.0, tracked.normalizedLumaSamples.average(), 1e-6)

        var sq = 0.0
        for (i in truePositions.indices) {
            sq += tracked.points[i].positionMeters.distanceSquaredTo(truePositions[i])
        }
        val rms = sqrt(sq / truePositions.size)
        assertTrue(rms < 0.008, "Pixel-tracked RMS position error was $rms m")

        val slideToRoll = tracked.events.firstOrNull { it.type == TrajectoryEventType.SLIDE_TO_ROLL_TRANSITION }
        val bounce = tracked.events.firstOrNull { it.type == TrajectoryEventType.CUSHION_BOUNCE }
        val stop = tracked.events.firstOrNull { it.type == TrajectoryEventType.BALL_STOP }
        assertNotNull(slideToRoll, "Expected slide-to-roll in ${tracked.events}")
        assertNotNull(bounce, "Expected cushion bounce in ${tracked.events}")
        assertNotNull(stop, "Expected ball stop in ${tracked.events}")

        assertTrue(abs(slideToRoll.timestampSeconds - tSlide) < 0.15)
        assertTrue(abs(bounce.timestampSeconds - tBounce) < 0.10)
        assertTrue(abs(table.lengthMeters - bounce.positionMeters.x) <= 2.5 * table.ballRadiusMeters)
        assertTrue(bounce.speedBeforeMps > bounce.speedAfterMps)
        assertTrue(stop.speedAfterMps <= 0.025)
        assertTrue(slideToRoll.timestampSeconds < bounce.timestampSeconds)
        assertTrue(bounce.timestampSeconds < stop.timestampSeconds)
    }

    @Test
    fun trackBallInArgbVideoFramesHandlesEmptyInput() {
        val tracked = RawImageTableDetector.trackBallInArgbVideoFrames(
            frames = emptyList(),
            quadCornersPx = trapezoidQuad,
            table = table,
        )
        assertTrue(tracked.points.isEmpty())
        assertTrue(tracked.events.isEmpty())
    }

    @Test
    fun brightNeutralGreyClothWithWarmWoodRailsDetectsAllNineBroadcastBalls() {
        val w = 640
        val h = 520
        val rawQuad = listOf(
            Vec2(145.0, 155.0),
            Vec2(455.0, 155.0),
            Vec2(560.0, 475.0),
            Vec2(55.0, 475.0),
        )
        val broadcastBalls = listOf(
            ball(0, 0.95, 0.28),
            ball(2, 2.02, 1.02),
            ball(3, 1.84, 0.98),
            ball(4, 0.72, 0.88),
            ball(5, 2.32, 0.48),
            ball(6, 0.56, 0.82),
            ball(7, 2.28, 0.84),
            ball(8, 0.76, 1.06),
            ball(9, 1.98, 0.64),
        )
        val layout = TableLayout(table, broadcastBalls)
        val (orientedQuad, endCamera) = RawImageTableDetector.orientQuadForHomography(rawQuad, table)
        val homography = PerspectiveHomography.fromTableQuad(orientedQuad, table)

        // Start from warm beige wood rails (~S=0.11, V=0.88) and bright neutral grey cloth (~S=0.03, V=0.81).
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (RawImageTableDetector.pointInQuad(x.toDouble(), y.toDouble(), rawQuad)) {
                0xFFD0CCD0.toInt()
            } else {
                0xFFE2D1C9.toInt()
            }
        }

        val ballSet = BallSetProfile.broadcastBlackPool()
        val paletteArgb = mapOf(
            com.runout.engine.model.BallPaletteColor.WHITE to 0xFFFBFBFB.toInt(),
            com.runout.engine.model.BallPaletteColor.YELLOW to 0xFFF0DC1E.toInt(),
            com.runout.engine.model.BallPaletteColor.BLUE to 0xFF3CC4DC.toInt(),
            com.runout.engine.model.BallPaletteColor.RED to 0xFFD82828.toInt(),
            com.runout.engine.model.BallPaletteColor.PINK to 0xFFF08CA8.toInt(),
            com.runout.engine.model.BallPaletteColor.PURPLE to 0xFF82508C.toInt(),
            com.runout.engine.model.BallPaletteColor.GREEN to 0xFF28B464.toInt(),
            com.runout.engine.model.BallPaletteColor.BROWN to 0xFF8E4218.toInt(),
            com.runout.engine.model.BallPaletteColor.BLACK to 0xFF141414.toInt(),
        )
        for (b in layout.balls) {
            val c = homography.ballCenterMetersToImage(b.position, b.radiusMeters, endCamera)
            val r = 10.5
            val color = paletteArgb.getValue(ballSet.colorForBall(b.number))
            for (py in (c.y - r).toInt()..(c.y + r).toInt()) {
                for (px in (c.x - r).toInt()..(c.x + r).toInt()) {
                    if (px !in 0 until w || py !in 0 until h) continue
                    val dx = px - c.x
                    val dy = py - c.y
                    val d2 = dx * dx + dy * dy
                    if (d2 > r * r) continue
                    pixels[py * w + px] = when {
                        b.number == 0 -> 0xFFFBFBFB.toInt()
                        b.number == 9 -> if (abs(dy) <= 0.40 * r) 0xFF1E1A16.toInt() else if (abs(dy) <= 0.72 * r) color else 0xFFFBFBFB.toInt()
                        else -> if (d2 <= 0.12 * r * r) 0xFFFBFBFB.toInt() else color
                    }
                }
            }
        }

        val frame = ArgbImageFrame(w, h, pixels)
        val summary = RawImageTableDetector.analyzeArgbFrame(frame)
        assertEquals(ClothHueCategory.GREY, summary.clothSurface.category)
        assertEquals(9, summary.detectedCandidates.size, "All 9 balls must be detected on bright neutral-grey cloth")
        for (corner in summary.detectedQuadCornersPx) {
            assertTrue(corner.y >= 135.0 && corner.y <= 495.0, "Quad corner leaked onto wood rails/arena: $corner")
        }

        val (detOriented, detCamera) = RawImageTableDetector.orientQuadForHomography(summary.detectedQuadCornersPx, table)
        val detHomography = PerspectiveHomography.fromTableQuad(detOriented, table)
        val rectified = BallIdentificationAndAssignment.rectifyAndIdentifyBalls(
            candidates = summary.detectedCandidates,
            homography = detHomography,
            table = table,
            ballSet = ballSet,
            cameraPositionMeters = detCamera,
        )
        assertEquals(broadcastBalls.map { it.number }.toSet(), rectified.layout.balls.map { it.number }.toSet())
    }

    @Test
    fun rescanLocalRegionDetectsMissedBallNearCornerAndSplitsCluster() {
        val layout = TableLayout(
            table = table,
            balls = listOf(
                ball(0, 0.45, 0.50),
                ball(2, 1.20, 0.55),
                ball(4, 1.26, 0.55),
                ball(3, table.lengthMeters - 0.06, table.widthMeters - 0.06),
            ),
        )
        val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
            layout = layout,
            quadCornersPx = trapezoidQuad,
            cameraPoseMeters = camera,
            clothCategory = ClothHueCategory.BLUE,
            ballSet = BallSetProfile.standardPool(),
            ballRadiusPx = 11,
        )
        val homography = PerspectiveHomography.fromTableQuad(trapezoidQuad, table)
        val cueCenter = homography.ballCenterMetersToImage(layout.balls[0].position, table.ballRadiusMeters, camera)
        val cornerBallCenter = homography.ballCenterMetersToImage(layout.balls[3].position, table.ballRadiusMeters, camera)
        val clusterMidCenter = (
            homography.ballCenterMetersToImage(layout.balls[1].position, table.ballRadiusMeters, camera) +
                homography.ballCenterMetersToImage(layout.balls[2].position, table.ballRadiusMeters, camera)
            ) * 0.5

        val existing = listOf(
            com.runout.engine.vision.DetectedBallCandidate(
                id = 1,
                imageCenterPx = cueCenter,
                dominantColor = com.runout.engine.model.BallPaletteColor.WHITE,
                whiteFraction = 0.95,
            ),
        )

        val cornerScan = RawImageTableDetector.rescanLocalRegion(
            frame = frame,
            tapCenterPx = cornerBallCenter,
            quadCornersPx = trapezoidQuad,
            existingCandidates = existing,
        )
        assertTrue(!cornerScan.usedColorFallback, "Expected smart blob rescan to recover corner ball")
        assertEquals(1, cornerScan.detectedCandidates.size)
        assertEquals(com.runout.engine.model.BallPaletteColor.RED, cornerScan.detectedCandidates.first().dominantColor)

        val clusterScan = RawImageTableDetector.rescanLocalRegion(
            frame = frame,
            tapCenterPx = clusterMidCenter,
            quadCornersPx = trapezoidQuad,
            existingCandidates = existing + cornerScan.detectedCandidates,
        )
        assertTrue(!clusterScan.usedColorFallback, "Expected cluster split rescan to detect both touching balls")
        assertEquals(2, clusterScan.detectedCandidates.size)
        val allNew = existing + cornerScan.detectedCandidates + clusterScan.detectedCandidates
        assertEquals(allNew.map { it.id }.toSet().size, allNew.size, "IDs must remain unique")
    }

    @Test
    fun rescanLocalRegionColorFallbackAndBallNumberPreselection() {
        val w = 640
        val h = 360
        val blueCloth = 0xFF1E78B4.toInt()
        val quad = listOf(
            Vec2(40.0, 40.0),
            Vec2(600.0, 40.0),
            Vec2(620.0, 320.0),
            Vec2(20.0, 320.0),
        )
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (RawImageTableDetector.pointInQuad(x.toDouble(), y.toDouble(), quad)) blueCloth else 0xFF2B1B12.toInt()
        }

        // Paint sub-threshold (r=4.6 px < minAllowedR ~5.1 px) patches so rescanLocalRegion exercises Pass 2 color fallback
        fun paintCircle(cx: Double, cy: Double, r: Double, colorAtDy: (Double) -> Int) {
            for (y in (cy - r).toInt()..(cy + r).toInt()) {
                for (x in (cx - r).toInt()..(cx + r).toInt()) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= r * r) {
                        pixels[y * w + x] = colorAtDy(dy)
                    }
                }
            }
        }

        val blackPos = Vec2(160.0, 140.0)
        val ninePos = Vec2(280.0, 140.0)
        val whitePos = Vec2(400.0, 140.0)
        val redPos = Vec2(500.0, 140.0)
        val subThresholdR = 3.6

        paintCircle(blackPos.x, blackPos.y, subThresholdR) { 0xFF141414.toInt() }
        paintCircle(ninePos.x, ninePos.y, subThresholdR) { dy -> if (abs(dy) <= 1.4) 0xFFF0DC1E.toInt() else 0xFFF5F7FA.toInt() }
        paintCircle(whitePos.x, whitePos.y, subThresholdR) { 0xFFF5F7FA.toInt() }
        paintCircle(redPos.x, redPos.y, subThresholdR) { 0xFFD22828.toInt() }

        val frame = ArgbImageFrame(w, h, pixels)
        val cloth = RawImageTableDetector.detectClothSurface(frame)
        val ballSet = BallSetProfile.standardPool()

        val blackRes = RawImageTableDetector.rescanLocalRegion(frame, blackPos, quad, emptyList(), clothOverride = cloth)
        assertTrue(blackRes.usedColorFallback)
        assertEquals(com.runout.engine.model.BallPaletteColor.BLACK, blackRes.detectedCandidates.single().dominantColor)
        assertEquals(8, BallIdentificationAndAssignment.suggestBallNumberForCandidate(blackRes.detectedCandidates.single(), ballSet))

        val nineRes = RawImageTableDetector.rescanLocalRegion(frame, ninePos, quad, blackRes.detectedCandidates, clothOverride = cloth)
        assertTrue(nineRes.usedColorFallback)
        val nineCand = nineRes.detectedCandidates.single()
        assertEquals(com.runout.engine.model.BallPaletteColor.YELLOW, nineCand.dominantColor)
        assertTrue(nineCand.whiteFraction >= 0.42, "White+Yellow stripe whiteFraction was ${nineCand.whiteFraction}")
        assertEquals(9, BallIdentificationAndAssignment.suggestBallNumberForCandidate(nineCand, ballSet))

        val whiteRes = RawImageTableDetector.rescanLocalRegion(frame, whitePos, quad, emptyList(), clothOverride = cloth)
        assertTrue(whiteRes.usedColorFallback)
        assertEquals(com.runout.engine.model.BallPaletteColor.WHITE, whiteRes.detectedCandidates.single().dominantColor)
        assertEquals(0, BallIdentificationAndAssignment.suggestBallNumberForCandidate(whiteRes.detectedCandidates.single(), ballSet))

        val redRes = RawImageTableDetector.rescanLocalRegion(frame, redPos, quad, emptyList(), clothOverride = cloth)
        assertTrue(redRes.usedColorFallback)
        val redCand = redRes.detectedCandidates.single()
        assertEquals(com.runout.engine.model.BallPaletteColor.RED, redCand.dominantColor)
        assertEquals(3, BallIdentificationAndAssignment.suggestBallNumberForCandidate(redCand, ballSet))
        assertEquals(5, BallIdentificationAndAssignment.suggestBallNumberForCandidate(redCand, ballSet, excludedNumbers = setOf(3)))
        assertEquals(11, BallIdentificationAndAssignment.suggestBallNumberForCandidate(redCand, ballSet, excludedNumbers = setOf(3, 5, 7)))
    }
}
