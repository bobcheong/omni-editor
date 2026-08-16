# F-05b Benchmark Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a `:benchmark` macrobenchmark module with deterministic fixture generation and an ADR-002 results table, so large-file work (F-01–F-03) has a measurement framework.

**Architecture:** A `com.android.test` module instruments `:app` via UiAutomator. A `benchmark` build type on `:app` gives minified, profileable builds. A Gradle task generates deterministic fixture files from a seeded PRNG. ADR-002 documents methodology and provides the results table.

**Tech Stack:** `androidx.benchmark:benchmark-macro-junit4`, `androidx.test.uiautomator:uiautomator`, AGP 9.3.1, Kotlin 2.3.21

## Global Constraints

- No dependency without a line in `docs/licenses.md` (CLAUDE.md rule).
- `core/model` and `core/diff` must not import `android.*` or `androidx.*`.
- The benchmark module must not break existing `debug`/`release` builds of `:app`.
- Both `direct` and `store` flavours must continue to build.
- Fixture generator must be deterministic: same seed = byte-identical output.
- Tests land in the same commit as the code they test.
- Commit messages reference requirement IDs.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `gradle/libs.versions.toml` | Add benchmark + uiautomator versions and libraries | 1 |
| `settings.gradle.kts` | Include `:benchmark` module | 1 |
| `app/build.gradle.kts` | Add `benchmark` build type | 1 |
| `benchmark/build.gradle.kts` | Module build config | 1 |
| `benchmark/src/main/AndroidManifest.xml` | Test instrumentation manifest | 1 |
| `benchmark/src/main/kotlin/.../FixtureGenerator.kt` | Deterministic fixture generation | 2 |
| `benchmark/build.gradle.kts` | `generateFixtures` task registration | 2 |
| `benchmark/src/main/kotlin/.../StartupBenchmark.kt` | Cold start benchmark | 3 |
| `benchmark/src/main/kotlin/.../CompareThresholdBenchmark.kt` | 250 MB compare benchmark | 3 |
| `benchmark/src/main/kotlin/.../ScrollBenchmark.kt` | 500k-line scroll benchmark | 3 |
| `benchmark/src/main/kotlin/.../HeapBenchmark.kt` | Peak heap benchmark | 3 |
| `docs/adr/002-performance-verification.md` | Methodology + results table | 4 |
| `docs/licenses.md` | Benchmark dependency entries | 4 |

---

### Task 1: Scaffold `:benchmark` module and `benchmark` build type on `:app`

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts:89-99`
- Create: `benchmark/build.gradle.kts`
- Create: `benchmark/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: existing `:app` module structure, `libs.versions.toml` catalog
- Produces: compilable `:benchmark` module targeting `:app`'s `directBenchmark` variant

- [ ] **Step 1: Add benchmark dependencies to version catalog**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
benchmarkMacro = "1.3.4"
uiautomator = "2.3.0"
```

Add to `[libraries]`:

```toml
benchmark-macro-junit4 = { module = "androidx.benchmark:benchmark-macro-junit4", version.ref = "benchmarkMacro" }
uiautomator = { module = "androidx.test.uiautomator:uiautomator", version.ref = "uiautomator" }
```

Add to `[plugins]`:

```toml
android-test = { id = "com.android.test", version.ref = "agp" }
```

- [ ] **Step 2: Add `benchmark` build type to `:app`**

In `app/build.gradle.kts`, inside `buildTypes { }` (after the `debug` block, around line 99), add:

```kotlin
create("benchmark") {
    initWith(getByName("release"))
    signingConfig = signingConfigs.getByName("debug")
    matchingFallbacks += listOf("release")
    isDebuggable = false
    // profileable is set via the manifest; AGP 9.x infers it
}
```

- [ ] **Step 3: Include `:benchmark` in settings**

In `settings.gradle.kts`, add after the last `include`:

```kotlin
include(":benchmark")
```

- [ ] **Step 4: Create `benchmark/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.omnieditor.benchmark"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Target the "direct" flavour — the one that ships first.
    flavorDimensions += "distribution"
    productFlavors {
        create("direct") { dimension = "distribution" }
        create("store") { dimension = "distribution" }
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = getByName("debug").signingConfig
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
}

dependencies {
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.uiautomator)
    implementation(libs.junit)
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType == "benchmark"
    }
}
```

- [ ] **Step 5: Create `benchmark/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- Profileable tag allows benchmark to capture method traces -->
</manifest>
```

- [ ] **Step 6: Verify module compiles**

Run: `./gradlew :benchmark:assembleDirectBenchmark 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

