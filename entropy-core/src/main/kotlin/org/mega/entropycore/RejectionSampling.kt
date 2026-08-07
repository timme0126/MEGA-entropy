package org.mega.entropycore

import java.math.BigInteger

/**
 * 6^100 computed once at class initialization.
 * Represents the total number of possible 100-dice sequences.
 */
val SIX_POW_100: BigInteger = BigInteger.valueOf(6).pow(100)

/**
 * 2^256 computed once at class initialization.
 * Represents the size of the BIP39 entropy space.
 */
val TWO_POW_256: BigInteger = BigInteger.ONE.shiftLeft(256)

/**
 * Rejection threshold T = floor(6^100 / 2^256) * 2^256.
 * Derived via integer division to guarantee exact mathematical relationship.
 * The multiplier floor(6^100 / 2^256) evaluates to exactly 5.
 * T = 5 * 2^256.
 */
val REJECTION_THRESHOLD_T: BigInteger = SIX_POW_100.divide(TWO_POW_256).multiply(TWO_POW_256)

/**
 * Determines whether the accumulated base-6 integer X is accepted or rejected.
 *
 * @param x The accumulated integer from the 100 dice rolls.
 * @return Accepted if x < T, Rejected if x >= T.
 * @throws IllegalArgumentException if x is negative (should never occur from valid input).
 */
fun checkAcceptance(x: BigInteger): RejectionResult {
    if (x.signum() < 0) {
        throw IllegalArgumentException("Accumulated value x must be non-negative, got: $x")
    }

    val comparison = x.compareTo(REJECTION_THRESHOLD_T)
    return if (comparison < 0) {
        RejectionResult.Accepted(x, REJECTION_THRESHOLD_T, SIX_POW_100, TWO_POW_256)
    } else {
        RejectionResult.Rejected(x, REJECTION_THRESHOLD_T, SIX_POW_100, TWO_POW_256)
    }
}
