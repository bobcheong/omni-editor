# ADR-007: Line Model -- newlines + 1

## Status
Accepted

## Context
Three line-counting conventions coexisted in the codebase:
- `LineIndex`: dropped the trailing empty line for files ending in a terminator
- `PieceTable`: kept it (`newlines + 1`)
- `String.lines()` (Kotlin stdlib): keeps the trailing element (matches `newlines + 1`)
- `File.readLines()` / `BufferedReader.readLine()`: drops the trailing element

For a diff engine, dropping the trailing line is defensible. For an editor, it is a
showstopper: a file ending in `\n` must have a caret-placeable empty final line, and
`"a"` vs `"a\n"` must differ in the diff.

## Decision
Line count is `newlines + 1` everywhere. A file ending in a terminator has a real,
caret-placeable empty final line. `LineIndex` was changed to match `PieceTable`.

## Consequences
- `"a\n"` is 2 lines: `"a"` and `""`. The diff of `"a"` vs `"a\n"` is a one-line addition.
- `tailSkip = 1` on a newline-terminated file skips the empty trailing line, not the last
  content line. Users setting tailSkip to skip meaningful content must account for this.
- `File.readLines()` must not be used as the line splitter for diff/merge input. Use
  `String.lines()` or a custom `splitLines()` utility matching the model instead.
- The golden test corpus was regenerated under this model: the `no-trailing-newline` case
  now correctly shows one ADDED hunk for the trailing empty line.

## Alternatives Considered
- Keep `LineIndex` semantics and change `PieceTable`: rejected because the editor needs
  the trailing line.
- Add a `noTrailingNewline` flag: rejected because the `newlines + 1` model handles it
  naturally.

## Trigger to Revisit
If a performance-critical path needs `LineIndex` to exclude the trailing line for indexing
efficiency, the index can store it internally while exposing `newlines + 1` to callers.
