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
}
