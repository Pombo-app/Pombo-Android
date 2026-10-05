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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A held shared key whose rev no announce carries yet must be announced on the
 * next open, however fresh the announce of the older rev is.
 */
class UnannouncedSharedKeyTest {

    private val adminPriv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val admin = EthereumSigner.address(adminPriv).lowercase()
    private val stream = "$admin/sealed-1"
    private val keysStream = "$admin/sealed-4"

    private val published = mutableListOf<JSONObject>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun tearDown() = scope.cancel()

    private fun key(keyId: String, byte: String, rev: Int) = JSONObject()
        .put("keyId", keyId).put("keyHex", "0x" + byte.repeat(32))
        .put("address", "0x" + byte.repeat(20)).put("rev", rev)

    /** The rev-1 announces as storage returns them: their timestamps make them fresh. */
    private fun storedRev1Announces() = listOf(
        EpochKeyManager.Entry(JSONObject().put("t", StreamConstants.PUB_ANNOUNCE).put("k", "i")
            .put("keyId", "i1.k").put("keyHash", "0xh").put("addr", "0x" + "33".repeat(20)).put("rev", 1),
            admin, System.currentTimeMillis()),
        EpochKeyManager.Entry(JSONObject().put("t", StreamConstants.PUB_ANNOUNCE)
            .put("keyId", "p1.k").put("keyHash", "0xh").put("addr", "0x" + "44".repeat(20)).put("rev", 1),
            admin, System.currentTimeMillis()))

    private fun open(intKey: JSONObject, pubKey: JSONObject) = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns JSONObject().put("intKey", intKey).put("pubKey", pubKey)
        val keys = EpochKeyManager(
            store = store,
            scope = scope,
            myAddress = { admin },
            publishKeys = { _, data -> published += data; null },
            resendKeys = { storedRev1Announces() },
            onKeyAdopted = { _, _ -> }
        )
        keys.ensureChannelKeys(stream, keysStream, allowMint = false)
    }

    private fun sharedAnnounces() = published.filter { it.optString("t") == StreamConstants.PUB_ANNOUNCE }

    @Test
    fun `announces shared keys above the announced rev`() {
        open(intKey = key("i2.k", "55", 2), pubKey = key("p2.k", "88", 2))

        assertEquals(listOf("p2.k", "i2.k"), sharedAnnounces().map { it.getString("keyId") })
    }

    @Test
    fun `stays quiet while the held keys are the ones freshly announced`() {
        open(intKey = key("i1.k", "33", 1), pubKey = key("p1.k", "44", 1))

        assertTrue(sharedAnnounces().isEmpty())
    }
}
