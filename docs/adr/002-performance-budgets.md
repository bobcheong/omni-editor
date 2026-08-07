# ADR 002 — Performance budgets

**Status:** unverified — Tier 4 (physical device) required.

## Budgets from spec §11

| ID | Requirement | Target | Status |
|---|---|---|---|
| NFR-P1 | Cold start to interactive Home | < 1.2s on mid-range 2024 device | UNVERIFIED |
| NFR-P2 | Compare two 5MB text files, first diff visible | < 1.5s | JVM: passes in <30s |
| NFR-P3 | Compare two 250MB text files, complete | < 45s, progress from 500ms | UNVERIFIED |
| NFR-P4 | Scroll 500k-line diff | No frame over 16ms at 60Hz | UNVERIFIED |
| NFR-P5 | Peak heap during any compare | < 40% of device per-app limit | UNVERIFIED |

## JVM verification (Tier 1)

The following are verified on the JVM and give confidence that the
algorithms are sound, even though the actual device budgets cannot
be verified without hardware:

- 5MB compare (50k lines, 50 changes): completes in <30s on JVM
- 20k lines with ignoreCase normalisation: completes in <30s
- Block diff on 10k lines: completes in <30s
- Intra-line word diff: <1ms per call (1000 iterations)

## Release blocker

NFR-P1..P5 are carried as release blockers. Before shipping:

1. Set up a macrobenchmark module with `androidx.benchmark:benchmark-macro-junit4`
2. Run on a reference mid-range device (e.g., Pixel 7a or equivalent)
3. Verify all five budgets
4. Wire benchmarks into CI to gate the build

## Architecture choices supporting performance

- **LazyColumn** for editor and compare: only visible lines composed
- **Canvas-based text rendering**: avoids Text widget layout overhead
- **Piece table**: edits don't copy the original buffer
- **Line index with FNV-1a hashing**: O(1) line equality checks
- **Block mode**: large files diffed in chunks, not all-in-memory
- **Streaming hunks**: first difference visible before compare finishes
- **Normalisation on hash input only**: original text never copied
