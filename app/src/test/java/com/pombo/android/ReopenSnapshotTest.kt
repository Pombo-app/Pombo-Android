package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
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
 * A channel reopened in the same session starts from the timeline it was left
 * with, while its storage reads run again behind it.
 */
class ReopenSnapshotTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private fun room(i: Int) = "$me/room$i-1"
    private val h = ChannelManagerHarness(channels = (0..11).map { ChannelManagerHarness.channel(room(it)) })
    private val manager = h.manager

    /** When set, the admin-state read of room 0 waits on it. */
    @Volatile private var adminRead: CompletableDeferred<Unit>? = null

    @Before fun setUp() {
        coEvery { h.bridge.call(eq("resend"), any(), any()) } coAnswers {
            if (secondArg<JSONObject>().optString("streamId") == StreamConstants.deriveAdminId(room(0))) {
                adminRead?.await()
            }
            JSONObject().put("messages", JSONArray())
        }
    }

    @After fun tearDown() = h.stop()

    private var clock = 1_000L
    private fun text(id: String) = UiMessage(
        id = id, text = id, sender = me, senderName = null, timestamp = clock++, mine = true
    )

    private fun ids() = manager.messages.value.map { it.id }

    @Test
    fun `a reopened channel starts from the timeline it was left with`() {
        manager.openChannel(room(0))
        manager.mergeMessages(listOf(text("m1"), text("m2")))

        manager.openChannel(room(1))
        assertTrue(manager.messages.value.isEmpty())

        manager.openChannel(room(0))
        assertEquals(listOf("m1", "m2"), ids())
    }

    @Test
    fun `the restored timeline is released before the admin state has been read`() {
        manager.openChannel(room(0))
        manager.mergeMessages(listOf(text("m1")))
        manager.openChannel(room(1))

        adminRead = CompletableDeferred()
        manager.openChannel(room(0))

        assertTrue(manager.initialLoad.value)
        assertEquals(setOf("m1"), manager.restoredTimeline.value?.ids)
        adminRead!!.complete(Unit)
    }

    @Test
    fun `the loading gate is already up while a reopen is still subscribing`() {
        manager.openChannel(room(0))
        manager.mergeMessages(listOf(text("m1")))
        manager.openChannel(room(1))

        val subscribed = CompletableDeferred<Unit>()
        coEvery { h.bridge.call("subscribe", any()) } coAnswers { subscribed.await(); JSONObject() }
        manager.openChannel(room(0))

        assertEquals(listOf("m1"), ids())
        assertTrue(manager.initialLoad.value)
        assertEquals(setOf("m1"), manager.restoredTimeline.value?.ids)
        subscribed.complete(Unit)
    }

    @Test
    fun `a channel opened with nothing kept releases no restored timeline`() {
        manager.openChannel(room(0))

        assertNull(manager.restoredTimeline.value)
    }

    @Test
    fun `a reopened channel keeps the moderation it was left with until the admin state is read`() {
        manager.openChannel(room(0))
        manager.mergeMessages(listOf(text("m1"), text("m2")))
        runBlocking { manager.hideMessage("m2", true) }
        manager.openChannel(room(1))
        assertTrue(manager.hiddenIds.value.isEmpty())

        adminRead = CompletableDeferred()
        manager.openChannel(room(0))

        assertEquals(setOf("m2"), manager.restoredTimeline.value?.hiddenIds)
        assertEquals(setOf("m2"), manager.hiddenIds.value)
        adminRead!!.complete(Unit)
    }

    @Test
    fun `an owner's hide before the admin state is read again keeps the channel's other hides`() {
        manager.openChannel(room(0))
        manager.mergeMessages(listOf(text("first-hide"), text("second-hide")))
        runBlocking { manager.hideMessage("first-hide", true) }
        manager.openChannel(room(1))

        adminRead = CompletableDeferred()
        manager.openChannel(room(0))
        runBlocking { manager.hideMessage("second-hide", true) }

        val published = h.published.last { it.contains("\"type\":\"ADMIN_STATE\"") && !it.contains("admin_invalidate") }
        assertTrue(published.contains("first-hide"))
        assertTrue(published.contains("second-hide"))
        adminRead!!.complete(Unit)
    }

    @Test
    fun `a failed send is still there, still failed, after leaving and coming back`() {
        manager.openChannel(room(0))
        coEvery { h.bridge.call("publishAsChannel", any()) } throws IllegalStateException("No epoch key")
        runCatching { runBlocking { manager.sendMessage("hello") } }

        manager.openChannel(room(1))
        manager.openChannel(room(0))

        val bubble = manager.messages.value.single()
        assertEquals("hello", bubble.text)
        assertTrue(bubble.failed)
        assertEquals("No epoch key", bubble.failError)
    }

    @Test
    fun `a send that settles after its channel was left lands on the kept copy`() {
        manager.openChannel(room(0))
        manager.mergeMessages(listOf(text("m1").copy(pending = true)))
        manager.openChannel(room(1))

        manager.markFailed("m1", "No epoch key")
        manager.openChannel(room(0))

        assertTrue(manager.messages.value.single().failed)
    }

    @Test
    fun `image bytes are not kept`() {
        manager.openChannel(room(0))
        manager.mergeMessages(listOf(
            text("img").copy(isImage = true, imageId = "i1", imageBytes = byteArrayOf(1, 2, 3))
        ))

        manager.openChannel(room(1))
        manager.openChannel(room(0))

        val bubble = manager.messages.value.single()
        assertEquals("i1", bubble.imageId)
        assertNull(bubble.imageBytes)
    }

    @Test
    fun `only the ten most recently left channels are kept`() {
        for (i in 0..10) {
            manager.openChannel(room(i))
            manager.mergeMessages(listOf(text("m$i")))
        }
        manager.openChannel(room(11))

        manager.openChannel(room(0))
        assertTrue(manager.messages.value.isEmpty())

        manager.openChannel(room(1))
        assertEquals(listOf("m1"), ids())
    }

    @Test
    fun `leaving a channel forgets its copy`() {
        manager.openChannel(room(0))
        manager.mergeMessages(listOf(text("m1")))
        val channel = manager.channels.value.first { it.messageStreamId == room(0) }

        manager.removeChannel(room(0))
        manager._channels.value = manager._channels.value + channel
        manager.openChannel(room(0))

        assertTrue(manager.messages.value.isEmpty())
    }

    @Test
    fun `an account switch forgets every kept timeline`() {
        manager.openChannel(room(0))
        manager.mergeMessages(listOf(text("m1")))

        manager.reloadChannels()
        manager.openChannel(room(0))

        assertTrue(manager.messages.value.isEmpty())
    }

    @Test
    fun `a timeline kept for one account is never shown to another`() {
        manager.openChannel(room(0))
        manager.mergeMessages(listOf(text("m1")))

        h.address = "0x" + "cd".repeat(20)
        manager.openChannel(room(0))
        assertTrue(manager.messages.value.isEmpty())

        h.address = me
        manager.openChannel(room(1))
        manager.openChannel(room(0))
        assertEquals(listOf("m1"), ids())
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }
}
