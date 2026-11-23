package com.runout.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.runout.app.tools.EightBallWalkthrough
import com.runout.app.tools.InteractiveTutorial
import com.runout.app.tools.PracticeDrillSpec
import com.runout.app.tools.PracticeLogEntry
import com.runout.app.tools.TutorialsAndDrillsCatalog
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.TableLayout
import kotlin.math.round

enum class LearnSubTab(val label: String) {
    WALKTHROUGHS("Walkthroughs"),
    TUTORIALS("Tutorials"),
    DRILLS("Drills"),
}

/**
 * Clean structured learning hub organizing 8-Ball walkthroughs, 2D/3D interactive tutorials,
 * and scored practice drills without crowding a single endless scroll view.
 */
@Composable
fun LearnHubScreen(
    ballSet: BallSetProfile,
    selectedSubTab: LearnSubTab,
    onSelectSubTab: (LearnSubTab) -> Unit,
    onOpenWalkthrough: (EightBallWalkthrough) -> Unit,
    onOpenTutorial: (InteractiveTutorial) -> Unit,
    onLoadDrillIntoPlanner: (TableLayout, GameRulesState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val walkthroughs = remember { TutorialsAndDrillsCatalog.eightBallWalkthroughs() }
    val tutorials = remember { TutorialsAndDrillsCatalog.allTutorials() }
    val drills = remember { TutorialsAndDrillsCatalog.allDrills() }
    var practiceLogs by remember { mutableStateOf(TutorialsAndDrillsCatalog.defaultPracticeLogs()) }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedSubTab.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            LearnSubTab.entries.forEach { tab ->
                val selected = selectedSubTab == tab
                Tab(
                    selected = selected,
                    onClick = { onSelectSubTab(tab) },
                    text = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Card),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Section),
        ) {
            when (selectedSubTab) {
                LearnSubTab.WALKTHROUGHS -> {
                    Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                        RunoutSectionHeader(
                            title = "8-Ball Walkthroughs (${walkthroughs.size})",
                            subtitle = "Tap any scenario for step-by-step text diagrams and a 3D table slider.",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card)) {
                            walkthroughs.forEach { wt ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(role = Role.Button) { onOpenWalkthrough(wt) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(RunoutSpacing.Margin),
                                        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                            ) {
                                                Text(
                                                    text = wt.rulesState.eightBallGroup.displayNameFor(ballSet),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            Text(
                                                text = "2D & 3D Interactive",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.secondary,
                                            )
                                        }
                                        Text(
                                            text = wt.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = wt.situationSummary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        OutlinedButton(
                                            onClick = { onOpenWalkthrough(wt) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp),
                                        ) {
                                            Text("Open Guide", fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                LearnSubTab.TUTORIALS -> {
                    Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                        RunoutSectionHeader(
                            title = "Interactive Tutorials (${tutorials.size})",
                            subtitle = "Core pool skills paired with 2D stage diagrams and a 3D frame scrubber.",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card)) {
                            tutorials.forEach { tutorial ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable(role = Role.Button) { onOpenTutorial(tutorial) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(RunoutSpacing.Margin),
                                        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                            ) {
                                                Text(
                                                    text = tutorial.category,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                )
                                            }
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
                                        OutlinedButton(
                                            onClick = { onOpenTutorial(tutorial) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp),
                                        ) {
                                            Text("Open 2D / 3D Lesson", fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                LearnSubTab.DRILLS -> {
                    LearnDrillsSection(
                        drills = drills,
                        practiceLogs = practiceLogs,
                        onAddLog = { entry -> practiceLogs = listOf(entry) + practiceLogs },
                        onLoadDrillIntoPlanner = onLoadDrillIntoPlanner,
                    )
                }
            }
        }
    }
}

/**
 * Full-screen dedicated player (Bottom Nav hidden) for a single [EightBallWalkthrough].
 */
@Composable
fun WalkthroughDetailPlayerScreen(
    walkthrough: EightBallWalkthrough,
    ballSet: BallSetProfile,
    onTryOnTablePlanner: (TableLayout, GameRulesState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Card),
        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Section),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
            Text(
                text = walkthrough.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = walkthrough.situationSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PhasedInteractiveTutorialViewer(
            layout = walkthrough.layout,
            stages = walkthrough.visualStages,
            targetBallNumber = walkthrough.primaryTargetBallNumber,
            pocketId = walkthrough.primaryPocketId,
            recommendedTipOffset = walkthrough.recommendedTipOffset,
            recommendedSpeedScale = walkthrough.recommendedSpeedScale,
            ballSet = ballSet,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(RunoutSpacing.Margin),
                verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
            ) {
                Text(
                    text = "Rule Applied",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = walkthrough.ruleApplied,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "Step-by-Step Guide",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.secondary,
                )
                walkthrough.stepByStepGuide.forEachIndexed { idx, step ->
                    Text(
                        text = "${idx + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    text = "Common Mistake to Avoid",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = walkthrough.commonMistakeToAvoid,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Button(
            onClick = { onTryOnTablePlanner(walkthrough.layout, walkthrough.rulesState) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Try on Table Planner", fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Full-screen dedicated player (Bottom Nav hidden) for a single [InteractiveTutorial].
 */
@Composable
fun TutorialDetailPlayerScreen(
    tutorial: InteractiveTutorial,
    ballSet: BallSetProfile,
    onTryOnTablePlanner: (TableLayout, GameRulesState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Card),
        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Section),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
            Text(
                text = tutorial.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = tutorial.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PhasedInteractiveTutorialViewer(
            layout = tutorial.practiceLayout,
            stages = tutorial.visualStages,
            targetBallNumber = tutorial.primaryTargetBallNumber,
            pocketId = tutorial.primaryPocketId,
            recommendedTipOffset = tutorial.recommendedTipOffset,
            recommendedSpeedScale = tutorial.recommendedSpeedScale,
            ballSet = ballSet,
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(RunoutSpacing.Margin),
                verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
            ) {
                Text(
                    text = "Key Takeaways",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                tutorial.keyTakeaways.forEach { takeaway ->
                    Text(
                        text = "• $takeaway",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Button(
            onClick = { onTryOnTablePlanner(tutorial.practiceLayout, tutorial.practiceRulesState) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text("Try on Table Planner", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LearnDrillsSection(
    drills: List<PracticeDrillSpec>,
    practiceLogs: List<PracticeLogEntry>,
    onAddLog: (PracticeLogEntry) -> Unit,
    onLoadDrillIntoPlanner: (TableLayout, GameRulesState) -> Unit,
) {
    var selectedDrillIndex by remember { mutableIntStateOf(0) }
    val safeIndex = selectedDrillIndex.coerceIn(0, (drills.size - 1).coerceAtLeast(0))
    val activeDrill = drills.getOrNull(safeIndex)
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

    Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Section)) {
        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
            RunoutSectionHeader(
                title = "Scored Drills (${drills.size})",
                subtitle = "Load any setup onto the table planner and track your score.",
            )
            Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card)) {
                drills.forEach { drill ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(RunoutSpacing.Margin),
                            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = RunoutColors.SuccessGreen.copy(alpha = 0.16f),
                                ) {
                                    Text(
                                        text = drill.targetSkill,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = RunoutColors.SuccessGreen,
                                    )
                                }
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
                                onClick = {
                                    onLoadDrillIntoPlanner(drill.drillLayout, drill.drillRulesState)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                            ) {
                                Text("Load Drill on Table", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        CollapsibleSectionCard(
            title = "Practice Log (${practiceLogs.size} sessions)",
            summary = "Overall: ${round(completionPct).toInt()}% ($totalSuccesses/$totalAttempts)",
            initiallyExpanded = false,
        ) {
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
                        .background(RunoutColors.SuccessGreen),
                )
            }

            if (activeDrill != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                ) {
                    drills.forEachIndexed { idx, drill ->
                        FilterChip(
                            selected = idx == safeIndex,
                            onClick = { selectedDrillIndex = idx },
                            modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                            label = { Text(drill.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
                ) {
                    OutlinedButton(
                        onClick = { logSuccesses = (logSuccesses - 1).coerceAtLeast(0) },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("- Made ($logSuccesses)") }
                    OutlinedButton(
                        onClick = { logSuccesses = (logSuccesses + 1).coerceAtMost(logAttempts) },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("+ Made") }
                }
                OutlinedTextField(
                    value = logNotes,
                    onValueChange = { logNotes = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Notes (optional)") },
                )
                Button(
                    onClick = {
                        onAddLog(
                            PracticeLogEntry(
                                id = "log-${practiceLogs.size + 1}",
                                drillId = activeDrill.id,
                                drillTitle = activeDrill.title,
                                dateIso = "Today",
                                successes = logSuccesses,
                                attempts = logAttempts,
                                notes = logNotes.trim(),
                            ),
                        )
                        logNotes = ""
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RunoutColors.SuccessGreen,
                        contentColor = RunoutColors.OnSuccess,
                    ),
                ) {
                    Text("Save Drill Result", fontWeight = FontWeight.SemiBold)
                }
            }

            practiceLogs.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = entry.drillTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${entry.successes}/${entry.attempts}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = RunoutColors.SuccessGreen,
                    )
                }
            }
        }
    }
}
