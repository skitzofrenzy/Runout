package com.runout.app.ui

import androidx.compose.runtime.Composable

/**
 * Locks or unlocks landscape orientation while a fullscreen table session is active.
 */
@Composable
expect fun LockLandscapeEffect(lockLandscape: Boolean)
