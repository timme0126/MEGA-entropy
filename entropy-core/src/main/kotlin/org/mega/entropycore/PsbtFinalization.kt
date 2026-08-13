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
    val finalizedInputs = psbt.inputs.mapIndexed { index, inputMap ->
        // Idempotency: never touch an already-finalized input.
        if (inputMap.finalScriptWitness() != null) {
            inputMap
        } else {
            val witnessScript = inputMap.witnessScript()
            if (witnessScript != null) {
                finalizeMultisigInput(psbt.unsignedTx, index, inputMap, witnessScript)
            } else {
                finalizeSingleSigInput(psbt.unsignedTx, index, inputMap)
            }
        }
    }
    return psbt.copy(inputs = finalizedInputs)
}

/**
 * Cryptographically verifies one candidate partial_sig against the EXACT
 * input it claims to sign, rather than trusting its mere presence for the
 * right pubkey — a signature's byte value is attacker/coordinator
 * controlled and must never be assumed valid just because it arrived
 * attached to the right key. In order:
 *
 * 1. The value must carry BIP174's mandatory trailing sighash-type byte.
 * 2. That byte must be SIGHASH_ALL (0x01) — the ONLY type this app ever
 *    finalizes with, mirroring [validateSighashType]'s restriction on the
 *    signER side. Without this check, a cosigner's signature could carry a
 *    DIFFERENT sighash byte than the PSBT's own declared
 *    PSBT_IN_SIGHASH_TYPE field (which [finalizePsbt]'s caller may not even
 *    have inspected for a signature it didn't itself produce) and still
 *    slip into a "fully signed" transaction that signs away less than what
 *    was reviewed.
 * 3. The remaining bytes must be a well-formed, low-S DER signature
 *    ([verifyEcdsa] itself enforces this) that actually verifies against
 *    [pubkey] and the BIP143 sighash for this exact input/scriptCode/amount.
 *
 * Never throws — [verifyEcdsa] already guarantees this, and every
 * additional check here is a plain boolean condition, not a parse that can
 * fail unexpectedly. An input with only invalid candidate signatures is
 * left unfinalized by the callers below, exactly as if no signatures had
 * arrived at all.
 */
internal fun isValidPartialSig(
    unsignedTx: Transaction,
    inputIndex: Int,
    scriptCode: ByteArray,
    amountSats: Long,
    pubkey: ByteArray,
    signature: ByteArray,
): Boolean {
    if (signature.isEmpty()) return false
    val sighashByte = signature.last().toInt() and 0xFF
    if (sighashByte != 1) return false
    val derSignature = signature.copyOfRange(0, signature.size - 1)
    return try {
        val sighash = computeSegwitSighash(unsignedTx, inputIndex, scriptCode, amountSats, sighashByte)
        verifyEcdsa(pubkey, sighash, derSignature)
    } catch (e: Exception) {
        // computeSegwitSighash can throw for a structurally odd (but
        // parser-accepted) input — e.g. an inputIndex a hand-built Psbt
        // doesn't actually have a matching unsignedTx entry for. Any such
        // failure means "this signature cannot be verified", which is the
        // same as "not valid" for finalization purposes.
        false
    }
}

/**
 * The exact structural conditions [finalizePsbt] itself requires before an
 * input can EVER be finalized, independent of how many/which signatures
 * have arrived: a parseable script template, and a witness_utxo that
 * actually commits to it. Exposed so [computePsbtSummary]'s "will this
 * finalize" prediction can never diverge from what the finalizer itself
 * requires — an input the finalizer would refuse to touch must never be
 * predicted "ready to broadcast", no matter how many (even
 * cryptographically valid) signatures show up for it.
 *
 * Returns `threshold to orderedPubkeys` for a multisig input whose UTXO
 * matches its witnessScript, or `1 to null` for a structurally valid
 * P2WPKH input (P2WPKH has no fixed pubkey list to pre-declare — the one
 * legitimate pubkey is whichever hash160s to the UTXO program, decided per
 * candidate signature). Returns null when finalization can never succeed
 * for this input regardless of what signatures arrive.
 */
internal fun finalizableInputTemplate(inputMap: PsbtMap): Pair<Int, List<ByteArray>?>? {
    val witnessUtxo = inputMap.witnessUtxo() ?: return null
    val witnessScript = inputMap.witnessScript()
    if (witnessScript != null) {
        val parsed = parseBareMultisigWitnessScript(witnessScript) ?: return null
        val expectedScriptPubKey = byteArrayOf(0x00, 0x20) + sha256(witnessScript)
        if (!witnessUtxo.scriptPubKey.contentEquals(expectedScriptPubKey)) return null
        return parsed.first to parsed.second
    }
    val program = witnessUtxo.scriptPubKey
    if (program.size != 22 || program[0] != 0x00.toByte() || program[1] != 0x14.toByte()) return null
    return 1 to null
}

