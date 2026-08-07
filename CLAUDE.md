# Omni Editor — working rules

Read this before every task. The specification is `docs/OE-SPEC-001.html`; the task list
is `docs/P1-BUILD-PLAN.md`.

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
  outside `core/io` and the flavour source sets.
- The editor and both compare panes share one `TextDocument`. Never load a whole file
  into a `String`. Never assume a file fits in memory.
- Long operations are cancellable coroutines scoped to a session, calling `ensureActive()`
  at least every 4096 lines, reporting determinate progress once a total is known.
- **No generic error path.** Every failure maps to an `OmniError` variant and to one named
  UI state in spec §13. Needing an error the sealed interface lacks means adding a variant
  and its UI state in the same change — not widening an existing one.
- Compose only. No XML layouts, no Fragments.
- Both flavours (`direct`, `store`) must build and pass tests before a task is done.

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
2. `./gradlew checkCorePurity` clean.
3. `./gradlew testDirectDebugUnitTest testStoreDebugUnitTest :core:model:test :core:diff:test` green.
4. `./gradlew detekt lintDirectDebug` clean.
5. No new dependency without a line in `docs/licenses.md`.
6. Requirement IDs referenced in the commit message.

## Two hard-won points about this product

- **Sync and merge destroy data.** Anything that writes is previewed, backed up first, and
  undoable. The plan the user approved is the object that executes — never a recomputation.
- **The engine is the product.** It is pure Kotlin so it can be verified on a JVM with no
  device. Keep it that way; it is the reason correctness is testable at all.
