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
    private val store get() = manager.store
    private val scope get() = manager.scope
    private val myAddress get() = manager.myAddress
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
    private suspend fun publishForChannel(
        channel: Channel,
        streamId: String,
        partition: Int,
        payload: JSONObject
    ) = manager.publishForChannel(channel, streamId, partition, payload)

    private companion object {
        private const val TAG = "PomboChannels"
    }

    internal val _pins = MutableStateFlow<List<Pin>>(emptyList())
    internal val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())
    internal val _bannedMembers = MutableStateFlow<Set<String>>(emptySet())
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
        val candidates = (channel.members + channel.knownBanned +
            epochKeys.seenRequesters(channel.messageStreamId) + roster)
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
                    everMember = m.optBoolean("everMember"),
                    erased = m.optBoolean("erased"),
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
        store.save(_channels.value)
        if (_current.value?.messageStreamId == updated.messageStreamId) _current.value = updated
    }

    /** Addresses the GATE has banned (Moderation panel's protocol-level list). */
    suspend fun gateBannedMembers(): List<String> =
        gateMemberFlags().filter { it.banned }.map { it.address }

    /**
     * Rotate the epoch for bans this device never rotated for.
     *
     * Only the channel admin can announce an epoch, so a moderator's ban cuts
     * key distribution immediately but leaves the banned member holding the
     * current key until an admin shows up. Comparing the gate's banned set
     * with the one we last rotated for closes that window on the admin's next
     * open, whoever did the banning and whenever. No event scan: free RPCs cap
     * eth_getLogs at 10k blocks, and the flags read is one we already make.
     */
    internal suspend fun rotateForPendingBans(channel: Channel) {
        if (channel.type != "gated" || channel.gateAddress == null) return
        val me = myAddress()?.lowercase() ?: return
        if (me != channel.messageStreamId.substringBefore('/').lowercase()) return

        val banned = try { gateBannedMembers().map { it.lowercase() } }
            catch (e: Exception) { return }
        if (banned.isEmpty()) return
        val covered = channel.rotatedForBanned.map { it.lowercase() }.toSet()
        if (banned.all { it in covered }) return

        val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
        try {
            epochKeys.rotateEpoch(channel.messageStreamId, keysId)
            val updated = channel.copy(rotatedForBanned = banned)
            _channels.value = _channels.value.map {
                if (it.messageStreamId == updated.messageStreamId) updated else it
            }
            store.save(_channels.value)
            if (_current.value?.messageStreamId == updated.messageStreamId) _current.value = updated
            Log.i(TAG, "Rotated the epoch for bans made while the admin was away")
        } catch (e: Exception) {
            Log.w(TAG, "Deferred rotation for pending bans failed (will retry next open): ${e.message}")
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
     * Grants a member access on all three streams — sequential, because
     * parallel on-chain writes from one account collide on the nonce.
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

        // Gated (N-C): membership is ONE gate transaction — allow() marks the
        // address allowlisted + everMember. No stream grants: access is proven
        // per-message via ERC-1271.
        if (channel.type == "gated") {
            val gate = channel.gateAddress
                ?: throw IllegalStateException("Gate address unknown (repair pending)")
            bridge.call("gateAllow", JSONObject().put("gate", gate).put("user", addr), 180_000)
            val updated = channel.copy(members = channel.members + addr)
            _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
            store.save(_channels.value)
            _current.value = updated
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
        store.save(_channels.value)
        _current.value = updated
    }

    /** Revokes all permissions (web: empty permission array = revoke). */
    suspend fun removeMember(address: String) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (channel.type != "gated" && !amOwner(channel)) throw IllegalStateException("Only the channel admin can remove members")
        val addr = address.trim()
        // The creator owns the streams on-chain; removing them is meaningless
        // and would only strip their explicit grants.
        if (channelOwner(channel)?.equals(addr, ignoreCase = true) == true) {
            throw IllegalStateException("Cannot remove the channel creator")
        }

        // Gated: removing takes the address off the allowlist WITHOUT the ban
        // mark, so re-adding later is a plain allow(). The rotation below cuts
        // their reads, and the contract's sticky isValidSignature keeps their
        // history readable for everyone else (Q10). Only Closed gates have an
        // allowlist: elsewhere membership is the asset or the subscription, and
        // Ban is the only way to cut it.
        if (channel.type == "gated") {
            val gate = channel.gateAddress
                ?: throw IllegalStateException("Gate address unknown (repair pending)")
            check(currentGateMode() == GATE_MODE_NONE) {
                "Only Closed channels have an allowlist to remove from — use Ban instead"
            }
            bridge.call("gateRevokeAllow", JSONObject()
                .put("gate", gate).put("user", addr), 180_000)
            val keysIdGated = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
            val updated = channel.copy(members = channel.members.filterNot { it.equals(addr, ignoreCase = true) })
            _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
            store.save(_channels.value)
            _current.value = updated
            gateManageCache.clear()
            try {
                epochKeys.rotateEpoch(channel.messageStreamId, keysIdGated)
            } catch (e: Exception) {
                Log.w(TAG, "Epoch rotation after removal FAILED — the removed member can still read new messages until the next rotation: ${e.message}")
            }
            return
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
        store.save(_channels.value)
        _current.value = updated

        // Rotate the epoch so the removed member cannot read anything published
        // from here on — they keep what they already read; the rotation protects
        // the future, not the past. Failure is surfaced, not fatal.
        try {
            epochKeys.rotateEpoch(channel.messageStreamId, keysId)
        } catch (e: Exception) {
            Log.w(TAG, "Epoch rotation after member removal FAILED — removed member can still read new messages until the next rotation: ${e.message}")
        }
    }

    /**
     * Replaces the shared publish key of a Members-only channel: grants the
     * new key's address and revokes the old one on `-1`/`-2` (one transaction
     * per stream), then announces the new key on `-4`. Members pick it up
     * through the normal PUB_WRAP flow.
     */
    suspend fun rekeyPublishKey(): Int {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        check(channel.type == "gated" && channel.authorMode == "members") {
            "the publish key only exists on Members-only channels"
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
            val verdict = try {
                val r = bridge.call("checkPermissions", JSONObject().put("streamId", key), 30_000)
                ChannelPerms(
                    canPublish = r.optBoolean("canPublish", false),
                    canGrant = r.optBoolean("canGrant", false),
                    canEdit = r.optBoolean("canEdit", false),
                    canDelete = r.optBoolean("canDelete", false)
                )
            } catch (e: Exception) {
                // Fail closed: offering actions we cannot perform is worse than
                // hiding actions the user might have — the former fails at
                // publish time with nothing to show for it.
                Log.w(TAG, "Permission check failed for $key: ${e.message}")
                ChannelPerms()
            }
            permCache[key] = me to verdict
            // Only apply if this channel is still the open one — a fast switch
            // must not stamp the previous channel's verdict onto the new one.
            if (_current.value?.messageStreamId == key) _perms.value = verdict
        }
    }

    internal suspend fun loadAdminState(channel: Channel, generation: Int) {
        try {
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
            if (!stillCurrent(generation)) return
            val arr = res.optJSONArray("messages") ?: return
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
                        gated = true, live = false, timestamp = meta.optLong("timestamp", 0L)
                    ) ?: continue
                }
                applyAdminMessage(channel, content, meta, generation)
            }
            // Even an empty history is an answer: the stream holds no
            // snapshot, so rev bookkeeping may start from zero.
            adminLoaded.add(channel.adminStreamId)
        } catch (e: Exception) { /* no admin history */ }
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
        state.optJSONArray("hiddenMessageIds")?.let { arr ->
            _hiddenIds.value = (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }.toSet()
        }
        state.optJSONArray("bannedMembers")?.let { arr ->
            _bannedMembers.value = (0 until arr.length()).mapNotNull { arr.optString(it).lowercase().ifEmpty { null } }.toSet()
        }
        state.optJSONArray("pins")?.let { _pins.value = pinsFromJson(it) }
    }

    /** Publishes the full ADMIN_STATE with an incremented rev (owner only). */
    internal suspend fun publishAdminState(channel: Channel) {
        val addr = myAddress() ?: return
        // Never compute a rev off an unscanned stream (web gates publish on
        // adminLoaded): moderating fast, before the on-open load finished,
        // published rev=1 over a channel already at rev N — every peer with
        // the higher rev discarded it. Failure proceeds with the stale rev,
        // matching the web's "may publish stale rev" warning path.
        if (channel.adminStreamId !in adminLoaded) {
            runCatching { loadAdminState(channel, switchGeneration) }
        }
        val rev = (adminRevs[channel.adminStreamId] ?: 0) + 1
        val state = JSONObject()
            .put("bannedMembers", JSONArray(_bannedMembers.value.toList()))
            .put("hiddenMessageIds", JSONArray(_hiddenIds.value.toList()))
            .put("pins", pinsToJson(_pins.value))
        val msg = JSONObject()
            .put("type", "ADMIN_STATE").put("rev", rev)
            .put("ts", System.currentTimeMillis()).put("createdBy", addr)
            .put("state", state)
        publishForChannel(channel, channel.adminStreamId, StreamConstants.ADMIN_MODERATION, msg)
        // Commit the revision only once it is on the wire. Incrementing up
        // front meant a failed publish — which [moderate] rolls back — still
        // burned a revision, so the next attempt skipped a number.
        adminRevs[channel.adminStreamId] = rev
        adminTs[channel.adminStreamId] = msg.optLong("ts")
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

    suspend fun hideMessage(messageId: String, hide: Boolean) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can moderate")
        moderate(channel, _hiddenIds, if (hide) _hiddenIds.value + messageId else _hiddenIds.value - messageId)
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
        if (!amOwner(channel)) throw IllegalStateException("Only the channel admin can moderate")
        val addr = address.lowercase()
        moderate(
            channel, _bannedMembers,
            if (ban) _bannedMembers.value + addr else _bannedMembers.value - addr
        )
    }

    /**
     * The two enforcement levels behind one Ban action.
     *
     * CLIENT is the ADMIN_STATE ban: every client hides the author's messages,
     * free and reversible, and only the creator may publish it. PROTOCOL is
     * the gate ban: `checkAccess` goes false, so no responder hands out keys,
     * and the epoch rotation that follows cuts reads from here on. Costs gas.
     */
    suspend fun banMemberLevels(address: String, client: Boolean, protocol: Boolean) {
        val channel = _current.value ?: throw IllegalStateException("No channel open")
        val addr = address.trim()
        if (channelOwner(channel)?.equals(addr, ignoreCase = true) == true) {
            throw IllegalStateException("Cannot ban the channel creator")
        }
        if (protocol) {
            val gate = channel.gateAddress
                ?: throw IllegalStateException("Only gated channels have a protocol-level ban")
            bridge.call("gateBan", JSONObject()
                .put("gate", gate).put("user", addr).put("erase", false), 180_000)
            gateManageCache.clear()
            val keysId = channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(channel.messageStreamId) }
            var rotated = channel.rotatedForBanned
            try {
                epochKeys.rotateEpoch(channel.messageStreamId, keysId)
                // Covered: the deferred pass must not rotate again for this one.
                rotated = (rotated + addr.lowercase()).distinct()
            } catch (e: Exception) {
                Log.w(TAG, "Epoch rotation after gate ban FAILED — banned member can still read new messages until the next rotation: ${e.message}")
            }
            val updated = channel.copy(
                members = channel.members.filterNot { it.equals(addr, ignoreCase = true) },
                rotatedForBanned = rotated,
                knownBanned = (channel.knownBanned + addr.lowercase()).distinct()
            )
            _channels.value = _channels.value.map { if (it.messageStreamId == updated.messageStreamId) updated else it }
            store.save(_channels.value)
            _current.value = updated
        }
        if (client) banMember(addr, true)
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
        }
        if (_bannedMembers.value.any { it.equals(addr, ignoreCase = true) }) {
            banMember(addr, false)
        }
    }
}
