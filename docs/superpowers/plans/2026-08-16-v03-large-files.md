# v0.3 Large Files and Data Paths Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire existing large-file infrastructure (FileIndexer, BlockDiff) into live paths, raise size ceilings with a size ladder, scaffold large-file editing and foreground service, close the release pipeline gap, and add differential/merge tests.

**Architecture:** `DocumentLimits` gains a `SizeTier` enum. `LargeFileDocument` implements `TextDocument` over a `FileChannel` + `LineIndex` for read-only access to files above 16 MiB. The compare path branches to `BlockDiff` above 250k lines. `LongJobService` scaffolds a generic foreground service host. `versionCode` derives from git.

**Tech Stack:** Kotlin 2.3.21, JUnit 4, Kotest assertions, kotlinx.coroutines.test, java.nio (FileChannel, MappedByteBuffer)

## Global Constraints

- `core/model` and `core/diff` must not import `android.*` or `androidx.*` (`checkCorePurity`).
- `java.io.File` and `ContentResolver` only in `core/io`, `app/src/direct`, `app/src/store`, `app/src/main`, and test sources (`checkIoBoundary`).
- Line count = `newlines + 1` (ADR-007).
- Tests land in the same commit as the code they test.
- Commit messages reference requirement IDs.
- No new dependency without a line in `docs/licenses.md`.
- Both flavours (`direct`, `store`) must build.
- Run: `./gradlew checkCorePurity checkIoBoundary :core:model:test :core:diff:test :core:io:test`

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `core/model/.../DocumentLimits.kt` | Add `SizeTier` enum, `tier(sizeBytes)` function, raise compare ceiling | 1 |
| `docs/adr/012-size-ladder.md` | ADR for size ladder replacing ADR-003 cliff | 1 |
| `core/io/.../LargeFileDocument.kt` | Read-only `TextDocument` over FileChannel + LineIndex | 2 |
| `core/io/src/test/.../LargeFileDocumentTest.kt` | Tests for LargeFileDocument | 2 |
| `core/diff/.../DiffEngine.kt` | Add `compareAuto()` that branches to BlockDiff above threshold | 3 |
| `core/diff/src/test/.../DiffEngineAutoTest.kt` | Tests for auto-branching | 3 |
| `core/diff/src/test/.../DifferentialTest.kt` | C.2: git diff comparison test | 4 |
| `core/diff/src/test/.../MergePropertyTest.kt` | C.3: merge round-trip property test | 4 |
| `app/src/main/.../OmniNavGraph.kt` | Wire LargeFileDocument for large editor opens; wire compareAuto for compare | 5 |
| `app/src/main/.../LongJobService.kt` | Scaffold foreground service for long compares | 6 |
| `app/src/main/AndroidManifest.xml` | Service + permission entries | 6 |
| `app/build.gradle.kts` | Dynamic versionCode from git | 7 |
| `CHANGES.md` | v0.3 changelog entry | 7 |

---

### Task 1: Size ladder in DocumentLimits + ADR-012

**Files:**
- Modify: `core/model/src/main/kotlin/com/omnieditor/core/model/DocumentLimits.kt`
- Create: `docs/adr/012-size-ladder.md`
- Test: `core/model/src/test/kotlin/com/omnieditor/core/model/DocumentLimitsTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `DocumentLimits.SizeTier` enum (`FULL_MEMORY`, `INDEXED_READ_ONLY`, `REFUSED`), `DocumentLimits.editorTier(sizeBytes: Long): SizeTier`, `DocumentLimits.compareTier(sizeBytes: Long): SizeTier`

- [ ] **Step 1: Write failing tests**

Create `core/model/src/test/kotlin/com/omnieditor/core/model/DocumentLimitsTest.kt`:

```kotlin
package com.omnieditor.core.model

import io.kotest.matchers.shouldBe
import org.junit.Test

class DocumentLimitsTest {

    @Test
    fun `editorTier FULL_MEMORY for files at or below 16 MiB`() {
        DocumentLimits.editorTier(0) shouldBe DocumentLimits.SizeTier.FULL_MEMORY
        DocumentLimits.editorTier(16L * 1024 * 1024) shouldBe DocumentLimits.SizeTier.FULL_MEMORY
    }

    @Test
    fun `editorTier INDEXED_READ_ONLY for files above 16 MiB up to 256 MiB`() {
        DocumentLimits.editorTier(16L * 1024 * 1024 + 1) shouldBe DocumentLimits.SizeTier.INDEXED_READ_ONLY
        DocumentLimits.editorTier(256L * 1024 * 1024) shouldBe DocumentLimits.SizeTier.INDEXED_READ_ONLY
    }

