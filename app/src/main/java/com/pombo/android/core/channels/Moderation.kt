package com.pombo.android.core.channels

import android.util.Log
import com.pombo.android.ChannelManager
import com.pombo.android.ChannelManager.ChannelPerms
import com.pombo.android.ChannelManager.Companion.ADMIN_POLL_INTERVAL_MS
import com.pombo.android.ChannelManager.Companion.GATE_MODE_NFT
import com.pombo.android.ChannelManager.Companion.GATE_MODE_NONE
import com.pombo.android.ChannelManager.Companion.GATE_MODE_PAID
import com.pombo.android.ChannelManager.Companion.GATE_MODE_TOKEN
import com.pombo.android.ChannelManager.Companion.WRAPPED_NATIVE
import com.pombo.android.ChannelManager.Companion.pinsFromJson
import com.pombo.android.ChannelManager.Companion.pinsToJson
import com.pombo.android.ChannelManager.GateCardInfo
import com.pombo.android.ChannelManager.GateEntryInfo
import com.pombo.android.ChannelManager.GateMemberFlags
import com.pombo.android.ChannelManager.MemberRow
import com.pombo.android.ChannelManager.Pin
import com.pombo.android.core.ModAction
import com.pombo.android.core.ModComposition
import com.pombo.android.core.PomboCrypto
import com.pombo.android.core.StreamConstants
import com.pombo.android.data.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Moderation and gated-channel membership: the ADMIN_STATE snapshot (pins,
 * hidden messages, bans), the on-chain permission checks behind it, and the
 * gate contract reads the membership surfaces need.
 *
 * The state lives here; [ChannelManager] keeps forwarding accessors for what
 * other areas read and delegates the entry points, so its surface does not
 * move. Everything this needs from the manager goes back through it rather
 * than to another collaborator, so a call site substituted on the manager
 * still intercepts.
 */
internal class Moderation(private val manager: ChannelManager) {

    private val bridge get() = manager.bridge
    private val scope get() = manager.scope
    private val myAddress get() = manager.myAddress
    private val adminFloorStore get() = manager.adminFloorStore
    private val epochKeys get() = manager.epochKeys
    private val _current get() = manager._current
    private val _channels get() = manager._channels
    private val _messages get() = manager._messages
    private val messages get() = manager.messages
    private val switchGeneration get() = manager.switchGeneration

    private fun gatedAuthor(channel: Channel, streamId: String, meta: JSONObject) =
        manager.gatedAuthor(channel, streamId, meta)
    private fun isEpochChannel(channel: Channel?) = manager.isEpochChannel(channel)
    private fun stillCurrent(generation: Int) = manager.stillCurrent(generation)
    private suspend fun setPermissionsRetry(streamId: String, assignments: JSONArray) =
        manager.setPermissionsRetry(streamId, assignments)
    private suspend fun setPermissionsRetry(streamIds: List<String>, assignments: JSONArray) =
        manager.setPermissionsRetry(streamIds, assignments)
    private suspend fun publishForChannel(
        channel: Channel,
        streamId: String,
        partition: Int,
        payload: JSONObject
    ) = manager.publishForChannel(channel, streamId, partition, payload)

    private companion object {
        private const val TAG = "PomboChannels"
        /** Delays before each read-back of a published ADMIN_STATE (web adminConfirmDelaysMs). */
        private val CONFIRM_DELAYS_MS = longArrayOf(5_000L, 10_000L, 20_000L, 40_000L)
        private const val CONFIRM_REPUBLISH_LIMIT = 3
    }

