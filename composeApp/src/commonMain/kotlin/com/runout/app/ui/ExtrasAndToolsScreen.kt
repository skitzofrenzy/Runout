package com.runout.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.runout.app.tools.BreakAudioPreset
import com.runout.app.tools.InteractiveTutorial
import com.runout.app.tools.PlatformBreakAudioController
import com.runout.app.tools.PoolRaceScoreState
import com.runout.app.tools.PracticeDrillSpec
import com.runout.app.tools.PracticeLogEntry
import com.runout.app.tools.ShotClockLiveState
import com.runout.app.tools.TutorialsAndDrillsCatalog
import com.runout.engine.math.Vec2
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.physics.TipOffset
import com.runout.engine.snooker.SnookerBallType
import com.runout.engine.snooker.SnookerBreakStep
import com.runout.engine.snooker.SnookerSafetyRecommendation
import com.runout.engine.snooker.SnookerScoreState
import com.runout.engine.snooker.SnookerSpots
import com.runout.engine.snooker.SnookerStrategyPlanner
import com.runout.engine.snooker.SnookerTacticalMode
import com.runout.engine.tools.BreakAudioEnvelopeResult
import com.runout.engine.tools.BreakSpeedEstimate
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

private const val MPS_TO_MPH: Double = 2.23694
private const val MPS_TO_KMH: Double = 3.6
private const val SECONDS_TO_MS: Double = 1000.0
private const val METERS_TO_CM: Double = 100.0

private val ToolsSuccessGreen = Color(0xFF4ADE80)
private val ToolsWarningAmber = Color(0xFFFBBF24)
private val ToolsDangerCoral = Color(0xFFF87171)
private val ToolsAccentCyan = Color(0xFF38BDF8)

private val SNOOKER_REDS_PRESETS = listOf(15, 6, 1, 0)
private val RACE_TARGET_OPTIONS = listOf(5, 7, 9)
private val POOL_GAME_LABELS = listOf("8-Ball", "9-Ball", "10-Ball")
private val ENDGAME_COLOURS = listOf(
    SnookerBallType.YELLOW,
    SnookerBallType.GREEN,
    SnookerBallType.BROWN,
    SnookerBallType.BLUE,
    SnookerBallType.PINK,
    SnookerBallType.BLACK,
)

private enum class ToolsSectionFilter(val label: String) {
    ALL("All"),
    SNOOKER_COACH("Snooker Coach"),
    EIGHT_BALL_WALKTHROUGHS("8-Ball Walkthroughs"),
    TUTORIALS_AND_DRILLS("Tutorials & Drills"),
    METER_AND_SCOREKEEPER("Break Meter & Match Tools"),
}

enum class ToolFocusDestination {
    ALL,
    SNOOKER_COACH,
    BREAK_METER,
    SCOREKEEPER_AND_CLOCK,
}

/**
 * Extras & Practice Coach screen hosting the Snooker Break & Safety Coach,
 * 8-Ball Walkthroughs, Interactive Tutorials & Scored Drill Library with Practice Log,
 * Acoustic Break-Speed Meter, Pool Race & Snooker Scorekeeper, and Shot Clock.
 */
