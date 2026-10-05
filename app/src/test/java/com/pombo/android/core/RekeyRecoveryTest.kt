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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A re-key whose grant lands while the call fails must not cost the owner the
 * key: the new key is written down before the grant is sent, and the chain
 * decides which of the two keys survives, in the call or on the next open.
 */
class RekeyRecoveryTest {

    private val adminPriv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val admin = EthereumSigner.address(adminPriv).lowercase()
    private val stream = "$admin/sealed-1"
    private val keysStream = "$admin/sealed-4"
    private val oldInt = "0x" + "33".repeat(20)
    private val oldPub = "0x" + "44".repeat(20)
    private val pendingAddress = "0x" + "55".repeat(20)
    private val hour = 60 * 60 * 1000L
    private val twoStreams = listOf(stream, "$admin/sealed-2")

    private val saved = HashMap<String, JSONObject>()
    private val published = mutableListOf<JSONObject>()
    private val warnings = mutableListOf<String>()
    private val reads = mutableListOf<Boolean>()
    private var grants: () -> EpochKeyManager.RekeyGrants = { throw IllegalStateException("rpc down") }
    private var pendingPushes = 0
    private val completed = mutableListOf<Triple<String, List<String>, List<String>>>()
    private var complete: () -> Unit = {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun key(keyId: String, address: String, rev: Int, hex: String) = JSONObject()
        .put("keyId", keyId).put("keyHex", "0x" + hex.repeat(32)).put("address", address).put("rev", rev)

    private fun announce(keyId: String, address: String, rev: Int) = JSONObject()
        .put("keyId", keyId).put("keyHash", "0xh").put("address", address).put("rev", rev)
        .put("publisher", admin).put("timestamp", System.currentTimeMillis())

    private fun pending(mintedAt: Long) = key("i2.k", pendingAddress, 2, "55")
        .put("oldAddress", oldInt).put("mintedAt", mintedAt)

    private fun seed(extra: JSONObject.() -> Unit = {}) {
        saved[stream] = JSONObject()
            .put("intKey", key("i1.k", oldInt, 1, "33"))
            .put("intAnnounce", announce("i1.k", oldInt, 1))
            .put("pubKey", key("p1.k", oldPub, 1, "44"))
            .put("pubAnnounce", announce("p1.k", oldPub, 1))
            .apply(extra)
    }

    private fun manager(): EpochKeyManager {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(any()) } answers { saved[firstArg()]?.let { JSONObject(it.toString()) } }
        every { store.save(any(), any()) } answers {
            saved[firstArg()] = JSONObject(secondArg<JSONObject>().toString())
        }
        return EpochKeyManager(
            store = store,
            scope = scope,
            myAddress = { admin },
            publishKeys = { _, data -> published += data; null },
            resendKeys = { emptyList() },
            onKeyAdopted = { _, _ -> },
            readRekeyGrants = { _, interactions, _, _ -> reads += interactions; grants() },
            onRekeyUnsettled = { _, warning -> warnings += warning },
            onRekeyPending = { pendingPushes++ },
            completePublishGrants = { _, next, revoke, streamIds -> completed += Triple(next, revoke, streamIds); complete() }
        ).also { runBlocking { it.loadPersistedState(stream) } }
    }

    private fun record() = saved.getValue(stream)
    private fun intAnnounces() = published.filter { it.optString("t") == StreamConstants.PUB_ANNOUNCE && it.optString("k") == "i" }

    @Test
    fun `writes the new key down, and hands it to sync, before the grant is sent`() = runBlocking {
        seed()
        val keys = manager()
        var atGrant: JSONObject? = null
        var granted: String? = null
        var pushesAtGrant = 0

        keys.rekeyInteractionsKey(stream, keysStream) { next, _ ->
            granted = next; atGrant = JSONObject(record().toString()); pushesAtGrant = pendingPushes
        }

        assertEquals(1, pushesAtGrant)
        val pendingAtGrant = atGrant!!.getJSONObject("intKeyPending")
        assertEquals(granted, pendingAtGrant.getString("address"))
        assertEquals(2, pendingAtGrant.getInt("rev"))
        assertEquals(oldInt, pendingAtGrant.getString("oldAddress"))
        assertEquals("i1.k", atGrant!!.getJSONObject("intKey").getString("keyId"))
        assertFalse(record().has("intKeyPending"))
        assertEquals(granted, record().getJSONObject("intKey").getString("address"))
    }

