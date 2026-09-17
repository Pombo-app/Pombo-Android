package com.pombo.android.core

/**
 * The tokens the wallet panel knows about on Polygon PoS, web parity.
 *
 * Addresses, not names: each was confirmed by reading its own `symbol()` on
 * chain, and the panel renders that symbol rather than a label written here,
 * so a contract that renames itself cannot leave a stale name on screen (the
 * Polygon USDT address answers "USDT0" today).
 */
object WalletTokens {

    /** Always listed, even at zero. */
    const val DATA = "0x3a9A81d576d83FF21f26f325066054540720fC34"
    const val USDC = "0x3c499c542cEF5E3811e1192ce70d8cC03d5c3359"

    /** Listed only when the account holds some. */
    val CURATED = listOf(
        "0xc2132D05D31c914a87C6611C10748AEb04B58e8F",
        "0x7ceB23fD6bC0adD59E62ac25578270cFf1b9f619",
        "0x0d500B1d8E8eF31E21C99d1Db9A6444d3ADf1270",
        "0x1BFD67037B42Cf73acF2047067bd4F2C47D9BfD6",
        "0x8f3Cf7ad23Cd3CaDbD9735AFf958023239c6A063"
    )
}
