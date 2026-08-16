# Omni Editor — Change Log

## P1 Completion Plan (R-01 onwards)

Tracks implementation of `docs/P1-COMPLETION-PLAN.md` (v3). The original build plan
(T-00 through T-29) established the codebase; this plan closes the gap between what
was built and what P1 requires.

### Phase 0 — Unblock (complete)

- **R-01** `83b8553` — Build repair: KSP 2.3.10, compileSdk/targetSdk from catalogue,
  Hilt plugin on feature modules, detekt violations fixed, CLAUDE.md amendments applied.
  AGP 9.x rejects `kotlin-android`; `compilerOptions` stays inside `android {}`.
- **R-02** `0fc626d` — Navigation repair: `navArgument` on setup route, `LaunchedEffect`
  for side effects in composable bodies.
- **R-03** `4d8f3e6` — Red tests for five known defects (R-04, R-05, R-07, R-08, R-09).

### Phase 1 — Core correctness (complete)

- **R-04** `f1f5740` — `edit()` line-range semantics: terminator excluded from replaced range.
- **R-05** `7e2f246` — Selection deletion: preserve tail of last line after selection end.
- **R-06** `04a6d0a` — One line model: `lineCount = newlines + 1` everywhere. `LineIndex`
  changed to match `PieceTable`. Golden corpus regenerated. ADR-007.
- **R-07** `d4a8990`..`e30e7ae` — `buildUnifiedRows` infinite loop: drain trailing lines,
  no-progress guard, correct row types, 10k-pair fuzz with reconciliation.
- **R-08** `1972b71`..`758b73a` — Diff engine uses supplied hash functions when rules are
  DEFAULT. Combined guard prevents hash-space mixing on asymmetric supply.
- **R-09** `6c9324c`..`bad958b` — CRLF at chunk boundary: `pendingCR` flag for cross-chunk
  lookback, injectable chunk size, heap copy of mmap buffer removed.
- **R-10** `9e8fdb3` — Streaming compare deferred: `compareStreaming` deleted (not deprecated).
  ADR-005 preserves the function signature for future resurrection.

### Phase 2 — Ceiling and document structure (complete)

- **R-11** `b387c37` — `DocumentLimits`: 16 MiB editor, 8 MiB compare, 1 MiB line. ADR-003.
- **R-12** `b4df786` — Over-threshold behaviour: size check before content read,
  `OverThreshold` UI state. Read-only preview escape hatch deferred.
- **R-13** `f9a60fe`..`5f09891` — Piece tree: augmented AVL with `charCount`/`newlineCount`
  per node. O(log p) insert, delete, line access, `lineToOffset`. Coalescing bounds piece
  count during sequential typing. `PieceTableDocument` no longer calls `table.text()` per
  edit. Journal holds file handle open with batched flush. `PieceTableDocument` implements
  `Closeable`.

### Phase 4 — Save, persistence, identity (complete)

- **R-20** `5912f5d` — Save writes: materialise through ContentResolver, save function
  injected from NavGraph to keep `feature:editor` free of Android dependencies.
- **R-21** `b82fab1` — Dirty state: undo-stack-depth tracking, `markSaved()`, `BackHandler`
  with Save/Discard/Cancel dialog, dirty indicator in title.
- **R-22** `eca54f6` — External change detection: size+modifiedAt fingerprint on open,
  re-check on resume and before save, reload banner with Keep mine / Reload.
- **R-23a** `9d925a3` — Direct flavour: `MANAGE_EXTERNAL_STORAGE` permission rationale
  screen, minimum viable file browser (flat list, breadcrumb, sort), `takePersistableUriPermission`
  for store flavour. ADR-009.
- **R-34a** `cb10574` — Identity model: `SourceRef.id` authoritative everywhere,
  `SessionStore`/`ResultStore` wired, `schemaVersion` on all persisted JSON, graceful
  degradation on corrupt or unknown versions.
- **R-23b** `7addd18` — `ContentCache` deleted, replaced by `DocumentRegistry`. `checkIoBoundary`
  task enforces no `ContentResolver`/`java.io.File` outside `core/io` and flavour source sets.
