package com.pombo.android.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A write whose receipt read failed has usually landed. The native retry asks
 * the bridge to confirm on chain from its second attempt on (`onlyIfMissing`),
 * so channel creation on a flaky RPC stops paying for the same stream, grant,
 * storage assignment or retention twice.
 *
 * The bridge page runs in the WebView and has no test runner of its own, so
 * what is checked here is that the confirmation is still in the shipped asset
 * (same approach as BridgeStorageRetentionTest).
 */
class BridgeWriteConfirmTest {

    private val asset = File("src/main/assets/pombo_bridge.html").readText()

    private fun body(name: String): String {
        val start = asset.indexOf("async $name(")
        assertTrue("$name is gone from the bridge", start >= 0)
        val next = asset.indexOf("\n    async ", start + 1)
        return asset.substring(start, if (next > 0) next else asset.length)
    }

    @Test
    fun `every write the creation retries confirms on chain before sending again`() {
        for (name in listOf("createStream", "setPermissions", "addToStorageNode", "setStorageDays")) {
            assertTrue("$name no longer honours onlyIfMissing", body(name).contains("onlyIfMissing"))
        }
    }

    @Test
    fun `a stream is created again only when the chain says it does not exist`() {
        assertTrue(
            "a failed read is taken for a missing stream, and the retry creates blind",
            Regex("STREAM_NOT_FOUND[\\s\\S]{0,40}return null;[\\s\\S]{0,20}throw e")
                .containsMatchIn(body("_streamIfExists"))
        )
    }

    @Test
    fun `a grant counts as in place only when every permission matches`() {
        val fn = body("_permissionsInPlace")
        for (permission in listOf("publish", "subscribe", "edit", "delete", "grant")) {
            assertTrue("$permission is not compared, so a narrower grant passes as landed", fn.contains("'$permission'"))
        }
    }

    @Test
    fun `the retention is read from the registry, past the SDK cache`() {
        assertTrue(
            "the retention check reads the SDK's cached metadata, which keeps the old value",
            body("_chainStorageDays").contains("getStreamMetadata")
        )
    }
}
