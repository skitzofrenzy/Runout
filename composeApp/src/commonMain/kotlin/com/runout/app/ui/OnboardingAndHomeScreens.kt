package com.runout.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.ExperienceLevel
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.GameType
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec

private data class TablePresetChoice(
    val label: String,
    val createSpec: () -> TableSpec,
)

private val ONBOARDING_TABLE_CHOICES = listOf(
    TablePresetChoice("7-ft Bar Box") { TableSpec.sevenFootPool() },
    TablePresetChoice("8-ft Home") { TableSpec.eightFootPool() },
    TablePresetChoice("9-ft Pro") { TableSpec.nineFootPool() },
    TablePresetChoice("12-ft Snooker") { TableSpec.fullSizeSnooker() },
)

private data class GameChoiceOption(
    val label: String,
    val gameType: GameType,
    val useUkBlackball: Boolean = false,
)

private val ONBOARDING_GAME_CHOICES = listOf(
    GameChoiceOption("8-Ball", GameType.EIGHT_BALL),
    GameChoiceOption("UK 8-Ball (Blackball)", GameType.EIGHT_BALL, useUkBlackball = true),
    GameChoiceOption("9-Ball", GameType.NINE_BALL),
    GameChoiceOption("10-Ball", GameType.TEN_BALL),
)

/**
 * 3-step first-run onboarding establishing what Runout does, player experience level,
 * and default game + table size in under 30 seconds.
 */
