package com.pombo.android.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The web writes a null mode when it does not know one yet, and older Android
 * builds turned that null into the text "null". Neither is Visible: read as
 * Visible, a Sealed channel stops handing out its shared keys.
 */
class ChannelWireIdentityJsonTest {

    private fun gated(mode: Any?): JSONObject = JSONObject()
        .put("messageStreamId", "0xowner/abc-1")
        .put("type", "gated")
        .put("gate", JSONObject().put("address", "0x7a3ee479b790578fb9ce885aa3356f79c4df0305"))
        .apply { if (mode != Unset) put("wireIdentity", mode) }

    @Test fun `an explicit null mode stays unknown`() {
        assertNull(Channel.fromJson(gated(JSONObject.NULL)).wireIdentity)
    }

    @Test fun `the text null is not a mode`() {
        assertNull(Channel.fromJson(gated("null")).wireIdentity)
    }

    @Test fun `a gated record from before the mode existed is Visible`() {
        assertEquals("visible", Channel.fromJson(gated(Unset)).wireIdentity)
    }

    @Test fun `a Sealed record survives the round trip`() {
        val channel = Channel.fromJson(gated("sealed"))
        assertEquals("sealed", Channel.fromJson(channel.toJson()).wireIdentity)
    }

    @Test fun `an unknown mode is written as null and read back as unknown`() {
        val channel = Channel.fromJson(gated(JSONObject.NULL))
        assertNull(Channel.fromJson(channel.toJson()).wireIdentity)
    }

    private object Unset
}
