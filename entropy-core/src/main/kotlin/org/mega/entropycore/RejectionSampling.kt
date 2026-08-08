package org.mega.entropycore

import java.math.BigInteger

/** 6^n, for n dice rolls. */
fun sixPow(rollCount: Int): BigInteger = BigInteger.valueOf(6).pow(rollCount)

/** 2^bits, the size of an `bits`-bit entropy space. */
fun twoPow(bits: Int): BigInteger = BigInteger.ONE.shiftLeft(bits)

/**
 * Rejection threshold T = floor(6^rollCount / 2^entropyBits) * 2^entropyBits.
 * Derived via integer division to guarantee the exact mathematical
 * relationship, rather than hard-coding the resulting multiplier.
 */
fun rejectionThreshold(rollCount: Int, entropyBits: Int): BigInteger {
    val sixN = sixPow(rollCount)
    val twoBits = twoPow(entropyBits)
    return sixN.divide(twoBits).multiply(twoBits)
}

/**
 * 6^100 computed once at class initialization.
 * Represents the total number of possible 100-dice sequences.
 */
val SIX_POW_100: BigInteger = sixPow(100)

/**
 * 2^256 computed once at class initialization.
 * Represents the size of the BIP39 entropy space.
 */
val TWO_POW_256: BigInteger = twoPow(256)

/**
 * Rejection threshold T = floor(6^100 / 2^256) * 2^256.
 * The multiplier floor(6^100 / 2^256) evaluates to exactly 5.
 * T = 5 * 2^256.
 */
val REJECTION_THRESHOLD_T: BigInteger = rejectionThreshold(100, 256)

/**
 * Determines whether the accumulated base-6 integer X (from the original
 * 100-roll / 256-bit design) is accepted or rejected.
 *
 * @param x The accumulated integer from the 100 dice rolls.
 * @return Accepted if x < T, Rejected if x >= T.
 * @throws IllegalArgumentException if x is negative (should never occur from valid input).
 */
fun checkAcceptance(x: BigInteger): RejectionResult = checkAcceptance(x, rollCount = 100, entropyBits = 256)

/**
 * Generalized rejection check covering both mnemonic lengths MEGA supports
 * (see MnemonicLength) — same logic as the 100-roll/256-bit case above,
 * parametrized by roll count and entropy bit width instead of hard-coded.
 *
 * @param x The accumulated integer from `rollCount` dice rolls.
 * @param rollCount Number of dice rolls X was derived from (50 or 100).
 * @param entropyBits Target entropy width in bits (128 or 256).
 * @return Accepted if x < T, Rejected if x >= T.
 * @throws IllegalArgumentException if x is negative (should never occur from valid input).
 */
fun checkAcceptance(x: BigInteger, rollCount: Int, entropyBits: Int): RejectionResult {
    if (x.signum() < 0) {
        throw IllegalArgumentException("Accumulated value x must be non-negative, got: $x")
    }

    val threshold = rejectionThreshold(rollCount, entropyBits)
    val sixN = sixPow(rollCount)
    val twoBits = twoPow(entropyBits)

    return if (x.compareTo(threshold) < 0) {
        RejectionResult.Accepted(x, threshold, sixN, twoBits)
    } else {
        RejectionResult.Rejected(x, threshold, sixN, twoBits)
    }
}
