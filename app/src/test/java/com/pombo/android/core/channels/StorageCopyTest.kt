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
        override suspend fun republishAnchors(channel: Channel) =
            listOf(publish("keys", keys, StreamConstants.P_KEY_EXCHANGE))
        override suspend fun publishAdminState(channel: Channel): Long {
            if (failAdminOnce) { failAdminOnce = false; error("channel not open") }
            return publish("admin", admin, StreamConstants.ADMIN_MODERATION)
        }
        override suspend fun republishImage(channel: Channel, payload: JSONObject) =
            publish("image", admin, StreamConstants.ADMIN_CHANNEL_IMAGE)
        override suspend fun publishPasswordChallenge(channel: Channel) =
            publish("password", admin, StreamConstants.ADMIN_PASSWORD_CHALLENGE)
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
}
