package com.pombo.android.core

import org.json.JSONArray

/**
 * Fills the bridge page's placeholders. The identity key goes in and does not
 * come out: the page is given the account address and the compressed public
 * key, and asks [SigningOracle] for every signature.
 */
object BridgePage {

    fun render(template: String, privateKeyHex: String, rpcUrls: List<String>): String =
        template
            .replace("__BRIDGE_ADDR__", address(privateKeyHex))
            .replace("__BRIDGE_PUB__", publicKey(privateKeyHex))
            .replace("__BRIDGE_RPCS__", JSONArray(rpcUrls).toString())

    private fun address(privateKeyHex: String): String =
        if (privateKeyHex.isEmpty()) ""
        else EthereumSigner.checksumAddress(EthereumSigner.address(privateKeyHex))

    private fun publicKey(privateKeyHex: String): String =
        if (privateKeyHex.isEmpty()) "" else EthereumSigner.compressedPublicKey(privateKeyHex)
}