/**
 * Counts how many of an input's CANDIDATE signatures are cryptographically
 * valid, given the structural [template] [finalizableInputTemplate] already
 * confirmed applies to it. This is the exact "how many real signatures does
 * this input have" question both [finalizeMultisigInput]/
 * [finalizeSingleSigInput] (via their own [isValidPartialSig] calls) and
 * [computePsbtSummary]'s "will this finalize" prediction need answered —
 * shared here so the prediction can never count a signature the finalizer
 * itself would refuse to count.
 */
internal fun countValidSignatures(
    unsignedTx: Transaction,
    inputIndex: Int,
    inputMap: PsbtMap,
    witnessUtxo: TxOut,
    template: Pair<Int, List<ByteArray>?>,
): Int {
    val orderedPubkeys = template.second
    if (orderedPubkeys != null) {
        // Multisig path: finalizableInputTemplate only returns a non-null
        // pubkey list when inputMap.witnessScript() is itself non-null, so
        // this !! is a restatement of that already-established invariant,
        // not a new assumption.
        val witnessScript = inputMap.witnessScript()!!
        val scriptCode = writeCompactSize(witnessScript.size.toLong()) + witnessScript
        val sigLookup = inputMap.partialSigs().associate { it.pubkey.toList() to it.signature }
        return orderedPubkeys.count { pubkey ->
            val signature = sigLookup[pubkey.toList()] ?: return@count false
            isValidPartialSig(unsignedTx, inputIndex, scriptCode, witnessUtxo.valueSats, pubkey, signature)
        }
    }
    // P2WPKH path: the one legitimate pubkey is whichever hash160s to the
    // UTXO program — finalizableInputTemplate already confirmed program is
    // exactly 22 bytes / OP_0 <20-byte push>.
    val expectedHash = witnessUtxo.scriptPubKey.copyOfRange(2, 22)
    val sig = inputMap.partialSigs().firstOrNull { hash160(it.pubkey).contentEquals(expectedHash) }
        ?: return 0
    val scriptCode = byteArrayOf(0x19, 0x76.toByte(), 0xa9.toByte(), 0x14) + expectedHash + byteArrayOf(0x88.toByte(), 0xac.toByte())
    return if (isValidPartialSig(unsignedTx, inputIndex, scriptCode, witnessUtxo.valueSats, sig.pubkey, sig.signature)) 1 else 0
}

/**
 * Finalizes a P2WPKH input by assembling a 2-element witness stack:
 * [signature, pubkey]. Per BIP174, all intermediate entries are stripped.
 * The signature's pubkey must hash to the UTXO's actual P2WPKH program —
 * a partial_sig for any other pubkey would produce an invalid witness, so
 * the input is left untouched instead.
 */
private fun finalizeSingleSigInput(unsignedTx: Transaction, inputIndex: Int, inputMap: PsbtMap): PsbtMap {
    val witnessUtxoEntry = inputMap.entries.find { it.keyType == 0x01 }
        ?: return inputMap
    val witnessUtxo = inputMap.witnessUtxo() ?: return inputMap
    val program = witnessUtxo.scriptPubKey
    if (program.size != 22 || program[0] != 0x00.toByte() || program[1] != 0x14.toByte()) return inputMap
    val expectedHash = program.copyOfRange(2, 22)

    val sig = inputMap.partialSigs().firstOrNull { hash160(it.pubkey).contentEquals(expectedHash) }
        ?: return inputMap

    // Verify the signature actually validates for this pubkey/sighash before
    // trusting it enough to finalize — its mere presence for the right
    // pubkey hash is not proof it's a real signature.
    val scriptCode = byteArrayOf(0x19, 0x76.toByte(), 0xa9.toByte(), 0x14) +
        hash160(sig.pubkey) + byteArrayOf(0x88.toByte(), 0xac.toByte())
    if (!isValidPartialSig(unsignedTx, inputIndex, scriptCode, witnessUtxo.valueSats, sig.pubkey, sig.signature)) {
        return inputMap
    }

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
private fun finalizeMultisigInput(unsignedTx: Transaction, inputIndex: Int, inputMap: PsbtMap, witnessScript: ByteArray): PsbtMap {
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
    val scriptCode = writeCompactSize(witnessScript.size.toLong()) + witnessScript

    // Walk orderedPubkeys in script order, keeping only signatures that are
    // BOTH present for that exact pubkey AND cryptographically valid for
    // this input — a malformed, forged, or wrong-sighash partial_sig must
    // never count toward the threshold, no matter how many of them arrive.
    val matchedSigs = orderedPubkeys.mapNotNull { pubkey ->
        val signature = sigLookup[pubkey.toList()] ?: return@mapNotNull null
        if (isValidPartialSig(unsignedTx, inputIndex, scriptCode, witnessUtxo.valueSats, pubkey, signature)) {
            signature
        } else {
            null
        }
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
