# F-05b Benchmark Harness — Design Spec

**Date:** 2026-08-16
**Issue:** #14
**Status:** Approved

## Goal

Create a benchmark harness that measures full-app performance (startup, scroll, compare throughput, peak heap) on a physical device, with deterministic fixture generation and a structured results table. Prerequisite for F-01 through F-03 acceptance (large-file work in v0.3).

## Components

### 1. `:benchmark` module

- Plugin: `com.android.test` targeting `:app`
- Dependency: `androidx.benchmark:benchmark-macro-junit4`
- `benchmark` build type added to `:app` (minified, non-debuggable, profileable)
- Device-agnostic — no hardcoded device expectations

### 2. Benchmark classes

| Class | NFR | Measurement |
|---|---|---|
| `StartupBenchmark` | P1 | Cold start time to home screen |
| `CompareThresholdBenchmark` | P2 | Wall-clock time to compare a 250 MB pair |
| `ScrollBenchmark` | P3 | Frame timing during 500k-line scroll |
| `HeapBenchmark` | P4/P5 | Peak heap via `dumpsys meminfo` after compare load |

Each benchmark uses `@Rule MacrobenchmarkRule` with `CompilationMode.Partial()` (baseline profile when available, interpreted otherwise) and `StartupMode.COLD` where applicable.

### 3. Fixture generator

A Gradle task `generateFixtures` in the `:benchmark` module that produces deterministic test files:

- **250 MB pair:** Two files with ~80% shared content, ~20% differing blocks. Seeded PRNG for reproducibility. Output: `benchmark/build/fixtures/large-left.txt`, `large-right.txt`.
- **500k-line file:** 500,000 lines of deterministic content (line number + padding). Output: `benchmark/build/fixtures/scroll-500k.txt`.

Files are generated locally, then pushed to device via `adb push` before benchmark runs. Never committed to git (output lives in `build/`).

### 4. ADR-002: Performance verification

Records:
- Methodology: macrobenchmark on physical device, manual per release (D-7)
- Fixture specifications (sizes, PRNG seed, content structure)
- Results table: `| Release | Device | OS | NFR | Value | Target | Date |`
- No CI gating; regressions caught by comparing rows across releases

## Build verification (no device required)

- `./gradlew :benchmark:assembleDirectBenchmark` — module compiles
- `./gradlew :benchmark:generateFixtures` — fixture generator runs on JVM

## Actual benchmark execution (physical device required)

```bash
adb push benchmark/build/fixtures/ /sdcard/OmniEditor-bench/
./gradlew :benchmark:connectedDirectBenchmarkAndroidTest
```

## Dependencies added

- `androidx.benchmark:benchmark-macro-junit4` (Apache-2.0) — recorded in `docs/licenses.md`
- `androidx.test.uiautomator:uiautomator` (Apache-2.0) — required by macrobenchmark

## Constraints

- No new module may import from `core/model` or `core/diff` directly (benchmark instruments the app externally via UiAutomator)
- Fixture generator must be deterministic (same seed = same output, byte-identical)
- `benchmark` build type on `:app` must not break existing `debug`/`release` builds
