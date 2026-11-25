package com.runout.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.runout.engine.math.Vec2
import com.runout.engine.model.PocketId
import com.runout.engine.model.PocketSpec
import com.runout.engine.model.TableSpec
import com.runout.engine.physics.PocketGeometry
import kotlin.math.abs
import kotlin.math.max

/*
 * Shared table drawing. The playing surface is the rectangle 0..length x 0..width measured at the cushion noses.
 * Cushions are straight strips behind the nose line that stop at each pocket's jaw tips; the end of a strip follows
 * the pocket facing, which leans back toward the hole. The holes sit behind the nose line, in the rail band, and
 * nothing dark is drawn on the cloth except the corner of a corner pocket behind its mouth.
 */

/** How far a cushion extends behind its nose line, in metres. */
internal const val CUSHION_DEPTH_METERS: Double = 0.05

/** Wood left between the edge of a hole and the outer edge of the rail, in metres. */
internal const val RAIL_WOOD_LIP_METERS: Double = 0.012

/** Smallest rail band, in metres, drawn around the nose rectangle even when no hole needs more. */
internal const val MIN_RAIL_BAND_METERS: Double = 0.095

/** A cushion strip in table metres: the nose from [noseStart] to [noseEnd] and the back edge behind it. */
internal data class CushionSegment(
    val noseStart: Vec2,
    val noseEnd: Vec2,
    val backEnd: Vec2,
    val backStart: Vec2,
) {
    val polygon: List<Vec2> get() = listOf(noseStart, noseEnd, backEnd, backStart)
}

/** Unit vector pointing out of the playing rectangle, away from the rail that [tip] lies on. */
private fun railOutward(table: TableSpec, tip: Vec2): Vec2 {
    val eps = 1e-6
    return when {
        abs(tip.y) < eps -> Vec2(0.0, -1.0)
        abs(tip.y - table.widthMeters) < eps -> Vec2(0.0, 1.0)
        abs(tip.x) < eps -> Vec2(-1.0, 0.0)
        else -> Vec2(1.0, 0.0)
    }
}

/** Unit direction of the facing that leaves [tip], pointing from the tip back toward the hole. */
private fun facingDirection(pocket: PocketSpec, tip: Vec2): Vec2 {
    val normal = PocketGeometry.facingNormal(pocket, tip)
    val along = Vec2(-normal.y, normal.x)
    return if (along.dot(pocket.axisIntoPocket) >= 0.0) along else -along
}

/** The point where the facing from [tip] reaches [depth] behind the nose line. */
internal fun facingBackPoint(
    table: TableSpec,
    pocket: PocketSpec,
    tip: Vec2,
    depth: Double = CUSHION_DEPTH_METERS,
): Vec2 {
    val outward = railOutward(table, tip)
    val facing = facingDirection(pocket, tip)
    val lean = facing.dot(outward)
    return if (lean > 0.05) tip + facing * (depth / lean) else tip + outward * depth
}

/** The six cushion strips (two per long rail, one per short rail), or none if the table lacks a pocket. */
internal fun cushionSegments(table: TableSpec, depth: Double = CUSHION_DEPTH_METERS): List<CushionSegment> {
    val byId = table.pockets.associateBy { it.id }
    if (PocketId.entries.any { it !in byId }) return emptyList()
    fun pocket(id: PocketId): PocketSpec = byId.getValue(id)

    fun segment(from: PocketSpec, fromTip: Vec2, to: PocketSpec, toTip: Vec2) = CushionSegment(
        noseStart = fromTip,
        noseEnd = toTip,
        backEnd = facingBackPoint(table, to, toTip, depth),
        backStart = facingBackPoint(table, from, fromTip, depth),
    )

    val hl = pocket(PocketId.HEAD_LEFT)
    val hr = pocket(PocketId.HEAD_RIGHT)
    val sl = pocket(PocketId.SIDE_LEFT)
    val sr = pocket(PocketId.SIDE_RIGHT)
    val fl = pocket(PocketId.FOOT_LEFT)
    val fr = pocket(PocketId.FOOT_RIGHT)
    return listOf(
        segment(hl, hl.jawTipA, sl, sl.jawTipA),
        segment(sl, sl.jawTipB, fl, fl.jawTipA),
        segment(hr, hr.jawTipA, sr, sr.jawTipA),
        segment(sr, sr.jawTipB, fr, fr.jawTipA),
        segment(hl, hl.jawTipB, hr, hr.jawTipB),
        segment(fl, fl.jawTipB, fr, fr.jawTipB),
    )
}

/** How far the hole of [pocket] reaches beyond the nose rectangle of [table], in metres (never negative). */
internal fun holeReachBeyondNoseMeters(table: TableSpec, pocket: PocketSpec): Double {
    val c = pocket.holeCenter
    val r = pocket.holeRadiusMeters
    return maxOf(
        0.0,
        r - c.x,
        c.x + r - table.lengthMeters,
        r - c.y,
        c.y + r - table.widthMeters,
    )
}

/**
 * Rail band, in metres, that holds every hole of [table] with a little wood to spare and the cushions behind their
 * noses; never less than [MIN_RAIL_BAND_METERS].
 */
