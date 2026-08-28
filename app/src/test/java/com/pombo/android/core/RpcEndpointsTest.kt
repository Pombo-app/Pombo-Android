package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with the web's RPC selection model (config.js): same rows, same order,
 * same storage shape, same migration from the older single-preset setting.
 */
class RpcEndpointsTest {

    private fun keysOn(selection: RpcEndpoints.Selection) =
        selection.rows.filter { it.on }.map { it.key }

    @Test
    fun endpoints_areUniqueAndReachable() {
        val keys = RpcEndpoints.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(RpcEndpoints.ALL.any { it.webviewSafe })
        RpcEndpoints.ALL.forEach { assertTrue(it.url.startsWith("https://")) }
    }

    @Test
    fun endpoints_dropTheOnesThatStoppedAnswering() {
        RpcEndpoints.ALL.forEach {
            assertFalse(it.url.contains("meowrpc"))
            assertFalse(it.url.contains("llamarpc"))
            assertFalse(it.url.contains("rpc.ankr.com"))
        }
    }

    @Test
    fun normalize_offersEveryRowPlusCustom() {
        val selection = RpcEndpoints.normalize(emptyList(), "")
        assertEquals(
            RpcEndpoints.ALL.map { it.key } + RpcEndpoints.CUSTOM_KEY,
            selection.rows.map { it.key }
        )
    }

    @Test
    fun normalize_keepsSavedOrderAndAppendsTheRest() {
        val selection = RpcEndpoints.normalize(
            listOf(RpcEndpoints.Row("1rpc", true), RpcEndpoints.Row("drpc", true)),
            ""
        )
        assertEquals("1rpc", selection.rows[0].key)
        assertEquals("drpc", selection.rows[1].key)
        assertEquals(
            listOf("https://1rpc.io/matic", "https://polygon.drpc.org"),
            selection.urls
        )
        selection.rows.drop(2).forEach { assertFalse(it.on) }
    }

    @Test
    fun normalize_dropsKeysTheCodeNoLongerKnows() {
        val selection = RpcEndpoints.normalize(
            listOf(RpcEndpoints.Row("meowrpc", true), RpcEndpoints.Row("drpc", true)),
            ""
        )
        assertFalse(selection.rows.any { it.key == "meowrpc" })
        assertEquals(listOf("https://polygon.drpc.org"), selection.urls)
    }

    @Test
    fun normalize_fallsBackToTheDefaultWhenNothingIsUsable() {
        val allOff = RpcEndpoints.ALL.map { RpcEndpoints.Row(it.key, false) }
        assertEquals(RpcEndpoints.DEFAULT_ENABLED, keysOn(RpcEndpoints.normalize(allOff, "")))

        // A ticked custom row with no URL behind it is not a selection either.
        val onlyEmptyCustom = listOf(RpcEndpoints.Row(RpcEndpoints.CUSTOM_KEY, true))
        assertEquals(
            RpcEndpoints.DEFAULT_ENABLED,
            keysOn(RpcEndpoints.normalize(onlyEmptyCustom, "   "))
        )
    }

    @Test
    fun json_roundTripsOrderAndCustomUrl() {
        val selection = RpcEndpoints.normalize(
            listOf(RpcEndpoints.Row("tenderly", true), RpcEndpoints.Row(RpcEndpoints.CUSTOM_KEY, true)),
            "https://my-own-node.example"
        )
        val back = RpcEndpoints.fromJson(RpcEndpoints.toJson(selection))
        assertEquals(selection.rows, back.rows)
        assertEquals(selection.customUrl, back.customUrl)
        assertEquals(
            listOf("https://polygon.gateway.tenderly.co", "https://my-own-node.example"),
            back.urls
        )
    }

    @Test
    fun json_survivesGarbage() {
        assertEquals(RpcEndpoints.DEFAULT_ENABLED, keysOn(RpcEndpoints.fromJson("not json")))
        assertEquals(RpcEndpoints.DEFAULT_ENABLED, keysOn(RpcEndpoints.fromJson(null)))
    }

    @Test
    fun legacy_autoBecomesEveryEndpointInOrder() {
        assertEquals(
            RpcEndpoints.ALL.map { it.url },
            RpcEndpoints.fromLegacy("auto", null).urls
        )
    }

    @Test
    fun legacy_providerBecomesThatProviderAlone() {
        assertEquals(
            listOf("https://polygon.gateway.tenderly.co"),
            RpcEndpoints.fromLegacy("tenderly", null).urls
        )
    }

    @Test
    fun legacy_customKeepsTheEndpointsTheBridgeUsedToAppend() {
        val selection = RpcEndpoints.fromLegacy("custom", "https://my-own-node.example")
        assertEquals(
            listOf("https://my-own-node.example") +
                RpcEndpoints.DEFAULT_ENABLED.map { RpcEndpoints.byKey(it)!!.url },
            selection.urls
        )
    }

    @Test
    fun legacy_presetThatIsGoneFallsBackToTheDefault() {
        assertEquals(RpcEndpoints.DEFAULT_ENABLED, keysOn(RpcEndpoints.fromLegacy("meowrpc", null)))
    }

    @Test
    fun reachesWebView_needsOneEndpointTheBridgeCanCall() {
        val customOnly = RpcEndpoints.normalize(
            listOf(RpcEndpoints.Row(RpcEndpoints.CUSTOM_KEY, true)),
            "https://my-own-node.example"
        )
        assertFalse(customOnly.reachesWebView(customUrlProvenFromWebView = false))
        assertTrue(customOnly.reachesWebView(customUrlProvenFromWebView = true))

        val withProvider = customOnly.withRow("drpc", true)
        assertTrue(withProvider.reachesWebView(customUrlProvenFromWebView = false))
    }

    @Test
    fun moved_reordersWithinTheWholeList() {
        val start = RpcEndpoints.normalize(emptyList(), "")
        val moved = start.moved(start.rows[0].key, 1)
        assertEquals(start.rows[1].key, moved.rows[0].key)
        assertEquals(start.rows[0].key, moved.rows[1].key)

        // Off the ends is a no-op rather than a crash.
        assertEquals(start.rows, start.moved(start.rows.first().key, -1).rows)
        assertEquals(start.rows, start.moved(start.rows.last().key, 1).rows)
    }
}
