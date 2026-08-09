package org.mega.entropycore

/**
 * Assembles the entropy bits and checksum bits into a single bit stream:
 * entropyBytes.size*8 + checksumBits.size bits total (264 for the original
 * 256-bit/24-word case, 132 for the 128-bit/12-word case — see
 * MnemonicLength). BIP39 specifies that entropy bits are extracted
 * MSB-first per byte (byte 0 first), followed immediately by the checksum
 * bits. This function enforces that exact bit ordering to guarantee
 * deterministic word index derivation.
 */
internal fun buildBitStream(entropyBytes: ByteArray, checksumBits: BooleanArray): BooleanArray {
    require(entropyBytes.size == 16 || entropyBytes.size == 32) {
        "Entropy must be exactly 16 or 32 bytes, got ${entropyBytes.size}"
    }
    require(checksumBits.size == 4 || checksumBits.size == 8) {
        "Checksum bits must be 4 or 8, got ${checksumBits.size}"
    }

    val stream = BooleanArray(entropyBytes.size * 8 + checksumBits.size)
    var pos = 0

    // Extract entropy bits, MSB-first per byte. Kotlin's Byte is signed
    // and has no shr/and operators of its own, so widen to Int first and
    // mask off any sign-extension bits before shifting.
    for (byte in entropyBytes) {
        val unsignedByte = byte.toInt() and 0xFF
        for (bitPos in 7 downTo 0) {
            stream[pos++] = (unsignedByte shr bitPos) and 1 == 1
        }
    }

    // Append the checksum bits directly after the entropy bits
    for (i in checksumBits.indices) {
        stream[pos++] = checksumBits[i]
    }

    return stream
}

/**
 * Splits a bit stream into consecutive 11-bit groups (24 groups for the
 * 264-bit/24-word case, 12 groups for the 132-bit/12-word case). Each
 * group is interpreted as an unsigned integer with the first bit of the
 * group being the most significant bit (MSB). This matches BIP39's
 * specification for deriving the 0..2047 word indices.
 */
internal fun splitInto11BitGroups(bitStream: BooleanArray): List<Int> {
    require(bitStream.size % 11 == 0) {
        "Bit stream length must be a multiple of 11, got ${bitStream.size}"
    }
    require(bitStream.isNotEmpty()) { "Bit stream must not be empty" }

    val groupCount = bitStream.size / 11
    val groups = mutableListOf<Int>()
    for (i in 0 until groupCount) {
        val start = i * 11
        var value = 0
        for (j in 0 until 11) {
            // Bit at offset j within the group is the (10 - j)-th power of 2
            if (bitStream[start + j]) {
                value += 1 shl (10 - j)
            }
        }
        groups.add(value)
    }
    return groups
}

/**
 * Inverse of [splitInto11BitGroups]: expands a list of 0..2047 word
 * indices back into their concatenated 11-bit, MSB-first representation.
 * Used to validate a manually typed-in mnemonic, where the words (and
 * therefore their indices) are known and the entropy+checksum bits need to
 * be reconstructed for verification — the reverse direction of the normal
 * dice-to-mnemonic pipeline.
 */
internal fun indicesTo11BitStream(indices: List<Int>): BooleanArray {
    require(indices.all { it in 0..2047 }) { "All word indices must be in range 0..2047, found invalid index" }

    val bits = BooleanArray(indices.size * 11)
    indices.forEachIndexed { groupIndex, value ->
        val start = groupIndex * 11
        for (j in 0 until 11) {
            bits[start + j] = (value shr (10 - j)) and 1 == 1
        }
    }
    return bits
}

/**
 * Packs a bit stream (MSB-first, length a multiple of 8) into bytes.
 * Inverse of the per-byte bit extraction in [buildBitStream].
 */
internal fun bitsToBytes(bits: BooleanArray): ByteArray {
    require(bits.size % 8 == 0) { "Bit count must be a multiple of 8, got ${bits.size}" }

    val bytes = ByteArray(bits.size / 8)
    for (byteIndex in bytes.indices) {
        var value = 0
        for (bitPos in 0 until 8) {
            if (bits[byteIndex * 8 + bitPos]) {
                value = value or (1 shl (7 - bitPos))
            }
        }
        bytes[byteIndex] = value.toByte()
    }
    return bytes
}
