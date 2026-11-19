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
    private const val CLOTH_MAX_VALUE = 0.90

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

    /** True when [argb] matches the measured [cloth] colour (cloth mask used by all detectors). */
    fun isClothPixel(argb: Int, cloth: DetectedClothSurface): Boolean {
        val scratch = DoubleArray(3)
        hsvInto(argb, scratch)
        return isClothHsv(scratch[0], scratch[1], scratch[2], cloth)
    }

    private fun isClothHsv(hue: Double, sat: Double, value: Double, cloth: DetectedClothSurface): Boolean {
        if (value < CLOTH_MIN_VALUE || value > CLOTH_MAX_VALUE) return false
        if ((value >= 0.84 && sat <= 0.18) || (value >= 0.64 && sat <= 0.15)) return false
        return if (cloth.meanSaturation < GREY_MEDIAN_SATURATION) {
            sat <= GREY_PIXEL_MAX_SATURATION &&
                abs(sat - cloth.meanSaturation) <= 0.16 &&
                abs(value - cloth.meanValue) <= GREY_VALUE_TOLERANCE
        } else {
            hueDistance(hue, cloth.dominantHueDegrees) <= CLOTH_HUE_TOLERANCE_DEGREES &&
                abs(sat - cloth.meanSaturation) <= 0.14 &&
                abs(value - cloth.meanValue) <= 0.30
        }
    }

    private fun isClothShadowHsv(
        hue: Double,
        sat: Double,
        value: Double,
        cloth: DetectedClothSurface,
    ): Boolean {
        if (cloth.meanSaturation > 0.40) return false
        val upperVal = (cloth.meanValue - 0.09).coerceAtLeast(0.55)
        if (value < 0.36 || value > upperVal) return false
        if (value >= 0.60 && sat <= 0.15) return false
        val chroma = sat * value
        if (sat > 0.34 && chroma > 0.20) return false
        return sat <= 0.25 || hueDistance(hue, cloth.dominantHueDegrees) <= 32.0
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
        var n = 0
        val hsv = DoubleArray(3)
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                hsvInto(frame.argbPixels[y * w + x], hsv)
                val isBright = hsv[2] > 0.84 && hsv[1] < 0.17
                val isDark = hsv[2] < 0.15
                if (!isBright && !isDark && n < capacity) {
                    hues[n] = hsv[0]
                    sats[n] = hsv[1]
                    vals[n] = hsv[2]
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
            var sumS = 0.0
            var sumV = 0.0
            var count = 0
            for (i in 0 until n) {
                if (sats[i] <= GREY_PIXEL_MAX_SATURATION) {
                    sumS += sats[i]
                    sumV += vals[i]
                    count++
                }
            }
            val safe = max(1, count)
            base = DetectedClothSurface(
                category = ClothHueCategory.SLATE_GREY,
                dominantHueDegrees = circularMeanHue(hues, n) { sats[it] <= GREY_PIXEL_MAX_SATURATION },
                meanSaturation = sumS / safe,
                meanValue = sumV / safe,
                clothCoverageFraction = 0.0,
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
            hsvInto(frame.argbPixels[i], hsv)
            if (isClothHsv(hsv[0], hsv[1], hsv[2], base)) clothPixels++
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
                hsvInto(frame.argbPixels[py * w + px], hsv)
                if (isClothHsv(hsv[0], hsv[1], hsv[2], cloth)) {
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
        var count = 0
        var tlSum = Int.MAX_VALUE
        var tlX = 0
        var tlY = 0
        var brSum = Int.MIN_VALUE
        var brX = 0
        var brY = 0
        var trDiff = Int.MIN_VALUE
        var trX = 0
        var trY = 0
        var blDiff = Int.MAX_VALUE
        var blX = 0
        var blY = 0

        for (gy in 0 until gh) {
            val y = yMin + gy * step
            val rowOffset = gy * gw
            for (gx in 0 until gw) {
                val idx = rowOffset + gx
                val include = if (useConnected) visited[idx] else clothGrid[idx]
                if (include) {
                    val x = xMin + gx * step
                    count++
                    val sum = x + y
                    val diff = x - y
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

        if (count < MIN_CLOTH_PIXELS_FOR_QUAD) {
            return listOf(
                Vec2(0.12 * w, 0.18 * h),
                Vec2(0.88 * w, 0.18 * h),
                Vec2(0.88 * w, 0.85 * h),
                Vec2(0.12 * w, 0.85 * h),
            )
        }
        val rawQuad = listOf(
            Vec2(tlX.toDouble(), tlY.toDouble()),
            Vec2(trX.toDouble(), trY.toDouble()),
            Vec2(brX.toDouble(), brY.toDouble()),
            Vec2(blX.toDouble(), blY.toDouble()),
        )
        val activeMask = if (useConnected) visited else clothGrid
        return if (isEndOnPerspectiveQuad(rawQuad) && cloth.meanSaturation < 0.45) {
            refineEndOnBroadcastQuad(activeMask, gw, gh, xMin, yMin, step, w, h, rawQuad)
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
        val yTop = (topYs[topYs.size / 2] - 9.0).coerceIn(0.0, h - 1.0)
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
            if (leftMag > 1.08 * slopeR) {
                // Upper-left rail partially occluded inward (e.g. player's arm); anchor on lower-left rail
                slopeL = -1.05 * slopeR
                xLU = xLL - slopeL * (yL - yU)
            } else if (leftMag < 0.95 * slopeR) {
                // Lower-left rail partially occluded inward (e.g. cue stick); anchor on upper-left rail
                slopeL = -1.05 * slopeR
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

    private fun isNearPocketMouth(center: Vec2, quad: List<Vec2>, isEndOn: Boolean): Boolean {
        if (quad.size != 4) return false
        for (corner in quad) {
            if (center.distanceTo(corner) <= 24.0) return true
        }
        if (isEndOn) {
            val leftMid = (quad[0] + quad[3]) * 0.5
            val rightMid = (quad[1] + quad[2]) * 0.5
            if (center.distanceTo(leftMid) <= 20.0 || center.distanceTo(rightMid) <= 20.0) return true
        } else {
            val topMid = (quad[0] + quad[1]) * 0.5
            val bottomMid = (quad[3] + quad[2]) * 0.5
            if (center.distanceTo(topMid) <= 18.0 || center.distanceTo(bottomMid) <= 18.0) return true
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
                hsvInto(frame.argbPixels[idx], hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]
                if (isClothHsv(hue, sat, value, cloth) || isClothShadowHsv(hue, sat, value, cloth)) continue
                val chroma = sat * value
                fgType[idx] = when {
                    (value >= 0.82 && sat <= 0.18) || (value >= 0.64 && sat <= 0.15) -> 1
                    value <= 0.28 || (value <= 0.34 && chroma <= 0.16) -> 2
                    hue in 38.0..75.0 && sat >= 0.30 && value >= 0.34 -> 5
                    (sat >= 0.35 && value >= 0.34) ||
                        (sat >= 0.20 && value >= 0.32 && hueDistance(hue, cloth.dominantHueDegrees) > 35.0) -> 3
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

        val visited = BooleanArray(total)
        val queue = IntArray(total)
        val rawBlobs = ArrayList<RawBlob>()
        for (start in 0 until total) {
            val startType = fgType[start]
            if (startType == 0.toByte() || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var area = 0
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
                area++
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

            val radiusPx = sqrt(area / PI)
            val minAllowedRadius = if (startType == 1.toByte()) min(2.0, radiusRange.start) else radiusRange.start
            if (radiusPx < minAllowedRadius || radiusPx > radiusRange.endInclusive) continue
            val boxW = maxX - minX + 1
            val boxH = maxY - minY + 1
            val aspect = boxW.toDouble() / boxH
            val fill = area.toDouble() / (boxW.toDouble() * boxH)
            val minAspect = if (startType == 1.toByte() && radiusPx < 4.5) 0.35 else 0.55
            val maxAspect = if (startType == 1.toByte() && radiusPx < 4.5) 2.50 else 1.80
            val minFill = if (startType == 1.toByte() && radiusPx < 4.5) 0.38 else 0.55
            if (aspect < minAspect || aspect > maxAspect || fill < minFill) continue

            val centerX = sumX / area
            val centerY = sumY / area
            val center = Vec2(centerX, centerY)
            if (radiusPx < radiusRange.start &&
                (!pointInQuad(centerX, centerY, quad, 5.0) || centerY > 0.50 * h)
            ) {
                continue
            }
            val sampleRadius = max(radiusPx, 3.2)
            val estimate = if (startType == 1.toByte() && radiusPx < radiusRange.start) {
                BallColorEstimate(
                    dominant = BallPaletteColor.WHITE,
                    secondary = null,
                    whiteFraction = 0.90,
                )
            } else {
                estimateBallColors(frame, centerX, centerY, sampleRadius) ?: continue
            }
            if (radiusPx < radiusRange.start &&
                !(estimate.dominant == BallPaletteColor.WHITE && estimate.whiteFraction >= 0.75)
            ) {
                continue
            }
            if (estimate.dominant == BallPaletteColor.BLACK &&
                (isNearPocketMouth(center, quad, isEndOn) || (isEndOn && !pointInQuad(centerX, centerY, quad, 14.0)))
            ) {
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
                val isShadowBelow = abs(blob.center.x - larger.center.x) <= 11.0 &&
                    blob.center.y >= larger.center.y &&
                    (blob.center.y - larger.center.y) <= 18.0
                dist < max(15.0, 1.45 * larger.radiusPx) || isShadowBelow
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

    private class BallColorEstimate(
        val dominant: BallPaletteColor,
        val secondary: BallPaletteColor?,
        val whiteFraction: Double,
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
        hue >= 245.5 && hue < 300.0 -> BallPaletteColor.PURPLE
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
        var blackPixels = 0
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
                if (value >= 0.75 && sat <= 0.20) {
                    whitePixels++
                } else if (value <= 0.28 || (value <= 0.35 && sat * value <= 0.16)) {
                    blackPixels++
                } else if (sat > 0.20) {
                    val color = chromaticColorOf(hue, sat, value)
                    if (color != null) votes[color.ordinal]++
                }
            }
        }
        if (totalPixels == 0) return null

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
        val whiteRatio = whitePixels.toDouble() / totalPixels
        val blackRatio = blackPixels.toDouble() / totalPixels

        val yellowVotes = votes[BallPaletteColor.YELLOW.ordinal]
        val isWntNineBallStripe = yellowVotes >= 3 && blackRatio >= 0.08

        return when {
            whiteRatio >= 0.75 -> BallColorEstimate(
                dominant = BallPaletteColor.WHITE,
                secondary = secondaryFromTop(topIdx, top, totalPixels),
                whiteFraction = whiteRatio.coerceIn(0.82, 0.99),
            )
            isWntNineBallStripe -> BallColorEstimate(
                dominant = BallPaletteColor.YELLOW,
                secondary = BallPaletteColor.BLACK,
                whiteFraction = max(whiteRatio, 0.56),
            )
            blackRatio >= 0.45 -> BallColorEstimate(
                dominant = BallPaletteColor.BLACK,
                secondary = secondaryFromTop(topIdx, top, totalPixels),
                whiteFraction = whiteRatio.coerceIn(0.05, 0.20),
            )
            else -> {
                val dominant = when {
                    topIdx >= 0 -> PALETTE[topIdx]
                    whitePixels >= blackPixels -> BallPaletteColor.WHITE
                    else -> BallPaletteColor.BLACK
                }
                val secondary = if (secondIdx >= 0 && second >= max(1.0, 0.10 * chromaticTotal)) {
                    PALETTE[secondIdx]
                } else {
                    null
                }
                val effectiveWhiteFraction = if (
                    dominant == BallPaletteColor.YELLOW &&
                    (blackRatio >= 0.08 || (whiteRatio + blackRatio) >= 0.25)
                ) {
                    max(whiteRatio, (0.50 + blackRatio * 0.50).coerceIn(0.52, 0.68))
                } else {
                    whiteRatio
                }
                BallColorEstimate(dominant, secondary, effectiveWhiteFraction.coerceIn(0.05, 0.75))
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
