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
 * An epoch rotates because someone lost access. The channel layer is told
 * when a later epoch reaches this device, so the responders forget the grants
 * they cached before it and ask the chain again before wrapping the new key.
 */
class AccessRecheckTest {

    private val priv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val admin = EthereumSigner.address(priv).lowercase()
    private val member = "0x" + "99".repeat(20)
    private val stream = "$admin/chan-1"
    private val keysStream = "$admin/chan-4"

    private val advanced = mutableListOf<String>()

    private fun manager(address: String): EpochKeyManager {
        val store = mockk<EpochKeyStore>(relaxed = true)
        every { store.load(stream) } returns JSONObject()
            .put("epochs", JSONObject().put("3.c", JSONObject()
                .put("keyHex", "0x" + "33".repeat(32)).put("keyHash", "0x" + "22".repeat(32)).put("epoch", 3)))
            .put("announces", JSONObject().put("3", JSONObject()
                .put("keyId", "3.c").put("keyHash", "0x" + "22".repeat(32))
                .put("publisher", admin).put("timestamp", 300L).put("validFrom", 300L)))
            .put("currentEpoch", 3)
        return EpochKeyManager(
            store = store,
            scope = CoroutineScope(Dispatchers.Unconfined),
            myAddress = { address },
            publishKeys = { _, _ -> null },
            resendKeys = { emptyList() },
            onKeyAdopted = { _, _ -> },
            myPrivateKey = { priv }
        ).apply { onEpochAdvanced = { advanced += it } }
    }

    private fun announce(epoch: Int) = JSONObject()
        .put("t", StreamConstants.KEY_ANNOUNCE).put("epoch", epoch).put("keyId", "$epoch.k")
        .put("keyHash", "0x" + "ab".repeat(32)).put("validFrom", epoch * 100L)

    @Test
    fun `the announce of a later epoch is reported`() = runBlocking {
        manager(member).handleKeysMessage(stream, keysStream, announce(4), admin, 400L)

        assertEquals(listOf(stream), advanced)
    }

    @Test
    fun `an earlier epoch, or another copy of the current one, is not`() = runBlocking {
        val keys = manager(member)
        keys.handleKeysMessage(stream, keysStream, announce(2), admin, 200L)
        keys.handleKeysMessage(stream, keysStream, announce(3), admin, 250L)

        assertEquals(emptyList<String>(), advanced)
    }

    @Test
    fun `the admin's own rotation is reported once`() = runBlocking {
        manager(admin).rotateEpoch(stream, keysStream)

        assertEquals(listOf(stream), advanced)
    }
}
