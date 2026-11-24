package com.runout.app.storage

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val SETUP_PREFS_NAME: String = "runout_setup_prefs"

@Composable
actual fun rememberSetupPreferencesStore(): SetupPreferencesStore {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        val prefs = appContext.getSharedPreferences(SETUP_PREFS_NAME, Context.MODE_PRIVATE)
        object : SetupPreferencesStore {
            override fun load(): SavedSetupPreferences? {
                if (!prefs.contains(SavedSetupPreferences.KEY_SETUP_COMPLETED)) return null
                val map = buildMap {
                    for (key in SavedSetupPreferences.ALL_KEYS) {
                        prefs.getString(key, null)?.let { put(key, it) }
                    }
                }
                return SavedSetupPreferences.fromKeyValueMap(map)
            }

            override fun save(preferences: SavedSetupPreferences) {
                val editor = prefs.edit()
                for ((key, value) in preferences.toKeyValueMap()) {
                    editor.putString(key, value)
                }
                editor.apply()
            }
        }
    }
}
