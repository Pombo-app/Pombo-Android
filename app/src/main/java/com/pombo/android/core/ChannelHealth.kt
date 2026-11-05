package com.pombo.android.core

import com.pombo.android.data.Channel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Channel Health (web channels/ChannelHealth.js): what a channel's streams
 * must look like for its type, what the chain says they look like, and the
 * steps that close the gap.
 *
 * Every check is true, false, or null when the chain did not answer. Only a
 * false is a finding and only a false is repaired.
 *
 * Shared Sealed keys are not checked: their addresses rotate and the rekey
 * tools own them. The clone is, and in Sealed it must not publish on the
 * -1/-2 (a grant it never uses would let a modified client self-identify).
 */
object ChannelHealth {

    /** Storage given to a stored stream when the -1 itself has none (DM inbox parity). */
    const val DEFAULT_STORAGE_DAYS = 180

    data class Grant(val userId: String?, val permissions: List<String>) {
        fun toJson(): JSONObject = JSONObject().apply {
            if (userId == null) put("public", true) else put("userId", userId)
            put("permissions", JSONArray(permissions))
        }
    }

    data class ExpectedStream(
        val kind: String,
        val id: String,
        val partitions: Int,
        val stored: Boolean,
        val linked: Boolean,
        val metaKind: String?,
        val grants: List<Grant>
    )

    data class Expected(val streams: List<ExpectedStream>, val password: Boolean, val gate: String?)

    private val SUB = listOf("subscribe")
    private val SUB_PUB = listOf("subscribe", "publish")

    fun expected(channel: Channel): Expected {
        val id = channel.messageStreamId
        val gated = channel.type == "gated"
        val sealed = gated && channel.wireIdentity == "sealed"
        val clone = channel.gateAddress?.lowercase()
        val listed = channel.exposure == "visible"
        fun viaClone(p: List<String>) = if (clone == null) emptyList() else listOf(Grant(clone, p))
        val pub = { p: List<String> -> Grant(null, p) }

        val streams = mutableListOf(
            ExpectedStream("message", id, StreamConstants.MSG_PARTITIONS, stored = true, linked = false, metaKind = null,
                grants = if (gated) viaClone(if (sealed) SUB else SUB_PUB) else listOf(pub(if (channel.readOnly) SUB else SUB_PUB))),
            ExpectedStream("ephemeral", channel.ephemeralStreamId.ifEmpty { StreamConstants.deriveEphemeralId(id) },
                StreamConstants.EPH_PARTITIONS, stored = false, linked = true, metaKind = null,
                grants = if (gated) viaClone(if (sealed) SUB else SUB_PUB) else listOf(pub(SUB_PUB))),
            ExpectedStream("admin", channel.adminStreamId.ifEmpty { StreamConstants.deriveAdminId(id) },
                StreamConstants.ADMIN_PARTITIONS, stored = true, linked = true, metaKind = "admin",
                grants = if (gated) viaClone(SUB) + (if (listed) listOf(pub(SUB)) else emptyList()) else listOf(pub(SUB)))
        )
        if (gated) {
            streams += ExpectedStream("keys", channel.keysStreamId.ifEmpty { StreamConstants.deriveKeysId(id) },
                StreamConstants.KEYS_PARTITIONS, stored = true, linked = true, metaKind = "keys", grants = viaClone(SUB_PUB))
        }
        streams += ExpectedStream("interactions", channel.interactionsStreamId.ifEmpty { StreamConstants.deriveInteractionsId(id) },
            StreamConstants.INTERACTIONS_PARTITIONS, stored = true, linked = true, metaKind = "interactions",
            grants = if (gated) viaClone(if (sealed) SUB else SUB_PUB) else listOf(pub(SUB_PUB)))
        return Expected(streams, password = channel.type == "password", gate = if (gated) clone else null)
    }

    /** The bridge channelStreamFacts request for these streams. */
    /** [nodes]: storage nodes to ask the chain about besides the ones already indexed. */
    fun factsRequest(expected: Expected, nodes: List<String> = emptyList()): JSONObject = JSONObject()
        .put("streams", JSONArray().apply {
            expected.streams.forEach { s ->
                put(JSONObject().put("id", s.id).put("stored", s.stored)
                    .put("grants", JSONArray().apply { s.grants.forEach { put(it.toJson()) } }))
            }
        })
        .put("nodes", JSONArray(nodes))

