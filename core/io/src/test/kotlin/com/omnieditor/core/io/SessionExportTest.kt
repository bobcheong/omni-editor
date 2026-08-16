package com.omnieditor.core.io

import com.omnieditor.core.model.CompareMode
import com.omnieditor.core.model.Session
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class SessionExportTest {

    private lateinit var dir: File
    private lateinit var store: SessionStore

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "session-export-test-${System.nanoTime()}")
        dir.mkdirs()
        store = SessionStore(dir)
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun `exportAsJson produces valid JSON with schemaVersion`() = runTest {
        val session = Session(id = "s1", name = "Test", mode = CompareMode.TEXT, createdAt = 1000L)
        store.save(session)
        val json = store.exportAsJson("s1")
        json shouldContain "\"schemaVersion\""
        json shouldContain "\"name\":\"Test\""
    }

    @Test
    fun `importFromJson round-trips a session`() = runTest {
        val session = Session(id = "s1", name = "Export Test", mode = CompareMode.TEXT, createdAt = 2000L)
        store.save(session)
        val json = store.exportAsJson("s1")

        val imported = store.importFromJson(json)
        imported.name shouldBe "Export Test"
        imported.mode shouldBe CompareMode.TEXT
    }

    @Test
    fun `group CRUD works`() = runTest {
        val group = store.createGroup("My Group")
        group.name shouldBe "My Group"

        val session = Session(id = "s1", name = "Test", mode = CompareMode.TEXT, createdAt = 1000L)
        store.save(session)
        store.addToGroup(group.id, "s1")

        val groups = store.listGroups()
        groups.size shouldBe 1
        groups[0].sessionIds shouldBe listOf("s1")

        store.removeFromGroup(group.id, "s1")
        store.listGroups()[0].sessionIds shouldBe emptyList()

        store.deleteGroup(group.id)
        store.listGroups().size shouldBe 0
    }
}
