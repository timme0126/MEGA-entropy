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
 * Calculates the BIP39 checksum for 16 or 32 bytes of entropy (128-bit
 * 12-word, or 256-bit 24-word mnemonics — see MnemonicLength). Per BIP39,
 * CS = ENT/32, i.e. the checksum bit count is entropyBytes.size/4: 4 bits
 * for 16-byte entropy, 8 bits for 32-byte entropy. The SHA-256 digest
 * itself is always 32 bytes regardless of input length — that's a
 * property of SHA-256, not something this function controls.
 *
 * @param entropyBytes Exactly 16 or 32 bytes of entropy.
 * @return ChecksumResult containing the full SHA-256 digest and the checksum bits.
 * @throws IllegalArgumentException if entropyBytes.size is not 16 or 32.
 */
fun calculateChecksum(entropyBytes: ByteArray): ChecksumResult {
    if (entropyBytes.size != 16 && entropyBytes.size != 32) {
        throw IllegalArgumentException("Entropy must be exactly 16 or 32 bytes, got: ${entropyBytes.size}")
    }

    val digest = sha256(entropyBytes)
    val checksumBitCount = entropyBytes.size / 4 // CS = ENT/32 = (bytes*8)/32 = bytes/4

    // Extract the first checksumBitCount bits from the first byte(s) of the
    // digest. Bit order: MSB of the first digest byte is checksum bit 0.
    // checksumBitCount is always <= 8 for the lengths this function
    // accepts, so all checksum bits come from digest[0].
    val firstByte = digest[0].toUByte().toInt()
    val checksumBits = BooleanArray(checksumBitCount)
    for (i in 0 until checksumBitCount) {
        checksumBits[i] = (firstByte and (1 shl (7 - i))) != 0
    }

    return ChecksumResult(digest, checksumBits)
}
