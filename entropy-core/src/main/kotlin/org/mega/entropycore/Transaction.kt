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

private data class ParsedInputsResult(val inputs: List<TxIn>, val consumed: Int)

private fun parseTxInputs(bytes: ByteArray, offset: Int, count: Long): ParsedInputsResult {
    var currentOffset = offset
    val inputs = mutableListOf<TxIn>()
    for (i in 0 until count) {
        if (currentOffset + 32 > bytes.size) throw IllegalArgumentException("Truncated txid")
        val previousTxid = bytes.copyOfRange(currentOffset, currentOffset + 32); currentOffset += 32

        val previousVout = readUInt32LE(bytes, currentOffset); currentOffset += 4

        val scriptSigLenResult = readCompactSize(bytes, currentOffset); currentOffset = scriptSigLenResult.consumed
        val scriptSigLen = scriptSigLenResult.value

        if (currentOffset + scriptSigLen > bytes.size) throw IllegalArgumentException("Truncated scriptSig")
        val scriptSig = bytes.copyOfRange(currentOffset, currentOffset + scriptSigLen.toInt()); currentOffset += scriptSigLen.toInt()

        val sequence = readUInt32LE(bytes, currentOffset); currentOffset += 4

        inputs.add(TxIn(previousTxid, previousVout, scriptSig, sequence))
    }
    return ParsedInputsResult(inputs, currentOffset)
}

private data class ParsedOutputsResult(val outputs: List<TxOut>, val consumed: Int)

private fun parseTxOutputs(bytes: ByteArray, offset: Int, count: Long): ParsedOutputsResult {
    var currentOffset = offset
    val outputs = mutableListOf<TxOut>()
    for (i in 0 until count) {
        val valueSats = requireValidSatsAmount(readUInt64LE(bytes, currentOffset), "Output $i amount"); currentOffset += 8

        val scriptPubKeyLenResult = readCompactSize(bytes, currentOffset); currentOffset = scriptPubKeyLenResult.consumed
        val scriptPubKeyLen = scriptPubKeyLenResult.value

        if (currentOffset + scriptPubKeyLen > bytes.size) throw IllegalArgumentException("Truncated scriptPubKey")
        val scriptPubKey = bytes.copyOfRange(currentOffset, currentOffset + scriptPubKeyLen.toInt()); currentOffset += scriptPubKeyLen.toInt()

        outputs.add(TxOut(valueSats, scriptPubKey))
    }
    return ParsedOutputsResult(outputs, currentOffset)
}

fun parseTransaction(bytes: ByteArray): Transaction {
    if (bytes.size < 4) throw IllegalArgumentException("Truncated transaction")
    var offset = 0
    val version = readUInt32LE(bytes, offset); offset += 4

    val inputCountResult = readCompactSize(bytes, offset); offset = inputCountResult.consumed
    val inputsResult = parseTxInputs(bytes, offset, inputCountResult.value)
    offset = inputsResult.consumed

    val outputCountResult = readCompactSize(bytes, offset); offset = outputCountResult.consumed
    val outputsResult = parseTxOutputs(bytes, offset, outputCountResult.value)
    offset = outputsResult.consumed

    if (offset + 4 > bytes.size) throw IllegalArgumentException("Truncated locktime")
    val locktime = readUInt32LE(bytes, offset); offset += 4

    return Transaction(version, inputsResult.inputs, outputsResult.outputs, locktime)
}

/**
 * Parses a previous transaction's raw bytes for PSBT_IN_NON_WITNESS_UTXO
 * purposes. Unlike [parseTransaction] — which is intentionally strict and
 * accepts ONLY the canonical legacy (non-witness) form, because BIP174
 * mandates that exact form for the PSBT's OWN global unsigned transaction
 * — this accepts EITHER valid Bitcoin wire serialization: plain legacy, or
 * BIP144 witness-serialized (marker 0x00, flag 0x01, one witness stack per
 * input after the outputs).
 *
 * BIP174 defines non_witness_utxo's value as simply "the transaction in
 * network serialization format the current input spends from" — there is
 * no canonical-form requirement, unlike the global unsigned tx. In
 * practice, a node's raw-transaction lookup returns the witness-inclusive
 * form whenever the REFERENCED (ancestor) transaction itself carries
 * witness data — the norm for the overwhelming majority of transactions
 * today, regardless of whether the CURRENT input being signed is itself
 * segwit. A parser that only accepts the legacy form breaks on nearly
 * every real-world PSBT a segwit-aware coordinator (e.g. Sparrow) embeds
 * a non_witness_utxo into — it misreads the marker byte as a zero input
 * count and the flag byte as an output count, producing garbage that (at
 * best) fails a later sanity check with a misleading message, silently
 * making every input relying on that non_witness_utxo unsignable.
 *
 * Witness data itself is read and discarded — non_witness_utxo exists only
 * to let a signer independently verify the spent output's amount/
 * scriptPubKey and recompute this transaction's TXID, neither of which
 * involves the witness stack. The security-critical check — that this
 * transaction's TXID (computed EXCLUDING witness data, exactly as BIP141
 * defines it, regardless of which serialization was actually used here)
 * equals the input's declared previousTxid — is performed by
 * [resolveInputUtxo] against this function's [Transaction.version]/
 * inputs/outputs/locktime, unchanged by which form was accepted.
 */
internal fun parsePreviousTransactionAllowingWitness(bytes: ByteArray): Transaction {
    if (bytes.size < 6) throw IllegalArgumentException("Truncated transaction")
    var offset = 0
    val version = readUInt32LE(bytes, offset); offset += 4

    // BIP144: a segwit-serialized transaction always starts its post-version
    // bytes with marker 0x00, flag 0x01. A LEGACY transaction can never
    // legitimately have this pair here — an input count of exactly 0 (what
    // marker byte 0x00 would otherwise mean) describes a transaction with
    // no inputs, which spends nothing and is never valid — so this pair is
    // an unambiguous, standard signal, not a heuristic guess.
    val hasWitness = bytes[offset] == 0x00.toByte() && bytes[offset + 1] == 0x01.toByte()
    if (hasWitness) offset += 2

    val inputCountResult = readCompactSize(bytes, offset); offset = inputCountResult.consumed
    val inputsResult = parseTxInputs(bytes, offset, inputCountResult.value)
    offset = inputsResult.consumed

    val outputCountResult = readCompactSize(bytes, offset); offset = outputCountResult.consumed
    val outputsResult = parseTxOutputs(bytes, offset, outputCountResult.value)
    offset = outputsResult.consumed

    if (hasWitness) {
        // One witness stack per input, in input order (BIP144): compactSize
        // item count, then each item as compactSize length + bytes. Read and
        // discard — see the doc comment above for why this data isn't kept.
        for (i in inputsResult.inputs.indices) {
            val itemCountResult = readCompactSize(bytes, offset); offset = itemCountResult.consumed
            for (j in 0 until itemCountResult.value) {
                val itemLenResult = readCompactSize(bytes, offset); offset = itemLenResult.consumed
                val itemLen = itemLenResult.value
                if (offset + itemLen > bytes.size) throw IllegalArgumentException("Truncated witness item")
                offset += itemLen.toInt()
            }
        }
    }

    if (offset + 4 > bytes.size) throw IllegalArgumentException("Truncated locktime")
    val locktime = readUInt32LE(bytes, offset); offset += 4

    if (offset != bytes.size) {
        throw IllegalArgumentException("Trailing bytes after transaction data (${bytes.size - offset} extra)")
    }

    return Transaction(version, inputsResult.inputs, outputsResult.outputs, locktime)
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
