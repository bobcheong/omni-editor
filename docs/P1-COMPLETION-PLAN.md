# Omni Editor — P1 Completion Plan v3

**Authoritative.** Supersedes v1, v2 and the v2.1 amendments; delete those. Companion to `docs/OE-SPEC-001.html` and `docs/P1-BUILD-PLAN.md`.

The original build plan was written before any code existed. This one is written against the code that exists now, and closes the gap between what is implemented and what P1 requires. Task IDs are `R-nn` so they never collide with the original `T-nn`; each names the original task it re-opens. The definition of done in `CLAUDE.md` applies to every task unchanged.

---

## 1. Decisions

| # | Decision |
|---|---|
| D-1 | **Fully custom text editing surface**, built on `TextLayoutResult` queries. No `BasicTextField`, no `TextField`, no `EditText`. |
| D-2 | **Honest size ceiling.** `DocumentLimits` + `OmniError.TooLarge`. The streaming/300 MB promise is deferred, stated, and given a trigger to revisit. |
| D-3 | **Explicit save with a dirty indicator.** No auto-save. Journalling still runs for crash recovery. |
| D-4 | **`direct` is the shipping flavour.** Real paths, `MANAGE_EXTERNAL_STORAGE`, own file browser. `store`/SAF stays compiling and tested. |
| D-5 | **All of P1 implemented.** Nothing advertised-but-dead. Every control either works or is deleted. |
| D-6 | **Ship v0.1 (Compare + Viewer) before v0.2 (Editor).** |
| D-7 | **Line count is `newlines + 1`.** A file ending in a terminator has a real, caret-placeable empty final line. |

Two of these deserve their reasoning recorded, because both reverse an earlier position.

**D-7** reverses a `wc -l` convention. For a diff tool that convention is right; for an editor it is a showstopper — you cannot place a caret at the end of a file ending in a newline, and cannot append a line. It also turns out to be simpler: `"a"` (1 line) versus `"a\n"` (2 lines: `a`, ``) differ naturally in the diff, with no separate no-trailing-newline flag.

**D-1's method** is not "hand-roll Unicode". Compose's `TextMeasurer` produces a `TextLayoutResult` per line that already handles bidi, grapheme clusters, CJK advances and combining marks, and exposes `getOffsetForPosition`, `getCursorRect`, `getBoundingBox` and `getLineEnd`. Building the caret model on those queries is correct by default and is days of work rather than weeks. A monospace fast path (advance × column, skipping measurement) is a *later optimisation* — adding it first would bake a column-equals-offset assumption into the caret model, and unpicking that is the multi-week job worth fearing.

---

## 2. The two releases

### 2.1 v0.1 — "Compare + Viewer"

Merge needs `TextDocument.edit` and a working save path. It does **not** need the input surface. So the compare and merge tool is completable without Phase 3, and that is the ship line.

The viewer is a complete thing on its own terms, not a crippled editor:

- Scrollable display with line numbers, syntax highlighting, word wrap, show-whitespace, long-line truncation with expand.
- Find (not replace), go-to-line.
- Long-press a row → copy line / copy hunk / share. **No character-level selection and no caret.**
- The text tools, because those are programmatic document edits needing no input surface: sort, dedupe, trim, tabs↔spaces, line-ending conversion, encoding conversion, join/split lines. **Case conversion is excluded** — see §2.3.
- Save, dirty indicator, undo/redo, crash recovery, external-change detection: the whole of Phase 4.

A v0.1 user opens a file, transforms it, saves it, compares and merges it. They cannot type into it. That is a coherent product.

### 2.2 UI consequences, binding on R-29, R-30, R-35

- The compare menu item reads **"Open in viewer"**, not "Open in editor".
- The viewer's top bar carries a persistent, non-dismissable read-only chip: "View only — text entry arrives in 0.2". Not an error state, not a nag.
- Anything implying typing — caret, IME, selection handles, `ProgrammerKeyRow` — is **absent**, not present-and-disabled.
- Settings shows no text-input options in v0.1.

### 2.3 Destructive-tool rule

- **Case conversion is not in v0.1.** Whole-document uppercase is never what anyone wants; it is the only tool in the set with no legitimate whole-file use. It ships in v0.2 with R-36b, selection-scoped. Undo protects the session but not after save, and a confirmation on a tool that always affects 100 % of lines just trains people to tap through.
- **Any tool that would change more than half the lines shows a counted confirmation** stating exactly what changes. The count is computed by dry-running the operation first; the confirmation shows the result ("Remove 847 of 1200 lines?") and the edit is applied only on accept. That covers dedupe on a heavily duplicated file and remove-blank-lines on a sparse one, without a dialog on every trim.

---

## 3. `CLAUDE.md` amendments — make these in R-01

Replace the "never load a whole file into a String" line with:

> - The editor and both compare panes share one `TextDocument`.
> - Documents above `DocumentLimits.EDITOR_MAX_BYTES` are refused with `OmniError.TooLarge`. Within the ceiling a file may be held in memory. **No code path may be O(file) per keystroke or per rendered row** — the ceiling does not excuse that. See `docs/adr/003-size-ceiling.md`.
> - Line count is `newlines + 1`. A file ending in a terminator has a real, caret-placeable empty final line. See `docs/adr/007-line-model.md`.

