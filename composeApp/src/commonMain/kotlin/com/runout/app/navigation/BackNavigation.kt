package com.runout.app.navigation

/** What the system Back gesture or button does. */
internal sealed interface BackAction {
    /** Close the open bottom sheet. */
    data object CloseSheet : BackAction

    /** Close the navigation drawer. */
    data object CloseDrawer : BackAction

    /** Leave the fullscreen table. */
    data object ExitFullscreen : BackAction

    /** Go to [route]. */
    data class Navigate(val route: ActiveRoute) : BackAction

    /** Nothing in the app is left to go back to: let the system leave the app. */
    data object LeaveApp : BackAction
}

/**
 * Everything [backActionFor] looks at.
 *
 * @property route Where the person is.
 * @property lastPrimaryTab The tab a pushed route was opened from.
 * @property isFullscreenTableShowing True while the fullscreen table covers the Plan tab.
 * @property isDrawerOpen True while the navigation drawer is open.
 * @property isSheetOpen True while a bottom sheet is open.
 */
internal data class BackContext(
    val route: ActiveRoute,
    val lastPrimaryTab: PrimaryTab,
    val isFullscreenTableShowing: Boolean = false,
    val isDrawerOpen: Boolean = false,
    val isSheetOpen: Boolean = false,
)

/**
 * The one place that decides what Back does, from the most local thing to the least:
 *
 * 1. an open bottom sheet closes;
 * 2. an open navigation drawer closes;
 * 3. the fullscreen table is left;
 * 4. a legal document goes back to About & Legal;
 * 5. any other pushed route goes back to the tab it was opened from;
 * 6. a tab other than Home goes to Home;
 * 7. Home leaves the app.
 *
 * The path-inspector card on the table is not listed: it sits in the table canvas, which closes it itself before
 * this decision is reached. The first-run notice is shown instead of all of this and is never left by Back.
 */
internal fun backActionFor(context: BackContext): BackAction {
    if (context.isSheetOpen) return BackAction.CloseSheet
    if (context.isDrawerOpen) return BackAction.CloseDrawer
    if (context.isFullscreenTableShowing) return BackAction.ExitFullscreen
    return when (val route = context.route) {
        is ActiveRoute.MainTab ->
            if (route.tab == PrimaryTab.HOME) {
                BackAction.LeaveApp
            } else {
                BackAction.Navigate(ActiveRoute.MainTab(PrimaryTab.HOME))
            }
        is ActiveRoute.LegalDocumentView -> BackAction.Navigate(ActiveRoute.AboutLegal)
        ActiveRoute.PhotoScan,
        is ActiveRoute.LearnWalkthroughPlayer,
        is ActiveRoute.LearnTutorialPlayer,
        ActiveRoute.Calibration,
        ActiveRoute.SnookerCoach,
        ActiveRoute.BreakSpeedMeter,
        ActiveRoute.ScorekeeperAndClock,
        ActiveRoute.PlayerProfileAndBallSet,
        ActiveRoute.AboutLegal,
        -> BackAction.Navigate(ActiveRoute.MainTab(context.lastPrimaryTab))
    }
}
