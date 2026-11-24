package com.runout.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.hypot
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalloutPlacementTest {
    /** Sizes in dp at the given density, mirroring the constants the app passes in. */
    private class Setup(val density: Float, val maxWidthDp: Float, val naturalHeightDp: Float) {
        val ring = 14f * density
        val clearance = ring + 12f * density
        val gap = 24f * density
        val margin = 8f * density
        val maxWidth = maxWidthDp * density
        val minWidth = 144f * density
        val minHeight = 128f * density

        /** A card laid out at the given width: narrower cards wrap and grow taller, capped by the height limit. */
        fun measure(width: Float, maxHeight: Float): Size {
            val natural = naturalHeightDp * density * (maxWidth / width)
            return Size(width, min(natural, maxHeight))
        }

        fun place(canvas: Size, anchor: Offset, others: List<Offset> = emptyList()) = placeCallout(
            canvas = canvas,
            anchor = anchor,
            others = others,
            ringRadius = ring,
            clearance = clearance,
            gap = gap,
            margin = margin,
            maxWidth = maxWidth,
            minWidth = minWidth,
            minHeight = minHeight,
            measure = ::measure,
        )
    }

    private val tallCard = Setup(density = 2.75f, maxWidthDp = 280f, naturalHeightDp = 420f)
    private val wideCard = Setup(density = 2.75f, maxWidthDp = 320f, naturalHeightDp = 100f)

    private val inlineTable = Size(360f * 2.75f, 180f * 2.75f)
    private val largeTable = Size(800f * 2.75f, 400f * 2.75f)

    private fun gridOver(canvas: Size, steps: Int = 24): List<Offset> {
        val points = mutableListOf<Offset>()
        for (i in 0..steps) {
            for (j in 0..steps) {
                points += Offset(canvas.width * i / steps, canvas.height * j / steps)
            }
        }
        return points
    }

    private fun Rect.insideCanvas(canvas: Size, margin: Float): Boolean =
        left >= margin - 0.01f && top >= margin - 0.01f &&
            right <= canvas.width - margin + 0.01f && bottom <= canvas.height - margin + 0.01f

    private fun checkGuarantees(setup: Setup, canvas: Size) {
        for (anchor in gridOver(canvas)) {
            val placement = setup.place(canvas, anchor)
            val card = placement.card
            assertTrue(card.insideCanvas(canvas, setup.margin), "card outside canvas for anchor $anchor: $card")
            val grown = Rect(
                card.left - setup.clearance,
                card.top - setup.clearance,
                card.right + setup.clearance,
                card.bottom + setup.clearance,
            )
            assertFalse(grown.contains(anchor), "card $card plus clearance contains anchor $anchor")
        }
    }

    @Test
    fun tallCardNeverCoversItsPointOnTheInlineTable() = checkGuarantees(tallCard, inlineTable)

    @Test
    fun wideCardNeverCoversItsPointOnTheInlineTable() = checkGuarantees(wideCard, inlineTable)

    @Test
    fun tallCardNeverCoversItsPointOnALargeTable() = checkGuarantees(tallCard, largeTable)

    @Test
    fun wideCardNeverCoversItsPointOnALargeTable() = checkGuarantees(wideCard, largeTable)

    @Test
    fun cardStaysInsideTheCanvasEvenWhenNoSideIsRoomyEnough() {
        val tiny = Size(200f * 2.75f, 100f * 2.75f)
        for (anchor in gridOver(tiny, steps = 10)) {
            val card = tallCard.place(tiny, anchor).card
            assertTrue(card.insideCanvas(tiny, tallCard.margin), "card $card outside tiny canvas for $anchor")
        }
    }

    @Test
    fun anchorOutsideTheCanvasIsTreatedAsOnItsEdge() {
        val placement = wideCard.place(largeTable, Offset(-500f, -500f))
        assertTrue(placement.card.insideCanvas(largeTable, wideCard.margin))
        val edgePlacement = wideCard.place(largeTable, Offset(0f, 0f))
        assertEquals(edgePlacement.card, placement.card)
    }

    @Test
    fun leaderStartsOnTheCardEdgeAndEndsOutsideTheCard() {
        for (setup in listOf(tallCard, wideCard)) {
            for (canvas in listOf(inlineTable, largeTable)) {
                for (anchor in gridOver(canvas, steps = 12)) {
                    val placement = setup.place(canvas, anchor)
                    val card = placement.card
                    val s = placement.leaderStart
                    val onEdge = (s.x in card.left..card.right && (s.y == card.top || s.y == card.bottom)) ||
                        (s.y in card.top..card.bottom && (s.x == card.left || s.x == card.right))
                    assertTrue(onEdge, "leader start $s is not on the edge of $card")
                    assertFalse(card.contains(placement.leaderEnd), "leader end is inside the card for $anchor")
                    val reach = hypot(placement.leaderEnd.x - anchor.x.coerceIn(0f, canvas.width), placement.leaderEnd.y - anchor.y.coerceIn(0f, canvas.height))
                    assertTrue(reach <= setup.ring + 0.01f, "leader should end on the ring, not past it")
                }
            }
        }
    }

    @Test
    fun cardSitsAboveThePointWhenThereIsRoom() {
        val anchor = Offset(largeTable.width / 2f, largeTable.height * 0.8f)
        val card = wideCard.place(largeTable, anchor).card
        assertTrue(card.bottom < anchor.y, "expected the card above the point")
    }

    @Test
    fun cardMovesBelowWhenTheTopIsTooTight() {
        val anchor = Offset(largeTable.width / 2f, largeTable.height * 0.1f)
        val card = wideCard.place(largeTable, anchor).card
        assertTrue(card.top > anchor.y, "expected the card below the point")
    }

    @Test
    fun cardMovesToTheSideOnTheInlineTableWhenAboveAndBelowAreTooShort() {
        val anchor = Offset(inlineTable.width * 0.8f, inlineTable.height / 2f)
        val card = tallCard.place(inlineTable, anchor).card
        assertTrue(card.right < anchor.x, "expected the card left of the point")
    }

    @Test
    fun prefersTheSideThatHidesFewerOtherWaypoints() {
        val anchor = Offset(largeTable.width / 2f, largeTable.height / 2f)
        val above = wideCard.place(largeTable, anchor).card
        assertTrue(above.bottom < anchor.y)
        // Put a neighbour where the "above" card would sit: the card should prefer a different side.
        val neighbour = Offset(above.center.x, above.center.y)
        val moved = wideCard.place(largeTable, anchor, listOf(neighbour)).card
        assertFalse(moved.contains(neighbour), "card should avoid covering the neighbouring waypoint")
    }

    @Test
    fun shrunkenCardKeepsAtLeastTheMinimumHeightAndTheWholeCardIsTriedFirst() {
        // On a canvas just tall enough for the minimum above the point, a tall card shrinks rather than moving on.
        val canvas = Size(400f * 2.75f, 260f * 2.75f)
        val anchor = Offset(canvas.width / 2f, canvas.height * 0.95f)
        val card = tallCard.place(canvas, anchor).card
        assertTrue(card.height >= tallCard.minHeight - 0.01f)
        assertTrue(card.bottom < anchor.y)
    }

    @Test
    fun pressesInsideThePlacedCardBelongToTheCardAndOthersDoNot() {
        val anchor = Offset(largeTable.width / 2f, largeTable.height * 0.8f)
        val placement = wideCard.place(largeTable, anchor)
        val card = placement.card
        assertTrue(isPointOnCallout(card, card.center))
        assertTrue(isPointOnCallout(card, Offset(card.left + 1f, card.top + 1f)))
        assertFalse(isPointOnCallout(card, Offset(card.left - 1f, card.center.y)))
        assertFalse(isPointOnCallout(card, anchor))
        assertFalse(isPointOnCallout(card, placement.leaderEnd))
        assertFalse(isPointOnCallout(null, card.center))
    }
}
