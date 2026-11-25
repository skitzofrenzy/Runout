package com.runout.app.ui

import com.runout.engine.math.Vec2
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.vision.DetectedBallCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhotoBallSwipeUndoTest {

    private fun sampleCandidate(id: Int, x: Double = 100.0): DetectedBallCandidate =
        DetectedBallCandidate(
            id = id,
            imageCenterPx = Vec2(x, 200.0),
            dominantColor = BallPaletteColor.RED,
            whiteFraction = 0.1,
            detectorConfidence = 0.9,
        )

    @Test
    fun swipeCommitThresholdClampsWithinMinMaxAndNarrowRowBounds() {
        // Typical phone width (1080 px row at 3x density -> min 288 px, max 432 px; 36% = 388.8 px)
        val phoneThreshold = computeSwipeRemoveCommitThresholdPx(
            rowWidthPx = 1080f,
            minThresholdPx = 288f,
            maxThresholdPx = 432f,
        )
        assertEquals(1080f * SWIPE_REMOVE_COMMIT_FRACTION, phoneThreshold, 0.01f)

        // Wide tablet row clamps at maxThresholdPx
        val tabletThreshold = computeSwipeRemoveCommitThresholdPx(
            rowWidthPx = 2400f,
            minThresholdPx = 288f,
            maxThresholdPx = 432f,
        )
        assertEquals(432f, tabletThreshold, 0.01f)

        // Very narrow split-screen row stays reachable at <= 60% of row width
        val narrowThreshold = computeSwipeRemoveCommitThresholdPx(
            rowWidthPx = 300f,
            minThresholdPx = 288f,
            maxThresholdPx = 432f,
        )
        assertEquals(180f, narrowThreshold, 0.01f)
    }

    @Test
    fun swipeArmsOnlyOnLeftwardDragAtOrBeyondThreshold() {
        val threshold = 120f
        assertFalse(isSwipeRemoveArmed(offsetX = 0f, commitThresholdPx = threshold))
        assertFalse(isSwipeRemoveArmed(offsetX = 150f, commitThresholdPx = threshold))
        assertFalse(isSwipeRemoveArmed(offsetX = -119.5f, commitThresholdPx = threshold))
        assertTrue(isSwipeRemoveArmed(offsetX = -120f, commitThresholdPx = threshold))
        assertTrue(isSwipeRemoveArmed(offsetX = -180f, commitThresholdPx = threshold))
    }

    @Test
    fun undoStackRestoresSequentialRemovalsInLifoOrderAndRefreshesTimerEventId() {
        val cand1 = sampleCandidate(1, x = 100.0)
        val cand2 = sampleCandidate(2, x = 200.0)
        val cand3 = sampleCandidate(3, x = 300.0)

        var stack = BallRemovalUndoStack()
        assertNull(stack.current)

        // Remove Ball #3 first
        stack = stack.push(
            message = "Removed Ball #3",
            rawDetections = listOf(cand1, cand2, cand3),
            numberOverrides = mapOf(1 to 1, 2 to 2, 3 to 3),
        )
        val firstEventId = stack.current!!.eventId
        assertEquals("Removed Ball #3", stack.current!!.message)

        // Remove Ball #2 next
        stack = stack.push(
            message = "Removed Ball #2",
            rawDetections = listOf(cand1, cand2),
            numberOverrides = mapOf(1 to 1, 2 to 2),
        )
        val secondEventId = stack.current!!.eventId
        assertNotEquals(firstEventId, secondEventId)
        assertEquals("Removed Ball #2", stack.current!!.message)
        assertEquals(2, stack.entries.size)

        // First Undo restores Ball #2 and exposes Ball #3 with a fresh eventId (resets countdown)
        val (restoredFirst, afterFirstUndo) = stack.pop()
        assertNotNull(restoredFirst)
        assertEquals("Removed Ball #2", restoredFirst.message)
        assertEquals(listOf(cand1, cand2), restoredFirst.previousRawDetections)
        assertEquals(mapOf(1 to 1, 2 to 2), restoredFirst.previousNumberOverrides)

        val exposedNext = afterFirstUndo.current
        assertNotNull(exposedNext)
        assertEquals("Removed Ball #3", exposedNext.message)
        assertTrue(
            exposedNext.eventId > secondEventId,
            "Exposed previous undo entry must get a fresh eventId so the undo timer resets",
        )

        // Second Undo restores Ball #3 and empties stack
        val (restoredSecond, afterSecondUndo) = afterFirstUndo.pop()
        assertNotNull(restoredSecond)
        assertEquals(listOf(cand1, cand2, cand3), restoredSecond.previousRawDetections)
        assertNull(afterSecondUndo.current)
    }

    @Test
    fun undoStackClearEmptiesPendingSnapshots() {
        val filled = BallRemovalUndoStack().push(
            message = "Cleared 9 balls",
            rawDetections = listOf(sampleCandidate(1)),
            numberOverrides = mapOf(1 to 9),
        )
        assertNotNull(filled.current)
        val cleared = filled.clear()
        assertNull(cleared.current)
        assertTrue(cleared.entries.isEmpty())
    }
}
