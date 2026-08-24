package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Link shape parity with the web's channels.js generateInviteLink: a password
 * is the only thing a link has to carry, so it is the only case that still
 * gets the encrypted token.
 *
 * `generate`/`parse` need android.util.Base64, so the encrypted half is
 * covered by the instrumented path — these tests cover the routing decision
 * and the `#/channel/` parser, which are pure Kotlin.
 */
class InviteLinkFormatTest {

    private val streamId = "0xae340e799e8151f6a4999d245e466197aa217667/9862eb7bd898f338-1"

    @Test
    fun passwordless_channel_links_by_stream_id() {
        assertEquals(
            "https://app.pombo.cc/#/channel/$streamId",
            InviteToken.link(streamId, "Alpha", "public", null)
        )
    }

    @Test
    fun empty_password_counts_as_none() {
        assertEquals(
            "https://app.pombo.cc/#/channel/$streamId",
            InviteToken.link(streamId, "Alpha", "public", "")
        )
    }

    @Test
    fun gated_channel_links_by_stream_id_the_gate_is_on_chain() {
        val link = InviteToken.link(streamId, "Token Gated", "gated", null, "0xgate")
        assertEquals("https://app.pombo.cc/#/channel/$streamId", link)
    }

    @Test
    fun channelIdFrom_reads_a_stream_id_containing_slashes() {
        assertEquals(
            streamId,
            InviteToken.channelIdFrom("https://app.pombo.cc/#/channel/$streamId")
        )
    }

    @Test
    fun channelIdFrom_drops_a_query_string() {
        assertEquals(
            streamId,
            InviteToken.channelIdFrom("https://app.pombo.cc/#/channel/$streamId?utm=x")
        )
    }

    @Test
    fun channelIdFrom_rejects_an_invite_link() {
        assertNull(InviteToken.channelIdFrom("https://app.pombo.cc/#/invite/aa.bb.cc"))
    }

    @Test
    fun channelIdFrom_rejects_a_marker_with_no_id() {
        assertNull(InviteToken.channelIdFrom("https://app.pombo.cc/#/channel/"))
    }

    @Test
    fun channelIdFrom_rejects_an_unrelated_link() {
        assertNull(InviteToken.channelIdFrom("https://app.pombo.cc/#/explore"))
    }
}
