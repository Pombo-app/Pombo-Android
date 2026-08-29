package com.pombo.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard, not a behavioural test — same reasoning as RetentionWiringTest:
 * ChannelManager needs a live bridge and store to instantiate and this project
 * has no mocking framework.
 *
 * `keysStreamId` is empty on records that came back through the sync, so every
 * caller derives it from the message stream. A caller that bails on the empty
 * value instead skips its work in exactly the case the sync produces.
 */
class KeysStreamWiringTest {

    private val source = File("src/main/java/com/pombo/android/ChannelManager.kt").readText()

    private fun body(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is gone from ChannelManager", start >= 0)
        val end = source.indexOf("\n    private ", start + 1)
            .let { if (it < 0) source.length else it }
        val end2 = source.indexOf("\n    suspend fun ", start + 1)
            .let { if (it < 0) source.length else it }
        return source.substring(start, minOf(end, end2))
    }

    @Test
    fun `the immediate key request derives the keys stream`() {
        val fn = body("suspend fun requestChannelKeysNow()")
        assertTrue(
            "requestChannelKeysNow stopped deriving the keys stream id",
            Regex("""keysStreamId\.ifEmpty\s*\{\s*StreamConstants\.deriveKeysId\(""").containsMatchIn(fn)
        )
    }

    @Test
    fun `no caller bails on an empty keys stream`() {
        assertEquals(
            "a caller went back to skipping its work when keysStreamId is empty",
            0,
            Regex("""keysStreamId\.isEmpty\(\)\s*\)\s*return""").findAll(source).count()
        )
    }
}
