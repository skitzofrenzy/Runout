package com.runout.engine.vision

import com.runout.engine.calibration.TrackedBallTrajectory
import com.runout.engine.calibration.VideoBallTracker
import com.runout.engine.calibration.VideoFrameBallObservation
import com.runout.engine.math.Vec2
import com.runout.engine.model.*
import kotlin.math.*

/**
 * Raw `0xAARRGGBB` image in row-major order (`argbPixels[y * width + x]`), independent of any
 * platform bitmap type.
 */
class ArgbImageFrame(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
) {
    init {
        require(width > 0 && height > 0 && argbPixels.size == width * height) {
            "argbPixels must contain exactly width * height pixels."
        }
    }

    /** Pixel at `(x, y)` with the coordinates clamped to the image bounds. */
    fun pixelAt(x: Int, y: Int): Int =
        argbPixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
}

/** Coarse hue family of the playing cloth. */
enum class ClothHueCategory {
    BLUE,
    GREEN,
    RED,
    GREY,
}

/** Dominant cloth colour measured from the central region of a frame. */
data class DetectedClothSurface(
    val category: ClothHueCategory,
    val dominantHueDegrees: Double,
    val meanSaturation: Double,
    val meanValue: Double,
    val clothCoverageFraction: Double,
    val meanRedMinusBlue: Double = 0.0,
    val meanRedMinusGreen: Double = 0.0,
)

/**
 * How one side of the table quad was located. Side `i` runs from quad corner `i` to corner
 * `(i + 1) % 4` in the order the quad is reported in.
 *
 * @property confidence share of the sampled cross-sections that agree with the fitted cushion-nose
 *   line (`0..1`); fallback sides report [RawImageTableDetector.FALLBACK_EDGE_CONFIDENCE].
 * @property usedFallback true when no reliable nose line was visible and the side was placed by
 *   insetting the outer cloth edge by a typical cushion width; such corners should be checked.
 * @property insetFromOuterPx distance in pixels from the midpoint of the outer cloth edge to the
 *   reported nose line.
 */
data class TableQuadEdgeFit(
    val confidence: Double,
    val usedFallback: Boolean,
    val insetFromOuterPx: Double,
)

/**
 * The two table outlines found in a frame.
 *
 * @property noseQuadCornersPx the playing surface at the cushion noses: the line where the bed meets
 *   the cushion face (or, on the side nearest the camera, the cushion top). This is the quad to
 *   hand to [PerspectiveHomography.fromTableQuad] after [RawImageTableDetector.orientQuadForHomography].
 * @property outerQuadCornersPx the outline of the connected cloth region. Cloth-covered cushions
 *   usually pass the cloth colour test, so this is often the cushion backs, with the near corners
 *   in the pocket holes. Ball detection masks with this outline (see
 *   [RawImageTableDetector.analyzeArgbFrame]).
 * @property edges one [TableQuadEdgeFit] per side of [noseQuadCornersPx].
 * @property endOnView true when the frame looks along the table from behind a short rail (the top
 *   side of the quad is a short rail and the bottom side the short rail nearest the camera). It is
 *   decided once, on [outerQuadCornersPx], and every later step (table frame, near side, corner
 *   order for the homography, ball mask) follows this flag instead of deciding again.
 */
data class TableQuadDetection(
    val noseQuadCornersPx: List<Vec2>,
    val outerQuadCornersPx: List<Vec2>,
    val edges: List<TableQuadEdgeFit>,
    val endOnView: Boolean = false,
) {
    /** True when at least one side used the cushion-width fallback. */
    val needsCornerReview: Boolean get() = edges.any { it.usedFallback }
}

/**
 * Everything extracted from one raw frame: cloth, table quad, ball candidates and quality metrics.
 *
 * [detectedQuadCornersPx] is the cushion-nose quad (see [TableQuadDetection.noseQuadCornersPx]);
 * [outerQuadCornersPx] is the outline of the cloth region found on the first analysis (carried
 * through a re-analysis given `previousQuads`; the ball mask itself when nothing was known).
 * [ballMaskCornersPx] is the quad ball blobs were looked for in: the nose quad pushed out over the
 * cushion band. [endOnView] is the orientation decision (see [TableQuadDetection.endOnView]).
 */
data class RawFrameDetectionSummary(
    val width: Int,
    val height: Int,
    val clothSurface: DetectedClothSurface,
    val detectedQuadCornersPx: List<Vec2>,
    val detectedCandidates: List<DetectedBallCandidate>,
    val sharpnessScore: Double,
    val glareFraction: Double,
    val meanLuma: Double,
    val outerQuadCornersPx: List<Vec2> = detectedQuadCornersPx,
    val quadEdges: List<TableQuadEdgeFit> = emptyList(),
    val endOnView: Boolean = false,
    val ballMaskCornersPx: List<Vec2> = outerQuadCornersPx,
) {
    /** True when a side of [detectedQuadCornersPx] came from the cushion-width fallback. */
    val quadNeedsCornerReview: Boolean get() = quadEdges.any { it.usedFallback }

    /**
     * The quads of this analysis, to hand back to [RawImageTableDetector.analyzeArgbFrame] when the
     * same frame is analysed again with a corrected quad.
     */
    fun toQuadDetection(): TableQuadDetection = TableQuadDetection(
        noseQuadCornersPx = detectedQuadCornersPx,
        outerQuadCornersPx = outerQuadCornersPx,
        edges = quadEdges,
        endOnView = endOnView,
    )
}

/** Result of a user-triggered local rescan around a tapped point on an imported photo. */
data class LocalTapRescanResult(
    val detectedCandidates: List<DetectedBallCandidate>,
    val usedColorFallback: Boolean,
)

/**
 * Pure-Kotlin table, cloth and ball detection on raw ARGB pixels, plus a synthetic table
 * renderer that lets any [TableLayout] round-trip through pixels in unit tests.
 *
 * Image convention: pixel `(x, y)` is sampled at the integer image point `(x, y)`; `x` grows to
 * the right and `y` grows downwards. The table quad is ordered
 * `[headLeftPx, footLeftPx, footRightPx, headRightPx]`, which in the standard landscape view is
 * top-left, top-right, bottom-right, bottom-left.
 */
object RawImageTableDetector {
    private const val HUE_BIN_COUNT = 36
    private const val HUE_BIN_DEGREES = 10.0
    private const val CLOTH_HUE_TOLERANCE_DEGREES = 28.0
    private const val CLOTH_MIN_SATURATION = 0.15
    private const val CLOTH_MAX_SATURATION = 0.95
    private const val CLOTH_MIN_VALUE = 0.18
    private const val CLOTH_MAX_VALUE = 1.00

    /** Median saturation below which the cloth is treated as grey regardless of hue. */
    private const val GREY_MEDIAN_SATURATION = 0.22
    private const val GREY_PIXEL_MAX_SATURATION = 0.30
    private const val GREY_VALUE_TOLERANCE = 0.30

    private const val MIN_CLOTH_PIXELS_FOR_QUAD = 50
    private const val BLOB_INSET_PX = 4.0
    private const val END_ON_BLOB_INSET_PX = 0.0
    private const val SHARPNESS_GRADIENT_SCALE = 1.5

    private const val RAIL_ARGB = 0xFF2B1B12L
    private const val WHITE_BALL_ARGB = 0xFFF5F7FAL
    private const val BLACK_BALL_ARGB = 0xFF141414L
    private const val POCKET_ARGB = 0xFF0A0A0AL

    /** Width of a cushion's top, from the back edge of the cloth to the nose, in metres. */
    const val CUSHION_TOP_WIDTH_METERS: Double = 0.050

    /**
     * Width of the cushion band seen in front of the bed on the sides facing the camera, in metres:
     * the top plus the visible part of the sloping nose face. Measured at 65 to 90 mm on broadcast
     * photos; the lower end is used so the fallback errs towards the bed edge.
     */
    const val CUSHION_VISIBLE_BAND_METERS: Double = 0.065

    /**
     * Smallest cushion width accepted for a measured nose, in metres: the width of the cloth-covered
     * strip that must lie behind a nose line (see [cushionWidthBeyond]). A cushion top alone is
     * about 50 mm wide, so a line with less than this behind it is the edge of the cloth region
     * itself, with the rail right behind, rather than a nose inside the cushion.
     */
    const val MIN_CUSHION_WIDTH_METERS: Double = 0.020

    /**
     * Largest cushion width accepted for a measured nose, in metres. The visible band measured on
     * broadcast photos (perspective included) tops out at about 91 mm; 110 mm leaves a fifth of
     * margin. A shadow, a reflection or a lit strip further in than this is not a cushion edge:
     * the next step out is tried instead and, if none is consistent with a cushion width, the side
     * falls back to the nominal width and is flagged.
     */
    const val MAX_CUSHION_WIDTH_METERS: Double = 0.110

    /** Distance between probes when the cushion width behind a line is measured, in metres. */
    private const val WIDTH_PROBE_STEP_METERS = 0.005

    /** Steps kept per cross-section: the nose is one of the first few steps in from the cloth edge. */
    private const val NOSE_MAX_STEP_LEVELS = 4

    /** Width of the sloping nose face drawn by the synthetic renderer, in metres. */
    private const val CUSHION_FACE_STRIP_METERS = CUSHION_VISIBLE_BAND_METERS - CUSHION_TOP_WIDTH_METERS

    /**
     * Shape limits for a blob whose centre is on the cushion band, outside the nose quad. A disc
     * fills `pi / 4 = 0.785` of its bounding box and has aspect 1; the synthetic frozen balls
     * measure fill 0.69 to 0.78 and aspect 0.9 to 1.1 at the expected radius, but real balls on the
     * rails of the local test photos measure fill 0.54 to 0.74 and aspect 0.9 to 1.5 (shading,
     * partial occlusion by the cue or the rail). The limits sit just outside the real ones: fill at
     * least 0.50, aspect within `0.6..1.67`, radius within `0.5..1.6` times the expected radius at
     * that image row. They are tighter than the bed limits only in the radius; a hand or cue lying
     * on the rail fails the aspect and fill limits when seen from the side, and on every view the
     * position test of the ball-height-corrected centre rules it out.
     */
    private const val BAND_MIN_FILL = 0.50
    private const val BAND_MIN_ASPECT = 0.60
    private const val BAND_MAX_ASPECT = 1.67
    private const val BAND_MIN_RADIUS_RATIO = 0.5
    private const val BAND_MAX_RADIUS_RATIO = 1.6

    /**
     * Largest area per distance-transform peak, in expected ball areas, for a cluster whose centre
     * is on the cushion band. Touching balls measure about one ball area per peak (the overlap of
     * the discs is small); 1.6 leaves room for shading and a cut-out highlight.
     */
    private const val BAND_MAX_AREA_PER_PEAK = 1.6

    /** Upper bound on how far a ball mask is pushed out beyond a nose side, in metres. */
    private const val MAX_MASK_BAND_METERS = 0.20

    /** Confidence reported for a side that was placed by the cushion-width fallback. */
    const val FALLBACK_EDGE_CONFIDENCE: Double = 0.25

    private const val NOSE_PROFILE_SAMPLES = 60
    private const val NOSE_MIN_CONFIDENCE = 0.5
    private const val NOSE_STEP_MIN_CONTRAST = 8.0
    private const val NOSE_STEP_NOISE_FACTOR = 4.0

    /** How far across the table, as a fraction of its width, each cross-section is scanned. */
    private const val NOSE_BAND_FRACTION = 0.12

    /**
     * A corner formed by two adjacent sides whose directions have `|sin|` of their angle below
     * this value (about 11 degrees) is ill-conditioned: a table corner seen from any usable camera
     * position is far from straight, so such fitted sides cannot both be cushion edges.
     */
    private const val MIN_CORNER_SIN = 0.2

    /**
     * Largest shift of a nose corner from its outer corner along either table axis, in metres: a
     * cushion width and a pocket mouth (the outline's corner is cut by the pocket) with margin.
     */
    private const val MAX_CORNER_SHIFT_METERS = 0.30

    private val PALETTE: Array<BallPaletteColor> = BallPaletteColor.values()

    // ---------------------------------------------------------------------------------------
    // Colour helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Converts an `0xAARRGGBB` pixel to `(hueDegrees in 0..360, saturation in 0..1, value in 0..1)`.
     * Hue is `0` for achromatic pixels.
     */
    fun rgbToHsv(argb: Int): Triple<Double, Double, Double> {
        val out = DoubleArray(3)
        hsvInto(argb, out)
        return Triple(out[0], out[1], out[2])
    }

