package com.pombo.android.core.channels

import com.pombo.android.ChannelManager
import com.pombo.android.ChannelManager.Companion.RECENT_MESSAGE_MS
import com.pombo.android.ChannelManager.Companion.TIMESTAMP_TOLERANCE_MS
import com.pombo.android.ChannelManager.Companion.VERIFY_BATCH_MAX
import com.pombo.android.ChannelManager.Companion.VERIFY_BATCH_WINDOW_MS
import com.pombo.android.UiMessage
import com.pombo.android.core.Protocol
import com.pombo.android.data.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Whether a message is what it claims to be, and how much the sender's name is
 * worth: the batched signature recovery, the unsigned-path verdict, and the
 * trust ladder both feed.
 *
 * The state lives here; [ChannelManager] delegates the entry points so its
 * surface does not move. Everything this needs from the manager goes back
 * through it rather than to another collaborator, so a call site substituted
 * on the manager still intercepts.
 */
internal class MessageVerification(private val manager: ChannelManager) {

    private val bridge get() = manager.bridge
    private val scope get() = manager.scope
    private val ensStore get() = manager.ensStore
    private val isTrustedContact get() = manager.isTrustedContact

    private fun patchMessage(id: String, f: (UiMessage) -> UiMessage) = manager.patchMessage(id, f)

    /** Signature check for chunked-image manifests (same trust levels as text). */
    internal fun verifyImageManifestAsync(data: JSONObject) {
        val id = data.optString("id")
        val signature = data.optString("signature")
        // Same discriminant as text (see verifyAsync): absent = current
        // format, identity already established at ingest.
        if (signature.isEmpty()) { markVerifiedFromAccount(id, data); return }
        scope.launch {
            try {
                val hashes = data.optJSONArray("chunkHashes") ?: return@launch
                val canonical = Protocol.canonicalImageManifestData(
                    id = id,
                    imageId = data.optString("imageId"),
                    sender = data.optString("sender"),
                    timestamp = data.optLong("timestamp", 0L),
                    channelId = data.optString("channelId"),
                    originalMime = data.optString("originalMime"),
                    finalMime = data.optString("finalMime"),
                    finalSizeBytes = data.optInt("finalSizeBytes"),
                    chunkCount = data.optInt("chunkCount"),
                    chunkHashes = (0 until hashes.length()).map { hashes.optString(it) },
                    assembledSha256 = data.optString("assembledSha256"),
                    qualityUsed = if (data.isNull("qualityUsed")) null else data.optDouble("qualityUsed"),
                    preservedOriginal = data.optBoolean("preservedOriginal", false),
                    convertedTo = if (data.isNull("convertedTo")) null
                        else data.optString("convertedTo").ifEmpty { null }
                )
                val res = bridge.call("verifyCanonical", JSONObject()
                    .put("data", canonical).put("signature", signature))
                val recovered = res.optString("address")
                val valid = recovered.equals(data.optString("sender"), ignoreCase = true)
                markVerified(id, valid)
                if (valid) applyTrustLevel(id, recovered)
            } catch (e: Exception) {
                markVerified(id, false)
            }
        }
    }

    /**
     * Signature check for a P2P file/video announce.
     *
     * The announce IS signed on send (canonicalFileManifestData, see
     * sendFile) — the receive side simply never checked it, so a file bubble
     * carried no badge at all while text and images did. Same trust ladder as
     * text, and the same batched queue: nothing here is announce-specific
     * beyond rebuilding the canonical string the sender signed.
     */
    internal fun verifyFileAnnounceAsync(data: JSONObject) {
        val id = data.optString("id")
        val signature = data.optString("signature")
        // Same discriminant as text (see verifyAsync): absent = current
        // format, identity already established at ingest.
        if (signature.isEmpty()) { markVerifiedFromAccount(id, data); return }
        val meta = data.optJSONObject("metadata")
        val hashes = meta?.optJSONArray("pieceHashes")
        if (meta == null || hashes == null) { markVerified(id, null); return }
        val canonical = Protocol.canonicalFileManifestData(
            id = id,
            sender = data.optString("sender"),
            timestamp = data.optLong("timestamp", 0L),
            channelId = data.optString("channelId"),
            fileId = meta.optString("fileId"),
            fileName = meta.optString("fileName"),
            fileSize = meta.optLong("fileSize"),
            fileType = meta.optString("fileType"),
            pieceCount = meta.optInt("pieceCount"),
            pieceHashes = (0 until hashes.length()).map { hashes.optString(it) }
        )
        enqueueVerify(PendingVerify(id, canonical, signature, data.optString("sender")))
    }

    /**
     * Signature check for a Persistent File Sharing announce
     * (canonicalStorageFileManifestData, signed in StorageMedia.sendFile).
     *
     * Every optional field has to come back as absent-vs-present exactly as the
     * sender wrote it: the canonical emits `null` for a missing value, so an
     * absent `storedChunks` read as 0 would produce a different string and fail
     * a perfectly good signature. Hence isNull() rather than opt* defaults.
     */
    internal fun verifyStorageAnnounceAsync(data: JSONObject) {
        val id = data.optString("id")
        val signature = data.optString("signature")
        // Same discriminant as text (see verifyAsync): absent = current
        // format, identity already established at ingest.
        if (signature.isEmpty()) { markVerifiedFromAccount(id, data); return }
        val meta = data.optJSONObject("metadata") ?: run { markVerified(id, null); return }
        fun str(k: String): String? = if (meta.isNull(k)) null else meta.optString(k)
        fun int(k: String): Int? = if (meta.isNull(k)) null else meta.optInt(k)
        fun long(k: String): Long? = if (meta.isNull(k)) null else meta.optLong(k)
        val canonical = Protocol.canonicalStorageFileManifestData(
            id = id,
            sender = data.optString("sender"),
            timestamp = data.optLong("timestamp", 0L),
            channelId = data.optString("channelId"),
            transferId = str("transferId"),
            fileName = str("fileName"),
            fileType = str("fileType"),
            originalSize = long("originalSize"),
            compressedSize = long("compressedSize"),
            compression = str("compression"),
            totalChunks = int("totalChunks"),
            chunkDataSize = int("chunkDataSize"),
            chunkPartitions = int("chunkPartitions"),
            firstChunkPartition = int("firstChunkPartition"),
            firstChunkTs = long("firstChunkTs"),
            lastChunkTs = long("lastChunkTs"),
            storedChunks = int("storedChunks"),
            encSalt = str("encSalt")
        )
        enqueueVerify(PendingVerify(id, canonical, signature, data.optString("sender")))
    }

