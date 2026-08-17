package com.omnieditor.core.model

/**
 * Every failure in the app is one of these. There is no generic error path (CLAUDE.md):
 * each variant maps to exactly one named user-facing state in OE-SPEC-001 §13.
 *
 * If you need a failure this interface cannot express, add a variant AND its UI state
 * in the same change. Do not widen an existing variant to cover it.
 */
sealed interface OmniError {
    data class AccessRevoked(val ref: SourceRef) : OmniError
    data class NotReachable(val connectionId: String, val cause: NetCause) : OmniError
    data class AuthFailed(val connectionId: String, val kind: AuthKind) : OmniError
    data class HostKeyChanged(val connectionId: String, val fingerprint: String) : OmniError
    data class Unsupported(val what: String, val by: String) : OmniError
    data class TooLarge(val bytes: Long, val limit: Long) : OmniError
    data class NoTextLayer(val ref: SourceRef) : OmniError
    data class WriteFailed(val ref: SourceRef, val partial: Boolean) : OmniError
    data class DecodeFailed(val ref: SourceRef, val attemptedCharset: String) : OmniError
    data class ExternallyModified(val path: String) : OmniError
    data object Cancelled : OmniError
}

enum class NetCause { DNS, REFUSED, TIMEOUT, TLS, INTERRUPTED }
enum class AuthKind { PASSWORD, KEY, PERMISSION }

class OmniException(val error: OmniError) : Exception(error.toString())
