package com.pombo.android

import android.util.Log
import com.pombo.android.bridge.PomboBridge
import com.pombo.android.core.InviteToken
import com.pombo.android.core.PomboCrypto
import com.pombo.android.core.Protocol
import com.pombo.android.core.StreamConstants
import com.pombo.android.core.channels.ChannelImages
import com.pombo.android.core.channels.FileTransfers
import com.pombo.android.core.channels.MessageVerification
import com.pombo.android.core.channels.Moderation
import com.pombo.android.core.channels.PresenceTracker
import com.pombo.android.core.channels.optStringOrNull
import com.pombo.android.data.Channel
import com.pombo.android.data.ChannelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Public channel preview for the Explore tab (web: card + tags + last message). */
data class ExploreChannel(
    val messageStreamId: String,
    val name: String,
    val description: String,
    val type: String,
    val language: String = "",
    val category: String = "",
    val lastSender: String = "",
    val lastText: String = "",
    /** Raw address behind [lastSender], so the card can resolve ENS at render. */
    val lastSenderAddress: String = "",
    /** Drives the megaphone on the Explore card, as in the web's readOnlyBadge. */
    val readOnly: Boolean = false,
    /** Gated (N-D): the PomboGate clone from metadata `g` — routes the tap
     *  (TOKEN/NFT with access → preview; PAID / no access → join flow). */
    val gateAddress: String? = null,
    /** Resolved gate mode (drives the Open/Gated/Paid markers). */
    val gateMode: Int? = null,
    /** 3-line access stack on the card: VERB / VALUE / QUALIFIER
     *  ("Subscribe" / "500 POL" / "30 days" · "Hold" / … / "in your wallet"). */
    val gateVerb: String? = null,
    val gateValue: String? = null,
    val gateQualifier: String? = null,
    /** Author visibility from metadata `m` ('members' | 'everyone'). */
    val authorMode: String? = null
)

/** Quoted message carried by a reply (web: msg.replyTo). */
data class ReplyRef(
    val id: String,
    val sender: String,
    val senderName: String?,
    val text: String
)

/** Message ready for the UI. verified: null = not yet verified. */
data class UiMessage(
    val id: String,
    val text: String,
    val sender: String,
    val senderName: String?,
    val timestamp: Long,
    val mine: Boolean,
    val pending: Boolean = false,
    val verified: Boolean? = null,
    /** -1 invalid, 0 valid signature, 1 ENS verified, 2 trusted contact. */
    val trustLevel: Int = 0,
    val edited: Boolean = false,
    /** Reverse-resolved ENS name for the sender, when available. */
    val ensName: String? = null,
    /** ENS avatar URL — replaces the generated avatar when present. */
    val ensAvatar: String? = null,
    val replyTo: ReplyRef? = null,
    // Chunked image (transport 'chunked' v2). imageBytes null while assembling.
    val isImage: Boolean = false,
    val imageId: String? = null,
    val imageBytes: ByteArray? = null,
    val imageMime: String? = null,
    /**
     * P2P shared file (wire type `file_announce`). The bytes are NOT in the
     * message — the announcement only describes the file and its piece hashes;
     * the content is fetched from whoever is seeding it.
     */
    val file: com.pombo.android.core.MediaController.FileMetadata? = null,
    /** Persistent File Sharing announce (wire type `storage_file_announce`). */
    val storageFile: com.pombo.android.core.StorageMedia.StorageFileMetadata? = null
)

/**
 * Channel logic — mirror of the web channels.js over PomboBridge.
 * All wire formats are identical to the Pombo web ones (see spec in the README).
 */
