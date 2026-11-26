package com.runout.app.ui

import com.runout.engine.math.Vec2
import com.runout.engine.physics.PathWaypoint
import com.runout.engine.physics.PathWaypointKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathWaypointDescriptionTest {

    private val origin = Vec2(0.0, 0.0)

    private val strike = PathWaypoint(
        kind = PathWaypointKind.CUE_START,
        position = origin,
        distanceAlongPathMeters = 0.0,
        speedOutMps = 2.0,
        verticalSpinAfter = 0.0,
        sideSpinAfter = 0.0,
        speedUnits = 4.0,
    )

    /** 93% arrives (7% lost in travel), 90% leaves (3% lost from contact). */
    private val objectContact = PathWaypoint(
        kind = PathWaypointKind.OBJECT_CONTACT,
        position = origin,
        distanceAlongPathMeters = 1.0,
        speedInMps = 1.86,
        speedOutMps = 1.80,
        objectBallSpeedMps = 1.2,
        otherBallNumber = 5,
        verticalSpinBefore = 0.0,
        verticalSpinAfter = 0.0,
    )

    /** Second contact: arrives with 50% of the stroke speed, leaves with 40%. */
    private val cushion = PathWaypoint(
        kind = PathWaypointKind.CUSHION_CONTACT,
        position = origin,
        distanceAlongPathMeters = 2.0,
        speedInMps = 1.0,
        speedOutMps = 0.8,
        cushionIndex = 1,
        verticalSpinAfter = 1.0,
        sideSpinBefore = 0.0,
        sideSpinAfter = 0.0,
    )

    private val ballContact = PathWaypoint(
        kind = PathWaypointKind.BALL_CONTACT,
        position = origin,
        distanceAlongPathMeters = 3.0,
        speedInMps = 0.6,
        speedOutMps = 0.4,
        otherBallNumber = 9,
    )

    private val waypoints = listOf(strike, objectContact, cushion, ballContact)

    private fun describe(index: Int, mode: AppDisplayMode) =
        describePathWaypointAt(waypoints, index, mode)

    @Test
    fun strikeShowsStartingSpeedAsOneHundredPercent() {
        for (mode in AppDisplayMode.entries) {
            val text = describe(0, mode).lines.joinToString(" ")
            assertTrue("100%" in text, "Strike card must show 100% in $mode: $text")
        }
    }

    @Test
    fun firstContactShowsRemainingTravelLossAndContactLossThatSumToOneHundred() {
        for (mode in AppDisplayMode.entries) {
            val line = describe(1, mode).lines.first()
            assertTrue("90%" in line, "remaining 90% missing in $mode: $line")
            assertTrue("7% lost in travel" in line, "travel loss missing in $mode: $line")
            assertTrue("3% from contact" in line, "contact loss missing in $mode: $line")
            assertEquals(100, 90 + 7 + 3)
        }
    }

    @Test
    fun laterContactsShowRemainingAndContactLossOnlyRelativeToStrokeSpeed() {
        for (index in listOf(2, 3)) {
            for (mode in AppDisplayMode.entries) {
                val text = describe(index, mode).lines.joinToString(" ")
                assertTrue("lost from contact" in text, "contact loss missing at $index in $mode: $text")
                assertFalse("travel" in text, "travel loss must not repeat at $index in $mode: $text")
            }
        }
        // Cushion: 40% of the 2.0 m/s stroke is left; 50% arrived so 10% was lost from contact.
        assertTrue("40% speed left (10% lost from contact)" in describe(2, AppDisplayMode.SIMPLE).lines.first())
        // Ball contact: 20% left of the stroke; 30% arrived so 10% was lost from contact.
        assertTrue("20% speed left (10% lost from contact)" in describe(3, AppDisplayMode.SIMPLE).lines.first())
    }

    @Test
    fun proContactCardsIncludeMetresPerSecondAgainstStrokeSpeed() {
        val first = describe(1, AppDisplayMode.PRO).lines.first()
        assertTrue("1.80 m/s of 2.00 m/s" in first, first)
        val later = describe(2, AppDisplayMode.PRO).lines.first()
        assertTrue("0.80 m/s of 2.00 m/s" in later, later)
    }

    @Test
    fun postContactAndSpinTransitionsShowRemainingShareOfStrokeSpeed() {
        val slideEnds = PathWaypoint(
            kind = PathWaypointKind.POST_IMPACT_TRANSITION,
            position = origin,
            distanceAlongPathMeters = 1.5,
            speedOutMps = 1.0,
            verticalSpinBefore = 0.0,
        )
        val transition = PathWaypoint(
            kind = PathWaypointKind.SPIN_TRANSITION,
            position = origin,
            distanceAlongPathMeters = 0.4,
            speedOutMps = 1.9,
            verticalSpinAfter = 0.2,
        )
        val list = listOf(strike, transition, slideEnds)
        for (mode in AppDisplayMode.entries) {
            val slide = describePathWaypointAt(list, 2, mode).lines.joinToString(" ")
            assertTrue("50% speed left" in slide, "post-impact remaining missing in $mode: $slide")
            val spin = describePathWaypointAt(list, 1, mode).lines.joinToString(" ")
            assertTrue("95% speed left" in spin, "spin transition remaining missing in $mode: $spin")
            assertTrue("5% lost in travel" in spin, "first point after strike shows travel loss in $mode: $spin")
        }
    }

    @Test
    fun everyCardKeepsAtMostThreeLines() {
        for (mode in AppDisplayMode.entries) {
            for (index in waypoints.indices) {
                val card = describe(index, mode)
                assertTrue(card.lines.size <= 3, "card ${card.title} has ${card.lines.size} lines in $mode")
            }
        }
    }
}
