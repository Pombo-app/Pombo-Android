package com.pombo.android

import android.content.Context
import com.pombo.android.bridge.PomboBridge
import com.pombo.android.core.ImageBlobStore
import com.pombo.android.core.SyncChunks
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A pushed image counts as synced only once storage is seen to hold every row
 * of it; a push storage could not tell about is read back before the image is
 * sent again.
 */
class SyncBlobConfirmTest {

    private val dir = kotlin.io.path.createTempDirectory("blobs").toFile()
    private val context: Context = mockk { every { filesDir } returns dir }
    private val blobStore = ImageBlobStore(context).apply { scopeAddress = ME }
    private val bridge: PomboBridge = mockk(relaxed = true)
    private val store: SyncStore = mockk(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val stored = CopyOnWriteArrayList<String>()
    private val published = CopyOnWriteArrayList<String>()
    private val left = CopyOnWriteArrayList<Int>()
    @Volatile private var storageKeeps = true
    @Volatile private var storageAnswers = true
    @Volatile private var onPublish: (List<ImageBlobStore.Record>) -> Unit = {}
    private lateinit var sync: SyncManager

    /** Large enough to travel as chunks and a manifest. */
    private val bigImage = "data:image/jpeg;base64," + "A".repeat(SyncChunks.CHUNK_CHARS * 2 + 10)

    @Before
    fun setUp() {
        every { store.pendingPush } returns null
        coEvery { bridge.call(any(), any(), any()) } answers {
            val args = secondArg<JSONObject>()
            when (firstArg<String>()) {
                "getPeerPublicKey" -> JSONObject().put("publicKey", "0x02aa")
                "publishAs" -> {
                    onPublish(blobStore.allRecords())
                    val publisher = com.pombo.android.core.EthereumSigner.address(args.getString("privateKey")).lowercase()
                    published.add(publisher)
                    stored.add(publisher)
                    JSONObject().put("ok", true).put("timestamp", 5000L).put("publisherId", publisher)
                }
                "resendEnvelopes" -> {
                    if (!storageAnswers) throw IllegalStateException("bridge call timed out")
                    val held = JSONArray()
                    if (storageKeeps) stored.forEach { held.put(JSONObject().put("timestamp", 5000L).put("publisherId", it)) }
                    JSONObject().put("rows", held)
                }
                "leaveStreamPart" -> { left.add(args.optInt("partition")); JSONObject().put("ok", true) }
                else -> JSONObject()
            }
        }
        sync = SyncManager(
            bridge, scope, store, blobStore,
            myAddress = { ME }, myPrivateKey = { PK }, isGuest = { false },
            exportLocal = { JSONObject() }, importMerged = {},
            confirmAtMs = longArrayOf(10L, 20L, 30L, 40L)
        )
        runBlocking { blobStore.save("img-1", "$ME/room-1", bigImage, synced = false) }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 3_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(5)
    }

    private fun record() = blobStore.allRecords().single()

    private fun push() = runBlocking { sync.pushImageBlobs() }

    @Test
    fun `a blob is synced once storage holds every row of it, and its rows were recorded before the first went out`() {
        var atFirstPublish: ImageBlobStore.Record? = null
        onPublish = { if (atFirstPublish == null) atFirstPublish = it.single() }
        push()
        waitFor { left.isNotEmpty() }

        assertEquals(stored.toList(), atFirstPublish!!.pendingPublishers)
        assertTrue(record().synced)
        assertTrue(record().pendingPublishers.isEmpty())
        assertEquals(listOf(2), left.toList())
    }

    @Test
    fun `a blob storage answered without is sent again on the next push`() {
        storageKeeps = false
        push()
        waitFor { left.isNotEmpty() }

        assertFalse(record().synced)
        assertTrue(record().pendingPublishers.isEmpty())
        published.clear()
        push()
        assertTrue(published.isNotEmpty())
    }

    @Test
    fun `a blob storage never answered about is read back instead of sent again`() {
        storageAnswers = false
        push()
        waitFor { left.isNotEmpty() }

        assertFalse(record().synced)
        assertTrue(record().pendingPublishers.isNotEmpty())
        published.clear()
        push()
        assertTrue(published.isEmpty())

        storageAnswers = true
        push()
        assertTrue(published.isEmpty())
        assertTrue(record().synced)
    }

    @Test
    fun `a blob whose publish failed after its rows landed is not sent again`() {
        val rows = (bigImage.length + SyncChunks.CHUNK_CHARS - 1) / SyncChunks.CHUNK_CHARS + 1
        coEvery { bridge.call("publishAs", any(), any()) } answers {
            val publisher = com.pombo.android.core.EthereumSigner.address(secondArg<JSONObject>().getString("privateKey")).lowercase()
            stored.add(publisher)
            published.add(publisher)
            if (published.size == rows) throw IllegalStateException("bridge call timed out")
            JSONObject().put("ok", true).put("timestamp", 5000L).put("publisherId", publisher)
        }
        push()

        assertFalse(record().synced)
        assertEquals(published.toList(), record().pendingPublishers)
        published.clear()
        push()
        assertTrue(published.isEmpty())
        assertTrue(record().synced)
    }

    @Test
    fun `a pending blob survives a restart`() {
        storageAnswers = false
        push()
        waitFor { left.isNotEmpty() }
        val pending = record().pendingPublishers

        val reopened = ImageBlobStore(context).apply { scopeAddress = ME }
        val again = reopened.allRecords().single()
        assertFalse(again.synced)
        assertEquals(pending, again.pendingPublishers)
        assertTrue(again in reopened.unsynced())
    }

    private companion object {
        const val PK = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
        const val ME = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8"
    }
}
