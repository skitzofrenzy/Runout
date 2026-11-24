package com.runout.app.navigation

import com.runout.app.legal.LegalDocument
import com.runout.app.tools.EightBallWalkthrough
import com.runout.app.tools.InteractiveTutorial
import com.runout.app.ui.RunoutIcon

/** The four tabs of the bottom bar. */
internal enum class PrimaryTab(val label: String, val icon: RunoutIcon) {
    HOME("Home", RunoutIcon.HOME),
    PLAN("Plan", RunoutIcon.PLAN),
    LEARN("Learn", RunoutIcon.LEARN),
    RULES("Rules", RunoutIcon.RULES),
}

/** Where the person is: one of the tabs, or a pushed full-screen route opened from one of them. */
internal sealed interface ActiveRoute {
    data class MainTab(val tab: PrimaryTab) : ActiveRoute
    data object PhotoScan : ActiveRoute
    data class LearnWalkthroughPlayer(val walkthrough: EightBallWalkthrough) : ActiveRoute
    data class LearnTutorialPlayer(val tutorial: InteractiveTutorial) : ActiveRoute
    data object Calibration : ActiveRoute
    data object SnookerCoach : ActiveRoute
    data object BreakSpeedMeter : ActiveRoute
    data object ScorekeeperAndClock : ActiveRoute
    data object PlayerProfileAndBallSet : ActiveRoute
    data object AboutLegal : ActiveRoute
    data class LegalDocumentView(val document: LegalDocument) : ActiveRoute
}
