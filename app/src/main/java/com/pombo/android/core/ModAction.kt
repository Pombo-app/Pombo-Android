package com.pombo.android.core

import org.json.JSONObject

/**
 * MOD_ACTION: the signed moderation delta a moderator publishes on the
 * message stream's moderation partition. Byte-exact port of the web's
 * modAction.js.
 *
 * The signature is over a domain-tagged digest of the fields in a fixed
 * order, never over serialized JSON — key order and whitespace would differ
 * between platforms. That makes a delta verifiable on its own, straight off a
 * raw read, with no dependence on transport validation. Whether the signer
 * still moderates is a separate question, asked only while the delta is
 * unabsorbed (see [ModComposition]).
 *
 * This is deliberately not the override path: an override says "the author
 * edits their own message", a delta says "someone who is not the author hides
 * it" — same wire shape, opposite authority rule.
 */
object ModAction {

    const val TYPE = "MOD_ACTION"
    private const val DOMAIN = "POMBO_MOD_V1"

    val OPS = setOf("hide", "unhide", "ban", "unban")

    /** `sinceEpoch` participates only for `ban`, and is empty for a ban that hides everything. */
    fun digest(streamId: String, op: String, target: String, sinceEpoch: Int?, ts: Long): ByteArray {
        val epochPart = if (op == "ban" && sinceEpoch != null) sinceEpoch.toString() else ""
        return SealedSenderCrypto.keccak256(
            "$DOMAIN|${streamId.lowercase()}|$op|${target.lowercase()}|$epochPart|$ts"
                .toByteArray(Charsets.UTF_8))
    }

    /** Build a signed delta with the moderator's ACCOUNT key. */
    fun build(
        streamId: String, op: String, target: String,
        privateKey: String, sinceEpoch: Int? = null, ts: Long = System.currentTimeMillis()
    ): JSONObject {
        require(op in OPS) { "unknown moderation op: $op" }
        val sig = EthereumSigner.toHex(
            EthereumSigner.signDigest(digest(streamId, op, target, sinceEpoch, ts), privateKey))
        val out = JSONObject()
            .put("t", TYPE)
            .put("op", op)
            .put("target", target.lowercase())
            .put("ts", ts)
            .put("mod", EthereumSigner.address(privateKey).lowercase())
            .put("sig", sig)
        if (op == "ban" && sinceEpoch != null) out.put("sinceEpoch", sinceEpoch)
        return out
    }

    /** @return the signer address, or null when the delta does not verify. */
    fun verify(streamId: String, payload: JSONObject?): String? {
        if (payload == null || payload.optString("t") != TYPE) return null
        val op = payload.optString("op")
        if (op !in OPS) return null
        val target = payload.optString("target")
        val sig = payload.optString("sig")
        if (target.isEmpty() || sig.isEmpty()) return null
        val ts = payload.optLong("ts", 0L)
        if (ts <= 0L) return null
        val sinceEpoch = if (payload.has("sinceEpoch")) payload.optInt("sinceEpoch") else null
        return try {
            val signer = SealedSenderCrypto.recoverAddress(
                digest(streamId, op, target, sinceEpoch, ts),
                SealedSenderCrypto.hexToBytes(sig))?.lowercase() ?: return null
            // `mod` is a convenience field; the signature is the authority, so
            // a mismatch means the payload was tampered with in transit.
            val claimed = payload.optString("mod").lowercase()
            if (claimed.isNotEmpty() && claimed != signer) null else signer
        } catch (e: Exception) {
            null
        }
    }
}
