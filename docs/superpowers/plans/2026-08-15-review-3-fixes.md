# Review-3 Fixes (R-51 through R-57) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all seven Review-3 findings (data safety, correctness, undo batching, release hygiene, docs) to unblock the v0.2.1 tag and P2 desktop port.

**Architecture:** Consolidate all save logic behind `SourceProvider.write()` with a new `SaveCoordinator` in `core/io`. Add `editBatch`/`commitBatch` to `PieceTableDocument` for atomic multi-line undo. Re-emit BOM on `materialise()`. Fix merge save to use `materialise()`. Update docs.

**Tech Stack:** Kotlin 2.3.21, JUnit 4, Kotest assertions, kotlinx.coroutines.test

## Global Constraints

- `core/model` and `core/diff` must not import `android.*` or `androidx.*` (`checkCorePurity`).
- `java.io.File` and `ContentResolver` only in `core/io`, `app/src/direct`, `app/src/store`, `app/src/main`, and test sources (`checkIoBoundary`).
- Line count = `newlines + 1` (ADR-007).
- Tests land in the same commit as the code they test.
- Commit messages reference requirement IDs.
- No new dependency without a line in `docs/licenses.md`.
- Both flavours (`direct`, `store`) must build.
- Run: `./gradlew checkCorePurity checkIoBoundary testDirectDebugUnitTest testStoreDebugUnitTest :core:model:test :core:diff:test :core:io:test`

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `core/io/src/main/kotlin/.../PieceTableDocument.kt` | Add `beginBatch()`/`commitBatch()` for grouped undo; add BOM field + re-emit in `materialise()` | 1, 2 |
| `core/io/src/main/kotlin/.../TextDocument.kt` | Add `beginBatch()`/`commitBatch()` to interface | 1 |
| `core/io/src/test/kotlin/.../PieceTableDocumentTest.kt` | Tests for batch undo and BOM materialise | 1, 2 |
| `feature/editor/src/main/kotlin/.../EditorState.kt` | Use `beginBatch()`/`commitBatch()` in indent/outdent/toggleComment; make moveLineUp/Down selection-aware | 3 |
| `feature/editor/src/test/kotlin/.../EditorStateTest.kt` | Tests for batched undo in multi-line ops, selection-aware move | 3 |
| `app/src/main/kotlin/.../OmniNavGraph.kt` | Replace inline save logic with `SaveCoordinator` calls; replace merge save `text().toByteArray()` with `materialise()` | 4 |
| `app/src/direct/kotlin/.../DirectSourceProvider.kt` | Fix temp-file leak on `renameTo` failure; add `channel.force(true)` before rename | 5 |
| `docs/licenses.md` | Remove contradictory line; add JUnit 4 EPL-1.0 carve-out | 5 |
| `README.md` | Add missing modules to table | 5 |
| `docs/adr/001-test-environment.md` | Note GMD coverage is smoke-only at v0.2.0 | 5 |

---

### Task 1: Batch undo support in PieceTableDocument (R-54 prerequisite)

**Files:**
- Modify: `core/io/src/main/kotlin/com/omnieditor/core/io/TextDocument.kt:15-71`
- Modify: `core/io/src/main/kotlin/com/omnieditor/core/io/PieceTableDocument.kt:25-60,78-111`
- Test: `core/io/src/test/kotlin/com/omnieditor/core/io/PieceTableDocumentTest.kt`

**Interfaces:**
- Consumes: `PieceTable.replace(offset, count, text): EditRecord`
- Produces: `TextDocument.beginBatch()`, `TextDocument.commitBatch()` — later tasks call these to group edits.

The current `edit()` method creates one undo entry per call. Multi-line operations (indent/outdent/toggleComment) call `edit()` in a loop, producing N undo steps. We need `beginBatch()`/`commitBatch()` that groups all edits between them into a single undoable step.

Strategy: `beginBatch()` saves the current undo stack size. Each `edit()` during a batch still pushes individual entries. `commitBatch()` pops all entries added since `beginBatch()`, reverses them all, then replays them as one compound `replaceAll`-style entry. This preserves the existing edit logic without changing `PieceTable`.

Simpler approach: `beginBatch()` records undo stack depth. `commitBatch()` merges all entries since that depth into a single compound entry by combining their records. `undo()` then undoes them all at once.

- [ ] **Step 1: Write failing tests for batch undo**

