package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected values were cross-checked against an independent from-scratch
 * Python implementation of the BIP173 reference pseudocode (not a bech32
 * library — just the algorithm re-typed independently), including a
 * round-trip decode of the well-known official BIP173 example address
 * to recover its exact witness program, rather than trusting a
 * from-memory hex string directly (see Bip32Test.kt's own KDoc for why:
 * a previous draft of that file had exactly this kind of memory slip).
 */
class Bech32Test {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    @Test
    fun `matches the official BIP173 example address on mainnet`() {
        val program = hexToBytes("751e76e8199196d454941c45d1b3a323f1433bd6")
        assertEquals(
            "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
            encodeSegwitV0Address("bc", program),
        )
    }

    @Test
    fun `same program on testnet uses the tb prefix and a different checksum`() {
        val program = hexToBytes("751e76e8199196d454941c45d1b3a323f1433bd6")
        assertEquals(
            "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx",
            encodeSegwitV0Address("tb", program),
        )
    }

    @Test
    fun `all-zero witness program`() {
        assertEquals(
            "bc1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq9e75rs",
            encodeSegwitV0Address("bc", ByteArray(20)),
        )
    }

    @Test
    fun `all-ff witness program`() {
        assertEquals(
            "bc1qllllllllllllllllllllllllllllllllfglmy6",
            encodeSegwitV0Address("bc", ByteArray(20) { 0xFF.toByte() }),
        )
    }

    @Test
    fun `sequential-byte witness program`() {
        assertEquals(
            "bc1qqqqsyqcyq5rqwzqfpg9scrgwpugpzysn4v0345",
            encodeSegwitV0Address("bc", ByteArray(20) { it.toByte() }),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a program that is not exactly 20 bytes`() {
        encodeSegwitV0Address("bc", ByteArray(21))
    }
}
