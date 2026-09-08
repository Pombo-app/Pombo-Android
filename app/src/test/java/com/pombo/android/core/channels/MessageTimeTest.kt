package com.pombo.android.core.channels

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D-X01(c): the UI orders, pages and dates by the signed envelope time
 * (`_timestamp`), never the publisher-chosen payload `timestamp`. A past-dated
 * payload must not be able to drag the timeline back or fake a message's date.
 */
class MessageTimeTest {

    @Test
    fun `the envelope time wins over a past-dated payload`() {
        val m = JSONObject().put("_timestamp", 1_700_000_000_000L).put("timestamp", 978_307_200_000L)
        assertEquals(1_700_000_000_000L, m.messageTime())
    }

    @Test
    fun `falls back to the payload when there is no envelope yet`() {
        val m = JSONObject().put("timestamp", 978_307_200_000L)
        assertEquals(978_307_200_000L, m.messageTime())
    }

    @Test
    fun `a zero envelope does not shadow the payload`() {
        val m = JSONObject().put("_timestamp", 0L).put("timestamp", 1_600_000_000_000L)
        assertEquals(1_600_000_000_000L, m.messageTime())
    }
}
