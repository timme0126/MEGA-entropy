package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*
import java.math.BigInteger

class DirectBase6Test {
    @Test
    fun `calculateXDirect throws if list is not exactly 100 digits`() {
        assertThrows(IllegalArgumentException::class.java) { calculateXDirect(List(99) { 0 }) }
        assertThrows(IllegalArgumentException::class.java) { calculateXDirect(List(101) { 0 }) }
    }

    @Test
    fun `calculateXDirect throws if any digit is outside 0 to 5`() {
        val badDigits = List(100) { 0 }.toMutableList()
        badDigits[50] = 6
        assertThrows(IllegalArgumentException::class.java) { calculateXDirect(badDigits) }
        badDigits[50] = -1
        assertThrows(IllegalArgumentException::class.java) { calculateXDirect(badDigits) }
    }

    @Test
    fun `calculateXDirect handles minimum value (all zeros)`() {
        assertEquals(BigInteger.ZERO, calculateXDirect(List(100) { 0 }))
    }

    @Test
    fun `calculateXDirect handles maximum value (all fives)`() {
        assertEquals(BigInteger.valueOf(6).pow(100).subtract(BigInteger.ONE), calculateXDirect(List(100) { 5 }))
    }

    @Test
    fun `cross-check vector 1 - repeating 0 to 5 pattern`() {
        val digits = List(100) { it % 6 }
        val expected = accumulateAllBatches(digits.chunked(5).map { calculateChunk(it) })
        assertEquals(expected, calculateXDirect(digits))
    }

    @Test
    fun `cross-check vector 2 - repeating 5 to 0 pattern`() {
        val digits = List(100) { 5 - (it % 6) }
        val expected = accumulateAllBatches(digits.chunked(5).map { calculateChunk(it) })
        assertEquals(expected, calculateXDirect(digits))
    }

    @Test
    fun `cross-check vector 3 - blocks of five 0s then five 1s`() {
        val digits = List(100) { if (it % 10 < 5) 0 else 1 }
        val expected = accumulateAllBatches(digits.chunked(5).map { calculateChunk(it) })
        assertEquals(expected, calculateXDirect(digits))
    }

    @Test
    fun `cross-check vector 4 - period 12 pattern 1,2,3,4,5,0 repeated`() {
        val digits = List(100) { listOf(1, 2, 3, 4, 5, 0, 1, 2, 3, 4, 5, 0)[it % 12] }
        val expected = accumulateAllBatches(digits.chunked(5).map { calculateChunk(it) })
        assertEquals(expected, calculateXDirect(digits))
    }

    @Test
    fun `cross-check vector 5 - alternating 0 and 5`() {
        val digits = List(100) { if (it % 2 == 0) 0 else 5 }
        val expected = accumulateAllBatches(digits.chunked(5).map { calculateChunk(it) })
        assertEquals(expected, calculateXDirect(digits))
    }
}