@Composable
fun ExtrasAndToolsScreen(
    playerProfile: PlayerProfile,
    onLoadLayoutIntoPlanner: (TableLayout, GameRulesState) -> Unit,
    modifier: Modifier = Modifier,
    focusedDestination: ToolFocusDestination = ToolFocusDestination.ALL,
) {
    var activeSection by remember(focusedDestination) {
        mutableStateOf(
            when (focusedDestination) {
                ToolFocusDestination.ALL -> ToolsSectionFilter.ALL
                ToolFocusDestination.SNOOKER_COACH -> ToolsSectionFilter.SNOOKER_COACH
                ToolFocusDestination.BREAK_METER,
                ToolFocusDestination.SCOREKEEPER_AND_CLOCK,
                -> ToolsSectionFilter.METER_AND_SCOREKEEPER
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Card),
        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
    ) {
        if (focusedDestination == ToolFocusDestination.ALL) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Tools & Practice Coach",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "Snooker break & baulk-safety advisor, 8-Ball walkthroughs, interactive tutorials, scored drills with practice log, acoustic break-speed meter, match scorekeeper, and shot clock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolsSectionFilter.entries.forEach { section ->
                    val isSelected = activeSection == section
                    FilterChip(
                        selected = isSelected,
                        onClick = { activeSection = section },
                        label = {
                            Text(
                                text = section.label,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }

        if (activeSection == ToolsSectionFilter.ALL || activeSection == ToolsSectionFilter.SNOOKER_COACH) {
            SnookerCoachCard(
                playerProfile = playerProfile,
                onLoadLayoutIntoPlanner = onLoadLayoutIntoPlanner,
            )
        }

        if (activeSection == ToolsSectionFilter.ALL || activeSection == ToolsSectionFilter.EIGHT_BALL_WALKTHROUGHS) {
            EightBallWalkthroughsSectionCard(
                onLoadLayoutIntoPlanner = onLoadLayoutIntoPlanner,
            )
        }

        if (activeSection == ToolsSectionFilter.ALL || activeSection == ToolsSectionFilter.TUTORIALS_AND_DRILLS) {
            TutorialsAndDrillsCard(
                onLoadLayoutIntoPlanner = onLoadLayoutIntoPlanner,
            )
        }

        if (activeSection == ToolsSectionFilter.ALL || activeSection == ToolsSectionFilter.METER_AND_SCOREKEEPER) {
            AcousticBreakAndMatchToolsCard(focusedDestination = focusedDestination)
        }
    }
}

@Composable
private fun EightBallWalkthroughsSectionCard(
    onLoadLayoutIntoPlanner: (TableLayout, GameRulesState) -> Unit,
) {
    val walkthroughs = remember { TutorialsAndDrillsCatalog.eightBallWalkthroughs() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "8-Ball Walkthroughs (${walkthroughs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Step-by-step 8-Ball rule & strategy examples covering open tables, key-ball pattern play, cluster breakouts, legal safeties, ball-in-hand placement, and avoiding tangent-line scratches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            walkthroughs.forEach { walkthrough ->
                EightBallWalkthroughCard(
                    walkthrough = walkthrough,
                    loadButtonLabel = "Load Walkthrough on Table",
                    onLoadClick = {
                        onLoadLayoutIntoPlanner(walkthrough.layout, walkthrough.rulesState)
                    },
                )
            }
        }
    }
}

@Composable
private fun SnookerCoachCard(
    playerProfile: PlayerProfile,
    onLoadLayoutIntoPlanner: (TableLayout, GameRulesState) -> Unit,
) {
    val snookerLayout = remember { TutorialsAndDrillsCatalog.sampleSnookerBreakLayout() }
    var redsRemaining by remember { mutableIntStateOf(15) }
    var onColourAfterRed by remember { mutableStateOf(false) }
    var lowestEndgameColourValue by remember { mutableIntStateOf(2) }
    var playerScore by remember { mutableIntStateOf(0) }
    var opponentScore by remember { mutableIntStateOf(0) }

    val snookerScoreState = remember(
        playerScore,
        opponentScore,
        redsRemaining,
        onColourAfterRed,
        lowestEndgameColourValue,
    ) {
        SnookerScoreState(
            playerScore = playerScore,
            opponentScore = opponentScore,
            currentBreakPoints = 0,
            redsRemaining = redsRemaining,
            onColourAfterRed = onColourAfterRed,
            lowestEndgameColourValue = lowestEndgameColourValue,
        )
    }

    val advice = remember(snookerLayout, snookerScoreState, playerProfile) {
        SnookerStrategyPlanner.evaluateSnookerPosition(
            layout = snookerLayout,
            scoreState = snookerScoreState,
            playerProfile = playerProfile,
        )
    }

    val modeAccent = when (advice.recommendedMode) {
        SnookerTacticalMode.ATTACK_BREAK_BUILD -> ToolsSuccessGreen
        SnookerTacticalMode.CONTAINMENT_SAFETY -> ToolsAccentCyan
        SnookerTacticalMode.PLAY_FOR_SNOOKERS -> ToolsWarningAmber
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Snooker Break & Safety Coach",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "WPBSA red–colour alternation, colour re-spotting, and baulk-return containment evaluation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                ToolsStatusBadge(
                    text = advice.recommendedMode.name,
                    containerColor = modeAccent.copy(alpha = 0.16f),
                    contentColor = modeAccent,
                    borderColor = modeAccent.copy(alpha = 0.5f),
                )
            }

            // Reds remaining selector
            Text(
                text = "Reds Remaining & Target State",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SNOOKER_REDS_PRESETS.forEach { count ->
                    val isSelected = redsRemaining == count
                    FilterChip(
                        selected = isSelected,
                        onClick = { redsRemaining = count },
                        label = {
                            Text(
                                text = if (count == 0) "0 Reds (Colours)" else "$count Reds",
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                    )
                }
                FilterChip(
                    selected = onColourAfterRed,
                    onClick = { onColourAfterRed = !onColourAfterRed },
                    label = {
                        Text(
                            text = if (onColourAfterRed) "On Colour After Red" else "On Ball in Sequence",
                            fontWeight = if (onColourAfterRed) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ToolsAccentCyan.copy(alpha = 0.2f),
                        selectedLabelColor = ToolsAccentCyan,
                    ),
                )
            }

            if (redsRemaining == 0 && !onColourAfterRed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ENDGAME_COLOURS.forEach { colour ->
                        val isSelected = lowestEndgameColourValue == colour.pointValue
                        FilterChip(
                            selected = isSelected,
                            onClick = { lowestEndgameColourValue = colour.pointValue },
                            leadingIcon = {
                                SnookerBallDot(ballType = colour, sizeDp = 10)
                            },
                            label = { Text("${snookerBallName(colour)} (${colour.pointValue})") },
                        )
                    }
                }
            }

            // Frame scores & Snookers preset
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScoreAdjustBox(
                    label = "Player Score",
                    score = playerScore,
                    onAdjust = { delta -> playerScore = (playerScore + delta).coerceAtLeast(0) },
                    modifier = Modifier.weight(1f),
                )
                ScoreAdjustBox(
                    label = "Opponent Score",
                    score = opponentScore,
                    onAdjust = { delta -> opponentScore = (opponentScore + delta).coerceAtLeast(0) },
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        redsRemaining = 0
                        onColourAfterRed = false
                        lowestEndgameColourValue = 2
                        playerScore = 20
                        opponentScore = 55
                    },
                ) {
                    Text("Trailing by 35 with 27 on table (Needs Snookers)")
                }
                OutlinedButton(
                    onClick = {
                        redsRemaining = 15
                        onColourAfterRed = false
                        lowestEndgameColourValue = 2
                        playerScore = 0
                        opponentScore = 0
                    },
                ) {
                    Text("Reset 147 Frame")
                }
            }

            // Frame math summary
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Points remaining on table: ${advice.scoreState.pointsRemainingOnTable}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Snookers required: ${advice.scoreState.snookersRequiredCount}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (advice.scoreState.snookersRequiredCount > 0) ToolsWarningAmber else ToolsSuccessGreen,
                        )
                    }
                    Text(
                        text = "Score diff: ${formatSignedInt(advice.scoreState.scoreDifferential)}  •  Expected break: ${formatFixed(advice.expectedBreakPoints, 1)} pts" +
                            if (advice.scoreState.isFrameBallAvailable) "  •  Frame ball available" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (advice.tacticalSummaryCodes.isNotEmpty()) {
                        Text(
                            text = "Signals: ${advice.tacticalSummaryCodes.joinToString(" • ")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = ToolsAccentCyan,
                        )
                    }
                }
            }

            // Break sequence steps
            Text(
                text = "Planned Break Sequence (${advice.breakSequence.size} steps)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (advice.breakSequence.isEmpty()) {
                Text(
                    text = "No high-percentage pot sequence found for the current target state; prioritize containment safety.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                advice.breakSequence.forEach { step ->
                    SnookerBreakStepRow(step = step)
                }
            }

            // Best baulk-return safety option
            advice.bestSafety?.let { safety ->
                SnookerSafetyCard(safety = safety)
            }

            Button(
                onClick = {
                    onLoadLayoutIntoPlanner(snookerLayout, GameRulesState(GameType.EIGHT_BALL))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Load Snooker Table in Planner", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ScoreAdjustBox(
    label: String,
    score: Int,
    onAdjust: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$score",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(-10, -1, 1, 7).forEach { delta ->
                    OutlinedButton(
                        onClick = { onAdjust(delta) },
                    ) {
                        Text(if (delta > 0) "+$delta" else "$delta")
                    }
                }
            }
        }
    }
}

