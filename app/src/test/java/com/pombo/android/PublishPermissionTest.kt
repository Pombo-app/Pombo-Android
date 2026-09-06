package com.pombo.android

import com.pombo.android.data.Channel
import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The composer asks the registry, not the stored flag: a channel entered
 * before its permissions changed keeps `readOnly = false` forever, and the
 * network refuses what that flag allows.
 */
class PublishPermissionTest {

    private val h = ChannelManagerHarness()
    private val manager = h.manager

    @After fun tearDown() = h.stop()

    private fun channel(type: String) = Channel(
        messageStreamId = "0xowner/announcements-1",
        ephemeralStreamId = "0xowner/announcements-2",
        adminStreamId = "0xowner/announcements-3",
        name = "StreamOperator",
        type = type,
        readOnly = false
    )

    private fun registrySays(canPublish: Boolean) {
        coEvery { h.bridge.call("checkPermissions", any(), any()) } returns
            JSONObject().put("canPublish", canPublish).put("canSubscribe", true)
    }

    @Test
    fun `a refused publisher may not write, whatever the record says`() = runBlocking {
        registrySays(canPublish = false)
        assertFalse(manager.mayPublishHere(channel("public")))
    }

    @Test
    fun `a granted publisher may write`() = runBlocking {
        registrySays(canPublish = true)
        assertTrue(manager.mayPublishHere(channel("public")))
    }

    @Test
    fun `gated channels are never asked, their grants belong to the clone`() = runBlocking {
        registrySays(canPublish = false)
        assertTrue(manager.mayPublishHere(channel("gated")))
    }

    @Test
    fun `an unread answer keeps the composer`() = runBlocking {
        coEvery { h.bridge.call("checkPermissions", any(), any()) } throws RuntimeException("rpc down")
        assertTrue(manager.mayPublishHere(channel("public")))
    }
}
