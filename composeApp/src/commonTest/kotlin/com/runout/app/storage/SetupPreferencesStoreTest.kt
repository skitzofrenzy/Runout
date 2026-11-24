package com.runout.app.storage

import com.runout.app.ui.AppDisplayMode
import com.runout.app.ui.PlanningHorizonOption
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.ExperienceLevel
import com.runout.engine.model.GameType
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupPreferencesStoreTest {

    @Test
    fun roundTripSerializationPreservesAllSetupFields() {
        val cases = listOf(
            SavedSetupPreferences(
                hasCompletedSetup = true,
                experienceLevel = ExperienceLevel.BEGINNER,
                shaftType = ShaftType.STANDARD,
                gameType = GameType.EIGHT_BALL,
                useUkBlackball = true,
                tableSizePreset = TableSizePreset.SEVEN_FOOT,
                displayMode = AppDisplayMode.SIMPLE,
            ),
            SavedSetupPreferences(
                hasCompletedSetup = true,
                experienceLevel = ExperienceLevel.INTERMEDIATE,
                shaftType = ShaftType.LOW_DEFLECTION,
                gameType = GameType.NINE_BALL,
                useUkBlackball = false,
                tableSizePreset = TableSizePreset.EIGHT_FOOT,
                displayMode = AppDisplayMode.SIMPLE,
            ),
            SavedSetupPreferences(
                hasCompletedSetup = true,
                experienceLevel = ExperienceLevel.ADVANCED,
                shaftType = ShaftType.LOW_DEFLECTION,
                gameType = GameType.TEN_BALL,
                useUkBlackball = false,
                tableSizePreset = TableSizePreset.NINE_FOOT,
                displayMode = AppDisplayMode.PRO,
            ),
            SavedSetupPreferences(
                hasCompletedSetup = false,
                experienceLevel = ExperienceLevel.ADVANCED,
                shaftType = ShaftType.STANDARD,
                gameType = GameType.TEN_BALL,
                useUkBlackball = false,
                tableSizePreset = TableSizePreset.SNOOKER_12FT,
                displayMode = AppDisplayMode.PRO,
            ),
        )

        for (expected in cases) {
            val encoded = expected.toKeyValueMap()
            val decoded = SavedSetupPreferences.fromKeyValueMap(encoded)
            assertEquals(expected, decoded)
        }
    }

    @Test
    fun missingCompletedFlagReturnsNull() {
        assertNull(SavedSetupPreferences.fromKeyValueMap(emptyMap()))
        assertNull(
            SavedSetupPreferences.fromKeyValueMap(
                mapOf(
                    SavedSetupPreferences.KEY_EXPERIENCE_LEVEL to ExperienceLevel.ADVANCED.name,
                    SavedSetupPreferences.KEY_GAME_TYPE to GameType.NINE_BALL.name,
                ),
            ),
        )
    }

    @Test
    fun invalidOrPartialEntriesFallBackSafelyToDefaults() {
        val decoded = SavedSetupPreferences.fromKeyValueMap(
            mapOf(
                SavedSetupPreferences.KEY_SETUP_COMPLETED to "true",
                SavedSetupPreferences.KEY_EXPERIENCE_LEVEL to "GRANDMASTER",
                SavedSetupPreferences.KEY_SHAFT_TYPE to "UNKNOWN_SHAFT",
                SavedSetupPreferences.KEY_GAME_TYPE to "ONE_POCKET",
                SavedSetupPreferences.KEY_USE_UK_BLACKBALL to "not_a_boolean",
                SavedSetupPreferences.KEY_TABLE_SIZE to "10ft_custom",
                SavedSetupPreferences.KEY_DISPLAY_MODE to "EXPERT",
            ),
        )

        assertEquals(
            SavedSetupPreferences(
                hasCompletedSetup = true,
                experienceLevel = ExperienceLevel.INTERMEDIATE,
                shaftType = ShaftType.STANDARD,
                gameType = GameType.EIGHT_BALL,
                useUkBlackball = false,
                tableSizePreset = TableSizePreset.NINE_FOOT,
                displayMode = AppDisplayMode.SIMPLE,
            ),
            decoded,
        )
    }

    @Test
    fun tableSizePresetMapsAllStandardSpecsAndLengths() {
        assertEquals(TableSizePreset.SEVEN_FOOT, TableSizePreset.fromTableSpec(TableSpec.sevenFootPool()))
        assertEquals(TableSizePreset.EIGHT_FOOT, TableSizePreset.fromTableSpec(TableSpec.eightFootPool()))
        assertEquals(TableSizePreset.NINE_FOOT, TableSizePreset.fromTableSpec(TableSpec.nineFootPool()))
        assertEquals(TableSizePreset.SNOOKER_12FT, TableSizePreset.fromTableSpec(TableSpec.fullSizeSnooker()))

        // Custom-named spec still resolves by closest table length
        val customBarBox = TableSpec(lengthMeters = 1.99, widthMeters = 0.995, name = "Bar Table")
        assertEquals(TableSizePreset.SEVEN_FOOT, TableSizePreset.fromTableSpec(customBarBox))
    }

    @Test
    fun derivedHelpersMatchSetupConfiguration() {
        val ukBeginner = SavedSetupPreferences(
            hasCompletedSetup = true,
            experienceLevel = ExperienceLevel.BEGINNER,
            shaftType = ShaftType.STANDARD,
            gameType = GameType.EIGHT_BALL,
            useUkBlackball = true,
            tableSizePreset = TableSizePreset.SEVEN_FOOT,
            displayMode = AppDisplayMode.SIMPLE,
        )
        assertEquals(ExperienceLevel.BEGINNER, ukBeginner.toPlayerProfile().experienceLevel)
        assertTrue(ukBeginner.toBallSetProfile().isUkBlackball)
        assertEquals(GameType.EIGHT_BALL, ukBeginner.toRulesState().gameType)
        assertEquals(EightBallGroup.OPEN, ukBeginner.toRulesState().eightBallGroup)
        assertEquals(PlanningHorizonOption.TWO_BALL, ukBeginner.toPlanningHorizon())

        val proNineBall = SavedSetupPreferences(
            hasCompletedSetup = true,
            experienceLevel = ExperienceLevel.ADVANCED,
            shaftType = ShaftType.LOW_DEFLECTION,
            gameType = GameType.NINE_BALL,
            useUkBlackball = false,
            tableSizePreset = TableSizePreset.NINE_FOOT,
            displayMode = AppDisplayMode.PRO,
        )
        val profile = proNineBall.toPlayerProfile()
        assertEquals(ExperienceLevel.ADVANCED, profile.experienceLevel)
        assertEquals(ShaftType.LOW_DEFLECTION, profile.shaftType)
        assertFalse(proNineBall.toBallSetProfile().isUkBlackball)
        assertEquals(GameType.NINE_BALL, proNineBall.toRulesState().gameType)
        assertEquals(PlanningHorizonOption.THREE_BALL, proNineBall.toPlanningHorizon())
    }
}
