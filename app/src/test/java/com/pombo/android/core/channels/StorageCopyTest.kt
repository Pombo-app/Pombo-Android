package com.pombo.android.core.channels

import com.pombo.android.core.StorageEndpoints
import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A storage provider added to a live channel gets what the channel cannot run
 * without published again by its owner, and is asked for those exact rows
 * until it holds them. Only what is still missing there is published again.
 */
class StorageCopyTest {

    private val owner = "0x" + "aa".repeat(20)
    private val stream = "$owner/room-1"
    private val admin = "$owner/room-3"
    private val keys = "$owner/room-4"
    private val newNode = "0x" + "bb".repeat(20)

    private inner class FakeHost : StorageCopy.Host {
        var channel = Channel(
            messageStreamId = stream, ephemeralStreamId = "$owner/room-2", adminStreamId = admin,
            keysStreamId = keys, name = "Room", type = "gated", gateAddress = "0x" + "cc".repeat(20)
        )
        var owns = true
        var adminRev = 4
        var image: JSONObject? = null
        var clock = 1000L
        var features = setOf("stored")
        /** Look-ups answered before the new provider shows up in the listing. */
        var unlistedLookups = 0
        /** `streamId|partition|timestamp` the new provider holds. */
        val held = HashSet<String>()
        var storesEverything = false
        var failAdminOnce = false
        val published = ArrayList<String>()
        val notices = ArrayList<StorageCopy.Notice>()
        val pendingStore = HashMap<String, List<String>>()

        private fun publish(item: String, streamId: String, partition: Int): Long {
            val ts = ++clock
            published += item
            if (storesEverything) held += "$streamId|$partition|$ts"
            return ts
        }

        override fun account() = owner
        override fun channel(messageStreamId: String) = channel.takeIf { it.messageStreamId == messageStreamId }
        override fun isOwner(channel: Channel) = owns
        override suspend fun ensureAdminLoaded(channel: Channel) {}
        override fun adminRev(channel: Channel) = adminRev
        override suspend fun readImage(channel: Channel) = image
        var onRepublishAnchors: () -> Unit = {}
        override suspend fun republishAnchors(channel: Channel): List<Long> {
            onRepublishAnchors()
            return listOf(publish("keys", keys, StreamConstants.P_KEY_EXCHANGE))
        }
        /** Rows the snapshot goes out as; above one it went out split. */
        var adminRows = 1
        /** Row of the next admin publish the provider does not keep, once. */
        var adminRowLost: Int? = null
        override suspend fun publishAdminState(channel: Channel): List<Long> {
            if (failAdminOnce) { failAdminOnce = false; error("channel not open") }
            val stamps = (0 until adminRows).map { publish("admin", admin, StreamConstants.ADMIN_MODERATION) }
            adminRowLost?.let { held -= "$admin|${StreamConstants.ADMIN_MODERATION}|${stamps[it]}" }
            adminRowLost = null
            return stamps
        }
        override suspend fun republishImage(channel: Channel, payload: JSONObject) =
            publish("image", admin, StreamConstants.ADMIN_CHANNEL_IMAGE)
        override suspend fun publishPasswordChallenge(channel: Channel) =
            publish("password", admin, StreamConstants.ADMIN_PASSWORD_CHALLENGE)
        var anchorKeyIds = listOf("7.cur")
        var providerList = listOf(StorageEndpoints.Node(newNode, listOf("https://new.example")))
        /** `url|streamId|partition` → rows that provider serves. */
        val serves = HashMap<String, List<JSONObject>>()
        var reads = 0

        override suspend fun currentAnchorKeyIds(channel: Channel) = anchorKeyIds
        var resolveFails = false
        override suspend fun resolve(streamId: String) =
            if (resolveFails) throw IllegalStateException("The Graph did not answer") else providerList
        override suspend fun readLast(
            provider: StorageEndpoints.Node, streamId: String, partition: Int, count: Int
        ): List<JSONObject>? {
            reads++
            return serves["${provider.urls.first()}|$streamId|$partition"] ?: emptyList()
        }
        var answered = true
        override suspend fun providers(streamId: String) =
            if (unlistedLookups-- > 0) emptyList()
            else listOf(StorageEndpoints.Provider(newNode, listOf("https://new.example"), features, answered))
        override suspend fun storedOn(
            provider: StorageEndpoints.Node, streamId: String, partition: Int, timestamps: List<Long>
        ) = timestamps.filter { "$streamId|$partition|$it" in held }.toSet()
        override fun loadPending(key: String) = pendingStore[key] ?: emptyList()
        override fun savePending(key: String, nodes: List<String>) {
            if (nodes.isEmpty()) pendingStore.remove(key) else pendingStore[key] = nodes
        }
        override fun notice(message: String, kind: StorageCopy.Notice) { notices += kind }
        override suspend fun sleep(ms: Long) {}
    }

