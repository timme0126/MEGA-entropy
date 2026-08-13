package org.mega.entropycore

import java.io.ByteArrayOutputStream

data class PsbtKeyValue(
    val keyType: Int,       // 0-255, unsigned — the key's first byte, read as an unsigned Int (a Kotlin Byte is signed; mask with `and 0xFF` when converting)
    val keyData: ByteArray, // key bytes AFTER the type byte (empty ByteArray for un-keyed types)
    val value: ByteArray,
)

data class PsbtMap(val entries: List<PsbtKeyValue>)

data class Psbt(
    val unsignedTx: Transaction,
    val global: PsbtMap,
    val inputs: List<PsbtMap>,
    val outputs: List<PsbtMap>,
)

data class PsbtBip32Derivation(
    val pubkey: ByteArray,
    val masterFingerprint: ByteArray, // 4 bytes
    val path: List<Long>,             // each element read as UNSIGNED 32-bit little-endian (must use Long, not Int — a hardened index like 0x80000000 does not fit in a signed Int)
)

data class PsbtPartialSig(val pubkey: ByteArray, val signature: ByteArray)

fun parsePsbt(bytes: ByteArray): Psbt {
    if (bytes.size < 5) throw IllegalArgumentException("Truncated PSBT header")
    val magic = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte())
    for (i in 0 until 5) {
        if (bytes[i] != magic[i]) throw IllegalArgumentException("Not a PSBT: wrong magic bytes")
    }

    var offset = 5

    // Parse global map using the shared map-reading loop.
    // We check bounds at every step to guarantee IllegalArgumentException on truncation.
    val globalMapResult = readMap(bytes, offset)
    offset = globalMapResult.consumed

    // Every valid PSBT must contain the unsigned transaction (keyType 0x00).
    val unsignedTxEntry = globalMapResult.map.entries.find { it.keyType == 0x00 }
        ?: throw IllegalArgumentException("Missing unsigned transaction in global map")
    val unsignedTx = parseTransaction(unsignedTxEntry.value)
    // The unsigned tx must be exactly the bytes given, nothing more and in
    // canonical form: re-serializing the parsed tx must reproduce the value
    // byte-for-byte. This rejects trailing garbage, non-minimal varint
    // encodings, and witness-serialized (0x00 0x01 marker/flag) txs, which
    // BIP174 forbids as the global unsigned transaction.
    if (!serializeTransaction(unsignedTx).contentEquals(unsignedTxEntry.value)) {
        throw IllegalArgumentException("Unsigned transaction is not in canonical non-witness serialization")
    }
    // BIP174: the unsigned tx's scriptSigs must be empty (script data lives
    // in the input maps). A non-empty scriptSig would otherwise flow into a
    // finalized transaction verbatim (see extractFinalTransactionHex).
    if (unsignedTx.inputs.any { it.scriptSig.isNotEmpty() }) {
        throw IllegalArgumentException("Unsigned transaction must have empty scriptSigs (BIP174)")
    }

    // Parse input maps: exactly one per input in the unsigned transaction.
    val inputs = mutableListOf<PsbtMap>()
    for (i in 0 until unsignedTx.inputs.size) {
        val inputMapResult = readMap(bytes, offset)
        offset = inputMapResult.consumed
        inputs.add(inputMapResult.map)
    }

    // Parse output maps: exactly one per output in the unsigned transaction.
    val outputs = mutableListOf<PsbtMap>()
    for (i in 0 until unsignedTx.outputs.size) {
        val outputMapResult = readMap(bytes, offset)
        offset = outputMapResult.consumed
        outputs.add(outputMapResult.map)
    }

    return Psbt(unsignedTx, globalMapResult.map, inputs, outputs)
}

private data class MapResult(val map: PsbtMap, val consumed: Int)

private fun readMap(bytes: ByteArray, offset: Int): MapResult {
    val entries = mutableListOf<PsbtKeyValue>()
    // BIP174: "The keys must be unique within each map." A duplicate full key
    // (type byte + keyData) would otherwise resolve to the first occurrence
    // here while another implementation may take the last — a display-vs-sign
    // ambiguity — so it fails closed at parse time. Distinct keyData under the
    // same keyType (e.g. one bip32_derivation per pubkey) remains allowed.
    val seenKeys = HashSet<List<Byte>>()
    var currentOffset = offset

    while (true) {
        // Read key length. A length of 0 signals the map terminator.
        val keyLenResult = readCompactSize(bytes, currentOffset)
        currentOffset = keyLenResult.consumed
        val keyLen = keyLenResult.value

        if (keyLen == 0L) {
            // Terminator consumed; return the accumulated map.
            return MapResult(PsbtMap(entries), currentOffset)
        }

        if (currentOffset + keyLen > bytes.size) throw IllegalArgumentException("Truncated key in map")
        val key = bytes.copyOfRange(currentOffset, currentOffset + keyLen.toInt())
        currentOffset += keyLen.toInt()

        if (!seenKeys.add(key.toList())) {
            throw IllegalArgumentException("Duplicate key in PSBT map (BIP174 requires unique keys)")
        }

        // key[0] is keyType (unsigned), rest is keyData.
        val keyType = key[0].toUByte().toInt()
        val keyData = if (key.size > 1) key.copyOfRange(1, key.size) else ByteArray(0)

        // Read value length and value bytes.
        val valLenResult = readCompactSize(bytes, currentOffset)
        currentOffset = valLenResult.consumed
        val valLen = valLenResult.value

        if (currentOffset + valLen > bytes.size) throw IllegalArgumentException("Truncated value in map")
        val value = bytes.copyOfRange(currentOffset, currentOffset + valLen.toInt())
        currentOffset += valLen.toInt()

        entries.add(PsbtKeyValue(keyType, keyData, value))
    }
}

