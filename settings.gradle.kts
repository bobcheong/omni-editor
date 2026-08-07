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
// Feature modules are added at the task that needs them (T-14 editor, T-17 compare,
// T-23 setup, T-24 home). Adding empty modules now would be scaffolding without a user.
