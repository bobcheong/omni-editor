package com.omnieditor.core.model

import kotlinx.serialization.Serializable

/**
 * A durable pointer to one side of a compare, or to one open document.
 *
 * DIST-2: both a path and a URI grant are carried. The `direct` flavour resolves by
 * [path]; the `store` flavour requires [uriGrant]. Neither side of the app knows which
 * — that is SourceProvider's job.
 */
@Serializable
data class SourceRef(
    val id: String,
    val kind: SourceKind,
    val path: String? = null,
    val uriGrant: String? = null,
    val label: String,
    val connectionId: String? = null,
    val archiveEntry: String? = null,
    val gitRef: String? = null,
    val charset: String? = null,
    val fingerprint: Fingerprint? = null,
) {
    init {
        require(path != null || uriGrant != null || kind == SourceKind.SNIPPET) {
            "A SourceRef needs a path, a URI grant, or must be a snippet"
        }
    }
}

enum class SourceKind { LOCAL, REMOTE, URL, ARCHIVE, GIT, SNIPPET }

/** Cheap identity check: lets a moved or externally-changed file be detected (OE-MRG-7). */
@Serializable
data class Fingerprint(
    val size: Long,
    val modifiedAt: Long,
    val edgeHash: String,
)

enum class Slot { LEFT, MIDDLE, RIGHT }
