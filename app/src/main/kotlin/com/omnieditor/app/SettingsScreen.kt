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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnieditor.core.model.LicenceInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
) {
    // Local state for settings (would be persisted via DataStore in production)
    var ignoreCase by remember { mutableStateOf(false) }
    var ignoreWhitespace by remember { mutableStateOf(false) }
    var ignoreLineEndings by remember { mutableStateOf(true) }
    var wordWrap by remember { mutableStateOf(false) }
    var showLineNumbers by remember { mutableStateOf(true) }
    var tabWidth by remember { mutableIntStateOf(4) }
    var dynamicColor by remember { mutableStateOf(true) }
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
                // Licences list
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
                // Compare settings
                SettingsSection("Compare defaults")
                SwitchItem("Ignore case", ignoreCase) { ignoreCase = it }
                SwitchItem("Ignore whitespace", ignoreWhitespace) { ignoreWhitespace = it }
                SwitchItem("Ignore line endings", ignoreLineEndings) { ignoreLineEndings = it }

                // Editor settings
                SettingsSection("Editor")
                SwitchItem("Word wrap", wordWrap) { wordWrap = it }
                SwitchItem("Show line numbers", showLineNumbers) { showLineNumbers = it }
                SettingsItem("Tab width", "$tabWidth spaces") {
                    tabWidth = if (tabWidth == 4) 2 else if (tabWidth == 2) 8 else 4
                }

                // Appearance
                SettingsSection("Appearance")
                SwitchItem("Dynamic colour", dynamicColor) { dynamicColor = it }
                SettingsItem("Theme", "System default") { }

                // About
                SettingsSection("About")
                SettingsItem("Version", "${LicenceInfo.appVersion} (${LicenceInfo.appVersionCode})") { }
                SettingsItem("Licences", "${LicenceInfo.entries.size} open-source libraries") {
                    showLicences = true
                }
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
