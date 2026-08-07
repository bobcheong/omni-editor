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
// Remaining feature modules added at the task that needs them (T-23 setup, T-24 home).
