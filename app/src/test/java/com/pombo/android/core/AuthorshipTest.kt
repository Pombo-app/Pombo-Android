package com.pombo.android.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with the web's authorship.js, locked by web-generated vectors
 * (Pombo Web tests/vectors/gen_authorship_vectors.mjs,
 * docs/GATED-CHANNELS-authorship-vectors.json). If any domain string or
 * digest construction changes, regenerate with the web — never edit by hand.
 */
class AuthorshipTest {

    private val accountPriv = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
    private val author = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"
    private val pseudonymPriv = "0x5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e01"
    private val streamId = "0xaaaabbbbccccddddeeeeffff0000111122223333/deadbeef01-1"

    /** The exact wrapper the web produced for these keys and payload. */
    private val webWrapper = JSONObject()
        .put("v", 1)
        .put("p", """{"type":"text","id":"msg-0001","text":"olá autoria selada","timestamp":1787000000000}""")
        .put("pk", "0x03126e8824ff1ce4bbf18352df62dc12687ea5613f046a32a3bcfc268b2b5b0a30")
        .put("sig", "0xe8ab29125cb8a9e7f5a9762494b56dcb525e03f92eb60ce720c3d8cf1ce35f6f4aad074a93d38a051aab3d9973c99b87700c7d52b90e540a94524ac60df3e6391b")
        .put("bp", "0x6bedd9ed7332468bc2da2e4e29b600ba128c8705144bd5b9ac92c0eb09eeb96e40b6c9734a56575641c88f22dd6a4eea0f74c158652da6888920acb2cafbf0721c")

    @Test
    fun `opens the web-produced wrapper to the exact author and payload`() {
        val opened = Authorship.open(streamId, webWrapper)!!
        assertEquals(author, opened.author)
        assertEquals("olá autoria selada", opened.payload.optString("text"))
        assertEquals(1787000000000L, opened.payload.optLong("timestamp"))
    }

    @Test
    fun `android seal round-trips and opens on this side`() {
        val pub = EthereumSigner.compressedPublicKey(pseudonymPriv)
        val bp = Authorship.createBindProof(streamId, pub, accountPriv)
        val wrapper = Authorship.seal(
            streamId, JSONObject().put("type", "text").put("id", "x").put("text", "hello"),
            pseudonymPriv, pub, bp)
        val opened = Authorship.open(streamId, wrapper)!!
        assertEquals(author, opened.author)
        assertEquals("hello", opened.payload.optString("text"))
    }

    @Test
    fun `android bind proof matches the web vector byte for byte`() {
        // RFC 6979 deterministic signing on both sides — same digest, same key,
        // same signature.
        val pub = EthereumSigner.compressedPublicKey(pseudonymPriv)
        assertEquals(webWrapper.getString("bp"), Authorship.createBindProof(streamId, pub, accountPriv))
    }

    @Test
    fun `rejects a tampered payload`() {
        val tampered = JSONObject(webWrapper.toString())
            .put("p", webWrapper.getString("p").replace("selada", "forjada"))
        assertNull(Authorship.open(streamId, tampered))
    }

    @Test
    fun `a pasted bind proof can never impersonate its owner`() {
        val (attackerPriv, attackerPub) = EpochKeyCrypto.generateRequestKeypair()
        val forged = Authorship.seal(
            streamId, JSONObject().put("type", "text").put("text", "not me"),
            attackerPriv, attackerPub, webWrapper.getString("bp"))
        val opened = Authorship.open(streamId, forged)
        assertNotEquals(author, opened?.author)
    }

    @Test
    fun `rejects a proof replayed into another channel`() {
        assertNull(Authorship.open("0x" + "99".repeat(20) + "/other-1", webWrapper))
    }

    @Test
    fun `wrapper detection rejects plain messages`() {
        assertFalse(Authorship.isWrapper(JSONObject().put("type", "text").put("text", "hi")))
        assertTrue(Authorship.isWrapper(webWrapper))
    }
}
