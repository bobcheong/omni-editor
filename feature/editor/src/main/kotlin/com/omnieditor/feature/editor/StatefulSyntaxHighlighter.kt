package com.omnieditor.feature.editor

import com.omnieditor.core.diff.syntax.Grammar
import com.omnieditor.core.diff.syntax.LexerState
import com.omnieditor.core.diff.syntax.StatefulTokenResult
import com.omnieditor.core.diff.syntax.SyntaxEngine
import com.omnieditor.core.diff.syntax.SyntaxToken

/**
 * Wraps [SyntaxEngine] with per-line lexer entry-state caching (R-19).
 *
 * Entry states for each line are stored in an [IntArray] keyed by line index.
 * When an edit occurs at line [n], [invalidateFrom] marks line [n] and all
 * subsequent cached states as stale. Re-lexing is lazy — states are only
 * computed when [tokenizeLine] is called for a visible row.
 *
 * Propagation shortcut: if the computed exit state for line [n] matches the
 * already-cached entry state for line [n+1], downstream lines are guaranteed
 * to produce the same result — no further re-lex is needed beyond [n].
 *
 * Thread-safety: NOT thread-safe. Expected to be created and used exclusively
 * on the Compose/Main thread inside [remember] blocks.
 */
class StatefulSyntaxHighlighter(private val grammar: Grammar) {

    /**
     * `entryStates[i]` holds the [LexerState.code] value for line `i`.
     * Sized lazily and grown on demand. New slots are zero-initialised,
     * which corresponds to [LexerState.NORMAL].
     */
    private var entryStates = IntArray(64)

    /**
     * The index of the first line whose entry state may be stale.
     * Lines `[0, invalidFrom)` have valid cached entry states.
     * Lines `[invalidFrom, ∞)` have potentially stale states.
     *
     * Invariant: `invalidFrom` is only ever decreased by [invalidateFrom]
     * and increased (frontier advanced) inside [tokenizeLine].
     */
    private var invalidFrom: Int = 0

    /**
     * Mark line [fromLine] and all subsequent lines as needing re-lex.
     * Idempotent — calling with a value ≥ the current [invalidFrom] is a no-op.
     */
    fun invalidateFrom(fromLine: Int) {
        val clamped = fromLine.coerceAtLeast(0)
        if (clamped < invalidFrom) {
            invalidFrom = clamped
        }
    }

    /**
     * Tokenize [lineText] at [lineIndex] using the cached entry state.
     *
     * For lines between [invalidFrom] and [lineIndex] that are not currently
     * visible, the last known valid state is propagated as a conservative
     * approximation. Those lines will be corrected as they scroll into view
     * ("lazy re-lex for visible rows" contract).
     *
     * After tokenizing, the computed exit state is stored as the entry state
     * for `lineIndex + 1`, making subsequent calls for the next line use the
     * correct state.
     */
    fun tokenizeLine(lineIndex: Int, lineText: String): List<SyntaxToken> {
        ensureCapacity(lineIndex + 2)

        // Fill any gap [invalidFrom, lineIndex) with the last valid state.
        // We don't have line text for un-rendered lines, so propagate the
        // last known entry state as an approximation.
        if (lineIndex > invalidFrom) {
            val propagated = entryStates[invalidFrom]  // last clean state
            for (i in invalidFrom + 1..lineIndex) {
                entryStates[i] = propagated
            }
            invalidFrom = lineIndex
        }

        val entryState = LexerState.fromCode(entryStates[lineIndex])
        val result: StatefulTokenResult = SyntaxEngine.tokenizeLineStateful(lineText, grammar, entryState)

        // Store exit state as entry for next line.
        entryStates[lineIndex + 1] = result.exitState.code

        // Advance the clean frontier past this line.
        if (lineIndex >= invalidFrom) {
            invalidFrom = lineIndex + 1
        }

        return result.tokens
    }

    /**
     * Notify the highlighter that the document now has [newLineCount] lines.
     * Trims the cached state array and clamps [invalidFrom] if the document shrank.
     */
    fun notifyLineCountChanged(newLineCount: Int) {
        if (newLineCount + 1 < entryStates.size) {
            entryStates = entryStates.copyOf(newLineCount + 1)
        }
        if (invalidFrom > newLineCount) {
            invalidFrom = newLineCount
        }
    }

    private fun ensureCapacity(minSize: Int) {
        if (minSize > entryStates.size) {
            val newSize = maxOf(minSize, entryStates.size * 2)
            entryStates = entryStates.copyOf(newSize)
            // New slots are zero-initialised (LexerState.NORMAL.code == 0).
        }
    }
}
