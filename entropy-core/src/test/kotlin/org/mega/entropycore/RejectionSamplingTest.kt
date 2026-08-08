package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*
import java.math.BigInteger

class RejectionSamplingTest {

    @Test
    fun `REJECTION_THRESHOLD_T equals 5 times 2^256`() {
        assertEquals(
            "Threshold T must be exactly 5 * 2^256 per spec",
            BigInteger.valueOf(5).multiply(TWO_POW_256),
            REJECTION_THRESHOLD_T
        )
    }

    @Test
    fun `SIX_POW_100 equals 6^100 computed independently`() {
        val expectedSixPow100 = BigInteger.valueOf(6).pow(100)
        assertEquals(
            "SIX_POW_100 constant must match independent pow(100) computation",
            expectedSixPow100,
            SIX_POW_100
        )
    }

    @Test
    fun `checkAcceptance of zero returns Accepted`() {
        val result = checkAcceptance(BigInteger.ZERO)
        assertTrue("Zero must be accepted", result is RejectionResult.Accepted)
    }

    @Test
    fun `checkAcceptance of zero has x field equal to zero`() {
        val result = checkAcceptance(BigInteger.ZERO)
        assertTrue("Result must be Accepted to inspect x", result is RejectionResult.Accepted)
        assertEquals("Accepted x must be zero", BigInteger.ZERO, result.x)
    }

    @Test
    fun `checkAcceptance(6^100 - 1) returns Rejected`() {
        val maxPossibleX = SIX_POW_100.subtract(BigInteger.ONE)
        val result = checkAcceptance(maxPossibleX)
        assertTrue("Maximum possible X must be rejected", result is RejectionResult.Rejected)
    }

    @Test
    fun `checkAcceptance(T - 1) is Accepted`() {
        val result = checkAcceptance(REJECTION_THRESHOLD_T.subtract(BigInteger.ONE))
        assertTrue("T - 1 must be accepted", result is RejectionResult.Accepted)
    }

    @Test
    fun `checkAcceptance(T) is Rejected`() {
        val result = checkAcceptance(REJECTION_THRESHOLD_T)
        assertTrue("T must be rejected", result is RejectionResult.Rejected)
    }

    @Test
    fun `checkAcceptance(T + 1) is Rejected`() {
        val result = checkAcceptance(REJECTION_THRESHOLD_T.add(BigInteger.ONE))
        assertTrue("T + 1 must be rejected", result is RejectionResult.Rejected)
    }

    @Test
    fun `checkAcceptance throws IllegalArgumentException for negative BigInteger`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            checkAcceptance(BigInteger.valueOf(-1))
        }
    }

    @Test
    fun `all five 2^256 block starts and ends below T are Accepted`() {
        for (k in 0..4) {
            val kBig = BigInteger.valueOf(k.toLong())
            val blockStart = TWO_POW_256.multiply(kBig)
            val blockEnd = TWO_POW_256.multiply(kBig).add(TWO_POW_256).subtract(BigInteger.ONE)

            val startResult = checkAcceptance(blockStart)
            assertTrue("Block $k start ($blockStart) must be accepted", startResult is RejectionResult.Accepted)

            val endResult = checkAcceptance(blockEnd)
            assertTrue("Block $k end ($blockEnd) must be accepted", endResult is RejectionResult.Accepted)
        }
    }
}
