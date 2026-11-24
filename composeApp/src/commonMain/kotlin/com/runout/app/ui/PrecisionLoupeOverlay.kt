package com.runout.app.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * Computes a clean viewport center for the floating magnifying glass loupe relative to
 * [targetCanvasPos], placing it above the user's finger when vertical headroom allows or
 * shifting beside/opposite near top corners while staying clamped inside [canvasSize].
 */
fun computeLoupeCenter(
    targetCanvasPos: Offset,
    canvasSize: Size,
    loupeRadiusPx: Float,
    fingerClearancePx: Float,
    preferOppositeCorner: Boolean = false,
): Offset {
    val minCanvasSide = min(canvasSize.width, canvasSize.height).coerceAtLeast(2f)
    val effectiveRadius = min(loupeRadiusPx, minCanvasSide * 0.45f).coerceAtLeast(1f)
    val margin = max(8f, effectiveRadius * 0.12f)
    val minX = (effectiveRadius + margin).coerceAtMost(canvasSize.width * 0.5f)
    val maxX = (canvasSize.width - effectiveRadius - margin).coerceAtLeast(minX)
    val minY = (effectiveRadius + margin).coerceAtMost(canvasSize.height * 0.5f)
    val maxY = (canvasSize.height - effectiveRadius - margin).coerceAtLeast(minY)

    if (preferOppositeCorner) {
        val rawX = if (targetCanvasPos.x < canvasSize.width * 0.5f) {
            canvasSize.width - effectiveRadius - margin
        } else {
            effectiveRadius + margin
        }
        val rawY = if (targetCanvasPos.y < canvasSize.height * 0.5f) {
            canvasSize.height - effectiveRadius - margin
        } else {
            effectiveRadius + margin
        }
        return Offset(
            x = rawX.coerceIn(minX, maxX),
            y = rawY.coerceIn(minY, maxY),
        )
    }

    val idealY = targetCanvasPos.y - fingerClearancePx - effectiveRadius
    if (idealY >= minY) {
        return Offset(
            x = targetCanvasPos.x.coerceIn(minX, maxX),
            y = idealY.coerceIn(minY, maxY),
        )
    }

    // Target is close to the top edge: shift sideways toward the side with more horizontal room.
    val sideSign = if (targetCanvasPos.x <= canvasSize.width * 0.5f) 1f else -1f
    val shiftedX = targetCanvasPos.x + sideSign * (effectiveRadius + fingerClearancePx * 0.85f)
    val fallbackY = max(minY, targetCanvasPos.y + effectiveRadius * 0.25f)
    return Offset(
        x = shiftedX.coerceIn(minX, maxX),
        y = fallbackY.coerceIn(minY, maxY),
    )
}

/**
 * Renders a floating circular magnifying loupe anchored to [loupeCenter] showing [drawZoomedScene]
 * magnified by [zoom] around [focusCanvasPos], complete with optional hairline crosshair,
 * precision red center dot, glass bezel, and bottom label badge.
 */
