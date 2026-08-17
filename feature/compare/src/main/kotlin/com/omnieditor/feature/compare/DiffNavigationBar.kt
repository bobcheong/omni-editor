package com.omnieditor.feature.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.design.LocalCompareColors

/**
 * Bottom navigation bar for the compare view (OE-TXT-2).
 *
 * Previous/next difference controls, difference counter "7 / 42",
 * conflict navigation (R-32), merge direction arrows, accept-all action, and find.
 */
@Composable
fun DiffNavigationBar(
    currentDiff: Int,
    totalDiffs: Int,
    conflictCount: Int = 0,
    isHunkMerged: Boolean = false,
    bookmarkCount: Int = 0,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPreviousConflict: () -> Unit = {},
    onNextConflict: () -> Unit = {},
    onPreviousBookmark: () -> Unit = {},
    onNextBookmark: () -> Unit = {},
    onMergeLeftToRight: () -> Unit = {},
    onMergeRightToLeft: () -> Unit = {},
    onAcceptAllLeftToRight: () -> Unit = {},
    onAcceptAllRightToLeft: () -> Unit = {},
    onFind: () -> Unit,
    canMerge: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val counterText = if (totalDiffs > 0) "${currentDiff + 1} / $totalDiffs" else "No differences"

    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Previous
            IconButton(
                onClick = onPrevious,
                enabled = currentDiff > 0,
                modifier = Modifier.semantics { contentDescription = "Previous difference" },
            ) {
                Icon(Icons.Default.KeyboardArrowUp, "Previous")
            }

            // Counter
            Text(
                text = counterText,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Next
            IconButton(
                onClick = onNext,
                enabled = currentDiff < totalDiffs - 1,
                modifier = Modifier.semantics { contentDescription = "Next difference" },
            ) {
                Icon(Icons.Default.KeyboardArrowDown, "Next")
            }

            // Conflict navigation — only shown when conflicts exist (R-32)
            if (conflictCount > 0) {
                val conflictColors = LocalCompareColors.current
                IconButton(
                    onClick = onPreviousConflict,
                    modifier = Modifier
                        .size(36.dp)
                        .semantics { contentDescription = "Previous conflict" },
                ) {
                    Text(
                        "!",
                        color = conflictColors.conflictFg,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text(
                    text = "$conflictCount !",
                    style = MaterialTheme.typography.labelSmall,
                    color = conflictColors.conflictFg,
                )
                IconButton(
                    onClick = onNextConflict,
                    modifier = Modifier
                        .size(36.dp)
                        .semantics { contentDescription = "Next conflict" },
                ) {
                    Text(
                        "!",
                        color = conflictColors.conflictFg,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // Bookmark navigation (F-08, W-02) — only shown when bookmarks exist.
            if (bookmarkCount > 0) {
                IconButton(
                    onClick = onPreviousBookmark,
                    modifier = Modifier
                        .size(36.dp)
                        .semantics { contentDescription = "Previous bookmark" },
                ) {
                    Icon(Icons.Default.Bookmark, contentDescription = "Previous bookmark", modifier = Modifier.size(16.dp))
                }
                Text(
                    text = "$bookmarkCount \u25CF",
                    style = MaterialTheme.typography.labelSmall,
                )
                IconButton(
                    onClick = onNextBookmark,
                    modifier = Modifier
                        .size(36.dp)
                        .semantics { contentDescription = "Next bookmark" },
                ) {
                    Icon(Icons.Default.Bookmark, contentDescription = "Next bookmark", modifier = Modifier.size(16.dp))
                }
            }

            // Merge left-to-right (right gets left's content)
            IconButton(
                onClick = onMergeLeftToRight,
                enabled = canMerge && totalDiffs > 0 && !isHunkMerged,
                modifier = Modifier
                    .size(36.dp)
                    .semantics { contentDescription = "Merge left to right" },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Merge left to right",
                    modifier = Modifier.size(20.dp),
                )
            }

            // Merge right-to-left (left gets right's content)
            IconButton(
                onClick = onMergeRightToLeft,
                enabled = canMerge && totalDiffs > 0 && !isHunkMerged,
                modifier = Modifier
                    .size(36.dp)
                    .semantics { contentDescription = "Merge right to left" },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Merge right to left",
                    modifier = Modifier.size(20.dp),
                )
            }

            // Accept all (text button with direction indicator)
            TextButton(
                onClick = onAcceptAllLeftToRight,
                enabled = canMerge && totalDiffs > 0,
            ) {
                Text("All", style = MaterialTheme.typography.labelSmall)
            }

            // Find
            IconButton(onClick = onFind) {
                Icon(Icons.Default.Search, "Find")
            }
        }
    }
}
