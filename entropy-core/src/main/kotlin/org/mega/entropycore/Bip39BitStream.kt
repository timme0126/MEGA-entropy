package org.mega.entropycore

/**
 * Assembles the 256 entropy bits and 8 checksum bits into a single 264-bit stream.
 * BIP39 specifies that entropy bits are extracted MSB-first per byte (byte 0 first),
 * followed immediately by the 8 checksum bits. This function enforces that exact
 * bit ordering to guarantee deterministic word index derivation.
 */
fun buildBitStream(entropy32Bytes: ByteArray, checksumBits: BooleanArray): BooleanArray {
    require(entropy32Bytes.size == 32) { "Entropy must be exactly 32 bytes, got ${entropy32Bytes.size}" }
    require(checksumBits.size == 8) { "Checksum bits must be exactly 8, got ${checksumBits.size}" }

    val stream = BooleanArray(264)
    var pos = 0

    // Extract 256 entropy bits, MSB-first per byte. Kotlin's Byte is signed
    // and has no shr/and operators of its own, so widen to Int first and
    // mask off any sign-extension bits before shifting.
    for (byte in entropy32Bytes) {
        val unsignedByte = byte.toInt() and 0xFF
        for (bitPos in 7 downTo 0) {
            stream[pos++] = (unsignedByte shr bitPos) and 1 == 1
        }
    }

    // Append the 8 checksum bits directly after the entropy bits
    for (i in checksumBits.indices) {
        stream[pos++] = checksumBits[i]
    }

    return stream
}

/**
 * Splits a 264-bit stream into 24 consecutive 11-bit groups.
 * Each group is interpreted as an unsigned integer with the first bit of the
 * group being the most significant bit (MSB). This matches BIP39's specification
 * for deriving the 0..2047 word indices.
 */
fun splitInto11BitGroups(bitStream: BooleanArray): List<Int> {
    require(bitStream.size == 264) { "Bit stream must be exactly 264 bits, got ${bitStream.size}" }

    val groups = mutableListOf<Int>()
    for (i in 0 until 24) {
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
