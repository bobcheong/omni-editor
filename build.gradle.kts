plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
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

tasks.register("checkCorePurity") {
    group = "verification"
    description = "Fails if :core:model or :core:diff import android.* or androidx.*"
    val roots = pureModules.map { rootProject.file("$it/src") }
    val forbidden = Regex("""^\s*import\s+(android|androidx)\.""")
    doLast {
        val violations = mutableListOf<String>()
        roots.filter { it.exists() }.forEach { root ->
            root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    if (forbidden.containsMatchIn(line)) {
                        violations += "${f.relativeTo(rootProject.projectDir)}:${i + 1}  ${line.trim()}"
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

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }
    tasks.named("check").configure { dependsOn(rootProject.tasks.named("checkCorePurity")) }
}
