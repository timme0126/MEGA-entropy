package org.mega.entropy.bbqr

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mega.entropycore.BbqrPart

/**
 * Covers accumulateBbqrPart — the shared scanner-accumulation rules:
 * duplicate frames never corrupt the payload, and two different series
 * claiming the same index are caught as conflicts instead of mixing bytes.
 */
class BbqrAccumulationTest {

    private fun part(index: Int, payload: String, total: Int = 3, encoding: Char = '2', fileType: Char = 'P') =
        BbqrPart(encoding = encoding, fileType = fileType, total = total, index = index, payload = payload)

    @Test
    fun `frames accumulate by index in any order`() {
        var parts = emptyMap<Int, BbqrPart>()
        parts = accumulateBbqrPart(parts, part(1, "BBB")).parts
        parts = accumulateBbqrPart(parts, part(0, "AAA")).parts
        parts = accumulateBbqrPart(parts, part(2, "CCC")).parts
        assertEquals(3, parts.size)
        assertEquals("AAA", parts[0]?.payload)
    }

    @Test
    fun `an identical duplicate frame is ignored without changing state`() {
        var parts = emptyMap<Int, BbqrPart>()
        parts = accumulateBbqrPart(parts, part(0, "AAA")).parts
        val result = accumulateBbqrPart(parts, part(0, "AAA"))
        assertEquals(BbqrAccumulateStatus.DuplicateSamePart, result.status)
        assertEquals(parts, result.parts)
    }

    @Test
    fun `a conflicting payload for an already-scanned index keeps the original and reports the conflict`() {
        var parts = emptyMap<Int, BbqrPart>()
        parts = accumulateBbqrPart(parts, part(0, "AAA")).parts
        val result = accumulateBbqrPart(parts, part(0, "ZZZ"))
        assertEquals(BbqrAccumulateStatus.ConflictingPart, result.status)
        assertEquals("AAA", result.parts[0]?.payload)
    }

    @Test
    fun `a frame from a different series (different total) starts a fresh accumulation`() {
        var parts = emptyMap<Int, BbqrPart>()
        parts = accumulateBbqrPart(parts, part(0, "AAA", total = 3)).parts
        val result = accumulateBbqrPart(parts, part(5, "QQQ", total = 9))
        assertEquals(BbqrAccumulateStatus.Added, result.status)
        assertEquals(1, result.parts.size)
        assertEquals("QQQ", result.parts[5]?.payload)
    }

    @Test
    fun `a frame from a different series (different file type) starts a fresh accumulation`() {
        var parts = emptyMap<Int, BbqrPart>()
        parts = accumulateBbqrPart(parts, part(0, "AAA", fileType = 'P')).parts
        val result = accumulateBbqrPart(parts, part(1, "BBB", fileType = 'J'))
        assertEquals(1, result.parts.size)
        assertEquals("BBB", result.parts[1]?.payload)
    }
}
