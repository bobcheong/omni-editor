# SPEC-GAP-PLAN — OE-SPEC-001 v1.2 audit and forward implementation plan

**Revision:** 5 · **Date:** 17 August 2026 · **Audited at:** HEAD `a15124a` (issue #16 closed)
**Companion to:** `docs/OE-SPEC-001.html`, `docs/P1-BUILD-PLAN.md`, `docs/P1-COMPLETION-PLAN.md`
**Task IDs:** F-nn (feature-gap tasks). Requirement IDs cited are from OE-SPEC-001.

---

## Part A — Re-audit at #16 closure: verified task status

Method: every F-nn claim in commits `43302b8..a15124a` (#16) and `d555789..12e2c59` (#15) was verified against the tree by grep, not taken from commit messages. Three statuses: **DONE** (engine + UI wired), **ENGINE-ONLY** (tested component exists, nothing reachable from the UI), **DIVERGENT** (implemented against the plan/decision).

### A.1 v0.3 tasks (#15)

| Task | Status | Evidence |
|---|---|---|
| F-01 large-file open | **DONE** (read-only tier) | `INDEXED_READ_ONLY` wired via `EditorCoordinator` → `LargeFileDocument`; ADR-012 ladder in force. |
| F-02 BlockDiff wiring | **DONE** | `compareAuto` path in `DiffEngine`; wired via `CompareCoordinator`. |
| F-03 large-file *editing* | **NOT DONE** | Large files open read-only (`EditorState.readOnly` guards all edit paths). Changelog `[F-01..F-05]` overstates; F-03 remains open. Acceptable sequencing — read-only-first was the plan — but the ledger should say so. |
| F-04 long-job service | **SCAFFOLD** | `LongJobService` exists; notification progress + cancel and generic-host shape not verified as wired to compares. |
| F-05 release/versioning | **DIVERGENT** | `versionCode = git rev-list --count HEAD` — the scheme D-8 rejected, and broken under the project's own `--depth 50` convention (count caps at fetch depth, so versionCode can *decrease*). `versionName` still hardcoded `"0.2.0"`; `BUILD_NUMBER` still alive. Tags v0.1.0/v0.1.0-store/v0.2.0 now exist on the remote (earlier "no tags" finding is stale), but no `v0.3.0` tag and no tag==file enforcement. |
| F-05b benchmark harness | **DONE** | `:benchmark` module present. Per D-7, first results land at the v0.3 release run. |

### A.2 v0.4 tasks (#16)

| Task | Status | Evidence |
|---|---|---|
| C.4 NavGraph split | **DONE** | `OmniNavGraph` 648 lines + `EditorCoordinator` (302) + `CompareCoordinator` (486). |
| F-06 compare find | **DONE** | Case/whole-word/regex toggles + per-side counts live in `CompareScreen`. |
| F-07 hex view | **ENGINE-ONLY** | `HexGrid` composable in `design` + `HexViewConfig` model — **zero usages** in `feature/*` or `app`. No "View as hex" toggle exists. |
| F-08 compare bookmarks | **ENGINE-ONLY** | `CompareBookmark` serialised in `Session` — **zero references** in `feature/compare`. No UI to set, list, or jump. |
| F-09 swipe navigation | **ENGINE-ONLY** | `SwipeDiffDetector` (fling-at-bound per ADR-013) exists — **never attached** to any view. |
| F-10 word-level merge | **ENGINE-ONLY** | `WordMerge.kt` in `core/diff` — not referenced by `MergeEngine`, any feature, or the app. |
| F-11 outline + brackets | **ENGINE-ONLY** | `SymbolExtractor` + `BracketMatcher` in `core/diff` — no outline sheet, no jump action in the editor. |
| F-12 reports | **PARTIAL + DIVERGENT** | HTML side-by-side ✓, PDF-via-print noted ✓, header/footer carries rules + engine mode ✓. But `ReportScope` = ALL/SELECTION/VISIBLE, which is **not** OE-RPT-2's scope set (diff-only / N-context-lines / full / one side). Diff-only and context-N — the two most-used report scopes — are missing. |
| F-13 sessions | **MOSTLY DONE** | `search()` ✓, `exportAsJson`/`importFromJson` ✓, `SessionGroup` model ✓. Home-screen UI for groups/search not verified; staleness badge absent. |
| F-14 theme editor | **MODEL-ONLY** | `UserTheme` data class exists. No editor screen, no import/export UI — the smallest slice of any task. |
| F-15 accessibility | **UNSUBSTANTIATED** | Commit `a15124a` is tagged `[F-15, F-16]` but its content is entirely F-16 (shortcuts + tile); no a11y work in the diff, no a11y CI checks added. Pre-existing `contentDescription` on diff rows predates #16. F-15 remains open. |
| F-16 shortcuts + QS tile | **DONE (tile as scaffold)** | Dynamic shortcuts registered and routed ✓; `CompareClipboardTile` launches the activity but is `STATE_INACTIVE` scaffold — acceptable v1 for a launcher tile. |

### A.3 Cross-cutting corrections outstanding

1. **D-8 versioning violation** (F-05 above) — highest priority; the current scheme mis-versions any shallow build.
2. **ADR numbering collision** — `002-performance-budgets.md` and `002-performance-verification.md` both exist. Renumber the newer to ADR-014.
3. **Changelog inflation** — CHANGES.md claims `[F-01..F-05]` and `[F-15, F-16]` for commits that don't contain F-03/F-15 work. Per the project's own honesty convention (OE-ENG-4 spirit), the ledger should distinguish *scaffolded* from *done*; this plan's三-status vocabulary (DONE / ENGINE-ONLY / DIVERGENT) is offered for reuse.

### A.4 Assessment

Issue #16 delivered the *engine layer* of v0.4 — every hard algorithmic piece (word merge, symbol extraction, bracket matching, hex grid rendering, swipe detection, report generation, session serialisation) exists and is presumably tested. What it did not deliver is the *feature layer*: six of eleven tasks have no user-reachable surface. The plan's v0.4 phase is therefore roughly half done, not closed. This is a natural seam, not a failure — but the next work package should be framed as "v0.4 wiring" rather than new scope.

---

## Part B — Revised near-term plan

### v0.4-w — Wiring package (recommended next issue, #17)

Ordered by user value per unit effort:

- **W-1 (F-12 fix)** Reconcile `ReportScope` with OE-RPT-2: add DIFF_ONLY and CONTEXT(n) scopes; keep SELECTION/VISIBLE as extensions beyond spec. Small, pure-Kotlin, unblocks real report use.
- **W-2 (F-08)** Compare bookmarks UI: long-press row → toggle bookmark; bookmark strip/menu to jump; persists via the already-serialised `Session.compareBookmarks`.
- **W-3 (F-09)** Attach `SwipeDiffDetector` to unified + split views; wire to Prev/Next actions; settings toggle per OE-TXT-2 ("configurable off").
- **W-4 (F-11)** Outline sheet in editor (from `SymbolExtractor`) + "jump to matching bracket" action (keyboard + overflow), disabled above the full-index tier.
- **W-5 (F-10)** Route `WordMerge` through `MergeEngine` for intra-line replace/insert; add the word-level accept UI in the active-line sheet.
- **W-6 (F-07)** "View as hex" toggle in the editor using `HexGrid`, backed by `FileIndexer`'s channel; becomes the destination for the "binary detected" state (§13).
- **W-7 (F-13 finish)** Home UI for session groups + search field; staleness badge.
- **W-8 (F-14)** Theme editor screen + JSON import/export UI over `UserTheme`.
- **W-9 (F-15, real)** The a11y pass as specified: TalkBack labels asserting side + change type per row, Compose a11y checks in CI, manual pass recorded.
- **W-10 (F-05 redo)** Implement D-8 as specified in rev-4's F-05: version file as source of truth, `versionCode` derived arithmetically, tag==file enforcement in `release.yml`, `ValueSource` SHA+dirty display, delete `BUILD_NUMBER`, update `CrashLogger`/`AnrWatchdog`. Then tag `v0.3.0` retroactively on the #15 closure commit or `v0.4.0` at #17 closure — either is fine, but one real tagged release must be exercised before the desktop branch (pre-agreed desktop gate).
- **W-11** Renumber `002-performance-verification.md` → ADR-014; fix internal references.
- **W-12 (F-03)** Large-file editing tier, or an explicit deferral note in ADR-012 if it slips to v0.5 — either resolves the changelog overstatement.
- **W-13 (F-04 finish)** LongJobService: bind to `compareAuto` for >10 s jobs, notification progress + cancel, generic-host shape for future sync reuse.

Exit criteria for calling v0.4 closed: every A.2 row reads DONE, W-10/W-11 landed, and a tagged release exists.

### v0.5 onward

Unchanged from rev 4: desktop port (project P2) after v0.4 closes → v0.6 three-way merge UI (F-17) + binary compare (F-18) + hex editing (F-18b, ADR on byte undo model first) → v0.7 structured data → v0.8 filesystems (7z + tar.gz per D-4) → v0.9 remote + URL → v1.0 Git + reach (M18/M19 requirements analysis per D-6 before any adoption).

---

## Part C — Standing refinements (carried, still applicable)

1. Size ladder (now ADR-012) — in force; ceiling raises follow the recorded-benchmark policy (D-2/D-7).
2. Differential testing vs `git diff --histogram` on the golden corpus in CI — still unimplemented, still cheap.
3. Property test: all left→right merges ⇒ byte-identical documents — now also covers `WordMerge` once W-5 lands.
4. Session JSON as the canonical interchange format — schema now exists; freeze `schemaVersion` semantics before desktop.
5. Error-model audit before each release — unchanged.
6. Spec amendments (C.7 of rev 4) — still pending: OE-TXT-10 and URL `~ deferred`, hex view added as `+`, ADR-013 gesture note.

## Part D — Decisions (D-1..D-8, unchanged)

- **D-1** Desktop after v0.4. **D-2** Benchmarked size ladder (ADR-012). **D-3** HTML preview → v1.0 review; URL → v0.9. **D-4** 7z + tar.gz, RAR dropped. **D-5** Hex editing at v0.6 as F-18b, byte-undo ADR first. **D-6** M18/M19 parked; requirements analysis before adoption. **D-7** Manual per-release benchmarks, no CI gating. **D-8** File-sourced hermetic versioning + tag==file enforcement + display-only SHA; build numbers and timestamps rejected. *(All Robert, 16 Aug 2026.)*

## Part E — Open questions

None standing. New question only if W-12 defers: which release picks up F-03.

---

## Appendix — Change history

- **Rev 5 (17 Aug 2026):** Re-audit at `a15124a` after #16 closure. Per-task verification with DONE/ENGINE-ONLY/DIVERGENT status: 6 of 11 v0.4 tasks are engine-only (F-07/08/09/10/11/14), F-12 diverges from OE-RPT-2 scopes, F-15 unsubstantiated, F-05 violates D-8 (shallow-clone versionCode bug), F-03 open despite changelog claim. Tags v0.1.0/v0.2.0 found on remote (stale "no tags" finding corrected). v0.4-w wiring package (W-1..W-13) defined as the next issue; exit criteria for v0.4 stated. ADR-002 collision correction carried as W-11.
- **Rev 4 (16 Aug 2026):** D-8 versioning scheme; F-05 rescoped with full mechanics.
- **Rev 3 (16 Aug 2026):** D-4..D-7; F-05b benchmark harness; F-18b hex editing; all questions closed.
- **Rev 2 (16 Aug 2026):** Audit corrections (filter view done; find partial); F-07 hex view; F-09 gesture conflict + options; D-1..D-3.
- **Rev 1 (16 Aug 2026):** Initial audit at `28cbc65` and phased plan v0.3–v1.0.
