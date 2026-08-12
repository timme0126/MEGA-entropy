package org.mega.entropycore

/**
 * Converts raw BIP39 entropy bytes directly into their mnemonic words —
 * the standard BIP39 "entropy -> mnemonic" step (SHA-256 checksum, 11-bit
 * groups, official word list), skipping the dice-specific rejection
 * sampling in deriveMnemonic() entirely since there is nothing to reject:
 * the caller already has final entropy, not raw physical rolls.
 *
 * Used for Compact SeedQR import (SeedSigner's format for embedding
 * entropy directly as binary QR data, rather than word indices) — the
 * scanned QR payload already IS 16 or 32 bytes of entropy.
 */
fun deriveMnemonicFromEntropy(entropyBytes: ByteArray): List<String> {
    require(entropyBytes.size == 16 || entropyBytes.size == 32) {
        "entropyBytes must be 16 (12 words) or 32 (24 words) bytes, got ${entropyBytes.size}"
    }
    val wordList = loadOfficialEnglishWordList()
    val checksumResult = calculateChecksum(entropyBytes)
    val bitStream = buildBitStream(entropyBytes, checksumResult.checksumBits)
    val indices = splitInto11BitGroups(bitStream)
    return deriveWords(indices, wordList)
}
