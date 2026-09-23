package com.pombo.android.core

import com.pombo.android.data.EpochKeyStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A responder skips every key another responder already wrapped for a
 * request, and with nothing left to send it has no reason to read the gate.
 */
class CoveredRequestTest {

    private val adminPriv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val admin = EthereumSigner.address(adminPriv).lowercase()
    private val me = "0x" + "77".repeat(20)
    private val requester = "0x" + "11".repeat(20)
    private val stream = "$admin/chan-1"
    private val keysStream = "$admin/chan-4"

    private val published = mutableListOf<JSONObject>()
    private var gateChecks = 0

    private fun manager(): EpochKeyManager {
        val store = mockk<EpochKeyStore>(relaxed = true)
        val epochs = JSONObject()
        val announces = JSONObject()
        for (epoch in 1..2) {
            epochs.put("$epoch.k", JSONObject()
                .put("keyHex", "0x" + "$epoch$epoch".repeat(32)).put("keyHash", "0xh$epoch").put("epoch", epoch))
            announces.put("$epoch", JSONObject().put("keyId", "$epoch.k").put("keyHash", "0xh$epoch")
                .put("publisher", admin).put("timestamp", epoch * 100L).put("validFrom", epoch * 100L))
        }
        every { store.load(stream) } returns JSONObject()
            .put("epochs", epochs).put("announces", announces).put("currentEpoch", 2)
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

    private fun wrapBySomeoneElse(keyId: String) = JSONObject()
        .put("t", StreamConstants.KEY_WRAP).put("requestId", "req-1").put("keyId", keyId)
        .put("epoch", keyId.substringBefore('.').toInt()).put("tag", "t").put("epk", "e").put("iv", "i").put("ct", "c")

    private val request = JSONObject()
        .put("t", StreamConstants.KEY_REQUEST).put("requestId", "req-1")
        .put("pubkey", EthereumSigner.compressedPublicKey(adminPriv)).put("fromEpoch", 1)

    @Test
    fun `costs no gate read when every key it asks for is already wrapped`() = runBlocking {
        val keys = manager()
        keys.handleKeysMessage(stream, keysStream, wrapBySomeoneElse("1.k"), admin, 500L)
        keys.handleKeysMessage(stream, keysStream, wrapBySomeoneElse("2.k"), admin, 501L)

        keys.handleKeysMessage(stream, keysStream, request, requester, 600L, memberCount = 1)

        assertEquals(0, gateChecks)
        assertEquals(emptyList<JSONObject>(), published)
    }

    @Test
    fun `is still checked and answered for the key nobody wrapped yet`() = runBlocking {
        val keys = manager()
        keys.handleKeysMessage(stream, keysStream, wrapBySomeoneElse("1.k"), admin, 500L)

        keys.handleKeysMessage(stream, keysStream, request, requester, 600L, memberCount = 1)

        assertEquals(1, gateChecks)
        assertEquals(listOf("2.k"), published.map { it.optString("keyId") })
    }
}