- **R-17a** `7d378f2` — Document edit API: `replaceAll(offset, length, replacement)` as single
  undo step. `EditorViewModel.getPieceTable()` and all reflection calls deleted. Property
  test rewritten to use public API.

### Phase 5 — Compare and viewer (complete)

- **R-24** `21c326a` — Rule set UI: bottom sheet with all RuleSet fields, live re-run with
  progress and cancel, active-rules chip showing count of non-default rules.
- **R-25** `d45520f` — Alignment model: single `List<AlignedRow>` drives both unified and
  split views. Sync holds by construction via spacer rows.
- **R-18a** `8a594d4` — Rendering correctness: monotonic `editGeneration` counter replaces
  broken `dirty` Boolean as recomposition key. Scroll feedback loop fixed. Word wrap, show-
  whitespace, long-line truncation with expand.
- **R-19** `8cb7ceb` — Syntax highlighting with carried lexer state: `IntArray` entry-state
  per line, lazy re-lex for visible rows, early termination when recomputed state matches
  cached. Token colours moved to design module with light/dark/high-contrast variants.
- **R-26** `2cc09ee` — Intra-line highlighting: `IntraLineDiff` wired into both views.
  Changed characters highlighted with background spans. Cached per row. Respects Granularity.
- **R-27** `606d986` — Merge applies changes: `replaceAll` per hunk (one undo step). Direction
  per hunk (← →). Accept-all as single batched edit with counted confirmation.
- **R-28** `b5c3f5e` — Merge safety wired: pre-write backup via `MergeSafety`, restore-
  originals via full undo, dirty prompt on compare exit, external change check before save.
- **R-29** `f196b75` — Active-line sheet: tap diff row to open. Both versions with intra-line
  highlighting, merge ← / → buttons, copy either side.
- **R-30** `1b43fce` — Four dead menu items wired: open in viewer (same document instance),
  flip sides (swap and re-run), re-run compare (picks up external changes), export report
  (unified-diff patch + plain-text via `ReportGenerator`, shared via `ACTION_SEND`).
- **R-31** `e9e085e` — Progress bar with cancel, "Files are identical" state naming active
  rules, cached aligned rows, measured minimap viewport, find within compare (OE-FND-1).
- **R-32** `cbd3863` — 3-way conflict resolution: `CONFLICT` rows visually distinct (purple +
  `!` glyph), resolution through active-line sheet (take left / take base / take right),
  conflict counter and next/prev navigation. Three-pane layout deferred (ADR-008).

### Phase 6 — Shell and tools (complete)

- **R-33** `02c15a8` — Entry points: `initialAction` consumed in NavGraph, intent filters
  (`ACTION_VIEW`, `ACTION_EDIT`, `ACTION_SEND`, `ACTION_SEND_MULTIPLE`), app shortcuts,
  `handleSend` fixed (EXTRA_STREAM before EXTRA_TEXT), deprecated `getParcelableExtra` replaced.
- **R-34b** `32023a7` — Home, sessions, tabs: `TabStrip` populated with open sessions, correct
  `CompareMode` labels, session persistence via `SessionStore`, LRU eviction preserving
  unsaved edits.
- **R-35** `349bd06` — Settings persist via DataStore: `SettingsRepository` with 10 typed flows,
  `SettingsViewModel`, every toggle wired and surviving restart. Theme/dynamic-colour honoured.
- **R-36a** `c12b1b4` — Programmatic text tools: line-ending/encoding conversion, join/split
  lines, go-to-line dialog. Counted confirmation for tools changing >50% of lines. Case
  conversion excluded from v0.1.

── v0.1 "Compare + Viewer" ship line ──

### Phase 3 — Custom editing surface / v0.2 (complete)

- **R-00b** `17b7f22` — Device test tier: Gradle Managed Devices configured (Pixel 6, API 34,
  aosp-atd). Robolectric dropped (incompatible with SDK 37 / AGP 9.x). ADR-001 updated.
