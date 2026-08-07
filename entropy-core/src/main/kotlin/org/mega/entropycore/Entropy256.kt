package org.mega.entropycore

import java.math.BigInteger

/**
 * Derives exactly 32 bytes of unsigned big-endian entropy from an accepted base-6 integer.
 *
 * @param x The accepted accumulated integer (guaranteed < 2^256 after rejection sampling).
 * @return Entropy256 wrapping the 32-byte array.
 */
fun deriveEntropy256(x: BigInteger): Entropy256 {
    // E = x mod 2^256. Since x < T = 5 * 2^256, E is simply x % 2^256.
    val e = x.mod(TWO_POW_256)
    val bytes = bigIntegerToUnsignedBytes(e, 32)
    return Entropy256(bytes)
}

/**
 * Converts a BigInteger to a fixed-length unsigned big-endian byte array.
 *
 * WHY this helper is necessary:
 * BigInteger.toByteArray() returns a two's-complement representation that is
 * minimally sized. It may prepend a 0x00 sign byte for positive numbers that
 * have the high bit set, and it will be shorter than 32 bytes for small values.
 * BIP39 requires exactly 32 bytes, unsigned, big-endian, with leading zeros
 * preserved. This helper strips the optional sign byte and left-pads to the
 * exact requested length.
 *
 * @param value The non-negative BigInteger to convert.
 * @param lengthBytes The exact number of bytes required in the output.
 * @return A ByteArray of exactly lengthBytes.
 * @throws IllegalArgumentException if value is negative or cannot fit in lengthBytes.
 */
fun bigIntegerToUnsignedBytes(value: BigInteger, lengthBytes: Int): ByteArray {
    if (value.signum() < 0) {
        throw IllegalArgumentException("Value must be non-negative, got: $value")
    }

    // Max value that fits in lengthBytes unsigned is 2^(8*lengthBytes) - 1
    val maxUnsigned = BigInteger.ONE.shiftLeft(8 * lengthBytes).subtract(BigInteger.ONE)
    if (value.compareTo(maxUnsigned) > 0) {
        throw IllegalArgumentException("Value $value does not fit in $lengthBytes bytes unsigned.")
    }

    // Get two's-complement minimal representation
    var rawBytes = value.toByteArray()

    // Strip leading 0x00 sign byte if present (happens when high bit is 1)
    if (rawBytes.size > 1 && rawBytes[0] == 0.toByte()) {
        rawBytes = rawBytes.copyOfRange(1, rawBytes.size)
    }

    // Left-pad with 0x00 if shorter than requested length
    if (rawBytes.size < lengthBytes) {
        val padded = ByteArray(lengthBytes)
        rawBytes.copyInto(padded, destinationOffset = lengthBytes - rawBytes.size)
        rawBytes = padded
    }

    return rawBytes
}
