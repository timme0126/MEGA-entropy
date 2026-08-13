package org.mega.entropy.seedqr

import org.mega.entropycore.ManualMnemonicValidation
import org.mega.entropycore.deriveMnemonicFromEntropy
import org.mega.entropycore.loadOfficialEnglishWordList
import org.mega.entropycore.validateManualMnemonic

sealed class SeedQrResult {
    data class Success(val words: List<String>) : SeedQrResult()
    data class Failure(val reason: String) : SeedQrResult()
}

/**
 * Parses a scanned SeedQR payload — SeedSigner's format for encoding a
 * BIP39 mnemonic as a QR code, also read by Sparrow Wallet and others —
 * into its words. Supports both variants:
 *
 * - Standard SeedQR: a numeric-mode QR whose digits are the mnemonic's
 *   word indices, 4 digits each (0000-2047), 48 digits for 12 words or
 *   96 for 24. [text] carries this directly.
 * - Compact SeedQR: a byte-mode QR containing the raw 16 or 32 bytes of
 *   entropy directly, rather than word indices. [byteSegments] — zxing's
 *   ResultMetadataType.BYTE_SEGMENTS — is the only reliable way to
 *   recover this: zxing's own decoded text for a byte-mode QR round-trips
 *   through a guessed character set, which isn't guaranteed to preserve
 *   arbitrary binary content, whereas BYTE_SEGMENTS is the undecoded
 *   payload bytes zxing itself extracted from the byte-mode segment.
 *
 * Checked in this order because a Compact SeedQR's raw bytes could
 * coincidentally decode to a digit-only string; byte-mode content always
 * takes precedence when present.
 */
fun decodeSeedQr(text: String, byteSegments: List<ByteArray>?): SeedQrResult {
    val bytes = byteSegments?.reduceOrNull { a, b -> a + b }
    if (bytes != null && (bytes.size == 16 || bytes.size == 32)) {
        return try {
            SeedQrResult.Success(deriveMnemonicFromEntropy(bytes))
        } catch (e: IllegalArgumentException) {
            SeedQrResult.Failure(e.message ?: "Could not decode Compact SeedQR.")
        }
    }

    if (text.isNotEmpty() && text.all { it.isDigit() } && (text.length == 48 || text.length == 96)) {
        val wordList = loadOfficialEnglishWordList()
        val indices = text.chunked(4).map { it.toInt() }
        if (indices.any { it !in wordList.indices }) {
            return SeedQrResult.Failure("SeedQR contains an out-of-range word index.")
        }
        val words = indices.map { wordList[it] }
        return when (val validation = validateManualMnemonic(words)) {
            is ManualMnemonicValidation.Valid -> SeedQrResult.Success(validation.words)
            is ManualMnemonicValidation.Invalid -> SeedQrResult.Failure(validation.reason)
        }
    }

    return SeedQrResult.Failure("Not a recognized SeedQR format — expected a Standard or Compact SeedQR.")
}
