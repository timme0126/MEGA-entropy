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

    // Every valid PSBT must contain EXACTLY ONE unsigned transaction
    // (keyType 0x00), and BIP174 requires its key to be the single type byte
    // with NO keydata ("The key must only contain the 1 byte type").
    //
    // Both halves matter. Without the count check, a file carrying two
    // different type-0x00 keys (legal under the duplicate-FULL-key rule, since
    // `00` and `00 aa` are different keys) would resolve to whichever came
    // first here while a strict parser rejects the file outright — the same
    // display-vs-sign divergence the duplicate-key rule exists to prevent.
    // Without the keydata check, a lone malformed `00 aa` key would be
    // accepted as the unsigned transaction even though Bitcoin Core and
    // Sparrow both refuse it.
    val globalUnsignedTxEntries = globalMapResult.map.entries.filter { it.keyType == 0x00 }
    if (globalUnsignedTxEntries.size != 1) {
        throw IllegalArgumentException(
            "A PSBT must contain exactly one global unsigned transaction, found ${globalUnsignedTxEntries.size}",
        )
    }
    val unsignedTxEntry = globalUnsignedTxEntries.single()
    if (unsignedTxEntry.keyData.isNotEmpty()) {
        throw IllegalArgumentException("Global unsigned transaction key must be the type byte alone (BIP174)")
    }
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
        requireEmptyKeyDataForSingletonTypes(inputMapResult.map, SINGLETON_INPUT_KEY_TYPES, "input", i)
        inputs.add(inputMapResult.map)
    }

    // Parse output maps: exactly one per output in the unsigned transaction.
    val outputs = mutableListOf<PsbtMap>()
    for (i in 0 until unsignedTx.outputs.size) {
        val outputMapResult = readMap(bytes, offset)
        offset = outputMapResult.consumed
        requireEmptyKeyDataForSingletonTypes(outputMapResult.map, SINGLETON_OUTPUT_KEY_TYPES, "output", i)
        outputs.add(outputMapResult.map)
    }

    // Nothing may follow the final output map. Silently ignoring trailing
    // bytes would mean the reviewed-and-signed PSBT is only a PREFIX of the
    // bytes actually scanned, so a peer that interprets the remainder
    // differently could disagree with MEGA about what the file contains.
    if (offset != bytes.size) {
        throw IllegalArgumentException("Trailing bytes after the final PSBT output map (${bytes.size - offset} extra)")
    }

    return Psbt(unsignedTx, globalMapResult.map, inputs, outputs)
}

/** BIP174 input key types whose key is defined as the type byte ALONE (no
 * keydata): non_witness_utxo, witness_utxo, sighash_type, redeem_script,
 * witness_script, final_scriptSig, final_scriptWitness. The keyed types
 * (0x02 partial_sig, 0x06 bip32_derivation) legitimately carry a pubkey and
 * are deliberately absent. */
private val SINGLETON_INPUT_KEY_TYPES = setOf(0x00, 0x01, 0x03, 0x04, 0x05, 0x07, 0x08)

/** BIP174 output key types whose key is the type byte alone: redeem_script
 * and witness_script. 0x02 (bip32_derivation) carries a pubkey. */
private val SINGLETON_OUTPUT_KEY_TYPES = setOf(0x00, 0x01)

/**
 * Rejects a key that carries keydata for a type BIP174 defines as un-keyed.
 *
 * Every typed accessor in this file resolves such a type with
 * `entries.find { it.keyType == N }`, so a malformed `N <extra>` key would be
 * silently accepted and — if it sorted first — returned in place of the real
 * one. That turns a decoy key into a way to show the user one witness_utxo,
 * sighash type, or witness_script while a strict peer sees another. Failing
 * closed at parse time keeps every accessor unambiguous by construction.
 */
private fun requireEmptyKeyDataForSingletonTypes(
    map: PsbtMap,
    singletonTypes: Set<Int>,
    mapKind: String,
    mapIndex: Int,
) {
    val offender = map.entries.firstOrNull { it.keyType in singletonTypes && it.keyData.isNotEmpty() }
        ?: return
    throw IllegalArgumentException(
        "PSBT $mapKind map $mapIndex has a key of type 0x${offender.keyType.toString(16)} with keydata, " +
            "but BIP174 defines that key as the type byte alone",
    )
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
    // Per BIP174: 8-byte LE amount + compact-size-prefixed scriptPubKey,
    // and NOTHING ELSE — the value must be consumed exactly. A witness_utxo
    // record with extra bytes after its script isn't a form BIP174 defines;
    // silently ignoring the remainder would mean two different byte strings
    // (one with trailing junk, one without) parse to the SAME TxOut, which
    // is exactly the kind of ambiguity a canonical-encoding rule exists to
    // close (see the global-unsigned-tx and compact-size hardening above).
    if (value.size < 8) throw IllegalArgumentException("Truncated witnessUtxo amount")
    val amount = requireValidSatsAmount(readUInt64LE(value, 0), "witnessUtxo amount")
    val scriptLenResult = readCompactSize(value, 8)
    val scriptLen = scriptLenResult.value
    val offset = scriptLenResult.consumed
    if (offset + scriptLen > value.size) throw IllegalArgumentException("Truncated witnessUtxo script")
    val scriptPubKey = value.copyOfRange(offset, offset + scriptLen.toInt())
    val consumedEnd = offset + scriptLen.toInt()
    if (consumedEnd != value.size) {
        throw IllegalArgumentException(
            "witnessUtxo value has ${value.size - consumedEnd} trailing byte(s) after its script",
        )
    }
    return TxOut(amount, scriptPubKey)
}

fun PsbtMap.partialSigs(): List<PsbtPartialSig> =
    entries.filter { it.keyType == 0x02 }.map { PsbtPartialSig(it.keyData, it.value) }

fun PsbtMap.sighashType(): Long? =
    entries.find { it.keyType == 0x03 }?.value?.let {
        // PSBT_IN_SIGHASH_TYPE is a fixed 4-byte little-endian uint32 (BIP174)
        // — not "at least 4 bytes". Accepting a longer value would silently
        // discard trailing bytes a strict peer might interpret differently,
        // the same divergence risk every other exact-length field here
        // already guards against.
        if (it.size != 4) throw IllegalArgumentException("PSBT_IN_SIGHASH_TYPE must be exactly 4 bytes, got ${it.size}")
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
