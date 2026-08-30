package com.pombo.android

import com.pombo.android.core.StreamConstants
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * File announcements: P2P shares (`file_announce`) and Persistent File Sharing
 * (`storage_file_announce`). Neither carries the bytes — the announcement only
 * describes the file, so an announcement that cannot be verified must become
 * nothing at all rather than a bubble that offers an unverifiable download.
 */
class FileAnnounceTest {

    private val owner = "0xowner"
    private val streamId = "$owner/files-1"
    private val room = ChannelManagerHarness.channel(streamId)
    private val h = ChannelManagerHarness(channels = listOf(room))
    private val manager = h.manager
    private val peer = "0xpeer"

    @Before fun setUp() = manager.openChannel(streamId)
    @After fun tearDown() = h.stop()

    private fun deliver(content: JSONObject) =
        h.deliver(streamId, StreamConstants.P_MESSAGES, content, peer)

    private fun bubble(id: String) = manager.messages.value.find { it.id == id }

    private fun fileAnnounce(
        id: String = "f-1",
        fileId: String = "file-1",
        fileSize: Long = 1000,
        hashes: List<String> = listOf("aa"),
        pieceCount: Int? = null,
        fileName: String = "notes.txt"
    ) = JSONObject()
        .put("type", "file_announce").put("id", id)
        .put("timestamp", 1_800_000_000_000L)
        .put("metadata", JSONObject()
            .put("fileId", fileId)
            .put("fileName", fileName)
            .put("fileSize", fileSize)
            .put("fileType", "text/plain")
            .put("pieceCount", pieceCount ?: hashes.size)
            .put("pieceHashes", JSONArray(hashes)))

    private fun storageAnnounce(
        id: String = "s-1",
        transferId: String = "t-1",
        originalSize: Long = 2048,
        fileName: String = "report.pdf"
    ) = JSONObject()
        .put("type", "storage_file_announce").put("id", id)
        .put("timestamp", 1_800_000_000_000L)
        .put("metadata", JSONObject()
            .put("transferId", transferId)
            .put("fileName", fileName)
            .put("fileType", "application/pdf")
            .put("originalSize", originalSize)
            .put("compression", "deflate")
            .put("totalChunks", 2))

    @Test
    fun `a file announce becomes a bubble carrying the metadata`() {
        deliver(fileAnnounce())
        val m = bubble("f-1")!!
        assertEquals("notes.txt", m.file!!.fileName)
        assertEquals(1000L, m.file!!.fileSize)
        assertEquals(1, m.file!!.pieceCount)
        assertEquals("", m.text)
    }

    /** One hash per piece or nothing: without them a download cannot be checked. */
    @Test
    fun `a file announce short of piece hashes is dropped`() {
        deliver(fileAnnounce(hashes = emptyList(), pieceCount = 1))
        assertNull(bubble("f-1"))
    }

    @Test
    fun `a file announce whose piece count contradicts its size is dropped`() {
        deliver(fileAnnounce(fileSize = 1000, hashes = listOf("aa", "bb")))
        assertNull(bubble("f-1"))
    }

    @Test
    fun `a file announce with no size is dropped`() {
        deliver(fileAnnounce(fileSize = 0))
        assertNull(bubble("f-1"))
    }

    @Test
    fun `a file announce with no id is dropped`() {
        deliver(fileAnnounce(id = ""))
        assertTrue(manager.messages.value.none { it.file != null })
    }

    @Test
    fun `a storage announce becomes a bubble carrying the metadata`() {
        deliver(storageAnnounce())
        val m = bubble("s-1")!!
        assertEquals("report.pdf", m.storageFile!!.fileName)
        assertEquals(2048L, m.storageFile!!.originalSize)
        assertEquals("deflate", m.storageFile!!.compression)
        assertEquals(2, m.storageFile!!.totalChunks)
    }

    @Test
    fun `a storage announce with no transfer id is dropped`() {
        deliver(storageAnnounce(transferId = ""))
        assertNull(bubble("s-1"))
    }

    @Test
    fun `a storage announce with no size is dropped`() {
        deliver(storageAnnounce(originalSize = 0))
        assertNull(bubble("s-1"))
    }

    /** Our own echo would double the optimistic bubble; the id guards it. */
    @Test
    fun `a repeated announce does not double the bubble`() {
        deliver(storageAnnounce())
        deliver(storageAnnounce(fileName = "renamed.pdf"))
        assertEquals(1, manager.messages.value.count { it.id == "s-1" })
        assertEquals("report.pdf", bubble("s-1")!!.storageFile!!.fileName)
    }

    @Test
    fun `no transfer is live before one is asked for`() {
        deliver(storageAnnounce())
        assertNotNull(bubble("s-1"))
        assertTrue(manager.activeStorageTransferStreams().isEmpty())
        assertNull(manager.storageTransferInfo("t-1"))
        assertTrue(manager.storageTransferPhase.value.isEmpty())
    }

    /** Acting on a transfer nobody started must stay a no-op, not a crash. */
    @Test
    fun `pausing and cancelling an unknown transfer changes nothing`() {
        manager.pauseStorageTransfer("nope")
        manager.cancelStorageTransfer("nope")
        manager.resumeStorageTransfer("nope")
        assertTrue(manager.storageTransferPhase.value.isEmpty())
        assertTrue(manager.activeStorageTransferStreams().isEmpty())
    }
}
