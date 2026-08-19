package com.omnieditor.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DesktopSettings(
    val darkTheme: String = "system", // "system", "light", "dark"
    val wordWrap: Boolean = true,
    val showLineNumbers: Boolean = true,
    val showWhitespace: Boolean = false,
    val fontSize: Int = 14,
    val tabWidth: Int = 4,
    val defaultLayout: String = "unified", // "unified" or "split"
    val syncScroll: Boolean = true,
    val granularity: String = "word", // "word", "char", "line"
    val windowWidth: Int = 1200,
    val windowHeight: Int = 800,
    val windowX: Int = -1,
    val windowY: Int = -1,
    val windowMaximized: Boolean = false,
) {
    companion object {
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

        private fun settingsDir(): File {
            val xdg = System.getenv("XDG_CONFIG_HOME")
            val base = if (!xdg.isNullOrBlank()) File(xdg) else File(System.getProperty("user.home"), ".config")
            return File(base, "omnieditor")
        }

        private fun settingsFile(): File = File(settingsDir(), "settings.json")

        fun load(): DesktopSettings {
            val file = settingsFile()
            if (!file.exists()) return DesktopSettings()
            return try {
                json.decodeFromString(serializer(), file.readText())
            } catch (_: Exception) {
                DesktopSettings()
            }
        }

        fun save(settings: DesktopSettings) {
            val file = settingsFile()
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer(), settings))
        }
    }
}
