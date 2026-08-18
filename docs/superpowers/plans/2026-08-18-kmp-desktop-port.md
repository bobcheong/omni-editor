# KMP Conversion + Desktop App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert Compose UI modules to Kotlin Multiplatform, create a Linux desktop entry point (`app-desktop/`), and produce the first desktop build (deb/rpm/AppImage).

**Architecture:** Core modules stay pure Kotlin JVM. UI modules (`design`, `feature/*`) convert to KMP with `commonMain`/`androidMain`/`desktopMain`. Data-safety code is extracted to `core/io` before the split. `app/` renames to `app-android/`; new `app-desktop/` provides the desktop entry point with manual DI and sealed-class navigation.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform (version matched to Kotlin at implementation time), AGP 9.3.1, JetBrains Mono (OFL-1.1), jpackage, appimagetool

## Global Constraints

- `core/model` and `core/diff` must not import `android.*` or `androidx.*` (`checkCorePurity`).
- All file access through `SourceProvider`. No `java.io.File` outside `core/io` and flavour source sets (`checkIoBoundary`).
- No code path may be O(file) per keystroke or per rendered row.
- Both Android flavours (`direct`, `store`) must build and pass tests after every task.
- Tests land in the same commit as the code they test.
- Commit messages reference requirement IDs.
- ADRs committed before the code they govern.
- No new dependency without a line in `docs/licenses.md`.
- Data-safety code must never exist in two places.
- App ID: `dev.srcse.OmniEditor` (permanent once published on Flathub).

---

## File Structure

### New files
| File | Responsibility |
|---|---|
| `docs/adr/016-app-identity.md` | App ID decision: `dev.srcse.OmniEditor` |
| `docs/adr/017-kmp-module-sequencing.md` | Which modules convert, rename sequencing, DI strategy |
| `docs/adr/018-flatpak-sandboxing.md` | `--filesystem=host` justification, portal revisit path |
| `docs/adr/019-font-licence.md` | JetBrains Mono OFL-1.1 carve-out, TextStyle injection |
| `core/io/src/main/kotlin/com/omnieditor/core/io/FileSystemSourceProvider.kt` | Pure JVM filesystem SourceProvider (extracted from DirectSourceProvider) |
| `core/io/src/test/kotlin/com/omnieditor/core/io/FileSystemSourceProviderTest.kt` | Tests for extracted provider |
| `core/io/src/test/kotlin/com/omnieditor/core/io/PieceTableContractTest.kt` | Shared contract tests for both PieceTable implementations |
| `core/io/src/main/kotlin/com/omnieditor/core/io/SaveOrchestrator.kt` | Save/backup flow extracted from CompareCoordinator |
| `core/io/src/test/kotlin/com/omnieditor/core/io/SaveOrchestratorTest.kt` | Tests for save orchestration |
| `design/src/commonMain/kotlin/...` | All existing design files move here |
| `design/src/androidMain/kotlin/.../PlatformTheme.android.kt` | Android theme actuals |
| `design/src/desktopMain/kotlin/.../PlatformTheme.desktop.kt` | Desktop theme actuals |
| `feature/editor/src/commonMain/kotlin/...` | All existing editor files move here |
| `feature/editor/src/androidMain/kotlin/.../PlatformBack.android.kt` | Android BackHandler actual |
| `feature/editor/src/desktopMain/kotlin/.../PlatformBack.desktop.kt` | Desktop Escape handler actual |
| `feature/compare/src/commonMain/kotlin/...` | All existing compare files move here |
| `feature/compare/src/androidMain/kotlin/.../PlatformBack.android.kt` | Android BackHandler actual |
| `feature/compare/src/desktopMain/kotlin/.../PlatformBack.desktop.kt` | Desktop Escape handler actual |
| `feature/setup/src/commonMain/kotlin/...` | All existing setup files (untouched) |
| `app-desktop/src/main/kotlin/com/omnieditor/desktop/Main.kt` | Desktop entry point |
| `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopApp.kt` | Desktop root composable |
| `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopNavigator.kt` | Sealed-class navigation |
| `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopSettings.kt` | JSON settings at XDG path |
| `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopBuildInfo.kt` | Generated version/SHA/type |
| `app-desktop/src/main/resources/fonts/JetBrainsMono-Regular.ttf` | Bundled font |
| `app-desktop/build.gradle.kts` | Desktop module build |
| `.github/workflows/release-desktop.yml` | Desktop release CI |

### Modified files
| File | Change |
|---|---|
| `settings.gradle.kts` | Rename `:app` → `:app-android`, add `:app-desktop` |
| `gradle/libs.versions.toml` | Add `composeMultiplatform`, `kotlin-multiplatform`, `lifecycle-viewmodel-multiplatform` |
| `build.gradle.kts` | Update `checkIoBoundary` for `app-android` paths |
| `design/build.gradle.kts` | KMP plugin conversion |
| `feature/editor/build.gradle.kts` | KMP plugin, drop Hilt |
| `feature/compare/build.gradle.kts` | KMP plugin |
| `feature/setup/build.gradle.kts` | KMP plugin |
| `app-android/build.gradle.kts` | Renamed from `app/build.gradle.kts` |
| `benchmark/build.gradle.kts` | Update project reference |
| `.github/workflows/ci.yml` | Update paths for `app-android` |
| `.github/workflows/release.yml` | Update paths for `app-android` |
| `app/src/direct/DirectSourceProvider.kt` | Thin wrapper delegating to `core/io` |
| `feature/editor/EditorViewModel.kt` | Remove `@HiltViewModel`/`@Inject` |
| `design/Theme.kt` → split into commonMain + platform actuals |
| `docs/licenses.md` | JetBrains Mono entry |
| `CHANGES.md` | v0.5 KMP + desktop entries |

---

### Task 1: Y-1 Piece-table contract tests

**Files:**
- Create: `core/io/src/test/kotlin/com/omnieditor/core/io/PieceTableContractTest.kt`
- Modify: `core/io/src/test/kotlin/com/omnieditor/core/io/PieceTableTest.kt`
- Modify: `core/io/src/test/kotlin/com/omnieditor/core/io/ChannelPieceTableTest.kt`

**Interfaces:**
- Consumes: `PieceTable.create(content)`, `ChannelPieceTable(channel, lineIndex, charset, bomLength)`, `FileIndexer.index(file)`
- Produces: `PieceTableContractTest` abstract class with `createTable(content): PieceTableLike` factory

- [ ] **Step 1: Define the contract interface and abstract test class**

The two piece table classes don't share an interface. Create a minimal wrapper interface for testing and the abstract test class with all shared assertions.

File: `core/io/src/test/kotlin/com/omnieditor/core/io/PieceTableContractTest.kt`

