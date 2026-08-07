package com.omnieditor.core.model

import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.Test

class ThemeTest {

    // ── Contrast checking ──

    @Test
    fun `black on white has high contrast`() {
        val ratio = ContrastChecker.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        ratio shouldBeGreaterThanOrEqual 21.0
    }

    @Test
    fun `same colour has contrast 1`() {
        val ratio = ContrastChecker.contrastRatio(0xFF808080.toInt(), 0xFF808080.toInt())
        ratio shouldBe 1.0
    }

    @Test
    fun `meetsAA correct for high contrast`() {
        ContrastChecker.meetsAA(0xFF000000.toInt(), 0xFFFFFFFF.toInt()) shouldBe true
    }

    @Test
    fun `meetsAA correct for low contrast`() {
        ContrastChecker.meetsAA(0xFFCCCCCC.toInt(), 0xFFDDDDDD.toInt()) shouldBe false
    }

    // ── Built-in theme validation ──

    @Test
    fun `light theme all pairs meet 4_5 to 1`() {
        val failures = ContrastChecker.validateTheme(ThemeDefinition.LIGHT)
        failures shouldBe emptyList()
    }

    @Test
    fun `dark theme all pairs meet 4_5 to 1`() {
        val failures = ContrastChecker.validateTheme(ThemeDefinition.DARK)
        failures shouldBe emptyList()
    }

    @Test
    fun `high contrast theme all pairs meet 4_5 to 1`() {
        val failures = ContrastChecker.validateTheme(ThemeDefinition.HIGH_CONTRAST)
        failures shouldBe emptyList()
    }

    @Test
    fun `colour vision safe theme all pairs meet 4_5 to 1`() {
        val failures = ContrastChecker.validateTheme(ThemeDefinition.COLOUR_SAFE)
        failures shouldBe emptyList()
    }

    @Test
    fun `all built-in themes pass validation`() {
        for (theme in ThemeDefinition.BUILT_IN) {
            val failures = ContrastChecker.validateTheme(theme)
            if (failures.isNotEmpty()) {
                throw AssertionError("Theme '${theme.name}' failed contrast check:\n${failures.joinToString("\n")}")
            }
        }
    }

    // ── Theme serialisation ──

    @Test
    fun `theme definition round trips through JSON`() {
        val theme = ThemeDefinition.LIGHT
        val json = Json.encodeToString(ThemeDefinition.serializer(), theme)
        val restored = Json.decodeFromString(ThemeDefinition.serializer(), json)
        restored shouldBe theme
    }

    @Test
    fun `dark theme round trips`() {
        val json = Json.encodeToString(ThemeDefinition.serializer(), ThemeDefinition.DARK)
        val restored = Json.decodeFromString(ThemeDefinition.serializer(), json)
        restored.isDark shouldBe true
        restored.name shouldBe "Dark"
    }

    @Test
    fun `custom theme with UI colours round trips`() {
        val custom = ThemeDefinition(
            name = "Custom",
            isDark = false,
            compare = ThemeDefinition.LIGHT.compare,
            ui = UiColorDef(primary = 0xFF1A73E8.toInt()),
        )
        val json = Json.encodeToString(ThemeDefinition.serializer(), custom)
        val restored = Json.decodeFromString(ThemeDefinition.serializer(), json)
        restored.ui shouldBe custom.ui
    }

    @Test
    fun `colour safe uses blue and orange not red and green`() {
        val safe = ThemeDefinition.COLOUR_SAFE.compare
        // Added should be blue-ish (low red, higher blue)
        val addedR = (safe.addedFg shr 16) and 0xFF
        val addedB = safe.addedFg and 0xFF
        (addedB > addedR) shouldBe true
        // Removed should be orange-ish (high red, medium green, low blue)
        val removedR = (safe.removedFg shr 16) and 0xFF
        val removedB = safe.removedFg and 0xFF
        (removedR > removedB) shouldBe true
    }

    // ── Luminance edge cases ──

    @Test
    fun `pure white luminance is 1`() {
        val lum = ContrastChecker.relativeLuminance(0xFFFFFFFF.toInt())
        (lum > 0.99) shouldBe true
    }

    @Test
    fun `pure black luminance is 0`() {
        val lum = ContrastChecker.relativeLuminance(0xFF000000.toInt())
        (lum < 0.01) shouldBe true
    }
}
