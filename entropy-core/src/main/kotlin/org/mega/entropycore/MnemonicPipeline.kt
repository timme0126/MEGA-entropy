package org.mega.entropycore

/**
 * Top-level entry point for the MEGA mnemonic derivation pipeline.
 * Converts 100 physical die rolls into a valid 24-word BIP39 mnemonic.
 * Validates input, computes the base-6 integer, applies unbiased rejection sampling,
 * extracts entropy, computes the BIP39 checksum, derives word indices,
 * and maps them to the official English word list.
 *
 * @param rolls Exactly 100 die rolls, each in the range 1..6.
 * @param wordList The validated BIP39 word list (defaults to the official English list).
 * @return MnemonicResult.Success with the derived mnemonic, or MnemonicResult.Rejected
 *         if the sequence fails the unbiased rejection sampling threshold.
 */
fun deriveMnemonic(rolls: List<Int>, wordList: List<String> = loadOfficialEnglishWordList()): MnemonicResult {
    // 1. Validate input invariants strictly
    require(rolls.size == 100) { "Exactly 100 die rolls are required, got ${rolls.size}" }
    require(rolls.all { it in 1..6 }) { "All rolls must be in the range 1..6, found invalid roll" }

    // 2. Map physical rolls to base-6 digits (0..5)
    val base6Digits = mapRollsToBase6(rolls)

    // 3. Compute the full 100-digit base-6 integer X using the direct path
    val x = calculateXDirect(base6Digits)

    // 4. Apply unbiased rejection sampling
    val rejectionResult = checkAcceptance(x)
    if (rejectionResult is RejectionResult.Rejected) {
        return MnemonicResult.Rejected(rejectionResult)
    }

    // 5. Extract exactly 256 bits of entropy from the accepted X
    val entropy256 = deriveEntropy256(x)
    val entropyBytes = entropy256.bytes

    // 6. Compute BIP39 checksum (first 8 bits of SHA-256(entropy))
    val checksumResult = calculateChecksum(entropyBytes)

    // 7. Build the 264-bit stream (256 entropy bits + 8 checksum bits)
    val bitStream = buildBitStream(entropyBytes, checksumResult.checksumBits)

    // 8. Split into 24 consecutive 11-bit groups
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
        entropy = entropy256,
        checksum = checksumResult,
        words = words,
        derivations = derivations
    )
}