class ChannelManager(
    internal val bridge: PomboBridge,
    internal val store: ChannelStore,
    internal val scope: CoroutineScope,
    internal val myAddress: () -> String?,
    private val myPrivateKey: () -> String?,
    internal val myUsername: () -> String?,
    /** Persistent caches so cold starts paint before the network answers. */
    internal val imageStore: com.pombo.android.core.ChannelImageStore,
    private val previewStore: com.pombo.android.core.LatestMessageStore,
    internal val ensStore: com.pombo.android.core.EnsStore,
    internal val blobStore: com.pombo.android.core.ImageBlobStore,
    internal val sentDmStore: com.pombo.android.data.SentDmStore,
    /** Own DM/write-only reactions — no resend returns them (web sentReactions). */
    private val sentReactionsStore: com.pombo.android.data.SentReactionsStore? = null,
    private val inviteStore: com.pombo.android.data.InviteStore,
    private val unreadStore: com.pombo.android.data.UnreadStore,
    /** Persisted epoch keys for gated channels (-4 protocol, N-A). */
    private val epochKeyStore: com.pombo.android.data.EpochKeyStore,
    /**
     * Where partial file transfers live. filesDir, never cacheDir: the system
     * may evict a cache directory at any moment, and a half-evicted transfer
     * would resume against a bitmap claiming pieces that are gone.
     */
    internal val transferDir: java.io.File,
    /** Trust level 2 in the web comes from the trusted-contacts list. */
    internal val isTrustedContact: (String) -> Boolean = { false },
    /** Blocked peers (web secureStorage.isBlocked) — a synced slice. */
    private val isBlockedPeer: (String) -> Boolean = { false },
    /** Persist a new block; the caller owns the settings store. */
    private val persistBlockedPeer: (String) -> Unit = {}
) {

    /**
     * Presence and typing (core/channels). Declared first: the public
     * StateFlows below read its state at construction.
     */
    private val presence = PresenceTracker(this)

    /** Channel images and the chunked image transport (core/channels). */
    private val images = ChannelImages(this)

    /** P2P and storage-cluster file sharing (core/channels). */
    private val files = FileTransfers(this)

    /** Moderation, permissions and gated membership (core/channels). */
    private val admin = Moderation(this)

    /** Signature verification and the trust ladder (core/channels). */
    private val verification = MessageVerification(this)

    /**
     * Epoch keys for gated channels (-4, N-A/N-C). Transport is injected:
     * protocol messages go out with encryptionType NONE (client.publish would
     * force the SDK's AES + group-key exchange on a members-only stream — the
     * very dependency the epoch protocol replaces), and announce history is a
     * plain -4 resend.
     */
    internal val epochKeys = com.pombo.android.core.EpochKeyManager(
        store = epochKeyStore,
        scope = scope,
        myAddress = myAddress,
        publishKeys = { keysStreamId, data ->
            // The -4 grant is on the clone, not per member — the account
            // signs, the clone is the on-wire publisher, receivers recover
            // the author from the envelope (N-C).
            val channel = channelByStream(keysStreamId) ?: throw IllegalStateException(
                "Unknown channel for $keysStreamId — cannot publish keys")
            val gate = channel.gateAddress ?: throw IllegalStateException(
                "Gate address unknown for ${channel.messageStreamId} — cannot publish keys")
            bridge.call("publishAsGate", JSONObject()
                .put("streamId", keysStreamId)
                .put("partition", StreamConstants.P_KEY_EXCHANGE)
                .put("content", data)
                .put("gateAddress", gate))
        },
        resendKeys = { keysStreamId ->
            val entries = mutableListOf<com.pombo.android.core.EpochKeyManager.Entry>()
            val gatedChannel = channelByStream(keysStreamId)?.takeIf { it.type == "gated" }
            try {
                // Raw: skips the SDK's validation/ordering pipeline. Gap
                // filling rides the mesh, so on a half-connected node an
                // ordered resend silently stalls or returns empty while
                // plain HTTP works. Authority comes from meta.signer
                // (recoverSigner) — raw always travels with it.
                val res = bridge.call("resend", JSONObject()
                    .put("streamId", keysStreamId)
                    .put("partition", StreamConstants.P_KEY_EXCHANGE)
                    .put("last", 1000)
                    .put("raw", gatedChannel != null)
                    .put("recoverSigner", gatedChannel != null), 30_000)
                val arr = res.optJSONArray("messages")
                if (arr != null) for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    val content = entry.opt("content") as? JSONObject ?: continue
                    val meta = entry.optJSONObject("meta") ?: JSONObject()
                    // Gated: the epoch protocol's identity checks (admin set,
                    // own-request skip, gate check) need the AUTHOR — the
                    // envelope signer — never the clone.
                    val publisher = if (gatedChannel != null) {
                        gatedAuthor(gatedChannel, keysStreamId, meta) ?: continue
                    } else meta.optString("publisherId").ifEmpty { null }
                    entries.add(com.pombo.android.core.EpochKeyManager.Entry(
                        content,
                        publisher,
                        meta.optLong("timestamp", 0L)))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // channel switch — propagate, never swallow
            } catch (e: Exception) {
                // No storage attached yet / empty stream — an empty list is
                // the correct cold start ("no announces")
                Log.d(TAG, "keys resend empty (${e.message})")
            }
            entries
        },
        onKeyAdopted = { messageStreamId, _ -> refreshAfterEpochKey(messageStreamId) },
        checkGateAccess = { messageStreamId, requester ->
            val channel = channelByStream(messageStreamId)
            if (channel?.type != "gated") true
            else {
                val gate = channel.gateAddress
                if (gate == null) false // fail-closed: unknown gate wraps nothing
                else try {
                    val res = bridge.call("gateCheckAccess", JSONObject()
                        .put("gate", gate).put("user", requester))
                    // The bridge reports WHY it says no. Reading only `access`
                    // turned every RPC outage into "refused by gate", which
                    // reads as policy and sent us hunting for bans that were
                    // really a dead endpoint. The answer stays fail-closed.
                    if (res.optBoolean("failed", false)) {
                        Log.w(TAG, "gate unreadable for $requester on ${messageStreamId.takeLast(20)} " +
                            "— refusing this request, NOT a ban")
                        false
                    } else res.optBoolean("access", false)
                } catch (e: Exception) {
                    Log.w(TAG, "gateCheckAccess failed (fail-closed): ${e.message}")
                    false
                }
            }
        },
        currentEpochOnly = { messageStreamId ->
            val channel = channelByStream(messageStreamId)
            val gate = channel?.takeIf { it.type == "gated" }?.gateAddress
            if (gate == null) false
            else try {
                // Readability probe, result unused: every gate mode receives
                // all retained epochs, but an unreadable gate must answer
                // current-epoch-only
                bridge.call("gateInfo", JSONObject().put("gate", gate))
                false
            } catch (e: Exception) {
                Log.w(TAG, "gateInfo failed, answering current epoch only: ${e.message}")
                true
            }
        },
        myPrivateKey = myPrivateKey,
        publishRoster = { keysStreamId, data ->
            val channel = channelByStream(keysStreamId) ?: throw IllegalStateException(
                "Unknown channel for $keysStreamId — cannot publish roster")
            val gate = channel.gateAddress ?: throw IllegalStateException(
                "Gate address unknown for ${channel.messageStreamId} — cannot publish roster")
            bridge.call("publishAsGate", JSONObject()
                .put("streamId", keysStreamId)
                .put("partition", StreamConstants.P_ROSTER)
                .put("content", data)
                .put("gateAddress", gate))
        },
        resendRoster = { keysStreamId ->
            val entries = mutableListOf<com.pombo.android.core.EpochKeyManager.Entry>()
            val gatedChannel = channelByStream(keysStreamId)?.takeIf { it.type == "gated" }
            try {
                // Raw + recoverSigner, same rationale as resendKeys above.
                val res = bridge.call("resend", JSONObject()
                    .put("streamId", keysStreamId)
                    .put("partition", StreamConstants.P_ROSTER)
                    .put("last", 500)
                    .put("raw", gatedChannel != null)
                    .put("recoverSigner", gatedChannel != null), 30_000)
                val arr = res.optJSONArray("messages")
                if (arr != null) for (i in 0 until arr.length()) {
                    val entry = arr.optJSONObject(i) ?: continue
                    val content = entry.opt("content") as? JSONObject ?: continue
                    val meta = entry.optJSONObject("meta") ?: JSONObject()
                    val publisher = if (gatedChannel != null) {
                        gatedAuthor(gatedChannel, keysStreamId, meta) ?: continue
                    } else meta.optString("publisherId").ifEmpty { null }
                    entries.add(com.pombo.android.core.EpochKeyManager.Entry(
                        content,
                        publisher,
                        meta.optLong("timestamp", 0L)))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "roster resend empty (${e.message})")
            }
            entries
        },
        keysPartitionCount = { keysStreamId ->
            bridge.call("getStreamInfo", JSONObject().put("streamId", keysStreamId))
                .optJSONObject("metadata")?.optInt("partitions", 1) ?: 1
        },
        emitKeysWake = { messageStreamId ->
            channelByStream(messageStreamId)?.let { sendWakeSignal(it, kind = "keys") }
        },
        sharedPublishFor = { messageStreamId ->
            channelByStream(messageStreamId)?.authorMode == "members"
        }
    )

    private var epochRefreshJob: Job? = null

    /**
     * A key was adopted: messages skipped as "waiting for key" are sitting in
     * storage — re-pull the recent window through the normal handlers (which
     * dedupe by id and re-apply pending overrides). Debounced: adopting N
     * epochs in a burst (join) fires one refresh.
     */
    private fun refreshAfterEpochKey(messageStreamId: String) {
        if (_current.value?.messageStreamId != messageStreamId) return
        _waitingForKeys.value = false
        epochRefreshJob?.cancel()
        epochRefreshJob = scope.launch {
            delay(1_500)
            val channel = _current.value?.takeIf { it.messageStreamId == messageStreamId }
                ?: return@launch
            Log.i(TAG, "epochKeys: refreshing history after key adoption")
            loadHistory(channel, switchGeneration)
            // The -3 artifacts fetched at open were epoch-sealed and
            // unreadable until this key arrived — pins/moderation and a
            // hidden channel's image need their own re-pull (the admin
            // poller would take up to a full tick to converge).
            runCatching { loadAdminState(channel, switchGeneration) }
            runCatching { loadChannelImage(channel) }
        }
    }

    /**
     * P2P file transfers. Owned here rather than beside the channel list
     * because the only questions it answers from outside — "does this channel
     * still need its media partitions?" — are asked during a channel switch,
     * and every subscribe/unsubscribe has to stay inside [channelSwitchMutex].
     */
    val media = com.pombo.android.core.MediaController(
        bridge = bridge,
        scope = scope,
        myAddress = myAddress,
        transferDir = { transferDir },
        gateAddressFor = { streamId ->
            channelByStream(streamId)?.takeIf { it.type == "gated" }?.gateAddress
        },
        transport = object : com.pombo.android.core.MediaController.Transport {
            /**
             * Signals seal in the BRIDGE rather than through [publishContent].
             *
             * The sealing rule is still honoured — the password is passed on
             * every call and the bridge encrypts whenever it gets one, so no
             * call site can forget. What moves is only WHERE the AES work
             * happens, and it has to move: a piece_request goes out per piece,
             * and PBKDF2 through Bouncy Castle costs about a second each. On a
             * 113 MB file that is over eight minutes spent asking.
             */
            override suspend fun publishMediaSignal(
                ephemeralStreamId: String,
                payload: JSONObject,
                password: String?,
                isDm: Boolean
            ) {
                if (isDm) {
                    // DM signals are sealed-sender v2 to the PEER's inbox
                    // ephemeral (web media.js: dmManager.sealAndPublish), one
                    // fresh throwaway publisher per signal. The peer is the
                    // inbox owner — the stream id's prefix. Sealing stays
                    // inside publishContent, the one place that decides how a
                    // payload is encrypted.
                    val peerAddr = ephemeralStreamId.substringBefore('/').lowercase()
                    publishContent(
                        ephemeralStreamId, StreamConstants.EPH_MEDIA_SIGNALS,
                        payload, password = null, dmPeer = peerAddr
                    )
                    return
                }
                // Channel signals ride the channel's ephemeral identity too —
                // "everything that channel sends" (channelIdentity.js): a
                // wallet-published piece_request would undo the pseudonym for
                // the whole transfer. Password sealing stays in the bridge.
                publishChannel(
                    channelByStream(ephemeralStreamId), ephemeralStreamId,
                    StreamConstants.EPH_MEDIA_SIGNALS, payload, password
                )
            }

            /**
             * Pieces bypass publishContent because they are bytes, not JSON:
             * password encryption for them happens inside the bridge, where
             * PBKDF2 runs in BoringSSL instead of Bouncy Castle. DM pieces
             * likewise seal in the bridge, with the pair's ECDH key.
             */
            override suspend fun publishMediaPiece(
                ephemeralStreamId: String,
                bytes: ByteArray,
                password: String?,
                isDm: Boolean
            ) {
                if (isDm) {
                    // Sealed-sender 0x02 envelope, ONE ephemeral key per
                    // transfer (web: sealer cached per (file, stream)). The
                    // fileId is already inside the piece frame
                    // [0x01][fileId:36][idx:4][data], so the sealer is keyed
                    // by (fileId, destination stream) without widening the
                    // Transport interface. Seal is native — the account key
                    // signs the binding proof in Kotlin; the bridge only
                    // publishes under the sealer's throwaway identity.
                    val peerAddr = ephemeralStreamId.substringBefore('/').lowercase()
                    val pk = dmMediaPeerKey(ephemeralStreamId)
                        ?: throw IllegalStateException("Peer public key unavailable for DM media")
                    val myPk = myPrivateKey()
                        ?: throw IllegalStateException("No identity")
                    val fileId = if (bytes.size >= 37) String(bytes, 1, 36, Charsets.UTF_8) else ""
                    val sealer = dmBinarySealers.computeIfAbsent("$fileId|$ephemeralStreamId") {
                        com.pombo.android.core.SealedSenderCrypto.binarySealer(myPk, peerAddr, pk)
                    }
                    bridge.publishBinary(
                        ephemeralStreamId,
                        StreamConstants.EPH_MEDIA_DATA,
                        com.pombo.android.core.SealedSenderCrypto.sealBinary(sealer, bytes),
                        password = null,
                        identityPk = sealer.ephemeralPrivateKeyHex
                    )
                    return
                }
                // Gated channels: the piece is sealed with the epoch key in
                // Kotlin (0x04 envelope), no inline proof — authorship comes
                // from the same place as the channel's messages (envelope
                // signer). Fail closed while waiting for a key: a plaintext
                // or ephemeral-key fallback would leak or be rejected.
                val channel = channelByStream(ephemeralStreamId)
                if (isEpochChannel(channel)) {
                    val sealed = epochKeys.sealBinaryCurrent(channel!!.messageStreamId, bytes)
                        ?: throw IllegalStateException(
                            "No epoch key for ${channel.messageStreamId} — cannot send media")
                    // Members-only: pieces travel under the SHARED key — the
                    // clone path would stamp the sender's account onto them.
                    if (channel.authorMode == "members") {
                        val pub = epochKeys.publishKeyFor(channel.messageStreamId)
                            ?: throw IllegalStateException(
                                "No publish key for ${channel.messageStreamId} — cannot send media on a Members-only channel")
                        bridge.publishBinary(
                            ephemeralStreamId,
                            StreamConstants.EPH_MEDIA_DATA,
                            sealed,
                            password = null,
                            identityPk = pub.keyHex
                        )
                        return
                    }
                    val gate = channel.gateAddress ?: throw IllegalStateException(
                        "Gate address unknown for ${channel.messageStreamId} — cannot publish")
                    bridge.publishBinary(
                        ephemeralStreamId,
                        StreamConstants.EPH_MEDIA_DATA,
                        sealed,
                        password = null,
                        gateAddress = gate
                    )
                    return
                }
                // Channel pieces carry the inline proof (0x03) and publish
                // under the channel identity — frame and proof built natively:
                //   [0x03][proof:65][fileId:36][idx:4][data]
                require(bytes.isNotEmpty() && bytes[0].toInt() == 0x01) {
                    "channel piece expects a 0x01 frame"
                }
                val entry = channelIdentity(ephemeralStreamId)
                val proofBytes = com.pombo.android.core.SealedSenderCrypto.hexToBytes(entry.proof)
                val framed = ByteArray(1 + proofBytes.size + bytes.size - 1)
                framed[0] = 0x03
                System.arraycopy(proofBytes, 0, framed, 1, proofBytes.size)
                System.arraycopy(bytes, 1, framed, 1 + proofBytes.size, bytes.size - 1)
                bridge.publishBinary(
                    ephemeralStreamId,
                    StreamConstants.EPH_MEDIA_DATA,
                    framed,
                    password,
                    identityPk = entry.identityPk
                )
            }
        }
    )

    /** One sealed-sender binary sealer per (file, destination stream) — see the DM piece path above. */
    private val dmBinarySealers =
        java.util.concurrent.ConcurrentHashMap<String, com.pombo.android.core.SealedSenderCrypto.BinarySealer>()

    /** Natively-minted channel pseudonym for a stream — proof signed in Kotlin. */
    private fun channelIdentity(streamId: String): com.pombo.android.core.ChannelIdentities.Entry {
        val addr = myAddress() ?: throw IllegalStateException("No identity")
        val pk = myPrivateKey() ?: throw IllegalStateException("No identity")
        return com.pombo.android.core.ChannelIdentities.entryFor(streamId, addr, pk)
    }

    /**
     * On-chain storage-endpoint resolution for the Persistent File Sharing
     * transport. The bridge does the two SDK calls; the web-safe URL filter and
     * rotation/health live natively in [com.pombo.android.core.StorageEndpoints].
     */
    private val storageEndpoints = com.pombo.android.core.StorageEndpoints(
        fetcher = { streamId ->
            val res = bridge.call("resolveStorageEndpoints", JSONObject().put("streamId", streamId))
            val arr = res.optJSONArray("nodes") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val n = arr.optJSONObject(i) ?: return@mapNotNull null
                val urlsArr = n.optJSONArray("urls") ?: org.json.JSONArray()
                com.pombo.android.core.StorageEndpoints.Node(
                    nodeAddress = n.optString("nodeAddress"),
                    urls = (0 until urlsArr.length()).map { urlsArr.optString(it) }
                )
            }
        }
    )

    /**
     * Persistent File Sharing engine (storage-node transport). Standalone (not
     * owned like [media]) — storage never holds live subscriptions, so nothing
     * ties it to the channel-switch mutex. It reaches the network for the announce
     * through [publishContent], the single sealing point; raw chunk publishes go
     * straight to the bridge with the timestamp it needs for verify.
     */
    val storageMedia = com.pombo.android.core.StorageMedia(
        bridge = bridge,
        endpoints = storageEndpoints,
        scope = scope,
        transferDir = { transferDir },
        myPrivateKey = myPrivateKey,
        transport = object : com.pombo.android.core.StorageMedia.Transport {
            override suspend fun publishAnnounce(
                messageStreamId: String, announce: JSONObject, password: String?, isDm: Boolean
            ): Long {
                if (isDm) {
                    // The announce rides a sealed-sender v2 envelope to the
                    // peer's inbox P0, through the single sealing point, like
                    // every DM message. Returns the publish timestamp.
                    val peer = _current.value?.peerAddress ?: throw IllegalStateException("DM announce: no peer")
                    return publishContent(messageStreamId, StreamConstants.P_MESSAGES, announce, password = null, dmPeer = peer)
                }
                // Regular/password channel: ephemeral identity + proof via the
                // channel rule (account only for readOnly).
                return publishChannel(channelByStream(messageStreamId), messageStreamId, StreamConstants.P_MESSAGES, announce, password)
            }

            override fun myAddress(): String? = this@ChannelManager.myAddress()
            override fun username(): String? = this@ChannelManager.myUsername()
        }
    )

    // distinctBy: the stream id keys the channel-list LazyColumn, so ONE
    // duplicate row in the persisted store crashes the app at first paint
    // ("Key was already used") — seen live on a device whose old state had a
    // channel recorded twice. Deduping at load both boots cleanly and heals
    // the store on the next save.
    internal val _channels = MutableStateFlow(store.load().distinctBy { it.messageStreamId })

    /** Re-reads the list after the storage scope changes (account switch/guest). */
    fun reloadChannels() {
        scope.launch { channelSwitchMutex.withLock { closeCurrentInternal() } }
        _channels.value = store.load().distinctBy { it.messageStreamId }
        _channelOrder.value = store.loadOrder()
    }

    /** Forgets in-memory epoch-key state for every channel (persisted keys
     *  are untouched by [EpochKeyStore]'s own scoping) — call on an account
     *  switch so a different identity does not inherit already-decrypted
     *  state cached in this process. */
    fun resetEpochRuntimeState() { scope.launch { epochKeys.resetRuntimeState() } }
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    /** User's manual channel order (messageStreamIds); the list UI sorts by it. */
    private val _channelOrder = MutableStateFlow(store.loadOrder())
    val channelOrder: StateFlow<List<String>> = _channelOrder.asStateFlow()
    fun setChannelOrder(order: List<String>) {
        store.saveOrder(order)
        _channelOrder.value = order
    }

    internal val _current = MutableStateFlow<Channel?>(null)
    val current: StateFlow<Channel?> = _current.asStateFlow()

    /** True while the open channel is a non-persisted preview (web: preview mode). */
    internal val _isPreview = MutableStateFlow(false)
    val isPreview: StateFlow<Boolean> = _isPreview.asStateFlow()

    /**
     * A just-joined channel without an on-chain name whose entry flow did not
     * ask the user for one: the UI offers the local name + classification
     * panel over the opening channel. Cleared on save or dismiss.
     */
    private val _pendingLocalIdentity = MutableStateFlow<Channel?>(null)
    val pendingLocalIdentity: StateFlow<Channel?> = _pendingLocalIdentity.asStateFlow()

    fun dismissLocalIdentity() { _pendingLocalIdentity.value = null }

    /**
     * Local name + classification write for a joined channel. Never touches
     * the chain; blank name keeps the current one. Propagated by device sync
     * like a DM nickname.
     */
    fun setLocalChannelIdentity(streamId: String, name: String?, classification: String?) {
        _pendingLocalIdentity.value = null
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() }
        _channels.value = _channels.value.map { ch ->
            if (ch.messageStreamId == streamId) ch.copy(
                name = trimmed ?: ch.name,
                classification = classification ?: ch.classification
            ) else ch
        }
        store.save(_channels.value)
        if (_current.value?.messageStreamId == streamId) {
            _current.value = _channels.value.find { it.messageStreamId == streamId } ?: _current.value
        }
        onLocalStateChanged()
    }

    internal val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    /** messageId -> emoji -> set of senderIds (in RAM, like the web). */
    private val _reactions = MutableStateFlow<Map<String, Map<String, Set<String>>>>(emptyMap())
    val reactions: StateFlow<Map<String, Map<String, Set<String>>>> = _reactions.asStateFlow()

    private val _onlineCount get() = presence._onlineCount
    val onlineCount: StateFlow<Int> = _onlineCount.asStateFlow()

    private val _presenceReady get() = presence._presenceReady
    val presenceReady: StateFlow<Boolean> = _presenceReady.asStateFlow()

    // Moderation (ADMIN_STATE on -3/P0). Latest-wins by rev; owner-authored only.
    /**
     * [senderName]/[ensName] mirror the web's pin snapshot fields (web
     * channels.js pinMessage) — frozen at pin time, same as [text]/[sender].
     */
    data class Pin(
        val targetId: String, val text: String, val sender: String,
        val senderName: String? = null, val ensName: String? = null,
        /** Wall-clock of the pin itself. Carried through republishes untouched. */
        val pinnedAt: Long = 0L
    )
    private val _pins get() = admin._pins
    val pins: StateFlow<List<Pin>> = _pins.asStateFlow()
    private val _hiddenIds get() = admin._hiddenIds
    val hiddenIds: StateFlow<Set<String>> = _hiddenIds.asStateFlow()
    private val _bannedMembers get() = admin._bannedMembers
    val bannedMembers: StateFlow<Set<String>> = _bannedMembers.asStateFlow()

    private val adminRevs get() = admin.adminRevs
    private val adminTs get() = admin.adminTs
    private val adminLoaded get() = admin.adminLoaded

    /**
     * Images whose message was deleted (web `deletedImageIds`, media.js:180).
     * Chunks and manifests for these ids are refused on arrival — without the
     * tombstone a paginated resend overlapping the delete resurrected the
     * image. Session-global on purpose: NOT cleared on channel switch, since
     * a deleted image is deleted everywhere.
     */
    internal val deletedImageIds = java.util.Collections.synchronizedSet(HashSet<String>())

    // Scroll-up pagination (web: channel.oldestTimestamp / hasMoreHistory / loadingHistory).
    private val _waitingForKeys = MutableStateFlow(false)
    /** Epoch channel open without a usable current key — the UI's "waiting
     *  for channel keys" state, cleared on adoption. */
    val waitingForKeys: StateFlow<Boolean> = _waitingForKeys.asStateFlow()

    /**
     * The CURRENT user's standing on the CURRENT channel's PAID gate (N-F).
     * Null = not a paid gate, gate owner, or unresolved. `accessNow` false
     * with an elapsed `paidUntil` is the "subscription expired" state — the
     * key layer cannot signal it (refusals are silent), only the chain can.
     */
    data class PaidStatus(
        /** Subscription end, unix seconds (0 = never paid). */
        val paidUntil: Long,
        /** checkAccess for us — true without a live subscription = moderator. */
        val accessNow: Boolean
    )

    private val _paidStatus = MutableStateFlow<PaidStatus?>(null)
    val paidStatus: StateFlow<PaidStatus?> = _paidStatus.asStateFlow()

    private suspend fun resolvePaidStatus(channel: Channel): PaidStatus? {
        val gate = channel.takeIf { it.type == "gated" }?.gateAddress ?: return null
        return try {
            val info = bridge.call("gateInfo", JSONObject().put("gate", gate))
            if (info.optInt("mode", GATE_MODE_NONE) != GATE_MODE_PAID) return null
            val me = myAddress() ?: return null
            if (info.optString("owner").equals(me, ignoreCase = true)) return null
            val until = bridge.call("gatePaidUntil", JSONObject()
                .put("gate", gate).put("user", me))
                .optString("paidUntil", "0").toLongOrNull() ?: 0L
            val accessNow = until * 1000 > System.currentTimeMillis() ||
                bridge.call("gateCheckAccess", JSONObject()
                    .put("gate", gate).put("user", me)).optBoolean("access", false)
            PaidStatus(until, accessNow)
        } catch (e: Exception) {
            Log.w(TAG, "paid status read failed: ${e.message}")
            null
        }
    }

    /** Re-read the paid standing for the current channel (after a renewal). */
    suspend fun refreshPaidStatus() {
        val channel = _current.value ?: run { _paidStatus.value = null; return }
        val status = resolvePaidStatus(channel)
        if (_current.value?.messageStreamId == channel.messageStreamId) {
            _paidStatus.value = status
        }
    }

    /**
     * One immediate KEY_REQUEST for the current channel — the renewal flow's
     * key refresh: requests sent while expired were refused silently, so the
     * backoff would otherwise sit on a 60s interval after the payment.
     */
    suspend fun requestChannelKeysNow() {
        val channel = _current.value ?: return
        if (!isEpochChannel(channel)) return
        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        runCatching {
            epochKeys.retryRequestIfWaiting(channel.messageStreamId, keysId)
        }
    }

    private val _hasMoreHistory = MutableStateFlow(false)
    val hasMoreHistory: StateFlow<Boolean> = _hasMoreHistory.asStateFlow()
    private val _loadingHistory = MutableStateFlow(false)
    val loadingHistory: StateFlow<Boolean> = _loadingHistory.asStateFlow()
    /** Oldest content timestamp seen on P0 — the cursor for the next page. */
    @Volatile private var oldestTimestamp: Long = 0L
    /**
     * Identifies the current *viewing session*, not the channel: it advances on
     * every open and every close, so reopening the same channel also invalidates
     * the previous session's in-flight work.
     *
     * This is the web's fence (channels.js `switchGeneration`, bumped in
     * `setCurrentChannel`). Async work captures it before it suspends and calls
     * [stillCurrent] after every resumption, before touching shared state. We
     * already had the counter but only consulted it in the two pagination
     * paths, so a slow open could write channel A's history, pins, presence and
     * loading flags into channel B — which is exactly the contamination seen on
     * fast switches and slow networks.
     */
    @Volatile internal var switchGeneration = 0

    /**
     * False once the user has moved on. Any caller that suspended must check
     * this before writing to shared state, and discard its result if it fails.
     */
    internal fun stillCurrent(generation: Int) = switchGeneration == generation

    /**
     * The in-flight channel-open coroutine, cancelled when the next open
     * starts. This is an optimisation, not the correctness boundary: the broad
     * `catch (e: Exception)` blocks throughout this class swallow
     * `CancellationException`, so a cancelled open can still run to completion.
     * [stillCurrent] is what actually keeps its writes out.
     */
    private var openJob: Job? = null

    /**
     * Serialises channel transitions. Only the teardown/subscribe half is held
     * under it — see [prepareChannel]; the history loads run outside so a slow
     * resend never blocks the next switch.
     */
    internal val channelSwitchMutex = Mutex()
    /** targetId -> (override payload, publisher) for targets not loaded yet. */
    private val pendingOverrides = LinkedHashMap<String, Pair<JSONObject, String?>>()

    /** Ids the author deleted — see [applyOverride]. Reset per channel. */
    private val deletedIds = mutableSetOf<String>()

    /**
     * Who is typing, as an address plus whatever nickname rode along with the
     * signal. Deliberately NOT a pre-rendered label: the display name has to be
     * resolvable through ENS, and only the address can be looked up.
     */
    data class TypingPeer(val address: String, val nickname: String?)

    private val _typingFrom get() = presence._typingFrom
    val typingFrom: StateFlow<List<TypingPeer>> = _typingFrom.asStateFlow()


    private fun markTyping(address: String, nickname: String?) =
        presence.markTyping(address, nickname)

    private fun clearTyping() = presence.clearTyping()

    private val online get() = presence.online
    private val onlineNames get() = presence.onlineNames

    /** Who is online, for the web's online-users list (not just the count). */
    data class OnlineUser(val address: String, val nickname: String?)
    private val _onlineUsers get() = presence._onlineUsers
    val onlineUsers: StateFlow<List<OnlineUser>> = _onlineUsers.asStateFlow()
    private var presenceJob: Job?
        get() = presence.presenceJob
        set(value) { presence.presenceJob = value }
    private var adminPollJob: Job?
        get() = admin.adminPollJob
        set(value) { admin.adminPollJob = value }

    /** My own DM ephemeral inbox (`me/Pombo-DM-2`), where a peer's encrypted
     *  typing/presence lands. Set while a DM is open; null otherwise. */
    @Volatile private var myDmEphemeralId: String? = null

    /**
     * Resolved ENS names by lowercase address, published so every surface that
     * shows an address (pins, channel list, contacts, profile) prefers the ENS
     * name. Persistence, TTLs and in-flight dedup live in [EnsStore].
     */
    val ensNames: StateFlow<Map<String, String>> = ensStore.resolved

    /** Requests an ENS lookup for an address seen outside the message list. */
    fun ensureEns(address: String?) {
        if (address.isNullOrEmpty()) return
        if (!Regex("^0x[a-fA-F0-9]{40}$").matches(address)) return
        resolveEnsFor(address)
    }

    /**
     * Resolves the sender's ENS name + avatar and patches the rendered
     * messages. Name and avatar are cached separately (like identity.js), so a
     * failed avatar lookup doesn't get pinned by a successful name lookup.
     */
    internal fun resolveEnsFor(address: String) {
        val key = address.lowercase()
        scope.launch {
            val name = ensStore.name(address) {
                try {
                    bridge.call("resolveEns", JSONObject().put("address", address), 20_000)
                        .optStringOrNull("name")
                } catch (e: Exception) {
                    Log.w("PomboEns", "resolveEns bridge call failed for $address: ${e.message}")
                    null
                }
            }
            Log.d("PomboEns", "name ${address.take(10)}… -> $name")
            if (name == null) return@launch

            // First-name rule for DM rooms (ENS > nick > raw address): when
            // the ENS only resolves after the room was created with the
            // address fallback, upgrade it. A name the user or the sender's
            // nick already gave the room is left alone.
            val shortForm = address.take(6) + "…" + address.takeLast(4)
            _channels.value.find {
                it.type == "dm" &&
                    it.peerAddress?.equals(address, ignoreCase = true) == true &&
                    it.name.equals(shortForm, ignoreCase = true)
            }?.let { renameDmForContact(address, name) }

            // Raise the trust level too, not just the name. applyTrustLevel
            // runs once at verification and reads the ENS cache; when the name
            // only resolves afterwards (the usual order in a DM) the message
            // kept trustLevel 0 forever, so it showed the ENS name with the
            // plain "valid signature" tick instead of the ENS badge. 2 is
            // "trusted contact", which outranks ENS and must not be lowered.
            _messages.value = _messages.value.map {
                if (it.sender.equals(address, ignoreCase = true))
                    it.copy(ensName = name, trustLevel = if (it.trustLevel == 2) 2 else 1)
                else it
            }

            // An ENS name may carry an avatar record, which replaces the
            // generated one (web: getCachedENSAvatar in the renderer).
            val avatar = ensStore.avatar(address) {
                try {
                    bridge.call("resolveEnsAvatar", JSONObject().put("name", name), 20_000)
                        .optStringOrNull("url")
                } catch (e: Exception) {
                    Log.w("PomboEns", "resolveEnsAvatar bridge call failed for $name: ${e.message}")
                    null
                }
            }
            Log.d("PomboEns", "avatar $name -> $avatar")
            if (avatar == null) return@launch

            _messages.value = _messages.value.map {
                if (it.sender.equals(address, ignoreCase = true)) it.copy(ensAvatar = avatar) else it
            }
        }
    }

    // DM: my own inbox stream id (once set up) + cache of peer pubkeys.
    @Volatile private var myInboxId: String? = null

    /** Web: settings toggle "Channel Invites" — when off, P3 is not subscribed. */
    @Volatile var inviteNotificationsEnabled: Boolean = true

    /**
     * Web SettingsUI.handleNotificationsToggle: flips the flag and, if the
     * inbox is up, (un)subscribes the notification partition live. When the
     * bridge is down the flag alone is enough — subscribeMyInbox re-reads it
     * on the next connect.
     */
    fun setInviteNotifications(on: Boolean) {
        if (inviteNotificationsEnabled == on) return
        inviteNotificationsEnabled = on
        val inbox = myInboxId ?: return
        scope.launch {
            if (on) {
                subscribeQuiet(inbox, StreamConstants.P_NOTIFICATIONS)
                // Backfill what arrived while muted — divergence from the web,
                // which drops those invites forever.
                catchUpInvites(inbox)
            } else unsubscribeQuiet(inbox, StreamConstants.P_NOTIFICATIONS)
        }
    }
    private val peerPubKeys = HashMap<String, String>()

    // ==================== channel image (ADMIN -3 / P1) ====================

    /** Every known channel image, keyed by admin stream id (shared cache). */
    val channelImages: StateFlow<Map<String, ByteArray>> = imageStore.images

    /** Admin stream ids with an image fetch in flight — spinner vs fallback-avatar signal. */
    val channelImagesPending: StateFlow<Set<String>> = imageStore.pending

    /** Decoded channel image for the open channel, or null when unset. */
    private val _channelImage get() = images._channelImage
    val channelImage: StateFlow<ByteArray?> = _channelImage.asStateFlow()
    private var channelImageRev: Int
        get() = images.channelImageRev
        set(value) { images.channelImageRev = value }

    fun ensureChannelImage(adminStreamId: String, password: String? = null, label: String = adminStreamId) =
        images.ensureChannelImage(adminStreamId, password, label)

    private suspend fun resendImagePayload(
        adminStreamId: String,
        password: String?,
        label: String = adminStreamId,
        timeoutMs: Long = 30_000
    ): JSONObject? = images.resendImagePayload(adminStreamId, password, label, timeoutMs)

    private fun loadChannelImage(channel: Channel) = images.loadChannelImage(channel)

    suspend fun publishChannelImage(bytes: ByteArray, mime: String): Boolean =
        images.publishChannelImage(bytes, mime)

    /** Whether this channel's name/description live in PUBLIC on-chain
     *  metadata — visible channels of any access type. Hidden channels keep
     *  their name off-chain: writing it into the public stream registry
     *  would leak a private channel's name (N-D parity with the web). */
    fun hasPublicMetadata(channel: Channel): Boolean = channel.exposure == "visible"

    /**
     * Renames / re-describes the channel (web: streamr.js updateStreamMetadata).
     * Owner-only. Visible channels: a single on-chain transaction (gas).
     * Hidden channels: LOCAL rename only, propagated by sync like a DM's.
     */
    suspend fun updateChannelMetadata(name: String?, description: String?) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can edit the channel")
        if (hasPublicMetadata(channel)) {
            val args = JSONObject().put("streamId", channel.messageStreamId)
            name?.trim()?.takeIf { it.isNotEmpty() }?.let { args.put("name", it) }
            description?.let { args.put("description", it.trim()) }
            bridge.call("updateStreamMetadata", args, 120_000)
        }

        val updated = channel.copy(
            name = name?.trim()?.ifEmpty { null } ?: channel.name,
            description = description?.trim() ?: channel.description,
            // Stamped so the Graph refresh below does not revert this edit while
            // the subgraph is still catching up.
            metaUpdatedAt = System.currentTimeMillis()
        )
        _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
        store.save(_channels.value)
        _current.value = updated
        // Renames ride the channels slice of device sync (like a DM's) — the
        // push needs scheduling, not just the local save.
        onLocalStateChanged()
    }

    /**
     * Pulls channel names/descriptions from on-chain metadata (web:
     * refreshChannelMetadataFromGraph). Two things depend on it: admins renaming
     * a channel propagate to everyone who already joined, and a channel joined
     * by raw stream ID gets its real name instead of the ID segment the join
     * falls back to.
     */
    suspend fun refreshChannelMetadataFromGraph(): Boolean {
        var changed = false
        val updates = mutableMapOf<String, Channel>()
        for (channel in _channels.value) {
            if (channel.type == "dm") continue
            val info = try {
                com.pombo.android.core.GraphApi.getChannelInfo(channel.messageStreamId)
            } catch (e: Exception) {
                null
            } ?: continue
            // Indexing lag: never overwrite a local admin edit with older data.
            if (channel.metaUpdatedAt != null && info.updatedAt <= channel.metaUpdatedAt) continue

            var next = channel
            if (!info.name.isNullOrEmpty() && info.name != channel.name) next = next.copy(name = info.name)
            // Hidden channels keep their description off-chain, so only trust
            // the on-chain one for visible channels.
            if (info.exposure == "visible" && info.description != channel.description) {
                next = next.copy(description = info.description)
            }
            if (next !== channel) { updates[channel.messageStreamId] = next; changed = true }
        }
        if (changed) {
            _channels.value = _channels.value.map { updates[it.messageStreamId] ?: it }
            store.save(_channels.value)
            // Keep the open channel's header in sync with the list.
            _current.value?.let { cur -> updates[cur.messageStreamId]?.let { _current.value = it } }
        }
        return changed
    }

    /**
     * Members of a Closed channel, read from The Graph's permission list
     * (web: graphAPI.getStreamMembers). Owner first, then the rest.
     *
     * Gated (N-D): the Graph's grantee list is exactly the clone — membership
     * lives on the GATE. Candidates: the local cache (owner-minted members)
     * plus the KEY_REQUEST authors seen on -4 (holders and pay() members
     * never pass through the owner, but every reader must request keys). Their
     * CURRENT state comes from the contract; `access` is the mode-aware
     * membership signal (allowlist only means Closed).
     */
    /**
     * Members-panel row. The roles come from the CONTRACT (the web reads the
     * same flags): stream permissions say nothing about a gate moderator,
     * whose only grant belongs to the clone.
     */
    data class MemberRow(
        val address: String,
        val paidUntil: Long = 0L,
        val isOwner: Boolean = false,
        val moderator: Boolean = false
    )

    /** Full contract flags for one candidate of the open gated channel. */
    data class GateMemberFlags(
        val address: String,
        val isOwner: Boolean,
        val moderator: Boolean,
        val access: Boolean,
        val banned: Boolean,
        val everMember: Boolean,
        val erased: Boolean,
        val paidUntil: Long
    )

    suspend fun gateMemberFlags(): List<GateMemberFlags> = admin.gateMemberFlags()

    suspend fun gateBannedMembers(): List<String> = admin.gateBannedMembers()

    private suspend fun rotateForPendingBans(channel: Channel) = admin.rotateForPendingBans(channel)

    suspend fun channelMembers(): List<MemberRow> = admin.channelMembers()

    suspend fun canManageGate(): Boolean = admin.canManageGate()

    suspend fun gateAccessLabel(): String? = admin.gateAccessLabel()


    /**
     * Explore card access info (N-D), as the 3-line pricing anatomy the
     * cards render: VERB (Subscribe = recurring payment, Hold = mere
     * possession — the semantic split users must get) / VALUE / QUALIFIER
     * ('in your wallet' says "you pay nothing"). POL for WPOL-priced
     * gates; NONE never lists (null parts); null result = unreadable.
     */
    data class GateCardInfo(
        val mode: Int, val verb: String?, val value: String?, val qualifier: String?
    )

    suspend fun gateCardInfo(gateAddress: String): GateCardInfo? = admin.gateCardInfo(gateAddress)

    suspend fun currentGateMode(): Int? = admin.currentGateMode()


    /** Everything the gate entry screen renders, in one fetch. */
    data class GateEntryInfo(
        val gateAddress: String, val mode: Int, val token: String,
        val minBalance: String, val price: String, val durationSeconds: Long,
        val tokenSymbol: String,
        /** null = ERC-721 collection (no decimals getter) — whole units. */
        val tokenDecimals: Int?,
        /** TOKEN/NFT: the user's raw-unit balance of the gate asset. */
        val balance: String,
        /** PAID: subscription end, unix seconds (0 = never paid). */
        val paidUntil: Long
    )

    suspend fun gateEntryInfo(gateAddress: String): GateEntryInfo = admin.gateEntryInfo(gateAddress)

    suspend fun gateTokenMeta(token: String): Pair<String, Int?> = admin.gateTokenMeta(token)

    suspend fun gateTokenBalance(token: String, user: String? = null): String =
        admin.gateTokenBalance(token, user)

    suspend fun gateInvalidateAccess(gateAddress: String) = admin.gateInvalidateAccess(gateAddress)

    suspend fun gatePay(gateAddress: String) = admin.gatePay(gateAddress)

    suspend fun resolveMemberInput(input: String): String? = admin.resolveMemberInput(input)

    suspend fun addMember(address: String) = admin.addMember(address)

    suspend fun removeMember(address: String) = admin.removeMember(address)

    suspend fun rekeyPublishKey(): Int = admin.rekeyPublishKey()

    suspend fun streamPermissions(): List<com.pombo.android.core.GraphApi.StreamPermission> = admin.streamPermissions()

    suspend fun setMemberGrant(address: String, canGrant: Boolean) =
        admin.setMemberGrant(address, canGrant)


    /** Storage nodes actually assigned to the message stream. */
    suspend fun storageNodes(): List<String> = try {
        val channel = _current.value ?: return emptyList()
        val res = bridge.call("getStreamStorageInfo",
            JSONObject().put("streamId", channel.messageStreamId), 30_000)
        val arr = res.optJSONArray("nodes") ?: JSONArray()
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
    } catch (e: Exception) { emptyList() }

    /** The official Pombo storage cluster (web: STREAM_CONFIG.NODE_ADDRESS). */
    val pomboStorageNode: String get() = STORAGE_NODE

    /**
     * A storage node as the channel sees it. `onMessage`/`onAdmin` track the two
     * streams separately so a half-applied assignment shows as "partial" instead
     * of looking healthy (web: getChannelStorageInfo).
     */
    data class StorageNode(
        val address: String,
        val onMessage: Boolean,
        val onAdmin: Boolean,
        val onKeys: Boolean = false,
        /** False on channels with no -4, where onKeys can never be true. */
        val hasKeys: Boolean = false,
        /**
         * False when a stream lookup failed. Absence then proves nothing, and
         * calling it partial sends the admin to spend gas on a repair that
         * may not be needed.
         */
        val allStreamsRead: Boolean = true
    ) {
        val partial: Boolean get() =
            allStreamsRead && !(onMessage && onAdmin && (!hasKeys || onKeys))
    }

    data class StorageInfo(
        val enabled: Boolean,
        val nodes: List<StorageNode>,
        /** The message stream's, which is the one figure the panel shows. */
        val storageDays: Int?,
        val adminStorageDays: Int? = null,
        val keysStorageDays: Int? = null,
        /** False when the stored streams hold different retentions. */
        val retentionInSync: Boolean = true,
        val hasKeysStream: Boolean = false
    )

    /**
     * What the chain currently says about one stored stream.
     *
     * `read` is false when the lookup failed. Every decision that would SKIP
     * a write has to treat that as nothing known and write anyway: a wrong
     * skip leaves the stream diverged with the UI reporting success, while a
     * redundant write only costs gas.
     */
    data class StoredStream(
        val id: String,
        val kind: String,
        val read: Boolean,
        val nodes: List<String>,
        val storageDays: Int?
    ) {
        fun carries(address: String) = nodes.any { it.equals(address, ignoreCase = true) }
    }

    private suspend fun readStoredStreams(channel: Channel): List<StoredStream> {
        val kinds = listOf("message", "admin", "keys")
        return storedStreams(channel).mapIndexed { i, id ->
            val res = streamStorage(id)
            StoredStream(id, kinds.getOrElse(i) { "extra" }, res != null,
                res?.first ?: emptyList(), res?.second)
        }
    }

    /**
     * Apply an operation to only the stored streams that still need it, then
     * read back to confirm.
     *
     * Every storage operation is one on-chain transaction per stream, so a
     * channel already half-configured should cost what is missing, not the
     * whole set again, and the count the user approves has to be the real one.
     *
     * @return per-stream outcome ("unchanged" | "applied" | "failed"), how
     *         many transactions were sent, and whether the read-back agrees
     *         (null when nothing was sent).
     */
    private suspend fun applyToStoredStreams(
        channel: Channel,
        needs: (StoredStream) -> Boolean,
        apply: suspend (String) -> Unit
    ): StorageWriteResult {
        val before = readStoredStreams(channel)
        val results = LinkedHashMap<String, String>()
        var sent = 0

        // Sequential to avoid nonce conflicts (REPLACEMENT_UNDERPRICED).
        for (stream in before) {
            if (!needs(stream)) { results[stream.kind] = "unchanged"; continue }
            sent += 1
            results[stream.kind] = try {
                apply(stream.id); "applied"
            } catch (e: Exception) {
                Log.w(TAG, "storage write failed on ${stream.kind} ${stream.id.takeLast(20)}: ${e.message}")
                "failed"
            }
        }

        if (sent == 0) return StorageWriteResult(results, 0, null)

        val after = readStoredStreams(channel)
        val verified = writesConverged(after, needs)
        if (!verified) Log.w(TAG, "storage write did not converge; streams still out of sync")
        return StorageWriteResult(results, sent, verified)
    }

    data class StorageWriteResult(
        val results: Map<String, String>,
        val sent: Int,
        val verified: Boolean?
    ) {
        val failed: Boolean get() = results.values.any { it == "failed" }
        val ok: Boolean get() = !failed && verified != false
    }

    /** Null when the lookup failed, which is not the same as "no nodes". */
    private suspend fun streamStorage(streamId: String): Pair<List<String>, Int?>? = try {
        val res = bridge.call("getStreamStorageInfo", JSONObject().put("streamId", streamId), 30_000)
        // The bridge answers a failed lookup with the same empty shape a
        // stream with no storage returns, so `ok` is the only thing that
        // tells them apart.
        if (!res.optBoolean("ok", false)) {
            Log.w(TAG, "storage lookup did not answer for " + streamId.takeLast(20))
            null
        } else {
            val arr = res.optJSONArray("nodes") ?: JSONArray()
            val nodes = (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            nodes to (if (res.isNull("storageDays")) null else res.optInt("storageDays").takeIf { it > 0 })
        }
    } catch (e: Exception) {
        Log.w(TAG, "storage lookup failed for " + streamId.takeLast(20) + ": " + e.message)
        null
    }

    /**
     * Every stored stream of the channel: -1, -3, and -4 on gated. The
     * ephemeral -2 never has storage by design, and the DM inbox is an
     * account-level stream rather than part of any one conversation.
     */
    suspend fun channelStorageInfo(): StorageInfo {
        val channel = _current.value ?: return StorageInfo(false, emptyList(), null)
        val keysStreamId = if (channel.type == "gated") {
            channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        } else ""

        val msg = streamStorage(channel.messageStreamId)
        val admin = if (channel.adminStreamId.isNotEmpty()) streamStorage(channel.adminStreamId) else null
        val keys = if (keysStreamId.isNotEmpty()) streamStorage(keysStreamId) else null

        val (msgNodes, days) = msg ?: (emptyList<String>() to null)
        val (adminNodes, adminDays) = admin ?: (emptyList<String>() to null)
        val (keysNodes, keysDays) = keys ?: (emptyList<String>() to null)

        val hasKeys = keysStreamId.isNotEmpty()
        // Every stream we are comparing has to have actually answered before
        // a node's absence from one of them means anything.
        val allStreamsRead = msg != null &&
            (channel.adminStreamId.isEmpty() || admin != null) &&
            (!hasKeys || keys != null)
        val byAddr = linkedMapOf<String, StorageNode>()
        fun mark(addresses: List<String>, set: (StorageNode) -> StorageNode) {
            addresses.forEach { n ->
                val k = n.lowercase()
                byAddr[k] = set(byAddr[k] ?: StorageNode(
                    n, onMessage = false, onAdmin = false,
                    hasKeys = hasKeys, allStreamsRead = allStreamsRead))
            }
        }
        mark(msgNodes) { it.copy(onMessage = true) }
        mark(adminNodes) { it.copy(onAdmin = true) }
        mark(keysNodes) { it.copy(onKeys = true) }

        val nodes = byAddr.values.toList()
        // A lookup that times out reads as "not known", which is silence the
        // panel cannot distinguish from agreement — so say what came back.
        Log.i(TAG, "Storage info: retention=[message=$days admin=$adminDays keys=$keysDays] " +
            "inSync=${retentionInSync(days, adminDays, keysDays)} hasKeys=$hasKeys " +
            "allRead=$allStreamsRead nodes=${nodes.size}")
        return StorageInfo(
            enabled = nodes.isNotEmpty(),
            nodes = nodes,
            storageDays = days,
            adminStorageDays = adminDays,
            keysStorageDays = keysDays,
            retentionInSync = retentionInSync(days, adminDays, keysDays),
            hasKeysStream = hasKeys
        )
    }

    /**
     * Assigns a storage node to every stored stream. Sequential, not parallel: two
     * on-chain writes from one account race on the nonce and the second gets
     * REPLACEMENT_UNDERPRICED.
     */
    suspend fun addStorageNode(address: String, storageDays: Int? = null): StorageWriteResult {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can change storage")
        val addr = address.trim()
        require(Regex("^0x[a-fA-F0-9]{40}$").matches(addr)) { "Invalid storage node address" }

        val args = { streamId: String ->
            JSONObject().put("streamId", streamId).put("nodeAddress", addr).apply {
                storageDays?.let { put("storageDays", it) }
            }
        }
        val out = applyToStoredStreams(
            channel,
            needs = { needsNodeAdd(it, addr) },
            apply = { bridge.call("addToStorageNode", args(it), 180_000) }
        )
        markStorage(channel, enabled = true)
        return out
    }

    suspend fun removeStorageNode(address: String): StorageWriteResult {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can change storage")
        val addr = address.trim()
        val out = applyToStoredStreams(
            channel,
            needs = { needsNodeRemove(it, addr) },
            apply = {
                bridge.call("removeFromStorageNode",
                    JSONObject().put("streamId", it).put("nodeAddress", addr), 180_000)
            }
        )
        markStorage(channel, enabled = channelStorageInfo().enabled)
        return out
    }

    /**
     * Set the retention of every stored stream that is not already at [days].
     *
     * Only the streams that differ are written, so re-saving the same figure
     * to heal a divergence costs one transaction per diverged stream instead
     * of one per stream.
     */
    suspend fun setStorageDays(days: Int): StorageWriteResult {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can change storage")
        require(days >= 1) { "Retention must be a positive number of days" }

        val out = applyToStoredStreams(
            channel,
            needs = { needsRetentionWrite(it, days) },
            apply = {
                bridge.call("setStorageDays",
                    JSONObject().put("streamId", it).put("storageDays", days), 180_000)
            }
        )

        // Keep the local copies in sync, per stream: each is a separate
        // transaction and the Graph lags them by enough to answer a reopen
        // with the old retention. A stream already at `days` counts as in
        // sync too, which is the whole point of not writing to it.
        fun settled(kind: String) = out.results[kind] == "applied" || out.results[kind] == "unchanged"
        val updated = channel.copy(
            storageDays = if (settled("message")) days else channel.storageDays,
            adminStorageDays = if (settled("admin")) days else channel.adminStorageDays,
            keysStorageDays = if (settled("keys")) days else channel.keysStorageDays
        )
        if (updated != channel) {
            _channels.value = _channels.value.map {
                if (it.messageStreamId == updated.messageStreamId) updated else it
            }
            store.save(_channels.value)
            if (_current.value?.messageStreamId == updated.messageStreamId) _current.value = updated
        }
        return out
    }

    /**
     * Refresh the cached retention of the stored streams other than -1, whose
     * value [Channel.storageDays] already holds. The chain is the system of
     * record and the record is a warm-start cache of it: each stream is
     * configured by its own transaction and any of them can fail alone.
     *
     * Resolved on open and cached because the key-responder sweep that reads
     * the -4 value runs every 45s, far too often to look up. Owner-only:
     * every consumer of these values is admin-side.
     *
     * Never throws, and returns the record to decide with.
     */
    private suspend fun refreshStreamRetentions(channel: Channel): Channel {
        val adminDays = if (channel.adminStreamId.isNotEmpty()) {
            com.pombo.android.core.GraphApi.streamRetention(channel.adminStreamId)
        } else null
        val keysId = if (channel.type == "gated") {
            channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        } else ""
        val keysDays = if (keysId.isNotEmpty()) {
            com.pombo.android.core.GraphApi.streamRetention(keysId)
        } else null

        val current = _channels.value.firstOrNull { it.messageStreamId == channel.messageStreamId }
            ?: channel
        val updated = current.copy(
            adminStorageDays = adminDays ?: current.adminStorageDays,
            keysStorageDays = keysDays ?: current.keysStorageDays
        )
        // One line per open: the sweep that consumes these is headless and
        // silent, so a wrong value is invisible until the announces are gone.
        Log.d(TAG, "Stream retentions read=[admin=$adminDays keys=$keysDays] " +
            "using=[message=${updated.storageDays} admin=${adminRetentionDays(updated)} " +
            "keys=${keysRetentionDays(updated)}]")
        if (updated == current) return current

        _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
        store.save(_channels.value)
        if (_current.value?.messageStreamId == updated.messageStreamId) _current.value = updated
        return updated
    }

    private fun markStorage(channel: Channel, enabled: Boolean) {
        if (channel.storageEnabled == enabled) return
        val updated = channel.copy(storageEnabled = enabled)
        _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
        store.save(_channels.value)
        if (_current.value?.messageStreamId == updated.messageStreamId) _current.value = updated
    }

    /**
     * Full streamr.js createStream flow: 3 streams serially (nonce),
     * permissions per type, storage on -1/-3, password challenge on -3/P2.
     */
    suspend fun createChannel(
        name: String,
        type: String,                    // 'public' | 'password' | 'gated'
        password: String? = null,
        exposure: String = "hidden",
        readOnly: Boolean = false,
        description: String = "",
        language: String = "en",
        category: String = "general",
        /** Closed channels: addresses granted access on-chain at creation. */
        members: List<String> = emptyList(),
        /** Local-only grouping for closed channels ('personal' | 'community'). */
        classification: String? = null,
        /** 'streamr' (Pombo cluster) or 'custom'. */
        storageProvider: String = "streamr",
        /** Only meaningful when storageProvider == 'custom'. */
        customStorageAddress: String? = null,
        /** Retention in days (web slider: 1..365, default 180). */
        storageDays: Int = 180,
        /** Gate mode (N-D): NONE (Closed), TOKEN, NFT or PAID. */
        gateMode: Int = GATE_MODE_NONE,
        /** TOKEN/NFT/PAID: asset or payment token contract (lowercase 0x…). */
        gateToken: String? = null,
        /** TOKEN: raw-unit minimum balance (decimal string — uint256). */
        gateMinBalance: String? = null,
        /** PAID: raw-unit price (decimal string — uint256). */
        gatePrice: String? = null,
        /** PAID: subscription period in SECONDS. */
        gateDuration: Long? = null,
        /** Author visibility for gated channels ('members' | 'everyone'), IMMUTABLE. */
        authorMode: String = "members",
        /** Called once per on-chain step so the UI can drive the progress ring. */
        onProgress: () -> Unit = {}
    ): Channel {
        val addr = myAddress() ?: throw IllegalStateException("No identity")
        require(type != "password" || !password.isNullOrEmpty()) { "Password channel requires a password" }

        // Gated (N-C): the gate clone comes FIRST — its address goes into the
        // -1 metadata and receives every stream grant. One factory tx; the
        // creator becomes the gate owner and is everMember from block one.
        // N-D: the mode and its params come from the caller — the UI already
        // validated them against PomboGate.initialize's per-mode rules (a bad
        // combination reverts InvalidParams after the deploy gas was spent).
        val gateAddress: String? = if (type == "gated") {
            val createArgs = JSONObject().put("mode", gateMode)
            gateToken?.let { createArgs.put("token", it) }
            gateMinBalance?.let { createArgs.put("minBalance", it) }
            gatePrice?.let { createArgs.put("price", it) }
            gateDuration?.let { createArgs.put("duration", it) }
            val res = bridge.call("gateCreate", createArgs, 180_000)
            val gate = res.optString("gate").lowercase().ifEmpty { null }
                ?: throw IllegalStateException("gateCreate returned no clone address")
            Log.i(TAG, "Gate clone created: " + gate)
            onProgress()
            gate
        } else null

        // Members-only author visibility (the default for gated): mint the
        // SHARED publish key now so its address rides the permission batch.
        val sharedPub = if (type == "gated" && authorMode == "members")
            epochKeys.mintPublishKey() else null

        val base = "${addr.lowercase()}/${PomboCrypto.randomHex(8)}"
        val messageStreamId = "$base${StreamConstants.SUFFIX_MESSAGE}"
        val ephemeralStreamId = "$base${StreamConstants.SUFFIX_EPHEMERAL}"
        val adminStreamId = "$base${StreamConstants.SUFFIX_ADMIN}"
        val keysStreamId = "$base${StreamConstants.SUFFIX_KEYS}"
        // Closed channels are never discoverable: exposure is forced to
        // 'hidden' — honouring a caller-supplied 'visible' would publish the
        // channel name and description in PLAINTEXT on-chain metadata for a
        // channel whose entire point is that membership is private. N-D:
        // token/NFT/paid gates are the opposite — storefronts — so only the
        // NONE mode keeps the clamp (mirrors web handleCreate).
        val effectiveExposure =
            if (type == "gated" && gateMode == GATE_MODE_NONE) "hidden"
            else exposure
        val visible = effectiveExposure == "visible"

        // Pombo metadata in the description (same short keys AND the same key
        // order as the web: a,v,n,t,e,r,d,l,c,ts — streamr.js:327-339).
        val msgMeta = JSONObject()
            .put("a", "pombo").put("v", "1")
            .put("n", if (visible) name else JSONObject.NULL)
            .put("t", type).put("e", effectiveExposure).put("r", readOnly)
        // Gated: the clone address — the system of record joins and repairs
        // read (web streamr.js metadata g field)
        gateAddress?.let { msgMeta.put("g", it) }
        // Author visibility: 1 = Members only. Absent = Everyone, which is
        // what every channel created before the flag existed is. IMMUTABLE.
        if (sharedPub != null) msgMeta.put("m", 1)
        if (visible) {
            msgMeta.put("d", description).put("l", language).put("c", category)
        }
        msgMeta.put("ts", System.currentTimeMillis())
        val ephMeta = JSONObject().put("a", "pombo").put("v", "1").put("ln", messageStreamId)
        val admMeta = JSONObject().put("a", "pombo").put("v", "1").put("ln", messageStreamId).put("k", "admin")
        val keysMeta = JSONObject().put("a", "pombo").put("v", "1").put("ln", messageStreamId).put("k", "keys")

        // Streams SERIALLY (parallel causes on-chain nonce conflicts) with retries
        createStreamRetry(messageStreamId, msgMeta.toString(), StreamConstants.MSG_PARTITIONS); onProgress()
        createStreamRetry(ephemeralStreamId, ephMeta.toString(), StreamConstants.EPH_PARTITIONS); onProgress()
        createStreamRetry(adminStreamId, admMeta.toString(), StreamConstants.ADMIN_PARTITIONS); onProgress()
        // Keys stream (-4): gated only. Lives outside -3 on purpose — any
        // member must publish KEY_REQUEST/KEY_WRAP here, while -3 stays
        // owner-only publish (web streamr.js createStream, N-A).
        if (type == "gated") {
            createStreamRetry(keysStreamId, keysMeta.toString(), StreamConstants.KEYS_PARTITIONS); onProgress()
        }

        val publicRW = JSONArray().put(JSONObject().put("public", true).put("permissions", JSONArray(listOf("subscribe", "publish"))))
        val publicRead = JSONArray().put(JSONObject().put("public", true).put("permissions", JSONArray(listOf("subscribe"))))
        when (type) {
            "public", "password" -> {
                setPermissionsRetry(messageStreamId, if (readOnly) publicRead else publicRW); onProgress()
                setPermissionsRetry(ephemeralStreamId, publicRW); onProgress()   // presence needs public publish
                setPermissionsRetry(adminStreamId, publicRead); onProgress()     // publish is the owner\x27s by ownership
            }
            "gated" -> {
                // ONE grantee for every stream: the gate clone (N-C, Q7).
                // Members prove access through the contract (ERC-1271), so
                // membership changes are gate transactions, not stream txs.
                // -3 is the exception: the clone is SUBSCRIBE-only there —
                // PUBLISH stays the owner's (creator keeps registry perms),
                // so the transport enforces owner-only admin writes. The
                // owner publishes -3 as the ACCOUNT; their address is the
                // streamId prefix already.
                val clonePerms = JSONArray().put(JSONObject()
                    .put("userId", gateAddress)
                    .put("permissions", JSONArray(listOf("subscribe", "publish"))))
                // Members-only: -1/-2 also grant the SHARED publish key's
                // address — every member publishes under it, so the transport
                // carries no authorship. -4 keeps clone-only (KEY_REQUESTs
                // must name the requester) and -3 stays owner-published.
                // The shared key only ever writes: reading is the clone's job
                // (members subscribe through ERC-1271), so it gets PUBLISH
                // alone. Same grant shape on re-key.
                val contentPerms = if (sharedPub != null) JSONArray().apply {
                    put(JSONObject().put("userId", gateAddress)
                        .put("permissions", JSONArray(listOf("subscribe", "publish"))))
                    put(JSONObject().put("userId", sharedPub.address)
                        .put("permissions", JSONArray(listOf("publish"))))
                } else clonePerms
                val cloneSubOnly = JSONObject()
                    .put("userId", gateAddress)
                    .put("permissions", JSONArray(listOf("subscribe")))
                // Visible gated channels are storefronts: -3 gains public
                // SUBSCRIBE so non-members (Explore) can read the channel
                // image. P0 (ADMIN_STATE) stays an epoch envelope — the
                // public only ever sees ciphertext. Hidden channels keep the
                // clone as the sole grantee.
                val adminPerms = if (visible) JSONArray().apply {
                    put(cloneSubOnly)
                    put(JSONObject().put("public", true)
                        .put("permissions", JSONArray(listOf("subscribe"))))
                } else JSONArray().put(cloneSubOnly)
                setPermissionsRetry(messageStreamId, contentPerms); onProgress()
                setPermissionsRetry(ephemeralStreamId, contentPerms); onProgress()
                setPermissionsRetry(adminStreamId, adminPerms); onProgress()
                setPermissionsRetry(keysStreamId, clonePerms); onProgress()
                // Initial members: ONE gate transaction. Failure is non-fatal
                // (the owner re-adds from the members UI).
                if (members.isNotEmpty()) {
                    try {
                        bridge.call("gateAllowBatch", JSONObject()
                            .put("gate", gateAddress)
                            .put("users", JSONArray(members)), 180_000)
                    } catch (e: Exception) {
                        Log.w(TAG, "gateAllowBatch failed (re-add members later): ${e.message}")
                    }
                }
            }
        }

        // Storage on the -1 and -3 streams (never the ephemeral one).
        // Storage failure is NOT fatal: the web logs and continues so the user
        // still gets a working channel, just without retained history
        // (channels.js:418-420). Letting retry() throw here would abort a
        // creation whose three streams are already paid for on-chain.
        val storageNode = if (storageProvider == "custom" && !customStorageAddress.isNullOrBlank())
            customStorageAddress else STORAGE_NODE
        var storageOk = true
        // Only what actually landed: a stream whose retention transaction
        // never went through keeps the storage node default, and the record
        // must not claim otherwise. Both the TTL republish and the key
        // re-announce time themselves off these.
        var msgDays: Int? = null
        var admDays: Int? = null
        var keyDays: Int? = null
        try { msgDays = addStorageRetry(messageStreamId, storageNode, storageDays) } catch (e: Exception) {
            storageOk = false
            Log.w(TAG, "Storage on -1 failed; continuing without history: ${e.message}")
        }
        onProgress()
        try { admDays = addStorageRetry(adminStreamId, storageNode, storageDays) } catch (e: Exception) {
            Log.w(TAG, "Storage on -3 failed; continuing without admin history: ${e.message}")
        }
        onProgress()
        // -4 MUST have storage: joiners pull KEY_ANNOUNCEs from it, and
        // requests/wraps survive there until the counterpart comes online.
        // (The web shipped without this once — the announce was never retained
        // and joiners could not even ask for the key.)
        if (type == "gated") {
            try { keyDays = addStorageRetry(keysStreamId, storageNode, storageDays) } catch (e: Exception) {
                Log.w(TAG, "Storage on -4 failed; key exchange limited to live members: ${e.message}")
            }
            onProgress()
        }
        val missingRetention = listOfNotNull(
            if (storageOk && msgDays == null) "-1" else null,
            if (admDays == null) "-3" else null,
            if (type == "gated" && keyDays == null) "-4" else null
        )
        if (missingRetention.isNotEmpty()) {
            Log.w(TAG, "Retention not applied on ${missingRetention.joinToString(", ")} — " +
                "those streams keep the storage node default until it is set again")
        }

        // Password challenge on -3/P2 (payload encrypted with the password)
        if (type == "password" && password != null) {
            try {
                publishPasswordChallenge(adminStreamId, password)
            } catch (e: Exception) {
                Log.w(TAG, "Initial PASSWORD_CHALLENGE failed; retention loop will retry: ${e.message}")
            }
            // Joiners fail closed when the challenge is missing, and storage
            // attachment can race the publish — so keep republishing until a
            // resend actually returns it (web: _ensurePasswordChallengeRetained).
            scope.launch { ensurePasswordChallengeRetained(adminStreamId, password) }
        }

        val channel = Channel(
            messageStreamId = messageStreamId,
            ephemeralStreamId = ephemeralStreamId,
            adminStreamId = adminStreamId,
            keysStreamId = if (type == "gated") keysStreamId else "",
            name = name,
            type = type,
            createdBy = addr,
            joinedAt = System.currentTimeMillis(),
            password = password,
            // Owner first, then the invited addresses — the web seeds the
            // members array at creation (channels.js:458-460) so the Members
            // panel is populated straight away instead of starting empty.
            members = if (type == "gated")
                listOf(addr) + members.filter { !it.equals(addr, ignoreCase = true) }
            else emptyList(),
            storageEnabled = storageOk,
            storageProvider = storageProvider,
            storageDays = msgDays,
            adminStorageDays = admDays,
            keysStorageDays = keyDays,
            exposure = effectiveExposure,
            description = if (visible) description else "",
            language = if (visible) language else "",
            category = if (visible) category else "",
            classification = classification ?: if (type == "gated") "personal" else null,
            readOnly = readOnly,
            gateAddress = gateAddress,
            authorMode = if (type == "gated") authorMode else null
        )
        addChannel(channel)
        sharedPub?.let { epochKeys.adoptPublishKey(messageStreamId, it) }
        return channel
    }

    /** Publishes the password challenge on -3/P2, sealed with the password. */
    private suspend fun publishPasswordChallenge(adminStreamId: String, password: String) {
        val challenge = JSONObject()
            .put("type", "PASSWORD_CHALLENGE").put("v", 1)
            .put("magic", StreamConstants.PASSWORD_CHALLENGE_MAGIC)
            .put("ts", System.currentTimeMillis())
        publishContent(adminStreamId, StreamConstants.ADMIN_PASSWORD_CHALLENGE, challenge, password)
    }

    /**
     * Republish the challenge until the storage node has actually retained it.
     *
     * A single create-time publish is not enough: `addToStorageNode` and the
     * publish race, and if the node was not yet attached the message is simply
     * not kept. Joiners fail closed on a missing challenge, so without this
     * loop a freshly created password channel can be permanently unjoinable —
     * including by its own owner from another device.
     */
    private suspend fun ensurePasswordChallengeRetained(
        adminStreamId: String, password: String,
        maxAttempts: Int = 12, delayMs: Long = 5_000
    ) {
        repeat(maxAttempts) { attempt ->
            delay(delayMs)
            val retained = try {
                verifyPasswordChallenge(adminStreamId, password); true
            } catch (e: Exception) {
                // A wrong-password verdict here would mean we cannot read back
                // what we just wrote; either way the answer is "republish".
                false
            }
            if (retained) {
                Log.i(TAG, "PASSWORD_CHALLENGE retained after ${attempt + 1} cycle(s)")
                return
            }
            try { publishPasswordChallenge(adminStreamId, password) } catch (e: Exception) {
                Log.d(TAG, "PASSWORD_CHALLENGE republish #${attempt + 1} failed: ${e.message}")
            }
        }
        Log.w(TAG, "PASSWORD_CHALLENGE not retained after $maxAttempts attempts — joiners may see CHALLENGE_NOT_FOUND")
    }

    private suspend fun createStreamRetry(id: String, description: String, partitions: Int) = retry(7) {
        bridge.call("createStream", JSONObject()
            .put("id", id).put("description", description).put("partitions", partitions), 120_000)
    }

    internal suspend fun setPermissionsRetry(streamId: String, assignments: JSONArray) = retry(7) {
        bridge.call("setPermissions", JSONObject()
            .put("streamId", streamId).put("assignments", assignments), 120_000)
    }

    /**
     * Assigns the node and asks for the retention. Returns the retention that
     * ACTUALLY landed, null when it did not: the bridge attaches the node and
     * sets the TTL in one call, and the TTL is a separate transaction that
     * fails on its own.
     */
    private suspend fun addStorageRetry(
        streamId: String, nodeAddress: String = STORAGE_NODE, storageDays: Int = 180
    ): Int? {
        val res = retry(7) {
            bridge.call("addToStorageNode", JSONObject()
                .put("streamId", streamId).put("nodeAddress", nodeAddress)
                .put("storageDays", storageDays), 120_000)
        }
        return if (res.optBoolean("retentionApplied", false)) storageDays else null
    }

    private suspend fun <T> retry(times: Int, block: suspend () -> T): T {
        var delayMs = 1_000L
        var last: Exception? = null
        repeat(times) {
            try { return block() } catch (e: Exception) {
                last = e
                // Web utils/retry.js shouldRetry: only NETWORK/NONCE failures
                // can turn out differently on a retry. A deterministic revert
                // or an empty wallet used to be retried 7× — a minute of
                // waiting for seven identical failures.
                if (!com.pombo.android.core.ChainErrors.isTransient(e)) throw e
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
        throw last ?: IllegalStateException("retry failed")
    }

    /** channels.js joinChannel flow: permissions -> type -> password (fail-closed). */
    suspend fun joinChannel(
        input: String,
        password: String? = null,
        localName: String? = null,
        classification: String? = null,
        named: Boolean = false
    ): Channel {
        val messageStreamId = input.trim().let {
            if (it.endsWith(StreamConstants.SUFFIX_MESSAGE)) it else it + StreamConstants.SUFFIX_MESSAGE
        }
        _channels.value.find { it.messageStreamId == messageStreamId }?.let { return it }

        val perms = bridge.call("checkPermissions", JSONObject().put("streamId", messageStreamId))
        var canPublish = perms.optBoolean("canPublish")
        var canSubscribe = perms.optBoolean("canSubscribe")

        // Type/name from metadata (accepts short keys 'a'/'t' and long 'app'/'type')
        var name = messageStreamId.substringAfterLast('/').removeSuffix(StreamConstants.SUFFIX_MESSAGE)
        var type: String? = null
        var exposure = "hidden"
        var descriptionMeta = ""
        var gateAddress: String? = null
        var metaAuthorMode: String? = null
        var netNamed = false
        var metaRead = false
        try {
            val info = bridge.call("getStreamInfo", JSONObject().put("streamId", messageStreamId))
            metaRead = true
            val desc = info.optJSONObject("metadata")?.optString("description") ?: ""
            if (desc.isNotEmpty()) {
                val meta = JSONObject(desc)
                val app = meta.optString("a").ifEmpty { meta.optString("app") }
                if (app == "pombo") {
                    type = meta.optString("t").ifEmpty { meta.optString("type") }.ifEmpty { null }
                    meta.optString("n").ifEmpty { null }?.let { if (it != "null") { name = it; netNamed = true } }
                    exposure = meta.optString("e", "hidden")
                    descriptionMeta = meta.optString("d", "")
                    gateAddress = meta.optString("g").lowercase()
                        .takeIf { Regex("^0x[0-9a-f]{40}$").matches(it) }
                    // Author visibility (immutable `m` flag): it has to be
                    // right BEFORE the first publish — joining a Members-only
                    // channel as Everyone would put the account on the wire.
                    metaAuthorMode = if (meta.optInt("m") == 1) "members" else "everyone"
                }
            }
        } catch (e: Exception) { /* metadata is optional */ }

        // The on-chain name is authoritative; a user-typed or invite-suggested
        // name only fills the hidden-channel case (metadata `n` null).
        if (!netNamed) localName?.trim()?.takeIf { it.isNotEmpty() }?.let { name = it }

        // Gated (N-C): stream permissions belong to the gate clone, never to
        // members — the join gate is the CONTRACT. One cached eth_call.
        if (gateAddress != null) {
            type = "gated"
            val me = myAddress() ?: throw IllegalStateException("No identity")
            val access = bridge.call("gateCheckAccess", JSONObject()
                .put("gate", gateAddress).put("user", me)).optBoolean("access", false)
            if (!access) {
                // Typed for the UI (N-D): the gate entry screen reads the mode
                // on-chain and offers pay() instead of a toast.
                throw GateAccessDenied(gateAddress)
            }
            canPublish = true
            canSubscribe = true
        } else if (!canPublish && !canSubscribe) {
            throw IllegalStateException("No access to this channel (neither subscribe nor publish)")
        }
        val resolvedType = type
            ?: if (password != null) "password"
            else throw IllegalStateException("Could not determine the channel type. The channel may still be indexing — try again shortly.")

        val adminStreamId = StreamConstants.deriveAdminId(messageStreamId)

        // Verify the password against the challenge (fail-closed, like the web)
        if (resolvedType == "password") {
            if (password.isNullOrEmpty()) throw IllegalStateException("This channel requires a password")
            verifyPasswordChallenge(adminStreamId, password)
        }

        val channel = Channel(
            messageStreamId = messageStreamId,
            ephemeralStreamId = StreamConstants.deriveEphemeralId(messageStreamId),
            adminStreamId = adminStreamId,
            keysStreamId = if (resolvedType == "gated")
                StreamConstants.deriveKeysId(messageStreamId) else "",
            name = name,
            type = resolvedType,
            gateAddress = gateAddress,
            authorMode = if (resolvedType == "gated") (metaAuthorMode ?: "everyone") else null,
            joinedAt = System.currentTimeMillis(),
            password = if (resolvedType == "password") password else null,
            exposure = exposure,
            description = descriptionMeta,
            classification = classification,
            readOnly = !canPublish && canSubscribe,
            writeOnly = canPublish && !canSubscribe
        )
        addChannel(channel)
        // Entry into a channel with no on-chain name and no user-typed one:
        // offer the local name + classification panel over the opening
        // channel. Unreadable metadata proves nothing — no ask (web parity).
        if (metaRead && !netNamed && !named) _pendingLocalIdentity.value = channel
        return channel
    }

    /**
     * Last message of a channel for the list/Explore preview
     * (web: resendLatestContentMessages via ChannelLatestMessageManager).
     */
    /**
     * Display label for a preview's sender — the web's `formatPreviewSender`
     * priority: You -> ENS -> senderName -> short address. When the ENS name is
     * not cached yet the resolution is kicked off here; the list row resolves
     * again at render time (it observes [ensNames]), so the name swaps in without
     * having to re-fetch the preview.
     */
    private fun previewSenderLabel(address: String, senderName: String?): String {
        if (address.isNotEmpty() && address.equals(myAddress(), ignoreCase = true)) return "You"
        ensStore.cachedName(address)?.let { return it }
        if (address.isNotEmpty()) resolveEnsFor(address)
        senderName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return if (address.length > 10) address.take(6) + "…" else address
    }

    suspend fun fetchLatestPreview(
        messageStreamId: String,
        /** Password channels seal P0 payloads; without it every entry is skipped. */
        password: String? = null
    ): com.pombo.android.core.LatestMessageStore.Preview? {
        return try {
            // Resends need a live client; without this the call fails instantly
            // on a cold start and the preview silently never appears.
            bridge.awaitConnected()
            // P0 carries messages AND reactions AND image chunks, so last:1
            // usually lands on something unrenderable. The web fetches a window
            // (config.channels.latestMessageFetchLast = 10) and picks the
            // newest entry it can actually show.
            val args = JSONObject()
                .put("streamId", messageStreamId)
                .put("partition", StreamConstants.P_MESSAGES)
                .put("last", LATEST_PREVIEW_FETCH)
                .put("budgetMs", 12_000)
            password?.let { args.put("password", it) }
            val res = bridge.call("resend", args, 20_000)
            val arr = res.optJSONArray("messages") ?: return null
            // Password channels seal each payload as a base64 STRING — reading it
            // as a JSONObject skipped every entry, so private channels never got
            // a preview at all. Same batch decrypt the history page uses.
            val decrypted = predecrypt(arr, password)
            var best: com.pombo.android.core.LatestMessageStore.Preview? = null
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val content = decrypted.getOrNull(i) as? JSONObject ?: continue
                val ts = entry.optJSONObject("meta")?.optLong("timestamp") ?: 0L
                // account = proof ? recovered wallet : publisherId — covers all
                // three eras: new messages (proof), legacy (publisherId = the
                // wallet) and reactions (no sender field at all). The legacy
                // payload `sender` only matters when meta carried no publisher.
                val senderAddr = attachAccount(
                    content, entry.optJSONObject("meta")?.optString("publisherId")
                ) ?: content.optString("sender")
                val sender = previewSenderLabel(senderAddr, content.optStringOrNull("senderName"))
                val body = when (content.optString("type")) {
                    "text" -> content.optString("text")
                    "image" -> "[image]"
                    "video_announce" -> "[video]"
                    "file_announce", "storage_file_announce" -> "[file]"
                    // Removals are not previewable (web filters them upstream).
                    "reaction" -> if (content.optString("action") == "remove") null
                        else "reacted with ${content.optString("emoji")}"
                    else -> null
                } ?: continue
                if (best == null || ts > best.ts) {
                    best = com.pombo.android.core.LatestMessageStore.Preview(sender, body, ts, senderAddr)
                }
            }
            best
        } catch (e: Exception) { null }
    }

    /**
     * One resend window over a background channel's P0, feeding BOTH the list
     * preview and the unread badge — the web's `checkChannelActivity`, minus
     * its 30s timer (we scan on app start and on entering Chats instead).
     *
     * No subscription is opened: the web works the same way, keeping a live
     * subscription only for the channel on screen.
     *
     * The two outputs need DIFFERENT filters, which is the easy thing to get
     * wrong here. A reaction is a legitimate preview ("reacted with 🎉") but is
     * not a new message, so it must not raise the badge; `image_chunk` is pure
     * transport and is neither. Web `isContentMessage`: text/message, image
     * with an imageId, video_announce with metadata — never reaction, edit or
     * delete.
     */
    suspend fun scanChannelActivity(channel: Channel) {
        val streamId = channel.messageStreamId
        try {
            bridge.awaitConnected()
            val args = JSONObject()
                .put("streamId", streamId)
                .put("partition", StreamConstants.P_MESSAGES)
                .put("last", LATEST_PREVIEW_FETCH)
                .put("budgetMs", 12_000)
            channel.password?.let { args.put("password", it) }
            val res = bridge.call("resend", args, 20_000)
            val arr = res.optJSONArray("messages") ?: return
            val since = unreadStore.watermark(streamId)
            val me = myAddress()
            // Password channels seal P0 payloads — decrypt before reading, or this
            // scan finds nothing (no preview AND no unread count for them).
            val decrypted = predecrypt(arr, channel.password)

            var best: com.pombo.android.core.LatestMessageStore.Preview? = null
            var newContent = 0
            var maxTs = since

            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                val content = decrypted.getOrNull(i) as? JSONObject ?: continue
                val meta = entry.optJSONObject("meta")
                val ts = meta?.optLong("timestamp") ?: 0L
                val type = content.optString("type")

                // Advance the watermark past ANY newer item, content or not, so
                // a window full of reactions cannot make the next scan reread
                // the same entries forever (web does the same).
                if (ts > maxTs) maxTs = ts

                // Same rule as fetchLatestPreview: identity from the proof-
                // resolved account, publisherId fallback for the legacy eras.
                val senderAddr = attachAccount(content, meta?.optString("publisherId"))
                    ?: content.optString("sender")

                // --- preview candidate (reactions allowed) ---
                val body = when (type) {
                    "text" -> content.optString("text")
                    "image" -> "[image]"
                    "video_announce" -> "[video]"
                    "file_announce", "storage_file_announce" -> "[file]"
                    "reaction" -> if (content.optString("action") == "remove") null
                        else "reacted with ${content.optString("emoji")}"
                    else -> null
                }
                if (body != null && (best == null || ts > best.ts)) {
                    best = com.pombo.android.core.LatestMessageStore.Preview(
                        previewSenderLabel(senderAddr, content.optStringOrNull("senderName")), body, ts, senderAddr
                    )
                }

                // --- unread candidate (content only, and not my own) ---
                if (ts <= since) continue
                if (senderAddr.equals(me, ignoreCase = true)) continue
                val isContent = when (type) {
                    "text", "message" -> true
                    "image" -> content.optStringOrNull("imageId") != null
                    "video_announce" -> content.opt("metadata") != null
                    else -> false
                }
                if (isContent) newContent++
            }

            best?.let { previewStore.put(streamId, it) }
            if (newContent > 0) unreadStore.add(streamId, newContent)
            if (maxTs > since) unreadStore.setWatermark(streamId, maxTs)
        } catch (e: Exception) {
            // A failed scan is not worth surfacing: the list keeps whatever it
            // had, and the next pass retries.
        }
    }

    /**
     * Fetches the public metadata of a channel (name/description/type) from the
     * pombo JSON in the stream description — used by the Explore preview cards.
     */
    suspend fun fetchChannelMeta(messageStreamId: String): ExploreChannel {
        var name = messageStreamId.substringAfterLast('/').removeSuffix(StreamConstants.SUFFIX_MESSAGE)
        var type = "public"
        var description = ""
        var language = ""
        var category = ""
        try {
            val info = bridge.call("getStreamInfo", JSONObject().put("streamId", messageStreamId), 20_000)
            val desc = info.optJSONObject("metadata")?.optString("description") ?: ""
            if (desc.isNotEmpty()) {
                val meta = JSONObject(desc)
                val app = meta.optString("a").ifEmpty { meta.optString("app") }
                if (app == "pombo") {
                    meta.optString("n").ifEmpty { null }?.let { if (it != "null") name = it }
                    type = meta.optString("t").ifEmpty { meta.optString("type") }.ifEmpty { "public" }
                    description = meta.optString("d", "")
                    language = meta.optString("l", "")
                    category = meta.optString("c", "")
                }
            }
        } catch (e: Exception) { /* keep fallbacks */ }

        // Last message preview (web shows "sender: text" on the explore card)
        var lastSender = ""
        var lastText = ""
        var lastSenderAddress = ""
        try {
            val res = bridge.call("resend", JSONObject()
                .put("streamId", messageStreamId).put("partition", StreamConstants.P_MESSAGES).put("last", 1), 20_000)
            val arr = res.optJSONArray("messages")
            val content = if (arr != null && arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.opt("content") else null
            val body = if (content is JSONObject) when (content.optString("type")) {
                "text" -> content.optString("text")
                "image" -> "[image]"
                "file_announce", "storage_file_announce" -> "[file]"
                else -> null
            } else null
            if (content is JSONObject && body != null) {
                lastSenderAddress = content.optString("sender")
                lastSender = previewSenderLabel(lastSenderAddress, content.optStringOrNull("senderName"))
                lastText = body
            }
        } catch (e: Exception) { /* preview is best-effort */ }

        return ExploreChannel(
            messageStreamId, name, description, type, language, category,
            lastSender, lastText, lastSenderAddress
        )
    }

    // ==================== moderation (ADMIN_STATE) ====================

    fun amOwner(channel: Channel): Boolean = admin.amOwner(channel)

    /**
     * Whether the current account may moderate the open channel: an on-chain
     * DELETE-permission check (web: MessageContextMenuUI.js:211-214), not
     * `amOwner` — `createdBy`/stream-id comparison misses a granted-but-not-
     * creator admin and keeps Pin/Hide/Ban offered to a creator whose
     * permission was since revoked.
     */
    /**
     * What the current account may do on the open channel.
     *
     * The web gates different surfaces on different axes — moderation and the
     * danger zone on DELETE, adding members on GRANT — so a single "am I the
     * owner" boolean cannot express it (ChannelSettingsUI.js:97-201).
     */
    data class ChannelPerms(
        val canPublish: Boolean = false,
        val canGrant: Boolean = false,
        val canEdit: Boolean = false,
        val canDelete: Boolean = false
    )

    private val _perms get() = admin._perms
    val perms: StateFlow<ChannelPerms> = _perms.asStateFlow()

    /** Convenience for the moderation surfaces, which all key off DELETE. */
    val canModerate: StateFlow<Boolean> = _perms
        .map { it.canDelete }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private fun refreshModerationPermission(channel: Channel, preview: Boolean) =
        admin.refreshModerationPermission(channel, preview)

    private suspend fun loadAdminState(channel: Channel, generation: Int) =
        admin.loadAdminState(channel, generation)

    private fun startAdminPoller(channel: Channel, generation: Int) =
        admin.startAdminPoller(channel, generation)


    /**
     * TTL-aware republish of the -3 artifacts on owner open (web channels.js
     * _ttlRepublishOnOpen; docs/TTL_REPUBLISH_PLAN.md).
     *
     * The storage node's TTL purge deletes by message timestamp and the -3
     * artifacts are published once, then only read — an unmoderated channel
     * eventually loses its ADMIN_STATE (bans/pins vanish silently), its
     * CHANNEL_IMAGE and its PASSWORD_CHALLENGE (joiners fail closed: channel
     * unjoinable). When the OWNER opens a channel, compare each retained
     * artifact's payload `ts` against the retention and republish anything
     * past TTL_REPUBLISH_AGE_FRACTION of its life. Republishing resets the
     * clock, so this stays quiet for the next ~80% of the TTL.
     *
     * The challenge branch is also the reopen redundancy check the web had
     * inline (create-time publish racing the storage attachment: missing or
     * invalid → republish) — ported here for parity.
     *
     * Must run AFTER loadAdminState (reads adminRevs/adminTs and the
     * moderation flows that publishAdminState snapshots).
     */
    private suspend fun ttlRepublishOnOpen(channel: Channel, generation: Int) {
        if (channel.type == "dm") return
        // Only the owner can publish on -3 (on-chain permissions).
        if (!amOwner(channel)) return
        // NOTE: deliberately NOT gated on channel.storageEnabled — the local
        // flag can be stale (joined channels, pre-flag records) while the
        // stream has storage on-chain. Each branch self-gates: nothing
        // retained → nothing republished (and a missing challenge must
        // republish regardless, the legacy redundancy semantics).
        if (channel.adminStreamId.isEmpty()) return
        // The purge applies the -3's own retention, not the -1's.
        val storageDays = adminRetentionDays(refreshStreamRetentions(channel))
        fun ageDays(ts: Long) = (System.currentTimeMillis() - ts) / 86_400_000L

        // ADMIN_STATE (-3/P0): republish the current snapshot with rev+1 via
        // the normal publish path (rev bookkeeping + admin_invalidate fan-out
        // included). Only when a snapshot is actually retained — an empty
        // state has nothing to preserve.
        val adminTsValue = adminTs[channel.adminStreamId] ?: 0L
        if (channel.adminStreamId in adminLoaded
            && (adminRevs[channel.adminStreamId] ?: 0) > 0
            && shouldRepublish(adminTsValue, storageDays)
        ) {
            try {
                if (!stillCurrent(generation)) return
                Log.i(TAG, "ADMIN_STATE nearing storage TTL (${ageDays(adminTsValue)}d/${storageDays}d) — owner republishing")
                publishAdminState(channel)
            } catch (e: Exception) {
                Log.w(TAG, "ADMIN_STATE TTL republish failed (will retry next open): ${e.message}")
            }
        }

        // PASSWORD_CHALLENGE (-3/P2): missing OR invalid (legacy redundancy
        // semantics) OR too old → republish a fresh payload. Content is
        // immutable, so a fresh publish is always safe.
        val pwd = channel.password
        if (channel.type == "password" && !pwd.isNullOrEmpty()) {
            try {
                val probe = probePasswordChallenge(channel.adminStreamId, pwd)
                val tooOld = probe.found && probe.valid && shouldRepublish(probe.ts, storageDays)
                when {
                    !probe.found -> Log.i(TAG, "PASSWORD_CHALLENGE not retained on -3/P2 — owner republishing for redundancy")
                    !probe.valid -> Log.w(TAG, "PASSWORD_CHALLENGE did not verify with owner password — republishing")
                    tooOld -> Log.i(TAG, "PASSWORD_CHALLENGE nearing storage TTL (${ageDays(probe.ts)}d/${storageDays}d) — owner republishing")
                }
                if (!probe.found || !probe.valid || tooOld) {
                    if (!stillCurrent(generation)) return
                    // A single publish into a cold partition overlay can be
                    // silently lost (observed in the field: publish returns ok,
                    // storage never sees it). Verify-until-retained, but by
                    // FRESHNESS, not existence — ensurePasswordChallengeRetained
                    // would see the old still-valid challenge and declare
                    // victory, which is exactly the entry we are replacing.
                    val publishedAt = System.currentTimeMillis()
                    publishPasswordChallenge(channel.adminStreamId, pwd)
                    scope.launch {
                        repeat(12) { attempt ->
                            delay(5_000)
                            val check = probePasswordChallenge(channel.adminStreamId, pwd)
                            if (check.found && check.valid && check.ts >= publishedAt) {
                                Log.i(TAG, "PASSWORD_CHALLENGE refresh retained after ${attempt + 1} cycle(s)")
                                return@launch
                            }
                            try { publishPasswordChallenge(channel.adminStreamId, pwd) } catch (e: Exception) {
                                Log.w(TAG, "PASSWORD_CHALLENGE refresh republish #${attempt + 1} failed: ${e.message}")
                            }
                        }
                        Log.w(TAG, "PASSWORD_CHALLENGE refresh not retained after 12 attempts")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "PASSWORD_CHALLENGE TTL check failed (will retry next open): ${e.message}")
            }
        }

        // CHANNEL_IMAGE (-3/P1): republish the RETAINED payload verbatim with
        // rev+1 and a fresh ts. Deliberately re-resent here instead of
        // trusting the image store — the disk cache survives a storage purge,
        // and resurrecting a purged image from local cache is a recovery
        // decision this path does not make (plan §6).
        try {
            val payload = resendImagePayload(channel.adminStreamId, pwd)
            val ts = payload?.optLong("ts", 0L) ?: 0L
            if (payload != null
                && payload.optString("data").isNotEmpty()
                && payload.optString("hash").isNotEmpty()
                && shouldRepublish(ts, storageDays)
            ) {
                val encrypted = payload.optBoolean("encrypted", false)
                if (encrypted && pwd.isNullOrEmpty()) {
                    Log.d(TAG, "CHANNEL_IMAGE TTL republish skipped: encrypted payload without password")
                    return
                }
                val newRev = payload.optInt("rev", 0) + 1
                val fresh = JSONObject(payload.toString())
                    .put("rev", newRev)
                    .put("ts", System.currentTimeMillis())
                if (fresh.optString("createdBy").isEmpty()) fresh.put("createdBy", myAddress())
                if (!stillCurrent(generation)) return
                Log.i(TAG, "CHANNEL_IMAGE nearing storage TTL (${ageDays(ts)}d/${storageDays}d) — owner republishing rev=$newRev")
                if (isEpochChannel(channel) && channel.exposure == "visible") {
                    // Storefront image stays in the CLEAR (mirrors the
                    // set-image path): the generic epoch publish would seal
                    // it and hide the storefront from Explore/non-members.
                    bridge.call("publishAsAccount", JSONObject()
                        .put("streamId", channel.adminStreamId)
                        .put("partition", StreamConstants.ADMIN_CHANNEL_IMAGE)
                        .put("content", fresh), 60_000)
                } else {
                    publishContent(
                        channel.adminStreamId, StreamConstants.ADMIN_CHANNEL_IMAGE, fresh,
                        if (encrypted) pwd else null
                    )
                }
                // Keep the open channel's rev counter ahead of the retained
                // entry so a later image change never publishes a lower rev.
                if (stillCurrent(generation)) channelImageRev = maxOf(channelImageRev, newRev)
            }
        } catch (e: Exception) {
            Log.w(TAG, "CHANNEL_IMAGE TTL republish failed (will retry next open): ${e.message}")
        }
    }

    private fun applyAdminMessage(
        channel: Channel,
        contentAny: Any?,
        meta: JSONObject,
        generation: Int
    ) = admin.applyAdminMessage(channel, contentAny, meta, generation)

    private suspend fun publishAdminState(channel: Channel) = admin.publishAdminState(channel)

    suspend fun hideMessage(messageId: String, hide: Boolean) = admin.hideMessage(messageId, hide)

    suspend fun pinMessage(messageId: String, pin: Boolean) = admin.pinMessage(messageId, pin)

    suspend fun banMember(address: String, ban: Boolean = true) = admin.banMember(address, ban)

    suspend fun banMemberLevels(address: String, client: Boolean, protocol: Boolean) =
        admin.banMemberLevels(address, client, protocol)

    suspend fun unbanMemberLevels(address: String) = admin.unbanMemberLevels(address)


    // ==================== DMs (E2E) ====================

    /**
     * True when my DM inbox stream already exists on-chain (web: dm.js hasInbox).
     * A positive result is cached for the session — streams aren't deleted
     * mid-session — while a negative one is re-checked, so a transient network
     * failure doesn't wrongly hide the DM UI until restart.
     */
    private var inboxExistsCache: String? = null

    suspend fun hasInbox(): Boolean {
        val me = myAddress()?.lowercase() ?: return false
        val inbox = "$me/Pombo-DM-1"
        if (inboxExistsCache == inbox) return true
        return try {
            bridge.call("getStreamInfo", JSONObject().put("streamId", inbox), 20_000)
            inboxExistsCache = inbox
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wake signal so other clients' push relays notify their subscribers
     * (web: relayManager.sendChannelWakeSignal / sendNativeChannelWakeSignal).
     *
     * The payload carries only a 1-byte K-anonymity tag and a proof of work —
     * never the message, the channel id or the sender — so the relay learns
     * nothing beyond "something happened in one of 256 tag buckets".
     *
     * Fire-and-forget: failing to notify must never fail the send itself.
     */
    private fun sendWakeSignal(channel: Channel, kind: String? = null) {
        scope.launch {
            try {
                // Web sendWakeSignals: ONLY gated channels use the 'native:'
                // tag (the push protocol keeps the historical name — it is
                // baked into registered devices' tag derivation); public,
                // password AND DMs use the 'channel:' tag (a DM wakes the
                // peer's inbox channel-tag — dm.js → sendChannelWakeSignal).
                // `type != "public"` silently broke Android→web DM and
                // password-channel notifications.
                val native = isEpochChannel(channel)
                val args = JSONObject().put("streamId", channel.messageStreamId).put("native", native)
                kind?.let { args.put("kind", it) }
                val res = bridge.call("pushWakePayload", args, 30_000)
                // Ephemeral publisher, fresh key per wake (see PushRelayClient):
                // the wake is validated by PoW, not publisher, so under the
                // account every message X sends would stamp X onto the public
                // /push stream — a timing side-channel back onto the sealed
                // DM it just sent. A throwaway key closes it.
                bridge.call("publishAs", JSONObject()
                    .put("streamId", PUSH_STREAM_ID)
                    .put("partition", 0)
                    .put("content", res.getJSONObject("payload")), 30_000)
                android.util.Log.d(
                    "PomboPush",
                    "wake signal sent: type=${channel.type} native=$native " +
                        "tag=${res.getJSONObject("payload").optString("tag")} " +
                        "stream=…${channel.messageStreamId.takeLast(24)}"
                )
            } catch (e: Exception) {
                // No relay, no PoW in time, no network — the message still went,
                // but the silence here is what made missing notifications
                // undiagnosable; say what died.
                android.util.Log.w(
                    "PomboPush",
                    "wake signal FAILED: type=${channel.type} " +
                        "stream=…${channel.messageStreamId.takeLast(24)}: ${e.message}"
                )
            }
        }
    }

    /**
     * One key-responder pass (owner mode): answer retained key requests on
     * every marked channel, open or not. ensureChannelKeys is idempotent —
     * wrap coverage keeps repeated sweeps from re-answering, and for the
     * admin it doubles as the TTL re-announce / rotation heartbeat.
     */
    suspend fun sweepKeyResponder(entries: List<com.pombo.android.data.KeyResponderEntry>) {
        for (entry in entries) {
            val channel = channelByStream(entry.messageStreamId) ?: continue
            try {
                epochKeys.ensureChannelKeys(
                    entry.messageStreamId,
                    entry.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(entry.messageStreamId) },
                    keysRetentionDays(channel),
                    allowMint = false,
                    memberCount = channel.members.size,
                    gated = channel.type == "gated")
            } catch (e: Exception) {
                Log.d(TAG, "key responder sweep failed on …${entry.messageStreamId.takeLast(20)}: ${e.message}")
            }
        }
    }

    /** The channel's k-anonymous push tag ('native:' prefix — gated). */
    suspend fun keyResponderTag(channel: Channel): String =
        bridge.call("pushTag", JSONObject()
            .put("streamId", channel.messageStreamId)
            .put("native", true)).optString("tag")

    /**
     * Ensures my DM inbox exists on-chain (needs gas) and subscribes to it.
     *
     * Step-by-step like the web's dmManager.createInbox — 2× stream, 2×
     * permissions, storage — so the UI can drive a progress ring and the user
     * can pick the storage node and retention. Streams have FIXED ids, so a
     * re-run after a partial failure resumes via get-or-create instead of
     * dying on "already exists".
     */
    suspend fun setupDmInbox(
        storageProvider: String = "streamr",
        customStorageAddress: String? = null,
        storageDays: Int = 180,
        onProgress: () -> Unit = {}
    ) {
        val me = myAddress()?.lowercase() ?: throw IllegalStateException("No identity")
        val pk = com.pombo.android.core.EthereumSigner.compressedPublicKey(
            myPrivateKey() ?: throw IllegalStateException("No identity")
        )
        val messageStreamId = "$me/Pombo-DM-1"
        val ephemeralStreamId = "$me/Pombo-DM-2"
        // Same metadata the web writes (streamr.js: dm-inbox marker + pubkey).
        val meta = JSONObject().put("a", "pombo").put("v", "1").put("t", "dm-inbox").put("pk", pk)
        val ephMeta = JSONObject().put("a", "pombo").put("v", "1").put("ln", messageStreamId)

        getOrCreateStreamRetry(messageStreamId, meta.toString(), StreamConstants.MSG_DM_PARTITIONS); onProgress()
        getOrCreateStreamRetry(ephemeralStreamId, ephMeta.toString(), StreamConstants.EPH_PARTITIONS); onProgress()

        // Public PUBLISH only (owner reads by ownership) — hides the social graph.
        val pubOnly = JSONArray().put(
            JSONObject().put("public", true).put("permissions", JSONArray(listOf("publish")))
        )
        setPermissionsRetry(messageStreamId, pubOnly); onProgress()
        setPermissionsRetry(ephemeralStreamId, pubOnly); onProgress()

        // Storage best-effort, like channel creation: the inbox works without
        // retained history, and the streams are already paid for.
        val storageNode = if (storageProvider == "custom" && !customStorageAddress.isNullOrBlank())
            customStorageAddress else STORAGE_NODE
        try { addStorageRetry(messageStreamId, storageNode, storageDays) } catch (e: Exception) {
            Log.w(TAG, "DM inbox storage failed; continuing without history: ${e.message}")
        }
        onProgress()

        myInboxId = messageStreamId
        // force: the inbox was just created (or repaired), so any earlier
        // "already subscribed" verdict was about a stream that did not exist —
        // web dm.js repairInbox nulls `inboxSubscription` for the same reason.
        subscribeMyInbox(force = true)
    }

    private suspend fun getOrCreateStreamRetry(id: String, description: String, partitions: Int) = retry(7) {
        bridge.call("getOrCreateStream", JSONObject()
            .put("id", id).put("description", description).put("partitions", partitions), 120_000)
    }

    /**
     * Received DMs, per peer, exactly as the web keeps them on `channel.messages`
     * with `_dmReceived`. In memory on purpose: the inbox history is replayed on
     * every connect, so this is a cache of the network, not a second source of
     * truth. Sent messages are the opposite — see [SentDmStore].
     */
    private val dmReceived = HashMap<String, MutableList<UiMessage>>()

    /**
     * Native sealed-sender open (SealedSenderCrypto, vector-locked) — the key
     * stays in Kotlin. One result per item: {sender, message} or NULL for
     * anything that is not a v2 envelope or does not open (not ours).
     */
    private suspend fun openSealedBatch(items: JSONArray): JSONArray? {
        val myPk = myPrivateKey() ?: return null
        val me = myAddress() ?: return null
        return withContext(Dispatchers.Default) {
            val out = JSONArray()
            for (i in 0 until items.length()) {
                val opened = items.optJSONObject(i)?.let {
                    com.pombo.android.core.SealedSenderCrypto.open(it, myPk, me)
                }
                out.put(
                    opened?.let { JSONObject().put("sender", it.first).put("message", it.second) }
                        ?: JSONObject.NULL
                )
            }
            out
        }
    }

    /** Single-envelope form of [openSealedBatch]. */
    private suspend fun openSealedOne(envelope: JSONObject): JSONObject? {
        val myPk = myPrivateKey() ?: return null
        val me = myAddress() ?: return null
        return withContext(Dispatchers.Default) {
            com.pombo.android.core.SealedSenderCrypto.open(envelope, myPk, me)
                ?.let { JSONObject().put("sender", it.first).put("message", it.second) }
        }
    }

    /**
     * Sealed-sender v2 publish: the envelope is sealed natively (the identity
     * key never enters the WebView) and goes out under the throwaway key via
     * the bridge's publishAs — which is also the message's publishing identity,
     * so that key crosses the bridge by design.
     */
    private suspend fun dmSealPublish(
        streamId: String,
        partition: Int,
        recipientAddress: String,
        recipientPublicKey: String,
        message: JSONObject,
        timeoutMs: Long = 45_000
    ): JSONObject {
        val myPk = myPrivateKey() ?: throw IllegalStateException("No identity")
        val (envelope, ephemeralPk) = withContext(Dispatchers.Default) {
            com.pombo.android.core.SealedSenderCrypto.seal(message, myPk, recipientAddress, recipientPublicKey)
        }
        return bridge.call("publishAs", JSONObject()
            .put("streamId", streamId).put("partition", partition)
            .put("content", envelope).put("privateKey", ephemeralPk), timeoutMs)
    }

    /**
     * Routes one inbox message to its conversation (web: routeInboxMessage).
     *
     * Runs for history and live traffic alike, which is what makes a DM from
     * someone new appear at all: it creates the conversation when there isn't
     * one. Previously anything not belonging to the open chat was dropped.
     */
    private suspend fun routeInboxMessage(
        envelope: Any?,
        /** Transport publisher — a THROWAWAY key on sealed traffic; only ever
         *  an identity for legacy plaintext payloads (back then it was the
         *  wallet). Never used to pick a decryption key: the sender is
         *  unknown until the envelope is open. */
        publisherIdRaw: String?,
        /** Already opened by the page-wide batch in [loadInboxHistory]. */
        preSender: String? = null,
        preData: JSONObject? = null
    ) {
        val me = myAddress()?.lowercase() ?: return
        if (envelope !is JSONObject) return

        // SEALED SENDER INVERTS THE ORDER: open first, identify after. Any
        // lookup keyed by the sender before decryption (peer key choice,
        // block check, conversation state) would key off a throwaway address
        // and silently drop every message — the web hit that five times.
        val sender: String
        val data: JSONObject
        if (preSender != null && preData != null) {
            sender = preSender
            data = preData
        } else if (envelope.optInt("v") == 2 && envelope.has("epk")) {
            // v2 sealed envelope: ECDH with OUR static key + the cleartext
            // epk; the true sender comes from ecrecover of the proof inside,
            // rebuilt against OUR address. What does not open is not ours.
            val opened = openSealedOne(envelope) ?: return
            sender = opened.optString("sender").lowercase().ifEmpty { return }
            data = opened.optJSONObject("message") ?: return
        } else if (envelope.optString("e") == "aes-256-gcm") {
            // v1 pair-key envelope: pre-migration traffic. Its outer
            // Streamr-native layer is gone (P6), and the web can no longer
            // produce this format — dropped by design, not by accident.
            return
        } else {
            // Legacy plaintext payloads (old unsealed file announces) pass
            // through, attributed to the transport publisher — which was the
            // wallet in that era.
            if (envelope.optString("type").isEmpty()) return
            sender = publisherIdRaw?.lowercase()?.ifEmpty { null } ?: return
            data = envelope
        }

        // My own messages are never in my inbox; if one shows up, it is not mine
        // to render from here (the local sent store owns that half).
        if (sender == me) return
        // The block check now necessarily runs AFTER opening — the sender is
        // simply not knowable before. The ~1ms ECDH spent on a blocked peer's
        // message is the documented cost of sealed sender (brief §5.3).
        if (isBlockedPeer(sender)) return
        // Same stamp as the channel ingest (applyAccount): identity for every
        // downstream reader comes from the proof, never from the wire.
        data.put("account", sender)
        data.put("sender", sender)

        // A conversation the user left stays gone until the peer writes again
        // after the tombstone (web: getDMLeftAt).
        val leftAt = dmLeftAt(sender)
        if (leftAt > 0 && data.optLong("timestamp", 0L) <= leftAt) return
        if (leftAt > 0) clearDmLeftAt(sender)

        // Is this peer's conversation the one on screen? Everything below has to
        // know, because _messages and _reactions describe the OPEN channel: work
        // that paints into them for a background DM lands in whatever channel is
        // actually open. That is how a DM's image turned up inside a Streamr
        // channel — the manifest below painted its bubble unconditionally.
        val onScreen = _current.value?.peerAddress?.lowercase() == sender

        when (data.optString("type")) {
            "edit", "delete" -> {
                if (onScreen) applyOverride(data, sender)
                // Off screen, still rewrite the inbox cache: that cache is what
                // the next open merges from, so skipping it is what made a
                // reopened DM flash the pre-edit text.
                else applyOverrideToInboxCache(sender, data.optString("targetId"), data)
                return
            }
            "reaction" -> {
                // Dropped when off screen rather than written into another
                // channel's reaction map. Reopening the DM replays the inbox
                // history through this same router, which restores it.
                if (onScreen) applyReaction(
                    data.optString("messageId"), data.optString("emoji"), sender,
                    data.optString("action") != "remove"
                )
                return
            }
            // Images ride the same chunked transport as channels, just sealed
            // per-payload. Chunks carry no message id, so they are assembled by
            // imageId and never become conversation entries themselves — and
            // assembly must run even off screen, or the bytes are lost.
            "image_chunk" -> { handleImageChunk(data); return }
        }

        val channel = getOrCreateDmConversation(sender, data.optStringOrNull("senderName"))

        // An image manifest must register the chunk assembly, not just become a
        // bubble — otherwise its chunks arrive with nowhere to go and the
        // placeholder spins forever. Off screen we want the registration and
        // NOT the bubble; the bubble is rebuilt from [dmReceived] on open.
        if (data.optString("type") == "image") {
            handleImageManifest(channel, data, showBubble = onScreen)
        }

        val msg = toUiMessage(data, myAddress() ?: return) ?: return

        val list = synchronized(dmReceived) { dmReceived.getOrPut(sender) { mutableListOf() } }
        synchronized(list) {
            if (list.none { it.id == msg.id }) list.add(msg)
        }
        // Paint immediately when this conversation is the one on screen.
        if (onScreen) {
            mergeMessages(listOf(msg))
            // toUiMessage only reads the ENS CACHE — nothing on the live path
            // asked for a resolution, so a message arriving with the cache
            // cold stayed nameless/avatarless until the chat was reopened
            // (the timeline rebuild is where ensureEns used to run). Cheap
            // when cached: the store early-returns fresh entries and dedups
            // in-flight lookups.
            ensureEns(sender)
        } else {
            previewStore.put(
                channel.messageStreamId,
                com.pombo.android.core.LatestMessageStore.Preview(
                    sender = sender,
                    text = msg.text.ifEmpty { if (msg.file != null || msg.storageFile != null) "[file]" else "[image]" },
                    ts = msg.timestamp
                )
            )
        }
    }

    /** Web CONFIG.dm.inboxHistoryCount — replayed through the router on connect. */
    private val inboxHistoryCount = 100

    private suspend fun loadInboxHistory(inbox: String) {
        try {
            val t0 = System.currentTimeMillis()
            val res = bridge.call("resend", JSONObject()
                .put("streamId", inbox)
                .put("partition", StreamConstants.P_MESSAGES)
                .put("last", inboxHistoryCount), 60_000)
            val tResend = System.currentTimeMillis()
            val arr = res.optJSONArray("messages") ?: return
            val n = arr.length()

            // Open the WHOLE page in one bridge call — no pre-filtering by
            // publisher, which is a throwaway key on sealed traffic. Every v2
            // envelope goes in; the ones that open are ours, the ones that
            // don't come back null and are dropped (opening IS the ownership
            // test). One batch instead of 100 sequential evaluateJavascript
            // hops is what keeps the replay fast.
            val publishers = arrayOfNulls<String>(n)
            val items = JSONArray()
            for (i in 0 until n) {
                val entry = arr.optJSONObject(i)
                publishers[i] = entry?.optJSONObject("meta")?.optString("publisherId")
                    ?.lowercase()?.ifEmpty { null }
                val content = entry?.opt("content")
                items.put(
                    if (content is JSONObject && content.optInt("v") == 2 && content.has("epk")) content
                    else JSONObject.NULL
                )
            }
            val opened = openSealedBatch(items)
            val tDecrypt = System.currentTimeMillis()

            for (i in 0 until n) {
                val entry = arr.optJSONObject(i) ?: continue
                val res = opened?.takeIf { !it.isNull(i) }?.optJSONObject(i)
                routeInboxMessage(
                    entry.opt("content"), publishers[i],
                    preSender = res?.optString("sender")?.lowercase()?.ifEmpty { null },
                    preData = res?.optJSONObject("message")
                )
            }
            android.util.Log.d("PomboPerf",
                "inbox replay: resend=${tResend - t0}ms decrypt=${tDecrypt - tResend}ms " +
                    "route=${System.currentTimeMillis() - tDecrypt}ms n=$n")
        } catch (e: Exception) {
            // No storage on the inbox, or the node is down — live traffic still
            // works, the user just starts without back-history.
        }
    }

    /** Finds the DM channel for a peer, creating it on first contact. */
    private fun getOrCreateDmConversation(peer: String, senderName: String?): Channel {
        val inbox = "${peer.lowercase()}/Pombo-DM-1"
        _channels.value.find { it.messageStreamId == inbox }?.let { return it }
        val channel = Channel(
            messageStreamId = inbox,
            ephemeralStreamId = "${peer.lowercase()}/Pombo-DM-2",
            adminStreamId = "",
            name = resolveDmDisplayName(peer, senderName),
            type = "dm",
            joinedAt = System.currentTimeMillis(),
            peerAddress = peer
        )
        _channels.value = _channels.value + channel
        store.save(_channels.value)
        ensureEns(peer)
        return channel
    }

    /**
     * A DM room's FIRST name: ENS → the sender's self-assigned nick (from the
     * first message) → raw short address. The user can rename it locally
     * afterwards, and contact edits keep the room in step separately.
     */
    private fun resolveDmDisplayName(peer: String, fallback: String?): String =
        ensStore.cachedName(peer)
            ?: fallback?.trim()?.take(50)?.ifEmpty { null }
            ?: (peer.take(6) + "…" + peer.takeLast(4))

    /**
     * The web keeps contacts and DM rooms linked (the room is named after the
     * contact at creation); here the link also survives edits — renaming or
     * removing the contact renames the room, and the new name reaches other
     * devices through the channels slice of sync.
     */
    fun renameDmForContact(peerAddress: String, nickname: String?) {
        val peer = peerAddress.lowercase()
        val ch = _channels.value.find { it.type == "dm" && it.peerAddress?.lowercase() == peer } ?: return
        val name = nickname?.trim()?.ifEmpty { null }
            ?: ensStore.cachedName(peer)
            ?: (peerAddress.take(6) + "…" + peerAddress.takeLast(4))
        if (ch.name == name) return
        _channels.value = _channels.value.map {
            if (it.messageStreamId == ch.messageStreamId) it.copy(name = name) else it
        }
        store.save(_channels.value)
        onLocalStateChanged()
        if (_current.value?.messageStreamId == ch.messageStreamId) {
            _current.value = _current.value?.copy(name = name)
        }
    }

    /** Leave tombstones for DMs (web: dmLeftAt slice, synced across devices). */
    private fun dmLeftAt(peer: String): Long =
        store.leftAtJson().optLong("dm:${peer.lowercase()}", 0L)

    private fun clearDmLeftAt(peer: String) {
        val o = store.leftAtJson()
        o.remove("dm:${peer.lowercase()}")
        store.saveLeftAt(o)
        // The slice changed shape — stamp it so this clear survives the merge.
        onSliceTouched("dmLeftAt")
    }

    /**
     * The inbox this session has already subscribed and replayed. Web parity:
     * dm.js subscribeToInbox() returns early when `inboxSubscription` is set, so
     * the 100-message replay + per-peer key registration happen ONCE — not on
     * every DM open, which is what this used to do (prepareChannel calls in on
     * each open, and loadDmTimeline then replayed the inbox a second time).
     *
     * Cleared by passing `force`: the bridge reconnect rebuilds the JS world, so
     * the live subscription is genuinely gone there. An account switch clears
     * itself, since the inbox id is derived from the address.
     */
    @Volatile private var subscribedInboxId: String? = null
    // Volatile too: loadDmTimeline joins it from a different coroutine than the
    // one subscribeMyInbox assigns it on.
    @Volatile private var inboxSubscribeJob: kotlinx.coroutines.Job? = null

    /** Subscribes to my own inbox (incoming DMs from any peer). */
    fun subscribeMyInbox(force: Boolean = false) {
        val me = myAddress()?.lowercase() ?: return
        val inbox = "$me/Pombo-DM-1"
        myInboxId = inbox
        // Already subscribed to exactly this inbox, or still doing it: the
        // in-flight job is the one callers should wait on, not a second replay.
        if (!force && subscribedInboxId == inbox) return
        inboxSubscribeJob?.cancel()
        subscribedInboxId = inbox
        inboxSubscribeJob = scope.launch {
            // No Streamr-layer key registration any more: everything Pombo
            // publishes on DM streams is EncryptionType.NONE, sealed at the
            // app layer, so the SDK has nothing to decrypt (P6). Traffic from
            // pre-migration clients stays SDK-wrapped and never reaches the
            // callback — accepted, there is no key left to add for it.
            //
            // History BEFORE live, mirroring subscribeWithHistory: every past
            // message goes through the same router, which is what populates
            // conversations the user has not opened yet — and creates ones they
            // never started. Doing this per-conversation on open (as this used
            // to) meant a DM from a new peer was simply dropped.
            loadInboxHistory(inbox)
            subscribeQuiet(inbox, StreamConstants.P_MESSAGES)
            // P3 carries channel invites. The web only subscribes it when
            // invite notifications are not muted; muting is a settings toggle
            // we mirror in AppViewModel.
            if (inviteNotificationsEnabled) {
                subscribeQuiet(inbox, StreamConstants.P_NOTIFICATIONS)
                catchUpInvites(inbox)
            }
        }
    }

    /** Once per session — the "All" toggle is a filter, not a refresh button. */
    @Volatile private var deepInviteCatchUpDone = false

    /**
     * On-demand DEEP replay of P3, for the bell's "All" view: the same
     * idempotent funnel as the connect-time [catchUpInvites], just a much
     * larger window — so historical invites resurface, answered ones landing
     * in the dismissed list via [handleNotification]'s backfill and
     * never-answered ones back in pending.
     */
    fun fetchAllInvites() {
        if (deepInviteCatchUpDone) return
        deepInviteCatchUpDone = true
        val inbox = myInboxId ?: return
        scope.launch { catchUpInvites(inbox, last = 100) }
    }

    /**
     * Replays recent P3 invites from the inbox's storage node — a deliberate
     * improvement over the web, where an invite sent while no tab was open is
     * lost. Every replayed envelope goes through handleNotification, which
     * drops duplicates, answered invites (dismissed ledger) and joined
     * channels, so re-running this on each connect is idempotent.
     */
    private suspend fun catchUpInvites(inbox: String, last: Int = 20) {
        val res = try {
            // Undecryptable entries cost a full key-request timeout each while
            // the drain-side recovery registers keys and replays, so give this
            // more room than the preview scans get.
            bridge.call("resend", JSONObject()
                .put("streamId", inbox)
                .put("partition", StreamConstants.P_NOTIFICATIONS)
                .put("last", last)
                .put("budgetMs", 35_000), 45_000)
        } catch (e: Exception) {
            android.util.Log.d("PomboInvites", "catchUp failed: ${e.message}")
            return
        }
        val arr = res.optJSONArray("messages") ?: return
        android.util.Log.d(
            "PomboInvites",
            "catchUp: ${arr.length()} P3 message(s), partial=${res.optBoolean("partial")}"
        )
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val sender = item.optJSONObject("meta")?.optString("publisherId")
                ?.lowercase()?.ifEmpty { null } ?: continue
            handleNotification(item.opt("content"), sender)
        }
    }

    /** Opens (or creates) a DM conversation with a peer address or ENS name. */
    suspend fun startDm(peerAddressRaw: String, localName: String? = null): Channel {
        val input = peerAddressRaw.trim()
        var roomName = localName
        // The input placeholder promises "0x... or name.eth" — resolve ENS
        // first like the web (dm.js:800-812), and let the typed name become
        // the room name when the caller gave none.
        val peer = if (!input.startsWith("0x") && input.contains('.')) {
            val resolved = resolveMemberInput(input)
                ?: throw IllegalStateException("Could not resolve ENS name: $input")
            if (roomName == null) roomName = input
            resolved
        } else input
        require(Regex("^0x[a-fA-F0-9]{40}$").matches(peer)) { "Invalid Ethereum address" }
        // Web: "Cannot send a DM to yourself". Without the guard this created
        // a permanently broken room — the inbox router drops sender == me.
        if (peer.equals(myAddress(), ignoreCase = true)) {
            throw IllegalStateException("Cannot send a DM to yourself")
        }
        val peerInbox = "${peer.lowercase()}/Pombo-DM-1"
        _channels.value.find { it.messageStreamId == peerInbox }?.let { return it }

        // Peer must have published their inbox (with pk) to receive DMs.
        val pk = bridge.call("getPeerPublicKey", JSONObject().put("address", peer)).optString("publicKey")
        if (pk.isEmpty() || pk == "null") throw IllegalStateException("This user hasn't set up DMs yet")
        peerPubKeys[peer.lowercase()] = pk

        val channel = Channel(
            messageStreamId = peerInbox,
            ephemeralStreamId = "${peer.lowercase()}/Pombo-DM-2",
            adminStreamId = "",
            // Web dm.startDM: explicit local name wins, then the ENS name the
            // user typed, then the contact/ENS resolution names the room.
            name = roomName ?: resolveDmDisplayName(peer.lowercase(), null),
            type = "dm",
            joinedAt = System.currentTimeMillis(),
            peerAddress = peer
        )
        addChannel(channel)
        ensureEns(peer)
        return channel
    }

    internal suspend fun peerPubKey(address: String): String? {
        val key = address.lowercase()
        peerPubKeys[key]?.let { return it }
        return try {
            val pk = bridge.call("getPeerPublicKey", JSONObject().put("address", address)).optString("publicKey")
            if (pk.isNotEmpty() && pk != "null") { peerPubKeys[key] = pk; pk } else null
        } catch (e: Exception) { null }
    }

    /**
     * Public key of the peer a DM media publish is addressed to. DM media
     * always targets the PEER's inbox ephemeral ("0xpeer/Pombo-DM-2"), so the
     * peer's address is the stream id's owner prefix — the transport gets no
     * channel object, only the stream it was told to publish on.
     */
    private suspend fun dmMediaPeerKey(ephemeralStreamId: String): String? {
        val owner = ephemeralStreamId.substringBefore('/').lowercase()
        if (!owner.startsWith("0x") || owner.length != 42) return null
        return peerPubKey(owner)
    }

    /**
     * Shareable invite link for a channel (web: generateInviteLink).
     * The password travels inside the token for protected channels — that is
     * the web's contract, and it is why the link must be treated as a secret.
     */
    fun inviteLink(channel: Channel): String =
        InviteToken.link(channel.messageStreamId, channel.name, channel.type, channel.password, channel.gateAddress)

    /**
     * Delivers a CHANNEL_INVITE to a peer's DM inbox on P3 (web:
     * notifications.js sendChannelInvite). Requires the peer to have an inbox —
     * without one there is nowhere to deliver, so this fails loudly rather than
     * pretending it was sent.
     */
    suspend fun sendChannelInvite(recipientRaw: String, channel: Channel) {
        // Accept an ENS name as well as a raw address, like the Members panel
        // (resolveMemberInput passes 0x through and forward-resolves names).
        val recipient = resolveMemberInput(recipientRaw.trim())
            ?: throw IllegalStateException("Invalid address or ENS name")
        val sender = myAddress() ?: throw IllegalStateException("No account connected")
        if (recipient.equals(sender, ignoreCase = true)) {
            throw IllegalStateException("You cannot invite yourself")
        }

        val pk = peerPubKey(recipient) ?: throw IllegalStateException(
            "This user has not enabled DMs yet. They need to open Pombo and create their inbox first."
        )

        val invite = JSONObject()
            .put("type", "CHANNEL_INVITE")
            .put("inviteId", Protocol.generateMessageId())
            .put("timestamp", System.currentTimeMillis())
            .put("from", sender)
            .put(
                "channel", JSONObject()
                    .put("streamId", channel.messageStreamId)
                    .put("name", channel.name)
                    .put("type", channel.type)
                    .put("password", channel.password ?: JSONObject.NULL)
            )

        // Sealed sender v2 under a throwaway publisher (web notifications.js):
        // the inner `from` field survives for display, but the receiver treats
        // the proof-recovered sender as the authoritative identity.
        dmSealPublish(
            "${recipient.lowercase()}/Pombo-DM-1",
            StreamConstants.P_NOTIFICATIONS,
            recipient.lowercase(),
            pk,
            invite,
            60_000
        )
    }

    /** Invites that arrived on my inbox P3 and are still unanswered. */
    private val _pendingInvites = MutableStateFlow<List<PendingInvite>>(emptyList())
    val pendingInvites: StateFlow<List<PendingInvite>> = _pendingInvites.asStateFlow()

    data class PendingInvite(
        val inviteId: String,
        val from: String,
        val streamId: String,
        val name: String,
        val type: String,
        val password: String?
    )

    /** Dismissed invites retained in full for the bell's "All" view, newest first. */
    private val _dismissedInvites = MutableStateFlow<List<PendingInvite>>(emptyList())
    val dismissedInvites: StateFlow<List<PendingInvite>> = _dismissedInvites.asStateFlow()

    fun dismissInvite(inviteId: String) {
        val invite = _pendingInvites.value.firstOrNull { it.inviteId == inviteId }
        _pendingInvites.value = _pendingInvites.value.filterNot { it.inviteId == inviteId }
        // Ledger it: the P3 catch-up replays recent invites from storage on
        // every connect, and an answered one must not resurface.
        inviteStore.markDismissed(inviteId)
        // Retained in full: a dismiss can be a mis-tap, so the "All" view
        // still offers Accept on it.
        invite?.let { inviteStore.recordDismissed(storedOf(it)) }
        persistInvites()
        reloadDismissedInvites()
    }

    /**
     * The accept path's removal: the invite is RESOLVED, not dismissed — it
     * must not linger in the "All" view (and accepting one FROM that view
     * consumes its dismissed record). The id ledger entry is still written:
     * replay suppression applies to answered invites of either kind.
     */
    fun resolveInvite(inviteId: String) {
        _pendingInvites.value = _pendingInvites.value.filterNot { it.inviteId == inviteId }
        inviteStore.markDismissed(inviteId)
        inviteStore.removeDismissedRecord(inviteId)
        persistInvites()
        reloadDismissedInvites()
    }

    /** Restores invites received while the app was closed (P3 has no replay). */
    fun reloadInvites() {
        _pendingInvites.value = inviteStore.load().map {
            PendingInvite(it.inviteId, it.from, it.streamId, it.name, it.type, it.password)
        }
        reloadDismissedInvites()
    }

    private fun reloadDismissedInvites() {
        _dismissedInvites.value = inviteStore.dismissedInvites().map {
            PendingInvite(it.inviteId, it.from, it.streamId, it.name, it.type, it.password)
        }
    }

    private fun storedOf(invite: PendingInvite) =
        com.pombo.android.data.InviteStore.StoredInvite(
            invite.inviteId, invite.from, invite.streamId, invite.name, invite.type, invite.password
        )

    private fun persistInvites() {
        inviteStore.save(_pendingInvites.value.map { storedOf(it) })
    }

    /** Opens and files an incoming P3 notification (open first — sealed sender). */
    private suspend fun handleNotification(envelope: Any?, publisherId: String?) {
        if (envelope !is JSONObject) return
        // Sealed sender: the sender is unknown until the envelope is open, so
        // the block check necessarily moved to AFTER the decrypt (web
        // routeNotification does the same). ~1ms of ECDH per blocked invite
        // is the documented cost.
        if (envelope.optInt("v") != 2 || !envelope.has("epk")) {
            // v1 invites are pre-migration traffic whose outer SDK layer is
            // gone (P6) — nothing to open any more.
            return
        }
        val opened = openSealedOne(envelope)
        if (opened == null) {
            android.util.Log.d("PomboInvites", "drop invite: envelope did not open (not for us)")
            return
        }
        // Authoritative sender = the proof inside the ciphertext, recovered
        // against OUR address — never the inner `from` (self-claimed) and
        // never the publisher (a throwaway key).
        val senderId = opened.optString("sender").lowercase().ifEmpty { return }
        if (senderId == myAddress()?.lowercase()) return
        if (isBlockedPeer(senderId)) return
        val decrypted = opened.optJSONObject("message") ?: return
        if (decrypted.optString("type") != "CHANNEL_INVITE") return
        val ch = decrypted.optJSONObject("channel") ?: return
        val streamId = ch.optString("streamId").ifEmpty { return }
        val inviteId = decrypted.optString("inviteId").ifEmpty { streamId }
        if (_pendingInvites.value.any { it.inviteId == inviteId }) return
        // Membership first: an invite to a channel we are in is moot whether
        // it was answered or not — it must not backfill the dismissed list.
        if (_channels.value.any { it.messageStreamId == streamId }) {
            android.util.Log.d("PomboInvites", "drop invite for $streamId: already a member")
            return
        }
        if (inviteStore.isDismissed(inviteId)) {
            // Suppressed as pending — but the id ledger predates full record
            // retention, so a replayed invite dismissed back then has content
            // the store never kept. File it now, and the "All" view can offer
            // those historical dismissals too.
            if (inviteStore.dismissedInvites().none { it.inviteId == inviteId }) {
                inviteStore.recordDismissed(
                    com.pombo.android.data.InviteStore.StoredInvite(
                        inviteId = inviteId,
                        from = senderId,
                        streamId = streamId,
                        name = ch.optString("name").ifEmpty { streamId.substringAfter('/') },
                        type = ch.optString("type").ifEmpty { "public" },
                        password = if (ch.isNull("password")) null else ch.optString("password").ifEmpty { null }
                    )
                )
                reloadDismissedInvites()
            }
            android.util.Log.d("PomboInvites", "drop invite $inviteId: already answered")
            return
        }
        _pendingInvites.value = _pendingInvites.value + PendingInvite(
            inviteId = inviteId,
            from = senderId,
            streamId = streamId,
            name = ch.optString("name").ifEmpty { streamId.substringAfter('/') },
            type = ch.optString("type").ifEmpty { "public" },
            password = if (ch.isNull("password")) null else ch.optString("password").ifEmpty { null }
        )
        persistInvites()
    }

    /**
     * Builds a DM conversation's timeline (web: dmManager.loadDMTimeline).
     *
     * Two halves that live in different places, and that is the whole point:
     *   received — resent from MY inbox off the storage node, filtered to this
     *              peer (the inbox carries every peer's messages)
     *   sent     — read from local storage, because outgoing DMs were published
     *              to the PEER's inbox and I have no read access there
     *
     * Merged by id, sorted by timestamp. Skipping either half loses half the
     * conversation.
     */
    private suspend fun loadDmTimeline(channel: Channel, generation: Int) {
        val peer = channel.peerAddress?.lowercase() ?: return
        val me = myAddress() ?: return

        // The peer's ENS drives the header and chat-list picture. Nothing else
        // in the DM path asks for it (handleText does, but DMs go through
        // toUiMessage), so without this the ENS avatar never appeared.
        ensureEns(peer)

        // Both halves are already in hand: sent from local storage, received
        // from the inbox history the router replayed on connect. No fetching
        // here — that is exactly the web's loadDMTimeline.
        val sent = sentDmStore.load(channel.messageStreamId).mapNotNull { toUiMessage(it, me) }

        // NO resend here. The inbox replay is a once-per-session job owned by
        // [subscribeMyInbox]; everything it routed is already in [dmReceived],
        // and [applyOverrideToInboxCache] keeps edits and deletes applied to
        // that cache while the conversation is off screen. This is exactly the
        // web's loadDMTimeline (dm.js:1069), which reads sent-storage plus the
        // already-routed received messages and touches the network not at all.
        //
        // Opening a DM before the replay has finished (bridge still connecting)
        // waits for that one job rather than firing a second resend of its own.
        // The caller wraps this in INITIAL_HISTORY_SAFETY_MS, so the join is
        // bounded.
        inboxSubscribeJob?.join()
        if (!stillCurrent(generation)) return
        val receivedNow = synchronized(dmReceived) { dmReceived[peer]?.toList() ?: emptyList() }

        val merged = (sent + receivedNow)
            .distinctBy { it.id }
            .sortedBy { it.timestamp }
        if (merged.isNotEmpty()) mergeMessages(merged)

        // Now that the messages exist, fill any image from the local ledger.
        // Order matters: hydrating before the merge would map over a list that
        // does not hold them yet, which is exactly how sent images came back as
        // blank bubbles after a restart.
        merged.filter { it.isImage && it.imageBytes == null }
            .mapNotNull { it.imageId }
            .distinct()
            .forEach { hydrateFromLedger(it) }
        // Own reactions never come back from the peer's inbox either — overlay
        // the local record (web dm.js loadDMTimeline reads sentReactions the
        // same way). The peer's reactions to me replay through the inbox.
        sentReactionsStore?.forStream(channel.messageStreamId)?.let { stored ->
            stored.keys().forEach { messageId ->
                val perMsg = stored.optJSONObject(messageId) ?: return@forEach
                perMsg.keys().forEach { emoji ->
                    val users = perMsg.optJSONArray(emoji) ?: return@forEach
                    for (i in 0 until users.length()) {
                        users.optString(i).ifEmpty { null }
                            ?.let { applyReaction(messageId, emoji, it.lowercase(), true) }
                    }
                }
            }
        }
        if (!stillCurrent(generation)) return
        applyPendingOverrides()
        // Older history is reachable by windowed search even when this first
        // page came back empty — the peer may simply not have written recently.
        _hasMoreHistory.value = true
    }

    /** Web CONFIG.dm.searchWindowMs — one week per pagination step. */
    private val dmSearchWindowMs = 7L * 24 * 3600 * 1000

    data class DmPage(val loaded: Int, val hasMore: Boolean, val noResultsInWindow: Boolean)

    /**
     * One page of older DM history (web: dmManager.fetchOlderDMMessages).
     *
     * Paginates by TIME, not by count, because the inbox is shared across every
     * peer: a `last: N` fetch would be filled by whichever conversation is
     * busiest and could return nothing for this one. A week-long window returns
     * what this peer actually sent in that range.
     *
     * An empty window is not the end of the conversation — it just means they
     * did not write that week. The caller surfaces that as "Search older"
     * rather than silently scanning backwards, which would fire a chain of slow
     * resends the user never asked for.
     */
    suspend fun loadMoreDmHistory(): DmPage {
        val channel = _current.value ?: return DmPage(0, false, false)
        if (channel.type != "dm") return DmPage(0, false, false)
        val peer = channel.peerAddress?.lowercase() ?: return DmPage(0, false, false)
        val me = myAddress()?.lowercase() ?: return DmPage(0, false, false)
        if (_loadingHistory.value) return DmPage(0, _hasMoreHistory.value, false)

        val before = if (oldestTimestamp > 0L) oldestTimestamp else System.currentTimeMillis()
        val generationAtStart = switchGeneration
        _loadingHistory.value = true
        try {
            val res = bridge.call("resendWindow", JSONObject()
                .put("streamId", "$me/Pombo-DM-1")
                .put("partition", StreamConstants.P_MESSAGES)
                .put("before", before)
                .put("windowMs", dmSearchWindowMs)
                .put("budgetMs", 45_000), 60_000)

            if (!stillCurrent(generationAtStart)) return DmPage(0, false, false)

            val arr = res.optJSONArray("messages") ?: JSONArray()
            val hasMore = res.optBoolean("hasMore", false)
            val existing = _messages.value.map { it.id }.toSet()
            val fresh = mutableListOf<UiMessage>()

            // Filtering by publisher no longer works AT ALL — on sealed
            // traffic it is a throwaway key per message. Open EVERYTHING in
            // the window (one batch, ~1ms per envelope) and keep what turns
            // out to be from this peer; the alternative is not knowing who
            // wrote what (brief §5.3).
            val n = arr.length()
            val items = JSONArray()
            for (i in 0 until n) {
                val content = arr.optJSONObject(i)?.opt("content")
                items.put(
                    if (content is JSONObject && content.optInt("v") == 2 && content.has("epk")) content
                    else JSONObject.NULL
                )
            }
            val openedAll = openSealedBatch(items)

            if (!stillCurrent(generationAtStart)) return DmPage(0, false, false)

            for (i in 0 until n) {
                val entry = arr.optJSONObject(i) ?: continue
                val meta = entry.optJSONObject("meta") ?: JSONObject()
                val opened = openedAll?.takeIf { !it.isNull(i) }?.optJSONObject(i)
                val plain: JSONObject
                val sender: String
                if (opened != null) {
                    val s = opened.optString("sender").lowercase()
                    if (s.isEmpty()) continue
                    sender = s
                    plain = opened.optJSONObject("message") ?: continue
                } else {
                    // Legacy plaintext payloads (old unsealed file announces):
                    // identity is the transport publisher, which was the wallet
                    // in that era. v1 envelopes no longer open (P6) and fall
                    // out here too.
                    val envelope = entry.opt("content") as? JSONObject ?: continue
                    if (envelope.optString("type").isEmpty()) continue
                    sender = meta.optString("publisherId").lowercase()
                    plain = envelope
                }
                // My own messages live in local storage, never in my inbox;
                // and this timeline shows one peer only.
                if (sender == me || sender != peer) continue
                meta.optLong("timestamp", 0L).takeIf { it > 0 }?.let { ts ->
                    if (oldestTimestamp == 0L || ts < oldestTimestamp) oldestTimestamp = ts
                }
                plain.put("account", sender)
                plain.put("sender", sender)
                when (plain.optString("type")) {
                    "text", "image", "file_announce", "storage_file_announce" -> {
                        val id = plain.optString("id")
                        if (id.isNotEmpty() && id !in existing) {
                            toUiMessage(plain, me)?.let { fresh.add(it) }
                        }
                    }
                    "edit", "delete" -> applyOverride(plain, peer)
                }
            }

            if (!stillCurrent(generationAtStart)) return DmPage(0, false, false)
            if (fresh.isNotEmpty()) mergeMessages(fresh)
            applyPendingOverrides()
            _hasMoreHistory.value = hasMore
            return DmPage(
                loaded = fresh.size,
                hasMore = hasMore,
                noResultsInWindow = fresh.isEmpty() && hasMore
            )
        } catch (e: Exception) {
            return DmPage(0, _hasMoreHistory.value, false)
        } finally {
            // Same rule as loadMoreHistory: clearing the flag for a channel the
            // user has left would unlock the new channel's pagination early.
            if (stillCurrent(generationAtStart)) _loadingHistory.value = false
        }
    }

    /** Shared conversion for both halves of a DM timeline. */
    private fun toUiMessage(data: JSONObject, me: String): UiMessage? {
        val id = data.optString("id").ifEmpty { return null }
        val sender = data.optString("sender").ifEmpty { return null }
        // An image manifest is a message too; treating it as text is what drew
        // the empty bubble where the picture should be.
        val isImage = data.optString("type") == "image"
        val imageId = data.optString("imageId").ifEmpty { null }
        // A file announce becomes a file bubble. Two shapes reach here: the
        // full manifest (from the peer's inbox) and our own LEAN record —
        // persisted without pieceHashes on purpose (web parity), so
        // FileMetadata.from refuses it and the bubble is rebuilt by hand.
        // downloadFile guards against ever starting a fetch from a lean one.
        val file = if (data.optString("type") == "file_announce") {
            val md = data.optJSONObject("metadata")
            com.pombo.android.core.MediaController.FileMetadata.from(md) ?: run {
                val fid = md?.optString("fileId").orEmpty()
                val fs = md?.optLong("fileSize", 0L) ?: 0L
                if (fid.isEmpty() || fs <= 0) null
                else com.pombo.android.core.MediaController.FileMetadata(
                    fileId = fid,
                    fileName = md!!.optString("fileName").ifEmpty { fid },
                    fileSize = fs,
                    fileType = md.optString("fileType"),
                    pieceCount = md.optInt("pieceCount", 0),
                    pieceHashes = emptyList()
                )
            }
        } else null
        if (data.optString("type") == "file_announce" && file == null) return null
        // A storage-file announce becomes a storage bubble (Persistent File Sharing).
        val storageFile = if (data.optString("type") == "storage_file_announce")
            com.pombo.android.core.StorageMedia.StorageFileMetadata.from(data.optJSONObject("metadata")) else null
        if (data.optString("type") == "storage_file_announce" && storageFile == null) return null
        // NOTE: hydration deliberately does NOT happen here. This runs before
        // the message reaches _messages, so a hydrate started now would map
        // over a list that does not contain it yet and quietly do nothing.
        // Callers hydrate after merging.
        return UiMessage(
            id = id,
            text = data.optString("text"),
            sender = sender,
            senderName = data.optStringOrNull("senderName"),
            timestamp = data.optLong("timestamp", 0L),
            mine = sender.equals(me, ignoreCase = true),
            isImage = isImage,
            imageId = imageId,
            imageMime = if (isImage) data.optString("finalMime", "image/jpeg") else null,
            file = file,
            storageFile = storageFile,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender),
            edited = data.optBoolean("_edited", false),
            // DMs are authenticated by the ECDH envelope itself: only the two
            // parties can produce one, so there is no per-message badge.
            verified = true
        )
    }

    suspend fun sendVideo(
        fileName: String,
        fileSize: Long,
        mimeType: String,
        openInput: () -> java.io.InputStream
    ) = files.sendVideo(fileName, fileSize, mimeType, openInput)

    suspend fun sendFile(
        fileName: String,
        fileSize: Long,
        mimeType: String,
        openInput: () -> java.io.InputStream
    ) = files.sendFile(fileName, fileSize, mimeType, openInput)

    /** Sends an E2E-encrypted DM to the peer's inbox (partition 0). */
    private suspend fun sendDm(channel: Channel, text: String) {
        val peer = channel.peerAddress ?: return
        val sender = myAddress() ?: return
        val id = Protocol.generateMessageId()
        val timestamp = System.currentTimeMillis()
        // Seed own ENS from the cache like every received bubble does — the
        // sender's own name/avatar were the one case nothing ever filled in
        // live (the timeline rebuild on reopen was what fixed them).
        mergeMessages(listOf(UiMessage(
            id, text, sender, myUsername(), timestamp,
            mine = true, pending = true, verified = true,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender)
        )))

        // Wire message, post-D6: no app-layer signature, no `sender`, no
        // `channelId`. Identity travels as the proof inside the sealed
        // envelope (`p`, added by the bridge); a signature here would be a
        // third authority covering the same claim, and `sender` in the
        // plaintext could name someone the proof doesn't vouch for.
        val wire = JSONObject()
            .put("type", "text").put("id", id).put("text", text)
            .put("senderName", myUsername() ?: JSONObject.NULL)
            .put("timestamp", timestamp).put("replyTo", JSONObject.NULL)

        publishTextWithRetry {
            publishContent(
                channel.messageStreamId, StreamConstants.P_MESSAGES,
                wire, password = null, dmPeer = peer
            )
        }
        confirmMessage(id)
        // Persist only after the publish succeeded, so a failed send does not
        // leave a message in the history that the peer never received. This is
        // the only copy we will ever have of it — and it keeps `sender`,
        // which the timeline's toUiMessage needs; the field never travels.
        sentDmStore.add(channel.messageStreamId, JSONObject(wire.toString()).put("sender", sender))
        onLocalStateChanged()
        // The DM branch returns out of sendMessage BEFORE its wake-signal
        // call, so no Android DM ever woke the peer's push relay. Web dm.js
        // fires the same signal after the publish: 'channel:' tag over the
        // peer's inbox, which is what their relay row is registered under.
        sendWakeSignal(channel)
    }

    /**
     * A peer's DM typing/presence control (sealed-sender v2 envelope on my
     * `-2`). Opens it with our static key, identifies the peer from the proof,
     * and feeds it into the same presence/typing state channels use, so the
     * DM header's "Online/Offline" and the typing indicator behave identically.
     */
    private fun handleDmControl(contentRaw: String, meta: JSONObject) {
        val envelope = try { JSONObject(contentRaw) } catch (e: Exception) { return }
        if (envelope.optInt("v") != 2 || !envelope.has("epk")) return  // v1: gone with P6
        scope.launch {
            // Open FIRST — the publisher is a throwaway key, so every check
            // (own echo, blocked, left) can only run on the sender the proof
            // recovers to (web routeInboxControl does the same).
            val opened = openSealedOne(envelope) ?: return@launch
            val sender = opened.optString("sender").lowercase().ifEmpty { return@launch }
            if (sender == myAddress()?.lowercase()) return@launch   // my own echo
            if (isBlockedPeer(sender)) return@launch
            if (dmLeftAt(sender) > 0) return@launch
            val plain = opened.optJSONObject("message") ?: return@launch
            // Only react while THIS peer's DM is the open channel — a stray
            // heartbeat must not light up another conversation.
            if (_current.value?.peerAddress?.lowercase() != sender) return@launch
            when (plain.optString("type")) {
                "presence" -> synchronized(online) {
                    online[sender] = plain.optLong("lastActive", System.currentTimeMillis())
                    onlineNames[sender] = plain.optStringOrNull("nickname")
                    _onlineCount.value = online.size
                    _onlineUsers.value = online.keys.map { OnlineUser(it, onlineNames[it]) }.sortedBy { it.address }
                }
                "typing" -> markTyping(sender, plain.optStringOrNull("nickname"))
            }
        }
    }

    /** Feeds a live inbox arrival through the same router as history. */
    private fun handleIncomingDm(contentRaw: String, meta: JSONObject) {
        val publisherId = meta.optString("publisherId").lowercase().ifEmpty { return }
        val envelope = try { JSONObject(contentRaw) } catch (e: Exception) { return }
        // Sealed envelopes and legacy plaintext payloads (old unsealed file
        // announces) both route; everything else is noise. The router owns
        // the open-first inversion.
        if (envelope.optString("e") != "aes-256-gcm" && envelope.optString("type").isEmpty()) return
        scope.launch { routeInboxMessage(envelope, publisherId) }
    }

    private suspend fun verifyPasswordChallenge(adminStreamId: String, password: String) {
        var lastError: Exception? = null
        repeat(4) { attempt ->
            try {
                val res = bridge.call("resend", JSONObject()
                    .put("streamId", adminStreamId)
                    .put("partition", StreamConstants.ADMIN_PASSWORD_CHALLENGE)
                    .put("last", 1))
                val arr = res.optJSONArray("messages") ?: JSONArray()
                if (arr.length() == 0) throw ChallengeNotFound()
                val content = arr.getJSONObject(arr.length() - 1).get("content")
                if (content !is String) throw WrongPassword()
                val decoded = try {
                    JSONObject(PomboCrypto.decryptString(content, password))
                } catch (e: Exception) {
                    throw WrongPassword()
                }
                if (decoded.optString("type") != "PASSWORD_CHALLENGE" ||
                    decoded.optString("magic") != StreamConstants.PASSWORD_CHALLENGE_MAGIC
                ) throw WrongPassword()
                return  // valid
            } catch (e: WrongPassword) {
                throw IllegalStateException("Incorrect password for this channel")
            } catch (e: ChallengeNotFound) {
                lastError = e
                delay(1500)
            } catch (e: Exception) {
                lastError = e
                delay(1500)
            }
        }
        throw IllegalStateException("Could not verify the channel password (${lastError?.message ?: "challenge not found"})")
    }

    private class WrongPassword : Exception()
    private class ChallengeNotFound : Exception()

    /** Non-throwing challenge probe result (web verifyPasswordChallenge's
     *  `{found, valid, ts}` — streamr.js). `ts` is the payload timestamp of
     *  the retained challenge, 0 when absent/undecryptable. */
    private data class ChallengeProbe(val found: Boolean, val valid: Boolean, val ts: Long)

    /**
     * Single-shot, non-throwing variant of [verifyPasswordChallenge] for the
     * TTL republish check on owner open (docs/TTL_REPUBLISH_PLAN.md). The
     * join flow keeps the strict throwing wrapper (fail-closed, retries);
     * this one only answers "is a valid challenge retained, and how old?".
     */
    private suspend fun probePasswordChallenge(adminStreamId: String, password: String): ChallengeProbe {
        return try {
            val res = bridge.call("resend", JSONObject()
                .put("streamId", adminStreamId)
                .put("partition", StreamConstants.ADMIN_PASSWORD_CHALLENGE)
                .put("last", 1), 30_000)
            val arr = res.optJSONArray("messages") ?: JSONArray()
            if (arr.length() == 0) return ChallengeProbe(found = false, valid = false, ts = 0L)
            val content = arr.getJSONObject(arr.length() - 1).opt("content")
            // Challenge is always published encrypted — a plaintext entry
            // cannot prove password knowledge and counts as invalid.
            if (content !is String) return ChallengeProbe(found = true, valid = false, ts = 0L)
            val decoded = try {
                JSONObject(PomboCrypto.decryptString(content, password))
            } catch (e: Exception) {
                return ChallengeProbe(found = true, valid = false, ts = 0L)
            }
            val ok = decoded.optString("type") == "PASSWORD_CHALLENGE" &&
                decoded.optString("magic") == StreamConstants.PASSWORD_CHALLENGE_MAGIC
            ChallengeProbe(found = true, valid = ok, ts = if (ok) decoded.optLong("ts", 0L) else 0L)
        } catch (e: Exception) {
            // Infra failure counts as "not retained", like the web (its
            // verifyPasswordChallenge swallows resend errors into found:false
            // and the caller republishes). The challenge publish is idempotent
            // and joiners fail closed on a missing one — erring towards a
            // redundant publish is the safe side.
            Log.d(TAG, "probePasswordChallenge resend failed (treating as not retained): ${e.message}")
            ChallengeProbe(found = false, valid = false, ts = 0L)
        }
    }

    fun openChannel(messageStreamId: String) {
        val channel = _channels.value.find { it.messageStreamId == messageStreamId } ?: return
        unreadStore.clear(messageStreamId)
        openInternal(channel, preview = false)
    }

    /**
     * Opens a public channel WITHOUT adding it to the user's list (web:
     * PreviewModeUI). Everything else behaves like a normal open — history,
     * moderation and presence — so the only difference is persistence and the
     * Join affordance in the header.
     */
    fun previewChannel(preview: ExploreChannel) {
        val messageStreamId = preview.messageStreamId
        _channels.value.find { it.messageStreamId == messageStreamId }?.let {
            openChannel(messageStreamId)
            return
        }
        // Gated (TOKEN/NFT holder browsing before committing): the preview
        // channel must carry the FULL gated context — the gate drives the
        // clone transport/subscribe, and the -4 is where the epoch keys come
        // from. Without them the open dies on the SDK's subscribe guard.
        val gated = preview.type == "gated" && preview.gateAddress != null
        openInternal(
            Channel(
                messageStreamId = messageStreamId,
                ephemeralStreamId = StreamConstants.deriveEphemeralId(messageStreamId),
                adminStreamId = StreamConstants.deriveAdminId(messageStreamId),
                keysStreamId = if (gated) StreamConstants.deriveKeysId(messageStreamId) else "",
                name = preview.name.ifEmpty { messageStreamId.substringAfterLast('/') },
                type = preview.type.ifEmpty { "public" },
                gateAddress = if (gated) preview.gateAddress else null,
                // Carry the Explore metadata so the settings sheet isn't blank —
                // anything listed in Explore is by definition exposure=visible.
                description = preview.description,
                language = preview.language,
                category = preview.category,
                exposure = "visible",
                createdBy = messageStreamId.substringBefore('/')
            ),
            preview = true
        )
    }

    /** Gate mode for an arbitrary clone (Explore tap routing); null = unreadable. */
    suspend fun gateModeOf(gateAddress: String): Int? = try {
        bridge.call("gateInfo", JSONObject().put("gate", gateAddress))
            .optInt("mode", -1).takeIf { it >= 0 }
    } catch (e: Exception) { null }

    /** Whether OUR account passes a gate right now (cached, fail-closed). */
    suspend fun gateAccessSelf(gateAddress: String): Boolean {
        val me = myAddress() ?: return false
        return try {
            bridge.call("gateCheckAccess", JSONObject()
                .put("gate", gateAddress).put("user", me)).optBoolean("access", false)
        } catch (e: Exception) { false }
    }

    /**
     * Turns the open preview into a real channel (web: addPreviewToList).
     * Goes through the normal join so permissions, type and metadata are
     * resolved exactly as they would be from the Join dialog.
     */
    suspend fun joinPreview(): Channel? {
        val preview = _current.value?.takeIf { _isPreview.value } ?: return null
        val joined = joinChannel(preview.messageStreamId)
        // The streams are already subscribed — just swap in the stored channel.
        _current.value = joined
        _isPreview.value = false
        return joined
    }

    /**
     * Fills in stream ids a stored channel is missing. Entries persisted by
     * older builds can carry an empty adminStreamId, and every admin read is
     * wrapped in a try/catch — so moderation just silently never applied and
     * hidden messages stayed visible on that device while a freshly opened
     * copy of the same channel looked correct. The web never had this: it
     * derives on every use (`channel.adminStreamId || deriveAdminId(...)`).
     */
    private fun healStreamIds(channel: Channel): Channel {
        if (channel.type == "dm") return channel
        var healed = channel
        if (healed.adminStreamId.isEmpty()) {
            healed = healed.copy(adminStreamId = StreamConstants.deriveAdminId(healed.messageStreamId))
        }
        if (healed.ephemeralStreamId.isEmpty()) {
            healed = healed.copy(
                ephemeralStreamId = healed.messageStreamId
                    .replace(Regex("-1$"), StreamConstants.SUFFIX_EPHEMERAL)
            )
        }
        if (healed.keysStreamId.isEmpty() && healed.type == "gated") {
            healed = healed.copy(keysStreamId = StreamConstants.deriveKeysId(healed.messageStreamId))
        }
        if (healed.type == "gated" && healed.gateAddress == null) {
            // Fire-and-forget repair from the stream's on-chain metadata (g).
            // Publishes on this channel fail loudly until it lands.
            val sid = healed.messageStreamId
            scope.launch {
                try {
                    val info = bridge.call("getStreamInfo", JSONObject().put("streamId", sid))
                    val desc = info.optJSONObject("metadata")?.optString("description") ?: ""
                    val meta = JSONObject(desc)
                    val g = meta.optString("g").lowercase()
                        .takeIf { Regex("^0x[0-9a-f]{40}$").matches(it) } ?: return@launch
                    // The author-visibility flag lives in the same metadata
                    // and is immutable — repair it together with the gate.
                    val mode = if (meta.optInt("m") == 1) "members" else "everyone"
                    _channels.value = _channels.value.map {
                        if (it.messageStreamId == sid) it.copy(gateAddress = g, authorMode = mode) else it
                    }
                    store.save(_channels.value)
                    if (_current.value?.messageStreamId == sid) {
                        _current.value = _channels.value.find { it.messageStreamId == sid }
                    }
                    Log.i(TAG, "Gate address repaired from metadata: " + g)
                } catch (e: Exception) {
                    Log.w(TAG, "Gate repair failed: ${e.message}")
                }
            }
        }
        if (healed !== channel) {
            _channels.value = _channels.value.map {
                if (it.messageStreamId == healed.messageStreamId) healed else it
            }
            store.save(_channels.value)
        }
        return healed
    }

    private fun openInternal(channelIn: Channel, preview: Boolean) {
        val channel = healStreamIds(channelIn)
        // Paint the cached image synchronously, before any coroutine or network
        // work — otherwise the header waits behind the history resend.
        _channelImage.value = imageStore.cached(channel.adminStreamId)
        // Stop the previous open before starting this one. Without this, both
        // coroutines stayed live on the shared viewModelScope and the loser
        // kept writing into the winner's state.
        openJob?.cancel()
        openJob = scope.launch {
            // The transition — teardown, state reset, subscribe — is serialised
            // (see [prepareChannel]). The history loads below run OUTSIDE that
            // lock on purpose: they can take 30s and must never hold up the next
            // channel switch.
            val generation = prepareChannel(channel, preview)
            if (!stillCurrent(generation)) return@launch
            // Bring back any in-progress upload bubble for this channel before the
            // history loads merge around it (the upload survived the switch).
            restoreStorageUploadBubbles(channel)

            if (channel.type == "dm") {
                _initialLoad.value = true
                withTimeoutOrNull(INITIAL_HISTORY_SAFETY_MS) { loadDmTimeline(channel, generation) }
                if (!stillCurrent(generation)) return@launch
                _initialLoad.value = false
                // Publish my presence heartbeat (encrypted) to the peer's `-2`,
                // same as a channel — this is how the peer sees me online and how
                // "Online/Offline" flips in their header.
                startPresence(channel)
            } else if (!channel.writeOnly) {
                // Paid standing resolves alongside history — the expiry strip
                // and the expired empty-state depend on it, and the chain read
                // must never hold up the open.
                if (channel.type == "gated") {
                    launch {
                        val status = resolvePaidStatus(channel)
                        if (stillCurrent(generation)) _paidStatus.value = status
                    }
                }
                // History: content + overrides
                // Image refresh runs alongside history instead of after it —
                // the cached one is already on screen.
                loadChannelImage(channel)
                // Gate the render until history AND its overrides are applied.
                _initialLoad.value = true
                // Safety net, mirroring the web's INITIAL_HISTORY_SAFETY_MS: a
                // resend against an empty or legacy stream does not reliably
                // signal completion, and waiting on the 60s bridge timeout
                // strands the user on the spinner. Release the gate after 30s
                // and let the loads finish in the background — they publish
                // into the same flows, so late history still lands.
                // Admin state (-3) runs ALONGSIDE the history resends, not after
                // them: it is an independent stream and chaining it made the
                // gate pay a third round trip in series.
                //
                // It stays INSIDE the gate on purpose. The chat filters on
                // `hiddenIds`/`bannedMembers` at render time, so opening the
                // gate before -3 has landed would flash moderated messages for
                // as long as that resend takes. The web accepts that window; we
                // do not, and the cost of closing it is now zero because the
                // fetch overlaps the history anyway.
                // Epoch keys BEFORE the -1 history pull, so the envelopes the
                // resend returns can already be opened (bootstrap as admin, or
                // request as member). FAST PATH: with persisted keys+announces
                // the channel decrypts immediately — the -4 resend leaves the
                // open's critical path and becomes a background reconcile
                // (measured: it added ~0.5-2s to every warm open for nothing).
                if (isEpochChannel(channel) && channel.keysStreamId.isNotEmpty()) {
                    epochKeys.loadPersistedState(channel.messageStreamId)
                    if (epochKeys.hasCurrentKey(channel.messageStreamId)) {
                        android.util.Log.d("PomboPerf", "epochKeys ${channel.name}: warm (persisted), reconcile in background")
                        launch {
                            // After the open's own resends: the WebView JS
                            // thread is single — a concurrent -4 drain here
                            // pushed the P0 history call into the seconds.
                            delay(8_000)
                            // Reading works without this pass, but it is what
                            // answers retained requests, re-announces and
                            // picks up new epochs/revs — a silent give-up
                            // leaves all of that undone until the next open.
                            // Same capped backoff as the cold path.
                            var attempt = 0
                            while (attempt < 5) {
                                if (!stillCurrent(generation)) return@launch
                                try {
                                    epochKeys.ensureChannelKeys(
                                        channel.messageStreamId, channel.keysStreamId,
                                        keysRetentionDays(channel),
                                        allowMint = System.currentTimeMillis() - channel.createdAt < 3_600_000,
                                        memberCount = channel.members.size,
                                        gated = channel.type == "gated")
                                    if (attempt > 0) Log.i(TAG, "Background epoch reconcile recovered on retry $attempt")
                                    return@launch
                                } catch (e: Exception) {
                                    Log.w(TAG, "Background epoch reconcile failed (attempt ${attempt + 1}/5)", e)
                                }
                                attempt += 1
                                delay(minOf(15_000L * attempt, 60_000L))
                            }
                        }
                    } else {
                        val tEnsure = System.currentTimeMillis()
                        try {
                            epochKeys.ensureChannelKeys(
                                channel.messageStreamId, channel.keysStreamId,
                                keysRetentionDays(channel),
                                    allowMint = System.currentTimeMillis() - channel.createdAt < 3_600_000,
                                    memberCount = channel.members.size,
                                    gated = channel.type == "gated")
                        } catch (e: Exception) {
                            Log.w(TAG, "Epoch key setup failed (messages will wait for key): ${e.message}")
                            // A cold node can miss every entrypoint on the
                            // first try, and the transport then keeps
                            // returning the cached failure — for the ADMIN
                            // that means the bootstrap announce never went
                            // out at all. Retry on a backoff while the
                            // channel stays open.
                            launch {
                                var attempt = 0
                                while (attempt < 5) {
                                    attempt += 1
                                    delay(minOf(15_000L * attempt, 60_000L))
                                    if (!stillCurrent(generation)) return@launch
                                    try {
                                        epochKeys.ensureChannelKeys(
                                            channel.messageStreamId, channel.keysStreamId,
                                            keysRetentionDays(channel),
                                            allowMint = System.currentTimeMillis() - channel.createdAt < 3_600_000,
                                            memberCount = channel.members.size,
                                            gated = channel.type == "gated")
                                        Log.i(TAG, "Epoch key setup recovered on retry $attempt")
                                        _waitingForKeys.value = !epochKeys.hasCurrentKey(channel.messageStreamId)
                                        return@launch
                                    } catch (re: Exception) {
                                        Log.w(TAG, "Epoch key setup retry $attempt failed: ${re.message}")
                                    }
                                }
                            }
                        }
                        android.util.Log.d("PomboPerf",
                            "epochKeys ${channel.name}: cold ensure=${System.currentTimeMillis() - tEnsure}ms")
                        _waitingForKeys.value = !epochKeys.hasCurrentKey(channel.messageStreamId)
                        // A first KEY_REQUEST from a cold node can miss every
                        // live subscriber (§7.2 R2) — waiting the full backoff
                        // turned that into a 60s blank channel. Retry fast
                        // while the topology warms; stop as soon as keys land.
                        launch {
                            repeat(com.pombo.android.core.EpochKeyManager.RETRY_LOOP_ATTEMPTS) {
                                delay(10_000)
                                if (!stillCurrent(generation)) return@launch
                                if (epochKeys.hasCurrentKey(channel.messageStreamId)) return@launch
                                try {
                                    epochKeys.retryRequestIfWaiting(channel.messageStreamId, channel.keysStreamId)
                                } catch (e: Exception) { /* next lap retries */ }
                            }
                        }
                    }
                    if (!stillCurrent(generation)) return@launch
                    launch { rotateForPendingBans(channel) }
                }
                val loads = launch {
                    listOf(
                        launch { loadHistory(channel, generation) },
                        launch { loadAdminState(channel, generation) }
                    ).joinAll()
                }
                withTimeoutOrNull(INITIAL_HISTORY_SAFETY_MS) { loads.join() }
                // Releasing the loading gate and starting presence for a channel
                // the user has left is how A's heartbeat used to replace B's —
                // startPresence cancels presenceJob unconditionally.
                if (!stillCurrent(generation)) return@launch
                _initialLoad.value = false
                startPresence(channel)
                // Re-announce anything we can still serve here. A seeder that
                // says nothing is invisible, so a file this device holds would
                // look dead to anyone who arrived while we were away.
                launch {
                    try {
                        // Rehydrate first: seeds finished in a PREVIOUS session
                        // only exist on disk until this runs, and this is the
                        // one moment we hold the channel's password (the
                        // registry deliberately does not store it).
                        withContext(Dispatchers.IO) {
                            media.restoreSeedsFor(channel.messageStreamId, channel.password)
                        }
                        media.reannounceForChannel(channel.messageStreamId, channel.password)
                    } catch (e: Exception) {
                        Log.w(TAG, "seed re-announce failed: ${e.message}")
                    }
                }
                // Moderation convergence (web adminStatePoller, 30s): -3 has
                // no live subscription, so after the on-open load this poller
                // is what catches anything the admin_invalidate signal missed.
                startAdminPoller(channel, generation)
                // TTL-aware owner republish of the -3 artifacts (web
                // _ttlRepublishOnOpen; docs/TTL_REPUBLISH_PLAN.md). Runs after
                // loadAdminState so adminRevs/adminTs and the moderation flows
                // are populated. Fire-and-forget: must never hold the open.
                launch {
                    try { ttlRepublishOnOpen(channel, generation) } catch (e: Exception) {
                        Log.d(TAG, "TTL republish check failed (will retry next open): ${e.message}")
                    }
                }
            }
            // Presence is live (or, for a write-only channel, will never be):
            // either way the header can stop saying "Connecting…" and show the
            // count. Set outside the branches so no channel type is stranded.
            if (stillCurrent(generation)) _presenceReady.value = true
        }
    }

    /**
     * Teardown + state reset + subscribe, run under [channelSwitchMutex] so a
     * close that is still unwinding cannot interleave with the next open.
     *
     * Without the lock the unsubscribes of the channel being left could resume
     * *after* the next channel had already subscribed — same stream ids, so the
     * close silently tore down the new channel's subscriptions and left it on
     * screen receiving nothing.
     *
     * @return the viewing session this open owns; callers must pass it to every
     *   async loader and re-check it with [stillCurrent] after each suspension.
     */
    private suspend fun prepareChannel(channel: Channel, preview: Boolean): Int =
        channelSwitchMutex.withLock {
            closeCurrentInternal()
            _current.value = channel
            _isPreview.value = preview
            // Web preloadDeletePermission: fire-and-forget so the sheet and the
            // context menu have an answer before the user asks for one.
            refreshModerationPermission(channel, preview)
            _messages.value = emptyList()
            _reactions.value = emptyMap()
            _pins.value = emptyList()
            _hiddenIds.value = emptySet()
            _bannedMembers.value = emptySet()
            val generation = ++switchGeneration
            oldestTimestamp = 0L
            synchronized(this) { pendingOverrides.clear(); deletedIds.clear() }
            _hasMoreHistory.value = false
            _loadingHistory.value = false
            // Both are per-channel verdicts — carrying them across a switch
            // shows the previous room's key/subscription state on this one.
            _waitingForKeys.value = false
            _paidStatus.value = null
            channelImageRev = 0
            synchronized(online) { online.clear(); onlineNames.clear() }
            clearTyping()   // whoever was typing was typing in the OTHER room
            _onlineUsers.value = emptyList()
            _onlineCount.value = 0
            _presenceReady.value = false

            if (channel.type == "dm") {
                // DMs arrive on MY inbox (subscribed globally), not the peer's inbox.
                subscribeMyInbox()
                // Typing and presence for a DM are ECDH-encrypted control on the
                // ephemeral inbox pair: I publish to the PEER's `-2` and receive
                // on MY OWN `-2` (web dmManager.subscribeDMEphemeral). Subscribe
                // it on open, tear it down on close.
                val myEph = "${myAddress()?.lowercase()}/Pombo-DM-2"
                myDmEphemeralId = myEph
                subscribeQuiet(myEph, StreamConstants.EPH_CONTROL)
            } else if (!channel.writeOnly) {
                // Real-time first (do not miss messages during the resend).
                // The admin stream (-3) is deliberately NOT subscribed live —
                // web model: every subscribed partition is its own overlay
                // topology with its own peer connections, and moderation has
                // two cheaper paths that share what is already open: the
                // admin_invalidate snapshot on -2/P0 (instant) and the 30s
                // poller, which resends straight from the storage node.
                val tSub = System.currentTimeMillis()
                subscribeQuiet(channel.messageStreamId, StreamConstants.P_MESSAGES)
                subscribeQuiet(channel.messageStreamId, StreamConstants.P_CONTROL)
                subscribeQuiet(channel.ephemeralStreamId, StreamConstants.EPH_CONTROL)
                // Keys stream (-4): live epoch-key protocol for gated channels
                if (isEpochChannel(channel) && channel.keysStreamId.isNotEmpty()) {
                    subscribeQuiet(channel.keysStreamId, StreamConstants.P_KEY_EXCHANGE)
                }
                android.util.Log.d("PomboPerf",
                    "subscribes ${channel.name}: ${System.currentTimeMillis() - tSub}ms")
                // Cancelled mid-transition: undo our own subscribes, since the
                // next open's teardown has already run and will not cover them.
                if (!stillCurrent(generation)) unsubscribeAll(channel)
            }
            generation
        }

    fun closeCurrent() { scope.launch { channelSwitchMutex.withLock { closeCurrentInternal() } } }

    private suspend fun closeCurrentInternal() {
        val channel = _current.value ?: return
        // Closing ends the viewing session too, so work in flight for this
        // channel cannot land after we reopen the very same channel.
        val generation = ++switchGeneration
        presenceJob?.cancel(); presenceJob = null
        adminPollJob?.cancel(); adminPollJob = null
        _current.value = null
        _isPreview.value = false
        unsubscribeAll(channel)
        // On-demand DM ephemeral (my `-2`): drop it when the DM closes.
        //
        // The media partitions need a DIFFERENT question than the channel case
        // in [unsubscribeAll]. There is exactly ONE DM ephemeral stream per
        // user, shared by every conversation, so asking "is this conversation
        // idle?" would tear it down and cut seeding for all the others at once.
        // Hence hasActiveDMTransfers(), which spans conversations.
        //
        // When they do survive, releasing them later is the transfer engine's
        // job — it holds the last-transfer-finished moment. The id is derivable
        // from our own address, so nothing is lost by clearing the field here.
        myDmEphemeralId?.let { eph ->
            unsubscribeQuiet(eph, StreamConstants.EPH_CONTROL)
            if (media.hasActiveDMTransfers()) {
                Log.i(TAG, "keeping DM media partitions alive for $eph")
            } else {
                media.releaseMediaPartitions(eph)
            }
            myDmEphemeralId = null
        }
        // Only wipe if nothing has opened in the meantime. The unsubscribes
        // above suspend, so a close started by the back button could otherwise
        // resume after the next channel had already loaded and blank it.
        if (!stillCurrent(generation)) return
        _messages.value = emptyList()
        _reactions.value = emptyMap()
    }

    /**
     * Leaves a channel's streams behind.
     *
     * The media partitions (-2/P1, P2) survive when the channel still has a
     * download or a seed in flight — mirroring web subscriptionManager's
     * downgradeToBackground. Navigating away is not the same as stopping: on
     * the web an upload keeps serving while the user reads another channel, and
     * without this exception the leecher on the far side just stalls.
     *
     * -1 and -2/P0 (presence, typing) always go: those are only meaningful for
     * the channel on screen.
     */
    private suspend fun unsubscribeAll(channel: Channel) {
        unsubscribeQuiet(channel.messageStreamId, StreamConstants.P_MESSAGES)
        unsubscribeQuiet(channel.messageStreamId, StreamConstants.P_CONTROL)
        unsubscribeQuiet(channel.ephemeralStreamId, StreamConstants.EPH_CONTROL)
        if (isEpochChannel(channel) && channel.keysStreamId.isNotEmpty()) {
            unsubscribeQuiet(channel.keysStreamId, StreamConstants.P_KEY_EXCHANGE)
        }
        if (media.hasActiveTransfers(channel.messageStreamId)) {
            Log.i(TAG, "keeping media partitions alive for ${channel.messageStreamId}")
        } else {
            media.releaseMediaPartitions(channel.ephemeralStreamId)
        }
    }

    /**
     * Re-activates subscriptions after a bridge reconnect.
     *
     * A reconnect rebuilds the JS world, so every subscription is gone — including
     * media partitions that no channel switch ever asked to be dropped. Restoring
     * them here is what stops a network change from silently killing a transfer
     * that is still tracked and still believed to be running.
     */
    fun resubscribeCurrent() {
        val channel = _current.value ?: return
        scope.launch {
            if (!channel.writeOnly) {
                subscribeQuiet(channel.messageStreamId, StreamConstants.P_MESSAGES)
                subscribeQuiet(channel.messageStreamId, StreamConstants.P_CONTROL)
                subscribeQuiet(channel.ephemeralStreamId, StreamConstants.EPH_CONTROL)
                if (isEpochChannel(channel) && channel.keysStreamId.isNotEmpty()) {
                    subscribeQuiet(channel.keysStreamId, StreamConstants.P_KEY_EXCHANGE)
                }
            }
            // Every stream with a live transfer, not just this channel's: the
            // seeding exception exists precisely so transfers outlive the
            // channel being on screen, and those are the ones nothing else
            // would ever bring back.
            for ((eph, password) in media.activeEphemeralStreams()) {
                media.ensureMediaPartitions(eph, password)
            }
        }
    }

    fun removeChannel(messageStreamId: String) {
        scope.launch {
            val leaving = _channels.value.firstOrNull { it.messageStreamId == messageStreamId }
            if (_current.value?.messageStreamId == messageStreamId) closeCurrentInternal()
            _channels.value = _channels.value.filterNot { it.messageStreamId == messageStreamId }
            store.save(_channels.value)
            // Rotate the channel pseudonym on a GENUINE leave (never on a mere
            // view switch — closeCurrent keeps it: peers mid-transfer know the
            // current publisher). Rejoining gets a fresh key, so yesterday's
            // pseudonym cannot be tied to today's. Guard: a leave with a
            // transfer still running keeps the identity (web
            // hasActiveMediaTransfers) — peers are mid-transfer against the
            // publisher they already know; it dies with the session anyway.
            val transfersActive = leaving?.ephemeralStreamId?.let { eph ->
                media.activeEphemeralStreams().containsKey(eph)
            } ?: false
            if (!transfersActive) {
                com.pombo.android.core.ChannelIdentities.drop(messageStreamId)
                runCatching { bridge.call("dropChannelIdentity", JSONObject().put("streamId", messageStreamId)) }
            }
            // Native: drop the channel's epoch keys (runtime + persisted)
            if (isEpochChannel(leaving)) {
                runCatching { epochKeys.forgetChannel(messageStreamId) }
            }
            // A DM leave is SOFT (web dmLeftAt): messages older than this stay
            // gone, a new one resurfaces the conversation. Without the
            // tombstone the next inbox history replay recreated the room from
            // old messages the moment the app reconnected. Synced as the
            // web's dmLeftAt slice.
            if (leaving?.type == "dm") {
                leaving.peerAddress?.lowercase()?.let { store.markLeft("dm:$it") }
                onSliceTouched("dmLeftAt")
            }
            // Otherwise the badge count outlives the channel and reappears if
            // the same stream is ever rejoined.
            unreadStore.clear(messageStreamId)
            // The conversation's images have no business staying on disk after
            // the user walks away (web clearImagesForStream on leave).
            blobStore.clearForStream(messageStreamId)
            // Tombstone, so a sync pull cannot resurrect the channel from an
            // older snapshot taken before the user left.
            store.markLeft(messageStreamId)
            onLocalStateChanged()
        }
    }

    /**
     * Leave a DM and permanently ignore the peer (web `leaveChannel(…, {block:true})`).
     *
     * Unlike a soft leave, this writes no `dmLeftAt` timestamp: a soft leave is
     * designed to let a new message resurface the conversation, which is
     * exactly what a block must NOT do. The block is recorded first so a
     * message arriving mid-teardown is already ignored.
     */
    fun blockPeer(messageStreamId: String) {
        scope.launch {
            val channel = _channels.value.firstOrNull { it.messageStreamId == messageStreamId }
            val peer = channel?.peerAddress?.lowercase()
            if (peer != null) {
                persistBlockedPeer(peer)
                // Drop the local half of the conversation too, as the web does.
                sentDmStore.clear(messageStreamId)
                peerPubKeys.remove(peer)
            }
            removeChannel(messageStreamId)
        }
    }

    /**
     * Delete the channel ON-CHAIN: all three streams, then drop it locally.
     *
     * Distinct from [removeChannel], which only forgets the channel on this
     * device and leaves the streams standing so anyone (including you) can
     * rejoin with the stream id. This one is irreversible for everybody.
     *
     * Each stream is deleted separately and failures are collected rather than
     * aborting: a partial delete is a real outcome, and the user needs to know
     * which streams are still out there instead of seeing one generic error.
     */
    suspend fun deleteChannel(messageStreamId: String): List<String> {
        val channel = _channels.value.firstOrNull { it.messageStreamId == messageStreamId }
        // Defensive owner-guard (web channels.js:4225): the UI already hides
        // the action, but a stale composition must not fire on-chain deletes.
        if (channel != null && !amOwner(channel)) {
            throw IllegalStateException("Only the channel owner can delete it")
        }
        val ids = listOfNotNull(
            messageStreamId,
            channel?.ephemeralStreamId,
            channel?.adminStreamId,
            channel?.takeIf { isEpochChannel(it) }?.let {
                it.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(messageStreamId) }
            }
        ).distinct()
        val failed = mutableListOf<String>()
        for (id in ids) {
            try {
                retry(3) { bridge.call("deleteStream", JSONObject().put("streamId", id), 120_000) }
            } catch (e: Exception) {
                Log.w(TAG, "deleteStream failed for $id: ${e.message}")
                failed.add(id)
            }
        }
        // Nothing was deleted at all (no gas, RPC down): keep the channel —
        // forgetting it locally would hide a still fully working channel and
        // read as success. Partial failure still drops it, because a channel
        // whose message stream is gone just fails on every open.
        if (failed.size == ids.size && ids.isNotEmpty()) return failed
        removeChannel(messageStreamId)
        return failed
    }

    /** Replaces the channel list wholesale after a sync merge. */
    fun replaceChannels(channels: List<Channel>) {
        _channels.value = channels
        store.save(channels)
    }

    /** Set by the ViewModel to schedule a debounced sync push. */
    @Volatile var onLocalStateChanged: () -> Unit = {}

    /** Stamps a sync slice's mutation timestamp (ViewModel sliceTouched) —
     *  a slice never stamped always loses the latest-wins merge. */
    @Volatile var onSliceTouched: (String) -> Unit = {}

    /**
     * Raised for a message from someone else. The ViewModel decides whether to
     * post a system notification — it knows whether the app is in front and
     * which conversation is open, which this layer does not.
     */
    @Volatile var onIncomingMessage: (channel: Channel, sender: String, preview: String) -> Unit =
        { _, _, _ -> }

    /**
     * True while the initial resend is still running. The web gates rendering
     * on this (`channel.initialLoadInProgress`) so a message that is about to
     * be deleted or edited by an override later in the same resend is never
     * painted first — without the gate, history flashes deleted messages.
     */
    private val _initialLoad = MutableStateFlow(false)
    val initialLoad: StateFlow<Boolean> = _initialLoad.asStateFlow()

    /** Web: INITIAL_HISTORY_SAFETY_MS in channels.js. */
    private val INITIAL_HISTORY_SAFETY_MS = 30_000L

    /**
     * Decrypts a password page's sealed payloads before the ordered merge.
     *
     * The page goes to the bridge's `pwDecryptBatch` in ONE call: Android's
     * javax.crypto PBKDF2 is Bouncy Castle in pure Java (~1s per message on a
     * phone — a 50-message page measured 7.5s even fanned across all cores),
     * while the WebView's crypto.subtle runs the same derivation in native
     * BoringSSL exactly like the PWA. Every message carries its own random
     * salt (web wire format), so no cache can help — the derivation cost is
     * unavoidable and the only lever is where it runs.
     *
     * If the bridge call fails the page falls back to the in-process parallel
     * decrypt. Failed entries become null and fall through handleContent's
     * type check, exactly as they would have inline.
     */
    internal suspend fun predecrypt(arr: JSONArray, password: String?): List<Any?> {
        val n = arr.length()
        val out = arrayOfNulls<Any>(n)
        val sealedIdx = ArrayList<Int>()
        for (i in 0 until n) {
            val content = arr.optJSONObject(i)?.opt("content")
            if (password != null && content is String) sealedIdx.add(i) else out[i] = content
        }
        if (password == null || sealedIdx.isEmpty()) return out.toList()

        val items = JSONArray().also { arr0 ->
            sealedIdx.forEach { arr0.put(arr.optJSONObject(it)!!.opt("content") as String) }
        }
        val plain = try {
            bridge.call("pwDecryptBatch", JSONObject()
                .put("password", password).put("items", items), 60_000)
                .optJSONArray("plain")
        } catch (e: Exception) { null }

        if (plain != null) {
            for (j in sealedIdx.indices) {
                out[sealedIdx[j]] = if (plain.isNull(j)) null else
                    try { JSONObject(plain.getString(j)) } catch (e: Exception) { null }
            }
        } else {
            coroutineScope {
                sealedIdx.map { i ->
                    async(Dispatchers.Default) {
                        out[i] = try {
                            JSONObject(PomboCrypto.decryptString(
                                arr.optJSONObject(i)!!.opt("content") as String, password))
                        } catch (e: Exception) { null }
                    }
                }.forEach { it.await() }
            }
        }
        return out.toList()
    }

    /** A resend page already decrypted: entry `i` of [entries] is [contents]`[i]`. */
    private class HistoryPage(val entries: JSONArray, val contents: List<Any?>)

    /**
     * One partition's resend, decrypted but not yet applied.
     *
     * `budgetMs` sits under the hard call timeout: when key-request waits eat
     * the budget, the bridge returns the PARTIAL page it already has — one
     * offline member no longer renders the whole room empty.
     *
     * Returns null on any failure, so a channel with no storage (or a partition
     * that simply has nothing) just contributes nothing.
     */
    private suspend fun fetchHistoryPage(
        channel: Channel,
        partition: Int,
        last: Int,
        budgetMs: Int,
        timeoutMs: Long
    ): HistoryPage? = try {
        val t0 = System.currentTimeMillis()
        val args = JSONObject()
            .put("streamId", channel.messageStreamId)
            .put("partition", partition)
            .put("last", last)
            .put("budgetMs", budgetMs)
        // Sealed rows are opened INSIDE the drain, so each message's PBKDF2
        // overlaps the network wait for the ones still coming and the ciphertext
        // never makes the extra pwDecryptBatch round trip. [predecrypt] below
        // stays as the fallback for anything that came back still sealed.
        channel.password?.let { args.put("password", it) }
        // Gated history reads the raw envelopes: the SDK validator re-checks
        // every stored message against the PRESENT gate state, which erases
        // ex-members' history. Authorship comes from the recovered envelope
        // signer (gatedAuthor) and stale keys are cut by kid freshness.
        if (channel.type == "gated") args.put("recoverSigner", true).put("raw", true)
        val res = bridge.call("resend", args, timeoutMs)
        val tResend = System.currentTimeMillis()
        val arr = res.optJSONArray("messages")
        if (arr == null) null else {
            val contents = predecrypt(arr, channel.password)
            // Where does a slow room open actually spend its time — network
            // (storage-node resend) or crypto (password page decrypt)?
            // call = the whole bridge round trip; drain/tail come from inside it
            // (drain = network, tail = PBKDF2 with no network left to hide it).
            android.util.Log.d(
                "PomboPerf",
                "history ${channel.name} P$partition: call=${tResend - t0}ms " +
                    "drain=${res.optInt("drainMs")}ms tail=${res.optInt("decryptMs")}ms " +
                    "post=${System.currentTimeMillis() - tResend}ms n=${arr.length()}"
            )
            HistoryPage(arr, contents)
        }
    } catch (e: Exception) { null }

    /**
     * Initial history: content (P0) and overrides (P1).
     *
     * The two resends are FETCHED concurrently — the web fires both without
     * awaiting (streamr.js subscribeWithHistory calls fetchHistoryAsync for each
     * partition), so its gate closes on max(P0, P1) while this used to pay
     * P0 + P1 in series.
     *
     * They are APPLIED in the old order, content before overrides, deliberately:
     * an edit or delete needs its target present, and while [applyOverride]
     * parks orphans in `pendingOverrides` for the sweep at the end, keeping the
     * original order means the common case never has to rely on that.
     */
    private suspend fun loadHistory(channel: Channel, generation: Int) {
        val (content, overrides) = coroutineScope {
            val c = async {
                fetchHistoryPage(
                    channel, StreamConstants.P_MESSAGES,
                    StreamConstants.INITIAL_MESSAGES, 45_000, 60_000
                )
            }
            val o = async {
                fetchHistoryPage(channel, StreamConstants.P_CONTROL, 50, 20_000, 30_000)
            }
            c.await() to o.await()
        }
        // The resends can take tens of seconds on a slow network — long enough
        // for several channel switches. Without this the whole of A's history
        // was appended to whatever channel is now on screen.
        if (!stillCurrent(generation)) return

        if (content != null) {
            // One merge for the whole page instead of one per message.
            batchingMerges {
                for (i in 0 until content.entries.length()) {
                    val entry = content.entries.optJSONObject(i) ?: continue
                    val meta = entry.optJSONObject("meta") ?: JSONObject()
                    trackOldest(meta)
                    handleContent(
                        channel, content.contents[i], meta,
                        historical = true, generation = generation
                    )
                }
            }
            // Like the web, stay optimistic: only a range resend can prove exhaustion.
            _hasMoreHistory.value = true
        }

        if (overrides != null) {
            for (i in 0 until overrides.entries.length()) {
                val entry = overrides.entries.optJSONObject(i) ?: continue
                val meta = entry.optJSONObject("meta") ?: JSONObject()
                handleContent(
                    channel, overrides.contents[i], meta,
                    historical = true, generation = generation
                )
            }
        }
        if (!stillCurrent(generation)) return
        // Unconditional now. The old code returned early when P1 came back
        // without a `messages` array, which skipped this sweep and left any
        // override that had arrived before its target parked forever.
        applyPendingOverrides()
    }

    /** Moves the pagination cursor back. Chunks count too — they carry no id. */
    private fun trackOldest(meta: JSONObject) {
        val ts = meta.optLong("timestamp", 0L)
        if (ts > 0L && (oldestTimestamp == 0L || ts < oldestTimestamp)) oldestTimestamp = ts
    }

    /**
     * Loads the previous page of history (web: channels.js loadMoreHistory).
     * Fetches content (P0) and overrides (P1) older than the current cursor;
     * pagination state is driven by the content partition. Returns how many
     * new messages became visible.
     */
    suspend fun loadMoreHistory(): Int {
        val channel = _current.value ?: return 0
        if (channel.writeOnly || channel.type == "dm") return 0
        if (_loadingHistory.value || !_hasMoreHistory.value) return 0

        // Cursor of Date.now() when history held only reactions/chunks (web parity).
        val before = if (oldestTimestamp > 0L) oldestTimestamp else System.currentTimeMillis()
        val generationAtStart = switchGeneration
        _loadingHistory.value = true
        val countBefore = _messages.value.size
        try {
            var content = resendOlder(channel.messageStreamId, StreamConstants.P_MESSAGES, before, channel.password)
            // Storage nodes sometimes close a range iterator early and answer
            // empty for a range that has data. The web retries [1s,2s,3s]
            // before believing exhaustion (channels.js:3419-3437); concluding
            // on the first empty page orphaned everything older.
            var emptyRetry = 0
            while (emptyRetry < 3 &&
                content != null &&
                (content.optJSONArray("messages")?.length() ?: 0) == 0 &&
                !content.optBoolean("hasMore", false)
            ) {
                delay(1_000L * (emptyRetry + 1))
                if (!stillCurrent(generationAtStart)) return 0
                content = resendOlder(channel.messageStreamId, StreamConstants.P_MESSAGES, before, channel.password)
                emptyRetry++
            }
            val overrides = resendOlder(channel.messageStreamId, StreamConstants.P_CONTROL, before, channel.password)

            // Discard if the user switched channels while we were fetching.
            if (!stillCurrent(generationAtStart)) return 0

            // Content first, then overrides — an edit/delete needs its target
            // present, so each partition gets its own batch rather than one
            // batch around both: the overrides must see the merged content.
            for (partition in listOf(content, overrides)) {
                val arr = partition?.optJSONArray("messages") ?: continue
                val contents = predecrypt(arr, channel.password)
                batchingMerges {
                    for (i in 0 until arr.length()) {
                        val entry = arr.optJSONObject(i) ?: continue
                        val meta = entry.optJSONObject("meta") ?: JSONObject()
                        trackOldest(meta)
                        handleContent(
                            channel, contents[i], meta,
                            historical = true, generation = generationAtStart
                        )
                    }
                }
            }
            // Overrides consumed before their target was paginated in.
            applyPendingOverrides()
            // Keep the flag untouched when the fetch itself failed, so a
            // transient network error doesn't permanently kill pagination.
            if (content != null) _hasMoreHistory.value = content.optBoolean("hasMore", false)
            val added = (_messages.value.size - countBefore).coerceAtLeast(0)
            android.util.Log.d("PomboPerf",
                "loadMore ${channel.name}: +$added hasMore=${_hasMoreHistory.value}")
            return added
        } catch (e: Exception) {
            return 0
        } finally {
            if (switchGeneration == generationAtStart) _loadingHistory.value = false
        }
    }

    private suspend fun resendOlder(
        streamId: String,
        partition: Int,
        before: Long,
        /** Opens sealed rows in the bridge during the drain — see [fetchHistoryPage]. */
        password: String?
    ): JSONObject? = try {
        val args = JSONObject()
            .put("streamId", streamId)
            .put("partition", partition)
            .put("before", before)
            .put("last", StreamConstants.LOAD_MORE_COUNT)
            .put("budgetMs", 45_000)
        password?.let { args.put("password", it) }
        withSignerRecovery(args, streamId)
        bridge.call("resendRange", args, 60_000)
    } catch (e: Exception) { null }

    private suspend fun subscribeQuiet(streamId: String, partition: Int) {
        try {
            val args = JSONObject().put("streamId", streamId).put("partition", partition)
            // Gated (N-C): prove access via the gate contract (no per-member
            // grant), and have the bridge recover the envelope signer — the
            // author — for every message (gatedAuthor consumes it).
            channelByStream(streamId)?.takeIf { it.type == "gated" }?.gateAddress?.let {
                args.put("erc1271Contract", it).put("recoverSigner", true)
            }
            bridge.call("subscribe", args)
        } catch (e: Exception) { /* reported via the bridge status/error */ }
    }

    /**
     * Gated resends read the raw envelopes (no re-validation against the
     * present gate state) and recover the author from the envelope signature.
     */
    private fun withSignerRecovery(args: JSONObject, streamId: String): JSONObject {
        if (channelByStream(streamId)?.type == "gated") {
            args.put("recoverSigner", true).put("raw", true)
        }
        return args
    }

    private suspend fun unsubscribeQuiet(streamId: String, partition: Int) {
        try {
            bridge.call("unsubscribe", JSONObject().put("streamId", streamId).put("partition", partition))
        } catch (e: Exception) { }
    }

    suspend fun sendMessage(text: String, replyTo: ReplyRef? = null) {
        val channel = _current.value ?: return
        val sender = myAddress() ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        if (channel.type == "dm") { sendDm(channel, trimmed); return }

        val id = Protocol.generateMessageId()
        val timestamp = System.currentTimeMillis()
        mergeMessages(listOf(UiMessage(
            id, trimmed, sender, myUsername(), timestamp,
            mine = true, pending = true, verified = true, replyTo = replyTo,
            // Same self-ENS seeding as sendDm — own bubbles never resolved.
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender)
        )))

        // No app-layer signature (D6): the Streamr envelope authenticates the
        // publisher and the proof (added by the bridge on ephemeral publishes)
        // authenticates the account behind it. `sender`/`channelId` stay on
        // the OBJECT for local use — the bridge strips them at egress; on the
        // account paths (gated/readOnly) they are simply redundant.
        val content = JSONObject()
            .put("type", "text")
            .put("id", id)
            .put("text", trimmed)
            .put("sender", sender)
            .put("senderName", myUsername() ?: JSONObject.NULL)
            .put("timestamp", timestamp)
            .put("replyTo", replyTo?.let {
                JSONObject()
                    .put("id", it.id)
                    .put("sender", it.sender)
                    .put("senderName", it.senderName ?: JSONObject.NULL)
                    .put("text", it.text)
            } ?: JSONObject.NULL)

        // Web publishWithRetry (channels.js:3277): a transient publish failure
        // left the optimistic bubble stuck on `pending` forever.
        publishTextWithRetry {
            publishForChannel(channel, channel.messageStreamId, StreamConstants.P_MESSAGES, content)
        }
        confirmMessage(id)
        // Without this a message sent from Android never wakes anyone: web
        // clients rely on the relay seeing a wake signal on the push stream.
        sendWakeSignal(channel)
    }

    /** Web publishWithRetry: 3 attempts, 2s apart, then the error surfaces. */
    internal suspend fun publishTextWithRetry(block: suspend () -> Unit) {
        var last: Exception? = null
        repeat(3) { attempt ->
            try { block(); return } catch (e: Exception) {
                last = e
                if (attempt < 2) delay(2_000)
            }
        }
        throw last ?: IllegalStateException("publish failed")
    }

    suspend fun sendImage(input: ByteArray, originalMime: String) =
        images.sendImage(input, originalMime)

    /** Web dedup key `streamId:messageId:emoji:action` -> last send time. */
    private val recentReactions = HashMap<String, Long>()

    /** channels.js sendReaction format — no signature; authorship = publisherId. */
    suspend fun sendReaction(messageId: String, emoji: String, add: Boolean) {
        val channel = _current.value ?: return
        val me = myAddress()?.lowercase() ?: return
        // Rapid taps published one reaction per tap; the web deduplicates the
        // same (stream, message, emoji, action) for 500ms (channels.js:4053).
        val dedupKey = "${channel.messageStreamId}:$messageId:$emoji:${if (add) "add" else "remove"}"
        val now = System.currentTimeMillis()
        synchronized(recentReactions) {
            val last = recentReactions[dedupKey]
            if (last != null && now - last < 500) return
            recentReactions[dedupKey] = now
            if (recentReactions.size > 64) {
                recentReactions.entries.removeAll { now - it.value > 5_000 }
            }
        }
        val reaction = JSONObject()
            .put("type", "reaction")
            .put("action", if (add) "add" else "remove")
            .put("messageId", messageId)
            .put("emoji", emoji)
            .put("senderName", myUsername() ?: JSONObject.NULL)
            .put("timestamp", System.currentTimeMillis())
        // Local optimistic update
        applyReaction(messageId, emoji, me, add)
        // A DM reaction was going out in plaintext to the peer's inbox — that
        // stream accepts public publishes, so the emoji and the message it
        // points at were readable by anyone. Seal it like every other DM
        // payload (web sendReaction takes the same branch).
        publishForChannel(channel, channel.messageStreamId, StreamConstants.P_MESSAGES, reaction)
        // A DM reaction goes to the PEER's inbox and never comes back from any
        // resend; a write-only channel is never resubscribed at all. The local
        // record is the only copy (web addSentReaction), and it is a sync
        // slice — mark state dirty so other devices receive it.
        if (channel.type == "dm" || channel.writeOnly) {
            sentReactionsStore?.record(channel.messageStreamId, messageId, emoji, me, add)
            onLocalStateChanged()
        }
    }

    /**
     * Where an override goes, and how it is sealed, differs by channel kind.
     *
     * A channel puts overrides on the control partition (P1). A DM cannot: on a
     * DM inbox P1 is the cross-device SYNC partition, so an override published
     * there would land among sync payloads and never reach the peer. DM
     * overrides ride P0 with the messages, sealed in the ECDH envelope.
     */
    private suspend fun publishOverride(channel: Channel, override: JSONObject) {
        // Partition still differs: a DM inbox P1 is the SYNC partition, so DM
        // overrides ride P0 with the messages. Sealing is handled centrally.
        val partition =
            if (channel.type == "dm") StreamConstants.P_MESSAGES else StreamConstants.P_CONTROL
        publishForChannel(channel, channel.messageStreamId, partition, override)
    }

    suspend fun editMessage(targetId: String, newText: String) {
        val channel = _current.value ?: return
        val original = _messages.value.find { it.id == targetId } ?: return
        require(original.mine) { "You can only edit your own messages" }
        val text = newText.trim()
        val timestamp = System.currentTimeMillis()
        val override = JSONObject()
            .put("type", "edit")
            .put("targetId", targetId)
            .put("text", text)
            .put("timestamp", timestamp)

        // Publish BEFORE touching local state (web sendEdit): if the publish
        // fails, showing an edit the peer never received is worse than showing
        // nothing — the two sides would silently disagree about the text.
        publishOverride(channel, override)

        applyEdit(targetId, text)
        // A DM's sent half is only in local storage, so the edit has to land
        // there too or it reverts the next time the chat is opened.
        if (channel.type == "dm") {
            sentDmStore.edit(channel.messageStreamId, targetId, text)
            onLocalStateChanged()
        }
    }

    suspend fun deleteMessage(targetId: String) {
        val channel = _current.value ?: return
        val original = _messages.value.find { it.id == targetId } ?: return
        require(original.mine) { "You can only delete your own messages" }
        val override = JSONObject()
            .put("type", "delete")
            .put("targetId", targetId)
            .put("timestamp", System.currentTimeMillis())

        publishOverride(channel, override)

        deletedIds.add(targetId)
        if (original.isImage) original.imageId?.let { tombstoneImage(it) }
        _messages.value = _messages.value.filterNot { it.id == targetId }
        if (channel.type == "dm") {
            sentDmStore.remove(channel.messageStreamId, targetId)
            onLocalStateChanged()
        }
    }

    fun sendTyping() = presence.sendTyping()

    internal suspend fun publishContent(
        streamId: String,
        partition: Int,
        payload: JSONObject,
        password: String?,
        /**
         * Recipient address for a DM. The payload is sealed-sender v2, sealed
         * natively ([dmSealPublish]): ECDH against a per-message ephemeral
         * key, the identity proof INSIDE the ciphertext, and the publish goes
         * out under that same throwaway key — the wallet address never
         * touches the wire.
         */
        dmPeer: String? = null
    ): Long {
        if (dmPeer != null) {
            val pk = peerPubKey(dmPeer)
                ?: throw IllegalStateException("DM publish: peer public key unavailable for $dmPeer")
            val res = dmSealPublish(streamId, partition, dmPeer, pk, payload)
            return res.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        }
        val content: Any = when {
            password != null -> PomboCrypto.encryptString(payload.toString(), password)
            else -> payload
        }
        // Returns the network-assigned publish timestamp (the storage-file announce
        // confirm loop needs it); falls back to local time if the bridge had none.
        val res = bridge.call("publish", JSONObject()
            .put("streamId", streamId).put("partition", partition).put("content", content))
        return res.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
    }

    /**
     * The single place that decides how a channel's payload is sealed.
     *
     * Every publish that belongs to a channel goes through here, so the rule
     * lives in one spot instead of being restated (and forgotten) at each call
     * site — which is exactly how DM reactions, typing and presence ended up
     * going out in the clear.
     *
     *   dm       -> ECDH envelope for the peer (their inbox and ephemeral
     *               stream both accept public publishes)
     *   password -> AES with the channel password
     *   public   -> plain
     */
    internal suspend fun publishForChannel(
        channel: Channel,
        streamId: String,
        partition: Int,
        payload: JSONObject
    ) {
        if (channel.type == "dm") {
            val peer = channel.peerAddress ?: return
            publishContent(streamId, partition, payload, password = null, dmPeer = peer)
            return
        }
        publishChannel(channel, streamId, partition, payload, channel.password)
    }

    /**
     * Ephemeral-or-account decision for a non-DM channel publish (returns the
     * publish timestamp):
     *
     *   public/password -> the channel's ephemeral identity, proof in the
     *                      payload, local fields stripped — all in the bridge
     *                      (publishAsChannel), which also refuses -3.
     *   gated           -> the epoch layer (the account signs, the gate clone
     *                      is the on-wire publisher) — an ephemeral key holds
     *                      no grant and would be rejected network-wide.
     *   readOnly -1     -> the ACCOUNT (publish is the owner's by ownership).
     *
     * `channel` may be null (media transport paths look the channel up by
     * stream id; a previewed channel is not in the list) — null means not
     * gated, so the ephemeral path applies.
     */
    private suspend fun publishChannel(
        channel: Channel?, streamId: String, partition: Int, payload: JSONObject, password: String?
    ): Long {
        // The admin stream (-3) ALWAYS publishes under the account, on every
        // channel kind: ADMIN_STATE / CHANNEL_IMAGE / password challenge are
        // gated by the owner's on-chain permission, which a throwaway key does
        // not hold — the bridge's publishAsChannel refuses -3 outright. Missing
        // this was the "publishAs refuses the admin stream" crash on every
        // admin moderation (delete/hide/ban/pin) in a public/password channel.
        val isAdminStream = streamId == channel?.adminStreamId ||
            streamId.endsWith(StreamConstants.SUFFIX_ADMIN)
        // Admin stream keeps the legacy account path EXCEPT on gated, where
        // -3 rides the epoch layer too: the SDK's group-key AES made a
        // web-published ADMIN_STATE unreadable here (20s key-exchange timeouts
        // per row, moderation never landed). The owner still signs as the
        // account, so the on-chain owner-only publish permission holds.
        if (isAdminStream && !isEpochChannel(channel)) {
            return publishContent(streamId, partition, payload, password)
        }
        if (channel != null && channel.readOnly && streamId == channel.messageStreamId) {
            return publishContent(streamId, partition, payload, password)
        }
        // GATED channels (N-A/N-C): encrypt with the channel's epoch key and
        // go out with encryptionType NONE — the SDK's group-key layer is
        // exactly the per-publisher, publisher-must-be-online dependency the
        // epoch protocol replaces. Publishes AS THE GATE CLONE with the
        // account signing (ERC-1271) — EXCEPT the admin stream, which
        // publishes as the ACCOUNT: the owner is its only writer and the
        // transport enforces that (new gated -3s grant the clone
        // subscribe-only). Fail-closed: no epoch key means NO publish, never
        // a plaintext or SDK-keyed fallback.
        if (isEpochChannel(channel)) {
            channel!!
            val keysId = channel.keysStreamId.ifEmpty {
                StreamConstants.deriveKeysId(channel.messageStreamId)
            }
            var clean = stripLocalFields(payload)

            // Members-only author visibility: the plaintext becomes an
            // authorship wrapper (pseudonym signature per message + account
            // bind proof) and the on-wire publisher becomes the channel's
            // SHARED key — the wire says nothing about who wrote this.
            // Fail-closed on both halves: no publish key or no wallet means
            // NO publish, never a fallback to the clone (which would put the
            // account on the wire).
            val membersOnly = channel.authorMode == "members" && !isAdminStream
            var sharedKeyHex: String? = null
            if (membersOnly) {
                var pub = epochKeys.publishKeyFor(channel.messageStreamId)
                if (pub == null) {
                    // A member can hold the epoch key (reads decrypt fine)
                    // while the PUB_WRAP never arrived — the epoch-gated
                    // recovery below never runs in that state, so the missing
                    // publish key gets its own one-shot attempt. The wrap
                    // arrives asynchronously: this send may still fail, but
                    // the request is now in flight for the retry. A recovery
                    // failure must not replace the honest no-key error below.
                    try {
                        epochKeys.ensureChannelKeys(
                            channel.messageStreamId, keysId,
                            keysRetentionDays(channel),
                            allowMint = System.currentTimeMillis() - channel.createdAt < 3_600_000,
                            memberCount = channel.members.size,
                            gated = channel.type == "gated")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "publish-key recovery failed", e)
                    }
                    pub = epochKeys.publishKeyFor(channel.messageStreamId)
                }
                if (pub == null) throw IllegalStateException(
                    "No publish key for ${channel.messageStreamId} — cannot publish on a Members-only channel (waiting for PUB_WRAP)")
                val auth = epochKeys.authorshipFor(channel.messageStreamId)
                    ?: throw IllegalStateException("No wallet available to bind the channel pseudonym")
                clean = com.pombo.android.core.Authorship.seal(
                    channel.messageStreamId, clean, auth.privateKey, auth.publicKey, auth.bindProof)
                sharedKeyHex = pub.keyHex
            }

            var envelope = epochKeys.encryptCurrent(channel.messageStreamId, clean)
            if (envelope == null) {
                // Cold open may not have run the bootstrap/request yet — one recovery attempt
                epochKeys.ensureChannelKeys(
                    channel.messageStreamId, keysId,
                    keysRetentionDays(channel),
                                    allowMint = System.currentTimeMillis() - channel.createdAt < 3_600_000,
                                    memberCount = channel.members.size,
                                    gated = channel.type == "gated")
                envelope = epochKeys.encryptCurrent(channel.messageStreamId, clean)
            }
            if (envelope == null) {
                throw IllegalStateException(
                    "No epoch key for ${channel.messageStreamId} — cannot publish on a ${channel.type} channel without one (waiting for KEY_WRAP)")
            }
            val args = JSONObject()
                .put("streamId", streamId).put("partition", partition).put("content", envelope)
            val method = if (membersOnly) {
                args.put("privateKey", sharedKeyHex)
                "publishAs"
            } else if (channel.type == "gated" && !isAdminStream) {
                val gate = channel.gateAddress ?: throw IllegalStateException(
                    "Gate address unknown for ${channel.messageStreamId} (repair pending) — cannot publish")
                args.put("gateAddress", gate)
                "publishAsGate"
            } else "publishAsAccount"
            val res = bridge.call(method, args)
            return res.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
        }
        val entry = channelIdentity(streamId)
        val args = JSONObject()
            .put("streamId", streamId).put("partition", partition).put("content", payload)
            .put(
                "identity", JSONObject()
                    .put("identityPk", entry.identityPk)
                    .put("publisherId", entry.publisherId)
                    .put("proof", entry.proof)
            )
        password?.let { args.put("password", it) }
        val res = bridge.call("publishAsChannel", args)
        return res.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
    }

    /**
     * Fields that must never reach the wire inside an epoch envelope (web
     * publisherProof.js stripLocalFields): local UI state, and the identity
     * fields D6/D7 removed. `sender` is re-derived by every receiver from the
     * envelope signer at ingest.
     */
    private fun stripLocalFields(data: JSONObject): JSONObject {
        val out = JSONObject()
        for (k in data.keys()) {
            if (k == "verified" || k == "pending" || k == "_dmSent" ||
                k == "sender" || k == "account" || k == "signature" || k == "channelId") continue
            out.put(k, data.opt(k))
        }
        return out
    }

    /** Channel lookup by ANY of its streams, for transports that only know a stream id. */
    internal fun channelByStream(streamId: String): Channel? {
        val match: (Channel) -> Boolean = {
            it.messageStreamId == streamId || it.ephemeralStreamId == streamId ||
                it.adminStreamId == streamId ||
                // Records persisted before the keys stream existed carry an
                // empty keysStreamId — the rest of the code lives with that by
                // deriving it. Matching only the stored value made the -4
                // lookup miss, and a miss is not neutral: the resend then goes
                // without raw/recoverSigner and attributes the announce to the
                // clone, so the admin's own KEY_ANNOUNCE is rejected as
                // non-admin and its wraps are discarded.
                (it.type == "gated" &&
                    StreamConstants.deriveKeysId(it.messageStreamId) == streamId)
        }
        // Preview channels live only in _current, never in _channels — the
        // gated paths (epoch ingest, clone transport, gate checks) must still
        // find them or a gated preview silently degrades to ungated handling.
        return _channels.value.find(match) ?: _current.value?.takeIf(match)
    }

    /** Gated channels run the epoch-key machinery (N-A/N-C). */
    internal fun isEpochChannel(channel: Channel?): Boolean =
        channel?.type == "gated"

    /**
     * The authenticated AUTHOR of a message on a gated channel, or null = DROP.
     *
     * The on-wire publisher is the gate clone for every member, so authorship
     * is the envelope signer the bridge recovered (`meta.signer`) — the very
     * signature the SDK validated against the gate's isValidSignature. Drops:
     * a foreign transport publisher (permissions are clone-only), an
     * unrecoverable envelope, and — on the admin stream, where the clone
     * necessarily holds the publish grant for everyone — a signer that is not
     * the namespace admin (this check replaces the transport's owner-only
     * enforcement). D10c applies: never fall back to the transport publisher.
     */
    /**
     * Live-content verdict for a gated author: false ONLY when the chain
     * positively says no (bridge cache, 10 min per author). RPC failure
     * allows — hiding legitimate traffic on an outage is worse than showing
     * an expired member's messages for a while.
     */
    private suspend fun liveGateAccessAllows(channel: Channel, author: String): Boolean {
        val gate = channel.gateAddress ?: return true
        return try {
            val res = bridge.call("gateCheckAccess", JSONObject()
                .put("gate", gate).put("user", author))
            res.optBoolean("access", false) || res.optBoolean("failed", false)
        } catch (e: Exception) { true }
    }

    internal fun gatedAuthor(channel: Channel, streamId: String, meta: JSONObject): String? {
        val gate = channel.gateAddress ?: return null
        val publisher = meta.optString("publisherId").lowercase()
        if (streamId.endsWith(StreamConstants.SUFFIX_ADMIN)) {
            // -3 as the ACCOUNT: the owner publishes the admin stream under
            // their own address — the transport validated the plain EVM
            // signature, and the namespace prefix IS the authority. The
            // clone-published path below stays for pre-switch history.
            val admin = channel.messageStreamId.substringBefore('/').lowercase()
            if (publisher == admin) return publisher
        }
        if (publisher != gate) return null
        val signer = meta.optString("signer").lowercase().ifEmpty { null } ?: run {
            Log.w(TAG, "gated: unrecoverable envelope on $streamId — dropping")
            return null
        }
        if (streamId.endsWith(StreamConstants.SUFFIX_ADMIN)) {
            val admin = channel.messageStreamId.substringBefore('/').lowercase()
            if (signer != admin) {
                Log.w(TAG, "gated: non-admin $signer on admin stream — dropping")
                return null
            }
        }
        return signer
    }

    private fun startPresence(channel: Channel) = presence.startPresence(channel)

    /** Called by the bridge listener (background thread). */
    fun onIncoming(streamId: String, partition: Int, contentRaw: String, metaRaw: String) {
        val meta = try { JSONObject(metaRaw) } catch (e: Exception) { JSONObject() }
        // Incoming DM on my own inbox (encrypted envelope) — route to DM handling.
        if (streamId == myInboxId && partition == StreamConstants.P_MESSAGES) {
            handleIncomingDm(contentRaw, meta)
            return
        }
        // Channel invites arrive on my inbox P3, encrypted to me.
        if (streamId == myInboxId && partition == StreamConstants.P_NOTIFICATIONS) {
            val envelope = try { JSONTokener(contentRaw).nextValue() } catch (e: Exception) { return }
            val sender = meta.optString("publisherId").lowercase().ifEmpty { null }
            scope.launch { handleNotification(envelope, sender) }
            return
        }
        // A peer's DM typing/presence lands on MY DM ephemeral inbox, ECDH-sealed.
        if (streamId == myDmEphemeralId && partition == StreamConstants.EPH_CONTROL) {
            handleDmControl(contentRaw, meta)
            return
        }
        // Epoch-key protocol (-4, gated channels). The -4 subscription follows
        // the open channel, so route to it. Publisher/timestamp travel raw:
        // KEY_ANNOUNCE authority is validated against the recovered envelope
        // signer and conflicts order by transport timestamp (D13) — this must
        // NOT go through attachAccount.
        if (StreamConstants.isKeysStream(streamId)) {
            val channel = _current.value?.takeIf { it.keysStreamId == streamId } ?: return
            val content = (try { JSONTokener(contentRaw).nextValue() } catch (e: Exception) { null })
                as? JSONObject ?: return
            // Gated: the clone publishes for everyone — the protocol's
            // identity checks need the envelope signer, not the transport.
            val publisher = if (channel.type == "gated") {
                gatedAuthor(channel, streamId, meta) ?: return
            } else meta.optString("publisherId").ifEmpty { null }
            val ts = meta.optLong("timestamp", 0L).takeIf { it > 0 } ?: System.currentTimeMillis()
            scope.launch {
                epochKeys.handleKeysMessage(
                    channel.messageStreamId, streamId, content, publisher, ts,
                    memberCount = channel.members.size)
            }
            return
        }
        // Media coordination (-2/P1). Handled BEFORE the open-channel gate
        // below on purpose: a seeder has to answer a piece request for a file
        // shared in a channel the user navigated away from, and a download has
        // to keep running while they read somewhere else. Gating this on
        // `_current` would make every transfer die on the next channel switch.
        if (partition == StreamConstants.EPH_MEDIA_SIGNALS &&
            StreamConstants.isEphemeralStream(streamId)
        ) {
            val content = try { JSONTokener(contentRaw).nextValue() } catch (e: Exception) { return }
            val publisher = meta.optString("publisherId").lowercase().ifEmpty { null }
            // A signal on OUR OWN DM inbox is a sealed-sender v2 envelope
            // (web dm.js routeInboxMedia): open it with OUR static key — the
            // publisher is a throwaway and picks nothing — identify the peer
            // from the proof, THEN check blocks and hand the plaintext to the
            // media controller, which expects the true account as sender.
            if (content is JSONObject && content.optInt("v") == 2 && content.has("epk")) {
                scope.launch {
                    val opened = openSealedOne(content) ?: return@launch
                    val sender = opened.optString("sender").lowercase().ifEmpty { return@launch }
                    if (isBlockedPeer(sender)) return@launch
                    val plain = opened.optJSONObject("message") ?: return@launch
                    media.onSignal(streamId, plain, sender)
                }
                return
            }
            // v1 envelopes (pre-migration) no longer open — P6.
            if (content is JSONObject && content.optString("e") == "aes-256-gcm") return
            // Epoch channel signals arrive as epoch envelopes: open with the
            // channel's epoch key first — unknown kid skips, not errors; the
            // signal re-fires on the sender's next retry.
            if (content is JSONObject && com.pombo.android.core.EpochKeyCrypto.isEpochEnvelope(content)) {
                val ch = channelByStream(streamId)
                if (ch != null && isEpochChannel(ch)) {
                    val membersOnly = ch.type == "gated" && ch.authorMode == "members"
                    // Everyone-mode gated: the on-wire publisher is the CLONE —
                    // the seeder/leecher identity the media controller needs is
                    // the envelope signer, never the transport publisher.
                    // Members-only rides the SHARED key instead: the transport
                    // says nothing, authorship comes from the wrapper inside
                    // the seal and resolves after decryption.
                    val envelopeAuthor = if (ch.type == "gated" && !membersOnly) {
                        gatedAuthor(ch, streamId, meta) ?: return
                    } else publisher
                    scope.launch {
                        val keysId = ch.keysStreamId.ifEmpty {
                            StreamConstants.deriveKeysId(ch.messageStreamId)
                        }
                        var plain = epochKeys.tryDecrypt(
                            ch.messageStreamId, keysId, content,
                            gated = ch.type == "gated", live = true,
                            timestamp = meta.optLong("timestamp", 0L)
                        ) ?: return@launch
                        var author = envelopeAuthor
                        if (membersOnly) {
                            val opened = com.pombo.android.core.Authorship.open(
                                ch.messageStreamId, plain) ?: return@launch
                            if (!liveGateAccessAllows(ch, opened.author)) return@launch
                            author = opened.author
                            plain = opened.payload
                        }
                        media.onSignal(streamId, plain, author)
                    }
                }
                return
            }
            // Channel signals now arrive under an ephemeral publisher: the
            // seeder/leecher identity the media controller needs is the
            // account the proof recovers to (legacy signals fall back to the
            // publisher, which was the wallet).
            if (content is JSONObject) {
                scope.launch {
                    val acct = attachAccount(content, publisher) ?: publisher
                    media.onSignal(streamId, content, acct)
                }
                return
            }
            media.onSignal(streamId, content, publisher)
            return
        }
        val channel = _current.value ?: return
        // Snapshot the session alongside the channel. This runs on the WebView
        // binder thread and the handlers below are dispatched to main, so by the
        // time they run the user may have switched; the captured generation is
        // what lets them notice. Reading the two fields is not atomic, but the
        // only way that can go wrong is dropping a message we could have kept —
        // it fails closed, which is the right direction.
        val generation = switchGeneration
        val content = try { JSONTokener(contentRaw).nextValue() } catch (e: Exception) { return }
        val isMsgStream = streamId == channel.messageStreamId
        val isEphStream = streamId == channel.ephemeralStreamId
        if (!isMsgStream && !isEphStream) return
        scope.launch {
            handleContent(
                channel, content, meta, historical = false,
                generation = generation, streamId = streamId, partition = partition
            )
        }
    }

    /**
     * Binary media off -2/P2. Same reasoning as the signal path in
     * [onIncoming]: never gated on the open channel, and left on the calling
     * (WebView binder) thread so piece hashing and disk writes stay off main.
     */
    fun onIncomingBinary(streamId: String, partition: Int, data: ByteArray, metaRaw: String) {
        if (partition != StreamConstants.EPH_MEDIA_DATA) return
        if (!StreamConstants.isEphemeralStream(streamId)) return
        val meta = try { JSONObject(metaRaw) } catch (e: Exception) { JSONObject() }
        // Gated channel piece (0x04): open with the epoch key, then hand the
        // plaintext 0x01 frame down the unchanged store/assembly path. The
        // sender is resolved the way the channel's messages resolve theirs —
        // the envelope signer — an unknown kid notes itself (rate-limited
        // KEY_REQUEST) and the piece is dropped; the leecher's timeout
        // re-requests it once the key lands.
        if (com.pombo.android.core.EpochKeyCrypto.isBinaryEpochEnvelope(data)) {
            val channel = channelByStream(streamId)?.takeIf { isEpochChannel(it) } ?: return
            scope.launch {
                val gated = channel.type == "gated"
                // Members-only: pieces publish under the SHARED key, so the
                // transport names nobody. Their trust anchor is the content
                // hash from an AUTHORED announce — the piece carries the
                // shared address as its identity, and the assembly path
                // validates bytes by hash exactly as before.
                val author = if (channel.authorMode == "members") {
                    meta.optString("publisherId").lowercase().ifEmpty { null }
                } else if (gated) {
                    gatedAuthor(channel, streamId, meta) ?: return@launch
                } else meta.optString("publisherId").lowercase().ifEmpty { null }
                val opened = epochKeys.tryOpenBinary(
                    channel.messageStreamId, channel.keysStreamId, data,
                    gated = gated, live = true,
                    timestamp = meta.optLong("timestamp", 0L)
                ) ?: return@launch
                media.onBinary(streamId, opened, author)
            }
            return
        }
        // Sealed DM piece (0x02) on my own inbox, opened natively — the bridge
        // delivers the wire bytes untouched. What does not open is not ours
        // and is dropped, which also covers the 1-in-256 legacy v1 payload
        // whose random IV byte happens to be 0x02.
        val me = myAddress()?.lowercase()
        if (me != null && data.isNotEmpty() && data[0].toInt() == 0x02 &&
            streamId.lowercase() == "$me/pombo-dm-2"
        ) {
            val myPk = myPrivateKey() ?: return
            scope.launch {
                val opened = withContext(Dispatchers.Default) {
                    com.pombo.android.core.SealedSenderCrypto.openBinary(data, myPk, me)
                } ?: return@launch
                media.onBinary(streamId, opened.second, opened.first)
            }
            return
        }
        val identity = meta.optString("account").lowercase().ifEmpty { null }
            ?: meta.optString("publisherId").lowercase().ifEmpty { null }
        // Channel piece with inline proof (0x03, web FILE_PIECE_SIGNED):
        //   [0x03][proof:65][fileId:36][idx:4][data]
        // The publisher is an ephemeral key; the seeder's identity is the
        // account the proof recovers to. After resolving it, the frame is
        // rewritten as legacy 0x01 so the store/assembly path stays byte-
        // identical. Safe to discriminate on the byte here: both are OUR
        // plaintext frame formats (the 1-in-256 random-byte hazard is the DM
        // v1 ciphertext, which never reaches this path).
        if (data.size >= 1 + 65 + 36 + 4 && data[0].toInt() == 0x03) {
            scope.launch {
                val proofHex = "0x" + data.copyOfRange(1, 66)
                    .joinToString("") { "%02x".format(it) }
                val pub = meta.optString("publisherId").lowercase().ifEmpty { null }
                val account = pub?.let { recoverProofAccount(it, proofHex) } ?: pub
                val legacy = ByteArray(data.size - 65)
                legacy[0] = 0x01
                System.arraycopy(data, 66, legacy, 1, data.size - 66)
                media.onBinary(streamId, legacy, account)
            }
            return
        }
        media.onBinary(streamId, data, identity)
    }

    // ---- Ingest identity (web streamr.js attachAccount + publisherProof.js) ----

    /**
     * (publisherId|proof) -> recovered account, or null when the proof does
     * not recover. Nulls are cached too — a malformed proof must not cost a
     * bridge hop per message. Crude bound, not an LRU: a channel session sees
     * far fewer distinct publishers than this, and an entry costs one bridge
     * round-trip to rebuild. Never invalidated on account switch on purpose:
     * an entry is a pure function of public wire data (ecrecover of a public
     * proof), identical for every viewer, so it cannot go stale.
     */
    private val proofAccounts = HashMap<String, String?>()

    /**
     * THE single place where "who sent this" is decided (web attachAccount):
     *
     *     account = proof ? ecrecover(proof, digest(publisherId)) : publisherId
     *
     * The fallback keeps pre-migration history working — old messages carry no
     * proof, and back then publisherId WAS the wallet, so both formats land in
     * the same identifier space. It is also the failure mode: a proof that
     * does not recover falls back to the ephemeral address, an identity that
     * owns nothing and matches nobody (D10b).
     *
     * `sender` is overwritten as a DERIVED alias of the account — the new wire
     * format does not send it, dozens of call sites still read it, and it must
     * never again come from the wire: a payload beside a proof could otherwise
     * name someone it cannot prove. For legacy messages the overwrite is a
     * no-op (publisherId was the wallet, which is what `sender` already says).
     *
     * DM paths do NOT come through here: there the proof lives inside the
     * sealed envelope and identity is decided after opening it (port step 4).
     */
    private suspend fun attachAccount(data: JSONObject, publisherId: String?): String? {
        var account = publisherId?.lowercase()?.ifEmpty { null }
        val proof = data.optString("proof").ifEmpty { null }
        if (proof != null && account != null) {
            recoverProofAccount(account, proof)?.let { account = it }
        }
        if (account != null) {
            data.put("account", account)
            data.put("sender", account)
        }
        return account
    }

    /**
     * ecrecover of a publisher proof, through the shared (publisherId|proof)
     * cache. Null when the proof does not recover — the caller falls back to
     * the publisherId, D10b's failure mode. A bridge failure is NOT cached
     * (unavailable ≠ invalid), so the next message retries instead of pinning
     * the ephemeral id.
     */
    private suspend fun recoverProofAccount(publisherId: String, proof: String): String? {
        val key = "$publisherId|$proof"
        val cached = synchronized(proofAccounts) {
            if (proofAccounts.containsKey(key)) proofAccounts[key] to true else null to false
        }
        if (cached.second) return cached.first
        val rec = try {
            val res = bridge.call(
                "recoverPublisherProofBatch",
                JSONObject().put("items", JSONArray().put(
                    JSONObject().put("publisherId", publisherId).put("proof", proof)
                ))
            )
            val arr = res.optJSONArray("accounts")
            if (arr == null || arr.isNull(0)) null else arr.optString(0).ifEmpty { null }
        } catch (e: Exception) { return null }
        synchronized(proofAccounts) {
            if (proofAccounts.size >= 500) proofAccounts.clear()
            proofAccounts[key] = rec
        }
        return rec
    }

    private suspend fun handleContent(
        channel: Channel,
        contentAny: Any?,
        meta: JSONObject,
        historical: Boolean,
        generation: Int,
        streamId: String = channel.messageStreamId,
        partition: Int = StreamConstants.P_MESSAGES
    ) {
        // Password channels: the wire is a base64 string — decrypt first
        var data: JSONObject = when (contentAny) {
            is JSONObject -> contentAny
            is String -> {
                val pwd = channel.password ?: return
                try { JSONObject(PomboCrypto.decryptString(contentAny, pwd)) } catch (e: Exception) { return }
            }
            else -> return
        }
        // Gated channels: epoch-encrypted envelope (N-A). Unknown kid is NOT
        // an error (§7.9) — skip; storage-backed messages come back via the
        // refresh fired when the key is adopted.
        var innerAuthor: String? = null
        if (com.pombo.android.core.EpochKeyCrypto.isEpochEnvelope(data)) {
            if (!isEpochChannel(channel)) return
            val keysId = channel.keysStreamId.ifEmpty {
                StreamConstants.deriveKeysId(channel.messageStreamId)
            }
            data = epochKeys.tryDecrypt(
                channel.messageStreamId, keysId, data,
                // Kid freshness (N-C, gated only): live = current epoch (short
                // tolerance), history = the kid in force at the timestamp
                gated = channel.type == "gated",
                live = !historical,
                timestamp = meta.optLong("timestamp", 0L)) ?: return

            // Members-only: the seal held an authorship wrapper — the author
            // comes from it, never from the transport (the shared key says
            // nothing). A sealed message without a valid wrapper has no
            // author and drops; lapsed members cut live, exactly like the
            // Everyone mode cuts them on the envelope signer.
            if (channel.authorMode == "members") {
                val opened = com.pombo.android.core.Authorship.open(channel.messageStreamId, data)
                    ?: return
                if (!historical && !liveGateAccessAllows(channel, opened.author)) return
                innerAuthor = opened.author
                data = opened.payload
            }
        }
        // Single gate for every write below — messages, images, reactions and
        // overrides all funnel through here, from both the resend and the live
        // subscription. Nothing reaches the open channel's state past this line
        // unless it belongs to the open channel.
        //
        // Two INDEPENDENT conditions, either of which blocks (this is the web's
        // defence in depth — its app.js dispatcher gates every event on
        // `data.streamId === currentStreamId`, on top of the generation fence):
        //   1. the generation fence — catches stale async work, including the
        //      same channel reopened (streamId would still match, so this is the
        //      only check that covers that case);
        //   2. an identity check against the channel actually on screen right
        //      now — a cheap, counter-independent net, so a bug in the
        //      generation bookkeeping still cannot paint into the wrong channel.
        val forOpenChannel = channel.messageStreamId == _current.value?.messageStreamId
        if (!stillCurrent(generation) || !forOpenChannel) {
            // A live message that landed in the switching window is still news
            // for the channel it was addressed to, so badge it rather than drop
            // it. History replays are not news and must stay silent.
            if (!historical && data.optString("type") == "text") {
                unreadStore.increment(channel.messageStreamId)
                // Move the watermark past it as well, or the next activity scan
                // finds the same message still "newer than last seen" and adds
                // a second point for it.
                val ts = meta.optLong("timestamp").takeIf { it > 0 }
                    ?: data.optLong("timestamp")
                if (ts > 0) unreadStore.setWatermark(channel.messageStreamId, ts)
            }
            return
        }
        // account, not publisherId: with ephemeral publishers the transport
        // identity is a throwaway key, and everything below that names a user
        // — reaction authorship, override authority, presence, typing — must
        // read the account the proof recovers to. For legacy traffic the two
        // are the same address, so nothing changes for old history (D10b).
        // Gated: the transport is the clone for everyone — the author is the
        // envelope signer, resolved by gatedAuthor (drop on failure; D10c).
        // Members-only channels resolved theirs from the wrapper above.
        val author = if (innerAuthor != null) {
            innerAuthor
        } else if (channel.type == "gated") {
            val signer = gatedAuthor(channel, streamId, meta) ?: return
            // Live cut for lapsed access (N-F): sticky membership keeps an
            // expired subscriber's messages transport-valid until the next
            // rotation (§7.11) — honest clients drop them at ingest instead.
            // Fail-OPEN: an unreachable chain renders the message; the
            // fail-closed side stays in the key layer. Resends are exempt —
            // without storedAt (Q11) a resent message's write-time cannot
            // be judged.
            if (!historical && !liveGateAccessAllows(channel, signer)) return
            signer
        } else meta.optString("publisherId")
        val account = attachAccount(data, author)

        when (data.optString("type")) {
            "text" -> handleText(channel, data, historical)
            "file_announce" -> handleFileAnnounce(channel, data)
            "storage_file_announce" -> handleStorageFileAnnounce(channel, data)
            "image" -> handleImageManifest(channel, data)
            "image_chunk" -> handleImageChunk(data)
            "reaction" -> {
                val user = account ?: return
                applyReaction(data.optString("messageId"), data.optString("emoji"), user, data.optString("action") == "add")
            }
            "edit", "delete" -> applyOverride(data, account)
            "presence" -> {
                val user = account ?: return
                synchronized(online) {
                    online[user] = data.optLong("lastActive", System.currentTimeMillis())
                    _onlineCount.value = online.size
                    // The web lists who is online, not just how many, so the
                    // nickname that rides along with the heartbeat is kept.
                    onlineNames[user] = data.optStringOrNull("nickname")
                    _onlineUsers.value = online.keys.map {
                        OnlineUser(address = it, nickname = onlineNames[it])
                    }.sortedBy { it.address }
                }
            }
            "typing" -> {
                val me = myAddress()?.lowercase()
                if (account != null && account != me) {
                    markTyping(account, data.optStringOrNull("nickname"))
                }
            }
            // Owner's low-latency moderation signal (web channels.js
            // handleControlMessage): the full ADMIN_STATE snapshot rides the
            // ephemeral stream so members converge without waiting for the
            // next 30s poller tick — with no live -3 subscription, this IS
            // the real-time path. applyAdminMessage re-runs the web's own
            // checks — type, publisher == owner, rev — so the signal cannot
            // inject state a direct -3 publish couldn't.
            "admin_invalidate" -> {
                val snapshot = data.optJSONObject("snapshot") ?: return
                // The authority check inside applyAdminMessage reads the
                // snapshot object, but the proof (and thus the resolved
                // account) lives on the OUTER admin_invalidate payload —
                // carry it over, or an owner publishing under an ephemeral
                // key (step 5) fails the owner check in silence.
                account?.let { snapshot.put("account", it) }
                applyAdminMessage(channel, snapshot, meta, generation)
            }
        }
    }

    private fun handleFileAnnounce(channel: Channel, data: JSONObject) =
        files.handleFileAnnounce(channel, data)

    private fun handleStorageFileAnnounce(channel: Channel, data: JSONObject) =
        files.handleStorageFileAnnounce(channel, data)

    /** File/channel of a storage transfer + (uploads) the optimistic bubble to restore. */
    data class StorageTransferInfo(
        val fileName: String,
        val channelName: String,
        val messageStreamId: String,
        /** Upload-only: re-inserted if the user returns to the channel mid-upload. */
        val uploadBubble: UiMessage? = null,
        /**
         * Download-only: the announce + its timestamp, cached so
         * [resumeStorageTransfer] can restart the transfer without the
         * channel being open — the Active Transfers list it is called from
         * lives outside any open channel, so [_messages] (scoped to whichever
         * channel is on screen) is not a safe place to look this up from.
         */
        val meta: com.pombo.android.core.StorageMedia.StorageFileMetadata? = null,
        val timestamp: Long = 0L
    )

    private val _storageTransferPhase get() = files._storageTransferPhase
    val storageTransferPhase: StateFlow<Map<String, String>> = _storageTransferPhase.asStateFlow()

    fun storageTransferInfo(transferId: String): StorageTransferInfo? =
        files.storageTransferInfo(transferId)

    fun activeStorageTransferStreams(): Set<String> = files.activeStorageTransferStreams()

    private fun restoreStorageUploadBubbles(channel: Channel) =
        files.restoreStorageUploadBubbles(channel)

    suspend fun sendStorageFile(source: com.pombo.android.core.StorageMedia.Source) = files.sendStorageFile(source)

    fun downloadStorageFile(messageId: String) = files.downloadStorageFile(messageId)

    fun pauseStorageTransfer(transferId: String) = files.pauseStorageTransfer(transferId)

    fun cancelStorageTransfer(transferId: String) = files.cancelStorageTransfer(transferId)

    fun resumeStorageTransfer(transferId: String) = files.resumeStorageTransfer(transferId)

    fun storageCompletedFile(transferId: String): java.io.File? =
        files.storageCompletedFile(transferId)

    fun cancelTransfer(fileId: String) = files.cancelTransfer(fileId)

    fun pauseTransfer(fileId: String) = files.pauseTransfer(fileId)

    fun resumeTransfer(fileId: String) = files.resumeTransfer(fileId)

    fun stopSeeding(fileId: String) = files.stopSeeding(fileId)

    fun deleteSeed(fileId: String) = files.deleteSeed(fileId)

    fun reseedFile(fileId: String, messageStreamId: String) =
        files.reseedFile(fileId, messageStreamId)

    fun inactiveSeeds() = files.inactiveSeeds()

    fun downloadFile(messageId: String) = files.downloadFile(messageId)

    /**
     * Fills an image from the local ledger. Chunks live only as long as the
     * stream's retention, so history older than that would otherwise render as
     * a permanent placeholder even though we hold the bytes.
     */
    fun hydrateImages(imageIds: List<String>) = images.hydrateImages(imageIds)

    private fun hydrateFromLedger(imageId: String) = images.hydrateFromLedger(imageId)

    private fun handleImageManifest(channel: Channel, data: JSONObject, showBubble: Boolean = true) =
        images.handleImageManifest(channel, data, showBubble)

    private fun handleImageChunk(data: JSONObject) = images.handleImageChunk(data)

    private fun tombstoneImage(imageId: String) = images.tombstoneImage(imageId)

    private fun handleText(channel: Channel, data: JSONObject, historical: Boolean) {
        val id = data.optString("id").ifEmpty { return }
        val sender = data.optString("sender").ifEmpty { return }
        val me = myAddress()
        val mine = sender.equals(me, ignoreCase = true)
        if (mine && _messages.value.any { it.id == id }) {
            confirmMessage(id)
            return
        }
        val msg = UiMessage(
            id = id,
            text = data.optString("text"),
            sender = sender,
            senderName = data.optStringOrNull("senderName"),
            timestamp = data.optLong("timestamp", 0L),
            mine = mine,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender),
            replyTo = data.optJSONObject("replyTo")?.let {
                val rid = it.optString("id")
                if (rid.isEmpty()) null else ReplyRef(
                    id = rid,
                    sender = it.optString("sender"),
                    senderName = it.optStringOrNull("senderName"),
                    text = it.optString("text")
                )
            }
        )
        mergeMessages(listOf(msg))
        if (!mine) resolveEnsFor(sender)
        verifyAsync(channel, data, historical)
        // Only live messages from other people are worth a notification —
        // replaying history on open must not fire a burst of them.
        if (!mine && !historical) {
            onIncomingMessage(channel, sender, msg.text)
            // No unread badge here: reaching this point means the channel IS
            // open, and the web treats the open channel as read on every render.
            // Messages that arrive for a channel the user just left are badged
            // by the fence in [handleContent] instead.
        }
    }

    private fun verifyAsync(channel: Channel, data: JSONObject, historical: Boolean) =
        verification.verifyAsync(channel, data, historical)

    internal fun verifyImageManifestAsync(data: JSONObject) =
        verification.verifyImageManifestAsync(data)

    internal fun verifyFileAnnounceAsync(data: JSONObject) =
        verification.verifyFileAnnounceAsync(data)

    internal fun verifyStorageAnnounceAsync(data: JSONObject) =
        verification.verifyStorageAnnounceAsync(data)


    private fun addChannel(channel: Channel) {
        _channels.value = _channels.value + channel
        store.save(_channels.value)
    }

    /**
     * Applies an edit/delete override (web: handleOverrideMessage).
     * An override whose target has not been loaded yet is parked in
     * [pendingOverrides] — overrides live on P1 and are always newer than
     * their target, so scroll-up pagination surfaces the target long after
     * the override was already consumed. Without parking them, paginating
     * would resurrect messages the author had deleted.
     */
    @Synchronized
    private fun applyOverride(data: JSONObject, senderId: String?) {
        val targetId = data.optString("targetId")
        if (targetId.isEmpty()) return
        val original = _messages.value.find { it.id == targetId }
        if (original == null) {
            pendingOverrides[targetId] = data to senderId
            return
        }
        // Authority is the publisher: only the original author may edit or delete.
        if (senderId == null || original.sender.lowercase() != senderId.lowercase()) {
            Log.w(TAG, "Override REJECTED (${data.optString("type")} on ${targetId.take(8)}): " +
                "original.sender=${original.sender} override.sender=$senderId")
            return
        }
        // The DM inbox cache holds the message as it first arrived. Without
        // rewriting it here, reopening a DM merges the PRE-EDIT copy back in
        // and the old text (or a deleted message) flashes on screen until live
        // traffic happens to re-deliver the override. Keep the cache in step
        // with what the timeline shows.
        applyOverrideToInboxCache(senderId, targetId, data)
        if (data.optString("type") == "delete") {
            // Tombstone, not just a removal. Deleting by filtering alone leaves
            // no record, so any later merge carrying the same id — realtime
            // replay, a second resend, an overlapping pagination window —
            // resurrects the message. Whether that happens comes down to
            // arrival timing, which is why it showed on a phone and not on the
            // emulator. mergeMessages screens every id against this set.
            deletedIds.add(targetId)
            // Images need their own tombstone: the chunks/manifest carry the
            // imageId, not the message id, and would rebuild the bubble.
            if (original.isImage) original.imageId?.let { tombstoneImage(it) }
            _messages.value = _messages.value.filterNot { it.id == targetId }
        } else {
            applyEdit(targetId, data.optString("text"))
        }
    }

    /**
     * Mirror an edit/delete into the in-memory DM inbox cache.
     *
     * [dmReceived] is what [loadDmTimeline] merges on every open, and it
     * deduplicates by id — so a cached pre-edit copy would win forever.
     */
    private fun applyOverrideToInboxCache(senderId: String, targetId: String, data: JSONObject) {
        val list = synchronized(dmReceived) { dmReceived[senderId] } ?: return
        synchronized(list) {
            val i = list.indexOfFirst { it.id == targetId }
            if (i < 0) return
            if (data.optString("type") == "delete") {
                list.removeAt(i)
            } else {
                list[i] = list[i].copy(text = data.optString("text"), edited = true)
            }
        }
    }

    /** Re-runs parked overrides once their targets have been paginated in. */
    private fun applyPendingOverrides() {
        if (pendingOverrides.isEmpty()) return
        val loaded = _messages.value.map { it.id }.toSet()
        pendingOverrides.keys.filter { it in loaded }.forEach { targetId ->
            val (data, senderId) = pendingOverrides.remove(targetId) ?: return@forEach
            applyOverride(data, senderId)
        }
    }

    /**
     * Set while a batch of merges is in flight. Read and written only under the
     * same monitor as [mergeMessages].
     */
    private var mergeBuffer: MutableList<UiMessage>? = null

    /**
     * Collects every [mergeMessages] call made inside [block] into a single
     * merge at the end.
     *
     * Loading history merged one message at a time, and each merge rebuilt a
     * map of the whole conversation, re-sorted it and emitted a new list — so
     * an N-message resend cost N sorts and N recompositions on the main thread,
     * which is most of what made opening a busy channel feel heavy.
     */
    // Inline so history loaders can call the now-suspending handleContent
    // inside the block (a non-inline lambda cannot suspend its caller).
    private inline fun batchingMerges(block: () -> Unit) {
        val alreadyBatching = synchronized(this) {
            if (mergeBuffer != null) true else { mergeBuffer = mutableListOf(); false }
        }
        // Nested call: the outermost batch owns the flush.
        if (alreadyBatching) { block(); return }
        try {
            block()
        } finally {
            val buffered = synchronized(this) { mergeBuffer.also { mergeBuffer = null } }
            buffered?.let { mergeMessages(it) }
        }
    }

    @Synchronized
    internal fun mergeMessages(incoming: List<UiMessage>) {
        if (incoming.isEmpty()) return
        // Inside a batch, park these and let the batch flush them in one pass.
        mergeBuffer?.let { it.addAll(incoming); return }
        // A message the author deleted must never come back, no matter which
        // path re-delivers it.
        val fresh = incoming.filterNot { it.id in deletedIds }
        if (fresh.isEmpty()) return
        val byId = LinkedHashMap<String, UiMessage>()
        _messages.value.forEach { byId[it.id] = it }
        fresh.forEach { msg ->
            val existing = byId[msg.id]
            byId[msg.id] = existing?.copy(pending = false) ?: msg
        }
        _messages.value = byId.values.sortedBy { it.timestamp }
        // A parked override waits for its target; the target may well arrive
        // here, live, long after history finished. Without this the override
        // sits unapplied and the deleted message stays on screen.
        applyPendingOverrides()
    }

    @Synchronized
    internal fun confirmMessage(id: String) {
        _messages.value = _messages.value.map { if (it.id == id) it.copy(pending = false) else it }
    }

    /**
     * Apply [transform] to the message with [id] wherever it currently lives —
     * the batch buffer while a history load is in flight, AND/OR the live list.
     *
     * A synchronous verdict (markVerified / applyTrustLevel on the unsigned
     * path) runs right after [mergeMessages] parked the message in the buffer,
     * so touching only [_messages] would miss every batched (historical)
     * message: it would flush with `verified = null` and render no badge — the
     * "badge shows on live messages but not on history" bug. Patch both, so
     * the update survives the flush no matter which list holds it.
     */
    @Synchronized
    internal fun patchMessage(id: String, transform: (UiMessage) -> UiMessage) {
        mergeBuffer?.let { buf ->
            for (i in buf.indices) if (buf[i].id == id) buf[i] = transform(buf[i])
        }
        if (_messages.value.any { it.id == id }) {
            _messages.value = _messages.value.map { if (it.id == id) transform(it) else it }
        }
    }


    @Synchronized
    private fun applyEdit(id: String, newText: String) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(text = newText, edited = true) else it
        }
    }

    @Synchronized
    private fun applyReaction(messageId: String, emoji: String, user: String, add: Boolean) {
        if (messageId.isEmpty() || emoji.isEmpty()) return
        val map = _reactions.value.toMutableMap()
        val perMsg = (map[messageId] ?: emptyMap()).toMutableMap()
        val users = (perMsg[emoji] ?: emptySet()).toMutableSet()
        if (add) users.add(user) else users.remove(user)
        if (users.isEmpty()) perMsg.remove(emoji) else perMsg[emoji] = users
        if (perMsg.isEmpty()) map.remove(messageId) else map[messageId] = perMsg
        _reactions.value = map
    }

    /**
     * A gated channel refused entry at the CONTRACT (checkAccess false).
     * Carries the clone address so the entry screen can read the requirement
     * (mode/token/price) and offer pay() instead of a dead-end toast.
     */
    class GateAccessDenied(val gateAddress: String) :
        IllegalStateException("You do not have access to this gated channel.")

    companion object {
        private const val TAG = "PomboChannels"
        /** Canonical wrapped-native on Polygon (WPOL) — bridge `_WRAPPED_NATIVE`. */
        const val WRAPPED_NATIVE = "0x0d500b1d8e8ef31e21c99d1db9a6444d3adf1270"
        /** PomboGate.Mode — ABI order, never reorder (NONE=0, TOKEN=1, NFT=2, PAID=3). */
        const val GATE_MODE_NONE = 0
        const val GATE_MODE_TOKEN = 1
        const val GATE_MODE_NFT = 2
        const val GATE_MODE_PAID = 3
        const val STORAGE_NODE = "0xae340e799e8151f6a4999d245e466197aa217667"
        /** Web CONFIG.push.pushStreamId — the shared relay wake-signal stream. */
        const val PUSH_STREAM_ID = "0xae340e799e8151f6a4999d245e466197aa217667/push"
        const val ONLINE_TIMEOUT_MS = 25_000L
        /** How long a "typing" signal keeps someone in the indicator. */
        const val TYPING_TTL_MS = 4_000L
        const val PRESENCE_INTERVAL_MS = 5_000L
        /** Web config.js previewPresenceIntervalMs. */
        const val PREVIEW_PRESENCE_INTERVAL_MS = 20_000L
        /** Web config.js subscriptions.adminPollIntervalMs. */
        const val ADMIN_POLL_INTERVAL_MS = 30_000L
        const val TIMESTAMP_TOLERANCE_MS = 5 * 60_000L
        /** Web isRecentMessage: only messages under 30s old get the replay check. */
        const val RECENT_MESSAGE_MS = 30_000L

        // Receive-side ceilings, mirroring the web config.media values. Without
        // these a hostile publisher could pin arbitrary memory in the app.
        const val IMAGE_MAX_ASSEMBLED_BYTES = 1 * 1024 * 1024
        const val GIF_MAX_ASSEMBLED_BYTES = 5 * 1024 * 1024
        const val MAX_CHUNK_BYTES = 220 * 1024        // config.media.imagePayloadMaxBytes
        const val MAX_CHUNKS = 512
        const val ASSEMBLY_TTL_MS = 10 * 60 * 1000L   // config.media.imageAssemblyTtlMs
        // Targeted chunk recovery (web config.imageRecovery: 15min window).
        const val CHUNK_RECOVERY_DELAY_MS = 8_000L
        const val CHUNK_RECOVERY_WINDOW_MS = 15 * 60 * 1000L
        const val CHUNK_RECOVERY_MAX_ATTEMPTS = 3
        // Web signature-verification batching (channels.js:2888).
        const val VERIFY_BATCH_MAX = 50
        const val VERIFY_BATCH_WINDOW_MS = 100L
        // config.channels.latestMessageFetchLast
        const val LATEST_PREVIEW_FETCH = 10

        // TTL-aware republish on owner open (docs/TTL_REPUBLISH_PLAN.md; web
        // config.js storage.ttlRepublishAgeFraction + ttlRepublish.js).
        const val DEFAULT_RETENTION_DAYS = 180
        const val TTL_REPUBLISH_AGE_FRACTION = 0.8

        /** ADMIN_STATE `pins` codec. [Pin.pinnedAt] is copied, never regenerated. */
        fun pinsToJson(pins: List<Pin>): JSONArray = JSONArray(
            pins.map {
                JSONObject()
                    .put("targetId", it.targetId)
                    .put("pinnedAt", it.pinnedAt)
                    .put(
                        "snapshot",
                        JSONObject().put("sender", it.sender).put("text", it.text)
                            .put("senderName", it.senderName ?: JSONObject.NULL)
                            .put("ensName", it.ensName ?: JSONObject.NULL)
                    )
            }
        )

        fun pinsFromJson(arr: JSONArray): List<Pin> = (0 until arr.length()).mapNotNull { idx ->
            val p = arr.optJSONObject(idx) ?: return@mapNotNull null
            val snap = p.optJSONObject("snapshot")
            Pin(
                p.optString("targetId"),
                snap?.optString("text") ?: "",
                snap?.optString("sender") ?: "",
                senderName = snap?.let { jsonStringOrNull(it, "senderName") },
                ensName = snap?.let { jsonStringOrNull(it, "ensName") },
                pinnedAt = p.optLong("pinnedAt")
            )
        }

        /** Android optString returns "null" for JSON null — guard against that. */
        private fun jsonStringOrNull(o: JSONObject, key: String): String? {
            if (o.isNull(key)) return null
            return o.optString(key, "").ifEmpty { null }
        }

        /**
         * Should a -3 artifact with payload timestamp [artifactTs] be
         * republished, given the channel's retention of [storageDays]? The
         * storage node's TTL purge deletes by message timestamp; republishing
         * inside the last stretch of the artifact's life resets that clock.
         * Pure — mirrors web ttlRepublish.js shouldRepublish. Unknown age or
         * TTL must never trigger a publish.
         */
        fun shouldRepublish(artifactTs: Long, storageDays: Int?, now: Long = System.currentTimeMillis()): Boolean {
            if (artifactTs <= 0L) return false
            if (storageDays == null || storageDays <= 0) return false
            val ttlMs = storageDays * 86_400_000L
            return now - artifactTs > (TTL_REPUBLISH_AGE_FRACTION * ttlMs).toLong()
        }

        /**
         * First usable retention among [candidates], most trusted first,
         * falling back to [DEFAULT_RETENTION_DAYS]. Web: streamRetention.js
         * pickRetention.
         */
        fun pickRetention(vararg candidates: Int?): Int =
            candidates.firstOrNull { it != null && it > 0 } ?: DEFAULT_RETENTION_DAYS

        /**
         * Retention to decide about the -3 artifacts. The purge applies each
         * stream's own value, and [Channel.storageDays] is the -1's.
         */
        fun adminRetentionDays(channel: Channel): Int =
            pickRetention(channel.adminStorageDays, channel.storageDays)

        /**
         * Retention to decide about the -4 announces, without a lookup: the
         * key-responder sweep evaluates freshness every 45s and must never
         * turn that into a network call. The -3 value ranks above the default
         * because every path that sets a channel's retention sets all of
         * them: it is wrong only when one of those transactions failed alone.
         * Web: streamRetention.js keysRetentionDays.
         */
        fun keysRetentionDays(channel: Channel): Int =
            pickRetention(channel.keysStorageDays, channel.adminStorageDays, channel.storageDays)

        /**
         * Do a channel's stored streams agree on their retention? Values that
         * are not a usable retention are skipped rather than counted as a
         * mismatch: they are streams the channel does not have, or lookups
         * that failed, and an unknown value contradicts nothing.
         */
        /**
         * The channel's stored streams: -1, -3, and -4 on gated. Never the
         * ephemeral -2, which has no storage by design, and never the DM
         * inbox, which is an account-level stream.
         */
        /**
         * Does this stream still need the retention set to [days]?
         *
         * A stream that could not be read counts as needing it: skipping on
         * an unknown leaves it diverged with the UI reporting success, while
         * a redundant write only costs gas.
         */
        /**
         * Did the writes land? Every stream has to answer AND no longer need
         * the write. A stream that stopped answering is not converged either:
         * claiming success on silence is the failure this read-back exists
         * to catch.
         */
        /**
         * May the admin save [days]?
         *
         * A value equal to the figure on screen is normally a no-op, but it
         * is exactly what heals a channel whose streams disagree: the panel
         * shows the message stream, and the other streams can differ from it.
         * Blocking it there makes the warning's own advice unfollowable.
         */
        fun canSaveRetention(days: Int, shown: Int?, retentionInSync: Boolean): Boolean =
            days >= 1 && (days != shown || !retentionInSync)

        fun writesConverged(after: List<StoredStream>, needs: (StoredStream) -> Boolean): Boolean =
            after.all { it.read && !needs(it) }

        fun needsRetentionWrite(stream: StoredStream, days: Int): Boolean =
            !stream.read || stream.storageDays != days

        /** Does this stream still need [address] assigned to it? */
        fun needsNodeAdd(stream: StoredStream, address: String): Boolean =
            !stream.read || !stream.carries(address)

        /** Does this stream still carry [address], so it needs removing? */
        fun needsNodeRemove(stream: StoredStream, address: String): Boolean =
            !stream.read || stream.carries(address)

        fun storedStreams(channel: Channel): List<String> = buildList {
            add(channel.messageStreamId)
            if (channel.adminStreamId.isNotEmpty()) add(channel.adminStreamId)
            if (channel.type == "gated") {
                add(channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) })
            }
        }

        fun retentionInSync(vararg values: Int?): Boolean {
            val known = values.filterNotNull().filter { it > 0 }
            return known.all { it == known.firstOrNull() }
        }
    }
}