    data class StreamState(
        val id: String,
        val exists: Boolean?,
        val partitionsOk: Boolean?,
        val metadataOk: Boolean?,
        val readOnlyMeta: Boolean?,
        val grantsOk: Boolean?,
        val nodes: List<String>?,
        val storageDays: Int?,
        val storageOk: Boolean? = null,
        val retentionOk: Boolean? = null
    )

    data class Report(
        val channelId: String,
        val expected: Expected,
        val streams: Map<String, StreamState>,
        /** Null when the channel has no password challenge; `checked` false without the password. */
        val passwordChecked: Boolean?,
        val passwordFound: Boolean?,
        /** Null when not gated; `gateRead` false when the gate did not answer. */
        val gateRead: Boolean?,
        val gateReadOnly: Boolean?
    )

    private fun JSONObject.bool(key: String): Boolean? = if (!has(key) || isNull(key)) null else optBoolean(key)

    /** Reads the bridge's facts against what the channel's type requires. */
    fun evaluate(
        channelId: String,
        expected: Expected,
        facts: JSONObject,
        passwordChecked: Boolean?,
        passwordFound: Boolean?,
        gateRead: Boolean?,
        gateReadOnly: Boolean?
    ): Report {
        val arr = facts.optJSONArray("streams") ?: JSONArray()
        val byId = (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.optString("id") }
        val states = linkedMapOf<String, StreamState>()
        for (want in expected.streams) {
            val f = byId[want.id] ?: JSONObject()
            val exists = f.bool("exists")
            if (exists == false) {
                states[want.kind] = StreamState(want.id, false, false, false, null, false,
                    if (want.stored) emptyList() else null, null)
                continue
            }
            val partitions = if (f.isNull("partitions") || !f.has("partitions")) null else f.optInt("partitions")
            val desc = if (f.isNull("description") || !f.has("description")) null else f.optString("description")
            val meta = desc?.let { runCatching { if (it.isEmpty()) JSONObject() else JSONObject(it) }.getOrNull() }
            val metadataOk = when {
                exists != true || meta == null -> null
                meta.optString("a") != "pombo" -> false
                !want.linked -> true
                meta.optString("ln") != channelId -> false
                want.metaKind != null -> meta.optString("k") == want.metaKind
                else -> true
            }
            val nodes = f.optJSONArray("nodes")?.let { n -> (0 until n.length()).map { n.getString(it).lowercase() } }
                ?.takeIf { !f.isNull("nodes") }
            states[want.kind] = StreamState(
                id = want.id,
                exists = exists,
                partitionsOk = partitions?.let { it >= want.partitions },
                metadataOk = metadataOk,
                readOnlyMeta = if (want.kind == "message" && expected.gate != null && meta != null) meta.optBoolean("r", false) else null,
                grantsOk = if (exists == true) f.bool("grantsOk") else null,
                nodes = if (exists == true && want.stored) nodes else null,
                storageDays = if (f.isNull("storageDays") || !f.has("storageDays")) null else f.optInt("storageDays")
            )
        }
        val message = states.getValue("message")
        for (want in expected.streams.filter { it.stored }) {
            val got = states.getValue(want.kind)
            states[want.kind] = when {
                got.nodes == null -> got.copy(storageOk = null)
                want.kind == "message" -> got.copy(storageOk = got.nodes.isNotEmpty())
                message.nodes == null -> got.copy(storageOk = null)
                else -> got.copy(
                    storageOk = got.nodes.isNotEmpty() && message.nodes.all { it in got.nodes },
                    retentionOk = message.storageDays == null || got.storageDays == message.storageDays
                )
            }
        }
        return Report(channelId, expected, states,
            passwordChecked = if (expected.password) passwordChecked ?: false else null,
            passwordFound = passwordFound,
            gateRead = if (expected.gate != null) gateRead ?: false else null,
            gateReadOnly = gateReadOnly)
    }

