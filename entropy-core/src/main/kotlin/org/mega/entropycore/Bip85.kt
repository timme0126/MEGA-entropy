package org.mega.entropycore

/**
 * Supported BIP-85 BIP39 child mnemonic sizes for MEGA's UI. BIP-85 also
 * defines 15/18/21 words and non-BIP39 applications, but this app exposes
 * only the 12- and 24-word English cases users already understand here.
 */
enum class Bip85MnemonicWords(val wordCount: Int, val entropyBytes: Int) {
    TWELVE(wordCount = 12, entropyBytes = 16),
    TWENTY_FOUR(wordCount = 24, entropyBytes = 32),
}

data class Bip85DerivedMnemonic(
    val index: Long,
    val words: Bip85MnemonicWords,
    val path: String,
    val entropy: MnemonicEntropy,
    val mnemonicWords: List<String>,
)

/**
 * Derives a BIP-85 English BIP39 child mnemonic from a parent BIP39 mnemonic.
 * The path is m/83696968'/39'/0'/{12|24}'/{index}', where 0' is English.
 *
 * The optional passphrase is the parent BIP39 passphrase. Leaving it empty
 * matches the normal no-passphrase BIP39 seed case.
 *
 * The child derivation itself (bip32MasterKeyFromSeed, deriveHardenedChild)
 * lives in Bip32.kt, shared with the wallet-derivation code in
 * WalletDerivation.kt — one BIP32 implementation, not two that could
 * quietly diverge.
 */
fun deriveBip85Bip39Mnemonic(
    parentWords: List<String>,
    childWords: Bip85MnemonicWords,
    index: Long,
    parentPassphrase: String = "",
): Bip85DerivedMnemonic {
    validateBip85ParentWords(parentWords)
    validateBip85Index(index)
    val seed = deriveSeed(parentWords, parentPassphrase)
    val master = bip32MasterKeyFromSeed(seed.bytes)
    return deriveBip85Bip39Mnemonic(master, childWords, index)
}

/**
 * Derives a BIP-85 English BIP39 child mnemonic from a BIP32 root xprv.
 * This overload exists mainly to test against the official BIP-85 vectors.
 */
internal fun deriveBip85Bip39Mnemonic(
    rootXprv: String,
    childWords: Bip85MnemonicWords,
    index: Long,
): Bip85DerivedMnemonic {
    validateBip85Index(index)
    return deriveBip85Bip39Mnemonic(decodeBip32RootXprv(rootXprv), childWords, index)
}

private fun deriveBip85Bip39Mnemonic(
    rootKey: Bip32ExtendedPrivateKey,
    childWords: Bip85MnemonicWords,
    index: Long,
): Bip85DerivedMnemonic {
    val pathIndexes = listOf(83696968L, 39L, 0L, childWords.wordCount.toLong(), index)
    val derivedKey = pathIndexes.fold(rootKey) { key, childIndex ->
        key.deriveHardenedChild(childIndex)
    }
    val bip85Entropy = hmacSha512(
        key = "bip-entropy-from-k".toByteArray(Charsets.US_ASCII),
        message = derivedKey.privateKey,
    )
    val entropyBytes = bip85Entropy.copyOfRange(0, childWords.entropyBytes)
    val entropy = MnemonicEntropy(entropyBytes)
    val mnemonicWords = mnemonicWordsFromEntropy(entropyBytes)
    val path = "m/83696968'/39'/0'/${childWords.wordCount}'/${index}'"
    return Bip85DerivedMnemonic(index, childWords, path, entropy, mnemonicWords)
}

private fun validateBip85ParentWords(parentWords: List<String>) {
    when (val validation = validateManualMnemonic(parentWords)) {
        is ManualMnemonicValidation.Valid -> Unit
        is ManualMnemonicValidation.Invalid -> {
            throw IllegalArgumentException("Invalid BIP85 parent mnemonic: ${validation.reason}")
        }
    }
}

private fun validateBip85Index(index: Long) {
    require(index in 0 until HARDENED_OFFSET) {
        "BIP85 index must be between 0 and ${HARDENED_OFFSET - 1}, got $index"
    }
}

private fun mnemonicWordsFromEntropy(entropyBytes: ByteArray): List<String> {
    val checksum = calculateChecksum(entropyBytes)
    val bitStream = buildBitStream(entropyBytes, checksum.checksumBits)
    val indices = splitInto11BitGroups(bitStream)
    return deriveWords(indices, loadOfficialEnglishWordList())
}
