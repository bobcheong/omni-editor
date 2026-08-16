package com.omnieditor.benchmark

import androidx.benchmark.macro.CompilationMode
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
 *
 * Requires a physical device with the benchmark build installed.
 * Run: ./gradlew :benchmark:connectedDirectBenchmarkAndroidTest
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
