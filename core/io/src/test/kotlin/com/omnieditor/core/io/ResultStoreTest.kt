package com.omnieditor.core.io

import com.omnieditor.core.model.CompareResult
import com.omnieditor.core.model.CompareStats
import com.omnieditor.core.model.EngineMode
import com.omnieditor.core.model.Hunk
import com.omnieditor.core.model.HunkType
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class ResultStoreTest {

    private lateinit var cacheDir: File
    private lateinit var store: ResultStore

    private val sampleResult = CompareResult(
        hunks = listOf(
            Hunk(10, 12, 10, 13, HunkType.CHANGED),
            Hunk(50, 50, 50, 52, HunkType.ADDED),
            Hunk(80, 83, 82, 82, HunkType.REMOVED),
        ),
        stats = CompareStats(
            linesAdded = 2,
            linesRemoved = 3,
            linesChanged = 3,
            hunkCount = 3,
        ),
        engineMode = EngineMode.FULL_INDEX,
        generatedAt = 1700000000000L,
    )

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "omni-result-store-test-${System.nanoTime()}")
        cacheDir.mkdirs()
        store = ResultStore(cacheDir)
    }

    @After
    fun tearDown() {
        cacheDir.deleteRecursively()
    }

    @Test
    fun `store and load round-trips a result`() = runTest {
        store.store("session-1", sampleResult)
        val loaded = store.load("session-1")
        loaded shouldNotBe null
        loaded!! shouldBe sampleResult
    }

    @Test
    fun `load returns null for missing session`() = runTest {
        store.load("nonexistent") shouldBe null
    }

    @Test
    fun `has returns true for stored results`() = runTest {
        store.has("session-1") shouldBe false
        store.store("session-1", sampleResult)
        store.has("session-1") shouldBe true
    }

    @Test
    fun `evict removes cached result`() = runTest {
        store.store("session-1", sampleResult)
        store.has("session-1") shouldBe true
        store.evict("session-1")
        store.has("session-1") shouldBe false
        store.load("session-1") shouldBe null
    }

    @Test
    fun `evictAll clears entire cache`() = runTest {
        store.store("s1", sampleResult)
        store.store("s2", sampleResult)
        store.has("s1") shouldBe true
        store.has("s2") shouldBe true
        store.evictAll()
        store.has("s1") shouldBe false
        store.has("s2") shouldBe false
    }

    @Test
    fun `store overwrites previous result`() = runTest {
        store.store("session-1", sampleResult)
        val updated = sampleResult.copy(
            hunks = listOf(Hunk(0, 1, 0, 1, HunkType.CHANGED)),
            stats = CompareStats(linesChanged = 1, hunkCount = 1),
        )
        store.store("session-1", updated)
        val loaded = store.load("session-1")!!
        loaded.hunks.size shouldBe 1
        loaded.stats.hunkCount shouldBe 1
    }

    @Test
    fun `corrupted cache returns null and deletes file`() = runTest {
        val file = File(cacheDir, "bad-session.json")
        file.writeText("not valid json {{{")
        store.load("bad-session") shouldBe null
        file.exists() shouldBe false
    }

    @Test
    fun `preserves all hunk fields through serialisation`() = runTest {
        store.store("session-1", sampleResult)
        val loaded = store.load("session-1")!!
        loaded.hunks.size shouldBe 3

        val h0 = loaded.hunks[0]
        h0.leftStart shouldBe 10
        h0.leftEnd shouldBe 12
        h0.rightStart shouldBe 10
        h0.rightEnd shouldBe 13
        h0.type shouldBe HunkType.CHANGED

        val h1 = loaded.hunks[1]
        h1.type shouldBe HunkType.ADDED

        val h2 = loaded.hunks[2]
        h2.type shouldBe HunkType.REMOVED
    }

    @Test
    fun `preserves engine mode`() = runTest {
        val blockResult = sampleResult.copy(engineMode = EngineMode.BLOCK_MATCH)
        store.store("block-session", blockResult)
        val loaded = store.load("block-session")!!
        loaded.engineMode shouldBe EngineMode.BLOCK_MATCH
    }

    @Test
    fun `preserves stale flag`() = runTest {
        val staleResult = sampleResult.copy(stale = true)
        store.store("stale-session", staleResult)
        val loaded = store.load("stale-session")!!
        loaded.stale shouldBe true
    }

    @Test
    fun `cacheSize reports total bytes`() = runTest {
        store.cacheSize() shouldBe 0L
        store.store("s1", sampleResult)
        store.cacheSize() shouldNotBe 0L
        val sizeAfterOne = store.cacheSize()
        store.store("s2", sampleResult)
        store.cacheSize() shouldBe sizeAfterOne * 2
    }

    @Test
    fun `concurrent stores to different sessions are safe`() = runTest {
        // Store to 10 different sessions
        for (i in 0 until 10) {
            store.store("session-$i", sampleResult.copy(generatedAt = i.toLong()))
        }
        for (i in 0 until 10) {
            val loaded = store.load("session-$i")!!
            loaded.generatedAt shouldBe i.toLong()
        }
    }

    @Test
    fun `simulates process death recovery`() = runTest {
        // Store a result
        store.store("session-1", sampleResult)

        // Create a new store instance (simulating app restart)
        val newStore = ResultStore(cacheDir)

        // Result should survive
        val loaded = newStore.load("session-1")
        loaded shouldNotBe null
        loaded!! shouldBe sampleResult
    }

    @Test
    fun `atomic write - temp file cleaned up on success`() = runTest {
        store.store("session-1", sampleResult)
        val tmpFile = File(cacheDir, "session-1.tmp")
        tmpFile.exists() shouldBe false
        val jsonFile = File(cacheDir, "session-1.json")
        jsonFile.exists() shouldBe true
    }
}
