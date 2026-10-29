package org.mega.entropycore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rescan behaviour of the BBQr assembler: the same frame scanned twice
 * (identical payload) is idempotent, but an index scanned a second time
 * with a DIFFERENT payload used to be silently decided by "last scan
 * wins" — a corrupted or tampered re-scan could overwrite a good frame
 * of a PSBT mid-flight. Now a hard, named error.
 */
class BbqrDuplicateScanTest {

    /** Long enough that a 'U'-file BBQr splits into multiple parts, so
     * duplicate-index logic is actually exercised against a sequence. */
    private val sampleText = (1..40).joinToString("-") { "frame$it" }

    private fun scanParts(): List<BbqrPart> =
        encodeBbqr('U', sampleText.toByteArray(Charsets.UTF_8)).map { parseBbqrPart(it)!! }

    @Test
    fun sampleReallySplitsIntoMultipleParts() {
        assertTrue("test needs >1 part to be meaningful", scanParts().size > 1)
    }

    @Test
    fun everyFrameScannedTwiceStillAssemblesExactlyOnce() {
        val parts = scanParts()
        assertEquals(sampleText, assembleBbqrParts(parts + parts))
    }

    @Test
    fun oneFrameScannedTwiceIdenticallyStillAssembles() {
        val parts = scanParts()
        val withOneDuplicate = parts + parts[1]
        assertEquals(sampleText, assembleBbqrParts(withOneDuplicate))
    }

    @Test
    fun conflictingReScanOfOneFrameIsRefusedNotSilentlyAccepted() {
        val parts = scanParts()
        val original = parts[1]
        val flippedChar = if (original.payload.first() == 'A') 'B' else 'A'
        val tampered = original.copy(
            payload = flippedChar + original.payload.substring(1),
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrParts(parts + tampered)
        }
        assertTrue(
            "error must name the conflicting index so the user knows to re-scan, was: ${error.message}",
            error.message!!.contains("repeat index 1"),
        )
    }

    @Test
    fun conflictIsCaughtEvenWhenEveryOtherPartIsFine() {
        // The tampered frame is the LAST one scanned — the exact
        // "last scan wins" ordering that the old associateBy() silently
        // accepted with the tampered bytes winning.
        val parts = scanParts()
        val last = parts.last()
        val flippedChar = if (last.payload.first() == 'A') 'B' else 'A'
        assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrParts(parts + last.copy(payload = flippedChar + last.payload.substring(1)))
        }
    }

    @Test
    fun byteLevelAssembleAlsoRefusesConflictingDuplicates() {
        val data = ByteArray(200) { (it * 7).toByte() }
        val parts = encodeBbqr('P', data).map { parseBbqrPart(it)!! }
        val first = parts.first()
        val flippedChar = if (first.payload.first() == 'A') 'B' else 'A'
        assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrPartsAsBytes(parts + first.copy(payload = flippedChar + first.payload.substring(1)))
        }
        // ...and identical duplicates assemble byte-exactly.
        assertEquals(
            data.toList(),
            assembleBbqrPartsAsBytes(parts + parts).toList(),
        )
    }
}
