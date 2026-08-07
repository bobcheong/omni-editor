import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// DIST-4: sideloaded updates only install over the previous version if every build is
// signed with the SAME key. Generate the keystore once, keep it out of the repository,
// and back it up — losing it means every user must uninstall before updating.
//
//   keytool -genkeypair -v -keystore omni-release.jks -alias omni \
//           -keyalg RSA -keysize 4096 -validity 10000
//
// Then create keystore.properties (gitignored) next to this file, or set the same four
// values as environment variables in CI:
//   storeFile=/absolute/path/omni-release.jks
//   storePassword=...
//   keyAlias=omni
//   keyPassword=...
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.omnieditor.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omnieditor"
        minSdk = 31          // Android 12. Decision recorded: OE-SPEC-001 §11 NFR-C1.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // DIST-1: two flavours, one codebase. They differ only in a manifest permission and
    // the SourceProvider implementation. Both must build and pass tests (CLAUDE.md).
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            // All-files access: real paths, real file browser, durable session references.
            // Only viable because release one is installed directly, not via a store.
        }
        create("store") {
            dimension = "distribution"
            applicationIdSuffix = ".store"
            // Storage Access Framework only. Kept green in CI so a store release is
            // never a rewrite (DIST-5), even though it is not shipped yet.
        }
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "OMNI_KEYSTORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = signingValue("storePassword", "OMNI_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "OMNI_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "OMNI_KEY_PASSWORD")
            }
            // Absent keystore is not an error here: debug builds and CI checks must still
            // run. `assembleDirectRelease` will fail loudly instead, which is correct.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:io"))
    implementation(project(":core:diff"))
    implementation(project(":design"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.ui.test.manifest)
}
