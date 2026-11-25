package com.runout.app.navigation

import androidx.compose.runtime.Composable

/**
 * Handles the system Back gesture or button while [enabled]; while disabled Back keeps its platform behaviour (on
 * Android it leaves the app). Of several enabled handlers the one composed last wins, so a handler inside a screen
 * takes precedence over the one at the root. A no-op where the platform has no Back control.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
