package com.runout.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Spatial grid tokens on an 8 dp rhythm with a 3:1 section-to-content proximity ratio.
 */
object RunoutSpacing {
    val Micro = 4.dp
    val Element = 8.dp
    val Card = 12.dp
    val Margin = 16.dp
    val Section = 24.dp
    val Hero = 32.dp
    val MinTouchTarget = 48.dp
}

/**
 * Dark-first pool-hall colour palette meeting WCAG AA text contrast on dark navy surfaces.
 */
object RunoutColors {
    val Background = Color(0xFF0B1120)
    val SurfaceLow = Color(0xFF111827)
    val SurfaceMid = Color(0xFF172033)
    val SurfaceHigh = Color(0xFF1E293B)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val PrimaryCyan = Color(0xFF38BDF8)
    val OnPrimary = Color(0xFF082F49)
    val SuccessGreen = Color(0xFF4ADE80)
    val OnSuccess = Color(0xFF052E16)
    val WarningAmber = Color(0xFFFBBF24)
    val DangerCoral = Color(0xFFF87171)
}

val RunoutDarkColorScheme = darkColorScheme(
    primary = RunoutColors.PrimaryCyan,
    onPrimary = RunoutColors.OnPrimary,
    secondary = RunoutColors.SuccessGreen,
    onSecondary = RunoutColors.OnSuccess,
    tertiary = RunoutColors.WarningAmber,
    error = RunoutColors.DangerCoral,
    background = RunoutColors.Background,
    onBackground = RunoutColors.TextPrimary,
    surface = RunoutColors.SurfaceLow,
    onSurface = RunoutColors.TextPrimary,
    surfaceVariant = RunoutColors.SurfaceHigh,
    onSurfaceVariant = RunoutColors.TextSecondary,
    outlineVariant = RunoutColors.SurfaceHigh,
)

/**
 * Two-level progressive disclosure complexity mode across planning and photo review flows.
 */
enum class AppDisplayMode(val label: String) {
    SIMPLE("Simple"),
    PRO("Pro"),
}

/**
 * Cross-platform vector glyphs drawn directly on Compose Canvas without external icon packs.
 */
enum class RunoutIcon {
    HOME,
    PLAN,
    LEARN,
    RULES,
    MENU,
    BACK,
    CAMERA,
    GALLERY,
    MORE,
    CHEVRON_DOWN,
    CHEVRON_UP,
    INFO,
    TUNE,
    PROFILE,
    RESET,
    CHECK,
    SNOOKER,
    SPEED,
    TIMER,
    TABLE,
}

