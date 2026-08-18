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
        namespace = "com.omnieditor.design"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }
    jvm("desktop")

    @Suppress("DEPRECATION")
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(compose.material3)
            api(compose.ui)
            implementation(compose.foundation)
        }
        androidMain.dependencies {
            // Android-specific: dynamic colour via Material You
            implementation("androidx.activity:activity-compose:1.13.0")
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.uiTooling)
            }
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