    private lateinit var host: FakeHost
    private lateinit var copy: StorageCopy

    @Before
    fun setUp() {
        host = FakeHost()
        copy = StorageCopy(CoroutineScope(Dispatchers.Unconfined), host)
    }

    @Test
    fun `publishes the anchors and confirms them on the new provider`() = runBlocking {
        host.storesEverything = true
        host.image = JSONObject().put("data", "x").put("hash", "0xh")

        val outcome = copy.copyTo(stream, newNode, copy.prepare(stream)).await()

        assertEquals(StorageCopy.Outcome.PRESENT, outcome)
        assertEquals(listOf("keys", "admin", "image"), host.published)
        assertEquals(listOf(StorageCopy.Notice.DONE), host.notices)
        assertEquals(emptyList<String>(), copy.pending(stream))
    }

    @Test
    fun `publishes again only what the provider still lacks`() = runBlocking {
        host.storesEverything = true
        host.failAdminOnce = true

        val outcome = copy.copyTo(stream, newNode, null).await()

        assertEquals(StorageCopy.Outcome.PRESENT, outcome)
        assertEquals(listOf("keys", "admin"), host.published)
    }

    @Test
    fun `confirms every row of a snapshot that went out split`() = runBlocking {
        host.storesEverything = true
        host.adminRows = 3
        host.adminRowLost = 1

        val outcome = copy.copyTo(stream, newNode, null).await()

        assertEquals(StorageCopy.Outcome.PRESENT, outcome)
        assertEquals(6, host.published.count { it == "admin" })
    }

    @Test
    fun `keeps the copy pending, and says so, when the provider never holds it`() = runBlocking {
        val outcome = copy.copyTo(stream, newNode, null).await()

        assertEquals(StorageCopy.Outcome.MISSING, outcome)
        assertEquals(StorageCopy.REPUBLISH_LIMIT + 1, host.published.count { it == "keys" })
        assertEquals(listOf(newNode), copy.pending(stream))
        assertEquals(listOf(StorageCopy.Notice.WARNING), host.notices)
    }

    @Test
    fun `keeps asking while the new provider is not listed yet`() = runBlocking {
        host.storesEverything = true
        host.unlistedLookups = 2

        assertEquals(StorageCopy.Outcome.PRESENT, copy.copyTo(stream, newNode, null).await())
    }

    @Test
    fun `stops at a provider that cannot say what it holds`() = runBlocking {
        host.features = setOf("signedReads")

        val outcome = copy.copyTo(stream, newNode, null).await()

        assertEquals(StorageCopy.Outcome.UNVERIFIABLE, outcome)
        assertEquals(emptyList<String>(), copy.pending(stream))
    }

    @Test
    fun `keeps the copy pending for a provider that does not answer`() = runBlocking {
        host.features = emptySet()
        host.answered = false

        val outcome = copy.copyTo(stream, newNode, null).await()

        assertEquals(StorageCopy.Outcome.MISSING, outcome)
        assertEquals(listOf(newNode), copy.pending(stream))
        assertEquals(listOf(StorageCopy.Notice.WARNING), host.notices)
    }

    @Test
    fun `copies the password challenge of a password channel`() = runBlocking {
        host.storesEverything = true
        host.channel = host.channel.copy(type = "password", gateAddress = null, password = "pw")

        assertEquals(StorageCopy.Outcome.PRESENT, copy.copyTo(stream, newNode, null).await())
        assertEquals(listOf("admin", "password"), host.published)
    }

