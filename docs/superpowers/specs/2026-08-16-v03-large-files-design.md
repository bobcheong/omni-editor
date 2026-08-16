# v0.3 Large Files and Data Paths — Design Spec

**Date:** 2026-08-16
**Issue:** #15
**Status:** Approved

## Goal

Wire existing large-file infrastructure (FileIndexer, BlockDiff) into the live open and compare paths, raise size ceilings with a benchmarked size ladder, add a foreground service for long compares, close the release pipeline gap, and add differential and merge property tests.

## Sub-project A: Core engine wiring (F-01 + F-02)

### F-01 — FileIndexer into open path

**Size ladder** replacing ADR-003's cliff (new ADR-012):

| Tier | Size | Behaviour | Disclosed as |
|---|---|---|---|
| `FULL_MEMORY` | ≤16 MiB | Current in-memory path (String → PieceTable) | *(no disclosure)* |
| `INDEXED_READ_ONLY` | 16–256 MiB | FileIndexer + mmap'd channel, read-only | "Read-only (large file)" |
| `REFUSED` | >256 MiB | Refused with reason | OmniError.TooLarge |

**New class: `LargeFileDocument`** in `core/io`:
- Wraps `FileIndexer.IndexResult` + `FileChannel` (mmap'd by FileIndexer)
- Implements `TextDocument` interface:
  - `line(index)`: reads from channel using LineIndex offsets/lengths
  - `lineCount`, `length`: from IndexResult
  - `edit()`, `undo()`, `redo()`: throw `UnsupportedOperationException` (read-only)
  - `beginBatch()`, `commitBatch()`: no-op (read-only)
  - `materialise()`: copies from channel to output
  - `dirty`: always false
  - `editGeneration`: always 0
  - `changes`: empty flow
- Implements `Closeable` to release the channel

**NavGraph changes:**
- `readUriIntoRegistry()` checks file size against `DocumentLimits.tier(size)`
- `FULL_MEMORY`: existing path (read content → PieceTableDocument)
- `INDEXED_READ_ONLY`: copy URI to temp file → `FileIndexer.index()` → `LargeFileDocument` → open read-only
- `REFUSED`: `EditorUiState.OverThreshold` (existing)
- Editor header shows tier disclosure string when not FULL_MEMORY

### F-02 — BlockDiff into compare path

**Threshold:** Use `BlockDiff.DEFAULT_LINE_THRESHOLD` (250,000 lines) as the branch point.

**Compare flow changes:**
- After loading both documents, check `leftDoc.lineCount + rightDoc.lineCount`
- If above threshold: call `BlockDiff.compare()` instead of `DiffEngine.compare()`
- `CompareResult.engineMode` already distinguishes `FULL_INDEX` vs `BLOCK_MATCH`
- Compare status bar shows engine mode when not `FULL_INDEX`

**Tests:**
- Golden-corpus parity: below threshold, BlockDiff and DiffEngine produce identical hunks
- JVM heap test: compare two 50k-line files via BlockDiff, assert heap stays reasonable

## Sub-project B: Large-file editing (F-03) — scaffold

**Extend `LargeFileDocument`** to optionally support editing:
- New tier `INDEXED_EDITABLE` between `FULL_MEMORY` and `INDEXED_READ_ONLY` (64–256 MiB)
- `LargeFileDocument` wraps a `PieceTable` overlay on the channel-backed original text
- Editing delegates to the overlay PieceTable; reads merge original + overlay
- `materialise()` flattens overlay + original through the atomic write path

**Scope for this implementation:** Write the `PieceTable.createFromChannel()` factory and JVM tests. The NavGraph wiring to use `INDEXED_EDITABLE` tier is scaffolded with a clear integration point but marked as requiring device verification.

## Sub-project C: Foreground service (F-04) — scaffold

**`LongJobService`** in `app/src/main`:
- Extends `Service`, runs as foreground with notification (progress bar + cancel)
- Generic host: accepts `suspend (onProgress: (Float) -> Unit) -> T` coroutine
- Compare flow checks estimated duration; if >10 s, wraps in `LongJobService`
- Manifest entry with `FOREGROUND_SERVICE` permission

**Scope:** Class structure, manifest entries, and the service lifecycle. No device test — marked as requiring Tier 2 verification.

## Sub-project D: Release pipeline (F-05)

- Replace hardcoded `versionCode = 1` with dynamic derivation: `git rev-list --count HEAD`
- Fallback to 1 when git is unavailable (CI without full clone)
- Verify `release.yml` workflow config is correct
- Do NOT tag — tagging is a release action, not an implementation task

## Sub-project E: Test improvements (C.2 + C.3)

### C.2 — Differential testing
JVM test in `core/diff` that:
1. Runs `git diff --no-index --histogram` on golden corpus file pairs
2. Parses the unified diff output to extract changed line ranges
3. Compares with `DiffEngine.compare()` output
4. Asserts semantic equivalence (same changed regions, allowing for context differences)

### C.3 — Merge property test
JVM property test in `core/diff` or `core/io` that:
1. Generates a random document pair
2. Runs `DiffEngine.compare()` to get hunks
3. Applies all left→right replacements to the left document
4. Asserts the result is byte-identical to the right document

## Constraints

- `core/model` and `core/diff` must not import `android.*` or `androidx.*`
- `LargeFileDocument` lives in `core/io` (which is allowed to use `java.io.File`)
- FileIndexer and BlockDiff are already in `core/io` and `core/diff` respectively
- No new dependency without `docs/licenses.md` entry
- Both flavours must build
- ADR-012 (size ladder) required before implementation
- Tests land in the same commit as the code they test
