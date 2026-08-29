package com.pombo.android.core.channels

import android.util.Log
import com.pombo.android.ChannelManager
import com.pombo.android.ChannelManager.StorageTransferInfo
import com.pombo.android.UiMessage
import com.pombo.android.core.Protocol
import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * File sharing, both transports: the P2P swarm (`file_announce`) and
 * Persistent File Sharing over the storage cluster (`storage_file_announce`),
 * with the transfer bookkeeping the Active Transfers list reads.
 *
 * The state lives here; [ChannelManager] keeps forwarding accessors for what
 * other areas read and delegates the entry points, so its surface does not
 * move. Everything this needs from the manager goes back through it rather
 * than to another collaborator, so a call site substituted on the manager
 * still intercepts.
 */
internal class FileTransfers(private val manager: ChannelManager) {

    private val store get() = manager.store
    private val scope get() = manager.scope
    private val myAddress get() = manager.myAddress
    private val myUsername get() = manager.myUsername
    private val ensStore get() = manager.ensStore
    private val sentDmStore get() = manager.sentDmStore
    private val transferDir get() = manager.transferDir
    private val media get() = manager.media
    private val storageMedia get() = manager.storageMedia
    private val epochKeys get() = manager.epochKeys
    private val _current get() = manager._current
    private val _channels get() = manager._channels
    private val _messages get() = manager._messages
    private val channelSwitchMutex get() = manager.channelSwitchMutex
    private val onLocalStateChanged get() = manager.onLocalStateChanged

    private fun confirmMessage(id: String) = manager.confirmMessage(id)
    private fun isEpochChannel(channel: Channel?) = manager.isEpochChannel(channel)
    private fun mergeMessages(incoming: List<UiMessage>) = manager.mergeMessages(incoming)
    private fun resolveEnsFor(address: String) = manager.resolveEnsFor(address)
    private fun verifyFileAnnounceAsync(data: JSONObject) = manager.verifyFileAnnounceAsync(data)
    private fun verifyStorageAnnounceAsync(data: JSONObject) = manager.verifyStorageAnnounceAsync(data)
    private suspend fun peerPubKey(address: String) = manager.peerPubKey(address)
    private suspend fun publishTextWithRetry(block: suspend () -> Unit) = manager.publishTextWithRetry(block)
    private suspend fun publishContent(
        streamId: String,
        partition: Int,
        payload: JSONObject,
        password: String?,
        dmPeer: String? = null
    ) = manager.publishContent(streamId, partition, payload, password, dmPeer)
    private suspend fun publishForChannel(
        channel: Channel,
        streamId: String,
        partition: Int,
        payload: JSONObject
    ) = manager.publishForChannel(channel, streamId, partition, payload)

    private companion object {
        private const val TAG = "PomboChannels"
    }

    /**
     * Shares a video over the P2P swarm (web media.js sendVideo): only the
     * type check is video-specific — everything downstream is [sendFile].
     */
    suspend fun sendVideo(
        fileName: String,
        fileSize: Long,
        mimeType: String,
        openInput: () -> java.io.InputStream
    ) {
        if (mimeType !in com.pombo.android.core.MediaConfig.ALLOWED_VIDEO_TYPES) {
            throw IllegalArgumentException(
                "Invalid video type. Allowed: " +
                    com.pombo.android.core.MediaConfig.ALLOWED_VIDEO_TYPES.joinToString(", ")
            )
        }
        sendFile(fileName, fileSize, mimeType, openInput)
    }

    /**
     * Shares a file in the open channel over the P2P swarm — the Android side
     * of web media.js sendFile: hash piece by piece, sign the manifest (the
     * hashes live INSIDE the signature), announce on -1/P0, then seed.
     *
     * The source is streamed in PIECE_SIZE slices straight into a [PieceStore]
     * (the same sparse file a download would build), so a 500MB send never
     * holds the file in memory and the serving path needs no second read path.
     */
    suspend fun sendFile(
        fileName: String,
        fileSize: Long,
        mimeType: String,
        openInput: () -> java.io.InputStream
    ) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (fileSize <= 0) throw IllegalArgumentException("File is empty")
        if (fileSize > com.pombo.android.core.MediaConfig.MAX_FILE_SIZE)
            throw IllegalArgumentException("File exceeds the 500MB limit")
        val sender = myAddress() ?: throw IllegalStateException("No identity")
        val isDm = channel.type == "dm"
        // DM: resolve the pair's key BEFORE hashing — failing after a 500MB
        // hashing pass over a missing public key would waste all of it.
        val dmPeerPk = if (isDm) {
            val peer = channel.peerAddress ?: throw IllegalStateException("DM has no peer")
            peerPubKey(peer) ?: throw IllegalStateException("Peer public key unavailable")
        } else null

