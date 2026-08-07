package org.mega.entropycore

import java.math.BigInteger

/**
 * Interprets exactly 100 base-6 digits as a single unsigned positional integer.
 * The first digit is the most significant (coefficient of 6^99), the last is least significant.
 * This function exists solely to provide a deterministic reference implementation
 * that must match the output of the incremental batch accumulator.
 * Uses Horner's method for efficient, numerically stable evaluation.
 */
fun calculateXDirect(all100Base6Digits: List<Int>): BigInteger {
    require(all100Base6Digits.size == 100) { "calculateXDirect requires exactly 100 digits, got: ${all100Base6Digits.size}" }
    require(all100Base6Digits.all { it in 0..5 }) { "All digits must be in 0..5, got: $all100Base6Digits" }

    // X = d1*6^99 + d2*6^98 + ... + d100*6^0
    // Horner's method: X = (...((d1 * 6 + d2) * 6 + d3) * 6 + ... ) * 6 + d100
    return all100Base6Digits.fold(BigInteger.ZERO) { acc, digit ->
        acc.multiply(BigInteger.valueOf(6)).add(BigInteger.valueOf(digit.toLong()))
    }
}
