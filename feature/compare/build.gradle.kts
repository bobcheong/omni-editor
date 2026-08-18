import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "com.omnieditor.feature.compare"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }
    jvm("desktop")

    @Suppress("DEPRECATION")
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:diff"))
            implementation(project(":core:io"))
            implementation(project(":design"))
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.foundation)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.lifecycle.viewmodel.compose.multiplatform)
        }
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
            implementation("androidx.activity:activity-compose:1.13.0")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.uiTooling)
            }
        }
        commonTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotest.assertions)
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
