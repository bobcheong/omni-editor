package com.omnieditor.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import org.junit.Test

class KeyboardShortcutsTest {

    private val shortcuts = KeyboardShortcuts.DEFAULT

    // ── Default bindings ──

    @Test
    fun `default save is Ctrl+S`() {
        val binding = shortcuts.bindingFor(ShortcutAction.SAVE)
        binding shouldNotBe null
        binding!!.ctrl shouldBe true
        binding.keyCode shouldBe 83 // 'S'
    }

    @Test
    fun `default undo is Ctrl+Z`() {
        val binding = shortcuts.bindingFor(ShortcutAction.UNDO)!!
        binding.ctrl shouldBe true
        binding.keyCode shouldBe 90
        binding.shift shouldBe false
    }

    @Test
    fun `default redo is Ctrl+Shift+Z`() {
        val binding = shortcuts.bindingFor(ShortcutAction.REDO)!!
        binding.ctrl shouldBe true
        binding.shift shouldBe true
        binding.keyCode shouldBe 90
    }

    @Test
    fun `all actions have default bindings`() {
        for (action in ShortcutAction.entries) {
            shortcuts.bindingFor(action) shouldNotBe null
        }
    }

    // ── Key lookup ──

    @Test
    fun `find action by key combination`() {
        val action = shortcuts.findAction(83, ctrl = true, shift = false, alt = false)
        action shouldBe ShortcutAction.SAVE
    }

    @Test
    fun `unknown key returns null`() {
        shortcuts.findAction(999, ctrl = false, shift = false, alt = false) shouldBe null
    }

    @Test
    fun `modifier mismatch returns null`() {
        // Ctrl+S exists but plain S does not
        shortcuts.findAction(83, ctrl = false, shift = false, alt = false) shouldBe null
    }

    // ── Remapping ──

    @Test
    fun `remap a shortcut`() {
        val remapped = shortcuts.withBinding(
            ShortcutAction.SAVE,
            KeyBinding(83, ctrl = true, shift = true), // Ctrl+Shift+S
        )
        val binding = remapped.bindingFor(ShortcutAction.SAVE)!!
        binding.shift shouldBe true
        // Original unchanged
        shortcuts.bindingFor(ShortcutAction.SAVE)!!.shift shouldBe false
    }

    // ── Labels ──

    @Test
    fun `binding label for Ctrl+S`() {
        val label = KeyBinding(83, ctrl = true).label()
        label shouldBe "Ctrl+S"
    }

    @Test
    fun `binding label for Alt+Left arrow`() {
        val label = KeyBinding(37, alt = true).label()
        label shouldBe "Alt+←"
    }

    @Test
    fun `binding label for Ctrl+Shift+Z`() {
        val label = KeyBinding(90, ctrl = true, shift = true).label()
        label shouldBe "Ctrl+Shift+Z"
    }

    // ── Serialisation ──

    @Test
    fun `shortcuts round-trip through JSON`() {
        val json = Json.encodeToString(KeyboardShortcuts.serializer(), shortcuts)
        val restored = Json.decodeFromString(KeyboardShortcuts.serializer(), json)
        restored.bindingFor(ShortcutAction.SAVE) shouldBe shortcuts.bindingFor(ShortcutAction.SAVE)
    }

    @Test
    fun `custom shortcuts round-trip`() {
        val custom = shortcuts.withBinding(ShortcutAction.SAVE, KeyBinding(70, ctrl = true, alt = true))
        val json = Json.encodeToString(KeyboardShortcuts.serializer(), custom)
        val restored = Json.decodeFromString(KeyboardShortcuts.serializer(), json)
        restored.bindingFor(ShortcutAction.SAVE)!!.keyCode shouldBe 70
    }

    @Test
    fun `accessibility config round-trips`() {
        val config = AccessibilityConfig(reduceMotion = false, forceHighContrast = true)
        val json = Json.encodeToString(AccessibilityConfig.serializer(), config)
        val restored = Json.decodeFromString(AccessibilityConfig.serializer(), json)
        restored shouldBe config
    }
}
