package com.pombo.android.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge page runs in the WebView and has no test runner of its own, so
 * what is checked here is the shipped asset (same approach as
 * BridgeWriteConfirmTest).
 */
class BridgeRpcHealthTest {

    private val asset = File("src/main/assets/pombo_bridge.html").readText()

    private fun between(from: String, to: String): String {
        val start = asset.indexOf(from)
        assertTrue("$from is gone from the bridge", start >= 0)
        val end = asset.indexOf(to, start)
        assertTrue("$to does not follow $from", end > start)
        return asset.substring(start, end)
    }

    @Test
    fun `the client is built only after the probe, with the endpoints that passed`() {
        val boot = between("async function boot()", "Native.connected(addr)")
        val probe = boot.indexOf("await api.rpcHealthProbe()")
        val healthy = boot.indexOf("RPCS = api._healthUsable().urls")
        val client = boot.indexOf("new window.StreamrClient(")
        assertTrue(probe in 0 until healthy)
        assertTrue(healthy < client)
    }

    @Test
    fun `the healthy list keeps the user's order and is never empty`() {
        val usable = between("_healthUsable() {", "async rpcHealthProbe()")
        assertTrue(usable.contains("CHOSEN_RPCS.filter("))
        assertTrue(usable.contains("CHOSEN_RPCS.slice(), fallback: true"))
    }

    @Test
    fun `Kotlin hears about the probe only once a client exists`() {
        val report = between("_healthReport() {", "_isRpcFailure(e) {")
        assertTrue(report.contains("if (!api._healthClientBuilt) return;"))
        assertTrue(report.contains("applied: RPCS.map("))
        assertTrue(report.contains("usable: usable.urls"))
    }

    @Test
    fun `the classifier reads the overload and rate-limit answers like the web`() {
        val failure = between("_healthFailure(o) {", "async _healthProbeOne(url)")
        assertTrue(failure.contains("o.status === 529 || o.status === 503"))
        assertTrue(failure.contains("o.status === 429"))
        assertTrue(failure.contains("o.code === -32005 || o.code === -32001"))
    }

    @Test
    fun `an RPC failure of any bridge call counts toward a new probe`() {
        val call = between("window.bridgeCall = function", "window.bridgePublishBinary")
        assertTrue(call.contains("if (api._isRpcFailure(e)) api._noteRpcFailure();"))
    }
}