    private fun hsvInto(argb: Int, out: DoubleArray) {
        val r = ((argb ushr 16) and 0xFF) / 255.0
        val g = ((argb ushr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val hue = if (d <= 1e-12) {
            0.0
        } else {
            val sector = when {
                mx == r -> {
                    val q = (g - b) / d
                    if (q < 0.0) q + 6.0 else q
                }
                mx == g -> (b - r) / d + 2.0
                else -> (r - g) / d + 4.0
            }
            sector * 60.0
        }
        out[0] = hue.coerceIn(0.0, 360.0)
        out[1] = if (mx <= 1e-12) 0.0 else d / mx
        out[2] = mx
    }

    private fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return min(d, 360.0 - d)
    }

    private fun categoryForHue(hue: Double): ClothHueCategory = when {
        hue in 175.0..255.0 -> ClothHueCategory.BLUE
        hue >= 75.0 && hue < 175.0 -> ClothHueCategory.GREEN
        hue <= 35.0 || hue >= 325.0 -> ClothHueCategory.RED
        else -> ClothHueCategory.GREY
    }

    private fun isBrightNeutralGreyCloth(cloth: DetectedClothSurface): Boolean =
        cloth.meanSaturation < GREY_MEDIAN_SATURATION && cloth.meanValue >= 0.55

    /** True when [argb] matches the measured [cloth] colour (cloth mask used by all detectors). */
    fun isClothPixel(argb: Int, cloth: DetectedClothSurface): Boolean {
        val scratch = DoubleArray(3)
        hsvInto(argb, scratch)
        return isClothArgbHsv(argb, scratch[0], scratch[1], scratch[2], cloth)
    }

    private fun isClothArgbHsv(
        argb: Int,
        hue: Double,
        sat: Double,
        value: Double,
        cloth: DetectedClothSurface,
    ): Boolean {
        if (value < CLOTH_MIN_VALUE || value > CLOTH_MAX_VALUE) return false
        if (isBrightNeutralGreyCloth(cloth)) {
            val whiteCeiling = min(0.905, max(0.64, cloth.meanValue + 0.075))
            if (value >= whiteCeiling && sat <= 0.18) return false
            if (value < cloth.meanValue - 0.17 || value >= whiteCeiling) return false
            val satTol = if (cloth.meanSaturation < 0.12) 0.042 else 0.075
            val maxSat = min(GREY_PIXEL_MAX_SATURATION, cloth.meanSaturation + satTol)
            if (sat > maxSat) return false
            val rf = ((argb ushr 16) and 0xFF) / 255.0
            val gf = ((argb ushr 8) and 0xFF) / 255.0
            val bf = (argb and 0xFF) / 255.0
            val rgbTol = if (cloth.meanSaturation < 0.12) 0.038 else 0.055
            return abs((rf - bf) - cloth.meanRedMinusBlue) <= rgbTol &&
                abs((rf - gf) - cloth.meanRedMinusGreen) <= rgbTol
        }
        if ((value >= 0.84 && sat <= 0.18) || (value >= 0.64 && sat <= 0.15)) return false
        return if (cloth.meanSaturation < GREY_MEDIAN_SATURATION) {
            sat <= GREY_PIXEL_MAX_SATURATION &&
                abs(sat - cloth.meanSaturation) <= 0.16 &&
                abs(value - cloth.meanValue) <= GREY_VALUE_TOLERANCE
        } else {
            val satTol = if (cloth.meanSaturation < 0.40) 0.14 else 0.22
            hueDistance(hue, cloth.dominantHueDegrees) <= CLOTH_HUE_TOLERANCE_DEGREES &&
                abs(sat - cloth.meanSaturation) <= satTol &&
                abs(value - cloth.meanValue) <= 0.30
        }
    }

    private fun isClothShadowHsv(
        hue: Double,
        sat: Double,
        value: Double,
        cloth: DetectedClothSurface,
    ): Boolean {
        if (cloth.meanSaturation > 0.40) {
            // Saturated blue/teal/green cloth drop-shadows keep the cloth hue and high saturation
            // while dropping in value below the cloth bed.
            if (cloth.category == ClothHueCategory.RED) return false
            val upperVal = (cloth.meanValue - 0.14).coerceAtLeast(0.45)
            if (value < 0.16 || value > upperVal) return false
            return hueDistance(hue, cloth.dominantHueDegrees) <= 22.0 &&
                abs(sat - cloth.meanSaturation) <= 0.25
        }
        val upperVal = (cloth.meanValue - 0.09).coerceAtLeast(0.55)
        if (value < 0.36 || value > upperVal) return false
        if (value >= 0.60 && sat <= 0.15) return false
        val chroma = sat * value
        if (sat > 0.34 && chroma > 0.20) return false
        return if (cloth.meanSaturation < GREY_MEDIAN_SATURATION) {
            sat <= 0.15
        } else {
            sat <= 0.25 || hueDistance(hue, cloth.dominantHueDegrees) <= 32.0
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cloth detection
    // ---------------------------------------------------------------------------------------

    /**
     * Measures the dominant cloth colour from the central `[0.28..0.72] x [0.22..0.78]` region,
     * ignoring near-white and near-black (ball and pocket) pixels, using a 36-bin circular hue
     * histogram. Cloth whose median saturation is below `0.22` is reported as [ClothHueCategory.GREY].
     */
    fun detectClothSurface(frame: ArgbImageFrame): DetectedClothSurface {
        val w = frame.width
        val h = frame.height
        val x0 = (0.28 * w).toInt().coerceIn(0, w - 1)
        val x1 = (0.72 * w).toInt().coerceIn(x0, w - 1)
        val y0 = (0.22 * h).toInt().coerceIn(0, h - 1)
        val y1 = (0.78 * h).toInt().coerceIn(y0, h - 1)
        val stepX = max(1, (x1 - x0) / 96)
        val stepY = max(1, (y1 - y0) / 96)

        val capacity = ((x1 - x0) / stepX + 1) * ((y1 - y0) / stepY + 1)
        val hues = DoubleArray(capacity)
        val sats = DoubleArray(capacity)
        val vals = DoubleArray(capacity)
        val rMinusB = DoubleArray(capacity)
        val rMinusG = DoubleArray(capacity)
        var n = 0
        val hsv = DoubleArray(3)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val argb = frame.argbPixels[y * w + x]
                hsvInto(argb, hsv)
                val isBright = hsv[2] > 0.89 && hsv[1] < 0.14
                val isDark = hsv[2] < 0.15
                if (!isBright && !isDark && n < capacity) {
                    val rf = ((argb ushr 16) and 0xFF) / 255.0
                    val gf = ((argb ushr 8) and 0xFF) / 255.0
                    val bf = (argb and 0xFF) / 255.0
                    hues[n] = hsv[0]
                    sats[n] = hsv[1]
                    vals[n] = hsv[2]
                    rMinusB[n] = rf - bf
                    rMinusG[n] = rf - gf
                    n++
                }
                x += stepX
            }
            y += stepY
        }

        if (n == 0) {
            return DetectedClothSurface(ClothHueCategory.GREY, 0.0, 0.0, 0.4, 0.0)
        }

        val medianSat = sats.copyOf(n).also { it.sort() }[n / 2]
        val base: DetectedClothSurface
        if (medianSat < GREY_MEDIAN_SATURATION) {
            val greySats = DoubleArray(n)
            val greyVals = DoubleArray(n)
            val greyRbs = DoubleArray(n)
            val greyRgs = DoubleArray(n)
            var count = 0
            for (i in 0 until n) {
                if (sats[i] <= GREY_PIXEL_MAX_SATURATION) {
                    greySats[count] = sats[i]
                    greyVals[count] = vals[i]
                    greyRbs[count] = rMinusB[i]
                    greyRgs[count] = rMinusG[i]
                    count++
                }
            }
            val medSat = if (count > 0) greySats.copyOf(count).also { it.sort() }[count / 2] else 0.0
            val medVal = if (count > 0) greyVals.copyOf(count).also { it.sort() }[count / 2] else 0.4
            val medRb = if (count > 0) greyRbs.copyOf(count).also { it.sort() }[count / 2] else 0.0
            val medRg = if (count > 0) greyRgs.copyOf(count).also { it.sort() }[count / 2] else 0.0
            base = DetectedClothSurface(
                category = ClothHueCategory.GREY,
                dominantHueDegrees = circularMeanHue(hues, n) { sats[it] <= GREY_PIXEL_MAX_SATURATION },
                meanSaturation = medSat,
                meanValue = medVal,
                clothCoverageFraction = 0.0,
                meanRedMinusBlue = medRb,
                meanRedMinusGreen = medRg,
            )
        } else {
            val bins = IntArray(HUE_BIN_COUNT)
            for (i in 0 until n) {
                if (sats[i] >= CLOTH_MIN_SATURATION) {
                    bins[binOf(hues[i])]++
                }
            }
            var best = 0
            for (b in 1 until HUE_BIN_COUNT) {
                if (bins[b] > bins[best]) best = b
            }
            val dominantHue = circularMeanHue(hues, n) { i ->
                if (sats[i] < CLOTH_MIN_SATURATION) {
                    false
                } else {
                    val diff = abs(binOf(hues[i]) - best)
                    min(diff, HUE_BIN_COUNT - diff) <= 1
                }
            }
            var sumS = 0.0
            var sumV = 0.0
            var count = 0
            for (i in 0 until n) {
                if (sats[i] >= CLOTH_MIN_SATURATION &&
                    hueDistance(hues[i], dominantHue) <= CLOTH_HUE_TOLERANCE_DEGREES
                ) {
                    sumS += sats[i]
                    sumV += vals[i]
                    count++
                }
            }
            val safe = max(1, count)
            base = DetectedClothSurface(
                category = categoryForHue(dominantHue),
                dominantHueDegrees = dominantHue,
                meanSaturation = sumS / safe,
                meanValue = sumV / safe,
                clothCoverageFraction = 0.0,
            )
        }

        var clothPixels = 0
        val total = w * h
        for (i in 0 until total) {
            val argb = frame.argbPixels[i]
            hsvInto(argb, hsv)
            if (isClothArgbHsv(argb, hsv[0], hsv[1], hsv[2], base)) clothPixels++
        }
        return base.copy(clothCoverageFraction = clothPixels.toDouble() / total)
    }

    private fun binOf(hue: Double): Int = min(HUE_BIN_COUNT - 1, (hue / HUE_BIN_DEGREES).toInt())

    private inline fun circularMeanHue(hues: DoubleArray, n: Int, include: (Int) -> Boolean): Double {
        var sumSin = 0.0
        var sumCos = 0.0
        var count = 0
        for (i in 0 until n) {
            if (!include(i)) continue
            val rad = hues[i] * PI / 180.0
            sumSin += sin(rad)
            sumCos += cos(rad)
            count++
        }
        if (count == 0) return 0.0
        var deg = atan2(sumSin, sumCos) * 180.0 / PI
        if (deg < 0.0) deg += 360.0
        return deg.coerceIn(0.0, 360.0)
    }

    // ---------------------------------------------------------------------------------------
    // Table quad
    // ---------------------------------------------------------------------------------------

    /**
     * Finds the four table corners at the cushion noses, ordered `[TL, TR, BR, BL]` in the image
     * (pass them through [orientQuadForHomography] before building a homography).
     *
     * This is [TableQuadDetection.noseQuadCornersPx] of [detectTableQuads]. Returns a default inset
     * quad when fewer than 50 cloth pixels are found.
     */
    fun detectTableQuadCornersPx(
        frame: ArgbImageFrame,
        cloth: DetectedClothSurface,
        table: TableSpec = TableSpec.nineFootPool(),
    ): List<Vec2> = detectTableQuads(frame, cloth, table).noseQuadCornersPx

    /**
     * Outline of the connected cloth region, ordered `[TL, TR, BR, BL]`: the extreme points of the
     * centre-seeded cloth component along the diagonals, refined for end-on broadcast views.
     * Cloth-covered cushions usually belong to this region, so its sides are often the cushion
     * backs rather than the noses.
     *
     * Returns a default inset quad when fewer than 50 cloth pixels are found.
     */
    fun detectOuterClothQuadCornersPx(frame: ArgbImageFrame, cloth: DetectedClothSurface): List<Vec2> =
        detectOuterClothQuadOrNull(frame, cloth) ?: defaultInsetQuad(frame.width, frame.height)

    /**
     * Finds both table outlines: the outer cloth region ([detectOuterClothQuadCornersPx]) and,
     * inside it, the cushion-nose quad.
     *
     * The orientation (end-on or landscape) is decided once on the outer quad and carried in
     * [TableQuadDetection.endOnView]; it fixes which table side each image side is and which side
     * is nearest the camera.
     *
     * Each side of the outer quad is refined on its own. Across [NOSE_PROFILE_SAMPLES] sections
     * along the side, the colour profile is walked from inside the bed outwards and the sharp
     * steps (bed to cushion face, cushion top or rail) are listed, innermost first. A straight line
     * is fitted with RANSAC to the innermost steps; if the cloth-covered strip behind that line is
     * not a plausible cushion width ([MIN_CUSHION_WIDTH_METERS] to [MAX_CUSHION_WIDTH_METERS],
     * measured through the plane of the outer quad), the same is tried with the next step in each
     * section, and so on for up to four steps. A shadow or lit strip in front of the nose is thus
     * passed over for the step that is consistent with a cushion. On sides facing the camera the
     * accepted line is where the bed meets the cushion face; on the side nearest the camera, whose
     * face is hidden, it is the inner edge of the cushion top.
     *
     * A side falls back to insetting the outer edge by a typical cushion band
     * ([CUSHION_VISIBLE_BAND_METERS], or [CUSHION_TOP_WIDTH_METERS] on the side nearest the camera)
     * when no step level gives a line that both
     * - at least half of the sections agree on, and
     * - has a plausible cushion width of cloth-coloured material behind it. A line with the rail
     *   right behind it is the edge of the cloth region, with the cushion, the same colour as the
     *   bed, hidden inside it; one with a much wider strip behind it is a mark on the bed.
     *
     * The whole refinement is abandoned in favour of the all-fallback quad when a corner formed by
     * fitted lines is ill-conditioned (adjacent sides nearly parallel) or lands further from its
     * outer corner than [MAX_CORNER_SHIFT_METERS] along either table axis.
     *
     * [table] supplies the dimensions behind every conversion to metres: the cushion width window,
     * the fallback band, the cushion check and the table frame.
     */
    fun detectTableQuads(
        frame: ArgbImageFrame,
        cloth: DetectedClothSurface,
        table: TableSpec = TableSpec.nineFootPool(),
    ): TableQuadDetection {
        val outer = detectOuterClothQuadOrNull(frame, cloth)
            ?: return defaultInsetQuad(frame.width, frame.height).let { quad ->
                TableQuadDetection(
                    noseQuadCornersPx = quad,
                    outerQuadCornersPx = quad,
                    edges = List(4) { TableQuadEdgeFit(0.0, usedFallback = true, insetFromOuterPx = 0.0) },
                    endOnView = false,
                )
            }
        return refineToCushionNoses(frame, cloth, outer, table, isEndOnPerspectiveQuad(outer))
    }

    private fun defaultInsetQuad(w: Int, h: Int): List<Vec2> = listOf(
        Vec2(0.12 * w, 0.18 * h),
        Vec2(0.88 * w, 0.18 * h),
        Vec2(0.88 * w, 0.85 * h),
        Vec2(0.12 * w, 0.85 * h),
    )

    private fun detectOuterClothQuadOrNull(frame: ArgbImageFrame, cloth: DetectedClothSurface): List<Vec2>? {
        val w = frame.width
        val h = frame.height
        val xMin = (0.04 * w).toInt().coerceIn(0, w - 1)
        val xMax = (0.96 * w).toInt().coerceIn(xMin, w - 1)
        val yMin = (0.04 * h).toInt().coerceIn(0, h - 1)
        val yMax = (0.96 * h).toInt().coerceIn(yMin, h - 1)
        val step = max(1, min(w, h) / 360)

        val gw = (xMax - xMin) / step + 1
        val gh = (yMax - yMin) / step + 1
        val gTotal = gw * gh
        val clothGrid = BooleanArray(gTotal)
        val hsv = DoubleArray(3)

        for (gy in 0 until gh) {
            val py = yMin + gy * step
            val rowOffset = gy * gw
            for (gx in 0 until gw) {
                val px = xMin + gx * step
                val argb = frame.argbPixels[py * w + px]
                hsvInto(argb, hsv)
                if (isClothArgbHsv(argb, hsv[0], hsv[1], hsv[2], cloth)) {
                    clothGrid[rowOffset + gx] = true
                }
            }
        }

        // Seed connected cloth component from the central playing surface [0.32..0.68] x [0.30..0.70]
        val visited = BooleanArray(gTotal)
        val queue = IntArray(gTotal)
        var head = 0
        var tail = 0
        val sx0 = (((0.32 * w).toInt() - xMin) / step).coerceIn(0, gw - 1)
        val sx1 = (((0.68 * w).toInt() - xMin) / step).coerceIn(sx0, gw - 1)
        val sy0 = (((0.30 * h).toInt() - yMin) / step).coerceIn(0, gh - 1)
        val sy1 = (((0.70 * h).toInt() - yMin) / step).coerceIn(sy0, gh - 1)
        for (gy in sy0..sy1) {
            val rowOffset = gy * gw
            for (gx in sx0..sx1) {
                val idx = rowOffset + gx
                if (clothGrid[idx] && !visited[idx]) {
                    visited[idx] = true
                    queue[tail++] = idx
                }
            }
        }
        while (head < tail) {
            val cur = queue[head++]
            val gx = cur % gw
            val gy = cur / gw
            if (gx > 0) {
                val n = cur - 1
                if (clothGrid[n] && !visited[n]) {
                    visited[n] = true
                    queue[tail++] = n
                }
            }
            if (gx < gw - 1) {
                val n = cur + 1
                if (clothGrid[n] && !visited[n]) {
                    visited[n] = true
                    queue[tail++] = n
                }
            }
            if (gy > 0) {
                val n = cur - gw
                if (clothGrid[n] && !visited[n]) {
                    visited[n] = true
                    queue[tail++] = n
                }
            }
            if (gy < gh - 1) {
                val n = cur + gw
                if (clothGrid[n] && !visited[n]) {
                    visited[n] = true
                    queue[tail++] = n
                }
            }
        }

        val useConnected = tail >= MIN_CLOTH_PIXELS_FOR_QUAD
        val activeMask = if (useConnected) visited else clothGrid
        var count = 0
        var cMinX = Int.MAX_VALUE
        var cMaxX = Int.MIN_VALUE
        var cMinY = Int.MAX_VALUE
        var cMaxY = Int.MIN_VALUE
        for (gy in 0 until gh) {
            val y = yMin + gy * step
            val rowOffset = gy * gw
            for (gx in 0 until gw) {
                if (activeMask[rowOffset + gx]) {
                    val x = xMin + gx * step
                    count++
                    if (x < cMinX) cMinX = x
                    if (x > cMaxX) cMaxX = x
                    if (y < cMinY) cMinY = y
                    if (y > cMaxY) cMaxY = y
                }
            }
        }

        if (count < MIN_CLOTH_PIXELS_FOR_QUAD) return null

        val spanX = max(1.0, (cMaxX - cMinX).toDouble())
        val spanY = max(1.0, (cMaxY - cMinY).toDouble())
        var tlSum = Double.POSITIVE_INFINITY
        var tlX = 0
        var tlY = 0
        var brSum = Double.NEGATIVE_INFINITY
        var brX = 0
        var brY = 0
        var trDiff = Double.NEGATIVE_INFINITY
        var trX = 0
        var trY = 0
        var blDiff = Double.POSITIVE_INFINITY
        var blX = 0
        var blY = 0

        for (gy in 0 until gh) {
            val y = yMin + gy * step
            val ny = (y - cMinY) / spanY
            val rowOffset = gy * gw
            for (gx in 0 until gw) {
                if (activeMask[rowOffset + gx]) {
                    val x = xMin + gx * step
                    val nx = (x - cMinX) / spanX
                    val sum = nx + ny
                    val diff = nx - ny
                    if (sum < tlSum) {
                        tlSum = sum
                        tlX = x
                        tlY = y
                    }
                    if (sum > brSum) {
                        brSum = sum
                        brX = x
                        brY = y
                    }
                    if (diff > trDiff) {
                        trDiff = diff
                        trX = x
                        trY = y
                    }
                    if (diff < blDiff) {
                        blDiff = diff
                        blX = x
                        blY = y
                    }
                }
            }
        }

        val rawQuad = listOf(
            Vec2(tlX.toDouble(), tlY.toDouble()),
            Vec2(trX.toDouble(), trY.toDouble()),
            Vec2(brX.toDouble(), brY.toDouble()),
            Vec2(blX.toDouble(), blY.toDouble()),
        )
        return if (isEndOnPerspectiveQuad(rawQuad)) {
            val topShift = if (cloth.meanSaturation < 0.45) 9.0 else 6.0
            refineEndOnBroadcastQuad(activeMask, gw, gh, xMin, yMin, step, w, h, topShift, rawQuad)
        } else {
            rawQuad
        }
    }

    /**
     * Refines an end-on broadcast table trapezoid by intersecting the top/bottom head/foot cushion
     * lines with the left/right side cushion lines of the connected cloth component [clothMask],
     * so that dark circular corner-pocket cutouts do not shift the four corners inward.
     */
    private fun refineEndOnBroadcastQuad(
        clothMask: BooleanArray,
        gw: Int,
        gh: Int,
        xMin: Int,
        yMin: Int,
        step: Int,
        w: Int,
        h: Int,
        topShiftPx: Double,
        fallbackQuad: List<Vec2>,
    ): List<Vec2> {
        val midGx0 = (((0.38 * w).toInt() - xMin) / step).coerceIn(0, gw - 1)
        val midGx1 = (((0.62 * w).toInt() - xMin) / step).coerceIn(midGx0, gw - 1)
        val topYs = ArrayList<Double>()
        val botYs = ArrayList<Double>()
        for (gx in midGx0..midGx1) {
            for (gy in 0 until gh / 2) {
                if (clothMask[gy * gw + gx]) {
                    topYs.add((yMin + gy * step).toDouble())
                    break
                }
            }
            for (gy in gh - 1 downTo gh / 2) {
                if (clothMask[gy * gw + gx]) {
                    botYs.add((yMin + gy * step).toDouble())
                    break
                }
            }
        }
        if (topYs.size < 4 || botYs.size < 4) return fallbackQuad
        topYs.sort()
        botYs.sort()
        val yTop = (topYs[topYs.size / 2] - topShiftPx).coerceIn(0.0, h - 1.0)
        val yBot = (botYs[botYs.size / 2] - 1.0).coerceIn(yTop + 20.0, h - 1.0)

        fun rowLeftX(gy: Int): Double? {
            val rowOffset = gy * gw
            for (gx in 0 until gw / 2) {
                if (clothMask[rowOffset + gx]) {
                    return (xMin + gx * step).toDouble()
                }
            }
            return null
        }

        fun rowRightX(gy: Int): Double? {
            val rowOffset = gy * gw
            for (gx in gw - 1 downTo gw / 2) {
                if (clothMask[rowOffset + gx]) {
                    return (xMin + gx * step).toDouble()
                }
            }
            return null
        }

        val spanY = yBot - yTop
        val gyU0 = (((yTop + 0.24 * spanY).toInt() - yMin) / step).coerceIn(0, gh - 1)
        val gyU1 = (((yTop + 0.40 * spanY).toInt() - yMin) / step).coerceIn(gyU0, gh - 1)
        val gyL0 = (((yTop + 0.62 * spanY).toInt() - yMin) / step).coerceIn(0, gh - 1)
        val gyL1 = (((yTop + 0.84 * spanY).toInt() - yMin) / step).coerceIn(gyL0, gh - 1)

        val leftUpper = ArrayList<Double>()
        val rightUpper = ArrayList<Double>()
        for (gy in gyU0..gyU1) {
            rowLeftX(gy)?.let { leftUpper.add(it - 2.0) }
            rowRightX(gy)?.let { rightUpper.add(it + 2.0) }
        }
        val leftLower = ArrayList<Double>()
        val rightLower = ArrayList<Double>()
        for (gy in gyL0..gyL1) {
            rowLeftX(gy)?.let { leftLower.add(it + 2.0) }
            rowRightX(gy)?.let { rightLower.add(it - 2.0) }
        }
        if (leftUpper.isEmpty() || rightUpper.isEmpty() || leftLower.isEmpty() || rightLower.isEmpty()) {
            return fallbackQuad
        }
        leftUpper.sort()
        rightUpper.sort()
        leftLower.sort()
        rightLower.sort()

        val yU = yTop + 0.29 * spanY
        val yL = yTop + 0.73 * spanY
        var xLU = leftUpper[leftUpper.size / 2]
        val xRU = rightUpper[rightUpper.size / 2]
        var xLL = leftLower[leftLower.size / 2]
        val xRL = rightLower[rightLower.size / 2]

        val slopeR = (xRL - xRU) / (yL - yU)
        var slopeL = (xLL - xLU) / (yL - yU)
        if (slopeR > 0.15) {
            val leftMag = -slopeL
            if (leftMag > 1.18 * slopeR) {
                // Upper-left rail partially occluded inward (e.g. player's arm); anchor on lower-left rail
                slopeL = -1.03 * slopeR
                xLU = xLL - slopeL * (yL - yU)
            } else if (leftMag < 0.82 * slopeR) {
                // Lower-left rail partially occluded inward (e.g. cue stick); anchor on upper-left rail
                slopeL = -1.03 * slopeR
                xLL = xLU + slopeL * (yL - yU)
            }
        }
        val tlX = (xLU + slopeL * (yTop - yU)).coerceIn(0.0, w - 1.0)
        val blX = (xLU + slopeL * (yBot - yU)).coerceIn(0.0, w - 1.0)
        val trX = (xRU + slopeR * (yTop - yU)).coerceIn(0.0, w - 1.0)
        val brX = (xRU + slopeR * (yBot - yU)).coerceIn(0.0, w - 1.0)

        return listOf(
            Vec2(tlX, yTop),
            Vec2(trX, yTop),
            Vec2(brX, yBot),
            Vec2(blX, yBot),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Cushion noses
    // ---------------------------------------------------------------------------------------

    /** A straight image line through [point] along the unit vector [direction]. */
    private class ImageLine(val point: Vec2, val direction: Vec2) {
        val second: Vec2 get() = point + direction

        fun distanceTo(p: Vec2): Double = abs(direction.x * (p.y - point.y) - direction.y * (p.x - point.x))

        companion object {
            fun through(a: Vec2, b: Vec2): ImageLine {
                val len = a.distanceTo(b)
                return ImageLine(a, if (len < 1e-12) Vec2(1.0, 0.0) else (b - a) / len)
            }
        }
    }

    private class SideScan(val line: ImageLine?, val confidence: Double)

    /** Geometry of one side of the outer quad, with its normal pointing into the table. */
    private class OuterSide(outer: List<Vec2>, val index: Int, centroid: Vec2) {
        val a: Vec2 = outer[index]
        val b: Vec2 = outer[(index + 1) % 4]
        val opposite: ImageLine = ImageLine.through(outer[(index + 2) % 4], outer[(index + 3) % 4])
        val length: Double = a.distanceTo(b)
        val inward: Vec2

        init {
            val ex = b.x - a.x
            val ey = b.y - a.y
            val len = max(length, 1e-12)
            var n = Vec2(-ey / len, ex / len)
            val mid = (a + b) * 0.5
            if ((centroid.x - mid.x) * n.x + (centroid.y - mid.y) * n.y < 0.0) n = n * -1.0
            inward = n
        }

        fun pointAt(t: Double): Vec2 = a + (b - a) * t

        /** Distance from [p] across the table to the opposite side, along [inward]. */
        fun acrossFrom(p: Vec2): Double? =
            lineIntersection(p, p + inward, opposite.point, opposite.second)?.distanceTo(p)

        /** Signed distance along [inward] from the point at [t] to [line] (negative: outside). */
        fun insetTo(line: ImageLine, t: Double): Double? {
            val p = pointAt(t)
            val hit = lineIntersection(p, p + inward, line.point, line.second) ?: return null
            return (hit.x - p.x) * inward.x + (hit.y - p.y) * inward.y
        }
    }

    private fun lineIntersection(p1: Vec2, p2: Vec2, p3: Vec2, p4: Vec2): Vec2? {
        val d = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x)
        if (abs(d) < 1e-9) return null
        val a = p1.x * p2.y - p1.y * p2.x
        val b = p3.x * p4.y - p3.y * p4.x
        val x = (a * (p3.x - p4.x) - (p1.x - p2.x) * b) / d
        val y = (a * (p3.y - p4.y) - (p1.y - p2.y) * b) / d
        return if (x.isFinite() && y.isFinite()) Vec2(x, y) else null
    }

    /** Bilinear RGB sample at a sub-pixel position, clamped to the frame. */
    private fun sampleRgbInto(frame: ArgbImageFrame, x: Double, y: Double, out: DoubleArray) {
        val x0 = floor(x).toInt().coerceIn(0, max(0, frame.width - 2))
        val y0 = floor(y).toInt().coerceIn(0, max(0, frame.height - 2))
        val ax = (x - x0).coerceIn(0.0, 1.0)
        val ay = (y - y0).coerceIn(0.0, 1.0)
        val p00 = frame.pixelAt(x0, y0)
        val p10 = frame.pixelAt(x0 + 1, y0)
        val p01 = frame.pixelAt(x0, y0 + 1)
        val p11 = frame.pixelAt(x0 + 1, y0 + 1)
        val w00 = (1.0 - ax) * (1.0 - ay)
        val w10 = ax * (1.0 - ay)
        val w01 = (1.0 - ax) * ay
        val w11 = ax * ay
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            out[c] = w00 * ((p00 ushr shift) and 0xFF) + w10 * ((p10 ushr shift) and 0xFF) +
                w01 * ((p01 ushr shift) and 0xFF) + w11 * ((p11 ushr shift) and 0xFF)
        }
    }

    /**
     * Samples cross-sections of one outer side and lists the candidate cushion-nose points of each.
     *
     * Each section runs along the inward normal from just outside the outer edge to
     * [NOSE_BAND_FRACTION] of the way across the table. The step response at a position is the
     * mean RGB difference between the few pixels just outside and just inside it. Walking from the
     * inner end outwards, every response above `max(8, 4 x bed noise)` (refined to its local
     * maximum) is a step, and up to [NOSE_MAX_STEP_LEVELS] of them are kept, innermost first. The
     * innermost is normally the nose: the bed's own slow shading never produces a sharp step,
     * while the cushion face, a cushion top of another shade or the rail does. Further steps are
     * kept so that a shadow or lit strip lying in front of the nose can be told apart from it by
     * its distance to the end of the cushion (see [cushionWidthBeyond]).
     *
     * Element `k` of the result holds the steps of the `k`-th section (empty when none was found
     * or the section was skipped).
     */
    private fun scanSideForNoseSteps(
        frame: ArgbImageFrame,
        side: OuterSide,
        scale: Double,
    ): List<List<Vec2>> {
        if (side.length < 8.0) return emptyList()
        val step = 0.5 * scale
        val window = 6
        val sections = ArrayList<List<Vec2>>(NOSE_PROFILE_SAMPLES)
        val rgb = DoubleArray(3)
        val n = side.inward
        for (k in 0 until NOSE_PROFILE_SAMPLES) {
            val t = 0.06 + 0.88 * k / (NOSE_PROFILE_SAMPLES - 1)
            val p = side.pointAt(t)
            val across = side.acrossFrom(p) ?: continue
            if (across < 20.0 * scale) continue
            val dMax = max(10.0 * scale, NOSE_BAND_FRACTION * across)
            val d0 = -8.0 * scale
            val count = ((dMax + 12.0 * scale - d0) / step).toInt()
            if (count <= 2 * window + 2) continue
            // Prefix sums of the RGB profile so every window mean is O(1).
            val prefix = Array(3) { DoubleArray(count + 1) }
            for (j in 0 until count) {
                val d = d0 + j * step
                sampleRgbInto(frame, p.x + n.x * d, p.y + n.y * d, rgb)
                for (c in 0 until 3) prefix[c][j + 1] = prefix[c][j] + rgb[c]
            }
            fun meanDiff(j: Int): Double {
                val o0 = max(0, j - window)
                val o1 = j
                val i0 = j
                val i1 = min(count, j + window)
                if (o1 <= o0 || i1 <= i0) return 0.0
                var sum = 0.0
                for (c in 0 until 3) {
                    val outer = (prefix[c][o1] - prefix[c][o0]) / (o1 - o0)
                    val inner = (prefix[c][i1] - prefix[c][i0]) / (i1 - i0)
                    sum += abs(outer - inner)
                }
                return sum / 3.0
            }
            val jMax = ((dMax - d0) / step).toInt().coerceIn(window, count - window)
            val bedResponses = DoubleArray(max(0, count - window - jMax)) { meanDiff(jMax + it) }
            bedResponses.sort()
            val noise = if (bedResponses.isEmpty()) 2.0 else bedResponses[bedResponses.size * 3 / 4]
            val threshold = max(NOSE_STEP_MIN_CONTRAST, NOSE_STEP_NOISE_FACTOR * noise)
            val steps = ArrayList<Vec2>(NOSE_MAX_STEP_LEVELS)
            var j = jMax
            while (j >= window && steps.size < NOSE_MAX_STEP_LEVELS) {
                if (meanDiff(j) > threshold) {
                    var best = j
                    while (best - 1 >= window && meanDiff(best - 1) >= meanDiff(best)) best--
                    val d = d0 + (best - 0.5) * step
                    steps.add(Vec2(p.x + n.x * d, p.y + n.y * d))
                    // Walk past the rest of this step before looking for the next one.
                    j = best - 1
                    while (j >= window && meanDiff(j) > threshold) j--
                } else {
                    j--
                }
            }
            sections.add(steps)
        }
        return sections
    }

    /**
     * Deterministic RANSAC over all point pairs at least a quarter of the side apart, followed by a
     * total-least-squares refit of the inliers. Confidence is the inlier share of all sections.
     */
    private fun fitLineRansac(points: List<Vec2>, sideLength: Double, tolerancePx: Double): SideScan {
        var bestCount = 0
        var bestLine: ImageLine? = null
        for (p in points.indices) {
            for (q in p + 1 until points.size) {
                if (points[p].distanceTo(points[q]) < 0.25 * sideLength) continue
                val candidate = ImageLine.through(points[p], points[q])
                val inliers = points.count { candidate.distanceTo(it) <= tolerancePx }
                if (inliers > bestCount) {
                    bestCount = inliers
                    bestLine = candidate
                }
            }
        }
        val seed = bestLine ?: return SideScan(null, 0.0)
        val inliers = points.filter { seed.distanceTo(it) <= tolerancePx }
        val cx = inliers.sumOf { it.x } / inliers.size
        val cy = inliers.sumOf { it.y } / inliers.size
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (s in inliers) {
            sxx += (s.x - cx) * (s.x - cx)
            sxy += (s.x - cx) * (s.y - cy)
            syy += (s.y - cy) * (s.y - cy)
        }
        val angle = 0.5 * atan2(2.0 * sxy, sxx - syy)
        return SideScan(
            line = ImageLine(Vec2(cx, cy), Vec2(cos(angle), sin(angle))),
            confidence = inliers.size.toDouble() / NOSE_PROFILE_SAMPLES,
        )
    }

    /**
     * True when the pixel belongs to the cloth family: the cloth hue (or, on grey cloth, the cloth's
     * tint) at a different brightness, as a cushion top or face is, as opposed to a rail.
     */
    private fun isClothFamilyPixel(argb: Int, cloth: DetectedClothSurface, hsv: DoubleArray): Boolean {
        hsvInto(argb, hsv)
        return if (cloth.meanSaturation >= GREY_MEDIAN_SATURATION) {
            hueDistance(hsv[0], cloth.dominantHueDegrees) <= 30.0 &&
                hsv[1] >= 0.5 * cloth.meanSaturation &&
                hsv[2] >= 0.25
        } else if (hsv[1] > 0.25 || hsv[2] < 0.25) {
            false
        } else {
            val clothValue = max(cloth.meanValue, 1e-6)
            val r = ((argb ushr 16) and 0xFF) / 255.0
            val g = ((argb ushr 8) and 0xFF) / 255.0
            val b = (argb and 0xFF) / 255.0
            abs((r - b) / hsv[2] - cloth.meanRedMinusBlue / clothValue) <= 0.08 &&
                abs((r - g) / hsv[2] - cloth.meanRedMinusGreen / clothValue) <= 0.08
        }
    }

    /**
     * Width in metres of the cloth-family strip that lies beyond [line], that is the cushion width
     * if [line] is the nose: the median, over 40 positions along the side, of the distance (through
     * [outerPlane]) from the line to the first place where the cloth family ends for two probes in
     * a row. Probing starts [MIN_CUSHION_WIDTH_METERS] beyond the line, so the shadow zone at the
     * nose is ignored and a line at the very edge of the cloth region, with the rail right behind
     * it, reports exactly the minimum. Probing stops after [MAX_CUSHION_WIDTH_METERS] with the
     * width reported as infinite. Positions whose probes leave the frame are skipped; returns null
     * when fewer than eight positions remain.
     *
     * The width is measured from the line to where the cloth ends rather than from the outer
     * outline: the outline can sit well inside the cushion back where the corner pockets cut the
     * cloth region, while this distance does not depend on it.
     */
    private fun cushionWidthBeyond(
        frame: ArgbImageFrame,
        cloth: DetectedClothSurface,
        side: OuterSide,
        line: ImageLine,
        outerPlane: PerspectiveHomography,
        frameOnTable: TableFrame,
    ): Double? {
        val hsv = DoubleArray(3)
        val outward = frameOnTable.outward[side.index]
        val widths = ArrayList<Double>(40)
        for (k in 0 until 40) {
            val t = 0.1 + 0.8 * k / 39.0
            val p = side.pointAt(t)
            val onLine = lineIntersection(p, p + side.inward, line.point, line.second) ?: continue
            val onTable = outerPlane.imageToTableCloth(onLine)
            var width = Double.POSITIVE_INFINITY
            var previousWasFamily = true
            var valid = true
            var s = MIN_CUSHION_WIDTH_METERS
            while (s <= MAX_CUSHION_WIDTH_METERS + 1e-9) {
                val probe = outerPlane.tableClothToImage(onTable + outward * s)
                val x = probe.x.roundToInt()
                val y = probe.y.roundToInt()
                if (!probe.x.isFinite() || !probe.y.isFinite() || x !in 0 until frame.width || y !in 0 until frame.height) {
                    valid = false
                    break
                }
                val isFamily = isClothFamilyPixel(frame.argbPixels[y * frame.width + x], cloth, hsv)
                if (!isFamily && !previousWasFamily) {
                    width = s - WIDTH_PROBE_STEP_METERS
                    break
                }
                if (!isFamily && s <= MIN_CUSHION_WIDTH_METERS + 1e-9) {
                    // Already off the cloth at the minimum width: nothing to measure beyond it.
                    width = MIN_CUSHION_WIDTH_METERS
                    break
                }
                previousWasFamily = isFamily
                s += WIDTH_PROBE_STEP_METERS
            }
            if (valid) widths.add(width)
        }
        if (widths.size < 8) return null
        widths.sort()
        return widths[widths.size / 2]
    }
    /**
     * The table rectangle in the corner order of a raw image quad (`[TL, TR, BR, BL]`), with each
     * side's outward unit normal in table coordinates and the index of the side nearest the camera.
     *
     * End-on views look along the table from behind the foot rail: the top side of the image is the
     * head rail, the bottom side the foot rail, and the foot rail is the near one. Landscape views
     * have the long rails on top and bottom and are taken from beyond the lower one, the `y = W`
     * rail. In both the near side is the bottom side of the image.
     */
    private class TableFrame(val rect: List<Vec2>, val outward: List<Vec2>, val nearSide: Int)

    private fun tableFrameFor(endOn: Boolean, table: TableSpec): TableFrame {
        val l = table.lengthMeters
        val w = table.widthMeters
        val rect = if (endOn) {
            listOf(Vec2(0.0, 0.0), Vec2(0.0, w), Vec2(l, w), Vec2(l, 0.0))
        } else {
            listOf(Vec2(0.0, 0.0), Vec2(l, 0.0), Vec2(l, w), Vec2(0.0, w))
        }
        val centre = Vec2(0.5 * l, 0.5 * w)
        val outward = (0 until 4).map { i ->
            val mid = (rect[i] + rect[(i + 1) % 4]) * 0.5 - centre
            if (abs(mid.x) > abs(mid.y)) Vec2(sign(mid.x), 0.0) else Vec2(0.0, sign(mid.y))
        }
        val nearOutward = if (endOn) Vec2(1.0, 0.0) else Vec2(0.0, 1.0)
        val nearSide = outward.indexOfFirst { it.dot(nearOutward) > 0.5 }
        return TableFrame(rect, outward, nearSide)
    }

    /** Cushion band seen beyond the nose: the top only on the side nearest the camera. */
    private fun cushionBandMeters(side: Int, nearSide: Int): Double =
        if (side == nearSide) CUSHION_TOP_WIDTH_METERS else CUSHION_VISIBLE_BAND_METERS

    /** The table rectangle of [frame] pushed out on each side by `bands[side]` metres. */
    private fun expandedRect(frame: TableFrame, bands: DoubleArray): List<Vec2> = (0 until 4).map { i ->
        val prev = (i + 3) % 4
        frame.rect[i] + frame.outward[prev] * bands[prev] + frame.outward[i] * bands[i]
    }

    private fun refineToCushionNoses(
        frame: ArgbImageFrame,
        cloth: DetectedClothSurface,
        outer: List<Vec2>,
        table: TableSpec,
        endOn: Boolean,
    ): TableQuadDetection {
        // Edge blur grows with resolution only on large captures; screenshots up to ~1000 px keep
        // one-pixel edges, so the profile step and window only widen above that.
        val scale = max(1.0, min(frame.width, frame.height) / 1000.0)
        val centroid = Vec2(outer.sumOf { it.x } / 4.0, outer.sumOf { it.y } / 4.0)
        val sides = (0 until 4).map { OuterSide(outer, it, centroid) }
        val frameOnTable = tableFrameFor(endOn, table)
        val nearSide = frameOnTable.nearSide
        val nominalBands = DoubleArray(4) { cushionBandMeters(it, nearSide) }
        val outerPlane = try {
            PerspectiveHomography.fromPointCorrespondences(outer, expandedRect(frameOnTable, nominalBands))
        } catch (e: IllegalArgumentException) {
            null
        }
        val scans = ArrayList<SideScan>(4)
        val accepted = BooleanArray(4)
        for (i in 0 until 4) {
            val side = sides[i]
            val steps = if (outerPlane == null) emptyList() else scanSideForNoseSteps(frame, side, scale)
            // The innermost step is normally the nose. When its line is not a cushion edge (a
            // shadow or lit strip in front of it, or the edge of the cloth region), try the next
            // step: the line that is followed by a plausible cushion width is the nose.
            var chosen = SideScan(null, 0.0)
            for (level in 0 until NOSE_MAX_STEP_LEVELS) {
                val points = steps.mapNotNull { it.getOrNull(level) }
                val scan = fitLineRansac(points, side.length, 1.6 * scale)
                if (level == 0) chosen = scan
                val line = scan.line
                if (line == null || outerPlane == null || scan.confidence < NOSE_MIN_CONFIDENCE) continue
                val width = cushionWidthBeyond(frame, cloth, side, line, outerPlane, frameOnTable)
                if (width != null && width > MIN_CUSHION_WIDTH_METERS && width <= MAX_CUSHION_WIDTH_METERS) {
                    chosen = scan
                    accepted[i] = true
                    break
                }
            }
            scans.add(chosen)
        }
        val nose = composeNoseQuad(sides, scans, accepted, frameOnTable, nominalBands, outer, outerPlane)
            ?: composeNoseQuad(sides, scans, BooleanArray(4), frameOnTable, nominalBands, outer, outerPlane)
        if (nose == null) {
            return TableQuadDetection(
                noseQuadCornersPx = outer,
                outerQuadCornersPx = outer,
                edges = List(4) { TableQuadEdgeFit(FALLBACK_EDGE_CONFIDENCE, usedFallback = true, insetFromOuterPx = 0.0) },
                endOnView = endOn,
            )
        }
        val usedAll = nose.second
        val edges = (0 until 4).map { i ->
            val side = sides[i]
            val noseSide = ImageLine.through(nose.first[i], nose.first[(i + 1) % 4])
            val inset = side.insetTo(noseSide, 0.5) ?: 0.0
            if (usedAll[i]) {
                TableQuadEdgeFit(scans[i].confidence, usedFallback = false, insetFromOuterPx = inset)
            } else {
                TableQuadEdgeFit(FALLBACK_EDGE_CONFIDENCE, usedFallback = true, insetFromOuterPx = inset)
            }
        }
        return TableQuadDetection(
            noseQuadCornersPx = nose.first,
            outerQuadCornersPx = outer,
            edges = edges,
            endOnView = endOn,
        )
    }

    /**
     * Intersects the accepted nose lines with the outer edges of the remaining sides, then maps the
     * remaining sides inwards by their nominal cushion band ([nominalBands], metres) through the
     * homography of that mixed quad. Returns the nose corners and which sides kept their measured
     * line, or null when the result cannot be trusted: a corner formed by two sides whose
     * directions are nearly parallel ([MIN_CORNER_SIN]), a non-convex or non-finite quad, or a
     * corner that moved more than [MAX_CORNER_SHIFT_METERS] along a table axis from its outer
     * corner (checked through [outerPlane] when there is one).
     */
    private fun composeNoseQuad(
        sides: List<OuterSide>,
        scans: List<SideScan>,
        accepted: BooleanArray,
        frameOnTable: TableFrame,
        nominalBands: DoubleArray,
        outer: List<Vec2>,
        outerPlane: PerspectiveHomography?,
    ): Pair<List<Vec2>, BooleanArray>? {
        val lines = (0 until 4).map { i ->
            val line = scans[i].line
            if (accepted[i] && line != null) line else ImageLine.through(sides[i].a, sides[i].b)
        }
        for (i in 0 until 4) {
            val prev = lines[(i + 3) % 4].direction
            val cur = lines[i].direction
            if (!isCornerWellConditioned(prev, cur)) return null
        }
        val mixed = (0 until 4).map { i ->
            val prev = lines[(i + 3) % 4]
            val cur = lines[i]
            lineIntersection(prev.point, prev.second, cur.point, cur.second) ?: return null
        }
        if (!isConvexQuad(mixed)) return null
        val nose = if (accepted.all { it }) {
            mixed
        } else {
            val bands = DoubleArray(4) { if (accepted[it]) 0.0 else nominalBands[it] }
            val homography = try {
                PerspectiveHomography.fromPointCorrespondences(mixed, expandedRect(frameOnTable, bands))
            } catch (e: IllegalArgumentException) {
                return null
            }
            frameOnTable.rect.map { homography.tableClothToImage(it) }
        }
        if (nose.any { !it.x.isFinite() || !it.y.isFinite() } || !isConvexQuad(nose)) return null
        if (outerPlane != null) {
            for (i in 0 until 4) {
                val shift = outerPlane.imageToTableCloth(nose[i]) -
                    outerPlane.imageToTableCloth(outer[i])
                if (!isCornerShiftPlausible(shift)) return null
            }
        }
        return nose to accepted.copyOf()
    }

    /**
     * True when two adjacent sides with directions [a] and [b] meet at a corner that can be
     * located: the sine of the angle between them is at least [MIN_CORNER_SIN].
     */
    internal fun isCornerWellConditioned(a: Vec2, b: Vec2): Boolean {
        val scale = a.length() * b.length()
        return scale > 1e-12 && abs(a.cross(b)) / scale >= MIN_CORNER_SIN
    }

    /** True when a nose corner moved by [shift] metres from its outer corner is plausible. */
    internal fun isCornerShiftPlausible(shift: Vec2): Boolean =
        abs(shift.x) <= MAX_CORNER_SHIFT_METERS && abs(shift.y) <= MAX_CORNER_SHIFT_METERS

    private fun isConvexQuad(quad: List<Vec2>): Boolean {
        if (quad.size != 4) return false
        var orientation = 0.0
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val c = quad[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-9) return false
            if (orientation == 0.0) orientation = sign(cross) else if (sign(cross) != orientation) return false
        }
        return true
    }


