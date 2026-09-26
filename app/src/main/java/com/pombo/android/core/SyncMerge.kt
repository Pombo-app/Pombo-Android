package com.pombo.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-device state merge — port of the web's syncMerge.js.
 *
 * Everything here operates on the raw payload object rather than a Kotlin
 * model, and that is deliberate: the web syncs slices this client does not
 * manage (`sentMessages`, `sentReactions`, `blockedPeers`, `dmLeftAt`). If
 * Android rebuilt the payload from its own state it would push back a snapshot
 * with those slices missing and wipe them on every other device. Unknown keys
 * survive a round trip untouched.
 */
object SyncMerge {

    /** Web CONFIG.dm.maxSentMessages. */
    private const val MAX_SENT_MESSAGES = 200

    /** Slices resolved by `sliceTs` latest-wins. */
    private val TIMESTAMPED_SLICES = listOf(
        "blockedPeers", "dmLeftAt", "trustedContacts", "username", "graphApiKey"
    )

    // Older than any real stamp, so a live edit still wins; newer than the 0 of
    // a snapshot that holds nothing.
    private const val UNSTAMPED_VALUE_TS = 1L

    private fun isEmptySlice(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL -> true
        is String -> value.isEmpty()
        is JSONArray -> value.length() == 0
        is JSONObject -> value.length() == 0
        else -> false
    }

    /**
     * The state's slice timestamps, with every slice that holds a value but no
     * stamp (a restored backup, an old client) stamped [UNSTAMPED_VALUE_TS].
     * Web syncMerge.js stampedSliceTs.
     */
    fun stampedSliceTs(state: JSONObject?): JSONObject {
        val out = JSONObject()
        state?.optJSONObject("sliceTs")?.let { s -> s.keys().forEach { out.put(it, s.get(it)) } }
        TIMESTAMPED_SLICES.forEach { key ->
            if (out.optLong(key, 0L) == 0L && !isEmptySlice(state?.opt(key))) {
                out.put(key, UNSTAMPED_VALUE_TS)
            }
        }
        return out
    }

    fun mergeState(base: JSONObject?, incoming: JSONObject?): JSONObject {
        val out = JSONObject()

        // Carry over any slice neither side models here, so a future web field
        // is not silently dropped by this client.
        (base ?: JSONObject()).keys().forEach { k -> out.put(k, (base ?: JSONObject()).get(k)) }
        (incoming ?: JSONObject()).keys().forEach { k ->
            if (!out.has(k)) out.put(k, (incoming ?: JSONObject()).get(k))
        }

        val (channels, leftAt) = mergeChannels(
            base?.optJSONArray("channels"),
            incoming?.optJSONArray("channels"),
            base?.optJSONObject("channelsLeftAt"),
            incoming?.optJSONObject("channelsLeftAt")
        )
        out.put("channels", channels)
        out.put("channelsLeftAt", leftAt)

        val survivingIds = HashSet<String>()
        for (i in 0 until channels.length()) {
            channels.optJSONObject(i)?.optStringOrNull("messageStreamId")?.let { survivingIds.add(it) }
        }
        out.put(
            "epochKeys",
            mergeEpochKeys(
                base?.optJSONObject("epochKeys"),
                incoming?.optJSONObject("epochKeys"),
                survivingIds
            )
        )

        val sentDeletedAt = mergeSentDeletedAt(
            base?.optJSONObject("sentDeletedAt"), incoming?.optJSONObject("sentDeletedAt")
        )
        out.put(
            "sentMessages",
            mergeSentMessages(
                base?.optJSONObject("sentMessages"), incoming?.optJSONObject("sentMessages"), sentDeletedAt
            )
        )
        out.put("sentDeletedAt", sentDeletedAt)
        out.put(
            "sentReactions",
            mergeSentReactions(base?.optJSONObject("sentReactions"), incoming?.optJSONObject("sentReactions"))
        )

        val sliceTs = JSONObject()
        TIMESTAMPED_SLICES.forEach { key ->
            // graphApiKey keeps the web's legacy rule: with no timestamps on
            // either side, a null incoming key must not delete a configured one.
            if (key == "graphApiKey" &&
                base?.optJSONObject("sliceTs")?.optLong("graphApiKey", 0L) ?: 0L == 0L &&
                incoming?.optJSONObject("sliceTs")?.optLong("graphApiKey", 0L) ?: 0L == 0L
            ) {
                val v = incoming?.optStringOrNull("graphApiKey") ?: base?.optStringOrNull("graphApiKey")
                out.put("graphApiKey", v ?: JSONObject.NULL)
                sliceTs.put("graphApiKey", 0L)
                return@forEach
            }
            val picked = pickSlice(key, base, incoming, sliceTs)
            out.put(key, picked ?: JSONObject.NULL)
        }
        out.put("sliceTs", sliceTs)

        // ensCache is a plain shallow merge, incoming wins per key.
        val ens = JSONObject()
        base?.optJSONObject("ensCache")?.let { b -> b.keys().forEach { ens.put(it, b.get(it)) } }
        incoming?.optJSONObject("ensCache")?.let { i -> i.keys().forEach { ens.put(it, i.get(it)) } }
        out.put("ensCache", ens)

        return out
    }