@Composable
fun OnboardingFlowScreen(
    initialLevel: ExperienceLevel,
    initialGame: GameType,
    initialTableSpec: TableSpec,
    onComplete: (
        level: ExperienceLevel,
        gameType: GameType,
        tableSpec: TableSpec,
        useUkBlackball: Boolean,
        preferredMode: AppDisplayMode,
    ) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by remember { mutableIntStateOf(0) }
    var selectedLevel by remember { mutableStateOf(initialLevel) }
    var selectedGameIndex by remember {
        val initialIdx = ONBOARDING_GAME_CHOICES.indexOfFirst { it.gameType == initialGame && !it.useUkBlackball }
            .coerceAtLeast(0)
        mutableIntStateOf(initialIdx)
    }
    var selectedTableIndex by remember {
        val initialIdx = ONBOARDING_TABLE_CHOICES.indexOfFirst {
            it.createSpec().name == initialTableSpec.name
        }.coerceAtLeast(2)
        mutableIntStateOf(initialIdx)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Card),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top bar: progress + Skip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Step ${step + 1} of 3",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                ) {
                    Text("Skip")
                }
            }

            // Middle content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = RunoutSpacing.Margin),
                verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Section),
            ) {
                when (step) {
                    0 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                            Text(
                                text = "Your offline pool coach & shot planner.",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = "See the highest-percentage shot, where the cue ball lands next, and clear explanations — right at the table.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card)) {
                            OnboardingValueCard(
                                number = "1",
                                title = "Snap or Build a Table",
                                detail = "Take a photo, pick one from your gallery, or drag balls manually on an interactive table.",
                                icon = RunoutIcon.CAMERA,
                            )
                            OnboardingValueCard(
                                number = "2",
                                title = "See Your Best Shot & Shape",
                                detail = "Get clear aim lines, speed guidance, and multi-shot plans without guessing.",
                                icon = RunoutIcon.PLAN,
                            )
                            OnboardingValueCard(
                                number = "3",
                                title = "Learn Rules & Practice Drills",
                                detail = "Search offline rules and scrub 2D diagrams or a 3D table slider step by step.",
                                icon = RunoutIcon.LEARN,
                            )
                        }
                    }

                    1 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                            Text(
                                text = "How familiar are you with pool?",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = "Runout adapts wording, coaching tips, and overlay detail to match you. You can switch Simple/Pro mode anytime.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card)) {
                            ExperienceChoiceCard(
                                title = "New to Pool",
                                badge = "Simple Mode",
                                description = "Plain-English shot advice, 8-Ball starter guides, and tap-to-explain pool terms.",
                                selected = selectedLevel == ExperienceLevel.BEGINNER,
                                onClick = { selectedLevel = ExperienceLevel.BEGINNER },
                            )
                            ExperienceChoiceCard(
                                title = "Casual / League Player",
                                badge = "Recommended",
                                description = "Balanced Simple Mode with 3-ball shape plans, speed ratings (1–10), and optional spin.",
                                selected = selectedLevel == ExperienceLevel.INTERMEDIATE,
                                onClick = { selectedLevel = ExperienceLevel.INTERMEDIATE },
                            )
                            ExperienceChoiceCard(
                                title = "Advanced / Competitive",
                                badge = "Pro Mode",
                                description = "Starts in Pro Mode with 2D English control, heat zones, landing clouds, and deep run-outs.",
                                selected = selectedLevel == ExperienceLevel.ADVANCED,
                                onClick = { selectedLevel = ExperienceLevel.ADVANCED },
                            )
                        }
                    }

                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                            Text(
                                text = "What do you usually play?",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Text(
                                text = "Sets your starting table size and favorite game rules.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                            Text(
                                text = "Usual Game",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                            ) {
                                ONBOARDING_GAME_CHOICES.forEachIndexed { idx, option ->
                                    val selected = idx == selectedGameIndex
                                    FilterChip(
                                        selected = selected,
                                        onClick = { selectedGameIndex = idx },
                                        modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                                        label = {
                                            Text(
                                                text = option.label,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
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

                        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                            Text(
                                text = "Typical Table Size",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                            ) {
                                ONBOARDING_TABLE_CHOICES.forEachIndexed { idx, option ->
                                    val selected = idx == selectedTableIndex
                                    FilterChip(
                                        selected = selected,
                                        onClick = { selectedTableIndex = idx },
                                        modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                                        label = {
                                            Text(
                                                text = option.label,
                                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Bottom footer buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step-- },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Text("Back", fontWeight = FontWeight.SemiBold)
                    }
                }
                Button(
                    onClick = {
                        if (step < 2) {
                            step++
                        } else {
                            val chosenGame = ONBOARDING_GAME_CHOICES[selectedGameIndex]
                            val chosenTable = ONBOARDING_TABLE_CHOICES[selectedTableIndex].createSpec()
                            val preferredMode = if (selectedLevel == ExperienceLevel.ADVANCED) {
                                AppDisplayMode.PRO
                            } else {
                                AppDisplayMode.SIMPLE
                            }
                            onComplete(
                                selectedLevel,
                                chosenGame.gameType,
                                chosenTable,
                                chosenGame.useUkBlackball,
                                preferredMode,
                            )
                        }
                    },
                    modifier = Modifier
                        .weight(if (step > 0) 2f else 1f)
                        .height(52.dp),
                ) {
                    val cta = when (step) {
                        0 -> "Get Started"
                        1 -> "Continue"
                        else -> "Enter Runout"
                    }
                    Text(cta, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Clean landing dashboard offering one-tap Continue / Reset session control,
 * direct Camera/Gallery photo scanning, New Table setup, and skill-tailored starting guides.
 */
@Composable
fun HomeLandingScreen(
    playerProfile: PlayerProfile,
    ballSet: BallSetProfile,
    currentLayout: TableLayout,
    rulesState: GameRulesState,
    hasActiveSession: Boolean,
    onContinueLastTable: () -> Unit,
    onRequestResetSession: () -> Unit,
    onOpenPhotoScan: () -> Unit,
    onOpenNewTableSheet: () -> Unit,
    onOpenSavedTables: () -> Unit,
    onOpenLearnWalkthroughs: () -> Unit,
    onOpenLearnDrills: () -> Unit,
    onOpenRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Card),
        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Section),
    ) {
        if (hasActiveSession) {
            Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                RunoutSectionHeader(title = "Pick Up Where You Left Off")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = RunoutColors.SurfaceMid),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(RunoutSpacing.Margin),
                        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
                    ) {
                        val objectCount = currentLayout.objectBalls().size
                        val groupSuffix = if (rulesState.gameType == GameType.EIGHT_BALL) {
                            " • ${rulesState.eightBallGroup.displayNameFor(ballSet)}"
                        } else {
                            ""
                        }
                        Text(
                            text = "${rulesState.gameType.displayName}$groupSuffix",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${currentLayout.table.name} • $objectCount object balls on table",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                        ) {
                            Button(
                                onClick = onContinueLastTable,
                                modifier = Modifier
                                    .weight(2f)
                                    .height(48.dp),
                            ) {
                                Text("Continue", fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = onRequestResetSession,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                            ) {
                                Text("Reset", color = RunoutColors.DangerCoral)
                            }
                        }
                    }
                }
            }
        }

        // Hero Table Capture section
        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
            RunoutSectionHeader(
                title = "Analyze a Table",
                subtitle = "Scan with your phone or set up balls by hand",
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClick = onOpenPhotoScan),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                ),
                border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.60f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(RunoutSpacing.Margin),
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Margin),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RunoutVectorIcon(
                                icon = RunoutIcon.CAMERA,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Scan or Import Table Photo",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Take a photo or choose one from your phone gallery to detect balls & get shot lines.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
            ) {
                QuickSquareActionCard(
                    title = "New Table",
                    subtitle = "Presets or blank rack",
                    icon = RunoutIcon.TABLE,
                    onClick = onOpenNewTableSheet,
                    modifier = Modifier.weight(1f),
                )
                QuickSquareActionCard(
                    title = "Saved Tables",
                    subtitle = "Venue & cloth profiles",
                    icon = RunoutIcon.TUNE,
                    onClick = onOpenSavedTables,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // Tailored for Skill Level
        Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
            val tailoringLabel = when (playerProfile.experienceLevel) {
                ExperienceLevel.BEGINNER -> "Great Starting Points"
                ExperienceLevel.INTERMEDIATE -> "Improve Your Run-Outs"
                ExperienceLevel.ADVANCED -> "Training & Rules"
            }
            RunoutSectionHeader(title = tailoringLabel)

            Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card)) {
                if (playerProfile.experienceLevel == ExperienceLevel.BEGINNER) {
                    HomeLinkRowCard(
                        title = "Start Here: 8-Ball Basics Walkthrough",
                        subtitle = "Visual step-by-step examples with an interactive 3D table slider.",
                        icon = RunoutIcon.LEARN,
                        onClick = onOpenLearnWalkthroughs,
                    )
                    HomeLinkRowCard(
                        title = "Quick Rules & Pool Terms",
                        subtitle = "Look up Scratch, Ball in Hand, Kitchen, or Open Table offline.",
                        icon = RunoutIcon.RULES,
                        onClick = onOpenRules,
                    )
                } else {
                    HomeLinkRowCard(
                        title = "Scored Drills & 2D/3D Walkthroughs",
                        subtitle = "Load target-score practice drills directly onto your table.",
                        icon = RunoutIcon.LEARN,
                        onClick = onOpenLearnDrills,
                    )
                    HomeLinkRowCard(
                        title = "Rules & UK/League Variations",
                        subtitle = "Compare WPA, BCAPL, APA, UK Blackball, and Snooker rules.",
                        icon = RunoutIcon.RULES,
                        onClick = onOpenRules,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingValueCard(
    number: String,
    title: String,
    detail: String,
    icon: RunoutIcon,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunoutSpacing.Margin),
            horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(44.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RunoutVectorIcon(icon = icon, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "$number. $title",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExperienceChoiceCard(
    title: String,
    badge: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        border = BorderStroke(
            width = if (selected) 1.6.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunoutSpacing.Margin),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = badge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickSquareActionCard(
    title: String,
    subtitle: String,
    icon: RunoutIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .defaultMinSize(minHeight = 88.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick),
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
            RunoutVectorIcon(
                icon = icon,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomeLinkRowCard(
    title: String,
    subtitle: String,
    icon: RunoutIcon,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunoutSpacing.Margin),
            horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RunoutVectorIcon(icon = icon, tint = MaterialTheme.colorScheme.secondary)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
