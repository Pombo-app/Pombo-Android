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
 * A storage provider added to a channel holds nothing published before it was
 * assigned. The admin publishes the -4 anchors again: every epoch's announce
 * with the validFrom it was made with, the shared keys' announces, and a self
 * wrap per held key.
 */
class AnchorRepublishTest {

    private val priv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val me = EthereumSigner.address(priv).lowercase()
    private val stream = "$me/chan-1"
    private val keysStream = "$me/chan-4"

    private val published = mutableListOf<JSONObject>()

    private fun manager(address: String = me): EpochKeyManager {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns JSONObject()
            .put("epochs", JSONObject().put("3.c", JSONObject()
                .put("keyHex", "0x" + "33".repeat(32)).put("keyHash", "0xh3").put("epoch", 3)))
            .put("announces", JSONObject()
                .put("3", JSONObject().put("keyId", "3.c").put("keyHash", "0xh3")
                    .put("publisher", me).put("timestamp", 300L).put("validFrom", 300L))
                .put("1", JSONObject().put("keyId", "1.a").put("keyHash", "0xh1")
                    .put("publisher", me).put("timestamp", 100L).put("validFrom", 100L)))
            .put("currentEpoch", 3)
            .put("pubAnnounce", JSONObject().put("keyId", "p2.x").put("keyHash", "0xpk")
                .put("address", "0x" + "0c".repeat(20)).put("rev", 2).put("publisher", me).put("timestamp", 50L))
        return EpochKeyManager(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            myAddress = { address },
            publishKeys = { _, data ->
                published += data
                JSONObject().put("ok", true).put("timestamp", 5000L + published.size)
            },
            resendKeys = { emptyList() },
            onKeyAdopted = { _, _ -> },
            myPrivateKey = { priv }
        )
    }

    @Test
    fun `every epoch's announce goes out in order with its own validFrom`() = runBlocking {
        manager().republishAnchors(stream, keysStream)

        val announces = published.filter { it.optString("t") == StreamConstants.KEY_ANNOUNCE }
        assertEquals(listOf(1, 3), announces.map { it.optInt("epoch") })
        assertEquals(listOf(100L, 300L), announces.map { it.optLong("validFrom") })
    }

    @Test
    fun `the publish key announce goes out as it was announced`() = runBlocking {
        manager().republishAnchors(stream, keysStream)

        val pub = published.single { it.optString("t") == StreamConstants.PUB_ANNOUNCE }
        assertEquals("p2.x", pub.optString("keyId"))
        assertEquals("0x" + "0c".repeat(20), pub.optString("addr"))
        assertEquals(2, pub.optInt("rev"))
    }

    @Test
    fun `every held key is sealed to the account again`() = runBlocking {
        manager().republishAnchors(stream, keysStream)

        val wrap = published.single { it.optString("t") == StreamConstants.KEY_WRAP }
        assertEquals("self", wrap.optString("requestId"))
        assertEquals("3.c", wrap.optString("keyId"))
    }

    @Test
    fun `returns where each announce landed`() = runBlocking {
        assertEquals(listOf(5001L, 5002L, 5003L), manager().republishAnchors(stream, keysStream))
    }

    @Test
    fun `names the announces a member needs for the keys in use now`() = runBlocking {
        assertEquals(listOf("3.c", "p2.x"), manager().currentAnchorKeyIds(stream))
    }

    @Test
    fun `a member publishes nothing`() = runBlocking {
        assertEquals(emptyList<Long>(), manager(address = "0x" + "99".repeat(20)).republishAnchors(stream, keysStream))
        assertEquals(emptyList<JSONObject>(), published)
    }
}
