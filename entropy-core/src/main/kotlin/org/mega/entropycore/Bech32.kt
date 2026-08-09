package org.mega.entropycore

/**
 * Bech32 (BIP173) encoding for native SegWit (P2WPKH, witness version 0)
 * addresses. MEGA only ever encodes witness v0 20-byte programs (BIP84
 * receive addresses), so this implements exactly that case, not the
 * general "any witness version" segwit address spec (which for v1+/Taproot
 * actually needs the related-but-different bech32m checksum constant).
 */
private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private const val BECH32_CONST = 1

private fun bech32Polymod(values: IntArray): Int {
    val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    var chk = 1
    for (v in values) {
        val b = chk ushr 25
        chk = (chk and 0x1ffffff) shl 5 xor v
        for (i in 0 until 5) {
            if ((b ushr i) and 1 == 1) {
                chk = chk xor generator[i]
            }
        }
    }
    return chk
}

private fun bech32HrpExpand(hrp: String): IntArray {
    val lower = IntArray(hrp.length) { hrp[it].code ushr 5 }
    val upper = IntArray(hrp.length) { hrp[it].code and 31 }
    return lower + intArrayOf(0) + upper
}

private fun bech32CreateChecksum(hrp: String, data: IntArray): IntArray {
    val values = bech32HrpExpand(hrp) + data + IntArray(6)
    val polymod = bech32Polymod(values) xor BECH32_CONST
    return IntArray(6) { (polymod ushr (5 * (5 - it))) and 31 }
}

/**
 * Converts a byte array into groups of [toBits] bits (used to repack the
 * 8-bit witness program into bech32's 5-bit alphabet). [pad] adds a final
 * short group with zero bits rather than dropping them, which is required
 * when going from 8 bits down to 5.
 */
private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
    var acc = 0
    var bits = 0
    val result = mutableListOf<Int>()
    val maxValue = (1 shl toBits) - 1
    val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
    for (byte in data) {
        val value = byte.toInt() and 0xFF
        require(value shr fromBits == 0) { "Input value exceeds fromBits" }
        acc = ((acc shl fromBits) or value) and maxAcc
        bits += fromBits
        while (bits >= toBits) {
            bits -= toBits
            result.add((acc ushr bits) and maxValue)
        }
    }
    if (pad && bits > 0) {
        result.add((acc shl (toBits - bits)) and maxValue)
    }
    return result.toIntArray()
}

/**
 * Encodes a native SegWit (BIP84) receive address: witness version 0 over
 * a 20-byte HASH160(compressed pubkey) program. [hrp] is "bc" for mainnet
 * or "tb" for testnet.
 */
internal fun encodeSegwitV0Address(hrp: String, program: ByteArray): String {
    require(program.size == 20) { "P2WPKH witness program must be 20 bytes, got ${program.size}" }

    val witnessVersionGroup = intArrayOf(0)
    val programGroups = convertBits(program, fromBits = 8, toBits = 5, pad = true)
    val data = witnessVersionGroup + programGroups

    val checksum = bech32CreateChecksum(hrp, data)
    val combined = data + checksum
    return hrp + "1" + combined.map { BECH32_CHARSET[it] }.joinToString("")
}
