package com.pombo.android.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainErrorsTest {

    @Test
    fun `a write whose block-number read failed on every RPC is retried`() {
        val fromBridge = IllegalStateException(
            "Error while executing contract call \"streamRegistry.setMultipleStreamPermissionsForUserIds\", " +
                "code=INVALID_ARGUMENT (argument=\"%internal\")"
        )
        assertTrue(ChainErrors.isTransient(fromBridge))
    }

    @Test
    fun `any other invalid argument still fails at once`() {
        val badInput = IllegalStateException(
            "Error while executing contract call \"streamRegistry.setMultipleStreamPermissionsForUserIds\", " +
                "code=INVALID_ARGUMENT"
        )
        assertFalse(ChainErrors.isTransient(badInput))
    }

    @Test
    fun `a contract call cut off by the network, with nothing after its name, is retried`() {
        val cut = IllegalStateException("Error while executing contract call \"streamRegistry.updateStreamMetadata\"")
        assertTrue(ChainErrors.isTransient(cut))
    }

    @Test
    fun `a contract call that names a revert or a code still fails at once`() {
        for (message in listOf(
            "Error while executing contract call \"streamRegistry.updateStreamMetadata\", reason=execution reverted",
            "Error while executing contract call \"streamRegistry.updateStreamMetadata\", code=CALL_EXCEPTION",
            "Error while executing contract call \"streamRegistry.updateStreamMetadata\", code=INSUFFICIENT_FUNDS"
        )) {
            assertFalse(message, ChainErrors.isTransient(IllegalStateException(message)))
        }
    }

    @Test
    fun `the bridge marks only the wrapped internal block-number failure`() {
        val asset = File("src/main/assets/pombo_bridge.html").readText()
        assertTrue(asset.contains("inner.code === 'INVALID_ARGUMENT' && inner.argument === '%internal'"))
        assertTrue(asset.contains("msg += ' (argument=\"%internal\")'"))
    }
}