    @Test
    fun `keeps the new key when the grant landed but the call failed`() = runBlocking {
        seed()
        val keys = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(true, true), listOf(false, false)) }

        val rev = keys.rekeyInteractionsKey(stream, keysStream) { _, _ -> throw IllegalStateException("receipt 503") }

        assertEquals(2, rev)
        val held = keys.interactionsKeyFor(stream)!!
        assertEquals(2, held.rev)
        assertEquals(held.keyId, intAnnounces().single().getString("keyId"))
        assertEquals(held.keyId, record().getJSONObject("intKey").getString("keyId"))
        assertFalse(record().has("intKeyPending"))
    }

    @Test
    fun `keeps the old key, and the new one pending, when the grant never landed`() = runBlocking {
        seed()
        val keys = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(false, false), listOf(true, true)) }

        val failure = runCatching {
            keys.rekeyInteractionsKey(stream, keysStream) { _, _ -> throw IllegalStateException("tx reverted") }
        }

        assertEquals("tx reverted", failure.exceptionOrNull()?.message)
        assertEquals("i1.k", keys.interactionsKeyFor(stream)?.keyId)
        assertEquals(2, record().getJSONObject("intKeyPending").getInt("rev"))
        assertTrue(published.isEmpty())
    }

    @Test
    fun `keeps the new key pending when the chain cannot be read`() = runBlocking {
        seed()
        val keys = manager()

        val failure = runCatching {
            keys.rekeyInteractionsKey(stream, keysStream) { _, _ -> throw IllegalStateException("receipt 503") }
        }

        assertEquals("receipt 503", failure.exceptionOrNull()?.message)
        assertEquals("i1.k", keys.interactionsKeyFor(stream)?.keyId)
        assertEquals(2, record().getJSONObject("intKeyPending").getInt("rev"))
    }

    @Test
    fun `revokes an unsettled earlier key too, and goes above its rev`() = runBlocking {
        seed { put("intKeyPending", pending(System.currentTimeMillis())) }
        val keys = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(false, false), listOf(true, true)) }
        var revoked: List<String>? = null

        val rev = keys.rekeyInteractionsKey(stream, keysStream) { _, revoke -> revoked = revoke }

        assertEquals(3, rev)
        assertEquals(listOf(oldInt, pendingAddress), revoked)
    }

    @Test
    fun `refuses to reset over an unsettled key while the chain cannot be read`() = runBlocking {
        seed { put("intKeyPending", pending(System.currentTimeMillis())) }
        val keys = manager()
        var granted = false

        val failure = runCatching { keys.rekeyInteractionsKey(stream, keysStream) { _, _ -> granted = true } }

        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("chain cannot be read"))
        assertFalse(granted)
        assertEquals("i2.k", record().getJSONObject("intKeyPending").getString("keyId"))
    }

    @Test
    fun `recovers the publish key the same way`() = runBlocking {
        seed()
        val keys = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(true, true), listOf(false, false)) }

        val rev = keys.rekeyPublishKey(stream, keysStream) { _, _ -> throw IllegalStateException("receipt 503") }

        assertEquals(2, rev)
        assertEquals(listOf(false), reads)
        assertEquals(2, keys.publishKeyFor(stream)?.rev)
        assertFalse(published.single().has("k"))
    }

    @Test
    fun `promotes a pending key on open when the chain holds its grant`() = runBlocking {
        seed { put("intKeyPending", pending(System.currentTimeMillis() - 5 * hour)) }
        val keys = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(true, true), listOf(false, false)) }

        keys.ensureChannelKeys(stream, keysStream, allowMint = false)

        assertEquals("i2.k", keys.interactionsKeyFor(stream)?.keyId)
        assertEquals("i2.k", intAnnounces().single().getString("keyId"))
        assertFalse(record().has("intKeyPending"))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `keeps a pending key on open while its grant may still land`() = runBlocking {
        seed { put("intKeyPending", pending(System.currentTimeMillis() - hour / 2)) }
        val keys = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(false, false), listOf(true, true)) }

        keys.ensureChannelKeys(stream, keysStream, allowMint = false)

        assertEquals("i1.k", keys.interactionsKeyFor(stream)?.keyId)
        assertEquals("i2.k", record().getJSONObject("intKeyPending").getString("keyId"))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `drops a pending key on open once its grant clearly never landed`() = runBlocking {
        seed { put("intKeyPending", pending(System.currentTimeMillis() - 2 * hour)) }
        val keys = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(false, false), listOf(true, true)) }

        keys.ensureChannelKeys(stream, keysStream, allowMint = false)

        assertEquals("i1.k", keys.interactionsKeyFor(stream)?.keyId)
        assertFalse(record().has("intKeyPending"))
        assertTrue(intAnnounces().none { it.getString("keyId") == "i2.k" })
    }

    @Test
    fun `keeps a pending key and warns when the chain cannot be read on open`() = runBlocking {
        seed { put("intKeyPending", pending(System.currentTimeMillis() - 2 * hour)) }
        val keys = manager()

        keys.ensureChannelKeys(stream, keysStream, allowMint = false)

        assertEquals("i2.k", record().getJSONObject("intKeyPending").getString("keyId"))
        assertEquals(listOf(EpochKeyManager.INT_REKEY_UNSETTLED), warnings)
    }

    @Test
    fun `keeps a pending key and warns when neither key holds the grant`() = runBlocking {
        seed { put("intKeyPending", pending(System.currentTimeMillis() - 2 * hour)) }
        val keys = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(false, false), listOf(false, false)) }

        keys.ensureChannelKeys(stream, keysStream, allowMint = false)

        assertEquals("i2.k", record().getJSONObject("intKeyPending").getString("keyId"))
        assertEquals(listOf(EpochKeyManager.INT_REKEY_UNSETTLED), warnings)
    }

    private fun pendingPub() = key("p2.k", "0x" + "99".repeat(20), 2, "99")
        .put("oldAddress", oldPub).put("mintedAt", System.currentTimeMillis())

    @Test
    fun `finishes a publish re-key whose grant landed on one stream only`() = runBlocking {
        seed { put("pubKeyPending", pendingPub()) }
        val keys = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(true, false), listOf(false, true)) }

        keys.ensureChannelKeys(stream, keysStream, allowMint = false)

        assertEquals(listOf(Triple("0x" + "99".repeat(20), listOf(oldPub), listOf("$admin/sealed-2"))), completed)
        assertEquals("p2.k", keys.publishKeyFor(stream)?.keyId)
        assertFalse(record().has("pubKeyPending"))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `keeps a half-landed publish re-key pending, and warns, when the missing grant fails again`() = runBlocking {
        seed { put("pubKeyPending", pendingPub()) }
        val keys = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(true, false), listOf(false, true)) }
        complete = { throw IllegalStateException("rpc down") }

        keys.ensureChannelKeys(stream, keysStream, allowMint = false)

        assertEquals("p1.k", keys.publishKeyFor(stream)?.keyId)
        assertEquals("p2.k", record().getJSONObject("pubKeyPending").getString("keyId"))
        assertEquals(listOf(EpochKeyManager.PUB_REKEY_UNSETTLED), warnings)
    }

    @Test
    fun `adopts a pending key without the chain when another device already announced it`() = runBlocking {
        seed {
            put("intKeyPending", pending(System.currentTimeMillis()))
            put("intAnnounce", announce("i2.k", pendingAddress, 2))
        }
        val keys = manager()

        val outcome = keys.reconcileRekey(stream, keysStream, interactions = true)

        assertEquals(EpochKeyManager.RekeyOutcome.PROMOTED, outcome)
        assertTrue(reads.isEmpty())
        assertEquals("i2.k", keys.interactionsKeyFor(stream)?.keyId)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `drops a pending key without the chain when a later re-key superseded it`() = runBlocking {
        seed {
            put("intKeyPending", pending(System.currentTimeMillis()))
            put("intAnnounce", announce("i3.x", "0x" + "66".repeat(20), 3))
        }
        val keys = manager()

        val outcome = keys.reconcileRekey(stream, keysStream, interactions = true)

        assertEquals(EpochKeyManager.RekeyOutcome.DROPPED, outcome)
        assertTrue(reads.isEmpty())
        assertFalse(record().has("intKeyPending"))
    }

    @Test
    fun `the bridge reads PUBLISH of both keys on every stream it is given`() {
        val asset = java.io.File("src/main/assets/pombo_bridge.html").readText()
        val start = asset.indexOf("async rekeyGrantsState(")
        assertTrue("rekeyGrantsState is gone from the bridge", start >= 0)
        val body = asset.substring(start, asset.indexOf("\n    },", start))
        assertTrue(body.contains("permission: 'publish'"))
        assertTrue(body.contains("for (const streamId of a.streamIds)"))
        assertFalse("the read must not write", body.contains("setPermissions"))
    }

    @Test
    fun `a pending key survives a restart`() = runBlocking {
        seed()
        manager().let { keys ->
            runCatching { keys.rekeyInteractionsKey(stream, keysStream) { _, _ -> throw IllegalStateException("killed") } }
        }
        val restarted = manager()
        grants = { EpochKeyManager.RekeyGrants(twoStreams, listOf(true, true), listOf(false, false)) }

        restarted.ensureChannelKeys(stream, keysStream, allowMint = false)

        assertEquals(2, restarted.interactionsKeyFor(stream)?.rev)
        assertNull(record().optJSONObject("intKeyPending"))
    }
}
