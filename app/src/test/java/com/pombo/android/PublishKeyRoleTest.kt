package com.pombo.android

import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In a read-only Sealed channel the shared publish key IS the right to post,
 * so a responder hands it only to the owner and the moderators, and to nobody
 * when it cannot read the gate.
 */
class PublishKeyRoleTest {

    private val channel = Channel(
        messageStreamId = STREAM,
        ephemeralStreamId = StreamConstants.deriveEphemeralId(STREAM),
        adminStreamId = StreamConstants.deriveAdminId(STREAM),
        keysStreamId = StreamConstants.deriveKeysId(STREAM),
        name = "Sealed",
        type = "gated",
        gateAddress = GATE,
        wireIdentity = "sealed",
        readOnly = true
    )

    private val h = ChannelManagerHarness(channels = listOf(channel))
    private val manager = h.manager

    @After fun tearDown() = h.stop()

    private fun gateSays(readOnly: Boolean, moderator: Boolean = false) {
        coEvery { h.bridge.call("gateInfo", any()) } returns
            JSONObject().put("readOnly", readOnly).put("owner", OWNER)
        coEvery { h.bridge.call("gateMembers", any(), any()) } returns JSONObject()
            .put("members", JSONArray().put(JSONObject()
                .put("address", REQUESTER).put("isOwner", false).put("moderator", moderator)))
    }

    @Test fun `the owner may hold it`() = runBlocking {
        gateSays(readOnly = true)
        assertTrue(manager.mayHoldPublishKey(STREAM, OWNER.uppercase().replace("0X", "0x")))
    }

    @Test fun `a moderator may hold it`() = runBlocking {
        gateSays(readOnly = true, moderator = true)
        assertTrue(manager.mayHoldPublishKey(STREAM, REQUESTER))
    }

    @Test fun `a plain member may not`() = runBlocking {
        gateSays(readOnly = true)
        assertFalse(manager.mayHoldPublishKey(STREAM, REQUESTER))
    }

    @Test fun `nobody may when the gate cannot be read`() = runBlocking {
        coEvery { h.bridge.call("gateInfo", any()) } throws RuntimeException("rpc down")
        assertFalse(manager.mayHoldPublishKey(STREAM, OWNER))
    }

    @Test fun `every member may when the channel is not read-only`() = runBlocking {
        gateSays(readOnly = false)
        assertTrue(manager.mayHoldPublishKey(STREAM, REQUESTER))
    }

    private companion object {
        const val STREAM = "0x7ea24eb97d400a76f8d96be92c4e7fce576aedb9/sealed-1"
        const val OWNER = "0x7ea24eb97d400a76f8d96be92c4e7fce576aedb9"
        const val REQUESTER = "0x1111111111111111111111111111111111111111"
        const val GATE = "0x38e731792e40a00a68cc9a912e0caf8e7fe2ca8d"
    }
}
