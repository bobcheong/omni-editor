# ADR 001 — Test environment tiers

**Status:** open — fill in after running `tools/verify-environment.sh`.

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

## Decision
_To be recorded._