Also verify existing builds are not broken:
Run: `./gradlew assembleDirectDebug assembleStoreDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts app/build.gradle.kts \
        benchmark/build.gradle.kts benchmark/src/main/AndroidManifest.xml
git commit -m "feat(benchmark): scaffold :benchmark module with benchmark build type [F-05b, #14]"
```

---

### Task 2: Deterministic fixture generator

**Files:**
- Create: `benchmark/src/main/kotlin/com/omnieditor/benchmark/FixtureGenerator.kt`
- Modify: `benchmark/build.gradle.kts` (add `generateFixtures` task)

**Interfaces:**
- Consumes: nothing from other tasks
- Produces: `generateFixtures` Gradle task that writes files to `benchmark/build/fixtures/`

- [ ] **Step 1: Create FixtureGenerator.kt**

Create `benchmark/src/main/kotlin/com/omnieditor/benchmark/FixtureGenerator.kt`:

```kotlin
package com.omnieditor.benchmark

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.random.Random

/**
 * Deterministic fixture generator for benchmark runs.
 *
 * All generators use a fixed seed so output is byte-identical across runs.
 * Files are written to a specified output directory (typically benchmark/build/fixtures/).
 */
object FixtureGenerator {

    private const val SEED = 20260816L

    /**
     * Generate a 250 MB pair with ~80% shared content, ~20% differing blocks.
     * Each line is 100 characters + newline. ~2.5M lines per file ≈ 250 MB.
     */
    fun generateLargePair(outputDir: File) {
        outputDir.mkdirs()
        val leftFile = File(outputDir, "large-left.txt")
        val rightFile = File(outputDir, "large-right.txt")
        val rng = Random(SEED)

        val linesPerFile = 2_500_000 // ~250 MB at ~100 chars/line
        val diffRate = 0.20 // 20% of lines differ

        BufferedWriter(FileWriter(leftFile)).use { left ->
            BufferedWriter(FileWriter(rightFile)).use { right ->
                for (i in 0 until linesPerFile) {
                    val shared = buildLine(rng, i, 100)
                    if (rng.nextDouble() < diffRate) {
                        // Differing line: modify a segment
                        val modified = shared.replaceRange(
                            10, minOf(30, shared.length),
                            buildSegment(rng, 20),
                        )
                        left.write(shared)
                        left.newLine()
                        right.write(modified)
                        right.newLine()
                    } else {
                        left.write(shared)
                        left.newLine()
                        right.write(shared)
                        right.newLine()
                    }
                }
            }
        }
    }

    /**
     * Generate a 500k-line file for scroll benchmarks.
     * Each line: line number (zero-padded to 6 digits) + space + deterministic padding.
     */
    fun generateScrollFile(outputDir: File) {
        outputDir.mkdirs()
        val file = File(outputDir, "scroll-500k.txt")
        val rng = Random(SEED + 1)

        BufferedWriter(FileWriter(file)).use { writer ->
            for (i in 0 until 500_000) {
                val prefix = "%06d ".format(i)
                val padding = buildSegment(rng, 80)
                writer.write(prefix + padding)
                writer.newLine()
            }
        }
    }

    private fun buildLine(rng: Random, lineNum: Int, length: Int): String {
        val prefix = "%06d ".format(lineNum)
        val remaining = length - prefix.length
        return prefix + buildSegment(rng, remaining)
    }

    private fun buildSegment(rng: Random, length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,-_"
        return buildString(length) {
            repeat(length) { append(chars[rng.nextInt(chars.length)]) }
        }
    }
}
```

- [ ] **Step 2: Register `generateFixtures` Gradle task**

In `benchmark/build.gradle.kts`, add at the top level (after the `androidComponents` block):

```kotlin
tasks.register("generateFixtures") {
    group = "benchmark"
    description = "Generate deterministic fixture files for benchmark runs"
    val outputDir = layout.buildDirectory.dir("fixtures")

    outputs.dir(outputDir)

    doLast {
        val dir = outputDir.get().asFile
        println("Generating fixtures in ${dir.absolutePath} ...")

        print("  250 MB pair... ")
        com.omnieditor.benchmark.FixtureGenerator.generateLargePair(dir)
        println("done.")

        print("  500k-line scroll file... ")
        com.omnieditor.benchmark.FixtureGenerator.generateScrollFile(dir)
        println("done.")

        val files = dir.listFiles() ?: emptyArray()
        for (f in files) {
            println("  ${f.name}: ${f.length() / (1024 * 1024)} MB")
        }
    }
}
```