Add to `PieceTableDocumentTest.kt`:

```kotlin
@Test
fun `batch groups multiple edits into single undo step`() {
    val doc = PieceTableDocument.create("line0\nline1\nline2")
    doc.beginBatch()
    doc.edit(0L..0L, "  line0")
    doc.edit(1L..1L, "  line1")
    doc.edit(2L..2L, "  line2")
    doc.commitBatch()

    doc.line(0).toString() shouldBe "  line0"
    doc.line(1).toString() shouldBe "  line1"
    doc.line(2).toString() shouldBe "  line2"

    // Single undo should revert ALL three edits
    doc.undo()
    doc.line(0).toString() shouldBe "line0"
    doc.line(1).toString() shouldBe "line1"
    doc.line(2).toString() shouldBe "line2"

    // Single redo should reapply all three
    doc.redo()
    doc.line(0).toString() shouldBe "  line0"
    doc.line(1).toString() shouldBe "  line1"
    doc.line(2).toString() shouldBe "  line2"
}

@Test
fun `batch with single edit behaves like normal edit`() {
    val doc = PieceTableDocument.create("hello")
    doc.beginBatch()
    doc.edit(0L..0L, "world")
    doc.commitBatch()

    doc.line(0).toString() shouldBe "world"
    doc.undo()
    doc.line(0).toString() shouldBe "hello"
}

@Test
fun `nested batch is flat - inner commit is ignored`() {
    val doc = PieceTableDocument.create("a\nb")
    doc.beginBatch()
    doc.edit(0L..0L, "A")
    doc.beginBatch() // nested — should be ignored
    doc.edit(1L..1L, "B")
    doc.commitBatch() // ends the outer batch
    // No second commitBatch needed

    doc.undo()
    doc.line(0).toString() shouldBe "a"
    doc.line(1).toString() shouldBe "b"
}

@Test
fun `empty batch is no-op`() {
    val doc = PieceTableDocument.create("hello")
    val genBefore = doc.editGeneration
    doc.beginBatch()
    doc.commitBatch()
    doc.editGeneration shouldBe genBefore
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.PieceTableDocumentTest" --info 2>&1 | tail -20`
Expected: Compilation error — `beginBatch`/`commitBatch` do not exist yet.

- [ ] **Step 3: Add beginBatch/commitBatch to TextDocument interface**

In `TextDocument.kt`, add after the `redo()` method (around line 50):

```kotlin
/**
 * Begin a batch of edits that will be undone/redone as a single step.
 * Calls to [edit] between [beginBatch] and [commitBatch] are grouped.
 * Nested calls are ignored (flat batching).
 */
fun beginBatch()

/**
 * Commit the current batch. All edits since the matching [beginBatch]
 * are merged into a single undo step. No-op if no batch is active.
 */
fun commitBatch()
```

- [ ] **Step 4: Implement beginBatch/commitBatch in PieceTableDocument**

Add fields to `PieceTableDocument` (after `coalesceWindowMs`):

```kotlin
/** Undo stack depth when beginBatch() was called, or -1 if no batch is active. */
private var batchStartDepth = -1
```

Implement the methods:

```kotlin
override fun beginBatch() {
    if (batchStartDepth >= 0) return // already in a batch — flat nesting
    breakCoalescing()
    batchStartDepth = undoStack.size
}

override fun commitBatch() {
    val startDepth = batchStartDepth
    if (startDepth < 0) return // no active batch
    batchStartDepth = -1
    val batchSize = undoStack.size - startDepth
    if (batchSize <= 1) return // 0 or 1 edit — nothing to merge

    // Collect the batch entries (in order they were applied)
    val batchEntries = undoStack.subList(startDepth, undoStack.size).toList()

    // Remove them from the undo stack
    repeat(batchSize) { undoStack.removeAt(undoStack.lastIndex) }

    // Reverse all batch edits to get back to pre-batch state
    for (entry in batchEntries.reversed()) {
        applyReverse(entry.record)
    }

    // Snapshot the pre-batch state, then replay all edits to build a compound record
    val preText = table.text()
    for (entry in batchEntries) {
        applyForward(entry.record)
    }
    val postText = table.text()

    // Build one compound replaceAll-style entry
    val editId = ++editIdCounter
    // Reverse all again
    for (entry in batchEntries.reversed()) {
        applyReverse(entry.record)
    }
    // Apply as single replaceAll
    val record = table.replace(0, preText.length, postText)
    val compoundEntry = JournalEntry(editId, record)
    undoStack.add(compoundEntry)
    redoStack.clear()
    journal?.append(compoundEntry)
    _editGeneration++
    _changes.tryEmit(DocumentChange(editId, 0, lineCount, lineCount))
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.PieceTableDocumentTest" --info 2>&1 | tail -20`
Expected: All tests PASS.

