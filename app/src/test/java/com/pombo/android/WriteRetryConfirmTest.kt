package com.pombo.android

import io.mockk.coEvery
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The first attempt of an on-chain write sends it; every retry asks the
 * bridge to confirm on chain first, since a write whose receipt read failed
 * has usually landed.
 */
class WriteRetryConfirmTest {

    private val h = ChannelManagerHarness()

    @After fun tearDown() = h.stop()

    @Test fun `only the retries of a write confirm on chain before sending`() {
        val flags = mutableListOf<Boolean>()
        coEvery { h.bridge.call("setPermissions", any(), any()) } answers {
            flags += secondArg<JSONObject>().optBoolean("onlyIfMissing")
            if (flags.size == 1) throw IllegalStateException("Error while waiting transaction, code=SERVER_ERROR")
            JSONObject().put("ok", true).put("skipped", true)
        }

        runBlocking { h.manager.setPermissionsRetry("${h.me}/c0ffee-1", JSONArray()) }

        assertEquals(listOf(false, true), flags)
    }

    @Test fun `a write that fails for good is not retried`() {
        var calls = 0
        coEvery { h.bridge.call("setPermissions", any(), any()) } answers {
            calls++
            throw IllegalStateException("insufficient funds for gas")
        }

        runCatching { runBlocking { h.manager.setPermissionsRetry("${h.me}/c0ffee-1", JSONArray()) } }

        assertEquals(1, calls)
    }
}
