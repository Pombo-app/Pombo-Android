package com.pombo.android

import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A published ADMIN_STATE is read back from storage until it is there;
 * missing, it is republished under the next rev a bounded number of times,
 * and a newer snapshot from another device of the owner is adopted instead
 * of being published over.
 */
class AdminStateConfirmTest {

    /** The harness key, so the channel under test is the account's own. */
    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val streamId = "$me/room-1"
    private val room = ChannelManagerHarness.channel(streamId)
    private val h = ChannelManagerHarness(channels = listOf(room))
    private val manager = h.manager
    private val warnings = mutableListOf<String>()

    /** What the storage node serves as the newest -3 entry; each test sets it. */
    private var stored: () -> JSONObject? = { null }

    /** The providers the admin stream resolves to; a channel without storage has none. */
    private var providers: JSONArray = JSONArray().put(
        JSONObject().put("nodeAddress", "0x1").put("urls", JSONArray().put("https://node.example")))

    @Before fun setUp() {
        manager.adminConfirmSleep = { }
        manager.onModerationWarning = { warnings += it }
        every { manager.adminFloorStore.get(any()) } returns null
        coEvery { h.bridge.call("resolveStorageEndpoints", any()) } answers { JSONObject().put("nodes", providers) }
        coEvery { h.bridge.call("resend", any(), any()) } answers {
            val messages = JSONArray()
            // Only the admin stream serves the snapshot; history reads see an empty page.
            if (secondArg<JSONObject>().optString("streamId") == room.adminStreamId) {
                stored()?.let { row ->
                    messages.put(JSONObject().put("content", row)
                        .put("meta", JSONObject().put("publisherId", me).put("timestamp", row.optLong("ts"))))
                }
            }
            JSONObject().put("messages", messages)
        }
        manager.openChannel(streamId)
    }

    @After fun tearDown() = h.stop()

    private fun pending(): JSONObject? = manager.pendingAdminConfirmation(room)
    private fun ban() = runBlocking { manager.banMember("0xBAD") }
    /** The -3 publishes alone: the admin_invalidate signal embeds the same snapshot. */
    private fun adminStatePublishes() =
        h.published.count { it.contains("\"type\":\"ADMIN_STATE\"") && !it.contains("admin_invalidate") }

    private fun onStorage(rev: Int, ts: Long, banned: List<String> = emptyList()) = JSONObject()
        .put("type", "ADMIN_STATE").put("rev", rev).put("ts", ts).put("createdBy", me)
        .put("state", JSONObject()
            .put("bannedMembers", JSONArray(banned))
            .put("hiddenMessageIds", JSONArray())
            .put("pins", JSONArray()))

    @Test
    fun `a publish the node serves back is confirmed and forgotten`() {
        stored = { pending()?.let { onStorage(it.optInt("rev"), it.optLong("ts")) } }
        ban()
        assertNull(pending())
        assertEquals(1, adminStatePublishes())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a publish the node never serves is republished up to the limit, then the owner is told`() {
        stored = { null }
        ban()
        val entry = pending()!!
        assertTrue(entry.optBoolean("stalled"))
        assertEquals(3, entry.optInt("republished"))
        assertEquals(4, adminStatePublishes())
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("not yet confirmed"))
    }

    @Test
    fun `an older snapshot on storage means not landed yet`() {
        // Storage still holds the previous rev at every read: republish once, then it lands.
        stored = {
            val p = pending()
            when {
                p == null -> null
                p.optInt("republished") == 0 -> onStorage(p.optInt("rev") - 1, p.optLong("ts") - 1000)
                else -> onStorage(p.optInt("rev"), p.optLong("ts"))
            }
        }
        ban()
        assertNull(pending())
        assertEquals(2, adminStatePublishes())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a newer snapshot from another device is adopted, not republished over`() {
        stored = { if (pending() != null) onStorage(50, 5_000_000_000L, banned = listOf("0xother")) else null }
        ban()
        assertNull(pending())
        assertEquals(1, adminStatePublishes())
        assertEquals(setOf("0xother"), manager.bannedMembers.value)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("another device"))
    }

    @Test
    fun `an admin stream without storage has nothing to confirm`() {
        providers = JSONArray()
        stored = { null }
        ban()
        assertNull(pending())
        assertEquals(1, adminStatePublishes())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `opening the channel again picks a stalled publish up and confirms it`() {
        stored = { null }
        ban()
        assertTrue(pending()!!.optBoolean("stalled"))

        stored = { pending()?.let { onStorage(it.optInt("rev"), it.optLong("ts")) } }
        manager.openChannel(streamId)
        assertNull(pending())
        assertEquals(4, adminStatePublishes())
    }
}
