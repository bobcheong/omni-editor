# F-03 Large-File Editing + F-10 Word-Merge UI — Design Spec

**Sub-project A of issue #10 (v0.5 Linux desktop port)**

**Goal:** Land the two v0.5 riders — large-file editing (INDEXED_EDITABLE tier) and word-merge UI in the active-line sheet — before the KMP source-set split touches the editor/compare surfaces.

**Architecture:** Channel-backed piece table for F-03; inline word-level toggle in ActiveLineSheet for F-10. Both build on proven engine code already tested at Tier 1.

**Decisions recorded here:**
- Approach 1 (channel-backed PieceTable variant) chosen over overlay document (line-granular) and load-on-edit (region stitching edge cases).
- App-ID ADR deferred to Sub-project B (KMP conversion).

---

## Global constraints

- `core/model` and `core/diff` must not import `android.*` or `androidx.*` (`checkCorePurity`).
- All file access through `SourceProvider`. No `java.io.File` outside `core/io` and flavour source sets (`checkIoBoundary`).
- No code path may be O(file) per keystroke or per rendered row.
- Line count is `newlines + 1` (ADR-007).
- Long operations cancellable with `ensureActive()` every 4096 lines.
- Every failure maps to an `OmniError` variant and a named UI state (spec section 13).
- Both flavours (`direct`, `store`) must build and pass tests.
- Tests land in the same commit as the code they test.
- Commit messages reference requirement IDs.
- ADR-015 committed before any F-03 implementation code.
- Benchmark left as explicit unverified item (no device in this environment); ceiling raise requires recorded benchmark per ADR-012/D-2/D-7.

---

## F-03: Large-File Editing (INDEXED_EDITABLE tier)

### Overview

The current `LargeFileDocument` is read-only (`INDEXED_READ_ONLY` tier, 16–256 MiB). F-03 adds a new `LargeFileEditableDocument` backed by a `ChannelPieceTable` that treats the `FileChannel` as the original buffer instead of a `String`.

### ADR-015: Channel-backed piece table

Must be committed before any implementation. Covers:

1. **Byte/char offset mapping** — CHANNEL pieces store byte ranges from `LineIndex`. Decode happens at piece boundaries. Piece splits decode first, find the char offset, then re-encode to compute the byte split point. Multi-byte characters are never split across piece boundaries.
2. **Encoding gate** — INDEXED_EDITABLE tier is gated to UTF-8 and ASCII. Files detected as UTF-16/UTF-32/other encodings in 16–256 MiB remain INDEXED_READ_ONLY. Stated decision, not an accident. Gate lifted per-encoding with a recorded benchmark per ADR-012 ceiling-raise policy.
3. **External-change fingerprint policy** — On open: record size + last-modified + content hash (FNV-1a of first+last 4 KiB). Before every `materialise()`: re-check; mismatch -> `OmniError.ExternallyModified`. On process resume: re-check; mismatch -> prompt reload or save-as.
4. **LRU read strategy** — 2048-entry LRU cache keyed by `(byteOffset, byteLength)`, value is decoded `String`. CHANNEL pieces in cache remain valid across edits (edits go to ADDITIONS buffer). Cache entries invalidated only on piece splits.
5. **Ceiling** — Launches at current 16 MiB boundary. Raise requires D-2/D-7 benchmark.
6. **Trigger to revisit** — When UTF-16 editing or mmap-based read path is needed.
7. **Save path by flavour:**
   - `direct`: temp file + atomic rename. Channel reads old inode while streaming to temp. Rename replaces atomically. Channel reopened after save.
   - `store` (SAF): copy channel to temp first (SAF `openOutputStream("w")` truncates in place, destroying the read buffer). Stream from temp + edits to SAF output. Delete temp after successful write.

### ChannelPieceTable

**Buffer types:**

```kotlin
enum class Buffer { ORIGINAL, ADDITIONS, CHANNEL }
```

`CHANNEL` is the new third type. `ORIGINAL` remains for `PieceTable` (in-memory). `ADDITIONS` is shared by both.

**Piece structure for CHANNEL:**

CHANNEL pieces need `Long` byte offsets but the existing `Piece.start` is `Int`. Solution: a side table `channelRegions: ArrayList<ChannelRegion>` where `ChannelRegion(byteOffset: Long, byteLength: Int)`. A CHANNEL piece's `start` field is the index into this side table. Its `length` field is the byte length (redundant with the region, but kept for AVL `charCount` bookkeeping after decode).

```kotlin
data class ChannelRegion(val byteOffset: Long, val byteLength: Int)

Piece(
    buffer = Buffer.CHANNEL,
    start: Int,           // index into channelRegions
    length: Int           // decoded char count (set after first decode)
)
```

On first decode, `length` is updated from byte length to decoded char count. The side table is append-only (new entries added on piece splits).

