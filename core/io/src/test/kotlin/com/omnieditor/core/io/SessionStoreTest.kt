package com.omnieditor.core.io

import com.omnieditor.core.model.CompareMode
import com.omnieditor.core.model.Session
import com.omnieditor.core.model.SessionSummary
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class SessionStoreTest {

    private lateinit var sessionDir: File
    private lateinit var store: SessionStore

    private fun session(id: String, name: String = id, pinned: Boolean = false, lastRunAt: Long? = null) =
        Session(
            id = id, name = name, mode = CompareMode.TEXT,
            pinned = pinned, createdAt = System.currentTimeMillis(),
            lastRunAt = lastRunAt,
        )

    @Before
    fun setUp() {
        sessionDir = File(System.getProperty("java.io.tmpdir"), "omni-session-test-${System.nanoTime()}")
        store = SessionStore(sessionDir)
    }

    @After
    fun tearDown() {
        sessionDir.deleteRecursively()
    }

    @Test
    fun `save and load round-trip`() = runTest {
        val s = session("s1", "My Compare")
        store.save(s)
        val loaded = store.load("s1")
        loaded shouldNotBe null
        loaded!!.name shouldBe "My Compare"
        loaded.mode shouldBe CompareMode.TEXT
    }

    @Test
    fun `load missing returns null`() = runTest {
        store.load("nonexistent") shouldBe null
    }

    @Test
    fun `listAll returns sessions sorted by lastRunAt`() = runTest {
        store.save(session("a", lastRunAt = 100))
        store.save(session("b", lastRunAt = 300))
        store.save(session("c", lastRunAt = 200))
        val all = store.listAll()
        all.size shouldBe 3
        all[0].id shouldBe "b"
        all[1].id shouldBe "c"
        all[2].id shouldBe "a"
    }

    @Test
    fun `listPinned filters`() = runTest {
        store.save(session("a", pinned = true))
        store.save(session("b", pinned = false))
        store.save(session("c", pinned = true))
        store.listPinned().size shouldBe 2
    }

    @Test
    fun `search by name`() = runTest {
        store.save(session("a", "nginx config"))
        store.save(session("b", "deploy script"))
        store.save(session("c", "nginx logs"))
        val results = store.search("nginx")
        results.size shouldBe 2
    }

    @Test
    fun `search case insensitive`() = runTest {
        store.save(session("a", "Config File"))
        store.search("config").size shouldBe 1
    }

    @Test
    fun `delete removes session`() = runTest {
        store.save(session("a"))
        store.delete("a")
        store.load("a") shouldBe null
        store.count() shouldBe 0
    }

    @Test
    fun `togglePin flips pin state`() = runTest {
        store.save(session("a", pinned = false))
        store.togglePin("a")
        store.load("a")!!.pinned shouldBe true
        store.togglePin("a")
        store.load("a")!!.pinned shouldBe false
    }

    @Test
    fun `rename updates name`() = runTest {
        store.save(session("a", "old name"))
        store.rename("a", "new name")
        store.load("a")!!.name shouldBe "new name"
    }

    @Test
    fun `duplicate creates new session`() = runTest {
        store.save(session("a", "original"))
        val copy = store.duplicate("a", "b", "copy of original")
        copy shouldNotBe null
        copy!!.id shouldBe "b"
        copy.name shouldBe "copy of original"
        store.count() shouldBe 2
    }

    @Test
    fun `persists across instances`() = runTest {
        store.save(session("a", "persistent"))
        val store2 = SessionStore(sessionDir)
        store2.load("a")!!.name shouldBe "persistent"
    }

    @Test
    fun `session with summary round-trips`() = runTest {
        val s = session("a").copy(
            lastSummary = SessionSummary(hunkCount = 42, linesAdded = 10)
        )
        store.save(s)
        val loaded = store.load("a")!!
        loaded.lastSummary shouldNotBe null
        loaded.lastSummary!!.hunkCount shouldBe 42
    }

    @Test
    fun `count returns correct number`() = runTest {
        store.count() shouldBe 0
        store.save(session("a"))
        store.save(session("b"))
        store.count() shouldBe 2
    }

    @Test
    fun `handles corrupted session file`() = runTest {
        sessionDir.mkdirs()
        File(sessionDir, "bad.json").writeText("not json {{{")
        store.listAll() // should not crash
    }
}
