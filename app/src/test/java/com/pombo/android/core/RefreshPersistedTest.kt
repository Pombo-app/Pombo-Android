package com.pombo.android.core

import com.pombo.android.data.EpochKeyStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sync pull writes keys and announces to the store, but a channel this
 * session already hydrated keeps its in-memory state: loadPersistedState runs
 * once per channel. Without a re-read the synced material only took effect
 * after an app restart, and history stayed unreadable because the
 * kid-freshness rule judges against announces the state never received.
 */
class RefreshPersistedTest {

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

    private fun manager(store: EpochKeyStore) = EpochKeyManager(
        store = store,
        scope = CoroutineScope(Dispatchers.Unconfined),
        myAddress = { "0xme" },
        publishKeys = { _, _ -> },
        resendKeys = { emptyList() },
        onKeyAdopted = { _, _ -> }
    )

    @Test
    fun `a hydrated channel picks up what the sync wrote after it loaded`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns null
        val keys = manager(store)

        keys.loadPersistedState(stream)
        assertFalse("nothing persisted yet", keys.hasCurrentKey(stream))

        // What a sync pull leaves behind, after this channel was hydrated.
        every { store.load(stream) } returns persisted(1)

        keys.loadPersistedState(stream)
        assertFalse("once per channel: the second call is a no-op",
            keys.hasCurrentKey(stream))

        keys.refreshPersisted()
        assertTrue("the re-read applies it without a restart", keys.hasCurrentKey(stream))
    }

    @Test
    fun `later announces reach a state that already had earlier ones`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns persisted(1)
        val keys = manager(store)

        keys.loadPersistedState(stream)
        assertTrue(keys.hasCurrentKey(stream))

        every { store.load(stream) } returns persisted(1, 2, 3)
        keys.refreshPersisted()

        // currentEpoch follows the highest announce, and its key came with it.
        assertTrue("epoch 3 is in force and readable", keys.hasCurrentKey(stream))
    }

    @Test
    fun `a channel this session never opened is left alone`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(any()) } returns persisted(1)
        val keys = manager(store)

        keys.refreshPersisted()

        assertFalse("no hydrated state to refresh", keys.hasCurrentKey(stream))
    }
}
