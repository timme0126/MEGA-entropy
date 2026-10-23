package org.mega.entropy.ui.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 pure-function coverage: NATO mapping completeness (all A–Z, all
 * digits, case-insensitive, total on junk input), deterministic chunking
 * with remainder, the twice-spoken address announcement (identical on
 * repeated invocation, raw chunk sequence once, then a full-phonetic repeat), and the
 * fingerprint pair expansion. Plain JUnit4 — the functions under test
 * use no Android APIs, so no Robolectric.
 */
class SpokenVerificationTest {

    private companion object {
        // BIP173 P2WPKH test vector — 42 chars, so 4-char chunking ends
        // in a remainder of 2.
        const val ADDRESS = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"

        val NATO_ALPHA = listOf(
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel",
            "India", "Juliett", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa",
            "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey",
            "X-ray", "Yankee", "Zulu",
        )
        val NATO_DIGITS = listOf(
            "Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine",
        )

        fun countOccurrences(haystack: String, needle: String): Int {
            var count = 0
            var index = 0
            while (true) {
                index = haystack.indexOf(needle, startIndex = index)
                if (index < 0) return count
                count++
                index += needle.length
            }
        }
    }

    @Test
    fun `every A-Z letter maps to its NATO code word`() {
        ('A'..'Z').forEachIndexed { index, ch ->
            assertEquals(
                "word for $ch",
                NATO_ALPHA[index],
                toNatoPhonetic(ch.toString()),
            )
        }
    }

    @Test
    fun `lowercase input maps to the same words as uppercase`() {
        assertEquals(
            "Alpha Bravo Charlie X-ray Yankee Zulu",
            toNatoPhonetic("abcXYZ"),
        )
    }

    @Test
    fun `every digit maps to its NATO digit word`() {
        ('0'..'9').forEachIndexed { index, ch ->
            assertEquals(
                "word for $ch",
                NATO_DIGITS[index],
                toNatoPhonetic(ch.toString()),
            )
        }
    }

    @Test
    fun `unmappable characters are dropped with a counted note and empty input is total`() {
        assertEquals("Alpha Bravo, 2 characters skipped", toNatoPhonetic("a?b!"))
        assertEquals("1 character skipped", toNatoPhonetic("?"))
        assertEquals("", toNatoPhonetic(""))
        // The three spelled separators stay, unmapped junk is counted.
        assertEquals(
            "Alpha dash Bravo dot Charlie space Delta, 1 character skipped",
            toNatoPhonetic("a-b.c d!"),
        )
    }

    @Test
    fun `twelve characters chunk evenly into groups of four`() {
        assertEquals(
            listOf("abcd", "efgh", "ijkl"),
            chunkForSpeech("abcdefghijkl"),
        )
    }

    @Test
    fun `fourteen characters leave a two-character remainder as its own group`() {
        assertEquals(
            listOf("abcd", "efgh", "ijkl", "mn"),
            chunkForSpeech("abcdefghijklmn"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            chunkForSpeech("abc", groupSize = 0)
        }
    }

    @Test
    fun `42-char address chunks as ten fours plus a remainder of two`() {
        val chunks = chunkForSpeech(ADDRESS)
        assertEquals(11, chunks.size)
        assertEquals(4, chunks.take(10).map { it.length }.distinct().single())
        assertEquals(2, chunks.last().length)
    }

    @Test
    fun `address announcement is deterministic and repeats the sequence phonetically`() {
        val first = buildAddressAnnouncement(ADDRESS)
        val second = buildAddressAnnouncement(ADDRESS)
        assertEquals(first, second)

        val chunkSequence = chunkForSpeech(ADDRESS).joinToString(", ")
        assertTrue("chunk sequence missing", first.contains(chunkSequence))
        assertEquals(1, countOccurrences(first, chunkSequence))
        assertTrue(first.contains("Once more, phonetically."))
        // The repeat carries NATO words for the FIRST and LAST chunks.
        val firstChunkWords = toNatoPhonetic(chunkForSpeech(ADDRESS).first())
        val lastChunkWords = toNatoPhonetic(chunkForSpeech(ADDRESS).last())
        assertTrue("first chunk not spelled", first.contains(firstChunkWords))
        assertTrue("last chunk not spelled", first.contains(lastChunkWords))

        // First/last two characters expanded phonetically:
        // b,c -> Bravo Charlie; t,4 -> Tango Four.
        assertEquals(
            2,
            countOccurrences(first, "Starts with Bravo Charlie, ends with Tango Four."),
        )
    }

    @Test
    fun `fingerprint is read as four NATO pairs twice`() {
        assertEquals(
            "Fingerprint in pairs of characters. " +
                "Alpha Foxtrot, Zero Echo, Three Four, Bravo Two. " +
                "Once more. " +
                "Alpha Foxtrot, Zero Echo, Three Four, Bravo Two. " +
                "Check every pair against the screen before you trust it.",
            buildFingerprintAnnouncement("af0e34b2"),
        )
        // Case of the hex input is irrelevant — no locale-dependent casing
        // anywhere in the pipeline.
        assertEquals(
            buildFingerprintAnnouncement("af0e34b2"),
            buildFingerprintAnnouncement("AF0E34B2"),
        )
    }
}
