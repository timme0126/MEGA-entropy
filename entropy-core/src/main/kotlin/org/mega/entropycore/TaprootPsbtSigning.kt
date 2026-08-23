package org.mega.entropycore

/** True when [scriptPubKey] is a Taproot (P2TR, witness v1) output:
 * OP_1 (0x51) followed by a 32-byte push (0x20) of the x-only output key.
 * The BIP341 script-version byte 0x51 is exactly OP_1, not a coincidence —
 * see BIP341's own taproot_output_script. */
internal fun isP2trScriptPubKey(scriptPubKey: ByteArray): Boolean =
    scriptPubKey.size == 34 && scriptPubKey[0] == 0x51.toByte() && scriptPubKey[1] == 0x20.toByte()

/** The only Taproot key-path sighash types this app will ever sign:
 * absent (SIGHASH_DEFAULT, 0x00) or explicit SIGHASH_ALL (0x01) — the
 * exact same restriction [validateSighashType] applies to non-Taproot
 * inputs, applied here identically rather than loosened for Taproot. */
internal fun validateTaprootSighashType(sighashType: Long?, inputIndex: Int) {
    if (sighashType != null && sighashType != 0L && sighashType != 1L) {
        throw IllegalArgumentException(
            "PSBT input $inputIndex requests Taproot sighash type 0x${sighashType.toString(16)} — only " +
                "SIGHASH_DEFAULT (0x00, or absent) or SIGHASH_ALL (0x01) is supported.",
        )
    }
}

/** Classifies one PSBT_IN_TAP_BIP32_DERIVATION entry's relationship to
 * [masterKey] — the Taproot counterpart of [classifyFingerprintMatch],
 * sharing the same [FingerprintMatchStatus]/[FingerprintTrustPolicy]
 * types and the same unrecorded-(00000000)-fingerprint handling (an
 * explicit, deliberate product requirement: most watch-only-wallet-built
 * PSBTs never record an origin fingerprint, and this app must still be
 * able to sign those). The one difference from the ECDSA version: the
 * derived pubkey comparison is x-only (32 bytes), not compressed SEC1
 * (33 bytes), since that's what BIP371 derivation entries carry.
 */
internal fun classifyTapFingerprintMatch(derivation: PsbtTapBip32Derivation, masterKey: Bip32ExtendedPrivateKey): FingerprintMatchStatus {
    if (derivation.masterFingerprint.size != 4) return FingerprintMatchStatus.MALFORMED
    if (derivation.masterFingerprint.contentEquals(masterKey.fingerprint())) return FingerprintMatchStatus.VERIFIED_MATCH
    val isUnknownPlaceholder = derivation.masterFingerprint.all { it == 0.toByte() }
    if (isUnknownPlaceholder && derivedXOnlyPubkeyMatchesClaimed(derivation, masterKey)) {
        return FingerprintMatchStatus.UNKNOWN_FINGERPRINT_PUBKEY_MATCH
    }
    return FingerprintMatchStatus.MISMATCH
}

private fun derivedXOnlyPubkeyMatchesClaimed(derivation: PsbtTapBip32Derivation, masterKey: Bip32ExtendedPrivateKey): Boolean = try {
    val child = deriveChildAlongPath(masterKey, derivation.path)
    Schnorr.xOnlyPubKeyFromPrivateKey(child.privateKey).contentEquals(derivation.pubkey)
} catch (e: Exception) {
    false
}

private fun deriveChildAlongPath(masterKey: Bip32ExtendedPrivateKey, path: List<Long>): Bip32ExtendedPrivateKey {
    var child = masterKey
    for (rawIndex in path) {
        val hardened = rawIndex >= HARDENED_OFFSET
        val index = if (hardened) rawIndex - HARDENED_OFFSET else rawIndex
        child = child.deriveChild(index, hardened)
    }
    return child
}

/**
 * Signs every Taproot (P2TR) key-path input this device controls, mirroring
 * [signPsbt]'s structure and security posture exactly, with one structural
 * difference BIP341 itself requires: the Taproot sighash commits to EVERY
 * input's spent amount and scriptPubKey (not just the input being signed —
 * see computeTaprootKeyPathSighash), so unlike signPsbt's per-input
 * independence, this function requires EVERY input's UTXO to be resolvable
 * up front. If even one input's witness_utxo/non_witness_utxo is missing,
 * NO Taproot input is signed (the sighash for any of them would be
 * incomplete/wrong) — the PSBT is returned unchanged rather than signing
 * some inputs with a sighash that doesn't actually commit to the whole
 * transaction a reviewer saw.
 *
 * Only key-path spending is supported (script-path/leaf-script signing is
 * separate, later inheritance work) — an input whose only spending option
 * is a script path is left unsigned here, same "leave unrecognized inputs
 * untouched" posture [signPsbt] already has for scripts it doesn't
 * understand.
 *
 * [randomBytes] supplies BIP340's 32-byte auxiliary randomness for each
 * signature — caller-injected, matching the RNG-boundary pattern
 * ShamirSecretSharing.kt and Schnorr.sign already use; :entropy-core's own
 * securityAudit Gradle task forbids referencing a hidden RNG source here.
 */