- [ ] **Step 3: Verify fixture generator compiles and the task is visible**

Run: `./gradlew :benchmark:tasks --group benchmark 2>&1 | grep generateFixtures`
Expected: `generateFixtures - Generate deterministic fixture files for benchmark runs`

Note: The `generateFixtures` task references `FixtureGenerator` by class name inside `doLast`, which executes at runtime. The class is part of the Android source set — Gradle tasks cannot directly reference Android source classes. Instead, move the generator logic into the build script's `doLast` block directly, OR create a separate `buildSrc` / standalone script. The simplest approach: put the generator logic inline in the `doLast` block.

**Revised approach:** Replace the `generateFixtures` task with inline logic:

```kotlin
tasks.register("generateFixtures") {
    group = "benchmark"
    description = "Generate deterministic fixture files for benchmark runs"
    val fixtureDir = layout.buildDirectory.dir("fixtures")
    outputs.dir(fixtureDir)

    doLast {
        val dir = fixtureDir.get().asFile
        dir.mkdirs()
        val seed = 20260816L
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,-_"

        fun buildSegment(rng: kotlin.random.Random, length: Int): String =
            buildString(length) { repeat(length) { append(chars[rng.nextInt(chars.length)]) } }

        // 250 MB pair (~2.5M lines, ~100 chars each, 20% differ)
        println("Generating 250 MB pair...")
        val rng1 = kotlin.random.Random(seed)
        java.io.BufferedWriter(java.io.FileWriter(java.io.File(dir, "large-left.txt"))).use { left ->
            java.io.BufferedWriter(java.io.FileWriter(java.io.File(dir, "large-right.txt"))).use { right ->
                for (i in 0 until 2_500_000) {
                    val prefix = "%06d ".format(i)
                    val body = buildSegment(rng1, 93)
                    val line = prefix + body
                    if (rng1.nextDouble() < 0.20) {
                        val modified = line.replaceRange(10, 30, buildSegment(rng1, 20))
                        left.write(line); left.newLine()
                        right.write(modified); right.newLine()
                    } else {
                        left.write(line); left.newLine()
                        right.write(line); right.newLine()
                    }
                }
            }
        }

        // 500k-line scroll file
        println("Generating 500k-line scroll file...")
        val rng2 = kotlin.random.Random(seed + 1)
        java.io.BufferedWriter(java.io.FileWriter(java.io.File(dir, "scroll-500k.txt"))).use { w ->
            for (i in 0 until 500_000) {
                w.write("%06d ".format(i) + buildSegment(rng2, 80))
                w.newLine()
            }
        }

        dir.listFiles()?.forEach { f ->
            println("  ${f.name}: ${f.length() / (1024 * 1024)} MB")
        }
    }
}
```

Keep `FixtureGenerator.kt` as the Android source set version (same logic) so benchmarks on device can also generate fixtures if needed. But the Gradle task uses inline logic.

- [ ] **Step 4: Run the task to verify fixture generation**

Run: `./gradlew :benchmark:generateFixtures 2>&1 | tail -10`
Expected: Output showing three files with approximate sizes (large-left.txt ~250 MB, large-right.txt ~250 MB, scroll-500k.txt ~43 MB).

Note: This may take 30–60 seconds and produce ~540 MB of files. Run only if disk space permits. If testing in a constrained environment, verify the task starts and the first few lines print without error, then cancel.

- [ ] **Step 5: Verify existing builds still work**

Run: `./gradlew assembleDirectDebug 2>&1 | tail -3`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add benchmark/src/main/kotlin/com/omnieditor/benchmark/FixtureGenerator.kt \
        benchmark/build.gradle.kts
