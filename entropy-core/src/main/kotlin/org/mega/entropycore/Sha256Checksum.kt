package org.mega.entropycore

import java.security.MessageDigest

/**
 * Computes the SHA-256 hash of the given data.
 *
 * WHY this is used:
 * SHA-256 is a deterministic cryptographic hash function specified by BIP39
 * for generating the checksum. It is NOT a randomness source; it deterministically
 * maps the 256-bit entropy to a 256-bit digest, from which we extract the
 * first 8 bits for the checksum.
 *
 * @param data The input bytes to hash.
 * @return The 32-byte SHA-256 digest.
 */
fun sha256(data: ByteArray): ByteArray {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data)
}

/**
 * Calculates the BIP39 checksum for 32 bytes of entropy.
 *
 * @param entropy32Bytes Exactly 32 bytes of entropy.
 * @return ChecksumResult containing the full SHA-256 digest and the 8 checksum bits.
 * @throws IllegalArgumentException if entropy32Bytes.size != 32.
 */
fun calculateChecksum(entropy32Bytes: ByteArray): ChecksumResult {
    if (entropy32Bytes.size != 32) {
        throw IllegalArgumentException("Entropy must be exactly 32 bytes, got: ${entropy32Bytes.size}")
    }

    val digest = sha256(entropy32Bytes)

    // Extract the first 8 bits from the first byte of the digest.
    // Bit order: MSB of the first digest byte is checksum bit 0.
    val firstByte = digest[0].toUByte().toInt()
    val checksumBits = BooleanArray(8)
    for (i in 0 until 8) {
        // Check bit at position (7 - i) to get MSB-first order
        checksumBits[i] = (firstByte and (1 shl (7 - i))) != 0
    }

    return ChecksumResult(digest, checksumBits)
}
