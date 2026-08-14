package org.mega.entropycore

/**
 * Public façade over signPsbt/finalizePsbt/bip32MasterKeyFromSeed — all
 * three are `internal` (their signatures mention `Bip32ExtendedPrivateKey`,
 * itself internal), so the `app` module, a separate Gradle module, cannot
 * call them directly. This is the only entry point app-layer PSBT-signing
 * UI needs: hand it the scanned PSBT bytes and the already-loaded
 * mnemonic/passphrase, get back the updated PSBT bytes (this device's
 * signature applied to every input it can sign, and any input finalized
 * where enough signatures — possibly including ones already present
 * before this call — now meet its threshold).
 *
 * The result may still be a PARTIALLY signed PSBT (e.g. a multisig input
 * still short of its threshold after this device's signature) — that's
 * expected, not an error. Use [isPsbtFullyFinalized] to check whether the
 * result is ready for [extractFinalTransactionHex].
 *
 * [fingerprintTrustPolicy] defaults to [FingerprintTrustPolicy.STRICT] —
 * every existing caller keeps its current behavior unless it explicitly
 * opts into [FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH],
 * which only single-seed Advanced Mode signing may do, and only after
 * showing the user the required warning and getting explicit confirmation.
 * See [FingerprintTrustPolicy]'s own doc for the full security rationale.
 */
fun signAndFinalizePsbt(
    psbtBytes: ByteArray,
    mnemonicWords: List<String>,
    passphrase: String = "",
    fingerprintTrustPolicy: FingerprintTrustPolicy = FingerprintTrustPolicy.STRICT,
): ByteArray {
    val psbt = parsePsbt(psbtBytes)
    val masterKey = bip32MasterKeyFromSeed(deriveSeed(mnemonicWords, passphrase).bytes)
    val signed = signPsbt(psbt, masterKey, fingerprintTrustPolicy)
    val finalized = finalizePsbt(signed)
    return serializePsbt(finalized)
}

/**
 * Read-only, safe-to-display explanation of why each input of [psbtBytes]
 * was or will be signed by this device — see [PsbtInputSigningDiagnostic]
 * for exactly what's included (never seed words, passphrase, private
 * keys, signatures, or full PSBT contents). Computed independently of
 * [signAndFinalizePsbt] and has no effect on it; call this alongside a
 * failed or unexpectedly-empty signing attempt to show the user (or log)
 * a structured reason instead of a generic failure message.
 */
fun diagnosePsbtSigning(psbtBytes: ByteArray, mnemonicWords: List<String>, passphrase: String = ""): List<PsbtInputSigningDiagnostic> {
    val psbt = parsePsbt(psbtBytes)
    val masterKey = bip32MasterKeyFromSeed(deriveSeed(mnemonicWords, passphrase).bytes)
    return diagnosePsbtInputSigning(psbt, masterKey)
}

/** True when every input has a PSBT_IN_FINAL_SCRIPTWITNESS — i.e. the
 * transaction is ready to extract and broadcast, needing no further
 * cosigner. A multisig PSBT below its threshold, or a PSBT with an input
 * this device doesn't hold a key for, returns false — that's the normal
 * "still needs more cosigners" state, not a malformed PSBT. */
fun isPsbtFullyFinalized(psbtBytes: ByteArray): Boolean {
    val psbt = parsePsbt(psbtBytes)
    return psbt.inputs.all { it.finalScriptWitness() != null }
}

/**
 * Extracts the final, broadcast-ready transaction (BIP144 witness
 * serialization) from a PSBT whose every input is already finalized.
 * Returns null if any input is not yet finalized — see
 * [isPsbtFullyFinalized] to check first, or to distinguish "not ready
 * yet" from a genuinely malformed PSBT (this function doesn't throw for
 * the "not ready" case, only for one it can't parse at all).
 *
 * Every input this app finalizes is native segwit (P2WPKH or P2WSH), so
 * scriptSig is always empty — the unsigned tx's own (already-empty)
 * TxIn.scriptSig is reused unchanged, and the witness field is exactly
 * each input's PSBT_IN_FINAL_SCRIPTWITNESS bytes, which finalizePsbt
 * already serializes in the BIP144 witness-stack wire format.
 */
fun extractFinalTransactionHex(psbtBytes: ByteArray): String? {
    val psbt = parsePsbt(psbtBytes)
    val witnesses = psbt.inputs.map { it.finalScriptWitness() ?: return null }
    return serializeTransactionWithWitness(psbt.unsignedTx, witnesses).toHexString()
}

private fun serializeTransactionWithWitness(tx: Transaction, witnesses: List<ByteArray>): ByteArray {
    var out = writeUInt32LE(tx.version)
    // BIP144 marker/flag: a zero-length input count (0x00) would be ambiguous
    // with a legacy transaction's own varint, so segwit transactions are
    // distinguished by this fixed 0x00 0x01 pair immediately after version.
    out += byteArrayOf(0x00, 0x01)
    out += writeCompactSize(tx.inputs.size.toLong())
    for (input in tx.inputs) {
        out += input.previousTxid
        out += writeUInt32LE(input.previousVout)
        out += writeCompactSize(input.scriptSig.size.toLong()) + input.scriptSig
        out += writeUInt32LE(input.sequence)
    }
    out += writeCompactSize(tx.outputs.size.toLong())
    for (output in tx.outputs) {
        out += writeUInt64LE(output.valueSats)
        out += writeCompactSize(output.scriptPubKey.size.toLong()) + output.scriptPubKey
    }
    // Each witness entry is already a complete BIP144 witness field
    // (compactSize(itemCount) + length-prefixed items) — finalizePsbt built
    // it in exactly this wire format, one per input, in input order.
    for (witness in witnesses) {
        out += witness
    }
    out += writeUInt32LE(tx.locktime)
    return out
}

private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