**Reading a CHANNEL piece:**

1. Check LRU cache for `(byteOffset, byteLength)`.
2. Cache miss: `channel.read(ByteBuffer.allocate(byteLength), byteOffset + bomLength)` -> decode with charset -> cache result.
3. Return cached `String`.

**Splitting a CHANNEL piece at char position `n`:**

1. Decode the full piece content (via cache or channel read).
2. Encode `content.substring(0, n)` to get byte length of the left half.
3. Left piece: `(byteOffset, leftByteLength)`. Right piece: `(byteOffset + leftByteLength, originalByteLength - leftByteLength)`.
4. Invalidate the original cache entry; the two new pieces will be cached on next read.

**Edit/undo/redo:** Identical to `PieceTable`. New content appended to `additions: StringBuilder`. `EditRecord` captures `(type, offset, deleted, inserted)` in char units. The AVL rebalancing, coalescing, and journal logic are unchanged.

### LargeFileEditableDocument

Implements `TextDocument`. Wraps `ChannelPieceTable`.

```kotlin
class LargeFileEditableDocument(
    private val table: ChannelPieceTable,
    private val channel: FileChannel,
    private val charset: Charset,
    private val bomBytes: ByteArray,
    private val fingerprint: FileFingerprint
) : TextDocument
```

- `line(index)` — delegates to `table.line(index)` (same O(log p) as PieceTableDocument).
- `edit()`, `undo()`, `redo()` — delegates to table, emits `DocumentChange` on the shared flow.
- `beginBatch()` / `commitBatch()` — same batch logic as `PieceTableDocument`.
- `materialise(into: WritableByteChannel)` — fingerprint check first; then writes BOM bytes, then streams pieces in order. CHANNEL pieces: direct byte copy from file channel (no decode/re-encode). ADDITIONS pieces: encode and write.
- `dirty`, `editGeneration` — same tracking as `PieceTableDocument`.
- `text()` — **throws `UnsupportedOperationException`** for INDEXED_EDITABLE tier. This method is O(file) and must not be called on large files. Any caller that needs full text must use `materialise()`.
- `close()` — closes channel and `RandomAccessFile`.

### FileFingerprint

```kotlin
data class FileFingerprint(
    val size: Long,
    val lastModified: Long,
    val contentHash: Long       // FNV-1a of first 4 KiB + last 4 KiB
)
```

- `FileFingerprint.of(file: File): FileFingerprint` — computes from file.
- `FileFingerprint.check(file: File, expected: FileFingerprint): Boolean` — returns true if unchanged.

### DocumentLimits update

```kotlin
enum class SizeTier {
    FULL_MEMORY,         // 0–16 MiB, PieceTableDocument
    INDEXED_EDITABLE,    // 16–256 MiB, LargeFileEditableDocument (UTF-8/ASCII only)
    INDEXED_READ_ONLY,   // 16–256 MiB, LargeFileDocument (non-UTF-8 large files)
    REFUSED              // >256 MiB
}
```

`editorTier(sizeBytes, charset)` now takes charset:
- <= 16 MiB -> FULL_MEMORY
- 16–256 MiB + UTF-8/ASCII -> INDEXED_EDITABLE
- 16–256 MiB + other encoding -> INDEXED_READ_ONLY
- > 256 MiB -> REFUSED

`compareTier()` unchanged (both sides remain read-only in compare).

### OmniError.ExternallyModified

New variant added to the sealed interface:

```kotlin
data class ExternallyModified(val path: String) : OmniError
```

UI state: error screen with message "File was modified externally" and options "Reload" / "Save as...".

### Wiring in EditorCoordinator

When `editorTier()` returns `INDEXED_EDITABLE`:
1. Run `FileIndexer.index()` (shows progress).
2. Open `RandomAccessFile` + `FileChannel`.
3. Construct `ChannelPieceTable` from `LineIndex` + channel.
4. Wrap in `LargeFileEditableDocument`.
5. Pass to `EditorViewModel.openLargeDocument()` with `readOnly = false`.

### Parameterised test suite

Extract shared test logic into `AbstractPieceTableTest`:
- All existing `PieceTableTest` cases: insert, delete, replace, undo, redo, coalescing, batch, line model, dirty tracking, edit generation.
- All existing property tests: random edit sequences, undo-all returns to original, line count = newlines + 1.
- Two concrete subclasses: `StringPieceTableTest` (existing) and `ChannelPieceTableTest` (new, writes test content to a temp file, opens channel).
- Identical assertions across both. This is the proof that the extraction is safe.

Additional `ChannelPieceTable`-specific tests:
- Multi-byte UTF-8 content: piece split never breaks a multi-byte char.
- BOM re-emission on materialise.
- LRU cache hit/miss verification.
- External modification detection (modify file between open and materialise).
- Save path: materialise to temp, verify byte-identical to expected output.

