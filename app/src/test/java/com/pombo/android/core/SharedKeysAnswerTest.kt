package com.pombo.android.core

import com.pombo.android.data.EpochKeyStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who receives each shared key of a Sealed channel. In a read-only channel the
 * publish key IS the write capability, so only the owner and the moderators
 * get it; the interactions key goes to every member, because reacting and
 * showing presence is all the participation a read-only member has.
 */
class SharedKeysAnswerTest {

    private val adminPriv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val admin = EthereumSigner.address(adminPriv).lowercase()
    private val me = "0x" + "77".repeat(20)
    private val requester = "0x" + "11".repeat(20)
    private val stream = "$admin/sealed-1"
    private val keysStream = "$admin/sealed-4"

    private val published = mutableListOf<JSONObject>()

    private fun shared(keyId: String, byte: String) = JSONObject()
        .put("keyId", keyId).put("keyHex", "0x" + byte.repeat(32))
        .put("address", "0x" + byte.repeat(20)).put("rev", 1)

    private fun announce(keyId: String, byte: String) = JSONObject()
        .put("keyId", keyId).put("keyHash", "0xh").put("address", "0x" + byte.repeat(20))
        .put("rev", 1).put("publisher", admin).put("timestamp", 100L)

    private fun manager(sealed: Boolean, mayHoldPublishKey: Boolean): EpochKeyManager {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns JSONObject()
            .put("epochs", JSONObject().put("1.k", JSONObject()
                .put("keyHex", "0x" + "11".repeat(32)).put("keyHash", "0xh1").put("epoch", 1)))
            .put("announces", JSONObject().put("1", JSONObject().put("keyId", "1.k").put("keyHash", "0xh1")
                .put("publisher", admin).put("timestamp", 100L).put("validFrom", 100L)))
            .put("currentEpoch", 1)
            .put("pubKey", shared("p1.k", "22")).put("pubAnnounce", announce("p1.k", "22"))
            .put("intKey", shared("i1.k", "33")).put("intAnnounce", announce("i1.k", "33"))
        return EpochKeyManager(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            myAddress = { me },
            publishKeys = { _, data -> published += data; null },
            resendKeys = { emptyList() },
            onKeyAdopted = { _, _ -> },
            checkGateAccess = { _, _ -> true },
            myPrivateKey = { null },
            sharedPublishFor = { sealed },
            mayHoldPublishKey = { _, _ -> mayHoldPublishKey }
        ).also { runBlocking { it.loadPersistedState(stream) } }
    }

    private val request = JSONObject()
        .put("t", StreamConstants.KEY_REQUEST).put("requestId", "req-1")
        .put("pubkey", EthereumSigner.compressedPublicKey(adminPriv)).put("fromEpoch", 1)

    private fun sharedKeysSent() = published
        .filter { it.optString("t") == StreamConstants.PUB_WRAP }
        .map { if (it.optString("k") == "i") "interactions" else "publish" }
        .sorted()

    @Test
    fun `whoever may hold the publish key gets both shared keys`() = runBlocking {
        manager(sealed = true, mayHoldPublishKey = true)
            .handleKeysMessage(stream, keysStream, request, requester, 600L, memberCount = 1)

        assertEquals(listOf("interactions", "publish"), sharedKeysSent())
    }

    @Test
    fun `a plain member of a read-only channel gets the interactions key only`() = runBlocking {
        manager(sealed = true, mayHoldPublishKey = false)
            .handleKeysMessage(stream, keysStream, request, requester, 600L, memberCount = 1)

        assertEquals(listOf("interactions"), sharedKeysSent())
        assertTrue(published.any { it.optString("t") == StreamConstants.KEY_WRAP })
    }

    @Test
    fun `a record that does not say Sealed hands out no shared key`() = runBlocking {
        manager(sealed = false, mayHoldPublishKey = true)
            .handleKeysMessage(stream, keysStream, request, requester, 600L, memberCount = 1)

        assertEquals(emptyList<String>(), sharedKeysSent())
        assertTrue(published.any { it.optString("t") == StreamConstants.KEY_WRAP })
    }
}