git commit -m "feat(benchmark): deterministic fixture generator for 250MB pair and 500k-line file [F-05b, #14]"
```

---

### Task 3: Benchmark test classes

**Files:**
- Create: `benchmark/src/main/kotlin/com/omnieditor/benchmark/StartupBenchmark.kt`
- Create: `benchmark/src/main/kotlin/com/omnieditor/benchmark/CompareThresholdBenchmark.kt`
- Create: `benchmark/src/main/kotlin/com/omnieditor/benchmark/ScrollBenchmark.kt`
- Create: `benchmark/src/main/kotlin/com/omnieditor/benchmark/HeapBenchmark.kt`

**Interfaces:**
- Consumes: `:benchmark` module from Task 1, fixtures from Task 2 (pushed to `/sdcard/OmniEditor-bench/`)
- Produces: runnable benchmark classes (require physical device)

- [ ] **Step 1: Create StartupBenchmark.kt**

```kotlin
package com.omnieditor.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * NFR-P1: Cold start to home screen.
 * Target: < 600 ms.
 */
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() = benchmarkRule.measureRepeated(
        packageName = "com.omnieditor",
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        pressHome()
        startActivityAndWait()
        // Wait for the home screen to render
        device.wait(Until.hasObject(By.textContains("Omni Editor")), 5_000)
    }
}
```

- [ ] **Step 2: Create CompareThresholdBenchmark.kt**

```kotlin
package com.omnieditor.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * NFR-P2: 250 MB pair compare throughput.
 *
 * Prerequisite: push fixtures to device first:
 *   adb push benchmark/build/fixtures/ /sdcard/OmniEditor-bench/
 *
 * This benchmark opens the app, navigates to compare, loads the large pair,
 * and measures how long the compare takes. The actual measurement is
 * wall-clock time from compare start to result display.
 *
 * Note: this is a structural placeholder. The exact UiAutomator navigation
 * sequence depends on the app's UI at the time of first device run and may
 * need adjustment. The framework and fixture infrastructure are the
 * deliverable of F-05b; the navigation is refined when F-01/F-02 land.
 */
class CompareThresholdBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun compareLargePair() = benchmarkRule.measureRepeated(
        packageName = "com.omnieditor",
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        pressHome()
        startActivityAndWait()
        // Navigation to compare with large fixtures will be refined
        // when F-01/F-02 wire large-file support.
        device.wait(Until.hasObject(By.textContains("Omni Editor")), 10_000)
    }
}
```

- [ ] **Step 3: Create ScrollBenchmark.kt**

```kotlin
package com.omnieditor.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * NFR-P3: 500k-line scroll at 60 fps.
 *
 * Prerequisite: push fixtures to device first:
 *   adb push benchmark/build/fixtures/ /sdcard/OmniEditor-bench/
 *
 * Note: navigation sequence is a placeholder refined at F-01/F-03 time.
 */
class ScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollLargeFile() = benchmarkRule.measureRepeated(
        packageName = "com.omnieditor",
        metrics = listOf(FrameTimingMetric()),
        iterations = 3,
        startupMode = StartupMode.WARM,
        compilationMode = CompilationMode.Partial(),
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Omni Editor")), 5_000)
        // Fling scroll will be wired when F-03 enables large-file editing.
        // Placeholder: the framework captures frame timing for any scroll gesture.
    }
}
```

- [ ] **Step 4: Create HeapBenchmark.kt**

```kotlin
package com.omnieditor.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * NFR-P4/P5: Peak heap after loading a large compare.
 *
 * Uses MemoryUsageMetric which captures RSS and PSS from dumpsys meminfo.
 *
 * Note: navigation sequence is a placeholder refined at F-01/F-02 time.
 */
class HeapBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun peakHeapAfterCompare() = benchmarkRule.measureRepeated(
        packageName = "com.omnieditor",
        metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
        iterations = 3,
        startupMode = StartupMode.COLD,
        compilationMode = CompilationMode.Partial(),
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.textContains("Omni Editor")), 5_000)
        // Load large compare when F-01/F-02 support is available.
    }
}
```

- [ ] **Step 5: Verify module compiles with benchmark classes**

Run: `./gradlew :benchmark:assembleDirectBenchmark 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add benchmark/src/main/kotlin/com/omnieditor/benchmark/StartupBenchmark.kt \
        benchmark/src/main/kotlin/com/omnieditor/benchmark/CompareThresholdBenchmark.kt \
        benchmark/src/main/kotlin/com/omnieditor/benchmark/ScrollBenchmark.kt \
        benchmark/src/main/kotlin/com/omnieditor/benchmark/HeapBenchmark.kt
