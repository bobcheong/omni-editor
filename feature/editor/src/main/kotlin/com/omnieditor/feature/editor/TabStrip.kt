package com.omnieditor.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tab strip shared between documents and compares (OE-EDT-2).
 *
 * Shows a dirty dot when unsaved. Tab state, caret position and scroll
 * offset survive process death (via the ViewModel/session persistence).
 */
data class TabInfo(
    val id: String,
    val label: String,
    val dirty: Boolean,
    val isCompare: Boolean = false,
)

@Composable
fun TabStrip(
    tabs: List<TabInfo>,
    selectedTabId: String?,
    onTabSelected: (String) -> Unit,
    onTabClosed: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in tabs) {
            val isSelected = tab.id == selectedTabId
            Tab(
                info = tab,
                isSelected = isSelected,
                onClick = { onTabSelected(tab.id) },
                onClose = { onTabClosed(tab.id) },
            )
        }

        // New tab button
        IconButton(
            onClick = onNewTab,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "New tab",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun Tab(
    info: TabInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Row(
        modifier = Modifier
            .height(40.dp)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dirty indicator
        if (info.dirty) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small)
                    .padding(end = 4.dp),
            )
        }

        Text(
            text = if (info.isCompare) "⇄ ${info.label}" else info.label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
            color = if (isSelected) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (info.dirty) 6.dp else 0.dp),
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier.size(20.dp).padding(start = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close ${info.label}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
