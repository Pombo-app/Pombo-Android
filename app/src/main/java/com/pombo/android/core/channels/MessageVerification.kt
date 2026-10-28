package com.pombo.android.core.channels

import com.pombo.android.ChannelManager
import com.pombo.android.ChannelManager.Companion.ACCOUNT_PROVEN
import com.pombo.android.ChannelManager.Companion.RECENT_MESSAGE_MS
import com.pombo.android.ChannelManager.Companion.TIMESTAMP_TOLERANCE_MS
import com.pombo.android.UiMessage
import com.pombo.android.data.Channel
import org.json.JSONObject

/**
 * Whether a message's author is vouched for, and how much the sender's name is
 * worth: the verdict the author badge reads, and the trust ladder.
 *
 * The state lives here; [ChannelManager] delegates the entry points so its
 * surface does not move. Everything this needs from the manager goes back
 * through it rather than to another collaborator, so a call site substituted
 * on the manager still intercepts.
 */
internal class MessageVerification(private val manager: ChannelManager) {

    private val ensStore get() = manager.ensStore
    private val isTrustedContact get() = manager.isTrustedContact

    private fun patchMessage(id: String, f: (UiMessage) -> UiMessage) = manager.patchMessage(id, f)

    internal fun verifyImageManifestAsync(data: JSONObject) = markFromProof(data)

    internal fun verifyFileAnnounceAsync(data: JSONObject) = markFromProof(data)

    internal fun verifyStorageAnnounceAsync(data: JSONObject) = markFromProof(data)

    /**
     * The replay guard applies to genuinely fresh messages only. The web keys
     * it off the message age (isRecentMessage = < 30s), not the delivery path:
     * a live subscription routinely delivers older messages (resend-on-
     * subscribe, slow peers, clock skew) and those must not be failed for
     * being old.
     */
    internal fun verifyAsync(channel: Channel, data: JSONObject, historical: Boolean) {
        val id = data.optString("id")
        if (data.optString("signature").isNotEmpty()) { markVerified(id, null); return }
        val timestamp = data.optLong("timestamp", 0L)
        val isRecent = System.currentTimeMillis() - timestamp < RECENT_MESSAGE_MS
        if (isRecent && kotlin.math.abs(System.currentTimeMillis() - timestamp) > TIMESTAMP_TOLERANCE_MS) {
            markVerified(id, false)
            return
        }
        markFromProof(data)
    }

    /**
     * Anti-spoofing: vouched for only when ingest bound the publisher to the
     * account with a proof. Legacy messages (with `signature`) and payloads
     * without a proof are attributed to their address and get no badge, never
     * a red flag.
     */
    private fun markFromProof(data: JSONObject) {
        val id = data.optString("id")
        val account = data.optString("account").ifEmpty { null }
        if (account == null || data.optString("signature").isNotEmpty() || !data.optBoolean(ACCOUNT_PROVEN)) {
            markVerified(id, null)
            return
        }
        markVerified(id, true)
        applyTrustLevel(id, account)
    }

    /**
     * Trust level for a verified sender (web identity.js _getTrustLevelSync):
     * 2 = trusted contact, 1 = has ENS, 0 = verified only.
     */
    private fun applyTrustLevel(messageId: String, address: String) {
        val level = when {
            isTrustedContact(address) -> 2
            ensStore.cachedName(address) != null -> 1
            else -> 0
        }
        // Via patchMessage so a batched (historical) message — still in the
        // merge buffer at verify time — gets its trust level too, not just
        // live messages.
        patchMessage(messageId) { it.copy(trustLevel = level) }
    }

    private fun markVerified(id: String, valid: Boolean?) =
        patchMessage(id) { it.copy(verified = valid) }
}
