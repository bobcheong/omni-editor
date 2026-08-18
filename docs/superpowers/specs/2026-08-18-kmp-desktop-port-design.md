# Sub-project B: KMP Conversion + Desktop App — Design Spec

**Part of issue #10 (v0.5 Linux desktop port)**

**Goal:** Convert the Compose UI modules to Kotlin Multiplatform, create a desktop entry point (`app-desktop/`), and ship the first Linux desktop build — deb/rpm/AppImage.

**Architecture:** Core modules (`core/model`, `core/diff`, `core/io`) stay pure Kotlin JVM. UI modules (`design`, `feature/editor`, `feature/compare`, `feature/setup`) convert to KMP with `commonMain`/`androidMain`/`desktopMain` source sets. The app layer splits into `app-android/` (renamed from `app/`) and `app-desktop/` (new). Data-safety code is extracted to shared locations before the split to prevent forking.

**Decisions:**
- App ID: `dev.srcse.OmniEditor` (domain-based, permanent once published on Flathub)
- Approach 1 chosen: full KMP conversion in one pass (no intermediate refactor phase)

---

## Global Constraints

- `core/model` and `core/diff` must not import `android.*` or `androidx.*` (`checkCorePurity`).
- All file access through `SourceProvider`. No `java.io.File` outside `core/io` and flavour source sets (`checkIoBoundary`).
- No code path may be O(file) per keystroke or per rendered row.
- Both Android flavours (`direct`, `store`) must build and pass tests.
- Tests land in the same commit as the code they test.
- Commit messages reference requirement IDs.
- ADRs committed before the code they govern.
- No new dependency without a line in `docs/licenses.md`.
- Data-safety code must never exist in two places.

---

## Pre-split Extractions

### Move 1: `DirectSourceProvider` filesystem logic → `core/io`

Extract the pure filesystem `SourceProvider` implementation (fsync, atomic write, cleanup — R-57 work) from `app/src/direct/DirectSourceProvider.kt` into `core/io` as a plain class with no `@Inject`, no `@Singleton`, no Android imports.

The class takes plain paths and assumes access. Permission checking (`Environment.isExternalStorageManager()`) and error mapping stay in the `app-android` wrapper. `app/src/direct/DirectSourceProvider.kt` becomes a thin Hilt-annotated wrapper delegating to the `core/io` class.

Verification: `checkCorePurity` + `checkIoBoundary` pass after the move — this proves the extraction is clean.

Both `app-android` (direct flavour) and `app-desktop` consume the same tested filesystem implementation.

### Move 2a (mandatory): Save/backup orchestration out of `CompareCoordinator`

Extract the merge-save flow (materialise-for-encoding, abort-on-backup-failure — R-51/R-52 fixes) from `CompareCoordinator` into `core/io` as a navigation-agnostic, Compose-free class. This is pure business logic: provider + document + backup + encoding. Unit-testable on JVM.

### Move 2b (best-effort): Coordinator sharing via `OmniNavigator`

Introduce a navigation interface:

```kotlin
interface OmniNavigator {
    fun openEditor(key: String)
    fun back()
}
```

`app-android` adapts `NavHostController`. `app-desktop` adapts sealed-class screen state. Attempt to move coordinators to shared code. If coupling runs deeper than the nav handle (Hilt ViewModels, app-level singletons), stop — let desktop have thin platform coordinators that both call the shared save flow from Move 2a. The safety property (no forked data-safety logic) is guaranteed by 2a regardless.

---

## Build System Conversion

### Plugin changes per module

| Module | Current | New | Notes |
|--------|---------|-----|-------|
| `core/model` | `kotlin.jvm` | `kotlin.jvm` | No change |
| `core/diff` | `kotlin.jvm` | `kotlin.jvm` | No change |
| `core/io` | `kotlin.jvm` | `kotlin.jvm` | No change — gains extracted filesystem provider |
| `design` | `android.library` + `kotlin.compose` | `kotlin.multiplatform` + `compose-multiplatform` + `kotlin.compose` | KMP source sets |
| `feature/editor` | `android.library` + `kotlin.compose` + `ksp` + `hilt` | `kotlin.multiplatform` + `compose-multiplatform` + `kotlin.compose` | Drop Hilt from module |
| `feature/compare` | `android.library` + `kotlin.compose` | `kotlin.multiplatform` + `compose-multiplatform` + `kotlin.compose` | Minimal changes |
| `feature/setup` | `android.library` + `kotlin.compose` | `kotlin.multiplatform` + `compose-multiplatform` + `kotlin.compose` | Zero Android imports — moves to commonMain untouched |
| `app` → `app-android` | `android.application` | `android.application` | Renamed, keeps Hilt |
| `app-desktop` | *(new)* | `kotlin.jvm` + `compose-multiplatform` + `kotlin.compose` | Desktop entry point |
| `benchmark` | `android.test` | `android.test` | No change |

