package org.mega.entropycore

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the 128-bit / 12-word mnemonic path (MnemonicLength.TWELVE_WORDS,
 * 50 dice rolls), added alongside the original 256-bit / 24-word path.
 * Mirrors the structure of the 24-word tests elsewhere in this package —
 * see docs/ENTROPY-MATH.md for why 50 rolls (T = 2 * 2^128, ~15.8%
 * rejection) was chosen the same way the original 100-roll design was.
 */
class TwelveWordMnemonicTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    @Test
    fun `rejection threshold for 50 rolls 128 bits is exactly 2 times 2^128`() {
        val expected = BigInteger.valueOf(2).multiply(twoPow(128))
        assertEquals(expected, rejectionThreshold(50, 128))
    }

    @Test
    fun `all rolls of one is the minimum X and must be accepted for 12 words`() {
        val rolls = List(50) { 1 }
        val result = deriveMnemonic(rolls, MnemonicLength.TWELVE_WORDS, loadOfficialEnglishWordList())
        assertTrue(result is MnemonicResult.Success)
        result as MnemonicResult.Success
        assertEquals("0".repeat(32), result.entropy.hex)
        assertEquals(12, result.words.size)
        // Official BIP39 all-zero 128-bit entropy vector (trezor/python-mnemonic
        // vectors.json), independently sourced, not derived from this implementation.
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            result.words.joinToString(" "),
        )
    }

    @Test
    fun `all rolls of six is the maximum X and must be rejected for 12 words`() {
        val rolls = List(50) { 6 }
        val result = deriveMnemonic(rolls, MnemonicLength.TWELVE_WORDS, loadOfficialEnglishWordList())
        assertTrue(result is MnemonicResult.Rejected)
    }

    @Test
    fun `rejection boundary T-1, T, T+1 for 128-bit threshold`() {
        val t = rejectionThreshold(50, 128)
        assertTrue(checkAcceptance(t.subtract(BigInteger.ONE), 50, 128) is RejectionResult.Accepted)
        assertTrue(checkAcceptance(t, 50, 128) is RejectionResult.Rejected)
        assertTrue(checkAcceptance(t.add(BigInteger.ONE), 50, 128) is RejectionResult.Rejected)
    }

    @Test
    fun `deriveMnemonic requires exactly 50 rolls for TWELVE_WORDS`() {
        assertThrows(IllegalArgumentException::class.java) {
            deriveMnemonic(List(49) { 1 }, MnemonicLength.TWELVE_WORDS, loadOfficialEnglishWordList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            deriveMnemonic(List(51) { 1 }, MnemonicLength.TWELVE_WORDS, loadOfficialEnglishWordList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            deriveMnemonic(List(100) { 1 }, MnemonicLength.TWELVE_WORDS, loadOfficialEnglishWordList())
        }
    }

    @Test
    fun `official BIP39 128-bit vector - all 0x7f entropy`() {
        val entropyBytes = hexToBytes("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f")
        val checksum = calculateChecksum(entropyBytes)
        val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
        val indices = splitInto11BitGroups(bitStream)
        val words = deriveWords(indices, loadOfficialEnglishWordList())
        assertEquals(
            "legal winner thank year wave sausage worth useful legal winner thank yellow",
            words.joinToString(" "),
        )
    }

    @Test
    fun `official BIP39 128-bit vector - all 0xff entropy`() {
        val entropyBytes = hexToBytes("ffffffffffffffffffffffffffffffff")
        val checksum = calculateChecksum(entropyBytes)
        val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
        val indices = splitInto11BitGroups(bitStream)
        val words = deriveWords(indices, loadOfficialEnglishWordList())
        assertEquals(
            "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            words.joinToString(" "),
        )
    }

    @Test
    fun `official BIP39 128-bit vector - mixed entropy`() {
        val entropyBytes = hexToBytes("9e885d952ad362caeb4efe34a8e91bd2")
        val checksum = calculateChecksum(entropyBytes)
        val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
        val indices = splitInto11BitGroups(bitStream)
        val words = deriveWords(indices, loadOfficialEnglishWordList())
        assertEquals(
            "ozone drill grab fiber curtain grace pudding thank cruise elder eight picnic",
            words.joinToString(" "),
        )
    }

    @Test
    fun `calculateChecksum produces 4 bits for 16-byte entropy`() {
        val checksum = calculateChecksum(ByteArray(16))
        assertEquals(4, checksum.checksumBits.size)
        assertEquals(32, checksum.digest.size)
    }

    @Test
    fun `buildBitStream produces 132 bits for 16-byte entropy plus 4 checksum bits`() {
        val stream = buildBitStream(ByteArray(16), BooleanArray(4))
        assertEquals(132, stream.size)
    }

    @Test
    fun `splitInto11BitGroups produces 12 groups for a 132-bit stream`() {
        val groups = splitInto11BitGroups(BooleanArray(132))
        assertEquals(12, groups.size)
    }

    @Test
    fun `deriveEntropyBits returns 16 bytes for 128-bit entropy`() {
        val entropy = deriveEntropyBits(BigInteger.ZERO, 128)
        assertEquals(16, entropy.bytes.size)
        assertEquals("0".repeat(32), entropy.hex)
    }
}
