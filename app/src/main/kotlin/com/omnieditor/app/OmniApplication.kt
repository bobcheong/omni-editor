package com.omnieditor.app

import android.app.Application
import android.util.Log
import com.omnieditor.core.model.ContrastChecker
import com.omnieditor.core.model.ThemeDefinition
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OmniApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        validateBuiltInThemeContrast()
    }
}

/**
 * Verify WCAG AA contrast ratios for all built-in theme colour pairs (OE-APP-2, R-37).
 *
 * Failures are logged as warnings at startup. In a future release these could gate
 * theme approval or trigger a high-contrast fallback (NFR-A2).
 */
private fun validateBuiltInThemeContrast() {
    for (theme in ThemeDefinition.BUILT_IN) {
        val failures = ContrastChecker.validateTheme(theme)
        if (failures.isEmpty()) {
            Log.d("OmniA11y", "Theme '${theme.name}': all contrast ratios pass WCAG AA")
        } else {
            for (failure in failures) {
                Log.w("OmniA11y", "Theme '${theme.name}' contrast failure: $failure")
            }
        }
    }
}
