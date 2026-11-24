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

val generateLegalDocuments = tasks.register<GenerateLegalDocumentsTask>("generateLegalDocuments") {
    legalDirectory.set(rootProject.layout.projectDirectory.dir("docs/legal"))
    documentPaths.set(
        listOf(
            "terms-of-use.md",
            "privacy-policy.md",
            "third-party-notices.md",
            "licenses/Apache-2.0.txt",
            "licenses/BSD-3-Clause-Skia.txt",
        ),
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/legal/commonMain/kotlin"))
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
        versionCode = 1
        versionName = "0.1.0"
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