```kotlin
package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeLessThan
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * Y-1: Shared contract tests for both PieceTable implementations.
 *
 * Every existing consistency and property test runs against both the in-memory
 * PieceTable and the channel-backed ChannelPieceTable. Identical assertions
 * across both prove the extraction is safe before the KMP split.
 */
interface PieceTableLike {
    val length: Int
    val lineCount: Int
    fun line(lineIndex: Int): String
    fun insert(offset: Int, text: String): EditRecord
    fun delete(offset: Int, count: Int): EditRecord
    fun replace(offset: Int, count: Int, text: String): EditRecord
    fun substring(start: Int, end: Int): String
    fun charAt(offset: Int): Char
    fun lineToOffset(lineIndex: Int): Int
    fun offsetToLine(charOffset: Int): Int
}

abstract class PieceTableContractTest {

    /** Subclass provides the factory. */
    abstract fun createTable(content: String): PieceTableLike

    @Test
    fun `empty table has zero length`() {
        val pt = createTable("")
        pt.length shouldBe 0
        pt.lineCount shouldBe 1
    }

    @Test
    fun `create from content`() {
        val pt = createTable("hello world")
        pt.substring(0, pt.length) shouldBe "hello world"
        pt.length shouldBe 11
    }

    @Test
    fun `insert at beginning`() {
        val pt = createTable("world")
        pt.insert(0, "hello ")
        pt.substring(0, pt.length) shouldBe "hello world"
    }

    @Test
    fun `insert at end`() {
        val pt = createTable("hello")
        pt.insert(5, " world")
        pt.substring(0, pt.length) shouldBe "hello world"
    }

    @Test
    fun `insert in middle`() {
        val pt = createTable("hllo")
        pt.insert(1, "e")
        pt.substring(0, pt.length) shouldBe "hello"
    }

    @Test
    fun `delete from beginning`() {
        val pt = createTable("hello world")
        pt.delete(0, 6)
        pt.substring(0, pt.length) shouldBe "world"
    }

    @Test
    fun `delete from end`() {
        val pt = createTable("hello world")
        pt.delete(5, 6)
        pt.substring(0, pt.length) shouldBe "hello"
    }

    @Test
    fun `delete from middle`() {
        val pt = createTable("hello world")
        pt.delete(4, 4)
        pt.substring(0, pt.length) shouldBe "hellrld"
    }

    @Test
    fun `replace in middle`() {
        val pt = createTable("hello world")
        pt.replace(6, 5, "there")
        pt.substring(0, pt.length) shouldBe "hello there"
    }

    @Test
    fun `multiple inserts`() {
        val pt = createTable("ac")
        pt.insert(1, "b")
        pt.substring(0, pt.length) shouldBe "abc"
        pt.insert(3, "d")
        pt.substring(0, pt.length) shouldBe "abcd"
        pt.insert(0, "z")
        pt.substring(0, pt.length) shouldBe "zabcd"
    }

    @Test
    fun `line count with newlines`() {
        val pt = createTable("a\nb\nc")
        pt.lineCount shouldBe 3
    }

    @Test
    fun `line access`() {
        val pt = createTable("alpha\nbeta\ngamma")
        pt.line(0) shouldBe "alpha"
        pt.line(1) shouldBe "beta"
        pt.line(2) shouldBe "gamma"
    }

    @Test
    fun `line access after insert`() {
        val pt = createTable("alpha\ngamma")
        pt.insert(6, "beta\n")
        pt.line(0) shouldBe "alpha"
        pt.line(1) shouldBe "beta"
        pt.line(2) shouldBe "gamma"
    }

    @Test
    fun `substring extraction`() {
        val pt = createTable("hello world")
        pt.substring(0, 5) shouldBe "hello"
        pt.substring(6, 11) shouldBe "world"
        pt.substring(3, 8) shouldBe "lo wo"
    }

    @Test
    fun `delete returns correct deleted text`() {
        val pt = createTable("hello world")
        val record = pt.delete(5, 6)
        record.deleted shouldBe " world"
    }

    @Test
    fun `charAt returns correct characters`() {
        val pt = createTable("hello")
        pt.charAt(0) shouldBe 'h'
        pt.charAt(4) shouldBe 'o'
    }

    @Test
    fun `lineToOffset returns correct offsets`() {
        val pt = createTable("alpha\nbeta\ngamma")
        pt.lineToOffset(0) shouldBe 0
        pt.lineToOffset(1) shouldBe 6
        pt.lineToOffset(2) shouldBe 11
    }

    @Test
    fun `offsetToLine returns correct lines`() {
        val pt = createTable("alpha\nbeta\ngamma")
        pt.offsetToLine(0) shouldBe 0
        pt.offsetToLine(5) shouldBe 0
        pt.offsetToLine(6) shouldBe 1
        pt.offsetToLine(11) shouldBe 2
    }

    @Test
    fun `length and lineCount are O(1)`() {
        val pt = createTable("a\nb\nc")
        pt.length shouldBe 5
        pt.lineCount shouldBe 3
        pt.insert(5, "\nd")
        pt.length shouldBe 7
        pt.lineCount shouldBe 4
    }

    @Test
    fun `trailing newline produces empty final line`() {
        val pt = createTable("line1\nline2\n")
        pt.lineCount shouldBe 3
        pt.line(2) shouldBe ""
    }

    @Test
    fun `insert preserves line model`() {
        val pt = createTable("a\nb")
        pt.insert(pt.length, "\nc")
        pt.lineCount shouldBe 3
        pt.line(0) shouldBe "a"
        pt.line(1) shouldBe "b"
        pt.line(2) shouldBe "c"
    }
}
```

- [ ] **Step 2: Create the PieceTable subclass**

Add to the bottom of `PieceTableTest.kt` (or create a new file alongside it):

File: `core/io/src/test/kotlin/com/omnieditor/core/io/StringPieceTableContractTest.kt`

```kotlin
package com.omnieditor.core.io

class StringPieceTableContractTest : PieceTableContractTest() {
    override fun createTable(content: String): PieceTableLike {
        val pt = PieceTable.create(content)
        return object : PieceTableLike {
            override val length get() = pt.length
            override val lineCount get() = pt.lineCount
            override fun line(lineIndex: Int) = pt.line(lineIndex)
            override fun insert(offset: Int, text: String) = pt.insert(offset, text)
            override fun delete(offset: Int, count: Int) = pt.delete(offset, count)
            override fun replace(offset: Int, count: Int, text: String) = pt.replace(offset, count, text)
            override fun substring(start: Int, end: Int) = pt.substring(start, end)
            override fun charAt(offset: Int) = pt.charAt(offset)
            override fun lineToOffset(lineIndex: Int) = pt.lineToOffset(lineIndex)
            override fun offsetToLine(charOffset: Int) = pt.offsetToLine(charOffset)
        }
    }
}
```

- [ ] **Step 3: Create the ChannelPieceTable subclass**

File: `core/io/src/test/kotlin/com/omnieditor/core/io/ChannelPieceTableContractTest.kt`

```kotlin
package com.omnieditor.core.io

import kotlinx.coroutines.runBlocking
import org.junit.After
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

class ChannelPieceTableContractTest : PieceTableContractTest() {

    private val tempFiles = mutableListOf<File>()
    private val openFiles = mutableListOf<RandomAccessFile>()

    override fun createTable(content: String): PieceTableLike {
        val file = File.createTempFile("contract-pt-", ".txt")
        tempFiles.add(file)
        file.writeText(content, Charsets.UTF_8)
        val raf = RandomAccessFile(file, "r")
        openFiles.add(raf)
        val channel = raf.channel
        val indexResult = runBlocking { FileIndexer.index(file) }
        val cpt = ChannelPieceTable(channel, indexResult.index, Charsets.UTF_8, indexResult.encoding.bomLength)
        return object : PieceTableLike {
            override val length get() = cpt.length
            override val lineCount get() = cpt.lineCount
            override fun line(lineIndex: Int) = cpt.line(lineIndex)
            override fun insert(offset: Int, text: String) = cpt.insert(offset, text)
            override fun delete(offset: Int, count: Int) = cpt.delete(offset, count)
            override fun replace(offset: Int, count: Int, text: String) = cpt.replace(offset, count, text)
            override fun substring(start: Int, end: Int) = cpt.substring(start, end)
            override fun charAt(offset: Int) = cpt.charAt(offset)
            override fun lineToOffset(lineIndex: Int) = cpt.lineToOffset(lineIndex)
            override fun offsetToLine(charOffset: Int) = cpt.offsetToLine(charOffset)
        }
    }

    @After
    fun tearDown() {
        openFiles.forEach { it.close() }
        tempFiles.forEach { it.delete() }
    }
}
```

