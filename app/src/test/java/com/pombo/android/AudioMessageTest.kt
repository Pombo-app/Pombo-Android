package com.pombo.android

import com.pombo.android.core.SecurePrefs
import com.pombo.android.core.StreamConstants
import com.pombo.android.data.FailedOutboxStore
import com.pombo.android.data.FailedOutboxStoreTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Voice messages ride the chunked image transport under their own manifest
 * type: assembly, the ceilings a received manifest is held to, and the send
 * states with their Retry.
 */
class AudioMessageTest {

    private val me = com.pombo.android.core.EthereumSigner
        .address("0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d").lowercase()
    private val streamId = "$me/voice-1"
    private val peer = "0xpeer"
    private val stored = HashMap<String, String>()
    private val harnesses = mutableListOf<ChannelManagerHarness>()

    @Before
    fun setUp() {
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        every { android.util.Base64.encodeToString(any<ByteArray>(), any()) } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg<ByteArray>())
        }
        mockkObject(SecurePrefs)
        every { SecurePrefs.create(any(), any(), any()) } returns FailedOutboxStoreTest.inMemoryPrefs(stored)
    }

    @After
    fun tearDown() {
        harnesses.forEach { it.stop() }
        unmockkObject(SecurePrefs)
        unmockkStatic(android.util.Base64::class)
    }

    private fun outbox() = FailedOutboxStore(mockk(relaxed = true)).apply { scopeAddress = me }

    private fun launch(): ChannelManagerHarness =
        ChannelManagerHarness(channels = listOf(ChannelManagerHarness.channel(streamId)), failedOutbox = outbox())
            .also { harnesses += it; it.manager.openChannel(streamId) }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val one = "voice-".toByteArray()
    private val two = "message".toByteArray()

    private fun manifest(
        chunks: List<ByteArray> = listOf(one, two),
        mime: String = "audio/webm",
        durationMs: Long? = 4200L,
        declaredSize: Int = chunks.sumOf { it.size }
    ) = JSONObject()
        .put("type", "audio").put("transport", "chunked").put("v", 2)
        .put("imageId", "aud-1").put("id", "m-aud")
        .put("text", ChannelManager.AUDIO_FALLBACK_TEXT)
        .put("sender", peer)
        .put("chunkHashes", JSONArray(chunks.map { sha256(it) }))
        .put("chunkCount", chunks.size)
        .put("finalMime", mime)
        .put("finalSizeBytes", declaredSize)
        .put("assembledSha256", sha256(chunks.fold(ByteArray(0)) { a, b -> a + b }))
        .put("timestamp", 1_750_000_000_000L)
        .apply { if (durationMs != null) put("durationMs", durationMs) }

    private fun chunk(index: Int, bytes: ByteArray) =
        JSONObject().put("type", "image_chunk").put("v", 2)
            .put("imageId", "aud-1").put("chunkIndex", index)
            .put("data", java.util.Base64.getEncoder().encodeToString(bytes))

    private fun ChannelManagerHarness.bubble(id: String = "m-aud") = manager.messages.value.find { it.id == id }

    @Test
    fun `an audio manifest and its chunks assemble into a voice bubble`() {
        val h = launch()
        h.deliver(streamId, StreamConstants.P_MESSAGES, manifest(), peer)
        h.deliver(streamId, StreamConstants.P_MESSAGES, chunk(0, one), peer)
        h.deliver(streamId, StreamConstants.P_MESSAGES, chunk(1, two), peer)

        val bubble = h.bubble()!!
        assertTrue(bubble.isAudio)
        assertFalse(bubble.isImage)
        assertEquals(4200L, bubble.audioDurationMs)
        assertEquals("audio/webm", bubble.imageMime)
        assertEquals("voice-message", String(bubble.imageBytes!!))
        assertEquals(ChannelManager.AUDIO_FALLBACK_TEXT, bubble.text)
    }

    @Test
    fun `an audio manifest without a duration is refused`() {
        val h = launch()
        h.deliver(streamId, StreamConstants.P_MESSAGES, manifest(durationMs = null), peer)
        assertNull(h.bubble())
    }

    @Test
    fun `an audio manifest naming a non-audio type is refused`() {
        val h = launch()
        h.deliver(streamId, StreamConstants.P_MESSAGES, manifest(mime = "image/png"), peer)
        assertNull(h.bubble())
    }

    @Test
    fun `an audio manifest over the audio ceiling is refused`() {
        val h = launch()
        h.deliver(streamId, StreamConstants.P_MESSAGES,
            manifest(declaredSize = ChannelManager.AUDIO_MAX_ASSEMBLED_BYTES + 1), peer)
        assertNull(h.bubble())
    }

    @Test
    fun `sending publishes the chunks before the manifest and settles as sent`() {
        val h = launch()
        val bytes = ByteArray(300 * 1024) { (it % 251).toByte() }
        runBlocking { h.manager.sendAudio(bytes, "audio/mp4", 7_000L) }

        val types = h.published.mapNotNull {
            val p = JSONObject(it)
            (p.optJSONObject("content") ?: p).optString("type").takeIf { t -> t == "image_chunk" || t == "audio" }
        }
        assertTrue("expected several chunks then the manifest, got $types", types.size >= 3)
        assertEquals("audio", types.last())
        assertTrue(types.dropLast(1).all { it == "image_chunk" })

        val sent = h.manager.messages.value.single { it.isAudio }
        assertFalse(sent.pending)
        assertFalse(sent.failed)
        assertEquals(7_000L, sent.audioDurationMs)

        val manifest = h.published.map { JSONObject(it).let { p -> p.optJSONObject("content") ?: p } }
            .single { it.optString("type") == "audio" }
        assertEquals("audio/mp4", manifest.getString("finalMime"))
        assertEquals(7_000L, manifest.getLong("durationMs"))
        assertEquals(ChannelManager.AUDIO_FALLBACK_TEXT, manifest.getString("text"))
        assertEquals(types.size - 1, manifest.getInt("chunkCount"))
    }

    @Test
    fun `a recording over 1MB is refused before any bubble`() {
        val h = launch()
        val result = runCatching {
            runBlocking { h.manager.sendAudio(ByteArray(ChannelManager.AUDIO_MAX_ASSEMBLED_BYTES + 1), "audio/mp4", 1_000L) }
        }
        assertTrue(result.isFailure)
        assertTrue(h.manager.messages.value.none { it.isAudio })
    }

    @Test
    fun `offline the bubble fails and is kept, and its retry republishes under the same id`() {
        val h = launch()
        h.online = false
        runCatching { runBlocking { h.manager.sendAudio(one + two, "audio/mp4", 2_000L) } }

        val failed = h.manager.messages.value.single { it.isAudio }
        assertTrue(failed.failed)
        assertEquals(0, h.publishedOfType("audio"))
        val entry = outbox().load(streamId).single()
        assertEquals("audio", entry.getString("type"))
        assertEquals(failed.id, entry.getString("id"))

        h.online = true
        runBlocking { h.manager.resendMessage(failed.id) }

        val manifest = h.published.map { JSONObject(it).let { p -> p.optJSONObject("content") ?: p } }
            .single { it.optString("type") == "audio" }
        assertEquals(failed.id, manifest.getString("id"))
        assertEquals(failed.timestamp, manifest.getLong("timestamp"))
        assertFalse(h.manager.messages.value.single { it.isAudio }.failed)
        assertTrue(outbox().load(streamId).isEmpty())
    }

    @Test
    fun `after a restart a failed voice message comes back as one, not as text`() {
        val first = launch()
        first.online = false
        runCatching { runBlocking { first.manager.sendAudio(one + two, "audio/mp4", 2_000L) } }
        val id = first.manager.messages.value.single { it.isAudio }.id
        first.stop()

        val h = launch()
        val restored = h.bubble(id)
        assertNotNull(restored)
        assertTrue(restored!!.isAudio)
        assertTrue(restored.failed)
        assertEquals(2_000L, restored.audioDurationMs)
        assertEquals("audio/mp4", restored.imageMime)
    }
}
