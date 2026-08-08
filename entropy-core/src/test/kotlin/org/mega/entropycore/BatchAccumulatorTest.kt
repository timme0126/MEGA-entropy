package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*
import java.math.BigInteger

class BatchAccumulatorTest {
    @Test
    fun `calculateChunk works on exact spec example listOf(1,3,2,5,3)`() {
        // 1*1296 + 3*216 + 2*36 + 5*6 + 3 = 1296 + 648 + 72 + 30 + 3 = 2049
        assertEquals(2049L, calculateChunk(listOf(1, 3, 2, 5, 3)))
    }

    @Test
    fun `calculateChunk handles all-zeros and all-fives batches`() {
        assertEquals(0L, calculateChunk(listOf(0, 0, 0, 0, 0)))
        assertEquals(7775L, calculateChunk(listOf(5, 5, 5, 5, 5))) // 6^5 - 1 = 7775
    }

    @Test
    fun `calculateChunk throws for wrong size or out-of-range digits`() {
        assertThrows(IllegalArgumentException::class.java) { calculateChunk(listOf(1, 2, 3, 4)) }
        assertThrows(IllegalArgumentException::class.java) { calculateChunk(listOf(1, 2, 3, 4, 5, 6)) }
        assertThrows(IllegalArgumentException::class.java) { calculateChunk(listOf(-1, 0, 0, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { calculateChunk(listOf(0, 0, 0, 0, 6)) }
    }

    @Test
    fun `accumulate handles first batch step from ZERO`() {
        assertEquals(BigInteger.valueOf(2049), accumulate(BigInteger.ZERO, 2049L))
    }

    @Test
    fun `accumulate handles second batch step`() {
        // 2049 * 7776 + 100 = 15933124
        assertEquals(BigInteger.valueOf(15933124), accumulate(BigInteger.valueOf(2049), 100L))
    }

    @Test
    fun `accumulate throws for invalid chunk or previousX`() {
        assertThrows(IllegalArgumentException::class.java) { accumulate(BigInteger.ZERO, -1L) }
        assertThrows(IllegalArgumentException::class.java) { accumulate(BigInteger.ZERO, 7776L) }
        assertThrows(IllegalArgumentException::class.java) { accumulate(BigInteger.valueOf(-1), 0L) }
    }

    @Test
    fun `accumulateAllBatches throws if list is not exactly 20 chunks`() {
        assertThrows(IllegalArgumentException::class.java) { accumulateAllBatches(List(19) { 0L }) }
        assertThrows(IllegalArgumentException::class.java) { accumulateAllBatches(List(21) { 0L }) }
    }

    @Test
    fun `accumulateAllBatches computes exact precomputed value for chunks 1 to 20`() {
        val chunks = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L)
        val expected = BigInteger("84038926253228173667482569740528863225100311082601661787929034819560753460")
        assertEquals(expected, accumulateAllBatches(chunks))
    }
}
