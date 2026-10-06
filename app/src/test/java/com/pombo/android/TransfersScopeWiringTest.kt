package com.pombo.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source guard: AppViewModel cannot be instantiated in a JVM test.
 *
 * Transfers are reset while the directory still points at the account being
 * left, and every send is cancelled, before the directory moves: a cancelled
 * Job unwinds later, and anything that resolves the directory then would
 * delete, or file, under the next account.
 */
class TransfersScopeWiringTest {

    private val vm = File("src/main/java/com/pombo/android/AppViewModel.kt").readText()
    private val files = File("src/main/java/com/pombo/android/core/channels/FileTransfers.kt").readText()
    private val storage = File("src/main/java/com/pombo/android/core/StorageMedia.kt").readText()

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature is gone", start >= 0)
        val end = source.indexOf("\n    fun ", start + 1).let { if (it < 0) source.length else it }
        return source.substring(start, end)
    }

    @Test
    fun `every scope change resets transfers before anything else moves`() {
        val apply = body(vm, "private fun applyStorageScope(")
        assertTrue(
            "applyStorageScope must start by moving the transfers",
            Regex("""private fun applyStorageScope\([^)]*\) \{\s*moveTransfers\(address, guest\)""").containsMatchIn(apply)
        )
        val move = body(vm, "private fun moveTransfers(")
        val reset = move.indexOf("manager.resetTransfers()")
        val cancel = move.indexOf("transferSends.toList().forEach { it.cancel() }")
        val moved = move.indexOf("manager.transferDir = next")
        assertTrue("moveTransfers no longer resets", reset >= 0)
        assertTrue("sends must be cancelled after the reset", cancel > reset)
        assertTrue("the directory must move last", moved > cancel)
    }

    @Test
    fun `every send can be cancelled by an account switch`() {
        listOf("fun sendFile(uri", "fun sendVideo(uri", "fun sendStorageFile(uri").forEach {
            assertTrue("$it is not tracked", Regex(Regex.escape(it) + """: android\.net\.Uri\) = trackSend\(viewModelScope\.launch \{""").containsMatchIn(vm))
        }
    }

    @Test
    fun `deleting an account erases its transfers before disconnect`() {
        val delete = body(vm, "fun deleteAccount()")
        val clear = delete.indexOf("manager.clearAccountTransfers()")
        assertTrue("deleteAccount no longer erases transfers", clear >= 0)
        assertTrue(clear < delete.lastIndexOf("disconnect()"))
    }

    @Test
    fun `cleanup after a reset uses the directory captured before the scope moved`() {
        val reset = body(files, "fun resetTransfers(dir: java.io.File)")
        assertTrue(reset.contains("media.reset(dir)"))
        assertTrue(reset.contains("StorageStagingStore.delete(dir, it)"))
        assertTrue("resetTransfers must not resolve the directory itself", !reset.contains("transferDir"))

        val download = body(storage, "suspend fun downloadFile(")
        assertTrue(download.contains("val dir = transferDir()"))
        assertTrue("a download assembles where it started", download.contains("assemble(staging, dir, tid, isCompressed)"))
        assertTrue("assemble resolves the directory itself", !body(storage, "private fun assemble(").contains("transferDir()"))
    }
}
