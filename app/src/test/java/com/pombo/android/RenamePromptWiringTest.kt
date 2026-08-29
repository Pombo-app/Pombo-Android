package com.pombo.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard, not a behavioural test: AppViewModel needs an Application and
 * a live ChannelManager to instantiate, and this project has no mocking
 * framework.
 *
 * A hidden channel's rename never reaches the chain (ChannelManager only calls
 * updateStreamMetadata when hasPublicMetadata). Asking for an on-chain
 * confirmation there costs the user the rename itself: cancelling the prompt
 * skips the local save too.
 */
class RenamePromptWiringTest {

    private val source = File("src/main/java/com/pombo/android/AppViewModel.kt").readText()

    private fun updateChannelMetadataBody(): String {
        val start = source.indexOf("fun updateChannelMetadata(")
        assertTrue("updateChannelMetadata is gone from AppViewModel", start >= 0)
        val end = source.indexOf("\n    suspend fun ", start + 1)
            .let { if (it < 0) source.length else it }
        return source.substring(start, end)
    }

    @Test
    fun `the rename asks for confirmation only when it writes on chain`() {
        val body = updateChannelMetadataBody()
        assertTrue(
            "the rename decides the prompt on something other than hasPublicMetadata",
            Regex("""hasPublicMetadata\(""").containsMatchIn(body)
        )
        assertTrue(
            "chainAction is no longer inside a branch",
            Regex("""if\s*\(onChain\)\s*\{\s*\r?\n\s*chainAction\(""").containsMatchIn(body)
        )
    }

    @Test
    fun `a hidden rename still saves`() {
        assertTrue(
            "the local branch stopped saving",
            Regex("""\}\s*else\s*\{\s*\r?\n\s*save\(\)""").containsMatchIn(updateChannelMetadataBody())
        )
    }
}