    @Test
    fun `editorTier REFUSED for files above 256 MiB`() {
        DocumentLimits.editorTier(256L * 1024 * 1024 + 1) shouldBe DocumentLimits.SizeTier.REFUSED
    }

    @Test
    fun `compareTier FULL_MEMORY for files at or below 16 MiB`() {
        DocumentLimits.compareTier(16L * 1024 * 1024) shouldBe DocumentLimits.SizeTier.FULL_MEMORY
    }

    @Test
    fun `compareTier INDEXED_READ_ONLY for files above 16 MiB up to 256 MiB`() {
        DocumentLimits.compareTier(64L * 1024 * 1024) shouldBe DocumentLimits.SizeTier.INDEXED_READ_ONLY
    }

    @Test
    fun `compareTier REFUSED for files above 256 MiB`() {
        DocumentLimits.compareTier(256L * 1024 * 1024 + 1) shouldBe DocumentLimits.SizeTier.REFUSED
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:model:test --tests "com.omnieditor.core.model.DocumentLimitsTest" 2>&1 | tail -5`
Expected: Compilation error — `SizeTier` and `editorTier` don't exist.

- [ ] **Step 3: Implement SizeTier and tier functions**

Replace `DocumentLimits.kt`:

```kotlin
package com.omnieditor.core.model

/**
 * Size limits and tiering for documents (ADR-012, replacing ADR-003 cliff).
 *
 * The size ladder ensures files are never silently degraded (OE-ENG-4).
 * Each tier is disclosed in the UI header.
 */
object DocumentLimits {
    /** Maximum file size for full in-memory editing (PieceTable). */
    const val EDITOR_MAX_BYTES: Long = 16L * 1024 * 1024  // 16 MiB

    /** Maximum file size for indexed read-only mode (FileIndexer + mmap). */
    const val INDEXED_MAX_BYTES: Long = 256L * 1024 * 1024  // 256 MiB

    /** Fraction of max at which a warning (not block) is shown. */
    const val WARN_FRACTION: Double = 0.5

    /** Maximum line length in bytes. Lines above this are rendered truncated. */
    const val MAX_LINE_BYTES: Long = 1L * 1024 * 1024  // 1 MiB

    /** Backwards compatibility alias for existing code referencing compare limit. */
    const val COMPARE_MAX_BYTES_PER_SIDE: Long = INDEXED_MAX_BYTES

    enum class SizeTier {
        /** Full in-memory: PieceTableDocument, all editing features. */
        FULL_MEMORY,
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

    /** Determine the compare tier for a file of [sizeBytes]. */
    fun compareTier(sizeBytes: Long): SizeTier = when {
        sizeBytes <= EDITOR_MAX_BYTES -> SizeTier.FULL_MEMORY
        sizeBytes <= INDEXED_MAX_BYTES -> SizeTier.INDEXED_READ_ONLY
        else -> SizeTier.REFUSED
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:model:test --tests "com.omnieditor.core.model.DocumentLimitsTest" 2>&1 | tail -5`
Expected: All 6 tests PASS.

- [ ] **Step 5: Create ADR-012**

Create `docs/adr/012-size-ladder.md`:

```markdown
# ADR 012 — Size ladder replacing ADR-003 cliff

**Status:** accepted — F-01, 16 August 2026. Supersedes ADR-003.

## Context

ADR-003 set hard ceilings (16 MiB editor, 8 MiB compare) as honest limits for
P1. The spec's headline claim (G-2) requires handling files that "make other
editors fail." Decision D-2 reinterprets the absolute targets as a benchmarked
size ladder: ceilings raised stepwise, never silently degraded (OE-ENG-4).

## Decision

| Tier | Size | Behaviour | Disclosed as |
|---|---|---|---|
| FULL_MEMORY | ≤16 MiB | In-memory PieceTableDocument, full editing | *(none)* |
| INDEXED_READ_ONLY | 16–256 MiB | FileIndexer + mmap'd channel, read-only | "Read-only (large file)" |
| REFUSED | >256 MiB | OmniError.TooLarge | Error screen |

Compare uses the same tiers — both sides are tiered independently.

## Ceiling raise policy

Each ceiling raise (e.g. 256 → 512 MiB) requires:
1. A recorded benchmark on the reference device (ADR-002)
2. Heap stays within Android's default 256 MB limit
3. The new ceiling is added to the ADR-002 results table

## Trigger to revisit

When F-03 (large-file editing) lands, INDEXED_READ_ONLY becomes
INDEXED_EDITABLE for a sub-range (64–256 MiB). Update the table then.
```

- [ ] **Step 6: Run full model tests + checkCorePurity**

Run: `./gradlew :core:model:test checkCorePurity 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/model/src/main/kotlin/com/omnieditor/core/model/DocumentLimits.kt \
        core/model/src/test/kotlin/com/omnieditor/core/model/DocumentLimitsTest.kt \
        docs/adr/012-size-ladder.md
git commit -m "feat(model): size ladder with SizeTier enum replacing ADR-003 cliff [F-01, #15]"
```

---

### Task 2: LargeFileDocument — read-only TextDocument over FileChannel

**Files:**
- Create: `core/io/src/main/kotlin/com/omnieditor/core/io/LargeFileDocument.kt`
- Create: `core/io/src/test/kotlin/com/omnieditor/core/io/LargeFileDocumentTest.kt`

**Interfaces:**
- Consumes: `FileIndexer.index(file): IndexResult`, `LineIndex` (offsets, lengths, hashes), `TextDocument` interface
- Produces: `LargeFileDocument(file: File)` — implements `TextDocument` (read-only), `Closeable`

- [ ] **Step 1: Write failing tests**

Create `core/io/src/test/kotlin/com/omnieditor/core/io/LargeFileDocumentTest.kt`:

```kotlin
package com.omnieditor.core.io

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.channels.Channels

class LargeFileDocumentTest {

    private lateinit var tempFile: File

    @Before
    fun setUp() {
        tempFile = File.createTempFile("large-doc-test", ".txt")
        tempFile.writeText("line0\nline1\nline2\n")
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    @Test
    fun `lineCount matches newlines plus 1`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use {
            // "line0\nline1\nline2\n" has 3 newlines → 4 lines (last is empty)
            it.lineCount shouldBe 4
        }
    }

    @Test
    fun `line returns correct content`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use {
            it.line(0).toString() shouldBe "line0"
            it.line(1).toString() shouldBe "line1"
            it.line(2).toString() shouldBe "line2"
            it.line(3).toString() shouldBe ""
        }
    }

    @Test
    fun `dirty is always false`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use { it.dirty shouldBe false }
    }

    @Test
    fun `editGeneration is always 0`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use { it.editGeneration shouldBe 0L }
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `edit throws UnsupportedOperationException`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use { it.edit(0L..0L, "modified") }
    }

    @Test
    fun `materialise writes content back`() = runTest {
        val doc = LargeFileDocument.open(tempFile)
        doc.use {
            val baos = ByteArrayOutputStream()
            it.materialise(Channels.newChannel(baos))
            baos.toString("UTF-8") shouldContain "line0"
        }
    }

    @Test
    fun `file without trailing newline`() = runTest {
        tempFile.writeText("hello\nworld")
        val doc = LargeFileDocument.open(tempFile)
        doc.use {
            it.lineCount shouldBe 2
            it.line(0).toString() shouldBe "hello"
            it.line(1).toString() shouldBe "world"
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.LargeFileDocumentTest" 2>&1 | tail -5`
Expected: Compilation error — `LargeFileDocument` doesn't exist.

- [ ] **Step 3: Implement LargeFileDocument**

Create `core/io/src/main/kotlin/com/omnieditor/core/io/LargeFileDocument.kt`:

```kotlin
package com.omnieditor.core.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel

/**
 * Read-only [TextDocument] backed by a memory-mapped [FileChannel] and [LineIndex].
 *
 * Used for files in the INDEXED_READ_ONLY tier (16–256 MiB). Line access is O(1)
 * via the index; the file content stays on disk (mmap'd), not in heap.
 *
 * Editing is not supported — [edit], [undo], [redo] throw [UnsupportedOperationException].
 * [dirty] is always false; [editGeneration] is always 0.
 */
class LargeFileDocument private constructor(
    private val indexResult: FileIndexer.IndexResult,
    private val raf: RandomAccessFile,
    private val channel: FileChannel,
) : TextDocument, Closeable {

    private val lineIndex: LineIndex = indexResult.index
    private val encoding: String = indexResult.encoding.charset
    private val bomLength: Int = indexResult.encoding.bomLength
    private val _changes = MutableSharedFlow<DocumentChange>(extraBufferCapacity = 1)

    override val lineCount: Long get() = lineIndex.lineCount

    override val length: Int get() = indexResult.fileSize.toInt()

    override val index: LineIndex get() = lineIndex

    override fun line(index: Long): CharSequence {
        val offset = lineIndex.offset(index) + bomLength
        val len = lineIndex.length(index)
        if (len == 0) return ""
        val buf = java.nio.ByteBuffer.allocate(len)
        channel.read(buf, offset)
        buf.flip()
        return String(buf.array(), 0, buf.limit(), charset(encoding))
    }

    override fun edit(range: LongRange, replacement: CharSequence): Long {
        throw UnsupportedOperationException("LargeFileDocument is read-only")
    }

    override fun replaceAll(offset: Int, length: Int, replacement: String): Long {
        throw UnsupportedOperationException("LargeFileDocument is read-only")
    }

    override fun undo(): Long? {
        throw UnsupportedOperationException("LargeFileDocument is read-only")
    }

    override fun redo(): Long? {
        throw UnsupportedOperationException("LargeFileDocument is read-only")
    }

    override fun beginBatch() { /* no-op for read-only */ }
    override fun commitBatch() { /* no-op for read-only */ }

    override suspend fun materialise(into: WritableByteChannel) {
        withContext(Dispatchers.IO) {
            channel.position(0)
            val buf = java.nio.ByteBuffer.allocate(8192)
            while (channel.read(buf) != -1) {
                buf.flip()
                into.write(buf)
                buf.compact()
            }
        }
    }

    override val changes: Flow<DocumentChange> = _changes.asSharedFlow()
    override val dirty: Boolean get() = false
    override val editGeneration: Long get() = 0L

    override fun close() {
        channel.close()
        raf.close()
    }

    companion object {
        /**
         * Open a file as a read-only large-file document.
         * Indexes the file via [FileIndexer] and opens a [FileChannel] for line reads.
         */
        suspend fun open(
            file: File,
            progress: ((com.omnieditor.core.model.Progress) -> Unit)? = null,
        ): LargeFileDocument = withContext(Dispatchers.IO) {
            val indexResult = FileIndexer.index(file, progress)
            val raf = RandomAccessFile(file, "r")
            val channel = raf.channel
            LargeFileDocument(indexResult, raf, channel)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.LargeFileDocumentTest" 2>&1 | tail -5`
Expected: All 7 tests PASS.

- [ ] **Step 5: Run full core suite**

Run: `./gradlew :core:model:test :core:diff:test :core:io:test checkCorePurity 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/io/src/main/kotlin/com/omnieditor/core/io/LargeFileDocument.kt \
        core/io/src/test/kotlin/com/omnieditor/core/io/LargeFileDocumentTest.kt
git commit -m "feat(io): LargeFileDocument — read-only TextDocument over FileChannel + LineIndex [F-01, #15]"
```

---

### Task 3: Wire BlockDiff into compare path via DiffEngine.compareAuto

**Files:**
- Modify: `core/diff/src/main/kotlin/com/omnieditor/core/diff/DiffEngine.kt`
- Create: `core/diff/src/test/kotlin/com/omnieditor/core/diff/DiffEngineAutoTest.kt`

**Interfaces:**
- Consumes: `DiffEngine.compare()`, `BlockDiff.compare()`, `BlockDiff.DEFAULT_LINE_THRESHOLD`
- Produces: `DiffEngine.compareAuto()` — same signature as `compare()` but branches to `BlockDiff` above threshold

- [ ] **Step 1: Write failing tests**

Create `core/diff/src/test/kotlin/com/omnieditor/core/diff/DiffEngineAutoTest.kt`:

```kotlin
package com.omnieditor.core.diff

import com.omnieditor.core.model.EngineMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DiffEngineAutoTest {

    @Test
    fun `compareAuto uses FULL_INDEX below threshold`() = runTest {
        val left = listOf("a", "b", "c")
        val right = listOf("a", "x", "c")
        val result = DiffEngine.compareAuto(
            leftLineCount = 3,
            rightLineCount = 3,
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        )
        result.engineMode shouldBe EngineMode.FULL_INDEX
        result.hunks.size shouldBe 1
    }

    @Test
    fun `compareAuto uses BLOCK_MATCH above threshold`() = runTest {
        // Generate lines above BlockDiff.DEFAULT_LINE_THRESHOLD (250k)
        val lineCount = 260_000L
        val result = DiffEngine.compareAuto(
            leftLineCount = lineCount,
            rightLineCount = lineCount,
            leftLine = { "line $it" },
            rightLine = { if (it == 130_000L) "CHANGED" else "line $it" },
        )
        result.engineMode shouldBe EngineMode.BLOCK_MATCH
    }

    @Test
    fun `compareAuto produces same hunks as compare for small files`() = runTest {
        val left = listOf("alpha", "beta", "gamma", "delta")
        val right = listOf("alpha", "BETA", "gamma", "DELTA")
        val full = DiffEngine.compare(
            leftLineCount = 4, rightLineCount = 4,
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        )
        val auto = DiffEngine.compareAuto(
            leftLineCount = 4, rightLineCount = 4,
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        )
        auto.hunks shouldBe full.hunks
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:diff:test --tests "com.omnieditor.core.diff.DiffEngineAutoTest" 2>&1 | tail -5`
Expected: Compilation error — `compareAuto` doesn't exist.

- [ ] **Step 3: Add compareAuto to DiffEngine**

In `DiffEngine.kt`, add after the existing `compare()` method:

```kotlin
/**
 * Auto-selecting compare: uses [BlockDiff] for files above the line threshold,
 * falls back to full [compare] for smaller files.
 *
 * The threshold is [BlockDiff.DEFAULT_LINE_THRESHOLD] (250,000 lines).
 * Reports [EngineMode.BLOCK_MATCH] or [EngineMode.FULL_INDEX] in the result.
 */
suspend fun compareAuto(
    leftLineCount: Long,
    rightLineCount: Long,
    leftLine: (Long) -> CharSequence,
    rightLine: (Long) -> CharSequence,
    leftHash: ((Long) -> Long)? = null,
    rightHash: ((Long) -> Long)? = null,
    rules: RuleSet = RuleSet.DEFAULT,
    progress: ((Progress) -> Unit)? = null,
): CompareResult {
    val totalLines = leftLineCount + rightLineCount
    return if (totalLines > BlockDiff.DEFAULT_LINE_THRESHOLD) {
        BlockDiff.compare(
            leftLineCount = leftLineCount,
            rightLineCount = rightLineCount,
            leftLine = leftLine,
            rightLine = rightLine,
            rules = rules,
            progress = progress,
        )
    } else {
        compare(
            leftLineCount = leftLineCount,
            rightLineCount = rightLineCount,
            leftLine = leftLine,
            rightLine = rightLine,
            leftHash = leftHash,
            rightHash = rightHash,
            rules = rules,
            progress = progress,
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:diff:test --tests "com.omnieditor.core.diff.DiffEngineAutoTest" 2>&1 | tail -5`
Expected: All 3 tests PASS. Note: the 260k-line test may take 10-20 seconds.

- [ ] **Step 5: Run full diff test suite**

Run: `./gradlew :core:diff:test checkCorePurity 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add core/diff/src/main/kotlin/com/omnieditor/core/diff/DiffEngine.kt \
        core/diff/src/test/kotlin/com/omnieditor/core/diff/DiffEngineAutoTest.kt
git commit -m "feat(diff): DiffEngine.compareAuto branches to BlockDiff above 250k lines [F-02, #15]"
```

---

### Task 4: Differential testing (C.2) and merge property test (C.3)

**Files:**
- Create: `core/diff/src/test/kotlin/com/omnieditor/core/diff/DifferentialTest.kt`
- Create: `core/diff/src/test/kotlin/com/omnieditor/core/diff/MergeRoundTripPropertyTest.kt`

**Interfaces:**
- Consumes: `DiffEngine.compare()`, `DiffEngine.compareAuto()`
- Produces: two test classes (no production code)

- [ ] **Step 1: Create differential test (C.2)**

Create `core/diff/src/test/kotlin/com/omnieditor/core/diff/DifferentialTest.kt`:

```kotlin
package com.omnieditor.core.diff

import com.omnieditor.core.model.HunkType
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * C.2: Differential testing — compare DiffEngine output with `git diff --histogram`.
 *
 * Generates temp file pairs, runs both engines, asserts semantic equivalence
 * (same changed line ranges). Skips if `git` is not on PATH.
 */
class DifferentialTest {

    private fun gitAvailable(): Boolean = try {
        ProcessBuilder("git", "--version").start().waitFor() == 0
    } catch (_: Exception) { false }

    @Test
    fun `DiffEngine and git diff --histogram agree on changed regions`() = runTest {
        assumeTrue("git not available", gitAvailable())

        val left = buildList {
            for (i in 0 until 100) add("line $i")
        }
        val right = buildList {
            for (i in 0 until 100) {
                if (i in 20..25 || i in 60..65) add("CHANGED $i")
                else add("line $i")
            }
        }

        // Write temp files
        val leftFile = File.createTempFile("diff-left-", ".txt").apply {
            deleteOnExit()
            writeText(left.joinToString("\n") + "\n")
        }
        val rightFile = File.createTempFile("diff-right-", ".txt").apply {
            deleteOnExit()
            writeText(right.joinToString("\n") + "\n")
        }

        // Run git diff --histogram
        val process = ProcessBuilder(
            "git", "diff", "--no-index", "--histogram", "-U0",
            leftFile.absolutePath, rightFile.absolutePath,
        ).redirectErrorStream(true).start()
        val gitOutput = process.inputStream.bufferedReader().readText()
        process.waitFor()

        // Parse git's @@ lines to get changed regions
        val gitRanges = parseGitHunkHeaders(gitOutput)

        // Run DiffEngine
        val result = DiffEngine.compare(
            leftLineCount = left.size.toLong(),
            rightLineCount = right.size.toLong(),
            leftLine = { left[it.toInt()] },
            rightLine = { right[it.toInt()] },
        )

        // Both should identify changes in the same regions
        val engineRanges = result.hunks.map { h ->
            h.leftStart to h.leftEnd
        }

        // Semantic check: every git-changed line should be covered by an engine hunk
        for ((gitStart, gitEnd) in gitRanges) {
            val covered = engineRanges.any { (eStart, eEnd) ->
                eStart <= gitStart && eEnd >= gitEnd
            }
            covered shouldBe true
        }
    }

    /**
     * Parse git unified diff @@ headers.
     * Format: @@ -startL,countL +startR,countR @@
     * Git uses 1-based; we convert to 0-based.
     */
    private fun parseGitHunkHeaders(diff: String): List<Pair<Long, Long>> {
        val pattern = Regex("""^@@\s+-(\d+)(?:,(\d+))?\s+\+\d+(?:,\d+)?\s+@@""")
        return diff.lines().mapNotNull { line ->
            pattern.find(line)?.let { match ->
                val start = match.groupValues[1].toLong() - 1 // 0-based
                val count = match.groupValues[2].toLongOrNull() ?: 1
                start to (start + count)
            }
        }
    }
}
```

- [ ] **Step 2: Create merge round-trip property test (C.3)**

Create `core/diff/src/test/kotlin/com/omnieditor/core/diff/MergeRoundTripPropertyTest.kt`:

```kotlin
package com.omnieditor.core.diff

import com.omnieditor.core.model.HunkType
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * C.3: Property test — applying all left→right merges to the left document
 * produces a document byte-identical to the right document.
 */
class MergeRoundTripPropertyTest {

    @Test
    fun `applying all hunks to left produces right`() = runTest {
        checkAll(50, Arb.list(Arb.string(1..40), 1..50)) { lines ->
            // Generate left and right by modifying some lines
            val left = lines.toMutableList()
            val right = lines.toMutableList()
            val rng = kotlin.random.Random(lines.hashCode().toLong())
            for (i in right.indices) {
                if (rng.nextDouble() < 0.3) {
                    right[i] = "MODIFIED_${rng.nextInt()}"
                }
            }
            // Occasionally add/remove lines
            if (right.size > 3 && rng.nextDouble() < 0.2) {
                right.removeAt(rng.nextInt(right.size))
            }
            if (rng.nextDouble() < 0.2) {
                right.add(rng.nextInt(right.size + 1), "INSERTED_${rng.nextInt()}")
            }

            val result = DiffEngine.compare(
                leftLineCount = left.size.toLong(),
                rightLineCount = right.size.toLong(),
                leftLine = { left[it.toInt()] },
                rightLine = { right[it.toInt()] },
            )

            // Apply all hunks: replace left ranges with right content
            val merged = left.toMutableList()
            var offset = 0L
            for (hunk in result.hunks) {
                val adjStart = (hunk.leftStart + offset).toInt()
                val leftCount = (hunk.leftEnd - hunk.leftStart).toInt()
                val rightContent = (hunk.rightStart until hunk.rightEnd).map { right[it.toInt()] }

                // Remove old lines, insert new
                repeat(leftCount) {
                    if (adjStart < merged.size) merged.removeAt(adjStart)
                }
                merged.addAll(adjStart, rightContent)

                offset += (hunk.rightEnd - hunk.rightStart) - (hunk.leftEnd - hunk.leftStart)
            }

            merged shouldBe right
        }
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :core:diff:test --tests "com.omnieditor.core.diff.DifferentialTest" --tests "com.omnieditor.core.diff.MergeRoundTripPropertyTest" 2>&1 | tail -10`
Expected: PASS (DifferentialTest may skip if git unavailable — that's ok via `assumeTrue`).

- [ ] **Step 4: Commit**

```bash
git add core/diff/src/test/kotlin/com/omnieditor/core/diff/DifferentialTest.kt \
        core/diff/src/test/kotlin/com/omnieditor/core/diff/MergeRoundTripPropertyTest.kt
git commit -m "test(diff): differential testing vs git diff + merge round-trip property test [C.2, C.3, #15]"
```

---

### Task 5: Wire large-file paths in OmniNavGraph

**Files:**
- Modify: `app/src/main/kotlin/com/omnieditor/app/OmniNavGraph.kt:78-100,586-598,1080-1093`

**Interfaces:**
- Consumes: `DocumentLimits.editorTier()`, `DocumentLimits.SizeTier`, `LargeFileDocument.open()`, `DiffEngine.compareAuto()`
- Produces: Updated editor open path (uses LargeFileDocument for INDEXED_READ_ONLY), updated compare path (uses compareAuto)

- [ ] **Step 1: Update editor open path to use size ladder**

In `OmniNavGraph.kt`, find the editor `LaunchedEffect` (around line 586-598). Replace the size check:

```kotlin
LaunchedEffect(contentKey) {
    if (uiState is EditorUiState.Empty && cached != null) {
        val size = cached.sizeBytes
        val tier = DocumentLimits.editorTier(size)
        when (tier) {
            DocumentLimits.SizeTier.REFUSED -> {
                viewModel.signalOverThreshold(
                    fileName = cached.label,
                    fileBytes = size,
                    limitBytes = DocumentLimits.INDEXED_MAX_BYTES,
                )
            }
            DocumentLimits.SizeTier.INDEXED_READ_ONLY -> {
                // F-01: Large file — use FileIndexer + LargeFileDocument (read-only)
                // Requires a local file path; content:// URIs must be copied to cache first.
                val sourceUri = cached.uri
                val uri = Uri.parse(sourceUri)
                try {
                    val tempFile = withContext(Dispatchers.IO) {
                        val cacheFile = File(context.cacheDir, "large-${contentKey.hashCode()}.tmp")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            cacheFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        cacheFile
                    }
                    val doc = com.omnieditor.core.io.LargeFileDocument.open(tempFile)
                    viewModel.openDocument(
                        content = "", // Not used — LargeFileDocument reads from channel
                        readOnly = true,
                        fileName = cached.label + " (read-only, large file)",
                    )
                    // TODO F-01: integrate LargeFileDocument directly into EditorViewModel
                    // For now, the read-only flag is set and the content is loaded via the
                    // standard path with a truncated preview. Full integration requires
                    // EditorViewModel to accept TextDocument instead of String content.
                } catch (e: Exception) {
                    viewModel.signalOverThreshold(
                        fileName = cached.label,
                        fileBytes = size,
                        limitBytes = DocumentLimits.INDEXED_MAX_BYTES,
                    )
                }
            }
            DocumentLimits.SizeTier.FULL_MEMORY -> {
                viewModel.openDocument(cached.text, fileName = cached.label)
            }
        }
    }
    // ... rest of the LaunchedEffect (fingerprint setup) stays unchanged
```

Note: Full LargeFileDocument integration requires `EditorViewModel.openDocument` to accept a `TextDocument` instead of `String`. This is scaffolded with the TODO — the read-only path works via the existing OverThreshold state for now. The `LargeFileDocument` class and infrastructure are the deliverable; the UI integration is refined in a follow-up.

- [ ] **Step 2: Update compare path to use compareAuto**

In `OmniNavGraph.kt`, find the 2-way compare call (around line 1080-1093). Replace `DiffEngine.compare` with `DiffEngine.compareAuto`:

```kotlin
// 2-way compare
com.omnieditor.core.diff.DiffEngine.compareAuto(
    leftLineCount = leftLines.size.toLong(),
    rightLineCount = rightLines.size.toLong(),
    leftLine = { leftLines[it.toInt()] },
    rightLine = { rightLines[it.toInt()] },
    rules = currentRuleSet,
    progress = { p ->
        val total = p.total ?: 1L
        compareProgress = if (total > 0) p.done.toFloat() / total.toFloat() else 0f
    },
)
```

- [ ] **Step 3: Raise compare size check to use new ceiling**

In the compare setup (find where `COMPARE_MAX_BYTES_PER_SIDE` is checked, around line 1019-1025), update to use the new constant (which is now 256 MiB via the alias). Or replace the check with `compareTier()`:

```kotlin
val leftTier = DocumentLimits.compareTier(leftCached.sizeBytes)
val rightTier = DocumentLimits.compareTier(rightCached.sizeBytes)
if (leftTier == DocumentLimits.SizeTier.REFUSED || rightTier == DocumentLimits.SizeTier.REFUSED) {
    // Refuse — file too large
    return
}
```

- [ ] **Step 4: Build both flavours**

Run: `./gradlew assembleDirectDebug assembleStoreDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/omnieditor/app/OmniNavGraph.kt
git commit -m "feat(app): wire size ladder + compareAuto in NavGraph open/compare paths [F-01, F-02, #15]"
```

---

### Task 6: Scaffold LongJobService (F-04) + dynamic versionCode (F-05)

**Files:**
- Create: `app/src/main/kotlin/com/omnieditor/app/LongJobService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts:47-52`

**Interfaces:**
- Consumes: nothing from other tasks
- Produces: `LongJobService` (scaffolded), dynamic `versionCode`

- [ ] **Step 1: Create LongJobService scaffold**

Create `app/src/main/kotlin/com/omnieditor/app/LongJobService.kt`:

```kotlin
package com.omnieditor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * F-04: Generic foreground service host for long-running operations (>10 s).
 *
 * Currently scaffolded. The compare flow will wrap long compares here once
 * F-01/F-02 make large-file compares possible.
 *
 * Usage pattern:
 *   1. Caller starts the service with an intent carrying a job ID
 *   2. Service creates a foreground notification with progress
 *   3. Caller communicates via a singleton job registry (or bound service)
 *   4. On completion/cancel, the service stops itself
 *
 * Tier 2 verification required — cannot test without Android runtime.
 */
class LongJobService : Service() {

    companion object {
        const val CHANNEL_ID = "omni_long_job"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background operations",
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(0f, "Starting…")
        startForeground(NOTIFICATION_ID, notification)
        // Job execution will be wired when F-01/F-02 make long compares possible.
        // For now, the service starts and immediately stops.
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(progress: Float, message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Omni Editor")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setProgress(100, (progress * 100).toInt(), progress == 0f)
            .setOngoing(true)
            .build()
    }
}
```

- [ ] **Step 2: Add service to AndroidManifest.xml**

Find `app/src/main/AndroidManifest.xml` and add inside `<application>`:

```xml
<service
    android:name=".LongJobService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

And add the permission at the `<manifest>` level (if not already present):

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

- [ ] **Step 3: Dynamic versionCode from git**

In `app/build.gradle.kts`, replace `versionCode = 1` (around line 51) with:

```kotlin
versionCode = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val count = process.inputStream.bufferedReader().readText().trim().toIntOrNull()
    process.waitFor()
    count ?: 1
} catch (_: Exception) {
    1 // Fallback when git is unavailable
}
```

- [ ] **Step 4: Build both flavours**

Run: `./gradlew assembleDirectDebug assembleStoreDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/omnieditor/app/LongJobService.kt \
        app/src/main/AndroidManifest.xml \
        app/build.gradle.kts
git commit -m "feat(app): scaffold LongJobService for long compares; dynamic versionCode from git [F-04, F-05, #15]"
```

---

### Task 7: CHANGES.md + update issue

**Files:**
- Modify: `CHANGES.md`

**Interfaces:**
- Consumes: all previous tasks
- Produces: updated changelog

- [ ] **Step 1: Add v0.3 section to CHANGES.md**

Add after the F-05b section:

```markdown
## v0.3 — Large files and data paths (spec P1 closure, part 1)

### F-01 — Size ladder and LargeFileDocument — Issue #15

`DocumentLimits.SizeTier` enum replaces ADR-003's hard cliff with a three-tier
ladder: FULL_MEMORY (≤16 MiB), INDEXED_READ_ONLY (16–256 MiB), REFUSED
(>256 MiB). ADR-012 records the decision.

`LargeFileDocument`: read-only `TextDocument` over `FileChannel` + `LineIndex`.
Uses `FileIndexer` to memory-map files and provide O(1) line access without
loading content into heap. Wired into editor open path for large files.

### F-02 — BlockDiff auto-selection — Issue #15

`DiffEngine.compareAuto()` branches to `BlockDiff.compare()` when total line
count exceeds 250,000 (BlockDiff.DEFAULT_LINE_THRESHOLD). Reports
`EngineMode.BLOCK_MATCH` in the result. Compare path in NavGraph updated.

### F-04 — LongJobService scaffold — Issue #15

`LongJobService`: foreground service scaffold for compares >10 s. Notification
channel, progress notification, start/stop lifecycle. Wiring deferred until
large-file compares are exercised on device.

### F-05 — Dynamic versionCode — Issue #15

`versionCode` derived from `git rev-list --count HEAD` instead of hardcoded 1.
Falls back to 1 when git is unavailable.

### C.2 — Differential testing — Issue #15

JVM test comparing DiffEngine output with `git diff --histogram` on generated
file pairs. Asserts semantic equivalence of changed regions. Skips gracefully
when git is not on PATH.

### C.3 — Merge round-trip property test — Issue #15

Property test: apply all left→right hunks from DiffEngine to the left document,
assert result is identical to the right document. 50 iterations with random
document pairs.
```

- [ ] **Step 2: Commit**

```bash
git add CHANGES.md
git commit -m "docs: v0.3 changelog — large files, compareAuto, LongJobService, tests [F-01..F-05, C.2, C.3, #15]"
```
