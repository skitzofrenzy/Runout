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
    TOURNAMENT_BLUE,
    SIMONIS_GREEN,
    CHAMPIONSHIP_RED,
    SLATE_GREY,
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

/** Everything extracted from one raw frame: cloth, table quad, ball candidates and quality metrics. */
data class RawFrameDetectionSummary(
    val width: Int,
    val height: Int,
    val clothSurface: DetectedClothSurface,
    val detectedQuadCornersPx: List<Vec2>,
    val detectedCandidates: List<DetectedBallCandidate>,
    val sharpnessScore: Double,
    val glareFraction: Double,
    val meanLuma: Double,
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
        hue in 175.0..255.0 -> ClothHueCategory.TOURNAMENT_BLUE
        hue >= 75.0 && hue < 175.0 -> ClothHueCategory.SIMONIS_GREEN
        hue <= 35.0 || hue >= 325.0 -> ClothHueCategory.CHAMPIONSHIP_RED
        else -> ClothHueCategory.SLATE_GREY
    }

    private fun isBrightNeutralGreyCloth(cloth: DetectedClothSurface): Boolean =
        cloth.meanSaturation < 0.12 && cloth.meanValue >= 0.55

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
            val whiteCeiling = min(0.895, max(0.64, cloth.meanValue + 0.075))
            if (value >= whiteCeiling && sat <= 0.18) return false
            if (value < cloth.meanValue - 0.17 || value >= whiteCeiling) return false
            val maxSat = min(GREY_PIXEL_MAX_SATURATION, cloth.meanSaturation + 0.042)
            if (sat > maxSat) return false
            val rf = ((argb ushr 16) and 0xFF) / 255.0
            val gf = ((argb ushr 8) and 0xFF) / 255.0
            val bf = (argb and 0xFF) / 255.0
            return abs((rf - bf) - cloth.meanRedMinusBlue) <= 0.038 &&
                abs((rf - gf) - cloth.meanRedMinusGreen) <= 0.038
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
            if (cloth.category == ClothHueCategory.CHAMPIONSHIP_RED) return false
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
     * histogram. Cloth whose median saturation is below `0.22` is reported as [ClothHueCategory.SLATE_GREY].
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
            return DetectedClothSurface(ClothHueCategory.SLATE_GREY, 0.0, 0.0, 0.4, 0.0)
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
                category = ClothHueCategory.SLATE_GREY,
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
     * Finds the four table corners `[headLeftPx, footLeftPx, footRightPx, headRightPx]` as the
     * extreme points of the cloth region along the diagonals: top-left `min(x + y)`, top-right
     * `max(x - y)`, bottom-right `max(x + y)` and bottom-left `min(x - y)`.
     *
     * Returns a default inset quad when fewer than 50 cloth pixels are found.
     */
    fun detectTableQuadCornersPx(frame: ArgbImageFrame, cloth: DetectedClothSurface): List<Vec2> {
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

        if (count < MIN_CLOTH_PIXELS_FOR_QUAD) {
            return listOf(
                Vec2(0.12 * w, 0.18 * h),
                Vec2(0.88 * w, 0.18 * h),
                Vec2(0.88 * w, 0.85 * h),
                Vec2(0.12 * w, 0.85 * h),
            )
        }

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
     */
    fun orientQuadForHomography(
        quadCornersPx: List<Vec2>,
        table: TableSpec = TableSpec.nineFootPool(),
    ): Pair<List<Vec2>, Vec3> {
        if (!isEndOnPerspectiveQuad(quadCornersPx)) {
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
     * Balls are the connected non-cloth blobs inside the table quad (inset by [quadInsetPx] so
     * rails and pockets are excluded) whose equivalent radius lies in [expectedBallRadiusPxRange].
     * When [quadOverridePx] is given it is used instead of the detected quad.
     */
    fun analyzeArgbFrame(
        frame: ArgbImageFrame,
        expectedBallRadiusPxRange: ClosedFloatingPointRange<Double> = 3.8..45.0,
        quadOverridePx: List<Vec2>? = null,
        quadInsetPx: Double = BLOB_INSET_PX,
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
        val quad = quadOverridePx ?: detectTableQuadCornersPx(frame, cloth)

        val candidates = findBallCandidates(frame, cloth, quad, expectedBallRadiusPxRange, quadInsetPx)
        return RawFrameDetectionSummary(
            width = w,
            height = h,
            clothSurface = cloth,
            detectedQuadCornersPx = quad,
            detectedCandidates = candidates,
            sharpnessScore = sharpness,
            glareFraction = glareFraction,
            meanLuma = meanLuma,
        )
    }

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
        radiusRange: ClosedFloatingPointRange<Double>,
        insetPx: Double,
    ): List<DetectedBallCandidate> {
        val w = frame.width
        val h = frame.height
        val total = w * h
        val hsv = DoubleArray(3)
        val isEndOn = isEndOnPerspectiveQuad(quad)
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
                    value >= min(0.89, cloth.meanValue + 0.07) && sat <= 0.18
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

        class RawBlob(
            val center: Vec2,
            val area: Int,
            val radiusPx: Double,
            val estimate: BallColorEstimate,
        )

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
            val minAllowedRadius = if (startType == 1.toByte()) min(2.0, radiusRange.start) else radiusRange.start
            if (radiusPx < minAllowedRadius || radiusPx > radiusRange.endInclusive * 2.5) continue
            val pixelsCopy = queue.copyOf(area)
            components.add(ForegroundComponent(pixelsCopy, startType, minX, maxX, minY, maxY, sumX, sumY))

            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            val aspect = boxW.toDouble() / boxH
            val fill = area.toDouble() / (boxW.toDouble() * boxH)
            if (radiusPx in radiusRange && aspect in 0.65..1.55 && fill >= 0.56) {
                singleRadii.add(radiusPx)
            }
        }

        val quadTopWidth = quad[0].distanceTo(quad[1])
        val quadBotWidth = quad[3].distanceTo(quad[2])
        val quadAvgWidth = 0.5 * (quadTopWidth + quadBotWidth)
        val quadTopY = 0.5 * (quad[0].y + quad[1].y)
        val quadBotY = 0.5 * (quad[3].y + quad[2].y)
        val quadHeightY = max(1.0, quadBotY - quadTopY)
        val estimatedBallRadius = if (singleRadii.size >= 2) {
            singleRadii.sort()
            singleRadii[singleRadii.size / 2]
        } else {
            (quadAvgWidth * 0.022).coerceIn(6.0, 22.0)
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
            val isClothColoredShadow = cloth.category == ClothHueCategory.TOURNAMENT_BLUE &&
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

        val rawBlobs = ArrayList<RawBlob>()
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
                    for ((center, estR) in splitCenters) {
                        val estimate = estimateBallColors(frame, center.x, center.y, estR) ?: continue
                        if (isRailOrPocketArtifact(center, estimate)) continue
                        rawBlobs.add(RawBlob(center, (PI * estR * estR).toInt(), estR, estimate))
                    }
                    continue
                }
            }

            if (radiusPx > radiusRange.endInclusive) continue
            val minAspect = if (comp.startType == 1.toByte() && radiusPx < 4.5) 0.35 else 0.55
            val maxAspect = if (comp.startType == 1.toByte() && radiusPx < 4.5) 2.50 else 1.80
            val minFill = if (comp.startType == 1.toByte() && radiusPx < 4.5) 0.38 else 0.52
            if (aspect < minAspect || aspect > maxAspect || fill < minFill) continue

            val centerX = comp.sumX / area
            val centerY = comp.sumY / area
            val center = Vec2(centerX, centerY)
            if (radiusPx < radiusRange.start &&
                (cloth.meanSaturation >= 0.45 || !pointInQuad(centerX, centerY, quad, 5.0) || centerY > 0.50 * h)
            ) {
                continue
            }
            val sampleRadius = max(radiusPx, 3.2)
            val estimate = if (comp.startType == 1.toByte() && radiusPx < radiusRange.start) {
                BallColorEstimate(
                    dominant = BallPaletteColor.WHITE,
                    secondary = null,
                    whiteFraction = 0.90,
                    darkFraction = 0.0,
                )
            } else {
                estimateBallColors(frame, centerX, centerY, sampleRadius) ?: continue
            }
            if (radiusPx < radiusRange.start &&
                !(estimate.dominant == BallPaletteColor.WHITE && estimate.whiteFraction >= 0.75)
            ) {
                continue
            }
            if (isRailOrPocketArtifact(center, estimate)) {
                continue
            }
            rawBlobs.add(RawBlob(center, area, radiusPx, estimate))
        }

        // Suppress any smaller inner dot/highlight or drop-shadow blob near a larger ball blob
        val sortedByArea = rawBlobs.sortedByDescending { it.area }
        val kept = ArrayList<RawBlob>()
        for (blob in sortedByArea) {
            val overlapsLarger = kept.any { larger ->
                val dist = blob.center.distanceTo(larger.center)
                val isShadowBelow = blob.area < 0.65 * larger.area &&
                    abs(blob.center.x - larger.center.x) <= max(11.0, 0.95 * larger.radiusPx) &&
                    blob.center.y >= larger.center.y &&
                    (blob.center.y - larger.center.y) <= max(18.0, 1.65 * larger.radiusPx)
                dist < max(13.0, 1.38 * larger.radiusPx) || isShadowBelow
            }
            if (!overlapsLarger) {
                kept.add(blob)
            }
        }
        return kept
            .sortedWith(compareBy<RawBlob> { it.center.y }.thenBy { it.center.x })
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

    private fun estimateBallColors(
        frame: ArgbImageFrame,
        cx: Double,
        cy: Double,
        radiusPx: Double,
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
                totalPixels++
                hsvInto(frame.argbPixels[y * w + x], hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                valueSum += value
                if (value >= 0.75 && sat <= 0.22) {
                    whitePixels++
                } else if (value >= 0.72 && sat <= 0.42 && hue in 18.0..54.0) {
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
        if (totalPixels == 0) return null
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

        val yellowVotes = votes[BallPaletteColor.YELLOW.ordinal]
        val isWntNineBallStripe = yellowVotes >= 3 && blackRatio >= 0.08 && combinedWhiteRatio < 0.65

        return when {
            whiteRatio >= 0.75 || (combinedWhiteRatio >= 0.68 && strongChromaticRatio < 0.15) -> BallColorEstimate(
                dominant = BallPaletteColor.WHITE,
                secondary = secondaryFromTop(topIdx, top, totalPixels),
                whiteFraction = max(whiteRatio, combinedWhiteRatio).coerceIn(0.82, 0.99),
                darkFraction = blackRatio,
                meanValue = meanVal,
            )
            isWntNineBallStripe -> BallColorEstimate(
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
                val effectiveWhiteFraction = if (
                    dominant == BallPaletteColor.YELLOW &&
                    (blackRatio >= 0.08 || (combinedWhiteRatio + blackRatio) >= 0.25)
                ) {
                    max(combinedWhiteRatio, (0.50 + blackRatio * 0.50).coerceIn(0.52, 0.68))
                } else {
                    combinedWhiteRatio
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
        ClothHueCategory.TOURNAMENT_BLUE -> 0xFF1E78B4L.toInt()
        ClothHueCategory.SIMONIS_GREEN -> 0xFF1E824CL.toInt()
        ClothHueCategory.CHAMPIONSHIP_RED -> 0xFFA82828L.toInt()
        ClothHueCategory.SLATE_GREY -> 0xFF5A626AL.toInt()
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
     * Renders a clean synthetic perspective photo of [layout]: dark rail outside [quadCornersPx]
     * (`[headLeftPx, footLeftPx, footRightPx, headRightPx]`), textured cloth inside, and every
     * ball projected through [PerspectiveHomography.fromTableQuad] with ball-height correction for
     * [cameraPoseMeters]. Cue ball is white, the 8-ball black with a small number dot, solids a
     * full colour disk with a small white dot, stripes white caps around a coloured band.
     */
    fun renderSyntheticTablePhotoArgb(
        layout: TableLayout,
        quadCornersPx: List<Vec2>,
        cameraPoseMeters: Vec3,
        width: Int = 640,
        height: Int = 360,
        clothCategory: ClothHueCategory = ClothHueCategory.TOURNAMENT_BLUE,
        ballSet: BallSetProfile = BallSetProfile.standardPool(),
        ballRadiusPx: Int = 11,
    ): ArgbImageFrame {
        require(quadCornersPx.size == 4) { "Exactly four table corners are required." }
        val pixels = IntArray(width * height)
        val railBase = RAIL_ARGB.toInt()
        val clothBase = clothBaseArgb(clothCategory)
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

        val homography = PerspectiveHomography.fromTableQuad(quadCornersPx, layout.table)
        for (ball in layout.balls) {
            val center = homography.ballCenterMetersToImage(ball.position, ball.radiusMeters, cameraPoseMeters)
            drawBall(pixels, width, height, center, ballRadiusPx.toDouble(), ball.number, ballSet.colorForBall(ball.number))
        }
        return ArgbImageFrame(width, height, pixels)
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
