# ADR 015 — Channel-backed piece table for large-file editing

**Status:** accepted — F-03, 17 August 2026.

## Context

ADR-012 defines an INDEXED_READ_ONLY tier (16–256 MiB) backed by
`LargeFileDocument` over a `FileChannel`. F-03 extends this to editable
large files (INDEXED_EDITABLE). The piece table is the product's core data
structure; a channel-backed variant must be provably equivalent to the
in-memory variant.

## Decision

### Byte/char offset mapping

CHANNEL pieces store byte ranges from `LineIndex`. Decode happens at piece
boundaries — each CHANNEL piece is decoded as a whole unit. Piece splits
decode first, find the char boundary, then re-encode to compute the byte
split point. Multi-byte characters are never split across piece boundaries.

A side table `channelRegions: ArrayList<ChannelRegion>` maps piece indices
to `(byteOffset: Long, byteLength: Int)` pairs. A CHANNEL piece's
`Piece.start` is the index into this side table. Its `Piece.length` is the
decoded char count (set after first decode).

### Encoding gate

INDEXED_EDITABLE is gated to UTF-8 and ASCII. Files detected as
UTF-16/UTF-32/other encodings in 16–256 MiB remain INDEXED_READ_ONLY.
Stated decision, not an accident. Gate lifted per-encoding with a recorded
benchmark per ADR-012 ceiling-raise policy.

### External-change fingerprint policy

The disk file is the original buffer. External changes corrupt the piece
structure silently.

- On open: record `FileFingerprint(size, lastModified, contentHash)`.
  Content hash is FNV-1a of first 4 KiB + last 4 KiB.
- Before every `materialise()`: re-check fingerprint. Mismatch →
  `OmniError.ExternallyModified` → spec §13 error state.
- On process resume: re-check fingerprint. Mismatch → prompt user:
  reload (discard edits) or save-as (preserve edits to new path).

### Read strategy

LRU block cache in `ChannelPieceTable`. Cache key is channel region index.
Cache value is the decoded `String`. Capacity: 2048 entries. Eviction is
LRU. CHANNEL pieces in cache remain valid across edits (edits go to the
ADDITIONS buffer). Cache entries invalidated only on piece splits.

### Save path by flavour

- `direct`: temp file + atomic rename. Channel reads old inode while
  streaming to temp. Rename replaces atomically. Channel reopened after save.
- `store` (SAF): copy channel to temp first (SAF `openOutputStream("w")`
  truncates in place). Stream from temp + edits to SAF output. Delete temp
  after successful write.

### Ceiling

Launches at current 16 MiB editor boundary. INDEXED_EDITABLE applies to
16–256 MiB. Raise requires D-2/D-7 benchmark.

## Alternatives considered

1. **Overlay document** (sparse line-range map over read-only channel):
   simpler but line-granular edits are wrong for an editor; undo requires
   reversing overlay patches without piece-table guarantees.
2. **Load-on-edit** (load visible region into PieceTableDocument): simplest
   for small edits but region stitching on save is an edge-case factory.

## Trigger to revisit

When UTF-16 editing or mmap-based read path is needed.
