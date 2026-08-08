package org.mega.entropy.security.pin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Minimum recommended iteration count for PBKDF2WithHmacSHA256.
 * Chosen to balance security (resistance to GPU/ASIC brute-force) with
 * acceptable UI latency on mid-range Android devices.
 */
const val DEFAULT_PBKDF2_ITERATIONS = 120_000

/**
 * Generates a cryptographically secure 16-byte random salt.
 *
 * This salt is stored in plaintext alongside the hash because the hash itself
 * is the protected artifact; the salt only prevents rainbow table attacks and
 * does not need to be secret. SecureRandom is used here for local PIN state
 * and is completely isolated from the wallet entropy generation pipeline.
 *
 * Uses nextBytes(), not generateSeed(): generateSeed() is meant for seeding
 * other PRNGs and on Android can pull from a slow true-entropy source,
 * sometimes blocking noticeably. nextBytes() is the correct, fast API for
 * generating random output bytes directly.
 */
fun generateSalt(): ByteArray {
    val salt = ByteArray(16)
    SecureRandom().nextBytes(salt)
    return salt
}

/**
 * Derives a 256-bit hash from the PIN using PBKDF2WithHmacSHA256.
 *
 * The PIN is converted to a CharArray because String objects are immutable
 * and may linger in memory until garbage collected. A CharArray can be
 * explicitly zeroed out after use to minimize the window of exposure.
 *
 * PBEKeySpec stores its OWN clone of the char array passed to its
 * constructor, and PBEKeySpec.getPassword() returns yet another copy each
 * time it's called — so filling either pinChars or spec.password would
 * leave PBEKeySpec's internal clone untouched. spec.clearPassword() is the
 * correct API: it zeroes that internal copy directly.
 */
fun hashPin(pin: String, salt: ByteArray, iterations: Int): ByteArray {
    val pinChars = pin.toCharArray()
    val spec = PBEKeySpec(pinChars, salt, iterations, 256)
    try {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    } finally {
        Arrays.fill(pinChars, '0')
        spec.clearPassword()
    }
}

/**
 * Performs a constant-time comparison of two byte arrays.
 *
 * We MUST use MessageDigest.isEqual() instead of ByteArray.contentEquals
 * or == because contentEquals/== short-circuits on the first differing byte.
 * Short-circuiting leaks timing information that can be exploited for
 * timing side-channel attacks to guess the hash byte-by-byte.
 * isEqual() always processes all bytes, making the execution time independent
 * of the number of matching bytes, thereby neutralizing timing attacks.
 */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    return MessageDigest.isEqual(a, b)
}
