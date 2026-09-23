package com.pombo.android.core

import com.pombo.android.data.EpochKeyStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A reset publish key exists on this device alone until sync carries it to
 * the account's other devices, so the reset has to reach sync.
 */
class RekeyPublishKeyTest {

    private val adminPriv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val admin = EthereumSigner.address(adminPriv).lowercase()
    private val stream = "$admin/sealed-1"
    private val keysStream = "$admin/sealed-4"
    private val oldAddress = "0x" + "44".repeat(20)

    private val events = mutableListOf<String>()
    private val adopted = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun manager(): EpochKeyManager {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns JSONObject()
            .put("pubKey", JSONObject().put("keyId", "p1.k").put("keyHex", "0x" + "44".repeat(32))
                .put("address", oldAddress).put("rev", 1))
            .put("pubAnnounce", JSONObject().put("keyId", "p1.k").put("keyHash", "0xh")
                .put("address", oldAddress).put("rev", 1).put("publisher", admin).put("timestamp", 100L))
        return EpochKeyManager(
            store = store,
            scope = scope,
            myAddress = { admin },
            publishKeys = { _, _ -> events += "announce"; null },
            resendKeys = { emptyList() },
            onKeyAdopted = { _, keyId -> events += "adopted"; adopted += keyId },
            checkGateAccess = { _, _ -> true },
            myPrivateKey = { null }
        ).also { runBlocking { it.loadPersistedState(stream) } }
    }

    @Test
    fun `hands the new publish key to sync once it is announced`() = runBlocking {
        val keys = manager()

        val rev = keys.rekeyPublishKey(stream, keysStream) { _, _ -> events += "grants" }

        assertEquals(2, rev)
        assertEquals(listOf("grants", "announce", "adopted"), events)
        assertEquals(keys.publishKeyFor(stream)?.keyId, adopted.single())
    }
}