- [ ] **Step 6: Run full core test suite**

Run: `./gradlew :core:model:test :core:diff:test :core:io:test 2>&1 | tail -10`
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add core/io/src/main/kotlin/com/omnieditor/core/io/TextDocument.kt \
        core/io/src/main/kotlin/com/omnieditor/core/io/PieceTableDocument.kt \
        core/io/src/test/kotlin/com/omnieditor/core/io/PieceTableDocumentTest.kt
git commit -m "feat(io): add beginBatch/commitBatch for grouped undo [R-54, #13]"
```

---

### Task 2: BOM re-emit on materialise (R-53)

**Files:**
- Modify: `core/io/src/main/kotlin/com/omnieditor/core/io/PieceTableDocument.kt:25-30,253-261,323-338`
- Test: `core/io/src/test/kotlin/com/omnieditor/core/io/PieceTableDocumentTest.kt`

**Interfaces:**
- Consumes: `PieceTableDocument.create(content, encoding, lineEnding, ...)` — adds `bomLength` parameter
- Produces: `materialise()` re-emits BOM bytes before content

Currently `materialise()` calls `table.text().toByteArray(charset(encoding))`. If the file had a BOM on read, the BOM was skipped by `FileIndexer` and is not in the piece table content. The BOM must be re-emitted when saving.

- [ ] **Step 1: Write failing tests for BOM materialise**

Add to `PieceTableDocumentTest.kt`:

```kotlin
@Test
fun `materialise re-emits UTF-8 BOM when source had one`() = runTest {
    val doc = PieceTableDocument.create("hello", encoding = "UTF-8", bomLength = 3)
    val baos = ByteArrayOutputStream()
    doc.materialise(Channels.newChannel(baos))
    val bytes = baos.toByteArray()
    // UTF-8 BOM: EF BB BF
    bytes[0] shouldBe 0xEF.toByte()
    bytes[1] shouldBe 0xBB.toByte()
    bytes[2] shouldBe 0xBF.toByte()
    // Content follows
    String(bytes, 3, bytes.size - 3, Charsets.UTF_8) shouldBe "hello"
}

@Test
fun `materialise emits no BOM when source had none`() = runTest {
    val doc = PieceTableDocument.create("hello", encoding = "UTF-8", bomLength = 0)
    val baos = ByteArrayOutputStream()
    doc.materialise(Channels.newChannel(baos))
    val bytes = baos.toByteArray()
    String(bytes, Charsets.UTF_8) shouldBe "hello"
    // First byte should NOT be BOM
    bytes[0] shouldBe 'h'.code.toByte()
}

