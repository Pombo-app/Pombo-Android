package com.pombo.android.core

import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An account switch drops every transfer: downloads lose their partial bytes
 * in the directory they were started in, even when the scope has already
 * moved on, and seeds stay with the account that owns them.
 */
class MediaControllerResetTest {

    private val root: File = Files.createTempDirectory("pombo-media-reset").toFile()
    private val dirA = File(root, "alice")
    private val dirB = File(root, "bob")
    private var current = dirA
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val media = MediaController(
        bridge = mockk(relaxed = true),
        scope = scope,
        myAddress = { "0xaaaa000000000000000000000000000000000001" },
        transferDir = { current },
        transport = mockk(relaxed = true)
    )

    @After fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    private fun metadata(fileId: String) =
        MediaController.FileMetadata(fileId, "$fileId.bin", 1024, "application/octet-stream", 1, listOf("00"))

    private fun filesIn(dir: File) = dir.walkTopDown().filter { it.isFile }.map { it.name }.toList()

    @Test
    fun `a dropped download loses its partial bytes in the old account's directory`() {
        media.startDownload("0xaaaa000000000000000000000000000000000001/room-1", metadata("file-a"), null, false)
        assertTrue(File(dirA, "file-a.part").exists())

        current = dirB
        media.reset(dirA)

        assertFalse(File(dirA, "file-a.part").exists())
        assertFalse(File(dirA, "file-a.bits").exists())
        assertTrue(filesIn(dirB).isEmpty())
        assertTrue(media.progress.value.isEmpty())
    }

    @Test
    fun `seeds stop serving on a switch and come back with their account only`() = runBlocking {
        val store = PieceStore.open(dirA, "seed-a", 1024)
        media.seedSentFile(metadata("seed-a"), "0xaaaa000000000000000000000000000000000001/room-1", false, null, store)
        assertTrue(media.isSeedingFile("seed-a"))

        current = dirB
        media.reset(dirA)
        assertFalse(media.isSeedingFile("seed-a"))
        assertTrue(media.inactiveSeeds().isEmpty())

        current = dirA
        media.reset(dirB)
        assertEquals(listOf("seed-a"), media.inactiveSeeds().map { it.fileId })
        assertTrue(File(dirA, "seed-a.part").exists())
    }
}
