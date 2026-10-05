package com.pombo.android.core

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Images are kept per account: blob sync pushes every unsynced record to the
 * active account, so one account must never read another's ledger.
 */
class ImageBlobStoreTest {

    private val filesDir: File = Files.createTempDirectory("pombo-blobs").toFile()
    private val alice = "0xAAAA000000000000000000000000000000000001"
    private val bob = "0xbbbb000000000000000000000000000000000002"

    @After fun tearDown() { filesDir.deleteRecursively() }

    private fun store(scope: String? = alice, guest: Boolean = false) =
        ImageBlobStore(mockk<Context>().also { every { it.filesDir } returns filesDir }).apply {
            scopeAddress = scope
            memoryOnly = guest
        }

    private fun allFiles() = filesDir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `one account never sees another account's images`() = runBlocking {
        val s = store(alice)
        s.save("img-a", "room-1", "data:a", synced = false)

        s.scopeAddress = bob
        assertTrue(s.unsynced().isEmpty())
        assertTrue(s.allRecords().isEmpty())
        assertNull(s.load("img-a"))
        s.save("img-b", "room-2", "data:b", synced = false)

        s.scopeAddress = alice
        assertEquals(listOf("img-a"), s.unsynced().map { it.imageId })
        assertEquals("data:a", s.load("img-a"))
    }

    @Test
    fun `an account's images survive a restart, under that account only`() = runBlocking {
        store(alice).save("img-a", "room-1", "data:a", synced = true)

        val restarted = store(alice.lowercase())
        assertEquals("data:a", restarted.load("img-a"))
        assertTrue(restarted.allRecords().single().synced)
        assertNull(store(bob).load("img-a"))
    }

    @Test
    fun `a guest keeps images for the session and writes nothing to disk`() = runBlocking {
        val s = store(scope = null, guest = true)
        s.save("img-g", "room-1", "data:g", synced = false)

        assertEquals("data:g", s.load("img-g"))
        assertTrue(allFiles().isEmpty())
        s.scopeAddress = alice
        assertNull(s.load("img-g"))
    }

    @Test
    fun `without an account nothing reaches the disk`() = runBlocking {
        store(scope = null).save("img-x", "room-1", "data:x", synced = false)

        assertTrue(allFiles().isEmpty())
    }

    @Test
    fun `deleting an account erases its images and leaves the others`() = runBlocking {
        store(bob).save("img-b", "room-2", "data:b", synced = false)
        val s = store(alice)
        s.save("img-a", "room-1", "data:a", synced = false)

        s.clearAccount()

        assertNull(s.load("img-a"))
        assertTrue(store(alice).allRecords().isEmpty())
        assertEquals("data:b", store(bob).load("img-b"))
    }

    @Test
    fun `leaving a conversation drops its images in this account only`() = runBlocking {
        val s = store(alice)
        s.save("img-1", "room-1", "data:1", synced = false)
        s.save("img-2", "room-2", "data:2", synced = false)

        s.clearForStream("room-1")

        assertNull(s.load("img-1"))
        assertEquals("data:2", s.load("img-2"))
        assertFalse(store(alice).allRecords().any { it.imageId == "img-1" })
    }

    @Test
    fun `the device-wide store is dropped, and the accounts' stores are not`() = runBlocking {
        store(alice).save("img-a", "room-1", "data:a", synced = false)
        File(filesDir, "image-blobs").apply { mkdirs() }.resolve("old-img").writeText("data:old")
        File(filesDir, "image-ledger.json").writeText("""[{"imageId":"old-img","streamId":"room-1","synced":false}]""")

        val restarted = store(alice)
        assertTrue(restarted.unsynced().none { it.imageId == "old-img" })

        assertFalse(File(filesDir, "image-blobs").exists())
        assertFalse(File(filesDir, "image-ledger.json").exists())
        assertEquals("data:a", restarted.load("img-a"))
    }

    @Test
    fun `account deletion clears the images before the account's scope goes`() {
        val vm = File("src/main/java/com/pombo/android/AppViewModel.kt").readText()
        val body = vm.substring(vm.indexOf("fun deleteAccount()"), vm.indexOf("fun blockPeer("))
        val clear = body.indexOf("blobStore.clearAccount()")
        assertTrue("deleteAccount no longer clears the image store", clear >= 0)
        assertTrue("the image store must be cleared before disconnect()", clear < body.lastIndexOf("disconnect()"))
    }
}
