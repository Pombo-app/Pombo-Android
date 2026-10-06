package com.pombo.android.core

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferDirsTest {

    private val root: File = Files.createTempDirectory("pombo-transfer-dirs").toFile()
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val context = mockk<Context>().also { every { it.filesDir } returns filesDir }
    private val alice = "0xAAAA000000000000000000000000000000000001"
    private val bob = "0xbbbb000000000000000000000000000000000002"

    @After fun tearDown() { root.deleteRecursively() }

    @Test
    fun `each account has its own directory, and a guest one per session`() {
        val a = TransferDirs.forScope(context, alice, guest = false, session = null)
        val b = TransferDirs.forScope(context, bob, guest = false, session = null)

        assertEquals(File(File(filesDir, "transfers-v2"), alice.lowercase()), a)
        assertNotEquals(a, b)
        val guests = File(filesDir, "transfers-guest")
        val guest1 = TransferDirs.forScope(context, null, guest = true, session = "0xG1")
        val guest2 = TransferDirs.forScope(context, null, guest = true, session = "0xG2")
        assertEquals(guests, guest1.parentFile)
        assertNotEquals(guest1, guest2)
        assertEquals(guests, TransferDirs.forScope(context, null, guest = false, session = null).parentFile)
    }

    @Test
    fun `the sweep drops the device-wide layout and other guest sessions, never an account's files`() {
        File(filesDir, "transfers").apply { mkdirs() }.resolve("old.part").writeText("old")
        val account = TransferDirs.forScope(context, alice, guest = false, session = null).apply { mkdirs() }
        account.resolve("mine.part").writeText("mine")
        val stale = TransferDirs.forScope(context, null, guest = true, session = "0xOld").apply { mkdirs() }
        stale.resolve("x.part").writeText("x")
        val current = TransferDirs.forScope(context, null, guest = true, session = "0xNow").apply { mkdirs() }
        current.resolve("y.part").writeText("y")

        TransferDirs.sweep(context, current)

        assertFalse(File(filesDir, "transfers").exists())
        assertFalse(stale.exists())
        assertTrue(current.resolve("y.part").exists())
        assertTrue(account.resolve("mine.part").exists())
    }
}