- **R-14** `0f1c03b` — LineLayoutCache: per-line `TextLayoutResult` from `TextMeasurer`. Tab
  expansion with bidirectional display-column ↔ character-offset maps. 25 JVM tests.
- **R-15** `f2d8797` — Caret, selection, gestures, semantics: blinking caret with tap-to-place,
  drag selection, long-press/double-tap word select, draggable handles ≥48dp, floating toolbar
  (cut/copy/paste/select all/share), TalkBack semantics, column selection model prepared.
- **R-16** `43fda2b` — IME connection: invisible `BasicTextField` bridge for soft keyboard
  input, composing region with underline, hardware keyboard shortcuts (Ctrl+Z/Y/S/A/C/X,
  arrows with Shift, Home/End, Tab), `imePadding`, autocorrect off by default.
- **R-17b** `47e098d` — Typing-level undo coalescing: consecutive keystrokes within one line
  and 2s window collapse to one step. Window breaks on caret jump, save, non-typing op.
- **R-18b** `cfd8a11` — Caret navigation across wrapped rows: `WrappedRowCache` stores visual-
  row boundaries, up/down navigate visual rows before crossing logical lines, smart Home/End.
- **R-36b** `37b61de` — Selection-scoped tools, case conversion (uppercase/lowercase/title case),
  bookmarks with gutter indicator and next/prev navigation, column selection mode toggle.

### Phase 7 — Hardening and release (complete)

- **R-37** `033612f` — Accessibility audit: keyboard shortcuts sheet in design module, gutter
  glyphs confirmed (+ − ~ !), ~90 strings extracted to `strings.xml`, `LocalReduceMotion`
  composition local, `ContrastChecker` wired at startup.
- **R-38** `b2397ad` — JVM performance budget references: 6 benchmark tests (creation, heap,
  typing latency, line access, compare throughput, intra-line ranges). DocumentLimits unchanged
  pending device measurement. Monospace fast path deferred. ADR-003 updated.
- **R-39** `1cf505f` — Dead code sweep: 9 of 13 original types confirmed wired, 5 deferred
  with ADR-010. Empty lambdas audited (3 legitimate display-only). KDoc honesty verified.
  `checkUnusedPublicTypes` task added.
- **R-40** `86a7080` — Release: `versionName = "0.2.0"`, signing config verified, `licenses.md`
  and in-app licences complete, Navigation Compose and DataStore entries added.
- **R-41** `ca63dd0` — On-device diagnostics: `CrashLogger` (ring buffer of 10 crash logs),
  `AnrWatchdog` (5s main-thread watchdog), "Share diagnostic report" in settings (user-
  initiated, redacted, no file paths/content, no network).

## Post-P1 Reviews and Bug Fixes

### Review-1 (R-42 through R-49) — `227a9c6`

Complete rewrite of horizontal scrolling, IME bridge, and touch input.
- **R-42** HorizontalScrollController in design module (ADR-011). Single offset per view,
  graphicsLayer translation, scrollable(Horizontal) gesture input.
- **R-43/R-44** Unified and split diff views adopt HorizontalScrollController.
- **R-45** Split diff bidirectional vertical sync with sub-row connector precision.
- **R-46** IME bridge shrinks to 1dp anchor; editor owns all gestures.
- **R-47** Selection via detectDragGesturesAfterLongPress; plain drags scroll.
- **R-48** IME sentinel for col-0 backspace, composition-safe sync, forward-delete.
- **R-49** DisplaySettings plumbing.

### Review-2 (R-50) — `f0c9c61`

Selection handles with real geometry, popup-clamped floating toolbar, tap precision
fix (fully specified textStyle matching measurement).

### Issue #11 — Menu redesign — `b113e8e`

Editor and compare overflow menus reorganized with contextual settings:
- Editor: view toggles (word wrap, line numbers, whitespace, font size) with checkmarks
- Compare: layout toggle (unified/split), sync scroll, granularity submenu
- Settings link in both menus

### Bug fixes

- `e03d693` — Save works for file:// URIs (direct flavour); dirty state observable via
  viewModel.isDirty collecting document changes flow.
