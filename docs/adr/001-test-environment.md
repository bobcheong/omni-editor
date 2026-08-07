# ADR 001 — Test environment tiers

**Status:** decided — probed 7 August 2026.

## Context
The build plan gates tasks on acceptance criteria. Some of those criteria need hardware
that may not be available. Rather than discover this at T-13, classify it once, up front.

| Tier | Needs | Gates |
|---|---|---|
| 1 · JVM unit tests | Java 17 | `:core:model`, `:core:diff` — the golden corpus, ignore rules, diff correctness, 3-way, property tests |
| 2 · Compile, lint, detekt | Android SDK | Everything compiles; both flavours assemble |
| 3 · Instrumented tests | Emulator or device | `SourceProvider` on both flavours, permission flows, Compose UI tests, crash recovery |
| 4 · Macrobenchmarks | Physical device | NFR-P1..P5 — cold start, 250 MB compare, 500k-line scroll, peak heap |

## Deliberate consequence
The engine, the ignore rules, the piece table and the diff correctness suite are all
**Tier 1**. This is not accidental: it is why `core/model` and `core/diff` are pure
Kotlin with no Android imports, enforced by `:checkCorePurity`. The hardest and most
correctness-critical part of the product can be fully verified with nothing but a JVM.

If only Tier 1 and 2 are available:
- T-02 through T-12 proceed at full rigour with no compromise.
- T-05, T-13, T-14, T-17..T-22, T-27 lose their automated gate. Convert each such
  criterion into a manual verification checklist in the task's commit message, and
  mark the requirement "verified manually, pending instrumented test".
- T-28 cannot be completed. Do not fabricate performance numbers — mark NFR-P1..P5
  unverified and carry them as a release blocker.

## Probe results (7 August 2026)

```
Java:        OpenJDK 21.0.11 (2026-04-21)
Android SDK: NOT AVAILABLE (ANDROID_HOME unset)
adb:         NOT AVAILABLE
Emulator:    NOT AVAILABLE
Device:      NOT AVAILABLE
Gradle:      NOT INSTALLED (wrapper generated, Gradle 8.14.1)
```

## Decision

**Only Tier 1 is available in this environment.**

| Tier | Available | Consequence |
|---|---|---|
| 1 · JVM unit tests | YES | `core/model`, `core/diff`, golden corpus, ignore rules, diff engine, piece table, 3-way, property tests — all run at full rigour. |
| 2 · Compile, lint, detekt | NO | Cannot verify Android compilation or run lint/detekt. CI (GitHub Actions) provides Tier 2. |
| 3 · Instrumented tests | NO | `SourceProvider`, permission flows, Compose UI tests, crash recovery — must be verified manually or in CI with an emulator. |
| 4 · Macrobenchmarks | NO | NFR-P1..P5 unverifiable. Do not fabricate numbers; carry as release blockers. |

### Consequences for the task sequence

- **T-02 through T-12** (golden corpus, model, io interfaces, diff engine, piece table):
  proceed at full rigour — these are all Tier 1 pure-Kotlin tests.
- **T-01, T-01a** (skeleton builds): cannot verify locally. Code is structured correctly
  and CI will validate on push.
- **T-05** (SourceProvider both flavours): write the implementation and unit tests for
  the interface contract; instrumented tests deferred to CI or manual verification.
- **T-13, T-14, T-17–T-22, T-27** (rendering, UI, accessibility): acceptance criteria
  converted to manual checklists in commit messages, marked "unverified pending
  instrumented test".
- **T-28** (performance hardening): cannot complete. NFR-P1..P5 marked unverified and
  carried as release blockers requiring a physical device.
- **T-29** (release build): signing and APK generation verified in CI only.