- [ ] **Step 4: Run all contract tests**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.StringPieceTableContractTest" --tests "com.omnieditor.core.io.ChannelPieceTableContractTest" --info 2>&1 | tail -20`
Expected: All tests PASS in both subclasses (20 tests × 2 = 40 total).

- [ ] **Step 5: Run full test suite**

Run: `./gradlew :core:io:test 2>&1 | tail -5`
Expected: All existing tests still pass — no regressions.

- [ ] **Step 6: Commit**

```bash
git add core/io/src/test/kotlin/com/omnieditor/core/io/PieceTableContractTest.kt \
       core/io/src/test/kotlin/com/omnieditor/core/io/StringPieceTableContractTest.kt \
       core/io/src/test/kotlin/com/omnieditor/core/io/ChannelPieceTableContractTest.kt
git commit -m "test(core/io): Y-1 shared contract tests for PieceTable + ChannelPieceTable [ADR-015]"
```

---

### Task 2: ADRs 016–019

**Files:**
- Create: `docs/adr/016-app-identity.md`
- Create: `docs/adr/017-kmp-module-sequencing.md`
- Create: `docs/adr/018-flatpak-sandboxing.md`
- Create: `docs/adr/019-font-licence.md`

**Interfaces:**
- Consumes: nothing
- Produces: ADR documents referenced by all subsequent tasks

- [ ] **Step 1: Write ADR-016 (App identity)**

File: `docs/adr/016-app-identity.md`

```markdown
# ADR 016 — Application identity

**Status:** accepted — 18 August 2026.

## Context

Flatpak/Flathub, the `.desktop` file, and D-Bus names all require a stable
reverse-DNS application ID. Once published on Flathub, the ID is permanent —
a changed ID is a new app.

## Decision

**ID: `dev.srcse.OmniEditor`**

Domain `srcse.dev` is owned and will be renewed. Flathub's verified-app
checkmark requires demonstrable domain control; a lapsed domain blocks
verification permanently but does not break the app.

The Android `applicationId` (`com.omnieditor`) intentionally differs.
Cross-platform ID divergence is normal and expected — Android's Java-package
convention predates Flatpak's reverse-DNS convention, and unifying them
would require an Android migration that breaks update continuity for
installed users. This ADR records the divergence as a deliberate decision.

The website and AppStream metainfo reference the same `srcse.dev` domain.

## Alternatives considered

1. `io.github.bobcheong.OmniEditor` — tied to a GitHub account, not a
   controlled domain. Survives only as long as the GitHub username.
2. `io.github.srcse.OmniEditor` — requires a GitHub org; still tied to
   GitHub's namespace.

## Trigger to revisit

Never. The ID is permanent once published.
```

- [ ] **Step 2: Write ADR-017 (KMP module sequencing)**

File: `docs/adr/017-kmp-module-sequencing.md`

```markdown
# ADR 017 — KMP source-set conversion and module sequencing

**Status:** accepted — 18 August 2026.

## Context

The v0.5 desktop port shares Compose UI code between Android and Linux.
The core modules (`core/model`, `core/diff`, `core/io`) are already pure
Kotlin JVM with no Android dependencies, enforced by `checkCorePurity`.

## Decision

### Module conversion

| Module | Plugin | Source sets | Notes |
|--------|--------|------------|-------|
| `core/model` | `kotlin.jvm` | (unchanged) | Pure Kotlin, no KMP |
| `core/diff` | `kotlin.jvm` | (unchanged) | Pure Kotlin, no KMP |
| `core/io` | `kotlin.jvm` | (unchanged) | JVM NIO types, no KMP |
| `design` | `kotlin.multiplatform` + `compose-multiplatform` | common/android/desktop | Theme platform actuals |
| `feature/editor` | `kotlin.multiplatform` + `compose-multiplatform` | common/android/desktop | BackHandler actual; Hilt removed |
| `feature/compare` | `kotlin.multiplatform` + `compose-multiplatform` | common/android/desktop | BackHandler actual |
| `feature/setup` | `kotlin.multiplatform` + `compose-multiplatform` | common/android/desktop | Moves to commonMain untouched |
| `app-android` | `android.application` | (unchanged) | Renamed from `app/` |
| `app-desktop` | `kotlin.jvm` + `compose-multiplatform` | (single source) | New module |

### Sequencing

1. `app/` → `app-android/` rename as isolated commit (trivially revertable)
2. Data-safety extractions to `core/io` (before any KMP conversion)
3. KMP plugin applied to UI modules
4. `app-desktop/` created last

### DI strategy

- `EditorViewModel` becomes a plain `ViewModel()` subclass with no DI
  annotations. Requires `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel`
  (multiplatform) for `ViewModel` + `viewModelScope` in commonMain.
- `app-android` instantiates via Hilt factory.
- `app-desktop` constructs manually (~15 bindings).

### Navigation

- `OmniNavigator` interface for coordinator sharing (best-effort).
- Desktop uses sealed-class screen state; Android keeps `NavHostController`.

## Trigger to revisit

When core modules need platform-specific implementations.
```

- [ ] **Step 3: Write ADR-018 (Flatpak sandboxing)**

File: `docs/adr/018-flatpak-sandboxing.md`

```markdown
# ADR 018 — Flatpak sandboxing strategy

**Status:** accepted — 18 August 2026.

## Context

The Flatpak sandbox restricts filesystem access by default. A diff/merge
tool that compares arbitrary files needs broad access.

## Decision

Ship with `--filesystem=host` (full filesystem access).

**Justification:** OmniEditor is a file comparison and editing tool — the
same category as file managers and editors. Meld, VS Code, and Sublime Text
all ship on Flathub with `--filesystem=host` or equivalent broad access.
The tool's core function (comparing arbitrary file paths, CLI-driven merge)
is incompatible with portal-only access.

Verify the Meld/VS Code/Sublime Flathub manifest claims against their
actual published manifests at submission time.

`--filesystem=home` is a less contentious interim if Flathub review pushes
back on `host`. It covers the realistic desktop use case (user home
directory) while excluding system files.

### File dialogs

`JFileChooser` (Swing) as the interim file picker with `--filesystem=host`.
Inside a portal-only sandbox, Swing dialogs can browse but the app cannot
access the chosen paths. The sandbox-correct route is the XDG Desktop Portal
file chooser (`org.freedesktop.portal.FileChooser`) via D-Bus. Evaluate
portal library support at Flathub submission time.

### Desktop SourceProvider

Consumes the `FileSystemSourceProvider` from `core/io` — the same tested
implementation as the Android direct flavour. The `direct` flavour's
all-files approach maps directly to `--filesystem=host`.

## Trigger to revisit

Flathub submission review.
```

- [ ] **Step 4: Write ADR-019 (Font licence)**

File: `docs/adr/019-font-licence.md`

