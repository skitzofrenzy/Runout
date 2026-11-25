package com.runout.app.navigation

import com.runout.app.legal.LegalDocument
import com.runout.app.tools.TutorialsAndDrillsCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BackNavigationTest {
    private val pushedRoutes: List<ActiveRoute> = listOf(
        ActiveRoute.PhotoScan,
        ActiveRoute.LearnWalkthroughPlayer(TutorialsAndDrillsCatalog.eightBallWalkthroughs().first()),
        ActiveRoute.LearnTutorialPlayer(TutorialsAndDrillsCatalog.allTutorials().first()),
        ActiveRoute.Calibration,
        ActiveRoute.SnookerCoach,
        ActiveRoute.BreakSpeedMeter,
        ActiveRoute.ScorekeeperAndClock,
        ActiveRoute.PlayerProfileAndBallSet,
        ActiveRoute.AboutLegal,
        ActiveRoute.WhatsNew(fromAbout = false),
    )

    private fun back(
        route: ActiveRoute,
        from: PrimaryTab = PrimaryTab.HOME,
        fullscreen: Boolean = false,
        drawer: Boolean = false,
        sheet: Boolean = false,
    ): BackAction = backActionFor(
        BackContext(
            route = route,
            lastPrimaryTab = from,
            isFullscreenTableShowing = fullscreen,
            isDrawerOpen = drawer,
            isSheetOpen = sheet,
        ),
    )

    @Test
    fun homeLeavesTheApp() {
        assertEquals(BackAction.LeaveApp, back(ActiveRoute.MainTab(PrimaryTab.HOME)))
    }

    @Test
    fun everyOtherTabGoesToHome() {
        for (tab in PrimaryTab.entries.filter { it != PrimaryTab.HOME }) {
            assertEquals(
                BackAction.Navigate(ActiveRoute.MainTab(PrimaryTab.HOME)),
                back(ActiveRoute.MainTab(tab), from = tab),
                "Back from $tab",
            )
        }
    }

    @Test
    fun aPushedRouteGoesBackToTheTabItWasOpenedFrom() {
        for (route in pushedRoutes) for (opener in PrimaryTab.entries) {
            assertEquals(
                BackAction.Navigate(ActiveRoute.MainTab(opener)),
                back(route, from = opener),
                "Back from $route opened from $opener",
            )
        }
    }

    @Test
    fun noPushedRouteLetsBackLeaveTheApp() {
        val everyRoute = pushedRoutes +
            LegalDocument.entries.map { ActiveRoute.LegalDocumentView(it) } +
            ActiveRoute.WhatsNew(fromAbout = true)
        for (route in everyRoute) for (opener in PrimaryTab.entries) {
            assertNotEquals(BackAction.LeaveApp, back(route, from = opener), "Back from $route opened from $opener")
        }
    }

    @Test
    fun whatsNewReturnsToAboutWhenOpenedFromAboutAndToTabFromDrawer() {
        for (opener in PrimaryTab.entries) {
            assertEquals(
                BackAction.Navigate(ActiveRoute.AboutLegal),
                back(ActiveRoute.WhatsNew(fromAbout = true), from = opener),
            )
            assertEquals(
                BackAction.Navigate(ActiveRoute.MainTab(opener)),
                back(ActiveRoute.WhatsNew(fromAbout = false), from = opener),
            )
        }
    }

    @Test
    fun aLegalDocumentGoesBackToAboutAndLegalWhicheverTabOpenedIt() {
        for (document in LegalDocument.entries) for (opener in PrimaryTab.entries) {
            val action = back(ActiveRoute.LegalDocumentView(document), from = opener)
            assertEquals(BackAction.Navigate(ActiveRoute.AboutLegal), action)
        }
        // And About & Legal goes on to the tab the menu was used from.
        assertEquals(
            BackAction.Navigate(ActiveRoute.MainTab(PrimaryTab.LEARN)),
            back(ActiveRoute.AboutLegal, from = PrimaryTab.LEARN),
        )
    }

    @Test
    fun theFullscreenTableIsLeftBeforeAnythingElse() {
        assertEquals(BackAction.ExitFullscreen, back(ActiveRoute.MainTab(PrimaryTab.PLAN), from = PrimaryTab.PLAN, fullscreen = true))
    }

    @Test
    fun anOpenSheetClosesFirstThenTheDrawerThenTheFullscreenTable() {
        val plan = ActiveRoute.MainTab(PrimaryTab.PLAN)
        assertEquals(BackAction.CloseSheet, back(plan, from = PrimaryTab.PLAN, fullscreen = true, drawer = true, sheet = true))
        assertEquals(BackAction.CloseSheet, back(plan, from = PrimaryTab.PLAN, sheet = true))
        assertEquals(BackAction.CloseDrawer, back(plan, from = PrimaryTab.PLAN, fullscreen = true, drawer = true))
        assertEquals(BackAction.CloseDrawer, back(ActiveRoute.MainTab(PrimaryTab.HOME), drawer = true))
        assertEquals(BackAction.CloseDrawer, back(ActiveRoute.PhotoScan, from = PrimaryTab.PLAN, drawer = true))
    }

    @Test
    fun walkingBackFromTheDeepestScreenEndsAtHomeThenLeaves() {
        // Plan tab -> menu -> About & Legal -> a document, then Back all the way out.
        var context = BackContext(ActiveRoute.LegalDocumentView(LegalDocument.entries.first()), PrimaryTab.PLAN)
        val visited = mutableListOf<ActiveRoute>()
        repeat(6) {
            when (val action = backActionFor(context)) {
                is BackAction.Navigate -> {
                    visited += action.route
                    context = context.copy(route = action.route)
                    (action.route as? ActiveRoute.MainTab)?.let { context = context.copy(lastPrimaryTab = it.tab) }
                }
                BackAction.LeaveApp -> {
                    assertEquals(
                        listOf(ActiveRoute.AboutLegal, ActiveRoute.MainTab(PrimaryTab.PLAN), ActiveRoute.MainTab(PrimaryTab.HOME)),
                        visited,
                    )
                    return
                }
                else -> error("Unexpected $action")
            }
        }
        error("Back never left the app: $visited")
    }
}
