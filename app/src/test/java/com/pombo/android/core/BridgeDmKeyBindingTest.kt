package com.pombo.android.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The peer's DM public key is read from inbox metadata over a user-chosen RPC
 * with public failover, so a forged `pk` there is a full DM MITM. Both clients
 * defend the same way: the key must derive into the address that owns the
 * inbox. The web's copy has behavioural tests; this copy lives inside the
 * bridge page, which runs in the WebView and has no test runner of its own, so
 * what is checked here is that the defence is still in the shipped asset.
 */
class BridgeDmKeyBindingTest {

    private val asset = File("src/main/assets/pombo_bridge.html").readText()

    private fun body(name: String): String {
        val start = asset.indexOf("async $name(")
        assertTrue("$name is gone from the bridge", start >= 0)
        val next = asset.indexOf("\n    async ", start + 1)
        return asset.substring(start, if (next > 0) next else asset.length)
    }

    @Test
    fun `getPeerPublicKey binds the key to the address that owns the inbox`() {
        val fn = body("getPeerPublicKey")
        assertTrue(
            "the returned key is no longer checked against the inbox owner",
            fn.contains("computeAddress")
        )
        assertTrue(
            "the address comparison no longer uses the address that was asked for",
            Regex("computeAddress\\(pk\\)[\\s\\S]{0,80}a\\.address").containsMatchIn(fn)
        )
        assertTrue(
            "a key that fails the check is no longer refused",
            Regex("a\\.address[\\s\\S]{0,200}publicKey:\\s*null").containsMatchIn(fn)
        )
    }

    @Test
    fun `getPeerPublicKey reads the inbox of the address it was given`() {
        val fn = body("getPeerPublicKey")
        assertTrue(
            "the inbox stream id is no longer derived from the requested address",
            Regex("a\\.address[\\s\\S]{0,120}Pombo-DM-1").containsMatchIn(fn)
        )
    }
}
