package com.runout.app.version

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.runout.app.legal.LegalMarkdown
import com.runout.app.ui.RunoutColors
import com.runout.app.ui.RunoutSpacing

/**
 * Scrollable in-app release notes screen showing the installed version and build number alongside
 * every release bundled from `CHANGELOG.md`.
 */
@Composable
fun WhatsNewScreen(
    versionName: String = AppVersion.versionName,
    versionCode: Int = AppVersion.versionCode,
    releases: List<ReleaseEntry> = AppVersion.releases,
) {
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(RunoutSpacing.Margin),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Margin),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = RunoutColors.SurfaceMid,
                border = BorderStroke(1.dp, RunoutColors.PrimaryCyan.copy(alpha = 0.45f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(RunoutSpacing.Card),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Micro)) {
                        Text(
                            text = "Runout v$versionName",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Installed build $versionCode • Offline release history",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = RunoutColors.PrimaryCyan.copy(alpha = 0.16f),
                    ) {
                        Text(
                            text = "Build $versionCode",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = RunoutColors.PrimaryCyan,
                        )
                    }
                }
            }

            if (releases.isEmpty()) {
                Text(
                    text = "No release notes are bundled with this build.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                releases.forEachIndexed { index, release ->
                    val isCurrent = release.version == versionName || (index == 0 && release.version.equals("v$versionName", ignoreCase = true))
                    ReleaseNotesCard(
                        release = release,
                        isCurrent = isCurrent,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseNotesCard(
    release: ReleaseEntry,
    isCurrent: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = RunoutColors.SurfaceLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (isCurrent) {
                RunoutColors.PrimaryCyan.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f)
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RunoutSpacing.Card),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "v${release.version}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isCurrent) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = RunoutColors.SuccessGreen.copy(alpha = 0.18f),
                        ) {
                            Text(
                                text = "Current",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = RunoutColors.SuccessGreen,
                            )
                        }
                    }
                }
                if (release.date != null) {
                    Text(
                        text = release.date,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            release.sections.forEachIndexed { secIdx, section ->
                if (secIdx > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
                val categoryColor = when (section.heading.lowercase()) {
                    "added" -> RunoutColors.SuccessGreen
                    "fixed" -> RunoutColors.WarningAmber
                    "improved", "changed" -> RunoutColors.PrimaryCyan
                    else -> MaterialTheme.colorScheme.secondary
                }
                Text(
                    text = section.heading.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = categoryColor,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    section.items.forEach { rawItem ->
                        val formatted = remember(rawItem) { formatChangelogInline(rawItem) }
                        Row(horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = categoryColor,
                            )
                            Text(
                                text = formatted,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatChangelogInline(text: String): AnnotatedString {
    val spans = LegalMarkdown.parseInline(text)
    return buildAnnotatedString {
        for (span in spans) {
            val style = when {
                span.code -> SpanStyle(fontFamily = FontFamily.Monospace)
                span.bold -> SpanStyle(fontWeight = FontWeight.Bold)
                else -> null
            }
            if (style != null) {
                withStyle(style) { append(span.text) }
            } else {
                append(span.text)
            }
        }
    }
}
