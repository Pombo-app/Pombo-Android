package com.pombo.android.core.channels

import android.util.Log
import com.pombo.android.ChannelManager
import com.pombo.android.ChannelManager.Companion.ASSEMBLY_TTL_MS
import com.pombo.android.ChannelManager.Companion.CHUNK_RECOVERY_DELAY_MS
import com.pombo.android.ChannelManager.Companion.CHUNK_RECOVERY_MAX_ATTEMPTS
import com.pombo.android.ChannelManager.Companion.CHUNK_RECOVERY_WINDOW_MS
import com.pombo.android.ChannelManager.Companion.GIF_MAX_ASSEMBLED_BYTES
import com.pombo.android.ChannelManager.Companion.IMAGE_MAX_ASSEMBLED_BYTES
import com.pombo.android.ChannelManager.Companion.MAX_CHUNKS
import com.pombo.android.ChannelManager.Companion.MAX_CHUNK_BYTES
import com.pombo.android.UiMessage
import com.pombo.android.core.PomboCrypto
import com.pombo.android.core.Protocol
import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Channel images and the chunked image transport: the avatar on the admin
 * stream, and the manifest/chunk/assembly pipeline for images in the timeline.
 *
 * The state lives here; [ChannelManager] keeps forwarding accessors for what
 * other areas read and delegates the entry points, so its surface does not
 * move. Everything this needs from the manager goes back through it rather
 * than to another collaborator, so a call site substituted on the manager
 * still intercepts.
 */
internal class ChannelImages(private val manager: ChannelManager) {

    private val bridge get() = manager.bridge
    private val scope get() = manager.scope
    private val myAddress get() = manager.myAddress
    private val myUsername get() = manager.myUsername
    private val imageStore get() = manager.imageStore
    private val ensStore get() = manager.ensStore
    private val blobStore get() = manager.blobStore
    private val sentDmStore get() = manager.sentDmStore
    private val _current get() = manager._current
    private val _messages get() = manager._messages
    private val deletedImageIds get() = manager.deletedImageIds
    private val epochKeys get() = manager.epochKeys
    private val switchGeneration get() = manager.switchGeneration
    private val onLocalStateChanged get() = manager.onLocalStateChanged

    private fun amOwner(channel: Channel) = manager.amOwner(channel)
    private fun channelByStream(streamId: String) = manager.channelByStream(streamId)
    private fun gatedAuthor(channel: Channel, streamId: String, meta: JSONObject) =
        manager.gatedAuthor(channel, streamId, meta)
    private fun isEpochChannel(channel: Channel?) = manager.isEpochChannel(channel)
    private fun mergeMessages(incoming: List<UiMessage>) = manager.mergeMessages(incoming)
    private suspend fun peerPubKey(address: String) = manager.peerPubKey(address)
    private suspend fun predecrypt(arr: JSONArray, password: String?) = manager.predecrypt(arr, password)
    private fun resolveEnsFor(address: String) = manager.resolveEnsFor(address)
    private fun stillCurrent(generation: Int) = manager.stillCurrent(generation)
    private fun verifyImageManifestAsync(data: JSONObject) = manager.verifyImageManifestAsync(data)
    private suspend fun publishForChannel(
        channel: Channel,
        streamId: String,
        partition: Int,
        payload: JSONObject
    ) = manager.publishForChannel(channel, streamId, partition, payload)

    private companion object {
        private const val TAG = "PomboChannels"
    }

    // Chunked-image reassembly: imageId -> pending state (manifest + chunks).
    private class PendingImage(var messageId: String) {
        /** Owning stream, so an assembled image can be ledgered like the web does. */
        var streamId: String = ""
        var chunkCount = 0
        var chunkHashes: List<String> = emptyList()
        var assembledSha256 = ""
        var finalSizeBytes = 0
        var finalMime = "image/jpeg"
        var haveManifest = false
        var lastSeen = System.currentTimeMillis()
        val chunks = HashMap<Int, ByteArray>()
        // Targeted recovery bookkeeping (web imageRecovery config).
        var manifestTs = 0L
        var recoveryAttempts = 0
        var recoveryScheduled = false
    }
    private val pendingImages = HashMap<String, PendingImage>()

