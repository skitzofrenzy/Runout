package com.runout.app.legal

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app bundles the documents from `docs/legal/` at build time. These tests read the originals from the
 * repository and fail if the bundled text or the copy duplicated in [LegalCopy] has drifted from them.
 */
class BundledLegalDocumentsMatchSourceTest {
    private fun legalDirectory(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/legal")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        error("docs/legal not found above ${File("").absolutePath}")
    }

    private fun read(relative: String): String =
        File(legalDirectory(), relative).readText(Charsets.UTF_8).replace("\r\n", "\n").trimEnd('\n')

    @Test
    fun bundledTextEqualsTheFilesInDocsLegal() {
        for (document in LegalDocument.entries) {
            assertEquals(read(document.path), BundledLegalSources.files.getValue(document.path).joinToString("\n"), document.path)
        }
    }

    @Test
    fun aboutSummaryMatchesTheReadmeParagraph() {
        val readme = read("README.md")
        val section = readme.substringAfter("### About / Legal summary")
        val quoted = section.lines().first { it.startsWith("> Runout is a free training aid") }.removePrefix("> ")
        assertEquals(quoted, LegalCopy.ABOUT_SUMMARY)
    }

    @Test
    fun acknowledgementCopyMatchesTheReadme() {
        val readme = read("README.md")
        val section = readme.substringAfter("### First-run acknowledgement").substringBefore("### About / Legal summary")
        assertTrue(section.contains("> **${LegalCopy.ACK_TITLE}**"))
        assertTrue(section.contains("> ${LegalCopy.ACK_PARAGRAPH_ONE}"))
        val agree = section.lines().first { it.startsWith("> By tapping") }
        assertEquals(
            "> ${LegalCopy.ACK_AGREE_PREFIX}**Terms of Use**${LegalCopy.ACK_AGREE_MIDDLE}**Privacy Policy**.",
            agree,
        )
    }
}
