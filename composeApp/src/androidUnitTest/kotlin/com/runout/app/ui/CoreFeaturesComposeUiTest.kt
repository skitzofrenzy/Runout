package com.runout.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.runout.app.App
import com.runout.app.legal.AboutLegalScreen
import com.runout.app.legal.LegalCopy
import com.runout.app.version.AppVersion
import com.runout.app.version.WhatsNewScreen
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.planner.MultiShotPlanner
import com.runout.engine.planner.RunoutPlan
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class CoreFeaturesComposeUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun samplePlannedLayout(): Pair<TableLayout, RunoutPlan> {
        val scenario = SampleLayouts.all.first()
        val plan = MultiShotPlanner.planRunout(
            layout = scenario.layout,
            rulesState = scenario.rulesState,
            playerProfile = PlayerProfile(),
            horizonShots = 2,
        ).first()
        return scenario.layout to plan
    }

    @Test
    fun pathPhysicsButtonOpensCalloutStepsThroughWaypointsAndClosesWithoutCrashing() {
        val (layout, plan) = samplePlannedLayout()
        assertTrue(
            "Sample runout plan must have at least one step to exercise the path physics callout",
            plan.steps.isNotEmpty(),
        )

        composeRule.setContent {
            MaterialTheme(colorScheme = RunoutDarkColorScheme) {
                Box(modifier = Modifier.width(390.dp).height(240.dp)) {
                    PoolTableCanvas(
                        layout = layout,
                        selectedShot = null,
                        activeRunoutPlan = plan,
                        focusedStepIndex = 0,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("ⓘ Path physics").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Close").assertIsDisplayed()
        composeRule.onNodeWithText("Next ›").assertIsDisplayed()
        composeRule.onNodeWithText("‹ Back").assertIsDisplayed()

        composeRule.onNodeWithText("Next ›").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("‹ Back").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Close").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("ⓘ Path physics").assertIsDisplayed()
    }

    @Test
    fun pathPhysicsCalloutFitsCompactAndTightTableCanvasesWithoutCrashing() {
        val (layout, plan) = samplePlannedLayout()

        composeRule.setContent {
            MaterialTheme(colorScheme = RunoutDarkColorScheme) {
                // Tight height forces placeCallout into candidate shrinking / docking branches.
                Box(modifier = Modifier.width(300.dp).height(155.dp)) {
                    PoolTableCanvas(
                        layout = layout,
                        selectedShot = null,
                        activeRunoutPlan = plan,
                        focusedStepIndex = 0,
                        displayMode = AppDisplayMode.PRO,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("ⓘ Path physics").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Close").assertIsDisplayed()
        composeRule.onNodeWithText("Next ›").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Close").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun tableZoomControlsAndFullscreenToggleRespondCleanly() {
        val (layout, plan) = samplePlannedLayout()
        var fullscreenToggled = false

        composeRule.setContent {
            MaterialTheme(colorScheme = RunoutDarkColorScheme) {
                Box(modifier = Modifier.width(390.dp).height(240.dp)) {
                    PoolTableCanvas(
                        layout = layout,
                        selectedShot = null,
                        activeRunoutPlan = plan,
                        focusedStepIndex = 0,
                        onToggleFullscreen = { fullscreenToggled = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        composeRule.onNodeWithText("+").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1.5x").assertIsDisplayed()

        composeRule.onNodeWithText("−").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("1.0x").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Enter fullscreen").performClick()
        composeRule.waitForIdle()
        assertTrue(fullscreenToggled)
    }

    @Test
    fun photoReviewRemoveClearAllAndUndoFlowWorksEndToEnd() {
        composeRule.setContent {
            MaterialTheme(colorScheme = RunoutDarkColorScheme) {
                PhotoCaptureAndConfirmScreen(
                    ballSet = BallSetProfile.standardPool(),
                    lastPlayedGameType = GameType.NINE_BALL,
                    onConfirmScannedLayout = { _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("No table nearby? Try a built-in sample photo")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("9-ft 9-Ball (Foot-End Angled)")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        // Step 2: Review Table -> Clear Balls -> Undo within the 5-second window restores balls cleanly
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Clear Balls")
            .performScrollTo()
            .performClick()
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertTrue(
            "Undo banner should dismiss immediately after restoring balls",
            composeRule.onAllNodesWithText("Undo").fetchSemanticsNodes().isEmpty(),
        )

        // Advance to Step 3: Confirm Game
        composeRule.onNodeWithText("Next: Confirm Game")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Suggested Game Type & Confirmation").assertIsDisplayed()
    }

    @Test
    fun photoReviewUndoBannerAutoDismissesAfterFiveSeconds() {
        composeRule.setContent {
            MaterialTheme(colorScheme = RunoutDarkColorScheme) {
                PhotoCaptureAndConfirmScreen(
                    ballSet = BallSetProfile.standardPool(),
                    lastPlayedGameType = GameType.NINE_BALL,
                    onConfirmScannedLayout = { _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("No table nearby? Try a built-in sample photo")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("9-ft 9-Ball (Foot-End Angled)")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Clear Balls")
            .performScrollTo()
            .performClick()
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Undo").assertIsDisplayed()

        // After the 5-second undo window elapses, the banner auto-dismisses.
        composeRule.mainClock.advanceTimeBy(BALL_REMOVAL_UNDO_TIMEOUT_MS + 400L)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertTrue(
            "Undo banner should auto-dismiss after the 5-second timeout",
            composeRule.onAllNodesWithText("Undo").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun whatsNewAndAboutLegalScreensRenderInstalledVersion() {
        var openedWhatsNew = false
        composeRule.setContent {
            MaterialTheme(colorScheme = RunoutDarkColorScheme) {
                AboutLegalScreen(
                    onOpenDocument = {},
                    onOpenWhatsNew = { openedWhatsNew = true },
                )
            }
        }

        composeRule.onNodeWithText(
            "Version ${AppVersion.versionName} (Build ${AppVersion.versionCode})",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("What's New").performClick()
        assertTrue(openedWhatsNew)
    }

    @Test
    fun whatsNewScreenRendersVersionHeaderAndReleaseEntries() {
        composeRule.setContent {
            MaterialTheme(colorScheme = RunoutDarkColorScheme) {
                WhatsNewScreen()
            }
        }

        composeRule.onNodeWithText("Runout v${AppVersion.versionName}").assertIsDisplayed()
        composeRule.onNodeWithText("Build ${AppVersion.versionCode}").assertIsDisplayed()
    }

    @Test
    fun fullAppSmokeFlowNavigatesCoreTabsAndOpensPathPhysicsWithoutCrashing() {
        composeRule.setContent {
            App()
        }
        composeRule.waitForIdle()

        // Accept first-run notice when shown on a clean install.
        if (composeRule.onAllNodesWithText(LegalCopy.ACK_AGREE_BUTTON).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText(LegalCopy.ACK_AGREE_BUTTON)
                .performScrollTo()
                .performClick()
            composeRule.waitForIdle()
        }

        // Skip onboarding wizard when shown on a clean install.
        if (composeRule.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Skip").performClick()
            composeRule.waitForIdle()
        }

        // Open Plan tab, wait for async runout planning, trigger Path physics callout, step, close.
        composeRule.onAllNodesWithText("Plan").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            composeRule.onAllNodesWithText("ⓘ Path physics").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("ⓘ Path physics").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Close").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Switch across Learn and Rules tabs to verify they render cleanly.
        composeRule.onAllNodesWithText("Learn").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Rules").onFirst().performClick()
        composeRule.waitForIdle()

        // Return to Plan and open Shot Options sheet to verify modal sheets render cleanly.
        composeRule.onAllNodesWithText("Plan").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Shot Options").performScrollTo().performClick()
        composeRule.waitForIdle()
    }
}
