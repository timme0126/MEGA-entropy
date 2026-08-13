package org.mega.entropy.seedqr

import org.junit.Assert.*
import org.junit.Test
import org.mega.entropycore.loadOfficialEnglishWordList

class SeedQrTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    @Test
    fun `Standard SeedQR decodes known 12-word vector`() {
        val expectedWords = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        val wordList = loadOfficialEnglishWordList()
        val digits = expectedWords.joinToString("") { wordList.indexOf(it).toString().padStart(4, '0') }

        val result = decodeSeedQr(digits, byteSegments = null)

        assertTrue(result is SeedQrResult.Success)
        assertEquals(expectedWords, (result as SeedQrResult.Success).words)
    }

    @Test
    fun `Standard SeedQR decodes known 24-word vector`() {
        val expectedWords = ("hamster diagram private dutch cause delay private meat slide toddler razor book " +
            "happy fancy gospel tennis maple dilemma loan word shrug inflict delay length").split(" ")
        val wordList = loadOfficialEnglishWordList()
        val digits = expectedWords.joinToString("") { wordList.indexOf(it).toString().padStart(4, '0') }

        val result = decodeSeedQr(digits, byteSegments = null)

        assertTrue(result is SeedQrResult.Success)
        assertEquals(expectedWords, (result as SeedQrResult.Success).words)
    }

    @Test
    fun `Standard SeedQR rejects an out-of-range word index`() {
        // 2047 is the highest valid index (0-2047); 2048 is one past the
        // end of the 2048-word list.
        val digits = "2048" + "0000".repeat(11)
        val result = decodeSeedQr(digits, byteSegments = null)
        assertTrue(result is SeedQrResult.Failure)
    }

    @Test
    fun `Standard SeedQR rejects a checksum-invalid word sequence`() {
        // Well-formed digits (right length, in-range indices) but not the
        // actual checksum-valid sequence for those words.
        val digits = "0001".repeat(12)
        val result = decodeSeedQr(digits, byteSegments = null)
        assertTrue(result is SeedQrResult.Failure)
    }

    @Test
    fun `Compact SeedQR decodes 16 bytes of entropy to 12 words`() {
        val entropy = hexToBytes("00000000000000000000000000000000")
        val result = decodeSeedQr(text = "", byteSegments = listOf(entropy))
        assertTrue(result is SeedQrResult.Success)
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            (result as SeedQrResult.Success).words.joinToString(" "),
        )
    }

    @Test
    fun `Compact SeedQR decodes 32 bytes of entropy to 24 words`() {
        val entropy = hexToBytes("68a79eaca2324873eacc50cb9c6eca8cc68ea5d936f98787c60c7ebc74e6ce7c")
        val result = decodeSeedQr(text = "", byteSegments = listOf(entropy))
        assertTrue(result is SeedQrResult.Success)
        assertEquals(
            "hamster diagram private dutch cause delay private meat slide toddler razor book happy fancy gospel tennis maple dilemma loan word shrug inflict delay length",
            (result as SeedQrResult.Success).words.joinToString(" "),
        )
    }

    @Test
    fun `byte segments take precedence over coincidentally numeric text`() {
        val entropy = hexToBytes("00000000000000000000000000000000")
        // Deliberately garbage/wrong-length text alongside valid byte
        // segments — the byte segments must win.
        val result = decodeSeedQr(text = "123", byteSegments = listOf(entropy))
        assertTrue(result is SeedQrResult.Success)
    }

    @Test
    fun `unrecognized content is a failure`() {
        val result = decodeSeedQr(text = "not a seedqr", byteSegments = null)
        assertTrue(result is SeedQrResult.Failure)
    }

    @Test
    fun `empty byte segments falls back to text parsing`() {
        val expectedWords = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        val wordList = loadOfficialEnglishWordList()
        val digits = expectedWords.joinToString("") { wordList.indexOf(it).toString().padStart(4, '0') }

        val result = decodeSeedQr(digits, byteSegments = emptyList())

        assertTrue(result is SeedQrResult.Success)
    }
}