    /**
     * True when [quadCornersPx] (`[topLeft, topRight, bottomRight, bottomLeft]`) is an end-on
     * perspective broadcast view (camera behind the near short foot rail looking toward the far
     * short head rail) rather than a horizontal landscape view.
     */
    fun isEndOnPerspectiveQuad(quadCornersPx: List<Vec2>): Boolean {
        if (quadCornersPx.size != 4) return false
        val tl = quadCornersPx[0]
        val tr = quadCornersPx[1]
        val br = quadCornersPx[2]
        val bl = quadCornersPx[3]
        val topWidth = tl.distanceTo(tr)
        val bottomWidth = bl.distanceTo(br)
        val avgWidth = 0.5 * (topWidth + bottomWidth)
        val verticalSpan = 0.5 * (abs(bl.y - tl.y) + abs(br.y - tr.y))
        if (verticalSpan < 1e-6) return false
        return topWidth < 0.85 * bottomWidth && (avgWidth / verticalSpan) < 1.55
    }

    /**
     * Returns `(orderedQuadCornersPx, defaultCameraPoseMeters)` suitable for
     * [PerspectiveHomography.fromTableQuad].
     *
     * For landscape views, `[TL, TR, BR, BL]` already corresponds to
     * `[headLeftPx, footLeftPx, footRightPx, headRightPx]`. For end-on broadcast views (far short
     * head rail at top `TL..TR`, near short foot rail at bottom `BL..BR`), reorders the corners to
     * `[TL, BL, BR, TR]` and places the camera pose behind the foot end.
     *
     * Pass [TableQuadDetection.endOnView] (or [RawFrameDetectionSummary.endOnView]) as [endOnView]
     * for a quad that came from the detector, so the orientation is the one already decided on the
     * outer outline; the default decides from [quadCornersPx] itself, which can differ for a
     * borderline trapezoid because the nose quad is narrower than the outline.
     */
    fun orientQuadForHomography(
        quadCornersPx: List<Vec2>,
        table: TableSpec = TableSpec.nineFootPool(),
        endOnView: Boolean = isEndOnPerspectiveQuad(quadCornersPx),
    ): Pair<List<Vec2>, Vec3> {
        if (!endOnView) {
            return quadCornersPx to Vec3(-0.55, table.widthMeters * 0.5, 1.55)
        }
        val tl = quadCornersPx[0]
        val tr = quadCornersPx[1]
        val br = quadCornersPx[2]
        val bl = quadCornersPx[3]
        return listOf(tl, bl, br, tr) to Vec3(table.lengthMeters + 0.65, table.widthMeters * 0.5, 1.55)
    }

