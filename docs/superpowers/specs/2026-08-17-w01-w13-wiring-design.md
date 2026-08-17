# Issue #22 — v0.4-w Wiring Package (W-01..W-13) Design Spec

**Date:** 2026-08-17
**Issue:** #22
**Status:** Approved

## Goal

Wire all engine-only v0.4 components into user-reachable features, fix the versioning scheme to D-8, renumber the ADR collision, defer F-03 explicitly, and close v0.4 with a tagged release.

## Batch 1: Corrections (highest priority)

### W-10 — D-8 versioning
- Create `version.properties` at project root: `major=0`, `minor=4`, `patch=0`
- `app/build.gradle.kts`: read version file, `versionName = "$major.$minor.$patch"`, `versionCode = major*10000 + minor*100 + patch`
- Git SHA via Gradle `ValueSource` (config-cache-safe), degrading to empty without `.git`
- `BuildConfig.GIT_SHA` field; About screen shows `0.4.0 (abc1234) · direct`
- Delete `build-number.txt`, remove `BUILD_NUMBER` from BuildConfig
- Update `CrashLogger`/`AnrWatchdog` to log version+SHA instead of BUILD_NUMBER
- Update `SettingsScreen` About section to use new version format

### W-11 — ADR renumber
- Rename `docs/adr/002-performance-verification.md` → `docs/adr/014-performance-verification.md`
- Update any references in CHANGES.md, MEMORY.md, other ADRs

## Batch 2: Core wiring (JVM-testable)

### W-01 — ReportScope reconciliation
- Add `DIFF_ONLY` and `CONTEXT(n: Int)` variants to `ReportScope` (sealed class replacing enum)
- `DIFF_ONLY`: only hunk lines, no surrounding context
- `CONTEXT(n)`: hunks + n context lines on each side
- Update `htmlSideBySide`, `unifiedDiffPatch`, `htmlUnified` to respect new scopes
- Tests for both new scopes

### W-05 — Word-level merge wiring
- Add `MergeEngine.mergeWordLevel(hunkIndex, result, leftLines, rightLines, selections): MergeAction` that uses `WordMerge`
- Extend merge property test to cover word-level merges

## Batch 3: UI wiring (compile-verifiable)

### W-02 — Compare bookmarks UI
- Long-press on diff row toggles `CompareBookmark` in `CompareState`
- Bookmark gutter indicator (icon on bookmarked rows)
- Next/prev bookmark buttons in compare toolbar
- Persist via `Session.compareBookmarks` on session save

### W-03 — Swipe diff navigation
- Attach `SwipeDiffDetector.detectSwipeDiff()` to `UnifiedDiffView` and `SplitDiffView`
- Wire `onPrevDiff`/`onNextDiff` to existing `CompareState` navigation
- Settings toggle in compare menu (default on)

### W-04 — Outline + bracket jump
- `SymbolOutlineSheet` composable in `feature/editor`: `ModalBottomSheet` listing symbols from `SymbolExtractor`
- Tap symbol → jump to line via `EditorState.moveCaret()`
- "Jump to matching bracket" action: Ctrl+Shift+M shortcut + overflow menu item
- Both disabled when `DocumentLimits.editorTier(size) != FULL_MEMORY`

### W-06 — Hex view toggle
- "View as hex" item in editor overflow menu
- Toggle shows `HexGrid` instead of text editor content
- Feed `HexGrid` with file bytes (read from URI via ContentResolver, or from `LargeFileDocument` channel)
- Binary-detected files (§13 `OmniError.NoTextLayer`) auto-open in hex view

### W-07 — Session groups + search UI
- Home screen: group chips above session list, tap to filter
- "New group" / "Rename" / "Delete" via long-press menu on group chip
- Search field at top of home screen filtering sessions
- Staleness badge: check fingerprint on session sources, show indicator if changed

### W-08 — Theme editor
- Settings → Themes section: list built-in + user themes
- Create/edit screen: colour pickers per `TokenType`, live preview panel
- Import/export via share sheet (JSON)

### W-09 — Accessibility pass
- `contentDescription` on every diff row: `"${side} line ${lineNum}, ${changeType}"`
- `semantics` blocks on merge buttons, find bar controls, tab strip items
- `clearAndSetSemantics` on decorative gutter elements

## Batch 4: Deferrals + cleanup

### W-12 — F-03 explicit deferral
- Add deferral note to ADR-012: "Large-file editing (INDEXED_EDITABLE tier) deferred to v0.5. Read-only tier (INDEXED_READ_ONLY) is the v0.4 deliverable."
- Correct CHANGES.md: distinguish F-03 as deferred, not done

### W-13 — LongJobService wiring
- In `CompareCoordinator`: estimate compare duration from line count, if >500k lines start `LongJobService`
- Service receives progress updates via a singleton `JobProgressReporter`
- Cancel button in notification stops the coroutine job

## Constraints
- `core/model` and `core/diff` must not import `android.*`
- Both flavours must build after every task
- No new dependencies
- Tests in same commit as code
- Version file is the single source of truth for version numbers
