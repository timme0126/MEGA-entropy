package org.mega.entropycore

/**
 * Coarse, safe-to-display classification of an input's resolved UTXO —
 * never the actual script bytes, only this classification. UNRESOLVED
 * means neither witness_utxo nor non_witness_utxo could be turned into a
 * spendable output (missing, malformed, or internally contradictory).
 */
enum class PsbtInputScriptKind { P2WPKH, P2WSH_MULTISIG, UNRECOGNIZED, UNRESOLVED }

/**
 * One PSBT_IN_BIP32_DERIVATION entry's relationship to this device's key.
 * [fingerprintHex] and [path] are copied verbatim from the scanned PSBT —
 * both are plaintext fields BIP174 puts there for exactly this purpose,
 * not secrets. [derivedPubkeyMatchesClaimed] is only meaningful when
 * [fingerprintMatchesLoadedKey] is true (a mismatched fingerprint means
 * this device never even attempts to derive along [path]); it stays false
 * otherwise, never null, so a diagnostic list is trivial to scan without
 * every reader re-deriving the same precondition.
 */
data class PsbtInputKeyDiagnostic(
    val fingerprintHex: String,
    val path: List<Long>,
    val fingerprintMatchesLoadedKey: Boolean,
    val derivedPubkeyMatchesClaimed: Boolean,
)

/**
 * Everything safe to report about why one PSBT input was, or was not,
 * signed by this device — computed read-only, without ever deriving or
 * touching a signature. Every field here is derived ONLY from PSBT bytes
 * that are already public (the file the user just scanned) or from this
 * device's own already-computed BIP32 master fingerprint (a public,
 * non-secret identifier, not a seed/passphrase/private key) — never from
 * the mnemonic, passphrase, private key material, or full PSBT contents.
 * Safe to show in a UI message or write to a log.
 */
data class PsbtInputSigningDiagnostic(
    val inputIndex: Int,
    val hasWitnessUtxo: Boolean,
    val hasNonWitnessUtxo: Boolean,
    val utxoResolved: Boolean,
    val scriptKind: PsbtInputScriptKind,
    val utxoMatchesDeclaredScript: Boolean,
    val loadedMasterFingerprintHex: String,
    val alreadyFinalized: Boolean,
    val sighashSupported: Boolean,
    val keys: List<PsbtInputKeyDiagnostic>,
    val existingPartialSigCount: Int,
    val wouldAddSignature: Boolean,
)

/**
 * Read-only prediction of what [signPsbt] will decide for every input of
 * [psbt], given [masterKey] — computed independently of, and without any
 * effect on, the real signing path, so it can be shown to the user
 * (or logged) as a safe explanation even when signing throws or silently
 * signs nothing. Mirrors signPsbt's own gating conditions in order (a-h,
 * see PsbtSigning.kt) closely enough that "wouldAddSignature" here always
 * agrees with what signPsbt would actually do for the same input — but
 * this function itself never throws: any per-input resolution failure
 * (malformed non_witness_utxo, txid mismatch, disagreeing UTXO
 * representations, etc.) is reported as `utxoResolved = false` rather than
 * propagating, since the whole point of this function is to explain a
 * failure, not risk becoming a second one.
 */
