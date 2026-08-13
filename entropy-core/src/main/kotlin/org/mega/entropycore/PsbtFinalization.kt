package org.mega.entropycore

/**
 * Assembles the final witness stack for PSBT inputs, handling both P2WPKH
 * (single-sig) and P2WSH (multisig) paths. Per BIP174, a finalizer strips
 * intermediate data (partial_sig, bip32_derivation, witness_script) and
 * leaves only the witnessUtxo and the newly assembled finalScriptWitness.
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
 */
private fun finalizeSingleSigInput(inputMap: PsbtMap): PsbtMap {
    val sig = inputMap.partialSigs().firstOrNull() ?: return inputMap
    val finalWitness = serializeWitnessStack(listOf(sig.signature, sig.pubkey))
    val witnessUtxoEntry = inputMap.entries.find { it.keyType == 0x01 }
        ?: throw IllegalStateException("Missing witnessUtxo for P2WPKH input")
    return PsbtMap(
        entries = listOf(
            witnessUtxoEntry,
            PsbtKeyValue(keyType = 0x08, keyData = ByteArray(0), value = finalWitness)
        )
    )
}

/**
 * Finalizes a P2WSH multisig input. OP_CHECKMULTISIG matches signatures
 * to pubkeys positionally in script order, so we must order them exactly
 * as they appear in the witnessScript.
 */
private fun finalizeMultisigInput(inputMap: PsbtMap, witnessScript: ByteArray): PsbtMap {
    // Parse the fixed witnessScript template to recover (threshold, orderedPubkeys).
    var offset = 0
    val threshold = (witnessScript[offset].toInt() and 0xFF) - 0x50
    offset++

    val orderedPubkeys = mutableListOf<ByteArray>()
    while (offset < witnessScript.size) {
        if (witnessScript[offset] == 0x21.toByte()) {
            val pubkey = witnessScript.sliceArray(offset + 1 until offset + 34)
            orderedPubkeys.add(pubkey)
            offset += 34
        } else {
            break
        }
    }

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

    val witnessUtxoEntry = inputMap.entries.find { it.keyType == 0x01 }
        ?: throw IllegalStateException("Missing witnessUtxo for P2WSH input")
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
