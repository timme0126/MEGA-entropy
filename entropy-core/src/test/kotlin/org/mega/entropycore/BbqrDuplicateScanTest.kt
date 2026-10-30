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

    @Test
    fun midPayloadConflictIsRefusedNotJustLeadingChar() {
        // The first-character flip above could theoretically ride a
        // padding-bit quirk; a mid-payload flip cannot. Both must be
        // hard errors, and only a decode-IDENTICAL duplicate may
        // collapse (tested by everyFrameScannedTwice… above).
        val parts = scanParts()
        val original = parts[1]
        val mid = original.payload.length / 2
        val flippedChar = if (original.payload[mid] == 'A') 'B' else 'A'
        val tampered = original.copy(
            payload = original.payload.substring(0, mid) + flippedChar +
                original.payload.substring(mid + 1),
        )
        assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrParts(parts + tampered)
        }
    }

    @Test
    fun reSplitAtIllegalMidByteBoundaryIsRefusedNotSilentlyCorrupted() {
        // A frame series whose stream was re-partitioned at a
        // non-multiple-of-8 Base32 boundary (152/152 → 156/148): every
        // header stays consistent and every part's length remainder is
        // individually legal, but the decoded bytes silently lose a
        // whole frame boundary's worth of alignment. The 2026-10-30
        // audit showed a decoder without the per-part boundary rule
        // would reassemble corrupt bytes from exactly such a sequence.
        // Must now be a named error, never a success.
        val parts = scanParts()
        val total = parts.size
        assertTrue("needs >= 2 parts", total >= 2)
        val stream = parts.joinToString("") { it.payload }
        val illegalFirst = 156 // 156 % 8 == 4 — not a whole-byte chunk
        val resplit = listOf(
            BbqrPart('2', 'U', total = 2, index = 0, payload = stream.substring(0, illegalFirst)),
            BbqrPart('2', 'U', total = 2, index = 1, payload = stream.substring(illegalFirst)),
        )
        val error = assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrParts(resplit)
        }
        assertTrue(
            "must name the illegal boundary, was: ${error.message}",
            error.message!!.contains("mid-byte"),
        )
    }

    @Test
    fun outOfRangeIndexFailsAtConstruction() {
        // parseBbqrPart declines such frames by returning null; the
        // constructor itself must never allow them in from any other
        // caller, or an out-of-range part silently vanishes from
        // assembly instead of being refused.
        assertThrows(IllegalArgumentException::class.java) {
            BbqrPart('2', 'U', total = 2, index = 7, payload = "AAAAAAA")
        }
    }

    @Test
    fun topLevelDescriptorFieldWinsOverNestedDecoy() {
        // The 'J' path used to regex-match the FIRST "descriptor":"..."
        // anywhere — a decoy nested earlier in the document outranked
        // the real top-level field, silently deciding which spend
        // policy a multisig import believes.
        //
        // Assembled from parts, never a raw literal: Kotlin raw strings
        // treat a run of 3+ quotes as terminating at the LAST three, so
        // hand-quoted JSON with escaped inner quotes silently LOSES a
        // brace at a segment boundary — the exact bug this test's first
        // version rode, and the trap any future "realistic" JSON
        // literal on a signer device deserves to spring on someone at
        // 2am.
        val q = "\""
        val e = "\\\"" // a backslash + a quote — an escaped quote inside JSON
        val json = "{" +
            q + "meta" + q + ":{" +
            q + "note" + q + ":" + q + "deprecated — see " +
            e + "descriptor" + e + ":" + e + "pkh(garbage)" + e + q +
            "}," +
            q + "descriptor" + q + ":" + q + "wsh(multi(2,xpubA,xpubB))" + q +
            "}"
        val parts = encodeBbqr('J', json.toByteArray(Charsets.UTF_8)).map { parseBbqrPart(it)!! }
        assertEquals("wsh(multi(2,xpubA,xpubB))", assembleBbqrParts(parts))
    }

    @Test
    fun duplicateTopLevelDescriptorFieldsAreRefused() {
        // "first wins" would let a later-appearing second top-level
        // field be silently ignored; "regex-first-match" let anything
        // nested win. An ambiguous document names nothing — it errors.
        val q = "\""
        val json = "{" +
            q + "descriptor" + q + ":" + q + "wsh(multi(1,xpubA))" + q + "," +
            q + "descriptor" + q + ":" + q + "wsh(multi(2,xpubA,xpubB))" + q +
            "}"
        val parts = encodeBbqr('J', json.toByteArray(Charsets.UTF_8)).map { parseBbqrPart(it)!! }
        val error = assertThrows(IllegalArgumentException::class.java) {
            assembleBbqrParts(parts)
        }
        assertTrue(
            "must name the ambiguity, was: ${error.message}",
            error.message!!.contains("more than one"),
        )
    }

    @Test
    fun nestedDescriptorWithoutTopLevelFallsBackToRawText() {
        // No top-level field at all: the extractor returns null and the
        // 'J' path hands back the raw decoded text unchanged — the
        // documented best-effort contract for a bare descriptor that is
        // not actually JSON-wrapped.
        val q = "\""
        val e = "\\\""
        val json = "{" +
            q + "meta" + q + ":{" +
            q + "note" + q + ":" + q + "see " + e + "descriptor" + e + ":" + e + "wsh(multi(2,xpubA,xpubB))" + e + q +
            "}" +
            "}"
        val parts = encodeBbqr('J', json.toByteArray(Charsets.UTF_8)).map { parseBbqrPart(it)!! }
        assertEquals(json, assembleBbqrParts(parts))
    }
}
