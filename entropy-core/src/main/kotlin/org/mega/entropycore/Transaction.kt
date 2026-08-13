package org.mega.entropycore

import java.io.ByteArrayOutputStream

data class TxIn(
    val previousTxid: ByteArray, // 32 bytes, wire/internal byte order — store and read/write EXACTLY as it appears on the wire, never reverse it (that reversal is a display-only convention for human-readable txid strings, irrelevant here)
    val previousVout: Long,
    val scriptSig: ByteArray,
    val sequence: Long,
)

data class TxOut(
    val valueSats: Long,
    val scriptPubKey: ByteArray,
)

/** Total satoshis that will ever exist (21,000,000 BTC × 100,000,000
 * sats/BTC) — the same MAX_MONEY bound Bitcoin Core's consensus rules
 * enforce on every amount field. A PSBT or transaction claiming more than
 * this for a single input/output is malformed by construction, not merely
 * unusual: no real Bitcoin amount can ever reach it. Amounts are read from
 * an attacker-controlled 8-byte little-endian field, so this range check
 * also catches the case where the raw bits happen to set the Kotlin Long's
 * sign bit (read back negative) — a negative value fails `>= 0` the same
 * as an oversized one fails `<= MAX_MONEY_SATS`. */
internal const val MAX_MONEY_SATS = 21_000_000L * 100_000_000L

internal fun Long.isValidSatsAmount(): Boolean = this in 0L..MAX_MONEY_SATS

internal fun requireValidSatsAmount(amount: Long, fieldDescription: String): Long {
    if (!amount.isValidSatsAmount()) {
        throw IllegalArgumentException(
            "$fieldDescription is $amount sats, outside the valid range 0..$MAX_MONEY_SATS",
        )
    }
    return amount
}

/**
 * Sums a list of already-individually-validated satoshi amounts using
 * checked (overflow-detecting) addition. Each amount is bounded to
 * [MAX_MONEY_SATS] by its own parser, but a PSBT is free to declare an
 * unbounded NUMBER of inputs/outputs, so a long enough list can still
 * overflow a 64-bit sum — silently wrapping to a small or negative total
 * would show the user a misleadingly small amount (or an implausible
 * negative one) instead of failing. Throws rather than returning a wrapped
 * value, so an overflowing total fails the whole computation closed
 * instead of producing a number that looks plausible but isn't.
 */
internal fun checkedSumSats(amounts: List<Long>, whatIsBeingSummed: String): Long {
    var total = 0L
    for (amount in amounts) {
        total = try {
            Math.addExact(total, amount)
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("$whatIsBeingSummed overflows a 64-bit total — refusing to report it", e)
        }
    }
    return total
}

/** Checked (overflow-detecting) subtraction for a fee calculation — see
 * [checkedSumSats] for why silently wrapping is unacceptable here. */
internal fun checkedSubtractSats(minuend: Long, subtrahend: Long, whatIsBeingComputed: String): Long = try {
    Math.subtractExact(minuend, subtrahend)
} catch (e: ArithmeticException) {
    throw IllegalArgumentException("$whatIsBeingComputed overflows a 64-bit value — refusing to report it", e)
}

data class Transaction(
    val version: Long,
    val inputs: List<TxIn>,
    val outputs: List<TxOut>,
    val locktime: Long,
)

// Compact-size varint helper, shared with Psbt.kt via package visibility.
// We check bounds before every read to guarantee IllegalArgumentException on truncation,
// never leaking ArrayIndexOutOfBoundsException or producing silent corruption.
internal data class VarIntResult(val value: Long, val consumed: Int)

internal fun readCompactSize(bytes: ByteArray, offset: Int): VarIntResult {
    if (offset >= bytes.size) throw IllegalArgumentException("Truncated compact-size varint")
    val first = bytes[offset].toUByte().toInt()
    return when (first) {
        in 0..252 -> VarIntResult(first.toLong(), offset + 1)
        253 -> {
            if (offset + 3 > bytes.size) throw IllegalArgumentException("Truncated compact-size varint (0xfd)")
            val v = bytes[offset + 1].toUByte().toLong() or (bytes[offset + 2].toUByte().toLong() shl 8)
            // Bitcoin's compact-size encoding is canonical: a value must use the
            // SHORTEST form that can represent it. Accepting `fd 01 00` for 1
            // would let one serialization of the same logical content have many
            // byte representations — a divergence surface between MEGA and any
            // strict implementation reading the same file (Bitcoin Core and
            // Sparrow both reject non-minimal encodings).
            if (v < 253L) throw IllegalArgumentException("Non-minimal compact-size varint (0xfd used for $v)")
            VarIntResult(v, offset + 3)
        }
        254 -> {
            if (offset + 5 > bytes.size) throw IllegalArgumentException("Truncated compact-size varint (0xfe)")
            var v = 0L
            for (i in 0..3) v = v or (bytes[offset + 1 + i].toUByte().toLong() shl (i * 8))
            if (v <= 0xFFFFL) throw IllegalArgumentException("Non-minimal compact-size varint (0xfe used for $v)")
            VarIntResult(v, offset + 5)
        }
        255 -> {
            if (offset + 9 > bytes.size) throw IllegalArgumentException("Truncated compact-size varint (0xff)")
            var v = 0L
            for (i in 0..7) v = v or (bytes[offset + 1 + i].toUByte().toLong() shl (i * 8))
            // A value with bit 63 set reads back NEGATIVE as a Kotlin Long, which
            // would slip past every `offset + len > size` bounds check below and
            // reach copyOfRange with a negative end index. Reject it outright
            // rather than relying on a downstream throw.
            if (v < 0L) throw IllegalArgumentException("Compact-size varint exceeds the maximum supported length")
            if (v <= 0xFFFFFFFFL) throw IllegalArgumentException("Non-minimal compact-size varint (0xff used for $v)")
            VarIntResult(v, offset + 9)
        }
        else -> throw IllegalArgumentException("Invalid compact-size varint")
    }
}

