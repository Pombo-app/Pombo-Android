package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.coEvery
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The -4 is read one partition at a time, each bounded by the bridge call's
 * timeout. A slow partition costs its own rows, never the other one's.
 */
class KeysResendTimeoutTest {

    private val h = ChannelManagerHarness()
    private val manager = h.manager
    private val stream = "${h.me}/keys-1"
    private val keysStream = "${h.me}/keys-4"

    @After fun tearDown() = h.stop()

    private fun announceRow() = JSONObject()
        .put("content", JSONObject()
            .put("t", StreamConstants.KEY_ANNOUNCE).put("epoch", 1).put("keyId", "1.k")
            .put("keyHash", "0x" + "ab".repeat(32)).put("validFrom", 1_000L))
        .put("meta", JSONObject().put("publisherId", h.me).put("timestamp", 1_000L))

    @Test fun `a request partition that times out keeps what the announce partition read`() = runBlocking {
        coEvery { h.bridge.call("resend", any(), any()) } coAnswers {
            if (secondArg<JSONObject>().optInt("partition") == StreamConstants.P_REQUESTS) {
                withTimeout(1) { delay(1_000) }
            }
            JSONObject().put("messages", JSONArray().put(announceRow()))
        }

        runCatching {
            manager.epochKeys.ensureChannelKeys(stream, keysStream, allowMint = false, memberCount = 1, gated = true)
        }

        assertEquals(1, manager.epochKeys.currentEpoch(stream))
    }
}