    fun mergeSeries(base: JSONObject?, payloads: List<JSONObject>): JSONObject {
        var merged = base ?: JSONObject()
        payloads.forEach { merged = mergeState(merged, it) }
        return merged
    }

    /**
     * LWW-element-set over channels. Membership is decided by comparing the
     * join timestamp against the leave tombstone; the most recent action wins
     * and a tie goes to Join. Snapshot replacement would let an older remote
     * snapshot delete a channel this device just joined. Two copies of the
     * same channel merge field by field ([mergeChannelRecord]).
     */
    private fun mergeChannels(
        baseChannels: JSONArray?,
        incomingChannels: JSONArray?,
        baseLeftAt: JSONObject?,
        incomingLeftAt: JSONObject?
    ): Pair<JSONArray, JSONObject> {
        val leftAt = JSONObject()
        listOfNotNull(baseLeftAt, incomingLeftAt).forEach { src ->
            src.keys().forEach { id ->
                val ts = src.optLong(id, -1L)
                if (ts >= 0 && (!leftAt.has(id) || ts > leftAt.optLong(id))) leftAt.put(id, ts)
            }
        }

        val byId = LinkedHashMap<String, JSONObject>()
        val latestJoin = HashMap<String, Long>()
        val noteJoin = { id: String, c: JSONObject ->
            latestJoin[id] = maxOf(latestJoin[id] ?: 0L, joinTs(c))
        }
        baseChannels?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optStringOrNull("messageStreamId") ?: continue
                byId[id] = c
                noteJoin(id, c)
            }
        }
        incomingChannels?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optStringOrNull("messageStreamId") ?: continue
                noteJoin(id, c)
                val existing = byId[id]
                byId[id] = when {
                    existing == null -> c
                    replaces(c, existing) -> mergeChannelRecord(c, existing)
                    else -> mergeChannelRecord(existing, c)
                }
            }
        }

        val channels = JSONArray()
        byId.forEach { (id, channel) ->
            val left = if (leftAt.has(id)) leftAt.optLong(id) else null
            if (left != null && left > (latestJoin[id] ?: 0L)) return@forEach
            channels.put(channel)
            // A re-join supersedes the tombstone; drop it so the map stays small.
            if (left != null) leftAt.remove(id)
        }
        return channels to leftAt
    }

    private fun fieldStamp(record: JSONObject, key: String): Long =
        (record.optJSONObject("fieldTs")?.opt(key) as? Number)?.toLong() ?: 0L

    /**
     * One channel record from two copies of it (web mergeChannelRecord). Each
     * field comes from the copy whose `fieldTs` stamped it later; a field
     * neither copy stamped, or both stamped at the same time, comes from
     * [preferred], and a field only one copy carries is kept. The stamps join,
     * the latest per field, so a client that drops them cannot take them from
     * the others. Returns [preferred] itself when nothing comes from [other].
     */
    fun mergeChannelRecord(preferred: JSONObject, other: JSONObject): JSONObject {
        val merged = JSONObject()
        var tookOther = false
        val keys = LinkedHashSet<String>()
        preferred.keys().forEach { keys.add(it) }
        other.keys().forEach { keys.add(it) }
        for (key in keys) {
            if (key == "fieldTs") continue
            val fromOther = other.has(key) &&
                (!preferred.has(key) || fieldStamp(other, key) > fieldStamp(preferred, key))
            merged.put(key, if (fromOther) other.get(key) else preferred.get(key))
            if (fromOther) tookOther = true
        }
        if (!tookOther) return preferred
        val sources = listOfNotNull(preferred.optJSONObject("fieldTs"), other.optJSONObject("fieldTs"))
        if (sources.isNotEmpty()) {
            val stamps = JSONObject()
            sources.forEach { src ->
                src.keys().forEach { key ->
                    val ts = (src.opt(key) as? Number)?.toLong() ?: return@forEach
                    if (ts > stamps.optLong(key, 0L)) stamps.put(key, ts)
                }
            }
            merged.put("fieldTs", stamps)
        }
        return merged
    }

    private fun joinTs(channel: JSONObject): Long {
        val joined = channel.optLong("joinedAt", 0L)
        return if (joined > 0L) joined else channel.optLong("createdAt", 0L)
    }

    /** For the fields no stamp decides: incoming wins ties. An entry with no
     *  joinedAt of its own is never a newer join than one that has it, but its
     *  time still counts as a join against a leave tombstone. */
    private fun replaces(incoming: JSONObject, existing: JSONObject): Boolean {
        val incomingJoined = incoming.optLong("joinedAt", 0L) > 0L
        val existingJoined = existing.optLong("joinedAt", 0L) > 0L
        if (incomingJoined != existingJoined) return incomingJoined
        return joinTs(incoming) >= joinTs(existing)
    }

    /**
     * Union-merge of the epoch-key slice (web mergeEpochKeys). Entries are
     * content-addressed (keyId → immutable key, epoch → immutable announce,
     * requestId → immutable pending id), so union is exact: base wins per
     * entry — an adopted key must never regress — and currentEpoch only moves
     * forward. Pending request ids union so a v2 wrap answered later opens on
     * every device of the account; helloEpochs union so a second device does
     * not re-hello an epoch. Channels the channel merge dropped retire their
     * keys with them.
     */
    private fun mergeEpochKeys(
        base: JSONObject?,
        incoming: JSONObject?,
        keepIds: Set<String>
    ): JSONObject {
        val result = JSONObject()
        val streamIds = LinkedHashSet<String>()
        base?.keys()?.forEach { streamIds.add(it) }
        incoming?.keys()?.forEach { streamIds.add(it) }

        streamIds.forEach { streamId ->
            if (streamId !in keepIds) return@forEach
            val b = base?.optJSONObject(streamId)
            val i = incoming?.optJSONObject(streamId)
            if (b == null || i == null) {
                (b ?: i)?.let { result.put(streamId, it) }
                return@forEach
            }
            val entry = JSONObject()
            val epochs = JSONObject()
            i.optJSONObject("epochs")?.let { src -> src.keys().forEach { epochs.put(it, src.get(it)) } }
            b.optJSONObject("epochs")?.let { src -> src.keys().forEach { epochs.put(it, src.get(it)) } }
            entry.put("epochs", epochs)
            val announces = JSONObject()
            i.optJSONObject("announces")?.let { src -> src.keys().forEach { announces.put(it, src.get(it)) } }
            b.optJSONObject("announces")?.let { src -> src.keys().forEach { announces.put(it, src.get(it)) } }
            entry.put("announces", announces)
            entry.put("currentEpoch", maxOf(b.optInt("currentEpoch"), i.optInt("currentEpoch")))
            val pendingRequests = JSONObject()
            i.optJSONObject("pendingRequests")?.let { src -> src.keys().forEach { pendingRequests.put(it, src.get(it)) } }
            b.optJSONObject("pendingRequests")?.let { src -> src.keys().forEach { pendingRequests.put(it, src.get(it)) } }
            entry.put("pendingRequests", pendingRequests)
            val helloEpochs = sortedSetOf<Int>()
            i.optJSONArray("helloEpochs")?.let { src -> for (k in 0 until src.length()) helloEpochs.add(src.optInt(k)) }
            b.optJSONArray("helloEpochs")?.let { src -> for (k in 0 until src.length()) helloEpochs.add(src.optInt(k)) }
            entry.put("helloEpochs", JSONArray(helloEpochs.filter { it > 0 }))
            // Shared keys (publish and interactions): higher rev wins (a
            // re-key must supersede on every device); ties keep base.
            val higherRev = { x: JSONObject?, y: JSONObject? ->
                if (x == null) y else if (y == null) x
                else if (y.optInt("rev") > x.optInt("rev")) y else x
            }
            for (slot in listOf("pubKey", "pubAnnounce", "intKey", "intAnnounce")) {
                higherRev(b.optJSONObject(slot), i.optJSONObject(slot))?.let { entry.put(slot, it) }
            }
            result.put(streamId, entry)
        }
        return result
    }

    private fun editedAt(message: JSONObject): Long =
        (message.opt("_editedAt") as? Number)?.toLong() ?: 0L

    /**
     * Union of sent messages by id (web mergeSentMessages). A copy edited later
     * than the other replaces it, and any message [deleted] names is left out,
     * whichever side still holds it. Exposed so the export can union the local
     * DM slice with the sync base.
     */
    fun mergeSentMessages(local: JSONObject?, remote: JSONObject?, deleted: JSONObject? = null): JSONObject {
        val result = JSONObject()
        local?.keys()?.forEach { streamId ->
            result.put(streamId, local.optJSONArray(streamId) ?: JSONArray())
        }
        remote?.keys()?.forEach { streamId ->
            val remoteMsgs = remote.optJSONArray(streamId) ?: return@forEach
            val existingArr = result.optJSONArray(streamId)
            if (existingArr == null) {
                result.put(streamId, remoteMsgs)
                return@forEach
            }
            val byId = LinkedHashMap<String, JSONObject>()
            for (i in 0 until existingArr.length()) {
                val m = existingArr.optJSONObject(i) ?: continue
                m.optStringOrNull("id")?.let { byId[it] = m }
            }
            for (i in 0 until remoteMsgs.length()) {
                val m = remoteMsgs.optJSONObject(i) ?: continue
                val id = m.optStringOrNull("id") ?: continue
                val existing = byId[id]
                if (existing == null) {
                    byId[id] = m
                } else if (editedAt(m) > editedAt(existing)) {
                    val edited = JSONObject()
                    existing.keys().forEach { edited.put(it, existing.get(it)) }
                    m.keys().forEach { edited.put(it, m.get(it)) }
                    if (m.isNull("imageData") && !existing.isNull("imageData")) {
                        edited.put("imageData", existing.get("imageData"))
                    }
                    byId[id] = edited
                } else if (m.optString("type") == "image" &&
                    !m.isNull("imageData") && existing.isNull("imageData")
                ) {
                    // Blob sync carries image bytes separately; fill the gap.
                    existing.put("imageData", m.get("imageData"))
                }
            }
            val sorted = byId.values.sortedBy { it.optLong("timestamp", 0L) }
                .takeLast(MAX_SENT_MESSAGES)
            result.put(streamId, JSONArray(sorted))
        }
        return withoutDeleted(result, deleted)
    }

    /**
     * Union of two sent-message deletion maps ({ streamId: { messageId:
     * deletedAt } }), the latest time per message (web mergeSentDeletedAt). A
     * message id is never reused, so an entry is never retracted and never
     * pruned.
     */
    fun mergeSentDeletedAt(base: JSONObject?, incoming: JSONObject?): JSONObject {
        val result = JSONObject()
        listOfNotNull(base, incoming).forEach { src ->
            src.keys().forEach { streamId ->
                val ids = src.optJSONObject(streamId) ?: return@forEach
                val out = result.optJSONObject(streamId) ?: JSONObject().also { result.put(streamId, it) }
                ids.keys().forEach { messageId ->
                    val ts = (ids.opt(messageId) as? Number)?.toLong() ?: return@forEach
                    if (!out.has(messageId) || ts > out.optLong(messageId)) out.put(messageId, ts)
                }
            }
        }
        return result
    }

    /** The sent messages without every one the deletion map names. */
    fun withoutDeleted(sentMessages: JSONObject, deleted: JSONObject?): JSONObject {
        if (deleted == null) return sentMessages
        val out = JSONObject()
        sentMessages.keys().forEach { streamId ->
            val messages = sentMessages.optJSONArray(streamId) ?: return@forEach
            val gone = deleted.optJSONObject(streamId)
            if (gone == null) {
                out.put(streamId, messages)
                return@forEach
            }
            val kept = JSONArray()
            for (i in 0 until messages.length()) {
                val m = messages.optJSONObject(i) ?: continue
                if (m.optStringOrNull("id")?.let { gone.has(it) } != true) kept.put(m)
            }
            out.put(streamId, kept)
        }
        return out
    }

    /**
     * Fold one channel's synced key slice into the stored one. A channel holds
     * more than its epoch keys: on a Sealed channel the shared publish key is
     * what opens authorship, so a field dropped here leaves history unreadable
     * on any device that already had state. Content keys union with local
     * winning (an adopted key never regresses); the keyed ones take the higher
     * rev, matching [mergeEpochKeys].
     */
    fun foldEpochKeySlice(local: JSONObject, incoming: JSONObject): JSONObject {
        for (field in listOf("epochs", "announces", "pendingRequests")) {
            val target = local.optJSONObject(field) ?: JSONObject().also { local.put(field, it) }
            incoming.optJSONObject(field)?.let { inc ->
                inc.keys().forEach { k -> if (!target.has(k)) target.put(k, inc.get(k)) }
            }
        }
        if (incoming.optInt("currentEpoch") > local.optInt("currentEpoch")) {
            local.put("currentEpoch", incoming.optInt("currentEpoch"))
        }
        for (field in listOf("pubKey", "pubAnnounce", "intKey", "intAnnounce")) {
            val inc = incoming.optJSONObject(field) ?: continue
            val cur = local.optJSONObject(field)
            if (cur == null || inc.optInt("rev") > cur.optInt("rev")) local.put(field, inc)
        }
        val helloEpochs = sortedSetOf<Int>()
        for (src in listOfNotNull(local.optJSONArray("helloEpochs"), incoming.optJSONArray("helloEpochs"))) {
            for (k in 0 until src.length()) src.optInt(k).takeIf { it > 0 }?.let { helloEpochs.add(it) }
        }
        if (helloEpochs.isNotEmpty()) local.put("helloEpochs", JSONArray(helloEpochs.toList()))
        if (local.optString("helloName").isEmpty()) {
            incoming.optString("helloName").ifEmpty { null }?.let { local.put("helloName", it) }
        }
        if (local.optLong("helloTs") <= 0L && incoming.optLong("helloTs") > 0L) {
            local.put("helloTs", incoming.optLong("helloTs"))
        }
        return local
    }

    /** Exposed for seeding SentReactionsStore from the stored sync base. */
    fun mergeSentReactions(local: JSONObject?, remote: JSONObject?): JSONObject {
        val result = JSONObject()
        val streamIds = LinkedHashSet<String>()
        local?.keys()?.forEach { streamIds.add(it) }
        remote?.keys()?.forEach { streamIds.add(it) }

        streamIds.forEach { streamId ->
            val localStream = local?.optJSONObject(streamId) ?: JSONObject()
            val remoteStream = remote?.optJSONObject(streamId) ?: JSONObject()
            val merged = JSONObject()
            val ids = LinkedHashSet<String>()
            localStream.keys().forEach { ids.add(it) }
            remoteStream.keys().forEach { ids.add(it) }
            ids.forEach { messageId ->
                if (remoteStream.has(messageId)) {
                    // Remote wins, but an emptied map means "removed".
                    val r = remoteStream.optJSONObject(messageId)
                    if (r != null && r.length() > 0) merged.put(messageId, r)
                } else {
                    localStream.opt(messageId)?.let { merged.put(messageId, it) }
                }
            }
            if (merged.length() > 0) result.put(streamId, merged)
        }
        return result
    }

    private fun pickSlice(key: String, base: JSONObject?, incoming: JSONObject?, sliceTs: JSONObject): Any? {
        val baseHas = base?.has(key) == true
        val incomingHas = incoming?.has(key) == true
        val baseTs = base?.optJSONObject("sliceTs")?.optLong(key, 0L) ?: 0L
        val incomingTs = incoming?.optJSONObject("sliceTs")?.optLong(key, 0L) ?: 0L
        // A device that has read nothing yet publishes empty, unstamped slices;
        // those never replace a value.
        val emptyOverValue = incomingTs == 0L &&
            isEmptySlice(incoming?.opt(key)) && !isEmptySlice(base?.opt(key))

        if (incomingHas && !emptyOverValue && (!baseHas || incomingTs >= baseTs)) {
            sliceTs.put(key, incomingTs)
            return incoming!!.opt(key)
        }
        if (baseHas) {
            sliceTs.put(key, baseTs)
            return base!!.opt(key)
        }
        sliceTs.put(key, 0L)
        return when (key) {
            "blockedPeers" -> JSONArray()
            "dmLeftAt", "trustedContacts" -> JSONObject()
            else -> null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        return optString(key, "").ifEmpty { null }
    }
}
