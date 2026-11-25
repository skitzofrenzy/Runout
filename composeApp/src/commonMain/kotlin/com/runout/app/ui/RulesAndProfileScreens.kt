package com.runout.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runout.app.rules.RuleEntry
import com.runout.app.rules.RulesGame
import com.runout.app.rules.RulesSearchEngine
import com.runout.app.tools.EightBallWalkthrough
import com.runout.app.tools.TutorialsAndDrillsCatalog
import com.runout.engine.model.BallPaletteColor
import com.runout.engine.model.BallSetProfile
import com.runout.engine.model.ExperienceLevel
import com.runout.engine.model.GameRulesState
import com.runout.engine.model.PlayerProfile
import com.runout.engine.model.ShaftType
import com.runout.engine.model.TableLayout
import com.runout.engine.model.TableSpec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.round

private const val METERS_TO_FEET: Double = 3.28084
private const val METERS_TO_MM: Double = 1000.0

/**
 * Offline searchable Rules & FAQ browser across 8-Ball, UK 8-Ball (Blackball), 9-Ball, 10-Ball,
 * and Snooker, with collapsed accordion rule cards and a shortcut into the interactive 8-Ball guides.
 */
@Composable
fun RulesAndFaqScreen(
    modifier: Modifier = Modifier,
    onLoadExampleLayout: ((TableLayout, GameRulesState) -> Unit)? = null,
    onOpenWalkthroughs: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var selectedGame by remember { mutableStateOf<RulesGame?>(null) }

    val matchingEntries = remember(query, selectedGame) {
        RulesSearchEngine.search(query = query, gameFilter = selectedGame)
    }
    val walkthroughs = remember { TutorialsAndDrillsCatalog.eightBallWalkthroughs() }
    val showWalkthroughShortcut = selectedGame == null ||
        selectedGame == RulesGame.EIGHT_BALL ||
        selectedGame == RulesGame.BLACKBALL

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Card),
        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search rules & FAQs") },
            placeholder = { Text("Try scratch, bih, push out, free ball, kitchen...") },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { query = "" }) {
                        Text("Clear")
                    }
                }
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
        ) {
            FilterChip(
                selected = selectedGame == null,
                onClick = { selectedGame = null },
                label = { Text("All") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
            RulesGame.entries.forEach { game ->
                val isSelected = selectedGame == game
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedGame = game },
                    label = {
                        Text(
                            text = game.displayName,
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

        Text(
            text = "Showing ${matchingEntries.size} ${if (matchingEntries.size == 1) "rule" else "rules"} — tap any card to expand",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (matchingEntries.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(RunoutSpacing.Margin),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "No matching rule entries found.",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Try searching with terms like scratch, bih, push out, free ball, kitchen, or switch the game filter back to All.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            matchingEntries.forEach { entry ->
                RuleEntryCard(entry = entry)
            }
        }

        if (showWalkthroughShortcut) {
            if (onOpenWalkthroughs != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = RunoutColors.SurfaceMid),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(RunoutSpacing.Margin),
                        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                    ) {
                        Text(
                            text = "Prefer Visual 8-Ball Walkthroughs?",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Step through ${walkthroughs.size} interactive examples with 2D stage diagrams and a 3D frame scrubber in Learn.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onOpenWalkthroughs,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) {
                            Text("Open 8-Ball Walkthroughs", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                walkthroughs.forEach { walkthrough ->
                    EightBallWalkthroughCard(
                        walkthrough = walkthrough,
                        loadButtonLabel = "Load Example on Table",
                        onLoadClick = onLoadExampleLayout?.let { loader ->
                            { loader(walkthrough.layout, walkthrough.rulesState) }
                        },
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Text(
                text = "Practice aid notice: summaries are written in plain language for offline reference. Consult the linked WPA or WPBSA official rules for tournament rulings.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuleEntryCard(entry: RuleEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(RunoutSpacing.Margin),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    ) {
                        Text(
                            text = entry.game.displayName,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = entry.category,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                RunoutVectorIcon(
                    icon = if (expanded) RunoutIcon.CHEVRON_UP else RunoutIcon.CHEVRON_DOWN,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = entry.summary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.tertiary,
            )

            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                )

                Text(
                    text = entry.plainAnswer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (entry.exampleScenario.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "On-Table Example",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = entry.exampleScenario,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Source: ${entry.officialSourceTitle}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${entry.officialSourceUrl}  •  Checked ${entry.lastCheckedDate}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

/**
 * Shared interactive card rendering an [EightBallWalkthrough] with situation context, rule applied,
 * numbered step-by-step guide, common pitfall callout, two-phase 2D diagram / 3D timeline viewer,
 * and optional table-loader action button.
 */
@Composable
internal fun EightBallWalkthroughCard(
    walkthrough: EightBallWalkthrough,
    loadButtonLabel: String,
    onLoadClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    ballSet: BallSetProfile = BallSetProfile.standardPool(),
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                ) {
                    Text(
                        text = walkthrough.rulesState.eightBallGroup.displayName,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = "${walkthrough.stepByStepGuide.size} steps",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = walkthrough.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Text(
                text = walkthrough.situationSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Rule Applied",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = walkthrough.ruleApplied,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "Common Mistake to Avoid",
                        style = MaterialTheme.typography.labelMedium,
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

            PhasedInteractiveTutorialViewer(
                layout = walkthrough.layout,
                stages = walkthrough.visualStages,
                targetBallNumber = walkthrough.primaryTargetBallNumber,
                pocketId = walkthrough.primaryPocketId,
                recommendedTipOffset = walkthrough.recommendedTipOffset,
                recommendedSpeedScale = walkthrough.recommendedSpeedScale,
                ballSet = ballSet,
            )

            if (onLoadClick != null) {
                OutlinedButton(
                    onClick = onLoadClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = loadButtonLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Player stroke questionnaire, calibrated parameter readout, ball-set colour-to-number swapper,
 * and active table/cloth parameter readout.
 */
@Composable
fun PlayerProfileScreen(
    profile: PlayerProfile,
    ballSet: BallSetProfile,
    table: TableSpec,
    onUpdateProfile: (PlayerProfile) -> Unit,
    onUpdateBallSet: (BallSetProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var experience by remember(profile) { mutableStateOf(profile.experienceLevel) }
    var shaft by remember(profile) { mutableStateOf(profile.shaftType) }
    var speedRating by remember(profile) { mutableIntStateOf(inferSpeedRating(profile)) }
    var spinRating by remember(profile) { mutableIntStateOf(inferSpinRating(profile)) }
    var selectedBallToSwap by remember(ballSet) { mutableStateOf<Int?>(null) }

    fun applyQuestionnaire(
        newExperience: ExperienceLevel = experience,
        newShaft: ShaftType = shaft,
        newSpeedRating: Int = speedRating,
        newSpinRating: Int = spinRating,
    ) {
        val calibrated = PlayerProfile.fromQuestionnaire(
            experience = newExperience,
            shaft = newShaft,
            speedControlRating = newSpeedRating,
            spinRating = newSpinRating,
        )
        val updated = if (profile.name == "Default Player") {
            calibrated
        } else {
            calibrated.copy(name = profile.name)
        }
        onUpdateProfile(updated)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Player & Ball Set",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Calibrate your 1–10 speed scale, spin comfort, and custom ball-set colour mapping.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ── 1. Player Questionnaire ──────────────────────────────────────────────────────────
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
                Text(
                    text = "Player Stroke Questionnaire",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Experience level selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Experience Level",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ExperienceLevel.entries.forEach { level ->
                            val isSelected = experience == level
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    experience = level
                                    applyQuestionnaire(newExperience = level)
                                },
                                label = { Text(level.displayLabel()) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }

                // Shaft type selector
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Cue Shaft Type",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ShaftType.entries.forEach { type ->
                            val isSelected = shaft == type
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    shaft = type
                                    applyQuestionnaire(newShaft = type)
                                },
                                label = { Text(type.displayLabel()) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }

                // Speed control rating (1..5)
                RatingSelectorRow(
                    title = "Speed Control Comfort (1–5)",
                    subtitle = "1 = basic touch, 5 = wide soft-to-power speed range",
                    selectedRating = speedRating,
                    onSelectRating = { rating ->
                        speedRating = rating
                        applyQuestionnaire(newSpeedRating = rating)
                    },
                )

                // Spin control rating (1..5)
                RatingSelectorRow(
                    title = "Spin & Draw Control (1–5)",
                    subtitle = "1 = centre-ball only, 5 = confident deep draw",
                    selectedRating = spinRating,
                    onSelectRating = { rating ->
                        spinRating = rating
                        applyQuestionnaire(newSpinRating = rating)
                    },
                )

                Button(
                    onClick = { applyQuestionnaire() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Recalibrate Player Profile")
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )

                Text(
                    text = "Calibrated Parameters",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )

                ParameterReadoutRow(
                    label = "Speed 1 (Min)",
                    value = "${formatFixed(profile.speedScaleMinMps, 2)} m/s",
                )
                ParameterReadoutRow(
                    label = "Speed 10 (Max)",
                    value = "${formatFixed(profile.speedScaleMaxMps, 2)} m/s",
                )
                ParameterReadoutRow(
                    label = "Soft / Medium / Firm (3 / 5 / 7)",
                    value = "${formatFixed(profile.speedForScale(3), 2)} / " +
                        "${formatFixed(profile.speedForScale(5), 2)} / " +
                        "${formatFixed(profile.speedForScale(7), 2)} m/s",
                )
                ParameterReadoutRow(
                    label = "Max Effective Draw Ratio",
                    value = "${formatFixed(profile.maxEffectiveDrawRatio, 2)}×",
                )
                ParameterReadoutRow(
                    label = "Aim Error (σ)",
                    value = "${formatFixed(profile.aimErrorStdRad, 3)} rad " +
                        "(${formatFixed(profile.aimErrorStdRad * 180.0 / PI, 2)}°)",
                )
            }
        }

        // ── 2. Ball-Set Profile & Colour Swapper ─────────────────────────────────────────────
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
                val standardPreset = remember { BallSetProfile.standardPool() }
                val tvPreset = remember { BallSetProfile.tvTournamentPool() }
                val ballSetBadgeLabel = when (ballSet.numberToColor) {
                    standardPreset.numberToColor -> "Standard Pool"
                    tvPreset.numberToColor -> "TV Tournament"
                    else -> "Custom"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Ball-Set Colour Mapping",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = ballSetBadgeLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = ballSet.numberToColor == standardPreset.numberToColor,
                        onClick = {
                            selectedBallToSwap = null
                            onUpdateBallSet(BallSetProfile.standardPool())
                        },
                        label = { Text("Standard Pool") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    FilterChip(
                        selected = ballSet.numberToColor == tvPreset.numberToColor,
                        onClick = {
                            selectedBallToSwap = null
                            onUpdateBallSet(BallSetProfile.tvTournamentPool())
                        },
                        label = { Text("TV Tournament") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                Text(
                    text = if (selectedBallToSwap == null) {
                        "Tap any two balls (1–15) below to swap their colours for mismatched or reordered ball sets."
                    } else {
                        "Ball #$selectedBallToSwap selected — tap another ball to swap colours, or tap #$selectedBallToSwap again to cancel."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedBallToSwap == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                )

                val ballRows = (1..15).chunked(5)
                ballRows.forEach { rowNumbers ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowNumbers.forEach { number ->
                            val paletteColor = ballSet.colorForBall(number)
                            val isSelectedForSwap = selectedBallToSwap == number
                            BallSwapCell(
                                ballNumber = number,
                                paletteColor = paletteColor,
                                isSelected = isSelectedForSwap,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val first = selectedBallToSwap
                                    if (first == null) {
                                        selectedBallToSwap = number
                                    } else if (first == number) {
                                        selectedBallToSwap = null
                                    } else {
                                        selectedBallToSwap = null
                                        onUpdateBallSet(ballSet.swapBallColors(first, number))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // ── 3. Table & Cloth Parameters ──────────────────────────────────────────────────────
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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Active Table & Cloth Parameters",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ParameterReadoutRow(
                    label = "Table",
                    value = table.name,
                )
                ParameterReadoutRow(
                    label = "Playing Surface",
                    value = "${formatFixed(table.lengthMeters, 2)} m × ${formatFixed(table.widthMeters, 2)} m " +
                        "(${formatFixed(table.lengthMeters * METERS_TO_FEET, 1)} ft × " +
                        "${formatFixed(table.widthMeters * METERS_TO_FEET, 1)} ft)",
                )
                ParameterReadoutRow(
                    label = "Ball Diameter",
                    value = "${formatFixed(table.ballRadiusMeters * 2.0 * METERS_TO_MM, 1)} mm " +
                        "(R = ${formatFixed(table.ballRadiusMeters, 4)} m)",
                )
                ParameterReadoutRow(
                    label = "Sliding Friction (μs)",
                    value = formatFixed(table.cloth.slidingFrictionMuS, 3),
                )
                ParameterReadoutRow(
                    label = "Rolling Resistance (μr)",
                    value = formatFixed(table.cloth.rollingResistanceMuR, 3),
                )
                ParameterReadoutRow(
                    label = "Rail Restitution (ec)",
                    value = formatFixed(table.cloth.cushionRestitutionEc, 3),
                )
            }
        }
    }
}

@Composable
private fun RatingSelectorRow(
    title: String,
    subtitle: String,
    selectedRating: Int,
    onSelectRating: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (rating in 1..5) {
                val isSelected = rating == selectedRating
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectRating(rating) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = rating.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BallSwapCell(
    ballNumber: Int,
    paletteColor: BallPaletteColor,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ballColor = paletteColor.toComposeColor()
    val isStripe = ballNumber in 9..15
    val borderColor = if (isSelected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(if (isSelected) 1.8.dp else 1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (isStripe) Color(0xFFF8FAFC) else ballColor)
                    .border(1.dp, Color(0xAA0F172A), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isStripe) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(16.dp)
                            .background(ballColor),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF8FAFC)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = ballNumber.toString(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A),
                    )
                }
            }
            Text(
                text = paletteColor.displayLabel(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ParameterReadoutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun inferSpeedRating(profile: PlayerProfile): Int =
    (1..5).minByOrNull { rating ->
        val candidate = PlayerProfile.fromQuestionnaire(
            experience = profile.experienceLevel,
            shaft = profile.shaftType,
            speedControlRating = rating,
            spinRating = 3,
        )
        abs(candidate.speedScaleMaxMps - profile.speedScaleMaxMps) +
            abs(candidate.speedScaleMinMps - profile.speedScaleMinMps)
    } ?: 3

private fun inferSpinRating(profile: PlayerProfile): Int =
    (1..5).minByOrNull { rating ->
        val candidate = PlayerProfile.fromQuestionnaire(
            experience = profile.experienceLevel,
            shaft = profile.shaftType,
            speedControlRating = 3,
            spinRating = rating,
        )
        abs(candidate.maxEffectiveDrawRatio - profile.maxEffectiveDrawRatio)
    } ?: 3

private fun ExperienceLevel.displayLabel(): String = when (this) {
    ExperienceLevel.BEGINNER -> "Beginner"
    ExperienceLevel.INTERMEDIATE -> "Intermediate"
    ExperienceLevel.ADVANCED -> "Advanced"
}

private fun ShaftType.displayLabel(): String = when (this) {
    ShaftType.STANDARD -> "Standard"
    ShaftType.LOW_DEFLECTION -> "Low-Deflection"
}

private fun BallPaletteColor.toComposeColor(): Color = ballPaletteToComposeColor(this)

private fun BallPaletteColor.displayLabel(): String = when (this) {
    BallPaletteColor.WHITE -> "White"
    BallPaletteColor.YELLOW -> "Yellow"
    BallPaletteColor.BLUE -> "Blue"
    BallPaletteColor.RED -> "Red"
    BallPaletteColor.PURPLE -> "Purple"
    BallPaletteColor.ORANGE -> "Orange"
    BallPaletteColor.GREEN -> "Green"
    BallPaletteColor.MAROON -> "Maroon"
    BallPaletteColor.BLACK -> "Black"
    BallPaletteColor.PINK -> "Pink"
    BallPaletteColor.BROWN -> "Brown"
}

private fun formatFixed(value: Double, decimals: Int): String {
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
