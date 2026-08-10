plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
}

/**
 * CLAUDE.md rule: :core:model and :core:diff are pure Kotlin and must never depend on
 * the Android platform. This task fails the build if an Android import appears there.
 * Wired into `check` below, so CI enforces it without anyone remembering to run it.
 */
val pureModules = listOf("core/model", "core/diff")
val projectRoot = rootProject.projectDir

tasks.register("checkCorePurity") {
    group = "verification"
    description = "Fails if :core:model or :core:diff import android.* or androidx.*"
    val roots = pureModules.map { projectRoot.resolve("$it/src") }
    val baseDir = projectRoot
    doLast {
        val forbidden = Regex("""^\s*import\s+(android|androidx)\.""")
        val violations = mutableListOf<String>()
        roots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    if (forbidden.containsMatchIn(line)) {
                        violations += "${f.relativeTo(baseDir)}:${i + 1}  ${line.trim()}"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Core purity violated — these modules must stay platform-independent:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

/**
 * CLAUDE.md rule: ContentResolver and java.io.File must only appear in core/io
 * and the flavour source sets (app/src/direct, app/src/store). All other app
 * and feature code must go through SourceProvider.
 *
 * Wired into `check` alongside checkCorePurity.
 */
tasks.register("checkIoBoundary") {
    group = "verification"
    description = "Fails if ContentResolver or java.io.File is used outside core/io and flavour source sets"
    val baseDir = projectRoot
    doLast {
        val forbidden = Regex("""^\s*import\s+(android\.content\.ContentResolver|java\.io\.File)\b""")
        // Paths where ContentResolver / java.io.File are legitimately allowed:
        //  - core/io/src          : the IO abstraction layer itself
        //  - app/src/direct       : direct-flavour filesystem access
        //  - app/src/store        : store-flavour SAF/ContentResolver access
        //  - app/src/main         : app entry-point layer; initialises core/io stores
        //                           with Android context paths (filesDir/cacheDir)
        //  - src/test / src/androidTest : test fixtures read golden files directly
        val allowedSegments = listOf(
            "core/io/src",
            "app/src/direct",
            "app/src/store",
            "app/src/main",
            "src/test/",
            "src/androidTest/",
        )
        val violations = mutableListOf<String>()
        baseDir.walkTopDown()
            .filter { it.extension == "kt" }
            // Only scan source files under src/ directories.
            .filter { f -> f.path.contains("/src/") }
            // Skip allowed paths.
            .filter { f ->
                val rel = f.relativeTo(baseDir).path.replace('\\', '/')
                allowedSegments.none { rel.startsWith(it) || rel.contains(it) }
            }
            .forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    if (forbidden.containsMatchIn(line)) {
                        violations += "${f.relativeTo(baseDir)}:${i + 1}  ${line.trim()}"
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "IO boundary violated — ContentResolver and java.io.File must only be used\n" +
                    "in core/io, flavour source sets (app/src/direct, app/src/store),\n" +
                    "the app entry-point (app/src/main), and test sources:\n" +
                    violations.joinToString("\n") { "  $it" }
            )
        }
    }
}

val detektConfigFile = rootProject.file("config/detekt/detekt.yml")
val corePurityTask = tasks.named("checkCorePurity")
val ioBoundaryTask = tasks.named("checkIoBoundary")

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(detektConfigFile)
        buildUponDefaultConfig = true
    }
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(corePurityTask)
        dependsOn(ioBoundaryTask)
    }
}
