package com.runout.app.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.runout.app.ui.RunoutColors
import com.runout.app.ui.RunoutIcon
import com.runout.app.ui.RunoutSpacing
import com.runout.app.ui.RunoutVectorIcon

/**
 * Full-screen "Before you start" notice shown before setup until the person agrees to the current terms.
 *
 * The screen cannot be dismissed from outside: the only ways forward are [onAgree], or reading a document and
 * coming back. "Not now" keeps the person here with a short explanation, because the app cannot be used without
 * agreeing.
 */
@Composable
fun TermsAcknowledgementScreen(onAgree: () -> Unit) {
    var openDocument by remember { mutableStateOf<LegalDocument?>(null) }
    var declined by remember { mutableStateOf(false) }

    val reading = openDocument
    if (reading != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = RunoutSpacing.Element),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { openDocument = null },
                    modifier = Modifier.defaultMinSize(RunoutSpacing.MinTouchTarget, RunoutSpacing.MinTouchTarget),
                ) {
                    RunoutVectorIcon(icon = RunoutIcon.BACK, tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = reading.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            LegalDocumentScreen(document = reading, onOpenDocument = { openDocument = it })
        }
        return
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(RunoutSpacing.Margin),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Margin),
        ) {
            Text(
                text = LegalCopy.ACK_TITLE,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = LegalCopy.ACK_PARAGRAPH_ONE,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = acknowledgementAgreementText(
                    onOpenTerms = { openDocument = LegalDocument.TERMS_OF_USE },
                    onOpenPrivacy = { openDocument = LegalDocument.PRIVACY_POLICY },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                TextButton(
                    onClick = { openDocument = LegalDocument.TERMS_OF_USE },
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                ) { Text("Terms of Use") }
                TextButton(
                    onClick = { openDocument = LegalDocument.PRIVACY_POLICY },
                    modifier = Modifier.defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                ) { Text("Privacy Policy") }
            }

            Button(
                onClick = onAgree,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RunoutColors.PrimaryCyan,
                    contentColor = RunoutColors.OnPrimary,
                ),
            ) {
                Text(LegalCopy.ACK_AGREE_BUTTON, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { declined = true },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
            ) {
                Text(LegalCopy.ACK_NOT_NOW_BUTTON)
            }
            if (declined) {
                Text(
                    text = LegalCopy.ACK_DECLINED_NOTE,
                    style = MaterialTheme.typography.bodyMedium,
                    color = RunoutColors.WarningAmber,
                )
            }
        }
    }
}

@Composable
private fun acknowledgementAgreementText(onOpenTerms: () -> Unit, onOpenPrivacy: () -> Unit): AnnotatedString {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = RunoutColors.PrimaryCyan,
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline,
        ),
    )
    return buildAnnotatedString {
        append(LegalCopy.ACK_AGREE_PREFIX)
        withLink(LinkAnnotation.Clickable("terms", linkStyles) { onOpenTerms() }) {
            append(LegalDocument.TERMS_OF_USE.title)
        }
        append(LegalCopy.ACK_AGREE_MIDDLE)
        withLink(LinkAnnotation.Clickable("privacy", linkStyles) { onOpenPrivacy() }) {
            append(LegalDocument.PRIVACY_POLICY.title)
        }
        append(".")
    }
}

/**
 * The About / Legal screen: the plain-language summary and a list of the bundled documents. Reading a document
 * is hosted by the caller so back navigation returns here.
 */
@Composable
fun AboutLegalScreen(onOpenDocument: (LegalDocument) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(RunoutSpacing.Margin),
        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Margin),
    ) {
        SelectionContainer {
            Text(
                text = LegalConfig.applyPlaceholders(LegalCopy.ABOUT_SUMMARY),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "Documents",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        LegalDocument.entries.forEach { document ->
            TextButton(
                onClick = { onOpenDocument(document) },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = RunoutSpacing.MinTouchTarget),
            ) {
                Text(
                    text = document.title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** Scrollable reader for one bundled document. Relative links to other bundled documents open through [onOpenDocument]. */
@Composable
fun LegalDocumentScreen(document: LegalDocument, onOpenDocument: (LegalDocument) -> Unit) {
    val text = remember(document) { document.text() }
    val blocks = remember(document, text) {
        if (document.format == LegalDocumentFormat.MARKDOWN) LegalMarkdown.parse(text) else emptyList()
    }
    SelectionContainer {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(RunoutSpacing.Margin),
            verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card),
        ) {
            if (document.format == LegalDocumentFormat.PLAIN_TEXT) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                blocks.forEach { block -> MarkdownBlockView(block, onOpenDocument) }
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, onOpenDocument: (LegalDocument) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    when (block) {
        is MarkdownBlock.Heading -> Text(
            text = inlineText(block.spans, onOpenDocument),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.Bold,
            color = onSurface,
            modifier = Modifier.padding(top = if (block.level == 1) 0.dp else RunoutSpacing.Element),
        )

        is MarkdownBlock.Paragraph -> Text(
            text = inlineText(block.spans, onOpenDocument),
            style = MaterialTheme.typography.bodyLarge,
            color = onSurface,
        )

        is MarkdownBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
            block.items.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(RunoutSpacing.Element)) {
                    Text("•", style = MaterialTheme.typography.bodyLarge, color = onSurface)
                    Text(
                        text = inlineText(item, onOpenDocument),
                        style = MaterialTheme.typography.bodyLarge,
                        color = onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Wide tables do not fit a phone, so each row becomes a small card: the first column as its title, the
        // other columns as "Heading: value" lines.
        is MarkdownBlock.Table -> Column(verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Card)) {
            block.rows.forEach { row ->
                Surface(color = RunoutColors.SurfaceMid, shape = MaterialTheme.shapes.medium) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(RunoutSpacing.Card),
                        verticalArrangement = Arrangement.spacedBy(RunoutSpacing.Micro),
                    ) {
                        Text(
                            text = inlineText(row.firstOrNull().orEmpty(), onOpenDocument),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = onSurface,
                        )
                        for (column in 1 until row.size) {
                            val heading = block.header.getOrNull(column)?.joinToString("") { it.text }.orEmpty()
                            val value = inlineText(row[column], onOpenDocument)
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$heading: ") }
                                    append(value)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Builds styled text for [spans]. Links to bundled documents are tappable; every other link is shown as plain text
 * with its address, because the app does not open external links.
 */
@Composable
private fun inlineText(spans: List<InlineSpan>, onOpenDocument: (LegalDocument) -> Unit): AnnotatedString {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(color = RunoutColors.PrimaryCyan, textDecoration = TextDecoration.Underline),
    )
    return buildAnnotatedString {
        for (span in spans) {
            val weight = if (span.bold) FontWeight.Bold else null
            val family = if (span.code) FontFamily.Monospace else null
            val target = span.target
            val internal = target?.let { LegalDocument.fromLinkTarget(it) }
            when {
                internal != null -> withLink(LinkAnnotation.Clickable(internal.path, linkStyles) { onOpenDocument(internal) }) {
                    withStyle(SpanStyle(fontWeight = weight)) { append(span.text) }
                }

                target != null -> withStyle(SpanStyle(fontWeight = weight, fontFamily = family)) {
                    val shown = target.removePrefix("mailto:")
                    append(if (span.text == target || span.text == shown) shown else "${span.text} ($shown)")
                }

                else -> withStyle(SpanStyle(fontWeight = weight, fontFamily = family)) { append(span.text) }
            }
        }
    }
}