internal fun signTaprootPsbt(
    psbt: Psbt,
    masterKey: Bip32ExtendedPrivateKey,
    fingerprintTrustPolicy: FingerprintTrustPolicy = FingerprintTrustPolicy.STRICT,
    randomBytes: () -> ByteArray,
): Psbt {
    // Every input's UTXO must resolve before ANY Taproot input can be
    // signed — see this function's own doc for why. A null anywhere means
    // "leave every Taproot input alone", not "sign what we can."
    val resolvedUtxos = psbt.inputs.mapIndexed { i, inputMap -> resolveInputUtxo(psbt.unsignedTx, i, inputMap) }
    if (resolvedUtxos.any { it == null }) return psbt
    @Suppress("UNCHECKED_CAST")
    val spentOutputs = resolvedUtxos as List<TxOut>

    val signedInputs = psbt.inputs.mapIndexed { i, inputMap ->
        if (inputMap.finalScriptWitness() != null) return@mapIndexed inputMap
        val witnessUtxo = spentOutputs[i]
        if (!isP2trScriptPubKey(witnessUtxo.scriptPubKey)) return@mapIndexed inputMap

        val sighashType = inputMap.sighashType()
        validateTaprootSighashType(sighashType, i)
        val effectiveSighashType = (sighashType ?: 0L).toInt()

        val internalKey = inputMap.tapInternalKey() ?: return@mapIndexed inputMap
        val merkleRoot = inputMap.tapMerkleRoot() ?: ByteArray(0)

        // Find the BIP32 derivation entry for the INTERNAL key specifically
        // (empty leafHashes — see PsbtTapBip32Derivation's own doc) rather
        // than any leaf-script key this input's derivations might also list;
        // this function only ever signs the key path.
        val internalKeyDerivation = inputMap.tapBip32Derivations()
            .firstOrNull { it.leafHashes.isEmpty() && it.pubkey.contentEquals(internalKey) }
            ?: return@mapIndexed inputMap

        val fingerprintStatus = classifyTapFingerprintMatch(internalKeyDerivation, masterKey)
        val fingerprintEligible = fingerprintStatus == FingerprintMatchStatus.VERIFIED_MATCH ||
            (fingerprintStatus == FingerprintMatchStatus.UNKNOWN_FINGERPRINT_PUBKEY_MATCH &&
                fingerprintTrustPolicy == FingerprintTrustPolicy.ALLOW_UNKNOWN_FINGERPRINT_WITH_KEY_MATCH)
        if (!fingerprintEligible) return@mapIndexed inputMap

        val childKey = deriveChildAlongPath(masterKey, internalKeyDerivation.path)
        val derivedInternalXOnly = Schnorr.xOnlyPubKeyFromPrivateKey(childKey.privateKey)
        // Defensive check: the derived key must match both the derivation
        // entry's own claim AND the input's stated internal key — mirrors
        // signPsbt's "ensure the derived pubkey matches the one claimed."
        if (!derivedInternalXOnly.contentEquals(internalKeyDerivation.pubkey) || !derivedInternalXOnly.contentEquals(internalKey)) {
            return@mapIndexed inputMap
        }

        // The UTXO must actually commit to THIS internal key/merkle root —
        // otherwise a signature produced here can never validate. Same
        // "bind the UTXO to the key we're signing with" reasoning
        // signPsbt applies to P2WPKH.
        val tweaked = try {
            TapTweak.tweakPubKey(internalKey, merkleRoot)
        } catch (e: IllegalArgumentException) {
            return@mapIndexed inputMap
        }
        val expectedScriptPubKey = byteArrayOf(0x51, 0x20) + tweaked.outputKeyXOnly
        if (!witnessUtxo.scriptPubKey.contentEquals(expectedScriptPubKey)) return@mapIndexed inputMap

        val tweakedPrivateKey = TapTweak.tweakPrivateKey(childKey.privateKey, internalKey, merkleRoot)
        val sighash = computeTaprootKeyPathSighash(psbt.unsignedTx, i, spentOutputs, effectiveSighashType)
        val signature = Schnorr.sign(tweakedPrivateKey, sighash, randomBytes())
        val sigValue = if (effectiveSighashType == 0) signature else signature + byteArrayOf(effectiveSighashType.toByte())

        // Replace any prior PSBT_IN_TAP_KEY_SIG (re-signing) rather than
        // accumulating duplicates, which parsePsbt's own duplicate-key rule
        // would then refuse to read back.
        val newEntries = inputMap.entries.filterNot { it.keyType == 0x13 } +
            PsbtKeyValue(keyType = 0x13, keyData = ByteArray(0), value = sigValue)
        PsbtMap(newEntries)
    }

    return psbt.copy(inputs = signedInputs)
}
