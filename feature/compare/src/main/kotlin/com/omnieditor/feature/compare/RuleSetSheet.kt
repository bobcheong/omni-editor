package com.omnieditor.feature.compare

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.omnieditor.core.model.ColumnRange
import com.omnieditor.core.model.Granularity
import com.omnieditor.core.model.LinePattern
import com.omnieditor.core.model.MarkerPair
import com.omnieditor.core.model.MatchPosition
import com.omnieditor.core.model.RuleSet
import com.omnieditor.core.model.WhitespaceRule

/**
 * Count how many rules differ from the defaults.
 */
fun countActiveRules(rules: RuleSet): Int {
    val default = RuleSet.DEFAULT
    var count = 0
    if (rules.ignoreCase != default.ignoreCase) count++
    if (rules.whitespace != default.whitespace) count++
    if (rules.ignoreBlankLines != default.ignoreBlankLines) count++
    if (rules.ignoreLineEndings != default.ignoreLineEndings) count++
    if (rules.linePatterns.isNotEmpty()) count++
    if (rules.betweenMarkers.isNotEmpty()) count++
    if (rules.headSkip > 0) count++
    if (rules.tailSkip > 0) count++
    if (rules.columnRanges.isNotEmpty()) count++
    if (rules.granularity != default.granularity) count++
    return count
}

