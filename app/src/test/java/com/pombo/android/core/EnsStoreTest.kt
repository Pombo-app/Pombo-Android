package com.pombo.android.core

import android.content.Context
import io.mockk.every
import io.mockk.mockk
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
}