### Version catalog additions

```toml
[versions]
composeMultiplatform = "X.Y.Z"  # matched to Kotlin 2.3.21 at implementation time
lifecycleViewmodelMultiplatform = "2.11.0"  # or latest KMP-compatible

[libraries]
lifecycle-viewmodel-multiplatform = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel", version.ref = "lifecycleViewmodelMultiplatform" }

[plugins]
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
```

First validation step: add plugins to catalog, apply to one module, run `./gradlew tasks` — confirms resolution.

---

## Source Set Structure and expect/actual

### KMP source set layout (all four UI modules)

```
<module>/src/
  commonMain/kotlin/   ← all existing files move here
  androidMain/kotlin/  ← platform actuals for Android
  desktopMain/kotlin/  ← platform actuals for desktop
```

`feature/setup` moves to `commonMain` untouched (zero Android imports).

### expect/actual inventory

| # | API | Files | commonMain expect | androidMain actual | desktopMain actual |
|---|-----|-------|-------------------|--------------------|--------------------|
| 1 | Dynamic color | `design/Theme.kt` | `@Composable expect fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme` | `dynamicDarkColorScheme(LocalContext.current)` with `Build.VERSION.SDK_INT >= 31` guard | Static `darkColorScheme()`/`lightColorScheme()` |
| 2 | Animations disabled | `design/Theme.kt` | `expect fun platformAnimationsDisabled(): Boolean` | `Settings.Global.getFloat(contentResolver, ANIMATOR_DURATION_SCALE, 1f) == 0f` | `false` initially |
| 3 | BackHandler | `EditorScreen.kt`, `CompareScreen.kt` | `@Composable expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)` | Delegates to `androidx.activity.compose.BackHandler` | `onPreviewKeyEvent` for `Key.Escape` — focus/priority chain documented in ADR (Escape overloaded: dismiss find bar, sheet, selection) |
| 4 | Hilt ViewModel | `EditorViewModel.kt` | Plain `ViewModel()` subclass, no DI annotations. Requires `lifecycle-viewmodel-multiplatform` artifact. | `hiltViewModel` with factory in `app-android` | Manual construction in `app-desktop` |

**Dropped:** `imePadding()`/`navigationBarsPadding()` — available in CMP common code (≥1.6), no-op on desktop naturally. No wrapper needed.

---

## Module Rename and New Modules

### `app/` → `app-android/`

Isolated commit before any KMP conversion. Changes:
- `settings.gradle.kts`: `include(":app")` → `include(":app-android")`
- `benchmark/build.gradle.kts`: update `testedProject` reference
- CI workflows: `ci.yml`, `release.yml` update paths
- `DirectSourceProvider` becomes thin Hilt wrapper delegating to `core/io` class

### `app-desktop/` (new module)

- Plugin: `kotlin("jvm")` + `org.jetbrains.compose` + `kotlin.compose`
- Entry point: `fun main(args: Array<String>)` — parse CLI args before `application {}`. Bad paths → `System.err.println` + `exitProcess(1)`.
- CLI: `omnieditor [file]`, `omnieditor --compare left right`
- Desktop `SourceProvider`: consumes the `core/io` filesystem provider directly (same implementation as Android direct flavour)
- File dialogs: `JFileChooser` as interim with `--filesystem=host`. Portal file chooser as revisit path per ADR-018.
- Settings: JSON file at `System.getenv("XDG_CONFIG_HOME") ?: "${System.getProperty("user.home")}/.config/omnieditor"` / `settings.json`
- Window state: size/position/maximized persisted in settings file, restored with sanity clamping against current screen bounds
- Navigation: sealed-class screen state with `when` in Window composable. No `NavHost`/`navigation-compose`.
- DI: manual construction (~15 bindings). EditorViewModel, SettingsRepository equivalents constructed directly.
- No `LongJobService` — coroutine with progress
- No `AppShortcuts`, `CompareClipboardTile`, `IntentRouter` — Android-only

