package com.pombo.android.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebView is handed a page, and the page is where an exfiltration would
 * have to start. These tests hand the renderer a known key and assert it is
 * absent from what the WebView receives, in every encoding it could travel in.
 */
class BridgePageTest {

    private val pk = "0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98"
    private val template = "<html>addr=__BRIDGE_ADDR__ pub=__BRIDGE_PUB__ rpcs=__BRIDGE_RPCS__</html>"
    private val asset = File("src/main/assets/pombo_bridge.html")

    private fun encodings(key: String): List<String> {
        val bare = key.removePrefix("0x")
        val bytes = SealedSenderCrypto.hexToBytes(key)
        return listOf(
            key,
            key.uppercase(),
            bare,
            bare.uppercase(),
            java.util.Base64.getEncoder().encodeToString(bytes),
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
            bytes.joinToString(",") { (it.toInt() and 0xff).toString() },
        )
    }

    @Test
    fun `the rendered page carries the address and the public key`() {
        val page = BridgePage.render(template, pk, listOf("https://rpc.example"))
        assertTrue(page.contains(EthereumSigner.checksumAddress(EthereumSigner.address(pk))))
        assertTrue(page.contains(EthereumSigner.compressedPublicKey(pk)))
        assertTrue(page.contains("[\"https:\\/\\/rpc.example\"]") || page.contains("[\"https://rpc.example\"]"))
    }

    @Test
    fun `the rendered page carries the private key in no encoding`() {
        val page = BridgePage.render(template, pk, listOf("https://rpc.example"))
        for (encoding in encodings(pk)) {
            assertFalse("private key leaked as $encoding", page.contains(encoding))
        }
    }

    @Test
    fun `the real bridge asset carries the private key in no encoding`() {
        val page = BridgePage.render(asset.readText(), pk, listOf("https://rpc.example"))
        for (encoding in encodings(pk)) {
            assertFalse("private key leaked as $encoding", page.contains(encoding))
        }
    }

    @Test
    fun `the bridge asset asks for nothing but the address, the public key and the endpoints`() {
        val placeholders = Regex("__BRIDGE_[A-Z_]+__").findAll(asset.readText())
            .map { it.value }.toSortedSet()
        assertEquals(sortedSetOf("__BRIDGE_ADDR__", "__BRIDGE_PUB__", "__BRIDGE_RPCS__"), placeholders)
    }

    @Test
    fun `an absent identity leaves the placeholders empty`() {
        val page = BridgePage.render(template, "", emptyList())
        assertEquals("<html>addr= pub= rpcs=[]</html>", page)
    }
}
