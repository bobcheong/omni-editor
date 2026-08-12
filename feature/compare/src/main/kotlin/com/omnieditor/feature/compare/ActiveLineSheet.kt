package com.omnieditor.feature.compare

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import com.omnieditor.design.LocalCompareColors

/**
 * Active-line inspector bottom sheet (OE-TXT-7).
 *
 * Shows the current line from every side stacked vertically with
 * intra-line highlighting. This is how a phone user sees "side by side"
 * for the line that matters.
 *
 * For CONFLICT hunks, resolution buttons are shown: take left, take base (if available),
 * take right, and a disabled "Edit manually" placeholder (R-32).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveLineSheet(
    visible: Boolean,
    hunk: Hunk?,
    leftLines: List<String>,
    rightLines: List<String>,
    onDismiss: () -> Unit,
    /** Called when the user taps "← Accept left" (copy left into right). Null hides the button. */
    onMergeLeftToRight: (() -> Unit)? = null,
    /** Called when the user taps "→ Accept right" (copy right into left). Null hides the button. */
    onMergeRightToLeft: (() -> Unit)? = null,
    /**
     * Base lines for 3-way conflict resolution — null when not in a 3-way merge context.
     * When non-null and [hunk.type] is CONFLICT, a "Take base" button is shown.
     */
    baseLines: List<String>? = null,
    /** Called when the user taps "Take base" — apply base content as the resolution. Null hides the button. */
    onTakeBase: (() -> Unit)? = null,
) {
    if (!visible || hunk == null) return

    val colors = LocalCompareColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val isConflict = hunk.type == HunkType.CONFLICT

    // Collect left and right text for copy actions.
    val leftText = (hunk.leftStart until hunk.leftEnd)
        .filter { it < leftLines.size }
        .joinToString("\n") { leftLines[it.toInt()] }
    val rightText = (hunk.rightStart until hunk.rightEnd)
        .filter { it < rightLines.size }
        .joinToString("\n") { rightLines[it.toInt()] }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            // Header
            Text(
                text = if (isConflict) "Conflict resolution" else "Line inspector",
                style = MaterialTheme.typography.titleSmall,
                color = if (isConflict) colors.conflictFg else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Left side lines
            if (hunk.leftEnd > hunk.leftStart) {
                SideLabel(
                    label = if (isConflict) "Left (yours)" else "Left",
                    color = if (isConflict) colors.conflictFg else colors.removedFg,
                )
                for (i in hunk.leftStart until hunk.leftEnd) {
                    if (i < leftLines.size) {
                        LineContent(
                            lineNumber = i + 1,
                            text = leftLines[i.toInt()],
                            bgColor = if (isConflict) colors.conflictBg else colors.removedBg,
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Base lines (3-way conflict only)
            if (isConflict && baseLines != null && baseLines.isNotEmpty()) {
                SideLabel("Base (common ancestor)", colors.gutter)
                baseLines.forEachIndexed { idx, line ->
                    LineContent(
                        lineNumber = (idx + 1).toLong(),
                        text = line,
                        bgColor = Color.Transparent,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            // Right side lines
            if (hunk.rightEnd > hunk.rightStart) {
                SideLabel(
                    label = if (isConflict) "Right (theirs)" else "Right",
                    color = if (isConflict) colors.conflictFg else colors.addedFg,
                )
                for (i in hunk.rightStart until hunk.rightEnd) {
                    if (i < rightLines.size) {
                        LineContent(
                            lineNumber = i + 1,
                            text = rightLines[i.toInt()],
                            bgColor = if (isConflict) colors.conflictBg else colors.addedBg,
                        )
                    }
                }
            }

            // Hunk info
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) {
                        append(
                            when (hunk.type) {
                                HunkType.ADDED -> "Added"
                                HunkType.REMOVED -> "Removed"
                                HunkType.CHANGED -> "Changed"
                                HunkType.CONFLICT -> "Conflict"
                            }
                        )
                    }
                    append(" · L${hunk.leftStart + 1}–${hunk.leftEnd}")
                    append(" → R${hunk.rightStart + 1}–${hunk.rightEnd}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Conflict resolution buttons (R-32)
            if (isConflict) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Take left
                    if (onMergeLeftToRight != null) {
                        Button(
                            onClick = { onMergeLeftToRight(); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Take left (yours)")
                        }
                    }
                    // Take base
                    if (onTakeBase != null) {
                        Button(
                            onClick = { onTakeBase(); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Take base")
                        }
                    }
                    // Take right
                    if (onMergeRightToLeft != null) {
                        Button(
                            onClick = { onMergeRightToLeft(); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Take right (theirs)")
                        }
                    }
                    // Edit manually — deferred to P2, shown disabled (R-32).
                    // onClick is a no-op; enabled=false prevents the user from tapping it.
                    OutlinedButton(
                        onClick = { /* deferred — button is disabled */ },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Edit manually (not yet available)")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else if (onMergeLeftToRight != null || onMergeRightToLeft != null) {
                // Regular merge action buttons (← / →)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (onMergeLeftToRight != null) {
                        Button(
                            onClick = {
                                onMergeLeftToRight()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("← Accept left")
                        }
                    }
                    if (onMergeRightToLeft != null) {
                        Button(
                            onClick = {
                                onMergeRightToLeft()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Accept right →")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Copy buttons — one per side that has content.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (leftText.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(leftText)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Copy left")
                    }
                }
                if (rightText.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(rightText)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Copy right")
                    }
                }
            }
        }
    }
}

@Composable
private fun SideLabel(label: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun LineContent(
    lineNumber: Long,
    text: String,
    bgColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = "$lineNumber",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
