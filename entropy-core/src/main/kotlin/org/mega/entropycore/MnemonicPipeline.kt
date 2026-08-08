package org.mega.entropycore

/**
 * Top-level entry point for the MEGA mnemonic derivation pipeline, for the
 * original 100-roll / 256-bit / 24-word case. Delegates to the generalized
 * pipeline below with MnemonicLength.TWENTY_FOUR_WORDS, so both mnemonic
 * lengths MEGA supports run through exactly one implementation of each
 * step — a reviewer verifies one pipeline works correctly for both cases,
 * rather than two hand-written copies that could quietly diverge.
 *
 * @param rolls Exactly 100 die rolls, each in the range 1..6.
 * @param wordList The validated BIP39 word list (defaults to the official English list).
 * @return MnemonicResult.Success with the derived mnemonic, or MnemonicResult.Rejected
 *         if the sequence fails the unbiased rejection sampling threshold.
 */
fun deriveMnemonic(rolls: List<Int>, wordList: List<String> = loadOfficialEnglishWordList()): MnemonicResult =
    deriveMnemonic(rolls, MnemonicLength.TWENTY_FOUR_WORDS, wordList)

/**
 * Generalized mnemonic derivation pipeline covering both lengths MEGA
 * supports (see MnemonicLength). Converts physical die rolls into a valid
 * BIP39 mnemonic. Validates input, computes the base-6 integer, applies
 * unbiased rejection sampling, extracts entropy, computes the BIP39
 * checksum, derives word indices, and maps them to the official English
 * word list.
 *
 * @param rolls Exactly `length.rollCount` die rolls, each in the range 1..6.
 * @param length Which mnemonic length to derive (12 or 24 words).
 * @param wordList The validated BIP39 word list (defaults to the official English list).
 * @return MnemonicResult.Success with the derived mnemonic, or MnemonicResult.Rejected
 *         if the sequence fails the unbiased rejection sampling threshold.
 */
fun deriveMnemonic(
    rolls: List<Int>,
    length: MnemonicLength,
    wordList: List<String> = loadOfficialEnglishWordList(),
): MnemonicResult {
    // 1. Validate input invariants strictly
    require(rolls.size == length.rollCount) {
        "Exactly ${length.rollCount} die rolls are required for ${length.wordCount} words, got ${rolls.size}"
    }
    require(rolls.all { it in 1..6 }) { "All rolls must be in the range 1..6, found invalid roll" }

    // 2. Map physical rolls to base-6 digits (0..5)
    val base6Digits = mapRollsToBase6(rolls)

    // 3. Compute the full base-6 integer X using the direct path
    val x = calculateXDirect(base6Digits)

    // 4. Apply unbiased rejection sampling
    val rejectionResult = checkAcceptance(x, length.rollCount, length.entropyBits)
    if (rejectionResult is RejectionResult.Rejected) {
        return MnemonicResult.Rejected(rejectionResult)
    }

    // 5. Extract exactly length.entropyBits bits of entropy from the accepted X
    val entropy = deriveEntropyBits(x, length.entropyBits)
    val entropyBytes = entropy.bytes

    // 6. Compute the BIP39 checksum (first ENT/32 bits of SHA-256(entropy))
    val checksumResult = calculateChecksum(entropyBytes)

    // 7. Build the entropy+checksum bit stream
    val bitStream = buildBitStream(entropyBytes, checksumResult.checksumBits)

    // 8. Split into consecutive 11-bit groups
    val indices = splitInto11BitGroups(bitStream)

    // 9. Map indices to words using the verified word list
    val words = deriveWords(indices, wordList)

    // 10. Assemble derivation metadata for UI/display
    val derivations = indices.mapIndexed { index, decimalIndex ->
        val startBit = index * 11
        val bits = BooleanArray(11) { bitStream[startBit + it] }
        WordDerivation(
            groupIndex = index,
            bits = bits,
            decimalIndex = decimalIndex,
            word = words[index]
        )
    }

    return MnemonicResult.Success(
        entropy = entropy,
        checksum = checksumResult,
        words = words,
        derivations = derivations
    )
}