internal fun requiredRailBandMeters(table: TableSpec): Double {
    val holes = table.pockets.maxOfOrNull { holeReachBeyondNoseMeters(table, it) } ?: 0.0
    return max(MIN_RAIL_BAND_METERS, max(holes + RAIL_WOOD_LIP_METERS, CUSHION_DEPTH_METERS * 1.4))
}

/** Colours used by [drawTableBody] and [drawTableCushions]. */
internal class TableDrawPalette(
    val woodOuter: Color,
    val woodInner: Color,
    val cloth: Color,
    val cushion: Color,
    val throat: Color,
    val holeRim: Color,
)

/** A slightly darker shade of [cloth], used for the cushions. */
internal fun cushionShadeOf(cloth: Color): Color = lerp(cloth, Color.Black, 0.28f)

private fun TableCanvasGeometry.polygonPath(points: List<Vec2>): Path = Path().apply {
    points.forEachIndexed { i, p ->
        val o = toCanvasOffset(p)
        if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
    }
    close()
}

/** The dark opening of [pocket] between its facings, from the mouth to [CUSHION_DEPTH_METERS] behind it. */
internal fun pocketThroat(table: TableSpec, pocket: PocketSpec): List<Vec2> = listOf(
    pocket.jawTipA,
    pocket.jawTipB,
    facingBackPoint(table, pocket, pocket.jawTipB),
    facingBackPoint(table, pocket, pocket.jawTipA),
)

/**
 * Draws the wooden rail, the cloth and the six pocket openings (throat and hole). Cushions go on top with
 * [drawTableCushions], after anything that is drawn on the cloth.
 */
internal fun DrawScope.drawTableBody(table: TableSpec, geom: TableCanvasGeometry, palette: TableDrawPalette) {
    val pad = geom.railPaddingPx
    val outerLeft = geom.playLeft - pad
    val outerTop = geom.playTop - pad
    val outerWidth = geom.playWidth + 2f * pad
    val outerHeight = geom.playHeight + 2f * pad
    drawRoundRect(
        color = palette.woodOuter,
        topLeft = Offset(outerLeft, outerTop),
        size = Size(outerWidth, outerHeight),
        cornerRadius = CornerRadius(pad * 0.7f, pad * 0.7f),
    )
    val inset = pad * 0.12f
    drawRoundRect(
        color = palette.woodInner,
        topLeft = Offset(outerLeft + inset, outerTop + inset),
        size = Size(outerWidth - 2f * inset, outerHeight - 2f * inset),
        cornerRadius = CornerRadius(pad * 0.6f, pad * 0.6f),
    )
    drawRect(
        color = palette.cloth,
        topLeft = Offset(geom.playLeft, geom.playTop),
        size = Size(geom.playWidth, geom.playHeight),
    )
    val rimWidth = max(1.2f, geom.pxPerMeter * 0.004f)
    for (pocket in table.pockets) {
        drawPath(geom.polygonPath(pocketThroat(table, pocket)), palette.throat)
        val hole = geom.toCanvasOffset(pocket.holeCenter)
        val radius = (pocket.holeRadiusMeters * geom.pxPerMeter).toFloat()
        drawCircle(color = palette.throat, radius = radius, center = hole)
        drawCircle(
            color = palette.holeRim,
            radius = radius - rimWidth * 0.5f,
            center = hole,
            style = Stroke(width = rimWidth),
        )
    }
}

/** Draws the six cushion strips over the rail band, each with a lighter line along its nose. */
internal fun DrawScope.drawTableCushions(table: TableSpec, geom: TableCanvasGeometry, palette: TableDrawPalette) {
    val noseWidth = max(1f, geom.pxPerMeter * 0.004f)
    for (segment in cushionSegments(table)) {
        drawPath(geom.polygonPath(segment.polygon), palette.cushion)
        drawLine(
            color = Color(0x40000000),
            start = geom.toCanvasOffset(segment.noseStart),
            end = geom.toCanvasOffset(segment.noseEnd),
            strokeWidth = noseWidth,
            cap = StrokeCap.Butt,
        )
    }
}

/** Distance, in pixels, from the nose line to the middle of the wood beside a cushion, where rail diamonds sit. */
internal fun TableCanvasGeometry.diamondOffsetPx(): Float =
    (CUSHION_DEPTH_METERS.toFloat() * pxPerMeter + railPaddingPx) * 0.5f

/** Points to draw on the screen for a stroke across a pocket's mouth, from jaw tip to jaw tip. */
internal fun TableCanvasGeometry.mouthSegment(pocket: PocketSpec): Pair<Offset, Offset> =
    toCanvasOffset(pocket.jawTipA) to toCanvasOffset(pocket.jawTipB)

/** Centre and radius, in pixels, of the circle that outlines the hole of [pocket]. */
internal fun TableCanvasGeometry.holeCircle(pocket: PocketSpec): Pair<Offset, Float> =
    toCanvasOffset(pocket.holeCenter) to (pocket.holeRadiusMeters * pxPerMeter).toFloat()

/** The point that selects [pocket] by tap: halfway between the middle of its mouth and the middle of its hole. */
internal fun pocketTapPoint(pocket: PocketSpec): Vec2 = (pocket.mouthCenter + pocket.holeCenter) * 0.5

/** Radius, in dp, within which a tap selects a pocket (a 48 dp target). */
internal const val POCKET_TAP_RADIUS_DP: Float = 24f
