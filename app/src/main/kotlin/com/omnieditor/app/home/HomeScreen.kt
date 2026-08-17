package com.omnieditor.app.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.core.model.Session
import com.omnieditor.core.model.SessionGroup
import com.omnieditor.design.OmniTheme

/**
 * Home screen S-01 (OE-SES-1..3).
 *
 * Three tabs: Recent, Sessions, Files.
 * Sessions tab has inline search and group filter chips (F-13).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    pinnedSessions: List<Session> = emptyList(),
    recentSessions: List<Session> = emptyList(),
    groups: List<SessionGroup> = emptyList(),
    onSessionTap: (String) -> Unit = {},
    onNewCompare: () -> Unit = {},
    onOpenFile: () -> Unit = {},
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
    onCreateGroup: (name: String) -> Unit = {},
    onRenameGroup: (id: String, name: String) -> Unit = { _, _ -> },
    onDeleteGroup: (id: String) -> Unit = {},
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
                Icon(Icons.Default.Add, "New compare")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
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
                0 -> RecentTab(pinnedSessions, recentSessions, onSessionTap, onOpenFile, onNewCompare)
                1 -> SessionsTab(
                    sessions = pinnedSessions + recentSessions,
                    groups = groups,
                    onTap = onSessionTap,
                    onNewCompare = onNewCompare,
                    onCreateGroup = onCreateGroup,
                    onRenameGroup = onRenameGroup,
                    onDeleteGroup = onDeleteGroup,
                )
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
    onOpenFile: () -> Unit,
    onNewCompare: () -> Unit,
) {
    if (pinned.isEmpty() && recent.isEmpty()) {
        EmptyState(onOpenFile, onNewCompare)
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

        if (recent.isNotEmpty()) {
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionsTab(
    sessions: List<Session>,
    groups: List<SessionGroup>,
    onTap: (String) -> Unit,
    onNewCompare: () -> Unit,
    onCreateGroup: (name: String) -> Unit,
    onRenameGroup: (id: String, name: String) -> Unit,
    onDeleteGroup: (id: String) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var pendingRenameGroup by remember { mutableStateOf<SessionGroup?>(null) }

    // Filter sessions by search query and selected group
    val filtered = sessions.filter { session ->
        val matchesQuery = searchQuery.isBlank() ||
            session.name.contains(searchQuery, ignoreCase = true)
        val matchesGroup = selectedGroupId == null ||
            session.groupId == selectedGroupId
        matchesQuery && matchesGroup
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search sessions") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Group filter chips
        if (groups.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "All" chip
                FilterChip(
                    selected = selectedGroupId == null,
                    onClick = { selectedGroupId = null },
                    label = { Text("All") },
                )
                for (group in groups) {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        FilterChip(
                            selected = selectedGroupId == group.id,
                            onClick = { selectedGroupId = if (selectedGroupId == group.id) null else group.id },
                            label = { Text(group.name) },
                            modifier = Modifier.combinedClickable(
                                onClick = { selectedGroupId = if (selectedGroupId == group.id) null else group.id },
                                onLongClick = { showMenu = true },
                            ),
                        )
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    showMenu = false
                                    pendingRenameGroup = group
                                    showRenameDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    showMenu = false
                                    onDeleteGroup(group.id)
                                    if (selectedGroupId == group.id) selectedGroupId = null
                                },
                            )
                        }
                    }
                }
                // New group button
                TextButton(onClick = { showCreateGroupDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("New group")
                }
            }
        } else {
            // No groups yet: show "New group" button inline
            TextButton(
                onClick = { showCreateGroupDialog = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("New group")
            }
        }

        // Session list
        if (filtered.isEmpty()) {
            EmptyState({}, onNewCompare)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { session ->
                    SessionRow(session, onTap)
                }
            }
        }
    }

    // Create group dialog
    if (showCreateGroupDialog) {
        GroupNameDialog(
            title = "New group",
            initialName = "",
            onConfirm = { name ->
                onCreateGroup(name)
                showCreateGroupDialog = false
            },
            onDismiss = { showCreateGroupDialog = false },
        )
    }

    // Rename group dialog
    if (showRenameDialog && pendingRenameGroup != null) {
        val group = pendingRenameGroup!!
        GroupNameDialog(
            title = "Rename group",
            initialName = group.name,
            onConfirm = { name ->
                onRenameGroup(group.id, name)
                showRenameDialog = false
                pendingRenameGroup = null
            },
            onDismiss = {
                showRenameDialog = false
                pendingRenameGroup = null
            },
        )
    }
}

@Composable
private fun GroupNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Group name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FilesTab(onOpenFile: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Browse files", style = MaterialTheme.typography.titleMedium)
        Text(
            "Open a file to edit or compare",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenFile) {
            Text("Open file")
        }
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
private fun EmptyState(onOpenFile: () -> Unit = {}, onNewCompare: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Compare two files to get started",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Open a file to edit, or compare two files to see what changed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenFile) { Text("Open file") }
        OutlinedButton(onClick = onNewCompare) { Text("New compare") }
    }
}

@Preview
@Composable
private fun HomePreview() = OmniTheme { HomeScreen() }