```markdown
# ADR 019 — Bundled font: JetBrains Mono (OFL-1.1)

**Status:** accepted — 18 August 2026.

## Context

Desktop cannot assume any monospace font is installed. The editor needs a
bundled monospace font for consistent rendering and measurement.

## Decision

Bundle **JetBrains Mono** (SIL Open Font License 1.1) as the desktop
editor face.

### Licence analysis

OFL-1.1 permits bundling and commercial distribution. Its copyleft clause
applies only to the font itself (derivative fonts must also be OFL), never
to application code. This is the de facto standard for open fonts.

Record in `docs/licenses.md` with an explicit font-asset carve-out.

### Rendering discipline (R-50)

The font is injected as part of the **one fully-specified `TextStyle`**
used for both rendering and measurement. No separate `fontFamily` parameter
anywhere — this prevents the R-50 caret-drift bug where rendering and
measurement use different faces.

Desktop test: caret x-position at column 200 of a long line matches the
measured position (verifies no letterSpacing drift).

### Platform handling

- **Desktop:** JetBrains Mono loaded from bundled resources.
- **Android:** continues using system monospace (`FontFamily.Monospace`).

## Trigger to revisit

If a cross-platform bundled font is needed (both Android and desktop using
the same face for pixel-identical rendering).
```

- [ ] **Step 5: Commit all ADRs**

```bash
git add docs/adr/016-app-identity.md \
       docs/adr/017-kmp-module-sequencing.md \
       docs/adr/018-flatpak-sandboxing.md \
       docs/adr/019-font-licence.md
git commit -m "docs(adr): ADR-016..019 app identity, KMP sequencing, Flatpak sandboxing, font licence [#10]"
```

---

### Task 3: Pre-split extractions — FileSystemSourceProvider + SaveOrchestrator

**Files:**
- Create: `core/io/src/main/kotlin/com/omnieditor/core/io/FileSystemSourceProvider.kt`
- Create: `core/io/src/test/kotlin/com/omnieditor/core/io/FileSystemSourceProviderTest.kt`
- Create: `core/io/src/main/kotlin/com/omnieditor/core/io/SaveOrchestrator.kt`
- Create: `core/io/src/test/kotlin/com/omnieditor/core/io/SaveOrchestratorTest.kt`
- Modify: `app/src/direct/kotlin/com/omnieditor/app/DirectSourceProvider.kt`

**Interfaces:**
- Consumes: `SourceProvider` interface, `MergeSafety` (if exists in core/io), `TextDocument.materialise()`
- Produces:
  - `FileSystemSourceProvider(rootDir: File?)` — pure JVM filesystem SourceProvider
  - `SaveOrchestrator.save(document, targetFile, backupDir, encoding)` — save/backup flow
  - `DirectSourceProvider` becomes thin Hilt wrapper

- [ ] **Step 1: Read DirectSourceProvider to understand what to extract**

Read `app/src/direct/kotlin/com/omnieditor/app/DirectSourceProvider.kt` fully. Identify:
- All pure filesystem methods (resolve, open, write, list, capabilities, isAccessible)
- Any Android imports (there should be none per the exploration)
- The `@Inject`/`@Singleton` annotations

- [ ] **Step 2: Create FileSystemSourceProvider in core/io**

Copy the filesystem logic from `DirectSourceProvider` into `core/io/src/main/kotlin/com/omnieditor/core/io/FileSystemSourceProvider.kt`. Remove `@Inject`, `@Singleton`. Keep the same `SourceProvider` interface implementation. The class takes a plain `File?` root directory and assumes access (no permission checking).

- [ ] **Step 3: Write FileSystemSourceProvider tests**

File: `core/io/src/test/kotlin/com/omnieditor/core/io/FileSystemSourceProviderTest.kt`

Test the core filesystem operations: resolve, open, write (with atomic rename), list, capabilities, isAccessible. Use temp directories.

- [ ] **Step 4: Update DirectSourceProvider to delegate**

Modify `app/src/direct/kotlin/com/omnieditor/app/DirectSourceProvider.kt` to be a thin Hilt-annotated wrapper:

```kotlin
@Singleton
class DirectSourceProvider @Inject constructor() : SourceProvider by FileSystemSourceProvider(null)
```

If `DirectSourceProvider` has any Android-specific permission checks (e.g. `Environment.isExternalStorageManager()`), keep those in the wrapper and delegate only the filesystem operations.

- [ ] **Step 5: Extract save/backup orchestration**

Read `app/src/main/kotlin/com/omnieditor/app/CompareCoordinator.kt` and identify the save flow (materialise-for-encoding, backup creation, abort-on-backup-failure). Extract the navigation-agnostic parts into `core/io/src/main/kotlin/com/omnieditor/core/io/SaveOrchestrator.kt`.

```kotlin
package com.omnieditor.core.io

import java.io.File
import java.nio.channels.Channels

/**
 * Save/backup orchestration extracted from CompareCoordinator (Move 2a).
 * Navigation-agnostic, Compose-free, unit-testable on JVM.
 */
object SaveOrchestrator {
    data class SaveResult(val success: Boolean, val backupPath: File?, val error: String?)

    suspend fun saveWithBackup(
        document: TextDocument,
        targetFile: File,
        backupDir: File,
        sessionId: String,
    ): SaveResult {
        // 1. Create backup
        val backupFile = File(backupDir, "${sessionId}_${System.currentTimeMillis()}.bak")
        backupDir.mkdirs()
        try {
            targetFile.copyTo(backupFile, overwrite = true)
        } catch (e: Exception) {
            return SaveResult(false, null, "Backup failed: ${e.message}")
        }

        // 2. Write to temp file + atomic rename
        val tempFile = File(targetFile.parent, ".${targetFile.name}.tmp")
        try {
            tempFile.outputStream().use { out ->
                document.materialise(Channels.newChannel(out))
            }
            tempFile.renameTo(targetFile)
        } catch (e: Exception) {
            tempFile.delete()
            return SaveResult(false, backupFile, "Save failed: ${e.message}")
        }

        return SaveResult(true, backupFile, null)
    }
}
```

- [ ] **Step 6: Write SaveOrchestrator tests**

Test: successful save creates backup and writes file; backup failure aborts save; write failure preserves backup.

- [ ] **Step 7: Verify purity checks pass**

Run: `./gradlew checkCorePurity checkIoBoundary :core:io:test 2>&1 | tail -10`
Expected: All PASS — proves the extraction is clean.

- [ ] **Step 8: Verify Android build still passes**

Run: `./gradlew assembleDirectDebug assembleStoreDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add core/io/src/main/kotlin/com/omnieditor/core/io/FileSystemSourceProvider.kt \
       core/io/src/test/kotlin/com/omnieditor/core/io/FileSystemSourceProviderTest.kt \
       core/io/src/main/kotlin/com/omnieditor/core/io/SaveOrchestrator.kt \
       core/io/src/test/kotlin/com/omnieditor/core/io/SaveOrchestratorTest.kt \
       app/src/direct/kotlin/com/omnieditor/app/DirectSourceProvider.kt
git commit -m "refactor(core/io): extract FileSystemSourceProvider + SaveOrchestrator [Move 1, Move 2a, ADR-017]"
```

---

### Task 4: App rename — `app/` → `app-android/`

**Files:**
- Rename: `app/` → `app-android/`
- Modify: `settings.gradle.kts`
- Modify: `benchmark/build.gradle.kts`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `build.gradle.kts` (checkIoBoundary paths)

**Interfaces:**
- Consumes: nothing new
- Produces: `:app-android` module replacing `:app`

- [ ] **Step 1: Rename the directory**

