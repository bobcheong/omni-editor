package com.omnieditor.feature.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Find and replace bar for the editor (OE-EDT-4, OE-FND-1).
 *
 * Shows a search field with match count, prev/next navigation,
 * and an expandable replace row with replace/replace-all actions.
 * Filter chips for case, whole word, and regex options.
 */
@Composable
fun FindReplaceBar(
    visible: Boolean,
    matchCount: Int,
    currentMatch: Int,
    onSearch: (String) -> Unit,
    onReplace: (String) -> Unit,
    onReplaceAll: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    onOptionsChanged: (caseSensitive: Boolean, wholeWord: Boolean, regex: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible) {
        Surface(
            tonalElevation = 2.dp,
            modifier = modifier.fillMaxWidth(),
        ) {
            var searchText by remember { mutableStateOf("") }
            var replaceText by remember { mutableStateOf("") }
            var showReplace by remember { mutableStateOf(false) }
            var caseSensitive by remember { mutableStateOf(false) }
            var wholeWord by remember { mutableStateOf(false) }
            var regex by remember { mutableStateOf(false) }

            Column(modifier = Modifier.padding(8.dp)) {
                // Search row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = {
                            searchText = it
                            onSearch(it)
                        },
                        placeholder = { Text("Find", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )

                    if (matchCount > 0) {
                        Text(
                            text = "${currentMatch + 1}/$matchCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    IconButton(onClick = onPrevious, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.KeyboardArrowUp, "Previous", Modifier.size(18.dp))
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.KeyboardArrowDown, "Next", Modifier.size(18.dp))
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, "Close find", Modifier.size(18.dp))
                    }
                }

                // Option chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    FilterChip(
                        selected = caseSensitive,
                        onClick = {
                            caseSensitive = !caseSensitive
                            onOptionsChanged(caseSensitive, wholeWord, regex)
                            onSearch(searchText)
                        },
                        label = { Text("Aa", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp),
                    )
                    FilterChip(
                        selected = wholeWord,
                        onClick = {
                            wholeWord = !wholeWord
                            onOptionsChanged(caseSensitive, wholeWord, regex)
                            onSearch(searchText)
                        },
                        label = { Text("W", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp),
                    )
                    FilterChip(
                        selected = regex,
                        onClick = {
                            regex = !regex
                            onOptionsChanged(caseSensitive, wholeWord, regex)
                            onSearch(searchText)
                        },
                        label = { Text(".*", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp),
                    )
                    TextButton(onClick = { showReplace = !showReplace }) {
                        Text(if (showReplace) "Hide replace" else "Replace", fontSize = 12.sp)
                    }
                }

                // Replace row (expandable)
                AnimatedVisibility(visible = showReplace) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        OutlinedTextField(
                            value = replaceText,
                            onValueChange = { replaceText = it },
                            placeholder = { Text("Replace", fontSize = 13.sp) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onReplace(replaceText) }) {
                            Text("Replace", fontSize = 12.sp)
                        }
                        TextButton(onClick = { onReplaceAll(replaceText) }) {
                            Text("All", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
