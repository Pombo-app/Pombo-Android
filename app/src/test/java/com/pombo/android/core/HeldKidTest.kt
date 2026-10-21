package com.pombo.android.core

import com.pombo.android.data.EpochKeyStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A key on disk is a key held, even before anything opened its channel. A
 * kid that looked missing because the state was not loaded yet kept the
 * device asking for keys it holds, on every ensure, for the whole session.
 */
class HeldKidTest {

    private val stream = "0xowner/chan-1"
    private val keys = "0xowner/chan-4"
    private val heldKid = "1.aaaaaaaaaaaa"
    private val keyHex = "0x" + "11".repeat(32)

    private fun persisted(): JSONObject = JSONObject()
        .put("epochs", JSONObject().put(heldKid, JSONObject()
            .put("keyHex", keyHex).put("keyHash", "0x" + "22".repeat(32)).put("epoch", 1)))
        .put("announces", JSONObject().put("1", JSONObject()
            .put("keyId", heldKid).put("keyHash", "0x" + "22".repeat(32))
            .put("publisher", "0xowner").put("timestamp", 1_000L).put("validFrom", 1_000L)))
        .put("currentEpoch", 1)

    private val published = mutableListOf<JSONObject>()
    private fun requests() = published.filter { it.optString("t") == StreamConstants.KEY_REQUEST }

    private fun manager(store: EpochKeyStore) = EpochKeyManager(
        store = store,
        scope = CoroutineScope(Dispatchers.Unconfined),
        myAddress = { "0xme" },
        publishKeys = { _, data -> published += data; null },
        resendKeys = { emptyList() },
        onKeyAdopted = { _, _ -> }
    )

    @Test
    fun `a message read before the channel was loaded opens, and nothing is asked for`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns persisted()
        val epochKeys = manager(store)
        val envelope = EpochKeyCrypto.encryptWithEpochKey(JSONObject().put("text", "hello"), keyHex, heldKid)

        val plain = epochKeys.tryDecrypt(stream, keys, envelope, gated = true, live = true)
        epochKeys.ensureChannelKeys(stream, keys)

        assertNotNull(plain)
        assertEquals("hello", plain!!.optString("text"))
        assertEquals(0, requests().size)
    }

    @Test
    fun `a kid noted missing stops being asked for once its key lands`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns null
        val epochKeys = manager(store)
        val envelope = EpochKeyCrypto.encryptWithEpochKey(JSONObject().put("text", "hello"), keyHex, heldKid)
        epochKeys.tryDecrypt(stream, keys, envelope, gated = true, live = true)

        // What a sync pull leaves behind after the kid was noted.
        every { store.load(stream) } returns persisted()
        epochKeys.refreshPersisted()
        epochKeys.ensureChannelKeys(stream, keys)

        assertEquals(0, requests().size)
    }

    @Test
    fun `a kid no held key opens is still asked for`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns persisted()
        val epochKeys = manager(store)
        val foreign = EpochKeyCrypto.encryptWithEpochKey(
            JSONObject().put("text", "later"), "0x" + "33".repeat(32), "2.bbbbbbbbbbbb")

        epochKeys.tryDecrypt(stream, keys, foreign, gated = false, live = true)

        assertEquals(1, requests().size)
        assertEquals(2, requests().single().optInt("fromEpoch"))
    }
}