---

## F-10: Word-Merge UI in ActiveLineSheet

### Overview

The engine (`MergeEngine.mergeWordLevel()` + `WordMerge`) is implemented and tested. F-10 adds the UI surface in `ActiveLineSheet` to make it user-reachable.

### UI design

A "Word" `FilterChip` toggle is added to the existing `ActiveLineSheet`, next to the hunk-level accept buttons. Available only for `CHANGED` hunks with paired lines (not insertions/deletions).

**When "Word" is toggled on:**

1. `IntraLineDiff.compute(leftLine, rightLine, WORD)` produces paired change ranges.
2. Each range is rendered as a selectable chip:
   - Left text shown in deletion colour (red background), right text in insertion colour (green background).
   - Tap toggles between `LEFT` (default) and `RIGHT`.
   - Selected side is visually emphasised (full opacity); unselected side is dimmed.
3. Live preview line below the chips shows the merged result, updating as selections change. Computed by `WordMerge.merge(leftLine, rightLine, WORD, currentSelections)`.
4. "Apply word merge" button at the bottom.

**On apply:**
1. `beginBatch()` on the target document.
2. Call `MergeEngine.mergeWordLevel()` with the current selections.
3. Apply each `MergeAction` to the document.
4. `commitBatch()` — single undo step (R-54 lesson).
5. Close the sheet, refresh diff state.

### Asymmetric hunks

When left/right line counts differ, word-level mode is available only for the `min(leftCount, rightCount)` paired lines. Remaining unpaired lines show as hunk-level only with a label: "n unpaired line(s) — use hunk-level accept".

### Accessibility (W-09)

Every word-change chip gets:
```
contentDescription = "Change $n: left is '$leftText', right is '$rightText', currently taking $side"
```

The toggle action announces the new state via `LiveRegion`. The "Word" toggle chip itself has `contentDescription = "Word-level merge mode"`.

### Property test (C.3 extension)

- All selections `LEFT` on any line pair => result is byte-identical to the left line.
- All selections `RIGHT` on any line pair => result is byte-identical to the right line.
- Parameterised over the golden corpus (100-line generated files with known diffs).

### State management

```kotlin
// In CompareState or ActiveLineSheet composable state:
var wordModeEnabled: Boolean
var wordSelections: List<WordMerge.Side>  // one per IntraLineDiff range
```

Selections reset when:
- The sheet is dismissed.
- A different hunk is selected.
- Word mode is toggled off.

---

## Files touched

### New files
- `docs/adr/015-channel-piece-table.md` — ADR covering offset mapping, encoding gate, external-change policy, read strategy, ceiling
- `core/io/src/main/kotlin/com/omnieditor/core/io/ChannelPieceTable.kt` — channel-backed piece table
- `core/io/src/main/kotlin/com/omnieditor/core/io/LargeFileEditableDocument.kt` — editable TextDocument over ChannelPieceTable
- `core/io/src/main/kotlin/com/omnieditor/core/io/FileFingerprint.kt` — fingerprint for external-change detection
- `core/io/src/test/kotlin/com/omnieditor/core/io/ChannelPieceTableTest.kt` — parameterised + channel-specific tests
- `core/io/src/test/kotlin/com/omnieditor/core/io/LargeFileEditableDocumentTest.kt` — document-level tests
- `core/io/src/test/kotlin/com/omnieditor/core/io/FileFingerprintTest.kt` — fingerprint tests

### Modified files
- `core/model/.../DocumentLimits.kt` — add `INDEXED_EDITABLE` tier, charset parameter on `editorTier()`
- `core/model/.../OmniError.kt` — add `ExternallyModified` variant
- `core/io/.../PieceTable.kt` — extract shared logic or add `CHANNEL` buffer type
- `core/io/src/test/.../PieceTableTest.kt` — refactor into parameterised base
- `feature/compare/.../ActiveLineSheet.kt` — add word-level toggle, chips, preview, apply
- `feature/compare/.../CompareState.kt` — add word-mode state fields
- `app/.../EditorCoordinator.kt` — wire INDEXED_EDITABLE tier dispatch
- `docs/adr/012-size-ladder.md` — update table with INDEXED_EDITABLE row
- `CHANGES.md` — F-03, F-10 entries
- `core/diff/src/test/.../WordMergeTest.kt` — add C.3 property tests (all-left, all-right identity)

---

## Out of scope

- Benchmark on reference device (no device in this environment; left as explicit unverified item per ADR-012 ceiling-raise policy).
- UTF-16/UTF-32 large-file editing (gated to UTF-8/ASCII; other encodings remain INDEXED_READ_ONLY).
- Compare-side editing of large files (compare sides remain read-only).
- App-ID ADR (deferred to Sub-project B).
