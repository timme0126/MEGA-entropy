package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class EntropyBackupSharesTest {

    private val random = SecureRandom()

    private fun testRandomBytes(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    private fun randomEntropy(byteLength: Int): MnemonicEntropy {
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        return MnemonicEntropy(bytes)
    }

    @Test
    fun `12-word (16-byte) entropy round trips through backup shares`() {
        val entropy = randomEntropy(16)
        val shares = entropyToBackupShares(entropy, threshold = 2, totalShares = 3) { testRandomBytes() }
        val recovered = backupSharesToEntropy(shares.take(2), expectedByteLength = 16)
        // MnemonicEntropy is a data class wrapping a ByteArray - Kotlin's
        // generated equals() does not do content equality on array
        // properties, so compare via .hex (content-based by construction)
        // rather than assertEquals(entropy, recovered) directly.
        assertEquals(entropy.hex, recovered.hex)
    }

    @Test
    fun `24-word (32-byte) entropy round trips through backup shares`() {
        val entropy = randomEntropy(32)
        val shares = entropyToBackupShares(entropy, threshold = 3, totalShares = 5) { testRandomBytes() }
        val recovered = backupSharesToEntropy(shares.takeLast(3), expectedByteLength = 32)
        assertEquals(entropy.hex, recovered.hex)
    }

    @Test
    fun `recovered entropy feeds directly into deriveMnemonicFromEntropy same as the original`() {
        val entropy = randomEntropy(32)
        val shares = entropyToBackupShares(entropy, threshold = 2, totalShares = 3) { testRandomBytes() }
        val recovered = backupSharesToEntropy(shares.take(2), expectedByteLength = 32)

        val originalWords = deriveMnemonicFromEntropy(entropy.bytes)
        val recoveredWords = deriveMnemonicFromEntropy(recovered.bytes)
        assertEquals(originalWords, recoveredWords)
    }

    @Test
    fun `wrong expected byte length is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            backupSharesToEntropy(emptyList(), expectedByteLength = 24)
        }
    }

    @Test
    fun `asking to reconstruct 32-byte-split shares at 16-byte length fails loudly rather than truncating`() {
        // A fixed byte pattern with a nonzero high byte, rather than
        // SecureRandom output, so this deterministically exceeds 2^128 and
        // therefore cannot fit in 16 bytes - no flakiness from the
        // astronomically rare random draw that would happen to fit.
        val entropy = MnemonicEntropy(ByteArray(32) { i -> if (i == 0) 0x7F else 0x11 })
        val shares = entropyToBackupShares(entropy, threshold = 2, totalShares = 3) { testRandomBytes() }
        // The recovered value is the same secp256k1 scalar the 32-byte
        // secret was; asking to re-encode it as 16 bytes must fail rather
        // than silently return a truncated/wrong value.
        assertThrows(IllegalArgumentException::class.java) {
            backupSharesToEntropy(shares.take(2), expectedByteLength = 16)
        }
    }
}
