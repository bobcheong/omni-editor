package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * F-13: A named group of sessions for organisation.
 */
@Serializable
data class SessionGroup(
    val id: String,
    val name: String,
    val sessionIds: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
)
