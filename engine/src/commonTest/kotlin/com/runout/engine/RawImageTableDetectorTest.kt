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
import com.runout.engine.vision.RawImageTableDetector
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

        val tournament = RawImageTableDetector.rgbToHsv(0xFF1E78B4.toInt())
        assertTrue(tournament.first in 195.0..215.0, "hue was ${tournament.first}")
        assertTrue(tournament.second in 0.75..0.90)

        // Hue wraps into 0..360 for reddish colours with blue above green.
        val crimson = RawImageTableDetector.rgbToHsv(0xFFDC143C.toInt())
        assertTrue(crimson.first in 340.0..350.0, "hue was ${crimson.first}")
    }

    @Test
    fun detectClothSurfaceClassifiesAllFourClothCategories() {
        val expectedHueRange = mapOf(
            ClothHueCategory.TOURNAMENT_BLUE to 175.0..255.0,
            ClothHueCategory.SIMONIS_GREEN to 75.0..175.0,
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
                ClothHueCategory.CHAMPIONSHIP_RED -> assertTrue(
                    cloth.dominantHueDegrees <= 35.0 || cloth.dominantHueDegrees >= 325.0,
                )
                ClothHueCategory.SLATE_GREY -> assertTrue(cloth.meanSaturation < 0.22)
                else -> assertTrue(cloth.dominantHueDegrees in expectedHueRange.getValue(category))
            }
            if (category != ClothHueCategory.SLATE_GREY) {
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
            clothCategory = ClothHueCategory.SIMONIS_GREEN,
        )
        val cloth = RawImageTableDetector.detectClothSurface(frame)
        assertEquals(ClothHueCategory.SIMONIS_GREEN, cloth.category)
        assertTrue(RawImageTableDetector.isClothPixel(frame.pixelAt(300, 200), cloth))
        assertTrue(!RawImageTableDetector.isClothPixel(frame.pixelAt(2, 2), cloth), "Rail must not be cloth")
    }

    @Test
    fun detectTableQuadCornersRecoversPerspectiveCornersWithinFourPixels() {
        for (quad in listOf(angledQuad, trapezoidQuad, greenGameQuad)) {
            val frame = RawImageTableDetector.renderSyntheticTablePhotoArgb(
                layout = nineBallLayout(),
                quadCornersPx = quad,
                cameraPoseMeters = camera,
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
            cloth = ClothHueCategory.TOURNAMENT_BLUE,
            ballSet = BallSetProfile.standardPool(),
        )
    }

    @Test
    fun tvTournamentEightBallPixelRoundTripOnGreenCloth() {
        assertRoundTrip(
            layout = tvEightBallLayout(),
            quad = greenGameQuad,
            cloth = ClothHueCategory.SIMONIS_GREEN,
            ballSet = BallSetProfile.tvTournamentPool(),
        )
    }

    @Test
    fun wntTournamentBlackSetAndEndOnOrientationRoundTrip() {
        assertRoundTrip(
            layout = nineBallLayout(),
            quad = trapezoidQuad,
            cloth = ClothHueCategory.SLATE_GREY,
            ballSet = BallSetProfile.wntTournamentBlackPool(),
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
}