Add:

> - No UI control may exist without behaviour. A menu item, button or switch that does nothing is a defect, not a placeholder.
> - No production code uses reflection to reach private state.
> - Every UI task's acceptance criteria include semantics, touch-target size and contrast. Accessibility is not a later phase.
> - State the test tier for every criterion. Never assert a test passed on a tier this environment does not have.

Add as item 7 of the definition of done:

> 7. If a task names an ADR, the ADR file exists, states the decision, the alternatives and the trigger to revisit, and is referenced in the commit. A task naming an ADR is not done without it.

---

## 4. Task list

Sizes: **S** ≤1 day · **M** 2–4 days · **L** 1–2 weeks · **XL** 2+ weeks.

### Phase 0 — Unblock · v0.1

Nothing below is verifiable until this passes. No Android module compiles today.

**R-01 · Build repair** `S` — re-opens T-01a
Apply `kotlin.android` to `app`, `design`, `feature:*` — the Compose plugin does not imply it. Move `kotlin { compilerOptions { … } }` out of `android { }`. Fix `ksp` to the `<kotlin>-<ksp>` coordinate; `2.3.11` will not resolve. Reconcile `compileSdk` (37) and `targetSdk` (35) from one catalogue value. Apply the Hilt plugin wherever `hilt-compiler` runs through KSP. Apply the §3 `CLAUDE.md` amendments.
*Done when:* `assembleDirectDebug assembleStoreDebug detekt checkCorePurity` all clean, no version-compatibility warnings.

**R-00b · Device test tier** `M` — extends T-00 · *runs in parallel with R-01…R-03*
Priority order, each level independently useful:
1. **Gradle Managed Devices** — the baseline. AGP-native, least likely to fight the toolchain, runs `createComposeRule()` instrumented tests in CI. Every criterion in this plan assumes this tier.
2. **Robolectric** — a JVM speed layer for semantics and gesture tests. If it fights AGP 9.x, **drop it and record that in ADR-001.** No criterion depends on it.
3. **Macrobenchmark** — wired but Tier 4: physical device, manual, explicitly marked unverified per `CLAUDE.md` until run.

*Done when:* one managed-device instrumented test runs in CI; ADR-001 states which tier each criterion belongs to, including any that has no available tier.
*Blocks:* Phase 3 only. Does not block R-02.

**R-02 · Navigation repair** `S` — re-opens T-23
`navigate("setup")` currently throws, because the route is `"setup?leftKey={leftKey}"` with no declared argument:
```kotlin
arguments = listOf(navArgument("leftKey") { nullable = true; defaultValue = null })
```
Move `openDocument` and the compare `scope.launch` out of composable bodies into `LaunchedEffect(key)`.
*Done when:* the fix lands immediately; the UI-test criterion (Home → New compare → back → Open file → Viewer → Compare with…) is marked pending until R-00b completes.

**R-03 · Red tests for the known defects** `S`
A failing test each for R-04, R-05, R-07, R-08, R-09 before any fix. The current suite passes over all five.

### Phase 1 — Core correctness · v0.1

**R-04 · `edit()` line-range semantics** `M` — re-opens T-12
`endOffset = lineToOffset(range.last + 1)` includes the terminator, while `insertAtCaret` supplies a replacement without one, so every mid-line insert joins two lines. Define the contract as **terminator excluded** in the `TextDocument` KDoc; update callers.
*Done when:* inserting one character at column 3 of a 5-line file yields a 5-line file; existing tests pass after their `"\n"` suffixes are removed.

**R-05 · Selection deletion tail loss** `S` — re-opens T-12
`deleteSelection()` replaces `startLine..endLine` with only the start-line prefix, destroying text after the selection end on the last line.
*Done when:* property test — delete a random multi-line selection, re-insert the selected text at the caret, document is byte-exact.

**R-06 · One line model** `M` — re-opens T-04, T-12, implements D-7
`lineCount = newlines + 1` everywhere. `LineIndex` changes to match `PieceTable`, not the reverse. Write `docs/adr/007-line-model.md`.

`Normaliser.normaliseHashes` sizes its arrays from `lineCount`, so the final empty line now participates in every comparison. That is correct under D-7, but it changes expected output:
*Done when:*
- One table-driven test (`""`, `"a"`, `"a\n"`, `"a\n\n"`, `"\n"`, `"a\r\nb"`, `"a\r\nb\r\n"`) gives identical counts from `LineIndex`, `PieceTable` and the app's splitter.
- **The golden corpus is regenerated and every `expected.json` is diffed against its previous version and reviewed by hand.** Silently regenerating turns the corpus from an oracle into an echo. `tools/generate-golden-corpus.sh` updated in the same commit.
- The `no-trailing-newline` fixture asserts `"a"` vs `"a\n"` as a one-line addition arising from the line model, not a special-case flag.
- A test asserts the **final line participates**: `"x\ny"` vs `"x\nz"` and `"x\ny\n"` vs `"x\nz\n"` both report the change.
- `RuleSet.tailSkip` re-verified — `totalLines` shifts by one, so `tailSkip = 1` on a newline-terminated file must skip the intended content line, not the new empty one.
- `MergeEngine.extractLines`' `coerceIn(0, lines.size)` bounds re-checked.
- ADR-007 exists.