@Composable
fun RunoutVectorIcon(
    icon: RunoutIcon,
    tint: Color,
    modifier: Modifier = Modifier.size(22.dp),
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.095f
        val strokeStyle = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (icon) {
            RunoutIcon.HOME -> {
                val roof = Path().apply {
                    moveTo(w * 0.14f, h * 0.48f)
                    lineTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.86f, h * 0.48f)
                }
                drawPath(roof, color = tint, style = strokeStyle)
                val body = Path().apply {
                    moveTo(w * 0.24f, h * 0.46f)
                    lineTo(w * 0.24f, h * 0.84f)
                    lineTo(w * 0.76f, h * 0.84f)
                    lineTo(w * 0.76f, h * 0.46f)
                }
                drawPath(body, color = tint, style = strokeStyle)
            }

            RunoutIcon.PLAN -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.22f),
                    size = Size(w * 0.76f, h * 0.56f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = strokeStyle,
                )
                drawCircle(color = tint, radius = w * 0.08f, center = Offset(w * 0.34f, h * 0.62f))
                drawCircle(color = tint, radius = w * 0.08f, center = Offset(w * 0.66f, h * 0.38f))
                drawLine(tint, Offset(w * 0.38f, h * 0.57f), Offset(w * 0.62f, h * 0.43f), strokeWidth = stroke * 0.85f, cap = StrokeCap.Round)
            }

            RunoutIcon.LEARN -> {
                drawCircle(color = tint, radius = w * 0.37f, center = Offset(w * 0.5f, h * 0.5f), style = strokeStyle)
                val play = Path().apply {
                    moveTo(w * 0.42f, h * 0.34f)
                    lineTo(w * 0.68f, h * 0.50f)
                    lineTo(w * 0.42f, h * 0.66f)
                    close()
                }
                drawPath(play, color = tint)
            }

            RunoutIcon.RULES -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.20f, h * 0.15f),
                    size = Size(w * 0.60f, h * 0.70f),
                    cornerRadius = CornerRadius(w * 0.07f, w * 0.07f),
                    style = strokeStyle,
                )
                drawLine(tint, Offset(w * 0.32f, h * 0.35f), Offset(w * 0.68f, h * 0.35f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.52f), Offset(w * 0.68f, h * 0.52f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, h * 0.68f), Offset(w * 0.55f, h * 0.68f), strokeWidth = stroke, cap = StrokeCap.Round)
            }

            RunoutIcon.MENU -> {
                drawLine(tint, Offset(w * 0.18f, h * 0.28f), Offset(w * 0.82f, h * 0.28f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.18f, h * 0.50f), Offset(w * 0.82f, h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.18f, h * 0.72f), Offset(w * 0.82f, h * 0.72f), strokeWidth = stroke, cap = StrokeCap.Round)
            }

            RunoutIcon.BACK -> {
                drawLine(tint, Offset(w * 0.78f, h * 0.50f), Offset(w * 0.24f, h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.45f, h * 0.26f)
                    lineTo(w * 0.22f, h * 0.50f)
                    lineTo(w * 0.45f, h * 0.74f)
                }
                drawPath(head, color = tint, style = strokeStyle)
            }

            RunoutIcon.CAMERA -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.14f, h * 0.28f),
                    size = Size(w * 0.72f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                    style = strokeStyle,
                )
                val bump = Path().apply {
                    moveTo(w * 0.34f, h * 0.28f)
                    lineTo(w * 0.40f, h * 0.18f)
                    lineTo(w * 0.60f, h * 0.18f)
                    lineTo(w * 0.66f, h * 0.28f)
                }
                drawPath(bump, color = tint, style = strokeStyle)
                drawCircle(color = tint, radius = w * 0.15f, center = Offset(w * 0.50f, h * 0.54f), style = strokeStyle)
            }

            RunoutIcon.GALLERY -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.15f, h * 0.18f),
                    size = Size(w * 0.70f, h * 0.64f),
                    cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                    style = strokeStyle,
                )
                drawCircle(color = tint, radius = w * 0.07f, center = Offset(w * 0.34f, h * 0.36f))
                val peaks = Path().apply {
                    moveTo(w * 0.22f, h * 0.74f)
                    lineTo(w * 0.44f, h * 0.52f)
                    lineTo(w * 0.58f, h * 0.64f)
                    lineTo(w * 0.70f, h * 0.46f)
                    lineTo(w * 0.80f, h * 0.74f)
                }
                drawPath(peaks, color = tint, style = strokeStyle)
            }

            RunoutIcon.MORE -> {
                drawCircle(color = tint, radius = w * 0.07f, center = Offset(w * 0.50f, h * 0.24f))
                drawCircle(color = tint, radius = w * 0.07f, center = Offset(w * 0.50f, h * 0.50f))
                drawCircle(color = tint, radius = w * 0.07f, center = Offset(w * 0.50f, h * 0.76f))
            }

            RunoutIcon.CHEVRON_DOWN -> {
                val p = Path().apply {
                    moveTo(w * 0.24f, h * 0.38f)
                    lineTo(w * 0.50f, h * 0.64f)
                    lineTo(w * 0.76f, h * 0.38f)
                }
                drawPath(p, color = tint, style = strokeStyle)
            }

            RunoutIcon.CHEVRON_UP -> {
                val p = Path().apply {
                    moveTo(w * 0.24f, h * 0.62f)
                    lineTo(w * 0.50f, h * 0.36f)
                    lineTo(w * 0.76f, h * 0.62f)
                }
                drawPath(p, color = tint, style = strokeStyle)
            }

            RunoutIcon.INFO -> {
                drawCircle(color = tint, radius = w * 0.38f, center = Offset(w * 0.50f, h * 0.50f), style = strokeStyle)
                drawCircle(color = tint, radius = w * 0.055f, center = Offset(w * 0.50f, h * 0.32f))
                drawLine(tint, Offset(w * 0.50f, h * 0.46f), Offset(w * 0.50f, h * 0.70f), strokeWidth = stroke, cap = StrokeCap.Round)
            }

            RunoutIcon.TUNE -> {
                drawLine(tint, Offset(w * 0.16f, h * 0.32f), Offset(w * 0.84f, h * 0.32f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(color = tint, radius = w * 0.09f, center = Offset(w * 0.36f, h * 0.32f))
                drawLine(tint, Offset(w * 0.16f, h * 0.68f), Offset(w * 0.84f, h * 0.68f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(color = tint, radius = w * 0.09f, center = Offset(w * 0.64f, h * 0.68f))
            }

            RunoutIcon.PROFILE -> {
                drawCircle(color = tint, radius = w * 0.16f, center = Offset(w * 0.50f, h * 0.34f), style = strokeStyle)
                val arc = Path().apply {
                    moveTo(w * 0.22f, h * 0.80f)
                    lineTo(w * 0.32f, h * 0.62f)
                    lineTo(w * 0.68f, h * 0.62f)
                    lineTo(w * 0.78f, h * 0.80f)
                }
                drawPath(arc, color = tint, style = strokeStyle)
            }

            RunoutIcon.RESET -> {
                val p = Path().apply {
                    moveTo(w * 0.26f, h * 0.36f)
                    lineTo(w * 0.48f, h * 0.20f)
                    lineTo(w * 0.76f, h * 0.34f)
                    lineTo(w * 0.76f, h * 0.66f)
                    lineTo(w * 0.50f, h * 0.80f)
                    lineTo(w * 0.26f, h * 0.64f)
                }
                drawPath(p, color = tint, style = strokeStyle)
                drawLine(tint, Offset(w * 0.20f, h * 0.20f), Offset(w * 0.26f, h * 0.38f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.42f, h * 0.36f), Offset(w * 0.26f, h * 0.38f), strokeWidth = stroke, cap = StrokeCap.Round)
            }

            RunoutIcon.CHECK -> {
                val p = Path().apply {
                    moveTo(w * 0.22f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.72f)
                    lineTo(w * 0.78f, h * 0.30f)
                }
                drawPath(p, color = tint, style = strokeStyle)
            }

            RunoutIcon.SNOOKER -> {
                drawCircle(color = tint, radius = w * 0.16f, center = Offset(w * 0.50f, h * 0.26f), style = strokeStyle)
                drawCircle(color = tint, radius = w * 0.16f, center = Offset(w * 0.30f, h * 0.66f), style = strokeStyle)
                drawCircle(color = tint, radius = w * 0.16f, center = Offset(w * 0.70f, h * 0.66f), style = strokeStyle)
            }

            RunoutIcon.SPEED -> {
                val gauge = Path().apply {
                    moveTo(w * 0.18f, h * 0.68f)
                    lineTo(w * 0.28f, h * 0.36f)
                    lineTo(w * 0.50f, h * 0.24f)
                    lineTo(w * 0.72f, h * 0.36f)
                    lineTo(w * 0.82f, h * 0.68f)
                }
                drawPath(gauge, color = tint, style = strokeStyle)
                drawLine(tint, Offset(w * 0.50f, h * 0.66f), Offset(w * 0.68f, h * 0.42f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(color = tint, radius = w * 0.07f, center = Offset(w * 0.50f, h * 0.68f))
            }

            RunoutIcon.TIMER -> {
                drawCircle(color = tint, radius = w * 0.34f, center = Offset(w * 0.50f, h * 0.55f), style = strokeStyle)
                drawLine(tint, Offset(w * 0.40f, h * 0.15f), Offset(w * 0.60f, h * 0.15f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.55f), Offset(w * 0.50f, h * 0.35f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.50f, h * 0.55f), Offset(w * 0.66f, h * 0.63f), strokeWidth = stroke, cap = StrokeCap.Round)
            }

            RunoutIcon.TABLE -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.12f, h * 0.24f),
                    size = Size(w * 0.76f, h * 0.52f),
                    cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
                    style = strokeStyle,
                )
                drawLine(tint, Offset(w * 0.50f, h * 0.24f), Offset(w * 0.50f, h * 0.76f), strokeWidth = stroke * 0.7f)
            }
        }
    }
}