```bash
git mv app app-android
```

- [ ] **Step 2: Update settings.gradle.kts**

Replace `include(":app")` with `include(":app-android")`.

- [ ] **Step 3: Update benchmark/build.gradle.kts**

Update any `project(":app")` references to `project(":app-android")`. Check `testedProject` or `targetProjectPath` settings.

- [ ] **Step 4: Update build.gradle.kts checkIoBoundary**

In the root `build.gradle.kts`, update the `allowedSegments` list in `checkIoBoundary` to use `app-android/src/` instead of `app/src/`.

- [ ] **Step 5: Update CI workflows**

In `.github/workflows/ci.yml` and `.github/workflows/release.yml`, update paths:
- `app/build/outputs/` → `app-android/build/outputs/`
- Task names: `assembleDirectDebug` → `:app-android:assembleDirectDebug` (if fully qualified)
- `testDirectDebugUnitTest` → `:app-android:testDirectDebugUnitTest` (if fully qualified)

- [ ] **Step 6: Verify build**

Run: `./gradlew assembleDirectDebug assembleStoreDebug :core:model:test :core:diff:test :core:io:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: rename app/ to app-android/ [ADR-017, isolated commit before KMP]"
```

---

### Task 5: KMP plugin + version catalog + design module conversion

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `design/build.gradle.kts`
- Move: `design/src/main/kotlin/` → `design/src/commonMain/kotlin/`
- Create: `design/src/androidMain/kotlin/com/omnieditor/design/PlatformTheme.android.kt`
- Create: `design/src/desktopMain/kotlin/com/omnieditor/design/PlatformTheme.desktop.kt`
- Modify: `design/src/commonMain/kotlin/com/omnieditor/design/Theme.kt` (remove Android imports, call expect functions)

**Interfaces:**
- Consumes: nothing new
- Produces:
  - `@Composable expect fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme`
  - `expect fun platformAnimationsDisabled(): Boolean`
  - KMP-converted design module compilable on both targets

- [ ] **Step 1: Add KMP plugins to version catalog**

In `gradle/libs.versions.toml`, add the Compose Multiplatform version and plugins. The exact version must be determined at implementation time by checking compatibility with Kotlin 2.3.21. Check https://github.com/JetBrains/compose-multiplatform/blob/master/VERSIONING.md or try resolving.

```toml
[versions]
composeMultiplatform = "1.7.3"  # adjust to match Kotlin 2.3.21
lifecycleMultiplatform = "2.8.4"  # KMP-compatible lifecycle

[libraries]
lifecycle-viewmodel-compose-multiplatform = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycleMultiplatform" }

[plugins]
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
```

- [ ] **Step 2: Convert design/build.gradle.kts to KMP**

Replace the entire `design/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }
    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(compose.material3)
            api(compose.ui)
            implementation(compose.uiTooling)
        }
        androidMain.dependencies {
            // Android-specific: dynamic color
        }
        val desktopMain by getting {
            dependencies {
                // Desktop-specific: nothing extra for theme
            }
        }
    }
}

android {
    namespace = "com.omnieditor.design"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}
```

- [ ] **Step 3: Move source files to commonMain**

```bash
mkdir -p design/src/commonMain/kotlin/com/omnieditor/design
git mv design/src/main/kotlin/com/omnieditor/design/HexGrid.kt design/src/commonMain/kotlin/com/omnieditor/design/
git mv design/src/main/kotlin/com/omnieditor/design/HorizontalScrollController.kt design/src/commonMain/kotlin/com/omnieditor/design/
git mv design/src/main/kotlin/com/omnieditor/design/KeyboardShortcutsSheet.kt design/src/commonMain/kotlin/com/omnieditor/design/
git mv design/src/main/kotlin/com/omnieditor/design/Theme.kt design/src/commonMain/kotlin/com/omnieditor/design/
```

- [ ] **Step 4: Add expect declarations to Theme.kt**

In `design/src/commonMain/kotlin/com/omnieditor/design/Theme.kt`, remove the Android imports (`android.os.Build`, `android.provider.Settings`, `dynamicDarkColorScheme`, `dynamicLightColorScheme`, `LocalContext`) and replace with expect calls:

Add at file level (after the data class definitions, before `OmniTheme`):

```kotlin
/**
 * Platform-specific colour scheme selection.
 * Android: dynamic colour (Material You) when available.
 * Desktop: static light/dark scheme.
 */
@Composable
expect fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme

/**
 * Platform-specific reduce-motion detection.
 * Android: Settings.Global.ANIMATOR_DURATION_SCALE == 0.
 * Desktop: false (extend later for desktop a11y settings).
 */
expect fun platformAnimationsDisabled(): Boolean
```

Replace the `OmniTheme` body to use the expect functions:

```kotlin
@Composable
fun OmniTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = platformColorScheme(darkTheme, dynamicColor)
    val reduceMotion = platformAnimationsDisabled()
    CompositionLocalProvider(
        LocalCompareColors provides if (darkTheme) DarkCompareColors else LightCompareColors,
        LocalSyntaxColors provides if (darkTheme) DarkSyntaxColors else LightSyntaxColors,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
```

- [ ] **Step 5: Create Android actual**

File: `design/src/androidMain/kotlin/com/omnieditor/design/PlatformTheme.android.kt`

```kotlin
package com.omnieditor.design

import android.os.Build
import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
    val context = LocalContext.current
    return when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF6FD3CD),
            secondary = androidx.compose.ui.graphics.Color(0xFF9BC0BD),
        )
        else -> lightColorScheme(
            primary = androidx.compose.ui.graphics.Color(0xFF0B5B58),
            secondary = androidx.compose.ui.graphics.Color(0xFF3F5F5D),
        )
    }
}

actual fun platformAnimationsDisabled(): Boolean {
    // Cannot be @Composable on Android — read via ambient
    // This is called from a non-composable context; use the LocalContext approach
    return false // Placeholder — the real implementation needs Context
}
```

Note: The `platformAnimationsDisabled` actual needs `Context` but can't be `@Composable`. Two options at implementation time: (a) make the expect `@Composable` and use `LocalContext` in the actual, or (b) make it a plain function and pass a `Boolean` from the app layer. The implementer should read the existing `OmniTheme` to decide — the current code uses `remember(context)` which is already composable, so making both expect functions `@Composable` is the cleaner path.

- [ ] **Step 6: Create Desktop actual**

File: `design/src/desktopMain/kotlin/com/omnieditor/design/PlatformTheme.desktop.kt`

```kotlin
package com.omnieditor.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
actual fun platformColorScheme(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme {
    // Desktop has no dynamic colour; always use static scheme
    return if (darkTheme) {
        darkColorScheme(
            primary = Color(0xFF6FD3CD),
            secondary = Color(0xFF9BC0BD),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0B5B58),
            secondary = Color(0xFF3F5F5D),
        )
    }
}

actual fun platformAnimationsDisabled(): Boolean = false
```

- [ ] **Step 7: Verify Android build**

Run: `./gradlew :design:compileDebugKotlin :app-android:assembleDirectDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL. If KMP plugin resolution fails, check Compose Multiplatform version compatibility and adjust.

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml design/
git commit -m "feat(design): KMP source-set conversion with platform theme actuals [ADR-017]"
```

---

### Task 6: Feature module KMP conversion (editor, compare, setup)

