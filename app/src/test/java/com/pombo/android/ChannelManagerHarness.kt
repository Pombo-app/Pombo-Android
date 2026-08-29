package com.pombo.android

import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
 */
class ChannelManagerHarness(
    channels: List<Channel> = emptyList()
) {
    /** Throwaway key; the channel pseudonym is minted from it, so it must be real. */
    val myKey = "0x4f3edf983ac636a65a842ce7c78d9aa706d3b113bce9c46f30d7d21715b23b1d"
    val me: String = com.pombo.android.core.EthereumSigner.address(myKey).lowercase()
    val bridge: com.pombo.android.bridge.PomboBridge = mockk(relaxed = true)
    val store: com.pombo.android.data.ChannelStore = mockk(relaxed = true)
    val ensStore: com.pombo.android.core.EnsStore = mockk(relaxed = true)
    val unreadStore: com.pombo.android.data.UnreadStore = mockk(relaxed = true)

    /** Every payload handed to the bridge, in order, as JSON text. */
    val published = mutableListOf<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    val manager: ChannelManager

    init {
        every { store.load() } returns channels
        val args = slot<JSONObject>()
        coEvery { bridge.call(any(), capture(args)) } answers {
            published += args.captured.toString()
            JSONObject()
        }
        manager = ChannelManager(
            bridge = bridge,
            store = store,
            scope = scope,
            myAddress = { me },
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
            transferDir = java.io.File(System.getProperty("java.io.tmpdir"), "pombo-tests")
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

    fun stop() = scope.cancel()

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
