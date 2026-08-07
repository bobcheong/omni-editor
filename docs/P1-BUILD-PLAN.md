# Omni Editor — P1 Build Plan

Companion to `omni-editor-spec.html` (OE-SPEC-001 v1.1). That document is *what* and *why*; this one is *in what order* and *done when*.

**Target:** Android 12 (API 31) minimum, targetSdk current. **Package:** `com.omnieditor` (`.store` suffix for the store flavour).

**P1 scope:** text compare (2-way and 3-way), merge, and the standalone editor — modules M1 (local/share/URL/snippet sources only), M2, M3, M4, M5, M13, M14, M15, M16.
**Out of P1:** folder compare, sync, remote protocols, archives, Git, binary, documents, tables, AI. Do not scaffold them beyond leaving the module boundaries clean.

---

## 0. Repository scaffold

```
omni-editor/
├── CLAUDE.md                    # non-negotiables (§ below) — read before every task
├── docs/
│   ├── OE-SPEC-001.html         # the specification
│   ├── P1-BUILD-PLAN.md         # this file
│   └── adr/                     # one file per architecture decision that deviates from spec §10
├── gradle/libs.versions.toml    # version catalogue — all dependency versions here, nowhere else
├── settings.gradle.kts
├── app/                         # navigation, DI wiring, entry points, flavours
├── core/
│   ├── model/                   # pure Kotlin: Session, SourceRef, RuleSet, CompareResult, OmniError
│   ├── diff/                    # pure Kotlin, NO Android imports: DiffEngine, normalisation
│   └── io/                      # SourceProvider, TextDocument, line index, encoding detection
├── feature/
│   ├── home/
│   ├── setup/                   # source setup screen S-02
│   ├── compare/                 # S-03 text compare + merge
│   └── editor/                  # S-04 editor
├── design/                      # theme, compare colour tokens, shared components
└── testfixtures/
    └── golden/                  # the corpus — created in T-02, before the engine exists
```

Flavours: `direct` (MANAGE_EXTERNAL_STORAGE, path-based) and `store` (SAF). Both must compile and pass tests from T-04 onward.

---

## 1. CLAUDE.md contents

Put this at the repo root verbatim; it is the file that keeps 40 tasks coherent.

```markdown
# Omni Editor — working rules

## Independence (non-negotiable)
This product has no relationship to any existing compare or editor tool or vendor.
- No third-party product name appears in code, comments, strings, package IDs, assets or docs.
- No "compatible with" / "alternative to" claims anywhere.
- No third-party config formats are read or written. Themes, grammars and rules use our own schemas.
- Dependency licences: Apache-2.0 / MIT / BSD only. LGPL needs justification. GPL and AGPL are forbidden.
  Record every dependency and its licence in docs/licenses.md when you add it.
- Implement from the spec and from public algorithm literature. Never from a specific product's implementation.

## Architecture rules
- `core/diff` and `core/model` must not import anything from `android.*` or `androidx.*`. Enforced by a CI check.
- All file access goes through `SourceProvider`. No `java.io.File` or `ContentResolver` outside `core/io` and the flavour source sets.
- The editor and both compare panes use one `TextDocument`. Never load a whole file into a String.
- Long operations are cancellable coroutines scoped to a session, checking `ensureActive()` at least every 4096 lines.
- No generic error path. Every failure maps to an `OmniError` variant and to one named UI state from spec §13.
  If you need an error the sealed interface lacks, add a variant and its UI state in the same change.
- Compose only. No XML layouts, no Fragments.

## Working method
- One task at a time from docs/P1-BUILD-PLAN.md. A task is done when its acceptance criteria pass, not when the code compiles.
- Commit messages reference requirement IDs: `feat(diff): streaming histogram diff [OE-ENG-1, OE-ENG-7]`.
- Tests land in the same commit as the code they test.
- Deviating from spec §10? Write docs/adr/NNN-title.md first, then deviate.
- Both flavours (`direct`, `store`) must build and pass tests before a task is done.
- Do not add a dependency to solve something under ~200 lines. Do not add a dependency without checking its licence.

## Definition of done for any task
1. Acceptance criteria in the build plan pass.
2. Unit tests written and passing; `./gradlew testDirectDebugUnitTest testStoreDebugUnitTest` green.
3. `./gradlew lint detekt` clean.
4. No new dependency without a line in docs/licenses.md.
5. Requirement IDs referenced in the commit.
```

