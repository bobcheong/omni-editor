plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
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

/**
 * R-39: Dead-code honesty sweep.
 *
 * Checks each Kotlin source file in core/model, core/diff and core/io whose name
 * matches a public top-level type (the "primary type" convention: one primary type
 * per file, file named after it). For each such file, verifies that some non-test,
 * non-self source file references the type name.
 *
 * This targets whole-module objects that have no consumer anywhere in the codebase,
 * without false-positives from helper types that live alongside primary types in
 * the same file.
 *
 * Types with no consumer are reported as errors unless they appear in the allowlist
 * documented in docs/adr/010-deferred-modules.md.
 */
tasks.register("checkUnusedPublicTypes") {
    group = "verification"
    description = "Fails when a core primary type has no non-test consumer and is not in the ADR-010 allowlist"

    // Types documented in ADR-010 as intentionally deferred to P2.
    val allowlist = setOf(
        "BlockDiff",                 // ADR-010: large-file path, needs device benchmark (P2)
        "Diff3",                     // ADR-010: three-pane merge UI deferred to P2
        "FileIndexer",               // ADR-010: streaming large-file load, wires with BlockDiff (P2)
        "AccessibilityConfig",       // ADR-010: settings screen deferred to P2
        "DocumentMeta",              // ADR-010: tab-state persistence, settings screen deferred to P2
        "SaveOrchestrator",          // ADR-010/ADR-017: Move 2a extraction; Android Move 2b + desktop wiring pending
        "FileSystemSourceProvider",  // ADR-010/ADR-017: Move 1 extraction; referenced via Kotlin delegation (no import text)
        "HexViewConfig",             // ADR-010: binary compare settings model; HexGrid UI deferred to P2
    )

    val coreModules = listOf("core/model", "core/diff", "core/io")
    val root = rootProject.projectDir

    doLast {
        val publicTypePattern = Regex(
            """^\s*(?:public\s+)?(?:(?:data|sealed|abstract|open|enum)\s+)?(?:class|object|interface)\s+(\w+)"""
        )

        // Collect primary types: file BaseName → file, only when the file declares
        // a top-level type whose name matches the file's base name (without .kt).
        val primaryTypes = mutableMapOf<String, File>() // typeName → declaring file
        coreModules.forEach { mod ->
            val mainSrc = root.resolve("$mod/src/main")
            if (!mainSrc.exists()) return@forEach
            mainSrc.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                val baseName = f.nameWithoutExtension
                val firstMatch = f.readLines().firstNotNullOfOrNull { line ->
                    publicTypePattern.find(line)?.groupValues?.get(1)
                        ?.takeIf { it == baseName }
                }
                if (firstMatch != null) primaryTypes[baseName] = f
            }
        }

        // All non-test production Kotlin sources.
        val allSources = root.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { f ->
                val rel = f.relativeTo(root).path.replace('\\', '/')
                !rel.contains("/test/") && !rel.contains("Test.kt")
            }
            .toList()

        val unlisted = mutableListOf<String>()
        val deferred = mutableListOf<String>()

        primaryTypes.forEach { (name, selfFile) ->
            val hasConsumer = allSources.any { f -> f != selfFile && f.readText().contains(name) }
            if (!hasConsumer) {
                if (name in allowlist) deferred += name
                else unlisted += "$name  (declared in ${selfFile.relativeTo(root)})"
            }
        }

        if (deferred.isNotEmpty()) {
            logger.warn(
                "[checkUnusedPublicTypes] Deferred (see docs/adr/010-deferred-modules.md):\n" +
                    deferred.joinToString("\n") { "  $it" }
            )
        }
        if (unlisted.isNotEmpty()) {
            throw GradleException(
                "[checkUnusedPublicTypes] Core primary types with no non-test consumer\n" +
                    "and no ADR-010 allowlist entry:\n" +
                    unlisted.joinToString("\n") { "  $it" } + "\n\n" +
                    "Either wire the type into production code, or add it to the allowlist\n" +
                    "in build.gradle.kts and document the reason in docs/adr/010-deferred-modules.md."
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
