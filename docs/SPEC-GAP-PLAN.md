# SPEC-GAP-PLAN — OE-SPEC-001 v1.2 audit and forward implementation plan

**Revision:** 3 · **Date:** 16 August 2026 · **Audited at:** HEAD `28cbc65` (post Review-3, R-51..R-57)
**Companion to:** `docs/OE-SPEC-001.html`, `docs/P1-BUILD-PLAN.md`, `docs/P1-COMPLETION-PLAN.md`
**Task IDs:** F-nn (feature-gap tasks). Requirement IDs cited are from OE-SPEC-001.

---

## Part A — Audit: spec vs. implemented

### A.1 Module disposition

| Spec module | Status at `28cbc65` |
|---|---|
| M1 Sources | **Partial.** LOCAL (direct path + SAF grant), SNIPPET, share-sheet (ACTION_SEND/SEND_MULTIPLE/ACTION_VIEW) done. URL, ARCHIVE, GIT exist only as `SourceKind` enum values — no provider behind them (URL now formally deferred to v0.9, see D-3). Recents done; favourites store exists but pin/backup semantics (OE-SRC-3) unverified. Per-side encoding override (OE-SRC-5) partial. |
| M2 Engine | **Substantially done** for in-memory files: histogram + Myers fallback, intra-line, full ignore-rule set incl. column ranges (OE-ENG-5/6), cancellation, ResultStore. **Missing:** large-file path — `BlockDiff` and `FileIndexer` built but unwired (ADR-010), ceilings at 16 MiB editor / 8 MiB compare (ADR-003) vs spec targets (OE-ENG-4, NFR-P3), now reinterpreted as a benchmarked size ladder (D-2). Diff3 wired for 3-way *compare* only. |
| M3 Text compare view | **Mostly done:** unified/split, minimap with drag-seek, sync scroll, granularity, status bar, active-line sheet, syntax highlighting with carried lexer state, **filter view All/Diffs/Matches (OE-TXT-4 — done**, segmented `FilterBar` wired to `state.filterMode`; rev-1 of this plan wrongly listed it as missing). **Missing:** swipe diff-to-diff (OE-TXT-2 — gesture conflict, see F-09), compare bookmarks persisted with session (OE-TXT-8), the §13 "differences only in ignored content" state, HTML preview (OE-TXT-10 — deferred, D-3). |
| M4 Merge | **Done** for 2-way: per-hunk directional merge, accept-all with counted confirmation, batched undo, backup-before-write with abort-on-failure, external-change detection, dirty-state handling. **Missing:** word-level replace/insert within a line pair (OE-MRG-2); three-pane merge UI (ADR-008). |
| M5 Editor | **Mostly done:** piece-tree document, tabs, find/replace (regex/whole-word/case, replace-all), text tools, programmer key row, touch bar, go-to-line, autosave journal, keyboard shortcuts. **Missing:** symbol outline + jump-to-matching-bracket (OE-EDT-5), multi-caret (OE-EDT-8; column select partial), find-in-selection + capture-group references verification (OE-EDT-4), large-file editing (OE-EDT-3 — same ceiling issue as M2), hex/binary view of the open file (new, F-07). |
| M13 Sessions | **Partial.** Session model, SessionStore, pinning, recents, 3-way sessions. **Missing:** groups/folders, search, JSON export/import (OE-SES-4), staleness badge, wide-layout preview pane. |
| M14 Reports | **Partial.** Unified patch, plain-text summary, HTML unified, share-out wired. **Missing:** HTML side-by-side, PDF via print pipeline, scope options (OE-RPT-2), configurable header/footer with rules + engine mode (OE-RPT-3). |
| M15 Find | **Implemented, partial vs spec** (rev-1 wrongly listed as unimplemented — the find bar lives inside `CompareScreen`, not NavGraph). Toggleable find bar, match list, prev/next stepping with wraparound. **Missing vs OE-FND-1:** case-insensitive substring only — no case toggle, whole-word, or regex — and no per-side match counts. |
| M16 Appearance | **Partial.** Light/dark/high-contrast + dynamic colour, token colours per theme. **Missing:** user-created themes, JSON import/export UI (OE-APP-1), per-mode compare colour sets. |
| M17 Input | **Partial.** Shortcuts exist; remapping UI, drag-and-drop, multi-window untested/absent. |
| M6 Folder+sync, M7 Table/Excel, M8 Documents, M9 Binary, M10 Archives, M11 Remote, M12 Git, M19 AI | **Not started** (spec phases P2–P5; consistent with D-2 in the spec). F-07 pulls a fragment of M9's hex grid forward as an editor view. |
| §9 Platform surfaces | Share sheet + ACTION_VIEW done. **Missing:** quick-settings tile, app shortcuts, widget (~v1.1), foreground service for >10 s compares. |
| NFR-A1 Accessibility | Glyph markers and contrast tooling exist (`ContrastChecker`, `AccessibilityConfig`). Full TalkBack per-row labels + a11y test pass not evidenced. |
| DIST-4 Release | **Gap persists:** no tags pushed, `versionCode = 1` hardcoded. `release.yml` never exercised end-to-end. |

