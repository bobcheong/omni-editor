// Android library: this is the only module allowed to touch SAF, ContentResolver or File.
plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "com.omnieditor.core.io"
    compileSdk = 35
    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") { dimension = "distribution" }
        create("store") { dimension = "distribution" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
}
dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    androidTestImplementation(libs.androidx.test.runner)
}
