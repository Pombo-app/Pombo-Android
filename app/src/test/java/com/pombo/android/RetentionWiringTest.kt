package com.pombo.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guards, not behavioural tests.
 *
 * The decisions themselves are covered by StreamRetentionTest and
 * StorageStreamsTest. What is NOT reachable from a unit test is the wiring:
 * ChannelManager needs a live bridge and store to instantiate, and this
 * project has no mocking framework. So the three places that have to keep
 * using the right value are pinned here by shape.
 *
 * A source guard catches the realistic regression, which is a call site
 * copied from an older one or a revert, and nothing else. Treat a failure as
 * "check this on purpose", not as proof of a defect.
 */
class RetentionWiringTest {

    private val source = File("src/main/java/com/pombo/android/ChannelManager.kt").readText()

    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is gone from ChannelManager", start >= 0)
        val next = source.indexOf("\n    private suspend fun ", start + 1)
            .let { if (it < 0) source.length else it }
        val next2 = source.indexOf("\n    suspend fun ", start + 1)
            .let { if (it < 0) source.length else it }
        return source.substring(start, minOf(next, next2))
    }

    /**
     * The -4 announces age out by the keys stream's own retention. Passing the
     * message stream's is how a gated channel loses its KEY_ANNOUNCE anchor
     * without anything reporting it.
     */
    @Test
    fun `no epoch-key path passes the message stream retention`() {
        assertEquals(
            "a call site went back to the -1 retention for the keys stream",
            0,
            Regex("storageDays\\s*\\?:\\s*180").findAll(source).count()
        )
    }

    @Test
    fun `every ensureChannelKeys call takes the keys retention`() {
        val calls = Regex("ensureChannelKeys\\(").findAll(source).count()
        val correct = Regex("keysRetentionDays\\(channel\\)").findAll(source).count()
        assertEquals("an ensureChannelKeys call stopped using keysRetentionDays", calls, correct)
    }

    /** The purge applies the -3's own retention to the -3 artifacts. */
    @Test
    fun `the TTL republish decides with the admin retention`() {
        assertTrue(
            "ttlRepublishOnOpen no longer resolves the admin stream retention",
            body("private suspend fun ttlRepublishOnOpen(").contains("adminRetentionDays(")
        )
    }

    /** Retention has to reach every stored stream, the -4 included. */
    @Test
    fun `setStorageDays writes to every stored stream`() {
        assertTrue(
            "setStorageDays stopped iterating the channel's stored streams",
            body("suspend fun setStorageDays(").contains("storedStreams(channel)")
        )
    }
}