- `17f9bca` — Setup screen state hoisted to NavGraph (file picks survive navigation).
- `49b9db5` — DisplaySettings wired through to EditorContent (settings take effect).
- `3bd5280` — Compare layout/granularity/sync scroll settings wired to views.
- `59911a6` — Granularity: pass currentRuleSet to CompareState on creation.
- `616efd5` — Sync scroll passed through AdaptiveDiffView to SplitDiffView.
- `fcb76c2` — Third file slot visibility survives file browser navigation.
- `ac95d21` — Third file picker fully wired (file browser slot, SAF, race condition fix).
- `42a507b` — 3-way compare wired: Diff3.diff3() runs when base file provided,
  Diff3.toCompareResult() bridges to UI.
- `f301c8d` — 3-way compare shown in sessions with base file label.
- `d7c7dac` — Compare tab shows base file name; incremental build number in About.

### Issue #12 — Touch bar and standard editing operations — `a2536dc`

Persistent bottom touch bar with scrollable icon buttons (44dp targets, grouped with
dividers): clipboard (cut/copy/paste/select all), undo/redo (disabled state), line ops
(delete/duplicate/insert above+below/move up+down), indent/outdent, comment toggle, find.

9 new EditorState methods: `deleteLine`, `duplicateLine`, `insertLineAbove`,
`insertLineBelow`, `moveLineUp`, `moveLineDown`, `indent`, `outdent`, `toggleComment`.
All selection-aware, all single undo steps.

8 new keyboard shortcuts: Ctrl+Shift+K (delete line), Ctrl+Shift+D (duplicate),
Ctrl+Enter / Ctrl+Shift+Enter (insert below/above), Alt+Up/Down (move line),
Tab/Shift+Tab (indent/outdent), Ctrl+/ (comment toggle).

Comment prefix detected from file extension (// for Kotlin/Java/JS, # for Python/Shell, etc.).

- `3ab276b` — Fix: touch bar cut/copy/paste wired via ClipboardManager (were empty placeholders).

### Review-3 (R-51 through R-57) — Issue #13

Save safety, correctness, and undo batching fixes from Review-3 (OE-REV-003).

- **R-51** — Merge save aborts on backup failure instead of silently proceeding.
- **R-52** — Merge save uses `materialise()` for correct charset encoding instead of
  `text().toByteArray()` (was silently transcoding non-UTF-8 files to UTF-8).
- **R-53** — `materialise()` re-emits BOM when the source file had one. UTF-8-BOM,
  UTF-16 files no longer lose their BOM on save. `bomLength` parameter added to
  `PieceTableDocument.create()`.
- **R-54** — `beginBatch()`/`commitBatch()` added to `TextDocument` interface.
  `indent()`, `outdent()`, `toggleComment()` batch all line edits into a single undo
  step. `moveLineUp()`/`moveLineDown()` are now selection-aware (move the entire
  selected block). Journal writes suppressed during batch to preserve single-step
  semantics on crash recovery.
- **R-57** — `DirectSourceProvider.write()`: `channel.force(true)` before rename;
  temp file deleted on `renameTo` failure. `docs/licenses.md` contradictory line
  removed; JUnit 4 EPL-1.0 carve-out added. `README.md` module table completed.
  ADR-001 notes GMD coverage is smoke-only at v0.2.0.

### F-05b — Benchmark harness — Issue #14

`:benchmark` macrobenchmark module targeting `:app` with `benchmark` build type
(minified, profileable). Four benchmark classes: `StartupBenchmark` (NFR-P1),
`CompareThresholdBenchmark` (NFR-P2), `ScrollBenchmark` (NFR-P3),
`HeapBenchmark` (NFR-P4/P5). Deterministic fixture generator
(`./gradlew :benchmark:generateFixtures`) produces 250 MB pair and 500k-line
file from seeded PRNG. ADR-002 documents methodology and results table.
Benchmark navigation sequences are structural placeholders refined when
F-01/F-02/F-03 land large-file support.
