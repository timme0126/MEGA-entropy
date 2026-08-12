package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*

class EntropyMnemonicTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    @Test
    fun `official BIP39 vector - 12 words, all zero entropy`() {
        val words = deriveMnemonicFromEntropy(hexToBytes("00000000000000000000000000000000"))
        assertEquals("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", words.joinToString(" "))
    }

    @Test
    fun `official BIP39 vector - 12 words, all 0x7f entropy`() {
        val words = deriveMnemonicFromEntropy(hexToBytes("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f"))
        assertEquals("legal winner thank year wave sausage worth useful legal winner thank yellow", words.joinToString(" "))
    }

    @Test
    fun `official BIP39 vector - 12 words, all one-bits entropy`() {
        val words = deriveMnemonicFromEntropy(hexToBytes("ffffffffffffffffffffffffffffffff"))
        assertEquals("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong", words.joinToString(" "))
    }

    @Test
    fun `official BIP39 vector - 24 words, all zero entropy`() {
        val words = deriveMnemonicFromEntropy(hexToBytes("0000000000000000000000000000000000000000000000000000000000000000"))
        assertEquals("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art", words.joinToString(" "))
    }

    @Test
    fun `official BIP39 vector - 24 words, mixed entropy`() {
        val words = deriveMnemonicFromEntropy(hexToBytes("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c"))
        assertEquals("hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length", words.joinToString(" "))
    }

    @Test
    fun `rejects entropy of an unsupported length`() {
        assertThrows(IllegalArgumentException::class.java) {
            deriveMnemonicFromEntropy(ByteArray(20))
        }
    }

    @Test
    fun `round-trips through validateManualMnemonic`() {
        val words = deriveMnemonicFromEntropy(hexToBytes("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c"))
        val validation = validateManualMnemonic(words)
        assertTrue(validation is ManualMnemonicValidation.Valid)
    }
}
