package com.omnieditor.app

import android.content.Intent
import android.net.Uri
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import java.util.UUID

/**
 * Routes incoming intents to the appropriate screen (OE-SRC-4, OE-SRC-6).
 *
 * Share-sheet ingestion:
 * - ACTION_SEND with one file → held as left side, prompt for right
 * - ACTION_SEND_MULTIPLE with two files → compare opens immediately
 * - ACTION_SEND with plain text → snippet compare
 * - ACTION_VIEW → open file in editor
 *
 * All from a cold start.
 */
object IntentRouter {

    sealed interface IntentAction {
        /** Open a file in the editor. */
        data class OpenFile(val source: SourceRef) : IntentAction

        /** Start a compare with one source pre-filled, prompt for the other. */
        data class CompareWithPrompt(val source: SourceRef) : IntentAction

        /** Start a compare directly with two sources. */
        data class CompareDirectly(val left: SourceRef, val right: SourceRef) : IntentAction

        /** Open snippet compare with pasted text. */
        data class SnippetCompare(val text: String) : IntentAction

        /** No actionable intent — show Home. */
        data object ShowHome : IntentAction
    }

    fun route(intent: Intent?): IntentAction {
        if (intent == null) return IntentAction.ShowHome

        return when (intent.action) {
            Intent.ACTION_SEND -> handleSend(intent)
            Intent.ACTION_SEND_MULTIPLE -> handleSendMultiple(intent)
            Intent.ACTION_VIEW -> handleView(intent)
            else -> IntentAction.ShowHome
        }
    }

    private fun handleSend(intent: Intent): IntentAction {
        // Check for plain text first (snippet compare)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text != null) {
            return IntentAction.SnippetCompare(text)
        }

        // Check for a file URI
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri != null) {
            val ref = uriToSourceRef(uri, intent.type)
            return IntentAction.CompareWithPrompt(ref)
        }

        return IntentAction.ShowHome
    }

    @Suppress("DEPRECATION")
    private fun handleSendMultiple(intent: Intent): IntentAction {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (uris == null || uris.isEmpty()) return IntentAction.ShowHome

        if (uris.size == 1) {
            return IntentAction.CompareWithPrompt(uriToSourceRef(uris[0], intent.type))
        }

        // Two or more → compare the first two
        val left = uriToSourceRef(uris[0], intent.type)
        val right = uriToSourceRef(uris[1], intent.type)
        return IntentAction.CompareDirectly(left, right)
    }

    private fun handleView(intent: Intent): IntentAction {
        val uri = intent.data ?: return IntentAction.ShowHome
        val ref = uriToSourceRef(uri, intent.type)
        return IntentAction.OpenFile(ref)
    }

    private fun uriToSourceRef(uri: Uri, mimeType: String?): SourceRef {
        val label = uri.lastPathSegment ?: uri.toString().substringAfterLast('/')
        return SourceRef(
            id = UUID.randomUUID().toString(),
            kind = SourceKind.LOCAL,
            uriGrant = uri.toString(),
            label = label,
        )
    }
}
