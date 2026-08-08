package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*

class Bip39VectorsTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    @Test
    fun `official BIP39 vector - all zero entropy`() {
        val entropyBytes = hexToBytes("0000000000000000000000000000000000000000000000000000000000000000")
        val checksum = calculateChecksum(entropyBytes)
        val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
        val indices = splitInto11BitGroups(bitStream)
        val wordList = loadOfficialEnglishWordList()
        val words = deriveWords(indices, wordList)
        assertEquals("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art", words.joinToString(" "))
    }

    @Test
    fun `official BIP39 vector - all 0x7f entropy`() {
        val entropyBytes = hexToBytes("7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f7f")
        val checksum = calculateChecksum(entropyBytes)
        val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
        val indices = splitInto11BitGroups(bitStream)
        val wordList = loadOfficialEnglishWordList()
        val words = deriveWords(indices, wordList)
        assertEquals("legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth useful legal winner thank year wave sausage worth title", words.joinToString(" "))
    }

    @Test
    fun `official BIP39 vector - all 0x80 entropy`() {
        val entropyBytes = hexToBytes("8080808080808080808080808080808080808080808080808080808080808080")
        val checksum = calculateChecksum(entropyBytes)
        val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
        val indices = splitInto11BitGroups(bitStream)
        val wordList = loadOfficialEnglishWordList()
        val words = deriveWords(indices, wordList)
        assertEquals("letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic bless", words.joinToString(" "))
    }

    @Test
    fun `official BIP39 vector - all one-bits entropy`() {
        val entropyBytes = hexToBytes("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
        val checksum = calculateChecksum(entropyBytes)
        val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
        val indices = splitInto11BitGroups(bitStream)
        val wordList = loadOfficialEnglishWordList()
        val words = deriveWords(indices, wordList)
        assertEquals("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote", words.joinToString(" "))
    }

    @Test
    fun `official BIP39 vector - mixed entropy 1`() {
        val entropyBytes = hexToBytes("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c")
        val checksum = calculateChecksum(entropyBytes)
        val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
        val indices = splitInto11BitGroups(bitStream)
        val wordList = loadOfficialEnglishWordList()
        val words = deriveWords(indices, wordList)
        assertEquals("hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length", words.joinToString(" "))
    }

    @Test
    fun `official BIP39 vector - mixed entropy 2`() {
        val entropyBytes = hexToBytes("9f6a2878b2520799a44ef18bc7df394e7061a224d2c33cd015b157d746869863")
        val checksum = calculateChecksum(entropyBytes)
        val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
        val indices = splitInto11BitGroups(bitStream)
        val wordList = loadOfficialEnglishWordList()
        val words = deriveWords(indices, wordList)
        assertEquals("panda eyebrow bullet gorilla call smoke muffin taste mesh discover soft ostrich alcohol speed nation flash devote level hobby quick inner drive ghost inside", words.joinToString(" "))
    }

    @Test
    fun `checksum bits for all-zero entropy match known SHA-256 first byte`() {
        // SHA-256(32 zero bytes) starts with 0x66 = 0110 0110
        // MSB-first bits: false, true, true, false, false, true, true, false
        val entropyBytes = hexToBytes("0000000000000000000000000000000000000000000000000000000000000000")
        val checksum = calculateChecksum(entropyBytes)
        val expectedBits = booleanArrayOf(false, true, true, false, false, true, true, false)
        assertArrayEquals(expectedBits, checksum.checksumBits)
    }
}
