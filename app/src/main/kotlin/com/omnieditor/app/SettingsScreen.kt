package com.omnieditor.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnieditor.core.model.LicenceInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    // ── Collect DataStore-backed state ────────────────────────────────────────
    val wordWrap by viewModel.wordWrap.collectAsStateWithLifecycle()
    val showLineNumbers by viewModel.showLineNumbers.collectAsStateWithLifecycle()
    val showWhitespace by viewModel.showWhitespace.collectAsStateWithLifecycle()
    val tabWidth by viewModel.tabWidth.collectAsStateWithLifecycle()
    val defaultLayout by viewModel.defaultLayout.collectAsStateWithLifecycle()
    val syncScroll by viewModel.syncScroll.collectAsStateWithLifecycle()
    val granularity by viewModel.granularity.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()

    // Local UI-only state — not persisted
    var showLicences by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showLicences) "Licences" else "Settings") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showLicences) showLicences = false else onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        ) {
            if (showLicences) {
                for (entry in LicenceInfo.entries) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${entry.version} · ${entry.licence}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                }
            } else {
                // ── Editor (v0.1: no text-input/IME options per §2.2) ─────────
                SettingsSection("Editor")
                SwitchItem("Word wrap", wordWrap) { viewModel.setWordWrap(it) }
                SwitchItem("Show line numbers", showLineNumbers) { viewModel.setShowLineNumbers(it) }
                SwitchItem("Show whitespace", showWhitespace) { viewModel.setShowWhitespace(it) }
                SettingsItem("Tab width", "$tabWidth spaces") {
                    // Cycle through common tab widths: 2 → 4 → 8 → 2
                    viewModel.setTabWidth(if (tabWidth == 2) 4 else if (tabWidth == 4) 8 else 2)
                }

                // ── Compare ───────────────────────────────────────────────────
                SettingsSection("Compare")
                SettingsItem(
                    title = "Default layout",
                    subtitle = if (defaultLayout == "split") "Split" else "Unified",
                ) {
                    viewModel.setDefaultLayout(if (defaultLayout == "unified") "split" else "unified")
                }
                SwitchItem("Sync scroll", syncScroll) { viewModel.setSyncScroll(it) }
                SettingsItem(
                    title = "Intra-line granularity",
                    subtitle = granularity.replaceFirstChar { it.uppercaseChar() },
                ) {
                    viewModel.setGranularity(
                        when (granularity) {
                            "word" -> "char"
                            "char" -> "line"
                            else -> "word"
                        }
                    )
                }

                // ── Appearance ────────────────────────────────────────────────
                SettingsSection("Appearance")
                SettingsItem(
                    title = "Theme",
                    subtitle = when (theme) {
                        "light" -> "Light"
                        "dark" -> "Dark"
                        else -> "System default"
                    },
                ) {
                    viewModel.setTheme(
                        when (theme) {
                            "system" -> "light"
                            "light" -> "dark"
                            else -> "system"
                        }
                    )
                }
                SwitchItem("Dynamic colour", dynamicColor) { viewModel.setDynamicColor(it) }

                // ── About ─────────────────────────────────────────────────────
                SettingsSection("About")
                SettingsItem(
                    title = "Version",
                    subtitle = "${LicenceInfo.appVersion} (${LicenceInfo.appVersionCode})",
                ) { }
                SettingsItem(
                    title = "Licences",
                    subtitle = "${LicenceInfo.entries.size} open-source libraries",
                ) { showLicences = true }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp),
    )
    HorizontalDivider()
}

@Composable
private fun SettingsItem(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SwitchItem(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