internal fun writeCompactSize(value: Long): ByteArray {
    return when {
        value < 253 -> byteArrayOf(value.toByte())
        value <= 0xFFFFL -> byteArrayOf(0xfd.toByte()) +
            byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
        value <= 0xFFFFFFFFL -> byteArrayOf(0xfe.toByte()) +
            byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
                        ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte())
        else -> byteArrayOf(0xff.toByte()) +
            byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte(),
                        ((value shr 16) and 0xFF).toByte(), ((value shr 24) and 0xFF).toByte(),
                        ((value shr 32) and 0xFF).toByte(), ((value shr 40) and 0xFF).toByte(),
                        ((value shr 48) and 0xFF).toByte(), ((value shr 56) and 0xFF).toByte())
    }
}

// Fixed-width little-endian readers. Made internal so Psbt.kt can reuse them for
// typed accessor functions (witnessUtxo, sighashType, bip32Derivations, etc.).
internal fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
    if (offset + 4 > bytes.size) throw IllegalArgumentException("Truncated 4-byte field")
    var v = 0L
    for (i in 0..3) v = v or (bytes[offset + i].toUByte().toLong() shl (i * 8))
    return v
}

internal fun readUInt64LE(bytes: ByteArray, offset: Int): Long {
    if (offset + 8 > bytes.size) throw IllegalArgumentException("Truncated 8-byte field")
    var v = 0L
    for (i in 0..7) v = v or (bytes[offset + i].toUByte().toLong() shl (i * 8))
    return v
}

fun parseTransaction(bytes: ByteArray): Transaction {
    if (bytes.size < 4) throw IllegalArgumentException("Truncated transaction")
    var offset = 0
    val version = readUInt32LE(bytes, offset); offset += 4

    val inputCountResult = readCompactSize(bytes, offset); offset = inputCountResult.consumed
    val inputCount = inputCountResult.value

    val inputs = mutableListOf<TxIn>()
    for (i in 0 until inputCount) {
        if (offset + 32 > bytes.size) throw IllegalArgumentException("Truncated txid")
        val previousTxid = bytes.copyOfRange(offset, offset + 32); offset += 32

        val previousVout = readUInt32LE(bytes, offset); offset += 4

        val scriptSigLenResult = readCompactSize(bytes, offset); offset = scriptSigLenResult.consumed
        val scriptSigLen = scriptSigLenResult.value

        if (offset + scriptSigLen > bytes.size) throw IllegalArgumentException("Truncated scriptSig")
        val scriptSig = bytes.copyOfRange(offset, offset + scriptSigLen.toInt()); offset += scriptSigLen.toInt()

        val sequence = readUInt32LE(bytes, offset); offset += 4

        inputs.add(TxIn(previousTxid, previousVout, scriptSig, sequence))
    }

    val outputCountResult = readCompactSize(bytes, offset); offset = outputCountResult.consumed
    val outputCount = outputCountResult.value

    val outputs = mutableListOf<TxOut>()
    for (i in 0 until outputCount) {
        val valueSats = requireValidSatsAmount(readUInt64LE(bytes, offset), "Output $i amount"); offset += 8

        val scriptPubKeyLenResult = readCompactSize(bytes, offset); offset = scriptPubKeyLenResult.consumed
        val scriptPubKeyLen = scriptPubKeyLenResult.value

        if (offset + scriptPubKeyLen > bytes.size) throw IllegalArgumentException("Truncated scriptPubKey")
        val scriptPubKey = bytes.copyOfRange(offset, offset + scriptPubKeyLen.toInt()); offset += scriptPubKeyLen.toInt()

        outputs.add(TxOut(valueSats, scriptPubKey))
    }

    if (offset + 4 > bytes.size) throw IllegalArgumentException("Truncated locktime")
    val locktime = readUInt32LE(bytes, offset); offset += 4

    return Transaction(version, inputs, outputs, locktime)
}

fun serializeTransaction(tx: Transaction): ByteArray {
    val buf = ByteArrayOutputStream()
    buf.write(writeUInt32LE(tx.version))
    buf.write(writeCompactSize(tx.inputs.size.toLong()))
    for (input in tx.inputs) {
        buf.write(input.previousTxid)
        buf.write(writeUInt32LE(input.previousVout))
        buf.write(writeCompactSize(input.scriptSig.size.toLong()))
        buf.write(input.scriptSig)
        buf.write(writeUInt32LE(input.sequence))
    }
    buf.write(writeCompactSize(tx.outputs.size.toLong()))
    for (output in tx.outputs) {
        buf.write(writeUInt64LE(output.valueSats))
        buf.write(writeCompactSize(output.scriptPubKey.size.toLong()))
        buf.write(output.scriptPubKey)
    }
    buf.write(writeUInt32LE(tx.locktime))
    return buf.toByteArray()
}

// internal (not private): reused by SegwitSighash.kt and other files in
// this package that need to write the same little-endian fixed-width
// fields BIP143/PSBT serialization uses.
internal fun writeUInt32LE(value: Long): ByteArray {
    return byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )
}

internal fun writeUInt64LE(value: Long): ByteArray {
    return byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
        ((value shr 32) and 0xFF).toByte(),
        ((value shr 40) and 0xFF).toByte(),
        ((value shr 48) and 0xFF).toByte(),
        ((value shr 56) and 0xFF).toByte()
    )
}
