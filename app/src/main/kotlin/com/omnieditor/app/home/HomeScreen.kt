package com.omnieditor.app.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.app.R
import com.omnieditor.core.model.Session
import com.omnieditor.design.OmniTheme

/**
 * Home screen S-01 (OE-SES-1..3).
 *
 * Three tabs: Recent (mixed documents and compares, newest first),
 * Sessions (saved, groupable, pinnable), Files (browse).
 *
 * States: empty (first run), no results for search, unresolvable session.
 * Wide layout: two panes — list left, preview right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    pinnedSessions: List<Session> = emptyList(),
    recentSessions: List<Session> = emptyList(),
    onSessionTap: (String) -> Unit = {},
    onNewCompare: () -> Unit = {},
    onOpenFile: () -> Unit = {},
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Omni Editor") },
                actions = {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Search, "Search")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewCompare) {
                Icon(Icons.Default.Add, "New")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab row
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                    Text("Recent", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                    Text("Sessions", modifier = Modifier.padding(12.dp))
                }
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }) {
                    Text("Files", modifier = Modifier.padding(12.dp))
                }
            }

            when (selectedTab) {
                0 -> RecentTab(pinnedSessions, recentSessions, onSessionTap)
                1 -> SessionsTab(pinnedSessions + recentSessions, onSessionTap)
                2 -> FilesTab(onOpenFile)
            }
        }
    }
}

@Composable
private fun RecentTab(
    pinned: List<Session>,
    recent: List<Session>,
    onTap: (String) -> Unit,
) {
    if (pinned.isEmpty() && recent.isEmpty()) {
        EmptyState()
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (pinned.isNotEmpty()) {
            item {
                Text(
                    "PINNED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                )
            }
            items(pinned, key = { it.id }) { session ->
                SessionRow(session, onTap)
            }
        }

        item {
            Text(
                "RECENT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
            )
        }
        items(recent, key = { it.id }) { session ->
            SessionRow(session, onTap)
        }
    }
}

@Composable
private fun SessionsTab(sessions: List<Session>, onTap: (String) -> Unit) {
    if (sessions.isEmpty()) {
        EmptyState()
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(sessions, key = { it.id }) { session ->
            SessionRow(session, onTap)
        }
    }
}

@Composable
private fun FilesTab(onOpenFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Browse files", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Open a file to edit or compare",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionRow(session: Session, onTap: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap(session.id) }
            .padding(16.dp, 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val summary = session.lastSummary
            val subtitle = if (summary != null) {
                "${summary.hunkCount} differences"
            } else {
                session.mode.name.lowercase()
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (session.pinned) {
            Text("📌", fontSize = 14.sp)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
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
    }
}

@Preview
@Composable
private fun HomePreview() = OmniTheme { HomeScreen() }
