package com.pombo.android

import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject

/**
 * A real [ChannelManager] over mocked collaborators, for tests that need to
 * drive the class rather than read its source.
 *
 * Two constraints the code cannot show:
 *
 * The scope MUST carry a SupervisorJob, matching the production
 * `viewModelScope`. Without one, the first child coroutine that throws
 * cancels the scope, and every later `scope.launch` is dispatched into a dead
 * job: the body never runs, nothing throws, and a test asserting "no crash"
 * passes while exercising nothing.
 *
 * The mocks are relaxed, so anything reached through the bridge answers with a
 * stub. Only assert on state this class wrote; a call that merely returned is
 * no evidence.
 *
 * The Graph is stubbed to know nothing. Its cache is JVM-wide: with real
 * lookups, whether an open runs to the end depends on an earlier test.
 */
class ChannelManagerHarness(
    channels: List<Channel> = emptyList(),
    private val trustedContacts: Set<String> = emptySet()
) {
    /** Throwaway key; the channel pseudonym is minted from it, so it must be real. */
    val myKey = "0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d"
    val me: String = com.pombo.android.core.EthereumSigner.address(myKey).lowercase()
    /** What the manager's `myAddress` answers; a test switches accounts by changing it. */
    @Volatile var address: String = me
    /** What the manager's `isOnline` answers; a test cuts the network by clearing it. */
    @Volatile var online: Boolean = true
    val bridge: com.pombo.android.bridge.PomboBridge = mockk(relaxed = true)
    val store: com.pombo.android.data.ChannelStore = mockk(relaxed = true)
    val ensStore: com.pombo.android.core.EnsStore = mockk(relaxed = true)
    val unreadStore: com.pombo.android.data.UnreadStore = mockk(relaxed = true)
    val adminFloorStore: com.pombo.android.core.AdminFloorStore = mockk(relaxed = true)

    /** Every payload handed to the bridge, in order, as JSON text. */
    val published = mutableListOf<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    val manager: ChannelManager

    init {
        every { store.load() } returns channels
        // A relaxed mock answers a String? with "", not null, and ENS treats
        // any non-null name as resolved — which silently grants trust level 1
        // to every sender. Say "nothing cached, nothing resolves" out loud.
        every { ensStore.cachedName(any()) } returns null
        every { ensStore.cachedAvatar(any()) } returns null
        coEvery { ensStore.name(any(), any()) } returns null
        mockkObject(com.pombo.android.core.GraphApi)
        coEvery { com.pombo.android.core.GraphApi.streamRetention(any()) } returns null
        coEvery { com.pombo.android.core.GraphApi.getChannelInfo(any()) } returns null

        val args = slot<JSONObject>()
        coEvery { bridge.call(any(), capture(args)) } answers {
            published += args.captured.toString()
            JSONObject()
        }
        manager = ChannelManager(
            bridge = bridge,
            store = store,
            scope = scope,
            myAddress = { address },
            myPrivateKey = { myKey },
            myUsername = { "me" },
            imageStore = mockk(relaxed = true),
            previewStore = mockk(relaxed = true),
            ensStore = ensStore,
            blobStore = mockk(relaxed = true),
            sentDmStore = mockk(relaxed = true),
            inviteStore = mockk(relaxed = true),
            unreadStore = unreadStore,
            epochKeyStore = mockk(relaxed = true),
            adminFloorStore = adminFloorStore,
            transferDir = java.io.File(System.getProperty("java.io.tmpdir"), "pombo-tests"),
            isTrustedContact = { addr -> addr.lowercase() in trustedContacts },
            isOnline = { online }
        )
    }

    /** How many publishes carried a payload of this wire type. */
    fun publishedOfType(type: String): Int =
        published.count { it.contains("\"type\":\"$type\"") }

    /** Delivers a signal as the bridge listener would. */
    fun deliver(streamId: String, partition: Int, content: JSONObject, from: String) {
        manager.onIncoming(
            streamId, partition, content.toString(),
            JSONObject().put("publisherId", from)
                .put("timestamp", System.currentTimeMillis()).toString()
        )
    }

    fun stop() {
        scope.cancel()
        unmockkObject(com.pombo.android.core.GraphApi)
    }

    companion object {
        fun channel(
            streamId: String,
            type: String = "public",
            name: String = "test"
        ) = Channel(
            messageStreamId = streamId,
            ephemeralStreamId = StreamConstants.deriveEphemeralId(streamId),
            adminStreamId = StreamConstants.deriveAdminId(streamId),
            name = name,
            type = type,
            createdBy = streamId.substringBefore('/')
        )
    }
}