    internal val _channelImage = MutableStateFlow<ByteArray?>(null)
    @Volatile internal var channelImageRev = 0
    /**
     * Fetches a channel image for any channel (not just the open one), used by
     * the list and Explore. Cache-first with a background refresh, one network
     * round-trip per channel thanks to the store's inflight dedup.
     */
    fun ensureChannelImage(adminStreamId: String, password: String? = null, label: String = adminStreamId) {
        if (adminStreamId.isEmpty()) return
        scope.launch {
            // One shot used to be it: a transient resend timeout or an empty
            // read during the bridge-connect burst (Explore's own fetches all
            // land in the same tick, see AppViewModel.onBridgeConnected) meant
            // that channel's card never got another chance this session. 3
            // tries, spaced apart, without hammering the bridge further.
            repeat(3) { attempt ->
                val result = imageStore.dedup(adminStreamId) { fetchChannelImage(adminStreamId, password, label) }
                if (result != null || attempt == 2) return@launch
                delay(2_500L * (attempt + 1))
            }
        }
    }

    /**
     * Resends the latest CHANNEL_IMAGE payload and validates type + owner
     * authority (the admin stream is owned by the address that prefixes it).
     * Returns the parsed payload, or null when absent/invalid.
     */
    internal suspend fun resendImagePayload(
        adminStreamId: String,
        password: String?,
        label: String = adminStreamId,
        timeoutMs: Long = 30_000
    ): JSONObject? {
        bridge.awaitConnected()
        val gatedChannel = channelByStream(adminStreamId)?.takeIf { it.type == "gated" }
        // recoverSigner unconditionally: an Explore fetch of a visible gated
        // storefront has no channel object, yet its authority check below
        // still needs the envelope signer. Raw envelopes only for channels we
        // KNOW are gated — the owner check below is the authority either way.
        val imageArgs = JSONObject()
            .put("streamId", adminStreamId)
            .put("partition", StreamConstants.ADMIN_CHANNEL_IMAGE)
            .put("last", 1)
            .put("recoverSigner", true)
        if (gatedChannel != null) imageArgs.put("raw", true)
        val res = bridge.call("resend", imageArgs, timeoutMs)
        val arr = res.optJSONArray("messages")
        if (arr == null) { Log.w(TAG, "channelImage $label ($adminStreamId): no messages array in resend response"); return null }
        if (arr.length() == 0) { Log.w(TAG, "channelImage $label ($adminStreamId): resend returned 0 entries"); return null }
        val entry = arr.getJSONObject(arr.length() - 1)
        val meta = entry.optJSONObject("meta") ?: JSONObject()
        val contentAny = entry.opt("content")
        var data: JSONObject = when (contentAny) {
            is JSONObject -> contentAny
            is String -> {
                val pwd = password
                if (pwd == null) { Log.w(TAG, "channelImage $label ($adminStreamId): content is sealed, no password to open it"); return null }
                try { JSONObject(PomboCrypto.decryptString(contentAny, pwd)) } catch (e: Exception) {
                    Log.w(TAG, "channelImage $label ($adminStreamId): password decrypt failed: ${e.message}"); return null
                }
            }
            else -> { Log.w(TAG, "channelImage $label ($adminStreamId): content is neither object nor string (${contentAny?.javaClass})"); return null }
        }
        // Gated: CHANNEL_IMAGE arrives as an epoch envelope. History context
        // so an image sealed under an older epoch opens in that epoch's
        // validity window instead of skipping the freshness rule.
        if (com.pombo.android.core.EpochKeyCrypto.isEpochEnvelope(data)) {
            val messageStreamId = adminStreamId.replace(Regex("-3$"), StreamConstants.SUFFIX_MESSAGE)
            val keysId = StreamConstants.deriveKeysId(messageStreamId)
            // List/Explore fetch channels that were never OPENED this session
            // — the epoch state only loads on open, so without this the image
            // stays undecryptable everywhere but inside the channel.
            epochKeys.loadPersistedState(messageStreamId)
            data = epochKeys.tryDecrypt(
                messageStreamId, keysId, data,
                gated = true, live = false, timestamp = meta.optLong("timestamp", 0L)
            )
                ?: run { Log.w(TAG, "channelImage $label ($adminStreamId): epoch envelope present but key unavailable/decrypt failed"); return null }
        }
        if (data.optString("type") != "CHANNEL_IMAGE") {
            Log.w(TAG, "channelImage $label ($adminStreamId): entry type is '${data.optString("type")}', not CHANNEL_IMAGE"); return null
        }
        if (gatedChannel != null) {
            // Gated: the transport publisher is the CLONE for everyone —
            // authority is the recovered envelope signer, and gatedAuthor
            // already enforces signer == namespace admin on -3 (D10c: never
            // fall back to the transport publisher).
            gatedAuthor(gatedChannel, adminStreamId, meta)
                ?: run { Log.w(TAG, "channelImage $label ($adminStreamId): gatedAuthor check failed"); return null }
        } else {
            // Unknown channels (Explore) may be gated storefronts: the clone
            // publishes for every member, so a non-owner transport publisher
            // is only acceptable when the envelope SIGNER is the owner —
            // otherwise any member could plant an image on the card.
            val senderId = meta.optString("publisherId").lowercase()
            val owner = adminStreamId.substringBefore('/').lowercase()
            if (senderId.isNotEmpty() && senderId != owner
                && meta.optString("signer").lowercase() != owner) {
                Log.w(TAG, "channelImage $label ($adminStreamId): authority check failed (sender=$senderId signer=${meta.optString("signer").lowercase()} owner=$owner)")
                return null
            }
        }
        return data
    }

