package com.pombo.android

import com.pombo.android.core.StreamConstants
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest

/**
 * Chunked images (transport 'chunked' v2): the manifest, the chunks, and the
 * assembly that only completes when every hash matches.
 *
 * Half of this is hostile input. A manifest is a stranger's description of how
 * much memory to set aside, so the ceilings it is checked against are as much
 * a part of the feature as the assembly itself.
 */
class ImageAssemblyTest {

    private val owner = "0xowner"
    private val streamId = "$owner/pics-1"
    private val room = ChannelManagerHarness.channel(streamId)
    private val h = ChannelManagerHarness(channels = listOf(room))
    private val manager = h.manager
    private val peer = "0xpeer"

    @Before
    fun setUp() {
        // android.util.Base64 is a stub in unit tests; the chunk path needs a
        // real decoder or every payload arrives as null.
        mockkStatic(android.util.Base64::class)
        every { android.util.Base64.decode(any<String>(), any()) } answers {
            java.util.Base64.getDecoder().decode(firstArg<String>())
        }
        manager.openChannel(streamId)
    }

    @After
    fun tearDown() {
        h.stop()
        unmockkStatic(android.util.Base64::class)
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)

    private fun manifest(
        imageId: String = "img-1",
        messageId: String = "m-1",
        chunks: List<ByteArray>,
        chunkCount: Int = chunks.size,
        hashes: List<String> = chunks.map { sha256(it) },
        declaredSize: Int = chunks.sumOf { it.size },
        assembledSha: String = sha256(chunks.fold(ByteArray(0)) { a, b -> a + b }),
        mime: String = "image/jpeg",
        transport: String = "chunked",
        v: Int = 2
    ) = JSONObject()
        .put("type", "image").put("transport", transport).put("v", v)
        .put("imageId", imageId).put("id", messageId)
        .put("chunkHashes", JSONArray(hashes))
        .put("chunkCount", chunkCount)
        .put("finalMime", mime)
        .put("finalSizeBytes", declaredSize)
        .put("assembledSha256", assembledSha)
        .put("timestamp", 1_800_000_000_000L)

    private fun chunk(imageId: String = "img-1", index: Int, bytes: ByteArray, v: Int = 2) =
        JSONObject().put("type", "image_chunk").put("v", v)
            .put("imageId", imageId).put("chunkIndex", index).put("data", b64(bytes))

    private fun deliver(content: JSONObject) =
        h.deliver(streamId, StreamConstants.P_MESSAGES, content, peer)

    private fun bubble(id: String = "m-1") = manager.messages.value.find { it.id == id }

    private val one = "hello".toByteArray()
    private val two = "world!".toByteArray()

    @Test
    fun `manifest then chunks assembles the image`() {
        deliver(manifest(chunks = listOf(one, two)))
        deliver(chunk(index = 0, bytes = one))
        deliver(chunk(index = 1, bytes = two))
        assertEquals("helloworld!", String(bubble()!!.imageBytes!!))
        assertEquals("image/jpeg", bubble()!!.imageMime)
    }

    /** Chunks routinely arrive before their manifest on a resend. */
    @Test
    fun `chunks before the manifest still assemble`() {
        deliver(chunk(index = 0, bytes = one))
        deliver(chunk(index = 1, bytes = two))
        deliver(manifest(chunks = listOf(one, two)))
        assertEquals("helloworld!", String(bubble()!!.imageBytes!!))
    }

    @Test
    fun `the manifest paints a placeholder before any chunk arrives`() {
        deliver(manifest(chunks = listOf(one, two)))
        assertTrue(bubble()!!.isImage)
        assertEquals("img-1", bubble()!!.imageId)
        assertNull(bubble()!!.imageBytes)
    }

    @Test
    fun `a chunk whose hash does not match never assembles`() {
        deliver(manifest(chunks = listOf(one, two), hashes = listOf(sha256(one), sha256("tampered".toByteArray()))))
        deliver(chunk(index = 0, bytes = one))
        deliver(chunk(index = 1, bytes = two))
        assertNull(bubble()!!.imageBytes)
    }

    @Test
    fun `a wrong assembled digest never assembles`() {
        deliver(manifest(chunks = listOf(one, two), assembledSha = sha256("something else".toByteArray())))
        deliver(chunk(index = 0, bytes = one))
        deliver(chunk(index = 1, bytes = two))
        assertNull(bubble()!!.imageBytes)
    }

    @Test
    fun `a total that contradicts the declared size never assembles`() {
        deliver(manifest(chunks = listOf(one, two), declaredSize = 99))
        deliver(chunk(index = 0, bytes = one))
        deliver(chunk(index = 1, bytes = two))
        assertNull(bubble()!!.imageBytes)
    }

    @Test
    fun `a manifest whose hash count contradicts its chunk count is refused`() {
        deliver(manifest(chunks = listOf(one, two), chunkCount = 3))
        assertNull(bubble())
    }

    @Test
    fun `a manifest over the chunk ceiling is refused`() {
        deliver(manifest(chunks = listOf(one), chunkCount = ChannelManager.MAX_CHUNKS + 1,
            hashes = List(ChannelManager.MAX_CHUNKS + 1) { sha256(one) }))
        assertNull(bubble())
    }

    @Test
    fun `a manifest over the size ceiling for its mime is refused`() {
        deliver(manifest(chunks = listOf(one), declaredSize = ChannelManager.IMAGE_MAX_ASSEMBLED_BYTES + 1))
        assertNull(bubble())
    }

    /** A GIF is allowed to be bigger than a still image, and only a GIF. */
    @Test
    fun `a gif gets the larger ceiling`() {
        val big = ChannelManager.IMAGE_MAX_ASSEMBLED_BYTES + 1
        deliver(manifest(imageId = "gif-1", messageId = "m-gif", chunks = listOf(one),
            declaredSize = big, mime = "image/gif"))
        assertTrue(bubble("m-gif")!!.isImage)
    }

    @Test
    fun `a manifest with no declared size is refused`() {
        deliver(manifest(chunks = listOf(one), declaredSize = 0))
        assertNull(bubble())
    }

    @Test
    fun `a manifest that is not the chunked v2 transport is ignored`() {
        deliver(manifest(chunks = listOf(one), transport = "inline"))
        assertNull(bubble())
        deliver(manifest(chunks = listOf(one), v = 1))
        assertNull(bubble())
    }

    @Test
    fun `an oversized chunk is dropped`() {
        val huge = ByteArray(ChannelManager.MAX_CHUNK_BYTES + 1) { 7 }
        deliver(manifest(chunks = listOf(huge)))
        deliver(chunk(index = 0, bytes = huge))
        assertNull(bubble()!!.imageBytes)
    }

    @Test
    fun `a chunk index beyond the ceiling is dropped`() {
        deliver(manifest(chunks = listOf(one, two)))
        deliver(chunk(index = ChannelManager.MAX_CHUNKS, bytes = two))
        deliver(chunk(index = 0, bytes = one))
        assertNull(bubble()!!.imageBytes)
    }

    @Test
    fun `a chunk that is not v2 is ignored`() {
        deliver(manifest(chunks = listOf(one, two)))
        deliver(chunk(index = 0, bytes = one, v = 1))
        deliver(chunk(index = 1, bytes = two))
        assertNull(bubble()!!.imageBytes)
    }

    @Test
    fun `a second manifest for the same message does not duplicate the bubble`() {
        deliver(manifest(chunks = listOf(one, two)))
        deliver(manifest(chunks = listOf(one, two)))
        assertEquals(1, manager.messages.value.count { it.id == "m-1" })
    }
}
