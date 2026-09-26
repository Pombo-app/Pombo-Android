package com.pombo.android

import com.pombo.android.bridge.PomboBridge
import com.pombo.android.core.ImageBlobStore
import com.pombo.android.data.SyncMode
import com.pombo.android.data.SyncStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sync partition's overlay lives only while publishing, a confirmed state
 * is not published twice, and foreground checks read one row over HTTP
 * (web syncManager.js: _confirmPush, checkForNewSnapshot).
 */
class SyncManagerLightTest {

    @Volatile private var dirty = false
    private var confirmedHash: String? = null
    private var confirmedAt = 0L
    @Volatile private var rows = JSONArray()
    @Volatile private var resendMs = 0L
    @Volatile private var onPublish: () -> Unit = {}
    private var mode = SyncMode.AUTOMATIC
    private val calls = CopyOnWriteArrayList<String>()
    private val left = CopyOnWriteArrayList<Int>()

    private val bridge: PomboBridge = mockk(relaxed = true)
    private val store: SyncStore = mockk(relaxed = true)
    private val blobStore: ImageBlobStore = mockk(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var sync: SyncManager

    private fun row(timestamp: Long, publisher: String) = JSONObject()
        .put("content", JSONObject())
        .put("meta", JSONObject().put("timestamp", timestamp).put("publisherId", publisher))

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 3_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
    }

    @Before
    fun setUp() {
        every { store.dirty } answers { dirty }
        every { store.dirty = any() } answers { dirty = firstArg() }
        every { store.confirmedHash } answers { confirmedHash }
        every { store.confirmedHash = any() } answers { confirmedHash = firstArg() }
        every { store.confirmedAt } answers { confirmedAt }
        every { store.confirmedAt = any() } answers { confirmedAt = firstArg() }
        every { store.appliedTs() } returns emptySet()
        every { blobStore.unsynced() } returns emptyList()
        coEvery { bridge.call(any(), any(), any()) } answers {
            val method = firstArg<String>()
            val args = secondArg<JSONObject>()
            calls.add(method)
            when (method) {
                "getPeerPublicKey" -> JSONObject().put("publicKey", "0x02aa")
                "publishAs" -> {
                    onPublish()
                    JSONObject().put("ok", true).put("timestamp", 5000L).put("publisherId", "0xEph")
                }
                "resend" -> {
                    if (resendMs > 0) Thread.sleep(resendMs)
                    JSONObject().put("messages", rows)
                }
                "leaveStreamPart" -> { left.add(args.optInt("partition")); JSONObject().put("ok", true) }
                else -> JSONObject()
            }
        }
        sync = SyncManager(
            bridge, scope, store, blobStore,
            myAddress = { ME }, myPrivateKey = { PK }, isGuest = { false },
            exportLocal = { JSONObject("""{"channels":[],"username":"Bob","sliceTs":{"username":1}}""") },
            importMerged = {}, syncMode = { mode },
            checkIntervalMs = 50L, confirmAtMs = longArrayOf(10L, 20L, 30L, 40L), blobLeaveAfterMs = 10L
        )
    }

    @Test
    fun `a confirmed push leaves the sync partition and the same state is not published again`() = runBlocking {
        rows = JSONArray().put(row(5000, "0xeph"))
        sync.pushSync()
        waitFor { left.isNotEmpty() }

        assertEquals(listOf(1), left.toList())
        assertNotNull(confirmedHash)
        calls.clear()
        assertNull(sync.pushSync())
        assertFalse("publishAs" in calls)
    }

    @Test
    fun `the same state is not published again while its read-back runs`() = runBlocking {
        rows = JSONArray().put(row(5000, "0xeph"))
        resendMs = 300L
        sync.pushSync()
        calls.clear()

        assertNull(sync.pushSync())
        assertFalse("publishAs" in calls)
    }

    @Test
    fun `an auto push clears the dirty flag`() {
        rows = JSONArray().put(row(5000, "0xeph"))
        sync.scheduleAutoPush(10L)
        waitFor { "publishAs" in calls && !dirty }

        assertTrue("publishAs" in calls)
        assertFalse(dirty)
    }

    @Test
    fun `a change signalled during a push keeps the dirty flag`() = runBlocking {
        rows = JSONArray().put(row(5000, "0xeph"))
        onPublish = { sync.scheduleAutoPush(60_000L) }
        sync.pushSync()
        sync.cancelAutoPush()

        assertTrue(dirty)
    }

    @Test
    fun `an unconfirmed push leaves too and its state is sent again`() = runBlocking {
        rows = JSONArray()
        sync.pushSync()
        waitFor { left.isNotEmpty() }

        assertTrue(dirty)
        assertNull(confirmedHash)
        calls.clear()
        sync.pushSync()
        assertTrue("publishAs" in calls)
    }

    @Test
    fun `a check pulls only a newer push from another device`() = runBlocking {
        rows = JSONArray().put(row(7000, "0xother"))
        sync.pullSync()
        calls.clear()
        assertEquals(0, sync.checkForNewSnapshot())
        assertEquals(1, calls.count { it == "resend" })

        rows = JSONArray().put(row(8000, "0xother"))
        calls.clear()
        sync.checkForNewSnapshot()
        assertEquals(2, calls.count { it == "resend" })
    }

    @Test
    fun `a check does not pull this device's own push back`() = runBlocking {
        rows = JSONArray().put(row(5000, "0xeph"))
        sync.pushSync()
        waitFor { left.isNotEmpty() }
        calls.clear()

        assertEquals(0, sync.checkForNewSnapshot())
        assertEquals(1, calls.count { it == "resend" })
    }

    @Test
    fun `foreground runs are no longer throttled, and manual only checks nothing`() = runBlocking {
        rows = JSONArray().put(row(7000, "0xother"))
        sync.autoSync()
        sync.autoSync()
        assertEquals(3, calls.count { it == "resend" })

        mode = SyncMode.MANUAL_ONLY
        calls.clear()
        sync.autoSync()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `the watch checks until stopped`() {
        rows = JSONArray().put(row(7000, "0xother"))
        sync.startSnapshotWatch()
        waitFor { calls.count { it == "resend" } >= 3 }
        sync.stopSnapshotWatch()
        val seen = calls.count { it == "resend" }
        Thread.sleep(200)

        assertTrue(seen >= 3)
        assertEquals(seen, calls.count { it == "resend" })
    }

    @Test
    fun `a slow check does not push the next one back`() {
        rows = JSONArray().put(row(7000, "0xother"))
        runBlocking { sync.pullSync() }
        calls.clear()
        resendMs = 40L
        sync.startSnapshotWatch()
        Thread.sleep(1_000)
        sync.stopSnapshotWatch()

        // A 50 ms period started from each check's start fits ~20 checks; counted from each end, ~11.
        assertTrue(calls.count { it == "resend" } >= 15)
    }

    private companion object {
        const val PK = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
        const val ME = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"
    }
}
