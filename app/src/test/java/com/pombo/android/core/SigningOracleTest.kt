package com.pombo.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The oracle's refusals. A renderer that is fully compromised still cannot
 * reach the key except through these two endpoints, so what they refuse is
 * the boundary itself: opaque digests, payloads that are not transactions,
 * and transactions arriving outside an approval window.
 */
class SigningOracleTest {

    private val pk = "0x8f2a559490d8e9bb4e0e7b53e1c6e4c2b1a0d9c8b7a6958473625140fedcba98"
    private val transaction = SealedSenderCrypto.hexToBytes(
        "0x02ef8189078506fc23ac00850df847580083033450941563915e194d8cfba1943570603f7606a31155088084a9059cbbc0"
    )

    @Test
    fun `message endpoint applies the EIP-191 prefix itself`() {
        val payload = "olá pombo".toByteArray(Charsets.UTF_8)
        assertEquals(
            EthereumSigner.toHex(EthereumSigner.signMessage(payload, pk)),
            SigningOracle.signMessage(payload, pk)
        )
    }

    @Test
    fun `message endpoint cannot be turned into a digest oracle`() {
        // A 32-byte transaction hash fed to the message endpoint: the prefix
        // is applied to it, so what comes back does not sign that transaction.
        val digest = SealedSenderCrypto.keccak256(transaction)
        assertEquals(32, digest.size)
        assertNotEquals(
            EthereumSigner.toHex(EthereumSigner.signDigest(digest, pk)),
            SigningOracle.signMessage(digest, pk)
        )
    }

    @Test
    fun `message endpoint cannot sign a transaction envelope either`() {
        assertNotEquals(
            EthereumSigner.toHex(
                EthereumSigner.signDigest(SealedSenderCrypto.keccak256(transaction), pk)
            ),
            SigningOracle.signMessage(transaction, pk)
        )
    }

    @Test
    fun `transaction endpoint signs an envelope inside an approval window`() {
        assertTrue(Rlp.isTransactionEnvelope(transaction))
        assertEquals(
            EthereumSigner.toHex(
                EthereumSigner.signDigest(SealedSenderCrypto.keccak256(transaction), pk)
            ),
            SigningOracle.signTransaction(transaction, pk, approved = true)
        )
    }

    @Test
    fun `transaction endpoint refuses outside an approval window`() {
        assertEquals("", SigningOracle.signTransaction(transaction, pk, approved = false))
    }

    @Test
    fun `transaction endpoint refuses an opaque digest`() {
        val digest = SealedSenderCrypto.keccak256(transaction)
        assertEquals("", SigningOracle.signTransaction(digest, pk, approved = true))
    }

    @Test
    fun `transaction endpoint refuses payloads that are not transactions`() {
        for (payload in listOf(
            "olá pombo".toByteArray(Charsets.UTF_8),
            ByteArray(0),
            ByteArray(64) { 0x11 },
            SealedSenderCrypto.hexToBytes("0x03c0"),
        )) {
            assertEquals("", SigningOracle.signTransaction(payload, pk, approved = true))
        }
    }

    @Test
    fun `neither endpoint signs without an identity`() {
        assertEquals("", SigningOracle.signMessage("x".toByteArray(Charsets.UTF_8), ""))
        assertEquals("", SigningOracle.signTransaction(transaction, "", approved = true))
    }
}
