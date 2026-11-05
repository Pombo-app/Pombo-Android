package com.pombo.android.core

import org.json.JSONObject

/**
 * Reads a DM inbox diagnosis (bridge diagnoseDmInbox; web SettingsUI
 * inboxFindings). Every check is true, false, or null when the chain did not
 * answer, and only a false is a finding: repairing a null would spend gas on
 * something that may well be fine.
 */
object DmInboxHealth {

    /** Inbox message stream partitions (web MESSAGE_STREAM.DM_PARTITIONS). */
    const val DM_PARTITIONS = 13

    data class Findings(val issues: List<String>, val unchecked: List<String>)

    /** A missing key and a JSON null both mean the check did not answer. */
    fun check(obj: JSONObject?, key: String): Boolean? =
        if (obj == null || !obj.has(key) || obj.isNull(key)) null else obj.optBoolean(key)

    fun findings(report: JSONObject): Findings {
        val m = report.optJSONObject("messageStream")
        val e = report.optJSONObject("ephemeralStream")
        val p = report.optJSONObject("permissions")
        val s = report.optJSONObject("storage")
        val issues = mutableListOf<String>()
        val unchecked = mutableListOf<String>()
        val messageExists = check(m, "exists")
        val ephemeralExists = check(e, "exists")
        when (messageExists) {
            false -> issues += "message stream"
            null -> unchecked += "message stream"
            true -> {}
        }
        when (ephemeralExists) {
            false -> issues += "ephemeral stream"
            null -> unchecked += "ephemeral stream"
            true -> {}
        }
        if (messageExists == true) {
            when (check(m, "partitionsCorrect")) {
                false -> issues += "partition count (${m?.opt("partitions")?.takeUnless { it == JSONObject.NULL } ?: "?"}/$DM_PARTITIONS)"
                null -> unchecked += "partition count"
                true -> {}
            }
            when (check(m, "publicKeyPresent")) {
                false -> issues += "public key"
                null -> unchecked += "public key"
                true -> {}
            }
            if (check(p, "messagePublicPublish") == null) unchecked += "message permissions"
            if (check(s, "enabled") == null) unchecked += "storage"
        }
        if (ephemeralExists == true && check(p, "ephemeralPublicPublish") == null) unchecked += "ephemeral permissions"
        if (check(p, "messagePublicPublish") == false) issues += "message permissions"
        if (check(p, "ephemeralPublicPublish") == false) issues += "ephemeral permissions"
        if (check(s, "enabled") == false) issues += "storage"
        return Findings(issues, unchecked)
    }
}
