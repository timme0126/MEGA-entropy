package org.mega.entropycore

import java.io.InputStream
import java.security.MessageDigest

// Anchor object purely so loadOfficialEnglishWordList() has a class whose
// classloader it can borrow for classpath resource lookups (this file only
// declares top-level functions, so there is no other class here to use).
private object ResourceAnchor

/**
 * Loads and cryptographically verifies the official English BIP39 word list.
 * Reads from the classpath resource /bip39/english.txt, verifies line count,
 * uniqueness, and SHA-256 integrity against the companion .sha256 file.
 * Fails closed on any discrepancy to prevent mnemonic derivation from tampered or malformed data.
 */
fun loadOfficialEnglishWordList(): List<String> {
    val wordListStream: InputStream = ResourceAnchor::class.java.getResourceAsStream("/bip39/english.txt")
        ?: throw IllegalStateException("BIP39 word list resource /bip39/english.txt not found on classpath")

    val sha256Stream: InputStream = ResourceAnchor::class.java.getResourceAsStream("/bip39/english.txt.sha256")
        ?: throw IllegalStateException("BIP39 word list SHA-256 resource /bip39/english.txt.sha256 not found on classpath")

    // Read raw bytes for integrity verification
    val rawBytes = wordListStream.use { it.readBytes() }
    val expectedHash = sha256Stream.use { it.readBytes().decodeToString().trim() }

    // Compute SHA-256 of the raw word list bytes to verify integrity
    val digest = MessageDigest.getInstance("SHA-256").digest(rawBytes)
    // it.toInt() and 0xFF masks off sign-extension bits so each byte formats
    // as its unsigned two-hex-digit value, not a signed one.
    val computedHash = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    if (computedHash != expectedHash) {
        throw IllegalStateException("BIP39 word list integrity check failed: computed SHA-256 does not match recorded hash")
    }

    // Parse into trimmed, non-blank lines and validate structure
    val lines = rawBytes.decodeToString().lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    if (lines.size != 2048) {
        throw IllegalStateException("BIP39 word list must contain exactly 2048 lines, got ${lines.size}")
    }

    if (lines.toSet().size != 2048) {
        throw IllegalStateException("BIP39 word list contains duplicate entries")
    }

    return lines
}

/**
 * Maps a list of 11-bit indices to their corresponding BIP39 words.
 * The word list is never sorted or modified; the index directly corresponds to the list position.
 * Validates that all indices fall within the valid range [0, 2047] to prevent out-of-bounds access.
 */
fun deriveWords(indices: List<Int>, wordList: List<String>): List<String> {
    require(wordList.size == 2048) { "Word list must contain exactly 2048 words, got ${wordList.size}" }
    require(indices.all { it in 0..2047 }) { "All word indices must be in range 0..2047, found invalid index" }

    return indices.map { wordList[it] }
}
