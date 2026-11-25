import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

/**
 * Turns the legal documents under `docs/legal/` into a Kotlin source file so the installed app carries
 * exactly the text that lives in the repository. Each file becomes a list of lines (no single string constant
 * can outgrow the class-file limit) and every special or non-ASCII character is escaped.
 */
abstract class GenerateLegalDocumentsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val legalDirectory: DirectoryProperty

    @get:Input
    abstract val documentPaths: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private fun escape(line: String): String = buildString {
        for (ch in line) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '$' -> append("\\$")
                ch.code in 0x20..0x7E -> append(ch)
                else -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            }
        }
    }

    @TaskAction
    fun generate() {
        val root = legalDirectory.get().asFile
        val out = outputDirectory.get().asFile.resolve("com/runout/app/legal")
        out.mkdirs()
        val body = StringBuilder()
        body.appendLine("package com.runout.app.legal")
        body.appendLine()
        body.appendLine("// Generated from docs/legal/ at build time. Do not edit.")
        body.appendLine("internal object BundledLegalSources {")
        body.appendLine("    val files: Map<String, List<String>> = mapOf(")
        for (path in documentPaths.get()) {
            val text = root.resolve(path).readText(Charsets.UTF_8).replace("\r\n", "\n").trimEnd('\n')
            body.appendLine("        \"$path\" to listOf(")
            for (line in text.split('\n')) {
                body.appendLine("            \"${escape(line)}\",")
            }
            body.appendLine("        ),")
        }
        body.appendLine("    )")
        body.appendLine("}")
        out.resolve("BundledLegalSources.kt").writeText(body.toString(), Charsets.UTF_8)
    }
}

/**
 * Fails when a bracketed placeholder is still visible in the legal documents the app would show, i.e. after the
 * values in `LegalConfig.kt` have been substituted. It runs only as part of release builds.
 *
 * The substitution itself lives in `LegalConfig.applyPlaceholders`; this task does not copy it. It reads the
 * placeholder texts and the configured values straight out of `LegalConfig.kt` with a deliberately simple parse
 * (see [readString]): each is one `val NAME: String? = "..."` or `= null` declaration, or one
 * `const val NAME: String = "..."` declaration, with a plain string literal. Keep them in that shape.
 */
abstract class VerifyLegalPlaceholdersTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val legalConfigFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val legalDirectory: DirectoryProperty

    @get:Input
    abstract val documentPaths: ListProperty<String>

    /** The value of `val|const val NAME ... = "..."` in [source], `null` for `= null`, or fails if absent. */
    private fun readString(source: String, name: String): String? {
        val pattern = Regex("""val\s+$name\s*:\s*String\??\s*=\s*(null|"((?:[^"\\]|\\.)*)")""")
        val match = pattern.find(source)
            ?: throw GradleException("Could not find '$name' in LegalConfig.kt; keep it a plain string or null.")
        val literal = match.groups[2]?.value ?: return null
        return literal.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    @TaskAction
    fun verify() {
        val source = legalConfigFile.get().asFile.readText(Charsets.UTF_8)
        // Same rule as LegalConfig.applyPlaceholders: a blank or null value leaves its placeholder in place.
        val substitutions = listOf(
            "DEVELOPER_NAME" to "DEVELOPER_NAME_PLACEHOLDER",
            "EFFECTIVE_DATE" to "EFFECTIVE_DATE_PLACEHOLDER",
            "PROPRIETOR_LEGAL_NAME" to "PROPRIETOR_LEGAL_NAME_PLACEHOLDER",
        ).map { (valueName, placeholderName) ->
            Triple(valueName, readString(source, placeholderName)!!, readString(source, valueName))
        }
        val root = legalDirectory.get().asFile
        val unresolved = sortedSetOf<String>()
        val unsetValues = sortedSetOf<String>()
        for (path in documentPaths.get()) {
            var text = root.resolve(path).readText(Charsets.UTF_8)
            for ((valueName, placeholder, value) in substitutions) {
                if (!value.isNullOrBlank()) text = text.replace(placeholder, value)
                if (text.contains(placeholder)) {
                    unresolved += "$placeholder in docs/legal/$path"
                    unsetValues += valueName
                }
            }
        }
        if (unresolved.isNotEmpty()) {
            throw GradleException(
                "Release build stopped: the legal documents still contain placeholders.\n" +
                    unresolved.joinToString("\n") { "  - $it" } + "\n" +
                    "Set ${unsetValues.joinToString(", ")} in " +
                    "composeApp/src/commonMain/kotlin/com/runout/app/legal/LegalConfig.kt " +
                    "(and read docs/legal/store-declarations.md section 7) before building a release.",
            )
        }
    }
}

