import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val vMajor = versionProps.getProperty("major")
val vMinor = versionProps.getProperty("minor")
val vPatch = versionProps.getProperty("patch")

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
            }
        }
    }
}