fun serializePsbt(psbt: Psbt): ByteArray {
    val buf = ByteArrayOutputStream()
    // Write the 5-byte magic header.
    buf.write(byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xFF.toByte()))

    // Serialize global map entries in stored order, followed by the 0x00 terminator.
    for (entry in psbt.global.entries) {
        buf.write(writeCompactSize((1 + entry.keyData.size).toLong()))
        buf.write(byteArrayOf(entry.keyType.toByte()) + entry.keyData)
        buf.write(writeCompactSize(entry.value.size.toLong()))
        buf.write(entry.value)
    }
    buf.write(0x00)

    // Serialize each input map in order.
    for (inputMap in psbt.inputs) {
        for (entry in inputMap.entries) {
            buf.write(writeCompactSize((1 + entry.keyData.size).toLong()))
            buf.write(byteArrayOf(entry.keyType.toByte()) + entry.keyData)
            buf.write(writeCompactSize(entry.value.size.toLong()))
            buf.write(entry.value)
        }
        buf.write(0x00)
    }

    // Serialize each output map in order.
    for (outputMap in psbt.outputs) {
        for (entry in outputMap.entries) {
            buf.write(writeCompactSize((1 + entry.keyData.size).toLong()))
            buf.write(byteArrayOf(entry.keyType.toByte()) + entry.keyData)
            buf.write(writeCompactSize(entry.value.size.toLong()))
            buf.write(entry.value)
        }
        buf.write(0x00)
    }

    return buf.toByteArray()
}

// Typed accessor functions. Each filters entries by keyType per BIP174,
// parsing the value payload according to the spec for that key type.
// We reuse the internal LE-read helpers from Transaction.kt for consistency.

fun PsbtMap.witnessUtxo(): TxOut? {
    val value = entries.find { it.keyType == 0x01 }?.value ?: return null
    // Per BIP174: 8-byte LE amount + compact-size-prefixed scriptPubKey.
    if (value.size < 8) throw IllegalArgumentException("Truncated witnessUtxo amount")
    val amount = readUInt64LE(value, 0)
    val scriptLenResult = readCompactSize(value, 8)
    val scriptLen = scriptLenResult.value
    val offset = scriptLenResult.consumed
    if (offset + scriptLen > value.size) throw IllegalArgumentException("Truncated witnessUtxo script")
    val scriptPubKey = value.copyOfRange(offset, offset + scriptLen.toInt())
    return TxOut(amount, scriptPubKey)
}

fun PsbtMap.partialSigs(): List<PsbtPartialSig> =
    entries.filter { it.keyType == 0x02 }.map { PsbtPartialSig(it.keyData, it.value) }

fun PsbtMap.sighashType(): Long? =
    entries.find { it.keyType == 0x03 }?.value?.let {
        if (it.size < 4) throw IllegalArgumentException("Truncated sighashType")
        readUInt32LE(it, 0)
    }

fun PsbtMap.witnessScript(): ByteArray? =
    entries.find { it.keyType == 0x05 }?.value

/** PSBT_IN_BIP32_DERIVATION (input-map keyType 0x06) — which cosigner
 * pubkey(s) an INPUT's script involves, and the path to derive each from
 * its own master. */
fun PsbtMap.bip32Derivations(): List<PsbtBip32Derivation> = parseBip32Derivations(entries, 0x06)

/** PSBT_OUT_BIP32_DERIVATION (output-map keyType 0x02 — a different
 * meaning than the SAME numeric keyType in an input map, where 0x02 is
 * PSBT_IN_PARTIAL_SIG; BIP174 scopes keyTypes independently per map kind)
 * — which cosigner pubkey(s) an OUTPUT pays to, and the path to derive
 * each from its own master. The standard way a wallet marks its own
 * change output: present here with a path/fingerprint matching one of
 * the transaction's own inputs, versus absent (or matching nothing) for
 * a plain external payment. Value wire format is identical to the input
 * case, so this shares the same parsing logic. */
fun PsbtMap.outputBip32Derivations(): List<PsbtBip32Derivation> = parseBip32Derivations(entries, 0x02)

private fun parseBip32Derivations(entries: List<PsbtKeyValue>, keyType: Int): List<PsbtBip32Derivation> =
    entries.filter { it.keyType == keyType }.map { entry ->
        val value = entry.value
        if (value.size < 4) throw IllegalArgumentException("Truncated bip32Derivation fingerprint")
        val fingerprint = value.copyOfRange(0, 4)
        val remaining = value.size - 4
        if (remaining % 4 != 0) throw IllegalArgumentException("Truncated bip32Derivation path")
        val pathElementCount = remaining / 4
        val path = (0 until pathElementCount).map { i ->
            readUInt32LE(value, 4 + 4 * i)
        }
        PsbtBip32Derivation(entry.keyData, fingerprint, path)
    }

fun PsbtMap.finalScriptWitness(): ByteArray? =
    entries.find { it.keyType == 0x08 }?.value
