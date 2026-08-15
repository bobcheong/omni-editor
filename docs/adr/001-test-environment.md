# ADR 001 — Test environment tiers

**Status:** updated — R-00b, 12 August 2026.

## Context
The build plan gates tasks on acceptance criteria. Some of those criteria need hardware
that may not be available. Rather than discover this at T-13, classify it once, up front.

| Tier | Needs | Gates |
|---|---|---|
| 1 · JVM unit tests | Java 21 | `:core:model`, `:core:diff`, `:core:io` — data model, diff engine, piece table, encoding, 3-way, property tests |
| 2 · Gradle Managed Devices (instrumented) | Android SDK + emulator image (auto-provisioned by GMD) | `SourceProvider` on both flavours, permission flows, Compose UI tests, crash recovery |
| 3 · Robolectric (JVM Compose semantics) | JVM only — but see decision below | Intermediate UI testing without an emulator |
| 4 · Macrobenchmarks on physical device | Physical device | NFR-P1..P5 — cold start, 250 MB compare, 500k-line scroll, peak heap |

## Deliberate consequence
The engine, the ignore rules, the piece table and the diff correctness suite are all
**Tier 1**. This is not accidental: it is why `core/model` and `core/diff` are pure
Kotlin with no Android imports, enforced by `:checkCorePurity`. The hardest and most
correctness-critical part of the product can be fully verified with nothing but a JVM.

---

## Tier 1 — JVM unit tests

**Status: AVAILABLE. All 445 tests green.**

```
core/model:  43 tests
core/diff:  204 tests
core/io:    198 tests
Total:      445 tests
```

Run via: `./gradlew :core:model:test :core:diff:test :core:io:test`

All T-02 through T-12, and the foundational correctness criteria for T-13 onward, are
fully verified at this tier.

---

## Tier 2 — Gradle Managed Devices

**Status: CONFIGURED. Cannot execute in current environment (no emulator/SDK).**

A Gradle Managed Device named `pixel6api34` is configured in `app/build.gradle.kts`:

- Device: Pixel 6
- API Level: 34
- System image: `aosp-atd` (Automated Test Device — smaller, faster than full images)

Run via: `./gradlew pixel6api34DebugAndroidTest`

GMD will auto-provision the emulator image if the Android SDK and a compatible
emulator are present. In CI (GitHub Actions with `macos-latest` or `ubuntu-latest`
with hardware acceleration), this tier is available.

A sample instrumented smoke test lives at:
`app/src/androidTest/kotlin/com/omnieditor/app/SmokeTest.kt`

It verifies the home screen renders without crashing (`onNodeWithText("Omni Editor").assertIsDisplayed()`).

**Unverified pending CI execution.**

**v0.2.0 coverage note:** GMD coverage at this release is smoke-only
(`SmokeTest.kt` — verifies home screen renders). No instrumented tests
for editor, compare, or settings screens exist yet. Instrumented UI
coverage expansion is planned for a future release.

---

## Tier 3 — Robolectric

**Status: DROPPED.**

**Reason:** Robolectric 4.14.1 (latest as of August 2026) supports up to SDK 35.
This project targets SDK 37 (`compileSdk = 37`, `targetSdk = 37`). Running Robolectric
against SDK 37 would fail at runtime with an unsupported SDK error; there is no
Robolectric shadow set for SDK 37. No Robolectric release that supports SDK 37 exists
at this time.

Additionally, AGP 9.x has deprecated several test runner hooks that Robolectric relies
on for resource merging. The combination of SDK 37 + AGP 9.3.1 makes Robolectric
untenable without significant workarounds that would add fragile build complexity.

**Decision:** Robolectric is not added. Tier 1 (pure JVM) covers the engine completely.
Tier 2 (GMD instrumented) covers UI. The gap is acceptable: there is no correctness
logic in Compose UI components that would benefit from Robolectric testing without
also requiring a full Android runtime.

No dependency has been added. No licence entry is required.

---

## Tier 4 — Macrobenchmarks on physical device

**Status: NOT WIRED. Unverified. Release blocker.**

A dedicated `benchmark` Gradle module has not been created. Macrobenchmarks require
a physical device (or a non-`aosp-atd` emulator with JIT enabled) and the
`androidx.benchmark` library. The performance numbers in NFR-P1..P5 cannot be
fabricated.

**Release blockers:**
- NFR-P1: cold start < 600 ms (physical device required)
- NFR-P2: 250 MB file compare throughput (physical device required)
- NFR-P3: 500k-line scroll at 60 fps (physical device required)
- NFR-P4: peak heap within budget (physical device required)
- NFR-P5: battery/CPU profile (physical device required)

These are carried as explicit release blockers. T-28 (performance hardening) cannot be
marked done until a physical device benchmark run is recorded.

---

## Probe results (7 August 2026, reconfirmed 12 August 2026)

```
Java:             OpenJDK 21.0.11 (2026-04-21)
Android SDK:      NOT AVAILABLE (ANDROID_HOME unset)
adb:              NOT AVAILABLE
Emulator:         NOT AVAILABLE
Physical device:  NOT AVAILABLE
Robolectric:      DROPPED (SDK 37 unsupported, AGP 9.x incompatibility)
```

---

## Tier assignment for all completion plan criteria

### T-00 (repository scaffold, test infrastructure)
- Tier 1: JVM build verification — DONE
- Tier 2: GMD configured — CONFIGURED (unverified pending CI)

### T-01, T-01a (skeleton builds)
- Tier 2: both flavours assemble — verified via CI on push

### T-02 through T-04 (golden corpus, model, interfaces)
- Tier 1: all tests — DONE (445 tests green)

### T-05 (SourceProvider both flavours)
- Tier 1: interface contract unit tests — DONE
- Tier 2: instrumented tests for real filesystem access — unverified pending CI

### T-06 through T-12 (diff engine, piece table, io, merge)
- Tier 1: all correctness tests — DONE

### T-13, T-14 (rendering, adaptive layout)
- Tier 2: Compose UI instrumented tests — unverified pending instrumented test
- Manual checklist in commit message; marked "verified manually, pending instrumented test"

### T-17 through T-22 (compare UI, merge UI, editor)
- Tier 2: Compose UI instrumented tests — unverified pending instrumented test
- Manual checklist in commit messages

### T-27 (accessibility)
- Tier 2: TalkBack / semantics tests — unverified pending instrumented test

### T-28 (performance hardening)
- Tier 4: macrobenchmarks on physical device — CANNOT COMPLETE
- NFR-P1..P5 unverified; carried as release blockers

### T-29 (release build)
- Tier 2: signing and APK generation — verified via CI only

---

## Decision summary

| Tier | Available locally | Available in CI | Action |
|---|---|---|---|
| 1 · JVM unit tests | YES | YES | Full rigour; 445 tests green |
| 2 · GMD instrumented | NO | YES | Configured; run via CI or `pixel6api34DebugAndroidTest` |
| 3 · Robolectric | NO | NO | Dropped — SDK 37 + AGP 9.x incompatible |
| 4 · Macrobenchmarks | NO | NO | Not wired; manual on physical device; release blocker |