---

## 2. Task sequence

Ordered so nothing is built on an unverified foundation. Each task states its acceptance criteria; those are the tests.

### Foundation

**T-00 · Environment probe** — run `tools/verify-environment.sh` and complete `docs/adr/001-test-environment.md`.
Classifies what can actually be tested here into four tiers: JVM unit tests (Java only), compile/lint (Android SDK), instrumented tests (emulator or device), macrobenchmarks (physical device only).
*Done when:* the ADR records which tiers are available. Tasks whose criteria need an unavailable tier get a manual checklist instead — and are marked unverified, never assumed passing.

**T-01 · Project skeleton** — *scaffold provided; verify and commit.*
Gradle with version catalogue, both flavours, module structure above, Hilt, Compose, detekt, CI running `assemble` + `test` for both flavours.
*Done when:* both flavours install and show the empty Home; `./gradlew checkCorePurity` passes and fails correctly when an `android.*` import is added to `core/diff`; `:core:model:test` is green; CI is green.

**T-01a · Version check** — before the first build, verify every version in `gradle/libs.versions.toml` against current releases. AGP, Kotlin, KSP and the Compose compiler plugin must be mutually compatible; the catalogue was pinned at scaffold time and is likely behind. Bump as one commit, then re-run T-01's criteria.
*Done when:* a clean `./gradlew assembleDirectDebug assembleStoreDebug` succeeds with no version-compatibility warnings.

**T-02 · Golden corpus** — before any engine code.
Build `testfixtures/golden/` with pairs and expected hunk sets as JSON: identical files, single-line change, insertion at head, insertion at tail, whole file replaced, CRLF vs LF, UTF-8 vs UTF-16LE with BOM, no trailing newline, empty vs non-empty, 1-byte files, 10k identical lines with one change in the middle, reordered blocks, files differing only in trailing whitespace, files differing only in case, a minified JS line 400 KB long, and a generated 300 MB log with 12 known changes.
*Done when:* the corpus loads in a test harness that can assert `expected == actual` once an engine exists; the 300 MB file is generated by script, not committed.

**T-03 · `core/model`**
All data classes and the `OmniError` sealed interface from spec §10.
*Done when:* serialisation round-trips in tests; no Android imports.

**T-04 · `core/io` — line index and readers** `[OE-SRC-5, OE-ENG-4]`
Memory-mapped reader, line index `[offset, length, hash]`, encoding detection (BOM, UTF-8 validation, heuristic for common 8-bit pages), line-ending detection.
*Done when:* indexing a 300 MB file uses under 60 MB heap (asserted by a memory test); encoding detection passes a fixture table; index build is cancellable and reports progress.

**T-05 · `SourceProvider`, both flavours** `[OE-SRC-1, OE-SRC-2, DIST-1, DIST-2]`
`direct` = path-based with all-files access and a permission rationale screen. `store` = SAF with persistable grants.
*Done when:* the same instrumented test suite passes against both implementations; a session reference survives app restart in both; revoked access surfaces `AccessRevoked`, not a crash.

### Diff engine

**T-06 · Normalisation and ignore rules** `[OE-ENG-5, OE-ENG-6]`
Applied to hash input only. Case, whitespace variants, blank lines, line terminators, begins/contains/ends-with, between-markers, head/tail skip, column ranges.
*Done when:* property test holds — enabling any ignore rule never increases hunk count; each rule has a golden pair proving it suppresses the intended difference and nothing else.

**T-07 · Histogram diff** `[OE-ENG-1, OE-ENG-7]`
Line-level hunks, streaming emission, cancellable, chunked, progress.
*Done when:* the whole golden corpus passes; results stream (first hunk emitted before completion, asserted); cancellation mid-run leaves no coroutine leak; differential test against `git diff` on 50 real source-file pairs shows no semantic divergence.

