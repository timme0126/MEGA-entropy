package org.mega.entropycore

/**
 * Outcome of validating a manually typed-in BIP39 mnemonic (Advanced Mode).
 * Unlike the dice pipeline (MnemonicResult), this never accepts anything
 * the user didn't type themselves — there is no rejection-sampling step,
 * only "is this a well-formed, checksum-valid BIP39 mnemonic or not".
 */
sealed class ManualMnemonicValidation {
    data class Valid(
        val entropy: MnemonicEntropy,
        val words: List<String>,
    ) : ManualMnemonicValidation()

    data class Invalid(val reason: String) : ManualMnemonicValidation()
}

/**
 * Validates a manually entered BIP39 mnemonic: word count (12 or 24),
 * every word present in the official English word list, and the BIP39
 * checksum. This is the "reverse direction" of deriveMnemonic — instead of
 * turning entropy into words, it turns words back into entropy and checks
 * the checksum that entropy would have produced matches the checksum bits
 * actually present in the given words.
 *
 * Words are trimmed and lowercased before lookup so incidental whitespace
 * or capitalization from a user typing on a phone keyboard doesn't cause a
 * spurious "not a BIP39 word" rejection.
 */
fun validateManualMnemonic(rawWords: List<String>): ManualMnemonicValidation {
    val words = rawWords.map { it.trim().lowercase() }

    if (words.size != 12 && words.size != 24) {
        return ManualMnemonicValidation.Invalid(
            "Enter exactly 12 or 24 words — got ${words.size}."
        )
    }
    val blankIndex = words.indexOfFirst { it.isEmpty() }
    if (blankIndex >= 0) {
        return ManualMnemonicValidation.Invalid("Word ${blankIndex + 1} is blank.")
    }

    val wordList = loadOfficialEnglishWordList()
    val indices = IntArray(words.size)
    for (i in words.indices) {
        val index = wordList.indexOf(words[i])
        if (index < 0) {
            return ManualMnemonicValidation.Invalid(
                "\"${words[i]}\" (word ${i + 1}) is not in the official BIP39 English word list."
            )
        }
        indices[i] = index
    }

    val bitStream = indicesTo11BitStream(indices.toList())
    val entropyBitCount = if (words.size == 12) 128 else 256
    val entropyBits = bitStream.copyOfRange(0, entropyBitCount)
    val checksumBits = bitStream.copyOfRange(entropyBitCount, bitStream.size)
    val entropyBytes = bitsToBytes(entropyBits)

    val expectedChecksum = calculateChecksum(entropyBytes)
    if (!checksumBits.contentEquals(expectedChecksum.checksumBits)) {
        return ManualMnemonicValidation.Invalid(
            "Checksum does not match. Double-check the word order — a valid BIP39 " +
                "mnemonic's last word encodes a checksum over the rest."
        )
    }

    return ManualMnemonicValidation.Valid(MnemonicEntropy(entropyBytes), words)
}
