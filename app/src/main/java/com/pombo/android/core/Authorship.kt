package com.pombo.android.core

import org.json.JSONObject

/**
 * Members-only authorship — byte-exact port of the web's authorship.js.
 *
 * In a Members-only channel every message publishes under the channel's
 * SHARED key, so the network learns nothing about who wrote what. Authorship
 * lives INSIDE the epoch-sealed plaintext as a wrapper:
 *
 *   { v: 1, p: <payload JSON string>, pk, sig, bp }
 *
 *   pk   pseudonym: compressed secp256k1 pubkey, session-local
 *   sig  pseudonym signature over keccak256(POMBO_MSG_V1|streamId|p) —
 *        MANDATORY per message (only the bind proof would let a member paste
 *        someone else's proof onto their own messages)
 *   bp   bind proof: ACCOUNT signature over
 *        keccak256(POMBO_BIND_V1|streamId|pk), channel-scoped
 *
 * The payload travels as the exact STRING that was signed, parsed only after
 * verification — canonical-JSON reconstruction across platforms is a parity
 * trap this format refuses to enter. Raw-digest secp256k1 signatures
 * (RFC 6979 via [EthereumSigner.signDigest]); parity locked by
 * [AuthorshipTest]'s web-generated vectors.
 *
 * What verification guarantees, and what it does not: impersonating a CHOSEN
 * account is infeasible (a pasted bind proof recovers to garbage, never to
 * its owner), but ecrecover always yields some address, so a member can
 * fabricate messages under meaningless authors. Live ingest cuts those with
 * the gate check on the author; in history they are member-origin spam for
 * moderation.
 */
object Authorship {

    private const val BIND_DOMAIN = "POMBO_BIND_V1"
    private const val MSG_DOMAIN = "POMBO_MSG_V1"

    class Opened(val author: String, val payload: JSONObject)

    fun bindDigest(messageStreamId: String, pseudonymPubkey: String): ByteArray =
        SealedSenderCrypto.keccak256(
            "$BIND_DOMAIN|${messageStreamId.lowercase()}|${pseudonymPubkey.lowercase()}"
                .toByteArray(Charsets.UTF_8))

    fun msgDigest(messageStreamId: String, payloadString: String): ByteArray =
        SealedSenderCrypto.keccak256(
            "$MSG_DOMAIN|${messageStreamId.lowercase()}|$payloadString"
                .toByteArray(Charsets.UTF_8))

    /** Account signature tying the pseudonym to the account for this channel. */
    fun createBindProof(messageStreamId: String, pseudonymPubkey: String, accountPrivateKey: String): String =
        EthereumSigner.toHex(
            EthereumSigner.signDigest(bindDigest(messageStreamId, pseudonymPubkey), accountPrivateKey))

    /** Build the authorship wrapper around a payload object. */
    fun seal(
        messageStreamId: String, payload: JSONObject,
        pseudonymPrivateKey: String, pseudonymPubkey: String, bindProof: String
    ): JSONObject {
        val p = payload.toString()
        val sig = EthereumSigner.toHex(
            EthereumSigner.signDigest(msgDigest(messageStreamId, p), pseudonymPrivateKey))
        return JSONObject()
            .put("v", 1).put("p", p).put("pk", pseudonymPubkey)
            .put("sig", sig).put("bp", bindProof)
    }

    /** Does this epoch-sealed plaintext carry the authorship wrapper? */
    fun isWrapper(obj: JSONObject?): Boolean =
        obj != null && obj.optInt("v") == 1
            && obj.opt("p") is String && obj.opt("pk") is String
            && obj.opt("sig") is String && obj.opt("bp") is String

    /**
     * Verify a wrapper and recover its author: sig must recover to the
     * pseudonym, bp recovers the account. Any mismatch is a drop.
     */
    fun open(messageStreamId: String, wrapper: JSONObject): Opened? {
        if (!isWrapper(wrapper)) return null
        return try {
            val p = wrapper.getString("p")
            val pk = wrapper.getString("pk")
            val signer = SealedSenderCrypto.recoverAddress(
                msgDigest(messageStreamId, p),
                SealedSenderCrypto.hexToBytes(wrapper.getString("sig"))) ?: return null
            val pseudonymAddress = SealedSenderCrypto.pubkeyToAddress(pk) ?: return null
            if (!signer.equals(pseudonymAddress, ignoreCase = true)) return null
            val author = SealedSenderCrypto.recoverAddress(
                bindDigest(messageStreamId, pk),
                SealedSenderCrypto.hexToBytes(wrapper.getString("bp"))) ?: return null
            Opened(author.lowercase(), JSONObject(p))
        } catch (e: Exception) {
            null
        }
    }
}
