package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hand-checked end-to-end sanity pass, ahead of the full deterministic
 * test suite. Not exhaustive on its own — see the rest of this package for
 * the boundary/vector-based tests required by the project spec.
 */
class SmokeTest {

    @Test
    fun `all rolls of one is the minimum X and must be accepted`() {
        val allOnes = List(100) { 1 }
        val result = deriveMnemonic(allOnes)
        assertTrue(result is MnemonicResult.Success)
        result as MnemonicResult.Success
        // X = 0 -> E = 0 mod 2^256 = 0 -> 32 zero bytes -> 64 hex zero chars.
        assertEquals("0".repeat(64), result.entropy.hex)
        assertEquals(24, result.words.size)
    }

    @Test
    fun `all rolls of six is the maximum X and must be rejected`() {
        val allSixes = List(100) { 6 }
        val result = deriveMnemonic(allSixes)
        assertTrue(result is MnemonicResult.Rejected)
    }

    @Test
    fun `batch accumulation matches direct base6 interpretation`() {
        val rolls = (1..100).map { ((it - 1) % 6) + 1 }
        val digits = mapRollsToBase6(rolls)
        val direct = calculateXDirect(digits)
        val chunks = digits.chunked(5).map { calculateChunk(it) }
        val accumulated = accumulateAllBatches(chunks)
        assertEquals(direct, accumulated)
    }

    @Test
    fun `spec worked example batch chunk equals 2049`() {
        val chunk = calculateChunk(mapRollsToBase6(listOf(2, 4, 3, 6, 4)))
        assertEquals(2049L, chunk)
    }

    @Test
    fun `word list loads exactly 2048 verified entries`() {
        val words = loadOfficialEnglishWordList()
        assertEquals(2048, words.size)
        assertEquals(2048, words.toSet().size)
        assertEquals("abandon", words[0])
        assertEquals("zoo", words[2047])
    }
}