**R-07 · `buildUnifiedRows` hangs the UI thread** `M` — re-opens T-17
Hunks exhausted with left consumed and right not → nothing advances → infinite loop. Reachable because `editsToHunks` drops blank-only edits under `ignoreBlankLines`, desynchronising the row walk. Add the trailing-right drain, a no-progress guard, and fix the cause: a suppressed edit must stay accounted for in line coverage.
*Done when:* a fuzz run over 10 000 random pairs × random rule sets builds rows under timeout, with rows always reconciling against input line counts.

**R-08 · Diff engine uses the index hashes** `M` — re-opens T-07
`leftHash`/`rightHash` are declared and never read; the engine re-hashes every line as a `String`, and `hashString()` hashes a UTF-8 re-encode so it does not match `LineIndex.hashLine()` for non-UTF-8 sources. Use the supplied hashes on the default rule set; take the text path only when a rule changes bytes; never mix hash spaces.
*Done when:* comparing two identical 100k-line UTF-8 files makes zero per-line string allocations in the default rule set.

**R-09 · CRLF at a chunk boundary** `S` — re-opens T-04
`i + 1 < chunkBytes.size` misreads a trailing `\r` as a lone CR. Add a one-byte lookback. Also stop copying each 64 MB mapping to heap — it defeats the mmap — and stop issuing one `channel.read()` per line to hash.
*Done when:* a fixture whose CRLF lands on an injectable chunk boundary indexes identically to the small-file path.

**R-10 · Streaming compare: defer and record** `S` — re-opens T-07
`compareStreaming` computes everything then emits, so T-07's "first hunk before completion" was never met. **Defer streaming** — D-2's ceiling makes it unnecessary, since a full in-ceiling compare completes fast enough that time-to-first-hunk stops being a distinct budget. The cascade is bounded but must be done explicitly.
*Done when:* `docs/adr/005-streaming-deferred.md` exists and **preserves the deleted function's signature verbatim** so it can be resurrected deliberately; `compareStreaming` is **removed from `core/diff`'s public API, not deprecated** (deprecation preserves the trap and gets suppressed; there is one in-repo consumer, so there is no compatibility argument); OE-ENG-7 amended; spec §11's first-hunk latency budget replaced by a full-compare budget for the largest in-ceiling pair; no comment anywhere claims streaming.

### Phase 2 — Ceiling and document structure · v0.1

**R-11 · `DocumentLimits` and ADR-003** `S`
Single source of truth. Starting values, to be confirmed or lowered by R-38: `EDITOR_MAX_BYTES = 16 MiB`, `COMPARE_MAX_BYTES_PER_SIDE = 8 MiB`, `WARN_FRACTION = 0.5`, `MAX_LINE_BYTES = 1 MiB`. ADR-003 records what was promised, what ships, what would lift it (piece-tree reads via `LineIndex`, block-mode compare) and the trigger to revisit. Amend spec §11.
*Done when:* no size threshold exists outside `DocumentLimits`; ADR-003 exists.

**R-12 · Over-threshold behaviour** `M` — re-opens T-14
Size check in `SourceProvider.resolve` before any read → `OmniError.TooLarge` → named §13 UI state with an escape hatch: read-only preview of the first *n* lines, editing and merge disabled, persistent banner. Same per side on the compare path.
*Done when:* a 64 MB file reaches the state in under 300 ms with no read of the body; preview cannot be edited or saved; compare names which side exceeded.

**R-13 · Piece tree** `L` — re-opens T-12
Replace the flat piece list with an augmented balanced tree carrying subtree `charCount` and `newlineCount`, giving line→offset, offset→line and edit in O(log p). Add **coalescing** so an insert contiguous with the additions tail extends the previous piece rather than splitting — this keeps `p` near-constant during sequential typing.

Remove `table.text()` from `lineToOffset` (a full document copy **per edit**) and from `line(i)` (O(n) **per rendered row**, making scrolling O(n²)). Cache `lineCount`, currently a `get()` walking every character from composition. Resolve `PieceTableDocument.index`, which throws `UnsupportedOperationException` from an interface member. Move `Journal.append` off the caller's thread onto a held handle with batched flush — it currently opens, appends and closes per edit.

*Done when:* 10 000 inserts at the midpoint of a 15 MB file with no operation over 2 ms; rendering row 400 000 costs the same as row 10; piece count after 10 000 sequential keystrokes stays within 2× of its starting value.

### Phase 4 — Save, persistence, identity · v0.1

Runs before Phase 3. Merge needs the write path, not the input surface.

**R-20 · Save actually writes** `L` — re-opens T-14, T-22
`viewModel.save { }` is an empty callback; nothing is ever written, and `SourceProvider.write()` has no caller. Route saves through it. Stream `materialise` piece by piece through an encoder instead of building a full `String` then a full `ByteArray`. Pre-write backup via `MergeSafety`. Encoding and line ending from the document, not hardcoded UTF-8/LF. Validate the charset resolves on-device before offering it — `UTF-32LE`/`UTF-32BE` may not — → `OmniError.DecodeFailed` with a picker, never a crash. Save As.