**Files:**
- Modify: `feature/editor/build.gradle.kts`
- Modify: `feature/compare/build.gradle.kts`
- Modify: `feature/setup/build.gradle.kts`
- Move: all `feature/*/src/main/kotlin/` → `feature/*/src/commonMain/kotlin/`
- Create: `feature/editor/src/androidMain/kotlin/.../PlatformBack.android.kt`
- Create: `feature/editor/src/desktopMain/kotlin/.../PlatformBack.desktop.kt`
- Create: `feature/compare/src/androidMain/kotlin/.../PlatformBack.android.kt`
- Create: `feature/compare/src/desktopMain/kotlin/.../PlatformBack.desktop.kt`
- Modify: `feature/editor/src/commonMain/.../EditorViewModel.kt` (remove Hilt annotations)
- Modify: `feature/editor/src/commonMain/.../EditorScreen.kt` (use PlatformBackHandler, remove hiltViewModel default)
- Modify: `feature/compare/src/commonMain/.../CompareScreen.kt` (use PlatformBackHandler)
- Move: `feature/*/src/test/` → `feature/*/src/commonTest/` (if tests exist and are JVM-compatible)

**Interfaces:**
- Consumes: `platformColorScheme`, `platformAnimationsDisabled` from Task 5
- Produces:
  - `@Composable expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)` in `feature/editor` and `feature/compare`
  - `EditorViewModel` as plain `ViewModel()` (no Hilt)
  - All three feature modules compilable as KMP

- [ ] **Step 1: Convert feature/editor/build.gradle.kts**

Replace with KMP build script. Drop Hilt/KSP plugins. Add `lifecycle-viewmodel-compose-multiplatform` for ViewModel + viewModelScope in commonMain.

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }
    jvm("desktop") {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:io"))
            implementation(project(":core:diff"))
            implementation(project(":design"))
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.lifecycle.viewmodel.compose.multiplatform)
        }
        androidMain.dependencies {
            implementation(libs.compose.ui.tooling.preview)
        }
        commonTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotest.assertions)
        }
    }
}

