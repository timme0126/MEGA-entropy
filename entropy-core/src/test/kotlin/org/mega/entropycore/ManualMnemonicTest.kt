package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*

class ManualMnemonicTest {

    @Test
    fun `accepts the official 12-word all-zero-entropy test vector`() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split(" ")
        val result = validateManualMnemonic(words)
        val valid = result as? ManualMnemonicValidation.Valid
        assertNotNull("expected Valid, got $result", valid)
        assertEquals(16, valid!!.entropy.bytes.size)
        assertTrue(valid.entropy.bytes.all { it == 0.toByte() })
    }

    @Test
    fun `accepts the official 24-word all-zero-entropy test vector`() {
        val words = ("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art")
            .split(" ")
        val result = validateManualMnemonic(words)
        val valid = result as? ManualMnemonicValidation.Valid
        assertNotNull("expected Valid, got $result", valid)
        assertEquals(32, valid!!.entropy.bytes.size)
        assertTrue(valid.entropy.bytes.all { it == 0.toByte() })
    }

    @Test
    fun `accepts the official all-one-bits 24-word test vector`() {
        val words = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote"
            .split(" ")
        val result = validateManualMnemonic(words)
        val valid = result as? ManualMnemonicValidation.Valid
        assertNotNull("expected Valid, got $result", valid)
        assertTrue(valid!!.entropy.bytes.all { it == 0xFF.toByte() })
    }

    @Test
    fun `is tolerant of surrounding whitespace and capitalization`() {
        val words = " Abandon  abandon ABANDON abandon abandon abandon abandon abandon abandon abandon abandon ABOUT "
            .trim().split(Regex("\\s+"))
        val result = validateManualMnemonic(words)
        assertTrue(result is ManualMnemonicValidation.Valid)
    }

    @Test
    fun `rejects a word count other than 12 or 24`() {
        val words = List(15) { "abandon" }
        val result = validateManualMnemonic(words) as ManualMnemonicValidation.Invalid
        assertTrue(result.reason.contains("12 or 24"))
    }

    @Test
    fun `rejects a word not in the BIP39 English list`() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon notaword"
            .split(" ")
        val result = validateManualMnemonic(words) as ManualMnemonicValidation.Invalid
        assertTrue(result.reason.contains("notaword"))
    }

    @Test
    fun `rejects a valid word list with the wrong checksum word`() {
        // Same 11 words as the all-zero 12-word vector, but the final
        // (checksum) word is swapped to another valid BIP39 word that does
        // NOT satisfy the checksum for that entropy.
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon zoo"
            .split(" ")
        val result = validateManualMnemonic(words) as ManualMnemonicValidation.Invalid
        assertTrue(result.reason.contains("Checksum"))
    }

    @Test
    fun `rejects a blank word`() {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon  "
            .trim().split(" ") + ""
        val result = validateManualMnemonic(words) as ManualMnemonicValidation.Invalid
        assertTrue(result.reason.contains("blank"))
    }

    @Test
    fun `accepts four-letter unique prefixes in place of full words`() {
        // "aban" and "abou" are each a unique four-letter prefix in the
        // official BIP39 English word list (verified: zero four-letter
        // prefix collisions across all 2048 words) — the same vector as
        // the full-word 12-word test, just abbreviated.
        val words = List(11) { "aban" } + "abou"
        val result = validateManualMnemonic(words)
        val valid = result as? ManualMnemonicValidation.Valid
        assertNotNull("expected Valid, got $result", valid)
        assertEquals(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            valid!!.words.joinToString(" "),
        )
    }

    @Test
    fun `accepts a mix of full words and unique prefixes`() {
        val words = List(11) { "abandon" } + "abou"
        val result = validateManualMnemonic(words)
        assertTrue(result is ManualMnemonicValidation.Valid)
    }

    @Test
    fun `rejects an ambiguous prefix that matches more than one word`() {
        // "ab" matches many BIP39 words (abandon, ability, able, about, ...).
        val words = List(11) { "aban" } + "ab"
        val result = validateManualMnemonic(words) as ManualMnemonicValidation.Invalid
        assertTrue(result.reason.contains("\"ab\""))
    }

    @Test
    fun `bip39WordsStartingWith returns every match for an ambiguous prefix and one for a unique prefix`() {
        assertEquals(listOf("about"), bip39WordsStartingWith("abou"))
        assertTrue(bip39WordsStartingWith("ab").size > 1)
        assertTrue(bip39WordsStartingWith("ab").contains("about"))
        assertTrue(bip39WordsStartingWith("ab").contains("abandon"))
        assertEquals(emptyList<String>(), bip39WordsStartingWith(""))
        assertEquals(emptyList<String>(), bip39WordsStartingWith("zzzz"))
    }
}
