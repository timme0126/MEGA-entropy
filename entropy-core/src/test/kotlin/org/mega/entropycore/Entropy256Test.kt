package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*
import java.math.BigInteger

class Entropy256Test {

    @Test
    fun `deriveEntropy256(ZERO) hex is 64 zeros`() {
        val result = deriveEntropy256(BigInteger.ZERO)
        assertEquals("All-zero entropy must produce 64 hex zeros", "0".repeat(64), result.hex)
    }

    @Test
    fun `deriveEntropy256(ONE) hex is 63 zeros followed by 1`() {
        val result = deriveEntropy256(BigInteger.ONE)
        assertEquals("Smallest nonzero entropy must left-pad correctly", "0".repeat(63) + "1", result.hex)
    }

    @Test
    fun `deriveEntropy256 returns 32 bytes for various x values`() {
        val testValues = listOf(
            BigInteger.ZERO,
            BigInteger.ONE,
            TWO_POW_256.subtract(BigInteger.ONE),
            BigInteger.valueOf(123456789)
        )
        for (x in testValues) {
            val result = deriveEntropy256(x)
            assertEquals("Bytes size must be 32 for x=$x", 32, result.bytes.size)
        }
    }

    @Test
    fun `deriveEntropy256 modulo 2^256 property holds across blocks`() {
        val x1 = BigInteger.valueOf(999)
        val x2 = x1.add(TWO_POW_256)
        val x3 = x1.add(TWO_POW_256.multiply(BigInteger.valueOf(2)))

        val h1 = deriveEntropy256(x1).hex
        val h2 = deriveEntropy256(x2).hex
        val h3 = deriveEntropy256(x3).hex

        assertEquals("E = X mod 2^256 must ignore block index", h1, h2)
        assertEquals("E = X mod 2^256 must ignore block index", h1, h3)
    }

    @Test
    fun `deriveEntropy256(max 256-bit) hex is 64 f's`() {
        val max256 = TWO_POW_256.subtract(BigInteger.ONE)
        val result = deriveEntropy256(max256)
        assertEquals("Max 256-bit value must produce 64 hex f's", "f".repeat(64), result.hex)
    }

    @Test
    fun `bigIntegerToUnsignedBytes(ZERO, 32) is 32 zero bytes`() {
        val bytes = bigIntegerToUnsignedBytes(BigInteger.ZERO, 32)
        assertEquals("Length must be 32", 32, bytes.size)
        for (b in bytes) {
            assertEquals("Each byte must be 0", 0.toByte(), b)
        }
    }

    @Test
    fun `bigIntegerToUnsignedBytes throws for negative value`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            bigIntegerToUnsignedBytes(BigInteger.valueOf(-1), 32)
        }
    }

    @Test
    fun `bigIntegerToUnsignedBytes throws when value exceeds requested length`() {
        // 256 requires 2 bytes unsigned, requesting 1 should throw
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            bigIntegerToUnsignedBytes(BigInteger.valueOf(256), 1)
        }
    }
}
