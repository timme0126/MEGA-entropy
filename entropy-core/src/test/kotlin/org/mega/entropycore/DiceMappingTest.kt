package org.mega.entropycore

import org.junit.Test
import org.junit.Assert.*

class DiceMappingTest {
    @Test
    fun `mapRollToBase6 maps 1 through 6 to 0 through 5 explicitly`() {
        assertEquals(0, mapRollToBase6(1))
        assertEquals(1, mapRollToBase6(2))
        assertEquals(2, mapRollToBase6(3))
        assertEquals(3, mapRollToBase6(4))
        assertEquals(4, mapRollToBase6(5))
        assertEquals(5, mapRollToBase6(6))
    }

    @Test
    fun `mapRollToBase6 throws IllegalArgumentException for invalid rolls 0, 7, -1, 100`() {
        assertThrows(IllegalArgumentException::class.java) { mapRollToBase6(0) }
        assertThrows(IllegalArgumentException::class.java) { mapRollToBase6(7) }
        assertThrows(IllegalArgumentException::class.java) { mapRollToBase6(-1) }
        assertThrows(IllegalArgumentException::class.java) { mapRollToBase6(100) }
    }

    @Test
    fun `mapRollsToBase6 works on exact spec example listOf(2,4,3,6,4)`() {
        assertEquals(listOf(1, 3, 2, 5, 3), mapRollsToBase6(listOf(2, 4, 3, 6, 4)))
    }

    @Test
    fun `mapRollsToBase6 throws if ANY element in the list is invalid`() {
        assertThrows(IllegalArgumentException::class.java) { mapRollsToBase6(listOf(1, 2, 3, 4, 7)) }
    }
}
