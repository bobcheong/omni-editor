package com.omnieditor.app.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.omnieditor.app.R
import com.omnieditor.design.OmniTheme

/**
 * T-01 placeholder for screen S-01. The real Home — three tabs, sessions, tab strip —
 * lands in T-24. This exists so both flavours can be installed and verified now.
 *
 * The empty state copy is already the final copy: an empty screen is an invitation to
 * act, not an apology.
 */
@Composable
fun HomeScreen() {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.home_empty_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.home_empty_body),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { /* T-23 */ }) { Text(stringResource(R.string.home_open_file)) }
            OutlinedButton(onClick = { /* T-23 */ }) { Text(stringResource(R.string.home_new_compare)) }
        }
    }
}

@Preview
@Composable
private fun HomePreview() = OmniTheme { HomeScreen() }
