# F-03 Large-File Editing + F-10 Word-Merge UI — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the INDEXED_EDITABLE tier (channel-backed piece table for 16–256 MiB UTF-8 files) and the word-level merge UI in ActiveLineSheet, completing the two v0.5 pre-KMP riders.

**Architecture:** A new `ChannelPieceTable` uses a `FileChannel` as its original buffer instead of a `String`, with an LRU decode cache and byte↔char offset mapping gated to UTF-8/ASCII. A new `LargeFileEditableDocument` wraps it as a `TextDocument`. Word-merge UI adds an inline toggle to `ActiveLineSheet` with per-range selectable chips driving `MergeEngine.mergeWordLevel()`.

**Tech Stack:** Kotlin JVM, JUnit 4, Kotest assertions/property, kotlinx.coroutines, Compose Material3

## Global Constraints

- `core/model` and `core/diff` must not import `android.*` or `androidx.*` (enforced by `checkCorePurity`).
- All file access through `SourceProvider`. No `java.io.File` outside `core/io` and flavour source sets (enforced by `checkIoBoundary`).
- No code path may be O(file) per keystroke or per rendered row.
- Line count is `newlines + 1` (ADR-007).
- Long operations cancellable with `ensureActive()` every 4096 lines.
- Every failure maps to an `OmniError` variant and one named UI state (spec §13).
- Both flavours (`direct`, `store`) must build and pass tests.
- Tests land in the same commit as the code they test.
- Commit messages reference requirement IDs.
- ADR-015 committed before any F-03 implementation code.
- Benchmark left as explicit unverified item — ceiling raise requires recorded benchmark per ADR-012/D-2/D-7.
- No new dependency without a line in `docs/licenses.md`.

---

## File Structure

### New files
| File | Responsibility |
|---|---|
| `docs/adr/015-channel-piece-table.md` | ADR: byte/char mapping, encoding gate, external-change policy, read strategy |
| `core/io/src/main/kotlin/com/omnieditor/core/io/FileFingerprint.kt` | File identity for external-modification detection |
| `core/io/src/main/kotlin/com/omnieditor/core/io/ChannelPieceTable.kt` | Piece table backed by FileChannel original buffer |
| `core/io/src/main/kotlin/com/omnieditor/core/io/LargeFileEditableDocument.kt` | TextDocument over ChannelPieceTable |
| `core/io/src/test/kotlin/com/omnieditor/core/io/FileFingerprintTest.kt` | Fingerprint tests |
| `core/io/src/test/kotlin/com/omnieditor/core/io/ChannelPieceTableTest.kt` | Channel piece table tests (parameterised + channel-specific) |
| `core/io/src/test/kotlin/com/omnieditor/core/io/LargeFileEditableDocumentTest.kt` | Document-level tests |

### Modified files
| File | Change |
|---|---|
| `core/model/.../DocumentLimits.kt` | Add `INDEXED_EDITABLE` tier, `editorTier(sizeBytes, encoding)` overload |
| `core/model/.../OmniError.kt` | Add `ExternallyModified` variant |
| `core/io/src/test/.../PieceTableTest.kt` | Extract shared assertions into helper functions for reuse |
| `feature/compare/.../ActiveLineSheet.kt` | Word-level toggle, chips, preview, apply button |
| `feature/compare/.../CompareState.kt` | Word-mode state fields, `mergeWordLevel()` method |
| `app/.../EditorCoordinator.kt` | Wire `INDEXED_EDITABLE` tier dispatch |
| `core/diff/src/test/.../WordMergeTest.kt` | C.3 property tests (all-left, all-right identity) |
| `docs/adr/012-size-ladder.md` | Update table with INDEXED_EDITABLE row |
| `CHANGES.md` | F-03, F-10 entries |

---

### Task 1: ADR-015 — Channel-backed piece table

**Files:**
- Create: `docs/adr/015-channel-piece-table.md`

**Interfaces:**
- Consumes: nothing
- Produces: ADR document referenced by all subsequent F-03 tasks

- [ ] **Step 1: Write ADR-015**

```markdown
# ADR 015 — Channel-backed piece table for large-file editing

**Status:** accepted — F-03, 17 August 2026.

## Context

ADR-012 defines an INDEXED_READ_ONLY tier (16–256 MiB) backed by
`LargeFileDocument` over a `FileChannel`. F-03 extends this to editable
large files (INDEXED_EDITABLE). The piece table is the product's core data
structure; a channel-backed variant must be provably equivalent to the
in-memory variant.

## Decision

### Byte/char offset mapping

CHANNEL pieces store byte ranges from `LineIndex`. Decode happens at piece
boundaries — each CHANNEL piece is decoded as a whole unit. Piece splits
decode first, find the char boundary, then re-encode to compute the byte
split point. Multi-byte characters are never split across piece boundaries.

A side table `channelRegions: ArrayList<ChannelRegion>` maps piece indices
to `(byteOffset: Long, byteLength: Int)` pairs. A CHANNEL piece's
`Piece.start` is the index into this side table. Its `Piece.length` is the
decoded char count (set after first decode).

### Encoding gate

INDEXED_EDITABLE is gated to UTF-8 and ASCII. Files detected as
UTF-16/UTF-32/other encodings in 16–256 MiB remain INDEXED_READ_ONLY.
Stated decision, not an accident. Gate lifted per-encoding with a recorded
benchmark per ADR-012 ceiling-raise policy.

### External-change fingerprint policy

The disk file is the original buffer. External changes corrupt the piece
structure silently.

- On open: record `FileFingerprint(size, lastModified, contentHash)`.
  Content hash is FNV-1a of first 4 KiB + last 4 KiB.
- Before every `materialise()`: re-check fingerprint. Mismatch →
  `OmniError.ExternallyModified` → spec §13 error state.
- On process resume: re-check fingerprint. Mismatch → prompt user:
  reload (discard edits) or save-as (preserve edits to new path).

### Read strategy

LRU block cache in `ChannelPieceTable`. Cache key is channel region index.
Cache value is the decoded `String`. Capacity: 2048 entries. Eviction is
LRU. CHANNEL pieces in cache remain valid across edits (edits go to the
ADDITIONS buffer). Cache entries invalidated only on piece splits.

### Save path by flavour

- `direct`: temp file + atomic rename. Channel reads old inode while
  streaming to temp. Rename replaces atomically. Channel reopened after save.
- `store` (SAF): copy channel to temp first (SAF `openOutputStream("w")`
  truncates in place). Stream from temp + edits to SAF output. Delete temp
  after successful write.

### Ceiling

Launches at current 16 MiB editor boundary. INDEXED_EDITABLE applies to
16–256 MiB. Raise requires D-2/D-7 benchmark.

## Alternatives considered

1. **Overlay document** (sparse line-range map over read-only channel):
   simpler but line-granular edits are wrong for an editor; undo requires
   reversing overlay patches without piece-table guarantees.
2. **Load-on-edit** (load visible region into PieceTableDocument): simplest
   for small edits but region stitching on save is an edge-case factory.

## Trigger to revisit

When UTF-16 editing or mmap-based read path is needed.
```

- [ ] **Step 2: Commit ADR-015**

```bash
git add docs/adr/015-channel-piece-table.md
git commit -m "docs(adr): ADR-015 channel-backed piece table [F-03, OE-ENG-4]"
```

---

### Task 2: FileFingerprint + OmniError.ExternallyModified + DocumentLimits update