        // The wire requires exactly 36 UTF-8 bytes — a random UUID is exactly that.
        val fileId = java.util.UUID.randomUUID().toString()
        val store = withContext(Dispatchers.IO) {
            com.pombo.android.core.PieceStore.open(transferDir, fileId, fileSize)
        }
        val pieceHashes = ArrayList<String>(com.pombo.android.core.MediaConfig.pieceCount(fileSize))
        try {
            withContext(Dispatchers.IO) {
                openInput().use { input ->
                    val buf = ByteArray(com.pombo.android.core.MediaConfig.PIECE_SIZE)
                    var index = 0
                    var total = 0L
                    while (total < fileSize) {
                        // A content stream may return short reads; a piece
                        // boundary must never move because of one.
                        val want = minOf(buf.size.toLong(), fileSize - total).toInt()
                        var read = 0
                        while (read < want) {
                            val n = input.read(buf, read, want - read)
                            if (n < 0) break
                            read += n
                        }
                        if (read != want) throw java.io.IOException(
                            "file shrank while reading: got $read of $want at piece $index"
                        )
                        val piece = buf.copyOf(read)
                        val hash = com.pombo.android.core.PieceStore.sha256Hex(piece)
                        if (!store.writePiece(index, piece, hash)) {
                            throw java.io.IOException("piece $index write failed")
                        }
                        pieceHashes.add(hash)
                        total += read
                        index++
                    }
                }
            }
        } catch (e: Exception) {
            store.close()
            com.pombo.android.core.PieceStore.delete(transferDir, fileId)
            throw e
        }

        val id = Protocol.generateMessageId()
        val timestamp = System.currentTimeMillis()
        // Unsigned (D6) — identity comes from the proof/envelope; the
        // canonical builders in Protocol.kt survive only to verify history.
        val metadataJson = JSONObject()
            .put("fileId", fileId).put("fileName", fileName)
            .put("fileSize", fileSize).put("fileType", mimeType)
            .put("pieceCount", pieceHashes.size)
            .put("pieceHashes", org.json.JSONArray(pieceHashes))
        val announce = JSONObject()
            .put("type", "file_announce").put("v", 2).put("id", id)
            .put("sender", sender).put("senderName", myUsername() ?: JSONObject.NULL)
            .put("timestamp", timestamp)
            .put("metadata", metadataJson)
            .put("replyTo", JSONObject.NULL)