**Atomic write, specified.** `Capabilities.canAtomicRename` already exists in the model and drives this:
- **direct:** write `.omni-tmp-<name>` in the *same directory*, `FileChannel.force(true)`, then `renameTo` — atomic within a filesystem on ext4/f2fs. Check the boolean return; it fails silently otherwise. Handle a read-only parent directory.
- **store/SAF:** atomic rename is **not available**. `openOutputStream(uri, "wt")` truncates first, so a crash mid-write loses the file. Strategy: full backup to app-private storage, write a journal marker, truncate-and-write, clear the marker. A marker surviving to next launch offers restore.
- The UI states which guarantee it has. Never claim atomicity on SAF.

**Storage pre-flight, before the original is touched on either flavour:**
1. Required = document size × 2 + 10 % margin.
2. Check `StorageManager.getAllocatableBytes()`; if short, call `allocateBytes()` once — it clears reclaimable cache and may succeed.
3. Still short → `OmniError.WriteFailed(ref, partial = false)` with a named state saying how much is needed. **The original is untouched and nothing is lost.**
4. The backup or temp is verified complete — length and content hash — before the original is truncated or renamed over. A backup that failed silently is worse than none.

*Done when:* edit → save → reopen shows the edit; a CRLF windows-1252 file round-trips byte-exactly with no edits; killing the process mid-write leaves either the original or the complete new file on `direct`, and offers restore on `store`; a save attempted with the volume artificially filled leaves the original byte-identical and surfaces the named state on both flavours.

**R-21 · Dirty state and unsaved-changes guard** `M` — re-opens T-14, implements D-3
`dirty` currently means "undo stack non-empty", so undoing back to the original still reports dirty — compare a content fingerprint. Indicator in title and per tab. `BackHandler` + `enableOnBackInvokedCallback` with Save / Discard / Cancel on every exit path: back, up, tab close, replacing a slot, backgrounding. Crash-recovery banner on next open — an S-04 state with no implementation today.
*Done when:* every exit path covered; force-stop mid-edit then reopen offers recovery with the exact unsaved content.

**R-22 · External change detection** `M` — re-opens T-22
`Fingerprint(size, modifiedAt, edgeHash)` is in the model and unused. Capture on open, re-check on resume and before write. Changed underneath → banner with Reload / Keep mine / Compare with disk. Never a silent overwrite.

**R-23a · `SourceProvider` becomes the read path** `L` — re-opens T-05, implements D-4
Permission rationale → `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` → result handling → denied state that still allows SAF fallback. `SourceRef.path` populated so recents are durable. Add `takePersistableUriPermission` to the store flavour so its recents work at all. `ContentCache` remains in place for now — see R-23b.

**Minimum viable file browser:** single-directory flat list, path breadcrumb with up-navigation, sort by name/size/date, tap to pick into the active slot. **Deferred to P2 with `docs/adr/009-file-browser-scope.md`:** tree navigation, favourites, recent folders, hidden-file toggle, multi-select — the setup screen's left/right slots make multi-select unnecessary for the two-file case.

*Done when:* `SourceProviderContractTest` passes against both implementations on a managed device; a session reference survives restart in both; revoked access surfaces `AccessRevoked`; ADR-009 exists.

**R-34a · Identity model and store schema** `M` — re-opens T-24, T-11
- `SourceRef.id` authoritative everywhere, replacing the current dual scheme (`ContentCache` key on one path, random UUID on another).
- **Navigation routes carry `SourceRef.id`**, not `ContentCache` keys. This is the step that makes R-23b safe.
- `SessionStore` and `ResultStore` wired at the persistence layer, keyed from it. Both are implemented and unused.
- **`schemaVersion` on every persisted JSON**, readers discarding unknown or older versions gracefully. No migration code — there are no users and `versionCode` is 1 — but shipping without the field is what forces a painful migration later.

*Done when:* one identity scheme exists; a hand-corrupted or version-bumped store file degrades to empty rather than crashing; a compare killed at 80 % and reopened restores from `ResultStore` without recomputing.

**R-23b · Delete `ContentCache`** `S`
Now unreferenced, it goes. Its current behaviour — whole file to `String` on the main thread inside an `ActivityResult` callback, with read errors stuffed into the document text so a user can save an error message over their own file — must not survive. Add a check alongside `checkCorePurity` forbidding `ContentResolver` and `java.io.File` outside `core/io` and the flavour source sets.
*Done when:* the check fails correctly when a violation is introduced; no navigation path references a content key.

**R-17a · Document edit API and operation-level undo** `M` — re-opens T-12, T-16
- `TextDocument.replaceAll(range, text)` as a single journalled edit.
- Undo grouping **by operation**: one text tool, one replace-all, one merged hunk, one accept-all = exactly one step.
- **Delete `EditorViewModel.getPieceTable()` and every reflection call.** They bypass undo and, because release builds enable R8, silently no-op in production.

*Done when:* the T-12 property test passes with text tools, replace-all and merge actions in the random operation set; `assembleDirectRelease` verified under R8 to still apply text tools.

### Phase 5 — Compare and viewer · v0.1

