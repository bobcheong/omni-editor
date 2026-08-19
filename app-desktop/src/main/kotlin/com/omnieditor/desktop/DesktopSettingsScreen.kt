package com.omnieditor.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Desktop settings screen — mirrors the Android SettingsScreen layout.
 * All settings are persisted to the XDG JSON config file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopSettingsScreen(
    settings: DesktopSettings,
    onSettingsChanged: (DesktopSettings) -> Unit,
    onNavigateBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onNavigateBack) { Text("Back") }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Editor ──
            SectionHeader("Editor")
            SwitchRow("Word wrap", settings.wordWrap) {
                onSettingsChanged(settings.copy(wordWrap = it))
            }
            SwitchRow("Show line numbers", settings.showLineNumbers) {
                onSettingsChanged(settings.copy(showLineNumbers = it))
            }
            SwitchRow("Show whitespace", settings.showWhitespace) {
                onSettingsChanged(settings.copy(showWhitespace = it))
            }
            ClickableRow("Font size", "${settings.fontSize}pt") {
                val next = if (settings.fontSize >= 48) 8 else settings.fontSize + 2
                onSettingsChanged(settings.copy(fontSize = next))
            }
            ClickableRow("Tab width", "${settings.tabWidth} spaces") {
                val next = when (settings.tabWidth) { 2 -> 4; 4 -> 8; else -> 2 }
                onSettingsChanged(settings.copy(tabWidth = next))
            }

            // ── Compare ──
            SectionHeader("Compare")
            ClickableRow(
                "Default layout",
                if (settings.defaultLayout == "split") "Split" else "Unified",
            ) {
                val next = if (settings.defaultLayout == "unified") "split" else "unified"
                onSettingsChanged(settings.copy(defaultLayout = next))
            }
            SwitchRow("Sync scroll", settings.syncScroll) {
                onSettingsChanged(settings.copy(syncScroll = it))
            }
            ClickableRow(
                "Intra-line granularity",
                settings.granularity.replaceFirstChar { it.uppercaseChar() },
            ) {
                val next = when (settings.granularity) {
                    "word" -> "char"; "char" -> "line"; else -> "word"
                }
                onSettingsChanged(settings.copy(granularity = next))
            }

            // ── Appearance ──
            SectionHeader("Appearance")
            ClickableRow(
                "Theme",
                when (settings.darkTheme) {
                    "light" -> "Light"; "dark" -> "Dark"; else -> "System default"
                },
            ) {
                val next = when (settings.darkTheme) {
                    "system" -> "light"; "light" -> "dark"; else -> "system"
                }
                onSettingsChanged(settings.copy(darkTheme = next))
            }

            // ── About ──
            SectionHeader("About")
            ClickableRow("Version", DesktopBuildInfo.aboutString) {}
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider()
}

@Composable
private fun ClickableRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}
