package com.runout.app.storage

import androidx.compose.runtime.Composable
import com.runout.app.ui.AppDisplayMode
import com.runout.app.ui.PlanningHorizonOption
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.EightBallGroup
import com.runout.engine.model.ExperienceLevel
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableSpec
import kotlin.math.abs

/**
 * Persisted table dimension preset matching the onboarding and game-setup options.
 */
enum class TableSizePreset(val storageKey: String) {
    SEVEN_FOOT("7ft"),
    EIGHT_FOOT("8ft"),
    NINE_FOOT("9ft"),
    SNOOKER_12FT("12ft_snooker"),
    ;

    fun toTableSpec(): TableSpec = when (this) {
        SEVEN_FOOT -> TableSpec.sevenFootPool()
        EIGHT_FOOT -> TableSpec.eightFootPool()
        NINE_FOOT -> TableSpec.nineFootPool()
        SNOOKER_12FT -> TableSpec.fullSizeSnooker()
    }

    companion object {
        fun fromTableSpec(spec: TableSpec): TableSizePreset =
            entries.firstOrNull { it.toTableSpec().name == spec.name }
                ?: entries.minByOrNull { abs(it.toTableSpec().lengthMeters - spec.lengthMeters) }
                ?: NINE_FOOT

        fun fromStorageKey(key: String?): TableSizePreset =
            entries.firstOrNull { it.storageKey == key } ?: NINE_FOOT
    }
}

/**
 * User selections collected across the 3-step onboarding setup and subsequent setting updates.
 */
data class SavedSetupPreferences(
    val hasCompletedSetup: Boolean = false,
    val experienceLevel: ExperienceLevel = ExperienceLevel.INTERMEDIATE,
    val shaftType: ShaftType = ShaftType.STANDARD,
    val gameType: GameType = GameType.EIGHT_BALL,
    val useUkBlackball: Boolean = false,
    val tableSizePreset: TableSizePreset = TableSizePreset.NINE_FOOT,
    val displayMode: AppDisplayMode = AppDisplayMode.SIMPLE,
) {
    fun toKeyValueMap(): Map<String, String> = mapOf(
        KEY_SETUP_COMPLETED to hasCompletedSetup.toString(),
        KEY_EXPERIENCE_LEVEL to experienceLevel.name,
        KEY_SHAFT_TYPE to shaftType.name,
        KEY_GAME_TYPE to gameType.name,
        KEY_USE_UK_BLACKBALL to useUkBlackball.toString(),
        KEY_TABLE_SIZE to tableSizePreset.storageKey,
        KEY_DISPLAY_MODE to displayMode.name,
    )

    fun toPlayerProfile(): PlayerProfile = PlayerProfile.fromQuestionnaire(
        experience = experienceLevel,
        shaft = shaftType,
        speedControlRating = if (experienceLevel == ExperienceLevel.BEGINNER) 4 else 6,
        spinRating = if (experienceLevel == ExperienceLevel.ADVANCED) 8 else 5,
    )

    fun toBallSetProfile(): BallSetProfile = if (useUkBlackball) {
        BallSetProfile.ukBlackballPool()
    } else {
        BallSetProfile.standardPool()
    }

    fun toRulesState(): GameRulesState = if (gameType == GameType.EIGHT_BALL) {
        GameRulesState(GameType.EIGHT_BALL, EightBallGroup.OPEN)
    } else {
        GameRulesState(gameType)
    }

    fun toPlanningHorizon(): PlanningHorizonOption = if (experienceLevel == ExperienceLevel.BEGINNER) {
        PlanningHorizonOption.TWO_BALL
    } else {
        PlanningHorizonOption.THREE_BALL
    }

    companion object {
        const val KEY_SETUP_COMPLETED: String = "setup_completed"
        const val KEY_EXPERIENCE_LEVEL: String = "experience_level"
        const val KEY_SHAFT_TYPE: String = "shaft_type"
        const val KEY_GAME_TYPE: String = "game_type"
        const val KEY_USE_UK_BLACKBALL: String = "use_uk_blackball"
        const val KEY_TABLE_SIZE: String = "table_size"
        const val KEY_DISPLAY_MODE: String = "display_mode"

        val ALL_KEYS: List<String> = listOf(
            KEY_SETUP_COMPLETED,
            KEY_EXPERIENCE_LEVEL,
            KEY_SHAFT_TYPE,
            KEY_GAME_TYPE,
            KEY_USE_UK_BLACKBALL,
            KEY_TABLE_SIZE,
            KEY_DISPLAY_MODE,
        )

        fun fromKeyValueMap(map: Map<String, String>): SavedSetupPreferences? {
            val completedRaw = map[KEY_SETUP_COMPLETED] ?: return null
            val hasCompleted = completedRaw.toBooleanStrictOrNull() ?: false
            val level = ExperienceLevel.entries.firstOrNull { it.name == map[KEY_EXPERIENCE_LEVEL] }
                ?: ExperienceLevel.INTERMEDIATE
            val shaft = ShaftType.entries.firstOrNull { it.name == map[KEY_SHAFT_TYPE] }
                ?: ShaftType.STANDARD
            val game = GameType.entries.firstOrNull { it.name == map[KEY_GAME_TYPE] }
                ?: GameType.EIGHT_BALL
            val ukBlackball = map[KEY_USE_UK_BLACKBALL]?.toBooleanStrictOrNull() ?: false
            val tablePreset = TableSizePreset.fromStorageKey(map[KEY_TABLE_SIZE])
            val mode = AppDisplayMode.entries.firstOrNull { it.name == map[KEY_DISPLAY_MODE] }
                ?: AppDisplayMode.SIMPLE
            return SavedSetupPreferences(
                hasCompletedSetup = hasCompleted,
                experienceLevel = level,
                shaftType = shaft,
                gameType = game,
                useUkBlackball = ukBlackball,
                tableSizePreset = tablePreset,
                displayMode = mode,
            )
        }
    }
}

/**
 * Persistent storage contract for saving and restoring the user's onboarding setup preferences.
 */
interface SetupPreferencesStore {
    fun load(): SavedSetupPreferences?
    fun save(preferences: SavedSetupPreferences)
}

@Composable
expect fun rememberSetupPreferencesStore(): SetupPreferencesStore