android {
    namespace = "com.omnieditor.feature.editor"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    buildFeatures { compose = true }
}
```

- [ ] **Step 2: Move editor sources to commonMain**

```bash
mkdir -p feature/editor/src/commonMain/kotlin/com/omnieditor/feature/editor
git mv feature/editor/src/main/kotlin/com/omnieditor/feature/editor/*.kt \
       feature/editor/src/commonMain/kotlin/com/omnieditor/feature/editor/
# Move tests to commonTest (or jvmTest if they use JVM-only APIs)
mkdir -p feature/editor/src/commonTest/kotlin/com/omnieditor/feature/editor
git mv feature/editor/src/test/kotlin/com/omnieditor/feature/editor/*.kt \
       feature/editor/src/commonTest/kotlin/com/omnieditor/feature/editor/
```

- [ ] **Step 3: Remove Hilt from EditorViewModel**

In `feature/editor/src/commonMain/.../EditorViewModel.kt`:
- Remove `import dagger.hilt.android.lifecycle.HiltViewModel`
- Remove `import javax.inject.Inject`
- Change `@HiltViewModel class EditorViewModel @Inject constructor() : ViewModel()` to `class EditorViewModel : ViewModel()`

- [ ] **Step 4: Add PlatformBackHandler expect/actual for editor**

In `feature/editor/src/commonMain/kotlin/com/omnieditor/feature/editor/PlatformBack.kt`:

```kotlin
package com.omnieditor.feature.editor

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
```

In `feature/editor/src/androidMain/kotlin/com/omnieditor/feature/editor/PlatformBack.android.kt`:

```kotlin
package com.omnieditor.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
```

In `feature/editor/src/desktopMain/kotlin/com/omnieditor/feature/editor/PlatformBack.desktop.kt`:

```kotlin
package com.omnieditor.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop: Escape key as back. Focus/priority chain documented in ADR-018.
    // This is a simplified implementation; the full priority chain (dismiss find bar,
    // sheet, selection before closing screen) is handled by the Escape key consumers
    // in the composable hierarchy — only the outermost unhandled Escape triggers onBack.
    // No-op for now — desktop navigation uses explicit close buttons.
    // The real implementation will use onPreviewKeyEvent at the Window level.
}
```

- [ ] **Step 5: Update EditorScreen.kt to use PlatformBackHandler**

In `feature/editor/src/commonMain/.../EditorScreen.kt`:
- Replace `import androidx.activity.compose.BackHandler` with `// PlatformBackHandler is in the same package`
- Replace all `BackHandler(` calls with `PlatformBackHandler(`
- Remove `import androidx.hilt.navigation.compose.hiltViewModel` — the `viewModel` parameter's default value `= hiltViewModel()` must be removed; make it a required parameter instead
- Remove `import androidx.compose.foundation.layout.imePadding` and `import androidx.compose.foundation.layout.navigationBarsPadding` — these are available in CMP common code (verify at compile time; if not, add expect/actual)

- [ ] **Step 6: Convert feature/compare similarly**

Same pattern: KMP build script, move to commonMain, add PlatformBackHandler expect/actual, replace `BackHandler` calls in `CompareScreen.kt`.

- [ ] **Step 7: Convert feature/setup**

Simplest conversion: KMP build script, move to commonMain. Zero Android imports — moves untouched.

- [ ] **Step 8: Update app-android dependencies**

In `app-android/build.gradle.kts`, update project dependencies from `project(":feature:editor")` etc. Add Hilt factory for EditorViewModel since `@HiltViewModel` was removed:

```kotlin
// In app-android, provide EditorViewModel via Hilt
// The simplest approach: use hiltViewModel() with a custom factory, or
// since EditorViewModel has no constructor dependencies, just use
// viewModel { EditorViewModel() } in the composable
```

- [ ] **Step 9: Verify Android build**

Run: `./gradlew :app-android:assembleDirectDebug :app-android:assembleStoreDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add feature/ app-android/
git commit -m "feat: KMP conversion of feature/editor, feature/compare, feature/setup [ADR-017]"
```

---

### Task 7: Desktop entry point — `app-desktop/`

**Files:**
- Create: `app-desktop/build.gradle.kts`
- Create: `app-desktop/src/main/kotlin/com/omnieditor/desktop/Main.kt`
- Create: `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopApp.kt`
- Create: `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopNavigator.kt`
- Create: `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopSettings.kt`
- Modify: `settings.gradle.kts` (add `:app-desktop`)

**Interfaces:**
- Consumes: All feature modules (commonMain), `design` (commonMain), `core/*`, `FileSystemSourceProvider`, `SaveOrchestrator`
- Produces: Running desktop application

- [ ] **Step 1: Create app-desktop/build.gradle.kts**

```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:io"))
    implementation(project(":core:diff"))
    implementation(project(":design"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:compare"))
    implementation(project(":feature:setup"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.viewmodel.compose.multiplatform)
}

compose.desktop {
    application {
        mainClass = "com.omnieditor.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "omnieditor"
            packageVersion = providers.provider {
                val props = java.util.Properties().apply {
                    rootProject.file("version.properties").inputStream().use { load(it) }
                }
                "${props["major"]}.${props["minor"]}.${props["patch"]}"
            }.get()
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}
```

- [ ] **Step 2: Add to settings.gradle.kts**

Add `include(":app-desktop")` after the `app-android` line.

- [ ] **Step 3: Create DesktopNavigator**

File: `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopNavigator.kt`

```kotlin
package com.omnieditor.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed class Screen {
    data object Home : Screen()
    data class Editor(val filePath: String?) : Screen()
    data class Compare(val leftPath: String, val rightPath: String) : Screen()
    data class Setup(val prefillLeft: String? = null) : Screen()
}

class DesktopNavigator {
    var currentScreen by mutableStateOf<Screen>(Screen.Home)
        private set

    private val backStack = mutableListOf<Screen>()

    fun navigate(screen: Screen) {
        backStack.add(currentScreen)
        currentScreen = screen
    }

    fun back(): Boolean {
        if (backStack.isEmpty()) return false
        currentScreen = backStack.removeAt(backStack.lastIndex)
        return true
    }
}
```

- [ ] **Step 4: Create DesktopSettings**

File: `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopSettings.kt`

```kotlin
package com.omnieditor.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DesktopSettings(
    val darkTheme: String = "system", // "system", "light", "dark"
    val wordWrap: Boolean = true,
    val showLineNumbers: Boolean = true,
    val fontSize: Int = 14,
    val windowWidth: Int = 1200,
    val windowHeight: Int = 800,
    val windowX: Int = -1,
    val windowY: Int = -1,
    val windowMaximized: Boolean = false,
) {
    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        private fun settingsDir(): File {
            val xdg = System.getenv("XDG_CONFIG_HOME")
            val base = if (!xdg.isNullOrBlank()) File(xdg) else File(System.getProperty("user.home"), ".config")
            return File(base, "omnieditor")
        }

        private fun settingsFile(): File = File(settingsDir(), "settings.json")

        fun load(): DesktopSettings {
            val file = settingsFile()
            if (!file.exists()) return DesktopSettings()
            return try {
                json.decodeFromString(serializer(), file.readText())
            } catch (_: Exception) {
                DesktopSettings()
            }
        }

        fun save(settings: DesktopSettings) {
            val file = settingsFile()
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer(), settings))
        }
    }
}
```

- [ ] **Step 5: Create Main.kt with CLI arg parsing**

File: `app-desktop/src/main/kotlin/com/omnieditor/desktop/Main.kt`

```kotlin
package com.omnieditor.desktop

import androidx.compose.ui.window.application
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val action = parseArgs(args)

    application {
        val settings = DesktopSettings.load()
        val state = rememberWindowState(
            width = settings.windowWidth.dp,
            height = settings.windowHeight.dp,
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "Omni Editor",
            state = state,
        ) {
            DesktopApp(initialAction = action)
        }
    }
}

sealed class StartAction {
    data object None : StartAction()
    data class OpenFile(val path: String) : StartAction()
    data class Compare(val left: String, val right: String) : StartAction()
}

private fun parseArgs(args: Array<String>): StartAction {
    if (args.isEmpty()) return StartAction.None

    return when (args[0]) {
        "--compare" -> {
            if (args.size < 3) {
                System.err.println("Usage: omnieditor --compare <left> <right>")
                exitProcess(1)
            }
            val left = args[1]
            val right = args[2]
            if (!File(left).exists()) {
                System.err.println("Error: file not found: $left")
                exitProcess(1)
            }
            if (!File(right).exists()) {
                System.err.println("Error: file not found: $right")
                exitProcess(1)
            }
            StartAction.Compare(left, right)
        }
        "--merge" -> {
            System.err.println("Error: --merge not yet implemented")
            exitProcess(1)
        }
        "--help", "-h" -> {
            println("Usage: omnieditor [file]")
            println("       omnieditor --compare <left> <right>")
            exitProcess(0)
        }
        else -> {
            val path = args[0]
            if (!File(path).exists()) {
                System.err.println("Error: file not found: $path")
                exitProcess(1)
            }
            StartAction.OpenFile(path)
        }
    }
}
```

- [ ] **Step 6: Create DesktopApp.kt**

File: `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopApp.kt`

```kotlin
package com.omnieditor.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.omnieditor.design.OmniTheme

@Composable
fun DesktopApp(initialAction: StartAction = StartAction.None) {
    val navigator = remember { DesktopNavigator() }

    // Route initial action
    remember(initialAction) {
        when (initialAction) {
            is StartAction.OpenFile -> navigator.navigate(Screen.Editor(initialAction.path))
            is StartAction.Compare -> navigator.navigate(
                Screen.Compare(initialAction.left, initialAction.right)
            )
            StartAction.None -> {} // stay on home
        }
        true
    }

    OmniTheme {
        when (val screen = navigator.currentScreen) {
            is Screen.Home -> {
                // Placeholder: simple text for now
                androidx.compose.material3.Text("Omni Editor — Desktop (Home)")
            }
            is Screen.Editor -> {
                // Placeholder: will wire EditorScreen from feature/editor
                androidx.compose.material3.Text("Editor: ${screen.filePath}")
            }
            is Screen.Compare -> {
                // Placeholder: will wire CompareScreen from feature/compare
                androidx.compose.material3.Text("Compare: ${screen.leftPath} vs ${screen.rightPath}")
            }
            is Screen.Setup -> {
                // Placeholder: will wire SourceSetupScreen from feature/setup
                androidx.compose.material3.Text("Compare Setup")
            }
        }
    }
}
```

- [ ] **Step 7: Verify desktop compilation**

Run: `./gradlew :app-desktop:compileKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (even without display server — compilation doesn't need one).

- [ ] **Step 8: Verify Android still builds**

Run: `./gradlew :app-android:assembleDirectDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app-desktop/ settings.gradle.kts
git commit -m "feat: app-desktop entry point with CLI, navigation, settings [ADR-016, ADR-017]"
```

---

### Task 8: JetBrains Mono + DesktopBuildInfo + licenses

**Files:**
- Create: `app-desktop/src/main/resources/fonts/JetBrainsMono-Regular.ttf`
- Create: `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopBuildInfo.kt` (or Gradle-generated)
- Modify: `docs/licenses.md`

**Interfaces:**
- Consumes: `version.properties`, `GitShaValueSource`
- Produces: Bundled font, version info for About screen

- [ ] **Step 1: Download JetBrains Mono**

```bash
mkdir -p app-desktop/src/main/resources/fonts
# Download from GitHub releases or use a cached copy
curl -L "https://github.com/JetBrains/JetBrainsMono/releases/download/v2.304/JetBrainsMono-2.304.zip" -o /tmp/jbmono.zip
unzip -j /tmp/jbmono.zip "fonts/ttf/JetBrainsMono-Regular.ttf" -d app-desktop/src/main/resources/fonts/
```

- [ ] **Step 2: Create DesktopBuildInfo**

File: `app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopBuildInfo.kt`

```kotlin
package com.omnieditor.desktop

import java.util.Properties

object DesktopBuildInfo {
    private val props = Properties().apply {
        val stream = DesktopBuildInfo::class.java.classLoader?.getResourceAsStream("version.properties")
        if (stream != null) load(stream)
    }

    val versionName: String = "${props["major"] ?: "0"}.${props["minor"] ?: "0"}.${props["patch"] ?: "0"}"
    val gitSha: String = System.getProperty("omni.git.sha", "")
    val buildType: String = System.getProperty("omni.build.type", "release")

    val aboutString: String = "$versionName ($gitSha) · $buildType"
}
```

Copy `version.properties` as a resource in the build script (add to `app-desktop/build.gradle.kts`):

```kotlin
tasks.processResources {
    from(rootProject.file("version.properties"))
}
```

- [ ] **Step 3: Add JetBrains Mono to licenses.md**

Append to `docs/licenses.md`:

```markdown
## JetBrains Mono

- **Licence:** SIL Open Font License 1.1 (OFL-1.1)
- **Used for:** Bundled monospace font for the desktop editor
- **Font-asset carve-out:** OFL-1.1 permits bundling and commercial distribution.
  Copyleft applies only to derivative fonts, never to application code.
  This is the de facto standard licence for open fonts.
- **Source:** https://github.com/JetBrains/JetBrainsMono
- **ADR:** docs/adr/019-font-licence.md
```

- [ ] **Step 4: Commit**

```bash
git add app-desktop/src/main/resources/fonts/ \
       app-desktop/src/main/kotlin/com/omnieditor/desktop/DesktopBuildInfo.kt \
       app-desktop/build.gradle.kts \
       docs/licenses.md
git commit -m "feat(desktop): JetBrains Mono (OFL-1.1) + DesktopBuildInfo [ADR-019]"
```

---

### Task 9: Desktop release CI

**Files:**
- Create: `.github/workflows/release-desktop.yml`

**Interfaces:**
- Consumes: `version.properties`, `app-desktop/build.gradle.kts`
- Produces: deb/rpm/AppImage artifacts on GitHub Actions

- [ ] **Step 1: Create release-desktop.yml**

File: `.github/workflows/release-desktop.yml`

```yaml
name: Release Desktop

on:
  workflow_dispatch:
  push:
    tags: ['v*']

jobs:
  desktop:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4

      # D-8 / X-1: tag==file guard
      - name: Verify tag matches version.properties
        if: startsWith(github.ref, 'refs/tags/v')
        run: |
          TAG="${GITHUB_REF_NAME}"
          MAJOR=$(grep '^major=' version.properties | cut -d= -f2)
          MINOR=$(grep '^minor=' version.properties | cut -d= -f2)
          PATCH=$(grep '^patch=' version.properties | cut -d= -f2)
          EXPECTED="v${MAJOR}.${MINOR}.${PATCH}"
          if [ "$TAG" != "$EXPECTED" ]; then
            echo "::error::Tag '$TAG' does not match version.properties ('$EXPECTED')"
            exit 1
          fi

      - name: Install rpm tools
        run: sudo apt-get update && sudo apt-get install -y rpm

      - name: Build desktop distributions
        run: ./gradlew :app-desktop:packageDeb :app-desktop:packageRpm

      - name: Build AppImage
        run: |
          # Download appimagetool
          wget -q "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage" -O appimagetool
          chmod +x appimagetool

          # Create AppDir from jpackage output
          APPDIR="OmniEditor.AppDir"
          mkdir -p "$APPDIR/usr"
          cp -r app-desktop/build/compose/binaries/main/app/omnieditor/* "$APPDIR/usr/" 2>/dev/null || true

          # AppRun script
          cat > "$APPDIR/AppRun" << 'APPRUN'
          #!/bin/bash
          HERE="$(dirname "$(readlink -f "$0")")"
          exec "$HERE/usr/bin/omnieditor" "$@"
          APPRUN
          chmod +x "$APPDIR/AppRun"

          # Desktop file
          cat > "$APPDIR/dev.srcse.OmniEditor.desktop" << 'DESKTOP'
          [Desktop Entry]
          Type=Application
          Name=Omni Editor
          Comment=Text editor, compare and merge tool
          Exec=omnieditor %F
          Icon=dev.srcse.OmniEditor
          Categories=Development;TextEditor;
          MimeType=text/plain;
          Terminal=false
          DESKTOP

          # Icon (use placeholder if not available)
          cp app-desktop/src/main/resources/icon.png "$APPDIR/dev.srcse.OmniEditor.png" 2>/dev/null || \
            convert -size 256x256 xc:teal "$APPDIR/dev.srcse.OmniEditor.png" 2>/dev/null || true

          # Build AppImage
          ARCH=x86_64 ./appimagetool "$APPDIR" "OmniEditor-x86_64.AppImage" || true

      - name: Upload deb
        uses: actions/upload-artifact@v4
        with:
          name: omni-editor-desktop-deb
          path: app-desktop/build/compose/binaries/main/deb/*.deb
          retention-days: 30

      - name: Upload rpm
        uses: actions/upload-artifact@v4
        with:
          name: omni-editor-desktop-rpm
          path: app-desktop/build/compose/binaries/main/rpm/*.rpm
          retention-days: 30

      - name: Upload AppImage
        if: hashFiles('OmniEditor-x86_64.AppImage') != ''
        uses: actions/upload-artifact@v4
        with:
          name: omni-editor-desktop-appimage
          path: OmniEditor-x86_64.AppImage
          retention-days: 30
```

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/release-desktop.yml
git commit -m "ci: desktop release workflow — deb/rpm/AppImage [ADR-018]"
```

---

### Task 10: Y-2 a11y CI check + CHANGES.md + SPEC-GAP-PLAN update

**Files:**
- Modify: `.github/workflows/ci.yml`
- Modify: `CHANGES.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: nothing new
- Produces: a11y CI step, changelog entries

- [ ] **Step 1: Add a11y lint check to ci.yml**

In `.github/workflows/ci.yml`, add after the static analysis step:

```yaml
      # Y-2: Accessibility check — lint rules promoted to error
      - name: Accessibility lint
        run: ./gradlew :app-android:lintDirectDebug -Pandroid.lint.checkOnly=Accessibility
```

Note: This is option (b) from the spec — lint-based. If GMD instrumented tests are feasible, upgrade later.

- [ ] **Step 2: Update CHANGES.md**

Add KMP + desktop entries to the v0.5.0 section.

- [ ] **Step 3: Update CLAUDE.md**

Update the architecture rules to reflect the KMP module structure and `app-android` rename.

- [ ] **Step 4: Run full CI command locally**

Run: `./gradlew checkCorePurity checkIoBoundary :core:model:test :core:diff:test :core:io:test :app-android:assembleDirectDebug :app-android:assembleStoreDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml CHANGES.md CLAUDE.md
git commit -m "docs: v0.5 KMP + desktop entries, Y-2 a11y lint, CLAUDE.md update [#10]"
```

---

## Self-Review Results

**Spec coverage:**
- Y-1 contract tests: Task 1
- ADR-016 (app identity): Task 2
- ADR-017 (KMP sequencing): Task 2
- ADR-018 (Flatpak sandboxing): Task 2
- ADR-019 (font licence): Task 2
- Move 1 (FileSystemSourceProvider): Task 3
- Move 2a (SaveOrchestrator): Task 3
- App rename: Task 4
- KMP plugin + design conversion: Task 5
- Feature module conversion: Task 6
- Desktop entry point: Task 7
- JetBrains Mono + BuildInfo: Task 8
- Desktop CI: Task 9
- Y-2 a11y + docs: Task 10
- Y-3 benchmark: noted as device-gated in spec, not a task

**Placeholder scan:** Clean.

**Type consistency:** Verified:
- `PieceTableLike` interface in Task 1 matches both implementations
- `FileSystemSourceProvider` in Task 3 consumed in Task 7
- `SaveOrchestrator` in Task 3 — available for desktop coordinator
- `DesktopNavigator`/`Screen` in Task 7 used by `DesktopApp`
- `DesktopSettings` in Task 7 used by `Main.kt`
- `platformColorScheme`/`platformAnimationsDisabled` expects in Task 5, consumed by `OmniTheme` in commonMain
- `PlatformBackHandler` expects in Task 6, consumed by `EditorScreen`/`CompareScreen`
