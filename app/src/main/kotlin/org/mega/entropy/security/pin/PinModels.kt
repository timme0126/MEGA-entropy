package org.mega.entropy.security.pin

/**
 * Represents a stored PIN credential.
 *
 * The salt and hash are stored in plaintext on disk because they do not
 * contain the raw PIN or mnemonic. The salt prevents rainbow table attacks,
 * and the hash is the only artifact that could theoretically be brute-forced.
 * Since the hash is derived via PBKDF2 with high iteration counts, storing
 * it unencrypted is consistent with how non-sensitive metadata is handled
 * elsewhere in this app, and avoids the complexity of managing separate
 * encryption keys for PIN state.
 */
data class PinRecord(
    val salt: ByteArray,
    val hash: ByteArray,
    val iterations: Int,
    val createdAtEpochMillis: Long,
) {
    init {
        // Validate invariants at construction to fail closed immediately.
        require(salt.size == 16) { "PIN salt must be exactly 16 bytes, got ${salt.size}" }
        require(hash.size == 32) { "PIN hash must be exactly 32 bytes, got ${hash.size}" }
        require(iterations > 0) { "PIN iterations must be positive, got $iterations" }
    }
}

/**
 * Tracks local rate-limiting state for PIN verification attempts.
 */
data class PinAttemptState(
    val failedAttempts: Int,
    val lockedUntilEpochMillis: Long, // 0 means not locked
)

/**
 * Sealed hierarchy representing the outcome of a PIN verification attempt.
 * Uses `object` for singletons to maintain compatibility with plain Kotlin
 * without requiring special language-version flags.
 */
sealed class PinVerifyResult {
    object Correct : PinVerifyResult()
    data class Incorrect(val failedAttempts: Int) : PinVerifyResult()
    data class Locked(val lockedUntilEpochMillis: Long) : PinVerifyResult()
    object Duress : PinVerifyResult()
    object NoPinConfigured : PinVerifyResult()
}
