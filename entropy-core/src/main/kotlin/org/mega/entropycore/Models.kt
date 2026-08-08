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
 *
 * Field names are generic (sixPowRollCount / twoPowEntropyBits) rather than
 * hardcoded to the 24-word case, since MEGA also supports a 128-bit/12-word
 * mnemonic (50 rolls) alongside the original 256-bit/24-word one (100
 * rolls) — see MnemonicLength.
 */
sealed class RejectionResult {
    abstract val x: BigInteger
    abstract val thresholdT: BigInteger
    abstract val sixPowRollCount: BigInteger
    abstract val twoPowEntropyBits: BigInteger

    data class Accepted(
        override val x: BigInteger,
        override val thresholdT: BigInteger,
        override val sixPowRollCount: BigInteger,
        override val twoPowEntropyBits: BigInteger
    ) : RejectionResult()

    data class Rejected(
        override val x: BigInteger,
        override val thresholdT: BigInteger,
        override val sixPowRollCount: BigInteger,
        override val twoPowEntropyBits: BigInteger
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
 * Generalized entropy wrapper covering both mnemonic lengths MEGA supports:
 * 16 bytes (128 bits, 12 words) or 32 bytes (256 bits, 24 words). Entropy256
 * above is kept as-is (unchanged, still used by the original 24-word-only
 * pipeline) so nothing about the already-audited 256-bit path changes;
 * this is the type used by the generalized pipeline that covers both.
 */
data class MnemonicEntropy(val bytes: ByteArray) {
    init {
        require(bytes.size == 16 || bytes.size == 32) {
            "MnemonicEntropy must wrap 16 or 32 bytes, got: ${bytes.size}"
        }
    }

    val hex: String
        get() = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Holds the SHA-256 digest of the entropy and the extracted checksum bits.
 * The checksum bits are exposed as a BooleanArray where index 0 is the MSB
 * of the first digest byte, matching BIP39's MSB-first bit ordering.
 * checksumBits.size is 4 for 128-bit entropy or 8 for 256-bit entropy
 * (BIP39's CS = ENT/32); digest is always 32 bytes regardless, since
 * SHA-256's output length doesn't depend on its input length.
 */
data class ChecksumResult(
    val digest: ByteArray,
    val checksumBits: BooleanArray
) {
    init {
        require(digest.size == 32) { "SHA-256 digest must be exactly 32 bytes, got: ${digest.size}" }
        require(checksumBits.size == 4 || checksumBits.size == 8) {
            "Checksum bits must be 4 (128-bit entropy) or 8 (256-bit entropy) elements, got: ${checksumBits.size}"
        }
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
        val entropy: MnemonicEntropy,
        val checksum: ChecksumResult,
        val words: List<String>,
        val derivations: List<WordDerivation>
    ) : MnemonicResult()

    data class Rejected(
        val rejection: RejectionResult
    ) : MnemonicResult()
}

/**
 * The two mnemonic lengths MEGA supports. rollCount is always a multiple of
 * 5 to fit the 5-roll batch entry UX. Each profile's roll count was chosen
 * the same way the original 100-roll/256-bit design was: comfortably above
 * the required bit count, with a small integer rejection-threshold
 * multiplier (T = multiplier * 2^entropyBits) — see docs/ENTROPY-MATH.md.
 */
enum class MnemonicLength(val wordCount: Int, val rollCount: Int, val entropyBits: Int) {
    TWELVE_WORDS(wordCount = 12, rollCount = 50, entropyBits = 128),
    TWENTY_FOUR_WORDS(wordCount = 24, rollCount = 100, entropyBits = 256),
}
