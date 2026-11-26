package com.runout.app.version

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangelogParserTest {
    @Test
    fun parsesReleasesSectionsDatesAndSkipsEmptyUnreleased() {
        val md = """
            # Changelog

            ## [Unreleased]

            ## [0.2.0] - 2026-11-25

            ### Added
            - **Feature A:** First item.
            - Second item.

            ### Fixed
            * Bug fix with asterisk bullet.

            ## [0.1.0] - 2026-11-17
            - Initial release without subsection.
        """.trimIndent()

        val releases = ChangelogParser.parse(md)
        assertEquals(2, releases.size)

        val latest = releases[0]
        assertEquals("0.2.0", latest.version)
        assertEquals("2026-11-25", latest.date)
        assertEquals(2, latest.sections.size)
        assertEquals("Added", latest.sections[0].heading)
        assertEquals(listOf("**Feature A:** First item.", "Second item."), latest.sections[0].items)
        assertEquals("Fixed", latest.sections[1].heading)
        assertEquals(listOf("Bug fix with asterisk bullet."), latest.sections[1].items)

        val first = releases[1]
        assertEquals("0.1.0", first.version)
        assertEquals("2026-11-17", first.date)
        assertEquals(1, first.sections.size)
        assertEquals("Changes", first.sections[0].heading)
        assertEquals(listOf("Initial release without subsection."), first.sections[0].items)
    }

    @Test
    fun bundledAppVersionHasNonEmptyChangelogMatchingCurrentVersion() {
        assertTrue(AppVersion.versionName.isNotBlank())
        assertTrue(AppVersion.versionCode >= 1)
        assertEquals("v${AppVersion.versionName}", AppVersion.shortLabel)
        assertEquals("v${AppVersion.versionName} (Build ${AppVersion.versionCode})", AppVersion.fullLabel)
        assertTrue(AppVersion.releases.isNotEmpty(), "Bundled CHANGELOG.md should parse into at least one release")
        assertEquals(AppVersion.versionName, AppVersion.releases.first().version)
    }
}
