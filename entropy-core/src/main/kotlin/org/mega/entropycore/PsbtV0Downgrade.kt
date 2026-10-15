package org.mega.entropycore

/**
 * Key types a v2 document's GLOBAL map may carry (BIP174 v2: 0x02
 * transaction version, 0x03 fallback locktime, 0x04 input count, 0x05
 * output count, 0x06 tx-modifiable flags, 0x07 version) that a strict v0
 * document cannot — [downgradePsbtToV0] drops them only after
 * [verifyDroppableV2Fields] confirms each is redundant with the embedded
 * unsigned transaction, and refuses any other global key type it does
 * not understand rather than guess.
 */
private val V2_GLOBAL_TYPES = setOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07)

/**
 * Key types a v2 document's INPUT maps may carry (BIP174 v2: 0x0E
 * previous txid, 0x0F output index, 0x10 sequence, 0x11 required
 * time-locktime, 0x12 required height-locktime) that a v0 document
 * cannot — a v0 reader reads exactly these values positionally from the
 * v0 document's own embedded unsigned transaction instead, so dropping
 * them loses nothing a v0 coordinator needs. 0x13 is v2's
 * per-input-key-type MAX marker and is likewise never a v0 input key.
 */
private val V2_INPUT_TYPES = setOf(0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13)

/**
 * Re-serializes a PSBT as a strict BIP174 v0 document, for legacy
 * coordinator wallets — notably BlueWallet, whose BIP174 parser
 * (bitcoinjs-lib 7.0.2, verified 2026-10-13) rejects any global key type
 * 0x02 outright with "Unknown global type 2", refusing to import
 * otherwise perfectly valid PSBTs. Sparrow and other v2-aware
 * coordinators accept the original v2 output unchanged; this function
 * only exists to keep legacy coordinators working, and the primary
 * export path must keep using [serializePsbt] — this is strictly a
 * second, compatibility output.
 *
 * What a faithful v0 re-encoding can and cannot represent:
 *  - v2 global fields 0x02..0x07: a v2 document is REQUIRED (by BIP174
 *    v2's own rules) to carry these as a redundant, mechanical encoding
 *    of the embedded unsigned transaction, so they are dropped after
 *    [verifyDroppableV2Fields] proves that redundancy here — never
 *    dropped on trust.
 *  - v2 input fields 0x0E..0x12: likewise purely redundant — the same
 *    values a v0 reader reads positionally from the embedded unsigned
 *    transaction's own input list — so they are dropped, and the
 *    0x12 (height-locktime) case, which v0 has no per-input
 *    representation for at all, is dropped with the SAME guarantee
 *    rather than silently mis-encoded as a v0 field.
 *  - v0 global 0x00: a v0 reader's ONLY source of the unsigned
 *    transaction (a v2 document may legitimately omit it), so it is
 *    ALWAYS present in the output — passed through BYTE-IDENTICAL from
 *    the source document's own global map, never re-encoded (re-encoding
 *    a document's own canonical bytes is exactly the kind of silent,
 *    untestable difference this function exists to avoid introducing).
 *
 * Everything else is a pure pass-through of the exact stored bytes:
 * global 0x01 (xpub) and 0xFB (proprietary) entries keep their exact
 * [PsbtKeyValue.keyData]/[PsbtKeyValue.value], every input entry with a
 * v0-legal key type (0x00..0x0D and 0xFB) is copied verbatim, output
 * maps pass through verbatim, and map entry ORDER is preserved exactly
 * as stored.
 *
 * Post-conditions, verified against the ORIGINAL document before
 * returning (never just against the one just built):
 *  - the output re-parses with [parsePsbt];
 *  - the 0x00 global entry is the original's, byte-identical, and
 *    appears FIRST in the output's global map;
 *  - every retained entry (global and input) is still present,
 *    byte-identical, in the same order;
 *  - nothing outside {v2 global 0x02..0x07, v2 input 0x0E..0x13} was
 *    dropped.
 *
 * @throws IllegalArgumentException if the source document's global map
 * does not carry exactly one keyless 0x00 entry, or contains a global or
 * input key type outside the known v2/v0 sets.
 */