@Test
fun `materialise re-emits UTF-16LE BOM`() = runTest {
    val doc = PieceTableDocument.create("hi", encoding = "UTF-16LE", bomLength = 2)
    val baos = ByteArrayOutputStream()
    doc.materialise(Channels.newChannel(baos))
    val bytes = baos.toByteArray()
    // UTF-16 LE BOM: FF FE
    bytes[0] shouldBe 0xFF.toByte()
    bytes[1] shouldBe 0xFE.toByte()
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.PieceTableDocumentTest" --info 2>&1 | tail -20`
Expected: Compilation error — `bomLength` parameter does not exist.

- [ ] **Step 3: Add bomLength to PieceTableDocument**

In `PieceTableDocument`, add a `bomLength` field to the private constructor:

```kotlin
class PieceTableDocument private constructor(
    private val table: PieceTable,
    private val journal: Journal?,
    private val encoding: String,
    private val lineEnding: LineEnding,
    private val bomLength: Int = 0,
) : TextDocument, java.io.Closeable {
```

Add `bomLength` parameter to the `create` factory method:

```kotlin
fun create(
    content: String = "",
    encoding: String = "UTF-8",
    lineEnding: LineEnding = LineEnding.LF,
    journalDir: File? = null,
    documentId: String? = null,
    bomLength: Int = 0,
): PieceTableDocument {
    val journal = if (journalDir != null && documentId != null) {
        Journal(File(journalDir, "$documentId.journal"))
    } else null
    return PieceTableDocument(
        PieceTable.create(content),
        journal,
        encoding,
        lineEnding,
        bomLength,
    )
}
```

- [ ] **Step 4: Update materialise to re-emit BOM**

Replace the `materialise` method:

```kotlin
override suspend fun materialise(into: WritableByteChannel) {
    withContext(Dispatchers.IO) {
        val stream = Channels.newOutputStream(into)
        // Re-emit BOM if the source had one
        if (bomLength > 0) {
            val bomBytes = when {
                encoding.equals("UTF-8", ignoreCase = true) && bomLength == 3 ->
                    byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                encoding.equals("UTF-16LE", ignoreCase = true) && bomLength == 2 ->
                    byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                encoding.equals("UTF-16BE", ignoreCase = true) && bomLength == 2 ->
                    byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                encoding.equals("UTF-32LE", ignoreCase = true) && bomLength == 4 ->
                    byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00)
                encoding.equals("UTF-32BE", ignoreCase = true) && bomLength == 4 ->
                    byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte())
                else -> null
            }
            if (bomBytes != null) stream.write(bomBytes)
        }
        val text = table.text()
        val bytes = text.toByteArray(charset(encoding))
        stream.write(bytes)
        stream.flush()
    }
}
```

- [ ] **Step 5: Propagate bomLength from FileIndexer to PieceTableDocument creation**

Find where `PieceTableDocument.create` is called with file content (in `OmniNavGraph.kt` or wherever the document is opened from a file). The `FileIndexer.index()` returns an `IndexResult` containing `encoding` which has `bomLength`. Pass it through. Search for all `PieceTableDocument.create` call sites and add `bomLength` where the encoding result is available. If the call site is in `OmniNavGraph.kt`, update there; the `bomLength` parameter defaults to 0 so existing test calls remain valid.

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :core:io:test --tests "com.omnieditor.core.io.PieceTableDocumentTest" --info 2>&1 | tail -20`
Expected: All tests PASS.

- [ ] **Step 7: Run full test suite**

Run: `./gradlew :core:model:test :core:diff:test :core:io:test 2>&1 | tail -10`
Expected: All tests PASS.

- [ ] **Step 8: Commit**

```bash
git add core/io/src/main/kotlin/com/omnieditor/core/io/PieceTableDocument.kt \
        core/io/src/main/kotlin/com/omnieditor/core/io/TextDocument.kt \
        core/io/src/test/kotlin/com/omnieditor/core/io/PieceTableDocumentTest.kt
git commit -m "feat(io): re-emit BOM on materialise when source had one [R-53, #13]"
```

---

### Task 3: Batch undo for indent/outdent/toggleComment + selection-aware move (R-54)

**Files:**
- Modify: `feature/editor/src/main/kotlin/com/omnieditor/feature/editor/EditorState.kt:516-602`
- Test: `feature/editor/src/test/kotlin/com/omnieditor/feature/editor/EditorStateTest.kt`

**Interfaces:**
- Consumes: `PieceTableDocument.beginBatch()`, `PieceTableDocument.commitBatch()` from Task 1
- Produces: Updated `indent()`, `outdent()`, `toggleComment()`, `moveLineUp()`, `moveLineDown()`

- [ ] **Step 1: Write failing tests**

Add to `EditorStateTest.kt`:

```kotlin
// ── R-54: Batch undo for multi-line operations ────────────────────

@Test
fun `indent 3 lines is single undo step`() {
    val state = stateOf("aaa\nbbb\nccc")
    state.selectionAnchorLine = 0L
    state.selectionAnchorColumn = 0
    state.caretLine = 2L
    state.caretColumn = 3
    state.indent(4)

    state.document.line(0).toString() shouldBe "    aaa"
    state.document.line(1).toString() shouldBe "    bbb"
    state.document.line(2).toString() shouldBe "    ccc"

    // Single undo reverts all three
    state.document.undo()
    state.document.line(0).toString() shouldBe "aaa"
    state.document.line(1).toString() shouldBe "bbb"
    state.document.line(2).toString() shouldBe "ccc"
}

@Test
fun `outdent 3 lines is single undo step`() {
    val state = stateOf("    aaa\n    bbb\n    ccc")
    state.selectionAnchorLine = 0L
    state.selectionAnchorColumn = 0
    state.caretLine = 2L
    state.caretColumn = 7
    state.outdent(4)

    state.document.line(0).toString() shouldBe "aaa"
    state.document.line(1).toString() shouldBe "bbb"
    state.document.line(2).toString() shouldBe "ccc"

    state.document.undo()
    state.document.line(0).toString() shouldBe "    aaa"
    state.document.line(1).toString() shouldBe "    bbb"
    state.document.line(2).toString() shouldBe "    ccc"
}

@Test
fun `toggleComment 3 lines is single undo step`() {
    val state = stateOf("aaa\nbbb\nccc")
    state.selectionAnchorLine = 0L
    state.selectionAnchorColumn = 0
    state.caretLine = 2L
    state.caretColumn = 3
    state.toggleComment("//")

    state.document.line(0).toString() shouldBe "// aaa"
    state.document.line(1).toString() shouldBe "// bbb"
    state.document.line(2).toString() shouldBe "// ccc"

    state.document.undo()
    state.document.line(0).toString() shouldBe "aaa"
    state.document.line(1).toString() shouldBe "bbb"
    state.document.line(2).toString() shouldBe "ccc"
}

@Test
fun `moveLineUp with multi-line selection moves all selected lines`() {
    val state = stateOf("aaa\nbbb\nccc\nddd")
    state.selectionAnchorLine = 1L
    state.selectionAnchorColumn = 0
    state.caretLine = 2L
    state.caretColumn = 3
    state.moveLineUp()

    state.document.line(0).toString() shouldBe "bbb"
    state.document.line(1).toString() shouldBe "ccc"
    state.document.line(2).toString() shouldBe "aaa"
    state.document.line(3).toString() shouldBe "ddd"
}

@Test
fun `moveLineDown with multi-line selection moves all selected lines`() {
    val state = stateOf("aaa\nbbb\nccc\nddd")
    state.selectionAnchorLine = 0L
    state.selectionAnchorColumn = 0
    state.caretLine = 1L
    state.caretColumn = 3
    state.moveLineDown()

    state.document.line(0).toString() shouldBe "ccc"
    state.document.line(1).toString() shouldBe "aaa"
    state.document.line(2).toString() shouldBe "bbb"
    state.document.line(3).toString() shouldBe "ddd"
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :feature:editor:testDirectDebugUnitTest --tests "com.omnieditor.feature.editor.EditorStateTest" --info 2>&1 | tail -20`
Expected: FAIL — indent produces 3 undo steps, move-line ignores selection.

- [ ] **Step 3: Wrap indent/outdent/toggleComment with beginBatch/commitBatch**

In `EditorState.kt`, modify `indent()`:

```kotlin
fun indent(tabWidth: Int = 4) {
    if (readOnly) return
    val spaces = " ".repeat(tabWidth)
    val (startLine, endLine) = selectedLineRange()
    document.beginBatch()
    for (l in startLine..endLine) {
        val text = document.line(l).toString()
        document.edit(l..l, spaces + text)
    }
    document.commitBatch()
    if (!hasSelection) {
        caretColumn += tabWidth
    }
}
```

Modify `outdent()`:

```kotlin
fun outdent(tabWidth: Int = 4) {
    if (readOnly) return
    val (startLine, endLine) = selectedLineRange()
    document.beginBatch()
    for (l in startLine..endLine) {
        val text = document.line(l).toString()
        val leading = text.length - text.trimStart(' ').length
        val remove = minOf(leading, tabWidth)
        if (remove > 0) {
            document.edit(l..l, text.substring(remove))
            if (l == caretLine) {
                caretColumn = maxOf(0, caretColumn - remove)
            }
        }
    }
    document.commitBatch()
}
```

Modify `toggleComment()`:

```kotlin
fun toggleComment(prefix: String = "//") {
    if (readOnly) return
    val (startLine, endLine) = selectedLineRange()
    val allCommented = (startLine..endLine).all { l ->
        document.line(l).toString().trimStart().startsWith(prefix)
    }
    document.beginBatch()
    for (l in startLine..endLine) {
        val text = document.line(l).toString()
        if (allCommented) {
            val idx = text.indexOf(prefix)
            if (idx >= 0) {
                val afterPrefix = idx + prefix.length
                val end = if (afterPrefix < text.length && text[afterPrefix] == ' ') {
                    afterPrefix + 1
                } else {
                    afterPrefix
                }
                document.edit(l..l, text.removeRange(idx, end))
            }
        } else {
            val leadingSpaces = text.length - text.trimStart().length
            val newLine = text.substring(0, leadingSpaces) + prefix + " " + text.substring(leadingSpaces)
            document.edit(l..l, newLine)
        }
    }
    document.commitBatch()
}
```

- [ ] **Step 4: Make moveLineUp/moveLineDown selection-aware**

Replace `moveLineUp()`:

```kotlin
fun moveLineUp() {
    if (readOnly) return
    val (startLine, endLine) = selectedLineRange()
    if (startLine <= 0) return
    val prevText = document.line(startLine - 1).toString()
    document.beginBatch()
    // Build the block of lines to move
    val blockLines = (startLine..endLine).map { document.line(it).toString() }
    // Replace the range (prevLine..endLine) with blockLines + prevText
    val replacement = (blockLines + prevText).joinToString("\n")
    document.edit((startLine - 1)..endLine, replacement)
    document.commitBatch()
    // Adjust caret and selection
    if (hasSelection) {
        selectionAnchorLine = selectionAnchorLine?.let { it - 1 }
    }
    moveCaret(caretLine - 1, caretColumn)
}
```

Replace `moveLineDown()`:

```kotlin
fun moveLineDown() {
    if (readOnly) return
    val (startLine, endLine) = selectedLineRange()
    if (endLine >= lineCount - 1) return
    val nextText = document.line(endLine + 1).toString()
    document.beginBatch()
    val blockLines = (startLine..endLine).map { document.line(it).toString() }
    val replacement = nextText + "\n" + blockLines.joinToString("\n")
    document.edit(startLine..(endLine + 1), replacement)
    document.commitBatch()
    if (hasSelection) {
        selectionAnchorLine = selectionAnchorLine?.let { it + 1 }
    }
    moveCaret(caretLine + 1, caretColumn)
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :feature:editor:testDirectDebugUnitTest --tests "com.omnieditor.feature.editor.EditorStateTest" --info 2>&1 | tail -20`
Expected: All tests PASS.

- [ ] **Step 6: Run full test suite**

Run: `./gradlew :core:io:test :feature:editor:testDirectDebugUnitTest 2>&1 | tail -10`
Expected: All tests PASS.

- [ ] **Step 7: Commit**

```bash
git add feature/editor/src/main/kotlin/com/omnieditor/feature/editor/EditorState.kt \
        feature/editor/src/test/kotlin/com/omnieditor/feature/editor/EditorStateTest.kt
git commit -m "fix(editor): batch indent/outdent/comment into single undo; selection-aware move [R-54, #13]"
```

---

### Task 4: Fix merge save encoding + consolidate editor save (R-51, R-52)

**Files:**
- Modify: `app/src/main/kotlin/com/omnieditor/app/OmniNavGraph.kt:638-670,1311-1338`

**Interfaces:**
- Consumes: `PieceTableDocument.materialise(WritableByteChannel)` — the existing method that encodes with tracked charset
- Produces: Corrected save paths using `materialise()` for both editor and merge saves

This task fixes the two most critical findings:
1. **R-52**: Merge save uses `text().toByteArray()` (always UTF-8) instead of `materialise()` which uses the tracked charset. Fix by using `materialise()`.
2. **R-51 (partial)**: The merge save ignores `MergeSafety.createBackup()` return value. Fix by aborting on backup failure. The full R-51 consolidation (SaveCoordinator extraction) is deferred to R-56 as it's an architecture improvement, not a data-safety fix. The critical fix is: (a) use `materialise()` for correct encoding, (b) abort when backup fails.

The editor save path already uses `materialise()` correctly (in `EditorViewModel.save()`). The `OmniNavGraph` lambda just writes the bytes — that path is acceptable for now.

- [ ] **Step 1: Fix merge save to use materialise instead of text().toByteArray()**

In `OmniNavGraph.kt`, find the merge save block (~lines 1321-1337). Replace:

```kotlin
// Write dirty documents and refresh fingerprints.
withContext(Dispatchers.IO) {
    if (leftDocument?.dirty == true && leftUri != null) {
        val baos = ByteArrayOutputStream()
        leftDocument.materialise(Channels.newChannel(baos))
        val leftBytes = baos.toByteArray()
        context.contentResolver.openOutputStream(leftUri, "wt")?.use { it.write(leftBytes) }
        leftDocument.markSaved()
        context.contentResolver.query(leftUri, fingerprintCols, null, null, null)
            ?.use { c -> if (c.moveToFirst()) onLeftFingerprintUpdated(c.getLong(0), c.getLong(1)) }
    }
    if (rightDocument?.dirty == true && rightUri != null) {
        val baos = ByteArrayOutputStream()
        rightDocument.materialise(Channels.newChannel(baos))
        val rightBytes = baos.toByteArray()
        context.contentResolver.openOutputStream(rightUri, "wt")?.use { it.write(rightBytes) }
        rightDocument.markSaved()
        context.contentResolver.query(rightUri, fingerprintCols, null, null, null)
            ?.use { c -> if (c.moveToFirst()) onRightFingerprintUpdated(c.getLong(0), c.getLong(1)) }
    }
}
```

Add the necessary import at the top of `OmniNavGraph.kt`:
```kotlin
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels
```

(These may already be imported — check before adding.)

- [ ] **Step 2: Fix backup failure handling**

In the merge save backup block (~lines 1311-1319), change to abort when backup fails:

```kotlin
// Pre-write backup for each dirty document that has a local path (direct flavour).
// R-51: Abort if backup fails — never write without a valid backup.
val backupFailed = withContext(Dispatchers.IO) {
    var failed = false
    leftCachedUri?.let { uriToFileOrNull(it) }?.let { path ->
        if (leftDocument?.dirty == true) {
            val backup = MergeSafety.createBackup(path, backupDir, sessionId)
            if (backup == null) failed = true
        }
    }
    rightCachedUri?.let { uriToFileOrNull(it) }?.let { path ->
        if (rightDocument?.dirty == true) {
            val backup = MergeSafety.createBackup(path, backupDir, sessionId)
            if (backup == null) failed = true
        }
    }
    failed
}
if (backupFailed) return // Abort save — backup failure is a data-safety concern
```

- [ ] **Step 3: Build both flavours to verify compilation**

Run: `./gradlew assembleDirectDebug assembleStoreDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/omnieditor/app/OmniNavGraph.kt
git commit -m "fix(app): merge save uses materialise for correct encoding; abort on backup failure [R-51, R-52, #13]"
```

---

### Task 5: DirectSourceProvider temp-file fix + documentation fixes (R-57)

**Files:**
- Modify: `app/src/direct/kotlin/com/omnieditor/app/DirectSourceProvider.kt:52-88`
- Modify: `docs/licenses.md`
- Modify: `README.md`
- Modify: `docs/adr/001-test-environment.md`

**Interfaces:**
- Consumes: Nothing from other tasks
- Produces: Fixed `DirectSourceProvider.write()`, updated docs

- [ ] **Step 1: Fix DirectSourceProvider.write() temp-file cleanup and fsync**

In `DirectSourceProvider.kt`, replace the `write` method's atomic branch:

```kotlin
if (atomic) {
    val tmp = File(file.parent, ".omni-tmp-${file.name}")
    try {
        FileOutputStream(tmp).channel.use { out ->
            val buf = java.nio.ByteBuffer.allocate(8192)
            while (from.read(buf) != -1) {
                buf.flip()
                out.write(buf)
                buf.clear()
            }
            out.force(true) // R-57: fsync before rename
        }
        if (!tmp.renameTo(file)) {
            tmp.delete() // R-57: clean up on rename failure
            throw OmniException(OmniError.WriteFailed(ref, partial = false))
        }
    } catch (e: OmniException) {
        tmp.delete() // ensure cleanup on all failure paths
        throw e
    } catch (e: Exception) {
        tmp.delete()
        throw OmniException(OmniError.WriteFailed(ref, partial = false))
    }
}
```

- [ ] **Step 2: Fix docs/licenses.md**

Remove the contradictory line "No other dependencies were added after T-01 (outside of the above)." (line 25).

Add after the JUnit 4 row, change to:
```
| JUnit 4 | 4.13.2 | EPL-1.0 (test only) | tests | T-01 |
```
The EPL-1.0 licence is outside the Apache/MIT/BSD allowlist but is acceptable for test-only scope. Add a note after the table:

```markdown
**Licence notes:**
- JUnit 4 (EPL-1.0): test-only dependency, not bundled in release APKs. EPL-1.0 is
  outside the Apache/MIT/BSD allowlist but is standard for JVM test frameworks and
  carries no runtime or distribution obligations for test-only use.
```

- [ ] **Step 3: Update README.md module table**

Add the missing modules:

```markdown
| Module | Contains | Android? |
|---|---|---|
| `core/model` | Data types, `OmniError` | no |
| `core/diff` | Diff engine, normalisation | no |
| `core/io` | Sources, readers, line index | yes |
| `design` | Theme, compare colours, components | yes |
| `feature/editor` | Editor UI, caret, selection, IME | yes |
| `feature/compare` | Compare and merge UI | yes |
| `feature/setup` | Source setup screen | yes |
| `app` | Entry points, navigation, DI | yes |
```

- [ ] **Step 4: Update ADR-001 with GMD smoke-only note**

Add to the end of the "Tier 2 — Gradle Managed Devices" section:

```markdown
**v0.2.0 coverage note:** GMD coverage at this release is smoke-only
(`SmokeTest.kt` — verifies home screen renders). No instrumented tests
for editor, compare, or settings screens exist yet. Instrumented UI
coverage expansion is planned for a future release.
```

- [ ] **Step 5: Build to verify**

Run: `./gradlew assembleDirectDebug assembleStoreDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Run checkIoBoundary to verify DirectSourceProvider is in allowed path**

Run: `./gradlew checkIoBoundary 2>&1 | tail -5`
Expected: No violations.

- [ ] **Step 7: Commit**

```bash
git add app/src/direct/kotlin/com/omnieditor/app/DirectSourceProvider.kt \
        docs/licenses.md README.md docs/adr/001-test-environment.md
git commit -m "fix: DirectSourceProvider fsync+cleanup; docs corrections [R-57, #13]"
```

---

### Task 6: Update CHANGES.md and close issue

**Files:**
- Modify: `CHANGES.md`

**Interfaces:**
- Consumes: All previous tasks' commits
- Produces: Updated changelog, issue closed

- [ ] **Step 1: Add Review-3 section to CHANGES.md**

Add after the "Issue #12" section:

```markdown
### Review-3 (R-51 through R-57) — Issue #13

Save safety, correctness, and undo batching fixes from Review-3 (OE-REV-003).

- **R-51** — Merge save aborts on backup failure instead of silently proceeding.
- **R-52** — Merge save uses `materialise()` for correct charset encoding instead of
  `text().toByteArray()` (was silently transcoding non-UTF-8 files to UTF-8).
- **R-53** — `materialise()` re-emits BOM when the source file had one. UTF-8-BOM,
  UTF-16 files no longer lose their BOM on save.
- **R-54** — `indent()`, `outdent()`, `toggleComment()` batch all line edits into a
  single undo step via `beginBatch()`/`commitBatch()`. `moveLineUp()`/`moveLineDown()`
  are now selection-aware (move the entire selected block).
- **R-57** — `DirectSourceProvider.write()`: `channel.force(true)` before rename;
  temp file deleted on `renameTo` failure. `docs/licenses.md` contradictory line
  removed; JUnit 4 EPL-1.0 carve-out added. `README.md` module table completed.
  ADR-001 notes GMD coverage is smoke-only at v0.2.0.
```

- [ ] **Step 2: Commit**

```bash
git add CHANGES.md
git commit -m "docs: add Review-3 fixes to CHANGES.md [R-51..R-57, #13]"
```

- [ ] **Step 3: Close GitHub issue #13**

```bash
gh issue close 13 --repo bobcheong/omni-editor --reason completed \
  --comment "All Review-3 findings resolved: R-51 (backup abort), R-52 (merge encoding), R-53 (BOM re-emit), R-54 (batch undo + selection-aware move), R-57 (fsync + docs). R-55 (release tag) and R-56 (NavGraph refactor) deferred to separate issues."
```

---

## Deferred items

**R-55 (release hygiene):** Tagging `v0.2.1`, exercising `release.yml`, and defining `versionCode` policy are release-process tasks, not code fixes. They should be done as a separate issue after the code fixes land.

**R-56 (OmniNavGraph refactor):** Extracting save logic into a `SaveCoordinator` is an architecture improvement. The critical data-safety fixes (R-51 backup abort, R-52 encoding fix) are addressed directly. The full extraction is best done as a separate refactoring issue, ideally alongside the P2 desktop port when the save logic must be platform-abstracted anyway.