/**
 * Tier-2 section heading enforcing crisp hierarchy and clean 8 dp spacing to the content below.
 */
@Composable
fun RunoutSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (actionLabel != null && onActionClick != null) {
            TextButton(
                onClick = onActionClick,
                modifier = Modifier.defaultMinSize(minWidth = RunoutSpacing.MinTouchTarget, minHeight = RunoutSpacing.MinTouchTarget),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Progressive-disclosure accordion card with a 48 dp+ tappable summary row and smooth expansion.
 */
@Composable
fun CollapsibleSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    badgeText: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = RunoutSpacing.Margin, vertical = RunoutSpacing.Card),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                    if (!summary.isNullOrBlank()) {
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!badgeText.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        ) {
                            Text(
                                text = badgeText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    RunoutVectorIcon(
                        icon = if (expanded) RunoutIcon.CHEVRON_UP else RunoutIcon.CHEVRON_DOWN,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.50f),
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(RunoutSpacing.Margin),
                        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
                        content = content,
                    )
                }
            }
        }
    }
}

/**
 * Tap-to-reveal glossary helper meeting the 48 dp minimum touch target (never relies on hover).
 */
@Composable
fun GlossaryInfoButton(
    term: String,
    explanation: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = RunoutSpacing.MinTouchTarget, minHeight = RunoutSpacing.MinTouchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        RunoutVectorIcon(
            icon = RunoutIcon.INFO,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = {
                Text(
                    text = term,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text("Got it")
                }
            },
        )
    }
}

/**
 * Compact segmented mode pill switching between Simple and Pro UI disclosure levels.
 */
@Composable
fun SimpleProTogglePill(
    mode: AppDisplayMode,
    onModeChange: (AppDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 40.dp),
        shape = RoundedCornerShape(999.dp),
        color = RunoutColors.SurfaceMid,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppDisplayMode.entries.forEach { item ->
                val selected = item == mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        )
                        .clickable(role = Role.Tab) { onModeChange(item) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