internal fun diagnosePsbtInputSigning(psbt: Psbt, masterKey: Bip32ExtendedPrivateKey): List<PsbtInputSigningDiagnostic> {
    val loadedFingerprint = masterKey.fingerprint()
    val loadedFingerprintHex = loadedFingerprint.toHex()

    return psbt.inputs.mapIndexed { index, inputMap ->
        val hasWitnessUtxo = inputMap.entries.any { it.keyType == 0x01 }
        val hasNonWitnessUtxo = inputMap.entries.any { it.keyType == 0x00 }
        val alreadyFinalized = inputMap.finalScriptWitness() != null

        val resolvedUtxo = if (alreadyFinalized) {
            null
        } else {
            try {
                resolveInputUtxo(psbt.unsignedTx, index, inputMap)
            } catch (e: Exception) {
                null
            }
        }

        val witnessScript = inputMap.witnessScript()
        val scriptKind: PsbtInputScriptKind
        val utxoMatchesDeclaredScript: Boolean
        when {
            resolvedUtxo == null -> {
                scriptKind = PsbtInputScriptKind.UNRESOLVED
                utxoMatchesDeclaredScript = false
            }
            witnessScript != null -> {
                scriptKind = PsbtInputScriptKind.P2WSH_MULTISIG
                val expected = byteArrayOf(0x00, 0x20) + sha256(witnessScript)
                utxoMatchesDeclaredScript = resolvedUtxo.scriptPubKey.contentEquals(expected)
            }
            isP2wpkhScriptPubKey(resolvedUtxo.scriptPubKey) -> {
                scriptKind = PsbtInputScriptKind.P2WPKH
                utxoMatchesDeclaredScript = true
            }
            else -> {
                scriptKind = PsbtInputScriptKind.UNRECOGNIZED
                utxoMatchesDeclaredScript = false
            }
        }

        val sighashType = try {
            inputMap.sighashType()
        } catch (e: Exception) {
            null
        }
        val sighashSupported = sighashType == null || sighashType == 1L

        val existingPubkeys = inputMap.partialSigs().map { it.pubkey.toList() }.toSet()

        val keys = inputMap.bip32Derivations().map { derivation ->
            val fingerprintMatches = derivation.masterFingerprint.contentEquals(loadedFingerprint)
            val derivedPubkeyMatches = if (!fingerprintMatches) {
                false
            } else {
                try {
                    var child = masterKey
                    for (rawIndex in derivation.path) {
                        val hardened = rawIndex >= HARDENED_OFFSET
                        val childIndex = if (hardened) rawIndex - HARDENED_OFFSET else rawIndex
                        child = child.deriveChild(childIndex, hardened)
                    }
                    child.compressedPublicKey().contentEquals(derivation.pubkey)
                } catch (e: Exception) {
                    false
                }
            }
            PsbtInputKeyDiagnostic(
                fingerprintHex = derivation.masterFingerprint.toHex(),
                path = derivation.path,
                fingerprintMatchesLoadedKey = fingerprintMatches,
                derivedPubkeyMatchesClaimed = derivedPubkeyMatches,
            )
        }

        // Would signPsbt add a NEW signature for this input? Mirrors its own
        // gating exactly: not already finalized, UTXO resolves and matches
        // its declared script, sighash is supported, and at least one
        // derivation both matches this device's key AND isn't already
        // signed (for P2WPKH, the UTXO program must also hash to that
        // specific derived pubkey — parallels signPsbt step (e)/(f) above).
        val wouldAddSignature = !alreadyFinalized && resolvedUtxo != null && utxoMatchesDeclaredScript && sighashSupported &&
            inputMap.bip32Derivations().any { derivation ->
                derivation.masterFingerprint.contentEquals(loadedFingerprint) &&
                    derivation.pubkey.toList() !in existingPubkeys &&
                    (scriptKind != PsbtInputScriptKind.P2WPKH || resolvedUtxo.scriptPubKey.contentEquals(byteArrayOf(0x00, 0x14) + hash160(derivation.pubkey))) &&
                    keys.any { it.fingerprintHex == derivation.masterFingerprint.toHex() && it.path == derivation.path && it.derivedPubkeyMatchesClaimed }
            }

        PsbtInputSigningDiagnostic(
            inputIndex = index,
            hasWitnessUtxo = hasWitnessUtxo,
            hasNonWitnessUtxo = hasNonWitnessUtxo,
            utxoResolved = resolvedUtxo != null,
            scriptKind = scriptKind,
            utxoMatchesDeclaredScript = utxoMatchesDeclaredScript,
            loadedMasterFingerprintHex = loadedFingerprintHex,
            alreadyFinalized = alreadyFinalized,
            sighashSupported = sighashSupported,
            keys = keys,
            existingPartialSigCount = inputMap.partialSigs().size,
            wouldAddSignature = wouldAddSignature,
        )
    }
}
