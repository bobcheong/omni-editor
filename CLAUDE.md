# Omni Editor — working rules

Read this before every task. The specification is `docs/OE-SPEC-001.html`; the completion
plan is `docs/P1-COMPLETION-PLAN.md` (v3, authoritative). Forward plan:
`docs/SPEC-GAP-PLAN.md` (rev 5, v0.3–v1.0). Change log: `CHANGES.md`.

## Independence (non-negotiable)

This product has no relationship to any existing compare or editor tool or vendor.

- No third-party product or company name appears in code, comments, strings, package IDs,
  assets, commit messages or documentation.
- No "compatible with", "alternative to" or "works like" claims anywhere.
- No third-party configuration formats are read or written. Themes, syntax grammars,
  ignore rules, sync rules and session definitions use schemas designed for this app.
- Dependency licences: Apache-2.0, MIT and BSD are fine. LGPL needs written justification
  and dynamic linking. **GPL and AGPL are forbidden.** Record every dependency and its
  licence in `docs/licenses.md` in the same commit that adds it.
- Implement from the specification and from public algorithm literature (Myers, histogram
  diff, diff3, piece tables). Never from a specific product's implementation. If generated
  code appears to reproduce an identifiable third-party implementation, rewrite it.

## Architecture rules

- `core/model` and `core/diff` must not import `android.*` or `androidx.*`.
  `./gradlew checkCorePurity` enforces this and is wired into `check`.
- All file access goes through `SourceProvider`. No `java.io.File`, no `ContentResolver`
  outside `core/io` and the flavour source sets. `OmniNavGraph` split into
  `EditorCoordinator`, `CompareCoordinator`, and `OmniNavGraph` (route declarations).
- The editor and both compare panes share one `TextDocument`. Editor has Save and
  Save As (via `CreateDocument` picker). "Compare with…" prefills setup from the
  current file and clears stale state from prior sessions.
- Documents are tiered by size via `DocumentLimits.SizeTier`: FULL_MEMORY (≤16 MiB,
  full editing), INDEXED_READ_ONLY (16–256 MiB, read-only via `LargeFileDocument`),
  REFUSED (>256 MiB, `OmniError.TooLarge`). **No code path may be O(file) per
  keystroke or per rendered row.** See `docs/adr/012-size-ladder.md`.
- Line count is `newlines + 1`. A file ending in a terminator has a real, caret-placeable
  empty final line. See `docs/adr/007-line-model.md`.
- Long operations are cancellable coroutines scoped to a session, calling `ensureActive()`
  at least every 4096 lines, reporting determinate progress once a total is known.
- **No generic error path.** Every failure maps to an `OmniError` variant and to one named
  UI state in spec §13. Needing an error the sealed interface lacks means adding a variant
  and its UI state in the same change — not widening an existing one.
- Compose only. No XML layouts, no Fragments.
- Both flavours (`direct`, `store`) must build and pass tests before a task is done.
- No UI control may exist without behaviour. A menu item, button or switch that does
  nothing is a defect, not a placeholder.
- No production code uses reflection to reach private state.
- Every UI task's acceptance criteria include semantics, touch-target size and contrast.
  Accessibility is not a later phase.
- State the test tier for every criterion. Never assert a test passed on a tier this
  environment does not have.

## Working method

- One task at a time, in the order given in the build plan.
- A task is done when its acceptance criteria pass — not when the code compiles.
- Commit messages reference requirement IDs:
  `feat(diff): streaming histogram diff [OE-ENG-1, OE-ENG-7]`
- Tests land in the same commit as the code they test.
- Deviating from spec §10? Write `docs/adr/NNN-title.md` first, then deviate.
- Do not add a dependency to solve something under ~200 lines. Do not add one without
  checking its licence.
- If an acceptance criterion cannot be verified in this environment (see
  `docs/adr/001-test-environment.md`), say so explicitly in the commit and mark the
  requirement unverified. Never assert a test passed that was not run, and never
  fabricate a performance number.

## Definition of done

1. Acceptance criteria in the build plan pass.
2. `./gradlew checkCorePurity checkIoBoundary` clean.
3. `./gradlew testDirectDebugUnitTest testStoreDebugUnitTest :core:model:test :core:diff:test` green.
4. `./gradlew detekt lintDirectDebug` clean.
5. No new dependency without a line in `docs/licenses.md`.
6. Requirement IDs referenced in the commit message.
7. If a task names an ADR, the ADR file exists, states the decision, the alternatives
   and the trigger to revisit, and is referenced in the commit. A task naming an ADR is
   not done without it.

## Two hard-won points about this product

- **Sync and merge destroy data.** Anything that writes is previewed, backed up first, and
  undoable. The plan the user approved is the object that executes — never a recomputation.
- **The engine is the product.** It is pure Kotlin so it can be verified on a JVM with no
  device. Keep it that way; it is the reason correctness is testable at all.
