# v0.4 Compare/Editor UX Completion — Design Spec

**Date:** 2026-08-17
**Issue:** #16
**Status:** Approved

## Goal

Complete all remaining spec P1 feature gaps in compare, editor, sessions, reports, and platform surfaces. Pre-task: split OmniNavGraph into per-screen coordinators.

## Sub-project 1: Core implementable items

### C.4 — NavGraph split

Split `OmniNavGraph.kt` (~1,400 lines) into focused files:
- `EditorCoordinator.kt` — editor open, save, fingerprint, reload logic
- `CompareCoordinator.kt` — compare setup, diff execution, merge save, report sharing
- `OmniNavGraph.kt` — route declarations and navigation only

The existing `EditorDestination`, `CompareDestination`, `HomeDestination`, `SetupDestination` composable functions stay as-is but move to their coordinator files. Helper functions (`readUriIntoRegistry`, `queryUriMeta`, `executeMergeSave`, `shareCompareReport`, `uriToFileOrNull`) move to the coordinator that owns them.

### F-06 — Compare find completion (OE-FND-1)

Add find bar to `CompareScreen` reusing `FindReplace.findAll()`:
- Case-sensitivity toggle, whole-word toggle, regex toggle
- Per-side match counts displayed (e.g., "Left: 3 / Right: 5")
- Previous/next navigation stepping through matches across both sides
- Reuse `FindReplaceBar` composable from `feature/editor` (move to `design` module if needed, or duplicate minimally)

### F-10 — Word-level merge (OE-MRG-2)

Extend the active-line sheet (R-29) to support word-level partial merge:
- When a CHANGED hunk is tapped, the sheet shows both lines with intra-line highlighting (already exists)
- Add word-level selection: tap a highlighted word segment to toggle its inclusion
- Apply partial merge: construct the merged line from selected left/right words
- Single undo step via `beginBatch()`/`commitBatch()`

### C.6 — Error-model audit

Grep for catch-all error strings (`catch (e: Exception)`, generic error messages). Verify every user-visible failure maps to an `OmniError` variant and a §13 state. Fix any gaps found.

## Sub-project 2: Scaffolded items (core logic + data models)

### F-07 — Hex view data model
- `HexViewModel` with `ByteArray` or `FileChannel` source, bytes-per-row (8/16/32), offset display
- `HexRow` data class: address, hex bytes, ASCII column
- Shared component design for reuse by F-18 (binary compare at v0.6)
- UI composable scaffolded but requires device testing

### F-08 — Compare bookmarks
- `CompareBookmark` data class in `core/model`: `lineIndex: Long`, `side: Side`, `label: String?`
- Persist with session via `SessionStore` (add `bookmarks: List<CompareBookmark>` to `Session`)
- "Differences only in ignored content" state: new `CompareUiState` variant

### F-09 — Swipe diff-to-diff (ADR needed)
- ADR for gesture conflict resolution with ADR-011 horizontal scroll
- Recommended: fling-at-bound approach (horizontal fling at scroll edge triggers diff nav)
- Scaffold the gesture detector; actual conflict resolution needs device testing

### F-11 — Symbol outline + bracket matching
- `SymbolOutline` from existing lexer token stream (filter KEYWORD + TYPE tokens at line start)
- Bracket matching: scan for `(){}[]` pairs from caret position
- Disable above full-index threshold

### F-12 — Report completion
- HTML side-by-side layout in `ReportGenerator`
- Scope options (all hunks / selection / visible)
- Header/footer with rules + engine mode
- PDF via print service (scaffold — needs device)

### F-13 — Session groups + JSON export
- `SessionGroup` data class, group CRUD in `SessionStore`
- JSON export/import with `schemaVersion` for cross-device sync bridge
- Search already exists in `SessionStore`

### F-14 — Theme editor
- `UserTheme` data class with token colour overrides
- JSON import/export
- Theme preview composable (scaffold)

### F-15 — Accessibility pass
- TalkBack labels per diff row (side + change type)
- Content descriptions on all interactive elements
- Scaffold automated a11y checks

### F-16 — Platform surfaces
- App shortcuts for "New compare" and "New editor"
- Quick-settings tile scaffold for "Compare clipboard"

## Constraints

- NavGraph split must not change any behaviour — pure refactor
- `core/model` and `core/diff` must not import `android.*`
- Both flavours must build after every task
- No new dependencies without `docs/licenses.md` entry