### A.2 The one structural divergence worth naming

The spec's headline claim (G-2, "files that make other Android editors fail") is currently inverted: v0.2 caps at 16 MiB. Everything needed to fix it already exists as tested, unwired code (`FileIndexer`, `BlockDiff`, ADR-005's preserved streaming signature). This is the highest-leverage gap and also pure-Kotlin work that survives the KMP port intact.

---

## Part B — Phased plan

Phase numbering note: OE-SPEC-001 §15 uses P1–P5 for *feature* phases; the project separately uses P1 = Android / P2 = Linux desktop. To avoid collision this plan uses release versions. Spec-phase mapping shown per release.

### v0.3 — Large files and data paths (spec P1 closure, part 1)

Ordering constraint: F-01..F-04 land before the desktop KMP branch opens — they change `core/io` and `core/diff` seams that both platforms will share.

- **F-01** Wire `FileIndexer` into the open path for files above an in-memory threshold; read-only mode first (view + compare, no edit) with the mode disclosed in the header (OE-ENG-4 "never silently degraded"). ADR required: the size ladder (per D-2) replacing ADR-003 ceilings.
- **F-02** Wire `BlockDiff` into `DiffEngine` behind the same threshold. Acceptance: golden-corpus parity with full diff below threshold; heap benchmark recorded (ADR-010 deferral condition discharged or explicitly re-deferred with a device-availability note).
- **F-03** Large-file *editing*: piece list over the mmap'd original (OE-EDT-3), materialise-on-save through the existing atomic write path. Raise the editor ceiling stepwise (64 → 256 MiB) with per-step benchmarks rather than jumping to 2 GB.
- **F-04** Foreground service for compares >10 s with notification progress + cancel (§9) — becomes necessary once F-02 makes long compares possible. Build as a generic long-job host (sync and scheduled jobs reuse it later).
- **F-05** Exercise the release pipeline for real: dynamic `versionCode`, tag `v0.3.0`, full `release.yml` run producing an installable APK. (Carried over; still outstanding at `28cbc65`.)
- **F-05b** Benchmark harness *(new in rev-3, supports D-2/D-7)*: `:benchmark` module (`androidx.benchmark:benchmark-macro-junit4`), `benchmark` build type on `:app` (minified, non-debuggable, profileable), deterministic fixture generator for the 250 MB pair and 500k-line diff (generated/pushed, never committed), `dumpsys meminfo` capture for NFR-P5. Run manually per release on the reference device; results recorded in the ADR-002 table. No CI gating (D-7). This is a prerequisite for F-01..F-03 acceptance, so it lands first in v0.3.

### v0.4 — Compare/editor UX completion (spec P1 closure, part 2)

