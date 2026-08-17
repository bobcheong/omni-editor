# ADR 012 — Size ladder replacing ADR-003 cliff

**Status:** accepted — F-01, 16 August 2026. Supersedes ADR-003.

## Context

ADR-003 set hard ceilings (16 MiB editor, 8 MiB compare) as honest limits for
P1. The spec's headline claim (G-2) requires handling files that "make other
editors fail." Decision D-2 reinterprets the absolute targets as a benchmarked
size ladder: ceilings raised stepwise, never silently degraded (OE-ENG-4).

## Decision

| Tier | Size | Behaviour | Disclosed as |
|---|---|---|---|
| FULL_MEMORY | ≤16 MiB | In-memory PieceTableDocument, full editing | *(none)* |
| INDEXED_READ_ONLY | 16–256 MiB | FileIndexer + mmap'd channel, read-only | "Read-only (large file)" |
| REFUSED | >256 MiB | OmniError.TooLarge | Error screen |

Compare uses the same tiers — both sides are tiered independently.

## Ceiling raise policy

Each ceiling raise (e.g. 256 → 512 MiB) requires:
1. A recorded benchmark on the reference device (ADR-002)
2. Heap stays within Android's default 256 MB limit
3. The new ceiling is added to the ADR-002 results table

## Trigger to revisit

When F-03 (large-file editing) lands, INDEXED_READ_ONLY becomes
INDEXED_EDITABLE for a sub-range (64–256 MiB). Update the table then.

## F-03 deferral

Large-file editing (INDEXED_EDITABLE tier) is deferred to v0.5. The v0.4
deliverable is the read-only INDEXED_READ_ONLY tier. Editing requires:

- Piece table over mmap'd original channel
- Materialise-on-save through the atomic write path
- Per-step benchmark to validate ceiling raise per the policy above
- Device measurement (ADR-002/ADR-014)
