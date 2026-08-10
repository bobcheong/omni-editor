package com.omnieditor.app

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Permission rationale screen for the direct flavour (T-05).
 *
 * Checks [Environment.isExternalStorageManager] and either:
 * - navigates to home when granted, or
 * - shows a rationale with a button to launch the system permission settings, or
 * - offers a SAF fallback if the user denies.
 */
@Composable
fun PermissionScreen(
    onGranted: () -> Unit,
    onUseSafFallback: () -> Unit,
) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }

    // Re-check whenever the activity resumes (user may have toggled the permission).
    LifecycleResumeEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            onGranted()
        } else {
            // If we already sent the user to settings once, mark as denied on return.
            // The `denied` flag is set after the first launch attempt.
        }
        onPauseOrDispose { }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "File access",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Omni Editor needs access to your files to open and save them.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(24.dp))

            if (!denied) {
                Button(
                    onClick = {
                        denied = true // will show fallback if still denied on resume
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant access")
                }
            } else {
                Text(
                    text = "Access denied",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            context.startActivity(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Try again")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onUseSafFallback,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Use file picker instead")
                }
            }
        }
    }
}
