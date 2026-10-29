package com.pombo.android.core

import com.pombo.android.bridge.PomboBridge
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A file shared in a DM keeps serving after a restart, once the DM is opened. */
class MediaControllerDmSeedTest {

    private val me = "0xaaaa000000000000000000000000000000000001"
    private val peerInbox = "0xbbbb000000000000000000000000000000000002/Pombo-DM-1"
    private val dir: File = Files.createTempDirectory("pombo-dm-seed").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun controller(bridge: PomboBridge = mockk(relaxed = true)) = MediaController(
        bridge = bridge,
        scope = scope,
        myAddress = { me },
        transferDir = { dir },
        transport = mockk(relaxed = true)
    )

    @After fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `a DM seed from an earlier session serves again on our own inbox`() = runBlocking {
        val bytes = ByteArray(1024) { it.toByte() }
        val store = PieceStore.open(dir, "dm-file", bytes.size.toLong())
        store.writePiece(0, bytes, null)
        val metadata = MediaController.FileMetadata(
            "dm-file", "clip.mp4", bytes.size.toLong(), "video/mp4", 1, listOf(PieceStore.sha256Hex(bytes))
        )
        controller().seedSentFile(metadata, peerInbox, true, null, store)
        store.close()

        val bridge = mockk<PomboBridge>(relaxed = true)
        val restarted = controller(bridge)
        restarted.restoreSeedsFor(peerInbox, null)
        restarted.reannounceForChannel(peerInbox, null)

        assertTrue(restarted.isSeedingFile("dm-file"))
        assertNotNull(restarted.localFile("dm-file"))
        coVerify {
            bridge.call("subscribe", match { it.optString("streamId") == "$me/Pombo-DM-2" }, any())
        }
    }
}
