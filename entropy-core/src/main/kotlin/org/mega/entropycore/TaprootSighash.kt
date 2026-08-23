package org.mega.entropycore

/**
 * BIP341 Taproot key-path signature hash: `hash_TapSighash(0x00 ||
 * SigMsg(hash_type, 0))`, restricted to `hash_type` 0x00 (SIGHASH_DEFAULT)
 * and 0x01 (SIGHASH_ALL) — the same SIGHASH_ALL-or-implicit-default-only
 * scope PsbtSigning.kt's `validateSighashType` already enforces for this
 * app's non-Taproot signing (see that function's own doc comment: never
 * sign away less than what a review screen showed). SIGHASH_NONE/SINGLE/
 * ANYONECANPAY branches of BIP341's SigMsg are deliberately NOT
 * implemented here — not because they're hard, but because an untested
 * code path this app will never actually take is worse than not having
 * it (see docs/PSBT-SECURITY.md's "only SIGHASH_ALL" scope, applied here
 * identically). `ext_flag` is always 0 (key-path spending only — BIP342
 * script-path's ext_flag=1 extension is separate, later work) and no
 * annex is ever assumed present.
 *
 * Verified against BIP341's own wallet-test-vectors.json input-spending
 * cases 3 (hash_type 1) and 4 (hash_type 0) — the two cases in that file
 * matching this function's supported scope — comparing the computed
 * SigMsg byte-for-byte against the vector's own `sigMsg`, not just the
 * final hash, so a field-ordering bug would be caught precisely rather
 * than just "the hash doesn't match."
 */
fun computeTaprootKeyPathSighash(
    unsignedTx: Transaction,
    inputIndex: Int,
    spentOutputs: List<TxOut>,
    sighashType: Int,
): ByteArray {
    require(sighashType == 0 || sighashType == 1) {
        "Only SIGHASH_DEFAULT (0) or SIGHASH_ALL (1) are supported for Taproot key-path signing, got $sighashType"
    }
    require(spentOutputs.size == unsignedTx.inputs.size) {
        "spentOutputs must have exactly one entry per transaction input, got ${spentOutputs.size} for ${unsignedTx.inputs.size} inputs"
    }
    require(inputIndex in unsignedTx.inputs.indices) { "inputIndex $inputIndex out of range" }

    val shaPrevouts = sha256(
        unsignedTx.inputs.fold(ByteArray(0)) { acc, inp -> acc + inp.previousTxid + writeUInt32LE(inp.previousVout) },
    )
    val shaAmounts = sha256(
        spentOutputs.fold(ByteArray(0)) { acc, out -> acc + writeUInt64LE(out.valueSats) },
    )
    val shaScriptPubkeys = sha256(
        spentOutputs.fold(ByteArray(0)) { acc, out -> acc + writeCompactSize(out.scriptPubKey.size.toLong()) + out.scriptPubKey },
    )
    val shaSequences = sha256(
        unsignedTx.inputs.fold(ByteArray(0)) { acc, inp -> acc + writeUInt32LE(inp.sequence) },
    )
    val shaOutputs = sha256(
        unsignedTx.outputs.fold(ByteArray(0)) { acc, out ->
            acc + writeUInt64LE(out.valueSats) + writeCompactSize(out.scriptPubKey.size.toLong()) + out.scriptPubKey
        },
    )

    // spend_type = (ext_flag * 2) + annex_present = (0 * 2) + 0 = 0, always,
    // in this function's supported scope (key-path only, no annex).
    val spendType = 0

    val sigMsg = byteArrayOf(sighashType.toByte()) +
        writeUInt32LE(unsignedTx.version) +
        writeUInt32LE(unsignedTx.locktime) +
        shaPrevouts + shaAmounts + shaScriptPubkeys + shaSequences +
        shaOutputs +
        byteArrayOf(spendType.toByte()) +
        writeUInt32LE(inputIndex.toLong())

    return tapSighash(sigMsg)
}

/** hash_TapSighash(0x00 || sigMsg) — the 0x00 prefix is BIP341's "sighash
 * epoch" byte, reserved for future signature-algorithm changes without
 * needing a new tagged-hash tag. */
private fun tapSighash(sigMsg: ByteArray): ByteArray {
    val tagHash = sha256("TapSighash".toByteArray(Charsets.UTF_8))
    return sha256(tagHash + tagHash + byteArrayOf(0x00) + sigMsg)
}