    /**
     * Trust level for a verified sender (web identity.js _getTrustLevelSync):
     * 2 = trusted contact, 1 = has ENS, 0 = valid signature only.
     */
    private fun applyTrustLevel(messageId: String, address: String) {
        val level = when {
            isTrustedContact(address) -> 2
            ensStore.cachedName(address) != null -> 1
            else -> 0
        }
        // Via patchMessage so a batched (historical) message — still in the
        // merge buffer at verify time — gets its trust level too, not just
        // live messages (the trusted-contact star was missing on history).
        patchMessage(messageId) { it.copy(trustLevel = level) }
    }

    /**
     * Verification discriminates on the PRESENCE of `signature`, no version
     * field (web identity.js verifyMessage):
     *
     *  - present → pre-migration message; verify the old way (the canonical
     *    hash functions are kept exactly for this).
     *  - absent  → current format (D6). Identity was already established at
     *    ingest — account = ecrecover(proof) — so trusting it here is not a
     *    weakening: the Streamr envelope authenticates the ephemeral publisher
     *    and the proof authenticates the account behind it. A third signature
     *    added no authority and re-exposed the address.
     *
     * The replay guard runs on BOTH paths — the window must not widen just
     * because the app-layer signature went away.
     */
    internal fun verifyAsync(channel: Channel, data: JSONObject, historical: Boolean) {
        val id = data.optString("id")
        val signature = data.optString("signature")
        val timestamp = data.optLong("timestamp", 0L)
        // The replay guard only applies to genuinely fresh messages. The web
        // keys this off the message age (isRecentMessage = < 30s), not off the
        // delivery path: a live subscription routinely delivers older messages
        // (resend-on-subscribe, slow peers, clock skew) and those must not be
        // failed for being old.
        val isRecent = System.currentTimeMillis() - timestamp < RECENT_MESSAGE_MS
        if (isRecent && kotlin.math.abs(System.currentTimeMillis() - timestamp) > TIMESTAMP_TOLERANCE_MS) {
            markVerified(id, false)
            return
        }
        if (signature.isEmpty()) { markVerifiedFromAccount(id, data); return }
        val canonical = Protocol.canonicalMessageData(
            id, data.optString("text"), data.optString("sender"), timestamp, data.optString("channelId")
        )
        enqueueVerify(PendingVerify(id, canonical, signature, data.optString("sender")))
    }

    /**
     * The unsigned-path verdict: valid via the ingest-resolved account, badge
     * from the same trust ladder as a recovered signature. No account at all
     * (a payload that never went through attachAccount, or arrived with no
     * publisher) keeps the web's old "no badge, not a red flag" behaviour.
     */
    private fun markVerifiedFromAccount(id: String, data: JSONObject) {
        val account = data.optString("account").ifEmpty { null }
        if (account == null) { markVerified(id, null); return }
        markVerified(id, true)
        applyTrustLevel(id, account)
    }

    private data class PendingVerify(
        val id: String, val canonical: String, val signature: String, val sender: String
    )

    private val verifyQueue = ArrayList<PendingVerify>()
    @Volatile private var verifyFlushJob: Job? = null

    /**
     * Batched signature verification (web verifies in a 50-message batch per
     * 100ms window over a worker pool — channels.js:2888). The WebView is
     * single-threaded, so what the batch buys here is the per-message
     * evaluateJavascript round-trip: a 100-message resend used to cost 100
     * bridge calls; now it costs two or three.
     */
    private fun enqueueVerify(pv: PendingVerify) {
        synchronized(verifyQueue) { verifyQueue.add(pv) }
        if (verifyFlushJob?.isActive == true) return
        verifyFlushJob = scope.launch {
            delay(VERIFY_BATCH_WINDOW_MS)
            while (true) {
                val batch = synchronized(verifyQueue) {
                    val take = ArrayList(verifyQueue.take(VERIFY_BATCH_MAX))
                    repeat(take.size) { verifyQueue.removeAt(0) }
                    take
                }
                if (batch.isEmpty()) return@launch
                try {
                    val items = JSONArray()
                    batch.forEach {
                        items.put(JSONObject().put("data", it.canonical).put("signature", it.signature))
                    }
                    val res = bridge.call(
                        "verifyCanonicalBatch", JSONObject().put("items", items), 60_000
                    )
                    val addrs = res.optJSONArray("addresses") ?: JSONArray()
                    batch.forEachIndexed { i, pv2 ->
                        val recovered = addrs.optString(i)
                        val valid = recovered.isNotEmpty() &&
                            recovered.equals(pv2.sender, ignoreCase = true)
                        markVerified(pv2.id, valid)
                        if (valid) applyTrustLevel(pv2.id, recovered)
                    }
                } catch (e: Exception) {
                    batch.forEach { markVerified(it.id, false) }
                }
            }
        }
    }
    private fun markVerified(id: String, valid: Boolean?) =
        patchMessage(id) { it.copy(verified = valid) }
}
