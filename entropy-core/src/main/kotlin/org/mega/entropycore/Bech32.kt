package org.mega.entropycore

/**
 * Bech32 (BIP173) and Bech32m (BIP350) encoding for native SegWit
 * addresses. Witness version 0 (P2WPKH/P2WSH) uses the original Bech32
 * checksum constant; witness version 1 and above (P2TR and any future
 * segwit output type) uses the related-but-different Bech32m constant —
 * per BIP350, this is a hard consensus-adjacent rule, not a preference:
 * an address with the wrong checksum variant for its witness version is
 * INVALID, not merely unconventional (see [decodeSegwitAddress]).
 */
private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
private const val BECH32_CONST = 1
private const val BECH32M_CONST = 0x2bc830a3

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

private fun bech32CreateChecksum(hrp: String, data: IntArray, const: Int): IntArray {
    val values = bech32HrpExpand(hrp) + data + IntArray(6)
    val polymod = bech32Polymod(values) xor const
    return IntArray(6) { (polymod ushr (5 * (5 - it))) and 31 }
}

/**
 * Converts a byte/value array between bit-group sizes — e.g. repacking an
 * 8-bit witness program into bech32's 5-bit alphabet (encode direction,
 * [pad] = true: a final short group is zero-padded rather than dropped),
 * or the reverse, 5-bit groups back into 8-bit bytes (decode direction,
 * [pad] = false). Ported field-for-field from the reference `convertbits`
 * in BIP173/BIP350's own Python reference implementation, INCLUDING its
 * `pad = false` strict-validation branch: per that spec, leftover bits
 * after a non-padded conversion must be fewer than [fromBits] AND must
 * all be zero, or the input is malformed (BIP350's own test vectors
 * include "zero padding of more than 4 bits" and "non-zero padding in
 * 8-to-5 conversion" as required-invalid cases — silently truncating
 * instead of rejecting would accept both).
 */
