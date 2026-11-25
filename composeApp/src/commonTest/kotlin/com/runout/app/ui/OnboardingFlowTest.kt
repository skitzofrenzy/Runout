package com.runout.app.ui

import com.runout.engine.model.ClothParams
import com.runout.engine.model.PocketPreset
import com.runout.engine.model.TableKind
import com.runout.engine.model.TableSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OnboardingFlowTest {

    @Test
    fun preselectIndexMatchesEveryStandardKindAndSize() {
        assertEquals(0, onboardingTablePresetIndex(TableSpec.sevenFootPool()))
        assertEquals(1, onboardingTablePresetIndex(TableSpec.eightFootPool()))
        assertEquals(2, onboardingTablePresetIndex(TableSpec.nineFootPool()))
        assertEquals(3, onboardingTablePresetIndex(TableSpec.fullSizeSnooker()))
    }

    @Test
    fun preselectIndexMatchesCalibratedTableWithCustomNameByKindAndLength() {
        val calibratedCloth = ClothParams(rollingResistanceMuR = 0.0118, cushionRestitutionEc = 0.72)

        val clubSeven = TableSpec.sevenFootPool(cloth = calibratedCloth, pocketPreset = PocketPreset.BAR_BOX)
            .copy(lengthMeters = 2.00, widthMeters = 1.00, name = "Corner Bar Box")
        val clubEight = TableSpec.eightFootPool(cloth = calibratedCloth, pocketPreset = PocketPreset.PRO_CUT)
            .copy(lengthMeters = 2.26, widthMeters = 1.13, name = "Club Table #4")
        val clubNine = TableSpec.nineFootPool(cloth = calibratedCloth, pocketPreset = PocketPreset.PRO_CUT)
            .copy(lengthMeters = 2.52, widthMeters = 1.26, name = "Match Table 1")
        val clubSnooker = TableSpec.fullSizeSnooker(cloth = calibratedCloth)
            .copy(name = "Snooker Room #2")

        assertEquals(0, onboardingTablePresetIndex(clubSeven))
        assertEquals(1, onboardingTablePresetIndex(clubEight))
        assertEquals(2, onboardingTablePresetIndex(clubNine))
        assertEquals(3, onboardingTablePresetIndex(clubSnooker))
    }

    @Test
    fun preselectIndexIgnoresMisleadingNameAndNeverCrossesTableKind() {
        // A 7-ft pool table renamed to match a snooker preset still resolves by kind and length.
        val poolNamedSnooker = TableSpec.sevenFootPool().copy(name = "12 ft Snooker")
        assertEquals(0, onboardingTablePresetIndex(poolNamedSnooker))

        // A 10-ft snooker table (3.05 m) is slightly closer in length to 9-ft pool (2.54 m) than 12-ft snooker (3.569 m),
        // yet its TableKind.SNOOKER must keep it on the snooker preset even with a pool name.
        val tenFootSnooker = TableSpec(
            lengthMeters = 3.05,
            widthMeters = 1.525,
            ballRadiusMeters = TableSpec.SNOOKER_BALL_RADIUS_METERS,
            name = "9 ft Pool",
            kind = TableKind.SNOOKER,
        )
        assertEquals(3, onboardingTablePresetIndex(tenFootSnooker))

        // Conversely, a 10-ft pool table stays on the nearest pool preset (9-ft), never snooker.
        val tenFootPool = TableSpec(
            lengthMeters = 3.05,
            widthMeters = 1.525,
            ballRadiusMeters = TableSpec.POOL_BALL_RADIUS_METERS,
            name = "Bigfoot 10-ft",
            kind = TableKind.POOL,
        )
        assertEquals(2, onboardingTablePresetIndex(tenFootPool))
    }

    @Test
    fun welcomeTourBackStepsBackOnStepsTwoAndThreeAndLeavesAppOnStepOne() {
        assertNull(onboardingBackStep(0))
        assertEquals(0, onboardingBackStep(1))
        assertEquals(1, onboardingBackStep(2))
        assertNull(onboardingBackStep(-1))

        // Walking back from the last step visits step 1, then step 0, then hands off to the system.
        val visited = mutableListOf<Int>()
        var current = 2
        while (true) {
            val next = onboardingBackStep(current) ?: break
            visited += next
            current = next
        }
        assertEquals(listOf(1, 0), visited)
        assertNull(onboardingBackStep(current))
    }
}
