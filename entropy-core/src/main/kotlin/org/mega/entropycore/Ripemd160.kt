package org.mega.entropycore

/**
 * Hand-rolled RIPEMD-160, needed for Bitcoin's HASH160 (RIPEMD160(SHA256(x)))
 * — used for addresses and BIP32 key fingerprints. Android's stock
 * `MessageDigest` does not reliably expose "RIPEMD160" (it was trimmed from
 * the platform's bundled provider on modern API levels), so this
 * reimplements the algorithm directly rather than pulling in a general-
 * purpose crypto library for one digest. Verified against the algorithm
 * authors' own published test vectors in Ripemd160Test.kt.
 *
 * Reference: "RIPEMD-160: A Strengthened Version of RIPEMD" (Dobbertin,
 * Bosselaers, Preneel, 1996). Deterministic, not a randomness source, same
 * category as the SHA-256 usage in Sha256Checksum.kt.
 */
internal fun ripemd160(message: ByteArray): ByteArray {
    val padded = padMessage(message)
    var h0 = 0x67452301
    var h1 = 0xEFCDAB89.toInt()
    var h2 = 0x98BADCFE.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0.toInt()

    val blockCount = padded.size / 64
    for (block in 0 until blockCount) {
        val x = IntArray(16)
        for (i in 0 until 16) {
            val offset = block * 64 + i * 4
            x[i] = (padded[offset].toInt() and 0xFF) or
                ((padded[offset + 1].toInt() and 0xFF) shl 8) or
                ((padded[offset + 2].toInt() and 0xFF) shl 16) or
                ((padded[offset + 3].toInt() and 0xFF) shl 24)
        }

        var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
        var ap = h0; var bp = h1; var cp = h2; var dp = h3; var ep = h4

        for (j in 0 until 80) {
            val round = j / 16
            val t = rotl(a + leftF(round, b, c, d) + x[LEFT_WORD_ORDER[j]] + LEFT_K[round], LEFT_SHIFT[j]) + e
            a = e; e = d; d = rotl(c, 10); c = b; b = t

            val tp = rotl(ap + rightF(round, bp, cp, dp) + x[RIGHT_WORD_ORDER[j]] + RIGHT_K[round], RIGHT_SHIFT[j]) + ep
            ap = ep; ep = dp; dp = rotl(cp, 10); cp = bp; bp = tp
        }

        val t = h1 + c + dp
        h1 = h2 + d + ep
        h2 = h3 + e + ap
        h3 = h4 + a + bp
        h4 = h0 + b + cp
        h0 = t
    }

    val digest = ByteArray(20)
    writeInt32LittleEndian(h0, digest, 0)
    writeInt32LittleEndian(h1, digest, 4)
    writeInt32LittleEndian(h2, digest, 8)
    writeInt32LittleEndian(h3, digest, 12)
    writeInt32LittleEndian(h4, digest, 16)
    return digest
}

/** HASH160 = RIPEMD160(SHA256(x)), Bitcoin's standard pubkey/script hash. */
internal fun hash160(data: ByteArray): ByteArray = ripemd160(sha256(data))

private fun rotl(value: Int, bits: Int): Int = (value shl bits) or (value ushr (32 - bits))

private fun writeInt32LittleEndian(value: Int, target: ByteArray, offset: Int) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

/** Standard MD4-family padding, but with a little-endian 64-bit length
 * suffix (RIPEMD-160 differs from SHA-256 here, which is big-endian). */
private fun padMessage(message: ByteArray): ByteArray {
    val bitLength = message.size.toLong() * 8
    val paddingLength = ((55 - message.size % 64) + 64) % 64 + 1
    val padded = ByteArray(message.size + paddingLength + 8)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[padded.size - 8 + i] = ((bitLength ushr (8 * i)) and 0xFFL).toByte()
    }
    return padded
}

private fun leftF(round: Int, x: Int, y: Int, z: Int): Int = when (round) {
    0 -> x xor y xor z
    1 -> (x and y) or (x.inv() and z)
    2 -> (x or y.inv()) xor z
    3 -> (x and z) or (y and z.inv())
    else -> x xor (y or z.inv())
}

private fun rightF(round: Int, x: Int, y: Int, z: Int): Int = leftF(4 - round, x, y, z)

private val LEFT_K = intArrayOf(0x00000000, 0x5A827999, 0x6ED9EBA1, 0x8F1BBCDC.toInt(), 0xA953FD4E.toInt())
private val RIGHT_K = intArrayOf(0x50A28BE6, 0x5C4DD124, 0x6D703EF3, 0x7A6D76E9, 0x00000000)

private val LEFT_WORD_ORDER = intArrayOf(
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
    3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
    1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
    4, 0, 5, 9, 7, 12, 2, 10, 14, 1, 3, 8, 11, 6, 15, 13,
)

private val RIGHT_WORD_ORDER = intArrayOf(
    5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
    6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
    15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
    8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
    12, 15, 10, 4, 1, 5, 8, 7, 6, 2, 13, 14, 0, 3, 9, 11,
)

private val LEFT_SHIFT = intArrayOf(
    11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
    7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
    11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
    11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
    9, 15, 5, 11, 6, 8, 13, 12, 5, 12, 13, 14, 11, 8, 5, 6,
)

private val RIGHT_SHIFT = intArrayOf(
    8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
    9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
    9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
    15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
    8, 5, 12, 9, 12, 5, 14, 6, 8, 13, 6, 5, 15, 13, 11, 11,
)
