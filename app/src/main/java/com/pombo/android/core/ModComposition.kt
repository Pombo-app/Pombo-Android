package com.pombo.android.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Moderation composition — port of the web's modComposition.js.
 *
 * The owner moderates by publishing a whole ADMIN_STATE; moderators cannot
 * publish there, so they emit signed deltas instead. What a client renders is
 * the snapshot with the unabsorbed deltas on top, which is what lets
 * moderation work while the owner is away and still lets the owner have the
 * last word when they return.
 *
 * The rules, in the order they matter:
 *  1. Only deltas newer than `absorbedThrough` count; at or below it, the
 *     owner has already spoken.
 *  2. A delta whose author is not a CURRENT moderator is ignored while it is
 *     unabsorbed — dismissing a moderator dissolves what they left pending,
 *     while what the owner ratified stays, because it became the owner's word.
 *  3. Deltas apply in (ts, mod, op, target) order, so two clients that
 *     received them in different orders still converge.
 *  4. A delta never overrides the snapshot: unhide/unban of something the
 *     snapshot asserts is a no-op.
 *  5. Among deltas, a later ban of the same address replaces the epoch stamp.
 *
 * Bans carry `sinceEpoch` rather than a timestamp: the epoch a message was
 * written under travels in the clear as its kid and cannot be forged, while
 * the payload timestamp is the publisher's to choose.
 */
object ModComposition {

    /** address -> epoch the ban starts from; null hides everything. */
    class Effective(
        val hiddenMessageIds: Set<String>,
        val bannedMembers: Map<String, Int?>
    )

    private fun compare(a: JSONObject, b: JSONObject): Int {
        val byTs = a.optLong("ts").compareTo(b.optLong("ts"))
        if (byTs != 0) return byTs
        val byMod = a.optString("mod").compareTo(b.optString("mod"))
        if (byMod != 0) return byMod
        val byOp = a.optString("op").compareTo(b.optString("op"))
        if (byOp != 0) return byOp
        return a.optString("target").compareTo(b.optString("target"))
    }

    /** Read a snapshot's `bannedMembers`, accepting the bare-address form. */
    fun bannedFromJson(arr: JSONArray?): Map<String, Int?> {
        val out = LinkedHashMap<String, Int?>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            when (val entry = arr.opt(i)) {
                is String -> if (entry.isNotEmpty()) out[entry.lowercase()] = null
                is JSONObject -> {
                    val address = entry.optString("address").lowercase()
                    if (address.isNotEmpty()) {
                        out[address] = if (entry.has("sinceEpoch") && !entry.isNull("sinceEpoch")) {
                            entry.optInt("sinceEpoch")
                        } else null
                    }
                }
            }
        }
        return out
    }

    fun bannedToJson(banned: Map<String, Int?>): JSONArray {
        val arr = JSONArray()
        banned.entries.sortedBy { it.key }.forEach { (address, sinceEpoch) ->
            val entry = JSONObject().put("address", address)
            if (sinceEpoch != null) entry.put("sinceEpoch", sinceEpoch) else entry.put("sinceEpoch", JSONObject.NULL)
            arr.put(entry)
        }
        return arr
    }

    fun compose(
        snapshotHidden: Set<String>,
        snapshotBanned: Map<String, Int?>,
        absorbedThrough: Long,
        deltas: List<JSONObject>,
        moderatorsNow: Collection<String>
    ): Effective {
        val hidden = LinkedHashSet(snapshotHidden)
        val banned = LinkedHashMap(snapshotBanned)
        val mods = moderatorsNow.map { it.lowercase() }.toSet()

        deltas
            .filter { it.optString("op") in ModAction.OPS && it.optString("target").isNotEmpty() }
            .filter { it.optLong("ts") > absorbedThrough }
            .filter { it.optString("mod").lowercase() in mods }
            .sortedWith(::compare)
            .forEach { d ->
                val op = d.optString("op")
                val target = if (op == "ban" || op == "unban") {
                    d.optString("target").lowercase()
                } else d.optString("target")
                when (op) {
                    "hide" -> hidden.add(target)
                    "unhide" -> if (target !in snapshotHidden) hidden.remove(target)
                    "ban" -> banned[target] =
                        if (d.has("sinceEpoch") && !d.isNull("sinceEpoch")) d.optInt("sinceEpoch") else null
                    "unban" -> if (target !in snapshotBanned) banned.remove(target)
                }
            }

        return Effective(hidden, banned)
    }

    /**
     * Is this message hidden by a ban? A ban with `sinceEpoch` hides only what
     * its author wrote from that epoch onward — everything before stays, which
     * is the difference between silencing someone and erasing their year.
     */
    fun banHides(sinceEpoch: Int?, messageEpoch: Int?): Boolean {
        if (sinceEpoch == null) return true
        if (messageEpoch == null) return false
        return messageEpoch >= sinceEpoch
    }
}
