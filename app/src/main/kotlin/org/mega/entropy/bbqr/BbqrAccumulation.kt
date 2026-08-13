package org.mega.entropy.bbqr

import org.mega.entropycore.BbqrPart

/** Outcome of folding one more scanned frame into an in-progress BBQr series. */
enum class BbqrAccumulateStatus {
    /** New index (or the first frame of a new series) accepted. */
    Added,

    /** Same index AND same payload as an already-scanned frame — a harmless
     * re-read of the same QR, ignored (the map is unchanged). */
    DuplicateSamePart,

    /** Same index but a DIFFERENT payload than an already-scanned frame —
     * the camera is seeing two different QR series (or a corrupted read).
     * The already-accumulated parts are kept and the new frame is rejected,
     * so a mixed series can never silently corrupt the payload — the caller
     * must surface this so the user can hold the camera on ONE series. */
    ConflictingPart,
}

data class BbqrAccumulation(
    val parts: Map<Int, BbqrPart>,
    val status: BbqrAccumulateStatus,
)

/**
 * Folds one scanned BBQr frame into the accumulation so far. Pure and
 * total (never throws), so both QR scanner screens share exactly this one
 * implementation — and so the conflict rules are unit-testable without a
 * camera.
 *
 * Series identity is the (total, encoding, fileType) triple a frame's
 * header carries: a frame whose triple disagrees with the series in
 * progress starts a NEW accumulation (the camera moved to a different
 * export entirely). Two frames of the SAME series claiming the same index
 * with different payloads are the dangerous case — that would mix two
 * series' bytes into one payload — and are reported as [BbqrAccumulateStatus.ConflictingPart]
 * with the existing part kept.
 */
fun accumulateBbqrPart(current: Map<Int, BbqrPart>, part: BbqrPart): BbqrAccumulation {
    val first = current.values.firstOrNull()
    val sameSequence = first == null ||
        (first.total == part.total && first.encoding == part.encoding && first.fileType == part.fileType)
    if (!sameSequence) {
        return BbqrAccumulation(mapOf(part.index to part), BbqrAccumulateStatus.Added)
    }
    val existing = current[part.index]
    if (existing != null) {
        return if (existing.payload == part.payload) {
            BbqrAccumulation(current, BbqrAccumulateStatus.DuplicateSamePart)
        } else {
            BbqrAccumulation(current, BbqrAccumulateStatus.ConflictingPart)
        }
    }
    return BbqrAccumulation(current + (part.index to part), BbqrAccumulateStatus.Added)
}
