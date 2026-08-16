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
 * and measures frame timing while the compare result renders. The actual
 * measurement is wall-clock time from compare start to result display.
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
