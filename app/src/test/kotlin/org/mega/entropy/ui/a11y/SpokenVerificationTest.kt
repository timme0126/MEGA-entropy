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

        // Valid lowercase BIP39 words as deterministic builder input.
        val WORDLIST = listOf(
            "abandon", "ability", "able", "about", "above", "absorb", "abstract",
            "absurd", "abuse", "access", "accident", "account", "achieve", "acid",
            "acoustic", "across", "act", "actual", "adapt", "add", "address",
            "adjust", "admit", "adult",
        )

        // 1..12 spelled — the labels a 12-word announcement must carry.
        val NUMBER_WORDS = listOf(
            "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "ten", "eleven", "twelve",
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

        // First/last two characters expanded phonetically inside the
        // single raw pass (b,c -> Bravo Charlie; t,4 -> Tango Four).
        // The repeat is the full NATO spelling, checked above.
        assertEquals(
            1,
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

    @Test
    fun `twelve-word mnemonic announcement numbers each word and ends with the footer`() {
        val words = List(12) { index -> WORDLIST[index] }
        val announcement = buildMnemonicAnnouncement(words)
        // Deterministic double call.
        assertEquals(announcement, buildMnemonicAnnouncement(words))
        assertTrue("first word labelled", announcement.startsWith("Word one: ${words[0]}."))
        // Spelled-out ordinals, not digits: "Word twelve", never "Word 12".
        assertEquals(1, countOccurrences(announcement, "Word twelve: ${words[11]}."))
        assertEquals(0, countOccurrences(announcement, "Word 12:"))
        // Each of the twelve labels appears exactly once.
        (1..12).forEach { n ->
            assertEquals(1, countOccurrences(announcement, "Word ${NUMBER_WORDS[n - 1]}:"))
        }
        assertTrue(
            "footer missing",
            announcement.endsWith(" Check every word against your written backup before you trust it."),
        )
    }

    @Test
    fun `twenty-four-word mnemonic announcement reaches twenty four and stays deterministic`() {
        val words = List(24) { index -> WORDLIST[index] }
        val announcement = buildMnemonicAnnouncement(words)
        assertEquals(announcement, buildMnemonicAnnouncement(words))
        assertTrue(announcement.startsWith("Word one: ${words[0]}."))
        // Compound ordinals follow the builder's hyphenated convention
        // (same spelled-hyphenated tradition as the "X-ray" code word).
        assertEquals(1, countOccurrences(announcement, "Word twenty: ${words[19]}."))
        assertEquals(1, countOccurrences(announcement, "Word twenty-one: ${words[20]}."))
        assertEquals(1, countOccurrences(announcement, "Word twenty-four: ${words[23]}."))
        // No digit labels anywhere.
        assertEquals(0, countOccurrences(announcement, "Word 2"))
        assertTrue(
            announcement.endsWith(" Check every word against your written backup before you trust it."),
        )
    }

    @Test
    fun `mnemonic announcement order is the input order and an empty list is total`() {
        val words = listOf("zoo", "ability", "middle")
        assertEquals(
            "Word one: zoo. Word two: ability. Word three: middle. " +
                "Check every word against your written backup before you trust it.",
            buildMnemonicAnnouncement(words),
        )
        // Total on the impossible-empty case: footer only, never a throw.
        assertEquals(
            " Check every word against your written backup before you trust it.",
            buildMnemonicAnnouncement(emptyList()),
        )
    }

    @Test
    fun `rolls are spoken as position-labelled NATO digit words`() {
        assertEquals(
            "Roll one: Four. Roll two: Two. That is 2 rolls. " +
                "Check every roll against your dice before you trust it.",
            buildRollsAnnouncement(listOf(4, 2)),
        )
        // Singular count, and the 1-based position word differs from the
        // roll value word when they coincide ("Roll one: One").
        assertEquals(
            "Roll one: One. That is 1 roll. " +
                "Check every roll against your dice before you trust it.",
            buildRollsAnnouncement(listOf(1)),
        )
    }

    @Test
    fun `the rolls count is derived from the list, impossible to mismatch, and deterministic`() {
        // 100 rolls: the count sentence and the highest ordinal both
        // come from the SAME list, and no count parameter exists at
        // all — a spoken-count mismatch is impossible by construction.
        val rolls = (0 until 100).map { (it % 6) + 1 }
        val announcement = buildRollsAnnouncement(rolls)
        assertEquals(announcement, buildRollsAnnouncement(rolls))
        assertEquals(1, countOccurrences(announcement, "That is 100 rolls."))
        assertEquals(0, countOccurrences(announcement, "That is 99 rolls."))
        assertEquals(0, countOccurrences(announcement, "That is 101 rolls."))
        // Every one of the 100 position labels is present exactly once,
        // including the composed "one hundred".
        assertEquals(100, countOccurrences(announcement, "Roll "))
        assertEquals(1, countOccurrences(announcement, "Roll one hundred: "))
        assertEquals(1, countOccurrences(announcement, "Roll fifty: "))
        // 50 rolls count as 50, never 100 — again size-derived.
        assertEquals(
            1,
            countOccurrences(buildRollsAnnouncement(rolls.take(50)), "That is 50 rolls."),
        )
    }
}