fun DrawScope.drawPrecisionLoupe(
    focusCanvasPos: Offset,
    loupeCenter: Offset,
    loupeRadiusPx: Float,
    zoom: Float = 2.5f,
    accentColor: Color = RunoutColors.PrimaryCyan,
    badgeLabel: String? = null,
    showCenterRedDot: Boolean = true,
    showCrosshair: Boolean = true,
    textMeasurer: TextMeasurer? = null,
    labelFontSp: TextUnit = 10.sp,
    drawZoomedScene: DrawScope.() -> Unit,
) {
    if (loupeRadiusPx <= 4f || zoom <= 0.1f) return

    // Dashed leader line connecting focus point to loupe rim
    val delta = loupeCenter - focusCanvasPos
    val dist = delta.getDistance()
    if (dist > loupeRadiusPx + 4f) {
        val rimPt = loupeCenter - delta * (loupeRadiusPx / dist)
        drawLine(
            color = accentColor.copy(alpha = 0.60f),
            start = focusCanvasPos,
            end = rimPt,
            strokeWidth = 1.6f,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f),
        )
    }

    // Soft drop shadow
    drawCircle(
        color = Color(0x66000000),
        radius = loupeRadiusPx + 3.5f,
        center = loupeCenter + Offset(0f, 3.5f),
    )

    // Clipped magnified lens contents
    val lensClip = Path().apply {
        addOval(Rect(center = loupeCenter, radius = loupeRadiusPx))
    }
    clipPath(lensClip) {
        drawRect(
            color = RunoutColors.Background,
            topLeft = Offset(loupeCenter.x - loupeRadiusPx, loupeCenter.y - loupeRadiusPx),
            size = Size(loupeRadiusPx * 2f, loupeRadiusPx * 2f),
        )
        withTransform({
            translate(
                left = loupeCenter.x - focusCanvasPos.x * zoom,
                top = loupeCenter.y - focusCanvasPos.y * zoom,
            )
            scale(scaleX = zoom, scaleY = zoom, pivot = Offset.Zero)
        }) {
            drawZoomedScene()
        }

        // Subtle inner rim shading
        drawCircle(
            color = Color(0x44000000),
            radius = loupeRadiusPx - 1f,
            center = loupeCenter,
            style = Stroke(width = 3.5f),
        )

        // Dual-contrast open-center crosshair
        if (showCrosshair) {
            val gap = min(6f, loupeRadiusPx * 0.14f)
            val arm = min(16f, loupeRadiusPx * 0.42f)
            val arms = listOf(
                Offset(loupeCenter.x - arm, loupeCenter.y) to Offset(loupeCenter.x - gap, loupeCenter.y),
                Offset(loupeCenter.x + gap, loupeCenter.y) to Offset(loupeCenter.x + arm, loupeCenter.y),
                Offset(loupeCenter.x, loupeCenter.y - arm) to Offset(loupeCenter.x, loupeCenter.y - gap),
                Offset(loupeCenter.x, loupeCenter.y + gap) to Offset(loupeCenter.x, loupeCenter.y + arm),
            )
            for ((start, end) in arms) {
                drawLine(Color(0xD90F172A), start, end, strokeWidth = 2.4f, cap = StrokeCap.Round)
                drawLine(Color(0xF0F8FAFC), start, end, strokeWidth = 1.1f, cap = StrokeCap.Round)
            }
        }

        // Precision center red dot
        if (showCenterRedDot) {
            drawCircle(color = Color(0xEE0F172A), radius = 4.2f, center = loupeCenter)
            drawCircle(color = Color(0xF5FFFFFF), radius = 3.2f, center = loupeCenter)
            drawCircle(color = Color(0xFFEF4444), radius = 2.3f, center = loupeCenter)
        }
    }

    // Outer double bezel + top-left specular glass highlight
    drawCircle(
        color = Color(0xFF0F172A),
        radius = loupeRadiusPx + 1.4f,
        center = loupeCenter,
        style = Stroke(width = 3.4f),
    )
    drawCircle(
        color = accentColor,
        radius = loupeRadiusPx,
        center = loupeCenter,
        style = Stroke(width = 2.2f),
    )
    val glassRadius = (loupeRadiusPx - 3f).coerceAtLeast(2f)
    drawArc(
        color = Color(0x44FFFFFF),
        startAngle = 205f,
        sweepAngle = 55f,
        useCenter = false,
        topLeft = Offset(loupeCenter.x - glassRadius, loupeCenter.y - glassRadius),
        size = Size(glassRadius * 2f, glassRadius * 2f),
        style = Stroke(width = 1.8f, cap = StrokeCap.Round),
    )

    // Bottom rim badge pill
    if (!badgeLabel.isNullOrBlank() && textMeasurer != null) {
        val measured = textMeasurer.measure(
            text = badgeLabel,
            style = TextStyle(
                color = Color.White,
                fontSize = labelFontSp,
                fontWeight = FontWeight.Bold,
            ),
        )
        val padX = 7f
        val padY = 3f
        val pillW = measured.size.width + padX * 2f
        val pillH = measured.size.height + padY * 2f
        val pillLeft = (loupeCenter.x - pillW * 0.5f).coerceIn(
            2f,
            (size.width - pillW - 2f).coerceAtLeast(2f),
        )
        val pillTop = (loupeCenter.y + loupeRadiusPx - pillH * 0.55f).coerceIn(
            2f,
            (size.height - pillH - 2f).coerceAtLeast(2f),
        )
        drawRoundRect(
            color = Color(0xE60F172A),
            topLeft = Offset(pillLeft, pillTop),
            size = Size(pillW, pillH),
            cornerRadius = CornerRadius(pillH * 0.5f, pillH * 0.5f),
        )
        drawRoundRect(
            color = accentColor,
            topLeft = Offset(pillLeft, pillTop),
            size = Size(pillW, pillH),
            cornerRadius = CornerRadius(pillH * 0.5f, pillH * 0.5f),
            style = Stroke(width = 1.2f),
        )
        drawText(
            textLayoutResult = measured,
            topLeft = Offset(pillLeft + padX, pillTop + padY),
        )
    }
}
