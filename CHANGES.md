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

### Phase 5 — Compare and viewer (not started)

Next: R-24 (Rule set UI).

### Phase 6 — Shell and tools (not started)

### Phase 3 — Custom editing surface / v0.2 (not started)

### Phase 7 — Hardening and release (not started)
