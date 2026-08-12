# ADR-010 — Deferred module wiring

**Date:** 2026-08-12
**Status:** Accepted
**Requirement:** R-39 (dead code and honesty sweep)

---

## Context

R-39 requires that every public type in `core/` either has a non-test consumer or is
documented here with a reason for deferral. The sweep performed in the same commit
found the following types referenced only in tests.

---

## Unreferenced types and deferral reasons

### `BlockDiff` (`core/diff`)

**Purpose:** Block-mode diff for large files (OE-ENG-4). Divides files into fixed blocks,
compares block hashes, then runs full line-level diff only in changed regions. Keeps heap
proportional to `changed_region_size` rather than `file_size`.

**Why deferred:** `DiffEngine` currently uses `HistogramDiff` for all files. Integrating
`BlockDiff` requires a size-threshold branch in `DiffEngine` and a device benchmark to
validate OE-ENG-4 (≤60 MB heap on a 300 MB file). Both require a physical device and are
deferred to P2 per `docs/adr/002-performance-budgets.md`.

**Deferral condition:** Wire into `DiffEngine` when OE-ENG-4 benchmarks can be run on a
physical device.

---

### `Diff3` (`core/diff`)

**Purpose:** Three-way diff3 merge algorithm (OE-3W-1..4). Produces a sequence of
`StableRegion` / `LeftChange` / `RightChange` / `Conflict` regions from base, left, and
right line arrays.

**Why deferred:** The three-pane merge UI is deferred to P2 per
`docs/adr/008-three-pane-deferred.md`. `MergeEngine` covers the two-way merge case that
P1 ships. `Diff3` is fully tested and ready to be wired into the three-way merge screen
when that feature is built.

**Deferral condition:** Wire into the three-pane merge screen in P2.

---

### `FileIndexer` (`core/io`)

**Purpose:** Indexes a file from disk by memory-mapping it and building a `LineIndex`.
Designed for large files that cannot be loaded into a `String`; used by `DiffEngine` to
obtain per-line hashes without reading the entire file into heap.

**Why deferred:** The P1 app layer loads file content as text via `SourceProvider` and
passes it to `PieceTableDocument.create()`, which builds its own index from the in-memory
string. `FileIndexer` is the path needed to stream files directly from disk without a full
in-memory copy. This path is deferred until the large-file flow (OE-ENG-4) is wired, at
which point `FileIndexer` replaces the in-memory load for files above the threshold.

**Deferral condition:** Wire alongside `BlockDiff` in P2 large-file path.

---

### `AccessibilityConfig` (`core/model`)

**Purpose:** Serialisable data class holding reduced-motion, high-contrast, and minimum
touch-target preferences (NFR-A1, NFR-A2).

**Why deferred:** The settings screen that reads and writes `AccessibilityConfig` is a P2
deliverable. The model class and its serialisation are complete and tested. The P1 app
always uses defaults; the class is not deleted because removing it would destroy the
settled schema.

**Deferral condition:** Wire into the settings screen in P2.

---

### `DocumentMeta` (`core/model`)

**Purpose:** Serialisable metadata for an open document (OE-EDT-2, M5). Holds caret
position, scroll offset, encoding, line ending and dirty flag — the part of editor state
that must survive process death and is displayed in the tab strip.

**Why deferred:** The tab-state persistence path (save/restore `DocumentMeta` across
process death) requires the settings/session infrastructure that is a P2 deliverable.
The P1 app holds document state in-memory only. The schema is settled and tested; the
class is kept so that the on-disk format is stable when the persistence path is wired.

**Deferral condition:** Wire into the session persistence path in P2 alongside
`AccessibilityConfig`.

---

## Types confirmed wired (not deferred)

The following types from the original R-39 checklist were found referenced in production
(non-test) code at the time of this sweep:

| Type | Location confirmed |
|---|---|
| `IntraLineDiff` | `feature/compare/IntraLineHighlight.kt` |
| `MergeSafety` | `app/OmniNavGraph.kt` |
| `ReportGenerator` | `app/OmniNavGraph.kt` |
| `SessionStore` | `app/OmniNavGraph.kt` |
| `ResultStore` | `app/OmniNavGraph.kt` |
| `LineIndex` | `core/io/FileIndexer.kt`, `PieceTableDocument.kt`, `TextDocument.kt` |
| `ContrastChecker` | `app/OmniApplication.kt` |
| `KeyboardShortcuts` | `design/KeyboardShortcutsSheet.kt` |
| `ThemeDefinition` | `app/OmniApplication.kt` |
