# Z-1 through Z-4: v0.5 Remainder — Design Spec

**Part of issue #10 (v0.5 Linux desktop port) — final sub-project**

**Goal:** Close the four findings from SPEC-GAP-PLAN rev 8: eliminate the forked save path (Z-1), fix DesktopBuildInfo to match D-8 (Z-2), wire real feature screens into the desktop shell (Z-3), and deduplicate PlatformBackHandler (Z-4).

**Architecture:** Z-1 is a pure refactor (rewire, delete). Z-2 is a build-system change (generated source replaces runtime read). Z-3 wires existing KMP composables into DesktopApp with manual DI. Z-4 moves one expect declaration.

**Order:** Z-1 → Z-2 → Z-3 → Z-4 (Z-1 first to prevent widening the save-path fork before screen wiring).

---

## Global Constraints

- `core/model` and `core/diff` must not import `android.*` or `androidx.*` (`checkCorePurity`).
- All file access through `SourceProvider`. No `java.io.File` outside `core/io`, `app-android` source sets, and `app-desktop` (`checkIoBoundary`).
- Data-safety code must never exist in two places.
- Both Android flavours (`direct`, `store`) must build and pass tests.
- Tests land in the same commit as the code they test.
- Desktop About must show D-8 string: `version (sha) · type`.
- No UI control may exist without behaviour.
- Commit messages reference requirement IDs.

---

## Z-1: Rewire CompareCoordinator through SaveOrchestrator

### Problem

`SaveOrchestrator` exists in `core/io` (from Move 2a) with zero consumers. `CompareCoordinator` in `app-android/` retains the full inline save flow: R-28 fingerprint check, backup creation via `MergeSafety.createBackup()`, materialise, abort-on-backup-failure. Two copies of the merge-save path exist — one live, one dead.

### Solution

Replace the inline save flow in `CompareCoordinator` with calls to `SaveOrchestrator.saveWithBackup()`. Delete the duplicated inline code. The existing Android save tests prove equivalence.

For the `store` flavour's ContentResolver write path (SAF URIs, not filesystem paths): `SaveOrchestrator` handles `File`-based saves. The SAF path remains in `CompareCoordinator` since it requires `ContentResolver` (Android-only). This is acceptable — the SAF path is Android-specific by definition and cannot be shared with desktop. The key constraint is that the *filesystem* save path (used by `direct` flavour and desktop) is single-sourced.

### Verification

- Existing `SaveOrchestratorTest` passes.
- `CompareCoordinator`'s save tests (if any) pass with the rewired path.
- `./gradlew :app-android:assembleDirectDebug :app-android:assembleStoreDebug` green.

---

## Z-2: DesktopBuildInfo — Gradle-generated source

### Problem

`DesktopBuildInfo` reads `version.properties` from the classpath at runtime. No `GIT_SHA`, no `BUILD_TYPE`. Desktop About cannot show the D-8 string.

### Solution

Generate `DesktopBuildInfo.kt` at build time via a Gradle task in `app-desktop/build.gradle.kts`:

```kotlin
// In app-desktop/build.gradle.kts
val generateBuildInfo by tasks.registering {
    val versionProps = rootProject.file("version.properties")
    val outputDir = layout.buildDirectory.dir("generated/buildinfo")
    inputs.file(versionProps)
    outputs.dir(outputDir)
    doLast {
        val props = java.util.Properties().apply {
            versionProps.inputStream().use { load(it) }
        }
        val version = "${props["major"]}.${props["minor"]}.${props["patch"]}"
        val sha = providers.of(GitShaValueSource::class.java) {}.get()
        val buildType = project.findProperty("omni.build.type")?.toString() ?: "release"
        val file = outputDir.get().file("com/omnieditor/desktop/DesktopBuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("""
            package com.omnieditor.desktop
            object DesktopBuildInfo {
                const val VERSION_NAME = "$version"
                const val GIT_SHA = "$sha"
                const val BUILD_TYPE = "$buildType"
                val aboutString: String = "${'$'}VERSION_NAME (${'$'}GIT_SHA) · ${'$'}BUILD_TYPE"
            }
        """.trimIndent())
    }
}
```