/**
 * Modal bottom sheet exposing every [RuleSet] field for live editing.
 *
 * Simple toggles and enum selectors sit at the top; advanced list-based
 * rules (line patterns, markers, column ranges) are below a divider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleSetSheet(
    ruleSet: RuleSet,
    onRuleSetChanged: (RuleSet) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("Compare Rules", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            // ── Simple toggles ──
            SwitchRow("Ignore case", ruleSet.ignoreCase) {
                onRuleSetChanged(ruleSet.copy(ignoreCase = it))
            }
            SwitchRow("Ignore blank lines", ruleSet.ignoreBlankLines) {
                onRuleSetChanged(ruleSet.copy(ignoreBlankLines = it))
            }
            SwitchRow("Ignore line endings", ruleSet.ignoreLineEndings) {
                onRuleSetChanged(ruleSet.copy(ignoreLineEndings = it))
            }

            Spacer(Modifier.height(12.dp))

            // ── Whitespace rule ──
            SectionLabel("Whitespace")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val entries = WhitespaceRule.entries
                entries.forEachIndexed { index, rule ->
                    SegmentedButton(
                        selected = ruleSet.whitespace == rule,
                        onClick = { onRuleSetChanged(ruleSet.copy(whitespace = rule)) },
                        shape = SegmentedButtonDefaults.itemShape(index, entries.size),
                    ) {
                        Text(
                            when (rule) {
                                WhitespaceRule.NONE -> "None"
                                WhitespaceRule.TRAILING -> "Trail"
                                WhitespaceRule.LEADING_AND_TRAILING -> "Lead+Trail"
                                WhitespaceRule.ALL -> "All"
                            },
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Granularity ──
            SectionLabel("Granularity")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val entries = Granularity.entries
                entries.forEachIndexed { index, g ->
                    SegmentedButton(
                        selected = ruleSet.granularity == g,
                        onClick = { onRuleSetChanged(ruleSet.copy(granularity = g)) },
                        shape = SegmentedButtonDefaults.itemShape(index, entries.size),
                    ) {
                        Text(
                            when (g) {
                                Granularity.LINE -> "Line"
                                Granularity.WORD -> "Word"
                                Granularity.CHARACTER -> "Char"
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Head / Tail skip ──
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntField(
                    label = "Head skip",
                    value = ruleSet.headSkip,
                    onValueChanged = { onRuleSetChanged(ruleSet.copy(headSkip = it.coerceAtLeast(0))) },
                    modifier = Modifier.weight(1f),
                )
                IntField(
                    label = "Tail skip",
                    value = ruleSet.tailSkip,
                    onValueChanged = { onRuleSetChanged(ruleSet.copy(tailSkip = it.coerceAtLeast(0))) },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // ── Line patterns ──
            ListSection(
                title = "Line Patterns",
                count = ruleSet.linePatterns.size,
                onAdd = {
                    onRuleSetChanged(
                        ruleSet.copy(
                            linePatterns = ruleSet.linePatterns +
                                LinePattern(MatchPosition.BEGINS_WITH, ""),
                        ),
                    )
                },
            ) {
                ruleSet.linePatterns.forEachIndexed { idx, pattern ->
                    LinePatternRow(
                        pattern = pattern,
                        onChanged = { updated ->
                            onRuleSetChanged(
                                ruleSet.copy(
                                    linePatterns = ruleSet.linePatterns.toMutableList().also {
                                        it[idx] = updated
                                    },
                                ),
                            )
                        },
                        onRemove = {
                            onRuleSetChanged(
                                ruleSet.copy(
                                    linePatterns = ruleSet.linePatterns.toMutableList().also {
                                        it.removeAt(idx)
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Between markers ──
            ListSection(
                title = "Between Markers",
                count = ruleSet.betweenMarkers.size,
                onAdd = {
                    onRuleSetChanged(
                        ruleSet.copy(
                            betweenMarkers = ruleSet.betweenMarkers + MarkerPair("", ""),
                        ),
                    )
                },
            ) {
                ruleSet.betweenMarkers.forEachIndexed { idx, pair ->
                    MarkerPairRow(
                        pair = pair,
                        onChanged = { updated ->
                            onRuleSetChanged(
                                ruleSet.copy(
                                    betweenMarkers = ruleSet.betweenMarkers.toMutableList().also {
                                        it[idx] = updated
                                    },
                                ),
                            )
                        },
                        onRemove = {
                            onRuleSetChanged(
                                ruleSet.copy(
                                    betweenMarkers = ruleSet.betweenMarkers.toMutableList().also {
                                        it.removeAt(idx)
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Column ranges ──
            ListSection(
                title = "Column Ranges",
                count = ruleSet.columnRanges.size,
                onAdd = {
                    onRuleSetChanged(
                        ruleSet.copy(
                            columnRanges = ruleSet.columnRanges + ColumnRange(1, 1, compare = true),
                        ),
                    )
                },
            ) {
                ruleSet.columnRanges.forEachIndexed { idx, range ->
                    ColumnRangeRow(
                        range = range,
                        onChanged = { updated ->
                            onRuleSetChanged(
                                ruleSet.copy(
                                    columnRanges = ruleSet.columnRanges.toMutableList().also {
                                        it[idx] = updated
                                    },
                                ),
                            )
                        },
                        onRemove = {
                            onRuleSetChanged(
                                ruleSet.copy(
                                    columnRanges = ruleSet.columnRanges.toMutableList().also {
                                        it.removeAt(idx)
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Reset button ──
            TextButton(
                onClick = { onRuleSetChanged(RuleSet.DEFAULT) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Reset to Defaults")
            }
        }
    }
}

// ── Reusable composables ──

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    onValueChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = if (value == 0) "" else value.toString(),
        onValueChange = { text ->
            onValueChanged(text.filter { it.isDigit() }.toIntOrNull() ?: 0)
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}

@Composable
private fun ListSection(
    title: String,
    count: Int,
    onAdd: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$title${if (count > 0) " ($count)" else ""}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = "Add $title")
        }
    }
    content()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinePatternRow(
    pattern: LinePattern,
    onChanged: (LinePattern) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Match position selector (compact)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(0.45f)) {
            val entries = MatchPosition.entries
            entries.forEachIndexed { index, pos ->
                SegmentedButton(
                    selected = pattern.match == pos,
                    onClick = { onChanged(pattern.copy(match = pos)) },
                    shape = SegmentedButtonDefaults.itemShape(index, entries.size),
                ) {
                    Text(
                        when (pos) {
                            MatchPosition.BEGINS_WITH -> "Start"
                            MatchPosition.CONTAINS -> "Has"
                            MatchPosition.ENDS_WITH -> "End"
                        },
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        OutlinedTextField(
            value = pattern.text,
            onValueChange = { onChanged(pattern.copy(text = it)) },
            placeholder = { Text("pattern") },
            singleLine = true,
            modifier = Modifier.weight(0.45f),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove pattern")
        }
    }
}

@Composable
private fun MarkerPairRow(
    pair: MarkerPair,
    onChanged: (MarkerPair) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = pair.start,
            onValueChange = { onChanged(pair.copy(start = it)) },
            placeholder = { Text("start") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = pair.end,
            onValueChange = { onChanged(pair.copy(end = it)) },
            placeholder = { Text("end") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove marker pair")
        }
    }
}

@Composable
private fun ColumnRangeRow(
    range: ColumnRange,
    onChanged: (ColumnRange) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = range.from.toString(),
            onValueChange = { text ->
                val v = text.filter { it.isDigit() }.toIntOrNull() ?: 1
                val newFrom = v.coerceAtLeast(1)
                val newTo = range.to.coerceAtLeast(newFrom)
                onChanged(ColumnRange(newFrom, newTo, range.compare))
            },
            label = { Text("From") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = range.to.toString(),
            onValueChange = { text ->
                val v = text.filter { it.isDigit() }.toIntOrNull() ?: 1
                val newTo = v.coerceAtLeast(range.from)
                onChanged(ColumnRange(range.from, newTo, range.compare))
            },
            label = { Text("To") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = range.compare,
            onCheckedChange = { onChanged(ColumnRange(range.from, range.to, it)) },
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove column range")
        }
    }
}
