package com.pombo.android.core

import android.content.Context
import com.pombo.android.core.LatestMessageStore.Preview
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Previews are kept per account: a DM's key is the peer's inbox, so two
 * accounts talking to the same peer share the key, and previews hold
 * decrypted text.
 */
class LatestMessageStoreTest {

    private val filesDir: File = Files.createTempDirectory("pombo-previews").toFile()
    private val alice = "0xAAAA000000000000000000000000000000000001"
    private val bob = "0xbbbb000000000000000000000000000000000002"
    private val dmWithCarol = "0xcccc000000000000000000000000000000000003/Pombo-DM-1"

    @After fun tearDown() { filesDir.deleteRecursively() }

    private fun store(scope: String? = alice, guest: Boolean = false) =
        LatestMessageStore(mockk<Context>().also { every { it.filesDir } returns filesDir }).apply {
            scopeAddress = scope
            memoryOnly = guest
        }

    private fun preview(text: String, ts: Long = 1_000L) = Preview("You", text, ts, alice.lowercase())

    private fun allFiles() = filesDir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun `two accounts talking to the same peer never see each other's preview`() = runBlocking {
        val s = store(alice)
        s.put(dmWithCarol, preview("alice's secret"))

        s.scopeAddress = bob
        s.warmUp()
        assertNull(s.cached(dmWithCarol))
        s.put(dmWithCarol, preview("bob's secret"))

        s.scopeAddress = alice
        s.warmUp()
        assertEquals("alice's secret", s.cached(dmWithCarol)?.text)
    }

    @Test
    fun `an account's previews survive a restart, under that account only`() = runBlocking {
        store(alice).put(dmWithCarol, preview("hello"))

        val restarted = store(alice.lowercase()).also { it.warmUp() }
        assertEquals("hello", restarted.cached(dmWithCarol)?.text)
        assertNull(store(bob).also { it.warmUp() }.cached(dmWithCarol))
    }

    @Test
    fun `a guest keeps previews for the session and writes nothing to disk`() = runBlocking {
        val s = store(scope = null, guest = true)
        s.put(dmWithCarol, preview("guest"))

        assertEquals("guest", s.cached(dmWithCarol)?.text)
        assertTrue(allFiles().isEmpty())
    }

    @Test
    fun `without an account nothing reaches the disk`() = runBlocking {
        store(scope = null).put(dmWithCarol, preview("nobody"))

        assertTrue(allFiles().isEmpty())
    }

    @Test
    fun `deleting an account erases its previews and leaves the others`() = runBlocking {
        store(bob).put(dmWithCarol, preview("bob"))
        val s = store(alice)
        s.put(dmWithCarol, preview("alice"))

        s.clearAccount()

        assertNull(s.cached(dmWithCarol))
        assertNull(store(alice).also { it.warmUp() }.cached(dmWithCarol))
        assertEquals("bob", store(bob).also { it.warmUp() }.cached(dmWithCarol)?.text)
    }

    @Test
    fun `the device-wide file is dropped, and the accounts' files are not`() = runBlocking {
        store(alice).put(dmWithCarol, preview("alice"))
        File(filesDir, "channel-previews.json").writeText("""{"$dmWithCarol":{"sender":"x","text":"old","ts":1}}""")

        val restarted = store(alice).also { it.warmUp() }

        assertFalse(File(filesDir, "channel-previews.json").exists())
        assertEquals("alice", restarted.cached(dmWithCarol)?.text)
    }

    @Test
    fun `a fetch that started under another account is dropped`() = runBlocking {
        val s = store(alice)
        val answer = CompletableDeferred<Preview?>()
        val fetch = async { s.dedup(dmWithCarol) { answer.await() } }
        yield()

        s.scopeAddress = bob
        answer.complete(preview("alice's secret"))
        fetch.await()

        assertNull(s.cached(dmWithCarol))
        assertTrue(allFiles().isEmpty())
    }

    @Test
    fun `every scope change re-scopes the previews and the cold start scopes them before loading`() {
        val vm = File("src/main/java/com/pombo/android/AppViewModel.kt").readText()
        val start = vm.indexOf("private fun applyStorageScope(")
        val apply = vm.substring(start, vm.indexOf("\n    init {", start))
        listOf("previewStore.scopeAddress = address", "previewStore.memoryOnly = guest", "previewStore.warmUp()").forEach {
            assertTrue("applyStorageScope no longer does $it", apply.contains(it))
        }
        val scoped = vm.indexOf("previewStore.scopeAddress = store.address")
        assertTrue("the cold start loads previews before scoping them", scoped in 0 until vm.lastIndexOf("previewStore.warmUp()"))

        val cm = File("src/main/java/com/pombo/android/ChannelManager.kt").readText()
        val scan = cm.substring(cm.indexOf("suspend fun scanChannelActivity("), cm.indexOf("suspend fun fetchChannelMeta("))
        val captured = scan.indexOf("previewStore.generation()")
        assertTrue("the activity scan no longer tags its preview with the scope it started in", captured >= 0)
        assertTrue(captured < scan.indexOf("previewStore.put(streamId, it, previewScope)"))
    }

    @Test
    fun `a scan put carrying an old scope is dropped`() = runBlocking {
        val s = store(alice)
        val startedAt = s.generation()

        s.scopeAddress = bob
        s.put(dmWithCarol, preview("alice's secret"), startedAt)

        assertNull(s.cached(dmWithCarol))
        assertTrue(allFiles().isEmpty())
    }
}
