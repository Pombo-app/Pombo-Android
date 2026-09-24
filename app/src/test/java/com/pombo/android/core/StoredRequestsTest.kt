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
 * Stored key requests pile up: a member who waits asks again every minute, and
 * every session of every responder reads them back from -4. Each account is
 * answered through its newest request, and a new session must remember every
 * wrap it reads, or the -4 fills with the same wraps again.
 */
class StoredRequestsTest {

    private val adminPriv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val admin = EthereumSigner.address(adminPriv).lowercase()
    private val me = "0x" + "77".repeat(20)
    private val alice = "0x" + "a1".repeat(20)
    private val carol = "0x" + "c3".repeat(20)
    private val stream = "$admin/chan-1"
    private val keysStream = "$admin/chan-4"
    private val hour = 3_600_000L

    private val published = mutableListOf<JSONObject>()
    private var gateChecks = 0
    private var stored: List<EpochKeyManager.Entry> = emptyList()

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
            resendKeys = { stored },
            onKeyAdopted = { _, _ -> },
            checkGateAccess = { _, _ -> gateChecks += 1; true },
            myPrivateKey = { null }
        ).also { runBlocking { it.loadPersistedState(stream) } }
    }

    private fun request(requestId: String, from: String, at: Long, spk: Boolean = true) = EpochKeyManager.Entry(
        JSONObject().put("t", StreamConstants.KEY_REQUEST).put("requestId", requestId)
            .put("pubkey", EthereumSigner.compressedPublicKey(adminPriv)).put("fromEpoch", 1)
            .apply { if (spk) put("spk", "0x02" + "ef".repeat(32)) },
        from, at)

    private fun wrap(requestId: String, keyId: String, at: Long) = EpochKeyManager.Entry(
        JSONObject().put("t", StreamConstants.KEY_WRAP).put("v", 2).put("requestId", requestId)
            .put("keyId", keyId).put("epoch", keyId.substringBefore('.').toInt())
            .put("tag", "t").put("epk", "e").put("iv", "i").put("ct", "c"),
        admin, at)

    @Test fun `answers each account through its newest request only`() = runBlocking {
        val now = System.currentTimeMillis()
        val keys = manager()

        val picked = keys.storedRequestsToAnswer(stream, listOf(
            request("a-old", alice, now - 5 * hour),
            request("c-only", carol, now - 3 * hour),
            request("a-mid", alice, now - 2 * hour),
            request("a-new", alice, now - hour)
        ), now).map { it.data.optString("requestId") }

        assertEquals(listOf("a-new", "c-only"), picked.sorted())
    }

    @Test fun `keeps the answer window for requests without a static key`() = runBlocking {
        val now = System.currentTimeMillis()
        val keys = manager()

        val picked = keys.storedRequestsToAnswer(stream, listOf(
            request("v1-recent", alice, now - 60_000L, spk = false),
            request("v1-stale", carol, now - hour, spk = false)
        ), now).map { it.data.optString("requestId") }

        assertEquals(listOf("v1-recent"), picked)
    }

    @Test fun `a new session reading 150 answered requests answers none of them again`() = runBlocking {
        val now = System.currentTimeMillis()
        stored = (0 until 150).flatMap { i ->
            val who = "0x" + i.toString(16).padStart(40, '0')
            listOf(request("r$i", who, now - hour + i), wrap("r$i", "1.k", now - hour + i + 1),
                wrap("r$i", "2.k", now - hour + i + 2))
        }
        val keys = manager()

        keys.ensureChannelKeys(stream, keysStream, allowMint = false, memberCount = 1, gated = true)

        assertEquals(0, gateChecks)
        assertEquals(0, published.count { it.optString("t") == StreamConstants.KEY_WRAP })
    }
}
