package org.mega.entropycore

import java.math.BigInteger

/**
 * Represents a single physical six-sided die roll.
 * Validated to be strictly between 1 and 6 inclusive.
 * Uses a value class to avoid heap allocation while preserving type safety.
 */
@JvmInline
value class DieRoll(val value: Int) {
    init {
        require(value in 1..6) { "DieRoll must be between 1 and 6 inclusive, got: $value" }
    }
}

/**
 * Represents a batch of exactly five die rolls.
 * Used by the UI to accumulate entropy incrementally in 20-step batches.
 */
data class DiceBatch(val rolls: List<DieRoll>) {
    init {
        require(rolls.size == 5) { "DiceBatch must contain exactly 5 rolls, got: ${rolls.size}" }
    }
}

/**
 * Holds the intermediate calculation state for a single 5-roll batch.
 * Allows the UI to display the full mathematical derivation for auditability
 * and to verify that the incremental path matches the direct path.
 */
data class BatchCalculation(
    val base6Digits: List<Int>,
    val chunk: Long,
    val previousX: BigInteger,
    val newX: BigInteger
)

/**
 * Sealed class representing the outcome of the rejection sampling step.
 * Both branches expose the raw accumulated integer X and the cryptographic
 * constants used for the threshold comparison, enabling deterministic verification.
 */
sealed class RejectionResult {
    abstract val x: BigInteger
    abstract val thresholdT: BigInteger
    abstract val sixPow100: BigInteger
    abstract val twoPow256: BigInteger

    data class Accepted(
        override val x: BigInteger,
        override val thresholdT: BigInteger,
        override val sixPow100: BigInteger,
        override val twoPow256: BigInteger
    ) : RejectionResult()

    data class Rejected(
        override val x: BigInteger,
        override val thresholdT: BigInteger,
        override val sixPow100: BigInteger,
        override val twoPow256: BigInteger
    ) : RejectionResult()
}

/**
 * Wraps the exact 32-byte unsigned big-endian representation of the 256-bit entropy.
 * Guarantees fixed length and provides a deterministic hex string accessor.
 * The hex accessor explicitly masks each byte to unsigned to prevent sign-extension artifacts.
 */
data class Entropy256(val bytes: ByteArray) {
    init {
        require(bytes.size == 32) { "Entropy256 must wrap exactly 32 bytes, got: ${bytes.size}" }
    }

    val hex: String
        get() = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Holds the SHA-256 digest of the entropy and the extracted 8-bit checksum.
 * The checksum bits are exposed as a BooleanArray where index 0 is the MSB
 * of the first digest byte, matching BIP39's MSB-first bit ordering.
 */
data class ChecksumResult(
    val digest: ByteArray,
    val checksumBits: BooleanArray
) {
    init {
        require(digest.size == 32) { "SHA-256 digest must be exactly 32 bytes, got: ${digest.size}" }
        require(checksumBits.size == 8) { "Checksum bits must be exactly 8 elements, got: ${checksumBits.size}" }
    }
}

/**
 * Represents a single 11-bit group extracted from the 264-bit bitstream.
 * Contains the group position, its raw bits, the decimal index into the word list,
 * and the resolved word.
 */
data class WordDerivation(
    val groupIndex: Int,
    val bits: BooleanArray,
    val decimalIndex: Int,
    val word: String
) {
    init {
        require(groupIndex in 0..23) { "WordDerivation groupIndex must be 0..23, got: $groupIndex" }
        require(bits.size == 11) { "WordDerivation bits must be exactly 11 elements, got: ${bits.size}" }
        require(decimalIndex in 0..2047) { "WordDerivation decimalIndex must be 0..2047, got: $decimalIndex" }
    }
}

/**
 * Sealed class representing the final outcome of the mnemonic derivation pipeline.
 * Success contains all intermediate cryptographic artifacts for auditability.
 * Rejected contains the rejection sampling details so the user can understand why.
 */
sealed class MnemonicResult {
    data class Success(
        val entropy: Entropy256,
        val checksum: ChecksumResult,
        val words: List<String>,
        val derivations: List<WordDerivation>
    ) : MnemonicResult()

    data class Rejected(
        val rejection: RejectionResult
    ) : MnemonicResult()
}
