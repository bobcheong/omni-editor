package com.omnieditor.feature.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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

/**
 * Bottom navigation bar for the compare view (OE-TXT-2).
 *
 * Previous/next difference controls, difference counter "7 / 42",
 * merge action, and find.
 */
@Composable
fun DiffNavigationBar(
    currentDiff: Int,
    totalDiffs: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMerge: () -> Unit,
    onFind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val counterText = if (totalDiffs > 0) "${currentDiff + 1} / $totalDiffs" else "No differences"

    Surface(
        tonalElevation = 3.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
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

            // Merge
            TextButton(onClick = onMerge, enabled = totalDiffs > 0) {
                Text("Merge")
            }

            // Find
            IconButton(onClick = onFind) {
                Icon(Icons.Default.Search, "Find")
            }
        }
    }
}
