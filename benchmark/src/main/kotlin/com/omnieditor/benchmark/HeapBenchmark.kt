package com.omnieditor.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
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
 * Uses MemoryUsageMetric (Mode.Max) which captures the maximum RSS and PSS
 * from a Perfetto memory trace taken during the benchmark window.
 *
 * Prerequisite: push fixtures to device first:
 *   adb push benchmark/build/fixtures/ /sdcard/OmniEditor-bench/
 *
 * Note: navigation sequence is a structural placeholder refined at F-01/F-02
 * time when large-file compare is wired through the UI.
 */
@OptIn(ExperimentalMetricApi::class)
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
