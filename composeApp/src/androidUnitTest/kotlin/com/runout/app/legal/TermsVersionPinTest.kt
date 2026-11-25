package com.runout.app.legal

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ties [LegalConfig.TERMS_VERSION] to the text of `docs/legal/terms-of-use.md`. The acknowledgement is shown
 * again only when the version changes, so a change to the terms without a new version would leave people
 * bound to text they never saw.
 */
class TermsVersionPinTest {
    private fun termsText(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/legal/terms-of-use.md")
            if (candidate.isFile) {
                return candidate.readText(Charsets.UTF_8).replace("\r\n", "\n").trimEnd('\n')
            }
            dir = dir.parentFile
        }
        error("docs/legal/terms-of-use.md not found above ${File("").absolutePath}")
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun termsTextMatchesThePinnedVersion() {
        val actual = sha256(termsText())
        assertEquals(
            PINNED_TERMS_SHA256,
            actual,
            "docs/legal/terms-of-use.md changed (hash $actual) but the pin for TERMS_VERSION " +
                "\"${LegalConfig.TERMS_VERSION}\" did not. If the change is material, bump " +
                "LegalConfig.TERMS_VERSION so people are asked to agree again; then set " +
                "PINNED_TERMS_VERSION and PINNED_TERMS_SHA256 in this test to the new values.",
        )
        assertEquals(
            PINNED_TERMS_VERSION,
            LegalConfig.TERMS_VERSION,
            "LegalConfig.TERMS_VERSION changed; update PINNED_TERMS_VERSION and PINNED_TERMS_SHA256 here to match " +
                "the terms text it now stands for.",
        )
    }

    private companion object {
        /** The terms version the hash below belongs to. */
        const val PINNED_TERMS_VERSION = "1"

        /**
         * SHA-256 of the terms text with line endings normalised to LF and trailing newlines removed.
         * To recompute: `printf '%s' "$(tr -d '\r' < docs/legal/terms-of-use.md)" | sha256sum`.
         */
        const val PINNED_TERMS_SHA256 = "6f24a0c7489d22a917460e7d30b4c2d0a99a763908eb6185bd6fd043f599b19d"
    }
}
