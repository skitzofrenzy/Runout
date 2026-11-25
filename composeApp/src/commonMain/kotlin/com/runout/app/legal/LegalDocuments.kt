package com.runout.app.legal

/** How a bundled document is laid out: Markdown is parsed into blocks, plain text is shown as written. */
enum class LegalDocumentFormat { MARKDOWN, PLAIN_TEXT }

/**
 * A legal document bundled with the app. [path] is relative to `docs/legal/`, which is where the text comes from.
 */
enum class LegalDocument(
    val title: String,
    val path: String,
    val format: LegalDocumentFormat,
) {
    TERMS_OF_USE("Terms of Use", "terms-of-use.md", LegalDocumentFormat.MARKDOWN),
    PRIVACY_POLICY("Privacy Policy", "privacy-policy.md", LegalDocumentFormat.MARKDOWN),
    THIRD_PARTY_NOTICES("Third-party notices", "third-party-notices.md", LegalDocumentFormat.MARKDOWN),
    APACHE_LICENSE("Apache License 2.0", "licenses/Apache-2.0.txt", LegalDocumentFormat.PLAIN_TEXT),
    SKIA_LICENSE("BSD 3-Clause License (Skia)", "licenses/BSD-3-Clause-Skia.txt", LegalDocumentFormat.PLAIN_TEXT),
    ;

    /** The document text with the developer name and effective date filled in where they are configured. */
    fun text(): String {
        val lines = BundledLegalSources.files.getValue(path)
        return LegalConfig.applyPlaceholders(lines.joinToString("\n"))
    }

    companion object {
        /** Resolves a relative link used inside the documents, such as `privacy-policy.md`, to a bundled document. */
        fun fromLinkTarget(target: String): LegalDocument? {
            val normalised = target.removePrefix("./")
            return entries.firstOrNull { it.path == normalised }
        }
    }
}

/** The copy shown on the acknowledgement and About screens. It mirrors `docs/legal/README.md`. */
object LegalCopy {
    const val ACK_TITLE: String = "Before you start"

    const val ACK_PARAGRAPH_ONE: String =
        "Runout is a training aid. Its shot lines, speeds and plans are estimates, not guarantees, so please check " +
            "anything it shows you before you rely on it. Please do not use it where electronic aids are not allowed."

    const val ACK_AGREE_PREFIX: String = "By tapping \"I agree\" you accept the "
    const val ACK_AGREE_MIDDLE: String = " and confirm you have read the "

    const val ACK_AGREE_BUTTON: String = "I agree"
    const val ACK_NOT_NOW_BUTTON: String = "Not now"
    const val ACK_DECLINED_NOTE: String =
        "Runout can't be used without agreeing. Tap \"I agree\" when you are ready to continue."

    const val ABOUT_SUMMARY: String =
        "Runout is a free training aid made by Nexova Digital and published as open source under the MIT License. " +
            "Its shot lines, speeds and plans are estimates and come with no guarantee, so check anything it shows " +
            "you before you rely on it. Runout does not collect your information: nothing leaves your device and " +
            "there are no accounts, adverts or analytics. Rules summaries are original summaries, not the official " +
            "rules. Runout is not affiliated with any governing body, league, tour or equipment maker. Read the " +
            "Terms of Use, Privacy Policy and Third-party notices from the links below. Questions: " +
            "nexovadigital.info@gmail.com."
}
