package com.omnieditor.core.model

/**
 * In-app licence information for the About/Settings screen (IND-5, T-29, R-40).
 *
 * Every third-party dependency is listed with its licence.
 * This data drives the licences screen in the app.
 */
data class LicenceEntry(
    val name: String,
    val version: String,
    val licence: String,
    val url: String,
)

object LicenceInfo {
    val entries: List<LicenceEntry> = listOf(
        LicenceEntry("Kotlin", "2.3.21", "Apache-2.0", "https://kotlinlang.org"),
        LicenceEntry("Kotlin Coroutines", "1.11.0", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
        LicenceEntry("Kotlin Serialization", "1.11.0", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
        LicenceEntry("AndroidX Activity", "1.13.0", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
        LicenceEntry("AndroidX Lifecycle", "2.11.0", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
        LicenceEntry("Jetpack Compose", "2026.06.01", "Apache-2.0", "https://developer.android.com/jetpack/compose"),
        LicenceEntry("Material 3", "via Compose BOM", "Apache-2.0", "https://m3.material.io"),
        LicenceEntry("Hilt / Dagger", "2.60.1", "Apache-2.0", "https://dagger.dev/hilt"),
        LicenceEntry(
            "Hilt Navigation Compose", "1.4.0", "Apache-2.0",
            "https://developer.android.com/jetpack/androidx",
        ),
        LicenceEntry(
            "Navigation Compose", "2.9.0", "Apache-2.0",
            "https://developer.android.com/jetpack/androidx/releases/navigation",
        ),
        LicenceEntry(
            "AndroidX DataStore", "1.2.1", "Apache-2.0",
            "https://developer.android.com/topic/libraries/architecture/datastore",
        ),
    )

    const val appVersion = "0.2.0"
    const val appVersionCode = 1
}