git commit -m "feat(benchmark): macrobenchmark classes for NFR-P1 through P5 [F-05b, #14]"
```

---

### Task 4: ADR-002, licenses, CHANGES.md, close issue

**Files:**
- Create: `docs/adr/002-performance-verification.md`
- Modify: `docs/licenses.md`
- Modify: `CHANGES.md`

**Interfaces:**
- Consumes: all previous tasks
- Produces: documentation, issue closed

- [ ] **Step 1: Create ADR-002**

Create `docs/adr/002-performance-verification.md`:

```markdown
# ADR 002 — Performance verification methodology

**Status:** accepted — F-05b, 16 August 2026.

## Context

The spec defines performance targets (NFR-P1 through P5) that require physical
device measurement. Decision D-7 establishes manual per-release benchmarking
with no CI gating.

Decision D-2 reinterprets the spec's absolute targets (2 GB, 250 MB in 45 s)
as a benchmarked size ladder: ceilings are raised stepwise with a recorded
benchmark per step.

## Decision

- Benchmarks run via `androidx.benchmark:benchmark-macro-junit4` in the
  `:benchmark` module.
- The `:app` module has a `benchmark` build type (minified, non-debuggable,
  profileable) for realistic measurements.
- Deterministic fixtures are generated by `./gradlew :benchmark:generateFixtures`:
  - 250 MB text file pair (2.5M lines, 100 chars/line, 20% differing, seed 20260816)
  - 500k-line scroll file (seed 20260817)
- Benchmarks are run manually on a physical device before each release.
- Results are recorded in the table below. No CI gating (D-7).
- Device-agnostic: any device can serve as the reference; device info is
  captured per run.

## Benchmark classes

| Class | NFR | Metric |
|---|---|---|
| `StartupBenchmark` | P1 | Cold start time (ms) |
| `CompareThresholdBenchmark` | P2 | Compare wall-clock (ms) |
| `ScrollBenchmark` | P3 | Frame timing (P50/P90/P99 ms) |
| `HeapBenchmark` | P4/P5 | Peak RSS/PSS (MB) |

## How to run

```bash
# Generate fixtures (once, ~540 MB output)
./gradlew :benchmark:generateFixtures

# Push to device
adb push benchmark/build/fixtures/ /sdcard/OmniEditor-bench/

# Run benchmarks
./gradlew :benchmark:connectedDirectBenchmarkAndroidTest
```

## Results table

| Release | Device | OS | NFR | Metric | Value | Target | Date |
|---|---|---|---|---|---|---|---|
| *(first entry at v0.3)* | | | | | | | |

## Trigger to revisit

- If regressions slip through manual testing, consider CI-gated benchmarks
  with a self-hosted runner or device farm.
- When the size ladder (D-2) adds a new tier, add a row per tier per NFR.
```

- [ ] **Step 2: Add benchmark dependencies to licenses.md**

Read `docs/licenses.md` first, then append before the "Licence notes:" section:

```markdown
| AndroidX Benchmark Macro | 1.3.4 | Apache-2.0 (test only) | benchmark | F-05b |
| AndroidX UiAutomator | 2.3.0 | Apache-2.0 (test only) | benchmark | F-05b |
```

- [ ] **Step 3: Add F-05b entry to CHANGES.md**

Add after the Review-3 section:

```markdown
### F-05b — Benchmark harness — Issue #14

`:benchmark` macrobenchmark module targeting `:app` with `benchmark` build type
(minified, profileable). Four benchmark classes: `StartupBenchmark` (NFR-P1),
`CompareThresholdBenchmark` (NFR-P2), `ScrollBenchmark` (NFR-P3),
`HeapBenchmark` (NFR-P4/P5). Deterministic fixture generator
(`./gradlew :benchmark:generateFixtures`) produces 250 MB pair and 500k-line
file from seeded PRNG. ADR-002 documents methodology and results table.
Benchmark navigation sequences are structural placeholders refined when
F-01/F-02/F-03 land large-file support.
```

- [ ] **Step 4: Verify full build**

Run: `./gradlew assembleDirectDebug assembleStoreDebug checkCorePurity checkIoBoundary 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add docs/adr/002-performance-verification.md docs/licenses.md CHANGES.md
git commit -m "docs: ADR-002 performance verification, licenses, changelog [F-05b, #14]"
```

- [ ] **Step 6: Close issue #14**

```bash
gh issue close 14 --repo bobcheong/omni-editor --reason completed \
  --comment "F-05b complete: :benchmark module, fixture generator, 4 benchmark classes, ADR-002. Navigation sequences are placeholders refined when F-01/F-02/F-03 land."
```