### Desktop `BuildConfig` substitute

Gradle task generates `DesktopBuildInfo.kt` from:
- `version.properties` → `VERSION_NAME`
- `GitShaValueSource` → `GIT_SHA`
- Gradle property → `BUILD_TYPE` (defaults to `release`; set to `debug` when running via `./gradlew :app-desktop:run`)

Same semantics as Android `BuildConfig` so the About string means the same thing on both platforms.

---

## ADRs

**ADR-016: App identity**
- ID: `dev.srcse.OmniEditor`
- Domain `srcse.dev` is owned and will be renewed (Flathub verified-app checkmark requires domain control; lapsed domain blocks verification permanently)
- Android `applicationId` (`com.omnieditor`) intentionally differs — cross-platform ID divergence is normal and expected; stated explicitly to prevent unification detour
- Website and AppStream metainfo reference the same domain
- Trigger to revisit: never (permanent)

**ADR-017: KMP module sequencing**
- Which modules convert (design, feature/*), which stay JVM (core/*)
- `app/` → `app-android/` rename as isolated commit
- Manual DI on desktop (~15 bindings)
- `OmniNavigator` interface for coordinator sharing (best-effort)
- `lifecycle-viewmodel` multiplatform artifact for ViewModel + viewModelScope in commonMain
- No restructure of core modules
- Trigger to revisit: if core modules need platform-specific implementations

**ADR-018: Flatpak sandboxing**
- `--filesystem=host` as primary, justified: file comparison tool in same category as file managers/editors
- Verify Meld/VS Code/Sublime Flathub manifest claims against actual published manifests at submission time
- `--filesystem=home` as less contentious interim if Flathub pushes back
- Portal file chooser (`org.freedesktop.portal.FileChooser`) as revisit path
- Trigger to revisit: Flathub submission review

**ADR-019: Font licence + rendering**
- JetBrains Mono (OFL-1.1) bundled as desktop editor face
- OFL-1.1 carve-out: permits bundling, copyleft applies only to font itself
- Font injected as part of the one fully-specified `TextStyle` for both rendering and measurement (R-50 caret-drift lesson)
- Desktop test: caret x-position at column 200 matches measured position
- Android continues using system monospace
- Trigger to revisit: if cross-platform font is needed

---

## Desktop CI (`release-desktop.yml`)

- `jpackage` for deb/rpm (`apt-get install rpm` on Ubuntu runner)
- `appimagetool` post-processing: `AppRun` script + `.desktop` file + icon into AppDir (~50 lines of CI)
- Tag==file guard: same `version.properties` check as Android release
- Upload artifacts with 30-day retention
- Flatpak deferred to follow-up (requires `flatpak-builder`, offline manifests, Flathub review)

---

## Y-items (ride along)

**Y-1 (contract tests):** Abstract `PieceTableContractTest` with `createTable(content)` factory. Two subclasses for `PieceTable` and `ChannelPieceTable`. Every existing consistency/property test runs against both. Lands first, before KMP touches these files.

**Y-2 (a11y CI check):** Instrumented tests with `AccessibilityChecks.enable()` on Gradle Managed Devices (`pixel6api34` already configured). Scoped to four screens: compare unified, compare split, editor, active-line sheet. If GMD proves too slow for CI, fall back to Android Lint accessibility rules promoted to `error` — record the limitation explicitly. No third carry.

**Y-3 (benchmark):** Explicit unverified item. INDEXED_EDITABLE tier benchmark on reference device required before `v0.5.0` tag. Stays on tag checklist.

---

## Out of Scope

- Flatpak packaging (follow-up after deb/rpm/AppImage are stable)
- IME/Wayland spike (documented as ADR investigation, requires desktop environment for real testing)
- CJK input (dependent on spike results — v1 desktop feature or documented limitation)
- Desktop keyboard navigation (click-drag, double-click word select, Ctrl+Arrow, Home/End, etc.) — Sub-project C
- Right-click context menus, window menu bar — Sub-project C
- Mouse wheel scroll, drag-and-drop file open — Sub-project C