**R-24 · Rule set UI** `L` — re-opens T-06 — *the largest functional gap in the app*
`Normaliser` implements case, whitespace, blank lines, line endings, begins/contains/ends-with, between-markers, head/tail skip and column ranges. None is reachable; `RuleSet.DEFAULT` is hardcoded at the one call site. Bottom sheet with toggles and editors for every field. Live re-run with progress and cancel, results cached per rule set. Named rule sets persisted, referenced by the existing `Session.ruleSetId`. An always-visible "3 rules active" chip — a user must never wonder why two visibly different lines are shown as equal.
*a11y:* every control labelled and keyboard-reachable; the chip announced.
*Done when:* every `RuleSet` field is reachable and persisted; changing a rule re-runs and updates the hunk count; the active rule set appears in exported reports.

**R-25 · Alignment model** `M` — re-opens T-18
Panes are scroll-synced **by item index**, so they drift at the first insertion and connectors join unpaired rows. Build one `List<AlignedRow>` of `(left: Long?, right: Long?, hunkIndex: Int?)` with spacers; render both panes and the unified view from it, so there is one row builder rather than two — see R-07.
*Done when:* a 40-line insertion at the top keeps matched lines on the same visual row at every scroll position; rotation and fold preserve scroll and selection.

**R-18a · Rendering correctness** `M` — re-opens T-13 · *must precede R-19*
- Fix the stale-render key: `remember(index, state.document.dirty)` never invalidates after the first edit because `dirty` is a `Boolean`. Key on a monotonically increasing **edit generation** from the `changes` flow. Merge writes to documents, so this bites in v0.1.
- Fix the scroll feedback loop: two `LaunchedEffect`s write each other's state and fight user flings. One direction is authoritative.
- Word wrap for the viewer, one gutter number per logical line. Show-whitespace rendering. Truncate lines over `MAX_LINE_BYTES` with expand.

*Done when:* a merge applied to a document updates every affected visible row; a 500k-line file scrolls with no frame over 16 ms *(Tier 4, manual)*; the 400 KB minified fixture opens without jank.

**R-19 · Syntax highlighting with carried state** `M` — re-opens T-20
`tokenizeLine` is stateless per line, so block comments and multi-line strings highlight wrongly from the second line on. Carry a lexer entry-state per line in an `IntArray`; an edit at line *k* invalidates *k*..end, recomputed lazily for visible rows and stopping early when a recomputed state matches the cached one. **Consume R-18a's edit generation** as the invalidation signal — building this before R-18a means inventing one, almost certainly the broken `dirty` Boolean, and rewriting it later.
Move token colours into the design module with light, dark and high-contrast variants; they are currently hardcoded light-theme values (`0xFF0033B3`) rendered on dark backgrounds. `SyntaxColors` is rebuilt every recomposition — hoist it.
*Done when:* a fixture with a 200-line block comment highlights correctly at every scroll position; highlighting never delays first paint; dark mode passes R-37's contrast check.

**R-26 · Intra-line highlighting** `M` — re-opens T-08
`IntraLineDiff` is tested and referenced nowhere; on a `CHANGED` hunk the user cannot see which characters changed. Compute lazily per visible row, cache per row, respect `Granularity`.
*Verification split:* the range-correctness criterion runs on the JVM against golden pairs. The "under 1 ms per visible row in situ" criterion moves to **R-38**.

**R-27 · Merge that merges** `L` — re-opens T-21
Today the merge button builds a `MergeAction` and shows a snackbar describing what it would have done. Apply actions to the target `TextDocument`. Direction per hunk, not hardcoded left-to-right. Line-level and selection-level merge (`MergeEngine.mergeLines` exists, uncalled); word-level per OE-MRG-4. Per-side dirty state and save. Move `MergeEngine` off `List<String>` onto document ranges so merge and edit share one undo stack.

**Accept-all applies as one batched edit, not one per hunk.** Build the complete target content once and issue a single `replaceAll` behind a counted confirmation. One undo step falls out naturally, matching R-17a, and it removes the merge path's sensitivity to per-edit cost — which is what makes R-13's fallback survivable.

*Done when:* T-21's property test passes **through the UI layer** — all merges in one direction make the files identical under the active rule set, and undo restores byte-exact state at every step.

**R-28 · Merge safety wired** `M` — re-opens T-22
`MergeSafety` is imported by nothing. Pre-write backup, restore-original, dirty prompts, external-change detection sharing R-22's fingerprint.
*Done when:* a backup exists before the first byte of any merge save; restore-original returns both sides to their opened state.

**R-29 · Active-line sheet reachable** `S` — re-opens T-19
`showActiveLineSheet` is never set true; the component is unreachable. Tap a diff row → both versions, intra-line highlighting, merge this line ← / →, copy either side. This is also the 3-way resolution surface (R-32).

**R-30 · The four dead menu items** `M` — re-opens T-25, T-18
**Open in viewer** (same document instance, so merges reflect back), **flip sides** (swap and re-run, preserving scroll to the same content), **re-run compare** (re-read both sides, picking up external changes), **export report** (wire `ReportGenerator`: HTML unified and side-by-side, unified-diff patch, plain-text summary, PDF via the print pipeline; header records sources, timestamp, rules and engine mode).
*Done when:* a generated patch applies cleanly with `git apply`; no menu item in the app is a no-op.

**R-31 · Progress, cancel, empty states, find** `M` — re-opens T-07, T-19
Determinate progress from the engine's existing `Progress` emissions, with cancel. "Files are identical" as a real state, naming the rules that made them so. Filter modes recompute without re-running the compare — `buildUnifiedRows()` currently rebuilds everything on every recomposition. Measure the minimap viewport instead of hardcoding 30 lines. Find within compare (OE-FND-1).
*Depends on:* R-34a for the ResultStore key.

