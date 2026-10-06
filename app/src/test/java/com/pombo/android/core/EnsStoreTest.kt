package com.pombo.android.core

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EnsStoreTest {

    @get:Rule val dir = TemporaryFolder()

    private val context: Context = mockk { every { filesDir } answers { dir.root } }
    private val cacheFile get() = File(dir.root, "ens-cache.json")
    private val twentyMinutesAgo get() = System.currentTimeMillis() - 20 * 60 * 1000L

    private fun storeOnDisk(entry: JSONObject) =
        cacheFile.writeText(JSONObject().put("0xabc", entry).toString())

    @Test
    fun `a no-name the lookup returned is stored as confirmed`() = runBlocking {
        assertNull(EnsStore(context).name("0xABC") { null })
        assertTrue(JSONObject(cacheFile.readText()).getJSONObject("0xabc").getBoolean("nameConfirmed"))
    }

    @Test
    fun `a confirmed no-name is not looked up again within the day`() = runBlocking {
        storeOnDisk(JSONObject().put("name", JSONObject.NULL).put("nameConfirmed", true).put("at", twentyMinutesAgo))
        val store = EnsStore(context).apply { warmUp() }
        var lookups = 0
        assertNull(store.name("0xabc") { lookups++; "late.eth" })
        assertEquals(0, lookups)
    }

    @Test
    fun `a no-name nobody confirmed is looked up again after fifteen minutes`() = runBlocking {
        storeOnDisk(JSONObject().put("name", JSONObject.NULL).put("at", twentyMinutesAgo))
        val store = EnsStore(context).apply { warmUp() }
        assertEquals("found.eth", store.name("0xabc") { "found.eth" })
    }

    @Test
    fun `a lookup made before warmUp sees what is on disk`() = runBlocking {
        storeOnDisk(JSONObject().put("name", "on.disk.eth").put("at", twentyMinutesAgo))
        var lookups = 0
        assertEquals("on.disk.eth", EnsStore(context).name("0xabc") { lookups++; "net.eth" })
        assertEquals(0, lookups)
    }

    @Test
    fun `concurrent first lookups all wait for the disk`() = runBlocking {
        storeOnDisk(
            JSONObject().put("name", JSONObject.NULL).put("nameConfirmed", true).put("at", twentyMinutesAgo)
                .put("avatar", "https://a.example/a.png").put("avatarAt", twentyMinutesAgo)
        )
        val store = EnsStore(context)
        var lookups = 0
        val results = listOf(
            async(Dispatchers.Default) { store.name("0xabc") { lookups++; "net.eth" } },
            async(Dispatchers.Default) { store.name("0xABC") { lookups++; "net.eth" } },
            async(Dispatchers.Default) { store.avatar("0xabc") { lookups++; "https://net/a.png" } }
        ).awaitAll()
        assertEquals(listOf(null, null, "https://a.example/a.png"), results)
        assertEquals(0, lookups)
    }
}
