package com.runout.app.legal

import com.runout.app.storage.isTermsAcceptanceCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegalMarkdownTest {
    @Test
    fun headingsParagraphsAndBulletsBecomeBlocks() {
        val blocks = LegalMarkdown.parse(
            """
            # Title

            First line
            continues here.

            ## Section

            - one
            - two with **bold**
            """.trimIndent(),
        )
        assertEquals(4, blocks.size)
        val h1 = assertIs<MarkdownBlock.Heading>(blocks[0])
        assertEquals(1, h1.level)
        assertEquals("Title", h1.spans.single().text)
        val para = assertIs<MarkdownBlock.Paragraph>(blocks[1])
        assertEquals("First line continues here.", para.spans.single().text)
        assertEquals(2, assertIs<MarkdownBlock.Heading>(blocks[2]).level)
        val list = assertIs<MarkdownBlock.BulletList>(blocks[3])
        assertEquals(2, list.items.size)
        assertTrue(list.items[1].any { it.bold && it.text == "bold" })
    }

    @Test
    fun inlineLinksAutolinksAndCodeAreRecognised() {
        val spans = LegalMarkdown.parse("See [the policy](privacy-policy.md), <https://example.org/x> and `LICENSE`.")
            .filterIsInstance<MarkdownBlock.Paragraph>().single().spans
        val link = spans.first { it.target == "privacy-policy.md" }
        assertEquals("the policy", link.text)
        assertTrue(spans.any { it.target == "https://example.org/x" && it.text == "https://example.org/x" })
        assertTrue(spans.any { it.code && it.text == "LICENSE" })
    }

    @Test
    fun unmatchedMarkersStayAsLiteralText() {
        val spans = LegalMarkdown.parseInline("2 ** 3 and [not a link] and a ` tick")
        assertEquals("2 ** 3 and [not a link] and a ` tick", spans.joinToString("") { it.text })
        assertTrue(spans.none { it.bold || it.code || it.target != null })
    }

    @Test
    fun pipeTableKeepsHeaderAndRows() {
        val table = LegalMarkdown.parse(
            """
            | Name | Use |
            |---|---|
            | Alpha | First |
            | Beta | Second |
            """.trimIndent(),
        ).single()
        val parsed = assertIs<MarkdownBlock.Table>(table)
        assertEquals(listOf("Name", "Use"), parsed.header.map { it.single().text })
        assertEquals(2, parsed.rows.size)
        assertEquals("Beta", parsed.rows[1][0].single().text)
    }

    @Test
    fun bulletsAndHeadingsEndAParagraphWithoutBlankLine() {
        val blocks = LegalMarkdown.parse("Intro\n- item\n## Next")
        assertEquals(3, blocks.size)
        assertIs<MarkdownBlock.Paragraph>(blocks[0])
        assertIs<MarkdownBlock.BulletList>(blocks[1])
        assertIs<MarkdownBlock.Heading>(blocks[2])
    }

    @Test
    fun hashWithoutSpaceIsNotAHeading() {
        assertIs<MarkdownBlock.Paragraph>(LegalMarkdown.parse("#hashtag").single())
    }
}

class LegalConfigTest {
    @Test
    fun unsetValuesLeaveThePlaceholdersVisible() {
        val text = "Made by [Developer name]. Effective [Effective date]."
        assertEquals(text, LegalConfig.applyPlaceholders(text, developerName = null, effectiveDate = null))
        assertEquals(text, LegalConfig.applyPlaceholders(text, developerName = " ", effectiveDate = ""))
    }

    @Test
    fun suppliedValuesReplaceEveryOccurrence() {
        val text = "[Developer name] and [Developer name], on [Effective date]"
        assertEquals(
            "A B and A B, on 1 May",
            LegalConfig.applyPlaceholders(text, developerName = "A B", effectiveDate = "1 May"),
        )
    }

    @Test
    fun termsVersionIsSet() {
        assertTrue(LegalConfig.TERMS_VERSION.isNotBlank())
    }

    @Test
    fun termsAcceptanceIsOnlyCurrentForTheSameVersion() {
        assertFalse(isTermsAcceptanceCurrent(null, "2"))
        assertFalse(isTermsAcceptanceCurrent("1", "2"))
        assertTrue(isTermsAcceptanceCurrent("2", "2"))
    }
}

class LegalDocumentsTest {
    @Test
    fun everyDocumentHasBundledText() {
        for (document in LegalDocument.entries) {
            assertTrue(document.text().isNotBlank(), "${document.path} is empty")
        }
    }

    @Test
    fun relativeLinksInsideDocumentsResolveToBundledDocuments() {
        assertEquals(LegalDocument.PRIVACY_POLICY, LegalDocument.fromLinkTarget("privacy-policy.md"))
        assertEquals(LegalDocument.APACHE_LICENSE, LegalDocument.fromLinkTarget("licenses/Apache-2.0.txt"))
        assertNull(LegalDocument.fromLinkTarget("https://example.org"))
    }

    @Test
    fun everyMarkdownLinkToALocalFileResolvesToABundledDocument() {
        for (document in LegalDocument.entries.filter { it.format == LegalDocumentFormat.MARKDOWN }) {
            val targets = LegalMarkdown.parse(document.text()).flatMap { block ->
                when (block) {
                    is MarkdownBlock.Heading -> block.spans
                    is MarkdownBlock.Paragraph -> block.spans
                    is MarkdownBlock.BulletList -> block.items.flatten()
                    is MarkdownBlock.Table -> block.rows.flatten().flatten()
                }
            }.mapNotNull { it.target }
            for (target in targets.filter { !it.contains("://") && !it.startsWith("mailto:") }) {
                assertNotNull(LegalDocument.fromLinkTarget(target), "${document.path} links to $target")
            }
        }
    }
}