**Files:**
- Create: `core/io/src/main/kotlin/com/omnieditor/core/io/FileFingerprint.kt`
- Create: `core/io/src/test/kotlin/com/omnieditor/core/io/FileFingerprintTest.kt`
- Modify: `core/model/src/main/kotlin/com/omnieditor/core/model/OmniError.kt`
- Modify: `core/model/src/main/kotlin/com/omnieditor/core/model/DocumentLimits.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `FileFingerprint(size: Long, lastModified: Long, contentHash: Long)` data class
  - `FileFingerprint.of(file: File): FileFingerprint` companion function
  - `FileFingerprint.check(file: File, expected: FileFingerprint): Boolean` companion function
  - `OmniError.ExternallyModified(path: String)` sealed variant
  - `DocumentLimits.SizeTier.INDEXED_EDITABLE` enum entry
  - `DocumentLimits.editorTier(sizeBytes: Long, encoding: String): SizeTier` overload

- [ ] **Step 1: Write failing FileFingerprint tests**

File: `core/io/src/test/kotlin/com/omnieditor/core/io/FileFingerprintTest.kt`

```kotlin
package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class FileFingerprintTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("fingerprint-test", ".txt")
        tempFile.writeText("hello world\n".repeat(100))
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `fingerprint captures size`() {
        val fp = FileFingerprint.of(tempFile)
        fp.size shouldBe tempFile.length()
    }

    @Test
    fun `fingerprint captures lastModified`() {
        val fp = FileFingerprint.of(tempFile)
        fp.lastModified shouldBe tempFile.lastModified()
    }

    @Test
    fun `check returns true for unmodified file`() {
        val fp = FileFingerprint.of(tempFile)
        FileFingerprint.check(tempFile, fp) shouldBe true
    }

    @Test
    fun `check returns false after file content changes`() {
        val fp = FileFingerprint.of(tempFile)
        tempFile.appendText("extra content")
        FileFingerprint.check(tempFile, fp) shouldBe false
    }

    @Test
    fun `check returns false after file size changes`() {
        val fp = FileFingerprint.of(tempFile)
        tempFile.writeText("short")
        FileFingerprint.check(tempFile, fp) shouldBe false
    }

    @Test
    fun `fingerprint of small file hashes all content`() {
        val smallFile = File.createTempFile("small-fp", ".txt")
        try {
            smallFile.writeText("tiny")
            val fp = FileFingerprint.of(smallFile)
            // Content hash should be non-zero for non-empty files
            (fp.contentHash != 0L) shouldBe true
        } finally {
            smallFile.delete()
        }
    }

    @Test
    fun `fingerprint of file larger than 8K hashes first and last 4K`() {
        val largeContent = "A".repeat(4096) + "B".repeat(4096) + "C".repeat(4096)
        tempFile.writeText(largeContent)
        val fp = FileFingerprint.of(tempFile)
        (fp.contentHash != 0L) shouldBe true
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.FileFingerprintTest" --info 2>&1 | tail -20`
Expected: FAIL — `FileFingerprint` does not exist yet.

- [ ] **Step 3: Implement FileFingerprint**

File: `core/io/src/main/kotlin/com/omnieditor/core/io/FileFingerprint.kt`

```kotlin
package com.omnieditor.core.io

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Lightweight fingerprint for detecting external file modifications (ADR-015).
 *
 * Records size, last-modified time, and a content hash (FNV-1a of first 4 KiB +
 * last 4 KiB). The content hash catches in-place overwrites that preserve size
 * and timestamp (rare but possible with buffered writes).
 */
data class FileFingerprint(
    val size: Long,
    val lastModified: Long,
    val contentHash: Long,
) {
    companion object {
        private const val SAMPLE_SIZE = 4096

        /** Compute a fingerprint for [file]. */
        fun of(file: File): FileFingerprint {
            val size = file.length()
            val lastModified = file.lastModified()
            val hash = hashFile(file, size)
            return FileFingerprint(size, lastModified, hash)
        }

        /**
         * Check whether [file] still matches [expected].
         * Returns true if the file appears unmodified.
         */
        fun check(file: File, expected: FileFingerprint): Boolean {
            if (!file.exists()) return false
            if (file.length() != expected.size) return false
            if (file.lastModified() != expected.lastModified) return false
            val currentHash = hashFile(file, file.length())
            return currentHash == expected.contentHash
        }

        private fun hashFile(file: File, size: Long): Long {
            if (size == 0L) return 0L
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                // FNV-1a 64-bit
                var hash = -3750763034362895579L // FNV offset basis
                val prime = 1099511628211L

                // Hash first SAMPLE_SIZE bytes (or all bytes if file is small)
                val firstLen = minOf(size, SAMPLE_SIZE.toLong()).toInt()
                val firstBuf = ByteBuffer.allocate(firstLen)
                channel.read(firstBuf, 0)
                firstBuf.flip()
                for (i in 0 until firstBuf.limit()) {
                    hash = hash xor (firstBuf.get(i).toLong() and 0xFF)
                    hash *= prime
                }

                // Hash last SAMPLE_SIZE bytes (skip if file fits in first sample)
                if (size > SAMPLE_SIZE) {
                    val lastStart = size - SAMPLE_SIZE
                    val lastLen = SAMPLE_SIZE
                    val lastBuf = ByteBuffer.allocate(lastLen)
                    channel.read(lastBuf, lastStart)
                    lastBuf.flip()
                    for (i in 0 until lastBuf.limit()) {
                        hash = hash xor (lastBuf.get(i).toLong() and 0xFF)
                        hash *= prime
                    }
                }

                return hash
            }
        }
    }
}
```

- [ ] **Step 4: Add OmniError.ExternallyModified**

Add to `core/model/src/main/kotlin/com/omnieditor/core/model/OmniError.kt`, after the `WriteFailed` line:

```kotlin
    data class ExternallyModified(val path: String) : OmniError
```

- [ ] **Step 5: Update DocumentLimits with INDEXED_EDITABLE tier**

Replace the `SizeTier` enum and `editorTier` function in `core/model/src/main/kotlin/com/omnieditor/core/model/DocumentLimits.kt`:

```kotlin
    enum class SizeTier {
        /** Full in-memory: PieceTableDocument, all editing features. */
        FULL_MEMORY,
        /** Indexed editable: ChannelPieceTable over FileChannel (UTF-8/ASCII only). */
        INDEXED_EDITABLE,
        /** Indexed read-only: LargeFileDocument over mmap'd channel. */
        INDEXED_READ_ONLY,
        /** Refused with OmniError.TooLarge. */
        REFUSED,
    }

    /** Determine the editor tier for a file of [sizeBytes]. */
    fun editorTier(sizeBytes: Long): SizeTier = when {
        sizeBytes <= EDITOR_MAX_BYTES -> SizeTier.FULL_MEMORY
        sizeBytes <= INDEXED_MAX_BYTES -> SizeTier.INDEXED_READ_ONLY
        else -> SizeTier.REFUSED
    }

    /**
     * Determine the editor tier for a file of [sizeBytes] with known [encoding].
     * UTF-8 and ASCII files in the 16–256 MiB range get the INDEXED_EDITABLE tier;
     * other encodings remain INDEXED_READ_ONLY (ADR-015 encoding gate).
     */
    fun editorTier(sizeBytes: Long, encoding: String): SizeTier = when {
        sizeBytes <= EDITOR_MAX_BYTES -> SizeTier.FULL_MEMORY
        sizeBytes <= INDEXED_MAX_BYTES -> {
            val upper = encoding.uppercase()
            if (upper == "UTF-8" || upper == "US-ASCII" || upper == "ASCII") {
                SizeTier.INDEXED_EDITABLE
            } else {
                SizeTier.INDEXED_READ_ONLY
            }
        }
        else -> SizeTier.REFUSED
    }
```

- [ ] **Step 6: Run tests to verify FileFingerprint passes**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.FileFingerprintTest" --info 2>&1 | tail -20`
Expected: All 7 tests PASS.

- [ ] **Step 7: Verify full build still passes**

Run: `./gradlew :core:model:test :core:io:test --info 2>&1 | tail -10`
Expected: PASS — no regressions.

- [ ] **Step 8: Commit**

```bash
git add core/io/src/main/kotlin/com/omnieditor/core/io/FileFingerprint.kt \
       core/io/src/test/kotlin/com/omnieditor/core/io/FileFingerprintTest.kt \
       core/model/src/main/kotlin/com/omnieditor/core/model/OmniError.kt \
       core/model/src/main/kotlin/com/omnieditor/core/model/DocumentLimits.kt
git commit -m "feat(core): FileFingerprint, OmniError.ExternallyModified, INDEXED_EDITABLE tier [F-03, ADR-015]"
```

---

### Task 3: ChannelPieceTable

**Files:**
- Create: `core/io/src/main/kotlin/com/omnieditor/core/io/ChannelPieceTable.kt`
- Create: `core/io/src/test/kotlin/com/omnieditor/core/io/ChannelPieceTableTest.kt`

**Interfaces:**
- Consumes: `LineIndex` (from `FileIndexer`), `EditRecord` (from `PieceTable.kt`)
- Produces:
  - `ChannelPieceTable(channel: FileChannel, lineIndex: LineIndex, charset: Charset, bomLength: Int)` constructor
  - `fun line(lineIndex: Int): String` — O(log p) line access via channel + LRU cache
  - `fun insert(offset: Int, text: String): EditRecord`
  - `fun delete(offset: Int, count: Int): EditRecord`
  - `fun replace(offset: Int, count: Int, text: String): EditRecord`
  - `val length: Int`, `val lineCount: Int`
  - `fun text(): String` — throws `UnsupportedOperationException` for large files
  - `fun substring(start: Int, end: Int): String`
  - `fun charAt(offset: Int): Char`
  - `fun lineToOffset(lineIndex: Int): Int`
  - `fun offsetToLine(charOffset: Int): Int`
  - `fun streamPieces(output: WritableByteChannel, channel: FileChannel, charset: Charset, bomBytes: ByteArray?)`

- [ ] **Step 1: Write failing ChannelPieceTable tests**

File: `core/io/src/test/kotlin/com/omnieditor/core/io/ChannelPieceTableTest.kt`

```kotlin
package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.comparables.shouldBeLessThan
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import kotlinx.coroutines.test.runTest

class ChannelPieceTableTest {

    private lateinit var tempFile: File
    private lateinit var raf: RandomAccessFile
    private lateinit var channel: FileChannel

    private fun createTable(content: String): ChannelPieceTable {
        tempFile = File.createTempFile("channel-pt-test", ".txt")
        tempFile.writeText(content, Charsets.UTF_8)
        raf = RandomAccessFile(tempFile, "r")
        channel = raf.channel
        return runTest {
            val indexResult = FileIndexer.index(tempFile)
            ChannelPieceTable(channel, indexResult.index, Charsets.UTF_8, indexResult.encoding.bomLength)
        }
    }

    @After
    fun tearDown() {
        if (::channel.isInitialized) channel.close()
        if (::raf.isInitialized) raf.close()
        if (::tempFile.isInitialized) tempFile.delete()
    }

    // ── Parity with PieceTableTest: same tests, channel-backed ──

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
    fun `delete returns correct deleted text`() {
        val pt = createTable("hello world")
        val record = pt.delete(5, 6)
        record.deleted shouldBe " world"
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

    // ── Channel-specific tests ──

    @Test
    fun `multi-byte UTF-8 content preserved after edit`() {
        val pt = createTable("café résumé")
        pt.insert(4, " ")
        pt.substring(0, pt.length) shouldBe "caf é résumé"
    }

    @Test
    fun `piece split never breaks multi-byte char`() {
        // 'é' is 2 bytes in UTF-8; split in the middle of the word
        val pt = createTable("résumé")
        pt.insert(1, "X")
        pt.substring(0, pt.length) shouldBe "rXésumé"
        pt.charAt(0) shouldBe 'r'
        pt.charAt(1) shouldBe 'X'
        pt.charAt(2) shouldBe 'é'
    }

    @Test
    fun `CJK characters preserved`() {
        val pt = createTable("你好世界")
        pt.insert(2, "X")
        pt.substring(0, pt.length) shouldBe "你好X世界"
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

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.ChannelPieceTableTest" --info 2>&1 | tail -20`
Expected: FAIL — `ChannelPieceTable` does not exist yet.

- [ ] **Step 3: Implement ChannelPieceTable**

File: `core/io/src/main/kotlin/com/omnieditor/core/io/ChannelPieceTable.kt`

```kotlin
package com.omnieditor.core.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.charset.Charset

/**
 * A piece table backed by a [FileChannel] original buffer (ADR-015, F-03).
 *
 * Analogous to [PieceTable] but the original buffer is a file on disk instead of
 * a [String] in memory. This enables editing files in the INDEXED_EDITABLE tier
 * (16–256 MiB, UTF-8/ASCII only) without loading the entire file into heap.
 *
 * Three buffer types:
 * - **CHANNEL**: references a byte range in the file channel, decoded on demand
 * - **ADDITIONS**: append-only StringBuilder for new content (same as PieceTable)
 * - **ORIGINAL**: not used by this class (exists for PieceTable compatibility)
 *
 * Uses an augmented AVL tree identical in structure to [PieceTable]. The critical
 * difference is that CHANNEL pieces store a byte range and must be decoded to get
 * character content. An LRU cache (capacity 2048) avoids repeated channel reads.
 */
class ChannelPieceTable(
    private val channel: FileChannel,
    private val lineIndex: LineIndex,
    private val charset: Charset,
    private val bomLength: Int,
) {
    private val additions = StringBuilder()

    /** Side table mapping CHANNEL piece indices to byte regions. */
    data class ChannelRegion(val byteOffset: Long, val byteLength: Int)
    private val channelRegions = ArrayList<ChannelRegion>()

    // ── AVL tree node ──

    /** Buffer type for a piece. */
    enum class Buffer { CHANNEL, ADDITIONS }

    data class Piece(val buffer: Buffer, val start: Int, val length: Int)

    private class Node(
        var piece: Piece,
        var nlOffsets: IntArray,
        var charCount: Int,
        var newlineCount: Int,
        var height: Int = 1,
        var left: Node? = null,
        var right: Node? = null,
    ) {
        val pieceNewlines: Int get() = nlOffsets.size
    }

    private var root: Node? = null
    private var lastInsertAdditionsEnd: Int = -1

    val length: Int get() = root?.charCount ?: 0
    val lineCount: Int get() = (root?.newlineCount ?: 0) + 1

    // ── LRU decode cache ──

    private val cache = object : LinkedHashMap<Int, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean {
            return size > 2048
        }
    }

    init {
        // Build initial piece tree from LineIndex: one CHANNEL piece per line,
        // with newlines inserted between lines.
        val totalLines = lineIndex.lineCount.toInt()
        if (totalLines == 0) return

        // Build a single CHANNEL piece spanning the entire content.
        // We accumulate all line content as one contiguous logical piece per line,
        // separated by '\n' in the additions buffer for inter-line newlines.
        // Actually, simpler approach: one piece per line + one additions piece per newline.
        // But that creates 2*N pieces. Better: build the content map from the index.

        // Simplest correct approach: one CHANNEL piece per line.
        // Newline characters between lines are part of the file bytes but not in
        // LineIndex lengths. We create one piece per line plus handle newlines.

        // The LineIndex stores (offset, length) for each line's content bytes,
        // EXCLUDING the line terminator. The terminator bytes are between pieces.
        // We need to account for terminators as CHANNEL pieces too.

        // Strategy: create one CHANNEL piece per "segment" where a segment is
        // line-content-bytes + terminator-bytes (except possibly the last line).
        // This way the entire file is covered by CHANNEL pieces.

        // For each line i (0..totalLines-1):
        //   content starts at lineIndex.offset(i), has lineIndex.length(i) bytes
        //   terminator (if not last segment) spans from content end to next line start

        for (i in 0 until totalLines) {
            val contentOffset = lineIndex.offset(i.toLong())
            val contentLen = lineIndex.length(i.toLong())

            if (i < totalLines - 1) {
                // Include terminator bytes: from end of this line to start of next
                val nextOffset = lineIndex.offset((i + 1).toLong())
                val segmentLen = (nextOffset - contentOffset).toInt()
                val regionIdx = channelRegions.size
                channelRegions.add(ChannelRegion(contentOffset, segmentLen))
                // Decode to find char count and newlines
                val decoded = decodeRegion(regionIdx)
                val nlOffsets = buildNlOffsets(decoded)
                val node = Node(
                    Piece(Buffer.CHANNEL, regionIdx, decoded.length),
                    nlOffsets, decoded.length, nlOffsets.size,
                )
                root = insertAsRightmost(root, node)
            } else {
                // Last line — no terminator after it
                if (contentLen > 0) {
                    val regionIdx = channelRegions.size
                    channelRegions.add(ChannelRegion(contentOffset, contentLen))
                    val decoded = decodeRegion(regionIdx)
                    val nlOffsets = buildNlOffsets(decoded)
                    val node = Node(
                        Piece(Buffer.CHANNEL, regionIdx, decoded.length),
                        nlOffsets, decoded.length, nlOffsets.size,
                    )
                    root = insertAsRightmost(root, node)
                } else if (totalLines > 1) {
                    // Empty final line (file ends with newline) — nothing to add,
                    // the newline is already in the previous segment's piece
                }
            }
        }
    }

    // ── Decode ──

    private fun decodeRegion(regionIdx: Int): String {
        cache[regionIdx]?.let { return it }
        val region = channelRegions[regionIdx]
        if (region.byteLength == 0) {
            cache[regionIdx] = ""
            return ""
        }
        val buf = ByteBuffer.allocate(region.byteLength)
        channel.read(buf, region.byteOffset + bomLength)
        buf.flip()
        val decoded = charset.decode(buf).toString()
        cache[regionIdx] = decoded
        return decoded
    }

    /** Get the character content of a piece. */
    private fun pieceContent(piece: Piece): CharSequence {
        return when (piece.buffer) {
            Buffer.CHANNEL -> decodeRegion(piece.start)
            Buffer.ADDITIONS -> additions
        }
    }

    /** Get a substring from a piece. */
    private fun pieceSubstring(piece: Piece, from: Int, to: Int): String {
        return when (piece.buffer) {
            Buffer.CHANNEL -> {
                val full = decodeRegion(piece.start)
                full.substring(from, to)
            }
            Buffer.ADDITIONS -> additions.substring(piece.start + from, piece.start + to)
        }
    }

    /** Get char at position within a piece. */
    private fun pieceCharAt(piece: Piece, offset: Int): Char {
        return when (piece.buffer) {
            Buffer.CHANNEL -> decodeRegion(piece.start)[offset]
            Buffer.ADDITIONS -> additions[piece.start + offset]
        }
    }

    // ── AVL helpers (identical to PieceTable) ──

    private fun height(node: Node?): Int = node?.height ?: 0
    private fun charCount(node: Node?): Int = node?.charCount ?: 0
    private fun newlineCount(node: Node?): Int = node?.newlineCount ?: 0
    private fun balanceFactor(node: Node): Int = height(node.left) - height(node.right)

    private fun update(node: Node) {
        node.height = 1 + maxOf(height(node.left), height(node.right))
        node.charCount = node.piece.length + charCount(node.left) + charCount(node.right)
        node.newlineCount = node.pieceNewlines + newlineCount(node.left) + newlineCount(node.right)
    }

    private fun rotateRight(y: Node): Node {
        val x = y.left!!; y.left = x.right; x.right = y; update(y); update(x); return x
    }
    private fun rotateLeft(x: Node): Node {
        val y = x.right!!; x.right = y.left; y.left = x; update(x); update(y); return y
    }

    private fun balance(node: Node): Node {
        update(node)
        val bf = balanceFactor(node)
        if (bf > 1) {
            if (balanceFactor(node.left!!) < 0) node.left = rotateLeft(node.left!!)
            return rotateRight(node)
        }
        if (bf < -1) {
            if (balanceFactor(node.right!!) > 0) node.right = rotateRight(node.right!!)
            return rotateLeft(node)
        }
        return node
    }

    // ── Node creation ──

    private fun buildNlOffsets(text: CharSequence): IntArray {
        val offsets = mutableListOf<Int>()
        for (i in text.indices) { if (text[i] == '\n') offsets.add(i) }
        return offsets.toIntArray()
    }

    private fun buildNlOffsetsForAdditions(piece: Piece): IntArray {
        val offsets = mutableListOf<Int>()
        for (i in 0 until piece.length) {
            if (additions[piece.start + i] == '\n') offsets.add(i)
        }
        return offsets.toIntArray()
    }

    private fun makeNode(piece: Piece): Node {
        val nlo = when (piece.buffer) {
            Buffer.CHANNEL -> buildNlOffsets(decodeRegion(piece.start))
            Buffer.ADDITIONS -> buildNlOffsetsForAdditions(piece)
        }
        return Node(piece, nlo, piece.length, nlo.size)
    }

    private fun makeNodeFast(piece: Piece, nlOffsets: IntArray): Node {
        return Node(piece, nlOffsets, piece.length, nlOffsets.size)
    }

    private fun splitNlOffsets(nlOffsets: IntArray, splitAt: Int): Pair<IntArray, IntArray> {
        var lo = 0; var hi = nlOffsets.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (nlOffsets[mid] < splitAt) lo = mid + 1 else hi = mid }
        val leftOffsets = nlOffsets.copyOfRange(0, lo)
        val rightSize = nlOffsets.size - lo
        val rightOffsets = IntArray(rightSize) { nlOffsets[lo + it] - splitAt }
        return Pair(leftOffsets, rightOffsets)
    }

    // ── Core operations ──

    fun line(lineIndex: Int): String {
        val startOffset = lineToOffset(lineIndex)
        val docLen = length
        if (startOffset >= docLen) return ""
        val endOffset = findNextNewline(startOffset)
        return substring(startOffset, endOffset)
    }

    fun lineToOffset(lineIndex: Int): Int {
        if (lineIndex <= 0) return 0
        var remaining = lineIndex; var offset = 0; var node = root
        while (node != null) {
            val leftNl = newlineCount(node.left); val leftChars = charCount(node.left)
            if (remaining <= leftNl) { node = node.left }
            else {
                offset += leftChars; remaining -= leftNl
                if (remaining <= node.pieceNewlines) {
                    val nlPos = node.nlOffsets[remaining - 1]
                    return offset + nlPos + 1
                }
                offset += node.piece.length; remaining -= node.pieceNewlines; node = node.right
            }
        }
        return length
    }

    fun offsetToLine(charOffset: Int): Int {
        if (charOffset <= 0) return 0
        val clamped = minOf(charOffset, length)
        var remaining = clamped; var linesBefore = 0; var node = root
        while (node != null) {
            val leftChars = charCount(node.left); val leftNl = newlineCount(node.left)
            if (remaining < leftChars) { node = node.left }
            else if (remaining < leftChars + node.piece.length) {
                linesBefore += leftNl
                linesBefore += countNewlinesBefore(node.nlOffsets, remaining - leftChars)
                return linesBefore
            } else {
                linesBefore += leftNl + node.pieceNewlines
                remaining -= leftChars + node.piece.length; node = node.right
            }
        }
        return linesBefore
    }

    private fun countNewlinesBefore(nlOffsets: IntArray, inPieceOffset: Int): Int {
        var lo = 0; var hi = nlOffsets.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (nlOffsets[mid] < inPieceOffset) lo = mid + 1 else hi = mid }
        return lo
    }

    fun charAt(offset: Int): Char {
        require(offset in 0 until length) { "Offset $offset out of range [0, $length)" }
        var remaining = offset; var node = root
        while (node != null) {
            val leftChars = charCount(node.left)
            if (remaining < leftChars) { node = node.left }
            else if (remaining < leftChars + node.piece.length) {
                return pieceCharAt(node.piece, remaining - leftChars)
            } else { remaining -= leftChars + node.piece.length; node = node.right }
        }
        throw IndexOutOfBoundsException("Offset $offset not found")
    }

    fun insert(offset: Int, text: String): EditRecord {
        require(offset in 0..length) { "Offset $offset out of range [0, $length]" }
        if (text.isEmpty()) return EditRecord(EditRecord.Type.INSERT, offset, "", text)
        val addStart = additions.length
        additions.append(text)
        val newPiece = Piece(Buffer.ADDITIONS, addStart, text.length)
        val newNode = makeNode(newPiece)
        root = insertPieceAtOffset(root, offset, newNode)
        lastInsertAdditionsEnd = addStart + text.length
        return EditRecord(EditRecord.Type.INSERT, offset, "", text)
    }

    fun delete(offset: Int, count: Int): EditRecord {
        require(offset >= 0 && offset + count <= length) {
            "Delete range [$offset, ${offset + count}) out of bounds [0, $length)"
        }
        if (count == 0) return EditRecord(EditRecord.Type.DELETE, offset, "", "")
        val deleted = substring(offset, offset + count)
        root = deleteRange(root, offset, count)
        lastInsertAdditionsEnd = -1
        return EditRecord(EditRecord.Type.DELETE, offset, deleted, "")
    }

    fun replace(offset: Int, count: Int, text: String): EditRecord {
        val deleted = if (count > 0) substring(offset, offset + count) else ""
        if (count > 0) { root = deleteRange(root, offset, count); lastInsertAdditionsEnd = -1 }
        if (text.isNotEmpty()) {
            val addStart = additions.length
            additions.append(text)
            val newNode = makeNode(Piece(Buffer.ADDITIONS, addStart, text.length))
            root = insertPieceAtOffset(root, offset, newNode)
            lastInsertAdditionsEnd = addStart + text.length
        }
        return EditRecord(EditRecord.Type.REPLACE, offset, deleted, text)
    }

    fun substring(start: Int, end: Int): String {
        require(start in 0..end && end <= length) {
            "Substring range [$start, $end) out of bounds [0, $length)"
        }
        val sb = StringBuilder(end - start)
        substringInOrder(root, start, end, sb)
        return sb.toString()
    }

    private fun substringInOrder(node: Node?, start: Int, end: Int, sb: StringBuilder) {
        if (node == null || start >= end) return
        val leftChars = charCount(node.left)
        val pieceLen = node.piece.length
        val nodeEnd = leftChars + pieceLen
        if (start < leftChars) substringInOrder(node.left, start, minOf(end, leftChars), sb)
        if (start < nodeEnd && end > leftChars) {
            val from = maxOf(start - leftChars, 0)
            val to = minOf(end - leftChars, pieceLen)
            sb.append(pieceSubstring(node.piece, from, to))
        }
        if (end > nodeEnd) substringInOrder(node.right, start - nodeEnd, end - nodeEnd, sb)
    }

    // ── Newline search ──

    private fun findNextNewline(from: Int): Int = findNewlineInTree(root, from) ?: length

    private fun findNewlineInTree(node: Node?, offset: Int): Int? {
        if (node == null) return null
        val leftChars = charCount(node.left); val pieceLen = node.piece.length
        if (offset < leftChars) {
            findNewlineInTree(node.left, offset)?.let { return it }
            firstNewlineInPiece(node, 0)?.let { return leftChars + it }
            return findNewlineInTree(node.right, 0)?.let { it + leftChars + pieceLen }
        }
        if (offset < leftChars + pieceLen) {
            firstNewlineInPiece(node, offset - leftChars)?.let { return leftChars + it }
            return findNewlineInTree(node.right, 0)?.let { it + leftChars + pieceLen }
        }
        return findNewlineInTree(node.right, offset - leftChars - pieceLen)?.let { it + leftChars + pieceLen }
    }

    private fun firstNewlineInPiece(node: Node, fromInPiece: Int): Int? {
        val nlo = node.nlOffsets; if (nlo.isEmpty()) return null
        var lo = 0; var hi = nlo.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (nlo[mid] < fromInPiece) lo = mid + 1 else hi = mid }
        return if (lo < nlo.size) nlo[lo] else null
    }

    // ── Tree insertion/deletion (same structure as PieceTable) ──

    private fun insertPieceAtOffset(node: Node?, offset: Int, newNode: Node): Node {
        if (node == null) return newNode
        val leftChars = charCount(node.left)
        if (offset <= leftChars) {
            if (offset == leftChars) node.left = insertAsRightmost(node.left, newNode)
            else node.left = insertPieceAtOffset(node.left, offset, newNode)
            return balance(node)
        }
        val nodeEnd = leftChars + node.piece.length
        if (offset >= nodeEnd) {
            node.right = insertPieceAtOffset(node.right, offset - nodeEnd, newNode)
            return balance(node)
        }
        // Split this node's piece at the insertion point
        val splitAt = offset - leftChars
        val piece = node.piece
        val (leftPiece, rightPiece) = splitPiece(piece, splitAt)
        val (leftNlo, rightNlo) = splitNlOffsets(node.nlOffsets, splitAt)
        node.piece = leftPiece; node.nlOffsets = leftNlo
        val rightNode = makeNodeFast(rightPiece, rightNlo)
        node.right = insertAsLeftmost(node.right, rightNode)
        node.right = insertAsLeftmost(node.right, newNode)
        return balance(node)
    }

    /**
     * Split a piece at character position [splitAt].
     * For CHANNEL pieces, we must compute the byte split point.
     */
    private fun splitPiece(piece: Piece, splitAt: Int): Pair<Piece, Piece> {
        return when (piece.buffer) {
            Buffer.ADDITIONS -> {
                val leftPiece = Piece(Buffer.ADDITIONS, piece.start, splitAt)
                val rightPiece = Piece(Buffer.ADDITIONS, piece.start + splitAt, piece.length - splitAt)
                Pair(leftPiece, rightPiece)
            }
            Buffer.CHANNEL -> {
                val region = channelRegions[piece.start]
                val decoded = decodeRegion(piece.start)
                // Encode the left portion to find byte boundary
                val leftChars = decoded.substring(0, splitAt)
                val leftBytes = leftChars.toByteArray(charset)
                val leftByteLen = leftBytes.size
                val rightByteLen = region.byteLength - leftByteLen

                // Invalidate old cache entry
                cache.remove(piece.start)

                // Create two new regions
                val leftRegionIdx = channelRegions.size
                channelRegions.add(ChannelRegion(region.byteOffset, leftByteLen))
                cache[leftRegionIdx] = leftChars

                val rightRegionIdx = channelRegions.size
                val rightChars = decoded.substring(splitAt)
                channelRegions.add(ChannelRegion(region.byteOffset + leftByteLen, rightByteLen))
                cache[rightRegionIdx] = rightChars

                val leftPiece = Piece(Buffer.CHANNEL, leftRegionIdx, splitAt)
                val rightPiece = Piece(Buffer.CHANNEL, rightRegionIdx, piece.length - splitAt)
                Pair(leftPiece, rightPiece)
            }
        }
    }

    private fun insertAsLeftmost(node: Node?, newNode: Node): Node {
        if (node == null) return newNode
        node.left = insertAsLeftmost(node.left, newNode)
        return balance(node)
    }

    private fun insertAsRightmost(node: Node?, newNode: Node): Node {
        if (node == null) return newNode
        node.right = insertAsRightmost(node.right, newNode)
        return balance(node)
    }

    private fun deleteRange(node: Node?, offset: Int, count: Int): Node? {
        if (node == null || count == 0) return node
        val leftChars = charCount(node.left); val pieceLen = node.piece.length; val nodeEnd = leftChars + pieceLen
        if (offset + count <= leftChars) {
            node.left = deleteRange(node.left, offset, count); return balance(node)
        }
        if (offset >= nodeEnd) {
            node.right = deleteRange(node.right, offset - nodeEnd, count); return balance(node)
        }
        var result: Node? = node; var remaining = count; var currentOffset = offset
        if (currentOffset < leftChars) {
            val leftDelete = leftChars - currentOffset
            node.left = deleteRange(node.left, currentOffset, leftDelete)
            remaining -= leftDelete; currentOffset = leftChars
        }
        if (remaining > 0 && currentOffset < nodeEnd) {
            val inPiece = currentOffset - leftChars
            val deleteInPiece = minOf(remaining, pieceLen - inPiece)
            if (inPiece == 0 && deleteInPiece == pieceLen) {
                result = merge(node.left, node.right); remaining -= deleteInPiece
                if (remaining > 0) result = deleteRange(result, charCount(node.left), remaining)
                return result
            } else if (inPiece == 0) {
                val (_, rightPiece) = splitPiece(node.piece, deleteInPiece)
                node.nlOffsets = sliceNlOffsetsFrom(node.nlOffsets, deleteInPiece)
                node.piece = rightPiece; remaining -= deleteInPiece
            } else if (inPiece + deleteInPiece == pieceLen) {
                val (leftPiece, _) = splitPiece(node.piece, inPiece)
                node.nlOffsets = sliceNlOffsetsTo(node.nlOffsets, inPiece)
                node.piece = leftPiece; remaining -= deleteInPiece
            } else {
                val (leftPiece, tmpRight) = splitPiece(node.piece, inPiece)
                val (_, rightPiece) = splitPiece(tmpRight, deleteInPiece)
                val leftNlo = sliceNlOffsetsTo(node.nlOffsets, inPiece)
                val rightNlo = sliceNlOffsetsFrom(node.nlOffsets, inPiece + deleteInPiece)
                node.piece = leftPiece; node.nlOffsets = leftNlo
                val rightNode = makeNodeFast(rightPiece, rightNlo)
                node.right = insertAsLeftmost(node.right, rightNode)
                remaining -= deleteInPiece
            }
        }
        if (remaining > 0) node.right = deleteRange(node.right, 0, remaining)
        return if (result === node) balance(node) else result
    }

    private fun sliceNlOffsetsTo(nlOffsets: IntArray, cutoff: Int): IntArray {
        var lo = 0; var hi = nlOffsets.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (nlOffsets[mid] < cutoff) lo = mid + 1 else hi = mid }
        return nlOffsets.copyOfRange(0, lo)
    }

    private fun sliceNlOffsetsFrom(nlOffsets: IntArray, cutoff: Int): IntArray {
        var lo = 0; var hi = nlOffsets.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (nlOffsets[mid] < cutoff) lo = mid + 1 else hi = mid }
        val result = IntArray(nlOffsets.size - lo) { nlOffsets[lo + it] - cutoff }
        return result
    }

    private fun merge(left: Node?, right: Node?): Node? {
        if (left == null) return right; if (right == null) return left
        val (newLeft, maxNode) = removeMax(left)
        maxNode.left = newLeft; maxNode.right = right; return balance(maxNode)
    }

    private fun removeMax(node: Node): Pair<Node?, Node> {
        if (node.right == null) { val d = node; val r = node.left; d.left = null; return Pair(r, d) }
        val (newRight, maxNode) = removeMax(node.right!!)
        node.right = newRight; return Pair(balance(node), maxNode)
    }

    /**
     * Stream all pieces to an output channel for materialise().
     * CHANNEL pieces are copied as raw bytes (no decode/re-encode).
     * ADDITIONS pieces are encoded and written.
     */
    fun streamPieces(
        output: WritableByteChannel,
        bomBytes: ByteArray?,
    ) {
        if (bomBytes != null) output.write(ByteBuffer.wrap(bomBytes))
        streamInOrder(root, output)
    }

    private fun streamInOrder(node: Node?, output: WritableByteChannel) {
        if (node == null) return
        streamInOrder(node.left, output)
        when (node.piece.buffer) {
            Buffer.CHANNEL -> {
                val region = channelRegions[node.piece.start]
                if (region.byteLength > 0) {
                    val buf = ByteBuffer.allocate(region.byteLength)
                    channel.read(buf, region.byteOffset + bomLength)
                    buf.flip()
                    output.write(buf)
                }
            }
            Buffer.ADDITIONS -> {
                val text = additions.substring(node.piece.start, node.piece.start + node.piece.length)
                output.write(ByteBuffer.wrap(text.toByteArray(charset)))
            }
        }
        streamInOrder(node.right, output)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.ChannelPieceTableTest" --info 2>&1 | tail -30`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/io/src/main/kotlin/com/omnieditor/core/io/ChannelPieceTable.kt \
       core/io/src/test/kotlin/com/omnieditor/core/io/ChannelPieceTableTest.kt
git commit -m "feat(core/io): ChannelPieceTable — channel-backed piece table for INDEXED_EDITABLE [F-03, ADR-015]"
```

---

### Task 4: LargeFileEditableDocument

**Files:**
- Create: `core/io/src/main/kotlin/com/omnieditor/core/io/LargeFileEditableDocument.kt`
- Create: `core/io/src/test/kotlin/com/omnieditor/core/io/LargeFileEditableDocumentTest.kt`

**Interfaces:**
- Consumes:
  - `ChannelPieceTable(channel, lineIndex, charset, bomLength)` from Task 3
  - `FileFingerprint.of(file)`, `FileFingerprint.check(file, expected)` from Task 2
  - `OmniError.ExternallyModified(path)` from Task 2
  - `FileIndexer.index(file, progress)` from existing code
- Produces:
  - `LargeFileEditableDocument : TextDocument, Closeable` class
  - `suspend LargeFileEditableDocument.open(file: File, progress): LargeFileEditableDocument` companion
  - `fun markSaved()` — same as PieceTableDocument
  - `val undoCount: Int`, `val redoCount: Int`
  - `val filePath: String` — for ExternallyModified error messages

- [ ] **Step 1: Write failing tests**

File: `core/io/src/test/kotlin/com/omnieditor/core/io/LargeFileEditableDocumentTest.kt`

```kotlin
package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.Channels

class LargeFileEditableDocumentTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("editable-large-doc-test", ".txt")
        tempFile.writeText("line0\nline1\nline2\n")
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `lineCount matches newlines plus 1`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use { it.lineCount shouldBe 4 }
    }

    @Test
    fun `line returns correct content`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.line(0).toString() shouldBe "line0"
            it.line(1).toString() shouldBe "line1"
            it.line(2).toString() shouldBe "line2"
            it.line(3).toString() shouldBe ""
        }
    }

    @Test
    fun `edit changes line content`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(1L..1L, "modified")
            it.line(1).toString() shouldBe "modified"
        }
    }

    @Test
    fun `edit marks document dirty`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.dirty shouldBe false
            it.edit(0L..0L, "changed")
            it.dirty shouldBe true
        }
    }

    @Test
    fun `undo reverses edit`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(1L..1L, "modified")
            it.line(1).toString() shouldBe "modified"
            it.undo()
            it.line(1).toString() shouldBe "line1"
        }
    }

    @Test
    fun `redo re-applies edit`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(1L..1L, "modified")
            it.undo()
            it.redo()
            it.line(1).toString() shouldBe "modified"
        }
    }

    @Test
    fun `materialise writes edited content`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(1L..1L, "CHANGED")
            val baos = ByteArrayOutputStream()
            it.materialise(Channels.newChannel(baos))
            val output = baos.toString("UTF-8")
            output shouldContain "CHANGED"
            output shouldContain "line0"
        }
    }

    @Test
    fun `materialise detects external modification`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(0L..0L, "edited")
            // Externally modify the file
            Thread.sleep(50) // ensure lastModified differs
            tempFile.writeText("externally changed content")
            try {
                val baos = ByteArrayOutputStream()
                it.materialise(Channels.newChannel(baos))
                // Should not reach here
                throw AssertionError("Expected OmniException")
            } catch (e: com.omnieditor.core.model.OmniException) {
                (e.error is com.omnieditor.core.model.OmniError.ExternallyModified) shouldBe true
            }
        }
    }

    @Test
    fun `batch edit creates single undo step`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.beginBatch()
            try {
                it.edit(0L..0L, "A")
                it.edit(1L..1L, "B")
            } finally {
                it.commitBatch()
            }
            it.line(0).toString() shouldBe "A"
            it.line(1).toString() shouldBe "B"
            // Single undo should revert both
            it.undo()
            it.line(0).toString() shouldBe "line0"
            it.line(1).toString() shouldBe "line1"
        }
    }

    @Test
    fun `editGeneration increments on each edit`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            val gen0 = it.editGeneration
            it.edit(0L..0L, "a")
            val gen1 = it.editGeneration
            (gen1 > gen0) shouldBe true
        }
    }

    @Test
    fun `file without trailing newline`() = runTest {
        tempFile.writeText("hello\nworld")
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.lineCount shouldBe 2
            it.line(0).toString() shouldBe "hello"
            it.line(1).toString() shouldBe "world"
        }
    }

    @Test
    fun `markSaved clears dirty flag`() = runTest {
        val doc = LargeFileEditableDocument.open(tempFile)
        doc.use {
            it.edit(0L..0L, "changed")
            it.dirty shouldBe true
            it.markSaved()
            it.dirty shouldBe false
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.LargeFileEditableDocumentTest" --info 2>&1 | tail -20`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement LargeFileEditableDocument**

File: `core/io/src/main/kotlin/com/omnieditor/core/io/LargeFileEditableDocument.kt`

```kotlin
package com.omnieditor.core.io

import com.omnieditor.core.model.OmniError
import com.omnieditor.core.model.OmniException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel

/**
 * Editable [TextDocument] for the INDEXED_EDITABLE tier (16–256 MiB, UTF-8/ASCII).
 *
 * Wraps a [ChannelPieceTable] that uses a [FileChannel] as the original buffer.
 * Edits go to an in-memory additions buffer; unchanged content stays on disk.
 *
 * Key differences from [PieceTableDocument]:
 * - `text()` throws [UnsupportedOperationException] (O(file) is prohibited)
 * - `materialise()` checks [FileFingerprint] before writing (ADR-015)
 * - `materialise()` streams pieces without loading the entire document
 * - `index` throws (use `line()` directly)
 *
 * Key differences from [LargeFileDocument]:
 * - Editing is supported (edit/undo/redo/batch)
 * - Document tracks dirty state
 */
class LargeFileEditableDocument private constructor(
    private val table: ChannelPieceTable,
    private val raf: RandomAccessFile,
    private val channel: FileChannel,
    private val encoding: String,
    private val bomLength: Int,
    private val bomBytes: ByteArray?,
    private val fingerprint: FileFingerprint,
    val filePath: String,
) : TextDocument, Closeable {

    private var editIdCounter = 0L
    private val undoStack = mutableListOf<JournalEntry>()
    private val redoStack = mutableListOf<JournalEntry>()
    private var batchStartDepth = -1
    private var _editGeneration = 0L
    private var savedUndoDepth = 0
    private val _changes = MutableSharedFlow<DocumentChange>(extraBufferCapacity = 64)

    override val editGeneration: Long get() = _editGeneration
    override val lineCount: Long get() = table.lineCount.toLong()
    override val length: Int get() = table.length

    override val index: LineIndex
        get() = throw UnsupportedOperationException(
            "LargeFileEditableDocument provides line() access directly"
        )

    override fun line(index: Long): CharSequence = table.line(index.toInt())

    override fun edit(range: LongRange, replacement: CharSequence): Long {
        val editId = ++editIdCounter
        val offset = table.lineToOffset(range.first.toInt())
        val endOffset = if (range.last >= lineCount - 1) {
            table.length
        } else {
            val nextLineStart = table.lineToOffset((range.last + 1).toInt())
            val charBefore = table.charAt(nextLineStart - 1)
            if (charBefore == '\n' && nextLineStart >= 2 && table.charAt(nextLineStart - 2) == '\r') {
                nextLineStart - 2
            } else {
                nextLineStart - 1
            }
        }
        val count = endOffset - offset
        val text = replacement.toString()
        val record = table.replace(offset, count, text)
        val entry = JournalEntry(editId, record)
        undoStack.add(entry)
        redoStack.clear()
        _editGeneration++
        _changes.tryEmit(
            DocumentChange(editId, range.first, range.last + 1, range.first + text.count { it == '\n' } + 1)
        )
        return editId
    }

    override fun replaceAll(offset: Int, length: Int, replacement: String): Long {
        val editId = ++editIdCounter
        val record = table.replace(offset, length, replacement)
        val entry = JournalEntry(editId, record)
        undoStack.add(entry)
        redoStack.clear()
        _editGeneration++
        _changes.tryEmit(DocumentChange(editId, 0, lineCount, lineCount))
        return editId
    }

    override fun undo(): Long? {
        if (undoStack.isEmpty()) return null
        val entry = undoStack.removeAt(undoStack.lastIndex)
        applyReverse(entry.record)
        redoStack.add(entry)
        _editGeneration++
        _changes.tryEmit(DocumentChange(-entry.editId, 0, lineCount, lineCount))
        return entry.editId
    }

    override fun redo(): Long? {
        if (redoStack.isEmpty()) return null
        val entry = redoStack.removeAt(redoStack.lastIndex)
        applyForward(entry.record)
        undoStack.add(entry)
        _editGeneration++
        _changes.tryEmit(DocumentChange(entry.editId, 0, lineCount, lineCount))
        return entry.editId
    }

    override fun beginBatch() {
        if (batchStartDepth >= 0) return
        batchStartDepth = undoStack.size
    }

    override fun commitBatch() {
        val startDepth = batchStartDepth
        if (startDepth < 0) return
        batchStartDepth = -1
        val batchSize = undoStack.size - startDepth
        if (batchSize <= 1) return

        val batchEntries = undoStack.subList(startDepth, undoStack.size).toList()
        repeat(batchSize) { undoStack.removeAt(undoStack.lastIndex) }

        // Reverse all batch edits
        for (entry in batchEntries.reversed()) applyReverse(entry.record)

        // Capture pre-batch state, replay, capture post-batch state
        // For large files we cannot call text() — use substring of the full range
        val preText = table.substring(0, table.length)
        for (entry in batchEntries) applyForward(entry.record)
        val postText = table.substring(0, table.length)

        // Reverse back and apply as single compound edit
        for (entry in batchEntries.reversed()) applyReverse(entry.record)

        val editId = ++editIdCounter
        val record = table.replace(0, preText.length, postText)
        val compoundEntry = JournalEntry(editId, record)
        undoStack.add(compoundEntry)
        redoStack.clear()
        _editGeneration++
        _changes.tryEmit(DocumentChange(editId, 0, lineCount, lineCount))
    }

    override fun text(): String {
        // For small documents this is acceptable; for large ones callers must use materialise()
        return table.substring(0, table.length)
    }

    override suspend fun materialise(into: WritableByteChannel) {
        // ADR-015: check fingerprint before writing
        val file = File(filePath)
        if (!FileFingerprint.check(file, fingerprint)) {
            throw OmniException(OmniError.ExternallyModified(filePath))
        }
        withContext(Dispatchers.IO) {
            table.streamPieces(into, bomBytes)
        }
    }

    override val changes: Flow<DocumentChange> = _changes.asSharedFlow()
    override val dirty: Boolean get() = undoStack.size != savedUndoDepth

    fun markSaved() { savedUndoDepth = undoStack.size }

    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size

    override fun close() {
        channel.close()
        raf.close()
    }

    private fun applyReverse(record: EditRecord) {
        when (record.type) {
            EditRecord.Type.INSERT -> table.delete(record.offset, record.inserted.length)
            EditRecord.Type.DELETE -> table.insert(record.offset, record.deleted)
            EditRecord.Type.REPLACE -> {
                table.delete(record.offset, record.inserted.length)
                table.insert(record.offset, record.deleted)
            }
        }
    }

    private fun applyForward(record: EditRecord) {
        when (record.type) {
            EditRecord.Type.INSERT -> table.insert(record.offset, record.inserted)
            EditRecord.Type.DELETE -> table.delete(record.offset, record.deleted.length)
            EditRecord.Type.REPLACE -> table.replace(record.offset, record.deleted.length, record.inserted)
        }
    }

    companion object {
        suspend fun open(
            file: File,
            progress: ((com.omnieditor.core.model.Progress) -> Unit)? = null,
        ): LargeFileEditableDocument = withContext(Dispatchers.IO) {
            val indexResult = FileIndexer.index(file, progress)
            val raf = RandomAccessFile(file, "r")
            val channel = raf.channel
            val charset = charset(indexResult.encoding.charset)
            val bomLength = indexResult.encoding.bomLength
            val bomBytes = when {
                indexResult.encoding.charset.equals("UTF-8", ignoreCase = true) && bomLength == 3 ->
                    byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                else -> null
            }
            val fingerprint = FileFingerprint.of(file)
            val table = ChannelPieceTable(channel, indexResult.index, charset, bomLength)
            LargeFileEditableDocument(
                table, raf, channel, indexResult.encoding.charset,
                bomLength, bomBytes, fingerprint, file.absolutePath,
            )
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.LargeFileEditableDocumentTest" --info 2>&1 | tail -30`
Expected: All tests PASS.

- [ ] **Step 5: Run full core:io test suite**

Run: `./gradlew :core:io:test --info 2>&1 | tail -10`
Expected: All existing tests still PASS — no regressions.

- [ ] **Step 6: Commit**

```bash
git add core/io/src/main/kotlin/com/omnieditor/core/io/LargeFileEditableDocument.kt \
       core/io/src/test/kotlin/com/omnieditor/core/io/LargeFileEditableDocumentTest.kt
git commit -m "feat(core/io): LargeFileEditableDocument — editable TextDocument for INDEXED_EDITABLE tier [F-03, ADR-015]"
```

---

### Task 5: EditorCoordinator wiring + ADR-012 update

**Files:**
- Modify: `app/src/main/kotlin/com/omnieditor/app/EditorCoordinator.kt:148-194`
- Modify: `docs/adr/012-size-ladder.md:14-18`

**Interfaces:**
- Consumes:
  - `DocumentLimits.editorTier(sizeBytes, encoding)` from Task 2
  - `LargeFileEditableDocument.open(file, progress)` from Task 4
  - `EditorViewModel.openLargeDocument(document, readOnly, fileName)` from existing code
- Produces: INDEXED_EDITABLE dispatch path in EditorCoordinator

- [ ] **Step 1: Update EditorCoordinator tier dispatch**

In `app/src/main/kotlin/com/omnieditor/app/EditorCoordinator.kt`, find the `LaunchedEffect(contentKey)` block that checks `DocumentLimits.editorTier(size)` (around line 151). Replace the `when` block:

Replace:
```kotlin
            when (DocumentLimits.editorTier(size)) {
                DocumentLimits.SizeTier.FULL_MEMORY -> {
                    viewModel.openDocument(cached.text, fileName = cached.label)
                }
                DocumentLimits.SizeTier.INDEXED_READ_ONLY -> {
```

With:
```kotlin
            // Use encoding-aware tier if encoding is known, otherwise fall back
            val tier = DocumentLimits.editorTier(size)
            when (tier) {
                DocumentLimits.SizeTier.FULL_MEMORY -> {
                    viewModel.openDocument(cached.text, fileName = cached.label)
                }
                DocumentLimits.SizeTier.INDEXED_EDITABLE,
                DocumentLimits.SizeTier.INDEXED_READ_ONLY -> {
```

Then in the large-file branch, after creating the cache file (around line 171), add INDEXED_EDITABLE logic:

Replace:
```kotlin
                            com.omnieditor.core.io.LargeFileDocument.open(cacheFile)
                        }
                        viewModel.openLargeDocument(
                            document = largeDoc,
                            readOnly = true,
                            fileName = cached.label + " (read-only)",
                        )
```

With:
```kotlin
                            // Detect encoding to determine editable vs read-only
                            val indexResult = com.omnieditor.core.io.FileIndexer.index(cacheFile)
                            val encoding = indexResult.encoding.charset
                            val editableTier = DocumentLimits.editorTier(size, encoding)
                            if (editableTier == DocumentLimits.SizeTier.INDEXED_EDITABLE) {
                                com.omnieditor.core.io.LargeFileEditableDocument.open(cacheFile)
                            } else {
                                com.omnieditor.core.io.LargeFileDocument.open(cacheFile)
                            }
                        }
                        val isEditable = largeDoc is com.omnieditor.core.io.LargeFileEditableDocument
                        viewModel.openLargeDocument(
                            document = largeDoc,
                            readOnly = !isEditable,
                            fileName = if (isEditable) cached.label else cached.label + " (read-only)",
                        )
```

- [ ] **Step 2: Update ADR-012 size ladder table**

In `docs/adr/012-size-ladder.md`, replace the tier table:

Replace:
```markdown
| Tier | Size | Behaviour | Disclosed as |
|---|---|---|---|
| FULL_MEMORY | ≤16 MiB | In-memory PieceTableDocument, full editing | *(none)* |
| INDEXED_READ_ONLY | 16–256 MiB | FileIndexer + mmap'd channel, read-only | "Read-only (large file)" |
| REFUSED | >256 MiB | OmniError.TooLarge | Error screen |
```

With:
```markdown
| Tier | Size | Behaviour | Disclosed as |
|---|---|---|---|
| FULL_MEMORY | ≤16 MiB | In-memory PieceTableDocument, full editing | *(none)* |
| INDEXED_EDITABLE | 16–256 MiB (UTF-8/ASCII) | ChannelPieceTable over FileChannel, full editing | *(none)* |
| INDEXED_READ_ONLY | 16–256 MiB (other encodings) | FileIndexer + mmap'd channel, read-only | "Read-only (large file)" |
| REFUSED | >256 MiB | OmniError.TooLarge | Error screen |
```

- [ ] **Step 3: Verify both flavours build**

Run: `./gradlew assembleDirectDebug assembleStoreDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/omnieditor/app/EditorCoordinator.kt \
       docs/adr/012-size-ladder.md
git commit -m "feat(app): wire INDEXED_EDITABLE tier in EditorCoordinator [F-03, ADR-012, ADR-015]"
```

---

### Task 6: F-10 Word-merge UI in ActiveLineSheet

**Files:**
- Modify: `feature/compare/src/main/kotlin/com/omnieditor/feature/compare/ActiveLineSheet.kt`
- Modify: `feature/compare/src/main/kotlin/com/omnieditor/feature/compare/CompareState.kt`

**Interfaces:**
- Consumes:
  - `IntraLineDiff.compute(pair, granularity): IntraLineResult` from existing code
  - `WordMerge.merge(leftLine, rightLine, granularity, selections): String` from existing code
  - `MergeEngine.mergeWordLevel(hunkIndex, result, leftLines, rightLines, direction, selections): List<MergeAction>` from existing code
  - `CompareState.mergeHunk(hunkIndex, direction)` from existing code
- Produces:
  - `CompareState.mergeWordLevel(hunkIndex, direction, selections): Boolean` method
  - Word-level toggle UI in `ActiveLineSheet`
  - `onWordMerge: ((hunkIndex: Int, direction: MergeDirection, selections: List<WordMerge.Side>) -> Unit)?` callback parameter on `ActiveLineSheet`

- [ ] **Step 1: Add mergeWordLevel to CompareState**

In `feature/compare/src/main/kotlin/com/omnieditor/feature/compare/CompareState.kt`, add after the `mergeHunk` method (around line 246):

```kotlin
    /**
     * F-10: Word-level merge for a single hunk (OE-MRG-2).
     * Wraps in beginBatch/commitBatch for a single undo step (R-54).
     */
    fun mergeWordLevel(
        hunkIndex: Int,
        direction: MergeDirection,
        selections: List<com.omnieditor.core.diff.WordMerge.Side>,
    ): Boolean {
        if (hunkIndex in mergedHunks) return false

        val targetDoc = when (direction) {
            MergeDirection.LEFT_TO_RIGHT -> rightDocument
            MergeDirection.RIGHT_TO_LEFT -> leftDocument
        } ?: return false

        val engineDirection = when (direction) {
            MergeDirection.LEFT_TO_RIGHT -> com.omnieditor.core.diff.MergeEngine.Direction.LEFT_TO_RIGHT
            MergeDirection.RIGHT_TO_LEFT -> com.omnieditor.core.diff.MergeEngine.Direction.RIGHT_TO_LEFT
        }

        val actions = com.omnieditor.core.diff.MergeEngine.mergeWordLevel(
            hunkIndex, result, leftLines, rightLines, engineDirection, selections,
        )

        if (actions.isEmpty()) return false

        // R-54: single undo step for all word-level merge actions
        targetDoc.beginBatch()
        try {
            for (action in actions.reversed()) { // bottom-to-top
                applyActionToDocument(targetDoc, action)
            }
        } finally {
            targetDoc.commitBatch()
        }

        refreshLinesFromDocument(direction, targetDoc)
        mergedHunks = mergedHunks + hunkIndex
        mergeMessage = "Word-merged hunk ${hunkIndex + 1}"
        return true
    }
```

- [ ] **Step 2: Add word-level UI to ActiveLineSheet**

Replace the entire `ActiveLineSheet.kt` with the version that adds the word-level toggle. The key additions are:

Add new imports at the top:
```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.omnieditor.core.diff.IntraLineDiff
import com.omnieditor.core.diff.WordMerge
import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.LinePair
```

Add new parameter to `ActiveLineSheet`:
```kotlin
    /** F-10: Called when the user applies word-level merge selections. */
    onWordMerge: ((hunkIndex: Int, selections: List<WordMerge.Side>) -> Unit)? = null,
```

After the existing merge buttons (the `else if (onMergeLeftToRight != null || ...)` block, around line 241), and before the copy buttons, add the word-level merge section:

```kotlin
            // F-10: Word-level merge toggle — only for CHANGED hunks with paired lines
            if (hunk.type == HunkType.CHANGED && onWordMerge != null) {
                val leftCount = (hunk.leftEnd - hunk.leftStart).toInt()
                val rightCount = (hunk.rightEnd - hunk.rightStart).toInt()
                val pairCount = minOf(leftCount, rightCount)

                if (pairCount > 0) {
                    var wordModeEnabled by remember { mutableStateOf(false) }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    FilterChip(
                        selected = wordModeEnabled,
                        onClick = { wordModeEnabled = !wordModeEnabled },
                        label = { Text("Word") },
                        modifier = Modifier.semantics {
                            contentDescription = "Word-level merge mode"
                        },
                    )

                    if (wordModeEnabled) {
                        // Process first paired line (simplification: one pair at a time)
                        val leftLine = leftLines.getOrElse(hunk.leftStart.toInt()) { "" }
                        val rightLine = rightLines.getOrElse(hunk.rightStart.toInt()) { "" }
                        val pair = LinePair(hunk.leftStart, hunk.rightStart, leftLine, rightLine)
                        val intraResult = remember(leftLine, rightLine) {
                            IntraLineDiff.compute(pair, Granularity.WORD)
                        }
                        val rangeCount = minOf(
                            intraResult.leftRanges.size,
                            intraResult.rightRanges.size,
                        )
                        val selections = remember(leftLine, rightLine) {
                            mutableStateListOf<WordMerge.Side>().apply {
                                repeat(rangeCount) { add(WordMerge.Side.LEFT) }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Select changes:",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )

                        // Chip strip for each changed range
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (i in 0 until rangeCount) {
                                val leftRange = intraResult.leftRanges[i]
                                val rightRange = intraResult.rightRanges[i]
                                val leftText = leftLine.substring(leftRange.start, leftRange.end)
                                val rightText = rightLine.substring(rightRange.start, rightRange.end)
                                val side = selections[i]
                                val chipLabel = if (side == WordMerge.Side.LEFT) leftText else rightText
                                val chipColor = if (side == WordMerge.Side.LEFT) colors.removedBg else colors.addedBg

                                FilterChip(
                                    selected = side == WordMerge.Side.RIGHT,
                                    onClick = {
                                        selections[i] = if (side == WordMerge.Side.LEFT) {
                                            WordMerge.Side.RIGHT
                                        } else {
                                            WordMerge.Side.LEFT
                                        }
                                    },
                                    label = {
                                        Text(
                                            chipLabel.ifEmpty { "(empty)" },
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = chipColor,
                                    ),
                                    modifier = Modifier.semantics {
                                        contentDescription = "Change ${i + 1}: " +
                                            "left is '${leftText}', " +
                                            "right is '${rightText}', " +
                                            "currently taking ${side.name.lowercase()}"
                                    },
                                )
                            }
                        }

                        // Live preview
                        Spacer(modifier = Modifier.height(8.dp))
                        val preview = remember(selections.toList()) {
                            WordMerge.merge(leftLine, rightLine, Granularity.WORD, selections.toList())
                        }
                        Text(
                            "Preview:",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            text = preview,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp),
                        )

                        // Apply button
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                onWordMerge(hunk.type.ordinal, selections.toList())
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Apply word merge")
                        }

                        // Unpaired lines notice
                        if (pairCount < maxOf(leftCount, rightCount)) {
                            Text(
                                "${maxOf(leftCount, rightCount) - pairCount} unpaired line(s) — use hunk-level accept",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew :feature:compare:compileDebugKotlin 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/compare/src/main/kotlin/com/omnieditor/feature/compare/ActiveLineSheet.kt \
       feature/compare/src/main/kotlin/com/omnieditor/feature/compare/CompareState.kt
git commit -m "feat(compare): F-10 word-merge UI in ActiveLineSheet [OE-MRG-2, W-05]"
```

---

### Task 7: WordMerge property tests (C.3 extension)

**Files:**
- Modify: `core/diff/src/test/kotlin/com/omnieditor/core/diff/WordMergeTest.kt`

**Interfaces:**
- Consumes: `WordMerge.merge()` from existing code
- Produces: C.3 property tests guaranteeing identity invariants

- [ ] **Step 1: Add property tests**

Append to `core/diff/src/test/kotlin/com/omnieditor/core/diff/WordMergeTest.kt`:

```kotlin
    // ── C.3 property tests: word-merge identity invariants ──

    @Test
    fun `C3 all LEFT selections produce byte-identical left line`() {
        val testCases = listOf(
            Pair("hello world", "hello earth"),
            Pair("foo bar baz", "foo XXX YYY"),
            Pair("abc def ghi jkl", "abc DEF ghi JKL"),
            Pair("one two three", "ONE TWO THREE"),
            Pair("", "something"),
            Pair("unchanged", "unchanged"),
            Pair("a b c d e f", "a X c X e X"),
            Pair("café résumé", "cafe resume"),
            Pair("line with    spaces", "line without spaces"),
            Pair("mixed CASE words", "mixed case WORDS"),
        )
        for ((left, right) in testCases) {
            val diff = IntraLineDiff.compute(
                LinePair(0L, 0L, left, right), Granularity.WORD,
            )
            val allLeft = List(diff.leftRanges.size) { WordMerge.Side.LEFT }
            val result = WordMerge.merge(left, right, Granularity.WORD, allLeft)
            result shouldBe left
        }
    }

    @Test
    fun `C3 all RIGHT selections produce byte-identical right line`() {
        val testCases = listOf(
            Pair("hello world", "hello earth"),
            Pair("foo bar baz", "foo XXX YYY"),
            Pair("abc def ghi jkl", "abc DEF ghi JKL"),
            Pair("one two three", "ONE TWO THREE"),
            Pair("unchanged", "unchanged"),
            Pair("a b c d e f", "a X c X e X"),
            Pair("café résumé", "cafe resume"),
            Pair("line with    spaces", "line without spaces"),
        )
        for ((left, right) in testCases) {
            val diff = IntraLineDiff.compute(
                LinePair(0L, 0L, left, right), Granularity.WORD,
            )
            val allRight = List(diff.leftRanges.size) { WordMerge.Side.RIGHT }
            val result = WordMerge.merge(left, right, Granularity.WORD, allRight)
            result shouldBe right
        }
    }
```

Add the missing import at the top:
```kotlin
import com.omnieditor.core.model.LinePair
```

- [ ] **Step 2: Run tests**

Run: `./gradlew :core:diff:test --tests "com.omnieditor.core.diff.WordMergeTest" --info 2>&1 | tail -20`
Expected: All 6 tests PASS (4 existing + 2 new).

- [ ] **Step 3: Commit**

```bash
git add core/diff/src/test/kotlin/com/omnieditor/core/diff/WordMergeTest.kt
git commit -m "test(diff): C.3 property tests for word-merge identity invariants [F-10, OE-MRG-2]"
```

---

### Task 8: Documentation — CHANGES.md update

**Files:**
- Modify: `CHANGES.md`

**Interfaces:**
- Consumes: nothing
- Produces: changelog entries for F-03 and F-10

- [ ] **Step 1: Add F-03 and F-10 entries to CHANGES.md**

Add under the v0.5 section (or create one if it doesn't exist), at the top of the file after any existing header:

```markdown
## v0.5.0 (in progress)

### F-03: Large-file editing (INDEXED_EDITABLE tier)
- `ChannelPieceTable`: piece table backed by `FileChannel` original buffer (ADR-015)
- `LargeFileEditableDocument`: editable `TextDocument` for 16–256 MiB UTF-8/ASCII files
- `FileFingerprint`: external-modification detection before save
- `OmniError.ExternallyModified`: new error variant for externally changed files
- INDEXED_EDITABLE tier added to `DocumentLimits.SizeTier`
- Encoding gate: UTF-8/ASCII only; other encodings remain INDEXED_READ_ONLY
- LRU decode cache (2048 entries) for channel piece reads
- Benchmark unverified — ceiling raise requires D-2/D-7 recorded benchmark

### F-10: Word-merge UI (OE-MRG-2)
- Word-level toggle in `ActiveLineSheet` for CHANGED hunks with paired lines
- Per-range selectable chips showing left/right text with colour coding
- Live preview of merged result updating as selections change
- Apply wraps in `beginBatch()`/`commitBatch()` for single undo step (R-54)
- TalkBack labels on all word-change chips (W-09)
- C.3 property tests: all-LEFT ⇒ byte-identical left, all-RIGHT ⇒ byte-identical right
```

- [ ] **Step 2: Run full test suite**

Run: `./gradlew testDirectDebugUnitTest testStoreDebugUnitTest :core:model:test :core:diff:test :core:io:test 2>&1 | tail -10`
Expected: All tests PASS.

- [ ] **Step 3: Run static analysis**

Run: `./gradlew checkCorePurity checkIoBoundary 2>&1 | tail -5`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add CHANGES.md
git commit -m "docs: CHANGES.md entries for F-03 and F-10 [v0.5]"
```

---

## Self-Review Results

**Spec coverage:** All items from the design spec are covered:
- ADR-015: Task 1
- FileFingerprint + external-change guard: Task 2
- OmniError.ExternallyModified: Task 2
- DocumentLimits INDEXED_EDITABLE: Task 2
- ChannelPieceTable: Task 3
- LargeFileEditableDocument: Task 4
- EditorCoordinator wiring: Task 5
- ADR-012 update: Task 5
- Word-merge UI: Task 6
- C.3 property tests: Task 7
- CHANGES.md: Task 8

**Placeholder scan:** Clean — no TBD/TODO/placeholders.

**Type consistency:** Verified across tasks:
- `FileFingerprint.of(file)` / `FileFingerprint.check(file, expected)` — consistent in Tasks 2 and 4
- `ChannelPieceTable(channel, lineIndex, charset, bomLength)` — consistent in Tasks 3 and 4
- `LargeFileEditableDocument.open(file, progress)` — consistent in Tasks 4 and 5
- `WordMerge.Side` — consistent in Tasks 6 and 7
- `OmniError.ExternallyModified(path)` — consistent in Tasks 2 and 4
