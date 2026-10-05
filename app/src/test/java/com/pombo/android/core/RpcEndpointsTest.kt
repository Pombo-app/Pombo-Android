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
    fun defaultEnabled_isAtMostFour_allUsableFromTheBridge() {
        assertTrue(RpcEndpoints.DEFAULT_ENABLED.size in 2..4)
        RpcEndpoints.DEFAULT_ENABLED.forEach { assertTrue(RpcEndpoints.byKey(it)!!.webviewSafe) }
        assertEquals(
            RpcEndpoints.DEFAULT_ENABLED,
            keysOn(RpcEndpoints.normalize(emptyList(), ""))
        )
    }

    @Test
    fun savedSelection_staysAsItWasWhenTheDefaultChanges() {
        val saved = RpcEndpoints.normalize(
            listOf(
                RpcEndpoints.Row("drpc", true), RpcEndpoints.Row("publicnode", true),
                RpcEndpoints.Row("tenderly", true), RpcEndpoints.Row("1rpc", false)
            ),
            ""
        )
        assertEquals(listOf("drpc", "publicnode", "tenderly"), keysOn(saved))
        assertEquals(RpcEndpoints.Row("pocket", false), saved.rows.first { it.key == "pocket" })
    }

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
    fun normalize_offersEveryEndpointAndNoCustomRowUntilOneExists() {
        val selection = RpcEndpoints.normalize(emptyList(), "")
        assertEquals(RpcEndpoints.ALL.map { it.key }, selection.rows.map { it.key })
    }

    @Test
    fun normalize_carriesTheCustomRowOnlyWhileAUrlStandsBehindIt() {
        val withUrl = RpcEndpoints.normalize(
            listOf(RpcEndpoints.Row("drpc", true), RpcEndpoints.Row(RpcEndpoints.CUSTOM_KEY, true)),
            "https://my-own-node.example"
        )
        assertTrue(withUrl.rows.any { it.key == RpcEndpoints.CUSTOM_KEY })

        val without = RpcEndpoints.normalize(
            listOf(RpcEndpoints.Row("drpc", true), RpcEndpoints.Row(RpcEndpoints.CUSTOM_KEY, true)),
            "   "
        )
        assertFalse(without.rows.any { it.key == RpcEndpoints.CUSTOM_KEY })
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
        assertFalse(RpcEndpoints.normalize(onlyEmptyCustom, "   ").rows.any {
            it.key == RpcEndpoints.CUSTOM_KEY
        })
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

    private fun saved(version: Int, rows: List<Pair<String, Boolean>>, customUrl: String = "") =
        org.json.JSONObject()
            .put("v", version)
            .put("rows", org.json.JSONArray().apply {
                rows.forEach { (key, on) -> put(org.json.JSONObject().put("key", key).put("on", on)) }
            })
            .put("customUrl", customUrl)
            .toString()

    private val oldDefault = listOf("drpc", "publicnode", "tenderly")

    @Test
    fun previousDefault_movesToTheCurrentOne_whateverTheSavedOrder() {
        val json = saved(2, listOf("tenderly" to true, "drpc" to true, "publicnode" to true, "1rpc" to false))
        assertEquals(
            RpcEndpoints.DEFAULT_ENABLED.map { RpcEndpoints.byKey(it)!!.url },
            RpcEndpoints.fromJson(json).urls
        )
    }

    @Test
    fun previousDefault_keepsACustomUrlAsAnOffRow() {
        val json = saved(
            2, oldDefault.map { it to true } + (RpcEndpoints.CUSTOM_KEY to false), "https://my-own-node.example"
        )
        val moved = RpcEndpoints.fromJson(json)
        assertEquals(RpcEndpoints.DEFAULT_ENABLED, keysOn(moved))
        assertEquals("https://my-own-node.example", moved.customUrl)
        assertEquals(RpcEndpoints.Row(RpcEndpoints.CUSTOM_KEY, false), moved.rows.first { it.key == RpcEndpoints.CUSTOM_KEY })
    }

    @Test
    fun anyOtherSavedChoice_staysAsItWas() {
        val choices = listOf(
            saved(2, listOf("drpc" to true, "tenderly" to true)) to listOf("drpc", "tenderly"),
            saved(2, (oldDefault + "1rpc").map { it to true }) to oldDefault + "1rpc",
            saved(
                2, oldDefault.map { it to true } + (RpcEndpoints.CUSTOM_KEY to true), "https://my-own-node.example"
            ) to oldDefault + RpcEndpoints.CUSTOM_KEY
        )
        choices.forEach { (json, enabled) -> assertEquals(enabled, keysOn(RpcEndpoints.fromJson(json))) }
    }

    @Test
    fun aChoiceSavedAfterTheChange_isNeverMoved_evenTheOldDefault() {
        assertEquals(oldDefault, keysOn(RpcEndpoints.fromJson(saved(3, oldDefault.map { it to true }))))
    }

    @Test
    fun movedSelection_staysPutOnceSaved() {
        val moved = RpcEndpoints.fromJson(saved(2, oldDefault.map { it to true }))
        assertEquals(moved, RpcEndpoints.fromJson(RpcEndpoints.toJson(moved)))
    }
}
