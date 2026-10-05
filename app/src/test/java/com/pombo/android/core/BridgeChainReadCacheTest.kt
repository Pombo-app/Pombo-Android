package com.pombo.android.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge page runs in the WebView and has no test runner of its own, so
 * what is checked here is the shipped asset (same approach as
 * BridgeWriteConfirmTest).
 */
class BridgeChainReadCacheTest {

    private val asset = File("src/main/assets/pombo_bridge.html").readText()

    private fun body(name: String): String {
        val start = asset.indexOf("async $name(")
        assertTrue("$name is gone from the bridge", start >= 0)
        val next = asset.indexOf("\n    async ", start + 1)
        return asset.substring(start, if (next > 0) next else asset.length)
    }

    @Test
    fun `a known gate is read for price and duration only`() {
        val info = body("gateInfo")
        assertTrue(info.contains("api._chainFacts('gates')"))
        assertTrue(info.contains("Promise.all([gate.price(), gate.duration()])"))
        assertTrue(info.contains("api._rememberChainFact('gates'"))
    }

    @Test
    fun `token metadata is kept only when both getters answered, and first reads are shared`() {
        val meta = body("gateTokenMeta")
        assertTrue(meta.contains("api._gateTokenMetaPending"))
        assertTrue(meta.contains("if (res.readBoth) api._rememberChainFact('tokens'"))
    }

    @Test
    fun `an ENS lookup no provider answered fails instead of reading as no name`() {
        assertTrue(body("resolveEns").contains("if (!answered) throw new Error('no ENS provider answered')"))
    }

    @Test
    fun `the dead ENS endpoint is gone`() {
        assertFalse(asset.contains("cloudflare-eth.com"))
    }
}