private fun convertBits(data: IntArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray {
    var acc = 0
    var bits = 0
    val result = mutableListOf<Int>()
    val maxValue = (1 shl toBits) - 1
    val maxAcc = (1 shl (fromBits + toBits - 1)) - 1
    for (value in data) {
        require(value >= 0 && value shr fromBits == 0) { "Input value exceeds fromBits" }
        acc = ((acc shl fromBits) or value) and maxAcc
        bits += fromBits
        while (bits >= toBits) {
            bits -= toBits
            result.add((acc ushr bits) and maxValue)
        }
    }
    if (pad) {
        if (bits > 0) {
            result.add((acc shl (toBits - bits)) and maxValue)
        }
    } else {
        require(bits < fromBits && (acc shl (toBits - bits)) and maxValue == 0) {
            "Invalid padding in bit-group conversion"
        }
    }
    return result.toIntArray()
}

private fun convertBits(data: ByteArray, fromBits: Int, toBits: Int, pad: Boolean): IntArray =
    convertBits(IntArray(data.size) { data[it].toInt() and 0xFF }, fromBits, toBits, pad)

/**
 * Encodes a witness version 0 SegWit address: 20 bytes for P2WPKH
 * (HASH160 of a compressed pubkey, BIP84) or 32 bytes for P2WSH (SHA256
 * of a witness script). [hrp] is "bc" for mainnet or "tb" for testnet.
 */
internal fun encodeSegwitV0Address(hrp: String, program: ByteArray): String {
    require(program.size == 20 || program.size == 32) {
        "Witness v0 program must be 20 (P2WPKH) or 32 (P2WSH) bytes, got ${program.size}"
    }

    val witnessVersionGroup = intArrayOf(0)
    val programGroups = convertBits(program, fromBits = 8, toBits = 5, pad = true)
    val data = witnessVersionGroup + programGroups

    val checksum = bech32CreateChecksum(hrp, data, BECH32_CONST)
    val combined = data + checksum
    return hrp + "1" + combined.map { BECH32_CHARSET[it] }.joinToString("")
}

/**
 * Encodes a general segwit address for any witness version 0-16 — per
 * BIP350, version 0 uses the Bech32 checksum, versions 1-16 use Bech32m.
 * [program] must be 2-40 bytes (BIP141's general witness-program length
 * range); version 0 additionally requires exactly 20 or 32 bytes (the
 * only two witness-v0 program lengths that exist, per BIP141) — this is
 * the function [encodeSegwitV0Address] above is a version-0-only special
 * case of, kept separate rather than refactored into a wrapper so its
 * existing call sites and behavior are untouched.
 */
internal fun encodeSegwitAddress(hrp: String, witnessVersion: Int, program: ByteArray): String {
    require(witnessVersion in 0..16) { "Witness version must be 0-16, got $witnessVersion" }
    require(program.size in 2..40) { "Witness program must be 2-40 bytes, got ${program.size}" }
    if (witnessVersion == 0) {
        require(program.size == 20 || program.size == 32) {
            "Witness v0 program must be exactly 20 or 32 bytes, got ${program.size}"
        }
    }

    val witnessVersionGroup = intArrayOf(witnessVersion)
    val programGroups = convertBits(program, fromBits = 8, toBits = 5, pad = true)
    val data = witnessVersionGroup + programGroups

    val const = if (witnessVersion == 0) BECH32_CONST else BECH32M_CONST
    val checksum = bech32CreateChecksum(hrp, data, const)
    val combined = data + checksum
    return hrp + "1" + combined.map { BECH32_CHARSET[it] }.joinToString("")
}

/** Convenience wrapper for the single case this codebase actually needs
 * from [encodeSegwitAddress]: a P2TR address (witness version 1, exactly
 * 32-byte x-only output key as the program). */
internal fun encodeTaprootAddress(hrp: String, outputKey: ByteArray): String {
    require(outputKey.size == 32) { "Taproot output key must be exactly 32 bytes, got ${outputKey.size}" }
    return encodeSegwitAddress(hrp, witnessVersion = 1, program = outputKey)
}

/** One witness-version/program pair decoded from a segwit address — see
 * [decodeSegwitAddress]. */
internal data class SegwitAddressData(val witnessVersion: Int, val program: ByteArray)

/**
 * Decodes and fully validates a segwit Bech32/Bech32m address string
 * against the given expected [hrp] ("bc"/"tb"), per BIP173 (bech32
 * general format + decoding rules) and BIP350 (bech32m + the
 * version-to-checksum-variant matching rule). Throws
 * [IllegalArgumentException] with a specific reason on any of BIP350's
 * own documented invalid-address categories — wrong checksum variant for
 * the witness version, mixed case, invalid witness version (>16), wrong
 * program length (general 2-40 byte range, or the exact 20/32 required
 * for v0), invalid/malformed checksum, or malformed bit-group padding —
 * rather than silently accepting or returning a null/partial result. This
 * is the function that backs "distinguish P2TR from P2WPKH/P2WSH" and
 * "decode P2TR addresses" — the witness version in the returned
 * [SegwitAddressData] is exactly that distinction.
 */
internal fun decodeSegwitAddress(hrp: String, address: String): SegwitAddressData {
    require(address.length in 8..90) { "Address length out of range" }
    require(address == address.lowercase() || address == address.uppercase()) {
        "Address must not mix uppercase and lowercase characters"
    }
    val lower = address.lowercase()

    val separatorIndex = lower.lastIndexOf('1')
    require(separatorIndex >= 1) { "No separator character, or empty human-readable part" }
    require(lower.length - separatorIndex - 1 >= 6) { "Data part too short to contain a checksum" }

    val decodedHrp = lower.substring(0, separatorIndex)
    require(decodedHrp == hrp.lowercase()) { "Unexpected human-readable part: got '$decodedHrp', expected '${hrp.lowercase()}'" }
    require(decodedHrp.all { it.code in 33..126 }) { "Human-readable part contains an out-of-range character" }

    val dataChars = lower.substring(separatorIndex + 1)
    val dataValues = IntArray(dataChars.length) { i ->
        BECH32_CHARSET.indexOf(dataChars[i]).also {
            require(it >= 0) { "Invalid data character '${dataChars[i]}'" }
        }
    }

    val polymod = bech32Polymod(bech32HrpExpand(decodedHrp) + dataValues)
    val usedBech32m = when (polymod) {
        BECH32_CONST -> false
        BECH32M_CONST -> true
        else -> throw IllegalArgumentException("Invalid checksum")
    }

    val payload = dataValues.copyOfRange(0, dataValues.size - 6)
    require(payload.isNotEmpty()) { "Empty data section (no witness version)" }
    val witnessVersion = payload[0]
    require(witnessVersion in 0..16) { "Invalid witness version: $witnessVersion" }

    val program = convertBits(payload.copyOfRange(1, payload.size), fromBits = 5, toBits = 8, pad = false)
        .map { it.toByte() }
        .toByteArray()
    require(program.size in 2..40) { "Invalid witness program length: ${program.size} bytes" }
    if (witnessVersion == 0) {
        require(program.size == 20 || program.size == 32) {
            "Invalid witness v0 program length (per BIP141): ${program.size} bytes"
        }
    }

    val expectedBech32m = witnessVersion != 0
    require(usedBech32m == expectedBech32m) {
        if (expectedBech32m) "Witness version $witnessVersion requires Bech32m, got Bech32" else "Witness version 0 requires Bech32, got Bech32m"
    }

    return SegwitAddressData(witnessVersion, program)
}
