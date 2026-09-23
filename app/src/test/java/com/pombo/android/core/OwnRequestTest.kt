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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session must not answer the key request it sent itself, and must answer
 * the one another device of the same account sent. Request ids travel in the
 * device sync so a later wrap opens anywhere, which makes them no evidence of
 * who sent them.
 */
class OwnRequestTest {

    private val adminPriv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val admin = EthereumSigner.address(adminPriv).lowercase()
    private val me = "0x" + "77".repeat(20)
    private val stream = "$admin/own-1"
    private val keysStream = "$admin/own-4"

    private val published = mutableListOf<JSONObject>()
    private var gateChecks = 0

    private fun manager(): EpochKeyManager {
        val store = mockk<EpochKeyStore>(relaxed = true)
        val epochs = JSONObject().put("1.k", JSONObject()
            .put("keyHex", "0x" + "11".repeat(32)).put("keyHash", "0xh1").put("epoch", 1))
        val announces = JSONObject()
        for (epoch in 1..2) {
            announces.put("$epoch", JSONObject().put("keyId", "$epoch.k").put("keyHash", "0xh$epoch")
                .put("publisher", admin).put("timestamp", epoch * 100L).put("validFrom", epoch * 100L))
        }
        every { store.load(stream) } returns JSONObject()
            .put("epochs", epochs).put("announces", announces).put("currentEpoch", 2)
            .put("pendingRequests", JSONObject().put("other-device",
                JSONObject().put("fromEpoch", 1).put("sentAt", System.currentTimeMillis())))
        return EpochKeyManager(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            myAddress = { me },
            publishKeys = { _, data -> published += data; null },
            resendKeys = { emptyList() },
            onKeyAdopted = { _, _ -> },
            checkGateAccess = { _, _ -> gateChecks += 1; true },
            myPrivateKey = { null }
        ).also { runBlocking { it.loadPersistedState(stream) } }
    }

    private fun request(requestId: String) = JSONObject()
        .put("t", StreamConstants.KEY_REQUEST).put("requestId", requestId)
        .put("pubkey", EthereumSigner.compressedPublicKey(adminPriv)).put("fromEpoch", 1)

    @Test
    fun `answers a request another device sent, even once its id has synced here`() = runBlocking {
        val keys = manager()

        keys.handleKeysMessage(stream, keysStream, request("other-device"), me, 600L, memberCount = 1)

        assertEquals(1, gateChecks)
        assertTrue(published.any { it.optString("t") == StreamConstants.KEY_WRAP && it.optString("requestId") == "other-device" })
    }

    @Test
    fun `does not answer the request this session sent`() = runBlocking {
        val keys = manager()
        keys.ensureChannelKeys(stream, keysStream)
        val sent = published.firstOrNull { it.optString("t") == StreamConstants.KEY_REQUEST }
        assertNotNull(sent)
        published.clear()

        keys.handleKeysMessage(stream, keysStream, request(sent!!.getString("requestId")), me, 600L, memberCount = 1)

        assertEquals(0, gateChecks)
        assertEquals(emptyList<JSONObject>(), published)
    }
}