**T-08 · Intra-line ranges** `[OE-ENG-1]`
Word and character granularity, computed lazily per visible row.
*Done when:* golden pairs assert exact ranges; computing ranges for one row is under 1 ms for lines up to 4 KB.

**T-09 · 3-way diff3** `[OE-ENG-2]`
*Done when:* conflict/non-conflict classification matches a fixture set of merge scenarios including both-changed-identically and adjacent-change cases.

**T-10 · Block mode for large files** `[OE-ENG-4]`
Rolling-hash region location above the 32 MB threshold, then full diff within regions.
*Done when:* the 300 MB fixture completes in under 45 s on a mid-range device, finds all 12 known changes, and the engine mode is reported in the result rather than applied silently.

**T-11 · Result store**
Cache to disk by session ID; survive process death.
*Done when:* a compare killed at 80 % and reopened restores the result without recomputing.

### Editor (spec M5, screen S-04)

**T-12 · `TextDocument` piece table** `[OE-EDT-3, OE-EDT-9]`
Edits as a piece list; materialise only on save; continuous journalling.
*Done when:* property test — a random sequence of 10 000 edits followed by full undo yields a byte-exact original; a killed process restores every open buffer exactly; editing a 300 MB file stays under the heap budget.

**T-13 · Editor rendering**
Custom lazy list over the line index. Not a `TextField`.
*Done when:* scrolling a 500k-line file shows no frame over 16 ms in a macrobenchmark; caret placement and selection are correct at any scroll position.

**T-14 · Editor chrome** `[OE-EDT-1, OE-EDT-2, OE-EDT-6]`
Tabs, gutter, status strip, programmer key row, IME handling, save / save-as, read-only state.
*Done when:* screen S-04's state list is exercised by UI tests: read-only source, changed-on-disk, undetectable encoding, over-threshold file, crash recovery banner.

**T-15 · Find and replace** `[OE-EDT-4, OE-FND-1]`
Case, whole word, regex with capture groups, replace-all with count, find-in-selection.
*Done when:* regex replace on a 50 MB file completes without loading it; replace-all is a single undo step.

**T-16 · Text tools and navigation** `[OE-EDT-5, OE-EDT-7, OE-EDT-8]`
Sort, dedupe, trim, case, tabs/spaces, line endings, encoding conversion, join/split, go-to-line, bookmarks, column select.
*Done when:* each operation is one undo step; encoding conversion round-trips.

### Compare UI (spec M3/M4, screen S-03)

**T-17 · Unified diff view** `[OE-TXT-1, OE-TXT-2, OE-TXT-9]`
Phone default. Gutter glyphs `+ − ~` alongside colour.
*Done when:* TalkBack announces side and change type for every row; colour is never the only signal; difference counter and navigation are correct at file boundaries.

**T-18 · Split view and adaptive layout** `[OE-TXT-1, OE-TXT-6]`
Width-driven at 600dp, synchronised scrolling, relational connectors, flip sides.
*Done when:* rotation and foldable fold/unfold preserve scroll position and selection.

**T-19 · Minimap, filters, active-line sheet** `[OE-TXT-3, OE-TXT-4, OE-TXT-7]`
*Done when:* dragging the rail seeks proportionally on a 500k-line file without jank; filter modes recompute without re-running the compare.

**T-20 · Syntax highlighting** `[OE-TXT-5, IND-3]`
Own JSON grammar format; 12 languages in P1 (Kotlin, Java, JS/TS, Python, Go, Rust, C/C++, shell, YAML, JSON, XML/HTML, SQL, Markdown); progressive application.
*Done when:* highlighting never delays first paint (asserted by a startup trace); an unknown extension degrades to plain text silently.

**T-21 · Merge** `[OE-MRG-1..4]`
Block, line, selection and word-level merge; accept-all with a counted confirmation; unlimited in-session undo.
*Done when:* property test — applying all left-to-right merges makes the files identical under the active rule set; undo restores byte-exact state at every step.

**T-22 · Merge safety** `[OE-MRG-5, OE-MRG-6, OE-MRG-7]`
Pre-write backup, restore-original, dirty-state prompts, external-change detection on resume.
*Done when:* a backup exists before the first byte is written; a file modified underneath produces the reload banner, never a silent overwrite.