    /** Returns the decoded bytes and caches them; null when unset/invalid. */
    private suspend fun fetchChannelImage(adminStreamId: String, password: String?, label: String = adminStreamId): ByteArray? {
        return try {
            val data = resendImagePayload(adminStreamId, password, label) ?: return null

            val hash = data.optString("hash")
            // Unchanged payload: keep what we have and skip the decode.
            if (hash.isNotEmpty() && imageStore.isFresh(adminStreamId, hash)) {
                return imageStore.cached(adminStreamId)
            }
            // Stale guard: a storage read can lag a just-published image; an
            // older payload must never overwrite the newer entry we hold.
            val ts = data.optLong("ts", 0L)
            if (ts in 1 until imageStore.cachedTs(adminStreamId)) {
                return imageStore.cached(adminStreamId)
            }
            val base64 = data.optString("data").substringAfter(',', "")
            if (base64.isEmpty()) return null
            val actual = java.security.MessageDigest.getInstance("SHA-256")
                .digest(base64.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (hash.isNotEmpty() && hash != actual) return null
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            if (bytes.size > IMAGE_MAX_ASSEMBLED_BYTES) return null
            imageStore.put(adminStreamId, hash.ifEmpty { actual }, bytes, ts)
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "channelImage $label ($adminStreamId): fetch threw ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Channel image for the open channel: paint whatever is cached first, then
     * refresh in the background (web ChannelImageManager stale-while-revalidate).
     */
    internal fun loadChannelImage(channel: Channel) {
        imageStore.cached(channel.adminStreamId)?.let { _channelImage.value = it }
        scope.launch {
            val fresh = imageStore.dedup(channel.adminStreamId) {
                fetchChannelImage(channel.adminStreamId, channel.password)
            }
            if (fresh != null && _current.value?.adminStreamId == channel.adminStreamId) {
                _channelImage.value = fresh
            }
        }
    }

    /**
     * Publishes a new channel image (owner only). [bytes] is the finished
     * 512² crop from the crop dialog (web renderSquareCrop output).
     *
     * Returns true when the storage node confirmed the new payload, false when
     * the publish went through but confirmation never arrived in time — the
     * web's "Published — propagating…" outcome, NOT a failure. A genuinely
     * failed publish throws.
     */
    suspend fun publishChannelImage(bytes: ByteArray, mime: String): Boolean {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can change the image")
        // Base64 + SHA-256 over a 512² image is still main-thread hostile.
        val base64 = withContext(Dispatchers.Default) {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
        val hash = withContext(Dispatchers.Default) {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(base64.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        val ts = System.currentTimeMillis()
        val payload = JSONObject()
            .put("type", "CHANNEL_IMAGE")
            .put("v", 1)
            .put("rev", channelImageRev + 1)
            .put("ts", ts)
            .put("createdBy", myAddress())
            .put("encrypted", false)
            .put("mime", mime)
            .put("hash", hash)
            .put("data", "data:$mime;base64,$base64")
        if (isEpochChannel(channel)) {
            // Epoch envelope, same reason as ADMIN_STATE: the SDK's group-key
            // AES on -3 would be unreadable cross-device. Visible channels
            // are STOREFRONTS instead: the image is the marketing and
            // publishes in the CLEAR so non-members (Explore) can render it —
            // only the transport differs.
            val content = if (channel.exposure == "visible") payload
            else epochKeys.encryptCurrent(channel.messageStreamId, payload)
                ?: throw IllegalStateException("No epoch key — cannot publish the channel image yet")
            val args = JSONObject()
                .put("streamId", channel.adminStreamId)
                .put("partition", StreamConstants.ADMIN_CHANNEL_IMAGE)
                .put("content", content)
            // -3 publishes as the ACCOUNT on gated too — the owner is its
            // only writer and the transport enforces that (new gated -3s
            // grant the clone subscribe-only).
            bridge.call("publishAsAccount", args, 60_000)
        } else {
            bridge.call("publish", JSONObject()
                .put("streamId", channel.adminStreamId)
                .put("partition", StreamConstants.ADMIN_CHANNEL_IMAGE)
                .put("content", payload), 60_000)
        }
        channelImageRev += 1
        _channelImage.value = bytes
        // Publish into the shared cache so list/Explore update without a refetch.
        // Recording ts also arms the stale guard in fetchChannelImage, so a
        // background refresh racing this publish cannot regress the cache.
        imageStore.put(channel.adminStreamId, hash, bytes, ts)

        // Verify it actually persisted, like the web's "Verifying…" step: an
        // optimistic local cache is not proof the storage node kept it. The
        // node takes a few seconds to index a publish, so poll by HASH ONLY —
        // going through fetchChannelImage here used to write the still-old
        // payload back into the store and revert the image everywhere.
        repeat(5) {
            kotlinx.coroutines.delay(3_000)
            val remote = try {
                resendImagePayload(channel.adminStreamId, channel.password, timeoutMs = 10_000)?.optString("hash")
            } catch (e: Exception) { null }
            if (remote == hash) return true
        }
        return false
    }

    /** Drops partial assemblies whose chunks stopped arriving (web: TTL sweep). */
    private fun sweepStaleAssemblies() {
        val cutoff = System.currentTimeMillis() - ASSEMBLY_TTL_MS
        pendingImages.entries.removeAll { it.value.lastSeen < cutoff }
    }

    /** Sends an image via the chunked transport (manifest + image_chunk on partition 0). */
    suspend fun sendImage(input: ByteArray, originalMime: String) {
        val channel = _current.value ?: return
        val sender = myAddress() ?: return
        // A DM uses the same chunked transport, but every payload is sealed in
        // the ECDH envelope: the peer's inbox accepts public publishes, so an
        // unsealed chunk there would be readable by anyone.
        // A DM image publishes through publishForChannel → sealed sender v2;
        // fail early here if the peer's key is unreachable, before the
        // expensive encode below.
        if (channel.type == "dm") {
            val peer = channel.peerAddress
                ?: throw IllegalStateException("DM conversation has no peer")
            peerPubKey(peer) ?: throw IllegalStateException(
                "Cannot send image: peer public key not available"
            )
        }

        // The heaviest single operation in the app: bitmap decode, then a
        // resolution × quality ladder that re-compresses until the output fits,
        // then SHA-256 per chunk and over the whole image. Never on main.
        // Sealed transports (DM envelope, password) double the base64 cost, so
        // their chunks are cut smaller to stay under the 220KB wire ceiling.
        val sealed = channel.type == "dm" || channel.password != null
        val enc = withContext(Dispatchers.Default) {
            com.pombo.android.core.MediaEncoder.encode(
                input, originalMime,
                if (sealed) com.pombo.android.core.MediaEncoder.CHUNK_RAW_SEALED
                else com.pombo.android.core.MediaEncoder.CHUNK_RAW
            )
        } ?: throw IllegalStateException("Couldn't read image")
        val id = Protocol.generateMessageId()
        val imageId = Protocol.generateMessageId()
        val timestamp = System.currentTimeMillis()

        // Immediate local echo (already assembled).
        mergeMessages(listOf(UiMessage(
            id = id, text = "", sender = sender, senderName = myUsername(),
            timestamp = timestamp, mine = true, isImage = true,
            imageId = imageId, imageBytes = enc.bytes, imageMime = enc.mime, verified = true
        )))

        // Ledger the image before publishing: it is the only copy once storage
        // retention drops the chunks, and blob sync pushes it to my other
        // devices. Marked unsynced so the next sync picks it up.
        blobStore.save(
            imageId, channel.messageStreamId,
            com.pombo.android.core.ImageBlobStore.toDataUrl(enc.bytes, enc.mime),
            synced = false
        )
        onLocalStateChanged()

        // Unsigned manifest (D6) — identity comes from the proof/envelope.
        val manifest = JSONObject()
            .put("type", "image").put("transport", "chunked").put("v", 2)
            .put("id", id).put("imageId", imageId).put("sender", sender)
            .put("senderName", myUsername() ?: JSONObject.NULL)
            .put("timestamp", timestamp)
            .put("originalMime", enc.originalMime).put("finalMime", enc.mime)
            .put("finalSizeBytes", enc.bytes.size).put("chunkCount", enc.chunks.size)
            .put("chunkHashes", JSONArray(enc.chunkHashes))
            .put("assembledSha256", enc.assembledSha256)
            .put("preservedOriginal", enc.preservedOriginal)
            .put("convertedTo", enc.convertedTo ?: JSONObject.NULL)
            .put("qualityUsed", enc.quality ?: JSONObject.NULL)

        // Chunks first, manifest last (web media.js order): the manifest is what
        // tells the receiver how many pieces to expect, so publishing it before
        // them invites an assembly attempt against data that has not landed.
        for ((index, piece) in enc.chunks.withIndex()) {
            val chunkMsg = JSONObject()
                .put("type", "image_chunk").put("v", 2)
                .put("imageId", imageId).put("chunkIndex", index)
                .put("timestamp", System.currentTimeMillis())
                .put("chunkHash", enc.chunkHashes[index])
                .put("data", android.util.Base64.encodeToString(piece, android.util.Base64.NO_WRAP))
            publishForChannel(channel, channel.messageStreamId, StreamConstants.P_MESSAGES, chunkMsg)
        }
        publishForChannel(channel, channel.messageStreamId, StreamConstants.P_MESSAGES, manifest)

        // A DM's sent half lives only here (the peer's inbox is not readable by
        // us). Image bytes stay in the ledger, so the stored entry is metadata
        // only — same split as the web's addSentMessage.
        if (channel.type == "dm") {
            sentDmStore.add(channel.messageStreamId, manifest)
            onLocalStateChanged()
        }
    }

    /**
     * Repaints any on-screen placeholders whose blobs just landed (called by
     * the sync pipeline after a blob import). A no-op for ids that belong to
     * closed conversations — their bubbles hydrate from the ledger on open.
     */
    fun hydrateImages(imageIds: List<String>) {
        imageIds.forEach { hydrateFromLedger(it) }
    }

    internal fun hydrateFromLedger(imageId: String) {
        if (imageId.isEmpty() || imageId in deletedImageIds) return
        scope.launch {
            val dataUrl = blobStore.load(imageId) ?: return@launch
            val (bytes, mime) = com.pombo.android.core.ImageBlobStore.fromDataUrl(dataUrl) ?: return@launch
            synchronized(manager) {
                _messages.value = _messages.value.map {
                    if (it.imageId == imageId && it.imageBytes == null) {
                        it.copy(imageBytes = bytes, imageMime = mime, pending = false)
                    } else it
                }
            }
        }
    }

    /**
     * @param showBubble whether the placeholder belongs on the visible timeline.
     *   False for a DM arriving while another channel is open: the chunk
     *   assembly still has to be registered, but the bubble must not be merged
     *   into a timeline it does not belong to.
     */
    internal fun handleImageManifest(channel: Channel, data: JSONObject, showBubble: Boolean = true) {
        if (data.optString("transport") != "chunked" || data.optInt("v") != 2) return
        val imageId = data.optString("imageId").ifEmpty { return }
        if (imageId in deletedImageIds) return
        val messageId = data.optString("id").ifEmpty { return }
        val hashes = data.optJSONArray("chunkHashes") ?: return
        val chunkCount = data.optInt("chunkCount")
        if (chunkCount <= 0 || hashes.length() != chunkCount) return

        // Refuse oversized manifests before allocating anything (web media.js
        // enforces the same ceiling at assembly time). A hostile peer must not
        // be able to make us buffer an arbitrary number of bytes.
        val finalMime = data.optString("finalMime", "image/jpeg")
        val declaredSize = data.optInt("finalSizeBytes")
        val sizeLimit = if (finalMime == "image/gif") GIF_MAX_ASSEMBLED_BYTES else IMAGE_MAX_ASSEMBLED_BYTES
        if (declaredSize <= 0 || declaredSize > sizeLimit) return
        if (chunkCount > MAX_CHUNKS) return

        val sender = data.optString("sender").ifEmpty { return }
        val mine = sender.equals(myAddress(), ignoreCase = true)
        // Placeholder bubble (image loading) keyed by the message id.
        if (showBubble && _messages.value.none { it.id == messageId }) {
            mergeMessages(listOf(UiMessage(
                id = messageId, text = "", sender = sender,
                senderName = data.optStringOrNull("senderName"),
                timestamp = data.optLong("timestamp", 0L), mine = mine,
                isImage = true, imageId = imageId, imageMime = data.optString("finalMime", "image/jpeg"),
                // Same ENS treatment as handleText: seed from the cache and
                // resolve below. Without both, a sender whose only message on
                // screen is an image never showed a name or avatar at all —
                // and an image merged after another message's resolve patch
                // missed the patch for good.
                ensName = ensStore.cachedName(sender),
                ensAvatar = ensStore.cachedAvatar(sender)
            )))
            // If we already hold the bytes, the placeholder resolves without
            // waiting for chunks that may no longer exist on the storage node.
            hydrateFromLedger(imageId)
            if (!mine) resolveEnsFor(sender)
        }

        // Chunks may arrive before the manifest (resend order): always (re)bind the message id.
        val p = pendingImages.getOrPut(imageId) { PendingImage(messageId) }
        p.messageId = messageId
        p.chunkCount = chunkCount
        p.chunkHashes = (0 until hashes.length()).map { hashes.optString(it) }
        p.assembledSha256 = data.optString("assembledSha256")
        p.finalSizeBytes = declaredSize
        p.finalMime = finalMime
        p.streamId = channel.messageStreamId
        p.haveManifest = true
        p.manifestTs = data.optLong("timestamp", 0L)
        // Image manifests are signed too (web verifyMessage branches on
        // transport 'chunked' and hashes the manifest fields).
        verifyImageManifestAsync(data)
        tryAssemble(imageId)
        // A manifest whose chunks never finish used to wait forever — the web
        // re-queries a window around it instead (channels.js:3842-3930).
        if (channel.type != "dm") scheduleChunkRecovery(channel, imageId)
    }

    /**
     * Targeted chunk recovery: when the manifest is in hand but chunks are
     * still missing after a grace period, re-resend a window around the
     * manifest's timestamp and feed any matching chunks back through the
     * normal path. Up to 3 attempts (web: 3 fetch tries over up to 20 rounds;
     * here the resend IS the transfer, so 3 windows carry the same coverage).
     * Channels only — a DM's chunks replay through the inbox router.
     */
    private fun scheduleChunkRecovery(channel: Channel, imageId: String) {
        val pending = pendingImages[imageId] ?: return
        if (pending.recoveryScheduled) return
        pending.recoveryScheduled = true
        val generation = switchGeneration
        scope.launch {
            while (true) {
                delay(CHUNK_RECOVERY_DELAY_MS)
                val p = pendingImages[imageId] ?: return@launch  // assembled or swept
                if (imageId in deletedImageIds || !stillCurrent(generation)) return@launch
                if (p.recoveryAttempts >= CHUNK_RECOVERY_MAX_ATTEMPTS) return@launch
                p.recoveryAttempts++
                val ts = p.manifestTs.takeIf { it > 0 } ?: return@launch
                try {
                    val res = bridge.call("resendWindow", JSONObject()
                        .put("streamId", channel.messageStreamId)
                        .put("partition", StreamConstants.P_MESSAGES)
                        .put("before", ts + CHUNK_RECOVERY_WINDOW_MS)
                        .put("windowMs", CHUNK_RECOVERY_WINDOW_MS * 2)
                        .put("budgetMs", 20_000), 30_000)
                    val arr = res.optJSONArray("messages") ?: continue
                    val contents = predecrypt(arr, channel.password)
                    for (i in 0 until arr.length()) {
                        val data = contents[i] as? JSONObject ?: continue
                        if (data.optString("type") == "image_chunk" &&
                            data.optString("imageId") == imageId
                        ) handleImageChunk(data)
                    }
                    android.util.Log.d(
                        TAG,
                        "chunk recovery for …${imageId.takeLast(8)}: attempt ${p.recoveryAttempts}, " +
                            "have ${p.chunks.size}/${p.chunkCount}"
                    )
                } catch (e: Exception) { /* next attempt */ }
            }
        }
    }

    /** Drops every trace of a deleted image (web blocks the same five paths). */
    internal fun tombstoneImage(imageId: String) {
        deletedImageIds.add(imageId)
        pendingImages.remove(imageId)
        scope.launch { blobStore.forget(imageId) }
    }

    internal fun handleImageChunk(data: JSONObject) {
        if (data.optInt("v") != 2) return
        val imageId = data.optString("imageId").ifEmpty { return }
        if (imageId in deletedImageIds) return
        val index = data.optInt("chunkIndex", -1)
        if (index < 0) return
        val b64 = data.optString("data").ifEmpty { return }
        val bytes = try { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) } catch (e: Exception) { return }
        if (bytes.size > MAX_CHUNK_BYTES) return
        if (index >= MAX_CHUNKS) return
        // Chunks routinely arrive before their manifest, so an entry may be
        // created here — bound the buffered total to keep that path safe too.
        val p = pendingImages.getOrPut(imageId) { PendingImage("") }
        if (p.chunks.values.sumOf { it.size } + bytes.size > GIF_MAX_ASSEMBLED_BYTES) return
        p.chunks[index] = bytes
        p.lastSeen = System.currentTimeMillis()
        sweepStaleAssemblies()
        tryAssemble(imageId)
    }

    /** Verifies and assembles a chunked image once all chunks + manifest are present. */
    private fun tryAssemble(imageId: String) {
        val p = pendingImages[imageId] ?: return
        if (!p.haveManifest || p.chunks.size < p.chunkCount) return
        val digest = try { java.security.MessageDigest.getInstance("SHA-256") } catch (e: Exception) { return }

        val ordered = ArrayList<ByteArray>(p.chunkCount)
        var total = 0
        for (i in 0 until p.chunkCount) {
            val bytes = p.chunks[i] ?: return
            val hex = digest.digest(bytes).joinToString("") { "%02x".format(it) }
            digest.reset()
            if (hex != p.chunkHashes.getOrNull(i)) return  // hash mismatch — keep waiting for redelivery
            ordered.add(bytes)
            total += bytes.size
        }
        if (total != p.finalSizeBytes) return
        val assembled = ByteArray(total)
        var off = 0
        for (b in ordered) { System.arraycopy(b, 0, assembled, off, b.size); off += b.size }
        val assembledHex = digest.digest(assembled).joinToString("") { "%02x".format(it) }
        if (assembledHex != p.assembledSha256) return

        pendingImages.remove(imageId)
        // Web media.js dispatches assembled data by imageId — order-independent.
        _messages.value = _messages.value.map {
            if (it.imageId == imageId) it.copy(imageBytes = assembled, imageMime = p.finalMime, pending = false) else it
        }

        // Ledger it, exactly as media.js does after a successful assembly. This
        // covers RECEIVED images too, which until now lived only in memory:
        // reopening the chat re-fetched them, and once retention dropped the
        // chunks they were gone for good. Saved unsynced so blob sync carries
        // them to this account's other devices.
        val streamId = p.streamId
        if (streamId.isNotEmpty()) {
            scope.launch {
                blobStore.save(
                    imageId, streamId,
                    com.pombo.android.core.ImageBlobStore.toDataUrl(assembled, p.finalMime),
                    synced = false
                )
                onLocalStateChanged()
            }
        }
    }

}
