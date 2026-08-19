import java.io.File
import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

// D-8: git SHA as a configuration-cache-safe ValueSource
abstract class GitShaValueSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(File(System.getProperty("user.dir")))
            .redirectErrorStream(true)
            .start()
        val sha = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (sha.length in 7..12) sha else ""
    } catch (_: Exception) { "" }
}

val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val vMajor = versionProps.getProperty("major")
val vMinor = versionProps.getProperty("minor")
val vPatch = versionProps.getProperty("patch")

val gitSha: String = providers.of(GitShaValueSource::class.java) {}.get()
val buildType: String = (project.findProperty("omni.build.type") as? String) ?: "release"

val generateBuildInfo by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/buildinfo/com/omnieditor/desktop")
    outputs.dir(outputDir)
    // Always re-run so GIT_SHA reflects the current HEAD, not a cached value
    outputs.upToDateWhen { false }
    val capturedVersionName = "$vMajor.$vMinor.$vPatch"
    val capturedGitSha = gitSha
    val capturedBuildType = buildType
    doLast {
        val outFile = outputDir.get().asFile.resolve("DesktopBuildInfo.kt")
        outFile.parentFile.mkdirs()
        outFile.writeText(
            """
            |package com.omnieditor.desktop
            |
            |object DesktopBuildInfo {
            |    const val VERSION_NAME = "$capturedVersionName"
            |    const val GIT_SHA = "$capturedGitSha"
            |    const val BUILD_TYPE = "$capturedBuildType"
            |    val aboutString: String = "${'$'}VERSION_NAME (${'$'}GIT_SHA) · ${'$'}BUILD_TYPE"
            |}
            """.trimMargin()
        )
    }
}

sourceSets.main {
    kotlin.srcDir(generateBuildInfo.map { layout.buildDirectory.dir("generated/buildinfo") })
}

tasks.compileKotlin {
    dependsOn(generateBuildInfo)
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:io"))
    implementation(project(":core:diff"))
    implementation(project(":design"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:compare"))
    implementation(project(":feature:setup"))

    // Compose Desktop runtime + UI fundamentals
    implementation(compose.desktop.currentOs)
    @Suppress("DEPRECATION")
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing) // Provides Dispatchers.Main on desktop (AWT EDT)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.viewmodel.compose.multiplatform)
}

compose.desktop {
    application {
        mainClass = "com.omnieditor.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "omnieditor"
            packageVersion = "$vMajor.$vMinor.$vPatch"
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                // Z-7b: MIME associations for deb/rpm. jpackage --linux-shortcut creates
                // a .desktop file; our resource .desktop file (with full MIME list) is
                // installed alongside by the CI workflow. The AppImage uses it directly.
                shortcut = true
            }
        }
    }
}
