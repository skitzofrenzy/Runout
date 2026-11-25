package com.runout.app.storage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSUserDefaults

private const val SETUP_KEY_PREFIX: String = "runout_setup_"

@Composable
actual fun rememberSetupPreferencesStore(): SetupPreferencesStore = remember {
    val defaults = NSUserDefaults.standardUserDefaults
    object : SetupPreferencesStore {
        override fun load(): SavedSetupPreferences? {
            if (defaults.objectForKey("$SETUP_KEY_PREFIX${SavedSetupPreferences.KEY_SETUP_COMPLETED}") == null) {
                return null
            }
            val map = buildMap {
                for (key in SavedSetupPreferences.ALL_KEYS) {
                    defaults.stringForKey("$SETUP_KEY_PREFIX$key")?.let { put(key, it) }
                }
            }
            return SavedSetupPreferences.fromKeyValueMap(map)
        }

        override fun save(preferences: SavedSetupPreferences) {
            for ((key, value) in preferences.toKeyValueMap()) {
                defaults.setObject(value, forKey = "$SETUP_KEY_PREFIX$key")
            }
        }

        override fun loadAcceptedTermsVersion(): String? {
            val accepted = defaults.stringForKey("$SETUP_KEY_PREFIX${TermsAcceptanceKeys.KEY_TERMS_ACCEPTED}")
            if (accepted != "true") return null
            return defaults.stringForKey("$SETUP_KEY_PREFIX${TermsAcceptanceKeys.KEY_TERMS_VERSION}")
        }

        override fun saveAcceptedTermsVersion(version: String) {
            defaults.setObject("true", forKey = "$SETUP_KEY_PREFIX${TermsAcceptanceKeys.KEY_TERMS_ACCEPTED}")
            defaults.setObject(version, forKey = "$SETUP_KEY_PREFIX${TermsAcceptanceKeys.KEY_TERMS_VERSION}")
        }
    }
}