Add the generated directory to the source set. Delete the existing runtime-read `DesktopBuildInfo.kt`.

`BUILD_TYPE` defaults to `release`; pass `-Pomni.build.type=debug` for dev runs.

### Verification

- `./gradlew :app-desktop:compileKotlin` succeeds with generated source.
- Generated file contains correct version, SHA, and build type.

---

## Z-3: Wire real screens into DesktopApp

### Problem

`DesktopApp.kt` has placeholder `Text()` composables for all screens. Desktop is not usable.

### Solution

Wire the actual feature composables into `DesktopApp.kt`:

**Home screen:**
- `HomeScreen` from `app-android/` cannot be reused directly (it has Android dependencies: `Intent`, `Uri`, session group callbacks wired to Android navigation).
- Create a minimal `DesktopHomeScreen` in `app-desktop/` that shows recent sessions and an "Open file" button using `JFileChooser`.

**Editor screen:**
- `EditorScreen` from `feature/editor` (commonMain) is the shared composable.
- Construct `EditorViewModel` manually (no Hilt).
- Wire save via `FileSystemSourceProvider` + `SaveOrchestrator`.
- Wire "Open file" / "Save as" via `JFileChooser`.
- Wire "Compare with" to navigate to Setup screen.

**Compare screen:**
- `SourceSetupScreen` from `feature/setup` (commonMain) for file selection.
- `CompareScreen` from `feature/compare` (commonMain) for the diff view.
- Wire merge-save through `SaveOrchestrator`.

**File dialogs:**
- `JFileChooser` wrapped in a composable helper: `fun showOpenDialog(): File?` and `fun showSaveDialog(suggestedName: String): File?`.
- Runs on `Dispatchers.IO` via `withContext` to avoid blocking the UI thread.

**Navigation callbacks:**
- Each screen gets `onNavigateBack = { navigator.back() }` and screen-specific navigation (e.g., `onCompareWith = { navigator.navigate(Screen.Setup(...)) }`).

### Verification

- `./gradlew :app-desktop:compileKotlin` succeeds.
- `./gradlew :app-android:assembleDirectDebug` unaffected.
- Desktop launch tested manually on a Linux machine (Tier 2 — documented as unverified in this environment).

---

## Z-4: PlatformBackHandler dedup

### Problem

`PlatformBackHandler` expect is declared identically in both `feature/editor` and `feature/compare`. It belongs once in `design` (which both feature modules already depend on).

### Solution

1. Move `PlatformBackHandler` expect from `feature/editor/src/commonMain/` to `design/src/commonMain/kotlin/com/omnieditor/design/PlatformBack.kt`.
2. Move Android actual from `feature/editor/src/androidMain/` to `design/src/androidMain/kotlin/com/omnieditor/design/PlatformBack.android.kt`.
3. Move desktop actual from `feature/editor/src/desktopMain/` to `design/src/desktopMain/kotlin/com/omnieditor/design/PlatformBack.desktop.kt`.
4. Delete the duplicates from both `feature/editor` and `feature/compare`.
5. Update imports in `EditorScreen.kt` and `CompareScreen.kt` to reference `com.omnieditor.design.PlatformBackHandler`.

Add explicit "Move 2b (`OmniNavigator` coordinator sharing) not attempted — accepted as best-effort per spec" to the progress ledger.

### Verification

- `./gradlew :design:compileKotlinDesktop :feature:editor:compileKotlinDesktop :feature:compare:compileKotlinDesktop :app-android:assembleDirectDebug` all pass.

---

## Out of scope

- Desktop keyboard navigation (Ctrl+Arrow, Home/End, etc.) — Sub-project C if needed
- Right-click context menus, window menu bar — Sub-project C if needed
- IME/Wayland spike — documented investigation, not code
- Y-3 benchmark — device-gated, on the v0.5.0 tag checklist
- Move 2b (`OmniNavigator` coordinator sharing) — not attempted, accepted