    /**
     * True when image point `(x, y)` lies inside the convex [quad] and at least [insetPx] pixels
     * away from every edge (either winding order is accepted).
     */
    fun pointInQuad(x: Double, y: Double, quad: List<Vec2>, insetPx: Double = 0.0): Boolean {
        if (quad.size != 4) return false
        var area2 = 0.0
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            area2 += a.x * b.y - b.x * a.y
        }
        val sign = if (area2 >= 0.0) 1.0 else -1.0
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val len = hypot(ex, ey)
            if (len < 1e-9) continue
            val cross = ex * (y - a.y) - ey * (x - a.x)
            if (sign * cross / len < insetPx) return false
        }
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Frame analysis
    // ---------------------------------------------------------------------------------------

    /**
     * Full analysis of one frame: quality metrics, cloth, table quad and ball candidates.
     *
     * The table quad is the cushion-nose quad of [detectTableQuads] for [table]. Balls are the
     * connected non-cloth blobs inside the ball mask whose equivalent radius lies in
     * [expectedBallRadiusPxRange]; the mask is inset by [quadInsetPx] (landscape views) so rails and
     * pockets are excluded.
     *
     * The ball mask is the region where a ball can be seen: the nose quad pushed out on each side
     * over the cushion band. A ball frozen to a cushion has its edge on the nose line, but in
     * perspective its image overlaps the cushion, so a mask that stopped at the nose would cut it.
     * The same rule applies on a first analysis and on a re-analysis after the player moved a
     * corner. Each side is pushed out
     * - to the cushion back, as far as the outer cloth outline of the first analysis reaches
     *   beyond that side (from [detectTableQuads], or [previousQuads] on a re-analysis, measured
     *   on the table plane of the nose quad in use), and
     * - by at least a ball diameter and the nominal cushion band ([CUSHION_VISIBLE_BAND_METERS]),
     *   so that where the outline lies inside the nose (a corner pocket can pull a side of the
     *   outline in) or nothing is known about the outline, the whole ball is still inside.
     * Rail and pocket artefacts that the mask lets through are rejected by the blob checks, and a
     * blob whose table position is more than a ball radius outside the playing surface by
     * [BallIdentificationAndAssignment.rectifyAndIdentifyBalls] (it is not a ball on the table).
     *
     * [quadOverridePx] is a nose quad in image order `[TL, TR, BR, BL]`; with it the table quads are
     * not detected again. [previousQuads] are the quads of an earlier analysis of the same frame
     * ([RawFrameDetectionSummary.toQuadDetection]). [endOnView] fixes the orientation; when null it
     * is taken from [previousQuads], or decided on the override.
     */
    fun analyzeArgbFrame(
        frame: ArgbImageFrame,
        expectedBallRadiusPxRange: ClosedFloatingPointRange<Double> = 3.8..45.0,
        quadOverridePx: List<Vec2>? = null,
        quadInsetPx: Double = BLOB_INSET_PX,
        table: TableSpec = TableSpec.nineFootPool(),
        previousQuads: TableQuadDetection? = null,
        endOnView: Boolean? = null,
    ): RawFrameDetectionSummary {
        val w = frame.width
        val h = frame.height
        val total = w * h
        val hsv = DoubleArray(3)

        val luma = DoubleArray(total)
        var lumaSum = 0.0
        for (i in 0 until total) {
            val p = frame.argbPixels[i]
            val l = 0.299 * ((p ushr 16) and 0xFF) + 0.587 * ((p ushr 8) and 0xFF) + 0.114 * (p and 0xFF)
            luma[i] = l
            lumaSum += l
        }
        val meanLuma = lumaSum / total

        var gradSum = 0.0
        var gradCount = 0
        for (y in 0 until h - 1) {
            for (x in 0 until w - 1) {
                val i = y * w + x
                gradSum += (abs(luma[i + 1] - luma[i]) + abs(luma[i + w] - luma[i])) * 0.5
                gradCount++
            }
        }
        val meanGradient = if (gradCount > 0) gradSum / gradCount else 0.0
        val sharpness = (1.0 - exp(-meanGradient / SHARPNESS_GRADIENT_SCALE)).coerceIn(0.0, 1.0)

        val cx0 = (0.20 * w).toInt().coerceIn(0, w - 1)
        val cx1 = (0.80 * w).toInt().coerceIn(cx0, w - 1)
        val cy0 = (0.20 * h).toInt().coerceIn(0, h - 1)
        val cy1 = (0.80 * h).toInt().coerceIn(cy0, h - 1)
        var glare = 0
        var central = 0
        for (y in cy0..cy1) {
            for (x in cx0..cx1) {
                hsvInto(frame.argbPixels[y * w + x], hsv)
                if (hsv[2] > 0.96 && hsv[1] < 0.08) glare++
                central++
            }
        }
        val glareFraction = if (central > 0) glare.toDouble() / central else 0.0

        val cloth = detectClothSurface(frame)
        val quads = if (quadOverridePx == null) detectTableQuads(frame, cloth, table) else null
        val noseQuad = quadOverridePx ?: quads?.noseQuadCornersPx.orEmpty()
        val endOn = when {
            quads != null -> quads.endOnView
            else -> endOnView ?: previousQuads?.endOnView ?: isEndOnPerspectiveQuad(noseQuad)
        }
        val maskQuad = ballMaskQuad(noseQuad, endOn, table, quads ?: previousQuads)

        val candidates = findBallCandidates(frame, cloth, maskQuad, noseQuad, endOn, expectedBallRadiusPxRange, quadInsetPx)
        return RawFrameDetectionSummary(
            width = w,
            height = h,
            clothSurface = cloth,
            detectedQuadCornersPx = noseQuad,
            detectedCandidates = candidates,
            sharpnessScore = sharpness,
            glareFraction = glareFraction,
            meanLuma = meanLuma,
            outerQuadCornersPx = quads?.outerQuadCornersPx ?: previousQuads?.outerQuadCornersPx ?: maskQuad,
            quadEdges = quads?.edges.orEmpty(),
            endOnView = endOn,
            ballMaskCornersPx = maskQuad,
        )
    }

    /**
     * The region in which ball blobs are looked for: the nose quad pushed out on each side over the
     * cushion band (see [analyzeArgbFrame] for the rule), as a quad in the same corner order.
     *
     * Each side is pushed out to the cushion back where [known] gives it (the reach of the outer
     * outline beyond the nose, measured on the table plane of the nose quad), and by at least
     * [ballMaskMinBandMeters] so a ball frozen to a cushion is whole inside the mask even where the
     * outline lies inside the nose. Returns the nose quad itself when the table plane cannot be
     * built from it.
     */
    private fun ballMaskQuad(
        nose: List<Vec2>,
        endOn: Boolean,
        table: TableSpec,
        known: TableQuadDetection?,
    ): List<Vec2> {
        if (nose.size != 4) return nose
        val frame = tableFrameFor(endOn, table)
        val homography = try {
            PerspectiveHomography.fromPointCorrespondences(nose, frame.rect)
        } catch (e: IllegalArgumentException) {
            return nose
        }
        val minBand = ballMaskMinBandMeters(table)
        val bands = DoubleArray(4) { minBand }
        val outline = known?.outerQuadCornersPx?.takeIf { it.size == 4 && known.noseQuadCornersPx.size == 4 }
        if (outline != null) {
            val knownPlane = try {
                PerspectiveHomography.fromPointCorrespondences(known.noseQuadCornersPx, frame.rect)
            } catch (e: IllegalArgumentException) {
                null
            }
            if (knownPlane != null) {
                for (i in 0 until 4) {
                    val outward = frame.outward[i]
                    val noseEdge = outward.dot(frame.rect[i])
                    val reach = listOf(i, (i + 1) % 4).maxOf { corner ->
                        outward.dot(knownPlane.imageToTableCloth(outline[corner])) - noseEdge
                    }
                    if (reach.isFinite()) bands[i] = reach.coerceIn(minBand, MAX_MASK_BAND_METERS)
                }
            }
        }
        val mask = expandedRect(frame, bands).map { homography.tableClothToImage(it) }
        return if (mask.all { it.x.isFinite() && it.y.isFinite() } && isConvexQuad(mask)) mask else nose
    }

    /**
     * Least distance the ball mask reaches beyond a nose side, in metres: a ball diameter, and not
     * less than the visible cushion band. A ball frozen to a cushion has its edge on the nose line,
     * but in perspective its image reaches over the cushion by up to about that much.
     */
    private fun ballMaskMinBandMeters(table: TableSpec): Double =
        max(2.0 * table.ballRadiusMeters, CUSHION_VISIBLE_BAND_METERS)

    private fun canConnectForeground(typeA: Byte, typeB: Byte): Boolean {
        if (typeA == typeB) return true
        // 1 = Bright White, 2 = Dark Black, 3 = Non-Yellow Chromatic, 4 = Dim, 5 = Yellow Chromatic
        // White (1) only merges with Chromatic (3 or 5), never with Dark Black (2) or Dim (4).
        if (typeA == 1.toByte() || typeB == 1.toByte()) {
            val other = if (typeA == 1.toByte()) typeB else typeA
            return other == 3.toByte() || other == 5.toByte()
        }
        // Dark Black (2) only merges with Yellow Chromatic (5) (for yellow/black 9-ball),
        // never with Non-Yellow Chromatic (3) or White (1) so rails/pockets/gloves never merge with balls.
        if (typeA == 2.toByte() || typeB == 2.toByte()) {
            val other = if (typeA == 2.toByte()) typeB else typeA
            return other == 5.toByte()
        }
        return true
    }

    private fun isNearPocketMouth(
        center: Vec2,
        quad: List<Vec2>,
        isEndOn: Boolean,
        ballRadiusPx: Double = 10.0,
    ): Boolean {
        if (quad.size != 4) return false
        val cornerRadius = max(24.0, 3.3 * ballRadiusPx)
        for (corner in quad) {
            if (center.distanceTo(corner) <= cornerRadius) return true
        }
        val sideRadius = max(22.0, 2.8 * ballRadiusPx)
        if (isEndOn) {
            val topW = quad[0].distanceTo(quad[1])
            val botW = quad[3].distanceTo(quad[2])
            // Projective foreshortening maps the physical 50% rail midpoint to t = topW / (topW + botW).
            val t = if (topW + botW > 1e-6) (topW / (topW + botW)).coerceIn(0.32, 0.55) else 0.5
            val leftSide = quad[0] * (1.0 - t) + quad[3] * t
            val rightSide = quad[1] * (1.0 - t) + quad[2] * t
            if (center.distanceTo(leftSide) <= sideRadius || center.distanceTo(rightSide) <= sideRadius) return true
        } else {
            val topMid = (quad[0] + quad[1]) * 0.5
            val bottomMid = (quad[3] + quad[2]) * 0.5
            if (center.distanceTo(topMid) <= sideRadius || center.distanceTo(bottomMid) <= sideRadius) return true
        }
        return false
    }

    private fun findBallCandidates(
        frame: ArgbImageFrame,
        cloth: DetectedClothSurface,
        quad: List<Vec2>,
        noseQuad: List<Vec2>,
        isEndOn: Boolean,
        radiusRange: ClosedFloatingPointRange<Double>,
        insetPx: Double,
    ): List<DetectedBallCandidate> {
        val w = frame.width
        val h = frame.height
        val total = w * h
        val hsv = DoubleArray(3)
        val effectiveInsetPx = if (isEndOn) END_ON_BLOB_INSET_PX else insetPx

        val bx0 = floor(quad.minOf { it.x }).toInt().coerceIn(0, w - 1)
        val bx1 = ceil(quad.maxOf { it.x }).toInt().coerceIn(bx0, w - 1)
        val by0 = floor(quad.minOf { it.y }).toInt().coerceIn(0, h - 1)
        val by1 = ceil(quad.maxOf { it.y }).toInt().coerceIn(by0, h - 1)

        // 0 = cloth/shadow, 1 = bright white, 2 = dark black, 3 = chromatic, 4 = dim, 5 = yellow chromatic
        val fgType = ByteArray(total)
        for (y in by0..by1) {
            for (x in bx0..bx1) {
                if (!pointInQuad(x.toDouble(), y.toDouble(), quad, effectiveInsetPx)) continue
                val idx = y * w + x
                val argb = frame.argbPixels[idx]
                hsvInto(argb, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                if (isClothArgbHsv(argb, hue, sat, value, cloth) || isClothShadowHsv(hue, sat, value, cloth)) continue
                val chroma = sat * value
                val isBrightWhite = if (isBrightNeutralGreyCloth(cloth)) {
                    val rf = ((argb ushr 16) and 0xFF) / 255.0
                    val bf = (argb and 0xFF) / 255.0
                    (value >= min(0.89, cloth.meanValue + 0.07) && sat <= 0.18) ||
                        (cloth.meanSaturation >= 0.10 && value >= 0.68 && sat <= 0.24 && abs((rf - bf) - cloth.meanRedMinusBlue) > 0.055)
                } else {
                    (value >= 0.82 && sat <= 0.18) || (value >= 0.64 && sat <= 0.15)
                }
                fgType[idx] = when {
                    isBrightWhite -> 1
                    value <= 0.18 || (value <= 0.34 && chroma <= 0.16) -> 2
                    hue in 38.0..75.0 && sat >= 0.30 && value >= 0.34 -> 5
                    (sat >= 0.35 && value >= 0.24) ||
                        (
                            sat >= 0.20 &&
                                value >= 0.32 &&
                                (cloth.meanSaturation < GREY_MEDIAN_SATURATION || hueDistance(hue, cloth.dominantHueDegrees) > 35.0)
                            ) -> 3
                    else -> 4
                }
            }
        }

        class ForegroundComponent(
            val pixels: IntArray,
            val startType: Byte,
            val minX: Int,
            val maxX: Int,
            val minY: Int,
            val maxY: Int,
            val sumX: Double,
            val sumY: Double,
        ) {
            val area: Int get() = pixels.size
        }

        val quadTopWidth = quad[0].distanceTo(quad[1])
        val quadBotWidth = quad[3].distanceTo(quad[2])
        val quadAvgWidth = 0.5 * (quadTopWidth + quadBotWidth)
        val quadTopY = 0.5 * (quad[0].y + quad[1].y)
        val quadBotY = 0.5 * (quad[3].y + quad[2].y)
        val quadHeightY = max(1.0, quadBotY - quadTopY)
        val quadShortSidePx = if (isEndOn) quadAvgWidth else min(quadAvgWidth, quadHeightY)
        val geomExpectedBallRadius = (quadShortSidePx * 0.0225).coerceAtLeast(6.0)
        val effectiveRadiusRange = max(radiusRange.start, geomExpectedBallRadius * 0.36)..max(radiusRange.endInclusive, geomExpectedBallRadius * 2.1)

        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val components = ArrayList<ForegroundComponent>()
        val singleRadii = ArrayList<Double>()

        for (start in 0 until total) {
            val startType = fgType[start]
            if (startType == 0.toByte() || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var sumX = 0.0
            var sumY = 0.0
            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            while (head < tail) {
                val cur = queue[head++]
                val curType = fgType[cur]
                val px = cur % w
                val py = cur / w
                sumX += px
                sumY += py
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (px > 0) {
                    val n = cur - 1
                    val nt = fgType[n]
                    if (nt != 0.toByte() && !visited[n] && canConnectForeground(curType, nt)) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (px < w - 1) {
                    val n = cur + 1
                    val nt = fgType[n]
                    if (nt != 0.toByte() && !visited[n] && canConnectForeground(curType, nt)) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (py > 0) {
                    val n = cur - w
                    val nt = fgType[n]
                    if (nt != 0.toByte() && !visited[n] && canConnectForeground(curType, nt)) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
                if (py < h - 1) {
                    val n = cur + w
                    val nt = fgType[n]
                    if (nt != 0.toByte() && !visited[n] && canConnectForeground(curType, nt)) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
            }

            val area = tail
            val radiusPx = sqrt(area / PI)
            val minAllowedRadius = if (startType == 1.toByte() && geomExpectedBallRadius <= 12.0) {
                min(2.0, effectiveRadiusRange.start)
            } else {
                effectiveRadiusRange.start
            }
            if (radiusPx < minAllowedRadius || radiusPx > effectiveRadiusRange.endInclusive * 2.5) continue
            val pixelsCopy = queue.copyOf(area)
            components.add(ForegroundComponent(pixelsCopy, startType, minX, maxX, minY, maxY, sumX, sumY))

            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            val aspect = boxW.toDouble() / boxH
            val fill = area.toDouble() / (boxW.toDouble() * boxH)
            if (radiusPx in effectiveRadiusRange && aspect in 0.65..1.55 && fill >= 0.56) {
                singleRadii.add(radiusPx)
            }
        }

        val estimatedBallRadius = if (singleRadii.size >= 2) {
            singleRadii.sort()
            singleRadii[singleRadii.size / 2]
        } else {
            geomExpectedBallRadius
        }

        fun localExpectedRadius(centerY: Double): Double {
            if (!isEndOn || quadAvgWidth < 1e-6) return estimatedBallRadius
            val ty = ((centerY - quadTopY) / quadHeightY).coerceIn(0.0, 1.0)
            val localWidth = quadTopWidth + ty * (quadBotWidth - quadTopWidth)
            val scale = (localWidth / quadAvgWidth).coerceIn(0.68, 1.32)
            return estimatedBallRadius * scale
        }

        val blackEdgeMargin = if (cloth.meanSaturation < 0.45) 16.5 else (0.95 * estimatedBallRadius).coerceIn(10.0, 16.0)

        fun isRailOrPocketArtifact(center: Vec2, estimate: BallColorEstimate): Boolean {
            val localR = localExpectedRadius(center.y)
            val nearPocket = isNearPocketMouth(center, quad, isEndOn, localR)
            val nearCornerHole = quad.any { center.distanceTo(it) <= max(20.0, 2.2 * localR) }
            val nearEdge = isEndOn && !pointInQuad(center.x, center.y, quad, 0.90 * localR)
            val isClothColoredShadow = cloth.category == ClothHueCategory.BLUE &&
                estimate.dominant == BallPaletteColor.BLUE &&
                estimate.meanValue < 0.68
            if (nearCornerHole && (estimate.darkFraction >= 0.08 || estimate.meanValue < 0.65)) {
                return true
            }
            if (nearPocket && (estimate.darkFraction >= 0.14 || estimate.meanValue < 0.58 || isClothColoredShadow)) {
                return true
            }
            if (nearEdge && (estimate.darkFraction >= 0.16 || estimate.meanValue < 0.52 || isClothColoredShadow)) {
                return true
            }
            if (estimate.dominant == BallPaletteColor.BLACK &&
                (nearPocket || (isEndOn && !pointInQuad(center.x, center.y, quad, blackEdgeMargin)))
            ) {
                return true
            }
            return false
        }

        val rawBlobs = ArrayList<RawCandidateBlob>()
        for (comp in components) {
            val area = comp.area
            val radiusPx = sqrt(area / PI)
            val boxW = comp.maxX - comp.minX + 1
            val boxH = comp.maxY - comp.minY + 1
            val aspect = boxW.toDouble() / boxH
            val fill = area.toDouble() / (boxW.toDouble() * boxH)
            val localExpectedR = localExpectedRadius(comp.sumY / area)
            val localExpectedArea = PI * localExpectedR * localExpectedR

            val isPotentialCluster = area >= (1.40 * localExpectedArea) &&
                area <= (14.0 * localExpectedArea) &&
                aspect in 0.28..3.60 &&
                (area > (1.70 * localExpectedArea) || aspect < 0.62 || aspect > 1.62 || fill < 0.55)

            if (isPotentialCluster) {
                val splitCenters = splitClusterByDistanceTransform(
                    pixels = comp.pixels,
                    imageWidth = w,
                    minX = comp.minX,
                    maxX = comp.maxX,
                    minY = comp.minY,
                    maxY = comp.maxY,
                    expectedRadiusPx = localExpectedR,
                )
                if (splitCenters.size >= 2) {
                    // Touching discs fill about one disc each, so a cluster on the cushion band has
                    // little more area than its peaks; a hand or cue split into peaks has far more.
                    val inBand = !pointInQuad(comp.sumX / area, comp.sumY / area, noseQuad, 0.0)
                    if (inBand && area > BAND_MAX_AREA_PER_PEAK * splitCenters.size * localExpectedArea) continue
                    for ((center, estR) in splitCenters) {
                        val estimate = estimateBallColors(frame, center.x, center.y, estR) ?: continue
                        if (isRailOrPocketArtifact(center, estimate)) continue
                        rawBlobs.add(RawCandidateBlob(center, (PI * estR * estR).toInt(), estR, estimate))
                    }
                    continue
                }
            }

            if (radiusPx > effectiveRadiusRange.endInclusive) continue
            val minAspect = if (comp.startType == 1.toByte() && radiusPx < 4.5) 0.35 else 0.55
            val maxAspect = if (comp.startType == 1.toByte() && radiusPx < 4.5) 2.50 else 1.80
            val minFill = if (comp.startType == 1.toByte() && radiusPx < 4.5) 0.38 else 0.52
            if (aspect < minAspect || aspect > maxAspect || fill < minFill) continue

            val centerX = comp.sumX / area
            val centerY = comp.sumY / area
            val center = Vec2(centerX, centerY)
            // On the cushion band, outside the nose quad, hands, cues and cushion shadows are far
            // more likely than on the bed, so a blob there must look like a ball: a disc of about
            // the expected radius at that image position.
            if (!pointInQuad(centerX, centerY, noseQuad, 0.0) &&
                (
                    radiusPx !in BAND_MIN_RADIUS_RATIO * localExpectedR..BAND_MAX_RADIUS_RATIO * localExpectedR ||
                        aspect !in BAND_MIN_ASPECT..BAND_MAX_ASPECT ||
                        fill < BAND_MIN_FILL
                    )
            ) {
                continue
            }
            if (radiusPx < effectiveRadiusRange.start &&
                (cloth.meanSaturation >= 0.45 || !pointInQuad(centerX, centerY, quad, 5.0) || centerY > 0.50 * h)
            ) {
                continue
            }
            val sampleRadius = max(radiusPx, 3.2)
            val estimate = if (comp.startType == 1.toByte() && radiusPx < effectiveRadiusRange.start) {
                BallColorEstimate(
                    dominant = BallPaletteColor.WHITE,
                    secondary = null,
                    whiteFraction = 0.90,
                    darkFraction = 0.0,
                )
            } else {
                estimateBallColors(frame, centerX, centerY, sampleRadius) ?: continue
            }
            if (radiusPx < effectiveRadiusRange.start &&
                !(estimate.dominant == BallPaletteColor.WHITE && estimate.whiteFraction >= 0.75)
            ) {
                continue
            }
            if (isRailOrPocketArtifact(center, estimate)) {
                continue
            }
            rawBlobs.add(RawCandidateBlob(center, area, radiusPx, estimate))
        }

        // Suppress any smaller inner dot/highlight or drop-shadow blob near a larger ball blob
        return suppressOverlappingRawBlobs(rawBlobs)
            .sortedWith(compareBy<RawCandidateBlob> { it.center.y }.thenBy { it.center.x })
            .mapIndexed { idx, blob ->
                DetectedBallCandidate(
                    id = idx + 1,
                    imageCenterPx = blob.center,
                    dominantColor = blob.estimate.dominant,
                    secondaryColor = blob.estimate.secondary,
                    whiteFraction = blob.estimate.whiteFraction,
                    detectorConfidence = 0.90,
                )
            }
    }

    private class RawCandidateBlob(
        val center: Vec2,
        val area: Int,
        val radiusPx: Double,
        val estimate: BallColorEstimate,
    )

    private fun suppressOverlappingRawBlobs(
        blobs: List<RawCandidateBlob>,
        minCenterSeparation: (largerRadius: Double) -> Double = { r -> max(13.0, 1.38 * r) },
    ): List<RawCandidateBlob> {
        val sortedByArea = blobs.sortedByDescending { it.area }
        val kept = ArrayList<RawCandidateBlob>()
        for (blob in sortedByArea) {
            val overlapsLarger = kept.any { larger ->
                val dist = blob.center.distanceTo(larger.center)
                val isShadowBelow = blob.area < 0.65 * larger.area &&
                    abs(blob.center.x - larger.center.x) <= max(11.0, 0.95 * larger.radiusPx) &&
                    blob.center.y >= larger.center.y &&
                    (blob.center.y - larger.center.y) <= max(18.0, 1.65 * larger.radiusPx)
                dist < minCenterSeparation(larger.radiusPx) || isShadowBelow
            }
            if (!overlapsLarger) {
                kept.add(blob)
            }
        }
        return kept
    }

    /**
     * Re-scans a circular region around [tapCenterPx] for missed or merged balls without pocket or
     * edge suppression. If no distinct foreground ball blob is found, falls back to sampling the
     * tapped region's dominant non-cloth colour so the caller can preselect a matching ball number.
     *
     * [endOnView] is the orientation already decided for this frame (see
     * [TableQuadDetection.endOnView]); when null it is decided on [quadCornersPx].
     */
    fun rescanLocalRegion(
        frame: ArgbImageFrame,
        tapCenterPx: Vec2,
        quadCornersPx: List<Vec2>,
        existingCandidates: List<DetectedBallCandidate>,
        clothOverride: DetectedClothSurface? = null,
        searchRadiusPx: Double? = null,
        endOnView: Boolean? = null,
    ): LocalTapRescanResult {
        val w = frame.width
        val h = frame.height
        val cloth = clothOverride ?: detectClothSurface(frame)
        val localR = if (quadCornersPx.size == 4) {
            val isEndOn = endOnView ?: isEndOnPerspectiveQuad(quadCornersPx)
            val topWidth = quadCornersPx[0].distanceTo(quadCornersPx[1])
            val botWidth = quadCornersPx[3].distanceTo(quadCornersPx[2])
            val avgWidth = 0.5 * (topWidth + botWidth)
            val topY = 0.5 * (quadCornersPx[0].y + quadCornersPx[1].y)
            val botY = 0.5 * (quadCornersPx[3].y + quadCornersPx[2].y)
            val spanY = max(1.0, botY - topY)
            val baseR = (avgWidth * 0.022).coerceAtLeast(6.0)
            if (!isEndOn || avgWidth < 1e-6) {
                baseR
            } else {
                val ty = ((tapCenterPx.y - topY) / spanY).coerceIn(0.0, 1.0)
                val localWidth = topWidth + ty * (botWidth - topWidth)
                baseR * (localWidth / avgWidth).coerceIn(0.68, 1.32)
            }
        } else {
            (min(w, h) * 0.024).coerceAtLeast(7.0)
        }

        val roiRadius = searchRadiusPx ?: (localR * 2.6).coerceIn(22.0, max(56.0, localR * 2.7))
        val roiRadiusSq = roiRadius * roiRadius
        val tapClamped = Vec2(
            x = tapCenterPx.x.coerceIn(0.0, (w - 1).toDouble()),
            y = tapCenterPx.y.coerceIn(0.0, (h - 1).toDouble()),
        )
        val bx0 = floor(tapClamped.x - roiRadius).toInt().coerceIn(0, w - 1)
        val bx1 = ceil(tapClamped.x + roiRadius).toInt().coerceIn(bx0, w - 1)
        val by0 = floor(tapClamped.y - roiRadius).toInt().coerceIn(0, h - 1)
        val by1 = ceil(tapClamped.y + roiRadius).toInt().coerceIn(by0, h - 1)
        val roiW = bx1 - bx0 + 1
        val roiH = by1 - by0 + 1
        val roiTotal = roiW * roiH

        val fgType = ByteArray(roiTotal)
        val hsv = DoubleArray(3)
        val clipToQuad = quadCornersPx.size == 4 &&
            pointInQuad(tapClamped.x, tapClamped.y, quadCornersPx, 0.0)
        for (ry in 0 until roiH) {
            val py = by0 + ry
            val dy = py - tapClamped.y
            for (rx in 0 until roiW) {
                val px = bx0 + rx
                val dx = px - tapClamped.x
                if (dx * dx + dy * dy > roiRadiusSq) continue
                if (clipToQuad && !pointInQuad(px.toDouble(), py.toDouble(), quadCornersPx, 0.0)) continue
                val argb = frame.argbPixels[py * w + px]
                hsvInto(argb, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                if (isClothArgbHsv(argb, hue, sat, value, cloth) || isClothShadowHsv(hue, sat, value, cloth)) continue
                val chroma = sat * value
                val isBrightWhite = if (isBrightNeutralGreyCloth(cloth)) {
                    val rf = ((argb ushr 16) and 0xFF) / 255.0
                    val bf = (argb and 0xFF) / 255.0
                    (value >= min(0.89, cloth.meanValue + 0.07) && sat <= 0.18) ||
                        (cloth.meanSaturation >= 0.10 && value >= 0.68 && sat <= 0.24 && abs((rf - bf) - cloth.meanRedMinusBlue) > 0.055)
                } else {
                    (value >= 0.82 && sat <= 0.18) || (value >= 0.64 && sat <= 0.15)
                }
                fgType[ry * roiW + rx] = when {
                    isBrightWhite -> 1
                    value <= 0.26 || (value <= 0.36 && chroma <= 0.16) -> 2
                    hue in 36.0..78.0 && sat >= 0.26 && value >= 0.32 -> 5
                    (sat >= 0.16 && value >= 0.22 && (cloth.meanSaturation < GREY_MEDIAN_SATURATION || hueDistance(hue, cloth.dominantHueDegrees) > 25.0)) ||
                        (sat >= 0.30 && value >= 0.20) -> 3
                    else -> 4
                }
            }
        }

        val visited = BooleanArray(roiTotal)
        val roiQueue = IntArray(roiTotal)
        val globalPixelsBuf = IntArray(roiTotal)
        val localBlobs = ArrayList<RawCandidateBlob>()
        val clusterSplitCenters = HashSet<Vec2>()
        val minAllowedR = max(2.6, localR * 0.36)
        val maxAllowedR = localR * 3.2
        val expectedArea = PI * localR * localR
        val existingDedupDist = max(10.0, 1.15 * localR)

        for (start in 0 until roiTotal) {
            val startType = fgType[start]
            if (startType == 0.toByte() || visited[start]) continue
            var head = 0
            var tail = 0
            roiQueue[tail++] = start
            visited[start] = true
            var sumX = 0.0
            var sumY = 0.0
            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            while (head < tail) {
                val cur = roiQueue[head++]
                val curType = fgType[cur]
                val rx = cur % roiW
                val ry = cur / roiW
                val px = bx0 + rx
                val py = by0 + ry
                globalPixelsBuf[head - 1] = py * w + px
                sumX += px
                sumY += py
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (rx > 0) {
                    val n = cur - 1
                    val nt = fgType[n]
                    if (nt != 0.toByte() && !visited[n] && canConnectForeground(curType, nt)) {
                        visited[n] = true
                        roiQueue[tail++] = n
                    }
                }
                if (rx < roiW - 1) {
                    val n = cur + 1
                    val nt = fgType[n]
                    if (nt != 0.toByte() && !visited[n] && canConnectForeground(curType, nt)) {
                        visited[n] = true
                        roiQueue[tail++] = n
                    }
                }
                if (ry > 0) {
                    val n = cur - roiW
                    val nt = fgType[n]
                    if (nt != 0.toByte() && !visited[n] && canConnectForeground(curType, nt)) {
                        visited[n] = true
                        roiQueue[tail++] = n
                    }
                }
                if (ry < roiH - 1) {
                    val n = cur + roiW
                    val nt = fgType[n]
                    if (nt != 0.toByte() && !visited[n] && canConnectForeground(curType, nt)) {
                        visited[n] = true
                        roiQueue[tail++] = n
                    }
                }
            }

            val area = tail
            val radiusPx = sqrt(area / PI)
            if (radiusPx < minAllowedR || radiusPx > maxAllowedR * 2.0) continue
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            val aspect = boxW.toDouble() / boxH
            val fill = area.toDouble() / (boxW.toDouble() * boxH)

            if (area >= 1.35 * expectedArea && aspect in 0.28..3.60) {
                val splitCenters = splitClusterByDistanceTransform(
                    pixels = globalPixelsBuf.copyOf(area),
                    imageWidth = w,
                    minX = minX,
                    maxX = maxX,
                    minY = minY,
                    maxY = maxY,
                    expectedRadiusPx = localR,
                )
                if (splitCenters.size >= 2) {
                    for ((center, estR) in splitCenters) {
                        if (center.distanceTo(tapClamped) > roiRadius * 0.92) continue
                        if (existingCandidates.any { it.imageCenterPx.distanceTo(center) < existingDedupDist }) continue
                        val estimate = estimateBallColorsExcludingCloth(frame, center.x, center.y, estR, cloth) ?: continue
                        clusterSplitCenters += center
                        localBlobs.add(RawCandidateBlob(center, (PI * estR * estR).toInt(), estR, estimate))
                    }
                    continue
                }
            }

            if (radiusPx > maxAllowedR || aspect !in 0.40..2.40 || fill < 0.38) continue
            val center = Vec2(sumX / area, sumY / area)
            if (center.distanceTo(tapClamped) > roiRadius * 0.92) continue
            if (existingCandidates.any { it.imageCenterPx.distanceTo(center) < existingDedupDist }) continue
            val sampleRadius = max(radiusPx, 3.2)
            val estimate = if (startType == 1.toByte() && radiusPx < 3.8) {
                BallColorEstimate(
                    dominant = BallPaletteColor.WHITE,
                    secondary = null,
                    whiteFraction = 0.90,
                    darkFraction = 0.0,
                )
            } else {
                estimateBallColorsExcludingCloth(frame, center.x, center.y, sampleRadius, cloth) ?: continue
            }
            localBlobs.add(RawCandidateBlob(center, area, radiusPx, estimate))
        }

        val kept = suppressOverlappingRawBlobs(
            blobs = localBlobs,
            minCenterSeparation = { r -> max(11.0, 1.25 * r) },
        )
        val tapMatchRadius = max(18.0, localR * 1.95)
        val clusterBlobs = kept.filter { it.center in clusterSplitCenters }
        val selectedBlobs = when {
            clusterBlobs.size >= 2 && clusterBlobs.any { it.center.distanceTo(tapClamped) <= tapMatchRadius } ->
                clusterBlobs.sortedBy { it.center.distanceTo(tapClamped) }
            else ->
                kept.filter { it.center.distanceTo(tapClamped) <= tapMatchRadius }
                    .minByOrNull { it.center.distanceTo(tapClamped) }
                    ?.let(::listOf)
                    .orEmpty()
        }
        val nextIdStart = (existingCandidates.maxOfOrNull { it.id } ?: 0) + 1
        if (selectedBlobs.isNotEmpty()) {
            val found = selectedBlobs
                .mapIndexed { idx, blob ->
                    DetectedBallCandidate(
                        id = nextIdStart + idx,
                        imageCenterPx = blob.center,
                        dominantColor = blob.estimate.dominant,
                        secondaryColor = blob.estimate.secondary,
                        whiteFraction = blob.estimate.whiteFraction,
                        detectorConfidence = 0.84,
                    )
                }
            return LocalTapRescanResult(
                detectedCandidates = found,
                usedColorFallback = false,
            )
        }

        val sampledEstimate = estimateBallColorsExcludingCloth(frame, tapClamped.x, tapClamped.y, localR * 0.82, cloth)
        val fallback = DetectedBallCandidate(
            id = nextIdStart,
            imageCenterPx = tapClamped,
            dominantColor = sampledEstimate?.dominant ?: BallPaletteColor.WHITE,
            secondaryColor = sampledEstimate?.secondary,
            whiteFraction = sampledEstimate?.whiteFraction ?: 0.85,
            detectorConfidence = 0.70,
        )
        return LocalTapRescanResult(
            detectedCandidates = listOf(fallback),
            usedColorFallback = true,
        )
    }

    /**
     * Splits a touching cluster of 2+ balls into individual `(centerPx, radiusPx)` peaks using a
     * 2-pass Chamfer (10/14) distance transform and greedy non-maximum suppression.
     */
    private fun splitClusterByDistanceTransform(
        pixels: IntArray,
        imageWidth: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        expectedRadiusPx: Double,
    ): List<Pair<Vec2, Double>> {
        val bw = (maxX - minX + 1) + 2
        val bh = (maxY - minY + 1) + 2
        val size = bw * bh
        val inf = 1_000_000
        val dist = IntArray(size)
        for (idx in pixels) {
            val lx = (idx % imageWidth) - minX + 1
            val ly = (idx / imageWidth) - minY + 1
            dist[ly * bw + lx] = inf
        }
        // Forward pass
        for (ly in 1 until bh - 1) {
            val row = ly * bw
            val up = row - bw
            for (lx in 1 until bw - 1) {
                val p = row + lx
                if (dist[p] == 0) continue
                var d = dist[p]
                val dUp = dist[up + lx] + 10
                val dLeft = dist[p - 1] + 10
                val dUpLeft = dist[up + lx - 1] + 14
                val dUpRight = dist[up + lx + 1] + 14
                if (dUp < d) d = dUp
                if (dLeft < d) d = dLeft
                if (dUpLeft < d) d = dUpLeft
                if (dUpRight < d) d = dUpRight
                dist[p] = d
            }
        }
        // Backward pass
        for (ly in bh - 2 downTo 1) {
            val row = ly * bw
            val down = row + bw
            for (lx in bw - 2 downTo 1) {
                val p = row + lx
                if (dist[p] == 0) continue
                var d = dist[p]
                val dDown = dist[down + lx] + 10
                val dRight = dist[p + 1] + 10
                val dDownLeft = dist[down + lx - 1] + 14
                val dDownRight = dist[down + lx + 1] + 14
                if (dDown < d) d = dDown
                if (dRight < d) d = dRight
                if (dDownLeft < d) d = dDownLeft
                if (dDownRight < d) d = dDownRight
                dist[p] = d
            }
        }

        val minPeakDist = (0.45 * expectedRadiusPx * 10.0).toInt().coerceAtLeast(35)
        val candidates = ArrayList<Triple<Int, Int, Int>>()
        for (ly in 1 until bh - 1) {
            val row = ly * bw
            for (lx in 1 until bw - 1) {
                val d = dist[row + lx]
                if (d >= minPeakDist) {
                    candidates.add(Triple(lx, ly, d))
                }
            }
        }
        candidates.sortByDescending { it.third }

        val minSep = 1.35 * expectedRadiusPx
        val minSepSq = minSep * minSep
        val peaks = ArrayList<Pair<Vec2, Double>>()
        for ((lx, ly, d) in candidates) {
            val cx = (minX + lx - 1).toDouble()
            val cy = (minY + ly - 1).toDouble()
            val tooClose = peaks.any { (pt, _) ->
                val dx = pt.x - cx
                val dy = pt.y - cy
                dx * dx + dy * dy < minSepSq
            }
            if (!tooClose) {
                val r = (d / 10.0).coerceIn(0.65 * expectedRadiusPx, 1.25 * expectedRadiusPx)
                peaks.add(Vec2(cx, cy) to r)
                if (peaks.size >= 8) break
            }
        }
        return peaks
    }

    private class BallColorEstimate(
        val dominant: BallPaletteColor,
        val secondary: BallPaletteColor?,
        val whiteFraction: Double,
        val darkFraction: Double = 0.0,
        val meanValue: Double = 0.70,
    )

    private fun chromaticColorOf(hue: Double, sat: Double, value: Double): BallPaletteColor? = when {
        hue >= 42.0 && hue < 72.0 -> BallPaletteColor.YELLOW
        hue >= 10.0 && hue < 42.0 && (value < 0.58 || (value < 0.78 && sat < 0.70)) -> BallPaletteColor.BROWN
        (hue < 20.0 || hue >= 335.0) && value < 0.48 -> BallPaletteColor.MAROON
        (hue >= 300.0 || hue < 12.0) && value >= 0.55 && sat < 0.62 -> BallPaletteColor.PINK
        (hue < 16.0 || hue >= 340.0) && value >= 0.48 -> BallPaletteColor.RED
        hue >= 16.0 && hue < 42.0 -> BallPaletteColor.ORANGE
        hue >= 300.0 && hue < 340.0 && value >= 0.50 -> BallPaletteColor.PINK
        (hue >= 170.0 && hue < 245.5) || (hue >= 245.5 && hue < 255.0 && sat >= 0.72) -> BallPaletteColor.BLUE
        hue >= 245.5 && hue < 312.0 && (hue < 300.0 || value < 0.55) -> BallPaletteColor.PURPLE
        hue >= 72.0 && hue < 170.0 -> BallPaletteColor.GREEN
        else -> null
    }

    private fun estimateBallColorsExcludingCloth(
        frame: ArgbImageFrame,
        cx: Double,
        cy: Double,
        radiusPx: Double,
        cloth: DetectedClothSurface,
    ): BallColorEstimate? {
        val filtered = estimateBallColorsCore(
            frame = frame,
            cx = cx,
            cy = cy,
            radiusPx = radiusPx,
            excludeCloth = cloth,
            minSamplesRequired = 8,
            minSampleFraction = 0.06,
        )
        return filtered ?: estimateBallColorsCore(
            frame = frame,
            cx = cx,
            cy = cy,
            radiusPx = radiusPx,
            excludeCloth = null,
        )
    }

    private fun estimateBallColors(
        frame: ArgbImageFrame,
        cx: Double,
        cy: Double,
        radiusPx: Double,
    ): BallColorEstimate? = estimateBallColorsCore(
        frame = frame,
        cx = cx,
        cy = cy,
        radiusPx = radiusPx,
        excludeCloth = null,
    )

    private fun estimateBallColorsCore(
        frame: ArgbImageFrame,
        cx: Double,
        cy: Double,
        radiusPx: Double,
        excludeCloth: DetectedClothSurface?,
        minSamplesRequired: Int = 1,
        minSampleFraction: Double = 0.0,
    ): BallColorEstimate? {
        val w = frame.width
        val h = frame.height
        val sampleRadius = 0.85 * radiusPx
        val sampleRadiusSq = sampleRadius * sampleRadius
        val x0 = floor(cx - sampleRadius).toInt().coerceIn(0, w - 1)
        val x1 = ceil(cx + sampleRadius).toInt().coerceIn(x0, w - 1)
        val y0 = floor(cy - sampleRadius).toInt().coerceIn(0, h - 1)
        val y1 = ceil(cy + sampleRadius).toInt().coerceIn(y0, h - 1)

        val votes = IntArray(PALETTE.size)
        val hsv = DoubleArray(3)
        var visitedDiskPixels = 0
        var totalPixels = 0
        var whitePixels = 0
        var warmIvoryPixels = 0
        var strongChromaticPixels = 0
        var blackPixels = 0
        var valueSum = 0.0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy > sampleRadiusSq) continue
                visitedDiskPixels++
                val argb = frame.argbPixels[y * w + x]
                hsvInto(argb, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                if (excludeCloth != null && isClothArgbHsv(argb, hue, sat, value, excludeCloth)) continue
                totalPixels++
                valueSum += value
                if (value >= 0.75 && sat <= 0.22) {
                    whitePixels++
                } else if (
                    (value >= 0.72 && sat <= 0.42 && hue in 18.0..54.0) ||
                    (value >= 0.78 && sat <= 0.34 && hue in 54.0..95.0)
                ) {
                    warmIvoryPixels++
                } else if (value <= 0.24 || (value <= 0.35 && sat * value <= 0.16)) {
                    blackPixels++
                } else if (sat > 0.20) {
                    if (sat >= 0.48) strongChromaticPixels++
                    val color = chromaticColorOf(hue, sat, value)
                    if (color != null) votes[color.ordinal]++
                }
            }
        }
        if (totalPixels < minSamplesRequired) return null
        if (excludeCloth != null && visitedDiskPixels > 0 && totalPixels.toDouble() / visitedDiskPixels < minSampleFraction) {
            return null
        }
        val meanVal = valueSum / totalPixels

        var topIdx = -1
        var top = 0
        var secondIdx = -1
        var second = 0
        for (i in votes.indices) {
            val c = votes[i]
            if (c > top) {
                second = top
                secondIdx = topIdx
                top = c
                topIdx = i
            } else if (c > second) {
                second = c
                secondIdx = i
            }
        }
        val chromaticTotal = votes.sum()
        val combinedWhiteRatio = (whitePixels + warmIvoryPixels).toDouble() / totalPixels
        val whiteRatio = whitePixels.toDouble() / totalPixels
        val blackRatio = blackPixels.toDouble() / totalPixels
        val strongChromaticRatio = strongChromaticPixels.toDouble() / totalPixels
        val chromaticRatio = chromaticTotal.toDouble() / totalPixels

        val yellowVotes = votes[BallPaletteColor.YELLOW.ordinal]
        val isBroadcastNineBallStripe = yellowVotes >= 3 && blackRatio >= 0.08 && combinedWhiteRatio < 0.65
        val isStripedWithChromatic = excludeCloth != null && chromaticRatio >= 0.18 && strongChromaticRatio >= 0.06 && whiteRatio in 0.18..0.84

        return when {
            !isStripedWithChromatic &&
                (
                    whiteRatio >= 0.75 ||
                        (combinedWhiteRatio >= 0.68 && strongChromaticRatio < 0.15) ||
                        (combinedWhiteRatio >= 0.45 && strongChromaticRatio < 0.06 && meanVal >= 0.68 && blackRatio < 0.25)
                    ) -> BallColorEstimate(
                dominant = BallPaletteColor.WHITE,
                secondary = secondaryFromTop(topIdx, top, totalPixels),
                whiteFraction = max(whiteRatio, combinedWhiteRatio).coerceIn(0.82, 0.99),
                darkFraction = blackRatio,
                meanValue = meanVal,
            )
            isBroadcastNineBallStripe -> BallColorEstimate(
                dominant = BallPaletteColor.YELLOW,
                secondary = BallPaletteColor.BLACK,
                whiteFraction = max(combinedWhiteRatio, 0.56),
                darkFraction = blackRatio,
                meanValue = meanVal,
            )
            blackRatio >= 0.45 -> BallColorEstimate(
                dominant = BallPaletteColor.BLACK,
                secondary = secondaryFromTop(topIdx, top, totalPixels),
                whiteFraction = whiteRatio.coerceIn(0.05, 0.20),
                darkFraction = blackRatio,
                meanValue = meanVal,
            )
            else -> {
                val dominant = when {
                    topIdx >= 0 -> PALETTE[topIdx]
                    combinedWhiteRatio >= blackRatio -> BallPaletteColor.WHITE
                    else -> BallPaletteColor.BLACK
                }
                val secondary = if (secondIdx >= 0 && second >= max(1.0, 0.10 * chromaticTotal)) {
                    PALETTE[secondIdx]
                } else {
                    null
                }
                val effectiveWhiteFraction = when {
                    isStripedWithChromatic -> combinedWhiteRatio.coerceIn(0.42, 0.72)
                    dominant == BallPaletteColor.YELLOW &&
                        (blackRatio >= 0.08 || (combinedWhiteRatio + blackRatio) >= 0.25) ->
                        max(combinedWhiteRatio, (0.50 + blackRatio * 0.50).coerceIn(0.52, 0.68))
                    else -> combinedWhiteRatio
                }
                BallColorEstimate(
                    dominant = dominant,
                    secondary = secondary,
                    whiteFraction = effectiveWhiteFraction.coerceIn(0.05, 0.75),
                    darkFraction = blackRatio,
                    meanValue = meanVal,
                )
            }
        }
    }

    private fun secondaryFromTop(topIdx: Int, top: Int, totalPixels: Int): BallPaletteColor? =
        if (topIdx >= 0 && top >= 0.10 * totalPixels) PALETTE[topIdx] else null

    // ---------------------------------------------------------------------------------------
    // Synthetic rendering
    // ---------------------------------------------------------------------------------------

    private fun clothBaseArgb(category: ClothHueCategory): Int = when (category) {
        ClothHueCategory.BLUE -> 0xFF1E78B4L.toInt()
        ClothHueCategory.GREEN -> 0xFF1E824CL.toInt()
        ClothHueCategory.RED -> 0xFFA82828L.toInt()
        ClothHueCategory.GREY -> 0xFF5A626AL.toInt()
    }

    /** Flat ball body colour for each palette entry, chosen to be separable from all cloth hues. */
    private fun paletteArgb(color: BallPaletteColor): Int = when (color) {
        BallPaletteColor.WHITE -> WHITE_BALL_ARGB.toInt()
        BallPaletteColor.YELLOW -> 0xFFF0DC1EL.toInt()
        BallPaletteColor.BLUE -> 0xFF2319AAL.toInt()
        BallPaletteColor.RED -> 0xFFD22828L.toInt()
        BallPaletteColor.PURPLE -> 0xFF7828AAL.toInt()
        BallPaletteColor.ORANGE -> 0xFFF2961EL.toInt()
        BallPaletteColor.GREEN -> 0xFF50C828L.toInt()
        BallPaletteColor.MAROON -> 0xFF64121CL.toInt()
        BallPaletteColor.BLACK -> BLACK_BALL_ARGB.toInt()
        BallPaletteColor.PINK -> 0xFFF06EB4L.toInt()
        BallPaletteColor.BROWN -> 0xFF825A1EL.toInt()
    }

    private fun shade(base: Int, delta: Int): Int {
        val r = (((base ushr 16) and 0xFF) + delta).coerceIn(0, 255)
        val g = (((base ushr 8) and 0xFF) + delta).coerceIn(0, 255)
        val b = ((base and 0xFF) + delta).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Deterministic pseudo-random integer in `-amp..amp` for pixel `(x, y)`. */
    private fun textureNoise(x: Int, y: Int, amp: Int): Int {
        var hash = x * 374761393 + y * 668265263
        hash = (hash xor (hash ushr 13)) * 1274126177
        hash = hash xor (hash ushr 16)
        return ((hash and 0x7FFFFFFF) % (2 * amp + 1)) - amp
    }

    /**
     * A flat object lying on the table plane in a synthetic render: a rectangle of
     * [lengthMeters] x [widthMeters] centred at [centerMeters] with its long axis at [angleRad] to
     * the table's `x` axis, filled with [argb]. Table-plane coordinates may lie beyond the nose, so
     * it can lie across a cushion like a hand or a cue.
     */
    class SyntheticTableObject(
        val centerMeters: Vec2,
        val lengthMeters: Double,
        val widthMeters: Double,
        val angleRad: Double,
        val argb: Int,
    )

    private fun drawTableObject(
        pixels: IntArray,
        width: Int,
        height: Int,
        homography: PerspectiveHomography,
        item: SyntheticTableObject,
    ) {
        val along = Vec2(cos(item.angleRad), sin(item.angleRad))
        val across = Vec2(-along.y, along.x)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = homography.imageToTableCloth(Vec2(x.toDouble(), y.toDouble()))
                if (!p.x.isFinite() || !p.y.isFinite()) continue
                val d = p - item.centerMeters
                if (abs(d.dot(along)) <= 0.5 * item.lengthMeters && abs(d.dot(across)) <= 0.5 * item.widthMeters) {
                    pixels[y * width + x] = item.argb
                }
            }
        }
    }

    /** How [renderSyntheticTablePhotoArgb] draws the cushions. */
    enum class SyntheticCushionStyle {
        /** No cushions: the cloth meets the rail at the playing-surface edge. */
        NONE,

        /**
         * Cloth-covered cushions that still pass the cloth colour test: a lighter top and a darker
         * strip along the nose, as cushions look under overhead lights.
         */
        SHADED,

        /** Cloth-covered cushions indistinguishable from the bed. */
        CLOTH_MATCHED,
    }

    /**
     * Renders a clean synthetic perspective photo of [layout]: dark rail outside [quadCornersPx]
     * (`[headLeftPx, footLeftPx, footRightPx, headRightPx]`), textured cloth inside, and every
     * ball projected through [PerspectiveHomography.fromTableQuad] with ball-height correction for
     * [cameraPoseMeters]. Cue ball is white, the 8-ball black with a small number dot, solids a
     * full colour disk with a small white dot, stripes white caps around a coloured band.
     *
     * [quadCornersPx] is always the playing surface at the cushion noses. With [cushions] other
     * than [SyntheticCushionStyle.NONE], cloth-covered cushions [cushionWidthMeters] wide on the
     * sides facing the camera (a face strip narrower on the side the camera stands behind) are drawn
     * around it on the table plane and six pocket holes cut them, so the cloth region reaches the
     * cushion backs as it does in photos. [cushionContrast] scales how far the cushion top and face
     * differ from the bed (`1` is the default look, smaller is flatter).
     */
    fun renderSyntheticTablePhotoArgb(
        layout: TableLayout,
        quadCornersPx: List<Vec2>,
        cameraPoseMeters: Vec3,
        width: Int = 640,
        height: Int = 360,
        clothCategory: ClothHueCategory = ClothHueCategory.BLUE,
        ballSet: BallSetProfile = BallSetProfile.standardPool(),
        ballRadiusPx: Int = 11,
        cushions: SyntheticCushionStyle = SyntheticCushionStyle.NONE,
        cushionWidthMeters: Double = CUSHION_VISIBLE_BAND_METERS,
        cushionContrast: Double = 1.0,
        objects: List<SyntheticTableObject> = emptyList(),
    ): ArgbImageFrame {
        require(quadCornersPx.size == 4) { "Exactly four table corners are required." }
        val pixels = IntArray(width * height)
        val railBase = RAIL_ARGB.toInt()
        val clothBase = clothBaseArgb(clothCategory)
        val homography = PerspectiveHomography.fromTableQuad(quadCornersPx, layout.table)

        if (cushions == SyntheticCushionStyle.NONE) {
            val minX = floor(quadCornersPx.minOf { it.x }).toInt().coerceIn(0, width - 1)
            val maxX = ceil(quadCornersPx.maxOf { it.x }).toInt().coerceIn(0, width - 1)
            val minY = floor(quadCornersPx.minOf { it.y }).toInt().coerceIn(0, height - 1)
            val maxY = ceil(quadCornersPx.maxOf { it.y }).toInt().coerceIn(0, height - 1)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val inside = x in minX..maxX && y in minY..maxY &&
                        pointInQuad(x.toDouble(), y.toDouble(), quadCornersPx)
                    pixels[y * width + x] = if (inside) {
                        val gradient = (x * 6) / width - 3
                        shade(clothBase, textureNoise(x, y, 4) + gradient)
                    } else {
                        shade(railBase, textureNoise(x, y, 2))
                    }
                }
            }
        } else {
            drawCushionedTable(
                pixels, width, height, layout.table, homography, cameraPoseMeters, clothBase, railBase,
                cushions, cushionWidthMeters, cushionContrast,
            )
        }

        for (item in objects) drawTableObject(pixels, width, height, homography, item)

        for (ball in layout.balls) {
            val center = homography.ballCenterMetersToImage(ball.position, ball.radiusMeters, cameraPoseMeters)
            drawBall(pixels, width, height, center, ballRadiusPx.toDouble(), ball.number, ballSet.colorForBall(ball.number))
        }
        return ArgbImageFrame(width, height, pixels)
    }

    /**
     * Draws the bed, cloth-covered cushions, rail and pocket holes on the table plane.
     *
     * A cushion facing the camera shows its sloping nose face (a darker strip, [CUSHION_FACE_STRIP_METERS]
     * wide, next to the bed) in front of its top, [cushionWidthMeters] in all; the cushion the camera
     * stands behind shows only its top, the face strip narrower. [contrast] scales how far the
     * top and the face differ from the bed.
     */
    private fun drawCushionedTable(
        pixels: IntArray,
        width: Int,
        height: Int,
        table: TableSpec,
        bedHomography: PerspectiveHomography,
        cameraPoseMeters: Vec3,
        clothBase: Int,
        railBase: Int,
        style: SyntheticCushionStyle,
        cushionWidthMeters: Double,
        contrast: Double,
    ) {
        val l = table.lengthMeters
        val w = table.widthMeters
        val faceStrip = CUSHION_FACE_STRIP_METERS
        val topShade = (24.0 * contrast).roundToInt()
        val faceShade = (-18.0 * contrast).roundToInt()
        // Band width per rail, from what the camera can see of it: head, foot, left, right.
        fun band(cameraBeyondRail: Boolean) = if (cameraBeyondRail) cushionWidthMeters - faceStrip else cushionWidthMeters
        val headFace = cameraPoseMeters.x >= 0.0
        val footFace = cameraPoseMeters.x <= l
        val leftFace = cameraPoseMeters.y >= 0.0
        val rightFace = cameraPoseMeters.y <= w
        val headBand = band(!headFace)
        val footBand = band(!footFace)
        val leftBand = band(!leftFace)
        val rightBand = band(!rightFace)
        val corner = 0.45 * cushionWidthMeters
        val pockets = listOf(
            Vec2(-corner, -corner) to 0.062,
            Vec2(l + corner, -corner) to 0.062,
            Vec2(l + corner, w + corner) to 0.062,
            Vec2(-corner, w + corner) to 0.062,
            Vec2(0.5 * l, -0.6 * cushionWidthMeters) to 0.056,
            Vec2(0.5 * l, w + 0.6 * cushionWidthMeters) to 0.056,
        )
        val pocketArgb = POCKET_ARGB.toInt()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = bedHomography.imageToTableCloth(Vec2(x.toDouble(), y.toDouble()))
                val gradient = (x * 6) / width - 3
                if (!p.x.isFinite() || !p.y.isFinite()) {
                    pixels[y * width + x] = shade(railBase, textureNoise(x, y, 2))
                    continue
                }
                if (p.x in 0.0..l && p.y in 0.0..w) {
                    pixels[y * width + x] = shade(clothBase, textureNoise(x, y, 4) + gradient)
                    continue
                }
                // Distance beyond each rail's nose, as a fraction of that rail's band.
                val beyondHead = -p.x / headBand
                val beyondFoot = (p.x - l) / footBand
                val beyondLeft = -p.y / leftBand
                val beyondRight = (p.y - w) / rightBand
                val reach = max(max(beyondHead, beyondFoot), max(beyondLeft, beyondRight))
                val inFace = (beyondHead > 0.0 && -p.x <= faceStrip && headFace) ||
                    (beyondFoot > 0.0 && p.x - l <= faceStrip && footFace) ||
                    (beyondLeft > 0.0 && -p.y <= faceStrip && leftFace) ||
                    (beyondRight > 0.0 && p.y - w <= faceStrip && rightFace)
                pixels[y * width + x] = when {
                    reach > 1.0 -> shade(railBase, textureNoise(x, y, 2))
                    pockets.any { (c, r) -> p.distanceTo(c) <= r } -> shade(pocketArgb, textureNoise(x, y, 2))
                    style == SyntheticCushionStyle.CLOTH_MATCHED -> shade(clothBase, textureNoise(x, y, 4) + gradient)
                    inFace -> shade(clothBase, textureNoise(x, y, 3) + gradient + faceShade)
                    else -> shade(clothBase, textureNoise(x, y, 3) + gradient + topShade)
                }
            }
        }
    }

    private fun drawBall(
        pixels: IntArray,
        width: Int,
        height: Int,
        center: Vec2,
        radius: Double,
        number: Int,
        color: BallPaletteColor,
    ) {
        val radiusSq = radius * radius
        val dotRadiusSq = 0.12 * radiusSq
        val body = paletteArgb(color)
        val white = WHITE_BALL_ARGB.toInt()
        val x0 = floor(center.x - radius).toInt().coerceAtLeast(0)
        val x1 = ceil(center.x + radius).toInt().coerceAtMost(width - 1)
        val y0 = floor(center.y - radius).toInt().coerceAtLeast(0)
        val y1 = ceil(center.y + radius).toInt().coerceAtMost(height - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val dx = x - center.x
                val dy = y - center.y
                val d2 = dx * dx + dy * dy
                if (d2 > radiusSq) continue
                pixels[y * width + x] = when {
                    number == 0 -> white
                    number in 9..15 -> if (abs(dy) <= 0.42 * radius) body else white
                    else -> if (d2 <= dotRadiusSq) white else body
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Video tracking
    // ---------------------------------------------------------------------------------------

    /**
     * Tracks one ball through timestamped raw frames.
     *
     * Each frame is analysed, the candidate whose dominant colour is [targetBallColor] (nearest to
     * the previous position when several match, or any nearest candidate when none matches) is
     * projected to table metres with ball-height correction, and the observations are smoothed and
     * annotated by [VideoBallTracker.trackBallTrajectory]. Frames without any candidate are skipped.
     *
     * [quadCornersPx] is the fixed table quad (`[headLeftPx, footLeftPx, footRightPx, headRightPx]`).
     */
    fun trackBallInArgbVideoFrames(
        frames: List<Pair<Double, ArgbImageFrame>>,
        quadCornersPx: List<Vec2>,
        table: TableSpec,
        cameraPoseMeters: Vec3 = Vec3(table.lengthMeters * 0.5, table.widthMeters * 0.5, 2.20),
        targetBallColor: BallPaletteColor = BallPaletteColor.WHITE,
    ): TrackedBallTrajectory {
        val homography = PerspectiveHomography.fromTableQuad(quadCornersPx, table)
        val observations = ArrayList<VideoFrameBallObservation>(frames.size)
        var previous: Vec2? = null
        for ((index, entry) in frames.withIndex()) {
            val timestampSeconds = entry.first
            val summary = analyzeArgbFrame(
                frame = entry.second,
                quadOverridePx = quadCornersPx,
                quadInsetPx = 0.0,
                table = table,
            )
            val candidates = summary.detectedCandidates
            if (candidates.isEmpty()) continue
            val matching = candidates.filter { it.dominantColor == targetBallColor }
            val pool = if (matching.isNotEmpty()) matching else candidates
            val prev = previous
            val chosen = if (prev == null) {
                pool.first()
            } else {
                pool.minByOrNull { it.imageCenterPx.distanceSquaredTo(prev) } ?: pool.first()
            }
            previous = chosen.imageCenterPx
            val positionMeters = homography.imageToBallCenterMeters(
                chosen.imageCenterPx,
                table.ballRadiusMeters,
                cameraPoseMeters,
            )
            observations.add(
                VideoFrameBallObservation(
                    frameIndex = index,
                    timestampSeconds = timestampSeconds,
                    rawPositionMeters = positionMeters,
                    frameMeanLuma = summary.meanLuma,
                ),
            )
        }
        return VideoBallTracker.trackBallTrajectory(observations, table)
    }
}
