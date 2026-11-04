package com.pombo.android.core

import com.pombo.android.data.EpochKeyStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sync pull or a backup restore brings keys and announces for channels this
 * session may already have hydrated: loadPersistedState runs once per channel.
 * The import has to reach that state in the same step it reaches the store, or
 * the open channel stays unreadable and the next persist of the stale state
 * erases what was imported.
 */
class ApplyPersistedTest {

    private val stream = "0xowner/chan-1"

    private fun persisted(vararg epochs: Int): JSONObject {
        val keys = JSONObject()
        val announces = JSONObject()
        for (e in epochs) {
            val keyId = "$e.aaaaaaaaaaaa"
            keys.put(keyId, JSONObject()
                .put("keyHex", "0x" + "11".repeat(32))
                .put("keyHash", "0x" + "22".repeat(32))
                .put("epoch", e))
            announces.put(e.toString(), JSONObject()
                .put("keyId", keyId)
                .put("keyHash", "0x" + "22".repeat(32))
                .put("publisher", "0xadmin")
                .put("timestamp", 1_000L * e)
                .put("validFrom", 1_000L * e))
        }
        return JSONObject()
            .put("epochs", keys)
            .put("announces", announces)
            .put("currentEpoch", epochs.maxOrNull() ?: 0)
    }

    private val rows = mutableMapOf<String, JSONObject>()
    private val store = mockk<EpochKeyStore>(relaxed = true).also { s ->
        every { s.load(any()) } answers { rows[firstArg()] }
        every { s.save(any(), any()) } answers { rows[firstArg()] = secondArg() }
    }

    private val imported = mutableListOf<String>()

    private fun manager() = EpochKeyManager(
        store = store,
        scope = CoroutineScope(Dispatchers.Unconfined),
        myAddress = { "0xme" },
        publishKeys = { _, _ -> },
        resendKeys = { emptyList() },
        onKeyAdopted = { _, _ -> },
        onKeysImported = { imported += it }
    )

    private fun slice(vararg epochs: Int) = JSONObject().put(stream, persisted(*epochs))

    private fun storedKeyIds(): Set<String> =
        rows[stream]?.optJSONObject("epochs")?.keys()?.asSequence()?.toSet() ?: emptySet()

    @Test
    fun `a hydrated channel takes the imported keys without a restart`() = runBlocking {
        val keys = manager()
        keys.loadPersistedState(stream)
        assertFalse("nothing persisted yet", keys.hasCurrentKey(stream))

        keys.applyPersisted(slice(1))

        assertTrue("applied to the open state", keys.hasCurrentKey(stream))
        assertEquals("the open channel is told once", listOf(stream), imported)
    }

    @Test
    fun `a persist after the import keeps the imported keys`() = runBlocking {
        val keys = manager()
        keys.loadPersistedState(stream)

        keys.applyPersisted(slice(1))
        keys.adoptInteractionsKey(stream, keys.mintInteractionsKey())

        assertEquals(setOf("1.aaaaaaaaaaaa"), storedKeyIds())
    }

    @Test
    fun `later announces reach a state that already had earlier ones`() = runBlocking {
        rows[stream] = persisted(1)
        val keys = manager()
        keys.loadPersistedState(stream)
        assertTrue(keys.hasCurrentKey(stream))

        keys.applyPersisted(slice(1, 2, 3))

        // currentEpoch follows the highest announce, and its key came with it.
        assertTrue("epoch 3 is in force and readable", keys.hasCurrentKey(stream))
    }

    @Test
    fun `keys this device holds survive a slice that lacks them`() = runBlocking {
        rows[stream] = persisted(1, 2)
        val keys = manager()
        keys.loadPersistedState(stream)

        keys.applyPersisted(slice(1))

        assertEquals(setOf("1.aaaaaaaaaaaa", "2.aaaaaaaaaaaa"), storedKeyIds())
        assertTrue(keys.hasCurrentKey(stream))
        assertTrue("nothing new for the open channel", imported.isEmpty())
    }

    @Test
    fun `a channel this session never opened is only written to the store`() = runBlocking {
        val keys = manager()

        keys.applyPersisted(slice(1))

        assertEquals(setOf("1.aaaaaaaaaaaa"), storedKeyIds())
        assertTrue("no hydrated state to tell", imported.isEmpty())
        assertFalse(keys.hasCurrentKey(stream))
        keys.loadPersistedState(stream)
        assertTrue("its first open reads the import", keys.hasCurrentKey(stream))
    }
}
