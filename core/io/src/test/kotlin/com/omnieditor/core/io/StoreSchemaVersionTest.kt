package com.omnieditor.core.io

import com.omnieditor.core.model.CompareMode
import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.CompareStats
import com.omnieditor.core.model.EngineMode
import com.omnieditor.core.model.Session
import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * R-34a: Graceful degradation on corrupt or unknown-versioned store files.
 *
 * Every store must return empty (not crash) when presented with:
 *   1. Invalid JSON (corrupt file)
 *   2. A future/unknown schemaVersion value
 */
class StoreSchemaVersionTest {

    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File(System.getProperty("java.io.tmpdir"), "omni-schema-test-${System.nanoTime()}")
        tmpDir.mkdirs()
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ── RecentsStore ─────────────────────────────────────────────────────────

    @Test
    fun `RecentsStore - corrupt file degrades to empty`() = runTest {
        val storeFile = File(tmpDir, "recents-corrupt.json")
        storeFile.writeText("not valid json {{{")
        val store = RecentsStore(storeFile)
        store.getRecents() shouldBe emptyList()
        store.getFavouriteIds() shouldBe emptySet()
    }

    @Test
    fun `RecentsStore - unknown schema version degrades to empty`() = runTest {
        val storeFile = File(tmpDir, "recents-future.json")
        // Simulate a file written by a future version of the app
        storeFile.writeText(
            """{"schemaVersion":999,"data":{"recents":[],"favouriteIds":[]}}"""
        )
        val store = RecentsStore(storeFile)
        store.getRecents() shouldBe emptyList()
    }

    @Test
    fun `RecentsStore - valid version 1 round-trips correctly`() = runTest {
        val storeFile = File(tmpDir, "recents-v1.json")
        val store = RecentsStore(storeFile)
        val ref = SourceRef(
            id = "r1",
            kind = SourceKind.LOCAL,
            path = "/test/file.txt",
            label = "file.txt",
        )
        store.addRecent(ref)
        store.toggleFavourite("r1")

        val store2 = RecentsStore(storeFile)
        store2.getRecents().size shouldBe 1
        store2.getRecents()[0].id shouldBe "r1"
        store2.isFavourite("r1") shouldBe true
    }

    // ── SessionStore ─────────────────────────────────────────────────────────

    @Test
    fun `SessionStore - corrupt file degrades to empty list`() = runTest {
        val sessionDir = File(tmpDir, "sessions-corrupt")
        sessionDir.mkdirs()
        File(sessionDir, "bad.json").writeText("not valid json {{{")
        val store = SessionStore(sessionDir)
        store.listAll() shouldBe emptyList()
        store.count() shouldBe 0
    }

    @Test
    fun `SessionStore - unknown schema version file is skipped`() = runTest {
        val sessionDir = File(tmpDir, "sessions-future")
        sessionDir.mkdirs()
        // Write a session file with a future schema version
        File(sessionDir, "s1.json").writeText(
            """{"schemaVersion":999,"data":{"id":"s1","name":"Old","mode":"TEXT","createdAt":1000}}"""
        )
        val store = SessionStore(sessionDir)
        // The unknown-version session must be skipped, not crash
        store.listAll() shouldBe emptyList()
        store.load("s1") shouldBe null
    }

    @Test
    fun `SessionStore - valid version 1 round-trips correctly`() = runTest {
        val sessionDir = File(tmpDir, "sessions-v1")
        val store = SessionStore(sessionDir)
        val session = Session(
            id = "s1",
            name = "My Session",
            mode = CompareMode.TEXT,
            createdAt = System.currentTimeMillis(),
        )
        store.save(session)

        val store2 = SessionStore(sessionDir)
        val loaded = store2.load("s1")
        loaded shouldNotBe null
        loaded!!.name shouldBe "My Session"
    }

    // ── ResultStore ──────────────────────────────────────────────────────────

    private val sampleResult = CompareResult(
        hunks = emptyList(),
        stats = CompareStats(hunkCount = 0),
        engineMode = EngineMode.FULL_INDEX,
        generatedAt = 1700000000000L,
    )

    @Test
    fun `ResultStore - corrupt file degrades to null and is deleted`() = runTest {
        val cacheDir = File(tmpDir, "results-corrupt")
        cacheDir.mkdirs()
        val badFile = File(cacheDir, "bad-session.json")
        badFile.writeText("not valid json {{{")
        val store = ResultStore(cacheDir)
        store.load("bad-session") shouldBe null
        badFile.exists() shouldBe false
    }

    @Test
    fun `ResultStore - unknown schema version degrades to null and is deleted`() = runTest {
        val cacheDir = File(tmpDir, "results-future")
        cacheDir.mkdirs()
        // Write a result with a future schema version
        val futureFile = File(cacheDir, "future-session.json")
        futureFile.writeText(
            """{"schemaVersion":999,"data":{"hunks":[],"stats":{"linesAdded":0,"linesRemoved":0,"linesChanged":0,"hunkCount":0},"engineMode":"FULL_INDEX","generatedAt":1000}}"""
        )
        val store = ResultStore(cacheDir)
        store.load("future-session") shouldBe null
        futureFile.exists() shouldBe false
    }

    @Test
    fun `ResultStore - valid version 1 round-trips correctly`() = runTest {
        val cacheDir = File(tmpDir, "results-v1")
        cacheDir.mkdirs()
        val store = ResultStore(cacheDir)
        store.store("session-1", sampleResult)

        val store2 = ResultStore(cacheDir)
        val loaded = store2.load("session-1")
        loaded shouldNotBe null
        loaded!!.engineMode shouldBe EngineMode.FULL_INDEX
    }
}