    @Test
    fun `prepares nothing for an account that does not own the channel`() = runBlocking {
        host.owns = false
        host.image = JSONObject().put("data", "x").put("hash", "0xh")

        assertNull(copy.prepare(stream))
    }

    @Test
    fun `runs a pending copy again when the owner opens the channel`() = runBlocking {
        host.storesEverything = true
        host.pendingStore["storage-copy|$owner|$stream"] = listOf(newNode)

        copy.resume(stream)

        assertTrue(StorageCopy.Notice.DONE in host.notices)
        assertEquals(emptyList<String>(), copy.pending(stream))
    }

    // ---- before a provider is removed ----

    private val oldNode = StorageEndpoints.Node("0x" + "dd".repeat(20), listOf("https://old.example"))
    private val newProvider = StorageEndpoints.Node(newNode, listOf("https://new.example"))
    private val announce = JSONObject().put("content", JSONObject().put("t", "key_announce").put("keyId", "7.cur"))
    private val sealed = JSONObject().put("content", JSONObject().put("e", "epoch-aes-gcm"))

    private fun serve(provider: StorageEndpoints.Node, streamId: String, partition: Int, vararg rows: JSONObject) {
        host.serves["${provider.urls.first()}|$streamId|$partition"] = rows.toList()
    }

    private fun removalSetUp() {
        host.providerList = listOf(oldNode, newProvider)
        serve(oldNode, keys, StreamConstants.P_KEY_EXCHANGE, announce)
        serve(oldNode, admin, StreamConstants.ADMIN_MODERATION, sealed)
    }

    private suspend fun refused(): String? = try {
        copy.ensureRemainingHold(stream, oldNode.nodeAddress); null
    } catch (e: IllegalStateException) {
        e.message
    }

    @Test
    fun `lets the removal go on when the provider that stays holds everything`() = runBlocking {
        removalSetUp()
        serve(newProvider, keys, StreamConstants.P_KEY_EXCHANGE, announce)
        serve(newProvider, admin, StreamConstants.ADMIN_MODERATION, sealed)

        assertNull(refused())
        assertEquals(emptyList<String>(), host.published)
    }

    @Test
    fun `copies what the provider that stays lacks, then lets the removal go on`() = runBlocking {
        removalSetUp()
        host.storesEverything = true
        serve(newProvider, admin, StreamConstants.ADMIN_MODERATION, sealed)
        host.onRepublishAnchors = { serve(newProvider, keys, StreamConstants.P_KEY_EXCHANGE, announce) }

        assertNull(refused())
        assertEquals("keys", host.published.first())
        assertEquals(StorageCopy.Notice.PROGRESS, host.notices.first())
    }

    @Test
    fun `refuses the removal while the provider that stays still lacks it`() = runBlocking {
        removalSetUp()

        assertTrue(refused()!!.contains("was not removed"))
    }

    @Test
    fun `asks for the image only when the provider leaving serves one`() = runBlocking {
        removalSetUp()
        serve(newProvider, keys, StreamConstants.P_KEY_EXCHANGE, announce)
        serve(newProvider, admin, StreamConstants.ADMIN_MODERATION, sealed)
        serve(oldNode, admin, StreamConstants.ADMIN_CHANNEL_IMAGE, sealed)

        assertTrue(refused()!!.contains("was not removed"))
    }

    @Test
    fun `protects nothing when the provider leaving is the last one`() = runBlocking {
        removalSetUp()
        host.providerList = listOf(oldNode)

        assertNull(refused())
        assertEquals(0, host.reads)
    }

    @Test
    fun `refuses the removal when the providers of the channel cannot be read`() = runBlocking {
        removalSetUp()
        host.resolveFails = true

        assertTrue(refused()!!.contains("was not removed"))
        assertEquals(0, host.reads)
    }

    @Test
    fun `refuses the removal while a provider still waiting for its copy is not listed yet`() = runBlocking {
        removalSetUp()
        host.providerList = listOf(oldNode)
        host.pendingStore["storage-copy|$owner|$stream"] = listOf(newNode)

        assertTrue(refused()!!.contains("not confirmed yet"))
    }

    @Test
    fun `checks nothing for an account that does not own the channel`() = runBlocking {
        removalSetUp()
        host.owns = false

        assertNull(refused())
        assertEquals(0, host.reads)
    }
}
