package org.mega.entropycore

import java.math.BigInteger

/**
 * Calculates the integer value of a 5-roll batch interpreted as base-6.
 * Requires exactly 5 digits each in 0..5.
 * Returns a value in 0..7775 (since 6^5 = 7776).
 * Uses explicit coefficients to avoid overflow and improve readability.
 */
fun calculateChunk(fiveBase6Digits: List<Int>): Long {
    require(fiveBase6Digits.size == 5) { "calculateChunk requires exactly 5 digits, got: ${fiveBase6Digits.size}" }
    require(fiveBase6Digits.all { it in 0..5 }) { "All digits must be in 0..5, got: $fiveBase6Digits" }

    val a = fiveBase6Digits[0]
    val b = fiveBase6Digits[1]
    val c = fiveBase6Digits[2]
    val d = fiveBase6Digits[3]
    val e = fiveBase6Digits[4]

    // chunk = a*6^4 + b*6^3 + c*6^2 + d*6 + e
    return (a * 1296L) + (b * 216L) + (c * 36L) + (d * 6L) + e
}

/**
 * Advances the running accumulator by one batch.
 * Multiplies the previous accumulated value by 7776 (6^5) and adds the new chunk.
 * Validates that the chunk is within the valid base-6 range and previousX is non-negative.
 * BigInteger is used because the accumulator grows beyond 64-bit precision after ~13 batches.
 */
fun accumulate(previousX: BigInteger, chunk: Long): BigInteger {
    require(chunk in 0..7775) { "Chunk must be in 0..7775, got: $chunk" }
    require(previousX.signum() >= 0) { "previousX must be non-negative, got: $previousX" }

    return previousX.multiply(BigInteger.valueOf(7776)).add(BigInteger.valueOf(chunk))
}

/**
 * Folds the accumulation process over a sequence of batch chunks: 20 chunks
 * for the 100-roll/24-word case, 10 chunks for the 50-roll/12-word case (see
 * MnemonicLength). Starts from X0 = 0 and applies accumulate sequentially.
 * This path is mathematically equivalent to interpreting all of the base-6
 * digits as a single positional integer, but allows incremental UI updates
 * without recomputing the entire sequence from scratch.
 */
fun accumulateAllBatches(chunksInOrder: List<Long>): BigInteger {
    require(chunksInOrder.size == 10 || chunksInOrder.size == 20) {
        "accumulateAllBatches requires exactly 10 or 20 chunks, got: ${chunksInOrder.size}"
    }
    return chunksInOrder.fold(BigInteger.ZERO) { acc, chunk -> accumulate(acc, chunk) }
}
