package org.mega.entropycore

import java.math.BigInteger

/**
 * Interprets a sequence of base-6 digits as a single unsigned positional integer.
 * The first digit is the most significant, the last is least significant.
 * This function exists solely to provide a deterministic reference implementation
 * that must match the output of the incremental batch accumulator.
 * Uses Horner's method for efficient, numerically stable evaluation.
 *
 * Not hard-coded to any particular digit count: MEGA uses this for both its
 * 100-digit (256-bit) and 50-digit (128-bit) mnemonic lengths — see
 * MnemonicLength. The caller (MnemonicPipeline) is responsible for
 * requiring the exact digit count for whichever length is in use.
 */
fun calculateXDirect(base6Digits: List<Int>): BigInteger {
    require(base6Digits.isNotEmpty()) { "calculateXDirect requires at least one digit" }
    require(base6Digits.all { it in 0..5 }) { "All digits must be in 0..5, got: $base6Digits" }

    // X = d1*6^(n-1) + d2*6^(n-2) + ... + dn*6^0
    // Horner's method: X = (...((d1 * 6 + d2) * 6 + d3) * 6 + ... ) * 6 + dn
    return base6Digits.fold(BigInteger.ZERO) { acc, digit ->
        acc.multiply(BigInteger.valueOf(6)).add(BigInteger.valueOf(digit.toLong()))
    }
}
