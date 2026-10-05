package com.pombo.android.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bridge page runs in the WebView and has no test runner of its own, so
 * what is checked here is the shipped asset (same approach as
 * BridgeWriteConfirmTest).
 */
class BridgeGateQuorumTest {

    private val asset = File("src/main/assets/pombo_bridge.html").readText()

    @Test
    fun `each access-quorum read is bounded by the gate read deadline`() {
        val start = asset.indexOf("async _gateReadAt(")
        assertTrue("_gateReadAt is gone from the bridge", start >= 0)
        val body = asset.substring(start, asset.indexOf("\n    },", start))
        assertTrue(
            "an endpoint that never answers holds the key responder's decision",
            body.contains("api._gateDeadline(")
        )
    }
}
