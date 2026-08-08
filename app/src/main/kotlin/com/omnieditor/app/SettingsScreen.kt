package com.omnieditor.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.omnieditor.core.model.LicenceInfo

/**
 * Settings screen (S-09).
 *
 * Compare defaults, editor preferences, appearance, about/licences.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
            SettingsSection("Compare")
            SettingsItem("Default ignore rules", "Case, whitespace, line endings")
            SettingsItem("Granularity", "Word")
            SettingsItem("Large file threshold", "32 MB")

            SettingsSection("Editor")
            SettingsItem("Font size", "14 sp")
            SettingsItem("Tab width", "4 spaces")
            SettingsItem("Word wrap", "Off")
            SettingsItem("Autosave", "Continuous journalling")

            SettingsSection("Appearance")
            SettingsItem("Theme", "System default")
            SettingsItem("Dynamic colour", "On")
            SettingsItem("Font scale", "System")

            SettingsSection("About")
            SettingsItem("Version", "${LicenceInfo.appVersion} (${LicenceInfo.appVersionCode})")
            SettingsItem("Licences", "${LicenceInfo.entries.size} open-source libraries")
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
private fun SettingsItem(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* TODO: open setting detail */ }
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
