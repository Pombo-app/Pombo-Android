package com.pombo.android.core

import com.pombo.android.data.EpochKeyStore
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resetting the interactions key: the grants move on-chain before anyone is
 * told about the new key, and the announce supersedes the old one by rev.
 */
class RekeyInteractionsTest {

    private val adminPriv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val admin = EthereumSigner.address(adminPriv).lowercase()
    private val stream = "$admin/sealed-1"
    private val keysStream = "$admin/sealed-4"
    private val oldAddress = "0x" + "33".repeat(20)

    private val events = mutableListOf<String>()
    private val published = mutableListOf<JSONObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun manager(me: String = admin): EpochKeyManager {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns JSONObject()
            .put("intKey", JSONObject().put("keyId", "i1.k").put("keyHex", "0x" + "33".repeat(32))
                .put("address", oldAddress).put("rev", 1))
            .put("intAnnounce", JSONObject().put("keyId", "i1.k").put("keyHash", "0xh")
                .put("address", oldAddress).put("rev", 1).put("publisher", admin).put("timestamp", 100L))
        return EpochKeyManager(
            store = store,
            scope = scope,
            myAddress = { me },
            publishKeys = { _, data -> events += "announce"; published += data; null },
            resendKeys = { emptyList() },
            onKeyAdopted = { _, _ -> events += "adopted" },
            checkGateAccess = { _, _ -> true },
            myPrivateKey = { null }
        ).also { runBlocking { it.loadPersistedState(stream) } }
    }

    @Test
    fun `moves the grants first, then announces the new key at the next rev`() = runBlocking {
        var granted: Pair<String, String?>? = null
        val keys = manager()

        val rev = keys.rekeyInteractionsKey(stream, keysStream) { next, old ->
            events += "grants"; granted = next to old
        }

        assertEquals(2, rev)
        // "adopted" is what hands the new key to the account's other devices through sync.
        assertEquals(listOf("grants", "announce", "adopted"), events)
        val ann = published.single()
        assertEquals("i", ann.getString("k"))
        assertEquals(2, ann.getInt("rev"))
        assertEquals(granted!!.first, ann.getString("addr"))
        assertEquals(oldAddress, granted!!.second)
        assertNotEquals(oldAddress, ann.getString("addr"))
        assertEquals(ann.getString("keyId"), keys.interactionsKeyFor(stream)?.keyId)
    }

    @Test
    fun `announces nothing when the grants did not move`() = runBlocking {
        val keys = manager()

        val failure = runCatching {
            keys.rekeyInteractionsKey(stream, keysStream) { _, _ -> throw IllegalStateException("tx reverted") }
        }

        assertTrue(failure.isFailure)
        assertTrue(published.isEmpty())
        assertEquals("i1.k", keys.interactionsKeyFor(stream)?.keyId)
    }

    @Test
    fun `is refused to anyone but the owner`() = runBlocking {
        val keys = manager(me = "0x" + "11".repeat(20))

        val failure = runCatching { keys.rekeyInteractionsKey(stream, keysStream) { _, _ -> events += "grants" } }

        assertTrue(failure.isFailure)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `the bridge sets several streams' permissions in one transaction`() {
        val asset = File("src/main/assets/pombo_bridge.html").readText()
        val start = asset.indexOf("async setPermissions(")
        val body = asset.substring(start, asset.indexOf("\n    },", start))
        assertTrue("setPermissions no longer takes a list of streams", body.contains("a.items"))
        assertTrue("the list is no longer sent as one call", body.contains("client.setPermissions(...items)"))
    }
}
