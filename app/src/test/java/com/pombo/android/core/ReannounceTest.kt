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
 * An admin whose announce storage no longer returns republishes it, and the
 * copy has to say when the epoch began: readers that only ever see the copy
 * judge the epoch's history by its validFrom.
 */
class ReannounceTest {

    private val priv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val me = EthereumSigner.address(priv).lowercase()
    private val stream = "$me/chan-1"
    private val keysStream = "$me/chan-4"

    private val published = mutableListOf<JSONObject>()

    private fun persisted(epoch: Int, validFrom: Long): JSONObject {
        val keyId = "$epoch.aaaaaaaaaaaa"
        return JSONObject()
            .put("epochs", JSONObject().put(keyId, JSONObject()
                .put("keyHex", "0x" + "11".repeat(32)).put("keyHash", "0x" + "22".repeat(32)).put("epoch", epoch)))
            .put("announces", JSONObject().put(epoch.toString(), JSONObject()
                .put("keyId", keyId).put("keyHash", "0x" + "22".repeat(32))
                .put("publisher", me).put("timestamp", validFrom).put("validFrom", validFrom)))
            .put("currentEpoch", epoch)
    }

    @Test
    fun `an announce storage lost is republished with the epoch's own validFrom`() = runBlocking {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns persisted(3, 1_000L)
        val keys = EpochKeyManager(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            myAddress = { me },
            publishKeys = { _, data -> published += data },
            resendKeys = { emptyList() },
            onKeyAdopted = { _, _ -> },
            myPrivateKey = { priv }
        )

        keys.ensureChannelKeys(stream, keysStream)

        val announce = published.first { it.optString("t") == StreamConstants.KEY_ANNOUNCE }
        assertEquals(3, announce.optInt("epoch"))
        assertEquals(1_000L, announce.optLong("validFrom"))
    }
}