@Composable
private fun SnookerBreakStepRow(step: SnookerBreakStep) {
    val ballLabel = snookerBallName(step.ballType)
    val ptLabel = if (step.pointValue == 1) "1 pt" else "${step.pointValue} pts"
    val makePct = (step.makeProbability.coerceIn(0.0, 1.0) * 100.0).roundToInt()
    val respot = step.respottedToSpotMeters

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.padding(top = 3.dp)) {
                SnookerBallDot(ballType = step.ballType, sizeDp = 14)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "Step ${step.stepNumber}: $ballLabel ($ptLabel) -> ${step.pocketId.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "P(make): $makePct%  •  Speed: ${step.recommendedSpeedScale}/10  •  Tip: ${formatTipSummary(step.recommendedTip)}  •  Cue stop: ${formatVec2Meters(step.cueBallStopMeters)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (respot != null) {
                    Text(
                        text = "$ballLabel (re-spotted to WPBSA spot ${formatVec2Meters(respot)})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = ToolsAccentCyan,
                    )
                }
            }
        }
    }
}

@Composable
private fun SnookerSafetyCard(safety: SnookerSafetyRecommendation) {
    val targetType = SnookerSpots.ballTypeForNumber(safety.targetBallNumber)
    val targetName = if (targetType != null) {
        "${snookerBallName(targetType)} (#${safety.targetBallNumber})"
    } else {
        "Ball #${safety.targetBallNumber}"
    }
    val inBaulk = safety.cueBallFinalMeters.x <= SnookerSpots.BAULK_LINE_X_METERS + 1e-6

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = ToolsAccentCyan.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, ToolsAccentCyan.copy(alpha = 0.4f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Best Baulk-Return Safety: Clip $targetName",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ToolsAccentCyan,
                )
                ToolsStatusBadge(
                    text = if (inBaulk) "In Baulk" else "Containment",
                    containerColor = ToolsAccentCyan.copy(alpha = 0.16f),
                    contentColor = ToolsAccentCyan,
                )
            }
            Text(
                text = "Cue stop: ${formatVec2Meters(safety.cueBallFinalMeters)}  •  Distance to baulk line: ${formatFixed(safety.distanceToBaulkLineMeters, 2)} m (${(safety.distanceToBaulkLineMeters * METERS_TO_CM).roundToInt()} cm)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Opponent pot probability: ${formatFixed(safety.opponentEstimatedPotProbability * 100.0, 1)}%  •  Safety score: ${formatFixed(safety.safetyQualityScore * 100.0, 0)}/100  •  Speed: ${safety.recommendedSpeedScale}/10",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TutorialsAndDrillsCard(
    onLoadLayoutIntoPlanner: (TableLayout, GameRulesState) -> Unit,
) {
    val tutorials = remember { TutorialsAndDrillsCatalog.allTutorials() }
    val drills = remember { TutorialsAndDrillsCatalog.allDrills() }
    var practiceLogs by remember { mutableStateOf(TutorialsAndDrillsCatalog.defaultPracticeLogs()) }

    var selectedDrillIndex by remember { mutableIntStateOf(0) }
    val safeDrillIndex = selectedDrillIndex.coerceIn(0, (drills.size - 1).coerceAtLeast(0))
    val activeDrill = drills.getOrNull(safeDrillIndex)
    var logAttempts by remember(activeDrill?.id) {
        mutableIntStateOf(activeDrill?.attemptsPerSet ?: 10)
    }
    var logSuccesses by remember(activeDrill?.id) {
        mutableIntStateOf((activeDrill?.targetSuccessCount ?: 7).coerceAtMost(activeDrill?.attemptsPerSet ?: 10))
    }
    var logNotes by remember { mutableStateOf("") }

    val totalSuccesses = practiceLogs.sumOf { it.successes }
    val totalAttempts = practiceLogs.sumOf { it.attempts }
    val completionPct = if (totalAttempts > 0) {
        (totalSuccesses.toDouble() / totalAttempts.toDouble()) * 100.0
    } else {
        0.0
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Interactive Tutorials & Scored Drill Library",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${tutorials.size} guided tutorials and ${drills.size} scored drills that load directly onto the interactive Table & Planner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "Interactive Tutorials (${tutorials.size})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            tutorials.forEach { tutorial ->
                TutorialItemCard(
                    tutorial = tutorial,
                    onTryOnPlanner = {
                        onLoadLayoutIntoPlanner(tutorial.practiceLayout, tutorial.practiceRulesState)
                    },
                )
            }

            ToolsDivider()

            Text(
                text = "Scored Practice Drills (${drills.size})",
                style = MaterialTheme.typography.labelLarge,
                color = ToolsSuccessGreen,
            )

            drills.forEach { drill ->
                DrillItemCard(
                    drill = drill,
                    onLoadDrill = {
                        onLoadLayoutIntoPlanner(drill.drillLayout, drill.drillRulesState)
                    },
                )
            }

            ToolsDivider()

            // Practice Log
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Practice Log (${practiceLogs.size} sessions)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ToolsStatusBadge(
                        text = "Overall: ${formatFixed(completionPct, 1)}% ($totalSuccesses/$totalAttempts)",
                        containerColor = ToolsSuccessGreen.copy(alpha = 0.16f),
                        contentColor = ToolsSuccessGreen,
                        borderColor = ToolsSuccessGreen.copy(alpha = 0.45f),
                    )
                }

                // Completion progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    val fraction = (completionPct / 100.0).toFloat().coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(ToolsSuccessGreen),
                    )
                }

                // Record new session controls
                if (activeDrill != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Log New Drill Session",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                drills.forEachIndexed { idx, drill ->
                                    val isSelected = idx == safeDrillIndex
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedDrillIndex = idx },
                                        label = {
                                            Text(
                                                text = drill.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CounterStepper(
                                    label = "Successes",
                                    value = logSuccesses,
                                    onDecrement = { logSuccesses = (logSuccesses - 1).coerceAtLeast(0) },
                                    onIncrement = { logSuccesses = (logSuccesses + 1).coerceAtMost(logAttempts) },
                                    modifier = Modifier.weight(1f),
                                )
                                CounterStepper(
                                    label = "Attempts",
                                    value = logAttempts,
                                    onDecrement = {
                                        val next = (logAttempts - 1).coerceAtLeast(1)
                                        logAttempts = next
                                        if (logSuccesses > next) logSuccesses = next
                                    },
                                    onIncrement = { logAttempts = (logAttempts + 1).coerceAtMost(50) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            OutlinedTextField(
                                value = logNotes,
                                onValueChange = { logNotes = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Session notes (optional)") },
                                placeholder = { Text("Cue speed felt smooth, slight right drift...") },
                            )
                            Button(
                                onClick = {
                                    val newEntry = PracticeLogEntry(
                                        id = "log-${practiceLogs.size + 1}",
                                        drillId = activeDrill.id,
                                        drillTitle = activeDrill.title,
                                        dateIso = "2026-11-17",
                                        successes = logSuccesses,
                                        attempts = logAttempts,
                                        notes = logNotes.trim(),
                                    )
                                    practiceLogs = listOf(newEntry) + practiceLogs
                                    logNotes = ""
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ToolsSuccessGreen,
                                    contentColor = Color(0xFF052E16),
                                ),
                            ) {
                                Text("Record Drill Session", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                practiceLogs.forEach { entry ->
                    val pct = formatFixed(entry.successRateFraction * 100.0, 0)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = entry.drillTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${entry.successes}/${entry.attempts} ($pct%)",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ToolsSuccessGreen,
                                )
                            }
                            Text(
                                text = entry.dateIso + if (entry.notes.isNotBlank()) "  •  ${entry.notes}" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialItemCard(
    tutorial: InteractiveTutorial,
    onTryOnPlanner: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolsStatusBadge(
                    text = tutorial.category,
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    contentColor = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = tutorial.practiceRulesState.gameType.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = tutorial.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = tutorial.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tutorial.keyTakeaways.forEach { takeaway ->
                Text(
                    text = "• $takeaway",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            PhasedInteractiveTutorialViewer(
                layout = tutorial.practiceLayout,
                stages = tutorial.visualStages,
                targetBallNumber = tutorial.primaryTargetBallNumber,
                pocketId = tutorial.primaryPocketId,
                recommendedTipOffset = tutorial.recommendedTipOffset,
                recommendedSpeedScale = tutorial.recommendedSpeedScale,
            )
            OutlinedButton(
                onClick = onTryOnPlanner,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Try on Table Planner", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun DrillItemCard(
    drill: PracticeDrillSpec,
    onLoadDrill: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolsStatusBadge(
                    text = drill.targetSkill,
                    containerColor = ToolsSuccessGreen.copy(alpha = 0.16f),
                    contentColor = ToolsSuccessGreen,
                )
                Text(
                    text = "Target: ${drill.targetSuccessCount}/${drill.attemptsPerSet}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Text(
                text = drill.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = drill.instructions,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onLoadDrill,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Load Drill on Table", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun AcousticBreakAndMatchToolsCard(
    focusedDestination: ToolFocusDestination = ToolFocusDestination.ALL,
) {
    val audioController = remember { PlatformBreakAudioController() }
    val breakPresets = remember { TutorialsAndDrillsCatalog.sampleBreakAudioPresets() }
    var selectedPresetIndex by remember { mutableIntStateOf(0) }
    val safePresetIndex = selectedPresetIndex.coerceIn(0, (breakPresets.size - 1).coerceAtLeast(0))
    val selectedPreset = breakPresets.getOrNull(safePresetIndex)
    val capturedPreset = remember(selectedPreset?.id, audioController) {
        selectedPreset?.let { audioController.captureBreakPreset(it.id) }
    }
    val breakAnalysis = remember(capturedPreset) {
        capturedPreset?.let { TutorialsAndDrillsCatalog.analyzeBreakPreset(it) }
    }

    var raceState by remember { mutableStateOf(PoolRaceScoreState()) }
    var snookerP1Score by remember { mutableIntStateOf(0) }
    var snookerP2Score by remember { mutableIntStateOf(0) }
    var snookerActivePlayer by remember { mutableIntStateOf(1) }
    var snookerCurrentBreak by remember { mutableIntStateOf(0) }
    var snookerHighBreak by remember { mutableIntStateOf(0) }
    var shotClock by remember { mutableStateOf(ShotClockLiveState()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (focusedDestination == ToolFocusDestination.ALL || focusedDestination == ToolFocusDestination.BREAK_METER) {
                // 1. Acoustic Break-Speed Meter
                BreakSpeedMeterSection(
                    audioBackendName = audioController.audioBackendName,
                    inMemoryOnly = audioController.processesTransientPcmInMemoryOnly,
                    presets = breakPresets,
                    selectedPresetIndex = safePresetIndex,
                    capturedPreset = capturedPreset,
                    analysis = breakAnalysis,
                    onSelectPreset = { selectedPresetIndex = it },
                )
            }

            if (focusedDestination == ToolFocusDestination.ALL) {
                ToolsDivider()
            }

            if (focusedDestination == ToolFocusDestination.ALL || focusedDestination == ToolFocusDestination.SCOREKEEPER_AND_CLOCK) {
                // 2. Pool Race & Snooker Scorekeeper
                ScorekeeperSection(
                    raceState = raceState,
                    onUpdateRace = { raceState = it },
                    snookerP1Score = snookerP1Score,
                    snookerP2Score = snookerP2Score,
                    snookerActivePlayer = snookerActivePlayer,
                    snookerCurrentBreak = snookerCurrentBreak,
                    snookerHighBreak = snookerHighBreak,
                    onSelectStriker = { striker ->
                        if (snookerActivePlayer != striker) {
                            snookerActivePlayer = striker
                            snookerCurrentBreak = 0
                        }
                    },
                    onPotSnookerPoints = { pts ->
                        if (snookerActivePlayer == 1) {
                            snookerP1Score += pts
                        } else {
                            snookerP2Score += pts
                        }
                        snookerCurrentBreak += pts
                        if (snookerCurrentBreak > snookerHighBreak) {
                            snookerHighBreak = snookerCurrentBreak
                        }
                    },
                    onSnookerFoul = {
                        if (snookerActivePlayer == 1) {
                            snookerP2Score += 4
                            snookerActivePlayer = 2
                        } else {
                            snookerP1Score += 4
                            snookerActivePlayer = 1
                        }
                        snookerCurrentBreak = 0
                    },
                    onEndSnookerBreak = {
                        snookerCurrentBreak = 0
                        snookerActivePlayer = if (snookerActivePlayer == 1) 2 else 1
                    },
                    onResetSnookerFrame = {
                        snookerP1Score = 0
                        snookerP2Score = 0
                        snookerActivePlayer = 1
                        snookerCurrentBreak = 0
                        snookerHighBreak = 0
                    },
                )

                ToolsDivider()

                // 3. Shot Clock
                ShotClockSection(
                    shotClock = shotClock,
                    onUpdateClock = { shotClock = it },
                )
            }
        }
    }
}

@Composable
private fun BreakSpeedMeterSection(
    audioBackendName: String,
    inMemoryOnly: Boolean,
    presets: List<BreakAudioPreset>,
    selectedPresetIndex: Int,
    capturedPreset: BreakAudioPreset?,
    analysis: Pair<BreakAudioEnvelopeResult, BreakSpeedEstimate>?,
    onSelectPreset: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Acoustic Break-Speed Meter",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolsStatusBadge(
                text = audioBackendName,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            ToolsStatusBadge(
                text = if (inMemoryOnly) {
                    "In-memory transient PCM only (no audio recorded)"
                } else {
                    "Buffered PCM"
                },
                containerColor = ToolsSuccessGreen.copy(alpha = 0.14f),
                contentColor = ToolsSuccessGreen,
                borderColor = ToolsSuccessGreen.copy(alpha = 0.45f),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEachIndexed { index, preset ->
                val isSelected = index == selectedPresetIndex
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectPreset(index) },
                    label = {
                        Text(
                            text = preset.title,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                )
            }
        }

        if (capturedPreset != null) {
            Text(
                text = "${capturedPreset.subtitle}  •  Break dist: ${formatFixed(capturedPreset.breakDistanceMeters, 2)} m  •  Envelope rate: ${capturedPreset.envelopeSampleRateHz} Hz",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            BreakEnvelopeCanvas(
                preset = capturedPreset,
                envelopeResult = analysis?.first,
            )
        }

        if (analysis != null) {
            val (env, estimate) = analysis
            val launchMph = estimate.launchSpeedMps * MPS_TO_MPH
            val launchKmh = estimate.launchSpeedMps * MPS_TO_KMH
            val impactMph = estimate.impactSpeedMps * MPS_TO_MPH
            val impactKmh = estimate.impactSpeedMps * MPS_TO_KMH

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "Launch Speed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${formatFixed(launchMph, 1)} mph",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = ToolsAccentCyan,
                            )
                            Text(
                                text = "${formatFixed(estimate.launchSpeedMps, 2)} m/s  •  ${formatFixed(launchKmh, 1)} km/h",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Rack Impact Speed",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${formatFixed(impactMph, 1)} mph",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = ToolsSuccessGreen,
                            )
                            Text(
                                text = "${formatFixed(estimate.impactSpeedMps, 2)} m/s  •  ${formatFixed(impactKmh, 1)} km/h",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    ToolsDivider()

                    Text(
                        text = "Cue strike: ${formatFixed(env.cueStrikeTimestampSeconds, 4)} s (${formatFixed(env.cueStrikeTimestampSeconds * SECONDS_TO_MS, 1)} ms)  •  Rack impact: ${formatFixed(env.rackImpactTimestampSeconds, 4)} s (${formatFixed(env.rackImpactTimestampSeconds * SECONDS_TO_MS, 1)} ms)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Raw Δt: ${formatFixed(env.rawDeltaTimeSeconds * SECONDS_TO_MS, 2)} ms  •  Acoustic delay correction: ${formatFixed(estimate.acousticDelayCorrectionSeconds * SECONDS_TO_MS, 2)} ms  •  Corrected flight: ${formatFixed(estimate.correctedFlightTimeSeconds * SECONDS_TO_MS, 2)} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakEnvelopeCanvas(
    preset: BreakAudioPreset,
    envelopeResult: BreakAudioEnvelopeResult?,
) {
    val amplitudes = preset.envelopeAmplitudes
    val sampleRate = preset.envelopeSampleRateHz.coerceAtLeast(1)
    if (amplitudes.isEmpty()) return

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A1220))
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        val count = amplitudes.size
        val bucketCount = minOf(count, 128)
        val bucketWidth = size.width / bucketCount.toFloat()
        val strikeIdx = envelopeResult?.let { (it.cueStrikeTimestampSeconds * sampleRate).roundToInt() } ?: -1
        val rackIdx = envelopeResult?.let { (it.rackImpactTimestampSeconds * sampleRate).roundToInt() } ?: -1
        val strikeBucket = if (strikeIdx in 0 until count) (strikeIdx * bucketCount) / count else -1
        val rackBucket = if (rackIdx in 0 until count) (rackIdx * bucketCount) / count else -1

        for (b in 0 until bucketCount) {
            val start = (b * count) / bucketCount
            val end = (((b + 1) * count) / bucketCount).coerceAtLeast(start + 1).coerceAtMost(count)
            var peakAmp = 0.0
            for (i in start until end) {
                if (amplitudes[i] > peakAmp) peakAmp = amplitudes[i]
            }
            val amp = peakAmp.coerceIn(0.0, 1.0).toFloat()
            val barH = (amp * size.height).coerceAtLeast(2f)
            val barColor = when {
                strikeBucket >= 0 && abs(b - strikeBucket) <= 1 -> ToolsAccentCyan
                rackBucket >= 0 && abs(b - rackBucket) <= 1 -> ToolsSuccessGreen
                else -> Color(0xFF475569)
            }
            drawRect(
                color = barColor,
                topLeft = Offset(x = b * bucketWidth, y = size.height - barH),
                size = Size(width = (bucketWidth * 0.8f).coerceAtLeast(1f), height = barH),
            )
        }
    }
}

@Composable
private fun ScorekeeperSection(
    raceState: PoolRaceScoreState,
    onUpdateRace: (PoolRaceScoreState) -> Unit,
    snookerP1Score: Int,
    snookerP2Score: Int,
    snookerActivePlayer: Int,
    snookerCurrentBreak: Int,
    snookerHighBreak: Int,
    onSelectStriker: (Int) -> Unit,
    onPotSnookerPoints: (Int) -> Unit,
    onSnookerFoul: () -> Unit,
    onEndSnookerBreak: () -> Unit,
    onResetSnookerFrame: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Pool Race & Snooker Scorekeeper",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Pool Race Scorekeeper
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${raceState.gameLabel} Race to ${raceState.raceTargetRacks}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    ToolsStatusBadge(
                        text = raceState.hillStatusText,
                        containerColor = if (raceState.isMatchComplete) {
                            ToolsSuccessGreen.copy(alpha = 0.16f)
                        } else {
                            ToolsAccentCyan.copy(alpha = 0.16f)
                        },
                        contentColor = if (raceState.isMatchComplete) ToolsSuccessGreen else ToolsAccentCyan,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    POOL_GAME_LABELS.forEach { label ->
                        FilterChip(
                            selected = raceState.gameLabel == label,
                            onClick = { onUpdateRace(raceState.copy(gameLabel = label)) },
                            label = { Text(label) },
                        )
                    }
                    RACE_TARGET_OPTIONS.forEach { target ->
                        FilterChip(
                            selected = raceState.raceTargetRacks == target,
                            onClick = { onUpdateRace(raceState.copy(raceTargetRacks = target)) },
                            label = { Text("Race $target") },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${raceState.playerOneName}: ${raceState.playerOneRacks}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = raceState.leaderName?.let { "Leader: $it" } ?: "Tied",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${raceState.playerTwoName}: ${raceState.playerTwoRacks}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            onUpdateRace(raceState.copy(playerOneRacks = raceState.playerOneRacks + 1))
                        },
                    ) {
                        Text("+1 Rack P1")
                    }
                    Button(
                        onClick = {
                            onUpdateRace(raceState.copy(playerTwoRacks = raceState.playerTwoRacks + 1))
                        },
                    ) {
                        Text("+1 Rack P2")
                    }
                    OutlinedButton(
                        onClick = {
                            onUpdateRace(
                                raceState.copy(
                                    playerOneRacks = (raceState.playerOneRacks - 1).coerceAtLeast(0),
                                ),
                            )
                        },
                    ) {
                        Text("-1 P1")
                    }
                    OutlinedButton(
                        onClick = {
                            onUpdateRace(
                                raceState.copy(
                                    playerTwoRacks = (raceState.playerTwoRacks - 1).coerceAtLeast(0),
                                ),
                            )
                        },
                    ) {
                        Text("-1 P2")
                    }
                    TextButton(
                        onClick = {
                            onUpdateRace(raceState.copy(playerOneRacks = 0, playerTwoRacks = 0))
                        },
                    ) {
                        Text("Reset")
                    }
                }
            }
        }

        // Snooker Break & Frame Counter
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Snooker Frame & Break Counter",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Break: $snookerCurrentBreak (High: $snookerHighBreak)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ToolsWarningAmber,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = snookerActivePlayer == 1,
                        onClick = { onSelectStriker(1) },
                        label = { Text("P1: $snookerP1Score") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = snookerActivePlayer == 2,
                        onClick = { onSelectStriker(2) },
                        label = { Text("P2: $snookerP2Score") },
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SnookerBallType.entries.forEach { ballType ->
                        OutlinedButton(
                            onClick = { onPotSnookerPoints(ballType.pointValue) },
                        ) {
                            SnookerBallDot(ballType = ballType, sizeDp = 10)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("+${ballType.pointValue} ${snookerBallName(ballType)}")
                        }
                    }
                    OutlinedButton(
                        onClick = onSnookerFoul,
                    ) {
                        Text("+4 Foul", color = ToolsDangerCoral)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onEndSnookerBreak,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("End Break / Switch")
                    }
                    TextButton(
                        onClick = onResetSnookerFrame,
                    ) {
                        Text("Reset Frame")
                    }
                }
            }
        }
    }
}

@Composable
private fun ShotClockSection(
    shotClock: ShotClockLiveState,
    onUpdateClock: (ShotClockLiveState) -> Unit,
) {
    val clockColor = when {
        shotClock.isExpired -> ToolsDangerCoral
        shotClock.isInWarningZone -> ToolsWarningAmber
        else -> ToolsSuccessGreen
    }
    val clockStatusText = when {
        shotClock.isExpired -> "TIME EXPIRED"
        shotClock.isInWarningZone -> "WARNING"
        else -> "READY"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Shot Clock & Extensions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ToolsStatusBadge(
                text = clockStatusText,
                containerColor = clockColor.copy(alpha = 0.16f),
                contentColor = clockColor,
                borderColor = clockColor.copy(alpha = 0.45f),
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, clockColor.copy(alpha = 0.5f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${shotClock.remainingSeconds} s",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = clockColor,
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "P1 Ext: ${if (shotClock.playerOneExtensionUsedInRack) "Used" else "Available"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "P2 Ext: ${if (shotClock.playerTwoExtensionUsedInRack) "Used" else "Available"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onUpdateClock(shotClock.tickOneSecond()) },
                    ) {
                        Text("-1s Tick")
                    }
                    OutlinedButton(
                        onClick = {
                            var next = shotClock
                            repeat(5) { next = next.tickOneSecond() }
                            onUpdateClock(next)
                        },
                    ) {
                        Text("-5s")
                    }
                    Button(
                        onClick = { onUpdateClock(shotClock.applyExtension(forPlayerOne = true)) },
                        enabled = !shotClock.playerOneExtensionUsedInRack,
                    ) {
                        Text("+30s P1 Extension")
                    }
                    Button(
                        onClick = { onUpdateClock(shotClock.applyExtension(forPlayerOne = false)) },
                        enabled = !shotClock.playerTwoExtensionUsedInRack,
                    ) {
                        Text("+30s P2 Extension")
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onUpdateClock(shotClock.resetForNextShot(isBreakOrPushOut = false)) },
                    ) {
                        Text("Reset Shot (35s)")
                    }
                    OutlinedButton(
                        onClick = { onUpdateClock(shotClock.resetForNextShot(isBreakOrPushOut = true)) },
                    ) {
                        Text("Reset Break (60s)")
                    }
                    TextButton(
                        onClick = { onUpdateClock(ShotClockLiveState()) },
                    ) {
                        Text("New Rack")
                    }
                }
            }
        }
    }
}

@Composable
private fun CounterStepper(
    label: String,
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDecrement) {
                Text("-1")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$value",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(onClick = onIncrement) {
                Text("+1")
            }
        }
    }
}

@Composable
private fun SnookerBallDot(
    ballType: SnookerBallType,
    sizeDp: Int,
) {
    val fillColor = when (ballType) {
        SnookerBallType.RED -> Color(0xFFDC2626)
        SnookerBallType.YELLOW -> Color(0xFFFACC15)
        SnookerBallType.GREEN -> Color(0xFF16A34A)
        SnookerBallType.BROWN -> Color(0xFF92400E)
        SnookerBallType.BLUE -> Color(0xFF2563EB)
        SnookerBallType.PINK -> Color(0xFFF472B6)
        SnookerBallType.BLACK -> Color(0xFF111827)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(fillColor)
            .border(1.dp, Color(0x66FFFFFF), CircleShape),
    )
}

@Composable
private fun ToolsStatusBadge(
    text: String,
    containerColor: Color,
    contentColor: Color,
    borderColor: Color? = null,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        border = borderColor?.let { BorderStroke(1.dp, it) },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}

@Composable
private fun ToolsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

private fun snookerBallName(type: SnookerBallType): String = when (type) {
    SnookerBallType.RED -> "Red"
    SnookerBallType.YELLOW -> "Yellow"
    SnookerBallType.GREEN -> "Green"
    SnookerBallType.BROWN -> "Brown"
    SnookerBallType.BLUE -> "Blue"
    SnookerBallType.PINK -> "Pink"
    SnookerBallType.BLACK -> "Black"
}

private fun formatVec2Meters(pos: Vec2): String =
    "(${formatFixed(pos.x, 2)}, ${formatFixed(pos.y, 2)}) m"

private fun formatTipSummary(tip: TipOffset): String {
    val vert = when {
        tip.clampedVerticalRatio > 0.12 -> "Follow"
        tip.clampedVerticalRatio < -0.12 -> "Draw"
        else -> "Stun"
    }
    val side = when {
        tip.clampedSideRatio > 0.08 -> "Right"
        tip.clampedSideRatio < -0.08 -> "Left"
        else -> "Center"
    }
    return if (side == "Center") vert else "$vert + $side"
}

private fun formatSignedInt(value: Int): String =
    if (value > 0) "+$value" else "$value"

private fun formatFixed(value: Double, decimals: Int): String {
    if (!value.isFinite()) return "0"
    var scale = 1L
    repeat(decimals) { scale *= 10L }
    val scaled = round(value * scale).toLong()
    val sign = if (scaled < 0L) "-" else ""
    val absScaled = abs(scaled)
    val whole = absScaled / scale
    val frac = absScaled % scale
    return if (decimals == 0) {
        "$sign$whole"
    } else {
        "$sign$whole.${frac.toString().padStart(decimals, '0')}"
    }
}