val legalDocumentPaths = listOf(
    "terms-of-use.md",
    "privacy-policy.md",
    "third-party-notices.md",
    "licenses/Apache-2.0.txt",
    "licenses/BSD-3-Clause-Skia.txt",
)

val generateLegalDocuments = tasks.register<GenerateLegalDocumentsTask>("generateLegalDocuments") {
    legalDirectory.set(rootProject.layout.projectDirectory.dir("docs/legal"))
    documentPaths.set(legalDocumentPaths)
    outputDirectory.set(layout.buildDirectory.dir("generated/legal/commonMain/kotlin"))
}

/**
 * Generates [com.runout.app.version.GeneratedAppVersion] from `gradle.properties` and root `CHANGELOG.md`
 * so the in-app version badge and What's New screen always match the packaged build.
 */
abstract class GenerateAppVersionTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val changelogFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    private fun escape(line: String): String = buildString {
        for (ch in line) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '$' -> append("\\$")
                ch.code in 0x20..0x7E -> append(ch)
                else -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            }
        }
    }

    @TaskAction
    fun generate() {
        val text = changelogFile.get().asFile.readText(Charsets.UTF_8).replace("\r\n", "\n").trimEnd('\n')
        val out = outputDirectory.get().asFile.resolve("com/runout/app/version")
        out.mkdirs()
        val body = StringBuilder()
        body.appendLine("package com.runout.app.version")
        body.appendLine()
        body.appendLine("// Generated from gradle.properties and CHANGELOG.md at build time. Do not edit.")
        body.appendLine("internal object GeneratedAppVersion {")
        body.appendLine("    const val VERSION_NAME: String = \"${escape(versionName.get())}\"")
        body.appendLine("    const val VERSION_CODE: Int = ${versionCode.get()}")
        body.appendLine("    val CHANGELOG_LINES: List<String> = listOf(")
        for (line in text.split('\n')) {
            body.appendLine("        \"${escape(line)}\",")
        }
        body.appendLine("    )")
        body.appendLine("}")
        out.resolve("GeneratedAppVersion.kt").writeText(body.toString(), Charsets.UTF_8)
    }
}

val appVersionName: String = providers.gradleProperty("runout.versionName").get()
val appVersionCode: Int = providers.gradleProperty("runout.versionCode").get().toInt()

val generateAppVersion = tasks.register<GenerateAppVersionTask>("generateAppVersion") {
    versionName.set(appVersionName)
    versionCode.set(appVersionCode)
    changelogFile.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    outputDirectory.set(layout.buildDirectory.dir("generated/version/commonMain/kotlin"))
}

val verifyLegalPlaceholders = tasks.register<VerifyLegalPlaceholdersTask>("verifyLegalPlaceholders") {
    group = "verification"
    description = "Fails while a bracketed placeholder remains in the bundled legal documents (release builds only)."
    legalConfigFile.set(layout.projectDirectory.file("src/commonMain/kotlin/com/runout/app/legal/LegalConfig.kt"))
    legalDirectory.set(rootProject.layout.projectDirectory.dir("docs/legal"))
    documentPaths.set(legalDocumentPaths)
}

// Release builds only: the entry points that produce a release APK or bundle, and the release iOS frameworks.
// Debug builds, unit tests and `check` are not affected.
val releaseEntryPoints = setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle")
tasks.configureEach {
    if (name in releaseEntryPoints || name.startsWith("linkRelease")) dependsOn(verifyLegalPlaceholders)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateLegalDocuments.map { it.outputDirectory })
            kotlin.srcDir(generateAppVersion.map { it.outputDirectory })
        }
        commonMain.dependencies {
            implementation(projects.engine)
            implementation(libs.kotlinx.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(compose.preview)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.runout.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.runout.app"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
