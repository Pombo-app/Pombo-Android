package com.pombo.android.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pombo.android.core.Notifier
import com.pombo.android.core.PushRegistry
import com.pombo.android.core.PushVerifier
import com.pombo.android.core.StorageReadSigner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives relay wake signals. The relay sends them data-only on purpose: an
 * FCM `notification` block would be drawn by the system before this code runs,
 * and the whole point is that most pushes must NOT be shown.
 *
 * Mirrors sw.js handlePushWithVerification step for step:
 *   1. no tag            -> ignore (K-anonymity noise)
 *   2. tag not ours      -> ignore (a colliding channel, not subscribed)
 *   3. nothing newer     -> ignore (false positive)
 *   4. otherwise         -> notify, then advance the watermark
 */
class PomboMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "wake") return
        val tag = data["tag"]
        if (tag.isNullOrEmpty()) return

        val myAddress = com.pombo.android.identity.WalletStore(applicationContext).address

        // Key-request wake ('keys'): NEVER a notification. Someone published
        // a KEY_REQUEST on a channel sharing this tag — if this device is the
        // key responder for one of them, sweep and answer. App up: through
        // the live bridge; dead process: a one-shot worker boots a headless
        // bridge. Anything else about this wake is silence by contract.
        if (data["channelType"] == "keys") {
            val settings = com.pombo.android.data.SettingsStore(applicationContext)
                .apply { scopeAddress = myAddress }
            if (settings.keyResponderChannels.none { it.tag.equals(tag, ignoreCase = true) }) return
            if (ForegroundGate.inForeground) {
                KeyResponderGate.sweepNow?.invoke()
            } else {
                KeyResponderWorker.enqueueNow(applicationContext)
            }
            return
        }

        val registry = PushRegistry(applicationContext).apply { scopeAddress = myAddress }
        val entries = registry.entriesByTag(tag)
        if (entries.isEmpty()) return

        scope.launch {
            // Read once for the whole wake: the same key signs the storage
            // reads and opens the sealed envelopes below. Absent before the
            // first unlock (Direct Boot), where neither is possible.
            val privateKey = try {
                com.pombo.android.identity.WalletStore(applicationContext).privateKey
            } catch (e: Exception) { null }

            val verifier = PushVerifier(
                endpointsFor = { registry.endpointsFor(it) },
                signHeaders = { url ->
                    StorageReadSigner.parse(url)?.let { StorageReadSigner.headers(it, privateKey) }
                },
                resolveProviders = { com.pombo.android.core.GraphApi.storageNodeUrls(it) ?: emptyList() },
                providersCheckedAt = { registry.providersCheckedAt(it) },
                rememberProviders = { streamId, urls -> registry.rememberProviders(streamId, urls) }
            )

            for (entry in entries) {
                val result = verifier.verify(entry)
                if (!result.hasNew) continue
                registry.updateLastSeen(entry.streamId, result.timestamp)
                notify(entry, result, myAddress, privateKey)
            }
        }
    }

    private suspend fun notify(
        entry: PushRegistry.Entry,
        result: PushVerifier.Result,
        myAddress: String?,
        privateKey: String?
    ) {
        var conversationId = entry.streamId
        var title = entry.name
        var avatar: android.graphics.Bitmap? = null
        // What the notification body is built from: the envelope as stored,
        // replaced by the message itself once it opens.
        var body = result.content
        if (entry.type == "dm-inbox") {
            // One relay row covers the whole inbox, so the per-peer mute
            // has to happen here, and the title comes from the sender —
            // same precedence the UI uses: contact nickname → ENS → the
            // DM room's local name → short address. Plus the face: ENS
            // avatar when cached, the generated one otherwise.
            //
            // Under sealed sender the row's publisherId is a throwaway
            // key, so the sender is only knowable by OPENING the envelope
            // — natively (SealedSenderCrypto): the bridge WebView does
            // not exist in a dead process. Failure paths fall back to a
            // generic notification rather than a wrong one: envelope that
            // is not ours, or the key still locked before the first
            // unlock (Direct Boot). Legacy plaintext rows keep the
            // publisherId attribution — it was the wallet in that era.
            val sender: String? = run {
                val content = result.content
                if (content != null && content.optInt("v") == 2 && content.has("epk")) {
                    if (privateKey.isNullOrEmpty() || myAddress.isNullOrEmpty()) null
                    else com.pombo.android.core.SealedSenderCrypto
                        .open(content, privateKey, myAddress)
                        ?.also { body = it.second }
                        ?.first
                } else {
                    result.publisherId?.lowercase()
                }
            }
            if (sender != null) {
                val settings = com.pombo.android.data.SettingsStore(applicationContext)
                    .apply { scopeAddress = myAddress }
                if (sender in settings.mutedDmPeers) return
                // Group under the conversation the app opens for this peer
                // (its channel id IS the peer's inbox).
                conversationId = "$sender/Pombo-DM-1"
                val nickname = com.pombo.android.data.ContactsStore(applicationContext)
                    .apply { scopeAddress = myAddress }
                    .load().firstOrNull { it.address.equals(sender, ignoreCase = true) }?.nickname
                // cachedName/cachedAvatar read memory only, and nothing else fills
                // it in this process. Already on IO, and only on the notify branch.
                val ens = com.pombo.android.core.EnsStore(applicationContext)
                    .apply { warmUp() }
                val roomName = com.pombo.android.data.ChannelStore(applicationContext)
                    .apply { scopeAddress = myAddress }
                    .load().firstOrNull {
                        it.type == "dm" && it.peerAddress?.equals(sender, ignoreCase = true) == true
                    }?.name
                title = nickname
                    ?: ens.cachedName(sender)
                    ?: roomName
                    ?: (sender.take(6) + "…" + sender.takeLast(4))
                avatar = com.pombo.android.core.NotificationAvatar.bitmapFor(
                    applicationContext, sender,
                    if (settings.ensAvatars) ens.cachedAvatar(sender) else null
                )
            }
        }

        // sw.js never notifies while a client is focused — it hands the
        // event to the app instead. Same here: with the app on screen the
        // wake becomes an unread update (or nothing, if that conversation
        // is the one being looked at); the system notification is for a
        // user who is NOT in the app.
        if (ForegroundGate.tryHandle(entry.type, conversationId, result.timestamp)) return

        Notifier(applicationContext).postMessage(
            conversationId = conversationId,
            title = title,
            body = PushVerifier.preview(entry.type, body),
            largeIcon = avatar
        )
    }

    /**
     * FCM rotates tokens (reinstall, restore, data clear). The old one is dead
     * the moment this fires, so the app must re-register on next start — the
     * relay drops stale tokens when a send fails, and the flag makes us push a
     * fresh one instead of waiting for that.
     */
    override fun onNewToken(token: String) {
        applicationContext
            .getSharedPreferences("pombo_push", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .putBoolean("needs_reregister", true)
            .apply()
    }
}
