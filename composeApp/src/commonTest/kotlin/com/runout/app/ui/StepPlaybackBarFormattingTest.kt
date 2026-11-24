package com.runout.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class StepPlaybackBarFormattingTest {
    @Test
    fun consecutiveBallsReadAsARange() {
        assertEquals("balls 3–5", formatBallRange(listOf(3, 4, 5)))
        assertEquals("balls 1–2", formatBallRange(listOf(1, 2)))
    }

    @Test
    fun aSingleBallIsNamedOnItsOwn() {
        assertEquals("ball 9", formatBallRange(listOf(9)))
    }

    @Test
    fun ballsOutOfSequenceAreListedInShootingOrder() {
        assertEquals("balls 1, 5, 3", formatBallRange(listOf(1, 5, 3)))
        assertEquals("balls 2, 4", formatBallRange(listOf(2, 4)))
    }

    @Test
    fun anEmptyWindowHasNoLabel() {
        assertEquals("", formatBallRange(emptyList()))
    }
}
