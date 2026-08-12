package com.omnieditor.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnitRunner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tier 2 instrumented smoke test. Requires an emulator or managed device.
 * Run via: ./gradlew pixel6api34DebugAndroidTest
 *
 * This test cannot be executed in the local JVM-only environment (see ADR-001).
 * It is marked unverified pending instrumented test execution.
 *
 * Requirement: R-00b, T-00
 */
@RunWith(AndroidJUnitRunner::class)
class SmokeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreenDisplays() {
        // Verify the home screen renders without crashing
        composeTestRule.onNodeWithText("Omni Editor").assertIsDisplayed()
    }
}
