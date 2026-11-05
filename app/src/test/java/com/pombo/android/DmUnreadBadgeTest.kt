package com.pombo.android

import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A DM that arrives while its conversation is not on screen raises the
 * conversation's unread badge (web noteMessage), once, and only for content.
 */
class DmUnreadBadgeTest {

    private val peer = "0x7ea24eb97d400a76f8d96be92c4e7fce576aedb9"
    private val conversation = "$peer/Pombo-DM-1"
    private val h = ChannelManagerHarness(
        channels = listOf(ChannelManagerHarness.channel(conversation, type = "dm").copy(peerAddress = peer))
    )
    private val manager = h.manager
    private val inbox = "${h.me}/Pombo-DM-1"
    private val watermarks = mutableMapOf<String, Long>()

    private fun deliver(content: JSONObject, ts: Long) = manager.onIncoming(
        inbox, 0, content.toString(),
        JSONObject().put("publisherId", peer).put("timestamp", ts).toString()
    )

    private fun text(id: String, ts: Long) = JSONObject()
        .put("type", "text").put("id", id).put("text", id).put("timestamp", ts)

    @Before fun setUp() {
        every { h.unreadStore.watermark(any()) } answers { watermarks[firstArg()] ?: 0L }
        every { h.unreadStore.setWatermark(any(), any()) } answers {
            watermarks[firstArg()] = maxOf(watermarks[firstArg()] ?: 0L, secondArg())
        }
        manager.subscribeMyInbox()
    }

    @After fun tearDown() = h.stop()

    @Test
    fun `a DM off screen raises the badge and moves the watermark`() {
        val ts = System.currentTimeMillis() - 1_000
        deliver(text("a", ts), ts)

        verify(exactly = 1) { h.unreadStore.increment(conversation) }
        verify { h.unreadStore.setWatermark(conversation, ts) }
    }

    @Test
    fun `a row already counted is not counted again when the replay routes it`() {
        val ts = System.currentTimeMillis() - 1_000
        deliver(text("a", ts), ts)
        deliver(text("a", ts), ts)

        verify(exactly = 1) { h.unreadStore.increment(conversation) }
    }

    @Test
    fun `a DM older than what was read does not count`() {
        val ts = System.currentTimeMillis() - 60_000
        watermarks[conversation] = ts + 1

        deliver(text("old", ts), ts)

        verify(exactly = 0) { h.unreadStore.increment(any()) }
    }

    @Test
    fun `a reaction does not count`() {
        val ts = System.currentTimeMillis() - 1_000
        deliver(
            JSONObject().put("type", "reaction").put("messageId", "x").put("emoji", "👍")
                .put("action", "add").put("timestamp", ts),
            ts
        )

        verify(exactly = 0) { h.unreadStore.increment(any()) }
    }

    @Test
    fun `a DM in the conversation on screen does not count`() = runBlocking {
        manager.openChannel(conversation)
        delay(300)
        val ts = System.currentTimeMillis() - 1_000

        deliver(text("seen", ts), ts)

        verify(exactly = 0) { h.unreadStore.increment(any()) }
    }
}
