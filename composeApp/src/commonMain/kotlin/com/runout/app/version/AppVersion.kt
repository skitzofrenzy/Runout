package com.runout.app.version

/** One categorized group of bullet points inside a [ReleaseEntry] (e.g. "Added", "Improved", "Fixed"). */
data class ReleaseSection(
    val heading: String,
    val items: List<String>,
)

/** One release entry parsed from `CHANGELOG.md`. */
data class ReleaseEntry(
    val version: String,
    val date: String?,
    val sections: List<ReleaseSection>,
)

/**
 * Bundled app version metadata and release notes generated at build time from `gradle.properties`
 * and root `CHANGELOG.md`.
 */
object AppVersion {
    val versionName: String
        get() = GeneratedAppVersion.VERSION_NAME

    val versionCode: Int
        get() = GeneratedAppVersion.VERSION_CODE

    val shortLabel: String
        get() = "v$versionName"

    val fullLabel: String
        get() = "v$versionName (Build $versionCode)"

    val changelogMarkdown: String by lazy {
        GeneratedAppVersion.CHANGELOG_LINES.joinToString("\n")
    }

    val releases: List<ReleaseEntry> by lazy {
        ChangelogParser.parse(changelogMarkdown)
    }
}

/**
 * Pure parser for Keep-a-Changelog Markdown (`## [x.y.z] - YYYY-MM-DD`, `### Category`, `- Item`).
 * Empty release entries (such as an empty `## [Unreleased]` heading) are omitted.
 */
object ChangelogParser {
    private val RELEASE_HEADING = Regex("""^##\s+\[?([^\]\s]+)\]?(?:\s*-\s*(.+))?$""")

    fun parse(markdown: String): List<ReleaseEntry> {
        val releases = mutableListOf<ReleaseEntry>()
        var currentVersion: String? = null
        var currentDate: String? = null
        var currentSections = mutableListOf<ReleaseSection>()
        var currentSectionHeading: String? = null
        var currentItems = mutableListOf<String>()

        fun flushSection() {
            val heading = currentSectionHeading
            if (heading != null && currentItems.isNotEmpty()) {
                currentSections += ReleaseSection(heading = heading, items = currentItems.toList())
            }
            currentSectionHeading = null
            currentItems = mutableListOf()
        }

        fun flushRelease() {
            flushSection()
            val version = currentVersion
            if (version != null && currentSections.isNotEmpty()) {
                releases += ReleaseEntry(
                    version = version,
                    date = currentDate,
                    sections = currentSections.toList(),
                )
            }
            currentVersion = null
            currentDate = null
            currentSections = mutableListOf()
        }

        for (rawLine in markdown.replace("\r\n", "\n").split('\n')) {
            val trimmed = rawLine.trim()
            when {
                trimmed.startsWith("## ") -> {
                    flushRelease()
                    val match = RELEASE_HEADING.matchEntire(trimmed)
                    if (match != null) {
                        currentVersion = match.groupValues[1].trim()
                        currentDate = match.groupValues[2].trim().ifEmpty { null }
                    }
                }

                trimmed.startsWith("### ") && currentVersion != null -> {
                    flushSection()
                    currentSectionHeading = trimmed.removePrefix("### ").trim()
                }

                (trimmed.startsWith("- ") || trimmed.startsWith("* ")) && currentVersion != null -> {
                    if (currentSectionHeading == null) {
                        currentSectionHeading = "Changes"
                    }
                    val itemText = trimmed.substring(2).trim()
                    if (itemText.isNotEmpty()) {
                        currentItems += itemText
                    }
                }
            }
        }
        flushRelease()
        return releases
    }
}
