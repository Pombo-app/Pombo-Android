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
 * An admin that rotates seals the new epoch key to its own account on -4/P1,
 * so any later session of that account adopts it from storage without a
 * request and without a responder. Members ignore those wraps.
 */
class SelfWrapTest {

    private val priv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val me = EthereumSigner.address(priv).lowercase()
    private val spk = EthereumSigner.compressedPublicKey(priv)
    private val stream = "$me/chan-1"
    private val keysStream = "$me/chan-4"
    private val epochKey = "0x4242424242424242424242424242424242424242424242424242424242424242"

    private val published = mutableListOf<JSONObject>()
    private var served: List<EpochKeyManager.Entry> = emptyList()

    private fun manager(store: EpochKeyStore, address: String = me) = EpochKeyManager(
        store = store,
        scope = CoroutineScope(Dispatchers.Unconfined),
        myAddress = { address },
        publishKeys = { _, data -> published += data },
        resendKeys = { served },
        onKeyAdopted = { _, _ -> },
        myPrivateKey = { priv }
    )

    private fun persisted(epoch: Int): JSONObject {
        val keyId = "$epoch.aaaaaaaaaaaa"
        return JSONObject()
            .put("epochs", JSONObject().put(keyId, JSONObject()
                .put("keyHex", "0x" + "11".repeat(32)).put("keyHash", "0x" + "22".repeat(32)).put("epoch", epoch)))
            .put("announces", JSONObject().put(epoch.toString(), JSONObject()
                .put("keyId", keyId).put("keyHash", "0x" + "22".repeat(32))
                .put("publisher", me).put("timestamp", 1_000L).put("validFrom", 1_000L)))
            .put("currentEpoch", epoch)
    }

    private fun selfWrapRows(epoch: Int, keyId: String): List<EpochKeyManager.Entry> {
        val keyHash = EpochKeyCrypto.computeKeyHash(epochKey)
        val wrapped = EpochKeyCrypto.wrapEpochKeyToStatic(epochKey, spk)
        val announce = JSONObject()
            .put("t", StreamConstants.KEY_ANNOUNCE).put("epoch", epoch).put("keyId", keyId)
            .put("keyHash", keyHash).put("validFrom", 3_000L)
        val wrap = JSONObject()
            .put("t", StreamConstants.KEY_WRAP).put("v", 2).put("requestId", "self")
            .put("keyId", keyId).put("epoch", epoch)
            .put("tag", EpochKeyCrypto.computeWrapTagV2("self", keyId))
            .put("epk", wrapped.getString("epk")).put("iv", wrapped.getString("iv")).put("ct", wrapped.getString("ct"))
        return listOf(
            EpochKeyManager.Entry(announce, me, 3_000L),
            EpochKeyManager.Entry(wrap, me, 4_000L)
        )
    }

    @Test
    fun `a rotation seals the new key to the account, and the account key opens it`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns persisted(1)
        val keys = manager(store)

        val epoch = keys.rotateEpoch(stream, keysStream)

        assertEquals(2, epoch)
        val announce = published.single { it.optString("t") == StreamConstants.KEY_ANNOUNCE }
        val wrap = published.single { it.optString("t") == StreamConstants.KEY_WRAP }
        assertEquals("self", wrap.optString("requestId"))
        assertEquals(2, wrap.optInt("v"))
        assertEquals(announce.optString("keyId"), wrap.optString("keyId"))
        assertEquals(EpochKeyCrypto.computeWrapTagV2("self", wrap.optString("keyId")), wrap.optString("tag"))
        val opened = EpochKeyCrypto.unwrapEpochKeyStatic(wrap, priv)
        assertEquals(announce.optString("keyHash").lowercase(), EpochKeyCrypto.computeKeyHash(opened).lowercase())
    }

    @Test
    fun `a rotation numbers above an announce that only storage holds`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns persisted(1)
        served = listOf(EpochKeyManager.Entry(JSONObject()
            .put("t", StreamConstants.KEY_ANNOUNCE).put("epoch", 5).put("keyId", "5.elsewhere")
            .put("keyHash", "0x" + "ab".repeat(32)).put("validFrom", 2_000L), me, 2_000L))
        val keys = manager(store)

        val epoch = keys.rotateEpoch(stream, keysStream)

        assertEquals(6, epoch)
    }

    @Test
    fun `a fresh admin session adopts the stored self wrap without asking anyone`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns null
        served = selfWrapRows(3, "3.deadbeef42")
        val keys = manager(store)

        keys.ensureChannelKeys(stream, keysStream)

        assertTrue(keys.hasCurrentKey(stream))
        assertTrue(published.none { it.optString("t") == StreamConstants.KEY_REQUEST })
    }

    @Test
    fun `a member ignores the admin's self wrap and asks instead`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns null
        served = selfWrapRows(3, "3.deadbeef42")
        val keys = manager(store, address = "0x" + "99".repeat(20))

        keys.ensureChannelKeys(stream, keysStream)

        assertFalse(keys.hasCurrentKey(stream))
        assertTrue(published.any { it.optString("t") == StreamConstants.KEY_REQUEST })
    }
}
