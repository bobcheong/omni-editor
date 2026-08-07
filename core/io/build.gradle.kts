// T-04: Pure Kotlin JVM for now. The line index, encoding detection and readers
// are all java.nio — no Android imports needed.
// T-05 converts this to an Android library when SAF/ContentResolver are added.
plugins {
    alias(libs.plugins.kotlin.jvm)
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
}
