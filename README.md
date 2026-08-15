# Omni Editor

An Android editor, compare and merge tool for text files, folders, tabular data and
documents — built for files large enough to defeat ordinary mobile viewers.

Independent product. No affiliation with, and no compatibility claims regarding, any
other editor or compare tool. See `CLAUDE.md` and OE-SPEC-001 §16.

## Layout

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

`core/model` and `core/diff` must never import `android.*` or `androidx.*`.
`./gradlew checkCorePurity` enforces this and runs as part of `check`.

## Build

```bash
tools/verify-environment.sh          # T-00: what can actually be tested here
./gradlew checkCorePurity
./gradlew :core:model:test :core:diff:test
./gradlew assembleDirectDebug        # the flavour that ships first
./gradlew assembleStoreDebug         # kept green for a possible store release
```

Two flavours: `direct` (all-files access, real paths) and `store` (Storage Access
Framework). Both build; only `direct` is distributed today.

## Working on this

Read `CLAUDE.md` first, then `docs/P1-BUILD-PLAN.md`. Work one task at a time; a task is
done when its acceptance criteria pass, not when the code compiles.
