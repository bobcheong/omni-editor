# SPEC-GAP-PLAN — OE-SPEC-001 v1.2 audit and forward implementation plan

**Revision:** 6 · **Date:** 17 August 2026 · **Audited at:** HEAD `e6434cb` (issue #22 closed)
**Companion to:** `docs/OE-SPEC-001.html`, `docs/P1-BUILD-PLAN.md`, `docs/P1-COMPLETION-PLAN.md`
**Task IDs:** F-nn (feature-gap tasks). Requirement IDs cited are from OE-SPEC-001.

---

## Part A — Re-audit at #22 closure: verified task status

Method unchanged: every W-nn claim verified against the tree by grep at `e6434cb`, not taken from commit messages. The post-feature fix commits (five HexGrid scroll fixes, session-group UX fixes, Compare-with/Save-as corrections) indicate real on-device testing.

### A.1 W-task disposition

| Task | Status | Evidence |
|---|---|---|
| W-01 ReportScope | **DONE** | Sealed class with `DiffOnly` + `Context(n)`; matches OE-RPT-2. |
| W-02 Compare bookmarks | **DONE** | Wired across `CompareState`/`CompareScreen`/`UnifiedDiffView`/`SplitDiffView`; session-persisted. |
| W-03 Swipe navigation | **DONE** | `SwipeDiffDetector` attached in both Unified and Split views per ADR-013. |
| W-04 Outline + brackets | **DONE** | `SymbolOutlineSheet` + bracket jump in `EditorScreen`. |
| W-05 Word-level merge | **ENGINE-ONLY — still open** | `MergeEngine.mergeWordLevel()` exists and documented; zero references from `feature/compare` or `app`. The active-line-sheet accept UI was not built. F-10/OE-MRG-2 remains user-unreachable. |
| W-06 Hex view toggle | **DONE** | Wired in `EditorScreen`; five follow-up scroll fixes show device exercise. |
| W-07 Groups + search | **DONE** | `HomeScreen` UI incl. group chips with move/rename/delete; search field. |
| W-08 Theme editor | **DONE** | `ThemeEditorScreen` in app. |
| W-09 Accessibility | **HALF DONE** | TalkBack semantics on diff rows ✓; **no a11y check in `ci.yml`**, no recorded manual pass. |
| W-10 D-8 versioning | **MOSTLY DONE** | `version.properties` (0.4.0) as source of truth, arithmetic `versionCode`, `ValueSource` SHA, `BUILD_NUMBER` deleted, About/CrashLogger show `version (sha) · type`. **Missing: tag==file guard step in `release.yml`, and no `v0.4.0` tag pushed** — remote still has only v0.1.0/v0.2.0. Nit (non-blocking): SHA provider is `.get()`-ed at configuration time, partially defeating configuration-cache safety; wire lazily into `buildConfigField`. |
| W-11 ADR renumber | **DONE** | `014-performance-verification.md`; collision resolved. |
| W-12 F-03 resolution | **DONE (deferred path)** | ADR-012 gains an explicit F-03 deferral to v0.5 (INDEXED_EDITABLE tier); CHANGES.md corrected for the F-03/F-15 overstatements. |
| W-13 LongJobService | **DONE** | Wired in `CompareCoordinator` with start/stop + notification path. |

### A.2 v0.4 exit punch-list (all that remains)

1. **X-1 — Tag + guard** *(blocking; also the D-1 desktop gate)*: add a `release.yml` step comparing `GITHUB_REF_NAME` to `version.properties` (fail on mismatch); push `v0.4.0`; verify green run, installable APK, About string.
2. **X-2 — a11y CI check** (finish W-09): Compose accessibility checks in CI; record one manual TalkBack pass.
3. **X-3 — Word-merge UI or explicit deferral** (resolve W-05): either build the word-level accept UI in the active-line sheet, or defer F-10 to v0.5 alongside F-03 with a note in ADR-012 — defensible, since both touch the same sheet and the editable large-file work lands there anyway. Either way the ledger states it.

v0.4 closes when X-1..X-3 are resolved. Nothing else in the audit is open.

## Part B — Forward plan

### v0.4 closure
The X-1..X-3 punch-list above (small; X-1 is ~an hour including the workflow run). Recommend a `v0.4.x` patch tag if X-2/X-3 land after X-1's `v0.4.0`.

### v0.5 — Linux desktop port (project P2) + riders
The D-1 gate is passed once X-1 is done. Pre-work unchanged: app-ID ADR first commit, KMP plugin, `expect/actual`, IME/Wayland spike, three desktop ADRs, JetBrains Mono, jpackage/appimagetool CI. **Riders confirmed for v0.5:** F-03 large-file editing (INDEXED_EDITABLE tier, per ADR-012 deferral) and — if X-3 takes the deferral path — F-10 word-merge UI; both concentrate in the editor/active-line surface and should land *before* the KMP `expect/actual` split touches those files, for the same reason save-path consolidation preceded the port.

### v0.6 onward (issues #17–#21, unchanged)
#17 v0.6 three-way merge UI + binary compare + hex editing (F-17/F-18/F-18b; hex-grid dependency now satisfied by W-06) → #18 v0.7 structured data → #19 v0.8 folders/sync/archives (7z + tar.gz, D-4) → #20 v0.9 remote + URL → #21 v1.0 Git + reach (M18/M19 per D-6). The "Blocked by #22" note on #17 can be cleared once X-1 lands.

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

One: X-3 — build the word-merge UI now, or defer F-10 to v0.5 with F-03. Both acceptable; the plan assumes deferral if unanswered.

---

## Appendix — Change history

- **Rev 6 (17 Aug 2026):** Re-audit at `e6434cb` after #22 closure. 11 of 13 W-tasks verified DONE with UI wiring; W-05 remains engine-only (no word-merge UI), W-09 half done (labels ✓, CI check ✗), W-10 missing only the tag==file guard and the `v0.4.0` tag itself. v0.4 exit reduced to punch-list X-1..X-3; X-1 is the D-1 desktop gate. v0.5 gains riders F-03 (per ADR-012 deferral) and conditionally F-10, both to land before the KMP split. Configuration-time `.get()` on the SHA provider noted as a non-blocking nit.
- **Rev 5 (17 Aug 2026):** Re-audit at `a15124a` after #16 closure. Per-task verification with DONE/ENGINE-ONLY/DIVERGENT status: 6 of 11 v0.4 tasks are engine-only (F-07/08/09/10/11/14), F-12 diverges from OE-RPT-2 scopes, F-15 unsubstantiated, F-05 violates D-8 (shallow-clone versionCode bug), F-03 open despite changelog claim. Tags v0.1.0/v0.2.0 found on remote (stale "no tags" finding corrected). v0.4-w wiring package (W-1..W-13) defined as the next issue; exit criteria for v0.4 stated. ADR-002 collision correction carried as W-11.
- **Rev 4 (16 Aug 2026):** D-8 versioning scheme; F-05 rescoped with full mechanics.
- **Rev 3 (16 Aug 2026):** D-4..D-7; F-05b benchmark harness; F-18b hex editing; all questions closed.
- **Rev 2 (16 Aug 2026):** Audit corrections (filter view done; find partial); F-07 hex view; F-09 gesture conflict + options; D-1..D-3.
- **Rev 1 (16 Aug 2026):** Initial audit at `28cbc65` and phased plan v0.3–v1.0.
