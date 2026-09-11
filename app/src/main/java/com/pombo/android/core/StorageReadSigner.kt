package com.pombo.android.core

import java.net.URI
import java.net.URLDecoder
import java.security.SecureRandom

/**
 * Signed storage reads — native mirror of the web's `storageReadSigner.js`
 * (the request half of the Pombo storage node's "signed requests" protocol).
 *
 * A node with `signedReads` on answers 401 to unsigned history reads of a
 * gated channel's streams (the `-3` stays open). The request authenticates
 * with an EIP-191 `personal_sign` signature over a message built from the
 * request itself, one field per line:
 *
 *   pombo-storage-node / read / streamId / partition / issuedAt / nonce /
 *   resendType / canonicalQuery
 *
 * canonicalQuery = the query parameters sorted by name (stable, so repeats
 * keep their received order), `name=value` with the values decoded, joined
 * by `&`. It must match what the node parses from the URL byte for byte, so
 * it is derived from the URL that is actually sent.
 *
 * Parity with the web is locked by docs/STORAGE-signed-read-vectors.json.
 * The bridge page carries the same logic in JavaScript for the SDK's own
 * resends; this object serves the native direct reads ([StorageHttp]).
 */
object StorageReadSigner {

    data class Parsed(
        val base: String,
        val streamId: String,
        val partition: Int,
        val resendType: String,
        val canonicalQuery: String
    )

    private val DATA_PATH = Regex("""^(.*)/streams/([^/]+)/data/partitions/(\d+)/(last|from|range)$""")
    private val random = SecureRandom()

    /** Split a storage node data URL into the parts the signature covers; null for any other URL. */
    fun parse(url: String): Parsed? {
        val uri = try { URI(url) } catch (e: Exception) { return null }
        val path = uri.rawPath ?: return null
        val m = DATA_PATH.matchEntire(path) ?: return null
        val streamId = try { URLDecoder.decode(m.groupValues[2], "UTF-8") } catch (e: Exception) { return null }
        val origin = buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority)
        }
        return Parsed(
            base = origin + m.groupValues[1],
            streamId = streamId,
            partition = m.groupValues[3].toInt(),
            resendType = m.groupValues[4],
            canonicalQuery = canonicalQuery(uri.rawQuery ?: "")
        )
    }

    /**
     * Canonical form of a raw query string. Decoding follows the browser's
     * URLSearchParams: `+` is a space, percent-escapes are UTF-8.
     */
    fun canonicalQuery(rawQuery: String): String {
        if (rawQuery.isEmpty()) return ""
        val entries = rawQuery.split('&').filter { it.isNotEmpty() }.mapIndexed { index, part ->
            val eq = part.indexOf('=')
            val name = if (eq < 0) part else part.substring(0, eq)
            val value = if (eq < 0) "" else part.substring(eq + 1)
            Triple(decode(name), decode(value), index)
        }
        return entries
            .sortedWith(compareBy<Triple<String, String, Int>> { it.first }.thenBy { it.third })
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun decode(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    /** The exact string the client signs for a read. */
    fun buildMessage(
        streamId: String, partition: Int, issuedAt: Long, nonce: String,
        resendType: String, canonicalQuery: String
    ): String = listOf(
        "pombo-storage-node", "read", streamId, partition.toString(), issuedAt.toString(),
        nonce, resendType, canonicalQuery
    ).joinToString("\n")

    /** 16 random bytes as lowercase hex. */
    fun randomNonce(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * The four `x-pombo-*` headers for a parsed read, signed with the
     * identity key. Null when there is no key to sign with.
     */
    fun headers(
        parsed: Parsed, privateKeyHex: String?,
        issuedAt: Long = System.currentTimeMillis(), nonce: String = randomNonce()
    ): Map<String, String>? {
        if (privateKeyHex.isNullOrEmpty()) return null
        val message = buildMessage(
            parsed.streamId, parsed.partition, issuedAt, nonce, parsed.resendType, parsed.canonicalQuery
        )
        val signature = SigningOracle.signMessage(message.toByteArray(Charsets.UTF_8), privateKeyHex)
        if (signature.isEmpty()) return null
        return mapOf(
            "x-pombo-user" to EthereumSigner.checksumAddress(EthereumSigner.address(privateKeyHex)),
            "x-pombo-issued-at" to issuedAt.toString(),
            "x-pombo-nonce" to nonce,
            "x-pombo-signature" to signature
        )
    }
}
