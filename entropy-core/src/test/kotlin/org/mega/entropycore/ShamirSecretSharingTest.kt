package org.mega.entropycore

import java.math.BigInteger
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ShamirSecretSharingTest {

    private val random = SecureRandom()

    private fun testRandomBytes(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    private fun randomSecret(byteLength: Int): ByteArray {
        val bytes = ByteArray(byteLength)
        random.nextBytes(bytes)
        return bytes
    }

    @Test
    fun `round trips across a matrix of threshold and total-share combinations, both entropy lengths`() {
        val combinations = listOf(2 to 3, 3 to 5, 2 to 2, 15 to 15, 5 to 15)
        val byteLengths = listOf(16, 32)
        for ((threshold, totalShares) in combinations) {
            for (byteLength in byteLengths) {
                val secret = randomSecret(byteLength)
                val shares = splitSecret(secret, threshold, totalShares) { testRandomBytes() }
                assertEquals(totalShares, shares.size)
                val recovered = reconstructSecret(shares.take(threshold), byteLength)
                assertEquals(
                    "threshold=$threshold totalShares=$totalShares byteLength=$byteLength",
                    secret.toList(),
                    recovered.toList(),
                )
            }
        }
    }

    @Test
    fun `reconstruction succeeds with any qualifying subset, not just the first ones generated`() {
        val secret = randomSecret(32)
        val shares = splitSecret(secret, threshold = 3, totalShares = 6) { testRandomBytes() }

        val lastThree = shares.takeLast(3)
        assertEquals(secret.toList(), reconstructSecret(lastThree, 32).toList())

        val scattered = listOf(shares[1], shares[3], shares[5])
        assertEquals(secret.toList(), reconstructSecret(scattered, 32).toList())
    }

    @Test
    fun `hand-constructed 2-of-3 polynomial reconstructs the exact known secret from any pair`() {
        // f(x) = secret + a1 * x (mod N) - independently evaluated here,
        // not via splitSecret's random coefficients, so this checks
        // reconstructSecret's Lagrange interpolation against a dataset
        // this test built itself rather than one splitSecret produced.
        val n = Secp256k1.N
        val secret = BigInteger.valueOf(123456789L)
        val a1 = BigInteger.valueOf(987654321L)

        fun evaluate(x: Long) = secret.add(a1.multiply(BigInteger.valueOf(x))).mod(n)

        val tag = computeIntegrityTag(secret)
        val share1 = BackupShare(x = 1, y = evaluate(1), threshold = 2, integrityTag = tag)
        val share2 = BackupShare(x = 2, y = evaluate(2), threshold = 2, integrityTag = tag)
        val share3 = BackupShare(x = 3, y = evaluate(3), threshold = 2, integrityTag = tag)

        val expectedBytes = bigIntegerToUnsignedBytes(secret, 32)
        assertEquals(expectedBytes.toList(), reconstructSecret(listOf(share1, share2), 32).toList())
        assertEquals(expectedBytes.toList(), reconstructSecret(listOf(share1, share3), 32).toList())
        assertEquals(expectedBytes.toList(), reconstructSecret(listOf(share2, share3), 32).toList())
    }

    @Test
    fun `threshold of 1 is rejected at split time`() {
        assertThrows(IllegalArgumentException::class.java) {
            splitSecret(randomSecret(32), threshold = 1, totalShares = 3) { testRandomBytes() }
        }
    }

    @Test
    fun `fewer than threshold shares fails loudly rather than reconstructing`() {
        val shares = splitSecret(randomSecret(32), threshold = 3, totalShares = 5) { testRandomBytes() }
        assertThrows(IllegalArgumentException::class.java) {
            reconstructSecret(shares.take(2), 32)
        }
    }

    @Test
    fun `shares with mismatched integrity tags are rejected as mixed-up shares`() {
        val secretA = randomSecret(32)
        val secretB = randomSecret(32)
        val sharesA = splitSecret(secretA, threshold = 2, totalShares = 3) { testRandomBytes() }
        val sharesB = splitSecret(secretB, threshold = 2, totalShares = 3) { testRandomBytes() }

        assertThrows(IllegalArgumentException::class.java) {
            reconstructSecret(listOf(sharesA[0], sharesB[1]), 32)
        }
    }

    @Test
    fun `shares with mismatched thresholds are rejected`() {
        val secret = randomSecret(32)
        val sharesLowThreshold = splitSecret(secret, threshold = 2, totalShares = 3) { testRandomBytes() }
        val sharesHighThreshold = splitSecret(secret, threshold = 3, totalShares = 3) { testRandomBytes() }

        assertThrows(IllegalArgumentException::class.java) {
            reconstructSecret(listOf(sharesLowThreshold[0], sharesHighThreshold[0]), 32)
        }
    }

    @Test
    fun `duplicate share (same index) is rejected`() {
        val shares = splitSecret(randomSecret(32), threshold = 2, totalShares = 3) { testRandomBytes() }
        assertThrows(IllegalArgumentException::class.java) {
            reconstructSecret(listOf(shares[0], shares[0]), 32)
        }
    }

    @Test
    fun `a single flipped bit in one share's y value fails the integrity check rather than silently reconstructing a wrong secret`() {
        val secret = randomSecret(32)
        val shares = splitSecret(secret, threshold = 2, totalShares = 3) { testRandomBytes() }
        val corrupted = shares[0].copy(y = shares[0].y.xor(BigInteger.ONE))

        assertThrows(IllegalArgumentException::class.java) {
            reconstructSecret(listOf(corrupted, shares[1]), 32)
        }
    }

    @Test
    fun `total shares below threshold is rejected at split time`() {
        assertThrows(IllegalArgumentException::class.java) {
            splitSecret(randomSecret(32), threshold = 5, totalShares = 3) { testRandomBytes() }
        }
    }

    @Test
    fun `share text encoding round trips exactly`() {
        val shares = splitSecret(randomSecret(32), threshold = 2, totalShares = 4) { testRandomBytes() }
        for (share in shares) {
            val encoded = encodeBackupShare(share)
            val decoded = decodeBackupShare(encoded)
            assertEquals(share, decoded)
        }
    }

    @Test
    fun `share text decoding rejects malformed strings`() {
        val shares = splitSecret(randomSecret(32), threshold = 2, totalShares = 3) { testRandomBytes() }
        val validEncoded = encodeBackupShare(shares[0])

        // Wrong field count.
        assertThrows(IllegalArgumentException::class.java) {
            decodeBackupShare(validEncoded.substringBeforeLast("."))
        }
        // Non-numeric index.
        assertThrows(IllegalArgumentException::class.java) {
            decodeBackupShare("x.${validEncoded.substringAfter(".")}")
        }
        // Out-of-range index.
        assertThrows(IllegalArgumentException::class.java) {
            decodeBackupShare("0.${validEncoded.substringAfter(".")}")
        }
        // Non-base58 y value (0 and O are excluded from the alphabet).
        val fields = validEncoded.split(".").toMutableList()
        fields[1] = "0OIl"
        assertThrows(IllegalArgumentException::class.java) {
            decodeBackupShare(fields.joinToString("."))
        }
        // Malformed integrity tag (wrong length/uppercase).
        val fields2 = validEncoded.split(".").toMutableList()
        fields2[3] = "NOTHEX12"
        assertThrows(IllegalArgumentException::class.java) {
            decodeBackupShare(fields2.joinToString("."))
        }
    }

    @Test
    fun `different splits of the same secret produce different shares (random coefficients, not deterministic)`() {
        val secret = randomSecret(32)
        val sharesFirst = splitSecret(secret, threshold = 2, totalShares = 2) { testRandomBytes() }
        val sharesSecond = splitSecret(secret, threshold = 2, totalShares = 2) { testRandomBytes() }
        assertNotEquals(sharesFirst[0].y, sharesSecond[0].y)
        // But both still reconstruct to the same secret and carry the same
        // integrity tag, since that's derived only from the secret itself.
        assertEquals(sharesFirst[0].integrityTag, sharesSecond[0].integrityTag)
        assertEquals(secret.toList(), reconstructSecret(sharesFirst, 32).toList())
        assertEquals(secret.toList(), reconstructSecret(sharesSecond, 32).toList())
    }
}