### Shell

**T-23 · Sources and entry points** `[OE-SRC-3, OE-SRC-4, OE-SRC-6]`
Source setup screen S-02, recents and favourites, share-sheet ingestion (`ACTION_SEND`, `SEND_MULTIPLE`, plain text), `ACTION_VIEW`, app shortcuts, snippet/paste compare, URL source.
*Done when:* sharing two files opens a compare directly; sharing one prompts for the second; sharing text opens snippet compare; all from a cold start.

**T-24 · Home and sessions** `[OE-SES-1..3, screen S-01]`
Three tabs, session persistence, tab strip shared between documents and compares, memory-pressure eviction without losing unsaved edits.
*Done when:* ten concurrent sessions survive a low-memory kill with all unsaved edits intact.

**T-25 · Export and reports** `[OE-RPT-1..4]`
HTML (unified and side-by-side), unified diff patch, plain-text summary, PDF via the print pipeline. Header records sources, timestamp, applied rules and engine mode.
*Done when:* a generated patch applies cleanly with `git apply`.

**T-26 · Theming and appearance** `[OE-APP-1..3]`
Light, dark, high-contrast, colour-vision-safe pairs; own JSON theme schema; dynamic colour; font scale to 200 %.
*Done when:* every diff colour pair meets 4.5:1; the app is usable at 200 % font scale with no clipped text.

**T-27 · Accessibility and input pass** `[NFR-A1, NFR-A2, OE-INP-1..3]`
TalkBack pass on editor and compare, full keyboard operation, remappable shortcuts, reduced-motion.
*Done when:* every function is reachable without gestures; automated a11y checks clean; a manual TalkBack script passes.

**T-28 · Performance hardening** `[NFR-P1..P5]`
Macrobenchmarks in CI for cold start, 5 MB compare, 250 MB compare, 500k-line scroll, peak heap.
*Done when:* all five budgets from spec §11 are met on the reference mid-range device and the benchmarks gate the build.

**T-29 · Release build**
Release keystore (generated once, stored outside the repo), signed APK for both flavours, `licenses.md` complete, in-app licences screen, versioning scheme, install-over-previous verified.
*Done when:* a signed `direct` APK installs over the prior version preserving all sessions and documents.

---

## 3. Suggested execution order for Claude Code

Sequential, because most tasks depend on the previous ones:

```
T-00 → T-01 → T-01a → T-02 → T-03 → T-04 → T-05   foundation
T-06 → T-07 → T-08 → T-09 → T-10 → T-11 engine  (T-08/T-09 can interleave)
T-12 → T-13 → T-14 → T-15 → T-16        editor
T-17 → T-18 → T-19 → T-20 → T-21 → T-22 compare UI
T-23 → T-24 → T-25 → T-26               shell
T-27 → T-28 → T-29                      hardening and release
```

Two checkpoints worth stopping at for human review rather than continuing straight through:
- **After T-11.** The engine is the product. Run the corpus, run the differential test against `git diff`, look at real diffs of your own files before any UI exists.
- **After T-22.** Everything destructive is now implemented. Review merge safety by hand before the app is used on files that matter.

---

## 4. Risk register for P1

| Risk | Signal it is happening | Response |
|---|---|---|
| Piece table plus line index is the hardest part of the build | T-12 property tests keep finding edge cases | Timebox; fall back to a simpler gap buffer with a documented size ceiling and an ADR |
| Custom text rendering underperforms | T-13 macrobenchmark misses 16 ms | Reduce scope to fixed-width fonts in P1; revisit proportional rendering later |
| Heap budget missed on low-RAM devices | T-04 or T-10 memory tests fail | Lower the block-mode threshold and make it device-aware rather than fixed at 32 MB |
| Test tier unknown at start | T-13/T-28 criteria cannot be run | T-00 classifies this up front; engine tasks are Tier 1 by design and proceed regardless |
| Scope creep into P2 | Folder or remote code appears in `feature/` | The module list above is the boundary; anything else needs a decision, not a commit |
| Dependency licence surprise | A library is GPL or unclear | Reject before use; `docs/licenses.md` is updated in the same commit that adds any dependency |
