package com.pombo.android.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge attaches a storage node and sets the retention in one call, and
 * the retention is a SECOND transaction that fails on its own. It used to be
 * caught and dropped, with `{ ok: true }` returned either way, so the native
 * retry never saw the failure and the channel record claimed a retention the
 * stream never got.
 *
 * The bridge page runs in the WebView and has no test runner of its own, so
 * what is checked here is that the defence is still in the shipped asset
 * (same approach as BridgeDmKeyBindingTest).
 */
class BridgeStorageRetentionTest {

    private val asset = File("src/main/assets/pombo_bridge.html").readText()

    private fun body(name: String): String {
        val start = asset.indexOf("async $name(")
        assertTrue("$name is gone from the bridge", start >= 0)
        val next = asset.indexOf("\n    async ", start + 1)
        return asset.substring(start, if (next > 0) next else asset.length)
    }

    @Test
    fun `addToStorageNode reports whether the retention landed`() {
        val fn = body("addToStorageNode")
        assertTrue(
            "the caller can no longer tell an applied retention from a dropped one",
            fn.contains("retentionApplied")
        )
    }

    @Test
    fun `a dropped retention is never reported as applied`() {
        val fn = body("addToStorageNode")
        assertTrue(
            "the failure path no longer says retentionApplied: false",
            Regex("retentionApplied:\\s*false").containsMatchIn(fn)
        )
        assertTrue(
            "the success path no longer says retentionApplied: true",
            Regex("retentionApplied:\\s*true").containsMatchIn(fn)
        )
    }

    @Test
    fun `the retention gets its own retries, not just the node assignment`() {
        val fn = body("addToStorageNode")
        assertTrue(
            "setStorageDayCount is no longer retried inside the bridge, and the " +
                "native retry cannot reach it through the catch",
            Regex("for\\s*\\([\\s\\S]{0,120}setStorageDayCount").containsMatchIn(fn)
        )
    }

    @Test
    fun `a failed storage lookup is distinguishable from an empty one`() {
        val fn = body("getStreamStorageInfo")
        assertTrue(
            "a lookup that threw is answered with the same empty shape a stream " +
                "with no storage returns, and nothing can tell them apart",
            Regex("ok:\\s*false").containsMatchIn(fn)
        )
        assertTrue(
            "the success path no longer says ok: true",
            Regex("ok:\\s*true").containsMatchIn(fn)
        )
    }

    @Test
    fun `there is one storage info handler, not two`() {
        assertEquals(
            "the duplicate storage info handler is back",
            0,
            Regex("async getStorageInfo\\(").findAll(asset).count()
        )
    }

    @Test
    fun `a failed retention is loud rather than silent`() {
        val fn = body("addToStorageNode")
        assertTrue(
            "the empty catch is back: a dropped retention leaves no trace",
            fn.contains("console.warn")
        )
    }
}
