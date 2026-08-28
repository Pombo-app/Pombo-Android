package com.pombo.android.core

/**
 * The only path from the WebView to the identity key.
 *
 * Two endpoints, each with its preimage built here rather than accepted from
 * the caller: [signMessage] applies the EIP-191 prefix itself, so it can never
 * yield a transaction signature whatever bytes it is handed, and
 * [signTransaction] signs only what parses as a transaction envelope and only
 * while the user's approval window is open. A caller-supplied digest has no
 * endpoint: an arbitrary-digest oracle would sign transactions with no
 * approval at all.
 *
 * Refusal is the empty string, never an exception, because the caller is a
 * JavaScript bridge method whose return value crosses into the page.
 */
object SigningOracle {

    /** Streamr/personal-message signature. Empty when there is no identity. */
    fun signMessage(payload: ByteArray, privateKeyHex: String): String {
        if (privateKeyHex.isEmpty()) return ""
        return try {
            EthereumSigner.toHex(EthereumSigner.signMessage(payload, privateKeyHex))
        } catch (e: Exception) {
            ""
        }
    }

    /** Polygon transaction signature. Empty unless the bytes are a transaction
     *  envelope and the caller arrives inside an approval window. */
    fun signTransaction(unsigned: ByteArray, privateKeyHex: String, approved: Boolean): String {
        if (privateKeyHex.isEmpty()) return ""
        if (!approved) return ""
        return try {
            if (!Rlp.isTransactionEnvelope(unsigned)) return ""
            EthereumSigner.toHex(
                EthereumSigner.signDigest(SealedSenderCrypto.keccak256(unsigned), privateKeyHex)
            )
        } catch (e: Exception) {
            ""
        }
    }
}
