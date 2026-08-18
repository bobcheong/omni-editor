package com.omnieditor.app

import com.omnieditor.core.io.FileSystemSourceProvider
import com.omnieditor.core.io.SourceProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DIST-2: the direct-install flavour works with real filesystem paths.
 * Requires MANAGE_EXTERNAL_STORAGE permission, requested at first file access
 * behind a rationale screen (T-05).
 *
 * Sessions store paths — they are durable because the permission is global.
 * This is why the direct flavour exists: SAF paths are not durable in the
 * same way, and folder compare needs stable references.
 *
 * All filesystem logic lives in [FileSystemSourceProvider] (core/io, pure JVM).
 * This class is a thin Hilt wrapper so the DI graph has a concrete binding.
 */
@Singleton
class DirectSourceProvider @Inject constructor() : SourceProvider
    by FileSystemSourceProvider(rootDir = null)
