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
| INDEXED_EDITABLE | 16–256 MiB (UTF-8/ASCII) | ChannelPieceTable over FileChannel, full editing | *(none)* |
| INDEXED_READ_ONLY | 16–256 MiB (other encodings) | FileIndexer + mmap'd channel, read-only | "Read-only (large file)" |
| REFUSED | >256 MiB | OmniError.TooLarge | Error screen |

Compare uses the same tiers — both sides are tiered independently.

## Ceiling raise policy

Each ceiling raise (e.g. 256 → 512 MiB) requires:
1. A recorded benchmark on the reference device (ADR-002)
2. Heap stays within Android's default 256 MB limit
3. The new ceiling is added to the ADR-002 results table

## Trigger to revisit

When the editable ceiling is raised beyond 256 MiB, a recorded benchmark on
the reference device is required per the ceiling raise policy above.

## F-03 landing (v0.5 pre-KMP rider)

Large-file editing (INDEXED_EDITABLE tier) landed in v0.5. UTF-8/ASCII files
in the 16–256 MiB range are opened via `LargeFileEditableDocument`
(ChannelPieceTable + atomic save). Other encodings remain INDEXED_READ_ONLY.
See ADR-015 for the encoding gate decision.

## F-10 deferral

Word-level merge UI (OE-MRG-2, W-05) is deferred to v0.5. The engine
(`MergeEngine.mergeWordLevel()` + `WordMerge`) is implemented and tested;
the active-line sheet UI for word-level accept is not built. Both F-03
and F-10 concentrate in the editor/active-line surface and should land
before the KMP `expect/actual` split touches those files.