**R-32 · 3-way conflict resolution** `M` — re-opens T-09, T-21
`Diff3` and `BlockDiff` are tested and unreachable. **In P1:** conflicts surfaced, `HunkType.CONFLICT` rows visually distinct, resolution through the R-29 sheet (take left / take base / take right / edit manually), conflict counter and next-conflict navigation. **Deferred to P2 with `docs/adr/008-three-pane-deferred.md`:** the three-pane wide-screen layout. The sheet already works at any width, so this is the cheap half kept and the expensive half deferred.
*Done when:* the T-09 fixture set drives the UI; a scripted 3-way merge with both-changed-identically and adjacent-change cases resolves correctly at any width; ADR-008 exists.

### Phase 6 — Shell and tools · v0.1

**R-33 · Entry points** `M` — re-opens T-23
`IntentRouter` is fully implemented and tested; `MainActivity` computes an action, passes it to `OmniNavGraph`, **which never reads the parameter**; and the manifest declares no intent filters at all. Consume `initialAction`. Add `ACTION_VIEW` (text/* plus extension filters), `ACTION_SEND`, `ACTION_SEND_MULTIPLE`, `ACTION_EDIT`. App shortcuts. Fix `handleSend` checking `EXTRA_TEXT` before `EXTRA_STREAM`, which makes file shares from apps supplying both open as snippets. Replace deprecated `getParcelableExtra`. Snippet and URL sources — both P1, neither implemented.
*Done when:* from a cold start, two shared files open a compare, one prompts for the second, shared text opens snippet compare, and "open with" works from a file manager.

**R-34b · Home, sessions, tabs** `M` — re-opens T-24
Three tabs per S-01; session naming, grouping, pinning, search; last summary shown without re-running; `TabStrip` actually populated — it exists and is always passed an empty list; memory-pressure eviction preserving unsaved edits.
*Done when:* ten concurrent sessions survive a low-memory kill with unsaved edits intact.

**R-35 · Settings that persist** `M` — re-opens T-26
Every switch is a local `remember`, reset on back, connected to nothing; `datastore-preferences` is in the catalogue and unused. DataStore repository; every setting applied. Note `OmniTheme(dynamicColor = true)` with `minSdk 31` means `LightScheme`/`DarkScheme` are dead on every real device — honour the setting or delete them. No text-input settings in v0.1 per §2.2.

**R-36a · Programmatic text tools** `M` — re-opens T-16
Line-ending conversion, encoding conversion, join/split lines, go-to-line (**implemented in the ViewModel today with no entry point**), plus the existing sort, dedupe, trim, tabs↔spaces, reverse lines and remove blank lines — all routed through R-17a. Whole-document in v0.1, since no selection exists. Case conversion excluded per §2.3. Counted confirmation for any tool changing more than half the lines.
*Done when:* encoding conversion round-trips; every tool is one undo step; go-to-line is reachable and keyboard-accessible.

**── SHIP v0.1 ──**

### Phase 3 — Custom editing surface · v0.2

Gated on R-00b; nothing here is verifiable without a device tier.

**R-14 · Layout, measurement, position mapping** `L` — re-opens T-13
Per-line `TextLayoutResult` from `TextMeasurer`, cached for visible rows. Offset↔position via `getOffsetForPosition` / `getCursorRect` / `getBoundingBox` — correct for bidi, grapheme clusters, CJK and combining marks **by construction**.
**Tabs are the real complication:** Compose has no tab stops. Expand tabs for layout and maintain a display-column ↔ character-offset map per line, driven by `DisplaySettings.tabWidth`.
**No monospace fast path yet** — it is R-38's decision.
*Done when:* offset↔position round-trips for every position across ASCII, CJK, RTL, ZWJ-emoji and tab-heavy fixtures; the caret never lands inside a grapheme cluster.

**R-15 · Caret, selection, gestures, semantics** `L` — re-opens T-13
Caret drawn and blinking, honouring reduced-motion. Tap to place; drag to select; long-press and double-tap select word; triple-tap selects line. Draggable handles ≥48dp. Floating toolbar: cut / copy / paste / select all / share. Auto-scroll at edges. Column selection — **decide before building**, per §6.2; retrofitting block selection into a linear model is expensive.
**Semantics belong here, not R-37:** a custom surface gets none for free. `SemanticsProperties.EditableText`, `TextSelectionRange`, `SetSelection`, `PasteText` and caret-movement actions, or TalkBack sees an empty view.
*Done when:* each gesture asserted; handles grabbable at 200 % font scale; TalkBack reads content and announces selection changes; scroll and selection survive rotation and fold.

**R-16 · IME connection** `XL` — re-opens T-13, T-14 — *the riskiest task in the plan*
Own `InputConnection` / `PlatformTextInputSession`: composing regions for CJK and predictive input, `setComposingText`, `commitText`, `deleteSurroundingText`, batch edits, `EditorInfo` with `IME_FLAG_NO_FULLSCREEN`, hardware keyboard including Ctrl chords. `imePadding` so the caret is never under the keyboard. Autocorrect and suggestions off by default for code, switchable.
*Done when:* Gboard, a CJK IME and a hardware keyboard each produce correct text under a scripted instrumented run; composing text is visible and cancellable; no ANR typing at 20 cps on a document at the ceiling.

**R-17b · Typing-level undo coalescing** `S`
Consecutive keystrokes within one line and a time window collapse to one step; the window breaks on caret jump, on save, and on a non-typing operation.
*Done when:* typing a 40-character word then undoing once removes the whole word; typing, moving the caret, typing again undoes in two steps.

**R-18b · Caret navigation across wrapped rows** `S`
Up/down/home/end across visual rows of a wrapped logical line; toggling wrap preserves the caret's logical position.

**R-36b · Selection-scoped tools, case conversion, bookmarks, column select** `M`
Tools operate on selection when present. Case conversion arrives here (§2.3). Bookmarks — `Bookmark` is in the model, unused. Column select.

### Phase 7 — Hardening and release

**R-37 · Accessibility audit and keyboard** `M` — re-opens T-27
An audit, not the first pass. Net-new: `KeyboardShortcuts` wiring — in the model, unwired — with a discoverable shortcuts sheet and remapping; reduced-motion across caret blink, scroll and sheets; extraction of hardcoded Compose strings to `strings.xml`, of which 5 are extracted today; `AccessibilityConfig` and `ContrastChecker` wired; **gutter glyphs `+ − ~` alongside colour**, since the spec's "colour is never the only signal" promise is currently not kept.
*Done when:* every function reachable without gestures; automated a11y checks clean; a manual TalkBack script passes on viewer, editor and compare; usable at 200 % font scale with no clipped text.

**R-38 · Performance and memory verification** `M` — re-opens T-28
Macrobenchmarks: cold start, 5 MB compare, 500k-line scroll, typing latency at the ceiling, peak heap at the ceiling, intra-line ranges per visible row (R-26's deferred criterion). **This confirms or lowers `DocumentLimits`** — if peak heap at 16 MiB misses budget, lower the constant and update ADR-003; do not raise the budget. Decide R-14's monospace fast path here.

**R-39 · Dead code and honesty sweep** `S`
Unreferenced outside tests today: `IntraLineDiff`, `BlockDiff`, `Diff3`, `MergeSafety`, `ReportGenerator`, `SessionStore`, `ResultStore`, `FileIndexer`, `LineIndex`, `AccessibilityConfig`, `ContrastChecker`, `KeyboardShortcuts`, `ThemeDefinition`. Re-run the sweep; anything still unreferenced is wired or deleted with `docs/adr/010-deferred-modules.md`. Add a CI check failing on additions to the allowlist. Audit every empty lambda and every KDoc claim.

**R-40 · Release** `M` — re-opens T-29
Keystore generated once and stored outside the repo, signed `direct` APK, complete `licenses.md`, in-app licences screen, versioning scheme, install-over-previous preserving sessions and documents.

**R-41 · On-device diagnostics** `S`
Direct install means no Play Console vitals — you get nothing unless you build it. Zero dependencies, no network, which also keeps the independence and licence constraints clean: an `UncaughtExceptionHandler` writing a bounded ring of crash logs to app-private storage, a main-thread watchdog for ANR detection, and a "share diagnostic report" action in settings letting the user send a redacted log — stack, device, build; no file contents or paths — by their own choice. No automatic upload.
*Done when:* a forced crash produces a retrievable log; the shared report contains stack trace, device and build info but no file paths or content; the ring buffer evicts the oldest entry when full; the watchdog detects a 5-second main-thread block and logs it without crashing.

---

## 5. Execution order

```
v0.1 "Compare + Viewer"
  R-01 → R-02 → R-03                                   unblock
  (R-00b runs alongside Phases 0–6; hard-gates Phase 3)
  R-04 → R-05 → R-06 → R-07 → R-08 → R-09 → R-10       core correctness
  R-11 → R-12 → R-13                                   ceiling + piece tree
  R-20 → R-21 → R-22 → R-23a → R-34a → R-23b → R-17a   save, sources, identity, edit API
  R-24 → R-25 → R-18a → R-19 → R-26 → R-27 → R-28
       → R-29 → R-30 → R-31 → R-32                     compare + viewer
  R-33 → R-34b → R-35 → R-36a                          shell + tools
  ────────────────────── SHIP v0.1 ──────────────────────
v0.2 "Editor"
  R-00b (hard gate) → R-14 → R-15 → R-16 → R-17b → R-18b → R-36b
  R-37 → R-38 → R-39 → R-40 → R-41                     hardening + release
```

**Ordering constraints that are not obvious from the list:**
- R-18a **before** R-19 — R-19's lazy re-lex consumes R-18a's edit generation counter.
- R-34a **before** R-23b — `ContentCache` is how content moves between screens today; deleting it before nav routes carry `SourceRef.id` breaks navigation, not just recents.
- R-34a **before** R-31 — R-31's ResultStore restore cannot be verified while identity is unstable.
- R-17a **before** R-27 — merge needs the single-undo-step guarantee.
- R-06 **before** R-08 — the hash path assumes a settled line model.

**Parallelisable:** R-24 and R-25/R-26 are independent of Phase 3 once R-04…R-13 land. R-33 and R-34b are independent of both. R-34a is not — it is a Phase 4 prerequisite.

**Human review checkpoints.** Stop; do not run through.
- **After R-13.** The performance model and document structure are set; everything builds on them.
- **After R-17a.** First point at which the app modifies a document through a path a user can trigger.
- **After R-20.** The app can now overwrite your files. Review the write path, backup, pre-flight and rename by hand, on both flavours.
- **After R-28.** Everything destructive exists. Review merge safety before using the app on files that matter.
- **After R-16.** IME correctness is not something a test suite fully covers. Type in three languages for a while.

---

## 6. Cutting scope

### 6.1 Sizes by phase

| Phase | Release | Size | Dominated by |
|---|---|---|---|
| 0 Unblock | v0.1 | S×3 + M | R-00b |
| 1 Core correctness | v0.1 | M×5 + S×2 | evenly spread; each has a red test first |
| 2 Ceiling + structure | v0.1 | L + M + S | R-13 |
| 4 Save, sources, identity | v0.1 | L×2 + M×4 + S | R-20, R-23a |
| 5 Compare + viewer | v0.1 | L×3 + M×6 + S | R-24, R-27 |
| 6 Shell + tools | v0.1 | M×4 | evenly spread |
| 3 Editing surface | v0.2 | XL + L×2 + M + S×2 | R-16 |
| 7 Hardening | both | M×3 + S×2 | R-37, R-38 |

### 6.2 Ordered cut list

Drop in this order under time pressure. Each becomes an ADR, not a silent omission.

1. **R-32's conflict UI** → 3-way becomes engine-only, exposed via report export. *Only acceptable if the app refuses to open a 3-way session rather than showing conflicts it cannot resolve.*
2. **R-30's PDF and side-by-side HTML export** → keep the unified-diff patch and plain-text summary, the ones people actually use.
3. **R-24's advanced rules** (between-markers, column ranges, head/tail skip) → keep case, whitespace, blank lines, line endings, line patterns. The chip must then name only the rules that exist.
4. **R-36b's bookmarks and column select** → but **R-15's selection model must be designed to accommodate column select even if R-36b is cut** — retrofitting a block-selection model into a linear one is the risk, not the feature itself. That design decision is part of R-15 regardless of this cut.
5. **R-19's carried lexer state** → highlighting degrades to per-line with a limitation documented in-app.
6. **R-18a's word wrap** → the setting is then removed from Settings, not left inert.

**Never cut:** R-04, R-05, R-07 (data loss and hangs), R-20's backup, pre-flight and atomicity, R-28 (merge safety), R-21 (unsaved-changes guard), R-12 (over-threshold refusal). These are where failure destroys a user's file.

### 6.3 Risk register

| Risk | Signal | Response |
|---|---|---|
| R-16 IME is the hardest single piece | CJK or predictive input keeps breaking past a week | Fall back to a windowed `BasicTextField` over a document slice; ADR-006 reverses D-1. The ceiling makes this viable, and v0.1 has already shipped, so it is a delay not a failure. |
| R-13 piece tree is subtle | Property tests keep finding edge cases past the L timebox | Fall back to the **existing flat piece list plus a lazily rebuilt line-start offset array**, keeping coalescing to bound piece count. Accepts O(p) per edit, keeps line→offset O(1) for rendering — the criterion that matters. **Not a gap buffer:** it is O(n) per non-local edit and merge is non-local by nature. Record in ADR-006. |
| Ceiling proves too low | R-38 forces `EDITOR_MAX_BYTES` under 8 MiB | Lower it honestly; open a P2 task for the `LineIndex` read path. A working 8 MiB editor beats a broken unlimited one. |
| Golden corpus regenerated carelessly in R-06 | `expected.json` changes accepted without review | The corpus is the oracle. Every changed file is diffed and reviewed by hand, or R-06 is not done. |
| Fixing dead code reveals more | R-39 finds a third layer | Expected. Allowlist with reasons rather than wiring something half-designed. |
| Flavours diverge once `direct` is primary | `store` tests start being skipped | CI fails on a skipped flavour, not warns. |
| v0.1 slips into v0.2 territory | Editor input tasks appear in Phase 5 | The ship line in §5 is the boundary. A viewer is a legitimate v0.1. |

---

## Appendix — change history

Kept so the reasoning survives; delete once v0.1 ships.

- **v1 → v2.** Reordered so compare ships before the editing surface (there was no partial-credit path). Reversed the line-count convention to editor semantics. Replaced the flat piece cache with a tree. Inverted the Unicode phasing to build on `TextLayoutResult` rather than layering correctness onto a monospace assumption. Scoped 3-way to keep resolution and defer the three-pane layout. Moved accessibility into every UI task. Added the device test tier, sizing and the cut list.
- **v2 → v3.** Defined v0.1 as "Compare + Viewer" and moved R-19, R-18a and R-36a into it. Split R-17, R-18, R-23, R-34 and R-36 along the release line. Killed the gap-buffer fallback and batched accept-all into one edit. Added storage pre-flight before destructive writes. Added the golden-corpus cascade to R-06. Decoupled R-00b from R-02. Made ADRs acceptance criteria. Deleted `compareStreaming` rather than deprecating it. Excluded whole-document case conversion. Ordered R-18a before R-19, and R-34a before R-23b.
