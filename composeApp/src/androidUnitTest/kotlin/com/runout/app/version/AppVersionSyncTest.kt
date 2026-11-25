package com.runout.app.version

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that the version and changelog bundled into the app match `gradle.properties`,
 * `iosApp/iosApp/Info.plist`, and root `CHANGELOG.md` so Android, iOS, and the in-app What's New
 * screen never drift out of sync.
 */
class AppVersionSyncTest {
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "CHANGELOG.md").isFile && File(dir, "gradle.properties").isFile) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Repository root with CHANGELOG.md and gradle.properties not found above ${File("").absolutePath}")
    }

    @Test
    fun bundledVersionMatchesGradleProperties() {
        val props = Properties()
        File(repoRoot(), "gradle.properties").inputStream().use { props.load(it) }
        assertEquals(props.getProperty("runout.versionName"), AppVersion.versionName)
        assertEquals(props.getProperty("runout.versionCode")?.toInt(), AppVersion.versionCode)
    }

    @Test
    fun iosInfoPlistMatchesBundledVersion() {
        val plist = File(repoRoot(), "iosApp/iosApp/Info.plist").readText(Charsets.UTF_8)
        val shortVersion = Regex("""<key>CFBundleShortVersionString</key>\s*<string>([^<]+)</string>""")
            .find(plist)?.groupValues?.get(1)
        val buildVersion = Regex("""<key>CFBundleVersion</key>\s*<string>([^<]+)</string>""")
            .find(plist)?.groupValues?.get(1)
        assertEquals(AppVersion.versionName, shortVersion, "CFBundleShortVersionString in iosApp/iosApp/Info.plist must match runout.versionName")
        assertEquals(AppVersion.versionCode.toString(), buildVersion, "CFBundleVersion in iosApp/iosApp/Info.plist must match runout.versionCode")
    }

    @Test
    fun bundledChangelogMatchesRootFileAndTopReleaseVersion() {
        val source = File(repoRoot(), "CHANGELOG.md")
            .readText(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .trimEnd('\n')
        assertEquals(source, AppVersion.changelogMarkdown)
        assertEquals(AppVersion.versionName, AppVersion.releases.first().version)
    }
}
