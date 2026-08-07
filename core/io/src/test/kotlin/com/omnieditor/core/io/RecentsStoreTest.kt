package com.omnieditor.core.io

import com.omnieditor.core.model.SourceKind
import com.omnieditor.core.model.SourceRef
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class RecentsStoreTest {

    private lateinit var storeFile: File
    private lateinit var store: RecentsStore

    private fun ref(id: String, label: String = id) = SourceRef(
        id = id, kind = SourceKind.LOCAL, path = "/test/$id", label = label,
    )

    @Before
    fun setUp() {
        storeFile = File(System.getProperty("java.io.tmpdir"), "omni-recents-test-${System.nanoTime()}.json")
        store = RecentsStore(storeFile)
    }

    @After
    fun tearDown() {
        storeFile.delete()
    }

    @Test
    fun `initially empty`() = runTest {
        store.getRecents() shouldBe emptyList()
    }

    @Test
    fun `add recent appears at front`() = runTest {
        store.addRecent(ref("a"))
        store.addRecent(ref("b"))
        val recents = store.getRecents()
        recents.size shouldBe 2
        recents[0].id shouldBe "b"
        recents[1].id shouldBe "a"
    }

    @Test
    fun `re-adding moves to front`() = runTest {
        store.addRecent(ref("a"))
        store.addRecent(ref("b"))
        store.addRecent(ref("a")) // re-add
        val recents = store.getRecents()
        recents.size shouldBe 2
        recents[0].id shouldBe "a"
    }

    @Test
    fun `caps at 100`() = runTest {
        for (i in 0 until 110) {
            store.addRecent(ref("item-$i"))
        }
        store.getRecents().size shouldBe 100
    }

    @Test
    fun `remove recent`() = runTest {
        store.addRecent(ref("a"))
        store.addRecent(ref("b"))
        store.removeRecent("a")
        val recents = store.getRecents()
        recents.size shouldBe 1
        recents[0].id shouldBe "b"
    }

    @Test
    fun `clear recents`() = runTest {
        store.addRecent(ref("a"))
        store.addRecent(ref("b"))
        store.clearRecents()
        store.getRecents() shouldBe emptyList()
    }

    @Test
    fun `toggle favourite`() = runTest {
        store.isFavourite("a") shouldBe false
        store.toggleFavourite("a")
        store.isFavourite("a") shouldBe true
        store.toggleFavourite("a")
        store.isFavourite("a") shouldBe false
    }

    @Test
    fun `get favourite ids`() = runTest {
        store.toggleFavourite("a")
        store.toggleFavourite("b")
        store.getFavouriteIds() shouldBe setOf("a", "b")
    }

    @Test
    fun `clear recents preserves favourites`() = runTest {
        store.addRecent(ref("a"))
        store.toggleFavourite("a")
        store.clearRecents()
        store.getRecents() shouldBe emptyList()
        store.isFavourite("a") shouldBe true
    }

    @Test
    fun `persists across instances`() = runTest {
        store.addRecent(ref("a"))
        store.toggleFavourite("a")

        // New instance reading same file
        val store2 = RecentsStore(storeFile)
        store2.getRecents().size shouldBe 1
        store2.getRecents()[0].id shouldBe "a"
        store2.isFavourite("a") shouldBe true
    }

    @Test
    fun `handles corrupted file gracefully`() = runTest {
        storeFile.writeText("not valid json {{{")
        val store2 = RecentsStore(storeFile)
        store2.getRecents() shouldBe emptyList()
    }
}
