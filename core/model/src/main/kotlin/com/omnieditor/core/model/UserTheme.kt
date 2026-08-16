package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * F-14: User-created theme with token colour overrides.
 * Imported/exported as JSON.
 */
@Serializable
data class UserTheme(
    val id: String,
    val name: String,
    val schemaVersion: Int = 1,
    val tokenColors: Map<String, String> = emptyMap(),
    val editorBackground: String? = null,
    val editorForeground: String? = null,
)
