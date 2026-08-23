package org.mega.entropycore

/**
 * The "entropy-rooted" backup pattern (after BRC-157): rather than the
 * mnemonic being the one recoverable object, the underlying entropy is —
 * a mnemonic and a set of Shamir backup shares are just two independent,
 * interchangeable ways to recover the identical bytes. Losing the words
 * doesn't matter if enough shares survive, and vice versa; either path
 * lands on the exact same [MnemonicEntropy] and therefore the exact same
 * mnemonic via [deriveMnemonicFromEntropy].
 *
 * Deliberately thin: all the actual splitting/reconstruction math lives
 * in ShamirSecretSharing.kt, keyed on [Secp256k1.N] as its field. This
 * file only adapts that generic secret-sharing primitive to MEGA's own
 * entropy type and length constraints (16 or 32 bytes, matching the two
 * mnemonic lengths [MnemonicEntropy] already supports).
 */
fun entropyToBackupShares(entropy: MnemonicEntropy, threshold: Int, totalShares: Int): List<BackupShare> =
    splitSecret(entropy.bytes, threshold, totalShares)

/**
 * Inverse of [entropyToBackupShares]. [expectedByteLength] must be told
 * up front (16 or 12/24-word mnemonics can't be told apart from the
 * shares alone - the shares only carry the secp256k1-scalar value, not
 * its original byte length) and is enforced here rather than left to
 * [MnemonicEntropy]'s own init block, so a length mismatch reports
 * clearly as a backup-recovery problem rather than a generic entropy
 * validation error.
 */
fun backupSharesToEntropy(shares: List<BackupShare>, expectedByteLength: Int): MnemonicEntropy {
    require(expectedByteLength == 16 || expectedByteLength == 32) {
        "Expected entropy length must be 16 or 32 bytes, got $expectedByteLength"
    }
    val bytes = reconstructSecret(shares, expectedByteLength)
    return MnemonicEntropy(bytes)
}