    internal val rotations by lazy {
        RotationRetry(scope, object : RotationRetry.Host {
            override fun account(): String? = myAddress()
            override suspend fun rotate(messageStreamId: String) {
                val channel = _channels.value.find { it.messageStreamId == messageStreamId }
                    ?: throw IllegalStateException("Channel no longer stored")
                val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(messageStreamId) }
                epochKeys.rotateEpoch(messageStreamId, keysId)
            }
            override suspend fun covered(messageStreamId: String, addresses: Set<String>) =
                updateStored(messageStreamId) {
                    it.copy(rotatedForNoAccess = (it.rotatedForNoAccess + addresses).distinct())
                }
            override fun stillOwned(messageStreamId: String): Boolean =
                _channels.value.find { it.messageStreamId == messageStreamId }?.let { amOwner(it) } == true
            override fun loadOwed(key: String): List<String> {
                val arr = adminFloorStore.get(key)?.optJSONArray("addresses") ?: return emptyList()
                return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotEmpty() }
            }
            override fun saveOwed(key: String, addresses: List<String>) {
                if (addresses.isEmpty()) adminFloorStore.remove(key)
                else adminFloorStore.put(key, JSONObject().put("addresses", JSONArray(addresses)))
            }
            override suspend fun sleep(ms: Long) = delay(ms)
        })
    }

    /** Owed rotations of the gated channels this account owns, taken up on a bridge connect. */
    fun resumeOwedRotations() = rotations.resume(
        _channels.value.filter { it.type == "gated" && amOwner(it) }.map { it.messageStreamId })

    /** Change the stored record as it is now, not a copy captured before a slow call. */
    private fun updateStored(messageStreamId: String, change: (Channel) -> Channel) {
        val latest = _channels.value.find { it.messageStreamId == messageStreamId } ?: return
        val updated = change(latest)
        _channels.value = _channels.value.map { if (it.messageStreamId == messageStreamId) updated else it }
        manager.saveChannels()
        if (_current.value?.messageStreamId == messageStreamId) _current.value = updated
    }

    internal val _pins = MutableStateFlow<List<Pin>>(emptyList())

    // What the UI renders: the owner's snapshot with the moderators' pending
    // deltas already folded in. The snapshot itself is kept apart because a
    // publish must build on the owner's own word, never on the composition.
    internal val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())
    internal val _bannedMembers = MutableStateFlow<Set<String>>(emptySet())
    /** address -> epoch the ban starts from; null hides everything. */
    internal val _banSince = MutableStateFlow<Map<String, Int?>>(emptyMap())

    private var snapHidden: Set<String> = emptySet()
    private var snapBanned: Map<String, Int?> = emptyMap()
    private var absorbedThrough: Long = 0L

    /** Deltas held for the OPEN channel, keyed to drop duplicates. */
    internal val deltas = LinkedHashMap<String, JSONObject>()
    /** Signers already answered by the gate, and which of them moderate. */
    private val deltaSignersAsked = HashSet<String>()
    private val deltaModerators = HashSet<String>()
    /** Signers the gate has answered for, either way. */
    private val deltaSignersSettled = HashSet<String>()
    /**
     * Last ADMIN_STATE revision applied, keyed by admin stream — NOT a single
     * counter. Revisions are per channel, so one shared field let a late
     * ADMIN_STATE from the channel the user just left raise the bar for the
     * channel now open: with A at rev 40 arriving after B opened, B's own rev 3
     * was silently discarded for the rest of the session (no pins, no bans, no
     * error), and moderating B would then publish at rev 41 and corrupt the
     * revision sequence for every other participant.
     *
     * Keyed, the value survives across opens, which is also more correct than
     * the old reset-to-zero: revisions only ever move forward for a channel.
     */
    internal val adminRevs = HashMap<String, Int>()

    /** Snapshot timestamps beside the revs — the web's latest-wins compares
     *  (rev, ts), so a stale snapshot sharing a rev must not win (M-C2). */
    internal val adminTs = HashMap<String, Long>()

    /** Admin streams whose history was scanned at least once this session —
     *  publishing a new rev before that would restart from rev=1 and lose to
     *  every peer holding a higher one (M-C1; web gates on adminLoaded). */
    internal val adminLoaded = java.util.Collections.synchronizedSet(HashSet<String>())
    internal var adminPollJob: Job? = null
    /**
     * Candidate membership read straight from the gate: the local cache, the
     * KEY_REQUEST authors seen on -4 and the -4/P1 roster, each answered by
     * the contract. Empty on failure — every caller decides its own fallback.
     */
    suspend fun gateMemberFlags(): List<GateMemberFlags> {
        val channel = _current.value?.takeIf { it.type == "gated" } ?: return emptyList()
        val gate = channel.gateAddress ?: return emptyList()
        // Roster (-4/P1) is the persistent, device-independent candidate
        // source; seenRequesters stays as the fallback for channels created
        // before the roster partition existed.
        val roster = try {
            val keysId = channel.keysStreamId.ifEmpty {
                StreamConstants.deriveKeysId(channel.messageStreamId)
            }
            epochKeys.rosterMembers(channel.messageStreamId, keysId).map { it.account }
        } catch (e: Exception) { emptyList() }
        // knownBanned: the ban drops them from `members` and the roster stops
        // carrying them, so without it a banned address falls out of the
        // candidate set and Moderation loses the entry it exists to show.
        // On Closed gates the contract's own enumeration completes the set —
        // candidates no longer depend on what this client happened to see.
        val onChain = try {
            val info = bridge.call("gateInfo", JSONObject().put("gate", gate))
            if (info.optInt("mode", -1) == GATE_MODE_NONE) {
                val res = bridge.call("gateListMembers", JSONObject().put("gate", gate), 60_000)
                val arr = res.optJSONArray("members")
                (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it)?.ifEmpty { null } }
            } else emptyList()
        } catch (e: Exception) { emptyList() }
        val candidates = (channel.members + channel.knownBanned +
            epochKeys.seenRequesters(channel.messageStreamId) + roster + onChain)
            .map { it.lowercase() }.distinct()
        return try {
            val res = bridge.call("gateMembers", JSONObject()
                .put("gate", gate)
                .put("candidates", JSONArray(candidates)), 60_000)
            val arr = res.optJSONArray("members") ?: return emptyList()
            val out = mutableListOf<GateMemberFlags>()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val addr = m.optString("address").ifEmpty { null } ?: continue
                out.add(GateMemberFlags(
                    address = addr,
                    isOwner = m.optBoolean("isOwner"),
                    moderator = m.optBoolean("moderator"),
                    access = m.optBoolean("access"),
                    banned = m.optBoolean("banned"),
                    allowed = m.optBoolean("allowed"),
                    paidUntil = m.optLong("paidUntil", 0L)
                ))
            }
            rememberBanned(channel, out)
            out
        } catch (e: Exception) {
            Log.w(TAG, "gateMembers failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Remember every banned address the gate reports, so it stays a candidate
     * once the roster and the members cache have let go of it. Self-healing:
     * bans made before this record existed stick the first time they are seen.
     */
    private fun rememberBanned(channel: Channel, flags: List<GateMemberFlags>) {
        val known = channel.knownBanned.map { it.lowercase() }.toSet()
        val fresh = flags.filter { it.banned }
            .map { it.address.lowercase() }
            .filterNot { it in known }
        if (fresh.isEmpty()) return
        val updated = _channels.value.find { it.messageStreamId == channel.messageStreamId }
            ?.let { it.copy(knownBanned = (it.knownBanned + fresh).distinct()) } ?: return
        _channels.value = _channels.value.map {
            if (it.messageStreamId == updated.messageStreamId) updated else it
        }
        manager.saveChannels()
        if (_current.value?.messageStreamId == updated.messageStreamId) _current.value = updated
    }

    /** Addresses the GATE has banned (Moderation panel's protocol-level list). */
    suspend fun gateBannedMembers(): List<String> =
        gateMemberFlags().filter { it.banned }.map { it.address }

    /**
     * Rotate the epoch for anyone who LOST access since the last sweep —
     * bans made while the admin was away, expired PAID subscriptions, sold
     * tokens/NFTs, Closed revokes.
     *
     * Only the channel admin can announce an epoch, so a cut elsewhere leaves
     * the ex-member holding the current key until an admin shows up. The
     * flags read is the one the members panel already makes; comparing it
     * with the previous sweep's snapshot closes the window on the admin's
     * next open. No event scan: free RPCs cap eth_getLogs at 10k blocks.
     *
     * Two triggers, deliberately different: a ban rotates even without a
     * snapshot (explicit intent); anything else only when the address was in
     * the last snapshot WITH access. A candidate who never had access
     * (refused requester) never triggers.
     */
    internal suspend fun rotateForLostAccess(channel: Channel) {
        if (channel.type != "gated" || channel.gateAddress == null) return
        val me = myAddress()?.lowercase() ?: return
        if (me != channel.messageStreamId.substringBefore('/').lowercase()) return
        // A preview has no stored record, and a Join during the gate read
        // below would be overwritten by the preview's copy.
        if (_channels.value.none { it.messageStreamId == channel.messageStreamId }) return
        // The retry owns the channel's rotation until it goes out.
        if (rotations.isOwed(channel.messageStreamId)) return

        val flags = try { gateMemberFlags() } catch (e: Exception) { return }
        if (flags.isEmpty()) return   // unreadable gate — judge nothing
        val stored = _channels.value.find { it.messageStreamId == channel.messageStreamId } ?: return

        val withAccess = flags.filter { it.access }.map { it.address.lowercase() }.toSet()
        val noAccessNow = flags.filter { !it.access && !it.isOwner }.map { it.address.lowercase() }
        val bannedNow = flags.filter { it.banned }.map { it.address.lowercase() }
        val previously = stored.accessSnapshot.map { it.lowercase() }.toSet()

        // Regained access clears the cover, so losing it AGAIN rotates again.
        val covered = stored.rotatedForNoAccess.map { it.lowercase() }
            .filterNot { it in withAccess }.toSet()

        val pending = (noAccessNow.filter { it in previously } + bannedNow)
            .distinct().filterNot { it in covered }

        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        try {
            if (pending.isNotEmpty()) {
                epochKeys.rotateEpoch(channel.messageStreamId, keysId)
                Log.i(TAG, "Rotated the epoch for lost access (${pending.size} address(es))")
            }
            val latest = _channels.value.find { it.messageStreamId == channel.messageStreamId } ?: return
            val cover = covered + pending
            // Runs on every admin open, and each save schedules a full sync push.
            if (latest.rotatedForNoAccess.map { it.lowercase() }.toSet() == cover &&
                latest.accessSnapshot.map { it.lowercase() }.toSet() == withAccess) return
            val updated = latest.copy(
                rotatedForNoAccess = cover.toList(),
                accessSnapshot = withAccess.toList())
            _channels.value = _channels.value.map {
                if (it.messageStreamId == updated.messageStreamId) updated else it
            }
            manager.saveChannels()
            if (_current.value?.messageStreamId == updated.messageStreamId) _current.value = updated
        } catch (e: Exception) {
            Log.w(TAG, "Deferred rotation for lost access failed (will retry next open): ${e.message}")
        }
    }

    suspend fun channelMembers(): List<MemberRow> {
        val channel = _current.value ?: return emptyList()
        if (channel.type == "gated") {
            if (channel.gateAddress == null) return channel.members.map { MemberRow(it) }
            val flags = gateMemberFlags()
            if (flags.isEmpty()) return channel.members.map { MemberRow(it) }
            return flags
                .filter { it.isOwner || it.moderator || it.access }   // banned/ex-members
                .map { MemberRow(it.address, it.paidUntil, it.isOwner, it.moderator) }
        }
        val owner = channelOwner(channel)
        val members = com.pombo.android.core.GraphApi.streamMembers(channel.messageStreamId)
        return (listOfNotNull(owner) + members.filter { !it.equals(owner, ignoreCase = true) })
            .distinct().map { MemberRow(it) }
    }

    /**
     * May this account manage the open gated channel's membership? The gate's
     * owner or one of its moderators (web: canAddMembers). Stream permissions
     * cannot answer it: a moderator holds none, every grant is the clone's.
     * Fail-closed, cached per (gate, account) for the session.
     */
    suspend fun canManageGate(): Boolean {
        val channel = _current.value?.takeIf { it.type == "gated" } ?: return false
        val gate = channel.gateAddress ?: return false
        val me = myAddress()?.lowercase() ?: return false
        gateManageCache["$gate:$me"]?.let { return it }
        val allowed = try {
            val res = bridge.call("gateMembers", JSONObject()
                .put("gate", gate)
                .put("candidates", JSONArray(listOf(me))), 30_000)
            val arr = res.optJSONArray("members")
            var ok = false
            if (arr != null) for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                if (!m.optString("address").equals(me, ignoreCase = true)) continue
                ok = m.optBoolean("isOwner") || m.optBoolean("moderator")
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "canManageGate failed (fail-closed): ${e.message}")
            return false
        }
        gateManageCache["$gate:$me"] = allowed
        return allowed
    }

    private val gateManageCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Channel Details access line for the CURRENT channel's gate (N-D):
     * NONE 'Verified Membership' · TOKEN 'Gated · ≥ N SYM' · NFT
     * 'Gated · SYM NFT' · PAID 'N SYM / D days'. Null = not gated or
     * unreadable — the caller keeps its default label.
     */
    suspend fun gateAccessLabel(): String? {
        val channel = _current.value?.takeIf { it.type == "gated" } ?: return null
        val gate = channel.gateAddress ?: return null
        return try {
            val info = bridge.call("gateInfo", JSONObject().put("gate", gate))
            val mode = info.optInt("mode", GATE_MODE_NONE)
            if (mode == GATE_MODE_NONE) return "Verified Membership"
            val meta = bridge.call("gateTokenMeta", JSONObject().put("token", info.optString("token")))
            val symbol = meta.optString("symbol")
            val decimals = if (meta.isNull("decimals")) 0 else meta.optInt("decimals")
            fun fmt(raw: String) = java.math.BigDecimal(raw)
                .movePointLeft(decimals).stripTrailingZeros().toPlainString()
            when (mode) {
                GATE_MODE_TOKEN -> "Gated · Hold ≥ ${fmt(info.optString("minBalance", "0"))} $symbol"
                GATE_MODE_NFT -> "Gated · Hold $symbol NFT"
                GATE_MODE_PAID -> {
                    val days = (info.optString("duration", "0").toLongOrNull() ?: 0L) / 86_400.0
                    val d = if (days == days.toLong().toDouble()) days.toLong().toString()
                        else "%.1f".format(days)
                    // WPOL-priced gates display POL: gatePay auto-wraps, so
                    // plain POL is literally what the subscriber spends.
                    val paySymbol = if (info.optString("token").lowercase() == WRAPPED_NATIVE) "POL" else symbol
                    "Paid · ${fmt(info.optString("price", "0"))} $paySymbol / $d ${if (d == "1") "day" else "days"}"
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }
    suspend fun gateCardInfo(gateAddress: String): GateCardInfo? = try {
        val info = bridge.call("gateInfo", JSONObject().put("gate", gateAddress))
        val mode = info.optInt("mode", GATE_MODE_NONE)
        if (mode == GATE_MODE_NONE) GateCardInfo(mode, null, null, null)
        else {
            val meta = bridge.call("gateTokenMeta", JSONObject().put("token", info.optString("token")))
            val symbol = meta.optString("symbol")
            val decimals = if (meta.isNull("decimals")) 0 else meta.optInt("decimals")
            fun fmt(raw: String) = java.math.BigDecimal(raw)
                .movePointLeft(decimals).stripTrailingZeros().toPlainString()
            when (mode) {
                GATE_MODE_TOKEN -> GateCardInfo(
                    mode, "Hold", "${fmt(info.optString("minBalance", "0"))} $symbol", "in your wallet")
                GATE_MODE_NFT -> GateCardInfo(mode, "Hold", "$symbol NFT", "in your wallet")
                GATE_MODE_PAID -> {
                    val days = (info.optString("duration", "0").toLongOrNull() ?: 0L) / 86_400.0
                    val d = if (days == days.toLong().toDouble()) days.toLong().toString()
                        else "%.1f".format(days)
                    val sym = if (info.optString("token").lowercase() == WRAPPED_NATIVE) "POL" else symbol
                    // "per" spells out the recurrence under SUBSCRIBE
                    GateCardInfo(mode, "Subscribe",
                        "${fmt(info.optString("price", "0"))} $sym",
                        if (d == "1") "per day" else "per $d days")
                }
                else -> GateCardInfo(mode, null, null, null)
            }
        }
    } catch (e: Exception) { null }

    /** Gate mode of the CURRENT channel; null = not gated or unreadable. */
    suspend fun currentGateMode(): Int? {
        val channel = _current.value?.takeIf { it.type == "gated" } ?: return null
        val gate = channel.gateAddress ?: return null
        return try {
            bridge.call("gateInfo", JSONObject().put("gate", gate))
                .optInt("mode", -1).takeIf { it >= 0 }
        } catch (e: Exception) { null }
    }
    suspend fun gateEntryInfo(gateAddress: String): GateEntryInfo {
        val info = bridge.call("gateInfo", JSONObject().put("gate", gateAddress))
        val mode = info.optInt("mode", GATE_MODE_NONE)
        val token = info.optString("token")
        val me = myAddress() ?: throw IllegalStateException("No identity")
        var symbol = ""
        var decimals: Int? = null
        var balance = "0"
        var paidUntil = 0L
        if (mode != GATE_MODE_NONE && token.isNotEmpty()) {
            val meta = bridge.call("gateTokenMeta", JSONObject().put("token", token))
            symbol = meta.optString("symbol")
            decimals = if (meta.isNull("decimals")) null else meta.optInt("decimals")
            if (mode == GATE_MODE_PAID) {
                paidUntil = bridge.call("gatePaidUntil", JSONObject()
                    .put("gate", gateAddress).put("user", me))
                    .optString("paidUntil", "0").toLongOrNull() ?: 0L
            } else {
                balance = bridge.call("gateTokenBalance", JSONObject()
                    .put("token", token).put("user", me)).optString("balance", "0")
            }
        }
        return GateEntryInfo(
            gateAddress.lowercase(), mode, token,
            info.optString("minBalance", "0"), info.optString("price", "0"),
            info.optString("duration", "0").toLongOrNull() ?: 0L,
            symbol, decimals, balance, paidUntil)
    }

    /** Creation-form helper: token metadata (also probes the contract). */
    suspend fun gateTokenMeta(token: String): Pair<String, Int?> {
        val meta = bridge.call("gateTokenMeta", JSONObject().put("token", token))
        return Pair(meta.optString("symbol"),
            if (meta.isNull("decimals")) null else meta.optInt("decimals"))
    }

    /**
     * Creation-form probe: an address without a working balanceOf would mint
     * a gate that fails checkAccess for everyone, forever. Throws on failure.
     */
    suspend fun gateTokenBalance(token: String, user: String? = null): String {
        val who = user ?: myAddress() ?: throw IllegalStateException("No identity")
        return bridge.call("gateTokenBalance", JSONObject()
            .put("token", token).put("user", who)).optString("balance", "0")
    }

    /** Drop the bridge's cached (fail-closed) access verdicts for a gate. */
    suspend fun gateInvalidateAccess(gateAddress: String) {
        runCatching {
            bridge.call("gateInvalidateAccess", JSONObject().put("gate", gateAddress))
        }
    }

    /**
     * PAID gates: pay one subscription period (wrap/approve/pay inside the
     * bridge call — the deny cache for the payer clears with the tx).
     */
    suspend fun gatePay(gateAddress: String) {
        bridge.call("gatePay", JSONObject().put("gate", gateAddress), 600_000)
    }

    /**
     * Resolves a member input to a 0x address: passes a raw address through,
     * otherwise treats it as an ENS name and resolves it forward (name→address).
     * Lets the Members panel accept "pombo.eth" as well as a raw address.
     */
    suspend fun resolveMemberInput(input: String): String? {
        val t = input.trim()
        if (Regex("^0x[a-fA-F0-9]{40}$").matches(t)) return t
        if (!t.contains('.')) return null
        return try {
            bridge.call("resolveEnsName", JSONObject().put("name", t), 20_000)
                .optString("address").takeIf { Regex("^0x[a-fA-F0-9]{40}$").matches(it) }
        } catch (e: Exception) { null }
    }

    /**
     * Grants a member access on the streams that need a grant — sequential,
     * because parallel on-chain writes from one account collide on the nonce.
     * Admin stream is subscribe-only: members read moderation, owner writes it.
     */
    suspend fun addMember(address: String) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (channel.type != "gated" && !amOwner(channel)) throw IllegalStateException("Only the channel admin can add members")
        // Accept an ENS name or a raw address.
        val addr = resolveMemberInput(address)
            ?: throw IllegalStateException("Invalid address or ENS name")
        if (channel.members.any { it.equals(addr, ignoreCase = true) }) {
            throw IllegalStateException("Address is already a member")
        }

        // Gated (N-C): membership is ONE gate transaction — allow() on the
        // Closed gate. No stream grants: access is proven per-message via
        // ERC-1271.
        if (channel.type == "gated") {
            val gate = channel.gateAddress
                ?: throw IllegalStateException("Gate address unknown (repair pending)")
            bridge.call("gateAllow", JSONObject().put("gate", gate).put("user", addr), 180_000)
            val updated = channel.copy(members = channel.members + addr)
            _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
            manager.saveChannels()
            _current.value = updated
            answerWaitingRequests(updated)
            return
        }

        // `-1`, `-2` and `-4`: subscribe + publish (messages, presence, and
        // the keys stream needs publish so the member can answer KEY_REQUESTs).
        // `-3` (moderation): subscribe ONLY — a normal member reads the admin
        // state but never writes it; publishing ADMIN_STATE is the owner's alone
        // (web addMember does the same).
        val rw = JSONArray().put(
            JSONObject().put("userId", addr).put("permissions", JSONArray(listOf("subscribe", "publish")))
        )
        val readOnly = JSONArray().put(
            JSONObject().put("userId", addr).put("permissions", JSONArray(listOf("subscribe")))
        )
        setPermissionsRetry(channel.messageStreamId, rw)
        setPermissionsRetry(channel.ephemeralStreamId, rw)
        if (channel.adminStreamId.isNotEmpty()) setPermissionsRetry(channel.adminStreamId, readOnly)
        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        setPermissionsRetry(keysId, rw)

        com.pombo.android.core.GraphApi.clearCache()
        val updated = channel.copy(members = channel.members + addr)
        _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
        manager.saveChannels()
        _current.value = updated
    }

    /**
     * Revokes all permissions (web: empty permission array = revoke).
     * @return false when the key rotation that follows is still owed
     */
    suspend fun removeMember(address: String): Boolean {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (channel.type != "gated" && !amOwner(channel)) throw IllegalStateException("Only the channel admin can remove members")
        val addr = address.trim()
        // The creator owns the streams on-chain; removing them is meaningless
        // and would only strip their explicit grants.
        if (channelOwner(channel)?.equals(addr, ignoreCase = true) == true) {
            throw IllegalStateException("Cannot remove the channel creator")
        }

        // Gated: removing takes the address off the allowlist WITHOUT the ban
        // mark, so re-adding later is a plain allow(). The single gate cuts
        // their transport at ingest, the rotation below cuts their reads, and
        // their history stays readable in Pombo clients because reads validate
        // at ingest and never revalidate. Only Closed gates have an allowlist:
        // elsewhere membership is the asset or the subscription, and Ban is
        // the only way to cut it.
        if (channel.type == "gated") {
            val gate = channel.gateAddress
                ?: throw IllegalStateException("Gate address unknown (repair pending)")
            check(currentGateMode() == GATE_MODE_NONE) {
                "Only Closed channels have an allowlist to remove from — use Ban instead"
            }
            bridge.call("gateRevokeAllow", JSONObject()
                .put("gate", gate).put("user", addr), 180_000)
            updateStored(channel.messageStreamId) { stored ->
                stored.copy(members = stored.members.filterNot { it.equals(addr, ignoreCase = true) })
            }
            gateManageCache.clear()
            return rotations.rotateFor(channel.messageStreamId, listOf(addr))
        }

        val revoke = JSONArray().put(
            JSONObject().put("userId", addr).put("permissions", JSONArray())
        )
        setPermissionsRetry(channel.messageStreamId, revoke)
        setPermissionsRetry(channel.ephemeralStreamId, revoke)
        if (channel.adminStreamId.isNotEmpty()) setPermissionsRetry(channel.adminStreamId, revoke)
        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        setPermissionsRetry(keysId, revoke)

        com.pombo.android.core.GraphApi.clearCache()
        val updated = channel.copy(members = channel.members.filterNot { it.equals(addr, ignoreCase = true) })
        _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
        manager.saveChannels()
        _current.value = updated

        // Rotate the epoch so the removed member cannot read anything published
        // from here on — they keep what they already read; the rotation protects
        // the future, not the past. Failure is surfaced, not fatal.
        try {
            epochKeys.rotateEpoch(channel.messageStreamId, keysId)
        } catch (e: Exception) {
            Log.w(TAG, "Epoch rotation after member removal FAILED — removed member can still read new messages until the next rotation: ${e.message}")
        }
        return true
    }

    /**
     * Manual epoch rotation (§3.5): issues a new channel key now. Free — no
     * transaction — unlike [rekeyPublishKey]. Admin only: nobody else's
     * announce is accepted on -4.
     */
    suspend fun rotateEpochManual() {
        val channel = _current.value?.takeIf { it.type == "gated" }
            ?: throw IllegalStateException("No gated channel open")
        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        epochKeys.rotateEpoch(channel.messageStreamId, keysId)
    }

    /** When the weekly rotation falls due for the open channel, or null. */
    suspend fun nextRotationAt(): Long? {
        val channel = _current.value?.takeIf { it.type == "gated" } ?: return null
        return epochKeys.nextRotationAt(channel.messageStreamId)
    }

    /**
     * Replaces the shared publish key of a Sealed channel: grants the
     * new key's address and revokes the old one on `-1`/`-2` (one transaction
     * per stream), then announces the new key on `-4`. Members pick it up
     * through the normal PUB_WRAP flow.
     */
    suspend fun rekeyPublishKey(): Int {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        check(channel.type == "gated" && channel.wireIdentity == "sealed") {
            "the publish key only exists on Sealed channels"
        }
        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        return epochKeys.rekeyPublishKey(channel.messageStreamId, keysId) { newAddress, oldAddress ->
            val assignments = JSONArray().apply {
                // PUBLISH alone: the shared key writes, the clone reads.
                put(JSONObject().put("userId", newAddress)
                    .put("permissions", JSONArray(listOf("publish"))))
                if (oldAddress != null) put(JSONObject().put("userId", oldAddress)
                    .put("permissions", JSONArray()))
            }
            setPermissionsRetry(channel.messageStreamId, assignments)
            setPermissionsRetry(channel.ephemeralStreamId, assignments)
        }
    }

    /** Replaces the interactions key of a Sealed channel: `-5` and `-2` in one transaction. */
    suspend fun rekeyInteractionsKey(): Int {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        check(channel.type == "gated" && channel.wireIdentity == "sealed") {
            "the interactions key only exists on Sealed channels"
        }
        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        val interactionsId = channel.interactionsStreamId
            .ifEmpty { StreamConstants.deriveInteractionsId(channel.messageStreamId) }
        return epochKeys.rekeyInteractionsKey(channel.messageStreamId, keysId) { newAddress, oldAddress ->
            val assignments = JSONArray().apply {
                put(JSONObject().put("userId", newAddress)
                    .put("permissions", JSONArray(listOf("publish"))))
                if (oldAddress != null) put(JSONObject().put("userId", oldAddress)
                    .put("permissions", JSONArray()))
            }
            setPermissionsRetry(listOf(interactionsId, channel.ephemeralStreamId), assignments)
        }
    }

    /**
     * The on-chain permission matrix for the channel's message stream (web:
     * graphAPI.getStreamPermissions), for the Members panel's Stream Permissions
     * list. Owner-only surface, so no permission gate here — the caller shows it.
     */
    suspend fun streamPermissions(): List<com.pombo.android.core.GraphApi.StreamPermission> {
        val channel = _current.value ?: return emptyList()
        return com.pombo.android.core.GraphApi.getStreamPermissions(channel.messageStreamId)
    }

    /**
     * Grants or revokes admin for a member (Members panel "Admin" toggle).
     *
     * ADMIN = TRUSTED CO-OWNER. Streamr's GRANT permission is all-or-nothing:
     * anyone with `canGrant` can set ANY of the five flags (edit, delete,
     * publish, subscribe, grant) for ANY user, including themselves — there is
     * no "manage read/write only" permission on-chain. So an admin can already
     * escalate to full owner. Given that, we grant admin the full non-owner set
     * — subscribe + publish + grant on ALL THREE streams — and only withhold the
     * two flags that define ownership: EDIT and DELETE. Those stay the owner's,
     * which is the sole on-chain line left between admin and owner
     * (isOwner = canGrant && canEdit && canDelete).
     *
     * Revoking returns the member to the normal set: sub+pub on `-1`/`-2`, sub
     * on `-3` (they read moderation state but do not publish it).
     */
    suspend fun setMemberGrant(address: String, canGrant: Boolean) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can change permissions")
        val addr = address.trim()
        // Gated (N-C): "can add members" is the gate's moderator flag — one
        // owner transaction; the contract enforces the rest (mods manage
        // members, never erase history, never touch owner/other mods).
        if (channel.type == "gated") {
            val gate = channel.gateAddress
                ?: throw IllegalStateException("Gate address unknown (repair pending)")
            bridge.call("gateSetModerator", JSONObject()
                .put("gate", gate).put("user", addr).put("enabled", canGrant), 180_000)
            return
        }
        fun assign(perms: List<String>) = JSONArray().put(
            JSONObject().put("userId", addr).put("permissions", JSONArray(perms))
        )
        if (canGrant) {
            // Admin: subscribe + publish + grant on every stream. Never edit/delete.
            val all = listOf("subscribe", "publish", "grant")
            setPermissionsRetry(channel.messageStreamId, assign(all))
            setPermissionsRetry(channel.ephemeralStreamId, assign(all))
            if (channel.adminStreamId.isNotEmpty()) setPermissionsRetry(channel.adminStreamId, assign(all))
        } else {
            // Back to a normal member: sub+pub on -1/-2, sub only on -3.
            val rw = listOf("subscribe", "publish")
            setPermissionsRetry(channel.messageStreamId, assign(rw))
            setPermissionsRetry(channel.ephemeralStreamId, assign(rw))
            if (channel.adminStreamId.isNotEmpty()) setPermissionsRetry(channel.adminStreamId, assign(listOf("subscribe")))
        }
        com.pombo.android.core.GraphApi.clearCache()
    }
    /**
     * Channel owner. Falls back to the address that prefixes the stream ID
     * (`0xowner/path`), which Streamr guarantees — the web does the same when
     * `createdBy` is unknown, e.g. for channels joined from Explore.
     */
    private fun channelOwner(channel: Channel): String? =
        channel.createdBy?.lowercase()
            ?: channel.messageStreamId.substringBefore('/', "").lowercase().ifEmpty { null }

    fun amOwner(channel: Channel): Boolean =
        channelOwner(channel)?.equals(myAddress(), ignoreCase = true) == true
    internal val _perms = MutableStateFlow(ChannelPerms())
    /** streamId -> (address that was checked, verdict). */
    private val permCache = HashMap<String, Pair<String, ChannelPerms>>()

    /** The stream lives under this account's own address, so it created it. */
    internal fun namespaceOwner(streamId: String, address: String?): Boolean {
        val ns = streamId.substringBefore('/', "")
        return ns.isNotEmpty() && ns.equals(address, ignoreCase = true)
    }

    internal fun refreshModerationPermission(channel: Channel, preview: Boolean) {
        val me = myAddress()?.lowercase()
        // A DM has no moderation surface, and a preview is read-only until the
        // user joins — the web zeroes both cases before it even asks.
        if (me == null || preview || channel.type == "dm") {
            _perms.value = ChannelPerms()
            return
        }
        val key = channel.messageStreamId
        // Serve the cache only when it was filled by THIS account: switching
        // accounts must not inherit the previous one's verdict.
        permCache[key]?.let { (addr, cached) ->
            if (addr == me) { _perms.value = cached; return }
        }
        _perms.value = ChannelPerms()
        scope.launch {
            // A moderator holds nothing on the stream, so this is a separate
            // read against the gate.
            val moderatesGate = channel.gateAddress?.let { gate ->
                try {
                    bridge.call("gateIsModerator", JSONObject()
                        .put("gate", gate).put("user", me), 30_000)
                        .optBoolean("moderator", false)
                } catch (e: Exception) { false }
            } ?: false
            // null = the read did not answer, which is not the same as "holds
            // nothing": caching that would hide the owner's own surfaces for
            // the rest of the session on one RPC miss (web
            // Membership.preloadDeletePermission takes the same care).
            val verdict: ChannelPerms? = if (namespaceOwner(key, me)) {
                // Nobody else can create a stream under my address, so these
                // are mine by construction — no RPC, and no way for a flaky
                // one to lock the owner out of their own channel (web
                // streamr.js hasDeletePermission short-circuits the same way).
                ChannelPerms(
                    canPublish = true, canGrant = true, canEdit = true,
                    canDelete = true, moderatesGate = moderatesGate
                )
            } else try {
                val r = bridge.call("checkPermissions", JSONObject().put("streamId", key), 30_000)
                ChannelPerms(
                    canPublish = r.optBoolean("canPublish", false),
                    canGrant = r.optBoolean("canGrant", false),
                    canEdit = r.optBoolean("canEdit", false),
                    canDelete = r.optBoolean("canDelete", false),
                    moderatesGate = moderatesGate
                )
            } catch (e: Exception) {
                // Fail closed for THIS render: offering actions we cannot
                // perform is worse than hiding actions the user might have.
                // The next open asks again.
                Log.w(TAG, "Permission check failed for $key: ${e.message}")
                null
            }
            if (verdict == null) return@launch
            permCache[key] = me to verdict
            // Only apply if this channel is still the open one — a fast switch
            // must not stamp the previous channel's verdict onto the new one.
            if (_current.value?.messageStreamId == key) _perms.value = verdict
        }
    }

    private fun floorKey(channel: Channel) =
        "${(myAddress() ?: "").lowercase()}|${channel.adminStreamId}"

    private fun applySnapshotState(state: JSONObject) {
        state.optJSONArray("hiddenMessageIds")?.let { arr ->
            snapHidden = (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }.toSet()
        }
        state.optJSONArray("bannedMembers")?.let { arr ->
            snapBanned = ModComposition.bannedFromJson(arr)
        }
        absorbedThrough = state.optLong("absorbedThrough", 0L)
        state.optJSONArray("pins")?.let { _pins.value = pinsFromJson(it) }
    }

    // Seed the open channel from the persisted floor before the bootstrap
    // resend, so the (rev, ts) check then refuses anything older.
    private fun seedFloor(channel: Channel) {
        val saved = adminFloorStore.get(floorKey(channel)) ?: return
        val rev = saved.optInt("rev", 0)
        val ts = saved.optLong("ts", 0L)
        val curRev = adminRevs[channel.adminStreamId] ?: 0
        val curTs = adminTs[channel.adminStreamId] ?: 0L
        if (rev < curRev || (rev == curRev && ts <= curTs)) return
        val state = saved.optJSONObject("state") ?: return
        adminRevs[channel.adminStreamId] = rev
        adminTs[channel.adminStreamId] = ts
        applySnapshotState(state)
        recompose()
        adminLoaded.add(channel.adminStreamId)
    }

    private fun persistFloor(channel: Channel, rev: Int, ts: Long, state: JSONObject) {
        adminFloorStore.put(floorKey(channel), JSONObject()
            .put("rev", rev).put("ts", ts).put("state", state))
    }

    internal suspend fun loadAdminState(channel: Channel, generation: Int) {
        seedFloor(channel)
        try {
            val entries = readAdminEntries(channel) ?: return
            if (!stillCurrent(generation)) return
            for ((content, meta) in entries) applyAdminMessage(channel, content, meta, generation)
            // Even an empty history is an answer: the stream holds no
            // snapshot, so rev bookkeeping may start from zero.
            adminLoaded.add(channel.adminStreamId)
        } catch (e: Exception) { /* no admin history */ }
    }

    /**
     * The retained -3/P0 entries the storage node serves, as (content, meta)
     * pairs with epoch envelopes already opened and, on gated, only the
     * owner-signed ones. Null when the read itself brought nothing back.
     */
    private suspend fun readAdminEntries(channel: Channel): List<Pair<Any?, JSONObject>>? {
        // Password channels seal ADMIN_STATE too, and applyAdminMessage's
        // fallback opens it with PomboCrypto — Bouncy Castle PBKDF2, ~1s per
        // message on a phone, up to 5 of them, all inside the render gate.
        // Handing the password to the bridge moves that to BoringSSL and
        // overlaps it with the resend.
        val args = JSONObject()
            .put("streamId", channel.adminStreamId)
            .put("partition", StreamConstants.ADMIN_MODERATION)
            .put("last", 5)
        channel.password?.let { args.put("password", it) }
        // Raw envelopes for gated, same as message history: authority on
        // -3 is the recovered envelope signer, never the present gate.
        if (channel.type == "gated") args.put("recoverSigner", true).put("raw", true)
        val t0 = System.currentTimeMillis()
        val res = bridge.call("resend", args, 30_000)
        android.util.Log.d("PomboPerf",
            "adminState ${channel.name}: call=${System.currentTimeMillis() - t0}ms " +
                "n=${res.optJSONArray("messages")?.length() ?: -1}")
        val arr = res.optJSONArray("messages") ?: return null
        val out = ArrayList<Pair<Any?, JSONObject>>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            var content = entry.opt("content")
            val meta = entry.optJSONObject("meta") ?: JSONObject()
            // Gated: -3 authority moved to ingest — the clone holds the
            // publish grant for everyone, so only the envelope SIGNER
            // proves the admin wrote this (gatedAuthor drops the rest).
            if (channel.type == "gated" &&
                gatedAuthor(channel, channel.adminStreamId, meta) == null) continue
            // ADMIN_STATE arrives as an epoch envelope. History context so
            // entries sealed under an older epoch open in that epoch's
            // validity window instead of skipping the freshness rule.
            if (content is JSONObject &&
                com.pombo.android.core.EpochKeyCrypto.isEpochEnvelope(content) &&
                isEpochChannel(channel)
            ) {
                val keysId = channel.keysStreamId.ifEmpty {
                    StreamConstants.deriveKeysId(channel.messageStreamId)
                }
                content = epochKeys.tryDecrypt(
                    channel.messageStreamId, keysId, content,
                    gated = true, live = false, timestamp = com.pombo.android.core.StoredAt.judgeTime(meta)
                ) ?: continue
            }
            out.add(content to meta)
        }
        return out
    }

    /** The plaintext of a -3 entry: a sealed one opens with the password. */
    private fun openAdminContent(channel: Channel, contentAny: Any?): JSONObject? = when (contentAny) {
        is JSONObject -> contentAny
        is String -> {
            val pwd = channel.password ?: return null
            try { JSONObject(PomboCrypto.decryptString(contentAny, pwd)) } catch (e: Exception) { null }
        }
        else -> null
    }

    /** The newest owner-authored ADMIN_STATE the node serves, by (rev, ts), with its meta. */
    private suspend fun readLatestAdminSnapshot(channel: Channel): Pair<JSONObject, JSONObject>? {
        val owner = channelOwner(channel)
        var best: Pair<JSONObject, JSONObject>? = null
        for ((contentAny, meta) in readAdminEntries(channel) ?: return null) {
            val data = openAdminContent(channel, contentAny) ?: continue
            if (data.optString("type") != "ADMIN_STATE") continue
            val sender = data.optString("account").ifEmpty { meta.optString("publisherId") }.lowercase()
            if (owner != null && sender.isNotEmpty() && sender != owner) continue
            val rev = data.optInt("rev", 0)
            val ts = data.optLong("ts", 0L)
            val b = best?.first
            if (b == null || rev > b.optInt("rev") || (rev == b.optInt("rev") && ts > b.optLong("ts"))) {
                best = data to meta
            }
        }
        return best
    }

    // ---- confirmation that a published ADMIN_STATE reached storage ----
    //
    // The publish goes out over the overlay and the client reports it sent
    // whether or not a storage node heard it: a snapshot published from a
    // cold session can be lost with nothing to show for it, and every other
    // client then keeps reading the previous one. After each publish the -3
    // is read back until the snapshot is there; when it is not by the last
    // delay the current snapshot is republished under the next rev (a
    // snapshot is complete, so the one that lands cures all before it).
    // After CONFIRM_REPUBLISH_LIMIT republishes the owner is told and the
    // entry stays pending, picked up again when the channel is next opened.
    // Only the open channel can republish: the snapshot lives in its flows.

    /** Publishes storage has not confirmed, by admin stream; mirrored in the floor store. */
    private val pendingConfirm = HashMap<String, JSONObject>()
    private var confirmJob: Job? = null
    private var confirmStream: String? = null

    private fun pendingKey(channel: Channel) = "pending|" + floorKey(channel)

    internal fun pendingConfirmationOf(channel: Channel): JSONObject? =
        pendingConfirm[channel.adminStreamId]
            ?: adminFloorStore.get(pendingKey(channel))?.also { pendingConfirm[channel.adminStreamId] = it }

    private fun setPendingConfirmation(channel: Channel, entry: JSONObject?) {
        if (entry == null) {
            pendingConfirm.remove(channel.adminStreamId)
            adminFloorStore.remove(pendingKey(channel))
        } else {
            pendingConfirm[channel.adminStreamId] = entry
            adminFloorStore.put(pendingKey(channel), entry)
        }
    }

    /** A publish just went out: remember it and see it to storage. */
    private fun trackPublished(channel: Channel, rev: Int, ts: Long, envelopeTs: Long) {
        val prev = pendingConfirmationOf(channel)
        setPendingConfirmation(channel, JSONObject()
            .put("rev", rev).put("ts", ts).put("envelopeTs", envelopeTs)
            .put("republished", prev?.optInt("republished") ?: 0)
            .put("since", prev?.optLong("since") ?: System.currentTimeMillis())
            .put("stalled", false))
        ensureConfirmLoop(channel, switchGeneration)
    }

    /**
     * The owner opened a channel whose last publish storage never confirmed:
     * wait for it again, with a fresh allowance of republishes.
     */
    internal fun resumeConfirmation(channel: Channel, generation: Int) {
        if (!amOwner(channel)) return
        val entry = pendingConfirmationOf(channel) ?: return
        setPendingConfirmation(channel, JSONObject(entry.toString()).put("republished", 0).put("stalled", false))
        ensureConfirmLoop(channel, generation)
    }

    private fun ensureConfirmLoop(channel: Channel, generation: Int) {
        if (confirmJob?.isActive == true && confirmStream == channel.adminStreamId) return
        confirmJob?.cancel()
        confirmStream = channel.adminStreamId
        confirmJob = scope.launch { confirmLoop(channel, generation) }
    }

    private enum class Landing { LANDED, SUPERSEDED, REPLACED, MISSING }

    private suspend fun confirmLoop(channel: Channel, generation: Int) {
        while (true) {
            val pending = pendingConfirmationOf(channel) ?: return
            if (pending.optBoolean("stalled")) return
            when (awaitLanding(channel, pending, generation)) {
                // A newer publish of ours took over: wait for that one instead.
                Landing.REPLACED -> continue
                Landing.MISSING -> Unit
                else -> return
            }
            val label = "ADMIN_STATE rev ${pending.optInt("rev")} of ${channel.name}"
            val republished = pending.optInt("republished")
            if (republished >= CONFIRM_REPUBLISH_LIMIT) {
                setPendingConfirmation(channel, JSONObject(pending.toString()).put("stalled", true))
                Log.w(TAG, "$label never reached storage after $republished republishes")
                manager.onModerationWarning?.invoke(
                    "Moderation change not yet confirmed on storage. It will be retried when you open the channel again.")
                return
            }
            // Republishing needs the open channel's snapshot; with another
            // channel on screen this waits for the next open.
            if (!stillCurrent(generation) || channel.adminStreamId != _current.value?.adminStreamId) return
            setPendingConfirmation(channel, JSONObject(pending.toString()).put("republished", republished + 1))
            Log.w(TAG, "$label not on storage, republishing")
            try {
                publishAdminState(channel)
            } catch (e: Exception) {
                Log.w(TAG, "$label republish failed, kept pending: ${e.message}")
                return
            }
        }
    }

    private suspend fun awaitLanding(channel: Channel, pending: JSONObject, generation: Int): Landing {
        val rev = pending.optInt("rev")
        val ts = pending.optLong("ts")
        val label = "ADMIN_STATE rev $rev of ${channel.name}"
        // An admin stream without storage has no node to read back from:
        // nothing to confirm, and nothing to republish.
        val providers = runCatching { manager.storageEndpoints.resolve(channel.adminStreamId) }.getOrNull()
        if (providers != null && providers.isEmpty()) {
            setPendingConfirmation(channel, null)
            Log.d(TAG, "$label: the admin stream has no storage, nothing to confirm")
            return Landing.LANDED
        }
        for (delayMs in CONFIRM_DELAYS_MS) {
            manager.adminConfirmSleep(delayMs)
            val current = pendingConfirmationOf(channel) ?: return Landing.LANDED
            if (current.optInt("rev") != rev) return Landing.REPLACED
            val latest = try { readLatestAdminSnapshot(channel) } catch (e: Exception) { null } ?: continue
            val storedRev = latest.first.optInt("rev")
            val storedTs = latest.first.optLong("ts")
            if (storedRev == rev && storedTs == ts) {
                setPendingConfirmation(channel, null)
                Log.i(TAG, "$label confirmed on storage")
                return Landing.LANDED
            }
            if (storedRev > rev || (storedRev == rev && storedTs > ts)) {
                // Published after ours, from another device of the owner:
                // theirs is the channel's state now.
                setPendingConfirmation(channel, null)
                applyAdminMessage(channel, latest.first, latest.second, generation)
                Log.w(TAG, "$label: storage holds rev $storedRev published later elsewhere, adopted")
                manager.onModerationWarning?.invoke(
                    "Moderation was changed from another device; the last change made here was replaced.")
                return Landing.SUPERSEDED
            }
        }
        return Landing.MISSING
    }

    /**
     * Periodic ADMIN_STATE refresh for the open channel (web adminStatePoller,
     * CONFIG.subscriptions.adminPollIntervalMs). There is no live subscription
     * on -3 — like the web, moderation converges via the on-open load, the
     * admin_invalidate signal on the already-subscribed -2 (instant path) and
     * this poller (safety net). The resend goes straight to the storage node,
     * so no overlay membership is spent on it. Same lifecycle as presence:
     * starts on open, dies on close/switch.
     */
    internal fun startAdminPoller(channel: Channel, generation: Int) {
        adminPollJob?.cancel()
        adminPollJob = scope.launch {
            while (isActive) {
                delay(ADMIN_POLL_INTERVAL_MS)
                if (!stillCurrent(generation)) return@launch
                loadAdminState(channel, generation)
            }
        }
    }
    internal fun applyAdminMessage(
        channel: Channel,
        contentAny: Any?,
        meta: JSONObject,
        generation: Int
    ) {
        // Pins, hidden ids and bans are flat flows describing the OPEN channel,
        // so a late arrival for any other channel must not reach them. Same
        // two independent nets as handleContent: the generation fence, plus a
        // counter-independent check that this is the admin stream on screen.
        if (!stillCurrent(generation)) return
        if (channel.adminStreamId != _current.value?.adminStreamId) return
        val data: JSONObject = when (contentAny) {
            is JSONObject -> contentAny
            is String -> {
                val pwd = channel.password ?: return
                try { JSONObject(PomboCrypto.decryptString(contentAny, pwd)) } catch (e: Exception) { return }
            }
            else -> return
        }
        if (data.optString("type") != "ADMIN_STATE") return
        // Owner-authored only. Authority is the ACCOUNT: on the -3 stream the
        // owner always publishes as the wallet (on-chain permission — an
        // ephemeral key can't), so publisherId still works there; but the
        // admin_invalidate snapshot rides the -2 stream, which will publish
        // under an ephemeral key once step 5 lands — there the proof-resolved
        // `account` (stamped by attachAccount before this is called) is the
        // only field that still names the owner. Web checks data.account too.
        // Latest-wins by (rev, ts) — the timestamp breaks rev ties so a stale
        // replica snapshot sharing a rev cannot overwrite a newer one.
        val senderId = data.optString("account")
            .ifEmpty { meta.optString("publisherId") }.lowercase()
        val owner = channelOwner(channel)
        if (owner != null && senderId.isNotEmpty() && senderId != owner) return
        val rev = data.optInt("rev", 0)
        val ts = data.optLong("ts", 0L)
        val curRev = adminRevs[channel.adminStreamId] ?: 0
        val curTs = adminTs[channel.adminStreamId] ?: 0L
        if (rev < curRev || (rev == curRev && ts < curTs)) return
        adminRevs[channel.adminStreamId] = rev
        adminTs[channel.adminStreamId] = ts
        val state = data.optJSONObject("state") ?: return
        applySnapshotState(state)
        recompose()
        persistFloor(channel, rev, ts, state)
    }

    /** Fold the held deltas onto the snapshot and publish the result to the UI. */
    internal fun recompose() {
        val effective = ModComposition.compose(
            snapHidden, snapBanned, absorbedThrough,
            deltas.values.toList(), deltaModerators)
        _hiddenIds.value = effective.hiddenMessageIds
        _bannedMembers.value = effective.bannedMembers.keys
        _banSince.value = effective.bannedMembers
    }

    /**
     * A MOD_ACTION arrived, live or from history. The signature is checked
     * here; whether the signer still moderates is asked once per signer and
     * recomposes when it answers, so an unknown signer simply does not count
     * until the gate says otherwise.
     */
    internal fun ingestModAction(channel: Channel, payload: JSONObject?): Boolean {
        val signer = ModAction.verify(channel.messageStreamId, payload) ?: return false
        val d = payload!!
        val key = "${d.optLong("ts")}|$signer|${d.optString("op")}|${d.optString("target")}"
        if (deltas.containsKey(key)) return false
        deltas[key] = JSONObject(d.toString()).put("mod", signer)
        resolveDeltaModerator(channel, signer)
        recompose()
        return true
    }

    private fun resolveDeltaModerator(channel: Channel, signer: String) {
        val gate = channel.gateAddress ?: return
        if (channelOwner(channel) == signer) {
            deltaModerators.add(signer)
            deltaSignersSettled.add(signer)
            return
        }
        if (!deltaSignersAsked.add(signer)) return
        scope.launch {
            val isMod = try {
                bridge.call("gateIsModerator", JSONObject()
                    .put("gate", gate).put("user", signer), 30_000)
                    .optBoolean("moderator", false)
            } catch (e: Exception) {
                deltaSignersAsked.remove(signer)
                return@launch
            }
            deltaSignersSettled.add(signer)
            if (!isMod) return@launch
            deltaModerators.add(signer)
            if (_current.value?.messageStreamId == channel.messageStreamId) recompose()
        }
    }

    /** Drop what belongs to the channel being left. */
    internal fun clearDeltas() {
        deltas.clear()
        deltaSignersAsked.clear()
        deltaModerators.clear()
        deltaSignersSettled.clear()
        snapHidden = emptySet()
        snapBanned = emptyMap()
        absorbedThrough = 0L
        _banSince.value = emptyMap()
    }

    /**
     * Publish a delta as a moderator. The owner never takes this path: their
     * snapshot is stronger and needs nobody's ratification.
     */
    suspend fun publishModAction(op: String, target: String, sinceEpoch: Int? = null) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        val priv = manager.myPrivateKey()
            ?: throw IllegalStateException("No wallet available to sign the moderation action")
        val payload = ModAction.build(channel.messageStreamId, op, target, priv, sinceEpoch)
        // Applied locally first, like a sent message: the moderator sees their
        // own action without waiting for the round trip.
        ingestModAction(channel, payload)
        publishForChannel(
            channel, channel.messageStreamId, StreamConstants.P_MODERATION, payload)
    }

    /**
     * Owner only: turn the moderators' deltas into the owner's own snapshot.
     * `absorbedThrough` advances only to what was actually read, so a delta
     * still in flight is never silently reverted.
     */
    suspend fun absorbModActions() {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can confirm")
        val unabsorbed = deltas.values.filter { it.optLong("ts") > absorbedThrough }
        if (unabsorbed.isEmpty()) return
        // Every pending delta needs a settled verdict on its author first.
        // Absorbing while the gate has not answered writes absorbedThrough
        // over a composition that still counts nobody: the ratification
        // lands, the moderation it was ratifying disappears, and the delta
        // stops counting for good.
        val unsettled = unabsorbed.map { it.optString("mod").lowercase() }
            .distinct().filter { it !in deltaSignersSettled }
        if (unsettled.isNotEmpty()) {
            unsettled.forEach { resolveDeltaModerator(channel, it) }
            throw IllegalStateException(
                "Still checking who moderates this channel — try again in a moment")
        }
        val through = unabsorbed.maxOf { it.optLong("ts") }
        snapHidden = _hiddenIds.value
        snapBanned = _banSince.value
        absorbedThrough = through
        publishAdminState(channel)
        recompose()
    }

    /**
     * Moderator actions still waiting for the owner. Absorbing deletes
     * nothing — the -1/P2 is append-only and `absorbedThrough` is what stops a
     * delta counting — so the surface has to measure the unabsorbed ones, or
     * it keeps offering work already done.
     */
    fun pendingModActions(): Int =
        deltas.values.count { it.optLong("ts") > absorbedThrough }

    /**
     * Publishes the full ADMIN_STATE with an incremented rev (owner only).
     * @return the publish timestamp, 0 when there is no account to publish as
     */
    internal suspend fun publishAdminState(channel: Channel): Long {
        val addr = myAddress() ?: return 0L
        // Never compute a rev off an unscanned stream (web gates publish on
        // adminLoaded): moderating fast, before the on-open load finished,
        // published rev=1 over a channel already at rev N — every peer with
        // the higher rev discarded it. Failure proceeds with the stale rev,
        // matching the web's "may publish stale rev" warning path.
        if (channel.adminStreamId !in adminLoaded) {
            runCatching { loadAdminState(channel, switchGeneration) }
        }
        val rev = (adminRevs[channel.adminStreamId] ?: 0) + 1
        // The owner's own word, never the composition: publishing the
        // composed view would silently ratify deltas they never looked at.
        val state = JSONObject()
            .put("bannedMembers", ModComposition.bannedToJson(snapBanned))
            .put("hiddenMessageIds", JSONArray(snapHidden.toList()))
            .put("pins", pinsToJson(_pins.value))
            .put("absorbedThrough", absorbedThrough)
        val msg = JSONObject()
            .put("type", "ADMIN_STATE").put("rev", rev)
            .put("ts", System.currentTimeMillis()).put("createdBy", addr)
            .put("state", state)
        val envelopeTs = publishForChannel(channel, channel.adminStreamId, StreamConstants.ADMIN_MODERATION, msg)
        // Commit the revision only once it is on the wire. Incrementing up
        // front meant a failed publish — which [moderate] rolls back — still
        // burned a revision, so the next attempt skipped a number.
        adminRevs[channel.adminStreamId] = rev
        adminTs[channel.adminStreamId] = msg.optLong("ts")
        // "Published" only means broadcast: the snapshot is read back from
        // storage until it is there, and republished when it is not.
        trackPublished(channel, rev, msg.optLong("ts"), envelopeTs)
        // Low-latency fan-out (web channels.js publishAdminState): nobody —
        // web or Android — subscribes -3 live, so this ephemeral signal with
        // the full snapshot is what makes a ban/pin/hide reach open channels
        // immediately; the 30s pollers are the fallback. Best-effort: the
        // canonical -3 publish above already succeeded.
        try {
            val signal = JSONObject()
                .put("type", "admin_invalidate")
                .put("rev", rev)
                .put("ts", msg.optLong("ts"))
                .put("snapshot", msg)
            publishForChannel(channel, channel.ephemeralStreamId, StreamConstants.EPH_CONTROL, signal)
        } catch (e: Exception) {
            Log.d(TAG, "admin_invalidate publish failed (non-fatal): ${e.message}")
        }
        return envelopeTs
    }

    /**
     * Moderation is applied locally first so the UI reacts instantly, then
     * published as ADMIN_STATE. If the publish fails the optimistic change is
     * rolled back and the error propagates — otherwise this device would show
     * a pin/ban that no one else can see.
     */
    private suspend fun <T> moderate(
        channel: Channel,
        state: MutableStateFlow<T>,
        next: T
    ) {
        val previous = state.value
        state.value = next
        try {
            publishAdminState(channel)
        } catch (e: Exception) {
            state.value = previous
            throw e
        }
    }

    /**
     * Publish an owner snapshot with the given change already applied, rolling
     * the local state back when the publish fails. Same contract as [moderate],
     * for the fields that live outside a flow.
     */
    private suspend fun moderateSnapshot(channel: Channel, apply: () -> Unit) {
        val prevHidden = snapHidden
        val prevBanned = snapBanned
        apply()
        recompose()
        try {
            publishAdminState(channel)
        } catch (e: Exception) {
            snapHidden = prevHidden
            snapBanned = prevBanned
            recompose()
            throw e
        }
    }

    suspend fun hideMessage(messageId: String, hide: Boolean) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        // A moderator has no permission on the admin stream, so their hide
        // travels as a signed delta instead of the owner's snapshot.
        if (!amOwner(channel)) {
            if (!isModeratorHere()) throw IllegalStateException("Only the channel admin can moderate")
            publishModAction(if (hide) "hide" else "unhide", messageId)
            return
        }
        moderateSnapshot(channel) {
            snapHidden = if (hide) snapHidden + messageId else snapHidden - messageId
        }
    }

    suspend fun pinMessage(messageId: String, pin: Boolean) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can moderate")
        val next = if (pin) {
            val msg = _messages.value.find { it.id == messageId }
                ?: throw IllegalStateException("Message not found")
            if (_pins.value.any { it.targetId == messageId }) return
            _pins.value + Pin(
                messageId, msg.text, msg.sender,
                senderName = msg.senderName, ensName = msg.ensName,
                pinnedAt = System.currentTimeMillis()
            )
        } else {
            _pins.value.filterNot { it.targetId == messageId }
        }
        moderate(channel, _pins, next)
    }

    suspend fun banMember(address: String, ban: Boolean = true) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        val addr = address.lowercase()
        // Stamped with the epoch in force, so the ban silences the author from
        // here on instead of erasing what they wrote before it.
        val since = if (ban) epochKeys.currentEpoch(channel.messageStreamId) else null
        if (!amOwner(channel)) {
            if (!isModeratorHere()) throw IllegalStateException("Only the channel admin can moderate")
            publishModAction(if (ban) "ban" else "unban", addr, since)
            return
        }
        moderateSnapshot(channel) {
            snapBanned = if (ban) snapBanned + (addr to since) else snapBanned - addr
        }
        // A ban the owner lifts must beat a moderator's delta that still
        // asserts it, and a delta is only ever overruled by absorption.
        if (!ban && deltas.isNotEmpty()) runCatching { absorbModActions() }
    }

    /** Does this account moderate the open channel's gate (cached read)? */
    private fun isModeratorHere(): Boolean = _perms.value.moderatesGate

    /**
     * The two enforcement levels behind one Ban action.
     *
     * CLIENT is the ADMIN_STATE ban: every client hides the author's messages,
     * free and reversible, and only the creator may publish it. PROTOCOL is
     * the gate ban: `checkAccess` goes false, so no responder hands out keys,
     * the single gate cuts their transport at ingest, and the epoch rotation
     * that follows cuts reads from here on. Costs gas.
     *
     * @return false when the key rotation that follows is still owed
     */
    suspend fun banMemberLevels(address: String, client: Boolean, protocol: Boolean): Boolean {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        val addr = address.trim()
        if (channelOwner(channel)?.equals(addr, ignoreCase = true) == true) {
            throw IllegalStateException("Cannot ban the channel creator")
        }
        var rotated = true
        if (protocol) {
            val gate = channel.gateAddress
                ?: throw IllegalStateException("Only gated channels have a protocol-level ban")
            bridge.call("gateBan", JSONObject()
                .put("gate", gate).put("user", addr), 180_000)
            gateManageCache.clear()
            updateStored(channel.messageStreamId) { stored ->
                stored.copy(
                    members = stored.members.filterNot { it.equals(addr, ignoreCase = true) },
                    knownBanned = (stored.knownBanned + addr.lowercase()).distinct()
                )
            }
            rotated = rotations.rotateFor(channel.messageStreamId, listOf(addr))
        }
        if (client) banMember(addr, true)
        return rotated
    }

    /**
     * Lifts whichever bans the address actually carries: the gate ban costs a
     * transaction, so it is only sent when the contract really has them
     * banned, and the free ADMIN_STATE entry is always cleared alongside.
     */
    suspend fun unbanMemberLevels(address: String) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        val addr = address.trim()
        val bannedOnChain = channel.gateAddress != null &&
            gateBannedMembers().any { it.equals(addr, ignoreCase = true) }
        if (bannedOnChain) {
            bridge.call("gateUnban", JSONObject()
                .put("gate", channel.gateAddress).put("user", addr), 180_000)
            gateManageCache.clear()
            answerWaitingRequests(channel)
        }
        if (_bannedMembers.value.any { it.equals(addr, ignoreCase = true) }) {
            banMember(addr, false)
        }
    }

    /**
     * Answer the key requests storage holds for the channel now. The SDK keeps
     * refusing a just-readmitted member's live requests for up to ten minutes;
     * the stored copies are read raw, past that check.
     */
    private fun answerWaitingRequests(channel: Channel) {
        if (channel.type != "gated") return
        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        scope.launch {
            try {
                epochKeys.ensureChannelKeys(
                    channel.messageStreamId, keysId, ChannelManager.keysRetentionDays(channel),
                    allowMint = false, memberCount = channel.members.size, gated = true)
            } catch (e: Exception) {
                Log.w(TAG, "Answering the stored key requests failed: ${e.message}")
            }
        }
    }
}
