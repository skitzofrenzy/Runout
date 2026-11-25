package com.runout.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrecisionLoupeTest {

    @Test
    fun computeLoupeCenterPlacesLoupeAboveFingerForCenterAndBottomTargets() {
        val canvas = Size(800f, 500f)
        val radius = 56f
        val clearance = 42f

        for (target in listOf(Offset(400f, 250f), Offset(400f, 440f), Offset(180f, 380f))) {
            val loupe = computeLoupeCenter(target, canvas, radius, clearance)
            assertTrue(loupe.y < target.y - clearance * 0.5f, "Expected loupe above $target, got $loupe")
            assertInsideBounds(loupe, canvas, radius)
        }
    }

    @Test
    fun computeLoupeCenterStaysInsideBoundsAndClearOfFingerAtAllCorners() {
        val canvas = Size(640f, 360f)
        val radius = 56f
        val clearance = 42f

        val corners = listOf(
            Offset(12f, 12f),
            Offset(628f, 12f),
            Offset(12f, 348f),
            Offset(628f, 348f),
        )
        for (corner in corners) {
            val loupe = computeLoupeCenter(corner, canvas, radius, clearance)
            assertInsideBounds(loupe, canvas, radius)
            val dist = hypot(loupe.x - corner.x, loupe.y - corner.y)
            assertTrue(dist >= radius + 8f, "Loupe $loupe overlaps finger at corner $corner (dist=$dist)")
        }
    }

    @Test
    fun computeLoupeCenterOppositeCornerModeSeparatesFromTargetOnCompactCanvas() {
        val compact = Size(140f, 140f)
        val radius = 32f
        val clearance = 20f

        val topLeftTarget = Offset(25f, 30f)
        val topLeftLoupe = computeLoupeCenter(
            targetCanvasPos = topLeftTarget,
            canvasSize = compact,
            loupeRadiusPx = radius,
            fingerClearancePx = clearance,
            preferOppositeCorner = true,
        )
        assertInsideBounds(topLeftLoupe, compact, radius)
        assertTrue(topLeftLoupe.x > compact.width * 0.5f && topLeftLoupe.y > compact.height * 0.5f)

        val bottomRightTarget = Offset(115f, 110f)
        val bottomRightLoupe = computeLoupeCenter(
            targetCanvasPos = bottomRightTarget,
            canvasSize = compact,
            loupeRadiusPx = radius,
            fingerClearancePx = clearance,
            preferOppositeCorner = true,
        )
        assertInsideBounds(bottomRightLoupe, compact, radius)
        assertTrue(bottomRightLoupe.x < compact.width * 0.5f && bottomRightLoupe.y < compact.height * 0.5f)
    }

    private fun assertInsideBounds(center: Offset, canvas: Size, radius: Float) {
        assertTrue(center.x >= radius, "center.x=${center.x} < radius=$radius")
        assertTrue(center.x <= canvas.width - radius, "center.x=${center.x} > maxX=${canvas.width - radius}")
        assertTrue(center.y >= radius, "center.y=${center.y} < radius=$radius")
        assertTrue(center.y <= canvas.height - radius, "center.y=${center.y} > maxY=${canvas.height - radius}")
    }

    @Test
    fun tableZoomCoordinateTransformsRoundTripAndClampPanCleanly() {
        val canvas = Size(800f, 400f)
        assertEquals(Offset.Zero, clampTablePanOffset(Offset(150f, -90f), zoom = 1.0f, canvas.width, canvas.height))

        val zoom = 2.5f
        val clampedPan = clampTablePanOffset(Offset(900f, -900f), zoom = zoom, canvas.width, canvas.height)
        // maxPanX = 800 * 1.5 * 0.5 = 600, maxPanY = 400 * 1.5 * 0.5 = 300
        assertEquals(600f, clampedPan.x, 1e-4f)
        assertEquals(-300f, clampedPan.y, 1e-4f)

        val activePan = Offset(-120f, 85f)
        for (pt in listOf(Offset(120f, 95f), Offset(400f, 200f), Offset(710f, 340f))) {
            val screen = baseToScreenCanvasOffset(pt, canvas, zoom, activePan)
            val recovered = screenToBaseCanvasOffset(screen, canvas, zoom, activePan)
            assertTrue(abs(pt.x - recovered.x) < 1e-3f, "Expected x=${pt.x}, got ${recovered.x}")
            assertTrue(abs(pt.y - recovered.y) < 1e-3f, "Expected y=${pt.y}, got ${recovered.y}")
        }
    }
}
