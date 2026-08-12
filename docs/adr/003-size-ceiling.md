# ADR-003: Document Size Ceiling

## Status
Accepted

## Context
The original spec (OE-SPEC-001 §11) promised streaming operations on files
up to 300 MB. The current implementation holds documents in memory: the
piece table stores the full text, `LineIndex` stores per-line offsets and
hashes, and the diff engine operates on in-memory arrays. At 4–6× file size
in heap (String + per-line rows + hashes), a 300 MB file would require
1.2–1.8 GB of heap — far exceeding a mid-range device's ~192–256 MB budget.

Rather than ship with unmet performance claims, P1 states an honest ceiling
and refuses gracefully above it.

## Decision
- `DocumentLimits.EDITOR_MAX_BYTES = 16 MiB`
- `DocumentLimits.COMPARE_MAX_BYTES_PER_SIDE = 8 MiB`
- `DocumentLimits.MAX_LINE_BYTES = 1 MiB`
- Files above the ceiling are refused with `OmniError.TooLarge` and the
  over-threshold UI state, with an escape hatch: read-only preview of the
  first N lines.

These are starting values, to be confirmed or lowered by R-38's memory
benchmark. If peak heap at 16 MiB exceeds budget on the reference device,
the constant is lowered — the budget is not raised.

## What Would Lift It
- Piece-tree offset cache (R-13) enabling O(log p) line access
- `LineIndex`-backed reads instead of full-text materialisation
- Block-mode compare for files above the standard threshold
- Streaming diff emission from within the histogram recursion

## Alternatives Considered
- Ship without a ceiling and hope for the best: rejected. Unmet claims
  are worse than honest limits.
- Set a very low ceiling (1 MiB): rejected. Too restrictive for a
  code editor; most source files are under 16 MiB.

## Trigger to Revisit
R-38 results. If the ceiling can be raised after measuring, raise it.
If it must be lowered, lower it and open a P2 task for the offset cache
and LineIndex read path.

## R-38 Budget Status (T-28)

JVM budget reference tests were written in
`core/io/src/test/kotlin/com/omnieditor/core/io/PerformanceBudgetTest.kt`.

These tests print measured values but make no hard assertions, because JVM
measurements are not authoritative for device performance.

What the JVM tests cover:

| Budget | Test name | Authority |
|--------|-----------|-----------|
| 16 MiB piece table creation time | `budget reference - 16MiB piece table creation time` | JVM reference only |
| 16 MiB heap delta and ratio | `budget reference - 16MiB document heap estimate` | JVM reference only |
| Typing latency: 1000 inserts in 15 MB doc | `budget reference - typing latency 1000 inserts in 15MB document` | JVM reference only |
| line(500000) vs line(10) access ratio | `budget reference - line access at line 500000 vs line 10` | JVM reference only |
| 5 MB compare line-access throughput | `budget reference - 5MB compare line access throughput` | JVM reference only |
| Intra-line 4 KB line median < 1 ms | `budget reference - intra-line range under 1ms for 4KB line` | JVM reference only |

Device benchmarks (NFR-P1..P5) remain open Tier 4 release blockers.
`DocumentLimits` values are NOT changed based on JVM data alone.

### Monospace fast path (R-14)

R-14 specified: "No monospace fast path yet — add only if R-38 shows
measurement is the bottleneck." Since no device profiling has been run,
the decision is deferred: JVM measurements do not indicate that
`TextMeasurer` is a bottleneck at the current ceiling. Revisit if device
profiling shows it is.