Pre-task: split `OmniNavGraph.kt` into per-screen coordinators before starting (see C.4) — most v0.4 tasks touch it.

- **F-06** Extend compare find to OE-FND-1: case-sensitivity toggle, whole-word, regex, and per-side match counts, added to the existing `CompareScreen` find bar. *(Rescoped in rev-2: the bar, matching, and stepping already exist.)*
- **F-07** Hex/binary view in the editor *(new in rev-2)*. A read-only "View as hex" toggle on any open document: address column, hex bytes, ASCII column, bytes-per-row adapting to width (8/16/32 — OE-BIN-2's grid rules). Backed by `FileIndexer`'s random-access channel from v0.3 so it works above the text ceiling, which also gives the "binary detected" state (§13) a destination. Build the grid as a shared component in `design`/a small feature module so F-18 (binary *compare*, v0.6) composes two of them rather than rebuilding. Hex *editing* is confirmed for later (D-5): a separate task at v0.6 alongside F-18, gated on an ADR for the byte-oriented undo model.
- **F-08** Compare bookmarks persisted with the session (OE-TXT-8); reuse editor bookmark model. Include the §13 "differences only in ignored content" state as a small adjunct.
- **F-09** Swipe left/right for next/previous difference (OE-TXT-2). **Known conflict:** ADR-011 attaches horizontal-gesture input for long-line panning to the same content container via `scrollable(Horizontal)`; Compose's axis disambiguation will route any horizontal-slop gesture to the pan, so a naive swipe detector either never fires or steals panning. Resolution options, to be settled in an ADR before implementation: (a) fling-at-bound — a horizontal fling when `offsetPx` is already at 0/`maxOffsetPx` triggers diff navigation, panning otherwise wins; (b) edge-zone swipe strips outside the scrollable container; (c) enable the gesture only when word-wrap is on (no horizontal overflow → no conflict); (d) drop the gesture — §8 requires every gesture to have a visible control equivalent, and Prev/Next buttons already exist, so OE-TXT-2's swipe is an enhancement, not a functional gap. Recommendation: (a) with (c) as the fallback if fling-at-bound feels accidental in testing.
- **F-10** Word-level replace/insert within a changed line pair (OE-MRG-2) — completes M4.
- **F-11** Symbol outline + jump-to-matching-bracket (OE-EDT-5); outline from the existing lexer token stream, disabled above the full-index threshold (S-04 state).
- **F-12** Report completion (M14): HTML side-by-side, scope options, configurable header/footer carrying rules + engine mode, PDF via the print service.
- **F-13** Session groups, search, JSON export/import with `schemaVersion` (OE-SES-4) — the export format doubles as the future desktop/session-sync bridge.
- **F-14** Theme editor: user themes, JSON import/export (OE-APP-1).
- **F-15** Accessibility pass to NFR-A1: TalkBack label per diff row (side + change type), automated Compose a11y checks in CI, manual pass documented per release.
- **F-16** Platform surfaces: app shortcuts, quick-settings "compare clipboard with…" tile.

### v0.5 — Linux desktop port (project P2)

Slots here per D-1. Pre-work as previously agreed: app-ID ADR first commit, KMP plugin, `expect/actual`, IME/Wayland spike, three desktop ADRs, JetBrains Mono, jpackage/appimagetool CI. Everything in `core/*` from v0.3 carries over unchanged; that is the point of doing v0.3 first.

### v0.6 — Three-way merge + binary compare (spec P4 fragment, pulled forward)

- **F-17** Three-pane conflict/merge UI over the already-tested `Diff3` (discharges ADR-008): result pane, take-all-local/remote, per-hunk selection, save-resolved. Doing this before Git (M12) means M12 later only adds source plumbing.
- **F-18** Binary/hex *compare* (M9): fast + smart modes over two instances of the F-07 hex grid; differing-bytes-only filter, dec/hex offsets (OE-BIN-1/3).
- **F-18b** Hex-level *editing* (per D-5): byte overwrite/insert in the F-07 grid. ADR required first — byte-oriented undo model distinct from the line-based piece table (options: byte-granular piece list over the raw channel, or an overlay edit map materialised on save). Shares the atomic-write save path.

### v0.7 — Structured data (spec P2)

- **F-19** Table compare (M7): CSV/TSV grid, key-column matching (OE-TBL-2), cell/row/column merge, frozen headers, cell inspector.
- **F-20** Excel XLSX/XLSM: on-device parse (custom trimmed OOXML reader per §10 — licence-check any candidate library against IND-5 before adoption), value + formula comparison.
- **F-21** Document compare (M8): DOCX/RTF structure-aware extraction, PDF text with page attribution, fidelity disclosure header (OE-DOC-4), no-text-layer state.

### v0.8 — Filesystems (spec P3, largest and riskiest)

- **F-22** Folder compare (M6): merged tree, method switching, filters, drill-down inheriting rules.
- **F-23** Sync engine to the full §6 semantics: SyncPlanner/SyncExecutor as separate types (the plan-is-what-executes property), baseline store for BOTH_CHANGED, trash-folder deletion, atomic temp+rename, resume, free-space pre-flight, case/illegal-name collision detection. ADR required before implementation; property tests that preview == execution on a simulated FS with failure injection.
- **F-24** Duplicates finder; scheduled sync via WorkManager with the ASK-blocks-execution rule.
- **F-25** Archives (M10): ZIP/JAR + 7z + tar.gz (D-4; RAR dropped — no licensed decoder needed, IND-5 satisfied), archive-as-folder-source.

Note: desktop existing by v0.8 materially helps here — sync engine correctness tests run far faster against a real POSIX filesystem than an emulator.

### v0.9 — Remote (spec P3 remainder)

- **F-26** SFTP + FTP/FTPS first (SSHJ, Commons Net — both Apache-2.0 ✓ IND-5), Keystore credentials, host-key TOFU + pin, connection editor with specific-failure test button (S-08).
- **F-27** SMB2/3 (SMBJ) + WebDAV (OkHttp); protocol capability matrix degradation labels ("not content-verified").
- **F-28** URL compare (OE-RMT-5): the URL `SourceKind` gains its provider here, per D-3.
- **F-29** Remote editing download–edit–upload cycle (OE-EDT-11) with conflict re-fingerprint before write.

### v1.0 — Version control + reach (spec P4/P5)

- **F-30** Git read/resolve (M12) via JGit: repo discovery, version diff, branch browser, conflict resolution reusing F-17, blame long-press.
- **F-31** Large-screen/keyboard polish, remapping UI, drag-and-drop, multi-window (M17); widget; localisation groundwork (Q-8 in spec); optional AI summary (M19) only if adopted per D-6 — a short requirements analysis (what leaves the device, size caps, kill switch, provider choice, cost) is written *before* any adoption decision, and M18/M19 stay stubbed until then; intent API (v1.2 per spec); HTML preview (OE-TXT-10) revisited here per D-3.

---

## Part C — Refinements and improvements beyond the spec

1. **Adopt a size ladder, not a cliff** (now Decision D-2). Disclosed tiers: full index → full index read-optimised → block mode → refuse-with-reason. Each tier named in the session header per OE-ENG-4's honesty rule; each ceiling raise backed by a recorded benchmark.
2. **Differential testing (§14) is specced but absent from CI.** Add a JVM test that shells to `git diff --histogram` on the golden corpus and flags semantic divergence. Cheap, catches engine regressions the golden files miss.
3. **Property test to add now:** applying all left→right merges makes documents byte-identical (spec §14). The batched-undo work in R-54 makes this assertable end-to-end.
4. **`OmniNavGraph.kt` split before v0.4 features.** ~1,350 lines at last review and most v0.4 tasks touch it. Extract per-screen coordinators first or the file becomes the merge-conflict epicentre of the release.
5. **Session JSON export (F-13) as the canonical interchange format.** Design it once with the desktop port in mind — it becomes cross-device sync (OE-SES-4 deferral) for free later.
6. **Error-model audit.** Spec's "no generic error path" rule: grep for catch-all error strings before each release; every user-visible failure maps to an `OmniError` variant and a §13 state.
7. **Spec amendments to file** (keeps OE-SPEC-001 and this plan from drifting): mark OE-TXT-10 (HTML preview) and the URL source in OE-SRC-1 as `~ deferred` per D-3; add the hex editor view to M5 (or M9) as a new `+` requirement; note the OE-TXT-2 gesture-conflict resolution once the F-09 ADR lands.

---

## Part D — Decisions taken (this plan)

- **D-1** Desktop port (project P2) is scheduled after v0.4, not before. Android spec-P1 closure completes first; `core/*` seams stabilised in v0.3 carry into KMP unchanged. *(Robert, 16 Aug 2026.)*
- **D-2** The spec's 2 GB / 250 MB-in-45 s targets are reinterpreted as a benchmarked size ladder: ceilings are raised stepwise with a recorded benchmark per step, never silently. To be recorded as the F-01 ADR. *(Robert, 16 Aug 2026.)*
- **D-3** HTML preview (OE-TXT-10) and URL sources are deferred: HTML preview to v1.0 review, URL sources to v0.9 with the rest of networking. Spec amendment to follow (C.7). *(Robert, 16 Aug 2026.)*
- **D-4** Archive formats are 7z and tar.gz in place of RAR (resolves spec Q-6). No licensed decoder; IND-5 satisfied. *(Robert, 16 Aug 2026.)*
- **D-5** Hex-level editing is confirmed as a later phase: F-07 ships read-only at v0.4; byte editing lands as F-18b at v0.6, gated on a byte-undo-model ADR. *(Robert, 16 Aug 2026.)*
- **D-6** Monetisation and AI summary (spec Q-4/Q-7, modules M18/M19) stay parked and stubbed. Before any adoption decision, a requirements analysis is produced (M18: tier split validation, entitlement mechanism; M19: data egress statement, size caps, kill switch, provider and cost) so the decision is made on captured inputs, not built speculatively. *(Robert, 16 Aug 2026.)*
- **D-7** Performance verification is manual per release: benchmarks run on the reference physical device with results recorded in ADR-002; no CI gating (no self-hosted runner or device farm). Revisit only if regressions slip through. *(Robert, 16 Aug 2026.)*

## Part E — Open questions

None. All questions from revisions 1–2 are closed as decisions D-1..D-7. New questions raised by future ADRs (F-09 gesture resolution, F-18b undo model, F-01 size-ladder steps) are settled within those ADRs.

---

## Appendix — Change history

- **Rev 3 (16 Aug 2026):** Decisions D-4..D-7 recorded (7z+tar.gz replace RAR; hex editing confirmed for v0.6 as F-18b; M18/M19 parked with a requirements-analysis-before-adoption note; manual per-release benchmarking, no CI gating). F-05b benchmark harness added to v0.3 as a prerequisite for F-01..F-03 acceptance. F-25 updated per D-4. All open questions closed.
- **Rev 2 (16 Aug 2026):** Audit corrections — filter view (OE-TXT-4) confirmed implemented; compare find confirmed implemented but partial (F-06 rescoped to extend, not create). F-07 reassigned from filter view to the new editor hex/binary view, with the grid shared forward to F-18. F-09 annotated with the ADR-011 horizontal-scroll gesture conflict and four resolution options. Decisions D-1..D-3 recorded (desktop after v0.4; size ladder accepted; HTML preview and URL deferred). Questions Q-1/Q-2/Q-3/Q-6 closed; Q-8 (hex editing) added.
- **Rev 1 (16 Aug 2026):** Initial audit at `28cbc65` and phased plan v0.3–v1.0.
