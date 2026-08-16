pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*|com\\.google.*|androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "omni-editor"

include(":app")
include(":core:model")
include(":core:diff")
include(":core:io")
include(":design")
include(":feature:editor")
include(":feature:compare")
include(":feature:setup")
// Remaining: T-24 adds feature:home if needed.
include(":benchmark")