    data class Findings(val issues: List<String>, val unchecked: List<String>, val reported: List<String>)

    private val LABEL = mapOf("message" to "-1", "ephemeral" to "-2", "admin" to "-3", "keys" to "-4", "interactions" to "-5")

    fun findings(report: Report): Findings {
        val issues = mutableListOf<String>()
        val unchecked = mutableListOf<String>()
        val reported = mutableListOf<String>()
        fun note(value: Boolean?, label: String) {
            when (value) {
                false -> issues += label
                null -> unchecked += label
                true -> {}
            }
        }
        for (want in report.expected.streams) {
            val got = report.streams.getValue(want.kind)
            val s = LABEL.getValue(want.kind)
            if (want.kind == "message" && got.exists == false) {
                reported += "$s stream missing"
                continue
            }
            note(got.exists, "$s stream")
            if (got.exists != true) continue
            note(got.partitionsOk, "$s partitions")
            note(got.metadataOk, "$s metadata")
            note(got.grantsOk, "$s permissions")
            if (want.stored) {
                note(got.storageOk, "$s storage")
                if (got.storageOk == true && got.retentionOk == false) issues += "$s retention"
            }
        }
        report.passwordChecked?.let { checked ->
            if (!checked) unchecked += "password challenge" else note(report.passwordFound, "password challenge")
        }
        report.gateRead?.let { read ->
            if (!read) unchecked += "gate"
            else when (val meta = report.streams.getValue("message").readOnlyMeta) {
                null -> unchecked += "read-only flag"
                else -> if (meta != report.gateReadOnly) issues += "read-only flag"
            }
        }
        return Findings(issues, unchecked, reported)
    }

    data class Step(
        val step: String,
        val kind: String,
        val gated: Boolean = false,
        val node: String? = null,
        val days: Int? = null,
        val readOnly: Boolean? = null
    )

    /**
     * The repair steps, in the order they are sent. A stream created here
     * needs its grants and storage as well. Storage follows the DM inbox: the
     * -1's nodes and retention, or the Pombo node for DEFAULT_STORAGE_DAYS
     * when the -1 has none.
     */
    fun repairSteps(report: Report, pomboNode: String): List<Step> {
        val steps = mutableListOf<Step>()
        val message = report.streams.getValue("message")
        if (message.exists == false) return steps
        val targetNodes = message.nodes?.takeIf { it.isNotEmpty() } ?: listOf(pomboNode.lowercase())
        val targetDays = if (message.nodes.isNullOrEmpty()) DEFAULT_STORAGE_DAYS else (message.storageDays ?: DEFAULT_STORAGE_DAYS)
        val gated = report.expected.gate != null
        for (want in report.expected.streams) {
            val got = report.streams.getValue(want.kind)
            val created = got.exists == false
            if (created) steps += Step("create", want.kind)
            if (!created && got.exists != true) continue
            if (!created && got.partitionsOk == false) steps += Step("partitions", want.kind)
            if (!created && got.metadataOk == false) steps += Step("metadata", want.kind)
            if (want.grants.isNotEmpty() && (created || got.grantsOk == false)) steps += Step("grants", want.kind, gated = gated)
            if (want.stored && (created || got.storageOk == false)) {
                targetNodes.filter { it !in got.nodes.orEmpty() }.forEach { steps += Step("storage", want.kind, node = it, days = targetDays) }
            } else if (want.stored && got.storageOk == true && got.retentionOk == false) {
                steps += Step("retention", want.kind, days = targetDays)
            }
        }
        val meta = message.readOnlyMeta
        if (report.gateRead == true && meta != null && report.gateReadOnly != null && meta != report.gateReadOnly) {
            steps += Step("readOnly", "message", readOnly = report.gateReadOnly)
        }
        if (report.passwordChecked == true && report.passwordFound == false) steps += Step("challenge", "admin")
        return steps
    }

    /** The description a created linked stream carries (web linkedMetadata). */
    fun linkedMetadata(want: ExpectedStream, channelId: String): JSONObject = JSONObject()
        .put("a", "pombo").put("v", "1").put("ln", channelId)
        .apply { want.metaKind?.let { put("k", it) } }
}
