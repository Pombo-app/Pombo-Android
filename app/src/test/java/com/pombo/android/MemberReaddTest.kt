package com.pombo.android

import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The members list on this device goes stale when a moderator or another
 * device takes someone off the allowlist. Adding them back asks the gate
 * before refusing, so the owner is never locked out of re-adding them.
 */
class MemberReaddTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val gate = "0x" + "cd".repeat(20)
    private val member = "0x" + "ab".repeat(20)
    private val h = ChannelManagerHarness(channels = listOf(
        ChannelManagerHarness.channel("$me/gated-1", type = "gated")
            .copy(gateAddress = gate, members = listOf(member))
    ))
    private val manager = h.manager

    @Before fun setUp() {
        manager.openChannel(manager.channels.value.single().messageStreamId)
    }

    @After fun tearDown() = h.stop()

    private fun gateSays(allowed: Boolean) {
        coEvery { h.bridge.call("gateMembers", any(), any()) } returns JSONObject().put("members",
            JSONArray().put(JSONObject().put("address", member).put("allowed", allowed)))
    }

    @Test fun `someone the gate no longer allows is added back though this device still lists them`() {
        gateSays(allowed = false)

        runBlocking { manager.addMember(member) }

        coVerify { h.bridge.call("gateAllow", match { it.optString("user") == member }, any()) }
        assertEquals(1, manager.channels.value.single().members.count { it.equals(member, ignoreCase = true) })
    }

    @Test fun `someone the gate still allows is refused with no transaction`() {
        gateSays(allowed = true)

        val error = runCatching { runBlocking { manager.addMember(member) } }.exceptionOrNull()

        assertEquals("Address is already a member", error?.message)
        coVerify(exactly = 0) { h.bridge.call("gateAllow", any(), any()) }
    }
}
