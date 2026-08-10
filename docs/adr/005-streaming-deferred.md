# ADR-005: Streaming Compare Deferred

## Status
Accepted

## Context
`DiffEngine.compareStreaming` was designed to emit hunks as they were found,
with the first hunk emitted before the compare completed (OE-ENG-7). In
practice, it computed every hunk then emitted them sequentially — the
"streaming" claim was never true.

Decision D-2 (honest size ceiling) caps documents at `DocumentLimits.EDITOR_MAX_BYTES`.
A full in-ceiling compare completes fast enough that time-to-first-hunk is not
a distinct performance budget. Streaming is unnecessary for P1.

## Decision
`compareStreaming` is removed from the public API. The function signature is
preserved below so it can be resurrected deliberately if needed:

```kotlin
fun compareStreaming(
    leftLineCount: Long,
    rightLineCount: Long,
    leftLine: (Long) -> CharSequence,
    rightLine: (Long) -> CharSequence,
    rules: RuleSet = RuleSet.DEFAULT,
    progress: ((Progress) -> Unit)? = null,
): Flow<Hunk>
```

## Consequences
- OE-ENG-7 is amended: the requirement is a full-compare latency budget
  for the largest in-ceiling file pair, not a first-hunk latency budget.
- Spec §11's first-hunk latency budget is replaced by a full-compare budget.
- No code path claims streaming behaviour.

## Alternatives Considered
- Implement true streaming from within the histogram recursion: deferred to P2.
  The recursion structure makes partial emission complex, and D-2's ceiling
  removes the urgency.
- Deprecate instead of delete: rejected. `@Deprecated` preserves the trap —
  it stays callable, deprecation warnings get suppressed. There is one
  in-repo consumer, so there is no compatibility argument.

## Trigger to Revisit
If the size ceiling is raised beyond the point where full-compare latency
exceeds 2 seconds on the reference device, streaming becomes necessary.
