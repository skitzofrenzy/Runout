package com.runout.app.legal

/**
 * The few facts the legal documents leave as bracketed placeholders, plus the version of the terms.
 *
 * Set [DEVELOPER_NAME] and [EFFECTIVE_DATE] once and every document shown in the app picks them up. While a value
 * is null the bracketed placeholder stays visible, so the app never shows a name or date that was not supplied.
 */
object LegalConfig {
    /** The developer's name as it should appear in the documents, or null while it is not set. */
    val DEVELOPER_NAME: String? = "Nexova Digital"

    /** The proprietor's legal name as the documents must show it, or null while it is not set. */
    val PROPRIETOR_LEGAL_NAME: String? = null

    /** The effective date of the current terms, or null while it is not set. */
    val EFFECTIVE_DATE: String? = null

    /**
     * Identifies the current Terms of Use. It is stored on the device when the person agrees, and the
     * acknowledgement appears again whenever the stored value differs. Change it when the terms change materially.
     */
    const val TERMS_VERSION: String = "1"

    const val DEVELOPER_NAME_PLACEHOLDER: String = "[Developer name]"
    const val EFFECTIVE_DATE_PLACEHOLDER: String = "[Effective date]"
    const val PROPRIETOR_LEGAL_NAME_PLACEHOLDER: String = "[Proprietor's legal name]"

    /** Replaces the placeholders in [text] with the configured values; unset values are left as they are. */
    fun applyPlaceholders(
        text: String,
        developerName: String? = DEVELOPER_NAME,
        effectiveDate: String? = EFFECTIVE_DATE,
        proprietorLegalName: String? = PROPRIETOR_LEGAL_NAME,
    ): String {
        var result = text
        if (!developerName.isNullOrBlank()) result = result.replace(DEVELOPER_NAME_PLACEHOLDER, developerName)
        if (!effectiveDate.isNullOrBlank()) result = result.replace(EFFECTIVE_DATE_PLACEHOLDER, effectiveDate)
        if (!proprietorLegalName.isNullOrBlank()) {
            result = result.replace(PROPRIETOR_LEGAL_NAME_PLACEHOLDER, proprietorLegalName)
        }
        return result
    }
}
