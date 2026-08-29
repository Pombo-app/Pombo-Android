package com.pombo.android.core.channels

import com.pombo.android.ChannelManager
import com.pombo.android.ChannelManager.Companion.ONLINE_TIMEOUT_MS
import com.pombo.android.ChannelManager.Companion.PRESENCE_INTERVAL_MS
import com.pombo.android.ChannelManager.Companion.PREVIEW_PRESENCE_INTERVAL_MS
import com.pombo.android.ChannelManager.Companion.TYPING_TTL_MS
import com.pombo.android.ChannelManager.OnlineUser
import com.pombo.android.ChannelManager.TypingPeer
import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Who is online and who is typing, off the ephemeral stream (-2/P0).
 *
 * The state lives here; [ChannelManager] keeps forwarding accessors for the
 * areas that still read it directly, and the public StateFlows stay on the
 * manager so its surface does not move. Everything this needs from the manager
 * goes back through it rather than to another collaborator, so a call site
 * substituted on the manager still intercepts.
 */
internal class PresenceTracker(private val manager: ChannelManager) {

    private val scope get() = manager.scope
    private val myUsername get() = manager.myUsername
    private val _current get() = manager._current
    private val _isPreview get() = manager._isPreview

    private fun ensureEns(address: String) = manager.ensureEns(address)

    private suspend fun publishForChannel(
        channel: Channel,
        streamId: String,
        partition: Int,
        payload: JSONObject
    ) = manager.publishForChannel(channel, streamId, partition, payload)

    internal val _onlineCount = MutableStateFlow(0)

    /**
     * False from the moment a channel starts opening until its subscriptions
     * are live. The header shows "Connecting…" in the online-count slot while
     * it is false, so that slot is never empty and the title above it does not
     * jump once presence lands.
     */
    internal val _presenceReady = MutableStateFlow(false)

    /**
     * Everyone typing right now, oldest signal first. A busy channel can have
     * several people typing at once, and a single slot meant the last signal
     * erased whoever was already there.
     */
    internal val _typingFrom = MutableStateFlow<List<TypingPeer>>(emptyList())

    /** address -> when their last signal arrived. */
    private val typingSeen = LinkedHashMap<String, Long>()
    private val typingNames = HashMap<String, String?>()

    /** Shared by the DM and channel paths — both also warm the ENS name. */
    internal fun markTyping(address: String, nickname: String?) {
        ensureEns(address)
        synchronized(typingSeen) {
            typingSeen[address] = System.currentTimeMillis()
            typingNames[address] = nickname
            publishTyping()
        }
        // One sweeper for everyone: each pass drops whoever went quiet and
        // re-arms only while someone is still typing.
        typingClearJob?.cancel()
        typingClearJob = scope.launch {
            while (true) {
                delay(1000)
                val remaining = synchronized(typingSeen) {
                    val cutoff = System.currentTimeMillis() - TYPING_TTL_MS
                    typingSeen.entries.removeAll { it.value < cutoff }
                    publishTyping()
                    typingSeen.size
                }
                if (remaining == 0) break
            }
        }
    }

    /** Caller holds the [typingSeen] lock. */
    private fun publishTyping() {
        _typingFrom.value = typingSeen.keys.map { TypingPeer(it, typingNames[it]) }
    }

    internal fun clearTyping() {
        typingClearJob?.cancel()
        synchronized(typingSeen) {
            typingSeen.clear()
            typingNames.clear()
        }
        _typingFrom.value = emptyList()
    }

    // presence: senderId -> lastActive
    internal val online = HashMap<String, Long>()
    internal val onlineNames = HashMap<String, String?>()

    internal val _onlineUsers = MutableStateFlow<List<OnlineUser>>(emptyList())
    internal var presenceJob: Job? = null
    private var typingClearJob: Job? = null

    // Web InputUI.js sends the typing signal at most every 2s while keys keep
    // coming. Without this floor every keystroke becomes a full Streamr publish
    // (plus an ECDH seal on DMs) queued on the single WebView JS thread, which
    // is enough to make the whole app feel frozen while composing a message.
    @Volatile private var lastTypingSent = 0L

    internal fun sendTyping() {
        val channel = _current.value ?: return
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 2_000) return
        lastTypingSent = now
        scope.launch {
            try {
                val typing = JSONObject()
                    .put("type", "typing")
                    .put("nickname", myUsername() ?: JSONObject.NULL)
                    .put("timestamp", System.currentTimeMillis())
                publishForChannel(channel, channel.ephemeralStreamId, StreamConstants.EPH_CONTROL, typing)
            } catch (e: Exception) { }
        }
    }

    internal fun startPresence(channel: Channel) {
        presenceJob?.cancel()
        presenceJob = scope.launch {
            while (isActive) {
                try {
                    val presence = JSONObject()
                        .put("type", "presence")
                        .put("nickname", myUsername() ?: JSONObject.NULL)
                        .put("lastActive", System.currentTimeMillis())
                    publishForChannel(channel, channel.ephemeralStreamId, StreamConstants.EPH_CONTROL, presence)
                } catch (e: Exception) { }
                evictStale()
                // A preview heartbeats at the web's slower cadence
                // (config.previewPresenceIntervalMs = 20s) — the browsing user
                // is not in the conversation, 4× the traffic bought nothing.
                delay(if (_isPreview.value) PREVIEW_PRESENCE_INTERVAL_MS else PRESENCE_INTERVAL_MS)
            }
        }
    }

    private fun evictStale() {
        val now = System.currentTimeMillis()
        synchronized(online) {
            online.entries.removeAll { now - it.value > ONLINE_TIMEOUT_MS }
            _onlineCount.value = online.size
        }
    }
}
