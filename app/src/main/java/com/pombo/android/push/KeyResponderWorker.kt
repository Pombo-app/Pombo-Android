package com.pombo.android.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pombo.android.core.StreamConstants
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Background half of the owner key-responder: with the app dead there is no
 * bridge and no ViewModel, so this worker boots a HEADLESS bridge (the
 * WebView is Activity-independent by construction), answers every retained
 * key request on the marked channels, and tears everything down. Runs on the
 * periodic 15-minute base and on FCM 'keys' wakes; skips itself while the
 * app is foregrounded — the in-app sweep loop owns that case.
 *
 * The publish paths it uses (resend, publishAsGate, gate reads) are not
 * chain-guarded methods, so headless operation needs no biometric approval.
 */
class KeyResponderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ForegroundGate.inForeground) return Result.success()

        val wallet = com.pombo.android.identity.WalletStore(applicationContext)
        val address = wallet.address ?: return Result.success()
        val privateKey = wallet.privateKey ?: return Result.success()

        val settings = com.pombo.android.data.SettingsStore(applicationContext)
            .apply { scopeAddress = address }
        val entries = settings.keyResponderChannels
        if (entries.isEmpty()) return Result.success()

        val byKeysStream = entries.associateBy {
            it.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(it.messageStreamId) }
        }
        val byMessageStream = entries.associateBy { it.messageStreamId }

        val bridge = withContext(Dispatchers.Main) {
            com.pombo.android.bridge.PomboBridge(
                applicationContext,
                object : com.pombo.android.bridge.PomboBridge.Listener {
                    override fun onBridgeStatus(status: String) {}
                    override fun onBridgeConnected(address: String) {}
                    override fun onBridgeMessage(
                        streamId: String, partition: Int, contentJson: String, metaJson: String
                    ) {}
                    override fun onBridgeError(message: String) {}
                },
                privateKey
            )
        }
        val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            bridge.awaitConnected(90_000)

            val store = com.pombo.android.data.EpochKeyStore(applicationContext)
                .apply { scopeAddress = address }
            val manager = com.pombo.android.core.EpochKeyManager(
                store = store,
                scope = sweepScope,
                myAddress = { address },
                publishKeys = { keysStreamId, data ->
                    val gate = byKeysStream[keysStreamId]?.gateAddress
                        ?: throw IllegalStateException("Unknown responder channel for $keysStreamId")
                    bridge.call("publishAsGate", JSONObject()
                        .put("streamId", keysStreamId)
                        .put("partition", StreamConstants.P_KEY_EXCHANGE)
                        .put("content", data)
                        .put("gateAddress", gate))
                },
                resendKeys = { keysStreamId ->
                    val gate = byKeysStream[keysStreamId]?.gateAddress?.lowercase()
                    val out = mutableListOf<com.pombo.android.core.EpochKeyManager.Entry>()
                    val res = bridge.call("resend", JSONObject()
                        .put("streamId", keysStreamId)
                        .put("partition", StreamConstants.P_KEY_EXCHANGE)
                        .put("last", 1000)
                        .put("recoverSigner", true), 30_000)
                    val arr = res.optJSONArray("messages")
                    if (arr != null) for (i in 0 until arr.length()) {
                        val entry = arr.optJSONObject(i) ?: continue
                        val content = entry.opt("content") as? JSONObject ?: continue
                        val meta = entry.optJSONObject("meta") ?: JSONObject()
                        // Same authority rule as the in-app resend: the clone
                        // is the transport publisher, the SIGNER is the author.
                        if (meta.optString("publisherId").lowercase() != gate) continue
                        val signer = meta.optString("signer").lowercase()
                        if (signer.isEmpty()) continue
                        out.add(com.pombo.android.core.EpochKeyManager.Entry(
                            content, signer, meta.optLong("timestamp", 0L)))
                    }
                    out
                },
                onKeyAdopted = { _, _ -> },
                checkGateAccess = { messageStreamId, requester ->
                    val gate = byMessageStream[messageStreamId]?.gateAddress
                    if (gate == null) false
                    else try {
                        bridge.call("gateCheckAccess", JSONObject()
                            .put("gate", gate).put("user", requester)).optBoolean("access", false)
                    } catch (e: Exception) {
                        false   // fail-closed, like the in-app wiring
                    }
                },
                currentEpochOnly = { messageStreamId ->
                    val gate = byMessageStream[messageStreamId]?.gateAddress
                    if (gate == null) false
                    else try {
                        bridge.call("gateInfo", JSONObject().put("gate", gate))
                        false
                    } catch (e: Exception) {
                        true    // unreadable gate answers current-epoch-only
                    }
                },
                myPrivateKey = { privateKey }
            )

            for (entry in entries) {
                try {
                    manager.ensureChannelKeys(
                        entry.messageStreamId,
                        entry.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(entry.messageStreamId) },
                        allowMint = false,   // a background mint on an established channel forks it
                        gated = false        // no rotation timers on a scope about to die
                    )
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "sweep failed on …${entry.messageStreamId.takeLast(20)}: ${e.message}")
                }
            }
            // Ranked answers are scheduled up to RANK_MAX × 2s out — the wraps
            // publish on sweepScope, which dies with this worker.
            delay(ANSWER_DRAIN_MS)
            return Result.success()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "key responder worker failed: ${e.message}")
            return Result.retry()
        } finally {
            sweepScope.cancel()
            withContext(Dispatchers.Main) { bridge.destroy() }
        }
    }

    companion object {
        private const val TAG = "PomboKeyResponder"
        private const val UNIQUE_PERIODIC = "key-responder-periodic"
        private const val UNIQUE_NOW = "key-responder-now"
        private const val ANSWER_DRAIN_MS = 20_000L

        private fun online() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** FCM 'keys' wake with the process dead: sweep as soon as possible. */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<KeyResponderWorker>()
                    .setConstraints(online()).build())
        }

        /** The guaranteed base: no push needed, ~15 min worst-case latency. */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<KeyResponderWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(online()).build())
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC)
        }
    }
}