        val metadata = com.pombo.android.core.MediaController.FileMetadata(
            fileId = fileId, fileName = fileName, fileSize = fileSize,
            fileType = mimeType, pieceCount = pieceHashes.size, pieceHashes = pieceHashes
        )
        // Local echo before the publish, like every other send path.
        mergeMessages(listOf(UiMessage(
            id = id, text = "", sender = sender, senderName = myUsername(),
            timestamp = timestamp, mine = true, pending = true, verified = true,
            file = metadata,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender)
        )))
        try {
            if (isDm) {
                // Same wire as web DM announces: sealed-sender v2 to the
                // peer's inbox, one throwaway publisher per message.
                val peer = channel.peerAddress
                    ?: throw IllegalStateException("DM conversation has no peer")
                publishTextWithRetry {
                    publishContent(
                        channel.messageStreamId, StreamConstants.P_MESSAGES,
                        announce, password = null, dmPeer = peer
                    )
                }
            } else {
                publishForChannel(channel, channel.messageStreamId, StreamConstants.P_MESSAGES, announce)
            }
        } catch (e: Exception) {
            // The seed store stays on disk: re-sending re-uses nothing today,
            // but a failed announce must not strand a bubble that looks sent.
            _messages.value = _messages.value.filterNot { it.id == id }
            store.close()
            com.pombo.android.core.PieceStore.delete(transferDir, fileId)
            throw e
        }
        confirmMessage(id)
        if (isDm) {
            // Web parity (media.js sendFile DM branch): persist the manifest
            // LEAN — without pieceHashes (a 500MB file's hashes are ~160KB in
            // one sync message) and with the signature dropped alongside them
            // (it covers the hashes, so kept it could never verify again).
            val leanMeta = JSONObject()
                .put("fileId", fileId).put("fileName", fileName)
                .put("fileSize", fileSize).put("fileType", mimeType)
                .put("pieceCount", pieceHashes.size)
            val lean = JSONObject(announce.toString())
                .put("metadata", leanMeta)
                .put("signature", JSONObject.NULL)
            sentDmStore.add(channel.messageStreamId, lean)
            onLocalStateChanged()
        }
        media.seedSentFile(
            metadata = metadata,
            messageStreamId = channel.messageStreamId,
            isDm = isDm,
            password = channel.password,
            store = store
        )
    }

    // ---- P2P shared file (wire type 'file_announce') ----

    /**
     * Turns a file announcement into a bubble. Nothing is downloaded here: the
     * announcement carries only the description and the piece hashes, and the
     * user decides whether to fetch the content — a channel could hold hundreds
     * of announcements and auto-fetching would be both a data bill and a way to
     * fill someone's storage from a message.
     */
    internal fun handleFileAnnounce(channel: Channel, data: JSONObject) {
        val messageId = data.optString("id").ifEmpty { return }
        // Rejected rather than shown as a broken bubble: FileMetadata.from
        // refuses an announcement without one hash per piece, and without those
        // a download could never be verified.
        val metadata = com.pombo.android.core.MediaController.FileMetadata
            .from(data.optJSONObject("metadata")) ?: run {
            Log.w(TAG, "unusable file_announce $messageId — dropped")
            return
        }
        if (_messages.value.any { it.id == messageId }) return

        val sender = data.optString("sender").ifEmpty { return }
        mergeMessages(listOf(UiMessage(
            id = messageId,
            text = "",
            sender = sender,
            senderName = data.optStringOrNull("senderName"),
            timestamp = data.optLong("timestamp", 0L),
            mine = sender.equals(myAddress(), ignoreCase = true),
            file = metadata,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender)
        )))
        if (!sender.equals(myAddress(), ignoreCase = true)) resolveEnsFor(sender)
        verifyFileAnnounceAsync(data)
    }

    // ---- Persistent File Sharing (wire type 'storage_file_announce') ----

    /** Turns a storage-file announcement into a bubble (download deferred to phase 3). */
    internal fun handleStorageFileAnnounce(channel: Channel, data: JSONObject) {
        val messageId = data.optString("id").ifEmpty { return }
        val metadata = com.pombo.android.core.StorageMedia.StorageFileMetadata
            .from(data.optJSONObject("metadata")) ?: run {
            Log.w(TAG, "unusable storage_file_announce $messageId — dropped")
            return
        }
        // Our own echo would double the optimistic bubble; the id guards it.
        if (_messages.value.any { it.id == messageId }) return
        val sender = data.optString("sender").ifEmpty { return }
        mergeMessages(listOf(UiMessage(
            id = messageId,
            text = "",
            sender = sender,
            senderName = data.optStringOrNull("senderName"),
            timestamp = data.optLong("timestamp", 0L),
            mine = sender.equals(myAddress(), ignoreCase = true),
            storageFile = metadata,
            ensName = ensStore.cachedName(sender),
            ensAvatar = ensStore.cachedAvatar(sender)
        )))
        if (!sender.equals(myAddress(), ignoreCase = true)) resolveEnsFor(sender)
        verifyStorageAnnounceAsync(data)
    }

    /**
     * transferId -> [StorageTransferInfo]. Storage transfers survive a channel
     * switch (the engine is standalone), so the Active Transfers list needs the
     * file/channel names even when it is no longer the open channel — the
     * progress snapshots do not carry them.
     */
    private val storageTransferChannel = java.util.concurrent.ConcurrentHashMap<String, StorageTransferInfo>()

    /** transferId -> the Job running its download, so pauseStorageTransfer() can cancel just that one. */
    private val storageDownloadJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /**
     * transferId -> "pausing" | "resuming": the gap between a tap and the
     * engine confirming it. Cancellation is cooperative — a Job mid
     * network-read only unwinds once that read returns — so the download's
     * own status can lag either tap by seconds. The Active Transfers row
     * flips its icon and shows "Pausing…"/"Resuming…" off this map instead
     * of waiting that lag out. Entries are cleared by the owning Job's
     * completion handler (see [launchStorageDownload]).
     */
    internal val _storageTransferPhase = MutableStateFlow<Map<String, String>>(emptyMap())
    fun storageTransferInfo(transferId: String): StorageTransferInfo? = storageTransferChannel[transferId]

    /** Distinct channel stream ids of the storage transfers currently in flight. */
    fun activeStorageTransferStreams(): Set<String> {
        val tids = storageMedia.uploads.value.filterValues { it.stage != "done" && it.error == null }.keys +
            storageMedia.downloads.value.filterValues { it.status == "downloading" }.keys
        return tids.mapNotNull { storageTransferChannel[it]?.messageStreamId }.toSet()
    }

    /** A human label for a channel/DM (Active Transfers list). */
    private fun channelDisplayName(channel: Channel): String = when {
        channel.name.isNotEmpty() -> channel.name
        channel.type == "dm" -> channel.peerAddress?.let { ensStore.cachedName(it) ?: it.take(8) } ?: "DM"
        else -> channel.messageStreamId.substringAfterLast('/').take(12)
    }

    /**
     * Re-inserts the optimistic bubbles for storage UPLOADS still running in
     * [channel]. A channel switch clears the timeline, but an in-progress upload
     * has not published its announce yet, so nothing else would bring the bubble
     * back until it finishes. Downloads need no restore — their announce reloads
     * from history and the download-progress state drives the bubble.
     */
    internal fun restoreStorageUploadBubbles(channel: Channel) {
        val bubbles = storageMedia.uploads.value.keys.mapNotNull { tid ->
            val info = storageTransferChannel[tid] ?: return@mapNotNull null
            if (info.messageStreamId != channel.messageStreamId) return@mapNotNull null
            val b = info.uploadBubble ?: return@mapNotNull null
            if (_messages.value.any { it.id == b.id }) null else b
        }
        if (bubbles.isNotEmpty()) mergeMessages(bubbles)
    }

    /**
     * Shares a file in the open channel over the storage nodes (Persistent File
     * Sharing). The engine publishes chunks, verifies & repairs, then signs and
     * publishes the announce; here we only own the optimistic bubble and the
     * timeline. Progress is observed via [storageMedia].uploads, keyed by the
     * bubble's transferId.
     */
    suspend fun sendStorageFile(source: com.pombo.android.core.StorageMedia.Source) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (source.size <= 0) throw IllegalArgumentException("File is empty")
        val sender = myAddress() ?: throw IllegalStateException("No identity")
        val isDm = channel.type == "dm"
        // DM: resolve the pair's key up front — the chunks seal with it and the
        // announce rides the ECDH envelope to the peer's inbox.
        val dmPeerPk = if (isDm) {
            val peer = channel.peerAddress ?: throw IllegalStateException("DM has no peer")
            peerPubKey(peer) ?: throw IllegalStateException("Peer public key unavailable")
        } else null

        val id = Protocol.generateMessageId()
        val tid = Protocol.generateMessageId()
        val timestamp = System.currentTimeMillis()
        val draft = com.pombo.android.core.StorageMedia.StorageFileMetadata(
            transferId = tid, fileName = source.fileName, fileType = source.fileType,
            originalSize = source.size, compressedSize = null, compression = "none",
            totalChunks = null, chunkDataSize = null,
            chunkPartitions = StreamConstants.STORAGE_CHUNK_PARTITIONS,
            firstChunkPartition = StreamConstants.STORAGE_FIRST_CHUNK_PARTITION,
            firstChunkTs = null, lastChunkTs = null, storedChunks = null, encSalt = null
        )
        // Optimistic bubble before the upload, like every other send path. Kept in
        // storageTransferChannel so it can be re-inserted if the user leaves and
        // returns to this channel mid-upload (the announce is not published yet).
        val bubble = UiMessage(
            id = id, text = "", sender = sender, senderName = myUsername(),
            timestamp = timestamp, mine = true, pending = true, verified = true,
            storageFile = draft,
            ensName = ensStore.cachedName(sender), ensAvatar = ensStore.cachedAvatar(sender)
        )
        storageTransferChannel[tid] = StorageTransferInfo(source.fileName, channelDisplayName(channel), channel.messageStreamId, bubble)
        mergeMessages(listOf(bubble))
        try {
            // Gated channels: capture the CURRENT key for the whole transfer
            // and fail closed without one — a plaintext chunk on a stored
            // partition would be harvestable forever.
            val epochKey = if (!isDm && isEpochChannel(channel)) {
                epochKeys.currentKey(channel.messageStreamId)
                    ?: throw IllegalStateException(
                        "No epoch key for ${channel.messageStreamId} — cannot store media")
            } else null
            // Members-only: chunks travel under the SHARED key; the clone path
            // would stamp the uploader's account onto every stored chunk.
            val membersOnly = !isDm && channel.authorMode == "members"
            val sharedKeyHex = if (membersOnly) {
                epochKeys.publishKeyFor(channel.messageStreamId)?.keyHex
                    ?: throw IllegalStateException(
                        "No publish key for ${channel.messageStreamId} — cannot store media on a Members-only channel")
            } else null
            val gate = if (!isDm && channel.type == "gated" && !membersOnly) {
                channel.gateAddress ?: throw IllegalStateException(
                    "Gate address unknown for ${channel.messageStreamId} — cannot publish")
            } else null
            val result = storageMedia.sendFile(
                messageStreamId = channel.messageStreamId,
                source = source,
                password = channel.password,
                isDm = isDm,
                dmPeerPublicKey = dmPeerPk,
                // public/password channels publish chunks under the channel's
                // ephemeral identity (same as the announce); readOnly keeps
                // the account (D3), same rule as publishChannel.
                channelEphemeral = !isDm && !isEpochChannel(channel) && !channel.readOnly,
                epochKey = epochKey,
                gateAddress = gate,
                sharedPublishKeyHex = sharedKeyHex,
                messageId = id,
                transferId = tid
            )
            // Replace the draft with the confirmed metadata from the announce.
            val finalMeta = com.pombo.android.core.StorageMedia.StorageFileMetadata
                .from(result.announce.optJSONObject("metadata"))
            _messages.value = _messages.value.map {
                if (it.id == id) it.copy(pending = false, storageFile = finalMeta ?: it.storageFile) else it
            }
            if (isDm) {
                // A DM cannot be replayed from the peer's inbox — persist the whole
                // (small) announce so this device keeps rendering the bubble across
                // restarts. No bulky fields here (unlike mesh pieceHashes).
                sentDmStore.add(channel.messageStreamId, result.announce)
                onLocalStateChanged()
            }
        } catch (e: Exception) {
            _messages.value = _messages.value.filterNot { it.id == id }
            throw e
        }
    }

    /**
     * Runs the actual download for [meta] against [channel] as a tracked Job.
     * Shared by [downloadStorageFile] (channel + announce come from whatever is
     * on screen) and [resumeStorageTransfer] (both come from caches instead,
     * since that one is called from outside any open channel).
     *
     * [waitFor], when given, is joined first — cancellation is cooperative, so
     * a Job just cancel()led by [pauseStorageTransfer] can still be unwinding
     * (its status not yet flipped to "paused") when the user immediately taps
     * resume. Starting a fresh download while that is still true would hit
     * downloadFile()'s own isDownloading() guard and silently do nothing, so
     * resumeStorageTransfer waits for the old Job to fully finish first.
     */
    private fun launchStorageDownload(
        channel: Channel,
        meta: com.pombo.android.core.StorageMedia.StorageFileMetadata,
        timestamp: Long,
        waitFor: kotlinx.coroutines.Job? = null
    ): kotlinx.coroutines.Job {
        val job = scope.launch {
            waitFor?.join()
            try {
                if (channel.type == "dm") {
                    // Chunks live on OUR own inbox (the sender published there); the
                    // channel's streamId points at the PEER's inbox. Open them with
                    // the sender's key (= the peer for this DM).
                    val me = myAddress()?.lowercase() ?: return@launch
                    val peer = channel.peerAddress ?: return@launch
                    val peerPk = peerPubKey(peer) ?: throw IllegalStateException("Peer public key unavailable")
                    storageMedia.downloadFile(
                        channel.messageStreamId, meta, timestamp, channel.password,
                        isDm = true, dmInboxStreamId = "$me/Pombo-DM-1", peerPublicKey = peerPk
                    )
                } else if (isEpochChannel(channel)) {
                    // Epoch chunks (0x04) resolve their kid per row; history
                    // freshness (gated) judges old kids by the row's transport
                    // timestamp. runBlocking is acceptable here: the opener
                    // runs on the HTTP parser's IO thread, which already
                    // blocks on the row handler as backpressure.
                    val gated = channel.type == "gated"
                    storageMedia.downloadFile(
                        channel.messageStreamId, meta, timestamp, password = null,
                        epochOpener = { bytes, ts ->
                            kotlinx.coroutines.runBlocking {
                                epochKeys.tryOpenBinary(
                                    channel.messageStreamId, channel.keysStreamId, bytes,
                                    gated = gated, live = false, timestamp = ts
                                )
                            }
                        }
                    )
                } else {
                    storageMedia.downloadFile(channel.messageStreamId, meta, timestamp, channel.password)
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "storage download failed: ${e.message}")
                }
            }
        }
        job.invokeOnCompletion {
            // Clear this transfer's pausing/resuming phase, but only while this
            // Job is still the registered one: a pause's unwind finishing after
            // resume already installed its successor must not wipe the
            // successor's "resuming" marker.
            if (storageDownloadJobs[meta.transferId] === job) {
                _storageTransferPhase.value = _storageTransferPhase.value - meta.transferId
            }
        }
        return job
    }

    /**
     * Starts downloading the storage-shared file announced by [messageId] in the
     * open channel. The engine handles resume and the completed-file handoff; the
     * bubble reflects [storageMedia].downloads. Non-DM only for now.
     */
    fun downloadStorageFile(messageId: String) {
        val channel = _current.value ?: return
        val msg = _messages.value.firstOrNull { it.id == messageId } ?: return
        val meta = msg.storageFile ?: return
        if (storageDownloadJobs[meta.transferId]?.isActive == true) return
        storageTransferChannel[meta.transferId] = StorageTransferInfo(
            meta.fileName, channelDisplayName(channel), channel.messageStreamId,
            meta = meta, timestamp = msg.timestamp
        )
        storageDownloadJobs[meta.transferId] = launchStorageDownload(channel, meta, msg.timestamp)
    }

    /**
     * Pauses a running storage download from the Active Transfers list: cancels
     * just its Job. [StorageMedia.downloadFile]'s own cancellation handling keeps
     * the staged bytes on disk (see its CancellationException branch), so
     * [resumeStorageTransfer] continues from here instead of starting over.
     * The "pausing" phase flips first — cancellation is cooperative, so the
     * download's own status can lag the tap by a network read's worth of time;
     * the Job's completion handler clears it once the unwind confirms.
     */
    fun pauseStorageTransfer(transferId: String) {
        val job = storageDownloadJobs[transferId] ?: return
        if (!job.isActive) return
        _storageTransferPhase.value = _storageTransferPhase.value + (transferId to "pausing")
        job.cancel()
    }

    /**
     * Cancels a storage download: "I do not want this", the mesh
     * cancelDownload's meaning — unlike [pauseStorageTransfer], the staged
     * bytes are deleted once the Job unwinds. Works on paused downloads too
     * (their Job already finished; join returns at once).
     */
    fun cancelStorageTransfer(transferId: String) {
        _storageTransferPhase.value = _storageTransferPhase.value - transferId
        val job = storageDownloadJobs.remove(transferId)
        job?.cancel()
        scope.launch {
            job?.join()
            storageMedia.discardDownload(transferId)
        }
    }

    /**
     * Resumes a storage download [pauseStorageTransfer] stopped. Deliberately
     * does NOT go through [downloadStorageFile]/[_messages] — the Active
     * Transfers list this is called from lives outside any open channel (see
     * [storageTransferChannel]'s doc comment), so the announce and channel are
     * resolved from caches that survive a channel switch instead.
     */
    fun resumeStorageTransfer(transferId: String) {
        val previous = storageDownloadJobs[transferId]
        // isActive alone cannot tell "running" from "cancelled but still
        // unwinding" — and the instant icon flip invites the user to tap
        // resume during exactly that unwind. During "pausing" the resume must
        // win: the successor Job joins [previous] before touching the engine,
        // so the old run has fully released the transfer by the time it starts.
        if (previous?.isActive == true && _storageTransferPhase.value[transferId] != "pausing") return
        val info = storageTransferChannel[transferId] ?: return
        val meta = info.meta ?: return
        val channel = _channels.value.firstOrNull { it.messageStreamId == info.messageStreamId } ?: return
        storageDownloadJobs[transferId] = launchStorageDownload(channel, meta, info.timestamp, waitFor = previous)
        _storageTransferPhase.value = _storageTransferPhase.value + (transferId to "resuming")
    }

    /** The completed storage download's file on disk, or null (for saving). */
    fun storageCompletedFile(transferId: String): java.io.File? = storageMedia.completedFile(transferId)

    /**
     * Starts fetching the file announced by [messageId] in the open channel.
     * Safe to call twice — a download already running is left alone.
     */
    /**
     * Cancels a running download from the Active Transfers list. Runs under
     * the channel-switch mutex because it may drop media partitions, and those
     * only ever change inside it.
     */
    fun cancelTransfer(fileId: String) {
        scope.launch {
            channelSwitchMutex.withLock {
                releaseMediaIfIdle(media.cancelDownload(fileId))
            }
        }
    }

    /** Pauses a running download from the Active Transfers list — keeps its bytes. */
    fun pauseTransfer(fileId: String) = media.pauseDownload(fileId)

    /** Resumes a download [pauseTransfer] stopped, from wherever it left off. */
    fun resumeTransfer(fileId: String) = media.resumeDownload(fileId)

    /** Stops SERVING a file (bytes kept, reseed offered) from the Transfers list. */
    fun stopSeeding(fileId: String) {
        scope.launch {
            channelSwitchMutex.withLock {
                releaseMediaIfIdle(media.stopSeeding(fileId))
            }
        }
    }

    /** Deletes a seed's bytes and record for good — the destructive half of the old stop. */
    fun deleteSeed(fileId: String) {
        scope.launch {
            channelSwitchMutex.withLock {
                releaseMediaIfIdle(media.deleteSeed(fileId))
            }
        }
    }

    /**
     * Re-activates an inactive seed from the Transfers list. The password
     * comes from the channel store — the registry deliberately does not hold
     * it — so a seed whose channel was left cannot be reseeded (the row
     * should not be offered for those). Runs under the channel-switch mutex
     * because it brings media partitions up.
     */
    fun reseedFile(fileId: String, messageStreamId: String) {
        scope.launch {
            channelSwitchMutex.withLock {
                val password = _channels.value.firstOrNull { it.messageStreamId == messageStreamId }?.password
                try {
                    media.reseedRegistered(fileId, password)
                } catch (e: Exception) {
                    Log.w(TAG, "reseed failed for $fileId: ${e.message}")
                }
            }
        }
    }

    /** Files held complete on disk but not being served — the Transfers list's "inactive" rows. */
    fun inactiveSeeds() = media.inactiveSeeds()

    /**
     * Drops the media partitions a finished/cancelled transfer was holding,
     * unless something still needs them: another transfer on the same stream,
     * or the channel being on screen (its open handler owns them then).
     */
    private suspend fun releaseMediaIfIdle(ref: Pair<String, Boolean>?) {
        val (messageStreamId, isDm) = ref ?: return
        if (isDm) {
            if (_current.value?.type != "dm" && !media.hasActiveDMTransfers()) {
                media.myDmEphemeralId()?.let { media.releaseMediaPartitions(it) }
            }
            return
        }
        val onScreen = _current.value?.messageStreamId == messageStreamId
        if (!onScreen && !media.hasActiveTransfers(messageStreamId)) {
            media.releaseMediaPartitions(StreamConstants.deriveEphemeralId(messageStreamId))
        }
    }

    fun downloadFile(messageId: String) {
        val channel = _current.value ?: return
        val metadata = _messages.value.firstOrNull { it.id == messageId }?.file ?: return
        // A lean record (own DM manifest persisted without pieceHashes) must
        // never start a download: with no expected hash, writePiece would
        // accept whatever bytes arrive. The full announce is what peers hold.
        if (metadata.pieceHashes.size != metadata.pieceCount) {
            Log.w(TAG, "refusing download of ${metadata.fileId}: manifest has no piece hashes")
            return
        }
        if (channel.type == "dm") {
            // Sealed-sender pieces open with OUR OWN static key and the epk in
            // each envelope — no peer-key registration, no publisher hint. The
            // bridge does it inline on the -2/P2 callback.
            scope.launch {
                media.startDownload(
                    messageStreamId = channel.messageStreamId,
                    metadata = metadata,
                    password = null,
                    isDm = true
                )
            }
            return
        }
        media.startDownload(
            messageStreamId = channel.messageStreamId,
            metadata = metadata,
            password = channel.password,
            isDm = false
        )
    }

    // ---- chunked image receive (transport 'chunked' v2) ----

}