fun downgradePsbtToV0(psbtBytes: ByteArray): ByteArray {
    val psbt = parsePsbt(psbtBytes)

    for (entry in psbt.global.entries) {
        require(entry.keyType in V2_GLOBAL_TYPES || entry.keyType == 0x00 ||
            entry.keyType == 0x01 || entry.keyType == 0xFB) {
            "Downgrade to v0: unrecognized global key type 0x${entry.keyType.toString(16)} — " +
                "refusing to guess how a v0 reader should interpret it."
        }
    }
    for ((i, inputMap) in psbt.inputs.withIndex()) {
        for (entry in inputMap.entries) {
            require(entry.keyType !in V2_INPUT_TYPES && (entry.keyType <= 0x0D || entry.keyType == 0xFB)) {
                "Downgrade to v0: input $i carries an input key type " +
                    "0x${entry.keyType.toString(16)} a v0 document cannot represent, " +
                    "and no other v0 field carries this data — refusing to guess."
            }
        }
    }

    val unsignedTxEntries = psbt.global.entries.filter { it.keyType == 0x00 }
    require(unsignedTxEntries.size == 1 && unsignedTxEntries.single().keyData.isEmpty()) {
        "Downgrade to v0: source document's global map must carry exactly one 0x00 " +
            "(unsigned transaction) entry with no key data — found ${unsignedTxEntries.size}."
    }
    val unsignedTxEntry = unsignedTxEntries.single()

    verifyDroppableV2Fields(psbt)

    val globalKept = psbt.global.entries.filter { it.keyType != 0x00 && it.keyType !in V2_GLOBAL_TYPES }
    val global = PsbtMap(listOf(unsignedTxEntry) + globalKept)

    val inputs = psbt.inputs.map { inputMap ->
        PsbtMap(inputMap.entries.filter { it.keyType !in V2_INPUT_TYPES })
    }

    val downgraded = Psbt(psbt.unsignedTx, global, inputs, psbt.outputs)
    val out = serializePsbt(downgraded)

    // Post-conditions, checked against the ORIGINAL, not just the
    // document we just built. NOTE: these compare BYTES, never `==` —
    // Transaction/TxIn/TxOut/PsbtKeyValue are data classes whose fields
    // include ByteArray, and data-class equality on arrays is
    // identity-based, so a plain `==` would reject even a
    // byte-identical round-trip.
    val reparsed = parsePsbt(out)
    fun entriesEquivalent(a: List<PsbtKeyValue>, b: List<PsbtKeyValue>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) ->
            x.keyType == y.keyType && x.keyData.contentEquals(y.keyData) && x.value.contentEquals(y.value)
        }
    check(
        serializeTransaction(reparsed.unsignedTx).contentEquals(serializeTransaction(psbt.unsignedTx)),
    ) { "Downgraded PSBT's unsigned transaction changed." }
    val reGlobal = reparsed.global.entries
    check(
        reGlobal.isNotEmpty() && reGlobal.first().keyType == 0x00 &&
            reGlobal.first().value.contentEquals(unsignedTxEntry.value),
    ) { "Downgraded PSBT's global 0x00 entry is missing, misplaced, or altered." }
    check(entriesEquivalent(reGlobal.drop(1), globalKept)) {
        "Downgraded PSBT's retained global entries changed."
    }
    check(reparsed.inputs.size == psbt.inputs.size && reparsed.outputs.size == psbt.outputs.size) {
        "Downgraded PSBT changed the number of input or output maps."
    }
    for (i in psbt.inputs.indices) {
        val originalKept = psbt.inputs[i].entries.filter { it.keyType !in V2_INPUT_TYPES }
        check(entriesEquivalent(reparsed.inputs[i].entries, originalKept)) { "Downgrade altered input map $i." }
    }
    for (i in psbt.outputs.indices) {
        check(entriesEquivalent(reparsed.outputs[i].entries, psbt.outputs[i].entries)) {
            "Downgrade altered output map $i."
        }
    }
    return out
}

/**
 * Verifies the two v2 global fields with an exact, mechanical
 * relationship to the embedded unsigned transaction, before
 * [downgradePsbtToV0] drops them:
 *  - 0x02 (transaction version) must be the 4-byte little-endian
 *    encoding of the embedded transaction's own version;
 *  - 0x04 (input count) and 0x05 (output count), when present, must
 *    match the embedded transaction's own counts.
 * 0x03 (fallback locktime), 0x06 (tx-modifiable flags), and 0x07 (v2
 * version) need no per-value check: 0x03 must, by BIP174 v2's own
 * rules, equal the embedded transaction's nLockTime, and 0x06/0x07 are
 * v2-native concepts a v0 reader has no field for at all — dropping
 * them is the entire point of a v0 downgrade, and the embedded
 * transaction (which v0 carries in full, verbatim, under global 0x00)
 * is a v0 reader's only source of truth for them anyway.
 */
private fun verifyDroppableV2Fields(psbt: Psbt) {
    val tx = psbt.unsignedTx
    for (entry in psbt.global.entries.filter { it.keyType == 0x02 }) {
        val encodedVersion = entry.value.decodeUInt32LE(0)
        require(encodedVersion == tx.version) {
            "Downgrade to v0: global 0x02 encodes transaction version $encodedVersion, " +
                "but the embedded unsigned transaction's own version is ${tx.version} — refusing."
        }
    }
    for (entry in psbt.global.entries.filter { it.keyType == 0x04 }) {
        val n = entry.value.decodeUInt32LE(0)
        require(n == tx.inputs.size.toLong()) {
            "Downgrade to v0: global 0x04 declares $n inputs, the embedded transaction has " +
                "${tx.inputs.size} — refusing."
        }
    }
    for (entry in psbt.global.entries.filter { it.keyType == 0x05 }) {
        val n = entry.value.decodeUInt32LE(0)
        require(n == tx.outputs.size.toLong()) {
            "Downgrade to v0: global 0x05 declares $n outputs, the embedded transaction has " +
                "${tx.outputs.size} — refusing."
        }
    }
}

private fun ByteArray.decodeUInt32LE(offset: Int): Long {
    require(size - offset >= 4) { "Truncated 32-bit little-endian field at offset $offset" }
    return (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
}
