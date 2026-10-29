package com.pombo.android.core

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StorageLocalFilesTest {

    private lateinit var dir: File
    private lateinit var files: StorageLocalFiles

    @Before
    fun setUp() {
        dir = File.createTempFile("storagelocal", null).apply { delete(); mkdirs() }
        files = StorageLocalFiles { dir }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun download(tid: String) = File(dir, "psf_$tid.spart").apply { writeText("bytes") }

    @Test
    fun `a finished download is found again by a fresh instance`() {
        val file = download("t1")
        files.recordReceived("t1", file)

        assertEquals(file, StorageLocalFiles { dir }.received("t1"))
    }

    @Test
    fun `a download deleted from disk is not offered`() {
        val file = download("t1")
        files.recordReceived("t1", file)
        file.delete()

        assertNull(files.received("t1"))
    }

    @Test
    fun `a sent original survives a fresh instance`() {
        files.recordSent("t2", "content://docs/video.mp4")

        assertEquals("content://docs/video.mp4", StorageLocalFiles { dir }.sent("t2"))
    }

    @Test
    fun `expiry deletes downloads and hands back originals to release`() {
        val old = System.currentTimeMillis() - SeedRegistry.EXPIRE_MS - 1
        val file = download("t1")
        files.recordReceived("t1", file, now = old)
        files.recordSent("t2", "content://docs/video.mp4", now = old)
        files.recordSent("t3", "content://docs/fresh.mp4")

        val released = files.sweepExpired()

        assertEquals(listOf("content://docs/video.mp4"), released)
        assertFalse(file.exists())
        assertNull(files.received("t1"))
        assertNull(files.sent("t2"))
        assertEquals("content://docs/fresh.mp4", files.sent("t3"))
    }

    @Test
    fun `reload reads the directory the provider points at now`() {
        files.recordSent("t2", "content://docs/video.mp4")
        val other = File.createTempFile("storagelocal2", null).apply { delete(); mkdirs() }
        try {
            var current = dir
            val scoped = StorageLocalFiles { current }
            assertTrue(scoped.sent("t2") != null)
            current = other
            scoped.reload()
            assertNull(scoped.sent("t2"))
        } finally {
            other.deleteRecursively()
        }
    }
}
