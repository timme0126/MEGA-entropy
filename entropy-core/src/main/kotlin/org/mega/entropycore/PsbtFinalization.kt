package org.mega.entropycore

/**
 * Strictly parses a bare-multisig witness script of EXACTLY the template
 * `OP_<M> <0x21 pubkey>* OP_<N> OP_CHECKMULTISIG` — the only script shape
 * this app builds (MultisigScript.kt) and the only one its finalizer knows
 * how to assemble a witness for. Returns (M, pubkeys-in-script-order), or
 * null for anything that deviates in any byte: wrong leading opcode, a
 * truncated/oversized push, a key-count opcode that disagrees with the
 * number of pushes, a missing OP_CHECKMULTISIG, trailing garbage, or an
 * M that exceeds N. Anything unrecognized is left UN-finalized rather than
 * turned into a guaranteed-invalid "final" witness.
 */
internal fun parseBareMultisigWitnessScript(script: ByteArray): Pair<Int, List<ByteArray>>? {
    if (script.size < 1 + 2 * 34 + 2) return null
    val first = script[0].toInt() and 0xFF
    if (first !in 0x51..0x60) return null
    val threshold = first - 0x50

    val pubkeys = mutableListOf<ByteArray>()
    var offset = 1
    while (offset < script.size && script[offset] == 0x21.toByte()) {
        if (offset + 34 > script.size) return null
        pubkeys.add(script.copyOfRange(offset + 1, offset + 34))
        offset += 34
    }
    if (pubkeys.size < 2 || pubkeys.size > 15) return null
    if (offset + 2 != script.size) return null
    val countByte = script[offset].toInt() and 0xFF
    if (countByte !in 0x51..0x60) return null
    if (countByte - 0x50 != pubkeys.size) return null
    if (script[offset + 1] != 0xAE.toByte()) return null
    if (threshold > pubkeys.size) return null
    return threshold to pubkeys
}

/**
 * Assembles the final witness stack for PSBT inputs, handling both P2WPKH
 * (single-sig) and P2WSH (multisig) paths. Per BIP174, a finalizer strips
 * intermediate data (partial_sig, bip32_derivation, witness_script) and
 * leaves only the witnessUtxo and the newly assembled finalScriptWitness.
 *
 * Fail-closed by construction: an input whose data doesn't match the exact
 * shape this finalizer understands (unknown script template, UTXO/script
 * mismatch, pubkey that doesn't match the UTXO) is left UNCHANGED — never
 * half-finalized into an invalid transaction.
 */
internal fun finalizePsbt(psbt: Psbt): Psbt {
    val finalizedInputs = psbt.inputs.mapIndexed { _, inputMap ->
        // Idempotency: never touch an already-finalized input.
        if (inputMap.finalScriptWitness() != null) {
            inputMap
        } else {
            val witnessScript = inputMap.witnessScript()
            if (witnessScript != null) {
                finalizeMultisigInput(inputMap, witnessScript)
            } else {
                finalizeSingleSigInput(inputMap)
            }
        }
    }
    return psbt.copy(inputs = finalizedInputs)
}

/**
 * Finalizes a P2WPKH input by assembling a 2-element witness stack:
 * [signature, pubkey]. Per BIP174, all intermediate entries are stripped.
 * The signature's pubkey must hash to the UTXO's actual P2WPKH program —
 * a partial_sig for any other pubkey would produce an invalid witness, so
 * the input is left untouched instead.
 */
private fun finalizeSingleSigInput(inputMap: PsbtMap): PsbtMap {
    val witnessUtxoEntry = inputMap.entries.find { it.keyType == 0x01 }
        ?: return inputMap
    val witnessUtxo = inputMap.witnessUtxo() ?: return inputMap
    val program = witnessUtxo.scriptPubKey
    if (program.size != 22 || program[0] != 0x00.toByte() || program[1] != 0x14.toByte()) return inputMap
    val expectedHash = program.copyOfRange(2, 22)

    val sig = inputMap.partialSigs().firstOrNull { hash160(it.pubkey).contentEquals(expectedHash) }
        ?: return inputMap
    val finalWitness = serializeWitnessStack(listOf(sig.signature, sig.pubkey))
    return PsbtMap(
        entries = listOf(
            witnessUtxoEntry,
            PsbtKeyValue(keyType = 0x08, keyData = ByteArray(0), value = finalWitness)
        )
    )
}

/**
 * Finalizes a P2WSH bare-multisig input. OP_CHECKMULTISIG matches signatures
 * to pubkeys positionally in script order, so we must order them exactly as
 * they appear in the witnessScript. A script that isn't exactly the
 * OP_M <keys> OP_N OP_CHECKMULTISIG template (or that doesn't match the
 * UTXO's scriptPubKey) is left unfinalized rather than mangled.
 */
private fun finalizeMultisigInput(inputMap: PsbtMap, witnessScript: ByteArray): PsbtMap {
    val (threshold, orderedPubkeys) = parseBareMultisigWitnessScript(witnessScript)
        ?: return inputMap

    // The UTXO being spent must actually commit to this script — otherwise
    // any witness assembled here is invalid by construction.
    val witnessUtxoEntry = inputMap.entries.find { it.keyType == 0x01 } ?: return inputMap
    val witnessUtxo = inputMap.witnessUtxo() ?: return inputMap
    val expectedScriptPubKey = byteArrayOf(0x00, 0x20) + sha256(witnessScript)
    if (!witnessUtxo.scriptPubKey.contentEquals(expectedScriptPubKey)) return inputMap

    // Build a pubkey→signature lookup. We map to List<Byte> because ByteArray
    // lacks structural equals/hashCode, and we need to match pubkeys correctly.
    val sigLookup = inputMap.partialSigs().associate { it.pubkey.toList() to it.signature }

    // Walk orderedPubkeys in script order, collecting signatures in that same order.
    val matchedSigs = orderedPubkeys.mapNotNull { pubkey ->
        sigLookup[pubkey.toList()]
    }

    // Not enough signatures yet to finalize.
    if (matchedSigs.size < threshold) {
        return inputMap
    }

    // OP_CHECKMULTISIG requires exactly `threshold` signatures on the stack.
    val selectedSigs = matchedSigs.take(threshold)

    // Witness stack for multisig: [empty dummy, sig_1, ..., sig_m, witnessScript].
    // The dummy element is mandatory to satisfy OP_CHECKMULTISIG's historical off-by-one bug.
    val finalWitnessItems = listOf(ByteArray(0)) + selectedSigs + listOf(witnessScript)
    val finalWitness = serializeWitnessStack(finalWitnessItems)

    return PsbtMap(
        entries = listOf(
            witnessUtxoEntry,
            PsbtKeyValue(keyType = 0x08, keyData = ByteArray(0), value = finalWitness)
        )
    )
}

/**
 * Serializes a witness stack per BIP144: compactSize(itemCount) followed by
 * compactSize(item.size) + item for each element. All items here are < 253 bytes,
 * so a single-byte compact size prefix suffices.
 */
private fun serializeWitnessStack(items: List<ByteArray>): ByteArray {
    return writeCompactSize(items.size.toLong()) + items.fold(ByteArray(0)) { acc, item ->
        acc + writeCompactSize(item.size.toLong()) + item
    }
}
