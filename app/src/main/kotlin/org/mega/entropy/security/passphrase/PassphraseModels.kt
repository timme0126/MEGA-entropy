package org.mega.entropy.security.passphrase

/**
 * A stored "can I verify a re-entered passphrase later?" credential — never
 * the passphrase itself. Same shape and rationale as PinRecord: the salt
 * prevents rainbow-table attacks and doesn't need to be secret, and the
 * hash is the only artifact that could theoretically be brute-forced, which
 * is why both live in the same AES-256-GCM-encrypted session blob as the
 * mnemonic rather than needing separate protection.
 */
data class PassphraseCheck(
    val salt: ByteArray,
    val hash: ByteArray,
) {
    init {
        require(salt.size == 16) { "Passphrase check salt must be exactly 16 bytes, got ${salt.size}" }
        require(hash.size == 32) { "Passphrase check hash must be exactly 32 bytes, got ${hash.size}" }
    }
}
